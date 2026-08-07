package dev.meshcall.sdk.internal.mesh

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.meshcall.sdk.api.Admission
import dev.meshcall.sdk.api.AudioRoute
import dev.meshcall.sdk.api.JoinRequest
import dev.meshcall.sdk.api.LocalMediaState
import dev.meshcall.sdk.internal.media.AudioRouteController
import dev.meshcall.sdk.internal.media.MediaConfig
import dev.meshcall.sdk.internal.media.MeshScreenShareService
import dev.meshcall.sdk.internal.signaling.SignalEvent
import dev.meshcall.sdk.internal.signaling.SignalingClient
import dev.meshcall.sdk.internal.signaling.SignalingSchema
import dev.meshcall.sdk.internal.signaling.SocketIOSignalingClient
import dev.meshcall.sdk.internal.util.MeshLog
import dev.meshcall.sdk.internal.webrtc.MeshWebRtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Coordinates signaling, the WebRTC mesh, and local media for one meeting session.
 *
 * The internal nerve center: consumes [SignalEvent]s, drives [MeshWebRtcEngine], and
 * publishes normalized state the view layer renders. Everything is multiplexed onto a
 * single confined dispatcher so WebRTC callbacks — which arrive on arbitrary binder
 * threads — never race each other.
 */
internal class MeshMeetingManager(
    private val appContext: Context,
    private val brokerUrl: String,
    private val userId: String,
    private val userName: String,
    /** Base64 thumbnail broadcast to peers and used for our own tile's placeholder. */
    val avatarBase64: String? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var engine: MeshWebRtcEngine? = null

    @Volatile private var signaling: SignalingClient? = null

    private val connections = LinkedHashMap<String, MeshWebRtcEngine.PeerConnectionHolder>()

    /** Roster names in arrival order, so tiles keep a stable position between updates. */
    private val remoteNames = LinkedHashMap<String, String>()

    /** Avatar thumbnail per peer, keyed the same as [remoteNames]; null means none chosen. */
    private val remoteAvatars = HashMap<String, String?>()

    /** Last known media state per peer, so roster rebuilds don't drop "muted" indicators. */
    private val remoteMedia = HashMap<String, Pair<Boolean, Boolean>>()

    /** Latest ICE label per peer, surfaced as each tile's connection dot. */
    private val iceStateByPeer = HashMap<String, String>()

    /** Re-link attempts per peer after an ICE failure; bounded so a dead peer can't spin. */
    private val relinkAttempts = HashMap<String, Int>()

    // ---- Active-speaker detection ----------------------------------------------
    // RMS computed from each remote audio track (AudioTrackSink) feeds _speakerId.

    /** Last RMS (0..1) per peer, written on the WebRTC audio thread. */
    private val speakerLevels = ConcurrentHashMap<String, Float>()

    /** Track + sink per peer so the sink can actually be detached later. */
    private val audioSinks = HashMap<String, Pair<AudioTrack, AudioTrackSink>>()

    private var lastSpeakerActiveAt = 0L
    private var speakerJob: Job? = null

    private val _speakerId = MutableStateFlow<String?>(null)
    val speakerId = _speakerId.asStateFlow()

    // ---- Observable state ------------------------------------------------------

    private val _session = MutableStateFlow<Session>(Session.Idle)
    val session = _session.asStateFlow()

    private val _peers = MutableStateFlow<List<MeetingPeer>>(emptyList())
    val peers = _peers.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errors = _errors.asSharedFlow()

    private val _mediaEvents = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 32)
    val mediaEvents = _mediaEvents.asSharedFlow()

    private val _signalingConnected = MutableStateFlow(false)
    val signalingConnected = _signalingConnected.asStateFlow()

    /** Authoritative local mic/camera state — the single source of truth for the controls. */
    private val _localMedia = MutableStateFlow(LocalMediaState())
    val localMedia = _localMedia.asStateFlow()

    /** True while the front camera is the one streaming — drives local preview mirroring. */
    private val _frontCameraActive = MutableStateFlow(true)
    val frontCameraActive = _frontCameraActive.asStateFlow()

    /** True while this device is sharing its screen in place of its camera. */
    private val _screenSharing = MutableStateFlow(false)
    val screenSharing = _screenSharing.asStateFlow()

    /**
     * Output routing. Built once per manager rather than per session so a route the user
     * picked survives a rejoin, and so leave() has something to restore the audio mode with
     * even when the session never came up.
     */
    private val audioRouter = AudioRouteController(appContext)
    val audioRoute = audioRouter.state

    private var signalingJob: Job? = null
    private var localVideoTrack: VideoTrack? = null

    /**
     * Emits the meeting id when the broker refuses the join because no such meeting is
     * live. Terminal: nothing else follows, and the host is expected to leave the screen.
     */
    private val _meetingNotFound = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)
    val meetingNotFound = _meetingNotFound.asSharedFlow()

    /** Where we stand with the meeting's door. Only ever leaves [Admission.JOINING] once. */
    private val _admission = MutableStateFlow(Admission.JOINING)
    val admission = _admission.asStateFlow()

    /**
     * People waiting to be let into our private meeting, oldest first — only ever
     * non-empty for the host, since the broker sends knocks nowhere else.
     */
    private val _joinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())
    val joinRequests = _joinRequests.asStateFlow()

    /**
     * Join [meetingId]. Idempotent: re-joining the meeting already in progress is a no-op.
     *
     * [createIfMissing] is for the participant who started the meeting. Everyone else
     * joins without it and is refused when the code is not live — see [meetingNotFound].
     * [isPrivate] applies only while creating: it makes every later joiner knock.
     */
    fun join(
        meetingId: String,
        config: MediaConfig,
        createIfMissing: Boolean = false,
        isPrivate: Boolean = false,
    ) {
        val current = _session.value
        if (current is Session.Active && current.meetingId == meetingId) return
        leave()

        MeshLog.i(TAG) { "joining meeting $meetingId as $userId" }

        // Before prepareLocalMedia: the audio mode has to be MODE_IN_COMMUNICATION when
        // WebRTC opens its capture session, or the platform hands it a media-mode recorder
        // and the hardware echo canceller never engages.
        audioRouter.start()

        val eng = MeshWebRtcEngine(appContext, config)
        engine = eng
        eng.prepareLocalMedia()
        localVideoTrack = eng.localVideo
        _localMedia.value = LocalMediaState(eng.isMicEnabled, eng.isCameraEnabled)
        _frontCameraActive.value = eng.isFrontCamera

        val client = SocketIOSignalingClient(brokerUrl, userId, userName, avatarBase64)
        signaling = client
        _signalingConnected.value = false

        _peers.value = emptyList()
        _joinRequests.value = emptyList()
        _admission.value = Admission.JOINING
        _session.value = Session.Active(meetingId)

        scope.launch {
            launch {
                eng.connectionEvents.collect { event ->
                    when (event) {
                        is MeshWebRtcEngine.ConnectionEvent.LocalMediaStateChanged -> {
                            _localMedia.value = LocalMediaState(event.micOn, event.camOn)
                            // Nested under `state` — see [sendSdp].
                            client.sendMessage(
                                SignalingSchema.TYPE_PEER_STATE,
                                null,
                                JSONObject().put(
                                    SignalingSchema.KEY_STATE,
                                    SignalingSchema.PeerStatePayload(event.micOn, event.camOn)
                                        .toJson(),
                                ).toString(),
                            )
                        }

                        is MeshWebRtcEngine.ConnectionEvent.LocalError ->
                            _errors.tryEmit(
                                if (event.peerId.isEmpty()) event.error
                                else "${event.peerId}: ${event.error}",
                            )

                        is MeshWebRtcEngine.ConnectionEvent.IceStateChanged -> Unit

                        is MeshWebRtcEngine.ConnectionEvent.CameraFacingChanged ->
                            _frontCameraActive.value = event.isFrontCamera

                        is MeshWebRtcEngine.ConnectionEvent.ScreenShareChanged -> {
                            _screenSharing.value = event.active
                            // The system UI can end a share on its own, so the service is
                            // stopped from the event rather than only from stopScreenShare().
                            if (!event.active) MeshScreenShareService.stop(appContext)
                            publishLocalVideo()
                        }
                    }
                }
            }

            launch {
                eng.remoteStreams.collect { update ->
                    attachSpeakerSink(update.peerId, update.stream)
                    _mediaEvents.tryEmit(
                        MediaEvent.RemoteStreamChanged(update.peerId, update.stream),
                    )
                }
            }

            signalingJob?.cancel()
            signalingJob = launch { client.events.collect(::onSignalEvent) }

            client.connect(meetingId, createIfMissing, isPrivate)
        }

        startSpeakerSampler()
    }

    // ---- Signaling routing ------------------------------------------------------

    private suspend fun onSignalEvent(event: SignalEvent) {
        when (event) {
            is SignalEvent.PeerJoined -> {
                remoteNames[event.peerId] = event.userName
                remoteAvatars[event.peerId] = event.avatarBase64
                ensureLinkTo(event.peerId)
                publishPeers()
            }

            is SignalEvent.PeerLeft -> {
                MeshLog.i(TAG) { "peer left: ${event.peerId}" }
                forgetPeer(event.peerId)
                publishPeers()
            }

            is SignalEvent.MeetingSnapshot -> {
                _signalingConnected.value = true
                // A roster only ever reaches a socket the broker accepted, so this is
                // also the moment a knock turns into admission.
                _admission.value = Admission.ADMITTED
                val ids = event.peers.map { it.id }.filter { it != userId }
                // Reconcile: adopt the roster order, link to anyone new, drop the gone.
                val known = remoteNames.toMap()
                val knownAvatars = remoteAvatars.toMap()
                remoteNames.clear()
                remoteAvatars.clear()
                event.peers.forEach { peer ->
                    if (peer.id != userId) {
                        remoteNames[peer.id] = peer.userName.ifEmpty { known[peer.id] ?: peer.id }
                        remoteAvatars[peer.id] = peer.avatarBase64 ?: knownAvatars[peer.id]
                    }
                }
                ids.forEach(::ensureLinkTo)
                connections.keys.toList()
                    .filterNot { it in ids }
                    .forEach(::forgetPeer)
                publishPeers()
            }

            is SignalEvent.Offer -> {
                MeshLog.i(TAG) { "<- offer from ${event.fromId}" }
                val holder = connections[event.fromId] ?: ensureLinkTo(event.fromId) ?: run {
                    MeshLog.w(TAG, "offer from ${event.fromId} dropped — no link could be made")
                    return
                }
                val engine = engine ?: run {
                    MeshLog.w(TAG, "offer from ${event.fromId} dropped — engine is gone")
                    return
                }
                engine.handleOffer(
                    holder,
                    SessionDescription(SessionDescription.Type.OFFER, event.sdp.sdp),
                ) { peerId, answer ->
                    MeshLog.i(TAG) { "-> answer to $peerId" }
                    sendSdp(SignalingSchema.TYPE_ANSWER, peerId, answer)
                }
            }

            is SignalEvent.Answer -> {
                MeshLog.i(TAG) { "<- answer from ${event.fromId}" }
                val holder = connections[event.fromId] ?: run {
                    MeshLog.w(TAG, "answer from ${event.fromId} dropped — no such connection")
                    return
                }
                engine?.handleAnswer(
                    holder,
                    SessionDescription(SessionDescription.Type.ANSWER, event.sdp.sdp),
                )
            }

            is SignalEvent.IceCandidate -> {
                val holder = connections[event.fromId] ?: run {
                    MeshLog.w(TAG, "ice from ${event.fromId} dropped — no such connection")
                    return
                }
                engine?.addIceCandidate(
                    holder,
                    org.webrtc.IceCandidate(
                        event.candidate.sdpMid ?: "",
                        event.candidate.sdpMLineIndex,
                        event.candidate.candidate,
                    ),
                )
            }

            is SignalEvent.PeerState -> {
                remoteNames.getOrPut(event.fromId) { event.fromId }
                remoteMedia[event.fromId] =
                    event.state.micEnabled to event.state.cameraEnabled
                publishPeers()
            }

            is SignalEvent.MuteRequest -> {
                // Only honor requests from someone actually in the meeting — a stale or
                // spoofed `from` should not be able to silence us.
                if (event.fromId in remoteNames) {
                    MeshLog.i(TAG) { "muted at request of ${event.fromId}" }
                    engine?.enableMic(false)
                }
            }

            is SignalEvent.SignalingDisconnected -> {
                _signalingConnected.value = false
                // socket.io reconnects on its own; the reconnect path re-emits
                // join-meeting, which brings back a fresh roster snapshot.
            }

            is SignalEvent.AwaitingApproval -> {
                MeshLog.i(TAG) { "waiting for the host to admit us" }
                _admission.value = Admission.AWAITING_APPROVAL
            }

            is SignalEvent.JoinDenied -> {
                MeshLog.w(TAG, "the host declined our request to join")
                _signalingConnected.value = false
                _admission.value = Admission.DENIED
            }

            is SignalEvent.Knock -> {
                // Re-sent by the broker whenever a host arrives or inherits the role, so
                // the same person must not stack up twice in the list.
                if (_joinRequests.value.none { it.userId == event.peerId }) {
                    _joinRequests.value = _joinRequests.value +
                        JoinRequest(event.peerId, event.userName.ifEmpty { event.peerId })
                }
            }

            is SignalEvent.KnockWithdrawn ->
                _joinRequests.value = _joinRequests.value.filterNot { it.userId == event.peerId }

            is SignalEvent.MeetingNotFound -> {
                MeshLog.w(TAG, "meeting ${event.meetingId} does not exist — leaving")
                _signalingConnected.value = false
                _meetingNotFound.tryEmit(event.meetingId)
            }

            is SignalEvent.ErrorReceived -> _errors.tryEmit(event.message)
        }
    }

    /**
     * Ensure a link to [peerId] exists.
     *
     * Negotiation is glare-free by construction: **the peer with the lexicographically
     * lower userId offers**, the other waits. No rollback or glare handling needed —
     * see README §7 rule 5 before changing this.
     */
    private fun ensureLinkTo(peerId: String): MeshWebRtcEngine.PeerConnectionHolder? {
        if (peerId == userId) return null
        val engine = engine ?: return null
        connections[peerId]?.let { if (!it.disposed) return it }

        val holder = engine.preparePeerConnection(
            peerId = peerId,
            onIceCandidate = { pid, candidate ->
                // Nested under `candidate`, same as the SDP payloads — see [sendSdp].
                val payload = JSONObject().put(
                    SignalingSchema.KEY_ICE_CANDIDATE,
                    SignalingSchema.IceCandidatePayload(
                        candidate.sdp,
                        candidate.sdpMLineIndex,
                        candidate.sdpMid,
                    ).toJson(),
                )
                scope.launch {
                    signaling?.sendMessage(
                        SignalingSchema.TYPE_ICE_CANDIDATE,
                        pid,
                        payload.toString(),
                    )
                }
            },
            onStreamAdded = { _mediaEvents.tryEmit(MediaEvent.RemoteStreamAdded(it)) },
            onStreamRemoved = { _mediaEvents.tryEmit(MediaEvent.RemoteStreamRemoved(it)) },
            onIceReport = { pid, state ->
                iceStateByPeer[pid] = state
                if (state == "failed") scheduleRelink(pid)
                publishPeers()
            },
        )
        connections[peerId] = holder

        if (shouldOffer(userId, peerId)) {
            MeshLog.i(TAG) { "link $peerId — we offer (our id sorts lower)" }
            engine.createOffer(holder) { pid, sdp ->
                MeshLog.i(TAG) { "-> offer to $pid" }
                sendSdp(SignalingSchema.TYPE_OFFER, pid, sdp)
            }
        } else {
            MeshLog.i(TAG) { "link $peerId — waiting for their offer" }
        }
        publishPeers()
        return holder
    }

    /**
     * Rebuild a link whose ICE failed.
     *
     * The old code disposed the connection inside the ICE observer but left the dead
     * handle in the map, so the peer was stuck at "failed" for the rest of the meeting.
     * Dropping the handle and re-running [ensureLinkTo] restarts negotiation from scratch.
     */
    private fun scheduleRelink(peerId: String) {
        val attempts = relinkAttempts.getOrDefault(peerId, 0)
        if (attempts >= MAX_RELINK_ATTEMPTS) {
            MeshLog.w(TAG, "giving up on $peerId after $attempts re-link attempts")
            return
        }
        relinkAttempts[peerId] = attempts + 1
        scope.launch {
            delay(RELINK_DELAY_MS * (attempts + 1))
            if (peerId !in remoteNames) return@launch
            MeshLog.i(TAG) { "re-linking $peerId (attempt ${attempts + 1})" }
            connections.remove(peerId)?.dispose()
            engine?.releasePeer(peerId)
            iceStateByPeer[peerId] = "connecting"
            ensureLinkTo(peerId)
            publishPeers()
        }
    }

    /** Drop every trace of a peer that left. */
    private fun forgetPeer(peerId: String) {
        connections.remove(peerId)?.dispose()
        engine?.releasePeer(peerId)
        detachSpeakerSink(peerId)
        remoteNames.remove(peerId)
        remoteAvatars.remove(peerId)
        remoteMedia.remove(peerId)
        iceStateByPeer.remove(peerId)
        relinkAttempts.remove(peerId)
        if (_speakerId.value == peerId) _speakerId.value = null
    }

    /**
     * Wire shape is `{ to, sdp: { type, sdp } }` — the description is **nested** under
     * `sdp`, per README §4. Emitting the SdpPayload flat put a raw SDP string where the
     * receiver's `optJSONObject("sdp")` expected an object, so every offer and answer was
     * silently discarded on arrival and no call ever negotiated.
     */
    private fun sendSdp(type: String, peerId: String, sdp: SessionDescription) {
        val payload = JSONObject().put(
            SignalingSchema.KEY_SDP,
            SignalingSchema.SdpPayload(sdp.type.canonicalForm(), sdp.description).toJson(),
        )
        scope.launch { signaling?.sendMessage(type, peerId, payload.toString()) }
    }

    private fun publishPeers() {
        _peers.value = remoteNames.mapNotNull { (id, name) ->
            if (id == userId || id !in connections) return@mapNotNull null
            val (mic, cam) = remoteMedia[id] ?: (true to true)
            MeetingPeer(
                id = id,
                userName = name,
                micEnabled = mic,
                cameraEnabled = cam,
                connectionState = iceStateByPeer[id] ?: "new",
                avatarBase64 = remoteAvatars[id],
            )
        }
    }

    // ---- Active speaker ---------------------------------------------------------

    /**
     * Sample per-peer RMS every [SPEAKER_SAMPLE_MS]; the loudest peer above the threshold
     * is the active speaker. The id is held through a short silence window so bursts of
     * background noise don't flicker the UI.
     */
    private fun startSpeakerSampler() {
        speakerJob?.cancel()
        lastSpeakerActiveAt = SystemClock.elapsedRealtime()
        speakerJob = scope.launch {
            while (true) {
                delay(SPEAKER_SAMPLE_MS)
                val now = SystemClock.elapsedRealtime()
                val loudest = speakerLevels.maxByOrNull { it.value }
                if (loudest != null && loudest.value > SPEAKER_THRESHOLD) {
                    lastSpeakerActiveAt = now
                    if (_speakerId.value != loudest.key) _speakerId.value = loudest.key
                } else if (now - lastSpeakerActiveAt > SPEAKER_HOLD_MS && _speakerId.value != null) {
                    _speakerId.value = null
                }
            }
        }
    }

    private fun attachSpeakerSink(peerId: String, stream: MediaStream?) {
        detachSpeakerSink(peerId)
        val track = stream?.audioTracks?.firstOrNull() ?: return
        val sink = AudioTrackSink { data, _, _, _, _, _ ->
            speakerLevels[peerId] = rmsLevel(data)
        }
        try {
            track.addSink(sink)
            audioSinks[peerId] = track to sink
        } catch (e: Exception) {
            MeshLog.w(TAG, "could not attach audio sink for $peerId", e)
        }
    }

    /** Detach a peer's level sink. Failing to remove it keeps the audio callback alive. */
    private fun detachSpeakerSink(peerId: String) {
        speakerLevels.remove(peerId)
        val (track, sink) = audioSinks.remove(peerId) ?: return
        try {
            track.removeSink(sink)
        } catch (e: Exception) {
            // The track may already be disposed with its connection; nothing left to do.
            MeshLog.d(TAG) { "removeSink($peerId) ignored: ${e.message}" }
        }
    }

    /** Normalized RMS (0..1) over a raw PCM16 buffer. */
    private fun rmsLevel(buffer: java.nio.ByteBuffer): Float {
        val pcm = buffer.duplicate()
        var sum = 0.0
        var count = 0
        while (pcm.remaining() >= 2) {
            val sample = pcm.short.toInt()
            sum += sample.toDouble() * sample
            count++
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    // ---- Controls ---------------------------------------------------------------

    fun toggleMic() = engine?.let { it.enableMic(!it.isMicEnabled) } ?: Unit
    fun toggleCamera() = engine?.let { it.enableCamera(!it.isCameraEnabled) } ?: Unit
    fun switchCamera() = engine?.switchCamera() ?: Unit
    fun setMic(enabled: Boolean) = engine?.enableMic(enabled) ?: Unit
    fun setCamera(enabled: Boolean) = engine?.enableCamera(enabled) ?: Unit
    fun selectAudioRoute(route: AudioRoute) = audioRouter.select(route)

    /**
     * Begin sharing the screen. [permissionData] is the MediaProjection consent result the
     * host obtained from an Activity.
     *
     * The foreground service has to be up *before* the projection is requested — Android 14
     * rejects it otherwise — so capture starts inside the service's ready callback rather
     * than inline here.
     */
    fun startScreenShare(permissionData: Intent) {
        val eng = engine ?: return
        if (eng.isScreenSharing) return
        try {
            MeshScreenShareService.start(appContext) {
                if (!eng.startScreenShare(permissionData)) {
                    MeshScreenShareService.stop(appContext)
                }
            }
        } catch (e: Exception) {
            _errors.tryEmit("Screen sharing could not start: ${e.message}")
        }
    }

    fun stopScreenShare() {
        engine?.stopScreenShare()
        MeshScreenShareService.stop(appContext)
    }

    /** Republish whichever track the local tile should render — camera or screen. */
    private fun publishLocalVideo() {
        localVideoTrack = engine?.outgoingVideo
        _mediaEvents.tryEmit(MediaEvent.LocalVideoChanged(localVideoTrack))
    }

    /**
     * Ask [peerId] to mute their mic. Honored automatically on their end — see
     * [SignalEvent.MuteRequest] — there is no permission gate, since the mesh has no host
     * role today. Requires broker support for `mute-request`; see [SignalingSchema].
     */
    fun requestMute(peerId: String) {
        val client = signaling ?: return
        scope.launch { client.sendMessage(SignalingSchema.TYPE_MUTE_REQUEST, peerId, "{}") }
    }

    /**
     * Answer one waiting request. Ignored by the broker unless we really are the host, so
     * this is a UI convenience, not the access control itself.
     *
     * The request is dropped locally either way: the broker does not echo the decision
     * back, and a card that lingers after being answered invites a second tap.
     */
    fun answerJoinRequest(peerId: String, admit: Boolean) {
        val client = signaling ?: return
        _joinRequests.value = _joinRequests.value.filterNot { it.userId == peerId }
        val payload = JSONObject()
            .put(SignalingSchema.KEY_USER_ID, peerId)
            .put(SignalingSchema.KEY_ADMIT, admit)
        scope.launch {
            client.sendMessage(SignalingSchema.TYPE_ADMIT_DECISION, null, payload.toString())
        }
    }

    fun localVideo(): VideoTrack? = localVideoTrack

    /** Shared EGL context; renderers must initialize against it to share hardware decode. */
    val eglContext: EglBase.Context?
        get() = engine?.eglBase?.eglBaseContext

    /** Leave the meeting and free everything. Safe to call more than once. */
    fun leave() {
        if (_session.value is Session.Idle && engine == null) return
        MeshLog.i(TAG) { "leaving meeting" }

        speakerJob?.cancel()
        speakerJob = null
        audioSinks.keys.toList().forEach(::detachSpeakerSink)
        audioSinks.clear()
        speakerLevels.clear()
        _speakerId.value = null

        signalingJob?.cancel()
        signalingJob = null

        // Capture the client first: the field is nulled synchronously below, and the old
        // code read `signaling` *inside* the coroutine — by then already null, so the
        // socket was never closed and the meeting kept a ghost participant.
        val client = signaling
        signaling = null
        scope.launch { client?.disconnect() }

        connections.values.forEach { it.dispose() }
        connections.clear()
        remoteNames.clear()
        remoteAvatars.clear()
        remoteMedia.clear()
        iceStateByPeer.clear()
        relinkAttempts.clear()
        _signalingConnected.value = false

        // Explicit, not left to engine.dispose(): by then the event collector is cancelled,
        // so the service would never be told to stop and the screen-capture notification
        // would outlive the meeting.
        MeshScreenShareService.stop(appContext)
        _screenSharing.value = false

        engine?.dispose()
        engine = null
        localVideoTrack = null

        // After the engine: stopping the router restores the system audio mode, and doing
        // that while WebRTC still holds the capture session leaves the device in call mode.
        audioRouter.stop()

        _peers.value = emptyList()
        _session.value = Session.Idle
    }

    /** Leave and cancel the manager's scope. The instance is unusable afterwards. */
    fun destroy() {
        leave()
        scope.cancel()
    }

    /** Normalized session status. */
    sealed class Session {
        data object Idle : Session()
        data class Active(val meetingId: String) : Session()
    }

    /** A participant in the meeting (excluding self). */
    data class MeetingPeer(
        val id: String,
        val userName: String,
        val micEnabled: Boolean,
        val cameraEnabled: Boolean,
        val connectionState: String = "new",
        val avatarBase64: String? = null,
    )

    /** Media/track lifecycle event surfaced to the view layer. */
    sealed class MediaEvent {
        data class RemoteStreamAdded(val peerId: String) : MediaEvent()
        data class RemoteStreamRemoved(val peerId: String) : MediaEvent()
        data class RemoteStreamChanged(val peerId: String, val stream: MediaStream?) : MediaEvent()

        /** The local tile's track was swapped — camera ⇄ screen share. */
        data class LocalVideoChanged(val track: VideoTrack?) : MediaEvent()
    }

    internal companion object {
        private const val TAG = "Mesh"

        /**
         * The deterministic offer rule. Extracted so it is unit-testable and so there is
         * exactly one place to look when negotiation misbehaves.
         */
        fun shouldOffer(selfId: String, peerId: String): Boolean = selfId < peerId

        const val SPEAKER_SAMPLE_MS = 300L
        const val SPEAKER_THRESHOLD = 0.05f
        const val SPEAKER_HOLD_MS = 1_500L

        const val MAX_RELINK_ATTEMPTS = 3
        const val RELINK_DELAY_MS = 1_500L
    }
}
