package cx.ring.automation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import cx.ring.BuildConfig
import cx.ring.application.JamiApplication
import cx.ring.utils.AutomationPrefs
import cx.ring.utils.ContentUri
import net.jami.daemon.JamiService
import net.jami.model.interaction.DataTransfer
import net.jami.model.interaction.Interaction
import net.jami.services.BridgeTap
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The SMS-over-Jami bridge's event side (2026-10-31, planned with the 自由作業盤 chat).
 *
 * Every swarm event of a WATCHED conversation — a message, an edit, a reaction, a file-transfer
 * state change — is turned into one flat event, appended to that conversation's journal and
 * announced to 自由作業盤 by an explicit, certificate-pinned broadcast. The broadcast is only a
 * hint: a process kill or reboot can lose one, so the journal (read with `bridge_messages_since`)
 * is the source of truth, cursored by the monotonic `seq`.
 *
 * ## Why only watched conversations
 *
 * 自由作業盤 also runs on 白い熊's carried phone, next to every private chat they have. A tap that
 * exported everything would copy all of them into a journal and out to another app. The bridge
 * exports exactly the conversations it was told to (`bridge_watch`), and nothing else ever leaves.
 *
 * ## Threading
 *
 * [BridgeTap] callbacks arrive on the daemon's callback thread; each is copied and handed to a
 * single private worker, so journal I/O, broadcasts and daemon look-ups never stall delivery.
 */
object Bridge : BridgeTap {
    private const val TAG = "SkBridge"
    const val TARGET = "shiroikuma.jiyusagyoban"
    const val ACTION_EVENT = BuildConfig.APPLICATION_ID + ".action.BRIDGE_EVENT"

    private const val PREFS = "shiroikuma_bridge"
    private const val KEY_WATCHED = "watched"
    private const val KEY_SEQ = "seq"
    private const val KEY_KEEP_AWAKE = "keep_awake"
    private const val JOURNAL_MAX = 3000
    private const val JOURNAL_KEEP = 2000

    const val TYPE_TEXT = "text"
    const val TYPE_FILE = "file"
    const val TYPE_EDIT = "edit"
    const val TYPE_REACTION = "reaction"
    const val TYPE_REACTION_REMOVED = "reaction_removed"
    const val TYPE_FILE_STATE = "file_state"
    const val TYPE_STATUS = "status"

    private lateinit var app: Context
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sk-bridge").apply { isDaemon = true } }
    private val lock = Any()
    private val lastTransferState = ConcurrentHashMap<String, String>()
    private val lastStatus = ConcurrentHashMap<String, String>()

    fun install(context: Context) {
        app = context.applicationContext
        net.jami.services.BridgeTaps.tap = this
    }

    // ------------------------------------------------------------------ watch list

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(accountId: String, conversationId: String) = "$accountId|$conversationId"

    fun watched(): Set<String> = prefs().getStringSet(KEY_WATCHED, emptySet()) ?: emptySet()
    fun isWatched(accountId: String, conversationId: String) = key(accountId, conversationId) in watched()

    fun setWatched(accountId: String, conversationId: String, on: Boolean) = synchronized(lock) {
        val set = HashSet(watched())
        if (on) set.add(key(accountId, conversationId)) else set.remove(key(accountId, conversationId))
        prefs().edit().putStringSet(KEY_WATCHED, set).commit()
    }

    /** Never let the background duty cycle put this phone's accounts to sleep (bridge_keep_awake). */
    fun keepAwake(): Boolean = ::app.isInitialized && prefs().getBoolean(KEY_KEEP_AWAKE, false)
    fun setKeepAwake(on: Boolean) { prefs().edit().putBoolean(KEY_KEEP_AWAKE, on).commit() }

    /** How long a send holds the accounts awake: long enough for the peer to fetch the commit. */
    const val SEND_HOLD_MS = 90_000L

    // ------------------------------------------------------------------ pending sends

