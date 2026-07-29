/*
 *  shiroikuma.jami fork — live proxy-subscription counts, lifted out of the daemon's own log.
 *
 *  In proxy mode the number of active listeners IS the idle data floor: each one is a permanent
 *  subscription that our SK-SUBREFRESH patch re-subscribes every 3 minutes, so four accounts
 *  holding 68 of them between them cost ~21 MiB/h doing nothing (measured 2026-07-29). That number
 *  decided the whole evening's work and yet was only visible over adb, buried in
 *  watchdog-incidents.log and only written there when an incident happened to fire.
 *
 *  It costs nothing to surface. The opendht subscription-diag patch already prints one tick line
 *  per proxy every 3 minutes at ERROR level, and [LogStormMonitor] already tails this process's own
 *  logcat at ERROR level — so the line is passing through the app anyway. This just parses it on the
 *  way past and keeps the latest per proxy.
 *
 *  Parsed on arrival rather than scanned out of LogStormMonitor's 250-line ring on demand: an error
 *  storm can flush that ring in seconds, and losing the count exactly when things go wrong is the
 *  opposite of useful.
 */
package cx.ring.utils

import java.util.concurrent.ConcurrentHashMap

object ProxySubs {
    /** `[dhtproxy5.jami.net:92] tick: listeners=18 rxLines=73 pushRx=168 lastRx=344s ago …` */
    private val TICK = Regex(
        """\[([^\]]+)] tick: listeners=(\d+).*?pushRx=(\d+) lastRx=(-?\d+)s ago"""
    )

    data class Entry(
        val proxy: String,
        val listeners: Int,
        val pushRx: Long,
        val lastRxSec: Long,
        val atMs: Long,
    )

    private val latest = ConcurrentHashMap<String, Entry>()

    /** Feed one logcat line. Cheap: a `contains` rejects everything that is not a tick. */
    fun note(line: String) {
        if (!line.contains("SK-PROXYDIAG") || !line.contains(" tick: ")) return
        val m = TICK.find(line) ?: return
        val (proxy, listeners, pushRx, lastRx) = m.destructured
        latest[proxy] = Entry(
            proxy = proxy,
            listeners = listeners.toIntOrNull() ?: return,
            pushRx = pushRx.toLongOrNull() ?: 0L,
            lastRxSec = lastRx.toLongOrNull() ?: -1L,
            atMs = System.currentTimeMillis(),
        )
    }

    /** Newest-first by proxy name, so the list does not reshuffle between refreshes. */
    fun snapshot(): List<Entry> = latest.values.sortedBy { it.proxy }

    fun totalListeners(): Int = latest.values.sumOf { it.listeners }

    /** When the most recent tick was seen, or 0 if none yet. The daemon ticks every ~3 min, so a
     *  fresh process shows nothing for a few minutes — the UI must say so rather than imply zero. */
    fun lastTickMs(): Long = latest.values.maxOfOrNull { it.atMs } ?: 0L

    /** Dropped on a daemon restart: the counts belong to the process that printed them. */
    fun reset() = latest.clear()
}
