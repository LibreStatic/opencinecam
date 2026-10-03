/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** The scopes the panel can show, in tab order. */
internal enum class ScopeTab { WAVEFORM, VECTORSCOPE, FALSE_COLOR, HISTOGRAM }

/** One tab per scope the operator turned on; the histogram joins only when the host feeds it. */
internal fun enabledScopeTabs(waveform: Boolean, vectorscope: Boolean, falseColor: Boolean, histogram: Boolean): List<ScopeTab> =
    buildList {
        if (waveform) add(ScopeTab.WAVEFORM)
        if (vectorscope) add(ScopeTab.VECTORSCOPE)
        if (falseColor) add(ScopeTab.FALSE_COLOR)
        if (histogram) add(ScopeTab.HISTOGRAM)
    }

/** The remembered tab while it is still enabled, otherwise the first enabled one. */
internal fun resolveScopeTab(saved: ScopeTab?, enabled: List<ScopeTab>): ScopeTab? =
    saved?.takeIf { it in enabled } ?: enabled.firstOrNull()

/** The tab after [current], wrapping; the single "next scope" key of the narrowest header. */
internal fun nextScopeTab(current: ScopeTab?, enabled: List<ScopeTab>): ScopeTab? {
    if (enabled.isEmpty()) return null
    val index = enabled.indexOf(current)
    return enabled[(index + 1).mod(enabled.size)]
}

/** How the tabs are labelled: full names, short codes ("WFM"), or one key that steps through them. */
internal enum class ScopeTabStyle { FULL, SHORT, CYCLE }

/** Header layout: the tab style, and whether the status chip still fits on the same row. */
internal data class ScopeHeaderPlan(val tabStyle: ScopeTabStyle, val chipInHeader: Boolean)

/** Padding a tab adds around its text. */
internal const val SCOPE_TAB_PADDING_DP = 20f
/** A tab and every header key are touch targets. */
internal const val SCOPE_TOUCH_DP = 48f
/** The cycle key's chevron plus its gap. */
internal const val SCOPE_CYCLE_GLYPH_DP = 18f

/**
 * Picks the richest header that fits [availableDp], measured from the localised label widths so
 * a long translation degrades to short codes instead of clipping. The chip stays on the header
 * row when it fits; otherwise the panel gives it a line of its own rather than cover the trace.
 * A single scope has no tabs, only a title, so it is not held to the touch-target width.
 */
internal fun scopeHeaderPlan(availableDp: Float, fullLabelsDp: List<Float>, shortLabelsDp: List<Float>,
    chipDp: Float, actionKeys: Int): ScopeHeaderPlan {
    val keys = actionKeys * SCOPE_TOUCH_DP
    val minTab = if (fullLabelsDp.size > 1) SCOPE_TOUCH_DP else 0f
    fun tabs(labels: List<Float>) = labels.sumOf { maxOf(it + SCOPE_TAB_PADDING_DP, minTab).toDouble() }.toFloat()
    val cycle = maxOf((shortLabelsDp.maxOrNull() ?: 0f) + SCOPE_TAB_PADDING_DP + SCOPE_CYCLE_GLYPH_DP, SCOPE_TOUCH_DP)
    val full = tabs(fullLabelsDp)
    val short = tabs(shortLabelsDp)
    // One scope needs no cycle key; its short title is the last resort.
    val last = if (fullLabelsDp.size <= 1) short else cycle
    // Full and short tabs with the chip, then without it, before collapsing to one key.
    val candidates = listOf(
        Triple(ScopeTabStyle.FULL, full, true), Triple(ScopeTabStyle.SHORT, short, true),
        Triple(ScopeTabStyle.FULL, full, false), Triple(ScopeTabStyle.SHORT, short, false),
        Triple(ScopeTabStyle.CYCLE, last, true),
    )
    for ((style, width, chip) in candidates) {
        if (width + keys + (if (chip) chipDp else 0f) <= availableDp) return ScopeHeaderPlan(style, chip)
    }
    return ScopeHeaderPlan(ScopeTabStyle.CYCLE, false)
}

/** Height each scope needs to read well when the panel stacks them in a tall pane. */
internal fun stackedScopeHeightDp(tab: ScopeTab, widthDp: Float): Float = when (tab) {
    ScopeTab.WAVEFORM -> (widthDp * .45f).coerceIn(120f, 220f)
    ScopeTab.VECTORSCOPE -> minOf(widthDp, 260f).coerceAtLeast(140f)
    ScopeTab.FALSE_COLOR -> 112f
    ScopeTab.HISTOGRAM -> (widthDp * .3f).coerceIn(80f, 140f)
}

/** Title line above each stacked scope. */
internal const val SCOPE_SECTION_LABEL_DP = 20f

/**
 * A tall side pane shows every enabled scope at once; anything shorter shows one at a time
 * behind tabs, so no scope is ever squeezed or scrolled out of view.
 */
internal fun scopePanelStacks(widthDp: Float, contentHeightDp: Float, tabs: List<ScopeTab>): Boolean =
    tabs.size > 1 && tabs.sumOf { (stackedScopeHeightDp(it, widthDp) + SCOPE_SECTION_LABEL_DP).toDouble() } <= contentHeightDp

/** The largest square centred in a [width] × [height] area; the vectorscope is never stretched. */
internal fun scopeSquareFit(width: Float, height: Float): Rect {
    val side = minOf(width, height).coerceAtLeast(0f)
    val left = (width - side) / 2f
    val top = (height - side) / 2f
    return Rect(left, top, left + side, top + side)
}

