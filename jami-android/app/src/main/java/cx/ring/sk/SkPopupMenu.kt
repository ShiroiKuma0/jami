/*
 * 白い熊 fork (sk): the popup menu of this app. It is AppCompat's PopupMenu — its rows are drawn
 * by SkPillMenuItemView like those of every other menu — with one addition: a menu taller than
 * the room below its anchor is shown shifted up, so it uses the height of the screen instead of
 * scrolling (black-yellow theme: a menu never scrolls while the screen has room).
 */
package cx.ring.sk

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.appcompat.widget.PopupMenu

@SuppressLint("RestrictedApi")
class SkPopupMenu(private val context: Context, private val anchor: View) : PopupMenu(context, anchor) {
    private var tall: MenuPopupHelper? = null

    override fun show() {
        val offset = SkMenuPills.verticalOffset(context, anchor, menu)
        val builder = menu as? MenuBuilder
        if (offset == null || builder == null) {
            super.show()
            return
        }
        try {
            tall = MenuPopupHelper(context, builder, anchor).also { it.show(0, offset) }
        } catch (e: Throwable) {
            tall = null
            super.show()
        }
    }

    override fun dismiss() {
        try { tall?.dismiss() } catch (ignored: Throwable) {}
        tall = null
        super.dismiss()
    }
}
