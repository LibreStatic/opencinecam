/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import com.librestatic.opencinecam.camera.RawBenchmarkStatus
import com.librestatic.opencinecam.camera.RawStorageCliff
import com.librestatic.opencinecam.camera.RawStorageSweep
import com.librestatic.opencinecam.camera.RawSustainedWrite
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoQualification
import com.librestatic.opencinecam.camera.RawVideoQualificationRecord
import kotlin.math.ceil
import kotlin.math.floor

/*
 * OCC-PLAN-071 decision 10, protocol 3: the staged qualification's decisions, pure so they are
 * host-tested. [RawVideoQualifier] runs the stages and feeds their results here:
 *
 * 1. a storage sweep (rule v2 (a) and (b));
 * 2. long disk runs in descending stream rate, stopping at the first that passes: it covers every
 *    lighter configuration storage-wise; a storage-only failure above it is risky. A configuration
 *    the sweep alone rules out ([RawQualificationStaging.sweepRuledOut]) gets no long run: the sweep
 *    decides it;
 * 3. a short RAM-only camera check of every configuration no long run judged;
 * 4. encode benchmarks, one descent per codec (block-pack by default, LJ92 when a spec asks for
 *    it), from which every compressed twin is judged by pixel rate. The camera must hold its rate
 *    while the encoder runs (on the Razr it slowed down under the LJ92 load), so the benchmark
 *    descends from the heaviest candidate until one holds the camera and keeps up with its own
 *    configuration, at most [RawQualificationStaging.MAX_ENCODE_BENCH_CONFIGS] configurations; a
 *    benchmark only the heat spoiled judges itself and the descent goes on to the next lighter one;
 * 5. a real long run of each compressed twin whose camera delivered and whose encoder keeps up,
 *    heaviest first, at most [RawQualificationStaging.MAX_COMPRESSED_LONG_RUNS]; the others, and a
 *    twin the phone is already too warm to run ([RawQualificationStaging.compressedHeatSkip]), are
 *    extrapolated from the benchmark ([RawQualificationStaging.compressedVerdict]).
 *
 * One sustained rule applies to every verdict ([RawQualificationStaging.sustainedRateReasons]).
 */

/** How a stored configuration was qualified ([com.librestatic.opencinecam.camera.RawVideoQualificationRecord.method]). */
object RawQualificationMethods {
    /** Its own long disk run judged it (rule v2 in full). */
    const val LONG_RUN = "long-run"

    /** A heavier long run that passed covers its storage; its own camera check judged the camera. */
    const val EXTRAPOLATED = "extrapolated"

    /** The encode benchmark's pixel rate, a long run covering the compressed rate and its twin's camera. */
    const val COMPRESSED_EXTRAPOLATED = "compressed-extrapolated"

    /**
     * A compressed configuration's own long disk run with its encoder active, written compressed as a
     * take writes it: judged from its own frame loss, ring, writer and measured stored bytes.
     */
    const val COMPRESSED_LONG_RUN = "compressed-long-run"

    /**
     * A camera-only run ([RawQualificationOptions.cameraOnly]) judged it from its camera check (and, compressed, its
     * codec's encode benchmark) alone; the storage was never tested ([RawQualificationRisk.REASON_STORAGE_NOT_TESTED]).
     */
    const val CAMERA_ONLY = "camera-only"

    /** [COMPRESSED_EXTRAPOLATED] as records stored before block-pack name it; still read as such. */
    const val LEGACY_LJ92_EXTRAPOLATED = "lj92-extrapolated"

    /** True for a method judged from the encode benchmark, under its current or its legacy name. */
    fun isCompressedExtrapolated(method: String?): Boolean = method == COMPRESSED_EXTRAPOLATED || method == LEGACY_LJ92_EXTRAPOLATED
}

/** What one long run or one camera check concluded. UNKNOWN is a skip; its reason is the only code. */
data class RawStageResult(val status: RawBenchmarkStatus, val reasonCodes: List<String>) {
    init {
        require(status == RawBenchmarkStatus.PASS || reasonCodes.isNotEmpty()) { "a failure or skip needs a reason" }
    }

    val passed: Boolean get() = status == RawBenchmarkStatus.PASS

    /** A failure every reason of which is the storage's ([RawQualificationRisk.STORAGE_REASONS]). */
    val storageOnlyFailure: Boolean get() = status == RawBenchmarkStatus.FAIL && RawQualificationRisk.isStorageOnly(reasonCodes)

    /** The camera delivered: a pass, or a failure only the storage explains. */
    val cameraOk: Boolean get() = passed || storageOnlyFailure

    /**
     * The camera kept its rate, even if the heat failed the run: [cameraOk], or a failure with
     * [RawQualificationStaging.REASON_THERMAL_LIMIT] whose every other reason is the storage's or
     * informational. Only a compressed twin's eligibility reads it (its own long run judges its own
     * heat); the uncompressed verdict stays the thermal failure.
     */
    val cameraDelivered: Boolean get() = cameraOk || (
        status == RawBenchmarkStatus.FAIL && RawQualificationStaging.REASON_THERMAL_LIMIT in reasonCodes &&
            reasonCodes.all {
                it == RawQualificationStaging.REASON_THERMAL_LIMIT || it in RawQualificationRisk.STORAGE_REASONS ||
                    it in RawQualificationRisk.INFORMATIONAL_REASONS
            }
        )

    /** The camera delivered steadily but under its target rate ([RawQualificationStaging.REASON_FPS_REDUCED]). */
    val reducedRate: Boolean get() = RawQualificationStaging.REASON_FPS_REDUCED in reasonCodes

    companion object {
        val PASS = RawStageResult(RawBenchmarkStatus.PASS, emptyList())

        /** A pass that keeps its informational codes ([RawQualificationRisk.INFORMATIONAL_REASONS]), e.g. a reduced rate. */
        fun pass(codes: List<String>): RawStageResult =
            codes.filter { it in RawQualificationRisk.INFORMATIONAL_REASONS }.distinct().let { if (it.isEmpty()) PASS else RawStageResult(RawBenchmarkStatus.PASS, it) }

        fun fail(codes: List<String>) = RawStageResult(RawBenchmarkStatus.FAIL, RawQualificationFrameRate.primaryFirst(codes.distinct()))

        fun skipped(reason: String) = RawStageResult(RawBenchmarkStatus.UNKNOWN, listOf(reason))

        /** A report row's `status` and `reasonCodes`: PASS, FAIL, anything else a skip. */
        fun of(status: String?, reasonCodes: List<String>): RawStageResult = when (status) {
            RawBenchmarkStatus.PASS.name -> pass(reasonCodes)
            RawBenchmarkStatus.FAIL.name -> fail(reasonCodes.filter { it != RawThroughputBenchmark.REASON_PASS }.ifEmpty { listOf("fail") })
            else -> skipped(reasonCodes.firstOrNull() ?: "skipped")
        }
    }
}

/**
 * A configuration's verdict. PASS and FAIL are stored; UNKNOWN (never judged: skipped, or no stage
 * could cover it) is not. [coveredBy] is the passing long run whose rate covers it, when one does.
 */
data class RawStagedVerdict(
    val status: RawBenchmarkStatus,
    val reasonCodes: List<String>,
    val method: String,
    val coveredBy: RawVideoConfig? = null,
) {
    val passed: Boolean get() = status == RawBenchmarkStatus.PASS
    val risky: Boolean get() = status == RawBenchmarkStatus.FAIL && RawQualificationRisk.isStorageOnly(reasonCodes)

    /** The camera delivered the uncompressed configuration this verdict judged: it passed or is only risky. */
    val cameraOk: Boolean get() = passed || risky
}

