/*
 *  shiroikuma.jami fork — daemon error-storm monitor.
 *
 *  Tails this process's own logcat (ERROR level only — a few lines per hour in
 *  healthy operation, no permissions needed for our own pid) and watches for
 *  the link-death storm signature observed in the 2026-07-20 outage: a dense
 *  burst of TLS fatals / ICE negotiation failures across many transports
 *  within a minute. Isolated errors never trigger — pairs of
 *  "Broken pipe" / "non-properly terminated" lines are benign swarm-sync
 *  teardowns that occur in normal operation.
 *
 *  On a storm the callback fires with a snapshot of the recent error lines;
 *  ConnectionWatchdog decides how to recover and files the incident.
 */
package cx.ring.utils

import android.util.Log
import kotlin.concurrent.thread

object LogStormMonitor {
    private const val TAG = "LogStormMonitor"
    private const val WINDOW_MS = 60_000L      // sliding window for the storm count
    private const val THRESHOLD = 8            // fatal lines within the window = storm
    private const val RING_MAX = 250           // error lines kept for incident forensics
    private const val RESTART_BACKOFF_MS = 5_000L

    @Volatile private var started = false
    private val stamps = ArrayDeque<Long>()
    private val ring = ArrayDeque<String>()

    /** Set by DRingService; invoked on the reader thread with recent error lines. */
    @Volatile var onStorm: ((List<String>) -> Unit)? = null

    fun start() {
        if (started) return
        started = true
        thread(name = "log-storm-monitor", isDaemon = true) { readLoop() }
    }

    @Synchronized
    fun recentLines(): List<String> = ring.toList()

    private fun readLoop() {
        val pid = android.os.Process.myPid().toString()
        while (true) {
            try {
                val proc = ProcessBuilder("logcat", "--pid=$pid", "-v", "time", "*:E")
                    .redirectErrorStream(true)
                    .start()
                proc.inputStream.bufferedReader().forEachLine { line -> handle(line) }
                proc.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "logcat reader died: $e")
            }
            try {
                Thread.sleep(RESTART_BACKOFF_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** The storm signature: link-fatal daemon lines only. UPnP mapping failures are
     *  excluded — they burst benignly and have their own circuit breaker daemon-side. */
    private fun isLinkFatal(l: String): Boolean =
        (l.contains("tls_session.cpp")
            && (l.contains("Fatal error") || l.contains("Transport failure")))
            || (l.contains("ice_transport.cpp")
            && (l.contains("negotiation failed") || l.contains("ICE send failed")))

    @Synchronized
    private fun handle(line: String) {
        ring.addLast(line)
        if (ring.size > RING_MAX) ring.removeFirst()
        // The daemon's SK-PROXYDIAG tick lines ride this same ERROR-level stream. Harvest the
        // proxy-subscription counts on the way past — they set the idle data floor, and the ring
        // they would otherwise be read from is flushed by any storm (see ProxySubs).
        ProxySubs.note(line)
        if (!isLinkFatal(line)) return
        val t = System.currentTimeMillis()
        stamps.addLast(t)
        while (stamps.isNotEmpty() && t - stamps.first() > WINDOW_MS) stamps.removeFirst()
        if (stamps.size >= THRESHOLD) {
            stamps.clear()
            onStorm?.invoke(ring.toList())
        }
    }
}
