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

/** Evidence clock for the push leg (proxy→ntfy→app). The UnifiedPush receiver reports every
 *  delivered message here, and the [PushProbe] self-test uses the nonce fields to recognise its
 *  own echo. In proxy(push) mode this is the ONLY carrier of new DHT values, so its liveness is
 *  load-bearing for the deafness triage (2026-07-23: ntfy riding Kōjiki's rewritten DNS silently
 *  killed every push — the daemon looked "wedged" while nothing could ever arrive). */
object PushEvidence {
    const val PROBE_KEY = "sk-push-probe"

    @Volatile private var expectedNonce: String? = null
    @Volatile private var matchedNonce: String? = null

    /** When a REAL daemon push (proxy→ntfy→app, probe echoes excluded) last reached the app.
     *  This is the proxies' leg — the one the self-test structurally cannot see (it only proves
     *  phone→ntfy→phone; 2026-07-24 morning: self-test green for 3 h while the proxies' pushes
     *  never arrived and 44 verified wedges piled up). Zero = none this process. */
    @Volatile var lastRealPushMs = 0L
        private set

    fun noteRealPush() { lastRealPushMs = System.currentTimeMillis() }

    fun expect(nonce: String) { expectedNonce = nonce; matchedNonce = null }

    /** True when the message is a probe echo — it must NOT be forwarded to the daemon. */
    fun noteIfProbe(msg: Map<String, String>): Boolean {
        val v = msg[PROBE_KEY] ?: return false
        if (v == expectedNonce) matchedNonce = v
        return true
    }

    fun matched(nonce: String) = matchedNonce == nonce
}
