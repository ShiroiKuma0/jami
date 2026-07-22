/*
 *  shiroikuma.jami fork — passive inbound-aliveness clock, per account.
 *
 *  Timestamps the last PEER-ORIGINATED event the daemon delivered (incoming
 *  message, delivery/read receipt, presence announce, typing indicator, trust
 *  request, incoming call), keyed by account. Fed by DaemonService's signal
 *  callbacks; read by the app-side ConnectionWatchdog. A per-account clock is
 *  essential: with several accounts, a couple of healthy ones otherwise mask a
 *  couple of deaf ones — an account can read REGISTERED ("connected") while its
 *  own presence listens and message channels deliver nothing (the 2026-07-20
 *  "4/4 connected yet contacts red" hollow-connection case). Purely
 *  observational: no probe traffic is ever generated.
 */
package net.jami.utils

import java.util.concurrent.ConcurrentHashMap

object InboundEvidence {
    data class Mark(val ms: Long, val kind: String)

    private val start = System.currentTimeMillis()
    private val perAccount = ConcurrentHashMap<String, Mark>()

    @Volatile
    var lastMs: Long = start
        private set

    @Volatile
    var lastKind: String = "start"
        private set

    // Echo suppression. The daemon replays each buddy's CACHED presence synchronously when we
    // (re)subscribe (newBuddyNotification) — that is NOT proof of live network reception. A probe
    // that re-subscribed therefore "answered itself" and masked real outages (2026-07-21). So
    // presence events within a short window after any (re)subscribe are treated as echoes and dropped.
    @Volatile private var lastSubscribeMs = 0L
    private const val ECHO_SUPPRESS_MS = 8_000L

    /** Call immediately before a buddy (re)subscription so the cached-presence echoes it triggers
     *  are not mistaken for live inbound traffic. */
    fun noteSubscribe() { lastSubscribeMs = System.currentTimeMillis() }

    /** Record one peer-originated event for [accountId]. Keep it trivial — called from daemon
     *  signal callbacks. A blank accountId still updates the global clock. */
    fun note(accountId: String?, kind: String) {
        val now = System.currentTimeMillis()
        if (kind == "presence" && now - lastSubscribeMs < ECHO_SUPPRESS_MS) return   // subscribe echo, not real
        lastMs = now
        lastKind = kind
        if (!accountId.isNullOrEmpty()) perAccount[accountId] = Mark(now, kind)
    }

    /** Milliseconds since the last inbound evidence for [accountId] (or since process start if this
     *  account has produced none yet — which errs benign: a brand-new account looks freshly alive). */
    fun quietMs(accountId: String): Long = System.currentTimeMillis() - (perAccount[accountId]?.ms ?: start)

    fun lastKind(accountId: String): String = perAccount[accountId]?.kind ?: "none"

    /** Mark this account's clock fresh without real evidence — used right after a successful probe
     *  answer so the just-verified account is not immediately re-flagged. */
    fun touch(accountId: String) {
        perAccount[accountId] = Mark(System.currentTimeMillis(), "probe")
    }
}
