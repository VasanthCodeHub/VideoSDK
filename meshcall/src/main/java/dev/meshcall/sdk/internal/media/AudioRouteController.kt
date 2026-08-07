package dev.meshcall.sdk.internal.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import dev.meshcall.sdk.api.AudioRoute
import dev.meshcall.sdk.api.AudioRouteState
import dev.meshcall.sdk.internal.util.MeshLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns audio mode, focus, and output routing for the duration of a meeting.
 *
 * WebRTC's audio device module captures and plays audio but does **not** route it: without
 * this, a video meeting comes out of the earpiece, and a connected Bluetooth headset is
 * ignored entirely. Speaker, Bluetooth, wired, and earpiece are all one decision — which
 * device the call is on — so they live behind one component and one control, never as
 * separate toggles that can disagree.
 *
 * Two implementations sit behind the same API:
 *  - **API 31+** uses `setCommunicationDevice`, which routes capture and playback together
 *    and manages the Bluetooth link itself.
 *  - **API 24-30** uses the legacy surface: `isSpeakerphoneOn` plus SCO start/stop, where
 *    Bluetooth has to be asked for explicitly and connects asynchronously.
 *
 * Device *discovery* is shared: [AudioManager.getDevices] and [AudioDeviceCallback] exist
 * since API 23, so neither path needs `BluetoothAdapter` and no Bluetooth permission is
 * required at any API level. `MODIFY_AUDIO_SETTINGS` covers everything here.
 *
 * Lifecycle: [start] on join → [select] as the user picks → [stop] on leave. [stop] restores
 * the audio mode it found, so the host app's media playback is not left in call mode.
 */
