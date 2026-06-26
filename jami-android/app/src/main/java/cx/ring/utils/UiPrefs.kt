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

    /** Account online/offline dot size, as a multiple of the 24dp base. Default 1.5 (150%). */
    fun getStatusDotScale(c: Context): Float = p(c).getFloat("status_dot_scale", 1.5f)
    fun setStatusDotScale(c: Context, v: Float) {
        p(c).edit().putFloat("status_dot_scale", v).apply()
    }

    /** Connection-monitor fold triangle (▶/▼) size, as a RelativeSizeSpan multiple of the row text.
     *  Default 0.94 (half the previous 1.88). Settable in the UI fonts & colours page. */
    fun getMonitorFoldScale(c: Context): Float = p(c).getFloat("monitor_fold_scale", 0.94f)
    fun setMonitorFoldScale(c: Context, v: Float) {
        p(c).edit().putFloat("monitor_fold_scale", v).apply()
    }
}
