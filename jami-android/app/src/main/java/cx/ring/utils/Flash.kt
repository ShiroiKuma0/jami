package cx.ring.utils

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Styled transient "flash" message — the fork's replacement for bare [Toast]s (e.g. "Copied to
 * clipboard"). Black fill, yellow text + 2dp yellow border, bigger and heavier than a system toast.
 *
 * Every aspect is user-settable from the **UI fonts & colors** screen ("Flashes" group):
 * colours via [ColorPrefs] ([ColorPrefs.FLASH_TEXT] / [ColorPrefs.FLASH_FILL] /
 * [ColorPrefs.FLASH_BORDER]) and font family/weight/size via the [FontPrefs.FLASH] category.
 * Unset prefs fall back to the bold yellow-on-black defaults below.
 *
 * Uses a custom-view Toast (like the original reconnect flash): foreground toasts render their
 * custom view on this device. The root carries [FontUtil.SKIP_SETTINGS_FONT_TAG] so the global
 * settings-font pass never overrides it.
 */
object Flash {
    private const val DEFAULT_SIZE_SP = 18f   // bigger than a system toast
    private const val DEFAULT_WEIGHT = 700    // bold by default ("heavier")

    fun show(context: Context?, text: CharSequence?, duration: Int = Toast.LENGTH_SHORT) {
        val ctx = context ?: return
        val msg = text ?: return
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = px(14f).toFloat()
            setColor(ColorPrefs.getColor(ctx, ColorPrefs.FLASH_FILL))
            setStroke(px(2f), ColorPrefs.getColor(ctx, ColorPrefs.FLASH_BORDER))
        }
        val size = FontPrefs.effectiveSize(ctx, FontPrefs.FLASH).let { if (it > 0f) it else DEFAULT_SIZE_SP }
        val weight = FontPrefs.effectiveWeight(ctx, FontPrefs.FLASH).let { if (it != 0) it else DEFAULT_WEIGHT }
        val tf = FontUtil.resolveTypeface(FontPrefs.effectiveFamily(ctx, FontPrefs.FLASH), weight)
            ?: Typeface.DEFAULT_BOLD

        val tv = TextView(ctx).apply {
            this.text = msg
            setTextColor(ColorPrefs.getColor(ctx, ColorPrefs.FLASH_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = tf
            gravity = Gravity.CENTER
            background = bg
            setPadding(px(18f), px(12f), px(18f), px(12f))
            tag = FontUtil.SKIP_SETTINGS_FONT_TAG
        }
        @Suppress("DEPRECATION")
        Toast(ctx).apply { this.duration = duration; view = tv; show() }
    }

    fun show(context: Context?, @StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        val ctx = context ?: return
        show(ctx, ctx.getString(resId), duration)
    }
}
