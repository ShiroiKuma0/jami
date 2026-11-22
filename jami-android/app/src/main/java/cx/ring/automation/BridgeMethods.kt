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
import net.jami.model.interaction.DataTransfer
import net.jami.model.interaction.Interaction
import net.jami.services.JamiFileTransferInfoProvider
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
    const val VERSION = "4"

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
        Triple("bridge_accept_file", "account,conversation,message_id,file_id", "state (started|already_finished|already_in_progress)"),
        Triple("bridge_file_state", "account,conversation,message_id,file_id",
            "state (finished|in_progress|accepted_waiting|not_started|unknown), bytes, total, transfer_state, file_name, file_size"),
        Triple("bridge_open_file", "account,conversation,message_id,file_id", "fd, file_name, file_size, file_uri"),
        Triple("bridge_status", "account?", "app fields + items[per account]"),
        Triple("bridge_react", "account,conversation,message_id,emoji", "reaction_id, pending"),
        Triple("bridge_unreact", "account,conversation,reaction_id", ""),
        Triple("bridge_edit", "account,conversation,message_id,text", "message_id"),
        Triple("bridge_delete", "account,conversation,message_id", "message_id"),
        Triple("bridge_message_status", "account,conversation,message_id", "delivered, read, items[peer,status]"),
        Triple("bridge_wake", "minutes? (default 2, max 30)", "asleep_before"),
        Triple("bridge_keep_awake", "on? (\"1\"|\"0\"; omit to read)", "keep_awake"),
    )

    fun call(ctx: Context, method: String, extras: Bundle?): Bundle = try {
        dispatch(ctx, method, extras ?: Bundle())
    } catch (t: Throwable) {
        err("${t.javaClass.simpleName}: ${t.message.orEmpty()}")
    }

    private val SENDING = setOf("bridge_send_text", "bridge_send_file", "bridge_react", "bridge_unreact",
        "bridge_edit", "bridge_delete", "bridge_accept_file")

    private fun dispatch(ctx: Context, method: String, x: Bundle): Bundle {
        // SK-BRIDGEWAKE: anything that must reach a peer wakes the accounts first and holds them
        // up long enough for the peer to fetch it. Asleep, a send only committed locally.
        if (method in SENDING)
            JamiApplication.instance?.automationWake(Bridge.SEND_HOLD_MS, method)
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
            "bridge_file_state" -> fileState(x)
            "bridge_open_file" -> openFile(x)
            "bridge_status" -> status(ctx, x)
            "bridge_react" -> react(x)
            "bridge_unreact" -> editTo(x, "reaction_id", "")
            "bridge_edit" -> editTo(x, "message_id", x.getString("text") ?: return err("missing text"))
            "bridge_delete" -> editTo(x, "message_id", "")
            "bridge_message_status" -> messageStatus(x)
            "bridge_wake" -> {
                val app = JamiApplication.instance ?: return err("app not initialized")
                val min = x.getString("minutes")?.toLongOrNull()?.coerceIn(1, 30) ?: 2
                ok().apply { putString("asleep_before", app.automationWake(min * 60_000L, "bridge_wake").toString()) }
            }
            "bridge_keep_awake" -> {
                x.getString("on")?.let { Bridge.setKeepAwake(it == "1" || it.equals("true", true)) }
                if (Bridge.keepAwake()) JamiApplication.instance?.automationWake(0L, "keep_awake on")
                ok().apply { putString("keep_awake", b(Bridge.keepAwake())) }
            }
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
            val jami = JamiApplication.instance
            val diag = cx.ring.utils.DiagPrefs.isOn(cx.ring.utils.DiagPrefs.Group.EXEC)
            if (diag) jami?.diagLog("SK-EXECDIAG send_text acct=${acc.take(6)} handed to executor")
            svc.sendConversationMessage(acc, Uri(Uri.SWARM_SCHEME, conv), text, replyTo)
            if (diag) {
                val queued = System.currentTimeMillis()
                jami?.mExecutor?.execute { jami.diagLog("SK-EXECDIAG executor passed send_text (${System.currentTimeMillis() - queued} ms)") }
            }
            return awaitId(p).also {
                if (diag) jami?.diagLog("SK-EXECDIAG send_text echo ${if (it.getString("pending") == "0") "id=" + it.getString("message_id")?.take(8) else "NONE after 8 s"}")
            }
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

    /**
     * Start (or resume) the download of a received file. The daemon needs only the four ids, so this
     * must not depend on Jami's UI model: on a phone whose Jami nobody opens the message is in the
     * model only while the process that received it lives, and the event reaches a bridge caller
     * BEFORE the model has it. It used to answer "message not loaded" in both cases and the accept
     * silently never happened (2026-11-22, a remote update that never arrived).
     */
    private fun acceptFile(x: Bundle): Bundle {
        val app = JamiApplication.instance ?: return err("app not initialized")
        val svc = app.mAccountService
        val acc = account(x) ?: return err("no such account")
        val conv = conversation(x) ?: return err("missing conversation")
        val messageId = x.getString("message_id")
        val fileId = x.getString("file_id")
        if (messageId.isNullOrEmpty() || fileId.isNullOrEmpty()) return err("missing message_id or file_id")
        when (daemonFileState(acc, conv, fileId).state) {
            "finished" -> return ok().apply { putString("state", "already_finished") }
            "in_progress" -> return ok().apply { putString("state", "already_in_progress") }
        }
        val swarm = svc.getAccount(acc)?.getSwarm(conv)
        val transfer = swarm?.getMessage(messageId) as? DataTransfer
        if (swarm != null && transfer != null) {
            if (transfer.hasExactContent) return ok().apply { putString("state", "already_finished") }
            svc.acceptFileTransfer(swarm, fileId, transfer)
            return ok().apply { putString("state", "started") }
        }
        // Not in the model: ask the daemon directly, with the destination the app itself would use.
        val name = (Bridge.readJournal(acc, conv)
            .lastOrNull { it["type"] == Bridge.TYPE_FILE && it["message_id"] == messageId }
            ?.get("file_name")?.takeIf { it.isNotEmpty() } ?: fileId).let { File(it).name }
        val dest = app.mDeviceRuntimeService.getNewConversationPath(acc, conv, name)
        val accepted = Bridge.daemon { JamiService.downloadFile(acc, conv, messageId, fileId, dest.absolutePath) } != 0L
        if (!accepted) return err("daemon refused the download (message or file id unknown to it)")
        return ok().apply { putString("state", "started") }
    }

    private class FileState(val state: String, val bytes: Long, val total: Long)

    /**
     * Where a received file stands, from the daemon alone:
     *  finished          — complete and checksum-verified, bridge_open_file will hand it over
     *  in_progress       — a channel is open and bytes are arriving
     *  accepted_waiting  — download requested, no channel now; bytes > 0 means an interrupted partial
     *                      that the next bridge_accept_file resumes
     *  not_started       — the daemon was never asked for this file
     *  unknown           — the daemon could not answer (conversation not ready, or it timed out)
     */
    private fun daemonFileState(acc: String, conv: String, fileId: String): FileState {
        val info = runCatching { Bridge.daemon { JamiFileTransferInfoProvider.get(acc, conv, fileId) } }.getOrNull()
            ?: return FileState("unknown", 0, 0)
        if (!info.isSuccess) return FileState(if (info.isRetryable) "unknown" else "not_started", 0, 0)
        val total = info.total.coerceAtLeast(0)
        val progress = info.progress.coerceAtLeast(0)
        if (info.path?.let { File(it).isFile } == true && progress >= total) return FileState("finished", progress, total)
        if (progress > 0) return FileState("in_progress", progress, total)
        // The daemon receives into a hidden partial beside the destination and keeps it for a resume.
        val partial = runCatching {
            JamiApplication.instance?.mDeviceRuntimeService?.getConversationPath(acc, conv, "x")?.parentFile
                ?.let { File(it, ".jami-$acc-$conv-$fileId.tmp") }?.takeIf { it.isFile }?.length()
        }.getOrNull() ?: 0L
        return FileState("accepted_waiting", partial, total)
    }

    private fun fileState(x: Bundle): Bundle {
        val acc = account(x) ?: return err("no such account")
        val conv = conversation(x) ?: return err("missing conversation")
        val messageId = x.getString("message_id").orEmpty()
        val fileId = x.getString("file_id")?.takeIf { it.isNotEmpty() } ?: return err("missing file_id")
        val st = daemonFileState(acc, conv, fileId)
        return ok().apply {
            putString("state", st.state)
            putString("bytes", st.bytes.toString())
            putString("total", st.total.toString())
            putString("transfer_state", Bridge.lastTransferState(acc, fileId))
            if (st.state == "finished") Bridge.resolveFile(acc, conv, messageId, fileId)?.let { f ->
                putString("file_name", f.name)
                putString("file_size", f.length().toString())
            }
        }
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
            putString("keep_awake", b(Bridge.keepAwake()))
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

    private fun react(x: Bundle): Bundle {
        val svc = JamiApplication.instance?.mAccountService ?: return err("app not initialized")
        val acc = account(x) ?: return err("no such account")
        val conv = conversation(x) ?: return err("missing conversation")
        val target = x.getString("message_id")?.takeIf { it.isNotEmpty() } ?: return err("missing message_id")
        val emoji = x.getString("emoji")?.takeIf { it.isNotEmpty() } ?: return err("missing emoji")
        val p = Bridge.Pending(acc, conv, Bridge.TYPE_REACTION, "$target|$emoji")
        Bridge.expect(p)
        try {
            svc.sendConversationReaction(acc, Uri(Uri.SWARM_SCHEME, conv), emoji, target)
            val got = p.latch.await(8, TimeUnit.SECONDS)
            return ok().apply {
                putString("reaction_id", p.messageId)
                putString("pending", if (got) "0" else "1")
            }
        } finally {
            Bridge.forget(p)
        }
    }

    /**
     * Edit, delete and un-react are one daemon operation: an edit commit on [idKey]'s message.
     * An empty [text] deletes it (for a reaction, that removes the reaction). The daemon only accepts
     * edits of this account's OWN messages and silently ignores anything else.
     */
    private fun editTo(x: Bundle, idKey: String, text: String): Bundle {
        val svc = JamiApplication.instance?.mAccountService ?: return err("app not initialized")
        val acc = account(x) ?: return err("no such account")
        val conv = conversation(x) ?: return err("missing conversation")
        val id = x.getString(idKey)?.takeIf { it.isNotEmpty() } ?: return err("missing $idKey")
        svc.editConversationMessage(acc, Uri(Uri.SWARM_SCHEME, conv), text, id)
        return ok().apply { putString("message_id", id) }
    }

    /** The delivery state per recipient, from the model (the message must be loaded in Jami). */
    private fun messageStatus(x: Bundle): Bundle {
        val svc = JamiApplication.instance?.mAccountService ?: return err("app not initialized")
        val acc = account(x) ?: return err("no such account")
        val conv = conversation(x) ?: return err("missing conversation")
        val id = x.getString("message_id")?.takeIf { it.isNotEmpty() } ?: return err("missing message_id")
        val msg = svc.getAccount(acc)?.getSwarm(conv)?.getMessage(id)
            ?: return err("message not loaded in this conversation")
        val me = Bridge.ownUri(acc).orEmpty()
        val peers = msg.statusMap.filterKeys { !it.equals(me, ignoreCase = true) }
        val delivered = peers.values.any { it == Interaction.MessageStates.SUCCESS || it == Interaction.MessageStates.DISPLAYED }
        val read = peers.values.any { it == Interaction.MessageStates.DISPLAYED }
        return ok().apply {
            putString("delivered", b(delivered))
            putString("read", b(read))
            putParcelableArray("items", peers.map { (peer, st) -> flat("peer" to peer, "status" to st.name) }.toTypedArray())
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
