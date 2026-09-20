/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class HeicEncodingTest {
    @Test fun oddDisplayDimensionsKeepExactGridTrimGeometry() {
        val grid = HeicEncodingGrid(717, 301, 512, 512)
        assertEquals(717, grid.width); assertEquals(301, grid.height)
        assertEquals(2, grid.columns); assertEquals(1, grid.rows); assertEquals(2, grid.count)
        assertEquals(0 to 0, grid.origin(0)); assertEquals(512 to 0, grid.origin(1))
    }
    @Test fun gridIsRowMajorAndDoesNotScaleSmallImages() {
        val grid = HeicEncodingGrid(1025, 513, 512, 512)
        assertEquals(listOf(0 to 0, 512 to 0, 1024 to 0, 0 to 512, 512 to 512, 1024 to 512),
            (0 until grid.count).map(grid::origin))
        assertEquals(1, HeicEncodingGrid(1, 1, 512, 512).count)
    }
    @Test fun alignedDimensionsHaveNoExtraTile() {
        assertEquals(4, HeicEncodingGrid(1024, 1024, 512, 512).count)
        assertEquals(1, HeicEncodingGrid(640, 480, 640, 480).count)
    }
    @Test fun invalidGeometryAndOverflowAreRejected() {
        for ((w, h) in listOf(0 to 1, 1 to 0, -1 to 5, Int.MAX_VALUE to Int.MAX_VALUE, 4097 to 4096))
            assertThrows(IllegalArgumentException::class.java) { HeicEncodingGrid(w, h, 512, 512) }
        for ((w, h) in listOf(0 to 512, 511 to 512, 512 to 511, Int.MAX_VALUE to 2))
            assertThrows(IllegalArgumentException::class.java) { HeicEncodingGrid(10, 10, w, h) }
        assertEquals(64, HeicEncodingGrid(4096, 4096, 512, 512).count)
    }
    @Test fun tileCountAndIndicesAreBounded() {
        assertThrows(IllegalArgumentException::class.java) { HeicEncodingGrid(16 * 1024 * 1024, 1, 512, 512) }
        val grid = HeicEncodingGrid(512, 512, 512, 512)
        assertThrows(IllegalArgumentException::class.java) { grid.origin(-1) }
        assertThrows(IllegalArgumentException::class.java) { grid.origin(1) }
    }
    @Test fun qualityUsesTheAdvertisedCqRangeMonotonically() {
        val values = (1..100).map { heicCodecQuality(it, 10, 110) }
        assertEquals(11, values.first()); assertEquals(110, values.last())
        assertTrue(values.zipWithNext().all { (a, b) -> b > a })
        assertEquals(Int.MAX_VALUE, heicCodecQuality(100, 0, Int.MAX_VALUE))
        assertEquals(95, heicCodecQuality(95, 0, 100))
    }
    @Test fun qualityDoesNotSilentlyAcceptUnavailableOrInvalidRanges() {
        for (quality in listOf(0, -1, 101))
            assertThrows(IllegalArgumentException::class.java) { heicCodecQuality(quality, 0, 100) }
        for ((lower, upper) in listOf(-1 to 100, 0 to 0, 100 to 1))
            assertThrows(IllegalArgumentException::class.java) { heicCodecQuality(95, lower, upper) }
    }
    @Test fun limitedRangeColorVectorsMatchSignalledMatrix() {
        assertEquals(16, heicLuma(0)); assertEquals(235, heicLuma(0xffffff))
        assertEquals(82, heicLuma(0xff0000)); assertEquals(144, heicLuma(0x00ff00)); assertEquals(41, heicLuma(0x0000ff))
        assertEquals(128, heicChroma(255, 255, 255, true)); assertEquals(128, heicChroma(0, 0, 0, false))
        assertEquals(90, heicChroma(255, 0, 0, true)); assertEquals(240, heicChroma(255, 0, 0, false))
    }
    @Test fun planarAndInterleavedStridesRespectPositionAndLastRow() {
        assertEquals(13, heicPlaneOffset(2, 1, 8, 1, 3, 14))
        assertEquals(15, heicPlaneOffset(2, 1, 8, 2, 3, 16))
        assertThrows(IllegalArgumentException::class.java) { heicPlaneOffset(2, 1, 8, 2, 3, 15) }
        assertThrows(IllegalArgumentException::class.java) { heicPlaneOffset(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 0, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { heicPlaneOffset(0, 0, 0, 1, 0, 10) }
    }
    @Test fun paddingReplicatesEdgesWithoutReadingOutsideTheSource() {
        val reads = mutableListOf<Pair<Int, Int>>()
        val values = mutableMapOf<Triple<Int, Int, Int>, Int>()
        writeHeicYuv420(1, 1, 0, 0, 2, 2,
            { x, y, count, pixels -> reads += x to y; assertEquals(1, count); pixels[0] = 0xff0000 },
            { p, x, y, value -> check(values.put(Triple(p, x, y), value) == null) }, {})
        assertEquals(listOf(0 to 0, 0 to 0), reads)
        assertEquals(6, values.size)
        for (y in 0..1) for (x in 0..1) assertEquals(82, values[Triple(0, x, y)])
        assertEquals(90, values[Triple(1, 0, 0)]); assertEquals(240, values[Triple(2, 0, 0)])
    }
    @Test fun chromaAveragesTheWholeTwoByTwoBlock() {
        val colors = arrayOf(intArrayOf(0xff0000, 0x00ff00), intArrayOf(0x0000ff, 0xffffff))
        val chroma = mutableListOf<Int>()
        writeHeicYuv420(2, 2, 0, 0, 2, 2,
            { _, y, _, row -> colors[y].copyInto(row) }, { p, _, _, value -> if (p != 0) chroma += value }, {})
        assertEquals(listOf(128, 128), chroma)
    }
    @Test fun cancellationIsObservedBeforeAllocationAndDuringConversion() {
        val cancel = CancellationException("owned cancellation")
        assertSame(cancel, assertThrows(CancellationException::class.java) {
            writeHeicYuv420(2, 2, 0, 0, 2, 2, { _, _, _, _ -> fail() }, { _, _, _, _ -> fail() }, { throw cancel })
        })
        var writes = 0
        assertSame(cancel, assertThrows(CancellationException::class.java) {
            writeHeicYuv420(2, 4, 0, 0, 2, 4, { _, _, _, row -> row.fill(0) },
                { _, _, _, _ -> writes++ }, { if (writes >= 6) throw cancel })
        })
        assertEquals(6, writes)
    }
    @Test fun retirementPreservesCancellationAndSuppressesCleanupFailure() {
        val cancel = CancellationException("cancel"); val cleanup = IllegalStateException("close")
        val observed = assertThrows(CancellationException::class.java) {
            heicRetiring({ throw cleanup }) { throw cancel }
        }
        assertSame(cancel, observed); assertArrayEquals(arrayOf(cleanup), observed.suppressed)
    }
    @Test fun retirementFailurePreventsSuccessfulDelivery() {
        val cleanup = IllegalStateException("close")
        assertSame(cleanup, assertThrows(IllegalStateException::class.java) { heicRetiring({ throw cleanup }) { "encoded" } })
        var retired = false
        assertEquals("encoded", heicRetiring({ retired = true }) { "encoded" })
        assertTrue(retired)
    }
}
