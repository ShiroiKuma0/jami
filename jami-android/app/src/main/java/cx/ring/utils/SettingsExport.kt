package cx.ring.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export/Import engine for every settable item in the app, mirroring the Kōjiki/ArcaneChat model —
 * a zip of one JSON file per category plus a manifest, written to a user-chosen directory.
 * Every SharedPreferences key round-trips with a type tag ({"t":..,"v":..}), and import is a
 * per-key MERGE (never a clear), so unknown/missing keys are simply left alone — old exports load
 * into new app versions and vice versa. Imported font files travel as raw fonts/<name> entries.
 *
 * Format 2 adds the chat corpus (see [ChatArchive]), which can run to gigabytes. Nothing is
 * buffered in memory any more: export streams straight into the destination, and import reads
 * through a [ZipSource] that seeks when it can (a real file) and scans when it cannot (SAF).
 */
object SettingsExport {

    const val FORMAT = "shiroikuma-jami-export"

    /** 1 = settings and account archives. 2 = the chat categories. Older archives still import. */
    const val VERSION = 2

    /** Filename stem: english-dash-separated app name, no version (白い熊, 2026-07-25) —
     *  `shiroikuma-jami_2026-07-25_18-58-23.zip`. Kept WITHOUT the trailing separator so the
     *  latest-export scan still matches the older `shiroikuma-jami-export_*` files too. */
    const val EXPORT_PREFIX = "shiroikuma-jami"

    /**
     * Where a direct-path archive lands when all-files access is granted.
     *
     * The test twin gets its own folder. Both installs can read shared storage, so a single folder
     * would let the twin import the real install's archive by mistake — which would pull the real
     * accounts onto a second device, the one side effect the twin exists to avoid.
     */
    fun defaultDirPath(c: Context): String {
        // Written out rather than `"…" + if (…)`: that shape crashes lint's UAST converter
        // ("Bad parent: KtNameReferenceExpression"), which fails lintVital on a release build.
        val base = "/storage/emulated/0/shiroikuma-jami"
        return if (c.packageName.endsWith(".test")) "$base-test" else base
    }

    /** Device-local prefs holding the export location; deliberately never exported. */
    private const val EXIM_PREFS = "shiroikuma_eximport"
    private const val KEY_DIR_URI = "dir_uri"
    private const val KEY_DIR_PATH = "dir_path"

    // Pref-store names (the objects keep theirs private; the export needs them by name).
    private const val P_FONTS = "shiroikuma_fonts"
    private const val P_COLORS = "shiroikuma_colors"
    private const val P_UI = "shiroikuma_ui"
    private const val P_AUTOMATION = "shiroikuma_automation"
    private const val P_PROTECTED = "shiroikuma_protected"
    private const val P_APP = "ring_settings"
    private const val P_VIDEO = "videoPrefs"

    /** shiroikuma_ui carries settings AND runtime state — only these keys are real settings. */
    private val UI_KEYS = setOf("split_view", "status_dot_scale", "monitor_fold_scale", "app_language")
    private val RECOVERY_KEYS = setOf(
        "recovery_base", "recovery_ping", "recovery_test_swarm", "recovery_test_account",
        "recovery_tick_min", "recovery_prune_days", "full_dht_mode", "push_backend")

    /**
     * The selectable categories; [id] doubles as the JSON entry name inside the zip, and as the
     * automation contract's item id. Declaration order is the order they appear in the panel, so
     * the three migration categories come first.
     */
    enum class Cat(val id: String, val label: String, val defaultOn: Boolean = true) {
        ACCOUNTS("accounts", "Accounts (Jami archives)"),
        CHAT_TEXTS("chat_texts", "Chats — messages & history"),
        CHAT_FILES("chat_files", "Chats — received & sent files", defaultOn = false),
        FONTS("fonts", "Fonts & sizes"),
        COLORS("colors", "Colours"),
        UI("ui", "UI behaviour"),
        RECOVERY("recovery", "Online recovery & connectivity"),
        AUTOMATION("automation", "Automation & protected contacts"),
        APP("app_settings", "App settings"),
        ;

        val isChat get() = this == CHAT_TEXTS || this == CHAT_FILES
    }

