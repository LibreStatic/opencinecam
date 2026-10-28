/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationValidatorTest {
    private val good = ColorFit(ColorFit.IDENTITY_MATRIX, floatArrayOf(1f, 1f, 1f), 0f, 1f, 6f, 80, false, 40f)
    private val geo = GeometryFit(0f, 0f, 1f, 0f, 0.1f, 20, 24, false, false)
    private val colourful = CellStats.compute(CalibrationTestSupport.cellFrame(CalibrationTestSupport.scene()))

    private fun eval(color: ColorFit = good, g: GeometryFit? = geo, r: List<CellStat> = colourful, t: List<CellStat> = colourful, ae: Boolean = true) =
        CalibrationValidator.evaluate(color, g, r, t, ae)

    @Test fun cleanCaptureScoresHigh() {
        val v = eval()
        assertTrue(v.issues.isEmpty()); assertTrue(v.score > 0.95f)
    }

    @Test fun eachIssueIsRaised() {
        assertTrue(CalibrationIssue.LOW_TEXTURE in eval(g = geo.copy(inliers = 2)).issues)
        assertTrue(eval(g = null).issues.isEmpty())
        val grey = CellStats.compute(CalibrationTestSupport.cellFrame(CalibrationTestSupport.greyScene()))
        assertTrue(CalibrationIssue.LOW_CHROMA in eval(r = grey, t = grey).issues)
        val clipped = colourful.map { it.copy(clipFraction = 0.5f) }
        assertTrue(CalibrationIssue.CLIPPED in eval(t = clipped).issues)
        assertTrue(CalibrationIssue.UNSTABLE_AE in eval(ae = false).issues)
        assertTrue(CalibrationIssue.PARALLAX_SUSPECT in eval(g = geo.copy(parallaxSuspect = true)).issues)
        assertTrue(CalibrationIssue.DIAGONAL_FALLBACK in eval(color = good.copy(diagonalFallback = true)).issues)
    }

    @Test fun scoreDropsWithIssuesAndStaysInRange() {
        val worst = eval(good.copy(diagonalFallback = true, residualDeltaE = 40f), geo.copy(inliers = 0, parallaxSuspect = true),
            colourful.map { it.copy(clipFraction = 1f) }, colourful, false)
        assertTrue(worst.score in 0f..1f); assertTrue(worst.score < 0.2f)
        assertEquals(0f, eval(good.copy(cellsUsed = 0)).score, 0f)
        assertTrue(eval(good.copy(residualDeltaE = 13f)).score < eval().score)
    }

    @Test fun issueNamesAreStable() {
        assertEquals(listOf("LOW_TEXTURE", "LOW_CHROMA", "CLIPPED", "UNSTABLE_AE", "PARALLAX_SUSPECT", "DIAGONAL_FALLBACK"),
            CalibrationIssue.values().map { it.name })
    }
}
