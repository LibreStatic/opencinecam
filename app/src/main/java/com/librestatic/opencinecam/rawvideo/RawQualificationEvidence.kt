/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import com.librestatic.opencinecam.camera.RawBenchmarkStatus
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawVideoConfig
import java.util.Locale

/**
 * One camera check or encode benchmark session an earlier qualification measured, kept so a later one can reuse it
 * instead of measuring it again (PLAN-071, 2026-11-20). [kind] is [RawQualificationEvidenceRules.KIND_CAMERA_CHECK] or
 * [RawQualificationEvidenceRules.KIND_ENCODE_BENCH]; [key] is everything its result depends on
 * ([RawQualificationEvidenceRules.cameraCheckKey], [RawQualificationEvidenceRules.encodeBenchKey]). It counts only on the
 * build ([fingerprint], [appVersion]) and [protocolVersion] it was measured on. [status] and [reasonCodes] are the
 * session's verdict, [ringSlots] the ring it ran with, [achievedFps] the rate the camera delivered, and [row] the
 * session's full JSON row, which a reused step replays.
 */
data class RawQualificationEvidence(
    val kind: String,
    val key: String,
    val fingerprint: String,
    val appVersion: Long,
    val protocolVersion: Int,
    val measuredAtEpochMs: Long,
    val status: String,
    val reasonCodes: List<String>,
    val ringSlots: Int,
    val achievedFps: Double?,
    val row: String,
)

/**
 * When a camera check or an encode benchmark of an earlier run may stand in for a new one (PLAN-071, 2026-11-20). Both
 * judge only the camera and the CPU, never the storage, so the destination does not matter; everything else they depend
 * on is in their key, and the build and protocol in [RawQualificationEvidence]. Only conclusive results the heat did not
 * touch are kept ([reusableResult]), and only for [MAX_AGE_MS].
 */
object RawQualificationEvidenceRules {
    /** A RAM-only camera check (the session's `stage`). */
    const val KIND_CAMERA_CHECK = "camera"

    /** An encode benchmark session (the session's `stage`). */
    const val KIND_ENCODE_BENCH = "encode-bench"

    /** How long a stored result may be reused: 30 days. */
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1_000

    /** The configuration part of a key: camera, sensor, crop, rate and encoding. */
    fun configKey(config: RawVideoConfig): String =
        "${config.cameraId}:${config.sensorWidth}x${config.sensorHeight}:${config.cropWidth}x${config.cropHeight}@${config.fps}:${config.encoding}"

    /**
     * The key of [config]'s camera check: the configuration, the ring slot size ([slotBytes]) and the check's warm-up and
     * measured seconds. The ring's slot count is not in it: a pass with fewer slots holds with more ([usable]).
     */
    fun cameraCheckKey(config: RawVideoConfig, slotBytes: Int, timing: RawQualificationTiming): String =
        "$KIND_CAMERA_CHECK|${configKey(config)}|slot=$slotBytes|warmup=${timing.cameraCheckWarmupSeconds}|measure=${timing.cameraCheckSeconds}"

    /**
     * The key of one encode benchmark session on [config]: the codec ([encoding]), its [threads] and [strips], the rate
     * the camera is asked to hold ([expectedFps], to a tenth), the slot size and the session's warm-up and measured seconds.
     */
    fun encodeBenchKey(
        config: RawVideoConfig,
        encoding: String,
        threads: Int,
        strips: Int,
        expectedFps: Double,
        slotBytes: Int,
        timing: RawQualificationTiming,
    ): String = "$KIND_ENCODE_BENCH|${configKey(config)}|codec=$encoding|threads=$threads|strips=$strips|" +
        "fps=${String.format(Locale.ROOT, "%.1f", expectedFps)}|slot=$slotBytes|warmup=${timing.cameraCheckWarmupSeconds}|" +
        "measure=${timing.encodeBenchSeconds}"

    /**
     * A result conclusive enough to reuse. A camera check must have passed (a reduced-rate pass included: it stayed no
     * hotter than MODERATE). An encode benchmark must have passed, or failed only because the camera did not hold the
     * rate under the encode load ([RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED]); a benchmark the heat
     * spoiled ([RawQualificationStaging.REASON_THERMAL_LIMIT]), an encoder error or one that encoded nothing is not.
     * Skips (UNKNOWN: thermal skip, no ring, an exception, no codec) never are; a cancelled session leaves no row at all.
     */
    fun reusableResult(kind: String, status: String, reasonCodes: List<String>): Boolean {
        val failures = reasonCodes.filter { it != RawThroughputBenchmark.REASON_PASS && it !in RawQualificationRisk.INFORMATIONAL_REASONS }
        return when (kind) {
            KIND_CAMERA_CHECK -> status == RawBenchmarkStatus.PASS.name && failures.isEmpty()
            KIND_ENCODE_BENCH -> when (status) {
                RawBenchmarkStatus.PASS.name -> failures.isEmpty()
                RawBenchmarkStatus.FAIL.name -> failures.isNotEmpty() && failures.all { it == RawQualificationStaging.REASON_COMPRESSED_CAMERA_STARVED }
                else -> false
            }
            else -> false
        }
    }

