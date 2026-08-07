package dev.meshcall.sdk.api

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import dev.meshcall.sdk.internal.media.MediaConfig
import dev.meshcall.sdk.internal.mesh.MeshMeetingManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Entry point for the MeshCall SDK.
 *
 * Handles one meeting at a time. [join] replaces any prior session; [leave] ends it and
 * frees all media and sockets so the screen can close safely. Call [dispose] once from
 * the host's `onDestroy`.
 *
 * Every observable is a stable [Flow] that survives across joins — collect it before or
 * after [join] and it will start emitting when a session exists.
 *
 * ```
 * val call = MeshCall(applicationContext)
 * call.join(
 *     brokerUrl = "wss://signaling.example.com",
 *     meetingId = "ABC123",
 *     displayName = "Ada",
 * )
 * lifecycleScope.launch { call.participants.collect(::render) }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshCall(context: Context) {

    private val appContext = context.applicationContext

    /**
     * The live manager, as a flow so the public observables can follow it across joins.
     * Exposing them as plain getters over a nullable field (the previous design) meant a
     * caller that collected before [join] was stuck on an empty flow forever.
     */
    private val managerFlow = MutableStateFlow<MeshMeetingManager?>(null)

    /** Internal seam: lets the `ui` package reach the live manager (same module). */
    internal val meshManager: MeshMeetingManager?
        get() = managerFlow.value

    /** Id of the meeting currently joined, or null. */
    var currentMeetingId: String? = null
        private set

    /**
     * Join a meeting.
     *
     * A meeting exists only while somebody is in it. Joining a code nobody is in is
     * **refused** — watch [meetingNotFound] and leave the screen — unless
     * [createIfMissing] is set, which is how the participant who started the meeting
     * brings it into existence. Validate a code before you get here with
     * [MeshMeetingDirectory].
     *
     * @param brokerUrl   signaling endpoint, e.g. `wss://signaling.example.com`
     * @param meetingId   identifier shared by every participant
     * @param displayName human-readable name broadcast to the others
     * @param config      capture, bitrate, and ICE (STUN/TURN) settings
     * @param createIfMissing open the meeting if the broker has no record of it
     * @param isPrivate open it as a private meeting: everyone who joins afterwards waits
     *   in [Admission.AWAITING_APPROVAL] until the host answers their [joinRequests]
     *   entry. Only meaningful together with [createIfMissing] — privacy is a property of
     *   the meeting, fixed when it is opened, and enforced by the broker.
     * @param avatarBase64 a small base64-encoded JPEG thumbnail broadcast to the others,
     *   shown in place of the initials placeholder (video off, participants list). Fixed
     *   for the session, like [displayName] — pick it before calling [join]. Requires the
     *   broker to relay the `avatar` field (see `SignalingSchema`); older brokers just
     *   ignore it and everyone falls back to initials.
     */
    fun join(
        brokerUrl: String,
        meetingId: String,
        displayName: String,
        config: MeshCallConfig = MeshCallConfig(),
        createIfMissing: Boolean = false,
        isPrivate: Boolean = false,
        avatarBase64: String? = null,
    ) {
        leave()
        val manager = MeshMeetingManager(appContext, brokerUrl, userId, displayName, avatarBase64)
        managerFlow.value = manager
        currentMeetingId = meetingId
        manager.join(meetingId, MediaConfig.from(config), createIfMissing, isPrivate)
    }

    // ---- Observable state -------------------------------------------------------

    /** High-level session status. */
    val state: Flow<MeetingState> = managerFlow.flatMapLatest { manager ->
        manager?.session?.map { session ->
            if (session is MeshMeetingManager.Session.Active) MeetingState.CONNECTED
            else MeetingState.IDLE
        } ?: flowOf(MeetingState.IDLE)
    }

    /** True while the signaling transport is connected to the broker. */
    val connected: Flow<Boolean> = managerFlow.flatMapLatest { manager ->
        manager?.signalingConnected ?: flowOf(false)
    }

    /** Remote participants, excluding self, in stable roster order. */
    val participants: Flow<List<MeshParticipant>> = managerFlow.flatMapLatest { manager ->
        manager?.peers?.map { list ->
            list.map {
                MeshParticipant(
                    id = it.id,
                    userName = it.userName,
                    micEnabled = it.micEnabled,
                    cameraEnabled = it.cameraEnabled,
                    connectionState = it.connectionState,
                    avatarBase64 = it.avatarBase64,
                )
            }
        } ?: flowOf(emptyList())
    }

    /**
     * Id of the participant currently talking, or null. Driven by real audio levels so
     * the UI can keep the speaker on screen.
     */
    val speaker: Flow<String?> = managerFlow.flatMapLatest { manager ->
        manager?.speakerId ?: flowOf(null)
    }

    /** Authoritative local mic/camera state. Bind controls to this, not to a local mirror. */
    val localMedia: Flow<LocalMediaState> = managerFlow.flatMapLatest { manager ->
        manager?.localMedia ?: flowOf(LocalMediaState())
    }

    /** True while the front camera is the one streaming — drives local preview mirroring. */
    val frontCameraActive: Flow<Boolean> = managerFlow.flatMapLatest { manager ->
        manager?.frontCameraActive ?: flowOf(true)
    }

    /**
     * Which output the meeting is playing through, and which others are reachable right
     * now. Emits again whenever a headset is connected or removed, so a route picker bound
     * to this needs no polling.
     */
    val audioRoute: Flow<AudioRouteState> = managerFlow.flatMapLatest { manager ->
        manager?.audioRoute ?: flowOf(AudioRouteState())
    }

    /** True while this device is sharing its screen instead of its camera. */
    val screenSharing: Flow<Boolean> = managerFlow.flatMapLatest { manager ->
        manager?.screenSharing ?: flowOf(false)
    }

    /**
     * Emits the meeting id when the broker refuses the join: no meeting with that code is
     * live. Fatal for the session — nothing else will arrive — so the host should tell the
     * user and navigate away rather than leaving them in an empty grid.
     */
    val meetingNotFound: Flow<String> = managerFlow.flatMapLatest { manager ->
        manager?.meetingNotFound ?: emptyFlow()
    }

    /**
     * Whether this device is in the meeting, still waiting at the door of a private one,
     * or was turned away. Watch for [Admission.DENIED] the same way as [meetingNotFound]:
     * it is terminal and the host app should navigate away.
     */
    val admission: Flow<Admission> = managerFlow.flatMapLatest { manager ->
        manager?.admission ?: flowOf(Admission.JOINING)
    }

    /**
     * People asking to be let into this private meeting, oldest first.
     *
     * Only ever non-empty for the host — the broker sends knocks nowhere else — so a UI
     * can bind to this unconditionally and simply show nothing for everyone else. Answer
     * with [admitParticipant] / [declineParticipant].
     */
    val joinRequests: Flow<List<JoinRequest>> = managerFlow.flatMapLatest { manager ->
        manager?.joinRequests ?: flowOf(emptyList())
    }

    /** Non-fatal errors worth surfacing (signaling drops, media failures). */
    val errors: Flow<String> = managerFlow.flatMapLatest { manager ->
        manager?.errors ?: emptyFlow()
    }

    // ---- Controls ---------------------------------------------------------------

    fun toggleMic() = meshManager?.toggleMic()
    fun toggleCamera() = meshManager?.toggleCamera()
    fun switchCamera() = meshManager?.switchCamera()
    fun setMic(enabled: Boolean) = meshManager?.setMic(enabled)
    fun setCamera(enabled: Boolean) = meshManager?.setCamera(enabled)

    /**
     * Move meeting audio to [route]. Ignored when that route is not in the current
     * [AudioRouteState.available] list — there is nothing to route to.
     */
    fun selectAudioRoute(route: AudioRoute) = meshManager?.selectAudioRoute(route)

    /** Let [participantId] into this private meeting. Only the host's call is obeyed. */
    fun admitParticipant(participantId: String) =
        meshManager?.answerJoinRequest(participantId, admit = true)

    /** Turn [participantId] away. Their session ends with [Admission.DENIED]. */
    fun declineParticipant(participantId: String) =
        meshManager?.answerJoinRequest(participantId, admit = false)

    /**
     * Start sharing the screen. The shared screen **replaces** this device's camera for
     * everyone in the meeting — one outgoing video track, so no extra tile appears and no
     * renegotiation is needed.
     *
     * [permissionData] is the `Intent` from the MediaProjection consent dialog, which only
     * an Activity can request:
     *
     * ```
     * private val shareLauncher = registerForActivityResult(StartActivityForResult()) { r ->
     *     val data = r.data
     *     if (r.resultCode == Activity.RESULT_OK && data != null) call.startScreenShare(data)
     * }
     * // from MeshMeetingView.onShareScreen:
     * shareLauncher.launch(MeshCall.screenCaptureIntent(this))
     * ```
     *
     * The consent token is single-use: a new one is needed for every share.
     */
    fun startScreenShare(permissionData: Intent) = meshManager?.startScreenShare(permissionData)

    /** Stop sharing and put the camera back on every peer. Safe to call when not sharing. */
    fun stopScreenShare() = meshManager?.stopScreenShare()

    /**
     * Ask [participantId] to mute their mic; honored automatically on their device.
     *
     * Client-side only today — the signaling broker (`VideoSDKServer/server`, a separate
     * repo) does not yet relay `mute-request`, so this is a no-op end to end until that
     * server-side handler ships.
     */
    fun requestMute(participantId: String) = meshManager?.requestMute(participantId)

    // ---- Teardown ---------------------------------------------------------------

    /** Leave the current meeting and free resources. Safe to call repeatedly. */
    fun leave() {
        // destroy(), not leave(): the manager owns a CoroutineScope that leave() alone
        // would leave running for the lifetime of the process.
        managerFlow.value?.destroy()
        managerFlow.value = null
        currentMeetingId = null
    }

    /** Leave + release the instance. Call once from the host's onDestroy. */
    fun dispose() = leave()

    private val userId: String
        get() = LocalIdentityProvider.userId ?: DEFAULT_ANON_ID

    companion object {
        private const val DEFAULT_ANON_ID = "anonymous"

        /**
         * The consent Intent to launch before [startScreenShare]. Wrapped here so hosts do
         * not need to reach for `MediaProjectionManager` themselves.
         */
        @JvmStatic
        fun screenCaptureIntent(context: Context): Intent {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            return manager.createScreenCaptureIntent()
        }
    }
}

