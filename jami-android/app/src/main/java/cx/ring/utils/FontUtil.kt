package cx.ring.utils

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cx.ring.R
import java.io.File

object FontUtil {
    val FAMILIES: List<Pair<String, String>> = listOf(
        "Default" to "",
        "Sans Serif" to "sans-serif",
        "Sans Serif Light" to "sans-serif-light",
        "Sans Serif Medium" to "sans-serif-medium",
        "Sans Serif Condensed" to "sans-serif-condensed",
        "Sans Serif Thin" to "sans-serif-thin",
        "Serif" to "serif",
        "Monospace" to "monospace",
        "Cursive" to "cursive",
    )
    val WEIGHTS: List<Pair<String, Int>> = listOf(
        "Default" to 0,
        "Thin (100)" to 100,
        "Light (300)" to 300,
        "Normal (400)" to 400,
        "Medium (500)" to 500,
        "Bold (700)" to 700,
        "Black (900)" to 900,
    )

    private val fileCache = HashMap<String, Typeface?>()
    private fun base(family: String): Typeface? = when {
        family.isEmpty() -> null
        family.startsWith("file:") -> {
            val path = family.removePrefix("file:")
            fileCache.getOrPut(path) { try { Typeface.createFromFile(path) } catch (_: Exception) { null } }
        }
        else -> Typeface.create(family, Typeface.NORMAL)
    }

    fun resolveTypeface(family: String, weight: Int): Typeface? {
        val b = base(family)
        if (weight == 0) return b
        val src = b ?: Typeface.DEFAULT
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(src, weight, false)
        else Typeface.create(src, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Line height (px) of the chat-text font, for sizing the message status icon to one line. */
    fun chatTextLineHeightPx(context: Context): Int {
        val sizeSp = FontPrefs.effectiveSize(context, FontPrefs.CHAT_TEXT)
        val sizePx = if (sizeSp > 0f)
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
        else context.resources.getDimension(R.dimen.custom_message_bubble_default_text_size)
        val paint = android.graphics.Paint().apply {
            typeface = resolveTypeface(
                FontPrefs.effectiveFamily(context, FontPrefs.CHAT_TEXT),
                FontPrefs.effectiveWeight(context, FontPrefs.CHAT_TEXT)
            ) ?: Typeface.DEFAULT
            textSize = sizePx
        }
        val fm = paint.fontMetricsInt
        return (fm.descent - fm.ascent + fm.leading).coerceAtLeast(1)
    }

    fun apply(view: TextView, category: String) {
        val c = view.context
        val tf = resolveTypeface(FontPrefs.effectiveFamily(c, category), FontPrefs.effectiveWeight(c, category))
        if (tf != null && view.typeface !== tf) view.typeface = tf
        val size = FontPrefs.effectiveSize(c, category)
        if (size > 0f) {
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, c.resources.displayMetrics)
            if (view.textSize != px) view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }
    }

    /** Recursively apply [category] to every TextView under [root]. */
    fun applyTree(root: View?, category: String) {
        when (root) {
            is TextView -> apply(root, category)
            is ViewGroup -> for (i in 0 until root.childCount) applyTree(root.getChildAt(i), category)
        }
    }

    private val settingsInstalled = java.util.WeakHashMap<View, Boolean>()
    /** Apply SETTINGS to a settings/preference subtree now and on every relayout, so recycled
     *  preference rows keep the font. apply() is idempotent, so the relayout walk can't loop. */
    fun installSettingsFont(root: View?) {
        root ?: return
        applyTree(root, FontPrefs.SETTINGS)
        if (settingsInstalled.put(root, true) == null) {
            root.viewTreeObserver.addOnGlobalLayoutListener { applyTree(root, FontPrefs.SETTINGS) }
        }
    }

    fun labelForFamily(value: String): String {
        FAMILIES.firstOrNull { it.second == value }?.let { return it.first }
        if (value.startsWith("file:")) return File(value.removePrefix("file:")).name
        return value
    }
    fun labelForWeight(weight: Int): String = WEIGHTS.firstOrNull { it.second == weight }?.first ?: weight.toString()

    fun describe(c: Context, category: String): String {
        val family = FontPrefs.getFamily(c, category)
        val weight = FontPrefs.getWeight(c, category)
        val size = FontPrefs.getSize(c, category)
        if (family.isEmpty() && weight == 0 && size <= 0f) return "Default"
        val parts = ArrayList<String>()
        parts.add(if (family.isEmpty()) "Default family" else labelForFamily(family))
        if (weight != 0) parts.add(labelForWeight(weight))
        if (size > 0f) parts.add("${size.toInt()}sp")
        return parts.joinToString("  \u00B7  ")
    }
}
