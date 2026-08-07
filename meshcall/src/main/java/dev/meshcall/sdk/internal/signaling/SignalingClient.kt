package dev.meshcall.sdk.internal.signaling

import kotlinx.coroutines.flow.Flow

/**
 * Thin, swappable signaling transport.
 *
 * The mesh depends only on this interface — never on the concrete Socket.IO
 * implementation — so the broker can later be reached over a raw WebSocket, MQTT, or
 * anything else without touching the WebRTC engine. [SocketIOSignalingClient] is the
 * only implementation.
 *
 * Implementations MUST preserve event order for a given peer pairing — an `answer` that
 * overtakes its `offer` breaks negotiation.
 */
internal interface SignalingClient {

    /**
     * Connect to the broker and join [meetingId] under our own identity.
     *
     * @param createIfMissing open the meeting when the broker has no record of it. Only
     *   the participant who started the meeting may do this; everyone else must be
     *   refused with [SignalEvent.MeetingNotFound] rather than silently landing in an
     *   empty meeting of their own making.
     * @param isPrivate open the meeting as private — every later joiner has to be admitted
     *   by the host. Only honored together with [createIfMissing]; the broker ignores it
     *   on any join that does not create the meeting.
     */
    suspend fun connect(
        meetingId: String,
        createIfMissing: Boolean = false,
        isPrivate: Boolean = false,
    )

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

    /** [fromId] is asking us to mute our mic. Honored automatically — see [MeshCall.requestMute]. */
    data class MuteRequest(val fromId: String) : SignalEvent()

    /** Participant roster snapshot delivered after every (re)join. */
    data class MeetingSnapshot(
        val peers: List<SignalingSchema.MeetingPeerInfo>,
        val meetingId: String?,
    ) : SignalEvent()

    /**
     * The broker refused the join: no meeting with that code is live. Terminal for this
     * session — nothing else will arrive, so the host should leave and say so.
     */
    data class MeetingNotFound(val meetingId: String) : SignalEvent()

    /** Private meeting: we are in the waiting room until the host decides. */
    data class AwaitingApproval(val meetingId: String) : SignalEvent()

    /** Private meeting: the host declined. Terminal, like [MeetingNotFound]. */
    data class JoinDenied(val meetingId: String) : SignalEvent()

    /** Someone is asking to be let into our private meeting. Delivered to the host only. */
    data class Knock(val peerId: String, val userName: String) : SignalEvent()

    /** A knocker gave up (or dropped) before the host decided. */
    data class KnockWithdrawn(val peerId: String) : SignalEvent()

    /** The transport is healthy but the broker reported a fault. */
    data class ErrorReceived(val message: String) : SignalEvent()

    /** Broker connection dropped (auto-reconnect will follow); the mesh can prepare. */
    data object SignalingDisconnected : SignalEvent()
}
