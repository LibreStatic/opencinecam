/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import com.librestatic.opencinecam.camera.RawBenchmarkStatus
import com.librestatic.opencinecam.camera.RawStorageCliff
import com.librestatic.opencinecam.camera.RawSustainedWrite
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoQualification
import com.librestatic.opencinecam.camera.RawVideoQualificationRecord
import com.librestatic.opencinecam.camera.RawVideoQualificationSet
import kotlin.math.abs
import kotlin.math.ceil

/*
 * OCC-PLAN-071: the pure half of [RawVideoQualifier]: configuration specs, the run plan, timing and
 * space estimates, progress and the qualification records. Nothing here touches Camera2 or storage.
 */

/**
 * One live run of a qualification: a spec resolved against one camera's largest RAW10 output. The
 * crop is centred, its left edge and width multiples of 4 (RAW10 packs four pixels in five bytes)
 * and its top even (the CFA phase), exactly as [RawVideoCrop.centred] places a take's frames.
 */
data class RawQualificationRun(val spec: String, val config: RawVideoConfig, val cropLeft: Int, val cropTop: Int) {
    val isFull: Boolean get() = config.cropWidth == config.sensorWidth && config.cropHeight == config.sensorHeight

    /** The `left,top,WxH` form the per-run JSON carries. */
    val cropLabel: String get() = "$cropLeft,$cropTop,${config.cropWidth}x${config.cropHeight}"
}

/**
 * The configuration spec syntax of RAW video qualification: `full@fps` (the whole RAW10 output) or
 * `WxH@fps` (a centred crop), optionally suffixed for a losslessly compressed twin: `+bp` for the
 * Bayer block-pack codec the app records, or `+lj92` for lossless JPEG (still accepted: records
 * qualified before block-pack, explicit device-test specs).
 */
object RawQualificationSpecs {
    const val BLOCK_PACK_SUFFIX = "+" + RawVideoConfig.ENCODING_BLOCK_PACK
    const val LJ92_SUFFIX = "+" + RawVideoConfig.ENCODING_LJ92

    private val SUFFIXES: Map<String, String> = mapOf(
        RawVideoConfig.ENCODING_BLOCK_PACK to BLOCK_PACK_SUFFIX,
        RawVideoConfig.ENCODING_LJ92 to LJ92_SUFFIX,
    )

    /** The uncompressed configurations the app qualifies by default (PLAN-071 decision 5 crops included). */
    val DEFAULT_RAW10: List<String> = listOf(
        "full@15", "full@24", "full@30", "full@60",
        "3840x2160@30", "3840x2160@60", "1920x1080@30", "1920x1080@60",
    )

    /** The encoding [spec] names: a compressed one from its suffix, else [RawVideoConfig.ENCODING_RAW10]. */
    fun encodingOf(spec: String): String =
        SUFFIXES.entries.firstOrNull { spec.endsWith(it.value, ignoreCase = true) }?.key ?: RawVideoConfig.ENCODING_RAW10

    fun isCompressed(spec: String): Boolean = encodingOf(spec) != RawVideoConfig.ENCODING_RAW10

    fun isLj92(spec: String): Boolean = encodingOf(spec) == RawVideoConfig.ENCODING_LJ92

    /** The suffix of a compressed [encoding]. */
    fun suffixOf(encoding: String): String = requireNotNull(SUFFIXES[encoding]) { "not a compressed RAW video encoding: $encoding" }

    /** The uncompressed twin of a compressed [spec]; an uncompressed one as it is. */
    fun uncompressedTwin(spec: String): String {
        val encoding = encodingOf(spec)
        return if (encoding == RawVideoConfig.ENCODING_RAW10) spec else spec.dropLast(suffixOf(encoding).length)
    }

    /** The [encoding] twin (block-pack by default) of [spec], compressed or not. */
    fun compressedTwin(spec: String, encoding: String = RawVideoConfig.ENCODING_BLOCK_PACK): String =
        uncompressedTwin(spec) + suffixOf(encoding)

    /** A comma-separated list, blanks dropped; null for null input. */
    fun split(text: String?): List<String>? = text?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)

    /**
     * [spec] on camera [cameraId] whose largest RAW10 output is [sensorWidth] x [sensorHeight], or null
     * when it does not parse or the camera cannot carry it (a crop wider or taller than the output,
     * a width that is not a multiple of 4, a non-positive rate).
     */
    fun parse(spec: String, cameraId: String, sensorWidth: Int, sensorHeight: Int): RawQualificationRun? {
        val encoding = encodingOf(spec)
        val plain = uncompressedTwin(spec)
        val (sizePart, fpsPart) = plain.split('@').takeIf { it.size == 2 } ?: return null
        val fps = fpsPart.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val full = sizePart.equals("full", ignoreCase = true)
        val (width, height) = if (full) sensorWidth to sensorHeight else {
            val dims = sizePart.lowercase().split('x').mapNotNull(String::toIntOrNull)
            if (dims.size != 2) return null
            dims[0] to dims[1]
        }
        if (cameraId.isBlank() || sensorWidth <= 0 || sensorHeight <= 0) return null
        if (width <= 0 || height <= 0 || width % 4 != 0 || width > sensorWidth || height > sensorHeight) return null
        val left = (sensorWidth - width) / 2 / 4 * 4
        val top = (sensorHeight - height) / 2 and 1.inv()
        return RawQualificationRun(
            spec = spec,
            config = RawVideoConfig(cameraId, sensorWidth, sensorHeight, width, height, fps, encoding = encoding),
            cropLeft = left,
            cropTop = top,
        )
    }
}

