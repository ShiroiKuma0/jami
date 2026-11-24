/*
 *  shiroikuma.jami fork — live per-contact (and per-group) connection monitor dialog.
 *
 *  Jami has no per-contact "connect" API and a typing nudge doesn't force ICE, so "Message ping" sends a
 *  small probe message (⌁) — only real content makes the daemon open a direct channel. This dialog shows
 *  the contact/group avatar + resolved name, then EACH member's live channels colour-coded (connecting /
 *  negotiating (ICE) / securing (TLS) / connected) with device IDs — the monitor's info, focused here.
 *
 *  Group-safe: it iterates conversation.contacts (non-user) and never touches conversation.contact, which
 *  throws check(size<=2) for group swarms. Buttons: Message ping ⌁ (probe) · Close — there is no
 *  per-dialog "Recover", because recovery is account-wide (the ⚡ lightning); when a message is already
 *  pending the ping routes you there instead of blindly re-sending. A live footer notes the view
 *  self-refreshes (presence pushed, channels re-polled every 2 s). The verdict reads the ⌁ ping's
 *  DELIVERY RECEIPT (the ground truth), not the channel: delivering… → delivered ✓, softening to "may be
 *  offline" only after a long wait — never a premature "unreachable".
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
import cx.ring.utils.DeviceUtils
import cx.ring.utils.DialogTheme
import cx.ring.utils.Flash
import cx.ring.views.AvatarDrawable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import net.jami.model.Contact
import net.jami.model.ContactViewModel
import net.jami.model.Conversation
import net.jami.model.Uri
import net.jami.model.interaction.Interaction
import net.jami.services.AccountService
import net.jami.services.AccountService.ConnectionStatus
import net.jami.services.ConversationFacade
import net.jami.utils.PeerReachability

private val C_CONNECTED: Int get() = cx.ring.sk.skColor(cx.ring.sk.SkRole.ACCENT)   // yellow — a live, usable channel
private val C_ATTEMPT: Int get() = cx.ring.sk.skPref(cx.ring.utils.ColorPrefs.STATUS_CONNECTING)   // blue — connecting / negotiating / reachable
private val C_NONE: Int get() = cx.ring.sk.skColor(cx.ring.sk.SkRole.DANGER)   // red — offline / unreachable
private val C_DIM: Int get() = cx.ring.sk.skColor(cx.ring.sk.SkRole.TEXT_SECONDARY)   // grey — IDs / detail
private const val SOFT_PENDING_MS = 60_000L          // after a ping, if still no receipt by here, SOFTEN to "may be offline" — never a hard verdict
private const val SNAP_TTL_MS = 90_000L              // how long a cached channel snapshot is worth re-showing after exit→reopen
// Per-conversation snapshot of the last NON-EMPTY channel set, so exit→reopen doesn't lose the in-flight picture.
private val snapCache = HashMap<String, Pair<List<AccountService.AccountConnections>, Long>>()

private fun progress(s: ConnectionStatus) = when (s) {
    ConnectionStatus.Connected -> 4
    ConnectionStatus.TLS -> 3
    ConnectionStatus.ICE -> 2
    ConnectionStatus.Connecting -> 1
    ConnectionStatus.Waiting -> 0
}

/** Is the last outgoing message still WITHOUT a delivery receipt? The ground-truth reachability signal —
 *  the ping's receipt, not the (ambiguous) presence/absence of a channel. An incoming last event means
 *  the peer is active, i.e. reachable. */
