/*
 *  shiroikuma.jami fork: shared UI for the data meter.
 *
 *  The meter lived only in Settings → UI fonts & colours → Online recovery, which is the wrong place
 *  to be standing when you are watching connections. Since 2026-07-26 the same dialog is reachable
 *  from the Connection monitor page ("Data" button) AND from the dot-tap Connection dashboard
 *  ("Data" pill) — the surface actually used day to day. All of them drive the same [DataMeter]
 *  state, so a session started on one shows on the others.
 */
package cx.ring.utils

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cx.ring.sk.SkGlyph
import cx.ring.sk.SkGlyphDrawable
import cx.ring.sk.SkIconRole
import cx.ring.sk.SkRole
import cx.ring.sk.applySkPill
import cx.ring.sk.skColor
import cx.ring.sk.skPanel
import net.jami.services.AccountService
import java.io.File

object DataMeterUi {
    private val YELLOW: Int get() = skColor(SkRole.ACCENT)

    /** One window of the unattended log, as its line says it. */
    private class LogRow(val time: String, val span: String, val rx: String?, val tx: String?,
                         val rxBytes: Long, val mode: String, val net: String, val raw: String)

    private val LOG_LINE = Regex(
        """^(\d{4}-\d\d-\d\d \d\d:\d\d(?::\d\d)?)\s+(\S+)\s+rx=(\S+) tx=(\S+)\s+mode=(\S+)\s+net=(\S+)""")
    private val LOG_OTHER = Regex("""^(\d{4}-\d\d-\d\d \d\d:\d\d(?::\d\d)?)\s+(.*)$""")

    /** "19.5MiB" back to bytes — only to size the bars against each other. */
    private fun bytesOf(label: String): Long {
        val m = Regex("""^([\d.,]+)(B|KiB|MiB|GiB)$""").find(label) ?: return 0L
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        return (v * when (m.groupValues[2]) { "KiB" -> 1024.0; "MiB" -> 1048576.0; "GiB" -> 1073741824.0; else -> 1.0 }).toLong()
    }

    private fun parseLog(text: String): List<LogRow> = text.lines().filter { it.isNotBlank() }.map { line ->
        LOG_LINE.find(line)?.destructured?.let { (t, span, rx, tx, mode, net) ->
            LogRow(t, span, rx, tx, bytesOf(rx), mode, net, line)
        } ?: LOG_OTHER.find(line)?.destructured?.let { (t, rest) ->
            LogRow(t, "", null, null, 0L, rest.trim(' ', '—'), "", line)
        } ?: LogRow("", "", null, null, 0L, line.trim(), "", line)
    }.reversed()

    /**
     * The "Data" dialog: the LIVE measurement session (start/stop + a ticking counter), the proxy
     * subscriptions, and the unattended log — each in a framed box of its own (白い熊, 2026-11-27:
     * the page was a column of plain yellow lines, with "Start measuring" looking like text).
     * Everything is drawn in code from colour roles.
     */
    fun showDataDialog(ctx: Context, accounts: AccountService?) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = Math.round(v * d)
        val ink = skColor(SkRole.DIALOG_TEXT)
        val note = skColor(SkRole.PAGE_NOTE)
        val rxInk = skColor(SkRole.DATA_RX)
        val txInk = skColor(SkRole.DATA_TX)
        val hair = skColor(SkRole.LIST_DIVIDER)

