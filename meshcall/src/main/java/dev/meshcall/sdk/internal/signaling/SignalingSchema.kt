package dev.meshcall.sdk.internal.signaling

import org.json.JSONObject

/**
 * Explicit signaling message schema exchanged over the Socket.IO broker.
 *
 * The broker is a dumb relay: it receives a message on namespace `/room` and re-emits
 * it to the targeted peer (or broadcasts room membership). It never inspects SDP or
 * ICE payloads and never touches media bits — those flow directly between peers over
 * WebRTC, out of band.
 *
 * Every message is a JSON object with at least a `type` field. The `Payload`
 * subclasses below mirror the JSON shape so the mesh engine can build/parse them
 * without a serializer dependency.
 *
 * Wire contract (JSON):
 *   { "type": "join-room", "payload": { "roomId": "...", "userId": "...", "userName": "..." } }
 *   { "type": "peer-joined", "peer": { "id": "...", "userName": "..." }, "room": "..." }
 *   { "type": "peer-left", "peerId": "..." }
 *   { "type": "offer", "from": "...", "to": "...", "sdp": { "type": "offer", "sdp": "..." } }
 *   { "type": "answer", "from": "...", "to": "...", "sdp": { "type": "answer", "sdp": "..." } }
 *   { "type": "ice-candidate", "from": "...", "to": "...", "candidate": { "candidate": "...", "sdpMLineIndex": 0, "sdpMid": "0" } }
 *   { "type": "peer-state", "from": "...", "to": "...", "state": { "micEnabled": true, "cameraEnabled": true } }
 *   { "type": "error", "error": "...", "room": "..." }
 */
object SignalingSchema {

    // Jackson-less: tiny JSON helpers mirroring the exact wire shape.
    const val KEY_TYPE = "type"
    const val KEY_PAYLOAD = "payload"
    const val KEY_ROOM = "room"
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

    const val TYPE_JOIN_ROOM = "join-room"
    const val TYPE_PEER_JOINED = "peer-joined"
    const val TYPE_PEER_LEFT = "peer-left"
    const val TYPE_OFFER = "offer"
    const val TYPE_ANSWER = "answer"
    const val TYPE_ICE_CANDIDATE = "ice-candidate"
    const val TYPE_PEER_STATE = "peer-state"
    const val TYPE_ERROR = "error"

    /** A peer known to the room (only ever via [RoomPeerInfo] in peer-joined). */
    data class RoomPeerInfo(
        val id: String,
        val userName: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put(KEY_USER_ID, id)
            put(KEY_USER_NAME, userName)
        }

        companion object {
            fun fromJson(o: JSONObject): RoomPeerInfo =
                RoomPeerInfo(o.optString(KEY_USER_ID), o.optString(KEY_USER_NAME))
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
            fun fromJson(o: JSONObject): SdpPayload = SdpPayload(o.optString("type"), o.optString("sdp"))
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

    /** Broadcast peer media state (so remote UIs can show "muted" indicators). */
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
                PeerStatePayload(o.optBoolean("micEnabled", true), o.optBoolean("cameraEnabled", true))
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)
}
