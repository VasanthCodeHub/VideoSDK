package com.example.videocall.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream

/**
 * The local display name and profile picture, kept in [android.content.SharedPreferences]
 * and app-private storage respectively.
 *
 * There is no account system here — the name and picture are only what the other
 * participants see in the meeting, so a plain key/value store plus one image file is
 * enough and nothing needs to outlive an uninstall.
 */
class UserPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Blank until the name screen has been through once. Always stored trimmed. */
    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_DISPLAY_NAME, value.trim()) }

    val hasDisplayName: Boolean get() = displayName.isNotBlank()

    private val avatarFile: File get() = File(appContext.filesDir, AVATAR_FILE_NAME)

    val hasAvatar: Boolean get() = avatarFile.exists()

    /** Decoded for display in the app's own screens (name entry, lobby chip). */
    fun loadAvatarBitmap(): Bitmap? {
        val file = avatarFile
        if (!file.exists()) {
            Log.d(TAG, "loadAvatarBitmap: no avatar file at ${file.path}")
            return null
        }
        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap == null) {
            Log.e(TAG, "loadAvatarBitmap: decodeFile failed for ${file.path} (${file.length()} bytes)")
        }
        return bitmap
    }

    /**
     * Base64-encoded, ready to hand to [dev.meshcall.sdk.api.MeshCall.join] so the mesh
     * can broadcast it to other participants; null if none was ever chosen.
     *
     * Already downscaled at [saveAvatar] time, so this is cheap to call once per join.
     */
    fun avatarBase64(): String? =
        avatarFile.takeIf { it.exists() }
            ?.readBytes()
            ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    /** Center-crops [bitmap] to a square thumbnail and stores it as the profile picture. */
    fun saveAvatar(bitmap: Bitmap) {
        val side = minOf(bitmap.width, bitmap.height)
        val cropped = Bitmap.createBitmap(
            bitmap,
            (bitmap.width - side) / 2,
            (bitmap.height - side) / 2,
            side,
            side,
        )
        val square = if (side == AVATAR_SIZE_PX) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, AVATAR_SIZE_PX, AVATAR_SIZE_PX, true)
        }
        val ok = FileOutputStream(avatarFile).use { out ->
            square.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, out)
        }
        Log.d(
            TAG,
            "saveAvatar: wrote ${avatarFile.path} compress-ok=$ok size=${avatarFile.length()} bytes",
        )
    }

    fun clearAvatar() {
        avatarFile.delete()
    }

    private companion object {
        const val TAG = "UserPreferences"
        const val PREFS_NAME = "videocall_user"
        const val KEY_DISPLAY_NAME = "display_name"
        const val AVATAR_FILE_NAME = "avatar.jpg"

        /**
         * Small on purpose: this travels over the signaling socket to every other
         * participant on every join, base64-encoded inside a JSON message. 160px JPEG at
         * this quality lands around 5-10KB — plenty sharp for the tile/list sizes it's
         * shown at, negligible over the wire.
         */
        const val AVATAR_SIZE_PX = 160
        const val AVATAR_JPEG_QUALITY = 80
    }
}