/**
 * The figures of one codec's encode benchmark the verdicts use. [config] is the uncompressed
 * configuration it ran on, [encoding] the codec ([RawVideoConfig.ENCODING_BLOCK_PACK] or
 * [RawVideoConfig.ENCODING_LJ92]); [sustainedPixelsPerSecond] is what one encoder keeps up with (see
 * [RawQualificationStaging.sustainedEncodeFps]); [conservativeRatio] the compression ratio storage
 * is judged at (see [RawQualificationStaging.conservativeRatio]); [cameraHeld] says the camera kept
 * [config]'s frame rate, with no frame lost on its side, while the encoder ran.
 */
data class RawEncodeBenchFigures(
    val config: RawVideoConfig,
    val sustainedPixelsPerSecond: Double,
    val conservativeRatio: Double,
    val threads: Int,
    val strips: Int,
    val cameraHeld: Boolean = true,
    /** Why the benchmark itself does not count (the phone reached SEVERE heat while it ran); empty when it does. */
    val reasonCodes: List<String> = emptyList(),
    val encoding: String = RawVideoConfig.ENCODING_BLOCK_PACK,
) {
    init {
        require(sustainedPixelsPerSecond >= 0.0) { "pixel rate must not be negative" }
        require(conservativeRatio >= 1.0) { "the ratio storage is judged at is at least 1" }
    }
}

object RawQualificationStaging {
    /** The thermal status a run or check may not reach ([android.os.PowerManager.THERMAL_STATUS_SEVERE]). */
    const val THERMAL_STATUS_SEVERE = 3

    /** The phone reached [THERMAL_STATUS_SEVERE] during the run or check: that configuration fails, nothing is extrapolated from it. */
    const val REASON_THERMAL_LIMIT = "thermal-limit"

    /**
     * Informational, never a reason a verdict fails or is risky for: a compressed configuration whose
     * own long run the heat alone failed ([compressedHeatOnly]) was judged by the extrapolation
     * instead. It goes in the verdict row's `infoCodes` and the record's evidence (`heatFallback`), not
     * in any `reasonCodes`.
     */
    const val REASON_COMPRESSED_HEAT_FALLBACK = "compressed-heat-fallback"

    /** No passing long run of at least this rate exists, so nothing vouches for the storage. */
    const val REASON_STORAGE_NOT_COVERED = "storage-not-covered"

    /** The camera check did not run (its stage was left out). */
    const val REASON_CAMERA_CHECK_MISSING = "camera-check-missing"

    /** The encode benchmark did not run or measured nothing. */
    const val REASON_COMPRESSED_BENCH_MISSING = "compressed-bench-missing"

    /** The encoder keeps up with less than [RawThroughputBenchmark.MARGIN_BASIS_POINTS] of the configuration's pixel rate. */
    const val REASON_COMPRESSED_ENCODE_TOO_SLOW = "compressed-encode-too-slow"

    /** The uncompressed twin was never judged. */
    const val REASON_COMPRESSED_TWIN_MISSING = "compressed-twin-missing"

    /** The camera did not hold the benchmarked configuration's frame rate while the encoder ran. */
    const val REASON_COMPRESSED_CAMERA_STARVED = "compressed-camera-starved"

    /**
     * The reason codes records stored before block-pack carry, by their current name. Nothing keys
     * on them (they are camera or encoder failures either way), so old records classify the same;
     * [canonicalReason] maps them for anything that compares names.
     */
    val LEGACY_REASONS: Map<String, String> = mapOf(
        "lj92-bench-missing" to REASON_COMPRESSED_BENCH_MISSING,
        "lj92-encode-too-slow" to REASON_COMPRESSED_ENCODE_TOO_SLOW,
        "lj92-twin-missing" to REASON_COMPRESSED_TWIN_MISSING,
        "lj92-camera-starved" to REASON_COMPRESSED_CAMERA_STARVED,
    )

    /** [code] by its current name: a legacy `lj92-…` reason ([LEGACY_REASONS]) mapped, any other as it is. */
    fun canonicalReason(code: String): String = LEGACY_REASONS[code] ?: code

    /** The run delivered under [RawQualificationFrameRate.FPS_TOLERANCE] of its target rate. */
    const val REASON_FPS_NOT_REACHED = "fps-not-reached"

    /**
     * Informational: a long run fell short of its rate only while the storage fell behind (its ring
     * overflowed), and a RAM-only camera check of the same configuration held the rate, so the drop
     * is the storage's doing. It replaces [REASON_FPS_NOT_REACHED] and is in no failure set
     * ([RawQualificationRisk.INFORMATIONAL_REASONS]).
     */
    const val REASON_FPS_DROPPED_UNDER_STORAGE_LOAD = "fps-dropped-under-storage-load"

    /**
     * Informational: the camera delivered steadily but under [RawQualificationFrameRate.FPS_TOLERANCE]
     * of its target (a reduced-rate pass, PLAN-071): no frame lost on its side, no capture failure,
     * no declared limit, and the phone no hotter than [REDUCED_RATE_MAX_THERMAL_STATUS]. It replaces
     * [REASON_FPS_NOT_REACHED]; the record's `achievedFps` says the rate. Automatic never picks such a
     * configuration; it can be chosen by hand.
     */
    const val REASON_FPS_REDUCED = "fps-reduced"

    /** The hottest thermal status a reduced-rate pass may reach ([android.os.PowerManager.THERMAL_STATUS_MODERATE]). */
    const val REDUCED_RATE_MAX_THERMAL_STATUS = 2

    /**
     * A session's [codes] with [REASON_FPS_NOT_REACHED] turned informational ([REASON_FPS_REDUCED])
     * when that is the camera's only problem and the session stayed cool: no
     * [RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT] or [RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES],
     * no frames delivered at all ([deliveredFps] 0), no capture failure or lost buffer, and a
     * [thermalPeak] of at most [REDUCED_RATE_MAX_THERMAL_STATUS]. Otherwise [codes] unchanged.
     */
    fun reducedRateCodes(codes: List<String>, deliveredFps: Double, thermalPeak: Int, captureFailed: Int, bufferLost: Int): List<String> {
        if (REASON_FPS_NOT_REACHED !in codes) return codes
        val relaxed = deliveredFps > 0.0 && thermalPeak <= REDUCED_RATE_MAX_THERMAL_STATUS && captureFailed == 0 && bufferLost == 0 &&
            RawQualificationFrameRate.REASON_CAMERA_FPS_LIMIT !in codes && RawQualificationFrameRate.REASON_CAMERA_NO_FRAMES !in codes
        return if (relaxed) codes.map { if (it == REASON_FPS_NOT_REACHED) REASON_FPS_REDUCED else it }.distinct() else codes
    }

    /**
     * A failed long run's [codes] need a camera check to tell the camera from the storage: the ring
     * overflowed and, beside the storage's own reasons, [REASON_FPS_NOT_REACHED] is the only one.
     */
    fun needsStorageLoadCheck(codes: List<String>): Boolean =
        RawThroughputBenchmark.REASON_RING_OVERFLOW in codes && REASON_FPS_NOT_REACHED in codes &&
            codes.all { it == REASON_FPS_NOT_REACHED || it in RawQualificationRisk.STORAGE_REASONS }

    /**
     * The long run's [codes] once its [cameraCheck] ran: with [needsStorageLoadCheck] and a passing
     * check, [REASON_FPS_NOT_REACHED] becomes [REASON_FPS_DROPPED_UNDER_STORAGE_LOAD] (so the verdict
     * is risky, the storage's alone), or [REASON_FPS_REDUCED] when the check itself only held a
     * reduced rate; otherwise null (the codes stand, a camera failure).
     */
    fun storageLoadCodes(codes: List<String>, cameraCheck: RawStageResult?): List<String>? =
        if (needsStorageLoadCheck(codes) && cameraCheck?.passed == true) {
            val replacement = if (cameraCheck.reducedRate) REASON_FPS_REDUCED else REASON_FPS_DROPPED_UNDER_STORAGE_LOAD
            codes.map { if (it == REASON_FPS_NOT_REACHED) replacement else it }.distinct()
        } else null

