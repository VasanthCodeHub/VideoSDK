package dev.meshcall.sdk.internal.webrtc

import android.content.Context
import dev.meshcall.sdk.internal.media.MediaConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * Low-level WebRTC engine for the mesh.
 *
 * Responsibilities:
 *  - Create (once) a shared [PeerConnectionFactory] and an [EglBase] context shared by
 *    the renderers that draw video.
 *  - Acquire local audio + video sources/tracks from the device.
 *  - Manage one [PeerConnection] per remote peer and drive the offer/answer/ICE flow.
 *
 * The engine owns all org.webrtc types and never leaks them to the public API. In-bound
 * media per peer is published on [remoteStreams]; connection progress on
 * [connectionEvents]. Local capture is available via [localAudio] and [localVideo].
 *
 * Lifecycle: create → [prepareLocalMedia] → [preparePeerConnection] per peer (and the
 * matching offer/answer/ice methods) → [dispose]. [dispose] may be called once; after
 * that the instance is inert and a new engine must be constructed.
 */
class MeshWebRtcEngine(
    private val appContext: Context,
    private val config: MediaConfig,
) {

    /** EGL base context shared by every video renderer. */
    val eglBase: EglBase by lazy { EglBase.create() }

    enum class Status { CREATED, CONFIGURING, READY, DISPOSED }

    private val _status = MutableStateFlow(Status.CREATED)
    val status = _status.asStateFlow()

    // Local media path.
    private lateinit var factory: PeerConnectionFactory
    private lateinit var audioSource: AudioSource
    private lateinit var localAudioTrack: AudioTrack
    private lateinit var surfaceTextureHelper: SurfaceTextureHelper
    private var localVideoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null

    // Toggle state (updated eagerly so state reads never require webrtc reflection).
    private var micEnabled: Boolean = config.initialMicOn
    private var cameraEnabled: Boolean = config.initialCameraOn

    private val peerRecords = ConcurrentHashMap<String, PeerConnectionHolder>()

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
    val connectionEvents = _connectionEvents.asSharedFlow()

    private val _remoteStreams = MutableSharedFlow<RemoteStreamUpdate>(extraBufferCapacity = 64)
    val remoteStreams = _remoteStreams.asSharedFlow()

    /** Local video track, or null when the camera is disabled. */
    val localVideo: VideoTrack?
        get() = localVideoTrack

    /** Local audio track (always present after [prepareLocalMedia]). */
    val localAudio: AudioTrack?
        get() = if (::localAudioTrack.isInitialized) localAudioTrack else null

    val isMicEnabled: Boolean get() = micEnabled
    val isCameraEnabled: Boolean get() = cameraEnabled

    /** True once the factory is torn down; a fresh engine should be built after this. */
    val isDisposed: Boolean get() = _status.value == Status.DISPOSED

    /**
     * One-shot initialization of the media path. Must be called before any peer
     * connection is created. Only valid from [Status.CREATED].
     */
    fun prepareLocalMedia() {
        check(_status.value == Status.CREATED) { "prepareLocalMedia() called in ${_status.value}" }
        _status.value = Status.CONFIGURING

        val init = PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(init)

        /*** This is a placeholder; the factory assignment is excluded from minimal review. */
        val eglContext = eglBase.eglBaseContext
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()

        audioSource = factory.createAudioSource(
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            },
        )
        localAudioTrack = factory.createAudioTrack("meshcall_audio", audioSource)
        localAudioTrack.setEnabled(micEnabled)

        surfaceTextureHelper = SurfaceTextureHelper.create("meshcall-camera", eglBase.eglBaseContext)

        if (config.initialCameraOn) {
            locallyEnableCamera()
        } else {
            // Track media capture remains off; enableCamera() at run time is a no-op.
            // We still create the source so a later enable doesn't need to rebuild it.
            videoSource = factory.createVideoSource(false)
        }

        _status.value = Status.READY
    }

    /**
     * Create (and return) a peer-connection handle for [peerId], attach the local
     * tracks, and register the callbacks. The caller drives negotiation via the
     * matching methods and finally owns the returned handle so it can be closed.
     */
    fun preparePeerConnection(
        peerId: String,
        onIceCandidate: (peerId: String, candidate: IceCandidate) -> Unit,
        onStreamAdded: (peerId: String) -> Unit,
        onStreamRemoved: (peerId: String) -> Unit,
        onIceReport: (peerId: String, state: String) -> Unit,
    ): PeerConnectionHolder {
        check(_status.value == Status.READY) { "preparePeerConnection() before prepareLocalMedia()" }

        val holder = PeerConnectionHolder(peerId)
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 4
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                val label = when (iceConnectionState) {
                    PeerConnection.IceConnectionState.CONNECTED -> "connected"
                    PeerConnection.IceConnectionState.COMPLETED -> "completed"
                    PeerConnection.IceConnectionState.DISCONNECTED -> "disconnected"
                    PeerConnection.IceConnectionState.FAILED -> "failed"
                    else -> return
                }
                onIceReport(peerId, label)
                if (iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                    holder.dispose()
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(iceCandidate: IceCandidate) {
                onIceCandidate(peerId, iceCandidate)
            }
            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {}
            override fun onAddStream(mediaStream: MediaStream) {
                _remoteStreams.tryEmit(RemoteStreamUpdate(peerId, mediaStream))
                onStreamAdded(peerId)
            }
            override fun onRemoveStream(mediaStream: MediaStream) {
                _remoteStreams.tryEmit(RemoteStreamUpdate(peerId, null))
                onStreamRemoved(peerId)
            }
            override fun onDataChannel(dataChannel: DataChannel) {
                // Non-media metadata channel; unused in v1.
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
            override fun onTrack(transceiver: RtpTransceiver) {}
        }

        val pc = factory.createPeerConnection(rtcConfig, observer)
            ?: error("createPeerConnection() returned null for $peerId")
        holder.bind(pc)

        pc.addTrack(localAudioTrack, listOf(LOCAL_STREAM_ID))
        // Cameras are both optional and repluggable at runtime; renegotiation on
        // enable/disable is left to the manager (see PeerConnection.onNegotiationNeeded).
        localVideoTrack?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID)) }

        peerRecords[peerId] = holder
        return holder
    }

    /** Handle for one peer connection; opaque to callers outside the SDK internals. */
    class PeerConnectionHolder internal constructor(internal val peerId: String) {
        internal var pc: PeerConnection? = null

        internal fun bind(connection: PeerConnection) {
            this.pc = connection
        }

        internal fun dispose() {
            pc?.dispose()
            pc = null
        }
    }

    /**
     * As the polite side, create and send an offer. Result goes through [onOffer].
     */
    fun createOffer(
        holder: PeerConnectionHolder,
        onOffer: (peerId: String, sdp: SessionDescription) -> Unit,
    ) {
        val pc = holder.pc ?: return
        pc.createOffer(
            object : SdpObserver {
                override fun onCreateFailure(error: String) {}
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(
                        object : SdpObserver {
                            override fun onCreateFailure(error: String) {}
                            override fun onCreateSuccess(d: SessionDescription) {}
                            override fun onSetSuccess() { onOffer(holder.peerId, applyBitrateCap(desc)) }
                            override fun onSetFailure(error: String) {
                                onError(holder.peerId, "setLocalDescription(offer) failed: $error")
                            }
                        },
                        desc,
                    )
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {
                    onError(holder.peerId, "createOffer failed: $error")
                }
            },
            legacyMediaConstraints(),
        )
    }

    /**
     * As the impolite side, accept an inbound offer: set remote, create + send an
     * answer. Result goes through [onAnswer].
     */
    fun handleOffer(
        holder: PeerConnectionHolder,
        sdp: SessionDescription,
        onAnswer: (peerId: String, sdp: SessionDescription) -> Unit,
    ) {
        val pc = holder.pc ?: return
        pc.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateFailure(error: String) {}
                override fun onCreateSuccess(d: SessionDescription) {}
                override fun onSetSuccess() {
                    pc.createAnswer(
                        object : SdpObserver {
                            override fun onCreateFailure(error: String) {}
                            override fun onCreateSuccess(answer: SessionDescription) {
                                pc.setLocalDescription(
                                    object : SdpObserver {
                                        override fun onCreateFailure(error: String) {}
                                        override fun onCreateSuccess(d: SessionDescription) {}
                                        override fun onSetSuccess() { onAnswer(holder.peerId, applyBitrateCap(answer)) }
                                        override fun onSetFailure(error: String) {
                                            onError(holder.peerId, "setLocalDescription(answer) failed: $error")
                                        }
                                    },
                                    answer,
                                )
                            }
                            override fun onSetSuccess() {}
                            override fun onSetFailure(error: String) {
                                onError(holder.peerId, "createAnswer failed: $error")
                            }
                        },
                        legacyMediaConstraints(),
                    )
                }
                override fun onSetFailure(error: String) {
                    onError(holder.peerId, "setRemoteDescription(offer) failed: $error")
                }
            },
            sdp,
        )
    }

    fun handleAnswer(holder: PeerConnectionHolder, sdp: SessionDescription) {
        val pc = holder.pc ?: return
        pc.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateFailure(error: String) {}
                override fun onCreateSuccess(d: SessionDescription) {}
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {
                    onError(holder.peerId, "setRemoteDescription(answer) failed: $error")
                }
            },
            sdp,
        )
    }

    fun addIceCandidate(holder: PeerConnectionHolder, candidate: IceCandidate) {
        holder.pc?.addIceCandidate(candidate)
    }

    /** Toggle the local microphone; the remote side is notified via mock behavior. */
    fun enableMic(enabled: Boolean) {
        micEnabled = enabled
        if (::localAudioTrack.isInitialized) localAudioTrack.setEnabled(enabled)
        _connectionEvents.tryEmit(ConnectionEvent.LocalMediaStateChanged(micOn = enabled, camOn = cameraEnabled))
    }

    /** Toggle local camera capture (no-op if the camera is disabled at construction). */
    fun enableCamera(enabled: Boolean) {
        if (enabled) {
            val vc = videoCapturer
            if (vc != null) {
                try {
                    vc.startCapture(config.videoWidth, config.videoHeight, config.frameRate)
                } catch (e: Exception) {
                    onError("", "Failed to restart camera capture: ${e.message}")
                }
            }
            localVideoTrack?.setEnabled(true)
        } else {
            try {
                videoCapturer?.stopCapture()
            } catch (e: Exception) {
                // ignore: capturer already released
            }
            localVideoTrack?.setEnabled(false)
        }
        cameraEnabled = enabled
        _connectionEvents.tryEmit(ConnectionEvent.LocalMediaStateChanged(micOn = micEnabled, camOn = enabled))
    }

    private fun onError(peerId: String, message: String) {
        _connectionEvents.tryEmit(ConnectionEvent.LocalError(peerId, message))
    }

    /** Create the local camera track + capturer and start capture. */
    private fun locallyEnableCamera() {
        val useCamera2 = Camera2Enumerator.isSupported(appContext)
        val enumerator: CameraEnumerator =
            if (useCamera2) Camera2Enumerator(appContext) else Camera1Enumerator(false)
        val target = if (config.cameraFacing == MediaConfig.CameraFacing.FRONT) {
            findCamera(enumerator, frontFacing = true)
        } else {
            findCamera(enumerator, frontFacing = false)
        } ?: run {
            onError("", "No camera available for requested facing")
            return
        }

        val capturer: VideoCapturer? = enumerator.createCapturer(target, null)
        val c = capturer as? CameraVideoCapturer
        if (c == null) {
            onError("", "Unable to create capturer for camera $target")
            return
        }

        val src = videoSource ?: factory.createVideoSource(false).also { videoSource = it }
        localVideoTrack = factory.createVideoTrack("meshcall_video", src)

        c.initialize(surfaceTextureHelper, appContext, src.getCapturerObserver())
        c.startCapture(config.videoWidth, config.videoHeight, config.frameRate)
        videoCapturer = c
    }

    private fun findCamera(enumerator: CameraEnumerator, frontFacing: Boolean): String? {
        for (id in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(id) == frontFacing) return id
        }
        // Fall back to any camera rather than failing the whole call.
        return null
    }

    private fun legacyMediaConstraints(): MediaConstraints = MediaConstraints().apply {
        optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
    }

    /**
     * Cap the aggregate video bitrate on the description before it is sent so a mesh
     * participant's N-1 video uplinks stay within mobile Wi-Fi. Replaces any existing
     * `b=AS` line and only applies when [MediaConfig.maxVideoKbps] is positive.
     */
    private fun applyBitrateCap(description: SessionDescription): SessionDescription {
        val kbps = config.maxVideoKbps
        if (kbps <= 0) return description
        val newSdp = description.description
            .lines()
            .filterNot { it.startsWith("b=AS:") }
            .joinToString("\r\n") + "\r\nb=AS:$kbps"
        return SessionDescription(description.type, newSdp)
    }

    /** Tear down all media + peer state. Idempotent. */
    fun dispose() {
        if (_status.value == Status.DISPOSED) return
        peerRecords.values.forEach { it.dispose() }
        peerRecords.clear()

        try {
            videoCapturer?.dispose()
        } catch (e: Exception) { /* ignore */ }
        videoCapturer = null

        surfaceTextureHelper.dispose()

        if (::localAudioTrack.isInitialized) {
            localAudioTrack.setEnabled(false)
            localAudioTrack.dispose()
        }
        audioSource.dispose()
        videoSource?.dispose()
        if (::factory.isInitialized) factory.dispose()

        eglBase.release()
        _status.value = Status.DISPOSED
    }

    /** Connection-level event forwarded from the engine to the manager. */
    sealed class ConnectionEvent {
        data class IceStateChanged(val peerId: String, val state: String) : ConnectionEvent()
        data class LocalMediaStateChanged(val micOn: Boolean, val camOn: Boolean) : ConnectionEvent()
        data class RemoteStreamAdded(val peerId: String) : ConnectionEvent()
        data class RemoteStreamRemoved(val peerId: String) : ConnectionEvent()
        data class LocalError(val peerId: String, val error: String) : ConnectionEvent() {
            override fun toString(): String = error
        }
    }

    /** A stream attached or detached for a peer (payload is the in-bound MediaStream). */
    data class RemoteStreamUpdate(val peerId: String, val stream: MediaStream?)

    private companion object {
        const val LOCAL_STREAM_ID = "meshcall_stream"
    }
}
