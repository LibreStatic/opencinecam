/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

/**
 * OCC-PLAN-071: per-frame evidence of one live qualification run, written under the run's
 * `frameDiagnostics` key. It explains a frame-rate shortfall (where the late frames fall, what
 * exposure and frame duration the camera reported, how long the crop copy took) and never changes
 * a verdict. Everything is summarised: no per-frame dump reaches the report.
 */
data class RawFrameDiagnostics(
    val nominalFrameNs: Long,
    val gaps: GapHistogram,
    val exposureNs: RawFrameStat?,
    val sensitivity: RawFrameStat?,
    val frameDurationNs: RawFrameStat?,
    /** Capture results whose exposure time was longer than [nominalFrameNs]. */
    val exposureOverFrame: Int,
    val captureResults: Int,
    val copyP50Ns: Long?,
    val copyP99Ns: Long?,
    /** CPU time of the reader thread (acquire, crop copy, publish) per wall second of the measured frames; null when unknown. */
    val readerCpuCores: Double?,
)

/** Min, median and max of a set of samples. */
data class RawFrameStat(val min: Long, val median: Long, val max: Long)

/** One gap later than [RawFrameDiagnosticsCalc.LATE_FACTOR] × nominal: between frame [index] and the next. */
data class LateGap(val index: Int, val offsetNs: Long, val gapNs: Long)

/**
 * The inter-frame gaps of the measured frames, against the nominal 1/fps: [upTo1_25] on time (or
 * near it), [upTo2_25] one frame missing, [upTo3_25] two missing, [larger] more. [late] counts gaps
 * past 1.5× nominal, [firstLate] lists the first of them, and [lateByQuarter] spreads them over
 * the four quarters of the measured time, to show whether they drift with heat.
 */
data class GapHistogram(
    val gapCount: Int,
    val upTo1_25: Int,
    val upTo2_25: Int,
    val upTo3_25: Int,
    val larger: Int,
    val late: Int,
    val firstLate: List<LateGap>,
    val lateByQuarter: IntArray,
) {
    override fun equals(other: Any?): Boolean = other is GapHistogram && gapCount == other.gapCount && upTo1_25 == other.upTo1_25 &&
        upTo2_25 == other.upTo2_25 && upTo3_25 == other.upTo3_25 && larger == other.larger && late == other.late &&
        firstLate == other.firstLate && lateByQuarter.contentEquals(other.lateByQuarter)

    override fun hashCode(): Int = arrayOf<Any>(gapCount, upTo1_25, upTo2_25, upTo3_25, larger, late, firstLate,
        lateByQuarter.contentHashCode()).contentHashCode()
}

object RawFrameDiagnosticsCalc {
    /** A gap past this multiple of the nominal frame time is late. */
    const val LATE_FACTOR = 1.5

    /** How many late gaps are listed one by one. */
    const val FIRST_LATE_LIMIT = 20

    /** The nominal frame time of [fps], in nanoseconds. */
    fun nominalFrameNs(fps: Int): Long = if (fps > 0) 1_000_000_000L / fps else 0L

    /**
     * Gap histogram of the first [count] of [timestamps] (sensor timestamps of consecutive measured
     * frames, in arrival order) against [nominalNs].
     */
    fun gaps(timestamps: LongArray, count: Int, nominalNs: Long): GapHistogram {
        val n = count.coerceIn(0, timestamps.size)
        val quarters = IntArray(4)
        if (n < 2 || nominalNs <= 0) return GapHistogram(maxOf(0, n - 1), 0, 0, 0, 0, 0, emptyList(), quarters)
        val start = timestamps[0]
        val span = (timestamps[n - 1] - start).coerceAtLeast(1)
        var a = 0; var b = 0; var c = 0; var d = 0; var late = 0
        val first = ArrayList<LateGap>(FIRST_LATE_LIMIT)
        for (i in 0 until n - 1) {
            val gap = timestamps[i + 1] - timestamps[i]
            // Integer-exact thresholds: gap ≤ k/4 × nominal.
            when {
                gap * 4 <= nominalNs * 5 -> a++
                gap * 4 <= nominalNs * 9 -> b++
                gap * 4 <= nominalNs * 13 -> c++
                else -> d++
            }
            if (gap * 2 > nominalNs * 3) {
                late++
                val offset = timestamps[i + 1] - start
                if (first.size < FIRST_LATE_LIMIT) first += LateGap(i, offset, gap)
                quarters[((offset * 4) / span).toInt().coerceIn(0, 3)]++
            }
        }
        return GapHistogram(n - 1, a, b, c, d, late, first, quarters)
    }

    /** Min, median and max of the first [count] of [values]; null when there are none. Sorts a copy. */
    fun stat(values: LongArray, count: Int): RawFrameStat? {
        val n = count.coerceIn(0, values.size)
        if (n == 0) return null
        val sorted = values.copyOf(n).also { it.sort() }
        return RawFrameStat(sorted[0], sorted[n / 2], sorted[n - 1])
    }

    /** The [percent] percentile (nearest rank) of the first [count] of [values]; null when there are none. */
    fun percentile(values: LongArray, count: Int, percent: Int): Long? {
        val n = count.coerceIn(0, values.size)
        if (n == 0) return null
        val sorted = values.copyOf(n).also { it.sort() }
        val rank = ((n.toLong() * percent + 99) / 100).toInt().coerceIn(1, n)
        return sorted[rank - 1]
    }

    /**
     * Every diagnostic of one run. [exposureNs], [sensitivity] and [frameDurationNs] hold the first
     * [captureCount] measured capture results (a value of 0 or less means the result did not report
     * it and is left out), [copyNs] the first [copyCount] crop copy times.
     */
    fun of(
        fps: Int,
        imageTs: LongArray,
        imageCount: Int,
        exposureNs: LongArray,
        sensitivity: LongArray,
        frameDurationNs: LongArray,
        captureCount: Int,
        copyNs: LongArray,
        copyCount: Int,
        readerCpuNs: Long?,
        readerWallNs: Long?,
    ): RawFrameDiagnostics {
        val nominal = nominalFrameNs(fps)
        val results = captureCount.coerceIn(0, minOf(exposureNs.size, sensitivity.size, frameDurationNs.size))
        fun reported(values: LongArray): LongArray = values.copyOf(results).filter { it > 0 }.toLongArray()
        val exposures = reported(exposureNs)
        val sensitivities = reported(sensitivity)
        val durations = reported(frameDurationNs)
        return RawFrameDiagnostics(
            nominalFrameNs = nominal,
            gaps = gaps(imageTs, imageCount, nominal),
            exposureNs = stat(exposures, exposures.size),
            sensitivity = stat(sensitivities, sensitivities.size),
            frameDurationNs = stat(durations, durations.size),
            exposureOverFrame = if (nominal > 0) exposures.count { it > nominal } else 0,
            captureResults = results,
            copyP50Ns = percentile(copyNs, copyCount, 50),
            copyP99Ns = percentile(copyNs, copyCount, 99),
            readerCpuCores = if (readerCpuNs != null && readerWallNs != null && readerCpuNs >= 0 && readerWallNs > 0) {
                readerCpuNs.toDouble() / readerWallNs
            } else null,
        )
    }
}
