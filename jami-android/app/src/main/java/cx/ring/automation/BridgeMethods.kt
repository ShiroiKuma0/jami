package cx.ring.automation

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import cx.ring.application.JamiApplication
import cx.ring.service.DRingService
import cx.ring.utils.ConnectionWatchdog
import cx.ring.utils.PushEvidence
import net.jami.daemon.JamiService
import net.jami.model.Account
import net.jami.model.Uri
import net.jami.utils.InboundEvidence
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The `bridge_*` methods of [AutomationProvider] — the SMS-over-Jami bridge's command side
 * (2026-10-31, contract agreed with 自由作業盤).
 *
 * Result shape, fixed by that contract so 自由作業盤's generic `provider.call` action can flatten it:
 * a flat Bundle of Strings, `status` = OK | ERROR, `error` = text (empty on OK), lists as a
 * `Bundle[]` under `items` (each a flat String bundle), and a ParcelFileDescriptor only where a
 * file is handed over (`fd`). The caller has already passed [AutomationCallers.verify] and the
 * automation switch before anything here runs.
 */
object BridgeMethods {
    const val VERSION = "1"

    private val METHODS = listOf(
        Triple("bridge_describe", "", "version, items[method,args,returns]"),
        Triple("bridge_list_accounts", "", "items[account,uri,registered_name,alias,enabled,registration_state,current]"),
        Triple("bridge_list_conversations", "account", "items[conversation,title,mode,members,roles,own_role,watched]"),
        Triple("bridge_watch", "account,conversation", "watched"),
        Triple("bridge_unwatch", "account,conversation", "watched"),
        Triple("bridge_watched", "", "items[account,conversation]"),
        Triple("bridge_messages_since", "account,conversation,after_seq|after_id,limit", "items[event], last_seq, count"),
        Triple("bridge_send_text", "account,conversation,text,reply_to?", "message_id, pending"),
        Triple("bridge_send_file", "account,conversation,fd|uri,name,reply_to?", "message_id, file_id, pending"),
        Triple("bridge_accept_file", "account,conversation,message_id,file_id", ""),
        Triple("bridge_open_file", "account,conversation,message_id,file_id", "fd, file_name, file_size, file_uri"),
        Triple("bridge_status", "account?", "app fields + items[per account]"),
    )

    fun call(ctx: Context, method: String, extras: Bundle?): Bundle = try {
        dispatch(ctx, method, extras ?: Bundle())
    } catch (t: Throwable) {
        err("${t.javaClass.simpleName}: ${t.message.orEmpty()}")
    }

    private fun dispatch(ctx: Context, method: String, x: Bundle): Bundle {
        return when (method) {
            "bridge_describe" -> ok().apply {
                putString("version", VERSION)
                putParcelableArray("items", METHODS.map { (m, a, r) ->
                    flat("method" to m, "args" to a, "returns" to r) }.toTypedArray())
            }
            "bridge_list_accounts" -> listAccounts()
            "bridge_list_conversations" -> listConversations(account(x) ?: return err("no such account"))
            "bridge_watch", "bridge_unwatch" -> {
                val acc = account(x) ?: return err("no such account")
                val conv = conversation(x) ?: return err("missing conversation")
                Bridge.setWatched(acc, conv, method == "bridge_watch")
                ok().apply { putString("watched", if (Bridge.isWatched(acc, conv)) "1" else "0") }
            }
            "bridge_watched" -> ok().apply {
                putParcelableArray("items", Bridge.watched().map {
                    flat("account" to it.substringBefore('|'), "conversation" to it.substringAfter('|'))
                }.toTypedArray())
            }
            "bridge_messages_since" -> messagesSince(x)
            "bridge_send_text" -> sendText(x)
            "bridge_send_file" -> sendFile(ctx, x)
            "bridge_accept_file" -> acceptFile(x)
            "bridge_open_file" -> openFile(x)
            "bridge_status" -> status(ctx, x)
            else -> err("unknown method: $method")
        }
    }

    // ------------------------------------------------------------------ methods

    private fun listAccounts(): Bundle {
        val svc = JamiApplication.instance?.mAccountService ?: return err("app not initialized")
        val current = svc.currentAccount?.accountId
        val ids = Bridge.daemon { JamiService.getAccountList().toList() }
        return ok().apply {
            putParcelableArray("items", ids.mapNotNull { id ->
                val a = svc.getAccount(id) ?: return@mapNotNull null
                flat("account" to id,
                    "uri" to (Bridge.ownUri(id) ?: ""),
                    "registered_name" to a.registeredName,
                    "alias" to (a.alias ?: ""),
                    "enabled" to b(a.isEnabled),
                    "registration_state" to a.registrationState.name,
                    "current" to b(id == current))
            }.toTypedArray())
        }
    }

