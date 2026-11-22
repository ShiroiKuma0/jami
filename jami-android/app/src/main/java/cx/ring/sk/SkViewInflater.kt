/*
 * 白い熊 fork (sk): the one place every inflated button, switch, check box and radio button
 * passes through.
 *
 * Named by `viewInflaterClass` in ThemeOverlay.Sk, so AppCompat hands it every view a layout
 * declares — upstream's screens, dialogs and preference rows included, with no layout or call
 * site touched. What it does:
 *  - a button becomes a pill drawn from its roles (frame, label, ground). The positive button of
 *    a dialog, and a button upstream drew filled, is the main pill: 3 dp and bold. In a dialog
 *    that deletes the main pill takes the danger role. A button that shows only an icon stays a
 *    glyph (白い熊, 2026-11-22);
 *  - a floating button becomes an outlined ring, an extended one an outlined pill;
 *  - a switch keeps its class (view bindings cast to it) and gets the outlined track and dot;
 *  - check boxes and radio buttons stay selection marks, in their own two roles.
 */
package cx.ring.sk

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.CompoundButton
import android.widget.Switch
import androidx.annotation.Keep
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.CompoundButtonCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.theme.MaterialComponentsViewInflater
import cx.ring.R

@Keep
class SkViewInflater : MaterialComponentsViewInflater() {
    override fun createButton(context: Context, attrs: AttributeSet): AppCompatButton =
        super.createButton(context, attrs).also { onAttach(it) { v -> stylePill(v as AppCompatButton) } }

    override fun createRadioButton(context: Context, attrs: AttributeSet): AppCompatRadioButton =
        super.createRadioButton(context, attrs).also { CompoundButtonCompat.setButtonTintList(it, marks()) }

    override fun createCheckBox(context: Context, attrs: AttributeSet): AppCompatCheckBox =
        super.createCheckBox(context, attrs).also { CompoundButtonCompat.setButtonTintList(it, marks()) }

    override fun createView(context: Context, name: String, attrs: AttributeSet): View? = when (name) {
        "com.google.android.material.button.MaterialButton" ->
            MaterialButton(context, attrs).also { onAttach(it) { v -> stylePill(v as AppCompatButton) } }
        "com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton" ->
            ExtendedFloatingActionButton(context, attrs).also { onAttach(it) { v -> styleExtendedFab(v as ExtendedFloatingActionButton) } }
        "com.google.android.material.floatingactionbutton.FloatingActionButton" ->
            FloatingActionButton(context, attrs).also { onAttach(it) { v -> styleFab(v as FloatingActionButton) } }
        "com.google.android.material.materialswitch.MaterialSwitch" ->
            MaterialSwitch(context, attrs).also { styleSwitch(it) }
        "androidx.appcompat.widget.SwitchCompat" -> SwitchCompat(context, attrs).also { styleSwitch(it) }
        "Switch" -> Switch(context, attrs).also { styleSwitch(it) }
        "com.google.android.material.checkbox.MaterialCheckBox" ->
            MaterialCheckBox(context, attrs).also { CompoundButtonCompat.setButtonTintList(it, marks()) }
        else -> super.createView(context, name, attrs)
    }

    private fun styleSwitch(view: CompoundButton) {
        SkSwitches.style(view)
        // A style applied after inflation (a preference binding, a theme pass) must not put the
        // stock drawables back.
        onAttach(view) { v -> SkSwitches.style(v as CompoundButton) }
    }

    // Its id, its text and its final style are only known once it is attached.
    private fun onAttach(view: View, style: (View) -> Unit) {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                try { style(v) } catch (ignored: Throwable) {}
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    companion object {
        /** Views that must keep the look upstream or the fork gave them: tag them with this. */
        const val KEEP_TAG = "sk_keep"

        fun marks() = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(
                skDisabled(skColor(SkRole.MARK_ON)), skDisabled(skColor(SkRole.MARK_OFF)),
                skColor(SkRole.MARK_ON), skColor(SkRole.MARK_OFF)
            )
        )

        private fun states(color: Int) = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(skDisabled(color), color)
        )

