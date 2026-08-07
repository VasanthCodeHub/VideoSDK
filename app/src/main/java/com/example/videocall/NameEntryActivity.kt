package com.example.videocall

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.videocall.data.UserPreferences
import com.example.videocall.databinding.ActivityNameEntryBinding

/**
 * Asks once for the name the other participants will see, and stores it in
 * [UserPreferences]. Deliberately not a login: there is no account, no password and no
 * backend — the name is just a label attached to the mesh participant.
 *
 * This is the launcher activity. On a normal cold start with a name already saved it
 * hands straight over to [LobbyActivity] without inflating anything, so the form is only
 * ever seen on first run or when the lobby sends the user back with [EXTRA_EDIT].
 */
class NameEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNameEntryBinding
    private val userPreferences by lazy { UserPreferences(applicationContext) }
    private val editing: Boolean get() = intent.getBooleanExtra(EXTRA_EDIT, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!editing && userPreferences.hasDisplayName) {
            openLobby()
            return
        }

        binding = ActivityNameEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitTopSafeArea()

        // Same screen, two moods: a welcome on first run, a plain edit afterwards.
        if (editing) {
            binding.nameTitle.setText(R.string.name_edit_title)
            binding.nameSubtitle.setText(R.string.name_edit_subtitle)
            binding.btnContinue.setText(R.string.name_edit_action)
        }

        binding.etDisplayName.setText(userPreferences.displayName)
        binding.etDisplayName.setSelection(binding.etDisplayName.text.length)
        binding.etDisplayName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveName()
                true
            } else {
                false
            }
        }
        binding.btnContinue.setOnClickListener { saveName() }
    }

    private fun saveName() {
        val name = binding.etDisplayName.text.toString().trim()
        // On the field itself rather than a toast: the problem is with what was typed,
        // and that is where the user is already looking.
        binding.nameInputLayout.error = null
        if (name.isEmpty()) {
            binding.nameInputLayout.error = getString(R.string.enter_your_name)
            return
        }
        userPreferences.displayName = name
        if (editing) finish() else openLobby()
    }

    private fun openLobby() {
        startActivity(Intent(this, LobbyActivity::class.java))
        finish()
    }

    private fun fitTopSafeArea() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    companion object {
        /** Set when the lobby opens this screen to change an already-saved name. */
        const val EXTRA_EDIT = "edit"
    }
}
