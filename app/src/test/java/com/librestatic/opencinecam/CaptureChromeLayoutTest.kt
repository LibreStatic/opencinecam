/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureChromeLayoutTest {

    private fun family(width: Float, height: Float) = captureLayoutFamily(width, height)

    @Test fun enlargingTheScopesGrowsTheirDockInPlace() {
        // Resting sizes do not depend on the room.
        assertEquals(SCOPE_TRAY_HEIGHT_DP, scopeTrayHeightDp(false, 650f))
        assertEquals(SCOPE_STRIP_WIDTH_DP, scopeStripWidthDp(false, 914f))
        // Phone portrait: most of the room between the top bar and the deck, a band of frame left.
        val tray = scopeTrayHeightDp(true, 650f)
        assertEquals(403f, tray, .5f)
        assert(tray < 650f)
        // Never smaller than at rest, never over 520 dp on a tall window.
        assertEquals(SCOPE_TRAY_HEIGHT_DP, scopeTrayHeightDp(true, 200f))
        assertEquals(520f, scopeTrayHeightDp(true, 2000f))
        assertEquals(365.6f, scopeStripWidthDp(true, 914f), .5f)
        assertEquals(400f, scopeStripWidthDp(true, 2000f))
        // The inspector may outgrow its room and scroll when enlarged.
        assertEquals(320f, inspectorScopesMaxHeightDp(false, 600f))
        assertEquals(480f, inspectorScopesMaxHeightDp(true, 300f))
    }

    @Test fun pixelFoldCoverLandscapeUsesSideRails() {
        // 2424 x 1080 px at 390 dpi (2.4375 density), before the safe-drawing insets.
        assertEquals(CaptureLayoutFamily.SIDE_RAILS, family(2424f / 2.4375f, 1080f / 2.4375f))
        // And after typical landscape insets (cutout side and gesture bar).
        assertEquals(CaptureLayoutFamily.SIDE_RAILS, family(950f, 420f))
    }

    @Test fun phonePortraitAndCoverScreensUseCompactPortrait() {
        assertEquals(CaptureLayoutFamily.COMPACT_PORTRAIT, family(1080f / 2.4375f, 2424f / 2.4375f))
        assertEquals(CaptureLayoutFamily.COMPACT_PORTRAIT, family(411f, 914f))
        assertEquals(CaptureLayoutFamily.COMPACT_PORTRAIT, family(360f, 380f))
    }

    @Test fun commonPhoneLandscapeUsesSideRails() {
        assertEquals(CaptureLayoutFamily.SIDE_RAILS, family(800f, 360f))
        assertEquals(CaptureLayoutFamily.SIDE_RAILS, family(2520f / 2.625f, 1080f / 2.625f))
    }

    @Test fun innerFoldAndTabletPortraitUseTheStackedDeck() {
        // Pixel Fold inner screen, both orientations.
        assertEquals(CaptureLayoutFamily.STACKED, family(841f, 701f))
        assertEquals(CaptureLayoutFamily.STACKED, family(701f, 841f))
        // Tablet portrait (MEDIUM width) keeps the deck rather than a narrow frame beside an inspector.
        assertEquals(CaptureLayoutFamily.STACKED, family(800f, 1280f))
    }

    @Test fun tabletLandscapeAndDesktopKeepTheInspectorOpen() {
        assertEquals(CaptureLayoutFamily.INSPECTOR, family(1280f, 800f))
        assertEquals(CaptureLayoutFamily.INSPECTOR, family(WINDOW_LARGE_MIN_WIDTH_DP, WINDOW_MEDIUM_MIN_HEIGHT_DP))
        assertEquals(CaptureLayoutFamily.STACKED, family(WINDOW_LARGE_MIN_WIDTH_DP - 1f, 800f))
        // A wide but short desktop window has no height for the inspector's slots and REC.
        assertEquals(CaptureLayoutFamily.SIDE_RAILS, family(1400f, 420f))
    }

    @Test fun heightThresholdIsExclusive() {
        assertEquals(CaptureLayoutFamily.SIDE_RAILS, family(1000f, WINDOW_MEDIUM_MIN_HEIGHT_DP - 1f))
        assertEquals(CaptureLayoutFamily.STACKED, family(1000f, WINDOW_MEDIUM_MIN_HEIGHT_DP))
    }

    @Test fun landscapeTooNarrowForRailAndColumnKeepsADeck() {
        val chrome = CAPTURE_RAIL_WIDTH_DP + SIDE_COLUMN_WIDTH_DP
        assertEquals(CaptureLayoutFamily.STACKED, family(400f + chrome - 1f, 400f))
        assertEquals(CaptureLayoutFamily.SIDE_RAILS, family(400f + chrome, 400f))
    }

    @Test fun degenerateSizesFallBackToCompactPortrait() {
        assertEquals(CaptureLayoutFamily.COMPACT_PORTRAIT, family(400f, 400f))
        assertEquals(CaptureLayoutFamily.COMPACT_PORTRAIT, family(0f, 0f))
        assertEquals(CaptureLayoutFamily.COMPACT_PORTRAIT, family(Float.NaN, 300f))
        assertEquals(CaptureLayoutFamily.COMPACT_PORTRAIT, family(900f, -1f))
    }

    @Test fun hingeControlsPaneNeverUsesRailsOrInspector() {
        // Tabletop: the lower half of an unfolded screen.
        assertEquals(CaptureLayoutFamily.STACKED, hingePaneLayoutFamily(841f, 330f))
        assertEquals(CaptureLayoutFamily.STACKED, hingePaneLayoutFamily(1400f, 800f))
        // Book posture: one narrow upright half.
        assertEquals(CaptureLayoutFamily.COMPACT_PORTRAIT, hingePaneLayoutFamily(420f, 840f))
    }

    @Test fun stackedPaneDocksWhereTheFrameStaysLarger() {
        // Fold inner portrait: a 16:9 frame is width-bound, so the pane goes under it.
        assertEquals(PaneDock.BOTTOM, stackedPaneDock(673f, 640f, 300f, 260f, 16f / 9f))
        // Fold inner landscape: the frame is height-bound, so the pane goes beside it.
        assertEquals(PaneDock.END, stackedPaneDock(841f, 470f, 360f, 260f, 16f / 9f))
        // Without a ratio, or with equal areas, the side wins.
        assertEquals(PaneDock.END, stackedPaneDock(800f, 800f, 200f, 200f, null))
    }

    @Test fun stackedSidePaneIsCapped() {
        assertEquals(SIDE_PANE_MAX_WIDTH_DP, stackedSidePaneWidth(1100f), 0.01f)
        assertEquals(700f * SIDE_PANE_MAX_FRACTION, stackedSidePaneWidth(700f), 0.01f)
    }

    @Test fun reservedViewportWithoutReserveMatchesTheRecordingViewport() {
        val pane = reservedPreviewViewport(1080f, 1774f, deckSpace = 500f, expansion = 0.5f, CaptureFrameReserve.None, 9f / 16f)
        assertEquals(recordingPreviewViewport(1080f, 2274f, 0f, 500f, 9f / 16f, 0.5f), pane)
    }

    @Test fun reservedViewportFitsTheFrameBesideAnEndPane() {
        // 1000 x 600 pane, 400 px pane at the end: a 16:9 frame fits 600 x 337.5, centred in what is left.
        val viewport = reservedPreviewViewport(1000f, 600f, 0f, 0f, CaptureFrameReserve(end = 400f), 16f / 9f)
        assertEquals(0f, viewport.left, 0.01f)
        assertEquals(600f, viewport.width, 0.01f)
        assertEquals(337.5f, viewport.height, 0.01f)
        assertEquals((600f - 337.5f) / 2f, viewport.top, 0.01f)
        val mirrored = reservedPreviewViewport(1000f, 600f, 0f, 0f, CaptureFrameReserve(end = 400f), 16f / 9f, rightToLeft = true)
        assertEquals(400f, mirrored.left, 0.01f)
    }

    @Test fun railsViewportRestsBetweenTheRailsAndGrowsOverThemDuringATake() {
        // 2400 x 1080 window, 200 px rail and 500 px column: a 1700 x 1080 pane.
        val resting = railsPreviewViewport(1700f, 1080f, 200f, 500f, 0f, CaptureFrameReserve.None, 16f / 9f)
        assertEquals(reservedPreviewViewport(1700f, 1080f, 0f, 0f, CaptureFrameReserve.None, 16f / 9f), resting)
        // Rails gone: the 16:9 frame is height-bound at 1920 x 1080, centred on the window.
        val grown = railsPreviewViewport(1700f, 1080f, 200f, 500f, 1f, CaptureFrameReserve.None, 16f / 9f)
        assertEquals(1920f, grown.width, 0.01f)
        assertEquals(1080f, grown.height, 0.01f)
        assertEquals((2400f - 1920f) / 2f - 200f, grown.left, 0.01f)
        assertEquals(0f, grown.top, 0.01f)
    }

    @Test fun reservedViewportKeepsTheFrameAboveABottomTray() {
        val viewport = reservedPreviewViewport(1080f, 1600f, 400f, 0f, CaptureFrameReserve(bottom = 500f), 3f / 4f)
        // 1100 px left: a 3:4 frame is height-bound at 825 x 1100.
        assertEquals(1100f, viewport.height, 0.01f)
        assertEquals(825f, viewport.width, 0.01f)
        assertEquals(0f, viewport.top, 0.01f)
        // While recording the hidden deck gives its room back under the tray.
        val recording = reservedPreviewViewport(1080f, 1600f, 400f, 1f, CaptureFrameReserve(bottom = 500f), 3f / 4f)
        assertEquals(1080f, recording.width, 0.01f)
    }

    @Test fun stackedViewportFitsTallImagesBetweenBarAndDeck() {
        // 9:16 in a 1080 x 2400 window with a 126 px bar and a 500 px deck: 1774 px of height
        // leave 997.9 px of width, centred, starting right under the bar.
        val viewport = stackedPreviewViewport(1080f, 2400f, topInset = 126f, bottomInset = 500f, ratio = 9f / 16f)
        assertEquals(1774f, viewport.height, 0.01f)
        assertEquals(1774f * 9f / 16f, viewport.width, 0.01f)
        assertEquals((1080f - 1774f * 9f / 16f) / 2f, viewport.left, 0.01f)
        assertEquals(126f, viewport.top, 0.01f)
    }

    @Test fun stackedViewportCentresWideImagesInTheGap() {
        // 3:4 at 1080 wide is 1440 tall, centred in the 1774 px gap.
        val viewport = stackedPreviewViewport(1080f, 2400f, topInset = 126f, bottomInset = 500f, ratio = 3f / 4f)
        assertEquals(1080f, viewport.width, 0.01f)
        assertEquals(1440f, viewport.height, 0.01f)
        assertEquals(126f + (1774f - 1440f) / 2f, viewport.top, 0.01f)
    }

    @Test fun stackedViewportNeverGoesNegative() {
        val viewport = stackedPreviewViewport(400f, 300f, topInset = 200f, bottomInset = 200f, ratio = 1f)
        assertEquals(0f, viewport.height, 0.01f)
        assertEquals(200f, viewport.top, 0.01f)
    }

    @Test fun recordingViewportGrowsIntoTheDeckSpace() {
        // 9:16 is height-limited above the 500 px deck; without it, it fills the 1080 px width.
        val rest = recordingPreviewViewport(1080f, 2400f, topInset = 126f, deckHeight = 500f, ratio = 9f / 16f, fraction = 0f)
        assertEquals(stackedPreviewViewport(1080f, 2400f, topInset = 126f, bottomInset = 500f, ratio = 9f / 16f), rest)
        val full = recordingPreviewViewport(1080f, 2400f, topInset = 126f, deckHeight = 500f, ratio = 9f / 16f, fraction = 1f)
        assertEquals(1080f, full.width, 0.01f)
        assertEquals(1920f, full.height, 0.01f)
        assertEquals(0f, full.left, 0.01f)
        assertEquals(126f + (2274f - 1920f) / 2f, full.top, 0.01f)
        val half = recordingPreviewViewport(1080f, 2400f, topInset = 126f, deckHeight = 500f, ratio = 9f / 16f, fraction = 0.5f)
        assertEquals((rest.width + full.width) / 2f, half.width, 0.01f)
        assertEquals((rest.top + full.top) / 2f, half.top, 0.01f)
        assertEquals(half.width / (9f / 16f), half.height, 0.5f)
    }

    @Test fun recordingViewportKeepsAFrameTheDeckDoesNotLimit() {
        // 3:4 is already as wide as the window, so it must not slide down into the deck space.
        val rest = stackedPreviewViewport(1080f, 2400f, topInset = 126f, bottomInset = 500f, ratio = 3f / 4f)
        assertEquals(rest, recordingPreviewViewport(1080f, 2400f, topInset = 126f, deckHeight = 500f, ratio = 3f / 4f, fraction = 1f))
    }
}
