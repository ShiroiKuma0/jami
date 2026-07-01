package cx.ring.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 */
@AndroidEntryPoint
class ProtectedContactsReceiver : BroadcastReceiver() {
    @Inject
    lateinit var accountService: AccountService

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutomationActivity.ACTION_SET_PROTECTED_CONTACTS) return
        ProtectedContacts.apply(
            context,
            accountService,
            intent.getStringExtra(AutomationActivity.KEY_CONTACTS),
            intent.getStringExtra(AutomationActivity.KEY_MODE),
            intent.getStringExtra(AutomationActivity.KEY_TITLE),
            intent.getStringExtra(AutomationActivity.KEY_BODY)
        )
    }
}
