/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import com.librestatic.opencinecam.storage.GyroflowLog
import java.io.Writer

/** Everything needed to render a take's motion once it has stopped. */
internal class MotionRenderInput(
    val spool: MotionSpool,
    val timeline: MotionTimeline,
    /** The [GyroflowLog.orientation] string of the take. */
    val orientation: String,
    val videoFileName: String,
    val unixSeconds: Long,
    val firmware: String,
    val synced: Boolean,
    /** The [GyroflowLog.readoutDirection] of the take. */
    val readoutDirection: Int,
    val vendor: String = "OpenCineCam",
    val note: String? = null,
)

internal class MotionRenderResult(
    val gcsvRows: Long,
    val gyroSamples: Int,
    val accelSamples: Int,
    val magSamples: Int,
    /** Measured on the raw sensor clock over the whole spool; null with fewer than two samples. */
    val measuredGyroHz: Double?,
    val measuredAccelHz: Double?,
    val measuredMagHz: Double?,
    /** The largest interval between consecutive raw gyro samples, 0 with fewer than two. */
    val largestGyroGapNs: Long,
    val globalRateScale: Double,
    /** File times of the first and last gyro row in microseconds, both 0 without rows. */
    val fileStartUs: Long,
    val fileEndUs: Long,
)

/**
 * Motion series for a CAMM track, all on the file's timeline and in image axes (X right, Y down,
 * Z forward): [gyro] in rad/s per file second (rate-scaled), [accel] in m/s², [mag] in µT. The
 * exposure entries mark each change: the file time of the first recorded frame it applies to, the
 * exposure time and the rolling-shutter skew (0 = unknown) in force from that frame on.
 */
internal class CammSeries(
    val gyro: MappedSeries,
    val accel: MappedSeries,
    val mag: MappedSeries,
    val exposureFileUs: LongArray,
    val exposureNs: IntArray,
    val skewNs: IntArray,
)

/**
 * The exposure entries of a CAMM series ([CammSeries.exposureFileUs] and its parallel arrays); they do not depend on
 * the bin, and there are at most as many as spooled frames.
 */
internal class CammExposures(val fileUs: LongArray, val exposureNs: IntArray, val skewNs: IntArray) {
    val size: Int get() = fileUs.size
}

/**
 * What a CAMM series would hold at each of [binsUs], counted without building it: the resampled sample count of each
 * sensor per bin, and the exposure entries.
 */
internal class CammPlan(
    val binsUs: LongArray,
    val gyro: LongArray,
    val accel: LongArray,
    val mag: LongArray,
    val exposures: CammExposures,
) {
    /** The packets the series at [binUs] makes, as [MotionCammBudget.packets] counts a built one. */
    fun packets(binUs: Long): Long {
        val b = index(binUs)
        return gyro[b] + accel[b] + mag[b] + exposures.size
    }

    internal fun index(binUs: Long): Int {
        val b = binsUs.indexOf(binUs)
        require(b >= 0) { "bin $binUs us was not planned" }
        return b
    }
}

/**
 * Renders a scanned spool. Every sensor stream is read from the spool through its own cursor and resampled on the
 * fly, so the memory a render needs does not grow with the take, except for the frames and exposures the scan keeps
 * and the CAMM series, which [MotionCammBudget] bounds.
 */
internal object MotionRender {
    /**
     * Writes the Gyroflow log. The rows keep the raw device axes, which the orientation string maps
     * for Gyroflow. The readout time is the latest known skew in file time (divided by the global
     * rate scale), left out when no skew is known.
     */
    fun renderGcsv(input: MotionRenderInput, out: Writer): MotionRenderResult {
        val spool = input.spool
        val timeline = input.timeline
        // The log keeps the accelerometer and magnetometer columns only when they have a mapped sample.
        val header = GyroflowLog.Header(
            orientation = input.orientation,
            videoFileName = input.videoFileName,
            unixSeconds = input.unixSeconds,
            firmware = input.firmware,
            readoutTimeMs = readoutTimeMs(spool.exposures, timeline.globalRateScale),
            readoutDirection = input.readoutDirection,
            synced = input.synced,
            hasAccelerometer = true,
            vendor = input.vendor,
            hasMagnetometer = true,
            note = input.note,
        )
        return resampled(input, MotionSpoolFormat.KIND_GYRO, MotionResampler.DEFAULT_BIN_US).use { gyro ->
            CountingCursor(resampled(input, MotionSpoolFormat.KIND_ACCEL, MotionResampler.DEFAULT_BIN_US)).use { accel ->
                CountingCursor(resampled(input, MotionSpoolFormat.KIND_MAG, MotionResampler.DEFAULT_BIN_US)).use { mag ->
                    val rows = GyroflowLog.render(out, header, gyro, accel, mag)
                    accel.drain()
                    mag.drain()
                    MotionRenderResult(
                        gcsvRows = rows.count,
                        gyroSamples = clampToInt(rows.count),
                        accelSamples = clampToInt(accel.count),
                        magSamples = clampToInt(mag.count),
                        measuredGyroHz = spool.gyroStats.measuredHz,
                        measuredAccelHz = spool.accelStats.measuredHz,
                        measuredMagHz = spool.magStats.measuredHz,
                        largestGyroGapNs = spool.gyroStats.largestGapNs,
                        globalRateScale = timeline.globalRateScale,
                        fileStartUs = rows.firstUs,
                        fileEndUs = rows.lastUs,
                    )
                }
            }
        }
    }

