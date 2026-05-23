package cx.ring.utils

import android.content.Context

/** Shiroikuma UI preferences (not part of the Jami account/settings model). */
object UiPrefs {
    private const val PREFS = "shiroikuma_ui"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether the list + conversation may show side-by-side on wide screens. Default on. */
    fun isSplitView(c: Context): Boolean = p(c).getBoolean("split_view", true)
    fun setSplitView(c: Context, on: Boolean) {
        p(c).edit().putBoolean("split_view", on).apply()
    }
}
