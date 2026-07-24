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
package cx.ring.application

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import cx.ring.utils.PushEvidence
import cx.ring.utils.UiPrefs
import dagger.hilt.android.HiltAndroidApp
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.ui.SelectDistributorDialogsBuilder
import org.unifiedpush.android.connector.ui.UnifiedPushFunctions

/**
 * Dual-backend push (shiroikuma fork, 2026-07-24). This one flavor carries BOTH transports —
 * Firebase Cloud Messaging (via microG) and UnifiedPush (via an ntfy/other distributor). Both
 * receivers are always alive; the `push_backend` preference decides which token the daemon is
 * registered against. Switching backends re-registers the selected token; the watchdog's adaptive
 * no-push machinery is transport-agnostic and works for either.
 */
@HiltAndroidApp
class JamiApplicationUnifiedPush : JamiApplication() {

    // Backend platform strings the daemon understands: FCM = "android", UnifiedPush = "unifiedpush".
    override val pushPlatform: String
        get() = if (backend() == UiPrefs.PUSH_FCM) PLATFORM_FCM else PLATFORM_UP

    // The two token slots, each filled by its own receiver. pushToken returns the SELECTED backend's.
    @Volatile private var fcmToken: Pair<String, String>? = null   // (token, "")
    @Volatile private var upToken: Pair<String, String>? = null    // (endpoint url, "pubKey|auth")

    override val pushToken: Pair<String, String>?
        get() = if (backend() == UiPrefs.PUSH_FCM) fcmToken else upToken

    private fun backend(): String =
        applicationContext?.let { UiPrefs.getPushBackend(it) } ?: UiPrefs.PUSH_FCM

    /** Register ONLY the selected backend's token with the daemon (or clear it if that backend has
     *  no token yet). While adaptive no-push is engaged the watchdog has deliberately cleared the
     *  token and moved the proxy clients to streaming LISTEN — do not undo that from a late token
     *  arrival; store it and let the watchdog restore on its own exit path. */
    fun registerSelectedToken() {
        if (cx.ring.utils.ConnectionWatchdog.isNoPushAdaptive()) {
            Log.d(TAG, "adaptive no-push active — token registration deferred")
            return
        }
        val token = pushToken
        if (mPreferencesService.settings.enablePushNotifications && token != null && token.first.isNotEmpty()) {
            mAccountService.setPushNotificationConfig(token.first, token.second, pushPlatform)
        } else {
            mAccountService.setPushNotificationToken("")
        }
    }

    /** Called from SettingsFragment (via the base hook) when the backend pref changes. */
    override fun onPushBackendChanged() = registerSelectedToken()

    // ---- FCM token intake (from JamiFirebaseMessagingService.onNewToken + the initial fetch) ----
    fun setFcmToken(token: String?) {
        fcmToken = token?.let { Pair(it, "") }
        if (backend() == UiPrefs.PUSH_FCM) registerSelectedToken()
    }

    // ---- UnifiedPush endpoint intake (from JamiPushService.onNewEndpoint) ----
    fun setUnifiedPushEndpoint(url: String, topicKey: String) {
        upToken = Pair(url, topicKey)
        if (backend() == UiPrefs.PUSH_UNIFIED) registerSelectedToken()
    }

    override fun activityInit(activityContext: Context) {
        // Only the UnifiedPush backend needs a distributor chosen; FCM needs none.
        if (backend() != UiPrefs.PUSH_UNIFIED) return
        try {
            SelectDistributorDialogsBuilder(
                activityContext,
                object : UnifiedPushFunctions {
                    override fun tryUseDefaultDistributor(callback: (Boolean) -> Unit) =
                        UnifiedPush.tryUseDefaultDistributor(activityContext, callback)
                    override fun getAckDistributor(): String? =
                        UnifiedPush.getAckDistributor(activityContext)
                    override fun getDistributors(): List<String> =
                        UnifiedPush.getDistributors(activityContext)
                    override fun register(instance: String) =
                        UnifiedPush.register(activityContext, instance)
                    override fun saveDistributor(distributor: String) =
                        UnifiedPush.saveDistributor(activityContext, distributor)
                }
            ).run()
        } catch (e: Exception) {
            Log.e(TAG, "Can't start UnifiedPush distributor selection", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Firebase is initialized programmatically (no google-services.json — see JamiFirebaseConfig).
        try {
            if (FirebaseApp.getApps(this).isEmpty())
                FirebaseApp.initializeApp(this, JamiFirebaseConfig.options)
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token: String? ->
                Log.w(TAG, "FCM token acquired (${token?.take(12)}…)")
                setFcmToken(token)
            }.addOnFailureListener { e ->
                Log.w(TAG, "FCM token unavailable (microG missing / registration refused): ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase init failed", e)
        }
    }

    /** UnifiedPush message arrival (JamiPushService.onMessage). */
    fun onMessage(remoteMessage: Map<String, String>) {
        if (PushEvidence.noteIfProbe(remoteMessage)) return   // self-test echo — not for the daemon
        PushEvidence.noteRealPush()
        mAccountService.pushNotificationReceived("", remoteMessage)
        mNotificationService.processPush()
    }

    /** FCM message arrival (JamiFirebaseMessagingService.onMessageReceived). */
    fun onMessageReceived(remoteMessage: RemoteMessage) {
        PushEvidence.noteRealPush()
        mAccountService.pushNotificationReceived(remoteMessage.from ?: "", remoteMessage.data)
        mNotificationService.processPush()
    }

    companion object {
        private const val PLATFORM_FCM = "android"
        private const val PLATFORM_UP = "unifiedpush"
        private val TAG = JamiApplicationUnifiedPush::class.simpleName
    }
}