    /** [result] with its codes replaced by [storageLoadCodes] when the [cameraCheck] clears the camera; else [result]. */
    fun storageLoadResult(result: RawStageResult, cameraCheck: RawStageResult?): RawStageResult =
        if (result.status != RawBenchmarkStatus.FAIL) result
        else storageLoadCodes(result.reasonCodes, cameraCheck)?.let(RawStageResult::fail) ?: result

    /** [q] with its codes replaced by [storageLoadCodes] when the [cameraCheck] clears the camera; else null. */
    fun storageLoadQualification(q: RawVideoQualification, cameraCheck: RawStageResult?): RawVideoQualification? {
        if (q.status != RawBenchmarkStatus.FAIL) return null
        val codes = storageLoadCodes(q.reasonCodes, cameraCheck) ?: return null
        return q.copy(reasonCode = codes.first(), reasonCodes = codes)
    }

    /**
     * The sustained storage reason ([RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED]), applied
     * the same way to every verdict, raw or compressed, long run or extrapolated: the sweep found the
     * write cache's cliff and a sustained rate, and the configuration's storage rate exceeds it, so a
     * take drops frames once the cache is full. Empty otherwise, and when the sustained rate is unknown.
     */
    fun sustainedRateReasons(storageBytesPerSecond: Long, sustained: RawSustainedWrite?): List<String> {
        if (sustained == null || !sustained.sustainedKnown) return emptyList()
        val rate = sustained.sustainedBytesPerSecond ?: return emptyList()
        return if (storageBytesPerSecond > rate) listOf(RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED) else emptyList()
    }

    /**
     * [sustainedRateReasons] at [config]'s storage rate: its stream at the rate the camera achieved
     * ([RawQualificationFrameRate.effectiveStreamBytesPerSecond]), compressed at [ratio] for a twin
     * judged without a long run of its own.
     */
    fun sustainedRateReasons(config: RawVideoConfig, ratio: Double, sustained: RawSustainedWrite?, achievedFps: Double? = null): List<String> =
        sustainedRateReasons(RawStorageCliff.compressedBytesPerSecond(RawQualificationFrameRate.effectiveStreamBytesPerSecond(config, achievedFps), ratio), sustained)

    /**
     * [q] with [sustainedRateReasons] at [config]'s storage rate added: a pass becomes a storage-only
     * failure (risky), a failure gets the reason appended.
     */
    fun withSustainedRate(q: RawVideoQualification, config: RawVideoConfig, ratio: Double, sustained: RawSustainedWrite?, achievedFps: Double? = null): RawVideoQualification =
        withSustainedReasons(q, sustainedRateReasons(config, ratio, sustained, achievedFps))

    /** [q] with the [extra] storage reasons ([sustainedRateReasons]) added; [q] itself when there are none. */
    fun withSustainedReasons(q: RawVideoQualification, extra: List<String>): RawVideoQualification {
        if (extra.isEmpty()) return q
        val codes = RawQualificationFrameRate.primaryFirst((q.reasonCodes.filter { it != RawThroughputBenchmark.REASON_PASS } + extra).distinct())
        return q.copy(status = RawBenchmarkStatus.FAIL, reasonCode = codes.first(), reasonCodes = codes)
    }

    /**
     * [result] without [RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED]: what a long run says about
     * the storage within its own duration, for the descent and the cover. A run risky only past the
     * write cache still kept up for its whole length, so it stops the descent and vouches for lighter
     * rates, each of which [sustainedRateReasons] judges again at its own rate.
     */
    fun withoutSustainedRate(result: RawStageResult): RawStageResult {
        if (result.status != RawBenchmarkStatus.FAIL || RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED !in result.reasonCodes) return result
        val rest = result.reasonCodes.filter { it != RawQualificationRisk.REASON_SUSTAINED_RATE_EXCEEDED }
        return if (rest.all { it in RawQualificationRisk.INFORMATIONAL_REASONS }) RawStageResult.pass(rest) else RawStageResult.fail(rest)
    }

    /**
     * [record] judged again against a [sustained] write the long runs gave
     * ([RawStorageCliff.withLongRunFallback]), when the sweep alone could not judge it: the sustained
     * rule at its [storageBytesPerSecond] ([sustainedRateReasons]), then its burst estimate at
     * [burstRatio] ([RawQualificationRecords.withBurstSeconds]). [record] itself when [sustained] does
     * not come from the long runs, so a verdict judged with a real cliff never changes.
     */
    fun rejudgeWithLongRunSustained(
        record: RawVideoQualificationRecord,
        storageBytesPerSecond: Long,
        burstRatio: Double,
        sustained: RawSustainedWrite?,
    ): RawVideoQualificationRecord {
        if (sustained == null || sustained.source != RawStorageCliff.SOURCE_LONG_RUNS) return record
        val q = withSustainedReasons(record.qualification, sustainedRateReasons(storageBytesPerSecond, sustained))
        return RawQualificationRecords.withBurstSeconds(record.copy(qualification = q), sustained, burstRatio.coerceAtLeast(1.0))
    }

    /** The most configurations one codec's benchmark descent runs on. */
    const val MAX_ENCODE_BENCH_CONFIGS = 3

    /** The margin the encoder needs over a configuration's pixel rate: rule v2's storage margin, 1.1225x. */
    const val ENCODE_MARGIN = RawThroughputBenchmark.MARGIN_BASIS_POINTS / 10_000.0

    /** The reasons a run or check adds for the hottest thermal status it saw. */
    fun thermalReasons(peakThermalStatus: Int): List<String> =
        if (peakThermalStatus >= THERMAL_STATUS_SEVERE) listOf(REASON_THERMAL_LIMIT) else emptyList()

    /**
     * The order of the long runs: uncompressed configurations only, heaviest stream first; equal
     * rates run the higher frame rate first, then by spec so the order is stable. The qualifier leaves
     * out of it every configuration the sweep rules out ([sweepRuledOut]), so the descent starts at the
     * heaviest one the sweep allows.
     */
    fun longRunOrder(runs: List<RawQualificationRun>): List<RawQualificationRun> = runs
        .filter { !it.config.isCompressed }
        .distinctBy { it.config }
        .sortedWith(compareByDescending<RawQualificationRun> { it.config.streamBytesPerSecond }.thenByDescending { it.config.fps }.thenBy { it.spec })

    /** The reasons of rule v2 the sweep alone decides, whatever the ring: [sweepRuledOut] returns only these. */
    val SWEEP_RULING_REASONS: Set<String> = setOf(
        RawThroughputBenchmark.REASON_SWEEP_WINDOW_INVALID,
        RawThroughputBenchmark.REASON_SWEEP_MEDIAN_MARGIN_FAIL,
    )

    /**
     * Why the [sweep] alone rules [config] out, compressed at [compressionRatio]: the reasons of rule v2
     * ([RawThroughputBenchmark.qualifyWithBuffer], with no live run) in [SWEEP_RULING_REASONS], at the
     * configuration's target stream. A long run of it merges these into its own reasons, so it could
     * never pass: it gets no long run, and these reasons decide its verdict. The ring's backlog
     * (`buffer-backlog-exceeded`) depends on the ring size, so it rules nothing out. Empty when the
     * sweep allows it.
     */
    fun sweepRuledOut(config: RawVideoConfig, sweep: RawStorageSweep, compressionRatio: Double = 1.0): List<String> =
        // The ring size only affects the backlog check, filtered out here; any positive size will do.
        RawThroughputBenchmark.qualifyWithBuffer(config, config.bytesPerFrame, sweep, null, compressionRatio)
            .reasonCodes.filter { it in SWEEP_RULING_REASONS }

