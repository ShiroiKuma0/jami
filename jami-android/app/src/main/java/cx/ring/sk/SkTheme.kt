/*
 * 白い熊 fork (sk): switches the black-yellow theme on for every phone screen.
 *
 * Before an activity inflates anything, the roles are laid over its colour resources (SkPalette)
 * and the overlay ThemeOverlay.Sk is applied on top of the theme the manifest gave it — so no
 * activity, layout or style of upstream has to name it. An activity that was built from colours
 * that have changed since recreates itself when it is next shown.
 *
 * The Android TV screens (cx.ring.tv) are left as upstream draws them: a phone never shows them
 * (白い熊, 2026-11-22).
 */
package cx.ring.sk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceFragmentCompat
import cx.ring.R

object SkTheme : Application.ActivityLifecycleCallbacks {
    fun install(app: Application) {
        SkRoles.install(app)
        SkPalette.apply(app)
        app.registerActivityLifecycleCallbacks(this)
    }

    private fun covers(activity: Activity) = !activity.javaClass.name.startsWith("cx.ring.tv.")

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!covers(activity)) return
        SkPalette.apply(activity)
        activity.theme.applyStyle(R.style.ThemeOverlay_Sk, true)
    }

    override fun onActivityResumed(activity: Activity) {
        if (covers(activity) && SkPalette.isStale(activity)) activity.recreate()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!covers(activity) || activity !is FragmentActivity) return
        // Preference screens: the icon of every row coloured by what the row does.
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                if (f is PreferenceFragmentCompat) try { SkRowIcons.tintPreferences(f.preferenceScreen) } catch (ignored: Throwable) {}
            }
        }, true)
    }
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
