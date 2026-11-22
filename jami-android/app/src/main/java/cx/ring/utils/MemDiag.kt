package cx.ring.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SK-MEMDIAG (2026-11-13): Settings → Diagnostics → “Memory & retained screens”.
 *
 * Why it exists: after 7 days the process held 775 MB — 651 MB of it native (daemon) heap against
 * 138 MB right after a restart — and 7 Activity objects for one live screen, and nothing on the
 * phone had recorded how either got there. While the switch is on, one line every [PERIOD_MS] goes to
 * “diag/memory-log.txt” (never “.log”: EMUI's nightly clean deletes those):
 *
 *   2026-11-13 12:00:00  up 3h12m  pss 412  native 351  java 24  gpu 31 MB  threads 150 [jami-io×40 …]
 *     screens live 1 [HomeActivity]  retained 2 [HomeActivity×2]
 *
 * “retained” = Activities already destroyed that are still reachable after a GC — a leak, named by
 * class. The destroy tracker is a weak list filled by the lifecycle callback and costs nothing; the
 * sampler thread, the GC and the file exist only while the switch is on.
 */
object MemDiag {
    const val PERIOD_MS = 10 * 60_000L
    private val startElapsed = SystemClock.elapsedRealtime()

    private val live = java.util.WeakHashMap<Activity, Unit>()
    private val destroyed = ArrayList<WeakReference<Activity>>()
    @Volatile private var thread: Thread? = null
    private lateinit var app: Context

    fun init(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { synchronized(this@MemDiag) { live[a] = Unit } }
            override fun onActivityDestroyed(a: Activity) {
                synchronized(this@MemDiag) {
                    live.remove(a)
                    destroyed.removeAll { it.get() == null }
                    destroyed.add(WeakReference(a))
                }
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        })
        DiagPrefs.addListener { g, on -> if (g == DiagPrefs.Group.MEMORY) { if (on) start() else stop() } }
        if (DiagPrefs.isOn(DiagPrefs.Group.MEMORY)) start()
    }

    @Synchronized private fun start() {
        if (thread != null) return
        val t = Thread({
            write("diag memory on, ${appVersion()}")
            while (thread === Thread.currentThread()) {
                runCatching { write(sample()) }
                try { Thread.sleep(PERIOD_MS) } catch (_: InterruptedException) { break }
            }
        }, "sk-memdiag").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        thread = t      // assigned before start, or the loop's identity check could see null and exit
        t.start()
    }

    @Synchronized private fun stop() {
        thread?.let { thread = null; it.interrupt() }
        Thread({ write("diag memory off") }, "sk-memdiag-off").start()
    }

    private fun sample(): String {
        // A GC first, so “retained” counts only what is really still referenced.
        Runtime.getRuntime().gc(); System.runFinalization(); Runtime.getRuntime().gc()
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val pss = mi.totalPss / 1024
        val gpu = (mi.getMemoryStat("summary.graphics")?.toIntOrNull() ?: 0) / 1024
        val native = Debug.getNativeHeapAllocatedSize() shr 20
        val rt = Runtime.getRuntime()
        val java = (rt.totalMemory() - rt.freeMemory()) shr 20

        val threads = (File("/proc/self/task").listFiles() ?: emptyArray()).mapNotNull {
            runCatching { File(it, "comm").readText().trim() }.getOrNull()
        }
        // Group by name with digits dropped (“jami-io-12” → “jami-io-#”): a thread leak shows as one
        // group that keeps growing.
        val groups = threads.groupingBy { it.replace(Regex("\\d+"), "#") }.eachCount()
            .entries.sortedByDescending { it.value }.take(6)
            .joinToString(" ") { "${it.key}×${it.value}" }

        val (liveNames, retainedNames) = synchronized(this) {
            destroyed.removeAll { it.get() == null }
            live.keys.map { it.javaClass.simpleName } to destroyed.mapNotNull { it.get()?.javaClass?.simpleName }
        }
        fun names(l: List<String>) = l.groupingBy { it }.eachCount().entries
            .joinToString(" ") { if (it.value > 1) "${it.key}×${it.value}" else it.key }

        return "up ${dur(SystemClock.elapsedRealtime() - startElapsed)}  pss $pss  native $native  java $java  gpu $gpu MB" +
            "  threads ${threads.size} [$groups]" +
            "  screens live ${liveNames.size} [${names(liveNames)}]  retained ${retainedNames.size} [${names(retainedNames)}]"
    }

    private fun dur(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 1440) "${m / 1440}d${(m % 1440) / 60}h" else "${m / 60}h${m % 60}m"
    }

    private fun appVersion(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun write(line: String) = runCatching {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        DiagFiles.file(app, DiagFiles.MEMORY_LOG).appendText("$ts  $line\n")
    }
}
