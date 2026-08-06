package dev.meshcall.sdk.internal.demo

import dev.meshcall.sdk.internal.signaling.SignalEvent
import dev.meshcall.sdk.internal.signaling.SignalingClient
import dev.meshcall.sdk.internal.signaling.SignalingSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Offline stand-in for the signaling broker.
 *
 * Emits a plausible simulated session so the meeting UI (tiles, badges, roster churn,
 * speaker promotion) can be developed while the real Node.js broker does not exist. It is
 * a plain [SignalingClient] — the mesh does not care what produces [SignalEvent]s — so
 * deleting this file cannot affect the production path.
 *
 * Simulated behaviour:
 *  - [MockMeetingData.PREFILL_PEERS] peers already seated → `meeting-members` snapshot.
 *  - The rest join one by one, staggered, via `peer-joined`.
 *  - A random peer mutes/unmutes every few seconds.
 *  - A rotating "speaker" so speaker promotion can be demoed.
 *  - No real SDP/ICE: offers are dropped, so connections stay in "connecting" — exactly
 *    the state the placeholder + connection-dot UI must render.
 */
internal class MockSignalingClient(
    private val userId: String,
    private val userName: String,
    simulatedPeerCount: Int,
) : SignalingClient {

    /** Participant identities we simulate (excluding ourselves). */
    private val simulatedPeers: List<Pair<String, String>> =
        MockMeetingData.participants
            .take(
                simulatedPeerCount.coerceIn(
                    MockMeetingData.PREFILL_PEERS,
                    MockMeetingData.participants.size,
                ),
            )
            .mapIndexed { index, name -> MockMeetingData.peerId(index) to name }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var churnJob: Job? = null
    private var speakerJob: Job? = null

    private val _events = MutableSharedFlow<SignalEvent>(replay = 16, extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    /** Peer ids known to be in the meeting, with the last state we broadcast for them. */
    private val joined = LinkedHashMap<String, Pair<Boolean, Boolean>>()

    override suspend fun connect(meetingId: String) {
        joined[userId] = true to true

        // 1) Pre-filled roster: a couple of peers already seated.
        val prefilled = simulatedPeers.take(MockMeetingData.PREFILL_PEERS)
        prefilled.forEach { (id, _) -> joined[id] = true to true }
        _events.emit(
            SignalEvent.MeetingSnapshot(
                prefilled.map { (id, name) -> SignalingSchema.MeetingPeerInfo(id, name) },
                meetingId,
            ),
        )

        // 2) The rest trickle in, simulating people tapping "join".
        simulatedPeers.drop(MockMeetingData.PREFILL_PEERS).forEachIndexed { index, (id, name) ->
            scope.launch {
                delay(MockMeetingData.JOIN_STAGGER_MS * (index + 1))
                joined[id] = true to true
                _events.emit(SignalEvent.PeerJoined(id, name, meetingId))
            }
        }

        startStateChurn()
        startSpeakerChurn()
    }

    /**
     * Rotate a simulated speaker so "the active speaker moves into the main grid" can be
     * demoed offline: pick someone, have them talk for a while, hand the mic on.
     */
    private fun startSpeakerChurn() {
        speakerJob?.cancel()
        speakerJob = scope.launch {
            var current: String? = null
            while (true) {
                delay(MockMeetingData.SPEAKING_GAP_MS)
                current?.let { _events.emit(SignalEvent.PeerSpeaking(it, false)) }
                val others = joined.keys.filter { it != userId }
                if (others.isEmpty()) continue
                val next = others.random()
                current = next
                _events.emit(SignalEvent.PeerSpeaking(next, true))
                delay(MockMeetingData.SPEAKING_ON_MS)
            }
        }
    }

    /** Randomly mute/unmute a peer so badges + the participant list react live. */
    private fun startStateChurn() {
        churnJob?.cancel()
        churnJob = scope.launch {
            while (true) {
                delay(MockMeetingData.STATE_CHURN_MS * (1 + Random.nextInt(3)))
                val others = joined.keys.filter { it != userId }
                if (others.isEmpty()) continue
                val victim = others.random()
                val (mic, cam) = joined.getValue(victim)
                val next = !mic to cam
                joined[victim] = next
                _events.emit(
                    SignalEvent.PeerState(
                        victim,
                        SignalingSchema.PeerStatePayload(next.first, next.second),
                    ),
                )
            }
        }
    }

    override suspend fun sendMessage(msgType: String, targetPeerId: String?, payload: String) {
        // No broker to relay to: offers/answers are dropped on purpose, which keeps the
        // simulated peers in "connecting" and exercises the placeholder UI.
    }

    override suspend fun disconnect() {
        churnJob?.cancel()
        churnJob = null
        speakerJob?.cancel()
        speakerJob = null
        joined.clear()
        scope.cancel()
    }
}
