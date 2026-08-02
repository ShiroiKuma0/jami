/*
 *  Copyright (C) 2004-2025 Savoir-faire Linux Inc.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package cx.ring.about

import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior
import android.app.Dialog
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import cx.ring.R
import cx.ring.utils.BottomSheetTheme
import com.google.android.material.bottomsheet.BottomSheetDialog

class AboutBottomSheetDialogFragment : BottomSheetDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = View.inflate(context, R.layout.dialog_about, null).apply {
        // shiroikuma: the section headings get the same treatment as the About screen's row labels —
        // yellow and bold from the layout, underlined here because there is no attribute for it.
        for (id in intArrayOf(R.id.credits_developers_title, R.id.credits_media_title,
                              R.id.credits_community_title, R.id.credits_thanks_title)) {
            findViewById<TextView>(id)?.apply { paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        (dialog as BottomSheetDialog).behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        return dialog
    }

    // shiroikuma: black sheet with a yellow border, like the fork's dialogs.
    override fun onStart() {
        super.onStart()
        BottomSheetTheme.apply(dialog)
    }
}