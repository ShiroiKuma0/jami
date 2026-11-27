/*
 *  shiroikuma.jami fork — Settings → Diagnostics → “Presence changes” (2026-11-27).
 *
 *  The daemon reports every presence change of a contact through a call (NewBuddyNotification →
 *  Account.presenceUpdate), never through a log line the app could keep: its own “Buddy online”
 *  line is debug level, which EMUI's logcat drops. So when a contact's dot was red while its
 *  messages kept arriving (2026-11-27), nothing on the phone recorded what the daemon had said.
 *
 *  This is the hook the model layer calls; the app installs [sink] while the switch is on
 *  (cx.ring.utils.PresenceLog) and clears it when off, so off costs one null check.
 */
package net.jami.utils

object PresenceTrace {
    /** Receives one finished line (no timestamp). Null = switched off. */
    @Volatile
    var sink: ((String) -> Unit)? = null

    val isOn: Boolean get() = sink != null

    private val NAMES = arrayOf("OFFLINE", "AVAILABLE", "CONNECTED")
    fun stateName(s: Int): String = NAMES.getOrElse(s) { "?$s" }

    /** A presence report for one contact. [source] is “daemon” or “watchdog” (the stuck-message
     *  demotion and its restore are the app's own calls, not the daemon's). */
    fun change(accountId: String, peerUri: String, source: String, reported: Int, shown: Int, was: Int) {
        val s = sink ?: return
        val peer = peerUri.removePrefix("jami:").removePrefix("ring:")
        val held = if (shown != reported) "  (held back: a message to this contact is stuck)" else ""
        s("acct ${accountId.take(6)}  peer $peer  $source=${stateName(reported)}  shown=${stateName(shown)}  was=${stateName(was)}$held")
    }

    fun note(text: String) {
        sink?.invoke(text)
    }
}
