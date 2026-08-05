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
 * Emits a plausible simulated session so the UI (tiles, badges, roster churn) can be
 * developed and exercised while the real Node.js broker is not running. It is a pure
 * [SignalingClient] — the mesh manager does not care what produces [SignalEvent]s — so
 * dropping this file does not affect the production [SocketIOSignalingClient] path.
 *
 * Simulated behaviour:
 *  - [MockRoomData.PREFILL_PEERS] peers already present → `room-members` snapshot.
 *  - The remaining simulated peers join one by one (staggered) via `peer-joined`.
 *  - Media state churn: a random peer mutes/unmutes every few seconds.
 *  - No real SDP/ICE is exchanged: offers/answers sent by the mesh are dropped, so
 *    `PeerConnection`s stay in "connecting" — exactly the state the UI must render.
 */
internal class MockSignalingClient(
    private val userId: String,
    private val userName: String,
    simulatedPeerCount: Int,
) : SignalingClient {

    /** Participant identities we simulate (excluding ourselves). */
    private val simulatedPeers: List<Pair<String, String>> =
        MockRoomData.participants
            .take(simulatedPeerCount.coerceIn(MockRoomData.PREFILL_PEERS, MockRoomData.participants.size))
            .mapIndexed { index, name -> MockRoomData.peerId(index) to name }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var churnJob: Job? = null

    private val _events = MutableSharedFlow<SignalEvent>(replay = 16, extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    /** Peer ids known to be in the room, with the last state we broadcast for them. */
    private val joined = LinkedHashMap<String, Pair<Boolean, Boolean>>()

    override suspend fun connect(roomId: String) {
        // Presence: we are in the room too.
        joined[userId] = Pair(true, true)

        // 1) Pre-filled roster: a couple of peers already seated.
        val prefilled = simulatedPeers.take(MockRoomData.PREFILL_PEERS)
        prefilled.forEach { (id, name) -> joined[id] = Pair(true, true) }
        _events.emit(
            SignalEvent.RoomSnapshot(
                prefilled.map { (id, name) -> SignalingSchema.RoomPeerInfo(id, name) },
                roomId,
            ),
        )

        // 2) The rest trickle in, simulating people tapping "join".
        simulatedPeers.drop(MockRoomData.PREFILL_PEERS).forEachIndexed { index, (id, name) ->
            scope.launch {
                delay(MockRoomData.JOIN_STAGGER_MS * (index + 1))
                joined[id] = Pair(true, true)
                _events.emit(SignalEvent.PeerJoined(id, name, roomId))
            }
        }

        startStateChurn(roomId)
    }

    /** Randomly mute/unmute a peer so badges + participant list react live. */
    private fun startStateChurn(roomId: String) {
        churnJob?.cancel()
        churnJob = scope.launch {
            while (true) {
                delay(MockRoomData.STATE_CHURN_MS * (1 + Random.nextInt(3)))
                val peersInRoom = joined.keys.toList().filter { it != userId }
                if (peersInRoom.isEmpty()) continue
                val victim = peersInRoom.random()
                val (mic, cam) = joined.getValue(victim)
                val next = Pair(!mic, cam)
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
        // No real broker to relay to: offers/answers are dropped, mirroring an
        // unreachable broker from the client's perspective.
    }

    override suspend fun disconnect() {
        churnJob?.cancel()
        churnJob = null
        joined.clear()
        scope.cancel()
    }
}
