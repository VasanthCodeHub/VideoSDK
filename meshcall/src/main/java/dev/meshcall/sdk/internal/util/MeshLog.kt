package dev.meshcall.sdk.internal.util

import android.util.Log

/**
 * Namespaced logging for the SDK.
 *
 * Every tag is prefixed with `MeshCall` so a consumer can filter the whole SDK in or out
 * of logcat with one filter. Messages are lambdas, so building the string costs nothing
 * when [verbose] is off.
 */
internal object MeshLog {

    private const val PREFIX = "MeshCall"

    /**
     * Every scope the SDK logs under. Declared here rather than as a private `TAG` in each
     * file so the set is greppable and logcat filters stay stable across refactors.
     */
    const val SCOPE_MESH = "Mesh"
    const val SCOPE_ENGINE = "Engine"
    const val SCOPE_CAMERA = "Camera"
    const val SCOPE_SIGNALING = "Signaling"
    const val SCOPE_GRID = "Grid"
    const val SCOPE_MEETING_VIEW = "MeetingView"

    /** Set false from a consumer's Application to silence everything below WARN. */
    @Volatile
    var verbose: Boolean = true

    private fun tag(scope: String) = "$PREFIX/$scope"

    fun d(scope: String, message: () -> String) {
        if (verbose) Log.d(tag(scope), message())
    }

    fun i(scope: String, message: () -> String) {
        if (verbose) Log.i(tag(scope), message())
    }

    fun w(scope: String, message: String, error: Throwable? = null) {
        Log.w(tag(scope), message, error)
    }

    fun e(scope: String, message: String, error: Throwable? = null) {
        Log.e(tag(scope), message, error)
    }
}
