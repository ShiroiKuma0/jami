package cx.ring.utils

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import cx.ring.databinding.FragInvitationCardBinding

/**
 * Black/yellow theming for the invitation surfaces (2026-10-31). Upstream draws them light blue
 * with black text (the "Invitations received" card and its expanded list) and a grey bar with a
 * green ACCEPT (an incoming request). Every colour is a settable [ColorPrefs] role.
 */
object InviteTheme {

    /** A rounded black fill with a 2dp border — the same shape every other themed card uses. */
    fun paint(view: View, fillRole: String, borderRole: String, radiusDp: Float) {
        val c = view.context
        val d = c.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * d
            setColor(ColorPrefs.getColor(c, fillRole))
            setStroke((2f * d).toInt(), ColorPrefs.getColor(c, borderRole))
        }
    }

    fun applyCard(b: FragInvitationCardBinding) {
        val c = b.root.context
        val text = ColorPrefs.getColor(c, ColorPrefs.INVITE_TEXT)
        paint(b.invitationGroup, ColorPrefs.INVITE_FILL, ColorPrefs.INVITE_BORDER, 16f)
        b.invitationReceivedLabel.setTextColor(text)
        b.invitationReceivedTxt.setTextColor(text)
        toolbar(b.pendingToolbar, text)
    }

    private fun toolbar(t: MaterialToolbar, color: Int) {
        t.setTitleTextColor(color)
        t.setNavigationIconTint(color)
    }

    fun applyRequestBar(card: View, buttons: List<TextView>) {
        paint(card, ColorPrefs.REQUEST_FILL, ColorPrefs.REQUEST_BORDER, 24f)
        val text = ColorPrefs.getColor(card.context, ColorPrefs.REQUEST_TEXT)
        buttons.forEach { it.setTextColor(text) }
    }
}
