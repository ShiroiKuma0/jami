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
package cx.ring.utils

import android.content.Context

/**
 * Can the SELECTED push backend actually produce a token on THIS device? (shiroikuma fork,
 * 2026-09-11.)
 *
 * Written after a phone swap cost a week of misdiagnosis. The new phone had no Google Play
 * Services implementation on it at all, so `FirebaseMessaging.getInstance().token` could never
 * answer; the app held no token, and NOTHING said so. The only trace was a `Log.w` that EMUI drops
 * for release builds, and the UI's response was to HIDE the "Push notifications" row
 * (`SettingsFragment`, upstream behaviour since 2023: `if (pushToken == null) … View.GONE`) — so
 * the one control that would have explained it disappeared precisely when it was needed.
 *
 * Downstream, the damage was not merely "no pushes". With no push leg the accounts can never sleep,
 * `PushEvidence.lastRealPushMs` stays 0 for the life of the process, and the uniform-wedge
 * detector's only acquittal ("a real push landed N s ago") becomes structurally unreachable — so a
 * quiet night convicted every time and the watchdog tore down all four accounts ~15×/day.
 *
 * Hence: state it out loud, name the remedy, and do it from MAIN source so every flavor gets it.
 * No Google or UnifiedPush class is referenced here — `noPush` has neither on its classpath.
 */
object PushProvider {

    /** microG's GmsCore uses the same package name as Google's own, so one check covers both. */
    const val PLAY_SERVICES = "com.google.android.gms"

    enum class Verdict {
        /** Push is switched off (local-node / permanent-service mode). Nothing to warn about. */
        DISABLED,
        /** A token is held for the selected backend. */
        OK,
        /** FCM selected and no Play Services implementation is installed — it CANNOT mint a token. */
        NO_PLAY_SERVICES,
        /** The provider is there but has handed us nothing (registration refused, distributor
         *  unregistered, or simply not answered yet). */
        NO_TOKEN,
    }

    /**
     * Is a Play Services implementation (Google's, or microG's GmsCore) installed?
     *
     * Needs the `<package android:name="com.google.android.gms"/>` entry in `<queries>`: from API
     * 30 package visibility hides it otherwise and this would answer a confident, WRONG "missing".
     * A `<package>` entry grants visibility only — no permission, no access.
     */
    fun playServicesInstalled(c: Context): Boolean = runCatching {
        c.packageManager.getPackageInfo(PLAY_SERVICES, 0) != null
    }.getOrDefault(false)

    /** The selected backend's held token, or null/empty when there is none. */
    private fun token(): String? = runCatching {
        cx.ring.application.JamiApplication.instance?.pushToken?.first?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun pushWanted(): Boolean = runCatching {
        cx.ring.application.JamiApplication.instance?.mPreferencesService?.settings
            ?.enablePushNotifications == true
    }.getOrDefault(false)

    /** The `noPush` flavor carries no push transport at all — telling its user to install microG
     *  would be nonsense. Mirrors SettingsFragment's own `isPushCompatible`. */
    private val buildHasPush: Boolean
        get() = cx.ring.BuildConfig.FLAVOR == "withFirebase" || cx.ring.BuildConfig.FLAVOR == "withUnifiedPush"

    fun verdict(c: Context): Verdict = when {
        !buildHasPush -> Verdict.DISABLED
        !pushWanted() -> Verdict.DISABLED
        token() != null -> Verdict.OK
        UiPrefs.getPushBackend(c) == UiPrefs.PUSH_FCM && !playServicesInstalled(c) ->
            Verdict.NO_PLAY_SERVICES
        else -> Verdict.NO_TOKEN
    }

    /** One line naming the fault and the remedy, or null when there is nothing to say. Localized. */
    fun advice(c: Context): String? = when (verdict(c)) {
        Verdict.DISABLED, Verdict.OK -> null
        Verdict.NO_PLAY_SERVICES -> c.getString(cx.ring.R.string.push_warn_no_play_services)
        Verdict.NO_TOKEN ->
            if (UiPrefs.getPushBackend(c) == UiPrefs.PUSH_FCM)
                c.getString(cx.ring.R.string.push_warn_no_token_fcm)
            else
                c.getString(cx.ring.R.string.push_warn_no_token_unified)
    }

    /** Short tag for the dashboard's existing "Push: …" row, or null when the leg is fine. */
    fun shortReason(c: Context): String? = when (verdict(c)) {
        Verdict.DISABLED, Verdict.OK -> null
        Verdict.NO_PLAY_SERVICES -> c.getString(cx.ring.R.string.push_warn_short_no_play_services)
        Verdict.NO_TOKEN -> c.getString(cx.ring.R.string.push_warn_short_no_token)
    }
}
