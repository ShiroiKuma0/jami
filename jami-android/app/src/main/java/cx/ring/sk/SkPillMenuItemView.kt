/*
 * 白い熊 fork (skui): the row of every popup menu — a file's menu, the toolbar ⋮ overflow, the
 * selection bar's overflow, submenus — drawn by the black-yellow theme's menu rule, so a menu
 * shows at a glance which lines can be pressed:
 *  - an action row is an outlined pill spanning the menu, its icon inside at the left and a ›
 *    at the right when it opens something;
 *  - the icon is coloured by what the row does (SkMenuPills), frame and label stay in the menu
 *    colours;
 *  - a row that deletes takes the danger role throughout, a disabled one is dimmed to 38 %;
 *  - a toggle row stays plain and gets the outlined switch, never a checkbox; the choices of
 *    an exclusive group keep their radio mark.
 *
 * (Ported from 白い熊 書類管理.) It is swapped in through the app's own copies of AppCompat's abc_popup_menu_item_layout and
 * abc_cascading_menu_item_layout — an app layout overrides the library layout of the same
 * name — so no call site asks for it.
 */

package cx.ring.sk

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.AbsListView
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.view.menu.ListMenuItemView
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.view.menu.SkMenuAccess
import androidx.core.graphics.ColorUtils

