package dev.meshcall.sdk.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.meshcall.sdk.R

/**
 * A bottom sheet built from platform views.
 *
 * The SDK takes no Material dependency (README §7 rule 7), so `BottomSheetDialog` is not
 * available — this is the same shape assembled from a plain [Dialog] pinned to the bottom
 * of the window. Used by the audio-output picker and the "more" menu, so both read as one
 * component rather than two lookalikes that drift.
 *
 * Build with [addRow] / [addTitle], then [show].
 */
internal class MeshBottomSheet(private val context: Context) {

    private val dialog = Dialog(context)

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.meshcall_bg_sheet)
        setPadding(dp(12), dp(10), dp(12), dp(20))
    }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        // The grabber is decoration, but without it a sheet that appears from nowhere reads
        // as a misplaced dialog rather than something dismissible by tapping away.
        content.addView(
            View(context).apply { setBackgroundResource(R.drawable.meshcall_bg_sheet_grabber) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            },
        )

        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
    }

    fun addTitle(textRes: Int) {
        content.addView(
            TextView(context).apply {
                setText(textRes)
                setTextColor(color(R.color.meshcall_on_chrome_dim))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setPadding(dp(12), dp(4), dp(12), dp(8))
            },
            rowParams(),
        )
    }

    /**
     * One tappable row: icon, label, and an optional trailing check.
     *
     * @param selected tints the row and shows the check — for rows that represent a current
     *   choice (which output is live), not for plain actions.
     */
    fun addRow(
        iconRes: Int,
        labelRes: Int,
        selected: Boolean = false,
        onClick: () -> Unit,
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(
                if (selected) R.drawable.meshcall_bg_sheet_row_selected
                else R.drawable.meshcall_bg_sheet_row,
            )
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener {
                onClick()
                dismiss()
            }
        }

        row.addView(
            ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(color(R.color.meshcall_white))
            },
            LinearLayout.LayoutParams(dp(22), dp(22)),
        )

        row.addView(
            TextView(context).apply {
                setText(labelRes)
                setTextColor(color(R.color.meshcall_white))
                textSize = 15f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
            },
        )

        if (selected) {
            row.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.meshcall_ic_check)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        color(R.color.meshcall_white),
                    )
                },
                LinearLayout.LayoutParams(dp(20), dp(20)),
            )
        }

        content.addView(row, rowParams())
    }

    fun show() = dialog.show()

    fun dismiss() = dialog.dismiss()

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()
}
