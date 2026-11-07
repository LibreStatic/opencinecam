/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.RawQualificationItemStatus
import com.librestatic.opencinecam.camera.RawBenchmarkStatus
import com.librestatic.opencinecam.camera.RawLiveRun
import com.librestatic.opencinecam.camera.RawStorageSweep
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawThroughputSample
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.rawQualificationRunStatus
import com.librestatic.opencinecam.service.rawVideoGate
import com.librestatic.opencinecam.service.rawVideoUsableConfigs
import com.librestatic.opencinecam.storage.RawVideoQualificationStore
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RawVideoQualificationPlanTest {
    private val sensorW = 4080
    private val sensorH = 3060
    private fun parse(spec: String, w: Int = sensorW, h: Int = sensorH) = RawQualificationSpecs.parse(spec, "0", w, h)
    private fun runs(specs: List<String>, w: Int = sensorW, h: Int = sensorH) = specs.mapNotNull { parse(it, w, h) }
    private fun config(spec: String) = requireNotNull(parse(spec)).config

    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("raw-qualifier").toFile() }

    @After fun tearDown() { dir.deleteRecursively() }

    // region specs

    @Test fun fullTakesTheWholeRaw10Output() {
        val run = requireNotNull(parse("full@30"))
        assertEquals(RawVideoConfig("0", 4080, 3060, 4080, 3060, 30), run.config)
        assertEquals(0 to 0, run.cropLeft to run.cropTop)
        assertTrue(run.isFull)
        assertEquals("0,0,4080x3060", run.cropLabel)
    }

    @Test fun cropsAreCentredOnRaw10AndCfaBoundaries() {
        val uhd = requireNotNull(parse("3840x2160@60"))
        assertEquals(120 to 450, uhd.cropLeft to uhd.cropTop)
        val odd = requireNotNull(parse("1924x1081@30", 4000, 3000))
        assertEquals(1036, odd.cropLeft) // (4000-1924)/2 = 1038, down to a multiple of 4
        assertEquals(958, odd.cropTop) // (3000-1081)/2 = 959, down to even
        assertTrue(!uhd.isFull)
        assertEquals(RawVideoCrop.centred(uhd.config), RawVideoCrop(uhd.cropLeft, uhd.cropTop, 3840, 2160))
    }

    @Test fun lj92SuffixSelectsTheCompressedEncoding() {
        val run = requireNotNull(parse("full@30+LJ92"))
        assertEquals(RawVideoConfig.ENCODING_LJ92, run.config.encoding)
        assertEquals("full@30+LJ92", run.spec)
        assertEquals(RawVideoConfig.ENCODING_RAW10, config("full@30").encoding)
        assertTrue(RawQualificationSpecs.isLj92("full@30+lj92"))
        assertEquals("full@30+lj92", RawQualificationSpecs.compressedTwin("full@30", RawVideoConfig.ENCODING_LJ92))
    }

    @Test fun blockPackSuffixSelectsTheBlockPackEncoding() {
        val run = requireNotNull(parse("1920x1080@60+BP"))
        assertEquals(RawVideoConfig.ENCODING_BLOCK_PACK, run.config.encoding)
        assertEquals(config("1920x1080@60").copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK), run.config)
        assertEquals(RawVideoConfig.ENCODING_BLOCK_PACK, RawQualificationSpecs.encodingOf("full@30+bp"))
        assertEquals(RawVideoConfig.ENCODING_RAW10, RawQualificationSpecs.encodingOf("full@30"))
        assertTrue(RawQualificationSpecs.isCompressed("full@30+bp"))
        assertFalse(RawQualificationSpecs.isLj92("full@30+bp"))
        assertEquals("full@30+bp", RawQualificationSpecs.compressedTwin("full@30"))
        assertEquals("full@30+bp", RawQualificationSpecs.compressedTwin("full@30+lj92"))
        assertEquals("full@30", RawQualificationSpecs.uncompressedTwin("full@30+bp"))
        assertNull(parse("full@30+deflate"))
    }

    @Test fun specsTheCameraCannotCarryAreRefused() {
        listOf("full", "full@0", "full@x", "1922x1080@30", "4096x2160@30", "3840x3072@30", "3840@30", "axb@30", "@30", "")
            .forEach { assertNull(it, parse(it)) }
        assertNull(RawQualificationSpecs.parse("full@30", " ", sensorW, sensorH))
    }

    @Test fun splitDropsBlanks() {
        assertEquals(listOf("full@30", "full@30+lj92"), RawQualificationSpecs.split(" full@30, ,full@30+lj92 ,"))
        assertNull(RawQualificationSpecs.split(null))
    }

    // endregion

    // region plan

    @Test fun defaultPlanSkipsWhatASmallerSensorCannotDo() {
        val plan = RawQualificationPlan.DEFAULT
        assertEquals(8, runs(plan.specs).size)
        assertEquals(listOf("full@15", "full@24", "full@30", "full@60", "1920x1080@30", "1920x1080@60"),
            runs(plan.specs, 1920, 1080).map { it.spec })
        assertTrue(plan.specs.none(RawQualificationSpecs::isCompressed))
    }

    @Test fun blockPackTwinsOfEveryUncompressedConfigurationOnlyWithTheCodec() {
        val plan = RawQualificationPlan.DEFAULT
        val initial = runs(plan.specs)
        assertEquals(initial, plan.uncompressedRuns(initial))
        val twins = plan.compressedRuns(initial, codecAvailable = true)
        assertEquals(initial.map { it.spec + "+bp" }, twins.map { it.spec })
        assertTrue(twins.all { it.config.encoding == RawVideoConfig.ENCODING_BLOCK_PACK })
        assertEquals(config("full@30").copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK), twins[2].config)
        assertEquals(120 to 450, twins[5].cropLeft to twins[5].cropTop)
        assertTrue(plan.compressedRuns(initial, codecAvailable = false).isEmpty())
        assertEquals(listOf(RawVideoConfig.ENCODING_BLOCK_PACK), plan.benchEncodings(initial, codecAvailable = true))
        assertEquals(1, plan.benchSessions(initial, codecAvailable = true))
        assertEquals(0, plan.benchSessions(initial, codecAvailable = false))
        assertEquals(3, plan.copy(encodeThreads = listOf(2, 4, 8)).benchSessions(initial, codecAvailable = true))
    }

    @Test fun explicitPlansJudgeExactlyTheirSpecs() {
        val plan = RawQualificationPlan.explicit(listOf("full@24", "full@30+lj92"))
        val initial = runs(plan.specs)
        // A `+lj92` spec brings its uncompressed twin to the long runs and the camera checks.
        assertEquals(listOf("full@24", "full@30"), plan.uncompressedRuns(initial).map { it.spec })
        assertEquals(config("full@30"), plan.uncompressedRuns(initial)[1].config)
        assertEquals(listOf("full@30+lj92"), plan.compressedRuns(initial, codecAvailable = true).map { it.spec })
        assertEquals(listOf(RawVideoConfig.ENCODING_LJ92), plan.benchEncodings(initial, codecAvailable = true))
        // Both codecs named: one benchmark each, block-pack first, each at its own production thread count.
        val both = RawQualificationPlan.explicit(listOf("full@30+lj92", "full@24+bp"))
        assertEquals(listOf(RawVideoConfig.ENCODING_BLOCK_PACK, RawVideoConfig.ENCODING_LJ92), both.benchEncodings(runs(both.specs), codecAvailable = true))
        assertEquals(listOf(2), both.threadsFor(RawVideoConfig.ENCODING_BLOCK_PACK))
        assertEquals(listOf(4), both.threadsFor(RawVideoConfig.ENCODING_LJ92))
        assertEquals(2, both.benchSessions(runs(both.specs), codecAvailable = true))
        // With twins, a twin the plan already lists is not repeated.
        val mixed = RawQualificationPlan(listOf("full@30", "full@30+bp", "full@24"), compressedTwins = true)
        assertEquals(listOf("full@30+bp", "full@24+bp"), mixed.compressedRuns(runs(mixed.specs), codecAvailable = true).map { it.spec })
        assertEquals("full@30", RawQualificationSpecs.uncompressedTwin("full@30+lj92"))
        assertEquals("full@30", RawQualificationSpecs.uncompressedTwin("full@30"))
    }

    @Test fun stagesParseAndALongRunBringsTheSweep() {
        assertEquals(RawQualificationStage.ALL, RawQualificationStage.parse("sweep, LONG,camera,encode"))
        // `lj92` is the older name of the encode stage.
        assertEquals(RawQualificationStage.ALL, RawQualificationStage.parse("sweep, LONG,camera,lj92"))
        assertEquals(setOf(RawQualificationStage.ENCODE), RawQualificationStage.parse("lj92"))
        assertNull(RawQualificationStage.parse("sweep,disk"))
        assertNull(RawQualificationStage.parse(null))
        val longOnly = RawQualificationPlan.DEFAULT.copy(stages = setOf(RawQualificationStage.LONG))
        assertEquals(setOf(RawQualificationStage.SWEEP, RawQualificationStage.LONG), longOnly.effectiveStages)
        assertFalse(longOnly.complete)
        assertTrue(RawQualificationPlan.DEFAULT.complete)
        assertTrue(RawQualificationPlan.DEFAULT.copy(stages = setOf(RawQualificationStage.LONG, RawQualificationStage.CAMERA, RawQualificationStage.ENCODE)).complete)
        val benchOnly = RawQualificationPlan.DEFAULT.copy(stages = setOf(RawQualificationStage.ENCODE))
        assertEquals(1, benchOnly.benchSessions(runs(benchOnly.specs), codecAvailable = true))
        assertEquals(0, longOnly.benchSessions(runs(longOnly.specs), codecAvailable = true))
    }

    @Test fun planSettingsAreBounded() {
        assertNull(RawQualificationPlan.DEFAULT.encodeThreads)
        assertEquals(listOf(RawFrameEncoders.BLOCK_PACK_THREADS), RawQualificationPlan.DEFAULT.threadsFor(RawVideoConfig.ENCODING_BLOCK_PACK))
        assertEquals(listOf(RawFrameEncoders.LJ92_THREADS), RawQualificationPlan.DEFAULT.threadsFor(RawVideoConfig.ENCODING_LJ92))
        assertEquals(listOf(3), RawQualificationPlan.DEFAULT.copy(encodeThreads = listOf(3)).threadsFor(RawVideoConfig.ENCODING_LJ92))
        assertEquals(RawFrameEncoders.DEFAULT_STRIPS, RawQualificationPlan.DEFAULT.encodeStrips)
        listOf(
            { RawQualificationPlan.DEFAULT.copy(encodeThreads = emptyList()) },
            { RawQualificationPlan.DEFAULT.copy(encodeThreads = listOf(0)) },
            { RawQualificationPlan.DEFAULT.copy(encodeThreads = listOf(65)) },
            { RawQualificationPlan.DEFAULT.copy(encodeStrips = 0) },
            { RawQualificationPlan.DEFAULT.copy(stages = emptySet()) },
        ).forEach { make -> assertTrue(runCatching(make).isFailure) }
    }

    // endregion

    // region timing and estimates

    @Test fun configsOfAtLeast500MBpsMeasureEightySeconds() {
        val timing = RawQualificationTiming()
        assertEquals(40, timing.liveSeconds(config("full@30"))) // 468 MB/s
        assertEquals(80, timing.liveSeconds(config("full@60"))) // 936 MB/s
        assertEquals(80, timing.liveSeconds(config("3840x2160@60"))) // 622 MB/s
        assertEquals(40, timing.liveSeconds(config("3840x2160@30"))) // 311 MB/s
        // Both stay above rule v2's 20 s minimum, which does not change.
        assertEquals(20_000L, RawThroughputBenchmark.LIVE_MIN_DURATION_MS)
        val exact = RawVideoConfig("0", 4000, 1000, 4000, 1000, 100) // 5,000,000 B/frame x 100
        assertEquals(500_000_000L, exact.streamBytesPerSecond)
        assertEquals(80, timing.liveSeconds(exact))
        assertEquals(40, timing.liveSeconds(exact.copy(fps = 99)))
        val custom = RawQualificationTiming(liveSeconds = 5, sustainedLiveSeconds = 7, sustainedBytesPerSecond = 300_000_000)
        assertEquals(7, custom.liveSeconds(config("3840x2160@30")))
        assertEquals(5, custom.liveSeconds(config("1920x1080@60")))
    }

    @Test fun neededBytesCoverTheRunPlusAReserve() {
        val timing = RawQualificationTiming()
        val full60 = config("full@60")
        assertEquals(936_360_000L * (80 + 3 + 2) + (1L shl 30), timing.neededBytes(full60))
        val full30 = config("full@30")
        assertEquals(468_180_000L * (40 + 3 + 2) + (1L shl 30), timing.neededBytes(full30))
    }

    @Test fun defaultPlanEstimateOnAnS24LikeSensor() {
        val withCodec = RawQualificationEstimates.of(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, codecAvailable = true)
        // Ring 2 s + 8 camera checks of 12.5 s + one 34.5 s benchmark + the 120 s storage rest + sweep 152 s (60 burst
        // windows, up to 90 s sustained, 2 s overhead) + 8 long runs (6 at 46.5 s, 2 at 86.5 s) + 16 cool-downs of 3 s.
        assertEquals(2_000L + 100_000L + 34_500L + 120_000L + 152_000L + 452_000L + 48_000L, withCodec.durationMs)
        assertEquals(17, withCodec.runCount)
        // The disk need is the sweep and the largest long run; the encode benchmark writes nothing.
        assertEquals(936_360_000L * 85 + (1L shl 30), withCodec.freeBytesNeeded)
        assertEquals(RawVideoQualifier.SWEEP_WRAP_BYTES + 15_606_000L, withCodec.sweepFreeBytesNeeded)
        val withoutCodec = RawQualificationEstimates.of(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, codecAvailable = false)
        assertEquals(2_000L + 100_000L + 120_000L + 152_000L + 452_000L + 45_000L, withoutCodec.durationMs)
        assertEquals(16, withoutCodec.runCount)
        assertEquals(withCodec.freeBytesNeeded, withoutCodec.freeBytesNeeded)
    }

    @Test fun partialStageEstimates() {
        val timing = RawQualificationTiming()
        assertEquals(10_500L + 2_000L, timing.cameraCheckMs())
        assertEquals(32_500L + 2_000L, timing.encodeBenchMs())
        // Camera checks only: no sweep, no disk, one check per configuration.
        val checks = RawQualificationEstimates.of(RawQualificationPlan.DEFAULT.copy(stages = setOf(RawQualificationStage.CAMERA)),
            "0", sensorW, sensorH, codecAvailable = true)
        assertEquals(2_000L + 8 * 12_500L + 7 * 3_000L, checks.durationMs)
        assertEquals(8, checks.runCount)
        assertEquals(0L, checks.freeBytesNeeded)
        // Three thread counts, the benchmark alone.
        val bench = RawQualificationEstimates.of(
            RawQualificationPlan.DEFAULT.copy(stages = setOf(RawQualificationStage.ENCODE), encodeThreads = listOf(2, 4, 8)),
            "0", sensorW, sensorH, codecAvailable = true)
        assertEquals(2_000L + 3 * 34_500L + 2 * 3_000L, bench.durationMs)
        assertEquals(3, bench.runCount)
        assertEquals(0L, bench.freeBytesNeeded)
    }

    @Test fun theStorageRestFollowsTheCameraSessionsBeforeTheSweep() {
        val timing = RawQualificationTiming()
        assertEquals(120_000L, RawQualificationTiming.STORAGE_REST_MS)
        val runs = runs(RawQualificationPlan.DEFAULT.specs).filter { !it.config.isCompressed }
        val all = RawQualificationEstimates.of(runs, 1, timing)
        val sweepAndLong = RawQualificationEstimates.of(runs, 0, timing, setOf(RawQualificationStage.SWEEP, RawQualificationStage.LONG))
        // Without camera sessions before it, the sweep starts at once: no rest.
        assertEquals(2_000L + 152_000L + runs.sumOf { timing.runMs(it.config) } + (runs.size - 1) * 3_000L, sweepAndLong.durationMs)
        // With them, the rest and their sessions and cool-downs come on top.
        assertEquals(sweepAndLong.durationMs + RawQualificationTiming.STORAGE_REST_MS + runs.size * (timing.cameraCheckMs() + 3_000L) +
            timing.encodeBenchMs() + 3_000L, all.durationMs)
        // Without a sweep (camera checks only), no rest either.
        val checks = RawQualificationEstimates.of(runs, 0, timing, setOf(RawQualificationStage.CAMERA))
        assertEquals(2_000L + runs.size * timing.cameraCheckMs() + (runs.size - 1) * 3_000L, checks.durationMs)
    }

    @Test fun theProgressMeterShrinksForStepsThatDoNotRunWithoutGoingBack() {
        val meter = RawQualificationProgressMeter(10_000L)
        meter.startStep(4_000L); meter.finishStep()
        assertEquals(0.4, meter.fraction(), 1e-9)
        meter.shrink(5_000L)
        assertEquals(0.8, meter.fraction(), 1e-9)
        // Never below the work done, and the fraction never goes back.
        meter.shrink(100_000L)
        assertEquals(4_000L, meter.totalMs)
        assertEquals(1.0, meter.fraction(), 1e-9)
        meter.extend(6_000L)
        assertEquals(1.0, meter.fraction(), 1e-9)
    }

    @Test fun freeSpaceEstimateKeepsEveryRunWhenThereIsRoom() {
        val full = RawQualificationEstimates.of(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, codecAvailable = true)
        val roomy = RawQualificationEstimates.forFreeSpace(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, codecAvailable = true,
            freeBytes = full.freeBytesNeeded)
        assertEquals(full, roomy.estimate)
        assertEquals(0, roomy.skippedRuns)
        assertTrue(roomy.enoughSpace)
    }

    @Test fun freeSpaceEstimateSkipsLongRunsThatDoNotFit() {
        val timing = RawQualificationTiming()
        val runs = runs(RawQualificationPlan.DEFAULT.specs)
        val largest = runs.maxOf { timing.neededBytes(it.config) }
        val partial = RawQualificationEstimates.forFreeSpace(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, codecAvailable = true,
            freeBytes = largest - 1)
        val skipped = runs.count { timing.neededBytes(it.config) > largest - 1 }
        assertTrue(skipped > 0)
        assertEquals(skipped, partial.skippedRuns)
        // The camera checks and the benchmark need no space, so only the skipped long runs leave the plan.
        assertEquals(17 - skipped, partial.estimate.runCount)
        val full = RawQualificationEstimates.of(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, codecAvailable = true)
        assertEquals(full.durationMs - runs.filter { timing.neededBytes(it.config) > largest - 1 }
            .sumOf { timing.runMs(it.config) + RawQualificationTiming.COOL_DOWN_MS }, partial.estimate.durationMs)
        assertTrue(partial.enoughSpace)
    }

    @Test fun freeSpaceEstimateListsEveryPlannedConfigWithTheFigureTheSkipUses() {
        val timing = RawQualificationTiming()
        val runs = RawQualificationStaging.longRunOrder(runs(RawQualificationPlan.DEFAULT.specs))
        assertEquals(listOf("full@60", "3840x2160@60", "full@30", "full@24", "3840x2160@30", "full@15", "1920x1080@60", "1920x1080@30"),
            runs.map { it.spec })
        val largest = runs.maxOf { timing.neededBytes(it.config) }
        val partial = RawQualificationEstimates.forFreeSpace(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, codecAvailable = true,
            freeBytes = largest - 1)
        assertEquals(runs.map { it.spec }, partial.configs.map { it.spec })
        partial.configs.forEachIndexed { index, planned ->
            assertEquals(runs[index].config, planned.config)
            assertEquals(timing.neededBytes(runs[index].config), planned.neededBytes)
            assertEquals(planned.neededBytes <= largest - 1, planned.fits)
        }
        assertEquals(partial.skippedRuns, partial.configs.count { !it.fits })
        // The free space is measured against the whole plan: the sweep and the largest long run.
        val full = RawQualificationEstimates.of(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, codecAvailable = true)
        assertEquals(full.freeBytesNeeded, partial.fullPlanFreeBytes)
        assertTrue(partial.freeBytes < partial.fullPlanFreeBytes)
    }

    @Test fun freeSpaceEstimateBelowTheSweepIsNotEnough() {
        val tiny = RawQualificationEstimates.forFreeSpace(RawQualificationPlan.DEFAULT, "0", sensorW, sensorH, codecAvailable = true,
            freeBytes = RawVideoQualifier.SWEEP_WRAP_BYTES / 2)
        assertTrue(!tiny.enoughSpace)
        assertTrue(tiny.minimumFreeBytes >= RawVideoQualifier.SWEEP_WRAP_BYTES)
        assertEquals(runs(RawQualificationPlan.DEFAULT.specs).size, tiny.skippedRuns)
    }

    @Test fun progressNeverGoesBackAndFollowsReplanning() {
        val meter = RawQualificationProgressMeter(100)
        meter.startStep(40)
        assertEquals(0.2, meter.fraction(20), 1e-9)
        assertEquals(0.4, meter.fraction(1_000), 1e-9) // capped at the step's plan
        meter.finishStep()
        assertEquals(0.4, meter.fraction(), 1e-9)
        meter.replan(10) // the follow-ups turned out shorter: 40 done + 10 left
        assertEquals(0.8, meter.fraction(), 1e-9)
        meter.replan(1_000) // longer again: the fraction holds instead of going back
        assertEquals(0.8, meter.fraction(), 1e-9)
        meter.startStep(1_000)
        meter.finishStep()
        assertEquals(1.0, meter.fraction(), 1e-9)
        assertEquals(1.0, meter.complete(), 0.0)
    }

    @Test fun previewSizeMirrorsTheEngineChoice() {
        val sizes = listOf(4080 to 3060, 3840 to 2160, 1920 to 1440, 1920 to 1080, 1280 to 720, 640 to 480)
        assertEquals(1920 to 1080, rawQualificationPreviewSize(sizes))
        assertEquals(1280 to 720, rawQualificationPreviewSize(listOf(4080 to 3060, 1280 to 720, 640 to 480)))
        assertEquals(3840 to 2160, rawQualificationPreviewSize(listOf(4080 to 3060, 3840 to 2160)))
        assertNull(rawQualificationPreviewSize(emptyList()))
    }

    // endregion

    // region records and store

    private val ring = 1_061_208_000L
    private val fastSweep = RawStorageSweep(RawVideoQualifier.DESTINATION_ID, List(60) { RawThroughputSample(1_000, 1_400_000_000, 0) })

    private fun v2(config: RawVideoConfig, framesWritten: Long = 600) = RawThroughputBenchmark.qualifyWithBuffer(
        config, ring, fastSweep,
        RawLiveRun(config, durationMs = 20_400, completedCaptures = 600, framesWritten = framesWritten, ringOverflow = false,
            peakRingBytes = 15_606_000, ringBytes = ring, maxWriteMs = 12.0),
    )

    private fun record(config: RawVideoConfig, extra: List<String> = emptyList(), framesWritten: Long = 600, at: Long = 1_000) =
        RawQualificationRecords.forRun(FP, RawVideoQualifier.DESTINATION_ID, config, ring, v2(config, framesWritten), extra, at)

    @Test fun aCleanPassIsStoredAsRuleV2JudgedIt() {
        val config = config("full@30")
        val verdict = v2(config)
        assertEquals(RawBenchmarkStatus.PASS, verdict.status)
        assertSame(verdict, RawQualificationRecords.storedQualification(verdict, emptyList()))
        val stored = record(config)
        assertEquals(RawThroughputBenchmark.PROTOCOL_VERSION, stored.protocolVersion)
        assertEquals(RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS, stored.destinationId)
        assertEquals(ring, stored.ringBytes)
        assertEquals(RawBenchmarkStatus.PASS, stored.qualification.status)
    }

    @Test fun theRunsOwnFailuresTurnAStoredPassIntoAFail() {
        val stored = record(config("full@30"), extra = listOf("fps-not-reached", "ocraw-scan-mismatch")).qualification
        assertEquals(RawBenchmarkStatus.FAIL, stored.status)
        assertEquals("fps-not-reached", stored.reasonCode)
        assertEquals(listOf("fps-not-reached", "ocraw-scan-mismatch"), stored.reasonCodes)
        val lost = record(config("full@30"), extra = listOf("writer-error"), framesWritten = 590).qualification
        assertEquals(listOf(RawThroughputBenchmark.REASON_RAW_FRAME_LOSS, "writer-error"), lost.reasonCodes)
    }

    @Test fun aCameraReasonLeadsTheStoredCodes() {
        val limited = record(config("full@60"), framesWritten = 0,
            extra = listOf("fps-not-reached", RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT)).qualification
        assertEquals(RawBenchmarkStatus.FAIL, limited.status)
        assertEquals(RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT, limited.reasonCode)
        assertEquals(RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT, limited.reasonCodes.first())
        assertTrue(limited.reasonCodes.containsAll(listOf("fps-not-reached", RawThroughputBenchmark.REASON_RAW_FRAME_LOSS)))
        val none = record(config("full@30"), extra = listOf("fps-not-reached", RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES)).qualification
        assertEquals(listOf(RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES, "fps-not-reached"), none.reasonCodes)
    }

    @Test fun aStorageOnlyFailureIsStoredAsFailButReadAsRisky() {
        // The Razr's full@60: zero loss and a 17 % ring peak, but a sweep median under the margin.
        val razr = RawVideoConfig("0", 4000, 3000, 4000, 3000, 60)
        val slowSweep = RawStorageSweep(RawVideoQualifier.DESTINATION_ID, List(60) { RawThroughputSample(1_000, 1_000_000_000, 0) })
        val live = RawLiveRun(razr, durationMs = 40_200, completedCaptures = 2_400, framesWritten = 2_400, ringOverflow = false,
            peakRingBytes = ring / 6, ringBytes = ring, maxWriteMs = 12.0)
        val verdict = RawThroughputBenchmark.qualifyWithBuffer(razr, ring, slowSweep, live)
        assertEquals(RawBenchmarkStatus.FAIL, verdict.status)
        assertEquals(listOf(RawThroughputBenchmark.REASON_SWEEP_MEDIAN_MARGIN_FAIL), verdict.reasonCodes)
        assertTrue(RawQualificationRisk.isRisky(RawQualificationRecords.storedQualification(verdict, emptyList())))
        // The run's own failures (here the frame rate) keep it failed, not risky.
        assertFalse(RawQualificationRisk.isRisky(RawQualificationRecords.storedQualification(verdict, listOf("fps-not-reached"))))
        assertFalse(RawQualificationRisk.isRisky(RawQualificationRecords.storedQualification(verdict,
            listOf("fps-not-reached", RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT))))
        // A pass is not risky; neither is an empty list.
        assertFalse(RawQualificationRisk.isRisky(v2(config("full@30"))))
        assertFalse(RawQualificationRisk.isStorageOnly(emptyList()))
        assertTrue(RawQualificationRisk.isStorageOnly(RawQualificationRisk.STORAGE_REASONS.toList()))
        assertFalse(RawQualificationRisk.isStorageOnly(listOf(RawThroughputBenchmark.REASON_RING_OVERFLOW, RawThroughputBenchmark.REASON_LIVE_RUN_TOO_SHORT)))
        assertFalse(RawQualificationRisk.isStorageOnly(listOf(RawThroughputBenchmark.REASON_SWEEP_WINDOW_INVALID)))
        assertFalse(RawQualificationRisk.isStorageOnly(listOf(RawVideoQualifier.REASON_CODEC_UNAVAILABLE)))
    }

    @Test fun riskyConfigsReadTheNewestCurrentRecordOfEachConfiguration() {
        val full60 = config("full@60")
        val full30 = config("full@30")
        val risky = record(full60, framesWritten = 590, at = 1_000) // raw-frame-loss alone
        assertTrue(RawQualificationRisk.isRisky(risky.qualification))
        val set = com.librestatic.opencinecam.camera.RawVideoQualificationSet(listOf(risky, record(full30)))
        fun riskyOf(records: com.librestatic.opencinecam.camera.RawVideoQualificationSet, fp: String = FP) =
            RawQualificationRisk.riskyConfigs(records, fp, RawThroughputBenchmark.PROTOCOL_VERSION, RawVideoQualifier.DESTINATION_ID, "0")
        assertEquals(listOf(full60), riskyOf(set))
        // Another build, camera or destination: nothing.
        assertTrue(riskyOf(set, fp = "other").isEmpty())
        assertTrue(RawQualificationRisk.riskyConfigs(set, FP, RawThroughputBenchmark.PROTOCOL_VERSION, "elsewhere", "0").isEmpty())
        assertTrue(RawQualificationRisk.riskyConfigs(set, FP, RawThroughputBenchmark.PROTOCOL_VERSION, RawVideoQualifier.DESTINATION_ID, "1").isEmpty())
        // A newer pass replaces it; a newer non-storage failure makes it plain failed.
        assertTrue(riskyOf(set.upsert(record(full60, at = 2_000))).isEmpty())
        assertTrue(riskyOf(set.upsert(record(full60, extra = listOf("writer-error"), framesWritten = 590, at = 2_000))).isEmpty())
    }

    @Test fun aRiskyRecordCarriesItsBurstSecondsAndOthersDoNot() {
        val full60 = config("full@60") // 936.36 MB/s
        // The Razr's sweep: 56 windows at 1.2 GB/s (67.2 GB of cache), then 15 at 500 MB/s.
        val sustained = com.librestatic.opencinecam.camera.RawStorageCliff.detect(
            List(56) { RawThroughputSample(1_000, 1_200_000_000, 0) } + List(15) { RawThroughputSample(1_000, 500_000_000, 0) })
        assertEquals(500_000_000L, sustained.sustainedBytesPerSecond)
        val risky = record(full60, framesWritten = 590) // raw-frame-loss alone
        // 67.2e9 / 936.36e6 = 71.8 s of cache, then 1.06 GB / 436.36 MB/s = 2.4 s of ring.
        assertEquals(74L, RawQualificationRecords.withBurstSeconds(risky, sustained).burstSeconds)
        // LJ92 at 1.5x: 624.24 MB/s, 107.7 s of cache, then (1.06 GB / 1.5) / 124.24 MB/s = 5.7 s of ring.
        assertEquals(113L, RawQualificationRecords.withBurstSeconds(risky, sustained, compressionRatio = 1.5).burstSeconds)
        // A pass, a camera failure or a sweep with no cliff carry none.
        assertNull(RawQualificationRecords.withBurstSeconds(record(full60), sustained).burstSeconds)
        assertNull(RawQualificationRecords.withBurstSeconds(record(full60, extra = listOf("writer-error"), framesWritten = 590), sustained).burstSeconds)
        assertNull(RawQualificationRecords.withBurstSeconds(risky, null).burstSeconds)
        assertNull(RawQualificationRecords.withBurstSeconds(risky,
            com.librestatic.opencinecam.camera.RawStorageCliff.detect(List(150) { RawThroughputSample(1_000, 1_200_000_000, 0) })).burstSeconds)
    }

    @Test fun theSustainedPhaseNeverGrowsAnUnboundedRowIntoTheReserve() {
        val reserve = RawQualificationTiming.SPACE_RESERVE_BYTES
        // A rewinding sweep never grows past its wrap, so it always has room.
        assertTrue(RawVideoQualifier.sustainedRoomLeft(RawVideoQualifier.SWEEP_WRAP_BYTES, 500L shl 30, 10L shl 30))
        // An unbounded one stops before its row would leave less than the reserve free.
        val free = 400L shl 30
        assertTrue(RawVideoQualifier.sustainedRoomLeft(Long.MAX_VALUE, free - reserve, free))
        assertFalse(RawVideoQualifier.sustainedRoomLeft(Long.MAX_VALUE, free - reserve + 1, free))
    }

    @Test fun theSweepPlansItsSustainedPhase() {
        assertEquals((60 + 90) * 1_000L + RawQualificationTiming.SWEEP_OVERHEAD_MS, RawQualificationTiming().sweepMs)
        assertEquals(60_000L + RawQualificationTiming.SWEEP_OVERHEAD_MS, RawQualificationTiming(sweepMaxExtraSeconds = 0).sweepMs)
    }

    @Test fun primaryFirstPrefersTheFpsLimitAndKeepsTheRestInOrder() {
        val limit = RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT
        val noFrames = RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES
        assertEquals(listOf(limit, "a", noFrames, "b"), RawQualificationFrameRate.primaryFirst(listOf("a", noFrames, "b", limit)))
        assertEquals(listOf(noFrames, "a", "b"), RawQualificationFrameRate.primaryFirst(listOf("a", "b", noFrames)))
        assertEquals(listOf("a", "b"), RawQualificationFrameRate.primaryFirst(listOf("a", "b")))
    }

    @Test fun aCameraLimitNeedsBothTheShortfallAndTheDeclaration() {
        val thirty = 33_333_333L
        assertEquals(30.0, RawQualificationFrameRate.declaredMaxFps(thirty)!!, 0.01)
        assertNull(RawQualificationFrameRate.declaredMaxFps(0L))
        assertNull(RawQualificationFrameRate.declaredMaxFps(-1L))
        // The Razr: 30 fps declared, nothing delivered at 60.
        assertTrue(RawQualificationFrameRate.isCameraLimit(thirty, 60, 0.0))
        // The S24: 30 fps declared, 60 delivered.
        assertFalse(RawQualificationFrameRate.isCameraLimit(thirty, 60, 59.9))
        // Within the tolerance of the declaration, or no declaration: not a camera limit.
        assertFalse(RawQualificationFrameRate.isCameraLimit(thirty, 30, 20.0))
        assertFalse(RawQualificationFrameRate.isCameraLimit(0L, 60, 0.0))
        assertTrue(RawQualificationFrameRate.isCameraLimit(41_666_666L, 30, 24.0))
    }

    @Test fun cameraReasonsFallBackToNoFrames() {
        val thirty = 33_333_333L
        assertEquals(listOf(RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT), RawQualificationFrameRate.cameraReasons(thirty, 60, 0.0, 0))
        assertEquals(listOf(RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES), RawQualificationFrameRate.cameraReasons(0L, 60, 0.0, 0))
        assertEquals(listOf(RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES), RawQualificationFrameRate.cameraReasons(thirty, 30, 0.0, 0))
        assertEquals(emptyList<String>(), RawQualificationFrameRate.cameraReasons(thirty, 30, 20.0, 400))
        assertEquals(emptyList<String>(), RawQualificationFrameRate.cameraReasons(thirty, 60, 59.9, 1200))
    }

    @Test fun theRazrWithTheQtiOverrideDeliveringSixtyIsNoCameraLimit() {
        // The run keeps its declared 30 fps (33.3 ms); the vendor keys make the HAL deliver 60.
        val thirty = 33_333_333L
        assertEquals(emptyList<String>(), RawQualificationFrameRate.cameraReasons(thirty, 60, 60.0, 1800))
        assertFalse(RawQualificationFrameRate.fpsNotReached(60.0, 60))
        // Should the override not take, the shortfall still reads as the camera's limit.
        assertEquals(listOf(RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT), RawQualificationFrameRate.cameraReasons(thirty, 60, 30.0, 900))
    }

    @Test fun aShortfallWithTheQtiOverrideAppliedIsAPlainFailureNotACameraLimit() {
        // The Razr at 1920x1080@60 with the vendor keys sent: 48-57 fps against a declared 30.
        val thirty = 33_333_333L
        assertTrue(RawQualificationFrameRate.fpsNotReached(51.0, 60))
        assertEquals(emptyList<String>(), RawQualificationFrameRate.cameraReasons(thirty, 60, 51.0, 1600, qtiFrameRateOverride = true))
        assertEquals(emptyList<String>(), RawQualificationFrameRate.cameraReasons(thirty, 60, 30.0, 900, qtiFrameRateOverride = true))
        // No frames at all is still the camera's, override or not.
        assertEquals(listOf(RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES),
            RawQualificationFrameRate.cameraReasons(thirty, 60, 0.0, 0, qtiFrameRateOverride = true))
        // Without the override the same shortfall stays the camera's limit.
        assertEquals(listOf(RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT), RawQualificationFrameRate.cameraReasons(thirty, 60, 51.0, 1600))
        // Stored and shown as an ordinary failure: not CAMERA_LIMIT and not RISKY.
        val extra = listOf("fps-not-reached") + RawQualificationFrameRate.cameraReasons(thirty, 60, 51.0, 1600, qtiFrameRateOverride = true)
        val stored = record(config("1920x1080@60"), extra = extra).qualification
        assertEquals(RawBenchmarkStatus.FAIL, stored.status)
        assertEquals("fps-not-reached", stored.reasonCode)
        assertFalse(RawQualificationRisk.isRisky(stored))
        assertEquals(RawQualificationItemStatus.FAILED, rawQualificationRunStatus("FAIL", stored.reasonCode, stored.reasonCodes))
    }

    @Test fun overallStatusFollowsTheReport() {
        assertEquals(RawQualificationStatus.PASS, RawQualificationRecords.overallStatus(listOf("FAIL", "PASS", "UNKNOWN")))
        assertEquals(RawQualificationStatus.FAIL, RawQualificationRecords.overallStatus(listOf("UNKNOWN", "FAIL")))
        assertEquals(RawQualificationStatus.UNKNOWN, RawQualificationRecords.overallStatus(listOf("UNKNOWN")))
        assertEquals(RawQualificationStatus.UNKNOWN, RawQualificationRecords.overallStatus(emptyList()))
    }

    @Test fun mergedRecordsRoundTripAndOpenTheGate() {
        val store = RawVideoQualificationStore(File(dir, RawVideoQualificationStore.FILE_NAME), FP)
        val other = RawVideoConfig("0", sensorW, sensorH, 1920, 1080, 30)
        store.upsert(record(other, at = 10))
        store.upsert(record(config("full@24"), at = 10).copy(fingerprint = "old-build"))
        val records = listOf(
            record(config("full@30"), at = 20),
            record(config("full@60"), extra = listOf("fps-not-reached"), at = 20),
            record(config("full@30+lj92"), at = 21),
            record(config("full@60+lj92"), at = 21),
        )
        val stored = store.upsertAll(records)
        assertEquals(5, stored.records.size) // the old build's record is pruned, the other config kept
        assertEquals(stored.records.toSet(), store.load().records.toSet())

        val qualified = store.qualifiedConfigs("0")
        assertEquals(setOf(other, config("full@30"), config("full@30+lj92"), config("full@60+lj92")), qualified.toSet())
        val cameraFailed = RawQualificationRisk.cameraFailedConfigs(store.load(), FP, RawThroughputBenchmark.PROTOCOL_VERSION,
            RawVideoQualifier.DESTINATION_ID, "0")
        assertEquals(listOf(config("full@60")), cameraFailed)
        val usable = rawVideoUsableConfigs("0", listOf(sensorW to sensorH), qualified, codecAvailable = true, cameraFailed = cameraFailed)
        // full@60+lj92 passed but its uncompressed twin failed for the camera, so the app never offers it.
        assertEquals(listOf(config("full@30"), config("full@30+lj92"), other), usable)
        assertEquals(ModeGateState.AVAILABLE, rawVideoGate(listOf(sensorW to sensorH), usable, failedThisSession = false))
        assertEquals(listOf(config("full@30"), other),
            rawVideoUsableConfigs("0", listOf(sensorW to sensorH), qualified, codecAvailable = false))
    }

    @Test fun aLaterFailureRevokesAnEarlierPass() {
        val store = RawVideoQualificationStore(File(dir, RawVideoQualificationStore.FILE_NAME), FP)
        store.upsertAll(listOf(record(config("full@30"), at = 1)))
        assertEquals(listOf(config("full@30")), store.qualifiedConfigs("0"))
        store.upsertAll(listOf(record(config("full@30"), extra = listOf("writer-hung"), at = 2)))
        assertEquals(1, store.load().records.size)
        assertTrue(store.qualifiedConfigs("0").isEmpty())
        assertEquals(ModeGateState.CANDIDATE, rawVideoGate(listOf(sensorW to sensorH),
            rawVideoUsableConfigs("0", listOf(sensorW to sensorH), store.qualifiedConfigs("0"), true), failedThisSession = false))
    }

    // endregion

    private companion object {
        const val FP = "samsung/e3qxxx/e3q:16/BP2A/S928B:user/release-keys"
    }
}
