/*
 *  shiroikuma.jami fork: the target of every home-screen shortcut this app creates.
 *
 *  Why a trampoline instead of pointing the shortcut straight at HomeActivity / CallActivity:
 *
 *  - Lightning Launcher (白い熊's raikidoban fork) repaints, on ACTION_PACKAGE_REPLACED, the icon of
 *    every desktop item whose component equals a MAIN/LAUNCHER activity of the updated package
 *    (MPReceiver.updatePackage). A chat shortcut pointing at HomeActivity therefore lost its badged
 *    avatar and came back as the plain app icon on every app update; the call shortcut, pointing at
 *    the non-launcher CallActivity, survived. This activity is not a launcher activity, so neither
 *    kind is ever matched.
 *  - A legacy launcher item is started with a plain startActivity from the launcher's uid, so the
 *    target must be exported — CallActivity and ConversationActivity are not.
 *
 *  Exported, therefore token-gated: the shortcut carries a token minted by ShortcutPickerActivity
 *  (see [ShortcutPrefs]); anything else is dropped without touching an account.
 */
package cx.ring.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import cx.ring.fragments.CallFragment
import cx.ring.utils.ConversationPath
import cx.ring.utils.ShortcutPrefs

class ShortcutLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            dispatch()
        } catch (e: Exception) {
            Log.w(TAG, "shortcut launch failed", e)
        }
        finish()
    }

    private fun dispatch() {
        val intent = intent ?: return
        if (!ShortcutPrefs.isValid(this, intent.getStringExtra(KEY_TOKEN))) {
            Log.w(TAG, "shortcut rejected: unknown token")
            return
        }
        val path = ConversationPath.fromIntent(intent) ?: return
        if (intent.getBooleanExtra(KEY_CALL, false)) {
            // Same intent ConversationPresenter.goToCall builds.
            val peer = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: path.conversationId
            startActivity(Intent(Intent.ACTION_CALL)
                .setClass(this, CallActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtras(ConversationPath.toBundle(path.accountId, path.conversationId))
                .putExtra(Intent.EXTRA_PHONE_NUMBER, peer)
                .putExtra(CallFragment.KEY_HAS_VIDEO, false)
                // A speaker shortcut differs from a plain call shortcut in this one extra: the
                // call is placed identically, then routed to the loudspeaker as soon as the
                // audio state offers it (CallFragment.applyPendingSpeakerRequest).
                .putExtra(CallFragment.KEY_WANT_SPEAKER,
                    intent.getBooleanExtra(KEY_SPEAKER, false)))
        } else {
            // Same intent every Jami notification uses — HomeActivity switches account, then opens.
            startActivity(Intent(Intent.ACTION_VIEW, path.toUri(), this, HomeActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    companion object {
        private val TAG = ShortcutLaunchActivity::class.java.simpleName
        const val KEY_CALL = "shortcut_call"
        /** Only meaningful together with [KEY_CALL]: place the call on the loudspeaker. */
        const val KEY_SPEAKER = "shortcut_speaker"
        const val KEY_TOKEN = "shortcut_token"
    }
}
