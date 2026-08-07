package com.example.videocall

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.videocall.data.UserPreferences
import com.example.videocall.databinding.ActivityNameEntryBinding

/**
 * Asks once for the name and picture the other participants will see, and stores them in
 * [UserPreferences]. Deliberately not a login: there is no account, no password and no
 * backend — the name and picture are just labels attached to the mesh participant.
 *
 * This is the launcher activity. On a normal cold start with a name already saved it
 * hands straight over to [LobbyActivity] without inflating anything, so the form is only
 * ever seen on first run or when the lobby sends the user back with [EXTRA_EDIT].
 */
class NameEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNameEntryBinding
    private val userPreferences by lazy { UserPreferences(applicationContext) }
    private val editing: Boolean get() = intent.getBooleanExtra(EXTRA_EDIT, false)

    private val pickAvatar =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            Log.d(TAG, "picker returned uri=$uri")
            if (uri != null) onAvatarPicked(uri) else Log.w(TAG, "picker returned no uri — user backed out, or the contract failed silently")
        }

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

        renderAvatar()
        val launchPicker = {
            pickAvatar.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        binding.ivAvatar.setOnClickListener { launchPicker() }
        binding.avatarEditBadge.setOnClickListener { launchPicker() }
    }

    private fun onAvatarPicked(uri: Uri) {
        try {
            val bitmap = decodeSampledBitmap(uri, AVATAR_DECODE_TARGET_PX)
            if (bitmap == null) {
                Log.e(TAG, "decodeSampledBitmap returned null for $uri — nothing to save")
                return
            }
            Log.d(TAG, "decoded ${bitmap.width}x${bitmap.height}, saving to UserPreferences")
            userPreferences.saveAvatar(bitmap)
            Log.d(TAG, "saveAvatar done, hasAvatar=${userPreferences.hasAvatar}")
            renderAvatar()
        } catch (e: Exception) {
            // Whatever went wrong, surface it loudly instead of leaving the placeholder up
            // with no trace of why the pick silently did nothing.
            Log.e(TAG, "avatar pick pipeline failed for $uri", e)
        }
    }

    private fun renderAvatar() {
        val avatar = userPreferences.loadAvatarBitmap()
        Log.d(TAG, "renderAvatar: hasAvatar=${userPreferences.hasAvatar}, decoded=${avatar != null}")
        if (avatar != null) {
            // The XML applies a tint for the placeholder icon; left in place it would
            // wash out an actual photo in that same flat color.
            binding.ivAvatar.imageTintList = null
            binding.ivAvatar.setImageBitmap(avatar)
        } else {
            binding.ivAvatar.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.on_surface_variant))
            binding.ivAvatar.setImageResource(R.drawable.ic_account_circle)
        }
    }

    /**
     * Decodes [uri] downsampled to roughly [targetSize] on its longest side, so picking a
     * multi-megapixel gallery photo does not load it into memory at full resolution just
     * to crop it down to a small avatar. Corrects for EXIF rotation — many camera photos
     * are stored sideways/upside-down with the intended orientation only in metadata, and
     * [BitmapFactory] ignores it.
     */
    private fun decodeSampledBitmap(uri: Uri, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri)
        if (boundsStream == null) {
            Log.e(TAG, "openInputStream(bounds pass) returned null for $uri")
            return null
        }
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        Log.d(TAG, "bounds: ${bounds.outWidth}x${bounds.outHeight}, mimeType=${bounds.outMimeType}")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.e(TAG, "bounds decode failed for $uri (outWidth/outHeight <= 0)")
            return null
        }

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetSize &&
            bounds.outHeight / (sampleSize * 2) >= targetSize
        ) {
            sampleSize *= 2
        }
        Log.d(TAG, "sampleSize=$sampleSize for target=$targetSize")

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val pixelStream = contentResolver.openInputStream(uri)
        if (pixelStream == null) {
            Log.e(TAG, "openInputStream(pixel pass) returned null for $uri")
            return null
        }
        val decoded = pixelStream.use { BitmapFactory.decodeStream(it, null, opts) }
        if (decoded == null) {
            Log.e(TAG, "BitmapFactory.decodeStream returned null for $uri")
            return null
        }
        return applyExifRotation(uri, decoded)
    }

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            // Some providers (the photo picker's content:// URIs among them) hand back a
            // stream ExifInterface can't parse. A photo with unreadable orientation is
            // still a photo — this degrades to "no rotation" instead of losing the
            // picture entirely, which is what happened here before this was caught.
            Log.w(TAG, "EXIF read failed for $uri, skipping rotation", e)
            0
        }
        Log.d(TAG, "EXIF rotation=$degrees for $uri")
        if (degrees == 0) return bitmap

        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
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
        private const val TAG = "NameEntry"

        /** Set when the lobby opens this screen to change an already-saved name. */
        const val EXTRA_EDIT = "edit"

        /** Upper bound for the decoded bitmap before [UserPreferences.saveAvatar] crops it. */
        const val AVATAR_DECODE_TARGET_PX = 512
    }
}