    /** The categories a headless caller gets when it names none — never the chat corpus, which
     *  would silently turn a settings backup into a multi-gigabyte one. */
    fun defaultHeadlessCats(): List<Cat> = Cat.entries.filter { !it.isChat }

    /** prefs-file name → key filter (null = every key) for one category. */
    private fun stores(cat: Cat): Map<String, ((String) -> Boolean)?> = when (cat) {
        Cat.ACCOUNTS, Cat.CHAT_TEXTS, Cat.CHAT_FILES -> emptyMap()  // payload, not prefs
        Cat.FONTS -> mapOf(P_FONTS to null)
        Cat.COLORS -> mapOf(P_COLORS to null)
        Cat.UI -> mapOf(P_UI to { k: String -> k in UI_KEYS })
        Cat.RECOVERY -> mapOf(P_UI to { k: String -> k in RECOVERY_KEYS })
        // The automation token must NEVER travel in a backup ZIP (保存復元 contract §2) — it is a
        // live credential; a restored backup regenerates one lazily. Everything else exports.
        Cat.AUTOMATION -> mapOf(P_AUTOMATION to { k: String -> k != "token" }, P_PROTECTED to null)
        Cat.APP -> mapOf(P_APP to null, P_VIDEO to null)
    }

    // --- export -------------------------------------------------------------------------------

    fun exportFileName(): String = EXPORT_PREFIX + "_" +
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + ".zip"