    private fun listConversations(acc: String): Bundle {
        val me = Bridge.ownUri(acc).orEmpty()
        val rows = Bridge.daemon {
            JamiService.getConversations(acc).toList().map { id ->
                Triple(id, JamiService.conversationInfos(acc, id).toNative(),
                    JamiService.getConversationMembers(acc, id).toNative())
            }
        }
        return ok().apply {
            putParcelableArray("items", rows.map { (id, info, members) ->
                flat("conversation" to id,
                    "title" to info["title"].orEmpty(),
                    "mode" to modeName(info["mode"]),
                    "members" to members.joinToString(",") { it["uri"].orEmpty() },
                    "roles" to members.joinToString(",") { "${it["uri"].orEmpty()}=${it["role"].orEmpty()}" },
                    "own_role" to (members.firstOrNull { it["uri"].equals(me, ignoreCase = true) }?.get("role") ?: ""),
                    "watched" to b(Bridge.isWatched(acc, id)))
            }.toTypedArray())
        }
    }

    private fun messagesSince(x: Bundle): Bundle {
        val acc = account(x) ?: return err("no such account")
        val conv = conversation(x) ?: return err("missing conversation")
        if (!Bridge.isWatched(acc, conv)) return err("conversation not watched (call bridge_watch first)")
        val all = Bridge.readJournal(acc, conv)
        val afterSeq = x.getString("after_seq")?.toLongOrNull()
        val afterId = x.getString("after_id").orEmpty()
        val limit = x.getString("limit")?.toIntOrNull()?.coerceIn(1, 1000) ?: 200
        val tail = when {
            afterSeq != null -> all.filter { (it["seq"]?.toLongOrNull() ?: 0) > afterSeq }
            afterId.isNotEmpty() -> {
                val i = all.indexOfLast { it["event_id"] == afterId || it["message_id"] == afterId }
                if (i < 0) all else all.drop(i + 1)
            }
            else -> all
        }.take(limit)
        return ok().apply {
            putString("count", tail.size.toString())
            putString("last_seq", (tail.lastOrNull() ?: all.lastOrNull())?.get("seq") ?: "0")
            putParcelableArray("items", tail.map(Bridge::toBundle).toTypedArray())
        }
    }

    private fun sendText(x: Bundle): Bundle {
        val svc = JamiApplication.instance?.mAccountService ?: return err("app not initialized")
        val acc = account(x) ?: return err("no such account")
        val conv = conversation(x) ?: return err("missing conversation")
        val text = x.getString("text")
        if (text.isNullOrEmpty()) return err("empty text")
        val replyTo = x.getString("reply_to")?.takeIf { it.isNotEmpty() }
        val p = Bridge.Pending(acc, conv, Bridge.TYPE_TEXT, text)
        Bridge.expect(p)
        try {
            svc.sendConversationMessage(acc, Uri(Uri.SWARM_SCHEME, conv), text, replyTo)
            return awaitId(p)
        } finally {
            Bridge.forget(p)
        }
    }

    private fun sendFile(ctx: Context, x: Bundle): Bundle {
        val app = JamiApplication.instance ?: return err("app not initialized")
        val acc = account(x) ?: return err("no such account")
        val conv = conversation(x) ?: return err("missing conversation")
        val conversation = app.mAccountService.getAccount(acc)?.getSwarm(conv)
            ?: return err("no such conversation on this account")
        val name = (x.getString("name") ?: "").replace('/', '_').replace('\\', '_').trim()
            .ifEmpty { "file-${System.currentTimeMillis()}" }
        val replyTo = x.getString("reply_to").orEmpty()
        // Stage a private copy: sendFile MOVES its input into the conversation's data directory.
        val stage = File(File(ctx.cacheDir, "bridge-out").apply { mkdirs() }, "${System.nanoTime()}")
            .apply { mkdirs() }
        val file = File(stage, name)
        val fd = fdExtra(x)
        val uri = x.getString("uri")
        when {
            fd != null -> ParcelFileDescriptor.AutoCloseInputStream(fd).use { i -> file.outputStream().use { i.copyTo(it) } }
            !uri.isNullOrEmpty() -> ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { i ->
                file.outputStream().use { i.copyTo(it) } } ?: return err("cannot open uri")
            else -> return err("missing fd or uri")
        }
        if (file.length() == 0L) return err("empty file")
        val p = Bridge.Pending(acc, conv, Bridge.TYPE_FILE, name)
        Bridge.expect(p)
        try {
            app.mConversationFacade.sendFile(conversation, conversation.uri, file, replyTo).blockingAwait(15, TimeUnit.SECONDS)
            return awaitId(p).apply { putString("file_id", p.fileId) }
        } finally {
            Bridge.forget(p)
            stage.deleteRecursively()
        }
    }