        /** Draw a button as the pill its place calls for. Safe to call again. */
        fun stylePill(button: AppCompatButton, forceMain: Boolean? = null, destructive: Boolean? = null) {
            if (button !is MaterialButton || button.tag == KEEP_TAG) return
            // A checkable button is a selection control (a segment of a selector): its fill marks
            // the selected state and stays.
            if (button.isCheckable) return
            // An icon without a label is a glyph, not a pill.
            if (button.text.isNullOrEmpty() && button.icon != null) {
                button.iconTint = states(skColor(SkRole.ICON))
                return
            }
            val context = button.context
            val density = context.resources.displayMetrics.density
            // Decided once: restyling clears the very signs it is decided from.
            val known = button.getTag(R.id.sk_pill_main) as? Boolean
            val isMain = forceMain ?: known ?: (button.id == android.R.id.button1 || wasFilled(button))
            button.setTag(R.id.sk_pill_main, isMain)
            val isDestructive = destructive
                ?: (button.getTag(R.id.sk_pill_danger) as? Boolean)
                ?: ((button.id == android.R.id.button1 && isDestructiveDialog(context)) || says(button, DESTRUCTIVE_WORDS))
            button.setTag(R.id.sk_pill_danger, isDestructive)
            val text = skColor(if (isDestructive) SkRole.DANGER else SkRole.PILL_TEXT)
            val frame = skColor(if (isDestructive) SkRole.DANGER else SkRole.PILL_FRAME)
            button.setTextColor(states(text))
            button.strokeColor = states(frame)
            button.iconTint = states(text)
            button.backgroundTintList = ColorStateList.valueOf(skColor(SkRole.PILL_GROUND))
            button.rippleColor = ColorStateList.valueOf((frame and 0x00FFFFFF) or 0x4D000000)
            button.cornerRadius = (100 * density).toInt()
            button.isAllCaps = false
            button.stateListAnimator = null
            button.elevation = 0f
            button.strokeWidth = skPillStrokePx(density, isMain)
            button.typeface = Typeface.create(button.typeface, if (isMain) Typeface.BOLD else Typeface.NORMAL)
        }

        /** A button upstream drew as a filled slab is the main action of its block. */
        private fun wasFilled(button: MaterialButton): Boolean {
            if (button.strokeWidth > 0) return false
            val tint = button.backgroundTintList ?: return false
            return Color.alpha(tint.getColorForState(intArrayOf(android.R.attr.state_enabled), Color.TRANSPARENT)) > 0x40
        }

        private val DESTRUCTIVE_WORDS = intArrayOf(
            R.string.menu_delete, R.string.clear_history, R.string.remove_conversation, R.string.collab_remove,
            R.string.shared_services_delete, R.string.revoke_device_title, R.string.action_call_hangup, R.string.action_call_decline,
            R.string.conversation_action_block_this, R.string.block
        )

        private fun says(button: MaterialButton, words: IntArray): Boolean {
            val label = button.text?.toString()?.trim().orEmpty()
            if (label.isEmpty()) return false
            // The fork's own English-only confirmations ("Delete forever", "Purge", "Reset").
            val lower = label.lowercase()
            if (listOf("delete", "remove", "purge", "revoke").any { lower.startsWith(it) }) return true
            return words.any { runCatching { button.context.getString(it) }.getOrNull().equals(label, ignoreCase = true) }
        }

        fun styleExtendedFab(fab: ExtendedFloatingActionButton) {
            if (fab.tag == KEEP_TAG) return
            val density = fab.resources.displayMetrics.density
            val ink = skColor(SkRole.FAB_GLYPH)
            fab.setTextColor(states(ink))
            fab.iconTint = states(ink)
            fab.strokeColor = states(skColor(SkRole.FAB_RING))
            fab.strokeWidth = skPillStrokePx(density, true)
            fab.backgroundTintList = ColorStateList.valueOf(skColor(SkRole.FAB_GROUND))
            fab.rippleColor = ColorStateList.valueOf((ink and 0x00FFFFFF) or 0x4D000000)
            fab.elevation = 0f
            fab.stateListAnimator = null
        }

        fun styleFab(fab: FloatingActionButton) {
            if (fab.tag == KEEP_TAG) return
            val density = fab.resources.displayMetrics.density
            fab.backgroundTintList = ColorStateList.valueOf(skColor(SkRole.FAB_GROUND))
            fab.imageTintList = ColorStateList.valueOf(skColor(SkRole.FAB_GLYPH))
            fab.rippleColor = (skColor(SkRole.FAB_GLYPH) and 0x00FFFFFF) or 0x4D000000
            fab.compatElevation = 0f
            // A floating button has no stroke of its own: the ring is drawn over it.
            fab.foreground = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(skPillStrokePx(density, true), skColor(SkRole.FAB_RING))
            }
        }

        private fun isDestructiveDialog(context: Context): Boolean {
            val a = context.obtainStyledAttributes(intArrayOf(R.attr.skDestructiveDialog))
            return try { a.getBoolean(0, false) } finally { a.recycle() }
        }
    }
}
