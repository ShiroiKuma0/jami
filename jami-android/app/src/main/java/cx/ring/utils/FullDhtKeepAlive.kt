/*
 *  Copyright (C) 2026 白い熊 (ShiroiKuma0)
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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import cx.ring.service.FullDhtKeepAliveReceiver
import io.reactivex.rxjava3.disposables.Disposable
import net.jami.services.AccountService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Keeps a phone that runs the FULL DHT present on the network while the system would let it sleep.
 *
 * A full-DHT node has no push leg: everything it owes the network is a timer of its own — the
 * refresh of its presence announcement ten seconds before the storing nodes drop it, the renewal of
 * its listeners every thirty seconds. Those timers stand still while the CPU is suspended, and a
 * phone suspends even on a charger once the battery is full or held at its protection limit.
 * Measured 2026-11-27: a full-DHT phone on a charger, awake 67 % of the time, was missing from its
 * own presence key while its DHT said "connected", and its contacts saw it offline.
 *
 * Two regimes, by where the energy comes from:
 *  - **Powered** — a partial wake lock for as long as the phone is plugged in. The CPU never
 *    suspends, so every DHT timer runs on time. Costs nothing that matters on external power.
 *  - **On battery** — no wake lock. A timer wakes the phone every [TICK_MS] and has the daemon
 *    renew the presence announcement (daemon patch SK-REANNOUNCE), inside every ten-minute expiry
 *    window. The phone still sleeps in between, so it is found, and a peer's connection request is
 *    answered at the next wake (the caller redials on a late answer, SK-LATEANSWER).
 *
 * Nothing here applies to an account on a DHT proxy: the proxy keeps its announcement alive and
 * push wakes it. With no full-DHT account the whole thing is off.
 */
object FullDhtKeepAlive {
    private const val TAG = "FullDhtKeepAlive"

    /** Below the ten minutes after which a DHT node drops a value, and below the nine minutes to
     *  which doze spaces an app's alarms — at nine the refresh still lands inside the window. */
    private const val TICK_MS = 8 * 60_000L

    /** How long one tick keeps the CPU awake: the announcement needs a few network round trips. */
    private const val TICK_AWAKE_MS = 15_000L

    /** The account list does not announce every proxy switch, so the state is also re-read on a
     *  plain handler. It never wakes the phone. */
    private const val RECHECK_MS = 10 * 60_000L

    /** For this long after a plug or unplug event the event is believed over the battery state. */
    private const val POWER_SETTLE_MS = 10_000L

    private enum class Mode { OFF, WAKE_LOCK, TIMER }

    private var app: Context? = null
    private var accounts: AccountService? = null
    private var mode = Mode.OFF
    private var wakeLock: PowerManager.WakeLock? = null
    private var ticks = 0
    private var listSubscription: Disposable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recheck = object : Runnable {
        override fun run() {
            evaluate("recheck")
            handler.postDelayed(this, RECHECK_MS)
        }
    }
    private val stampFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The event itself is the answer. The battery state the system keeps for readers lags
            // behind it: read right now it still says "on battery" after a plug-in, and the wake
            // lock then came back only with the next recheck (measured 2026-11-28, both Slaves).
            synchronized(this@FullDhtKeepAlive) {
                poweredHint = intent.action == Intent.ACTION_POWER_CONNECTED
                poweredHintUntil = SystemClock.elapsedRealtime() + POWER_SETTLE_MS
            }
            evaluate("power")
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, POWER_SETTLE_MS + 1_000L)
        }
    }

    /** Once the battery state has caught up with a power event, it is read again and decides. */
    private val settle = Runnable { evaluate("power settled") }
    private var poweredHint = false
    private var poweredHintUntil = 0L

    /** Called once the daemon and its accounts are up. Safe to call again. */
    @Synchronized
    fun start(context: Context, accountService: AccountService) {
        if (app != null) {
            evaluate("restart")
            return
        }
        val c = context.applicationContext
        app = c
        accounts = accountService
        ContextCompat.registerReceiver(
            c, powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        listSubscription = accountService.observableAccountList
            .debounce(2, TimeUnit.SECONDS)
            .subscribe({ evaluate("accounts") }, { Log.w(TAG, "account list", it) })
        handler.postDelayed(recheck, RECHECK_MS)
        evaluate("start")
    }

    /** The timer fired ([FullDhtKeepAliveReceiver]): renew every full-DHT account's announcement. */
    fun onTick(context: Context) {
        val svc = accounts
        if (svc == null) {
            // The process was started by the alarm alone; the daemon is not up. start() re-arms.
            return
        }
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shiroikuma:full-dht-tick").acquire(TICK_AWAKE_MS)
        } catch (e: Exception) {
            Log.w(TAG, "tick wake lock", e)
        }
        val ids = try { svc.fullDhtAccountIds() } catch (e: Exception) { emptyList() }
        for (id in ids) svc.reannouncePresence(id)
        synchronized(this) { ticks++ }
        evaluate("tick")
    }

    private fun isPowered(c: Context): Boolean {
        if (SystemClock.elapsedRealtime() < poweredHintUntil) return poweredHint
        val battery = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Plugged in, not "charging": a phone held at its protection limit is plugged in and draws
        // nothing from the battery, and that is exactly when it suspends on the charger.
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    @Synchronized
    private fun evaluate(why: String) {
        val c = app ?: return
        val svc = accounts ?: return
        val fullDht = try { svc.fullDhtAccountIds().size } catch (e: Exception) { 0 }
        val wanted = when {
            fullDht == 0 -> Mode.OFF
            isPowered(c) -> Mode.WAKE_LOCK
            else -> Mode.TIMER
        }
        if (wanted != mode) {
            val was = mode
            mode = wanted
            when (wanted) {
                Mode.WAKE_LOCK -> { cancelTimer(c); holdWakeLock(c) }
                Mode.TIMER -> { releaseWakeLock(); armTimer(c) }
                Mode.OFF -> { releaseWakeLock(); cancelTimer(c) }
            }
            log(c, "SK-KEEPALIVE full DHT keep-alive: $was → $wanted ($why; full-DHT accounts=$fullDht, ticks so far=$ticks)")
        } else if (mode == Mode.TIMER && why == "tick") {
            armTimer(c)   // one-shot alarms: every tick arms the next
        }
    }

    private fun holdWakeLock(c: Context) {
        try {
            val lock = wakeLock ?: (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shiroikuma:full-dht")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            if (!lock.isHeld) lock.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock release", e)
        }
    }

    private fun timerIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 0, Intent(c, FullDhtKeepAliveReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun armTimer(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = SystemClock.elapsedRealtime() + TICK_MS
        val pi = timerIntent(c)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun cancelTimer(c: Context) {
        try {
            (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(timerIntent(c))
        } catch (e: Exception) {
            Log.w(TAG, "cancel timer", e)
        }
    }

    private fun log(c: Context, line: String) {
        try {
            UiPrefs.appendRecoveryLog(c, "${stampFormat.format(Date())}  $line")
        } catch (e: Exception) {
            Log.w(TAG, line)
        }
    }
}
