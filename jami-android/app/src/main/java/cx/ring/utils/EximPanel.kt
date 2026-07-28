package cx.ring.utils

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import cx.ring.R
import net.jami.services.AccountService
import java.io.File

/**
 * The backup panel, as a standalone dialog.
 *
 * It lives here rather than inside the settings fragment because a fresh install needs it too: on a
 * phone with no account yet the only screen is the account wizard, and without an entry point there
 * a backup could be exported but never restored — the migration would dead-end at exactly the
 * moment it matters (白い熊, 2026-07-28).
 *
 * Nothing here uses an ActivityResultLauncher, so any Context with a window can open it. That is
 * also why the directory is chosen with an in-house folder browser: the SAF tree picker returns a
 * document URI, and mixing that with the direct paths the engine actually writes to is what made
 * the directory appear unchangeable — the picker set one field while the label read another.
 *
 * Every visible string is a resource: English by default, Japanese and Czech shipped, and English
 * for every other system language.
 */
object EximPanel {

    private const val YELLOW = 0xFFFFFF00.toInt()
    private const val DIM = 0xFFC8C800.toInt()
    private const val WARN = 0xFFFF5252.toInt()
    private const val ROOT = "/storage/emulated/0"

    private class Ui(
        val dialog: AlertDialog,
        val permission: TextView,
        val permissionBox: View,
        val folder: TextView,
        val status: TextView,
        val preflight: TextView,
        val progress: TextView,
        val checks: List<Pair<SettingsExport.Cat, CheckBox>>,
    )

    @Volatile private var current: Ui? = null
    @Volatile private var lastWasImport = false

    private fun main() = Handler(Looper.getMainLooper())

