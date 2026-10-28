/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSolverTest {
    private val matrix = floatArrayOf(0.90f, 0.07f, 0.03f, 0.05f, 0.92f, 0.03f, 0.02f, 0.08f, 0.90f)
    private val rawGains = floatArrayOf(1.08f, 0.97f, 0.88f)
    private val gains = rawGains.let { g ->
        val l = 0.2126f * g[0] + 0.7152f * g[1] + 0.0722f * g[2]
        FloatArray(3) { g[it] / l }
    }
    private val ev = 0.4f

    private fun forward(t: FloatArray) = LensColorTransform.apply(t, matrix, gains, ev)

    private fun frames(scene: List<FloatArray>, noise: Float = 0.01f): Pair<List<CellStat>, List<CellStat>> {
        val target = CalibrationTestSupport.cellFrame(scene, noise = noise, seed = 3)
        val reference = CalibrationTestSupport.cellFrame(scene.map { forward(it).map { v -> v.coerceIn(0f, 0.95f) }.toFloatArray() }, noise = noise, seed = 4)
        return CellStats.compute(reference) to CellStats.compute(target)
    }

    @Test fun recoversKnownTransform() {
        val (ref, tgt) = frames(CalibrationTestSupport.scene().map { c -> FloatArray(3) { c[it] * 0.7f } })
        val fit = ColorSolver.solve(ref, tgt)
        assertFalse(fit.diagonalFallback)
        assertTrue("cells ${fit.cellsUsed}", fit.cellsUsed >= 16)
        for (r in 0 until 3) assertEquals(1f, fit.matrix[r * 3] + fit.matrix[r * 3 + 1] + fit.matrix[r * 3 + 2], 1e-5f)
        assertEquals(0.2126f * fit.gains[0] + 0.7152f * fit.gains[1] + 0.0722f * fit.gains[2], 1f, 1e-5f)
        assertTrue("dE ${fit.residualDeltaE} before ${fit.residualDeltaEBefore}", fit.residualDeltaE < fit.residualDeltaEBefore / 3)
        assertTrue("dE ${fit.residualDeltaE}", fit.residualDeltaE < 2.5f)
        // The effective colour mapping agrees with the truth on fresh colours.
        for (c in listOf(floatArrayOf(0.3f, 0.2f, 0.1f), floatArrayOf(0.1f, 0.3f, 0.2f), floatArrayOf(0.2f, 0.2f, 0.2f))) {
            val want = forward(c); val got = LensColorTransform.apply(c, fit)
            for (k in 0 until 3) assertEquals(want[k], got[k], 0.03f * want[k] + 0.005f)
        }
        assertEquals(ev, fit.evOffset, 0.15f)
    }

    @Test fun exposureOnlyIsRecovered() {
        val scene = CalibrationTestSupport.scene().map { c -> FloatArray(3) { c[it] * 0.5f } }
        val target = CellStats.compute(CalibrationTestSupport.cellFrame(scene, seed = 1))
        val reference = CellStats.compute(CalibrationTestSupport.cellFrame(scene.map { c -> FloatArray(3) { c[it] * 1.5f } }, seed = 1))
        val fit = ColorSolver.solve(reference, target)
        assertEquals(Math.log(1.5) / Math.log(2.0), fit.evOffset.toDouble(), 0.03)
        assertTrue(fit.residualDeltaE < 1f)
    }

    /**
     * A room-like scene: luminance dominates and the chroma runs mostly along warm-cool with a little
     * green-magenta, as the Razr frames did. The full RGB normal matrix is then ill-conditioned (in
     * the thousands) while the chroma system is not (tens), and the matrix must still be fitted.
     */
    @Test fun lumaDominatedSceneStillFitsTheMatrix() {
        val rng = java.util.Random(11)
        val scene = List(96) {
            val y = 0.05f + 0.55f * rng.nextFloat()
            val warm = 0.25f * (2f * rng.nextFloat() - 1f)
            val green = 0.04f * (2f * rng.nextFloat() - 1f)
            floatArrayOf(y * (1f + warm - green), y * (1f + green), y * (1f - warm - green))
        }
        val (ref, tgt) = frames(scene, noise = 0.004f)
        val fit = ColorSolver.solve(ref, tgt)
        assertFalse("cond ${fit.conditionNumber}", fit.diagonalFallback)
        assertTrue("dE ${fit.residualDeltaE} before ${fit.residualDeltaEBefore}", fit.residualDeltaE < fit.residualDeltaEBefore / 2)
        for (c in listOf(floatArrayOf(0.3f, 0.25f, 0.2f), floatArrayOf(0.2f, 0.25f, 0.3f))) {
            val want = forward(c); val got = LensColorTransform.apply(c, fit)
            for (k in 0 until 3) assertEquals(want[k], got[k], 0.05f * want[k] + 0.005f)
        }
    }

    @Test fun greyOnlySceneFallsBackToDiagonal() {
        val grey = CalibrationTestSupport.greyScene()
        val ref = CellStats.compute(CalibrationTestSupport.cellFrame(grey.map { c -> FloatArray(3) { c[it] * 1.3f } }))
        val tgt = CellStats.compute(CalibrationTestSupport.cellFrame(grey))
        val fit = ColorSolver.solve(ref, tgt)
        assertTrue(fit.diagonalFallback)
        assertTrue(fit.matrix.contentEquals(ColorFit.IDENTITY_MATRIX))
        assertTrue(fit.evOffset > 0.3f)
    }

    @Test fun tooFewCellsFallBack() {
        val (ref, tgt) = frames(CalibrationTestSupport.scene())
        val fit = ColorSolver.solve(ref.take(10), tgt.take(10))
        assertTrue(fit.diagonalFallback)
        assertTrue(fit.cellsUsed in 1..10)
    }

    @Test fun clippedAndDarkCellsAreRejected() {
        val scene = CalibrationTestSupport.scene().map { c -> FloatArray(3) { c[it] * 0.7f } }.toMutableList()
        for (i in 0 until 20) scene[i] = floatArrayOf(1f, 1f, 1f)           // clipped in the target
        for (i in 20 until 30) scene[i] = floatArrayOf(0.001f, 0.001f, 0.001f) // dark
        val target = CellStats.compute(CalibrationTestSupport.cellFrame(scene))
        val reference = CellStats.compute(CalibrationTestSupport.cellFrame(scene.map { c -> forward(c).map { it.coerceIn(0f, 0.95f) }.toFloatArray() }))
        val fit = ColorSolver.solve(reference, target, ColorSolverParams(variancePercentile = 1f))
        assertTrue("used ${fit.cellsUsed}", fit.cellsUsed <= 66)
        assertFalse(fit.diagonalFallback)
        assertEquals(ev, fit.evOffset, 0.2f)
    }

    @Test fun outlierCellsDoNotDominate() {
        val scene = CalibrationTestSupport.scene().map { c -> FloatArray(3) { c[it] * 0.7f } }
        val target = CellStats.compute(CalibrationTestSupport.cellFrame(scene, seed = 1))
        val refColors = scene.map { forward(it).map { v -> v.coerceIn(0f, 0.95f) }.toFloatArray() }.toMutableList()
        for (i in listOf(3, 17, 40, 61, 80)) refColors[i] = floatArrayOf(0.05f, 0.9f, 0.05f)
        val fit = ColorSolver.solve(CellStats.compute(CalibrationTestSupport.cellFrame(refColors, seed = 2)), target)
        val c = floatArrayOf(0.3f, 0.2f, 0.1f)
        val want = forward(c); val got = LensColorTransform.apply(c, fit)
        for (k in 0 until 3) assertEquals(want[k], got[k], 0.06f * want[k] + 0.01f)
    }

    @Test fun identicalFramesGiveIdentity() {
        val cells = CellStats.compute(CalibrationTestSupport.cellFrame(CalibrationTestSupport.scene(), noise = 0.005f))
        val fit = ColorSolver.solve(cells, cells)
        assertEquals(0f, fit.evOffset, 1e-4f)
        for (i in 0 until 9) assertEquals(ColorFit.IDENTITY_MATRIX[i], fit.matrix[i], 1e-3f)
        assertEquals(0f, fit.residualDeltaE, 0.1f)
    }

    @Test fun medianRejectsOneBadCycle() {
        val good = ColorFit(matrix, gains, 0.4f, 1f, 5f, 80, false, 50f)
        val bad = good.copy(matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), evOffset = 2f, diagonalFallback = true)
        val med = CalibrationMedian.combine(listOf(good, bad, good.copy(evOffset = 0.42f)))
        assertEquals(0.42f, med.evOffset, 1e-6f)
        assertFalse(med.diagonalFallback)
        for (r in 0 until 3) assertEquals(1f, med.matrix[r * 3] + med.matrix[r * 3 + 1] + med.matrix[r * 3 + 2], 1e-5f)
        assertEquals(1f, 0.2126f * med.gains[0] + 0.7152f * med.gains[1] + 0.0722f * med.gains[2], 1e-5f)
        val geo = CalibrationMedian.combineGeometry(listOf(
            GeometryFit(0.01f, 0f, 1f, 0f, 0.1f, 20, 24, false, false),
            GeometryFit(0.5f, 0f, 1f, 0f, 0.1f, 20, 24, true, true),
            GeometryFit(0.012f, 0f, 1.01f, 0f, 0.1f, 20, 24, false, false),
        ))
        assertEquals(0.012f, geo.tx, 1e-6f)
        assertFalse(geo.parallaxSuspect)
    }
}