/**
 * The stages of the staged qualification (PLAN-071 decision 10, protocol 3), in the order they run:
 * the storage sweep, the long disk runs in descending stream rate, the RAM-only camera checks of the
 * configurations no long run judged, and the encode benchmark of the compressed twins.
 */
enum class RawQualificationStage {
    SWEEP, LONG, CAMERA, ENCODE;

    companion object {
        val ALL: Set<RawQualificationStage> = entries.toSet()

        /** The stage name [ENCODE] had when only LJ92 was benchmarked; still accepted by [parse]. */
        const val LEGACY_LJ92 = "lj92"

        /**
         * A comma-separated list of stage names (any case, `lj92` an alias of [ENCODE]); null for
         * null input or an unknown name.
         */
        fun parse(text: String?): Set<RawQualificationStage>? {
            val names = RawQualificationSpecs.split(text) ?: return null
            return names.map { name ->
                if (name.equals(LEGACY_LJ92, ignoreCase = true)) ENCODE
                else entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
            }.toSet()
        }
    }
}

/**
 * What a qualification runs. The uncompressed configurations of [specs] (and the twins of explicit
 * compressed specs) take the long-run descent and the camera checks; compressed configurations are
 * judged from an encode benchmark of their codec: the explicit `+bp` and `+lj92` specs, and with
 * [compressedTwins] the block-pack twin of every uncompressed one (only when the native codec is
 * available).
 *
 * [stages] lets the device test run part of the procedure; only a [complete] plan stores records.
 * Each codec's benchmark runs once per [encodeThreads] entry (null: the codec's production count,
 * [RawFrameEncoders.defaultThreads]), each with [encodeStrips] strips; verdicts use the production
 * thread count when it was measured, else the first.
 */
data class RawQualificationPlan(
    val specs: List<String>,
    val compressedTwins: Boolean,
    val stages: Set<RawQualificationStage> = RawQualificationStage.ALL,
    val encodeThreads: List<Int>? = null,
    val encodeStrips: Int = RawFrameEncoders.DEFAULT_STRIPS,
) {
    init {
        require(specs.isNotEmpty()) { "a qualification plan needs at least one spec" }
        require(stages.isNotEmpty()) { "a qualification plan needs at least one stage" }
        require(encodeThreads == null || (encodeThreads.isNotEmpty() && encodeThreads.all { it in RawFrameEncoders.SETTING_RANGE })) {
            "encoder thread counts must be 1..64"
        }
        require(encodeStrips in RawFrameEncoders.SETTING_RANGE) { "encoder strips must be 1..64" }
    }

    /** The thread counts [encoding]'s benchmark runs with. */
    fun threadsFor(encoding: String): List<Int> = encodeThreads ?: listOf(RawFrameEncoders.defaultThreads(encoding))

    /** The stages that run: a long run needs the sweep's figures, so LONG brings SWEEP along. */
    val effectiveStages: Set<RawQualificationStage>
        get() = if (RawQualificationStage.LONG in stages) stages + RawQualificationStage.SWEEP else stages

    /** Every stage runs, so the verdicts are complete and records are stored. */
    val complete: Boolean get() = effectiveStages == RawQualificationStage.ALL

    fun runs(stage: RawQualificationStage): Boolean = stage in effectiveStages

    /** The uncompressed configurations of [runs]: its RAW10 runs and the twins of its compressed ones, once each. */
    fun uncompressedRuns(runs: List<RawQualificationRun>): List<RawQualificationRun> = runs
        .map { if (it.config.isCompressed) it.copy(spec = RawQualificationSpecs.uncompressedTwin(it.spec), config = it.config.copy(encoding = RawVideoConfig.ENCODING_RAW10)) else it }
        .distinctBy { it.config }

    /**
     * The compressed configurations [runs] judges: its `+bp` and `+lj92` runs, then with
     * [compressedTwins] the block-pack twin of every uncompressed one. Empty without [codecAvailable].
     */
    fun compressedRuns(runs: List<RawQualificationRun>, codecAvailable: Boolean): List<RawQualificationRun> {
        if (!codecAvailable) return emptyList()
        val explicit = runs.filter { it.config.isCompressed }
        val twins = if (compressedTwins) uncompressedRuns(runs).map {
            it.copy(spec = RawQualificationSpecs.compressedTwin(it.spec), config = it.config.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK))
        } else emptyList()
        return (explicit + twins).distinctBy { it.config }
    }

    /** The codecs [runs] benchmarks, in [RawVideoConfig.COMPRESSED_ENCODINGS] order (block-pack first). */
    fun benchEncodings(runs: List<RawQualificationRun>, codecAvailable: Boolean): List<String> {
        if (!runs(RawQualificationStage.ENCODE)) return emptyList()
        val present = compressedRuns(runs, codecAvailable).map { it.config.encoding }.toSet()
        return RawVideoConfig.COMPRESSED_ENCODINGS.filter { it in present }
    }

    /**
     * The benchmark sessions the plan counts when the codec is available: one benchmark configuration
     * per codec with a compressed configuration to judge, a session per thread count.
     */
    fun benchSessions(runs: List<RawQualificationRun>, codecAvailable: Boolean): Int =
        benchEncodings(runs, codecAvailable).sumOf { threadsFor(it).size }

    companion object {
        /** The app's plan: [RawQualificationSpecs.DEFAULT_RAW10] and their block-pack twins, every stage. */
        val DEFAULT = RawQualificationPlan(RawQualificationSpecs.DEFAULT_RAW10, compressedTwins = true)

        /** Exactly [specs], `+bp` and `+lj92` ones included, with no added twins (the device test's `rawConfigs`). */
        fun explicit(specs: List<String>) = RawQualificationPlan(specs, compressedTwins = false)
    }
}