    /**
     * Why [config] gets no long run when [RawQualificationOptions.skipPredictedLongRuns] is [enabled]: the sweep found the
     * write cache's cliff ([RawSustainedWrite.cliffDetected]) and [config]'s stream at the rate its camera check achieved
     * ([achievedFps]), compressed at [ratio] for a twin, exceeds the sustained rate past it ([sustainedRateReasons]). Such a
     * run could at best be risky; the sustained rule alone decides it. Empty when the option is off, with no cliff (the
     * option then changes nothing), and when the configuration is within the sustained rate.
     */
    fun predictedLongRunSkip(
        enabled: Boolean,
        config: RawVideoConfig,
        sustained: RawSustainedWrite?,
        achievedFps: Double? = null,
        ratio: Double = 1.0,
    ): List<String> =
        if (!enabled || sustained?.cliffDetected != true) emptyList() else sustainedRateReasons(config, ratio, sustained, achievedFps)

    /**
     * [predictedLongRunSkip] for a configuration whose camera check is [cameraCheck] (a compressed twin: its uncompressed
     * twin's): only a check that passed (a reduced rate included) gives the rate the prediction reads, so with a failed,
     * skipped or missing check nothing is predicted and the long-run descent behaves as without the option. The sweep's
     * own exclusions ([sweepRuledOut]) do not depend on it.
     */
    fun predictedLongRunSkip(
        enabled: Boolean,
        cameraCheck: RawStageResult?,
        config: RawVideoConfig,
        sustained: RawSustainedWrite?,
        achievedFps: Double? = null,
        ratio: Double = 1.0,
    ): List<String> =
        if (cameraCheck?.passed != true) emptyList() else predictedLongRunSkip(enabled, config, sustained, achievedFps, ratio)

    /**
     * The verdict a camera-only run ([RawQualificationOptions.cameraOnly]) gives an uncompressed configuration from its
     * [cameraCheck] alone: a pass (a reduced rate included, keeping its informational codes) is a FAIL with
     * [RawQualificationRisk.REASON_STORAGE_NOT_TESTED], so it is risky; a failure keeps the check's reasons; a skipped or
     * missing check leaves it UNKNOWN, never stored.
     */
    fun cameraOnlyVerdict(cameraCheck: RawStageResult?): RawStagedVerdict {
        val method = RawQualificationMethods.CAMERA_ONLY
        return when {
            cameraCheck == null -> RawStagedVerdict(RawBenchmarkStatus.UNKNOWN, listOf(REASON_CAMERA_CHECK_MISSING), method)
            cameraCheck.status == RawBenchmarkStatus.UNKNOWN -> RawStagedVerdict(RawBenchmarkStatus.UNKNOWN, cameraCheck.reasonCodes, method)
            cameraCheck.status == RawBenchmarkStatus.FAIL -> RawStagedVerdict(RawBenchmarkStatus.FAIL, cameraCheck.reasonCodes, method)
            else -> RawStagedVerdict(
                RawBenchmarkStatus.FAIL,
                (listOf(RawQualificationRisk.REASON_STORAGE_NOT_TESTED) + cameraCheck.reasonCodes.filter { it in RawQualificationRisk.INFORMATIONAL_REASONS }).distinct(),
                method,
            )
        }
    }

    /**
     * [compressedVerdict] for a camera-only run: no long run covers anything, so a twin whose camera delivered and whose
     * [bench] encoder keeps up is a FAIL with [RawQualificationRisk.REASON_STORAGE_NOT_TESTED] (risky), any camera or
     * encoder failure keeps its reasons, and a missing twin or benchmark leaves it UNKNOWN.
     */
    fun cameraOnlyCompressedVerdict(twin: RawVideoConfig, twinCamera: RawStageResult?, bench: RawEncodeBenchFigures?): RawStagedVerdict =
        compressedVerdict(twin, twinCamera, bench, emptyMap(), listOf(RawQualificationRisk.REASON_STORAGE_NOT_TESTED))
            .copy(method = RawQualificationMethods.CAMERA_ONLY, coveredBy = null)

    /** The descent goes on after [result] until a long run passes. */
    fun continueDescent(result: RawStageResult): Boolean = !result.passed

    /**
     * The passing long run that vouches for the storage at [streamBytesPerSecond]: the lightest
     * passing one of at least that rate, or null.
     */
    fun coveringRun(streamBytesPerSecond: Long, longRuns: Map<RawVideoConfig, RawStageResult>): RawVideoConfig? =
        longRuns.filter { (config, result) -> result.passed && config.streamBytesPerSecond >= streamBytesPerSecond }
            .keys.minByOrNull { it.streamBytesPerSecond }

    /**
     * The verdict of an uncompressed [config]. [longRuns] holds the long runs that ran (PASS or FAIL);
     * a configuration with its own takes that run's verdict (a storage-only failure is risky), even
     * when a camera check of it ran too: the run already weighed that check
     * ([storageLoadResult]) where its shortfall could be the storage's.
     * Otherwise its [cameraCheck] judges the camera: a failure fails it; with a pass, the [sweepReasons]
     * that ruled it out without a long run ([sweepRuledOut]) fail it (a storage-only failure is risky),
     * and otherwise it is ready only when a passing long run covers its rate ([coveringRun]). A skipped
     * or missing check leaves it UNKNOWN, and so does no cover, with the reason its own long run was
     * skipped for ([longRunSkip], e.g. `insufficient-space`) or [REASON_STORAGE_NOT_COVERED].
     */
    fun uncompressedVerdict(
        config: RawVideoConfig,
        longRuns: Map<RawVideoConfig, RawStageResult>,
        cameraCheck: RawStageResult?,
        longRunSkip: RawStageResult? = null,
        sweepReasons: List<String> = emptyList(),
    ): RawStagedVerdict {
        longRuns[config]?.takeIf { it.status != RawBenchmarkStatus.UNKNOWN }?.let { own ->
            return RawStagedVerdict(own.status, own.reasonCodes, RawQualificationMethods.LONG_RUN)
        }
        val cover = coveringRun(config.streamBytesPerSecond, longRuns)
        val method = RawQualificationMethods.EXTRAPOLATED
        return when {
            cameraCheck == null -> RawStagedVerdict(RawBenchmarkStatus.UNKNOWN, listOf(REASON_CAMERA_CHECK_MISSING), method, cover)
            cameraCheck.status != RawBenchmarkStatus.PASS -> RawStagedVerdict(cameraCheck.status, cameraCheck.reasonCodes, method, cover)
            sweepReasons.isNotEmpty() -> RawStagedVerdict(RawBenchmarkStatus.FAIL, sweepReasons.distinct(), method)
            cover == null -> RawStagedVerdict(RawBenchmarkStatus.UNKNOWN, longRunSkip?.reasonCodes ?: listOf(REASON_STORAGE_NOT_COVERED), method)
            else -> RawStagedVerdict(RawBenchmarkStatus.PASS, emptyList(), method, cover)
        }
    }

    /**
     * What says whether the camera delivers [config]: its camera check when one judged it (the checks
     * run first, on a cool phone), else its own long run when one ran, else the skipped check.
     */
    fun cameraEvidence(config: RawVideoConfig, longRuns: Map<RawVideoConfig, RawStageResult>, cameraChecks: Map<RawVideoConfig, RawStageResult>): RawStageResult? =
        cameraChecks[config]?.takeIf { it.status != RawBenchmarkStatus.UNKNOWN }
            ?: longRuns[config]?.takeIf { it.status != RawBenchmarkStatus.UNKNOWN }
            ?: cameraChecks[config]

