package dev.meshcall.sdk.internal.signaling

import kotlinx.coroutines.flow.Flow

/**
 * Thin, swappable signaling transport abstraction.
 *
 * The mesh engine depends only on this interface — never on the concrete Socket.IO
 * implementation — so the broker can later be reached over a raw WebSocket, MQTT, or
 * Firebase without touching the WebRTC engine. The current production-path is
 * [SocketIOSignalingClient].
 *
 * Implementations MUST:
 *  - deliver all events on the same single-threaded dispatcher they were given (the
 *    mesh pipeline is confined), or document otherwise;
 *  - be symmetric in ordering: event order is preserved for a given peer pairing.
 */
interface SignalingClient {

    /** Connect to the broker and join [roomId] under our own identity. */
    suspend fun connect(roomId: String)

    /**
     * Push a message. `targetPeerId` is null for room-scoped broadcasts.
     * Message payload is a JSON string already shaped by [SignalingSchema].
     */
    suspend fun sendMessage(msgType: String, targetPeerId: String?, payload: String)

    /** Close the connection and release resources. */
    suspend fun disconnect()

    /** Hot stream of decoded inbound signaling events. */
    val events: Flow<SignalEvent>
}

/** Decoded, type-safe inbound event delivered to the mesh engine. */
sealed class SignalEvent {

    /** A peer announced they joined the room and are listening for our connection. */
    data class PeerJoined(
        val peerId: String,
        val userName: String,
        val roomId: String,
    ) : SignalEvent()

    /** A peer left the room (or the broker timed them out). */
    data class PeerLeft(val peerId: String) : SignalEvent()

    /** SDP offer from [fromId], targeted at us. */
    data class Offer(val fromId: String, val sdp: SignalingSchema.SdpPayload) : SignalEvent()

    /** SDP answer from [fromId] in response to an offer we sent. */
    data class Answer(val fromId: String, val sdp: SignalingSchema.SdpPayload) : SignalEvent()

    /** ICE candidate from [fromId] targeted at us. */
    data class IceCandidate(
        val fromId: String,
        val candidate: SignalingSchema.IceCandidatePayload,
    ) : SignalEvent()

    /** Broadcast media state change notification from [fromId]. */
    data class PeerState(
        val fromId: String,
        val state: SignalingSchema.PeerStatePayload,
    ) : SignalEvent()

    /** A peer started/stopped talking (demo simulation; real path uses audio levels). */
    data class PeerSpeaking(
        val peerId: String,
        val speaking: Boolean,
    ) : SignalEvent()

    /** The connected peer list snapshot after (re)joining the room. */
    data class RoomSnapshot(val peers: List<SignalingSchema.RoomPeerInfo>, val roomId: String?) : SignalEvent()

    /** Signing the wiring is healthy but the broker replied with a fault. */
    data class ErrorReceived(val message: String) : SignalEvent()

    /** Broker connection dropped (before auto-reconnect fired); mesh can prepare. */
    data object SignalingDisconnected : SignalEvent()
}
