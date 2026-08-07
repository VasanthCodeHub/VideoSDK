package dev.meshcall.sdk.internal.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Base64

/**
 * Decodes a [dev.meshcall.sdk.api.MeshParticipant.avatarBase64] thumbnail into a circular
 * bitmap ready to drop straight into an [android.widget.ImageView].
 *
 * Never throws: a blank, malformed, or corrupt payload — from a peer's decode step gone
 * wrong, or a broker that mangles the field — returns null so the caller falls back to the
 * initials placeholder instead of crashing the tile.
 */
internal object AvatarBitmaps {

    fun decodeCircular(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val source = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        } ?: return null

        val circle = toCircle(source)
        source.recycle()
        return circle
    }

    private fun toCircle(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (source.width - size) / 2f
        val top = (source.height - size) / 2f
        canvas.drawBitmap(source, -left, -top, paint)
        return output
    }
}
