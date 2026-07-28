package cx.ring.utils

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Collect and restore the chat corpus — the part of a backup that makes a phone migration real.
 *
 * The daemon keeps every swarm conversation as a git repository under
 * `filesDir/<accountId>/conversations/<convId>`, and rebuilds it purely by scanning that directory
 * (`ConversationModule::loadConversations`). So history that is on disk when an account loads is
 * history nobody has to download. Everything here exists to get those directories into a zip and
 * back out again.
 *
 * Two trees hold a conversation's bytes:
 *
 *  - `filesDir/<accountId>/...` — the daemon's: the repositories, `convInfo`, the per-conversation
 *    state files, peer profiles. Small, and the whole point.
 *  - `filesDir/conversation_data/<accountId>/<convId>/<name>` — the client's, where
 *    `ConversationFacade.sendFile` moves outgoing files and incoming downloads land. This is where
 *    attachment payloads actually live, and it is the only copy worth packing: the daemon's
 *    `conversation_data/<convId>/<fileId>` entries are *hard links* to these same inodes
 *    (`fileutils::createFileLink(..., hard=true)`, same filesystem). Packing both would store every
 *    photo twice, so we pack the client tree and record a fileId → filename map to rebuild the
 *    links on restore.
 */
object ChatArchive {

    private const val TAG = "SK-CHATARC"

    /** Zip prefixes. Texts are worth deflating; payloads are already-compressed media. */
    const val TEXTS = "chat_texts"
    const val FILES = "chat_files"
    const val INDEX_ENTRY = "chats.json"

    /** Payload subtree for daemon-side file entries that have no client-tree twin to link to. */
    private const val UNLINKED = "__unlinked"

    /**
     * Stand-in for an empty directory. A zip cannot carry one as a file, and a git repository needs
     * `refs/heads` to exist even when every ref is packed — libgit2 refuses to open a repository
     * whose `refs` directory is gone. The marker is written into the archive and never onto disk:
     * extraction only creates its parent.
     */
    private const val KEEPDIR = ".exim-keepdir"

    /** Non-file contents of a daemon `conversation_data/<convId>/` — see ConversationDirectories. */
    private val STATE_NAMES = setOf(
        "preferences", "status", "sending", "fetched", "activeCalls", "hostedCalls", "cached",
        "members")

    /** `getFileId()` in the daemon: `<40-hex commit>_<tid>[.ext]`. Anything else is state. */
    private val FILE_ID = Regex("^[0-9a-f]{40}_[0-9]+(\\.[^./]+)?$")

    private fun isStateEntry(name: String) = name in STATE_NAMES || !FILE_ID.matches(name)

    // --- layout --------------------------------------------------------------------------------

    fun daemonDir(c: Context, accountId: String) = File(c.filesDir, accountId)
    fun clientFilesDir(c: Context, accountId: String) =
        File(File(c.filesDir, "conversation_data"), accountId)

    // --- progress ------------------------------------------------------------------------------

    /** One item finished, of [bytes] size. Called often — keep implementations cheap. */
    fun interface Progress {
        fun step(bytes: Long, label: String)
    }

    class Tally(var files: Int = 0, var bytes: Long = 0) {
        fun add(n: Long) { files++; bytes += n }
    }

