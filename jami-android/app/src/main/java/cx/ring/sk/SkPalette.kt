/*
 * 白い熊 fork (sk): how a colour role reaches the XML this fork does not draw itself.
 *
 * The fork is rebased onto every upstream release, so upstream's layouts are not edited for
 * colour (the "mixed way", decided for Jami by 白い熊 on 2026-11-22). Two things name the roles:
 *  - the theme overlay ThemeOverlay.Sk (values/sk_theme.xml) points every colour attribute at a
 *    role colour resource, @color/sk_role_…;
 *  - upstream's own colour names (@color/colorOnSurface, @color/background, …), which its
 *    layouts use directly, are each given a role in UPSTREAM below.
 * This object lays the stored colours over all of those resources at run time with a
 * ResourcesLoader (Android 11 and later). Below Android 11 the values in the XML show.
 *
 * A role colour must live in the default values/ only: the loader's table carries the default
 * configuration, so a night-qualified copy in the APK would beat it. That is why the fork has
 * no values-night/colors.xml any more.
 */
package cx.ring.sk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.android.material.color.SkColorLoaderBridge
import cx.ring.utils.ColorPrefs
import java.util.WeakHashMap

object SkPalette {
    private const val TAG = "SkPalette"

    private class Applied(val loader: ResourcesLoader, val generation: Int)

    private val applied = WeakHashMap<Resources, Applied>()
    private val built = WeakHashMap<Activity, Int>()

    @Volatile
    var generation = 1
        private set

    fun changed() {
        ++generation
    }

    /** Lays the roles over the context's resources, and over the application's as well. */
    @Synchronized
    fun apply(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        applyTo(context)
        val application = context.applicationContext
        if (application != null && application !== context) applyTo(application)
        if (context is Activity) built[context] = generation
    }

    /** True when [activity] was inflated from colours that have changed since. */
    fun isStale(activity: Activity): Boolean = (built[activity] ?: generation) != generation

    @RequiresApi(Build.VERSION_CODES.R)
    private fun applyTo(context: Context) {
        val resources = context.resources
        val current = applied[resources]
        if (current != null && current.generation == generation) return
        try {
            val loader = SkColorLoaderBridge.create(context, resourceColors(context)) ?: return
            if (current != null) resources.removeLoaders(current.loader)
            resources.addLoaders(loader)
            applied[resources] = Applied(loader, generation)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not lay the colour roles over the resources", e)
        }
    }

    private class Feed(val role: SkRole? = null, val pref: String? = null, val alpha: Int = -1, val const: Int? = null)

