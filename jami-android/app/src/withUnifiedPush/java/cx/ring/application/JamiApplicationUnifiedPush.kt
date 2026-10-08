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
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import cx.ring.services.PushWakeup
import cx.ring.services.PushWakeupClassifier
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
            // …but tell the watchdog a token now EXISTS. Deferring was right; leaving the decision
            // unrevisited for up to PUSH_PROBE_PERIODIC_MS was not — the reason for the deferral
            // (no token) has just gone away, and on 2026-09-11 that cost 30 minutes of streaming
            // over a 0.7 s race between the startup probe and Firebase's answer.
            runCatching {
                cx.ring.utils.ConnectionWatchdog.onPushTokenArrived(applicationContext, mAccountService)
            }
            return
        }
        val token = pushToken
        if (mPreferencesService.settings.enablePushNotifications && token != null && token.first.isNotEmpty()) {
            mAccountService.setPushNotificationConfig(token.first, token.second, pushPlatform)
            // Keep the accounts online long enough to re-announce the new token before the
            // background optimization puts them back to sleep.
            onPushTokenRegistered()
        } else {
            mAccountService.setPushNotificationToken("")
            // No usable token: never leave accounts deactivated with nothing able to wake them.
            onPushTokenLost()
        }
    }

    /** Called from SettingsFragment (via the base hook) when the backend pref changes. */
    override fun onPushBackendChanged() = registerSelectedToken()

    /** Watchdog manual-Recover path: re-announce the current token to the daemon. Non-destructive —
     *  [registerSelectedToken] re-registers whatever we hold (or clears it if we hold nothing), so
     *  it repairs a lost daemon-side registration without ever putting a working token at risk. */
    override fun refreshPushRegistration() = registerSelectedToken()

    /** Watchdog repeat-wedge path: delete + re-fetch the FCM token so the accumulated stale
     *  server-side proxy subscriptions (every churn generation × ~80 keys, all pushing to the
     *  same token — measured 2–3 pushes/s, 2026-07-25) go orphaned at Google instead of flooding
     *  microG. The fresh token re-enters via [setFcmToken] → [registerSelectedToken], and the
     *  daemon re-subscribes everything against it. UnifiedPush backend: no rotation (endpoint
     *  is distributor-managed), no-op.
     *
     *  RETRY + SAFE DEGRADATION (2026-09-24, after a 48-hour self-inflicted outage). This deleted
     *  the working token unconditionally and gave the re-fetch no handler but a `Log.w` — and it
     *  fires from `fullRecover()`, i.e. exactly when the network is already wedged and the
     *  re-fetch is at its most likely to fail. On 2026-09-22 11:23:32 it did: the last push ever
     *  received landed 65 s later and push stayed dead for two days, because nothing retried and
     *  nothing noticed. FirebaseMessaging offers no "replace" — `getToken()` hands back the
     *  existing token, so the delete genuinely must come first — but a failed re-fetch must not be
     *  terminal. So: retry on a backoff, and when the retries are exhausted declare the token LOST
     *  rather than keep holding a string that can never receive anything again. Holding it was the
     *  real damage: `pushToken` stayed non-null, so every "is push usable?" check downstream —
     *  above all `deactivateRunnable`'s — kept passing, and the accounts kept being put to sleep
     *  with nothing able to wake them. */
    override fun rotatePushToken() {
        if (backend() != UiPrefs.PUSH_FCM) return
        try {
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener {
                refetchFcmToken(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "FCM token rotation failed", e)
            declareFcmTokenLost("rotation threw: ${e.message}")
        }
    }

    /** One post-rotation re-fetch attempt; failures retry on [TOKEN_REFETCH_BACKOFF_MS]. */
    private fun refetchFcmToken(attempt: Int) {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token: String? ->
                if (token.isNullOrEmpty()) scheduleFcmRefetch(attempt, "empty token")
                else {
                    Log.w(TAG, "FCM token rotated (${token.take(12)}…) on attempt ${attempt + 1}")
                    setFcmToken(token)
                }
            }.addOnFailureListener { e -> scheduleFcmRefetch(attempt, e.message ?: "unknown") }
        } catch (e: Exception) {
            scheduleFcmRefetch(attempt, "threw: ${e.message}")
        }
    }

    private fun scheduleFcmRefetch(attempt: Int, why: String) {
        if (attempt >= TOKEN_REFETCH_BACKOFF_MS.size) {
            Log.e(TAG, "FCM token re-fetch failed on every attempt ($why) — declaring the token lost")
            declareFcmTokenLost(why)
            return
        }
        val delay = TOKEN_REFETCH_BACKOFF_MS[attempt]
        Log.w(TAG, "FCM token re-fetch after rotation failed ($why) — retry ${attempt + 1} in ${delay / 1000}s")
        tokenHandler.postDelayed({ refetchFcmToken(attempt + 1) }, delay)
    }

    /** Every re-fetch failed. The old token is already gone at Google's side, so clearing our copy
     *  is not losing anything — it is making the loss VISIBLE: `setFcmToken(null)` runs
     *  [registerSelectedToken]'s else branch, which clears the daemon's token and calls
     *  `onPushTokenLost()`, so the accounts are restored and stop being deactivated. The watchdog's
     *  standing push-leg watch then moves the proxy clients to streaming LISTEN, which is what
     *  actually restores inbound delivery. Keep re-fetching in the background: the leg usually
     *  returns with the network, and a token that arrives later re-enters by the normal path. */
    private fun declareFcmTokenLost(why: String) {
        Log.e(TAG, "FCM token declared LOST ($why) — clearing it so push stops looking usable")
        setFcmToken(null)
        tokenHandler.postDelayed({ if (fcmToken == null) refetchFcmToken(0) }, TOKEN_REFETCH_LONG_RETRY_MS)
    }

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
        handleBackgroundWakeup(remoteMessage)
        mAccountService.pushNotificationReceived("", remoteMessage)
        mNotificationService.processPush()
    }

    /** FCM message arrival (JamiFirebaseMessagingService.onMessageReceived). */
    fun onMessageReceived(remoteMessage: RemoteMessage) {
        PushEvidence.noteRealPush()
        handleBackgroundWakeup(remoteMessage.data)
        mAccountService.pushNotificationReceived(remoteMessage.from ?: "", remoteMessage.data)
        mNotificationService.processPush()
    }

    /** Both transports converge here (2026-07-29): a push that arrives while backgrounded must
     *  restore the deactivated accounts BEFORE the daemon is handed the payload, or the fetch it
     *  triggers runs against inactive accounts. Payload classification is shared with the
     *  Firebase-only flavor, so a UnifiedPush arrival gets the same call/message grace windows
     *  instead of being treated as noise. */
    private fun handleBackgroundWakeup(data: Map<String, String>) {
        if (isForeground) return
        val expired = PushWakeupClassifier.isExpiration(data)
        // An expiry that NAMES a call/message type is the starvation signal measured in trial 6:
        // while asleep it may be the only thing the proxy has left to send us. Classified here so
        // the decision (and its rate limit) stays in one place, in JamiApplication.
        val expiredNamed = expired && PushWakeupClassifier.mentionsCallOrMessage(data)
        val wakeup = if (expired) PushWakeup(false, false) else PushWakeupClassifier.classify(data)
        // SK-WAKE (2026-09-12, diagnostic): what the proxy actually sent, and what we made of it.
        // The two ways a REAL message push becomes a no-op are both invisible without this: an
        // "exp" field short-circuits the whole restore, and the seenMessageIds dedupe demotes a
        // re-delivered id to noise, which the cooldown then drops.
        wakeClassifyLog(data, expired, wakeup)
        onBackgroundPushReceived(wakeup.isCall, wakeup.isMessage, expired, expiredNamed)
    }

    /** Records the raw classification inputs alongside the verdict. Field names only — `pt` is a
     *  MIME-ish type, `ids` are opaque DHT value ids (counted, not listed), `key` a DHT hash that
     *  SK-PROXYDIAG already logs. No message content passes through here. */
    private fun wakeClassifyLog(data: Map<String, String>, expired: Boolean, wakeup: PushWakeup) = try {
        cx.ring.utils.UiPrefs.appendRecoveryLog(
            this,
            "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}" +
                "  SK-WAKE classify pt=${data["pt"]?.take(80) ?: "-"}" +
                " ids=${data["ids"]?.split(',')?.count { it.isNotBlank() } ?: 0}" +
                " exp=${data.containsKey("exp")} keys=${data.keys.sorted().joinToString("|")}" +
                " → call=${wakeup.isCall} msg=${wakeup.isMessage} expired=$expired")
    } catch (e: Exception) {
        Log.w(TAG, "wakeClassifyLog failed", e)
    }

    /** Retries for the post-rotation token re-fetch. Main-looper: every hop is a short Firebase
     *  call, and [setFcmToken] lands on the same thread the rest of the token path uses. */
    private val tokenHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val PLATFORM_FCM = "android"
        private const val PLATFORM_UP = "unifiedpush"
        private val TAG = JamiApplicationUnifiedPush::class.simpleName

        /** Post-rotation re-fetch backoff. The rotation runs during a wedge, so the first attempts
         *  are expected to fail; the spread reaches ~12 min, past most transient outages, before
         *  the token is declared lost. */
        private val TOKEN_REFETCH_BACKOFF_MS = longArrayOf(5_000L, 30_000L, 120_000L, 600_000L)

        /** …and after that, keep asking at this cadence for as long as we hold no token. */
        private const val TOKEN_REFETCH_LONG_RETRY_MS = 30 * 60_000L
    }
}