    /** What one account contributes to the archive, and the map needed to restore its links. */
    class AccountChats(
        val accountId: String,
        val uri: String,
        val conversations: List<String>,
        /** convId → (fileId → client filename); an empty name means the payload is packed raw. */
        val links: Map<String, Map<String, String>>,
        val texts: Tally,
        val payload: Tally,
    ) {
        fun toJson(): JSONObject {
            val l = JSONObject()
            for ((conv, m) in links) l.put(conv, JSONObject().apply { for ((k, v) in m) put(k, v) })
            return JSONObject()
                .put("uri", uri)
                .put("conversations", JSONArray(conversations))
                .put("links", l)
                .put("textFiles", texts.files).put("textBytes", texts.bytes)
                .put("payloadFiles", payload.files).put("payloadBytes", payload.bytes)
        }

        companion object {
            fun fromJson(accountId: String, o: JSONObject): AccountChats {
                val convs = ArrayList<String>()
                o.optJSONArray("conversations")?.let { a ->
                    for (i in 0 until a.length()) convs.add(a.optString(i))
                }
                val links = HashMap<String, Map<String, String>>()
                o.optJSONObject("links")?.let { l ->
                    for (conv in l.keys()) {
                        val inner = l.optJSONObject(conv) ?: continue
                        val m = HashMap<String, String>()
                        for (fid in inner.keys()) m[fid] = inner.optString(fid)
                        links[conv] = m
                    }
                }
                return AccountChats(
                    accountId, o.optString("uri"), convs, links,
                    Tally(o.optInt("textFiles"), o.optLong("textBytes")),
                    Tally(o.optInt("payloadFiles"), o.optLong("payloadBytes")))
            }
        }
    }

    // --- indexing ------------------------------------------------------------------------------

    /**
     * Walks both trees for each (accountId, uri) and returns what an export would contain — the
     * counts and byte totals shown before a single byte is written, plus the link map.
     *
     * The enumeration helpers below are shared with the writers, so the pre-flight figure and the
     * archive can never drift apart.
     */
    fun index(c: Context, accounts: List<Pair<String, String>>): List<AccountChats> =
        accounts.map { (accountId, uri) ->
            val conversations = (File(daemonDir(c, accountId), "conversations").listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.map { it.name } ?: emptyList()).sorted()
            val links = linkMap(c, accountId)
            val texts = Tally()
            for ((f, _) in textEntries(c, accountId)) texts.add(if (f.isDirectory) 0L else f.length())
            val payload = Tally()
            for ((f, _) in payloadEntries(c, accountId, links)) payload.add(f.length())
            AccountChats(accountId, uri, conversations, links, texts, payload)
        }

    /**
     * convId → (fileId → client filename). Matching is by inode: the daemon's entry and the client
     * file are the same inode whenever the hard link succeeded, which is the normal case on one
     * filesystem. An entry with no match is packed on its own (empty filename).
     */
    private fun linkMap(c: Context, accountId: String): Map<String, Map<String, String>> {
        val out = HashMap<String, Map<String, String>>()
        val convData = File(daemonDir(c, accountId), "conversation_data")
        for (convDir in convData.listFiles().orEmpty()) {
            if (!convDir.isDirectory) continue
            val byIno = HashMap<Long, String>()
            for (f in File(clientFilesDir(c, accountId), convDir.name).listFiles().orEmpty()) {
                if (!isRegular(f)) continue
                runCatching { byIno[Os.stat(f.absolutePath).st_ino] = f.name }
            }
            val m = HashMap<String, String>()
            for (f in convDir.listFiles().orEmpty()) {
                if (!isRegular(f) || isStateEntry(f.name)) continue
                val ino = runCatching { Os.stat(f.absolutePath).st_ino }.getOrNull()
                m[f.name] = ino?.let { byIno[it] } ?: ""
            }
            if (m.isNotEmpty()) out[convDir.name] = m
        }
        return out
    }

    /** Every file the texts category carries, as (file on disk, path relative to the account). */
    private fun textEntries(c: Context, accountId: String): Sequence<Pair<File, String>> = sequence {
        val root = daemonDir(c, accountId)
        yieldAll(walk(File(root, "conversations"), "conversations"))
        yieldAll(walk(File(root, "profiles"), "profiles"))
        for (name in listOf("convInfo", "profile.vcf", "history.db")) {
            val f = File(root, name)
            if (isRegular(f)) yield(f to name)
        }
        for (convDir in File(root, "conversation_data").listFiles().orEmpty()) {
            if (!convDir.isDirectory) continue
            for (f in convDir.listFiles().orEmpty()) {
                if (isRegular(f) && isStateEntry(f.name))
                    yield(f to "conversation_data/${convDir.name}/${f.name}")
            }
        }
    }

