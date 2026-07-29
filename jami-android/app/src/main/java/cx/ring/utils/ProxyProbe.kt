/*
 *  shiroikuma.jami fork — DHT proxy reachability probe.
 *
 *  Answers the one question the presence re-arm probe structurally CANNOT answer in proxy mode:
 *  is the account's own DHT proxy actually there?
 *
 *  Measured 2026-07-29: through a two-hour stretch on proxy the uniform "presence re-arm" probe came
 *  back 4/4 silent EVERY time, because a re-arm only re-listens on refCount 0→1 and there is nothing
 *  on the far side obliged to answer it. `SK-PROXYDIAG` showed why the silence means nothing — the
 *  proxy holds its listen stream idle for 600–750 s at a stretch ("listen rx RESUMED after 690s
 *  silence", 603 s, 752 s) and then resumes perfectly well. An unanswered probe there is not
 *  evidence of a wedge; it is the normal shape of the protocol. Three false `uniform-wedge`
 *  incidents fired on it that afternoon.
 *
 *  So ask the proxy directly instead. One HTTP round-trip to the node-info endpoint every opendht
 *  proxy serves at `/`: a reply — ANY reply, including an error status — proves the server is up and
 *  reachable from this network. No Jami traffic, nothing a contact can see, ~200 bytes.
 *
 *  Deliberately NOT a wedge verdict on its own: a reachable proxy with a dead subscription is
 *  possible, and an unreachable one is only conclusive in the negative direction. The watchdog uses
 *  it to separate "the proxy leg is gone" (real evidence, recover and escalate) from "the proxy is
 *  fine and simply quiet" (do not hammer).
 */
package cx.ring.utils

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

object ProxyProbe {
    private const val TIMEOUT_MS = 5000
    private const val DEFAULT_PORT = 80

    /** Result of one probe round. [reachable] is the verdict; [detail] is for the recovery log. */
    data class Verdict(val reachable: Boolean, val detail: String, val atMs: Long)

    @Volatile private var cached: Verdict? = null

    /** Last verdict, or null if never probed. Cheap and non-blocking. */
    fun lastVerdict(): Verdict? = cached

    /**
     * Probe every distinct proxy in [servers]. Reachable iff AT LEAST ONE answers — the accounts
     * share the app's fate, and one live proxy proves the network can carry the proxy protocol, so
     * a uniform "everything is wedged" accusation cannot be blamed on the transport.
     *
     * BLOCKING (DNS + connect + read, up to ~[TIMEOUT_MS] per distinct server) — background only.
     */
    fun probe(servers: Collection<String>): Verdict {
        val targets = servers.mapNotNull { normalize(it) }.distinct()
        if (targets.isEmpty()) return Verdict(false, "no proxy configured", System.currentTimeMillis())
                .also { cached = it }
        val notes = ArrayList<String>(targets.size)
        var anyUp = false
        for (t in targets) {
            val (ok, note) = reach(t)
            if (ok) anyUp = true
            notes.add(note)
        }
        return Verdict(anyUp, notes.joinToString("; "), System.currentTimeMillis()).also { cached = it }
    }

    /** Probe off the caller's thread; [done] runs on the caller's Looper via [post] if given. */
    fun probeAsync(
        servers: Collection<String>,
        post: ((Runnable) -> Unit)? = null,
        done: (Verdict) -> Unit,
    ) {
        Thread({
            val v = probe(servers)
            if (post != null) post(Runnable { done(v) }) else done(v)
        }, "proxy-probe").start()
    }

    /**
     * The daemon reports the proxy it actually resolved to as a bare `host:port` (e.g.
     * `dhtproxy4.jami.net:93` in the SK-PROXYDIAG lines), but the configured value may carry a
     * scheme or omit the port. Normalize to an absolute URL; null when there is nothing usable.
     */
    private fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("http://") || s.startsWith("https://")) return s.trimEnd('/')
        // A bare host, optionally with :port. Reject anything that is not host-shaped so a stray
        // config value can never be turned into a request at some unrelated address.
        val hostPort = s.substringBefore('/')
        if (!hostPort.contains('.')) return null
        return if (hostPort.contains(':')) "http://$hostPort" else "http://$hostPort:$DEFAULT_PORT"
    }

    /** One server. Any HTTP status line proves reachability — we are testing the server's presence,
     *  not its API, so a 404 or 400 counts exactly as much as a 200. */
    private fun reach(url: String): Pair<Boolean, String> {
        val host = runCatching { URL(url).host }.getOrNull() ?: return false to "$url: bad url"
        try {
            val conn = (URL("$url/").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Connection", "close")
            }
            try {
                val code = conn.responseCode      // triggers the round-trip
                return true to "$host: HTTP $code"
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (e: SocketTimeoutException) {
            // Accepted the connection and then said nothing. That is NOT reachability — a proxy that
            // takes our TCP and never answers is precisely the wedge this probe exists to catch, so
            // it must not be laundered into "up" by the TCP fallback below.
            return false to "$host: no reply in ${TIMEOUT_MS}ms"
        } catch (e: Exception) {
            // Some other failure: a dead host, a DNS miss, or a reply HttpURLConnection could not
            // parse. Only the last of those is still a live server, so settle it with a bare TCP
            // connect — "the port is open but it did not speak HTTP" is a reachable proxy.
            val port = runCatching { URL(url).port.takeIf { it > 0 } ?: DEFAULT_PORT }.getOrDefault(DEFAULT_PORT)
            return try {
                Socket().use { it.connect(InetSocketAddress(host, port), TIMEOUT_MS) }
                true to "$host: TCP open (no HTTP: ${e.javaClass.simpleName})"
            } catch (_: Exception) {
                false to "$host: unreachable (${e.javaClass.simpleName})"
            }
        }
    }
}
