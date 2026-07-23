/*
 *  Copyright (C) 2004-2025 Savoir-faire Linux Inc.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package cx.ring.utils

import cx.ring.application.JamiApplication
import java.net.HttpURLConnection
import java.net.URL

/** End-to-end self-test of the push leg: POST a marker message to our OWN UnifiedPush endpoint
 *  (the ntfy topic URL the accounts are registered with) and wait for it to come back through the
 *  distributor into [PushEvidence]. A pass proves ntfy-server reachability, the ntfy app's
 *  delivery, and our receiver — everything except the DHT proxy's own POST. A fail means
 *  proxy-mode inbound structurally cannot arrive, whatever the daemon's connection state says.
 *  ~1 KB of traffic, no Jami signaling involved. */
object PushProbe {
    private const val WINDOW_MS = 20_000L
    private const val POLL_MS = 500L

    fun run(onVerdict: (ok: Boolean, detail: String) -> Unit) {
        val endpoint = JamiApplication.instance?.pushToken?.first
        if (endpoint.isNullOrEmpty()) {
            onVerdict(false, "no UnifiedPush endpoint registered")
            return
        }
        val nonce = java.lang.Long.toHexString(System.currentTimeMillis()) + "-" + (1000..9999).random()
        PushEvidence.expect(nonce)
        Thread({
            val t0 = System.currentTimeMillis()
            val postError = try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write("{\"${PushEvidence.PROBE_KEY}\":\"$nonce\"}".toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) null else "endpoint answered HTTP $code"
            } catch (e: Exception) {
                "POST failed: ${e.javaClass.simpleName} ${e.message}"
            }
            if (postError != null) {
                onVerdict(false, postError)
                return@Thread
            }
            val deadline = t0 + WINDOW_MS
            while (System.currentTimeMillis() < deadline) {
                if (PushEvidence.matched(nonce)) {
                    onVerdict(true, "echo in ${System.currentTimeMillis() - t0}ms")
                    return@Thread
                }
                Thread.sleep(POLL_MS)
            }
            onVerdict(false, "posted OK but no echo within ${WINDOW_MS / 1000}s — distributor not delivering")
        }, "push-probe").start()
    }
}
