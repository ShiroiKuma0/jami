package cx.ring.utils

import android.content.Context

/**
 * Persisted set of "protected" contact identifiers, set exclusively via the automation intent
 * [cx.ring.automation.AutomationActivity] (action SET_PROTECTED_CONTACTS) from the companion app
 * (白い熊 自由作業盤, shiroikuma.jiyusagyoban).
 *
 * Entries are the contact's **registered name** (the companion's format) — or a bare ring id. Both
 * are matched case-insensitively against the incoming sender, whose ring id ([net.jami.model.Uri.uri])
 * and resolved registered name ([net.jami.model.ContactViewModel.registeredName]) are both checked in
 * the notification path. When a sender matches, the notification is built vague + marked instead of
 * the normal one (see cx.ring.services.NotificationServiceImpl). Survives restart.
 *
 * These ids are the user's private data — never log them. SharedPreferences-backed object, no DI.
 */
object ProtectedContactsPrefs {
    private const val PREFS = "shiroikuma_protected"
    private const val KEY = "protected_contacts"
    private const val KEY_TITLE = "protected_title"
    private const val KEY_BODY = "protected_body"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Registered names and ring ids are case-insensitive; everything is stored normalized. */
    private fun norm(s: String) = s.trim().lowercase()

    private fun raw(c: Context): Set<String> = p(c).getStringSet(KEY, emptySet()) ?: emptySet()

    fun isProtected(c: Context, id: String): Boolean {
        val n = norm(id)
        return n.isNotEmpty() && raw(c).contains(n)
    }

    /** The whole stored set (normalized/lowercase) — the companion's GET read-back channel. */
    fun getAll(c: Context): Set<String> = raw(c)

    private fun store(c: Context, ids: Set<String>) {
        p(c).edit().putStringSet(KEY, ids).apply()
    }

    private fun normalized(ids: Collection<String>): MutableSet<String> =
        ids.map(::norm).filter { it.isNotEmpty() }.toMutableSet()

    /** Replace the whole set; an empty collection clears it. */
    fun replace(c: Context, ids: Collection<String>) = store(c, normalized(ids))

    fun add(c: Context, ids: Collection<String>) =
        store(c, HashSet(raw(c)).apply { addAll(normalized(ids)) })

    fun remove(c: Context, ids: Collection<String>) =
        store(c, HashSet(raw(c)).apply { removeAll(normalized(ids)) })

    // --- Vague-notification title/body (companion-controlled, optional) --------------------------
    /** Companion-supplied vague title; blank/unset → null so the caller uses the app-name default. */
    fun getTitle(c: Context): String? = p(c).getString(KEY_TITLE, null)?.takeIf { it.isNotBlank() }

    /** Companion-supplied vague body; blank/unset → null so the caller uses its default. */
    fun getBody(c: Context): String? = p(c).getString(KEY_BODY, null)?.takeIf { it.isNotBlank() }

    /**
     * Set (or clear) the vague-notification title/body. Blank/null clears the value → the default is
     * used. Each SET_PROTECTED_CONTACTS reflects the current desired state, so an absent extra clears
     * the prior value (there is no separate clear path). Companion-private text — never logged.
     */
    fun setText(c: Context, title: String?, body: String?) {
        p(c).edit().apply {
            if (title.isNullOrBlank()) remove(KEY_TITLE) else putString(KEY_TITLE, title.trim())
            if (body.isNullOrBlank()) remove(KEY_BODY) else putString(KEY_BODY, body.trim())
        }.apply()
    }
}
