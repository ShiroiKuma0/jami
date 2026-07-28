package cx.ring.utils

import android.content.Context
import android.util.Log
import cx.ring.R
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

    class Progress(private val app: Context? = null) {
        @Volatile var phase: String = ""

        /** The Cat.id currently being written — "chat_texts", "chat_files", … Machine-readable
         *  companion to [phase], so 自由作業盤 can highlight the right row instead of reading the
         *  file counter as a row number (保存復元 contract §3). */
        @Volatile var itemId: String = ""
        @Volatile var files: Int = 0
        @Volatile var totalFiles: Int = 0
        @Volatile var bytes: Long = 0L
        @Volatile var totalBytes: Long = 0L

        fun line(): String {
            if (totalFiles <= 0) return phase
            return app?.getString(R.string.sk_exim_progress, phase, files, totalFiles,
                ChatArchive.human(bytes), ChatArchive.human(totalBytes)) ?: phase
        }
    }

    class Report {
        val lines = ArrayList<String>()
        var failure: String? = null
        var cancelled = false
        fun add(s: String) { if (s.isNotBlank()) lines.add(s.trim()) }
        val text: String get() = lines.joinToString("\n")
    }

    val progress = Progress(app)
    private var lastTick = 0L

    /** Set by [cancel]; the write loops check it and unwind with a CancelledException. */
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    // --- diagnostics, TEST TWIN ONLY --------------------------------------------------------------
    //
    // This device drops the app's logcat output entirely — 73k lines captured across an import
    // window contained not one line of ours — so a failure that only reaches a dialog is a failure
    // nobody can diagnose. The twin writes a plain log next to the archive, on shared storage, where
    // adb can read it without root. The real app writes NOTHING: 白い熊 does not want log files
    // appearing beside backups in normal use (白い熊, 2026-07-28).

    private val debugLogging get() = app.packageName.endsWith(".test")

    private var logFile: File? = null

    /** Arms the diagnostic log for this run. A no-op outside the test twin. */
    fun enableDebugLog(archive: File, kind: String) {
        if (!debugLogging) return
        logFile = File(archive.parentFile, "${archive.name}.$kind.log").also { f ->
            runCatching { f.writeText("") }
        }
        logLine("=== $kind ${archive.name} (${app.packageName})")
    }

    private fun logLine(s: String) {
        val f = logFile ?: return
        runCatching {
            val t = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
                .format(java.util.Date())
            f.appendText("$t  $s\n")
        }
    }

    private fun tick(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastTick < 400) return
        lastTick = now
        watcher?.onProgress(progress)
    }

    private fun phase(name: String, item: String = "") {
        progress.phase = name
        progress.itemId = item
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

    fun exportInto(cats: List<Cat>, out: OutputStream, verifyTarget: File? = null): Report {
        val report = Report()
        var archives = emptyMap<String, ByteArray>()
        var meta: JSONObject? = null
        if (Cat.ACCOUNTS in cats) {
            phase(app.getString(R.string.sk_exim_phase_accounts), Cat.ACCOUNTS.id)
            val (a, m, notes) = SettingsExport.collectAccountArchives(app, accounts)
            archives = a; meta = m
            report.add(app.getString(R.string.sk_rep_acc_exported, a.size))
            report.add(notes)
        }

        phase(app.getString(R.string.sk_exim_phase_index))
        val chats = indexChats(cats)
        progress.totalFiles = chats.sumOf {
            (if (Cat.CHAT_TEXTS in cats) it.texts.files else 0) +
                    (if (Cat.CHAT_FILES in cats) it.payload.files else 0)
        }
        progress.totalBytes = chats.sumOf {
            (if (Cat.CHAT_TEXTS in cats) it.texts.bytes else 0L) +
                    (if (Cat.CHAT_FILES in cats) it.payload.bytes else 0L)
        }

        phase(app.getString(R.string.sk_exim_phase_write))
        SettingsExport.export(app, cats, out, archives, meta, chats, step, { cat ->
            progress.itemId = cat.id
            tick(true)
        }) { cancelled }

        if (Cat.CHAT_TEXTS in cats) report.add(app.getString(R.string.sk_rep_chats_exported,
            chats.sumOf { it.conversations.size },
            ChatArchive.human(chats.sumOf { it.texts.bytes })))
        if (Cat.CHAT_FILES in cats) report.add(app.getString(R.string.sk_rep_files_exported,
            chats.sumOf { it.payload.files },
            ChatArchive.human(chats.sumOf { it.payload.bytes })))

        // SettingsExport.export() closed the zip (and with it `out`), so the file on disk is
        // complete and can be read straight back.
        if (verifyTarget != null) {
            phase(app.getString(R.string.sk_exim_phase_verify))
            progress.files = 0; progress.bytes = 0
            var n = 0
            val bad = SettingsExport.verify(verifyTarget, { b ->
                n++; progress.files = n; progress.bytes += b; tick()
            }) { cancelled }
            if (bad.isEmpty()) report.add(app.getString(R.string.sk_rep_verified, n))
            else {
                report.failure = app.getString(R.string.sk_rep_corrupt, bad.size)
                for (name in bad.take(5)) report.add(name)
            }
        }
        phase(app.getString(R.string.sk_exim_done))
        return report
    }

    /**
     * Where an export is being written.
     *
     * The bytes go to `<name>.zip.part` and the file takes its real name only once the archive is
     * closed AND verified. 白い熊 keeps every app's backups in one directory sorted by date, so a
     * truncated file silently becomes "the latest Jami backup" and is indistinguishable from a real
     * one until the day it is needed — three such files existed before this (保存復元 hand-off,
     * 2026-07-28). [file] is null when only SAF is available, where neither verification nor an
     * atomic rename is possible.
     */
    class ExportTarget(
        val label: String,
        val stream: OutputStream,
        val file: File?,
        private val finalFile: File?,
    ) {
        fun commit(): Boolean {
            val part = file ?: return true
            val dest = finalFile ?: return true
            dest.delete()
            return part.renameTo(dest)
        }

        fun abort() {
            file?.delete()
        }
    }

    /** Opens the destination. [overrideDir] is the automation contract's `path` extra. */
    fun openExportTarget(overrideDir: File? = null): ExportTarget? {
        val name = SettingsExport.exportFileName()
        val dir = overrideDir ?: SettingsExport.directDir(app)
        if (dir != null) {
            runCatching { dir.mkdirs() }
            val part = File(dir, "$name.part")
            val dest = File(dir, name)
            return runCatching {
                ExportTarget(dest.absolutePath, part.outputStream(), part, dest)
            }.getOrNull() ?: return null
        }
        val safDir = SettingsExport.getExportDir(app) ?: return null
        val doc = safDir.createFile("application/zip", name) ?: return null
        val out = app.contentResolver.openOutputStream(doc.uri) ?: return null
        return ExportTarget(name, out, null, null)
    }

    /**
     * The whole export, atomically: write, verify, then rename into place — or delete the partial
     * file. Both the panel and the automation service go through here so neither can get it wrong.
     */
    fun exportTo(cats: List<Cat>, target: ExportTarget): Report {
        var report: Report? = null
        try {
            target.stream.use { out -> report = exportInto(cats, out, target.file) }
            return report!!
        } finally {
            // Anything other than a clean run — failure, cancellation, an exception on the way out
            // — deletes the partial file rather than leaving it to look like a backup.
            val r = report
            if (r != null && r.failure == null && !cancelled) target.commit() else target.abort()
        }
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
        phase(app.getString(R.string.sk_exim_phase_read))
        val present = SettingsExport.categoriesIn(src)
        if (present.isEmpty()) {
            report.failure = app.getString(R.string.sk_rep_not_ours)
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
                report.failure = app.getString(R.string.sk_rep_no_space,
                    ChatArchive.human(need), ChatArchive.human(free))
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
            // One account's failure must not end the restore for the rest — before this, an
            // exception here ended the loop silently and only the first account survived.
            try {
                importOne(src, srcId, meta, archives, chatIdx, cats, wantTexts, wantFiles,
                    staging, report).let { outcome ->
                    when (outcome) {
                        Outcome.CREATED -> created++
                        Outcome.PRESENT -> alreadyThere++
                        Outcome.SKIPPED -> Unit
                    }
                }
            } catch (e: ChatArchive.CancelledException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "import of $srcId failed", e)
                logLine("account $srcId FAILED: ${e.javaClass.simpleName}: ${e.message}")
                report.add(app.getString(R.string.sk_rep_acc_failed, srcId.take(8)))
            }
        }

        staging.deleteRecursively()
        if (Cat.ACCOUNTS in cats) {
            report.add(if (alreadyThere > 0)
                app.getString(R.string.sk_rep_acc_present, created, alreadyThere)
            else app.getString(R.string.sk_rep_acc_imported, created))
        }
        logLine("=== finished: $created created, $alreadyThere already present")
        logLine(report.text)
        report.failure?.let { logLine("FAILURE: $it") }
        phase(app.getString(R.string.sk_exim_done))
        return report
    }

    private enum class Outcome { CREATED, PRESENT, SKIPPED }

    /** One archived account: resolve where it goes, stage its chats, then create it or merge. */
    private fun importOne(
        src: SettingsExport.ZipSource,
        srcId: String,
        meta: JSONObject,
        archives: Map<String, ByteArray>,
        chatIdx: Map<String, ChatArchive.AccountChats>,
        cats: List<Cat>,
        wantTexts: Boolean,
        wantFiles: Boolean,
        staging: File,
        report: Report,
    ): Outcome {
        val m = meta.optJSONObject(srcId)
        val uri = m?.optString("uri").orEmpty()
        val label = m?.optString("registeredName").orEmpty()
            .ifBlank { m?.optString("alias").orEmpty() }.ifBlank { srcId.take(8) }
        logLine("account $srcId ($label)")

        val existing = accounts.getAccounts().firstOrNull { uri.isNotEmpty() && it.username == uri }
        var targetId = existing?.accountId
        var fresh = false
        when {
            existing != null -> logLine("  already here as ${existing.accountId}")
            Cat.ACCOUNTS in cats && archives[srcId] != null -> {
                // Reusing the archive's own account id is what lets the daemon adopt the restored
                // directory as it starts, and it is the path the whole design rests on.
                //
                // A directory at that id with NO account owning it is orphaned data — exactly what
                // the daemon's own cleanupAccountStorage() deletes — usually the husk of an earlier
                // attempt. Stepping aside for it dropped every retry onto the slower merge path
                // under a random id, and left the stale directory behind to do it again next time
                // (白い熊, 2026-07-28). So reclaim it.
                val dir = ChatArchive.daemonDir(app, srcId)
                val idTaken = accounts.getAccounts().any { it.accountId == srcId }
                if (!idTaken && dir.exists()) {
                    val left = dir.listFiles()?.joinToString(", ") { it.name } ?: ""
                    logLine("  reclaiming orphaned directory for $srcId [$left]")
                    dir.deleteRecursively()
                }
                fresh = !idTaken && !dir.exists()
                if (!fresh) logLine("  cannot reuse id: idTaken=$idTaken dirExists=${dir.exists()}")
                logLine("  new account, fresh=$fresh")
            }
            else -> {
                logLine("  SKIPPED — not on this device and no archive for it")
                report.add(app.getString(R.string.sk_rep_orphan, label))
                return Outcome.SKIPPED
            }
        }

        val chats = chatIdx[srcId]
        val stage = File(staging, srcId)
        if (chats != null) {
            phase(app.getString(R.string.sk_exim_phase_unpack, label))
            var damaged = 0
            if (wantTexts) damaged += ChatArchive.extract(
                src, "${ChatArchive.TEXTS}/$srcId/", File(stage, "daemon"), step) { cancelled }
                .failed
            if (wantFiles) damaged += ChatArchive.extract(
                src, "${ChatArchive.FILES}/$srcId/", File(stage, "files"), step) { cancelled }
                .failed
            logLine("  unpacked (damaged entries: $damaged)")
            if (damaged > 0) report.add(app.getString(R.string.sk_rep_damaged, damaged))
        }

        if (targetId == null) {
            val r = ChatArchive.InstallResult()
            if (fresh && chats != null) {
                ChatArchive.installFresh(app, srcId, stage, r)
                logLine("  installed ${r.restored} conversations, ${r.filesRestored} files")
            }
            phase(app.getString(R.string.sk_exim_phase_create, label))
            val newId = createAccount(archives.getValue(srcId), m, if (fresh) srcId else null)
            if (newId == null) {
                logLine("  CREATE FAILED")
                report.add(app.getString(R.string.sk_rep_acc_failed, label))
                return Outcome.SKIPPED
            }
            logLine("  created as $newId")
            if (!awaitAccountReady(newId, uri)) {
                // The daemon deletes an account that is still pending at the next start, wiping its
                // directory with it. Carrying on would produce a restore that looks complete and
                // evaporates on restart — so fail this account honestly instead.
                logLine("  ABORTING this account: it would be deleted on the next restart")
                runCatching { accounts.removeAccount(newId) }
                report.add(app.getString(R.string.sk_rep_acc_not_ready, label))
                return Outcome.SKIPPED
            }
            targetId = newId
            if (chats != null && (!fresh || newId != srcId)) {
                mergeInto(newId, stage, chats, label, report, wantFiles)
            } else if (chats != null) {
                ChatArchive.relink(app, newId, chats.links, r)
                reportChats(report, label, r, wantFiles)
                logLine("  relinked ${r.relinked}/${r.linksWanted}")
            }
            return Outcome.CREATED
        }

        if (chats != null) mergeInto(targetId, stage, chats, label, report, wantFiles)
        return Outcome.PRESENT
    }

    /**
     * Waits until a newly created account has actually LOADED ITS IDENTITY — not merely appeared.
     *
     * This is the difference between a restore that survives and one that evaporates.
     * `Manager::addAccount` marks every new Jami account **pending**, and only `markAccountReady`
     * clears it once `loadAccount` has read the archive. At the next startup the daemon does
     * `if (isAccountPending(id)) { removeAccount(id, /*flush*/true); cleanupAccountStorage(id); }`
     * — and `flush = true` wipes the whole account directory, taking every conversation we just
     * restored with it. Restarting a few seconds after the import destroyed three accounts exactly
     * that way (白い熊, 2026-07-28).
     *
     * The identity landing is observable: `loadAccount` sets `config_->username` from it just
     * before scheduling the ready callback, so a matching username means the archive was read. The
     * short settle afterwards lets that posted callback actually run.
     */
    private fun awaitAccountReady(id: String, expectedUri: String, timeoutMs: Long = 60_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) return false
            val a = accounts.getAccount(id)
            val user = a?.username.orEmpty()
            if (user.isNotEmpty() && (expectedUri.isEmpty() || user == expectedUri)) {
                logLine("  identity loaded (${user.take(12)}…), settling")
                Thread.sleep(2_000)          // let markAccountReady run on the daemon's main thread
                logLine("  ready, state=${runCatching { a?.registrationState }.getOrNull()}")
                return true
            }
            Thread.sleep(250)
        }
        logLine("  identity did not load within $timeoutMs ms — account is still PENDING")
        return false
    }

    /**
     * Quiesce → move in only what is missing → re-scan. Safe against a live, syncing account.
     *
     * Every step announces itself. This phase moves files and talks to the daemon but never touches
     * the extraction counter, so without these the panel sat frozen on the unpack total for nearly
     * two minutes and looked hung (白い熊, 2026-07-28).
     */
    private fun mergeInto(
        targetId: String, stage: File, chats: ChatArchive.AccountChats,
        label: String, report: Report, wantFiles: Boolean,
    ) {
        phase(app.getString(R.string.sk_exim_phase_merge, label))
        val r = ChatArchive.InstallResult()
        logLine("  merging into $targetId")
        phase(app.getString(R.string.sk_exim_phase_pause, label))
        runCatching { accounts.pauseAccountForImport(targetId) }
            .onFailure {
                Log.w(TAG, "could not pause $targetId: ${it.message}")
                logLine("  pause timed out or failed: ${it.javaClass.simpleName}")
            }
        try {
            phase(app.getString(R.string.sk_exim_phase_merge, label))
            ChatArchive.installMerge(app, targetId, stage, r)
            logLine("  merged ${r.restored} conversations, ${r.filesRestored} files " +
                    "(${r.filesPresent} already present)")
            phase(app.getString(R.string.sk_exim_phase_link, label))
            ChatArchive.relink(app, targetId, chats.links, r)
            logLine("  relinked ${r.relinked}/${r.linksWanted}")
        } finally {
            phase(app.getString(R.string.sk_exim_phase_resume, label))
            runCatching { accounts.resumeAccountAfterImport(targetId) }
                .onFailure { logLine("  resume timed out or failed: ${it.javaClass.simpleName}") }
            runCatching { accounts.reloadConversationsAndRequests(targetId) }
                .onFailure {
                    Log.w(TAG, "reload failed for $targetId: ${it.message}")
                    logLine("  reload timed out or failed: ${it.javaClass.simpleName}")
                }
            logLine("  account resumed")
        }
        reportChats(report, label, r, wantFiles)
    }

    private fun reportChats(
        report: Report, label: String, r: ChatArchive.InstallResult, wantFiles: Boolean
    ) {
        val chat = StringBuilder(app.getString(R.string.sk_rep_chats, label, r.restored))
        if (r.present > 0) chat.append(app.getString(R.string.sk_rep_chats_complete, r.present))
        if (r.deleted > 0) chat.append(app.getString(R.string.sk_rep_chats_deleted, r.deleted))
        report.add(chat.toString())
        if (wantFiles || r.filesRestored > 0) {
            val files = StringBuilder(app.getString(R.string.sk_rep_files, label, r.filesRestored))
            if (r.filesPresent > 0)
                files.append(app.getString(R.string.sk_rep_files_present, r.filesPresent))
            report.add(files.toString())
        }
        // Always state the link result when the archive carried any: pictures show as
        // "not downloaded" without them, and a silent 0 is what hid that for a whole round.
        if (r.linksWanted > 0)
            report.add(app.getString(R.string.sk_rep_links, r.relinked, r.linksWanted))
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
        // registeredName first: the alias is often empty in an archive, and falling straight to
        // the literal left every restored account showing "Jami account" (白い熊, 2026-07-28).
        details[ConfigKey.ACCOUNT_ALIAS.key] = m?.optString("registeredName").orEmpty()
            .ifBlank { m?.optString("alias").orEmpty() }
            .ifBlank { "Jami account" }
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
        logLine("  createAccount threw ${e.javaClass.simpleName}: ${e.message}")
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
            report.failure = app.getString(R.string.sk_exim_nothing_selected)
            return report
        }
        val staging = ChatArchive.stagingDir(app)
        staging.deleteRecursively()
        for ((targetId, group) in picks.groupBy { it.targetAccountId }) {
            val label = group.first().accountLabel
            phase(app.getString(R.string.sk_exim_phase_restore, label))
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
            report.add(app.getString(R.string.sk_rep_deleted_done, label, r.restored, r.filesRestored))
            for (n in r.notes) report.add(n)
        }
        staging.deleteRecursively()
        phase(app.getString(R.string.sk_exim_done))
        return report
    }

    companion object {
        private const val TAG = "SK-EXIM"
    }
}
