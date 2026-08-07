package dev.meshcall.sdk.internal.signaling

import org.json.JSONObject

/**
 * The wire contract with the Socket.IO broker.
 *
 * The broker is a dumb relay: it tracks who is in which meeting, forwards SDP/ICE/state
 * packets between participants, and never inspects a payload or touches media — that
 * flows directly peer-to-peer over WebRTC, out of band.
 *
 * Each message is a Socket.IO **event name** plus a flat JSON object. There is no
 * `{type, payload}` envelope; the event name is the type.
 *
 * Client → server:
 *   join-meeting   { meeting, userId, userName, create, private }
 *   admit-decision { userId, admit }                               // host only
 *   check-meetings { meetings: [ code ] }                          // Socket.IO ack reply
 *   offer          { to, sdp: { type, sdp } }
 *   answer         { to, sdp: { type, sdp } }
 *   ice-candidate  { to, candidate: { candidate, sdpMLineIndex, sdpMid } }
 *   peer-state     { state: { micEnabled, cameraEnabled } }        // `to` optional
 *   mute-request   { to }
 *
 * Server → client (the broker always injects `from`):
 *   meeting-members   { meeting, peers: [ { userId, userName } ] }
 *   meeting-not-found { meeting }
 *   awaiting-approval { meeting }                                  // private: you knocked
 *   join-denied       { meeting }                                  // private: host said no
 *   knock             { userId, userName, meeting }                // host only
 *   knock-withdrawn   { userId }                                   // host only
 *   peer-joined       { userId, userName, meeting }
 *   peer-left         { peerId }
 *   offer / answer    { from, sdp: {...} }
 *   ice-candidate     { from, candidate: {...} }
 *   peer-state        { from, state: {...} }
 *   mute-request      { from }
 *   error             { error }
 *
 * **A meeting exists only while at least one participant is in it.** The broker keeps no
 * meeting records, so `check-meetings` answers with a live participant count per code
 * (`{ meetings: [ { meeting, participants } ] }`, 0 meaning "not live"), and `join-meeting`
 * is refused with `meeting-not-found` unless the meeting already exists or `create` is
 * true. `create` belongs to the participant who started the meeting — and to anyone
 * already established in it whose socket reconnects, since a meeting that emptied while
 * its last participant was dropped must still be re-enterable by them.
 *
 * **Private meetings** are enforced by the broker, never by the client — a flag the
 * joining app could ignore would be no gate at all. `private` is honored only on the
 * `join-meeting` that *creates* the meeting; from then on anyone whose `userId` has not
 * been admitted lands in the meeting's pending list and gets `awaiting-approval` while
 * the host receives `knock`. Admission is keyed by `userId`, so a reconnect never sends
 * an established participant back to the waiting room, and `admit-decision` is obeyed
 * only from the host's own sockets.
 *
 * `mute-request` is a client-only contract today: the broker in `VideoSDKServer/server`
 * does not yet relay it, so [SocketIOSignalingClient] emits/listens for it but no
 * `mute-request` will actually reach a peer until the broker adds a matching handler
 * (mirroring how it already relays `offer`/`answer`/`ice-candidate` by `to`). Once that
 * lands server-side, this path needs no further client changes.
 *
 * See README §3. Any change here must land in the same commit as the README update.
 */
internal object SignalingSchema {

    const val KEY_MEETING = "meeting"
    const val KEY_USER_ID = "userId"
    const val KEY_USER_NAME = "userName"
    const val KEY_FROM = "from"
    const val KEY_TO = "to"
    const val KEY_SDP = "sdp"
    const val KEY_ICE_CANDIDATE = "candidate"
    const val KEY_ERROR = "error"
    const val KEY_STATE = "state"
    const val KEY_PEER_ID = "peerId"
    const val KEY_PEERS = "peers"
    const val KEY_CREATE = "create"
    const val KEY_PRIVATE = "private"
    const val KEY_ADMIT = "admit"
    const val KEY_MEETINGS = "meetings"
    const val KEY_PARTICIPANTS = "participants"

    const val TYPE_JOIN_MEETING = "join-meeting"
    const val TYPE_CHECK_MEETINGS = "check-meetings"
    const val TYPE_MEETING_NOT_FOUND = "meeting-not-found"
    const val TYPE_ADMIT_DECISION = "admit-decision"
    const val TYPE_AWAITING_APPROVAL = "awaiting-approval"
    const val TYPE_JOIN_DENIED = "join-denied"
    const val TYPE_KNOCK = "knock"
    const val TYPE_KNOCK_WITHDRAWN = "knock-withdrawn"
    const val TYPE_MEETING_MEMBERS = "meeting-members"
    const val TYPE_PEER_JOINED = "peer-joined"
    const val TYPE_PEER_LEFT = "peer-left"
    const val TYPE_OFFER = "offer"
    const val TYPE_ANSWER = "answer"
    const val TYPE_ICE_CANDIDATE = "ice-candidate"
    const val TYPE_PEER_STATE = "peer-state"
    const val TYPE_MUTE_REQUEST = "mute-request"
    const val TYPE_ERROR = "error"

    /** One participant in the meeting roster snapshot. */
    data class MeetingPeerInfo(
        val id: String,
        val userName: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put(KEY_USER_ID, id)
            put(KEY_USER_NAME, userName)
        }

        companion object {
            fun fromJson(o: JSONObject): MeetingPeerInfo =
                MeetingPeerInfo(o.optString(KEY_USER_ID), o.optString(KEY_USER_NAME))
        }
    }

    /** SDP from a PeerConnection session description (offer or answer). */
    data class SdpPayload(
        val type: String, // "offer" | "answer"
        val sdp: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type)
            put("sdp", sdp)
        }

        companion object {
            fun fromJson(o: JSONObject): SdpPayload =
                SdpPayload(o.optString("type"), o.optString("sdp"))
        }
    }

    /** ICE candidate, mirrored from RTCIceCandidate. */
    data class IceCandidatePayload(
        val candidate: String,
        val sdpMLineIndex: Int,
        val sdpMid: String?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("candidate", candidate)
            put("sdpMLineIndex", sdpMLineIndex)
            if (sdpMid != null) put("sdpMid", sdpMid)
        }

        companion object {
            fun fromJson(o: JSONObject): IceCandidatePayload =
                IceCandidatePayload(
                    o.optString("candidate"),
                    o.optInt("sdpMLineIndex", -1),
                    o.optStringOrNull("sdpMid"),
                )
        }
    }

    /** Broadcast media state so remote UIs can show "muted" indicators. */
    data class PeerStatePayload(
        val micEnabled: Boolean,
        val cameraEnabled: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("micEnabled", micEnabled)
            put("cameraEnabled", cameraEnabled)
        }

        companion object {
            fun fromJson(o: JSONObject): PeerStatePayload =
                PeerStatePayload(
                    o.optBoolean("micEnabled", true),
                    o.optBoolean("cameraEnabled", true),
                )
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)
}
