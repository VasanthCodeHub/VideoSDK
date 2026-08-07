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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.meshcall.sdk.R
import dev.meshcall.sdk.api.MeshCall
import dev.meshcall.sdk.api.MeshParticipant
import dev.meshcall.sdk.internal.mesh.MeshMeetingManager
import dev.meshcall.sdk.internal.util.AvatarBitmaps
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
import kotlin.math.min

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
 *  - Tiles fill the grid in rows: pairs per row, and a leftover odd tile spans the full
 *    width of its own row. Rows share the height equally, so the whole block shrinks as
 *    people join instead of pushing anyone out.
 *  - Only past [maxGridTiles] — far beyond what a mesh meeting carries — does anyone become
 *    a compact avatar chip in [overflowContainer]; pinned and speaking participants are
 *    then promoted back into the grid.
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

    /**
     * Hard ceiling on grid tiles; the "You" tile always keeps one.
     *
     * This is a legibility floor, not a design preference: at ten tiles a phone-sized cell
     * is too small to recognise a face, so the tail goes to the overflow strip. A mesh
     * meeting is nowhere near this in practice, so everyone normally stays in the grid.
     */
    private val maxGridTiles = 9

    private var localMicOn = true
    private var localCamOn = true

    /** True while the local tile is showing a shared screen rather than the camera. */
    private var localSharing = false

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

                    is MeshMeetingManager.MediaEvent.LocalVideoChanged -> {
                        localSharing = manager.screenSharing.value
                        tiles[LOCAL_PEER_ID]?.let { bindTrack(it, event.track) }
                        localPreview()?.apply {
                            // A shared screen is not a selfie: mirroring reverses every
                            // word on it.
                            setMirror(!localSharing && manager.frontCameraActive.value)
                            // FIT, not FILL, while sharing: a phone screen is far taller
                            // than a grid tile, and cropping it to fill would cut off the
                            // very content being shared.
                            setScalingType(
                                if (localSharing) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                                else RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                            )
                        }
                        applyLocalChrome()
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
        preview.setMirror(manager.frontCameraActive.value)
        ensureLocalTile(preview, manager.avatarBase64)
        bindTrack(tiles[LOCAL_PEER_ID] ?: return, manager.localVideo())
        applyLocalChrome()

        scope.launch {
            manager.frontCameraActive.collect { isFront ->
                localPreview()?.setMirror(!localSharing && isFront)
            }
        }
    }

    /** Wrap the host's preview into a regular grid tile labelled "You". */
    private fun ensureLocalTile(preview: MeshVideoRenderer, avatarBase64: String?) {
        if (tiles.containsKey(LOCAL_PEER_ID)) return
        (preview.parent as? ViewGroup)?.removeView(preview)
        val tile = buildTile(
            LOCAL_PEER_ID,
            context.getString(R.string.meshcall_you),
            preview,
            avatarBase64,
        )
        tile.dot.setBackgroundResource(R.drawable.dot_connected)
        tiles[LOCAL_PEER_ID] = tile
        relayout()
    }

    private fun applyLocalChrome() {
        val tile = tiles[LOCAL_PEER_ID] ?: return
        tile.chip.text = context.getString(
            if (localSharing) R.string.meshcall_you_are_sharing else R.string.meshcall_you,
        )
        applyMicIndicator(tile.micBadge, localMicOn)
        // While sharing, the camera badge and the avatar placeholder both describe a camera
        // nobody is watching — the tile is carrying the screen. Showing them would read as
        // "sharing is broken".
        tile.camBadge.visibility = visibleIf(!localCamOn && !localSharing)
        tile.placeholder.visibility = visibleIf(!localCamOn && !localSharing)
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
        val tile = buildTile(participant.id, participant.userName, renderer, participant.avatarBase64)
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
    private fun buildTile(
        peerId: String,
        name: String,
        renderer: MeshVideoRenderer,
        avatarBase64: String? = null,
    ): Tile {
        val isLocal = peerId == LOCAL_PEER_ID
        val frame = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_tile_frame)
        }

        // No background, elevation, or clipToOutline on the renderer: the surface sits in
        // the underlay plane, so anything painted on the view itself composites *above* the
        // video and hides it. Rounded corners come from the TileFrameDrawable overlay below.
        // See README §7 rule 6.
        frame.addView(renderer, matchParent())

        val placeholder = buildPlaceholder(name, peerId, avatarBase64).apply { visibility = View.GONE }
        frame.addView(placeholder, matchParent())

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

        // Topmost: the frame border *and* the corner mask that gives the tile its rounded
        // look. Both have to be painted here, above the video — the renderer's surface is
        // an unclippable underlay, so its square corners can only be covered, not cut.
        frame.addView(
            View(context).apply {
                background = TileFrameDrawable(
                    fillColor = color(R.color.meshcall_tile_frame_bg),
                    strokeColor = color(R.color.meshcall_tile_frame_stroke),
                    strokeWidth = dpF(1.5f),
                    cornerRadius = dpF(18f),
                )
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
    private fun buildPlaceholder(name: String, peerId: String, avatarBase64: String?): View {
        val layer = FrameLayout(context).apply {
            setBackgroundColor(color(R.color.meshcall_tile_placeholder_bg))
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val avatar = buildAvatarView(name, peerId, avatarBase64, textSizeSp = 22f)
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

    /**
     * A circular avatar view: the participant's photo when they have one, else their
     * initials over a color drawn from their id. Caller supplies the size via LayoutParams.
     */
    private fun buildAvatarView(
        name: String,
        peerId: String,
        avatarBase64: String?,
        textSizeSp: Float,
    ): View {
        val bitmap = AvatarBitmaps.decodeCircular(avatarBase64)
        if (bitmap != null) {
            return ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bitmap)
            }
        }
        return TextView(context).apply {
            text = initialsFor(name)
            setTextColor(Color.WHITE)
            textSize = textSizeSp
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(avatarPalette[peerId.hashCode().mod(avatarPalette.size)])
            }
        }
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
     * Choose the main-grid occupants.
     *
     * While everyone fits — the normal case — the grid keeps plain roster order with the
     * "You" tile last. Promoting the speaker here would reshuffle every tile each time
     * somebody starts talking, and a grid that rearranges itself mid-sentence is far more
     * distracting than it is helpful.
     *
     * Only once the roster outgrows [maxGridTiles] does priority matter: the pinned
     * participant comes first, then the active speaker, then roster order, and the
     * lowest-priority remote is evicted to the overflow strip so the "You" tile keeps a slot.
     */
    private fun mainTileIds(): List<String> {
        val remote = tiles.keys.filter { it != LOCAL_PEER_ID }
        val hasLocal = LOCAL_PEER_ID in tiles
        if (remote.size + (if (hasLocal) 1 else 0) <= maxGridTiles) {
            return if (hasLocal) remote + LOCAL_PEER_ID else remote
        }

        val main = LinkedHashSet<String>()
        pinnedId?.takeIf { it in remote }?.let(main::add)
        speakerId?.takeIf { it in remote }?.let(main::add)
        for (id in remote) {
            if (main.size >= maxGridTiles) break
            main.add(id)
        }
        if (hasLocal) {
            if (main.size >= maxGridTiles) {
                main.lastOrNull { it != pinnedId && it != speakerId }?.let(main::remove)
            }
            main.add(LOCAL_PEER_ID)
        }
        return main.toList()
    }

    /**
     * How many tiles sit on each row, top to bottom.
     *
     * The rule is "pair them up, and let a leftover tile own its row": 3 tiles are a pair
     * plus one full-width tile underneath, 4 are a clean 2x2, 5 are two pairs plus a
     * full-width tile. Rows always fill the container edge to edge, so a partial row is
     * stretched rather than left as a half-width tile with a hole beside it.
     *
     * Row size grows with the container instead of staying at two forever: past six tiles a
     * portrait phone would otherwise stack four rows of letterbox slivers, and a landscape
     * container has the width to seat three or four across from the start.
     *
     * Two participants are special-cased (Meet/WhatsApp convention): portrait stacks them
     * 50/50 top-bottom, which suits upright faces far better than two tall slivers;
     * landscape keeps them side by side.
     */
    private fun rowPlan(count: Int, landscape: Boolean): List<Int> {
        if (count <= 1) return listOf(1)
        if (count == 2) return if (landscape) listOf(2) else listOf(1, 1)

        val perRow = if (landscape) {
            when {
                count <= 4 -> 2
                count <= 6 -> 3
                else -> 4
            }
        } else {
            if (count <= 6) 2 else 3
        }

        val plan = ArrayList<Int>()
        var remaining = count
        while (remaining > 0) {
            val inRow = min(perRow, remaining)
            plan.add(inRow)
            remaining -= inRow
        }
        return plan
    }

    /**
     * Place the main-grid tiles along the [rowPlan].
     *
     * Every row is the same height and every tile in a row the same width, so adding a
     * participant shrinks the existing tiles instead of displacing anyone: the block always
     * fills the container exactly, with a uniform gap as the only gutter.
     */
    private fun applyGrid() {
        val width = gridContainer.width
        val height = gridContainer.height
        val mainIds = mainTileIds()
        val overflowIds = tiles.keys.filter { it !in mainIds }

        if (mainIds.isNotEmpty() && width > 0 && height > 0) {
            val gap = dp(10)
            val plan = rowPlan(mainIds.size, landscape = width > height)
            val cellH = (height - gap * (plan.size + 1)) / plan.size

            if (cellH > 0) {
                var index = 0
                plan.forEachIndexed { row, inRow ->
                    // Per-row width, not one global column width: this is what lets the odd
                    // tile of a 3- or 5-person meeting span the whole row.
                    val cellW = (width - gap * (inRow + 1)) / inRow
                    val top = gap + row * (cellH + gap)
                    repeat(inRow) { col ->
                        val tile = tiles[mainIds[index++]].takeIf { cellW > 0 } ?: return@repeat
                        tile.frame.visibility = View.VISIBLE
                        val lp = tile.frame.layoutParams as FrameLayout.LayoutParams
                        lp.width = cellW
                        lp.height = cellH
                        lp.leftMargin = gap + col * (cellW + gap)
                        lp.topMargin = top
                        lp.rightMargin = 0
                        lp.bottomMargin = 0
                        tile.frame.layoutParams = lp
                    }
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

    private fun dp(value: Int): Int = dpF(value.toFloat()).toInt()

    /** Unrounded px, for the frame's sub-pixel stroke width and corner radius. */
    private fun dpF(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    )

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
