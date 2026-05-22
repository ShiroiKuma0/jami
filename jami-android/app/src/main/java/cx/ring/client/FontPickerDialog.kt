package cx.ring.client

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cx.ring.R
import cx.ring.utils.FontPrefs
import cx.ring.utils.FontUtil

/** Reusable font picker: family + weight + size, with live preview and reset. */
object FontPickerDialog {
    private const val SEEK_MAX = 72

    fun show(context: Context, category: String, title: String, onChanged: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_font_picker, null)
        val preview = view.findViewById<TextView>(R.id.font_preview)
        val familySpinner = view.findViewById<Spinner>(R.id.font_family_spinner)
        val weightSpinner = view.findViewById<Spinner>(R.id.font_weight_spinner)
        val sizeSeek = view.findViewById<SeekBar>(R.id.font_size_seek)
        val sizeValue = view.findViewById<EditText>(R.id.font_size_value)

        val familyValues = ArrayList<String>()
        val familyLabels = ArrayList<String>()
        for ((label, value) in FontUtil.FAMILIES) { familyLabels.add(label); familyValues.add(value) }
        for (f in FontPrefs.getFontFiles(context)) { familyLabels.add("File: ${f.name}"); familyValues.add("file:${f.absolutePath}") }
        familySpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, familyLabels)

        val weightValues = FontUtil.WEIGHTS.map { it.second }
        weightSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, FontUtil.WEIGHTS.map { it.first })

        val curFamily = FontPrefs.getFamily(context, category)
        val curWeight = FontPrefs.getWeight(context, category)
        val curSize = FontPrefs.getSize(context, category)
        familySpinner.setSelection(familyValues.indexOf(curFamily).coerceAtLeast(0))
        weightSpinner.setSelection(weightValues.indexOf(curWeight).coerceAtLeast(0))
        sizeSeek.max = SEEK_MAX
        sizeSeek.progress = curSize.toInt().coerceIn(0, SEEK_MAX)
        sizeValue.setText(if (curSize > 0f) curSize.toInt().toString() else "")

        fun currentSize(): Float = sizeValue.text.toString().toFloatOrNull() ?: 0f
        fun refreshPreview() {
            val fam = familyValues[familySpinner.selectedItemPosition]
            val wt = weightValues[weightSpinner.selectedItemPosition]
            preview.typeface = FontUtil.resolveTypeface(fam, wt) ?: Typeface.DEFAULT
            val s = currentSize()
            preview.textSize = if (s > 0f) s else 18f
        }

        val selListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshPreview()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        familySpinner.onItemSelectedListener = selListener
        weightSpinner.onItemSelectedListener = selListener
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) sizeValue.setText(if (progress > 0) progress.toString() else "")
                refreshPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sizeValue.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().toIntOrNull() ?: 0
                if (v in 0..SEEK_MAX && v != sizeSeek.progress) sizeSeek.progress = v
                refreshPreview()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        refreshPreview()

        MaterialAlertDialogBuilder(context, R.style.ShiroikumaDialog)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("OK") { _, _ ->
                val fam = familyValues[familySpinner.selectedItemPosition]
                val wt = weightValues[weightSpinner.selectedItemPosition]
                FontPrefs.setFont(context, category, fam, wt, currentSize().coerceIn(0f, 400f))
                onChanged()
            }
            .setNeutralButton("Reset") { _, _ ->
                FontPrefs.setFont(context, category, "", 0, 0f)
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show().apply {
                window?.setBackgroundDrawable(context.getDrawable(R.drawable.dialog_black_yellow))
            }
    }
}
