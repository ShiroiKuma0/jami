package cx.ring.utils

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import net.jami.model.Conversation
import net.jami.model.interaction.DataTransfer
import net.jami.model.interaction.Interaction.TransferStatus
import net.jami.services.AccountService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The ledger behind the chat-files panel: what every conversation's attachments actually cost on
 * disk, which chat message each one belongs to, and how to remove one for good.
 *
 * It reads the same two trees [ChatArchive] packs, and for the same reason — that is where the
 * gigabytes are:
 *
 *  - `filesDir/conversation_data/<accountId>/<convId>/<name>` — the client tree. The bytes.
 *  - `filesDir/<accountId>/conversation_data/<convId>/<fileId>` — the daemon's entry, normally a
 *    symlink (or hard link) to the client file. It holds bytes of its own only when the client
 *    twin is gone.
 *
 * Sizes come from the filesystem, never from the commit's `totalSize`: a file that was never
 * downloaded occupies nothing, and a panel about disk usage must say so.
 *
 * ## What "delete" can mean
 *
 * The daemon refuses to edit a commit it did not author — `ConversationModule::Impl::editMessage`
 * checks `commit->authorId == username_` and gives up otherwise. So:
 *
 *  - **Our own file** → the message can be deleted from the swarm. The daemon removes its own copy
 *    and commits a `fileDeleted` edit, which every device and every member follows. Gone for good.
 *  - **A received file** → the commit belongs to the sender and nothing can retract it. Only this
 *    device's copy goes; the chat entry stays and could be downloaded again from whoever still has
 *    it. That is the honest limit, and the panel says so rather than pretending otherwise.
 */
object ChatFiles {

    private const val TAG = "SK-CHATFILES"

    const val DATA_TRANSFER = "application/data-transfer+json"

    /** `getFileId()` in the daemon: `<40-hex commit>_<tid>[.ext]`. Anything else is state. */
    private val FILE_ID = Regex("^[0-9a-f]{40}_[0-9]+(\\.[^./]+)?$")

    /** Non-file contents of a daemon `conversation_data/<convId>/` — see ConversationDirectories. */
    private val STATE_NAMES = setOf(
        "preferences", "status", "sending", "fetched", "activeCalls", "hostedCalls", "cached",
        "members")

