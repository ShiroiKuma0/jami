package cx.ring.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export/Import engine for every settable item in the app, mirroring the Kōjiki/ArcaneChat model —
 * a zip of one JSON file per category plus a manifest, written to a user-chosen SAF directory.
 * Every SharedPreferences key round-trips with a type tag ({"t":..,"v":..}), and import is a
 * per-key MERGE (never a clear), so unknown/missing keys are simply left alone — old exports load
 * into new app versions and vice versa. Imported font files travel as raw fonts/<name> zip entries.
 */
object SettingsExport {

    const val FORMAT = "shiroikuma-jami-export"
    const val VERSION = 1

    /** Filename stem: english-dash-separated app name, no version (白い熊, 2026-07-25) —
     *  `shiroikuma-jami_2026-07-25_18-58-23.zip`. Kept WITHOUT the trailing separator so the
     *  latest-export scan still matches the older `shiroikuma-jami-export_*` files too. */
    const val EXPORT_PREFIX = "shiroikuma-jami"

    /** Device-local prefs holding the export-directory URI; deliberately never exported. */
    private const val EXIM_PREFS = "shiroikuma_eximport"
    private const val KEY_DIR_URI = "dir_uri"

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

    /** The selectable categories; [id] doubles as the JSON entry name inside the zip. */
    enum class Cat(val id: String, val label: String) {
        ACCOUNTS("accounts", "Accounts (Jami archives)"),
        FONTS("fonts", "Fonts & sizes"),
        COLORS("colors", "Colours"),
        UI("ui", "UI behaviour"),
        RECOVERY("recovery", "Online recovery & connectivity"),
        AUTOMATION("automation", "Automation & protected contacts"),
        APP("app_settings", "App settings"),
    }

