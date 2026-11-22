/*
 * 白い熊 fork (sk): the menu rule for rows that are not AppCompat menu items — the message
 * long-press menu, bottom-sheet actions, action lists. Same look as SkPillMenuItemView: an
 * outlined pill spanning the menu, its icon at the left coloured by what the row does, a › at
 * the right when the row opens something, and the danger role throughout for a row that deletes.
 */
package cx.ring.sk

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.TextViewCompat

/** The icon of a settings or list row: the label's start drawable, as tall as the label's text
 *  line (never under 18 dp) and coloured by what the row does. */
fun TextView.skRowIcon(glyph: SkGlyph, role: SkIconRole = SkIconRole.VIEW) {
    val size = maxOf(Math.round(18 * resources.displayMetrics.density), Math.round(textSize * 1.25f))
    val d = SkGlyphDrawable(glyph, skColor(role), size)
    d.setBounds(0, 0, size, size)
    setCompoundDrawablesRelative(d, null, null, null)
    compoundDrawablePadding = Math.round(10 * resources.displayMetrics.density)
}

object SkMenus {
    /** Style a label-with-start-icon TextView as a full-width menu pill. Safe to call again. */
    fun styleRow(row: TextView, role: SkIconRole, opens: Boolean = false, glyph: SkGlyph? = null) {
        val d = row.resources.displayMetrics.density
        fun dp(v: Float) = Math.round(v * d)
        val destructive = role == SkIconRole.DELETE
        var ink = if (destructive) skColor(SkRole.DANGER) else skColor(SkRole.MENU_LABEL)
        var frame = if (destructive) ink else skColor(SkRole.MENU_ROW_FRAME)
        var iconInk = skColor(role)
        if (!row.isEnabled) { ink = skDisabled(ink); frame = skDisabled(frame); iconInk = skDisabled(iconInk) }
        row.setTextColor(ink)
        // One tint for both ends would colour the › like the icon: tint each drawable itself.
        TextViewCompat.setCompoundDrawableTintList(row, null as ColorStateList?)
        val existing = row.compoundDrawablesRelative[0]
        val start = if (glyph != null || existing == null)
            SkGlyphDrawable(glyph ?: SkGlyph.DOT, iconInk, dp(18f))
        else existing.mutate().also { it.setTint(iconInk) }
        val end = if (opens) SkGlyphDrawable(SkGlyph.CHEVRON, ink, dp(18f)) else null
        start.setBounds(0, 0, dp(18f), dp(18f))
        end?.setBounds(0, 0, dp(18f), dp(18f))
        row.setCompoundDrawablesRelative(start, null, end, null)
        row.compoundDrawablePadding = dp(12f)
        row.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        row.background = skPillBackground(d, frame, Color.TRANSPARENT, false)
        row.setPaddingRelative(dp(14f), dp(9f), dp(12f), dp(9f))
        row.minimumHeight = dp(40f)
        (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.setMargins(dp(8f), dp(3f), dp(8f), dp(3f))
            row.layoutParams = lp
        }
    }
}
