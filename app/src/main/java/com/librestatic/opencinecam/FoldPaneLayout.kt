/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

data class FoldPane(val left: Int, val top: Int, val width: Int, val height: Int)
data class FoldPanes(val preview: FoldPane, val controls: FoldPane)

/** Window-coordinate hinge converted to content coordinates, with a gutter even for a zero-width crease. */
fun foldPanes(width: Int, height: Int, originX: Int, originY: Int, hinge: FoldHinge?, gutter: Int, minimum: Int, swap: Boolean): FoldPanes? {
    if (hinge == null || width <= 0 || height <= 0) return null
    val first: FoldPane
    val second: FoldPane
    if (hinge.horizontal) {
        if (hinge.right <= originX || hinge.left >= originX + width) return null
        val start = (hinge.top - originY - gutter / 2).coerceIn(0, height)
        val end = (hinge.bottom - originY + gutter / 2).coerceIn(0, height)
        if (start < minimum || height - end < minimum) return null
        first = FoldPane(0, 0, width, start)
        second = FoldPane(0, end, width, height - end)
    } else {
        if (hinge.bottom <= originY || hinge.top >= originY + height) return null
        val start = (hinge.left - originX - gutter / 2).coerceIn(0, width)
        val end = (hinge.right - originX + gutter / 2).coerceIn(0, width)
        if (start < minimum || width - end < minimum) return null
        first = FoldPane(0, 0, start, height)
        second = FoldPane(end, 0, width - end, height)
    }
    return if (swap) FoldPanes(second, first) else FoldPanes(first, second)
}
