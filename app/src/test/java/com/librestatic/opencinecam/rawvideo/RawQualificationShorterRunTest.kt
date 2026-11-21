/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import com.librestatic.opencinecam.camera.RawBenchmarkStatus
import com.librestatic.opencinecam.camera.RawSustainedWrite
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoQualification
import com.librestatic.opencinecam.camera.RawVideoQualificationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN-071 (2026-11-20): the options of a shorter qualification, skipping predicted long runs and camera only. */
class RawQualificationShorterRunTest {
    private val sensorW = 4080
    private val sensorH = 3060

    private fun run(spec: String) = requireNotNull(RawQualificationSpecs.parse(spec, "0", sensorW, sensorH))
    private fun config(spec: String) = run(spec).config

    private val storageNotTested = RawQualificationRisk.REASON_STORAGE_NOT_TESTED
    private val reduced = RawQualificationStaging.REASON_FPS_REDUCED

    /** The Razr-like cliff: 56 GB of cache, then [sustained] B/s. */
    private fun cliff(sustained: Long?, detected: Boolean = true) =
        RawSustainedWrite(detected, if (detected) 56 else null, if (detected) 56_000_000_000L else null, if (detected) null else 1L,
            1_200_000_000L, sustained, 80, if (detected) 24 else 0)

    private fun bench(pixels: Double, ratio: Double, reasons: List<String> = emptyList(), held: Boolean = true) =
        RawEncodeBenchFigures(config("full@60"), pixels, ratio, 4, 8, held, reasons, RawVideoConfig.ENCODING_BLOCK_PACK)

    // region skipPredictedLongRuns

    @Test fun aPredictedSkipNeedsTheOptionAndACliff() {
        // full@30 streams 468 MB/s: above a 400 MB/s sustained rate.
        val full30 = config("full@30")
        assertEquals(listOf(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED),
            RawQualificationStaging.predictedLongRunSkip(true, full30, cliff(400_000_000L)))
        // The option off changes nothing, whatever the sweep found.
        assertTrue(RawQualificationStaging.predictedLongRunSkip(false, full30, cliff(400_000_000L)).isEmpty())
        // No cliff, no sustained rate or no sweep: nothing is predicted.
        assertTrue(RawQualificationStaging.predictedLongRunSkip(true, full30, cliff(400_000_000L, detected = false)).isEmpty())
        assertTrue(RawQualificationStaging.predictedLongRunSkip(true, full30, cliff(null)).isEmpty())
        assertTrue(RawQualificationStaging.predictedLongRunSkip(true, full30, null).isEmpty())
        // Within the sustained rate it keeps its long run.
        assertTrue(RawQualificationStaging.predictedLongRunSkip(true, full30, cliff(500_000_000L)).isEmpty())
        // The same reasons as the sustained-rate rule.
        assertEquals(RawQualificationStaging.sustainedRateReasons(full30, 1.0, cliff(400_000_000L)),
            RawQualificationStaging.predictedLongRunSkip(true, full30, cliff(400_000_000L)))
    }

    @Test fun aPredictedSkipReadsTheAchievedRateAndTheCompressionRatio() {
        val full30 = config("full@30")
        // At a reduced 24 fps the stream is 375 MB/s: under 400 MB/s, so the long run is kept.
        assertTrue(RawQualificationStaging.predictedLongRunSkip(true, full30, cliff(400_000_000L), achievedFps = 24.0).isEmpty())
        assertEquals(listOf(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED),
            RawQualificationStaging.predictedLongRunSkip(true, full30, cliff(400_000_000L), achievedFps = 29.9))
        // A compressed twin at its conservative ratio: 468 / 1.4 = 334 MB/s fits 400 MB/s, 468 / 1.1 = 425 MB/s does not.
        val bp = full30.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        assertTrue(RawQualificationStaging.predictedLongRunSkip(true, bp, cliff(400_000_000L), ratio = 1.4).isEmpty())
        assertEquals(listOf(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED),
            RawQualificationStaging.predictedLongRunSkip(true, bp, cliff(400_000_000L), ratio = 1.1))
    }

