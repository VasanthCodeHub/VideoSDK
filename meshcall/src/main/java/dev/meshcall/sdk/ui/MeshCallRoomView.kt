package dev.meshcall.sdk.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.meshcall.sdk.R
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
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Binds a [MeshCall] session to a responsive tile grid inside [rendererContainer].
 *
 * Layout model:
 *  - The local preview is a [MeshVideoRenderer] child of the container (the host places
 *    it as a PiP). All renderers share the underlay plane; the preview's wrapper is kept
 *    as the last child so its surface always floats above the grid.
 *  - Every remote participant gets a tile: a [FrameLayout] cell holding the video
 *    surface (underlay plane), a placeholder + bottom gradient (shown while the peer has
 *    no video), and a chrome bar (name chip + mic/camera-off badges + connection dot)
 *    that always draws above the video.
 *  - Cells are recomputed whenever the container resizes or the roster changes, for any
 *    participant count (exercised with 8+ peers in offline demo mode).
 *
 * The controller needs the engine's shared EGL context to initialize renderers; it is
 * read from the [MeshCall]'s live internal manager, so [bind] must be called after the
 * call has [MeshCall.join]ed. Call [release] when the host view is destroyed.
 */
class MeshCallRoomView(
    private val context: Context,
    private val rendererContainer: ViewGroup,
) {

    private val scope = CoroutineScope(Dispatchers.Main)

    private var eglContext: EglBase.Context? = null

    // Remote tile per peer id so they survive roster churn.
    private val tiles = LinkedHashMap<String, Tile>()

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
            addAll(tiles.values.map { it.renderer })
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
        relayout()

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

        // Reflow the grid on any container resize (rotation, split-screen, insets).
        rendererContainer.addOnLayoutChangeListener(layoutListener)
    }

    /** Stop applying media to renderers. Safe to call repeatedly. */
    fun unbind() {
        rendererContainer.removeOnLayoutChangeListener(layoutListener)
        bindLocalPreviewSink(null)
        tiles.keys.forEach(::stopPeerRenderer)
        streamsByPeer.clear()
        eglContext = null
        _renderersReady.value = false
    }

    /** Release all renderers and the coroutine scope. Call from the host's onDestroy. */
    fun release() {
        unbind()
        tiles.values.forEach { tile ->
            rendererContainer.removeView(tile.frame)
            tile.renderer.release()
        }
        tiles.clear()
        scope.cancel()
    }

    // ---- Internal wiring -------------------------------------------------------

    /** Initialize renderers already present in the layout (the host's local preview). */
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
            // All surfaces live in the underlay plane so tile chrome (window plane)
            // always draws above the video. The local preview is kept as the last child
            // of the container, which puts its surface above every grid tile.
            renderer.setZOrderMediaOverlay(false)
            renderer.init(egl, null)
        }
    }

    /** Diff the public roster into the remote tile pool. */
    private fun syncPeers(roster: List<MeshRoomPeer>) {
        val wanted = roster.map { it.id }.toSet()

        tiles.keys.toList().forEach { id ->
            if (id !in wanted) {
                removeTile(id)
            }
        }

        roster.forEach { peer ->
            val tile = tiles[peer.id] ?: createTile(peer.id, peer.userName)
            tile.name = peer.userName
            applyChrome(tile, peer)
            bindPeerStream(peer.id, streamsByPeer[peer.id])
        }
        relayout()
    }

    private fun removeTile(peerId: String) {
        val tile = tiles.remove(peerId) ?: return
        stopPeerRenderer(peerId)
        streamsByPeer.remove(peerId)
        rendererContainer.removeView(tile.frame)
        tile.renderer.release()
    }

    private fun createTile(peerId: String, name: String): Tile {
        val egc = eglContext ?: error("bind() must run before peers arrive")

        val frame = FrameLayout(context)
        frame.setBackgroundResource(R.drawable.bg_tile_frame)

        val renderer = MeshVideoRenderer(context)
        frame.addView(
            renderer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        initRenderer(renderer, egc)
        renderer.setMirror(false)
        renderer.outlineProvider = ViewOutlineProvider.BACKGROUND
        renderer.clipToOutline = true

        val placeholder = buildPlaceholder(name, peerId)
        placeholder.visibility = View.GONE
        frame.addView(
            placeholder,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Subtle bottom fade that keeps the name chip readable over any video.
        val overlay = View(context)
        overlay.setBackgroundResource(R.drawable.bg_tile_overlay)
        frame.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val chip = TextView(context)
        chip.setTextColor(Color.WHITE)
        chip.textSize = 12f
        chip.setSingleLine(true)
        chip.ellipsize = TextUtils.TruncateAt.END
        chip.maxWidth = dp(110)
        chip.setBackgroundResource(R.drawable.bg_tile_chip)
        chip.setPadding(dp(8), dp(4), dp(8), dp(4))

        val micBadge = ImageView(context)
        micBadge.setImageResource(R.drawable.ic_mic_off)
        micBadge.setBackgroundResource(R.drawable.bg_tile_badge)
        micBadge.imageTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
        micBadge.setPadding(dp(5), dp(5), dp(5), dp(5))
        micBadge.visibility = View.GONE

        val camBadge = ImageView(context)
        camBadge.setImageResource(R.drawable.ic_videocam_off)
        camBadge.setBackgroundResource(R.drawable.bg_tile_badge)
        camBadge.imageTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
        camBadge.setPadding(dp(5), dp(5), dp(5), dp(5))
        camBadge.visibility = View.GONE

        val badges = LinearLayout(context)
        badges.orientation = LinearLayout.HORIZONTAL
        badges.gravity = Gravity.END
        badges.addView(micBadge, LinearLayout.LayoutParams(dp(24), dp(24)).also { it.marginEnd = dp(4) })
        badges.addView(camBadge, LinearLayout.LayoutParams(dp(24), dp(24)))

        val dot = View(context)
        dot.setBackgroundResource(R.drawable.dot_connecting)

        val chrome = FrameLayout(context)
        chrome.addView(
            dot,
            FrameLayout.LayoutParams(dp(8), dp(8), Gravity.END).also {
                it.topMargin = dp(8)
                it.marginEnd = dp(8)
            },
        )
        chrome.addView(
            chip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply {
                bottomMargin = dp(6)
                marginStart = dp(8)
            },
        )
        chrome.addView(
            badges,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply {
                bottomMargin = dp(6)
                marginEnd = dp(8)
            },
        )

        frame.addView(
            chrome,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        rendererContainer.addView(
            frame,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val tile = Tile(peerId, name, frame, renderer, placeholder, overlay, dot, chip, micBadge, camBadge)
        tiles[peerId] = tile
        relayout()
        return tile
    }

    /** Big-tile visual shown while the peer has no video or their camera is off. */
    private fun buildPlaceholder(name: String, peerId: String): View {
        val layer = FrameLayout(context)
        layer.setBackgroundColor(0xFF18181B.toInt())

        val inner = LinearLayout(context)
        inner.orientation = LinearLayout.VERTICAL
        inner.gravity = Gravity.CENTER

        val avatar = TextView(context)
        avatar.text = initialsFor(name)
        avatar.setTextColor(Color.WHITE)
        avatar.textSize = 22f
        avatar.gravity = Gravity.CENTER
        avatar.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(avatarPalette[peerId.hashCode().mod(avatarPalette.size)])
            },
        )
        inner.addView(
            avatar,
            LinearLayout.LayoutParams(dp(64), dp(64)),
        )

        val nameView = TextView(context)
        nameView.text = name
        nameView.setTextColor(Color.rgb(255, 255, 255))
        nameView.textSize = 15f
        nameView.setSingleLine(true)
        inner.addView(
            nameView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(8)
            },
        )

        layer.addView(
            inner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        return layer
    }

    private fun applyChrome(tile: Tile, peer: MeshRoomPeer) {
        tile.chip.text = peer.userName
        tile.chip.setBackgroundResource(R.drawable.bg_tile_chip)

        tile.micBadge.visibility = if (peer.micEnabled) View.GONE else View.VISIBLE
        tile.camBadge.visibility = if (peer.cameraEnabled) View.GONE else View.VISIBLE

        tile.dot.setBackgroundResource(
            when (peer.connectionState) {
                "connected", "completed" -> R.drawable.dot_connected
                "failed", "disconnected" -> R.drawable.dot_failed
                else -> R.drawable.dot_connecting
            },
        )

        val showPlaceholder = !peer.cameraEnabled ||
            streamsByPeer[tile.peerId]?.videoTracks?.isEmpty() != false
        tile.placeholder.visibility = if (showPlaceholder) View.VISIBLE else View.GONE
    }

    private fun bindPeerStream(peerId: String, stream: MediaStream?) {
        val tile = tiles[peerId] ?: return
        val videoTrack = stream?.videoTracks?.firstOrNull()
        if (videoTrack == null) {
            tile.renderer.clearImage()
            return
        }
        videoTrack.addSink(tile.renderer)
        tile.placeholder.visibility = View.GONE
    }

    /** Unbind the single peer stream this renderer draws, if any. */
    private fun stopPeerRenderer(peerId: String) {
        val tile = tiles[peerId] ?: return
        streamsByPeer[peerId]?.videoTracks?.firstOrNull()?.removeSink(tile.renderer)
    }

    private fun relayout() {
        rendererContainer.post { applyGrid() }
    }

    /**
     * Position every remote tile on a responsive grid that adapts to the container
     * size and roster count. Cells never overlap, so SurfaceViews compose cleanly
     * without z-order fights (verified up to 9 peers = 3x3).
     */
    private fun applyGrid() {
        val w = rendererContainer.width
        val h = rendererContainer.height
        if (w == 0 || h == 0 || tiles.isEmpty()) return

        val gap = dp(10)
        val count = tiles.size
        val cols = ceil(sqrt(count.toDouble())).toInt().coerceIn(1, 3)
        val rows = ceil(count.toDouble() / cols).toInt()
        val cellW = (w - gap * (cols + 1)) / cols
        val cellH = (h - gap * (rows + 1)) / rows

        tiles.values.forEachIndexed { index, tile ->
            val col = index % cols
            val row = index / cols
            val lp = tile.frame.layoutParams as FrameLayout.LayoutParams
            lp.width = cellW
            lp.height = cellH
            lp.leftMargin = gap + col * (cellW + gap)
            lp.topMargin = gap + row * (cellH + gap)
            lp.rightMargin = 0
            lp.bottomMargin = 0
            tile.frame.layoutParams = lp
        }

        // All renderers share the underlay plane, where sibling order decides who draws
        // on top. Keeping the local preview last floats it above every grid tile while
        // the tile chrome (window plane) still draws above it.
        (localPreview()?.parent as? View)?.let(rendererContainer::bringChildToFront)
    }

    private fun localPreview(): MeshVideoRenderer? = allChildren()
        .filterIsInstance<MeshVideoRenderer>()
        .firstOrNull()

    private fun allChildren(): Sequence<android.view.View> = sequence {
        val queue = ArrayDeque<android.view.View>()
        for (i in 0 until rendererContainer.childCount) {
            queue.addLast(rendererContainer.getChildAt(i))
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            yield(current)
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) {
                    queue.addLast(current.getChildAt(i))
                }
            }
        }
    }

    private fun initialsFor(name: String): String =
        name.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { it.first().uppercaseChar().toString() }
            .take(2)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyGrid() }

    private data class Tile(
        val peerId: String,
        var name: String,
        val frame: FrameLayout,
        val renderer: MeshVideoRenderer,
        val placeholder: View,
        val overlay: View,
        val dot: View,
        val chip: TextView,
        val micBadge: ImageView,
        val camBadge: ImageView,
    )

    private companion object {
        val avatarPalette = intArrayOf(
            0xFF5C6BC0.toInt(),
            0xFF26A69A.toInt(),
            0xFFEF5350.toInt(),
            0xFFFFA726.toInt(),
            0xFFAB47BC.toInt(),
            0xFF29B6F6.toInt(),
            0xFF8D6E63.toInt(),
            0xFF66BB6A.toInt(),
        )
    }
}