/**
 * Durations of a qualification. A long run measures [liveSeconds] after [WARMUP_SECONDS] of warm-up,
 * but a configuration of at least [sustainedBytesPerSecond] measures [sustainedLiveSeconds]: on the
 * S24 a UHD60 take filled the ring after about 30 s, which a 20 s run missed. Both run well past rule
 * v2's 20 s minimum ([RawThroughputBenchmark.LIVE_MIN_DURATION_MS]) so slow storage shows in the
 * ring. A camera check measures [cameraCheckSeconds] after [cameraCheckWarmupSeconds], from the RAM
 * ring alone; an encode benchmark session encodes for [encodeBenchSeconds] after the camera check's
 * warm-up. Estimates use the nominal timings plus fixed overheads; thermal waits are not predicted.
 */
data class RawQualificationTiming(
    val liveSeconds: Int = DEFAULT_LIVE_SECONDS,
    val sustainedLiveSeconds: Int = DEFAULT_SUSTAINED_LIVE_SECONDS,
    val sustainedBytesPerSecond: Long = DEFAULT_SUSTAINED_BYTES_PER_SECOND,
    val sweepWindows: Int = RawThroughputBenchmark.SWEEP_MIN_WINDOWS,
    val cameraCheckSeconds: Int = DEFAULT_CAMERA_CHECK_SECONDS,
    val cameraCheckWarmupSeconds: Int = DEFAULT_CAMERA_CHECK_WARMUP_SECONDS,
    val encodeBenchSeconds: Int = DEFAULT_ENCODE_BENCH_SECONDS,
    /**
     * The most the sweep writes past its [sweepWindows] looking for the write cache's cliff and the
     * windows after it ([RawStorageCliff]); 0 turns that sustained phase off.
     */
    val sweepMaxExtraSeconds: Int = RawStorageCliff.SUSTAINED_MAX_EXTRA_SECONDS,
) {
    init {
        require(liveSeconds > 0 && sustainedLiveSeconds > 0 && sweepWindows > 0) { "durations must be positive" }
        require(sweepMaxExtraSeconds >= 0) { "the sweep's sustained phase must not be negative" }
        require(cameraCheckSeconds > 0 && cameraCheckWarmupSeconds >= 0 && encodeBenchSeconds > 0) { "durations must be positive" }
        require(sustainedBytesPerSecond > 0) { "sustained threshold must be positive" }
    }

    /** Measured seconds of a long run at [config]. */
    fun liveSeconds(config: RawVideoConfig): Int =
        if (config.streamBytesPerSecond >= sustainedBytesPerSecond) sustainedLiveSeconds else liveSeconds

    /** Free bytes a long run at [config] must find before it starts; less skips it as `insufficient-space`. */
    fun neededBytes(config: RawVideoConfig): Long =
        config.streamBytesPerSecond * (liveSeconds(config) + WARMUP_SECONDS + 2) + SPACE_RESERVE_BYTES

    /** Planned milliseconds of one long run, from opening the camera to deleting its row. */
    fun runMs(config: RawVideoConfig): Long = (WARMUP_SECONDS + liveSeconds(config)) * 1_000L + MEASURE_SLACK_MS + RUN_OVERHEAD_MS

    /** Planned milliseconds of one camera check. */
    fun cameraCheckMs(): Long = (cameraCheckWarmupSeconds + cameraCheckSeconds) * 1_000L + MEASURE_SLACK_MS + CHECK_OVERHEAD_MS

    /** Planned milliseconds of one encode benchmark session. */
    fun encodeBenchMs(): Long = (cameraCheckWarmupSeconds + encodeBenchSeconds) * 1_000L + MEASURE_SLACK_MS + CHECK_OVERHEAD_MS

    /**
     * Planned milliseconds of the storage sweep, an upper bound: its burst windows plus the whole
     * sustained phase. A storage whose cliff falls inside the burst windows (the Razr) stops about
     * [RawStorageCliff.SUSTAINED_TAIL_WINDOWS] seconds after them.
     */
    val sweepMs: Long get() = (sweepWindows + sweepMaxExtraSeconds) * 1_000L + SWEEP_OVERHEAD_MS

    companion object {
        const val DEFAULT_LIVE_SECONDS = 40
        const val DEFAULT_SUSTAINED_LIVE_SECONDS = 80
        const val DEFAULT_SUSTAINED_BYTES_PER_SECOND = 500_000_000L
        const val DEFAULT_CAMERA_CHECK_SECONDS = 8
        const val DEFAULT_CAMERA_CHECK_WARMUP_SECONDS = 2
        const val DEFAULT_ENCODE_BENCH_SECONDS = 30
        const val WARMUP_SECONDS = 3

        /** The measured window runs a little past its nominal length so the frame span covers it. */
        const val MEASURE_SLACK_MS = 500L

        /** The pause between two runs. */
        const val COOL_DOWN_MS = 3_000L

        /**
         * The pause between the last camera session (camera checks, encode benchmarks) and the storage
         * sweep, so the storage is measured rested rather than right after the camera work.
         */
        const val STORAGE_REST_MS = 120_000L

        /** Kept free beyond a run's data. */
        const val SPACE_RESERVE_BYTES = 1L shl 30

        /** Camera open and configure, 1 s for in-flight results after the stop, the drain and the scan. */
        const val RUN_OVERHEAD_MS = 3_000L

        /** Camera open and configure and 1 s for in-flight results: a check writes nothing. */
        const val CHECK_OVERHEAD_MS = 2_000L

        /** Opening and deleting the sweep row, the final force. */
        const val SWEEP_OVERHEAD_MS = 2_000L

        /** Allocating the ring. */
        const val RING_OVERHEAD_MS = 2_000L
    }
}