    @Test fun thePredictedDescentStartsAtTheHeaviestConfigurationTheSustainedRateAllows() {
        // With a 400 MB/s sustained rate the descent skips every configuration above it and starts below.
        val order = RawQualificationStaging.longRunOrder(RawQualificationPlan.DEFAULT.specs.map(::run))
        val skipped = order.filter { RawQualificationStaging.predictedLongRunSkip(true, it.config, cliff(400_000_000L)).isNotEmpty() }
        val first = order.first { RawQualificationStaging.predictedLongRunSkip(true, it.config, cliff(400_000_000L)).isEmpty() }
        assertTrue(skipped.isNotEmpty())
        assertTrue(skipped.all { it.config.streamBytesPerSecond > 400_000_000L })
        assertTrue(first.config.streamBytesPerSecond <= 400_000_000L)
        assertEquals(order.indexOf(first), skipped.size)
    }

    // endregion

    // region cameraOnly verdicts

    @Test fun aCameraOnlyVerdictIsRiskyWhenTheCameraPasses() {
        val pass = RawQualificationStaging.cameraOnlyVerdict(RawStageResult.PASS)
        assertEquals(RawBenchmarkStatus.FAIL, pass.status)
        assertEquals(listOf(storageNotTested), pass.reasonCodes)
        assertEquals(RawQualificationMethods.CAMERA_ONLY, pass.method)
        assertNull(pass.coveredBy)
        assertTrue(RawQualificationRisk.isStorageOnly(pass.reasonCodes))
        assertTrue(pass.risky)
        // A reduced rate keeps its informational code.
        val slow = RawQualificationStaging.cameraOnlyVerdict(RawStageResult.pass(listOf(reduced)))
        assertEquals(listOf(storageNotTested, reduced), slow.reasonCodes)
        assertTrue(RawQualificationRisk.isStorageOnly(slow.reasonCodes))
    }

    @Test fun aCameraOnlyVerdictKeepsACameraFailureAndLeavesASkipUnknown() {
        val failed = RawQualificationStaging.cameraOnlyVerdict(RawStageResult.fail(listOf("fps-not-reached")))
        assertEquals(RawBenchmarkStatus.FAIL, failed.status)
        assertEquals(listOf("fps-not-reached"), failed.reasonCodes)
        assertFalse(failed.risky)
        val hot = RawQualificationStaging.cameraOnlyVerdict(RawStageResult.fail(RawQualificationStaging.thermalReasons(4)))
        assertEquals(listOf(RawQualificationStaging.REASON_THERMAL_LIMIT), hot.reasonCodes)
        val skipped = RawQualificationStaging.cameraOnlyVerdict(RawStageResult.skipped("thermal-wait-timeout"))
        assertEquals(RawBenchmarkStatus.UNKNOWN, skipped.status)
        val missing = RawQualificationStaging.cameraOnlyVerdict(null)
        assertEquals(RawBenchmarkStatus.UNKNOWN, missing.status)
        assertEquals(listOf(RawQualificationStaging.REASON_CAMERA_CHECK_MISSING), missing.reasonCodes)
    }

    @Test fun aCameraOnlyCompressedVerdictNeedsTheCameraAndTheEncoder() {
        val twin = config("full@30")
        // 4080x3060x30 = 375 Mpx/s; 900 Mpx/s keeps up.
        val ready = RawQualificationStaging.cameraOnlyCompressedVerdict(twin, RawStageResult.PASS, bench(900e6, 1.6))
        assertEquals(RawBenchmarkStatus.FAIL, ready.status)
        assertEquals(listOf(storageNotTested), ready.reasonCodes)
        assertEquals(RawQualificationMethods.CAMERA_ONLY, ready.method)
        assertNull(ready.coveredBy)
        assertTrue(ready.risky)
        // Too slow an encoder fails it for the encoder, not the storage.
        val slow = RawQualificationStaging.cameraOnlyCompressedVerdict(twin, RawStageResult.PASS, bench(300e6, 1.6))
        assertEquals(RawBenchmarkStatus.FAIL, slow.status)
        assertFalse(storageNotTested in slow.reasonCodes)
        assertFalse(slow.risky)
        // A twin whose camera failed keeps its reasons; a missing benchmark or twin is unknown.
        assertEquals(listOf("fps-not-reached"),
            RawQualificationStaging.cameraOnlyCompressedVerdict(twin, RawStageResult.fail(listOf("fps-not-reached")), bench(900e6, 1.6)).reasonCodes)
        assertEquals(RawBenchmarkStatus.UNKNOWN, RawQualificationStaging.cameraOnlyCompressedVerdict(twin, RawStageResult.PASS, null).status)
        assertEquals(RawBenchmarkStatus.UNKNOWN, RawQualificationStaging.cameraOnlyCompressedVerdict(twin, null, bench(900e6, 1.6)).status)
        // A benchmark the heat spoiled fails it with the heat.
        assertEquals(listOf(RawQualificationStaging.REASON_THERMAL_LIMIT), RawQualificationStaging.cameraOnlyCompressedVerdict(twin, RawStageResult.PASS,
            bench(900e6, 1.6, RawQualificationStaging.thermalReasons(4))).reasonCodes)
    }