        fun label(sp: Float, color: Int = ink, bold: Boolean = false, mono: Boolean = false) = TextView(ctx).apply {
            setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT,
                if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        fun glyph(g: SkGlyph, color: Int, sizeDp: Float) = SkGlyphDrawable(g, color, dp(sizeDp)).apply {
            setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        }
        fun TextView.startIcon(g: SkGlyph, color: Int) {
            val size = maxOf(18f, textSize * 1.25f / d)
            setCompoundDrawablesRelative(glyph(g, color, size), null, compoundDrawablesRelative[2], null)
            compoundDrawablePadding = dp(size / 2f)
        }
        fun hairline() = View(ctx).apply {
            setBackgroundColor(hair)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, dp(0.5f)))
                .apply { topMargin = dp(4f); bottomMargin = dp(4f) }
        }
        /** A framed box with an icon heading; returns the column to fill. */
        fun box(into: LinearLayout, g: SkGlyph, role: SkIconRole, title: String): LinearLayout {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = skPanel(d, skColor(SkRole.DIALOG_GROUND), skColor(SkRole.DATA_BOX_FRAME), 14f)
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) }
            }
            col.addView(label(16f, skColor(SkRole.PAGE_HEADING), bold = true).apply {
                text = title; startIcon(g, skColor(role)); setPadding(0, 0, 0, dp(6f))
            })
            into.addView(col)
            return col
        }
        fun pill(title: String, g: SkGlyph, role: SkIconRole, main: Boolean, opens: Boolean) = label(15f).apply {
            text = title
            applySkPill(main = main)
            if (opens) setCompoundDrawablesRelative(null, null, glyph(SkGlyph.CHEVRON, skColor(SkRole.PILL_TEXT), 16f), null)
            startIcon(g, skColor(role))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f); marginEnd = dp(8f); bottomMargin = dp(2f) }
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(2f))
        }

        // ---- 1. Measurement -------------------------------------------------------------------
        val measure = box(root, SkGlyph.PULSE, SkIconRole.VIEW, ctx.getString(cx.ring.R.string.data_meter_sec_measure))
        val idle = label(14f, note).apply { text = ctx.getString(cx.ring.R.string.data_meter_idle) }
        val figures = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val rxLive = label(22f, rxInk, bold = true, mono = true).apply { startIcon(SkGlyph.DOWN, rxInk) }
        val txLive = label(22f, txInk, bold = true, mono = true).apply {
            startIcon(SkGlyph.UP, txInk); setPadding(dp(18f), 0, 0, 0)
        }
        figures.addView(rxLive); figures.addView(txLive)
        val since = label(13f, note).apply { setPadding(0, dp(2f), 0, 0) }
        measure.addView(idle); measure.addView(figures); measure.addView(since)
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6f), 0, 0) }
        val toggle = pill("", SkGlyph.PLAY, SkIconRole.CALL, main = true, opens = false)
        val sessions = pill(ctx.getString(cx.ring.R.string.data_meter_sessions), SkGlyph.LIST, SkIconRole.VIEW,
            main = false, opens = true)
        buttons.addView(toggle); buttons.addView(sessions)
        // On the folded screen the two pills scroll sideways rather than squeezing their labels.
        measure.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(buttons) })

        // ---- 2. Proxy subscriptions ------------------------------------------------------------
        // The driver of every number below it (白い熊, 2026-07-29): each listener is a permanent
        // proxy subscription re-subscribed every ~3 min, so the idle data rate scales with it.
        val subsBox = box(root, SkGlyph.NETWORK, SkIconRole.CALL, ctx.getString(cx.ring.R.string.data_meter_sec_subs))
        val subsHead = label(14f, note)
        val subsRows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        subsBox.addView(subsHead); subsBox.addView(subsRows)

        fun renderSubs() {
            val list = runCatching { accounts?.getAccounts().orEmpty().filter { it.isJami } }.getOrDefault(emptyList())
            val entries = list.mapNotNull { a -> accounts?.proxyRxStats(a.accountId)?.let { a to it } }
            val total = entries.sumOf { it.second.listeners }
            subsHead.text = when {
                entries.isEmpty() -> ctx.getString(cx.ring.R.string.data_meter_subs_waiting)
                total == 0 -> ctx.getString(cx.ring.R.string.data_meter_subs_settling, entries.size)
                else -> ctx.getString(cx.ring.R.string.data_meter_subs_head, total, entries.size)
            }
            // Rebuilt only when the set of accounts changes; otherwise the texts are updated in
            // place, so a selection or a scroll position survives the one-second tick.
            if (subsRows.childCount != entries.size * 2) {
                subsRows.removeAllViews()
                entries.forEach { _ ->
                    subsRows.addView(hairline())
                    subsRows.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        top.addView(label(15f, bold = true).apply {
                            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                            startIcon(SkGlyph.PERSON, skColor(SkIconRole.CONTACT))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        top.addView(label(14f, mono = true).apply { setPadding(dp(8f), 0, 0, 0) })
                        addView(top)
                        addView(label(12f, note).apply { setTextIsSelectable(true) })
                    })
                }
            }
            entries.forEachIndexed { n, (a, e) ->
                val row = subsRows.getChildAt(n * 2 + 1) as LinearLayout
                val top = row.getChildAt(0) as LinearLayout
                (top.getChildAt(0) as TextView).text =
                    runCatching { a.displayname }.getOrNull()?.takeIf { it.isNotBlank() } ?: e.accountId.take(8)
                (top.getChildAt(1) as TextView).text = ctx.getString(cx.ring.R.string.data_meter_subs_count, e.listeners)
                val last = e.lastRxAgeMs?.let { ctx.getString(cx.ring.R.string.data_meter_subs_secs, it / 1000) }
                    ?: ctx.getString(cx.ring.R.string.data_meter_subs_never)
                (row.getChildAt(1) as TextView).text =
                    ctx.getString(cx.ring.R.string.data_meter_subs_detail, e.proxy, e.pushRx, last)
            }
        }

        fun renderLive() {
            val active = DataMeter.isActive(ctx)
            idle.visibility = if (active) View.GONE else View.VISIBLE
            figures.visibility = if (active) View.VISIBLE else View.GONE
            since.visibility = figures.visibility
            if (active) {
                val (el, rx, tx) = DataMeter.snapshot(ctx)
                rxLive.text = DataMeter.bytesLabel(rx); txLive.text = DataMeter.bytesLabel(tx)
                since.text = ctx.getString(cx.ring.R.string.data_meter_live_since,
                    DataMeter.elapsedLabel(el), DataMeter.netDisplayLabel(ctx))
            }
            toggle.text = ctx.getString(
                if (active) cx.ring.R.string.data_meter_stop else cx.ring.R.string.data_meter_start)
            toggle.startIcon(if (active) SkGlyph.STOP else SkGlyph.PLAY,
                skColor(if (active) SkIconRole.BLOCK else SkIconRole.CALL))
            renderSubs()
        }
        renderLive()
        val ticker = object : Runnable {
            override fun run() {
                if (!root.isAttachedToWindow) return
                renderLive()
                root.postDelayed(this, 1000L)
            }
        }
        toggle.setOnClickListener {
            if (DataMeter.isActive(ctx)) Flash.show(ctx, DataMeter.stop(ctx, modeInfo(ctx, accounts)))
            else DataMeter.start(ctx)
            renderLive()
        }
        sessions.setOnClickListener {
            showHistory(ctx, DataMeter.historyFile(ctx), ctx.getString(cx.ring.R.string.data_meter_sessions_title))
        }

        // ---- 3. The unattended log -------------------------------------------------------------
        val logBox = box(root, SkGlyph.CLOCK, SkIconRole.VIEW, ctx.getString(cx.ring.R.string.data_meter_sec_log))
        val on = DataMeter.isSamplingOn(ctx)
        logBox.addView(label(14f, if (on) note else skColor(SkRole.DANGER)).apply {
            text = if (on) ctx.getString(cx.ring.R.string.data_meter_recording,
                DataMeter.windowLabel(DataMeter.getWindowMinutes(ctx)))
            else ctx.getString(cx.ring.R.string.data_meter_not_recording)
            startIcon(SkGlyph.DOT, if (on) skColor(SkRole.ACCENT) else skColor(SkRole.DANGER))
        })
        val rows = parseLog(runCatching { DataMeter.hourlyFile(ctx).readText() }.getOrDefault("")).take(LOG_ROWS_SHOWN)
        if (rows.isEmpty())
            logBox.addView(label(13f, note).apply { text = ctx.getString(cx.ring.R.string.data_meter_empty) })
        val peak = rows.maxOfOrNull { it.rxBytes }?.coerceAtLeast(1L) ?: 1L
        rows.forEach { r ->
            logBox.addView(hairline())
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(label(13f, mono = true).apply {
                text = r.time.drop(5).take(11)            // MM-dd HH:mm
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (r.rx != null && r.tx != null) {
                line.addView(label(13f, rxInk, bold = true, mono = true).apply { text = "↓${r.rx}" })
                line.addView(label(13f, txInk, mono = true).apply { text = "↑${r.tx}"; setPadding(dp(10f), 0, 0, 0) })
            } else {
                line.addView(label(13f, note).apply { text = r.mode.ifEmpty { r.raw } })
            }
            logBox.addView(line)
            if (r.rx != null) {
                // The window's received volume against the largest one shown: a quiet night and a
                // burst are told apart before a single number is read.
                logBox.addView(FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4f))
                        .apply { topMargin = dp(2f); bottomMargin = dp(2f) }
                    val share = (Math.sqrt(r.rxBytes.toDouble() / peak)).toFloat().coerceIn(0.01f, 1f)
                    addView(View(ctx).apply {
                        background = GradientDrawable().apply { cornerRadius = dp(2f).toFloat(); setColor(rxInk) }
                        pivotX = 0f; scaleX = share
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                })
                logBox.addView(label(11f, note).apply { text = "${r.span} · ${r.mode} · ${r.net}" })
            }
        }
        // Always tick, not only during a measurement session: the subscription block is live.
        // The runnable stops itself when the view detaches.
        root.post(ticker)

        // No "Clear" here (白い熊, 2026-07-29). It deleted data-hourly-log.txt — the unattended
        // record every data measurement is read from, and the only history of what the app did
        // while nobody was watching — with no confirmation and no undo, from a dialog negative
        // button that sits under the thumb. The log is append-only and self-limiting; there is no
        // reason to offer destroying it next to the numbers it produces.
        //
        // Close is the NEGATIVE button, so that it is a regular pill: the main pill of this panel
        // is Start measuring, and a panel has one.
        DialogTheme.builder(ctx)
            .setTitle(ctx.getString(cx.ring.R.string.data_meter_title))
            .setView(ScrollView(ctx).apply { addView(root) })
            .setNegativeButton(ctx.getString(cx.ring.R.string.data_meter_close), null)
            .show().let { DialogTheme.theme(it, ctx) }
    }

    /** Windows of the unattended log drawn in the panel; the whole file stays on disk. */
    private const val LOG_ROWS_SHOWN = 72

    /**
     * Newest-first log dialog — the manual session history and the unattended hourly log both
     * render through this, so no two pages can show the same log differently.
     *
     * [clearable] is false for the hourly log (白い熊, 2026-07-29). That file is the unattended
     * record every data measurement is read from; offering to delete it from a dialog negative
     * button, with no confirmation and no undo, is a hazard rather than a feature. Removing it from
     * the "Data" dialog alone would have left this second door to the same file wide open. The
     * manual session history keeps its Clear — those are measurements you started yourself, and
     * discarding a botched one is legitimate.
     */
    fun showHistory(ctx: Context, f: File, title: String, clearable: Boolean = true) {
        val d = ctx.resources.displayMetrics.density
        val body = runCatching { f.readText().trim() }.getOrDefault("")
            .ifEmpty { ctx.getString(cx.ring.R.string.data_meter_empty) }
            .lines().reversed().joinToString("\n\n")   // newest first, blank line between records
        val tv = TextView(ctx).apply {
            text = body
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
            setTextIsSelectable(true)
        }
        val b = DialogTheme.builder(ctx)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(tv) })
            .setPositiveButton(ctx.getString(cx.ring.R.string.data_meter_close), null)
        if (clearable)
            b.setNegativeButton(ctx.getString(cx.ring.R.string.data_meter_clear)) { _, _ ->
                runCatching { f.delete() }
            }
        b.show().let { DialogTheme.theme(it, ctx) }
    }

    /** `proxy(actual:fullDHT,adaptive)` — the mode label stamped into a saved measurement, so a
     *  recorded window can never be misread as belonging to the mode the UI merely prefers. */
    fun modeInfo(ctx: Context, accounts: AccountService?): String {
        val pref = if (UiPrefs.isFullDhtMode(ctx)) "fullDHT" else "proxy"
        val actualProxy = runCatching {
            accounts?.getAccounts()?.any { it.isJami && it.isDhtProxyEnabled } == true
        }.getOrDefault(false)
        val adaptive = ConnectionWatchdog.isNoPushAdaptive()
        return "$pref(actual:${if (actualProxy) "proxy" else "fullDHT"}${if (adaptive) ",adaptive" else ""})"
    }

    /** One line of live session state, or null when no session is running. */
    fun liveLine(ctx: Context): String? {
        if (!DataMeter.isActive(ctx)) return null
        val (el, rx, tx) = DataMeter.snapshot(ctx)
        return "${DataMeter.elapsedLabel(el)} · ↓${DataMeter.bytesLabel(rx)} ↑${DataMeter.bytesLabel(tx)} · ${DataMeter.netDisplayLabel(ctx)}"
    }
}
