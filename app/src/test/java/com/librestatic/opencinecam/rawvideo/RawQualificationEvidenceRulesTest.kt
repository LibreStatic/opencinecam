/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawVideoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN-071 (2026-11-20): when a camera check or encode benchmark of an earlier run may be reused. */
class RawQualificationEvidenceRulesTest {
    private val sensorW = 4080
    private val sensorH = 3060
    private val slot = 15_606_000
    private val timing = RawQualificationTiming()
    private val now = 100L * 24 * 60 * 60 * 1_000

    private fun run(spec: String) = requireNotNull(RawQualificationSpecs.parse(spec, "0", sensorW, sensorH))
    private fun config(spec: String) = run(spec).config

    private fun entry(
        kind: String = RawQualificationEvidenceRules.KIND_CAMERA_CHECK,
        status: String = "PASS",
        codes: List<String> = emptyList(),
        at: Long = now - 1_000,
        fingerprint: String = FP,
        app: Long = 200,
        protocol: Int = RawThroughputBenchmark.PROTOCOL_VERSION,
        slots: Int = 24,
        fps: Double? = 30.0,
    ) = RawQualificationEvidence(kind, "k", fingerprint, app, protocol, at, status, codes, slots, fps, "{}")

    private fun usable(e: RawQualificationEvidence, ringSlots: Int? = null) =
        RawQualificationEvidenceRules.usable(e, FP, 200, RawThroughputBenchmark.PROTOCOL_VERSION, now, ringSlots)

    @Test fun keysCoverEverythingTheResultDependsOn() {
        val key = RawQualificationEvidenceRules.cameraCheckKey(config("full@30"), slot, timing)
        assertEquals(key, RawQualificationEvidenceRules.cameraCheckKey(config("full@30"), slot, timing))
        assertNotEquals(key, RawQualificationEvidenceRules.cameraCheckKey(config("full@24"), slot, timing))
        assertNotEquals(key, RawQualificationEvidenceRules.cameraCheckKey(config("full@30").copy(cameraId = "2"), slot, timing))
        assertNotEquals(key, RawQualificationEvidenceRules.cameraCheckKey(config("3840x2160@30"), slot, timing))
        assertNotEquals(key, RawQualificationEvidenceRules.cameraCheckKey(config("full@30"), slot + 1, timing))
        assertNotEquals(key, RawQualificationEvidenceRules.cameraCheckKey(config("full@30"), slot, timing.copy(cameraCheckSeconds = 9)))
        assertNotEquals(key, RawQualificationEvidenceRules.cameraCheckKey(config("full@30"), slot, timing.copy(cameraCheckWarmupSeconds = 3)))

        val bp = RawVideoConfig.ENCODING_BLOCK_PACK
        val bench = RawQualificationEvidenceRules.encodeBenchKey(config("full@30"), bp, 4, 8, 30.0, slot, timing)
        assertNotEquals(bench, key)
        assertEquals(bench, RawQualificationEvidenceRules.encodeBenchKey(config("full@30"), bp, 4, 8, 30.04, slot, timing))
        assertNotEquals(bench, RawQualificationEvidenceRules.encodeBenchKey(config("full@30"), RawVideoConfig.ENCODING_LJ92, 4, 8, 30.0, slot, timing))
        assertNotEquals(bench, RawQualificationEvidenceRules.encodeBenchKey(config("full@30"), bp, 6, 8, 30.0, slot, timing))
        assertNotEquals(bench, RawQualificationEvidenceRules.encodeBenchKey(config("full@30"), bp, 4, 16, 30.0, slot, timing))
        assertNotEquals(bench, RawQualificationEvidenceRules.encodeBenchKey(config("full@30"), bp, 4, 8, 27.5, slot, timing))
        assertNotEquals(bench, RawQualificationEvidenceRules.encodeBenchKey(config("full@30"), bp, 4, 8, 30.0, slot, timing.copy(encodeBenchSeconds = 20)))
    }

