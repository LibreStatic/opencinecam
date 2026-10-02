/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureChromeLayoutTest {

    @Test fun pixelFoldCoverLandscapeUsesSideRails() {
        // 2424 x 1080 px at 390 dpi (2.4375 density), before the safe-drawing insets.
        assertEquals(CaptureChromeLayout.SIDE_RAILS, captureChromeLayout(2424f / 2.4375f, 1080f / 2.4375f))
        // And after typical landscape insets (cutout side and gesture bar).
        assertEquals(CaptureChromeLayout.SIDE_RAILS, captureChromeLayout(950f, 420f))
    }

    @Test fun pixelFoldCoverPortraitKeepsStackedDeck() {
        assertEquals(CaptureChromeLayout.STACKED, captureChromeLayout(1080f / 2.4375f, 2424f / 2.4375f))
    }

    @Test fun commonPhoneLandscapeUsesSideRails() {
        assertEquals(CaptureChromeLayout.SIDE_RAILS, captureChromeLayout(800f, 360f))
        assertEquals(CaptureChromeLayout.SIDE_RAILS, captureChromeLayout(2520f / 2.625f, 1080f / 2.625f))
    }

    @Test fun nearSquareInnerFoldAndTabletsKeepStackedDeck() {
        // Pixel Fold inner screen, both orientations.
        assertEquals(CaptureChromeLayout.STACKED, captureChromeLayout(841f, 701f))
        assertEquals(CaptureChromeLayout.STACKED, captureChromeLayout(701f, 841f))
        // Landscape tablet.
        assertEquals(CaptureChromeLayout.STACKED, captureChromeLayout(1280f, 800f))
    }

    @Test fun heightThresholdIsExclusive() {
        assertEquals(CaptureChromeLayout.SIDE_RAILS, captureChromeLayout(1000f, SIDE_RAIL_MAX_HEIGHT_DP - 1f))
        assertEquals(CaptureChromeLayout.STACKED, captureChromeLayout(1000f, SIDE_RAIL_MAX_HEIGHT_DP))
    }

    @Test fun landscapeTooNarrowForBothRailsKeepsStackedDeck() {
        val rails = SIDE_RAIL_START_WIDTH_DP + SIDE_RAIL_END_WIDTH_DP
        assertEquals(CaptureChromeLayout.STACKED, captureChromeLayout(400f + rails - 1f, 400f))
        assertEquals(CaptureChromeLayout.SIDE_RAILS, captureChromeLayout(400f + rails, 400f))
    }

    @Test fun squareAndDegenerateSizesKeepStackedDeck() {
        assertEquals(CaptureChromeLayout.STACKED, captureChromeLayout(400f, 400f))
        assertEquals(CaptureChromeLayout.STACKED, captureChromeLayout(0f, 0f))
        assertEquals(CaptureChromeLayout.STACKED, captureChromeLayout(Float.NaN, 300f))
        assertEquals(CaptureChromeLayout.STACKED, captureChromeLayout(900f, -1f))
    }

    @Test fun sideRailViewportFitsBetweenRailsAtFullHeight() {
        // 1000 x 400 container, rails 100 + 200: 700 x 400 left for a 16:9 image. At full height
        // it would be 711 wide (> 700), so it is width-bound at 700 x 393.75.
        val viewport = sideRailPreviewViewport(1000f, 400f, startRail = 100f, endRail = 200f, ratio = 16f / 9f)
        assertEquals(100f, viewport.left, 0.01f)
        assertEquals(700f, viewport.width, 0.01f)
        assertEquals(393.75f, viewport.height, 0.01f)
        assertEquals(3.125f, viewport.top, 0.01f)
    }

    @Test fun sideRailViewportCentersNarrowImagesBetweenRails() {
        // 4:3 at 400 tall is 533.3 wide, centred in the 700 px gap after the 100 px start rail.
        val viewport = sideRailPreviewViewport(1000f, 400f, startRail = 100f, endRail = 200f, ratio = 4f / 3f)
        assertEquals(400f, viewport.height, 0.01f)
        assertEquals(1600f / 3f, viewport.width, 0.01f)
        assertEquals(100f + (700f - 1600f / 3f) / 2f, viewport.left, 0.01f)
        assertEquals(0f, viewport.top, 0.01f)
    }

    @Test fun sideRailViewportMirrorsRailsForRightToLeft() {
        val viewport = sideRailPreviewViewport(1000f, 400f, startRail = 100f, endRail = 200f, ratio = 4f / 3f, rightToLeft = true)
        assertEquals(200f + (700f - 1600f / 3f) / 2f, viewport.left, 0.01f)
    }

    @Test fun sideRailViewportWithoutRatioIsTheGapBetweenRails() {
        val viewport = sideRailPreviewViewport(1000f, 400f, startRail = 100f, endRail = 200f, ratio = null)
        assertEquals(100f, viewport.left, 0.01f)
        assertEquals(700f, viewport.width, 0.01f)
        assertEquals(400f, viewport.height, 0.01f)
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
