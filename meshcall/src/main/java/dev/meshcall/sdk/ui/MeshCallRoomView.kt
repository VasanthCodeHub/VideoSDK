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
 *  - Every participant gets an equal tile in the main grid, including the local
 *    camera: the host places its preview renderer as a child of the container and
 *    this view wraps it into a "You" tile (Meet-style). All renderers share the
 *    underlay plane; cell frames never overlap, so surfaces compose cleanly.
 *  - Each tile is a [FrameLayout] holding the video surface (underlay plane), a
 *    placeholder + bottom gradient (shown while the peer has no video), and a
 *    chrome bar (name chip + mic/camera-off badges + connection dot) that always
 *    draws above the video.
 *  - Cells are recomputed whenever the container resizes or the roster changes, for
 *    any participant count (exercised with 8+ peers in offline demo mode).
 *
 * The controller needs the engine's shared EGL context to initialize renderers; it is
 * read from the [MeshCall]'s live internal manager, so [bind] must be called after the
 * call has [MeshCall.join]ed. Call [release] when the host view is destroyed.
 */
class MeshCallRoomView(
    private val context: Context,
    private val rendererContainer: ViewGroup,
    private val overflowContainer: ViewGroup? = null,
) {

    private val scope = CoroutineScope(Dispatchers.Main)

    private var eglContext: EglBase.Context? = null

    // Remote tile per peer id so they survive roster churn.
    private val tiles = LinkedHashMap<String, Tile>()

    // Latest roster snapshot per peer (used by overflow chips).
    private val peersById = HashMap<String, MeshRoomPeer>()

    // Compact chips for participants beyond the main-grid slots.
    private val overflowChips = LinkedHashMap<String, OverflowChip>()

    // Renderers we have initialized against the EGL context (init is one-shot).
    private val initialized = HashSet<MeshVideoRenderer>()

    /** Latest in-bound stream per peer, used to bind a stream as soon as a renderer exists. */
    private val streamsByPeer = HashMap<String, MediaStream>()

    /** Peer the host pinned; always keeps a main-grid slot. */
    private var pinnedId: String? = null

    /** Peer currently talking; always keeps a main-grid slot. */
    private var speakerId: String? = null

    /** Max tiles on the main grid (the "You" tile is always included); rest go to the overflow strip. */
    private val mainSlots = 4

    /** Local mic/camera state, mirrored on the "You" tile by the host. */
    private var localMicOn = true
    private var localCamOn = true

    /**
     * Invoked when the user taps a tile or an overflow chip. The host decides the new
     * pin state and calls [setPinned] (typically a toggle).
     */
    var onPinRequest: ((String) -> Unit)? = null

    val pinnedPeerId: String? get() = pinnedId

    private val _renderersReady = MutableStateFlow(false)
    /** True once the container renderers have been initialized and can draw. */
    val renderersReady: StateFlow<Boolean> = _renderersReady.asStateFlow()

    /** Renderers currently in use, one per grid tile (local tile included). */
    val activeRenderers: List<SurfaceViewRenderer>
        get() = tiles.values.map { it.renderer }

    /**
     * Pin [peerId] to the main grid (null to unpin). Pinned peers always occupy one
     * of the four main-grid slots, no matter who else is talking.
     */
    fun setPinned(peerId: String?) {
        if (pinnedId != peerId) {
            pinnedId = peerId
            relayout()
        }
    }

    /**
     * Mark [peerId] as the active speaker (null when nobody is talking). The speaker
     * is promoted into the main grid and their tile gets a highlight ring.
     */
    fun setSpeaker(peerId: String?) {
        if (speakerId != peerId) {
            speakerId = peerId
            relayout()
        }
    }

    /**
     * Reflect the local mic/camera state on the "You" tile (the host already mirrors
     * these onto its own controls). Drives the self tile's badges + placeholder.
     */
    fun setLocalMediaState(micOn: Boolean, camOn: Boolean) {
        if (localMicOn != micOn || localCamOn != camOn) {
            localMicOn = micOn
            localCamOn = camOn
            applyLocalChrome()
        }
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
        peersById.clear()
        overflowChips.keys.toList().forEach(::removeOverflowChip)
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
        ensureLocalTile(preview)
        applyLocalChrome()
    }

    private fun bindLocalPreviewSink(track: VideoTrack?) {
        val preview = localPreview() ?: return
        if (track == null) {
            preview.clearImage()
            return
        }
        track.addSink(preview)
    }

    /**
     * Wrap the host's local preview in a regular grid tile labeled "You", so the
     * self camera sits in the grid like every other participant (Meet-style) instead
     * of floating over it as a PiP.
     */
    private fun ensureLocalTile(preview: MeshVideoRenderer) {
        if (tiles.containsKey(LOCAL_PEER_ID)) return
        val egc = eglContext ?: error("bind() must run before the local tile is built")

        val frame = FrameLayout(context)
        frame.setBackgroundResource(R.drawable.bg_tile_frame)
        (preview.parent as? ViewGroup)?.removeView(preview)
        frame.addView(
            preview,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        preview.outlineProvider = ViewOutlineProvider.BACKGROUND
        preview.clipToOutline = true

        val placeholder = buildPlaceholder(context.getString(R.string.meshcall_you), LOCAL_PEER_ID)
        placeholder.visibility = View.GONE
        frame.addView(
            placeholder,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

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
        dot.setBackgroundResource(R.drawable.dot_connected)

        val ring = View(context)
        ring.setBackgroundResource(R.drawable.bg_ring_speaking)
        ring.isClickable = false
        ring.visibility = View.GONE

        val pinBadge = ImageView(context)
        pinBadge.visibility = View.GONE

        val chrome = FrameLayout(context)
        chrome.addView(
            dot,
            FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.START).also {
                it.topMargin = dp(8)
                it.marginStart = dp(8)
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
            ring,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        frame.addView(
            pinBadge,
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).also {
                it.topMargin = dp(8)
                it.marginEnd = dp(8)
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
        tiles[LOCAL_PEER_ID] =
            Tile(LOCAL_PEER_ID, "You", frame, preview, placeholder, overlay, dot, ring, pinBadge, chip, micBadge, camBadge)
        relayout()
    }

    /** Keep the "You" tile's chrome in sync with the local media state. */
    private fun applyLocalChrome() {
        val tile = tiles[LOCAL_PEER_ID] ?: return
        tile.chip.text = context.getString(R.string.meshcall_you)
        tile.micBadge.visibility = if (localMicOn) View.GONE else View.VISIBLE
        tile.camBadge.visibility = if (localCamOn) View.GONE else View.VISIBLE
        tile.placeholder.visibility = if (localCamOn) View.GONE else View.VISIBLE
        tile.dot.setBackgroundResource(R.drawable.dot_connected)
        tile.ring.visibility = View.GONE
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
            if (id != LOCAL_PEER_ID && id !in wanted) {
                removeTile(id)
            }
        }

        roster.forEach { peer ->
            peersById[peer.id] = peer
            val tile = tiles[peer.id] ?: createTile(peer.id, peer.userName)
            tile.name = peer.userName
            applyChrome(tile, peer)
            refreshOverflowChip(peer)
            bindPeerStream(peer.id, streamsByPeer[peer.id])
        }
        relayout()
    }

    private fun removeTile(peerId: String) {
        val tile = tiles.remove(peerId) ?: return
        peersById.remove(peerId)
        stopPeerRenderer(peerId)
        streamsByPeer.remove(peerId)
        rendererContainer.removeView(tile.frame)
        tile.renderer.release()
        removeOverflowChip(peerId)
        if (pinnedId == peerId) pinnedId = null
        if (speakerId == peerId) speakerId = null
    }

    private fun removeOverflowChip(peerId: String) {
        val chip = overflowChips.remove(peerId) ?: return
        overflowContainer?.removeView(chip.root)
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

        // Speaking highlight ring: a green stroke around the tile while this peer
        // is the active speaker (window plane, so it draws above the video).
        val ring = View(context)
        ring.setBackgroundResource(R.drawable.bg_ring_speaking)
        ring.isClickable = false
        ring.visibility = View.GONE

        // Pin badge: top-right, mirrored on the local preview. Tap to unpin.
        val pinBadge = ImageView(context)
        pinBadge.setImageResource(R.drawable.ic_push_pin)
        pinBadge.setBackgroundResource(R.drawable.bg_pin_glass)
        pinBadge.imageTintList = ColorStateList.valueOf(Color.WHITE)
        pinBadge.setPadding(dp(5), dp(5), dp(5), dp(5))
        pinBadge.contentDescription = context.getString(R.string.meshcall_pin_participant)
        pinBadge.visibility = View.GONE
        pinBadge.setOnClickListener { onPinRequest?.invoke(peerId) }

        val chrome = FrameLayout(context)
        chrome.addView(
            dot,
            FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.START).also {
                it.topMargin = dp(8)
                it.marginStart = dp(8)
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
            ring,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        frame.addView(
            pinBadge,
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8)
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

        // Tap anywhere on the tile to pin / unpin this participant.
        frame.setOnClickListener { onPinRequest?.invoke(peerId) }

        rendererContainer.addView(
            frame,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val tile = Tile(peerId, name, frame, renderer, placeholder, overlay, dot, ring, pinBadge, chip, micBadge, camBadge)
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

        tile.ring.visibility = if (tile.peerId == speakerId) View.VISIBLE else View.GONE
        tile.pinBadge.visibility = if (tile.peerId == pinnedId) View.VISIBLE else View.GONE

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
     * Decide which participants occupy the main grid. Up to [mainSlots] remote peers,
     * prioritizing the pinned user first, then the active speaker, then everyone else
     * in roster order. The local "You" tile always keeps a slot (Meet-style); when the
     * grid is full the lowest-priority remote is evicted to make room. Everybody
     * beyond the slots goes to the overflow strip.
     */
    private fun mainTileIds(): List<String> {
        val remote = tiles.keys.filter { it != LOCAL_PEER_ID }
        val main = LinkedHashSet<String>()
        pinnedId?.let { if (it in remote) main.add(it) }
        speakerId?.let { if (it in remote) main.add(it) }
        for (id in remote) {
            if (main.size >= mainSlots) break
            main.add(id)
        }
        if (LOCAL_PEER_ID in tiles) {
            if (main.size >= mainSlots) {
                val evict = main.lastOrNull { it != pinnedId && it != speakerId }
                evict?.let(main::remove)
            }
            main.add(LOCAL_PEER_ID)
        }
        return main.toList()
    }

    /**
     * Position the main-grid tiles (up to 4) on a responsive grid and move everyone
     * else to the overflow strip. Cells never overlap, so SurfaceViews compose
     * cleanly without z-order fights.
     */
    private fun applyGrid() {
        val w = rendererContainer.width
        val h = rendererContainer.height

        val mainIds = mainTileIds()
        val overflowIds = tiles.keys.filter { it !in mainIds }

        if (w > 0 && h > 0 && mainIds.isNotEmpty()) {
            val gap = dp(10)
            val count = mainIds.size
            val cols = ceil(sqrt(count.toDouble())).toInt().coerceIn(1, 3)
            val rows = ceil(count.toDouble() / cols).toInt()
            val cellW = (w - gap * (cols + 1)) / cols
            val cellH = (h - gap * (rows + 1)) / rows

            mainIds.forEachIndexed { index, peerId ->
                val tile = tiles[peerId] ?: return@forEachIndexed
                tile.frame.visibility = View.VISIBLE
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
        }

        // Overflow peers keep their (hidden) renderers alive so they can return to
        // the grid instantly; the strip shows compact avatar chips instead.
        overflowIds.forEach { tiles[it]?.frame?.visibility = View.GONE }
        syncOverflowChips(overflowIds)
    }

    // ---- Overflow strip ---------------------------------------------------------

    /** Keep the compact participant strip in sync with the overflow roster. */
    private fun syncOverflowChips(overflowIds: List<String>) {
        val container = overflowContainer ?: return
        overflowChips.keys.toList().forEach { id ->
            if (id !in overflowIds) removeOverflowChip(id)
        }
        if (overflowIds.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        overflowIds.forEach { id ->
            if (overflowChips[id] == null) {
                val chip = buildOverflowChip(id)
                overflowChips[id] = chip
                container.addView(chip.root)
            }
            refreshOverflowChip(peersById[id] ?: MeshRoomPeer(id, id, true, true, "new"))
        }
    }

    private fun refreshOverflowChip(peer: MeshRoomPeer) {
        val chip = overflowChips[peer.id] ?: return
        chip.name.text = peer.userName
        chip.avatar.text = initialsFor(peer.userName)
        chip.micBadge.visibility = if (peer.micEnabled) View.GONE else View.VISIBLE
    }

    /** A 64dp avatar chip: initials circle, name, mic-off badge. Tap to pin. */
    private fun buildOverflowChip(peerId: String): OverflowChip {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(dp(4), dp(2), dp(4), dp(2))
        root.isClickable = true
        root.setBackgroundResource(R.drawable.bg_overflow_chip)
        root.setOnClickListener { onPinRequest?.invoke(peerId) }

        val avatar = TextView(context)
        avatar.textSize = 14f
        avatar.setTextColor(Color.WHITE)
        avatar.gravity = Gravity.CENTER
        avatar.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(avatarPalette[peerId.hashCode().mod(avatarPalette.size)])
            },
        )

        val micBadge = ImageView(context)
        micBadge.setImageResource(R.drawable.ic_mic_off)
        micBadge.setBackgroundResource(R.drawable.bg_tile_badge)
        micBadge.imageTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
        micBadge.setPadding(dp(3), dp(3), dp(3), dp(3))
        micBadge.visibility = View.GONE

        val avatarWrap = FrameLayout(context)
        avatarWrap.addView(
            avatar,
            FrameLayout.LayoutParams(dp(44), dp(44)),
        )
        avatarWrap.addView(
            micBadge,
            FrameLayout.LayoutParams(dp(16), dp(16), Gravity.BOTTOM or Gravity.END),
        )

        val name = TextView(context)
        name.setTextColor(Color.WHITE)
        name.textSize = 10f
        name.setSingleLine(true)
        name.ellipsize = TextUtils.TruncateAt.END
        name.gravity = Gravity.CENTER

        root.addView(
            avatarWrap,
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        root.addView(
            name,
            LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(4)
            },
        )
        return OverflowChip(root, avatar, name, micBadge)
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
        val ring: View,
        val pinBadge: ImageView,
        val chip: TextView,
        val micBadge: ImageView,
        val camBadge: ImageView,
    )

    private data class OverflowChip(
        val root: LinearLayout,
        val avatar: TextView,
        val name: TextView,
        val micBadge: ImageView,
    )

    private companion object {
        /** Key of the "You" tile: the host's local preview wrapped as a grid tile. */
        const val LOCAL_PEER_ID = "__local__"

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