package cx.ring.client

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import cx.ring.R
import cx.ring.application.JamiApplication
import cx.ring.utils.AndroidFileUtils
import cx.ring.utils.ChatArchive
import cx.ring.utils.ChatFiles
import cx.ring.utils.ContentUri
import cx.ring.utils.DeviceUtils
import cx.ring.utils.DialogTheme
import cx.ring.utils.Flash
import cx.ring.views.AvatarDrawable
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import net.jami.services.AccountService
import net.jami.services.ConversationFacade
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The chat-files panel: every attachment on the device, by account, by conversation, by size —
 * with the means to actually get rid of them.
 *
 * It exists because a phone migration made the shape of the problem obvious: the backup is
 * gigabytes and almost all of it is old chat files nobody will open again (白い熊, 2026-07-29).
 * Export/Import can only carry that weight around; this page is where it gets put down.
 *
 * Three levels, all folded at first, each showing its own file count and byte total: accounts →
 * conversations (largest first) → files (largest first). Sizes are what the filesystem says, so a
 * file that was never downloaded reads as nothing, because that is what it costs.
 *
 * The page is built in code rather than XML for the same reason the backup panel is: it is a
 * yellow-on-black surface of its own, every row is generated, and a ConstraintLayout that cannot
 * be previewed in this environment buys nothing here.
 *
 * On what "delete" can and cannot mean — the swarm only lets us retract our own messages — see
 * [ChatFiles].
 */
@AndroidEntryPoint
class ChatFilesActivity : AppCompatActivity() {

    @Inject
    @Singleton
    lateinit var accountService: AccountService

    @Inject
    @Singleton
    lateinit var conversationFacade: ConversationFacade

    private val disposables = CompositeDisposable()
    private val groups = ArrayList<ChatFiles.Group>()
    private val bucketByKey = HashMap<String, ChatFiles.Bucket>()
    private val rows = ArrayList<Row>()
    private val openAccounts = HashSet<String>()
    private val openConversations = HashSet<String>()
    private val openDirections = HashSet<String>()
    private val selected = LinkedHashSet<ChatFiles.Item>()

    private lateinit var totals: TextView
    private lateinit var selection: TextView
    private lateinit var deleteButton: TextView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private val adapter = FileAdapter()

    private lateinit var fileSaver: ActivityResultLauncher<String>
    private var pendingSave: File? = null
    private var scanning = true

    // --- rows -------------------------------------------------------------------------------

    private sealed class Row {
        class Acc(val group: ChatFiles.Group) : Row()
        class Conv(val group: ChatFiles.Group, val bucket: ChatFiles.Bucket) : Row()
        /** Sent / received / orphaned, inside one conversation. */
        class Dir(
            val bucket: ChatFiles.Bucket,
            val direction: ChatFiles.Direction,
            val items: List<ChatFiles.Item>,
        ) : Row()
        class Fil(val bucket: ChatFiles.Bucket, val item: ChatFiles.Item) : Row()
    }

