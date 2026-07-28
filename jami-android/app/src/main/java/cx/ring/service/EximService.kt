/*
 *  shiroikuma.jami fork — foreground service for backup export/import (2026-07-28).
 *
 *  A chat backup can run to gigabytes and take minutes. Left on a plain background thread it dies
 *  the moment EMUI decides the app is idle, halfway through a zip. This keeps the process alive
 *  and shows what is happening; the work itself lives in EximJob, so cancelling the notification
 *  never orphans a half-written archive.
 */
package cx.ring.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import cx.ring.R
import cx.ring.client.HomeActivity
import cx.ring.utils.ContentUri

class EximService : Service() {

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stop()
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "白い熊 GNU Jami"
                val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
                try {
                    val n = build(title, text)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    else
                        startForeground(NOTIF_ID, n)
                } catch (e: Exception) {
                    Log.e(TAG, "startForeground failed", e)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stop() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (_: IllegalStateException) {
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout: startId=$startId, fgsType=$fgsType")
        stop()
    }

    private fun build(title: String, text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.sk_exim_notif_channel),
                NotificationManager.IMPORTANCE_LOW))
        val open = Intent(Intent.ACTION_VIEW)
            .setClass(applicationContext, HomeActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_ring_logo_white)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(PendingIntent.getActivity(
                applicationContext, 0, open, ContentUri.immutable()))
            .build()
    }

    companion object {
        private const val TAG = "SK-EXIMSVC"
        private const val CHANNEL = "shiroikuma_exim"
        private const val NOTIF_ID = 1071
        const val ACTION_START = "cx.ring.exim.START"
        const val ACTION_STOP = "cx.ring.exim.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        /** Starts the service, or refreshes its notification text when it is already up. */
        fun show(c: Context, title: String, text: String) {
            runCatching {
                c.startForegroundService(Intent(c, EximService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_TEXT, text))
            }.onFailure { Log.w(TAG, "could not show progress: ${it.message}") }
        }

        fun hide(c: Context) {
            runCatching {
                c.startService(Intent(c, EximService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
