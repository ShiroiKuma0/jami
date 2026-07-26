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
        fun renderLive() {
            val l = liveLine(ctx)
            live.text = l?.let { ctx.getString(cx.ring.R.string.data_meter_measuring, it) }
                ?: ctx.getString(cx.ring.R.string.data_meter_idle)
            toggle.text = ctx.getString(
                if (l == null) cx.ring.R.string.data_meter_start else cx.ring.R.string.data_meter_stop)
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
        root.addView(live); root.addView(toggle); root.addView(sessions); root.addView(log)
        if (DataMeter.isActive(ctx)) live.post(ticker)

        DialogTheme.builder(ctx)
            .setTitle(ctx.getString(cx.ring.R.string.data_meter_title))
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton(ctx.getString(cx.ring.R.string.data_meter_close), null)
            .setNegativeButton(ctx.getString(cx.ring.R.string.data_meter_clear)) { _, _ ->
                runCatching { f.delete() }
            }
            .show().let { DialogTheme.theme(it, ctx) }
    }

    /** Newest-first log dialog with Close / Clear — the manual session history and the unattended
     *  hourly log both render through this, so no two pages can show the same log differently. */
    fun showHistory(ctx: Context, f: File, title: String) {
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
        DialogTheme.builder(ctx)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(tv) })
            .setPositiveButton(ctx.getString(cx.ring.R.string.data_meter_close), null)
            .setNegativeButton(ctx.getString(cx.ring.R.string.data_meter_clear)) { _, _ ->
                runCatching { f.delete() }
            }
            .show().let { DialogTheme.theme(it, ctx) }
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
