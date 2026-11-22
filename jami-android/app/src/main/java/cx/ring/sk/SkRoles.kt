/*
 * 白い熊 fork (sk): the colour role table of the black-yellow theme.
 *
 * Every themed item of the app has a role here, set on the UI page in the section it belongs
 * to. The two primaries, #FFFF00 and #000000, are written once, below, as defaults; every other
 * default is either a colour that carries meaning or derived from another role. Stored colours
 * live in ColorPrefs' preferences file ("shiroikuma_colors"), next to the older per-surface
 * roles of ColorPrefs, so they travel in the export with them.
 *
 * How a role reaches the screen:
 *  - what the fork draws itself (pills, switches, menu rows, the UI page) calls skColor();
 *  - upstream's layouts are not edited: SkPalette lays the roles over the colour resources
 *    those layouts and the theme name (the "mixed way", 白い熊 2026-11-22).
 */
package cx.ring.sk

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import cx.ring.application.JamiApplication
import cx.ring.utils.ColorPrefs
import java.util.concurrent.ConcurrentHashMap

enum class SkSection(val title: String) {
    FOUNDATION("Foundation"),
    TEXT("Text"),
    BUTTONS("Buttons"),
    DIALOGS("Dialogs"),
    SWITCHES("Switches"),
    MARKS("Check boxes and radio buttons"),
    MENUS("Menus"),
    LISTS("Lists"),
    FIELDS("Text fields"),
    BARS("Top bars"),
    CALLS("Call controls"),
    PAGES("Settings pages"),
}

/** How a role's default is found. */
sealed class SkDefault {
    class Const(val argb: Int) : SkDefault()
    class Same(val role: () -> SkRole) : SkDefault()
    /** [t] of the way from [from] to [to]. */
    class Mix(val from: () -> SkRole, val to: () -> SkRole, val t: Float) : SkDefault()
    class Alpha(val role: () -> SkRole, val alpha: Int) : SkDefault()
}

private const val YELLOW = 0xFFFFFF00.toInt()
private const val BLACK = 0xFF000000.toInt()

enum class SkRole(val key: String, val section: SkSection, val label: String, val default: SkDefault) {
    // --- Foundation
    BACKGROUND("sk_background", SkSection.FOUNDATION, "Background", SkDefault.Const(BLACK)),
    SURFACE("sk_surface", SkSection.FOUNDATION, "Raised surfaces (cards, sheets, bars)", SkDefault.Same { BACKGROUND }),
    ACCENT("sk_accent", SkSection.FOUNDATION, "Accent", SkDefault.Const(YELLOW)),
    ON_ACCENT("sk_on_accent", SkSection.FOUNDATION, "Content on an accent ground", SkDefault.Same { BACKGROUND }),
    ICON("sk_icon", SkSection.FOUNDATION, "Icons", SkDefault.Same { ACCENT }),
    ICON_ACTIVE("sk_icon_active", SkSection.FOUNDATION, "Active controls (sliders, cursors)", SkDefault.Same { ACCENT }),
    OUTLINE("sk_outline", SkSection.FOUNDATION, "Outlines and frames", SkDefault.Same { ACCENT }),
    DIVIDER("sk_divider", SkSection.FOUNDATION, "Dividers", SkDefault.Mix({ BACKGROUND }, { ACCENT }, 0.70f)),
    PRESSED("sk_pressed", SkSection.FOUNDATION, "Pressed highlight", SkDefault.Alpha({ ACCENT }, 0x4D)),
    DANGER("sk_danger", SkSection.FOUNDATION, "Delete and warnings", SkDefault.Const(0xFFFF0000.toInt())),

    // --- Text
    TEXT("sk_text", SkSection.TEXT, "Text", SkDefault.Const(YELLOW)),
    TEXT_SECONDARY("sk_text_secondary", SkSection.TEXT, "Secondary text", SkDefault.Const(0xFFC8C800.toInt())),
    TEXT_DISABLED("sk_text_disabled", SkSection.TEXT, "Disabled text", SkDefault.Alpha({ TEXT }, 0x61)),
    TEXT_HINT("sk_text_hint", SkSection.TEXT, "Hints", SkDefault.Alpha({ TEXT }, 0x99)),
    TEXT_SELECTION("sk_text_selection", SkSection.TEXT, "Text selection", SkDefault.Alpha({ ACCENT }, 0x66)),
    LINK("sk_link", SkSection.TEXT, "Links", SkDefault.Same { ACCENT }),