    /** A send waiting to learn the commit id the daemon gives it (our own message echoes back). */
    class Pending(val accountId: String, val conversationId: String, val kind: String, val match: String) {
        val latch = CountDownLatch(1)
        @Volatile var messageId: String = ""
        @Volatile var fileId: String = ""
    }
    private val pending = java.util.concurrent.CopyOnWriteArrayList<Pending>()

    fun expect(p: Pending) { pending.add(p) }
    fun forget(p: Pending) { pending.remove(p) }

    // ------------------------------------------------------------------ tap

    override fun onMessage(accountId: String, conversationId: String, id: String, type: String,
                           body: Map<String, String>, editions: Int, updated: Boolean) {
        val copy = HashMap(body)
        worker.execute { safe { handleMessage(accountId, conversationId, id, type, copy, updated) } }
    }

    override fun onReaction(accountId: String, conversationId: String, messageId: String, reaction: Map<String, String>) {
        val copy = HashMap(reaction)
        worker.execute { safe {
            val author = copy["author"].orEmpty()
            // A bridge_react waiting for its reaction's id gets it here, watched or not.
            if (own(accountId, author) == "1") {
                val match = "$messageId|${copy["body"].orEmpty()}"
                pending.firstOrNull { it.accountId == accountId && it.conversationId == conversationId &&
                        it.kind == TYPE_REACTION && it.match == match && it.messageId.isEmpty() }?.let {
                    it.messageId = copy["id"].orEmpty()
                    it.latch.countDown()
                }
            }
            if (!isWatched(accountId, conversationId)) return@safe
            emit(accountId, conversationId, mutableMapOf(
                "type" to TYPE_REACTION,
                "message_id" to copy["id"].orEmpty(),
                "event_id" to copy["id"].orEmpty(),
                "parent_id" to messageId,
                "author" to author,
                "own" to own(accountId, author),
                "body" to copy["body"].orEmpty(),
                "timestamp" to copy["timestamp"].orEmpty()))
        } }
    }

    override fun onReactionRemoved(accountId: String, conversationId: String, messageId: String, reactionId: String) {
        worker.execute { safe {
            if (!isWatched(accountId, conversationId)) return@safe
            // The removal callback carries no author; take it from the reaction's own event.
            val author = readJournal(accountId, conversationId)
                .lastOrNull { it["type"] == TYPE_REACTION && it["message_id"] == reactionId }?.get("author").orEmpty()
            emit(accountId, conversationId, mutableMapOf(
                "type" to TYPE_REACTION_REMOVED,
                "message_id" to reactionId,
                "event_id" to "$reactionId:removed",
                "parent_id" to messageId,
                "author" to author,
                "own" to if (author.isEmpty()) "" else own(accountId, author)))
        } }
    }

    override fun onTransfer(accountId: String, conversationId: String, interactionId: String, fileId: String, eventCode: Int) {
        worker.execute { safe {
            if (!isWatched(accountId, conversationId)) return@safe
            val state = Interaction.TransferStatus.fromIntFile(eventCode).name
            // The daemon repeats a state (progress refreshes, re-announcements); one event per change.
            if (lastTransferState.put("$accountId|$fileId", state) == state) return@safe
            val ev = mutableMapOf(
                "type" to TYPE_FILE_STATE,
                "message_id" to interactionId,
                "event_id" to "$interactionId:$state",
                "file_id" to fileId,
                "transfer_state" to state)
            if (state == Interaction.TransferStatus.TRANSFER_FINISHED.name) {
                resolveFile(accountId, conversationId, interactionId, fileId)?.let { f ->
                    ev["file_name"] = f.name
                    ev["file_size"] = f.length().toString()
                    grantedUri(f)?.let { ev["file_uri"] = it }
                }
            }
            emit(accountId, conversationId, ev)
        } }
    }

