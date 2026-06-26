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
    const val PRESENCE_CONNECTED = "presence_connected"
    const val PRESENCE_AVAILABLE = "presence_available"
    const val MSG_SENT_FILL = "msg_sent_fill"
    const val MSG_SENT_BORDER = "msg_sent_border"
    const val MSG_RECEIVED_FILL = "msg_received_fill"
    const val MSG_RECEIVED_BORDER = "msg_received_border"
    const val LINK_CARD_FILL = "link_card_fill"
    const val LINK_CARD_BORDER = "link_card_border"
    const val FILE_CARD_FILL = "file_card_fill"
    const val FILE_CARD_BORDER = "file_card_border"
    const val BADGE_FILL = "badge_fill"
    const val BADGE_BORDER = "badge_border"
    const val STATUS_SENDING = "status_sending"
    const val STATUS_SUCCESS = "status_success"
    const val STATUS_ONLINE = "status_online"
    const val STATUS_OFFLINE = "status_offline"
    const val UNREAD_BORDER = "unread_border"
    const val FLASH_TEXT = "flash_text"
    const val FLASH_FILL = "flash_fill"
    const val FLASH_BORDER = "flash_border"
    const val MONITOR_HEALTHY = "monitor_healthy"
    const val MONITOR_CONNECTING = "monitor_connecting"
    const val MONITOR_PROBLEM = "monitor_problem"
    const val MONITOR_CONNECTED = "monitor_connected"
    const val MONITOR_IDLE = "monitor_idle"
    const val MONITOR_OFFLINE = "monitor_offline"

    private const val PREFS = "shiroikuma_colors"
    private const val YELLOW = 0xFFFFFF00.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(role: String) = "color_$role"

    /** The original hardcoded palette colour for [role]; painter and settings UI both read this. */
    fun defaultColor(c: Context, role: String): Int = when (role) {
        LIST_DATE, MSG_TIME, LINK_DOMAIN -> ContextCompat.getColor(c, R.color.textColorSecondary)
        PRESENCE_AVAILABLE -> ContextCompat.getColor(c, R.color.available_indicator)
        MSG_SENT_FILL, MSG_RECEIVED_FILL, LINK_CARD_FILL, FILE_CARD_FILL, BADGE_FILL, FLASH_FILL -> BLACK
        STATUS_SENDING, STATUS_SUCCESS, STATUS_OFFLINE -> ContextCompat.getColor(c, R.color.grey_500)
        STATUS_ONLINE -> 0xFF4CAF50.toInt()
        MONITOR_IDLE, MONITOR_CONNECTING -> 0xFF2196F3.toInt()   // blue — in-progress / connecting
        MONITOR_OFFLINE, MONITOR_PROBLEM -> 0xFFFF5252.toInt()   // red — offline / not reachable / not syncing
        // MONITOR_HEALTHY and MONITOR_CONNECTED fall through to the YELLOW (#FFFF00) default
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
