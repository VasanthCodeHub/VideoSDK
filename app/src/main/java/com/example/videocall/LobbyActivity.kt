package com.example.videocall

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.security.SecureRandom

/**
 * Lobby: create a new meeting (generates a shareable code) or join an existing one.
 *
 * This is the app's job — the meeting itself is entirely the SDK's (see [MainActivity]
 * and `MeshMeetingView`). Both paths hand the same extras to [MainActivity].
 */
class LobbyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lobby)
        fitTopSafeArea()

        findViewById<MaterialButton>(R.id.btn_create_meeting).setOnClickListener {
            showMeetingCodeDialog(generateMeetingCode())
        }
        findViewById<MaterialButton>(R.id.btn_join_meeting).setOnClickListener {
            showJoinDialog()
        }

        findViewById<TextView>(R.id.btn_rejoin_1).setOnClickListener {
            startMeeting(MEETING_1_CODE, DEFAULT_BROKER)
        }
        findViewById<TextView>(R.id.btn_rejoin_2).setOnClickListener {
            startMeeting(MEETING_2_CODE, DEFAULT_BROKER)
        }
        findViewById<TextView>(R.id.btn_view_all).setOnClickListener {
            Toast.makeText(this, R.string.enter_meeting_code, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_account).setOnClickListener {
            Toast.makeText(this, R.string.lobby_subtitle, Toast.LENGTH_SHORT).show()
        }
    }

    private fun fitTopSafeArea() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun showJoinDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_join_meeting, null)
        val codeField = view.findViewById<EditText>(R.id.et_meeting_code)
        val brokerField = view.findViewById<EditText>(R.id.et_broker)
        brokerField.setText(DEFAULT_BROKER)

        val dialog = AlertDialog.Builder(this).setView(view).create()

        view.findViewById<MaterialButton>(R.id.btn_join).setOnClickListener {
            val code = codeField.text.toString().trim().uppercase()
            if (code.isEmpty()) {
                Toast.makeText(this, R.string.enter_meeting_code, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            startMeeting(
                meetingCode = code,
                broker = brokerField.text.toString().trim(),
                demo = view.findViewById<MaterialSwitch>(R.id.sw_demo).isChecked,
            )
        }
        dialog.show()
    }

    private fun showMeetingCodeDialog(meetingCode: String) {
        val view = layoutInflater.inflate(R.layout.dialog_meeting_code, null)
        view.findViewById<TextView>(R.id.meeting_code).text = meetingCode

        val dialog = AlertDialog.Builder(this).setView(view).create()

        view.findViewById<View>(R.id.btn_copy).setOnClickListener {
            copyToClipboard(meetingCode)
            dialog.dismiss()
            Toast.makeText(this, R.string.meeting_code_copied, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btn_share).setOnClickListener {
            dialog.dismiss()
            shareMeetingCode(meetingCode)
        }
        view.findViewById<View>(R.id.btn_start).setOnClickListener {
            dialog.dismiss()
            startMeeting(
                meetingCode = meetingCode,
                broker = DEFAULT_BROKER,
                demo = view.findViewById<MaterialSwitch>(R.id.sw_demo).isChecked,
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

    private fun startMeeting(meetingCode: String, broker: String, demo: Boolean = false) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_BROKER, broker.ifEmpty { DEFAULT_BROKER })
                .putExtra(EXTRA_MEETING, meetingCode)
                .putExtra(EXTRA_NAME, Build.MODEL)
                .putExtra(EXTRA_DEMO, demo)
                .putExtra(EXTRA_PEERS, DEFAULT_DEMO_PEERS),
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
        const val EXTRA_NAME = "name"
        const val EXTRA_DEMO = "demo"
        const val EXTRA_PEERS = "peers"

        const val DEFAULT_BROKER = "ws://10.0.2.2:3000"
        const val DEFAULT_DEMO_PEERS = 6
        const val MEETING_CODE_LENGTH = 6
        const val MEETING_CLIP_LABEL = "meeting_code"
        const val MEETING_1_CODE = "842194"
        const val MEETING_2_CODE = "119450"
        const val MEETING_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val secureRandom = SecureRandom()
    }
}
