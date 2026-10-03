/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

/**
 * How the capture chrome is arranged around the viewfinder, chosen from the window classes.
 *
 * - [COMPACT_PORTRAIT]: a phone in portrait or a foldable's cover screen. A slim status bar, the
 *   F-keys as icons over the viewfinder edge, and a deck of five slots over the gallery, the mode
 *   wheel and REC. Panels open as sheets docked above the deck.
 * - [STACKED]: a tablet in portrait, a foldable's inner screen and mid-size desktop windows. A
 *   top bar, a deck of slots over MODE ▾ and REC, and panels in a pane beside or under the frame,
 *   whichever leaves the frame larger.
 * - [SIDE_RAILS]: a short landscape window (a phone turned sideways). An icon rail at the start,
 *   the viewfinder at full height, and a column at the end with MODE ▾, the slots and REC; an open
 *   panel takes that column's place.
 * - [INSPECTOR]: a tablet in landscape or a large desktop window. An icon rail at the start and an
 *   inspector at the end that stays open, with the slots, the scopes and REC.
 */
internal enum class CaptureLayoutFamily { COMPACT_PORTRAIT, STACKED, SIDE_RAILS, INSPECTOR }

/** Icon rail at the start of the side-rail and inspector layouts: one 48 dp key plus margins. */
internal const val CAPTURE_RAIL_WIDTH_DP = 72f

/** End column of the side-rail layout: MODE ▾, the five slots and REC. */
internal const val SIDE_COLUMN_WIDTH_DP = 240f

/** A panel replacing the side-rail column; the frame gives up the difference. */
internal const val SIDE_PANE_WIDTH_DP = 320f

/** Always-open inspector of large windows. Fixed, so the viewfinder never reflows under it. */
internal const val INSPECTOR_WIDTH_DP = 360f

/** A side pane of the stacked layout: at most this wide, and never more than [SIDE_PANE_MAX_FRACTION]. */
internal const val SIDE_PANE_MAX_WIDTH_DP = 380f
internal const val SIDE_PANE_MAX_FRACTION = 0.45f

/** Scopes beside the side-rail column, between it and the frame. */
internal const val SCOPE_STRIP_WIDTH_DP = 180f

/** Scope tray between the viewfinder and the deck of the compact and stacked layouts. */
internal const val SCOPE_TRAY_HEIGHT_DP = 200f

/** A docked bottom sheet never covers more than this share of the window height. */
internal const val DOCKED_SHEET_MAX_FRACTION = 0.55f

/** A docked bottom sheet outside compact portrait is at most this wide, centred on the deck. */
internal const val DOCKED_SHEET_MAX_WIDTH_DP = 640f

/** Height of the top bar over the viewfinder in the stacked layout. */
internal const val STACKED_TOP_BAR_HEIGHT_DP = 56f

/** Height of the slim top bar that compact portrait windows use. */
internal const val SLIM_TOP_BAR_HEIGHT_DP = 48f

/**
 * Chooses the chrome arrangement from the safe window size in dp.
 *
 * Tablet portrait (a MEDIUM width) takes [STACKED], not the inspector: a 360 dp column would leave
 * a portrait frame narrower than the phone's, while the stacked deck costs height the window has.
 * Short landscape windows take side rails only when the frame left between rail and column is at
 * least as wide as it is tall; otherwise a deck is the better use of the space.
 */
internal fun captureLayoutFamily(widthDp: Float, heightDp: Float): CaptureLayoutFamily {
    if (!(widthDp > 0f) || !(heightDp > 0f)) return CaptureLayoutFamily.COMPACT_PORTRAIT
    val width = windowWidthClass(widthDp)
    val height = windowHeightClass(heightDp)
    return when {
        width == WindowWidthClass.LARGE && height != WindowHeightClass.COMPACT -> CaptureLayoutFamily.INSPECTOR
        widthDp > heightDp && height == WindowHeightClass.COMPACT &&
            widthDp - CAPTURE_RAIL_WIDTH_DP - SIDE_COLUMN_WIDTH_DP >= heightDp -> CaptureLayoutFamily.SIDE_RAILS
        width == WindowWidthClass.COMPACT -> CaptureLayoutFamily.COMPACT_PORTRAIT
        else -> CaptureLayoutFamily.STACKED
    }
}

