package dev.meshcall.sdk.internal.webrtc

import android.content.Context
import dev.meshcall.sdk.internal.media.MediaConfig
import dev.meshcall.sdk.internal.util.MeshLog
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
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Low-level WebRTC engine for the mesh.
 *
 * Responsibilities:
 *  - Create (once) a shared [PeerConnectionFactory] and the [EglBase] context every
 *    renderer draws against.
 *  - Acquire local audio + video from the device.
 *  - Manage one [PeerConnection] per remote peer and drive offer/answer/ICE.
 *
 * The engine owns every `org.webrtc` type and never leaks them past the SDK internals.
 * In-bound media is published on [remoteStreams], connection progress on
 * [connectionEvents].
 *
 * Lifecycle: construct → [prepareLocalMedia] → [preparePeerConnection] per peer →
 * [dispose]. After [dispose] the instance is inert; build a new engine for a new meeting.
 */
internal class MeshWebRtcEngine(
    private val appContext: Context,
    private val config: MediaConfig,
) {

    /** EGL base context shared by every video renderer. */
    val eglBase: EglBase by lazy { EglBase.create() }

    enum class Status { CREATED, CONFIGURING, READY, DISPOSED }

    private val _status = MutableStateFlow(Status.CREATED)
    val status = _status.asStateFlow()

    // Local media path. Nullable rather than lateinit so a partially-built engine can
    // still be disposed safely (prepareLocalMedia can fail at the camera step).
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null

    /**
     * `CameraVideoCapturer.startCapture`/`stopCapture` block until the camera thread
     * acknowledges, which is an ANR on the main thread. All capture control is serialized
     * onto this single worker instead.
     */
    private val captureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "meshcall-capture")
    }

    // Toggle state, tracked eagerly so reads never need to touch WebRTC.
    @Volatile private var micEnabled: Boolean = config.initialMicOn
    @Volatile private var cameraEnabled: Boolean = config.initialCameraOn

    private val peerRecords = ConcurrentHashMap<String, PeerConnectionHolder>()

    /** Last remote stream id published per peer, so onAddStream/onAddTrack don't double-fire. */
    private val lastStreamIdByPeer = ConcurrentHashMap<String, String>()

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
    val connectionEvents = _connectionEvents.asSharedFlow()

    private val _remoteStreams = MutableSharedFlow<RemoteStreamUpdate>(extraBufferCapacity = 64)
    val remoteStreams = _remoteStreams.asSharedFlow()

    /** Local video track. Present whenever a camera exists, even while the camera is off. */
    val localVideo: VideoTrack?
        get() = localVideoTrack

    val isMicEnabled: Boolean get() = micEnabled
    val isCameraEnabled: Boolean get() = cameraEnabled
    val isDisposed: Boolean get() = _status.value == Status.DISPOSED

    /**
     * One-shot initialization of the media path. Must run before any peer connection is
     * created. Only valid from [Status.CREATED].
     */
    fun prepareLocalMedia() {
        check(_status.value == Status.CREATED) { "prepareLocalMedia() called in ${_status.value}" }
        _status.value = Status.CONFIGURING

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )

        val eglContext = eglBase.eglBaseContext
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
        factory = f

        val source = f.createAudioSource(audioConstraints())
        audioSource = source
        localAudioTrack = f.createAudioTrack(AUDIO_TRACK_ID, source).apply {
            setEnabled(micEnabled)
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("meshcall-camera", eglContext)

        // The camera track is always created, even when the meeting starts camera-off.
        // Creating it lazily on the first toggle would mean the track is missing from
        // every already-negotiated PeerConnection, so remote peers would never see video
        // without a full renegotiation.
        createCameraTrack(f)
        if (config.initialCameraOn) {
            startCapture()
        } else {
            localVideoTrack?.setEnabled(false)
        }

        _status.value = Status.READY
        MeshLog.i(TAG) { "local media ready (mic=$micEnabled camera=$cameraEnabled)" }
    }

    /**
     * Create a peer-connection handle for [peerId], attach the local tracks, and register
     * callbacks. The caller drives negotiation and owns the returned handle.
     */
    fun preparePeerConnection(
        peerId: String,
        onIceCandidate: (peerId: String, candidate: IceCandidate) -> Unit,
        onStreamAdded: (peerId: String) -> Unit,
        onStreamRemoved: (peerId: String) -> Unit,
        onIceReport: (peerId: String, state: String) -> Unit,
    ): PeerConnectionHolder {
        check(_status.value == Status.READY) { "preparePeerConnection() before prepareLocalMedia()" }
        val f = factory ?: error("factory missing in ${_status.value}")

        val holder = PeerConnectionHolder(peerId)
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            keyType = PeerConnection.KeyType.ECDSA
            iceCandidatePoolSize = 4
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                val label = when (state) {
                    PeerConnection.IceConnectionState.CHECKING -> "connecting"
                    PeerConnection.IceConnectionState.CONNECTED -> "connected"
                    PeerConnection.IceConnectionState.COMPLETED -> "completed"
                    PeerConnection.IceConnectionState.DISCONNECTED -> "disconnected"
                    PeerConnection.IceConnectionState.FAILED -> "failed"
                    PeerConnection.IceConnectionState.CLOSED -> "closed"
                    else -> return
                }
                MeshLog.i(TAG) { "ice[$peerId] -> $label" }
                _connectionEvents.tryEmit(ConnectionEvent.IceStateChanged(peerId, label))
                // Report only. Tearing the connection down here would strand the holder
                // in the manager's map and make the link unrecoverable; recovery is the
                // manager's decision.
                onIceReport(peerId, label)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                onIceCandidate(peerId, iceCandidate)
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {}

            override fun onAddStream(mediaStream: MediaStream) {
                publishStream(peerId, mediaStream)
                onStreamAdded(peerId)
            }

            override fun onRemoveStream(mediaStream: MediaStream) {
                lastStreamIdByPeer.remove(peerId)
                _remoteStreams.tryEmit(RemoteStreamUpdate(peerId, null))
                onStreamRemoved(peerId)
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                // Reserved for in-meeting text chat (VC-007).
            }

            override fun onRenegotiationNeeded() {}

            /**
             * The Unified Plan callback. `onAddStream` is a Plan B shim that the Java
             * wrapper still fires for compatibility, but it is not guaranteed — handling
             * both is what makes remote video reliable across WebRTC revisions.
             * [publishStream] dedupes so the two paths cannot double-bind a renderer.
             */
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                mediaStreams.firstOrNull()?.let { publishStream(peerId, it) }
            }

            override fun onTrack(transceiver: RtpTransceiver) {}
        }

        val pc = f.createPeerConnection(rtcConfig, observer)
            ?: error("createPeerConnection() returned null for $peerId")
        holder.bind(pc)

        localAudioTrack?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID)) }
        localVideoTrack?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID)) }

        peerRecords[peerId] = holder
        MeshLog.d(TAG) { "peer connection created for $peerId" }
        return holder
    }

    /** Emit a remote stream once, no matter which callback surfaced it. */
    private fun publishStream(peerId: String, stream: MediaStream) {
        if (lastStreamIdByPeer.put(peerId, stream.id) == stream.id) return
        MeshLog.d(TAG) { "remote stream ${stream.id} from $peerId" }
        _remoteStreams.tryEmit(RemoteStreamUpdate(peerId, stream))
    }

    private fun iceServers(): List<PeerConnection.IceServer> = config.iceServers.map { server ->
        PeerConnection.IceServer.builder(server.urls)
            .apply {
                server.username?.let { setUsername(it) }
                server.credential?.let { setPassword(it) }
            }
            .createIceServer()
    }

    /**
     * Handle for one peer connection.
     *
     * Buffers inbound ICE candidates until the remote description is applied: WebRTC
     * rejects `addIceCandidate` before `setRemoteDescription`, and because the broker
     * relays candidates and SDP over the same socket a candidate routinely arrives first.
     * Dropping those is the classic "signaling looks fine but ICE never connects" bug.
     */
    class PeerConnectionHolder internal constructor(internal val peerId: String) {
        internal var pc: PeerConnection? = null

        @Volatile internal var remoteDescriptionSet = false

        @Volatile internal var disposed = false

        internal val pendingCandidates = ArrayDeque<IceCandidate>()

        internal fun bind(connection: PeerConnection) {
            pc = connection
        }

        internal fun dispose() {
            if (disposed) return
            disposed = true
            synchronized(pendingCandidates) { pendingCandidates.clear() }
            try {
                pc?.dispose()
            } catch (e: Exception) {
                MeshLog.w(TAG, "dispose($peerId) failed", e)
            }
            pc = null
        }
    }

    /** As the polite side, create and send an offer. */
    fun createOffer(
        holder: PeerConnectionHolder,
        onOffer: (peerId: String, sdp: SessionDescription) -> Unit,
    ) {
        val pc = holder.pc ?: return
        pc.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    val capped = applyBitrateCap(desc)
                    pc.setLocalDescription(
                        object : SdpObserver {
                            override fun onCreateSuccess(d: SessionDescription) {}
                            override fun onCreateFailure(error: String) {}
                            override fun onSetSuccess() = onOffer(holder.peerId, capped)
                            override fun onSetFailure(error: String) =
                                onError(holder.peerId, "setLocalDescription(offer) failed: $error")
                        },
                        capped,
                    )
                }

                override fun onCreateFailure(error: String) =
                    onError(holder.peerId, "createOffer failed: $error")

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            },
            MediaConstraints(),
        )
    }

    /** As the impolite side, accept an offer: set remote, create + send an answer. */
    fun handleOffer(
        holder: PeerConnectionHolder,
        sdp: SessionDescription,
        onAnswer: (peerId: String, sdp: SessionDescription) -> Unit,
    ) {
        val pc = holder.pc ?: return
        pc.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) {}
                override fun onCreateFailure(error: String) {}

                override fun onSetSuccess() {
                    drainPendingCandidates(holder)
                    pc.createAnswer(
                        object : SdpObserver {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                val capped = applyBitrateCap(answer)
                                pc.setLocalDescription(
                                    object : SdpObserver {
                                        override fun onCreateSuccess(d: SessionDescription) {}
                                        override fun onCreateFailure(error: String) {}
                                        override fun onSetSuccess() = onAnswer(holder.peerId, capped)
                                        override fun onSetFailure(error: String) = onError(
                                            holder.peerId,
                                            "setLocalDescription(answer) failed: $error",
                                        )
                                    },
                                    capped,
                                )
                            }

                            override fun onCreateFailure(error: String) =
                                onError(holder.peerId, "createAnswer failed: $error")

                            override fun onSetSuccess() {}
                            override fun onSetFailure(error: String) {}
                        },
                        MediaConstraints(),
                    )
                }

                override fun onSetFailure(error: String) =
                    onError(holder.peerId, "setRemoteDescription(offer) failed: $error")
            },
            sdp,
        )
    }

    fun handleAnswer(holder: PeerConnectionHolder, sdp: SessionDescription) {
        val pc = holder.pc ?: return
        pc.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) {}
                override fun onCreateFailure(error: String) {}
                override fun onSetSuccess() = drainPendingCandidates(holder)
                override fun onSetFailure(error: String) =
                    onError(holder.peerId, "setRemoteDescription(answer) failed: $error")
            },
            sdp,
        )
    }

    /** Add a remote candidate, or hold it until the remote description lands. */
    fun addIceCandidate(holder: PeerConnectionHolder, candidate: IceCandidate) {
        if (holder.disposed) return
        if (!holder.remoteDescriptionSet) {
            synchronized(holder.pendingCandidates) {
                if (holder.pendingCandidates.size < MAX_PENDING_CANDIDATES) {
                    holder.pendingCandidates.addLast(candidate)
                }
            }
            return
        }
        holder.pc?.addIceCandidate(candidate)
    }

    private fun drainPendingCandidates(holder: PeerConnectionHolder) {
        holder.remoteDescriptionSet = true
        val drained = synchronized(holder.pendingCandidates) {
            if (holder.pendingCandidates.isEmpty()) return
            val copy = holder.pendingCandidates.toList()
            holder.pendingCandidates.clear()
            copy
        }
        MeshLog.d(TAG) { "applying ${drained.size} buffered candidate(s) for ${holder.peerId}" }
        val pc = holder.pc ?: return
        drained.forEach(pc::addIceCandidate)
    }

    /** Forget a peer's handle without disposing it twice. */
    fun releasePeer(peerId: String) {
        peerRecords.remove(peerId)?.dispose()
        lastStreamIdByPeer.remove(peerId)
    }

    // ---- Local media toggles ----------------------------------------------------

    fun enableMic(enabled: Boolean) {
        micEnabled = enabled
        localAudioTrack?.setEnabled(enabled)
        _connectionEvents.tryEmit(ConnectionEvent.LocalMediaStateChanged(enabled, cameraEnabled))
    }

    /**
     * Toggle the camera. The track itself always exists and stays attached to every peer
     * connection, so this only starts/stops capture and flips the track — no renegotiation.
     */
    fun enableCamera(enabled: Boolean) {
        cameraEnabled = enabled
        localVideoTrack?.setEnabled(enabled)
        if (enabled) startCapture() else stopCapture()
        _connectionEvents.tryEmit(ConnectionEvent.LocalMediaStateChanged(micEnabled, enabled))
    }

    /** Flip front ⇄ back. The capturer restarts itself; no renegotiation is needed. */
    fun switchCamera() {
        val capturer = videoCapturer ?: return
        try {
            capturer.switchCamera(null)
        } catch (e: Exception) {
            onError("", "Failed to switch camera: ${e.message}")
        }
    }

    private fun startCapture() {
        val capturer = videoCapturer ?: return
        captureExecutor.execute {
            try {
                capturer.startCapture(config.videoWidth, config.videoHeight, config.frameRate)
            } catch (e: Exception) {
                onError("", "Failed to start camera capture: ${e.message}")
            }
        }
    }

    private fun stopCapture() {
        val capturer = videoCapturer ?: return
        captureExecutor.execute {
            try {
                capturer.stopCapture()
            } catch (e: Exception) {
                // Already stopped or released — not worth surfacing.
                MeshLog.d(TAG) { "stopCapture ignored: ${e.message}" }
            }
        }
    }

    private fun onError(peerId: String, message: String) {
        MeshLog.e(TAG, if (peerId.isEmpty()) message else "$peerId: $message")
        _connectionEvents.tryEmit(ConnectionEvent.LocalError(peerId, message))
    }

    /** Build the camera capturer + local video track (capture is started separately). */
    private fun createCameraTrack(f: PeerConnectionFactory) {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(appContext)) {
            Camera2Enumerator(appContext)
        } else {
            Camera1Enumerator(false)
        }
        val deviceId = findCamera(enumerator, config.cameraFacing == MediaConfig.CameraFacing.FRONT)
        if (deviceId == null) {
            onError("", "No camera available on this device")
            return
        }

        val capturer = enumerator.createCapturer(deviceId, null) as? CameraVideoCapturer
        if (capturer == null) {
            onError("", "Unable to create a capturer for camera $deviceId")
            return
        }

        val helper = surfaceTextureHelper ?: return
        val source = f.createVideoSource(false)
        videoSource = source
        localVideoTrack = f.createVideoTrack(VIDEO_TRACK_ID, source)
        capturer.initialize(helper, appContext, source.capturerObserver)
        videoCapturer = capturer
        MeshLog.d(TAG) { "camera track ready on $deviceId" }
    }

    /**
     * Prefer the requested facing, but fall back to any available camera rather than
     * failing the whole meeting — plenty of tablets and emulators expose only one.
     */
    private fun findCamera(enumerator: CameraEnumerator, frontFacing: Boolean): String? {
        val devices = enumerator.deviceNames
        return devices.firstOrNull { enumerator.isFrontFacing(it) == frontFacing }
            ?: devices.firstOrNull()
    }

    private fun audioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
    }

    private fun applyBitrateCap(description: SessionDescription): SessionDescription =
        SessionDescription(
            description.type,
            SdpTransform.applyVideoBitrateCap(description.description, config.maxVideoKbps),
        )

    /**
     * Tear down all media + peer state. Idempotent, and safe even when
     * [prepareLocalMedia] never ran or failed part-way through.
     */
    fun dispose() {
        if (_status.value == Status.DISPOSED) return
        _status.value = Status.DISPOSED

        peerRecords.values.forEach { it.dispose() }
        peerRecords.clear()
        lastStreamIdByPeer.clear()

        // stopCapture must complete before the capturer is disposed, so this one call
        // stays synchronous — by now we are off the UI thread's critical path anyway.
        videoCapturer?.let { capturer ->
            try {
                capturer.stopCapture()
            } catch (e: Exception) {
                MeshLog.d(TAG) { "stopCapture during dispose: ${e.message}" }
            }
            try {
                capturer.dispose()
            } catch (e: Exception) {
                MeshLog.w(TAG, "capturer dispose failed", e)
            }
        }
        videoCapturer = null
        captureExecutor.shutdown()

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        localAudioTrack?.let {
            it.setEnabled(false)
            it.dispose()
        }
        localAudioTrack = null
        localVideoTrack = null

        audioSource?.dispose()
        audioSource = null
        videoSource?.dispose()
        videoSource = null

        factory?.dispose()
        factory = null

        eglBase.release()
        MeshLog.i(TAG) { "engine disposed" }
    }

    /** Connection-level event forwarded to the manager. */
    sealed class ConnectionEvent {
        data class IceStateChanged(val peerId: String, val state: String) : ConnectionEvent()
        data class LocalMediaStateChanged(val micOn: Boolean, val camOn: Boolean) : ConnectionEvent()
        data class LocalError(val peerId: String, val error: String) : ConnectionEvent() {
            override fun toString(): String = error
        }
    }

    /** A stream attached (or detached, when [stream] is null) for a peer. */
    data class RemoteStreamUpdate(val peerId: String, val stream: MediaStream?)

    private companion object {
        const val TAG = "Engine"
        const val LOCAL_STREAM_ID = "meshcall_stream"
        const val AUDIO_TRACK_ID = "meshcall_audio"
        const val VIDEO_TRACK_ID = "meshcall_video"

        /** Bound the pre-remote-description candidate buffer. */
        const val MAX_PENDING_CANDIDATES = 128
    }
}
