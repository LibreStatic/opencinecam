/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import com.librestatic.opencinecam.core.model.CalibrationFlag
import com.librestatic.opencinecam.core.model.CalibrationSet
import com.librestatic.opencinecam.core.model.CalibrationSourcePath
import com.librestatic.opencinecam.core.model.LensCalibrationCodec
import com.librestatic.opencinecam.core.model.LensCalibrationDecoding
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCalibrationRunnerTest {
    private val uw = SimLens(
        "2", 0.5f, scale = 1.012f, txPx = 2.4f, tyPx = -1.8f,
        matrix = floatArrayOf(0.94f, 0.04f, 0.02f, 0.03f, 0.95f, 0.02f, 0.01f, 0.05f, 0.94f),
        gains = SimulatedRig.lumaGains(1.1f, 1f, 0.9f), ev = -0.3f,
    )
    private val main = SimLens("0", 1f)
    private val tele = SimLens(
        "3", 2f, scale = 0.99f, txPx = -3f, tyPx = 1.2f,
        matrix = floatArrayOf(1.03f, -0.02f, -0.01f, -0.02f, 1.04f, -0.02f, 0f, -0.03f, 1.03f),
        gains = SimulatedRig.lumaGains(0.93f, 1f, 1.08f), ev = 0.25f,
    )

    private fun rig(
        lenses: List<SimLens> = listOf(uw, main, tele),
        up: FloatArray = floatArrayOf(1.0f, 2.0f),
        down: FloatArray = floatArrayOf(0.98f, 1.9f),
        unconverged: Set<Int> = emptySet(),
        exposureWobble: Float = 0f,
        onSample: (Int) -> Unit = {},
    ) = SimulatedRig(lenses, up, down, unconverged, exposureWobble, onSample)

    private fun runner(
        rig: SimulatedRig,
        progress: MutableList<RunProgress> = mutableListOf(),
    ) = LensCalibrationRunner(
        rig = rig,
        lenses = rig.lenses.map { CalibrationLens(it.id, it.nominal) },
        logicalId = "0",
        fingerprint = "test/fingerprint",
        sourcePath = CalibrationSourcePath.SDR,
        zoomRange = 0.5f..10f,
        onProgress = { progress += it },
        wallClockMs = { 1_700_000_000_000L },
    )

    private fun success(outcome: RunOutcome): RunOutcome.Success {
        assertTrue("expected success but was $outcome", outcome is RunOutcome.Success)
        return outcome as RunOutcome.Success
    }

    /** The correction applied to the lens' own colours must land on the reference's. */
    private fun assertColourRecovered(rig: SimulatedRig, lensIndex: Int, set: CalibrationSet) {
        val lens = rig.lenses[lensIndex]
        val correction = checkNotNull(set.correctionFor(lens.id))
        val inv = SimulatedRig.invert(lens.matrix)
        var worst = 0f
        for (colour in CalibrationTestSupport.scene(3, 40)) {
            val ref = FloatArray(3) { colour[it] * 0.5f }
            val scaled = FloatArray(3) { ref[it] / Math.pow(2.0, lens.ev.toDouble()).toFloat() }
            val target = FloatArray(3) { c ->
                (inv[c * 3] * scaled[0] + inv[c * 3 + 1] * scaled[1] + inv[c * 3 + 2] * scaled[2]) / lens.gains[c]
            }
            val corrected = LensColorTransform.apply(target, correction.colorMatrix.toFloatArray(), correction.gains.toFloatArray(), correction.evOffset)
            for (c in 0 until 3) worst = maxOf(worst, abs(corrected[c] - ref[c]))
        }
        assertTrue("worst linear error $worst for lens ${lens.id}", worst < 0.02f)
    }

    private fun assertAlignmentRecovered(lens: SimLens, set: CalibrationSet) {
        val align = checkNotNull(set.correctionFor(lens.id)).align
        assertEquals("tx of ${lens.id}", lens.txPx / SimulatedRig.WIDTH, align.tx, 0.0015f)
        assertEquals("ty of ${lens.id}", lens.tyPx / SimulatedRig.HEIGHT, align.ty, 0.0015f)
        assertEquals("scale of ${lens.id}", lens.scale, align.scale, 0.003f)
        assertEquals("rotation of ${lens.id}", 0f, align.rotationRad, 0.002f)
    }

    @Test
    fun calibratesBothNeighboursAcrossHysteresis() {
        val progress = mutableListOf<RunProgress>()
        val rig = rig()
        val result = success(runner(rig, progress).run())
        val set = result.set
        assertEquals("0", set.referencePhysicalId)
        assertEquals(listOf("2", "0", "3"), set.lenses.map { it.physicalId })
        assertTrue(set.correctionFor("0")!!.isIdentity)
        assertColourRecovered(rig, 0, set)
        assertColourRecovered(rig, 2, set)
        assertAlignmentRecovered(uw, set)
        assertAlignmentRecovered(tele, set)
        assertEquals(1_700_000_000_000L, set.createdAtMs)
        assertEquals(listOf(2f, 1f, 1f, 1.6f), set.illuminantHint?.awbGains)
        assertEquals(CalibrationSourcePath.SDR, set.sourcePath)
        assertEquals(RunLensStatus.DONE, result.progress.lenses.first { it.physicalId == "2" }.status)
        assertEquals(RunLensStatus.REFERENCE, result.progress.lenses.first { it.physicalId == "0" }.status)
        // Quality is reported and sensible.
        for (id in listOf("2", "3")) {
            val q = set.correctionFor(id)!!.quality
            assertTrue("score $id ${q.score}", q.score > 0.6f)
            assertTrue("dE after $id ${q.colorResidualDeltaE}", q.colorResidualDeltaE < 3f)
            val row = result.progress.lenses.first { it.physicalId == id }
            assertTrue(row.colorDeltaEBefore!! > row.colorDeltaEAfter!!)
        }
        // Phases advance in order and progress reaches 1 for the measured lenses.
        val phases = progress.map { it.phase }.distinct()
        assertEquals(
            listOf(RunPhase.PREPARING, RunPhase.FINDING_SWITCH_POINTS, RunPhase.MEASURING, RunPhase.SOLVING),
            phases,
        )
        assertTrue(progress.any { p -> p.lenses.any { it.status == RunLensStatus.MEASURING && it.progress in 0.25f..0.99f } })
    }

    @Test
    fun withoutHysteresisHoldsEitherSideOfTheSwitchAndRemovesTheZoomDifference() {
        val rig = rig(up = floatArrayOf(1.0f, 2.0f), down = floatArrayOf(1.0f, 2.0f))
        val set = success(runner(rig).run()).set
        assertColourRecovered(rig, 0, set)
        assertColourRecovered(rig, 2, set)
        assertAlignmentRecovered(uw, set)
        assertAlignmentRecovered(tele, set)
    }

    @Test
    fun resultSurvivesTheCodecRoundTrip() {
        val set = success(runner(rig()).run()).set
        val decoded = LensCalibrationCodec.decode(LensCalibrationCodec.encode(set))
        assertTrue("decoded as $decoded", decoded is LensCalibrationDecoding.Decoded)
        val back = (decoded as LensCalibrationDecoding.Decoded).set
        assertEquals(set.lenses.map { it.physicalId }.sorted(), back.lenses.map { it.physicalId }.sorted())
        assertEquals(set.referencePhysicalId, back.referencePhysicalId)
        assertEquals(set.illuminantHint, back.illuminantHint)
        for (l in set.lenses) {
            val b = back.correctionFor(l.physicalId)!!
            for (i in 0 until 9) assertEquals(l.colorMatrix[i], b.colorMatrix[i], 1e-4f)
            assertEquals(l.align.scale, b.align.scale, 1e-5f)
            assertEquals(l.quality.flags, b.quality.flags)
        }
    }

    @Test
    fun lensesNotNextToTheReferenceAreSkipped() {
        val far = SimLens("9", 5f, scale = 1.01f)
        val rig = rig(lenses = listOf(uw, main, tele, far), up = floatArrayOf(1.0f, 2.0f, 5.0f), down = floatArrayOf(0.98f, 1.9f, 4.8f))
        val result = success(runner(rig).run())
        assertEquals(RunLensStatus.SKIPPED, result.progress.lenses.first { it.physicalId == "9" }.status)
        assertNull(result.set.correctionFor("9"))
        assertNotNull(result.set.correctionFor("3"))
        assertNotNull(result.set.correctionFor("2"))
    }

    @Test
    fun aLensWhoseExposureNeverSettlesFailsAloneAndTheRestStillSaves() {
        val rig = rig(unconverged = setOf(2)) // tele
        val result = success(runner(rig).run())
        assertEquals(RunLensStatus.FAILED, result.progress.lenses.first { it.physicalId == "3" }.status)
        assertEquals(RunLensStatus.DONE, result.progress.lenses.first { it.physicalId == "2" }.status)
        assertNull(result.set.correctionFor("3"))
        assertEquals(listOf("2", "0"), result.set.lenses.map { it.physicalId })
    }

    @Test
    fun everyNeighbourUnsettledEndsInSceneUnstable() {
        val rig = rig(unconverged = setOf(0, 2))
        val outcome = runner(rig).run()
        assertTrue("was $outcome", outcome is RunOutcome.Failure)
        assertEquals(RunFailure.SCENE_UNSTABLE, (outcome as RunOutcome.Failure).failure)
        assertTrue(outcome.progress.lenses.filter { it.physicalId != "0" }.all { it.status == RunLensStatus.FAILED })
    }

    @Test
    fun aHoldGivesUpAfterTheTimeout() {
        val rig = rig(unconverged = setOf(0, 2))
        val before = rig.clockMs
        runner(rig).run()
        // Two sweeps worth of samples plus at most two 6 s holds; far less than if holds never timed out.
        assertTrue(rig.clockMs - before < 120_000)
    }

    @Test
    fun exposureThatWobblesInsideAHoldIsFlagged() {
        val rig = rig(exposureWobble = 0.12f)
        val result = success(runner(rig).run())
        for (id in listOf("2", "3")) {
            assertTrue(CalibrationFlag.UNSTABLE_AE in result.set.correctionFor(id)!!.quality.flags)
        }
    }

    @Test
    fun cancelStopsTheRunWhereverItIs() {
        for (after in listOf(5, 80, 250)) {
            lateinit var runner: LensCalibrationRunner
            val rig = rig(onSample = { if (it == after) runner.cancel() })
            runner = runner(rig)
            val outcome = runner.run()
            assertTrue("cancel after $after gave $outcome", outcome is RunOutcome.Cancelled)
            assertTrue(rig.samplesServed <= after + 1)
        }
    }

    @Test
    fun abortReportsTheGivenFailure() {
        lateinit var runner: LensCalibrationRunner
        val rig = rig(onSample = { if (it == 30) runner.abort(RunFailure.CAMERA_LOST) })
        runner = runner(rig)
        val outcome = runner.run()
        assertEquals(RunFailure.CAMERA_LOST, (outcome as RunOutcome.Failure).failure)
    }

    @Test
    fun aSingleLensCannotBeCalibrated() {
        val rig = rig(lenses = listOf(main), up = floatArrayOf(), down = floatArrayOf())
        val outcome = runner(rig).run()
        assertEquals(RunFailure.INTERNAL, (outcome as RunOutcome.Failure).failure)
    }

}
