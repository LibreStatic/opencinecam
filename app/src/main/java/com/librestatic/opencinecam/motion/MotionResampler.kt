/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

/** A sensor stream on the file's timeline: strictly increasing [fileUs] and parallel values. */
internal class MappedSeries(
    val fileUs: LongArray,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
) {
    init {
        require(x.size == fileUs.size && y.size == fileUs.size && z.size == fileUs.size) { "series arrays differ in size" }
    }

    val size: Int get() = fileUs.size

    companion object {
        val EMPTY = MappedSeries(LongArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
    }
}

/**
 * Maps a sensor stream onto the file's timeline, preserving its integral.
 *
 * Every sample is mapped through the [MotionTimeline] (unmapped samples are dropped) and gathered
 * into bins of `binUs` of file time. A bin emits one sample at the mean file time of its members,
 * holding their mean value. With `scaleByRate` (the gyro) each value is first multiplied by its
 * segment's rate scale, so that integrating the output over file time gives the true rotation: in
 * slow motion the rate shrinks as file time stretches, across a pause it grows to fit the pause's
 * rotation into one frame interval. The accelerometer and magnetometer are not scaled.
 *
 * A bin whose samples come from segments of different rate scales (it spans a pause edge or a speed
 * change) weights each scaled value by the file time its sample covers, which is its sensor spacing
 * divided by its scale: the mean is then the raw values' sum over the sum of 1/scale, and the bin
 * integrates over file time to the rotation its samples made. Within one scale every weight is equal,
 * so such a bin keeps the plain mean.
 *
 * A bin holding a single sample of a segment whose rate scale is within [PASSTHROUGH_TOLERANCE] of 1
 * emits that sample unchanged at its mapped time, so a normal take passes through bit-exact.
 * Bins are disjoint and a mean member time lies inside its bin, so output times strictly increase.
 */
internal object MotionResampler {
    const val DEFAULT_BIN_US: Long = 1_000L
    const val PASSTHROUGH_TOLERANCE: Double = 0.02

    /** [times] must be non-decreasing. The whole stream in and out, for tests and small inputs; see [cursor]. */
    fun resample(
        times: LongArray,
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        timeline: MotionTimeline,
        scaleByRate: Boolean,
        binUs: Long = DEFAULT_BIN_US,
    ): MappedSeries = cursor(ArrayVectorCursor(times, x, y, z), timeline, scaleByRate, binUs).use { it.collect() }

    /**
     * [samples] (sensor-clock times, non-decreasing) resampled as a cursor of file-time samples; it holds one bin of
     * state and closes [samples] when closed.
     */
    fun cursor(samples: VectorCursor, timeline: MotionTimeline, scaleByRate: Boolean, binUs: Long = DEFAULT_BIN_US): VectorCursor {
        require(binUs > 0) { "binUs must be positive" }
        return Resampled(samples, timeline, scaleByRate, binUs)
    }

    /**
     * How many samples [cursor] would emit at each of [binsUs], counted in one pass over [samples] (which is used up):
     * one per run of mapped samples in the same bin, the rule [cursor] emits by.
     */
    fun countBins(samples: VectorCursor, timeline: MotionTimeline, binsUs: LongArray): LongArray {
        require(binsUs.all { it > 0 }) { "binUs must be positive" }
        val counts = LongArray(binsUs.size)
        val bins = LongArray(binsUs.size)
        var any = false
        while (samples.next()) {
            val segment = timeline.segmentOf(samples.time)
            if (segment < 0) continue
            val us = timeline.fileUsIn(segment, samples.time)
            for (b in binsUs.indices) {
                val bin = Math.floorDiv(us, binsUs[b])
                if (!any || bin != bins[b]) {
                    counts[b]++
                    bins[b] = bin
                }
            }
            any = true
        }
        return counts
    }

    private class Resampled(
        private val source: VectorCursor,
        private val timeline: MotionTimeline,
        private val scaleByRate: Boolean,
        private val binUs: Long,
    ) : VectorCursor {
        override var time: Long = 0L
            private set
        override var x: Float = 0f
            private set
        override var y: Float = 0f
            private set
        override var z: Float = 0f
            private set
        private var emitted = false

        // The bin being gathered; count 0 means none.
        private var bin = 0L
        private var count = 0
        private var sumUs = 0L
        private var sumX = 0.0
        private var sumY = 0.0
        private var sumZ = 0.0
        // For a bin of mixed rate scales: the raw values' sums and the sum of 1/scale.
        private var mixed = false
        private var rawX = 0.0
        private var rawY = 0.0
        private var rawZ = 0.0
        private var sumInverseScale = 0.0
        private var firstUs = 0L
        private var firstX = 0f
        private var firstY = 0f
        private var firstZ = 0f
        private var firstScale = 1.0

        override fun next(): Boolean {
            while (source.next()) {
                val t = source.time
                val segment = timeline.segmentOf(t)
                if (segment < 0) continue
                val us = timeline.fileUsIn(segment, t)
                val scale = if (scaleByRate) timeline.rateScaleOf(segment) else 1.0
                val sampleBin = Math.floorDiv(us, binUs)
                // A sample in a new bin closes the one gathered so far, which is emitted, and starts its own.
                val closed = count > 0 && sampleBin != bin
                if (closed) emit()
                if (count == 0 || closed) {
                    bin = sampleBin
                    count = 0
                    sumUs = 0L
                    sumX = 0.0
                    sumY = 0.0
                    sumZ = 0.0
                    mixed = false
                    rawX = 0.0
                    rawY = 0.0
                    rawZ = 0.0
                    sumInverseScale = 0.0
                    firstUs = us
                    firstX = source.x
                    firstY = source.y
                    firstZ = source.z
                    firstScale = scale
                }
                count++
                sumUs += us
                sumX += source.x * scale
                sumY += source.y * scale
                sumZ += source.z * scale
                if (scale != firstScale) mixed = true
                rawX += source.x
                rawY += source.y
                rawZ += source.z
                sumInverseScale += 1.0 / scale
                if (closed) return true
            }
            if (count == 0) return false
            emit()
            count = 0
            return true
        }

        private fun emit() {
            val out: Long
            if (count == 1) {
                out = firstUs
                if (!scaleByRate || Math.abs(firstScale - 1.0) <= PASSTHROUGH_TOLERANCE) {
                    x = firstX
                    y = firstY
                    z = firstZ
                } else {
                    x = (firstX * firstScale).toFloat()
                    y = (firstY * firstScale).toFloat()
                    z = (firstZ * firstScale).toFloat()
                }
            } else {
                out = Math.floorDiv(sumUs, count.toLong())
                if (mixed) {
                    x = (rawX / sumInverseScale).toFloat()
                    y = (rawY / sumInverseScale).toFloat()
                    z = (rawZ / sumInverseScale).toFloat()
                } else {
                    x = (sumX / count).toFloat()
                    y = (sumY / count).toFloat()
                    z = (sumZ / count).toFloat()
                }
            }
            check(!emitted || out > time) { "resampled times must increase" }
            time = out
            emitted = true
        }

        override fun close() = source.close()
    }
}