    /** Every file the files category carries: client payloads, plus any unlinked daemon entry. */
    private fun payloadEntries(
        c: Context, accountId: String, links: Map<String, Map<String, String>>
    ): Sequence<Pair<File, String>> = sequence {
        for (convDir in clientFilesDir(c, accountId).listFiles().orEmpty()) {
            if (!convDir.isDirectory) continue
            for (f in convDir.listFiles().orEmpty())
                if (isRegular(f)) yield(f to "${convDir.name}/${f.name}")
        }
        val convData = File(daemonDir(c, accountId), "conversation_data")
        for ((conv, m) in links) for ((fileId, name) in m) {
            if (name.isNotEmpty()) continue
            val f = File(File(convData, conv), fileId)
            if (isRegular(f)) yield(f to "$UNLINKED/$conv/$fileId")
        }
    }

    private fun walk(root: File, prefix: String): Sequence<Pair<File, String>> = sequence {
        if (!root.isDirectory) return@sequence
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(root to prefix)
        while (stack.isNotEmpty()) {
            val (dir, rel) = stack.removeLast()
            val children = dir.listFiles().orEmpty()
            if (children.isEmpty()) {
                yield(dir to "$rel/$KEEPDIR")
                continue
            }
            for (f in children) {
                val childRel = "$rel/${f.name}"
                when {
                    isSymlink(f) -> Log.w(TAG, "skipping symlink $childRel")
                    f.isDirectory -> stack.addLast(f to childRel)
                    isRegular(f) -> yield(f to childRel)
                }
            }
        }
    }

    private fun isRegular(f: File): Boolean = try {
        OsConstants.S_ISREG(Os.lstat(f.absolutePath).st_mode)
    } catch (_: Exception) { f.isFile }

