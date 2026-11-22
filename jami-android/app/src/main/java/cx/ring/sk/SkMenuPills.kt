/*
 * 白い熊 fork (sk): what a menu row needs to know to be drawn by the black-yellow theme's menu
 * rule (SkPillMenuItemView): its icon when the menu resource gave it none, whether it opens
 * something (a › at the right end), and what it does — which colours its icon. Menu items carry
 * none of that, so this table supplies it by item id.
 *
 * Frame and label of a row stay in the menu colours — the danger role for a row that deletes —
 * while the icon tells what the row does: call / connect green, message / write blue, contact
 * cyan, copy / share orange, view / open the accent, block magenta, delete red. Each is a
 * settable role (UI page → Menus).
 *
 * A new menu item: add a line to ROWS. One without a line still becomes a pill, with a
 * view-coloured icon column and no ›.
 */
package cx.ring.sk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import cx.ring.R

@SuppressLint("RestrictedApi")
object SkMenuPills {
    const val TOGGLE_HEIGHT_DP = 36 // a toggle row: the switch and little more
    const val PILL_MARGIN_DP = 3 // above and below a pill
    const val SIDE_MARGIN_DP = 8 // from the pill to the edge of the menu

    class Row(val role: SkIconRole, val opens: Boolean = false, val glyph: SkGlyph? = null)

    private val ROWS: Map<Int, Row> = mapOf(
        // the + of the message bar
        R.id.conv_share_location to Row(SkIconRole.SHARE, true),
        R.id.conv_send_video to Row(SkIconRole.MESSAGE, true),
        R.id.conv_select_media to Row(SkIconRole.MESSAGE, true),
        R.id.conv_send_file to Row(SkIconRole.MESSAGE, true),
        R.id.conv_new_document to Row(SkIconRole.MESSAGE, true),
        R.id.chat_extensions to Row(SkIconRole.VIEW, true),
        // the ⋮ of the chat list
        R.id.menu_account_settings to Row(SkIconRole.CONTACT, true),
        R.id.menu_advanced_settings to Row(SkIconRole.VIEW, true),
        R.id.menu_ui_fonts_colors to Row(SkIconRole.VIEW, true, SkGlyph.DROP),
        R.id.menu_create_shortcut to Row(SkIconRole.SHARE, true, SkGlyph.HOME),
        R.id.menu_donate to Row(SkIconRole.VIEW, true),
        R.id.menu_about to Row(SkIconRole.VIEW, true),
        R.id.menu_split_view to Row(SkIconRole.VIEW, glyph = SkGlyph.SPLIT),
        R.id.menu_location_sharing to Row(SkIconRole.SHARE, true, SkGlyph.LOCATION),
        // a conversation's top bar
        R.id.conv_action_peer_services to Row(SkIconRole.VIEW, true),
        R.id.conv_action_audiocall to Row(SkIconRole.CALL),
        R.id.conv_action_videocall to Row(SkIconRole.CALL),
        R.id.conv_conversation_details to Row(SkIconRole.CONTACT, true),
        R.id.conv_search to Row(SkIconRole.VIEW, true),
        R.id.contact_search to Row(SkIconRole.VIEW, true),
        // a file or a picture
        R.id.conv_action_open to Row(SkIconRole.VIEW, true),
        R.id.conv_action_share to Row(SkIconRole.SHARE, true),
        R.id.conv_action_download to Row(SkIconRole.SHARE),
        R.id.conv_action_delete to Row(SkIconRole.DELETE),
        // logs
        R.id.menu_log_crashes to Row(SkIconRole.VIEW, true),
        R.id.menu_log_share to Row(SkIconRole.SHARE, true),
        R.id.menu_log_save to Row(SkIconRole.SHARE),
        R.id.menu_clear to Row(SkIconRole.DELETE),
        R.id.menu_share to Row(SkIconRole.SHARE, true),
        R.id.menu_save to Row(SkIconRole.SHARE),
        // shared documents
        R.id.menu_collab_history to Row(SkIconRole.VIEW, true),
        R.id.menu_collab_rename to Row(SkIconRole.MESSAGE, true, SkGlyph.EDIT),
        R.id.menu_collab_export to Row(SkIconRole.SHARE, true, SkGlyph.EXPORT),
        // exposed services
        R.id.action_edit to Row(SkIconRole.MESSAGE, true),
        R.id.action_delete to Row(SkIconRole.DELETE),
        // a call
        R.id.menuitem_dialpad to Row(SkIconRole.CALL, true),
        R.id.menuitem_video_extensions to Row(SkIconRole.VIEW, true),
    )