/** How long a plan takes and the free space it needs. */
data class RawQualificationEstimate(
    /**
     * Upper bound: the ring, a camera check of each configuration, the benchmark sessions, the
     * storage rest, the sweep, every long run as if none passed and the cool-downs between steps.
     */
    val durationMs: Long,
    /** The free space the sweep and the largest long run need; the encode benchmark and the camera checks write nothing. */
    val freeBytesNeeded: Long,
    /**
     * The free space the sweep alone needs. It rewinds within [RawVideoQualifier.SWEEP_WRAP_BYTES]
     * unless the volume has room for an unbounded write, so its sustained phase needs no more; an
     * unbounded sweep stops that phase early (`free-space`) rather than eat into the reserve.
     */
    val sweepFreeBytesNeeded: Long,
    /**
     * Steps the ring counts: a camera check per uncompressed configuration, the benchmark sessions
     * and a long run per uncompressed configuration (the rest and the sweep are not steps).
     */
    val runCount: Int,
)

object RawQualificationEstimates {
    /**
     * The estimate of [runs] (uncompressed, the camera-check and long-run candidates) and
     * [benchSessions] encode benchmark sessions under [plan]'s stages, with [timing], in the order the
     * qualifier runs them: the ring, a camera check of every run, the benchmark sessions, the storage
     * rest ([RawQualificationTiming.STORAGE_REST_MS], only after camera sessions and before a sweep),
     * the sweep, then the long runs. The duration is an upper bound: every long run of the descent, as
     * if none passed. [longRuns] (default [runs]) are the long runs counted: [forFreeSpace] leaves out
     * those the free space would skip.
     */
    fun of(
        runs: List<RawQualificationRun>,
        benchSessions: Int,
        timing: RawQualificationTiming,
        stages: Set<RawQualificationStage> = RawQualificationStage.ALL,
        longRuns: List<RawQualificationRun> = runs,
    ): RawQualificationEstimate {
        val long = RawQualificationStage.LONG in stages
        val camera = RawQualificationStage.CAMERA in stages
        val sweep = long || RawQualificationStage.SWEEP in stages
        val before = (if (camera) List(runs.size) { timing.cameraCheckMs() } else emptyList()) +
            List(benchSessions) { timing.encodeBenchMs() }
        val after = if (long) longRuns.map { timing.runMs(it.config) } else emptyList()
        val stepMs = before + after
        val stepsMs = stepMs.sum() + (stepMs.size - 1).coerceAtLeast(0) * RawQualificationTiming.COOL_DOWN_MS
        val rest = if (sweep && before.isNotEmpty()) RawQualificationTiming.STORAGE_REST_MS else 0L
        val sweepBytes = if (sweep) RawVideoQualifier.SWEEP_WRAP_BYTES + (runs.maxOfOrNull { it.config.bytesPerFrame } ?: 0L) else 0L
        val longBytes = if (long) longRuns.maxOfOrNull { timing.neededBytes(it.config) } ?: 0L else 0L
        return RawQualificationEstimate(
            durationMs = RawQualificationTiming.RING_OVERHEAD_MS + rest + (if (sweep) timing.sweepMs else 0L) + stepsMs,
            freeBytesNeeded = maxOf(sweepBytes, longBytes),
            sweepFreeBytesNeeded = sweepBytes,
            runCount = stepMs.size,
        )
    }

    /** The estimate of [plan] on a camera whose largest RAW10 output is [sensorWidth] x [sensorHeight]. */
    fun of(
        plan: RawQualificationPlan,
        cameraId: String,
        sensorWidth: Int,
        sensorHeight: Int,
        codecAvailable: Boolean,
        timing: RawQualificationTiming = RawQualificationTiming(),
    ): RawQualificationEstimate {
        val runs = plan.specs.mapNotNull { RawQualificationSpecs.parse(it, cameraId, sensorWidth, sensorHeight) }
        return of(plan.uncompressedRuns(runs), plan.benchSessions(runs, codecAvailable), timing, plan.effectiveStages)
    }