    private fun isSymlink(f: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode)
    } catch (_: Exception) { false }

    // --- writing -------------------------------------------------------------------------------

    fun writeTexts(zip: ZipOutputStream, c: Context, a: AccountChats, progress: Progress?) {
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        for ((f, rel) in textEntries(c, a.accountId))
            copyIn(zip, f, "$TEXTS/${a.accountId}/$rel", progress)
    }

    /** Payloads go in uncompressed — photos and video do not deflate, they only burn CPU. */
    fun writeFiles(zip: ZipOutputStream, c: Context, a: AccountChats, progress: Progress?) {
        zip.setLevel(Deflater.NO_COMPRESSION)
        try {
            for ((f, rel) in payloadEntries(c, a.accountId, a.links))
                copyIn(zip, f, "$FILES/${a.accountId}/$rel", progress)
        } finally {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        }
    }

    private fun copyIn(zip: ZipOutputStream, f: File, entryName: String, progress: Progress?) {
        try {
            zip.putNextEntry(ZipEntry(entryName))
            val n = if (entryName.endsWith("/$KEEPDIR")) 0L
            else f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            progress?.step(n, f.name)
        } catch (e: Exception) {
            Log.w(TAG, "export: skipping $entryName — ${e.message}")
        }
    }

    // --- extraction ----------------------------------------------------------------------------

    /**
     * Writes every archive entry under [prefix] into [dest], keeping the relative layout.
     * Entry CRCs are checked by the inflater, so a truncated archive fails here rather than later.
     */
    fun extract(
        source: SettingsExport.ZipSource, prefix: String, dest: File, progress: Progress?
    ): Tally {
        val t = Tally()
        source.forEach(prefix) { name, _, input ->
            val rel = name.removePrefix(prefix)
            if (rel.isEmpty() || rel.contains("..")) return@forEach
            val target = File(dest, rel)
            target.parentFile?.mkdirs()
            // An empty-directory marker exists only to have created that parent.
            if (rel.substringAfterLast('/') == KEEPDIR) return@forEach
            val n = target.outputStream().use { input.copyTo(it) }
            t.add(n)
            progress?.step(n, target.name)
        }
        return t
    }

    // --- installing ----------------------------------------------------------------------------

    class InstallResult {
        var restored = 0
        var present = 0
        var deleted = 0
        var filesRestored = 0
        var filesPresent = 0
        var relinked = 0
        val deletedIds = ArrayList<String>()
        val notes = ArrayList<String>()
    }

    /**
     * Moves a staged account tree into place for an account that does not exist yet. Staging lives
     * on the same filesystem, so each move is a rename: no second copy, no half-written state.
     */
    fun installFresh(c: Context, targetId: String, staging: File, r: InstallResult) {
        val daemonStage = File(staging, "daemon")
        val filesStage = File(staging, "files")
        if (daemonStage.isDirectory) {
            val target = daemonDir(c, targetId)
            target.parentFile?.mkdirs()
            if (!daemonStage.renameTo(target)) moveTree(daemonStage, target, r)
            r.restored += File(target, "conversations").listFiles()?.count { it.isDirectory } ?: 0
        }
        if (filesStage.isDirectory) {
            val target = clientFilesDir(c, targetId)
            target.parentFile?.mkdirs()
            if (!filesStage.renameTo(target)) moveTree(filesStage, target, r)
            r.filesRestored += countFiles(target)
        }
    }

    /**
     * Merges a staged tree into an account that is already on the device.
     *
     * Conversations already present are left strictly alone: swarm history is append-only and the
     * client cannot truncate a repository (only `removeConversation` deletes one, wholesale), so a
     * repository still on disk already contains everything a backup of this device could hold.
     * Conversations the live `convInfo` flags as removed are deliberate deletions — they are
     * reported, not resurrected; the dedicated restore flow handles those.
     */
    fun installMerge(c: Context, targetId: String, staging: File, r: InstallResult) {
        val target = daemonDir(c, targetId)
        val removed = removedConversations(target)
        val stagedRepos = File(File(staging, "daemon"), "conversations")
        for (repo in stagedRepos.listFiles().orEmpty()) {
            if (!repo.isDirectory) continue
            val live = File(File(target, "conversations"), repo.name)
            when {
                live.isDirectory -> r.present++
                repo.name in removed -> { r.deleted++; r.deletedIds.add(repo.name) }
                else -> {
                    live.parentFile?.mkdirs()
                    if (repo.renameTo(live) || moveTree(repo, live, r)) r.restored++
                    // The state files only make sense alongside a repository we actually restored.
                    val stateSrc = File(File(File(staging, "daemon"), "conversation_data"), repo.name)
                    if (stateSrc.isDirectory)
                        mergeMissing(stateSrc, File(File(target, "conversation_data"), repo.name), r)
                }
            }
        }
        // Peer profiles and the legacy DB: fill gaps, never overwrite what is live.
        mergeMissing(File(File(staging, "daemon"), "profiles"), File(target, "profiles"), r)
        for (name in listOf("profile.vcf", "history.db")) {
            val src = File(File(staging, "daemon"), name)
            val dst = File(target, name)
            if (src.isFile && !dst.exists()) src.renameTo(dst)
        }
        // Attachments merge per file, including into conversations that were already here — this
        // is what makes re-importing on the same device worth doing.
        val filesStage = File(staging, "files")
        val filesTarget = clientFilesDir(c, targetId)
        for (convDir in filesStage.listFiles().orEmpty()) {
            if (!convDir.isDirectory || convDir.name == UNLINKED) continue
            mergeMissing(convDir, File(filesTarget, convDir.name), r)
        }
        val unlinked = File(filesStage, UNLINKED)
        for (convDir in unlinked.listFiles().orEmpty()) {
            if (!convDir.isDirectory) continue
            mergeMissing(convDir, File(File(target, "conversation_data"), convDir.name), r)
        }
    }

    /** Moves files from [src] into [dst] that are not already there; counts both outcomes. */
    private fun mergeMissing(src: File, dst: File, r: InstallResult) {
        if (!src.isDirectory) return
        dst.mkdirs()
        for (f in src.listFiles().orEmpty()) {
            val target = File(dst, f.name)
            when {
                f.isDirectory -> mergeMissing(f, target, r)
                target.exists() -> r.filesPresent++
                f.renameTo(target) -> r.filesRestored++
                else -> {
                    runCatching { f.copyTo(target, overwrite = false) }
                        .onSuccess { r.filesRestored++ }
                        .onFailure { r.notes.add("could not restore ${f.name}: ${it.message}") }
                }
            }
        }
    }

    /** Rename across directories can fail; fall back to a recursive copy so nothing is lost. */
    private fun moveTree(src: File, dst: File, r: InstallResult): Boolean = try {
        src.copyRecursively(dst, overwrite = false)
        src.deleteRecursively()
        true
    } catch (e: Exception) {
        r.notes.add("could not move ${src.name}: ${e.message}")
        false
    }

    /**
     * Recreates the daemon's hard links into the client tree, so the daemon can still serve those
     * files to peers that re-ask for them. A link that already exists is left alone.
     */
    fun relink(c: Context, accountId: String, links: Map<String, Map<String, String>>, r: InstallResult) {
        val convData = File(daemonDir(c, accountId), "conversation_data")
        for ((conv, m) in links) for ((fileId, name) in m) {
            if (name.isEmpty()) continue
            val target = File(File(convData, conv), fileId)
            if (target.exists()) continue
            val src = File(File(clientFilesDir(c, accountId), conv), name)
            if (!src.isFile) continue
            target.parentFile?.mkdirs()
            runCatching { Os.link(src.absolutePath, target.absolutePath) }
                .onSuccess { r.relinked++ }
                .onFailure { Log.w(TAG, "relink ${target.name} failed: ${it.message}") }
        }
    }

    // --- convInfo ------------------------------------------------------------------------------

    /** Conversation ids the account's `convInfo` flags as removed (ConvInfo::isRemoved). */
    fun removedConversations(accountDir: File): Set<String> {
        val f = File(accountDir, "convInfo")
        if (!f.isFile) return emptySet()
        return try {
            val b = f.readBytes()
            MsgPackLite.topEntries(b).filter { e ->
                val created = MsgPackLite.intField(b, e, "createdMs")
                    ?: (MsgPackLite.intField(b, e, "created")?.times(1000)) ?: 0L
                val removed = MsgPackLite.intField(b, e, "removedMs")
                    ?: (MsgPackLite.intField(b, e, "removed")?.times(1000)) ?: 0L
                removed >= created && removed > 0L
            }.map { it.key }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "convInfo unreadable: ${e.message}")
            emptySet()
        }
    }

    /**
     * Drops [convIds] from `convInfo` so the daemon stops treating them as deleted. It rebuilds the
     * entry from the restored repository on the next scan (`convInfos_[repository] = sconv->info`),
     * which is why deleting the entry is enough and no timestamp has to be invented.
     */
    fun forgetRemovals(accountDir: File, convIds: Set<String>): Boolean {
        val f = File(accountDir, "convInfo")
        if (!f.isFile || convIds.isEmpty()) return false
        return try {
            val rewritten = MsgPackLite.withoutKeys(f.readBytes(), convIds)
            File(accountDir, "convInfo.tmp").let { tmp ->
                tmp.writeBytes(rewritten)
                tmp.renameTo(f) || run { f.writeBytes(rewritten); tmp.delete(); true }
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not rewrite convInfo: ${e.message}")
            false
        }
    }

    // --- misc ----------------------------------------------------------------------------------

    fun stagingDir(c: Context): File = File(c.filesDir, ".exim-staging")

    /** Free space on the data volume, which is where staging and the final tree both live. */
    fun freeBytes(c: Context): Long = try {
        val s = StatFs(c.filesDir.absolutePath)
        s.availableBlocksLong * s.blockSizeLong
    } catch (_: Exception) { Long.MAX_VALUE }

    fun countFiles(dir: File): Int {
        if (!dir.isDirectory) return 0
        var n = 0
        for (f in dir.listFiles().orEmpty()) n += if (f.isDirectory) countFiles(f) else 1
        return n
    }

    fun human(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KiB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MiB".format(b / 1048576.0)
        else -> "%.2f GiB".format(b / 1073741824.0)
    }
}
