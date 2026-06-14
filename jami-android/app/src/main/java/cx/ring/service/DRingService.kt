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
package cx.ring.service

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.*
import android.net.ConnectivityManager.NetworkCallback
import android.os.*
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.RemoteInput
import cx.ring.BuildConfig
import cx.ring.application.JamiApplication
import cx.ring.client.CallActivity
import cx.ring.client.ConversationActivity
import cx.ring.utils.ConversationPath
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.disposables.CompositeDisposable
import net.jami.model.Conversation
import net.jami.model.Settings
import net.jami.services.*
import javax.inject.Inject
import javax.inject.Singleton


@AndroidEntryPoint
class DRingService : Service() {
    private val contactContentObserver = ContactsContentObserver()

    @Inject
    @Singleton
    lateinit var mDaemonService: DaemonService

    @Inject
    @Singleton
    lateinit var mCallService: CallService

    @Inject
    @Singleton
    lateinit var mAccountService: AccountService

    @Inject
    @Singleton
    lateinit var mHardwareService: HardwareService

    @Inject
    @Singleton
    lateinit var mHistoryService: HistoryService

    @Inject
    @Singleton
    lateinit var mDeviceRuntimeService: DeviceRuntimeService

    @Inject
    @Singleton
    lateinit var mNotificationService: NotificationService

    @Inject
    @Singleton
    lateinit var mContactService: ContactService

    @Inject
    @Singleton
    lateinit var mPreferencesService: PreferencesService

    @Inject
    @Singleton
    lateinit var mConversationFacade: ConversationFacade

    private val mHandler = Handler(Looper.myLooper()!!)
    private val mDisposableBag = CompositeDisposable()
    private val mConnectivityChecker = Runnable { updateConnectivityState() }

