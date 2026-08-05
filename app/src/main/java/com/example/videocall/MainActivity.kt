package com.example.videocall

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dev.meshcall.sdk.api.LocalIdentityProvider
import dev.meshcall.sdk.api.MeshCall
import dev.meshcall.sdk.api.MeshRoomPeer
import dev.meshcall.sdk.ui.MeshCallRoomView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * In-call host for the MeshCall SDK.
 *
 * Requests camera + microphone, joins a room (real broker or offline demo), binds the
 * [MeshCallRoomView], and owns the call chrome: call timer, signaling banner,
 * participants panel, and the mic / camera / switch-camera / end controls.
 *
 * Launch extras:
 *   -e broker ws://10.0.2.2:3000 -e room demo-room --es name "Alice"   (real broker)
 *   -e demo 1 -e peers 8                                                (offline demo)
 */
class MainActivity : AppCompatActivity() {

    private var call: MeshCall? = null
    private var roomView: MeshCallRoomView? = null
    private var timerJob: Job? = null
    private var callStartedAt = 0L

    // Mirrors of the last toggles so the FAB tint can flip (independent of broker).
    private var micOn = true
    private var camOn = true

    private val permissions =
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.entries.all { it.value }) {
                startCall()
            } else {
                Toast.makeText(this, "Camera and mic are required for the call.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<FloatingActionButton>(R.id.btn_mic).setOnClickListener {
            micOn = !micOn
            findViewById<FloatingActionButton>(R.id.btn_mic).backgroundTintList =
                if (micOn) null else android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
            call?.toggleMic()
        }
        findViewById<FloatingActionButton>(R.id.btn_camera).setOnClickListener {
            camOn = !camOn
            findViewById<FloatingActionButton>(R.id.btn_camera).backgroundTintList =
                if (camOn) null else android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
            call?.toggleCamera()
        }
        findViewById<FloatingActionButton>(R.id.btn_switch_cam).setOnClickListener { call?.switchCamera() }
        findViewById<FloatingActionButton>(R.id.btn_end).setOnClickListener {
            roomView?.release()
            call?.leave()
            finish()
        }
        findViewById<FloatingActionButton>(R.id.btn_participants).setOnClickListener {
            toggleParticipants()
        }
        findViewById<TextView>(R.id.txtRoomCode).setOnClickListener {
            copyRoomCode()
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCall() else permissionLauncher.launch(permissions)
    }

    private fun startCall() {
        val demo = intent.getBooleanExtra(EXTRA_DEMO, false)
        val broker = intent.getStringExtra(BROKER_EXTRA) ?: DEFAULT_BROKER
        val room = intent.getStringExtra(ROOM_EXTRA) ?: DEFAULT_ROOM
        val name = intent.getStringExtra(NAME_EXTRA) ?: DEFAULT_NAME
        val simulatedPeers = intent.getIntExtra(EXTRA_PEERS, DEFAULT_DEMO_PEERS)

        findViewById<TextView>(R.id.txtRoomCode).text = getString(R.string.room_code_label, room)

        // Identity: without a real auth backend the SDK takes an opaque user id.
        LocalIdentityProvider.userId = "$name-${System.currentTimeMillis()}"

        val container = findViewById<FrameLayout>(R.id.call_container)
        val view = MeshCallRoomView(this, container)
        roomView = view

        val mesh = MeshCall(applicationContext)
        call = mesh
        if (demo) {
            mesh.joinDemo(roomId = room, displayName = name, simulatedPeers = simulatedPeers)
        } else {
            mesh.join(brokerUrl = broker, roomId = room, displayName = name)
        }
        view.bind(mesh)

        lifecycleScope.launch {
            mesh.errors.collect { err ->
                Toast.makeText(this@MainActivity, "Call error: $err", Toast.LENGTH_SHORT).show()
            }
        }
        lifecycleScope.launch {
            mesh.connected.collect { isConnected ->
                findViewById<TextView>(R.id.banner_conn).visibility =
                    if (isConnected || demo) View.GONE else View.VISIBLE
            }
        }
        lifecycleScope.launch {
            mesh.peers.collect { renderParticipants(it) }
        }
        startTimer()
    }

    // ---- Timer ---------------------------------------------------------------

    private fun startTimer() {
        callStartedAt = SystemClock.elapsedRealtime()
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            val label = findViewById<TextView>(R.id.txtCallTimer)
            label.visibility = View.VISIBLE
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - callStartedAt
                label.text = formatTimer(elapsed)
                delay(500L)
            }
        }
    }

    private fun formatTimer(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    // ---- Participants --------------------------------------------------------

    private fun toggleParticipants() {
        val panel = findViewById<LinearLayout>(R.id.panel_participants)
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun renderParticipants(roster: List<MeshRoomPeer>) {
        val list = findViewById<LinearLayout>(R.id.list_participants)
        if (list.childCount != roster.size) {
            list.removeAllViews()
            roster.forEach { peer -> list.addView(participantRow(peer)) }
        }
        findViewById<TextView>(R.id.txtParticipantsTitle).text =
            getString(R.string.participants_title, roster.size)

        list.post {
            for (i in 0 until list.childCount) {
                val peer = roster.getOrNull(i) ?: continue
                val row = list.getChildAt(i) as TextView
                row.text = getString(
                    R.string.peer_state_mic_cam,
                    peer.userName,
                    if (peer.micEnabled) getString(R.string.state_on) else getString(R.string.state_off),
                    if (peer.cameraEnabled) getString(R.string.state_on) else getString(R.string.state_off),
                )
            }
        }
    }

    private fun participantRow(peer: MeshRoomPeer): TextView =
        TextView(this).apply {
            text = peer.userName
            setTextColor(0xFFE8EDF8.toInt())
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
        }

    // ---- Helpers -------------------------------------------------------------

    private fun copyRoomCode() {
        val room = intent.getStringExtra(ROOM_EXTRA) ?: DEFAULT_ROOM
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("videocall_room_id", room))
        Toast.makeText(this, R.string.room_copied, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        roomView?.release()
        call?.dispose()
        call = null
        roomView = null
    }

    private companion object {
        const val BROKER_EXTRA = "broker"
        const val ROOM_EXTRA = "room"
        const val NAME_EXTRA = "name"
        const val EXTRA_DEMO = "demo"
        const val EXTRA_PEERS = "peers"
        const val DEFAULT_BROKER = "ws://10.0.2.2:3000"
        const val DEFAULT_ROOM = "demo-room"
        const val DEFAULT_NAME = "Android"
        const val DEFAULT_DEMO_PEERS = 6
    }
}