    /**
     * Whether the long run of [config] is skipped because its camera check already proved the camera
     * cannot deliver it ([RawStageResult.cameraDelivered] false): a long run would only repeat the
     * failure, hotter. A skipped, missing or passing check keeps the run.
     */
    fun skipLongRun(cameraCheck: RawStageResult?): Boolean =
        cameraCheck != null && cameraCheck.status == RawBenchmarkStatus.FAIL && !cameraCheck.cameraDelivered

    /** The configuration the encode benchmark runs on: the heaviest uncompressed one whose camera delivered, or null. */
    fun benchRun(runs: List<RawQualificationRun>, cameraOk: (RawVideoConfig) -> Boolean): RawQualificationRun? =
        longRunOrder(runs).firstOrNull { cameraOk(it.config) }

    /** Width x height x fps of [config]. */
    fun pixelsPerSecond(config: RawVideoConfig): Double = config.cropWidth.toDouble() * config.cropHeight * config.fps

    /** The encoder keeps up with [config] with [ENCODE_MARGIN] to spare. */
    fun encodeSufficient(config: RawVideoConfig, bench: RawEncodeBenchFigures): Boolean =
        bench.sustainedPixelsPerSecond >= pixelsPerSecond(config) * ENCODE_MARGIN

    /** The rate [config]'s compressed frames reach the storage at [ratio], rounded up. */
    fun compressedBytesPerSecond(config: RawVideoConfig, ratio: Double): Long = ceil(config.streamBytesPerSecond / ratio).toLong()

    /**
     * The verdict of the compressed twin of [twin] (the uncompressed configuration), whose camera
     * evidence is [twinCamera] ([cameraEvidence]). It needs the twin's camera to deliver (a pass, or a
     * long-run failure only the storage explains: a risky twin does not hold its compressed variant
     * back, which writes far less), the [bench] encoder to keep up with the pixel rate
     * ([encodeSufficient]), the camera to have held the benchmark's rate when [twin]'s pixel rate is at
     * least the benchmarked one's ([REASON_COMPRESSED_CAMERA_STARVED]), and a passing long run covering
     * the compressed rate at the benchmark's conservative ratio. Without a cover, any storage-only
     * long-run failure makes it risky, and so do the [sweepReasons] that rule the compressed
     * configuration itself out at that ratio ([sweepRuledOut]), which join the reasons; otherwise it
     * stays UNKNOWN. [longRuns] may hold the configurations the sweep decided without a long run: they
     * failed, so they never cover, but their storage failure counts. A benchmark the heat spoiled fails
     * it with the benchmark's reasons. [bench] is the one of the twin's codec that judges it ([encodeJudges]).
     */
    fun compressedVerdict(
        twin: RawVideoConfig,
        twinCamera: RawStageResult?,
        bench: RawEncodeBenchFigures?,
        longRuns: Map<RawVideoConfig, RawStageResult>,
        sweepReasons: List<String> = emptyList(),
    ): RawStagedVerdict {
        val method = RawQualificationMethods.COMPRESSED_EXTRAPOLATED
        val compressed = twin.copy(encoding = bench?.encoding ?: RawVideoConfig.ENCODING_BLOCK_PACK)
        return when {
            twinCamera == null -> RawStagedVerdict(RawBenchmarkStatus.UNKNOWN, listOf(REASON_COMPRESSED_TWIN_MISSING), method)
            twinCamera.status == RawBenchmarkStatus.UNKNOWN -> RawStagedVerdict(RawBenchmarkStatus.UNKNOWN, twinCamera.reasonCodes, method)
            !twinCamera.cameraOk -> RawStagedVerdict(RawBenchmarkStatus.FAIL, twinCamera.reasonCodes, method)
            bench == null -> RawStagedVerdict(RawBenchmarkStatus.UNKNOWN, listOf(REASON_COMPRESSED_BENCH_MISSING), method)
            bench.reasonCodes.isNotEmpty() -> RawStagedVerdict(RawBenchmarkStatus.FAIL, bench.reasonCodes, method)
            encodeFailures(compressed, bench).isNotEmpty() -> RawStagedVerdict(RawBenchmarkStatus.FAIL, encodeFailures(compressed, bench), method)
            else -> {
                val cover = coveringRun(compressedBytesPerSecond(compressed, bench.conservativeRatio), longRuns)
                val storageFailure = (longRuns.values.filter { it.storageOnlyFailure }.flatMap { it.reasonCodes } + sweepReasons)
                    .filter { it !in RawQualificationRisk.INFORMATIONAL_REASONS }.distinct()
                when {
                    cover != null -> RawStagedVerdict(RawBenchmarkStatus.PASS, emptyList(), method, cover)
                    storageFailure.isNotEmpty() -> RawStagedVerdict(RawBenchmarkStatus.FAIL, storageFailure, method)
                    else -> RawStagedVerdict(RawBenchmarkStatus.UNKNOWN, listOf(REASON_STORAGE_NOT_COVERED), method)
                }
            }
        }
    }

    /** The most compressed twins that get a long run of their own ([COMPRESSED_LONG_RUN]); the rest are extrapolated. */
    const val MAX_COMPRESSED_LONG_RUNS = MAX_ENCODE_BENCH_CONFIGS

    /** [bench] (the one judging [config]'s twin) counts and its encoder keeps up with [config]: no heat, no starved camera, fast enough. */
    fun encodeKeepsUp(config: RawVideoConfig, bench: RawEncodeBenchFigures?): Boolean =
        bench != null && bench.reasonCodes.isEmpty() && encodeFailures(config, bench).isEmpty()

    /**
     * The compressed configurations that get a long run of their own, in the order they run: those
     * among [compressed] whose uncompressed twin's camera delivered ([twinCamera], called with the
     * twin; a reduced rate included, and a run only the heat and the storage failed,
     * [RawStageResult.cameraDelivered]) and whose benchmark ([judge], called with the compressed
     * configuration) keeps up ([encodeKeepsUp]) and which the sweep allows ([sweepAllows], called with
     * the compressed configuration: the qualifier asks [sweepRuledOut] at the benchmark's conservative
     * ratio), heaviest stream first (then the higher frame rate), at most [MAX_COMPRESSED_LONG_RUNS].
     */
    fun compressedLongRunOrder(
        compressed: List<RawVideoConfig>,
        twinCamera: (RawVideoConfig) -> RawStageResult?,
        sweepAllows: (RawVideoConfig) -> Boolean = { true },
        judge: (RawVideoConfig) -> RawEncodeBenchFigures?,
    ): List<RawVideoConfig> = compressed.filter { it.isCompressed }.distinct()
        .filter { config ->
            twinCamera(config.copy(encoding = RawVideoConfig.ENCODING_RAW10))?.cameraDelivered == true && encodeKeepsUp(config, judge(config)) &&
                sweepAllows(config)
        }
        .sortedWith(compareByDescending<RawVideoConfig> { it.streamBytesPerSecond }.thenByDescending { it.fps }
            .thenBy { it.cropWidth.toLong() * it.cropHeight }.thenBy { it.encoding })
        .take(MAX_COMPRESSED_LONG_RUNS)