private fun lastOutgoingUndelivered(e: Interaction?): Boolean {
    e ?: return false
    if (e.isIncoming) return false
    if (e.type != Interaction.InteractionType.TEXT && e.type != Interaction.InteractionType.DATA_TRANSFER) return false
    val delivered = e.status == Interaction.InteractionStatus.SUCCESS ||
        e.status == Interaction.InteractionStatus.DISPLAYED ||
        e.statusMap.values.any { it == Interaction.MessageStates.SUCCESS || it == Interaction.MessageStates.DISPLAYED }
    return !delivered
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
    val titleBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 10 * d
            setStroke((2 * d).toInt(), C_CONNECTED)
            setColor(cx.ring.sk.skColor(cx.ring.sk.SkRole.BACKGROUND))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) }
        // Big title (weighted, so the note never clips) + the small live note beside it on two lines.
        addView(TextView(ctx).apply {
            text = ctx.getString(R.string.sk_conn_live_title)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C_CONNECTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(ctx).apply {
            text = android.text.SpannableString(ctx.getString(R.string.sk_conn_live_note)).apply {
                setSpan(android.text.style.ForegroundColorSpan(C_CONNECTED), 0, 1, 0)   // dot — yellow
                setSpan(android.text.style.ForegroundColorSpan(C_DIM), 1, length, 0)     // caption — grey
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        })
    }
    val summaryTv = TextView(ctx).apply {
        setTextColor(C_DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(16), 0, dp(6))
        text = ctx.getString(R.string.sk_conn_live_checking)
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
        .setNeutralButton(R.string.sk_conn_live_ping_button, null)   // neutral = far-left edge, away from Close
        .setPositiveButton(R.string.conn_dash_close, null)
        .create()
    dialog.setOnDismissListener { disposable.clear(); handler.removeCallbacksAndMessages(null) }

    var memberVms: List<ContactViewModel> = emptyList()
    var lastAccounts: List<AccountService.AccountConnections> = emptyList()
    var pingAtMs = 0L
    var lastUndelivered = false
    // Snapshot of the last attempt's channels, preserved across exit→reopen (file-level snapCache).
    val convKey = conversation.uri.uri
    var cachedAccounts: List<AccountService.AccountConnections>? = null
    var cacheTs = 0L
    snapCache[convKey]?.let { if (System.currentTimeMillis() - it.second < SNAP_TTL_MS) { cachedAccounts = it.first; cacheTs = it.second } }

    fun connsFor(accounts: List<AccountService.AccountConnections>, raw: String?): List<AccountService.DeviceConnection> {
        if (raw.isNullOrEmpty()) return emptyList()
        return accounts.firstOrNull { it.accountId == accountId }?.peers
            ?.filter { Uri.fromString(it.first).rawRingId == raw }?.flatMap { it.second }.orEmpty()
    }

    fun addChannelRow(c: AccountService.DeviceConnection) {
        val color = if (c.status == ConnectionStatus.Connected) C_CONNECTED else C_ATTEMPT
        val statusText = ctx.getString(when (c.status) {
            ConnectionStatus.Waiting -> R.string.conn_state_waiting
            ConnectionStatus.Connecting -> R.string.conn_state_connecting
            ConnectionStatus.ICE -> R.string.conn_state_ice
            ConnectionStatus.TLS -> R.string.conn_state_tls
            ConnectionStatus.Connected -> R.string.conn_state_connected
        })
        val iconRes = when (c.status) {
            ConnectionStatus.ICE -> R.drawable.p2p_24
            ConnectionStatus.TLS, ConnectionStatus.Connected -> R.drawable.baseline_private_connectivity_24
            else -> R.drawable.baseline_radar_24
        }
        val up = if (c.connectionTime > 0L) {
            val s = (System.currentTimeMillis() - c.connectionTime) / 1000L
            if (s in 0..2_592_000L) "  ·  " + ctx.getString(R.string.sk_conn_live_up,
                if (s < 60) ctx.getString(R.string.sk_conn_dur_s, s.toInt())
                else if (s < 3600) ctx.getString(R.string.sk_conn_dur_m, (s / 60).toInt())
                else ctx.getString(R.string.sk_conn_dur_hm, (s / 3600).toInt(), ((s % 3600) / 60).toInt())) else ""
        } else ""
        val dev = if (c.device.length > 18) c.device.take(10) + "…" + c.device.takeLast(6) else c.device
        val chans = c.channels.joinToString(", ").let { if (it.length > 36) it.take(34) + "…" else it }
        val detail = buildString {
            append(dev)
            if (!c.remoteAddress.isNullOrEmpty()) append("  ·  ").append(c.remoteAddress)
            append("  ·  ").append(ctx.getString(R.string.conn_channels, c.channels.size))
            if (chans.isNotEmpty()) append(" (").append(chans).append(")")
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = statusText + up; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) })
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
                    .setPrimaryClip(android.content.ClipData.newPlainText(ctx.getString(R.string.sk_conn_device_id), c.device))
                Flash.show(ctx, ctx.getString(R.string.sk_conn_copied, ctx.getString(R.string.sk_conn_device_id)))
            }
        })
    }

    fun render() {
        membersContainer.removeAllViews()
        val isGroup = memberVms.size > 1
        var anyConnected = false; var anyAttempt = false; var anyOnline = false; var totalCh = 0; var maxP = -1
        var anyUnreachable = false   // verified: msg stuck + no live channel (PeerReachability)

        // LIVE channels drive the summary. If live is empty but we still have a recent snapshot of the last
        // attempt (e.g. you exited and re-opened), re-show that snapshot so the picture isn't lost.
        val liveTotal = memberVms.sumOf { connsFor(lastAccounts, it.contact.uri.rawRingId).size }
        if (liveTotal > 0) { cachedAccounts = lastAccounts; cacheTs = System.currentTimeMillis(); snapCache[convKey] = lastAccounts to cacheTs }
        val useCache = liveTotal == 0 && cachedAccounts != null && (System.currentTimeMillis() - cacheTs) < SNAP_TTL_MS
        val src = if (useCache) cachedAccounts!! else lastAccounts

        if (useCache) {
            val ageS = ((System.currentTimeMillis() - cacheTs) / 1000L).toInt()
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(cacheTs))
            membersContainer.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.sk_conn_live_last_attempt, ageS, stamp)
                setTextColor(C_ATTEMPT); setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); setPadding(dp(4), dp(8), 0, dp(2))
            })
            membersContainer.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.sk_conn_live_snapshot_note)
                setTextColor(C_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(dp(4), 0, 0, dp(6))
            })
        }

        for (vm in memberVms) {
            val raw = vm.contact.uri.rawRingId
            val liveConns = connsFor(lastAccounts, raw)
            val mOnline = vm.presence != Contact.PresenceStatus.OFFLINE
            val mUnreachable = raw != null && PeerReachability.isUnreachable(accountId, raw)
            if (mUnreachable) anyUnreachable = true
            if (liveConns.any { it.status == ConnectionStatus.Connected }) anyConnected = true
            if (liveConns.any { it.status != ConnectionStatus.Connected }) anyAttempt = true
            if (mOnline) anyOnline = true
            totalCh += liveConns.size
            liveConns.forEach { maxP = maxOf(maxP, progress(it.status)) }

            val rowConns = connsFor(src, raw)   // cached snapshot when useCache, else live
            if (isGroup) {
                val mConnected = rowConns.any { it.status == ConnectionStatus.Connected }
                val mAttempt = rowConns.any { it.status != ConnectionStatus.Connected }
                val mColor = if (mConnected) C_CONNECTED else if (mAttempt || mOnline) C_ATTEMPT else C_NONE
                membersContainer.addView(TextView(ctx).apply {
                    text = "● ${vm.displayName}"
                    setTextColor(mColor); setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(12), 0, dp(2))
                })
            }
            // No channel to show (live empty + no snapshot) → say what's happening, never a blank.
            if (rowConns.isEmpty()) membersContainer.addView(TextView(ctx).apply {
                text = "   " + when {
                    mUnreachable -> ctx.getString(R.string.sk_conn_live_row_unreachable)
                    pingAtMs != 0L && lastUndelivered -> ctx.getString(R.string.sk_conn_live_row_queued)
                    mOnline -> ctx.getString(R.string.sk_conn_live_row_reachable)
                    else -> ctx.getString(R.string.sk_conn_live_row_offline)
                }
                setTextColor(C_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(dp(4), if (isGroup) 0 else dp(6), 0, dp(2))
            })
            rowConns.forEach { addChannelRow(it) }
        }

        // Verdict from the GROUND TRUTH — the ⌁ ping's delivery receipt — never the (ambiguous)
        // presence/absence of a channel, and never a premature timeout. Delivery can legitimately take a
        // while; we wait for the receipt and only SOFTEN after a long wait. We never declare "dead".
        val pinged = pingAtMs != 0L
        val pingDelivered = pinged && !lastUndelivered
        val pingPending = pinged && lastUndelivered
        val pendingLong = pingPending && (System.currentTimeMillis() - pingAtMs) > SOFT_PENDING_MS
        summaryTv.text = when {
            pingDelivered -> ctx.getString(R.string.sk_conn_live_sum_delivered)
            anyConnected -> ctx.getString(R.string.sk_conn_live_sum_connected, totalCh)
            anyAttempt -> ctx.getString(R.string.sk_conn_live_sum_connecting, totalCh)
            anyOnline -> ctx.getString(R.string.sk_conn_live_sum_reachable)
            memberVms.isEmpty() -> ctx.getString(R.string.sk_conn_live_checking)
            anyUnreachable -> ctx.getString(R.string.sk_conn_live_sum_unreachable)
            else -> ctx.getString(R.string.sk_conn_live_sum_offline)
        }
        summaryTv.setTextColor(when {
            pingDelivered || anyConnected -> C_CONNECTED
            anyAttempt || anyOnline -> C_ATTEMPT
            else -> C_NONE
        })

        val diag = when {
            pingDelivered -> ""
            anyUnreachable -> ctx.getString(R.string.sk_conn_live_diag_unreachable)
            pendingLong -> ctx.getString(R.string.sk_conn_live_diag_pending_long)
            pingPending -> ctx.getString(R.string.sk_conn_live_diag_pending)
            anyConnected && lastUndelivered -> ctx.getString(R.string.sk_conn_live_diag_stall)
            anyConnected -> ""
            maxP >= 2 -> ctx.getString(R.string.sk_conn_live_diag_ice)
            maxP >= 0 -> ctx.getString(R.string.sk_conn_live_diag_connecting)
            anyOnline -> ctx.getString(R.string.sk_conn_live_diag_online)
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

    // The ground-truth signal: the conversation's last-event delivery (the ping's receipt).
    disposable.add(conversation.currentStateObservable
        .observeOn(DeviceUtils.uiScheduler)
        .subscribe({ lastUndelivered = lastOutgoingUndelivered(it.first); render() }, {}))

    fun doPing() {
        pingAtMs = System.currentTimeMillis()
        lastUndelivered = true   // the ⌁ is pending now — show "delivering", not a stale "delivered"
        accountService.openConnectionTo(accountId, conversation)
        render()
        Flash.show(ctx, ctx.getString(R.string.sk_conn_live_ping_sent))
        handler.postDelayed({ render() }, SOFT_PENDING_MS + 500L)
    }

    // A message is already out and undelivered: re-pinging won't help — the daemon already retries on its
    // own. Tell the truth and route to the account-wide ⚡ lightning, spelling out that it resets ALL chats.
    fun showPendingPrompt() {
        val name = nameTv.text
        val whenStr = if (pingAtMs != 0L)
            ctx.getString(R.string.sk_conn_live_pending_when,
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(pingAtMs))) else ""
        val body = TextView(ctx).apply {
            setTextColor(C_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(22), dp(18), dp(22), dp(8))
            text = ctx.getString(R.string.sk_conn_live_pending_body, name, whenStr)
        }
        DialogTheme.builder(ctx)
            .setTitle(R.string.sk_conn_live_pending_title)
            .setView(ScrollView(ctx).apply { addView(body) })
            .setNeutralButton(R.string.sk_conn_live_send_anyway) { _, _ -> doPing() }   // neutral = far-left edge
            .setPositiveButton(R.string.sk_conn_got_it, null)
            .create().also { it.show(); DialogTheme.theme(it, ctx) }
    }

    dialog.show()
    DialogTheme.theme(dialog, ctx)
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
        if (lastUndelivered) showPendingPrompt() else doPing()
    }
}
