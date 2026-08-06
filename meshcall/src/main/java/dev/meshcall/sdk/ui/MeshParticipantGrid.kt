package dev.meshcall.sdk.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import androidx.core.content.ContextCompat
import dev.meshcall.sdk.R
import dev.meshcall.sdk.api.MeshCall
import dev.meshcall.sdk.api.MeshParticipant
import dev.meshcall.sdk.internal.mesh.MeshMeetingManager
import dev.meshcall.sdk.internal.util.MeshLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Binds a [MeshCall] session to a responsive tile grid inside [gridContainer].
 *
 * Layout model:
 *  - Every participant gets an equal tile, the local camera included: its preview is
 *    wrapped into a "You" tile so the self view sits in the grid like everyone else
 *    rather than floating over it.
 *  - A tile is a [FrameLayout] holding the video surface (underlay plane), a placeholder
 *    shown while the peer has no video, and chrome (name chip, mic/camera badges,
 *    connection dot, speaking ring) that always draws above the video.
 *  - Up to [mainSlots] tiles occupy the grid; everyone else becomes a compact avatar chip
 *    in [overflowContainer]. Pinned and speaking participants are promoted into the grid.
 *  - Cells never overlap, so SurfaceViews compose without z-order fights.
 *
 * Most hosts should use [MeshMeetingView], which owns one of these plus the meeting
 * chrome. Use this directly only to build a custom meeting screen.
 *
 * [bind] must run after [MeshCall.join] — the shared EGL context comes from the live
 * session. Call [release] when the host view is destroyed.
 */
