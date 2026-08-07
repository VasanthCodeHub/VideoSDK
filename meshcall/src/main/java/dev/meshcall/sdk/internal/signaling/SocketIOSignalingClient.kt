package dev.meshcall.sdk.internal.signaling

import dev.meshcall.sdk.internal.util.MeshLog
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Socket.IO signaling transport. See [SignalingSchema] for the wire contract.
 *
 * Reconnection: socket.io-client-java reconnects with the backoff configured in
 * [createSocket]. On *every* connect — initial and each reconnect — [rejoinAndSync]
 * re-emits `join-meeting`, so the broker re-broadcasts our presence and replies with the
 * current roster. That lets the mesh rebuild any links torn down while we were dropped.
 *
 * Outbound messages produced while the socket is down are queued rather than dropped.
 * ICE candidates in particular are generated continuously by the gathering process and
 * silently losing them during a brief reconnect leaves a link that never completes.
 */
internal class SocketIOSignalingClient(
    private val url: String,
    private val userId: String,
    private val userName: String,
) : SignalingClient {

    private val _events = MutableSharedFlow<SignalEvent>(replay = 4, extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    @Volatile private var socket: Socket? = null
    private var currentMeetingId: String? = null

    /** Whether this participant is entitled to open the meeting — see [rejoinAndSync]. */
    @Volatile private var createIfMissing = false

    /**
     * Kept for the lifetime of the session, not just the first join: if the host is the
     * last one out and reconnects into a meeting that emptied, the re-created meeting has
     * to come back private. Dropping the flag on reconnect would quietly unlock the door.
     */
    @Volatile private var isPrivate = false

    /**
     * Set once the broker has accepted us into the meeting. From then on every reconnect
     * may re-create it: the meeting can only have gone away because *we* were the last
     * one in it and got dropped, and that must not lock us out of our own call.
     */
    @Volatile private var established = false

    /** Messages produced while the socket was down, replayed in order on reconnect. */
    private val pending = ArrayDeque<Pair<String, JSONObject>>()

    override suspend fun connect(meetingId: String, createIfMissing: Boolean, isPrivate: Boolean) {
        currentMeetingId = meetingId
        this.createIfMissing = createIfMissing
        this.isPrivate = isPrivate
        established = false
        // A fresh client is created per session, so disconnect() clears all handler state
        // and a previous socket is never reused here.
        val s = createSocket(url)
        socket = s
        wire(s)
        MeshLog.i(TAG) { "connecting to $url as $userId" }
        s.connect()
    }

    private fun wire(s: Socket) {
        s.on(Socket.EVENT_CONNECT) {
            MeshLog.i(TAG) { "connected" }
            rejoinAndSync(s)
            flushPending(s)
        }
        s.on(Socket.EVENT_DISCONNECT) {
            MeshLog.w(TAG, "disconnected")
            _events.tryEmit(SignalEvent.SignalingDisconnected)
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            MeshLog.w(TAG, "connect error: ${args.firstOrNull()}")
            _events.tryEmit(SignalEvent.SignalingDisconnected)
        }

        s.on(SignalingSchema.TYPE_PEER_JOINED) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                _events.tryEmit(
                    SignalEvent.PeerJoined(
                        j.optString(SignalingSchema.KEY_USER_ID),
                        j.optString(SignalingSchema.KEY_USER_NAME),
                        j.optString(SignalingSchema.KEY_MEETING, currentMeetingId.orEmpty()),
                    ),
                )
            }
        }
        s.on(SignalingSchema.TYPE_PEER_LEFT) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                _events.tryEmit(SignalEvent.PeerLeft(j.optString(SignalingSchema.KEY_PEER_ID)))
            }
        }
        s.on(SignalingSchema.TYPE_OFFER) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val sdp = j.requireObject(SignalingSchema.KEY_SDP, SignalingSchema.TYPE_OFFER)
                    ?: return@let
                MeshLog.i(TAG) { "<- offer from ${j.optString(SignalingSchema.KEY_FROM)}" }
                _events.tryEmit(
                    SignalEvent.Offer(
                        j.optString(SignalingSchema.KEY_FROM),
                        SignalingSchema.SdpPayload.fromJson(sdp),
                    ),
                )
            }
        }
        s.on(SignalingSchema.TYPE_ANSWER) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val sdp = j.requireObject(SignalingSchema.KEY_SDP, SignalingSchema.TYPE_ANSWER)
                    ?: return@let
                MeshLog.i(TAG) { "<- answer from ${j.optString(SignalingSchema.KEY_FROM)}" }
                _events.tryEmit(
                    SignalEvent.Answer(
                        j.optString(SignalingSchema.KEY_FROM),
                        SignalingSchema.SdpPayload.fromJson(sdp),
                    ),
                )
            }
        }
        s.on(SignalingSchema.TYPE_ICE_CANDIDATE) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val c = j.requireObject(
                    SignalingSchema.KEY_ICE_CANDIDATE,
                    SignalingSchema.TYPE_ICE_CANDIDATE,
                ) ?: return@let
                _events.tryEmit(
                    SignalEvent.IceCandidate(
                        j.optString(SignalingSchema.KEY_FROM),
                        SignalingSchema.IceCandidatePayload.fromJson(c),
                    ),
                )
            }
        }
        s.on(SignalingSchema.TYPE_PEER_STATE) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val st = j.requireObject(SignalingSchema.KEY_STATE, SignalingSchema.TYPE_PEER_STATE)
                    ?: return@let
                _events.tryEmit(
                    SignalEvent.PeerState(
                        j.optString(SignalingSchema.KEY_FROM),
                        SignalingSchema.PeerStatePayload.fromJson(st),
                    ),
                )
            }
        }
        s.on(SignalingSchema.TYPE_MUTE_REQUEST) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                _events.tryEmit(SignalEvent.MuteRequest(j.optString(SignalingSchema.KEY_FROM)))
            }
        }
        s.on(SignalingSchema.TYPE_MEETING_MEMBERS) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val peers = j.optJSONArray(SignalingSchema.KEY_PEERS)?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let(SignalingSchema.MeetingPeerInfo::fromJson)
                    }
                } ?: emptyList()
                MeshLog.i(TAG) { "roster: ${peers.size} peer(s)" }
                // The broker only sends a roster to a socket it accepted, so this is the
                // point where we are provably in the meeting.
                established = true
                _events.tryEmit(
                    SignalEvent.MeetingSnapshot(
                        peers,
                        j.optString(SignalingSchema.KEY_MEETING, currentMeetingId.orEmpty()),
                    ),
                )
            }
        }
        s.on(SignalingSchema.TYPE_MEETING_NOT_FOUND) { args ->
            val meeting = args.firstOrNull()?.asJson()
                ?.optString(SignalingSchema.KEY_MEETING)
                ?.takeIf { it.isNotEmpty() }
                ?: currentMeetingId.orEmpty()
            MeshLog.w(TAG, "join refused: meeting $meeting does not exist")
            // Nothing further will arrive on this socket, and socket.io would otherwise
            // keep reconnecting and re-asking forever.
            s.disconnect()
            _events.tryEmit(SignalEvent.MeetingNotFound(meeting))
        }
        s.on(SignalingSchema.TYPE_AWAITING_APPROVAL) { args ->
            val meeting = args.firstOrNull()?.asJson()
                ?.optString(SignalingSchema.KEY_MEETING)
                ?.takeIf { it.isNotEmpty() }
                ?: currentMeetingId.orEmpty()
            MeshLog.i(TAG) { "waiting for the host to admit us to $meeting" }
            // Deliberately stays connected: the socket *is* the pending request, and
            // dropping it withdraws the knock.
            _events.tryEmit(SignalEvent.AwaitingApproval(meeting))
        }
        s.on(SignalingSchema.TYPE_JOIN_DENIED) { args ->
            val meeting = args.firstOrNull()?.asJson()
                ?.optString(SignalingSchema.KEY_MEETING)
                ?.takeIf { it.isNotEmpty() }
                ?: currentMeetingId.orEmpty()
            MeshLog.w(TAG, "host declined our request to join $meeting")
            s.disconnect()
            _events.tryEmit(SignalEvent.JoinDenied(meeting))
        }
        s.on(SignalingSchema.TYPE_KNOCK) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val peerId = j.optString(SignalingSchema.KEY_USER_ID)
                if (peerId.isEmpty()) return@let
                MeshLog.i(TAG) { "knock from $peerId" }
                _events.tryEmit(
                    SignalEvent.Knock(peerId, j.optString(SignalingSchema.KEY_USER_NAME)),
                )
            }
        }
        s.on(SignalingSchema.TYPE_KNOCK_WITHDRAWN) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val peerId = j.optString(SignalingSchema.KEY_USER_ID)
                if (peerId.isNotEmpty()) _events.tryEmit(SignalEvent.KnockWithdrawn(peerId))
            }
        }
        s.on(SignalingSchema.TYPE_ERROR) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val message = j.optString(SignalingSchema.KEY_ERROR)
                MeshLog.w(TAG, "broker error: $message")
                _events.tryEmit(SignalEvent.ErrorReceived(message))
            }
        }
    }

    /** Re-announce presence + request the roster. Fires on initial connect and every reconnect. */
    private fun rejoinAndSync(s: Socket) {
        val meeting = currentMeetingId ?: return
        s.emit(
            SignalingSchema.TYPE_JOIN_MEETING,
            JSONObject().apply {
                put(SignalingSchema.KEY_MEETING, meeting)
                put(SignalingSchema.KEY_USER_ID, userId)
                put(SignalingSchema.KEY_USER_NAME, userName)
                put(SignalingSchema.KEY_CREATE, createIfMissing || established)
                put(SignalingSchema.KEY_PRIVATE, isPrivate)
            },
        )
    }

    override suspend fun sendMessage(msgType: String, targetPeerId: String?, payload: String) {
        val o = try {
            JSONObject(payload)
        } catch (e: Exception) {
            MeshLog.e(TAG, "malformed $msgType payload; dropped", e)
            return
        }
        if (targetPeerId != null) o.put(SignalingSchema.KEY_TO, targetPeerId)

        val s = socket
        if (s == null || !s.connected()) {
            enqueue(msgType, o)
            return
        }
        s.emit(msgType, o)
    }

    /** Buffer an outbound message until the socket is back, bounded so a long outage can't grow forever. */
    private fun enqueue(msgType: String, payload: JSONObject) {
        synchronized(pending) {
            while (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(msgType to payload)
        }
        MeshLog.d(TAG) { "queued $msgType while offline (${pending.size} pending)" }
    }

    private fun flushPending(s: Socket) {
        val drained = synchronized(pending) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        MeshLog.i(TAG) { "flushing ${drained.size} queued message(s)" }
        drained.forEach { (type, payload) -> s.emit(type, payload) }
    }

    override suspend fun disconnect() {
        synchronized(pending) { pending.clear() }
        socket?.let {
            it.off()
            it.disconnect()
        }
        socket = null
        currentMeetingId = null
        MeshLog.i(TAG) { "disconnected and released" }
    }

    private fun createSocket(url: String): Socket {
        val opts = IO.Options().apply {
            forceNew = true
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 2000
            reconnectionDelayMax = 10000
            randomizationFactor = 0.4
            timeout = 12_000
        }
        return IO.socket(url, opts)
    }

    /**
     * Read a required nested object, loudly.
     *
     * These lookups used to be `optJSONObject(key) ?: return@let`, which discarded the
     * message without a trace. A sender that flattened the payload — putting a raw SDP
     * string where an object belonged — therefore produced a meeting that connected,
     * exchanged nothing, and showed no error anywhere. Never fail silently here.
     */
    private fun JSONObject.requireObject(key: String, event: String): JSONObject? {
        optJSONObject(key)?.let { return it }
        MeshLog.w(
            TAG,
            "malformed $event: \"$key\" is ${if (has(key)) "not an object" else "missing"} " +
                "— dropped. keys=${keys().asSequence().toList()}",
        )
        return null
    }

    private fun Any?.asJson(): JSONObject? = when (this) {
        is JSONObject -> this
        is String -> try {
            JSONObject(this)
        } catch (e: Exception) {
            null
        }
        else -> null
    }

    private companion object {
        const val TAG = "Signaling"

        /** Cap the offline queue; a long outage should not grow memory without bound. */
        const val MAX_PENDING = 256
    }
}
