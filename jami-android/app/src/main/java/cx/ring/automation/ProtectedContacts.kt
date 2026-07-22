package cx.ring.automation

import android.content.Context
import android.content.Intent
import cx.ring.service.DRingService
import cx.ring.utils.ProtectedContactsPrefs
import net.jami.model.Uri
import net.jami.services.AccountService
import java.util.concurrent.TimeUnit

/**
 * Shared handling for the companion's protected-contacts list (action SET_PROTECTED_CONTACTS), used
 * by both [ProtectedContactsReceiver] (the primary, broadcast path) and [AutomationActivity] (a
 * secondary path / `jami-cmd://protect` deep link).
 *
 * The companion (白い熊 自由作業盤) sends `contacts` as one **'|'-separated** string of registered
 * names (or bare ring ids), plus an optional `mode` — replace (default) | add | remove. The set is
 * persisted durably and matched (registered name + ring id, case-insensitive) in the notification
 * path. Unauthenticated. The entries are private data and are never logged.
 */
object ProtectedContacts {

    fun apply(
        context: Context,
        accountService: AccountService,
        contactsRaw: String?,
        modeRaw: String?,
        titleRaw: String? = null,
        bodyRaw: String? = null,
        pictureBodyRaw: String? = null,
    ) {
        val mode = modeRaw?.trim()?.lowercase().orEmpty()
        val entries = (contactsRaw ?: "").split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val app = context.applicationContext
        when (mode) {
            "add" -> ProtectedContactsPrefs.add(app, entries)
            "remove" -> ProtectedContactsPrefs.remove(app, entries)
            else -> ProtectedContactsPrefs.replace(app, entries)   // "replace" (default); empty clears
        }
        // Optional companion-controlled vague title/body; absent/blank → cleared → default is used.
        ProtectedContactsPrefs.setText(app, titleRaw, bodyRaw, pictureBodyRaw)
        resolveNamesToIds(app, accountService, entries, remove = mode == "remove")
    }

    /**
     * Best-effort hardening: resolve each registered-name entry to its ring id via the name service
     * and persist (or remove) the id too, so a protected contact matches on its id even before its
     * name is locally cached — the first-message case. Waits (with a timeout) for an account to be
     * loaded so it works on a cold start. Fire-and-forget; any failure (offline / not found /
     * timeout) leaves the registered-name match to cover cached contacts, and ids resolve on a
     * later set.
     */
    private fun resolveNamesToIds(
        app: Context,
        accountService: AccountService,
        entries: List<String>,
        remove: Boolean
    ) {
        val names = entries.filterNot { runCatching { Uri.fromString(it).isHexId }.getOrDefault(false) }
        if (names.isEmpty()) return
        // The name lookup needs the daemon + accounts loaded; best-effort (may be background-limited).
        try { app.startService(Intent(app, DRingService::class.java)) } catch (_: Exception) {}
        accountService.currentAccountSubject
            .firstOrError()
            .timeout(30, TimeUnit.SECONDS)
            .subscribe({ account ->
                for (name in names) {
                    accountService.findRegistrationByName(account.accountId, "", name)
                        .timeout(30, TimeUnit.SECONDS)
                        .subscribe({ reg ->
                            val ringId = reg.address
                            if (reg.state == AccountService.LookupState.Success && !ringId.isNullOrEmpty()) {
                                if (remove) ProtectedContactsPrefs.remove(app, listOf(ringId))
                                else ProtectedContactsPrefs.add(app, listOf(ringId))
                            }
                        }, { /* unresolved — registered-name match still covers cached contacts */ })
                }
            }, { /* no account within timeout — names are stored; ids resolve on a later set */ })
    }
}
