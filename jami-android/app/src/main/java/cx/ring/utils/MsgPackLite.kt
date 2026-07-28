package cx.ring.utils

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * The smallest MessagePack reader/rewriter that does the job.
 *
 * The daemon's `convInfo` file is a msgpack map of conversationId → ConvInfo (itself a map, see
 * `ConversationMapKeys` in daemon/src/jamidht/conversation.h). The deleted-conversation restore
 * needs exactly two operations on it: read the `created`/`removed` stamps to find which
 * conversations are flagged removed, and drop one top-level entry so the daemon rebuilds it from
 * the restored repository at the next scan.
 *
 * Only the top level is decoded structurally — every value is skipped as an opaque byte span and
 * copied verbatim on rewrite, so ConvInfo fields this fork has never heard of survive untouched.
 * That is also why this is 120 lines instead of a dependency.
 */
object MsgPackLite {

    /** One top-level map entry: the decoded key, and the byte span its value occupies. */
    data class Entry(val key: String, val valueStart: Int, val valueEnd: Int)

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    /** Big-endian unsigned read of [len] bytes. */
    private fun be(b: ByteArray, i: Int, len: Int): Long {
        var v = 0L
        for (k in 0 until len) v = (v shl 8) or u(b, i + k).toLong()
        return v
    }

    private fun bad(what: String, t: Int): Nothing =
        throw IllegalArgumentException("msgpack: $what, got 0x${t.toString(16)}")

    /** Offset just past the object starting at [p]. Every type is handled; none is decoded. */
    fun skip(b: ByteArray, p: Int): Int {
        val t = u(b, p)
        return when {
            t <= 0x7f || t >= 0xe0 -> p + 1                          // fixint (both signs)
            t in 0x80..0x8f -> skipN(b, p + 1, (t and 0x0f) * 2)     // fixmap
            t in 0x90..0x9f -> skipN(b, p + 1, t and 0x0f)           // fixarray
            t in 0xa0..0xbf -> p + 1 + (t and 0x1f)                  // fixstr
            t == 0xc0 || t == 0xc2 || t == 0xc3 -> p + 1             // nil / false / true
            t == 0xc4 -> p + 2 + be(b, p + 1, 1).toInt()             // bin8
            t == 0xc5 -> p + 3 + be(b, p + 1, 2).toInt()             // bin16
            t == 0xc6 -> p + 5 + be(b, p + 1, 4).toInt()             // bin32
            t == 0xc7 -> p + 3 + be(b, p + 1, 1).toInt()             // ext8
            t == 0xc8 -> p + 4 + be(b, p + 1, 2).toInt()             // ext16
            t == 0xc9 -> p + 6 + be(b, p + 1, 4).toInt()             // ext32
            t == 0xca -> p + 5                                       // float32
            t == 0xcb -> p + 9                                       // float64
            t in 0xcc..0xcf -> p + 1 + (1 shl (t - 0xcc))            // uint8..uint64
            t in 0xd0..0xd3 -> p + 1 + (1 shl (t - 0xd0))            // int8..int64
            t in 0xd4..0xd8 -> p + 2 + (1 shl (t - 0xd4))            // fixext1..fixext16
            t == 0xd9 -> p + 2 + be(b, p + 1, 1).toInt()             // str8
            t == 0xda -> p + 3 + be(b, p + 1, 2).toInt()             // str16
            t == 0xdb -> p + 5 + be(b, p + 1, 4).toInt()             // str32
            t == 0xdc -> skipN(b, p + 3, be(b, p + 1, 2).toInt())    // array16
            t == 0xdd -> skipN(b, p + 5, be(b, p + 1, 4).toInt())    // array32
            t == 0xde -> skipN(b, p + 3, be(b, p + 1, 2).toInt() * 2) // map16
            t == 0xdf -> skipN(b, p + 5, be(b, p + 1, 4).toInt() * 2) // map32
            else -> bad("unknown type byte", t)
        }
    }

    private fun skipN(b: ByteArray, start: Int, count: Int): Int {
        var p = start
        repeat(count) { p = skip(b, p) }
        return p
    }