    // --- Connectivity resilience: survive network-change signals + reconnect when stale ---
    private var mLastNetworkKey: String? = null
    private var mNetworkPathChanged = false
    private var mLastForceReconnect = 0L
    private val mNetworkSettleRunnable = Runnable { onNetworkSettled() }
    private val mForceReconnectRunnable = Runnable {
        if (mCallService.hasActiveCall()) {
            Log.w(TAG, "skip force-reconnect: active call")
            return@Runnable
        }
        val now = SystemClock.elapsedRealtime()
        if (now - mLastForceReconnect < FORCE_RECONNECT_MIN_INTERVAL_MS) {
            Log.w(TAG, "skip force-reconnect: backoff")
            return@Runnable
        }
        mLastForceReconnect = now
        mAccountService.reconnectStaleAccounts(true)
    }
    private val mWatchdogRunnable = object : Runnable {
        override fun run() {
            runStaleWatchdog()
            mHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val monitor = object : NetworkCallback() {
        fun enable(context: Context) {
            val connectivityManager = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
            try {
                // Track the *default* network — the transport this app actually egresses
                // on (for a VPN-excluded app, the underlying physical network). This is
                // what lets us notice the path moving *under* an active VPN/WireGuard
                // tunnel, which onAvailable alone never reported.
                connectivityManager?.registerDefaultNetworkCallback(this)
            } catch (e: Exception) {
                Log.e(TAG, "Can't register network callback", e)
            }
        }

        fun disable(context: Context) {
            val connectivityManager =
                context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
            try {
                connectivityManager?.unregisterNetworkCallback(this)
            } catch (e: Exception) {
                Log.w(TAG, "Can't unregister network callback", e)
            }
        }

        override fun onAvailable(network: Network) {
            Log.w(TAG, "net onAvailable $network")
            onNetworkChanged(network)
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "net onLost $network")
            mLastNetworkKey = null
            scheduleNetworkSettle()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            onNetworkChanged(network, linkProperties.interfaceName)
        }
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == null) {
                Log.w(TAG, "onReceive: received a null action on broadcast receiver")
                return
            }
            Log.d(TAG, "receiver.onReceive: $action")
            when (action) {
                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    updateConnectivityState()
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    mConnectivityChecker.run()
                    mHandler.postDelayed(mConnectivityChecker, 100)
                }
                Intent.ACTION_SCREEN_ON -> {
                    runStaleWatchdog()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        isRunning = true
        if (mDeviceRuntimeService.hasContactPermission()) {
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactContentObserver)
        }
        val intentFilter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(receiver, intentFilter)
        updateConnectivityState()
        mDisposableBag.add(mPreferencesService.settingsSubject.subscribe { settings: Settings ->
            showSystemNotification(settings)
        })
        monitor.enable(this)
        mHandler.postDelayed(mWatchdogRunnable, WATCHDOG_INTERVAL_MS)
        JamiApplication.instance!!.apply {
            bindDaemon()
            bootstrapDaemon()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy()")
        unregisterReceiver(receiver)
        contentResolver.unregisterContentObserver(contactContentObserver)
        monitor.disable(this)
        mHandler.removeCallbacks(mWatchdogRunnable)
        mHandler.removeCallbacks(mNetworkSettleRunnable)
        mHandler.removeCallbacks(mForceReconnectRunnable)
        mHardwareService.unregisterCameraDetectionCallback()
        mDisposableBag.clear()
        isRunning = false
    }

    private fun showSystemNotification(settings: Settings) {
        try {
            if (settings.enablePermanentService) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, mNotificationService.serviceNotification as Notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
                } else {
                    startForeground(NOTIFICATION_ID, mNotificationService.serviceNotification as Notification)
                }
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Can't start or stop foreground service: ${e.message}")
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "Can't start or stop foreground service: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Log.i(TAG, "onStartCommand ${intent?.action} $flags $startId");
        if (intent != null) {
            parseIntent(intent)
        }
        return START_STICKY /* started and stopped explicitly */
    }

    private val binder: IBinder = Binder()
    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    /* ************************************
     *
     * Implement public interface for the service
     *
     * *********************************
     */
    private fun updateConnectivityState() {
        updateConnectivityState(mPreferencesService.hasNetworkConnected())
    }

    private fun updateConnectivityState(isConnected: Boolean) {
        if (mDaemonService.isStarted) {
            mAccountService.setAccountsActive(isConnected)
            mHardwareService.connectivityChanged(isConnected)
        }
    }

    /**
     * Called from the default-network callback. Builds a stable identity for the
     * current network path (network handle + interface name); a change means the
     * transport actually moved (Wi-Fi↔cell, roaming, or the path under a VPN/
     * WireGuard tunnel changing) and existing peer/swarm connections are now dead.
     * Coalesces bursts of callbacks via [scheduleNetworkSettle].
     */
    private fun onNetworkChanged(network: Network, iface: String? = null) {
        val name = iface ?: try {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?)
                ?.getLinkProperties(network)?.interfaceName
        } catch (e: Exception) { null }
        val key = "$network/${name ?: "?"}"
        if (key == mLastNetworkKey) return  // no real path change (capability/keepalive churn)
        Log.w(TAG, "network path changed: $mLastNetworkKey -> $key")
        mLastNetworkKey = key
        mNetworkPathChanged = true
        scheduleNetworkSettle()
    }

    private fun scheduleNetworkSettle() {
        mHandler.removeCallbacks(mNetworkSettleRunnable)
        mHandler.postDelayed(mNetworkSettleRunnable, NETWORK_DEBOUNCE_MS)
    }

    /**
     * Runs once the network has stopped flapping. Pushes current connectivity to
     * the daemon, and — if the transport path actually moved — force-reconnects
     * stale accounts so swarms rebuild instead of sitting dead until a manual toggle.
     */
    private fun onNetworkSettled() {
        val connected = mPreferencesService.hasNetworkConnected()
        val pathChanged = mNetworkPathChanged
        mNetworkPathChanged = false
        Log.w(TAG, "onNetworkSettled connected=$connected pathChanged=$pathChanged")
        updateConnectivityState(connected)
        if (connected && pathChanged) {
            // Let the daemon settle on the new path, then replay the manual toggle.
            mHandler.removeCallbacks(mForceReconnectRunnable)
            mHandler.postDelayed(mForceReconnectRunnable, TRANSITION_RECONNECT_DELAY_MS)
        }
    }

