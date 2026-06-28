/*
 *  shiroikuma.jami fork — live per-contact (and per-group) connection monitor dialog.
 *
 *  Jami has no per-contact "connect" API and a typing nudge doesn't force ICE, so "Message ping" sends a
 *  small probe message (⌁) — only real content makes the daemon open a direct channel. This dialog shows
 *  the contact/group avatar + resolved name, then EACH member's live channels colour-coded (connecting /
 *  negotiating (ICE) / securing (TLS) / connected) with device IDs — the monitor's info, focused here.
 *
 *  Group-safe: it iterates conversation.contacts (non-user) and never touches conversation.contact, which
 *  throws check(size<=2) for group swarms. Buttons: Message ping (probe) · Recover ⚡ (smart account
 *  recover) · Close. After a ping, if nothing reaches Connected within the window, it gives a definitive
 *  "couldn't connect — offline or unreachable" verdict rather than silently sitting on "reachable".
 */
package cx.ring.fragments

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import cx.ring.R
import cx.ring.utils.ConnectionWatchdog
import cx.ring.utils.DeviceUtils
import cx.ring.utils.DialogTheme
import cx.ring.utils.Flash
import cx.ring.views.AvatarDrawable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import net.jami.model.Contact
import net.jami.model.ContactViewModel
import net.jami.model.Conversation
import net.jami.model.Uri
import net.jami.services.AccountService
import net.jami.services.AccountService.ConnectionStatus
import net.jami.services.ConversationFacade

private const val C_CONNECTED = 0xFFFFFF00.toInt()   // yellow — a live, usable channel
private const val C_ATTEMPT = 0xFF0000FF.toInt()     // blue — connecting / negotiating / reachable
private const val C_NONE = 0xFFFF5252.toInt()        // red — offline / unreachable
private const val C_DIM = 0xFF9E9E9E.toInt()         // grey — IDs / detail
private const val PING_VERDICT_MS = 12_000L          // after a ping, conclude offline/unreachable if no connect by here

private fun progress(s: ConnectionStatus) = when (s) {
    ConnectionStatus.Connected -> 4
    ConnectionStatus.TLS -> 3
    ConnectionStatus.ICE -> 2
    ConnectionStatus.Connecting -> 1
    ConnectionStatus.Waiting -> 0
}

