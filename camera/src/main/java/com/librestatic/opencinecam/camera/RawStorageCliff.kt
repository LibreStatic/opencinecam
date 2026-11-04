/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.ceil
import kotlin.math.floor

/**
 * What a storage sweep's sustained phase found about the write cache (PLAN-071, the SLC cliff).
 * [burstBytesPerSecond] is the median of the first [RawStorageCliff.detect] `burstWindows` windows,
 * the figure rule v2 reads. With [cliffDetected], [cliffWindow] is the index of the first window past
 * the cache, [cacheBytes] what was written before it and [sustainedBytesPerSecond] the median from it
 * on (null with fewer than [RawStorageCliff.MIN_SUSTAINED_WINDOWS] of them). Without a cliff,
 * [cacheBytesAtLeast] is everything written and [sustainedBytesPerSecond] is the burst rate.
 * [windows] counts every window measured, [windowsAfterCliff] those from [cliffWindow] on.
 *
 * [source] says where the sustained rate comes from: the sweep ([RawStorageCliff.SOURCE_SWEEP]), or,
 * when the sweep found no cliff, the long runs that fell behind the storage
 * ([RawStorageCliff.SOURCE_LONG_RUNS], [RawStorageCliff.withLongRunFallback]): then [cacheBytes] is
 * the sweep's [cacheBytesAtLeast], a lower bound, [derivedFrom] names those runs, and
 * [floorBytesPerSecond] is the highest rate a later run kept up with ([floorFrom] names it), or null.
 */
data class RawSustainedWrite(
    val cliffDetected: Boolean,
    val cliffWindow: Int?,
    val cacheBytes: Long?,
    val cacheBytesAtLeast: Long?,
    val burstBytesPerSecond: Long?,
    val sustainedBytesPerSecond: Long?,
    val windows: Int,
    val windowsAfterCliff: Int,
    val source: String = RawStorageCliff.SOURCE_SWEEP,
    val derivedFrom: List<String> = emptyList(),
    val floorBytesPerSecond: Long? = null,
    val floorFrom: String? = null,
) {
    /** The rules can use [cacheBytes] and [sustainedBytesPerSecond]: the sweep saw the cliff, or the long runs gave the rate. */
    val sustainedKnown: Boolean get() = cliffDetected || source == RawStorageCliff.SOURCE_LONG_RUNS
}

/**
 * One long run's word on the storage, for [RawStorageCliff.withLongRunFallback]: the rate its writer
 * achieved ([bytesPerSecond]), whether it fell behind the storage ([fellBehind]: a ring overflow or
 * the ring's peak exceeded), the hottest thermal status it saw ([thermalPeak], a `PowerManager`
 * status, null when unknown) and the rate it had to store ([storageBytesPerSecond]: its stream at the
 * achieved rate, or a compressed run's measured stored rate; null when unknown).
 */
data class RawWriteEvidence(
    val spec: String,
    val bytesPerSecond: Long,
    val fellBehind: Boolean,
    val thermalPeak: Int? = null,
    val storageBytesPerSecond: Long? = null,
) {
    /** The heat reached SEVERE during the run, so the heat, not the storage, may have slowed its writer. */
    val severe: Boolean get() = (thermalPeak ?: 0) >= RawStorageCliff.THERMAL_STATUS_SEVERE
}

/**
 * Flash storage with an SLC write cache writes fast until the cache is full, then falls to its
 * sustained rate (the Razr Fold: about 1.2 GB/s for 56 s, then 270–880 MB/s). The sweep keeps
 * writing past its burst windows until it sees the cliff and [SUSTAINED_TAIL_WINDOWS] windows after
 * it, so the long runs that follow always write past the cache, and a risky configuration can say
 * how long a take lasts before it drops frames ([burstSeconds]).
 */
object RawStorageCliff {
    /** Windows measured after the cliff before the sweep stops. */
    const val SUSTAINED_TAIL_WINDOWS = 15

    /** The most the sweep writes past its burst windows looking for the cliff and its tail. */
    const val SUSTAINED_MAX_EXTRA_SECONDS = 90

