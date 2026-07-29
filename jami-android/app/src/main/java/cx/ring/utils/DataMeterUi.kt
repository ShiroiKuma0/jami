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
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.jami.services.AccountService
import java.io.File

object DataMeterUi {
    private const val YELLOW = 0xFFFFFF00.toInt()

    /**
     * The "Data" dialog: the LIVE measurement session (start/stop + a ticking counter), the saved
     * sessions, and the unattended hourly log — in one place.
     */
    fun showDataDialog(ctx: Context, accounts: AccountService?) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val f = DataMeter.hourlyFile(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(4f))
        }
        val live = TextView(ctx).apply {
            setTextColor(YELLOW); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, 0, dp(6f))
        }
        val toggle = TextView(ctx).apply {
            setTextColor(YELLOW); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(8f), 0, dp(10f))
        }
        // Proxy subscriptions — the driver of every number below it, so it belongs on this page
        // rather than in the connection legend (白い熊, 2026-07-29). Each listener is a permanent
        // proxy subscription re-subscribed every ~3 min, so the idle data rate scales with it.
        // Declared up here so renderLive() can refresh it: the first version set the text once at
        // dialog-build time, which froze both the counts and the "· N s ago" for as long as the
        // dialog stayed open.
        val subs = TextView(ctx).apply {
            setTextColor(YELLOW); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2f), 0, dp(8f))
            setTextIsSelectable(true)
        }
        fun renderLive() {
            val l = liveLine(ctx)
            live.text = l?.let { ctx.getString(cx.ring.R.string.data_meter_measuring, it) }
                ?: ctx.getString(cx.ring.R.string.data_meter_idle)
            toggle.text = ctx.getString(
                if (l == null) cx.ring.R.string.data_meter_start else cx.ring.R.string.data_meter_stop)
            subs.text = proxySubsText(ctx)
        }
        renderLive()
        val ticker = object : Runnable {
            override fun run() {
                if (!live.isAttachedToWindow) return
                renderLive()
                live.postDelayed(this, 1000L)
            }
        }
        toggle.setOnClickListener {
            if (DataMeter.isActive(ctx)) {
                Flash.show(ctx, DataMeter.stop(ctx, modeInfo(ctx, accounts)))
            } else {
                DataMeter.start(ctx)
                live.removeCallbacks(ticker)
                live.post(ticker)
            }
            renderLive()
        }
        val sessions = TextView(ctx).apply {
            text = ctx.getString(cx.ring.R.string.data_meter_sessions)
            setTextColor(YELLOW); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(2f), 0, dp(10f))
            setOnClickListener {
                showHistory(ctx, DataMeter.historyFile(ctx),
                    ctx.getString(cx.ring.R.string.data_meter_sessions_title))
            }
        }
        val on = DataMeter.isSamplingOn(ctx)
        val win = DataMeter.windowLabel(DataMeter.getWindowMinutes(ctx))
        val body = runCatching { f.readText().trim() }.getOrDefault("")
            .ifEmpty { ctx.getString(cx.ring.R.string.data_meter_empty) }
            .lines().reversed().joinToString("\n")
        val head = if (on) ctx.getString(cx.ring.R.string.data_meter_recording, win)
        else ctx.getString(cx.ring.R.string.data_meter_not_recording)
        val log = TextView(ctx).apply {
            text = "$head\n\n$body"
            setTextColor(YELLOW); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8f), 0, dp(8f))
            setTextIsSelectable(true)
        }
        root.addView(live); root.addView(toggle); root.addView(sessions); root.addView(subs); root.addView(log)
        // Always tick, not only during a measurement session: the subscription block ages in real
        // time and its whole point is to be trustworthy about how stale it is. The runnable stops
        // itself when the view detaches.
        live.post(ticker)

        // No "Clear" here (白い熊, 2026-07-29). It deleted data-hourly-log.txt — the unattended
        // record every data measurement is read from, and the only history of what the app did
        // while nobody was watching — with no confirmation and no undo, from a dialog negative
        // button that sits under the thumb. The log is append-only and self-limiting; there is no
        // reason to offer destroying it next to the numbers it produces.
        DialogTheme.builder(ctx)
            .setTitle(ctx.getString(cx.ring.R.string.data_meter_title))
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton(ctx.getString(cx.ring.R.string.data_meter_close), null)
            .show().let { DialogTheme.theme(it, ctx) }
    }

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

    /**
     * The proxy-subscription block for the Data dialog.
     *
     * Deliberately reports its own staleness instead of implying the numbers are live: the daemon
     * emits one tick per proxy every ~3 minutes, so a freshly started process legitimately has
     * nothing to show for a few minutes, and "0 listeners" and "not measured yet" must never look
     * the same.
     */
    private fun proxySubsText(ctx: Context): String {
        val entries = ProxySubs.snapshot()
        // THREE states, not two (2026-07-29). The first version tested only "no tick parsed yet",
        // but the daemon emits a tick the moment the proxy client exists — before a single
        // subscription is established — carrying listeners=0, pushRx=0, lastRx=-1. So a freshly
        // started app showed four proxies all reading zero, indistinguishable from a real
        // measurement of nothing. That is the exact confusion this block was supposed to prevent.
        if (entries.isEmpty())
            return ctx.getString(cx.ring.R.string.data_meter_subs_waiting)
        val ageSec = (System.currentTimeMillis() - ProxySubs.lastTickMs()) / 1000
        val total = ProxySubs.totalListeners()
        if (total == 0)
            return ctx.getString(cx.ring.R.string.data_meter_subs_settling, entries.size, ageSec)
        val head = ctx.getString(cx.ring.R.string.data_meter_subs_head, total, entries.size, ageSec)
        val rows = entries.joinToString("\n") { e ->
            // -1 is the daemon's "nothing ever received" sentinel, not an age.
            val last = if (e.lastRxSec < 0) ctx.getString(cx.ring.R.string.data_meter_subs_never)
                else ctx.getString(cx.ring.R.string.data_meter_subs_secs, e.lastRxSec)
            ctx.getString(cx.ring.R.string.data_meter_subs_row, e.proxy, e.listeners, e.pushRx, last)
        }
        return "$head\n$rows"
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
        return "${DataMeter.elapsedLabel(el)} · ↓${DataMeter.bytesLabel(rx)} ↑${DataMeter.bytesLabel(tx)} · ${DataMeter.netLabel(ctx)}"
    }
}
