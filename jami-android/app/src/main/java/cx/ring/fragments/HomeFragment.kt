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

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import cx.ring.utils.Flash
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SearchView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.AutoTransition
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.TransitionManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.Behavior.DragCallback
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cx.ring.R
import cx.ring.account.AccountWizardActivity
import cx.ring.adapters.SmartListAdapter
import cx.ring.client.AccountAdapter
import cx.ring.client.HomeActivity
import cx.ring.mvp.BaseSupportFragment
import cx.ring.utils.BitmapUtils
import cx.ring.utils.DeviceUtils
import cx.ring.viewholders.SmartListViewHolder
import cx.ring.views.AvatarDrawable
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import net.jami.home.HomePresenter
import net.jami.home.HomeView
import net.jami.model.Account
import net.jami.model.AccountConfig
import net.jami.model.Conversation
import net.jami.services.AccountService
import net.jami.services.ConversationFacade
import net.jami.smartlist.ConversationItemViewModel
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.google.android.material.search.SearchView.TransitionState
import com.google.android.material.shape.MaterialShapeDrawable
import cx.ring.databinding.FragHomeBinding
import cx.ring.utils.ActionHelper.openJamiDonateWebPage
import io.reactivex.rxjava3.disposables.Disposable
import net.jami.model.Uri
import net.jami.services.NotificationService

