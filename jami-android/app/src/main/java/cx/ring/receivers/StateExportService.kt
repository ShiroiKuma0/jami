/*
 *  shiroikuma.jami fork — where the 保存復元 automation export actually RUNS (2026-07-28).
 *
 *  It used to run inside StateExportReceiver: goAsync() plus a bare Thread. goAsync() does not
 *  extend the broadcast window — a manifest receiver must reach finish() within ~10 s in the
 *  foreground and ~60 s otherwise — so a multi-gigabyte export was killed mid-write every time,
 *  at a different byte each run, leaving truncated archives that look exactly like real backups.
 *  Battery exemption is irrelevant: the deadline is the broadcast contract, not power management.
 *  Worse, once the PendingResult was abandoned the process held no foreground component at all
 *  while doing gigabytes of I/O.
 *
 *  So the receiver now only validates and starts this service, which holds the foreground state and
 *  a wakelock for the whole run. The export itself is EximRunner — the same code the in-app panel
 *  calls, never duplicated.
 */
package cx.ring.receivers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import cx.ring.R
import cx.ring.utils.ChatArchive
import cx.ring.utils.EximRunner
import cx.ring.utils.SettingsExport
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StateExportService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeat: ScheduledExecutorService? = null

    @Volatile private var runner: EximRunner? = null

    /** A run is in flight. Distinct from [runner], which is only set once the export proper begins —
     *  a cancel arriving in between must still be honoured, not dropped. */
    @Volatile private var busy = false
    @Volatile private var cancelRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_CANCEL) {
            // Stop the work; the worker thread unwinds, deletes the partial file and sends its own
            // terminal reply, so the service is not torn down from under it here.
            cancelRequested = true
            runner?.cancel()
            // A cancel with nothing running is a no-op by design — 自由作業盤 fires it whenever
            // 白い熊 presses 中止, without knowing how far we got. Do not go foreground for it, and
            // do not linger as a created-but-idle service.
            if (!busy) stopSelf(startId)
            return START_NOT_STICKY
        }
        // Foreground within 5 s of starting, or the system kills us for exactly the reason we are
        // here to avoid.
        startForegroundNow()

        val path = intent.getStringExtra("path")
        val items = intent.getStringExtra("items")
        val progressAction = intent.getStringExtra("progress_action")
        val replyAction = intent.getStringExtra("reply_action").orEmpty()
        val replyPackage = intent.getStringExtra("reply_package").orEmpty()
        val replyId = intent.getStringExtra("reply_id").orEmpty()

        busy = true
        Thread {
            val replied = AtomicBoolean(false)
            fun reply(result: String) {
                if (!replied.compareAndSet(false, true)) return
                Log.e(TAG, "reply [$replyId]: ${result.take(160)}")
                if (replyAction.isNotEmpty() && replyPackage.isNotEmpty()) {
                    sendBroadcast(Intent(replyAction).apply {
                        setPackage(replyPackage)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra("reply_id", replyId)
                        putExtra("result", result)
                    })
                }
            }
            try {
                reply(runExport(path, items, progressAction, replyPackage, replyId))
            } catch (e: Throwable) {
                Log.e(TAG, "state export failed", e)
                reply("ERROR:${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                cancelRequested = false
                runner = null
                heartbeat?.shutdownNow()
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                stopSelf(startId)
            }
        }.apply { name = "state-export"; priority = Thread.NORM_PRIORITY }.start()

        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.sk_exim_notif_channel),
                NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.sk_exim_notif_export))
            .setContentText("…")
            .setSmallIcon(R.drawable.ic_ring_logo_white)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.sk_exim_stop), android.app.PendingIntent.getService(
                this, 2, Intent(this, StateExportService::class.java).setAction(ACTION_CANCEL),
                cx.ring.utils.ContentUri.immutable()))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else
                startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        // This runs for many minutes with the screen off.
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shiroikuma:state-export")
                .also { it.acquire(60 * 60 * 1000L) }
        }
    }

    /** Same categories, same engine, same archives as the panel — only the caller differs. */
    private fun runExport(
        path: String?, items: String?, progressAction: String?,
        replyPackage: String, replyId: String,
    ): String {
        val cats: List<SettingsExport.Cat> = if (items.isNullOrBlank()) {
            SettingsExport.defaultHeadlessCats()
        } else {
            val byId = SettingsExport.Cat.entries.associateBy { it.id }
            val ids = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (ids.any { byId[it] == null }) return "ERROR:unknown category in items: $items"
            ids.map { byId.getValue(it) }
        }

        var lastProgress = 0L
        fun progress(
            cur: Long, total: Long, label: String, unit: String,
            item: String = "", bytes: Long = 0, bytesTotal: Long = 0, force: Boolean = false,
        ) {
            if (progressAction.isNullOrEmpty()) return
            val now = System.currentTimeMillis()
            if (!force && now - lastProgress < 500) return   // ≥500 ms apart, final always sent
            lastProgress = now
            sendBroadcast(Intent(progressAction).apply {
                setPackage(replyPackage)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("reply_id", replyId)
                putExtra("app", "白い熊 GNU Jami")
                putExtra("text", "$label $cur/$total")
                putExtra("current", cur)
                putExtra("total", total)
                putExtra("unit", unit)
                putExtra("item", item)
                putExtra("bytes", bytes)
                putExtra("bytes_total", bytesTotal)
            })
        }

        val accounts = cx.ring.application.JamiApplication.instance?.mAccountService
            ?: return "ERROR:app not initialized"
        if (SettingsExport.Cat.ACCOUNTS in cats) {
            progress(0, cats.size.toLong(), "区分", "区分",
                item = SettingsExport.Cat.ACCOUNTS.id, force = true)
            val deadline = System.currentTimeMillis() + 15_000
            while (accounts.getAccounts().isEmpty() && System.currentTimeMillis() < deadline)
                Thread.sleep(250)
        }

        val runner = EximRunner(this, accounts) { p ->
            if (p.totalFiles > 0)
                progress(p.files.toLong(), p.totalFiles.toLong(), p.phase, "ファイル",
                    item = p.itemId, bytes = p.bytes, bytesTotal = p.totalBytes)
            else
                progress(0, cats.size.toLong(), p.phase, "区分", item = p.itemId)
        }

        // 自由作業盤's watchdog fails any app that goes 180 s without its numbers changing. A single
        // 189 MB video takes longer than that to copy, and the per-file callback says nothing while
        // it does — so re-send the current figures on a timer regardless of file boundaries.
        heartbeat = Executors.newSingleThreadScheduledExecutor().also { ex ->
            ex.scheduleWithFixedDelay({
                runCatching {
                    val p = runner.progress
                    progress(p.files.toLong(), p.totalFiles.toLong(), p.phase, "ファイル",
                        item = p.itemId, bytes = p.bytes, bytesTotal = p.totalBytes, force = true)
                }
            }, 20, 20, TimeUnit.SECONDS)
        }

        val overrideDir = if (!path.isNullOrEmpty()) File(path) else null
        val target = runner.openExportTarget(overrideDir)
            ?: return if (path.isNullOrEmpty() && SettingsExport.getExportDir(this) == null)
                "ERROR:no-directory" else "ERROR:no-storage-access"

        this.runner = runner
        // A cancel that arrived while accounts were still loading must not be lost.
        if (cancelRequested) runner.cancel()
        val report = try {
            runner.exportTo(cats, target)
        } catch (e: ChatArchive.CancelledException) {
            return "ERROR:cancelled"
        }
        progress(1, 1, "書き込み", "区分", force = true)
        report.failure?.let { return "ERROR:$it" }

        // exportTo() renamed the .part into place, so the final name is what the caller gets told.
        val written = File(target.label)
        val size = written.length()
        return "OK:${written.absolutePath}|$size|${ChatArchive.human(size)}|${cats.size} categories"
    }

    companion object {
        private const val TAG = "SK-EXPORTSVC"
        private const val CHANNEL = "shiroikuma_exim"
        private const val NOTIF_ID = 1072
        const val ACTION_CANCEL = "cx.ring.stateexport.CANCEL"
    }
}
