package dev.meshcall.sdk.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.meshcall.sdk.api.MeshCall
import dev.meshcall.sdk.api.MeshRoomPeer
import dev.meshcall.sdk.internal.mesh.MeshCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Binds a [MeshCall] session to a grid of [MeshVideoRenderer]s inside [rendererContainer].
 *
 * The first child renderer is the local preview; every renderer added afterwards draws
 * one remote participant. As the roster churns the controller reuses renderers rather
 * than recreating them, so [SurfaceViewRenderer] state stays stable and the layout is
 * only ever added to — never torn down — while a call is live.
 *
 * The controller needs the engine's shared EGL context to initialize renderers; it is
 * read from the [MeshCall]'s live internal manager, so [bind] must be called after the
 * call has [MeshCall.join]ed. Call [release] when the host view is destroyed.
 *
 * This class is public so host apps can subclass it to add chrome (headers, controls)
 * while delegating all track↔renderer wiring here.
 */
class MeshCallRoomView(
    private val context: Context,
    private val rendererContainer: ViewGroup,
) {

    private val scope = CoroutineScope(Dispatchers.Main)

    private var eglContext: EglBase.Context? = null

    // Remote renderers keyed by peer id so they survive roster churn.
    private val remoteRenderers = LinkedHashMap<String, MeshVideoRenderer>()

    // Renderers we have initialized against the EGL context (init is one-shot).
    private val initialized = HashSet<MeshVideoRenderer>()

    /** Latest in-bound stream per peer, used to bind a stream as soon as a renderer exists. */
    private val streamsByPeer = HashMap<String, MediaStream>()

    private val _renderersReady = MutableStateFlow(false)
    /** True once the container renderers have been initialized and can draw. */
    val renderersReady: StateFlow<Boolean> = _renderersReady.asStateFlow()

    /** Renderers currently in use, ordered [local, peer0, peer1, ...]. */
    val activeRenderers: List<SurfaceViewRenderer>
        get() = buildList {
            localPreview()?.let(::add)
            addAll(remoteRenderers.values)
        }

    /**
     * Bind this view to an in-progress call. [call] must already be [MeshCall.join]ed
     * (the manager is resolved from it). Any prior binding is released first.
     */
    fun bind(call: MeshCall) {
        val mgr = call.meshManager ?: return
        if (initialized.isNotEmpty()) unbind()

        eglContext = mgr.eglContext
        seedSlots()
        bindLocalPreview(mgr)

        scope.launch {
            call.peers.collect { roster -> syncPeers(roster) }
        }
        scope.launch {
            mgr.mediaEvents.collect { ev ->
                when (ev) {
                    is MeshCallManager.MediaEvent.RemoteStreamChanged -> {
                        if (ev.stream == null) {
                            streamsByPeer.remove(ev.peerId)
                        } else {
                            streamsByPeer[ev.peerId] = ev.stream
                        }
                        bindPeerStream(ev.peerId, ev.stream)
                    }
                    is MeshCallManager.MediaEvent.RemoteStreamAdded ->
                        // The stream arrives via RemoteStreamChanged; on add we just
                        // ensure the peer has a bound renderer showing the upcoming stream.
                        bindPeerStream(ev.peerId, null)
                    is MeshCallManager.MediaEvent.RemoteStreamRemoved -> {
                        streamsByPeer.remove(ev.peerId)
                        bindPeerStream(ev.peerId, null)
                    }
                }
            }
        }
    }

    /** Stop applying media to renderers. Safe to call repeatedly. */
    fun unbind() {
        bindLocalPreviewSink(null)
        remoteRenderers.keys.forEach(::stopPeerRenderer)
        streamsByPeer.clear()
        eglContext = null
        _renderersReady.value = false
    }

    /** Release all renderers and the coroutine scope. Call from the host's onDestroy. */
    fun release() {
        unbind()
        scope.cancel()
    }

    // ---- Internal wiring -------------------------------------------------------

    /** Initialize renderers already present in the layout. */
    private fun seedSlots() {
        val egc = eglContext ?: return
        for (child in allChildren()) {
            if (child is MeshVideoRenderer) {
                initRenderer(child, egc)
            }
        }
        if (localPreview() == null) {
            val preview = MeshVideoRenderer(context)
            rendererContainer.addView(
                preview,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            initRenderer(preview, egc)
        }
        _renderersReady.value = initialized.isNotEmpty()
    }

    private fun bindLocalPreview(mgr: MeshCallManager) {
        val egc = eglContext ?: return
        val preview = localPreview() ?: return
        initRenderer(preview, egc)
        preview.setMirror(true)
        bindLocalPreviewSink(mgr.localVideo())
    }

    private fun bindLocalPreviewSink(track: VideoTrack?) {
        val preview = localPreview() ?: return
        if (track == null) {
            preview.clearImage()
            return
        }
        track.addSink(preview)
    }

    private fun initRenderer(renderer: MeshVideoRenderer, egl: EglBase.Context) {
        if (initialized.add(renderer)) {
            renderer.setEnableHardwareScaler(true)
            renderer.setZOrderMediaOverlay(renderer === localPreview())
            renderer.init(egl, null)
        }
    }

    /** Diff the public roster into the remote renderer pool. */
    private fun syncPeers(roster: List<MeshRoomPeer>) {
        val wanted = roster.map { it.id }.toSet()

        remoteRenderers.keys.toList().forEach { id ->
            if (id !in wanted) {
                stopPeerRenderer(id)
                val renderer = remoteRenderers.remove(id) ?: return@forEach
                streamsByPeer.remove(id)
                rendererContainer.removeView(renderer)
            }
        }

        roster.forEach { peer ->
            if (peer.id !in remoteRenderers) ensurePeerRenderer(peer.id)
        }
    }

    private fun ensurePeerRenderer(peerId: String) {
        val egc = eglContext ?: return
        val renderer = remoteRenderers[peerId]
            ?: createRemoteRenderer(peerId, egc)
        bindPeerStream(peerId, streamsByPeer[peerId])
    }

    private fun createRemoteRenderer(peerId: String, egc: EglBase.Context): MeshVideoRenderer {
        val renderer = MeshVideoRenderer(context)
        remoteRenderers[peerId] = renderer
        initRenderer(renderer, egc)
        renderer.setMirror(false)
        rendererContainer.addView(
            renderer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return renderer
    }

    private fun bindPeerStream(peerId: String, stream: MediaStream?) {
        val renderer = remoteRenderers[peerId] ?: return
        val videoTrack = stream?.videoTracks?.firstOrNull()
        if (videoTrack == null) {
            renderer.clearImage()
            return
        }
        videoTrack.addSink(renderer)
    }

    /** Unbind the single peer stream this renderer draws, if any. */
    private fun stopPeerRenderer(peerId: String) {
        val renderer = remoteRenderers[peerId] ?: return
        streamsByPeer[peerId]?.videoTracks?.firstOrNull()?.removeSink(renderer)
    }

    private fun localPreview(): MeshVideoRenderer? = allChildren()
        .filterIsInstance<MeshVideoRenderer>()
        .firstOrNull()

    private fun allChildren(): Sequence<android.view.View> =
        (0 until rendererContainer.childCount).asSequence()
            .map { rendererContainer.getChildAt(it) }
}