    // --- Buttons
    PILL_TEXT("sk_pill_text", SkSection.BUTTONS, "Button label", SkDefault.Same { ACCENT }),
    PILL_FRAME("sk_pill_frame", SkSection.BUTTONS, "Button frame", SkDefault.Same { ACCENT }),
    PILL_GROUND("sk_pill_ground", SkSection.BUTTONS, "Button ground", SkDefault.Same { BACKGROUND }),
    FAB_RING("sk_fab_ring", SkSection.BUTTONS, "Floating button ring", SkDefault.Same { ACCENT }),
    FAB_GLYPH("sk_fab_glyph", SkSection.BUTTONS, "Floating button glyph", SkDefault.Same { ACCENT }),
    FAB_GROUND("sk_fab_ground", SkSection.BUTTONS, "Floating button ground", SkDefault.Same { BACKGROUND }),

    // --- Dialogs
    DIALOG_GROUND("sk_dialog_ground", SkSection.DIALOGS, "Dialog ground", SkDefault.Same { BACKGROUND }),
    DIALOG_FRAME("sk_dialog_frame", SkSection.DIALOGS, "Dialog frame", SkDefault.Same { ACCENT }),
    DIALOG_TITLE("sk_dialog_title", SkSection.DIALOGS, "Dialog title", SkDefault.Same { TEXT }),
    DIALOG_TEXT("sk_dialog_text", SkSection.DIALOGS, "Dialog text", SkDefault.Same { TEXT }),

    // --- Switches
    SWITCH_ON("sk_switch_on", SkSection.SWITCHES, "Switch, on", SkDefault.Same { ACCENT }),
    SWITCH_OFF("sk_switch_off", SkSection.SWITCHES, "Switch, off", SkDefault.Mix({ BACKGROUND }, { TEXT }, 0.55f)),

    // --- Selection marks
    MARK_ON("sk_mark_on", SkSection.MARKS, "Mark, chosen", SkDefault.Same { ACCENT }),
    MARK_OFF("sk_mark_off", SkSection.MARKS, "Mark, not chosen", SkDefault.Same { SWITCH_OFF }),

    // --- Menus (the first three are the older ColorPrefs roles, kept under their keys)
    MENU_GROUND(ColorPrefs.MENU_FILL, SkSection.MENUS, "Menu ground", SkDefault.Same { BACKGROUND }),
    MENU_FRAME(ColorPrefs.MENU_BORDER, SkSection.MENUS, "Menu frame", SkDefault.Same { ACCENT }),
    MENU_LABEL(ColorPrefs.MENU_TEXT, SkSection.MENUS, "Menu label", SkDefault.Same { TEXT }),
    MENU_ROW_FRAME("sk_menu_row_frame", SkSection.MENUS, "Menu row frame", SkDefault.Same { ACCENT }),
    MENU_DIVIDER("sk_menu_divider", SkSection.MENUS, "Menu group divider", SkDefault.Same { DIVIDER }),
    MENU_ICON_CALL("sk_menu_icon_call", SkSection.MENUS, "Icon: call, connect", SkDefault.Const(0xFF00FF00.toInt())),
    MENU_ICON_MESSAGE("sk_menu_icon_message", SkSection.MENUS, "Icon: message, write", SkDefault.Const(0xFF4D94FF.toInt())),
    MENU_ICON_CONTACT("sk_menu_icon_contact", SkSection.MENUS, "Icon: contact, person", SkDefault.Const(0xFF00FFFF.toInt())),
    MENU_ICON_SHARE("sk_menu_icon_share", SkSection.MENUS, "Icon: copy, share, export", SkDefault.Const(0xFFFF8000.toInt())),
    MENU_ICON_VIEW("sk_menu_icon_view", SkSection.MENUS, "Icon: view, open, settings", SkDefault.Same { ACCENT }),
    MENU_ICON_BLOCK("sk_menu_icon_block", SkSection.MENUS, "Icon: block, mute, leave", SkDefault.Const(0xFFFF00FF.toInt())),
    MENU_ICON_DELETE("sk_menu_icon_delete", SkSection.MENUS, "Icon: delete, remove", SkDefault.Same { DANGER }),

    // --- Lists
    LIST_DIVIDER("sk_list_divider", SkSection.LISTS, "List divider", SkDefault.Same { DIVIDER }),
    LIST_SELECTED("sk_list_selected", SkSection.LISTS, "Selected row ground", SkDefault.Mix({ BACKGROUND }, { ACCENT }, 0.20f)),
    LIST_SELECTED_TEXT("sk_list_selected_text", SkSection.LISTS, "Selected row text", SkDefault.Same { TEXT }),

    // --- Text fields
    FIELD_TEXT("sk_field_text", SkSection.FIELDS, "Typed text", SkDefault.Same { TEXT }),
    FIELD_GROUND("sk_field_ground", SkSection.FIELDS, "Field ground", SkDefault.Same { BACKGROUND }),
    FIELD_FRAME("sk_field_frame", SkSection.FIELDS, "Field frame and underline", SkDefault.Same { OUTLINE }),

