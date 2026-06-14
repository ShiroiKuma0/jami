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

    private val yellow = 0xFFFFFF00.toInt()
    private val grey = 0xFFAAAAAA.toInt()
    private val black = 0xFF000000.toInt()
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

        root.addView(header("Automation"))
        root.addView(body(
            "Let external automation — Tasker, OpenTasker, or the `am` shell — send messages and " +
            "place calls through this app. Every request must carry the secret token below. Turn " +
            "this off to block all automation."))

        // Enable switch
        val switchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20f), 0, dp(8f))
        }
        switchRow.addView(TextView(ctx).apply {
            text = "Enable automation"
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        switchRow.addView(MaterialSwitch(ctx).apply {
            isChecked = AutomationPrefs.isEnabled(ctx)
            setOnCheckedChangeListener { _, checked -> AutomationPrefs.setEnabled(ctx, checked) }
        })
        root.addView(switchRow)

        // Token
        root.addView(miniLabel("Secret token"))
        tokenView = TextView(ctx).apply {
            text = AutomationPrefs.getToken(ctx)
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(Typeface.MONOSPACE)
            setTextIsSelectable(true)
            setPadding(0, dp(4f), 0, dp(10f))
        }
        root.addView(tokenView)

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(ctx).apply {
            text = "Copy"
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Jami automation token", AutomationPrefs.getToken(ctx)))
                Toast.makeText(ctx, "Token copied", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Button(ctx).apply {
            text = "Regenerate"
            setOnClickListener {
                tokenView?.text = AutomationPrefs.regenerateToken(ctx)
                Toast.makeText(ctx, "Token regenerated — update your scripts", Toast.LENGTH_LONG).show()
            }
        })
        root.addView(buttons)

        // Accounts — the otherwise-hidden <account> id; or pass "default" for the current one.
        root.addView(miniLabel("Your accounts (use the id as <account>, or \"default\")"))
        val accounts: List<Account> = try {
            accountService.observableAccountList
                .timeout(500, TimeUnit.MILLISECONDS)
                .blockingFirst(emptyList())
        } catch (e: Exception) {
            listOfNotNull(accountService.currentAccount)
        }
        if (accounts.isEmpty()) {
            root.addView(body("No accounts found — open the app's account list once, then return here."))
        } else {
            for (a in accounts) root.addView(accountRow(a))
        }

        // Usage help
        root.addView(miniLabel("How to call it"))
        root.addView(mono(
            "Deep link (any URL opener / am):\n" +
            "  am start -a android.intent.action.VIEW \\\n" +
            "    -d 'jami-cmd://send/<account>/<peer>?text=hi&token=<token>'\n" +
            "  jami-cmd://call/<account>/<peer>?video=0&token=<token>\n" +
            "  jami-cmd://open/<account>/<peer>?token=<token>\n" +
            "\n" +
            "Explicit intent (Tasker / OpenTasker, extras):\n" +
            "  package    shiroikuma.jami\n" +
            "  class      cx.ring.automation.AutomationActivity\n" +
            "  action     shiroikuma.jami.action.SEND_MESSAGE\n" +
            "             …PLACE_CALL / …PLACE_VIDEO_CALL / …OPEN_CONVERSATION\n" +
            "  extras     account, peer, text, video (bool), token\n" +
            "\n" +
            "<account> may be \"default\" for the current account. <peer> is a swarm:<id>, " +
            "jami:<40-hex>, or sip: URI. URL-encode <peer> and the message text in the deep link."))

        return scroll
    }

    private fun accountRow(a: Account): View {
        val ctx = requireContext()
        val name = a.displayUsername?.takeIf { it.isNotEmpty() }
            ?: a.registeredName.takeIf { it.isNotEmpty() }
            ?: a.alias?.takeIf { it.isNotEmpty() }
            ?: "(Jami account)"
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