    /**
     * The verdict of a compressed configuration that ran its own long run ([own], a PASS or FAIL):
     * that run's, by [RawQualificationMethods.COMPRESSED_LONG_RUN]; null for a skipped run and for one
     * the heat alone failed ([compressedHeatOnly]): the caller falls back to [compressedVerdict].
     */
    fun compressedLongRunVerdict(own: RawStageResult?): RawStagedVerdict? =
        own?.takeIf { it.status != RawBenchmarkStatus.UNKNOWN && !compressedHeatOnly(it) }
            ?.let { RawStagedVerdict(it.status, it.reasonCodes, RawQualificationMethods.COMPRESSED_LONG_RUN) }

    /**
     * A compressed long run failed by the heat alone: FAIL with [REASON_THERMAL_LIMIT] and no other
     * reason but informational ones ([RawQualificationRisk.INFORMATIONAL_REASONS]); no frame loss, ring
     * overflow, writer, scan or frame-rate code. Such a run proves nothing about the compressed
     * configuration: it runs last, on the hottest phone, so its verdict comes from its twin's camera
     * and its codec's benchmark ([compressedVerdict]), marked [REASON_COMPRESSED_HEAT_FALLBACK]. An
     * uncompressed long run never falls back: there the heat failure stands.
     */
    fun compressedHeatOnly(result: RawStageResult?): Boolean =
        result != null && result.status == RawBenchmarkStatus.FAIL && REASON_THERMAL_LIMIT in result.reasonCodes &&
            result.reasonCodes.all { it == REASON_THERMAL_LIMIT || it in RawQualificationRisk.INFORMATIONAL_REASONS }

    /** The thermal status ([android.os.PowerManager.THERMAL_STATUS_MODERATE]) at which a compressed twin's long run is not started. */
    const val THERMAL_STATUS_MODERATE = 2

    /**
     * A compressed twin's long run is not started when the phone is already at [thermalStatus] MODERATE or hotter just
     * before it: the run would wait for the heat, then most likely fail for it alone ([compressedHeatOnly]), and such a
     * run is judged by the extrapolation anyway. The twin takes that verdict ([compressedFinalVerdict] with no run of
     * its own), marked [REASON_COMPRESSED_HEAT_FALLBACK]. Below MODERATE the run goes ahead.
     */
    fun compressedHeatSkip(thermalStatus: Int): Boolean = thermalStatus >= THERMAL_STATUS_MODERATE

    /** A long run's measured window ended as soon as its ring overflowed (`stoppedEarly`): it could no longer pass. */
    const val STOPPED_EARLY_RING_OVERFLOW = "ring-overflow"

    /**
     * How long a session's measured window lasted (`measuredSeconds`), in ms: from [startMs] to the first overflow
     * [frozenAtMs] when the reader froze a long run's window there (-1: it did not), never past [endMs]; else to [endMs],
     * when the window ended. The run thread only notices the overflow at its next poll, so the time it stopped measuring
     * would overstate the window.
     */
    fun measuredWindowMs(startMs: Long, frozenAtMs: Long, endMs: Long): Long =
        ((if (frozenAtMs >= startMs) minOf(frozenAtMs, endMs) else endMs) - startMs).coerceAtLeast(0L)

    /** A long run's read-back was not done (`scan.skipped`): the run had already failed ([skipReadBack]). */
    const val SCAN_SKIPPED_RUN_FAILED = "run-already-failed"

    /**
     * A long run's row need not be read back and checked ([com.librestatic.opencinecam.camera.OcrawStreamReader]): its
     * ring overflowed, it lost frames ([lostFrames]) or its writer failed ([writerError]), so rule v2 or the writer
     * check already fails it and the scan of tens of gigabytes would only add wear and time. A skipped scan adds no
     * `ocraw-scan-mismatch`.
     */
    fun skipReadBack(ringOverflow: Boolean, lostFrames: Long, writerError: Boolean): Boolean =
        ringOverflow || lostFrames > 0 || writerError

    /**
     * Rule v2's verdict [q] of a long run whose measured window [stoppedEarly] at its ring overflow: without
     * [RawThroughputBenchmark.REASON_LIVE_RUN_TOO_SHORT], which only says the window was cut short on purpose (the
     * full window would have met the minimum); its other reasons, the overflow among them, stand. [q] itself otherwise,
     * also when the short window is its only reason: a cut window never passes.
     */
    fun earlyStopQualification(q: RawVideoQualification, stoppedEarly: Boolean): RawVideoQualification {
        if (!stoppedEarly || RawThroughputBenchmark.REASON_LIVE_RUN_TOO_SHORT !in q.reasonCodes) return q
        val codes = q.reasonCodes.filter { it != RawThroughputBenchmark.REASON_LIVE_RUN_TOO_SHORT && it != RawThroughputBenchmark.REASON_PASS }
        // A cut window never passes: with no other reason (it was cut for an overflow rule v2 did not see) the short window stands.
        return if (codes.isEmpty()) q else q.copy(reasonCode = codes.first(), reasonCodes = codes)
    }

    /**
     * The verdict of a compressed configuration whose uncompressed twin is [twin] (camera evidence
     * [twinCamera]): its own long run's ([own], [compressedLongRunVerdict]) when that judged it, else the
     * extrapolation from [bench], [longRuns] and [sweepReasons] ([compressedVerdict]): no run, a skipped
     * one, or one the heat alone failed.
     */
    fun compressedFinalVerdict(
        own: RawStageResult?,
        twin: RawVideoConfig,
        twinCamera: RawStageResult?,
        bench: RawEncodeBenchFigures?,
        longRuns: Map<RawVideoConfig, RawStageResult>,
        sweepReasons: List<String> = emptyList(),
    ): RawStagedVerdict = compressedLongRunVerdict(own) ?: compressedVerdict(twin, twinCamera, bench, longRuns, sweepReasons)

    /**
     * What a compressed long run's shortfall under a ring overflow is weighed against
     * ([storageLoadCodes]): its twin's camera evidence [twinCamera] as a check, a pass (with
     * [REASON_FPS_REDUCED] when the twin achieved only [twinReduced]) when the camera delivered the twin,
     * the heat aside ([RawStageResult.cameraDelivered]). The twin's `thermal-limit` never reaches the
     * compressed run's verdict: that run's own thermal peak judges its heat.
     */
    fun twinCameraCheck(twinCamera: RawStageResult?, twinReduced: Boolean): RawStageResult? = when {
        twinCamera == null -> null
        twinCamera.cameraDelivered -> if (twinReduced || twinCamera.reducedRate) RawStageResult.pass(listOf(REASON_FPS_REDUCED)) else RawStageResult.PASS
        else -> twinCamera
    }

    /**
     * The ratio a compressed long run's burst estimate uses: the stream at the rate the camera achieved
     * over the stored bytes per second it measured, never below 1; 1 without a measurement.
     */
    fun measuredRatio(effectiveStreamBytesPerSecond: Long, storedBytesPerSecond: Long?): Double =
        if (storedBytesPerSecond == null || storedBytesPerSecond <= 0) 1.0
        else (effectiveStreamBytesPerSecond.toDouble() / storedBytesPerSecond).coerceAtLeast(1.0)

    /** Why [bench] fails [config] by itself: the camera starved at or above its pixel rate, the encoder too slow. */
    private fun encodeFailures(config: RawVideoConfig, bench: RawEncodeBenchFigures): List<String> = RawQualificationFrameRate.primaryFirst(
        listOfNotNull(
            REASON_COMPRESSED_CAMERA_STARVED.takeIf { !bench.cameraHeld && pixelsPerSecond(config) >= pixelsPerSecond(bench.config) },
            REASON_COMPRESSED_ENCODE_TOO_SLOW.takeIf { !encodeSufficient(config, bench) },
        ),
    )

    /** Where one codec's benchmark descent stands: the bench judging each decided candidate, and whether it stopped. */
    private class EncodeDescent(val judges: Map<RawVideoConfig, RawEncodeBenchFigures?>, val stopped: Boolean)

