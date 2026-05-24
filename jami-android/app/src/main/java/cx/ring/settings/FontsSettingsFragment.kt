package cx.ring.settings

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cx.ring.R
import cx.ring.utils.FontPrefs
import cx.ring.utils.FontUtil

/** "UI fonts & colors" — grouped, inline per-element font controls (App Manager treatment). */
class FontsSettingsFragment : Fragment() {
    private data class Element(val category: String, val label: String)
    private data class Group(val title: String, val elements: List<Element>)

    private val groups = listOf(
        Group("Default", listOf(Element(FontPrefs.DEFAULT, "Default (all text)"))),
        Group("Chat list", listOf(
            Element(FontPrefs.LIST_TITLE, "Contact name"),
            Element(FontPrefs.LIST_PREVIEW, "Last-message preview"))),
        Group("Conversation", listOf(
            Element(FontPrefs.CONV_TITLE, "Conversation title"),
            Element(FontPrefs.CHAT_TEXT, "Message text"))),
        Group("Settings", listOf(
            Element(FontPrefs.SETTINGS, "Settings text"))),
    )

    private val yellow = 0xFFFFFF00.toInt()
    private val grey = 0xFFAAAAAA.toInt()
    private val sample = "AaIiMmOoQqWw 012 白い熊相撲道 áÁčČďĎéÉěĚíÍňŇóÓřŘšŠ"

    private var container: LinearLayout? = null
    private var pendingCategory: String? = null

    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val cat = pendingCategory
        pendingCategory = null
        uri ?: return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        try {
            val name = queryName(uri) ?: "font.ttf"
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@registerForActivityResult
            val file = FontPrefs.addFontFile(ctx, name, bytes)
            if (cat != null) FontPrefs.setFont(ctx, cat, "file:${file.absolutePath}",
                FontPrefs.getWeight(ctx, cat), FontPrefs.getSize(ctx, cat))
            Toast.makeText(ctx, "Added $name", Toast.LENGTH_SHORT).show()
            rebuild()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.frag_fonts_settings, parent, false)
        container = root.findViewById(R.id.fonts_container)
        rebuild()
        return root
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun rebuild() {
        val c = container ?: return
        c.removeAllViews()
        for (g in groups) {
            c.addView(groupHeader(g.title))
            for (e in g.elements) c.addView(elementView(e))
        }
    }

