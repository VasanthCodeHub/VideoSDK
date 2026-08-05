package com.example.videocall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dev.meshcall.sdk.api.LocalIdentityProvider
import dev.meshcall.sdk.api.MeshCall
import dev.meshcall.sdk.ui.MeshCallRoomView
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/**
 * Minimal demo host for the MeshCall SDK.
 *
 * Requests camera + microphone at runtime, then joins a mesh room and binds the
 * [MeshCallRoomView] to the [FrameLayout] root (whose first child is the local
 * preview). All media/peer logic lives inside the SDK; this screen only forwards
 * controls and clears resources on exit.
 *
 * Launch with extras to point at your broker + room:
 *   adb shell am start \
 *     -n com.example.videocall/.MainActivity \
 *     -e broker ws://10.0.2.2:3000 \
 *     -e room demo-room \
 *     --es name "Alice"
 */
class MainActivity : AppCompatActivity() {

    private var call: MeshCall? = null
    private var roomView: MeshCallRoomView? = null

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

        findViewById<FloatingActionButton>(R.id.btn_mic).setOnClickListener { call?.toggleMic() }
        findViewById<FloatingActionButton>(R.id.btn_camera).setOnClickListener { call?.toggleCamera() }
        findViewById<FloatingActionButton>(R.id.btn_end).setOnClickListener {
            roomView?.release()
            call?.leave()
            finish()
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCall() else permissionLauncher.launch(permissions)
    }

    private fun startCall() {
        val broker = intent.getStringExtra(BROKER_EXTRA) ?: DEFAULT_BROKER
        val room = intent.getStringExtra(ROOM_EXTRA) ?: DEFAULT_ROOM
        val name = intent.getStringExtra(NAME_EXTRA) ?: DEFAULT_NAME

        // Identity: without a real auth backend the SDK takes an opaque user id.
        LocalIdentityProvider.userId = "$name-${System.currentTimeMillis()}"

        // Bind the view controller to the layout container.
        val container = findViewById<FrameLayout>(R.id.call_container)
        val view = MeshCallRoomView(this, container)
        roomView = view

        // Create + join, then bind the in-call view.
        val mesh = MeshCall(applicationContext)
        call = mesh
        mesh.join(brokerUrl = broker, roomId = room, displayName = name)
        view.bind(mesh)

        lifecycleScope.launch {
            mesh.errors.collect { err ->
                Toast.makeText(this@MainActivity, "Call error: $err", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        roomView?.release()
        call?.dispose()
        call = null
        roomView = null
    }

    private companion object {
        const val BROKER_EXTRA = "broker"
        const val ROOM_EXTRA = "room"
        const val NAME_EXTRA = "name"
        const val DEFAULT_BROKER = "ws://10.0.2.2:3000"
        const val DEFAULT_ROOM = "demo-room"
        const val DEFAULT_NAME = "Android"
    }
}