    /** True when the heat alone spoiled [figures]: its only reason is [REASON_THERMAL_LIMIT]. */
    private fun heatOnly(figures: RawEncodeBenchFigures?): Boolean =
        figures != null && figures.reasonCodes.isNotEmpty() && figures.reasonCodes.all { it == REASON_THERMAL_LIMIT }

    private fun encodeDescent(candidates: List<RawVideoConfig>, benches: Map<RawVideoConfig, RawEncodeBenchFigures?>): EncodeDescent {
        val judges = linkedMapOf<RawVideoConfig, RawEncodeBenchFigures?>()
        fun undecided() = candidates.filter { it !in judges }
        var lastHeatOnly: RawEncodeBenchFigures? = null
        for ((benched, figures) in benches) {
            lastHeatOnly = null
            when {
                // Only the heat spoiled it: it says nothing about lighter configurations, so it judges itself and the
                // descent goes on (the cool-down before the next benchmark gives it a fair start).
                heatOnly(figures) -> {
                    judges.putIfAbsent(benched, figures)
                    lastHeatOnly = figures
                }
                // Nothing measured, any other reason spoiled it, or it holds the camera and keeps up: it judges everything left.
                figures == null || figures.reasonCodes.isNotEmpty() || (figures.cameraHeld && encodeSufficient(benched, figures)) -> {
                    (listOf(benched) + undecided()).forEach { judges.putIfAbsent(it, figures) }
                    return EncodeDescent(judges, stopped = true)
                }
                else -> {
                    // It fails itself, and every candidate its encoder is too slow for, without a benchmark of its own.
                    judges.putIfAbsent(benched, figures)
                    undecided().filter { !encodeSufficient(it, figures) }.forEach { judges[it] = figures }
                }
            }
        }
        // The cap ended a descent the heat kept going: what is left stays thermal-limited, not without a benchmark.
        if (lastHeatOnly != null && benches.size >= MAX_ENCODE_BENCH_CONFIGS) {
            undecided().forEach { judges[it] = lastHeatOnly }
        }
        return EncodeDescent(judges, stopped = false)
    }

    /**
     * The configuration one codec's benchmark descent runs on next, or null when it is over. [candidates]
     * are the uncompressed twins of that codec's configurations whose camera delivered, heaviest first
     * ([longRunOrder]); [benches] the configurations benchmarked so far, in order, with the figures of
     * the entry verdicts use ([verdictBench]; null when it measured nothing). The descent stops at a
     * benchmark that measured nothing, one spoiled for any reason but the heat alone, one that held the
     * camera and kept up with its own configuration, after [MAX_ENCODE_BENCH_CONFIGS] configurations, or
     * when every candidate is decided; otherwise it runs on the heaviest undecided candidate not yet
     * benchmarked. A benchmark whose only reason is [REASON_THERMAL_LIMIT] decides only itself, so the
     * descent goes on to the next lighter candidate.
     */
    fun nextEncodeBenchConfig(candidates: List<RawVideoConfig>, benches: Map<RawVideoConfig, RawEncodeBenchFigures?>): RawVideoConfig? {
        if (benches.size >= MAX_ENCODE_BENCH_CONFIGS) return null
        val descent = encodeDescent(candidates, benches)
        if (descent.stopped) return null
        return candidates.firstOrNull { it !in descent.judges && it !in benches }
    }

    /**
     * The benchmark that judges each of [candidates] (see [nextEncodeBenchConfig] for both arguments):
     * a benchmarked configuration that failed (camera starved or encoder too slow) judges itself and
     * every candidate its encoder is too slow for; one only the heat spoiled judges only itself; the
     * one that stopped the descent judges every candidate still undecided. When the cap ends the descent
     * right after a heat-only benchmark, that benchmark judges every candidate still undecided, which
     * stays thermal-limited. A candidate absent from the map (the cap left it undecided otherwise) or
     * mapped to null has no benchmark ([REASON_COMPRESSED_BENCH_MISSING] in [compressedVerdict]).
     */
    fun encodeJudges(candidates: List<RawVideoConfig>, benches: Map<RawVideoConfig, RawEncodeBenchFigures?>): Map<RawVideoConfig, RawEncodeBenchFigures?> =
        encodeDescent(candidates, benches).judges

    /**
     * The verdict stored for a configuration no long run of its own judged: [base] is rule v2 run on
     * the sweep at its own rate (and ratio) with no live run, which leaves `live-run-missing` alone
     * when the storage keeps up. That reason is dropped; any other reason of [base] (a storage
     * margin a cover should have implied) still fails it, so a stored PASS never contradicts the sweep.
     */
    fun extrapolatedQualification(base: RawVideoQualification, verdict: RawStagedVerdict): RawVideoQualification {
        val sweepReasons = base.reasonCodes.filter { it != RawThroughputBenchmark.REASON_LIVE_RUN_MISSING && it != RawThroughputBenchmark.REASON_PASS }
        val codes = when (verdict.status) {
            RawBenchmarkStatus.PASS -> sweepReasons
            else -> RawQualificationFrameRate.primaryFirst((verdict.reasonCodes + sweepReasons).distinct())
        }
        return if (codes.isEmpty()) {
            base.copy(status = RawBenchmarkStatus.PASS, reasonCode = RawThroughputBenchmark.REASON_PASS, reasonCodes = listOf(RawThroughputBenchmark.REASON_PASS))
        } else {
            base.copy(status = RawBenchmarkStatus.FAIL, reasonCode = codes.first(), reasonCodes = codes)
        }
    }

    /**
     * The frame rate one encoder sustains from the mean encode time of each quarter of the measured
     * window: the slowest quarter's, so heat building up over the window counts against it. Null
     * without a measured quarter.
     */
    fun sustainedEncodeFps(quarterMeanEncodeNs: List<Long>): Double? =
        quarterMeanEncodeNs.filter { it > 0 }.maxOrNull()?.let { 1e9 / it }

    /** The mean of each quarter of [encodeNs] (in encode order); quarters with no frame are 0. */
    fun quarterMeans(encodeNs: LongArray): List<Long> {
        if (encodeNs.isEmpty()) return emptyList()
        return (0 until 4).map { quarter ->
            val from = encodeNs.size * quarter / 4
            val to = encodeNs.size * (quarter + 1) / 4
            if (to > from) (from until to).sumOf { encodeNs[it] } / (to - from) else 0L
        }
    }

    /**
     * The ratio storage is judged at: the worst single frame's ratio of the benchmark, rounded down
     * to a tenth and never below 1. Compression falls with ISO and scene detail, so the aggregate of
     * one scene would flatter; the worst frame of that scene, floored, leaves room for a busier one.
     */
    fun conservativeRatio(minFrameRatio: Double?): Double =
        (minFrameRatio?.takeIf { it.isFinite() && it > 0 }?.let { floor(it * 10.0) / 10.0 } ?: 1.0).coerceAtLeast(1.0)

    /** The benchmark figures of [config] from its measurements, or null when it encoded nothing. */
    fun benchFigures(
        config: RawVideoConfig,
        quarterMeanEncodeNs: List<Long>,
        minFrameRatio: Double?,
        threads: Int,
        strips: Int,
        cameraHeld: Boolean = true,
        reasonCodes: List<String> = emptyList(),
        encoding: String = RawVideoConfig.ENCODING_BLOCK_PACK,
    ): RawEncodeBenchFigures? {
        val fps = sustainedEncodeFps(quarterMeanEncodeNs) ?: return null
        val pixelsPerFrame = config.cropWidth.toLong() * config.cropHeight
        return RawEncodeBenchFigures(config, fps * pixelsPerFrame, conservativeRatio(minFrameRatio), threads, strips, cameraHeld, reasonCodes, encoding)
    }