    /**
     * The estimate of [plan] given [freeBytes] free now: long runs that would be skipped for space
     * ([RawQualificationTiming.neededBytes] above [freeBytes]) are left out of the duration, as the
     * qualifier skips them; every configuration still gets its camera check. Compressed twins need no
     * space of their own.
     */
    fun forFreeSpace(
        plan: RawQualificationPlan,
        cameraId: String,
        sensorWidth: Int,
        sensorHeight: Int,
        codecAvailable: Boolean,
        freeBytes: Long,
        timing: RawQualificationTiming = RawQualificationTiming(),
    ): RawQualificationSpaceEstimate {
        val parsed = plan.specs.mapNotNull { RawQualificationSpecs.parse(it, cameraId, sensorWidth, sensorHeight) }
        val runs = RawQualificationStaging.longRunOrder(plan.uncompressedRuns(parsed))
        val kept = runs.filter { timing.neededBytes(it.config) <= freeBytes }
        val bench = plan.benchSessions(parsed, codecAvailable)
        val sweepNeeded = of(runs, 0, timing).sweepFreeBytesNeeded
        val smallestRun = runs.minOfOrNull { timing.neededBytes(it.config) } ?: 0L
        return RawQualificationSpaceEstimate(
            estimate = if (kept.isEmpty()) of(kept, 0, timing, plan.effectiveStages) else of(runs, bench, timing, plan.effectiveStages, kept),
            freeBytes = freeBytes,
            skippedRuns = runs.size - kept.size,
            minimumFreeBytes = maxOf(sweepNeeded, smallestRun),
            configs = runs.map { run ->
                val needed = timing.neededBytes(run.config)
                RawQualificationPlannedConfig(run.spec, run.config, needed, fits = needed <= freeBytes)
            },
            fullPlanFreeBytes = of(runs, bench, timing, plan.effectiveStages).freeBytesNeeded,
        )
    }
}

/**
 * [RawQualificationEstimates.forFreeSpace]: [estimate] covers only the long runs [freeBytes] has room
 * for; [skippedRuns] of the plan's long runs are left out. Below [minimumFreeBytes] (the sweep, or
 * the smallest long run) nothing can be qualified at all.
 */
data class RawQualificationSpaceEstimate(
    val estimate: RawQualificationEstimate,
    val freeBytes: Long,
    val skippedRuns: Int,
    val minimumFreeBytes: Long,
    /** The plan's uncompressed configurations in long-run order, heaviest first (compressed twins are judged later, needing no space). */
    val configs: List<RawQualificationPlannedConfig> = emptyList(),
    /** The free space the whole plan needs: what [freeBytes] is measured against. */
    val fullPlanFreeBytes: Long = 0L,
) {
    val enoughSpace: Boolean get() = freeBytes >= minimumFreeBytes
}

/**
 * One configuration of a plan before it runs: [neededBytes] is [RawQualificationTiming.neededBytes],
 * the figure the qualifier skips a long run on, and [fits] whether the free space holds it now.
 */
data class RawQualificationPlannedConfig(val spec: String, val config: RawVideoConfig, val neededBytes: Long, val fits: Boolean)

/** The steps a qualification reports. */
enum class RawQualificationPhase { SWEEP, RING, THERMAL_WAIT, RUN, CAMERA_CHECK, ENCODE_BENCH, COOLING, STORAGE_REST, SAVING, DONE }

/**
 * One progress report. [runIndex] is 1-based and [runCount] the steps planned so far (it shrinks once
 * the descent stops); both are 0 outside [RawQualificationPhase.RUN], [RawQualificationPhase.CAMERA_CHECK],
 * [RawQualificationPhase.ENCODE_BENCH], [RawQualificationPhase.THERMAL_WAIT] and [RawQualificationPhase.COOLING],
 * except that [RawQualificationPhase.STORAGE_REST] and a [RawQualificationPhase.SWEEP] after camera
 * sessions carry the last session's, so the count does not drop back to a percentage.
 * [fraction] is 0..1 and never goes back. [liveFps] is the rate the camera delivers in a running
 * camera session, over the last [RawQualificationLiveRate.WINDOW_NS] ([RawQualificationLiveRate.fps]);
 * null outside one (the sweep, cool-downs) and until two measured frames arrived.
 */
data class RawQualificationProgress(
    val phase: RawQualificationPhase,
    val fraction: Double,
    val runIndex: Int = 0,
    val runCount: Int = 0,
    val spec: String? = null,
    val liveFps: Double? = null,
)

/**
 * The live frame rate a qualification shows while a camera session runs. Display only: no verdict
 * reads it (those use the whole measured window).
 */
object RawQualificationLiveRate {
    /** The rolling window the rate covers. */
    const val WINDOW_NS = 2_000_000_000L

    /** How often the qualifier recomputes it. */
    const val INTERVAL_MS = 1_000L

    /**
     * The rate of the frames whose timestamps [timestampNs] gives at indices 0 until [count] (ascending;
     * entries not written yet read 0 or less and are ignored at the end), over the last [windowNs]:
     * frame intervals in the window over its span. [sinceLastNs] is the time since the last frame
     * arrived; beyond one average interval it counts as a gap, so a stalled camera reads lower and
     * then 0. Null with fewer than two frames.
     */
    fun fps(count: Int, timestampNs: (Int) -> Long, windowNs: Long = WINDOW_NS, sinceLastNs: Long = 0L): Double? {
        var n = count
        while (n > 0 && timestampNs(n - 1) <= 0L) n--
        if (n < 2) return null
        val last = timestampNs(n - 1)
        // The oldest frame in the window ending at the last frame, keeping at least one interval.
        val first = minOf(firstInWindow(n, last, windowNs, timestampNs), n - 2)
        val span = last - timestampNs(first)
        if (span <= 0L) return null
        val gap = sinceLastNs - span / (n - 1 - first)
        if (gap <= 0L) return (n - 1 - first) * 1e9 / span
        // A stall: the window ends [gap] after the last frame.
        val end = last + gap
        val stalledFirst = firstInWindow(n, end, windowNs, timestampNs)
        if (stalledFirst >= n - 1) return 0.0
        return (n - 1 - stalledFirst) * 1e9 / (end - timestampNs(stalledFirst))
    }

