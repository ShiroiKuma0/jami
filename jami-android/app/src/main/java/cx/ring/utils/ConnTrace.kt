package cx.ring.utils

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Settings → Diagnostics → “Connection trace” (2026-11-23): the daemon's connection lines, kept in a
 * FILE. Every other connection diagnostic ends in logcat, and logcat is not a record: on the Samsung
 * relay phones it rolls over in about five minutes, and EMUI keeps ERROR-level lines only. On
 * 2026-11-23 a message sat stuck for five minutes between two awake phones and no log of those
 * minutes existed on any of the three devices by the time anyone looked.
 *
 * While the switch is on, this tails the process's own logcat from "now" and appends to
 * [DiagFiles.CONN_TRACE]: every `SK-…` diagnostic line, and the daemon's own lines from the files
 * that dial, carry and sync ([FILES]). UPnP, audio and video are left out. Off = no reader process,
 * no thread, nothing written.
 *
 * The lines keep logcat's “MM-dd HH:mm:ss.SSS L/tag(pid): …” form, which [DiagFiles] can date.
 * Writes are batched once a second (open, append, close), so a purge or a deleted file is simply
 * recreated by the next batch. At [MAX_BYTES] the file moves to [DiagFiles.CONN_TRACE_OLD],
 * replacing the previous one — two files, at most twice the cap.
 */
object ConnTrace {
    private const val TAG = "ConnTrace"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val FLUSH_MS = 1_000L
    private const val RESTART_BACKOFF_MS = 5_000L

    /** Daemon source files whose lines are kept (the logcat tag is the file name). */
    private val FILES = setOf(
        "connectionmanager.cpp", "multiplexed_socket.cpp", "ice_transport.cpp", "tls_session.cpp",
        "peer_connection.cpp", "turn_cache.cpp", "turn_transport.cpp",
        "jamiaccount.cpp", "account_manager.cpp", "archive_account_manager.cpp", "sipaccountbase.cpp",
        "message_engine.cpp", "message_channel_handler.cpp", "channeled_transport.cpp",
        "swarm_manager.cpp", "swarm_channel_handler.cpp", "routing_table.cpp",
        "conversation_module.cpp", "conversation.cpp", "conversationrepository.cpp",
        "conversation_channel_handler.cpp", "gitserver.cpp", "gittransport.cpp",
        "sync_module.cpp", "sync_channel_handler.cpp", "manager.cpp",
    )

    private val lock = Any()
    private val pending = StringBuilder()
    @Volatile private var app: Context? = null
    @Volatile private var generation = 0
    private var proc: Process? = null

    /** Call once, after [DiagPrefs.init] and [DiagFiles.init]. */
    fun init(context: Context) {
        app = context.applicationContext
        DiagPrefs.addListener { g, on -> if (g == DiagPrefs.Group.CONN_TRACE) { if (on) start() else stop() } }
        if (DiagPrefs.isOn(DiagPrefs.Group.CONN_TRACE)) start()
    }

    private fun start() {
        val gen = synchronized(lock) { ++generation }
        mark("trace on (pid ${android.os.Process.myPid()})")
        thread(name = "sk-conn-trace", isDaemon = true) { readLoop(gen) }
        thread(name = "sk-conn-trace-flush", isDaemon = true) {
            while (generation == gen) {
                try { Thread.sleep(FLUSH_MS) } catch (_: InterruptedException) { break }
                flush()
            }
            flush()
        }
    }

    private fun stop() {
        mark("trace off")
        synchronized(lock) {
            generation++
            proc?.destroy(); proc = null
        }
    }

    private fun readLoop(gen: Int) {
        val pid = android.os.Process.myPid().toString()
        while (generation == gen) {
            try {
                // "-T 1": from now on. Replaying the buffer would duplicate lines on every restart.
                val p = ProcessBuilder("logcat", "--pid=$pid", "-v", "time", "-T", "1")
                    .redirectErrorStream(true).start()
                synchronized(lock) { if (generation == gen) proc = p else p.destroy() }
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (wanted(line)) synchronized(pending) { pending.append(line).append('\n') }
                }
                p.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "logcat reader died: $e")
            }
            if (generation != gen) return
            try { Thread.sleep(RESTART_BACKOFF_MS) } catch (_: InterruptedException) { return }
        }
    }

    /** “MM-dd HH:mm:ss.SSS L/tag(pid): …” — the tag starts at column 21 and ends at the bracket. */
    private fun wanted(line: String): Boolean {
        if (line.length < 24 || line[20] != '/') return false
        val end = line.indexOf('(', 21)
        if (end < 0) return false
        val tag = line.substring(21, end).trim()
        return tag.startsWith("SK-") || tag in FILES
    }

    /** A line of our own, in the same dated form, so the file shows when tracing started and stopped. */
    private fun mark(text: String) {
        val t = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        synchronized(pending) { pending.append(t).append(" I/SK-CONNTRACE: ").append(text).append('\n') }
    }

    private fun flush() {
        val c = app ?: return
        val text = synchronized(pending) {
            if (pending.isEmpty()) return
            pending.toString().also { pending.setLength(0) }
        }
        runCatching {
            val f = DiagFiles.file(c, DiagFiles.CONN_TRACE)
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, DiagFiles.CONN_TRACE_OLD)
                old.delete()
                f.renameTo(old)
            }
            f.appendText(text)
        }
    }
}
