/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

/**
 * Maps SensorEvent-clock time to the video file's timeline, piecewise linearly between frame anchors.
 *
 * Each anchor pairs the time a recorded frame was captured (a camera timestamp plus [clockOffsetNs],
 * so on the SensorEvent clock) with the time it is presented in the file. Between consecutive anchors
 * i and i+1 a sensor time t maps to `f_i + (t - a_i) * (f_{i+1} - f_i) / (a_{i+1} - a_i)`, and the
 * segment's rate scale `k_i = (a_{i+1} - a_i) / (f_{i+1} - f_i)` is real time per file time.
 *
 * One rule covers every kind of take: a normal take has k = 1; across a pause the frames either
 * side are one file frame apart, so the rotation made during the pause lands in that one frame
 * interval, which is the true orientation jump across the cut; slow motion has k < 1; off-speed
 * and time lapse have k > 1.
 *
 * Samples before the first anchor map to null. After the last anchor the last segment's slope is
 * extrapolated for at most one last-segment duration (the last frame's own interval), then null.
 * With a single anchor the slope is 1 and the extrapolation lasts at most [SINGLE_ANCHOR_SPAN_NS]
 * (1 s); with no anchor nothing maps.
 *
 * File times are kept in nanoseconds internally so that a take whose presentation times equal its
 * sensor times maps exactly; [fileUs] floors to whole microseconds.
 */
internal class MotionTimeline private constructor(
    private val anchorNs: LongArray,
    private val anchorFileNs: LongArray,
    val clockOffsetNs: Long,
) {
    private val rateScales = DoubleArray(maxOf(anchorNs.size - 1, 0)) { i ->
        (anchorNs[i + 1] - anchorNs[i]).toDouble() / (anchorFileNs[i + 1] - anchorFileNs[i]).toDouble()
    }

    /** Anchors kept after cleaning. */
    val anchorCount: Int get() = anchorNs.size

    /** The median segment rate scale, 1.0 with fewer than two anchors. */
    val globalRateScale: Double = if (rateScales.isEmpty()) {
        1.0
    } else {
        val sorted = rateScales.sortedArray()
        val mid = sorted.size / 2
        if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** File time in microseconds of a SensorEvent-clock time, or null outside the mapped range. */
    fun fileUs(sensorNs: Long): Long? {
        val segment = segmentOf(sensorNs)
        return if (segment < 0) null else fileUsIn(segment, sensorNs)
    }

    /** Real time per file time at a SensorEvent-clock time, or null outside the mapped range. */
    fun rateScale(sensorNs: Long): Double? {
        val segment = segmentOf(sensorNs)
        return if (segment < 0) null else rateScaleOf(segment)
    }

    /**
     * The index of the anchor at or before [sensorNs], or -1 when it does not map. The last index
     * stands for the extrapolation past the last anchor.
     */
    fun segmentOf(sensorNs: Long): Int {
        val n = anchorNs.size
        if (n == 0 || sensorNs < anchorNs[0]) return -1
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (anchorNs[mid] <= sensorNs) lo = mid else hi = mid - 1
        }
        if (lo == n - 1) {
            val limit = if (n == 1) SINGLE_ANCHOR_SPAN_NS else anchorNs[n - 1] - anchorNs[n - 2]
            if (sensorNs - anchorNs[n - 1] >= limit) return -1
        }
        return lo
    }

    /** File time in microseconds of [sensorNs] within [segment], as returned by [segmentOf]. */
    fun fileUsIn(segment: Int, sensorNs: Long): Long = Math.floorDiv(fileNsIn(segment, sensorNs), 1_000L)

    /** The rate scale of [segment], as returned by [segmentOf]. */
    fun rateScaleOf(segment: Int): Double = when {
        rateScales.isEmpty() -> 1.0
        segment >= rateScales.size -> rateScales[rateScales.size - 1]
        else -> rateScales[segment]
    }

    private fun fileNsIn(segment: Int, sensorNs: Long): Long {
        val n = anchorNs.size
        val delta = sensorNs - anchorNs[segment]
        if (n == 1) return anchorFileNs[0] + delta
        val from = if (segment < n - 1) segment else n - 2
        val spanNs = anchorNs[from + 1] - anchorNs[from]
        val spanFileNs = anchorFileNs[from + 1] - anchorFileNs[from]
        val scaled = if (delta <= Long.MAX_VALUE / spanFileNs) {
            Math.floorDiv(delta * spanFileNs, spanNs)
        } else {
            Math.floor(delta.toDouble() * spanFileNs.toDouble() / spanNs.toDouble()).toLong()
        }
        return anchorFileNs[segment] + scaled
    }

    companion object {
        /** How long past a single anchor samples still map. */
        const val SINGLE_ANCHOR_SPAN_NS: Long = 1_000_000_000L

        /**
         * Anchors from camera timestamps [sensorNs] and file times [fileUs]. Anchors that do not
         * strictly increase both are dropped.
         */
        fun fromAnchors(sensorNs: LongArray, fileUs: LongArray, clockOffsetNs: Long): MotionTimeline {
            require(sensorNs.size == fileUs.size) { "anchor arrays differ in size" }
            return build(sensorNs, LongArray(fileUs.size) { fileUs[it] * 1_000L }, clockOffsetNs)
        }

        /**
         * Anchors from recorded frames. The muxer rebases presentation times to the first frame's
         * and then adds a constant, so a frame's file time is
         * `(filePtsNs - firstFilePtsNs) / 1000 + fileOffsetUs` microseconds.
         */
        fun fromFrames(sensorNs: LongArray, filePtsNs: LongArray, fileOffsetUs: Long, clockOffsetNs: Long): MotionTimeline {
            require(sensorNs.size == filePtsNs.size) { "frame arrays differ in size" }
            if (sensorNs.isEmpty()) return build(sensorNs, filePtsNs, clockOffsetNs)
            val first = filePtsNs[0]
            val offsetNs = fileOffsetUs * 1_000L
            return build(sensorNs, LongArray(filePtsNs.size) { filePtsNs[it] - first + offsetNs }, clockOffsetNs)
        }

        fun fromFrames(frames: FrameTrack, fileOffsetUs: Long, clockOffsetNs: Long): MotionTimeline =
            fromFrames(frames.sensorNs, frames.filePtsNs, fileOffsetUs, clockOffsetNs)

        /** Two anchors, for recordings where only the first and the last frame are known. */
        fun linear(firstSensorNs: Long, firstFileUs: Long, lastSensorNs: Long, lastFileUs: Long, clockOffsetNs: Long): MotionTimeline =
            fromAnchors(longArrayOf(firstSensorNs, lastSensorNs), longArrayOf(firstFileUs, lastFileUs), clockOffsetNs)

        private fun build(cameraNs: LongArray, fileNs: LongArray, clockOffsetNs: Long): MotionTimeline {
            val keptSensor = LongArray(cameraNs.size)
            val keptFile = LongArray(cameraNs.size)
            var kept = 0
            for (i in cameraNs.indices) {
                val t = cameraNs[i] + clockOffsetNs
                val f = fileNs[i]
                if (kept > 0 && (t <= keptSensor[kept - 1] || f <= keptFile[kept - 1])) continue
                keptSensor[kept] = t
                keptFile[kept] = f
                kept++
            }
            return MotionTimeline(keptSensor.copyOf(kept), keptFile.copyOf(kept), clockOffsetNs)
        }
    }
}
