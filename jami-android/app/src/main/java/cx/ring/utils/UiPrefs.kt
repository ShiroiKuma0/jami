package cx.ring.utils

import android.content.Context

/** Shiroikuma UI preferences (not part of the Jami account/settings model). */
object UiPrefs {
    private const val PREFS = "shiroikuma_ui"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether the list + conversation may show side-by-side on wide screens. Default on. */
    fun isSplitView(c: Context): Boolean = p(c).getBoolean("split_view", true)
    fun setSplitView(c: Context, on: Boolean) {
        p(c).edit().putBoolean("split_view", on).apply()
    }

    /** Account online/offline dot size, as a multiple of the 24dp base. Default 1.5 (150%). */
    fun getStatusDotScale(c: Context): Float = p(c).getFloat("status_dot_scale", 1.5f)
    fun setStatusDotScale(c: Context, v: Float) {
        p(c).edit().putFloat("status_dot_scale", v).apply()
    }

    /** Connection-monitor fold triangle (▶/▼) size, as a RelativeSizeSpan multiple of the row text.
     *  Default 0.94 (half the previous 1.88). Settable in the UI fonts & colours page. */
    fun getMonitorFoldScale(c: Context): Float = p(c).getFloat("monitor_fold_scale", 0.94f)
    fun setMonitorFoldScale(c: Context, v: Float) {
        p(c).edit().putFloat("monitor_fold_scale", v).apply()
    }

    // ---- Online recovery (DHT-proxy wedge watchdog) ----------------------------------------
    // When DHT proxy is on, every connection-setup ICE exchange rides the single proxy link; if it
    // wedges, external delivery strands while the app still shows "connected". This watchdog detects
    // that and recovers by briefly dropping every account to the full DHT (proxy off → settle → on).

    // Two mutually-exclusive detection modes. Default: base ON, ping OFF. Both off = recovery disabled.
    /** Base check: passive "nothing is connecting" heuristic — no test pings. Default ON. */
    fun isRecoveryBaseEnabled(c: Context): Boolean = p(c).getBoolean("recovery_base", true)
    fun setRecoveryBaseEnabled(c: Context, on: Boolean) {
        p(c).edit().putBoolean("recovery_base", on).apply()
        if (on) p(c).edit().putBoolean("recovery_ping", false).apply()   // mutually exclusive with ping
    }
    /** Ping check: active test-swarm canary (needs the account + test conversation below). Default OFF. */
    fun isRecoveryPingEnabled(c: Context): Boolean = p(c).getBoolean("recovery_ping", false)
    fun setRecoveryPingEnabled(c: Context, on: Boolean) {
        p(c).edit().putBoolean("recovery_ping", on).apply()
        if (on) p(c).edit().putBoolean("recovery_base", false).apply()   // mutually exclusive with base
    }
    /** Either mode on → recovery is active at all; both off → fully disabled. */
    fun isOnlineRecoveryEnabled(c: Context): Boolean = isRecoveryBaseEnabled(c) || isRecoveryPingEnabled(c)

    /** Optional test-swarm conversation id (stored WITHOUT the "swarm:" prefix). Empty = use the
     *  passive "nothing is connecting" heuristic instead of the active canary. */
    fun getRecoveryTestSwarm(c: Context): String = p(c).getString("recovery_test_swarm", "") ?: ""
    fun setRecoveryTestSwarm(c: Context, v: String) {
        p(c).edit().putString("recovery_test_swarm", v.removePrefix("swarm:").trim()).apply()
    }

    /** Account id the canary pings are sent from. Empty = canary disabled (heuristic used). */
    fun getRecoveryTestAccount(c: Context): String = p(c).getString("recovery_test_account", "") ?: ""
    fun setRecoveryTestAccount(c: Context, v: String) {
        p(c).edit().putString("recovery_test_account", v).apply()
    }

    /** Both fields set → the active test-swarm canary is used instead of the heuristic. */
    fun isCanaryConfigured(c: Context): Boolean =
        getRecoveryTestSwarm(c).isNotEmpty() && getRecoveryTestAccount(c).isNotEmpty()

    /** Watchdog tick, in minutes. Default 3. */
    fun getRecoveryTickMinutes(c: Context): Int = p(c).getInt("recovery_tick_min", 3)
    fun setRecoveryTickMinutes(c: Context, v: Int) {
        p(c).edit().putInt("recovery_tick_min", v.coerceIn(1, 60)).apply()
    }

    /** Prune canary pings in the test swarm older than this many days. Default 7. */
    fun getRecoveryPruneDays(c: Context): Int = p(c).getInt("recovery_prune_days", 7)
    fun setRecoveryPruneDays(c: Context, v: Int) {
        p(c).edit().putInt("recovery_prune_days", v.coerceIn(0, 90)).apply()
    }

    // ---- Watchdog event log (rolling, capped) ----------------------------------------------
    private const val LOG_KEY = "recovery_log"
    private const val LOG_MAX = 200

    /** Append one timestamped line to the rolling watchdog log (newest last). */
    fun appendRecoveryLog(c: Context, line: String) {
        val cur = p(c).getString(LOG_KEY, "")?.takeIf { it.isNotEmpty() }?.split('\n') ?: emptyList()
        val next = (cur + line).takeLast(LOG_MAX)
        p(c).edit().putString(LOG_KEY, next.joinToString("\n")).apply()
    }

    /** The rolling watchdog log, oldest first. */
    fun getRecoveryLog(c: Context): List<String> =
        p(c).getString(LOG_KEY, "")?.takeIf { it.isNotEmpty() }?.split('\n') ?: emptyList()

    fun clearRecoveryLog(c: Context) {
        p(c).edit().remove(LOG_KEY).apply()
    }
}