    // endregion

    // region plan and options

    @Test fun optionsAreOffByDefaultAndCameraOnlyWins() {
        val plan = RawQualificationPlan.DEFAULT
        assertEquals(RawQualificationOptions(), plan.options)
        assertFalse(plan.cameraOnly)
        assertFalse(plan.skipPredictedLongRuns)
        assertEquals(RawQualificationStage.ALL, plan.effectiveStages)
        assertTrue(plan.complete)

        val skip = plan.withOptions(RawQualificationOptions(skipPredictedLongRuns = true))
        assertTrue(skip.skipPredictedLongRuns)
        assertEquals(RawQualificationStage.ALL, skip.effectiveStages)

        val cameraOnly = plan.withOptions(RawQualificationOptions(skipPredictedLongRuns = true, cameraOnly = true))
        assertTrue(cameraOnly.cameraOnly)
        assertFalse(cameraOnly.skipPredictedLongRuns)
        assertFalse(cameraOnly.runs(RawQualificationStage.SWEEP))
        assertFalse(cameraOnly.runs(RawQualificationStage.LONG))
        assertTrue(cameraOnly.runs(RawQualificationStage.CAMERA))
        assertTrue(cameraOnly.runs(RawQualificationStage.ENCODE))
        // A camera-only run of the full plan is complete: its records are stored.
        assertTrue(cameraOnly.complete)
        assertEquals(plan.specs, cameraOnly.specs)
    }

    @Test fun onlyTheAppsCompletePlanReusesEvidence() {
        assertTrue(RawQualificationPlan.DEFAULT.reusesEvidence)
        assertTrue(RawQualificationPlan.DEFAULT.withOptions(RawQualificationOptions(cameraOnly = true)).reusesEvidence)
        assertTrue(RawQualificationPlan.DEFAULT.withOptions(RawQualificationOptions(skipPredictedLongRuns = true)).reusesEvidence)
        // The device test's explicit plans never reuse, nor do partial stages.
        assertFalse(RawQualificationPlan.explicit(listOf("full@30")).reusesEvidence)
        val partial = RawQualificationPlan.DEFAULT.copy(stages = setOf(RawQualificationStage.CAMERA))
        assertFalse(partial.complete)
        assertFalse(partial.reusesEvidence)
        // LONG brings the sweep along but not the camera checks: still partial.
        assertFalse(RawQualificationPlan.DEFAULT.copy(stages = setOf(RawQualificationStage.LONG)).reusesEvidence)
    }

    // endregion

    // region estimates

    private val tib = 1L shl 40

