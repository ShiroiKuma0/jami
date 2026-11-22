/*
 * 白い熊 fork (sk): the theme's switch — a round 2 dp outline with a solid dot inside it, right
 * and in the on colour when checked, left and in the darker, opaque off colour when not. Never
 * filled, never a white ring.
 *
 * Upstream's layouts and view bindings name three switch classes (the platform Switch,
 * SwitchCompat and Material's MaterialSwitch), and the bindings cast to them, so the widget is
 * kept and only its two drawables are replaced. The thumb is a box as tall as the track with the
 * dot drawn in its middle — a drawable of its own, not an InsetDrawable, whose insets SwitchCompat
 * would take for optical insets and shrink the track by.
 */
package cx.ring.sk

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.CompoundButton
import android.widget.Switch
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.materialswitch.MaterialSwitch

object SkSwitches {
    private const val TRACK_W_DP = 52f
    private const val TRACK_H_DP = 28f
    private const val STROKE_DP = 2f

    fun style(view: CompoundButton) {
        val d = view.resources.displayMetrics.density
        val w = Math.round(TRACK_W_DP * d)
        val h = Math.round(TRACK_H_DP * d)
        val on = skColor(SkRole.SWITCH_ON)
        val off = skColor(SkRole.SWITCH_OFF)
        val thumb = Part(on, off, h, h, 0f, true)
        val track = Part(on, off, w, h, STROKE_DP * d, false)
        when (view) {
            is MaterialSwitch -> {
                view.thumbIconDrawable = null
                view.trackDecorationDrawable = null
                view.thumbTintList = null
                view.trackTintList = null
                view.thumbDrawable = thumb
                view.trackDrawable = track
                view.switchMinWidth = w
            }
            is SwitchCompat -> {
                view.thumbTintList = null
                view.trackTintList = null
                view.thumbDrawable = thumb
                view.trackDrawable = track
                view.switchMinWidth = w
            }
            is Switch -> {
                view.thumbTintList = null
                view.trackTintList = null
                view.thumbDrawable = thumb
                view.trackDrawable = track
                view.switchMinWidth = w
            }
        }
    }

    /** The track outline, or the dot in its box. */
    private class Part(
        private val on: Int, private val off: Int,
        private val w: Int, private val h: Int,
        private val stroke: Float, private val dot: Boolean
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var checked = false
        private var enabled = true

        override fun draw(canvas: Canvas) {
            val b = bounds
            var color = if (checked) on else off
            if (!enabled) color = skDisabled(color)
            paint.color = color
            if (dot) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), b.height() * 0.23f, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                val i = stroke / 2f
                rect.set(b.left + i, b.top + i, b.right - i, b.bottom - i)
                canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
            }
        }

        override fun isStateful() = true

        override fun onStateChange(state: IntArray): Boolean {
            val c = state.contains(android.R.attr.state_checked)
            val e = state.contains(android.R.attr.state_enabled)
            if (c == checked && e == enabled) return false
            checked = c
            enabled = e
            invalidateSelf()
            return true
        }

        override fun getIntrinsicWidth() = w
        override fun getIntrinsicHeight() = h
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

/** The same switch as one stateful drawable — the button of a checkable menu row. */
class SkOutlinedSwitchDrawable(
    private val on: Int, private val off: Int,
    private val widthPx: Int, private val heightPx: Int, strokePx: Int
) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokePx.toFloat() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private var checked = false

    override fun draw(canvas: Canvas) {
        val b = bounds
        val color = if (checked) on else off
        val inset = stroke.strokeWidth / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        stroke.color = color
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, stroke)
        fill.color = color
        val cx = if (checked) b.right - b.height() / 2f else b.left + b.height() / 2f
        canvas.drawCircle(cx, b.exactCenterY(), b.height() * 0.23f, fill)
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_checked)
        if (now == checked) return false
        checked = now
        invalidateSelf()
        return true
    }
    override fun getIntrinsicWidth() = widthPx
    override fun getIntrinsicHeight() = heightPx
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