    /** The index of the oldest of the [n] frames no older than [windowNs] before [endNs] (n - 1 when only the last, or none). */
    private fun firstInWindow(n: Int, endNs: Long, windowNs: Long, timestampNs: (Int) -> Long): Int {
        var first = n - 1
        while (first > 0 && endNs - timestampNs(first - 1) <= windowNs) first--
        return first
    }
}

/**
 * The overall 0..1 fraction from planned durations: completed steps count their planned time, the
 * current step its elapsed time capped at its plan. Replanning (the descent stopping) changes the
 * total; the fraction never decreases. Not thread-safe: one qualification thread drives it.
 */
class RawQualificationProgressMeter(totalMs: Long) {
    var totalMs: Long = totalMs.coerceAtLeast(1)
        private set
    private var doneMs = 0L
    private var stepMs = 0L
    private var last = 0.0

    fun startStep(plannedMs: Long) { stepMs = plannedMs.coerceAtLeast(0) }

    fun finishStep() { doneMs += stepMs; stepMs = 0 }

    /** A step the plan did not count, of [ms], was added. */
    fun extend(ms: Long) { totalMs += ms.coerceAtLeast(0) }

    /** A step the plan counted, of [ms], will not run; the total never drops below the work done. */
    fun shrink(ms: Long) { totalMs = maxOf(doneMs + stepMs, totalMs - ms.coerceAtLeast(0)).coerceAtLeast(1) }

    /** The work left changed to [remainingMs] after the steps done so far. */
    fun replan(remainingMs: Long) { totalMs = (doneMs + stepMs + remainingMs.coerceAtLeast(0)).coerceAtLeast(1) }

    fun fraction(elapsedInStepMs: Long = 0): Double {
        val value = ((doneMs + elapsedInStepMs.coerceIn(0, stepMs)).toDouble() / totalMs).coerceIn(0.0, 1.0)
        last = maxOf(last, value)
        return last
    }

    fun complete(): Double { last = 1.0; return last }
}

/** The overall verdict of a qualification. */
enum class RawQualificationStatus {
    /** At least one configuration passed. */
    PASS,
    /** None passed and at least one failed. */
    FAIL,
    /** Nothing was judged (no RAW10 camera, every run skipped, an exception). */
    UNKNOWN,
    /** [RawVideoQualifier.cancel] stopped it; nothing was stored. */
    CANCELLED,
}

/**
 * Whether a run's frame rate failure is the camera's: the HAL's declared limit, or no frames at all.
 * The declaration is only an explanation, never a reason to skip a run: the S24 declares 30 fps for
 * RAW10 and delivers 60.
 */
object RawQualificationFrameRate {
    /** A run reaches its target when it delivers at least this fraction of it. */
    const val FPS_TOLERANCE = 0.875

    /** The rate was not reached and the camera declares it cannot deliver it. */
    const val REASON_CAMERA_FPS_LIMIT = "camera-fps-limit"

    /** No frame arrived in the whole run, and the camera declares no limit that explains it. */
    const val REASON_CAMERA_NO_FRAMES = "camera-no-frames"

    /**
     * The highest rate the camera declares for the run's streams, from [minFrameDurationNs] (the
     * larger of the RAW10 and preview streams' minimum frame durations at their sizes); null when it
     * declares none (0 or less).
     */
    fun declaredMaxFps(minFrameDurationNs: Long): Double? = if (minFrameDurationNs > 0) 1e9 / minFrameDurationNs else null

    fun fpsNotReached(deliveredFps: Double, targetFps: Int): Boolean = fpsNotReached(deliveredFps, targetFps.toDouble())

    fun fpsNotReached(deliveredFps: Double, targetFps: Double): Boolean = deliveredFps < FPS_TOLERANCE * targetFps

    /**
     * A reduced-rate configuration (PLAN-071): its [achievedFps] is under [FPS_TOLERANCE] of [targetFps].
     * An unknown rate (records stored before `achievedFps`) reads as reaching the target.
     */
    fun isReducedRate(achievedFps: Double?, targetFps: Int): Boolean = achievedFps != null && fpsNotReached(achievedFps, targetFps)

    /** [record] passed or is risky at a reduced rate: its `achievedFps` says so, or its codes do. */
    fun isReducedRate(record: RawVideoQualificationRecord): Boolean =
        isReducedRate(record.achievedFps, record.config.fps) || RawQualificationStaging.REASON_FPS_REDUCED in record.qualification.reasonCodes

    /**
     * The bytes per second [config] streams at the rate the camera achieved: [RawVideoConfig.streamBytesPerSecond]
     * unless [achievedFps] is a reduced rate ([isReducedRate]), then its frame size times that rate.
     */
    fun effectiveStreamBytesPerSecond(config: RawVideoConfig, achievedFps: Double?): Long =
        if (achievedFps != null && isReducedRate(achievedFps, config.fps)) ceil(config.bytesPerFrame * achievedFps).toLong()
        else config.streamBytesPerSecond

    /** [fps] to one decimal, as records keep it. */
    fun roundFps(fps: Double): Double = Math.round(fps * 10.0) / 10.0

    /** [deliveredFps] fell short of [targetFps] and so does the declared rate of [minFrameDurationNs]. */
    fun isCameraLimit(minFrameDurationNs: Long, targetFps: Int, deliveredFps: Double): Boolean {
        val declared = declaredMaxFps(minFrameDurationNs) ?: return false
        return fpsNotReached(deliveredFps, targetFps) && declared < FPS_TOLERANCE * targetFps
    }

