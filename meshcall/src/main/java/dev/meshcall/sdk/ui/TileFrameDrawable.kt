package dev.meshcall.sdk.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The visible frame of a participant tile, drawn as the topmost child so it lands in the
 * window plane — above the video.
 *
 * A tile's video comes from a [MeshVideoRenderer], a `SurfaceView` living in the *underlay*
 * plane, and an underlay surface cannot be clipped: `clipToOutline` does nothing to it, and
 * an opaque background on the renderer covers the video instead of shaping it (README §7
 * rule 6). So the square corners of the video always overhang `bg_tile_frame`'s rounded
 * ones. The only place left to fix that is on top, which is what this does:
 *
 *  1. fills the four corner slivers — everything inside the tile bounds but outside the
 *     rounded rect — with the same color as `bg_tile_frame`, hiding the video's corners;
 *  2. strokes the rounded rect itself, so the border reads crisp over video.
 *
 * The corner fill is why this is a [Drawable] rather than an XML `<shape>`: a shape can
 * only paint *inside* its corners, never outside them.
 */
internal class TileFrameDrawable(
    fillColor: Int,
    strokeColor: Int,
    private val strokeWidth: Float,
    private val cornerRadius: Float,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        strokeWidth = this@TileFrameDrawable.strokeWidth
    }

    /** Tile bounds minus the rounded rect, i.e. the four corner slivers. */
    private val cornerPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val strokeRect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        val outer = RectF(bounds)
        cornerPath.reset()
        cornerPath.addRect(outer, Path.Direction.CW)
        cornerPath.addRoundRect(outer, cornerRadius, cornerRadius, Path.Direction.CW)

        // A stroke straddles its path, so inset by half its width to keep it fully inside
        // the tile; shrink the radius to match or the stroke drifts off the fill's curve.
        val half = strokeWidth / 2f
        strokeRect.set(outer)
        strokeRect.inset(half, half)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(cornerPath, fillPaint)
        val half = strokeWidth / 2f
        canvas.drawRoundRect(strokeRect, cornerRadius - half, cornerRadius - half, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    // Deprecated upstream, still abstract on Drawable — it has to be implemented.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
