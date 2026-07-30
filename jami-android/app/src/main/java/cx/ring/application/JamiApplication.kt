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

import android.app.Activity
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.*
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bumptech.glide.Glide
import cx.ring.BuildConfig
import cx.ring.service.ActiveServiceMonitor
import cx.ring.service.ConnectionService
import cx.ring.R
import cx.ring.service.DRingService
import cx.ring.service.JamiJobService
import cx.ring.service.PeerTunnelForegroundService
import cx.ring.linkpreview.LinkPreview
import cx.ring.services.AndroidExposedServicesService
import cx.ring.services.CallServiceImpl.Companion.CONNECTION_SERVICE_TELECOM_API_SDK_COMPATIBILITY
import cx.ring.utils.AndroidFileUtils
import cx.ring.views.AvatarFactory
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import net.jami.daemon.JamiService
import net.jami.services.*
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named


abstract class JamiApplication : Application() {
    companion object {
        private val TAG = JamiApplication::class.java.simpleName
        const val DRING_CONNECTION_CHANGED = BuildConfig.APPLICATION_ID + ".event.DRING_CONNECTION_CHANGE"
        const val PERMISSIONS_REQUEST = 57
        private val RINGER_FILTER = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        var instance: JamiApplication? = null

        // ---- Background battery optimization timings ------------------------------------------
        private const val BACKGROUND_DEACTIVATION_DELAY_MS = 5_000L
        // Recheck interval while a call is active (less aggressive than the base delay).
        private const val CALL_ACTIVE_RECHECK_MS = 30_000L
        // Grace windows keeping accounts active after a background push; calls get longer.
        private const val PUSH_GRACE_MS = 30_000L
        private const val CALL_PUSH_GRACE_MS = 60_000L
        // Upper bound for one continuous background-active episode under sustained pushes.
        private const val MAX_BACKGROUND_ACTIVE_MS = 10 * 60_000L
        // Minimum time deactivated before a non-call push may restore accounts again.
        private const val NONCALL_RESTORE_COOLDOWN_MS = 3 * 60_000L
        // Recheck cadence while the watchdog says the push leg is not proven — long enough that a
        // standing outage costs nothing, short enough to sleep soon after the leg comes back.
        private const val SLEEP_UNSAFE_RECHECK_MS = 60_000L

        /**
         * Background deactivation is OFF — it cannot pay for itself on the DHT proxy (measured
         * 2026-07-29, four accounts, 4 h 41 m on battery).
         *
         * [AccountService.deactivateProxyAccountsForBackground] tears each account down with
         * `setAccountActive(id, false, shutdownConnections=true)`, so every restore rebuilds its
         * proxy listen subscriptions from scratch — and re-establishing a proxy subscription
         * re-downloads the full value set of every key. Measured cost: **~13 MiB per sleep/wake
         * round**. Pushes arrive every 60–180 s, well inside [PUSH_GRACE_MS], so the cycle ran about
         * ten times an hour and burned **133 MiB/h — no better than the full DHT it replaced**, plus
         * 285 MB of metered cellular in one afternoon.
         *
         * Registered and idle on the proxy costs **1.33 MiB/h** (measured over the 1 h 33 m the
         * watchdog happened to hold the accounts awake). A sleep episode would therefore have to run
         * ~10 hours to break even on one teardown, and nothing like that can happen while push
         * traffic keeps waking us. There is no cadence at which this wins, so it is not a tuning
         * problem — the optimization is simply inapplicable to proxy mode, which is now the resting
         * mode. It was written in the withFirebase flavor against full-DHT costs and hoisted into
         * `main` in `+143` on the assumption that it would transfer; the measurement says it does
         * not.
         *
         * Kept rather than deleted: the guards it needs ([ConnectionWatchdog.backgroundSleepSafe],
         * the detector stand-down, the restore ledger) are all still correct, and flipping this back
         * to `true` is the whole of the rollback. Note `hardwareService.connectivityChanged(true)`
         * on message pushes is NOT implicated and stays — it fired ~17×/h through the cheap stretch
         * and cost nothing measurable, which retires the leading suspect from the earlier analysis.
         *
         * Deliberately a plain `val`, not a `const val`: a compile-time constant would fold the
         * guards below into dead code and draw "condition is always false" warnings on a file that
         * is in the built variant's lintVital scope.
         */
        private val BACKGROUND_DEACTIVATION_ENABLED = false
    }

