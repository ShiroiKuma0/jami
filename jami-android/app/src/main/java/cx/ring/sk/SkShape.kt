/*
 * 白い熊 fork (sk): the fork's own shapes, drawn in code.
 *
 * A shape the fork added (a dialog panel, a sheet, a badge, the answer and decline rings …) is
 * referenced from upstream's layouts and styles as @drawable/…, so it has to stay a drawable
 * resource — but it must carry no colour in XML. Its file is a stub that names this class and
 * says, in plain attributes, which ROLES fill and frame it:
 *
 *   <drawable class="cx.ring.sk.SkShape" shape="rect" fill="sk_role_dialog_ground"
 *       stroke="sk_role_dialog_frame" strokeDp="2" radiusDp="16" />
 *
 * Attributes (all optional): shape = rect | oval; fill, stroke, ripple = a colour name
 * (sk_role_… is read from the role table, pref_<key> from the older ColorPrefs roles, any other
 * name from the resources, "transparent" is none); fillAlpha = 00–FF over the fill; strokeDp; radiusDp or radiusDimen (a dimen name);
 * corners = "top" for round top corners only; under = a drawable name drawn beneath.
 *
 * It keeps no constant state, so it is built anew — from the colours of that moment — every
 * time it is loaded.
 */
package cx.ring.sk

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import androidx.annotation.Keep
import org.xmlpull.v1.XmlPullParser

@Keep
class SkShape : DrawableWrapper(null) {
    @SuppressLint("DiscouragedApi")
    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        val d = r.displayMetrics.density
        fun attr(name: String): String? = attrs.getAttributeValue(null, name)
        // The resource package is the application id; any of the app's own resources names it.
        val pkg = try { r.getResourcePackageName(cx.ring.R.color.sk_role_accent) } catch (e: Exception) { "" }
        fun color(name: String?): Int? {
            if (name.isNullOrEmpty() || name == "transparent") return null
            // pref_<key>: one of the older per-surface roles of ColorPrefs.
            if (name.startsWith("pref_")) return skPref(name.removePrefix("pref_"))
            if (name.startsWith("sk_role_")) {
                val role = SkRole.entries.firstOrNull { it.name.equals(name.removePrefix("sk_role_"), ignoreCase = true) }
                if (role != null) return skColor(role)
            }
            val id = r.getIdentifier(name, "color", pkg)
            return if (id != 0) r.getColor(id, theme) else null
        }
        var fill = color(attr("fill")) ?: Color.TRANSPARENT
        attr("fillAlpha")?.toIntOrNull(16)?.let { fill = (fill and 0x00FFFFFF) or (it shl 24) }
        val stroke = color(attr("stroke"))
        val strokePx = Math.round((attr("strokeDp")?.toFloatOrNull() ?: 0f) * d)
        var radius = (attr("radiusDp")?.toFloatOrNull() ?: 0f) * d
        attr("radiusDimen")?.let { name ->
            val id = r.getIdentifier(name, "dimen", pkg)
            if (id != 0) radius = r.getDimension(id)
        }
        val oval = attr("shape") == "oval"
        fun body(solid: Int) = GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            setColor(solid)
            if (stroke != null && strokePx > 0) setStroke(strokePx, stroke)
            if (!oval && radius > 0f) {
                if (attr("corners") == "top") cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                else cornerRadius = radius
            }
        }
        var built: Drawable = body(fill)
        color(attr("ripple"))?.let { ripple ->
            built = RippleDrawable(ColorStateList.valueOf(ripple), built, body(Color.WHITE))
        }
        attr("under")?.let { name ->
            val id = r.getIdentifier(name, "drawable", pkg)
            val under = if (id != 0) try { r.getDrawable(id, theme) } catch (e: Exception) { null } else null
            if (under != null) built = LayerDrawable(arrayOf(under, built))
        }
        drawable = built
    }
}
