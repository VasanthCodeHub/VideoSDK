package com.example.videocall.data

import android.content.Context
import androidx.core.content.edit

/**
 * The local display name, kept in [android.content.SharedPreferences].
 *
 * There is no account system here — the name is only what the other participants see in
 * the meeting, so a plain key/value store is enough and nothing needs to outlive an
 * uninstall.
 */
class UserPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Blank until the name screen has been through once. Always stored trimmed. */
    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_DISPLAY_NAME, value.trim()) }

    val hasDisplayName: Boolean get() = displayName.isNotBlank()

    private companion object {
        const val PREFS_NAME = "videocall_user"
        const val KEY_DISPLAY_NAME = "display_name"
    }
}
