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
import cx.ring.utils.FontPrefs
import cx.ring.utils.SettingsExport
import cx.ring.utils.FontUtil
import cx.ring.utils.UiPrefs

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
    private data class Group(val title: String, val elements: List<Element>)

    private val groups = listOf(
        Group("Default", listOf(
            Element("Default (all text)", FontPrefs.DEFAULT, emptyList()))),
        Group("Chat list", listOf(
            Element("Contact name", FontPrefs.LIST_TITLE, listOf(ColorRole("Color", ColorPrefs.LIST_NAME))),
            Element("Last-message preview", FontPrefs.LIST_PREVIEW, listOf(ColorRole("Color", ColorPrefs.LIST_PREVIEW))),
            Element("Timestamp", FontPrefs.LIST_DATE, listOf(ColorRole("Color", ColorPrefs.LIST_DATE))))),
        Group("Conversation", listOf(
            Element("Conversation title", FontPrefs.CONV_TITLE, listOf(ColorRole("Color", ColorPrefs.CONV_TITLE))),
            Element("Sent message", FontPrefs.CHAT_TEXT, listOf(ColorRole("Text", ColorPrefs.MSG_SENT), ColorRole("Bubble fill", ColorPrefs.MSG_SENT_FILL), ColorRole("Bubble border", ColorPrefs.MSG_SENT_BORDER))),
            Element("Received message", null, listOf(ColorRole("Text", ColorPrefs.MSG_RECEIVED), ColorRole("Bubble fill", ColorPrefs.MSG_RECEIVED_FILL), ColorRole("Bubble border", ColorPrefs.MSG_RECEIVED_BORDER))),
            Element("Message timestamp", FontPrefs.MSG_TIME, listOf(ColorRole("Color", ColorPrefs.MSG_TIME))),
            Element("Link-preview card", null, listOf(
                ColorRole("Title", ColorPrefs.LINK_TITLE),
                ColorRole("Description", ColorPrefs.LINK_DESC),
                ColorRole("Domain", ColorPrefs.LINK_DOMAIN),
                ColorRole("Card fill", ColorPrefs.LINK_CARD_FILL),
                ColorRole("Card border", ColorPrefs.LINK_CARD_BORDER))),
            Element("File message", null, listOf(
                ColorRole("Name / size", ColorPrefs.FILE_NAME),
                ColorRole("Download arrow", ColorPrefs.FILE_ARROW),
                ColorRole("Card fill", ColorPrefs.FILE_CARD_FILL),
                ColorRole("Card border", ColorPrefs.FILE_CARD_BORDER))))),
        Group("Call events", listOf(
            Element("Call message", null, listOf(
                ColorRole("Text", ColorPrefs.CALL_TEXT),
                ColorRole("Pill fill", ColorPrefs.CALL_FILL),
                ColorRole("Pill border", ColorPrefs.CALL_BORDER))))),
        Group("Message menu", listOf(
            Element("Long-press menu", null, listOf(
                ColorRole("Text", ColorPrefs.MENU_TEXT),
                ColorRole("Fill", ColorPrefs.MENU_FILL),
                ColorRole("Border", ColorPrefs.MENU_BORDER))))),
        Group("Top bar", listOf(
            Element("Search hint", FontPrefs.SEARCH_HINT, listOf(ColorRole("Color", ColorPrefs.SEARCH_HINT))))),
        Group("Settings", listOf(
            Element("Settings text", FontPrefs.SETTINGS, listOf(ColorRole("Color", ColorPrefs.SETTINGS))),
            Element("Settings icons", null, listOf(ColorRole("Tint", ColorPrefs.SETTINGS_ICON))))),
        Group("Presence dots (chat list)", listOf(
            Element("Online (connected)", null, listOf(ColorRole("Dot", ColorPrefs.PRESENCE_CONNECTED))),
            Element("Reachable (available)", null, listOf(ColorRole("Dot", ColorPrefs.PRESENCE_AVAILABLE))))),
        Group("Account list", listOf(
            Element("Invitation badge", null, listOf(
                ColorRole("Fill", ColorPrefs.BADGE_FILL),
                ColorRole("Border", ColorPrefs.BADGE_BORDER))))),
        Group("Status & indicators", listOf(
            Element("Sending icon", null, listOf(ColorRole("Tint", ColorPrefs.STATUS_SENDING))),
            Element("Sent / delivered icon", null, listOf(ColorRole("Tint", ColorPrefs.STATUS_SUCCESS))),
            Element("Connection dot — connected", null, listOf(ColorRole("Tint", ColorPrefs.STATUS_ONLINE))),
            Element("Connection dot — connecting", null, listOf(ColorRole("Tint", ColorPrefs.STATUS_CONNECTING))),
            Element("Connection dot — disconnected", null, listOf(ColorRole("Tint", ColorPrefs.STATUS_OFFLINE))),
            Element("Account dot size", null, emptyList(), ScaleRole("Size (% of default)",
                { c -> (UiPrefs.getStatusDotScale(c) * 100f).toInt() },
                { c, v -> UiPrefs.setStatusDotScale(c, v / 100f) }, 50, 300)),
            Element("Unread row border", null, listOf(ColorRole("Border", ColorPrefs.UNREAD_BORDER))))),
        Group("Flashes (toasts)", listOf(
            Element("Flash message (e.g. \"Copied to clipboard\")", FontPrefs.FLASH, listOf(
                ColorRole("Text", ColorPrefs.FLASH_TEXT),
                ColorRole("Background", ColorPrefs.FLASH_FILL),
                ColorRole("Border", ColorPrefs.FLASH_BORDER))))),
        Group("Connection monitor", listOf(
            Element("Account healthy", null, listOf(ColorRole("Color", ColorPrefs.MONITOR_HEALTHY))),
            Element("Account connecting", null, listOf(ColorRole("Color", ColorPrefs.MONITOR_CONNECTING))),
            Element("Account problem (offline / not syncing)", null, listOf(ColorRole("Color", ColorPrefs.MONITOR_PROBLEM))),
            Element("Connection: connected", null, listOf(ColorRole("Color", ColorPrefs.MONITOR_CONNECTED))),
            Element("Connection: in progress", null, listOf(ColorRole("Color", ColorPrefs.MONITOR_IDLE))),
            Element("Contact: disconnected / offline", null, listOf(ColorRole("Color", ColorPrefs.MONITOR_OFFLINE))),
            Element("Fold triangle size", null, emptyList(), ScaleRole("Size (% of row text)",
                { c -> (UiPrefs.getMonitorFoldScale(c) * 100f).toInt() },
                { c, v -> UiPrefs.setMonitorFoldScale(c, v / 100f) }, 20, 300)))),
        Group("Connectivity help page", listOf(
            Element("Headings", FontPrefs.INFO_HEADING, listOf(ColorRole("Color", ColorPrefs.INFO_HEADING))),
            Element("Body text", FontPrefs.INFO_BODY, listOf(ColorRole("Color", ColorPrefs.INFO_BODY))),
            Element("Button pills", null, listOf(
                ColorRole("Text", ColorPrefs.INFO_PILL_TEXT),
                ColorRole("Border", ColorPrefs.INFO_PILL_BORDER),
                ColorRole("Fill", ColorPrefs.INFO_PILL_FILL))))),
        Group("Launcher shortcuts", listOf(
            Element("Chat / call badge (applies to newly created shortcuts)", null, listOf(
                ColorRole("Glyph + ring", ColorPrefs.SHORTCUT_ICON),
                ColorRole("Glyph outline", ColorPrefs.SHORTCUT_FILL))))),
    )

    private val yellow = 0xFFFFFF00.toInt()
    private val grey = 0xFFAAAAAA.toInt()
    private val dim = 0xFFC8C800.toInt()      // kxkb_yellow_dim — summaries/captions
    private val warnRed = 0xFFFF5252.toInt()  // warning state (dir unset / no export yet)
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
            Flash.show(ctx, "Added $name", Toast.LENGTH_SHORT)
            rebuild()
        } catch (e: Exception) {
            Flash.show(ctx, "Import failed: ${e.message}", Toast.LENGTH_LONG)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.frag_fonts_settings, parent, false)
        root.tag = FontUtil.SKIP_SETTINGS_FONT_TAG
        container = root.findViewById(R.id.fonts_container)
        rebuild()
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recoveryDisposable.clear()
        eximDialog?.dismiss()
        eximDialog = null
        eximPageStatusTv = null
        (activity as? cx.ring.client.HomeActivity)?.refreshThemedViews()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun rebuild() {
        val c = container ?: return
        c.removeAllViews()
        addExportImportSection(c)
        addOnlineRecoverySection(c)
        c.addView(groupHeader("App language"))
        c.addView(languageRow())
        for (g in groups) {
            c.addView(groupHeader(g.title))
            for (e in g.elements) c.addView(elementView(e))
        }
    }

    /** A tappable row showing the current app language; opens a locale picker. Overrides the
     *  phone locale for this app only (AppCompat per-app locales; localeConfig is declared). */
    private fun languageRow(): View {
        val ctx = requireContext()
        val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val label = if (current.isEmpty) "System default" else {
            val loc = java.util.Locale.forLanguageTag(current.toLanguageTags())
            loc.getDisplayName(loc).replaceFirstChar { it.uppercase() }
        }
        return TextView(ctx).apply {
            text = "Language: $label"
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(72f), dp(10f), dp(16f), dp(10f))
            layoutParams = matchWrap()
            setOnClickListener { showLanguageDialog() }
        }
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

    private fun showLanguageDialog() {
        val ctx = context ?: return
        val pairs = ArrayList<Pair<String, String>>()
        pairs.add("" to "System default")
        readSupportedLocaleTags().forEach { tag ->
            val loc = java.util.Locale.forLanguageTag(tag)
            pairs.add(tag to "${loc.getDisplayName(loc).replaceFirstChar { it.uppercase() }}  ($tag)")
        }
        val ordered = listOf(pairs.first()) + pairs.drop(1).sortedBy { it.second }
        val names = ordered.map { it.second }.toTypedArray()
        val currentTag = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val checked = ordered.indexOfFirst { it.first == currentTag }.let { if (it < 0) 0 else it }
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle("App language")
            .setSingleChoiceItems(names, checked) { d, which ->
                val tag = ordered[which].first
                cx.ring.utils.UiPrefs.setAppLanguage(ctx, tag)   // survives updates; re-asserted on start
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    else androidx.core.os.LocaleListCompat.forLanguageTags(tag))
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    // ---- Online recovery section (top of the page) -----------------------------------------
    private fun addOnlineRecoverySection(c: LinearLayout) {
        val ctx = requireContext()
        c.addView(groupHeader("Online recovery"))
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(72f), dp(6f), dp(16f), dp(4f))
        }
        box.addView(orSwitchRow("Full DHT — proxy off, robustness-first (default)", UiPrefs.isFullDhtMode(ctx)) {
            UiPrefs.setFullDhtMode(ctx, it)
            mAccountService.setProxyEnabled(!it)   // authoritative + immediate: full DHT → all accounts proxy off; off → proxy on
            // Same log + verification probe as the ⬡ tap — this silent write is what made the
            // recovery log untrustworthy about the active mode (2026-07-23 retraction).
            cx.ring.utils.ConnectionWatchdog.onDhtModeSwitched(ctx, mAccountService)
        })
        box.addView(orSwitchRow("Full DHT on while charging (default on — battery is free)", UiPrefs.isFullDhtWhileCharging(ctx)) {
            UiPrefs.setFullDhtWhileCharging(ctx, it)
        })
        box.addView(orSwitchRow("Base check — detect a stuck link, no pings", UiPrefs.isRecoveryBaseEnabled(ctx)) {
            UiPrefs.setRecoveryBaseEnabled(ctx, it); rebuild()   // mutually exclusive → rebuild to reflect ping
        })
        box.addView(orTapRow("How this works  ⓘ") { showRecoveryInfo() })
        box.addView(orSwitchRow("Ping check — send test pings", UiPrefs.isRecoveryPingEnabled(ctx)) {
            UiPrefs.setRecoveryPingEnabled(ctx, it); rebuild()   // mutually exclusive → rebuild to reflect base
        })
        box.addView(orTapRow("①  Send pings from:  ${recoveryAccountLabel(ctx)}") { showRecoveryAccountPicker() })
        box.addView(orTapRow("②  Pick test conversation…") { showRecoverySwarmPicker() })
        box.addView(orMini("…or paste the conversation ID (without “swarm:”)"))
        box.addView(orSwarmField(ctx))
        box.addView(orTapRow("Check every:  ${UiPrefs.getRecoveryTickMinutes(ctx)} min") {
            showRecoveryNumber("Check interval (minutes)", UiPrefs.getRecoveryTickMinutes(ctx), 1, 60) {
                UiPrefs.setRecoveryTickMinutes(ctx, it); rebuild()
            }
        })
        box.addView(orTapRow("Prune test pings older than:  ${UiPrefs.getRecoveryPruneDays(ctx)} days") {
            showRecoveryNumber("Prune pings older than (days)", UiPrefs.getRecoveryPruneDays(ctx), 0, 90) {
                UiPrefs.setRecoveryPruneDays(ctx, it); rebuild()
            }
        })
        box.addView(orTapRow("View recovery log") { showRecoveryLog() })
        // ---- Data-usage meter (2026-07-25, from the 36-GiB runaway investigation) ----
        box.addView(orSwitchRow("Measure data usage", DataMeter.isActive(ctx)) { on ->
            if (on) {
                DataMeter.start(ctx)
                startMeterLive()
            } else {
                val rec = DataMeter.stop(ctx, meterModeInfo())
                meterLiveRow?.text = "saved: $rec"
            }
        })
        box.addView(TextView(ctx).apply {
            setTextColor(0xFFAAAAAA.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8f), 0, 0, dp(4f)); layoutParams = matchWrap()
            text = if (DataMeter.isActive(ctx)) "measuring…" else "(off — flip on to start a measurement session)"
            meterLiveRow = this
        })
        box.addView(orTapRow("Measurement history") { showDataMeasureHistory() })
        // ---- Unattended sampler (2026-07-26): records without a human at either end ----
        box.addView(orSwitchRow("Record data usage automatically", DataMeter.isSamplingOn(ctx)) { on ->
            DataMeter.setSamplingOn(ctx, on); rebuild()
        })
        if (DataMeter.isSamplingOn(ctx)) {
            box.addView(orTapRow("Sample window:  ${DataMeter.windowLabel(DataMeter.getWindowMinutes(ctx))}") {
                showWindowSlider()
            })
            box.addView(orMini("One line per window. Windows land on the ${UiPrefs.getRecoveryTickMinutes(ctx)}-min check tick, so they are approximate."))
        }
        box.addView(orTapRow("Data usage log") { showDataHistory(DataMeter.hourlyFile(ctx), "Data usage log") })
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
            row.text = "measuring ${DataMeter.elapsedLabel(el)} · ↓${DataMeter.bytesLabel(rx)} ↑${DataMeter.bytesLabel(tx)} · ${DataMeter.netLabel(ctx)}"
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
        showDataHistory(DataMeter.historyFile(ctx), "Data measurements")
    }

    /** Shared renderer for both data logs — the manual session history and the unattended hourly
     *  log. Same themed scroll dialog, same newest-first order, same Clear. */
    private fun showDataHistory(f: java.io.File, title: String) {
        // Shared with the Connection monitor's "Data" dialog (2026-07-26) so the two pages can
        // never render the same log differently.
        context?.let { cx.ring.utils.DataMeterUi.showHistory(it, f, title) }
    }

    private fun orTapRow(text: String, onClick: () -> Unit): View = TextView(requireContext()).apply {
        this.text = text
        setTextColor(yellow)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, dp(12f), dp(12f), dp(12f))
        layoutParams = matchWrap()
        setOnClickListener { onClick() }
    }

    private fun orMini(text: String): View = TextView(requireContext()).apply {
        this.text = text
        setTextColor(0xFFAAAAAA.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(12f), 0, dp(2f))
        layoutParams = matchWrap()
    }

    private fun orSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap()
            setPadding(0, dp(10f), dp(12f), dp(6f))
            addView(TextView(ctx).apply {
                text = label; setTextColor(yellow); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
                isChecked = checked
                thumbTintList = android.content.res.ColorStateList.valueOf(yellow)
                trackTintList = android.content.res.ColorStateList.valueOf(0xFF666600.toInt())
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
        }
    }

    private fun orSwarmField(ctx: android.content.Context): View = EditText(ctx).apply {
        setText(UiPrefs.getRecoveryTestSwarm(ctx))
        hint = "paste conversation ID"
        setTextColor(yellow)
        setHintTextColor(0xFF777777.toInt())
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
        if (id.isEmpty()) return "not set"
        val a = mAccountService.getAccount(id) ?: return id.take(8)
        return a.registeredName.ifBlank { a.alias.orEmpty() }.ifBlank { a.accountId.take(8) }
    }

    private fun showRecoveryInfo() {
        val ctx = context ?: return
        val html = """
            <b><u>What this does</u></b><br>
            When DHT proxy is on, the single proxy link carries the setup of every connection. If it
            wedges, messages quietly stop flowing in and out while the app still shows “connected”.
            Online recovery notices that and briefly drops every account to the full distributed DHT
            (proxy off → settle → on) to recover — keeping the battery benefit of proxy mode the rest of
            the time.<br><br>
            <b><u>Base check (no pings)</u></b><br>
            Passive. Watches for “nothing is connecting” — registered accounts that are trying but have
            nothing actually established for a few minutes. Sends no messages: zero traffic, zero battery
            cost. A good always-on safety net.<br><br>
            <b><u>Ping check (test swarm)</u></b><br>
            Active and sharper. Pick an always-online account (e.g. your own account on another device),
            choose a one-to-one test conversation with it, and the watchdog sends a timestamp ping every
            interval. If a ping isn’t delivered, that is the cue to recover. Set the <b>Send pings from</b>
            account and the <b>test conversation</b> below (clear the account to switch the pings off).<br><br>
            <b><u>Choosing a mode</u></b><br>
            The two checks are <b>mutually exclusive</b> — turning one on turns the other off. With
            <b>both off, online recovery is disabled</b>: no detection and no automatic recovery. Either
            way, the <b>Sync</b> button still recovers on demand.<br><br>
            <b><u>Recovery action</u></b><br>
            On a detected wedge every account is briefly switched proxy off → settle → on; repeated
            wedges back off to a longer settle. Every event is written to the recovery log
            (<b>View recovery log</b>, or tap the Sync icon).
        """.trimIndent()
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle("Online recovery")
            .setMessage(androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT))
            .setPositiveButton(android.R.string.ok, null)
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun showRecoveryAccountPicker() {
        val ctx = context ?: return
        val accs = mAccountService.getAccounts().filter { it.isJami }
        if (accs.isEmpty()) { Flash.show(ctx, "No accounts"); return }
        val labels = accs.map { it.registeredName.ifBlank { it.alias.orEmpty() }.ifBlank { it.accountId.take(8) } }.toTypedArray()
        val current = accs.indexOfFirst { it.accountId == UiPrefs.getRecoveryTestAccount(ctx) }
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle("Send pings from")
            .setSingleChoiceItems(labels, current) { d, which ->
                UiPrefs.setRecoveryTestAccount(ctx, accs[which].accountId)
                d.dismiss(); rebuild()
            }
            // Let it be un-set: clearing the account disables the ping check (nothing to send from).
            .setNeutralButton("Clear") { _, _ -> UiPrefs.setRecoveryTestAccount(ctx, ""); rebuild() }
            .setNegativeButton(android.R.string.cancel, null)
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun showRecoverySwarmPicker() {
        val ctx = context ?: return
        val accId = UiPrefs.getRecoveryTestAccount(ctx)
        if (accId.isEmpty()) { Flash.show(ctx, "Pick the send-from account first (①)"); return }
        val acc = mAccountService.getAccount(accId) ?: return
        val convs = acc.getConversations().toList()
        if (convs.isEmpty()) { Flash.show(ctx, "No conversations on this account"); return }
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
                        .setTitle("Pick test conversation")
                        .setItems(labels) { d, which ->
                            UiPrefs.setRecoveryTestSwarm(c2, vms[which].uri.rawRingId ?: vms[which].uri.uri)
                            d.dismiss(); rebuild()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show().let { cx.ring.utils.DialogTheme.theme(it, c2) }
                }, { context?.let { Flash.show(it, "Could not load conversations") } })
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
            label.text = "${DataMeter.windowLabel(minutes)}   (~$perDay lines/day)"
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
            .setTitle("Sample window")
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
        val text = if (log.isEmpty()) "(no events yet)" else log.reversed().joinToString("\n")
        val tv = TextView(ctx).apply {
            this.text = text; setTextColor(yellow); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f)); setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(tv) }
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle("Recovery log")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear") { _, _ -> UiPrefs.clearRecoveryLog(ctx) }
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    // ---- Export / Import (top of the page — Kōjiki flow, kxkb look) -------------------------
    private var eximDialog: androidx.appcompat.app.AlertDialog? = null
    private var eximFolderTv: TextView? = null
    private var eximStatusTv: TextView? = null
    private var eximPageStatusTv: TextView? = null
    private var eximChecks: List<Pair<SettingsExport.Cat, android.widget.CheckBox>> = emptyList()
    private var pendingExportCats: List<SettingsExport.Cat>? = null
    private var pendingImportCats: List<SettingsExport.Cat>? = null

    private val pickExportDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        SettingsExport.setDirUri(ctx, uri)
        refreshEximStatus()
    }
    private val pickImportFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runEximImport(uri)
    }
    private val exportSaveAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val cats = pendingExportCats
        pendingExportCats = null
        if (uri != null && cats != null) writeExportTo(uri, cats)
    }

    /** First section of the page: heading + a tappable summary row that opens the panel. */
    private fun addExportImportSection(c: LinearLayout) {
        val ctx = requireContext()
        c.addView(groupHeader("Export / Import", first = true))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(72f), dp(6f), dp(16f), dp(6f))
            isClickable = true
            setOnClickListener { showExportImportPanel() }
        }
        row.addView(TextView(ctx).apply {
            text = "Save or load every setting — accounts, fonts, colours, recovery, automation — as selectable categories."
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        val status = TextView(ctx).apply {
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2f), 0, 0)
        }
        row.addView(status)
        eximPageStatusTv = status
        c.addView(row)
        // ---- Automation (moved here from its standalone settings row, 白い熊 2026-07-25):
        //      the master switch + token live next to Export/Import because the 保存復元 batch
        //      backup is token-gated automation of exactly this export. ----
        val autoBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap()
            setPadding(dp(72f), 0, dp(16f), dp(6f))
        }
        autoBox.addView(orSwitchRow("Automation — external control & 保存復元 backups", AutomationPrefs.isEnabled(ctx)) {
            AutomationPrefs.setEnabled(ctx, it)
        })
        autoBox.addView(orMini("Secret token — tap to copy"))
        val tokenTv = TextView(ctx).apply {
            text = AutomationPrefs.getToken(ctx)
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(2f), 0, dp(2f))
            setOnClickListener {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Jami automation token", AutomationPrefs.getToken(ctx)))
                Flash.show(ctx, "Token copied")
            }
        }
        autoBox.addView(tokenTv)
        autoBox.addView(orTapRow("Regenerate token") {
            tokenTv.text = AutomationPrefs.regenerateToken(ctx)
            Flash.show(ctx, "Token regenerated — update your scripts", Toast.LENGTH_LONG)
        })
        autoBox.addView(orTapRow("Automation details & usage  ⓘ") {
            (parentFragment as? SettingsFragment)?.goToAutomationSettings()
        })
        c.addView(autoBox)
        refreshEximPageStatus()
    }

    /** SAF listing can be slow — query the latest export off the main thread. */
    private fun refreshEximPageStatus() {
        val app = context?.applicationContext ?: return
        Thread {
            val status = SettingsExport.lastExportStatus(app)
            val warn = SettingsExport.latestExport(app) == null
            eximPageStatusTv?.post {
                eximPageStatusTv?.let { it.text = status; it.setTextColor(if (warn) warnRed else dim) }
            }
        }.start()
    }

    /** The Export/Import panel: directory box, latest-export line, category checkboxes,
     *  and the ArcaneChat pill row (Cancel left; Import + Export right). */
    private fun showExportImportPanel() {
        val ctx = context ?: return
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(12f), dp(20f), dp(8f))
        }
        root.addView(TextView(ctx).apply {
            text = "Save or load every setting as selectable categories."
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        val dirBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(10f).toFloat()
                setStroke(dp(2f), yellow)
            }
            isClickable = true
            setOnClickListener { pickExportDir.launch(SettingsExport.getDirUri(ctx)) }
        }
        dirBox.addView(TextView(ctx).apply {
            text = "Export directory (tap to choose)"
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        eximFolderTv = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        }
        dirBox.addView(eximFolderTv)
        root.addView(dirBox, matchWrap().apply { topMargin = dp(10f) })
        eximStatusTv = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        root.addView(eximStatusTv, matchWrap().apply { topMargin = dp(8f); bottomMargin = dp(8f) })
        root.addView(eximDivider())

        val checks = ArrayList<Pair<SettingsExport.Cat, android.widget.CheckBox>>()
        val selectAll = eximCheckbox("Select all", bold = true).apply { isChecked = true }
        root.addView(selectAll)
        for (cat in SettingsExport.Cat.entries) {
            val cb = eximCheckbox(cat.label).apply { isChecked = true }
            checks.add(cat to cb)
            root.addView(cb)
        }
        selectAll.setOnCheckedChangeListener { _, on -> checks.forEach { it.second.isChecked = on } }
        eximChecks = checks
        root.addView(eximDivider(topGap = 8))

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14f), 0, 0)
        }
        buttons.addView(pillButton("Cancel") { eximDialog?.dismiss() })
        buttons.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        buttons.addView(pillButton("Import") { onEximImport() }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8f) }
        })
        buttons.addView(pillButton("Export") { onEximExport() })
        root.addView(buttons)

        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }
        eximDialog = cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle("Export / Import — 白い熊 GNU Jami")
            .setView(scroll)
            .setOnDismissListener {
                eximFolderTv = null; eximStatusTv = null; eximChecks = emptyList(); eximDialog = null
            }
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
        refreshEximStatus()
    }

    private fun eximDivider(topGap: Int = 0): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
            .apply { topMargin = dp(topGap.toFloat()) }
        setBackgroundColor(yellow)
        alpha = 0.4f
    }

    private fun eximCheckbox(label: String, bold: Boolean = false): android.widget.CheckBox =
        android.widget.CheckBox(requireContext()).apply {
            text = label
            setTextColor(yellow)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            buttonTintList = android.content.res.ColorStateList.valueOf(yellow)
            setPadding(dp(8f), dp(7f), 0, dp(7f))
        }

    /** ArcaneChat pill: black fill, 1.5dp yellow stroke, full-round corners, yellow ripple. */
    private fun pillButton(label: String, onClick: () -> Unit): android.widget.Button =
        android.widget.Button(requireContext()).apply {
            text = label
            isAllCaps = false
            setTextColor(yellow)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((yellow and 0x00FFFFFF) or 0x33000000),
                GradientDrawable().apply {
                    setColor(Color.BLACK)
                    cornerRadius = dp(50f).toFloat()
                    setStroke(dp(1.5f), yellow)
                }, null)
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(20f), dp(6f), dp(20f), dp(6f))
            stateListAnimator = null
            setOnClickListener { onClick() }
        }

    private fun refreshEximStatus() {
        val ctx = context ?: return
        val dirName = SettingsExport.getExportDir(ctx)?.name ?: SettingsExport.getDirUri(ctx)?.lastPathSegment
        eximFolderTv?.let {
            it.text = dirName ?: "Not set — tap to choose a directory"
            it.setTextColor(if (dirName == null) warnRed else yellow)
        }
        val app = ctx.applicationContext
        Thread {
            val status = SettingsExport.lastExportStatus(app)
            val warn = SettingsExport.latestExport(app) == null
            eximStatusTv?.post {
                eximStatusTv?.let { it.text = status; it.setTextColor(if (warn) warnRed else dim) }
            }
        }.start()
        refreshEximPageStatus()
    }

    private fun selectedCats(): List<SettingsExport.Cat> =
        eximChecks.filter { it.second.isChecked }.map { it.first }

    private fun postToUi(r: () -> Unit) { activity?.runOnUiThread(r) }

    private fun onEximExport() {
        val ctx = context ?: return
        val cats = selectedCats()
        if (cats.isEmpty()) { Flash.show(ctx, "No categories selected."); return }
        val app = ctx.applicationContext
        if (SettingsExport.getExportDir(app) != null) {
            Flash.show(ctx, "Exporting…")
            Thread {
                try {
                    val (bytes, notes) = buildExportBytes(app, cats)
                    val dir = SettingsExport.getExportDir(app)
                        ?: throw IllegalStateException("directory unavailable")
                    val name = SettingsExport.exportFileName()
                    val file = dir.createFile("application/zip", name)
                        ?: throw IllegalStateException("could not create $name")
                    app.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("no stream")
                    postToUi { showExportDone(name, notes) }
                } catch (e: Exception) {
                    postToUi { context?.let { Flash.show(it, "Export failed: ${e.message}", Toast.LENGTH_LONG) } }
                }
            }.start()
        } else {
            // no directory configured — fall back to a save-as picker
            pendingExportCats = cats
            exportSaveAs.launch(SettingsExport.exportFileName())
        }
    }

    private fun writeExportTo(uri: Uri, cats: List<SettingsExport.Cat>) {
        val ctx = context ?: return
        val app = ctx.applicationContext
        Flash.show(ctx, "Exporting…")
        Thread {
            try {
                val (bytes, notes) = buildExportBytes(app, cats)
                app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("no stream")
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "export.zip"
                postToUi { showExportDone(name, notes) }
            } catch (e: Exception) {
                postToUi { context?.let { Flash.show(it, "Export failed: ${e.message}", Toast.LENGTH_LONG) } }
            }
        }.start()
    }

    /** Builds the export zip on the calling (background) thread; collects the daemon account
     *  archives first when the Accounts category is selected. Returns bytes + account notes. */
    private fun buildExportBytes(
        app: android.content.Context, cats: List<SettingsExport.Cat>
    ): Pair<ByteArray, String> {
        var archives = emptyMap<String, ByteArray>()
        var meta: org.json.JSONObject? = null
        var notes = ""
        if (SettingsExport.Cat.ACCOUNTS in cats) {
            val (a, m, n) = SettingsExport.collectAccountArchives(app, mAccountService)
            archives = a; meta = m; notes = n
        }
        return SettingsExport.export(app, cats, archives, meta) to notes
    }

    /** Restores accounts/<id>.gz archives via the daemon; identities already on this device are
     *  skipped. Blocking — call on a background thread. Returns the summary line. */
    private fun importAccounts(app: android.content.Context, bytes: ByteArray): String {
        val archives = SettingsExport.accountArchivesIn(bytes)
        if (archives.isEmpty()) return "Accounts: none in this export"
        val meta = SettingsExport.accountsMetaIn(bytes)
        val existing = mAccountService.getAccounts().mapNotNull { it.username }.toSet()
        var imported = 0
        var skipped = 0
        val errors = StringBuilder()
        val cacheDir = java.io.File(app.cacheDir, "eximport").apply { mkdirs() }
        for ((id, data) in archives) {
            val m = meta.optJSONObject(id)
            val uri = m?.optString("uri").orEmpty()
            val label = m?.optString("registeredName").orEmpty()
                .ifBlank { m?.optString("alias").orEmpty() }.ifBlank { id.take(8) }
            if (uri.isNotEmpty() && uri in existing) { skipped++; continue }
            try {
                // The daemon may read the archive asynchronously after addAccount — leave the
                // temp file for the cache auto-cleanup rather than deleting it immediately.
                val f = java.io.File(cacheDir, "import_$id.gz")
                f.writeBytes(data)
                val details = mAccountService
                    .getAccountTemplate(net.jami.model.AccountConfig.ACCOUNT_TYPE_JAMI)
                    .blockingGet()
                // Same shape as the wizard's backup-restore path (initJamiAccountBackup), incl.
                // the fork's connectivity defaults; the archive then carries the account config.
                details[net.jami.model.ConfigKey.ACCOUNT_ALIAS.key] =
                    m?.optString("alias").orEmpty().ifBlank { "Jami account" }
                details[net.jami.model.ConfigKey.VIDEO_ENABLED.key] = true.toString()
                details[net.jami.model.ConfigKey.ACCOUNT_DTMF_TYPE.key] = "sipinfo"
                details[net.jami.model.ConfigKey.ACCOUNT_UPNP_ENABLE.key] = net.jami.model.AccountConfig.TRUE_STR
                details[net.jami.model.ConfigKey.TURN_ENABLE.key] = net.jami.model.AccountConfig.TRUE_STR
                details[net.jami.model.ConfigKey.ACCOUNT_PEER_DISCOVERY.key] = net.jami.model.AccountConfig.FALSE_STR
                details[net.jami.model.ConfigKey.PROXY_ENABLED.key] = net.jami.model.AccountConfig.FALSE_STR
                details[net.jami.model.ConfigKey.ARCHIVE_PATH.key] = f.absolutePath
                mAccountService.addAccount(details)
                    .timeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .blockingFirst()
                imported++
            } catch (e: Exception) {
                errors.append("\n  $label: ${e.message}")
            }
        }
        val line = StringBuilder("Accounts: $imported imported")
        if (skipped > 0) line.append(", $skipped already present")
        if (errors.isNotEmpty()) line.append(errors)
        return line.toString()
    }

    private fun onEximImport() {
        val ctx = context ?: return
        val cats = selectedCats()
        if (cats.isEmpty()) { Flash.show(ctx, "No categories selected."); return }
        pendingImportCats = cats
        pickImportFile.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun runEximImport(uri: Uri) {
        val ctx = context ?: return
        val cats = pendingImportCats ?: return
        pendingImportCats = null
        val app = ctx.applicationContext
        Flash.show(ctx, "Importing…")
        Thread {
            var summary: String? = null
            var error: String? = null
            try {
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("no stream")
                val present = SettingsExport.categoriesIn(bytes)
                if (present.isEmpty()) {
                    error = "No 白い熊 GNU Jami export found in that file."
                } else {
                    val parts = ArrayList<String>()
                    SettingsExport.importData(app, bytes, cats)?.let { parts.add(it) }
                    if (SettingsExport.Cat.ACCOUNTS in cats && SettingsExport.Cat.ACCOUNTS in present)
                        parts.add(importAccounts(app, bytes))
                    if (parts.isEmpty()) error = "No 白い熊 GNU Jami export found in that file."
                    else summary = parts.joinToString("\n")
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            val s = summary
            postToUi {
                if (s != null) showImportDone(s)
                else context?.let { Flash.show(it, "Import failed: $error", Toast.LENGTH_LONG) }
            }
        }.start()
    }

    /** Export-finished info dialog (yellow border); OK closes the whole chain: dialog → panel → page. */
    private fun showExportDone(name: String, notes: String = "") {
        val ctx = context ?: return
        refreshEximStatus()
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle("Export finished")
            .setMessage("Exported: $name" + if (notes.isEmpty()) "" else "\n$notes")
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> closeEximChain() }
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    /** Import-finished info dialog (yellow border). "Restart now" restarts the app;
     *  "Later" closes the whole chain: dialog → panel → page. */
    private fun showImportDone(summary: String) {
        val ctx = context ?: return
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle("Import finished")
            .setMessage("Restored:\n\n$summary\n\nRestart to apply everything.")
            .setCancelable(false)
            .setPositiveButton("Restart now") { _, _ -> restartApp() }
            .setNegativeButton("Later") { _, _ -> closeEximChain() }
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun closeEximChain() {
        eximDialog?.dismiss()
        eximDialog = null
        activity?.onBackPressedDispatcher?.onBackPressed()
    }

    private fun restartApp() {
        val app = context?.applicationContext ?: return
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        launch.component?.let { app.startActivity(android.content.Intent.makeRestartActivityTask(it)) }
        Runtime.getRuntime().exit(0)
    }

    /** kxkb-style section header: a full-width 1px hairline above (skipped on the first section),
     *  then a bold 20sp yellow title with a TEXT-WIDE 2.5dp underline. The underline is a
     *  match_parent View inside a wrap_content wrapper, so it collapses to the text width. */
    private fun groupHeader(title: String, first: Boolean = false): View {
        val ctx = requireContext()
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
            setPadding(dp(54f), dp(10f), dp(16f), dp(4f))
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
        controls.addView(miniLabel("Font"))
        controls.addView(valueRow(
            FontPrefs.getFamily(ctx, category).let {
                if (it.isEmpty()) "Inherit (default)" else FontUtil.labelForFamily(it)
            }) { showFamilyPicker(category) })
        controls.addView(miniLabel("weight"))
        val w = FontPrefs.getWeight(ctx, category)
        controls.addView(valueRow(if (w == 0) "Inherit (default)" else FontUtil.labelForWeight(w)) {
            showWeightPicker(category)
        })
        controls.addView(miniLabel("size"))
        val size = FontPrefs.getSize(ctx, category)
        val sizeValue = TextView(ctx).apply {
            text = if (size > 0f) "${size.toInt()} sp" else "default"
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
                    sizeValue.text = if (ns > 0f) "${ns.toInt()} sp" else "default"
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
            setPadding(0, dp(8f), 0, dp(8f))
            isClickable = true
            setOnClickListener { showColorPicker(role) }
        }
        row.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
            background = swatch(ColorPrefs.getColor(ctx, role.key))
        })
        row.addView(TextView(ctx).apply {
            text = role.label
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12f), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = if (ColorPrefs.isSet(ctx, role.key)) hex(ColorPrefs.getColor(ctx, role.key)) else "Default"
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        return row
    }

    private fun swatch(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(4f).toFloat()
        setColor(color)
        setStroke(dp(1f), 0x66FFFFFF.toInt())
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
        cx.ring.utils.DialogTheme.builder(ctx)
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
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun showWeightPicker(cat: String) {
        val ctx = context ?: return
        val labels = FontUtil.WEIGHTS.map { it.first }.toTypedArray()
        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle("Weight")
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
            hint = "sp (blank = default)"
            val s = FontPrefs.getSize(ctx, cat)
            if (s > 0f) setText(s.toInt().toString())
        }
        cx.ring.utils.DialogTheme.builder(ctx)
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
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    private fun showColorPicker(role: ColorRole) {
        val ctx = context ?: return
        val initial = ColorPrefs.getColor(ctx, role.key)
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

        val names = arrayOf("Alpha (opacity)", "Red", "Green", "Blue")
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
            .setNeutralButton("Default") { _, _ -> ColorPrefs.reset(ctx, role.key); rebuild() }
            .setNegativeButton(android.R.string.cancel, null)
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
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
