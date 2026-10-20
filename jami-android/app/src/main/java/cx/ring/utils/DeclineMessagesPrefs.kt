package cx.ring.utils

import android.content.Context
import cx.ring.R
import org.json.JSONArray

/**
 * shiroikuma: the canned replies offered when an incoming call is declined with a message.
 *
 * Two kinds of list, both ORDERED (hence JSON arrays rather than a StringSet):
 *  · the general list, offered for every caller;
 *  · a per-contact list, keyed by the caller's bare ring id — stable across renames and identical on
 *    all of our accounts, so a contact keeps their own replies whichever account they call.
 *
 * The picker shows the contact's own list first, then the general one ([listFor]). The contact's
 * last-known display name is stored beside the list only so the settings page can label it without
 * an asynchronous profile lookup.
 *
 * The general list is seeded once, on first read, with four defaults in the current locale; after
 * that they are ordinary items. Deleting every item leaves an empty list and is never re-seeded.
 * SharedPreferences-backed object, no DI; exported with the app settings (see SettingsExport).
 */
object DeclineMessagesPrefs {
    private const val PREFS = "shiroikuma_decline"
    private const val KEY_GLOBAL = "global"
    private const val CONTACT_PREFIX = "contact."
    private const val NAME_PREFIX = "name."

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun norm(id: String) = id.trim().lowercase()

    private fun read(c: Context, key: String): MutableList<String> {
        val raw = p(c).getString(key, null) ?: return mutableListOf()
        return try {
            val a = JSONArray(raw)
            MutableList(a.length()) { a.optString(it) }.filter { it.isNotBlank() }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun write(c: Context, key: String, list: List<String>) {
        p(c).edit().putString(key, JSONArray(list).toString()).apply()
    }

    // --- general list ------------------------------------------------------------------------

    fun global(c: Context): List<String> {
        if (!p(c).contains(KEY_GLOBAL)) {
            val seed = listOf(
                c.getString(R.string.sk_decline_default_1),
                c.getString(R.string.sk_decline_default_2),
                c.getString(R.string.sk_decline_default_3),
                c.getString(R.string.sk_decline_default_4))
            write(c, KEY_GLOBAL, seed)
            return seed
        }
        return read(c, KEY_GLOBAL)
    }

    fun setGlobal(c: Context, list: List<String>) = write(c, KEY_GLOBAL, list.filter { it.isNotBlank() })

    // --- per-contact lists -------------------------------------------------------------------

    fun forContact(c: Context, contactId: String): List<String> =
        read(c, CONTACT_PREFIX + norm(contactId))

    /** Writes the contact's list; an empty list removes the contact (and its stored name). */
    fun setForContact(c: Context, contactId: String, list: List<String>) {
        val id = norm(contactId)
        val clean = list.filter { it.isNotBlank() }
        if (clean.isEmpty()) {
            p(c).edit().remove(CONTACT_PREFIX + id).remove(NAME_PREFIX + id).apply()
        } else {
            write(c, CONTACT_PREFIX + id, clean)
        }
    }

    fun contactName(c: Context, contactId: String): String? =
        p(c).getString(NAME_PREFIX + norm(contactId), null)?.takeIf { it.isNotBlank() }

    fun setContactName(c: Context, contactId: String, name: String) {
        if (name.isNotBlank()) p(c).edit().putString(NAME_PREFIX + norm(contactId), name).apply()
    }

    /** Ring ids of every contact that has a list of its own. */
    fun contactsWithOwn(c: Context): List<String> =
        p(c).all.keys.filter { it.startsWith(CONTACT_PREFIX) }
            .map { it.removePrefix(CONTACT_PREFIX) }
            .filter { forContact(c, it).isNotEmpty() }
            .sortedBy { (contactName(c, it) ?: it).lowercase() }

    /** What the picker offers: the contact's own replies, then the general ones not already shown. */
    fun listFor(c: Context, contactId: String?): Pair<List<String>, List<String>> {
        val own = contactId?.let { forContact(c, it) } ?: emptyList()
        return own to global(c).filter { it !in own }
    }

    /** Appends [text] to the general list or to the contact's list, unless it is already there. */
    fun addGlobal(c: Context, text: String) {
        val l = global(c).toMutableList()
        if (text.isNotBlank() && text !in l) { l.add(text); setGlobal(c, l) }
    }

    fun addForContact(c: Context, contactId: String, text: String) {
        val l = forContact(c, contactId).toMutableList()
        if (text.isNotBlank() && text !in l) { l.add(text); setForContact(c, contactId, l) }
    }
}
