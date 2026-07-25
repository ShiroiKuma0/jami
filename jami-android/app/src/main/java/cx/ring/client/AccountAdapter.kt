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
package cx.ring.client

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import cx.ring.R
import cx.ring.databinding.ItemAccountBinding
import cx.ring.utils.DeviceUtils
import cx.ring.views.AvatarDrawable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import net.jami.model.Account
import net.jami.model.Profile
import net.jami.services.AccountService
import net.jami.services.ConversationFacade

/**
 * Adapter allowing to select account user want to use.
 */
class AccountAdapter(
    context: Context,
    accounts: List<Account>,
    val disposable: CompositeDisposable,
    var mAccountService: AccountService,
    var mConversationFacade: ConversationFacade,
) : ArrayAdapter<Account>(context, R.layout.item_account, accounts) {

    private val mInflater: LayoutInflater = LayoutInflater.from(context)
    private val ip2ipString = context.getString(R.string.account_type_ip2ip)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        // Try to recycle the view
        val holder: ViewHolder
        var view = convertView
        if (view != null) {
            holder = view.tag as ViewHolder
            holder.loader.clear()
            holder.binding.logo.setImageDrawable(null)
            holder.binding.title.text = null
            holder.binding.subtitle.text = null
        } else { // Create a new view
            holder = ViewHolder(
                ItemAccountBinding.inflate(mInflater, parent, false),
                disposable
            )
            view = holder.binding.root
            view.tag = holder
        }

        // Items can be:
        // - available accounts (type=TYPE_ACCOUNT)
        // - a button to create a new account (type=TYPE_CREATE_ACCOUNT)
        val type = getItemViewType(position)
        // Row metrics per type (recycling-safe: a recycled Add-account holder must be restored to the
        // full account size, and vice-versa). Account rows use the layout defaults (60dp / 24sp);
        // the "Add account" row is half that (白い熊, 2026-07-24).
        val density = context.resources.displayMetrics.density
        val logoDp = if (type == TYPE_ACCOUNT) 60 else 30
        val titleSp = if (type == TYPE_ACCOUNT) 24f else 12f
        holder.binding.logo.layoutParams = holder.binding.logo.layoutParams.apply {
            width = (logoDp * density).toInt(); height = (logoDp * density).toInt()
        }
        holder.binding.title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSp)
        if (type == TYPE_ACCOUNT) {
            holder.binding.logo.imageTintList = null
            val account = getItem(position)!!

            // Update the unread counter (sum of unread conversations and pending conversations)
            holder.loader.add(
                mAccountService.getObservableAccountProfile(account.accountId).switchMap {
                    Observable.combineLatest(
                        account.unreadConversations,
                        account.getPendingSubject()
                    ) { unreadConversationCounter, pendingConversationList ->
                        unreadConversationCounter + pendingConversationList.size
                    }
                }
                    .observeOn(DeviceUtils.uiScheduler)
                    .subscribe {
                        val c = holder.binding.root.context
                        val d = c.resources.displayMetrics.density
                        if (it > 0) {
                            holder.binding.invitationBadge.visibility = View.VISIBLE
                            holder.binding.invitationBadge.text = it.toString()
                            (holder.binding.invitationBadge.background?.mutate() as? android.graphics.drawable.GradientDrawable)?.apply {
                                setColor(cx.ring.utils.ColorPrefs.getColor(c, cx.ring.utils.ColorPrefs.BADGE_FILL))
                                setStroke((2f * d).toInt(), cx.ring.utils.ColorPrefs.getColor(c, cx.ring.utils.ColorPrefs.BADGE_BORDER))
                            }
                            // Unread rows get a row-wide box in the badge's own style (白い熊,
                            // 2026-07-25). Drawn INSET from the row edges so its verticals never
                            // merge with the dialog border (left: 2 lines) or with the dialog
                            // border + badge border (right: 3 lines); padding pushes the content
                            // — badge included — clear of the box's stroke.
                            val box = android.graphics.drawable.GradientDrawable().apply {
                                setColor(cx.ring.utils.ColorPrefs.getColor(c, cx.ring.utils.ColorPrefs.BADGE_FILL))
                                setStroke((2f * d).toInt(), cx.ring.utils.ColorPrefs.getColor(c, cx.ring.utils.ColorPrefs.BADGE_BORDER))
                                cornerRadius = 8f * d
                            }
                            // Horizontal inset 10dp: a wider clear gap to the dialog border
                            // (白い熊 2026-07-25 round 2); padding raised in step so the box-to-
                            // content — and box-to-"1"-badge — spacing stays exactly as approved.
                            holder.binding.root.background = android.graphics.drawable.InsetDrawable(
                                box, (10f * d).toInt(), (3f * d).toInt(), (10f * d).toInt(), (3f * d).toInt())
                            holder.binding.root.setPadding(
                                (22f * d).toInt(), (10f * d).toInt(), (22f * d).toInt(), (10f * d).toInt())
                        } else {
                            holder.binding.invitationBadge.visibility = View.GONE
                            // Recycled rows must drop the unread box again.
                            holder.binding.root.background = null
                            holder.binding.root.setPadding(
                                (8f * d).toInt(), (8f * d).toInt(), (8f * d).toInt(), (8f * d).toInt())
                        }
                    }
            )

            // Subscribe to account profile changes to update:
            // - avatar
            // - name (will display the jami if name is not setup)
            holder.loader.add(mAccountService.getObservableAccountProfile(account.accountId)
                .observeOn(DeviceUtils.uiScheduler)
                .subscribe({ profile ->
                    val subtitle = getUri(account, ip2ipString)
                    // The dot must show the account's HEALTH, not bare presence: a NOT_SYNCING /
                    // deaf / unregistered account is red here too — a red account with a yellow
                    // dot in "Select account" is a contradiction (白い熊, 2026-07-24).
                    val acct = profile.first
                    val healthPresence = runCatching {
                        val myUris = mAccountService.getAccounts()
                            .filter { it.isJami }
                            .mapNotNull { a -> a.uri?.takeIf(String::isNotEmpty) }.toSet()
                        val stuck = cx.ring.utils.ConnectionHealth
                            .accountStuckConvUris(acct, System.currentTimeMillis(), myUris).isNotEmpty()
                        when {
                            !acct.isRegistered || stuck ||
                                cx.ring.utils.ConnectionWatchdog.accountVerifiedDeaf(acct.accountId) ->
                                net.jami.model.Contact.PresenceStatus.OFFLINE
                            else -> acct.presenceStatus
                        }
                    }.getOrDefault(acct.presenceStatus)
                    holder.binding.logo.setImageDrawable(
                        AvatarDrawable.build(
                            holder.binding.root.context,
                            profile.first,
                            profile.second,
                            true,
                            healthPresence
                        )
                    )
                    holder.binding.title.text = getTitle(profile.first, profile.second)
                    if (holder.binding.title.text == subtitle) {
                        holder.binding.subtitle.visibility = View.GONE
                    } else {
                        holder.binding.subtitle.visibility = View.VISIBLE
                        holder.binding.subtitle.text = subtitle
                    }
                }) { e: Throwable -> Log.e(TAG, "Error loading avatar", e) })
        } else {
            holder.binding.invitationBadge.visibility = View.GONE
            holder.binding.title.setText(
                if (type == TYPE_CREATE_ACCOUNT) R.string.add_ring_account_title
                else R.string.add_sip_account_title
            )
            holder.binding.logo.setImageResource(R.drawable.baseline_add_24)
            holder.binding.logo.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.YELLOW)
            holder.binding.subtitle.visibility = View.GONE
        }
        return view
    }

    override fun getItemViewType(position: Int): Int {
        if (position == super.getCount()) {
            return TYPE_CREATE_ACCOUNT
        }
        return TYPE_ACCOUNT
    }

    override fun getCount(): Int = super.getCount() + 1

    private fun getTitle(account: Account, profile: Profile): String =
        profile.displayName.orEmpty().ifEmpty {
            account.registeredName.ifEmpty {
                account.alias.orEmpty().ifEmpty {
                    if (account.isSip) context.getString(R.string.sip_account)
                    else context.getString(R.string.ring_account)
                }
            }
        }

    private class ViewHolder(val binding: ItemAccountBinding, parentDisposable: CompositeDisposable) {
        val loader = CompositeDisposable().apply { parentDisposable.add(this) }
    }

    private fun getUri(account: Account, defaultNameSip: CharSequence): String =
        if (account.isIP2IP) defaultNameSip.toString() else account.displayUri!!

    companion object {
        private val TAG = AccountAdapter::class.simpleName!!
        const val TYPE_ACCOUNT = 0
        const val TYPE_CREATE_ACCOUNT = 1
    }

}