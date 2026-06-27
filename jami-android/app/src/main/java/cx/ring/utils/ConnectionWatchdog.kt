/*
 *  shiroikuma.jami fork — online-recovery watchdog.
 *
 *  With DHT proxy ON, every connection-setup ICE exchange rides the single proxy link (the daemon
 *  publishes a PeerConnectionRequest with the ICE candidates via dht()->putEncrypted). When that link
 *  wedges, ICE never completes and external delivery strands (in + out) while the UI still says
 *  "connected". The fix is to drop to the resilient distributed DHT (proxy OFF), let it flush, then
 *  restore proxy. This watchdog detects the wedge and runs that recovery automatically.
 *
 *  Detection is two-tier:
 *   - Base (no user data): "nothing is connecting" — registered, attempts happening, but zero peers
 *     reach Connected for HEURISTIC_STALE_MS.
 *   - Opt-in (more robust): a test-swarm canary — a datetime ping to a dedicated swarm with an
 *     always-online account; if it isn't delivered within CANARY_TIMEOUT_MS, that's the wedge.
 *  When a test swarm + account are configured, the canary REPLACES the heuristic.
 */
package cx.ring.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import net.jami.model.Account
import net.jami.model.Uri
import net.jami.model.interaction.Interaction
import net.jami.services.AccountService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConnectionWatchdog {
    private const val TAG = "ConnWatchdog"
    private const val CANARY_TIMEOUT_MS = 20_000L          // first delivery check after this
    private const val CANARY_RECHECK_MS = 20_000L          // confirm recheck before declaring stale
    private const val HEURISTIC_STALE_MS = 2 * 60_000L     // nothing connected on ANY account this long = wedge
    private const val BACKOFF_WINDOW_MS = 2 * 60_000L      // re-wedge within this of a recovery → escalate
    private const val SETTLE_MS = 20_000L                  // full-DHT settle on normal recovery
    private const val SETTLE_BACKOFF_MS = 60_000L          // escalated settle when flapping
    private const val CANARY_MARK = "⌁"                    // canary ping marker
    private const val OK_MARK = "✓ recovered"

    private val handler = Handler(Looper.getMainLooper())
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Volatile private var lastAnyConnectedMs = 0L          // when any account last held a live connection
    @Volatile private var lastRecoveryMs = 0L
    @Volatile private var canaryInFlight = false
    @Volatile private var recovering = false

    private fun now() = System.currentTimeMillis()
    private fun stamp() = hms.format(Date())
    private fun log(c: Context, line: String) = UiPrefs.appendRecoveryLog(c, "${stamp()}  $line")

    /** One watchdog tick — call from the periodic driver (DRingService). Caller must ensure no active
     *  call (toggling proxy mid-call would drop it). */
    fun tick(c: Context, accounts: AccountService) {
        when {
            // Ping mode: the active canary — only when its test swarm + account are configured.
            UiPrefs.isRecoveryPingEnabled(c) && UiPrefs.isCanaryConfigured(c) -> canaryTick(c, accounts)
            // Base mode: the passive "nothing is connecting" heuristic.
            UiPrefs.isRecoveryBaseEnabled(c) -> heuristicTick(c, accounts)
            // Both off (or ping on but unconfigured) → no automatic recovery.
        }
    }

    /** Manual recovery (the Sync button). */
    fun manualRecover(c: Context, accounts: AccountService) {
        log(c, "manual Sync — recovering")
        runRecovery(c, accounts, manual = true)
    }

    // ---- Tier 2: test-swarm canary ----------------------------------------------------------
    private fun canaryTick(c: Context, accounts: AccountService) {
        if (canaryInFlight) return
        val accId = UiPrefs.getRecoveryTestAccount(c)
        val swarmId = UiPrefs.getRecoveryTestSwarm(c)
        val acc = accounts.getAccount(accId) ?: return
        val conv = acc.getSwarm(swarmId) ?: run { log(c, "canary: test swarm not found on account"); return }
        sendCanary(c, accounts, accId, swarmId, conv) { delivered ->
            if (delivered) onPingOk(c, accounts) else runRecovery(c, accounts, manual = false)
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

    /** A delivered canary. Always logged (so the log is the full record of pings). If we were
     *  mid-recovery, mark recovered and post "✓ recovered HH:MM:SS" into the swarm; otherwise a plain
     *  "ping ok". */
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

    // ---- Tier 1: passive "nothing is connecting" heuristic ----------------------------------
    private fun heuristicTick(c: Context, accounts: AccountService) {
        val t = now()
        var reg = 0; var conn = 0
        for (acc in accounts.getAccounts()) {
            if (!acc.isJami || !acc.isRegistered) continue
            reg++
            val (connected, _) = accounts.accountConnectionSnapshot(acc.accountId)
            if (connected) conn++
        }
        if (reg == 0) { log(c, "base check — no registered accounts"); return }
        // Healthy: at least one registered account holds a live connection. Heartbeat every tick.
        if (conn > 0) { lastAnyConnectedMs = t; log(c, "base check ok — $conn/$reg connected"); return }
        // Wedge: NOTHING connected on any registered account. Recover once that has held for the window.
        // No "attempts" requirement — a wedged daemon often stops attempting entirely, and that exact
        // state (0 connected, 0 attempting) is what previously slipped through and never recovered.
        if (lastAnyConnectedMs == 0L) lastAnyConnectedMs = t
        val downSec = (t - lastAnyConnectedMs) / 1000
        if (t - lastAnyConnectedMs > HEURISTIC_STALE_MS) {
            log(c, "base check — 0/$reg connected for ${downSec}s → recovering")
            runRecovery(c, accounts, manual = false)
        } else {
            log(c, "base check — 0/$reg connected, watching (${downSec}s)")
        }
    }

    // ---- recovery ---------------------------------------------------------------------------
    private fun runRecovery(c: Context, accounts: AccountService, manual: Boolean) {
        val t = now()
        val escalate = !manual && (t - lastRecoveryMs) < BACKOFF_WINDOW_MS
        val settle = if (escalate) SETTLE_BACKOFF_MS else SETTLE_MS
        lastRecoveryMs = t
        recovering = true
        lastAnyConnectedMs = t  // restart the heuristic clock across the recovery
        if (!manual) log(c, "stale — recovering (proxy off → ${settle / 1000}s → on${if (escalate) " · backoff" else ""})")
        accounts.recoverFromWedge(settle) {
            // After the cycle, fire a confirm canary so "recovered" is posted promptly (when configured).
            if (UiPrefs.isCanaryConfigured(c)) {
                val accId = UiPrefs.getRecoveryTestAccount(c)
                val swarmId = UiPrefs.getRecoveryTestSwarm(c)
                accounts.getAccount(accId)?.getSwarm(swarmId)?.let { conv ->
                    handler.postDelayed({
                        sendCanary(c, accounts, accId, swarmId, conv) { delivered ->
                            if (delivered) onPingOk(c, accounts) else log(c, "still stale after recovery")
                        }
                    }, 4_000L)
                }
            } else {
                log(c, "recovery cycle complete")
                recovering = false
            }
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