    // --- Bars
    BAR_GROUND("sk_bar_ground", SkSection.BARS, "Top bar ground", SkDefault.Same { BACKGROUND }),

    // --- Call controls
    CALL_CTRL("sk_call_ctrl", SkSection.CALLS, "Control ring and glyph", SkDefault.Same { ACCENT }),
    CALL_CTRL_GROUND("sk_call_ctrl_ground", SkSection.CALLS, "Control ground", SkDefault.Same { BACKGROUND }),
    CALL_CTRL_ACTIVE("sk_call_ctrl_active", SkSection.CALLS, "Control switched on (muted, camera off)", SkDefault.Const(0xFFFF5252.toInt())),
    CALL_ACCEPT("sk_call_accept", SkSection.CALLS, "Answer", SkDefault.Const(0xFF00E676.toInt())),
    CALL_DECLINE("sk_call_decline", SkSection.CALLS, "Decline and hang up", SkDefault.Const(0xFFFF5252.toInt())),
    CALL_MISSED("sk_call_missed", SkSection.CALLS, "Missed call", SkDefault.Const(0xFFFA2E30.toInt())),

    // --- Settings pages
    PAGE_HEADING("sk_page_heading", SkSection.PAGES, "Section headings", SkDefault.Same { ACCENT }),
    PAGE_CHIP("sk_page_chip", SkSection.PAGES, "Index chips", SkDefault.Same { ACCENT }),
    PAGE_NOTE(ColorPrefs.SETTINGS_NOTE, SkSection.PAGES, "Explanations and the left rule", SkDefault.Const(0xFFC8C800.toInt())),
    ;

    companion object {
        fun ofSection(section: SkSection) = entries.filter { it.section == section }
        fun byKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

object SkRoles {
    private val cache = ConcurrentHashMap<SkRole, Int>()
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun context(): Context? = JamiApplication.instance

    /** Called once at application start: any stored colour change drops the cache and the palette. */
    fun install(context: Context) {
        val prefs = context.getSharedPreferences("shiroikuma_colors", Context.MODE_PRIVATE)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // Only colours: the same file also holds the UI page's section order and fold state.
            if (key == null || key.startsWith("color_")) {
                cache.clear()
                SkPalette.changed()
            }
        }
        listener = l   // the preferences hold listeners weakly
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    fun color(role: SkRole): Int {
        cache[role]?.let { return it }
        val c = context() ?: return computeDefault(role, null)
        val v = if (ColorPrefs.isSet(c, role.key)) ColorPrefs.getColor(c, role.key) else computeDefault(role, c)
        cache[role] = v
        return v
    }

    fun defaultOf(role: SkRole): Int = computeDefault(role, context())

    private fun computeDefault(role: SkRole, c: Context?): Int = when (val d = role.default) {
        is SkDefault.Const -> d.argb
        is SkDefault.Same -> resolved(d.role(), c)
        is SkDefault.Alpha -> (resolved(d.role(), c) and 0x00FFFFFF) or (d.alpha shl 24)
        is SkDefault.Mix -> mix(resolved(d.from(), c), resolved(d.to(), c), d.t)
    }

    private fun resolved(role: SkRole, c: Context?): Int =
        if (c != null && ColorPrefs.isSet(c, role.key)) ColorPrefs.getColor(c, role.key) else computeDefault(role, c)

    fun mix(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return Math.round(a + (b - a) * t).coerceIn(0, 255)
        }
        return Color.argb(0xFF, ch(16), ch(8), ch(0))
    }

    fun resetAll(context: Context) {
        for (role in SkRole.entries) ColorPrefs.reset(context, role.key)
    }
}

fun skColor(role: SkRole): Int = SkRoles.color(role)

/** One of the older per-surface roles of ColorPrefs, by its key. */
fun skPref(key: String): Int =
    JamiApplication.instance?.let { ColorPrefs.getColor(it, key) } ?: 0xFFFFFF00.toInt()

/** The disabled look of any colour: 38 % of it. */
fun skDisabled(color: Int): Int = (color and 0x00FFFFFF) or ((Color.alpha(color) * 0.38f).toInt() shl 24)

/** What a row does — it colours the row's icon. */
enum class SkIconRole(val role: SkRole) {
    CALL(SkRole.MENU_ICON_CALL),
    MESSAGE(SkRole.MENU_ICON_MESSAGE),
    CONTACT(SkRole.MENU_ICON_CONTACT),
    SHARE(SkRole.MENU_ICON_SHARE),
    VIEW(SkRole.MENU_ICON_VIEW),
    BLOCK(SkRole.MENU_ICON_BLOCK),
    DELETE(SkRole.MENU_ICON_DELETE),
}

fun skColor(role: SkIconRole): Int = SkRoles.color(role.role)