    /**
     * The camera reasons of a run: [REASON_CAMERA_FPS_LIMIT], else [REASON_CAMERA_NO_FRAMES] when
     * [framesSeen] is 0, else none. A run that sent the vendor frame-rate override
     * ([qtiFrameRateOverride], the Razr) asked past the declared limit on purpose, so the declaration
     * explains nothing there: a shortfall stays a plain `fps-not-reached` failure, never a camera limit.
     */
    fun cameraReasons(
        minFrameDurationNs: Long,
        targetFps: Int,
        deliveredFps: Double,
        framesSeen: Int,
        qtiFrameRateOverride: Boolean = false,
    ): List<String> = when {
        !qtiFrameRateOverride && isCameraLimit(minFrameDurationNs, targetFps, deliveredFps) -> listOf(REASON_CAMERA_FPS_LIMIT)
        framesSeen == 0 -> listOf(REASON_CAMERA_NO_FRAMES)
        else -> emptyList()
    }

    /** [codes] with [REASON_CAMERA_FPS_LIMIT], or else [REASON_CAMERA_NO_FRAMES], moved first; the rest keep their order. */
    fun primaryFirst(codes: List<String>): List<String> {
        val primary = codes.firstOrNull { it == REASON_CAMERA_FPS_LIMIT } ?: codes.firstOrNull { it == REASON_CAMERA_NO_FRAMES } ?: return codes
        return listOf(primary) + codes.filter { it != primary }
    }
}

/**
 * Risky configurations: the camera delivers them, but the storage is too slow for their rate. A
 * stored FAIL whose every reason is a storage or flash one ([STORAGE_REASONS]) stays choosable by
 * hand, with a warning, and is never picked automatically. Any other reason (a camera limit, heat,
 * a capture or writer fault, an invalid sweep or live run) keeps it failed. It is read from the
 * stored reason codes, so records measured before this existed are classified the same way.
 */
object RawQualificationRisk {
    /**
     * A configuration judged without a long run of its own (a camera check or the encode benchmark,
     * extrapolated from a heavier pass) streams faster than the sweep's sustained rate past the
     * write cache's cliff ([RawQualificationStaging.sustainedRateReasons]): it drops frames once the
     * cache is full.
     */
    const val REASON_SUSTAINED_RATE_EXCEEDED = "sustained-rate-exceeded"

    /** The reasons that only say the storage could not keep up: rule v2's, and [REASON_SUSTAINED_RATE_EXCEEDED]. */
    val STORAGE_REASONS: Set<String> = setOf(
        RawThroughputBenchmark.REASON_SWEEP_MEDIAN_MARGIN_FAIL,
        RawThroughputBenchmark.REASON_BUFFER_BACKLOG_EXCEEDED,
        RawThroughputBenchmark.REASON_RAW_FRAME_LOSS,
        RawThroughputBenchmark.REASON_RING_OVERFLOW,
        RawThroughputBenchmark.REASON_RING_PEAK_EXCEEDED,
        REASON_SUSTAINED_RATE_EXCEEDED,
    )

    /**
     * Codes that explain a verdict without being a failure of their own; [isStorageOnly] ignores them
     * ([RawQualificationStaging.REASON_FPS_DROPPED_UNDER_STORAGE_LOAD], [RawQualificationStaging.REASON_FPS_REDUCED]).
     */
    val INFORMATIONAL_REASONS: Set<String> = setOf(
        RawQualificationStaging.REASON_FPS_DROPPED_UNDER_STORAGE_LOAD,
        RawQualificationStaging.REASON_FPS_REDUCED,
    )

    /**
     * True when [codes] holds at least one [STORAGE_REASONS] code and every code is one, or
     * informational ([INFORMATIONAL_REASONS]).
     */
    fun isStorageOnly(codes: List<String>): Boolean {
        val failures = codes.filter { it !in INFORMATIONAL_REASONS }
        return failures.isNotEmpty() && failures.all { it in STORAGE_REASONS }
    }

    /** A failed verdict whose reasons are all the storage's. */
    fun isRisky(qualification: RawVideoQualification): Boolean =
        qualification.status == RawBenchmarkStatus.FAIL && isStorageOnly(qualification.reasonCodes)

    /**
     * The risky configurations of [cameraId] among [records]: like [RawVideoQualificationSet.qualifiedConfigs],
     * stale records are ignored and only the newest record of each configuration counts.
     */
    fun riskyConfigs(
        records: RawVideoQualificationSet,
        fingerprint: String,
        protocolVersion: Int,
        destinationId: String,
        cameraId: String,
    ): List<RawVideoConfig> = newest(records, fingerprint, protocolVersion, destinationId, cameraId) { isRisky(it) }

    /**
     * The configurations of [cameraId] whose newest current record failed for something other than
     * the storage (the camera, heat, a fault): their compressed twins stay unusable (`rawVideoUsableConfigs`).
     */
    fun cameraFailedConfigs(
        records: RawVideoQualificationSet,
        fingerprint: String,
        protocolVersion: Int,
        destinationId: String,
        cameraId: String,
    ): List<RawVideoConfig> = newest(records, fingerprint, protocolVersion, destinationId, cameraId) {
        it.status == RawBenchmarkStatus.FAIL && !isRisky(it)
    }

