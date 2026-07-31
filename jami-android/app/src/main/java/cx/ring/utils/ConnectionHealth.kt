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
import net.jami.model.Contact
import net.jami.model.interaction.Interaction

object ConnectionHealth {
    enum class Health { OFFLINE, HEALTHY, CONNECTING, NOT_SYNCING, DEAF }

    /** An outgoing message undelivered (no ✓ from any recipient) longer than this is "stuck". */
    const val STUCK_MSG_MS = 90_000L

    /** Delivered to a PEER — mirrors the ○/✓ tick logic (ConversationAdapter.configureDisplayIndicator):
     *  the daemon's swarm statusMap includes the SENDER'S OWN entry (DISPLAYED for one's own message),
     *  so the user must be filtered out before checking, or every outgoing message reads as delivered
     *  (2026-07-24: this false-delivered kept a dead contact's stuck file invisible to the scan).
     *  The legacy per-interaction status stays as an extra delivered signal for old non-swarm rows. */
    fun deliveredToPeer(conv: net.jami.model.Conversation, e: Interaction): Boolean =
        e.status == Interaction.InteractionStatus.SUCCESS ||
            e.status == Interaction.InteractionStatus.DISPLAYED ||
            e.statusMap.any { (k, v) ->
                conv.findContact(net.jami.model.Uri.fromId(k))?.isUser != true &&
                    (v == Interaction.MessageStates.SUCCESS || v == Interaction.MessageStates.DISPLAYED)
            }

    /** One undelivered-too-long outgoing message, with the triage data the watchdog needs:
     *  the best (highest) presence among the recipients decides whose fault it likely is —
     *  a CONNECTED recipient who still hasn't ACKed is damning for our side; an OFFLINE one
     *  (airplane, powered-off device) makes the same observation completely benign. */
    data class StuckMsg(
        val convId: String,
        val memberUri: String,
        val presence: Contact.PresenceStatus,
        val fingerprint: String,
        /** Which account observed it. Without this the watchdog flattened stuck messages from all
         *  accounts into one list and could not tell WHICH was wedged, so a single stuck message on
         *  one account escalated ALL of them to full DHT for 10-30 min — with three healthy
         *  accounts unable to stop it, because the evidence no longer said where it came from. */
        val accountId: String,
    )

    /** All conversations (1:1 and swarm) whose NEWEST outgoing deliverable interaction (TEXT or
     *  DATA_TRANSFER — via Conversation.lastDeliverableOutgoing, so calls/contact events can't
     *  mask it) is undelivered for > STUCK_MSG_MS, each annotated with the best recipient
     *  presence. Unlike the old same-device-only heuristic, this scans everything and leaves the
     *  its-their-fault / our-fault judgment to the caller, which has the presence to decide. */
    fun accountStuckMessages(account: Account, nowMs: Long): List<StuckMsg> {
        val out = ArrayList<StuckMsg>()
        for (conv in account.getConversations()) {
            val e = conv.lastDeliverableOutgoing() ?: continue
            if (deliveredToPeer(conv, e) || nowMs - e.timestamp <= STUCK_MSG_MS) continue
            val best = conv.contacts.filter { !it.isUser }
                .maxByOrNull { it.lastPresence.ordinal } ?: continue
            // Triage must judge by the RAW daemon presence: for a peer the watchdog demoted (red
            // dot), lastPresence reads the demotion's OFFLINE — feeding that back here made the
            // wedge triage call real evidence "benign offline" and stand down (2026-07-24 22:09).
            val raw = net.jami.utils.PeerReachability.rawPresence(account.accountId, best.uri.uri)
                ?.let { Contact.PresenceStatus.entries[it.coerceIn(0, 2)] }
            out.add(StuckMsg(
                conv.uri.uri,
                best.uri.uri,
                raw ?: best.lastPresence,
                "${conv.uri.uri}:${e.timestamp}",
                account.accountId))
        }
        return out
    }

    /** Why an outgoing message is stuck — decides red vs blue (白い熎, 2026-07-24):
     *   FAULT  (red)  = something WE should act on: a same-device sync stall, or a peer that is
     *                   CONNECTED (a live channel) yet never ACKed. Counts as "needs attention".
     *   PENDING (blue) = we are simply attempting to deliver to a recipient who is offline or only
     *                   announced (away) — benign, will deliver when they return. NOT our fault,
     *                   never turns the account red. */
    enum class StuckSeverity { FAULT, PENDING }

    /** How long a PENDING (offline-recipient) message stays on the health surfaces. Older than this
     *  it's just an ancient message to a long-gone contact — noise on a connectivity view. FAULTs
     *  are always shown regardless of age. */
    const val PENDING_SHOW_MS = 24 * 3_600_000L

    /** A conversation whose newest outgoing message is stuck, with the data the UI needs to NAME it
     *  unambiguously, show CONCRETE detail, colour it by cause, and OPEN it on tap. */
    data class StuckConv(
        val convId: String,     // conversation uri — open it with ConversationPath.toUri(accountId, convId)
        val memberUri: String,  // the recipient's ring id (to disambiguate the shared name)
        val timestamp: Long,    // the stuck message's timestamp (ms) — age = now − this
        val isFile: Boolean,    // file transfer vs text
        val preview: String,    // filename (file) or a short text preview
        val sameDevice: Boolean,// recipient is another of MY accounts (always reachable → a real stall)
        val presence: Contact.PresenceStatus, // recipient's RAW presence (never the demoted value)
        val severity: StuckSeverity,
    )

