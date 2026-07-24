/*
 *  shiroikuma.jami fork — verified per-peer unreachability.
 *
 *  A contact can show a BLUE (available) presence dot while messages to them strand: presence is a
 *  DHT announce with its own TTL (and the daemon re-echoes cached values on re-subscribe), while
 *  delivery needs a live device channel. 2026-07-24: a peer whose device dropped off the DHT kept a
 *  blue dot for many minutes while a message sat undelivered — the dot must not claim a
 *  reachability the network cannot back.
 *
 *  The watchdog records evidence here when the LAST outgoing message to a peer has been stuck past
 *  the threshold with presence below CONNECTED (ConnectionHealth.accountStuckMessages). While the
 *  evidence stands, Account.presenceUpdate demotes an AVAILABLE announce to OFFLINE (red dot) and
 *  the monitor surfaces say "unreachable". Evidence clears when the message delivers (the stuck
 *  scan no longer lists the peer) or presence reaches CONNECTED (a live channel is proof of
 *  reachability); a suppressed AVAILABLE is restored on clear so the dot springs back to blue.
 */
package net.jami.utils

import java.util.concurrent.ConcurrentHashMap

object PeerReachability {
    private class Evidence {
        /** True when an AVAILABLE announce was seen (and suppressed) while the evidence stood —
         *  lets the clear path restore the blue dot instead of leaving a stale red. */
        @Volatile var hadAvailable = false
        /** Last RAW daemon presence (0/1/2) while the evidence stood. The demotion is DISPLAY-only;
         *  the watchdog's wedge triage must judge by this raw value, or the demotion feeds back
         *  into the triage as fake-OFFLINE and suppresses recovery (seen 2026-07-24 22:09). */
        @Volatile var rawStatus = 0
    }

    /** The RAW daemon presence for a demoted peer, or null when no evidence stands. */
    fun rawPresence(accountId: String, peerUri: String): Int? =
        evidence[key(accountId, peerUri)]?.rawStatus

    private val evidence = ConcurrentHashMap<String, Evidence>()

    private fun norm(u: String) = u.removePrefix("jami:").removePrefix("ring:")
    private fun key(accountId: String, peerUri: String) = "$accountId:${norm(peerUri)}"

    /** True while a stuck outgoing message marks this peer as verified-unreachable. */
    fun isUnreachable(accountId: String, peerUri: String): Boolean =
        evidence.containsKey(key(accountId, peerUri))

    /** Remember that the peer was showing AVAILABLE when the evidence was recorded. */
    fun noteHadAvailable(accountId: String, peerUri: String) {
        evidence[key(accountId, peerUri)]?.let { it.hadAvailable = true; it.rawStatus = 1 }
    }

    /** Replace the evidence set for [accountId] with the current stuck-peer scan result.
     *  Returns (newly unreachable, cleared-and-restore-available) peer uris. */
    fun sync(accountId: String, stuckUris: Collection<String>): Pair<List<String>, List<String>> {
        val want = HashMap<String, String>(stuckUris.size)
        for (u in stuckUris) want[key(accountId, u)] = u
        val newly = ArrayList<String>()
        val restore = ArrayList<String>()
        val prefix = "$accountId:"
        val iter = evidence.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key.startsWith(prefix) && e.key !in want) {
                val hadAvailable = e.value.hadAvailable
                iter.remove()
                if (hadAvailable) restore.add(e.key.removePrefix(prefix))
            }
        }
        for ((k, raw) in want) if (evidence.putIfAbsent(k, Evidence()) == null) newly.add(raw)
        return newly to restore
    }

    /** Drop the evidence for one peer (e.g. a live channel proved them reachable). */
    fun clear(accountId: String, peerUri: String) {
        evidence.remove(key(accountId, peerUri))
    }

    /** Presence gate for Account.presenceUpdate: CONNECTED clears the evidence (a live channel is
     *  proof of reachability); a — possibly stale — AVAILABLE announce is demoted to OFFLINE while
     *  evidence stands, remembered so a later clear can restore it. OFFLINE passes through. */
    fun gate(accountId: String, peerUri: String, rawStatus: Int): Int {
        if (rawStatus >= 2) {
            clear(accountId, peerUri)
            return rawStatus
        }
        if (rawStatus == 1) evidence[key(accountId, peerUri)]?.let {
            it.hadAvailable = true
            it.rawStatus = 1
            return 0
        }
        // A genuine OFFLINE from the daemon invalidates any remembered AVAILABLE — the clear
        // path must not restore a blue dot the network has since withdrawn.
        if (rawStatus == 0) evidence[key(accountId, peerUri)]?.let {
            it.hadAvailable = false
            it.rawStatus = 0
        }
        return rawStatus
    }
}