    @Test fun onlyConclusiveResultsWithoutHeatAreReusable() {
        val camera = RawQualificationEvidenceRules.KIND_CAMERA_CHECK
        val bench = RawQualificationEvidenceRules.KIND_ENCODE_BENCH
        assertTrue(RawQualificationEvidenceRules.reusableResult(camera, "PASS", emptyList()))
        assertTrue(RawQualificationEvidenceRules.reusableResult(camera, "PASS", listOf(RawQualificationStaging.REASON_FPS_REDUCED)))
        assertFalse(RawQualificationEvidenceRules.reusableResult(camera, "FAIL", listOf("fps-not-reached")))
        assertFalse(RawQualificationEvidenceRules.reusableResult(camera, "FAIL", listOf(RawQualificationStaging.REASON_THERMAL_LIMIT)))
        assertFalse(RawQualificationEvidenceRules.reusableResult(camera, "UNKNOWN", listOf("thermal-wait-timeout")))
        assertFalse(RawQualificationEvidenceRules.reusableResult(camera, "PASS", listOf(RawQualificationStaging.REASON_THERMAL_LIMIT)))

        assertTrue(RawQualificationEvidenceRules.reusableResult(bench, "PASS", emptyList()))
        assertTrue(RawQualificationEvidenceRules.reusableResult(bench, "FAIL", listOf(RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED)))
        assertFalse(RawQualificationEvidenceRules.reusableResult(bench, "FAIL", listOf(RawQualificationStaging.REASON_THERMAL_LIMIT)))
        assertFalse(RawQualificationEvidenceRules.reusableResult(bench, "FAIL",
            listOf(RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED, RawQualificationStaging.REASON_THERMAL_LIMIT)))
        assertFalse(RawQualificationEvidenceRules.reusableResult(bench, "FAIL", emptyList()))
        assertFalse(RawQualificationEvidenceRules.reusableResult(bench, "UNKNOWN", emptyList()))
        assertFalse(RawQualificationEvidenceRules.reusableResult("live", "PASS", emptyList()))
    }

    @Test fun anEntryCountsOnlyOnItsBuildForThirtyDaysWithNoMoreRingSlots() {
        assertTrue(usable(entry()))
        assertTrue(usable(entry(at = now - RawQualificationEvidenceRules.MAX_AGE_MS)))
        assertFalse(usable(entry(at = now - RawQualificationEvidenceRules.MAX_AGE_MS - 1)))
        assertFalse(usable(entry(at = now + 1)))
        assertFalse(usable(entry(fingerprint = "other")))
        assertFalse(usable(entry(app = 201)))
        assertFalse(usable(entry(protocol = RawThroughputBenchmark.PROTOCOL_VERSION + 1)))
        assertFalse(usable(entry(status = "FAIL", codes = listOf("fps-not-reached"))))
        // A check that held with 24 slots holds with 24 or more, not with fewer; one with no ring never counts.
        assertTrue(usable(entry(slots = 24), ringSlots = 24))
        assertTrue(usable(entry(slots = 24), ringSlots = 40))
        assertFalse(usable(entry(slots = 24), ringSlots = 16))
        assertFalse(usable(entry(slots = 0)))
    }

    @Test fun aBenchmarkAsksForTheReducedRateItsCheckAchieved() {
        assertEquals(30.0, RawQualificationEvidenceRules.expectedFps(config("full@30"), null), 0.0)
        assertEquals(30.0, RawQualificationEvidenceRules.expectedFps(config("full@30"), 29.95), 0.0)
        assertEquals(26.0, RawQualificationEvidenceRules.expectedFps(config("full@30"), 26.0), 0.0)
    }