    override fun onStatus(accountId: String, conversationId: String, messageId: String, contactId: String, status: Int) {
        if (conversationId.isEmpty()) return   // legacy non-swarm path
        worker.execute { safe {
            if (!isWatched(accountId, conversationId)) return@safe
            // The sender's own entry is always DISPLAYED for its own message — it says nothing.
            if (ownUri(accountId)?.equals(contactId, ignoreCase = true) == true) return@safe
            val state = net.jami.model.interaction.Interaction.MessageStates.entries.getOrNull(status)?.name ?: "UNKNOWN"
            // Only for a message the journal knows (text, file or reaction). Edit and un-react commits
            // get delivery states too, under a commit id no other event carries — noise to a caller.
            if (readJournal(accountId, conversationId).none { it["message_id"] == messageId &&
                        (it["type"] == TYPE_TEXT || it["type"] == TYPE_FILE || it["type"] == TYPE_REACTION) })
                return@safe
            if (lastStatus.put("$accountId|$messageId|$contactId", state) == state) return@safe
            emit(accountId, conversationId, mutableMapOf(
                "type" to TYPE_STATUS,
                "message_id" to messageId,
                "event_id" to "$messageId:$contactId:$state",
                "peer" to contactId,
                "status" to state))
        } }
    }

    private fun handleMessage(accountId: String, conversationId: String, id: String, type: String,
                              body: Map<String, String>, updated: Boolean) {
        val author = body["author"].orEmpty()
        val isOwn = own(accountId, author)
        val kind = when (type) {
            "text/plain" -> TYPE_TEXT
            "application/data-transfer+json" -> TYPE_FILE
            else -> return     // member changes, call history, profile updates: not bridge traffic
        }
        // A send waiting for its id gets it here, whether or not the conversation is watched.
        if (isOwn == "1" && !updated) {
            val match = if (kind == TYPE_TEXT) body["body"].orEmpty() else body["displayName"].orEmpty()
            pending.firstOrNull { it.accountId == accountId && it.conversationId == conversationId &&
                    it.kind == kind && it.match == match && it.messageId.isEmpty() }?.let {
                it.messageId = id
                it.fileId = body["fileId"].orEmpty()
                it.latch.countDown()
            }
        }
        if (!isWatched(accountId, conversationId)) return
        if (updated) {
            // Only text edits are bridge events; file updates are reported through onTransfer.
            if (kind != TYPE_TEXT) return
            val text = body["body"].orEmpty()
            if (lastBody(accountId, conversationId, id) == text) return   // a status refresh, not an edit
            emit(accountId, conversationId, mutableMapOf(
                "type" to TYPE_EDIT,
                "message_id" to id,
                "event_id" to "$id:edit:${text.hashCode()}",
                "author" to author,
                "own" to isOwn,
                "body" to text,
                "deleted" to if (text.isEmpty()) "1" else "0",
                "timestamp" to body["timestamp"].orEmpty()))
            return
        }
        val ev = mutableMapOf(
            "type" to kind,
            "message_id" to id,
            "event_id" to id,
            "author" to author,
            "own" to isOwn,
            "body" to body["body"].orEmpty(),
            "parent_id" to body["reply-to"].orEmpty(),
            "timestamp" to body["timestamp"].orEmpty())
        if (kind == TYPE_FILE) {
            ev["file_id"] = body["fileId"].orEmpty()
            ev["file_name"] = body["displayName"].orEmpty()
            ev["file_size"] = body["totalSize"].orEmpty()
        }
        emit(accountId, conversationId, ev)
    }

    // ------------------------------------------------------------------ journal + broadcast

    private fun journal(accountId: String, conversationId: String): File =
        File(File(app.filesDir, "bridge").apply { mkdirs() }, "${accountId}_$conversationId.jsonl")

