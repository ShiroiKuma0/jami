package cx.ring.utils

import android.content.Context
import android.util.Log
import cx.ring.utils.SettingsExport.Cat
import net.jami.model.AccountConfig
import net.jami.model.ConfigKey
import net.jami.services.AccountService
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/**
 * Drives one export or one import from start to finish: what the panel's buttons and the headless
 * receiver both call, so there is exactly one implementation of the awkward parts.
 *
 * The import order is the whole trick. A restored account keeps the account id it had in the
 * archive, so the chat corpus is unpacked to the very paths it came from and the account is created
 * *on top of it* — by the time the daemon builds its ConversationModule and scans
 * `conversations/`, the history is already there. Nothing is rewritten, nothing races a syncing
 * daemon, and no restart is needed. When the account is already on this device the flow inverts:
 * quiesce it, merge in only what is missing, then ask the daemon to re-scan.
 */
class EximRunner(
    private val app: Context,
    private val accounts: AccountService,
    private val watcher: Watcher? = null,
) {

    fun interface Watcher {
        fun onProgress(p: Progress)
    }

    class Progress {
        @Volatile var phase: String = ""
        @Volatile var files: Int = 0
        @Volatile var totalFiles: Int = 0
        @Volatile var bytes: Long = 0L
        @Volatile var totalBytes: Long = 0L

        fun line(): String {
            if (totalFiles <= 0) return phase
            return "$phase — $files/$totalFiles files, " +
                    "${ChatArchive.human(bytes)}/${ChatArchive.human(totalBytes)}"
        }
    }

    class Report {
        val lines = ArrayList<String>()
        var failure: String? = null
        fun add(s: String) { if (s.isNotBlank()) lines.add(s.trim()) }
        val text: String get() = lines.joinToString("\n")
    }

    val progress = Progress()
    private var lastTick = 0L

    private fun tick(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastTick < 400) return
        lastTick = now
        watcher?.onProgress(progress)
    }

    private fun phase(name: String) {
        progress.phase = name
        tick(true)
    }

    private val step = ChatArchive.Progress { bytes, _ ->
        progress.files++
        progress.bytes += bytes
        tick()
    }

    // --- export ---------------------------------------------------------------------------------

    /** What an export of [cats] would carry, per Jami account — the pre-flight figures. */
    fun indexChats(cats: List<Cat>): List<ChatArchive.AccountChats> =
        if (cats.none { it.isChat }) emptyList()
        else ChatArchive.index(app, accounts.getAccounts()
            .filter { it.isJami }
            .map { it.accountId to (it.username ?: "") })

    fun exportInto(cats: List<Cat>, out: OutputStream): Report {
        val report = Report()
        var archives = emptyMap<String, ByteArray>()
        var meta: JSONObject? = null
        if (Cat.ACCOUNTS in cats) {
            phase("Exporting accounts")
            val (a, m, notes) = SettingsExport.collectAccountArchives(app, accounts)
            archives = a; meta = m
            report.add("Accounts: ${a.size} exported")
            report.add(notes)
        }

        phase("Indexing chats")
        val chats = indexChats(cats)
        progress.totalFiles = chats.sumOf {
            (if (Cat.CHAT_TEXTS in cats) it.texts.files else 0) +
                    (if (Cat.CHAT_FILES in cats) it.payload.files else 0)
        }
        progress.totalBytes = chats.sumOf {
            (if (Cat.CHAT_TEXTS in cats) it.texts.bytes else 0L) +
                    (if (Cat.CHAT_FILES in cats) it.payload.bytes else 0L)
        }

        phase("Writing archive")
        SettingsExport.export(app, cats, out, archives, meta, chats, step)

        if (Cat.CHAT_TEXTS in cats) report.add("Chats: ${chats.sumOf { it.conversations.size }} " +
                "conversations, ${ChatArchive.human(chats.sumOf { it.texts.bytes })}")
        if (Cat.CHAT_FILES in cats) report.add("Chat files: ${chats.sumOf { it.payload.files }} " +
                "files, ${ChatArchive.human(chats.sumOf { it.payload.bytes })}")
        phase("Done")
        return report
    }

    /** Opens the configured destination (direct path first, SAF second). Null = none configured. */
    fun openExportTarget(): Pair<String, OutputStream>? {
        val name = SettingsExport.exportFileName()
        SettingsExport.directDir(app)?.let { dir ->
            return File(dir, name).let { it.absolutePath to it.outputStream() }
        }
        val dir = SettingsExport.getExportDir(app) ?: return null
        val doc = dir.createFile("application/zip", name) ?: return null
        val out = app.contentResolver.openOutputStream(doc.uri) ?: return null
        return name to out
    }

    // --- import ---------------------------------------------------------------------------------

    /** How an archived account maps onto this device. */
    private class Target(
        val srcId: String,
        val label: String,
        var id: String? = null,
        var fresh: Boolean = false,
    )

    fun runImport(src: SettingsExport.ZipSource, want: List<Cat>): Report {
        val report = Report()
        phase("Reading archive")
        val present = SettingsExport.categoriesIn(src)
        if (present.isEmpty()) {
            report.failure = "No 白い熊 GNU Jami export found in that file."
            return report
        }
        val cats = want.filter { it in present }

        SettingsExport.importData(app, src, cats)?.let { report.add(it) }

        val meta = SettingsExport.accountsMetaIn(src)
        val chatIdx =
            if (cats.any { it.isChat }) SettingsExport.chatIndexIn(src).orEmpty() else emptyMap()
        val archives =
            if (Cat.ACCOUNTS in cats) SettingsExport.accountArchivesIn(src) else emptyMap()
        if (archives.isEmpty() && chatIdx.isEmpty()) return report

        val wantTexts = Cat.CHAT_TEXTS in cats
        val wantFiles = Cat.CHAT_FILES in cats
        val need = chatIdx.values.sumOf {
            (if (wantTexts) it.texts.bytes else 0L) + (if (wantFiles) it.payload.bytes else 0L)
        }
        if (need > 0) {
            val free = ChatArchive.freeBytes(app)
            if (free < need + need / 20) {
                report.failure = "Not enough free space: ${ChatArchive.human(need)} needed, " +
                        "${ChatArchive.human(free)} available."
                return report
            }
        }

        progress.totalFiles = chatIdx.values.sumOf {
            (if (wantTexts) it.texts.files else 0) + (if (wantFiles) it.payload.files else 0)
        }
        progress.totalBytes = need

        val srcIds = LinkedHashSet<String>().apply { addAll(archives.keys); addAll(chatIdx.keys) }
        var created = 0
        var alreadyThere = 0
        val staging = ChatArchive.stagingDir(app)
        staging.deleteRecursively()

        for (srcId in srcIds) {
            val m = meta.optJSONObject(srcId)
            val uri = m?.optString("uri").orEmpty()
            val label = m?.optString("registeredName").orEmpty()
                .ifBlank { m?.optString("alias").orEmpty() }.ifBlank { srcId.take(8) }
            val t = Target(srcId, label)

            val existing = accounts.getAccounts()
                .firstOrNull { uri.isNotEmpty() && it.username == uri }
            if (existing != null) {
                t.id = existing.accountId
                alreadyThere++
            } else if (Cat.ACCOUNTS in cats && archives[srcId] != null) {
                t.fresh = ChatArchive.daemonDir(app, srcId).let { !it.exists() } &&
                        accounts.getAccounts().none { it.accountId == srcId }
            } else {
                report.add("Chats for $label: skipped — that account is not on this device " +
                        "(select Accounts as well to restore it).")
                continue
            }

            // Chat data is staged first: for a fresh account it must be on disk *before* the
            // daemon creates the account, which is what makes the history load with no download.
            val chats = chatIdx[srcId]
            val stage = File(staging, srcId)
            if (chats != null) {
                phase("Unpacking chats — $label")
                if (wantTexts)
                    ChatArchive.extract(src, "${ChatArchive.TEXTS}/$srcId/", File(stage, "daemon"), step)
                if (wantFiles)
                    ChatArchive.extract(src, "${ChatArchive.FILES}/$srcId/", File(stage, "files"), step)
            }

            if (t.id == null) {
                if (t.fresh && chats != null) {
                    val r = ChatArchive.InstallResult()
                    ChatArchive.installFresh(app, srcId, stage, r)
                    reportChats(report, label, r, wantFiles)
                }
                phase("Creating account — $label")
                val newId = createAccount(archives.getValue(srcId), m, if (t.fresh) srcId else null)
                if (newId == null) {
                    report.add("Accounts: $label failed to import")
                    continue
                }
                created++
                t.id = newId
                if (chats != null && (!t.fresh || newId != srcId)) {
                    // Collided on the id, so the daemon picked its own: merge instead.
                    mergeInto(newId, stage, chats, label, report, wantFiles)
                } else if (chats != null) {
                    ChatArchive.relink(app, newId, chats.links, ChatArchive.InstallResult())
                }
            } else if (chats != null) {
                mergeInto(t.id!!, stage, chats, label, report, wantFiles)
            }
        }

        staging.deleteRecursively()
        if (Cat.ACCOUNTS in cats) {
            val line = StringBuilder("Accounts: $created imported")
            if (alreadyThere > 0) line.append(", $alreadyThere already present")
            report.add(line.toString())
        }
        phase("Done")
        return report
    }

    /** Quiesce → move in only what is missing → re-scan. Safe against a live, syncing account. */
    private fun mergeInto(
        targetId: String, stage: File, chats: ChatArchive.AccountChats,
        label: String, report: Report, wantFiles: Boolean,
    ) {
        phase("Merging chats — $label")
        val r = ChatArchive.InstallResult()
        runCatching { accounts.pauseAccountForImport(targetId) }
            .onFailure { Log.w(TAG, "could not pause $targetId: ${it.message}") }
        try {
            ChatArchive.installMerge(app, targetId, stage, r)
            ChatArchive.relink(app, targetId, chats.links, r)
        } finally {
            runCatching { accounts.resumeAccountAfterImport(targetId) }
            runCatching { accounts.reloadConversationsAndRequests(targetId) }
                .onFailure { Log.w(TAG, "reload failed for $targetId: ${it.message}") }
        }
        reportChats(report, label, r, wantFiles)
    }

    private fun reportChats(
        report: Report, label: String, r: ChatArchive.InstallResult, wantFiles: Boolean
    ) {
        val chat = StringBuilder("Chats $label: ${r.restored} restored")
        if (r.present > 0) chat.append(", ${r.present} already complete")
        if (r.deleted > 0) chat.append(", ${r.deleted} skipped as deleted " +
                "(use 削除済み会話の復元 to bring them back)")
        report.add(chat.toString())
        if (wantFiles || r.filesRestored > 0) {
            val files = StringBuilder("Files $label: ${r.filesRestored} restored")
            if (r.filesPresent > 0) files.append(", ${r.filesPresent} already present")
            if (r.relinked > 0) files.append(", ${r.relinked} relinked")
            report.add(files.toString())
        }
        for (n in r.notes) report.add(n)
    }

    /**
     * Creates one account from its archive. [withId] reuses the archive's own account id, which is
     * only ever passed when that id is free — the directory is already populated at this point and
     * the daemon must adopt it rather than mint a new one.
     */
    private fun createAccount(archive: ByteArray, m: JSONObject?, withId: String?): String? = try {
        val cacheDir = File(app.cacheDir, "eximport").apply { mkdirs() }
        // The daemon may read the archive asynchronously after addAccount — leave the temp file
        // for the cache auto-cleanup rather than deleting it immediately.
        val f = File(cacheDir, "import_${withId ?: System.nanoTime()}.gz")
        f.writeBytes(archive)
        val details = accounts.getAccountTemplate(AccountConfig.ACCOUNT_TYPE_JAMI).blockingGet()
        // Same shape as the wizard's backup-restore path (initJamiAccountBackup), incl. the fork's
        // connectivity defaults; the archive then carries the account config.
        details[ConfigKey.ACCOUNT_ALIAS.key] =
            m?.optString("alias").orEmpty().ifBlank { "Jami account" }
        details[ConfigKey.VIDEO_ENABLED.key] = true.toString()
        details[ConfigKey.ACCOUNT_DTMF_TYPE.key] = "sipinfo"
        details[ConfigKey.ACCOUNT_UPNP_ENABLE.key] = AccountConfig.TRUE_STR
        details[ConfigKey.TURN_ENABLE.key] = AccountConfig.TRUE_STR
        details[ConfigKey.ACCOUNT_PEER_DISCOVERY.key] = AccountConfig.FALSE_STR
        details[ConfigKey.PROXY_ENABLED.key] = AccountConfig.FALSE_STR
        details[ConfigKey.ARCHIVE_PATH.key] = f.absolutePath
        if (withId != null) accounts.addAccountWithId(details, withId).ifEmpty { null }
        else accounts.addAccount(details)
            .timeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .blockingFirst().accountId
    } catch (e: Exception) {
        Log.e(TAG, "account import failed", e)
        null
    }

    // --- deleted-conversation restore ------------------------------------------------------------

    /** One conversation the archive still holds that this device has deliberately deleted. */
    class Deleted(
        val srcAccountId: String,
        val targetAccountId: String,
        val accountLabel: String,
        val convId: String,
    )

    /**
     * The conversations a normal import would skip: present in the archive, and flagged removed in
     * the live `convInfo`. Restoring one is opt-in precisely because the deletion was deliberate.
     */
    fun deletedCandidates(src: SettingsExport.ZipSource): List<Deleted> {
        val out = ArrayList<Deleted>()
        val meta = SettingsExport.accountsMetaIn(src)
        val chatIdx = SettingsExport.chatIndexIn(src) ?: return out
        for ((srcId, chats) in chatIdx) {
            val uri = meta.optJSONObject(srcId)?.optString("uri").orEmpty().ifEmpty { chats.uri }
            val live = accounts.getAccounts().firstOrNull { uri.isNotEmpty() && it.username == uri }
                ?: continue
            val label = live.registeredName.ifBlank { live.alias.orEmpty() }
                .ifBlank { live.accountId.take(8) }
            val removed = ChatArchive.removedConversations(ChatArchive.daemonDir(app, live.accountId))
            for (conv in chats.conversations) {
                if (conv in removed) out.add(Deleted(srcId, live.accountId, label, conv))
            }
        }
        return out
    }

    /** Restores the picked conversations and clears their removal flag so they survive the scan. */
    fun restoreDeleted(src: SettingsExport.ZipSource, picks: List<Deleted>): Report {
        val report = Report()
        if (picks.isEmpty()) {
            report.failure = "Nothing selected."
            return report
        }
        val staging = ChatArchive.stagingDir(app)
        staging.deleteRecursively()
        for ((targetId, group) in picks.groupBy { it.targetAccountId }) {
            val label = group.first().accountLabel
            phase("Restoring deleted conversations — $label")
            val stage = File(staging, targetId)
            for (d in group) {
                ChatArchive.extract(src, "${ChatArchive.TEXTS}/${d.srcAccountId}/conversations/${d.convId}/",
                    File(File(File(stage, "daemon"), "conversations"), d.convId), step)
                ChatArchive.extract(src, "${ChatArchive.TEXTS}/${d.srcAccountId}/conversation_data/${d.convId}/",
                    File(File(File(stage, "daemon"), "conversation_data"), d.convId), step)
                ChatArchive.extract(src, "${ChatArchive.FILES}/${d.srcAccountId}/${d.convId}/",
                    File(File(stage, "files"), d.convId), step)
            }
            val r = ChatArchive.InstallResult()
            runCatching { accounts.pauseAccountForImport(targetId) }
            try {
                // Drop the removal entries first: the daemon rebuilds them from the repositories
                // we are about to put back, so it must not see them flagged when it re-scans.
                ChatArchive.forgetRemovals(
                    ChatArchive.daemonDir(app, targetId), group.map { it.convId }.toSet())
                ChatArchive.installMerge(app, targetId, stage, r)
                SettingsExport.chatIndexIn(src)?.get(group.first().srcAccountId)?.let {
                    ChatArchive.relink(app, targetId, it.links, r)
                }
            } finally {
                runCatching { accounts.resumeAccountAfterImport(targetId) }
                runCatching { accounts.reloadConversationsAndRequests(targetId) }
            }
            report.add("$label: ${r.restored} conversation(s) restored, ${r.filesRestored} file(s)")
            for (n in r.notes) report.add(n)
        }
        staging.deleteRecursively()
        phase("Done")
        return report
    }

    companion object {
        private const val TAG = "SK-EXIM"
    }
}
