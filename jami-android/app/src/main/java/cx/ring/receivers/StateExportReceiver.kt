/*
 *  shiroikuma.jami fork — 保存復元 state-export automation receiver (2026-07-25).
 *
 *  Implements the 白い熊 自由作業盤 batch-backup wire contract (~/tmp/hand-off.md):
 *  two token-gated exported actions, <pkg>.action.EXPORT_STATE (headless run of the
 *  Export/Import panel's category-ZIP backup) and <pkg>.action.LIST_CATEGORIES.
 *  Reply = a fresh broadcast to reply_package/reply_action carrying reply_id + result —
 *  never a Binder (ResultReceiver/PendingIntent/Messenger get dropped between third-party
 *  apps on EMUI; verified on the Mate XT 2026-07-23). The ordered-broadcast result is set
 *  too (correct AOSP behaviour, and it is what `adb shell am broadcast` prints), but the
 *  broadcast is the reply that counts. Progress = real counts, never percentages.
 *
 *  Token infra: the fork's existing AutomationPrefs (same switch + token as the
 *  jami-cmd:// intents). "automation disabled" and "bad token" are distinct errors.
 */
package cx.ring.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cx.ring.utils.AutomationPrefs
import cx.ring.utils.EximRunner
import cx.ring.utils.SettingsExport
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class StateExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val pkg = app.packageName
        val kind = when (action) {
            "$pkg.action.EXPORT_STATE" -> Kind.EXPORT
            "$pkg.action.LIST_CATEGORIES" -> Kind.LIST
            else -> return
        }
        val token = intent.getStringExtra("token")
        val path = intent.getStringExtra("path")
        val items = intent.getStringExtra("items")
        val progressAction = intent.getStringExtra("progress_action")
        val replyAction = intent.getStringExtra("reply_action")
        val replyPackage = intent.getStringExtra("reply_package")
        val replyId = intent.getStringExtra("reply_id")

        val pending = goAsync()
        val replied = AtomicBoolean(false)

        // Exactly ONE terminal reply per request: fresh broadcast (the channel that works) +
        // the ordered result (harmless AOSP correctness; also what `am broadcast` displays).
        fun reply(result: String) {
            if (!replied.compareAndSet(false, true)) return
            Log.e(TAG, "reply [$replyId]: ${result.take(160)}")
            if (!replyAction.isNullOrEmpty() && !replyPackage.isNullOrEmpty()) {
                app.sendBroadcast(Intent(replyAction).apply {
                    setPackage(replyPackage)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("reply_id", replyId)
                    putExtra("result", result)
                })
            }
            runCatching { pending.setResultData(result) }
            pending.finish()
        }

        Thread {
            try {
                when {
                    !AutomationPrefs.isEnabled(app) -> reply("ERROR:automation disabled")
                    !AutomationPrefs.isAuthorized(app, token) -> reply("ERROR:bad token")
                    replyAction.isNullOrEmpty() || replyPackage.isNullOrEmpty() || replyId.isNullOrEmpty() ->
                        reply("ERROR:missing reply extras")
                    // id ⇥ label ⇥ parent ⇥ on|off (保存復元 contract, LIST_CATEGORIES). The parent
                    // field is empty — this app has no item groups — and the fourth field is the
                    // app stating whether an item starts ticked, rather than the picker assuming.
                    kind == Kind.LIST -> reply("OK:" + SettingsExport.Cat.entries.joinToString("\n") {
                        "${it.id}\t${app.getString(it.labelRes)}\t\t${if (it.defaultOn) "on" else "off"}"
                    })
                    else -> reply(runExport(app, path, items, progressAction, replyPackage, replyId))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "state export failed", e)
                reply("ERROR:${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    /** The headless export: same categories, same ZIP engine, same archives as the panel. */
    private fun runExport(
        app: Context, path: String?, items: String?,
        progressAction: String?, replyPackage: String, replyId: String,
    ): String {
        // Category selection — absent/empty items = everything EXCEPT the chat corpus. Chats and
        // chat files are gigabyte-scale; a caller that wants them in a headless backup names them
        // explicitly, so an existing automation's behaviour does not change under it.
        val cats: List<SettingsExport.Cat> = if (items.isNullOrBlank()) {
            SettingsExport.defaultHeadlessCats()
        } else {
            val byId = SettingsExport.Cat.entries.associateBy { it.id }
            val ids = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (ids.any { byId[it] == null }) return "ERROR:unknown category in items: $items"
            ids.map { byId.getValue(it) }
        }

        var lastProgress = 0L
        // `item` is the Cat.id being written and `bytes`/`bytes_total` the byte pair — both
        // additive extras of 保存復元 contract §3, so the panel can highlight the right row instead
        // of reading the file counter as a row number, and draw both counters on one line.
        fun progress(
            cur: Long, total: Long, label: String, unit: String,
            item: String = "", bytes: Long = 0, bytesTotal: Long = 0, force: Boolean = false,
        ) {
            if (progressAction.isNullOrEmpty()) return
            val now = System.currentTimeMillis()
            if (!force && now - lastProgress < 500) return   // ≥500 ms apart, final always sent
            lastProgress = now
            app.sendBroadcast(Intent(progressAction).apply {
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

        // Account archives need the daemon; a cold-started process may still be loading accounts.
        val accounts = cx.ring.application.JamiApplication.instance?.mAccountService
            ?: return "ERROR:app not initialized"
        if (SettingsExport.Cat.ACCOUNTS in cats) {
            progress(0, cats.size.toLong(), "区分", "区分",
                item = SettingsExport.Cat.ACCOUNTS.id, force = true)
            val deadline = System.currentTimeMillis() + 15_000
            while (accounts.getAccounts().isEmpty() && System.currentTimeMillis() < deadline)
                Thread.sleep(250)
        }

        val runner = EximRunner(app, accounts) { p ->
            if (p.totalFiles > 0)
                progress(p.files.toLong(), p.totalFiles.toLong(), p.phase, "ファイル",
                    item = p.itemId, bytes = p.bytes, bytesTotal = p.totalBytes)
            else
                progress(0, cats.size.toLong(), p.phase, "区分", item = p.itemId)
        }

        // Destination precedence: path extra → configured direct directory → SAF directory.
        // A plain-File write is ATTEMPTED even without all-files access (EMUI sometimes allows
        // /sdcard/tmp); the SAF directory is the fallback when it is refused.
        val name = SettingsExport.exportFileName()
        val direct = if (!path.isNullOrEmpty()) File(path) else SettingsExport.directDir(app)
        var written: String? = null
        var size = 0L
        if (direct != null) {
            runCatching {
                direct.mkdirs()
                val f = File(direct, name)
                val counter = CountingStream(f.outputStream())
                counter.use { runner.exportInto(cats, it) }
                size = counter.count
                written = f.absolutePath
            }.onFailure { Log.e(TAG, "direct write to $direct failed: $it") }
        }
        if (written == null) {
            runCatching {
                val dir = SettingsExport.getExportDir(app) ?: return@runCatching
                val doc = dir.createFile("application/zip", name) ?: return@runCatching
                val stream = app.contentResolver.openOutputStream(doc.uri) ?: return@runCatching
                val counter = CountingStream(stream)
                counter.use { runner.exportInto(cats, it) }
                size = counter.count
                written = safDisplayPath(doc.uri.toString()) ?: doc.uri.toString()
            }.onFailure { Log.e(TAG, "SAF write failed: $it") }
        }
        progress(1, 1, "書き込み", "区分", force = true)
        val target = written ?: return if (SettingsExport.getExportDir(app) == null)
            "ERROR:no-directory" else "ERROR:no-storage-access"
        return "OK:$target|$size|${human(size)}|${cats.size} categories"
    }

    /** Counts bytes on the way out, so the reply can state the size without re-reading the file. */
    private class CountingStream(private val out: java.io.OutputStream) : java.io.OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    /** Best-effort absolute path for a primary-storage SAF document URI (for the reply line). */
    private fun safDisplayPath(uri: String): String? {
        val marker = "primary%3A"
        val i = uri.lastIndexOf(marker)
        if (i < 0) return null
        val rel = java.net.URLDecoder.decode(uri.substring(i + marker.length), "UTF-8")
        return "/storage/emulated/0/$rel"
    }

    private fun human(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    private enum class Kind { EXPORT, LIST }

    companion object {
        private const val TAG = "SK-EXPORT"
    }
}
