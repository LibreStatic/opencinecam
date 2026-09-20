/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class HingeSettingsBoundaryTest {
    @Test fun narrowTopDoesNotMakeSettingsCrossTabletopHinge() {
        val hinge = FoldHinge(0, 50, 360, 60, true)
        assertNull(foldPanes(360, 400, 0, 0, hinge, 16, 180, false))
        assertEquals(FoldPane(0, 68, 360, 332), bounds(360, 400, hinge))
    }

    @Test fun narrowBottomUsesTheUsableTopPane() {
        assertEquals(FoldPane(0, 0, 360, 332), bounds(360, 400, FoldHinge(0, 340, 360, 350, true)))
    }

    @Test fun narrowRightUsesTheUsableLeftPane() {
        assertEquals(FoldPane(0, 0, 732, 900), bounds(800, 900, FoldHinge(740, 0, 750, 900, false)))
    }

    @Test fun centeredFoldKeepsExistingTrailingPane() {
        val hinge = FoldHinge(400, 0, 400, 900, false)
        assertEquals(foldPanes(800, 900, 0, 0, hinge, 16, 180, false)?.controls, bounds(800, 900, hinge))
    }

    @Test fun bothNarrowSidesUseLargerPaneInsteadOfSpanningHinge() {
        assertEquals(FoldPane(0, 0, 142, 400), bounds(240, 400, FoldHinge(150, 0, 160, 400, false)))
    }

    @Test fun partialHingeOverlapAndWindowOffsetRemainContiguous() {
        assertEquals(
            FoldPane(0, 168, 300, 432),
            hingeSettingsPaneBounds(300, 600, 100, 200, FoldHinge(50, 350, 150, 360, true), 16, 180),
        )
    }

    @Test fun hingeCrossingWindowEdgeKeepsOnlyUnoccludedSide() {
        assertEquals(FoldPane(0, 58, 300, 342), bounds(300, 400, FoldHinge(0, -20, 300, 50, true)))
        assertEquals(FoldPane(0, 0, 300, 342), bounds(300, 400, FoldHinge(0, 350, 300, 420, true)))
    }

    @Test fun absentOrNonIntersectingHingeUsesWholeWindow() {
        assertNull(bounds(300, 400, null))
        assertNull(bounds(300, 400, FoldHinge(300, 100, 500, 120, true)))
        assertNull(bounds(300, 400, FoldHinge(0, 410, 300, 420, true)))
        assertNull(bounds(300, 400, FoldHinge(-20, 0, 0, 400, false)))
    }

    @Test fun malformedAndEmptyGeometryDoNotProduceNegativeBounds() {
        assertNull(bounds(0, 400, FoldHinge(0, 100, 300, 120, true)))
        assertNull(bounds(300, 400, FoldHinge(0, 120, 300, 100, true)))
        assertEquals(FoldPane(0, 400, 300, 0), bounds(300, 400, FoldHinge(0, -10, 300, 410, true)))
    }

    @Test fun windowCoordinateSubtractionDoesNotOverflow() {
        assertNull(hingeSettingsPaneBounds(300, 400, Int.MIN_VALUE, Int.MIN_VALUE,
            FoldHinge(Int.MAX_VALUE - 10, Int.MAX_VALUE - 10, Int.MAX_VALUE, Int.MAX_VALUE, false), 16, 180))
    }

    @Test fun oddGutterKeepsItsFullPhysicalWidth() {
        val hinge = FoldHinge(100, 0, 100, 400, false)
        assertEquals(FoldPane(109, 0, 191, 400), hingeSettingsPaneBounds(300, 400, 0, 0, hinge, 17, 180))
    }

    @Test fun allResizeBoundariesStayInsideWindowAndOutsideHinge() {
        for (horizontal in listOf(false, true)) {
            for (extent in listOf(120, 180, 240, 360, 800)) {
                for (start in 1 until extent) {
                    val hinge = if (horizontal) FoldHinge(0, start, extent, start + 4, true)
                        else FoldHinge(start, 0, start + 4, extent, false)
                    val pane = requireNotNull(bounds(extent, extent, hinge))
                    assertTrue(pane.left >= 0 && pane.top >= 0 && pane.width >= 0 && pane.height >= 0)
                    assertTrue(pane.left + pane.width <= extent && pane.top + pane.height <= extent)
                    val paneStart = if (horizontal) pane.top else pane.left
                    val paneSize = if (horizontal) pane.height else pane.width
                    assertTrue(paneSize == 0 || paneStart + paneSize <= start - 8 || paneStart >= start + 12)
                }
            }
        }
    }

    private fun bounds(width: Int, height: Int, hinge: FoldHinge?) =
        hingeSettingsPaneBounds(width, height, 0, 0, hinge, gutter = 16, minimum = 180)
}
