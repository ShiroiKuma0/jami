package cx.ring.utils

import android.content.Context
import net.jami.services.AccountService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 *  SK diagnostic (2026-07-31): **which presence subscriptions do we hold, and what put them there.**
 *
 *  Written because the SK-PROXYDIAG log identifies an account only by its proxy URL
 *  (`dhtproxy5.jami.net:83`), so a key measured as expensive could not be traced back to an account,
 *  let alone to a conversation. That gap cost a whole round of guessing on 2026-07-31, when three
 *  DHT keys were found carrying 68–144 KiB each — against 4–9 KiB for a healthy key — and consuming
 *  81 % of all inbound bytes, with no way to say whose they were.
 *
 *  The cause is structural: [net.jami.services.AccountService.resubscribeAccountPresence] tracks
 *  every member of every conversation, and a group swarm's members are not necessarily contacts. So
 *  we hold permanent DHT listens on strangers, and when a stranger's account key is landfilled (the
 *  upstream CRL bug — see `patches/jami-publish-current-crl-only.patch`) every push about them makes
 *  us re-download their entire key.
 *
 *  Output goes beside the other logs, readable over adb without root:
 *  `/sdcard/Android/data/shiroikuma.jami/files/presence-map.txt`.
 *
 *  Diagnostic only — never throws into the caller, and rate-limited so it cannot become a write
 *  storm on the watchdog's 60 s tick.
 */
object PresenceMap {
    private const val FILE = "presence-map.txt"
    private const val MIN_INTERVAL_MS = 5 * 60_000L

    @Volatile private var lastWrite = 0L

    private fun bare(uri: String) = uri.removePrefix("jami:").removePrefix("ring:")

    /** opendht's InfoHash::get(str) is SHA-1 of the raw bytes (crypto::hash -> gnutls, 20 bytes). */
    private fun infoHash(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Rate-limited, exception-swallowing entry point. Safe to call from any periodic tick. */
    fun maybeWrite(c: Context, accounts: AccountService) {
        val now = System.currentTimeMillis()
        if (now - lastWrite < MIN_INTERVAL_MS) return
        lastWrite = now
        try {
            write(c, accounts)
        } catch (e: Throwable) {
            // A diagnostic must never take the watchdog down with it.
        }
    }

    private fun write(c: Context, accounts: AccountService) {
        val sb = StringBuilder(8192)
        sb.append("=== presence map ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append(" ===\n")
        sb.append("# [self NNNNNN] = one of MY OWN accounts; [contact] = in this account's contact\n")
        sb.append("# list; [STRANGER] = a conversation member who is neither.\n")
        sb.append("#\n")
        sb.append("# Section 1 is the CLIENT-side bounded presence set (resubscribeAccountPresence).\n")
        sb.append("# Section 2 is EVERY conversation and its members — which is what matters, because\n")
        sb.append("# the DAEMON independently presence-tracks up to min(N, 3+log2 N) members per\n")
        sb.append("# conversation whenever a swarm is down (stock conversation.cpp rotateTrackedMembers).\n")
        sb.append("# Those subscriptions never pass through presenceHeld, so section 1 alone MISSES\n")
        sb.append("# most of what we actually listen to — the lesson of 2026-07-31.\n")

        // Own-account URIs, so my own accounts stop being reported as strangers to each other.
        val selfUris = HashMap<String, String>()
        for (a in accounts.getAccounts())
            if (a.isJami) a.uri?.let { selfUris[bare(it)] = a.accountId.take(6) }

        for (a in accounts.getAccounts()) {
            if (!a.isJami) continue
            val contactUris = a.contacts.keys.mapTo(HashSet()) { bare(it) }
            val tracked = accounts.presenceTrackedUris(a.accountId)
            val convs = a.getConversations()

            fun label(u: String) = when {
                selfUris.containsKey(u) -> "  [self ${selfUris[u]}]"
                u in contactUris -> "  [contact]"
                else -> "  [STRANGER]"
            }

            sb.append("\nacct ").append(a.accountId.take(6))
                .append("  uri=").append(a.uri?.let { bare(it) } ?: "?")
                .append("  proxy=").append(a.dhtProxy.ifEmpty { "-" })
                .append("  tracked=").append(tracked.size)
                .append("  conversations=").append(convs.size)
                .append('\n')

            // The keys we listen on are NOT all account URIs. Per account the daemon also listens on
            // two DERIVED keys, and a derived key is what a 40-hex hash that matches no account, no
            // contact and no conversation member has to be:
            //   inbox:<deviceId>  -> TrustRequest        (account_manager.cpp:464)
            //   peer:<deviceId>   -> PeerConnectionRequest (dhtnet connectionmanager.cpp, key_prefix)
            // Printing them here is what turns an unexplained fat key into a named one. NOTE the
            // non-legacy dhtnet path derives peer: from the LONG device id, which the client does not
            // expose — so a peer: key may legitimately not match the short-id line below.
            val dev = a.deviceId
            sb.append("  device=").append(dev.ifEmpty { "?" }).append('\n')
            if (dev.isNotEmpty()) {
                sb.append("    inbox:<dev> = ").append(infoHash("inbox:$dev")).append('\n')
                sb.append("    peer:<dev>  = ").append(infoHash("peer:$dev")).append('\n')
            }

            sb.append("  -- section 1: client-side tracked set --\n")
            if (tracked.isEmpty()) sb.append("  (none)\n")
            for (t in tracked.sorted()) {
                val tb = bare(t)
                sb.append("  ").append(tb).append(label(tb)).append('\n')
            }

            sb.append("  -- section 2: all conversations and members --\n")
            for (conv in convs.sortedBy { it.uri.uri }) {
                val members = conv.contacts.filter { !it.isUser }
                sb.append("  ").append(if (conv.isSwarm) "swarm " else "conv  ")
                    .append(conv.uri.uri)
                    .append("  members=").append(members.size)
                    .append('\n')
                // Co-members make the conversation identifiable in the UI: searching a CONTACT's id
                // in the app resolves to that person, where a stranger's falls through to "Public
                // directory". So a group is named by whichever member you already know.
                for (m in members)
                    sb.append("      ").append(bare(m.uri.uri)).append(label(bare(m.uri.uri))).append('\n')
            }
        }
        File(c.getExternalFilesDir(null), FILE).writeText(sb.toString())
    }
}
