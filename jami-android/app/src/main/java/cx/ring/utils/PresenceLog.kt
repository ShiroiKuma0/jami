package cx.ring.utils

import android.content.Context
import cx.ring.application.JamiApplication
import net.jami.utils.PresenceTrace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Settings → Diagnostics → “Presence changes” (2026-11-27): every presence change the daemon
 * reports for a contact, kept in [DiagFiles.PRESENCE_LOG] — what the daemon said (OFFLINE /
 * AVAILABLE = its announcement is heard / CONNECTED = a live channel), what the contact's dot was
 * given, and what it was before. Also when an account is put to sleep or woken, because a sleeping
 * account hears no presence at all.
 *
 * The source is the daemon's own call ([PresenceTrace], fed by Account.presenceUpdate), not a log
 * line. Switching on writes a snapshot of every contact's current state first, so the file can be
 * read from its first line. Off = no sink, nothing written.
 *
 * One line is opened, appended and closed at a time on a single background thread. At [MAX_BYTES]
 * the file moves to [DiagFiles.PRESENCE_LOG_OLD], replacing the previous one.
 */
object PresenceLog {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sk-presence-log").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var app: Context? = null

    /** Call once, after [DiagPrefs.init] and [DiagFiles.init]. */
    fun init(context: Context) {
        app = context.applicationContext
        DiagPrefs.addListener { g, on -> if (g == DiagPrefs.Group.PRESENCE) { if (on) start(true) else stop() } }
        if (DiagPrefs.isOn(DiagPrefs.Group.PRESENCE)) start(false)
    }

    private fun start(withSnapshot: Boolean) {
        write("recording on (pid ${android.os.Process.myPid()})" +
            if (withSnapshot) "" else " — app start: every contact begins at OFFLINE until the daemon reports it")
        PresenceTrace.sink = ::write
        if (withSnapshot) snapshot()
    }

    private fun stop() {
        PresenceTrace.sink = null
        write("recording off")
    }

    /** The state every contact has right now, so that later changes have a starting point. */
    private fun snapshot() {
        runCatching {
            val accounts = JamiApplication.instance?.mAccountService?.getAccounts() ?: return
            for (a in accounts) {
                if (!a.isJami) continue
                val seen = HashSet<String>()
                for (conv in a.getConversations()) for (ct in conv.contacts) {
                    if (ct.isUser) continue
                    val uri = ct.uri.rawRingId
                    if (uri.isEmpty() || !seen.add(uri)) continue
                    write("acct ${a.accountId.take(6)}  peer $uri  snapshot=${ct.lastPresence.name}")
                }
            }
        }.onFailure { write("snapshot failed (${it.javaClass.simpleName}: ${it.message})") }
    }

    private fun write(line: String) {
        val c = app ?: return
        val t = System.currentTimeMillis()
        io.execute {
            runCatching {
                val f = DiagFiles.file(c, DiagFiles.PRESENCE_LOG)
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, DiagFiles.PRESENCE_LOG_OLD)
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(stamp.format(Date(t)) + "  " + line + "\n")
            }
        }
    }
}
