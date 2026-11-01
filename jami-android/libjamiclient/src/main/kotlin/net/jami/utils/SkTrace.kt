package net.jami.utils

/**
 * shiroikuma fork (2026-11-01, diagnostic): a sink for traces that must survive EMUI dropping this
 * package's release logcat. The app points [sink] at its persistent recovery log and flips [enabled]
 * from Settings → Diagnostics → Conversation loading (off by default). While off, [log] costs one
 * volatile read: the message lambda is never invoked, so no string is built. Never throws.
 */
object SkTrace {
    @Volatile
    var sink: ((String) -> Unit)? = null

    @Volatile
    var enabled: Boolean = false

    inline fun log(msg: () -> String) {
        if (!enabled) return
        val s = sink ?: return
        try { s(msg()) } catch (e: Throwable) { }
    }
}
