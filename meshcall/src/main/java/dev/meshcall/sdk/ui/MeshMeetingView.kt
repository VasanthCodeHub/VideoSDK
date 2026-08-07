package dev.meshcall.sdk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dev.meshcall.sdk.R
import dev.meshcall.sdk.api.Admission
import dev.meshcall.sdk.api.AudioRoute
import dev.meshcall.sdk.api.AudioRouteState
import dev.meshcall.sdk.api.JoinRequest
import dev.meshcall.sdk.api.MeshCall
import dev.meshcall.sdk.api.MeshParticipant
import dev.meshcall.sdk.internal.util.AvatarBitmaps
import dev.meshcall.sdk.internal.util.MeshLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The complete meeting screen, owned by the SDK.
 *
 * Drop this into a layout (or set it as the content view), call [attach] with a joined
 * [MeshCall], and the host is done: the video grid, meeting-code badge, call timer,
 * signaling banner, participants panel, and the mic / camera / audio-output / flip-camera /
 * more / leave controls are all handled here.
 *
 * The host keeps only what genuinely belongs to it: runtime permissions, navigation, and
 * deciding what "share screen" should do.
 *
 * ```
 * val meeting = MeshMeetingView(this)
 * setContentView(meeting)
 * val call = MeshCall(applicationContext)
 * call.join(brokerUrl, meetingId, displayName)
 * meeting.attach(call, meetingId)
 * meeting.onLeave = { finish() }
 * // onDestroy:
 * meeting.detach()
 * call.dispose()
 * ```
 *
 * Deliberately built from platform views only — no Material dependency — so it inflates
 * under any host theme.
 */
class MeshMeetingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val gridContainer: FrameLayout
    private val overflowScroll: View
    private val overflowStrip: LinearLayout
    private val meetingCodeLabel: TextView
    private val timerLabel: TextView
    private val liveDot: View
    private val banner: TextView
    private val participantsPanel: LinearLayout
    private val participantsTitle: TextView
    private val participantsList: LinearLayout
    private val micButton: ImageButton
    private val cameraButton: ImageButton
    private val audioRouteButton: ImageButton
    private val moreButton: ImageButton
    private val leaveButton: ImageButton
    private val participantsButton: ImageButton
    private val switchCameraButton: ImageButton

    // Private-meeting chrome: the host's admit/decline card, and the waiting room the
    // person outside sees.
    private val joinRequestCard: LinearLayout
    private val requestAvatar: TextView
    private val requestName: TextView
    private val requestMore: TextView
    private val admitButton: TextView
    private val declineButton: TextView
    private val waitingOverlay: View
    private val waitingTitle: TextView
    private val waitingMessage: TextView
    private val overlayLeaveButton: TextView

    /** Oldest first; the card always answers [0] and counts the rest. */
    private var joinRequests: List<JoinRequest> = emptyList()

    /** One built row per roster slot; kept parallel to [MeshCall.participants] by index. */
    private val participantRows = mutableListOf<ParticipantRow>()

    private var grid: MeshParticipantGrid? = null
    private var call: MeshCall? = null
    private var scope: CoroutineScope? = null
    private var timerJob: Job? = null
    private var meetingStartedAt = 0L

    /** Latest routing snapshot, so the picker can be built on tap without re-reading state. */
    private var audioRouteState = AudioRouteState()

    /** Mirrors [MeshCall.screenSharing]; decides whether "more" offers start or stop. */
    private var screenSharing = false

    /** Invoked after the user confirms leaving. Navigate away here. */
    var onLeave: (() -> Unit)? = null

    /**
     * Invoked when "share screen" is tapped. Null hides the entry.
     *
     * Screen share lives in the "more" sheet, not the control bar: MediaProjection consent
     * is an Activity-scoped flow the host has to run, so it is a host decision, and the bar
     * is reserved for the controls used every meeting.
     */
    var onShareScreen: (() -> Unit)? = null

    /**
     * Replace the built-in "more" sheet with the host's own menu.
     *
     * Left null — the recommended setup — "more" opens the SDK's sheet with screen share
     * and the other secondary actions in it. Set this only when the host needs entries the
     * SDK knows nothing about; it then owns the whole menu, including re-offering share.
     */
    var onMoreOptions: (() -> Unit)? = null

    /**
     * Ask before leaving. Leave it on unless the host runs its own confirmation and calls
     * [leaveNow] itself.
     */
    var confirmBeforeLeaving: Boolean = true

    init {
        LayoutInflater.from(context).inflate(R.layout.meshcall_view_meeting, this, true)

        gridContainer = findViewById(R.id.meshcall_grid_container)
        overflowScroll = findViewById(R.id.meshcall_overflow_scroll)
        overflowStrip = findViewById(R.id.meshcall_overflow_strip)
        meetingCodeLabel = findViewById(R.id.meshcall_meeting_code)
        timerLabel = findViewById(R.id.meshcall_timer)
        liveDot = findViewById(R.id.meshcall_live_dot)
        banner = findViewById(R.id.meshcall_banner)
        participantsPanel = findViewById(R.id.meshcall_participants_panel)
        participantsTitle = findViewById(R.id.meshcall_participants_title)
        participantsList = findViewById(R.id.meshcall_participants_list)
        micButton = findViewById(R.id.meshcall_btn_mic)
        cameraButton = findViewById(R.id.meshcall_btn_camera)
        audioRouteButton = findViewById(R.id.meshcall_btn_audio_route)
        moreButton = findViewById(R.id.meshcall_btn_more)
        leaveButton = findViewById(R.id.meshcall_btn_leave)
        participantsButton = findViewById(R.id.meshcall_btn_participants)
        switchCameraButton = findViewById(R.id.meshcall_btn_switch_camera)
        joinRequestCard = findViewById(R.id.meshcall_join_request_card)
        requestAvatar = findViewById(R.id.meshcall_request_avatar)
        requestName = findViewById(R.id.meshcall_request_name)
        requestMore = findViewById(R.id.meshcall_request_more)
        admitButton = findViewById(R.id.meshcall_btn_admit)
        declineButton = findViewById(R.id.meshcall_btn_decline)
        waitingOverlay = findViewById(R.id.meshcall_waiting_overlay)
        waitingTitle = findViewById(R.id.meshcall_waiting_title)
        waitingMessage = findViewById(R.id.meshcall_waiting_message)
        overlayLeaveButton = findViewById(R.id.meshcall_btn_overlay_leave)

        wireControls()
    }

    private fun wireControls() {
        micButton.setOnClickListener { call?.toggleMic() }
        cameraButton.setOnClickListener { call?.toggleCamera() }
        switchCameraButton.setOnClickListener { call?.switchCamera() }
        audioRouteButton.setOnClickListener { showAudioRoutePicker() }
        moreButton.setOnClickListener {
            val hostMenu = onMoreOptions
            if (hostMenu != null) hostMenu() else showMoreSheet()
        }
        participantsButton.setOnClickListener {
            participantsPanel.visibility =
                if (participantsPanel.visibility == VISIBLE) GONE else VISIBLE
        }
        // Already out of the meeting, so there is nothing to confirm leaving from.
        overlayLeaveButton.setOnClickListener { leaveNow() }
        admitButton.setOnClickListener { answerFirstRequest(admit = true) }
        declineButton.setOnClickListener { answerFirstRequest(admit = false) }
        meetingCodeLabel.setOnClickListener { copyMeetingCode() }
        leaveButton.setOnClickListener {
            if (confirmBeforeLeaving) confirmLeave() else leaveNow()
        }
        // Buttons do nothing useful until attach() supplies a call.
        setControlsEnabled(false)
    }

    /**
     * Bind an already-joined [call] to this view.
     *
     * @param meetingId shown in the badge and copied to the clipboard when tapped
     * @param showConnectionBanner whether to show the "connecting to broker" banner
     */
    fun attach(
        call: MeshCall,
        meetingId: String = call.currentMeetingId.orEmpty(),
        showConnectionBanner: Boolean = true,
    ) {
        detach()
        this.call = call

        meetingCodeLabel.text = meetingId
        setControlsEnabled(true)

        val participantGrid = MeshParticipantGrid(context, gridContainer, overflowStrip)
        participantGrid.onPinRequest = { peerId ->
            participantGrid.setPinned(if (participantGrid.pinnedPeerId == peerId) null else peerId)
        }
        participantGrid.bind(call)
        grid = participantGrid

        val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = viewScope

        viewScope.launch { call.participants.collect(::renderParticipants) }
        viewScope.launch { call.speaker.collect(participantGrid::setSpeaker) }
        viewScope.launch {
            call.localMedia.collect { state ->
                applyMicButton(state.micEnabled)
                applyCameraButton(state.cameraEnabled)
                participantGrid.setLocalMediaState(state.micEnabled, state.cameraEnabled)
            }
        }
        viewScope.launch {
            call.screenSharing.collect { sharing ->
                screenSharing = sharing
                // The "more" button carries the only stop control, so it has to advertise
                // that something is running behind it.
                moreButton.setBackgroundResource(
                    if (sharing) R.drawable.meshcall_bg_control_active
                    else R.drawable.meshcall_bg_control,
                )
            }
        }
        viewScope.launch { call.frontCameraActive.collect(::applySwitchCameraButton) }
        viewScope.launch { call.joinRequests.collect(::renderJoinRequests) }
        viewScope.launch {
            call.admission.collect { admission ->
                when (admission) {
                    Admission.AWAITING_APPROVAL -> showOverlay(
                        R.string.meshcall_waiting_title,
                        R.string.meshcall_waiting_message,
                        terminal = false,
                    )

                    Admission.DENIED -> showOverlay(
                        R.string.meshcall_denied_title,
                        R.string.meshcall_denied_message,
                        terminal = true,
                    )

                    Admission.JOINING, Admission.ADMITTED -> hideOverlay()
                }
            }
        }
        viewScope.launch {
            call.meetingNotFound.collect {
                showOverlay(
                    R.string.meshcall_ended_title,
                    R.string.meshcall_ended_message,
                    terminal = true,
                )
            }
        }
        viewScope.launch {
            call.audioRoute.collect { state ->
                audioRouteState = state
                applyAudioRouteButton(state)
            }
        }
        viewScope.launch {
            call.connected.collect { connected ->
                banner.visibility = if (connected || !showConnectionBanner) GONE else VISIBLE
            }
        }
        viewScope.launch {
            call.errors.collect { message ->
                MeshLog.w(TAG, "surfaced error: $message")
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        startTimer()
        MeshLog.i(TAG) { "attached to meeting $meetingId" }
    }

    /** Unbind and release renderers. Call from the host's onDestroy. */
    fun detach() {
        timerJob?.cancel()
        timerJob = null
        scope?.cancel()
        scope = null
        grid?.release()
        grid = null
        call = null
        setControlsEnabled(false)
        participantsList.removeAllViews()
        participantRows.clear()
        participantsPanel.visibility = GONE
        joinRequests = emptyList()
        joinRequestCard.visibility = GONE
        hideOverlay()
    }

    /** Leave immediately, skipping the confirmation dialog. */
    fun leaveNow() {
        detach()
        onLeave?.invoke()
    }

    /**
     * Leaving is confirmed on the SDK's own sheet, not a platform `AlertDialog`: the
     * dialog inherits the *host* app's theme, so the same meeting screen ended up with a
     * different-looking, usually light, box depending on who embedded the SDK.
     */
    private fun confirmLeave() {
        MeshBottomSheet(context).apply {
            addHeadline(
                iconRes = R.drawable.meshcall_ic_call_end,
                titleRes = R.string.meshcall_leave_title,
                messageRes = R.string.meshcall_leave_message,
            )
            addAction(R.string.meshcall_leave_confirm, danger = true) { leaveNow() }
            addAction(R.string.meshcall_cancel) {}
        }.show()
    }

    // ---- Chrome -----------------------------------------------------------------

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(
            micButton,
            cameraButton,
            switchCameraButton,
            participantsButton,
            audioRouteButton,
        ).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
    }

    /**
     * The button shows the *live* route, so the control bar answers "where is the sound
     * going" without opening anything. It is highlighted whenever audio is anywhere other
     * than the earpiece — that is the state worth noticing before you start talking.
     */
    private fun applyAudioRouteButton(state: AudioRouteState) {
        audioRouteButton.setImageResource(state.selected.iconRes)
        audioRouteButton.setBackgroundResource(
            if (state.selected == AudioRoute.EARPIECE) R.drawable.meshcall_bg_control
            else R.drawable.meshcall_bg_control_active,
        )
        audioRouteButton.imageTintList = ColorStateList.valueOf(color(R.color.meshcall_white))
        audioRouteButton.contentDescription = context.getString(
            R.string.meshcall_desc_audio_route_current,
            context.getString(state.selected.labelRes),
        )
    }

    /**
     * Open the output picker. A single route means there is nothing to choose between, so
     * the sheet is skipped rather than shown with one disabled row.
     */
    private fun showAudioRoutePicker() {
        val call = call ?: return
        val state = audioRouteState
        if (state.available.size <= 1) return

        MeshBottomSheet(context).apply {
            addTitle(R.string.meshcall_audio_output_title)
            state.available.forEach { route ->
                addRow(
                    iconRes = route.iconRes,
                    labelRes = route.labelRes,
                    selected = route == state.selected,
                ) { call.selectAudioRoute(route) }
            }
        }.show()
    }

    /**
     * The secondary-actions sheet behind "more". Entries appear only when they are actually
     * usable — screen share needs a host that can run the MediaProjection consent flow — so
     * the sheet never offers something that does nothing when tapped.
     */
    private fun showMoreSheet() {
        val call = call
        val share = onShareScreen
        // Stopping needs no host involvement, so an active share is always offered an exit
        // even when the host never supplied a way to start one.
        if (share == null && !screenSharing) {
            Toast.makeText(context, R.string.meshcall_more_empty, Toast.LENGTH_SHORT).show()
            return
        }

        MeshBottomSheet(context).apply {
            addTitle(R.string.meshcall_more_title)
            if (screenSharing) {
                addRow(
                    iconRes = R.drawable.meshcall_ic_present_to_all,
                    labelRes = R.string.meshcall_stop_sharing,
                    selected = true,
                ) { call?.stopScreenShare() }
            } else if (share != null) {
                addRow(
                    iconRes = R.drawable.meshcall_ic_present_to_all,
                    labelRes = R.string.meshcall_desc_share,
                    onClick = share,
                )
            }
        }.show()
    }

    private fun applyMicButton(micOn: Boolean) {
        micButton.setImageResource(
            if (micOn) R.drawable.meshcall_ic_mic else R.drawable.ic_mic_off,
        )
        micButton.setBackgroundResource(
            if (micOn) R.drawable.meshcall_bg_control else R.drawable.meshcall_bg_control_danger,
        )
        micButton.imageTintList = ColorStateList.valueOf(color(R.color.meshcall_white))
    }

    private fun applyCameraButton(cameraOn: Boolean) {
        cameraButton.setImageResource(
            if (cameraOn) R.drawable.meshcall_ic_videocam_on else R.drawable.ic_videocam_off,
        )
        cameraButton.setBackgroundResource(
            if (cameraOn) R.drawable.meshcall_bg_control else R.drawable.meshcall_bg_control_danger,
        )
        cameraButton.imageTintList = ColorStateList.valueOf(color(R.color.meshcall_white))
    }

    /**
     * The flip button reports the lens that is *currently* live rather than the one a tap
     * would switch to. A control that shows the state it is in matches the mic and camera
     * buttons beside it; showing the destination instead would make the bar contradict
     * itself the moment someone reads it as a status row.
     */
    private fun applySwitchCameraButton(frontActive: Boolean) {
        switchCameraButton.setImageResource(
            if (frontActive) R.drawable.meshcall_ic_camera_front
            else R.drawable.meshcall_ic_camera_rear,
        )
        switchCameraButton.contentDescription = context.getString(
            if (frontActive) R.string.meshcall_desc_switch_to_rear
            else R.string.meshcall_desc_switch_to_front,
        )
        switchCameraButton.imageTintList = ColorStateList.valueOf(color(R.color.meshcall_white))
    }

    /**
     * The full-screen state for someone who is not (or no longer) in the meeting.
     *
     * These outcomes are shown here rather than as a toast: a toast on top of a live
     * meeting grid says nothing about *why* the grid is empty, and it is gone by the time
     * anyone reads it. [terminal] states add the way out, because nothing further will
     * happen on this screen.
     */
    private fun showOverlay(titleRes: Int, messageRes: Int, terminal: Boolean) {
        waitingTitle.setText(titleRes)
        waitingMessage.setText(messageRes)
        overlayLeaveButton.visibility = if (terminal) VISIBLE else GONE
        waitingOverlay.visibility = VISIBLE
        // Nothing behind the overlay is actionable, and a pending knock cannot be
        // answered by someone who was just turned away themselves.
        if (terminal) {
            setControlsEnabled(false)
            joinRequestCard.visibility = GONE
        }
    }

    private fun hideOverlay() {
        waitingOverlay.visibility = GONE
        overlayLeaveButton.visibility = GONE
    }

    /**
     * The host's door.
     *
     * One card at a time, always the oldest request, with the rest counted underneath —
     * a stack of cards over the video would bury the meeting the host is still in, and
     * queued knocks are answered in the order people arrived anyway.
     */
    private fun renderJoinRequests(requests: List<JoinRequest>) {
        joinRequests = requests
        val next = requests.firstOrNull()
        if (next == null) {
            joinRequestCard.visibility = GONE
            return
        }

        requestAvatar.text = initialOf(next.userName)
        requestName.text = next.userName
        val waiting = requests.size - 1
        requestMore.visibility = if (waiting > 0) VISIBLE else GONE
        if (waiting > 0) {
            requestMore.text = context.getString(R.string.meshcall_more_waiting, waiting)
        }
        joinRequestCard.visibility = VISIBLE
    }

    private fun answerFirstRequest(admit: Boolean) {
        val request = joinRequests.firstOrNull() ?: return
        val call = call ?: return
        if (admit) call.admitParticipant(request.userId) else call.declineParticipant(request.userId)
    }

    /**
     * Rebuild the participants panel.
     *
     * Rows are rebuilt whenever the roster identity changes and only re-labelled
     * otherwise. The previous implementation compared child counts alone, so two peers
     * swapping position left every row showing the wrong name.
     */
    private fun renderParticipants(roster: List<MeshParticipant>) {
        participantsTitle.text =
            context.getString(R.string.meshcall_participants_title, roster.size)

        if (participantRows.size != roster.size) {
            participantsList.removeAllViews()
            participantRows.clear()
            repeat(roster.size) { index ->
                val row = buildParticipantRow(addTopSpacing = index > 0)
                participantsList.addView(row.root)
                participantRows.add(row)
            }
        }
        roster.forEachIndexed { index, participant ->
            val row = participantRows[index]
            val photo = AvatarBitmaps.decodeCircular(participant.avatarBase64)
            if (photo != null) {
                row.avatarImage.setImageBitmap(photo)
                row.avatarImage.visibility = View.VISIBLE
                row.avatarLabel.visibility = View.GONE
            } else {
                row.avatarLabel.text = initialOf(participant.userName)
                row.avatarLabel.visibility = View.VISIBLE
                row.avatarImage.visibility = View.GONE
            }
            row.nameLabel.text = participant.userName
            applyRowMicButton(row.micButton, participant)
            applyRowCameraIcon(row.cameraIcon, participant)
        }
    }

    /**
     * One row: avatar (photo if the participant chose one, else their initial), name, a
     * mic icon that mutes that participant on tap, and a camera icon that mirrors their
     * on/off state (informational only — this SDK has no way to force someone's camera off).
     *
     * [avatarLabel] and [avatarImage] occupy the same slot; [renderParticipants] toggles
     * which is visible per participant rather than rebuilding the row, since rows are
     * reused positionally as the roster reorders.
     */
    private class ParticipantRow(
        val root: LinearLayout,
        val avatarLabel: TextView,
        val avatarImage: ImageView,
        val nameLabel: TextView,
        val micButton: ImageButton,
        val cameraIcon: ImageView,
    )

    private fun buildParticipantRow(addTopSpacing: Boolean): ParticipantRow {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = if (addTopSpacing) dp(10) else 0 }
            setPadding(0, dp(4), 0, dp(4))
        }

        val avatarSize = dp(32)
        val avatarLabel = TextView(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.meshcall_bg_avatar)
            gravity = Gravity.CENTER
            setTextColor(color(R.color.meshcall_white))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        val avatarImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val avatarWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
            addView(avatarLabel, FrameLayout.LayoutParams(avatarSize, avatarSize))
            addView(avatarImage, FrameLayout.LayoutParams(avatarSize, avatarSize))
        }

        val nameLabel = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(10); marginEnd = dp(8) }
            setTextColor(color(R.color.meshcall_on_chrome))
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val micButtonSize = dp(30)
        val micButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(micButtonSize, micButtonSize)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            imageTintList = ColorStateList.valueOf(color(R.color.meshcall_white))
        }

        val cameraIconSize = dp(30)
        val cameraIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(cameraIconSize, cameraIconSize).apply {
                marginStart = dp(4)
            }
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            imageTintList = ColorStateList.valueOf(color(R.color.meshcall_white))
        }

        root.addView(avatarWrap)
        root.addView(nameLabel)
        root.addView(micButton)
        root.addView(cameraIcon)
        return ParticipantRow(root, avatarLabel, avatarImage, nameLabel, micButton, cameraIcon)
    }

    /**
     * Mic icon mirrors the local mic button's on/off styling for visual consistency.
     * Unmuted: tap sends a mute request. Already muted: disabled — this SDK has no way to
     * force someone to unmute, only to mute them.
     */
    private fun applyRowMicButton(button: ImageButton, participant: MeshParticipant) {
        val micOn = participant.micEnabled
        button.setImageResource(
            if (micOn) R.drawable.meshcall_ic_mic else R.drawable.ic_mic_off,
        )
        button.setBackgroundResource(
            if (micOn) R.drawable.meshcall_bg_control else R.drawable.meshcall_bg_control_danger,
        )
        button.isEnabled = micOn
        button.alpha = if (micOn) 1f else 0.5f
        button.contentDescription = if (micOn) {
            context.getString(R.string.meshcall_desc_mute_participant, participant.userName)
        } else {
            context.getString(R.string.meshcall_desc_participant_muted, participant.userName)
        }
        button.setOnClickListener {
            if (participant.micEnabled) call?.requestMute(participant.id)
        }
    }

    /**
     * Camera icon mirrors that participant's on/off state. Purely informational — unlike
     * the mic icon, there is no remote "force camera off" action to wire a tap to.
     */
    private fun applyRowCameraIcon(icon: ImageView, participant: MeshParticipant) {
        val cameraOn = participant.cameraEnabled
        icon.setImageResource(
            if (cameraOn) R.drawable.meshcall_ic_videocam_on else R.drawable.ic_videocam_off,
        )
        icon.alpha = if (cameraOn) 1f else 0.5f
        icon.contentDescription = if (cameraOn) {
            context.getString(R.string.meshcall_desc_participant_camera_on, participant.userName)
        } else {
            context.getString(R.string.meshcall_desc_participant_camera_off, participant.userName)
        }
    }

    private fun initialOf(name: String) = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    private fun copyMeetingCode() {
        val code = meetingCodeLabel.text?.toString().orEmpty()
        if (code.isEmpty()) return
        context.getSystemService<ClipboardManager>()?.setPrimaryClip(
            ClipData.newPlainText(
                context.getString(R.string.meshcall_meeting_code_clip_label),
                code,
            ),
        )
        Toast.makeText(context, R.string.meshcall_meeting_code_copied, Toast.LENGTH_SHORT).show()
    }

    // ---- Timer -------------------------------------------------------------------

    private fun startTimer() {
        meetingStartedAt = SystemClock.elapsedRealtime()
        timerJob?.cancel()
        timerJob = scope?.launch {
            timerLabel.visibility = VISIBLE
            liveDot.visibility = VISIBLE
            while (true) {
                timerLabel.text = formatElapsed(SystemClock.elapsedRealtime() - meetingStartedAt)
                delay(TIMER_TICK_MS)
            }
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Stop the ticking clock if the host forgot to detach; media teardown stays the
        // host's responsibility because a meeting may legitimately outlive one screen.
        timerJob?.cancel()
        timerJob = null
    }

    private companion object {
        const val TAG = "MeetingView"
        const val TIMER_TICK_MS = 500L

        /**
         * Icon per route. Wired headphones and Bluetooth deliberately differ: "which
         * headset is this going to" is exactly the question the picker exists to answer.
         */
        val AudioRoute.iconRes: Int
            get() = when (this) {
                AudioRoute.EARPIECE -> R.drawable.meshcall_ic_route_earpiece
                AudioRoute.SPEAKER -> R.drawable.meshcall_ic_route_speaker
                AudioRoute.WIRED_HEADSET -> R.drawable.meshcall_ic_route_headset
                AudioRoute.BLUETOOTH -> R.drawable.meshcall_ic_route_bluetooth
            }

        val AudioRoute.labelRes: Int
            get() = when (this) {
                AudioRoute.EARPIECE -> R.string.meshcall_route_earpiece
                AudioRoute.SPEAKER -> R.string.meshcall_route_speaker
                AudioRoute.WIRED_HEADSET -> R.string.meshcall_route_wired
                AudioRoute.BLUETOOTH -> R.string.meshcall_route_bluetooth
            }

        fun formatElapsed(elapsedMs: Long): String {
            val totalSeconds = elapsedMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }
}