    /**
     * Periodic + screen-on safety net: re-register any account that has drifted out
     * of REGISTERED while the network is up (catches silent stalls with no further
     * network-change signal).
     */
    private fun runStaleWatchdog() {
        if (!mDaemonService.isStarted) return
        if (!mPreferencesService.hasNetworkConnected()) return
        if (mCallService.hasActiveCall()) return
        // Don't pile onto a transport-change reconnect that just ran — let it settle.
        if (SystemClock.elapsedRealtime() - mLastForceReconnect < FORCE_RECONNECT_MIN_INTERVAL_MS) return
        mAccountService.reconnectStaleAccounts(false)
    }

    private fun parseIntent(intent: Intent) {
        val action = intent.action ?: return
        val extras = intent.extras
        when (action) {
            ACTION_TRUST_REQUEST_ACCEPT, ACTION_TRUST_REQUEST_REFUSE, ACTION_TRUST_REQUEST_BLOCK ->
                handleTrustRequestAction(intent.data, action)
            ACTION_CALL_ACCEPT, ACTION_CALL_REFUSE, ACTION_CALL_END -> extras?.let {
                handleCallAction(action, it)
            }
            ACTION_CONV_READ, ACTION_CONV_ACCEPT, ACTION_CONV_DISMISS, ACTION_CONV_REPLY_INLINE ->
                handleConvAction(intent, action, extras)
            ACTION_FILE_ACCEPT, ACTION_FILE_CANCEL -> extras?.let {
                handleFileAction(intent.data, action, it)
            }
        }
    }

    private fun handleFileAction(uri: Uri?, action: String, extras: Bundle) {
        Log.w(TAG, "handleFileAction $extras")
        val messageId = extras.getString(KEY_MESSAGE_ID)
        val id = extras.getString(KEY_TRANSFER_ID)!!
        val path = ConversationPath.fromUri(uri)!!
        if (action == ACTION_FILE_ACCEPT) {
            mNotificationService.removeTransferNotification(path.accountId, path.conversationUri, id)
            mAccountService.acceptFileTransfer(path.accountId, path.conversationUri, messageId, id)
        } else if (action == ACTION_FILE_CANCEL) {
            mConversationFacade.cancelFileTransfer(path.accountId, path.conversationUri, messageId, id)
        }
    }

    private fun handleTrustRequestAction(uri: Uri?, action: String) {
        ConversationPath.fromUri(uri)?.let { path ->
            mNotificationService.cancelTrustRequestNotification(path.accountId)
            when (action) {
                ACTION_TRUST_REQUEST_ACCEPT -> mConversationFacade.startConversation(path.accountId, path.conversationUri).subscribe({ request ->
                    mConversationFacade.acceptRequest(request)
                }) {
                    mAccountService.acceptTrustRequest(path.accountId, path.conversationUri)
                }
                ACTION_TRUST_REQUEST_REFUSE -> mConversationFacade.discardRequest(path.accountId, path.conversationUri)
                ACTION_TRUST_REQUEST_BLOCK -> {
                    mConversationFacade.blockConversation(path.accountId, path.conversationUri)
                    mConversationFacade.discardRequest(path.accountId, path.conversationUri)
                }
                else -> {}
            }
        }
    }

