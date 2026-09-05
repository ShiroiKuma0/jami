package cx.ring.automation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import cx.ring.utils.AutomationPrefs
import cx.ring.utils.SettingsExport

/**
 * The data door: export this app's own state, and put it back, for a caller we can identify.
 *
 * Sister-app contract v2 §2a (2026-09-04). It sits *alongside* [cx.ring.receivers.StateExportReceiver],
 * which keeps the §1 broadcast surface; it does not replace it.
 *
 * ## Why a provider and not another broadcast action
 *
 * **A broadcast cannot tell you who sent it.** v1's answer to that was the shared secret, and that
 * secret cannot survive the wipe this feature exists to recover from. A provider gets the caller's
 * identity from the framework — see [AutomationCallers] for what is actually checked, and why a
 * `shiroikuma.*` prefix test would have been strictly weaker than the token it replaced.
 *
 * **And a list needs a synchronous answer.** 応用管理 draws a row per installed app before any
 * export exists; a broadcast round trip per app to fill a list is the wrong shape.
 *
 * ## What does NOT happen here
 *
 * The payload. `call()` validates, starts a foreground service and returns. Tens of megabytes over
 * minutes inside a binder call would block the caller, report no progress, refuse cancellation and
 * die silently if this process were killed. The bytes go through a descriptor the caller opened.
 *
 * ## `import` exists ONLY here
 *
 * It never gets a broadcast action. An import overwrites this app's data, and the §1 receiver is
 * `exported="true"` with no permission — an import there would let any app on the phone wipe any
 * sister app.
 */
class AutomationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * Every method answers a [Bundle] with [KEY_RESULT] — `OK…` or `ERROR:…`, the same vocabulary
     * the broadcast contract uses, so a caller has one grammar to parse rather than two.
     *
     * A refusal is returned, never thrown: an exception across a binder reaches the caller as a
     * `RuntimeException` carrying our stack trace, which tells 白い熊 nothing and tells a
     * misbehaving caller rather more than it should.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return fail("ERROR:not ready")

        // WHO, before WHAT. A caller we cannot identify gets the same answer whatever it asked for.
        when (val verdict = AutomationCallers.verify(ctx, callingPackage)) {
            is AutomationCallers.Verdict.Refused -> return fail(verdict.why)
            AutomationCallers.Verdict.Allowed -> Unit
        }
        // Then this app's own switches. Note that this door is the ONLY part of the automation
        // surface with a caller identity behind it — the three checks above have already proved
        // who is asking. The broadcast and Activity entry points cannot do that, which is why the
        // token is the only gate they have.
        AutomationPrefs.refuse(ctx, extras?.getString(KEY_TOKEN))?.let { return fail(it) }

        return when (method) {
            METHOD_DESCRIBE -> ok(describe(ctx))
            METHOD_EXPORT -> start(ctx, extras, importing = false)
            METHOD_IMPORT -> start(ctx, extras, importing = true)
            METHOD_CANCEL -> {
                AutomationJobs.cancel(extras?.getString(KEY_JOB_ID))
                ok("OK:cancelled")
            }
            else -> fail("ERROR:unknown method: $method")
        }
    }

    /**
     * What this app would export, answered without exporting anything.
     *
     * Returned from the call rather than written into the archive: 応用管理 must draw a row before
     * an export exists, and at restore must judge compatibility **before** streaming tens of
     * megabytes into an app that would reject them — which it cannot do if the header is buried
     * inside an encrypted archive.
     *
     * `requires_launch_first` is **true** here, and Jami is the contract's anticipated exception.
     * Restoring an account is not a file copy: [cx.ring.utils.EximRunner] drives the daemon
     * throughout — `pauseAccountForImport`, `resumeAccountAfterImport`,
     * `reloadConversationsAndRequests`, and an adoption of the restored archive that only completes
     * across a daemon restart. A never-launched process has no daemon and no `AccountService`, so an
     * import there would half-restore 白い熊's real accounts and chats and report success over it —
     * the one failure worse than refusing.
     */
    private fun describe(ctx: Context): String {
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val cats = SettingsExport.defaultHeadlessCats()
        val contains = cats.joinToString(",") { "\"" + ctx.getString(it.labelRes).replace("\"", "'") + "\"" }
        return "OK:" + """
            {"app_id":"${ctx.packageName}",
             "version_code":${@Suppress("DEPRECATION") pkg.versionCode},
             "version_name":"${pkg.versionName}",
             "format":$FORMAT,
             "min_format_readable":$MIN_FORMAT_READABLE,
             "requires_launch_first":true,
             "contains":[$contains]}
        """.trimIndent().replace("\n", "")
    }

    /**
     * Hand the descriptor to a foreground service and get out of the way.
     *
     * The descriptor is **duplicated** before it leaves this method: the one in [extras] belongs to
     * the binder transaction and is closed when `call()` returns, so a service reading it afterwards
     * would find it shut — a bug that only shows under load.
     *
     * If the service will not start, the dup is closed and the job dropped **before** answering.
     * That window is real: `startForegroundService` from a binder call is a background start, and on
     * API 31+ it can be refused outright with `ForegroundServiceStartNotAllowedException` unless the
     * app is exempt from battery optimisation. Left unhandled, the caller's open file is stranded in
     * a map nothing will ever read and the exception crosses the binder as a stack trace instead of
     * a refusal. (Found in the v2 rollout by shiroikuma-denwa and shiroikuma-jinsoningen; the
     * reference implementation leaked on this path until they did.)
     */
    private fun start(ctx: Context, extras: Bundle?, importing: Boolean): Bundle {
        // NOT the typed getParcelable(String, Class): that overload is API 33, and 白い熊's Mate XT
        // reports SDK_INT = 31, so it would compile against compileSdk 37 and then throw
        // NoSuchMethodError on the one device this has to work on.
        val fd = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            extras?.getParcelable(KEY_FD, ParcelFileDescriptor::class.java)
        else
            @Suppress("DEPRECATION") extras?.getParcelable<ParcelFileDescriptor>(KEY_FD))
            ?: return fail("ERROR:no descriptor")
        val dup = runCatching { fd.dup() }.getOrNull() ?: return fail("ERROR:descriptor unusable")
        val jobId = AutomationJobs.begin()
        return try {
            AutomationDataService.start(ctx, jobId, dup, importing, extras)
            ok("OK:$jobId")
        } catch (t: Throwable) {
            AutomationDataService.abandon(jobId)
            runCatching { dup.close() }
            AutomationJobs.finish(jobId)
            fail("ERROR:foreground service start refused: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun ok(result: String) = Bundle().apply { putString(KEY_RESULT, result) }
    private fun fail(why: String) = Bundle().apply { putString(KEY_RESULT, why) }

    // A provider that is only ever call()ed still has to answer these. Refusing loudly beats an
    // empty cursor, which reads downstream as "there is no data" rather than "wrong door".
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? =
        throw UnsupportedOperationException("automation is call() only")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("automation is call() only")
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    companion object {
        const val METHOD_DESCRIBE = "describe"
        const val METHOD_EXPORT = "export"
        const val METHOD_IMPORT = "import"
        const val METHOD_CANCEL = "cancel"

        const val KEY_RESULT = "result"
        const val KEY_FD = "fd"
        const val KEY_TOKEN = "token"
        const val KEY_JOB_ID = "job_id"
        const val KEY_ITEMS = "items"
        const val KEY_REPLY_ACTION = "reply_action"
        const val KEY_REPLY_PACKAGE = "reply_package"
        const val KEY_PROGRESS_ACTION = "progress_action"

        /** This app's archive format. Bumped when an older build could no longer read what we write. */
        const val FORMAT = 1

        /**
         * The oldest archive this build can still read.
         *
         * Version skew has a direction: old data into a newer app is normally fine, because an app
         * migrates its own storage; newer data into an older app is not. This is what lets a caller
         * refuse the second case at discovery time, before anything is streamed.
         */
        const val MIN_FORMAT_READABLE = 1
    }
}
