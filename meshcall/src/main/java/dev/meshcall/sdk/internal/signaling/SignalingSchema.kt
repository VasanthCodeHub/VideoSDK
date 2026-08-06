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
 *   join-meeting   { meeting, userId, userName }
 *   offer          { to, sdp: { type, sdp } }
 *   answer         { to, sdp: { type, sdp } }
 *   ice-candidate  { to, candidate: { candidate, sdpMLineIndex, sdpMid } }
 *   peer-state     { state: { micEnabled, cameraEnabled } }        // `to` optional
 *   mute-request   { to }
 *
 * Server → client (the broker always injects `from`):
 *   meeting-members { meeting, peers: [ { userId, userName } ] }
 *   peer-joined     { userId, userName, meeting }
 *   peer-left       { peerId }
 *   offer / answer  { from, sdp: {...} }
 *   ice-candidate   { from, candidate: {...} }
 *   peer-state      { from, state: {...} }
 *   mute-request    { from }
 *   error           { error }
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

    const val TYPE_JOIN_MEETING = "join-meeting"
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
