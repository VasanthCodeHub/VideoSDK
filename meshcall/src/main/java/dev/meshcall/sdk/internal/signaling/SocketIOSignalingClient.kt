package dev.meshcall.sdk.internal.signaling

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

/**
 * Socket.IO signaling transport.
 *
 * Talks to a lightweight broker. Events:
 *   client→server : join-room, offer, answer, ice-candidate, peer-state
 *   server→client : peer-joined, peer-left, offer, answer, ice-candidate, peer-state,
 *                   room-members (roster snapshot after join/rejoin), error
 *
 * Reconnection: socket.io-client-java reconnects with the backoff configured in
 * [createSocket]. On *every* connect — initial and each reconnect — we call
 * [rejoinAndSync] to re-emit join-room, so the broker re-broadcasts our presence and
 * replies with the current roster. That lets the mesh manager rebuild any links that
 * were torn down while the socket was dropped.
 */
class SocketIOSignalingClient(
    private val url: String,
    private val userId: String,
    private val userName: String,
) : SignalingClient {

    private val _events = MutableSharedFlow<SignalEvent>(replay = 4, extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    @Volatile private var socket: Socket? = null
    private var currentRoomId: String? = null

    override suspend fun connect(roomId: String) {
        currentRoomId = roomId
        // A fresh client instance is created per room session, so disconnect() clears
        // all handler state and a previous socket is never reused here.
        val s = createSocket(url)
        socket = s
        wire(s)
        // socket.io emits EVENT_CONNECT asynchronously; wire() handles presence + roster.
        s.connect()
    }

    private fun wire(s: Socket) {
        // Any (re)connect re-announces presence and pulls the roster.
        s.on(Socket.EVENT_CONNECT) { rejoinAndSync(s) }
        s.on(Socket.EVENT_DISCONNECT) { _events.tryEmit(SignalEvent.SignalingDisconnected) }

        s.on(SignalingSchema.TYPE_PEER_JOINED) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                _events.tryEmit(
                    SignalEvent.PeerJoined(
                        j.optString(SignalingSchema.KEY_USER_ID),
                        j.optString(SignalingSchema.KEY_USER_NAME),
                        j.optString(SignalingSchema.KEY_ROOM, currentRoomId.orEmpty()),
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
                val sdp = j.optJSONObject(SignalingSchema.KEY_SDP) ?: return@let
                _events.tryEmit(
                    SignalEvent.Offer(
                        j.optString(SignalingSchema.KEY_FROM),
                        SignalingSchema.SdpPayload(sdp.optString("type"), sdp.optString("sdp")),
                    ),
                )
            }
        }
        s.on(SignalingSchema.TYPE_ANSWER) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val sdp = j.optJSONObject(SignalingSchema.KEY_SDP) ?: return@let
                _events.tryEmit(
                    SignalEvent.Answer(
                        j.optString(SignalingSchema.KEY_FROM),
                        SignalingSchema.SdpPayload(sdp.optString("type"), sdp.optString("sdp")),
                    ),
                )
            }
        }
        s.on(SignalingSchema.TYPE_ICE_CANDIDATE) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val c = j.optJSONObject(SignalingSchema.KEY_ICE_CANDIDATE) ?: return@let
                _events.tryEmit(
                    SignalEvent.IceCandidate(
                        j.optString(SignalingSchema.KEY_FROM),
                        SignalingSchema.IceCandidatePayload(
                            c.optString("candidate"),
                            c.optInt("sdpMLineIndex", -1),
                            if (c.isNull("sdpMid")) null else c.optString("sdpMid"),
                        ),
                    ),
                )
            }
        }
        s.on(SignalingSchema.TYPE_PEER_STATE) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val st = j.optJSONObject(SignalingSchema.KEY_STATE) ?: return@let
                _events.tryEmit(
                    SignalEvent.PeerState(
                        j.optString(SignalingSchema.KEY_FROM),
                        SignalingSchema.PeerStatePayload(
                            st.optBoolean("micEnabled", true),
                            st.optBoolean("cameraEnabled", true),
                        ),
                    ),
                )
            }
        }
        s.on(EVENT_ROOM_MEMBERS) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                val peers = j.optJSONArray(SignalingSchema.KEY_PEERS)?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { o -> SignalingSchema.RoomPeerInfo.fromJson(o) }
                    }
                } ?: emptyList()
                _events.tryEmit(SignalEvent.RoomSnapshot(peers, j.optString(SignalingSchema.KEY_ROOM, null)))
            }
        }
        s.on(SignalingSchema.TYPE_ERROR) { args ->
            args.firstOrNull()?.asJson()?.let { j ->
                _events.tryEmit(SignalEvent.ErrorReceived(j.optString(SignalingSchema.KEY_ERROR)))
            }
        }
    }

    /** Re-emit presence + request roster. Called on initial connect and every reconnect. */
    private fun rejoinAndSync(s: Socket) {
        val room = currentRoomId ?: return
        s.emit(
            SignalingSchema.TYPE_JOIN_ROOM,
            JSONObject().apply {
                put(SignalingSchema.KEY_ROOM, room)
                put(SignalingSchema.KEY_USER_ID, userId)
                put(SignalingSchema.KEY_USER_NAME, userName)
            },
        )
    }

    override suspend fun sendMessage(msgType: String, targetPeerId: String?, payload: String) {
        val s = socket
        if (s == null || !s.connected()) return
        val o = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return
        }
        if (targetPeerId != null) o.put(SignalingSchema.KEY_TO, targetPeerId)
        s.emit(msgType, o)
    }

    override suspend fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
        currentRoomId = null
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

    private fun Any?.asJson(): JSONObject? =
        when (this) {
            is JSONObject -> this
            is String -> try {
                JSONObject(this)
            } catch (e: Exception) {
                null
            }
            else -> null
        }

    companion object {
        private const val EVENT_ROOM_MEMBERS = "room-members"
    }
}