    @Test fun aCameraOnlyEstimateHasNoRestSweepOrLongRunAndNeedsNoSpace() {
        val plan = RawQualificationPlan.DEFAULT.withOptions(RawQualificationOptions(cameraOnly = true))
        val space = RawQualificationEstimates.forFreeSpace(plan, "0", sensorW, sensorH, codecAvailable = true, freeBytes = 0L)
        val byKind = space.estimate.plannedMsByKind
        assertEquals(0L, byKind[RawQualificationStepKind.STORAGE_REST])
        assertEquals(0L, byKind[RawQualificationStepKind.SWEEP])
        assertEquals(0L, byKind[RawQualificationStepKind.LONG_RUN])
        assertTrue(byKind.getValue(RawQualificationStepKind.CAMERA_CHECK) > 0)
        assertTrue(byKind.getValue(RawQualificationStepKind.ENCODE_BENCH) > 0)
        assertEquals(0L, space.estimate.freeBytesNeeded)
        assertEquals(0, space.skippedRuns)
        assertEquals(0L, space.minimumFreeBytes)
        assertEquals(0L, space.fullPlanFreeBytes)
        assertTrue(space.enoughSpace)
        assertTrue(space.configs.isNotEmpty())
        assertTrue(space.configs.all { it.fits && it.neededBytes == 0L })
        // The steps are the camera checks and the benchmark sessions alone.
        val full = RawQualificationEstimates.forFreeSpace(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, codecAvailable = true, freeBytes = tib)
        val checks = space.configs.size
        val benches = RawQualificationPlan.DEFAULT.benchSessions(RawQualificationPlan.DEFAULT.specs.map(::run), true)
        assertEquals(checks + benches, space.estimate.runCount)
        assertTrue(space.estimate.durationMs < full.estimate.durationMs)
    }

    @Test fun skippingPredictedLongRunsLeavesTheEstimateAnUpperBound() {
        val plain = RawQualificationEstimates.forFreeSpace(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, true, tib)
        val skip = RawQualificationEstimates.forFreeSpace(
            RawQualificationPlan.DEFAULT.withOptions(RawQualificationOptions(skipPredictedLongRuns = true)), "0", sensorW, sensorH, true, tib)
        assertEquals(plain.estimate, skip.estimate)
        assertEquals(plain.configs, skip.configs)
    }

    @Test fun reusedStepsLeaveTheEstimate() {
        val plan = RawQualificationPlan.DEFAULT
        val runs = plan.specs.map(::run)
        val plain = RawQualificationEstimates.forFreeSpace(plan, "0", sensorW, sensorH, true, tib)
        val first = RawQualificationStaging.longRunOrder(plan.uncompressedRuns(runs)).first().config
        val reuse = RawQualificationReuse(
            cameraChecks = setOf(first, config("full@30")),
            benches = setOf(RawQualificationReusedBench(first, RawVideoConfig.ENCODING_BLOCK_PACK, 4, "k")),
        )
        val reused = RawQualificationEstimates.forFreeSpace(plan, "0", sensorW, sensorH, true, tib, reuse = reuse)
        assertEquals(plain.estimate.runCount - 3, reused.estimate.runCount)
        val timing = RawQualificationTiming()
        assertEquals(plain.estimate.plannedMsByKind.getValue(RawQualificationStepKind.CAMERA_CHECK) - 2 * timing.cameraCheckMs(),
            reused.estimate.plannedMsByKind.getValue(RawQualificationStepKind.CAMERA_CHECK))
        assertEquals(plain.estimate.plannedMsByKind.getValue(RawQualificationStepKind.ENCODE_BENCH) - timing.encodeBenchMs(),
            reused.estimate.plannedMsByKind.getValue(RawQualificationStepKind.ENCODE_BENCH))
        assertEquals(plain.estimate.plannedMsByKind.getValue(RawQualificationStepKind.COOL_DOWN) - 3 * RawQualificationTiming.COOL_DOWN_MS,
            reused.estimate.plannedMsByKind.getValue(RawQualificationStepKind.COOL_DOWN))
        // The rest still follows the camera sessions that run.
        assertEquals(RawQualificationTiming.STORAGE_REST_MS, reused.estimate.plannedMsByKind[RawQualificationStepKind.STORAGE_REST])
        assertEquals(reuse, reused.reuse)
        assertEquals(setOf(first, config("full@30")), reused.configs.filter { it.cameraCheckReused }.map { it.config }.toSet())
        // The plan-based estimate agrees.
        assertEquals(reused.estimate.runCount, RawQualificationEstimates.of(plan, "0", sensorW, sensorH, true, reuse = reuse).runCount)
    }

