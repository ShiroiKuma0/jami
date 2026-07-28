/*
 *  shiroikuma.jami fork — 保存復元 state-export automation receiver (2026-07-25).
 *
 *  Implements the 白い熊 自由作業盤 batch-backup wire contract (~/tmp/hand-off.md):
 *  two token-gated exported actions, <pkg>.action.EXPORT_STATE (headless run of the
 *  Export/Import panel's category-ZIP backup) and <pkg>.action.LIST_CATEGORIES.
 *  Reply = a fresh broadcast to reply_package/reply_action carrying reply_id + result —
 *  never a Binder (ResultReceiver/PendingIntent/Messenger get dropped between third-party
 *  apps on EMUI; verified on the Mate XT 2026-07-23). Progress = real counts, never percentages.
 *
 *  NOTHING SLOW HAPPENS HERE. A manifest receiver must reach finish() within ~10 s in the
 *  foreground and ~60 s otherwise, and goAsync() does not extend that window — running a
 *  multi-gigabyte export here got the process killed mid-write on every attempt, leaving truncated
 *  archives that look like real backups (2026-07-28). The receiver validates and hands off to
 *  StateExportService; only instant refusals are answered from here, because they cost nothing.
 *
 *  Token infra: the fork's existing AutomationPrefs (same switch + token as the
 *  jami-cmd:// intents). "automation disabled" and "bad token" are distinct errors.
 */
package cx.ring.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import cx.ring.utils.AutomationPrefs
import cx.ring.utils.SettingsExport

class StateExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val pkg = app.packageName
        val kind = when (action) {
            "$pkg.action.EXPORT_STATE" -> Kind.EXPORT
            "$pkg.action.LIST_CATEGORIES" -> Kind.LIST
            "$pkg.action.CANCEL_EXPORT" -> Kind.CANCEL
            else -> return
        }
        val token = intent.getStringExtra("token")
        val items = intent.getStringExtra("items")
        val replyAction = intent.getStringExtra("reply_action")
        val replyPackage = intent.getStringExtra("reply_package")
        val replyId = intent.getStringExtra("reply_id")

        // A cancel is handled before anything else, and deliberately answers NOTHING: it carries no
        // reply extras (so the missing-extras check below must not see it), and the running export
        // owns the single terminal reply — it will send ERROR:cancelled itself. Arriving when
        // nothing is running is a no-op by design: 自由作業盤 fires this whenever 白い熊 presses
        // 中止, without knowing how far the export got.
        if (kind == Kind.CANCEL) {
            if (AutomationPrefs.isEnabled(app) && AutomationPrefs.isAuthorized(app, token)) {
                runCatching {
                    app.startService(Intent(app, StateExportService::class.java)
                        .setAction(StateExportService.ACTION_CANCEL))
                }.onFailure { Log.w(TAG, "cancel could not reach the service: ${it.message}") }
            }
            return
        }

        // Answered straight from the receiver: every one of these is instant.
        fun reply(result: String) {
            Log.e(TAG, "reply [$replyId]: ${result.take(160)}")
            if (!replyAction.isNullOrEmpty() && !replyPackage.isNullOrEmpty()) {
                app.sendBroadcast(Intent(replyAction).apply {
                    setPackage(replyPackage)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("reply_id", replyId)
                    putExtra("result", result)
                })
            }
            runCatching { resultData = result }
        }

        when {
            !AutomationPrefs.isEnabled(app) -> reply("ERROR:automation disabled")
            !AutomationPrefs.isAuthorized(app, token) -> reply("ERROR:bad token")
            replyAction.isNullOrEmpty() || replyPackage.isNullOrEmpty() || replyId.isNullOrEmpty() ->
                reply("ERROR:missing reply extras")

            // id ⇥ label ⇥ parent ⇥ on|off (保存復元 contract, LIST_CATEGORIES). The parent field is
            // empty — this app has no item groups — and the fourth field is the app stating whether
            // an item starts ticked, rather than the picker assuming.
            kind == Kind.LIST -> reply("OK:" + SettingsExport.Cat.entries.joinToString("\n") {
                "${it.id}\t${app.getString(it.labelRes)}\t\t${if (it.defaultOn) "on" else "off"}"
            })

            // Reject an unknown category here rather than starting a service that would only fail:
            // validating the list costs nothing and the caller gets its error immediately.
            !itemsValid(items) -> reply("ERROR:unknown category in items: $items")

            else -> {
                ContextCompat.startForegroundService(app,
                    Intent(app, StateExportService::class.java).apply {
                        putExtra("path", intent.getStringExtra("path"))
                        putExtra("items", items)
                        putExtra("progress_action", intent.getStringExtra("progress_action"))
                        putExtra("reply_action", replyAction)
                        putExtra("reply_package", replyPackage)
                        putExtra("reply_id", replyId)
                    })
                // No reply here: the service sends the ONE terminal reply when it is done.
            }
        }
    }

    private fun itemsValid(items: String?): Boolean {
        if (items.isNullOrBlank()) return true          // absent = this app's default set
        val byId = SettingsExport.Cat.entries.associateBy { it.id }
        return items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .all { byId[it] != null }
    }

    private enum class Kind { EXPORT, LIST, CANCEL }

    companion object {
        private const val TAG = "SK-EXPORT"
    }
}
