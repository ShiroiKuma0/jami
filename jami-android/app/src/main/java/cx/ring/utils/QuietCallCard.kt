/*
 * 白い熊 fork (sk): the card of a quiet incoming call (see QuietCall) — a small window at the top
 * of the display, over whatever app holds the other call: who calls, and Hide / Decline / Answer.
 * A touch on the card itself opens the full call screen.
 */
package cx.ring.utils

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import cx.ring.R
import cx.ring.sk.SkRole
import cx.ring.sk.applySkPill
import cx.ring.sk.skColor
import cx.ring.sk.skPanel

object QuietCallCard {
    private const val TAG = "QuietCallCard"
    private val main = Handler(Looper.getMainLooper())
    private var shownFor: String? = null
    private var card: View? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Shows the card for the call [id]. [onOpen] opens the full call screen, [onDecline] refuses
     * the call, [onAnswer] answers it; each of them also takes the card away.
     */
    fun show(context: Context, id: String, caller: String, onOpen: () -> Unit, onDecline: () -> Unit, onAnswer: () -> Unit) {
        main.post {
            if (!canShow(context)) return@post
            removeNow(context)
            try {
                val app = context.applicationContext
                val density = app.resources.displayMetrics.density
                fun dp(v: Float) = Math.round(v * density)
                fun act(action: () -> Unit) = View.OnClickListener {
                    hide(app, id)
                    try { action() } catch (e: Exception) { Log.w(TAG, "Card action failed", e) }
                }

                val root = LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    background = skPanel(density).apply {
                        setStroke(dp(2f), skColor(SkRole.DIALOG_FRAME))
                    }
                    setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                    setOnClickListener(act(onOpen))
                }
                root.addView(TextView(app).apply {
                    text = app.getString(R.string.notif_incoming_call)
                    textSize = 13f
                    setTextColor(skColor(SkRole.TEXT_SECONDARY))
                })
                root.addView(TextView(app).apply {
                    text = caller
                    textSize = 19f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(skColor(SkRole.DIALOG_TITLE))
                })
                val row = LinearLayout(app).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
                fun pill(label: Int, mainAction: Boolean, danger: Boolean, click: View.OnClickListener) =
                    TextView(app).apply {
                        text = app.getString(label)
                        textSize = 15f
                        maxLines = 1
                        if (danger) applySkPill(main = false, ink = skColor(SkRole.DANGER))
                        else applySkPill(main = mainAction)
                        setOnClickListener(click)
                    }
                val gap = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8f) }
                row.addView(pill(R.string.sk_quiet_call_hide, false, false) { hide(app, id) })
                row.addView(pill(R.string.action_call_decline, false, true, act(onDecline)), LinearLayout.LayoutParams(gap))
                row.addView(pill(R.string.sk_quiet_call_answer, true, false, act(onAnswer)), LinearLayout.LayoutParams(gap))
                root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(10f)
                })

                val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val screenWidth = app.resources.displayMetrics.widthPixels
                val params = WindowManager.LayoutParams(
                    minOf(screenWidth - dp(24f), dp(440f)),
                    WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(44f)
                }
                wm.addView(root, params)
                card = root
                shownFor = id
            } catch (e: Exception) {
                Log.w(TAG, "Can't show the quiet call card", e)
                card = null
                shownFor = null
            }
        }
    }

    /** Takes the card away if it is the one of the call [id]. */
    fun hide(context: Context, id: String) {
        main.post { if (shownFor == id) removeNow(context) }
    }

    fun hideAll(context: Context) {
        main.post { removeNow(context) }
    }

    private fun removeNow(context: Context) {
        val view = card ?: return
        card = null
        shownFor = null
        try {
            (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Can't remove the quiet call card", e)
        }
    }
}
