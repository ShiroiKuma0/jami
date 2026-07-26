/*
 *  shiroikuma.jami fork: launcher "Add shortcut" picker.
 *
 *  Entered from the home-screen launcher (ACTION_CREATE_SHORTCUT) or from the search-bar overflow
 *  (R.string.shortcut_create, which pins through ShortcutManagerCompat.requestPinShortcut instead of
 *  returning a result). Three steps: account → conversation → chat-or-call. The result is a pinned
 *  shortcut whose icon is the contact avatar badged with the Jami app icon (bottom right) and a
 *  yellow-traced chat or phone glyph (bottom left), and whose intent goes to
 *  [ShortcutLaunchActivity], which re-issues exactly the intent the in-app UI uses — ACTION_VIEW on
 *  HomeActivity for a chat, ACTION_CALL on CallActivity for a call.
 *
 *  The badge colours are settable roles (ColorPrefs.SHORTCUT_ICON / SHORTCUT_FILL). The icon is a
 *  bitmap baked at creation time, so a later colour change only affects newly created shortcuts.
 */
package cx.ring.client

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cx.ring.R
import cx.ring.service.DRingService
import cx.ring.services.VCardServiceImpl
import cx.ring.utils.ColorPrefs
import cx.ring.utils.ConversationPath
import cx.ring.utils.DeviceUtils
import cx.ring.utils.DialogTheme
import cx.ring.utils.Flash
import cx.ring.utils.FontPrefs
import cx.ring.utils.FontUtil
import cx.ring.utils.ShortcutPrefs
import cx.ring.utils.showThemed
import cx.ring.views.AvatarDrawable
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import net.jami.model.Account
import net.jami.model.Conversation
import net.jami.services.AccountService
import net.jami.services.ContactService
import net.jami.smartlist.ConversationItemViewModel
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@AndroidEntryPoint
class ShortcutPickerActivity : AppCompatActivity() {

    @Inject
    lateinit var mAccountService: AccountService

    @Inject
    lateinit var mContactService: ContactService

    private val mDisposable = CompositeDisposable()

    /** true when started in-app (overflow menu): pin directly instead of returning a result. */
    private var pinMode = false

