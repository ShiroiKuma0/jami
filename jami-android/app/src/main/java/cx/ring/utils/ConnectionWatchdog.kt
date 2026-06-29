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

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import net.jami.model.Uri
import net.jami.model.interaction.Interaction
import net.jami.services.AccountService
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
        }
        applyProxyState(c, accounts)
    }

    /** DHT-proxy state machine. OFF (full DHT) when forced, charging, or riding out a recent wedge;
     *  ON (push / battery) only on battery and stable. Applies only on a change, so no churn. */
    private fun applyProxyState(c: Context, accounts: AccountService) {
        val t = now()
        val offForced = UiPrefs.isProxyForcedOff(c)
        val offCharging = isCharging(c)
        val desiredOff = offForced || offCharging || recentWedge(t)
        val currentlyOn = accounts.getAccounts().any { it.isJami && it.isDhtProxyEnabled }
        if (desiredOff && currentlyOn) {
            val why = if (offForced) "forced" else if (offCharging) "charging" else "recent wedge"
            if (proxyOffSinceMs == 0L) proxyOffSinceMs = t
            log(c, "DHT proxy OFF — full DHT ($why)")
            accounts.setProxyEnabled(false)
        } else if (!desiredOff && !currentlyOn) {
            val offFor = if (proxyOffSinceMs != 0L) (t - proxyOffSinceMs) / 1000 else 0L
            proxyOffSinceMs = 0L
            log(c, "DHT proxy ON — back on battery, stable (was off ${offFor}s)")
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

    // ---- Tier 1: heuristic — 0-connected OR a stuck outgoing message -------------------------
    private fun heuristicTick(c: Context, accounts: AccountService) {
        val t = now()
        val myUris = accounts.getAccounts().mapNotNull { it.uri?.takeIf(String::isNotEmpty) }.toSet()
        var reg = 0; var conn = 0; var stuck = false
        for (acc in accounts.getAccounts()) {
            if (!acc.isJami || !acc.isRegistered) continue
            reg++
            val (connected, _) = accounts.accountConnectionSnapshot(acc.accountId)
            if (connected) conn++
            if (ConnectionHealth.accountStuckConvUris(acc, t, myUris).isNotEmpty()) stuck = true
        }
        if (reg == 0) { log(c, "base check — no registered accounts"); return }
        if (conn > 0) {
            lastAnyConnectedMs = t
            if (recovering && !stuck) { recovering = false; val off = if (proxyOffSinceMs != 0L) (t - proxyOffSinceMs) / 1000 else 0L; log(c, "recovered — $conn/$reg connected (${off}s on full DHT)") }
        } else if (lastAnyConnectedMs == 0L) lastAnyConnectedMs = t
        val zeroStale = conn == 0 && t - lastAnyConnectedMs > HEURISTIC_STALE_MS
        when {
            // Wedge: a stuck outgoing message (NOT_SYNCING) even while connected, or nothing connected.
            stuck || zeroStale ->
                { log(c, "wedge: ${if (stuck) "message stuck" else "0/$reg connected"} — recovering"); fullRecover(c, accounts) }
            conn > 0 -> log(c, "base check ok — $conn/$reg connected")
            else -> log(c, "base check — 0/$reg connected, watching (${(t - lastAnyConnectedMs) / 1000}s)")
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
