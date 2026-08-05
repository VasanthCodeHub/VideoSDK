package dev.meshcall.sdk.api

import android.content.Context
import dev.meshcall.sdk.internal.media.MediaConfig
import dev.meshcall.sdk.internal.mesh.MeshCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Entry point for the MeshCall SDK.
 *
 * Handles one mesh session at a time. [join] replaces any prior session for this
 * instance; [leave] ends the session and frees all media + sockets so the app can
 * background or close the screen safely.
 *
 * Public state is exposed as cold Kotlin flows so callers may collect from any
 * lifecycle owner without leaking. Call [dispose] when the instance is no longer
 * needed (e.g. Activity.destroy) so sockets and WebRTC resources are released.
 *
 * Example:
 * ```
 * val call = MeshCall(applicationContext)
 * call.join(
 *     brokerUrl = "https://signaling.example.com",
 *     roomId = "support-session-42",
 *     displayName = "Ada",
 * )
 * lifecycleScope.launch { call.peers.collect { render(it) } }
 * ```
 */
class MeshCall(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // One live manager at most; replaced on each join, dropped on leave/dispose.
    private var manager: MeshCallManager? = null

    /** Internal seam: exposes the live manager to the ui package (same module). */
    internal val meshManager: MeshCallManager?
        get() = manager

    /**
     * Join a mesh room. Any prior session is left first.
     *
     * @param brokerUrl   WebSocket signaling endpoint, e.g. "https://signaling.example.com"
     * @param roomId      room/group identifier shared by all participants
     * @param displayName human-readable name broadcast to other participants
     */
    fun join(
        brokerUrl: String,
        roomId: String,
        displayName: String,
    ) {
        leave()
        val m = MeshCallManager(appContext, brokerUrl, userId, displayName)
        manager = m
        m.join(roomId, MediaConfig())
        _stateFlow = m.roomState.map(::mapState)
    }

    private val userId: String
        get() = LocalIdentityProvider.userId ?: DEFAULT_ANON_ID

    private companion object {
        const val DEFAULT_ANON_ID = "anonymous"
    }

    /** High-level session status. */
    private var _stateFlow: Flow<MeshCallState> = flowOf(MeshCallState.IDLE)
    val state: Flow<MeshCallState> get() = _stateFlow

    /** Roster of remote participants (excluding self). */
    val peers: Flow<List<MeshRoomPeer>>
        get() = manager?.peers?.map { list ->
            list.map { MeshRoomPeer(it.id, it.userName, it.micEnabled, it.cameraEnabled) }
        } ?: emptyFlow()

    /** Non-fatal errors worth surfacing (signaling drops, media failures). */
    val errors: Flow<String>
        get() = manager?.errors ?: emptyFlow()

    // ---- Media toggles ----------------------------------------------------------

    fun toggleMic() = manager?.toggleMic()
    fun toggleCamera() = manager?.toggleCamera()

    // ---- Teardown ---------------------------------------------------------------

    /** Leave the current room and free resources. Safe to call repeatedly. */
    fun leave() {
        manager?.leave()
        manager = null
        _stateFlow = flowOf(MeshCallState.IDLE)
    }

    /** Leave + release the instance. Call once from Activity/Fragment onDestroy. */
    fun dispose() {
        leave()
        scope.cancel()
    }

    private fun mapState(value: MeshCallManager.RoomState): MeshCallState = when (value) {
        is MeshCallManager.RoomState.Active -> MeshCallState.CONNECTED
        else -> MeshCallState.IDLE
    }
}

/** Publicly visible snapshot of one remote participant. */
data class MeshRoomPeer(
    val id: String,
    val userName: String,
    val micEnabled: Boolean,
    val cameraEnabled: Boolean,
)

/** High-level session status. */
enum class MeshCallState { IDLE, CONNECTED }

/**
 * Simple identity hook so the SDK does not bake in an auth provider. Set it once at
 * application start; the value is used as this device's id in rooms.
 */
object LocalIdentityProvider {
    @Volatile
    var userId: String? = null
}
