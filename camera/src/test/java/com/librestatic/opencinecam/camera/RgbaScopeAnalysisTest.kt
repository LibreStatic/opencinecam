/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbaScopeAnalysisTest {
    @Test
    fun `separates rgb channels and publishes bounded luma`() {
        val analysis = analyzeRgbaFrame(
            width = 2,
            height = 1,
            rgba = byteArrayOf(
                255.toByte(), 0, 0, 255.toByte(),
                0, 0, 255.toByte(), 255.toByte(),
            ),
            capturedAtElapsedRealtimeMs = 1234L,
        )

        assertEquals(64, analysis.histogram.size)
        assertEquals(.5f, analysis.redHistogram.last(), .0001f)
        assertEquals(1f, analysis.greenHistogram.first(), .0001f)
        assertEquals(.5f, analysis.blueHistogram.last(), .0001f)
        assertEquals(1f, analysis.histogram.sum(), .0001f)
        assertEquals(1234L, analysis.capturedAtElapsedRealtimeMs)
        assertTrue(analysis.zebraCells.none { it })
    }
}
