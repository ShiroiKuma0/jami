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
