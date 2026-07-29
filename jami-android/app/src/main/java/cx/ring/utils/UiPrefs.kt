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

    // ---- Media viewer: suppressed (hidden-from-swipe) pictures & videos ---------------------
    // A picture/video the user swiped down on is suppressed: never shown again while swiping
    // through the media viewer. Keyed by the transfer's swarm messageId (or fileId). Persists.
    /** A mutable copy of the suppressed-media key set. */
    fun getSuppressedMedia(c: Context): MutableSet<String> =
        HashSet(p(c).getStringSet("suppressed_media", emptySet()) ?: emptySet())

    fun isMediaSuppressed(c: Context, key: String): Boolean =
        p(c).getStringSet("suppressed_media", emptySet())?.contains(key) == true

    fun setMediaSuppressed(c: Context, key: String, suppressed: Boolean) {
        val s = getSuppressedMedia(c)
        if (suppressed) s.add(key) else s.remove(key)
        p(c).edit().putStringSet("suppressed_media", s).apply()
    }

    // ---- Push backend selection (dual-backend flavor: FCM ⟷ UnifiedPush) --------------------
    // Which push transport the daemon registers against. Both receivers stay alive; only the
    // registered token switches. Default "fcm" (fresh-install Firebase default, 2026-07-24).
    const val PUSH_FCM = "fcm"
    const val PUSH_UNIFIED = "unifiedpush"
    fun getPushBackend(c: Context): String = p(c).getString("push_backend", PUSH_FCM) ?: PUSH_FCM
    fun setPushBackend(c: Context, backend: String) {
        p(c).edit().putString("push_backend", backend).apply()
    }

    // ---- Adaptive no-push persistence (survive process restart mid-outage) -------------------
    // noPushAdaptive + the hold deadline + the permanent-service backup, so a restart during an
    // outage re-enters streaming immediately instead of eating a 10-min re-detection.
    fun isAdaptivePersisted(c: Context): Boolean = p(c).getBoolean("adaptive_nopush", false)
    fun getAdaptiveHoldUntil(c: Context): Long = p(c).getLong("adaptive_hold_until", 0L)
    fun getAdaptivePermBackup(c: Context): Boolean = p(c).getBoolean("adaptive_perm_backup", false)
    fun setAdaptivePersisted(c: Context, on: Boolean, holdUntil: Long, permBackup: Boolean) {
        p(c).edit()
            .putBoolean("adaptive_nopush", on)
            .putLong("adaptive_hold_until", holdUntil)
            .putBoolean("adaptive_perm_backup", permBackup)
            .apply()
    }

    // ---- Crash-safe re-register ledger ------------------------------------------------------
    // Accounts currently inside a watchdog unregister→register nudge. sendRegister persists
    // ACCOUNT_ENABLE, so a process death inside the 1.5-s window leaves the account disabled on
    // disk; a marker surviving into the next process = that account needs healing (re-enable).
    // Synchronous commit() on purpose — the marker must hit disk BEFORE the disable does.
    fun getReregisterInFlight(c: Context): MutableSet<String> =
        HashSet(p(c).getStringSet("reregister_inflight", emptySet()) ?: emptySet())

    fun setReregisterInFlight(c: Context, accountId: String, inFlight: Boolean) {
        val s = getReregisterInFlight(c)
        if (inFlight) s.add(accountId) else s.remove(accountId)
        @Suppress("ApplySharedPref")
        p(c).edit().putStringSet("reregister_inflight", s).commit()
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

    /** Restricted-network (hostile WiFi) mode: UDP egress verified blocked while TCP works —
     *  the watchdog pins the DHT proxy ON and traffic rides TURN relays until UDP returns. */
    fun isRestrictedNet(c: Context): Boolean = p(c).getBoolean("restricted_net", false)
    fun setRestrictedNet(c: Context, on: Boolean) {
        p(c).edit().putBoolean("restricted_net", on).apply()
    }

    /** DHT mode. The DHT proxy + push is the RESTING state (default since 2026-07-29); full DHT is the
     *  ESCALATION path, entered by the watchdog on a proxy-implicated wedge and left again once the
     *  linger expires. Full DHT is wedge-proof but runs a real opendht UDP node per account: measured on
     *  this phone with four accounts, 145 pkt/s of ~180-byte datagrams, 94 MB/h, 19 % of a core, and a
     *  WiFi radio that slept 289 ms in two hours — deep Doze never engaged. Push is bypassed entirely in
     *  that mode ([ConnectionWatchdog] returns early), so the proxy is the only state in which the device
     *  can sleep. Restricted-network pins the proxy ON regardless (full DHT is UDP; blocked UDP → the
     *  TURN/relay path is the only one that works). See [ConnectionWatchdog.applyProxyState]. */
    fun isFullDhtMode(c: Context): Boolean = p(c).getBoolean("full_dht_mode", false)
    fun setFullDhtMode(c: Context, on: Boolean) {
        p(c).edit().putBoolean("full_dht_mode", on).apply()
    }

    /** One-shot move to the proxy resting state (2026-07-29). Flipping the [isFullDhtMode] /
     *  [isFullDhtWhileCharging] defaults is not enough on an install that already wrote those prefs —
     *  this migrates the stored values exactly once, leaving every later deliberate switch alone. Both
     *  switches remain in Settings. Returns true when it actually changed something, so the caller can
     *  log it. */
    fun migrateToProxyRestingState(c: Context): Boolean {
        val pref = p(c)
        if (pref.getBoolean("proxy_default_20260729", false)) return false
        val changed = pref.getBoolean("full_dht_mode", false) || pref.getBoolean("full_dht_while_charging", false)
        pref.edit()
            .putBoolean("proxy_default_20260729", true)
            .putBoolean("full_dht_mode", false)
            .putBoolean("full_dht_while_charging", false)
            .apply()
        return changed
    }

    /** One-shot push-token rotation after the 2026-07-25 update: weeks of churn left generations of
     *  stale server-side proxy subscriptions all pushing to the same FCM token (measured 2–3/s,
     *  600 KB standing microG backlog, Firebase-Messaging thread at ~24% CPU). A single rotation
     *  orphans them all at Google; the flag makes it exactly once. */
    fun isTokenRotatedOnce(c: Context): Boolean = p(c).getBoolean("token_rotated_20260725", false)
    fun setTokenRotatedOnce(c: Context) {
        p(c).edit().putBoolean("token_rotated_20260725", true).apply()
    }

    /** Full DHT while charging (default OFF since 2026-07-29). Battery is free on the charger, but the
     *  full DHT is not: 94 MB/h of tiny datagrams and a WiFi radio pinned awake all night, for a mode
     *  the watchdog would otherwise only enter on a real wedge. Turning it back ON restores the old
     *  "charging ⇒ robust full DHT" behaviour. Only meaningful in proxy mode; see
     *  [ConnectionWatchdog.applyProxyState]. */
    fun isFullDhtWhileCharging(c: Context): Boolean = p(c).getBoolean("full_dht_while_charging", false)
    fun setFullDhtWhileCharging(c: Context, on: Boolean) {
        p(c).edit().putBoolean("full_dht_while_charging", on).apply()
    }

    /** Watchdog tick, in minutes. Default 1 — a proxy wedge must be caught in ~a minute, not 3. */
    fun getRecoveryTickMinutes(c: Context): Int = p(c).getInt("recovery_tick_min", 1)
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
    private const val LOG_SEQ_KEY = "recovery_log_seq"
    private const val LOG_MAX = 200

    /** Append one timestamped line to the rolling watchdog log (newest last). */
    fun appendRecoveryLog(c: Context, line: String) {
        val cur = p(c).getString(LOG_KEY, "")?.takeIf { it.isNotEmpty() }?.split('\n') ?: emptyList()
        val next = (cur + line).takeLast(LOG_MAX)
        p(c).edit()
            .putString(LOG_KEY, next.joinToString("\n"))
            // Monotonic append counter — see getRecoveryLogSince. Never trimmed, unlike the log.
            .putLong(LOG_SEQ_KEY, p(c).getLong(LOG_SEQ_KEY, 0L) + 1L)
            .apply()
        mirrorToFile(c, line)
    }

    /** Total lines ever appended. Take this as a cursor, then read back with
     *  [getRecoveryLogSince] — never the log's `.size`, which is capped (see below). */
    fun getRecoveryLogSeq(c: Context): Long = p(c).getLong(LOG_SEQ_KEY, 0L)

    /**
     * Lines appended since [seq].
     *
     * The obvious version of this — snapshot `getRecoveryLog().size`, later `drop(baseline)` — is
     * WRONG and silently so, because the log is a ring: `appendRecoveryLog` keeps the last [LOG_MAX]
     * lines, so once it is full its size never changes again and `drop(size)` returns an empty list
     * forever. The Inbound test dialog did exactly that and had therefore shown an empty body on
     * every device whose log had filled once — permanently, since prefs survive app updates
     * (白い熊 caught it 2026-07-29). A count is only a valid cursor into a buffer that never
     * discards; this one discards from the front, so the cursor has to be the append counter.
     */
    fun getRecoveryLogSince(c: Context, seq: Long): List<String> {
        val added = (getRecoveryLogSeq(c) - seq).coerceAtLeast(0L)
        if (added == 0L) return emptyList()
        val log = getRecoveryLog(c)
        return if (added >= log.size) log else log.takeLast(added.toInt())
    }

    // ---- External-file mirror --------------------------------------------------------------------
    // The dialog log lives in private SharedPreferences (unreadable over adb on a release build).
    // Mirror every line to the app's EXTERNAL files dir so the full history is inspectable with any
    // file manager and pullable over adb — Android/data/shiroikuma.jami/files/recovery-log.txt.
    private const val LOG_FILE = "recovery-log.txt"
    private const val LOG_FILE_MAX_BYTES = 512 * 1024L   // trim to the last ~half when it grows past this

    private fun mirrorToFile(c: Context, line: String) {
        runCatching {
            val dir = c.getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, LOG_FILE)
            val day = java.text.SimpleDateFormat("MM-dd", java.util.Locale.US).format(java.util.Date())
            f.appendText("$day $line\n")
            if (f.length() > LOG_FILE_MAX_BYTES) {
                val kept = f.readLines().let { it.takeLast(it.size / 2) }
                f.writeText(kept.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** Absolute path of the external mirror file, for surfacing in the dashboard. */
    fun recoveryLogFilePath(c: Context): String =
        java.io.File(c.getExternalFilesDir(null), LOG_FILE).absolutePath

    /** The rolling watchdog log, oldest first. */
    fun getRecoveryLog(c: Context): List<String> =
        p(c).getString(LOG_KEY, "")?.takeIf { it.isNotEmpty() }?.split('\n') ?: emptyList()

    fun clearRecoveryLog(c: Context) {
        p(c).edit().remove(LOG_KEY).apply()
    }

    // ---- App language (BCP-47 tag; "" = follow system) -----------------------------------------
    // Persisted in our own prefs (which survive app updates) because some ROMs (EMUI) drop the
    // system per-app locale on a sideload update; JamiApplication re-asserts this on every start.
    fun getAppLanguage(c: Context): String? = p(c).getString("app_language", null)
    fun setAppLanguage(c: Context, tag: String) { p(c).edit().putString("app_language", tag).apply() }

    // One-time reset of the connection-dot colours: STATUS_ONLINE/OFFLINE changed meaning (account
    // online/offline icon → connection-dot connected/disconnected) with new yellow/red defaults, so a
    // value stored under the old meaning is stale and must be cleared once.
    fun needsDotColorMigration(c: Context): Boolean = !p(c).getBoolean("dot_color_migrated_v1", false)
    fun setDotColorMigrated(c: Context) { p(c).edit().putBoolean("dot_color_migrated_v1", true).apply() }
}
