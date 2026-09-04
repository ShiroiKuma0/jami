package cx.ring.utils

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Settings for the external-automation surface (see [cx.ring.automation.AutomationActivity],
 * [cx.ring.receivers.StateExportReceiver] and [cx.ring.automation.AutomationProvider]).
 *
 * ## v2 — the switch is ON and the token is OFF (sister-app contract v2, 2026-09-04)
 *
 * v1 shipped closed: automation defaulted to disabled and every caller had to present a
 * 48-character secret 白い熊 had pasted out of this app's settings. That is the wrong shape for
 * where this family went. **A pasted secret cannot survive a wipe**, and the case the contract now
 * exists to serve is 応用管理 restoring apps *and their data* onto a clean phone, where nothing has
 * been configured and nobody has pasted anything. A gate that only works once the phone is already
 * set up is no gate for setting the phone up.
 *
 * So `enabled` now defaults to **true** and the new `require_token` defaults to **false**. The
 * token itself is unchanged — still 24 `SecureRandom` bytes hex-encoded, still generated lazily,
 * still never in an export.
 *
 * ## The one exception: operations that ACT AS 白い熊
 *
 * The contract's justification for defaulting the token off is clean-phone restore, and it covers
 * reading and restoring this app's own data completely. It has **no force** for sending a message
 * or placing a call: restoring a wiped phone never requires sending a message from it.
 *
 * That matters more here than in any other app in the family. A message sent through the
 * automation surface is indistinguishable from one 白い熊 typed, and a placed call opens the
 * microphone and rings a real contact — that is impersonation, not data access. Nor does §2a's
 * caller verification reach those paths: package name, uid and pinned-certificate checks live on
 * the [cx.ring.automation.AutomationProvider], while the acting operations arrive at an exported
 * Activity and an exported receiver, which have no caller identity check of any kind.
 *
 * Hence [refuse]'s `acting` flag: those operations require the token **regardless** of the switch,
 * so relaxing the gate for backups never quietly opens a channel that speaks as 白い熊.
 * See [cx.ring.automation.AutomationActivity] for which operations set it.
 */
object AutomationPrefs {
    private const val PREFS = "shiroikuma_automation"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_REQUIRE_TOKEN = "require_token"
    private const val KEY_TOKEN = "token"
    private const val HEX = "0123456789abcdef"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The master switch. **Default ON** since v2 — the only way to close this app off entirely. */
    fun isEnabled(c: Context): Boolean = p(c).getBoolean(KEY_ENABLED, true)

    fun setEnabled(c: Context, enabled: Boolean) {
        p(c).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 「Use authorization token?」 — **default OFF**. When off, a token sent anyway is ignored. */
    fun isTokenRequired(c: Context): Boolean = p(c).getBoolean(KEY_REQUIRE_TOKEN, false)

    fun setTokenRequired(c: Context, required: Boolean) {
        p(c).edit().putBoolean(KEY_REQUIRE_TOKEN, required).apply()
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

    /**
     * The whole gate, in one place. `null` = proceed; otherwise the exact `ERROR:` string to answer.
     *
     * One function rather than two checks at each entry point, because two checks written out at
     * five call sites is how "disabled" and "bad token" drift apart.
     *
     * **A token handed to an app that does not require one is IGNORED, never an error.** Tokens
     * live in task arguments and workspace variables that outlive the setting they were pasted for;
     * a caller still sending one — because it was configured last year, or because another app on
     * the batch does want one — must be served. Refusing it would turn "白い熊 turned a switch off"
     * into "half the batch mysteriously fails", which is the friction the switch exists to remove.
     *
     * @param acting the request would act as 白い熊 (send a message, place a call) rather than read
     *   or restore this app's own data. Those always require the token — see the class note.
     */
    fun refuse(c: Context, candidate: String?, acting: Boolean = false): String? = when {
        !isEnabled(c) -> "ERROR:automation disabled"
        (acting || isTokenRequired(c)) && !isTokenValid(c, candidate) -> "ERROR:bad token"
        else -> null
    }

    /** Constant-time compare, kept for the case where the token actually is required. */
    fun isTokenValid(c: Context, token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return MessageDigest.isEqual(token.toByteArray(), getToken(c).toByteArray())
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
}