    /** Extensions worth asking Glide for a thumbnail of. */
    private val THUMBABLE = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "mp4", "webm", "mkv", "3gp")

    // --- model ----------------------------------------------------------------------------------

    /**
     * One file on disk. [file] is where the bytes are; [link] is the daemon's entry pointing at
     * them, which has to go too or a hard link would keep the inode alive.
     *
     * Everything below [messageId] is filled in by [index] and is only known once the conversation
     * has been read.
     */
    class Item(
        val accountId: String,
        val convId: String,
        val file: File,
        val bytes: Long,
        val mtime: Long,
    ) {
        var link: File? = null
        var messageId: String? = null
        var fileId: String? = null
        var commitName: String? = null
        var timestamp: Long = 0L
        var outgoing = false

        /** True once a chat message has been matched to this file. */
        var known = false

        val name: String get() = commitName ?: file.name
        val whenMs: Long get() = if (timestamp > 0L) timestamp else mtime
        val thumbable: Boolean
            get() = name.substringAfterLast('.', "").lowercase() in THUMBABLE

        /** Whether this file's chat message COULD be deleted — only ever true for our own files. */
        val removesMessage: Boolean get() = known && outgoing && messageId != null

        val direction: Direction get() = when {
            !known -> Direction.UNKNOWN
            outgoing -> Direction.SENT
            else -> Direction.RECEIVED
        }
    }

    /**
     * Which way a file went. It is only knowable once the conversation has been read, so files sit
     * in [UNKNOWN] until then — and stay there if no message claims them, which is the honest
     * answer for a payload left behind by something that no longer exists.
     */
    enum class Direction { SENT, RECEIVED, UNKNOWN }

    /** One conversation's files. [title] and [indexed] are filled in later, on demand. */
    class Bucket(val accountId: String, val convId: String) {
        val items = ArrayList<Item>()
        var title: String? = null
        var avatar: android.graphics.drawable.Drawable? = null
        var indexed = false
        var indexing = false
        val bytes: Long get() = items.sumOf { it.bytes }
        val count: Int get() = items.size
        val key: String get() = "$accountId/$convId"

        /**
         * The conversation's files split by direction, empty groups dropped.
         *
         * Order is fixed — sent, received, orphans — rather than biggest-first like every other
         * level of the page. These are a classification, not a ranking, and there are only ever
         * two or three: a group you reach for repeatedly should be where it was last time.
         */
        fun byDirection(): List<Pair<Direction, List<Item>>> =
            listOf(Direction.SENT, Direction.RECEIVED, Direction.UNKNOWN)
                .map { dir -> dir to items.filter { it.direction == dir } }
                .filter { it.second.isNotEmpty() }
    }

    /** One account's conversations, biggest first. */
    class Group(val accountId: String) {
        val buckets = ArrayList<Bucket>()
        var label: String = accountId
        var avatar: android.graphics.drawable.Drawable? = null
        val bytes: Long get() = buckets.sumOf { it.bytes }
        val count: Int get() = buckets.sumOf { it.count }
    }

    // --- scanning -------------------------------------------------------------------------------

    /**
     * Walks one account's two trees and returns a bucket per conversation that has files, largest
     * first. Pure filesystem work: no daemon call, no git log, so the panel can show real totals
     * the moment it opens.
     */
    fun scanAccount(c: Context, accountId: String): List<Bucket> {
        val clientRoot = File(File(c.filesDir, "conversation_data"), accountId)
        val daemonRoot = File(File(c.filesDir, accountId), "conversation_data")
        val convIds = LinkedHashSet<String>()
        for (d in clientRoot.listFiles().orEmpty()) if (d.isDirectory) convIds.add(d.name)
        for (d in daemonRoot.listFiles().orEmpty()) if (d.isDirectory) convIds.add(d.name)

        val out = ArrayList<Bucket>()
        for (convId in convIds) {
            val bucket = Bucket(accountId, convId)
            val byName = HashMap<String, Item>()
            val byIno = HashMap<Long, Item>()
            for (f in File(clientRoot, convId).listFiles().orEmpty()) {
                if (!isRegular(f)) continue
                val item = Item(accountId, convId, f, f.length(), f.lastModified())
                bucket.items.add(item)
                byName[f.name] = item
                runCatching { byIno[Os.stat(f.absolutePath).st_ino] = item }
            }
            // The daemon's own entries: normally just a link back to a file already counted, so
            // attach it to that item. An entry with no twin holds bytes nobody else counts.
            for (f in File(daemonRoot, convId).listFiles().orEmpty()) {
                if (f.name in STATE_NAMES || !FILE_ID.matches(f.name)) continue
                when {
                    isSymlink(f) -> {
                        val target = runCatching { Os.readlink(f.absolutePath) }.getOrNull()
                            ?.substringAfterLast('/')
                        val item = target?.let { byName[it] }
                        when {
                            item != null -> { item.link = f; item.fileId = f.name }
                            // A dangling symlink costs nothing; leave it be.
                            f.isFile -> bucket.items.add(
                                Item(accountId, convId, f, f.length(), f.lastModified())
                                    .also { it.fileId = f.name })
                        }
                    }
                    isRegular(f) -> {
                        val item = runCatching { Os.stat(f.absolutePath).st_ino }.getOrNull()
                            ?.let { byIno[it] }
                        if (item != null) { item.link = f; item.fileId = f.name }
                        else bucket.items.add(
                            Item(accountId, convId, f, f.length(), f.lastModified())
                                .also { it.fileId = f.name })
                    }
                }
            }
            if (bucket.items.isNotEmpty()) {
                bucket.items.sortByDescending { it.bytes }
                out.add(bucket)
            }
        }
        out.sortByDescending { it.bytes }
        return out
    }

    // --- indexing -------------------------------------------------------------------------------

    /**
     * Attaches chat messages to the files of one conversation, so the panel knows a file's real
     * name, when it was sent, which way it went — and whether its message can be deleted at all.
     *
     * The daemon's own message search does the walking (`Conversation::search` on a worker of its
     * own), filtered to file commits; this is the same call the media gallery makes.
     *
     * **Call from a worker thread** — it blocks. The timeout is not paranoia: searching a
     * conversation the daemon has not loaded emits nothing at all, not even the finished signal,
     * so without it the wait would never end.
     */
    fun index(accounts: AccountService, conversation: Conversation, bucket: Bucket): Boolean {
        val found = runCatching {
            accounts.searchConversation(
                conversation.accountId, conversation.uri, type = DATA_TRANSFER)
                .flatMapIterable { it.results }
                .toList()
                .timeout(60, TimeUnit.SECONDS)
                .blockingGet()
        }.getOrElse {
            Log.w(TAG, "index ${bucket.convId}: ${it.message}")
            return false
        }

        val byPath = HashMap<String, Item>()
        val byFileId = HashMap<String, Item>()
        for (item in bucket.items) {
            byPath[canonical(item.file)] = item
            item.fileId?.let { byFileId[it] = item }
        }
        for (interaction in found) {
            val transfer = interaction as? DataTransfer ?: continue
            val messageId = transfer.messageId ?: continue
            // daemonPath comes from the daemon's own fileTransferInfo(), so it points at the entry
            // that really backs this commit — canonicalised, that is the client file. Matching by
            // name would go wrong the moment two files in one chat share a name.
            val item = transfer.daemonPath?.let { byPath[canonical(it)] }
                ?: transfer.fileId?.let { byFileId[it] }
                ?: continue
            item.messageId = messageId
            transfer.fileId?.let { item.fileId = it }
            item.commitName = transfer.body
            item.timestamp = transfer.timestamp
            item.outgoing = transfer.isOutgoing
            item.known = true
        }
        bucket.indexed = true
        return true
    }

    private fun canonical(f: File): String =
        runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    // --- deleting -------------------------------------------------------------------------------

    class Outcome {
        var files = 0
        var bytes = 0L
        var messages = 0
        /** Own files whose message was deliberately left standing, so the peer keeps its copy. */
        var kept = 0
        var failed = 0
    }

    /**
     * Removes one file, and — only when [removeMessages] asks for it — its chat message too.
     *
     * Both copies of the bytes always go: the client file holds them, and the daemon's entry has
     * to follow, since a hard link would otherwise keep the inode and the freed space would never
     * appear.
     *
     * With [removeMessages] false this is the *soft* delete: the message stays exactly as it was,
     * so the other side keeps its copy and nothing about their chat changes — and the file remains
     * downloadable here for as long as somebody in the conversation still has it. That works in
     * both directions, because every layer below is direction-agnostic: `Conversation::downloadFile`
     * never asks who authored the commit, and `askForFileChannel` with no device id walks every
     * device of every member. The commit's `sha3sum` verifies whatever comes back.
     *
     * **Call from a worker thread.**
     */
    fun delete(accounts: AccountService, item: Item, out: Outcome, removeMessages: Boolean) {
        val conversation = accounts.getAccount(item.accountId)?.getSwarm(item.convId)
        val bytes = item.bytes
        val gone = !item.file.exists() || item.file.delete()
        item.link?.let { link -> runCatching { link.delete() } }

        val messageId = item.messageId
        if (item.removesMessage && !removeMessages) out.kept++
        if (item.removesMessage && removeMessages && conversation != null && messageId != null) {
            // An empty edit of our own file commit: the daemon drops its copy and commits the
            // deletion, which every member and every one of our devices then follows.
            accounts.deleteConversationMessage(item.accountId, conversation.uri, messageId)
            out.messages++
        } else if (conversation != null && messageId != null) {
            // Nothing to retract — just tell the open chat that the file is no longer here, so the
            // bubble goes back to offering a download instead of claiming to hold it.
            (conversation.getMessage(messageId) as? DataTransfer)?.let { transfer ->
                transfer.transferStatus = TransferStatus.FILE_AVAILABLE
                transfer.bytesProgress = 0
                conversation.updateInteraction(transfer)
            }
        }

        if (gone) { out.files++; out.bytes += bytes } else out.failed++
    }

    // --- filesystem helpers ---------------------------------------------------------------------

    private fun isRegular(f: File): Boolean = try {
        OsConstants.S_ISREG(Os.lstat(f.absolutePath).st_mode)
    } catch (_: Exception) { f.isFile }

    private fun isSymlink(f: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode)
    } catch (_: Exception) { false }
}
