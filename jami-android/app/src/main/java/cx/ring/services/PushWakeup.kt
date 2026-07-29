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
package cx.ring.services

import java.util.Locale

/** What a DHT-proxy push wakeup is asking us to do. */
data class PushWakeup(val isCall: Boolean, val isMessage: Boolean)

/**
 * Transport-independent classification of a DHT-proxy push payload (shiroikuma fork, moved out of
 * the withFirebase FCM service 2026-07-29 so the dual-backend flavor can classify both its FCM and
 * its UnifiedPush arrivals with the same rules). The payload is the proxy's own key/value map, so
 * FCM's `RemoteMessage.data` and a UnifiedPush JSON body decode to the same shape.
 *
 * Classification drives the background-active grace windows in [cx.ring.application.JamiApplication]:
 * a call or message wakeup restores the deactivated accounts and reconnects, anything else is noise
 * that must not re-feed the restore/deactivate churn.
 */
object PushWakeupClassifier {

    /**
     * Classifies from the "pt" field (the connection request type the proxy copies in):
     * audioCall/videoCall for calls, application/im-gitmessage-id or application/invite for swarm
     * messages and invitations, and sync for multi-device account sync. Push priority is never used,
     * as regular DHT values are also high priority. Only value ids never seen by this process count,
     * dropping the catch-up re-deliveries the proxy emits on every fresh listener; the dedupe caches
     * are in-memory, so after a restart a stale id classifies once.
     */
    fun classify(data: Map<String, String>): PushWakeup {
        val pushTypes = data["pt"] ?: return PushWakeup(false, false)
        val ids = data["ids"]?.split(',') ?: emptyList()
        // Scope the dedupe key by destination client id and DHT key: value ids are random
        // 64-bit values, unique in practice but not across keys. Missing fields degrade to
        // coarser scoping, never to dropping a wakeup.
        val scope = "${data["to"] ?: ""}:${data["key"] ?: ""}"
        var newCall = false
        var newMessage = false
        pushTypes.splitToSequence(',').forEachIndexed { i, rawType ->
            val type = rawType.trim().lowercase(Locale.ROOT)
            val isCall = type == "audiocall" || type == "videocall"
            // Exact type or explicit separator only, so unrelated future types cannot match.
            val isMessage = type == "application/im-gitmessage-id"
                    || type.startsWith("application/im-gitmessage-id/")
                    || type == "application/invite"
                    || type.startsWith("application/invite+")
                    || type == "sync"
            if (!isCall && !isMessage) return@forEachIndexed
            val id = ids.getOrNull(i)?.trim()
            val isNew = if (id.isNullOrEmpty()) {
                true // No id to deduplicate on: fail open, a missed call is worse than a redundant restore.
            } else {
                // Per-kind caches so message volume cannot evict call dedupe state.
                val seen = if (isCall) seenCallIds else seenMessageIds
                synchronized(seen) { seen.put("$scope:$id", Unit) == null }
            }
            if (isNew) {
                if (isCall) newCall = true
                if (isMessage) newMessage = true
            }
        }
        return PushWakeup(newCall, newMessage)
    }

    /** An "exp" key means the value already left the DHT: nothing to fetch or answer. */
    fun isExpiration(data: Map<String, String>): Boolean = data.containsKey("exp")

    // Value ids already handled by this process, to drop proxy catch-up re-deliveries.
    // Two bounded LRU caches so frequent message ids cannot evict call dedupe state.
    private const val SEEN_CALL_IDS_MAX = 256
    private const val SEEN_MESSAGE_IDS_MAX = 2048
    private val seenCallIds = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) =
            size > SEEN_CALL_IDS_MAX
    }
    private val seenMessageIds = object : LinkedHashMap<String, Unit>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) =
            size > SEEN_MESSAGE_IDS_MAX
    }
}
