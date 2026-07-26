/*
 *  shiroikuma.jami fork: per-shortcut tokens for the exported shortcut trampoline.
 *
 *  ShortcutLaunchActivity has to be exported — a legacy-style launcher item is started with a plain
 *  startActivity from the launcher's own uid, so a non-exported target throws. To keep that exported
 *  surface from becoming a free "call anyone / open any chat" entry point for other apps, every
 *  shortcut carries a random token issued when it was created; the trampoline dispatches only when
 *  the token is one we issued. The token lives in the launcher's copy of the intent, which no
 *  ordinary app can read.
 */
package cx.ring.utils

import android.content.Context
import java.util.UUID

object ShortcutPrefs {
    private const val PREFS = "shiroikuma_shortcuts"
    private const val KEY_TOKENS = "tokens"
    private const val MAX_TOKENS = 200

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Mint a token for a shortcut being created and remember it. */
    fun issue(c: Context): String {
        val token = UUID.randomUUID().toString()
        // Deleting a launcher item tells us nothing, so tokens can only accumulate: keep the newest.
        val tokens = ArrayList(p(c).getStringSet(KEY_TOKENS, emptySet()) ?: emptySet())
        tokens.add(token)
        while (tokens.size > MAX_TOKENS) tokens.removeAt(0)
        p(c).edit().putStringSet(KEY_TOKENS, tokens.toSet()).apply()
        return token
    }

    fun isValid(c: Context, token: String?): Boolean =
        token != null && p(c).getStringSet(KEY_TOKENS, emptySet())?.contains(token) == true
}
