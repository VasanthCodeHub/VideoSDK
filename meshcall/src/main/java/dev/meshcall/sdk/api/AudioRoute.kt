package dev.meshcall.sdk.api

/**
 * Where meeting audio is playing.
 *
 * The mic follows the same choice: selecting [BLUETOOTH] moves capture to the headset's
 * mic, and dropping back to [SPEAKER] or [EARPIECE] returns it to the built-in one.
 */
enum class AudioRoute {
    /** The small in-ear speaker. Private, quiet, phones only. */
    EARPIECE,

    /** The loudspeaker. The default for a video meeting, since the phone is not at the ear. */
    SPEAKER,

    /** A wired headset, 3.5mm or USB. Only ever available while something is plugged in. */
    WIRED_HEADSET,

    /** A connected Bluetooth headset. Only available while one is paired *and* connected. */
    BLUETOOTH,
}

/**
 * The routes this device can use right now, and which one is live.
 *
 * [available] changes as headsets come and go — unplugging the wired headset mid-meeting
 * removes it from the list and the SDK falls back on its own. Render the picker from this
 * list rather than from [AudioRoute.entries], or you will offer routes that do not exist.
 */
data class AudioRouteState(
    val selected: AudioRoute = AudioRoute.SPEAKER,
    val available: List<AudioRoute> = listOf(AudioRoute.SPEAKER),
)
