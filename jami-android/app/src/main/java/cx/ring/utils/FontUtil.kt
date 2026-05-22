package cx.ring.utils

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.widget.TextView
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

    private fun base(family: String): Typeface? = when {
        family.isEmpty() -> null
        family.startsWith("file:") ->
            try { Typeface.createFromFile(family.removePrefix("file:")) } catch (_: Exception) { null }
        else -> Typeface.create(family, Typeface.NORMAL)
    }

    fun resolveTypeface(family: String, weight: Int): Typeface? {
        val b = base(family)
        if (weight == 0) return b
        val src = b ?: Typeface.DEFAULT
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(src, weight, false)
        else Typeface.create(src, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    fun apply(view: TextView, category: String) {
        val c = view.context
        val tf = resolveTypeface(FontPrefs.effectiveFamily(c, category), FontPrefs.effectiveWeight(c, category))
        if (tf != null) view.typeface = tf
        val size = FontPrefs.effectiveSize(c, category)
        if (size > 0f) view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
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
