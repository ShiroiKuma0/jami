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
                    kind == Kind.LIST -> reply("OK:" + SettingsExport.Cat.entries
                        .joinToString("\n") { "${it.id}\t${it.label}" })
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
        // Category selection — absent/empty items = everything; any unknown id is a hard error.
        val cats: List<SettingsExport.Cat> = if (items.isNullOrBlank()) {
            SettingsExport.Cat.entries.toList()
        } else {
            val byId = SettingsExport.Cat.entries.associateBy { it.id }
            val ids = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (ids.any { byId[it] == null }) return "ERROR:unknown category in items: $items"
            ids.map { byId.getValue(it) }
        }

        var lastProgress = 0L
        fun progress(cur: Int, total: Int, label: String, force: Boolean = false) {
            if (progressAction.isNullOrEmpty()) return
            val now = System.currentTimeMillis()
            if (!force && now - lastProgress < 500) return   // ≥500 ms apart, final always sent
            lastProgress = now
            app.sendBroadcast(Intent(progressAction).apply {
                setPackage(replyPackage)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("reply_id", replyId)
                putExtra("app", "白い熊 GNU Jami")
                putExtra("text", "区分 $cur/$total — $label")
                putExtra("current", cur.toLong())
                putExtra("total", total.toLong())
                putExtra("unit", "区分")
            })
        }

        // Account archives need the daemon; a cold-started process may still be loading accounts.
        var archives = emptyMap<String, ByteArray>()
        var meta: org.json.JSONObject? = null
        if (SettingsExport.Cat.ACCOUNTS in cats) {
            progress(0, cats.size, "Accounts (daemon)", force = true)
            val accounts = cx.ring.application.JamiApplication.instance?.mAccountService
                ?: return "ERROR:app not initialized"
            val deadline = System.currentTimeMillis() + 15_000
            while (accounts.getAccounts().isEmpty() && System.currentTimeMillis() < deadline)
                Thread.sleep(250)
            val (a, m, _) = SettingsExport.collectAccountArchives(app, accounts)
            archives = a; meta = m
        }

        // Build the ZIP with per-category progress (the engine is the panel's — one implementation).
        var done = 0
        for (cat in cats) { progress(done, cats.size, cat.label); done++ }
        val bytes = SettingsExport.export(app, cats, archives, meta)
        progress(cats.size, cats.size, "書き込み", force = true)

        // Directory precedence: path extra → configured SAF export directory → ERROR:no-directory.
        // No All-Files-Access in this app: a plain-File write to the override path is ATTEMPTED
        // (EMUI sometimes allows /sdcard/tmp); if it fails, the contract's fallback applies —
        // configured SAF directory, else ERROR:no-storage-access.
        val name = SettingsExport.exportFileName()
        val written: String = if (!path.isNullOrEmpty()) {
            writeToAbsoluteDir(path, name, bytes)
                ?: writeToSafDir(app, name, bytes)
                ?: return "ERROR:no-storage-access"
        } else {
            writeToSafDir(app, name, bytes)
                ?: return "ERROR:no-directory"
        }
        return "OK:$written|${bytes.size}|${human(bytes.size.toLong())}|${cats.size} categories"
    }

    /** Plain-File write into an absolute directory; null when storage access is refused. */
    private fun writeToAbsoluteDir(dir: String, name: String, bytes: ByteArray): String? = try {
        val d = File(dir)
        d.mkdirs()
        val f = File(d, name)
        f.writeBytes(bytes)
        if (f.length() == bytes.size.toLong()) f.absolutePath else { f.delete(); null }
    } catch (e: Exception) {
        Log.e(TAG, "absolute-path write to $dir failed: $e")
        null
    }

    /** SAF write into the panel's configured export directory; null when none/unwritable. */
    private fun writeToSafDir(app: Context, name: String, bytes: ByteArray): String? {
        try {
            val dir = SettingsExport.getExportDir(app) ?: return null
            val file = dir.createFile("application/zip", name) ?: return null
            app.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) } ?: return null
            return safDisplayPath(file.uri.toString()) ?: file.uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "SAF write failed: $e")
            return null
        }
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
