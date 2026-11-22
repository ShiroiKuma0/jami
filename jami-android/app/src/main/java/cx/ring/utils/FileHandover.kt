package cx.ring.utils

import android.content.Context
import cx.ring.application.JamiApplication
import cx.ring.automation.Bridge
import io.reactivex.rxjava3.subjects.PublishSubject
import net.jami.services.JamiFileTransferInfoProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * shiroikuma (SK-HANDOVER): which of our SENT files a peer has actually pulled.
 *
 * In a swarm the file bytes are never pushed: the message (a commit) goes out, and a peer that wants
 * the file opens a channel back to us and pulls it. The blue "sent" marker only says the commit was
 * fetched. The daemon does raise one "finished" data-transfer event on the sending side when it has
 * written every byte of the file to a peer — and stock shows nothing for it. That event is one-off,
 * so it is recorded here to survive a restart and shown on the outgoing file bubble. While a pull
 * runs, the bubble shows the bytes written so far (the daemon patch SK-OUTPROGRESS supplies them).
 *
 * What it proves: all bytes were written to a peer's channel. Not which peer (the event carries
 * none; in a group it is "at least one device"), and not that the receiver verified the copy.
 */
object FileHandover {
    private const val PREFS = "sk_file_handover"
    private const val MAX = 2000
    private const val ONGOING = 5    // libjami::DataTransferEventCode::ongoing
    private const val FINISHED = 6   // libjami::DataTransferEventCode::finished

    private fun prefs() = JamiApplication.instance?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A pull of one of our files that is running right now. */
    class Pull(val accountId: String, val conversationId: String, val fileId: String) {
        @Volatile var bytes = 0L
        @Volatile var total = 0L
        @Volatile var misses = 0
    }

    private val pulls = ConcurrentHashMap<String, Pull>()
    private val poller = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sk-handover").apply { isDaemon = true } }
    private var tick: ScheduledFuture<*>? = null

    /** Emits a message id whenever what the bubble should show for it has changed. */
    val changes: PublishSubject<String> = PublishSubject.create()

    /** Bytes written / total for a pull in flight, or null when none is running for [messageId]. */
    fun progress(messageId: String?): Pair<Long, Long>? =
        messageId?.let { pulls[it] }?.takeIf { it.total > 0 }?.let { it.bytes to it.total }

    /** Every daemon data-transfer event passes here, for every conversation. */
    fun note(accountId: String, conversationId: String, messageId: String, fileId: String, eventCode: Int) {
        if (messageId.isEmpty()) return
        when (eventCode) {
            // The daemon announces a pull starting (patches/jami-outgoing-transfer-progress.patch).
            // The same code arrives for a file WE are receiving; that one has no outgoing copy,
            // so the first polls miss and it is dropped again.
            ONGOING -> if (fileId.isNotEmpty() && pulls.putIfAbsent(messageId, Pull(accountId, conversationId, fileId)) == null)
                startPolling()
            FINISHED -> {
                pulls.remove(messageId)
                record(messageId)
                changes.onNext(messageId)
            }
        }
    }

    @Synchronized
    private fun startPolling() {
        if (tick?.isDone == false) return
        tick = poller.scheduleWithFixedDelay({ runCatching { poll() } }, 300, 1000, TimeUnit.MILLISECONDS)
    }

    private fun poll() {
        for ((id, p) in pulls) {
            val info = runCatching {
                Bridge.daemon { JamiFileTransferInfoProvider.get(p.accountId, p.conversationId, p.fileId + "?sk-out") }
            }.getOrNull()
            if (info != null && info.isSuccess) {
                p.misses = 0
                p.bytes = info.progress
                p.total = info.total
            } else if (++p.misses >= 3) {
                pulls.remove(id)   // the pull ended without finishing (or was never ours)
            }
            changes.onNext(id)
        }
        synchronized(this) { if (pulls.isEmpty()) tick?.cancel(false) }
    }

    private fun record(messageId: String) {
        val p = prefs() ?: return
        if (p.contains(messageId)) return
        val e = p.edit().putLong(messageId, System.currentTimeMillis())
        val all = p.all
        if (all.size >= MAX)
            all.entries.sortedBy { (it.value as? Long) ?: 0L }.take(all.size - MAX + 200).forEach { e.remove(it.key) }
        e.apply()
    }

    fun has(messageId: String?): Boolean =
        !messageId.isNullOrEmpty() && prefs()?.contains(messageId) == true
}
