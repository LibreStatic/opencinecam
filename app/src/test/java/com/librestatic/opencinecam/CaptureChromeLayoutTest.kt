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
}
