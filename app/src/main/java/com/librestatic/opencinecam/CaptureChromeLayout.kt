/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

/**
 * How the capture chrome is arranged around the viewfinder.
 *
 * [STACKED] keeps the top bar and a control deck across the bottom. [SIDE_RAILS] is for short
 * landscape windows (a phone turned sideways, a foldable's cover screen): a stacked deck there
 * leaves the viewfinder a thin strip, so the controls move into vertical rails at both sides and
 * the viewfinder keeps the full height between them.
 */
internal enum class CaptureChromeLayout { STACKED, SIDE_RAILS }

/** Windows at least this tall have room for the stacked deck below the viewfinder. */
internal const val SIDE_RAIL_MAX_HEIGHT_DP = 480f

/** Start rail: camera actions and the F-keys. Wide enough for two 52 dp F-keys side by side. */
internal const val SIDE_RAIL_START_WIDTH_DP = 120f

/** End rail: the exposure column beside a column with the mode selector and the shutter. */
internal const val SIDE_RAIL_END_WIDTH_DP = 196f

/** Exposure column inside the end rail; the mode column takes what is left. */
internal const val SIDE_RAIL_EXPOSURE_WIDTH_DP = 68f

/**
 * Chooses the chrome arrangement from the safe window size in dp. Rails are only used when the
 * window is landscape, short, and still leaves a viewfinder at least as wide as it is tall once
 * both rails are taken out; otherwise the stacked deck is the better use of the space.
 */
internal fun captureChromeLayout(widthDp: Float, heightDp: Float): CaptureChromeLayout = when {
    !(widthDp > 0f) || !(heightDp > 0f) -> CaptureChromeLayout.STACKED
    widthDp <= heightDp -> CaptureChromeLayout.STACKED
    heightDp >= SIDE_RAIL_MAX_HEIGHT_DP -> CaptureChromeLayout.STACKED
    widthDp - SIDE_RAIL_START_WIDTH_DP - SIDE_RAIL_END_WIDTH_DP < heightDp -> CaptureChromeLayout.STACKED
    else -> CaptureChromeLayout.SIDE_RAILS
}

/**
 * The viewfinder bounds between the two rails, in the container's pixel space. The start rail is
 * on the left in a left-to-right layout and on the right in a right-to-left one.
 */
internal fun sideRailPreviewViewport(
    containerWidth: Float,
    containerHeight: Float,
    startRail: Float,
    endRail: Float,
    ratio: Float?,
    rightToLeft: Boolean = false,
): PreviewViewport {
    val leftInset = if (rightToLeft) endRail else startRail
    val rightInset = if (rightToLeft) startRail else endRail
    val inner = fittedPreviewViewport((containerWidth - leftInset - rightInset).coerceAtLeast(0f), containerHeight, ratio)
    return inner.copy(left = inner.left + leftInset)
}

/** Height of the top bar over the viewfinder in the stacked layout. */
internal const val STACKED_TOP_BAR_HEIGHT_DP = 56f

/** Height of the slim top bar that compact portrait windows use. */
internal const val SLIM_TOP_BAR_HEIGHT_DP = 48f

/**
 * The viewfinder between the top bar and the control deck of the stacked layout, in the
 * container's pixel space. Fitting it there instead of behind the chrome keeps the whole frame in
 * view, which is what an operator frames with.
 */
internal fun stackedPreviewViewport(
    containerWidth: Float,
    containerHeight: Float,
    topInset: Float,
    bottomInset: Float,
    ratio: Float?,
): PreviewViewport {
    val inner = fittedPreviewViewport(containerWidth, (containerHeight - topInset - bottomInset).coerceAtLeast(0f), ratio)
    return inner.copy(top = inner.top + topInset)
}

/** How long the stacked viewfinder takes to grow into the deck's space when a take starts. */
internal const val RECORDING_VIEWFINDER_EXPANSION_MS = 280

/**
 * The stacked viewfinder while recording. The deck slides away during a take, so the frame grows
 * into its space as [fraction] goes from 0 (fitted above the deck) to 1 (fitted down to the
 * container's bottom edge). The aspect ratio is kept, so nothing is cropped; a frame whose size
 * the deck does not limit stays where it is rather than sliding down for no gain.
 */
internal fun recordingPreviewViewport(
    containerWidth: Float,
    containerHeight: Float,
    topInset: Float,
    deckHeight: Float,
    ratio: Float?,
    fraction: Float,
): PreviewViewport {
    val resting = stackedPreviewViewport(containerWidth, containerHeight, topInset, deckHeight, ratio)
    val expanded = stackedPreviewViewport(containerWidth, containerHeight, topInset, 0f, ratio)
    if (expanded.width - resting.width < 1f) return resting
    val t = fraction.coerceIn(0f, 1f)
    return PreviewViewport(
        left = resting.left + (expanded.left - resting.left) * t,
        top = resting.top + (expanded.top - resting.top) * t,
        width = resting.width + (expanded.width - resting.width) * t,
        height = resting.height + (expanded.height - resting.height) * t,
    )
}
