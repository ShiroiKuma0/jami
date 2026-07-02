package cx.ring.automation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cx.ring.utils.ProtectedContactsPrefs
import dagger.hilt.android.AndroidEntryPoint
import net.jami.services.AccountService
import javax.inject.Inject

/**
 * Primary entry point for the companion (白い熊 自由作業盤) to set the protected-contacts list.
 *
 * An explicit broadcast is invisible and is delivered while Jami is backgrounded — unlike a headless
 * activity, which Android's background-activity-start limits can block or flash. The companion sends:
 *   Intent("shiroikuma.jami.action.SET_PROTECTED_CONTACTS").setPackage("shiroikuma.jami")
 *   .putExtra("contacts", "<'|'-separated>").putExtra("mode", "replace"|"add"|"remove")
 *   // optional vague-notification text: .putExtra("protected_title", …).putExtra("protected_body", …)
 * → sendBroadcast(...). Unauthenticated (no token), no UI.
 *
 * GET_PROTECTED_CONTACTS is the read-back channel: an ORDERED broadcast whose result carries the
 * stored set — '|'-separated lowercase entries, or the literal "EMPTY" when the list has no entries
 * (distinguishing "answered, empty" from "nobody answered"). Same local trust model as SET.
 */
@AndroidEntryPoint
class ProtectedContactsReceiver : BroadcastReceiver() {
    @Inject
    lateinit var accountService: AccountService

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AutomationActivity.ACTION_SET_PROTECTED_CONTACTS -> ProtectedContacts.apply(
                context,
                accountService,
                intent.getStringExtra(AutomationActivity.KEY_CONTACTS),
                intent.getStringExtra(AutomationActivity.KEY_MODE),
                intent.getStringExtra(AutomationActivity.KEY_TITLE),
                intent.getStringExtra(AutomationActivity.KEY_BODY)
            )
            AutomationActivity.ACTION_GET_PROTECTED_CONTACTS -> {
                if (!isOrderedBroadcast) return   // a result needs an ordered broadcast
                val set = ProtectedContactsPrefs.getAll(context)
                resultCode = Activity.RESULT_OK
                resultData = if (set.isEmpty()) "EMPTY" else set.sorted().joinToString("|")
            }
        }
    }
}
