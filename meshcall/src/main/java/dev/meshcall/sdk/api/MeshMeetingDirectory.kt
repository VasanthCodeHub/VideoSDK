package dev.meshcall.sdk.api

import dev.meshcall.sdk.internal.signaling.MeetingLookupClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Live state of one meeting code, as the broker sees it right now. */
data class MeetingStatus(
    val meetingId: String,
    /** People currently in the meeting. Zero means the code is not live. */
    val participantCount: Int,
    /** Private meetings are joinable, but only after the host admits you. */
    val isPrivate: Boolean = false,
) {
    val isLive: Boolean get() = participantCount > 0
}

/**
 * Ask the broker which meeting codes are live, without joining anything.
 *
 * A meeting exists only while somebody is in it — the broker keeps no meeting records —
 * so this is the only way to answer "can I still join that code?" before committing to a
 * join. Use it to validate a typed code and to decide whether a past meeting can be
 * rejoined.
 *
 * ```
 * when (val live = MeshMeetingDirectory.isLive(brokerUrl, "ABC123")) {
 *     true  -> startMeeting("ABC123")
 *     false -> toast("No meeting with that code")
 *     null  -> toast("Can't reach the meeting server")   // don't blame the user's code
 * }
 * ```
 *
 * Every call opens and closes its own short-lived connection, so batch codes into one
 * [status] call rather than looping over [isLive].
 */
object MeshMeetingDirectory {

    /** Default ceiling for a lookup. Short: this runs while somebody waits on a button. */
    const val DEFAULT_TIMEOUT_MS = 6_000L

    /**
     * Status for each of [meetingIds].
     *
     * @return one entry per requested code, or **null** if the broker could not be
     *   reached. Null is not "nothing is live" — treat it as "unknown" and say so, rather
     *   than telling the user their code is wrong when the server is simply down.
     */
    suspend fun status(
        brokerUrl: String,
        meetingIds: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<MeetingStatus>? = withContext(Dispatchers.IO) {
        MeetingLookupClient.check(brokerUrl, meetingIds, timeoutMs)?.values?.toList()
    }

    /** Convenience for one code. Null when the broker could not be reached. */
    suspend fun isLive(
        brokerUrl: String,
        meetingId: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean? {
        val statuses = status(brokerUrl, listOf(meetingId), timeoutMs) ?: return null
        // A reply that omits the code still counts as an answer: the broker was reached
        // and did not report anyone in it.
        return statuses.firstOrNull { it.meetingId == meetingId }?.isLive ?: false
    }
}