    private fun acceptFile(x: Bundle): Bundle {
        val svc = JamiApplication.instance?.mAccountService ?: return err("app not initialized")
        val acc = account(x) ?: return err("no such account")
        val conv = conversation(x) ?: return err("missing conversation")
        val messageId = x.getString("message_id")
        val fileId = x.getString("file_id")
        if (messageId.isNullOrEmpty() || fileId.isNullOrEmpty()) return err("missing message_id or file_id")
        svc.getAccount(acc)?.getSwarm(conv)?.getMessage(messageId)
            ?: return err("message not loaded in this conversation")
        svc.acceptFileTransfer(acc, Uri(Uri.SWARM_SCHEME, conv), messageId, fileId)
        return ok()
    }

    private fun openFile(x: Bundle): Bundle {
        val acc = account(x) ?: return err("no such account")
        val conv = conversation(x) ?: return err("missing conversation")
        val f = Bridge.resolveFile(acc, conv, x.getString("message_id").orEmpty(), x.getString("file_id").orEmpty())
            ?: return err("file not available (transfer not finished?)")
        return ok().apply {
            putParcelable("fd", ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY))
            putString("file_name", f.name)
            putString("file_size", f.length().toString())
            putString("file_uri", Bridge.grantedUri(f) ?: "")
        }
    }

    private fun status(ctx: Context, x: Bundle): Bundle {
        val svc = JamiApplication.instance?.mAccountService ?: return err("app not initialized")
        val only = x.getString("account")?.takeIf { it.isNotEmpty() }?.let { account(x) }
        val ids = Bridge.daemon { JamiService.getAccountList().toList() }.filter { only == null || it == only }
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return ok().apply {
            putString("app_version", pkg.versionName ?: "")
            putString("now_ms", System.currentTimeMillis().toString())
            putString("daemon_running", b(DRingService.isRunning))
            putString("watchdog_recovering", b(ConnectionWatchdog.isRecovering()))
            putString("last_inbound_ms", InboundEvidence.lastMs.toString())
            putString("last_inbound_kind", InboundEvidence.lastKind)
            putString("last_push_ms", PushEvidence.lastRealPushMs.toString())
            putParcelableArray("items", ids.mapNotNull { id ->
                val a: Account = svc.getAccount(id) ?: return@mapNotNull null
                flat("account" to id,
                    "uri" to (Bridge.ownUri(id) ?: ""),
                    "alias" to (a.alias ?: ""),
                    "enabled" to b(a.isEnabled),
                    "registration_state" to a.registrationState.name,
                    "dht_proxy" to b(a.isDhtProxyEnabled),
                    "proxy_in_use" to a.dhtProxyUsed)
            }.toTypedArray())
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Wait up to 8 s for our own message to echo back with its commit id. */
    private fun awaitId(p: Bridge.Pending): Bundle {
        val got = p.latch.await(8, TimeUnit.SECONDS)
        return ok().apply {
            putString("message_id", p.messageId)
            putString("pending", if (got) "0" else "1")
        }
    }

    /** `account`: an id, or empty / "default" = the current account. Null when it does not exist. */
    private fun account(x: Bundle): String? {
        val svc = JamiApplication.instance?.mAccountService ?: return null
        val raw = x.getString("account")?.trim()
        val id = if (raw.isNullOrEmpty() || raw.equals("default", true)) svc.currentAccount?.accountId else raw
        return id?.takeIf { svc.getAccount(it) != null }
    }

    /** `conversation`: the bare swarm id, or `swarm:<id>`. */
    private fun conversation(x: Bundle): String? =
        x.getString("conversation")?.trim()?.removePrefix("swarm:")?.takeIf { it.isNotEmpty() }

    private fun fdExtra(x: Bundle): ParcelFileDescriptor? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) x.getParcelable("fd", ParcelFileDescriptor::class.java)
        else @Suppress("DEPRECATION") x.getParcelable("fd")

    private fun modeName(m: String?) = when (m) {
        "0" -> "one_to_one"; "1" -> "admin_invites_only"; "2" -> "invites_only"; "3" -> "public"
        else -> m.orEmpty()
    }

    private fun b(v: Boolean) = if (v) "1" else "0"
    private fun flat(vararg kv: Pair<String, String>) = Bundle().apply { kv.forEach { (k, v) -> putString(k, v) } }
    private fun ok() = Bundle().apply { putString("status", "OK"); putString("error", "") }
    fun err(why: String) = Bundle().apply { putString("status", "ERROR"); putString("error", why) }
}
