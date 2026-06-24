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
import android.text.format.DateUtils
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
import cx.ring.utils.ConnectionHealth
import cx.ring.utils.ConnectionHealth.Health
import cx.ring.utils.DialogTheme
import cx.ring.utils.Flash
import cx.ring.views.AvatarDrawable
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import net.jami.model.ContactViewModel
import net.jami.services.AccountService
import net.jami.services.AccountService.ConnectionStatus
import net.jami.services.ContactService
import javax.inject.Inject

private val YELLOW = 0xFFFFEB3B.toInt()   // legend section-header accent only (not a monitor state)

@AndroidEntryPoint
class ConnectionMonitorFragment: Fragment() {

    @Inject
    lateinit var service: AccountService
    @Inject
    lateinit var contactService: ContactService

    private var list: RecyclerView? = null
    private var summaryView: TextView? = null
    private val disposableBag = CompositeDisposable()

    private val collapsed = HashSet<String>()
    private var lastLoaded: List<LoadedAccount> = emptyList()
    private val accountAvatars = HashMap<String, Drawable>()
    private var peerNames: Map<String, String> = emptyMap()
    private var uriToAccountId: Map<String, String> = emptyMap()

    data class LoadedAccount(
        val account: AccountService.AccountConnections,
        val peers: List<Pair<ContactViewModel, List<AccountService.DeviceConnection>>>
    )

