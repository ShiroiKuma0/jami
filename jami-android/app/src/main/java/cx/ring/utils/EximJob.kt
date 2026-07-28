package cx.ring.utils

import android.content.Context
import android.util.Log
import cx.ring.service.EximService
import net.jami.services.AccountService

/**
 * Runs one export/import at a time, off the UI thread and under a foreground service.
 *
 * The panel observes it rather than owning it, so rotating the phone or closing the dialog cannot
 * kill a running backup — and re-opening the panel finds the job still going.
 */
object EximJob {

    private const val TAG = "SK-EXIM"

    fun interface Listener {
        /** [done] arrives exactly once; [report] and [error] are only set then. */
        fun onUpdate(line: String, done: Boolean, report: EximRunner.Report?, error: String?)
    }

    @Volatile private var listener: Listener? = null

    @Volatile var running = false
        private set

    @Volatile var lastLine = ""
        private set

    @Volatile private var active: EximRunner? = null

    /** Stops the running job. The write loops notice within one file and unwind; the partial
     *  archive is deleted by the runner's own finally, so nothing half-written survives. */
    fun cancel() {
        active?.cancel()
    }

    fun observe(l: Listener?) { listener = l }

    /**
     * Starts [work] on its own thread. Returns false when a job is already running — two
     * concurrent imports over the same directories is the one thing this must never allow.
     */
    fun start(
        app: Context,
        accounts: AccountService,
        title: String,
        work: (EximRunner) -> EximRunner.Report,
    ): Boolean {
        synchronized(this) {
            if (running) return false
            running = true
        }
        lastLine = ""
        EximService.show(app, title, "…")
        Thread {
            var report: EximRunner.Report? = null
            var error: String? = null
            try {
                val runner = EximRunner(app, accounts) { p ->
                    lastLine = p.line()
                    EximService.show(app, title, lastLine)
                    listener?.onUpdate(lastLine, false, null, null)
                }
                active = runner
                report = runner.runCatchingReport(work)
                error = report.failure
            } catch (e: Throwable) {
                Log.e(TAG, "$title failed", e)
                error = e.message ?: e.javaClass.simpleName
            } finally {
                active = null
                running = false
                EximService.hide(app)
                listener?.onUpdate(lastLine, true, report, error)
            }
        }.apply { name = "exim-job"; isDaemon = false }.start()
        return true
    }

    /** Keeps a partial report when the work throws: half a migration still needs explaining. */
    private fun EximRunner.runCatchingReport(
        work: (EximRunner) -> EximRunner.Report
    ): EximRunner.Report = try {
        work(this)
    } catch (e: ChatArchive.CancelledException) {
        // Stopping is a normal outcome, not a failure.
        EximRunner.Report().apply { cancelled = true }
    } catch (e: Throwable) {
        Log.e(TAG, "job body failed", e)
        EximRunner.Report().apply {
            failure = e.message ?: e.javaClass.simpleName
        }
    }
}
