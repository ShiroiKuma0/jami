package cx.ring.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cx.ring.R
import cx.ring.utils.Flash
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import cx.ring.utils.AutomationPrefs
import dagger.hilt.android.AndroidEntryPoint
import net.jami.model.Account
import net.jami.services.AccountService
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * "Automation" — master on/off plus the shared secret that external automation must present.
 * Built programmatically in the fork's yellow-on-black style, like [FontsSettingsFragment].
 */
@AndroidEntryPoint
class AutomationSettingsFragment : Fragment() {
    @Inject
    lateinit var accountService: AccountService

    private val yellow: Int get() = cx.ring.sk.skColor(cx.ring.sk.SkRole.ACCENT)
    private val grey: Int get() = cx.ring.sk.skColor(cx.ring.sk.SkRole.TEXT_SECONDARY)
    private val black: Int get() = cx.ring.sk.skColor(cx.ring.sk.SkRole.BACKGROUND)
    private var tokenView: TextView? = null

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(black) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(16f), dp(24f), dp(24f))
        }
        scroll.addView(root)

        root.addView(header(getString(R.string.sk_misc_automation_title)))
        root.addView(body(getString(R.string.sk_misc_automation_intro)))

        // Enable switch
        val switchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20f), 0, dp(8f))
        }
        switchRow.addView(TextView(ctx).apply {
            text = getString(R.string.sk_misc_automation_enable)
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        switchRow.addView(MaterialSwitch(ctx).apply {
            isChecked = AutomationPrefs.isEnabled(ctx)
            setOnCheckedChangeListener { _, checked -> AutomationPrefs.setEnabled(ctx, checked) }
        })
        root.addView(switchRow)

        // Row 2 — the token requirement (contract v2, default OFF).
        val requireRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(8f))
        }
        requireRow.addView(TextView(ctx).apply {
            text = getString(R.string.sk_misc_automation_use_token)
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // The token block, hidden while nothing is asking for it: a 48-character secret under an
        // off switch invites 白い熊 to paste it somewhere it will do nothing.
        val tokenBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (AutomationPrefs.isTokenRequired(ctx)) View.VISIBLE else View.GONE
        }
        requireRow.addView(MaterialSwitch(ctx).apply {
            isChecked = AutomationPrefs.isTokenRequired(ctx)
            setOnCheckedChangeListener { _, checked ->
                AutomationPrefs.setTokenRequired(ctx, checked)
                tokenBox.visibility = if (checked) View.VISIBLE else View.GONE
            }
        })
        root.addView(requireRow)
        root.addView(tokenBox)

        // Token
        tokenBox.addView(miniLabel(getString(R.string.sk_misc_automation_secret_token)))
        tokenView = TextView(ctx).apply {
            text = AutomationPrefs.getToken(ctx)
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(Typeface.MONOSPACE)
            setTextIsSelectable(true)
            setPadding(0, dp(4f), 0, dp(10f))
        }
        tokenBox.addView(tokenView)

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(ctx).apply {
            text = getString(R.string.menu_message_copy)
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.sk_misc_automation_clip_label), AutomationPrefs.getToken(ctx)))
                Flash.show(ctx, getString(R.string.sk_misc_automation_token_copied), Toast.LENGTH_SHORT)
            }
        })
        buttons.addView(Button(ctx).apply {
            text = getString(R.string.sk_misc_automation_regenerate)
            setOnClickListener {
                tokenView?.text = AutomationPrefs.regenerateToken(ctx)
                Flash.show(ctx, getString(R.string.sk_misc_automation_token_regenerated), Toast.LENGTH_LONG)
            }
        })
        tokenBox.addView(buttons)

        // Accounts — the otherwise-hidden <account> id; or pass "default" for the current one.
        root.addView(miniLabel(getString(R.string.sk_misc_automation_accounts)))
        val accounts: List<Account> = try {
            accountService.observableAccountList
                .timeout(500, TimeUnit.MILLISECONDS)
                .blockingFirst(emptyList())
        } catch (e: Exception) {
            listOfNotNull(accountService.currentAccount)
        }
        if (accounts.isEmpty()) {
            root.addView(body(getString(R.string.sk_misc_automation_no_accounts)))
        } else {
            for (a in accounts) root.addView(accountRow(a))
        }

        // Usage help
        root.addView(miniLabel(getString(R.string.sk_misc_automation_howto)))
        // The command lines are machine syntax and stay as they are; only the prose is translated.
        root.addView(mono(
            getString(R.string.sk_misc_automation_help_deeplink) + "\n" +
            "  am start -a android.intent.action.VIEW \\\n" +
            "    -d 'jami-cmd://send/<account>/<peer>?text=hi&token=<token>'\n" +
            "  jami-cmd://call/<account>/<peer>?video=0&token=<token>\n" +
            "  jami-cmd://open/<account>/<peer>?token=<token>\n" +
            "\n" +
            getString(R.string.sk_misc_automation_help_intent) + "\n" +
            "  package    shiroikuma.jami\n" +
            "  class      cx.ring.automation.AutomationActivity\n" +
            "  action     shiroikuma.jami.action.SEND_MESSAGE\n" +
            "             …PLACE_CALL / …PLACE_VIDEO_CALL / …OPEN_CONVERSATION\n" +
            "  extras     account, peer, text, video (bool), token\n" +
            "\n" +
            getString(R.string.sk_misc_automation_help_note)))

        return scroll
    }

    private fun accountRow(a: Account): View {
        val ctx = requireContext()
        val name = a.displayUsername?.takeIf { it.isNotEmpty() }
            ?: a.registeredName.takeIf { it.isNotEmpty() }
            ?: a.alias?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.sk_misc_automation_unnamed_account)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))
            addView(TextView(ctx).apply {
                text = a.accountId
                setTextColor(yellow)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(Typeface.MONOSPACE)
                setTextIsSelectable(true)
            })
            addView(TextView(ctx).apply {
                text = name
                setTextColor(grey)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        }
    }

    private fun header(t: String) = TextView(requireContext()).apply {
        text = t
        setTextColor(yellow)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8f))
    }

    private fun body(t: String) = TextView(requireContext()).apply {
        text = t
        setTextColor(grey)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private fun miniLabel(t: String) = TextView(requireContext()).apply {
        text = t
        setTextColor(grey)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(18f), 0, dp(4f))
    }

    private fun mono(t: String) = TextView(requireContext()).apply {
        text = t
        setTextColor(yellow)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(Typeface.MONOSPACE)
        setTextIsSelectable(true)
    }
}
