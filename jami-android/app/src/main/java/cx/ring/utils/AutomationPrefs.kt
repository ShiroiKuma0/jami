package cx.ring.utils

import android.content.Context
import java.security.SecureRandom

/**
 * Settings for the external-automation intent surface (see [cx.ring.automation.AutomationActivity]).
 *
 * A master enable flag plus a shared secret token that every automation intent must carry. Modeled
 * on [ColorPrefs] / FontPrefs: a tiny SharedPreferences-backed object, no DI. The token is generated
 * lazily on first read so the settings screen always shows a value.
 */
object AutomationPrefs {
    private const val PREFS = "shiroikuma_automation"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TOKEN = "token"
    private const val HEX = "0123456789abcdef"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = p(c).getBoolean(KEY_ENABLED, false)

    fun setEnabled(c: Context, enabled: Boolean) {
        p(c).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** The shared secret; generated and persisted on first access so it is never empty. */
    fun getToken(c: Context): String {
        p(c).getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        return regenerateToken(c)
    }

    fun regenerateToken(c: Context): String {
        val fresh = generateToken()
        p(c).edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    /** True only when automation is enabled AND [token] matches the stored secret (constant-time). */
    fun isAuthorized(c: Context, token: String?): Boolean {
        if (!isEnabled(c)) return false
        if (token.isNullOrEmpty()) return false
        return constantTimeEquals(token, getToken(c))
    }

    /** 48 hex chars from 24 cryptographically-random bytes; URL-safe in a jami-cmd:// query. */
    private fun generateToken(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray()
        val bb = b.toByteArray()
        if (ab.size != bb.size) return false
        var r = 0
        for (i in ab.indices) r = r or (ab[i].toInt() xor bb[i].toInt())
        return r == 0
    }
}
