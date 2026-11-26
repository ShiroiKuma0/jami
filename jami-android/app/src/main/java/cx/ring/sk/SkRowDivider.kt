package cx.ring.sk

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * A thin line between the rows of a list, in the list-divider role (白い熊, 2026-11-26).
 * It starts at the row's text ([insetViewId]), not under the avatar, and is left out next to
 * the selected row, whose own ground already sets it apart.
 */
class SkRowDivider(private val insetViewId: Int) : RecyclerView.ItemDecoration() {
    private val paint = Paint()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val thickness = parent.resources.displayMetrics.density.coerceAtLeast(1f)
        paint.color = skColor(SkRole.LIST_DIVIDER)
        val last = (parent.adapter?.itemCount ?: 0) - 1
        for (i in 0 until parent.childCount) {
            val row = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(row)
            if (position == RecyclerView.NO_POSITION || position >= last) continue
            if (row.isActivated) continue
            val next = parent.findViewHolderForAdapterPosition(position + 1)?.itemView
            if (next?.isActivated == true) continue
            val text = row.findViewById<View>(insetViewId)
            val left = row.left + (text?.left ?: row.paddingLeft)
            val right = row.right - row.paddingRight
            val y = row.bottom + row.translationY
            c.drawRect(left.toFloat(), y - thickness / 2, right.toFloat(), y + thickness / 2, paint)
        }
    }
}
