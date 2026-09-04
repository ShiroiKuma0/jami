package cx.ring.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import cx.ring.R
import cx.ring.application.JamiApplication
import cx.ring.utils.ChatArchive
import cx.ring.utils.EximRunner
import cx.ring.utils.SettingsExport
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a data-door export or import actually runs (sister-app contract v2 §2a).
 *
 * ## Why a foreground service and not the provider call
 *
 * The call returns in milliseconds; this runs for minutes. A binder call holds the caller — 応用管理
 * is drawing a list, and a multi-minute synchronous call would freeze its UI, report no progress and
 * refuse cancellation. And a backgrounded app writing for minutes is frozen mid-stream on this
 * phone, which yields a truncated archive underneath a success reply: the worst failure available,
 * because it is indistinguishable from a good backup until the day it is restored. This fork already
 * paid for that lesson once in [cx.ring.receivers.StateExportService] (2026-07-28).
 *
 * ## The descriptor
 *
 * Already duplicated by [AutomationProvider] before it got here, because the original belongs to the
 * binder transaction and is closed the moment `call()` returns. This service owns the copy and
 * closes it in a `finally`: a leaked descriptor holds the caller's file open, and a caller cannot
 * checksum or encrypt a file that is still open.
 *
 * ## Same engine as everything else
 *
 * The work is [EximRunner] — the very code the in-app Export/Import panel and the §1 broadcast
 * service call. Never duplicated, so the three doors cannot drift apart.
 */
