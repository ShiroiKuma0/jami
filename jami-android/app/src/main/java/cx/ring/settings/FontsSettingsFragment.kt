package cx.ring.settings

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import cx.ring.R
import cx.ring.client.FontPickerDialog
import cx.ring.utils.FontPrefs
import cx.ring.utils.FontUtil

class FontsSettingsFragment : Fragment() {
    private val categories = listOf(
        FontPrefs.DEFAULT to "Default (all text)",
        FontPrefs.CHAT_TEXT to "Chat text",
        FontPrefs.CONV_TITLE to "Conversation title",
        FontPrefs.LIST_TITLE to "Chat-list contact name",
        FontPrefs.LIST_PREVIEW to "Chat-list preview",
        FontPrefs.SETTINGS to "Settings",
    )
    private var container: LinearLayout? = null

    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        try {
            val name = queryName(uri) ?: "font.ttf"
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@registerForActivityResult
            FontPrefs.addFontFile(ctx, name, bytes)
            Toast.makeText(ctx, "Added $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.frag_fonts_settings, parent, false)
        container = root.findViewById(R.id.fonts_container)
        root.findViewById<View>(R.id.fonts_add_custom).setOnClickListener {
            pickFont.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream", "*/*"))
        }
        rebuild()
        return root
    }

    private fun rebuild() {
        val ctx = context ?: return
        val c = container ?: return
        c.removeAllViews()
        val pad = (16 * resources.displayMetrics.density).toInt()
        for ((cat, label) in categories) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                val tv = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { FontPickerDialog.show(ctx, cat, label) { rebuild() } }
            }
            row.addView(TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(0xFFFFFF00.toInt())
            })
            row.addView(TextView(ctx).apply {
                text = FontUtil.describe(ctx, cat)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFFAAAAAA.toInt())
            })
            c.addView(row)
        }
    }

    private fun queryName(uri: Uri): String? {
        val c = context ?: return null
        c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cur.moveToFirst()) return cur.getString(idx)
        }
        return null
    }
}
