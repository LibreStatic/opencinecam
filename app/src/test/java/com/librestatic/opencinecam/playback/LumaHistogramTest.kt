/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import org.junit.Assert.*
import org.junit.Test

class LumaHistogramTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun emptyInputIsAllZeros() {
        val histogram = lumaHistogram(IntArray(0))
        assertEquals(64, histogram.size)
        assertTrue(histogram.all { it == 0f })
    }

    @Test fun binsUseRec709LumaAndNormalizeToTheTallestBin() {
        val pixels = IntArray(4) { argb(0, 0, 0) } + IntArray(2) { argb(255, 255, 255) } + argb(128, 128, 128) + argb(255, 0, 0)
        val histogram = lumaHistogram(pixels)
        assertEquals(1f, histogram[0], 0f)
        assertEquals(0.5f, histogram[63], 0f)
        assertEquals(0.25f, histogram[32], 0f)
        assertEquals(0.25f, histogram[13], 0f)
        assertEquals(1f + 0.5f + 0.25f + 0.25f, histogram.sum(), 1e-6f)
    }

    @Test fun greenWeighsMoreThanBlue() {
        val histogram = lumaHistogram(intArrayOf(argb(0, 255, 0), argb(0, 0, 255)), bins = 16)
        assertEquals(1f, histogram[11], 0f)
        assertEquals(1f, histogram[1], 0f)
    }

    @Test fun rejectsZeroBins() {
        assertThrows(IllegalArgumentException::class.java) { lumaHistogram(IntArray(1), bins = 0) }
    }
}
