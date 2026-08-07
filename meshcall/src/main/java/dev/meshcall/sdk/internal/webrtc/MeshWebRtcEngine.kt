package dev.meshcall.sdk.internal.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
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
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
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

    // Screen share path. Deliberately a *separate* source from the camera: a source is
    // created with its screencast flag fixed for life, and that flag is what tells the
    // encoder to protect resolution over frame rate. Sharing the camera source would keep
    // text sharp only by accident.
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenTextureHelper: SurfaceTextureHelper? = null

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

    /** Which physical camera is streaming right now — drives local preview mirroring. */
    @Volatile private var frontCameraActive: Boolean = config.cameraFacing == MediaConfig.CameraFacing.FRONT

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

    /**
     * What the local tile and every peer should be showing: the screen while sharing, the
     * camera otherwise. Screen share replaces the camera rather than adding a second
     * stream, so there is only ever one outgoing video track.
     */
    val outgoingVideo: VideoTrack?
        get() = screenTrack ?: localVideoTrack

    val isMicEnabled: Boolean get() = micEnabled
    val isCameraEnabled: Boolean get() = cameraEnabled
    val isFrontCamera: Boolean get() = frontCameraActive
    val isScreenSharing: Boolean get() = screenTrack != null
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
        // outgoingVideo, not localVideoTrack: someone joining mid-share must receive the
        // screen, otherwise they sit looking at a camera feed nobody else can see.
        outgoingVideo?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID)) }
        capVideoSenderBitrate(pc)

        peerRecords[peerId] = holder
        MeshLog.d(TAG) { "peer connection created for $peerId" }
        return holder
    }

    /**
     * Enforce [MediaConfig.maxVideoKbps] on the local video sender. The SDP `b=TIAS`
     * line is only a hint; the encoder consults the sender's encoding parameters, so
     * without this the cap is frequently ignored and video bursts well past it (or the
     * encoder runs unbounded and starves the mesh uplink).
     */
    private fun capVideoSenderBitrate(pc: PeerConnection) {
        if (config.maxVideoKbps <= 0) return
        val track = localVideoTrack ?: return
        pc.senders.forEach { sender ->
            if (sender.track() !== track) return@forEach
            val params = sender.parameters
            params.encodings.forEach { it.maxBitrateBps = config.maxVideoKbps * 1000 }
            if (sender.setParameters(params)) {
                MeshLog.d(TAG) { "video sender capped at ${config.maxVideoKbps}kbps" }
            } else {
                MeshLog.w(TAG, "setParameters failed; video sender uncapped")
            }
        }
    }

    /** Emit a remote stream once, no matter which callback surfaced it. */
    private fun publishStream(peerId: String, stream: MediaStream) {
        if (lastStreamIdByPeer.put(peerId, stream.id) == stream.id) return
        // Track counts matter: a stream that arrives with zero video tracks renders as a
        // black tile, which is indistinguishable from an ICE failure without this line.
        MeshLog.i(TAG) {
            "remote stream ${stream.id} from $peerId " +
                "(video=${stream.videoTracks.size} audio=${stream.audioTracks.size})"
        }
        if (stream.videoTracks.isEmpty()) {
            MeshLog.w(TAG, "remote stream from $peerId has no video track — tile will stay black")
        }
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

    // ---- Screen share -------------------------------------------------------------

    /**
     * Start sharing the screen, replacing the camera on every existing peer connection.
     *
     * [permissionData] is the `Intent` returned by the MediaProjection consent dialog; the
     * host has to obtain it, because consent can only be requested from an Activity. A
     * foreground service of type `mediaProjection` must already be running — Android 14
     * refuses to hand out a projection otherwise — which the manager guarantees.
     *
     * No renegotiation happens: `RtpSender.setTrack` swaps the track inside the existing
     * video transceiver, and a same-kind swap needs no new offer/answer. That is the whole
     * reason this replaces the camera instead of adding a second stream.
     */
    fun startScreenShare(permissionData: Intent): Boolean {
        if (_status.value != Status.READY) {
            onError("", "startScreenShare() in ${_status.value}")
            return false
        }
        if (screenTrack != null) return true
        val f = factory ?: return false

        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                // Fired when the user revokes sharing from the system UI rather than from
                // ours. Without this the capture dies but the app keeps claiming to share.
                MeshLog.i(TAG) { "screen share stopped by the system" }
                stopScreenShare()
            }
        }

        val capturer = try {
            ScreenCapturerAndroid(permissionData, projectionCallback)
        } catch (e: Exception) {
            onError("", "Screen capture could not start: ${e.message}")
            return false
        }

        val eglContext = eglBase.eglBaseContext
        // Its own helper: the camera's is owned by the camera capturer and feeding two
        // capturers through one helper deadlocks the texture queue.
        val helper = SurfaceTextureHelper.create("meshcall-screen", eglContext)
        val source = f.createVideoSource(true)
        val track = f.createVideoTrack(SCREEN_TRACK_ID, source)

        try {
            capturer.initialize(helper, appContext, source.capturerObserver)
            val size = screenCaptureSize()
            capturer.startCapture(size.first, size.second, config.frameRate)
        } catch (e: Exception) {
            onError("", "Screen capture failed to start: ${e.message}")
            MeshLog.e(TAG, "screen startCapture failed", e)
            track.dispose()
            source.dispose()
            helper.dispose()
            return false
        }

        screenCapturer = capturer
        screenTextureHelper = helper
        screenSource = source
        screenTrack = track

        swapOutgoingVideo(track)
        // The camera is released while sharing: holding it open drains battery and keeps
        // the privacy indicator lit for a feed nobody is receiving.
        stopCapture()

        MeshLog.i(TAG) { "screen share started" }
        _connectionEvents.tryEmit(ConnectionEvent.ScreenShareChanged(true))
        return true
    }

    /** Stop sharing and put the camera back on every peer connection. Idempotent. */
    fun stopScreenShare() {
        val capturer = screenCapturer ?: return

        swapOutgoingVideo(localVideoTrack)

        screenCapturer = null
        try {
            capturer.stopCapture()
        } catch (e: Exception) {
            MeshLog.d(TAG) { "screen stopCapture: ${e.message}" }
        }
        capturer.dispose()

        screenTrack?.dispose()
        screenTrack = null
        screenSource?.dispose()
        screenSource = null
        screenTextureHelper?.dispose()
        screenTextureHelper = null

        // Only resume the camera if it was meant to be on — a user who shared with the
        // camera already off should not find it switched on when they stop.
        if (cameraEnabled) startCapture()

        MeshLog.i(TAG) { "screen share stopped" }
        _connectionEvents.tryEmit(ConnectionEvent.ScreenShareChanged(false))
    }

    /**
     * Point every peer's video sender at [track].
     *
     * Senders are matched by kind rather than by identity: the attached track changes each
     * time sharing starts or stops, so comparing against a remembered instance would miss
     * exactly the senders that need updating.
     */
    private fun swapOutgoingVideo(track: VideoTrack?) {
        peerRecords.values.forEach { holder ->
            val pc = holder.pc ?: return@forEach
            pc.senders.forEach { sender: RtpSender ->
                if (sender.track()?.kind() != VIDEO_KIND) return@forEach
                // takeOwnership = false: this engine disposes its own tracks, and letting
                // the sender own one would double-free it on the next swap.
                if (!sender.setTrack(track, false)) {
                    MeshLog.w(TAG, "setTrack failed on ${holder.peerId}")
                }
            }
            capVideoSenderBitrate(pc)
        }
    }

    /**
     * Capture size for the screen, scaled down so the long edge fits [MAX_SCREEN_EDGE].
     *
     * A mesh uploads this once per peer, and a modern phone display is well past 1080p —
     * capturing natively would blow the uplink apart for 3-4 participants. The aspect ratio
     * is preserved so the shared screen is never stretched.
     */
    private fun screenCaptureSize(): Pair<Int, Int> {
        val metrics = appContext.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) return config.videoWidth to config.videoHeight

        val longEdge = maxOf(width, height)
        if (longEdge <= MAX_SCREEN_EDGE) return width to height

        val scale = MAX_SCREEN_EDGE.toDouble() / longEdge
        // Rounded to even numbers: H.264 chroma subsampling rejects odd dimensions.
        fun even(value: Double) = (value.toInt() / 2) * 2
        return even(width * scale).coerceAtLeast(2) to even(height * scale).coerceAtLeast(2)
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
        // While sharing, the camera is off the wire entirely — running the capturer would
        // burn battery and light the privacy indicator for frames no one receives. The flag
        // is still recorded so stopping the share restores what the user actually wanted.
        if (!isScreenSharing) {
            if (enabled) startCapture() else stopCapture()
        }
        _connectionEvents.tryEmit(ConnectionEvent.LocalMediaStateChanged(micEnabled, enabled))
    }

    /** Flip front ⇄ back. The capturer restarts itself; no renegotiation is needed. */
    fun switchCamera() {
        val capturer = videoCapturer ?: return
        try {
            capturer.switchCamera(
                object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                        frontCameraActive = isFrontCamera
                        _connectionEvents.tryEmit(ConnectionEvent.CameraFacingChanged(isFrontCamera))
                    }

                    override fun onCameraSwitchError(error: String) {
                        onError("", "Failed to switch camera: $error")
                    }
                },
            )
        } catch (e: Exception) {
            onError("", "Failed to switch camera: ${e.message}")
        }
    }

    private fun startCapture() {
        val capturer = videoCapturer
        if (capturer == null) {
            // Silent here previously: no capturer meant a black self-tile and no clue why.
            MeshLog.w(MeshLog.SCOPE_CAMERA, "startCapture ignored — no capturer was created")
            return
        }
        captureExecutor.execute {
            try {
                capturer.startCapture(config.videoWidth, config.videoHeight, config.frameRate)
                MeshLog.i(MeshLog.SCOPE_CAMERA) {
                    "capture started ${config.videoWidth}x${config.videoHeight}@${config.frameRate}"
                }
            } catch (e: Exception) {
                onError("", "Failed to start camera capture: ${e.message}")
                MeshLog.e(MeshLog.SCOPE_CAMERA, "startCapture failed", e)
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
        val camera2 = Camera2Enumerator.isSupported(appContext)
        val enumerator: CameraEnumerator = if (camera2) {
            Camera2Enumerator(appContext)
        } else {
            Camera1Enumerator(false)
        }

        val devices = enumerator.deviceNames
        MeshLog.i(MeshLog.SCOPE_CAMERA) {
            "enumerator=${if (camera2) "Camera2" else "Camera1"} devices=${devices.size} " +
                devices.joinToString(prefix = "[", postfix = "]") {
                    "$it${if (enumerator.isFrontFacing(it)) ":front" else ":back"}"
                }
        }
        if (devices.isEmpty()) {
            onError("", "Camera enumerator reported no devices")
            return
        }

        val wantFront = config.cameraFacing == MediaConfig.CameraFacing.FRONT
        val deviceId = findCamera(enumerator, wantFront)
        if (deviceId == null) {
            onError("", "No camera available on this device")
            return
        }
        frontCameraActive = enumerator.isFrontFacing(deviceId)
        MeshLog.i(MeshLog.SCOPE_CAMERA) { "selected camera $deviceId (wantFront=$wantFront)" }

        // createCapturer throws rather than returning null on some OEM builds (Samsung
        // in particular). Letting it escape aborts prepareLocalMedia() mid-way and the
        // meeting comes up with no local track at all, which looks like a dead camera.
        val capturer = try {
            enumerator.createCapturer(deviceId, null) as? CameraVideoCapturer
        } catch (e: Exception) {
            onError("", "createCapturer($deviceId) threw: ${e.message}")
            MeshLog.e(MeshLog.SCOPE_CAMERA, "createCapturer($deviceId) threw", e)
            null
        }
        if (capturer == null) {
            onError("", "Unable to create a capturer for camera $deviceId")
            return
        }

        val helper = surfaceTextureHelper
        if (helper == null) {
            onError("", "SurfaceTextureHelper missing; camera cannot be initialized")
            return
        }

        val source = f.createVideoSource(false)
        // Different physical cameras expose different supported-format tables, so the
        // capturer's own "closest match" to (videoWidth, videoHeight) can legitimately
        // differ between front and back — e.g. the back sensor lands on a larger frame
        // than the front one did. Without this, that larger frame is encoded through the
        // same fixed bitrate cap (capVideoSenderBitrate), which reads as a quality drop
        // after switching camera. Clamping the source output pins the encoded resolution
        // to the configured target regardless of which camera actually captured it.
        source.adaptOutputFormat(config.videoWidth, config.videoHeight, config.frameRate)
        videoSource = source
        localVideoTrack = f.createVideoTrack(VIDEO_TRACK_ID, source)
        try {
            capturer.initialize(helper, appContext, source.capturerObserver)
        } catch (e: Exception) {
            onError("", "capturer.initialize failed: ${e.message}")
            MeshLog.e(MeshLog.SCOPE_CAMERA, "capturer.initialize failed", e)
            return
        }
        videoCapturer = capturer
        MeshLog.i(MeshLog.SCOPE_CAMERA) { "camera track ready on $deviceId" }
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

        // Before the peer records are cleared: stopScreenShare walks them to put the camera
        // back, and it also releases the MediaProjection, which otherwise outlives the
        // meeting and leaves the system's "recording screen" indicator on.
        stopScreenShare()

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
        data class CameraFacingChanged(val isFrontCamera: Boolean) : ConnectionEvent()
        data class ScreenShareChanged(val active: Boolean) : ConnectionEvent()
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
        const val SCREEN_TRACK_ID = "meshcall_screen"

        /** `MediaStreamTrack.VIDEO_TRACK_KIND`, without depending on that constant's name. */
        const val VIDEO_KIND = "video"

        /**
         * Longest edge the screen is captured at. A phone display is well past 1080p and a
         * mesh uploads the stream once per peer, so native capture would swamp the uplink.
         */
        const val MAX_SCREEN_EDGE = 1280

        /** Bound the pre-remote-description candidate buffer. */
        const val MAX_PENDING_CANDIDATES = 128
    }
}
