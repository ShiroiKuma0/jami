package cx.ring.settings

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import cx.ring.R
import cx.ring.utils.DeclineMessagesPrefs
import cx.ring.utils.DialogTheme
import cx.ring.utils.showThemed

/**
 * shiroikuma: dialogs that edit a list of decline messages — the general list ([contactId] null) or
 * one contact's own list. Shared by the "白い熊 GNU Jami UI" settings page and the contact's details
 * screen, so both edit through exactly the same code. [onChanged] runs after every write.
 */
object DeclineMessagesEditor {
    private const val YELLOW = 0xFFFFFF00.toInt()

    private fun load(ctx: Context, contactId: String?): List<String> =
        if (contactId == null) DeclineMessagesPrefs.global(ctx) else DeclineMessagesPrefs.forContact(ctx, contactId)

    private fun store(ctx: Context, contactId: String?, list: List<String>) {
        if (contactId == null) DeclineMessagesPrefs.setGlobal(ctx, list)
        else DeclineMessagesPrefs.setForContact(ctx, contactId, list)
    }

    /** The whole list in one dialog: tap a message for its actions, "+ Add message" to add one. */
    fun showList(ctx: Context, contactId: String?, contactName: String?, onChanged: () -> Unit = {}) {
        if (contactId != null && contactName != null) DeclineMessagesPrefs.setContactName(ctx, contactId, contactName)
        val list = load(ctx, contactId)
        val title = if (contactId == null) ctx.getString(R.string.sk_decline_editor_title)
            else ctx.getString(R.string.sk_decline_editor_contact_title, contactName ?: contactId.take(8))
        val again = { showList(ctx, contactId, contactName, onChanged) }
        val b = DialogTheme.builder(ctx).setTitle(title)
        if (list.isEmpty()) b.setMessage(R.string.sk_decline_editor_empty)
        else b.setItems(list.toTypedArray()) { _, which -> showItemActions(ctx, contactId, which, onChanged, again) }
        b.setPositiveButton(R.string.sk_decline_add) { _, _ ->
            showEdit(ctx, ctx.getString(R.string.sk_decline_add), "") { text ->
                val l = load(ctx, contactId).toMutableList()
                if (text !in l) l.add(text)
                store(ctx, contactId, l); onChanged(); again()
            }
        }
        if (contactId != null && list.isNotEmpty())
            b.setNeutralButton(R.string.sk_decline_delete_all) { _, _ ->
                store(ctx, contactId, emptyList()); onChanged()
            }
        b.setNegativeButton(android.R.string.ok, null)
        b.showThemed()
    }

    /** Edit / Move up / Move down / Delete for the message at [index]; [then] re-opens the list. */
    fun showItemActions(ctx: Context, contactId: String?, index: Int, onChanged: () -> Unit, then: () -> Unit = {}) {
        val list = load(ctx, contactId)
        if (index !in list.indices) return
        val actions = arrayOf(
            ctx.getString(R.string.sk_decline_edit),
            ctx.getString(R.string.sk_decline_move_up),
            ctx.getString(R.string.sk_decline_move_down),
            ctx.getString(R.string.sk_decline_delete))
        DialogTheme.builder(ctx)
            .setTitle(list[index])
            .setItems(actions) { _, which ->
                val l = load(ctx, contactId).toMutableList()
                if (index !in l.indices) return@setItems
                when (which) {
                    0 -> {
                        showEdit(ctx, ctx.getString(R.string.sk_decline_edit), l[index]) { text ->
                            val m = load(ctx, contactId).toMutableList()
                            if (index in m.indices) { m[index] = text; store(ctx, contactId, m); onChanged() }
                            then()
                        }
                        return@setItems
                    }
                    1 -> if (index > 0) { l.add(index - 1, l.removeAt(index)) }
                    2 -> if (index < l.size - 1) { l.add(index + 1, l.removeAt(index)) }
                    3 -> l.removeAt(index)
                }
                store(ctx, contactId, l); onChanged(); then()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> then() }
            .showThemed()
    }

    /** A multi-line text box; OK with blank text is ignored. */
    fun showEdit(ctx: Context, title: String, initial: String, onOk: (String) -> Unit) {
        val density = ctx.resources.displayMetrics.density
        val input = EditText(ctx).apply {
            setText(initial)
            setSelection(initial.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            setTextColor(YELLOW)
            setHintTextColor(0xFF777777.toInt())
            hint = ctx.getString(R.string.sk_decline_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        DialogTheme.builder(ctx)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onOk(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showThemed()
    }
}
