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
import cx.ring.application.JamiApplicationFirebase
import cx.ring.service.PushForegroundService
import kotlinx.coroutines.*

class JamiFirebaseMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Some devices block during call negotiation without a wake lock. The 10s timeout
        // is a safety net only: the lock is released as soon as the push is processed.
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wake:push").apply {
                setReferenceCounted(false)
                acquire((10 * 1000).toLong())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't acquire wake lock", e)
        }

        // Expired-value notification ("exp"): the value already left the DHT. Classified
        // first, since a stale call value keeps its audioCall/videoCall pt on expiration.
        val isExpiration = PushWakeupClassifier.isExpiration(remoteMessage.data)
        val wakeup = if (isExpiration) PushWakeup(false, false) else PushWakeupClassifier.classify(remoteMessage.data)
        val isCallWakeup = wakeup.isCall
        val isMessageWakeup = wakeup.isMessage
        val app = JamiApplication.instance as? JamiApplicationFirebase
        val appInForeground = app?.isForeground ?: false
        Log.d(TAG, "push call=$isCallWakeup message=$isMessageWakeup exp=$isExpiration foreground=$appInForeground priority=${remoteMessage.priority}/${remoteMessage.originalPriority}")

        // Start FGS for calls and messages when backgrounded: both trigger an async daemon
        // fetch (proxy reconnect + DHT/swarm pull).
        val needsFgs = (isCallWakeup || isMessageWakeup) && remoteMessage.priority == RemoteMessage.PRIORITY_HIGH && !appInForeground
        if (needsFgs) {
            try {
                Handler(Looper.getMainLooper()).post {
                    try {
                        startForegroundService(Intent(this, PushForegroundService::class.java))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start foreground service on main thread", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start foreground service for push notification", e)
            }
        }

        if (!appInForeground && app != null) {
            // Single serialized entry point; push-availability, grace window, restore,
            // reconnect and deactivation re-arm are all evaluated on the main thread.
            app.onBackgroundPushReceived(
                isCallPush = isCallWakeup,
                isMessagePush = isMessageWakeup,
                isExpiration = isExpiration
            )
        }

        serviceScope.launch {
            try {
                app?.onMessageReceived(remoteMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Error in processing message", e)
            } finally {
                // Re-arm deactivation so accounts restored above are not left active.
                if (!appInForeground) {
                    Log.d(TAG, "scheduling deactivation")
                    app?.scheduleBackgroundDeactivation()
                }
                // Non-call, non-message push (noise/expiration): nothing to fetch from the
                // DHT, so release early instead of burning the 10s timeout. Call and message
                // pushes keep the lock — both trigger an async daemon fetch (proxy reconnect
                // + DHT/swarm pull) that can take several seconds after this returns.
                if (!isCallWakeup && !isMessageWakeup) {
                    try {
                        wakeLock?.let { if (it.isHeld) it.release() }
                    } catch (e: Exception) {
                        Log.w(TAG, "Can't release wake lock", e)
                    }
                }
            }
        }
    }

    override fun onNewToken(refreshedToken: String) {
        Log.w(TAG, "onNewToken $refreshedToken")
        val app = JamiApplication.instance as? JamiApplicationFirebase
        app?.pushToken = Pair(refreshedToken, "")
    }

    companion object {
        private const val TAG = "JamiFirebaseMessaging"
    }
}