    private fun newest(
        records: RawVideoQualificationSet,
        fingerprint: String,
        protocolVersion: Int,
        destinationId: String,
        cameraId: String,
        predicate: (RawVideoQualification) -> Boolean,
    ): List<RawVideoConfig> = records.current(fingerprint, protocolVersion)
        .filter { it.destinationId == destinationId && it.config.cameraId == cameraId }
        .groupBy { it.config }
        .mapNotNull { (config, entries) -> config.takeIf { predicate(entries.maxBy(RawVideoQualificationRecord::measuredAtEpochMs).qualification) } }
}

object RawQualificationRecords {
    /**
     * The verdict a record stores for one live run: rule v2's [v2], turned to FAIL with
     * [extraReasons] appended when the run's own checks failed (frame rate not reached, writer error
     * or hang, OCRAW scan mismatch, timestamp capacity). Those make the run's status FAIL in the
     * report, so a stored PASS must not outlive them. A camera reason leads (see
     * [RawQualificationFrameRate.primaryFirst]), so the reason code explains the failure. Informational
     * extras ([RawQualificationRisk.INFORMATIONAL_REASONS], e.g. a reduced rate) fail nothing: a pass
     * stays a pass without them (the record's `achievedFps` tells), a failure lists them.
     */
    fun storedQualification(v2: RawVideoQualification, extraReasons: List<String>): RawVideoQualification {
        val failing = extraReasons.filter { it !in RawQualificationRisk.INFORMATIONAL_REASONS }
        if (extraReasons.isEmpty() || (failing.isEmpty() && v2.status == RawBenchmarkStatus.PASS)) return v2
        val codes = RawQualificationFrameRate.primaryFirst((v2.reasonCodes.filter { it != RawThroughputBenchmark.REASON_PASS } + extraReasons).distinct())
        return v2.copy(status = if (failing.isEmpty()) v2.status else RawBenchmarkStatus.FAIL, reasonCode = codes.first(), reasonCodes = codes)
    }

    /**
     * The record [RawVideoQualificationStore] persists for one judged configuration. [method] says how
     * ([RawQualificationMethods]), [coveredBy] names the passing long run's spec when another run
     * vouches for its storage, and [evidence] is the compact JSON of the camera check (and benchmark).
     */
    fun forRun(
        fingerprint: String,
        destinationId: String,
        config: RawVideoConfig,
        ringBytes: Long,
        v2: RawVideoQualification,
        extraReasons: List<String>,
        measuredAtEpochMs: Long,
        method: String? = RawQualificationMethods.LONG_RUN,
        coveredBy: String? = null,
        evidence: String? = null,
        achievedFps: Double? = null,
    ): RawVideoQualificationRecord = RawVideoQualificationRecord(
        fingerprint = fingerprint,
        protocolVersion = RawThroughputBenchmark.PROTOCOL_VERSION,
        destinationId = destinationId,
        config = config,
        ringBytes = ringBytes,
        qualification = storedQualification(v2, extraReasons),
        measuredAtEpochMs = measuredAtEpochMs,
        method = method,
        coveredBy = coveredBy,
        evidence = evidence,
        achievedFps = achievedFps,
    )

    /**
     * [record] with its [RawVideoQualificationRecord.burstSeconds] when it is risky
     * ([RawQualificationRisk.isRisky]): how long a take at its rate lasts before the storage's write
     * cache is full and the ring overflows, from the sweep's [sustained] phase ([RawStorageCliff.burstSeconds]),
     * at the stream the camera achieved ([RawQualificationFrameRate.effectiveStreamBytesPerSecond]).
     * A compressed configuration passes the [compressionRatio] its storage is judged at. Any other record,
     * or one the cache does not limit, keeps a null.
     */
    fun withBurstSeconds(record: RawVideoQualificationRecord, sustained: RawSustainedWrite?, compressionRatio: Double = 1.0): RawVideoQualificationRecord =
        if (!RawQualificationRisk.isRisky(record.qualification)) record.copy(burstSeconds = null)
        else record.copy(burstSeconds = RawStorageCliff.burstSeconds(sustained,
            RawQualificationFrameRate.effectiveStreamBytesPerSecond(record.config, record.achievedFps), record.ringBytes, compressionRatio))

    /** The overall status of run statuses (`PASS`/`FAIL`/`UNKNOWN`), as the report computes it. */
    fun overallStatus(runStatuses: List<String>): RawQualificationStatus = when {
        runStatuses.any { it == RawBenchmarkStatus.PASS.name } -> RawQualificationStatus.PASS
        runStatuses.any { it == RawBenchmarkStatus.FAIL.name } -> RawQualificationStatus.FAIL
        else -> RawQualificationStatus.UNKNOWN
    }
}

/**
 * The viewfinder stream size of the RAW_VIDEO session: the engine feeds its GPU viewfinder at
 * `Camera2CameraDescriptor.previewSize` (RAW_VIDEO sets no video profile), chosen by `previewSizeIndex`
 * in `:camera`: among sizes of at most 1920 x 1080 pixels (all of them when none is), the aspect
 * nearest 16:9, then the pixel count nearest 1920 x 1080. Mirrored here because that one is internal.
 */
fun rawQualificationPreviewSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? {
    if (sizes.isEmpty()) return null
    val bounded = sizes.filter { it.first.toLong() * it.second <= PREVIEW_PIXELS }.ifEmpty { sizes }
    return bounded.minWith(compareBy<Pair<Int, Int>> { (w, h) -> abs(maxOf(w, h).toDouble() / minOf(w, h) - 16.0 / 9.0) }
        .thenBy { (w, h) -> abs(w.toLong() * h - PREVIEW_PIXELS) })
}

private const val PREVIEW_PIXELS = 1920L * 1080L