    /** (entry count, offset just past the header) for the map starting at [p]. */
    private fun mapHeader(b: ByteArray, p: Int): Pair<Int, Int> {
        val t = u(b, p)
        return when {
            t in 0x80..0x8f -> (t and 0x0f) to (p + 1)
            t == 0xde -> be(b, p + 1, 2).toInt() to (p + 3)
            t == 0xdf -> be(b, p + 1, 4).toInt() to (p + 5)
            else -> bad("map expected", t)
        }
    }

    /** (value, offset just past it) for the string starting at [p]. */
    private fun readString(b: ByteArray, p: Int): Pair<String, Int> {
        val t = u(b, p)
        val (len, start) = when {
            t in 0xa0..0xbf -> (t and 0x1f) to (p + 1)
            t == 0xd9 -> be(b, p + 1, 1).toInt() to (p + 2)
            t == 0xda -> be(b, p + 1, 2).toInt() to (p + 3)
            t == 0xdb -> be(b, p + 1, 4).toInt() to (p + 5)
            else -> bad("string expected", t)
        }
        return String(b, start, len, Charsets.UTF_8) to (start + len)
    }

    /** Signed integer at [p], or null when what sits there is not an integer. */
    private fun readLong(b: ByteArray, p: Int): Long? {
        val t = u(b, p)
        return when {
            t <= 0x7f -> t.toLong()
            t >= 0xe0 -> (t - 256).toLong()
            t == 0xcc -> be(b, p + 1, 1)
            t == 0xcd -> be(b, p + 1, 2)
            t == 0xce -> be(b, p + 1, 4)
            t == 0xcf -> be(b, p + 1, 8)
            t == 0xd0 -> b[p + 1].toLong()
            t == 0xd1 -> be(b, p + 1, 2).toShort().toLong()
            t == 0xd2 -> be(b, p + 1, 4).toInt().toLong()
            t == 0xd3 -> be(b, p + 1, 8)
            else -> null
        }
    }

    /** The top-level map's entries, in file order. */
    fun topEntries(b: ByteArray): List<Entry> {
        val (n, afterHeader) = mapHeader(b, 0)
        var p = afterHeader
        val out = ArrayList<Entry>(n)
        repeat(n) {
            val (key, afterKey) = readString(b, p)
            val end = skip(b, afterKey)
            out.add(Entry(key, afterKey, end))
            p = end
        }
        return out
    }

    /** A named integer field inside the map that is the value of top-level entry [e]. */
    fun intField(b: ByteArray, e: Entry, name: String): Long? {
        val (n, afterHeader) = mapHeader(b, e.valueStart)
        var p = afterHeader
        repeat(n) {
            val (key, afterKey) = readString(b, p)
            val end = skip(b, afterKey)
            if (key == name) return readLong(b, afterKey)
            p = end
        }
        return null
    }

    /** The same top-level map minus [drop]; surviving values are copied byte for byte. */
    fun withoutKeys(b: ByteArray, drop: Set<String>): ByteArray {
        val kept = topEntries(b).filter { it.key !in drop }
        val out = ByteArrayOutputStream(b.size)
        writeMapHeader(out, kept.size)
        for (e in kept) {
            writeString(out, e.key)
            out.write(b, e.valueStart, e.valueEnd - e.valueStart)
        }
        return out.toByteArray()
    }

    private fun writeMapHeader(out: OutputStream, n: Int) = when {
        n < 16 -> out.write(0x80 or n)
        n < 65536 -> { out.write(0xde); out.write(n ushr 8); out.write(n and 0xFF) }
        else -> { out.write(0xdf); for (s in 24 downTo 0 step 8) out.write((n ushr s) and 0xFF) }
    }

    private fun writeString(out: OutputStream, s: String) {
        val raw = s.toByteArray(Charsets.UTF_8)
        when {
            raw.size < 32 -> out.write(0xa0 or raw.size)
            raw.size < 256 -> { out.write(0xd9); out.write(raw.size) }
            raw.size < 65536 -> { out.write(0xda); out.write(raw.size ushr 8); out.write(raw.size and 0xFF) }
            else -> { out.write(0xdb); for (s2 in 24 downTo 0 step 8) out.write((raw.size ushr s2) and 0xFF) }
        }
        out.write(raw)
    }
}