/** Publicly visible snapshot of one remote participant. */
data class MeshParticipant(
    val id: String,
    val userName: String,
    val micEnabled: Boolean,
    val cameraEnabled: Boolean,
    /** "new" | "connecting" | "connected" | "completed" | "disconnected" | "failed" | "closed" */
    val connectionState: String = "new",
    /** Base64 JPEG thumbnail this participant joined with, or null if they chose none. */
    val avatarBase64: String? = null,
)

/** High-level session status. */
enum class MeetingState { IDLE, CONNECTED }

/** Someone waiting to be admitted to a private meeting. */
data class JoinRequest(
    val userId: String,
    val userName: String,
)

/**
 * Where this device stands with the meeting's door.
 *
 * Only private meetings ever reach [AWAITING_APPROVAL] or [DENIED] — a normal meeting
 * goes straight from [JOINING] to [ADMITTED].
 */
enum class Admission {
    /** Connecting; the broker has not answered yet. */
    JOINING,

    /** Private meeting: the host has been asked and has not decided. */
    AWAITING_APPROVAL,

    /** In the meeting. */
    ADMITTED,

    /** The host said no. Terminal — leave the screen. */
    DENIED,
}

/**
 * Identity hook, so the SDK does not bake in an auth provider. Set it once at application
 * start; the value becomes this device's id in every meeting and decides who offers.
 */
object LocalIdentityProvider {
    @Volatile
    var userId: String? = null
}
