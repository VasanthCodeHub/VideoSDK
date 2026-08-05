package com.example.videocall

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
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

        etBroker.setText(DEFAULT_BROKER)

        btnCreate.setOnClickListener {
            startCall(generateRoomId(), etBroker.text.toString().trim())
        }
        btnJoin.setOnClickListener {
            val roomId = etRoom.text.toString().trim().uppercase()
            if (roomId.isEmpty()) {
                Toast.makeText(this, R.string.enter_room_id, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startCall(roomId, etBroker.text.toString().trim())
        }
    }

    private fun startCall(roomId: String, broker: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_BROKER, broker.ifEmpty { DEFAULT_BROKER })
                .putExtra(EXTRA_ROOM, roomId)
                .putExtra(EXTRA_NAME, Build.MODEL),
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
        const val DEFAULT_BROKER = "ws://10.0.2.2:3000"
        const val ROOM_ID_LENGTH = 6
        val ROOM_ID_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val secureRandom = SecureRandom()
    }
}