/**
 * The family of a hinge split's controls pane. The frame lives on the other side of the hinge, so
 * neither rails nor an inspector apply: a narrow upright pane takes the compact deck, the rest the
 * stacked one.
 */
internal fun hingePaneLayoutFamily(widthDp: Float, heightDp: Float): CaptureLayoutFamily =
    if (windowWidthClass(widthDp) == WindowWidthClass.COMPACT && heightDp >= widthDp) CaptureLayoutFamily.COMPACT_PORTRAIT
    else CaptureLayoutFamily.STACKED

/** Where a stacked layout docks an open pane. */
internal enum class PaneDock { END, BOTTOM }

/**
 * Docks a pane where it costs the frame least: beside it or under it, by which leaves the larger
 * fitted frame. A landscape frame in a tall pane keeps its width with the pane under it; the same
 * frame in a wide pane keeps its height with the pane beside it. Ties go to the side.
 */
internal fun stackedPaneDock(paneWidth: Float, paneHeight: Float, endWidth: Float, bottomHeight: Float, ratio: Float?): PaneDock {
    val beside = fittedPreviewViewport((paneWidth - endWidth).coerceAtLeast(0f), paneHeight, ratio)
    val under = fittedPreviewViewport(paneWidth, (paneHeight - bottomHeight).coerceAtLeast(0f), ratio)
    return if (under.width * under.height > beside.width * beside.height) PaneDock.BOTTOM else PaneDock.END
}

/** Width of a stacked side pane in a window [windowWidth] wide. */
internal fun stackedSidePaneWidth(windowWidth: Float): Float =
    minOf(SIDE_PANE_MAX_WIDTH_DP, windowWidth * SIDE_PANE_MAX_FRACTION)

/**
 * Space a pane or tray takes from the frame, in pixels. The viewfinder pane keeps its layout size
 * (a resized surface is reattached, which reconfigures a session mid-take), so the frame is moved
 * and scaled into what is left instead.
 */
internal data class CaptureFrameReserve(val end: Float = 0f, val bottom: Float = 0f) {
    companion object { val None = CaptureFrameReserve() }
}

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

/** How long the frame takes to move out of the way of a pane or the scopes, and back. */
internal const val FRAME_RESERVE_MS = 220

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

/**
 * The frame inside a viewfinder pane of [paneWidth] × [paneHeight] (its own pixel space), after a
 * pane or tray took [reserve] from its end or bottom edge. [deckSpace] × [expansion] is the room a
 * hidden deck gives back under the pane while recording. Without a reserve this is the recording
 * viewport, so a frame the deck does not limit stays put.
 */
internal fun reservedPreviewViewport(
    paneWidth: Float,
    paneHeight: Float,
    deckSpace: Float,
    expansion: Float,
    reserve: CaptureFrameReserve,
    ratio: Float?,
    rightToLeft: Boolean = false,
): PreviewViewport {
    if (reserve.end <= 0f && reserve.bottom <= 0f) {
        return recordingPreviewViewport(paneWidth, paneHeight + deckSpace, 0f, deckSpace, ratio, expansion)
    }
    val width = (paneWidth - reserve.end.coerceAtLeast(0f)).coerceAtLeast(0f)
    val height = (paneHeight + deckSpace * expansion.coerceIn(0f, 1f) - reserve.bottom.coerceAtLeast(0f)).coerceAtLeast(0f)
    val inner = fittedPreviewViewport(width, height, ratio)
    return if (rightToLeft) inner.copy(left = inner.left + reserve.end.coerceAtLeast(0f)) else inner
}