class MeshParticipantGrid(
    private val context: Context,
    private val gridContainer: ViewGroup,
    private val overflowContainer: ViewGroup? = null,
) {

    private val scope = CoroutineScope(Dispatchers.Main)

    private var eglContext: EglBase.Context? = null

    /** Tile per peer id, in roster order, so they survive roster churn. */
    private val tiles = LinkedHashMap<String, Tile>()

    /** Latest roster snapshot per peer (used by the overflow chips). */
    private val participantsById = HashMap<String, MeshParticipant>()

    private val overflowChips = LinkedHashMap<String, OverflowChip>()

    /** Renderers already initialized against the EGL context (init is one-shot per renderer). */
    private val initialized = HashSet<MeshVideoRenderer>()

    /** Latest in-bound stream per peer, so a stream can bind as soon as a tile exists. */
    private val streamsByPeer = HashMap<String, MediaStream>()

    private var pinnedId: String? = null
    private var speakerId: String? = null

    /** Max tiles in the main grid; the "You" tile always keeps one. */
    private val mainSlots = 4

    private var localMicOn = true
    private var localCamOn = true

    /**
     * Invoked when the user taps a tile or overflow chip. The host decides the new pin
     * state and calls [setPinned] — typically a toggle.
     */
    var onPinRequest: ((String) -> Unit)? = null

    val pinnedPeerId: String? get() = pinnedId

    private val _renderersReady = MutableStateFlow(false)

    /** True once container renderers are initialized and able to draw. */
    val renderersReady: StateFlow<Boolean> = _renderersReady.asStateFlow()

    /** Renderers currently in use, one per tile (the local tile included). */
    val activeRenderers: List<SurfaceViewRenderer>
        get() = tiles.values.map { it.renderer }

    /** Pin [peerId] into the main grid (null to unpin). */
    fun setPinned(peerId: String?) {
        if (pinnedId == peerId) return
        pinnedId = peerId
        tiles.values.forEach { it.pinBadge.visibility = visibleIf(it.peerId == peerId) }
        relayout()
    }

    /** Mark [peerId] as the active speaker (null when nobody is talking). */
    fun setSpeaker(peerId: String?) {
        if (speakerId == peerId) return
        speakerId = peerId
        tiles.values.forEach { it.ring.visibility = visibleIf(it.peerId == peerId) }
        relayout()
    }

    /** Reflect local mic/camera state on the "You" tile. */
    fun setLocalMediaState(micOn: Boolean, camOn: Boolean) {
        if (localMicOn == micOn && localCamOn == camOn) return
        localMicOn = micOn
        localCamOn = camOn
        applyLocalChrome()
    }

    /**
     * Bind to an in-progress meeting. [call] must already be joined. Any prior binding is
     * released first.
     */
    fun bind(call: MeshCall) {
        val manager = call.meshManager ?: run {
            MeshLog.w(TAG, "bind() before join(); nothing to render")
            return
        }
        if (initialized.isNotEmpty()) unbind()

        eglContext = manager.eglContext
        seedLocalRenderer()
        bindLocalPreview(manager)
        relayout()

        scope.launch { call.participants.collect(::syncParticipants) }
        scope.launch {
            manager.mediaEvents.collect { event ->
                when (event) {
                    is MeshMeetingManager.MediaEvent.RemoteStreamChanged -> {
                        if (event.stream == null) streamsByPeer.remove(event.peerId)
                        else streamsByPeer[event.peerId] = event.stream
                        bindPeerStream(event.peerId, event.stream)
                    }

                    is MeshMeetingManager.MediaEvent.RemoteStreamAdded ->
                        // The stream itself arrives via RemoteStreamChanged; this only
                        // makes sure a tile exists to receive it.
                        bindPeerStream(event.peerId, streamsByPeer[event.peerId])

                    is MeshMeetingManager.MediaEvent.RemoteStreamRemoved -> {
                        streamsByPeer.remove(event.peerId)
                        bindPeerStream(event.peerId, null)
                    }
                }
            }
        }

        // Reflow on any container resize (rotation, split-screen, insets).
        gridContainer.addOnLayoutChangeListener(layoutListener)
    }

    /** Stop applying media to renderers. Safe to call repeatedly. */
    fun unbind() {
        gridContainer.removeOnLayoutChangeListener(layoutListener)
        tiles.values.forEach(::detachTrack)
        streamsByPeer.clear()
        eglContext = null
        _renderersReady.value = false
    }

    /** Release all renderers and the coroutine scope. Call from the host's onDestroy. */
    fun release() {
        unbind()
        tiles.values.forEach { tile ->
            gridContainer.removeView(tile.frame)
            tile.renderer.release()
        }
        tiles.clear()
        participantsById.clear()
        overflowChips.keys.toList().forEach(::removeOverflowChip)
        initialized.clear()
        scope.cancel()
    }

    // ---- Local tile -------------------------------------------------------------

    /** Adopt a renderer the host already placed in the layout, or create one. */
    private fun seedLocalRenderer() {
        val egl = eglContext ?: return
        allChildren().filterIsInstance<MeshVideoRenderer>().forEach { initRenderer(it, egl) }
        if (localPreview() == null) {
            val preview = MeshVideoRenderer(context)
            gridContainer.addView(preview, matchParent())
            initRenderer(preview, egl)
        }
        _renderersReady.value = initialized.isNotEmpty()
    }

    private fun bindLocalPreview(manager: MeshMeetingManager) {
        val egl = eglContext ?: return
        val preview = localPreview() ?: return
        initRenderer(preview, egl)
        preview.setMirror(true)
        ensureLocalTile(preview)
        bindTrack(tiles[LOCAL_PEER_ID] ?: return, manager.localVideo())
        applyLocalChrome()
    }

    /** Wrap the host's preview into a regular grid tile labelled "You". */
    private fun ensureLocalTile(preview: MeshVideoRenderer) {
        if (tiles.containsKey(LOCAL_PEER_ID)) return
        (preview.parent as? ViewGroup)?.removeView(preview)
        val tile = buildTile(LOCAL_PEER_ID, context.getString(R.string.meshcall_you), preview)
        tile.dot.setBackgroundResource(R.drawable.dot_connected)
        tiles[LOCAL_PEER_ID] = tile
        relayout()
    }

    private fun applyLocalChrome() {
        val tile = tiles[LOCAL_PEER_ID] ?: return
        tile.chip.text = context.getString(R.string.meshcall_you)
        applyMicIndicator(tile.micBadge, localMicOn)
        tile.camBadge.visibility = visibleIf(!localCamOn)
        tile.placeholder.visibility = visibleIf(!localCamOn)
        tile.dot.setBackgroundResource(R.drawable.dot_connected)
    }

    // ---- Roster ------------------------------------------------------------------

    private fun syncParticipants(roster: List<MeshParticipant>) {
        val wanted = roster.mapTo(HashSet()) { it.id }
        tiles.keys.toList()
            .filter { it != LOCAL_PEER_ID && it !in wanted }
            .forEach(::removeTile)

        roster.forEach { participant ->
            participantsById[participant.id] = participant
            val tile = tiles[participant.id] ?: createRemoteTile(participant)
            tile.name = participant.userName
            applyChrome(tile, participant)
            refreshOverflowChip(participant)
            bindPeerStream(participant.id, streamsByPeer[participant.id])
        }
        relayout()
    }

    private fun createRemoteTile(participant: MeshParticipant): Tile {
        val egl = eglContext ?: error("bind() must run before participants arrive")
        val renderer = MeshVideoRenderer(context)
        initRenderer(renderer, egl)
        renderer.setMirror(false)
        val tile = buildTile(participant.id, participant.userName, renderer)
        tiles[participant.id] = tile
        relayout()
        return tile
    }

    private fun removeTile(peerId: String) {
        val tile = tiles.remove(peerId) ?: return
        detachTrack(tile)
        participantsById.remove(peerId)
        streamsByPeer.remove(peerId)
        gridContainer.removeView(tile.frame)
        initialized.remove(tile.renderer)
        tile.renderer.release()
        removeOverflowChip(peerId)
        if (pinnedId == peerId) pinnedId = null
        if (speakerId == peerId) speakerId = null
    }

    /**
     * Build one tile. Local and remote tiles are structurally identical — only the chrome
     * differs — so they share this builder instead of two near-duplicate blocks that
     * drift apart over time.
     */
    private fun buildTile(peerId: String, name: String, renderer: MeshVideoRenderer): Tile {
        val isLocal = peerId == LOCAL_PEER_ID
        val frame = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_tile_frame)
        }

        frame.addView(renderer, matchParent())
        // ViewOutlineProvider.BACKGROUND only produces an outline when the view itself has
        // a non-null background — without this, clipToOutline is silently a no-op and the
        // video's square corners overhang the tile's rounded frame.
        renderer.background = ContextCompat.getDrawable(context, R.drawable.bg_tile_clip)
        renderer.outlineProvider = ViewOutlineProvider.BACKGROUND
        renderer.clipToOutline = true

        val placeholder = buildPlaceholder(name, peerId).apply { visibility = View.GONE }
        frame.addView(placeholder, matchParent())

        // Bottom fade that keeps the name chip readable over any video.
        frame.addView(
            View(context).apply { setBackgroundResource(R.drawable.bg_tile_overlay) },
            matchParent(),
        )

        val chip = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(110)
            setBackgroundResource(R.drawable.bg_tile_chip)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            text = name
        }
        // Persistent state icon (not a hide/show badge): always shows the current mic
        // state, top-right, mirroring the participants popup's mic icon.
        val micBadge = ImageView(context).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val camBadge = buildBadge(R.drawable.ic_videocam_off)
        val dot = View(context).apply {
            setBackgroundResource(if (isLocal) R.drawable.dot_connected else R.drawable.dot_connecting)
        }

        // Speaking highlight ring — window plane, so it draws above the video.
        val ring = View(context).apply {
            setBackgroundResource(R.drawable.bg_ring_speaking)
            isClickable = false
            visibility = View.GONE
        }

        val pinBadge = ImageView(context).apply {
            setImageResource(R.drawable.ic_push_pin)
            setBackgroundResource(R.drawable.bg_pin_glass)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            contentDescription = context.getString(R.string.meshcall_pin_participant)
            visibility = View.GONE
            if (!isLocal) setOnClickListener { onPinRequest?.invoke(peerId) }
        }

        val chrome = FrameLayout(context).apply {
            addView(
                dot,
                FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.START).also {
                    it.topMargin = dp(8)
                    it.marginStart = dp(8)
                },
            )
            addView(
                chip,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START,
                ).also {
                    it.bottomMargin = dp(6)
                    it.marginStart = dp(8)
                },
            )
            addView(
                camBadge,
                FrameLayout.LayoutParams(
                    dp(24),
                    dp(24),
                    Gravity.BOTTOM or Gravity.END,
                ).also {
                    it.bottomMargin = dp(6)
                    it.marginEnd = dp(8)
                },
            )
            // Top-right: persistent mic state, matching the participants popup.
            addView(
                micBadge,
                FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).also {
                    it.topMargin = dp(8)
                    it.marginEnd = dp(8)
                },
            )
        }

        frame.addView(ring, matchParent())
        // Top-left, stacked under the connection dot so it never collides with the mic
        // icon that now owns the top-right corner.
        frame.addView(
            pinBadge,
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.START).also {
                it.topMargin = dp(20)
                it.marginStart = dp(8)
            },
        )
        frame.addView(chrome, matchParent())

        // Topmost: the visible frame border, drawn above the video so it is never
        // obscured regardless of whether the SurfaceView clip above was honored.
        frame.addView(
            View(context).apply {
                setBackgroundResource(R.drawable.bg_tile_border)
                isClickable = false
            },
            matchParent(),
        )

        if (!isLocal) frame.setOnClickListener { onPinRequest?.invoke(peerId) }

        gridContainer.addView(frame, matchParent())
        return Tile(peerId, name, frame, renderer, placeholder, dot, ring, pinBadge, chip, micBadge, camBadge)
    }

    /**
     * Mic icon, top-right of every tile: glass background + white icon when unmuted,
     * translucent-red background + red icon when muted — same on/off language as the
     * local control bar and the participants popup.
     */
    private fun applyMicIndicator(icon: ImageView, micOn: Boolean) {
        icon.setImageResource(if (micOn) R.drawable.meshcall_ic_mic else R.drawable.ic_mic_off)
        icon.setBackgroundResource(if (micOn) R.drawable.bg_pin_glass else R.drawable.bg_tile_badge)
        icon.imageTintList = ColorStateList.valueOf(
            color(if (micOn) R.color.meshcall_white else R.color.meshcall_badge_red),
        )
    }

    private fun buildBadge(iconRes: Int) = ImageView(context).apply {
        setImageResource(iconRes)
        setBackgroundResource(R.drawable.bg_tile_badge)
        imageTintList = ColorStateList.valueOf(color(R.color.meshcall_badge_red))
        setPadding(dp(5), dp(5), dp(5), dp(5))
        visibility = View.GONE
    }

    /** Shown while the peer has no video, or their camera is off. */
    private fun buildPlaceholder(name: String, peerId: String): View {
        val layer = FrameLayout(context).apply {
            setBackgroundColor(color(R.color.meshcall_tile_placeholder_bg))
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val avatar = TextView(context).apply {
            text = initialsFor(name)
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(avatarPalette[peerId.hashCode().mod(avatarPalette.size)])
            }
        }
        inner.addView(avatar, LinearLayout.LayoutParams(dp(64), dp(64)))
        inner.addView(
            TextView(context).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 15f
                setSingleLine(true)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(8) },
        )
        layer.addView(
            inner,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        return layer
    }

    private fun applyChrome(tile: Tile, participant: MeshParticipant) {
        tile.chip.text = participant.userName
        applyMicIndicator(tile.micBadge, participant.micEnabled)
        tile.camBadge.visibility = visibleIf(!participant.cameraEnabled)
        tile.ring.visibility = visibleIf(tile.peerId == speakerId)
        tile.pinBadge.visibility = visibleIf(tile.peerId == pinnedId)
        tile.dot.setBackgroundResource(
            when (participant.connectionState) {
                "connected", "completed" -> R.drawable.dot_connected
                "failed", "disconnected", "closed" -> R.drawable.dot_failed
                else -> R.drawable.dot_connecting
            },
        )
        tile.placeholder.visibility =
            visibleIf(!participant.cameraEnabled || tile.boundTrack == null)
    }

    private fun initRenderer(renderer: MeshVideoRenderer, egl: EglBase.Context) {
        if (!initialized.add(renderer)) return
        renderer.setEnableHardwareScaler(true)
        // Fill the tile edge to edge: the default stretch mode distorts non-matching
        // aspects, which reads as "blurry" video on non-16:9 tiles.
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        // Every surface stays in the underlay plane so tile chrome (window plane) always
        // draws above the video. See README §7 rule 6.
        renderer.setZOrderMediaOverlay(false)
        renderer.init(egl, null)
    }

    // ---- Track binding ------------------------------------------------------------

    private fun bindPeerStream(peerId: String, stream: MediaStream?) {
        val tile = tiles[peerId] ?: return
        bindTrack(tile, stream?.videoTracks?.firstOrNull())
    }

    /**
     * Attach [track] to this tile's renderer, replacing whatever was attached before.
     *
     * The previous implementation called `addSink` on every roster update, so a peer
     * accumulated one sink per update and the renderer received the same frame many
     * times. Tracking the bound track makes this idempotent.
     */
    private fun bindTrack(tile: Tile, track: VideoTrack?) {
        if (tile.boundTrack === track) {
            if (track == null) tile.placeholder.visibility = View.VISIBLE
            return
        }
        detachTrack(tile)
        tile.boundTrack = track
        if (track == null) {
            tile.renderer.clearImage()
            tile.placeholder.visibility = View.VISIBLE
            return
        }
        try {
            track.addSink(tile.renderer)
            tile.placeholder.visibility = View.GONE
        } catch (e: Exception) {
            MeshLog.w(TAG, "could not bind video for ${tile.peerId}", e)
            tile.boundTrack = null
            tile.placeholder.visibility = View.VISIBLE
        }
    }

    private fun detachTrack(tile: Tile) {
        val track = tile.boundTrack ?: return
        tile.boundTrack = null
        try {
            track.removeSink(tile.renderer)
        } catch (e: Exception) {
            // Track already disposed with its peer connection; nothing to release.
            MeshLog.d(TAG) { "removeSink(${tile.peerId}) ignored: ${e.message}" }
        }
    }

    // ---- Layout --------------------------------------------------------------------

    private fun relayout() {
        gridContainer.post { applyGrid() }
    }

    /**
     * Choose the main-grid occupants: the pinned participant first, then the active
     * speaker, then roster order. The "You" tile always keeps a slot; when the grid is
     * full the lowest-priority remote is evicted. Everyone else goes to the overflow strip.
     */
    private fun mainTileIds(): List<String> {
        val remote = tiles.keys.filter { it != LOCAL_PEER_ID }
        val main = LinkedHashSet<String>()
        pinnedId?.takeIf { it in remote }?.let(main::add)
        speakerId?.takeIf { it in remote }?.let(main::add)
        for (id in remote) {
            if (main.size >= mainSlots) break
            main.add(id)
        }
        if (LOCAL_PEER_ID in tiles) {
            if (main.size >= mainSlots) {
                main.lastOrNull { it != pinnedId && it != speakerId }?.let(main::remove)
            }
            main.add(LOCAL_PEER_ID)
        }
        return main.toList()
    }

    /**
     * Place the main-grid tiles.
     *
     * Columns adapt to the container: portrait meetings get at most 2 columns (a 3-wide
     * row on a phone makes every tile a sliver), landscape gets up to 4. Partial rows —
     * e.g. 3 tiles in a 2x2 grid — are centered instead of left-aligned, and the whole
     * block is centered vertically, so the layout stays symmetric as people join/leave.
     *
     * Two participants are special-cased (Meet/WhatsApp convention): sqrt-based columns
     * would put them side by side even in portrait, leaving two tall slivers. Portrait
     * instead stacks them into a 50/50 top-bottom split; landscape keeps them side by side.
     */
    private fun applyGrid() {
        val width = gridContainer.width
        val height = gridContainer.height
        val mainIds = mainTileIds()
        val overflowIds = tiles.keys.filter { it !in mainIds }

        if (mainIds.isNotEmpty() && width > 0 && height > 0) {
            val n = mainIds.size
            val gap = dp(10)
            val maxCols = if (width > height) 4 else 2
            val cols = if (n == 2) {
                if (width > height) 2 else 1
            } else {
                ceil(sqrt(n.toDouble())).toInt().coerceIn(1, maxCols)
            }
            val rows = ceil(n.toDouble() / cols).toInt()
            val cellW = (width - gap * (cols + 1)) / cols
            val cellH = (height - gap * (rows + 1)) / rows

            if (cellW > 0 && cellH > 0) {
                // Center the whole block vertically when the grid leaves unused space.
                val blockH = rows * cellH + (rows - 1) * gap
                val topPad = ((height - blockH) / 2).coerceAtLeast(gap)

                mainIds.forEachIndexed { index, peerId ->
                    val tile = tiles[peerId] ?: return@forEachIndexed
                    tile.frame.visibility = View.VISIBLE
                    val row = index / cols
                    val col = index % cols
                    val inRow = min(cols, n - row * cols)
                    // Center a partial last row so the odd tile sits in the middle.
                    val rowWidth = inRow * cellW + (inRow - 1) * gap
                    val leftPad = ((width - rowWidth) / 2).coerceAtLeast(gap)

                    val lp = tile.frame.layoutParams as FrameLayout.LayoutParams
                    lp.width = cellW
                    lp.height = cellH
                    lp.leftMargin = leftPad + col * (cellW + gap)
                    lp.topMargin = topPad + row * (cellH + gap)
                    lp.rightMargin = 0
                    lp.bottomMargin = 0
                    tile.frame.layoutParams = lp
                }
            }
        }

        // Overflow participants keep their (hidden) renderers alive so they can return to
        // the grid instantly; the strip shows compact avatar chips instead.
        overflowIds.forEach { tiles[it]?.frame?.visibility = View.GONE }
        syncOverflowChips(overflowIds)
    }

    // ---- Overflow strip ------------------------------------------------------------

    private fun syncOverflowChips(overflowIds: List<String>) {
        val container = overflowContainer ?: return
        overflowChips.keys.toList()
            .filterNot { it in overflowIds }
            .forEach(::removeOverflowChip)

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
            refreshOverflowChip(
                participantsById[id] ?: MeshParticipant(id, id, true, true, "new"),
            )
        }
    }

    private fun refreshOverflowChip(participant: MeshParticipant) {
        val chip = overflowChips[participant.id] ?: return
        chip.name.text = participant.userName
        chip.avatar.text = initialsFor(participant.userName)
        chip.micBadge.visibility = visibleIf(!participant.micEnabled)
    }

    private fun removeOverflowChip(peerId: String) {
        val chip = overflowChips.remove(peerId) ?: return
        overflowContainer?.removeView(chip.root)
    }

    /** A compact avatar chip: initials circle, name, mic-off badge. Tap to pin. */
    private fun buildOverflowChip(peerId: String): OverflowChip {
        val avatar = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(avatarPalette[peerId.hashCode().mod(avatarPalette.size)])
            }
        }
        val micBadge = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic_off)
            setBackgroundResource(R.drawable.bg_tile_badge)
            imageTintList = ColorStateList.valueOf(color(R.color.meshcall_badge_red))
            setPadding(dp(3), dp(3), dp(3), dp(3))
            visibility = View.GONE
        }
        val avatarWrap = FrameLayout(context).apply {
            addView(avatar, FrameLayout.LayoutParams(dp(44), dp(44)))
            addView(
                micBadge,
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.BOTTOM or Gravity.END),
            )
        }
        val name = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
            isClickable = true
            setBackgroundResource(R.drawable.bg_overflow_chip)
            setOnClickListener { onPinRequest?.invoke(peerId) }
            addView(avatarWrap, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(
                name,
                LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.topMargin = dp(4) },
            )
        }
        return OverflowChip(root, avatar, name, micBadge)
    }

    // ---- Helpers --------------------------------------------------------------------

    private fun localPreview(): MeshVideoRenderer? =
        allChildren().filterIsInstance<MeshVideoRenderer>().firstOrNull()

    private fun allChildren(): Sequence<View> = sequence {
        val queue = ArrayDeque<View>()
        for (i in 0 until gridContainer.childCount) queue.addLast(gridContainer.getChildAt(i))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            yield(current)
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) queue.addLast(current.getChildAt(i))
            }
        }
    }

    private fun initialsFor(name: String): String =
        name.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { it.first().uppercaseChar().toString() }
            .take(2)

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    private val layoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyGrid() }

    private class Tile(
        val peerId: String,
        var name: String,
        val frame: FrameLayout,
        val renderer: MeshVideoRenderer,
        val placeholder: View,
        val dot: View,
        val ring: View,
        val pinBadge: ImageView,
        val chip: TextView,
        val micBadge: ImageView,
        val camBadge: ImageView,
    ) {
        /** The track currently sinking into [renderer], so binding stays idempotent. */
        var boundTrack: VideoTrack? = null
    }

    private class OverflowChip(
        val root: LinearLayout,
        val avatar: TextView,
        val name: TextView,
        val micBadge: ImageView,
    )

    private companion object {
        const val TAG = "Grid"

        /** Key of the "You" tile: the host's local preview wrapped as a grid tile. */
        const val LOCAL_PEER_ID = "__local__"

        val avatarPalette = intArrayOf(
            0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF5350.toInt(), 0xFFFFA726.toInt(),
            0xFFAB47BC.toInt(), 0xFF29B6F6.toInt(), 0xFF8D6E63.toInt(), 0xFF66BB6A.toInt(),
        )
    }
}