internal class AudioRouteController(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(AudioRouteState())
    val state = _state.asStateFlow()

    private var started = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerOn = false
    private var focusRequest: AudioFocusRequest? = null

    /**
     * What the user last asked for, which is not always what is playing: picking Bluetooth
     * while the headset is connecting, or having the wired headset yanked, both leave the
     * live route behind the intent. Kept so the intent can be honored once the device shows
     * up, instead of silently downgrading the user's choice.
     */
    private var desiredRoute: AudioRoute? = null

    /** Re-evaluates routing whenever a headset is plugged, unplugged, or (dis)connects. */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
            onDevicesChanged()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
            onDevicesChanged()
    }

    // ---- Lifecycle ---------------------------------------------------------------

    // isSpeakerphoneOn is deprecated at API 31 in favour of communication devices, but
    // minSdk is 24 and it remains the only legacy route control — suppressed at function
    // level rather than per statement, which Kotlin does not reliably allow.
    @Suppress("DEPRECATION")
    fun start() {
        if (started) return
        started = true

        previousMode = audioManager.mode
        previousSpeakerOn = audioManager.isSpeakerphoneOn

        // MODE_IN_COMMUNICATION is what makes the earpiece, the SCO link, and the platform's
        // own echo canceller available at all. Without it, routing calls are accepted and
        // then quietly ignored.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestFocus()
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)

        val available = availableRoutes()
        val initial = desiredRoute ?: defaultRoute(available)
        desiredRoute = initial
        applyRoute(initial)
        _state.value = AudioRouteState(initial, available)
        MeshLog.i(TAG) { "audio started on $initial (available=$available)" }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (!started) return
        started = false

        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
            MeshLog.d(TAG) { "unregister device callback: ${e.message}" }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (e: Exception) {
                MeshLog.d(TAG) { "clearCommunicationDevice: ${e.message}" }
            }
        } else {
            stopScoLegacy()
            // Only restored on the legacy path: on API 31+ clearCommunicationDevice already
            // undid the routing, and touching the flag there re-deprecates what it fixed.
            audioManager.isSpeakerphoneOn = previousSpeakerOn
        }

        audioManager.mode = previousMode
        abandonFocus()

        desiredRoute = null
        _state.value = AudioRouteState()
        MeshLog.i(TAG) { "audio stopped; mode restored to $previousMode" }
    }

    // ---- Selection ---------------------------------------------------------------

    /** Move the call to [route]. Ignored when the route is not currently available. */
    fun select(route: AudioRoute) {
        if (!started) {
            // Remembered rather than dropped: a host that restores a saved preference
            // before joining would otherwise have it silently discarded.
            desiredRoute = route
            return
        }
        val available = availableRoutes()
        if (route !in available) {
            MeshLog.w(TAG, "route $route is not available (have $available)")
            return
        }
        desiredRoute = route
        applyRoute(route)
        _state.value = AudioRouteState(route, available)
        MeshLog.i(TAG) { "audio route -> $route" }
    }

    /**
     * Recompute after a device change. A newly connected headset wins — that is what users
     * expect from plugging one in mid-call — and losing the current route falls back to the
     * best of what is left.
     */
    private fun onDevicesChanged() {
        if (!started) return
        val available = availableRoutes()
        val current = _state.value.selected

        val next = when {
            // A headset appeared that outranks the current route: follow it.
            available.any { it.isHeadset && it !in _state.value.available } ->
                available.first { it.isHeadset && it !in _state.value.available }
            // The user's standing choice became possible again (headset reconnected).
            desiredRoute?.let { it in available && it != current } == true -> desiredRoute!!
            // Current route is gone.
            current !in available -> defaultRoute(available)
            else -> current
        }

        if (next != current) {
            applyRoute(next)
            MeshLog.i(TAG) { "audio route follows device change: $current -> $next" }
        }
        _state.value = AudioRouteState(next, available)
    }

    // ---- Routing ------------------------------------------------------------------

    private fun applyRoute(route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyRouteModern(route)
        } else {
            applyRouteLegacy(route)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyRouteModern(route: AudioRoute) {
        val target = audioManager.availableCommunicationDevices
            .firstOrNull { it.type in route.deviceTypes }
        if (target == null) {
            MeshLog.w(TAG, "no communication device for $route")
            return
        }
        if (!audioManager.setCommunicationDevice(target)) {
            MeshLog.w(TAG, "setCommunicationDevice(${target.type}) refused for $route")
        }
    }

    @Suppress("DEPRECATION")
    private fun applyRouteLegacy(route: AudioRoute) {
        // Order matters: SCO has to be torn down before the speaker flag is touched, or the
        // still-open SCO link keeps ownership of playback and the speaker change does nothing.
        if (route != AudioRoute.BLUETOOTH) stopScoLegacy()

        when (route) {
            AudioRoute.SPEAKER -> audioManager.isSpeakerphoneOn = true
            AudioRoute.EARPIECE, AudioRoute.WIRED_HEADSET -> audioManager.isSpeakerphoneOn = false
            AudioRoute.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                startScoLegacy()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startScoLegacy() {
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            MeshLog.w(TAG, "device does not allow SCO outside a cellular call")
            return
        }
        try {
            audioManager.startBluetoothSco()
            // The link is asynchronous; this flag only takes effect once SCO is up, which is
            // why it is set unconditionally rather than gated on a connected state.
            audioManager.isBluetoothScoOn = true
        } catch (e: Exception) {
            MeshLog.w(TAG, "startBluetoothSco failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopScoLegacy() {
        try {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        } catch (e: Exception) {
            MeshLog.d(TAG) { "stopBluetoothSco: ${e.message}" }
        }
    }

    // ---- Discovery ----------------------------------------------------------------

    private fun availableRoutes(): List<AudioRoute> {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.map { it.type }
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        }.toSet()

        // Built in declaration order so the picker is stable, not shuffled by whatever
        // order the platform happened to report devices in.
        return AudioRoute.entries.filter { route ->
            route.deviceTypes.any { it in types }
        }
    }

    /**
     * A meeting is a hands-free activity: prefer a headset when one is there, otherwise the
     * loudspeaker. The earpiece is only ever chosen deliberately.
     */
    private fun defaultRoute(available: List<AudioRoute>): AudioRoute = when {
        AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH
        AudioRoute.WIRED_HEADSET in available -> AudioRoute.WIRED_HEADSET
        AudioRoute.SPEAKER in available -> AudioRoute.SPEAKER
        else -> available.firstOrNull() ?: AudioRoute.EARPIECE
    }

    // ---- Focus ---------------------------------------------------------------------

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private companion object {
        const val TAG = "Audio"
    }
}

/** True for routes that represent a headset the user physically attached. */
private val AudioRoute.isHeadset: Boolean
    get() = this == AudioRoute.BLUETOOTH || this == AudioRoute.WIRED_HEADSET

/**
 * Platform device types that count as this route. Several map to more than one: a USB
 * headset and a 3.5mm one are the same choice to a user, and a Bluetooth headset reports
 * SCO for the call profile.
 */
private val AudioRoute.deviceTypes: List<Int>
    get() = when (this) {
        AudioRoute.EARPIECE -> listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        AudioRoute.SPEAKER -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        AudioRoute.WIRED_HEADSET -> listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        // TYPE_BLE_HEADSET is how LE Audio buds report themselves, and they do *not* also
        // appear as SCO — without it, a modern pair looks like "no Bluetooth" to the picker.
        AudioRoute.BLUETOOTH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        } else {
            listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        }
    }
