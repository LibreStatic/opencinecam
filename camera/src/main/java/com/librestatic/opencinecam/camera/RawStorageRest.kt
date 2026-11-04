/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.abs

/**
 * What [RawStorageRest.judge] concluded about a sweep's burst windows. [rule] is the rule that found
 * the cache unrested ([RawStorageRest.RULE_ERRATIC] or [RawStorageRest.RULE_BELOW_REFERENCE]), null
 * when [rested]. [medianBytesPerSecond] and [p01BytesPerSecond] are those of every burst window;
 * [cliffWindow] is the cliff [RawStorageCliff.detect] found inside them, if any, and
 * [worstSpread] the highest spread (median absolute deviation / median) of the segments rule (a) judged.
 */
data class RawStorageRestResult(
    val rested: Boolean,
    val rule: String?,
    val medianBytesPerSecond: Long?,
    val p01BytesPerSecond: Long?,
    val referenceBytesPerSecond: Long?,
    val cliffWindow: Int?,
    val worstSpread: Double?,
)

/**
 * PLAN-071: a sweep run on a write cache that has not flushed since the last heavy write (a second
 * qualification soon after the first) measures neither the burst nor the sustained rate fairly: on
 * the Razr, 28 minutes after a full qualification, its windows swung between 15 and 675 MB/s, then
 * settled at the post-cache 270–300 MB/s (median 198 MB/s against 1.16 GB/s rested). The
 * qualification stops after such a sweep and asks the operator to let the phone rest.
 *
 * The burst windows are unrested when either rule holds:
 * - (a) [RULE_ERRATIC]: their spread, the median absolute deviation over the median, is above
 *   [ERRATIC_SPREAD]. A robust spread, not a low percentile: one slow window (a rested Razr sweep
 *   had one at 217 MB/s among 1.2 GB/s windows) must not call a rested cache busy. A cliff inside the
 *   burst windows is one step, not noise, so with a cliff (as [RawStorageCliff.detect] finds it) the
 *   rule judges the windows before it and the windows from it separately, each when it has at least
 *   [MIN_SEGMENT_WINDOWS] windows;
 * - (b) [RULE_BELOW_REFERENCE]: a [reference][judge] exists (the best rested burst median measured on
 *   this build and destination) and the burst median is below [REFERENCE_RATIO] of it.
 */
object RawStorageRest {
    /** Rule (a): a spread above this is a cache still busy flushing. Rested Razr sweeps: 0.015 and 0.017; unrested: 0.40. */
    const val ERRATIC_SPREAD = 0.15

    /** Rule (b): a burst median under this fraction of the rested reference. */
    const val REFERENCE_RATIO = 0.6

    /** The fewest windows a segment needs for rule (a) to judge it. */
    const val MIN_SEGMENT_WINDOWS = 10

    const val RULE_ERRATIC = "erratic"
    const val RULE_BELOW_REFERENCE = "below-reference"

    /**
     * Whether the write cache was rested for [burst] (the sweep's first
     * [RawThroughputBenchmark.SWEEP_MIN_WINDOWS] windows) against [referenceBytesPerSecond] (null or
     * not positive: none, so only rule (a) applies). No windows count as rested: nothing to judge.
     */
    fun judge(burst: List<RawThroughputSample>, referenceBytesPerSecond: Long?): RawStorageRestResult {
        val reference = referenceBytesPerSecond?.takeIf { it > 0 }
        val rates = burst.map(RawThroughputSample::bytesPerSecond)
        if (rates.isEmpty()) return RawStorageRestResult(true, null, null, null, reference, null, null)
        val median = median(rates)
        val p01 = RawThroughputBenchmark.percentile01(rates.sorted())
        val cliff = RawStorageCliff.detect(burst, burst.size).cliffWindow
        val segments = (if (cliff == null) listOf(rates) else listOf(rates.subList(0, cliff), rates.subList(cliff, rates.size)))
            .filter { it.size >= MIN_SEGMENT_WINDOWS }
            .ifEmpty { listOf(rates) }
        val worst = segments.maxOf { segment ->
            val m = median(segment)
            if (m <= 0) 0.0 else median(segment.map { abs(it - m) }).toDouble() / m
        }
        val rule = when {
            worst > ERRATIC_SPREAD -> RULE_ERRATIC
            reference != null && median < reference * REFERENCE_RATIO -> RULE_BELOW_REFERENCE
            else -> null
        }
        return RawStorageRestResult(rule == null, rule, median, p01, reference, cliff, worst)
    }

    /** The reference after a sweep judged [result]: the best rested median, so a rested sweep only raises it. */
    fun nextReference(previous: Long?, result: RawStorageRestResult): Long? {
        val median = result.medianBytesPerSecond?.takeIf { result.rested && it > 0 } ?: return previous
        return maxOf(previous ?: 0L, median)
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