    private fun groupHeader(title: String): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(16f), dp(20f), dp(16f), dp(4f))
            addView(TextView(ctx).apply {
                text = title
                setTextColor(yellow)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(View(ctx).apply {
                setBackgroundColor(yellow)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2f))
                    .apply { topMargin = dp(2f) }
            })
        }
    }

    private fun elementView(e: Element): View {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(16f), dp(12f), dp(16f), dp(4f))
        }
        // sub-heading with text-width underline (wrap_content wrapper)
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(ctx).apply {
                text = e.label
                setTextColor(yellow)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(View(ctx).apply {
                setBackgroundColor(yellow)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2f))
            })
        })
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(12f), dp(6f), 0, 0)
        }
        controls.addView(miniLabel("Font"))
        controls.addView(valueRow(
            FontPrefs.getFamily(ctx, e.category).let {
                if (it.isEmpty()) "Inherit (default)" else FontUtil.labelForFamily(it)
            }) { showFamilyPicker(e.category) })
        controls.addView(miniLabel("weight"))
        val w = FontPrefs.getWeight(ctx, e.category)
        controls.addView(valueRow(if (w == 0) "Inherit (default)" else FontUtil.labelForWeight(w)) {
            showWeightPicker(e.category)
        })
        controls.addView(miniLabel("size"))
        val size = FontPrefs.getSize(ctx, e.category)
        val sizeValue = TextView(ctx).apply {
            text = if (size > 0f) "${size.toInt()} sp" else "default"
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(2f), 0, dp(2f))
            isClickable = true
            setOnClickListener { showSizeDialog(e.category) }
        }
        controls.addView(sizeValue)
        val preview = TextView(ctx).apply {
            text = sample
            setTextColor(yellow)
            val eff = FontPrefs.effectiveSize(ctx, e.category)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (eff > 0f) eff else 16f)
            typeface = FontUtil.resolveTypeface(
                FontPrefs.effectiveFamily(ctx, e.category),
                FontPrefs.effectiveWeight(ctx, e.category)) ?: Typeface.DEFAULT
            setPadding(0, dp(4f), 0, dp(2f))
        }
        controls.addView(preview)
        controls.addView(SeekBar(ctx).apply {
            max = 96
            progress = if (size > 0f) size.toInt().coerceIn(0, 96) else 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val ns = if (p < 8) 0f else p.toFloat()
                    FontPrefs.setFont(ctx, e.category, FontPrefs.getFamily(ctx, e.category),
                        FontPrefs.getWeight(ctx, e.category), ns)
                    sizeValue.text = if (ns > 0f) "${ns.toInt()} sp" else "default"
                    val eff = FontPrefs.effectiveSize(ctx, e.category)
                    preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (eff > 0f) eff else 16f)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        col.addView(controls)
        return col
    }

    private fun miniLabel(t: String) = TextView(requireContext()).apply {
        text = t
        setTextColor(grey)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(6f), 0, 0)
    }

    private fun valueRow(value: String, onClick: () -> Unit) = TextView(requireContext()).apply {
        text = value
        setTextColor(yellow)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, dp(2f), 0, dp(2f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private data class Opt(val label: String, val value: String, val addMarker: Boolean)

    private fun showFamilyPicker(cat: String) {
        val ctx = context ?: return
        val weight = FontPrefs.effectiveWeight(ctx, cat)
        val opts = ArrayList<Opt>()
        for ((label, value) in FontUtil.FAMILIES) opts.add(Opt(label, value, false))
        for (f in FontPrefs.getFontFiles(ctx)) opts.add(Opt(f.name, "file:${f.absolutePath}", false))
        opts.add(Opt("Add custom font…", "", true))
        val adapter = object : ArrayAdapter<Opt>(ctx, android.R.layout.simple_list_item_1, opts) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                val o = opts[position]
                tv.text = o.label
                tv.setTextColor(yellow)
                tv.typeface = if (o.addMarker) Typeface.DEFAULT
                    else FontUtil.resolveTypeface(o.value, weight) ?: Typeface.DEFAULT
                return tv
            }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Font")
            .setAdapter(adapter) { _, which ->
                val o = opts[which]
                if (o.addMarker) {
                    pendingCategory = cat
                    pickFont.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*"))
                } else {
                    FontPrefs.setFont(ctx, cat, o.value, FontPrefs.getWeight(ctx, cat), FontPrefs.getSize(ctx, cat))
                    rebuild()
                }
            }
            .show()
    }

    private fun showWeightPicker(cat: String) {
        val ctx = context ?: return
        val labels = FontUtil.WEIGHTS.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Weight")
            .setItems(labels) { _, which ->
                FontPrefs.setFont(ctx, cat, FontPrefs.getFamily(ctx, cat),
                    FontUtil.WEIGHTS[which].second, FontPrefs.getSize(ctx, cat))
                rebuild()
            }
            .show()
    }

    private fun showSizeDialog(cat: String) {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "sp (blank = default)"
            val s = FontPrefs.getSize(ctx, cat)
            if (s > 0f) setText(s.toInt().toString())
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Size")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = (input.text.toString().toFloatOrNull() ?: 0f).coerceIn(0f, 300f)
                FontPrefs.setFont(ctx, cat, FontPrefs.getFamily(ctx, cat), FontPrefs.getWeight(ctx, cat), v)
                rebuild()
            }
            .setNeutralButton("Default") { _, _ ->
                FontPrefs.setFont(ctx, cat, FontPrefs.getFamily(ctx, cat), FontPrefs.getWeight(ctx, cat), 0f)
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
