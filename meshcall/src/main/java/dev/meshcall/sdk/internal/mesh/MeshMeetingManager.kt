package dev.meshcall.sdk.internal.mesh

import android.content.Context
import android.os.SystemClock
import dev.meshcall.sdk.api.LocalMediaState
import dev.meshcall.sdk.internal.media.MediaConfig
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var engine: MeshWebRtcEngine? = null

    @Volatile private var signaling: SignalingClient? = null

    private val connections = LinkedHashMap<String, MeshWebRtcEngine.PeerConnectionHolder>()

    /** Roster names in arrival order, so tiles keep a stable position between updates. */
    private val remoteNames = LinkedHashMap<String, String>()

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

    private var signalingJob: Job? = null
    private var localVideoTrack: VideoTrack? = null

    /**
     * Join [meetingId]. Idempotent: re-joining the meeting already in progress is a no-op.
     */
    fun join(meetingId: String, config: MediaConfig) {
        val current = _session.value
        if (current is Session.Active && current.meetingId == meetingId) return
        leave()

        MeshLog.i(TAG) { "joining meeting $meetingId as $userId" }

        val eng = MeshWebRtcEngine(appContext, config)
        engine = eng
        eng.prepareLocalMedia()
        localVideoTrack = eng.localVideo
        _localMedia.value = LocalMediaState(eng.isMicEnabled, eng.isCameraEnabled)

        val client = SocketIOSignalingClient(brokerUrl, userId, userName)
        signaling = client
        _signalingConnected.value = false

        _peers.value = emptyList()
        _session.value = Session.Active(meetingId)

        scope.launch {
            launch {
                eng.connectionEvents.collect { event ->
                    when (event) {
                        is MeshWebRtcEngine.ConnectionEvent.LocalMediaStateChanged -> {
                            _localMedia.value = LocalMediaState(event.micOn, event.camOn)
                            client.sendMessage(
                                SignalingSchema.TYPE_PEER_STATE,
                                null,
                                SignalingSchema.PeerStatePayload(event.micOn, event.camOn)
                                    .toJson().toString(),
                            )
                        }

                        is MeshWebRtcEngine.ConnectionEvent.LocalError ->
                            _errors.tryEmit(
                                if (event.peerId.isEmpty()) event.error
                                else "${event.peerId}: ${event.error}",
                            )

                        is MeshWebRtcEngine.ConnectionEvent.IceStateChanged -> Unit
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

            client.connect(meetingId)
        }

        startSpeakerSampler()
    }

    // ---- Signaling routing ------------------------------------------------------

    private suspend fun onSignalEvent(event: SignalEvent) {
        when (event) {
            is SignalEvent.PeerJoined -> {
                remoteNames[event.peerId] = event.userName
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
                val ids = event.peers.map { it.id }.filter { it != userId }
                // Reconcile: adopt the roster order, link to anyone new, drop the gone.
                val known = remoteNames.toMap()
                remoteNames.clear()
                event.peers.forEach { peer ->
                    if (peer.id != userId) {
                        remoteNames[peer.id] = peer.userName.ifEmpty { known[peer.id] ?: peer.id }
                    }
                }
                ids.forEach(::ensureLinkTo)
                connections.keys.toList()
                    .filterNot { it in ids }
                    .forEach(::forgetPeer)
                publishPeers()
            }

            is SignalEvent.Offer -> {
                val holder = connections[event.fromId] ?: ensureLinkTo(event.fromId) ?: return
                val engine = engine ?: return
                engine.handleOffer(
                    holder,
                    SessionDescription(SessionDescription.Type.OFFER, event.sdp.sdp),
                ) { peerId, answer -> sendSdp(SignalingSchema.TYPE_ANSWER, peerId, answer) }
            }

            is SignalEvent.Answer -> {
                val holder = connections[event.fromId] ?: return
                engine?.handleAnswer(
                    holder,
                    SessionDescription(SessionDescription.Type.ANSWER, event.sdp.sdp),
                )
            }

            is SignalEvent.IceCandidate -> {
                val holder = connections[event.fromId] ?: return
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

            is SignalEvent.SignalingDisconnected -> {
                _signalingConnected.value = false
                // socket.io reconnects on its own; the reconnect path re-emits
                // join-meeting, which brings back a fresh roster snapshot.
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
                val payload = SignalingSchema.IceCandidatePayload(
                    candidate.sdp,
                    candidate.sdpMLineIndex,
                    candidate.sdpMid,
                )
                scope.launch {
                    signaling?.sendMessage(
                        SignalingSchema.TYPE_ICE_CANDIDATE,
                        pid,
                        payload.toJson().toString(),
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
            engine.createOffer(holder) { pid, sdp -> sendSdp(SignalingSchema.TYPE_OFFER, pid, sdp) }
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
        remoteMedia.remove(peerId)
        iceStateByPeer.remove(peerId)
        relinkAttempts.remove(peerId)
        if (_speakerId.value == peerId) _speakerId.value = null
    }

    private fun sendSdp(type: String, peerId: String, sdp: SessionDescription) {
        val payload = SignalingSchema.SdpPayload(sdp.type.canonicalForm(), sdp.description).toJson()
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
        remoteMedia.clear()
        iceStateByPeer.clear()
        relinkAttempts.clear()
        _signalingConnected.value = false

        engine?.dispose()
        engine = null
        localVideoTrack = null

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
    )

    /** Media/track lifecycle event surfaced to the view layer. */
    sealed class MediaEvent {
        data class RemoteStreamAdded(val peerId: String) : MediaEvent()
        data class RemoteStreamRemoved(val peerId: String) : MediaEvent()
        data class RemoteStreamChanged(val peerId: String, val stream: MediaStream?) : MediaEvent()
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