fun showContactConnectionDialog(
    ctx: Context,
    accountService: AccountService,
    conversationFacade: ConversationFacade,
    conversation: Conversation,
) {
    val accountId = conversation.accountId
    val disposable = CompositeDisposable()
    val handler = Handler(Looper.getMainLooper())
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val avatar = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) }
    }
    val nameTv = TextView(ctx).apply {
        setTextColor(C_CONNECTED)
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        text = "…"
    }
    val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(avatar); addView(nameTv)
    }
    val titleBox = TextView(ctx).apply {
        text = "Contact live monitor"
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(C_CONNECTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 10 * d
            setStroke((2 * d).toInt(), C_CONNECTED)
            setColor(0xFF000000.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) }
    }
    val summaryTv = TextView(ctx).apply {
        setTextColor(C_DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(16), 0, dp(6))
        text = "○ checking channels…"
    }
    val diagTv = TextView(ctx).apply {
        setTextColor(C_DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, 0, 0, dp(6))
        visibility = View.GONE
    }
    val membersContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(8))
        addView(titleBox); addView(header); addView(summaryTv); addView(diagTv); addView(membersContainer)
    }
    val scroll = ScrollView(ctx).apply { addView(root) }

    val dialog = DialogTheme.builder(ctx)
        .setView(scroll)
        .setNegativeButton("Message ping ⌁", null)
        .setNeutralButton("Recover ⚡", null)
        .setPositiveButton("Close", null)
        .create()
    dialog.setOnDismissListener { disposable.clear(); handler.removeCallbacksAndMessages(null) }

    var memberVms: List<ContactViewModel> = emptyList()
    var lastAccounts: List<AccountService.AccountConnections> = emptyList()
    var pingAtMs = 0L

    fun connsFor(raw: String?): List<AccountService.DeviceConnection> {
        if (raw.isNullOrEmpty()) return emptyList()
        return lastAccounts.firstOrNull { it.accountId == accountId }?.peers
            ?.filter { Uri.fromString(it.first).rawRingId == raw }?.flatMap { it.second }.orEmpty()
    }

    fun addChannelRow(c: AccountService.DeviceConnection) {
        val color = if (c.status == ConnectionStatus.Connected) C_CONNECTED else C_ATTEMPT
        val statusText = when (c.status) {
            ConnectionStatus.Waiting -> "waiting…"
            ConnectionStatus.Connecting -> "connecting…"
            ConnectionStatus.ICE -> "negotiating (ICE)…"
            ConnectionStatus.TLS -> "securing (TLS)…"
            ConnectionStatus.Connected -> "connected"
        }
        val iconRes = when (c.status) {
            ConnectionStatus.ICE -> R.drawable.p2p_24
            ConnectionStatus.TLS, ConnectionStatus.Connected -> R.drawable.baseline_private_connectivity_24
            else -> R.drawable.baseline_radar_24
        }
        val dev = if (c.device.length > 18) c.device.take(10) + "…" + c.device.takeLast(6) else c.device
        val detail = buildString {
            append(dev)
            if (!c.remoteAddress.isNullOrEmpty()) append("  ·  ").append(c.remoteAddress)
            append("  ·  ").append(c.channels.size).append(" ch")
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = statusText; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) })
            addView(TextView(ctx).apply { text = detail; setTextColor(C_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) })
        }
        membersContainer.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), 0, dp(2))
            addView(ImageView(ctx).apply {
                setImageResource(iconRes); setColorFilter(color)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) }
            })
            addView(texts)
            setOnClickListener {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Device ID", c.device))
                Flash.show(ctx, "Device ID copied")
            }
        })
    }

    fun render() {
        membersContainer.removeAllViews()
        val isGroup = memberVms.size > 1
        var anyConnected = false; var anyAttempt = false; var anyOnline = false; var totalCh = 0; var maxP = -1

        for (vm in memberVms) {
            val conns = connsFor(vm.contact.uri.rawRingId)
            val mConnected = conns.any { it.status == ConnectionStatus.Connected }
            val mAttempt = conns.any { it.status != ConnectionStatus.Connected }
            val mOnline = vm.presence != Contact.PresenceStatus.OFFLINE
            if (mConnected) anyConnected = true
            if (mAttempt) anyAttempt = true
            if (mOnline) anyOnline = true
            totalCh += conns.size
            conns.forEach { maxP = maxOf(maxP, progress(it.status)) }

            if (isGroup) {
                val mColor = if (mConnected) C_CONNECTED else if (mAttempt || mOnline) C_ATTEMPT else C_NONE
                membersContainer.addView(TextView(ctx).apply {
                    text = "● ${vm.displayName}"
                    setTextColor(mColor); setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(12), 0, dp(2))
                })
                if (conns.isEmpty()) membersContainer.addView(TextView(ctx).apply {
                    text = if (mOnline) "   reachable — no open channel" else "   offline"
                    setTextColor(C_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(dp(4), 0, 0, dp(2))
                })
            }
            conns.forEach { addChannelRow(it) }
        }

        summaryTv.text = when {
            anyConnected -> "● connected · $totalCh channel(s)"
            anyAttempt -> "● connecting · $totalCh channel(s)"
            anyOnline -> "● reachable — online, no open channel (opens when you message)"
            memberVms.isEmpty() -> "○ checking channels…"
            else -> "○ offline — no presence, no channel"
        }
        summaryTv.setTextColor(if (anyConnected) C_CONNECTED else if (anyAttempt || anyOnline) C_ATTEMPT else C_NONE)

        val pingAge = if (pingAtMs != 0L) System.currentTimeMillis() - pingAtMs else 0L
        val diag = when {
            anyConnected -> ""
            pingAtMs != 0L && pingAge > PING_VERDICT_MS ->
                "✗ No response to the ping — offline or unreachable. Messages won't deliver until they're back. (Recover ⚡ if you suspect it's your link.)"
            maxP >= 2 -> "ⓘ Reached ICE. If it doesn't complete, it's a NAT/relay path (they appear online) or they're going offline — Recover ⚡ may help."
            maxP >= 0 -> "ⓘ Still connecting. If it never reaches ICE, the peer likely isn't answering."
            anyOnline -> "ⓘ Online on the DHT but no channel yet — tap Message ping ⌁ to open one."
            else -> ""
        }
        diagTv.text = diag
        diagTv.visibility = if (diag.isEmpty()) View.GONE else View.VISIBLE
    }

    disposable.add(conversationFacade.observeConversation(conversation, true)
        .observeOn(DeviceUtils.uiScheduler)
        .subscribe({ cvm ->
            nameTv.text = cvm.title
            avatar.setImageDrawable(
                AvatarDrawable.Builder().withViewModel(cvm).withCircleCrop(true).withPresence(false).build(ctx))
            memberVms = cvm.contacts.filter { !it.contact.isUser }
            render()
        }, {}))

    disposable.add(accountService.monitorAllConnections(true)
        .observeOn(DeviceUtils.uiScheduler)
        .subscribe({ accounts -> lastAccounts = accounts; render() }, {}))

    dialog.show()
    DialogTheme.theme(dialog, ctx)
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
        pingAtMs = System.currentTimeMillis()
        accountService.openConnectionTo(accountId, conversation)
        Flash.show(ctx, "Sent a message ping (⌁) — watching…")
        handler.postDelayed({ render() }, PING_VERDICT_MS + 500L)
    }
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
        ConnectionWatchdog.manualRecover(ctx, accountService)
        Flash.show(ctx, "Recovering…")
    }
}
