package dev.meshcall.sdk.api

import android.content.Context
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
     * @param brokerUrl   signaling endpoint, e.g. `wss://signaling.example.com`
     * @param meetingId   identifier shared by every participant
     * @param displayName human-readable name broadcast to the others
     * @param config      capture, bitrate, and ICE (STUN/TURN) settings
     */
    fun join(
        brokerUrl: String,
        meetingId: String,
        displayName: String,
        config: MeshCallConfig = MeshCallConfig(),
    ) {
        leave()
        val manager = MeshMeetingManager(appContext, brokerUrl, userId, displayName)
        managerFlow.value = manager
        currentMeetingId = meetingId
        manager.join(meetingId, MediaConfig.from(config))
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

    private companion object {
        const val DEFAULT_ANON_ID = "anonymous"
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
)

/** High-level session status. */
enum class MeetingState { IDLE, CONNECTED }

/**
 * Identity hook, so the SDK does not bake in an auth provider. Set it once at application
 * start; the value becomes this device's id in every meeting and decides who offers.
 */
object LocalIdentityProvider {
    @Volatile
    var userId: String? = null
}
