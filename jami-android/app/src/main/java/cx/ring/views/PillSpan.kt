package cx.ring.views

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import kotlin.math.ceil

/**
 * Inline rounded "pill" that renders a button label the way the Contact-live-monitor buttons look:
 * a rounded rect (fill + stroke) with the label inside. Used on the connectivity help page so a
 * mention of e.g. "Message ping" reads as the real control, not just quoted text. Colours are
 * supplied by the caller (settable via ColorPrefs INFO_PILL_* roles); the text uses the host
 * TextView's paint, so it inherits the help page's font and size.
 */
class PillSpan(
    private val textColor: Int,
    private val strokeColor: Int,
    private val fillColor: Int,
    density: Float,
) : ReplacementSpan() {
    private val padH = 7f * density
    private val radius = 9f * density
    private val strokeW = 1.5f * density
    private val vInset = 2f * density

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        ceil(paint.measureText(text, start, end) + padH * 2 + strokeW * 2).toInt()

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val total = paint.measureText(text, start, end) + padH * 2 + strokeW * 2
        val fm = paint.fontMetrics
        val rect = RectF(x + strokeW / 2f, y + fm.ascent - vInset, x + total - strokeW / 2f, y + fm.descent + vInset)
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = fillColor
        })
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = strokeW; color = strokeColor
        })
        val tp = Paint(paint).apply { color = textColor; isAntiAlias = true }
        canvas.drawText(text, start, end, x + strokeW + padH, y.toFloat(), tp)
    }
}
