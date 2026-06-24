/*
 *  shiroikuma.jami fork — per-ACCOUNT connection health.
 *
 *  IMPORTANT lesson (2026-06-24): the daemon connection table is full of SHORT-LIVED sync
 *  connections (swarm-conversation git fetches). A connection that is not `Connected` at a given
 *  instant is almost always a transient sync op, NOT a failure — and the daemon's noisy
 *  `Broken pipe` / `non-properly terminated` ERROR logs are just those connections being torn down
 *  after a fetch. So we do NOT flag individual device connections as "dead/stuck" anymore.
 *
 *  True health comes from reliable signals only: is the account REGISTERED, and is it actually
 *  SYNCING (has it had any connected peer recently). This object remembers, per account, the last
 *  time it saw a connected peer, so a momentary gap in the churn isn't mistaken for an outage.
 */
package cx.ring.utils

object ConnectionHealth {
    enum class Health { OFFLINE, HEALTHY, CONNECTING, NOT_SYNCING }

    private data class AccS(var lastConnectedMs: Long = 0L, var lastSeenMs: Long = 0L)
    private val accs = HashMap<String, AccS>()
    private var lastUpdateMs = 0L

    /** Feed per-account connectivity each poll: (accountId, hasConnectedPeerNow). */
    @Synchronized
    fun update(nowMs: Long, perAccount: List<Pair<String, Boolean>>) {
        if (nowMs - lastUpdateMs < 1500L) return  // dedupe across pollers
        lastUpdateMs = nowMs
        for ((id, connectedNow) in perAccount) {
            val s = accs.getOrPut(id) { AccS(lastSeenMs = nowMs) }
            s.lastSeenMs = nowMs
            if (connectedNow) s.lastConnectedMs = nowMs
        }
        accs.entries.removeAll { nowMs - it.value.lastSeenMs > 300_000L }
    }

    /** ms since the account last had any connected peer; Long.MAX_VALUE if never seen connected. */
    @Synchronized
    fun msSinceConnected(accountId: String, nowMs: Long): Long {
        val s = accs[accountId] ?: return Long.MAX_VALUE
        return if (s.lastConnectedMs == 0L) Long.MAX_VALUE else nowMs - s.lastConnectedMs
    }

    /** True health from reliable signals only. Transient connection churn never reads as failure:
     *  a registered account that connected within the last 90s is HEALTHY even if nothing is
     *  connected this exact instant. Only a registered account that has peers to reach but hasn't
     *  managed a connection in >150s is NOT_SYNCING (a real "registered but isolated" problem). */
    @Synchronized
    fun classify(accountId: String, nowMs: Long, registered: Boolean, hasConnectedNow: Boolean, hasPeers: Boolean): Health {
        if (!registered) return Health.OFFLINE
        if (hasConnectedNow) return Health.HEALTHY
        val since = msSinceConnected(accountId, nowMs)
        if (since < 90_000L) return Health.HEALTHY    // connected very recently → just churn
        if (!hasPeers) return Health.HEALTHY          // nothing to connect to → not a problem
        return if (since > 150_000L) Health.NOT_SYNCING else Health.CONNECTING
    }

    /** Health states that should raise the alarm (red dot ring + "needs attention"). */
    fun isProblem(h: Health) = h == Health.OFFLINE || h == Health.NOT_SYNCING

    @Synchronized fun reset() { accs.clear(); lastUpdateMs = 0L }
}
