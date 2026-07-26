/*
 *  shiroikuma.jami fork — raw transport egress tests.
 *
 *  Answers one question in ~2 s: can this network carry the UDP that ICE
 *  needs, and the TCP that proxy/TURN-relay mode needs? Used by the
 *  watchdog's restricted-network (hostile WiFi) classifier. Speaks only to
 *  Jami's own TURN server — one 20-byte STUN Binding Request over plain UDP
 *  and one bare TCP connect. No Jami traffic, nothing any contact can see.
 */
package cx.ring.utils

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

object NetProbe {
    private const val HOST = "turn.jami.net"
    private const val PORT = 3478
    private const val TIMEOUT_MS = 4000
    private const val UDP_ATTEMPTS = 2

    /** True iff a STUN Binding Response arrives over plain UDP — i.e. UDP egress works.
     *  Blocking (network I/O + DNS): call from a background thread only. */
    fun udpWorks(): Boolean {
        repeat(UDP_ATTEMPTS) {
            try {
                DatagramSocket().use { s ->
                    s.soTimeout = TIMEOUT_MS
                    val req = ByteArray(20)
                    req[0] = 0x00; req[1] = 0x01                                  // Binding Request
                    req[4] = 0x21; req[5] = 0x12; req[6] = 0xA4.toByte(); req[7] = 0x42  // magic cookie
                    val txid = ByteArray(12)
                    SecureRandom().nextBytes(txid)
                    System.arraycopy(txid, 0, req, 8, 12)
                    val addr = InetAddress.getByName(HOST)
                    s.send(DatagramPacket(req, req.size, addr, PORT))
                    val buf = ByteArray(256)
                    val resp = DatagramPacket(buf, buf.size)
                    s.receive(resp)
                    // Any STUN response with our cookie+transaction id proves UDP egress.
                    if (resp.length >= 20 && buf[0].toInt() == 0x01 &&
                        (8 until 20).all { i -> buf[i] == req[i] })
                        return true
                }
            } catch (_: Exception) {
                // fall through to retry / false
            }
        }
        return false
    }

    /** True iff a bare TCP connection to the TURN server succeeds — the relay/proxy path.
     *  Blocking: call from a background thread only. */
    fun tcpWorks(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS); true }
    } catch (_: Exception) {
        false
    }

    // ---- Cached verdict (2026-07-26) ----
    //
    // Until now the probe ran only reactively, after a recovery had already failed, and its answer
    // went nowhere but the recovery log — so you could sit on a UDP-blocking network and see nothing
    // until something broke. The verdict is now cached and readable, so the connection monitor can
    // show it and a network change can refresh it.

    enum class Transport { UNKNOWN, OK, HOSTILE, DEAD }

    data class Verdict(val udp: Boolean, val tcp: Boolean, val atMs: Long) {
        val transport: Transport get() = when {
            udp -> Transport.OK           // full DHT viable (TCP state is then not decisive)
            tcp -> Transport.HOSTILE      // the trap: UDP blocked, TCP alive → DHT proxy is the answer
            else -> Transport.DEAD        // no egress at all
        }
    }

    @Volatile private var cached: Verdict? = null

    /** Last verdict, or null if never probed. Cheap and non-blocking. */
    fun lastVerdict(): Verdict? = cached

    /** Record a verdict measured elsewhere (the watchdog already probes during diagnosis — its
     *  result should feed the same cache rather than being thrown away). */
    fun record(udp: Boolean, tcp: Boolean): Verdict =
        Verdict(udp, tcp, System.currentTimeMillis()).also { cached = it }

    /** Run both probes and cache the result. BLOCKING (up to ~2 x TIMEOUT_MS + a TCP connect) —
     *  background thread only. */
    fun refresh(): Verdict = record(udpWorks(), tcpWorks())

    /** Refresh off the caller's thread; [done] runs on the caller's Looper via [post] if given. */
    fun refreshAsync(post: ((Runnable) -> Unit)? = null, done: ((Verdict) -> Unit)? = null) {
        Thread({
            val v = refresh()
            if (done != null) {
                if (post != null) post(Runnable { done(v) }) else done(v)
            }
        }, "net-probe").start()
    }
}
