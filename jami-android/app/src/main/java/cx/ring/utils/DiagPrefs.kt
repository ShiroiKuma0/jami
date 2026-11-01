package cx.ring.utils

import android.content.Context
import java.io.File

/**
 * Settings → Diagnostics (2026-11-01): every SK diagnostic, native and app-side, behind its own switch,
 * all OFF by default. Off means no log line is formatted or written and no diagnostic timer runs.
 *
 * Native groups reach the daemon as a decimal bitmask in `<filesDir>/sk-diag` (the daemon's data dir,
 * JAMI_DATADIR); `sk_diag_on()` in manager.cpp re-reads it every 3 s, so a switch takes effect
 * without restarting the app. App-side groups are read from the cached booleans here.
 */
object DiagPrefs {
    private const val PREFS = "shiroikuma_diag"
    private const val NATIVE_FILE = "sk-diag"

    /** A switchable group. [bit] > 0 = native (daemon/contrib), else app-side. */
    enum class Group(val key: String, val bit: Int) {
        CONNECTIONS("conn", 1),        // SK-ICEDIAG — dhtnet ICE/TLS/rendezvous, pjproject socket eviction
        DHT_PROXY("proxy", 2),         // SK-PROXYDIAG — opendht proxy subscriptions, pushes, HTTP connects
        DHT_STATS("dhtstats", 4),      // SK-DHTSTATS — opendht per-message counters
        HISTORY("history", 8),         // SK-LOADDIAG — slow history loads, daemon io-pool starvation
        AUDIO("audio", 16),            // SK-AUDIODIAG — per-call audio path
        SWARM_SYNC("swarmsync", 32),   // SK-SWARMSYNC — each pull (fetch/merge/heads) and each commit's parent
        OPEN("open", 0),               // SK-OPENDIAG — conversation opening, callback queue, history requests
        EXEC("exec", 0),               // SK-EXECDIAG — app daemon-executor heartbeat, bridge send markers
        WAKE("wake", 0),               // SK-WAKE — push classification and background sleep/wake decisions
    }

    private val on = BooleanArray(Group.entries.size)
    private var app: Context? = null

    /** Load the switches and rewrite the native mask. Call once, early in Application.onCreate. */
    fun init(context: Context) {
        val c = context.applicationContext
        app = c
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (g in Group.entries) on[g.ordinal] = p.getBoolean(g.key, false)
        writeNative(c)
    }

    fun isOn(g: Group): Boolean = on[g.ordinal]

    fun set(context: Context, g: Group, value: Boolean) {
        on[g.ordinal] = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(g.key, value).apply()
        if (g.bit > 0) writeNative(context.applicationContext)
        listeners.forEach { runCatching { it(g, value) } }
    }

    /** Components that start/stop something (a heartbeat thread) when their switch flips. */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Group, Boolean) -> Unit>()
    fun addListener(l: (Group, Boolean) -> Unit) { listeners.add(l) }

    private fun writeNative(c: Context) {
        val mask = Group.entries.filter { it.bit > 0 && on[it.ordinal] }.sumOf { it.bit }
        runCatching { File(c.filesDir, NATIVE_FILE).writeText(mask.toString()) }
    }
}
