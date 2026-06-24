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
            // Long-press: reconnect / re-register everything immediately.
            setOnLongClickListener { reconnectAllWithFeedback(); true }
        }
        // Long-press the overflow ("hamburger") menu to jump straight to the UI page.
        // doOnLayout: the action-item views only exist once the bar has been laid out.
        searchBar.doOnLayout {
            searchBar.findViewById<View>(R.id.menu_overflow)?.setOnLongClickListener {
                (activity as? HomeActivity)?.goToAdvancedSettings(openFonts = true)
                true
            }
        }
        searchBar.setOnMenuItemClickListener {
            when (it.itemId) {
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

    override fun onStart() {
        super.onStart()
        activity?.intent?.let { handleIntent(it) }

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
                cx.ring.utils.ConnectionHealth.update(now, accounts.map { a ->
                    a.accountId to a.peers.any { (_, c) -> c.any { it.status == AccountService.ConnectionStatus.Connected } }
                })
                // Ring the dot ONLY for a genuine hidden problem: an account REGISTERED but unable to
                // sync for >2.5 min. Normal short-lived sync churn never lights it; plain offline is
                // already shown by the hollow dot icon.
                val problems = accounts.count { a ->
                    cx.ring.utils.ConnectionHealth.classify(a.accountId, now, a.registered,
                        a.peers.any { (_, c) -> c.any { it.status == AccountService.ConnectionStatus.Connected } },
                        a.peers.isNotEmpty()) == cx.ring.utils.ConnectionHealth.Health.NOT_SYNCING
                }
                if (problems != dotAlarmCount) {
                    dotAlarmCount = problems
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

    /** >0 when there are dead peer links — rings the account dot red as an alarm. */
    private var dotAlarmCount = 0

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
        if (dotAlarmCount > 0) {
            dot.setBackgroundResource(R.drawable.dot_alarm_ring)
            val p = (3 * dot.resources.displayMetrics.density).toInt()
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
        val dialog = MaterialAlertDialogBuilder(ctx, R.style.ShiroikumaDialog)
            .setTitle("Connection status")
            .setView(scroll)
            .setPositiveButton("Reconnect now") { _, _ -> reconnectAllWithFeedback() }
            .setNeutralButton("Monitor", null)  // click wired below so it does NOT dismiss the dialog
            .setNegativeButton("Close", null)
            .create()
        val dis = CompositeDisposable()
        dialog.setOnDismissListener { dis.clear() }
        // Account avatars (by accountId) for the dialog rows, loaded async; rebuild on either source.
        val avatars = HashMap<String, android.graphics.drawable.Drawable>()
        var lastAccounts: List<AccountService.AccountConnections> = emptyList()
        fun rebuild() = populateConnectionStatusView(container, lastAccounts, System.currentTimeMillis(), avatars)
        dis.add(mAccountService.observableAccountList
            .switchMap { accs -> io.reactivex.rxjava3.core.Observable.merge(
                accs.filter { it.isJami }.map { mAccountService.getObservableAccountProfile(it.accountId) }) }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (account, profile) ->
                avatars[account.accountId] = AvatarDrawable.build(ctx, account, profile, true, account.presenceStatus)
                rebuild()
            }, { /* ignore */ }))
        dis.add(mAccountService.monitorAllConnections(true)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ accounts ->
                val now = System.currentTimeMillis()
                cx.ring.utils.ConnectionHealth.update(now, accounts.map { a ->
                    a.accountId to a.peers.any { (_, c) -> c.any { it.status == AccountService.ConnectionStatus.Connected } }
                })
                lastAccounts = accounts
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
        accounts: List<AccountService.AccountConnections>,
        now: Long,
        avatars: Map<String, android.graphics.drawable.Drawable>,
    ) {
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        val red = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.MONITOR_PROBLEM)
        val amber = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.MONITOR_CONNECTING)
        val healthyCol = cx.ring.utils.ColorPrefs.getColor(ctx, cx.ring.utils.ColorPrefs.MONITOR_HEALTHY)
        val grey = 0xFFAAAAAA.toInt()
        container.removeAllViews()
        fun text(s: String, color: Int, bold: Boolean = false, sizeSp: Float = 14f) = TextView(ctx).apply {
            text = s; setTextColor(color); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        data class Row(val ac: AccountService.AccountConnections,
                       val health: cx.ring.utils.ConnectionHealth.Health, val connected: Int)
        val rows = accounts.map { ac ->
            val byDevice = ac.peers.flatMap { it.second }.groupBy { it.device }
            val connectedNow = byDevice.any { (_, l) -> l.any { it.status == AccountService.ConnectionStatus.Connected } }
            val connected = byDevice.count { (_, l) -> l.any { it.status == AccountService.ConnectionStatus.Connected } }
            Row(ac, cx.ring.utils.ConnectionHealth.classify(ac.accountId, now, ac.registered, connectedNow, ac.peers.isNotEmpty()), connected)
        }
        val problems = rows.count { cx.ring.utils.ConnectionHealth.isProblem(it.health) }
        container.addView(text(
            if (problems > 0) "⚠ $problems account(s) need attention" else "✓ All accounts healthy",
            if (problems > 0) red else healthyCol, bold = true, sizeSp = 15f))
        container.addView(text("Network: ${networkLabel() ?: "none"}", grey, sizeSp = 12f))
        for (r in rows) {
            val word = when (r.health) {
                cx.ring.utils.ConnectionHealth.Health.HEALTHY -> "online · healthy"
                cx.ring.utils.ConnectionHealth.Health.CONNECTING -> "connecting…"
                cx.ring.utils.ConnectionHealth.Health.NOT_SYNCING -> "NOT SYNCING"
                cx.ring.utils.ConnectionHealth.Health.OFFLINE -> "OFFLINE"
            }
            val col = when (r.health) {
                cx.ring.utils.ConnectionHealth.Health.HEALTHY -> healthyCol
                cx.ring.utils.ConnectionHealth.Health.CONNECTING -> amber
                else -> red
            }
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (8 * d).toInt(), 0, 0)
            }
            val s = (40 * d).toInt()
            row.addView(ImageView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(s, s).apply { marginEnd = (10 * d).toInt() }
                avatars[r.ac.accountId]?.let { setImageDrawable(it) }
            })
            row.addView(text("${r.ac.name} — $word · ${r.connected} connected", col))
            container.addView(row)
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

    /** Brief flash — the shared [Flash] style (black / yellow text + border, settable). */
    private fun showReconnectFlash(msg: String) {
        Flash.show(context, msg)
    }

    override fun onStop() {
        super.onStop()
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