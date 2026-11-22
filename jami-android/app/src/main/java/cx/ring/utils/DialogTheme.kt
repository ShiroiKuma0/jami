/*
 *  shiroikuma.jami fork: black/yellow chrome for MaterialAlertDialogs, matching the app's theme —
 *  black fill, 2dp yellow rounded border, yellow text + yellow-outlined buttons. Use
 *  DialogTheme.builder(ctx) to construct and .let { DialogTheme.theme(it, ctx) } after .show().
 */
package cx.ring.utils

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cx.ring.R

object DialogTheme {
    /** A builder pre-styled with the black-surface / yellow-text dialog theme. */
    fun builder(ctx: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(ctx, R.style.ShiroikumaDialog)

    /** theme() with the context taken from the dialog itself. */
    fun theme(dialog: AlertDialog): AlertDialog = theme(dialog, dialog.context)

    /** Attach the theme at show time — for create()-then-show-later patterns and DialogFragments.
     *  REPLACES any OnShowListener; where the site already sets one, call theme() inside it instead. */
    fun onShow(dialog: AlertDialog): AlertDialog {
        dialog.setOnShowListener { theme(dialog) }
        return dialog
    }

    /** Apply (after show) the dialog panel and the pill buttons, all from their colour roles: the
     *  positive button is the main pill (3 dp, bold), the others regular (1.25 dp); a dialog that
     *  deletes — [destructive], or one whose positive button says so — confirms with a red pill. */
    fun theme(dialog: AlertDialog, ctx: Context, destructive: Boolean? = null): AlertDialog {
        dialog.window?.setBackgroundDrawable(
            cx.ring.sk.skPanel(ctx.resources.displayMetrics.density, radiusDp = 16f).apply {
                setStroke((2 * ctx.resources.displayMetrics.density).toInt(), cx.ring.sk.skColor(cx.ring.sk.SkRole.DIALOG_FRAME))
            })
        intArrayOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)
            .forEach { which ->
                val b = dialog.getButton(which) as? androidx.appcompat.widget.AppCompatButton ?: return@forEach
                val main = which == DialogInterface.BUTTON_POSITIVE
                cx.ring.sk.SkViewInflater.stylePill(b, forceMain = main,
                    destructive = if (main) destructive else false)
            }
        return dialog
    }
}

/** A dialog that deletes: the same chrome, its confirm pill red. */
fun AlertDialog.Builder.showDestructive(): AlertDialog = DialogTheme.theme(show(), context, destructive = true)

/** Show any AlertDialog.Builder chain (Material or androidx) with the black/yellow chrome —
 *  the drop-in replacement for a chain-ending .show() (2026-07-24 border sweep: the global
 *  ShiroikumaDialog theme styles fill/text/buttons, but MaterialAlertDialogBuilder replaces the
 *  window background at create time, losing the 2dp yellow border — this puts it back). */
fun AlertDialog.Builder.showThemed(): AlertDialog = DialogTheme.theme(show())
