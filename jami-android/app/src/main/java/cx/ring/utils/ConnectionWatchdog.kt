/*
 *  shiroikuma.jami fork — online-recovery watchdog + DHT-proxy state machine.
 *
 *  With DHT proxy ON, every connection-setup ICE exchange rides the single proxy link; when it wedges,
 *  ICE never completes and delivery strands (in + out) while the UI still says "connected". The full
 *  distributed DHT (proxy OFF) is wedge-proof but costs idle CPU/battery. So:
 *
 *   - Recovery (on a detected wedge, or manual): drop to the full DHT (proxy OFF) and RE-REGISTER so the
 *     stuck backlog flushes — then leave proxy off for a while rather than snapping it back and
 *     re-wedging.
 *   - Proxy state machine (applyProxyState): OFF when forced, when charging (battery is free), or while
 *     riding out a recent wedge (window grows on repeats); ON (push / battery) only on battery + stable.
 *   - Detection: 0-connected-for-a-while OR a stuck outgoing message (ConnectionHealth.NOT_SYNCING);
 *     an optional test-swarm canary replaces the heuristic when configured.
 *
 *  Priority order, by design: inbound > outbound > battery. Reliability gets the full DHT whenever it is
 *  free (charging) or needed (wedge / forced); battery is reclaimed only when unplugged AND stable.
 */