@AndroidEntryPoint
class HomeFragment: BaseSupportFragment<HomePresenter, HomeView>(),
    SearchView.OnQueryTextListener, HomeView {

    private var mBinding: FragHomeBinding? = null
    private var mSmartListFragment: SmartListFragment? = null

    fun refreshSmartListTheme() {
        mSmartListFragment?.refreshTheme()
    }
    private val mDisposable = CompositeDisposable()
    private var mSearchView: SearchView? = null
    private var searchDisposable: Disposable? = null
    private var searchAdapter: SmartListAdapter? = null
    private var pendingAdapter: SmartListAdapter? = null
    private val querySubject = BehaviorSubject.createDefault("")
    private val debouncedQuery = querySubject.debounce { item ->
        if (item.isEmpty()) Observable.empty()
        else Observable.timer(350, TimeUnit.MILLISECONDS)
    }.distinctUntilChanged()

    @Inject
    lateinit var mAccountService: AccountService

    @Inject
    lateinit var mConversationFacade: ConversationFacade

    @Inject
    lateinit var mContactService: net.jami.services.ContactService

    /** Loaded per-account data for the foldable Connection-status dialog (mirrors the monitor). */
    private data class DlgAcct(
        val ac: AccountService.AccountConnections,
        val peers: List<Pair<net.jami.model.ContactViewModel, List<AccountService.DeviceConnection>>>)

    private val searchBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            collapseSearchActionView()
        }
    }

    private val conversationBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            collapsePendingView()
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        requireActivity().onBackPressedDispatcher.let {
            it.addCallback(this, conversationBackPressedCallback)
            it.addCallback(this, searchBackPressedCallback)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FragHomeBinding.inflate(inflater, container, false).apply {

        qrCode.setOnClickListener { goToQRFragment() }
        newSwarm.setOnClickListener { startNewSwarm() }

        // SearchBar is composed of:
        // - Account selection (navigation)
        // - Search bar (search for swarms or for new contacts)
        // - Menu (for settings, about jami)
        searchBar.setNavigationOnClickListener { // Account selection
            mDisposable.add(mAccountService.observableAccountList.firstElement().subscribe { accounts ->
                MaterialAlertDialogBuilder(requireContext(), R.style.ShiroikumaDialog)
                    .setTitle(getString(R.string.account_selection))
                    .setAdapter(
                            AccountAdapter(
                                requireContext(),
                                accounts,
                                mDisposable, mAccountService, mConversationFacade
                            )
                    ) { _, index ->
                        if (index >= accounts.size) // Add account
                            startActivity(Intent(activity, AccountWizardActivity::class.java))
                        else if (mAccountService.currentAccount != accounts[index]) {
                            // Disable account settings menu option when account is loading
                            searchBar.menu.findItem(R.id.menu_account_settings).isEnabled = false
                            mAccountService.currentAccount = accounts[index]
                        }
                    }.show().apply {
                        window?.setBackgroundDrawable(requireContext().getDrawable(R.drawable.dialog_black_yellow))
                    }
            })
        }
        searchView.editText.addTextChangedListener { // Search bar
            querySubject.onNext(it.toString())
        }

        // Inflate Menu and connect it
        searchBar.inflateMenu(R.menu.smartlist_menu)
        cx.ring.utils.FontUtil.apply(searchBar.textView, cx.ring.utils.FontPrefs.SEARCH_HINT)
        searchBar.textView.setHintTextColor(cx.ring.utils.ColorPrefs.getColor(requireContext(), cx.ring.utils.ColorPrefs.SEARCH_HINT))
        searchBar.menu.findItem(R.id.menu_split_view)?.isChecked =
            (activity as? HomeActivity)?.isSplitViewEnabled() ?: true
        // Account online/offline dot: a resizable action view (size set in the UI page) tapped to
        // toggle the account. Its filled<->hollow shape + colour are driven by the onStart
        // registration subscription, which re-subscribes each onStart so the dot keeps updating
        // after navigating away (the avatar presence dot already did; this matches it).
        searchBar.menu.findItem(R.id.menu_account_status)?.actionView = ImageView(requireContext()).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(statusDotSizePx(), statusDotSizePx())
            setImageResource(R.drawable.ic_status_offline)
            // Tap: live connection-status diagnostic (with a Reconnect action inside).
            setOnClickListener { showConnectionStatusDialog() }
            // Long-press: the connectivity help / info page (UI settings is on the hamburger long-press).
            setOnLongClickListener { showConnectionInfoDialog(); true }
        }
        // DHT-proxy lightning: an action view (so it sits tight to the overflow, like the dot — a plain
        // menu icon leaves a wide slot). Tap = recover now (full DHT + re-register); long-press = toggle
        // forced full-DHT. Colour by state via updateLightningIcon().
        val lightningPx = (28 * resources.displayMetrics.density).toInt()
        searchBar.menu.findItem(R.id.menu_lightning)?.actionView = ImageView(requireContext()).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(lightningPx, lightningPx)
            setImageResource(R.drawable.ic_proxy_flash)
            setOnClickListener {   // tap: smart recover (light if proxy good, full if 0-connected)
                cx.ring.utils.ConnectionWatchdog.manualRecover(requireContext(), mAccountService)
                updateLightningIcon(); refreshLightningAtSettle()
                Flash.show(context, "Recovering…")
            }
            setOnLongClickListener {   // long-press: hard reset (always proxy off + re-register)
                cx.ring.utils.ConnectionWatchdog.hardReset(requireContext(), mAccountService)
                updateLightningIcon(); refreshLightningAtSettle()
                Flash.show(context, "Hard reset — full DHT + re-register")
                true
            }
        }
        // Long-press the overflow ("hamburger") menu to jump straight to the UI page.
        // doOnLayout: the action-item views only exist once the bar has been laid out.
        searchBar.doOnLayout {
            searchBar.findViewById<View>(R.id.menu_overflow)?.setOnLongClickListener {
                (activity as? HomeActivity)?.goToAdvancedSettings(openFonts = true)
                true
            }
            // Long-press Sync → pin DHT proxy OFF (full DHT, max reliability). Sync turns blue while
            // pinned; long-press again to release. (Also consumes the long-press, so no white tooltip.)
            searchBar.findViewById<View>(R.id.menu_sync)?.setOnLongClickListener {
                val forced = cx.ring.utils.ConnectionWatchdog.toggleForcedOff(requireContext(), mAccountService)
                updateSyncIcon()
                Flash.show(context, if (forced) "DHT proxy pinned OFF (full DHT) — long-press Sync again to release"
                    else "DHT proxy unpinned (auto)")
                true
            }
        }
        searchBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_sync -> showRecoveryLogDialog()   // tap: show the recovery log

                R.id.menu_account_settings -> (activity as? HomeActivity)?.goToAccountSettings()

                R.id.menu_advanced_settings -> (activity as? HomeActivity)?.goToAdvancedSettings()

                R.id.menu_ui_fonts_colors -> (activity as? HomeActivity)?.goToAdvancedSettings(openFonts = true)

                R.id.menu_about -> (activity as? HomeActivity)?.goToAbout()

                R.id.menu_donate -> openJamiDonateWebPage(requireContext())

                R.id.menu_split_view -> {
                    val ha = activity as? HomeActivity
                    val newState = !(ha?.isSplitViewEnabled() ?: true)
                    ha?.setSplitViewEnabled(newState)
                    it.isChecked = newState
                }
            }
            true
        }

        // Update padding of the list depending on the AppBarLayout height
        appBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            mSmartListFragment?.getRecyclerView()?.setPadding(
                    0,
                    appBar.height - DeviceUtils.getStatusBarHeight(requireContext()),
                    0, 0
            )
        }

        // Make the appBarLayout not going under the status bar.
        appBar.statusBarForeground = MaterialShapeDrawable.createWithElevationOverlay(requireContext())

        // Setup search result list
        searchResult.setHasFixedSize(true)
        searchResult.adapter = SmartListAdapter(null,
                object : SmartListViewHolder.SmartListListeners {
                    override fun onItemClick(item: Conversation) {
                        collapseSearchActionView()
                        (requireActivity() as HomeActivity).startConversation(item.accountId, item.uri)
                    }

                    override fun onItemLongClick(item: Conversation) {}
                },
                mConversationFacade,
                mDisposable
        ).apply { searchAdapter = this }

        searchView.addTransitionListener { _, previousState, newState ->
            // When using the SearchView, we have to :
            // - Manage back press
            // - Manage search results

            if (newState === TransitionState.SHOWN) { // Shown
                // Hide AppBar and SmartList to avoid weird animation
                fragmentContainer.isVisible = false
                appBar.isVisible = false

                searchBackPressedCallback.isEnabled = true
            } else if (previousState === TransitionState.SHOWN) { // Hiding
                // Make SmartList and appbar visible again
                fragmentContainer.isVisible = true
                appBar.isVisible = true

                searchBackPressedCallback.isEnabled = false
            }

            if (newState === TransitionState.HIDDEN) { // Hidden
                // Hide floating button to avoid weird animation
                newSwarmFab.isVisible = true

                searchDisposable?.dispose()
                querySubject.onNext("")
                searchAdapter?.update(ConversationFacade.ConversationList())
                searchDisposable = null
            } else if (previousState === TransitionState.HIDDEN) { // Showing
                searchView.toolbar.navigationIcon = AppCompatResources.getDrawable(
                    requireContext(), R.drawable.baseline_arrow_back_24
                )
                newSwarmFab.isVisible = false
                startSearch()
            }
        }

        // Setup floating button.
        newSwarmFab.setOnClickListener { expandSearchActionView() }

        // Setup donation card
        donationCard.donationCard.visibility = View.GONE
        donationCard.donationCard.setOnClickListener {
            openJamiDonateWebPage(requireContext())
        }
        donationCard.donationCardDonateButton.setOnClickListener {
            openJamiDonateWebPage(requireContext())
        }
        donationCard.donationCardNotNowButton.setOnClickListener {
            presenter.setDonationReminderDismissed()
        }

        // Setup invitation card adapter.
        invitationCard.pendingList.adapter = SmartListAdapter(null,
                object : SmartListViewHolder.SmartListListeners {
                    override fun onItemClick(item: Conversation) {
                        (requireActivity() as HomeActivity).startConversation(item.accountId, item.uri)
                    }

                    override fun onItemLongClick(item: Conversation) {
                        displayConversationRequestDialog(item)
                    }
                },
                mConversationFacade,
                mDisposable
        ).apply { pendingAdapter = this }

        // Setup invitation card
        invitationCard.invitationGroup.setOnClickListener {
            expandPendingView()
        }

        // Return to search
        invitationCard.pendingToolbar.setNavigationOnClickListener { collapsePendingView() }

        mBinding = this
    }.root

    private fun displayConversationRequestDialog(conversation: Conversation) {
        val request = conversation.request ?: return
        if (request.mode == Conversation.Mode.OneToOne)
            MaterialAlertDialogBuilder(requireContext())
                .setItems(R.array.swarm_request_one_to_one_actions) { _, which ->
                    when (which) {
                        0 -> mConversationFacade.acceptRequest(conversation)
                        1 -> mConversationFacade
                            .discardRequest(conversation.accountId, conversation.uri)
                        2 -> mConversationFacade
                            .blockConversation(conversation.accountId, conversation.uri)
                    }
                }.show()
        else
            MaterialAlertDialogBuilder(requireContext())
                .setItems(R.array.swarm_request_group_actions) { _, which ->
                    when (which) {
                        0 -> mConversationFacade.acceptRequest(conversation)
                        1 -> mConversationFacade
                            .discardRequest(conversation.accountId, conversation.uri)
                    }
                }.show()
    }

    private fun startSearch() {
        searchDisposable?.dispose()
        searchDisposable = mConversationFacade.getSearchResults(debouncedQuery)
            .observeOn(DeviceUtils.uiScheduler)
            .subscribe { searchAdapter?.update(it) }
            .apply { mDisposable.add(this) }
    }

    /**
     * Expand the appBarLayoutBottom to give fixed space between it and fragmentList.
     */
    private fun updateAppBarLayoutBottomPadding(hasInvites: Boolean) {
        mBinding?.appBarContainer?.updatePadding(top = 0, bottom = if (hasInvites)
            resources.getDimensionPixelSize(R.dimen.bottom_sheet_radius) else 0)
        if (hasInvites)
            mSmartListFragment?.scrollToTop()
    }

    private fun expandPendingView() {
        val binding = mBinding ?: return

        // Transitions to animate the changes
        // Make the search bar slide down
        TransitionManager.beginDelayedTransition(binding.searchBar, Slide())
        // Make the invitation card expand.
        TransitionManager.beginDelayedTransition(
            binding.invitationCard.invitationGroup,
            ChangeBounds().setInterpolator(DecelerateInterpolator())
        )

        // Make the invitation card take all the height.
        binding.appBar.updateLayoutParams {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }

        // Adapt the margins of the invitation card.
        requireContext().resources.getDimensionPixelSize(R.dimen.bottom_sheet_radius).let {
            (binding.invitationCard.invitationGroup.layoutParams as ViewGroup.MarginLayoutParams)
                .setMargins(it, it, it, 2*it)
        }

        // Enable invitation pending list scroll (remove side effect with appbar behavior).
        (binding.appBar.layoutParams as CoordinatorLayout.LayoutParams).behavior = null

        // Hide everything unneeded.
        binding.donationCard.donationCard.isVisible = false
        binding.searchBar.isVisible = false
        binding.invitationCard.invitationSummary.isVisible = false
        binding.fragmentContainer.isVisible = false
        binding.newSwarmFab.isVisible = false

        // Display pending list.
        binding.invitationCard.pendingListGroup.isVisible = true

        // Enable back press.
        conversationBackPressedCallback.isEnabled = true

        val insetsCompat = ViewCompat.getRootWindowInsets(binding.invitationCard.invitationGroup) ?: return
        val insets = insetsCompat.getInsets(WindowInsetsCompat.Type.systemBars())
        binding.appBar.updatePadding(bottom = insets.bottom)
    }

    fun collapsePendingView() {
        val binding = mBinding ?: return

        // Animate back to search
        // Make the search bar slide up
        TransitionManager.beginDelayedTransition(
            binding.searchBar,
            Slide().setInterpolator(DecelerateInterpolator())
        )
        // Make the invitation card collapse.
        TransitionManager.beginDelayedTransition(
            binding.appBar,
            ChangeBounds().setInterpolator(DecelerateInterpolator())
        )
        // Make the invitation card text fade in.
        TransitionManager.beginDelayedTransition(
            binding.invitationCard.invitationSummary,
            Fade()
        )

        binding.appBar.updatePadding(bottom = 0)

        // Make the invitation card wrap content (not take all space available anymore).
        binding.appBar.updateLayoutParams {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        // Adapt the margins of the invitation card.
        requireContext().resources.getDimensionPixelSize(R.dimen.bottom_sheet_radius).let {
            (binding.invitationCard.invitationGroup.layoutParams as ViewGroup.MarginLayoutParams)
                .setMargins(it, 0, it, 0)
        }

        disableAppBarScroll()

        // Show everything needed.
        binding.donationCard.donationCard.isVisible = presenter.donationCardIsVisible
        binding.searchBar.isVisible = true
        binding.invitationCard.invitationSummary.isVisible = true
        binding.newSwarmFab.isVisible = true
        binding.fragmentContainer.isVisible = true

        // Hide pending list.
        binding.invitationCard.pendingListGroup.isVisible = false

        // Disable back press.
        conversationBackPressedCallback.isEnabled = false
    }

    // Will hide the floating button when scrolling down and show it when scrolling up.
    private val fabScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val canScrollUp = recyclerView.canScrollVertically(-1)
            val isExtended = mBinding!!.newSwarmFab.isExtended
            if (dy > 0 && isExtended) { // Going down
                mBinding!!.newSwarmFab.shrink()
            } else if ((dy < 0 || !canScrollUp) && !isExtended) { // Going up
                mBinding!!.newSwarmFab.extend()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(mBinding!!.searchView) { v, insets ->
            if (v.paddingTop > 0) {
                // Ensure searchView ignores top insets for proper fullscreen appearance
                v.setPadding(v.paddingLeft, 0, v.paddingRight, v.paddingBottom)
            }
            insets
        }

        val accessibilityManager =
            requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        adjustLayoutForAccessibility(accessibilityManager.isEnabled )

        accessibilityManager.addAccessibilityStateChangeListener { isEnabled ->
            adjustLayoutForAccessibility(isEnabled)
        }

        mSmartListFragment = mBinding!!.fragmentContainer.getFragment()

        disableAppBarScroll()

        // Subscribe on fragmentContainer to add scroll listener on the recycler view.
        mSmartListFragment?.viewLifecycleOwnerLiveData?.observe(viewLifecycleOwner) {
            it.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    mSmartListFragment?.getRecyclerView()?.addOnScrollListener(fabScrollListener)
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    mSmartListFragment?.getRecyclerView()?.removeOnScrollListener(fabScrollListener)
                }
            })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mSmartListFragment = null
        pendingAdapter = null
        searchAdapter = null
        mBinding = null
        mDisposable.dispose()
    }

    // Foreground online-recovery: while the chat list is open, run the watchdog every minute (faster
    // than the background tick) and once immediately on open, so a wedge is caught + recovered in ~1 min.
    private val mFgWatchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mFgWatchdogRunnable = object : Runnable {
        override fun run() {
            context?.let { cx.ring.utils.ConnectionWatchdog.tick(it, mAccountService) }
            updateLightningIcon(); updateSyncIcon()
            mFgWatchdogHandler.postDelayed(this, 60_000L)
        }
    }

    /** Lightning-icon tint by DHT-proxy state: yellow = on, blue = auto-off (recovery/charging/wedge),
     *  red = forced off. */
    /** Lightning: yellow when idle, blue while a recover/reset is settling. */
    private fun updateLightningIcon() {
        val iv = mBinding?.searchBar?.menu?.findItem(R.id.menu_lightning)?.actionView as? ImageView ?: return
        iv.setColorFilter(if (cx.ring.utils.ConnectionWatchdog.isRecovering()) 0xFF0000FF.toInt() else 0xFFFFFF00.toInt())
    }

    /** Sync: blue while DHT proxy is pinned off (Sync long-press), else yellow. */
    private fun updateSyncIcon() {
        val item = mBinding?.searchBar?.menu?.findItem(R.id.menu_sync) ?: return
        val ctx = context ?: return
        val color = if (cx.ring.utils.UiPrefs.isProxyForcedOff(ctx)) 0xFF0000FF.toInt() else 0xFFFFFF00.toInt()
        androidx.core.view.MenuItemCompat.setIconTintList(item, android.content.res.ColorStateList.valueOf(color))
    }

    /** Flip the lightning back to yellow once the recover settle window passes. */
    private fun refreshLightningAtSettle() {
        mFgWatchdogHandler.postDelayed({ updateLightningIcon() }, 31_000L)
    }

    /** Connectivity help / info page (Account-dot long-press). */
    private fun showConnectionInfoDialog() {
        val ctx = context ?: return
        val dens = resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()
        // All settable in “UI fonts & colours” → “Connectivity help page”.
        val bodyC = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.INFO_BODY)        // body — yellow by default
        val headC = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.INFO_HEADING)     // headings — white by default
        val pillText = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.INFO_PILL_TEXT)
        val pillBorder = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.INFO_PILL_BORDER)
        val pillFill = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.INFO_PILL_FILL)
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        // Turn {Button name} markers into real pills that look like the Contact-live-monitor buttons.
        fun pillify(s: String): CharSequence {
            if (!s.contains('{')) return s
            val sb = android.text.SpannableStringBuilder()
            var i = 0
            while (i < s.length) {
                val open = s.indexOf('{', i)
                if (open < 0) { sb.append(s.substring(i)); break }
                sb.append(s.substring(i, open))
                val close = s.indexOf('}', open)
                if (close < 0) { sb.append(s.substring(open)); break }
                val st = sb.length
                sb.append(s.substring(open + 1, close))
                sb.setSpan(cx.ring.views.PillSpan(pillText, pillBorder, pillFill, dens), st, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = close + 1
            }
            return sb
        }
        fun heading(iconRes: Int, title: String) {
            root.addView(android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(17), 0, dp(5))
                addView(ImageView(ctx).apply {
                    setImageResource(iconRes); setColorFilter(bodyC)
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
                })
                addView(android.widget.TextView(ctx).apply {
                    text = title; setTextColor(headC); setTypeface(typeface, android.graphics.Typeface.BOLD)
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                    cx.ring.utils.FontUtil.apply(this, cx.ring.utils.FontPrefs.INFO_HEADING)
                })
            })
        }
        fun line(s: String) = root.addView(android.widget.TextView(ctx).apply {
            text = pillify(s); setTextColor(bodyC); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(dp(2).toFloat(), 1f); setPadding(dp(34), dp(2), 0, dp(4))
            cx.ring.utils.FontUtil.apply(this, cx.ring.utils.FontPrefs.INFO_BODY)
        })
        // "lead — description", lead in bold, rest body-coloured; {pills} in either part become buttons.
        fun action(lead: String, desc: String) = root.addView(android.widget.TextView(ctx).apply {
            setTextColor(bodyC); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(dp(2).toFloat(), 1f); setPadding(dp(34), dp(2), 0, dp(4))
            text = android.text.SpannableStringBuilder().apply {
                val st = length; append(lead)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("   —   ").append(pillify(desc))
            }
            cx.ring.utils.FontUtil.apply(this, cx.ring.utils.FontPrefs.INFO_BODY)
        })
        fun banner(s: String) = root.addView(android.widget.TextView(ctx).apply {
            text = s; setTextColor(headC); setTypeface(typeface, android.graphics.Typeface.BOLD)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(0, dp(20), 0, dp(5))
            cx.ring.utils.FontUtil.apply(this, cx.ring.utils.FontPrefs.INFO_HEADING)
        })

        heading(R.drawable.baseline_autorenew_white_24dp, "Sync   ↻")
        action("Tap", "show the connection / recovery log.")
        action("Long-press", "pin DHT proxy OFF (full DHT, maximum reliability, ignores battery). {Sync ↻} turns blue while pinned; long-press again to release.")

        heading(R.drawable.ic_status_online, "Account dot   ●")
        action("Tap", "connection status — your accounts and contacts, and who's connected.")
        action("Long-press", "this help page.")
        line("The dot shows your selected account: online (filled) / offline (hollow).")

        heading(R.drawable.ic_proxy_flash, "Lightning   ⚡")
        action("Tap", "smart recover — re-registers to fix a stuck link; drops to full DHT only if nothing is connected. Blue while recovering.")
        action("Long-press", "hard reset — force full DHT + re-register no matter what. The big hammer.")
        line("Account-WIDE: it re-registers every account and affects all your chats, not one contact.")

        heading(R.drawable.ic_status_online, "A contact's avatar   ◎")
        line("Tap a contact or group avatar to open a LIVE monitor for just them — it re-checks itself every 2 s while open, so there's nothing to refresh by hand.")
        line("Channels are colour-coded: connecting → negotiating (ICE) → securing (TLS) → connected, with device IDs and uptime.")
        line("{Message ping ⌁}   —   Jami has no per-contact connect button, so this sends a tiny ⌁ probe; only real content makes the daemon open a direct channel. The per-CONTACT nudge.")
        line("If your last message is still undelivered, {Message ping ⌁} won't just fire again (the daemon is already retrying) — it says so and points you to {Lightning ⚡}, since one stuck contact usually means YOUR link.")
        line("Per-contact ({Message ping ⌁}) re-attempts ONE contact. Account-wide ({Lightning ⚡}) re-registers your WHOLE account. Try the ping first; escalate to the lightning if it doesn't land.")

        banner("When something's wrong")
        line("•   A message won't go / a contact is stuck → tap their avatar → {Message ping ⌁}. Reaches ICE but stalls = online but NAT-blocked. Never connects = likely offline.")
        line("•   Already pinged, still undelivered → don't keep pinging; escalate to {Lightning ⚡} (tap = smart recover). It recovers your WHOLE account — all chats — not just this contact.")
        line("•   Everything stuck (0 connected) → {Lightning ⚡} long-press (hard reset).")
        line("•   Guaranteed delivery, battery aside → {Sync ↻} long-press (pin proxy off).")
        line("•   Dot colours:  yellow = a live connection now  ·  blue = online, no open pipe  ·  red = offline.")

        cx.ring.utils.DialogTheme.builder(ctx)
            .setTitle("Connectivity — how the icons work")
            .setView(android.widget.ScrollView(ctx).apply { addView(root) })
            .setPositiveButton("Got it", null)
            .show().let { cx.ring.utils.DialogTheme.theme(it, ctx) }
    }

    override fun onStart() {
        super.onStart()
        activity?.intent?.let { handleIntent(it) }
        // Online-recovery: an immediate check on open, then a 1-minute foreground cadence.
        context?.let { cx.ring.utils.ConnectionWatchdog.tick(it, mAccountService) }
        updateLightningIcon(); updateSyncIcon()
        mFgWatchdogHandler.removeCallbacks(mFgWatchdogRunnable)
        mFgWatchdogHandler.postDelayed(mFgWatchdogRunnable, 60_000L)

        // Enable account settings menu option when an account is loaded
        mDisposable.add(mAccountService.currentAccountSubject
            .observeOn(DeviceUtils.uiScheduler)
            .subscribe {
                mBinding?.newSwarm?.isVisible = !it.isSip
                mBinding?.searchBar?.menu?.findItem(R.id.menu_account_settings)?.isEnabled = true
            }
        )

        // Subscribe on invitation pending list to show a badge counter
        mDisposable.add(mAccountService
            .currentAccountSubject
            .switchMap { account ->
                account.getPendingSubject()
                    .switchMap { list ->
                        Log.w(TAG, "setBadge getPendingSubject ${list.size}")
                        if (list.isEmpty()) Observable.just(Pair(emptyList(), emptyList()))
                        else mConversationFacade.observeConversations(
                            account, list.take(3), false
                        ).map { Pair(it, list) }
                    }
            }
            .observeOn(DeviceUtils.uiScheduler)
            .subscribe { count ->
                // Collapse pending view if there is no more pending invitations
                if(count.first.isEmpty()) {
                    collapsePendingView()
                }
                setInvitationBadge(count.second, count.first)
            }
        )

        // Subscribe on current account to display avatar on navigation (searchbar)
        mDisposable.add(mAccountService
            .currentAccountSubject
            .switchMap { mAccountService.getObservableAccountProfile(it.accountId) }
            .observeOn(DeviceUtils.uiScheduler)
            .subscribe { profile ->
                val binding = mBinding ?: return@subscribe
                binding.searchBar.navigationIcon =
                    BitmapUtils.withPadding(
                        AvatarDrawable.build(
                            binding.root.context,
                            profile.first,
                            profile.second,
                            true,
                            profile.first.presenceStatus
                        ).setInSize(
                            TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP,
                                54f,
                                resources.displayMetrics
                            ).toInt()
                        ),
                        TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            2f,
                            resources.displayMetrics
                        ).toInt()
                    )
                binding.searchView.toolbar.navigationIcon = AppCompatResources.getDrawable(
                    binding.root.context, R.drawable.baseline_arrow_back_24
                )
            }
        )

        // Drive the account online/offline dot (filled<->hollow + colour). Re-subscribed here so it
        // keeps tracking the registration state after the fragment is stopped and restarted.
        mDisposable.add(mAccountService.currentAccountSubject
            .switchMap { acc -> acc.registrationStateObservable.map { acc } }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { acc -> applyStatusDot(acc.isRegistered) })

        // Health alarm: while the home screen is shown, poll connectivity across accounts and ring the
        // dot red ONLY for a genuine hidden problem — an account registered but unable to sync for
        // >2.5min (ConnectionHealth.NOT_SYNCING). Transient sync churn never triggers it. Foreground-only.
        mDisposable.add(mAccountService.monitorAllConnections(true)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ accounts ->
                val now = System.currentTimeMillis()
                val myUris = accounts.mapNotNull { it.uri.takeIf(String::isNotEmpty) }.toSet()
                // RED ring = a genuine problem (an account with an outgoing message stuck undelivered —
                // the ○ that never fills — in a same-device conversation). BLUE ring = some account is
                // still connecting (in progress, not a problem). No ring = all healthy. Normal churn and
                // offline-contact waits never ring it; plain offline is shown by the hollow dot icon.
                var problems = 0; var connecting = 0
                accounts.forEach { a ->
                    val cn = a.peers.any { (_, c) -> c.any { it.status == AccountService.ConnectionStatus.Connected } }
                    val at = a.peers.any { (_, c) -> c.any { it.status != AccountService.ConnectionStatus.Connected } }
                    val stuckMsg = mAccountService.getAccount(a.accountId)
                        ?.let { cx.ring.utils.ConnectionHealth.accountStuckConvUris(it, now, myUris).isNotEmpty() } ?: false
                    when (cx.ring.utils.ConnectionHealth.classify(a.registered, cn, stuckMsg, at)) {
                        cx.ring.utils.ConnectionHealth.Health.NOT_SYNCING -> problems++
                        cx.ring.utils.ConnectionHealth.Health.CONNECTING -> connecting++
                        else -> {}
                    }
                }
                if (problems != dotAlarmCount || connecting != dotConnectingCount) {
                    dotAlarmCount = problems
                    dotConnectingCount = connecting
                    applyStatusDot(mAccountService.currentAccount?.isRegistered == true)
                }
            }, {}))

        if (mBinding!!.searchView.isShowing)
            startSearch()
    }

    /** Account dot side length in px, from the user-set scale (UI page) over the 24dp base. */
    private fun statusDotSizePx(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        24f * cx.ring.utils.UiPrefs.getStatusDotScale(requireContext()),
        resources.displayMetrics
    ).toInt()

    /** >0 when an account has a stuck (undelivered) message — rings the account dot RED as an alarm. */
    private var dotAlarmCount = 0
    /** >0 when an account is still connecting (in progress) — rings the dot BLUE (only if no red). */
    private var dotConnectingCount = 0

    /** Set the dot's shape (online = filled, offline = hollow) and colour (yellow until overridden),
     *  plus a thick red alarm ring around it when there are dead peer links. */
    private fun applyStatusDot(online: Boolean) {
        val dot = mBinding?.searchBar?.menu?.findItem(R.id.menu_account_status)?.actionView as? ImageView ?: return
        dot.setImageResource(if (online) R.drawable.ic_status_online else R.drawable.ic_status_offline)
        val role = if (online) cx.ring.utils.ColorPrefs.STATUS_ONLINE else cx.ring.utils.ColorPrefs.STATUS_OFFLINE
        dot.imageTintList = android.content.res.ColorStateList.valueOf(
            if (cx.ring.utils.ColorPrefs.isSet(dot.context, role))
                cx.ring.utils.ColorPrefs.getColor(dot.context, role)
            else 0xFFFFFF00.toInt())
        // Ring: RED for a stuck-message problem, else BLUE while connecting, else none. Both use the
        // settable monitor colours so they match the monitor screen.
        val ringColor = when {
            dotAlarmCount > 0 -> cx.ring.utils.ColorPrefs.getColor(dot.context, cx.ring.utils.ColorPrefs.MONITOR_PROBLEM)
            dotConnectingCount > 0 -> cx.ring.utils.ColorPrefs.getColor(dot.context, cx.ring.utils.ColorPrefs.MONITOR_CONNECTING)
            else -> 0
        }
        if (ringColor != 0) {
            val dens = dot.resources.displayMetrics.density
            dot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke((2 * dens).toInt(), ringColor)
            }
            val p = (3 * dens).toInt()
            dot.setPadding(p, p, p, p)
        } else {
            dot.background = null
            dot.setPadding(0, 0, 0, 0)
        }
    }

    /** Re-apply the dot size + colour after they are changed in the UI page (live refresh). */
    fun refreshStatusDot() {
        val dot = mBinding?.searchBar?.menu?.findItem(R.id.menu_account_status)?.actionView as? ImageView ?: return
        dot.updateLayoutParams { width = statusDotSizePx(); height = statusDotSizePx() }
        applyStatusDot(mAccountService.currentAccount?.isRegistered == true)
    }

    /** The open connection-status dialog, tracked so it can be dismissed when we navigate away (e.g.
     *  the monitor's Recovery pill → UI settings) instead of lingering overlaid on the next screen. */
    private var mConnStatusDialog: androidx.appcompat.app.AlertDialog? = null

    /** Dismiss the connection-status dialog if showing (called by HomeActivity before navigating). */
    fun dismissConnStatusDialog() { mConnStatusDialog?.dismiss(); mConnStatusDialog = null }

    /** Tap the account dot → live connection-status diagnostic + a Reconnect action.
     *  Surfaces the one signal that distinguishes a healthy account from the
     *  "registered but swarms dead" state: the daemon's live peer-connection count. */
    private fun showConnectionStatusDialog() {
        val ctx = context ?: return
        val account = mAccountService.currentAccount ?: run {
            Flash.show(ctx, "No account", Toast.LENGTH_SHORT); return
        }
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(ctx).apply { setTextColor(0xFFFFFF00.toInt()); text = "Checking…" })
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(container) }
        // Custom title: "Connection status" on the left, a "Recovery" pill top-right → Online-recovery settings.
        val d = ctx.resources.displayMetrics.density
        val recoveryPill = TextView(ctx).apply {
            text = "ⓘ Recovery"   // ⓘ marks it as the informative Online-recovery settings button
            setTextColor(0xFFFFFF00.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
            background = AppCompatResources.getDrawable(ctx, R.drawable.dialog_black_yellow)
        }
        val syncNowPill = TextView(ctx).apply {
            text = "Sync now"
            setTextColor(0xFFFFFF00.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
            background = AppCompatResources.getDrawable(ctx, R.drawable.dialog_black_yellow)
        }
        val titleRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(TextView(ctx).apply {
                text = "Connection status"
                setTextColor(0xFFFFFF00.toInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(syncNowPill, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * d).toInt() })
            addView(recoveryPill)
        }
        val dialog = MaterialAlertDialogBuilder(ctx, R.style.ShiroikumaDialog)
            .setCustomTitle(titleRow)
            .setView(scroll)
            .setNeutralButton("Monitor", null)  // click wired below so it does NOT dismiss the dialog
            .setNegativeButton("Close", null)
            .create()
        mConnStatusDialog = dialog
        syncNowPill.setOnClickListener { syncAllWithFeedback() }   // top pill = hard sync (dialog stays open)
        recoveryPill.setOnClickListener { (activity as? HomeActivity)?.goToAdvancedSettings(openFonts = true) }
        val dis = CompositeDisposable()
        dialog.setOnDismissListener { dis.clear(); mConnStatusDialog = null }
        // Account avatars (by accountId) for the dialog rows, loaded async; rebuild on either source.
        val avatars = HashMap<String, android.graphics.drawable.Drawable>()
        val contactAvatars = HashMap<String, android.graphics.drawable.Drawable>()
        // Foldable at both levels: a key is an accountId (account fold) or "accountId|peerUri" (contact
        // fold). Empty = everything folded by default.
        val expanded = HashSet<String>()
        var loaded: List<DlgAcct> = emptyList()
        fun rebuild() {
            populateConnectionStatusView(container, loaded, System.currentTimeMillis(), avatars, contactAvatars, expanded) { key ->
                if (!expanded.remove(key)) expanded.add(key); rebuild()
            }
        }
        dis.add(mAccountService.observableAccountList
            .switchMap { accs -> io.reactivex.rxjava3.core.Observable.merge(
                accs.filter { it.isJami }.map { mAccountService.getObservableAccountProfile(it.accountId) }) }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (account, profile) ->
                avatars[account.accountId] = AvatarDrawable.build(ctx, account, profile, true, account.presenceStatus)
                rebuild()
            }, { /* ignore */ }))
        // Load each account's full contact roster + connections (same pipeline as the monitor), so the
        // dialog can show the same per-account detail when an account is unfolded.
        dis.add(mAccountService.monitorAllConnections(true)
            .switchMapSingle { accounts ->
                val singles = accounts.map { ac ->
                    val account = mAccountService.getAccount(ac.accountId)
                    val connByUri = HashMap<String, List<AccountService.DeviceConnection>>()
                    ac.peers.forEach { (peer, conns) -> connByUri[peer] = conns }
                    val contactObjs = LinkedHashMap<String, net.jami.model.Contact>()
                    account?.contacts?.values?.forEach { c -> if (!c.isUser) c.uri.rawRingId?.let { contactObjs[it] = c } }
                    connByUri.keys.forEach { uri -> if (uri !in contactObjs) account?.getContactFromCache(uri)?.let { contactObjs[uri] = it } }
                    if (contactObjs.isEmpty()) io.reactivex.rxjava3.core.Single.just(DlgAcct(ac, emptyList()))
                    else mContactService.getLoadedContact(ac.accountId, contactObjs.values, true).map { cvms ->
                        DlgAcct(ac, cvms.map { cvm ->
                            val uri = cvm.contact.uri.rawRingId ?: cvm.contact.uri.toString()
                            cvm to (connByUri[uri] ?: emptyList())
                        })
                    }   // resilience: a slow/stalled contact load (an account in a poor state) must not
                        // blank the whole dialog. monitorAllConnections re-emits every 2 s and switchMap
                        // cancels any zip that has not finished — so a >2 s contact load leaves `loaded`
                        // empty: zero rows AND a false "all healthy". Cap each account's load so the zip
                        // always completes inside the poll window; the header + health come from ac.peers.
                        .timeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .onErrorReturn { DlgAcct(ac, emptyList()) }
                }
                if (singles.isEmpty()) io.reactivex.rxjava3.core.Single.just(emptyList<DlgAcct>())
                else io.reactivex.rxjava3.core.Single.zip(singles) { arr -> arr.map { it as DlgAcct } }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ data ->
                loaded = data
                rebuild()
            }, { e ->
                container.removeAllViews()
                container.addView(TextView(ctx).apply { setTextColor(0xFFFF5252.toInt()); text = "Error: ${e.message}" })
            }))
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            AppCompatResources.getDrawable(ctx, R.drawable.dialog_black_yellow))
        styleDialogButton(dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE))
        styleDialogButton(dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL))
        styleDialogButton(dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE))
        // Open the monitor WITHOUT dismissing this dialog, so Back from the monitor returns here.
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), cx.ring.client.ConnectionMonitorActivity::class.java))
        }
    }

    /** Black fill, yellow text + 2dp yellow border — matches the app's black/yellow chrome. */
    private fun styleDialogButton(button: android.widget.Button?) {
        val b = button ?: return
        val yellow = 0xFFFFFF00.toInt()
        b.setTextColor(yellow)
        if (b is com.google.android.material.button.MaterialButton) {
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF000000.toInt())
            b.strokeColor = android.content.res.ColorStateList.valueOf(yellow)
            b.strokeWidth = (2 * resources.displayMetrics.density).toInt()
        } else {
            b.setBackgroundResource(R.drawable.dialog_black_yellow)
        }
    }

    /** Render the all-accounts connection status into [container] with avatars: a verdict line, then
     *  one row per account (avatar + name + TRUE health). Transient sync connections are not failures,
     *  so health = registered + actually-syncing (ConnectionHealth.classify); only OFFLINE / NOT_SYNCING
     *  read as a problem. */
    private fun populateConnectionStatusView(
        container: android.widget.LinearLayout,
        loaded: List<DlgAcct>,
        now: Long,
        avatars: Map<String, android.graphics.drawable.Drawable>,
        contactAvatars: MutableMap<String, android.graphics.drawable.Drawable>,
        expanded: Set<String>,
        onToggle: (String) -> Unit,
    ) {
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        val H = cx.ring.utils.ConnectionHealth
        val red = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.MONITOR_PROBLEM)
        val amber = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.MONITOR_CONNECTING)
        val healthyCol = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.MONITOR_HEALTHY)
        val connectedCol = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.MONITOR_CONNECTED)
        val idleCol = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.MONITOR_IDLE)
        val offlineCol = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.MONITOR_OFFLINE)
        val grey = 0xFFAAAAAA.toInt()
        container.removeAllViews()
        fun text(s: CharSequence, color: Int, bold: Boolean = false, sizeSp: Float = 14f, padL: Int = 0, padT: Int = 0) =
            TextView(ctx).apply {
                text = s; setTextColor(color); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding((padL * d).toInt(), (padT * d).toInt(), 0, 0)
            }
        val myUris = loaded.mapNotNull { it.ac.uri.takeIf(String::isNotEmpty) }.toSet()
        val myOnlineUris = loaded.filter { it.ac.registered && it.ac.uri.isNotEmpty() }.map { it.ac.uri }.toSet()
        // uri -> display name (account names + loaded contact names), for naming the stuck chat.
        val nameOf = HashMap<String, String>()
        loaded.forEach { da ->
            if (da.ac.uri.isNotEmpty()) nameOf[da.ac.uri] = da.ac.name
            da.peers.forEach { (cvm, _) -> cvm.contact.uri.rawRingId?.let { nameOf[it] = cvm.displayName } }
        }
        fun healthOf(da: DlgAcct): cx.ring.utils.ConnectionHealth.Health {
            // Use the raw connection list (ac.peers), not the contact-keyed da.peers — so health stays
            // accurate even when the contact roster timed out (da.peers empty but connections known).
            val cn = da.ac.peers.any { (_, c) -> c.any { it.status == AccountService.ConnectionStatus.Connected } }
            val at = da.ac.peers.any { (_, c) -> c.any { it.status != AccountService.ConnectionStatus.Connected } }
            val stuck = mAccountService.getAccount(da.ac.accountId)
                ?.let { H.accountStuckConvUris(it, now, myUris).isNotEmpty() } ?: false
            return H.classify(da.ac.registered, cn, stuck, at)
        }
        val ranked = loaded.sortedBy { if (H.isProblem(healthOf(it))) 0 else 1 }   // problems on top
        val problems = loaded.count { H.isProblem(healthOf(it)) }
        container.addView(text(
            if (problems > 0) "⚠ $problems account(s) need attention" else "✓ All accounts healthy",
            if (problems > 0) red else healthyCol, bold = true, sizeSp = 15f))
        container.addView(text("Network: ${networkLabel() ?: "none"}", grey, sizeSp = 12f))
        for (da in ranked) {
            val ac = da.ac
            val health = healthOf(da)
            val word = when (health) {
                cx.ring.utils.ConnectionHealth.Health.HEALTHY -> "online · healthy"
                cx.ring.utils.ConnectionHealth.Health.CONNECTING -> "connecting…"
                cx.ring.utils.ConnectionHealth.Health.NOT_SYNCING -> "NOT SYNCING"
                cx.ring.utils.ConnectionHealth.Health.OFFLINE -> "OFFLINE"
            }
            val col = when (health) {
                cx.ring.utils.ConnectionHealth.Health.HEALTHY -> healthyCol
                cx.ring.utils.ConnectionHealth.Health.CONNECTING -> amber
                else -> red
            }
            val acctExpanded = ac.accountId in expanded
            val stuckUris = mAccountService.getAccount(ac.accountId)
                ?.let { H.accountStuckConvUris(it, now, myUris) } ?: emptyList()
            // Account header: triangle + avatar + name/health, tap to fold/unfold.
            val header = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (12 * d).toInt(), 0, 0)
                setOnClickListener { onToggle(ac.accountId) }
            }
            header.addView(text(if (acctExpanded) "▼" else "▶", col, bold = true, sizeSp = 13f).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * d).toInt() }
            })
            val s = (40 * d).toInt()
            header.addView(ImageView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(s, s).apply { marginEnd = (10 * d).toInt() }
                avatars[ac.accountId]?.let { setImageDrawable(it) }
            })
            val cnt = da.ac.peers.count { (_, c) -> c.any { it.status == AccountService.ConnectionStatus.Connected } }
            val stuckWord = if (stuckUris.isNotEmpty()) " (${stuckUris.size} msg stuck)" else ""
            header.addView(text("${ac.name} — $word$stuckWord · $cnt connected", col))
            container.addView(header)
            // Stuck-message sub-rows — always shown (the problem the user must see).
            for (n in stuckUris.map { nameOf[it] ?: it.take(8) }.distinct())
                container.addView(text("⚠ message not delivered → $n", red, sizeSp = 13f, padL = 48, padT = 4))
            if (acctExpanded) {
                val sorted = da.peers.sortedWith(compareBy({ (_, conns) ->
                    when { conns.any { it.status == AccountService.ConnectionStatus.Connected } -> 0
                           conns.isNotEmpty() -> 1; else -> 2 }
                }, { (cvm, _) -> cvm.displayName.lowercase() }))
                for ((cvm, conns) in sorted) {
                    val connected = conns.any { it.status == AccountService.ConnectionStatus.Connected }
                    val attempting = conns.isNotEmpty() && !connected
                    val peerKey = cvm.contact.uri.rawRingId ?: cvm.contact.uri.uri
                    val (st, sc) = when {
                        connected -> "connected" to connectedCol
                        attempting -> "connecting…" to idleCol
                        peerKey in myOnlineUris || cvm.presence != net.jami.model.Contact.PresenceStatus.OFFLINE ->
                            "reachable" to connectedCol
                        else -> "offline" to offlineCol
                    }
                    val ckey = "${ac.accountId}|$peerKey"
                    val cExpanded = ckey in expanded
                    // Contact row: small fold arrow + small avatar + name + status, tap to fold/unfold.
                    val crow = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding((40 * d).toInt(), (6 * d).toInt(), 0, 0)
                        setOnClickListener { onToggle(ckey) }
                    }
                    crow.addView(text(if (cExpanded) "▾" else "▸", sc, bold = true, sizeSp = 11f).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (6 * d).toInt() }
                    })
                    val cs = (28 * d).toInt()
                    crow.addView(ImageView(ctx).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(cs, cs).apply { marginEnd = (8 * d).toInt() }
                        setImageDrawable(contactAvatars.getOrPut(peerKey) {
                            cx.ring.views.AvatarDrawable.Builder().withContact(cvm).withPresence(true)
                                .withOnlineState(cvm.presence).withCircleCrop(true).build(ctx)
                        })
                    })
                    crow.addView(text("${cvm.displayName} — $st", sc, sizeSp = 13f))
                    container.addView(crow)
                    // 3rd level: this contact's device connections (the monitor's deepest detail).
                    if (cExpanded) {
                        if (conns.isEmpty())
                            container.addView(text("no active connection", grey, sizeSp = 12f, padL = 84, padT = 2))
                        for (conn in conns.sortedByDescending { it.status == AccountService.ConnectionStatus.Connected }) {
                            val stage = when (conn.status) {
                                AccountService.ConnectionStatus.Waiting -> "waiting…"
                                AccountService.ConnectionStatus.Connecting -> "connecting…"
                                AccountService.ConnectionStatus.ICE -> "negotiating (ICE)…"
                                AccountService.ConnectionStatus.TLS -> "securing (TLS)…"
                                AccountService.ConnectionStatus.Connected -> conn.remoteAddress ?: "connected"
                            }
                            val isC = conn.status == AccountService.ConnectionStatus.Connected
                            val ch = if (isC && conn.channels.isNotEmpty()) "  · ${conn.channels.size} ch" else ""
                            container.addView(text("↳ ${conn.device.take(8)}…  $stage$ch",
                                if (isC) connectedCol else idleCol, sizeSp = 12f, padL = 84, padT = 2))
                        }
                    }
                }
            }
        }
    }

    private fun networkLabel(): String? {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val n = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(n) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
    }

    /** Long-press the dot / "Reconnect now": bring every account fully online, re-enabling any
     *  that are Offline/disabled — not just nudging connectivity on already-enabled ones.
     *  The dot blinks hollow→filled as the account re-registers (built-in feedback). */
    private fun reconnectAllWithFeedback() {
        mAccountService.forceReconnectAllAccounts()
        showReconnectFlash("Reconnecting…")
    }

    /**
     * "Sync now" — the visible top-bar sync icon (left of the connection dot). Re-registers every
     * account, which re-bootstraps each swarm and re-fetches pending commits — this is what clears
     * inter-account (same-device) messages that strand under DHT-proxy mode (the proxy-on tradeoff:
     * a deactivated receiver misses the swarm-sync notification). Same machinery as the dot's
     * long-press reconnect, surfaced on the Sync long-press with sync-worded feedback.
     */
    private fun syncAllWithFeedback() {
        cx.ring.utils.ConnectionWatchdog.manualRecover(requireContext(), mAccountService)
        showReconnectFlash("Recovering connections…")
    }

    /** Tap Sync: show the online-recovery event log (stale detected, recovered, backoff, …). */
    private fun showRecoveryLogDialog() {
        val ctx = context ?: return
        val d = ctx.resources.displayMetrics.density
        val log = cx.ring.utils.UiPrefs.getRecoveryLog(ctx)
        val logText = if (log.isEmpty()) "(no recovery events yet)" else log.reversed().joinToString("\n")
        val tv = TextView(ctx).apply {
            this.text = logText
            setTextColor(0xFFFFFF00.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(tv) }
        // Custom title: "Recovery log" left, an "ⓘ Recovery" pill top-right → Online-recovery settings.
        val recoveryPill = TextView(ctx).apply {
            text = "ⓘ Recovery"
            setTextColor(0xFFFFFF00.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
            background = AppCompatResources.getDrawable(ctx, R.drawable.dialog_black_yellow)
        }
        val titleRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), 0)
            addView(TextView(ctx).apply {
                text = "Recovery log"
                setTextColor(0xFFFFFF00.toInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(recoveryPill)
        }
        val dialog = MaterialAlertDialogBuilder(ctx, R.style.ShiroikumaDialog)
            .setCustomTitle(titleRow)
            .setView(scroll)
            .setPositiveButton("Close", null)
            // Clear is the NEUTRAL button so it sits bottom-LEFT, away from Close. Guarded below:
            // a tap only warns; it clears the log only on a long-tap (prevents accidental loss).
            .setNeutralButton("Clear", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(AppCompatResources.getDrawable(ctx, R.drawable.dialog_black_yellow))
        recoveryPill.setOnClickListener { (activity as? HomeActivity)?.goToAdvancedSettings(openFonts = true); dialog.dismiss() }
        styleDialogButton(dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE))
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.let { clearBtn ->
            styleDialogButton(clearBtn)
            clearBtn.setOnClickListener {
                Flash.show(ctx, "Long-tap Clear to wipe the recovery log — every entry will be LOST.",
                    android.widget.Toast.LENGTH_LONG)
            }
            clearBtn.setOnLongClickListener {
                cx.ring.utils.UiPrefs.clearRecoveryLog(ctx)
                Flash.show(ctx, "Recovery log cleared.")
                dialog.dismiss()
                true
            }
        }
    }

    /** Brief flash — the shared [Flash] style (black / yellow text + border, settable). */
    private fun showReconnectFlash(msg: String) {
        Flash.show(context, msg)
    }

    override fun onStop() {
        super.onStop()
        mFgWatchdogHandler.removeCallbacks(mFgWatchdogRunnable)
        mDisposable.clear()
    }

    private fun adjustLayoutForAccessibility(enabled: Boolean) {
        val params = mBinding?.appBarContainer?.layoutParams as? AppBarLayout.LayoutParams ?: return
        val recyclerView = mSmartListFragment?.getRecyclerView() ?: return

        params.scrollFlags = if (enabled) 0 else (
                AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                        AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
                        AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP or
                        AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED
                )
        mBinding?.appBarContainer?.layoutParams = params
        recyclerView.clipToPadding = enabled
    }

    /**
     * Set a badge to display how many invitations are pending.
     */
    private fun setInvitationBadge(conversations: List<Conversation>, snip: List<ConversationItemViewModel>) {
        val binding = mBinding ?: return
        pendingAdapter?.update(conversations)
        val hasInvites = conversations.isNotEmpty()
        binding.invitationCard.invitationGroup.isVisible = hasInvites
        if (hasInvites) {
            binding.invitationCard.invitationBadge.text = conversations.size.toString()
            binding.invitationCard.invitationReceivedTxt.text = snip.joinToString(", ") { it.title }
        }
        updateAppBarLayoutBottomPadding(hasInvites)
    }

    fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_CALL -> {
                expandSearchActionView()
                mSearchView?.setQuery(intent.dataString, true)
            }

            Intent.ACTION_DIAL -> {
                expandSearchActionView()
                mSearchView?.setQuery(intent.dataString, false)
            }

            Intent.ACTION_SEARCH -> {
                expandSearchActionView()
                mSearchView?.setQuery(intent.getStringExtra(SearchManager.QUERY), true)
            }

            NotificationService.NOTIF_TRUST_REQUEST_MULTIPLE -> {
                expandPendingView()
            }

            else -> {}
        }
    }

    override fun showDonationReminder(show: Boolean) {
        mBinding?.appBar?.let {
            TransitionManager.beginDelayedTransition(it, AutoTransition())
        }
        mBinding?.donationCard?.donationCard?.isVisible = show
        mBinding?.fragmentContainer?.getFragment<SmartListFragment>()?.scrollToTop()
    }

    override fun onQueryTextSubmit(query: String?) = true

    override fun onQueryTextChange(newText: String): Boolean {
        querySubject.onNext(newText)
        return true
    }

    private fun goToQRFragment() {
        // Hide keyboard to prevent any glitch.
        val accountUri = mAccountService.currentAccount?.uri ?: return
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(requireView().windowToken, 0)

        QRCodeFragment.newInstance(
            QRCodeFragment.MODE_SHARE or QRCodeFragment.MODE_SCAN,
            QRCodeFragment.MODE_SCAN,
            Uri.fromString(accountUri)
        ).show(parentFragmentManager, QRCodeFragment.TAG)

        collapseSearchActionView()
    }

    private fun startNewSwarm() {
        ContactPickerFragment().show(parentFragmentManager, ContactPickerFragment.TAG)
        collapseSearchActionView()
    }

    private fun expandSearchActionView() {
        mBinding?.searchView?.show()
    }

    fun collapseSearchActionView() {
        mBinding?.searchView?.hide()
    }

    /** Prevent appbar to be collapsed by direct scroll. */
    private fun disableAppBarScroll() {
        (mBinding!!.appBar.layoutParams as CoordinatorLayout.LayoutParams).behavior =
            AppBarLayout.Behavior().apply {
                setDragCallback(object : DragCallback() {
                    override fun canDrag(appBarLayout: AppBarLayout): Boolean = false
                })
            }
    }

    companion object {
        private val TAG = HomeFragment::class.simpleName!!
    }

}