    /**
     * The camera held a benchmark session's [targetFps]: the camera check's own criteria
     * ([RawQualificationFrameRate.fpsNotReached], frames seen, every capture completed in the window
     * reaching the reader), less the ring overflow, which here is the encoder falling behind.
     */
    fun benchCameraHeld(deliveredFps: Double, targetFps: Int, framesSeen: Long, completedInWindow: Long): Boolean =
        benchCameraHeld(deliveredFps, targetFps.toDouble(), framesSeen, completedInWindow)

    /**
     * [benchCameraHeld] against [expectedFps]: the configuration's target, or the rate its camera
     * evidence achieved when that is a reduced rate ([REASON_FPS_REDUCED]), so the benchmark of a
     * reduced-rate configuration asks the camera to hold what it held without the encoder.
     */
    fun benchCameraHeld(deliveredFps: Double, expectedFps: Double, framesSeen: Long, completedInWindow: Long): Boolean =
        framesSeen > 0 && !RawQualificationFrameRate.fpsNotReached(deliveredFps, expectedFps) && framesSeen == completedInWindow

    /**
     * The `ratioBound` a compressed twin's verdict row and proof carry when its storage was judged as if uncompressed
     * ([extrapolatedStorageRatio]): its benchmark was reused, so its ratio proves nothing about this scene.
     */
    const val RATIO_BOUND_UNCOMPRESSED = "uncompressed"

    /**
     * The compression ratio a compressed twin with no long run of its own (extrapolated: left out of the long-run order,
     * kept from it by the heat, or a run the heat alone failed; and every camera-only twin) is judged at for the storage:
     * the conservative ratio of the [bench] judging it when that benchmark was measured in this run, but 1.0 (as if
     * uncompressed, a bound no scene can break) when it was [benchReused] from an earlier run. Compression depends on the
     * scene, and a reused benchmark's ratio is the one of the scene it was measured on, which a busier scene now may not
     * reach. The reused benchmark still says the encoder keeps up and which twins get a long run, in which order; a twin
     * with its own long run is judged at that run's measured ratio. 1.0 without a benchmark.
     */
    fun extrapolatedStorageRatio(bench: RawEncodeBenchFigures?, benchReused: Boolean): Double =
        if (bench == null || benchReused) 1.0 else bench.conservativeRatio

    /** [bench] as [compressedVerdict] reads it for the storage: its ratio set to [extrapolatedStorageRatio]. */
    fun storageJudgingBench(bench: RawEncodeBenchFigures?, benchReused: Boolean): RawEncodeBenchFigures? =
        bench?.let { if (benchReused) it.copy(conservativeRatio = extrapolatedStorageRatio(it, true)) else it }

    /** The benchmark entry verdicts use: the production thread count when it was measured, else the first. */
    fun verdictBench(entries: List<RawEncodeBenchFigures?>, productionThreads: Int): RawEncodeBenchFigures? =
        entries.firstOrNull { it?.threads == productionThreads } ?: entries.firstOrNull()
}

/**
 * When a heavy session's wait for the thermal status to drop below MODERATE ends before its timeout: the phone has
 * stopped cooling. After a heavy run it rarely gets below MODERATE within the wait, so the wait used to last its whole
 * timeout and the session ran anyway. Every poll samples a continuous temperature signal, the thermal headroom
 * (`PowerManager.getThermalHeadroom`, which falls as the phone cools) while it covers a current window, or else the
 * battery temperature in °C; once the wait has lasted [MIN_WAIT_MS] and the signal fell by less than its minimum drop
 * over the last [WINDOW_MS], waiting on would gain little. The trend never ends a wait at SEVERE or hotter ([endsWait]).
 * The constants are provisional until measured on the Razr.
 */
object RawThermalTrend {
    /** The least a wait lasts before the trend can end it. */
    const val MIN_WAIT_MS = 20_000L

    /** The span the drop is measured over, ending at the latest sample. */
    const val WINDOW_MS = 20_000L

    /** Under this fall of the thermal headroom over [WINDOW_MS], the phone counts as no longer cooling. */
    const val MIN_HEADROOM_DROP = 0.02

    /** Under this fall of the battery temperature (°C) over [WINDOW_MS], the phone counts as no longer cooling. */
    const val MIN_BATTERY_DROP_C = 0.3

    /** Why a thermal wait ended (`thermalWaitEnded`): the status dropped below the threshold, the trend, or the timeout. */
    const val ENDED_BELOW_THRESHOLD = "below-threshold"
    const val ENDED_NOT_COOLING = "not-cooling"
    const val ENDED_TIMEOUT = "timeout"

    /** One reading [value] of a signal, [elapsedMs] after the wait began. */
    data class Sample(val elapsedMs: Long, val value: Double)

    /**
     * The signal of [samples] (in time order; non-finite values ignored) fell by less than [minDrop] from the latest
     * sample at least [WINDOW_MS] before the last one to the last one, and the wait has lasted [elapsedMs] of at least
     * [MIN_WAIT_MS]. False until the samples cover the window.
     */
    fun stoppedCooling(samples: List<Sample>, elapsedMs: Long, minDrop: Double): Boolean {
        if (elapsedMs < MIN_WAIT_MS) return false
        val usable = samples.filter { it.value.isFinite() }
        val latest = usable.lastOrNull() ?: return false
        val from = usable.lastOrNull { it.elapsedMs <= latest.elapsedMs - WINDOW_MS } ?: return false
        return from.value - latest.value < minDrop
    }

    /** The oldest the latest sample of a signal may be, at the wait's [elapsedMs], for its window to count as current. */
    const val MAX_SAMPLE_AGE_MS = 5_000L

    /**
     * [samples] (non-finite values ignored) cover a current window at [elapsedMs]: the latest sample is at most
     * [MAX_SAMPLE_AGE_MS] old, and one at least [WINDOW_MS] before it exists.
     */
    fun currentWindow(samples: List<Sample>, elapsedMs: Long): Boolean {
        val usable = samples.filter { it.value.isFinite() }
        val latest = usable.lastOrNull() ?: return false
        if (elapsedMs - latest.elapsedMs > MAX_SAMPLE_AGE_MS) return false
        return usable.any { it.elapsedMs <= latest.elapsedMs - WINDOW_MS }
    }

    /**
     * [stoppedCooling] on the thermal [headroom] when it covers a current window ([currentWindow]), else on the
     * [batteryC] temperature, so a headroom that gave a few readings and then stopped answering does not hide the
     * battery's trend; false with no usable signal, so the wait runs to its threshold or timeout as before. A headroom
     * with a current window that shows the phone still cooling keeps the wait going, whatever the battery says.
     */
    fun stoppedCooling(headroom: List<Sample>, batteryC: List<Sample>, elapsedMs: Long): Boolean =
        if (currentWindow(headroom, elapsedMs)) stoppedCooling(headroom, elapsedMs, MIN_HEADROOM_DROP)
        else stoppedCooling(batteryC, elapsedMs, MIN_BATTERY_DROP_C)

    /**
     * The trend ends a thermal wait at the [thermalStatus] polled last: only below
     * [RawQualificationStaging.THERMAL_STATUS_SEVERE] and once [stoppedCooling]. At SEVERE or hotter the session could
     * not start (it would be a `thermal-skip`), so the wait goes on until the status permits a start or the timeout.
     */
    fun endsWait(thermalStatus: Int, headroom: List<Sample>, batteryC: List<Sample>, elapsedMs: Long): Boolean =
        thermalStatus < RawQualificationStaging.THERMAL_STATUS_SEVERE && stoppedCooling(headroom, batteryC, elapsedMs)
}