    private fun healthColor(h: Health): Int {
        val ctx = requireContext()
        return when (h) {
            Health.HEALTHY -> ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_HEALTHY)
            Health.CONNECTING -> ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_CONNECTING)
            Health.OFFLINE, Health.NOT_SYNCING -> ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_PROBLEM)
        }
    }
    private fun healthWord(h: Health): String = when (h) {
        Health.HEALTHY -> "online · healthy"
        Health.CONNECTING -> "connecting…"
        Health.NOT_SYNCING -> "NOT SYNCING"
        Health.OFFLINE -> "OFFLINE"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragConnectionMonitorBinding.inflate(inflater, container, false).apply {
            connectionList.adapter = ConnectionAdapter(
                onToggle = { id -> if (!collapsed.remove(id)) collapsed.add(id); rebuildRows() },
                onConnectionClick = { conn -> showDeviceDialog(conn) },
                avatarFor = { id -> accountAvatars[id] }
            )
            list = connectionList
            summaryView = summary
            help.setOnClickListener { showLegendDialog() }
            reconnect.setOnClickListener {
                service.forceReconnectAllAccounts()
                Flash.show(context, "Reconnecting all accounts…")
            }
        }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (parentFragment as? AppBarStateListener)?.apply {
            onToolbarTitleChanged(getText(R.string.pref_connection_monitor))
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
        val connection: AccountService.DeviceConnection? = null,
    )

    class ConnectionAdapter(
        val onToggle: (String) -> Unit,
        val onConnectionClick: (AccountService.DeviceConnection) -> Unit,
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
            connections[position].contact != null -> 0
            else -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionViewHolder = when (viewType) {
            0 -> ItemListContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                .let { ConnectionViewHolder(it.root, binding = it) }
            1 -> ItemDeviceConnectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                .let { ConnectionViewHolder(it.root, connBinding = it) }
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
                val h = connections[position].accountHeader!!
                holder.headerIcon?.setImageDrawable(avatarFor(h.accountId))
                val arrow = if (h.collapsed) "›" else "⌄"
                val text = "$arrow  ${h.name}  —  ${h.statusWord} · ${h.total} device(s)"
                tv.text = SpannableString(text).apply {
                    setSpan(RelativeSizeSpan(2.8f), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tv.setTextColor(h.color)
                holder.root.setOnClickListener { onToggle(h.accountId) }
            }
            holder.connBinding?.let {
                val connection = connections[position].connection!!
                val ctx = it.root.context
                val d = ctx.resources.displayMetrics.density
                it.root.setPaddingRelative((76 * d).toInt(), it.root.paddingTop, it.root.paddingEnd, it.root.paddingBottom)
                val connected = connection.status == ConnectionStatus.Connected
                // Connected = green (a real, live link). Anything else is a transient sync connection —
                // shown neutral grey, NEVER red. Short-lived connections are normal swarm sync.
                it.name.text = connection.device
                val stage = when (connection.status) {
                    ConnectionStatus.Waiting -> "waiting…"
                    ConnectionStatus.Connecting -> "connecting…"
                    ConnectionStatus.ICE -> "negotiating (ICE)…"
                    ConnectionStatus.TLS -> "securing (TLS)…"
                    ConnectionStatus.Connected -> connection.remoteAddress ?: "connected"
                }
                val connectedColor = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_CONNECTED)
                val idleColor = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_IDLE)
                val tint = if (connected) connectedColor else idleColor
                it.device.text = stage
                it.device.isVisible = stage.isNotEmpty()
                it.device.setTextColor(if (connected) connectedColor else idleColor)
                it.icon.setImageResource(when (connection.status) {
                    ConnectionStatus.Waiting -> R.drawable.baseline_radar_24
                    ConnectionStatus.Connecting -> R.drawable.baseline_radar_24
                    ConnectionStatus.ICE -> R.drawable.p2p_24
                    ConnectionStatus.TLS -> R.drawable.baseline_private_connectivity_24
                    ConnectionStatus.Connected -> R.drawable.baseline_private_connectivity_24
                })
                it.icon.imageTintList = ColorStateList.valueOf(tint)
                it.icon.contentDescription = connection.status.toString()
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
                val d = it.root.resources.displayMetrics.density
                it.root.setPaddingRelative((58 * d).toInt(), it.root.paddingTop, it.root.paddingEnd, it.root.paddingBottom)
                val sub = (38 * d).toInt()
                it.photo.layoutParams.apply { width = sub; height = sub }
                it.photo.requestLayout()
                val contact = connections[position].contact!!
                it.photo.setAvatar(AvatarDrawable.Builder()
                    .withContact(contact)
                    .withCircleCrop(true)
                    .build(it.root.context))
                it.convParticipant.text = contact.displayName
                it.convLastItem.text = contact.displayUri
            }
        }

        private fun key(v: DeviceConnectionViewModel): String = when {
            v.accountHeader != null -> "acct:${v.accountHeader.accountId}"
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
                        a.contact != null && b.contact != null -> a.contact.displayName == b.contact.displayName
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
        val now = System.currentTimeMillis()
        val rows = ArrayList<DeviceConnectionViewModel>()
        var peers = 0; var connectedDevs = 0; var healthy = 0; var problem = 0
        for (la in lastLoaded) {
            val ac = la.account
            val accConns = la.peers.flatMap { it.second }
            val byDevice = accConns.groupBy { it.device }
            val connectedNow = byDevice.any { (_, l) -> l.any { it.status == ConnectionStatus.Connected } }
            val health = ConnectionHealth.classify(ac.accountId, now, ac.registered, connectedNow, la.peers.isNotEmpty())
            if (ConnectionHealth.isProblem(health)) problem++ else healthy++
            connectedDevs += byDevice.count { (_, l) -> l.any { it.status == ConnectionStatus.Connected } }
            peers += la.peers.size
            val isCollapsed = ac.accountId in collapsed
            rows.add(DeviceConnectionViewModel(accountHeader =
                AccountHeader(ac.accountId, ac.name, healthWord(health), healthColor(health), byDevice.size, isCollapsed)))
            if (!isCollapsed) {
                la.peers.sortedByDescending { (_, conns) -> conns.count { it.status == ConnectionStatus.Connected } }
                    .forEach { (contact, conns) ->
                        rows.add(DeviceConnectionViewModel(contact = contact))
                        conns.sortedByDescending { it.status == ConnectionStatus.Connected }
                            .forEach { rows.add(DeviceConnectionViewModel(connection = it)) }
                    }
            }
        }
        val tail = if (problem > 0) " · ⚠ $problem need attention" else ""
        summaryView?.text = "${lastLoaded.size} account(s) · $healthy healthy · $connectedDevs connected$tail"
        (list?.adapter as? ConnectionAdapter)?.setData(rows)
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
                text = desc; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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
        val cHealthy = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_HEALTHY)
        val cConnecting = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_CONNECTING)
        val cProblem = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_PROBLEM)
        val cConnected = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_CONNECTED)
        val cIdle = ColorPrefs.getColor(ctx, ColorPrefs.MONITOR_IDLE)
        header("Account health")
        row(dot(cHealthy), "Healthy — registered & syncing")
        row(dot(cConnecting), "Connecting — establishing (transient)")
        row(dot(cProblem), "OFFLINE (not registered) or NOT SYNCING (registered but isolated >2.5 min)")
        header("A connection row")
        row(dot(cConnected), "Connected — a live link right now")
        row(dot(cIdle), "A short-lived sync connection in progress (NORMAL, not a failure)")
        header("Icons (handshake stage)")
        row(icon(R.drawable.baseline_radar_24, cIdle), "Radar — looking up the peer / waiting")
        row(icon(R.drawable.p2p_24, cIdle), "Two phones — ICE: finding a direct path")
        row(icon(R.drawable.baseline_private_connectivity_24, cConnected), "Shield — secured (TLS) & connected")
        header("Note")
        row(TextView(ctx).apply {
            text = "ⓘ"; setTextColor(YELLOW); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((30 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (14 * d).toInt() }
        }, "Connections constantly open & close — that's normal swarm-conversation sync, not a fault. Tap a row for device/contact IDs.")
        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }
        DialogTheme.builder(ctx)
            .setTitle("What this means")
            .setView(scroll)
            .setPositiveButton("Got it", null)
            .show().let { DialogTheme.theme(it, ctx) }
    }

    /** Tap a connection row → device detail + copy/reconnect. */
    private fun showDeviceDialog(conn: AccountService.DeviceConnection) {
        val ctx = context ?: return
        val name = peerNames[conn.peer]
        val ourAccount = uriToAccountId[conn.peer]  // non-null when the peer is one of OUR accounts
        val msg = buildString {
            if (name != null) append("Contact: $name\n")
            append("Device:\n${conn.device}\n\n")
            append("Contact (peer) ID:\n${conn.peer}\n\n")
            append("Status: ${conn.status}\n")
            if (!conn.remoteAddress.isNullOrEmpty()) append("Remote: ${conn.remoteAddress}\n")
            if (conn.channels.isNotEmpty()) append("Channels: ${conn.channels.size}\n")
            if (ourAccount != null) append("\nThis is one of YOUR accounts (same device).")
        }
        fun copy(label: String, text: String) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            Flash.show(ctx, "$label copied")
        }
        val builder = DialogTheme.builder(ctx)
            .setTitle("Connection detail")
            .setMessage(msg)
            .setNegativeButton("Close", null)
        if (ourAccount != null) {
            builder.setPositiveButton("Reconnect ${name ?: "account"}") { _, _ ->
                service.forceReconnectAccount(ourAccount)
                Flash.show(ctx, "Reconnecting ${name ?: "account"}…")
            }
            builder.setNeutralButton("Copy device ID") { _, _ -> copy("Device ID", conn.device) }
        } else {
            builder.setPositiveButton("Copy device ID") { _, _ -> copy("Device ID", conn.device) }
            builder.setNeutralButton("Copy contact ID") { _, _ -> copy("Contact ID", conn.peer) }
        }
        builder.show().let { DialogTheme.theme(it, ctx) }
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
                    if (ac.peers.isEmpty()) Single.just(LoadedAccount(ac, emptyList()))
                    else Single.zip(ac.peers.map { (peer, conns) ->
                        contactService.getLoadedContact(ac.accountId, peer).map { Pair(it, conns) }
                    }) { arr ->
                        @Suppress("UNCHECKED_CAST")
                        LoadedAccount(ac, arr.map { it as Pair<ContactViewModel, List<AccountService.DeviceConnection>> })
                    }
                }
                if (singles.isEmpty()) Single.just(emptyList<LoadedAccount>())
                else Single.zip(singles) { arr -> arr.map { it as LoadedAccount } }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ loaded ->
                lastLoaded = loaded
                uriToAccountId = loaded.filter { it.account.uri.isNotEmpty() }
                    .associate { it.account.uri to it.account.accountId }
                val names = HashMap<String, String>()
                loaded.forEach { la ->
                    if (la.account.uri.isNotEmpty()) names[la.account.uri] = la.account.name
                    la.peers.forEach { (c, conns) -> conns.firstOrNull()?.peer?.let { names[it] = c.displayName } }
                }
                peerNames = names
                val now = System.currentTimeMillis()
                ConnectionHealth.update(now, loaded.map { la ->
                    la.account.accountId to la.peers.any { (_, c) -> c.any { it.status == ConnectionStatus.Connected } }
                })
                rebuildRows()
            }, { /* ignore transient errors */ }))
    }

    override fun onStop() {
        super.onStop()
        disposableBag.clear()
    }
}