    /**
     * The most the whole sweep writes, burst windows included, 96 GB: past it the sustained phase
     * stops ([STOP_MAX_BYTES]) even without a cliff, so fast flash with a very large write cache does
     * not wear for nothing. Without a cliff the sustained rate stays unknown and the cache is at
     * least what was written ([RawSustainedWrite.cacheBytesAtLeast]).
     */
    const val SUSTAINED_MAX_TOTAL_BYTES = 96_000_000_000L

    /** A window belongs past the cliff below this fraction of the burst median. */
    const val CLIFF_FRACTION = 0.75

    /** The rolling median spans this many windows. */
    const val ROLLING_WINDOWS = 5

    /** The sustained rate needs at least this many windows past the cliff; fewer leave it unknown. */
    const val MIN_SUSTAINED_WINDOWS = 10

    const val STOP_TAIL_COMPLETE = "tail-complete"
    const val STOP_MAX_DURATION = "max-duration"
    const val STOP_FREE_SPACE = "free-space"
    const val STOP_DISABLED = "disabled"
    const val STOP_MAX_BYTES = "max-bytes"

    /** The burst windows found the write cache unrested ([RawStorageRest]): the sweep stops there, with no sustained phase. */
    const val STOP_NOT_RESTED = "not-rested"

    const val SOURCE_SWEEP = "sweep"
    const val SOURCE_LONG_RUNS = "long-runs"

    /** `PowerManager.THERMAL_STATUS_SEVERE`, which this module cannot reference. */
    const val THERMAL_STATUS_SEVERE = 3

    /**
     * [sustained] with a sustained rate from the long runs when the sweep found no cliff (it stopped
     * at `max-bytes`, `max-duration` or `free-space` first): the slowest rate a writer achieved among
     * the runs that fell behind the storage ([RawWriteEvidence.fellBehind]), the cache at least what
     * the sweep wrote. Those runs follow the sweep at once, so they wrote past whatever cache the
     * sweep could not exhaust. A run that reached SEVERE ([RawWriteEvidence.severe]) does not count:
     * the heat may have slowed its writer. [evidence] is in run order: a run after the first counted
     * one that kept up proves the storage sustains its [RawWriteEvidence.storageBytesPerSecond], so the
     * rate is never below the highest such floor ([RawSustainedWrite.floorBytesPerSecond]). [sustained]
     * itself with a real cliff, without a sweep sustained phase (null), or when no run counts.
     */
    fun withLongRunFallback(sustained: RawSustainedWrite?, evidence: List<RawWriteEvidence>): RawSustainedWrite? {
        if (sustained == null || sustained.cliffDetected) return sustained
        fun counts(run: RawWriteEvidence) = run.fellBehind && run.bytesPerSecond > 0 && !run.severe
        val behind = evidence.filter(::counts)
        val slowest = behind.minOfOrNull { it.bytesPerSecond } ?: return sustained
        val floor = evidence.drop(evidence.indexOfFirst(::counts) + 1)
            .filter { !it.fellBehind && (it.storageBytesPerSecond ?: 0) > 0 }
            .maxByOrNull { it.storageBytesPerSecond!! }
        val floorRate = floor?.storageBytesPerSecond
        return sustained.copy(
            cacheBytes = sustained.cacheBytesAtLeast,
            sustainedBytesPerSecond = maxOf(slowest, floorRate ?: 0L),
            source = SOURCE_LONG_RUNS,
            derivedFrom = behind.map { it.spec }.distinct(),
            floorBytesPerSecond = floorRate,
            floorFrom = floor?.spec,
        )
    }

