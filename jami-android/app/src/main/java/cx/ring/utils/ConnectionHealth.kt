/*
 *  shiroikuma.jami fork — connection health.
 *
 *  The reliable "is my message going through?" signal is the UNDELIVERED MESSAGE itself — the hollow
 *  ○ that never fills — NOT the daemon connection table (which can't even see group swarms, and churns
 *  with benign short-lived sync connections). So an account is flagged NOT_SYNCING when its last
 *  outgoing text in a SAME-DEVICE conversation (one whose members include another of MY OWN accounts —
 *  the inter-account / same-device-group case where the DHT-proxy stall happens) has not been delivered
 *  (no SUCCESS/DISPLAYED from any recipient) for longer than STUCK_MSG_MS. The "same-device" gate keeps
 *  a normal wait on a genuinely-offline external contact from reading as a fault. The Sync icon is the
 *  remedy for this state.
 */
package cx.ring.utils

import net.jami.model.Account
import net.jami.model.interaction.Interaction

object ConnectionHealth {
    enum class Health { OFFLINE, HEALTHY, CONNECTING, NOT_SYNCING }

    /** An outgoing message undelivered (no ✓ from any recipient) longer than this is "stuck". */
    const val STUCK_MSG_MS = 90_000L

    /** For each SAME-DEVICE conversation (a member is another of MY accounts — includes group swarms,
     *  which the connection table cannot see) whose last outgoing text is undelivered for > STUCK_MSG_MS,
     *  returns a uri identifying it (the same-device member's ring id) so the caller can name the stuck
     *  chat. myUris = my own accounts' ring ids. The list size is the stuck-message count; non-empty =
     *  the account is NOT_SYNCING. This is the ground-truth "message not going through" signal. */
    fun accountStuckConvUris(account: Account, nowMs: Long, myUris: Set<String>): List<String> {
        val out = ArrayList<String>()
        for (conv in account.getConversations()) {
            val member = conv.contacts.firstOrNull { c ->
                val r = c.uri.rawRingId; (r != null && r in myUris) || c.uri.uri in myUris
            } ?: continue
            val e = conv.lastEvent ?: continue
            if (e.isIncoming || e.type != Interaction.InteractionType.TEXT) continue
            val delivered = e.status == Interaction.InteractionStatus.SUCCESS ||
                e.status == Interaction.InteractionStatus.DISPLAYED ||
                e.statusMap.values.any { it == Interaction.MessageStates.SUCCESS || it == Interaction.MessageStates.DISPLAYED }
            if (!delivered && nowMs - e.timestamp > STUCK_MSG_MS) out.add(member.uri.rawRingId ?: member.uri.uri)
        }
        return out
    }

    /** Account health. NOT_SYNCING iff it has a stuck outgoing message (the real, self-explanatory
     *  problem). Otherwise: connected → HEALTHY; actively attempting → CONNECTING (blue); idle / nothing
     *  to sync → HEALTHY. Connecting is never itself a problem. */
    fun classify(registered: Boolean, hasConnectedNow: Boolean, hasStuckMsg: Boolean, hasAttempts: Boolean): Health {
        if (!registered) return Health.OFFLINE
        if (hasStuckMsg) return Health.NOT_SYNCING
        if (hasConnectedNow) return Health.HEALTHY
        if (hasAttempts) return Health.CONNECTING
        return Health.HEALTHY
    }

    /** Health states that should raise the alarm (red dot ring + "needs attention"). */
    fun isProblem(h: Health) = h == Health.OFFLINE || h == Health.NOT_SYNCING
}
