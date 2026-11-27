package cx.ring.settings

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import cx.ring.utils.Flash
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cx.ring.R
import cx.ring.utils.AutomationPrefs
import cx.ring.utils.ColorPrefs
import cx.ring.utils.DataMeter
import cx.ring.utils.DiagPrefs.Group as DG
import cx.ring.utils.DeclineMessagesPrefs
import cx.ring.utils.EximPanel
import cx.ring.utils.FontPrefs
import cx.ring.utils.SettingsExport
import cx.ring.utils.FontUtil
import cx.ring.utils.PickListDialog
import cx.ring.utils.UiPrefs
import cx.ring.sk.SkGlyph
import cx.ring.sk.SkGlyphDrawable
import cx.ring.sk.SkIconRole
import cx.ring.sk.SkLeftRule
import cx.ring.sk.SkPalette
import cx.ring.sk.SkRole
import cx.ring.sk.SkRoles
import cx.ring.sk.SkSection
import cx.ring.sk.SkSectionIndex
import cx.ring.sk.displayLabel
import cx.ring.sk.displayTitle
import cx.ring.sk.skColor
import cx.ring.sk.skPillBackground

/** "白い熊 GNU Jami UI" — the Online-recovery section on top, then grouped, inline per-element font +
 *  colour controls (App Manager treatment). */
@dagger.hilt.android.AndroidEntryPoint
class FontsSettingsFragment : Fragment() {
    @javax.inject.Inject lateinit var mAccountService: net.jami.services.AccountService
    @javax.inject.Inject lateinit var mConversationFacade: net.jami.services.ConversationFacade
    private val recoveryDisposable = io.reactivex.rxjava3.disposables.CompositeDisposable()
    private data class ColorRole(val label: String, val key: String)
    /** A percentage slider backed by a pref (e.g. the account dot size). */
    private data class ScaleRole(
        val label: String,
        val getPct: (android.content.Context) -> Int,
        val setPct: (android.content.Context, Int) -> Unit,
        val minPct: Int,
        val maxPct: Int,
    )
    private data class Element(
        val label: String,
        val fontCategory: String?,
        val colors: List<ColorRole>,
        val scale: ScaleRole? = null,
    )
    private data class Group(val key: String, val title: String, val elements: List<Element>)

