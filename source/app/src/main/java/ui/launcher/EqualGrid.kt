/*
    This file is part of OpenMW-Android.

    OpenMW-Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenMW-Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenMW-Android.  If not, see <https://www.gnu.org/licenses/>.
*/

package ui.launcher

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Lays its children out in equal-width columns, like the button rows of the
 * CubeEvil4 menu. The column count follows the measured width: as many columns
 * as fit [minCellWidthDp], at most [maxColumns]. Every row is as tall as its
 * tallest child, so tiles and buttons side by side line up.
 */
class EqualGrid(
    context: Context,
    private val maxColumns: Int,
    private val minCellWidthDp: Int
) : ViewGroup(context) {

    private val gap = LauncherStyle.dp(context, LauncherStyle.GAP)
    private var columns = 1
    private val rowHeights = ArrayList<Int>()

    private fun visibleChildren(): List<View> =
        (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - paddingLeft - paddingRight
        val minCell = LauncherStyle.dp(context, minCellWidthDp)
        val children = visibleChildren()

        columns = ((inner + gap) / (minCell + gap)).coerceIn(1, maxColumns)
        if (children.isNotEmpty())
            columns = columns.coerceAtMost(children.size)
        val cellWidth = ((inner - gap * (columns - 1)) / columns).coerceAtLeast(0)

        rowHeights.clear()
        var height = paddingTop + paddingBottom
        children.chunked(columns).forEachIndexed { index, row ->
            var rowHeight = 0
            row.forEach { child ->
                child.measure(
                    MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
            // Same height for every child in the row.
            row.forEach { child ->
                if (child.measuredHeight != rowHeight) {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
                    )
                }
            }
            rowHeights += rowHeight
            height += rowHeight + if (index > 0) gap else 0
        }
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inner = (r - l) - paddingLeft - paddingRight
        val cellWidth = ((inner - gap * (columns - 1)) / columns).coerceAtLeast(0)
        var top = paddingTop
        visibleChildren().chunked(columns).forEachIndexed { index, row ->
            val rowHeight = rowHeights.getOrElse(index) { 0 }
            row.forEachIndexed { column, child ->
                val left = paddingLeft + column * (cellWidth + gap)
                child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            }
            top += rowHeight + gap
        }
    }
}