    @Test fun whenEverythingIsReusedNoSessionRunsAndTheRestIsDropped() {
        val plan = RawQualificationPlan.DEFAULT.withOptions(RawQualificationOptions(cameraOnly = true))
        val runs = plan.specs.map(::run)
        val uncompressed = plan.uncompressedRuns(runs)
        val reuse = RawQualificationReuse(
            cameraChecks = uncompressed.map { it.config }.toSet(),
            benches = setOf(RawQualificationReusedBench(uncompressed.first().config, RawVideoConfig.ENCODING_BLOCK_PACK, 4, "k")),
        )
        val camOnly = RawQualificationEstimates.forFreeSpace(plan, "0", sensorW, sensorH, true, 0L, reuse = reuse).estimate
        assertEquals(0, camOnly.runCount)
        assertEquals(RawQualificationTiming.RING_OVERHEAD_MS, camOnly.durationMs)
        // A full run that reuses every camera session needs no storage rest before its sweep.
        val full = RawQualificationEstimates.forFreeSpace(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, true, tib, reuse = reuse).estimate
        assertEquals(0L, full.plannedMsByKind[RawQualificationStepKind.STORAGE_REST])
        assertEquals(0L, full.plannedMsByKind[RawQualificationStepKind.CAMERA_CHECK])
        assertTrue(full.plannedMsByKind.getValue(RawQualificationStepKind.SWEEP) > 0)
        assertEquals(uncompressed.size, full.runCount)
    }

    // endregion

    // region camera-only records and the merge rule

    private val fp = "google/razr/blanc:15/V1/1:user/release-keys"
    private val dest = RawVideoQualifier.DESTINATION_ID
    private val ring = 1L shl 30

    private fun cameraOnlyRecord(spec: String, verdict: RawStagedVerdict, at: Long = 2_000) =
        requireNotNull(RawQualificationRecords.cameraOnly(fp, dest, config(spec), ring, verdict, at))

    private fun storedRecord(spec: String, status: RawBenchmarkStatus, codes: List<String>, at: Long = 1_000) = RawVideoQualificationRecord(
        fingerprint = fp,
        protocolVersion = RawThroughputBenchmark.PROTOCOL_VERSION,
        destinationId = dest,
        config = config(spec),
        ringBytes = ring,
        qualification = RawVideoQualification(
            status = status, reasonCode = codes.firstOrNull() ?: RawThroughputBenchmark.REASON_PASS, reasonCodes = codes,
            streamBytesPerSecond = config(spec).streamBytesPerSecond, requiredMedianBytesPerSecond = config(spec).streamBytesPerSecond * 5 / 4,
            sweepWindows = 60, sweepMedianBytesPerSecond = 1_500_000_000, sweepP01BytesPerSecond = 500_000_000,
            sweepMinBytesPerSecond = 400_000_000, medianMarginRatio = 4.0, p01MarginRatio = 1.5, ringBytes = ring,
            backlogLimitBytes = ring / 2, backlog = null, live = null, livePeakFraction = 0.1,
        ),
        measuredAtEpochMs = at,
    )