    /** Upstream's (and the libraries') colour names that layouts use directly → the role that feeds each. */
    private val UPSTREAM: Map<String, Feed> = mapOf(
        // grounds
        "background" to Feed(SkRole.BACKGROUND),
        "black" to Feed(SkRole.BACKGROUND),
        "background_secondary" to Feed(SkRole.SURFACE),
        "background_on_surface" to Feed(SkRole.SURFACE),
        "surface" to Feed(SkRole.SURFACE),
        "settings_option_background" to Feed(SkRole.SURFACE),
        "searchbar_color" to Feed(SkRole.BAR_GROUND),
        "icon_background_color" to Feed(SkRole.SURFACE),
        "lighter_gray" to Feed(SkRole.SURFACE),
        "light" to Feed(SkRole.SURFACE),
        "appbar_gradient_color" to Feed(SkRole.BAR_GROUND, alpha = 0xCC),
        "color_bottom_sheet_background" to Feed(SkRole.SURFACE, alpha = 0xF2),
        "edit_text_background" to Feed(SkRole.FIELD_GROUND),
        // accent
        "colorPrimary" to Feed(SkRole.ACCENT),
        "colorSecondary" to Feed(SkRole.ACCENT),
        "color_primary_dark" to Feed(SkRole.ACCENT),
        "color_primary_light" to Feed(SkRole.ACCENT),
        "color_secondary" to Feed(SkRole.ACCENT),
        "textColorAccent" to Feed(SkRole.ACCENT),
        "jami_id_surface_color" to Feed(SkRole.ACCENT),
        "jami_id_edit_text_underline_color" to Feed(SkRole.FIELD_FRAME),
        "colorPrimaryTranslucent" to Feed(SkRole.ACCENT, alpha = 0xC0),
        "colorSecondaryTranslucent" to Feed(SkRole.ACCENT, alpha = 0x80),
        "color_primary_light_translucent" to Feed(SkRole.ACCENT, alpha = 0x80),
        "color_primary_dark_translucent" to Feed(SkRole.ACCENT, alpha = 0x80),
        "button_border" to Feed(SkRole.PILL_FRAME),
        "link_peer_color" to Feed(SkRole.LINK),
        "colorOnPrimary" to Feed(SkRole.ON_ACCENT),
        "colorOnSecondary" to Feed(SkRole.ON_ACCENT),
        "colorOnError" to Feed(SkRole.ON_ACCENT),
        "colorSecondaryContainer" to Feed(SkRole.LIST_SELECTED),
        "chip_background" to Feed(SkRole.LIST_SELECTED),
        "colorError" to Feed(SkRole.DANGER),
        // icons
        "icon_tint" to Feed(SkRole.ICON),
        "icon_color" to Feed(SkRole.ICON),
        "menu_conversation_icons" to Feed(SkRole.ICON),
        // text
        "white" to Feed(SkRole.TEXT),
        "colorOnSurface" to Feed(SkRole.TEXT),
        "text_color" to Feed(SkRole.TEXT),
        "text_color_primary_dark" to Feed(SkRole.TEXT),
        "primary_text_default_material_dark" to Feed(SkRole.TEXT),
        "abc_primary_text_material_light" to Feed(SkRole.TEXT),
        "textColorSecondary" to Feed(SkRole.TEXT_SECONDARY),
        "text_color_secondary_dark" to Feed(SkRole.TEXT_SECONDARY),
        "secondary_text_default_material_dark" to Feed(SkRole.TEXT_SECONDARY),
        "colorOnSecondaryContainer" to Feed(SkRole.TEXT_SECONDARY),
        "msg_display_name" to Feed(SkRole.TEXT_SECONDARY),
        "settings_submenu_header" to Feed(SkRole.TEXT_SECONDARY),
        "jami_id_info_color" to Feed(SkRole.TEXT_SECONDARY),
        "call_missed_text_message" to Feed(SkRole.TEXT_SECONDARY),
        "darker_gray" to Feed(SkRole.TEXT_SECONDARY),
        "grey_400" to Feed(SkRole.TEXT_SECONDARY),
        "grey_500" to Feed(SkRole.TEXT_SECONDARY),
        "text_hint" to Feed(SkRole.TEXT_HINT),
        // floating buttons, call controls
        "fab_background_color" to Feed(SkRole.FAB_GROUND),
        "fab_text_color" to Feed(SkRole.FAB_GLYPH),
        "shiroikuma_call_yellow" to Feed(SkRole.CALL_CTRL),
        "shiroikuma_call_on_yellow" to Feed(SkRole.CALL_CTRL_GROUND),
        "call_accept" to Feed(SkRole.CALL_ACCEPT),
        "call_decline" to Feed(SkRole.CALL_DECLINE),
        "call_missed" to Feed(SkRole.CALL_MISSED),
        "call_button_ripple" to Feed(SkRole.PRESSED),
        // older per-surface roles of ColorPrefs
        "invitation_card_background_color" to Feed(pref = ColorPrefs.INVITE_FILL),
        "invitation_card_text_color" to Feed(pref = ColorPrefs.INVITE_TEXT),
        "invitation_card_on_surface_text_color" to Feed(pref = ColorPrefs.INVITE_TEXT),
        "invitation_card_badge_color" to Feed(pref = ColorPrefs.INVITE_BORDER),
        "invitation_card_on_badge_text_color" to Feed(pref = ColorPrefs.INVITE_FILL),
        "conversation_secondary_background" to Feed(pref = ColorPrefs.MSG_RECEIVED_FILL),
        "online_indicator" to Feed(pref = ColorPrefs.PRESENCE_CONNECTED),
        "available_indicator" to Feed(pref = ColorPrefs.PRESENCE_AVAILABLE),
        // a QR code needs its white ground to be read
        "qr_background" to Feed(const = 0xFFFFFFFF.toInt()),
    )

    @SuppressLint("DiscouragedApi")
    private fun resourceColors(context: Context): Map<Int, Int> {
        val res = context.resources
        val pkg = context.packageName   // the resource package is the application id
        val out = HashMap<Int, Int>()
        fun id(name: String) = res.getIdentifier(name, "color", pkg)
        // every role has a resource of its own, sk_role_<name>
        for (role in SkRole.entries) {
            val rid = id("sk_role_" + role.name.lowercase())
            if (rid != 0) out[rid] = skColor(role)
        }
        for ((name, feed) in UPSTREAM) {
            val rid = id(name)
            if (rid == 0) continue
            var color = when {
                feed.const != null -> feed.const
                feed.role != null -> skColor(feed.role)
                feed.pref != null -> ColorPrefs.getColor(context, feed.pref)
                else -> continue
            }
            if (feed.alpha >= 0) color = (color and 0x00FFFFFF) or (feed.alpha shl 24)
            out[rid] = color
        }
        return out
    }
}
