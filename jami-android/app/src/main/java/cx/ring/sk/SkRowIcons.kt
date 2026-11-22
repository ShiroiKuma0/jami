/*
 * 白い熊 fork (sk): icons of list rows, coloured by what the row does.
 *
 * A menu, a settings list or an action sheet is read by colour before it is read by word: call
 * and connect green, message and write blue, contact cyan, copy / share / export orange, block
 * magenta, delete red, and plain view / open / settings in the accent. Rows do not carry that
 * role, so it is told from a name that does not depend on the app's language: a view's id, a
 * preference's key, or the resource name of the icon itself.
 */
package cx.ring.sk

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceGroup

object SkRowIcons {
    private val DELETE = listOf("delete", "remove", "clear", "trash", "purge", "erase")
    private val BLOCK = listOf("block", "mute", "leave", "ban", "revoke", "logout", "lock", "password", "biometric", "disable", "cancel")
    private val CALL = listOf("call", "phone", "dial", "video", "camera", "connect", "ring", "mic", "speaker", "audio", "push", "dht", "proxy", "turn", "upnp", "network", "hwenc", "codec")
    private val MESSAGE = listOf("message", "chat", "typing", "write", "edit", "reply", "notification", "mail", "send", "document", "collab", "preview", "rename")
    private val CONTACT = listOf("account", "contact", "person", "people", "profile", "user", "member", "group", "device", "name", "identity")
    private val SHARE = listOf("share", "copy", "export", "import", "save", "download", "backup", "link", "qr", "location", "file", "record", "shortcut", "donat")

    fun roleForName(name: String?): SkIconRole {
        val n = name?.lowercase().orEmpty()
        fun any(words: List<String>) = words.any { it in n }
        return when {
            n.isEmpty() -> SkIconRole.VIEW
            any(DELETE) -> SkIconRole.DELETE
            any(BLOCK) -> SkIconRole.BLOCK
            any(CALL) -> SkIconRole.CALL
            any(MESSAGE) -> SkIconRole.MESSAGE
            any(CONTACT) -> SkIconRole.CONTACT
            any(SHARE) -> SkIconRole.SHARE
            else -> SkIconRole.VIEW
        }
    }

    /** The role of a view, from its id name ("system_typing_image" → message). */
    fun roleForView(view: View): SkIconRole = roleForName(idName(view))

    private fun idName(view: View): String? =
        if (view.id == View.NO_ID) null
        else try { view.resources.getResourceEntryName(view.id) } catch (e: Exception) { null }

    /** Tint a row's icon view by the role its id names. */
    fun tint(icon: ImageView) {
        icon.imageTintList = ColorStateList.valueOf(skColor(roleForView(icon)))
    }

    /** Colour the icon of every preference of a screen by the role its key names. */
    fun tintPreferences(group: PreferenceGroup?) {
        group ?: return
        for (i in 0 until group.preferenceCount) {
            val p: Preference = group.getPreference(i)
            p.icon?.mutate()?.setTint(skColor(roleForName(p.key)))
            if (p is PreferenceGroup) tintPreferences(p)
        }
    }
}