    // Built on first use (the fragment is attached by then): the labels are string resources.
    // A group's key is its stable English name — the stored section order and fold state use it.
    private val groups by lazy { listOf(
        Group("Default", getString(R.string.sk_set_grp_default), listOf(
            Element(getString(R.string.sk_set_el_default), FontPrefs.DEFAULT, emptyList()))),
        Group("Chat list", getString(R.string.sk_set_grp_chat_list), listOf(
            Element(getString(R.string.sk_set_el_contact_name), FontPrefs.LIST_TITLE, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.LIST_NAME))),
            Element(getString(R.string.sk_set_el_last_message), FontPrefs.LIST_PREVIEW, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.LIST_PREVIEW))),
            Element(getString(R.string.sk_set_el_timestamp), FontPrefs.LIST_DATE, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.LIST_DATE))))),
        Group("Conversation", getString(R.string.sk_set_grp_conversation), listOf(
            Element(getString(R.string.sk_set_el_conv_title), FontPrefs.CONV_TITLE, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.CONV_TITLE))),
            Element(getString(R.string.sk_set_el_sent), FontPrefs.CHAT_TEXT, listOf(ColorRole(getString(R.string.sk_set_c_text), ColorPrefs.MSG_SENT), ColorRole(getString(R.string.sk_set_c_bubble_fill), ColorPrefs.MSG_SENT_FILL), ColorRole(getString(R.string.sk_set_c_bubble_border), ColorPrefs.MSG_SENT_BORDER))),
            Element(getString(R.string.sk_set_el_received), null, listOf(ColorRole(getString(R.string.sk_set_c_text), ColorPrefs.MSG_RECEIVED), ColorRole(getString(R.string.sk_set_c_bubble_fill), ColorPrefs.MSG_RECEIVED_FILL), ColorRole(getString(R.string.sk_set_c_bubble_border), ColorPrefs.MSG_RECEIVED_BORDER))),
            Element(getString(R.string.sk_set_el_msg_time), FontPrefs.MSG_TIME, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.MSG_TIME))),
            Element(getString(R.string.sk_set_el_link_card), null, listOf(
                ColorRole(getString(R.string.sk_set_c_title), ColorPrefs.LINK_TITLE),
                ColorRole(getString(R.string.sk_set_c_description), ColorPrefs.LINK_DESC),
                ColorRole(getString(R.string.sk_set_c_domain), ColorPrefs.LINK_DOMAIN),
                ColorRole(getString(R.string.sk_set_c_card_fill), ColorPrefs.LINK_CARD_FILL),
                ColorRole(getString(R.string.sk_set_c_card_border), ColorPrefs.LINK_CARD_BORDER))),
            Element(getString(R.string.sk_set_el_file), null, listOf(
                ColorRole(getString(R.string.sk_set_c_name_size), ColorPrefs.FILE_NAME),
                ColorRole(getString(R.string.sk_set_c_download_arrow), ColorPrefs.FILE_ARROW),
                ColorRole(getString(R.string.sk_set_c_card_fill), ColorPrefs.FILE_CARD_FILL),
                ColorRole(getString(R.string.sk_set_c_card_border), ColorPrefs.FILE_CARD_BORDER))))),
        Group("Call events", getString(R.string.sk_set_grp_call_events), listOf(
            Element(getString(R.string.sk_set_el_call), null, listOf(
                ColorRole(getString(R.string.sk_set_c_text), ColorPrefs.CALL_TEXT),
                ColorRole(getString(R.string.sk_set_c_pill_fill), ColorPrefs.CALL_FILL),
                ColorRole(getString(R.string.sk_set_c_pill_border), ColorPrefs.CALL_BORDER))))),
        Group("Invitations", getString(R.string.sk_set_grp_invitations), listOf(
            Element(getString(R.string.sk_set_el_invite_card), null, listOf(
                ColorRole(getString(R.string.sk_set_c_text), ColorPrefs.INVITE_TEXT),
                ColorRole(getString(R.string.sk_set_c_fill), ColorPrefs.INVITE_FILL),
                ColorRole(getString(R.string.sk_set_c_border), ColorPrefs.INVITE_BORDER))),
            Element(getString(R.string.sk_set_el_request_bar), null, listOf(
                ColorRole(getString(R.string.sk_set_c_buttons), ColorPrefs.REQUEST_TEXT),
                ColorRole(getString(R.string.sk_set_c_fill), ColorPrefs.REQUEST_FILL),
                ColorRole(getString(R.string.sk_set_c_border), ColorPrefs.REQUEST_BORDER))))),
        Group("Message menu", getString(R.string.sk_set_grp_message_menu), listOf(
            Element(getString(R.string.sk_set_el_longpress_menu), null, listOf(
                ColorRole(getString(R.string.sk_set_c_text), ColorPrefs.MENU_TEXT),
                ColorRole(getString(R.string.sk_set_c_fill), ColorPrefs.MENU_FILL),
                ColorRole(getString(R.string.sk_set_c_border), ColorPrefs.MENU_BORDER))))),
        Group("Top bar", getString(R.string.sk_set_grp_top_bar), listOf(
            Element(getString(R.string.sk_set_el_search_hint), FontPrefs.SEARCH_HINT, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.SEARCH_HINT))))),
        Group("Settings", getString(R.string.sk_set_grp_settings), listOf(
            Element(getString(R.string.sk_set_el_settings_text), FontPrefs.SETTINGS, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.SETTINGS))),
            Element(getString(R.string.sk_set_el_settings_icons), null, listOf(ColorRole(getString(R.string.sk_set_c_tint), ColorPrefs.SETTINGS_ICON))),
            Element(getString(R.string.sk_set_el_settings_notes), null, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.SETTINGS_NOTE))))),
        Group("Presence dots (chat list)", getString(R.string.sk_set_grp_presence), listOf(
            Element(getString(R.string.sk_set_el_presence_connected), null, listOf(ColorRole(getString(R.string.sk_set_c_dot), ColorPrefs.PRESENCE_CONNECTED))),
            Element(getString(R.string.sk_set_el_presence_available), null, listOf(ColorRole(getString(R.string.sk_set_c_dot), ColorPrefs.PRESENCE_AVAILABLE))))),
        Group("Account list", getString(R.string.sk_set_grp_account_list), listOf(
            Element(getString(R.string.sk_set_el_invite_badge), null, listOf(
                ColorRole(getString(R.string.sk_set_c_fill), ColorPrefs.BADGE_FILL),
                ColorRole(getString(R.string.sk_set_c_border), ColorPrefs.BADGE_BORDER))))),
        Group("Status & indicators", getString(R.string.sk_set_grp_status), listOf(
            Element(getString(R.string.sk_set_el_sending), null, listOf(ColorRole(getString(R.string.sk_set_c_tint), ColorPrefs.STATUS_SENDING))),
            Element(getString(R.string.sk_set_el_sent_icon), null, listOf(ColorRole(getString(R.string.sk_set_c_tint), ColorPrefs.STATUS_SUCCESS))),
            Element(getString(R.string.sk_set_el_dot_connected), null, listOf(ColorRole(getString(R.string.sk_set_c_tint), ColorPrefs.STATUS_ONLINE))),
            Element(getString(R.string.sk_set_el_dot_connecting), null, listOf(ColorRole(getString(R.string.sk_set_c_tint), ColorPrefs.STATUS_CONNECTING))),
            Element(getString(R.string.sk_set_el_dot_disconnected), null, listOf(ColorRole(getString(R.string.sk_set_c_tint), ColorPrefs.STATUS_OFFLINE))),
            Element(getString(R.string.sk_set_el_dot_size), null, emptyList(), ScaleRole(getString(R.string.sk_set_scale_of_default),
                { c -> (UiPrefs.getStatusDotScale(c) * 100f).toInt() },
                { c, v -> UiPrefs.setStatusDotScale(c, v / 100f) }, 50, 300)),
            Element(getString(R.string.sk_set_el_unread_border), null, listOf(ColorRole(getString(R.string.sk_set_c_border), ColorPrefs.UNREAD_BORDER))))),
        Group("Flashes (toasts)", getString(R.string.sk_set_grp_flashes), listOf(
            Element(getString(R.string.sk_set_el_flash), FontPrefs.FLASH, listOf(
                ColorRole(getString(R.string.sk_set_c_text), ColorPrefs.FLASH_TEXT),
                ColorRole(getString(R.string.sk_set_c_background), ColorPrefs.FLASH_FILL),
                ColorRole(getString(R.string.sk_set_c_border), ColorPrefs.FLASH_BORDER))))),
        Group("Connection monitor", getString(R.string.sk_set_grp_monitor), listOf(
            Element(getString(R.string.sk_set_el_mon_healthy), null, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.MONITOR_HEALTHY))),
            Element(getString(R.string.sk_set_el_mon_connecting), null, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.MONITOR_CONNECTING))),
            Element(getString(R.string.sk_set_el_mon_problem), null, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.MONITOR_PROBLEM))),
            Element(getString(R.string.sk_set_el_mon_connected), null, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.MONITOR_CONNECTED))),
            Element(getString(R.string.sk_set_el_mon_idle), null, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.MONITOR_IDLE))),
            Element(getString(R.string.sk_set_el_mon_offline), null, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.MONITOR_OFFLINE))),
            Element(getString(R.string.sk_set_el_fold_size), null, emptyList(), ScaleRole(getString(R.string.sk_set_scale_of_row_text),
                { c -> (UiPrefs.getMonitorFoldScale(c) * 100f).toInt() },
                { c, v -> UiPrefs.setMonitorFoldScale(c, v / 100f) }, 20, 300)))),
        Group("Connectivity help page", getString(R.string.sk_set_grp_help_page), listOf(
            Element(getString(R.string.sk_set_el_headings), FontPrefs.INFO_HEADING, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.INFO_HEADING))),
            Element(getString(R.string.sk_set_el_body), FontPrefs.INFO_BODY, listOf(ColorRole(getString(R.string.sk_set_c_color), ColorPrefs.INFO_BODY))),
            Element(getString(R.string.sk_set_el_pills), null, listOf(
                ColorRole(getString(R.string.sk_set_c_text), ColorPrefs.INFO_PILL_TEXT),
                ColorRole(getString(R.string.sk_set_c_border), ColorPrefs.INFO_PILL_BORDER),
                ColorRole(getString(R.string.sk_set_c_fill), ColorPrefs.INFO_PILL_FILL))))),
        Group("Launcher shortcuts", getString(R.string.sk_set_grp_shortcuts), listOf(
            Element(getString(R.string.sk_set_el_shortcut_badge), null, listOf(
                ColorRole(getString(R.string.sk_set_c_glyph_ring), ColorPrefs.SHORTCUT_ICON),
                ColorRole(getString(R.string.sk_set_c_glyph_outline), ColorPrefs.SHORTCUT_FILL))))),
    ) }

    // Every colour of this page is a role (black-yellow theme: nothing hard-coded).
    private val yellow: Int get() = skColor(SkRole.TEXT)
    private val grey: Int get() = skColor(SkRole.TEXT_SECONDARY)
    private val dim: Int get() = skColor(SkRole.PAGE_NOTE)      // summaries / captions
    private val warnRed: Int get() = skColor(SkRole.DANGER)     // warning state (dir unset / no export yet)
    private val heading: Int get() = skColor(SkRole.PAGE_HEADING)
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
            Flash.show(ctx, ctx.getString(R.string.sk_set_font_added, name), Toast.LENGTH_SHORT)
            rebuild()
        } catch (e: Exception) {
            Flash.show(ctx, ctx.getString(R.string.sk_set_font_import_failed, e.message.toString()), Toast.LENGTH_LONG)
        }
    }

    /** Settings → Diagnostics → Export: the SAF directory picker (2026-11-03). */
    private val pickDiagExportTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        tree ?: return@registerForActivityResult
        val app = context?.applicationContext ?: return@registerForActivityResult
        cx.ring.utils.DiagFiles.setExportTree(app, tree)
        val where = cx.ring.utils.DiagFiles.treeLabel(tree)
        val res = context ?: app     // the app language lives on the activity's resources
        Thread({
            val msg = try {
                val (sub, n) = cx.ring.utils.DiagFiles.export(app, tree)
                res.resources.getQuantityString(R.plurals.sk_set_diag_exported, n, n, where, sub)
            } catch (e: Exception) {
                res.getString(R.string.sk_set_diag_export_failed, e.message.toString())
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Flash.show(app, msg, Toast.LENGTH_LONG)
                if (isAdded) rebuild()
            }
        }, "sk-diag-export").start()
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.frag_fonts_settings, parent, false)
        root.tag = FontUtil.SKIP_SETTINGS_FONT_TAG
        container = root.findViewById(R.id.fonts_container)
        rebuild()
        return root
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system all-files screen must show the new state immediately.
        refreshEximPermission()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recoveryDisposable.clear()
        eximPageStatusTv = null
        eximPermTv = null
        eximPermBox = null
        (activity as? cx.ring.client.HomeActivity)?.refreshThemedViews()
        // Colours changed on this page: the screens behind it were built from the old ones.
        activity?.let { if (SkPalette.isStale(it)) it.recreate() }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    /** A row that DOES something on tap — opens a screen, picker, panel or dialog, or runs an
     *  action — is an OUTLINED pill (never filled) that hugs its label, its explanation beneath in dim gold,
     *  aligned with the label (black-yellow-theme “Settings pages — every pressable row is a pill”,
     *  白い熊 2026-11-07; reference: 白い熊 読書 DokshoUiActivity.actionRow). [main] = the section's
     *  main action: 3 dp frame + bold label; otherwise 1.25 dp + normal. [opens] = it opens something
     *  → a › inside at the right; an in-place action (Purge, Regenerate) has none. Only the pill is
     *  the touch target. Rows with their own control (switch, swatch, slider, a shown value) stay
     *  plain rows. Returns the column and the label, so callers can update or recolour the label. */
    private fun actionRow(title: String, summary: String? = null, main: Boolean = false, opens: Boolean = true,
                          glyph: SkGlyph? = null, role: SkIconRole = SkIconRole.VIEW, danger: Boolean = false,
                          onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val ctx = requireContext()
        val noteC = skColor(SkRole.PAGE_NOTE)
        val ink = if (danger) skColor(SkRole.DANGER) else skColor(SkRole.PILL_TEXT)
        val frame = if (danger) ink else skColor(SkRole.PILL_FRAME)
        val padH = dp(16f)
        val label = TextView(ctx).apply {
            text = title; setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            if (main) setTypeface(typeface, Typeface.BOLD)
            rowIcon(this, glyph ?: guessGlyph(title, opens), if (danger) SkIconRole.DELETE else role)
        }
        // The pill hugs its label: a short action stretched across the line reads as a banner.
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, dp(9f), dp(12f), dp(9f))
            background = skPillBackground(resources.displayMetrics.density, frame, skColor(SkRole.PILL_GROUND), main)
            isClickable = true
            setOnClickListener { onClick() }
            addView(label)
            if (opens) addView(android.widget.ImageView(ctx).apply {
                setImageDrawable(SkGlyphDrawable(SkGlyph.CHEVRON, ink, dp(20f)))
                setPadding(dp(6f), 0, 0, 0)
            })
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap().apply { topMargin = dp(3f); bottomMargin = dp(3f) }
            addView(pill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (summary != null) {
                addView(TextView(ctx).apply {
                    text = summary; setTextColor(noteC); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(padH, dp(3f), dp(8f), 0)
                }, matchWrap())
                // The rule spans pill + explanation.
                foreground = SkLeftRule(dp(3f).toFloat(), dp(2f).toFloat(), dp(2f).toFloat(), noteC)
                setPadding(dp(12f), 0, 0, 0)
            }
        }
        return col to label
    }

    /** The icon of a row: the label's start drawable, as tall as the label's text line (never
     *  under 18 dp) and coloured by what the row does. */
    private fun rowIcon(label: TextView, glyph: SkGlyph, role: SkIconRole = SkIconRole.VIEW) {
        val size = maxOf(dp(18f), Math.round(label.textSize * 1.25f))
        val d = SkGlyphDrawable(glyph, skColor(role), size)
        d.setBounds(0, 0, size, size)
        label.setCompoundDrawablesRelative(d, null, null, null)
        label.compoundDrawablePadding = dp(10f)
    }

    /** An icon for an action told from its wording — only where the caller named none. */
    private fun guessGlyph(title: String, opens: Boolean): SkGlyph {
        val t = title.lowercase()
        return when {
            "export" in t || "share" in t || "save" in t -> SkGlyph.EXPORT
            "import" in t || "restore" in t -> SkGlyph.IMPORT
            "purge" in t || "delete" in t || "clear" in t || "remove" in t -> SkGlyph.TRASH
            "reset" in t || "regenerate" in t -> SkGlyph.RESET
            "file" in t || "director" in t -> SkGlyph.DIRECTORY
            "log" in t || "history" in t -> SkGlyph.LIST
            "how" in t || "help" in t || "details" in t || "about" in t -> SkGlyph.INFO
            "add" in t -> SkGlyph.EDIT
            "conversation" in t || "message" in t -> SkGlyph.MESSAGE
            "access" in t || "permission" in t -> SkGlyph.CHECK
            "setting" in t -> SkGlyph.GEAR
            opens -> SkGlyph.OPEN
            else -> SkGlyph.DOT
        }
    }

    // ---------------------------------------------------------------- the page: index + folding sections

    /** [tone]: one of the seven section-icon colours, by what kind of thing the section is. */
    private class UiSection(val key: String, val title: String, val glyph: SkGlyph, val tone: SkRole,
                            val ownHeader: Boolean, val build: (LinearLayout) -> Unit) {
        /** The colour of the section's icon, on its heading and on its chip alike. */
        fun iconColor(): Int = skColor(tone)
    }

    /** Set while a section that adds its own group header builds: that header is the folding one now. */
    private var swallowHeader = false
    private val sectionHeaders = HashMap<String, View>()
    private val sectionOpeners = HashMap<String, () -> Unit>()

    private fun pagePrefs() = requireContext().getSharedPreferences("shiroikuma_colors", android.content.Context.MODE_PRIVATE)
    private fun storedOrder(): List<String> =
        pagePrefs().getString(KEY_ORDER, "").orEmpty().split('\n').filter { it.isNotEmpty() }
    private fun storedOpen(): Set<String> =
        pagePrefs().getString(KEY_OPEN, "").orEmpty().split('\n').filter { it.isNotEmpty() }.toSet()
    private fun saveOpen(key: String, open: Boolean) {
        val now = storedOpen().toMutableSet().apply { if (open) add(key) else remove(key) }
        pagePrefs().edit().putString(KEY_OPEN, now.joinToString("\n")).apply()
    }

    private fun uiSections(): List<UiSection> {
        val list = ArrayList<UiSection>()
        list += UiSection("language", getString(R.string.sk_set_sec_language), SkGlyph.GLOBE, SkRole.SECTION_WORDS, false) { addLanguageSection(it) }
        list += UiSection("exim", getString(R.string.sk_set_sec_exim), SkGlyph.EXPORT, SkRole.SECTION_REACH, true) { addExportImportSection(it) }
        list += UiSection("recovery", getString(R.string.sk_set_sec_recovery), SkGlyph.PULSE, SkRole.SECTION_CONTROLS, true) { addOnlineRecoverySection(it) }
        list += UiSection("decline", getString(R.string.sk_set_sec_decline), SkGlyph.MESSAGE, SkRole.SECTION_WORDS, true) { addDeclineMessagesSection(it) }
        list += UiSection("diag", getString(R.string.sk_set_sec_diag), SkGlyph.BARS, SkRole.SECTION_WARNING, true) { addDiagnosticsSection(it) }
        for (g in groups) {
            val look = GROUP_LOOK[g.key] ?: (SkGlyph.TEXT to SkRole.SECTION_BASE)
            list += UiSection("g:" + g.key, g.title, look.first, look.second, false) { c ->
                for (e in g.elements) c.addView(elementView(e))
            }
        }
        for (sec in SkSection.entries) {
            val look = ROLE_LOOK[sec] ?: (SkGlyph.DROP to SkRole.SECTION_BASE)
            list += UiSection("r:" + sec.name, sec.displayTitle(requireContext()), look.first, look.second, false) { c -> addRoleRows(c, sec) }
        }
        list += UiSection("maintenance", getString(R.string.sk_set_sec_maintenance), SkGlyph.RESET, SkRole.SECTION_WARNING, false) { addMaintenanceSection(it) }
        val known = list.associateBy { it.key }
        // The built-in order: “App language” first, and no two neighbours of one colour. A section
        // the table does not name yet comes after it.
        val builtIn = DEFAULT_ORDER.mapNotNull { known[it] } + list.filter { it.key !in DEFAULT_ORDER }
        // The stored order first; a section it does not know yet keeps its built-in place after them.
        languageFirstOnce()
        val order = storedOrder()
        return order.mapNotNull { known[it] } + builtIn.filter { it.key !in order }
    }

    /** Once: the default order used to have “App language” fifth, right after Diagnostics. A stored
     *  order that still has it exactly there never moved it, so it goes first there too; an order
     *  in which it was dragged somewhere else is left alone. */
    private fun languageFirstOnce() {
        val prefs = pagePrefs()
        if (prefs.getBoolean(KEY_LANG_FIRST, false)) return
        val order = storedOrder()
        val i = order.indexOf("language")
        val untouched = i > 0 && order[i - 1] == "diag" && (i + 1 >= order.size || order[i + 1].startsWith("g:"))
        prefs.edit().apply {
            putBoolean(KEY_LANG_FIRST, true)
            if (untouched) putString(KEY_ORDER, (listOf("language") + order.filter { it != "language" }).joinToString("\n"))
        }.apply()
    }

    private fun rebuild() {
        val c = container ?: return
        val ctx = requireContext()
        c.removeAllViews()
        sectionHeaders.clear()
        sectionOpeners.clear()
        // The app's own settings: the first, thick pill of the page.
        c.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(16f), dp(6f), dp(16f), dp(2f))
            addView(actionRow(getString(R.string.sk_set_app_settings), getString(R.string.sk_set_app_settings_desc),
                main = true, opens = true, glyph = SkGlyph.GEAR) {
                (activity as? cx.ring.client.HomeActivity)?.goToAdvancedSettings()
            }.first)
        })
        val sections = uiSections()
        // The index: one small chip per section. Tap = unfold and jump, long press = drag to reorder.
        c.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(16f), dp(4f), dp(16f), dp(4f))
            addView(SkSectionIndex.build(ctx, sections.map { SkSectionIndex.Item(it.key, it.title, it.glyph, it.iconColor()) },
                skColor(SkRole.PAGE_CHIP), skColor(SkRole.PAGE_CHIP),
                onJump = { key ->
                    sectionOpeners[key]?.invoke()
                    val header = sectionHeaders[key]
                    val scroller = view as? android.widget.ScrollView
                    if (header != null && scroller != null) scroller.post {
                        scroller.smoothScrollTo(0, (header.top + c.top - dp(4f)).coerceAtLeast(0))
                    }
                },
                onReordered = { order ->
                    pagePrefs().edit().putString(KEY_ORDER, order.joinToString("\n")).apply()
                    rebuild()
                }), matchWrap())
        })
        val open = storedOpen()
        for (s in sections) addFoldingSection(c, s, s.key in open)
    }

    /** One top-level section: the 1 px divider, a heading that folds its rows (› folded, ⌄ open),
     *  and the rows — built only when the section is first opened. Folded by default. */
    private fun addFoldingSection(c: LinearLayout, s: UiSection, open: Boolean) {
        val ctx = requireContext()
        c.addView(View(ctx).apply {
            setBackgroundColor(skColor(SkRole.DIVIDER))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(4f) }
        })
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            visibility = View.GONE
        }
        val title = TextView(ctx).apply {
            text = s.title
            setTextColor(heading)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            background = underlineBg(heading)
            setPadding(0, 0, dp(6f), dp(4f))
        }
        val iconSize = maxOf(dp(18f), Math.round(title.textSize * 1.25f))
        val mark = android.widget.ImageView(ctx)
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(16f), dp(8f), dp(14f), dp(6f))
            isClickable = true
            addView(android.widget.ImageView(ctx).apply {
                setImageDrawable(SkGlyphDrawable(s.glyph, s.iconColor(), iconSize))
            }, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = dp(10f) })
            addView(title, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(mark, LinearLayout.LayoutParams(dp(22f), dp(22f)))
        }
        var built = false
        fun show(now: Boolean) {
            if (now && !built) {
                built = true
                swallowHeader = s.ownHeader
                try { s.build(body) } finally { swallowHeader = false }
                ruleExplained(body)
            }
            body.visibility = if (now) View.VISIBLE else View.GONE
            mark.setImageDrawable(SkGlyphDrawable(if (now) SkGlyph.EXPAND else SkGlyph.CHEVRON, heading, dp(22f)))
        }
        header.setOnClickListener {
            val now = body.visibility != View.VISIBLE
            show(now)
            saveOpen(s.key, now)
        }
        show(open)
        sectionHeaders[s.key] = header
        sectionOpeners[s.key] = { show(true); saveOpen(s.key, true) }
        c.addView(header)
        c.addView(body)
    }

    /** A switch row and the note right under it are one explained item: the dim-gold rule runs
     *  down the left edge of both, the text a step to its right. */
    private fun ruleExplained(group: ViewGroup) {
        val noteC = skColor(SkRole.PAGE_NOTE)
        var i = 0
        while (i < group.childCount) {
            val row = group.getChildAt(i)
            val next = if (i + 1 < group.childCount) group.getChildAt(i + 1) else null
            if (row.tag == TAG_SWITCH && next?.tag == TAG_NOTE) {
                for (v in listOf(row, next!!)) {
                    v.foreground = SkLeftRule(0f, dp(2f).toFloat(), 0f, noteC)
                    v.setPadding(v.paddingLeft + dp(12f), v.paddingTop, v.paddingRight, v.paddingBottom)
                }
                // The note belongs to the switch above it: no gap that would break the rule.
                next.setPadding(next.paddingLeft, 0, next.paddingRight, next.paddingBottom)
                i += 2
                continue
            }
            if (row is ViewGroup && row.tag != TAG_SWITCH) ruleExplained(row)
            i++
        }
    }

    /** The colour roles of one surface, each a swatch row — minus those an older group already shows. */
    private fun addRoleRows(c: LinearLayout, section: SkSection) {
        val ctx = requireContext()
        val shown = groups.flatMap { g -> g.elements.flatMap { e -> e.colors.map { it.key } } }.toSet()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(54f), dp(2f), dp(16f), dp(4f))
        }
        for (role in SkRole.ofSection(section)) if (role.key !in shown) box.addView(colorRow(ColorRole(role.displayLabel(ctx), role.key)))
        c.addView(box)
    }

    private fun addMaintenanceSection(c: LinearLayout) {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(36f), dp(4f), dp(16f), dp(4f))
        }
        box.addView(actionRow(getString(R.string.sk_set_reset_order), getString(R.string.sk_set_reset_order_desc),
            opens = false, glyph = SkGlyph.RESET) {
            pagePrefs().edit().remove(KEY_ORDER).apply()
            rebuild()
        }.first)
        box.addView(actionRow(getString(R.string.sk_set_reset_colours), getString(R.string.sk_set_reset_colours_desc),
            opens = false, glyph = SkGlyph.RESET, danger = true) {
            cx.ring.utils.DialogTheme.builder(ctx)
                .setTitle(ctx.getString(R.string.sk_set_reset_colours_title))
                .setMessage(ctx.getString(R.string.sk_set_reset_colours_msg))
                .setPositiveButton(ctx.getString(R.string.sk_set_reset)) { _, _ ->
                    SkRoles.resetAll(ctx)
                    for (g in groups) for (e in g.elements) for (r in e.colors) ColorPrefs.reset(ctx, r.key)
                    rebuild()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show().let { cx.ring.utils.DialogTheme.theme(it, ctx, destructive = true) }
        }.first)
        c.addView(box)
    }

    /** A role's colour: the newer roles compute their default from other roles. */
    private fun roleColor(key: String): Int =
        SkRole.byKey(key)?.let { skColor(it) } ?: ColorPrefs.getColor(requireContext(), key)

    /** “Language: ‹current›” — a pill with a › that opens the locale picker. Overrides the phone
     *  locale for this app only (AppCompat per-app locales; localeConfig is declared). */
    private fun addLanguageSection(c: LinearLayout) {
        val ctx = requireContext()
        val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val label = if (current.isEmpty) getString(R.string.sk_set_lang_system) else {
            val loc = java.util.Locale.forLanguageTag(current.toLanguageTags().substringBefore(','))
            loc.getDisplayName(loc).replaceFirstChar { it.uppercase() }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(36f), dp(4f), dp(16f), dp(4f))
        }
        box.addView(actionRow(getString(R.string.sk_set_lang_row, label), opens = true, glyph = SkGlyph.GLOBE) {
            showLanguageDialog()
        }.first)
        c.addView(box)
    }

    private fun readSupportedLocaleTags(): List<String> {
        val tags = ArrayList<String>()
        try {
            val parser = resources.getXml(R.xml.locales_config)
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "locale") {
                    val name = parser.getAttributeValue(
                        "http://schemas.android.com/apk/res/android", "name")
                    if (!name.isNullOrEmpty()) tags.add(name)
                }
                event = parser.next()
            }
        } catch (_: Exception) {}
        return tags
    }

    /** The language dialog (black-yellow-theme, settled 2026-11-27): 日本語 and English as two
     *  full-width pills on top, outside the scrolling part; below them one framed box that scrolls
     *  by itself — “System default” first, then every other language by its own name. Each line:
     *  the mark, the flag, the language in its own language with its country beneath in dim gold,
     *  the tag in a column at the right. Inside the box only the current language is a pill. */
    private fun showLanguageDialog() {
        val ctx = context ?: return
        class Lang(val tag: String, val name: String, val country: String, val flag: Int)
        fun flagOf(region: String): Int = when (region) {
            "JP" -> R.drawable.sk_flag_jp; "US" -> R.drawable.sk_flag_us; "CZ" -> R.drawable.sk_flag_cz
            "DE" -> R.drawable.sk_flag_de; "EE" -> R.drawable.sk_flag_ee; "ES" -> R.drawable.sk_flag_es
            "FI" -> R.drawable.sk_flag_fi; "FR" -> R.drawable.sk_flag_fr; "RO" -> R.drawable.sk_flag_ro
            "CN" -> R.drawable.sk_flag_cn; "TW" -> R.drawable.sk_flag_tw
            else -> R.drawable.sk_flag_globe
        }
        val langs = ArrayList<Lang>()
        readSupportedLocaleTags().forEach { tag ->
            val loc = java.util.Locale.forLanguageTag(tag)
            val name = loc.getDisplayLanguage(loc).replaceFirstChar { it.uppercase() }.ifEmpty { tag }
            langs.add(Lang(tag, name, loc.getDisplayCountry(loc), flagOf(loc.country)))
        }
        val currentTag = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
        // A language chosen earlier that is no longer listed stays in force until another is
        // picked (JamiApplication re-asserts the stored tag whatever it is), so it is shown, as
        // the current row, and not passed off as “System default”.
        if (currentTag.isNotEmpty() && langs.none { it.tag == currentTag }) {
            val loc = java.util.Locale.forLanguageTag(currentTag)
            langs.add(Lang(currentTag,
                loc.getDisplayLanguage(loc).replaceFirstChar { it.uppercase() }.ifEmpty { currentTag },
                loc.getDisplayCountry(loc), flagOf(loc.country)))
        }
        val pinned = listOf("ja-JP", "en-US").mapNotNull { t -> langs.firstOrNull { it.tag == t } }
        val boxed = listOf(Lang("", getString(R.string.sk_set_lang_system), "", R.drawable.sk_flag_globe)) +
            langs.filter { it !in pinned }.sortedWith(compareBy({ it.name }, { it.tag }))
        val current = (pinned + boxed).firstOrNull { it.tag == currentTag } ?: boxed.first()

        val density = resources.displayMetrics.density
        val ink = skColor(SkRole.DIALOG_TEXT)
        val noteC = skColor(SkRole.PAGE_NOTE)
        fun mark(on: Boolean): android.graphics.drawable.Drawable {
            val c = skColor(if (on) SkRole.MARK_ON else SkRole.MARK_OFF)
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2f), c)
            }
            if (!on) return ring
            val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
            return android.graphics.drawable.LayerDrawable(arrayOf(ring, dot)).apply {
                setLayerInset(1, dp(6f), dp(6f), dp(6f), dp(6f))
            }
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        /** One line; [pill] = one of the pinned pair, which is a pill whether chosen or not. */
        fun line(lang: Lang, pill: Boolean): View {
            val on = lang === current
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // The tag column stays at the right for right-to-left languages too.
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(dp(14f), dp(7f), dp(16f), dp(7f))
                background = when {
                    on -> skPillBackground(density, skColor(SkRole.PILL_FRAME), Color.TRANSPARENT, true)
                    pill -> skPillBackground(density, skColor(SkRole.PILL_FRAME), Color.TRANSPARENT, false)
                    else -> skPillBackground(density, Color.TRANSPARENT)
                }
                isClickable = true
                setOnClickListener {
                    cx.ring.utils.UiPrefs.setAppLanguage(ctx, lang.tag)   // survives updates; re-asserted on start
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                        if (lang.tag.isEmpty()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                        else androidx.core.os.LocaleListCompat.forLanguageTags(lang.tag))
                    dialog?.dismiss()
                }
                addView(View(ctx).apply { background = mark(on) },
                    LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { marginEnd = dp(12f) })
                addView(android.widget.ImageView(ctx).apply {
                    setImageResource(lang.flag)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(28f), dp(26f)).apply { marginEnd = dp(12f) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(ctx).apply {
                        text = lang.name; setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        textDirection = View.TEXT_DIRECTION_LTR
                        if (on) setTypeface(typeface, Typeface.BOLD)
                    })
                    if (lang.country.isNotEmpty()) addView(TextView(ctx).apply {
                        text = lang.country; setTextColor(noteC); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        textDirection = View.TEXT_DIRECTION_LTR
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (lang.tag.isNotEmpty()) addView(TextView(ctx).apply {
                    text = lang.tag; setTextColor(noteC); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(10f), 0, 0, 0)
                })
            }
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = FontUtil.SKIP_SETTINGS_FONT_TAG
            setPadding(dp(14f), dp(8f), dp(14f), dp(4f))
        }
        // The pinned pair: always visible, never scrolled away.
        pinned.forEach { lang ->
            content.addView(line(lang, true), matchWrap().apply { bottomMargin = dp(6f) })
        }
        // The box with everything else.
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var currentRow: View? = null
        boxed.forEachIndexed { index, lang ->
            val on = lang === current
            // A hairline between rows, but none right above or below the pill of the current row.
            if (index > 0 && !on && boxed[index - 1] !== current) list.addView(View(ctx).apply {
                setBackgroundColor(skColor(SkRole.LIST_DIVIDER))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dp(14f); marginEnd = dp(14f)
            })
            val row = line(lang, false)
            if (on) currentRow = row
            list.addView(row, matchWrap())
        }
        val scroller = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(list)
        }
        content.addView(android.widget.FrameLayout(ctx).apply {
            background = cx.ring.sk.skPanel(density)
            setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
            addView(scroller)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(4f)
        })
        dialog = cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle(ctx.getString(R.string.sk_set_sec_language))
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .show().also { cx.ring.utils.DialogTheme.theme(it, ctx) }
        // Open with the current language in view.
        currentRow?.let { r -> scroller.post { scroller.scrollTo(0, (r.top - dp(60f)).coerceAtLeast(0)) } }
    }

    // ---- Call decline messages ---------------------------------------------------------------
    // The replies offered by the incoming-call screen's Message button. The general list is shown
    // inline; each contact with replies of its own gets one row that opens the shared editor
    // (DeclineMessagesEditor — the contact's details screen opens the very same dialogs).
    private fun addDeclineMessagesSection(c: LinearLayout) {
        val ctx = requireContext()
        c.addView(groupHeader(getString(R.string.sk_set_sec_decline)))
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(72f), dp(6f), dp(16f), dp(4f))
        }
        box.addView(orMini(getString(R.string.sk_set_decline_general_note)))
        DeclineMessagesPrefs.global(ctx).forEachIndexed { i, m ->
            box.addView(orTapRow("•  $m") {
                context?.let { DeclineMessagesEditor.showItemActions(it, null, i, { rebuild() }) }
            })
        }
        box.addView(actionRow(getString(R.string.sk_set_decline_add), glyph = SkGlyph.EDIT) {
            context?.let { c3 ->
                DeclineMessagesEditor.showEdit(c3, c3.getString(R.string.sk_set_decline_add), "") { DeclineMessagesPrefs.addGlobal(c3, it); rebuild() }
            }
        }.first)
        box.addView(orMini(getString(R.string.sk_set_decline_contact_note)))
        for (id in DeclineMessagesPrefs.contactsWithOwn(ctx)) {
            val name = DeclineMessagesPrefs.contactName(ctx, id) ?: (id.take(8) + "…")
            val n = DeclineMessagesPrefs.forContact(ctx, id).size
            box.addView(orTapRow(resources.getQuantityString(R.plurals.sk_set_decline_contact_row, n, name, n)) {
                context?.let { DeclineMessagesEditor.showList(it, id, DeclineMessagesPrefs.contactName(it, id)) { rebuild() } }
            })
        }
        box.addView(actionRow(getString(R.string.sk_set_decline_add_contact), glyph = SkGlyph.EDIT) { showDeclineContactPicker() }.first)
        c.addView(box)
    }

    /** Every 1:1 contact on every account, once each (keyed by ring id, as the lists are). */
    private fun showDeclineContactPicker() {
        val ctx = context ?: return
        val seen = HashSet<String>()
        val singles = ArrayList<io.reactivex.rxjava3.core.Single<net.jami.smartlist.ConversationItemViewModel>>()
        val ids = ArrayList<String>()
        for (acc in mAccountService.getAccounts()) {
            for (conv in acc.getConversations().toList()) {
                if (conv.isSwarmGroup() || conv.isSyncing()) continue
                val contact = try { conv.contact } catch (_: Exception) { null } ?: continue
                if (contact.isUser) continue
                val id = contact.uri.rawRingId.lowercase()
                if (id.isEmpty() || !seen.add(id)) continue
                ids.add(id)
                singles.add(mConversationFacade.getConversationProfile(acc.accountId, conv.uri))
            }
        }
        if (singles.isEmpty()) { Flash.show(ctx, ctx.getString(R.string.sk_set_no_contacts)); return }
        recoveryDisposable.add(
            io.reactivex.rxjava3.core.Single.zip(singles) { arr ->
                arr.map { it as net.jami.smartlist.ConversationItemViewModel } }
                .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe({ vms ->
                    val c2 = context ?: return@subscribe
                    val items = vms.mapIndexed { i, vm ->
                        PickListDialog.Item(
                            title = vm.title.ifBlank { ids[i].take(16) + "…" },
                            subtitle = vm.uriTitle.takeIf { it != vm.title },
                            icon = cx.ring.views.AvatarDrawable.Builder()
                                .withViewModel(vm).withCircleCrop(true).withPresence(false).build(c2),
                            payload = ids[i])
                    }.sortedBy { it.title.lowercase() }
                    PickListDialog.show(c2, c2.getString(R.string.sk_set_pick_contact), items) { item ->
                        DeclineMessagesEditor.showList(c2, item.payload as String, item.title) { rebuild() }
                    }
                }, { context?.let { Flash.show(it, it.getString(R.string.sk_set_contacts_failed)) } })
        )
    }

    // ---- Diagnostics (2026-11-01) --------------------------------------------------------------
    // Every SK diagnostic behind its own switch, all OFF by default: off means nothing is formatted,
    // written or timed. Native groups reach the daemon through DiagPrefs' mask file within 3 s.
    private fun addDiagnosticsSection(c: LinearLayout) {
        val ctx = requireContext()
        val D = cx.ring.utils.DiagPrefs
        val F = cx.ring.utils.DiagFiles
        c.addView(groupHeader(getString(R.string.sk_set_sec_diag)))
        // Tight layout (白い熊, 2026-11-03): minimal padding everywhere; each sub-heading underlined,
        // its rows indented one further step beneath it.
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(72f), dp(4f), dp(16f), dp(4f))
        }
        // Explanatory text in dim gold, a step below the yellow labels (白い熊, 2026-11-03: no grey).
        val noteC = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.SETTINGS_NOTE)
        fun note(text: String, into: LinearLayout) = into.addView(TextView(ctx).apply {
            this.text = text; setTextColor(noteC); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(2f)); layoutParams = matchWrap()
        })
        fun section(title: String): LinearLayout {
            box.addView(TextView(ctx).apply {
                text = title; setTextColor(yellow); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, Typeface.BOLD)
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setPadding(0, dp(8f), 0, dp(2f)); layoutParams = matchWrap()
            })
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = matchWrap()
                setPadding(dp(20f), 0, 0, 0)
                box.addView(this)
            }
        }
        // One item = bold label + switch, then the tag line (bold monospace tag · where the lines go),
        // then the description — all behind a thin dim-gold rule on the left that groups the item.
        fun sw(into: LinearLayout, label: String, g: cx.ring.utils.DiagPrefs.Group, text: String) {
            val tag = text.substringBefore(" (")
            val dest = text.substringAfter(" (").substringBefore("):")
            val desc = text.substringAfter("): ").replaceFirstChar { it.uppercase() }
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(10f), 0, 0, 0)
            }
            body.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = matchWrap()
                setPadding(0, 0, dp(12f), 0)
                addView(TextView(ctx).apply {
                    this.text = label; setTextColor(yellow); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTypeface(typeface, Typeface.BOLD)
                    rowIcon(this, SkGlyph.TOGGLE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
                    isChecked = D.isOn(g)
                    minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
                    cx.ring.sk.SkSwitches.style(this)
                    setOnCheckedChangeListener { _, v -> D.set(ctx, g, v) }
                })
            })
            body.addView(TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); layoutParams = matchWrap()
                this.text = android.text.SpannableStringBuilder().apply {
                    append(tag, android.text.style.TypefaceSpan("monospace"), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, tag.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.ForegroundColorSpan(yellow), 0, tag.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append("  ·  $dest")
                }
                setTextColor(noteC)
            })
            body.addView(TextView(ctx).apply {
                this.text = desc; setTextColor(noteC); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = matchWrap()
            })
            into.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = matchWrap().apply { topMargin = dp(4f); bottomMargin = dp(4f) }
                addView(View(ctx).apply {
                    setBackgroundColor(noteC)
                    layoutParams = LinearLayout.LayoutParams(dp(2f), LinearLayout.LayoutParams.MATCH_PARENT)
                })
                addView(body)
            })
        }
        note(getString(R.string.sk_set_diag_note), box)

        section(getString(R.string.sk_set_diag_sec_network)).let { s ->
            sw(s, getString(R.string.sk_set_diag_connections), DG.CONNECTIONS,
                "SK-ICEDIAG (logcat): " + getString(R.string.sk_set_diag_connections_desc))
            sw(s, getString(R.string.sk_diag_conn_trace), DG.CONN_TRACE,
                "SK-CONNTRACE (connection-trace.txt): " + getString(R.string.sk_diag_conn_trace_desc))
            sw(s, getString(R.string.sk_diag_presence), DG.PRESENCE,
                "SK-PRESENCE (presence-changes.txt): " + getString(R.string.sk_diag_presence_desc))
            sw(s, getString(R.string.sk_set_diag_dht_proxy), DG.DHT_PROXY,
                "SK-PROXYDIAG (logcat): " + getString(R.string.sk_set_diag_dht_proxy_desc))
            sw(s, getString(R.string.sk_set_diag_dht_stats), DG.DHT_STATS,
                "SK-DHTSTATS (logcat): " + getString(R.string.sk_set_diag_dht_stats_desc))
        }
        section(getString(R.string.sk_set_diag_sec_conversations)).let { s ->
            sw(s, getString(R.string.sk_set_diag_open), DG.OPEN,
                "SK-OPENDIAG (" + getString(R.string.sk_set_diag_dest_recovery_log) + "): " + getString(R.string.sk_set_diag_open_desc))
            sw(s, getString(R.string.sk_set_diag_history), DG.HISTORY,
                "SK-LOADDIAG (logcat): " + getString(R.string.sk_set_diag_history_desc))
            sw(s, getString(R.string.sk_set_diag_swarm_sync), DG.SWARM_SYNC,
                "SK-SWARMSYNC (logcat): " + getString(R.string.sk_set_diag_swarm_sync_desc))
        }
        section(getString(R.string.sk_set_diag_sec_calls)).let { s ->
            sw(s, getString(R.string.sk_set_diag_audio), DG.AUDIO,
                "SK-AUDIODIAG (logcat): " + getString(R.string.sk_set_diag_audio_desc))
        }
        section(getString(R.string.sk_set_diag_sec_background)).let { s ->
            sw(s, getString(R.string.sk_set_diag_wake), DG.WAKE,
                "SK-WAKE (" + getString(R.string.sk_set_diag_dest_recovery_log) + "): " + getString(R.string.sk_set_diag_wake_desc))
            sw(s, getString(R.string.sk_set_diag_exec), DG.EXEC,
                "SK-EXECDIAG (" + getString(R.string.sk_set_diag_dest_recovery_log) + "): " + getString(R.string.sk_set_diag_exec_desc))
        }
        section(getString(R.string.sk_set_diag_sec_health)).let { s ->
            sw(s, getString(R.string.sk_set_diag_memory), DG.MEMORY,
                "SK-MEMDIAG (memory-log.txt): " + getString(R.string.sk_set_diag_memory_desc))
        }

        // ---- Stored files, Export, Purge (2026-11-03) ----
        section(getString(R.string.sk_set_diag_sec_data)).let { s ->
            note(getString(R.string.sk_set_diag_kept_in, F.dir(ctx).absolutePath), s)
            val stored = TextView(ctx).apply {
                text = getString(R.string.sk_set_diag_stored_pending); setTextColor(yellow); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, 0, 0, dp(2f)); layoutParams = matchWrap()
            }
            s.addView(stored)
            Thread({
                val sum = runCatching { F.summarize(ctx) }.getOrNull()
                stored.post {
                    stored.text = when {
                        sum == null -> ctx.getString(R.string.sk_set_diag_stored_unknown)
                        sum.files == 0 -> ctx.getString(R.string.sk_set_diag_stored_nothing)
                        else -> ctx.getString(R.string.sk_set_diag_stored, F.filesLabel(ctx, sum.files, sum.bytes), F.spanLabel(sum.span))
                    }
                }
            }, "sk-diag-summary").start()
            val last = F.getExportTree(ctx)
            s.addView(actionRow(getString(R.string.sk_set_diag_export),
                getString(R.string.sk_set_diag_export_desc, last?.let { F.treeLabel(it) } ?: "/sdcard/tmp"),
                glyph = SkGlyph.EXPORT) { exportDiagData() }.first)
            s.addView(actionRow(getString(R.string.sk_set_diag_purge),
                getString(R.string.sk_set_diag_purge_desc), opens = false, glyph = SkGlyph.TRASH) { confirmDiagPurge() }.first)
        }
        c.addView(box)
    }

    private fun exportDiagData() {
        val ctx = requireContext()
        if (cx.ring.utils.DiagFiles.files(ctx).isEmpty()) {
            Flash.show(ctx, getString(R.string.sk_set_diag_nothing_to_export), Toast.LENGTH_SHORT); return
        }
        val tree = cx.ring.utils.DiagFiles.getExportTree(ctx)
        val initial = runCatching {
            if (tree != null) android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                android.provider.DocumentsContract.getTreeDocumentId(tree))
            else null
        }.getOrNull() ?: android.provider.DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:tmp")
        try {
            pickDiagExportTree.launch(initial)
        } catch (e: Exception) {
            Flash.show(ctx, getString(R.string.sk_set_diag_no_picker, e.message.toString()), Toast.LENGTH_LONG)
        }
    }

    private fun confirmDiagPurge() {
        val app = requireContext().applicationContext
        Thread({
            val s = runCatching { cx.ring.utils.DiagFiles.summarize(app) }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val ctx = context ?: return@post
                if (s == null || s.files == 0) {
                    Flash.show(ctx, ctx.getString(R.string.sk_set_diag_stored_nothing), Toast.LENGTH_SHORT); return@post
                }
                val df = cx.ring.utils.DiagFiles
                val msg = StringBuilder()
                for ((kind, k) in s.kinds)
                    msg.append(df.kindLabel(ctx, kind)).append(": ").append(df.filesLabel(ctx, k.files, k.bytes)).append(",\n    ")
                        .append(df.spanLabel(k.span)).append('\n')
                msg.append('\n')
                val day = java.text.SimpleDateFormat("EEE yyyy-MM-dd", java.util.Locale.US)
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                for (d in s.days.values)
                    msg.append(day.format(java.util.Date(d.first))).append("  ")
                        .append(time.format(java.util.Date(d.first))).append("–")
                        .append(time.format(java.util.Date(d.last))).append('\n')
                msg.append('\n').append(ctx.getString(R.string.sk_set_diag_total,
                    df.filesLabel(ctx, s.files, s.bytes), df.spanLabel(s.span)))
                cx.ring.utils.DialogTheme.builder(ctx)
                    .setTitle(ctx.getString(R.string.sk_set_diag_purge_title))
                    .setMessage(msg.toString())
                    .setPositiveButton(ctx.getString(R.string.sk_set_diag_delete_forever)) { _, _ ->
                        val n = df.purge(ctx)
                        Flash.show(ctx, ctx.resources.getQuantityString(R.plurals.sk_set_diag_deleted, n, n), Toast.LENGTH_SHORT)
                        if (isAdded) rebuild()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
            }
        }, "sk-diag-purge-summary").start()
    }

    // ---- Online recovery section (top of the page) -----------------------------------------
    private fun addOnlineRecoverySection(c: LinearLayout) {
        val ctx = requireContext()
        c.addView(groupHeader(getString(R.string.sk_set_sec_recovery)))
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(72f), dp(6f), dp(16f), dp(4f))
        }
        // The two Full DHT switches are gone (白い熊, 2026-07-30) — measurement settled the question,
        // so there is nothing left to choose here. Full DHT was 110–130 MiB/h with the radio never
        // sleeping; proxy + push rests at ~5 MiB/h. "Full DHT while charging" additionally paid a
        // ~50 MB re-subscription every time the charger went in or out.
        //
        // The MODE ITSELF is untouched: full DHT remains the watchdog's escalation path
        // (applyProxyState / fullRecover / restricted-network pinning), and the ⬡ control in the
        // search bar remains as the one manual override. Only these two settings rows are removed —
        // UiPrefs.isFullDhtMode / isFullDhtWhileCharging still exist and still read false by default,
        // so the charging branch is simply never armed.
        box.addView(orSwitchRow(getString(R.string.sk_set_rec_base), UiPrefs.isRecoveryBaseEnabled(ctx)) {
            UiPrefs.setRecoveryBaseEnabled(ctx, it); rebuild()   // mutually exclusive → rebuild to reflect ping
        })
        box.addView(actionRow(getString(R.string.sk_set_rec_how), glyph = SkGlyph.INFO) { showRecoveryInfo() }.first)
        box.addView(orSwitchRow(getString(R.string.sk_set_rec_ping), UiPrefs.isRecoveryPingEnabled(ctx)) {
            UiPrefs.setRecoveryPingEnabled(ctx, it); rebuild()   // mutually exclusive → rebuild to reflect base
        })
        box.addView(orTapRow(getString(R.string.sk_set_rec_from_row, recoveryAccountLabel(ctx))) { showRecoveryAccountPicker() })
        box.addView(actionRow(getString(R.string.sk_set_rec_pick_conv_row), glyph = SkGlyph.MESSAGE) { showRecoverySwarmPicker() }.first)
        box.addView(orMini(getString(R.string.sk_set_rec_paste_note)))
        box.addView(orSwarmField(ctx))
        box.addView(orTapRow(getString(R.string.sk_set_rec_every_row, UiPrefs.getRecoveryTickMinutes(ctx))) {
            showRecoveryNumber(getString(R.string.sk_set_rec_every_title), UiPrefs.getRecoveryTickMinutes(ctx), 1, 60) {
                UiPrefs.setRecoveryTickMinutes(ctx, it); rebuild()
            }
        })
        box.addView(orTapRow(getString(R.string.sk_set_rec_prune_row, UiPrefs.getRecoveryPruneDays(ctx))) {
            showRecoveryNumber(getString(R.string.sk_set_rec_prune_title), UiPrefs.getRecoveryPruneDays(ctx), 0, 90) {
                UiPrefs.setRecoveryPruneDays(ctx, it); rebuild()
            }
        })
        box.addView(actionRow(getString(R.string.sk_set_rec_view_log), glyph = SkGlyph.LIST) { showRecoveryLog() }.first)
        // ---- Data-usage meter (2026-07-25, from the 36-GiB runaway investigation) ----
        box.addView(orSwitchRow(getString(R.string.sk_set_meter_measure), DataMeter.isActive(ctx)) { on ->
            if (on) {
                DataMeter.start(ctx)
                startMeterLive()
            } else {
                val rec = DataMeter.stop(ctx, meterModeInfo())
                meterLiveRow?.text = ctx.getString(R.string.sk_set_meter_saved, rec)
            }
        })
        box.addView(TextView(ctx).apply {
            setTextColor(dim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8f), 0, 0, dp(4f)); layoutParams = matchWrap()
            text = if (DataMeter.isActive(ctx)) getString(R.string.sk_set_meter_measuring) else getString(R.string.sk_set_meter_off)
            meterLiveRow = this
        })
        box.addView(actionRow(getString(R.string.sk_set_meter_history), glyph = SkGlyph.LIST) { showDataMeasureHistory() }.first)
        // ---- Unattended sampler (2026-07-26): records without a human at either end ----
        box.addView(orSwitchRow(getString(R.string.sk_set_meter_auto), DataMeter.isSamplingOn(ctx)) { on ->
            DataMeter.setSamplingOn(ctx, on); rebuild()
        })
        if (DataMeter.isSamplingOn(ctx)) {
            box.addView(orTapRow(getString(R.string.sk_set_meter_window_row, DataMeter.windowLabel(DataMeter.getWindowMinutes(ctx)))) {
                showWindowSlider()
            })
            box.addView(orMini(getString(R.string.sk_set_meter_window_note, UiPrefs.getRecoveryTickMinutes(ctx))))
        }
        box.addView(actionRow(getString(R.string.sk_set_meter_usage_log), glyph = SkGlyph.LIST) { showDataHistory(DataMeter.hourlyFile(ctx), getString(R.string.sk_set_meter_usage_log), clearable = false) }.first)
        if (DataMeter.isActive(ctx)) startMeterLive()
        c.addView(box)
    }

    // Live data-meter display: elapsed + rx/tx, ticking once a second while the row is attached.
    private var meterLiveRow: TextView? = null
    private val meterHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val meterTick = object : Runnable {
        override fun run() {
            val ctx = context ?: return
            val row = meterLiveRow?.takeIf { it.isAttachedToWindow } ?: return
            if (!DataMeter.isActive(ctx)) return
            val (el, rx, tx) = DataMeter.snapshot(ctx)
            row.text = ctx.getString(R.string.sk_set_meter_live, DataMeter.elapsedLabel(el), DataMeter.bytesLabel(rx), DataMeter.bytesLabel(tx), DataMeter.netDisplayLabel(ctx))
            meterHandler.postDelayed(this, 1000L)
        }
    }
    private fun startMeterLive() { meterHandler.removeCallbacks(meterTick); meterHandler.post(meterTick) }

    /** Mode context for the history record: DHT pref + daemon-actual proxy + adaptive state. */
    private fun meterModeInfo(): String {
        val ctx = context ?: return "?"
        val pref = if (UiPrefs.isFullDhtMode(ctx)) "fullDHT" else "proxy"
        val actualProxy = runCatching { mAccountService.getAccounts().any { it.isJami && it.isDhtProxyEnabled } }.getOrDefault(false)
        val adaptive = cx.ring.utils.ConnectionWatchdog.isNoPushAdaptive()
        return "$pref(actual:${if (actualProxy) "proxy" else "fullDHT"}${if (adaptive) ",adaptive" else ""})"
    }

    private fun showDataMeasureHistory() {
        val ctx = context ?: return
        showDataHistory(DataMeter.historyFile(ctx), ctx.getString(R.string.sk_set_meter_measurements))
    }

    /** Shared renderer for both data logs — the manual session history and the unattended hourly
     *  log. Same themed scroll dialog, same newest-first order. Clear is offered only where it is
     *  safe: the hourly log is the unattended record every measurement is read from, so it is
     *  read-only here as well as in the "Data" dialog (白い熊, 2026-07-29). */
    private fun showDataHistory(f: java.io.File, title: String, clearable: Boolean = true) {
        // Shared with the Connection monitor's "Data" dialog (2026-07-26) so the two pages can
        // never render the same log differently.
        context?.let { cx.ring.utils.DataMeterUi.showHistory(it, f, title, clearable) }
    }

    private fun orTapRow(text: String, onClick: () -> Unit): View = TextView(requireContext()).apply {
        this.text = text
        setTextColor(yellow)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        rowIcon(this, SkGlyph.LIST)
        setPadding(0, dp(2f), dp(12f), dp(2f))
        layoutParams = matchWrap()
        setOnClickListener { onClick() }
    }

    private fun orMini(text: String): View = TextView(requireContext()).apply {
        this.text = text
        setTextColor(dim)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tag = TAG_NOTE
        setPadding(0, dp(6f), 0, dp(2f))
        layoutParams = matchWrap()
    }

    private fun orSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap()
            setPadding(0, dp(2f), dp(12f), dp(2f))
            tag = TAG_SWITCH
            addView(TextView(ctx).apply {
                text = label; setTextColor(yellow); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                rowIcon(this, SkGlyph.TOGGLE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
                isChecked = checked
                minimumHeight = 0
                cx.ring.sk.SkSwitches.style(this)
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
        }
    }

    private fun orSwarmField(ctx: android.content.Context): View = EditText(ctx).apply {
        setText(UiPrefs.getRecoveryTestSwarm(ctx))
        hint = ctx.getString(R.string.sk_set_rec_paste_hint)
        setTextColor(yellow)
        setHintTextColor(skColor(SkRole.TEXT_HINT))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        inputType = InputType.TYPE_CLASS_TEXT
        layoutParams = matchWrap()
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                UiPrefs.setRecoveryTestSwarm(ctx, s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, cnt: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, cnt: Int) {}
        })
    }

    private fun recoveryAccountLabel(ctx: android.content.Context): String {
        val id = UiPrefs.getRecoveryTestAccount(ctx)
        if (id.isEmpty()) return ctx.getString(R.string.sk_set_rec_not_set)
        val a = mAccountService.getAccount(id) ?: return id.take(8)
        return a.registeredName.ifBlank { a.alias.orEmpty() }.ifBlank { a.accountId.take(8) }
    }

    private fun showRecoveryInfo() {
        val ctx = context ?: return
        val html = ctx.getString(R.string.sk_set_rec_info_html)
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle(getString(R.string.sk_set_sec_recovery))
            .setMessage(androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT))
            .setPositiveButton(android.R.string.ok, null)
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun showRecoveryAccountPicker() {
        val ctx = context ?: return
        val accs = mAccountService.getAccounts().filter { it.isJami }
        if (accs.isEmpty()) { Flash.show(ctx, ctx.getString(R.string.sk_set_no_accounts)); return }
        val labels = accs.map { it.registeredName.ifBlank { it.alias.orEmpty() }.ifBlank { it.accountId.take(8) } }.toTypedArray()
        val current = accs.indexOfFirst { it.accountId == UiPrefs.getRecoveryTestAccount(ctx) }
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle(ctx.getString(R.string.sk_set_rec_from_title))
            .setSingleChoiceItems(labels, current) { d, which ->
                UiPrefs.setRecoveryTestAccount(ctx, accs[which].accountId)
                d.dismiss(); rebuild()
            }
            // Let it be un-set: clearing the account disables the ping check (nothing to send from).
            .setNeutralButton(ctx.getString(R.string.sk_set_rec_account_clear)) { _, _ -> UiPrefs.setRecoveryTestAccount(ctx, ""); rebuild() }
            .setNegativeButton(android.R.string.cancel, null)
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun showRecoverySwarmPicker() {
        val ctx = context ?: return
        val accId = UiPrefs.getRecoveryTestAccount(ctx)
        if (accId.isEmpty()) { Flash.show(ctx, ctx.getString(R.string.sk_set_rec_pick_account_first)); return }
        val acc = mAccountService.getAccount(accId) ?: return
        val convs = acc.getConversations().toList()
        if (convs.isEmpty()) { Flash.show(ctx, ctx.getString(R.string.sk_set_rec_no_conversations)); return }
        // Resolve each conversation's display title (same as the chat list shows), then pick by name.
        val singles = convs.map { mConversationFacade.getConversationProfile(accId, it.uri) }
        recoveryDisposable.add(
            io.reactivex.rxjava3.core.Single.zip(singles) { arr ->
                arr.map { it as net.jami.smartlist.ConversationItemViewModel } }
                .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe({ vms ->
                    val c2 = context ?: return@subscribe
                    val labels = vms.map {
                        it.title.ifBlank { (it.uri.rawRingId ?: it.uri.uri).take(16) + "…" } }.toTypedArray()
                    cx.ring.utils.DialogTheme.builder(c2)
                        .setTitle(c2.getString(R.string.sk_set_rec_pick_conv_title))
                        .setItems(labels) { d, which ->
                            UiPrefs.setRecoveryTestSwarm(c2, vms[which].uri.rawRingId ?: vms[which].uri.uri)
                            d.dismiss(); rebuild()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show().let { cx.ring.utils.DialogTheme.theme(it, c2) }
                }, { context?.let { Flash.show(it, it.getString(R.string.sk_set_conversations_failed)) } })
        )
    }

    /** Sample-window picker: a SeekBar rather than the numeric field the recovery settings use,
     *  because the useful range (1 min … 6 h) is wide and browsing it by feel beats typing. */
    private fun showWindowSlider() {
        val ctx = context ?: return
        val lo = DataMeter.WINDOW_MIN_MINUTES
        val hi = DataMeter.WINDOW_MAX_MINUTES
        val label = TextView(ctx).apply {
            setTextColor(yellow); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(24f), dp(16f), dp(24f), dp(4f))
        }
        var minutes = DataMeter.getWindowMinutes(ctx)
        fun paint() {
            val perDay = (24 * 60) / minutes
            label.text = ctx.getString(R.string.sk_set_meter_window_value, DataMeter.windowLabel(minutes), perDay)
        }
        paint()
        val bar = android.widget.SeekBar(ctx).apply {
            max = hi - lo
            progress = minutes - lo
            setPadding(dp(24f), dp(8f), dp(24f), dp(16f))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    minutes = (p + lo).coerceIn(lo, hi); paint()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; addView(label); addView(bar)
        }
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle(ctx.getString(R.string.sk_set_meter_window_title))
            .setView(col)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataMeter.setWindowMinutes(ctx, minutes); rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun showRecoveryNumber(title: String, current: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            setText(current.toString()); inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(yellow); setPadding(dp(24f), dp(12f), dp(24f), dp(12f))
        }
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = input.text.toString().toIntOrNull()?.coerceIn(min, max) ?: current
                onSet(v)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun showRecoveryLog() {
        val ctx = context ?: return
        val log = UiPrefs.getRecoveryLog(ctx)
        val text = if (log.isEmpty()) ctx.getString(R.string.sk_set_rec_log_empty) else log.reversed().joinToString("\n")
        val tv = TextView(ctx).apply {
            this.text = text; setTextColor(yellow); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f)); setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(tv) }
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle(ctx.getString(R.string.sk_set_rec_log_title))
            .setView(scroll)
            .setPositiveButton(ctx.getString(R.string.sk_set_close), null)
            .setNegativeButton(ctx.getString(R.string.sk_set_rec_log_clear)) { _, _ -> UiPrefs.clearRecoveryLog(ctx) }
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    // ---- Export / Import. The panel itself lives in EximPanel so the account wizard can open the
    //      same dialog on a fresh install; this page keeps only its summary row. ----------------
    private var eximPageStatusTv: TextView? = null
    private var eximPermTv: TextView? = null
    private var eximPermBox: View? = null

    /** First section of the page: heading + a tappable summary row that opens the panel. */
    private fun addExportImportSection(c: LinearLayout) {
        val ctx = requireContext()
        c.addView(groupHeader(getString(R.string.sk_set_sec_exim), first = true))
        // The way in: the section's main action, so the thick pill (白い熊, 2026-07-28; the
        // settings-page pill rule, 2026-11-07). The status line sits beneath with the explanation.
        val (row, _) = actionRow(getString(R.string.sk_exim_open).removeSuffix("▸").trim(),
            getString(R.string.sk_exim_page_summary), main = true, glyph = SkGlyph.IMPORT) {
            // onClosed, not onImported: the directory and the permission can both change inside
            // the panel, and cancelling out of it must still leave this page truthful.
            EximPanel.show(ctx, mAccountService, onClosed = {
                refreshEximPageStatus()
                refreshEximPermission()
            })
        }
        val status = TextView(ctx).apply {
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16f), dp(3f), dp(8f), 0)
        }
        row.addView(status, matchWrap())
        eximPageStatusTv = status
        c.addView(row, matchWrap().apply { leftMargin = dp(72f); rightMargin = dp(16f); bottomMargin = dp(6f) })
        // ---- Chat files. A second, separate way in — it does NOT replace the backup panel above.
        //      It belongs in this section because the export made the shape of the problem plain:
        //      almost all of the weight is old chat attachments (白い熊, 2026-07-29).
        val (filesRow, _) = actionRow(getString(R.string.sk_files_open).removeSuffix("▸").trim(),
            getString(R.string.sk_files_open_summary), glyph = SkGlyph.DIRECTORY) {
            startActivity(cx.ring.client.ChatFilesActivity.intent(ctx))
        }
        c.addView(filesRow, matchWrap().apply { leftMargin = dp(72f); rightMargin = dp(16f); bottomMargin = dp(6f) })
        // ---- All-files access. Nothing in the panel can run without it, so it is a proper pill
        //      rather than a line of small print (白い熊, 2026-07-28). It opens the system screen.
        val (permBox, perm) = actionRow(getString(R.string.sk_set_all_files), glyph = SkGlyph.DIRECTORY) { EximPanel.openAllFilesSettings(ctx) }
        eximPermTv = perm
        eximPermBox = permBox
        c.addView(permBox, matchWrap().apply { leftMargin = dp(72f); rightMargin = dp(16f); bottomMargin = dp(8f) })
        refreshEximPermission()
        // ---- Automation (moved here from its standalone settings row, 白い熊 2026-07-25):
        //      the master switch + token live next to Export/Import because the 保存復元 batch
        //      backup is token-gated automation of exactly this export. ----
        val autoBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(72f), 0, dp(16f), dp(6f))
        }
        // Contract v2 (2026-09-04): three rows — the master switch (default ON), the token
        // requirement (default OFF), and the token itself, shown ONLY while it is being asked for.
        // A 48-character secret sitting under an off switch invites 白い熊 to paste it somewhere it
        // will do nothing.
        autoBox.addView(orSwitchRow(getString(R.string.sk_set_auto_switch), AutomationPrefs.isEnabled(ctx)) {
            AutomationPrefs.setEnabled(ctx, it)
        })

        // The token rows, grouped so row 2 can hide them as one.
        val tokenBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            visibility = if (AutomationPrefs.isTokenRequired(ctx)) View.VISIBLE else View.GONE
        }

        autoBox.addView(orSwitchRow(getString(R.string.sk_set_auto_token_switch), AutomationPrefs.isTokenRequired(ctx)) {
            AutomationPrefs.setTokenRequired(ctx, it)
            tokenBox.visibility = if (it) View.VISIBLE else View.GONE
        })
        autoBox.addView(orMini(getString(R.string.sk_set_auto_token_note)))

        tokenBox.addView(orMini(getString(R.string.sk_set_auto_token_label)))
        val tokenTv = TextView(ctx).apply {
            text = AutomationPrefs.getToken(ctx)
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(2f), 0, dp(2f))
            setOnClickListener {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(ctx.getString(R.string.sk_set_auto_token_clip), AutomationPrefs.getToken(ctx)))
                Flash.show(ctx, ctx.getString(R.string.sk_set_auto_token_copied))
            }
        }
        tokenBox.addView(tokenTv)
        tokenBox.addView(actionRow(getString(R.string.sk_set_auto_token_regen), opens = false, glyph = SkGlyph.RESET) {
            tokenTv.text = AutomationPrefs.regenerateToken(ctx)
            Flash.show(ctx, ctx.getString(R.string.sk_set_auto_token_regenerated), Toast.LENGTH_LONG)
        }.first)
        autoBox.addView(tokenBox)
        autoBox.addView(actionRow(getString(R.string.sk_set_auto_details), glyph = SkGlyph.INFO) {
            (parentFragment as? SettingsFragment)?.goToAutomationSettings()
        }.first)
        c.addView(autoBox)
        refreshEximPageStatus()
    }

    /** SAF listing can be slow — query the latest export off the main thread. */
    private fun refreshEximPageStatus() {
        val app = context?.applicationContext ?: return
        Thread {
            val status = SettingsExport.lastExportStatus(app)
            // Red means "there is no backup", nothing else. Checking only the SAF directory made a
            // perfectly good direct-path export read as a warning (白い熊, 2026-07-28).
            val warn = !SettingsExport.exportExists(app)
            eximPageStatusTv?.post {
                eximPageStatusTv?.let { it.text = status; it.setTextColor(if (warn) warnRed else yellow) }
            }
        }.start()
    }

    private fun refreshEximPermission() {
        val tv = eximPermTv ?: return
        val ctx = context ?: return
        val ok = SettingsExport.hasAllFilesAccess()
        // State only — NO directory here. Showing the path made the pill read as "tap to change
        // the folder", and tapping it opens the system permission screen instead (白い熊,
        // 2026-07-28). The folder belongs to the status line and to the panel's own folder box.
        tv.text = ctx.getString(R.string.sk_set_all_files_state,
            ctx.getString(if (ok) R.string.sk_exim_perm_granted else R.string.sk_exim_perm_missing))
        tv.setTextColor(if (ok) yellow else warnRed)
        // Once granted this is settled business: fade it back so it stops competing with the
        // control that actually does something. It shouts only while it still needs attention.
        eximPermBox?.alpha = if (ok) 0.35f else 1f
    }

    private fun groupHeader(title: String, first: Boolean = false): View {
        val ctx = requireContext()
        // Inside a folding section the folding heading already carries this title.
        if (swallowHeader) {
            swallowHeader = false
            return View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0) }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(0, if (first) dp(12f) else dp(10f), 0, dp(2f))
            if (!first) addView(View(ctx).apply {
                setBackgroundColor(yellow)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(dp(36f), dp(8f), 0, 0)
                addView(TextView(ctx).apply {
                    text = title
                    setTextColor(yellow)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(View(ctx).apply {
                    setBackgroundColor(yellow)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(2.5f))
                        .apply { topMargin = dp(2f) }
                })
            })
        }
    }

    private fun elementView(e: Element): View {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(54f), dp(6f), dp(16f), dp(2f))
        }
        // sub-heading: underline spans only the text width (bottom band drawable on a wrap_content view)
        col.addView(TextView(ctx).apply {
            text = e.label
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
            background = underlineBg(yellow)
            setPadding(0, 0, dp(6f), dp(4f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        if (e.fontCategory != null) col.addView(fontControls(e.fontCategory))
        if (e.colors.isNotEmpty()) {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = matchWrap()
                setPadding(dp(18f), dp(6f), 0, 0)
            }
            for (role in e.colors) box.addView(colorRow(role))
            col.addView(box)
        }
        e.scale?.let { col.addView(scaleControls(it)) }
        return col
    }

    private fun underlineBg(color: Int): android.graphics.drawable.Drawable {
        val band = android.graphics.drawable.ColorDrawable(color)
        val ld = android.graphics.drawable.LayerDrawable(arrayOf(band))
        ld.setLayerGravity(0, Gravity.BOTTOM)
        ld.setLayerHeight(0, dp(1.5f))
        return ld
    }

    private fun fontControls(category: String): View {
        val ctx = requireContext()
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(18f), dp(6f), 0, 0)
        }
        controls.addView(miniLabel(getString(R.string.sk_set_font_label)))
        controls.addView(valueRow(
            FontPrefs.getFamily(ctx, category).let {
                if (it.isEmpty()) getString(R.string.sk_set_font_inherit) else FontUtil.labelForFamily(ctx, it)
            }) { showFamilyPicker(category) })
        controls.addView(miniLabel(getString(R.string.sk_set_font_weight_label)))
        val w = FontPrefs.getWeight(ctx, category)
        controls.addView(valueRow(if (w == 0) getString(R.string.sk_set_font_inherit) else FontUtil.labelForWeight(ctx, w)) {
            showWeightPicker(category)
        })
        controls.addView(miniLabel(getString(R.string.sk_set_font_size_label)))
        val size = FontPrefs.getSize(ctx, category)
        val sizeValue = TextView(ctx).apply {
            text = if (size > 0f) "${size.toInt()} sp" else ctx.getString(R.string.sk_set_font_size_default)
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(2f), 0, dp(2f))
            isClickable = true
            setOnClickListener { showSizeDialog(category) }
        }
        controls.addView(sizeValue)
        val preview = TextView(ctx).apply {
            text = sample
            setTextColor(yellow)
            val eff = FontPrefs.effectiveSize(ctx, category)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (eff > 0f) eff else 16f)
            typeface = FontUtil.resolveTypeface(
                FontPrefs.effectiveFamily(ctx, category),
                FontPrefs.effectiveWeight(ctx, category)) ?: Typeface.DEFAULT
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
                    FontPrefs.setFont(ctx, category, FontPrefs.getFamily(ctx, category),
                        FontPrefs.getWeight(ctx, category), ns)
                    sizeValue.text = if (ns > 0f) "${ns.toInt()} sp" else ctx.getString(R.string.sk_set_font_size_default)
                    val eff = FontPrefs.effectiveSize(ctx, category)
                    preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (eff > 0f) eff else 16f)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        return controls
    }

    private fun scaleControls(role: ScaleRole): View {
        val ctx = requireContext()
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(18f), dp(6f), 0, 0)
        }
        val cur = role.getPct(ctx)
        controls.addView(miniLabel(role.label))
        val value = TextView(ctx).apply {
            text = "$cur %"
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            rowIcon(this, SkGlyph.SLIDER)
            setPadding(0, dp(2f), 0, dp(2f))
        }
        controls.addView(value)
        controls.addView(SeekBar(ctx).apply {
            max = role.maxPct - role.minPct
            progress = (cur - role.minPct).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = role.minPct + p
                    role.setPct(ctx, v)
                    value.text = "$v %"
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        return controls
    }

    private fun colorRow(role: ColorRole): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = matchWrap()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2f), 0, dp(2f))
            isClickable = true
            setOnClickListener { showColorPicker(role) }
        }
        row.addView(TextView(ctx).apply {
            text = role.label
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            rowIcon(this, SkGlyph.DROP)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28f), dp(22f)).apply { marginEnd = dp(10f) }
            background = swatch(roleColor(role.key))
        })
        row.addView(TextView(ctx).apply {
            text = if (ColorPrefs.isSet(ctx, role.key)) hex(roleColor(role.key)) else ctx.getString(R.string.sk_set_default_value)
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        return row
    }

    private fun swatch(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(4f).toFloat()
        setColor(color)
        setStroke(dp(1f), skColor(SkRole.DIVIDER))
    }

    private fun hex(c: Int): String =
        if ((c ushr 24) == 0xFF) String.format("#%06X", c and 0xFFFFFF)
        else String.format("#%08X", c)

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
        rowIcon(this, SkGlyph.TEXT)
        setPadding(0, dp(2f), 0, dp(2f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private data class Opt(val label: String, val value: String, val addMarker: Boolean)

    private fun showFamilyPicker(cat: String) {
        val ctx = context ?: return
        val weight = FontPrefs.effectiveWeight(ctx, cat)
        val opts = ArrayList<Opt>()
        for ((label, value) in FontUtil.families(ctx)) opts.add(Opt(label, value, false))
        for (f in FontPrefs.getFontFiles(ctx)) opts.add(Opt(f.name, "file:${f.absolutePath}", false))
        opts.add(Opt(ctx.getString(R.string.sk_set_font_add), "", true))
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
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle(ctx.getString(R.string.sk_set_font_label))
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
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun showWeightPicker(cat: String) {
        val ctx = context ?: return
        val labels = FontUtil.weights(ctx).map { it.first }.toTypedArray()
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle(ctx.getString(R.string.sk_set_font_weight_title))
            .setItems(labels) { _, which ->
                FontPrefs.setFont(ctx, cat, FontPrefs.getFamily(ctx, cat),
                    FontUtil.WEIGHTS[which].second, FontPrefs.getSize(ctx, cat))
                rebuild()
            }
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun showSizeDialog(cat: String) {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = ctx.getString(R.string.sk_set_font_size_hint)
            val s = FontPrefs.getSize(ctx, cat)
            if (s > 0f) setText(s.toInt().toString())
        }
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle(ctx.getString(R.string.sk_set_font_size_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = (input.text.toString().toFloatOrNull() ?: 0f).coerceIn(0f, 300f)
                FontPrefs.setFont(ctx, cat, FontPrefs.getFamily(ctx, cat), FontPrefs.getWeight(ctx, cat), v)
                rebuild()
            }
            .setNeutralButton(ctx.getString(R.string.sk_set_default_value)) { _, _ ->
                FontPrefs.setFont(ctx, cat, FontPrefs.getFamily(ctx, cat), FontPrefs.getWeight(ctx, cat), 0f)
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun showColorPicker(role: ColorRole) {
        val ctx = context ?: return
        val initial = roleColor(role.key)
        // channels: 0=alpha 1=red 2=green 3=blue
        val ch = intArrayOf(Color.alpha(initial), Color.red(initial), Color.green(initial), Color.blue(initial))
        fun current() = Color.argb(ch[0], ch[1], ch[2], ch[3])

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
        }
        val preview = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56f))
            background = swatch(initial)
        }
        box.addView(preview)

        var updating = false
        val hexInput = EditText(ctx).apply {
            setText(hex(initial)); hint = "#AARRGGBB"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, dp(10f), 0, dp(2f))
        }
        box.addView(hexInput)

        val names = arrayOf(ctx.getString(R.string.sk_set_col_alpha), ctx.getString(R.string.sk_set_col_red),
            ctx.getString(R.string.sk_set_col_green), ctx.getString(R.string.sk_set_col_blue))
        val labels = arrayOfNulls<TextView>(4)
        val bars = arrayOfNulls<SeekBar>(4)

        fun syncPreviewAndLabels() {
            preview.background = swatch(current())
            for (i in 0..3) labels[i]?.text = "${names[i]}:  ${ch[i]}"
        }
        for (i in 0..3) {
            labels[i] = miniLabel("${names[i]}:  ${ch[i]}").also { box.addView(it) }
            bars[i] = SeekBar(ctx).apply {
                max = 255; progress = ch[i]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        ch[i] = p
                        labels[i]?.text = "${names[i]}:  $p"
                        preview.background = swatch(current())
                        updating = true
                        hexInput.setText(hex(current())); hexInput.setSelection(hexInput.text.length)
                        updating = false
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }.also { box.addView(it) }
        }
        hexInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (updating) return
                var x = s.toString().trim()
                if (x.isNotEmpty() && !x.startsWith("#")) x = "#$x"
                val parsed = try { Color.parseColor(x) } catch (e: Exception) { null } ?: return
                ch[0] = Color.alpha(parsed); ch[1] = Color.red(parsed); ch[2] = Color.green(parsed); ch[3] = Color.blue(parsed)
                updating = true
                for (i in 0..3) bars[i]?.progress = ch[i]
                updating = false
                syncPreviewAndLabels()
            }
        })

        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle(role.label)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> ColorPrefs.setColor(ctx, role.key, current()); rebuild() }
            .setNeutralButton(ctx.getString(R.string.sk_set_default_value)) { _, _ -> ColorPrefs.reset(ctx, role.key); rebuild() }
            .setNegativeButton(android.R.string.cancel, null)
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private companion object {
        const val KEY_ORDER = "sk_ui_section_order"
        const val KEY_OPEN = "sk_ui_sections_open"
        const val KEY_LANG_FIRST = "sk_ui_language_first_v1"

        /** Icon and section colour of the older per-surface groups, by the group's stable key. */
        val GROUP_LOOK: Map<String, Pair<SkGlyph, SkRole>> = mapOf(
            "Default" to (SkGlyph.TEXT to SkRole.SECTION_BASE),
            "Chat list" to (SkGlyph.LIST to SkRole.SECTION_FRAME),
            "Conversation" to (SkGlyph.MESSAGE to SkRole.SECTION_BASE),
            "Message menu" to (SkGlyph.LIST to SkRole.SECTION_REACH),
            "Call events" to (SkGlyph.CLOCK to SkRole.SECTION_BASE),
            "Invitations" to (SkGlyph.IMPORT to SkRole.SECTION_REACH),
            "Top bar" to (SkGlyph.SPLIT to SkRole.SECTION_FRAME),
            "Settings" to (SkGlyph.GEAR to SkRole.SECTION_BASE),
            "Account list" to (SkGlyph.PERSON to SkRole.SECTION_FRAME),
            "Presence dots (chat list)" to (SkGlyph.EYE to SkRole.SECTION_BASE),
            "Flashes (toasts)" to (SkGlyph.BELL to SkRole.SECTION_FLASH),
            "Status & indicators" to (SkGlyph.BOLT to SkRole.SECTION_BASE),
            "Connectivity help page" to (SkGlyph.INFO to SkRole.SECTION_WORDS),
            "Connection monitor" to (SkGlyph.NETWORK to SkRole.SECTION_BASE),
            "Launcher shortcuts" to (SkGlyph.HOME to SkRole.SECTION_REACH),
        )

        /** The same for the colour-role sections. */
        val ROLE_LOOK: Map<SkSection, Pair<SkGlyph, SkRole>> = mapOf(
            SkSection.FOUNDATION to (SkGlyph.DROP to SkRole.SECTION_BASE),
            SkSection.TEXT to (SkGlyph.TEXT to SkRole.SECTION_WORDS),
            SkSection.BUTTONS to (SkGlyph.DOT to SkRole.SECTION_CONTROLS),
            SkSection.DIALOGS to (SkGlyph.OPEN to SkRole.SECTION_REACH),
            SkSection.SWITCHES to (SkGlyph.TOGGLE to SkRole.SECTION_CONTROLS),
            SkSection.MENUS to (SkGlyph.LIST to SkRole.SECTION_REACH),
            SkSection.MARKS to (SkGlyph.CHECK to SkRole.SECTION_CONTROLS),
            SkSection.LISTS to (SkGlyph.LIST to SkRole.SECTION_FRAME),
            SkSection.FIELDS to (SkGlyph.EDIT to SkRole.SECTION_WORDS),
            SkSection.BARS to (SkGlyph.SPLIT to SkRole.SECTION_FRAME),
            SkSection.CALLS to (SkGlyph.PHONE to SkRole.SECTION_CONTROLS),
            SkSection.PAGES to (SkGlyph.GEAR to SkRole.SECTION_BASE),
        )

        /** The default order of the page. Arranged so that no two neighbours share a section
         *  colour; keep that when a section is added. */
        val DEFAULT_ORDER = listOf(
            "language", "exim", "recovery", "decline", "diag",
            "g:Default", "g:Chat list", "g:Conversation", "g:Message menu", "g:Call events",
            "g:Invitations", "g:Top bar", "g:Settings", "g:Account list", "g:Presence dots (chat list)",
            "g:Flashes (toasts)", "g:Status & indicators", "g:Connectivity help page",
            "g:Connection monitor", "g:Launcher shortcuts",
            "r:FOUNDATION", "r:TEXT", "r:BUTTONS", "r:DIALOGS", "r:SWITCHES", "r:MENUS", "r:MARKS",
            "r:LISTS", "r:FIELDS", "r:BARS", "r:CALLS", "r:PAGES",
            "maintenance",
        )
        const val TAG_SWITCH = "sk_switch_row"
        const val TAG_NOTE = "sk_note"
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
