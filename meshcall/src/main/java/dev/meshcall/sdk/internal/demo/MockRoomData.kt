package dev.meshcall.sdk.internal.demo

/**
 * Mock data used by [MockSignalingClient] to simulate a populated room while the real
 * Node.js broker is not running. Dev/demo only — never referenced by the production
 * signaling path (SocketIOSignalingClient).
 */
internal object MockRoomData {

    /** Pool of participant names the demo pulls from when simulating a crowded room. */
    val participants: List<String> = listOf(
        "Alex",
        "Priya",
        "Marco",
        "Zoe",
        "Ravi",
        "Lena",
        "Omar",
        "Mei",
        "Ivan",
        "Sofia",
    )

    /** Deterministic mock id per index so reconnects produce stable peer identities. */
    fun peerId(index: Int): String = "mock-peer-$index"

    /** How many peers the room "starts with" before the call (roster prefill). */
    const val PREFILL_PEERS = 2

    /** Per-peer stagger (ms) between simulated joins — mimics people tapping join. */
    const val JOIN_STAGGER_MS = 1_200L

    /** Interval (ms) at which the simulator flips a random peer's media state. */
    const val STATE_CHURN_MS = 6_000L

    /** How long (ms) a simulated peer "talks" before stopping. */
    const val SPEAKING_ON_MS = 3_000L

    /** Interval (ms) between two simulated speakers starting to talk. */
    const val SPEAKING_GAP_MS = 4_500L
}