    private fun rawPresenceOf(account: Account, c: Contact): Contact.PresenceStatus {
        val uri = c.uri.rawRingId ?: c.uri.uri
        return net.jami.utils.PeerReachability.rawPresence(account.accountId, uri)
            ?.let { Contact.PresenceStatus.entries[it.coerceIn(0, 2)] } ?: c.lastPresence
    }

    /** Every conversation whose newest outgoing deliverable interaction is undelivered for
     *  > STUCK_MSG_MS, classified FAULT (red) vs PENDING (blue). PENDING entries older than
     *  [PENDING_SHOW_MS] are dropped (ancient message to an away contact = noise). myUris = my own
     *  accounts' ring ids, used to spot same-device stalls. NOTE: the recipient scan excludes the
     *  USER's own contact (isUser) — otherwise a 1:1 with an external contact self-matched myUris and
     *  was mislabelled a same-device stall "→ myself" (白い熎, 2026-07-24). */
    fun accountStuckConvs(account: Account, nowMs: Long, myUris: Set<String>): List<StuckConv> {
        val out = ArrayList<StuckConv>()
        for (conv in account.getConversations()) {
            val e = conv.lastDeliverableOutgoing() ?: continue
            if (deliveredToPeer(conv, e) || nowMs - e.timestamp <= STUCK_MSG_MS) continue
            val recipients = conv.contacts.filter { !it.isUser }
            if (recipients.isEmpty()) continue
            val sameDevice = recipients.any { (it.uri.rawRingId ?: it.uri.uri) in myUris }
            // Name the same-device sibling when there is one (that's the interesting party); else the
            // best-presence external recipient.
            val named = (if (sameDevice) recipients.firstOrNull { (it.uri.rawRingId ?: it.uri.uri) in myUris } else null)
                ?: recipients.maxByOrNull { rawPresenceOf(account, it).ordinal }!!
            val bestPresence = recipients.maxOf { rawPresenceOf(account, it) }
            val severity = when {
                sameDevice -> StuckSeverity.FAULT                              // same phone → real stall
                bestPresence == Contact.PresenceStatus.CONNECTED -> StuckSeverity.FAULT  // live channel, no ACK
                else -> StuckSeverity.PENDING                                  // away → attempting, benign
            }
            if (severity == StuckSeverity.PENDING && nowMs - e.timestamp > PENDING_SHOW_MS) continue
            out.add(StuckConv(conv.uri.uri, named.uri.rawRingId ?: named.uri.uri, e.timestamp,
                e.type == Interaction.InteractionType.DATA_TRANSFER,
                (e.body ?: "").trim().replace('\n', ' ').take(48),
                sameDevice, bestPresence, severity))
        }
        return out
    }

    /** FAULT-class recipient ring ids only — the account-health signal (NOT_SYNCING / red dot / "needs
     *  attention"). A benign PENDING message to an away contact must never turn the account red, so it
     *  is excluded here (白い熎, 2026-07-24). Used by the classifier, the dot poll and the picker. */
    fun accountStuckConvUris(account: Account, nowMs: Long, myUris: Set<String>): List<String> =
        accountStuckConvs(account, nowMs, myUris).filter { it.severity == StuckSeverity.FAULT }.map { it.memberUri }

    /** Compact age label for a stuck message: "45s" / "6m" / "2h14m" / "1d3h". */
    fun ageLabel(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h${(s % 3600) / 60}m"
            else -> "${s / 86_400}d${(s % 86_400) / 3600}h"
        }
    }

    /** Account health (2026-07-23 metric redesign: "registered" is NOT "receiving" — the watchdog's
     *  probe-VERIFIED verdicts outrank the registration table). Priority: OFFLINE → DEAF (verified
     *  not-receiving: an unanswered 60-s probe, never the bare quiet clock — that false-positives on
     *  every quiet evening) → NOT_SYNCING (stuck outgoing message) → CONNECTING (a verification
     *  probe in flight, or connection attempts — in-progress, blue, never itself a problem) →
     *  HEALTHY. The default deaf/probing arguments keep old call sites compiling unchanged. */
    fun classify(registered: Boolean, hasConnectedNow: Boolean, hasStuckMsg: Boolean, hasAttempts: Boolean,
                 deaf: Boolean = false, probing: Boolean = false): Health {
        if (!registered) return Health.OFFLINE
        if (deaf) return Health.DEAF
        if (hasStuckMsg) return Health.NOT_SYNCING
        if (probing) return Health.CONNECTING
        if (hasConnectedNow) return Health.HEALTHY
        if (hasAttempts) return Health.CONNECTING
        return Health.HEALTHY
    }

    /** Health states that should raise the alarm (red dot ring + "needs attention"). */
    fun isProblem(h: Health) = h == Health.OFFLINE || h == Health.NOT_SYNCING || h == Health.DEAF
}
