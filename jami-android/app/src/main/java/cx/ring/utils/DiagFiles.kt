package cx.ring.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TreeMap

/**
 * Settings → Diagnostics storage (2026-11-03, the same contract as 白い熊 地図's ChizuDiag): every
 * diagnostic file the app writes lives in `<getExternalFilesDir(null)>/diag/`, i.e.
 * “/sdcard/Android/data/shiroikuma.jami/files/diag/”, readable with adb — the recovery-log mirror,
 * the watchdog incidents, the data-usage logs, the presence map and upstream's push log.
 *
 * Every writer opens, appends and closes per line (`appendText`/`writeText`), so nothing holds a
 * file open across a migration or a purge and no reopen generation is needed. The daemon's `sk-diag`
 * mask in filesDir is a switch, not a log, and stays where it is ([DiagPrefs]).
 */
object DiagFiles {
    const val DIR = "diag"
    private const val PREFS = "shiroikuma_diag"
    private const val KEY_EXPORT_TREE = "export_tree"

    const val RECOVERY_LOG = "recovery-log.txt"
    const val INCIDENTS = "watchdog-incidents.log"
    const val DATA_HOURLY = "data-hourly-log.txt"
    const val DATA_MEASURE = "data-measure-log.txt"
    const val PRESENCE_MAP = "presence-map.txt"
    const val PUSH_LOG = "firebaselog.txt"

    /** Files that used to sit in the external files root. */
    private val EXTERNAL_ROOT_NAMES = listOf(RECOVERY_LOG, INCIDENTS, DATA_HOURLY, DATA_MEASURE, PRESENCE_MAP)
    /** Files that used to sit in the internal filesDir (unreadable over adb on a release build). */
    private val INTERNAL_NAMES = listOf(PUSH_LOG)

    @Volatile private var app: Context? = null

    /** The diag directory, created on demand. Falls back to filesDir/diag when external storage is absent. */
    fun dir(c: Context? = null): File {
        val ctx = c?.applicationContext ?: app ?: throw IllegalStateException("DiagFiles.init not called")
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(base, DIR).apply { if (!isDirectory) mkdirs() }
    }

    fun file(c: Context, name: String): File = File(dir(c), name)

    /** Call once, early in Application.onCreate: moves old files in, off the main thread. */
    fun init(context: Context) {
        val c = context.applicationContext
        app = c
        Thread({ runCatching { migrate(c) } }, "sk-diag-migrate").apply { isDaemon = true }.start()
    }

    // ---------- moving the old files in ----------

    /** Idempotent: rename, or copy+delete when rename fails; prepend when the target exists. */
    private fun migrate(c: Context) {
        val target = dir(c)
        val sources = buildList {
            c.getExternalFilesDir(null)?.let { root -> EXTERNAL_ROOT_NAMES.forEach { add(File(root, it)) } }
            INTERNAL_NAMES.forEach { add(File(c.filesDir, it)) }
        }
        for (f in sources) {
            if (!f.isFile) continue
            runCatching { moveInto(f, File(target, f.name)) }   // left in place on failure; next start retries
        }
    }

    private fun moveInto(f: File, to: File) {
        if (!to.exists() && f.renameTo(to)) return
        val tmp = File(to.parentFile, to.name + ".moving")
        FileOutputStream(tmp).use { out ->
            copy(f, out)
            if (to.exists()) copy(to, out)
        }
        check(tmp.renameTo(to)) { "rename $tmp" }
        check(f.delete()) { "delete $f" }
    }

    private fun copy(f: File, out: OutputStream) {
        FileInputStream(f).use { it.copyTo(out, 64 * 1024) }
    }

    // ---------- what is stored ----------

    /** Every file in the diag directory, by name. */
    fun files(c: Context): List<File> =
        (dir(c).listFiles { f -> f.isFile && !f.name.endsWith(".moving") } ?: emptyArray())
            .sortedBy { it.name }

    fun kindOf(name: String): String = when (name) {
        RECOVERY_LOG -> "Recovery log"
        INCIDENTS -> "Watchdog incidents"
        DATA_HOURLY, DATA_MEASURE -> "Data usage"
        PRESENCE_MAP -> "Presence map"
        PUSH_LOG -> "Push log"
        else -> "Other"
    }