    @Inject
    @Named("DaemonExecutor") lateinit
    var mExecutor: ScheduledExecutorService

    @Inject lateinit
    var daemon: DaemonService

    @Inject lateinit
    var mAccountService: AccountService

    @Inject lateinit
    var mNotificationService: NotificationService

    @Inject lateinit
    var mCallService: CallService

    @Inject lateinit
    var hardwareService: HardwareService

    @Inject lateinit
    var mPreferencesService: PreferencesService

    @Inject lateinit
    var mDeviceRuntimeService: DeviceRuntimeService

    @Inject lateinit
    var mContactService: ContactService

    @Inject lateinit
    var mConversationFacade: ConversationFacade

    @Inject lateinit
    var peerServicesService: PeerServicesService

    @Inject lateinit
    var mExposedServicesService: ExposedServicesService

    private val ringerModeListener: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ringerModeChanged(intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL))
        }
    }
    abstract val pushToken: Pair<String, String>?
    abstract val pushPlatform: String

    /** The push backend selection changed (dual-backend flavor) — re-register the selected token.
     *  No-op in single-backend flavors. Kept in the base so main-source callers stay flavor-safe. */
    open fun onPushBackendChanged() {}

    /** Rotate the push registration token (watchdog, repeat-wedge path): stale dhtproxy
     *  subscription generations keep pushing to the old token indefinitely — a fresh token
     *  orphans them upstream instead of at our battery/quota (2026-07-25). No-op unless the
     *  flavor's active backend supports rotation (FCM does). */
    open fun rotatePushToken() {}

    var androidPhoneAccountHandle: PhoneAccountHandle? = null

    open fun activityInit(activityContext: Context) {}

    // ---- Background battery optimization -----------------------------------------------------
    // Hoisted out of the withFirebase flavor (2026-07-29). The shipped withUnifiedPush flavor never
    // carried it, so every account stayed fully active for as long as the process lived — measured
    // 145 pkt/s, 94 MB/h and a WiFi radio that slept 289 ms in two hours. Living in the base means
    // every flavor gets the same behaviour; it is inherently a no-op unless push is usable AND the
    // accounts ride the DHT proxy, since deactivateProxyAccountsForBackground() only touches
    // proxy-enabled accounts (full DHT has no push leg to be woken by).

    private val backgroundHandler = Handler(Looper.getMainLooper())

    /** True while an activity is visible. Must be read on the main thread. */
    private fun isAppVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** Cached foreground state, readable from any thread (set on the main thread by the
     *  lifecycle observer). Push receivers use it to decide whether to escalate. */
    @Volatile var isForeground: Boolean = false
        private set

    // elapsedRealtime of the last background push or token refresh / last call push, used as grace
    // windows that keep accounts active long enough for the daemon to reconnect and deliver the
    // incoming call or message. Written from the push thread, read from the main thread.
    private val lastPushTime = AtomicLong(0L)
    private val lastCallPushTime = AtomicLong(0L)
    private val lastMessagePushTime = AtomicLong(0L)

    // Start of the current continuous background-active episode (0 when none). Bounds how long
    // sustained pushes can keep accounts active; only the first event records it.
    private val backgroundActiveSince = AtomicLong(0L)

    // elapsedRealtime of the last background deactivation (0 if none). Gates how soon a non-call
    // push may restore accounts again, breaking the restore/deactivate churn.
    private val lastBackgroundDeactivation = AtomicLong(0L)

    private val deactivateRunnable = Runnable {
        // Belt to scheduleBackgroundDeactivation's braces: guard the act itself, not only the
        // scheduling, so no caller can reach a teardown by posting this runnable directly.
        if (!BACKGROUND_DEACTIVATION_ENABLED) return@Runnable
        if (isAppVisible()) return@Runnable
        // Push no longer usable: restore accounts and fall back to always-connected behavior.
        if (!mPreferencesService.settings.enablePushNotifications || pushToken == null) {
            Log.d(TAG, "Push unavailable while backgrounded — restoring accounts")
            backgroundActiveSince.set(0L)
            mAccountService.restoreProxyAccountsAfterBackground()
            return@Runnable
        }
        val now = SystemClock.elapsedRealtime()
        val callGraceRemaining = lastCallPushTime.get() + CALL_PUSH_GRACE_MS - now
        val messageGraceRemaining = lastMessagePushTime.get() + PUSH_GRACE_MS - now
        val graceRemaining = maxOf(callGraceRemaining, messageGraceRemaining, lastPushTime.get() + PUSH_GRACE_MS - now)
        val episodeStart = backgroundActiveSince.get()
        val capReached = episodeStart != 0L && now - episodeStart >= MAX_BACKGROUND_ACTIVE_MS
        when {
            // Active/pending call or any running foreground service (file transfer, peer/hosted
            // tunnel, location sharing): keep accounts active and recheck until all sessions finish.
            mCallService.hasActiveCalls() || ActiveServiceMonitor.hasActiveServices() ->
                scheduleBackgroundDeactivation(CALL_ACTIVE_RECHECK_MS)
            // Call push still negotiating (not yet visible to hasActiveCalls): wait out its
            // grace window, also exempt from the cap.
            callGraceRemaining > 0 -> scheduleBackgroundDeactivation(callGraceRemaining)
            messageGraceRemaining > 0 -> scheduleBackgroundDeactivation(messageGraceRemaining)
            // Recent push/token refresh: wait out the grace window unless the episode cap is reached.
            graceRemaining > 0 && !capReached -> scheduleBackgroundDeactivation(graceRemaining)
            // Never sleep into deafness: the watchdog knows when the push leg is not delivering
            // (dead endpoint, adaptive streaming-LISTEN mode, restricted network, a recovery still
            // settling). Deactivating there would leave nothing at all able to wake us, which is a
            // reliability failure, not a battery saving. Recheck instead of deactivating.
            !cx.ring.utils.ConnectionWatchdog.backgroundSleepSafe(this) -> {
                Log.d(TAG, "Background deactivation held — push leg not proven; rechecking")
                scheduleBackgroundDeactivation(SLEEP_UNSAFE_RECHECK_MS)
            }
            else -> {
                // Written out rather than `"literal" + if (…) … else ""`: that shape crashes lint's
                // Kotlin→UAST converter, and this file is in the built variant's lintVital scope.
                val capNote = if (capReached) " (background-active cap reached)" else ""
                Log.d(TAG, "App went to background with push enabled — deactivating accounts$capNote")
                // Open the non-call cooldown only when a real episode concludes; redundant
                // passes (no episode) must not slide it forward.
                if (episodeStart != 0L) lastBackgroundDeactivation.set(now)
                backgroundActiveSince.set(0L)
                mAccountService.deactivateProxyAccountsForBackground()
            }
        }
    }

    /**
     * Handles a push received while backgrounded: opens the grace window, restores accounts
     * (and reconnects for call/message pushes), then re-arms the deactivation check.
     */
    fun onBackgroundPushReceived(isCallPush: Boolean, isMessagePush: Boolean, isExpiration: Boolean = false) {
        // Expired value: already gone from the DHT, nothing to fetch or answer.
        if (isExpiration) return
        // Background noise (neither call nor message): gated during the post-deactivation
        // cooldown to avoid re-feeding the reconnect churn. 0 means no deactivation yet.
        if (!isCallPush && !isMessagePush) {
            val lastDeactivation = lastBackgroundDeactivation.get()
            if (lastDeactivation != 0L
                && SystemClock.elapsedRealtime() - lastDeactivation < NONCALL_RESTORE_COOLDOWN_MS
            ) {
                return
            }
        }
        // Publish the grace window before cancelling: a deactivateRunnable already running
        // reads these atomics, and any deactivation it queues runs FIFO after the restore below.
        val now = SystemClock.elapsedRealtime()
        lastPushTime.set(now)
        if (isCallPush) lastCallPushTime.set(now)
        if (isMessagePush) lastMessagePushTime.set(now)
        backgroundActiveSince.compareAndSet(0L, now)
        backgroundHandler.removeCallbacks(deactivateRunnable)
        backgroundHandler.post {
            // Call pushes always restore/reconnect; non-call pushes keep the push-availability
            // gate so a stale delivery after push was disabled cannot reactivate accounts.
            if (isCallPush
                || (mPreferencesService.settings.enablePushNotifications && pushToken != null)
            ) {
                mAccountService.restoreProxyAccountsAfterBackground()
                // Full DHT/SIP reconnect to rebuild sockets torn down in doze.
                if (isCallPush || isMessagePush) hardwareService.connectivityChanged(true)
            }
            backgroundHandler.removeCallbacks(deactivateRunnable)
            if (BACKGROUND_DEACTIVATION_ENABLED)
                backgroundHandler.postDelayed(deactivateRunnable, BACKGROUND_DEACTIVATION_DELAY_MS)
        }
    }

    /**
     * Schedules a delayed background deactivation, serialized through the main looper since
     * it is also called from the push service thread. The runnable re-schedules itself while
     * a call is active or a push grace window is open.
     */
    fun scheduleBackgroundDeactivation(delayMs: Long = BACKGROUND_DEACTIVATION_DELAY_MS) {
        // Off by measurement — see BACKGROUND_DEACTIVATION_ENABLED. Returning here (rather than
        // letting the runnable fire and decline) also keeps the main looper free of a wake-up per
        // push that could only ever decide to do nothing.
        if (!BACKGROUND_DEACTIVATION_ENABLED) return
        backgroundHandler.post {
            backgroundHandler.removeCallbacks(deactivateRunnable)
            backgroundHandler.postDelayed(deactivateRunnable, delayMs)
        }
    }

    /** A flavor just registered (or re-registered) its push token with the daemon: keep the
     *  accounts online long enough to re-announce it before the optimization shuts them down. */
    protected fun onPushTokenRegistered() {
        lastPushTime.set(SystemClock.elapsedRealtime())
        backgroundHandler.post {
            mAccountService.restoreProxyAccountsAfterBackground()
            if (!isAppVisible()) {
                hardwareService.connectivityChanged(true)
                scheduleBackgroundDeactivation(PUSH_GRACE_MS)
            }
        }
    }

    /** The flavor's push token became unusable: restore immediately (no-op if nothing was
     *  deactivated) so the accounts are never left asleep with no way to be woken. */
    protected fun onPushTokenLost() {
        mAccountService.restoreProxyAccountsAfterBackground()
    }

    // Guards against duplicate observer registration.
    private var lifecycleObserverRegistered = false
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            isForeground = true
            backgroundHandler.removeCallbacks(deactivateRunnable)
            backgroundActiveSince.set(0L)
            // Unconditional restore: only touches the recorded set, a no-op otherwise.
            Log.d(TAG, "App came to foreground — reactivating accounts")
            mAccountService.restoreProxyAccountsAfterBackground()
            // Two-second connection polling is only worth paying for while something is on screen
            // (2026-07-30) — backgrounded, the only consumer is the dead-link alarm.
            mAccountService.setConnectionPollFast(true)
        }
        override fun onStop(owner: LifecycleOwner) {
            isForeground = false
            mAccountService.setConnectionPollFast(false)
            backgroundActiveSince.compareAndSet(0L, SystemClock.elapsedRealtime())
            scheduleBackgroundDeactivation()
        }
    }

    /** ProcessLifecycleOwner gives reliable app-wide foreground transitions across activity and
     *  configuration changes. Registered from [onCreate] so every flavor is covered. */
    private fun registerBackgroundOptimization() {
        if (lifecycleObserverRegistered) return
        lifecycleObserverRegistered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    }

    private var mBound = false
    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, s: IBinder) {
            Log.d(TAG, "onServiceConnected: " + className.className)
            mBound = true
            // bootstrap Daemon
            //bootstrapDaemon();
        }

        override fun onServiceDisconnected(className: ComponentName) {
            Log.d(TAG, "onServiceDisconnected: " + className.className)
            mBound = false
        }
    }

    private fun ringerModeChanged(newMode: Int) {
        val mute = newMode == AudioManager.RINGER_MODE_VIBRATE || newMode == AudioManager.RINGER_MODE_SILENT
        mCallService.muteRingTone(mute)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AvatarFactory.clearCache()
        Glide.get(this).clearMemory()
        LinkPreview.clearCache()
    }

    fun bootstrapDaemon() {
        if (daemon.isStarted) {
            return
        }
        Log.d(TAG, "bootstrapDaemon")
        mExecutor.execute {
            try {
                Log.d(TAG, "bootstrapDaemon: START")
                if (daemon.isStarted) {
                    return@execute
                }
                daemon.startDaemon()

                // Check if the camera hardware feature is available.
                if (mDeviceRuntimeService.hasVideoPermission()) {
                    //initVideo is called here to give time to the application to initialize hardware cameras
                    Log.d(TAG, "bootstrapDaemon: At least one camera available. Initializing video...")
                    hardwareService.initVideo()
                            .onErrorComplete()
                            .subscribe()
                } else {
                    Log.d(TAG, "bootstrapDaemon: No camera available")
                }
                ringerModeChanged((getSystemService(AUDIO_SERVICE) as AudioManager).ringerMode)
                registerReceiver(ringerModeListener, RINGER_FILTER)

                // load accounts from Daemon
                mAccountService.loadAccountsFromDaemon(mPreferencesService.hasNetworkConnected())
                // Sync embedded HTTP servers
                (mExposedServicesService as? AndroidExposedServicesService)?.syncAllAccounts()
                // Recover tunnels the daemon kept alive across a process restart
                peerServicesService.refreshActiveTunnels()
                if (mPreferencesService.settings.enablePushNotifications) {
                    pushToken.let { token -> if (token != null) mAccountService.setPushNotificationConfig(token.first, token.second, pushPlatform) }
                } else {
                    JamiService.setPushNotificationToken("")
                }
                sendBroadcast(Intent(DRING_CONNECTION_CHANGED).apply {
                    putExtra("connected", daemon.isStarted)
                })
                scheduleRefreshJob()
            } catch (e: Exception) {
                Log.e(TAG, "DRingService start failed", e)
            }
        }
    }

    private fun scheduleRefreshJob() {
        Log.w(TAG, "JobScheduler: scheduling job")
        getSystemService(JobScheduler::class.java)
            .schedule(JobInfo.Builder(JamiJobService.JOB_ID, ComponentName(this, JamiJobService::class.java))
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(JamiJobService.JOB_INTERVAL, JamiJobService.JOB_FLEX)
                .build())
    }

    private fun terminateDaemon() {
        val stopResult = mExecutor.submit<Boolean> {
            unregisterReceiver(ringerModeListener)
            daemon.stopDaemon()
            val intent = Intent(DRING_CONNECTION_CHANGED)
            intent.putExtra("connected", daemon.isStarted)
            sendBroadcast(intent)
            true
        }
        try {
            stopResult.get()
            mExecutor.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "DRingService stop failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        LinkPreview.init(this)

        // Re-assert the user's chosen app language. Some ROMs (EMUI) don't keep the system
        // per-app locale across a sideload update, so we store the choice ourselves (UiPrefs) and
        // re-apply it here on every start — done in Application.onCreate so no activity flashes.
        cx.ring.utils.UiPrefs.getAppLanguage(this)?.let { tag ->
            val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (current != tag) androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                else androidx.core.os.LocaleListCompat.forLanguageTags(tag))
        }
        // One-time: clear a stale connection-dot colour left over from when STATUS_ONLINE/OFFLINE
        // meant the account online/offline icon (green/grey) rather than the dot's connected/
        // disconnected states (yellow/red). After this the new defaults show; re-customising sticks.
        if (cx.ring.utils.UiPrefs.needsDotColorMigration(this)) {
            cx.ring.utils.ColorPrefs.reset(this, cx.ring.utils.ColorPrefs.STATUS_ONLINE)
            cx.ring.utils.ColorPrefs.reset(this, cx.ring.utils.ColorPrefs.STATUS_OFFLINE)
            cx.ring.utils.UiPrefs.setDotColorMigrated(this)
        }

        // Launch logging if previously set up by user (info is stored in shared preferences).
        // Subscribe on it (first element) to initialize pipe construction.
        if (hardwareService.mPreferenceService.isLogActive)
            hardwareService.startLogs().firstElement().subscribe()

        if (!BuildConfig.DEBUG) {
            // Set a default exception handler for RxJava.
            // Most of these errors bubble up here because the original Rx flow was normally disposed, and can be
            // safely ignored. In some cases this might hide real bugs so we only do that in production.
            RxJavaPlugins.setErrorHandler { e -> Log.e(TAG, "Unhandled RxJava error", e) }
        }

        // Initialize the Android Telecom API if available
        if (Build.VERSION.SDK_INT >= CONNECTION_SERVICE_TELECOM_API_SDK_COMPATIBILITY) {
            Schedulers.computation().scheduleDirect {
                getSystemService<TelecomManager>()?.let { telecomService ->
                    try {
                        val componentName = ComponentName(this, ConnectionService::class.java)
                        val handle = PhoneAccountHandle(componentName, ConnectionService.HANDLE_ID)
                        //telecomService.unregisterPhoneAccount(handle)
                        telecomService.registerPhoneAccount(
                            PhoneAccount.Builder(handle, getString(R.string.app_name))
                                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                                //.setCapabilities(PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING)
                                .setHighlightColor(getColor(R.color.color_primary_dark))
                                .addSupportedUriScheme("ring")
                                .addSupportedUriScheme("jami")
                                .addSupportedUriScheme("swarm")
                                .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                                .build())
                        androidPhoneAccountHandle = handle
                        Log.d(TAG, "Registered Telecom API with handle $handle")
                    } catch (e: Exception) {
                        Log.e(TAG, "Can't register the Telecom API", e)
                    }
                }
            }
        }

        bootstrapDaemon()
        mPreferencesService.loadDarkMode()
        Completable.fromAction {
            val caRootFile = getString(R.string.ca_root_file)
            val dest = File(filesDir, caRootFile)
            AndroidFileUtils.copyAsset(assets, caRootFile, dest)
            Os.setenv("CA_ROOT_FILE", dest.absolutePath, true)

            val path = AndroidFileUtils.ringtonesPath(this)
            val defaultRingtone = File(path, getString(R.string.ringtone_default_name))
            val defaultLink = File(path, "default.opus")
            if (!defaultRingtone.exists()) {
                AndroidFileUtils.copyAssetFolder(assets, "ringtones", path)
            }
            if (!defaultLink.exists()) {
                AndroidFileUtils.linkOrCopy(defaultRingtone.absolutePath, defaultLink.absolutePath)
            }
        }
            .subscribeOn(Schedulers.io())
            .subscribe()
        setupActivityListener()
        registerBackgroundOptimization()
    }

    fun startDaemon(activityContext: Context) {
        if (!DRingService.isRunning) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && mPreferencesService.settings.enablePermanentService) {
                    startForegroundService(Intent(this, DRingService::class.java))
                } else {
                    startService(Intent(this, DRingService::class.java))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error starting daemon service")
            }
        }
        bindDaemon()
        activityInit(activityContext)
    }

    fun bindDaemon() {
        if (!mBound) {
            try {
                bindService(Intent(this, DRingService::class.java), mConnection, BIND_AUTO_CREATE or BIND_IMPORTANT or BIND_ABOVE_CLIENT)
            } catch (e: Exception) {
                Log.w(TAG, "Error binding daemon service")
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        terminateDaemon()
        instance = null
    }

    private fun setupActivityListener() {
        var startedActivityCount = 0
        val appInForeground = BehaviorSubject.createDefault(true)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
                if (mPreferencesService.settings.isRecordingBlocked) {
                    activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) appInForeground.onNext(true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = maxOf(0, startedActivityCount - 1)
                if (startedActivityCount == 0) appInForeground.onNext(false)
            }

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        Observable.combineLatest(
            appInForeground,
            peerServicesService.observeAnyActiveTunnel(),
            mExposedServicesService.observeHostingActive(),
        ) { inForeground, hasTunnels, hasHosting -> !inForeground && (hasTunnels || hasHosting) }
            .distinctUntilChanged()
            .switchMap { shouldRun ->
                if (shouldRun) Observable.just(true).delay(300, TimeUnit.MILLISECONDS)
                else Observable.just(false)
            }
            .subscribe { shouldRun ->
                if (shouldRun) {
                    try {
                        ContextCompat.startForegroundService(
                            this, Intent(this, PeerTunnelForegroundService::class.java)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot start tunnel foreground service: ${e.message}")
                    }
                } else {
                    stopService(Intent(this, PeerTunnelForegroundService::class.java))
                }
            }
    }
}