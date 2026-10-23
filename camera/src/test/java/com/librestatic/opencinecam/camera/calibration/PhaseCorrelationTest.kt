/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseCorrelationTest {
    private val n = 64

    private fun image(dx: Double, dy: Double): FloatArray {
        val img = CalibrationTestSupport.render(n, n) { x, y -> x - dx to y - dy }
        return img.values
    }

    @Test fun fftRoundTrips() {
        val re = DoubleArray(16) { Math.sin(it * 0.7) }; val im = DoubleArray(16)
        val orig = re.copyOf()
        PhaseCorrelation.fft1d(re, im, false)
        PhaseCorrelation.fft1d(re, im, true)
        for (i in 0 until 16) assertEquals(orig[i], re[i], 1e-12)
    }

    @Test fun integerShiftsAreRecovered() {
        val a = image(0.0, 0.0)
        for ((dx, dy) in listOf(3 to 0, 0 to -2, -4 to 5, 7 to -6, 0 to 0)) {
            val s = PhaseCorrelation.estimate(a, image(dx.toDouble(), dy.toDouble()), n)
            assertEquals("dx for $dx,$dy", dx.toFloat(), s.dx, 0.1f)
            assertEquals("dy for $dx,$dy", dy.toFloat(), s.dy, 0.1f)
            assertTrue("ratio ${s.peakRatio}", s.peakRatio > 2f)
        }
    }

    @Test fun signConvention() {
        // b(x) = a(x - dx): b's content sits dx pixels to the right of a's, so dx is positive.
        val s = PhaseCorrelation.estimate(image(0.0, 0.0), image(2.0, 3.0), n)
        assertTrue(s.dx > 1.5f && s.dy > 2.5f)
    }

    @Test fun subPixelShiftsAreRecovered() {
        val a = image(0.0, 0.0)
        for ((dx, dy) in listOf(0.5 to 0.0, 1.3 to -0.7, -2.25 to 1.6, 0.2 to 0.35)) {
            val s = PhaseCorrelation.estimate(a, image(dx, dy), n)
            assertEquals("dx $dx", dx.toFloat(), s.dx, 0.2f)
            assertEquals("dy $dy", dy.toFloat(), s.dy, 0.2f)
        }
    }

    @Test fun unrelatedImagesHaveLowPeak() {
        val rng = kotlin.random.Random(5)
        val a = FloatArray(n * n) { rng.nextFloat() }; val b = FloatArray(n * n) { rng.nextFloat() }
        assertTrue(PhaseCorrelation.estimate(a, b, n).peakRatio < 2f)
    }
}