    /**
     * The cliff in [samples]: burst = the median of the first [burstWindows] windows; the cliff is
     * the first window from which the rolling median of [ROLLING_WINDOWS] windows stays below
     * [CLIFF_FRACTION] of the burst until the end, moved forward to the first window that is itself
     * below it (a rolling median under the line can start on one or two fast windows). A single slow
     * window is never a cliff: a median of five ignores it.
     */
    fun detect(samples: List<RawThroughputSample>, burstWindows: Int = RawThroughputBenchmark.SWEEP_MIN_WINDOWS): RawSustainedWrite {
        val total = samples.sumOf { it.bytesWritten }
        val burst = median(samples.take(burstWindows).map(RawThroughputSample::bytesPerSecond))
        val noCliff = RawSustainedWrite(false, null, null, total, burst, burst, samples.size, 0)
        if (burst == null || burst <= 0 || samples.size < ROLLING_WINDOWS) return noCliff
        val threshold = burst * CLIFF_FRACTION
        val rates = samples.map(RawThroughputSample::bytesPerSecond)
        var start = -1
        for (j in rates.size - ROLLING_WINDOWS downTo 0) {
            if (median(rates.subList(j, j + ROLLING_WINDOWS))!! < threshold) start = j else break
        }
        if (start < 0) return noCliff
        val cliff = (start until rates.size).first { rates[it] < threshold }
        val after = rates.size - cliff
        return RawSustainedWrite(
            cliffDetected = true,
            cliffWindow = cliff,
            cacheBytes = samples.subList(0, cliff).sumOf { it.bytesWritten },
            cacheBytesAtLeast = null,
            burstBytesPerSecond = burst,
            sustainedBytesPerSecond = if (after >= MIN_SUSTAINED_WINDOWS) median(rates.subList(cliff, rates.size)) else null,
            windows = samples.size,
            windowsAfterCliff = after,
        )
    }

    /**
     * Why the sweep stops after its latest window, or null to keep writing: never before
     * [burstWindows]; then once the cliff and [SUSTAINED_TAIL_WINDOWS] windows after it are measured
     * ([STOP_TAIL_COMPLETE]), once every window together wrote [maxTotalBytes] ([STOP_MAX_BYTES]),
     * or once the windows past [burstWindows] last [maxExtraSeconds] ([STOP_MAX_DURATION]).
     * [maxExtraSeconds] of 0 turns the sustained phase off ([STOP_DISABLED]).
     */
    fun sweepStop(
        samples: List<RawThroughputSample>,
        burstWindows: Int,
        maxExtraSeconds: Int = SUSTAINED_MAX_EXTRA_SECONDS,
        maxTotalBytes: Long = SUSTAINED_MAX_TOTAL_BYTES,
    ): String? {
        if (samples.size < burstWindows) return null
        if (maxExtraSeconds <= 0) return STOP_DISABLED
        val found = detect(samples, burstWindows)
        if (found.cliffDetected && found.windowsAfterCliff >= SUSTAINED_TAIL_WINDOWS) return STOP_TAIL_COMPLETE
        if (samples.sumOf { it.bytesWritten } >= maxTotalBytes) return STOP_MAX_BYTES
        val extraMs = samples.drop(burstWindows).sumOf { it.durationMs }
        return if (extraMs >= maxExtraSeconds * 1_000L) STOP_MAX_DURATION else null
    }

    /**
     * How long a take at [streamBytesPerSecond] (RAW10) writes before the ring overflows, in whole
     * seconds, or null when it is not limited by the cache: no cliff found, an unknown sustained
     * rate, or a stream the sustained rate keeps up with. The cache absorbs the stream S for
     * `cacheBytes / S` seconds; past it the ring fills at `S - sustained`. With a [compressionRatio]
     * above 1 (a compressed twin) S is the compressed rate ([compressedBytesPerSecond]), and the ring, which holds
     * RAW10 frames, holds [ringBytes] / ratio of compressed output.
     */
    fun burstSeconds(sustained: RawSustainedWrite?, streamBytesPerSecond: Long, ringBytes: Long, compressionRatio: Double = 1.0): Long? {
        require(compressionRatio >= 1.0) { "compression ratio must be at least 1" }
        if (sustained == null || !sustained.sustainedKnown) return null
        val cache = sustained.cacheBytes ?: return null
        val rate = sustained.sustainedBytesPerSecond ?: return null
        val s = compressedBytesPerSecond(streamBytesPerSecond, compressionRatio)
        if (s <= rate) return null
        val seconds = cache.toDouble() / s + ringBytes / compressionRatio / (s - rate)
        return floor(seconds).toLong()
    }

    /** The rate a compressed stream reaches the storage at [ratio]; the RAW10 rate itself at 1. */
    fun compressedBytesPerSecond(streamBytesPerSecond: Long, ratio: Double): Long =
        if (ratio == 1.0) streamBytesPerSecond else ceil(streamBytesPerSecond / ratio).toLong()

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
