/*
 *  Copyright (C) 2004-2025 Savoir-faire Linux Inc.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package cx.ring.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import cx.ring.R
import cx.ring.databinding.FragConnectionMonitorBinding
import cx.ring.databinding.ItemDeviceConnectionBinding
import cx.ring.databinding.ItemListContactBinding
import cx.ring.interfaces.AppBarStateListener
import cx.ring.utils.ColorPrefs
import cx.ring.utils.UiPrefs
import cx.ring.utils.ConnectionHealth
import cx.ring.utils.ConnectionHealth.Health
import cx.ring.utils.DialogTheme
import cx.ring.utils.Flash
import cx.ring.utils.NetProbe
import cx.ring.views.AvatarDrawable
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import net.jami.model.Contact
import net.jami.model.ContactViewModel
import net.jami.services.AccountService
import net.jami.services.AccountService.ConnectionStatus
import net.jami.services.ContactService
import net.jami.utils.PeerReachability
import javax.inject.Inject

private val YELLOW: Int get() = cx.ring.sk.skColor(cx.ring.sk.SkRole.ACCENT)   // legend section-header accent only (not a monitor state)

@AndroidEntryPoint
class ConnectionMonitorFragment: Fragment() {

    @Inject
    lateinit var service: AccountService
    @Inject
    lateinit var contactService: ContactService
    @Inject
    lateinit var conversationFacade: net.jami.services.ConversationFacade

    private var list: RecyclerView? = null
    private var summaryView: TextView? = null
    private val disposableBag = CompositeDisposable()

    private val collapsed = HashSet<String>()
    private var firstLoad = true   // collapse all accounts on the first load (fold-by-default)
    private var lastLoaded: List<LoadedAccount> = emptyList()
    private val accountAvatars = HashMap<String, Drawable>()
    private var peerNames: Map<String, String> = emptyMap()
    private var uriToAccountId: Map<String, String> = emptyMap()

    /** Every account row carries its FULL contact roster (people), each paired with its live device
     *  connections (empty when the contact is currently disconnected). */
    data class LoadedAccount(
        val account: AccountService.AccountConnections,
        val peers: List<Pair<ContactViewModel, List<AccountService.DeviceConnection>>>
    )

    private fun healthColor(h: Health): Int {
        val ctx = requireContext()
        return when (h) {
            Health.HEALTHY -> ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_HEALTHY)
            Health.CONNECTING -> ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_CONNECTING)
            Health.OFFLINE, Health.NOT_SYNCING, Health.DEAF -> ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_PROBLEM)
        }
    }
    private fun healthWord(h: Health): String = getString(when (h) {
        Health.HEALTHY -> R.string.conn_state_healthy
        Health.CONNECTING -> R.string.conn_state_connecting
        Health.NOT_SYNCING -> R.string.conn_state_not_syncing
        Health.DEAF -> R.string.conn_state_deaf
        Health.OFFLINE -> R.string.conn_state_offline
    })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragConnectionMonitorBinding.inflate(inflater, container, false).apply {
            connectionList.adapter = ConnectionAdapter(
                onToggle = { id -> if (!collapsed.remove(id)) collapsed.add(id); rebuildRows() },
                onConnectionClick = { conn -> showDeviceDialog(conn) },
                onContactClick = { id, cvm -> testLink(id, cvm) },
                avatarFor = { id -> accountAvatars[id] }
            )
            list = connectionList
            summaryView = summary
            help.setOnClickListener { showLegendDialog() }
            // New control (2026-07-26) — the unattended data-usage log, where it is actually looked
            // for. Deliberately a NEW button: `help` and `reconnect` keep their existing actions.
            dataLog.setOnClickListener { showDataLogDialog() }
            transportView = transportRow
            transportRow.setOnClickListener { probeTransport() }
            transportRow.setOnLongClickListener { showTransportDialog(); true }
            renderTransport()
            // Probe on open only when the cached verdict is stale — reopening the screen every
            // minute shouldn't re-test a network that hasn't changed.
            NetProbe.lastVerdict().let { v ->
                if (v == null || System.currentTimeMillis() - v.atMs > TRANSPORT_STALE_MS) probeTransport()
            }
            reconnect.setOnClickListener {
                service.forceReconnectAllAccounts()
                Flash.show(context, getString(R.string.sk_conn_mon_reconnecting_all))
            }
        }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (parentFragment as? AppBarStateListener)?.apply {
            onToolbarTitleChanged(getText(R.string.sk_conn_monitor_title))
            onAppBarScrollTargetViewChanged(list)
        }
    }

    data class AccountHeader(
        val accountId: String,
        val name: String,
        val statusWord: String,
        val color: Int,
        val total: Int,
        val collapsed: Boolean,
    )

    data class DeviceConnectionViewModel(
        val accountHeader: AccountHeader? = null,
        val contact: ContactViewModel? = null,
        val contactStatusText: String? = null,
        val contactStatusColor: Int = 0,
        val accountId: String? = null,
        val connection: AccountService.DeviceConnection? = null,
        val stuckLabel: String? = null,   // a "message not delivered → <chat>" sub-row (account avatar)
        val stuckConvId: String? = null,  // conversation to open when the stuck sub-row is tapped
    )

    class ConnectionAdapter(
        val onToggle: (String) -> Unit,
        val onConnectionClick: (AccountService.DeviceConnection) -> Unit,
        val onContactClick: (String, ContactViewModel) -> Unit,
        val avatarFor: (String) -> Drawable?,
        var connections: List<DeviceConnectionViewModel> = emptyList()
    ): RecyclerView.Adapter<ConnectionAdapter.ConnectionViewHolder>() {
        class ConnectionViewHolder(
            val root: View,
            val binding: ItemListContactBinding? = null,
            val connBinding: ItemDeviceConnectionBinding? = null,
            val headerIcon: ImageView? = null,
            val headerText: TextView? = null,
        ): RecyclerView.ViewHolder(root)

        override fun getItemViewType(position: Int): Int = when {
            connections[position].accountHeader != null -> 2
            connections[position].stuckLabel != null -> 3
            connections[position].contact != null -> 0
            else -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionViewHolder = when (viewType) {
            0 -> ItemListContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                .let { ConnectionViewHolder(it.root, binding = it) }
            1 -> ItemDeviceConnectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                .let { ConnectionViewHolder(it.root, connBinding = it) }
            3 -> {
                // Stuck-message sub-row: account avatar + red "message not delivered → <chat>".
                val ctx = parent.context
                val d = ctx.resources.displayMetrics.density
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((58 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                }
                val iv = ImageView(ctx).apply {
                    val s = (38 * d).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (12 * d).toInt() }
                }
                val tv = TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                }
                row.addView(iv); row.addView(tv)
                ConnectionViewHolder(row, headerIcon = iv, headerText = tv)
            }
            else -> {
                val ctx = parent.context
                val d = ctx.resources.displayMetrics.density
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((10 * d).toInt(), (16 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                }
                val iv = ImageView(ctx).apply {
                    val s = (64 * d).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (12 * d).toInt() }
                }
                val tv = TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTypeface(typeface, Typeface.BOLD)
                }
                row.addView(iv); row.addView(tv)
                ConnectionViewHolder(row, headerIcon = iv, headerText = tv)
            }
        }

        override fun onBindViewHolder(holder: ConnectionViewHolder, position: Int) {
            holder.headerText?.let { tv ->
                val vm = connections[position]
                if (vm.stuckLabel != null) {
                    holder.headerIcon?.setImageDrawable(avatarFor(vm.accountId ?: ""))
                    tv.text = vm.stuckLabel
                    tv.setTextColor(vm.contactStatusColor)
                    // Tap the stuck sub-row → open that conversation (白い熊, 2026-07-24).
                    val acctId = vm.accountId; val convId = vm.stuckConvId
                    if (acctId != null && convId != null) holder.root.setOnClickListener { v ->
                        v.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            cx.ring.utils.ConversationPath.toUri(acctId, net.jami.model.Uri.fromString(convId)),
                            v.context, cx.ring.client.HomeActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else holder.root.setOnClickListener(null)
                } else {
                    val h = vm.accountHeader!!
                    holder.headerIcon?.setImageDrawable(avatarFor(h.accountId))
                    // Filled disclosure triangle (▶ folded / ▼ open) — immediately apparent, unlike the
                    // old "⌄" that read as a "v". Size is settable in the UI page (default 0.94×).
                    val arrow = if (h.collapsed) "▶" else "▼"
                    val text = tv.context.getString(R.string.sk_conn_mon_account_header, arrow, h.name, h.statusWord, h.total)
                    tv.text = SpannableString(text).apply {
                        setSpan(RelativeSizeSpan(UiPrefs.getMonitorFoldScale(tv.context)), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    tv.setTextColor(h.color)
                    holder.root.setOnClickListener { onToggle(h.accountId) }
                }
            }
            holder.connBinding?.let {
                val connection = connections[position].connection!!
                val ctx = it.root.context
                val d = ctx.resources.displayMetrics.density
                it.root.setPaddingRelative((92 * d).toInt(), it.root.paddingTop, it.root.paddingEnd, it.root.paddingBottom)
                val connected = connection.status == ConnectionStatus.Connected
                val connectedColor = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_CONNECTED)
                val idleColor = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_IDLE)
                it.name.text = connection.device
                val stage = when (connection.status) {
                    ConnectionStatus.Waiting -> ctx.getString(R.string.conn_state_waiting)
                    ConnectionStatus.Connecting -> ctx.getString(R.string.conn_state_connecting)
                    ConnectionStatus.ICE -> ctx.getString(R.string.conn_state_ice)
                    ConnectionStatus.TLS -> ctx.getString(R.string.conn_state_tls)
                    ConnectionStatus.Connected -> connection.remoteAddress ?: ctx.getString(R.string.conn_state_connected)
                }
                val tint = if (connected) connectedColor else idleColor
                it.device.text = stage
                it.device.isVisible = stage.isNotEmpty()
                it.device.setTextColor(tint)
                it.icon.setImageResource(when (connection.status) {
                    ConnectionStatus.Waiting -> R.drawable.baseline_radar_24
                    ConnectionStatus.Connecting -> R.drawable.baseline_radar_24
                    ConnectionStatus.ICE -> R.drawable.p2p_24
                    ConnectionStatus.TLS -> R.drawable.baseline_private_connectivity_24
                    ConnectionStatus.Connected -> R.drawable.baseline_private_connectivity_24
                })
                it.icon.imageTintList = ColorStateList.valueOf(tint)
                it.icon.contentDescription = ctx.getString(when (connection.status) {
                    ConnectionStatus.Waiting -> R.string.conn_state_waiting
                    ConnectionStatus.Connecting -> R.string.conn_state_connecting
                    ConnectionStatus.ICE -> R.string.conn_state_ice
                    ConnectionStatus.TLS -> R.string.conn_state_tls
                    ConnectionStatus.Connected -> R.string.conn_state_connected
                })
                if (connected) {
                    it.channels.text = connection.channels.size.toString()
                    it.channels.isVisible = true
                    it.channels.setOnClickListener { view ->
                        DialogTheme.builder(view.context)
                            .setTitle(R.string.connection_monitor_channels)
                            .setItems(connection.channels.toTypedArray(), null)
                            .setPositiveButton(android.R.string.ok, null)
                            .show().let { d2 -> DialogTheme.theme(d2, view.context) }
                    }
                    if (connection.connectionTime > 0L) {
                        it.connectionTime.text = DateUtils.getRelativeTimeSpanString(
                            connection.connectionTime, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
                        it.connectionTime.isVisible = true
                    } else it.connectionTime.isVisible = false
                } else {
                    it.channels.isVisible = false
                    it.channels.setOnClickListener(null)
                    it.connectionTime.isVisible = false
                }
                it.root.setOnClickListener { onConnectionClick(connection) }
            }
            holder.binding?.let {
                val vm = connections[position]
                val d = it.root.resources.displayMetrics.density
                it.root.setPaddingRelative((58 * d).toInt(), it.root.paddingTop, it.root.paddingEnd, it.root.paddingBottom)
                val sub = (38 * d).toInt()
                it.photo.layoutParams.apply { width = sub; height = sub }
                it.photo.requestLayout()
                val contact = vm.contact!!
                it.photo.setAvatar(AvatarDrawable.Builder()
                    .withContact(contact)
                    .withPresence(true)
                    .withOnlineState(contact.presence)
                    .withCircleCrop(true)
                    .build(it.root.context))
                it.convParticipant.text = contact.displayName
                it.convLastItem.text = "● ${vm.contactStatusText ?: contact.displayUri}"
                it.convLastItem.setTextColor(if (vm.contactStatusColor != 0) vm.contactStatusColor else YELLOW)
                it.root.setOnClickListener { onContactClick(vm.accountId ?: "", contact) }
            }
        }

        private fun key(v: DeviceConnectionViewModel): String = when {
            v.accountHeader != null -> "acct:${v.accountHeader.accountId}"
            v.stuckLabel != null -> "stuck:${v.accountId}:${v.stuckLabel}"
            v.contact != null -> "contact:${v.contact.contact.uri}"
            else -> "conn:${v.connection?.id}"
        }

        fun setData(newConnections: List<DeviceConnectionViewModel>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = connections.size
                override fun getNewListSize(): Int = newConnections.size
                override fun areItemsTheSame(o: Int, n: Int): Boolean =
                    key(connections[o]) == key(newConnections[n])
                override fun areContentsTheSame(o: Int, n: Int): Boolean {
                    val a = connections[o]; val b = newConnections[n]
                    return when {
                        a.accountHeader != null && b.accountHeader != null -> a.accountHeader == b.accountHeader
                        a.contact != null && b.contact != null ->
                            a.contact.displayName == b.contact.displayName && a.contactStatusText == b.contactStatusText
                        else -> false
                    }
                }
            })
            connections = newConnections
            diff.dispatchUpdatesTo(this)
        }
        override fun getItemCount(): Int = connections.size
    }

    private fun rebuildRows() {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        val connectedColor = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_CONNECTED)
        val idleColor = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_IDLE)
        val offlineColor = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_OFFLINE)
        val problemColor = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_PROBLEM)
        val rows = ArrayList<DeviceConnectionViewModel>()
        var healthy = 0; var problem = 0; var connectedContacts = 0
        // My own accounts' uris → tells SAME-DEVICE conversations (where a stuck message is a real
        // problem) from external ones (a normal wait on an offline contact is not a fault).
        val myUris = lastLoaded.mapNotNull { it.account.uri.takeIf(String::isNotEmpty) }.toSet()
        // My own accounts that are registered/online — they're reachable by definition (same device),
        // even though same-device accounts don't publish presence to each other (so their contact
        // presence reads stale-OFFLINE). Used to show them "reachable" instead of a false "offline".
        val myOnlineUris = lastLoaded.filter { it.account.registered && it.account.uri.isNotEmpty() }
            .map { it.account.uri }.toSet()
        // Per-account stuck (undelivered) outgoing messages — the real "not going through" signal,
        // works for group swarms (invisible to the connection table) — named by the stuck chat's member.
        val stuckChats = HashMap<String, List<ConnectionHealth.StuckConv>>()
        for (la in lastLoaded) {
            val model = service.getAccount(la.account.accountId)
            val convs = model?.let { ConnectionHealth.accountStuckConvs(it, now, myUris) } ?: emptyList()
            stuckChats[la.account.accountId] = convs.distinctBy { it.convId }
        }
        // Disambiguate the recipient: 白い熊 shares the profile name "白い熎" across accounts, so append
        // the account username (when it differs) and the id tail (2026-07-24).
        val sibByUri = lastLoaded.filter { it.account.uri.isNotEmpty() }.associateBy { it.account.uri }
        fun recipientLabel(uri: String): String {
            val disp = peerNames[uri] ?: uri.take(8)
            val user = sibByUri[uri]?.account?.name?.takeIf { it.isNotBlank() && it != disp }
            val tail = uri.take(8)
            return if (user != null) "$disp ($user · $tail)" else "$disp · $tail"
        }
        fun healthOf(la: LoadedAccount): Health {
            val cn = la.peers.any { (_, c) -> c.any { it.status == ConnectionStatus.Connected } }
            val at = la.peers.any { (_, c) -> c.any { it.status != ConnectionStatus.Connected } }
            return ConnectionHealth.classify(la.account.registered, cn, (stuckChats[la.account.accountId]?.isNotEmpty() == true), at,
                cx.ring.utils.ConnectionWatchdog.accountVerifiedDeaf(la.account.accountId),
                cx.ring.utils.ConnectionWatchdog.accountProbing(la.account.accountId))
        }
        // Sort problem (red) accounts to the TOP; keep account order otherwise (stable sort).
        val ranked = lastLoaded.sortedBy { if (ConnectionHealth.isProblem(healthOf(it))) 0 else 1 }
        for (la in ranked) {
            val ac = la.account
            val health = healthOf(la)
            if (ConnectionHealth.isProblem(health)) problem++ else healthy++
            // The account avatar's dot must match the HEALTH verdict — a red NOT SYNCING row with a
            // yellow presence dot is a contradiction (白い熊, 2026-07-24).
            (accountAvatars[ac.accountId] as? AvatarDrawable)?.setPresenceStatus(when {
                ConnectionHealth.isProblem(health) -> Contact.PresenceStatus.OFFLINE
                health == Health.CONNECTING -> Contact.PresenceStatus.AVAILABLE
                else -> Contact.PresenceStatus.CONNECTED
            })
            val isCollapsed = ac.accountId in collapsed
            val chats = stuckChats[ac.accountId] ?: emptyList()
            // Only FAULT-class (red) messages count as the account's "N msg stuck".
            val faultCount = chats.count { it.severity == ConnectionHealth.StuckSeverity.FAULT }
            val word = healthWord(health) + if (faultCount > 0) " (${ctx.getString(R.string.conn_msg_stuck_count, faultCount)})" else ""
            rows.add(DeviceConnectionViewModel(accountHeader =
                AccountHeader(ac.accountId, ac.name, word, healthColor(health), la.peers.size, isCollapsed)))
            // Stuck-message sub-rows — shown even when the account is collapsed. COLOUR is the cause
            // (白い熎, 2026-07-24): FAULT = red ("not delivered" — same-device stall or live-peer-no-ACK,
            // ours to fix); PENDING = blue ("delivering…" — recipient away, benign, we're attempting).
            for (sc in chats) {
                val fault = sc.severity == ConnectionHealth.StuckSeverity.FAULT
                val age = ConnectionHealth.ageLabel(ctx, now - sc.timestamp)
                val kind = if (sc.isFile) "📎 " + sc.preview
                    else if (sc.preview.isNotEmpty()) "💬 " + sc.preview else ctx.getString(R.string.sk_conn_mon_kind_text)
                val label = if (fault) {
                    val why = if (PeerReachability.isUnreachable(ac.accountId, sc.memberUri))
                        " · " + ctx.getString(R.string.sk_conn_mon_stuck_unreachable) else ""
                    ctx.getString(R.string.conn_msg_not_delivered, recipientLabel(sc.memberUri)) + why
                } else {
                    val away = if (sc.presence == Contact.PresenceStatus.AVAILABLE)
                        ctx.getString(R.string.sk_conn_mon_away_announced) else ctx.getString(R.string.sk_conn_mon_away_offline)
                    ctx.getString(R.string.sk_conn_mon_delivering, recipientLabel(sc.memberUri), away)
                }
                rows.add(DeviceConnectionViewModel(
                    accountId = ac.accountId,
                    stuckLabel = "$label\n    $age · $kind ▸",
                    stuckConvId = sc.convId,
                    contactStatusColor = if (fault) problemColor else idleColor))
            }
            if (!isCollapsed) {
                // connected first, then reconnecting, then disconnected; alphabetical within each.
                val sorted = la.peers.sortedWith(compareBy({ (_, conns) ->
                    when {
                        conns.any { it.status == ConnectionStatus.Connected } -> 0
                        conns.isNotEmpty() -> 1
                        else -> 2
                    }
                }, { (cvm, _) -> cvm.displayName.lowercase() }))
                for ((cvm, conns) in sorted) {
                    val connected = conns.any { it.status == ConnectionStatus.Connected }
                    val attempting = conns.isNotEmpty() && !connected
                    val peerKey = cvm.contact.uri.rawRingId ?: cvm.contact.uri.uri
                    if (connected) connectedContacts++
                    val (stext, scolor) = when {
                        connected -> ctx.getString(R.string.conn_state_connected) to connectedColor                  // yellow — live link
                        attempting -> ctx.getString(R.string.conn_state_connecting) to idleColor                     // blue — establishing (in progress, not a problem)
                        // Verified unreachable (stuck outgoing message, no live channel): the dot is
                        // demoted red by the presence gate — say WHY instead of a bare "offline".
                        PeerReachability.isUnreachable(ac.accountId, peerKey) ->
                            ctx.getString(R.string.conn_state_unreachable) to problemColor
                        // A same-device account of mine (always reachable here), or a contact present on
                        // the network → reachable. Same-device accounts don't publish presence to each
                        // other, so without this they'd falsely read "offline".
                        peerKey in myOnlineUris || cvm.presence != Contact.PresenceStatus.OFFLINE ->
                            ctx.getString(R.string.conn_state_reachable) to connectedColor
                        else -> ctx.getString(R.string.conn_state_peer_offline) to offlineColor                           // grey — not reachable now
                    }
                    rows.add(DeviceConnectionViewModel(contact = cvm, contactStatusText = stext,
                        contactStatusColor = scolor, accountId = ac.accountId))
                    conns.sortedByDescending { it.status == ConnectionStatus.Connected }
                        .forEach { rows.add(DeviceConnectionViewModel(connection = it)) }
                }
            }
        }
        val base = ctx.getString(R.string.sk_conn_mon_summary, lastLoaded.size, healthy, connectedContacts)
        summaryView?.text = if (problem > 0) {
            val warn = " · " + ctx.getString(R.string.sk_conn_mon_summary_attention, problem)
            SpannableStringBuilder(base).append(warn).apply {
                setSpan(ForegroundColorSpan(problemColor), base.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), base.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else base
        (list?.adapter as? ConnectionAdapter)?.setData(rows)
    }

    // ---- Transport row (2026-07-26) ----
    /** A cached verdict older than this is re-tested when the screen opens. */
    private val TRANSPORT_STALE_MS = 10 * 60_000L
    private var transportView: TextView? = null
    private var transportProbing = false
    // Not view?.post(): the first probe is kicked off from onCreateView, where the fragment's view
    // is still null, and the completion callback would be silently dropped.
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun ageLabel(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> getString(R.string.sk_conn_age_just_now)
            s < 3600 -> getString(R.string.sk_conn_age_min, (s / 60).toInt())
            else -> getString(R.string.sk_conn_age_hours, (s / 3600).toInt())
        }
    }

    /** Render the cached verdict. Always states its AGE — a stale reading presented as live is
     *  exactly what misleads during an incident. */
    private fun renderTransport() {
        val tv = transportView ?: return
        val ctx = context ?: return
        if (transportProbing) {
            tv.text = ctx.getString(R.string.sk_conn_transport_testing)
            tv.setTextColor(ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_CONNECTING))
            return
        }
        val v = NetProbe.lastVerdict()
        if (v == null) {
            tv.text = ctx.getString(R.string.sk_conn_transport_untested)
            tv.setTextColor(ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_IDLE))
            return
        }
        val udp = if (v.udp) "UDP ✓" else "UDP ✗"
        val tcp = if (v.tcp) "TCP ✓" else "TCP ✗"
        val (verdict, color) = when (v.transport) {
            NetProbe.Transport.OK ->
                ctx.getString(R.string.sk_conn_transport_ok) to ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_HEALTHY)
            NetProbe.Transport.HOSTILE ->
                ctx.getString(R.string.sk_conn_transport_hostile) to ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_PROBLEM)
            NetProbe.Transport.DEAD ->
                ctx.getString(R.string.sk_conn_transport_dead) to ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_PROBLEM)
            else -> ctx.getString(R.string.sk_conn_transport_unknown) to ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_IDLE)
        }
        tv.text = ctx.getString(R.string.sk_conn_transport_line, udp, tcp, verdict, ageLabel(System.currentTimeMillis() - v.atMs))
        tv.setTextColor(color)
    }

    /** Re-run the egress test. Blocking work goes to NetProbe's own thread; only the render is here. */
    private fun probeTransport() {
        if (transportProbing) return
        transportProbing = true
        renderTransport()
        NetProbe.refreshAsync({ r -> uiHandler.post(r) }) {
            transportProbing = false
            if (isAdded) renderTransport()
        }
    }

    private fun showTransportDialog() {
        val ctx = context ?: return
        val v = NetProbe.lastVerdict()
        val state = when (v?.transport) {
            NetProbe.Transport.OK -> ctx.getString(R.string.sk_conn_transport_state_ok)
            NetProbe.Transport.HOSTILE -> ctx.getString(R.string.sk_conn_transport_state_hostile)
            NetProbe.Transport.DEAD -> ctx.getString(R.string.sk_conn_transport_state_dead)
            else -> ctx.getString(R.string.sk_conn_transport_state_untested)
        }
        val body = state + "\n\n" +
            ctx.getString(R.string.sk_conn_transport_what) + "\n\n" +
            ctx.getString(R.string.sk_conn_transport_why) + "\n\n" +
            ctx.getString(R.string.sk_conn_transport_where) + "\n\n" +
            ctx.getString(R.string.sk_conn_transport_advice)
        val tv = TextView(ctx).apply {
            text = body; setTextColor(YELLOW); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val p = (16 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p); setTextIsSelectable(true)
        }
        DialogTheme.builder(ctx)
            .setTitle(R.string.sk_conn_transport_title)
            .setView(android.widget.ScrollView(ctx).apply { addView(tv) })
            .setPositiveButton(R.string.conn_dash_close, null)
            .setNegativeButton(R.string.sk_conn_transport_again) { _, _ -> probeTransport() }
            .show().let { DialogTheme.theme(it, ctx) }
    }

    /** The unattended data-usage log, newest window first. Read-only here — the switch and the
     *  window length live in Settings → UI fonts & colors → Online recovery, next to the manual
     *  measurement session they belong with. */
    /** The "Data" dialog — live measurement session, saved sessions and the hourly log. Shared
     *  with the dot-tap Connection dashboard's "Data" pill so both show exactly the same thing. */
    private fun showDataLogDialog() {
        context?.let { cx.ring.utils.DataMeterUi.showDataDialog(it, service) }
    }

    private fun showLegendDialog() {
        val ctx = context ?: return
        val d = ctx.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun header(t: String) = root.addView(TextView(ctx).apply {
            text = t; setTypeface(typeface, Typeface.BOLD); setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
        })
        fun row(badge: View, desc: String) {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            }
            r.addView(badge)
            r.addView(TextView(ctx).apply {
                text = desc; setTextColor(cx.ring.sk.skColor(cx.ring.sk.SkRole.TEXT)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            root.addView(r)
        }
        fun icon(res: Int, color: Int): View = ImageView(ctx).apply {
            val s = (30 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (14 * d).toInt() }
            setImageResource(res); imageTintList = ColorStateList.valueOf(color)
        }
        fun dot(color: Int): View = TextView(ctx).apply {
            text = "●"; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((30 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (14 * d).toInt() }
        }
        // Plain full-width paragraph (no badge column) — for explanation rather than a legend entry.
        fun para(t: String, emphasis: Boolean = false) = root.addView(TextView(ctx).apply {
            text = t
            setTextColor(if (emphasis) YELLOW else cx.ring.sk.skColor(cx.ring.sk.SkRole.TEXT))
            if (emphasis) setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (emphasis) 15f else 14f)
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        })
        val cHealthy = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_HEALTHY)
        val cConnecting = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_CONNECTING)
        val cProblem = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_PROBLEM)
        val cConnected = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_CONNECTED)
        val cIdle = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_IDLE)
        val cOffline = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_OFFLINE)
        // Wording mirrors ConnectionHealth.classify() exactly — priority OFFLINE → DEAF →
        // NOT_SYNCING → CONNECTING → HEALTHY. Keep these in step if the classifier changes.
        header(ctx.getString(R.string.sk_conn_legend_h_health))
        row(dot(cHealthy), ctx.getString(R.string.sk_conn_legend_healthy))
        row(dot(cConnecting), ctx.getString(R.string.sk_conn_legend_connecting))
        row(dot(cProblem), ctx.getString(R.string.sk_conn_legend_deaf))
        row(dot(cProblem), ctx.getString(R.string.sk_conn_legend_not_syncing))
        row(dot(cProblem), ctx.getString(R.string.sk_conn_legend_offline))
        header(ctx.getString(R.string.sk_conn_legend_h_contacts))
        row(dot(cConnected), ctx.getString(R.string.sk_conn_legend_c_connected))
        row(dot(cIdle), ctx.getString(R.string.sk_conn_legend_c_connecting))
        row(dot(cOffline), ctx.getString(R.string.sk_conn_legend_c_offline))
        header(ctx.getString(R.string.sk_conn_legend_h_icons))
        row(icon(R.drawable.baseline_radar_24, cIdle), ctx.getString(R.string.sk_conn_legend_i_radar))
        row(icon(R.drawable.p2p_24, cIdle), ctx.getString(R.string.sk_conn_legend_i_p2p))
        row(icon(R.drawable.baseline_private_connectivity_24, cConnected), ctx.getString(R.string.sk_conn_legend_i_shield))
        header(ctx.getString(R.string.sk_conn_legend_h_transport))
        para(ctx.getString(R.string.sk_conn_legend_t_row))
        para(ctx.getString(R.string.sk_conn_legend_t_case), emphasis = true)
        para(ctx.getString(R.string.sk_conn_legend_t_udp))
        para(ctx.getString(R.string.sk_conn_legend_t_default))
        para(ctx.getString(R.string.sk_conn_legend_t_switch))
        header(ctx.getString(R.string.sk_conn_legend_h_note))
        row(TextView(ctx).apply {
            text = "ⓘ"; setTextColor(YELLOW); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((30 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (14 * d).toInt() }
        }, ctx.getString(R.string.sk_conn_legend_note))
        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }
        DialogTheme.builder(ctx)
            .setTitle(R.string.sk_conn_legend_title)
            .setView(scroll)
            .setPositiveButton(R.string.sk_conn_got_it, null)
            .show().let { DialogTheme.theme(it, ctx) }
    }

    /** Tap a connection row → device detail + copy/reconnect. */
    private fun showDeviceDialog(conn: AccountService.DeviceConnection) {
        val ctx = context ?: return
        val name = peerNames[conn.peer]
        val ourAccount = uriToAccountId[conn.peer]  // non-null when the peer is one of OUR accounts
        val statusWord = ctx.getString(when (conn.status) {
            ConnectionStatus.Waiting -> R.string.conn_state_waiting
            ConnectionStatus.Connecting -> R.string.conn_state_connecting
            ConnectionStatus.ICE -> R.string.conn_state_ice
            ConnectionStatus.TLS -> R.string.conn_state_tls
            ConnectionStatus.Connected -> R.string.conn_state_connected
        })
        val msg = buildString {
            if (name != null) append(ctx.getString(R.string.sk_conn_detail_contact, name)).append("\n")
            append(ctx.getString(R.string.sk_conn_detail_device, conn.device)).append("\n\n")
            append(ctx.getString(R.string.sk_conn_detail_peer_id, conn.peer)).append("\n\n")
            append(ctx.getString(R.string.sk_conn_detail_status, statusWord)).append("\n")
            if (!conn.remoteAddress.isNullOrEmpty()) append(ctx.getString(R.string.sk_conn_detail_remote, conn.remoteAddress)).append("\n")
            if (conn.channels.isNotEmpty()) append(ctx.getString(R.string.sk_conn_detail_channels, conn.channels.size)).append("\n")
            if (ourAccount != null) append("\n").append(ctx.getString(R.string.sk_conn_detail_own_account))
        }
        fun copy(label: String, text: String) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            Flash.show(ctx, ctx.getString(R.string.sk_conn_copied, label))
        }
        val builder = DialogTheme.builder(ctx)
            .setTitle(R.string.sk_conn_detail_title)
            .setMessage(msg)
            .setNegativeButton(R.string.conn_dash_close, null)
        if (ourAccount != null) {
            val who = name ?: ctx.getString(R.string.sk_conn_account_fallback)
            builder.setPositiveButton(ctx.getString(R.string.sk_conn_reconnect_named, who)) { _, _ ->
                service.forceReconnectAccount(ourAccount)
                Flash.show(ctx, ctx.getString(R.string.sk_conn_reconnecting_named, who))
            }
            builder.setNeutralButton(R.string.sk_conn_copy_device_id) { _, _ -> copy(ctx.getString(R.string.sk_conn_device_id), conn.device) }
        } else {
            builder.setPositiveButton(R.string.sk_conn_copy_device_id) { _, _ -> copy(ctx.getString(R.string.sk_conn_device_id), conn.device) }
            builder.setNeutralButton(R.string.sk_conn_copy_contact_id) { _, _ -> copy(ctx.getString(R.string.sk_conn_contact_id), conn.peer) }
        }
        builder.show().let { DialogTheme.theme(it, ctx) }
    }

    /** Tap a contact with no active channel → a dialog to act on the link: force a reconnect (re-register
     *  the account on the current DHT — what clears a stuck / partial transfer) or test it (open a channel
     *  + re-check presence), then watch the monitor for the ICE / connection state coming up. */
    private fun testLink(accountId: String, cvm: ContactViewModel) {
        val ctx = context ?: return
        if (accountId.isEmpty()) return
        val name = cvm.displayName
        val conv = service.getAccount(accountId)?.getByUri(cvm.contact.uri)
        if (conv != null) showContactConnectionDialog(ctx, service, conversationFacade, conv)
        else { service.refreshBuddyPresence(accountId, cvm.contact.uri.uri); Flash.show(ctx, ctx.getString(R.string.sk_conn_refreshed, name)) }
    }

    override fun onStart() {
        super.onStart()
        disposableBag.add(service.observableAccountList
            .switchMap { accs ->
                Observable.merge(accs.filter { it.isJami }.map { service.getObservableAccountProfile(it.accountId) })
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (account, profile) ->
                val ctx = context ?: return@subscribe
                accountAvatars[account.accountId] =
                    AvatarDrawable.build(ctx, account, profile, true, account.presenceStatus)
                rebuildRows()
            }, { /* ignore */ }))

        disposableBag.add(service.monitorAllConnections(true)
            .switchMapSingle { accounts ->
                val singles = accounts.map { ac ->
                    val account = service.getAccount(ac.accountId)
                    val connByUri = HashMap<String, List<AccountService.DeviceConnection>>()
                    ac.peers.forEach { (peer, conns) -> connByUri[peer] = conns }
                    // Full roster: all the account's contacts (people) ∪ any connected non-contact peers.
                    val contactObjs = LinkedHashMap<String, Contact>()
                    account?.contacts?.values?.forEach { c ->
                        if (!c.isUser) c.uri.rawRingId?.let { contactObjs[it] = c }
                    }
                    connByUri.keys.forEach { uri ->
                        if (uri !in contactObjs) account?.getContactFromCache(uri)?.let { contactObjs[uri] = it }
                    }
                    if (contactObjs.isEmpty()) Single.just(LoadedAccount(ac, emptyList()))
                    else contactService.getLoadedContact(ac.accountId, contactObjs.values, true).map { cvms ->
                        LoadedAccount(ac, cvms.map { cvm ->
                            val uri = cvm.contact.uri.rawRingId ?: cvm.contact.uri.toString()
                            cvm to (connByUri[uri] ?: emptyList())
                        })
                    }
                }
                if (singles.isEmpty()) Single.just(emptyList<LoadedAccount>())
                else Single.zip(singles) { arr -> arr.map { it as LoadedAccount } }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ loaded ->
                // Start with every account FOLDED; the user expands what they want. Only on the first
                // load (so later live updates don't re-collapse what the user opened).
                if (firstLoad && loaded.isNotEmpty()) {
                    collapsed.addAll(loaded.map { it.account.accountId })
                    firstLoad = false
                }
                lastLoaded = loaded
                uriToAccountId = loaded.filter { it.account.uri.isNotEmpty() }
                    .associate { it.account.uri to it.account.accountId }
                val names = HashMap<String, String>()
                loaded.forEach { la ->
                    if (la.account.uri.isNotEmpty()) names[la.account.uri] = la.account.name
                    la.peers.forEach { (c, _) ->
                        c.contact.uri.rawRingId?.let { names[it] = c.displayName }
                    }
                }
                peerNames = names
                rebuildRows()
            }, { /* ignore transient errors */ }))
    }

    override fun onStop() {
        super.onStop()
        disposableBag.clear()
    }
}
