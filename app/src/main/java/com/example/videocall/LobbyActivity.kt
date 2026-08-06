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
import com.example.videocall.databinding.ActivityLobbyBinding
import com.example.videocall.databinding.DialogCreateMeetingBinding
import com.example.videocall.databinding.DialogJoinMeetingBinding
import com.example.videocall.databinding.DialogMeetingCodeBinding
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
    private val adapter = RecentMeetingAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLobbyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitTopSafeArea()

        binding.btnCreateMeeting.setOnClickListener { showCreateMeetingDialog() }
        binding.btnJoinMeeting.setOnClickListener { showJoinDialog() }
        binding.btnAccount.setOnClickListener {
            Toast.makeText(this, R.string.lobby_subtitle, Toast.LENGTH_SHORT).show()
        }

        binding.recentMeetingsList.layoutManager = LinearLayoutManager(this)
        binding.recentMeetingsList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadRecentMeetings()
    }

    private fun loadRecentMeetings() {
        lifecycleScope.launch {
            val meetings = repository.recentMeetings()
            adapter.submitList(meetings)
            binding.emptyRecentMeetings.visibility =
                if (meetings.isEmpty()) View.VISIBLE else View.GONE
            binding.recentMeetingsList.visibility =
                if (meetings.isEmpty()) View.GONE else View.VISIBLE
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
        dialogBinding.etBroker.setText(DEFAULT_BROKER)

        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()

        dialogBinding.btnJoin.setOnClickListener {
            val code = dialogBinding.etMeetingCode.text.toString().trim().uppercase()
            if (code.isEmpty()) {
                Toast.makeText(this, R.string.enter_meeting_code, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            startMeeting(
                meetingCode = code,
                broker = dialogBinding.etBroker.text.toString().trim(),
            )
        }
        dialog.show()
    }

    private fun showMeetingCodeDialog(meetingCode: String, title: String) {
        val dialogBinding = DialogMeetingCodeBinding.inflate(layoutInflater)
        dialogBinding.meetingCode.text = meetingCode

        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()

        dialogBinding.btnCopy.setOnClickListener {
            copyToClipboard(meetingCode)
            dialog.dismiss()
            Toast.makeText(this, R.string.meeting_code_copied, Toast.LENGTH_SHORT).show()
        }
        dialogBinding.btnShare.setOnClickListener {
            dialog.dismiss()
            shareMeetingCode(meetingCode)
        }
        dialogBinding.btnStart.setOnClickListener {
            dialog.dismiss()
            startMeeting(
                meetingCode = meetingCode,
                broker = DEFAULT_BROKER,
                title = title,
            )
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

    private fun startMeeting(
        meetingCode: String,
        broker: String,
        title: String = "",
    ) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_BROKER, broker.ifEmpty { DEFAULT_BROKER })
                .putExtra(EXTRA_MEETING, meetingCode)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_NAME, Build.MODEL),
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

        const val DEFAULT_BROKER = "ws://10.0.2.2:3000"
        const val MEETING_CODE_LENGTH = 6
        const val MEETING_CLIP_LABEL = "meeting_code"
        const val MEETING_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val secureRandom = SecureRandom()
    }
}
