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
import net.jami.model.Uri
import net.jami.model.interaction.Interaction
import net.jami.services.AccountService
import net.jami.utils.InboundEvidence
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
    private const val HEURISTIC_STALE_MS = 2 * 60_000L     // nothing connected on ANY account this long = wedge
    private const val WEDGE_WINDOW_BASE_MS = 5 * 60_000L   // proxy lingers off this long after a wedge…
    private const val WEDGE_WINDOW_MAX_MS = 30 * 60_000L   // …growing on repeat wedges, capped here
    private const val REREGISTER_DELAY_MS = 2_000L         // re-register after proxy-off takes effect
    private const val RECOVER_SETTLE_MS = 30_000L          // lightning shows "recovering" (blue) this long after a recover
    private const val CANARY_MARK = "⌁"                    // canary ping marker
    private const val OK_MARK = "✓ recovered"

    // ---- Reactive detectors (2026-07-20 outage class) ---------------------------------------
    private const val STORM_COOLDOWN_MS = 5 * 60_000L      // min spacing between storm reactions
    private const val STORM_HARD_WINDOW_MS = 10 * 60_000L  // second storm inside this → hard reset
    private const val STORM_EVIDENCE_VETO_MS = 90_000L     // fresh inbound evidence → benign teardown, ignore
    private const val DEAF_LIMIT_MS = 5 * 60_000L          // no inbound evidence this long = deafness SUSPECTED
    private const val DEAF_BACKOFF_MAX_MS = 60 * 60_000L   // escalating recheck backoff cap
    private const val PROBE_VERDICT_MS = 60_000L           // silent presence probe must be answered within this
    private const val PROBE_PEERS_PER_ACCOUNT = 3          // re-subscribe this many best-presence peers per account
    private const val NOTIF_CHANNEL = "shiroikuma_watchdog"
    private const val NOTIF_ID_BASE = 58_000

    @Volatile private var lastStormMs = 0L                 // when a storm last triggered a reaction
    @Volatile private var deafStrikes = 0                  // consecutive deafness triggers without evidence
    @Volatile private var nextDeafCheckMs = 0L             // backoff gate for the next deafness reaction
    @Volatile private var probeInFlight = false            // a silent presence probe is awaiting its verdict
    @Volatile private var probeStartEvidenceMs = 0L        // evidence clock value when the probe was sent
    @Volatile private var incidentSeq = 0

    // Restricted-network (hostile WiFi) mode: UDP egress blocked, TCP alive.
    private const val RESTRICTED_RETEST_MS = 2 * 60_000L   // re-test UDP egress this often while restricted
    private const val RESTRICTED_EXIT_PASSES = 2           // consecutive UDP passes required to exit (flap damping)
    private const val DIAG_THROTTLE_MS = 10 * 60_000L      // at most one transport diagnosis per this window
    @Volatile private var lastDiagMs = 0L
    @Volatile private var restrictedPasses = 0
    @Volatile private var retestScheduled = false

    // Wedge-recovery ledger: never recover twice for the SAME unchanged stuck evidence.
    private const val WEDGE_STAND_DOWN_MS = 60 * 60_000L   // give up on an unchanged fingerprint this long
    private const val BREADTH_QUIET_MS = 2 * 60_000L       // breadth trigger also needs this much inbound silence
    @Volatile private var lastWedgeFp: String? = null      // fingerprint of the evidence behind the last recover
    @Volatile private var wedgeEscalated = false           // the one hard-reset escalation already spent
    @Volatile private var wedgeStandDownUntil = 0L
    @Volatile private var stuckAmbiguous = false           // AVAILABLE-presence stuck exists → deafness clock runs at half limit

    private val handler = Handler(Looper.getMainLooper())
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Volatile private var lastAnyConnectedMs = 0L          // when any account last held a live connection
    @Volatile private var lastWedgeMs = 0L                 // when a wedge was last seen (linger-off clock)
    @Volatile private var proxyOffSinceMs = 0L             // when the proxy was last switched off (for log durations)
    @Volatile private var wedgeStrikes = 0                 // consecutive wedges → grows the linger window
    @Volatile private var lastRecoverMs = 0L               // when a recover (any kind) last started — drives the lightning blue
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

    /** Lightning shows "recovering" (blue) for a settle window after any recover starts. */
    fun isRecovering(): Boolean = lastRecoverMs != 0L && now() - lastRecoverMs < RECOVER_SETTLE_MS

    /** Broadly connected = ≥1 registered account holds a live connection → the proxy is fine, so a recover
     *  can be "light" (re-register only). 0-connected → proxy suspect → "full" (drop to full DHT). */
    private fun broadlyConnected(accounts: AccountService): Boolean =
        accounts.getAccounts().any { it.isJami && it.isRegistered && accounts.accountConnectionSnapshot(it.accountId).first }

    /** One watchdog tick — periodic driver (DRingService bg, HomeFragment fg). The caller skips this
     *  during an active call (toggling proxy mid-call would drop it). */
    fun tick(c: Context, accounts: AccountService) {
        val forced = UiPrefs.isProxyForcedOff(c)
        val active = UiPrefs.isRecoveryBaseEnabled(c) || UiPrefs.isRecoveryPingEnabled(c)
        if (!forced && !active) return   // recovery fully off and not forced → leave the proxy alone
        if (active) {
            if (UiPrefs.isRecoveryPingEnabled(c) && UiPrefs.isCanaryConfigured(c)) canaryTick(c, accounts)
            else heuristicTick(c, accounts)
            deafnessTick(c, accounts)
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
            val sinceEvidence = t - InboundEvidence.lastMs
            if (sinceEvidence < STORM_EVIDENCE_VETO_MS) {
                log(c, "error storm ignored — inbound evidence ${sinceEvidence / 1000}s ago (benign churn)")
                return@post
            }
            val secondStorm = lastStormMs != 0L && t - lastStormMs < STORM_HARD_WINDOW_MS
            lastStormMs = t
            writeIncident(c, "error-storm", "no inbound evidence for ${sinceEvidence / 1000}s", recentLines)
            if (secondStorm) {
                log(c, "error storm ×2 within ${STORM_HARD_WINDOW_MS / 60_000}m → HARD reset")
                notifyUser(c, "エラーストーム再発 → ハードリセット (${stamp()})")
                hardReset(c, accounts)
                maybeDiagnoseTransport(c, accounts, "repeat error storm")
            } else {
                log(c, "error storm detected → smart recover")
                notifyUser(c, "エラーストーム検出 → スマート回復 (${stamp()})")
                manualRecover(c, accounts)
            }
        }
    }

    // ---- Reactive detector 2: passive inbound-aliveness clock --------------------------------
    /** No peer-originated event (message, receipt, presence, typing, request, call) for
     *  DEAF_LIMIT_MS while the network is up = the daemon has gone deaf even if its own state
     *  says "connected" (stale NAT bindings — the 52-minute silent window). Escalates
     *  smart → hard, with a doubling backoff so a genuinely quiet network is not churned. */
    private fun deafnessTick(c: Context, accounts: AccountService) {
        val t = now()
        val quiet = t - InboundEvidence.lastMs
        // An ambiguous-presence stuck message corroborates: run the clock at half the limit.
        val limit = if (stuckAmbiguous) DEAF_LIMIT_MS / 2 else DEAF_LIMIT_MS
        if (quiet < limit) {
            if (deafStrikes > 0) log(c, "inbound evidence back (${InboundEvidence.lastKind}) — deafness cleared")
            deafStrikes = 0
            nextDeafCheckMs = 0L
            return
        }
        if (probeInFlight) return
        if (t < nextDeafCheckMs) return
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm?.activeNetwork == null) {
            log(c, "deaf ${quiet / 60_000}m but no network — waiting")
            return
        }
        if (accounts.getAccounts().none { it.isJami && it.isRegistered }) return

        // Silence alone proves nothing (presence announces are themselves ~10-15 min periodic;
        // a quiet evening looks identical to deafness). VERIFY first: a silent DHT presence
        // re-subscription — invisible to the peers, no message sent — must produce an announce
        // within PROBE_VERDICT_MS if we can hear the network. Only an unanswered probe recovers.
        probeInFlight = true
        probeStartEvidenceMs = InboundEvidence.lastMs
        val n = probePresence(accounts)
        if (n == 0) { probeInFlight = false; return }
        log(c, "quiet ${quiet / 60_000}m → probing presence of $n peer(s); verdict in ${PROBE_VERDICT_MS / 1000}s")
        handler.postDelayed({
            probeInFlight = false
            if (InboundEvidence.lastMs != probeStartEvidenceMs) {
                log(c, "probe answered (${InboundEvidence.lastKind}) — network audible, no recovery needed")
                deafStrikes = 0
                nextDeafCheckMs = 0L
            } else {
                deafnessConfirmed(c, accounts)
            }
        }, PROBE_VERDICT_MS)
    }

    /** Re-subscribe the best-presence peers on each account: the daemon re-arms its DHT presence
     *  listen and the current announce is redelivered — our "can we hear the network" ping that
     *  sends nothing to anyone. Returns the number of peers probed. */
    private fun probePresence(accounts: AccountService): Int {
        var n = 0
        for (acc in accounts.getAccounts()) {
            if (!acc.isJami || !acc.isRegistered) continue
            val peers = acc.getConversations().asSequence()
                .flatMap { it.contacts.asSequence() }
                .filter { !it.isUser }
                .distinctBy { it.uri.uri }
                .sortedByDescending { it.lastPresence.ordinal }
                .take(PROBE_PEERS_PER_ACCOUNT)
                .toList()
            for (p in peers) {
                accounts.subscribeBuddy(acc.accountId, p.uri.uri, true)
                n++
            }
        }
        return n
    }

    /** The probe went unanswered — verified deafness. The original escalation ladder applies. */
    private fun deafnessConfirmed(c: Context, accounts: AccountService) {
        val t = now()
        val quiet = t - InboundEvidence.lastMs
        deafStrikes++
        val backoff = (DEAF_LIMIT_MS shl (deafStrikes - 1).coerceAtMost(3)).coerceAtMost(DEAF_BACKOFF_MAX_MS)
        nextDeafCheckMs = t + backoff
        writeIncident(c, "deafness", "probe unanswered; no inbound evidence for ${quiet / 60_000}m (strike $deafStrikes)", LogStormMonitor.recentLines())
        if (deafStrikes >= 2) {
            log(c, "deaf ${quiet / 60_000}m, probe unanswered (strike $deafStrikes) → HARD reset; next check +${backoff / 60_000}m")
            notifyUser(c, "受信沈黙 ${quiet / 60_000}分・応答なし → ハードリセット (${stamp()})")
            hardReset(c, accounts)
            maybeDiagnoseTransport(c, accounts, "confirmed deafness")
        } else {
            log(c, "deaf ${quiet / 60_000}m, probe unanswered → smart recover; next check +${backoff / 60_000}m")
            notifyUser(c, "受信沈黙 ${quiet / 60_000}分・応答なし → スマート回復 (${stamp()})")
            manualRecover(c, accounts)
        }
    }

    // ---- Restricted-network (hostile WiFi) mode ----------------------------------------------
    /** Called after a recovery visibly failed to help (spent wedge ledger, repeat storm,
     *  confirmed deafness). Runs the two-second egress diagnosis on a background thread:
     *  UDP blocked while TCP works = the hostile-network trap → enter restricted mode.
     *  Throttled; no-op when already restricted. */
    fun maybeDiagnoseTransport(c: Context, accounts: AccountService, reason: String) {
        val t = now()
        if (UiPrefs.isRestrictedNet(c)) return
        if (t - lastDiagMs < DIAG_THROTTLE_MS) return
        lastDiagMs = t
        Thread({
            val udp = NetProbe.udpWorks()
            val tcp = NetProbe.tcpWorks()
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
        notifyUser(c, "制限ネットワーク検出（UDP遮断）→ プロキシ固定ON・中継モード (${stamp()})")
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
        notifyUser(c, "通常ネットワーク復帰 — UDP開通 (${stamp()})")
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

    private fun notifyUser(c: Context, text: String) {
        runCatching {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "自動回復", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(NOTIF_ID_BASE + (incidentSeq % 20),
                NotificationCompat.Builder(c, NOTIF_CHANNEL)
                    .setSmallIcon(cx.ring.R.drawable.ic_ring_logo_white)
                    .setContentTitle("白い熊 Jami 自動回復")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build())
        }
    }

    /** DHT-proxy state machine. OFF (full DHT) when forced, charging, or riding out a recent wedge;
     *  ON (push / battery) only on battery and stable. Applies only on a change, so no churn. */
    private fun applyProxyState(c: Context, accounts: AccountService) {
        val t = now()
        // Restricted network (UDP blocked): the full DHT is UDP — turning the proxy off there
        // would kill the only working signaling path. The pin overrides EVERYTHING, including
        // the manual forced-off, until UDP egress is verified back.
        val restricted = UiPrefs.isRestrictedNet(c)
        val offForced = UiPrefs.isProxyForcedOff(c)
        val offCharging = isCharging(c)
        val desiredOff = !restricted && (offForced || offCharging || recentWedge(t))
        val currentlyOn = accounts.getAccounts().any { it.isJami && it.isDhtProxyEnabled }
        if (desiredOff && currentlyOn) {
            val why = if (offForced) "forced" else if (offCharging) "charging" else "recent wedge"
            if (proxyOffSinceMs == 0L) proxyOffSinceMs = t
            log(c, "DHT proxy OFF — full DHT ($why)")
            accounts.setProxyEnabled(false)
        } else if (!desiredOff && !currentlyOn) {
            val offFor = if (proxyOffSinceMs != 0L) (t - proxyOffSinceMs) / 1000 else 0L
            proxyOffSinceMs = 0L
            log(c, if (restricted) "DHT proxy ON — pinned (restricted network, UDP blocked)"
                else "DHT proxy ON — back on battery, stable (was off ${offFor}s)")
            accounts.setProxyEnabled(true)
        }
    }

    /** Full recover: drop to the full DHT, re-register so the stuck backlog flushes, and linger off (window
     *  grows on repeats). For a real proxy wedge or the hard reset. */
    private fun fullRecover(c: Context, accounts: AccountService) {
        val t = now()
        wedgeStrikes = if (recentWedge(t)) (wedgeStrikes + 1).coerceAtMost(5) else 0
        lastWedgeMs = t
        recovering = true
        lastRecoverMs = t
        lastAnyConnectedMs = t
        if (UiPrefs.isRestrictedNet(c)) {
            // UDP is blocked here — the full DHT would be deaf. Keep the proxy pinned ON and
            // only re-register; TURN relays carry what they can.
            log(c, "→ restricted network: proxy stays ON; re-registering only")
            accounts.forceReconnectAllAccounts()
            return
        }
        if (proxyOffSinceMs == 0L) proxyOffSinceMs = t
        log(c, "→ DHT proxy OFF (full DHT); re-registering in ${REREGISTER_DELAY_MS / 1000}s to flush the backlog; lingering off ~${wedgeWindow() / 60_000}m")
        accounts.setProxyEnabled(false)
        handler.postDelayed({ accounts.forceReconnectAllAccounts(); log(c, "↻ re-registered all accounts on full DHT") }, REREGISTER_DELAY_MS)
    }

    /** Light recover: re-register only, proxy stays on. For when the proxy is healthy (a single stuck
     *  link) — no battery cost, no off-window. */
    private fun lightRecover(c: Context, accounts: AccountService) {
        recovering = true
        lastRecoverMs = now()
        log(c, "light recover — re-register on the current proxy (stays on)")
        accounts.forceReconnectAllAccounts()
    }

    /** Smart recover (lightning tap / dialog Recover / Sync now): light when the proxy looks good (broadly
     *  connected → just re-register), full when 0-connected (proxy suspect → drop to full DHT). */
    fun manualRecover(c: Context, accounts: AccountService) {
        if (broadlyConnected(accounts)) { log(c, "smart recover — proxy looks good → light"); lightRecover(c, accounts) }
        else { log(c, "smart recover — 0 connected → full"); fullRecover(c, accounts) }
    }

    /** Hard reset (lightning long-press): always proxy off + re-register, no matter the state. */
    fun hardReset(c: Context, accounts: AccountService) {
        log(c, "HARD reset — forced full DHT + re-register")
        fullRecover(c, accounts)
    }

    /** Toggle "force proxy off" — the Sync long-press pin. Returns the new forced state. */
    fun toggleForcedOff(c: Context, accounts: AccountService): Boolean {
        val next = !UiPrefs.isProxyForcedOff(c)
        UiPrefs.setProxyForcedOff(c, next)
        log(c, if (next) "proxy FORCED off (full DHT until you toggle back)" else "forced-off cleared (auto)")
        applyProxyState(c, accounts)
        return next
    }

    /** Lightning-icon state. */
    fun proxyState(c: Context, accounts: AccountService): ProxyUi = when {
        UiPrefs.isProxyForcedOff(c) -> ProxyUi.OFF_FORCED
        accounts.getAccounts().any { it.isJami && it.isDhtProxyEnabled } -> ProxyUi.ON
        else -> ProxyUi.OFF_AUTO
    }

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
        var reg = 0; var conn = 0
        val stuck = ArrayList<ConnectionHealth.StuckMsg>()
        for (acc in accounts.getAccounts()) {
            if (!acc.isJami || !acc.isRegistered) continue
            reg++
            val (connected, _) = accounts.accountConnectionSnapshot(acc.accountId)
            if (connected) conn++
            stuck += ConnectionHealth.accountStuckMessages(acc, t)
        }
        if (reg == 0) { log(c, "base check — no registered accounts"); return }

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

        if (conn > 0) {
            lastAnyConnectedMs = t
            if (recovering && wedgeEvidence.isEmpty()) { recovering = false; val off = if (proxyOffSinceMs != 0L) (t - proxyOffSinceMs) / 1000 else 0L; log(c, "recovered — $conn/$reg connected (${off}s on full DHT)") }
        } else if (lastAnyConnectedMs == 0L) lastAnyConnectedMs = t
        val zeroStale = conn == 0 && t - lastAnyConnectedMs > HEURISTIC_STALE_MS

        when {
            zeroStale -> { log(c, "wedge: 0/$reg connected — recovering"); fullRecover(c, accounts) }
            wedgeEvidence.isNotEmpty() -> wedgeWithLedger(c, accounts, wedgeEvidence, t)
            conn > 0 -> log(c, "base check ok — $conn/$reg connected" +
                if (stuck.isNotEmpty()) "; ${stuck.size} undelivered (${offlineCount} to offline peers — benign)" else "")
            else -> log(c, "base check — 0/$reg connected, watching (${(t - lastAnyConnectedMs) / 1000}s)")
        }
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
            notifyUser(c, "配信詰まり継続 → ハードリセット (${stamp()})")
            fullRecover(c, accounts)
            maybeDiagnoseTransport(c, accounts, "wedge persisted past recover")
        } else {
            wedgeStandDownUntil = t + WEDGE_STAND_DOWN_MS
            log(c, "wedge STILL unchanged ($who) — giving up for ${WEDGE_STAND_DOWN_MS / 60_000}m (recipient likely unreachable)")
            notifyUser(c, "配信不能 ($who) — 回復を${WEDGE_STAND_DOWN_MS / 60_000}分停止")
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
        return e.status == Interaction.InteractionStatus.SUCCESS ||
            e.status == Interaction.InteractionStatus.DISPLAYED ||
            e.statusMap.values.any { it == Interaction.MessageStates.SUCCESS || it == Interaction.MessageStates.DISPLAYED }
    }
}
