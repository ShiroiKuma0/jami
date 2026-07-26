/*
 *  shiroikuma.jami fork — on-demand app data-usage meter (2026-07-25).
 *
 *  Born from the 36-GiB/15-h runaway-download investigation: per-app byte counters are the only
 *  honest way to see what a connectivity mode really costs. TrafficStats' per-UID counters are
 *  kernel-accounted (all interfaces, Wi-Fi + mobile) and cost nothing to read. A measurement
 *  session survives process restarts AND device reboots (counters reset at boot — detected by
 *  the counter running backwards, the pre-reboot delta banked into an accumulator).
 *
 *  History records append to files/data-measure-log.txt, one line per session, newest last;
 *  the fonts-settings "Online recovery" section renders them in a themed dialog.
 */
package cx.ring.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DataMeter {
    private fun p(c: Context) = c.getSharedPreferences("shiroikuma_datameter", Context.MODE_PRIVATE)
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun isActive(c: Context): Boolean = p(c).getBoolean("active", false)

    private fun rxNow(): Long = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0)
    private fun txNow(): Long = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0)

    fun netLabel(c: Context): String {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "?"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
    }

    fun start(c: Context) {
        p(c).edit()
            .putBoolean("active", true)
            .putLong("start_ms", System.currentTimeMillis())
            .putLong("base_rx", rxNow()).putLong("base_tx", txNow())
            .putLong("last_rx", rxNow()).putLong("last_tx", txNow())
            .putLong("acc_rx", 0L).putLong("acc_tx", 0L)
            .putString("start_net", netLabel(c))
            .apply()
    }

    /** Current session totals: Triple(elapsedMs, rxBytes, txBytes). Persists the reboot-detection
     *  samples as a side effect — call it from the live-display poller and before [stop]. */
    fun snapshot(c: Context): Triple<Long, Long, Long> {
        val pr = p(c)
        if (!pr.getBoolean("active", false)) return Triple(0, 0, 0)
        var accRx = pr.getLong("acc_rx", 0); var accTx = pr.getLong("acc_tx", 0)
        var baseRx = pr.getLong("base_rx", 0); var baseTx = pr.getLong("base_tx", 0)
        val lastRx = pr.getLong("last_rx", 0); val lastTx = pr.getLong("last_tx", 0)
        val curRx = rxNow(); val curTx = txNow()
        if (curRx < lastRx || curTx < lastTx) {          // counters went backwards → reboot
            accRx += (lastRx - baseRx).coerceAtLeast(0)
            accTx += (lastTx - baseTx).coerceAtLeast(0)
            baseRx = 0; baseTx = 0
            pr.edit().putLong("acc_rx", accRx).putLong("acc_tx", accTx)
                .putLong("base_rx", 0).putLong("base_tx", 0).apply()
        }
        pr.edit().putLong("last_rx", curRx).putLong("last_tx", curTx).apply()
        val elapsed = System.currentTimeMillis() - pr.getLong("start_ms", System.currentTimeMillis())
        return Triple(elapsed, accRx + (curRx - baseRx), accTx + (curTx - baseTx))
    }

    /** Ends the session and appends a history record. [modeInfo] is caller-supplied context
     *  (DHT mode pref + actual proxy state + adaptive), since only the UI layer can see it. */
    fun stop(c: Context, modeInfo: String): String {
        val (elapsed, rx, tx) = snapshot(c)
        val pr = p(c)
        val startMs = pr.getLong("start_ms", System.currentTimeMillis())
        val startNet = pr.getString("start_net", "?") ?: "?"
        pr.edit().putBoolean("active", false).apply()
        val rec = "${fmt.format(Date(startMs))} → ${fmt.format(Date())}  ${elapsedLabel(elapsed)}  " +
                "rx=${bytesLabel(rx)} tx=${bytesLabel(tx)}  mode=$modeInfo  net=$startNet→${netLabel(c)}"
        runCatching { historyFile(c).appendText(rec + "\n") }
        return rec
    }

    fun historyFile(c: Context): File = File(c.getExternalFilesDir(null), "data-measure-log.txt")

    fun hourlyFile(c: Context): File = File(c.getExternalFilesDir(null), "data-hourly-log.txt")

    // ---- Unattended sampler settings (2026-07-26) ----
    const val WINDOW_MIN_MINUTES = 1        // the watchdog tick is the sampling clock — can't go finer
    const val WINDOW_MAX_MINUTES = 360      // 6 h
    const val WINDOW_DEFAULT_MINUTES = 60

    fun isSamplingOn(c: Context): Boolean = p(c).getBoolean("hour_on", true)   // default ON

    fun getWindowMinutes(c: Context): Int =
        p(c).getInt("hour_window_min", WINDOW_DEFAULT_MINUTES)
            .coerceIn(WINDOW_MIN_MINUTES, WINDOW_MAX_MINUTES)

    fun setWindowMinutes(c: Context, v: Int) {
        val w = v.coerceIn(WINDOW_MIN_MINUTES, WINDOW_MAX_MINUTES)
        p(c).edit().putInt("hour_window_min", w).apply()
        mark(c, "window → ${windowLabel(w)}")
        resetWindow(c)   // don't measure a partial window against the new length
    }

    /** Turning sampling off/on writes an explicit marker. Without it, a gap in the history is
     *  indistinguishable from a crash, a reboot or a dead app — the exact ambiguity that wasted time
     *  during the 2026-07-25 wedge investigation. */
    fun setSamplingOn(c: Context, on: Boolean) {
        if (isSamplingOn(c) == on) return
        p(c).edit().putBoolean("hour_on", on).apply()
        mark(c, if (on) "sampling started (window ${windowLabel(getWindowMinutes(c))})" else "sampling stopped")
        if (on) resetWindow(c)
    }

    fun windowLabel(minutes: Int): String = when {
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h${minutes % 60}m"
    }

    private fun mark(c: Context, what: String) {
        runCatching { hourlyFile(c).appendText("${fmt.format(Date())}  — $what —\n") }
    }

    /** Start a fresh window from now, discarding the partial one in progress. */
    private fun resetWindow(c: Context) {
        p(c).edit().putLong("hour_mark_ms", System.currentTimeMillis())
            .putLong("hour_rx", rxNow()).putLong("hour_tx", txNow()).apply()
    }

    /** Unattended sampler (2026-07-26). The manual [start]/[stop] sessions above need a human at both
     *  ends; the CRL-landfill work needs unwatched per-window numbers to tell a real drop from
     *  measurement noise, and polling it over adb would mean a standing wireless-adb session (which
     *  by itself costs ~1.3 Ah/day). So: one line per elapsed window, appended from the watchdog's
     *  existing ~1-minute tick — no alarm, no worker, no extra wakeups. That is also why the window
     *  cannot be finer than a minute and lands on tick boundaries: the tick IS the clock.
     *
     *  Keys are namespaced `hour_*` so a running manual session is left completely alone.
     *  [modeInfo] is caller-supplied for the same reason as in [stop]: only the UI layer can see the
     *  DHT mode. */
    fun hourlyTick(c: Context, modeInfo: String) {
        if (!isSamplingOn(c)) return
        val pr = p(c)
        val now = System.currentTimeMillis()
        val curRx = rxNow(); val curTx = txNow()
        val markMs = pr.getLong("hour_mark_ms", 0L)
        if (markMs == 0L) {                                  // first ever tick — just set the mark
            pr.edit().putLong("hour_mark_ms", now)
                .putLong("hour_rx", curRx).putLong("hour_tx", curTx).apply()
            return
        }
        if (now - markMs < getWindowMinutes(c) * 60_000L) return
        val baseRx = pr.getLong("hour_rx", curRx); val baseTx = pr.getLong("hour_tx", curTx)
        // Counters are since-boot: a reboot inside the window makes them run backwards. Bank that
        // window as unknown rather than logging a negative (the manual session banks a partial
        // delta instead — it can, because it keeps an accumulator; here each line stands alone).
        val rebooted = curRx < baseRx || curTx < baseTx
        val rec = if (rebooted)
            "${fmt.format(Date(now))}  ${elapsedLabel(now - markMs)}  (reboot in window — not counted)"
        else
            "${fmt.format(Date(now))}  ${elapsedLabel(now - markMs)}  " +
                    "rx=${bytesLabel(curRx - baseRx)} tx=${bytesLabel(curTx - baseTx)}  " +
                    "mode=$modeInfo  net=${netLabel(c)}"
        runCatching { hourlyFile(c).appendText(rec + "\n") }
        pr.edit().putLong("hour_mark_ms", now)
            .putLong("hour_rx", curRx).putLong("hour_tx", curTx).apply()
    }

    fun elapsedLabel(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m${s % 60}s"
            else -> "${s / 3600}h${(s % 3600) / 60}m"
        }
    }

    fun bytesLabel(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "%.1fKiB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1fMiB".format(b / 1048576.0)
        else -> "%.2fGiB".format(b / 1073741824.0)
    }
}
