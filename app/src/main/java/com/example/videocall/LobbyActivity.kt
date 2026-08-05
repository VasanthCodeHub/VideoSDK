package com.example.videocall

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.security.SecureRandom

/**
 * Lobby: create a new room (generates a shareable Room ID) or join an existing
 * one by entering its Room ID. Either way the user lands on the in-call screen
 * with the same [EXTRA_BROKER] / [EXTRA_ROOM] configuration as everyone else.
 */
class LobbyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lobby)

        val btnCreate = findViewById<Button>(R.id.btn_create)
        val btnJoin = findViewById<Button>(R.id.btn_join)
        val etRoom = findViewById<EditText>(R.id.et_room)
        val etBroker = findViewById<EditText>(R.id.et_broker)
        val swDemo = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.sw_demo)

        etBroker.setText(DEFAULT_BROKER)

        btnCreate.setOnClickListener {
            val roomId = generateRoomId()
            showRoomDialog(roomId, etBroker.text.toString().trim(), swDemo.isChecked)
        }
        btnJoin.setOnClickListener {
            val roomId = etRoom.text.toString().trim().uppercase()
            if (roomId.isEmpty()) {
                Toast.makeText(this, R.string.enter_room_id, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startCall(roomId, etBroker.text.toString().trim(), swDemo.isChecked)
        }
    }

    private fun showRoomDialog(roomId: String, broker: String, demo: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_room_code, null)
        view.findViewById<TextView>(R.id.room_code).text = roomId

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        view.findViewById<View>(R.id.btn_copy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(ROOM_CLIP_LABEL, roomId))
            dialog.dismiss()
            Toast.makeText(this, R.string.room_copied, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btn_share).setOnClickListener {
            dialog.dismiss()
            shareRoomId(roomId)
        }
        view.findViewById<View>(R.id.btn_start).setOnClickListener {
            dialog.dismiss()
            startCall(roomId, broker, demo)
        }

        dialog.show()
    }

    private fun shareRoomId(roomId: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.room_share_message, roomId))
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_room_title)))
    }

    private fun startCall(roomId: String, broker: String, demo: Boolean) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_BROKER, broker.ifEmpty { DEFAULT_BROKER })
                .putExtra(EXTRA_ROOM, roomId)
                .putExtra(EXTRA_NAME, Build.MODEL)
                .putExtra(EXTRA_DEMO, demo)
                .putExtra(EXTRA_PEERS, DEFAULT_DEMO_PEERS),
        )
    }

    private fun generateRoomId(): String =
        (1..ROOM_ID_LENGTH).joinToString("") {
            ROOM_ID_ALPHABET[secureRandom.nextInt(ROOM_ID_ALPHABET.length)].toString()
        }

    private companion object {
        const val EXTRA_BROKER = "broker"
        const val EXTRA_ROOM = "room"
        const val EXTRA_NAME = "name"
        const val EXTRA_DEMO = "demo"
        const val EXTRA_PEERS = "peers"
        const val DEFAULT_BROKER = "ws://10.0.2.2:3000"
        const val DEFAULT_DEMO_PEERS = 6
        const val ROOM_ID_LENGTH = 6
        const val ROOM_CLIP_LABEL = "videocall_room_id"
        val ROOM_ID_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val secureRandom = SecureRandom()
    }
}
