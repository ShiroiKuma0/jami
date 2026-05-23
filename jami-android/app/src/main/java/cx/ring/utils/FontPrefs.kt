package cx.ring.utils

import android.content.Context
import java.io.File

/** Per-category font preferences (family / weight / size) with a global DEFAULT fallback. */
object FontPrefs {
    const val DEFAULT = "default"
    const val CHAT_TEXT = "chat_text"
    const val CONV_TITLE = "conv_title"
    const val LIST_TITLE = "list_title"
    const val LIST_PREVIEW = "list_preview"
    const val SETTINGS = "settings"

    private const val PREFS = "shiroikuma_fonts"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getFamily(c: Context, cat: String): String = p(c).getString("family_$cat", "") ?: ""
    fun getWeight(c: Context, cat: String): Int = p(c).getInt("weight_$cat", 0)
    fun getSize(c: Context, cat: String): Float = p(c).getFloat("size_$cat", 0f)

    fun setFont(c: Context, cat: String, family: String, weight: Int, size: Float) {
        p(c).edit()
            .putString("family_$cat", family)
            .putInt("weight_$cat", weight)
            .putFloat("size_$cat", size)
            .apply()
    }

    fun effectiveFamily(c: Context, cat: String): String =
        getFamily(c, cat).ifEmpty { getFamily(c, DEFAULT) }
    fun effectiveWeight(c: Context, cat: String): Int =
        getWeight(c, cat).let { if (it != 0) it else getWeight(c, DEFAULT) }
    fun effectiveSize(c: Context, cat: String): Float =
        getSize(c, cat).let { if (it > 0f) it else getSize(c, DEFAULT) }

    fun getStatusIconLines(c: Context): Float = p(c).getFloat("status_icon_lines", 1f)
    fun setStatusIconLines(c: Context, lines: Float) {
        p(c).edit().putFloat("status_icon_lines", lines).apply()
    }

    fun fontsDir(c: Context): File = File(c.filesDir, "fonts").apply { mkdirs() }
    fun getFontFiles(c: Context): List<File> =
        fontsDir(c).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
    fun addFontFile(c: Context, name: String, bytes: ByteArray): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "font.ttf" }
        val out = File(fontsDir(c), safe)
        out.writeBytes(bytes)
        return out
    }
}