    private fun emit(accountId: String, conversationId: String, fields: MutableMap<String, String>) {
        val ev: Map<String, String>
        synchronized(lock) {
            val seq = prefs().getLong(KEY_SEQ, 0L) + 1
            prefs().edit().putLong(KEY_SEQ, seq).commit()
            fields["seq"] = seq.toString()
            fields["account"] = accountId
            fields["conversation"] = conversationId
            fields["received_ms"] = System.currentTimeMillis().toString()
            ev = fields
            val f = journal(accountId, conversationId)
            f.appendText(JSONObject(ev as Map<*, *>).toString() + "\n")
            trimIfNeeded(f)
        }
        broadcast(ev)
    }

    private fun trimIfNeeded(f: File) {
        val lines = f.readLines()
        if (lines.size > JOURNAL_MAX)
            f.writeText(lines.takeLast(JOURNAL_KEEP).joinToString("\n", postfix = "\n"))
    }

    fun readJournal(accountId: String, conversationId: String): List<Map<String, String>> = synchronized(lock) {
        val f = journal(accountId, conversationId)
        if (!f.exists()) return emptyList()
        f.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                o.keys().asSequence().associateWith { o.optString(it) }
            }.getOrNull()
        }
    }

    private fun lastBody(accountId: String, conversationId: String, messageId: String): String? =
        readJournal(accountId, conversationId).lastOrNull {
            it["message_id"] == messageId && (it["type"] == TYPE_TEXT || it["type"] == TYPE_EDIT)
        }?.get("body")

    private fun broadcast(ev: Map<String, String>) {
        if (!AutomationPrefs.isEnabled(app)) return
        if (!AutomationCallers.isPinnedTarget(app, TARGET)) return
        val i = Intent(ACTION_EVENT).setPackage(TARGET)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        ev.forEach { (k, v) -> i.putExtra(k, v) }
        app.sendBroadcast(i)
    }

    fun toBundle(ev: Map<String, String>) = Bundle().apply { ev.forEach { (k, v) -> putString(k, v) } }

    // ------------------------------------------------------------------ helpers

    /** "1" when [author] is this account itself (our own message echoing back), else "0". */
    private fun own(accountId: String, author: String): String {
        val me = ownUri(accountId)
        return if (author.isEmpty() || (me != null && me.equals(author, ignoreCase = true))) "1" else "0"
    }

    fun ownUri(accountId: String): String? =
        JamiApplication.instance?.mAccountService?.getAccount(accountId)?.username
            ?.substringAfterLast(':')?.takeIf { it.isNotEmpty() }

    /** The finished file on disk: the client's own copy first (it is under the FileProvider root). */
    fun resolveFile(accountId: String, conversationId: String, messageId: String, fileId: String): File? {
        val svc = JamiApplication.instance?.mAccountService
        val conv = svc?.getAccount(accountId)?.getSwarm(conversationId)
        (conv?.getMessage(messageId) as? DataTransfer)?.destination?.takeIf { it.isFile }?.let { return it }
        if (fileId.isEmpty()) return null
        return runCatching {
            val path = arrayOf("")
            val total = longArrayOf(0)
            val progress = longArrayOf(0)
            daemon { JamiService.fileTransferInfo(accountId, conversationId, fileId, path, total, progress) }
            File(path[0]).takeIf { path[0].isNotEmpty() && it.isFile }
        }.getOrNull()
    }

    /** A content URI for [f], read-granted to 自由作業盤 — or null when the file is outside our roots. */
    fun grantedUri(f: File): String? = runCatching {
        if (!AutomationCallers.isPinnedTarget(app, TARGET)) return null
        val uri = ContentUri.getUriForFile(app, f)
        app.grantUriPermission(TARGET, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        uri.toString()
    }.getOrNull()

    /** Run a daemon call on the daemon executor and wait for it — SWIG calls stay on one thread. */
    fun <T> daemon(block: () -> T): T {
        val ex = JamiApplication.instance?.mExecutor ?: return block()
        return ex.submit<T> { block() }.get(15, TimeUnit.SECONDS)
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "bridge event dropped", t) }
    }
}
