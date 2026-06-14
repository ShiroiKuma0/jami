package cx.ring.automation

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import cx.ring.BuildConfig
import cx.ring.client.CallActivity
import cx.ring.client.ConversationActivity
import cx.ring.fragments.CallFragment
import cx.ring.service.DRingService
import cx.ring.utils.AutomationPrefs
import cx.ring.utils.ConversationPath
import dagger.hilt.android.AndroidEntryPoint
import net.jami.model.Uri
import net.jami.services.AccountService
import javax.inject.Inject
import android.net.Uri as AndroidUri

/**
 * Exported, headless entry point for external automation (Tasker, the forked OpenTasker, or `am`).
 *
 * Accepts either an explicit-action intent with string extras, or a `jami-cmd://` VIEW deep-link,
 * and dispatches exactly one operation: send a text message, place an audio/video call, or open a
 * conversation. It shows nothing (translucent theme) and finishes immediately.
 *
 * An Activity — not a Service — is the entry point so a cold-process invocation from another app is
 * not blocked by Android's background-service-start limits. The actual message send is forwarded to
 * [DRingService] (which owns the daemon and keeps the process alive); calls/opens reuse the same
 * intents the in-app UI uses.
 *
 * Every request is gated on [AutomationPrefs]: automation must be enabled in Settings → Automation
 * and the request must carry a `token` matching the stored secret. Unauthorized or malformed
 * requests are dropped (logged + brief toast) and never touch an account.
 *
 * Contract:
 * - Extras: action = [ACTION_SEND_MESSAGE] | [ACTION_PLACE_CALL] | [ACTION_PLACE_VIDEO_CALL] |
 *   [ACTION_OPEN_CONVERSATION]; extras [KEY_ACCOUNT], [KEY_PEER], [KEY_TEXT] (send), [KEY_VIDEO]
 *   (bool, call), [KEY_TOKEN].
 * - Deep link: jami-cmd://send/<account>/<peer>?text=..&token=..,
 *   jami-cmd://call/<account>/<peer>?video=0|1&token=.., jami-cmd://open/<account>/<peer>?token=..
 */
@AndroidEntryPoint
class AutomationActivity : ComponentActivity() {

    @Inject
    lateinit var accountService: AccountService

    private enum class Op { SEND, CALL, VIDEO, OPEN }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Exception) {
            Log.w(TAG, "automation intent failed", e)
        }
        finish()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val deepLink = intent.data?.takeIf { it.scheme == SCHEME }
        val op = resolveOp(intent.action, deepLink)
        if (op == null) {
            Log.w(TAG, "automation: unknown operation (action=${intent.action}, data=${intent.data})")
            return
        }

        // --- authorization gate ---
        val token = deepLink?.getQueryParameter(KEY_TOKEN) ?: intent.getStringExtra(KEY_TOKEN)
        if (!AutomationPrefs.isEnabled(this)) {
            reject("disabled (enable it in Settings → Automation)")
            return
        }
        if (!AutomationPrefs.isAuthorized(this, token)) {
            reject("rejected: bad or missing token")
            return
        }

        // "default" (or empty) resolves to the current account, so single-account callers
        // never need to know the internal account id.
        val rawAccount = (deepLink?.let { pathSeg(it, 0) } ?: intent.getStringExtra(KEY_ACCOUNT))?.trim()
        val account = if (rawAccount.isNullOrEmpty() || rawAccount.equals("default", ignoreCase = true))
            accountService.currentAccount?.accountId else rawAccount
        val peer = (deepLink?.let { pathSeg(it, 1) } ?: intent.getStringExtra(KEY_PEER))?.trim()
        if (account.isNullOrEmpty() || peer.isNullOrEmpty()) {
            reject("missing account or peer (no current account?)")
            return
        }
        val peerUri = Uri.fromString(peer)

        // Guarantee the daemon is up regardless of which op runs (loads accounts on cold start).
        ensureDaemon()

        when (op) {
            Op.SEND -> {
                val text = deepLink?.getQueryParameter(KEY_TEXT) ?: intent.getStringExtra(KEY_TEXT)
                if (text.isNullOrEmpty()) {
                    reject("empty message text")
                    return
                }
                startService(Intent(this, DRingService::class.java)
                    .setAction(DRingService.ACTION_AUTOMATION_SEND)
                    .putExtras(ConversationPath.toBundle(account, peerUri))
                    .putExtra(DRingService.KEY_TEXT_REPLY, text))
            }
            Op.CALL, Op.VIDEO -> {
                val video = op == Op.VIDEO ||
                        deepLink?.getQueryParameter(KEY_VIDEO).let { it == "1" || it == "true" } ||
                        intent.getBooleanExtra(KEY_VIDEO, false)
                startActivity(Intent(Intent.ACTION_CALL)
                    .setClass(this, CallActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtras(ConversationPath.toBundle(account, peerUri))
                    .putExtra(Intent.EXTRA_PHONE_NUMBER, peerUri.uri)
                    .putExtra(CallFragment.KEY_HAS_VIDEO, video))
            }
            Op.OPEN -> {
                startActivity(Intent(Intent.ACTION_VIEW,
                    ConversationPath.toUri(account, peerUri), this, ConversationActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun resolveOp(action: String?, deepLink: AndroidUri?): Op? {
        if (deepLink != null) return when (deepLink.host?.lowercase()) {
            "send" -> Op.SEND
            "call" -> Op.CALL
            "open" -> Op.OPEN
            else -> null
        }
        return when (action) {
            ACTION_SEND_MESSAGE -> Op.SEND
            ACTION_PLACE_CALL -> Op.CALL
            ACTION_PLACE_VIDEO_CALL -> Op.VIDEO
            ACTION_OPEN_CONVERSATION -> Op.OPEN
            else -> null
        }
    }

    /** Android decodes path segments already; just index safely. */
    private fun pathSeg(uri: AndroidUri, i: Int): String? = uri.pathSegments.getOrNull(i)

    private fun ensureDaemon() {
        try {
            startService(Intent(this, DRingService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "could not start daemon service", e)
        }
    }

    private fun reject(reason: String) {
        Log.w(TAG, "automation $reason")
        Toast.makeText(applicationContext, "Jami automation $reason", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val TAG = AutomationActivity::class.java.simpleName

        const val SCHEME = "jami-cmd"

        const val ACTION_SEND_MESSAGE = BuildConfig.APPLICATION_ID + ".action.SEND_MESSAGE"
        const val ACTION_PLACE_CALL = BuildConfig.APPLICATION_ID + ".action.PLACE_CALL"
        const val ACTION_PLACE_VIDEO_CALL = BuildConfig.APPLICATION_ID + ".action.PLACE_VIDEO_CALL"
        const val ACTION_OPEN_CONVERSATION = BuildConfig.APPLICATION_ID + ".action.OPEN_CONVERSATION"

        const val KEY_ACCOUNT = "account"
        const val KEY_PEER = "peer"
        const val KEY_TEXT = "text"
        const val KEY_VIDEO = "video"
        const val KEY_TOKEN = "token"
    }
}