    /**
     * Streams the export for the given categories into [out]. Account archives (collected by the
     * caller — they need the daemon) travel as raw accounts/<id>.gz entries plus an accounts.json
     * meta; the chat corpus is written by [ChatArchive] from the pre-computed [chats] index.
     */
    fun export(
        c: Context,
        cats: List<Cat>,
        out: OutputStream,
        accountArchives: Map<String, ByteArray> = emptyMap(),
        accountsMeta: JSONObject? = null,
        chats: List<ChatArchive.AccountChats> = emptyList(),
        progress: ChatArchive.Progress? = null,
    ) {
        ZipOutputStream(out).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("app", c.packageName)
                .put("createdTs", System.currentTimeMillis())
                .put("categories", JSONArray(cats.map { it.id }))
            writeEntry(zip, "manifest.json", manifest.toString(2).toByteArray())

            for (cat in cats) {
                when (cat) {
                    Cat.ACCOUNTS -> {
                        writeEntry(zip, "accounts.json",
                            (accountsMeta ?: JSONObject()).toString(2).toByteArray())
                        for ((id, data) in accountArchives) writeEntry(zip, "accounts/$id.gz", data)
                    }
                    Cat.CHAT_TEXTS, Cat.CHAT_FILES -> Unit   // written below, after the index
                    else -> {
                        val obj = JSONObject()
                        for ((prefsName, filter) in stores(cat)) {
                            obj.put(prefsName, exportPrefs(
                                c.getSharedPreferences(prefsName, Context.MODE_PRIVATE), filter))
                        }
                        writeEntry(zip, "${cat.id}.json", obj.toString(2).toByteArray())
                        if (cat == Cat.FONTS) {
                            for (f in FontPrefs.getFontFiles(c))
                                writeEntry(zip, "fonts/${f.name}", f.readBytes())
                        }
                    }
                }
            }

            if (chats.isNotEmpty() && cats.any { it.isChat }) {
                val accounts = JSONObject()
                for (a in chats) accounts.put(a.accountId, a.toJson())
                writeEntry(zip, ChatArchive.INDEX_ENTRY,
                    JSONObject().put("accounts", accounts).toString(2).toByteArray())
                for (a in chats) {
                    if (Cat.CHAT_TEXTS in cats) ChatArchive.writeTexts(zip, c, a, progress)
                    if (Cat.CHAT_FILES in cats) ChatArchive.writeFiles(zip, c, a, progress)
                }
            }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content)
        zip.closeEntry()
    }

    /** Every matching key with a type tag: {"t":"b|i|l|f|s|ss","v":...}. */
    private fun exportPrefs(sp: SharedPreferences, filter: ((String) -> Boolean)?): JSONObject {
        val obj = JSONObject()
        for ((k, v) in sp.all) {
            if (filter != null && !filter(k)) continue
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            obj.put(k, e)
        }
        return obj
    }

    // --- reading an archive ---------------------------------------------------------------------

    /**
     * Read access to an archive. A real file is opened with [ZipFile], so an unselected category
     * is seeked past rather than read through — which matters once attachments are in there. A SAF
     * document only offers a stream, so that implementation rescans from the start per pass.
     */
    interface ZipSource : Closeable {
        /** A single small entry, or null. */
        fun entry(name: String): ByteArray?

        /** Every entry whose name starts with [prefix]; the stream is valid only inside [cb]. */
        fun forEach(prefix: String, cb: (name: String, size: Long, input: InputStream) -> Unit)

        /** Entry names starting with [prefix] (no payload read). */
        fun names(prefix: String): List<String>
    }

    class FileZipSource(file: File) : ZipSource {
        private val zip = ZipFile(file)
        override fun entry(name: String): ByteArray? =
            zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

        override fun forEach(prefix: String, cb: (String, Long, InputStream) -> Unit) {
            val it = zip.entries()
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                if (e.isDirectory || !e.name.startsWith(prefix)) continue
                zip.getInputStream(e).use { s -> cb(e.name, e.size, s) }
            }
        }

        override fun names(prefix: String): List<String> {
            val out = ArrayList<String>()
            val it = zip.entries()
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                if (!e.isDirectory && e.name.startsWith(prefix)) out.add(e.name)
            }
            return out
        }

        override fun close() = zip.close()
    }

    class StreamZipSource(private val open: () -> InputStream) : ZipSource {
        override fun entry(name: String): ByteArray? {
            var found: ByteArray? = null
            scan { n, _, s -> if (n == name) found = s.readBytes() }
            return found
        }

        override fun forEach(prefix: String, cb: (String, Long, InputStream) -> Unit) =
            scan { n, size, s -> if (n.startsWith(prefix)) cb(n, size, s) }

        override fun names(prefix: String): List<String> {
            val out = ArrayList<String>()
            scan { n, _, _ -> if (n.startsWith(prefix)) out.add(n) }
            return out
        }

        private fun scan(cb: (String, Long, InputStream) -> Unit) {
            ZipInputStream(open()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.isDirectory) cb(e.name, e.size, zin)
                    zin.closeEntry()
                }
            }
        }

        override fun close() {}
    }

    /** A [ZipSource] for a picked document: a real file when we can reach one, a stream otherwise. */
    fun openSource(c: Context, uri: Uri): ZipSource {
        val path = if (uri.scheme == "file") uri.path else null
        if (path != null && File(path).isFile) return FileZipSource(File(path))
        return StreamZipSource { c.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("cannot read the selected file") }
    }

    fun openSource(file: File): ZipSource = FileZipSource(file)

    fun sourceOf(bytes: ByteArray): ZipSource = StreamZipSource { ByteArrayInputStream(bytes) }

    // --- import -------------------------------------------------------------------------------

    /** The categories present in an archive; empty if it isn't one of ours. */
    fun categoriesIn(src: ZipSource): List<Cat> = try {
        val manifest = src.entry("manifest.json")?.let { JSONObject(String(it)) }
        if (manifest == null || manifest.optString("format") != FORMAT) {
            emptyList()
        } else {
            val declared = manifest.optJSONArray("categories")
            val ids = HashSet<String>()
            if (declared != null) for (i in 0 until declared.length()) ids.add(declared.optString(i))
            // One index read, not one per chat category: a stream source rescans on every call.
            val hasChats = ids.any { it == Cat.CHAT_TEXTS.id || it == Cat.CHAT_FILES.id } &&
                    chatIndexIn(src) != null
            Cat.entries.filter {
                when (it) {
                    Cat.ACCOUNTS -> src.entry("accounts.json") != null
                    Cat.CHAT_TEXTS, Cat.CHAT_FILES -> hasChats && it.id in ids
                    else -> src.entry("${it.id}.json") != null
                }
            }
        }
    } catch (_: Exception) { emptyList() }

    /**
     * Applies the selected preference categories; categories missing from the archive are skipped.
     * Returns a human-readable per-category summary, or null when the file carried none.
     */
    fun importData(c: Context, src: ZipSource, cats: List<Cat>): String? {
        if (src.entry("manifest.json") == null) return null
        val summary = StringBuilder()
        var any = false
        for (cat in cats) {
            if (cat == Cat.ACCOUNTS || cat.isChat) continue   // payload — the caller restores it
            val data = src.entry("${cat.id}.json") ?: continue
            var n = importCat(c, cat, JSONObject(String(data)))
            if (cat == Cat.FONTS) n += importFontFiles(c, src)
            any = true
            if (summary.isNotEmpty()) summary.append('\n')
            summary.append(cat.label).append(": ").append(n)
        }
        return if (any) summary.toString() else null
    }

    private fun importCat(c: Context, cat: Cat, obj: JSONObject): Int {
        var count = 0
        for ((prefsName, filter) in stores(cat)) {
            val entries = obj.optJSONObject(prefsName) ?: continue
            count += importPrefs(c,
                c.getSharedPreferences(prefsName, Context.MODE_PRIVATE), entries, filter)
        }
        return count
    }

    /** Per-key merge — never clears, so unrelated/device-local keys survive. Returns keys applied. */
    private fun importPrefs(
        c: Context, sp: SharedPreferences, obj: JSONObject, filter: ((String) -> Boolean)?
    ): Int {
        val ed = sp.edit()
        var count = 0
        for (k in obj.keys()) {
            if (filter != null && !filter(k)) continue   // no smuggling another category's keys
            val e = obj.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.optBoolean("v"))
                "i" -> ed.putInt(k, e.optInt("v"))
                "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                "s" -> ed.putString(k, remapFontPath(c, e.optString("v")))
                "ss" -> {
                    val a = e.optJSONArray("v")
                    val set = HashSet<String>()
                    if (a != null) for (i in 0 until a.length()) set.add(a.optString(i))
                    ed.putStringSet(k, set)
                }
                else -> continue
            }
            count++
        }
        ed.apply()
        return count
    }

    /** An imported "file:<path>/fonts/<name>" family points at the SOURCE install's filesDir —
     *  re-point it at ours (same basename), so exports survive path differences. */
    private fun remapFontPath(c: Context, v: String): String {
        if (!v.startsWith("file:") || !v.contains("/fonts/")) return v
        val name = v.substringAfterLast('/')
        return "file:${File(FontPrefs.fontsDir(c), name).absolutePath}"
    }

    /** Restores the fonts/<name> entries into filesDir/fonts. Returns files written. */
    private fun importFontFiles(c: Context, src: ZipSource): Int {
        var n = 0
        src.forEach("fonts/") { name, _, input ->
            val base = name.removePrefix("fonts/").substringAfterLast('/')
            if (base.isNotEmpty()) runCatching {
                File(FontPrefs.fontsDir(c), base).outputStream().use { input.copyTo(it) }
                n++
            }
        }
        return n
    }

    /** The accounts/<id>.gz archives in an archive (accountId → archive bytes). */
    fun accountArchivesIn(src: ZipSource): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        src.forEach("accounts/") { name, _, input ->
            if (name.endsWith(".gz"))
                out[name.removePrefix("accounts/").removeSuffix(".gz")] = input.readBytes()
        }
        return out
    }

    /** The accounts.json metadata (accountId → {uri, alias, registeredName}), or empty. */
    fun accountsMetaIn(src: ZipSource): JSONObject =
        src.entry("accounts.json")?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
            ?: JSONObject()

    /** The chats.json index (accountId → [ChatArchive.AccountChats]), or null when absent. */
    fun chatIndexIn(src: ZipSource): Map<String, ChatArchive.AccountChats>? {
        val raw = src.entry(ChatArchive.INDEX_ENTRY) ?: return null
        return runCatching {
            val accounts = JSONObject(String(raw)).optJSONObject("accounts") ?: return@runCatching null
            val out = LinkedHashMap<String, ChatArchive.AccountChats>()
            for (id in accounts.keys()) {
                val o = accounts.optJSONObject(id) ?: continue
                out[id] = ChatArchive.AccountChats.fromJson(id, o)
            }
            out
        }.getOrNull()
    }

    // --- account-archive collection (shared by the Export/Import panel and the headless
    //     保存復元 StateExportReceiver — the ZIP engine must have exactly one implementation) ----

    /** Exports every password-less Jami account to an archive via the daemon (blocking — call on a
     *  background thread). Password-protected archives can't be exported silently → noted, skipped.
     *  Returns (accountId → archive bytes, accounts meta, human notes). */
    fun collectAccountArchives(
        app: Context, accountService: net.jami.services.AccountService
    ): Triple<Map<String, ByteArray>, JSONObject, String> {
        val out = LinkedHashMap<String, ByteArray>()
        val meta = JSONObject()
        val notes = StringBuilder()
        val cacheDir = File(app.cacheDir, "eximport").apply { mkdirs() }
        for (a in accountService.getAccounts().filter { it.isJami }) {
            val label = a.registeredName.ifBlank { a.alias.orEmpty() }.ifBlank { a.accountId.take(8) }
            if (a.hasPassword()) {
                notes.append("\n$label: archive has a password — not included.")
                continue
            }
            val f = File(cacheDir, "${a.accountId}.gz")
            try {
                accountService.exportToFile(a.accountId, f.absolutePath, "", "").blockingAwait()
                out[a.accountId] = f.readBytes()
                meta.put(a.accountId, JSONObject()
                    .put("uri", a.username ?: "")
                    .put("alias", a.alias ?: "")
                    .put("registeredName", a.registeredName))
            } catch (e: Exception) {
                notes.append("\n$label: export failed — ${e.message}")
            } finally {
                f.delete()
            }
        }
        return Triple(out, meta, notes.toString())
    }

    // --- export location: direct path when granted, SAF otherwise ------------------------------

    private fun p(c: Context) = c.getSharedPreferences(EXIM_PREFS, Context.MODE_PRIVATE)

    /** All-files access lets us use plain paths — random-access import, and no picker for
     *  automation. Everything still works without it, through SAF. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun getDirPath(c: Context): String = p(c).getString(KEY_DIR_PATH, null) ?: defaultDirPath(c)

    fun setDirPath(c: Context, path: String) {
        p(c).edit().putString(KEY_DIR_PATH, path).apply()
    }

    /** The direct archive directory, created on demand; null when all-files access is not held. */
    fun directDir(c: Context): File? {
        if (!hasAllFilesAccess()) return null
        val d = File(getDirPath(c))
        if (!d.isDirectory) d.mkdirs()
        return d.takeIf { it.isDirectory }
    }

    fun getDirUri(c: Context): Uri? =
        p(c).getString(KEY_DIR_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setDirUri(c: Context, uri: Uri) {
        p(c).edit().putString(KEY_DIR_URI, uri.toString()).apply()
    }

    fun getExportDir(c: Context): DocumentFile? {
        val uri = getDirUri(c) ?: return null
        return runCatching { DocumentFile.fromTreeUri(c, uri)?.takeIf { it.isDirectory } }.getOrNull()
    }

    private fun isExportName(n: String?) =
        n != null && n.startsWith(EXPORT_PREFIX) && n.endsWith(".zip")

    /** Archives in the direct directory, newest first. Empty without all-files access. */
    fun directExports(c: Context): List<File> =
        directDir(c)?.listFiles()?.filter { it.isFile && isExportName(it.name) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** The newest export zip in the configured SAF directory, or null. */
    fun latestExport(c: Context): DocumentFile? {
        val dir = getExportDir(c) ?: return null
        return runCatching {
            dir.listFiles().filter { it.isFile && isExportName(it.name) }
                .maxByOrNull { it.lastModified() }
        }.getOrNull()
    }

    /** Where an export would be written, for display. */
    fun locationLabel(c: Context): String? =
        directDir(c)?.absolutePath
            ?: getExportDir(c)?.name
            ?: getDirUri(c)?.lastPathSegment

    /** The "last export" status line — call off the main thread (SAF listing can be slow). */
    fun lastExportStatus(c: Context): String {
        directDir(c)?.let { dir ->
            val newest = directExports(c).firstOrNull() ?: return "No exports yet in ${dir.absolutePath}"
            return "Latest export: ${newest.name}  (${stamp(newest.lastModified())}, " +
                    "${ChatArchive.human(newest.length())})"
        }
        if (getExportDir(c) == null) return "Export directory not set"
        val newest = latestExport(c) ?: return "No exports yet"
        return "Latest export: ${newest.name}  (${stamp(newest.lastModified())})"
    }

    private fun stamp(t: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(t))
}