    /**
     * Opens the panel. [onImported] fires after a completed import; [onClosed] fires whenever the
     * dialog goes away, cancel included — the host has to be told, because settings changed here
     * (the folder, the permission) are shown on the page behind it and a dismissed dialog raises no
     * lifecycle callback of its own.
     */
    fun show(
        ctx: Context,
        accounts: AccountService,
        onImported: (() -> Unit)? = null,
        onClosed: (() -> Unit)? = null,
    ) {
        val app = ctx.applicationContext
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 12f), dp(ctx, 20f), dp(ctx, 8f))
        }

        root.addView(caption(ctx, ctx.getString(R.string.sk_exim_subtitle)))

        // Progress belongs at the TOP. It used to sit under the category list, which on a long
        // panel is below the fold — a running restore looked like nothing was happening at all
        // (白い熊, 2026-07-28). Counts are items and bytes, never a percentage.
        val progress = TextView(ctx).apply {
            text = EximJob.lastLine
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(ctx, 10f).toFloat()
                setStroke(dp(ctx, 2f), YELLOW)
            }
            visibility = if (EximJob.running) View.VISIBLE else View.GONE
        }
        root.addView(progress, wide(ctx, topMargin = 8f))

        // All-files access first: without it nothing here can run. Loud while missing, faded once
        // granted — see refresh().
        val permissionText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val permissionBox = boxed(ctx) { openAllFilesSettings(ctx) }.apply {
            addView(caption(ctx, ctx.getString(R.string.sk_exim_perm_label), pad = false))
            addView(permissionText)
        }
        root.addView(permissionBox, wide(ctx, topMargin = 10f))

        val folderText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(YELLOW)
        }
        val folderBox = boxed(ctx) { pickFolder(ctx, app) { refresh(ctx, app) } }.apply {
            addView(caption(ctx, ctx.getString(R.string.sk_exim_folder_label), pad = false))
            addView(folderText)
        }
        root.addView(folderBox, wide(ctx, topMargin = 10f))

        val status = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        root.addView(status, wide(ctx, topMargin = 8f, bottomMargin = 8f))
        root.addView(divider(ctx))

        // These are the EXPORT categories. Restore asks separately, once it can see the archive.
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.sk_exim_export_cats)
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(ctx, 8f), dp(ctx, 10f), 0, dp(ctx, 2f))
        })
        val checks = ArrayList<Pair<SettingsExport.Cat, CheckBox>>()
        val selectAll = checkbox(ctx, ctx.getString(R.string.sk_exim_select_all), bold = true)
        root.addView(selectAll)
        for (cat in SettingsExport.Cat.entries) {
            val cb = checkbox(ctx, ctx.getString(cat.labelRes)).apply { isChecked = cat.defaultOn }
            checks.add(cat to cb)
            root.addView(cb)
        }
        selectAll.setOnCheckedChangeListener { _, on -> checks.forEach { it.second.isChecked = on } }

        val preflight = caption(ctx, ctx.getString(R.string.sk_exim_measuring))
        root.addView(preflight)

        root.addView(divider(ctx, topGap = 8))
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.sk_exim_deleted_entry)
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(ctx, 8f), dp(ctx, 10f), 0, dp(ctx, 6f))
            isClickable = true
            minHeight = dp(ctx, 48f)
            setOnClickListener {
                current?.let { ui -> if (guard(ctx, ui)) startDeletedRestore(ctx, app, accounts) }
            }
        })
        root.addView(caption(ctx, ctx.getString(R.string.sk_exim_deleted_hint)))

        // The dialog's own button bar, NOT buttons inside the scrolling content: as content they
        // scrolled away off the bottom of the screen once the category list grew (白い熊,
        // 2026-07-28). Cancel dismisses; Import and Export must not, so their listeners are
        // replaced after show() — the default ones close the dialog.
        val dialog = DialogTheme.builder(ctx)
            .setTitle(R.string.sk_exim_title)
            .setView(ScrollView(ctx).apply { addView(root) })
            .setNeutralButton(android.R.string.cancel, null)
            .setNegativeButton(R.string.sk_exim_import, null)
            .setPositiveButton(R.string.sk_exim_export, null)
            .show().let { DialogTheme.theme(it, ctx) }

        val ui = Ui(dialog, permissionText, permissionBox, folderText, status, preflight, progress,
            checks)
        current = ui
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            onImport(ctx, app, accounts, ui)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            onExport(ctx, app, accounts, ui)
        }

        dialog.setOnDismissListener {
            current = null
            onClosed?.invoke()
        }
        // Granting the permission means leaving for the system settings screen and coming back to a
        // dialog that never stopped — no lifecycle callback fires, so the panel would still read
        // "not granted". Regaining window focus is the signal, and it also covers the folder
        // browser closing.
        dialog.window?.decorView?.viewTreeObserver
            ?.addOnWindowFocusChangeListener { hasFocus -> if (hasFocus) refresh(ctx, app) }
        refresh(ctx, app)
        refreshPreflight(ctx, app, accounts, ui)
        observe(ctx, app, onImported)
    }

    // --- state -----------------------------------------------------------------------------------

    private fun refresh(ctx: Context, app: Context) {
        val ui = current ?: return
        val granted = SettingsExport.hasAllFilesAccess()
        ui.permission.text = ctx.getString(
            if (granted) R.string.sk_exim_perm_granted else R.string.sk_exim_perm_missing)
        ui.permission.setTextColor(if (granted) DIM else WARN)
        // Faded once granted, loud while it is not. The folder box below stays at full strength,
        // because that one is a control you actually use.
        ui.permissionBox.alpha = if (granted) 0.35f else 1f
        ui.folder.text = SettingsExport.getDirPath(app)
        Thread {
            val text = SettingsExport.lastExportStatus(app)
            val warn = !SettingsExport.exportExists(app)
            ui.status.post {
                ui.status.text = text
                ui.status.setTextColor(if (warn) WARN else YELLOW)
            }
        }.start()
    }

    private fun refreshPreflight(ctx: Context, app: Context, accounts: AccountService, ui: Ui) {
        Thread {
            val idx = runCatching {
                EximRunner(app, accounts).indexChats(
                    listOf(SettingsExport.Cat.CHAT_TEXTS, SettingsExport.Cat.CHAT_FILES))
            }.getOrDefault(emptyList())
            val text = ctx.getString(R.string.sk_exim_preflight,
                idx.sumOf { it.conversations.size },
                ChatArchive.human(idx.sumOf { it.texts.bytes }),
                idx.sumOf { it.payload.files },
                ChatArchive.human(idx.sumOf { it.payload.bytes }))
            ui.preflight.post { ui.preflight.text = text }
        }.start()
    }

    private fun observe(ctx: Context, app: Context, onImported: (() -> Unit)?) {
        EximJob.observe { line, done, report, error ->
            val ui = current
            if (ui == null) {
                // Panel closed mid-run — the outcome still has to reach somebody.
                if (done) main().post {
                    Flash.show(app, error?.let { app.getString(R.string.sk_exim_failed, it) }
                        ?: app.getString(R.string.sk_exim_done), Toast.LENGTH_LONG)
                }
                return@observe
            }
            ui.progress.post {
                ui.progress.text = line
                ui.progress.visibility = if (done) View.GONE else View.VISIBLE
                if (!done) return@post
                refresh(ctx, app)
                when {
                    error != null ->
                        Flash.show(ctx, ctx.getString(R.string.sk_exim_failed, error), Toast.LENGTH_LONG)
                    report == null ->
                        Flash.show(ctx, ctx.getString(R.string.sk_exim_no_result), Toast.LENGTH_LONG)
                    lastWasImport -> showImportDone(ctx, report.text, onImported)
                    else -> showDone(ctx, ctx.getString(R.string.sk_exim_export_done_title), report.text)
                }
            }
        }
    }

    // --- the permission gate ---------------------------------------------------------------------

    /** Everything here writes to shared storage, so nothing may start without the permission.
     *  Refusing loudly beats a half-written archive or a silent no-op. */
    private fun guard(ctx: Context, ui: Ui): Boolean {
        if (SettingsExport.hasAllFilesAccess()) return true
        Flash.show(ctx, ctx.getString(R.string.sk_exim_perm_required), Toast.LENGTH_LONG)
        flash(ui.permissionBox)
        return false
    }

    private fun flash(v: View) {
        v.animate().alpha(0.15f).setDuration(140).withEndAction {
            v.animate().alpha(1f).setDuration(140).withEndAction {
                v.animate().alpha(0.15f).setDuration(140).withEndAction {
                    v.animate().alpha(1f).setDuration(140).start()
                }.start()
            }.start()
        }.start()
    }

    fun openAllFilesSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Flash.show(ctx, ctx.getString(R.string.sk_exim_android11))
            return
        }
        val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(direct) }.onFailure {
            // Some EMUI builds refuse the per-app deep link; the全体 list always exists.
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                Flash.show(ctx, ctx.getString(R.string.sk_exim_settings_failed), Toast.LENGTH_LONG)
            }
        }
    }

    // --- run -------------------------------------------------------------------------------------

    private fun selected(ui: Ui) = ui.checks.filter { it.second.isChecked }.map { it.first }

    private fun onExport(ctx: Context, app: Context, accounts: AccountService, ui: Ui) {
        if (!guard(ctx, ui)) return
        val cats = selected(ui)
        if (cats.isEmpty()) { Flash.show(ctx, ctx.getString(R.string.sk_exim_no_cats)); return }
        if (EximJob.running) { Flash.show(ctx, ctx.getString(R.string.sk_exim_busy)); return }
        val target = runCatching { EximRunner(app, accounts).openExportTarget() }.getOrNull()
        if (target == null) {
            Flash.show(ctx, ctx.getString(R.string.sk_exim_no_target), Toast.LENGTH_LONG)
            return
        }
        lastWasImport = false
        EximJob.start(app, accounts, app.getString(R.string.sk_exim_notif_export)) { r ->
            target.stream.use { out -> r.exportInto(cats, out, target.file) }
        }
    }

    /**
     * Restore is archive-first: pick the file, then choose from what that file actually holds.
     *
     * The panel's own checkboxes are the EXPORT selection. Reusing them for restore asked which
     * categories to bring back before anything knew which categories existed — you could tick
     * things the archive never contained, and never see what it did.
     */
    private fun onImport(ctx: Context, app: Context, accounts: AccountService, ui: Ui) {
        if (!guard(ctx, ui)) return
        if (EximJob.running) { Flash.show(ctx, ctx.getString(R.string.sk_exim_busy)); return }
        pickArchive(ctx, app) { file -> inspect(ctx, app, accounts, file) }
    }

    /** Reads the archive's manifest off the main thread, then offers what is in it. */
    private fun inspect(ctx: Context, app: Context, accounts: AccountService, file: File) {
        Flash.show(ctx, ctx.getString(R.string.sk_exim_loading))
        Thread {
            val found = runCatching {
                SettingsExport.openSource(file).use { src ->
                    SettingsExport.categoriesIn(src) to SettingsExport.chatIndexIn(src)
                }
            }.getOrNull()
            main().post { showImportPicker(ctx, app, accounts, file, found?.first, found?.second) }
        }.start()
    }

    private fun showImportPicker(
        ctx: Context, app: Context, accounts: AccountService, file: File,
        present: List<SettingsExport.Cat>?, chats: Map<String, ChatArchive.AccountChats>?,
    ) {
        if (present.isNullOrEmpty()) {
            showDone(ctx, ctx.getString(R.string.sk_exim_cant_restore_title),
                ctx.getString(R.string.sk_exim_cant_restore_body, file.name))
            return
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 12f), dp(ctx, 20f), dp(ctx, 8f))
        }
        box.addView(caption(ctx, "${file.name}  (${ChatArchive.human(file.length())})"))
        box.addView(caption(ctx, ctx.getString(R.string.sk_exim_pick_cats_hint)))
        val boxes = present.map { cat ->
            checkbox(ctx, describe(ctx, cat, chats)).apply { isChecked = true }
                .also { box.addView(it) }
        }
        DialogTheme.builder(ctx)
            .setTitle(R.string.sk_exim_pick_cats_title)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sk_exim_restore_start) { _, _ ->
                val picked = present.filterIndexed { i, _ -> boxes[i].isChecked }
                if (picked.isEmpty()) {
                    Flash.show(ctx, ctx.getString(R.string.sk_exim_no_cats))
                    return@setPositiveButton
                }
                lastWasImport = true
                EximJob.start(app, accounts, app.getString(R.string.sk_exim_notif_import)) { r ->
                    SettingsExport.openSource(file).use { src -> r.runImport(src, picked) }
                }
            }
            .show().let { DialogTheme.theme(it, ctx) }
    }

    /** Category label, with what the archive holds for it where that is known. */
    private fun describe(
        ctx: Context, cat: SettingsExport.Cat, chats: Map<String, ChatArchive.AccountChats>?
    ): String {
        val label = ctx.getString(cat.labelRes)
        if (chats.isNullOrEmpty()) return label
        val all = chats.values
        return when (cat) {
            SettingsExport.Cat.CHAT_TEXTS -> ctx.getString(R.string.sk_exim_chat_texts_detail,
                label, all.sumOf { it.conversations.size },
                ChatArchive.human(all.sumOf { it.texts.bytes }))
            SettingsExport.Cat.CHAT_FILES -> ctx.getString(R.string.sk_exim_chat_files_detail,
                label, all.sumOf { it.payload.files },
                ChatArchive.human(all.sumOf { it.payload.bytes }))
            else -> label
        }
    }

    private fun startDeletedRestore(ctx: Context, app: Context, accounts: AccountService) {
        pickArchive(ctx, app) { file ->
            Flash.show(ctx, ctx.getString(R.string.sk_exim_loading))
            Thread {
                val found = runCatching {
                    SettingsExport.openSource(file).use {
                        EximRunner(app, accounts).deletedCandidates(it)
                    }
                }.getOrElse { emptyList() }
                main().post { showDeletedRestore(ctx, app, accounts, file, found) }
            }.start()
        }
    }

    private fun showDeletedRestore(
        ctx: Context, app: Context, accounts: AccountService, file: File,
        found: List<EximRunner.Deleted>,
    ) {
        if (found.isEmpty()) {
            showDone(ctx, ctx.getString(R.string.sk_exim_deleted_title),
                ctx.getString(R.string.sk_exim_deleted_none))
            return
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 12f), dp(ctx, 20f), dp(ctx, 8f))
        }
        box.addView(caption(ctx, ctx.getString(R.string.sk_exim_deleted_pick)))
        val boxes = found.map { d ->
            checkbox(ctx, "${d.accountLabel} — ${d.convId.take(12)}…").also { box.addView(it) }
        }
        DialogTheme.builder(ctx)
            .setTitle(R.string.sk_exim_deleted_title)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sk_exim_restore_start) { _, _ ->
                val picks = found.filterIndexed { i, _ -> boxes[i].isChecked }
                if (picks.isEmpty()) {
                    Flash.show(ctx, ctx.getString(R.string.sk_exim_nothing_selected))
                    return@setPositiveButton
                }
                lastWasImport = true
                EximJob.start(app, accounts, app.getString(R.string.sk_exim_notif_deleted)) { r ->
                    SettingsExport.openSource(file).use { src -> r.restoreDeleted(src, picks) }
                }
            }
            .show().let { DialogTheme.theme(it, ctx) }
    }

    // --- pickers ---------------------------------------------------------------------------------

    private fun pickArchive(ctx: Context, app: Context, onPicked: (File) -> Unit) {
        val local = SettingsExport.directExports(app)
        if (local.isEmpty()) {
            showDone(ctx, ctx.getString(R.string.sk_exim_no_backups_title),
                ctx.getString(R.string.sk_exim_no_backups_body, SettingsExport.getDirPath(app)))
            return
        }
        val labels = local.map { "${it.name}  (${ChatArchive.human(it.length())})" }
        DialogTheme.builder(ctx)
            .setTitle(R.string.sk_exim_pick_backup)
            .setItems(labels.toTypedArray()) { _, which -> onPicked(local[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show().let { DialogTheme.theme(it, ctx) }
    }

    private fun pickFolder(ctx: Context, app: Context, onChanged: () -> Unit) {
        if (!SettingsExport.hasAllFilesAccess()) {
            current?.let { guard(ctx, it) }
            return
        }
        val start = File(SettingsExport.getDirPath(app)).let { if (it.isDirectory) it else File(ROOT) }
        browse(ctx, start) { dir ->
            SettingsExport.setDirPath(app, dir.absolutePath)
            onChanged()
            Flash.show(ctx, ctx.getString(R.string.sk_exim_folder_set, dir.absolutePath))
        }
    }

    /**
     * Plain-path folder browser. The chosen path is what the engine actually writes to — no
     * document URI in between, which is what made the old picker look like it did nothing.
     *
     * "Use this folder" is a button, not the first row of the list: as a row it sat above the
     * folders and read like just another destination. Cancel left, confirm right.
     */
    private fun browse(ctx: Context, dir: File, onPick: (File) -> Unit) {
        val subs = dir.listFiles()?.filter { it.isDirectory && !it.isHidden }?.sortedBy { it.name }
            ?: emptyList()
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12f), dp(ctx, 8f), dp(ctx, 12f), dp(ctx, 8f))
        }
        var dialog: AlertDialog? = null
        fun row(label: String, target: File) = TextView(ctx).apply {
            text = label
            setTextColor(YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(ctx, 8f), dp(ctx, 12f), dp(ctx, 8f), dp(ctx, 12f))
            minHeight = dp(ctx, 48f)
            isClickable = true
            setOnClickListener {
                dialog?.dismiss()
                browse(ctx, target, onPick)
            }
        }
        val parent = dir.parentFile
        if (parent != null && dir.absolutePath != ROOT && dir.absolutePath.startsWith(ROOT))
            list.addView(row("⬆  ..", parent))
        if (subs.isEmpty()) list.addView(caption(ctx, ctx.getString(R.string.sk_exim_no_subfolders)))
        for (s in subs) list.addView(row("📁  ${s.name}", s))

        dialog = DialogTheme.builder(ctx)
            .setTitle(dir.absolutePath)
            .setView(ScrollView(ctx).apply { addView(list) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sk_exim_use_folder) { _, _ -> onPick(dir) }
            .show().let { DialogTheme.theme(it, ctx) }
    }

    // --- result dialogs ---------------------------------------------------------------------------

    private fun showDone(ctx: Context, title: String, body: String) {
        DialogTheme.builder(ctx)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show().let { DialogTheme.theme(it, ctx) }
    }

    private fun showImportDone(ctx: Context, summary: String, onImported: (() -> Unit)?) {
        DialogTheme.builder(ctx)
            .setTitle(R.string.sk_exim_import_done_title)
            .setMessage(ctx.getString(R.string.sk_exim_import_done_body, summary))
            .setCancelable(false)
            .setPositiveButton(R.string.sk_exim_restart_now) { _, _ -> restart(ctx) }
            .setNegativeButton(R.string.sk_exim_later) { _, _ ->
                current?.dialog?.dismiss()
                onImported?.invoke()
            }
            .show().let { DialogTheme.theme(it, ctx) }
    }

    private fun restart(ctx: Context) {
        val app = ctx.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        launch.component?.let { app.startActivity(Intent.makeRestartActivityTask(it)) }
        Runtime.getRuntime().exit(0)
    }

    // --- small views ------------------------------------------------------------------------------

    private fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density).toInt()

    private fun wide(c: Context, topMargin: Float = 0f, bottomMargin: Float = 0f) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = dp(c, topMargin)
            this.bottomMargin = dp(c, bottomMargin)
        }

    private fun caption(c: Context, text: String, pad: Boolean = true) = TextView(c).apply {
        this.text = text
        setTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        if (pad) setPadding(dp(c, 8f), dp(c, 6f), 0, 0)
    }

    private fun divider(c: Context, topGap: Int = 0) = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1f))
            .apply { topMargin = dp(c, topGap.toFloat()) }
        setBackgroundColor(YELLOW)
        alpha = 0.4f
    }

    /** Black box, yellow border — the tappable rows on this panel are all this shape and size, so
     *  they are hard to miss with a thumb. */
    private fun boxed(c: Context, onClick: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 14f), dp(c, 12f), dp(c, 14f), dp(c, 12f))
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(c, 10f).toFloat()
            setStroke(dp(c, 2f), YELLOW)
        }
        isClickable = true
        minimumHeight = dp(c, 56f)
        setOnClickListener { onClick() }
    }

    private fun checkbox(c: Context, label: String, bold: Boolean = false) = CheckBox(c).apply {
        text = label
        setTextColor(YELLOW)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        buttonTintList = android.content.res.ColorStateList.valueOf(YELLOW)
        setPadding(dp(c, 8f), dp(c, 7f), 0, dp(c, 7f))
    }
}
