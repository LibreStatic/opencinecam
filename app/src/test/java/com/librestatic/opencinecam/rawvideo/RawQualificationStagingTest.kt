/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import com.librestatic.opencinecam.camera.RawBenchmarkStatus
import com.librestatic.opencinecam.camera.RawStorageSweep
import com.librestatic.opencinecam.camera.RawSustainedWrite
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawThroughputSample
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.service.rawVideoUsableConfigs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawQualificationStagingTest {
    private val sensorW = 4080
    private val sensorH = 3060

    private fun run(spec: String) = requireNotNull(RawQualificationSpecs.parse(spec, "0", sensorW, sensorH))
    private fun config(spec: String) = run(spec).config

    private val storageFail = RawStageResult.fail(listOf(RawThroughputBenchmark.REASON_SWEEP_MEDIAN_MARGIN_FAIL))
    private val cameraFail = RawStageResult.fail(listOf("fps-not-reached"))

    /** The descent as the qualifier runs it: heaviest first, stopping at the first pass. */
    private fun descend(results: Map<String, RawStageResult>): Map<RawVideoConfig, RawStageResult> {
        val longRuns = linkedMapOf<RawVideoConfig, RawStageResult>()
        for (r in RawQualificationStaging.longRunOrder(RawQualificationPlan.DEFAULT.specs.map(::run))) {
            val result = results[r.spec] ?: RawStageResult.PASS
            longRuns[r.config] = result
            if (!RawQualificationStaging.continueDescent(result)) break
        }
        return longRuns
    }

    @Test fun theHeaviestPassingCoversEveryLighterConfiguration() {
        val longRuns = descend(emptyMap())
        assertEquals(listOf(config("full@60")), longRuns.keys.toList())
        for (spec in listOf("3840x2160@60", "full@30", "full@24", "full@15", "1920x1080@30")) {
            val verdict = RawQualificationStaging.uncompressedVerdict(config(spec), longRuns, RawStageResult.PASS)
            assertEquals(spec, RawBenchmarkStatus.PASS, verdict.status)
            assertEquals(RawQualificationMethods.EXTRAPOLATED, verdict.method)
            assertEquals(config("full@60"), verdict.coveredBy)
        }
        val own = RawQualificationStaging.uncompressedVerdict(config("full@60"), longRuns, null)
        assertEquals(RawBenchmarkStatus.PASS, own.status)
        assertEquals(RawQualificationMethods.LONG_RUN, own.method)
        assertNull(own.coveredBy)
    }

    @Test fun aStorageFailureOnTheHeaviestMakesItRiskyAndTheNextPassCoversTheRest() {
        val longRuns = descend(mapOf("full@60" to storageFail))
        assertEquals(listOf(config("full@60"), config("3840x2160@60")), longRuns.keys.toList())
        val heaviest = RawQualificationStaging.uncompressedVerdict(config("full@60"), longRuns, null)
        assertTrue(heaviest.risky)
        assertTrue(heaviest.cameraOk)
        assertFalse(heaviest.passed)
        val lighter = RawQualificationStaging.uncompressedVerdict(config("full@30"), longRuns, RawStageResult.PASS)
        assertEquals(RawBenchmarkStatus.PASS, lighter.status)
        assertEquals(config("3840x2160@60"), lighter.coveredBy)
    }

    @Test fun aCameraFailureInALongRunFailsItAndTheDescentGoesOn() {
        val longRuns = descend(mapOf("full@60" to cameraFail))
        assertEquals(2, longRuns.size)
        val heaviest = RawQualificationStaging.uncompressedVerdict(config("full@60"), longRuns, null)
        assertEquals(RawBenchmarkStatus.FAIL, heaviest.status)
        assertFalse(heaviest.risky)
        assertFalse(heaviest.cameraOk)
        assertEquals(listOf("fps-not-reached"), heaviest.reasonCodes)
    }

    @Test fun anExtrapolatedConfigurationNeedsItsCameraCheck() {
        val longRuns = descend(emptyMap())
        val failed = RawQualificationStaging.uncompressedVerdict(config("1920x1080@60"), longRuns, cameraFail)
        assertEquals(RawBenchmarkStatus.FAIL, failed.status)
        assertEquals(config("full@60"), failed.coveredBy)
        assertFalse(failed.passed)
        val missing = RawQualificationStaging.uncompressedVerdict(config("1920x1080@60"), longRuns, null)
        assertEquals(RawBenchmarkStatus.UNKNOWN, missing.status)
        assertEquals(listOf(RawQualificationStaging.REASON_CAMERA_CHECK_MISSING), missing.reasonCodes)
        val skipped = RawQualificationStaging.uncompressedVerdict(config("1920x1080@60"), longRuns, RawStageResult.skipped("thermal-wait-timeout"))
        assertEquals(RawBenchmarkStatus.UNKNOWN, skipped.status)
    }

    @Test fun withoutAPassingCoverACheckedConfigurationStaysUnknown() {
        // Every long run failed for the storage: nothing vouches for any rate.
        val longRuns = RawQualificationStaging.longRunOrder(RawQualificationPlan.DEFAULT.specs.map(::run)).associate { it.config to storageFail }
        assertNull(RawQualificationStaging.coveringRun(1, longRuns))
        val skippedOwnRun = RawStageResult.skipped("insufficient-space")
        val withSkip = RawQualificationStaging.uncompressedVerdict(config("full@15"), emptyMap(), RawStageResult.PASS, skippedOwnRun)
        assertEquals(listOf("insufficient-space"), withSkip.reasonCodes)
        val plain = RawQualificationStaging.uncompressedVerdict(config("full@15"), emptyMap(), RawStageResult.PASS)
        assertEquals(listOf(RawQualificationStaging.REASON_STORAGE_NOT_COVERED), plain.reasonCodes)
        // The cover is the lightest passing run at or above the rate.
        val covers = mapOf(config("full@60") to RawStageResult.PASS, config("3840x2160@60") to RawStageResult.PASS, config("full@30") to storageFail)
        assertEquals(config("3840x2160@60"), RawQualificationStaging.coveringRun(config("full@30").streamBytesPerSecond, covers))
        assertEquals(config("full@60"), RawQualificationStaging.coveringRun(config("3840x2160@60").streamBytesPerSecond + 1, covers))
    }

    @Test fun severeHeatFailsTheConfiguration() {
        assertEquals(emptyList<String>(), RawQualificationStaging.thermalReasons(2))
        assertEquals(listOf(RawQualificationStaging.REASON_THERMAL_LIMIT), RawQualificationStaging.thermalReasons(3))
        assertEquals(listOf(RawQualificationStaging.REASON_THERMAL_LIMIT), RawQualificationStaging.thermalReasons(6))
        val hot = RawStageResult.fail(RawQualificationStaging.thermalReasons(4))
        assertFalse(hot.cameraOk)
        val longRuns = descend(mapOf("full@60" to hot))
        val verdict = RawQualificationStaging.uncompressedVerdict(config("full@60"), longRuns, null)
        assertEquals(RawBenchmarkStatus.FAIL, verdict.status)
        assertFalse(verdict.risky)
        // A benchmark the heat spoiled fails the LJ92 configurations.
        val spoiled = bench(900e6, 1.6, reasons = listOf(RawQualificationStaging.REASON_THERMAL_LIMIT))
        val lj92 = RawQualificationStaging.compressedVerdict(config("full@30"), RawStageResult.PASS, spoiled, longRuns)
        assertEquals(RawBenchmarkStatus.FAIL, lj92.status)
        assertEquals(listOf(RawQualificationStaging.REASON_THERMAL_LIMIT), lj92.reasonCodes)
    }

    private fun bench(
        pixels: Double,
        ratio: Double,
        threads: Int = 4,
        reasons: List<String> = emptyList(),
        on: String = "full@60",
        held: Boolean = true,
        encoding: String = RawVideoConfig.ENCODING_LJ92,
    ) = RawEncodeBenchFigures(config(on), pixels, ratio, threads, 8, held, reasons, encoding)

    /** The descent as the qualifier runs it: bench the next configuration until it stops; [measure] gives each bench's figures. */
    private fun benchDescent(candidates: List<RawVideoConfig>, measure: (RawVideoConfig) -> RawEncodeBenchFigures?): Map<RawVideoConfig, RawEncodeBenchFigures?> {
        val benches = linkedMapOf<RawVideoConfig, RawEncodeBenchFigures?>()
        while (true) {
            val next = RawQualificationStaging.nextEncodeBenchConfig(candidates, benches) ?: return benches
            benches[next] = measure(next)
        }
    }

    /** The default plan's candidates, heaviest first, as the qualifier orders them. */
    private val lj92Candidates get() = RawQualificationStaging.longRunOrder(RawQualificationPlan.DEFAULT.specs.map(::run)).map { it.config }

    @Test fun theDefaultCandidatesStartWithTheHeaviest() {
        assertEquals(listOf("full@60", "3840x2160@60", "full@30", "full@24").map(::config), lj92Candidates.take(4))
    }

    @Test fun aHeldSufficientFirstBenchJudgesEverythingAlone() {
        val longRuns = descend(emptyMap())
        val first = bench(900e6, 1.6)
        val benches = benchDescent(lj92Candidates) { first }
        assertEquals(listOf(config("full@60")), benches.keys.toList())
        val judges = RawQualificationStaging.encodeJudges(lj92Candidates, benches)
        assertEquals(lj92Candidates.toSet(), judges.keys)
        assertTrue(judges.values.all { it == first })
        for (twin in lj92Candidates) {
            val verdict = RawQualificationStaging.compressedVerdict(twin, RawStageResult.PASS, judges[twin], longRuns)
            assertEquals(twin.toString(), RawBenchmarkStatus.PASS, verdict.status)
        }
    }

    @Test fun theRazrBenchDescendsWhenTheCameraStarvesUnderTheEncoder() {
        // The Razr (4000x3000 sensor): full@60 fails only for the storage, 3840x2160@60 passes.
        val razr = { spec: String -> requireNotNull(RawQualificationSpecs.parse(spec, "0", 4000, 3000)).config }
        val candidates = listOf("full@60", "3840x2160@60", "full@30", "1920x1080@60").map(razr)
        val longRuns = mapOf(razr("full@60") to storageFail, razr("3840x2160@60") to RawStageResult.PASS)
        // Bench on full@60 with 4 threads: the camera delivered 55.9 fps, the encoder sustains 47 fps.
        val fullPixels = 4000.0 * 3000
        val starved = RawEncodeBenchFigures(razr("full@60"), 47 * fullPixels, 1.6, 4, 8, cameraHeld = false)
        // UHD@60 (498 Mpx/s): held, and 567 Mpx/s clears 498 x 1.1225 = 559.
        val uhd = RawEncodeBenchFigures(razr("3840x2160@60"), 567e6, 1.6, 4, 8, cameraHeld = true)
        val benches = benchDescent(candidates) { if (it == razr("full@60")) starved else uhd }
        assertEquals(listOf(razr("full@60"), razr("3840x2160@60")), benches.keys.toList())
        val judges = RawQualificationStaging.encodeJudges(candidates, benches)
        assertEquals(starved, judges[razr("full@60")])
        val full = RawQualificationStaging.compressedVerdict(razr("full@60"), storageFail, judges[razr("full@60")], longRuns)
        assertEquals(RawBenchmarkStatus.FAIL, full.status)
        assertFalse(full.risky)
        assertEquals(listOf(RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED, RawQualificationStaging.REASON_COMPRESSED_ENCODE_TOO_SLOW), full.reasonCodes)
        for (spec in listOf("3840x2160@60", "full@30", "1920x1080@60")) {
            assertEquals(spec, uhd, judges[razr(spec)])
            val verdict = RawQualificationStaging.compressedVerdict(razr(spec), RawStageResult.PASS, judges[razr(spec)], longRuns)
            assertEquals(spec, RawBenchmarkStatus.PASS, verdict.status)
            assertEquals(spec, razr("3840x2160@60"), verdict.coveredBy)
        }
    }

    @Test fun aCandidateTheFailingBenchIsTooSlowForFailsWithoutItsOwnBench() {
        val full = config("full@60")
        // Starved and too slow for 3840x2160@60 (498 Mpx/s x 1.1225 = 559), fast enough for full@30.
        val failing = bench(550e6, 1.6, held = false)
        val benched = mutableListOf<RawVideoConfig>()
        val benches = benchDescent(lj92Candidates) { benched += it; if (it == full) failing else bench(600e6, 1.6, on = "full@30") }
        assertEquals(listOf(full, config("full@30")), benched)
        val judges = RawQualificationStaging.encodeJudges(lj92Candidates, benches)
        assertEquals(failing, judges[config("3840x2160@60")])
        val uhd = RawQualificationStaging.compressedVerdict(config("3840x2160@60"), RawStageResult.PASS, judges[config("3840x2160@60")], descend(emptyMap()))
        assertEquals(listOf(RawQualificationStaging.REASON_COMPRESSED_ENCODE_TOO_SLOW), uhd.reasonCodes)
        assertEquals(RawBenchmarkStatus.PASS,
            RawQualificationStaging.compressedVerdict(config("full@24"), RawStageResult.PASS, judges[config("full@24")], descend(emptyMap())).status)
    }

    @Test fun heatOnlyBenchesRunToTheCapAndKeepTheRestThermalLimited() {
        val thermal = listOf(RawQualificationStaging.REASON_THERMAL_LIMIT)
        val benches = benchDescent(lj92Candidates) { bench(900e6, 1.6, reasons = thermal, held = false).copy(config = it) }
        assertEquals(lj92Candidates.take(RawQualificationStaging.MAX_ENCODE_BENCH_CONFIGS), benches.keys.toList())
        val judges = RawQualificationStaging.encodeJudges(lj92Candidates, benches)
        assertEquals(lj92Candidates.toSet(), judges.keys)
        // The leftovers the cap left undecided take the last heat-spoiled bench, not a missing one.
        val last = benches.values.last()
        lj92Candidates.drop(RawQualificationStaging.MAX_ENCODE_BENCH_CONFIGS).forEach { assertEquals(it.toString(), last, judges[it]) }
        for (twin in lj92Candidates) {
            val verdict = RawQualificationStaging.compressedVerdict(twin, RawStageResult.PASS, judges[twin], descend(emptyMap()))
            assertEquals(RawBenchmarkStatus.FAIL, verdict.status)
            assertEquals(thermal, verdict.reasonCodes)
        }
        // A benchmark that measured nothing stops it, leaving every candidate without one.
        val empty = benchDescent(lj92Candidates) { null }
        assertEquals(1, empty.size)
        assertEquals(listOf(RawQualificationStaging.REASON_COMPRESSED_BENCH_MISSING), RawQualificationStaging.compressedVerdict(config("full@15"),
            RawStageResult.PASS, RawQualificationStaging.encodeJudges(lj92Candidates, empty)[config("full@15")], descend(emptyMap())).reasonCodes)
    }

    @Test fun aHeatOnlyBenchJudgesOnlyItselfAndTheDescentGoesOn() {
        val hot = bench(900e6, 1.6, reasons = listOf(RawQualificationStaging.REASON_THERMAL_LIMIT), held = false)
        val benches = linkedMapOf<RawVideoConfig, RawEncodeBenchFigures?>(config("full@60") to hot)
        assertEquals(config("3840x2160@60"), RawQualificationStaging.nextEncodeBenchConfig(lj92Candidates, benches))
        val judges = RawQualificationStaging.encodeJudges(lj92Candidates, benches)
        assertEquals(mapOf(config("full@60") to hot), judges)
    }

    @Test fun aPassingBenchAfterAHeatOnlyOneJudgesTheRest() {
        val hot = bench(900e6, 1.6, reasons = listOf(RawQualificationStaging.REASON_THERMAL_LIMIT), held = false)
        val cool = bench(900e6, 1.6, on = "3840x2160@60")
        val benches = benchDescent(lj92Candidates) { if (it == config("full@60")) hot else cool }
        assertEquals(listOf(config("full@60"), config("3840x2160@60")), benches.keys.toList())
        val judges = RawQualificationStaging.encodeJudges(lj92Candidates, benches)
        assertEquals(hot, judges[config("full@60")])
        lj92Candidates.drop(1).forEach { assertEquals(it.toString(), cool, judges[it]) }
        val longRuns = descend(emptyMap())
        val full = RawQualificationStaging.compressedVerdict(config("full@60"), RawStageResult.PASS, judges[config("full@60")], longRuns)
        assertEquals(listOf(RawQualificationStaging.REASON_THERMAL_LIMIT), full.reasonCodes)
        for (twin in lj92Candidates.drop(1)) {
            assertEquals(twin.toString(), RawBenchmarkStatus.PASS,
                RawQualificationStaging.compressedVerdict(twin, RawStageResult.PASS, judges[twin], longRuns).status)
        }
    }

    @Test fun aBenchWithHeatAndAnotherReasonStillStopsTheDescent() {
        val spoiled = bench(900e6, 1.6, reasons = listOf(RawQualificationStaging.REASON_THERMAL_LIMIT, "writer-error"), held = false)
        val benches = benchDescent(lj92Candidates) { spoiled }
        assertEquals(listOf(config("full@60")), benches.keys.toList())
        val judges = RawQualificationStaging.encodeJudges(lj92Candidates, benches)
        assertEquals(lj92Candidates.toSet(), judges.keys)
        assertTrue(judges.values.all { it == spoiled })
    }

    @Test fun aHeatOnlyBenchBeforeTheCapWithAFailingLastLeavesTheRestUnknown() {
        // Heat on the first, then two starved benches: the cap's leftovers have no benchmark, as without heat.
        val hot = bench(2e9, 1.6, reasons = listOf(RawQualificationStaging.REASON_THERMAL_LIMIT), held = false)
        val benches = benchDescent(lj92Candidates) { if (it == config("full@60")) hot else bench(2e9, 1.6, held = false).copy(config = it) }
        assertEquals(lj92Candidates.take(3), benches.keys.toList())
        val judges = RawQualificationStaging.encodeJudges(lj92Candidates, benches)
        assertEquals(lj92Candidates.take(3).toSet(), judges.keys)
    }

    @Test fun theBenchCapLeavesTheRestUnknown() {
        // Each bench starves the camera but keeps up with every configuration's encode.
        val benches = benchDescent(lj92Candidates) { bench(2e9, 1.6, on = "full@60", held = false).copy(config = it) }
        assertEquals(RawQualificationStaging.MAX_ENCODE_BENCH_CONFIGS, benches.size)
        assertEquals(lj92Candidates.take(3), benches.keys.toList())
        val judges = RawQualificationStaging.encodeJudges(lj92Candidates, benches)
        assertEquals(lj92Candidates.take(3).toSet(), judges.keys)
        val longRuns = descend(emptyMap())
        assertEquals(listOf(RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED),
            RawQualificationStaging.compressedVerdict(config("full@30"), RawStageResult.PASS, judges[config("full@30")], longRuns).reasonCodes)
        val capped = RawQualificationStaging.compressedVerdict(config("full@24"), RawStageResult.PASS, judges[config("full@24")], longRuns)
        assertEquals(RawBenchmarkStatus.UNKNOWN, capped.status)
        assertEquals(listOf(RawQualificationStaging.REASON_COMPRESSED_BENCH_MISSING), capped.reasonCodes)
        assertNull(RawQualificationStaging.nextEncodeBenchConfig(emptyList(), emptyMap()))
    }

    @Test fun aStarvedBenchOnlyStarvesConfigurationsAtOrAboveItsPixelRate() {
        val starved = bench(2e9, 1.6, on = "3840x2160@60", held = false)
        val longRuns = descend(emptyMap())
        assertEquals(listOf(RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED),
            RawQualificationStaging.compressedVerdict(config("full@60"), RawStageResult.PASS, starved, longRuns).reasonCodes)
        assertEquals(RawBenchmarkStatus.PASS, RawQualificationStaging.compressedVerdict(config("full@30"), RawStageResult.PASS, starved, longRuns).status)
    }

    @Test fun theBenchCameraHoldsOnTheCameraCheckCriteria() {
        assertTrue(RawQualificationStaging.benchCameraHeld(59.8, 60, 1_794, 1_794))
        assertFalse(RawQualificationStaging.benchCameraHeld(51.0, 60, 1_530, 1_530))
        assertFalse(RawQualificationStaging.benchCameraHeld(60.0, 60, 1_790, 1_794))
        assertFalse(RawQualificationStaging.benchCameraHeld(0.0, 60, 0, 0))
    }

    @Test fun anLj92TwinIsReadyDespiteARiskyTwinWhenTheCompressedRateIsCovered() {
        // The Razr: full@60 fails only for the storage, 3840x2160@60 (622 MB/s) passes.
        val longRuns = descend(mapOf("full@60" to storageFail))
        val twin = config("full@60")
        val twinCamera = RawQualificationStaging.cameraEvidence(twin, longRuns, emptyMap())
        assertEquals(storageFail, twinCamera)
        // 4080x3060x60 = 749 Mpx/s; 900 Mpx/s clears the 1.1225 margin. At 1.6 the stream is 585 MB/s.
        val ready = RawQualificationStaging.compressedVerdict(twin, twinCamera, bench(900e6, 1.6), longRuns)
        assertEquals(RawBenchmarkStatus.PASS, ready.status)
        assertEquals(RawQualificationMethods.COMPRESSED_EXTRAPOLATED, ready.method)
        assertEquals(config("3840x2160@60"), ready.coveredBy)
        // At 1.4 the compressed stream (669 MB/s) is above every passing run: risky, like its twin.
        val risky = RawQualificationStaging.compressedVerdict(twin, twinCamera, bench(900e6, 1.4), longRuns)
        assertTrue(risky.risky)
        assertEquals(listOf(RawThroughputBenchmark.REASON_SWEEP_MEDIAN_MARGIN_FAIL), risky.reasonCodes)
        // With no storage failure and no cover it is not judged.
        val unjudged = RawQualificationStaging.compressedVerdict(twin, RawStageResult.PASS, bench(900e6, 1.0), mapOf(config("full@30") to RawStageResult.PASS))
        assertEquals(RawBenchmarkStatus.UNKNOWN, unjudged.status)
        assertEquals(listOf(RawQualificationStaging.REASON_STORAGE_NOT_COVERED), unjudged.reasonCodes)
    }

    @Test fun anLj92TwinIsNotReadyWhenTheEncoderIsTooSlowOrTheTwinCameraFailed() {
        val longRuns = descend(mapOf("full@60" to storageFail))
        val twin = config("full@60")
        val pixels = RawQualificationStaging.pixelsPerSecond(twin)
        assertEquals(4080.0 * 3060 * 60, pixels, 0.0)
        val slow = RawQualificationStaging.compressedVerdict(twin, storageFail, bench(pixels * 1.12, 2.0), longRuns)
        assertEquals(RawBenchmarkStatus.FAIL, slow.status)
        assertEquals(listOf(RawQualificationStaging.REASON_COMPRESSED_ENCODE_TOO_SLOW), slow.reasonCodes)
        assertEquals(RawBenchmarkStatus.PASS,
            RawQualificationStaging.compressedVerdict(twin, storageFail, bench(pixels * 1.1225, 2.0), longRuns).status)
        val twinFailed = RawQualificationStaging.compressedVerdict(config("1920x1080@60"), cameraFail, bench(900e6, 2.0), longRuns)
        assertEquals(RawBenchmarkStatus.FAIL, twinFailed.status)
        assertEquals(listOf("fps-not-reached"), twinFailed.reasonCodes)
        assertEquals(listOf(RawQualificationStaging.REASON_COMPRESSED_TWIN_MISSING),
            RawQualificationStaging.compressedVerdict(twin, null, bench(900e6, 2.0), longRuns).reasonCodes)
        assertEquals(listOf("insufficient-space"),
            RawQualificationStaging.compressedVerdict(twin, RawStageResult.skipped("insufficient-space"), bench(900e6, 2.0), longRuns).reasonCodes)
        assertEquals(listOf(RawQualificationStaging.REASON_COMPRESSED_BENCH_MISSING),
            RawQualificationStaging.compressedVerdict(twin, storageFail, null, longRuns).reasonCodes)
    }

    @Test fun theBenchmarkRunsOnTheHeaviestConfigurationWhoseCameraDelivered() {
        val runs = RawQualificationPlan.DEFAULT.specs.map(::run)
        val failed = setOf(config("full@60"))
        assertEquals("3840x2160@60", RawQualificationStaging.benchRun(runs) { it !in failed }?.spec)
        assertNull(RawQualificationStaging.benchRun(runs) { false })
        // The camera evidence is the config's own long run when one ran, else its check.
        val longRuns = mapOf(config("full@60") to storageFail)
        val checks = mapOf(config("full@30") to cameraFail)
        assertEquals(storageFail, RawQualificationStaging.cameraEvidence(config("full@60"), longRuns, checks))
        assertEquals(cameraFail, RawQualificationStaging.cameraEvidence(config("full@30"), longRuns, checks))
        assertNull(RawQualificationStaging.cameraEvidence(config("full@24"), longRuns, checks))
    }

    @Test fun theCoolCameraCheckIsTheCameraEvidenceEvenWhenALongRunRanToo() {
        // The checks run first, on a cool phone; the long run after the sweep may run hot.
        val hot = RawStageResult.fail(listOf(RawQualificationStaging.REASON_THERMAL_LIMIT))
        val longRuns = mapOf(config("full@60") to hot, config("full@30") to storageFail)
        val checks = mapOf(config("full@60") to RawStageResult.PASS, config("full@30") to RawStageResult.skipped("ring-unavailable"))
        assertEquals(RawStageResult.PASS, RawQualificationStaging.cameraEvidence(config("full@60"), longRuns, checks))
        // A skipped check judged nothing: the long run speaks for the camera.
        assertEquals(storageFail, RawQualificationStaging.cameraEvidence(config("full@30"), longRuns, checks))
        // With neither, the skipped check says why.
        val skipped = RawStageResult.skipped("thermal-skip")
        assertEquals(skipped, RawQualificationStaging.cameraEvidence(config("full@24"), longRuns, mapOf(config("full@24") to skipped)))
    }

    @Test fun aLongRunIsSkippedOnlyWhenItsCameraCheckProvedTheCameraCannotDeliver() {
        assertTrue(RawQualificationStaging.skipLongRun(cameraFail))
        assertTrue(RawQualificationStaging.skipLongRun(RawStageResult.fail(listOf(RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT))))
        // A pass, a reduced rate, a skipped or missing check, or a heat failure the camera delivered through keep the run.
        assertFalse(RawQualificationStaging.skipLongRun(RawStageResult.PASS))
        assertFalse(RawQualificationStaging.skipLongRun(RawStageResult.pass(listOf(RawQualificationStaging.REASON_FPS_REDUCED))))
        assertFalse(RawQualificationStaging.skipLongRun(RawStageResult.skipped("thermal-skip")))
        assertFalse(RawQualificationStaging.skipLongRun(null))
        assertFalse(RawQualificationStaging.skipLongRun(RawStageResult.fail(listOf(RawQualificationStaging.REASON_THERMAL_LIMIT))))
    }

    @Test fun aConfigurationWithBothALongRunAndACameraCheckTakesItsLongRunsVerdict() {
        val full60 = config("full@60")
        // Its own long run judged the storage: risky, even though the check passed.
        val longRuns = mapOf(full60 to storageFail, config("full@30") to RawStageResult.PASS)
        val risky = RawQualificationStaging.uncompressedVerdict(full60, longRuns, RawStageResult.PASS)
        assertEquals(storageFail.status, risky.status)
        assertEquals(storageFail.reasonCodes, risky.reasonCodes)
        assertEquals(RawQualificationMethods.LONG_RUN, risky.method)
        // A passing long run passes with any check.
        val passed = RawQualificationStaging.uncompressedVerdict(config("full@30"), longRuns, cameraFail)
        assertEquals(RawBenchmarkStatus.PASS, passed.status)
        assertEquals(RawQualificationMethods.LONG_RUN, passed.method)
        // A configuration whose long run was skipped for its failed check takes the check's failure.
        val skippedForCamera = RawQualificationStaging.uncompressedVerdict(config("3840x2160@60"), longRuns, cameraFail)
        assertEquals(RawBenchmarkStatus.FAIL, skippedForCamera.status)
        assertEquals(cameraFail.reasonCodes, skippedForCamera.reasonCodes)
        assertEquals(RawQualificationMethods.EXTRAPOLATED, skippedForCamera.method)
    }

    @Test fun anLj92ConfigurationIsUsableUnlessItsTwinFailedForTheCamera() {
        val sizes = listOf(sensorW to sensorH)
        val lj92 = config("full@60+lj92")
        val qualified = listOf(config("full@30"), lj92)
        // Its risky twin is not qualified, and that no longer holds it back.
        assertEquals(listOf(lj92, config("full@30")), rawVideoUsableConfigs("0", sizes, qualified, codecAvailable = true))
        assertEquals(listOf(config("full@30")),
            rawVideoUsableConfigs("0", sizes, qualified, codecAvailable = true, cameraFailed = listOf(config("full@60"))))
        assertEquals(listOf(config("full@30")), rawVideoUsableConfigs("0", sizes, qualified, codecAvailable = false))
    }

    @Test fun anExtrapolatedRecordDropsTheMissingLiveRunButKeepsSweepReasons() {
        val fast = RawStorageSweep(RawVideoQualifier.DESTINATION_ID, List(60) { RawThroughputSample(1_000, 1_400_000_000, 0) })
        val slow = RawStorageSweep(RawVideoQualifier.DESTINATION_ID, List(60) { RawThroughputSample(1_000, 300_000_000, 0) })
        val ring = 1_061_208_000L
        val config = config("full@30")
        val pass = RawStagedVerdict(RawBenchmarkStatus.PASS, emptyList(), RawQualificationMethods.EXTRAPOLATED, config("full@60"))
        val base = RawThroughputBenchmark.qualifyWithBuffer(config, ring, fast, null)
        assertEquals(listOf(RawThroughputBenchmark.REASON_LIVE_RUN_MISSING), base.reasonCodes)
        val stored = RawQualificationStaging.extrapolatedQualification(base, pass)
        assertEquals(RawBenchmarkStatus.PASS, stored.status)
        assertEquals(listOf(RawThroughputBenchmark.REASON_PASS), stored.reasonCodes)
        // A sweep too slow for the rate still fails it, even under a cover.
        val slowStored = RawQualificationStaging.extrapolatedQualification(RawThroughputBenchmark.qualifyWithBuffer(config, ring, slow, null), pass)
        assertEquals(RawBenchmarkStatus.FAIL, slowStored.status)
        assertTrue(RawThroughputBenchmark.REASON_SWEEP_MEDIAN_MARGIN_FAIL in slowStored.reasonCodes)
        assertFalse(RawThroughputBenchmark.REASON_LIVE_RUN_MISSING in slowStored.reasonCodes)
        // A failed verdict keeps its reasons first.
        val failed = RawQualificationStaging.extrapolatedQualification(base,
            RawStagedVerdict(RawBenchmarkStatus.FAIL, listOf("fps-not-reached"), RawQualificationMethods.EXTRAPOLATED))
        assertEquals(RawBenchmarkStatus.FAIL, failed.status)
        assertEquals(listOf("fps-not-reached"), failed.reasonCodes)
        // The LJ92 ratio relaxes the median the storage needs.
        val lj92 = config.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        val tight = RawStorageSweep(RawVideoQualifier.DESTINATION_ID, List(60) { RawThroughputSample(1_000, 400_000_000, 0) })
        assertTrue(RawThroughputBenchmark.REASON_SWEEP_MEDIAN_MARGIN_FAIL in RawThroughputBenchmark.qualifyWithBuffer(lj92, ring, tight, null).reasonCodes)
        assertEquals(RawBenchmarkStatus.PASS,
            RawQualificationStaging.extrapolatedQualification(RawThroughputBenchmark.qualifyWithBuffer(lj92, ring, tight, null, 1.6), pass).status)
    }

    @Test fun benchmarkFigures() {
        assertEquals(listOf(10L, 20L, 30L, 40L), RawQualificationStaging.quarterMeans(longArrayOf(10, 10, 20, 20, 30, 30, 40, 40)))
        assertEquals(listOf(0L, 5L, 0L, 7L), RawQualificationStaging.quarterMeans(longArrayOf(5, 7)))
        assertTrue(RawQualificationStaging.quarterMeans(LongArray(0)).isEmpty())
        // The slowest quarter sets the sustained rate.
        assertEquals(50.0, RawQualificationStaging.sustainedEncodeFps(listOf(10_000_000L, 20_000_000L, 0L))!!, 1e-9)
        assertNull(RawQualificationStaging.sustainedEncodeFps(listOf(0L)))
        assertEquals(1.6, RawQualificationStaging.conservativeRatio(1.69), 1e-9)
        assertEquals(1.0, RawQualificationStaging.conservativeRatio(0.8), 0.0)
        assertEquals(1.0, RawQualificationStaging.conservativeRatio(null), 0.0)
        assertEquals(1.0, RawQualificationStaging.conservativeRatio(Double.NaN), 0.0)
        val on = config("1920x1080@30")
        val figures = requireNotNull(RawQualificationStaging.benchFigures(on, listOf(12_500_000L, 14_000_000L), 1.77, 4, 8, cameraHeld = false))
        assertEquals(1e9 / 14_000_000 * 1920 * 1080, figures.sustainedPixelsPerSecond, 1e-3)
        assertEquals(1.7, figures.conservativeRatio, 1e-9)
        assertEquals(on, figures.config)
        assertFalse(figures.cameraHeld)
        assertNull(RawQualificationStaging.benchFigures(on, emptyList(), 1.7, 4, 8))
        val two = bench(1.0, 1.0, threads = 2)
        val four = bench(2.0, 1.0, threads = 4)
        assertEquals(four, RawQualificationStaging.verdictBench(listOf(two, four), 4))
        assertEquals(two, RawQualificationStaging.verdictBench(listOf(two, bench(3.0, 1.0, threads = 8)), 4))
        assertNull(RawQualificationStaging.verdictBench(emptyList(), 4))
        assertEquals(585_225_000L, RawQualificationStaging.compressedBytesPerSecond(config("full@60"), 1.6))
    }

    // region block-pack, the recorded codec

    @Test fun aBlockPackDescentJudgesBlockPackTwinsWithItsOwnFailures() {
        // The Razr: block-pack keeps up with about 4080x3060 at 150 fps on 2 threads (6.6 ms/frame on one).
        val longRuns = descend(mapOf("full@60" to storageFail))
        val full = config("full@60")
        val uhd = config("3840x2160@60")
        // full@60 starved the camera under the encode load; 3840x2160@60 held it and keeps up.
        val starved = bench(1.9e9, 1.4, threads = 2, on = "full@60", held = false, encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val held = bench(1.9e9, 1.4, threads = 2, on = "3840x2160@60", encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val benches = benchDescent(lj92Candidates) { if (it == full) starved else held }
        assertEquals(listOf(full, uhd), benches.keys.toList())
        val judges = RawQualificationStaging.encodeJudges(lj92Candidates, benches)
        val fullVerdict = RawQualificationStaging.compressedVerdict(full, storageFail, judges[full], longRuns)
        assertEquals(RawBenchmarkStatus.FAIL, fullVerdict.status)
        assertEquals(listOf(RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED), fullVerdict.reasonCodes)
        assertFalse(fullVerdict.risky)
        // 3840x2160@60 at 1.4 streams 444 MB/s, under the 622 MB/s run that passed.
        val uhdVerdict = RawQualificationStaging.compressedVerdict(uhd, RawStageResult.PASS, judges[uhd], longRuns)
        assertEquals(RawBenchmarkStatus.PASS, uhdVerdict.status)
        assertEquals(RawQualificationMethods.COMPRESSED_EXTRAPOLATED, uhdVerdict.method)
        // A block-pack encoder too slow for the rate fails it.
        val slow = bench(RawQualificationStaging.pixelsPerSecond(uhd), 2.0, threads = 2, on = "3840x2160@60",
            encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        assertEquals(listOf(RawQualificationStaging.REASON_COMPRESSED_ENCODE_TOO_SLOW),
            RawQualificationStaging.compressedVerdict(uhd, RawStageResult.PASS, slow, longRuns).reasonCodes)
        // The verdict thread count is the codec's production setting.
        val one = bench(1.0, 1.0, threads = 1, encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val two = bench(2.0, 1.0, threads = 2, encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        assertEquals(two, RawQualificationStaging.verdictBench(listOf(one, two), RawFrameEncoders.defaultThreads(RawVideoConfig.ENCODING_BLOCK_PACK)))
    }

    @Test fun legacyLj92ReasonsAndMethodsKeepTheirMeaning() {
        assertEquals(RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED, RawQualificationStaging.canonicalReason("lj92-camera-starved"))
        assertEquals(RawQualificationStaging.REASON_COMPRESSED_ENCODE_TOO_SLOW, RawQualificationStaging.canonicalReason("lj92-encode-too-slow"))
        assertEquals(RawQualificationStaging.REASON_COMPRESSED_BENCH_MISSING, RawQualificationStaging.canonicalReason("lj92-bench-missing"))
        assertEquals(RawQualificationStaging.REASON_COMPRESSED_TWIN_MISSING, RawQualificationStaging.canonicalReason("lj92-twin-missing"))
        assertEquals("fps-not-reached", RawQualificationStaging.canonicalReason("fps-not-reached"))
        assertEquals("compressed-camera-starved", RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED)
        assertEquals("compressed-encode-too-slow", RawQualificationStaging.REASON_COMPRESSED_ENCODE_TOO_SLOW)
        assertTrue(RawQualificationMethods.isCompressedExtrapolated("lj92-extrapolated"))
        assertTrue(RawQualificationMethods.isCompressedExtrapolated("compressed-extrapolated"))
        assertFalse(RawQualificationMethods.isCompressedExtrapolated(RawQualificationMethods.EXTRAPOLATED))
        // A legacy encoder failure is a failure, never a storage risk.
        assertFalse(RawQualificationRisk.isStorageOnly(listOf("lj92-encode-too-slow")))
    }

    // endregion

    // region sustained rate past the write cache

    /** The Razr-like cliff: 56 GB of cache, then [sustained] B/s. */
    private fun cliff(sustained: Long?, detected: Boolean = true) =
        RawSustainedWrite(detected, if (detected) 56 else null, if (detected) 56_000_000_000L else null, if (detected) null else 1L,
            1_200_000_000L, sustained, 80, if (detected) 24 else 0)

    @Test fun anExtrapolatedStreamAboveTheSustainedRateIsRisky() {
        val q = RawThroughputBenchmark.qualifyWithBuffer(config("full@30"), 1_061_208_000L,
            RawStorageSweep(RawVideoQualifier.DESTINATION_ID, List(60) { RawThroughputSample(1_000, 1_400_000_000, 0) }), null)
        val pass = RawQualificationStaging.extrapolatedQualification(q,
            RawStagedVerdict(RawBenchmarkStatus.PASS, emptyList(), RawQualificationMethods.EXTRAPOLATED, config("full@60")))
        assertEquals(RawBenchmarkStatus.PASS, pass.status)
        // full@30 streams 468 MB/s: above a 400 MB/s sustained rate, under a 500 MB/s one.
        val full30 = config("full@30")
        assertEquals(listOf(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED),
            RawQualificationStaging.sustainedRateReasons(full30, 1.0, cliff(400_000_000L)))
        assertTrue(RawQualificationStaging.sustainedRateReasons(full30, 1.0, cliff(500_000_000L)).isEmpty())
        // A compressed twin is judged at its conservative ratio: 468 / 1.4 = 334 MB/s fits 400 MB/s.
        assertTrue(RawQualificationStaging.sustainedRateReasons(full30.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK), 1.4,
            cliff(400_000_000L)).isEmpty())
        // No cliff, or an unknown sustained rate, adds nothing.
        assertTrue(RawQualificationStaging.sustainedRateReasons(full30, 1.0, cliff(400_000_000L, detected = false)).isEmpty())
        assertTrue(RawQualificationStaging.sustainedRateReasons(full30, 1.0, cliff(null)).isEmpty())
        assertTrue(RawQualificationStaging.sustainedRateReasons(full30, 1.0, null).isEmpty())

        val risky = RawQualificationStaging.withSustainedRate(pass, full30, 1.0, cliff(400_000_000L))
        assertEquals(RawBenchmarkStatus.FAIL, risky.status)
        assertEquals(listOf(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED), risky.reasonCodes)
        assertEquals(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED, risky.reasonCode)
        assertTrue(RawQualificationRisk.isStorageOnly(risky.reasonCodes))
        assertEquals(pass, RawQualificationStaging.withSustainedRate(pass, full30, 1.0, cliff(500_000_000L)))
        // A camera failure stays a camera failure, the storage reason added beside it.
        val failed = pass.copy(status = RawBenchmarkStatus.FAIL, reasonCode = "fps-not-reached", reasonCodes = listOf("fps-not-reached"))
        val both = RawQualificationStaging.withSustainedRate(failed, full30, 1.0, cliff(400_000_000L))
        assertEquals(listOf("fps-not-reached", RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED), both.reasonCodes)
        assertFalse(RawQualificationRisk.isStorageOnly(both.reasonCodes))
    }

    // endregion

    // region a long run that lost frames only while the storage fell behind

    /** The Razr's full@60 long run past the SLC cliff: fps 54.52 with a ring overflow. */
    private val razrLongRun = listOf(
        RawThroughputBenchmark.REASON_SWEEP_MEDIAN_MARGIN_FAIL,
        RawThroughputBenchmark.REASON_BUFFER_BACKLOG_EXCEEDED,
        RawThroughputBenchmark.REASON_RAW_FRAME_LOSS,
        RawThroughputBenchmark.REASON_RING_OVERFLOW,
        RawThroughputBenchmark.REASON_RING_PEAK_EXCEEDED,
        RawQualificationStaging.REASON_FPS_NOT_REACHED,
    )

    @Test fun aRingOverflowWithOnlyAFrameRateShortfallAsksForACameraCheck() {
        assertTrue(RawQualificationStaging.needsStorageLoadCheck(razrLongRun))
        // No overflow: the camera itself fell short.
        assertFalse(RawQualificationStaging.needsStorageLoadCheck(razrLongRun - RawThroughputBenchmark.REASON_RING_OVERFLOW))
        // No frame-rate shortfall: already the storage's alone.
        assertFalse(RawQualificationStaging.needsStorageLoadCheck(razrLongRun - RawQualificationStaging.REASON_FPS_NOT_REACHED))
        // Another camera or heat reason keeps it failed without a check.
        assertFalse(RawQualificationStaging.needsStorageLoadCheck(razrLongRun + RawQualificationStaging.REASON_THERMAL_LIMIT))
    }

    @Test fun aPassingCameraCheckMakesTheOverflowedRunRisky() {
        val result = RawStageResult.fail(razrLongRun)
        assertFalse(result.cameraOk)
        val reclassified = RawQualificationStaging.storageLoadResult(result, RawStageResult.PASS)
        assertEquals(RawBenchmarkStatus.FAIL, reclassified.status)
        assertFalse(RawQualificationStaging.REASON_FPS_NOT_REACHED in reclassified.reasonCodes)
        assertTrue(RawQualificationStaging.REASON_FPS_DROPPED_UNDER_STORAGE_LOAD in reclassified.reasonCodes)
        assertTrue(reclassified.storageOnlyFailure)
        assertTrue(reclassified.cameraOk)
        // The informational code is in no failure set, and alone it is not a storage failure either.
        assertFalse(RawQualificationStaging.REASON_FPS_DROPPED_UNDER_STORAGE_LOAD in RawQualificationRisk.STORAGE_REASONS)
        assertFalse(RawQualificationRisk.isStorageOnly(listOf(RawQualificationStaging.REASON_FPS_DROPPED_UNDER_STORAGE_LOAD)))
        // A failing or missing camera check keeps the camera failure.
        assertEquals(result, RawQualificationStaging.storageLoadResult(result, RawStageResult.fail(listOf("fps-not-reached"))))
        assertEquals(result, RawQualificationStaging.storageLoadResult(result, null))
        assertEquals(result, RawQualificationStaging.storageLoadResult(result, RawStageResult.skipped("thermal-skip")))
        // A pass is left alone.
        assertEquals(RawStageResult.PASS, RawQualificationStaging.storageLoadResult(RawStageResult.PASS, RawStageResult.PASS))

        // The stored qualification follows: risky, so it gets its burst seconds downstream.
        val q = RawThroughputBenchmark.qualifyWithBuffer(config("full@60"), 1_061_208_000L,
            RawStorageSweep(RawVideoQualifier.DESTINATION_ID, List(60) { RawThroughputSample(1_000, 300_000_000, 0) }), null)
            .let { it.copy(status = RawBenchmarkStatus.FAIL, reasonCode = razrLongRun.first(), reasonCodes = razrLongRun) }
        val stored = requireNotNull(RawQualificationStaging.storageLoadQualification(q, RawStageResult.PASS))
        assertEquals(RawBenchmarkStatus.FAIL, stored.status)
        assertTrue(RawQualificationRisk.isRisky(stored))
        assertTrue(RawQualificationStaging.REASON_FPS_DROPPED_UNDER_STORAGE_LOAD in stored.reasonCodes)
        assertNull(RawQualificationStaging.storageLoadQualification(q, RawStageResult.fail(listOf("fps-not-reached"))))
        // A compressed twin judged from that risky run inherits the storage reasons, not the informational one.
        val longRuns = linkedMapOf(config("full@60") to reclassified, config("3840x2160@60") to storageFail)
        val twin = RawQualificationStaging.compressedVerdict(config("full@60"), reclassified,
            bench(3e9, 1.0, threads = 2, encoding = RawVideoConfig.ENCODING_BLOCK_PACK), longRuns)
        assertTrue(twin.risky)
        assertFalse(RawQualificationStaging.REASON_FPS_DROPPED_UNDER_STORAGE_LOAD in twin.reasonCodes)
    }

    // endregion

    // region reduced-rate pass

    private val reduced = RawQualificationStaging.REASON_FPS_REDUCED
    private val notReached = RawQualificationStaging.REASON_FPS_NOT_REACHED

    @Test fun aShortfallAtModerateHeatIsAReducedRatePassAndAtSevereItFails() {
        // Thermal status 2 (MODERATE): the shortfall turns informational, so the session passes.
        val moderate = RawQualificationStaging.reducedRateCodes(listOf(notReached), 51.9, 2, 0, 0)
        assertEquals(listOf(reduced), moderate)
        val passed = RawStageResult.of("PASS", moderate)
        assertTrue(passed.passed)
        assertTrue(passed.reducedRate)
        assertTrue(passed.cameraOk)
        assertTrue(RawQualificationRisk.isStorageOnly(listOf(reduced, RawThroughputBenchmark.REASON_RING_OVERFLOW)))
        assertFalse(RawQualificationRisk.isStorageOnly(listOf(reduced)))
        // Thermal status 3 (SEVERE): unchanged, a failure.
        val severe = listOf(notReached, RawQualificationStaging.REASON_THERMAL_LIMIT)
        assertEquals(severe, RawQualificationStaging.reducedRateCodes(severe, 51.9, 3, 0, 0))
        assertEquals(listOf(notReached), RawQualificationStaging.reducedRateCodes(listOf(notReached), 51.9, 3, 0, 0))
        assertFalse(RawStageResult.fail(severe).cameraOk)
    }

    @Test fun aCameraLimitNoFramesOrALostCaptureIsNeverAReducedRate() {
        val limit = listOf(RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT, notReached)
        assertEquals(limit, RawQualificationStaging.reducedRateCodes(limit, 30.0, 0, 0, 0))
        val none = listOf(notReached, RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES)
        assertEquals(none, RawQualificationStaging.reducedRateCodes(none, 0.0, 0, 0, 0))
        assertEquals(listOf(notReached), RawQualificationStaging.reducedRateCodes(listOf(notReached), 0.0, 0, 0, 0))
        assertEquals(listOf(notReached), RawQualificationStaging.reducedRateCodes(listOf(notReached), 51.9, 0, 1, 0))
        assertEquals(listOf(notReached), RawQualificationStaging.reducedRateCodes(listOf(notReached), 51.9, 0, 0, 2))
        // Another failure stays beside it: the session still fails, for that reason alone.
        val loss = RawQualificationStaging.reducedRateCodes(listOf(notReached, "camera-frame-loss"), 51.9, 1, 0, 0)
        assertEquals(listOf(reduced, "camera-frame-loss"), loss)
        assertFalse(RawStageResult.fail(loss).cameraOk)
        // Codes without a shortfall are left alone.
        assertEquals(listOf("writer-error"), RawQualificationStaging.reducedRateCodes(listOf("writer-error"), 60.0, 0, 0, 0))
    }

    @Test fun aReducedRateLongRunOrCheckJudgesItsConfigurationAPass() {
        val passed = RawStageResult.of("PASS", listOf(reduced))
        val longRuns = linkedMapOf(config("full@60") to passed)
        val own = RawQualificationStaging.uncompressedVerdict(config("full@60"), longRuns, null)
        assertTrue(own.passed)
        // It covers lighter configurations' storage like any pass, and a reduced camera check passes its own.
        val lighter = RawQualificationStaging.uncompressedVerdict(config("3840x2160@60"), longRuns, passed)
        assertTrue(lighter.passed)
        assertEquals(config("full@60"), lighter.coveredBy)
        // Its compressed twin's camera evidence is fine.
        val twin = RawQualificationStaging.compressedVerdict(config("full@60"), passed, bench(3e9, 2.0, threads = 2), longRuns)
        assertTrue(twin.passed)
    }

    @Test fun aReducedRateCheckMakesAnOverflowedRunRiskyAtTheReducedRate() {
        val codes = RawQualificationStaging.storageLoadCodes(razrLongRun, RawStageResult.of("PASS", listOf(reduced)))!!
        assertTrue(reduced in codes)
        assertFalse(notReached in codes)
        assertFalse(RawQualificationStaging.REASON_FPS_DROPPED_UNDER_STORAGE_LOAD in codes)
        assertTrue(RawQualificationRisk.isStorageOnly(codes))
    }

    @Test fun aStoredReducedRatePassStaysAPassAndARiskyOneStaysRisky() {
        val sweep = RawStorageSweep(RawVideoQualifier.DESTINATION_ID, List(60) { RawThroughputSample(1_000, 1_400_000_000, 0) })
        val v2 = RawThroughputBenchmark.qualifyWithBuffer(config("full@30"), 1_061_208_000L, sweep, null)
            .let { it.copy(status = RawBenchmarkStatus.PASS, reasonCode = RawThroughputBenchmark.REASON_PASS, reasonCodes = listOf(RawThroughputBenchmark.REASON_PASS)) }
        assertEquals(v2, RawQualificationRecords.storedQualification(v2, listOf(reduced)))
        val failed = RawQualificationRecords.storedQualification(v2, listOf(reduced, "writer-error"))
        assertEquals(RawBenchmarkStatus.FAIL, failed.status)
        val storage = v2.copy(status = RawBenchmarkStatus.FAIL, reasonCode = RawThroughputBenchmark.REASON_RING_OVERFLOW,
            reasonCodes = listOf(RawThroughputBenchmark.REASON_RING_OVERFLOW))
        val risky = RawQualificationRecords.storedQualification(storage, listOf(reduced))
        assertTrue(RawQualificationRisk.isRisky(risky))
        assertTrue(reduced in risky.reasonCodes)
    }

    @Test fun burstAndSustainedMathsUseTheAchievedRate() {
        val full60 = config("full@60")
        // 15.6 MB per frame: 936 MB/s at 60 fps, 810 MB/s at 51.9 fps; 59.9 fps is within the tolerance and keeps the target.
        assertEquals(full60.streamBytesPerSecond, RawQualificationFrameRate.effectiveStreamBytesPerSecond(full60, null))
        assertEquals(full60.streamBytesPerSecond, RawQualificationFrameRate.effectiveStreamBytesPerSecond(full60, 59.9))
        val achieved = RawQualificationFrameRate.effectiveStreamBytesPerSecond(full60, 51.9)
        assertEquals(kotlin.math.ceil(full60.bytesPerFrame * 51.9).toLong(), achieved)
        // A sustained rate between the two: risky at the target, fine at the achieved rate.
        val between = cliff((achieved + full60.streamBytesPerSecond) / 2)
        assertEquals(listOf(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED), RawQualificationStaging.sustainedRateReasons(full60, 1.0, between))
        assertTrue(RawQualificationStaging.sustainedRateReasons(full60, 1.0, between, achievedFps = 51.9).isEmpty())
        // The burst estimate of a risky record follows the achieved stream.
        val q = RawThroughputBenchmark.qualifyWithBuffer(full60, 1_061_208_000L,
            RawStorageSweep(RawVideoQualifier.DESTINATION_ID, List(60) { RawThroughputSample(1_000, 300_000_000, 0) }), null)
            .let { it.copy(status = RawBenchmarkStatus.FAIL, reasonCode = RawThroughputBenchmark.REASON_RING_OVERFLOW,
                reasonCodes = listOf(RawThroughputBenchmark.REASON_RING_OVERFLOW, reduced)) }
        val record = RawQualificationRecords.forRun("fp", RawVideoQualifier.DESTINATION_ID, full60, 1_061_208_000L, q, emptyList(), 0L,
            achievedFps = 51.9)
        val slow = cliff(233_000_000L)
        val atAchieved = RawQualificationRecords.withBurstSeconds(record, slow).burstSeconds!!
        val atTarget = RawQualificationRecords.withBurstSeconds(record.copy(achievedFps = null), slow).burstSeconds!!
        assertEquals(com.librestatic.opencinecam.camera.RawStorageCliff.burstSeconds(slow, achieved, 1_061_208_000L), atAchieved)
        assertTrue(atAchieved > atTarget)
    }

    @Test fun reducedRateRecordsReadFromTheirRateOrTheirCodes() {
        assertTrue(RawQualificationFrameRate.isReducedRate(51.9, 60))
        assertFalse(RawQualificationFrameRate.isReducedRate(57.0, 60))
        assertFalse(RawQualificationFrameRate.isReducedRate(null, 60))
        assertEquals(51.9, RawQualificationFrameRate.roundFps(51.94), 0.0)
        assertEquals(52.0, RawQualificationFrameRate.roundFps(51.96), 0.0)
        // A benchmark of a reduced-rate configuration asks the camera to hold the reduced rate.
        assertTrue(RawQualificationStaging.benchCameraHeld(51.5, 51.9, 100, 100))
        assertFalse(RawQualificationStaging.benchCameraHeld(51.5, 60, 100, 100))
    }

    // endregion

    // region compressed twins with a long run of their own, and one sustained rule

    private fun bp(spec: String) = config(spec).copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)

    @Test fun twinLongRunsGoToTheHeaviestTwinsWhoseCameraAndEncoderKeepUp() {
        val twins = listOf("1920x1080@30", "full@60", "full@30", "3840x2160@60", "full@24").map(::bp)
        val fast = bench(1e10, 1.6, encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val camera = mapOf(
            config("full@60") to RawStageResult.fail(listOf(RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT)),
            config("3840x2160@60") to RawStageResult.pass(listOf(RawQualificationStaging.REASON_FPS_REDUCED)),
            config("full@30") to storageFail,
            config("full@24") to RawStageResult.PASS,
            config("1920x1080@30") to RawStageResult.PASS,
        )
        val order = RawQualificationStaging.compressedLongRunOrder(twins, camera::get) { fast }
        // The camera-limited full@60 has no run; a reduced 3840x2160@60 and a storage-risky full@30 do. Capped at three.
        assertEquals(listOf("3840x2160@60", "full@30", "full@24").map(::bp), order)
        assertEquals(RawQualificationStaging.MAX_COMPRESSED_LONG_RUNS, order.size)
        // An encoder too slow, heated or starving the camera keeps a twin out.
        val slow = bench(1e6, 1.6, encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        assertTrue(RawQualificationStaging.compressedLongRunOrder(twins, camera::get) { slow }.isEmpty())
        val hot = bench(1e10, 1.6, reasons = listOf(RawQualificationStaging.REASON_THERMAL_LIMIT), encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        assertTrue(RawQualificationStaging.compressedLongRunOrder(twins, camera::get) { hot }.isEmpty())
        assertTrue(RawQualificationStaging.compressedLongRunOrder(twins, camera::get) { null }.isEmpty())
        // Uncompressed configurations never get one.
        assertTrue(RawQualificationStaging.compressedLongRunOrder(listOf(config("full@24")), camera::get) { fast }.isEmpty())
    }

    @Test fun aTwinLongRunIsJudgedFromItsOwnEvidence() {
        val pass = RawQualificationStaging.compressedLongRunVerdict(RawStageResult.PASS)!!
        assertEquals(RawBenchmarkStatus.PASS, pass.status)
        assertEquals(RawQualificationMethods.COMPRESSED_LONG_RUN, pass.method)
        assertNull(pass.coveredBy)
        val risky = RawQualificationStaging.compressedLongRunVerdict(RawStageResult.fail(listOf(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED)))!!
        assertTrue(risky.risky)
        val failed = RawQualificationStaging.compressedLongRunVerdict(RawStageResult.fail(listOf("writer-error")))!!
        assertEquals(RawBenchmarkStatus.FAIL, failed.status)
        assertFalse(failed.cameraOk)
        // A skipped run (no space, no codec) and no run fall back to the extrapolation.
        assertNull(RawQualificationStaging.compressedLongRunVerdict(RawStageResult.skipped("insufficient-space")))
        assertNull(RawQualificationStaging.compressedLongRunVerdict(null))
    }

    @Test fun aTwinLongRunTheHeatAloneFailedIsHeatOnly() {
        val thermal = RawQualificationStaging.REASON_THERMAL_LIMIT
        // The Razr's full@30+bp: its own run, the last and hottest, failed with thermal-limit alone.
        assertTrue(RawQualificationStaging.compressedHeatOnly(RawStageResult.fail(listOf(thermal))))
        assertTrue(RawQualificationStaging.compressedHeatOnly(RawStageResult.fail(listOf(thermal, RawQualificationStaging.REASON_FPS_REDUCED))))
        assertTrue(RawQualificationStaging.compressedHeatOnly(
            RawStageResult.fail(listOf(thermal, RawQualificationStaging.REASON_FPS_DROPPED_UNDER_STORAGE_LOAD))))
        // Heat with frame loss, an overflow, a camera shortfall, the storage or the writer stays the run's failure.
        for (other in listOf(RawThroughputBenchmark.REASON_RAW_FRAME_LOSS, RawThroughputBenchmark.REASON_RING_OVERFLOW,
            RawQualificationStaging.REASON_FPS_NOT_REACHED, RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED, "writer-error")) {
            val result = RawStageResult.fail(listOf(thermal, other))
            assertFalse(other, RawQualificationStaging.compressedHeatOnly(result))
            assertEquals(other, RawQualificationMethods.COMPRESSED_LONG_RUN, RawQualificationStaging.compressedLongRunVerdict(result)?.method)
        }
        // No heat, a pass, a skip (thermal-skip included: UNKNOWN already falls back) or no run is not heat-only.
        assertFalse(RawQualificationStaging.compressedHeatOnly(RawStageResult.fail(listOf("writer-error"))))
        assertFalse(RawQualificationStaging.compressedHeatOnly(RawStageResult.PASS))
        assertFalse(RawQualificationStaging.compressedHeatOnly(RawStageResult.skipped("thermal-skip")))
        assertFalse(RawQualificationStaging.compressedHeatOnly(null))
        // A heat-only run gives no verdict of its own: the extrapolation judges it.
        assertNull(RawQualificationStaging.compressedLongRunVerdict(RawStageResult.fail(listOf(thermal))))
        // The fallback code is informational, never a failing or storage reason.
        assertFalse(RawQualificationStaging.REASON_COMPRESSED_HEAT_FALLBACK in RawQualificationRisk.STORAGE_REASONS)
    }

    @Test fun theExtrapolationReplacesATwinLongRunTheHeatAloneFailed() {
        val thermal = RawStageResult.fail(listOf(RawQualificationStaging.REASON_THERMAL_LIMIT))
        val twin = config("full@30")
        val longRuns = descend(emptyMap())
        val fast = bench(1e10, 1.6, encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        // The twin's cool check delivered and the bench keeps up: the extrapolation passes it, covered by a long run.
        val passed = RawQualificationStaging.compressedFinalVerdict(thermal, twin, RawStageResult.PASS, fast, longRuns)
        assertEquals(RawBenchmarkStatus.PASS, passed.status)
        assertEquals(RawQualificationMethods.COMPRESSED_EXTRAPOLATED, passed.method)
        assertEquals(config("full@60"), passed.coveredBy)
        assertTrue(passed.reasonCodes.isEmpty())
        assertEquals(RawQualificationStaging.compressedVerdict(twin, RawStageResult.PASS, fast, longRuns), passed)
        // No extra pass rule: a bench too slow for the twin still fails it.
        val slow = bench(100e6, 1.6, encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val tooSlow = RawQualificationStaging.compressedFinalVerdict(thermal, twin, RawStageResult.PASS, slow, longRuns)
        assertEquals(RawBenchmarkStatus.FAIL, tooSlow.status)
        assertEquals(RawQualificationMethods.COMPRESSED_EXTRAPOLATED, tooSlow.method)
        assertTrue(RawQualificationStaging.REASON_COMPRESSED_ENCODE_TOO_SLOW in tooSlow.reasonCodes)
        // Nor a missing bench, or no long run to cover it.
        assertEquals(RawBenchmarkStatus.UNKNOWN, RawQualificationStaging.compressedFinalVerdict(thermal, twin, RawStageResult.PASS, null, longRuns).status)
        val uncovered = RawQualificationStaging.compressedFinalVerdict(thermal, twin, RawStageResult.PASS, fast, emptyMap())
        assertEquals(RawBenchmarkStatus.UNKNOWN, uncovered.status)
        assertEquals(listOf(RawQualificationStaging.REASON_STORAGE_NOT_COVERED), uncovered.reasonCodes)
        // Heat with frame loss keeps the run's own failure.
        val lossy = RawStageResult.fail(listOf(RawQualificationStaging.REASON_THERMAL_LIMIT, RawThroughputBenchmark.REASON_RAW_FRAME_LOSS))
        val own = RawQualificationStaging.compressedFinalVerdict(lossy, twin, RawStageResult.PASS, fast, longRuns)
        assertEquals(RawBenchmarkStatus.FAIL, own.status)
        assertEquals(RawQualificationMethods.COMPRESSED_LONG_RUN, own.method)
        assertEquals(lossy.reasonCodes, own.reasonCodes)
        // A run that judged it, passing or not, keeps its verdict.
        assertEquals(RawQualificationMethods.COMPRESSED_LONG_RUN,
            RawQualificationStaging.compressedFinalVerdict(RawStageResult.PASS, twin, RawStageResult.PASS, slow, longRuns).method)
    }

    @Test fun aRunOnlyTheHeatAndTheStorageFailedStillDeliveredTheCamera() {
        val thermal = RawQualificationStaging.REASON_THERMAL_LIMIT
        val sustained = RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED
        // The Razr's full@30 and 3840x2160@60: heat with storage reasons, or heat alone.
        val hotStorage = RawStageResult.fail(listOf(thermal, sustained))
        val hotOverflow = RawStageResult.fail(listOf(RawThroughputBenchmark.REASON_RAW_FRAME_LOSS, RawThroughputBenchmark.REASON_RING_OVERFLOW,
            RawThroughputBenchmark.REASON_RING_PEAK_EXCEEDED, thermal, sustained))
        val hotOnly = RawStageResult.fail(listOf(thermal))
        for (result in listOf(hotStorage, hotOverflow, hotOnly)) {
            assertFalse(result.toString(), result.cameraOk)
            assertTrue(result.toString(), result.cameraDelivered)
        }
        assertTrue(RawStageResult.fail(listOf(thermal, RawQualificationStaging.REASON_FPS_REDUCED)).cameraDelivered)
        // Any camera reason beside the heat, or no heat at all, still says the camera did not deliver.
        assertFalse(RawStageResult.fail(listOf(thermal, RawQualificationStaging.REASON_FPS_NOT_REACHED)).cameraDelivered)
        assertFalse(RawStageResult.fail(listOf(thermal, RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES)).cameraDelivered)
        assertFalse(RawStageResult.fail(listOf(thermal, "writer-error")).cameraDelivered)
        assertFalse(cameraFail.cameraDelivered)
        assertFalse(RawStageResult.skipped(thermal).cameraDelivered)
        assertTrue(storageFail.cameraDelivered)
        assertTrue(RawStageResult.PASS.cameraDelivered)
        // The uncompressed verdict stays the thermal failure.
        val verdict = RawQualificationStaging.uncompressedVerdict(config("full@30"), mapOf(config("full@30") to hotStorage), null)
        assertEquals(RawBenchmarkStatus.FAIL, verdict.status)
        assertFalse(verdict.risky)
    }

    @Test fun twinsOfRunsTheHeatFailedStillGetALongRun() {
        val thermal = RawQualificationStaging.REASON_THERMAL_LIMIT
        val twins = listOf("full@30", "full@24", "3840x2160@30").map(::bp)
        val fast = bench(1e10, 1.6, encoding = RawVideoConfig.ENCODING_BLOCK_PACK)
        val camera = mapOf(
            config("full@30") to RawStageResult.fail(listOf(thermal, RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED)),
            config("full@24") to RawStageResult.fail(listOf(thermal, RawQualificationStaging.REASON_FPS_NOT_REACHED)),
            config("3840x2160@30") to RawStageResult.fail(listOf(thermal)),
        )
        // full@24's camera fell short of its rate under the heat: no run; the other two do.
        assertEquals(listOf("full@30", "3840x2160@30").map(::bp), RawQualificationStaging.compressedLongRunOrder(twins, camera::get) { fast })
        // The twin's own cool long run decides: it passes, nothing of the twin's thermal-limit reaches it.
        val own = RawQualificationStaging.compressedLongRunVerdict(RawStageResult.PASS)!!
        assertEquals(RawBenchmarkStatus.PASS, own.status)
        assertTrue(own.reasonCodes.isEmpty())
        // A storage-load shortfall is weighed against a camera that held its rate under the heat, without the heat.
        val check = RawQualificationStaging.twinCameraCheck(camera[config("full@30")], twinReduced = false)
        assertEquals(RawStageResult.PASS, check)
        val codes = listOf(RawThroughputBenchmark.REASON_RING_OVERFLOW, RawQualificationStaging.REASON_FPS_NOT_REACHED)
        assertEquals(listOf(RawThroughputBenchmark.REASON_RING_OVERFLOW, RawQualificationStaging.REASON_FPS_DROPPED_UNDER_STORAGE_LOAD),
            RawQualificationStaging.storageLoadResult(RawStageResult.fail(codes), check).reasonCodes)
        assertNull(RawQualificationStaging.storageLoadCodes(codes, RawQualificationStaging.twinCameraCheck(camera[config("full@24")], twinReduced = false)))
    }

    @Test fun aTwinLongRunsShortfallUnderOverflowIsWeighedAgainstItsTwinsCamera() {
        val codes = listOf(RawThroughputBenchmark.REASON_RING_OVERFLOW, RawQualificationStaging.REASON_FPS_NOT_REACHED)
        val full = RawQualificationStaging.twinCameraCheck(RawStageResult.PASS, twinReduced = false)
        assertEquals(RawStageResult.PASS, full)
        assertEquals(listOf(RawThroughputBenchmark.REASON_RING_OVERFLOW, RawQualificationStaging.REASON_FPS_DROPPED_UNDER_STORAGE_LOAD),
            RawQualificationStaging.storageLoadCodes(codes, full))
        // A twin risky for the storage alone still clears the camera; a reduced twin makes the shortfall a reduced rate.
        assertEquals(RawStageResult.PASS, RawQualificationStaging.twinCameraCheck(storageFail, twinReduced = false))
        val reducedCheck = RawQualificationStaging.twinCameraCheck(RawStageResult.PASS, twinReduced = true)!!
        assertTrue(reducedCheck.passed && reducedCheck.reducedRate)
        assertEquals(listOf(RawThroughputBenchmark.REASON_RING_OVERFLOW, RawQualificationStaging.REASON_FPS_REDUCED),
            RawQualificationStaging.storageLoadCodes(codes, reducedCheck))
        // A twin the camera failed keeps the shortfall a failure; none leaves it alone.
        assertNull(RawQualificationStaging.storageLoadCodes(codes, RawQualificationStaging.twinCameraCheck(cameraFail, twinReduced = false)))
        assertNull(RawQualificationStaging.twinCameraCheck(null, twinReduced = false))
    }

    @Test fun theSustainedRuleJudgesEveryVerdictAtItsStorageRate() {
        val full24 = config("full@24")
        val razr = cliff(233_000_000L)
        // Raw full@24 streams about 375 MB/s, over the Razr's 233 MB/s past the cache: risky, from its long run or extrapolated alike.
        assertTrue(full24.streamBytesPerSecond in 360_000_000L..380_000_000L)
        assertEquals(listOf(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED),
            RawQualificationStaging.sustainedRateReasons(full24.streamBytesPerSecond, razr))
        assertEquals(listOf(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED), RawQualificationStaging.sustainedRateReasons(full24, 1.0, razr))
        val q = RawThroughputBenchmark.qualifyWithBuffer(full24, 1_061_208_000L,
            RawStorageSweep(RawVideoQualifier.DESTINATION_ID, List(60) { RawThroughputSample(1_000, 1_200_000_000, 0) }), null)
            .copy(status = RawBenchmarkStatus.PASS, reasonCode = RawThroughputBenchmark.REASON_PASS, reasonCodes = listOf(RawThroughputBenchmark.REASON_PASS))
        val judged = RawQualificationStaging.withSustainedRate(q, full24, 1.0, razr)
        assertTrue(RawQualificationRisk.isRisky(judged))
        val record = RawQualificationRecords.withBurstSeconds(
            RawQualificationRecords.forRun("fp", RawVideoQualifier.DESTINATION_ID, full24, 1_061_208_000L, judged, emptyList(), 0L), razr)
        assertEquals(com.librestatic.opencinecam.camera.RawStorageCliff.burstSeconds(razr, full24.streamBytesPerSecond, 1_061_208_000L), record.burstSeconds)
        // A compressed run is judged at the bytes per second it stored: 1.7x smaller is under the sustained rate.
        val stored = kotlin.math.ceil(full24.streamBytesPerSecond / 1.7).toLong()
        assertTrue(RawQualificationStaging.sustainedRateReasons(stored, razr).isEmpty())
        assertEquals(1.7, RawQualificationStaging.measuredRatio(full24.streamBytesPerSecond, stored), 1e-3)
        // Without a measurement, or one above the stream, the ratio is 1.
        assertEquals(1.0, RawQualificationStaging.measuredRatio(full24.streamBytesPerSecond, null), 0.0)
        assertEquals(1.0, RawQualificationStaging.measuredRatio(1_000L, 2_000L), 0.0)
        // Without a cliff, or a sustained rate, no rule.
        assertTrue(RawQualificationStaging.sustainedRateReasons(full24.streamBytesPerSecond, cliff(233_000_000L, detected = false)).isEmpty())
        assertTrue(RawQualificationStaging.sustainedRateReasons(full24.streamBytesPerSecond, cliff(null)).isEmpty())
    }

    @Test fun aLongRunRiskyOnlyPastTheCacheStillStopsTheDescent() {
        val sustained = RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED
        val onlySustained = RawStageResult.fail(listOf(sustained))
        val held = RawQualificationStaging.withoutSustainedRate(onlySustained)
        assertEquals(RawStageResult.PASS, held)
        assertFalse(RawQualificationStaging.continueDescent(held))
        assertEquals(config("full@24"), RawQualificationStaging.coveringRun(config("1920x1080@30").streamBytesPerSecond, mapOf(config("full@24") to held)))
        // A reduced rate stays with it; another storage failure keeps the run failed without the sustained code.
        assertEquals(RawStageResult.pass(listOf(reduced)), RawQualificationStaging.withoutSustainedRate(RawStageResult.fail(listOf(sustained, reduced))))
        assertEquals(storageFail, RawQualificationStaging.withoutSustainedRate(RawStageResult.fail(storageFail.reasonCodes + sustained)))
        assertEquals(cameraFail, RawQualificationStaging.withoutSustainedRate(cameraFail))
    }

    @Test fun aSustainedRateFromTheLongRunsJudgesWhatTheSweepCouldNot() {
        val full24 = config("full@24")
        val ring = 1_061_208_000L
        val capped = com.librestatic.opencinecam.camera.RawStorageCliff.detect(List(91) { RawThroughputSample(1_000, 1_050_000_000L, 0) }, 60)
        val derived = com.librestatic.opencinecam.camera.RawStorageCliff.withLongRunFallback(capped,
            listOf(com.librestatic.opencinecam.camera.RawWriteEvidence("3840x2160@60", 233_000_000L, fellBehind = true)))
        val pass = RawThroughputBenchmark.qualifyWithBuffer(full24, ring,
            RawStorageSweep(RawVideoQualifier.DESTINATION_ID, List(60) { RawThroughputSample(1_000, 1_050_000_000, 0) }), null)
            .copy(status = RawBenchmarkStatus.PASS, reasonCode = RawThroughputBenchmark.REASON_PASS, reasonCodes = listOf(RawThroughputBenchmark.REASON_PASS))
        val record = RawQualificationRecords.forRun("fp", RawVideoQualifier.DESTINATION_ID, full24, ring, pass, emptyList(), 0L)
        // The sweep alone: no rule. With the long runs' 233 MB/s, raw full@24 (about 375 MB/s) is risky, with a burst.
        assertTrue(RawQualificationStaging.sustainedRateReasons(full24.streamBytesPerSecond, capped).isEmpty())
        val rejudged = RawQualificationStaging.rejudgeWithLongRunSustained(record, full24.streamBytesPerSecond, 1.0, derived)
        assertTrue(RawQualificationRisk.isRisky(rejudged.qualification))
        assertEquals(listOf(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED), rejudged.qualification.reasonCodes)
        assertTrue(rejudged.burstSeconds != null && rejudged.burstSeconds!! > 0)
        // A configuration under the derived rate stays ready.
        val light = config("1920x1080@30")
        val lightRecord = record.copy(config = light)
        assertEquals(lightRecord, RawQualificationStaging.rejudgeWithLongRunSustained(lightRecord, light.streamBytesPerSecond, 1.0, derived))
        // A real cliff's sustained write never re-judges: those verdicts already used it.
        assertEquals(record, RawQualificationStaging.rejudgeWithLongRunSustained(record, full24.streamBytesPerSecond, 1.0, cliff(233_000_000L)))
        assertEquals(record, RawQualificationStaging.rejudgeWithLongRunSustained(record, full24.streamBytesPerSecond, 1.0, capped))
    }

    // endregion

    @Test fun stageResultsFromReportRows() {
        assertEquals(RawStageResult.PASS, RawStageResult.of("PASS", listOf(RawThroughputBenchmark.REASON_PASS)))
        assertEquals(listOf("fps-not-reached"), RawStageResult.of("FAIL", listOf("fps-not-reached")).reasonCodes)
        assertEquals(RawBenchmarkStatus.UNKNOWN, RawStageResult.of("UNKNOWN", listOf("insufficient-space")).status)
        assertTrue(RawStageResult.of("FAIL", listOf(RawThroughputBenchmark.REASON_BUFFER_BACKLOG_EXCEEDED)).storageOnlyFailure)
        assertFalse(RawStageResult.of("FAIL", listOf(RawThroughputBenchmark.REASON_BUFFER_BACKLOG_EXCEEDED, "fps-not-reached")).storageOnlyFailure)
    }
}