class AutomationDataService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeat: ScheduledExecutorService? = null
    @Volatile private var runner: EximRunner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(EXTRA_JOB) ?: return stop(startId)
        val fd = HANDOVER.remove(jobId) ?: return stop(startId)
        val importing = intent.getBooleanExtra(EXTRA_IMPORTING, false)
        val items = intent.getStringExtra(AutomationProvider.KEY_ITEMS)
        val progressAction = intent.getStringExtra(AutomationProvider.KEY_PROGRESS_ACTION)
        val replyAction = intent.getStringExtra(AutomationProvider.KEY_REPLY_ACTION)
        val replyPackage = intent.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE)

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            // Exactly one terminal answer per job, whatever path got here — a synchronous failure
            // and an asynchronous success must never both fire.
            if (!replied.compareAndSet(false, true)) return
            Log.e(TAG, "reply [$jobId]: ${result.take(160)}")
            AutomationJobs.finish(jobId)
            if (replyAction.isNullOrEmpty() || replyPackage.isNullOrEmpty()) return
            sendBroadcast(Intent(replyAction).apply {
                setPackage(replyPackage)
                // Without this a backgrounded caller never hears the answer, and on a clean phone
                // the caller may not have been launched at all.
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(AutomationProvider.KEY_JOB_ID, jobId)
                // The job id doubles as the correlation id so ONE progress reader serves both doors.
                putExtra("reply_id", jobId)
                putExtra(AutomationProvider.KEY_RESULT, result)
            })
        }

        // AFTER `reply` exists, and guarded. `startForeground` throws when the declared
        // foregroundServiceType disagrees with the manifest, and on API 31+ it can be refused
        // outright for a service started from the background — which a provider call() always is.
        // The descriptor has already left HANDOVER by this point, so nothing else would ever close
        // it, and a throw out of onStartCommand would kill the service with the caller still
        // waiting for an answer it will never get.
        if (!startForegroundNow(importing)) {
            runCatching { fd.close() }
            reply("ERROR:cannot go foreground")
            return stop(startId)
        }

        Thread {
            try {
                fd.use { open ->
                    if (importing) reply(runImport(jobId, open, progressAction, replyPackage))
                    else reply(runExport(jobId, open, items, progressAction, replyPackage))
                }
            } catch (e: ChatArchive.CancelledException) {
                reply("ERROR:cancelled")
            } catch (t: Throwable) {
                Log.e(TAG, "automation data job failed", t)
                reply("ERROR:${t.message ?: t.javaClass.simpleName}")
            } finally {
                runner = null
                heartbeat?.shutdownNow()
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }.apply { name = "automation-data"; priority = Thread.NORM_PRIORITY }.start()

        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- export

    /**
     * Write the archive straight into the caller's descriptor.
     *
     * [EximRunner.exportInto] already takes an arbitrary `OutputStream`, so the descriptor needs no
     * special path through the engine. `verifyTarget` is null deliberately: verification re-reads
     * the written file, and this one belongs to the caller — it may be an anonymous pipe or a
     * descriptor into a directory this app cannot even list. 応用管理 checksums it on its own side,
     * which is where the file actually lives.
     */
    private fun runExport(
        jobId: String, fd: ParcelFileDescriptor, items: String?,
        progressAction: String?, replyPackage: String?,
    ): String {
        val cats = resolve(items) ?: return "ERROR:unknown category in items: $items"
        val accounts = JamiApplication.instance?.mAccountService ?: return "ERROR:app not initialized"
        val send = progressSender(jobId, progressAction, replyPackage)

        if (SettingsExport.Cat.ACCOUNTS in cats) {
            send(0, cats.size.toLong(), "区分", "区分", SettingsExport.Cat.ACCOUNTS.id, 0, 0, true)
            val deadline = System.currentTimeMillis() + 15_000
            while (accounts.getAccounts().isEmpty() && System.currentTimeMillis() < deadline)
                Thread.sleep(250)
        }

        val run = EximRunner(this, accounts) { p ->
            if (p.totalFiles > 0)
                send(p.files.toLong(), p.totalFiles.toLong(), p.phase, "ファイル", p.itemId,
                    p.bytes, p.totalBytes, false)
            else send(0, cats.size.toLong(), p.phase, "区分", p.itemId, 0, 0, false)
        }
        runner = run
        startHeartbeat(run, cats.size, send)
        if (AutomationJobs.isCancelled(jobId)) run.cancel()

        var written = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            // Counted as it goes rather than stat'ed afterwards: the caller owns the file and we may
            // not be able to see it at all.
            val counting = object : OutputStream() {
                override fun write(b: Int) { out.write(b); written++ }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len); written += len
                }
                override fun flush() = out.flush()
            }
            val report = run.exportInto(cats, counting, verifyTarget = null)
            report.failure?.let { return "ERROR:$it" }
        }
        if (AutomationJobs.isCancelled(jobId)) return "ERROR:cancelled"
        send(1, 1, "書き込み", "区分", "", written, written, true)
        return "OK:$written|${ChatArchive.human(written)}|${cats.size} categories"
    }

    // ---------------------------------------------------------------- import

    /**
     * Spool the descriptor to a cache file, then import from there.
     *
     * **To disk, never into memory.** Reading the whole archive into a byte array to sniff it is
     * fine for a settings ZIP and fatal here: a Jami backup carries the chat corpus and every
     * attachment 白い熊 has ever received, which is measured in gigabytes. The guarantee is
     * unchanged — nothing is written until the whole archive has arrived and been read — only the
     * bound moves from RAM to disk.
     */
    private fun runImport(
        jobId: String, fd: ParcelFileDescriptor,
        progressAction: String?, replyPackage: String?,
    ): String {
        val accounts = JamiApplication.instance?.mAccountService ?: return "ERROR:app not initialized"
        val send = progressSender(jobId, progressAction, replyPackage)
        val spool = File(cacheDir, "automation-import-$jobId.zip")
        try {
            send(0, 1, "受け取り", "区分", "", 0, 0, true)
            var got = 0L
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                spool.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (AutomationJobs.isCancelled(jobId)) return "ERROR:cancelled"
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        send(0, 1, "受け取り", "区分", "", got, 0, false)
                    }
                }
            }
            if (got == 0L) return "ERROR:empty archive"

            val src = SettingsExport.openSource(spool)
            // Every category the archive actually carries, not every category we know about: asking
            // for one the archive lacks is how a restore ends up reporting success over nothing.
            val present = SettingsExport.categoriesIn(src)
            if (present.isEmpty()) return "ERROR:archive carries no categories"

            val deadline = System.currentTimeMillis() + 15_000
            while (accounts.getAccounts().isEmpty() && System.currentTimeMillis() < deadline)
                Thread.sleep(250)

            val run = EximRunner(this, accounts) { p ->
                if (p.totalFiles > 0)
                    send(p.files.toLong(), p.totalFiles.toLong(), p.phase, "ファイル", p.itemId,
                        p.bytes, p.totalBytes, false)
                else send(0, present.size.toLong(), p.phase, "区分", p.itemId, 0, 0, false)
            }
            runner = run
            startHeartbeat(run, present.size, send)
            if (AutomationJobs.isCancelled(jobId)) run.cancel()

            val report = run.runImport(src, present)
            report.failure?.let { return "ERROR:$it" }
            send(1, 1, "完了", "区分", "", got, got, true)
            // 応用管理 force-stops us straight after this, deliberately and on its side: a running
            // process writes its cached SharedPreferences back out at orderly shutdown and would
            // silently undo the import that just happened.
            return "OK:${report.lines.size} restored|${present.size} categories"
        } finally {
            spool.delete()
        }
    }

    // ---------------------------------------------------------------- shared

    private fun resolve(items: String?): List<SettingsExport.Cat>? {
        if (items.isNullOrBlank()) return SettingsExport.defaultHeadlessCats()
        val byId = SettingsExport.Cat.entries.associateBy { it.id }
        val ids = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.any { byId[it] == null }) return null
        return ids.map { byId.getValue(it) }
    }

    /**
     * §3 progress, throttled to one every 500 ms with the final one always sent.
     *
     * The correlation id is the **job id**, set in both `job_id` and `reply_id`, so one progress
     * reader on 自由作業盤's side serves the broadcast door and this one alike.
     */
    private fun progressSender(
        jobId: String, progressAction: String?, replyPackage: String?,
    ): (Long, Long, String, String, String, Long, Long, Boolean) -> Unit {
        var last = 0L
        return { cur, total, label, unit, item, bytes, bytesTotal, force ->
            if (!progressAction.isNullOrEmpty() && !replyPackage.isNullOrEmpty()) {
                val now = System.currentTimeMillis()
                if (force || now - last >= 500) {
                    last = now
                    sendBroadcast(Intent(progressAction).apply {
                        setPackage(replyPackage)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra("reply_id", jobId)
                        putExtra(AutomationProvider.KEY_JOB_ID, jobId)
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
            }
        }
    }

    /**
     * The heartbeat, kept from the §1 service and required by the same watchdog.
     *
     * 自由作業盤 fails any app that goes quiet, and a single 189 MB attachment takes longer to copy
     * than that window while the per-file callback says nothing — so re-send the current figures on
     * a timer regardless of file boundaries.
     */
    private fun startHeartbeat(
        run: EximRunner, catCount: Int,
        send: (Long, Long, String, String, String, Long, Long, Boolean) -> Unit,
    ) {
        heartbeat = Executors.newSingleThreadScheduledExecutor().also { ex ->
            ex.scheduleWithFixedDelay({
                runCatching {
                    val p = run.progress
                    if (p.totalFiles > 0)
                        send(p.files.toLong(), p.totalFiles.toLong(), p.phase, "ファイル", p.itemId,
                            p.bytes, p.totalBytes, true)
                    else send(0, catCount.toLong(), p.phase, "区分", p.itemId, 0, 0, true)
                }
            }, 20, 20, TimeUnit.SECONDS)
        }
    }

    /** @return false when the system refused the foreground start; the caller then answers and stops. */
    private fun startForegroundNow(importing: Boolean): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.sk_exim_notif_channel),
                NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(
                if (importing) R.string.sk_exim_notif_import else R.string.sk_exim_notif_export))
            .setContentText("…")
            .setSmallIcon(R.drawable.ic_ring_logo_white)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else
                startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            return false
        }
        // Gigabytes of I/O with the screen off.
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shiroikuma:automation-data")
                .also { it.acquire(60 * 60 * 1000L) }
        }
        return true
    }

    private fun stop(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "SK-AUTODATA"
        private const val CHANNEL = "shiroikuma_exim"
        private const val NOTIF_ID = 1073
        private const val EXTRA_JOB = "job"
        private const val EXTRA_IMPORTING = "importing"

        /**
         * The descriptor's way across, because an Intent is the wrong vehicle for one.
         *
         * A `ParcelFileDescriptor` in an Intent extra is duplicated by the system on delivery and
         * the copy's lifetime stops being ours to reason about. A map keyed by job id keeps exactly
         * one open descriptor with exactly one owner — this service, which closes it in a `finally`.
         */
        private val HANDOVER = java.util.concurrent.ConcurrentHashMap<String, ParcelFileDescriptor>()

        /** Drop a handover whose service never started, so the entry cannot strand the caller's file. */
        fun abandon(jobId: String) {
            HANDOVER.remove(jobId)
        }

        /** Throws if the foreground start is refused; [AutomationProvider] closes the dup and answers. */
        fun start(
            context: Context, jobId: String, fd: ParcelFileDescriptor,
            importing: Boolean, extras: Bundle?,
        ) {
            HANDOVER[jobId] = fd
            context.startForegroundService(
                Intent(context, AutomationDataService::class.java).apply {
                    putExtra(EXTRA_JOB, jobId)
                    putExtra(EXTRA_IMPORTING, importing)
                    putExtra(AutomationProvider.KEY_ITEMS,
                        extras?.getString(AutomationProvider.KEY_ITEMS))
                    putExtra(AutomationProvider.KEY_PROGRESS_ACTION,
                        extras?.getString(AutomationProvider.KEY_PROGRESS_ACTION))
                    putExtra(AutomationProvider.KEY_REPLY_ACTION,
                        extras?.getString(AutomationProvider.KEY_REPLY_ACTION))
                    putExtra(AutomationProvider.KEY_REPLY_PACKAGE,
                        extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE))
                },
            )
        }
    }
}