/** Waveform levels (percent of code range) that get a graticule line. */
internal val WAVEFORM_SCALE_LINES = listOf(0, 25, 50, 75, 100)

/** Levels that also get a number; a short trace keeps only the two ends so labels never collide. */
internal fun waveformScaleLabels(heightDp: Float): List<Int> = if (heightDp >= 64f) listOf(100, 50, 0) else listOf(100, 0)

/** Y of a waveform level inside a trace that spans [top] to [top] + [height]; 100 % is at the top. */
internal fun waveformLevelY(percent: Int, top: Float, height: Float): Float = top + height * (1f - percent.coerceIn(0, 100) / 100f)

/**
 * BT.709 100 % colour bar chroma as (Cb, Cr) for R, Yl, G, Cy, B, Mg, matching how the analysis
 * bins the vectorscope (+Cb right, +Cr up, ±0.5 at the square's edge).
 */
internal val VECTORSCOPE_BARS: List<Pair<Float, Float>> = listOf(
    -.1146f to .5f, -.5f to .0458f, -.3854f to -.4542f, .1146f to -.5f, .5f to -.0458f, .3854f to .4542f,
)

/** Graticule target boxes sit on the 75 % bars, as on a broadcast vectorscope. */
internal const val VECTORSCOPE_TARGET_LEVEL = .75f

/** The skin-tone (I) line angle, counter-clockwise from +Cb. */
internal const val VECTORSCOPE_SKIN_LINE_DEGREES = 123f

/** Screen point of a (Cb, Cr) value inside the vectorscope square. */
internal fun vectorscopePoint(cb: Float, cr: Float, square: Rect): Offset =
    Offset(square.center.x + cb * square.width, square.center.y - cr * square.height)

/** Fractions of the 0–100 % ramp each false-colour band covers, from black to clip. */
internal fun falseColorRampStops(black: Int, shadow: Int, highlight: Int, clip: Int): List<ClosedFloatingPointRange<Float>> {
    val cuts = listOf(0, black, shadow, highlight, clip, 100).map { it.coerceIn(0, 100) / 100f }
    return cuts.zipWithNext { a, b -> a..maxOf(a, b) }
}

/** The picture inside the overlay after the GPU viewfinder's letterbox scale; centred. */
internal fun monitoringImageRect(width: Float, height: Float, scaleX: Float, scaleY: Float): Rect {
    val w = width * scaleX.coerceAtLeast(0f)
    val h = height * scaleY.coerceAtLeast(0f)
    return Rect((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
}

/**
 * The part of an overlay at ([left], [top]) in its parent that the parent shows. A FILL
 * viewfinder is larger than the window, so anything placed by the overlay's own corners lands
 * off screen.
 */
internal fun overlayVisibleRect(left: Float, top: Float, width: Float, height: Float, parentWidth: Float, parentHeight: Float): Rect =
    Rect(maxOf(0f, -left), maxOf(0f, -top), minOf(width, parentWidth - left), minOf(height, parentHeight - top))

/** [image] clipped to [visible]; the whole image when nothing of it would remain. */
internal fun visibleImageRect(image: Rect, visible: Rect?): Rect {
    if (visible == null) return image
    val clipped = image.intersect(visible)
    return if (clipped.width > 0f && clipped.height > 0f) clipped else image
}

/** Overlay histogram: top start of the visible picture, a fixed share of its width within bounds. */
internal fun overlayHistogramRect(image: Rect, density: Float, insetDp: Float = 12f): Rect {
    val inset = insetDp * density
    val width = (image.width * .28f).coerceIn(96f * density, 240f * density).coerceAtMost((image.width - 2 * inset).coerceAtLeast(0f))
    val height = (width * .34f).coerceAtMost((image.height * .25f).coerceAtLeast(0f))
    return Rect(image.left + inset, image.top + inset, image.left + inset + width, image.top + inset + height)
}

/**
 * Size of the scopes panel when it has to float over the picture: big enough for a readable
 * vectorscope, never more than about a third of the width or two thirds of the height.
 */
internal fun overlayScopesPanelSizeDp(imageWidthDp: Float, imageHeightDp: Float, endPaddingDp: Float): Pair<Float, Float> {
    val width = (imageWidthDp * .36f).coerceIn(160f, 320f).coerceAtMost((imageWidthDp - endPaddingDp - 12f).coerceAtLeast(0f))
    val height = (imageHeightDp * .62f).coerceIn(160f, 440f).coerceAtMost((imageHeightDp - 24f).coerceAtLeast(0f))
    return width to height
}

/**
 * Outline of the union of the active cells in a [columns] × [rows] grid, as unit-square segments
 * in grid coordinates (0..columns, 0..rows). Shared edges between two active cells are dropped,
 * so neighbouring cells read as one region instead of a mesh of boxes.
 */
internal fun cellRegionBoundary(active: List<Boolean>, columns: Int, rows: Int): List<Pair<Offset, Offset>> {
    fun on(column: Int, row: Int) = column in 0 until columns && row in 0 until rows && active.getOrElse(row * columns + column) { false }
    val edges = ArrayList<Pair<Offset, Offset>>()
    for (row in 0 until rows) for (column in 0 until columns) {
        if (!on(column, row)) continue
        val l = column.toFloat(); val t = row.toFloat(); val r = l + 1f; val b = t + 1f
        if (!on(column, row - 1)) edges += Offset(l, t) to Offset(r, t)
        if (!on(column, row + 1)) edges += Offset(l, b) to Offset(r, b)
        if (!on(column - 1, row)) edges += Offset(l, t) to Offset(l, b)
        if (!on(column + 1, row)) edges += Offset(r, t) to Offset(r, b)
    }
    return edges
}