    /**
     * Counts what the CAMM series would hold at each of [binsUs], one pass over the spool per sensor, so that a bin
     * can be chosen before any series is built.
     */
    fun cammPlan(input: MotionRenderInput, binsUs: LongArray): CammPlan {
        fun count(kind: Int): LongArray = input.spool.samples(kind).use { MotionResampler.countBins(it, input.timeline, binsUs) }
        return CammPlan(
            binsUs.copyOf(),
            count(MotionSpoolFormat.KIND_GYRO),
            count(MotionSpoolFormat.KIND_ACCEL),
            count(MotionSpoolFormat.KIND_MAG),
            cammExposures(input),
        )
    }

    /**
     * The CAMM series with the vector streams in bins of [binUs] of file time ([MotionCammBudget] picks it), sized
     * from [plan] (which must hold [binUs]) and rotated into image axes as it is read. An exposure entry is kept only
     * where the exposure or the skew changes: at the first recorded frame it is in force, then at each frame where it
     * differs. A take without spooled frames (the MediaRecorder route) places each exposure record at its own mapped
     * time instead, except that the latest record from before the timeline's start (motion capture starts before the
     * recorder) is placed at that start, as the exposure in force at the first frame.
     */
    fun cammSeries(
        input: MotionRenderInput,
        binUs: Long = MotionResampler.DEFAULT_BIN_US,
        plan: CammPlan = cammPlan(input, longArrayOf(binUs)),
    ): CammSeries {
        val b = plan.index(binUs)
        // Applying the orientation to (1, 2, 3) yields each output's signed source axis, validated once.
        val axes = GyroflowLog.applyOrientation(input.orientation, 1f, 2f, 3f)
        val gyro = rotated(input, MotionSpoolFormat.KIND_GYRO, binUs, plan.gyro[b], axes)
        val accel = rotated(input, MotionSpoolFormat.KIND_ACCEL, binUs, plan.accel[b], axes)
        val mag = rotated(input, MotionSpoolFormat.KIND_MAG, binUs, plan.mag[b], axes)
        val exposures = plan.exposures
        return CammSeries(gyro, accel, mag, exposures.fileUs, exposures.exposureNs, exposures.skewNs)
    }

    private fun resampled(input: MotionRenderInput, kind: Int, binUs: Long): VectorCursor =
        MotionResampler.cursor(input.spool.samples(kind), input.timeline, scaleByRate = kind == MotionSpoolFormat.KIND_GYRO, binUs = binUs)

    /** The resampled stream of [kind] in image axes, into arrays of exactly [count] samples as the plan counted. */
    private fun rotated(input: MotionRenderInput, kind: Int, binUs: Long, count: Long, axes: FloatArray): MappedSeries {
        if (count == 0L) return MappedSeries.EMPTY
        val size = Math.toIntExact(count)
        val times = LongArray(size)
        val xs = FloatArray(size)
        val ys = FloatArray(size)
        val zs = FloatArray(size)
        var n = 0
        resampled(input, kind, binUs).use { cursor ->
            while (n < size && cursor.next()) {
                times[n] = cursor.time
                xs[n] = axis(axes[0], cursor)
                ys[n] = axis(axes[1], cursor)
                zs[n] = axis(axes[2], cursor)
                n++
            }
        }
        check(n == size) { "the spool changed between the CAMM count and the build" }
        return MappedSeries(times, xs, ys, zs)
    }

    private fun axis(signed: Float, cursor: VectorCursor): Float {
        val value = when (Math.abs(signed).toInt()) {
            1 -> cursor.x
            2 -> cursor.y
            else -> cursor.z
        }
        return if (signed > 0) value else -value
    }

    private fun cammExposures(input: MotionRenderInput): CammExposures {
        val frames = input.spool.frames
        val exposures = input.spool.exposures
        val timeline = input.timeline
        val startNs = timeline.firstAnchorNs
        val placed = if (frames.size > 0) frames.size else exposures.size
        val fileUs = LongArray(placed)
        val exposureNs = IntArray(placed)
        val skewNs = IntArray(placed)
        var count = 0
        for (i in 0 until placed) {
            var cameraNs = if (frames.size > 0) frames.sensorNs[i] else exposures.fromNs[i]
            val exposure = if (frames.size > 0) inForce(exposures.fromNs, cameraNs) else i
            if (exposure < 0) continue
            if (frames.size == 0 && startNs != null && cameraNs + timeline.clockOffsetNs < startNs) {
                // Only the last record before the start is in force there, unless another applies from the start itself.
                if (i + 1 < exposures.size && exposures.fromNs[i + 1] + timeline.clockOffsetNs <= startNs) continue
                cameraNs = startNs - timeline.clockOffsetNs
            }
            val us = timeline.fileUs(cameraNs + timeline.clockOffsetNs) ?: continue
            if (count > 0 && us <= fileUs[count - 1]) continue
            val e = exposures.exposureNs[exposure]
            val s = exposures.skewNs[exposure]
            if (count > 0 && e == exposureNs[count - 1] && s == skewNs[count - 1]) continue
            fileUs[count] = us
            exposureNs[count] = e
            skewNs[count] = s
            count++
        }
        return CammExposures(fileUs.copyOf(count), exposureNs.copyOf(count), skewNs.copyOf(count))
    }

    private fun readoutTimeMs(exposures: ExposureTrack, rateScale: Double): Double? {
        for (i in exposures.size - 1 downTo 0) {
            val skew = exposures.skewNs[i]
            if (skew > 0) return skew / 1_000_000.0 / rateScale
        }
        return null
    }

    private fun clampToInt(value: Long): Int = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** The index of the last entry of sorted [fromNs] at or before [cameraNs], or -1. */
    private fun inForce(fromNs: LongArray, cameraNs: Long): Int {
        var lo = 0
        var hi = fromNs.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (fromNs[mid] <= cameraNs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}