    private fun handleCallAction(action: String, extras: Bundle) {
        val callId = extras.getString(NotificationService.KEY_CALL_ID) ?: return
        val accountId = extras.getString(ConversationPath.KEY_ACCOUNT_ID) ?: return
        if (callId.isEmpty() || accountId.isEmpty()) {
            return
        }
        when (action) {
            ACTION_CALL_ACCEPT -> {
                startActivity(Intent(ACTION_CALL_ACCEPT)
                        .putExtras(extras)
                        .setClass(applicationContext, CallActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ACTION_CALL_REFUSE -> {
                mCallService.refuse(accountId, callId)
                mHardwareService.closeAudioState()
            }
            ACTION_CALL_END -> {
                mCallService.hangUpAny(accountId, callId)
                mHardwareService.closeAudioState()
            }
        }
    }

    private fun handleConvAction(intent: Intent, action: String, extras: Bundle?) {
        val path = ConversationPath.fromIntent(intent)
        if (path == null || path.conversationId.isEmpty()) {
            return
        }
        when (action) {
            ACTION_CONV_READ -> mConversationFacade.readMessages(path.accountId, path.conversationUri)
            ACTION_CONV_DISMISS -> {
                extras?.getString(KEY_MESSAGE_ID)?.let { messageId ->
                    mConversationFacade.messageNotified(path.accountId, path.conversationUri, messageId)
                }
            }
            ACTION_CONV_REPLY_INLINE -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                if (remoteInput != null) {
                    val reply = remoteInput.getCharSequence(KEY_TEXT_REPLY)
                    if (!reply.isNullOrEmpty()) {
                        val uri = path.conversationUri
                        val message = reply.toString()
                        mConversationFacade.startConversation(path.accountId, uri)
                            .flatMapCompletable { c: Conversation ->
                                mConversationFacade.sendTextMessage(c, uri, message)
                                    .doOnComplete { mNotificationService.showTextNotification(c)}
                            }
                            .subscribe()
                    }
                }
            }
            ACTION_CONV_ACCEPT -> startActivity(Intent(Intent.ACTION_VIEW, path.toUri(), applicationContext, ConversationActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> {
            }
        }
    }

    fun refreshContacts() {
        if (mAccountService.currentAccount == null) {
            return
        }
        mContactService.loadContacts(
            mAccountService.hasJamiAccount(),
            mAccountService.hasSipAccount(),
            mAccountService.currentAccount
        )
    }

    private class ContactsContentObserver internal constructor() : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            //mContactService.loadContacts(mAccountService.hasRingAccount(), mAccountService.hasSipAccount(), mAccountService.getCurrentAccount());
        }
    }

    companion object {
        private val TAG = DRingService::class.java.simpleName

        // Connectivity resilience tuning.
        private const val NETWORK_DEBOUNCE_MS = 1500L            // coalesce callback bursts
        private const val TRANSITION_RECONNECT_DELAY_MS = 4000L  // settle on new path before re-register
        private const val FORCE_RECONNECT_MIN_INTERVAL_MS = 20_000L // backoff against flapping
        private const val WATCHDOG_INTERVAL_MS = 90_000L         // periodic stale-account safety net
        const val ACTION_TRUST_REQUEST_ACCEPT = BuildConfig.APPLICATION_ID + ".action.TRUST_REQUEST_ACCEPT"
        const val ACTION_TRUST_REQUEST_REFUSE = BuildConfig.APPLICATION_ID + ".action.TRUST_REQUEST_REFUSE"
        const val ACTION_TRUST_REQUEST_BLOCK = BuildConfig.APPLICATION_ID + ".action.TRUST_REQUEST_BLOCK"
        const val ACTION_CALL_ACCEPT = BuildConfig.APPLICATION_ID + ".action.CALL_ACCEPT"
        const val ACTION_CALL_REFUSE = BuildConfig.APPLICATION_ID + ".action.CALL_REFUSE"
        const val ACTION_CALL_END = BuildConfig.APPLICATION_ID + ".action.CALL_END"
        const val ACTION_CONV_READ = BuildConfig.APPLICATION_ID + ".action.CONV_READ"
        const val ACTION_CONV_DISMISS = BuildConfig.APPLICATION_ID + ".action.CONV_DISMISS"
        const val ACTION_CONV_ACCEPT = BuildConfig.APPLICATION_ID + ".action.CONV_ACCEPT"
        const val ACTION_CONV_REPLY_INLINE = BuildConfig.APPLICATION_ID + ".action.CONV_REPLY"
        const val ACTION_FILE_ACCEPT = BuildConfig.APPLICATION_ID + ".action.FILE_ACCEPT"
        const val ACTION_FILE_CANCEL = BuildConfig.APPLICATION_ID + ".action.FILE_CANCEL"
        const val KEY_MESSAGE_ID = "messageId"
        const val KEY_TRANSFER_ID = "transferId"
        const val KEY_TEXT_REPLY = "textReply"
        private const val NOTIFICATION_ID = 1
        var isRunning = false
    }
}