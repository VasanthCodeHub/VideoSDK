package dev.meshcall.sdk.internal.signaling

import dev.meshcall.sdk.internal.util.MeshLog
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * One-shot "is this meeting live?" lookup, outside any session.
 *
 * The lobby needs this *before* joining anything, so it cannot ride on the session's
 * socket — there isn't one yet. A short-lived connection is opened, `check-meetings` is
 * asked over a Socket.IO ack, and the socket is closed again; reconnection is disabled
 * because a lookup that has to wait out a backoff is worse than a lookup that fails.
 *
 * Returns null on *any* transport failure — unreachable broker, timeout, malformed reply.
 * Null is deliberately distinct from "no meetings are live": refusing a join because the
 * server is down and refusing it because the code is wrong are different messages to show.
 */
internal object MeetingLookupClient {

    /** @return participants per requested code, or null if the broker could not be reached. */
    suspend fun check(
        brokerUrl: String,
        meetingIds: List<String>,
        timeoutMs: Long,
    ): Map<String, Int>? {
        val requested = meetingIds.filter { it.isNotBlank() }.distinct()
        if (requested.isEmpty()) return emptyMap()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val socket = IO.socket(
                    brokerUrl,
                    IO.Options().apply {
                        forceNew = true
                        reconnection = false
                        timeout = timeoutMs
                    },
                )
                val done = AtomicBoolean(false)

                fun finish(result: Map<String, Int>?) {
                    // Every path below can race the others (ack vs. connect error vs.
                    // cancellation), and resuming a continuation twice is a crash.
                    if (!done.compareAndSet(false, true)) return
                    socket.off()
                    socket.disconnect()
                    if (continuation.isActive) continuation.resume(result)
                }

                socket.on(Socket.EVENT_CONNECT) {
                    socket.emit(
                        SignalingSchema.TYPE_CHECK_MEETINGS,
                        arrayOf<Any>(
                            JSONObject().put(
                                SignalingSchema.KEY_MEETINGS,
                                JSONArray(requested),
                            ),
                        ),
                        Ack { args -> finish(parse(args.firstOrNull(), requested)) },
                    )
                }
                socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                    MeshLog.w(TAG, "lookup connect error: ${args.firstOrNull()}")
                    finish(null)
                }

                continuation.invokeOnCancellation { finish(null) }
                socket.connect()
            }
        }
    }

    /**
     * Reply shape is `{ meetings: [ { meeting, participants } ] }`. Codes the broker left
     * out are reported as 0 rather than dropped, so callers can index the result by the
     * code they asked about without null-handling every entry.
     */
    private fun parse(raw: Any?, requested: List<String>): Map<String, Int>? {
        val json = raw as? JSONObject ?: return null
        val array = json.optJSONArray(SignalingSchema.KEY_MEETINGS) ?: return null

        val counts = HashMap<String, Int>(requested.size)
        requested.forEach { counts[it] = 0 }
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val meeting = entry.optString(SignalingSchema.KEY_MEETING)
            if (meeting.isEmpty()) continue
            counts[meeting] = entry.optInt(SignalingSchema.KEY_PARTICIPANTS, 0)
        }
        MeshLog.i(TAG) { "lookup: $counts" }
        return counts
    }

    private const val TAG = "MeetingLookup"
}
