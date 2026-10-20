/*
 *  shiroikuma.jami fork: the themed "pick one" list dialog — avatar, title, optional subtitle, and a
 *  filter box once the list is long. Lifted out of ShortcutPickerActivity so the decline-message
 *  settings can pick a contact with the very same dialog.
 */
package cx.ring.utils

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cx.ring.R
import kotlin.math.min

object PickListDialog {

    data class Item(
        val title: String,
        val subtitle: String?,
        val icon: Drawable?,
        val payload: Any
    )

    /** [onCancel] runs on Cancel and on back/outside; [onPick] after the dialog is dismissed. */
    fun show(ctx: Context, title: String, items: List<Item>, onCancel: (() -> Unit)? = null, onPick: (Item) -> Unit): AlertDialog {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_shortcut_pick, null)
        val filter = view.findViewById<EditText>(R.id.shortcut_pick_filter)
        val list = view.findViewById<RecyclerView>(R.id.shortcut_pick_list)

        var dialog: AlertDialog? = null
        val adapter = PickAdapter(items) { item ->
            dialog?.dismiss()
            onPick(item)
        }
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter

        val metrics = ctx.resources.displayMetrics
        val row = (64 * metrics.density).toInt()
        val maxHeight = (metrics.heightPixels * 0.55f).toInt()
        list.layoutParams.height = min(items.size * row, maxHeight).coerceAtLeast(row)

        filter.visibility = if (items.size > 8) View.VISIBLE else View.GONE
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
            }
        })

        return DialogTheme.builder(ctx)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
            .showThemed()
            .also { dialog = it }
    }

    private class PickAdapter(
        private val all: List<Item>,
        private val onPick: (Item) -> Unit
    ) : RecyclerView.Adapter<PickViewHolder>() {
        private var shown: List<Item> = all

        fun filter(query: String) {
            shown = if (query.isBlank()) all else all.filter {
                it.title.contains(query, true) || it.subtitle?.contains(query, true) == true
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PickViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_shortcut_pick, parent, false)
        )

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: PickViewHolder, position: Int) {
            val item = shown[position]
            holder.avatar.setImageDrawable(item.icon)
            holder.title.text = item.title
            holder.title.setTextColor(ColorPrefs.getColor(holder.title.context, ColorPrefs.LIST_NAME))
            FontUtil.apply(holder.title, FontPrefs.LIST_TITLE)
            holder.subtitle.text = item.subtitle
            holder.subtitle.visibility =
                if (item.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            holder.subtitle.setTextColor(ColorPrefs.getColor(holder.subtitle.context, ColorPrefs.LIST_PREVIEW))
            FontUtil.apply(holder.subtitle, FontPrefs.LIST_PREVIEW)
            holder.itemView.setOnClickListener { onPick(item) }
        }
    }

    private class PickViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.shortcut_pick_avatar)
        val title: TextView = view.findViewById(R.id.shortcut_pick_title)
        val subtitle: TextView = view.findViewById(R.id.shortcut_pick_subtitle)
    }
}