package cx.ring.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import net.jami.model.Contact
import net.jami.model.Uri
import net.jami.model.interaction.Interaction
import net.jami.services.AccountService
import net.jami.utils.InboundEvidence
import net.jami.utils.PeerReachability
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConnectionWatchdog {
    /** Lightning-icon state: yellow ON, blue auto-off, red forced-off. */
    enum class ProxyUi { ON, OFF_AUTO, OFF_FORCED }

    private const val TAG = "ConnWatchdog"
    private const val CANARY_TIMEOUT_MS = 20_000L          // first delivery check after this
    private const val CANARY_RECHECK_MS = 20_000L          // confirm recheck before declaring stale
    private const val SILENT_WEDGE_MS = 20 * 60_000L      // 0-healthy on a NOT-down network this long = last-resort strong recover (idle ≠ wedge)
    private const val IDLE_REARM_MS = 5 * 60_000L         // cheap presence re-arm cadence while all-quiet (no re-register / no proxy toggle)
    // A real push this recently ⇒ the proxy→app leg is delivering ⇒ a wedge does not implicate the
    // proxy, so recovery must not tear its subscriptions down (see proxyImplicated()).
    private const val PROXY_ALIVE_MS = 10 * 60_000L
    private const val WEDGE_WINDOW_BASE_MS = 10 * 60_000L  // proxy lingers off this long after a wedge… (raised 5→10 min 2026-07-25: the false-wedge cycle period was 7 min, so a 5-min linger guaranteed recentWedge() was already false at the next wedge and the escalating backoff never engaged)
    private const val WEDGE_WINDOW_MAX_MS = 30 * 60_000L   // …growing on repeat wedges, capped here
    private const val REREGISTER_DELAY_MS = 2_000L         // re-register after proxy-off takes effect
    private const val RECOVER_SETTLE_MS = 30_000L          // lightning shows "recovering" (blue) this long after a recover
    private const val CANARY_MARK = "⌁"                    // canary ping marker
    private const val OK_MARK = "✓ recovered"

    // ---- Reactive detectors (2026-07-20 outage class) ---------------------------------------
    private const val STORM_COOLDOWN_MS = 5 * 60_000L      // min spacing between storm reactions
    private const val STORM_HARD_WINDOW_MS = 10 * 60_000L  // second storm inside this → hard reset
    private const val STORM_EVIDENCE_VETO_MS = 90_000L     // fresh inbound evidence → benign teardown, ignore
    private const val STORM_SETTLE_BLACKOUT_MS = 2 * 60_000L  // ignore storms this long after ANY recovery — its own re-register teardown/rebuild emits the very fatals the monitor counts (the 2026-07-22 self-inflicted loop)
    private const val STORM_AMBIGUOUS_MS = 5 * 60_000L        // an uncorroborated storm runs the deafness clock at half limit this long (accelerate the evidence path instead of recovering on noise)
    private const val DEAF_LIMIT_MS = 5 * 60_000L          // no inbound evidence this long = deafness SUSPECTED
    private const val DEAF_BACKOFF_MAX_MS = 60 * 60_000L   // escalating recheck backoff cap
    private const val WEDGE_RECOVER_BACKOFF_MS = 2 * 60_000L // after a per-account wedge recover, wait this long before re-recovering the SAME account
    private const val PROBE_VERDICT_MS = 60_000L           // silent presence probe must be answered within this
    private const val UNIFORM_PROBE_VERDICT_MS = 75_000L   // uniform (0-healthy) probe window: the 60 s gate + echo-suppression slack
    private const val UNIFORM_PROBE_FAIL_CAP = 2           // consecutive unanswered-probe recovers before standing down (dead-quiet night ≠ churn)
    private const val PROBE_PUSH_GRACE_MS = 60_000L        // …a real push this close to the probe window refutes a UNIFORM wedge
    const val REASON_CHARGING = "charging"                 // why the proxy is being held off — keys, localised by the UI
    const val REASON_WEDGE = "wedge"
    private const val MODE_SWITCH_PROBE_DELAY_MS = 10_000L // let a just-switched DHT mode register before its verification probe
    private const val BACKFILL_DELAY_MS = 12_000L          // post-recovery backfill fires this long after the recover (let the re-register land)
    private const val BACKFILL_DEBOUNCE_MS = 90_000L       // min gap between backfills — a wedge storm must not churn connections continuously
    private const val NOTIF_CHANNEL = "shiroikuma_watchdog"
    private const val NOTIF_ID_BASE = 58_000

    // Uniform-deafness probe state (2026-07-23: all four proxy subscriptions wedged at once read as
    // "idle" for 20+ min — the differential detector needs a healthy sibling, and the old touch()
    // self-certification kept resetting the last-resort fuse).
    @Volatile private var uniformProbePending = false
    @Volatile private var uniformProbeFails = 0
    // Did the last wedge implicate the proxy leg? Scopes both the teardown and the 10-min linger.
    @Volatile private var wedgeProxyImplicated = false
    // Why the proxy was last forced off — reported when it comes back, and shown in Account
    // settings → Advanced so that switch can explain why it disagrees with the search-bar hexagon.
    @Volatile private var proxyOffReason = REASON_WEDGE

    /** Non-null while the watchdog is holding the proxy off against a "proxy" preference — the state
     *  that makes the hexagon and the per-account Advanced switch disagree. Returns a stable key
     *  ([REASON_CHARGING] / [REASON_WEDGE]) for the UI to localise. */
    fun proxyForcedOffReason(): String? =
        if (proxyOffSinceMs != 0L) proxyOffReason else null
    @Volatile private var uniformWedgeSeq = 0            // lifetime count, shown as ×N in the pinned notification
    @Volatile private var backfillHourStartMs = 0L       // hourly backfill cap window
    @Volatile private var backfillCountHour = 0
    @Volatile private var lastTokenRotateMs = 0L         // FCM-token rotation throttle
    private const val TOKEN_ROTATE_MIN_GAP_MS = 6 * 60 * 60_000L
    @Volatile private var manualProbeInFlight = false

    @Volatile private var lastStormMs = 0L                 // when a storm last triggered a reaction
    @Volatile private var incidentSeq = 0

    // Per-account deafness state. An account can read REGISTERED ("connected") while its own presence
    // listens and channels deliver nothing — a couple of healthy accounts otherwise mask the deaf
    // ones (2026-07-20 "4/4 connected yet contacts red"). Each account gets its own evidence clock,
    // probe, strike count and backoff, and is recovered INDIVIDUALLY (re-register + presence re-arm,
    // escalating to that account's proxy-off) so healthy accounts are never disturbed.
    private class AcctState {
        var strikes = 0
        var nextCheckMs = 0L
        var probeInFlight = false
        var probeStartQuiet = 0L
    }
    private val acctStates = java.util.concurrent.ConcurrentHashMap<String, AcctState>()

    // Restricted-network (hostile WiFi) mode: UDP egress blocked, TCP alive.
    private const val RESTRICTED_RETEST_MS = 2 * 60_000L   // re-test UDP egress this often while restricted
    private const val RESTRICTED_EXIT_PASSES = 2           // consecutive UDP passes required to exit (flap damping)
    private const val DIAG_THROTTLE_MS = 10 * 60_000L      // at most one transport diagnosis per this window
    private const val TRANSPORT_PROBE_THROTTLE_MS = 2 * 60_000L // min spacing for network-change transport probes
    private const val PUSH_PROBE_THROTTLE_MS = 10 * 60_000L // at most one push (ntfy) self-test per this window
    private const val PUSH_PROBE_PERIODIC_MS = 30 * 60_000L // standing cadence in proxy mode (~48 msgs/day worst case)
    private const val PUSH_ENDPOINT_GRACE_MS = 5 * 60_000L  // no push verdict while the distributor may still be registering
    @Volatile private var lastPushProbeMs = 0L
    @Volatile private var pushLegDown: Boolean? = null      // null = never tested this process; drives DOWN→UP transition notices
    @Volatile private var noPushAdaptive = false            // push token cleared, proxy clients riding streaming LISTENs
    private const val REAL_PUSH_STARVATION_MS = 10 * 60_000L   // verified wedge + no real push this long = proxies' leg dead for us
    private const val ADAPTIVE_REENTRY_HOLD_MS = 2 * 60 * 60_000L // UP relapse after an exit → hold streaming this long
    private const val ADAPTIVE_RELAPSE_WINDOW_MS = 15 * 60_000L   // re-entry this soon after an exit counts as a relapse
    private const val ADAPTIVE_FCM_HOLD_BASE_MS = 15 * 60_000L    // FCM: stream at least this long before an optimistic push re-entry
    private const val ADAPTIVE_FCM_HOLD_MAX_MS = 4 * 60 * 60_000L // FCM: doubling hold cap on repeated relapse
    @Volatile private var lastAdaptiveExitMs = 0L
    @Volatile private var adaptiveHoldUntil = 0L
    @Volatile private var adaptiveFcmHoldMs = ADAPTIVE_FCM_HOLD_BASE_MS  // current FCM hold, doubles on relapse
    @Volatile private var ledgerWired = false               // reregisterMarker hooked into AccountService
    @Volatile private var ledgerHealed = false              // interrupted-re-register healing done this process
    @Volatile private var lastDiagMs = 0L
    @Volatile private var restrictedPasses = 0
    @Volatile private var lastTransportProbeMs = 0L
    @Volatile private var advisedHostile = false   // notify on the transition into hostility, not every probe
    @Volatile private var retestScheduled = false

    // Wedge-recovery ledger: never recover twice for the SAME unchanged stuck evidence.
    private const val WEDGE_STAND_DOWN_MS = 60 * 60_000L   // give up on an unchanged fingerprint this long
    private const val BREADTH_QUIET_MS = 2 * 60_000L       // breadth trigger also needs this much inbound silence
    @Volatile private var lastWedgeFp: String? = null      // fingerprint of the evidence behind the last recover
    @Volatile private var wedgeEscalated = false           // the one hard-reset escalation already spent
    @Volatile private var wedgeStandDownUntil = 0L
    @Volatile private var stuckAmbiguous = false           // AVAILABLE-presence stuck exists → deafness clock runs at half limit
    @Volatile private var stormAmbiguousUntil = 0L         // an uncorroborated error storm tightens the deafness limit until this time

    private val handler = Handler(Looper.getMainLooper())
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val processStartMs = System.currentTimeMillis()   // cold-start reference for the tightened windows
    @Volatile private var firstHealthyMs = 0L                 // when this process first saw ≥1 account connected
    private const val STARTUP_FAST_WINDOW_MS = 10 * 60_000L   // early-life period with the halved stale window
    private const val STARTUP_STALL_GRACE_MS = 60_000L        // enabled-but-unregistered this long after start = cold-start stall
    @Volatile private var lastAnyConnectedMs = 0L          // when any account last held a live connection
    @Volatile private var lastIdleRearmMs = 0L             // last cheap presence re-arm while all-quiet (throttle)
    @Volatile private var lastWedgeMs = 0L                 // when a wedge was last seen (linger-off clock)
    @Volatile private var proxyOffSinceMs = 0L             // when the proxy was last switched off (for log durations)
    @Volatile private var wedgeStrikes = 0                 // consecutive wedges → grows the linger window
    @Volatile private var lastRecoverMs = 0L               // when a recover (any kind) last started — drives the lightning blue
    @Volatile private var lastBackfillMs = 0L              // when a post-recovery backfill was last scheduled (debounce)
    @Volatile private var canaryInFlight = false
    @Volatile private var recovering = false

    private fun now() = System.currentTimeMillis()
    private fun stamp() = hms.format(Date())
    private fun log(c: Context, line: String) = UiPrefs.appendRecoveryLog(c, "${stamp()}  $line")

    private fun isCharging(c: Context): Boolean =
        (c.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging == true

    private fun wedgeWindow(): Long =
        (WEDGE_WINDOW_BASE_MS * (1 + wedgeStrikes)).coerceAtMost(WEDGE_WINDOW_MAX_MS)

    private fun recentWedge(t: Long) = lastWedgeMs != 0L && t - lastWedgeMs < wedgeWindow()

    /**
     * Is the PROXY LEG itself the suspect? Only then is a proxy OFF→ON worth its price.
     *
     * That toggle is not free: `DhtRunner::enableProxy` shuts the DHT down and constructs a brand-new
     * `DhtProxyClient`, and a new client can only issue a fresh SUBSCRIBE — which the proxy server
     * answers with the FULL value set of the key (`dht_proxy_server.cpp`: a subscribe on an existing
     * listener with `"refresh": true` returns `{}` and zero bytes; a new listener gets a whole
     * `dht_->get` dump). Measured 2026-07-26 on the oldest account key: **1,348,230 bytes per
     * subscribe**, ×4 accounts, ×6 recoveries in two hours — that is the entire 100 → 300 MiB/h gap.
     *
     * A real push proves the proxy→app leg is delivering, so a wedge is not the proxy's fault and
     * re-registering alone is the honest remedy.
     */
    private fun proxyImplicated(accounts: AccountService, t: Long): Boolean {
        val proxyOn = accounts.getAccounts().any { it.isJami && it.isDhtProxyEnabled }
        if (!proxyOn) return false        // already full DHT — there is nothing to toggle
        if (noPushAdaptive) return true   // streaming LISTEN is the only inbound path, so it IS the suspect
        val push = PushEvidence.lastRealPushMs
        return push == 0L || t - push > PROXY_ALIVE_MS
    }

    /** Lightning shows "recovering" (blue) for a settle window after any recover starts. */
    fun isRecovering(): Boolean = lastRecoverMs != 0L && now() - lastRecoverMs < RECOVER_SETTLE_MS

    /** Fully healthy = EVERY registered account holds a live connection AND is not deaf. Only then is
     *  a "light" recover (re-register, proxy stays on) safe — any partial failure means the proxy is
     *  suspect for someone, so the account(s) at fault need the full treatment. (The old "≥1 connected"
     *  rule bet all four accounts on a proxy half of them couldn't register through — 2026-07-20.) */
    private fun allHealthy(accounts: AccountService): Boolean {
        val regd = accounts.getAccounts().filter { it.isJami && it.isRegistered }
        if (regd.isEmpty()) return false
        return regd.all { accounts.accountConnectionSnapshot(it.accountId).first &&
            InboundEvidence.quietMs(it.accountId) < DEAF_LIMIT_MS }
    }

    /** One watchdog tick — periodic driver (DRingService bg, HomeFragment fg). The caller skips this
     *  during an active call (toggling proxy mid-call would drop it). */
    fun tick(c: Context, accounts: AccountService) {
        // Echo-suppression regime: subscribe echoes are non-evidence ONLY in push-SUBSCRIBE mode.
        // On full DHT or adaptive LISTEN a subscribe answer proves the receive path — it must count,
        // or the probes discard their own answers and quiet evenings false-wedge (2026-07-23).
        // Keyed to the daemon's ACTUAL proxy state, never the UI pref: the charging/wedge machinery
        // forces the proxy off while the pref still says proxy, and judging by the pref left the
        // suppression armed on a de-facto full DHT — every probe answer discarded, a 7-min
        // false-wedge limit cycle all night (56 incidents, 2026-07-25).
        val proxyOnNow = accounts.getAccounts().any { it.isJami && it.isDhtProxyEnabled }
        InboundEvidence.pushMode = proxyOnNow && !noPushAdaptive
        // Unattended per-hour data accounting (2026-07-26, CRL-landfill work). Piggy-backs on this
        // existing ~1-min tick — appends only when an hour has elapsed, so it costs one counter read
        // per tick and nothing else. Mode label from the ACTUAL daemon state plus the pref, since
        // the two diverge whenever charging or a wedge forces the proxy off under a "proxy" pref.
        runCatching {
            val mode = (if (proxyOnNow) "proxy" else "fullDHT") +
                    (if (UiPrefs.isFullDhtMode(c)) "" else "/pref=proxy") +
                    (if (noPushAdaptive) "/adaptive" else "")
            DataMeter.hourlyTick(c, mode)
        }
        if (!ledgerWired) {
            ledgerWired = true
            val app = c.applicationContext
            accounts.reregisterMarker = { id, inFlight -> UiPrefs.setReregisterInFlight(app, id, inFlight) }
            // One-shot after the 2026-07-25 update: rotate the FCM token to orphan the accumulated
            // stale server-side subscription generations (2–3 pushes/s were burning CPU + microG
            // quota; they would otherwise keep pushing for up to ~24 h). Delayed so startup settles.
            if (!UiPrefs.isTokenRotatedOnce(c)) {
                UiPrefs.setTokenRotatedOnce(c)
                handler.postDelayed({
                    log(c, "one-shot push-token rotation (stale subscription cleanup, 2026-07-25 update)")
                    runCatching { cx.ring.application.JamiApplication.instance?.rotatePushToken() }
                }, 30_000L)
            }
            // Telemetry honesty: start the proxy-off clock from the ACTUAL standing mode at process
            // start (it used to start at 0 and only track the watchdog's own linger windows).
            if (UiPrefs.isFullDhtMode(c) && proxyOffSinceMs == 0L) proxyOffSinceMs = now()
            // Restore adaptive no-push across a process restart mid-outage (a restart during an
            // outage would otherwise return to push mode and eat a 10-min re-detection). Re-enter
            // streaming immediately; the permanent-service backup is remembered so exit restores it.
            if (!UiPrefs.isFullDhtMode(c) && UiPrefs.isAdaptivePersisted(c)) {
                noPushAdaptive = true
                adaptiveHoldUntil = UiPrefs.getAdaptiveHoldUntil(c)
                adaptivePermBackup = UiPrefs.getAdaptivePermBackup(c)
                log(c, "restored adaptive no-push from a previous process — re-entering streaming (hold ${((adaptiveHoldUntil - now()).coerceAtLeast(0)) / 60_000}m)")
                accounts.setPushNotificationToken("")
                handler.postDelayed({
                    recovering = true; lastRecoverMs = now()
                    accounts.forceReconnectAllAccounts()
                    resubscribeAllPresence(accounts)
                }, REREGISTER_DELAY_MS)
            }
        }
        healInterruptedReregisters(c, accounts)
        val active = UiPrefs.isRecoveryBaseEnabled(c) || UiPrefs.isRecoveryPingEnabled(c)
        if (!active) return   // recovery fully off → leave the proxy alone
        if (active) {
            if (UiPrefs.isRecoveryPingEnabled(c) && UiPrefs.isCanaryConfigured(c)) canaryTick(c, accounts)
            else heuristicTick(c, accounts)
            perAccountTick(c, accounts)
            verifyStuckPeers(c, accounts)
            // Standing push-leg watch (proxy mode only): one ~1 KB self-test per 30 min, so the log
            // shows when a dead push leg (e.g. an ntfy rate limit) comes back — DOWN→UP notifies.
            if (now() - lastPushProbeMs >= PUSH_PROBE_PERIODIC_MS) maybeProbePush(c, accounts, "periodic")
        }
        if (UiPrefs.isRestrictedNet(c)) scheduleRestrictedRetest(c, accounts)  // resume after process restart
        applyProxyState(c, accounts)
    }

    // ---- Reactive detector 1: daemon error storm (fed by LogStormMonitor) --------------------
    /** A dense burst of TLS/ICE fatals = links dying and failing to rebuild (the 10:42 signature
     *  of the 2026-07-20 outage). React in seconds instead of waiting for a tick. Fresh inbound
     *  evidence vetoes the reaction — mass teardown with traffic still flowing is benign churn. */
    fun onErrorStorm(c: Context, accounts: AccountService, recentLines: List<String>) {
        handler.post {
            val t = now()
            if (t - lastStormMs < STORM_COOLDOWN_MS) return@post
            // (1) Post-recovery settle blackout — a recovery re-registers every account, tearing down and
            // rebuilding every connection, which emits the very TLS/ICE fatals the monitor counts. Ignoring
            // storms for a settle window after ANY recovery breaks the self-inflicted recover→teardown→
            // re-storm loop that spun for 3h on 2026-07-22.
            if (lastRecoverMs != 0L && t - lastRecoverMs < STORM_SETTLE_BLACKOUT_MS) {
                log(c, "error storm ${(t - lastRecoverMs) / 1000}s after a recovery — teardown settle, ignored")
                return@post
            }
            val sinceEvidence = t - InboundEvidence.lastMs
            if (sinceEvidence < STORM_EVIDENCE_VETO_MS) {
                log(c, "error storm ignored — inbound evidence ${sinceEvidence / 1000}s ago (benign churn)")
                return@post
            }
            // (2) A storm is only a SUSPICION. Verify the REAL network and look for a stuck delivery to a
            // reachable peer; recover ONLY when corroborated. A burst with the network alive and nothing
            // stuck is transient link churn (a VPN/DNS flap tearing sockets that then rebuild), not a fault —
            // recovering on it just re-tears everything and re-storms.
            ensureNetVerdict(c) {
                val t2 = now()
                if (t2 - lastStormMs < STORM_COOLDOWN_MS) return@ensureNetVerdict
                lastStormMs = t2
                val down = networkDown()
                val stuckReachable = anyStuckToReachable(accounts, t2)
                if (!down && !stuckReachable) {
                    // (4) De-weight the raw error count: don't recover on noise. Accelerate the evidence
                    // path instead — a genuinely emerging problem confirms via verified deafness at half
                    // the usual limit; transient churn simply lapses.
                    stormAmbiguousUntil = t2 + STORM_AMBIGUOUS_MS
                    writeIncident(c, "error-storm-uncorroborated", "network alive, no stuck-to-reachable — transient churn", recentLines)
                    log(c, "error storm — network alive + no stuck delivery → transient churn, not recovering (deafness watch tightened)")
                    return@ensureNetVerdict
                }
                // (3) Escalate to HARD only when the prior recovery demonstrably didn't help: a corroborated
                // storm recurs within the hard window AND no inbound has arrived since that recovery.
                val priorDidntHelp = lastRecoverMs != 0L && t2 - lastRecoverMs < STORM_HARD_WINDOW_MS &&
                    InboundEvidence.lastMs < lastRecoverMs
                val cause = if (down) "network egress DOWN" else "stuck delivery to a reachable peer"
                writeIncident(c, "error-storm", "$cause; inbound ${(t2 - InboundEvidence.lastMs) / 1000}s ago", recentLines)
                if (priorDidntHelp) {
                    log(c, "error storm ($cause) — prior recover didn't restore inbound → HARD reset")
                    notifyUser(c, c.getString(cx.ring.R.string.notif_storm_hard, stamp()))
                    hardReset(c, accounts)
                    maybeDiagnoseTransport(c, accounts, "persistent error storm")
                } else {
                    log(c, "error storm ($cause) → smart recover")
                    notifyUser(c, c.getString(cx.ring.R.string.notif_storm_smart, stamp()))
                    manualRecover(c, accounts)
                }
            }
        }
    }

    /** Verified-unreachable dot demotion (2026-07-24). A contact can keep a stale BLUE (available)
     *  dot while the last outgoing message to them is stuck: presence is a DHT announce with its
     *  own TTL, delivery needs a live device channel. When the stuck scan finds a peer below
     *  CONNECTED, record PeerReachability evidence: the presence gate paints the dot RED and the
     *  monitor surfaces say "unreachable", until the message delivers or a real channel appears
     *  (CONNECTED clears it in the gate). Purely a truth-display mechanism — it triggers NO
     *  recovery (the wedge triage above stays the only recovery path, per the +10 redesign). */
    private fun verifyStuckPeers(c: Context, accounts: AccountService) {
        val t = now()
        // Same-device siblings are never "unreachable" (same daemon process) — their stuck
        // messages are the ACCOUNT's sync problem (NOT_SYNCING red), not the contact's; the
        // wedge triage in heuristicTick handles them as CONNECTED-class evidence.
        val sameDeviceUris = accounts.getAccounts()
            .filter { it.isJami && it.isRegistered }
            .mapNotNull { it.uri?.removePrefix("jami:")?.removePrefix("ring:")?.takeIf(String::isNotEmpty) }
            .toSet()
        accounts.getAccounts().filter { it.isJami }.forEach { acc -> runCatching {
            val stuck = ConnectionHealth.accountStuckMessages(acc, t)
                .filter { it.presence != Contact.PresenceStatus.CONNECTED &&
                    it.memberUri.removePrefix("jami:").removePrefix("ring:") !in sameDeviceUris }
                .map { it.memberUri }.distinct()
            val (newly, restore) = PeerReachability.sync(acc.accountId, stuck)
            newly.forEach { uri ->
                val wasAvailable = acc.getContactFromCache(uri).lastPresence == Contact.PresenceStatus.AVAILABLE
                acc.presenceUpdate(uri, 0)   // gate holds it OFFLINE against stale re-announces
                // Note AFTER the synthetic OFFLINE above (the gate wipes hadAvailable on raw 0).
                if (wasAvailable) PeerReachability.noteHadAvailable(acc.accountId, uri)
                log(c, "acct ${acc.accountId.take(6)}: peer ${uri.take(8)}… unreachable (msg stuck, no live channel) — dot red")
            }
            restore.forEach { uri ->
                acc.presenceUpdate(uri, 1)   // evidence gone + peer was announced → back to blue
                log(c, "acct ${acc.accountId.take(6)}: peer ${uri.take(8)}… delivering again — dot restored")
            }
        }.onFailure { e ->
            // A silent per-account failure here would look identical to "nothing stuck" — log it.
            log(c, "acct ${acc.accountId.take(6)}: stuck-peer scan FAILED (${e.javaClass.simpleName}: ${e.message})")
        } }
    }

    /** Corroboration for an error storm: a message stuck to a peer the daemon still sees as reachable
     *  (CONNECTED) is a real delivery failure, not transient link churn. */
    private fun anyStuckToReachable(accounts: AccountService, t: Long): Boolean =
        accounts.getAccounts().any { acc ->
            acc.isJami && acc.isRegistered &&
                ConnectionHealth.accountStuckMessages(acc, t).any {
                    it.presence == net.jami.model.Contact.PresenceStatus.CONNECTED
                }
        }

    // Shared NETWORK-liveness verdict (real STUN-UDP + TCP egress via NetProbe). Replaces the old
    // per-account "presence probe": re-subscribing to probe only echoed cached presence and answered
    // itself, so a dead network read as healthy (2026-07-21). NetProbe is a real round-trip.
    @Volatile private var netVerdictMs = 0L
    @Volatile private var netAlive = true
    @Volatile private var netCheckInFlight = false
    private const val NET_VERDICT_TTL_MS = 90_000L

    private fun ensureNetVerdict(c: Context, onReady: () -> Unit) {
        if (now() - netVerdictMs < NET_VERDICT_TTL_MS) { onReady(); return }
        if (netCheckInFlight) return
        netCheckInFlight = true
        Thread({
            val udp = NetProbe.udpWorks(); val tcp = NetProbe.tcpWorks()
            handler.post {
                netAlive = udp || tcp
                netVerdictMs = now()
                netCheckInFlight = false
                log(c, "network check: UDP=${if (udp) "ok" else "×"} TCP=${if (tcp) "ok" else "×"} → ${if (netAlive) "alive" else "DOWN"}")
                onReady()
            }
        }, "net-verify").start()
    }

    // ---- Reactive detector 2: PER-ACCOUNT deafness, verified against the REAL network -----------
    /** Each account is judged on its OWN inbound evidence (presence echoes now excluded). Silence
     *  past the limit only SUSPECTS. Instead of a self-answering re-subscribe, verify the real
     *  network with NetProbe: if egress is DOWN the silence is a genuine outage (the account is
     *  deaf — this is what dead WiFi looks like); if egress is ALIVE the account is merely quiet
     *  (a soft proxy wedge is caught by the error-storm / stuck-message detectors), so its clock is
     *  reset rather than churned. Healthy accounts are never disturbed. */
    private fun perAccountTick(c: Context, accounts: AccountService) {
        val t = now()
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val netUp = cm?.activeNetwork != null
        val regd = accounts.getAccounts().filter { it.isJami && it.isRegistered }
        acctStates.keys.retainAll(regd.map { it.accountId }.toSet())
        // Proxy mode is the fragile mode (the only place the subscription wedge lives) — suspect at
        // half the limit there, so a wedged proxy is probed in ~2.5 min instead of 5 (2026-07-23).
        val limit = if (stuckAmbiguous || now() < stormAmbiguousUntil || !UiPrefs.isFullDhtMode(c))
            DEAF_LIMIT_MS / 2 else DEAF_LIMIT_MS
        var anyQuiet = false
        var healthy = 0
        for (acc in regd) {
            val st = acctStates.getOrPut(acc.accountId) { AcctState() }
            if (InboundEvidence.quietMs(acc.accountId) < limit) {
                healthy++
                if (st.strikes > 0) {
                    log(c, "acct ${acc.accountId.take(6)}: inbound back (${InboundEvidence.lastKind(acc.accountId)}) — deaf cleared")
                    // The account just came back from verified deafness — messages sent to it during
                    // the deaf window are still sitting on their senders. Pull them now.
                    scheduleBackfill(c, accounts, "acct ${acc.accountId.take(6)} deaf cleared")
                }
                st.strikes = 0; st.nextCheckMs = 0L
            } else if (netUp && t >= st.nextCheckMs) anyQuiet = true
        }
        if (!anyQuiet || !netUp) return
        // At least one account is quiet past the limit → verify the real network, then resolve each.
        ensureNetVerdict(c) {
            val t2 = now()
            for (acc in regd) {
                val id = acc.accountId
                val st = acctStates[id] ?: continue
                val quiet = InboundEvidence.quietMs(id)
                if (quiet < limit || t2 < st.nextCheckMs) continue
                if (!netAlive) {
                    accountDeafConfirmed(c, accounts, id, st, networkDown = true)
                } else if (healthy > 0) {
                    // DIFFERENTIAL DEAFNESS SUSPECTED — but silence + sibling contrast is NOT proof
                    // (2026-07-23: on a quiet phone the bare clock false-positived every ~3 min all
                    // evening — real inbound evidence naturally arrives sparser than the limit, and
                    // each recovery's own subscribe echoes reset the sibling clocks, keeping the
                    // differential condition true forever: a self-sustaining limit cycle that churned
                    // the proxy clients and spammed the push topic into a 429 rate-limit). Gate the
                    // action behind the SAME verification the uniform path got in +11: a silent
                    // presence re-arm probe on THIS account; only an unanswered probe recovers.
                    // A healthy-but-quiet client answers via the subscribe-response echoes (they ride
                    // the HTTP connection, not push, so a dead push leg does not fail the probe);
                    // a genuinely wedged client stays silent and recovers ~60 s later than before.
                    if (st.probeInFlight) continue
                    st.probeInFlight = true
                    st.probeStartQuiet = t2 - InboundEvidence.quietMs(id)   // lastMs snapshot
                    log(c, "acct ${id.take(6)}: deaf ${quiet / 60_000}m but $healthy/${regd.size} healthy — probe (presence re-arm), verdict in ${PROBE_VERDICT_MS / 1000}s")
                    accounts.resubscribeAccountPresence(id)
                    handler.postDelayed({
                        st.probeInFlight = false
                        val lastNow = now() - InboundEvidence.quietMs(id)
                        if (lastNow > st.probeStartQuiet) {
                            log(c, "acct ${id.take(6)}: probe answered (${InboundEvidence.lastKind(id)}) — quiet but receiving, no recovery")
                            st.strikes = 0
                            st.nextCheckMs = now() + IDLE_REARM_MS
                        } else {
                            val t3 = now()
                            val quiet3 = InboundEvidence.quietMs(id)
                            st.strikes++
                            st.nextCheckMs = t3 + WEDGE_RECOVER_BACKOFF_MS
                            // Corroboration against the coin-flip false positive (2026-07-24): on a
                            // QUIET account the only thing that can answer the probe is a peer's own
                            // periodic presence re-put (~10–15 min) landing in the ~52 s window after
                            // the 8 s echo blackout — so a single unanswered probe is often just bad
                            // luck, not a wedge. A REAL wedge, by contrast, has a message STUCK to a
                            // reachable (CONNECTED) peer. So: recover on the first miss only when
                            // something is genuinely stuck-to-connected; otherwise require a SECOND
                            // consecutive unanswered probe (no inbound cleared the strike in between)
                            // before treating it as verified. The extra ~2 min only ever applies when
                            // nothing is being sent to the account — i.e. when it can't cost a message.
                            val stuckToConnected = runCatching {
                                accounts.getAccount(id)?.let { acct ->
                                    ConnectionHealth.accountStuckMessages(acct, t3)
                                        .any { m -> m.presence == net.jami.model.Contact.PresenceStatus.CONNECTED }
                                } ?: false
                            }.getOrDefault(false)
                            if (st.strikes < 2 && !stuckToConnected) {
                                log(c, "acct ${id.take(6)}: probe unanswered but nothing stuck to a reachable peer — SUSPECT (strike ${st.strikes}), re-probe in ${WEDGE_RECOVER_BACKOFF_MS / 60_000}m")
                            } else {
                                recovering = true; lastRecoverMs = t3
                                // The wedged path depends on the mode — "proxy subscription" outside proxy mode was misleading.
                                val path = if (UiPrefs.isFullDhtMode(c)) "DHT listen" else "proxy subscription"
                                val why = if (stuckToConnected) "stuck→connected peer" else "probe unanswered ×${st.strikes}"
                                writeIncident(c, "acct-wedge", "account ${id.take(8)} deaf ${quiet3 / 60_000}m, $why — $path wedged (strike ${st.strikes})", LogStormMonitor.recentLines())
                                if (st.strikes >= 3 && !UiPrefs.isFullDhtMode(c)) {
                                    // Repeat verified wedge on the SAME account: a bare re-register re-attaches
                                    // the CACHED proxy endpoint (proxyServerCached_ survives it) — the 41c041
                                    // 12-min hard wedge rode that forever. Cycle THIS account's proxy off→on
                                    // instead, which is the only thing that re-picks the endpoint.
                                    log(c, "acct ${id.take(6)}: verified WEDGE again (strike ${st.strikes}) → proxy cycle for a FRESH endpoint; next +${WEDGE_RECOVER_BACKOFF_MS / 60_000}m")
                                    accounts.recoverAccountFromWedge(id) { accounts.resubscribeAccountPresence(id) }
                                } else {
                                    log(c, "acct ${id.take(6)}: verified WEDGE ($why) → re-register + resubscribe; next +${WEDGE_RECOVER_BACKOFF_MS / 60_000}m")
                                    accounts.forceReconnectAccount(id)
                                    accounts.resubscribeAccountPresence(id)
                                }
                                scheduleBackfill(c, accounts, "acct ${id.take(6)} wedge recover")
                                maybeEnterAdaptiveOnStarvation(c, accounts, "acct ${id.take(6)} wedge")
                                maybeProbePush(c, accounts, "acct ${id.take(6)} wedge")
                            }
                        }
                    }, PROBE_VERDICT_MS)
                } else {
                    // Uniform deafness (no sibling receiving) on a live network — genuine idle OR an
                    // ALL-accounts wedge (2026-07-23: four wedged proxy subscriptions read as "idle" for
                    // 20+ min). No more touch() self-certification here — heuristicTick's probe-verdict
                    // decides: an answered presence probe marks the accounts verified; an unanswered one
                    // recovers. Just quiet the strike state so this branch doesn't churn every tick.
                    if (st.strikes > 0) log(c, "acct ${id.take(6)}: quiet ${quiet / 60_000}m, all quiet — deferring to the uniform probe")
                    st.strikes = 0; st.nextCheckMs = t2 + IDLE_REARM_MS
                }
            }
        }
    }

    /** A quiet account with a verified-DOWN network (real outage) — or, when networkDown is false, a
     *  soft per-account wedge. Recovery cannot conjure a dead network, so on a down network we just
     *  re-register (which prods the daemon to re-evaluate connectivity and may switch networks) and
     *  tell the user; on a live network we re-register + re-arm presence. Doubling backoff. */
    private fun accountDeafConfirmed(c: Context, accounts: AccountService, id: String, st: AcctState, networkDown: Boolean) {
        val t = now()
        val quiet = InboundEvidence.quietMs(id)
        st.strikes++
        val backoff = (DEAF_LIMIT_MS shl (st.strikes - 1).coerceAtMost(3)).coerceAtMost(DEAF_BACKOFF_MAX_MS)
        st.nextCheckMs = t + backoff
        recovering = true; lastRecoverMs = t
        if (networkDown) {
            writeIncident(c, "network-down", "account ${id.take(8)} deaf ${quiet / 60_000}m + network egress DOWN (strike ${st.strikes})", LogStormMonitor.recentLines())
            log(c, "acct ${id.take(6)}: deaf ${quiet / 60_000}m + NETWORK DOWN → re-register (may re-evaluate/switch network); next +${backoff / 60_000}m")
            notifyUser(c, c.getString(cx.ring.R.string.notif_network_down, stamp()))
            accounts.forceReconnectAccount(id)
        } else {
            writeIncident(c, "acct-deaf", "account ${id.take(8)} deaf ${quiet / 60_000}m (strike ${st.strikes})", LogStormMonitor.recentLines())
            log(c, "acct ${id.take(6)}: deaf ${quiet / 60_000}m → recover; next +${backoff / 60_000}m")
            notifyUser(c, c.getString(cx.ring.R.string.notif_deaf_hard, (quiet / 60_000).toInt(), stamp()))
            accounts.recoverAccountFromWedge(id) { accounts.resubscribeAccountPresence(id) }
            scheduleBackfill(c, accounts, "acct ${id.take(6)} deaf recover")
        }
    }

    // ---- Push-path (ntfy) self-test ----------------------------------------------------------
    /** In proxy(push) mode the ONLY carrier of new DHT values is proxy→ntfy→app — the daemon
     *  subscribes push-style and then hears nothing except what the distributor delivers. When
     *  accounts go deaf in that mode, self-test the leg end-to-end (PushProbe POSTs a marker to
     *  our own ntfy endpoint and waits for the echo). Failure is STRUCTURAL: no amount of
     *  re-register/proxy-cycling can conjure inbound while the push leg is dead (2026-07-23:
     *  ntfy riding Kōjiki's rewritten DNS silently killed every push for days — the daemon read
     *  "wedged" while nothing could ever arrive). Log + incident + notify; throttled. */
    private fun maybeProbePush(c: Context, accounts: AccountService, reason: String) {
        if (UiPrefs.isFullDhtMode(c)) return   // full DHT does not ride push
        val t = now()
        // FCM backend has NO end-to-end self-test — the phone cannot POST to its own FCM token
        // (only the dhtproxy servers, holding the server key, can). DOWN detection is the
        // starvation trigger's job (transport-agnostic); EXIT is timed optimistic re-entry: once
        // the hold expires, restore the token and re-register — if pushes resume, we stay in push
        // mode; if the starvation trigger re-fires, we re-enter with a doubled hold.
        if (UiPrefs.getPushBackend(c) == UiPrefs.PUSH_FCM) {
            if (t - lastPushProbeMs < PUSH_PROBE_THROTTLE_MS) return
            lastPushProbeMs = t
            if (noPushAdaptive && t >= adaptiveHoldUntil) {
                log(c, "FCM adaptive: hold expired → optimistic push re-entry (restore token, re-register)")
                exitNoPushAdaptive(c, accounts)
            }
            return
        }
        // Startup grace: right after process start the UnifiedPush distributor has not registered
        // the endpoint yet — "no endpoint" then is a race, not a verdict, and acting on it entered
        // adaptive no-push at every app start (2026-07-23 21:39). No verdict until the endpoint
        // exists or the process is old enough for its absence to be real.
        val endpointReady = cx.ring.application.JamiApplication.instance?.pushToken?.first?.isNotEmpty() == true
        if (!endpointReady && t - processStartMs < PUSH_ENDPOINT_GRACE_MS) return
        if (t - lastPushProbeMs < PUSH_PROBE_THROTTLE_MS) return
        lastPushProbeMs = t
        log(c, "push self-test ($reason) — marker to own ntfy endpoint, 20s echo window")
        PushProbe.run { ok, detail ->
            handler.post {
                val was = pushLegDown
                pushLegDown = !ok
                if (ok) {
                    if (noPushAdaptive) {
                        if (now() < adaptiveHoldUntil) {
                            log(c, "push self-test OK ($detail) but adaptive HELD ${(adaptiveHoldUntil - now()) / 60_000}m more — the proxies' leg was dead on the last exit")
                        } else {
                            log(c, "push self-test OK — $detail; push leg RESTORED")
                            exitNoPushAdaptive(c, accounts)
                        }
                    } else if (was == true) {
                        // DOWN→UP transition: the leg came back (e.g. a rate limit lifted) — say so.
                        log(c, "push self-test OK — $detail; push leg RESTORED")
                        notifyUser(c, c.getString(cx.ring.R.string.notif_push_up, stamp()))
                    } else {
                        log(c, "push self-test OK — $detail; push leg alive")
                    }
                } else {
                    log(c, "push self-test FAILED — $detail; push delivery CANNOT arrive (check ntfy app / DNS exclusion / rate limit)")
                    if (!noPushAdaptive) {
                        writeIncident(c, "push-down",
                            "ntfy self-test FAILED ($detail) — push delivery dead ($reason) → adaptive no-push",
                            LogStormMonitor.recentLines())
                        enterNoPushAdaptive(c, accounts)
                    }
                }
            }
        }
    }

    /** The self-test's blind spot (2026-07-24): it proves phone→ntfy→phone, but delivery rides
     *  proxy→ntfy→phone — a leg that can be dead (the dhtproxy IPs chronically rate-limited at
     *  ntfy.sh) while our own posts echo fine. The observable truth for THAT leg is real daemon
     *  pushes arriving. A VERIFIED wedge in push mode with no real push for
     *  [REAL_PUSH_STARVATION_MS] means push delivery is dead for us regardless of the self-test →
     *  enter streaming. A quick relapse after an exit arms a 2-h hold so a green self-test can't
     *  oscillate us back onto a dead leg. */
    private fun maybeEnterAdaptiveOnStarvation(c: Context, accounts: AccountService, reason: String) {
        if (UiPrefs.isFullDhtMode(c) || noPushAdaptive) return
        val t = now()
        val ref = maxOf(PushEvidence.lastRealPushMs, processStartMs)
        if (t - ref < REAL_PUSH_STARVATION_MS) return
        val fcm = UiPrefs.getPushBackend(c) == UiPrefs.PUSH_FCM
        val relapse = lastAdaptiveExitMs != 0L && t - lastAdaptiveExitMs < ADAPTIVE_RELAPSE_WINDOW_MS
        if (relapse) {
            if (fcm) {
                // Optimistic re-entry failed again quickly — the leg is still dead. Double the hold.
                adaptiveFcmHoldMs = minOf(adaptiveFcmHoldMs * 2, ADAPTIVE_FCM_HOLD_MAX_MS)
                log(c, "FCM push starvation relapse ${(t - lastAdaptiveExitMs) / 60_000}m after re-entry — hold doubled to ${adaptiveFcmHoldMs / 60_000}m")
            } else {
                adaptiveHoldUntil = t + ADAPTIVE_REENTRY_HOLD_MS
                log(c, "push starvation relapse ${(t - lastAdaptiveExitMs) / 60_000}m after adaptive exit — holding streaming ${ADAPTIVE_REENTRY_HOLD_MS / 3_600_000}h")
            }
        } else if (fcm) {
            // Leg was healthy long enough since the last exit → reset the FCM hold to baseline.
            adaptiveFcmHoldMs = ADAPTIVE_FCM_HOLD_BASE_MS
        }
        writeIncident(c, "push-starved",
            "verified wedge ($reason) with no real push for ${(t - ref) / 60_000}m in push mode — proxies' push leg dead for us (self-test notwithstanding)",
            LogStormMonitor.recentLines())
        log(c, "verified wedge + no real push ${(t - ref) / 60_000}m (push mode) → adaptive no-push (self-test notwithstanding)")
        enterNoPushAdaptive(c, accounts)
    }

    /** Adaptive no-push proxy (2026-07-23). With the push leg dead, push-mode subscriptions are
     *  structurally deaf: SUBSCRIBE delivers new values ONLY via proxy→ntfy→app. The F-Droid
     *  (noPush-flavor) configuration needs no push at all — with NO device key the proxy client
     *  subscribes with LISTEN, a keep-alive connection the proxy STREAMS values over continuously.
     *  So while the self-test says the leg is down, clear the daemon's push token and re-register:
     *  the rebuilt clients come up in LISTEN mode → streaming inbound, zero ntfy dependence, at a
     *  battery cost paid only for the outage's duration. Restore the token on recovery. NOTE the
     *  re-register is mandatory: opendht's token-change resubscribe no-ops on an EMPTY key, so a
     *  bare token clear leaves the old push subscriptions in place. */
    private fun enterNoPushAdaptive(c: Context, accounts: AccountService) {
        noPushAdaptive = true
        // FCM has no self-test to confirm recovery, so stream at least a baseline (doubling on
        // relapse) before optimistically re-trying push. UnifiedPush leaves the hold at whatever
        // the relapse logic set (0 normally — the self-test drives its exit).
        if (UiPrefs.getPushBackend(c) == UiPrefs.PUSH_FCM)
            adaptiveHoldUntil = now() + adaptiveFcmHoldMs
        // Guarantee the process survives the outage: streaming LISTEN only works while the daemon
        // lives, but the push lifecycle lets Android reap it (a push would normally revive it —
        // and push is exactly what's dead). Turn on the permanent foreground service for the
        // duration, backing up the user's setting so exit restores it. This makes adaptive mode
        // equal the F-Droid noPush configuration (which always runs the permanent service).
        val permBackup = mPrefs(c)?.settings?.enablePermanentService ?: false
        adaptivePermBackup = permBackup
        if (!permBackup) setPermanentService(c, true)
        persistAdaptive(c)
        log(c, "adaptive no-push ON — clearing push token; permanent service on; re-register rebuilds proxy clients in LISTEN (streaming) mode")
        notifyUser(c, c.getString(cx.ring.R.string.notif_nopush_on, stamp()))
        accounts.setPushNotificationToken("")
        handler.postDelayed({
            recovering = true; lastRecoverMs = now()
            accounts.forceReconnectAllAccounts()
            resubscribeAllPresence(accounts)
            scheduleBackfill(c, accounts, "adaptive no-push enter")
        }, REREGISTER_DELAY_MS)
    }

    private fun exitNoPushAdaptive(c: Context, accounts: AccountService) {
        val token = cx.ring.application.JamiApplication.instance?.pushToken
        val platform = cx.ring.application.JamiApplication.instance?.pushPlatform ?: ""
        if (token == null || token.first.isEmpty()) {
            // The leg answered but the app holds no endpoint (distributor unregistered?) — a token
            // we cannot restore. Stay in streaming mode; the next periodic test retries the exit.
            log(c, "push leg answered but no push endpoint to restore — staying in LISTEN mode")
            return
        }
        noPushAdaptive = false
        lastAdaptiveExitMs = now()
        // Restore the permanent-service setting to whatever it was before we forced it on.
        if (!adaptivePermBackup) setPermanentService(c, false)
        persistAdaptive(c)
        log(c, "adaptive no-push OFF — push leg restored; permanent service restored; re-registering push token, clients rebuild in push mode")
        notifyUser(c, c.getString(cx.ring.R.string.notif_nopush_off, stamp()))
        accounts.setPushNotificationConfig(token.first, token.second, platform)
        handler.postDelayed({
            recovering = true; lastRecoverMs = now()
            accounts.forceReconnectAllAccounts()
            resubscribeAllPresence(accounts)
            scheduleBackfill(c, accounts, "adaptive no-push exit")
        }, REREGISTER_DELAY_MS)
    }

    /** Backup of the user's enablePermanentService before adaptive forced it on. */
    @Volatile private var adaptivePermBackup = false

    private fun mPrefs(c: Context): net.jami.services.PreferencesService? =
        (c.applicationContext as? cx.ring.application.JamiApplication)?.mPreferencesService

    /** Flip enablePermanentService via the settings model — DRingService reacts and starts/stops the
     *  permanent foreground service on the settings subject. */
    private fun setPermanentService(c: Context, on: Boolean) {
        val ps = mPrefs(c) ?: return
        val s = ps.settings
        if (s.enablePermanentService == on) return
        ps.settings = s.copy(enablePermanentService = on)
        log(c, "permanent foreground service → ${if (on) "ON (adaptive streaming needs a live process)" else "restored"}")
    }

    private fun persistAdaptive(c: Context) =
        UiPrefs.setAdaptivePersisted(c, noPushAdaptive, adaptiveHoldUntil, adaptivePermBackup)

    // ---- Restricted-network (hostile WiFi) mode ----------------------------------------------
    /** Called after a recovery visibly failed to help (spent wedge ledger, repeat storm,
     *  confirmed deafness). Runs the two-second egress diagnosis on a background thread:
     *  UDP blocked while TCP works = the hostile-network trap → enter restricted mode.
     *  Throttled; no-op when already restricted. */
    /** Probe transport egress after a NETWORK CHANGE — the only moment hostility can actually change.
     *  A timer would be the wrong trigger: on a stable network the answer never moves, and on a new
     *  one it moves immediately. Advisory ONLY: it records the verdict for the monitor's Transport
     *  row and notifies on a transition into hostility, but never switches mode. Mode stays a manual
     *  act on the hexagon, except for the genuine emergency path in [enterRestricted].
     *
     *  Costs one STUN datagram pair and one TCP connect, throttled — negligible, but not free, so it
     *  does not run on an unchanged network. */
    fun onNetworkChanged(c: Context) {
        val t = now()
        if (t - lastTransportProbeMs < TRANSPORT_PROBE_THROTTLE_MS) return
        lastTransportProbeMs = t
        val app = c.applicationContext
        NetProbe.refreshAsync({ r -> handler.post(r) }) { v ->
            log(app, "transport probe (network change): UDP=${if (v.udp) "ok" else "BLOCKED"} TCP=${if (v.tcp) "ok" else "BLOCKED"}")
            val hostile = v.transport == NetProbe.Transport.HOSTILE
            // Notify only on the TRANSITION into hostility, and never while restricted mode is
            // already engaged — that path posts its own, more specific notification.
            if (hostile && !advisedHostile && !UiPrefs.isRestrictedNet(app))
                notifyUser(app, app.getString(cx.ring.R.string.notif_transport_hostile, stamp()))
            advisedHostile = hostile
        }
    }

    fun maybeDiagnoseTransport(c: Context, accounts: AccountService, reason: String) {
        val t = now()
        if (UiPrefs.isRestrictedNet(c)) return
        if (t - lastDiagMs < DIAG_THROTTLE_MS) return
        lastDiagMs = t
        Thread({
            val udp = NetProbe.udpWorks()
            val tcp = NetProbe.tcpWorks()
            NetProbe.record(udp, tcp)   // feed the monitor's Transport row — don't discard the result
            handler.post {
                log(c, "transport diagnosis ($reason): UDP=${if (udp) "ok" else "BLOCKED"} TCP=${if (tcp) "ok" else "BLOCKED"}")
                when {
                    !udp && tcp -> enterRestricted(c, accounts)
                    !udp && !tcp -> log(c, "both egress paths blocked — no usable network; leaving state machine unchanged")
                }
            }
        }, "net-diagnosis").start()
    }

    private fun enterRestricted(c: Context, accounts: AccountService) {
        UiPrefs.setRestrictedNet(c, true)
        restrictedPasses = 0
        writeIncident(c, "restricted-net", "UDP egress blocked, TCP alive — DHT proxy pinned ON, relay mode", LogStormMonitor.recentLines())
        log(c, "RESTRICTED NETWORK: UDP blocked, TCP ok — proxy pinned ON; TURN relays carry traffic; re-testing every ${RESTRICTED_RETEST_MS / 60_000}m")
        notifyUser(c, c.getString(cx.ring.R.string.notif_restricted, stamp()))
        applyProxyState(c, accounts)
        scheduleRestrictedRetest(c, accounts)
    }

    /** 2-minute UDP re-test loop while restricted; two consecutive passes exit the mode
     *  (flap damping — re-entry is slow by design, needing fresh failures + a spent recovery). */
    private fun scheduleRestrictedRetest(c: Context, accounts: AccountService) {
        if (retestScheduled) return
        retestScheduled = true
        handler.postDelayed({
            retestScheduled = false
            if (!UiPrefs.isRestrictedNet(c)) return@postDelayed
            Thread({
                val udp = NetProbe.udpWorks()
                handler.post {
                    if (udp) {
                        restrictedPasses++
                        if (restrictedPasses >= RESTRICTED_EXIT_PASSES) exitRestricted(c, accounts)
                        else { log(c, "restricted net: UDP pass ($restrictedPasses/$RESTRICTED_EXIT_PASSES) — confirming"); scheduleRestrictedRetest(c, accounts) }
                    } else {
                        restrictedPasses = 0
                        scheduleRestrictedRetest(c, accounts)
                    }
                }
            }, "net-retest").start()
        }, RESTRICTED_RETEST_MS)
    }

    private fun exitRestricted(c: Context, accounts: AccountService) {
        UiPrefs.setRestrictedNet(c, false)
        log(c, "restricted net cleared — UDP egress back ($RESTRICTED_EXIT_PASSES consecutive passes); normal state machine resumes")
        notifyUser(c, c.getString(cx.ring.R.string.notif_restricted_exit, stamp()))
        applyProxyState(c, accounts)
    }

    // ---- Incident forensics + user notification ---------------------------------------------
    /** Append a full incident record (context + recent daemon error lines) to
     *  watchdog-incidents.log in the app's external files dir — readable with any file manager
     *  at Android/data/shiroikuma.jami/files/ — and mirror a one-liner to the rolling log. */
    private fun writeIncident(c: Context, kind: String, detail: String, lines: List<String>) {
        val n = ++incidentSeq
        log(c, "incident #$n [$kind] $detail")
        runCatching {
            val f = File(c.getExternalFilesDir(null), "watchdog-incidents.log")
            f.appendText(buildString {
                append("\n==== incident #$n  $kind  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ====\n")
                append("$detail\n")
                append("recent daemon errors (${lines.size}):\n")
                lines.takeLast(120).forEach { append("  $it\n") }
            })
        }
    }

    /** [fixedId] pins a notification to ONE slot that updates in place — used for the repeating
     *  uniform-wedge so a night of incidents is one evolving notification, not a rotation that
     *  overwrites the shade's history 20 IDs at a time (2026-07-25). */
    private fun notifyUser(c: Context, text: String, fixedId: Int = -1) {
        runCatching {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "自動回復", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(if (fixedId >= 0) fixedId else NOTIF_ID_BASE + (incidentSeq % 20),
                NotificationCompat.Builder(c, NOTIF_CHANNEL)
                    .setSmallIcon(cx.ring.R.drawable.ic_ring_logo_white)
                    .setContentTitle(c.getString(cx.ring.R.string.notif_recover_title))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build())
        }
    }

    /** DHT-proxy state machine. OFF (full DHT) when charging (battery is free) or riding out a
     *  recent wedge; ON (push / battery) only on battery and stable. Applies only on a change. */
    private fun applyProxyState(c: Context, accounts: AccountService) {
        val t = now()
        // Restricted network (UDP blocked): the full DHT is UDP — turning the proxy off there
        // would kill the only working signaling path, so pin proxy ON until UDP egress is back.
        val restricted = UiPrefs.isRestrictedNet(c)
        // Full-DHT mode (the robust default, 2026-07-22): the proxy stays OFF as the standing state — no
        // single proxy link to wedge. Restricted-network is the SOLE exception (UDP blocked → the full DHT
        // is deaf → pin proxy ON so TURN/relay carries traffic). No charging/wedge juggling here.
        if (UiPrefs.isFullDhtMode(c)) {
            val onNow = accounts.getAccounts().any { it.isJami && it.isDhtProxyEnabled }
            if (restricted && !onNow) {
                log(c, "DHT proxy ON — pinned (restricted network, UDP blocked)")
                accounts.setProxyEnabled(true)
            } else if (!restricted && onNow) {
                if (proxyOffSinceMs == 0L) proxyOffSinceMs = t
                log(c, "DHT proxy OFF — full-DHT mode (standing state)")
                accounts.setProxyEnabled(false)
            }
            return
        }
        // "Full DHT on while charging" (default on): charging forces full DHT (battery is free);
        // switching the toggle off keeps the proxy's low-data profile even on the charger
        // (2026-07-25).
        val offCharging = isCharging(c) && UiPrefs.isFullDhtWhileCharging(c)
        // The wedge linger applies ONLY to proxy-implicated wedges (2026-07-26). It used to fire on
        // every wedge, which is what kept the daemon on full DHT for 10 of every 18 minutes while
        // the hexagon still said "proxy" — the mode=proxy(actual:fullDHT) split, and the reason
        // Account settings → Advanced disagreed with the search bar.
        val desiredOff = !restricted && (offCharging || (recentWedge(t) && wedgeProxyImplicated))
        val currentlyOn = accounts.getAccounts().any { it.isJami && it.isDhtProxyEnabled }
        if (desiredOff && currentlyOn) {
            // Stable key (not prose): the recovery log stays English, the UI localises it.
            proxyOffReason = if (offCharging) REASON_CHARGING else REASON_WEDGE
            if (proxyOffSinceMs == 0L) proxyOffSinceMs = t
            log(c, "DHT proxy OFF — full DHT (${if (offCharging) "charging" else "recent proxy-implicated wedge"})")
            accounts.setProxyEnabled(false)
        } else if (!desiredOff && !currentlyOn) {
            // Startup hold: never switch the proxy ON before this process has seen at
            // least one healthy check. A fresh process forgets the wedge-linger, and
            // flipping the proxy on at boot put a poisoned cached proxy endpoint back
            // into the connect path (2026-07-20: cost ~4 min of 0/4 after an update).
            // The accounts keep whatever state the daemon persisted until we are healthy.
            if (firstHealthyMs == 0L && !restricted) {
                log(c, "proxy ON deferred — startup hold until first healthy check")
                return
            }
            val offFor = if (proxyOffSinceMs != 0L) (t - proxyOffSinceMs) / 1000 else 0L
            proxyOffSinceMs = 0L
            // Name the REAL reason (2026-07-26). This line always read "back on battery, stable"
            // even when the phone had never been on a charger and it was the wedge linger expiring —
            // which made the 18-minute limit cycle read like charger noise in the log.
            log(c, if (restricted) "DHT proxy ON — pinned (restricted network, UDP blocked)"
                else "DHT proxy ON — ${if (proxyOffReason == REASON_CHARGING) "charging" else "wedge linger"} cleared (was off ${offFor}s)")
            accounts.setProxyEnabled(true)
        }
    }

    /** Post-recovery backfill (2026-07-23). A message that arrived while an account was deaf is NOT
     *  redelivered by re-register + presence re-arm: swarm delivery is sender-initiated, the sender
     *  only retries when it notices us again, and "healthy" here is presence-based — so a pending
     *  message can sit through several healthy windows until the sender happens to retry or the app
     *  restarts (observed: sent 17:10, delivered 17:35 only by a reinstall, through two "recovered —
     *  4/4 healthy" windows). connectivityChanged() is the daemon's own network-change signal: every
     *  account re-evaluates connectivity and each conversation's swarm manager re-maintains its
     *  buckets, re-opening device channels — and an opened channel pulls pending commits from the
     *  peer, receiver-initiated. Fire it a moment AFTER each recovery (let the re-register land) and
     *  on a deaf→alive transition; debounced so a wedge storm doesn't churn connections continuously. */
    private fun scheduleBackfill(c: Context, accounts: AccountService, reason: String) {
        val t = now()
        if (t - lastBackfillMs < BACKFILL_DEBOUNCE_MS) return
        // Hourly cap: connectivityChanged makes every conversation's swarm re-maintain its buckets
        // and re-open device channels — each nudge generates fresh ICE offers to every device of
        // every contact. A recover storm nudging every few minutes was a top junk-value generator
        // on our own DHT keys (2026-07-25). 6/h is plenty for real recoveries.
        if (t - backfillHourStartMs > 60 * 60_000L) { backfillHourStartMs = t; backfillCountHour = 0 }
        if (backfillCountHour >= 6) { log(c, "backfill sync SKIPPED ($reason) — hourly cap reached"); return }
        backfillCountHour++
        lastBackfillMs = t
        handler.postDelayed({
            log(c, "backfill sync — connectivityChanged ($reason)")
            accounts.nudgeConnectivity()
        }, BACKFILL_DELAY_MS)
    }

    /** Full recover: drop to the full DHT, re-register so the stuck backlog flushes, and linger off (window
     *  grows on repeats). For a real proxy wedge or the hard reset. */
    private fun fullRecover(c: Context, accounts: AccountService) {
        val t = now()
        wedgeStrikes = if (recentWedge(t)) (wedgeStrikes + 1).coerceAtMost(5) else 0
        lastWedgeMs = t
        // Repeat wedge in push mode → rotate the FCM token (throttled). Every churn generation
        // leaves stale server-side proxy subscriptions pushing to the OLD token forever (measured
        // 2–3 pushes/s + a 600 KB standing FCM Recv-Q, 2026-07-25); a fresh token orphans them at
        // Google's side instead of our battery. No-op on flavors without FCM.
        if (wedgeStrikes >= 1 && InboundEvidence.pushMode && t - lastTokenRotateMs > TOKEN_ROTATE_MIN_GAP_MS) {
            lastTokenRotateMs = t
            log(c, "repeat wedge in push mode → rotating the push token (stale server subscriptions)")
            runCatching { cx.ring.application.JamiApplication.instance?.rotatePushToken() }
        }
        recovering = true
        lastRecoverMs = t
        lastAnyConnectedMs = t
        if (UiPrefs.isRestrictedNet(c)) {
            // UDP is blocked here — the full DHT would be deaf. Keep the proxy pinned ON and
            // only re-register; TURN relays carry what they can.
            log(c, "→ restricted network: proxy stays ON; re-registering only")
            accounts.forceReconnectAllAccounts()
            scheduleBackfill(c, accounts, "full recover, restricted")
            return
        }
        // Only pay for the proxy teardown when the proxy is the suspect (2026-07-26). Otherwise
        // re-register and re-arm presence with the subscriptions left standing: same remedy for a
        // stuck receive path, none of the full-value-set re-download that made every recovery cost
        // megabytes. wedgeProxyImplicated also scopes applyProxyState's 10-min linger, so a
        // non-proxy wedge no longer drags the daemon onto full DHT behind the user's chosen mode.
        wedgeProxyImplicated = proxyImplicated(accounts, t)
        if (!wedgeProxyImplicated) {
            val ago = if (PushEvidence.lastRealPushMs == 0L) "n/a" else "${(t - PushEvidence.lastRealPushMs) / 1000}s"
            log(c, "→ recovering WITHOUT touching the proxy (real push $ago ago — the proxy leg delivers); re-registering + presence re-arm")
            accounts.forceReconnectAllAccounts()
            resubscribeAllPresence(accounts)
            scheduleBackfill(c, accounts, "full recover, proxy kept")
            return
        }
        if (proxyOffSinceMs == 0L) proxyOffSinceMs = t
        log(c, "→ DHT proxy OFF (full DHT); re-registering in ${REREGISTER_DELAY_MS / 1000}s to flush the backlog; lingering off ~${wedgeWindow() / 60_000}m")
        accounts.setProxyEnabled(false)
        handler.postDelayed({
            accounts.forceReconnectAllAccounts()
            resubscribeAllPresence(accounts)   // re-arm presence listens (else dots stay red)
            log(c, "↻ re-registered all accounts on full DHT")
            scheduleBackfill(c, accounts, "full recover")
        }, REREGISTER_DELAY_MS)
    }

    /** Light recover: re-register only, proxy stays on. For when the proxy is healthy (a single stuck
     *  link) — no battery cost, no off-window. */
    private fun lightRecover(c: Context, accounts: AccountService) {
        recovering = true
        lastRecoverMs = now()
        log(c, "light recover — re-register on the current proxy (stays on)")
        accounts.forceReconnectAllAccounts()
        resubscribeAllPresence(accounts)
        scheduleBackfill(c, accounts, "light recover")
    }

    /** Re-arm every registered account's presence listens after a global recover. */
    private fun resubscribeAllPresence(accounts: AccountService) {
        for (acc in accounts.getAccounts()) if (acc.isJami && acc.isRegistered)
            accounts.resubscribeAccountPresence(acc.accountId)
    }

    /** Automatic smart recover (error-storm path): light when all healthy, else full. Used by the
     *  watchdog itself, not the manual button — a human who presses Recover wants the strong fix. */
    fun manualRecover(c: Context, accounts: AccountService) {
        if (allHealthy(accounts)) { log(c, "smart recover — all accounts healthy → light"); lightRecover(c, accounts) }
        else { log(c, "smart recover — partial/zero healthy → full"); fullRecover(c, accounts) }
    }

    /** Hard reset (automatic ×2-storm escalation): always full DHT + re-register. */
    fun hardReset(c: Context, accounts: AccountService) {
        log(c, "HARD reset — full DHT + re-register")
        fullRecover(c, accounts)
    }

    /** The one manual Recover (flash tap / dashboard button): always the strong fix — full DHT +
     *  re-register + presence re-arm. No smart/light distinction (that's for the automatic path). */
    fun recoverNow(c: Context, accounts: AccountService) {
        log(c, "manual Recover — full DHT + re-register + presence re-arm")
        fullRecover(c, accounts)
    }

    /** Called right after the ⬡ mode toggle. Verify the NEW mode actually receives: probe once the
     *  switch has had a moment to settle; no real inbound inside the window → nudge every account
     *  (re-register + presence re-arm — NOT a mode flip: the user just chose this mode deliberately)
     *  and notify. Catches a deaf proxy within ~90 s of the tap instead of drifting into the
     *  suspicion path (2026-07-23: a 05:00 proxy switch sat deaf until 05:05+). */
    fun onDhtModeSwitched(c: Context, accounts: AccountService) {
        val full = UiPrefs.isFullDhtMode(c)
        val mode = if (full) "full DHT" else "proxy"
        // Keep the proxy-off duration clock honest across DELIBERATE mode switches too — it used to
        // track only the watchdog's own linger windows, so "recovered (22877s on full DHT)" lied.
        proxyOffSinceMs = if (full) now() else 0L
        log(c, "mode switched → $mode — verification probe in ${MODE_SWITCH_PROBE_DELAY_MS / 1000}s")
        handler.postDelayed({
            val baseline = InboundEvidence.lastMs
            resubscribeAllPresence(accounts)
            handler.postDelayed({
                if (InboundEvidence.lastMs > baseline) {
                    log(c, "mode-switch probe answered (${InboundEvidence.lastKind}) — $mode receiving fine")
                } else {
                    writeIncident(c, "mode-switch-deaf", "no real inbound ${UNIFORM_PROBE_VERDICT_MS / 1000}s after switching to $mode — re-registering all accounts", LogStormMonitor.recentLines())
                    notifyUser(c, c.getString(cx.ring.R.string.notif_mode_deaf, stamp()))
                    log(c, "mode-switch probe UNANSWERED — re-register + presence re-arm on the chosen mode")
                    recovering = true; lastRecoverMs = now()
                    accounts.forceReconnectAllAccounts()
                    resubscribeAllPresence(accounts)
                    scheduleBackfill(c, accounts, "mode-switch recover")
                }
            }, UNIFORM_PROBE_VERDICT_MS)
        }, MODE_SWITCH_PROBE_DELAY_MS)
    }

    /** Manual inbound test (dashboard button — "did anything real answer within 60 s?"). Re-subscribes
     *  presence on every account and reports the verdict; purely observational, recovery stays the
     *  user's decision. Returns false when a test is already running. */
    fun startManualProbe(c: Context, accounts: AccountService, onVerdict: (answered: Boolean, kind: String) -> Unit): Boolean {
        if (manualProbeInFlight) return false
        manualProbeInFlight = true
        val baseline = InboundEvidence.lastMs
        log(c, "manual inbound test — presence re-arm, verdict in ${PROBE_VERDICT_MS / 1000}s")
        resubscribeAllPresence(accounts)
        handler.postDelayed({
            manualProbeInFlight = false
            val answered = InboundEvidence.lastMs > baseline
            log(c, if (answered) "manual inbound test: answered (${InboundEvidence.lastKind})"
                else "manual inbound test: NO answer in ${PROBE_VERDICT_MS / 1000}s — receive path suspect")
            onVerdict(answered, InboundEvidence.lastKind)
        }, PROBE_VERDICT_MS)
        return true
    }

    /** Recover ONE account (dashboard per-row flash): re-register + presence re-arm for that account,
     *  leaving the healthy ones untouched. */
    fun recoverAccount(c: Context, accounts: AccountService, accountId: String) {
        recovering = true; lastRecoverMs = now()
        log(c, "manual recover — acct ${accountId.take(6)}: re-register + presence re-arm")
        accounts.forceReconnectAccount(accountId)
        accounts.resubscribeAccountPresence(accountId)
    }

    /** Lightning-icon state (proxy on / auto-off — the pin is gone). */
    fun proxyState(c: Context, accounts: AccountService): ProxyUi =
        if (accounts.getAccounts().any { it.isJami && it.isDhtProxyEnabled }) ProxyUi.ON else ProxyUi.OFF_AUTO

    /** Per-account health snapshot for the dashboard. `connected` = has a live peer link now;
     *  `deaf` = no inbound evidence past DEAF_LIMIT_MS; `healthy` = registered and not deaf. */
    data class AcctHealth(val id: String, val name: String, val registered: Boolean,
                          val connected: Boolean, val quietMs: Long, val deaf: Boolean) {
        val healthy: Boolean get() = registered && !deaf
    }
    fun accountHealthList(accounts: AccountService): List<AcctHealth> =
        accounts.getAccounts().filter { it.isJami }.map { a ->
            val reg = a.isRegistered
            val conn = reg && accounts.accountConnectionSnapshot(a.accountId).first
            val quiet = InboundEvidence.quietMs(a.accountId)
            val name = a.alias?.takeIf { it.isNotBlank() }
                ?: a.registeredName.takeIf { it.isNotBlank() }
                ?: a.displayname.takeIf { it.isNotBlank() }
                ?: a.accountId.take(8)
            AcctHealth(a.accountId, name, reg, conn, quiet, reg && quiet >= DEAF_LIMIT_MS)
        }

    /** True while a recover is settling — dashboard/flash "recovering" indicator. */
    fun proxyOn(accounts: AccountService): Boolean =
        accounts.getAccounts().any { it.isJami && it.isDhtProxyEnabled }

    /** Heal accounts left persistently DISABLED by a re-register nudge the previous process never
     *  finished (kill/crash/install inside the 1.5-s sendRegister(false)→(true) window — exactly
     *  what disabled two accounts on the 2026-07-23 +52 install). A ledger marker surviving into
     *  this process is proof the disable was OURS, never the user's, so re-enabling is always
     *  correct. Runs once, on the first tick after the account list has loaded. */
    private fun healInterruptedReregisters(c: Context, accounts: AccountService) {
        if (ledgerHealed) return
        val loaded = accounts.getAccounts()
        if (loaded.isEmpty()) return   // account list not loaded yet — retry next tick
        ledgerHealed = true
        for (id in UiPrefs.getReregisterInFlight(c)) {
            val a = loaded.firstOrNull { it.accountId == id }
            when {
                a == null ->
                    log(c, "re-register ledger: unknown account ${id.take(6)} — marker cleared")
                !a.isEnabled -> {
                    writeIncident(c, "reregister-heal",
                        "account ${id.take(8)} was left DISABLED by an interrupted re-register (process died mid-nudge) — auto re-enabled",
                        emptyList())
                    log(c, "re-register ledger: acct ${id.take(6)} left DISABLED by an interrupted nudge — re-enabling")
                    accounts.forceReconnectAccount(id)   // its !isEnabled branch re-enables + registers
                }
                else ->
                    log(c, "re-register ledger: stale marker for ${id.take(6)} (already enabled) — cleared")
            }
            UiPrefs.setReregisterInFlight(c, id, false)
        }
    }

    /** Hub-icon transitional state (blue): a recovery is settling, or the adaptive streaming
     *  fallback is riding out a dead push leg — functioning, but not in the chosen steady state. */
    fun hubTransitional(): Boolean = recovering || noPushAdaptive

    /** True while adaptive no-push is active — the UnifiedPush token setter checks this so a late
     *  distributor registration does not silently re-enter push mode behind the watchdog's back
     *  (2026-07-23: exactly that race put the accounts back on the dead push leg minutes after
     *  adaptive mode had escaped it). */
    fun isNoPushAdaptive(): Boolean = noPushAdaptive

    /** Probe-VERIFIED deafness for one account: a 60-s presence-re-arm probe went unanswered (the
     *  strike count survives until real inbound clears it). This — never the bare quiet clock — is
     *  the metric the UI surfaces trust (2026-07-23: the bare clock false-positived every ~3 min on
     *  a quiet evening; "quiet" and "deaf" are different states, and only the probe tells them apart). */
    fun accountVerifiedDeaf(accountId: String): Boolean = (acctStates[accountId]?.strikes ?: 0) > 0

    /** A verification probe is currently in flight for this account (suspected, not yet judged). */
    fun accountProbing(accountId: String): Boolean = acctStates[accountId]?.probeInFlight == true

    /** For the main-bar dot: any registered account with VERIFIED deafness (probe unanswered) —
     *  the honest "something is wrong" signal, immune to quiet-evening false positives. */
    fun anyDeaf(accounts: AccountService): Boolean =
        accounts.getAccounts().any { it.isJami && it.isRegistered && accountVerifiedDeaf(it.accountId) }

    /** True when the last network egress check came back DOWN and is still fresh. */
    fun networkDown(): Boolean = !netAlive && netVerdictMs != 0L && now() - netVerdictMs < NET_VERDICT_TTL_MS

    // ---- Tier 2: test-swarm canary ----------------------------------------------------------
    private fun canaryTick(c: Context, accounts: AccountService) {
        if (canaryInFlight) return
        val accId = UiPrefs.getRecoveryTestAccount(c)
        val swarmId = UiPrefs.getRecoveryTestSwarm(c)
        val acc = accounts.getAccount(accId) ?: return
        val conv = acc.getSwarm(swarmId) ?: run { log(c, "canary: test swarm not found on account"); return }
        sendCanary(c, accounts, accId, swarmId, conv) { delivered ->
            if (delivered) onPingOk(c, accounts)
            else { log(c, "canary stale → recovering (full DHT + re-register)"); fullRecover(c, accounts) }
        }
    }

    /** Send a datetime ping into the test swarm, then verify THAT ping is delivered. One confirm
     *  recheck before declaring stale, so a slow-but-fine delivery doesn't trigger a needless recovery. */
    private fun sendCanary(c: Context, accounts: AccountService, accId: String, swarmId: String,
                           conv: net.jami.model.Conversation, onResult: (Boolean) -> Unit) {
        canaryInFlight = true
        accounts.setAccountActive(accId, true)  // a deactivated account can't send the ping
        val marker = "$CANARY_MARK ${stamp()}"
        accounts.sendConversationMessage(accId, Uri.fromString("swarm:$swarmId"), marker, null)
        handler.postDelayed({
            if (pingDelivered(conv, marker)) { canaryInFlight = false; onResult(true) }
            else handler.postDelayed({
                canaryInFlight = false
                onResult(pingDelivered(conv, marker))
            }, CANARY_RECHECK_MS)
        }, CANARY_TIMEOUT_MS)
    }

    /** A delivered canary. Always logged. If we were mid-recovery, mark recovered and post
     *  "✓ recovered HH:MM:SS" into the swarm; otherwise a plain "ping ok". */
    private fun onPingOk(c: Context, accounts: AccountService) {
        if (recovering) {
            recovering = false
            val ts = stamp()
            log(c, "recovered $ts")
            if (UiPrefs.isCanaryConfigured(c)) {
                val accId = UiPrefs.getRecoveryTestAccount(c)
                val swarmId = UiPrefs.getRecoveryTestSwarm(c)
                accounts.sendConversationMessage(accId, Uri.fromString("swarm:$swarmId"), "$OK_MARK $ts", null)
            }
        } else {
            log(c, "ping ok")
        }
    }

    // ---- Tier 1: heuristic — local-health triggers + presence-triaged stuck messages ---------
    /** A stuck outgoing message alone is NEVER local-fault evidence (the recipient may simply be
     *  on an airplane — this exact false positive caused a 3-minute recover loop on 2026-07-20).
     *  Triage by recipient presence: CONNECTED-and-still-no-ACK is damning; OFFLINE is benign;
     *  AVAILABLE only accelerates the deafness clock. Breadth (≥2 distinct non-offline
     *  recipients stuck, with inbound also quiet) counts as local evidence too. Every wedge
     *  recovery goes through a fingerprint ledger so unchanged evidence can never re-trigger. */
    private fun heuristicTick(c: Context, accounts: AccountService) {
        val t = now()
        // "Connected" (a live PEER link) under-counts idle-but-healthy accounts (an account with no
        // active conversation legitimately has none) — that read as 3/4 while all four dots were
        // yellow (2026-07-20). Health = REGISTERED and not deaf (recent inbound evidence), matching
        // the dots and the deaf-detector. conn is kept only for the granular per-account log token.
        var reg = 0; var conn = 0; var healthy = 0
        val stuck = ArrayList<ConnectionHealth.StuckMsg>()
        val tokens = StringBuilder()
        // Same-device recipients (another of MY registered accounts) are reachable BY DEFINITION —
        // they never publish presence to each other, so they read stale-OFFLINE and their stuck
        // messages were triaged "benign offline" forever (2026-07-24: 6 msgs stuck to a same-device
        // sibling, no recovery). Upgrade them to CONNECTED so the wedge ledger treats them as the
        // local-fault evidence they are (the classic inter-account sync stall).
        val sameDeviceUris = accounts.getAccounts()
            .filter { it.isJami && it.isRegistered }
            .mapNotNull { it.uri?.removePrefix("jami:")?.removePrefix("ring:")?.takeIf(String::isNotEmpty) }
            .toSet()
        for (acc in accounts.getAccounts()) {
            if (!acc.isJami || !acc.isRegistered) continue
            reg++
            val (connected, _) = accounts.accountConnectionSnapshot(acc.accountId)
            if (connected) conn++
            val quiet = InboundEvidence.quietMs(acc.accountId)
            val deaf = quiet >= DEAF_LIMIT_MS
            if (!deaf) healthy++
            tokens.append(acc.accountId.take(6))
                .append(if (deaf) ":⚠${quiet / 60_000}m" else if (connected) ":✓" else ":✓idle")
                .append(' ')
            stuck += ConnectionHealth.accountStuckMessages(acc, t).map { m ->
                if (m.memberUri.removePrefix("jami:").removePrefix("ring:") in sameDeviceUris)
                    m.copy(presence = Contact.PresenceStatus.CONNECTED) else m
            }
        }
        if (reg == 0) {
            // Cold-start stall: enabled accounts that never reach REGISTERED are invisible to the
            // rest of this heuristic (it counts only registered accounts), so a flaky proxy at boot
            // left the app 0-connected and UNWATCHED for minutes (2026-07-20). After a short grace,
            // drop to the full DHT and re-register — the reliable fast path (a full-DHT recover
            // brought 4/4 up in seconds tonight while the proxy churned). recentWedge gates re-fire.
            val enabled = accounts.getAccounts().count { it.isJami && it.isEnabled }
            val sinceStart = t - processStartMs
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (enabled > 0 && cm?.activeNetwork != null && sinceStart > STARTUP_STALL_GRACE_MS && !recentWedge(t)) {
                log(c, "startup stall — $enabled account(s) enabled, none registered ${sinceStart / 1000}s → full-DHT re-register")
                fullRecover(c, accounts)
            } else {
                log(c, "base check — no registered accounts ($enabled enabled, ${sinceStart / 1000}s)")
            }
            return
        }

        val positive = stuck.filter { it.presence == net.jami.model.Contact.PresenceStatus.CONNECTED }
        val nonOffline = stuck.filter { it.presence != net.jami.model.Contact.PresenceStatus.OFFLINE }
        val offlineCount = stuck.size - nonOffline.size
        stuckAmbiguous = nonOffline.any { it.presence == net.jami.model.Contact.PresenceStatus.AVAILABLE }
        val breadth = nonOffline.map { it.memberUri }.toSet().size >= 2 &&
            t - InboundEvidence.lastMs > BREADTH_QUIET_MS
        val wedgeEvidence = positive.ifEmpty { if (breadth) nonOffline else emptyList() }
        if (stuck.isEmpty() && lastWedgeFp != null) {
            lastWedgeFp = null; wedgeEscalated = false; wedgeStandDownUntil = 0L
            log(c, "stuck messages cleared — wedge ledger reset")
        }

        // Healthy (registered + not deaf) is the real "is this account up" signal; an idle account
        // with no live peer link is still healthy. The wedge fires only when NO account is healthy.
        if (healthy > 0) {
            if (firstHealthyMs == 0L) firstHealthyMs = t
            lastAnyConnectedMs = t
            // Give-up counter: reset only when health has OUTLIVED the wedge cycle, not on the
            // transient post-recover blip — every recover produced a few healthy minutes, reset
            // the counter, and the 2-strike stand-down never engaged (56 wedges, 2026-07-25).
            if (uniformProbeFails > 0 && t - lastWedgeMs > wedgeWindow() + 5 * 60_000L)
                uniformProbeFails = 0
            if (recovering && wedgeEvidence.isEmpty()) {
                recovering = false
                // Only claim time-on-full-DHT when the proxy-off clock is actually running — the
                // bare "(0s on full DHT)" / stale-clock variants were misleading (2026-07-24).
                val off = if (proxyOffSinceMs != 0L) (t - proxyOffSinceMs) / 1000 else 0L
                log(c, "recovered — $healthy/$reg healthy" + (if (off > 0) " (${off}s on full DHT)" else ""))
            }
        } else if (lastAnyConnectedMs == 0L) lastAnyConnectedMs = t
        val summary = tokens.toString().trim()
        val stuckSuffix = if (stuck.isNotEmpty()) "; ${stuck.size} undelivered (${offlineCount} to offline — benign)" else ""

        // A stuck message to a REACHABLE contact is genuine wedge evidence regardless of the health count.
        if (wedgeEvidence.isNotEmpty()) { wedgeWithLedger(c, accounts, wedgeEvidence, t); return }
        if (healthy > 0) { log(c, "base check ok — $healthy/$reg healthy [$summary]$stuckSuffix"); return }

        // healthy == 0. Reachability-max (2026-07-21): on an idle network real inbound is naturally
        // sparse now that presence echoes no longer self-answer, so 0-healthy is NOT proof of a wedge.
        // The old zeroStale path full-recovered here — unregistering every account and blacking out
        // new-message notifications for 15 s on a WORKING connection, every ~6 min (the soak churn).
        // A recovery of a healthy account is pure harm: the daemon re-announces presence on its own,
        // so re-registering adds nothing but a reachability gap. Consult the REAL network instead:
        //   · egress DOWN → genuine outage; perAccountTick re-registers (may prompt a network switch).
        //     Nothing to do from here — and never toggle the proxy on a dead network.
        //   · not down, 0-healthy past a long fuse → a true SILENT wedge is possible → one strong
        //     recover, gated by recentWedge() so it can never loop.
        //   · not down, within the fuse → just quiet: re-arm presence listens cheaply (no re-register,
        //     no proxy toggle, no notification blackout) to keep the dots fresh. Never the hammer.
        ensureNetVerdict(c) {}   // refresh async so networkDown() can flip promptly on a real outage
        // Honest staleness: only REAL inbound counts — touch()/probe marks never advance the global
        // InboundEvidence clock, so fabricated idle-health can no longer hold the last-resort fuse
        // open forever (2026-07-23: four wedged accounts cycled "✓idle" while the fuse clock reset).
        val realQuiet = t - InboundEvidence.lastMs
        // No startup quartering any more (2026-07-26). A fresh process on a quiet four-account phone
        // hit the 5-minute fuse almost every time, and the fuse used to recover BLIND — that is what
        // fired at 10:42, 10:49, 10:53, 13:23 and 14:39 on plain full DHT, and after every install.
        // The fuse now only starts the probe, so it no longer needs to be cautious about its length.
        val silentFuse = SILENT_WEDGE_MS
        when {
            networkDown() ->
                log(c, "0/$reg healthy + network egress DOWN — per-account handler recovering [$summary]")
            realQuiet > silentFuse && !recentWedge(t) && uniformProbeFails < UNIFORM_PROBE_FAIL_CAP -> {
                // PROBE, never recover blind (2026-07-26). This branch used to call fullRecover()
                // straight off a quiet clock — "idle ≠ wedge" was already the stated intent of this
                // block, but the code hammered anyway. The probe's per-account verdict now decides,
                // exactly as the deafness path does.
                log(c, "silent ${realQuiet / 60_000}m, 0/$reg healthy on a working network — probing before any recover [$summary]")
                startUniformProbe(c, accounts, reg, summary)
            }
            t - lastIdleRearmMs > IDLE_REARM_MS -> {
                lastIdleRearmMs = t
                startUniformProbe(c, accounts, reg, summary)
            }
            else ->
                log(c, "base check — 0/$reg healthy, no real inbound ${realQuiet / 1000}s [$summary]")
        }
    }

    /** 0-healthy probe with a STRICTLY PER-ACCOUNT verdict (2026-07-23, rev 2). The cheap presence
     *  re-arm IS the probe; the verdict then judges each account by ITS OWN inbound. The previous
     *  version tested the GLOBAL clock and, on any single event, `touch()`ed every account — so one
     *  sibling's delivery receipt certified a 67-minute-dead account as "healthy" (the yellow-dot-while-
     *  dead bug 白い熎 caught). Now: an account counts as answered only if its own last-inbound advanced
     *  during the window; accounts still individually silent are the wedged ones and drive the recover,
     *  capped at [UNIFORM_PROBE_FAIL_CAP] consecutive misses so a genuinely dead-quiet night can't churn.
     *  No cross-account fabrication: nothing is marked healthy that didn't itself receive. */
    private fun startUniformProbe(c: Context, accounts: AccountService, reg: Int, summary: String) {
        if (uniformProbePending) return
        uniformProbePending = true
        val regd = accounts.getAccounts().filter { it.isJami && it.isRegistered }
        // Per-account last-inbound timestamp at probe start (lastMs = now − quietMs).
        val snap = regd.associate { it.accountId to (now() - InboundEvidence.quietMs(it.accountId)) }
        log(c, "0/$reg healthy, network not down — probe: presence re-arm, per-account verdict in ${UNIFORM_PROBE_VERDICT_MS / 1000}s [$summary]")
        resubscribeAllPresence(accounts)
        handler.postDelayed({
            uniformProbePending = false
            val t = now()
            if (networkDown()) {
                log(c, "probe: network egress DOWN — genuine outage, not recovering")
                return@postDelayed
            }
            // An account is answered iff ITS OWN inbound advanced past its snapshot — no global clock,
            // no cross-account marking. Presence echoes from the re-arm are already suppressed upstream.
            val silent = regd.filter { (t - InboundEvidence.quietMs(it.accountId)) <= (snap[it.accountId] ?: 0L) }
            val answered = regd.size - silent.size
            if (silent.isEmpty()) {
                uniformProbeFails = 0
                log(c, "probe: all ${regd.size} answered on their own inbound — receiving fine")
            } else if (silent.size == regd.size && PushEvidence.lastRealPushMs != 0L &&
                t - PushEvidence.lastRealPushMs <= UNIFORM_PROBE_VERDICT_MS + PROBE_PUSH_GRACE_MS) {
                // A push landed inside the probe window, so the shared proxy→app leg is demonstrably
                // delivering and a UNIFORM accusation ("every account is deaf") cannot be true — the
                // presence re-arm simply cannot answer in proxy mode (trackBuddy only listens on
                // refCount 0→1, and SK-SUBREFRESH's re-arm is suppressed while the client is busy).
                // Scoped to the uniform case on purpose: with only some accounts silent the push may
                // belong to one that answered, so a single-account wedge still stands.
                uniformProbeFails = 0
                log(c, "probe: ${silent.size}/${regd.size} silent, but a real push landed ${(t - PushEvidence.lastRealPushMs) / 1000}s ago — receive path alive, no recover")
            } else if (recentWedge(t)) {
                log(c, "probe: ${silent.size}/${regd.size} still silent (${silent.joinToString { it.accountId.take(6) }}) but inside the wedge linger — standing by")
            } else if (uniformProbeFails >= UNIFORM_PROBE_FAIL_CAP) {
                log(c, "probe: ${silent.size}/${regd.size} still silent — recovered ${uniformProbeFails}× without effect, standing down until real inbound returns")
            } else {
                uniformProbeFails++
                uniformWedgeSeq++
                val who = silent.joinToString { it.accountId.take(8) }
                writeIncident(c, "uniform-wedge", "${silent.size}/${regd.size} account(s) silent through a ${UNIFORM_PROBE_VERDICT_MS / 1000}s probe on a live network — receive path wedged ($who) (recover $uniformProbeFails/$UNIFORM_PROBE_FAIL_CAP)", LogStormMonitor.recentLines())
                val n = c.getString(cx.ring.R.string.notif_uniform_wedge, stamp())
                notifyUser(c, if (uniformWedgeSeq > 1) "×$uniformWedgeSeq · $n" else n, NOTIF_ID_BASE - 1)
                log(c, "probe UNANSWERED by ${silent.size}/${regd.size} ($answered answered) — wedge → strong recover ($uniformProbeFails/$UNIFORM_PROBE_FAIL_CAP)")
                maybeEnterAdaptiveOnStarvation(c, accounts, "uniform wedge")
                maybeProbePush(c, accounts, "uniform wedge")
                fullRecover(c, accounts)
            }
        }, UNIFORM_PROBE_VERDICT_MS)
    }

    /** Recover on wedge evidence — but only once per distinct evidence set: same fingerprint
     *  again → one hard-reset escalation → stand down (~60 m) with a single notification.
     *  Any NEW evidence (different message, different peer) re-arms immediately. */
    private fun wedgeWithLedger(c: Context, accounts: AccountService, evidence: List<ConnectionHealth.StuckMsg>, t: Long) {
        val fp = evidence.map { it.fingerprint }.sorted().joinToString("|")
        val who = evidence.joinToString { "${it.memberUri.takeLast(8)}(${it.presence})" }
        if (fp != lastWedgeFp) {
            lastWedgeFp = fp; wedgeEscalated = false; wedgeStandDownUntil = 0L
            log(c, "wedge: undelivered to reachable peer(s) $who — recovering")
            fullRecover(c, accounts)
            return
        }
        if (t < wedgeStandDownUntil) { log(c, "wedge unchanged ($who) — standing down"); return }
        if (!wedgeEscalated) {
            wedgeEscalated = true
            log(c, "wedge persists after recover ($who) → HARD reset (single escalation)")
            notifyUser(c, c.getString(cx.ring.R.string.notif_stuck_hard, stamp()))
            fullRecover(c, accounts)
            maybeDiagnoseTransport(c, accounts, "wedge persisted past recover")
        } else {
            wedgeStandDownUntil = t + WEDGE_STAND_DOWN_MS
            log(c, "wedge STILL unchanged ($who) — giving up for ${WEDGE_STAND_DOWN_MS / 60_000}m (recipient likely unreachable)")
            notifyUser(c, c.getString(cx.ring.R.string.notif_stuck_giveup, who, (WEDGE_STAND_DOWN_MS / 60_000).toInt()))
            maybeDiagnoseTransport(c, accounts, "wedge stand-down")
        }
    }

    /** Has our specific ping (matched by its marker text) been delivered? If the last event is NOT our
     *  ping — a newer message arrived (traffic is flowing) or it's incoming — treat as healthy, so a
     *  stale verdict only ever comes from our own, still-undelivered ping. */
    private fun pingDelivered(conv: net.jami.model.Conversation, marker: String): Boolean {
        val e: Interaction = conv.lastEvent ?: return true
        if (e.isIncoming) return true
        if (e.body != marker) return true
        // deliveredToPeer filters the sender's own statusMap entry — the raw check read one's own
        // DISPLAYED mark (set by merely viewing the conv) as "delivered" (2026-07-24).
        return ConnectionHealth.deliveredToPeer(conv, e)
    }
}
