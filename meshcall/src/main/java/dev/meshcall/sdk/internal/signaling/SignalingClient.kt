package dev.meshcall.sdk.internal.signaling

import kotlinx.coroutines.flow.Flow

/**
 * Thin, swappable signaling transport.
 *
 * The mesh depends only on this interface — never on the concrete Socket.IO
 * implementation — so the broker can later be reached over a raw WebSocket, MQTT, or
 * anything else without touching the WebRTC engine. Production uses
 * [SocketIOSignalingClient]; offline demo mode uses
 * `dev.meshcall.sdk.internal.demo.MockSignalingClient`.
 *
 * Implementations MUST preserve event order for a given peer pairing — an `answer` that
 * overtakes its `offer` breaks negotiation.
 */
internal interface SignalingClient {

    /** Connect to the broker and join [meetingId] under our own identity. */
    suspend fun connect(meetingId: String)

    /**
     * Push a message. [targetPeerId] is null for meeting-wide broadcasts. [payload] is a
     * JSON string already shaped by [SignalingSchema].
     */
    suspend fun sendMessage(msgType: String, targetPeerId: String?, payload: String)

    /** Close the connection and release resources. */
    suspend fun disconnect()

    /** Hot stream of decoded inbound signaling events. */
    val events: Flow<SignalEvent>
}

/** Decoded, type-safe inbound event delivered to the mesh. */
internal sealed class SignalEvent {

    /** A peer announced they joined the meeting and are listening for our connection. */
    data class PeerJoined(
        val peerId: String,
        val userName: String,
        val meetingId: String,
    ) : SignalEvent()

    /** A peer left the meeting (or the broker timed them out). */
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

    /** Media state change broadcast by [fromId]. */
    data class PeerState(
        val fromId: String,
        val state: SignalingSchema.PeerStatePayload,
    ) : SignalEvent()

    /** A peer started/stopped talking. Demo simulation only; the real path uses audio levels. */
    data class PeerSpeaking(
        val peerId: String,
        val speaking: Boolean,
    ) : SignalEvent()

    /** Participant roster snapshot delivered after every (re)join. */
    data class MeetingSnapshot(
        val peers: List<SignalingSchema.MeetingPeerInfo>,
        val meetingId: String?,
    ) : SignalEvent()

    /** The transport is healthy but the broker reported a fault. */
    data class ErrorReceived(val message: String) : SignalEvent()

    /** Broker connection dropped (auto-reconnect will follow); the mesh can prepare. */
    data object SignalingDisconnected : SignalEvent()
}
