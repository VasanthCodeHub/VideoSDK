package dev.meshcall.sdk.internal.mesh

import android.content.Context
import android.os.SystemClock
import dev.meshcall.sdk.internal.media.MediaConfig
import dev.meshcall.sdk.internal.signaling.SignalEvent
import dev.meshcall.sdk.internal.signaling.SignalingClient
import dev.meshcall.sdk.internal.signaling.SignalingSchema
import dev.meshcall.sdk.internal.signaling.SocketIOSignalingClient
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
import org.webrtc.AudioTrackSink
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Coordinates signaling, the WebRTC mesh, and local media for one room session.
 *
 * This is the internal nerve center: it consumes [SignalEvent]s, drives
 * [MeshWebRtcEngine], and publishes a normalized [RoomState] that the view layer
 * renders. It multiplexes everything on a single confined coroutine dispatcher so the
 * WebRTC callbacks (which arrive on random binder threads) never race each other.
 *
 * Threading model: every event handler/fun-off-from-flow `launch`s on
 * [scope]; all state that the UI reads is exposed as cold flows so publishes are safe
 * from any thread.
 */
internal class MeshCallManager(
    private val appContext: Context,
    private val brokerUrl: String,
    private val userId: String,
    private val userName: String,
    private val signalingFactory: ((roomId: String) -> SignalingClient)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var engine: MeshWebRtcEngine? = null

    @Volatile
    private var signaling: SignalingClient? = null

    private val connections = HashMap<String, MeshWebRtcEngine.PeerConnectionHolder>()
    private val remoteNames = HashMap<String, String>()
    // Tracks the last known media state per peer so roster rebuilds don't drop the
    // "muted" indicators a peer sent us over the peer-state broadcast.
    private val remoteMedia = HashMap<String, Pair<Boolean, Boolean>>()

    // ---- Active-speaker detection ----------------------------------------------
    // Real path: RMS levels computed from each remote audio track (AudioTrackSink);
    // demo path: SignalEvent.PeerSpeaking emitted by the mock client. Both funnel
    // into _speakerId, which drives the "speaker moves into the main grid" UX.

    /** Last RMS (0..1) per peer, written on the WebRTC audio thread. */
    private val speakerLevels = ConcurrentHashMap<String, Float>()

    /** Registered sinks per peer so they can be detached when a stream is removed. */
    private val audioSinks = HashMap<String, AudioTrackSink>()

    private var lastSpeakerActiveAt = 0L
    private var speakerJob: Job? = null

    private val _speakerId = MutableStateFlow<String?>(null)
    /** Peer currently talking (null when nobody is). */
    val speakerId = _speakerId.asStateFlow()

    // ---- Observable state ------------------------------------------------------
    private val _roomState = MutableStateFlow<RoomState>(RoomState.Idle)
    val roomState = _roomState.asStateFlow()

    private val _peers = MutableStateFlow<List<RoomPeer>>(emptyList())
    val peers = _peers.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errors = _errors.asSharedFlow()

    private val _mediaStreams = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 32)
    val mediaEvents = _mediaStreams.asSharedFlow()

    /** True while the signaling socket is connected (roster received). */
    private val _signalingConnected = MutableStateFlow(false)
    val signalingConnected = _signalingConnected.asStateFlow()

    /** Latest ICE label per peer so tiles can show a connection dot. */
    private val iceStateByPeer = HashMap<String, String>()

    private var signalingJob: Job? = null
    private var localVideoTrack: VideoTrack? = null

    /**
     * Join [roomId]. Idempotent: if already in this room this is a no-op.
     * [config] is applied only on first join; toggling later is via [toggleMic]/[toggleCamera].
     */
    fun join(
        roomId: String,
        config: MediaConfig,
    ) {
        if (_roomState.value is RoomState.Active && (_roomState.value as RoomState.Active).roomId == roomId) {
            return
        }
        leave()

        val eng = MeshWebRtcEngine(appContext, config)
        engine = eng
        eng.prepareLocalMedia()
        localVideoTrack = eng.localVideo

        val s: SignalingClient = signalingFactory?.invoke(roomId)
            ?: SocketIOSignalingClient(brokerUrl, userId, userName)
        signaling = s
        _signalingConnected.value = false

        _peers.value = emptyList()
        _roomState.value = RoomState.Active(roomId)

        scope.launch {
            // Publish engine connection events.
            launch {
                eng.connectionEvents.collect { ev: MeshWebRtcEngine.ConnectionEvent ->
                    when (ev) {
                        is MeshWebRtcEngine.ConnectionEvent.LocalMediaStateChanged -> {
                            // Broadcast our new state to all peers.
                            scope.launch {
                                s.sendMessage(
                                    SignalingSchema.TYPE_PEER_STATE,
                                    null,
                                    SignalingSchema.PeerStatePayload(ev.micOn, ev.camOn).toJson().toString(),
                                )
                            }
                        }
                        is MeshWebRtcEngine.ConnectionEvent.RemoteStreamAdded -> {}
                        is MeshWebRtcEngine.ConnectionEvent.RemoteStreamRemoved -> {}
                        is MeshWebRtcEngine.ConnectionEvent.LocalError -> {
                            _errors.tryEmit("${if (ev.peerId.isEmpty()) "" else ev.peerId + ": "}${ev.error}")
                        }
                        is MeshWebRtcEngine.ConnectionEvent.IceStateChanged -> {}
                    }
                }
            }
            // Publish engine media (in-bound tracks) to the view layer.
            scope.launch {
                eng.remoteStreams.collect { update: MeshWebRtcEngine.RemoteStreamUpdate ->
                    attachSpeakerSink(update.peerId, update.stream)
                    _mediaStreams.tryEmit(MediaEvent.RemoteStreamChanged(update.peerId, update.stream))
                }
            }

            // Collect signaling events and route them.
            signalingJob?.cancel()
            signalingJob = launch {
                s.events.collect(::onSignalEvent)
            }

            s.connect(roomId)
        }

        startSpeakerSampler()
    }

    /**
     * Sample the per-peer RMS levels every 300 ms; the loudest peer above the
     * threshold is the active speaker. The speaker id is held for a short silence
     * window so bursts of background noise don't flicker the UI.
     */
    private fun startSpeakerSampler() {
        speakerJob?.cancel()
        lastSpeakerActiveAt = SystemClock.elapsedRealtime()
        speakerJob = scope.launch {
            while (true) {
                delay(SPEAKER_SAMPLE_MS)
                val now = SystemClock.elapsedRealtime()
                val best = speakerLevels.maxByOrNull { it.value }
                if (best != null && best.value > SPEAKER_THRESHOLD) {
                    lastSpeakerActiveAt = now
                    if (_speakerId.value != best.key) _speakerId.value = best.key
                } else if (now - lastSpeakerActiveAt > SPEAKER_HOLD_MS) {
                    if (_speakerId.value != null) _speakerId.value = null
                }
            }
        }
    }

    /** Attach an audio-level sink to a peer's remote audio track (real calls only). */
    private fun attachSpeakerSink(peerId: String, stream: MediaStream?) {
        detachSpeakerSink(peerId)
        speakerLevels.remove(peerId)
        val track = stream?.audioTracks?.firstOrNull() ?: return
        val sink = AudioTrackSink { data, _, _, _, _, _ ->
            speakerLevels[peerId] = rmsLevel(data)
        }
        audioSinks[peerId] = sink
        try {
            track.addSink(sink)
        } catch (_: Exception) {
            audioSinks.remove(peerId)
        }
    }

    /** Detach a peer's audio-level sink when their stream goes away. */
    private fun detachSpeakerSink(peerId: String) {
        val sink = audioSinks.remove(peerId) ?: return
        speakerLevels.remove(peerId)
        // The owning track is disposed by the engine; the sink is inert once the
        // peer is gone, so dropping the reference is sufficient.
    }

    /** Compute normalized RMS (0..1) from a raw PCM16 buffer. */
    private fun rmsLevel(buffer: java.nio.ByteBuffer): Float {
        val pcm = buffer.duplicate()
        var sum = 0.0
        var count = 0
        while (pcm.remaining() >= 2) {
            val sample = pcm.short.toInt()
            sum += sample.toLong() * sample
            count++
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) / 32767.0).toFloat().coerceIn(0f, 1f)
    }

    /** Route one inbound signaling event into the mesh. */
    private suspend fun onSignalEvent(event: SignalEvent) {
        when (event) {
            is SignalEvent.PeerJoined -> {
                remoteNames[event.peerId] = event.userName
                ensureLinkTo(event.peerId)
                publishPeers()
            }
            is SignalEvent.PeerLeft -> {
                connections.remove(event.peerId)?.dispose()
                remoteNames.remove(event.peerId)
                remoteMedia.remove(event.peerId)
                iceStateByPeer.remove(event.peerId)
                publishPeers()
            }
            is SignalEvent.RoomSnapshot -> {
                _signalingConnected.value = true
                // Reconcile the roster: connect to any peers we missed, drop stale ones.
                val ids = event.peers.map { it.id }
                remoteNames.clear()
                event.peers.forEach { remoteNames[it.id] = it.userName }
                ids.forEach(::ensureLinkTo)
                connections.keys.toList()
                    .filter { it != userId }
                    .filterNot { it in ids }
                    .forEach {
                        connections.remove(it)?.dispose()
                        remoteNames.remove(it)
                        remoteMedia.remove(it)
                        iceStateByPeer.remove(it)
                    }
                publishPeers()
            }
            is SignalEvent.Offer -> {
                val holder = connections[event.fromId] ?: createHolderFor(event.fromId) ?: return
                engine?.let { eng ->
                    val sdp = SessionDescription(SessionDescription.Type.OFFER, event.sdp.sdp)
                    eng.handleOffer(holder, sdp) { peerId, answer ->
                        sendSdp(SignalingSchema.TYPE_ANSWER, peerId, answer)
                    }
                }
            }
            is SignalEvent.Answer -> {
                val holder = connections[event.fromId] ?: return
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, event.sdp.sdp)
                engine?.handleAnswer(holder, sdp)
            }
            is SignalEvent.IceCandidate -> {
                val holder = connections[event.fromId] ?: return
                val cand = org.webrtc.IceCandidate(
                    event.candidate.sdpMid ?: "",
                    event.candidate.sdpMLineIndex,
                    event.candidate.candidate,
                )
                engine?.addIceCandidate(holder, cand)
            }
            is SignalEvent.PeerState -> {
                remoteNames.getOrPut(event.fromId) { event.fromId }
                remoteMedia[event.fromId] = Pair(event.state.micEnabled, event.state.cameraEnabled)
                publishPeers()
            }
            is SignalEvent.PeerSpeaking -> {
                if (event.speaking) {
                    _speakerId.value = event.peerId
                } else if (_speakerId.value == event.peerId) {
                    _speakerId.value = null
                }
            }
            is SignalEvent.SignalingDisconnected -> {
                _signalingConnected.value = false
                // Socket dropped; socket.io reconnects on its own and the reconnect
                // path re-emits join-room which triggers a mid-loop roster snapshot.
            }
            is SignalEvent.ErrorReceived -> {
                _errors.tryEmit(event.message)
            }
        }
    }

    /** Deterministic: the peer with the lower id sends the offer. */
    private fun ensureLinkTo(peerId: String): MeshWebRtcEngine.PeerConnectionHolder? {
        if (peerId == userId) return null
        val eng = engine ?: return null
        connections[peerId]?.let { return it }

        val holder = eng.preparePeerConnection(
            peerId = peerId,
            onIceCandidate = { pid, candidate ->
                val cand = SignalingSchema.IceCandidatePayload(
                    candidate.sdp,
                    candidate.sdpMLineIndex,
                    candidate.sdpMid,
                )
                scope.launch {
                    signaling?.sendMessage(
                        SignalingSchema.TYPE_ICE_CANDIDATE,
                        pid,
                        cand.toJson().toString(),
                    )
                }
            },
            onStreamAdded = {
                _mediaStreams.tryEmit(MediaEvent.RemoteStreamAdded(it))
            },
            onStreamRemoved = {
                _mediaStreams.tryEmit(MediaEvent.RemoteStreamRemoved(it))
            },
            onIceReport = { pid, state ->
                iceStateByPeer[pid] = state
                publishPeers()
            },
        )
        connections[peerId] = holder

        if (userId < peerId) {
            // We are the polite side: offer.
            eng.createOffer(holder) { pid, sdp ->
                sendSdp(SignalingSchema.TYPE_OFFER, pid, sdp)
            }
        }
        // Otherwise we wait for the remote's offer (they are polite).
        publishPeers()
        return holder
    }

    private fun createHolderFor(peerId: String): MeshWebRtcEngine.PeerConnectionHolder? {
        if (peerId == userId) return null
        return ensureLinkTo(peerId)
    }

    private fun sendSdp(type: String, peerId: String, sdp: SessionDescription) {
        val payload = SignalingSchema.SdpPayload(sdp.type.canonicalForm(), sdp.description).toJson()
        scope.launch {
            signaling?.sendMessage(type, peerId, payload.toString())
        }
    }

    private fun publishPeers() {
        _peers.value = remoteNames.mapNotNull { (id, name) ->
            if (id == userId) return@mapNotNull null
            if (!connections.containsKey(id)) return@mapNotNull null
            val (mic, cam) = remoteMedia[id] ?: Pair(true, true)
            RoomPeer(
                id = id,
                userName = name,
                micEnabled = mic,
                cameraEnabled = cam,
                connectionState = iceStateByPeer[id] ?: "new",
            )
        }
    }

    fun toggleMic() {
        engine?.enableMic(!(engine?.isMicEnabled ?: true))
    }

    fun toggleCamera() {
        engine?.enableCamera(!(engine?.isCameraEnabled ?: true))
    }

    fun switchCamera() {
        engine?.switchCamera()
    }

    fun setMic(enabled: Boolean) { engine?.enableMic(enabled) }
    fun setCamera(enabled: Boolean) { engine?.enableCamera(enabled) }

    fun localVideo(): VideoTrack? = localVideoTrack

    /**
     * The shared [org.webrtc.EglBase.Context] used by the engine. Renderers that want
     * to share the decoder/encoder hardware must be initialized against this context.
     */
    val eglContext: EglBase.Context?
        get() = engine?.eglBase?.eglBaseContext

    /**
     * Leave the room and free everything. Safe to call more than once.
     */
    fun leave() {
        speakerJob?.cancel()
        speakerJob = null
        audioSinks.clear()
        speakerLevels.clear()
        _speakerId.value = null
        signalingJob?.cancel()
        signalingJob = null
        scope.launch {
            signaling?.disconnect()
        }
        signaling = null

        connections.values.forEach { it.dispose() }
        connections.clear()
        remoteNames.clear()
        remoteMedia.clear()
        iceStateByPeer.clear()
        _signalingConnected.value = false

        engine?.dispose()
        engine = null
        localVideoTrack = null

        _peers.value = emptyList()
        _roomState.value = RoomState.Idle
    }

    fun destroy() {
        leave()
        scope.cancel()
    }

    /** Normalized room status. */
    sealed class RoomState {
        data object Idle : RoomState()
        data class Active(val roomId: String) : RoomState()
    }

    /** A participant in the room (excluding self). */
    data class RoomPeer(
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

    private companion object {
        /** Sample interval (ms) for audio-level based speaker detection. */
        const val SPEAKER_SAMPLE_MS = 300L

        /** RMS (0..1) above which a peer counts as talking. */
        const val SPEAKER_THRESHOLD = 0.05f

        /** How long (ms) of quiet before the speaker indicator is cleared. */
        const val SPEAKER_HOLD_MS = 1_500L
    }
}

// Convenience ctor used by the public factory.
internal fun newManager(
    context: Context,
    brokerUrl: String,
    userId: String,
    userName: String,
): MeshCallManager = MeshCallManager(context.applicationContext, brokerUrl, userId, userName)
