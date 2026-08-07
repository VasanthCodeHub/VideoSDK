package com.example.videocall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.videocall.data.MeetingHistoryRepository
import com.example.videocall.data.UserPreferences
import com.example.videocall.databinding.ActivityMainBinding
import dev.meshcall.sdk.api.Admission
import dev.meshcall.sdk.api.IceServerConfig
import dev.meshcall.sdk.api.LocalIdentityProvider
import dev.meshcall.sdk.api.MeshCall
import dev.meshcall.sdk.api.MeshCallConfig
import kotlinx.coroutines.launch

/**
 * Host for the in-meeting screen.
 *
 * Everything the meeting *looks* like — video grid, meeting-code badge, timer, signaling
 * banner, participants panel, mic / camera / audio-output / more / leave controls —
 * belongs to the SDK's [MeshMeetingView]. This activity keeps only what genuinely belongs
 * to the host app: runtime permissions (including MediaProjection consent), identity, and
 * navigation.
 *
 * Launch extras:
 *   -e broker ws://10.0.2.2:3000 -e meeting ABC123 -e name Alice
 */
class MainActivity : AppCompatActivity() {

    private var call: MeshCall? = null
    private lateinit var binding: ActivityMainBinding
    private val historyRepository by lazy { MeetingHistoryRepository(applicationContext) }
    private val userPreferences by lazy { UserPreferences(applicationContext) }

    private var meetingCode: String = ""
    private var meetingTitle: String = ""
    private var meetingStartedAt: Long = 0L
    private var lastParticipantCount: Int = 0

    /** False while we were never admitted — a knock that was declined, or a dead code. */
    private var enteredMeeting: Boolean = false

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

    /**
     * MediaProjection consent. This stays in the host because only an Activity can ask for
     * it — the SDK takes the resulting Intent and does the rest. The token is single-use, so
     * the dialog appears on every share.
     */
    private val screenShareLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                call?.startScreenShare(data)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitTopSafeArea()

        binding.meetingView.onLeave = { saveMeetingHistory(); finish() }
        // Share stays a host callback because MediaProjection consent has to be requested
        // from an Activity; the SDK owns the button and the stop path.
        binding.meetingView.onShareScreen = {
            screenShareLauncher.launch(MeshCall.screenCaptureIntent(this))
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMeeting() else permissionLauncher.launch(permissions)
    }

    private fun startMeeting() {
        val broker = intent.getStringExtra(EXTRA_BROKER) ?: DEFAULT_BROKER
        val meetingId = intent.getStringExtra(EXTRA_MEETING) ?: DEFAULT_MEETING
        // The name the lobby stored; Build.MODEL only backstops an adb launch with no
        // -e name and no name saved yet.
        val name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }
            ?: userPreferences.displayName.ifBlank { Build.MODEL }

        meetingCode = meetingId
        meetingTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        meetingStartedAt = System.currentTimeMillis()

        // Identity: without an auth backend the SDK just needs an opaque, stable id.
        // It also decides who offers, so it must be unique per participant.
        LocalIdentityProvider.userId = "$name-${System.currentTimeMillis()}"

        val mesh = MeshCall(applicationContext)
        call = mesh
        // Only the participant who started the meeting may bring it into existence; the
        // lobby has already checked that everyone else's code is live.
        mesh.join(
            brokerUrl = broker,
            meetingId = meetingId,
            displayName = name,
            config = MeshCallConfig(iceServers = ICE_SERVERS),
            createIfMissing = intent.getBooleanExtra(EXTRA_CREATE, false),
            isPrivate = intent.getBooleanExtra(EXTRA_PRIVATE, false),
            avatarBase64 = userPreferences.avatarBase64(),
        )
        binding.meetingView.attach(mesh, meetingId, showConnectionBanner = true)

        lifecycleScope.launch {
            mesh.participants.collect { peers -> lastParticipantCount = peers.size }
        }
        // Refused codes and declined requests are explained by the SDK's own full-screen
        // state, so there is nothing to say here — the host app only has to know whether
        // this was ever a real meeting, because one we never got into does not belong in
        // Recent Meetings.
        lifecycleScope.launch {
            mesh.admission.collect { admission ->
                if (admission == Admission.ADMITTED) enteredMeeting = true
            }
        }
    }

    private fun saveMeetingHistory() {
        if (meetingCode.isEmpty() || !enteredMeeting) return
        lifecycleScope.launch {
            historyRepository.recordMeeting(
                code = meetingCode,
                title = meetingTitle,
                startedAt = meetingStartedAt,
                // participants excludes self; +1 to count the local participant too.
                participantCount = lastParticipantCount + 1,
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

    override fun onDestroy() {
        super.onDestroy()
        binding.meetingView.detach()
        call?.dispose()
        call = null
    }

    private companion object {
        const val EXTRA_BROKER = "broker"
        const val EXTRA_MEETING = "meeting"
        const val EXTRA_TITLE = "title"
        const val EXTRA_NAME = "name"
        const val EXTRA_CREATE = "create"
        const val EXTRA_PRIVATE = "private"
        const val DEFAULT_BROKER = "wss://district-body-stumbling.ngrok-free.dev"
        const val DEFAULT_MEETING = "ABC123"

        /**
         * STUN discovers each phone's public address; TURN relays media when no direct
         * path exists. Mobile carriers sit behind CGNAT, where STUN alone never completes
         * ICE — signaling succeeds and the video simply never arrives.
         *
         * These are Open Relay's public dev credentials, not secrets. They are rate
         * limited and occasionally down: swap in your own TURN before shipping.
         */
        val ICE_SERVERS = listOf(
            IceServerConfig("stun:stun.l.google.com:19302"),
            IceServerConfig("stun:stun1.l.google.com:19302"),
            IceServerConfig("turn:openrelay.metered.ca:80", "openrelayproject", "openrelayproject"),
            IceServerConfig("turn:openrelay.metered.ca:443", "openrelayproject", "openrelayproject"),
            IceServerConfig(
                "turn:openrelay.metered.ca:443?transport=tcp",
                "openrelayproject",
                "openrelayproject",
            ),
        )
    }
}