    private val iconSize by lazy {
        max(ShortcutManagerCompat.getIconMaxHeight(this), ShortcutManagerCompat.getIconMaxWidth(this))
            .coerceIn(96, 256)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pinMode = intent?.action != Intent.ACTION_CREATE_SHORTCUT
        setResult(RESULT_CANCELED)

        // A launcher can start this on a cold process — make sure the daemon is loading accounts.
        try {
            startService(Intent(this, DRingService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "could not start daemon service", e)
        }

        mDisposable.add(mAccountService.observableAccountList
            .filter { it.isNotEmpty() }
            .firstOrError()
            .timeout(20, TimeUnit.SECONDS)
            .observeOn(DeviceUtils.uiScheduler)
            .subscribe({ accounts -> chooseAccount(accounts) },
                { e ->
                    Log.w(TAG, "no account available", e)
                    fail(R.string.shortcut_error_accounts)
                })
        )
    }

    override fun onDestroy() {
        mDisposable.clear()
        super.onDestroy()
    }

    // --- step 1: account -------------------------------------------------------------------

    private fun chooseAccount(accounts: List<Account>) {
        if (accounts.size == 1) {
            chooseConversation(accounts[0])
            return
        }
        mDisposable.add(Single.zip(accounts.map { account ->
            VCardServiceImpl.loadProfile(this, account)
                .firstOrError()
                .observeOn(Schedulers.computation())
                .map { profile ->
                    Item(
                        title = profile.displayName?.takeIf { it.isNotBlank() }
                            ?: account.displayUsername ?: account.accountId,
                        subtitle = account.displayUsername ?: account.username ?: account.accountId,
                        icon = accountAvatar(account, profile),
                        payload = account
                    )
                }
                .onErrorReturn {
                    Item(account.displayUsername ?: account.accountId, null, null, account)
                }
        }) { items -> items.map { it as Item } }
            .observeOn(DeviceUtils.uiScheduler)
            .subscribe({ items ->
                showPicker(getString(R.string.shortcut_pick_account), items) { item ->
                    chooseConversation(item.payload as Account)
                }
            }, { e ->
                Log.w(TAG, "could not load accounts", e)
                fail(R.string.shortcut_error_accounts)
            })
        )
    }

    // --- step 2: conversation --------------------------------------------------------------

    private fun chooseConversation(account: Account) {
        mDisposable.add(account.getConversationsSubject()
            .filter { it.isNotEmpty() }
            .firstOrError()
            .timeout(15, TimeUnit.SECONDS)
            .onErrorReturnItem(emptyList())
            .flatMap { conversations ->
                val usable = conversations.filter { it.mode.blockingFirst() != Conversation.Mode.Syncing }
                if (usable.isEmpty()) Single.just(emptyList<Item>())
                else Single.zip(usable.map { c ->
                    mContactService.getLoadedConversation(c)
                        .observeOn(Schedulers.computation())
                        .map { vm ->
                            Item(
                                title = vm.title,
                                subtitle = vm.uriTitle.takeIf { it != vm.title },
                                icon = avatarOf(vm),
                                payload = vm
                            )
                        }
                }) { items -> items.map { it as Item } }
            }
            .observeOn(DeviceUtils.uiScheduler)
            .subscribe({ items ->
                if (items.isEmpty()) fail(R.string.shortcut_error_empty)
                else showPicker(getString(R.string.shortcut_pick_contact), items) { item ->
                    chooseAction(item.payload as ConversationItemViewModel)
                }
            }, { e ->
                Log.w(TAG, "could not load conversations", e)
                fail(R.string.shortcut_error_conversations)
            })
        )
    }

    // --- step 3: chat or call --------------------------------------------------------------

    private fun chooseAction(vm: ConversationItemViewModel) {
        DialogTheme.builder(this)
            .setTitle(R.string.shortcut_pick_kind)
            .setItems(arrayOf<CharSequence>(
                getString(R.string.shortcut_kind_chat), getString(R.string.shortcut_kind_call))
            ) { _, which ->
                deliver(vm, call = which == 1)
            }
            .setOnCancelListener { finish() }
            .showThemed()
    }

    // --- the shortcut itself ---------------------------------------------------------------

    private fun deliver(vm: ConversationItemViewModel, call: Boolean) {
        val path = ConversationPath(vm.accountId, vm.uri)
        // Distinct from the dynamic share shortcuts' id (which is path.toKey()): those are wiped by
        // HomeActivity's removeAllDynamicShortcuts on every refresh, and a shared id would drag the
        // pinned one along with them.
        val id = (if (call) "sk-call:" else "sk-chat:") + path.toKey()
        val shortcut = ShortcutInfoCompat.Builder(this, id)
            .setShortLabel(vm.title)
            .setLongLabel(getString(
                if (call) R.string.shortcut_label_call else R.string.shortcut_label_chat, vm.title))
            .setIcon(IconCompat.createWithBitmap(buildIcon(vm, call)))
            .setIntent(launchIntent(vm, call))
            .build()

        if (pinMode) {
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
                ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
            } else {
                fail(R.string.shortcut_error_unsupported)
                return
            }
        } else {
            setResult(RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this, shortcut))
        }
        finish()
    }

    /**
     * Every shortcut points at [ShortcutLaunchActivity], never straight at HomeActivity or
     * CallActivity — see that class for why (icon clobbering on app update, and the launcher's
     * inability to start a non-exported activity). The peer to ring is resolved here, at creation
     * time, so the trampoline needs no contact lookup.
     */
    private fun launchIntent(vm: ConversationItemViewModel, call: Boolean): Intent {
        // 1:1 calls the peer directly; a group call targets the swarm uri (as goToGroupCall does).
        val peers = vm.contacts.filter { !it.contact.isUser }
        val target = if (peers.size == 1) peers[0].contact.uri else vm.uri
        return Intent(Intent.ACTION_VIEW)
            .setClass(this, ShortcutLaunchActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtras(ConversationPath.toBundle(vm.accountId, vm.uri))
            .putExtra(Intent.EXTRA_PHONE_NUMBER, target.uri)
            .putExtra(ShortcutLaunchActivity.KEY_CALL, call)
            .putExtra(ShortcutLaunchActivity.KEY_TOKEN, ShortcutPrefs.issue(this))
    }

    // --- icon composition ------------------------------------------------------------------

    /**
     * Account row avatar. Deliberately NOT [AvatarDrawable.build]: that helper defaults to
     * showPresence=true with an OFFLINE status, which paints a red dot on every account — a status
     * this picker never asked for and does not track, and which contradicts the live yellow dots
     * shown elsewhere in the app.
     */
    private fun accountAvatar(account: Account, profile: net.jami.model.Profile): Drawable {
        val builder = AvatarDrawable.Builder()
            .withPhoto(profile.avatar as? android.graphics.Bitmap)
            .withNameData(profile.displayName, account.registeredName)
            .withCircleCrop(true)
            .withPresence(false)
        val id = if (account.isSip) account.uri else account.username
        if (id != null)
            builder.withUri(net.jami.model.Uri(
                if (account.isSip) net.jami.model.Uri.SIP_URI_SCHEME
                else net.jami.model.Uri.JAMI_URI_SCHEME, id))
        else
            builder.withId(account.accountId)
        return builder.build(this)
    }

    private fun avatarOf(vm: ConversationItemViewModel): Drawable =
        AvatarDrawable.Builder()
            .withViewModel(vm)
            .withCircleCrop(true)
            .withPresence(false)   // a launcher icon must not freeze a presence dot
            .build(this)

    /** Contact avatar + Jami badge (bottom right corner) + chat/phone trace (bottom left edge). */
    private fun buildIcon(vm: ConversationItemViewModel, call: Boolean): android.graphics.Bitmap {
        val size = iconSize
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        avatarOf(vm).apply {
            setBounds(0, 0, size, size)
            draw(canvas)
        }

        // Jami badge sits flush in the bottom-right corner; the action trace hugs the left edge.
        val dj = size * 0.268f
        drawJamiBadge(canvas, size - dj / 2f, size - dj / 2f, dj)
        val g = size * 0.28f
        drawActionGlyph(canvas, 0f, size - size * 0.015f - g, g, call)
        return bitmap
    }

    /**
     * The bare yellow trace of a phone / message — no disc, no ring — with a thin black rim so it
     * still reads over a light photo. The rim is the same glyph drawn 12% larger underneath.
     */
    private fun drawActionGlyph(canvas: Canvas, left: Float, top: Float, g: Float, call: Boolean) {
        val res = if (call) R.drawable.outline_call_24 else R.drawable.baseline_chat_24
        val big = g * 1.12f
        val inset = (big - g) / 2f
        ContextCompat.getDrawable(this, res)?.mutate()?.apply {
            setTint(ColorPrefs.getColor(this@ShortcutPickerActivity, ColorPrefs.SHORTCUT_FILL))
            setBounds((left - inset).toInt(), (top - inset).toInt(),
                (left - inset + big).toInt(), (top - inset + big).toInt())
            draw(canvas)
        }
        ContextCompat.getDrawable(this, res)?.mutate()?.apply {
            setTint(ColorPrefs.getColor(this@ShortcutPickerActivity, ColorPrefs.SHORTCUT_ICON))
            setBounds(left.toInt(), top.toInt(), (left + g).toInt(), (top + g).toInt())
            draw(canvas)
        }
    }

    private fun drawJamiBadge(canvas: Canvas, cx: Float, cy: Float, d: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@ShortcutPickerActivity, R.color.ic_launcher_background)
        }
        canvas.drawCircle(cx, cy, d / 2f, paint)

        val logo = ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)?.mutate()
        if (logo != null) {
            val saved = canvas.save()
            canvas.clipPath(Path().apply { addCircle(cx, cy, d / 2f, Path.Direction.CW) })
            // The adaptive foreground already insets its content to ~66% of the canvas, so drawing
            // it a touch larger than the badge keeps the logo inside the circle.
            val s = d * 1.15f
            logo.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
            logo.draw(canvas)
            canvas.restoreToCount(saved)
        }

        // Hairline ring at the logo's own line weight: stroke 1.7 in a 116 viewport, scaled by the
        // vector's 0.66 group scale and drawn at 1.15 x d — about 1.1% of the badge diameter.
        val ring = max(1f, d * 0.0111f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ring
        paint.color = ColorPrefs.getColor(this, ColorPrefs.SHORTCUT_ICON)
        canvas.drawCircle(cx, cy, d / 2f - ring / 2f, paint)
    }

    // --- shared list dialog ----------------------------------------------------------------

    private data class Item(
        val title: String,
        val subtitle: String?,
        val icon: Drawable?,
        val payload: Any
    )

    private fun showPicker(title: String, items: List<Item>, onPick: (Item) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_shortcut_pick, null)
        val filter = view.findViewById<EditText>(R.id.shortcut_pick_filter)
        val list = view.findViewById<RecyclerView>(R.id.shortcut_pick_list)

        var dialog: androidx.appcompat.app.AlertDialog? = null
        val adapter = PickAdapter(items) { item ->
            dialog?.dismiss()
            onPick(item)
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val row = (64 * resources.displayMetrics.density).toInt()
        val maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        list.layoutParams.height = min(items.size * row, maxHeight).coerceAtLeast(row)

        filter.visibility = if (items.size > 8) View.VISIBLE else View.GONE
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
            }
        })

        dialog = DialogTheme.builder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .showThemed()
    }

    private inner class PickAdapter(
        private val all: List<Item>,
        private val onPick: (Item) -> Unit
    ) : RecyclerView.Adapter<PickViewHolder>() {
        private var shown: List<Item> = all

        fun filter(query: String) {
            shown = if (query.isBlank()) all else all.filter {
                it.title.contains(query, true) || it.subtitle?.contains(query, true) == true
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PickViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_shortcut_pick, parent, false)
        )

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: PickViewHolder, position: Int) {
            val item = shown[position]
            holder.avatar.setImageDrawable(item.icon)
            holder.title.text = item.title
            holder.title.setTextColor(ColorPrefs.getColor(holder.title.context, ColorPrefs.LIST_NAME))
            FontUtil.apply(holder.title, FontPrefs.LIST_TITLE)
            holder.subtitle.text = item.subtitle
            holder.subtitle.visibility =
                if (item.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            holder.subtitle.setTextColor(ColorPrefs.getColor(holder.subtitle.context, ColorPrefs.LIST_PREVIEW))
            FontUtil.apply(holder.subtitle, FontPrefs.LIST_PREVIEW)
            holder.itemView.setOnClickListener { onPick(item) }
        }
    }

    private class PickViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.shortcut_pick_avatar)
        val title: TextView = view.findViewById(R.id.shortcut_pick_title)
        val subtitle: TextView = view.findViewById(R.id.shortcut_pick_subtitle)
    }

    private fun fail(@androidx.annotation.StringRes message: Int) {
        Flash.show(applicationContext, getString(message))
        finish()
    }

    companion object {
        private val TAG = ShortcutPickerActivity::class.java.simpleName
        const val ACTION_PICK_SHORTCUT = "cx.ring.action.PICK_SHORTCUT"
    }
}