    private fun row(item: MenuItem) = ROWS[item.itemId]

    fun label() = skColor(SkRole.MENU_LABEL)
    fun frame() = skColor(SkRole.MENU_ROW_FRAME)
    fun danger() = skColor(SkRole.DANGER)
    fun divider() = skColor(SkRole.MENU_DIVIDER)
    fun iconColor(item: MenuItem) = skColor(row(item)?.role ?: SkIconRole.VIEW)

    /** The table icon of an item that brought none, or null. */
    fun iconFor(context: Context, item: MenuItem): Drawable? {
        val glyph = row(item)?.glyph ?: return null
        return SkGlyphDrawable(glyph, iconColor(item), Math.round(24 * context.resources.displayMetrics.density))
    }

    private fun hasTableIcon(item: MenuItem) = row(item)?.glyph != null

    fun opens(item: MenuItem): Boolean =
        item.hasSubMenu() || row(item)?.opens == true || item.title?.toString()?.trim()?.endsWith("…") == true

    fun isDestructive(item: MenuItem) = row(item)?.role == SkIconRole.DELETE

    /** The disabled title line a menu opens with. */
    private fun isHeader(item: MenuItem) = !item.isEnabled && item.itemId == 0 && !item.hasSubMenu()

    /** A row with a switch: checkable, and not one of an exclusive (radio) group. */
    fun isToggle(item: MenuItemImpl) = item.isCheckable && !item.isExclusiveCheckable

    /** An action row: everything that is not a toggle, a radio choice or a header. */
    fun isPill(item: MenuItemImpl) = !item.isCheckable && !isHeader(item)

    /**
     * Whether the rows of this menu keep an icon column. A list of pure choices with no icon
     * anywhere stays iconless; as soon as one row has an icon every row keeps the column, so the
     * labels share one left edge.
     */
    fun anyIcon(menu: MenuBuilder?): Boolean {
        menu ?: return true
        return try {
            menu.visibleItems.any { it.icon != null || hasTableIcon(it) }
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * A menu never scrolls while the screen has room. A stock popup is given only the larger of
     * the room above and the room below its anchor; showing it shifted up by what is missing
     * below lets it use the whole height. Returns that shift (negative), or null when the menu
     * fits below its anchor. The height is estimated from the rows as SkPillMenuItemView draws
     * them, a little generously — an estimate that is too large only moves the menu slightly
     * higher.
     */
    fun verticalOffset(context: Context, anchor: View, menu: Menu): Int? {
        return try {
            val builder = menu as? MenuBuilder ?: return null
            val metrics = context.resources.displayMetrics
            val density = metrics.density
            val value = TypedValue()
            if (!context.theme.resolveAttribute(androidx.appcompat.R.attr.dropdownListPreferredItemHeight, value, true))
                return null
            val rowHeight = value.getDimension(metrics)
            var needed = 24 * density
            var group: Int? = null
            for (item in builder.visibleItems) {
                needed += when {
                    isToggle(item) -> TOGGLE_HEIGHT_DP * density
                    isPill(item) -> rowHeight + 2 * PILL_MARGIN_DP * density
                    else -> rowHeight
                }
                if (group != null && group != item.groupId && builder.isGroupDividerEnabled) needed += 9 * density
                group = item.groupId
            }
            val frame = Rect()
            anchor.getWindowVisibleDisplayFrame(frame)
            val position = IntArray(2)
            anchor.getLocationOnScreen(position)
            val base = position[1] + anchor.height
            val below = frame.bottom - base
            if (needed <= below) null else Math.round(maxOf((frame.top - base).toFloat(), below - needed))
        } catch (ignored: Throwable) {
            null
        }
    }
}
