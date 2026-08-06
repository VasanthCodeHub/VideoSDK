package dev.meshcall.sdk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dev.meshcall.sdk.R
import dev.meshcall.sdk.api.MeshCall
import dev.meshcall.sdk.api.MeshParticipant
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
 * signaling banner, participants panel, and the mic / camera / switch-camera / share /
 * more / leave controls are all handled here.
 *
 * The host keeps only what genuinely belongs to it: runtime permissions, navigation, and
 * deciding what "share screen" or "more" should do.
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
    private val shareButton: ImageButton
    private val moreButton: ImageButton
    private val leaveButton: ImageButton
    private val participantsButton: ImageButton
    private val switchCameraButton: ImageButton

    private var grid: MeshParticipantGrid? = null
    private var call: MeshCall? = null
    private var scope: CoroutineScope? = null
    private var timerJob: Job? = null
    private var meetingStartedAt = 0L

    /** Invoked after the user confirms leaving. Navigate away here. */
    var onLeave: (() -> Unit)? = null

    /** Invoked when "share screen" is tapped. Null hides the button. */
    var onShareScreen: (() -> Unit)? = null
        set(value) {
            field = value
            shareButton.visibility = if (value == null) GONE else VISIBLE
        }

    /** Invoked when "more" is tapped. Null hides the button. */
    var onMoreOptions: (() -> Unit)? = null
        set(value) {
            field = value
            moreButton.visibility = if (value == null) GONE else VISIBLE
        }

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
        shareButton = findViewById(R.id.meshcall_btn_share)
        moreButton = findViewById(R.id.meshcall_btn_more)
        leaveButton = findViewById(R.id.meshcall_btn_leave)
        participantsButton = findViewById(R.id.meshcall_btn_participants)
        switchCameraButton = findViewById(R.id.meshcall_btn_switch_camera)

        wireControls()
    }

    private fun wireControls() {
        micButton.setOnClickListener { call?.toggleMic() }
        cameraButton.setOnClickListener { call?.toggleCamera() }
        switchCameraButton.setOnClickListener { call?.switchCamera() }
        shareButton.setOnClickListener { onShareScreen?.invoke() }
        moreButton.setOnClickListener { onMoreOptions?.invoke() }
        participantsButton.setOnClickListener {
            participantsPanel.visibility =
                if (participantsPanel.visibility == VISIBLE) GONE else VISIBLE
        }
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
        participantsPanel.visibility = GONE
    }

    /** Leave immediately, skipping the confirmation dialog. */
    fun leaveNow() {
        detach()
        onLeave?.invoke()
    }

    private fun confirmLeave() {
        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.meshcall_leave_title)
            .setMessage(R.string.meshcall_leave_message)
            .setPositiveButton(R.string.meshcall_leave_confirm) { _, _ -> leaveNow() }
            .setNegativeButton(R.string.meshcall_cancel, null)
            .show()
    }

    // ---- Chrome -----------------------------------------------------------------

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(micButton, cameraButton, switchCameraButton, participantsButton).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
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
     * Rebuild the participants panel.
     *
     * Rows are rebuilt whenever the roster identity changes and only re-labelled
     * otherwise. The previous implementation compared child counts alone, so two peers
     * swapping position left every row showing the wrong name.
     */
    private fun renderParticipants(roster: List<MeshParticipant>) {
        participantsTitle.text =
            context.getString(R.string.meshcall_participants_title, roster.size)

        if (participantsList.childCount != roster.size) {
            participantsList.removeAllViews()
            repeat(roster.size) { participantsList.addView(buildParticipantRow()) }
        }
        roster.forEachIndexed { index, participant ->
            (participantsList.getChildAt(index) as? TextView)?.text = context.getString(
                R.string.meshcall_participant_state,
                participant.userName,
                stateLabel(participant.micEnabled),
                stateLabel(participant.cameraEnabled),
            )
        }
    }

    private fun buildParticipantRow() = TextView(context).apply {
        setTextColor(color(R.color.meshcall_on_chrome))
        textSize = 13f
        setPadding(0, 0, 0, dp(4))
    }

    private fun stateLabel(enabled: Boolean) = context.getString(
        if (enabled) R.string.meshcall_state_on else R.string.meshcall_state_off,
    )

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