    // --- lifecycle --------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reading chat history means asking the daemon, so it has to be up before anything here
        // can answer which message a file belongs to.
        JamiApplication.instance?.startDaemon(this)
        setContentView(buildUi())
        fileSaver = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri: Uri? ->
            val source = pendingSave
            pendingSave = null
            if (uri == null || source == null) return@registerForActivityResult
            disposables.add(AndroidFileUtils.copyFileToUri(contentResolver, source, uri)
                .observeOn(DeviceUtils.uiScheduler)
                .subscribe({ Flash.show(this, getString(R.string.sk_files_saved)) },
                    { Flash.show(this, getString(R.string.sk_files_save_failed), Toast.LENGTH_LONG) }))
        }
        startScan()
    }

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
    }

    // --- chrome -----------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            isClickable = true
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = getString(R.string.sk_files_title)
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        root.addView(bar, wide())

        totals = TextView(this).apply {
            text = getString(R.string.sk_files_scanning)
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = pill(YELLOW)
        }
        root.addView(totals, wide().apply {
            marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(8)
        })

        empty = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(24))
            visibility = View.GONE
        }
        root.addView(empty, wide())

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChatFilesActivity)
            adapter = this@ChatFilesActivity.adapter
            setHasFixedSize(false)
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        selection = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        bottom.addView(selection, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        deleteButton = TextView(this).apply {
            text = getString(R.string.sk_files_delete)
            setTextColor(RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            minHeight = dp(52)
            background = pill(RED)
            isClickable = true
            setOnClickListener { onDeletePressed() }
        }
        bottom.addView(deleteButton)
        root.addView(bottom, wide())
        return root
    }

    // --- scanning ---------------------------------------------------------------------------

    /** Filesystem only, so the totals are on screen without waiting for the daemon. */
    private fun startScan() {
        Thread {
            val accounts = accountService.getAccounts()
            val found = ArrayList<ChatFiles.Group>()
            for (account in accounts) {
                val group = ChatFiles.Group(account.accountId)
                group.label = account.displayUsername?.takeIf { it.isNotEmpty() }
                    ?: account.displayname.takeIf { it.isNotEmpty() }
                    ?: account.accountId
                group.buckets.addAll(ChatFiles.scanAccount(this, account.accountId))
                if (group.buckets.isNotEmpty()) found.add(group)
            }
            found.sortByDescending { it.bytes }
            runOnUiThread {
                scanning = false
                groups.clear()
                groups.addAll(found)
                bucketByKey.clear()
                for (group in groups) for (bucket in group.buckets) bucketByKey[bucket.key] = bucket
                rebuild()
                resolveLabels()
            }
        }.start()
    }

    /** Account avatars and conversation titles, one chain per account so nothing stampedes. */
    private fun resolveLabels() {
        for (group in groups) {
            disposables.add(accountService.getObservableAccountProfile(group.accountId)
                .firstOrError()
                .observeOn(Schedulers.io())
                .map { pair -> AvatarDrawable.build(this, pair.first, pair.second, true) }
                .observeOn(DeviceUtils.uiScheduler)
                .subscribe({ avatar -> group.avatar = avatar; adapter.notifyDataSetChanged() },
                    { e -> Log.w(TAG, "account avatar: ${e.message}") }))

            val account = accountService.getAccount(group.accountId) ?: continue
            disposables.add(io.reactivex.rxjava3.core.Observable.fromIterable(group.buckets)
                .concatMapMaybe { bucket ->
                    val conversation = account.getSwarm(bucket.convId)
                        ?: return@concatMapMaybe io.reactivex.rxjava3.core.Maybe.empty()
                    conversationFacade.observeConversation(conversation, false)
                        .firstElement()
                        .map { vm ->
                            bucket.title = vm.title
                            bucket.avatar = AvatarDrawable.Builder()
                                .withViewModel(vm)
                                .withCircleCrop(true)
                                .build(this)
                            bucket
                        }
                        // concatMap runs these one at a time, so one conversation whose profile
                        // never arrives would leave every later chat in this account nameless.
                        .timeout(15, TimeUnit.SECONDS)
                        .onErrorComplete()
                }
                .observeOn(DeviceUtils.uiScheduler)
                .subscribe({ adapter.notifyDataSetChanged() },
                    { e -> Log.w(TAG, "conversation titles: ${e.message}") }))
        }
    }

    /**
     * Reads one conversation's messages so its files can name themselves, date themselves, and say
     * whether their message can be deleted. Runs when a conversation is unfolded — never for the
     * whole device up front, because that is a git log walk per chat and this phone's CPU budget
     * is not free.
     */
    private fun indexInBackground(bucket: ChatFiles.Bucket) {
        if (bucket.indexed || bucket.indexing) return
        bucket.indexing = true
        Thread {
            val conversation = accountService.getAccount(bucket.accountId)?.getSwarm(bucket.convId)
            if (conversation != null) ChatFiles.index(accountService, conversation, bucket)
            runOnUiThread {
                bucket.indexing = false
                // The sent/received groups only exist once the reading is done. Open them as they
                // appear, so files already on screen stay on screen instead of folding themselves
                // away under a heading that was not there a moment ago.
                for ((dir, _) in bucket.byDirection()) openDirections.add("${bucket.key}/$dir")
                rebuild()
            }
        }.start()
    }

    // --- the flattened tree -----------------------------------------------------------------

    private fun rebuild() {
        rows.clear()
        for (group in groups) {
            rows.add(Row.Acc(group))
            if (group.accountId !in openAccounts) continue
            for (bucket in group.buckets) {
                rows.add(Row.Conv(group, bucket))
                if (bucket.key !in openConversations) continue
                if (!bucket.indexed) {
                    // Still reading: show the files rather than an empty wait, since sizes and
                    // thumbnails are already known and only the direction is not.
                    for (item in bucket.items) rows.add(Row.Fil(bucket, item))
                    continue
                }
                for ((dir, items) in bucket.byDirection()) {
                    rows.add(Row.Dir(bucket, dir, items))
                    if ("${bucket.key}/$dir" in openDirections)
                        for (item in items) rows.add(Row.Fil(bucket, item))
                }
            }
        }
        adapter.notifyDataSetChanged()
        refreshCounters()
        val nothing = !scanning && groups.isEmpty()
        empty.text = getString(R.string.sk_files_none)
        empty.visibility = if (nothing) View.VISIBLE else View.GONE
        list.visibility = if (nothing) View.GONE else View.VISIBLE
    }

    private fun refreshCounters() {
        val allFiles = groups.sumOf { it.count }
        val allBytes = groups.sumOf { it.bytes }
        totals.text = if (scanning) getString(R.string.sk_files_scanning)
        else resources.getQuantityString(
            R.plurals.sk_files_totals_all, allFiles, allFiles, ChatArchive.human(allBytes))
        selection.text = getString(R.string.sk_files_selected,
            selected.size, allFiles,
            ChatArchive.human(selected.sumOf { it.bytes }), ChatArchive.human(allBytes))
        val on = selected.isNotEmpty()
        deleteButton.isEnabled = on
        deleteButton.alpha = if (on) 1f else 0.4f
        deleteButton.text = if (on) getString(R.string.sk_files_delete_n, selected.size)
        else getString(R.string.sk_files_delete)
    }

    // --- selection --------------------------------------------------------------------------

    private fun setSelected(items: List<ChatFiles.Item>, on: Boolean) {
        if (on) selected.addAll(items) else selected.removeAll(items.toSet())
        adapter.notifyDataSetChanged()
        refreshCounters()
    }

    private fun allSelected(items: List<ChatFiles.Item>) =
        items.isNotEmpty() && selected.containsAll(items)

    private fun anySelected(items: List<ChatFiles.Item>) = items.any { it in selected }

    private fun itemsOf(group: ChatFiles.Group) = group.buckets.flatMap { it.items }

    // --- deleting ---------------------------------------------------------------------------

    /**
     * Nothing is deleted before the affected conversations have been read: until then we cannot
     * tell our own files (whose message goes with them) from received ones (where only this
     * device's copy can go), and the warning would be a guess.
     */
    private fun onDeletePressed() {
        if (selected.isEmpty()) {
            Flash.show(this, getString(R.string.sk_files_nothing_selected))
            return
        }
        requestDelete(selected.toList())
    }

    private fun requestDelete(items: List<ChatFiles.Item>) {
        val pending = items.map { "${it.accountId}/${it.convId}" }.toSet()
            .mapNotNull { bucketByKey[it] }.filter { !it.indexed }
        if (pending.isEmpty()) {
            confirmDelete(items)
            return
        }
        val progress = progressDialog(getString(R.string.sk_files_indexing))
        Thread {
            for (bucket in pending) {
                val conversation =
                    accountService.getAccount(bucket.accountId)?.getSwarm(bucket.convId)
                if (conversation != null) ChatFiles.index(accountService, conversation, bucket)
            }
            runOnUiThread {
                progress.dismiss()
                adapter.notifyDataSetChanged()
                confirmDelete(items)
            }
        }.start()
    }

    /**
     * Specific about what is lost, and cancel is the button under your thumb.
     *
     * When any of the selection is your own, there are two ways to proceed rather than one, because
     * they are genuinely different acts: freeing the space is yours to decide, deleting the message
     * reaches into everyone else's chat. The soft one is offered first and described first; the
     * irreversible one sits furthest from the thumb and is the only button in red.
     */
    private fun confirmDelete(items: List<ChatFiles.Item>) {
        val own = items.count { it.removesMessage }
        val local = items.size - own
        val body = StringBuilder(resources.getQuantityString(R.plurals.sk_files_confirm_head,
            items.size, items.size, ChatArchive.human(items.sumOf { it.bytes })))
        if (own > 0) body.append("\n\n")
            .append(resources.getQuantityString(R.plurals.sk_files_confirm_own, own, own))
        if (local > 0) body.append("\n\n")
            .append(resources.getQuantityString(R.plurals.sk_files_confirm_local, local, local))

        val text = TextView(this).apply {
            this.text = body.toString()
            setTextColor(if (own > 0) YELLOW else RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val builder = DialogTheme.builder(this)
            .setTitle(if (own > 0) R.string.sk_files_confirm_title_own
            else R.string.sk_files_confirm_title)
            .setView(ScrollView(this).apply { addView(text) })
            // Cancel sits in the positive slot on purpose: it is the default answer to this
            // question, and a delete should never be where OK usually is.
            .setPositiveButton(android.R.string.cancel, null)
        if (own > 0) {
            builder.setNegativeButton(R.string.sk_files_free_space) { _, _ -> runDelete(items, false) }
            builder.setNeutralButton(R.string.sk_files_delete_messages) { _, _ -> runDelete(items, true) }
        } else {
            // Nothing of ours in here, so there is no choice to offer: received files can only ever
            // lose their local copy.
            builder.setNegativeButton(R.string.sk_files_confirm_delete) { _, _ -> runDelete(items, false) }
        }
        val dialog = builder.show().let { DialogTheme.theme(it, this) }
        dialog.getButton(
            if (own > 0) AlertDialog.BUTTON_NEUTRAL else AlertDialog.BUTTON_NEGATIVE
        )?.setTextColor(RED)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun runDelete(items: List<ChatFiles.Item>, removeMessages: Boolean) {
        val progress = progressDialog(getString(R.string.sk_files_deleting))
        Thread {
            val outcome = ChatFiles.Outcome()
            var done = 0
            for (item in items) {
                ChatFiles.delete(accountService, item, outcome, removeMessages)
                done++
                if (done % 10 == 0 || done == items.size) {
                    val line = getString(R.string.sk_files_deleting_n, done, items.size)
                    runOnUiThread { progress.setMessage(line) }
                }
            }
            val removed = items.toSet()
            runOnUiThread {
                progress.dismiss()
                for (group in groups) {
                    for (bucket in group.buckets) bucket.items.removeAll(removed)
                    group.buckets.removeAll { it.items.isEmpty() }
                }
                groups.removeAll { it.buckets.isEmpty() }
                bucketByKey.clear()
                for (group in groups) for (bucket in group.buckets) bucketByKey[bucket.key] = bucket
                selected.clear()
                rebuild()
                showResult(outcome)
            }
        }.start()
    }

    private fun showResult(outcome: ChatFiles.Outcome) {
        val body = StringBuilder(resources.getQuantityString(R.plurals.sk_files_deleted,
            outcome.files, outcome.files, ChatArchive.human(outcome.bytes)))
        if (outcome.messages > 0) body.append("\n").append(resources.getQuantityString(
            R.plurals.sk_files_deleted_messages, outcome.messages, outcome.messages))
        if (outcome.kept > 0) body.append("\n").append(resources.getQuantityString(
            R.plurals.sk_files_deleted_kept, outcome.kept, outcome.kept))
        if (outcome.failed > 0)
            body.append("\n").append(getString(R.string.sk_files_deleted_failed, outcome.failed))
        DialogTheme.builder(this)
            .setTitle(R.string.sk_files_deleted_title)
            .setMessage(body.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show().let { DialogTheme.theme(it, this) }
    }

    private fun progressDialog(message: String): AlertDialog =
        DialogTheme.builder(this)
            .setMessage(message)
            .setCancelable(false)
            .show().let { DialogTheme.theme(it, this) }

    // --- viewing ----------------------------------------------------------------------------

    /**
     * Opens one file, so a picture can be recognised before it is thrown away — the whole point of
     * a page whose other buttons are irreversible (白い熊, 2026-07-29).
     *
     * Pictures and videos go to the app's own [MediaViewerActivity] (zoom, playback, no chooser in
     * the way); anything else is handed to whatever app claims its type.
     */
    private fun viewFile(item: ChatFiles.Item) {
        if (!item.file.isFile) {
            Flash.show(this, getString(R.string.sk_files_gone))
            return
        }
        val mime = AndroidFileUtils.getMimeTypeFromExtension(item.name.substringAfterLast('.', ""))
        val uri = runCatching { ContentUri.getUriForFile(this, item.file, item.name) }.getOrNull()
        if (uri != null) {
            launchViewer(uri, mime, item.name)
            return
        }
        // A payload with no client-tree twin lives in the daemon's own directory, which is outside
        // every path file_paths.xml declares — FileProvider refuses it outright. Stage it into the
        // cache path that IS declared and show that instead.
        Flash.show(this, getString(R.string.sk_files_preparing))
        Thread {
            val staged = File(File(cacheDir, "tmp"), item.name)
            staged.parentFile?.mkdirs()
            val ready = runCatching { item.file.copyTo(staged, overwrite = true) }.isSuccess
            val cached = if (ready)
                runCatching { ContentUri.getUriForFile(this, staged, item.name) }.getOrNull()
            else null
            runOnUiThread {
                if (cached != null) launchViewer(cached, mime, item.name)
                else Flash.show(this, getString(R.string.sk_files_view_failed), Toast.LENGTH_LONG)
            }
        }.start()
    }

    private fun launchViewer(uri: Uri, mime: String, displayName: String) {
        val media = mime.startsWith("image/") || mime.startsWith("video/")
        if (media) {
            val opened = runCatching {
                startActivity(Intent(this, MediaViewerActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.isSuccess
            if (opened) return
        }
        AndroidFileUtils.openFile(this, uri, displayName)
    }

    // --- saving -----------------------------------------------------------------------------

    private fun saveToDisk(item: ChatFiles.Item) {
        if (!item.file.isFile) {
            Flash.show(this, getString(R.string.sk_files_gone))
            return
        }
        pendingSave = item.file
        fileSaver.launch(item.name)
    }

    // --- adapter ----------------------------------------------------------------------------

    private inner class Holder(
        val root: LinearLayout,
        val check: CheckBox,
        val icon: ImageView,
        val title: TextView,
        val meta: TextView,
        val save: ImageView,
        val trash: ImageView,
        val chevron: TextView,
        /** Per-holder, never shared: a foreground drawable takes its bounds from the view it is
         *  attached to, and one instance across many rows would be laid out by the last of them. */
        val thumbFrame: GradientDrawable,
    ) : RecyclerView.ViewHolder(root)

    private inner class FileAdapter : RecyclerView.Adapter<Holder>() {

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                minimumHeight = dp(56)
                isClickable = true
            }
            val check = CheckBox(ctx).apply {
                buttonTintList = ColorStateList.valueOf(YELLOW)
            }
            root.addView(check)
            val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            root.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(2); marginEnd = dp(10)
            })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(ctx).apply {
                setTextColor(YELLOW)
                maxLines = 2
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            val meta = TextView(ctx).apply {
                setTextColor(DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            // WRAP_CONTENT, not the vertical LinearLayout's MATCH_PARENT default: on a file row the
            // name is a tap target, and its box has to hug the text so the empty space beside a
            // short filename still belongs to the row — and still toggles the selection.
            texts.addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            texts.addView(meta)
            root.addView(texts, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val save = iconButton(ctx, R.drawable.download_24px, YELLOW)
            val trash = iconButton(ctx, R.drawable.delete_24px, RED)
            root.addView(save)
            root.addView(trash)
            val chevron = TextView(ctx).apply {
                setTextColor(YELLOW)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                minWidth = dp(32)
            }
            root.addView(chevron)
            val frame = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(4).toFloat()
                setStroke(dp(1), YELLOW)
            }
            return Holder(root, check, icon, title, meta, save, trash, chevron, frame)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows[position]) {
                is Row.Acc -> bindAccount(holder, row)
                is Row.Conv -> bindConversation(holder, row)
                is Row.Dir -> bindDirection(holder, row)
                is Row.Fil -> bindFile(holder, row)
            }
        }

        /**
         * Undoes everything a file row does to a recycled holder. A group row has no thumbnail to
         * open and no name to tap, and leaving either live would swallow the tap that folds it.
         */
        private fun asGroupRow(holder: Holder) {
            Glide.with(this@ChatFilesActivity).clear(holder.icon)
            holder.icon.imageTintList = null
            holder.icon.foreground = null
            holder.icon.setOnClickListener(null)
            holder.icon.isClickable = false
            // Order matters: setOnClickListener(null) leaves a view clickable, so isClickable is
            // cleared after it, not before.
            holder.title.paintFlags = holder.title.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            holder.title.setOnClickListener(null)
            holder.title.isClickable = false
            holder.save.visibility = View.GONE
            holder.trash.visibility = View.GONE
            holder.chevron.visibility = View.VISIBLE
        }

        private fun bindAccount(holder: Holder, row: Row.Acc) {
            val group = row.group
            val items = itemsOf(group)
            holder.root.setPadding(dp(8), dp(8), dp(8), dp(8))
            sizeIcon(holder.icon, 52)
            holder.icon.visibility = View.VISIBLE
            asGroupRow(holder)
            holder.icon.setImageDrawable(group.avatar)
            holder.title.text = group.label
            holder.title.setTypeface(holder.title.typeface, Typeface.BOLD)
            holder.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            holder.meta.text = resources.getQuantityString(R.plurals.sk_files_totals,
                group.count, group.count, ChatArchive.human(group.bytes))
            holder.chevron.text = if (group.accountId in openAccounts) "▾" else "▸"
            bindCheck(holder.check, items)
            holder.root.setOnClickListener {
                if (!openAccounts.remove(group.accountId)) openAccounts.add(group.accountId)
                rebuild()
            }
        }

        private fun bindConversation(holder: Holder, row: Row.Conv) {
            val bucket = row.bucket
            holder.root.setPadding(dp(26), dp(6), dp(8), dp(6))
            sizeIcon(holder.icon, 40)
            holder.icon.visibility = View.VISIBLE
            asGroupRow(holder)
            holder.icon.setImageDrawable(bucket.avatar)
            holder.title.text = bucket.title
                ?: getString(R.string.sk_files_unnamed_conv, bucket.convId.take(12))
            holder.title.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            holder.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            holder.meta.text = resources.getQuantityString(R.plurals.sk_files_totals,
                bucket.count, bucket.count, ChatArchive.human(bucket.bytes))
            holder.chevron.text = if (bucket.key in openConversations) "▾" else "▸"
            bindCheck(holder.check, bucket.items)
            holder.root.setOnClickListener {
                if (!openConversations.remove(bucket.key)) {
                    openConversations.add(bucket.key)
                    indexInBackground(bucket)
                }
                rebuild()
            }
        }

        /**
         * Sent / received / orphaned, inside one conversation — each with its own tally, so a whole
         * direction can be taken in one tick. Which is the point: what you send is the half you can
         * act on without touching anybody else's chat.
         */
        private fun bindDirection(holder: Holder, row: Row.Dir) {
            val key = "${row.bucket.key}/${row.direction}"
            holder.root.setPadding(dp(44), dp(5), dp(8), dp(5))
            asGroupRow(holder)
            // No avatar here — a direction is not somebody. The heading carries its own arrow.
            holder.icon.visibility = View.GONE
            holder.title.text = getString(when (row.direction) {
                ChatFiles.Direction.SENT -> R.string.sk_files_dir_sent
                ChatFiles.Direction.RECEIVED -> R.string.sk_files_dir_received
                ChatFiles.Direction.UNKNOWN -> R.string.sk_files_dir_unknown
            })
            holder.title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            holder.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            holder.meta.text = resources.getQuantityString(R.plurals.sk_files_totals,
                row.items.size, row.items.size, ChatArchive.human(row.items.sumOf { it.bytes }))
            holder.chevron.text = if (key in openDirections) "▾" else "▸"
            bindCheck(holder.check, row.items)
            holder.root.setOnClickListener {
                if (!openDirections.remove(key)) openDirections.add(key)
                rebuild()
            }
        }

        private fun bindFile(holder: Holder, row: Row.Fil) {
            val item = row.item
            // One step deeper once the sent/received headings exist to sit under.
            holder.root.setPadding(dp(if (row.bucket.indexed) 60 else 44), dp(4), dp(4), dp(4))
            sizeIcon(holder.icon, 44)
            holder.icon.visibility = View.VISIBLE
            if (item.thumbable && item.file.isFile) {
                Glide.with(this@ChatFilesActivity)
                    .load(item.file)
                    .centerCrop()
                    .placeholder(R.drawable.baseline_insert_drive_file_24)
                    .error(R.drawable.baseline_insert_drive_file_24)
                    .into(holder.icon)
            } else {
                Glide.with(this@ChatFilesActivity).clear(holder.icon)
                holder.icon.setImageResource(R.drawable.baseline_insert_drive_file_24)
                holder.icon.imageTintList = ColorStateList.valueOf(YELLOW)
            }
            if (item.thumbable) holder.icon.imageTintList = null
            // The thumbnail is the way in to the file itself — tapping it opens the picture or
            // video. A thin yellow frame is what marks it as a target; the rest of the row keeps
            // toggling the selection, so neither gesture takes the other's place.
            holder.icon.foreground = holder.thumbFrame
            holder.icon.isClickable = true
            holder.icon.setOnClickListener { viewFile(item) }
            holder.title.text = item.name
            holder.title.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            holder.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            // The name opens the file too — underlined so it reads as something to tap rather than
            // just a label. Only the text itself; the rest of the row still marks.
            holder.title.paintFlags = holder.title.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            holder.title.setOnClickListener { viewFile(item) }
            holder.meta.text = fileMeta(item)
            holder.save.visibility = View.VISIBLE
            holder.trash.visibility = View.VISIBLE
            holder.chevron.visibility = View.GONE
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = item in selected
            holder.check.alpha = 1f
            holder.check.setOnClickListener {
                setSelected(listOf(item), holder.check.isChecked)
            }
            holder.root.setOnClickListener { setSelected(listOf(item), item !in selected) }
            holder.save.setOnClickListener { saveToDisk(item) }
            // Through requestDelete, never straight to the warning: until the conversation has
            // been read we cannot tell our own file from a received one, and the warning would
            // promise the wrong thing.
            holder.trash.setOnClickListener { requestDelete(listOf(item)) }
        }

        /** Parent rows: on when everything under them is, half-lit when only some of it is. */
        private fun bindCheck(check: CheckBox, items: List<ChatFiles.Item>) {
            check.setOnCheckedChangeListener(null)
            val all = allSelected(items)
            check.isChecked = all || anySelected(items)
            check.alpha = if (all || !anySelected(items)) 1f else 0.45f
            check.setOnClickListener { setSelected(items, !all) }
        }
    }

    private fun fileMeta(item: ChatFiles.Item): String {
        val parts = ArrayList<String>(4)
        parts.add(ChatArchive.human(item.bytes))
        parts.add(DateFormat.getDateFormat(this).format(Date(item.whenMs)) + " " +
                DateFormat.getTimeFormat(this).format(Date(item.whenMs)))
        val bucket = bucketByKey["${item.accountId}/${item.convId}"]
        when {
            item.removesMessage -> parts.add(getString(R.string.sk_files_sent))
            item.known -> parts.add(getString(R.string.sk_files_received))
            bucket?.indexed == true -> parts.add(getString(R.string.sk_files_no_message))
            else -> parts.add(getString(R.string.sk_files_reading))
        }
        return parts.joinToString(" · ")
    }

    // --- small views ------------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun pill(stroke: Int) = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = dp(10).toFloat()
        setStroke(dp(2), stroke)
    }

    private fun sizeIcon(icon: ImageView, size: Int) {
        val lp = icon.layoutParams
        lp.width = dp(size)
        lp.height = dp(size)
        icon.layoutParams = lp
    }

    private fun iconButton(ctx: Context, res: Int, tint: Int) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        val pad = dp(10)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        isClickable = true
    }

    companion object {
        private val TAG = ChatFilesActivity::class.simpleName!!
        private const val YELLOW = 0xFFFFFF00.toInt()
        private const val DIM = 0xFFC8C800.toInt()
        private const val RED = 0xFFFF5252.toInt()

        fun intent(ctx: Context) = Intent(ctx, ChatFilesActivity::class.java)
    }
}
