package com.example.videocall

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.videocall.data.MeetingHistoryRepository
import com.example.videocall.data.UserPreferences
import com.example.videocall.databinding.ActivityLobbyBinding
import com.example.videocall.databinding.DialogCreateMeetingBinding
import com.example.videocall.databinding.DialogJoinMeetingBinding
import com.example.videocall.databinding.DialogMeetingCodeBinding
import com.google.android.material.button.MaterialButton
import dev.meshcall.sdk.api.MeshMeetingDirectory
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Lobby: create a new meeting (generates a shareable code) or join an existing one.
 *
 * This is the app's job — the meeting itself is entirely the SDK's (see [MainActivity]
 * and `MeshMeetingView`). Both paths hand the same extras to [MainActivity].
 */
class LobbyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLobbyBinding
    private val repository by lazy { MeetingHistoryRepository(applicationContext) }
    private val userPreferences by lazy { UserPreferences(applicationContext) }

    /** Rejoin is only offered for meetings the broker says are still live — see [loadRecentMeetings]. */
    private val adapter = RecentMeetingAdapter { meeting ->
        startMeeting(meetingCode = meeting.code, title = meeting.title)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLobbyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitTopSafeArea()

        binding.btnCreateMeeting.setOnClickListener { showCreateMeetingDialog() }
        binding.btnJoinMeeting.setOnClickListener { showJoinDialog() }
        binding.btnProfile.setOnClickListener {
            startActivity(
                Intent(this, NameEntryActivity::class.java)
                    .putExtra(NameEntryActivity.EXTRA_EDIT, true),
            )
        }
        binding.recentMeetingsList.layoutManager = LinearLayoutManager(this)
        binding.recentMeetingsList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // Re-read on resume: the name screen may have just changed it.
        binding.profileName.text = userPreferences.displayName
        loadRecentMeetings()
    }

    /**
     * Load history, then ask the broker which of those codes still has somebody in it.
     *
     * The list renders immediately and grows its Rejoin buttons a moment later, rather
     * than waiting on the network: history is local and always correct, liveness is not.
     * Anything unproven — including an unreachable broker — stays hidden, so Rejoin never
     * appears on a meeting that would refuse the join.
     */
    private fun loadRecentMeetings() {
        lifecycleScope.launch {
            val meetings = repository.recentMeetings()
            adapter.submitList(meetings)
            binding.emptyRecentMeetings.visibility =
                if (meetings.isEmpty()) View.VISIBLE else View.GONE
            binding.recentMeetingsList.visibility =
                if (meetings.isEmpty()) View.GONE else View.VISIBLE
            if (meetings.isEmpty()) return@launch

            val statuses = MeshMeetingDirectory.status(DEFAULT_BROKER, meetings.map { it.code })
            adapter.setLiveMeetings(
                statuses.orEmpty().filter { it.isLive }.map { it.meetingId }.toSet(),
            )
        }
    }

    private fun fitTopSafeArea() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun showCreateMeetingDialog() {
        val dialogBinding = DialogCreateMeetingBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()

        dialogBinding.btnCreate.setOnClickListener {
            val title = dialogBinding.etMeetingTitle.text.toString().trim()
            dialog.dismiss()
            showMeetingCodeDialog(generateMeetingCode(), title)
        }
        dialog.show()
    }

    private fun showJoinDialog() {
        val dialogBinding = DialogJoinMeetingBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()

        dialogBinding.btnJoin.setOnClickListener {
            val code = dialogBinding.etMeetingCode.text.toString().trim().uppercase()
            if (code.isEmpty()) {
                Toast.makeText(this, R.string.enter_meeting_code, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            verifyThenJoin(code, dialog, dialogBinding.btnJoin)
        }
        dialog.show()
    }

    /**
     * Check the code with the broker before opening the meeting screen.
     *
     * Without this a wrong code used to *create* a meeting of one: the joiner sat alone
     * in a working call, with nothing to say they had mistyped. The dialog stays open
     * while the check runs so the code is still there to correct.
     *
     * "Not live" and "couldn't ask" are different failures and get different messages —
     * telling someone their code is wrong when the server is down sends them hunting for
     * a problem that isn't theirs.
     */
    private fun verifyThenJoin(code: String, dialog: AlertDialog, joinButton: MaterialButton) {
        joinButton.isEnabled = false
        joinButton.setText(R.string.checking_meeting)
        lifecycleScope.launch {
            val live = MeshMeetingDirectory.isLive(DEFAULT_BROKER, code)
            joinButton.isEnabled = true
            joinButton.setText(R.string.join_meeting)
            when (live) {
                true -> {
                    dialog.dismiss()
                    startMeeting(meetingCode = code)
                }
                false ->
                    Toast.makeText(this@LobbyActivity, R.string.meeting_not_found, Toast.LENGTH_LONG)
                        .show()
                null ->
                    Toast.makeText(this@LobbyActivity, R.string.broker_unreachable, Toast.LENGTH_LONG)
                        .show()
            }
        }
    }

    private fun showMeetingCodeDialog(meetingCode: String, title: String) {
        val dialogBinding = DialogMeetingCodeBinding.inflate(layoutInflater)
        dialogBinding.meetingCode.text = meetingCode

        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        // Copying and sharing are things you often do both of, and neither ends this
        // step — only Start or the close button does. Outside taps are ignored too: the
        // code is gone for good once this closes, since nothing has been created yet.
        dialog.setCanceledOnTouchOutside(false)

        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnCopy.setOnClickListener {
            copyToClipboard(meetingCode)
            Toast.makeText(this, R.string.meeting_code_copied, Toast.LENGTH_SHORT).show()
        }
        dialogBinding.btnShare.setOnClickListener { shareMeetingCode(meetingCode) }
        dialogBinding.btnStart.setOnClickListener {
            dialog.dismiss()
            // The only path allowed to open a meeting: this code was generated here and
            // exists nowhere until somebody starts it.
            startMeeting(meetingCode = meetingCode, title = title, create = true)
        }
        dialog.show()
    }

    private fun copyToClipboard(meetingCode: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(MEETING_CLIP_LABEL, meetingCode))
    }

    private fun shareMeetingCode(meetingCode: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.meeting_share_message, meetingCode))
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_meeting_title)))
    }

    /**
     * The broker is infrastructure, not a user-facing choice, so it is never surfaced in
     * the create or join dialogs. Overriding it is still possible for local development
     * by launching [MainActivity] directly with `-e broker …` (see README).
     */
    private fun startMeeting(
        meetingCode: String,
        title: String = "",
        create: Boolean = false,
    ) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_BROKER, DEFAULT_BROKER)
                .putExtra(EXTRA_MEETING, meetingCode)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_NAME, userPreferences.displayName)
                .putExtra(EXTRA_CREATE, create),
        )
    }

    /**
     * Six characters from an alphabet with no visually ambiguous glyphs (no O/0, I/1),
     * so a code read aloud or off a screen is unambiguous. [SecureRandom] rather than
     * [kotlin.random.Random] because a guessable code is a way into someone's meeting.
     */
    private fun generateMeetingCode(): String =
        (1..MEETING_CODE_LENGTH).joinToString("") {
            MEETING_CODE_ALPHABET[secureRandom.nextInt(MEETING_CODE_ALPHABET.length)].toString()
        }

    private companion object {
        const val EXTRA_BROKER = "broker"
        const val EXTRA_MEETING = "meeting"
        const val EXTRA_TITLE = "title"
        const val EXTRA_NAME = "name"
        const val EXTRA_CREATE = "create"

        const val DEFAULT_BROKER = "wss://district-body-stumbling.ngrok-free.dev"
        const val MEETING_CODE_LENGTH = 6
        const val MEETING_CLIP_LABEL = "meeting_code"
        const val MEETING_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val secureRandom = SecureRandom()
    }
}
