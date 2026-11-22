/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionResamplerTest {
    private val frameNs = 33_333_333L

    private class Stream(val times: LongArray, val x: FloatArray, val y: FloatArray, val z: FloatArray)

    /** Samples every [periodNs] from [startNs] to [endNs], z = [rate] at each time, x and y zero. */
    private fun stream(startNs: Long, endNs: Long, periodNs: Long, rate: (Long) -> Double): Stream {
        val count = ((endNs - startNs) / periodNs + 1).toInt()
        val times = LongArray(count) { startNs + it * periodNs }
        return Stream(times, FloatArray(count), FloatArray(count), FloatArray(count) { rate(times[it]).toFloat() })
    }

    /** A smooth bump over [fromNs, toNs], zero outside it, peaking at [peak] rad/s. */
    private fun bump(fromNs: Long, toNs: Long, peak: Double): (Long) -> Double = { t ->
        if (t < fromNs || t > toNs) 0.0 else peak * Math.pow(Math.sin(Math.PI * (t - fromNs) / (toNs - fromNs)), 2.0)
    }

    /** Left Riemann sum in rad: each sample's rate holds until the next sample, as orientation integration does. */
    private fun integral(times: LongArray, values: FloatArray, unitSeconds: Double): Double {
        var sum = 0.0
        for (i in 0 until times.size - 1) sum += values[i] * (times[i + 1] - times[i]) * unitSeconds
        return sum
    }

    private fun resample(stream: Stream, timeline: MotionTimeline, scaleByRate: Boolean) =
        MotionResampler.resample(stream.times, stream.x, stream.y, stream.z, timeline, scaleByRate)

    private fun assertPreservesRotation(stream: Stream, timeline: MotionTimeline) {
        val sensor = integral(stream.times, stream.z, 1e-9)
        val mapped = resample(stream, timeline, scaleByRate = true)
        val file = integral(mapped.fileUs, mapped.z, 1e-6)
        assertTrue("rotation too small to test: $sensor", sensor > 0.5)
        assertEquals("sensor $sensor rad, file $file rad", 1.0, file / sensor, 1e-4)
        assertIncreasing(mapped.fileUs)
    }

    private fun assertIncreasing(times: LongArray) {
        for (i in 1 until times.size) assertTrue("time $i does not increase", times[i] > times[i - 1])
    }

    private fun pauseTimeline(): Pair<MotionTimeline, LongArray> {
        val before = LongArray(60) { 1_000_000_000L + it * frameNs }
        val resume = before.last() + 3_000_000_000L
        val sensor = before + LongArray(60) { resume + it * frameNs }
        val pts = LongArray(120) { it * frameNs }
        return MotionTimeline.fromFrames(sensor, pts, fileOffsetUs = 0, clockOffsetNs = 0) to sensor
    }

    @Test fun normalTakePassesThroughBitExact() {
        val sensor = LongArray(90) { 1_000_000_000L + it * frameNs }
        val timeline = MotionTimeline.fromFrames(sensor, sensor, fileOffsetUs = 1_000, clockOffsetNs = 0)
        val random = Random(7)
        val count = 1_400
        val times = LongArray(count) { sensor[0] + 137_000L + it * 2_000_000L + random.nextInt(200_000) }
        val x = FloatArray(count) { random.nextFloat() * 10f - 5f }
        val y = FloatArray(count) { random.nextFloat() * 10f - 5f }
        val z = FloatArray(count) { random.nextFloat() * 10f - 5f }

        for (scaleByRate in listOf(true, false)) {
            val mapped = MotionResampler.resample(times, x, y, z, timeline, scaleByRate)
            assertEquals(count, mapped.size)
            for (i in 0 until count) {
                assertEquals(1_000L + (times[i] - sensor[0]) / 1_000L, mapped.fileUs[i])
                assertEquals(x[i].toRawBits(), mapped.x[i].toRawBits())
                assertEquals(y[i].toRawBits(), mapped.y[i].toRawBits())
                assertEquals(z[i].toRawBits(), mapped.z[i].toRawBits())
            }
        }
    }

    @Test fun unmappedSamplesAreDropped() {
        val timeline = MotionTimeline.linear(1_000_000L, 0L, 2_000_000L, 1_000L, clockOffsetNs = 0)
        val mapped = MotionResampler.resample(
            longArrayOf(0L, 1_000_000L, 2_500_000L, 3_000_000L),
            FloatArray(4) { it.toFloat() }, FloatArray(4), FloatArray(4),
            timeline, scaleByRate = true,
        )
        assertEquals(listOf(0L, 1_500L), mapped.fileUs.toList())
        assertEquals(listOf(1f, 2f), mapped.x.toList())
    }

    @Test fun pauseRotationLandsInOneFrameIntervalWithItsIntegral() {
        val (timeline, frames) = pauseTimeline()
        val pauseFrom = frames[59]
        val pauseTo = frames[60]
        val gyro = stream(frames[0] - 100_000_000L + 700_000L, frames.last() + 20_000_000L, 2_000_000L, bump(pauseFrom, pauseTo, 1.5))
        assertPreservesRotation(gyro, timeline)

        val mapped = resample(gyro, timeline, scaleByRate = true)
        val pauseStartUs = timeline.fileUs(pauseFrom)!!
        val pauseEndUs = timeline.fileUs(pauseTo)!!
        for (i in 0 until mapped.size) {
            if (mapped.z[i] != 0f) {
                assertTrue("rotation outside the pause interval", mapped.fileUs[i] in pauseStartUs until pauseEndUs)
            }
        }
    }

    @Test fun timeLapseRotationIntegralIsPreserved() {
        val frames = LongArray(600) { 5_000_000_000L + it * 1_000_000_000L }
        val pts = LongArray(600) { it * frameNs }
        val timeline = MotionTimeline.fromFrames(frames, pts, fileOffsetUs = 0, clockOffsetNs = 0)
        val gyro = stream(frames[0] + 1_300_000L, frames.last(), 5_000_000L, bump(frames[0], frames.last(), 0.2))
        assertPreservesRotation(gyro, timeline)
        assertTrue(resample(gyro, timeline, scaleByRate = true).size < gyro.times.size / 5)
    }

    @Test fun slowMotionRotationIntegralIsPreserved() {
        val timeline = MotionTimeline.linear(1_000_000_000L, 0L, 11_000_000_000L, 80_000_000L, clockOffsetNs = 0)
        val gyro = stream(1_000_000_000L, 11_000_000_000L, 2_000_000L, bump(1_000_000_000L, 11_000_000_000L, 0.8))
        assertPreservesRotation(gyro, timeline)
        val mapped = resample(gyro, timeline, scaleByRate = true)
        assertEquals(gyro.times.size, mapped.size)
        val middle = mapped.size / 2
        assertEquals(gyro.z[middle] / 8f, mapped.z[middle], 1e-6f)
    }

    @Test fun accelerometerIsNotScaled() {
        val frames = LongArray(30) { it * 1_000_000_000L }
        val pts = LongArray(30) { it * frameNs }
        val timeline = MotionTimeline.fromFrames(frames, pts, fileOffsetUs = 0, clockOffsetNs = 0)
        val count = 29 * 200
        val times = LongArray(count) { it * 5_000_000L }
        val mapped = MotionResampler.resample(
            times, FloatArray(count) { 0.25f }, FloatArray(count) { -9.80665f }, FloatArray(count) { 1f / 3f },
            timeline, scaleByRate = false,
        )
        assertTrue(mapped.size in 1 until count)
        for (i in 0 until mapped.size) {
            assertEquals(0.25f, mapped.x[i], 0f)
            assertEquals(-9.80665f, mapped.y[i], 0f)
            assertEquals(1f / 3f, mapped.z[i], 0f)
        }
    }

    @Test fun outputTimesStrictlyIncreaseAndSameMicrosecondSamplesMerge() {
        val (timeline, frames) = pauseTimeline()
        val random = Random(11)
        val count = 20_000
        val times = LongArray(count) { frames[0] + random.nextInt(Int.MAX_VALUE).toLong() * 4L % (frames.last() - frames[0]) }
        times.sort()
        val values = FloatArray(count) { 1f }
        for (scaleByRate in listOf(true, false)) {
            val mapped = MotionResampler.resample(times, values, values, values, timeline, scaleByRate)
            assertTrue(mapped.size > 0)
            assertIncreasing(mapped.fileUs)
        }

        val identity = MotionTimeline.linear(0L, 0L, 1_000_000_000L, 1_000_000L, clockOffsetNs = 0)
        val merged = MotionResampler.resample(
            longArrayOf(2_000_100L, 2_000_400L, 3_000_000L),
            floatArrayOf(1f, 2f, 5f), FloatArray(3), FloatArray(3),
            identity, scaleByRate = true,
        )
        assertEquals(listOf(2_000L, 3_000L), merged.fileUs.toList())
        assertEquals(listOf(1.5f, 5f), merged.x.toList())
    }

    @Test fun aBinAcrossARateChangeKeepsTheRotationOfItsSamples() {
        // Sensor time 0..80 ms plays in 8 ms of file (rate scale 10), then real time. Gyro at 1 rad/s every 4 ms: a 16 ms
        // bin holds 20 samples at scale 10 (file 0..7.6 ms) and 2 at scale 1 (file 8 and 12 ms), 88 ms of sensor time.
        val base = 1_000_000_000L
        val timeline = MotionTimeline.fromAnchors(
            longArrayOf(base, base + 80_000_000L, base + 1_080_000_000L), longArrayOf(0L, 8_000L, 1_008_000L), clockOffsetNs = 0,
        )
        val times = LongArray(22) { base + it * 4_000_000L }
        val mapped = MotionResampler.resample(times, FloatArray(22), FloatArray(22), FloatArray(22) { 1f }, timeline,
            scaleByRate = true, binUs = 16_000L)
        assertEquals(1, mapped.size)
        // Integrated over the bin's 16 ms of file time it gives the 88 mrad the samples made (a plain mean gives 147).
        assertEquals(0.088, mapped.z[0] * 0.016, 1e-6)

        // Bins of one rate scale keep the plain mean of the scaled values.
        val single = MotionResampler.resample(LongArray(20) { base + it * 4_000_000L }, FloatArray(20), FloatArray(20),
            FloatArray(20) { 1f + it }, timeline, scaleByRate = true, binUs = 16_000L)
        assertEquals(1, single.size)
        assertEquals(((1..20).sum() * 10.0 / 20).toFloat(), single.z[0], 0f)
    }
}
