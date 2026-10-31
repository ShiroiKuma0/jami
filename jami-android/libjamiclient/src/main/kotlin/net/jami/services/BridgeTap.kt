package net.jami.services

/**
 * shiroikuma fork: a read-only tap on the daemon's raw conversation callbacks, for the SMS-over-Jami
 * bridge (automation contract, `bridge_*` methods of the app's AutomationProvider, 2026-10-31).
 *
 * The callbacks are delivered exactly as the daemon sent them, BEFORE the client model consumes
 * them, so the bridge sees the authenticated author URI, the commit id, the reply-to parent and the
 * file id without depending on how the UI model happens to represent a message.
 *
 * Contract for implementors: these run on the daemon's callback thread. Copy what you need and
 * return immediately — never block, never call back into the daemon from here. Every call site
 * swallows exceptions, so a broken tap can never take down message delivery.
 */
interface BridgeTap {
    /** A new swarm message (`updated = false`) or an update of an existing one, e.g. an edit. */
    fun onMessage(accountId: String, conversationId: String, id: String, type: String,
                  body: Map<String, String>, editions: Int, updated: Boolean)

    fun onReaction(accountId: String, conversationId: String, messageId: String, reaction: Map<String, String>)

    fun onReactionRemoved(accountId: String, conversationId: String, messageId: String, reactionId: String)

    /** A data-transfer status change; [eventCode] is the daemon's raw `DataTransferEventCode`. */
    fun onTransfer(accountId: String, conversationId: String, interactionId: String, fileId: String, eventCode: Int)
}

object BridgeTaps {
    @Volatile
    var tap: BridgeTap? = null

    internal inline fun fire(block: (BridgeTap) -> Unit) {
        val t = tap ?: return
        try {
            block(t)
        } catch (e: Throwable) {
            // Never let the bridge break the daemon callback path.
        }
    }
}
