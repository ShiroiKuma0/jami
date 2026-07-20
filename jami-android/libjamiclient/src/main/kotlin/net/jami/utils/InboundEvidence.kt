/*
 *  shiroikuma.jami fork — passive inbound-aliveness clock.
 *
 *  Timestamps the last PEER-ORIGINATED event the daemon delivered (incoming
 *  message, delivery/read receipt, presence announce, typing indicator, trust
 *  request, incoming call). Fed by DaemonService's signal callbacks; read by
 *  the app-side ConnectionWatchdog: a long gap with the network up means the
 *  daemon has gone deaf (e.g. expired NAT bindings while the CPU slept) even
 *  though its own connection state still looks fine — the failure mode behind
 *  the 2026-07-20 52-minute silent outage. Purely observational: no probe
 *  traffic is ever generated.
 */
package net.jami.utils

object InboundEvidence {
    @Volatile
    var lastMs: Long = System.currentTimeMillis()
        private set

    @Volatile
    var lastKind: String = "start"
        private set

    /** Record one peer-originated event. Called from daemon signal callbacks — keep it trivial. */
    fun note(kind: String) {
        lastMs = System.currentTimeMillis()
        lastKind = kind
    }
}
