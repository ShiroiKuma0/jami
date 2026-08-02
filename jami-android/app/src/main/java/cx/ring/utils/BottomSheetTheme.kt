/*
 *  shiroikuma.jami fork: black/yellow chrome for BottomSheetDialogs, matching the app's dialogs —
 *  black fill, 2dp yellow border, rounded top corners. Call BottomSheetTheme.apply(dialog) from a
 *  BottomSheetDialogFragment's onStart() (or from a plain BottomSheetDialog's show listener).
 */
package cx.ring.utils

import android.app.Dialog
import android.graphics.drawable.Drawable
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import cx.ring.R

object BottomSheetTheme {

    /**
     * Paint the sheet container black with a yellow border, or with [background] when a sheet wants
     * a look of its own (the audio recorder's frosted panel).
     *
     * The background has to go on the sheet container rather than on the fragment's own root: a
     * BottomSheetDialog paints that container itself from the theme's tonal surface colour, so a
     * background on our root would still leave grey visible around the rounded corners.
     *
     * BottomSheetBehavior installs its own MaterialShapeDrawable on the container during its first
     * layout pass — which happens *after* onStart() and after a show listener runs — so the
     * drawable is applied both immediately and again from a layout listener; the identity check
     * keeps that a no-op once ours is in place.
     */
    fun apply(dialog: Dialog?, background: Drawable? = null) {
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        val wanted = background
            ?: AppCompatResources.getDrawable(sheet.context, R.drawable.bottomsheet_black_yellow)
            ?: return
        val paint = {
            if (sheet.background !== wanted) {
                sheet.backgroundTintList = null
                sheet.background = wanted
            }
        }
        paint()
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> paint() }
    }
}
