/*
 * 白い熊 fork (sk): the pill — the one button look of the black-yellow theme.
 *
 * A pill is fully round and never filled: a frame with the ground showing through. The main
 * action of a block has a 3 dp frame and bold text, every other action a 1.25 dp frame and
 * normal text; they differ by weight only. Buttons inflated from a layout get the same look from
 * SkViewInflater — this file is for views built in code.
 */
package cx.ring.sk

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.TextView

const val SK_PILL_MAIN_STROKE_DP = 3f
const val SK_PILL_STROKE_DP = 1.25f

fun skPillStrokePx(density: Float, main: Boolean): Int =
    maxOf(1, Math.round((if (main) SK_PILL_MAIN_STROKE_DP else SK_PILL_STROKE_DP) * density))

/** The bare pill outline, without a ripple. */
fun skPillFrame(density: Float, ink: Int, fill: Int, main: Boolean): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 100 * density
        setColor(fill)
        setStroke(skPillStrokePx(density, main), ink)
    }

/** A pill background for a tappable view: the outline plus a ripple inside it. */
fun skPillBackground(density: Float, ink: Int, fill: Int = Color.TRANSPARENT, main: Boolean = false): Drawable {
    // The ripple needs an opaque mask: the frame's own inside may be transparent.
    val mask = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 100 * density
        setColor(Color.WHITE)
    }
    return RippleDrawable(ColorStateList.valueOf((ink and 0x00FFFFFF) or 0x4D000000), skPillFrame(density, ink, fill, main), mask)
}

/** A ring for a round control (floating button, in-call control). */
fun skRing(density: Float, ink: Int, fill: Int, main: Boolean = false): Drawable {
    val ring = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(skPillStrokePx(density, main), ink)
    }
    val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
    return RippleDrawable(ColorStateList.valueOf((ink and 0x00FFFFFF) or 0x4D000000), ring, mask)
}

/** Turn a text view (or a Button) built in code into a pill that hugs its label. */
fun TextView.applySkPill(
    main: Boolean = false,
    ink: Int = skColor(SkRole.PILL_TEXT),
    fill: Int = skColor(SkRole.PILL_GROUND),
    frame: Int = if (ink == skColor(SkRole.PILL_TEXT)) skColor(SkRole.PILL_FRAME) else ink
) {
    val density = resources.displayMetrics.density
    isAllCaps = false
    setTextColor(ink)
    typeface = Typeface.create(typeface, if (main) Typeface.BOLD else Typeface.NORMAL)
    background = skPillBackground(density, frame, fill, main)
    stateListAnimator = null
    minWidth = 0
    minimumWidth = 0
    minHeight = 0
    minimumHeight = 0
    val paddingH = (18 * density).toInt()
    val paddingV = (9 * density).toInt()
    setPadding(paddingH, paddingV, paddingH, paddingV)
}

/** The panel of a dialog, a sheet, a popup or a toast: a ground inside a 1 dp frame, round corners. */
fun skPanel(
    density: Float,
    ground: Int = skColor(SkRole.DIALOG_GROUND),
    frame: Int = skColor(SkRole.DIALOG_FRAME),
    radiusDp: Float = 12f
): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * density
        setColor(ground)
        setStroke(maxOf(1, Math.round(density)), frame)
    }
