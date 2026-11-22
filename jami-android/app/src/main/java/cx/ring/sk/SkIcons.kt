/*
 * 白い熊 fork (sk): the theme's own icons, drawn in code on a 24-unit grid as 2-unit outlines.
 *
 * Rows of the UI page, of menus and of action lists each carry an icon coloured by what the row
 * does. Upstream's icon set has no glyph for most of the fork's rows, and a vector asset would
 * pin a fill colour in XML — so these are paths, tinted where they are used.
 */
package cx.ring.sk

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

enum class SkGlyph {
    CHEVRON, EXPAND, DROP, TEXT, SLIDER, TOGGLE, OPEN, EXPORT, IMPORT, TRASH, GEAR, RESET, GLOBE,
    CLOCK, LIST, INFO, SPLIT, HOME, MESSAGE, PERSON, DIRECTORY, NETWORK, BOLT, PULSE, BARS, EYE,
    PHONE, EDIT, LOCATION, CHECK, BELL, DOT
}

class SkGlyphDrawable(private val glyph: SkGlyph, private var color: Int, private val sizePx: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val r = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        canvas.scale(s, s)
        stroke.color = color
        stroke.strokeWidth = 2f
        fill.color = color
        path.reset()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) { path.moveTo(x1, y1); path.lineTo(x2, y2) }
        fun circle(cx: Float, cy: Float, rad: Float) = path.addCircle(cx, cy, rad, Path.Direction.CW)
        fun rect(l: Float, t: Float, rr: Float, bb: Float, rad: Float = 1.5f) {
            r.set(l, t, rr, bb); path.addRoundRect(r, rad, rad, Path.Direction.CW)
        }
        fun arc(l: Float, t: Float, rr: Float, bb: Float, start: Float, sweep: Float) {
            r.set(l, t, rr, bb); path.addArc(r, start, sweep)
        }
        fun dot(cx: Float, cy: Float, rad: Float = 1.6f) = canvas.drawCircle(cx, cy, rad, fill)
        when (glyph) {
            SkGlyph.CHEVRON -> { path.moveTo(9f, 6f); path.lineTo(15f, 12f); path.lineTo(9f, 18f) }
            SkGlyph.EXPAND -> { path.moveTo(6f, 9f); path.lineTo(12f, 15f); path.lineTo(18f, 9f) }
            SkGlyph.DROP -> {
                path.moveTo(12f, 3f); path.cubicTo(8f, 8f, 6f, 11f, 6f, 14.5f)
                path.cubicTo(6f, 18f, 8.7f, 21f, 12f, 21f); path.cubicTo(15.3f, 21f, 18f, 18f, 18f, 14.5f)
                path.cubicTo(18f, 11f, 16f, 8f, 12f, 3f); path.close()
            }
            SkGlyph.TEXT -> { path.moveTo(5f, 20f); path.lineTo(12f, 4f); path.lineTo(19f, 20f); line(8f, 14f, 16f, 14f) }
            SkGlyph.SLIDER -> { line(3f, 12f, 21f, 12f); canvas.drawCircle(14f, 12f, 3.2f, fill) }
            SkGlyph.TOGGLE -> { rect(2f, 7f, 22f, 17f, 5f); dot(16.5f, 12f, 2.4f) }
            SkGlyph.OPEN -> {
                path.moveTo(11f, 5f); path.lineTo(5f, 5f); path.lineTo(5f, 19f); path.lineTo(19f, 19f); path.lineTo(19f, 13f)
                line(12f, 12f, 20f, 4f); path.moveTo(14f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 10f)
            }
            SkGlyph.EXPORT -> {
                line(12f, 15f, 12f, 3f); path.moveTo(7f, 8f); path.lineTo(12f, 3f); path.lineTo(17f, 8f)
                path.moveTo(4f, 14f); path.lineTo(4f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 14f)
            }
            SkGlyph.IMPORT -> {
                line(12f, 3f, 12f, 15f); path.moveTo(7f, 10f); path.lineTo(12f, 15f); path.lineTo(17f, 10f)
                path.moveTo(4f, 14f); path.lineTo(4f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 14f)
            }
            SkGlyph.TRASH -> {
                line(4f, 7f, 20f, 7f); path.moveTo(9f, 7f); path.lineTo(9f, 4f); path.lineTo(15f, 4f); path.lineTo(15f, 7f)
                path.moveTo(6f, 7f); path.lineTo(7f, 21f); path.lineTo(17f, 21f); path.lineTo(18f, 7f)
                line(10f, 11f, 10f, 17f); line(14f, 11f, 14f, 17f)
            }
            SkGlyph.GEAR -> {
                circle(12f, 12f, 3.2f); circle(12f, 12f, 7f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(12f + 7f * Math.cos(a).toFloat(), 12f + 7f * Math.sin(a).toFloat(),
                        12f + 10f * Math.cos(a).toFloat(), 12f + 10f * Math.sin(a).toFloat())
                }
            }
            SkGlyph.RESET -> {
                arc(4f, 4f, 20f, 20f, -60f, 300f)
                path.moveTo(16f, 2f); path.lineTo(16.2f, 6f); path.lineTo(20f, 5.6f)
            }
            SkGlyph.GLOBE -> { circle(12f, 12f, 9f); line(3f, 12f, 21f, 12f); r.set(8f, 3f, 16f, 21f); path.addOval(r, Path.Direction.CW) }
            SkGlyph.CLOCK -> { circle(12f, 12f, 9f); path.moveTo(12f, 6.5f); path.lineTo(12f, 12f); path.lineTo(16f, 14.5f) }
            SkGlyph.LIST -> { line(8f, 6f, 20f, 6f); line(8f, 12f, 20f, 12f); line(8f, 18f, 20f, 18f); dot(4f, 6f, 1.3f); dot(4f, 12f, 1.3f); dot(4f, 18f, 1.3f) }
            SkGlyph.INFO -> { circle(12f, 12f, 9f); line(12f, 11f, 12f, 17f); dot(12f, 7.5f, 1.3f) }
            SkGlyph.SPLIT -> { rect(3f, 4f, 21f, 20f, 2f); line(12f, 4f, 12f, 20f) }
            SkGlyph.HOME -> {
                path.moveTo(3f, 11f); path.lineTo(12f, 3f); path.lineTo(21f, 11f)
                path.moveTo(6f, 9f); path.lineTo(6f, 20f); path.lineTo(18f, 20f); path.lineTo(18f, 9f)
            }
            SkGlyph.MESSAGE -> {
                path.moveTo(4f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 16f); path.lineTo(10f, 16f)
                path.lineTo(5f, 21f); path.lineTo(5f, 16f); path.lineTo(4f, 16f); path.close()
            }
            SkGlyph.PERSON -> { circle(12f, 8f, 4f); arc(4f, 14f, 20f, 28f, 180f, 180f) }
            SkGlyph.DIRECTORY -> {
                path.moveTo(3f, 6f); path.lineTo(9f, 6f); path.lineTo(11f, 8.5f); path.lineTo(21f, 8.5f)
                path.lineTo(21f, 19f); path.lineTo(3f, 19f); path.close()
            }
            SkGlyph.NETWORK -> { arc(2f, 6f, 22f, 26f, 225f, 90f); arc(6f, 10f, 18f, 22f, 225f, 90f); dot(12f, 18f, 1.8f) }
            SkGlyph.BOLT -> {
                path.moveTo(13f, 2f); path.lineTo(5f, 13.5f); path.lineTo(11f, 13.5f); path.lineTo(10f, 22f)
                path.lineTo(19f, 10f); path.lineTo(13f, 10f); path.close()
            }
            SkGlyph.PULSE -> {
                path.moveTo(2f, 12f); path.lineTo(7f, 12f); path.lineTo(10f, 5f); path.lineTo(14f, 19f)
                path.lineTo(17f, 12f); path.lineTo(22f, 12f)
            }
            SkGlyph.BARS -> { line(5f, 20f, 5f, 12f); line(12f, 20f, 12f, 5f); line(19f, 20f, 19f, 9f) }
            SkGlyph.EYE -> {
                path.moveTo(2f, 12f); path.quadTo(12f, 2f, 22f, 12f); path.quadTo(12f, 22f, 2f, 12f); path.close()
                dot(12f, 12f, 2.6f)
            }
            SkGlyph.PHONE -> {
                path.moveTo(6f, 3f); path.lineTo(9.5f, 3f); path.lineTo(11f, 8f); path.lineTo(8.5f, 9.5f)
                path.cubicTo(9.5f, 12f, 12f, 14.5f, 14.5f, 15.5f); path.lineTo(16f, 13f); path.lineTo(21f, 14.5f)
                path.lineTo(21f, 18f); path.cubicTo(21f, 19.7f, 19.7f, 21f, 18f, 21f)
                path.cubicTo(10f, 21f, 3f, 14f, 3f, 6f); path.cubicTo(3f, 4.3f, 4.3f, 3f, 6f, 3f); path.close()
            }
            SkGlyph.EDIT -> {
                path.moveTo(4f, 20f); path.lineTo(4f, 16f); path.lineTo(15f, 5f); path.lineTo(19f, 9f)
                path.lineTo(8f, 20f); path.close(); line(13f, 7f, 17f, 11f)
            }
            SkGlyph.LOCATION -> {
                path.moveTo(12f, 22f); path.cubicTo(7f, 16f, 5f, 13f, 5f, 9.5f)
                path.cubicTo(5f, 5.5f, 8f, 2.5f, 12f, 2.5f); path.cubicTo(16f, 2.5f, 19f, 5.5f, 19f, 9.5f)
                path.cubicTo(19f, 13f, 17f, 16f, 12f, 22f); path.close(); dot(12f, 9.5f, 2.2f)
            }
            SkGlyph.CHECK -> { path.moveTo(4f, 12.5f); path.lineTo(9.5f, 18f); path.lineTo(20f, 6.5f) }
            SkGlyph.BELL -> {
                path.moveTo(5f, 17f); path.lineTo(6.5f, 15f); path.lineTo(6.5f, 10.5f)
                path.cubicTo(6.5f, 7f, 9f, 4.5f, 12f, 4.5f); path.cubicTo(15f, 4.5f, 17.5f, 7f, 17.5f, 10.5f)
                path.lineTo(17.5f, 15f); path.lineTo(19f, 17f); path.close(); line(10.5f, 20.5f, 13.5f, 20.5f)
            }
            SkGlyph.DOT -> dot(12f, 12f, 4f)
        }
        canvas.drawPath(path, stroke)
        canvas.restore()
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setTint(tintColor: Int) { color = tintColor; invalidateSelf() }
    override fun setTintList(tint: android.content.res.ColorStateList?) {
        if (tint != null) { color = tint.defaultColor; invalidateSelf() }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
