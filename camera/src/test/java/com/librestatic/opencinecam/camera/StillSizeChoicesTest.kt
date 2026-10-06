/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class StillSizeChoicesTest {

    @Test fun keepsTheNativeAspectLargestFirstFromTwoMegapixels() {
        val advertised = listOf(
            1920 to 1080, 4000 to 3000, 4000 to 2250, 640 to 480, 3264 to 2448, 1600 to 1200, 2592 to 1944, 3000 to 3000, 1440 to 1080,
        )
        assertEquals(listOf(4000 to 3000, 3264 to 2448, 2592 to 1944, 1600 to 1200), stillSizeChoices(advertised))
    }

    @Test fun dropsSizesThatReadAsTheSameMegapixels() {
        assertEquals(listOf(4000 to 3000), stillSizeChoices(listOf(4000 to 3000, 3968 to 2976)))
    }

    @Test fun aSingleOrNoOutputStaysThatWay() {
        assertEquals(listOf(4000 to 3000), stillSizeChoices(listOf(4000 to 3000)))
        assertEquals(emptyList<Pair<Int, Int>>(), stillSizeChoices(emptyList()))
    }

    /** OCC-PLAN-069: the Razr Fold's logical map tops at 4000x3000 while physical "5" offers 4096x3072. */
    @Test fun physicalStillRoutingPicksTheOwningPhysicalCamera() {
        val logical = listOf(4000 to 3000, 3840 to 2880, 3264 to 2448)
        val physical = mapOf("5" to listOf(4096 to 3072))
        assertEquals("5", physicalStillRouting(4096 to 3072, logical, physical))
    }

    @Test fun physicalStillRoutingPrefersTheLogicalMap() {
        val logical = listOf(4000 to 3000)
        val physical = mapOf("5" to listOf(4096 to 3072, 4000 to 3000))
        assertEquals(null, physicalStillRouting(4000 to 3000, logical, physical))
        assertEquals(null, physicalStillRouting(1920 to 1440, logical, physical))
    }

    @Test fun physicalStillRoutingBreaksTiesBySmallestPhysicalId() {
        val logical = listOf(4000 to 3000)
        val physical = mapOf("5" to listOf(4096 to 3072), "2" to listOf(4096 to 3072))
        assertEquals("2", physicalStillRouting(4096 to 3072, logical, physical))
    }

    /** Razr Fold: logical 1x is the 6.57 mm main; "5" is the 13.3 mm tele and must not carry the still. */
    @Test fun unityZoomPhysicalIdsKeepOnlyTheOneTimesLens() {
        val physical = listOf("3" to 2.2f, "2" to 6.57f, "5" to 13.3f)
        assertEquals(setOf("2"), unityZoomPhysicalIds(6.57f, physical))
        assertEquals(setOf("2"), unityZoomPhysicalIds(6.4f, physical))
        assertEquals(emptySet<String>(), unityZoomPhysicalIds(6.57f, listOf("5" to 13.3f)))
    }

    @Test fun unityZoomPhysicalIdsRouteNowhereWithoutALogicalFocal() {
        assertEquals(emptySet<String>(), unityZoomPhysicalIds(null, listOf("2" to 6.57f)))
        assertEquals(emptySet<String>(), unityZoomPhysicalIds(0f, listOf("2" to 6.57f)))
    }
}
