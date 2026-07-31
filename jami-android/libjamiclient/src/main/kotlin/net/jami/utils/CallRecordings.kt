/*
 *  shiroikuma fork — index of locally recorded calls.
 *
 *  A call recording is written by the daemon and belongs to nobody: it is not a swarm message, it is
 *  never sent to the peer, and the daemon tells the client about it exactly once, through the
 *  RecordPlaybackFilepath signal (which upstream's client logs and drops). So there is nothing in
 *  either history store that would make it reappear after the conversation is reloaded.
 *
 *  This is that missing store — a flat, append-only TSV beside the recordings themselves, holding one
 *  line per recording. It is deliberately dumb: no database, no schema migration, and a corrupt or
 *  half-written line costs one bubble rather than the file it points at. Entries whose file has been
 *  deleted are skipped on read, so removing a recording from storage is a supported way to remove it
 *  from the conversation.
 */
package net.jami.utils

import java.io.File

object CallRecordings {
    private const val INDEX_NAME = "index.tsv"
    private const val TAG = "CallRecordings"

    data class Entry(
        val accountId: String,
        val conversationUri: String,
        val file: File,
        val timestamp: Long
    )

    private fun index(dir: File) = File(dir, INDEX_NAME)

    @Synchronized
    fun add(dir: File, entry: Entry) {
        try {
            dir.mkdirs()
            index(dir).appendText(
                "${entry.accountId}\t${entry.conversationUri}\t${entry.file.absolutePath}\t${entry.timestamp}\n"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to record ${entry.file}", e)
        }
    }

    /** Forget one recording. Rewrites the index without it; a missing index is not an error. */
    @Synchronized
    fun remove(dir: File, file: File) {
        val f = index(dir)
        if (!f.isFile) return
        try {
            val kept = f.readLines().filter { line ->
                val p = line.split('\t')
                p.size < 4 || p[2] != file.absolutePath
            }
            f.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to forget ${file.name}", e)
        }
    }

    /** Every recording known for one conversation, oldest first, skipping files that are gone. */
    @Synchronized
    fun forConversation(dir: File, accountId: String, conversationUri: String): List<Entry> {
        val f = index(dir)
        if (!f.isFile) return emptyList()
        return try {
            f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 4) return@mapNotNull null
                if (p[0] != accountId || p[1] != conversationUri) return@mapNotNull null
                val file = File(p[2])
                if (!file.isFile) return@mapNotNull null
                Entry(p[0], p[1], file, p[3].toLongOrNull() ?: file.lastModified())
            }.sortedBy { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read the recording index", e)
            emptyList()
        }
    }
}