    /** prefs-file name → key filter (null = every key) for one category. */
    private fun stores(cat: Cat): Map<String, ((String) -> Boolean)?> = when (cat) {
        Cat.ACCOUNTS -> emptyMap()   // daemon archives, not prefs — handled by the caller
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

    /** Builds the export zip for the given categories. Account archives (collected by the caller —
     *  they need the daemon) travel as raw accounts/<id>.gz entries plus an accounts.json meta. */
    fun export(
        c: Context, cats: List<Cat>,
        accountArchives: Map<String, ByteArray> = emptyMap(),
        accountsMeta: JSONObject? = null,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("app", c.packageName)
                .put("createdTs", System.currentTimeMillis())
                .put("categories", JSONArray(cats.map { it.id }))
            writeEntry(zip, "manifest.json", manifest.toString(2).toByteArray())
            for (cat in cats) {
                if (cat == Cat.ACCOUNTS) {
                    writeEntry(zip, "accounts.json",
                        (accountsMeta ?: JSONObject()).toString(2).toByteArray())
                    for ((id, data) in accountArchives) writeEntry(zip, "accounts/$id.gz", data)
                    continue
                }
                val obj = JSONObject()
                for ((prefsName, filter) in stores(cat)) {
                    obj.put(prefsName, exportPrefs(
                        c.getSharedPreferences(prefsName, Context.MODE_PRIVATE), filter))
                }
                writeEntry(zip, "${cat.id}.json", obj.toString(2).toByteArray())
                if (cat == Cat.FONTS) {
                    for (f in FontPrefs.getFontFiles(c)) {
                        writeEntry(zip, "fonts/${f.name}", f.readBytes())
                    }
                }
            }
        }
        return bos.toByteArray()
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

    // --- import -------------------------------------------------------------------------------

    /** The categories present in an export zip; empty if it isn't one of ours. */
    fun categoriesIn(bytes: ByteArray): List<Cat> = try {
        val files = readZip(bytes)
        val manifest = files["manifest.json"]?.let { JSONObject(String(it)) }
        if (manifest == null || manifest.optString("format") != FORMAT) emptyList()
        else Cat.entries.filter { files.containsKey("${it.id}.json") }
    } catch (_: Exception) { emptyList() }

    /**
     * Applies the selected categories from an export zip; categories missing from the zip are
     * skipped. Returns a human-readable per-category summary, or null when the file carried none.
     */
    fun importData(c: Context, bytes: ByteArray, cats: List<Cat>): String? {
        val files = readZip(bytes)
        if (files["manifest.json"] == null) return null
        val summary = StringBuilder()
        var any = false
        for (cat in cats) {
            if (cat == Cat.ACCOUNTS) continue   // daemon archives — the caller restores them
            val data = files["${cat.id}.json"] ?: continue
            var n = importCat(c, cat, JSONObject(String(data)))
            if (cat == Cat.FONTS) n += importFontFiles(c, files)
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
        return "file:${java.io.File(FontPrefs.fontsDir(c), name).absolutePath}"
    }

    /** Restores the fonts/<name> zip entries into filesDir/fonts. Returns files written. */
    private fun importFontFiles(c: Context, files: Map<String, ByteArray>): Int {
        var n = 0
        for ((name, bytes) in files) {
            if (!name.startsWith("fonts/")) continue
            val base = name.removePrefix("fonts/").substringAfterLast('/')
            if (base.isEmpty()) continue
            runCatching {
                java.io.File(FontPrefs.fontsDir(c), base).writeBytes(bytes)
                n++
            }
        }
        return n
    }

    /** The accounts/<id>.gz archives in an export zip (accountId → archive bytes). */
    fun accountArchivesIn(bytes: ByteArray): Map<String, ByteArray> =
        readZip(bytes).filterKeys { it.startsWith("accounts/") && it.endsWith(".gz") }
            .mapKeys { it.key.removePrefix("accounts/").removeSuffix(".gz") }

    /** The accounts.json metadata (accountId → {uri, alias, registeredName}), or empty. */
    fun accountsMetaIn(bytes: ByteArray): JSONObject =
        readZip(bytes)["accounts.json"]
            ?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: JSONObject()

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return out
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
        val cacheDir = java.io.File(app.cacheDir, "eximport").apply { mkdirs() }
        for (a in accountService.getAccounts().filter { it.isJami }) {
            val label = a.registeredName.ifBlank { a.alias.orEmpty() }.ifBlank { a.accountId.take(8) }
            if (a.hasPassword()) {
                notes.append("\n$label: archive has a password — not included.")
                continue
            }
            val f = java.io.File(cacheDir, "${a.accountId}.gz")
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

    // --- export directory + latest-export probe ----------------------------------------------

    private fun p(c: Context) = c.getSharedPreferences(EXIM_PREFS, Context.MODE_PRIVATE)

    fun getDirUri(c: Context): Uri? =
        p(c).getString(KEY_DIR_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setDirUri(c: Context, uri: Uri) {
        p(c).edit().putString(KEY_DIR_URI, uri.toString()).apply()
    }

    fun getExportDir(c: Context): DocumentFile? {
        val uri = getDirUri(c) ?: return null
        return runCatching { DocumentFile.fromTreeUri(c, uri)?.takeIf { it.isDirectory } }.getOrNull()
    }

    /** The newest export zip in the configured directory, or null. */
    fun latestExport(c: Context): DocumentFile? {
        val dir = getExportDir(c) ?: return null
        return runCatching {
            dir.listFiles().filter {
                it.isFile && (it.name?.let { n -> n.startsWith(EXPORT_PREFIX) && n.endsWith(".zip") } == true)
            }.maxByOrNull { it.lastModified() }
        }.getOrNull()
    }

    /** The "last export" status line — call off the main thread (SAF listing can be slow). */
    fun lastExportStatus(c: Context): String {
        if (getExportDir(c) == null) return "Export directory not set"
        val newest = latestExport(c) ?: return "No exports yet"
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(newest.lastModified()))
        return "Latest export: ${newest.name}  ($ts)"
    }
}