    @Test fun reuseIsPredictedFromTheStoredChecksAndTheFirstBenchmarkConfiguration() {
        val plan = RawQualificationPlan.DEFAULT
        val runs = plan.specs.map(::run)
        val order = RawQualificationStaging.longRunOrder(plan.uncompressedRuns(runs)).map { it.config }
        val heaviest = order.first()
        val stored = mutableMapOf<String, RawQualificationEvidence>()
        fun store(kind: String, key: String, fps: Double? = null) { stored[key] = entry(kind = kind, fps = fps) }
        val lookup: (String, String) -> RawQualificationEvidence? = { _, key -> stored[key] }

        assertEquals(RawQualificationReuse.NONE, RawQualificationEvidenceRules.predictReuse(plan, runs, true, slot, timing, lookup))
        // A stored check of a lighter configuration is reused, but the benchmark is not predicted.
        store(RawQualificationEvidenceRules.KIND_CAMERA_CHECK, RawQualificationEvidenceRules.cameraCheckKey(config("full@30"), slot, timing))
        val benchKey = RawQualificationEvidenceRules.encodeBenchKey(heaviest, RawVideoConfig.ENCODING_BLOCK_PACK,
            plan.threadsFor(RawVideoConfig.ENCODING_BLOCK_PACK).single(), plan.encodeStrips, heaviest.fps.toDouble(), slot, timing)
        store(RawQualificationEvidenceRules.KIND_ENCODE_BENCH, benchKey)
        val lighter = RawQualificationEvidenceRules.predictReuse(plan, runs, true, slot, timing, lookup)
        assertEquals(setOf(config("full@30")), lighter.cameraChecks)
        assertEquals(0, lighter.benchSessions)
        // With the heaviest configuration's check stored, its benchmark is reused too.
        store(RawQualificationEvidenceRules.KIND_CAMERA_CHECK, RawQualificationEvidenceRules.cameraCheckKey(heaviest, slot, timing))
        val both = RawQualificationEvidenceRules.predictReuse(plan, runs, true, slot, timing, lookup)
        assertEquals(setOf(config("full@30"), heaviest), both.cameraChecks)
        assertEquals(1, both.benchSessions)
        assertEquals(benchKey, both.benches.single().key)
        // A heaviest check that reached a reduced rate asks for the benchmark at that rate, which is not stored.
        store(RawQualificationEvidenceRules.KIND_CAMERA_CHECK, RawQualificationEvidenceRules.cameraCheckKey(heaviest, slot, timing), fps = heaviest.fps * 0.8)
        assertEquals(0, RawQualificationEvidenceRules.predictReuse(plan, runs, true, slot, timing, lookup).benchSessions)
        store(RawQualificationEvidenceRules.KIND_CAMERA_CHECK, RawQualificationEvidenceRules.cameraCheckKey(heaviest, slot, timing))
        // Without the codec there is no benchmark to reuse.
        assertEquals(0, RawQualificationEvidenceRules.predictReuse(plan, runs, false, slot, timing, lookup).benchSessions)
        // The device test's explicit plans, partial stages and a plan without camera checks reuse nothing.
        assertEquals(RawQualificationReuse.NONE,
            RawQualificationEvidenceRules.predictReuse(RawQualificationPlan.explicit(plan.specs), runs, true, slot, timing, lookup))
        assertEquals(RawQualificationReuse.NONE, RawQualificationEvidenceRules.predictReuse(
            plan.copy(stages = setOf(RawQualificationStage.CAMERA, RawQualificationStage.ENCODE)), runs, true, slot, timing, lookup))
        // A camera-only run reuses the same.
        assertEquals(both, RawQualificationEvidenceRules.predictReuse(plan.withOptions(RawQualificationOptions(cameraOnly = true)),
            runs, true, slot, timing, lookup))
        // The overload on a camera's sensor parses the plan's specs.
        assertEquals(both, RawQualificationEvidenceRules.predictReuse(plan, "0", sensorW, sensorH, true, slot, timing, lookup))
    }

    private companion object {
        const val FP = "google/razr/blanc:15/V1/1:user/release-keys"
    }
}
