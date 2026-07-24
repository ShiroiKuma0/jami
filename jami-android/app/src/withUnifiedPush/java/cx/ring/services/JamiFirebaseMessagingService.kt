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

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import cx.ring.application.JamiApplication
import cx.ring.application.JamiApplicationUnifiedPush
import cx.ring.service.PushForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM receiver for the dual-backend flavor. Slimmed from the withFirebase flavor's service: it
 * keeps the wake lock and the high-priority foreground-service escalation (call/message delivery
 * reliability while backgrounded) but drops the background account-deactivation machinery, which
 * the UnifiedPush-based flavor has never carried and which would interact with the watchdog's
 * adaptive streaming. Messages forward to the shared Application, which registers push-arrival
 * evidence regardless of transport.
 */
class JamiFirebaseMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wake:push").apply {
                setReferenceCounted(false)
                acquire(10_000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't acquire wake lock", e)
        }

        val pt = remoteMessage.data["pt"]?.lowercase() ?: ""
        val isHighPriority = remoteMessage.priority == RemoteMessage.PRIORITY_HIGH
        val isCallOrMessage = pt.contains("call") || pt.contains("gitmessage") ||
            pt.contains("invite") || pt.contains("sync")
        val app = JamiApplication.instance as? JamiApplicationUnifiedPush

        // Foreground-service escalation for high-priority call/message pushes: gives the daemon
        // fetch (proxy reconnect + DHT/swarm pull) a protected window.
        if (isHighPriority && isCallOrMessage) {
            Handler(Looper.getMainLooper()).post {
                try {
                    startForegroundService(Intent(this, PushForegroundService::class.java))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start push foreground service", e)
                }
            }
        }

        serviceScope.launch {
            try {
                app?.onMessageReceived(remoteMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing push message", e)
            } finally {
                if (!isCallOrMessage) {
                    try { wakeLock?.let { if (it.isHeld) it.release() } }
                    catch (e: Exception) { Log.w(TAG, "Can't release wake lock", e) }
                }
            }
        }
    }

    override fun onNewToken(refreshedToken: String) {
        Log.w(TAG, "onNewToken ${refreshedToken.take(12)}…")
        (JamiApplication.instance as? JamiApplicationUnifiedPush)?.setFcmToken(refreshedToken)
    }

    companion object {
        private const val TAG = "JamiFirebaseMessaging"
    }
}