    class Span(var first: Long = Long.MAX_VALUE, var last: Long = Long.MIN_VALUE) {
        fun add(t: Long) { if (t < first) first = t; if (t > last) last = t }
        fun add(o: Span) { if (o.first <= o.last) { add(o.first); add(o.last) } }
        val empty get() = first > last
    }

    class Kind(var files: Int = 0, var bytes: Long = 0, val span: Span = Span())

    class Summary {
        var files = 0
        var bytes = 0L
        val span = Span()
        val kinds = TreeMap<String, Kind>()
        /** "yyyy-MM-dd" → first/last line time on that day. */
        val days = TreeMap<String, Span>()
    }

    /** Reads every file's line times. Several MB at most — call off the main thread. */
    fun summarize(c: Context): Summary {
        val s = Summary()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for (f in files(c)) {
            val times = lineTimes(f).ifEmpty { listOf(f.lastModified()) }
            val fs = Span()
            for (t in times) {
                fs.add(t)
                s.days.getOrPut(dayFmt.format(Date(t))) { Span() }.add(t)
            }
            s.files++; s.bytes += f.length(); s.span.add(fs)
            s.kinds.getOrPut(kindOf(f.name)) { Kind() }.apply { files++; bytes += f.length(); span.add(fs) }
        }
        return s
    }

    private val FULL = Regex("""(\d{4})-(\d{2})-(\d{2})[ T_](\d{2}):(\d{2})(?::(\d{2}))?""")
    private val SHORT = Regex("""^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})""")   // recovery log: "MM-dd HH:mm:ss"

    /** The time of every line that carries one: a full date-time anywhere, or the recovery log's
     *  yearless “MM-dd HH:mm:ss” prefix (its year taken from the file's mtime, wrapping back a year). */
    private fun lineTimes(f: File): List<Long> {
        val out = ArrayList<Long>()
        val mtime = f.lastModified()
        val cal = Calendar.getInstance()
        val year = Calendar.getInstance().apply { timeInMillis = mtime }.get(Calendar.YEAR)
        runCatching {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val full = FULL.find(line)
                    if (full != null) {
                        val g = full.groupValues
                        cal.clear()
                        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(),
                            g[6].ifEmpty { "0" }.toInt())
                        out.add(cal.timeInMillis)
                        continue
                    }
                    val short = SHORT.find(line) ?: continue
                    val g = short.groupValues
                    cal.clear()
                    cal.set(year, g[1].toInt() - 1, g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt())
                    if (cal.timeInMillis > mtime + 86_400_000L) cal.add(Calendar.YEAR, -1)
                    out.add(cal.timeInMillis)
                }
            }
        }
        return out
    }

    fun filesLabel(count: Int, bytes: Long): String =
        String.format(Locale.US, "%d %s, %.1f MB", count, if (count == 1) "file" else "files", bytes / 1048576.0)

    fun spanLabel(span: Span): String {
        if (span.empty) return "—"
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return f.format(Date(span.first)) + " – " + f.format(Date(span.last))
    }

    // ---------- export and purge ----------

    fun getExportTree(c: Context): Uri? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EXPORT_TREE, null)?.let(Uri::parse)

    fun setExportTree(c: Context, tree: Uri) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_EXPORT_TREE, tree.toString()).apply()
    }

    /** Copies every diag file into a new “<yyyy-MM-dd_HH-mm-ss>_jami-diag” subdirectory of [tree].
     *  Returns (subdirectory name, files copied). Off the main thread. */
    fun export(c: Context, tree: Uri): Pair<String, Int> {
        val root = DocumentFile.fromTreeUri(c, tree)
        check(root != null && root.canWrite()) { "the chosen directory is not writable" }
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + "_jami-diag"
        val sub = root.createDirectory(name) ?: error("could not create $name")
        var n = 0
        for (f in files(c)) {
            val doc = sub.createFile("application/octet-stream", f.name) ?: error(f.name)
            (c.contentResolver.openOutputStream(doc.uri) ?: error(f.name)).use { copy(f, it) }
            n++
        }
        return name to n
    }

    /** Deletes every diag file; returns how many went. */
    fun purge(c: Context): Int = files(c).count { it.delete() }

    /** “/sdcard/tmp” for a tree on the phone's own storage, else the tree's document id. */
    fun treeLabel(tree: Uri): String = runCatching {
        val id = android.provider.DocumentsContract.getTreeDocumentId(tree)
        if (id.startsWith("primary:")) "/sdcard/" + id.removePrefix("primary:") else id
    }.getOrDefault(tree.toString())
}
