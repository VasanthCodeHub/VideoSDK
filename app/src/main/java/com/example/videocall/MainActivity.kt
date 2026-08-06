package com.example.videocall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.meshcall.sdk.api.LocalIdentityProvider
import dev.meshcall.sdk.api.MeshCall
import dev.meshcall.sdk.api.MeshCallConfig
import dev.meshcall.sdk.ui.MeshMeetingView

/**
 * Host for the in-meeting screen.
 *
 * Everything the meeting *looks* like — video grid, meeting-code badge, timer, signaling
 * banner, participants panel, mic / camera / switch-camera / share / more / leave controls
 * — belongs to the SDK's [MeshMeetingView]. This activity keeps only what genuinely
 * belongs to the host app: runtime permissions, identity, and navigation.
 *
 * Launch extras:
 *   -e broker ws://10.0.2.2:3000 -e meeting ABC123 -e name Alice   (real broker)
 *   --ez demo true --ei peers 8                                    (offline demo)
 */
class MainActivity : AppCompatActivity() {

    private var call: MeshCall? = null
    private lateinit var meetingView: MeshMeetingView

    private val permissions =
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                startMeeting()
            } else {
                Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fitTopSafeArea()

        meetingView = findViewById(R.id.meeting_view)
        meetingView.onLeave = { finish() }
        meetingView.onShareScreen = {
            Toast.makeText(this, R.string.share_coming_soon, Toast.LENGTH_SHORT).show()
        }
        meetingView.onMoreOptions = {
            Toast.makeText(this, R.string.more_coming_soon, Toast.LENGTH_SHORT).show()
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMeeting() else permissionLauncher.launch(permissions)
    }

    private fun startMeeting() {
        val demo = intent.getBooleanExtra(EXTRA_DEMO, false)
        val broker = intent.getStringExtra(EXTRA_BROKER) ?: DEFAULT_BROKER
        val meetingId = intent.getStringExtra(EXTRA_MEETING) ?: DEFAULT_MEETING
        val name = intent.getStringExtra(EXTRA_NAME) ?: Build.MODEL
        val simulatedPeers = intent.getIntExtra(EXTRA_PEERS, DEFAULT_DEMO_PEERS)

        // Identity: without an auth backend the SDK just needs an opaque, stable id.
        // It also decides who offers, so it must be unique per participant.
        LocalIdentityProvider.userId = "$name-${System.currentTimeMillis()}"

        val mesh = MeshCall(applicationContext)
        call = mesh
        if (demo) {
            mesh.joinDemo(meetingId, name, simulatedPeers)
        } else {
            mesh.join(broker, meetingId, name, MeshCallConfig())
        }
        meetingView.attach(mesh, meetingId, showConnectionBanner = !demo)
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

    override fun onDestroy() {
        super.onDestroy()
        meetingView.detach()
        call?.dispose()
        call = null
    }

    private companion object {
        const val EXTRA_BROKER = "broker"
        const val EXTRA_MEETING = "meeting"
        const val EXTRA_NAME = "name"
        const val EXTRA_DEMO = "demo"
        const val EXTRA_PEERS = "peers"
        const val DEFAULT_BROKER = "ws://10.0.2.2:3000"
        const val DEFAULT_MEETING = "demo-meeting"
        const val DEFAULT_DEMO_PEERS = 6
    }
}
