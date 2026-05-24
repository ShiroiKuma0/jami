package cx.ring.utils

import android.content.Context
import androidx.core.content.ContextCompat
import cx.ring.R

/**
 * Per-element colour overrides (sibling to [FontPrefs]). Each role defaults to the current palette
 * value via [defaultColor], so an unset role reproduces today's appearance exactly (0 is a valid
 * colour, so "unset" is tracked by key presence, never a sentinel).
 */
object ColorPrefs {
    const val LIST_NAME = "list_name"
    const val LIST_PREVIEW = "list_preview"
    const val LIST_DATE = "list_date"
    const val CONV_TITLE = "conv_title"
    const val MSG_SENT = "msg_sent"
    const val MSG_RECEIVED = "msg_received"
    const val MSG_TIME = "msg_time"
    const val LINK_TITLE = "link_title"
    const val LINK_DESC = "link_desc"
    const val LINK_DOMAIN = "link_domain"
    const val FILE_NAME = "file_name"
    const val FILE_ARROW = "file_arrow"
    const val SEARCH_HINT = "search_hint"
    const val SETTINGS = "settings"

    private const val PREFS = "shiroikuma_colors"
    private const val YELLOW = 0xFFFFFF00.toInt()
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(role: String) = "color_$role"

    /** The original hardcoded palette colour for [role]; painter and settings UI both read this. */
    fun defaultColor(c: Context, role: String): Int = when (role) {
        LIST_DATE, MSG_TIME, LINK_DOMAIN -> ContextCompat.getColor(c, R.color.textColorSecondary)
        else -> YELLOW
    }

    fun isSet(c: Context, role: String): Boolean = p(c).contains(key(role))
    fun getColor(c: Context, role: String): Int =
        if (isSet(c, role)) p(c).getInt(key(role), defaultColor(c, role)) else defaultColor(c, role)
    fun setColor(c: Context, role: String, color: Int) {
        p(c).edit().putInt(key(role), color).apply()
    }
    fun reset(c: Context, role: String) {
        p(c).edit().remove(key(role)).apply()
    }
}
