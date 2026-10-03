/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Settings use a contiguous pane rather than putting editable text across a physical hinge. The
 * screens inside lay out for that pane, so [LocalAdaptiveWindow] is re-measured to its size: half
 * of an unfolded inner screen is a compact window, not the whole display.
 */
@Composable
internal fun HingeSafeSettingsPane(hinge: FoldHinge?, content: @Composable () -> Unit) {
    val window = LocalAdaptiveWindow.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() },
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        val density = LocalDensity.current
        val pane = with(density) {
            hingeSettingsPaneBounds(
                constraints.maxWidth, constraints.maxHeight, origin.x.roundToInt(), origin.y.roundToInt(),
                hinge, gutter = 16.dp.roundToPx(), minimum = 180.dp.roundToPx(),
            )
        }
        val bounds = if (pane == null) Modifier.fillMaxSize() else with(density) {
            Modifier.absoluteOffset(pane.left.toDp(), pane.top.toDp()).size(pane.width.toDp(), pane.height.toDp())
        }
        val paneWindow = remember(pane, window, density) {
            pane?.let { with(density) { AdaptiveWindow(it.width.toDp().value, it.height.toDp().value, window.hardwareKeyboard) } } ?: window
        }
        Box(bounds.clipToBounds().testTag("hinge-safe-settings")) {
            CompositionLocalProvider(LocalAdaptiveWindow provides paneWindow) { content() }
        }
    }
}

/**
 * Settings need one usable pane, not two camera panes. A narrow opposite side must never
 * make editable controls span an intersecting hinge. Prefer the usual bottom/right pane
 * when usable; otherwise choose the larger contiguous side, even in a small window.
 * Coordinates remain physical window pixels, independently of the reading direction.
 */
internal fun hingeSettingsPaneBounds(
    width: Int,
    height: Int,
    originX: Int,
    originY: Int,
    hinge: FoldHinge?,
    gutter: Int,
    minimum: Int,
): FoldPane? {
    require(gutter >= 0 && minimum >= 0)
    if (hinge == null || width <= 0 || height <= 0) return null
    if (hinge.left > hinge.right || hinge.top > hinge.bottom) return null
    val left = hinge.left.toLong() - originX
    val right = hinge.right.toLong() - originX
    val top = hinge.top.toLong() - originY
    val bottom = hinge.bottom.toLong() - originY
    val horizontal = hinge.horizontal
    val axisStart = if (horizontal) top else left
    val axisEnd = if (horizontal) bottom else right
    val extent = if (horizontal) height else width
    val crossStart = if (horizontal) left else top
    val crossEnd = if (horizontal) right else bottom
    val crossExtent = if (horizontal) width else height
    if (crossEnd <= 0 || crossStart >= crossExtent || axisEnd <= 0 || axisStart >= extent) return null

    val firstSize = (axisStart - gutter / 2).coerceIn(0L, extent.toLong()).toInt()
    val secondStart = (axisEnd + (gutter.toLong() + 1) / 2).coerceIn(0L, extent.toLong()).toInt()
    val secondSize = extent - secondStart
    val useSecond = secondSize >= minimum || secondSize >= firstSize
    return if (horizontal) {
        if (useSecond) FoldPane(0, secondStart, width, secondSize) else FoldPane(0, 0, width, firstSize)
    } else {
        if (useSecond) FoldPane(secondStart, 0, secondSize, height) else FoldPane(0, 0, firstSize, height)
    }
}