    /** Measured no later than [nowMs] and at most [MAX_AGE_MS] before it. */
    fun fresh(measuredAtEpochMs: Long, nowMs: Long): Boolean = measuredAtEpochMs <= nowMs && nowMs - measuredAtEpochMs <= MAX_AGE_MS

    /**
     * [entry] stands in for a new session on this build ([fingerprint], [appVersion], [protocolVersion]) at [nowMs]: the
     * build matches, it is [fresh] and [reusableResult], and it ran with no more ring slots than the [ringSlots] now
     * allocated (null: not known yet, before the ring is allocated).
     */
    fun usable(
        entry: RawQualificationEvidence,
        fingerprint: String,
        appVersion: Long,
        protocolVersion: Int,
        nowMs: Long,
        ringSlots: Int? = null,
    ): Boolean = entry.fingerprint == fingerprint && entry.appVersion == appVersion && entry.protocolVersion == protocolVersion &&
        fresh(entry.measuredAtEpochMs, nowMs) && reusableResult(entry.kind, entry.status, entry.reasonCodes) &&
        entry.ringSlots > 0 && (ringSlots == null || entry.ringSlots <= ringSlots)

    /** What an encode benchmark of [config] asks the camera to hold: its reduced [achievedFps] when it has one, else the target. */
    fun expectedFps(config: RawVideoConfig, achievedFps: Double?): Double =
        achievedFps?.takeIf { RawQualificationFrameRate.isReducedRate(it, config.fps) } ?: config.fps.toDouble()

    /**
     * What a run of [plan] on [runs] (one camera's parsed specs) expects to reuse, from [lookup] (the evidence store's
     * usable entry of a kind and key, or null): every uncompressed configuration whose camera check is stored, and for
     * each codec the sessions of its first benchmark configuration, the heaviest candidate, when that configuration's
     * camera check is reused too (otherwise the check runs first and decides which configuration the benchmark takes).
     * Nothing without [RawQualificationPlan.reusesEvidence] or the camera stage.
     */
    fun predictReuse(
        plan: RawQualificationPlan,
        runs: List<RawQualificationRun>,
        codecAvailable: Boolean,
        slotBytes: Int,
        timing: RawQualificationTiming,
        lookup: (kind: String, key: String) -> RawQualificationEvidence?,
    ): RawQualificationReuse {
        if (!plan.reusesEvidence || RawQualificationStage.CAMERA !in plan.effectiveStages) return RawQualificationReuse.NONE
        val uncompressed = RawQualificationStaging.longRunOrder(plan.uncompressedRuns(runs))
        val checks = uncompressed.mapNotNull { run -> lookup(KIND_CAMERA_CHECK, cameraCheckKey(run.config, slotBytes, timing))?.let { run.config to it } }.toMap()
        val compressed = plan.compressedRuns(runs, codecAvailable)
        val benches = mutableSetOf<RawQualificationReusedBench>()
        for (encoding in plan.benchEncodings(runs, codecAvailable)) {
            val twins = compressed.filter { it.config.encoding == encoding }.map { it.config.copy(encoding = RawVideoConfig.ENCODING_RAW10) }.toSet()
            val first = uncompressed.firstOrNull { it.config in twins } ?: continue
            val check = checks[first.config] ?: continue
            val fps = expectedFps(first.config, check.achievedFps)
            for (threads in plan.threadsFor(encoding)) {
                val key = encodeBenchKey(first.config, encoding, threads, plan.encodeStrips, fps, slotBytes, timing)
                if (lookup(KIND_ENCODE_BENCH, key) != null) benches += RawQualificationReusedBench(first.config, encoding, threads, key)
            }
        }
        return RawQualificationReuse(checks.keys, benches)
    }

    /** [predictReuse] for [plan]'s specs on the camera [cameraId] whose largest RAW10 output is [sensorWidth] x [sensorHeight]. */
    fun predictReuse(
        plan: RawQualificationPlan,
        cameraId: String,
        sensorWidth: Int,
        sensorHeight: Int,
        codecAvailable: Boolean,
        slotBytes: Int,
        timing: RawQualificationTiming,
        lookup: (kind: String, key: String) -> RawQualificationEvidence?,
    ): RawQualificationReuse = predictReuse(
        plan, plan.specs.mapNotNull { RawQualificationSpecs.parse(it, cameraId, sensorWidth, sensorHeight) }, codecAvailable, slotBytes, timing, lookup,
    )
}