    @Test fun aCameraOnlyRecordHasNoSweepFiguresAndReadsRisky() {
        val verdict = RawQualificationStaging.cameraOnlyVerdict(RawStageResult.pass(listOf(reduced)))
        val record = cameraOnlyRecord("full@30", verdict)
        val q = record.qualification
        assertEquals(RawBenchmarkStatus.FAIL, q.status)
        assertEquals(storageNotTested, q.reasonCode)
        assertEquals(listOf(storageNotTested, reduced), q.reasonCodes)
        assertEquals(0, q.sweepWindows)
        assertNull(q.sweepMedianBytesPerSecond)
        assertNull(q.sweepP01BytesPerSecond)
        assertNull(q.sweepMinBytesPerSecond)
        assertNull(q.medianMarginRatio)
        assertNull(q.live)
        assertNull(q.backlog)
        assertNull(record.burstSeconds)
        assertEquals(RawQualificationMethods.CAMERA_ONLY, record.method)
        assertEquals(RawThroughputBenchmark.PROTOCOL_VERSION, record.protocolVersion)
        assertEquals(ring * RawThroughputBenchmark.RING_LIMIT_PERCENT / 100L, q.backlogLimitBytes)
        // The required median is what rule v2 would ask of the storage.
        assertEquals(config("full@30").streamBytesPerSecond * RawThroughputBenchmark.MARGIN_BASIS_POINTS / 10_000L, q.requiredMedianBytesPerSecond)
        assertTrue(RawQualificationRisk.isStorageFailure(q))
        assertTrue(RawQualificationRisk.isRisky(q, record.burstSeconds))
        // A compressed record's required median is at its ratio.
        val bp = requireNotNull(RawQualificationRecords.cameraOnly(fp, dest, config("full@30").copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK),
            ring, RawQualificationStaging.cameraOnlyCompressedVerdict(config("full@30"), RawStageResult.PASS, bench(900e6, 1.6)), 2_000, compressionRatio = 1.6))
        assertTrue(bp.qualification.requiredMedianBytesPerSecond < q.requiredMedianBytesPerSecond)
        // An unknown verdict, or no ring, is never stored.
        assertNull(RawQualificationRecords.cameraOnly(fp, dest, config("full@30"), ring, RawQualificationStaging.cameraOnlyVerdict(null), 2_000))
        assertNull(RawQualificationRecords.cameraOnly(fp, dest, config("full@30"), 0L, verdict, 2_000))
        // A camera failure is stored as a plain failure.
        val failed = cameraOnlyRecord("full@60", RawQualificationStaging.cameraOnlyVerdict(RawStageResult.fail(listOf("fps-not-reached"))))
        assertFalse(RawQualificationRisk.isStorageFailure(failed.qualification))
    }

    @Test fun aCameraOnlyRecordNeverReplacesAStorageTestedOne() {
        val risky = RawQualificationStaging.cameraOnlyVerdict(RawStageResult.PASS)
        val cameraFail = RawQualificationStaging.cameraOnlyVerdict(RawStageResult.fail(listOf("fps-not-reached")))
        val existing = listOf(
            storedRecord("full@60", RawBenchmarkStatus.FAIL, listOf(RawThroughputBenchmark.REASON_SWEEP_MEDIAN_MARGIN_FAIL)),
            storedRecord("full@30", RawBenchmarkStatus.PASS, emptyList()),
            cameraOnlyRecord("full@24", risky, at = 1_000),
            storedRecord("3840x2160@60", RawBenchmarkStatus.PASS, emptyList()),
        )
        val incoming = listOf(
            cameraOnlyRecord("full@60", risky), // storage-tested risky: kept as it is
            cameraOnlyRecord("full@30", risky), // a pass with a long run: kept
            cameraOnlyRecord("full@24", risky), // an earlier camera-only record: refreshed
            cameraOnlyRecord("3840x2160@60", cameraFail), // the camera now fails: revokes the pass
            cameraOnlyRecord("full@15", risky), // nothing yet: filled
        )
        val merged = RawQualificationRecords.cameraOnlyMerge(existing, incoming)
        assertEquals(listOf(config("full@24"), config("3840x2160@60"), config("full@15")), merged.map { it.config })
        // Records of another build, protocol or destination do not count as existing.
        val otherBuild = existing.map { it.copy(fingerprint = "other") }
        assertEquals(incoming, RawQualificationRecords.cameraOnlyMerge(otherBuild, incoming))
        val otherDestination = existing.map { it.copy(destinationId = "drive:1234") }
        assertEquals(incoming, RawQualificationRecords.cameraOnlyMerge(otherDestination, incoming))
        // The newest existing record decides: a later storage-tested record wins over an older camera-only one.
        val history = listOf(cameraOnlyRecord("full@24", risky, at = 1_000), storedRecord("full@24", RawBenchmarkStatus.PASS, emptyList(), at = 1_500))
        assertTrue(RawQualificationRecords.cameraOnlyMerge(history, listOf(cameraOnlyRecord("full@24", risky))).isEmpty())
        assertNotNull(RawQualificationRecords.cameraOnlyMerge(emptyList(), incoming).firstOrNull())
    }

    // endregion
}