@SuppressLint("RestrictedApi")
class SkPillMenuItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.listMenuViewStyle
) : ListMenuItemView(context, attrs, defStyleAttr) {
    private var ready = false
    private var content: LinearLayout? = null
    private var title: TextView? = null
    private var arrow: ImageView? = null
    private var padStart = 0
    private var padTop = 0
    private var padEnd = 0
    private var padBottom = 0
    private var contentHeight = 0

    override fun onFinishInflate() {
        super.onFinishInflate()

        try {
            content = findViewById(androidx.appcompat.R.id.content)
            title = findViewById(androidx.appcompat.R.id.title)
            arrow = findViewById(androidx.appcompat.R.id.submenuarrow)
            val holder = content
            ready = holder != null && title != null
            if (holder != null && ready) {
                padStart = holder.paddingStart
                padTop = holder.paddingTop
                padEnd = holder.paddingEnd
                padBottom = holder.paddingBottom
                contentHeight = holder.layoutParams.height
                findViewById<View>(androidx.appcompat.R.id.group_divider)?.let { divider ->
                    // The stock divider is all but invisible on black: a clear line, as wide
                    // as the pills.
                    divider.setBackgroundColor(SkMenuPills.divider())
                    (divider.layoutParams as? MarginLayoutParams)?.let {
                        it.leftMargin = dp(SkMenuPills.SIDE_MARGIN_DP)
                        it.rightMargin = dp(SkMenuPills.SIDE_MARGIN_DP)
                        divider.layoutParams = it
                    }
                }
            }
        } catch (ignored: Throwable) {
            ready = false
        }
    }

    override fun initialize(itemData: MenuItemImpl, menuType: Int) {
        var icons = true
        if (ready) {
            try {
                icons = SkMenuPills.anyIcon(SkMenuAccess.menuOf(itemData))
                // Also keeps the icon column for a row whose icon is still missing.
                setForceShowIcon(icons)
            } catch (ignored: Throwable) {
            }
        }
        super.initialize(itemData, menuType)
        if (!ready) {
            return
        }
        try {
            if (icons && itemData.icon == null) {
                SkMenuPills.iconFor(context, itemData)?.let { setIcon(it) }
            }
            style(itemData)
        } catch (ignored: Throwable) {
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // The pill carries the ripple; the list's rectangular press highlight would paint over
        // its round ends.
        val list = parent as? AbsListView ?: return
        if (ready && list.selector !is ColorDrawable) {
            list.selector = ColorDrawable(Color.TRANSPARENT)
        }
    }

    private fun style(item: MenuItemImpl) {
        val holder = content ?: return
        val label = title ?: return
        val toggle = SkMenuPills.isToggle(item)
        val pill = SkMenuPills.isPill(item)
        val destructive = pill && SkMenuPills.isDestructive(item)

        fun dimmed(color: Int) = if (item.isEnabled) color else skDisabled(color)
        val rowColor = dimmed(if (destructive) SkMenuPills.danger() else SkMenuPills.label())
        val iconColor = dimmed(SkMenuPills.iconColor(item))

        val params = holder.layoutParams as MarginLayoutParams
        val horizontal = dp(SkMenuPills.SIDE_MARGIN_DP)
        val vertical = if (pill) dp(SkMenuPills.PILL_MARGIN_DP) else 0
        // A toggle row has nothing to frame, so it is only as tall as its switch needs.
        val height = if (toggle) dp(SkMenuPills.TOGGLE_HEIGHT_DP) else contentHeight
        val placed = params.leftMargin == horizontal && params.topMargin == vertical
            && params.rightMargin == horizontal && params.bottomMargin == vertical
        if (!placed || params.height != height) {
            params.height = height
            params.setMargins(horizontal, vertical, horizontal, vertical)
            holder.layoutParams = params
        }
        // A row that deletes lends its colour to the frame as well; every other frame has its
        // own role.
        val frameColor = if (destructive) rowColor else dimmed(SkMenuPills.frame())
        holder.background = if (pill) pillBackground(frameColor) else null
        // The switch of a toggle row ends where the pills end.
        holder.setPaddingRelative(padStart, padTop, if (toggle) 0 else padEnd, padBottom)

        // Plain text: a colour span left on the title would outrank the row's colour.
        // (The choices of an exclusive group keep their spans: a style menu shows each style.)
        if (!item.isExclusiveCheckable) label.text = item.title?.toString().orEmpty()
        label.setTextColor(rowColor)

        arrow?.let { chevronView ->
            val opens = pill && SkMenuPills.opens(item)
            if (opens) {
                chevronView.setImageDrawable(
                    SkGlyphDrawable(SkGlyph.CHEVRON, rowColor, dp(20))
                )
            }
            chevronView.visibility = if (opens) View.VISIBLE else View.GONE
        }

        for (index in 0 until holder.childCount) {
            val child = holder.getChildAt(index)
            when {
                child is CheckBox -> styleSwitch(child)
                child is RadioButton -> styleRadio(child)
                child.id == androidx.appcompat.R.id.icon -> styleIcon(child, iconColor)
            }
        }
    }

    private fun styleSwitch(box: CheckBox) {
        if (box.tag != null) {
            return
        }
        box.buttonDrawable = SkOutlinedSwitchDrawable(
            skColor(SkRole.SWITCH_ON), skColor(SkRole.SWITCH_OFF), dp(SWITCH_WIDTH_DP),
            dp(SWITCH_HEIGHT_DP), dp(2)
        )
        box.buttonTintList = null
        box.background = null
        // A long label stops short of the switch.
        (box.layoutParams as? MarginLayoutParams)?.let {
            it.marginStart = dp(SWITCH_GAP_DP)
            box.layoutParams = it
        }
        box.tag = true
    }

    // The choice of an exclusive group stays a selection mark, in the mark roles.
    private fun styleRadio(radio: RadioButton) {
        radio.buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(skColor(SkRole.MARK_ON), skColor(SkRole.MARK_OFF))
        )
        (radio.layoutParams as? MarginLayoutParams)?.let {
            if (it.marginEnd != dp(SkMenuPills.SIDE_MARGIN_DP)) {
                it.marginEnd = dp(SkMenuPills.SIDE_MARGIN_DP)
                radio.layoutParams = it
            }
        }
    }

    private fun styleIcon(icon: View, color: Int) {
        // Clear of the round end of the pill, and not squeezed in a low row.
        val params = icon.layoutParams as? MarginLayoutParams
        if (params != null && params.topMargin != dp(2)) {
            params.marginStart = dp(ICON_INSET_DP)
            params.marginEnd = -dp(ICON_PULL_DP)
            params.topMargin = dp(2)
            params.bottomMargin = dp(2)
            icon.layoutParams = params
        }
        // The icon is coloured by what the row does, not by the row.
        (icon as? ImageView)?.apply {
            clearColorFilter()
            drawable?.mutate()?.clearColorFilter()
            imageTintList = ColorStateList.valueOf(color)
        }
    }

    private fun pillBackground(color: Int): Drawable {
        val density = resources.displayMetrics.density
        val radius = 100 * density
        val frame = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(skPillStrokePx(density, false), color)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        val ripple = ColorUtils.setAlphaComponent(
            color, Math.round(Color.alpha(color) * RIPPLE_ALPHA)
        )
        return RippleDrawable(ColorStateList.valueOf(ripple), frame, mask)
    }

    private fun dp(value: Int) = Math.round(value * resources.displayMetrics.density)

    private companion object {
        const val RIPPLE_ALPHA = 0.3f
        const val SWITCH_WIDTH_DP = 44
        const val SWITCH_HEIGHT_DP = 24
        const val SWITCH_GAP_DP = 8
        const val ICON_INSET_DP = 14
        const val ICON_PULL_DP = 6
    }
}
