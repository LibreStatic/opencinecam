/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import com.librestatic.opencinecam.storage.GyroflowLog
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.StringWriter
import java.io.Writer
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The streaming render against the in-memory render it replaced. The oracle below is the former array algorithm
 * (resampling, nearest pairing and axis rotation) kept here verbatim, so the cursors must give the same output, sample
 * for sample and byte for byte.
 */
class MotionStreamingRenderTest {
    @get:Rule val temp = TemporaryFolder()

    private val startNs = 20_000_000_000L
    private val frameNs = 33_333_333L

    /** Samples of one sensor as written, in order. */
    private class Written {
        val times = ArrayList<Long>()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val zs = ArrayList<Float>()

        fun add(t: Long, x: Float, y: Float, z: Float) {
            times += t
            xs += x
            ys += y
            zs += z
        }

        fun arrays(): SampleArrays = SampleArrays(times.toLongArray(), xs.toFloatArray(), ys.toFloatArray(), zs.toFloatArray())
    }

    private class Take(val bytes: ByteArray, val gyro: SampleArrays, val accel: SampleArrays, val mag: SampleArrays)

    /**
     * 2 s at 30 fps, a 3 s pause (the file frames stay one interval apart across it), then 2 s of frames captured at
     * half the rate (off-speed, rate scale 2). Gyro at about 200 Hz with jitter, accelerometer at 100 Hz, magnetometer
     * at 50 Hz, all starting before the first frame and ending after the last.
     */
    private fun pausedTake(): Take {
        val random = Random(5)
        val gyro = Written()
        val accel = Written()
        val mag = Written()
        val bytes = spoolBytes {
            exposure(startNs, 10_000_000, 0)
            exposure(startNs + 1_000_000_000L, 9_000_000, 11_000_000)
            var pts = 3_000_000_000L
            for (i in 0 until 60) {
                frame(startNs + i * frameNs, pts)
                pts += frameNs
            }
            val resumeNs = startNs + 59 * frameNs + 3_000_000_000L
            for (i in 0 until 30) {
                frame(resumeNs + i * 2 * frameNs, pts)
                pts += frameNs
            }
            val endNs = resumeNs + 31 * 2 * frameNs
            var t = startNs - 50_000_000L
            var i = 0
            while (t < endNs) {
                val gx = random.nextFloat() - 0.5f
                val gy = random.nextFloat() - 0.5f
                val gz = random.nextFloat() - 0.5f
                sample(MotionSpoolFormat.KIND_GYRO, t, gx, gy, gz)
                gyro.add(t, gx, gy, gz)
                if (i % 2 == 0) {
                    val ax = random.nextFloat() * 2f
                    sample(MotionSpoolFormat.KIND_ACCEL, t + 300_000L, ax, 9.80665f, -ax)
                    accel.add(t + 300_000L, ax, 9.80665f, -ax)
                }
                if (i % 4 == 1) {
                    val mx = random.nextFloat() * 40f
                    sample(MotionSpoolFormat.KIND_MAG, t, mx, -mx, 12.5f)
                    mag.add(t, mx, -mx, 12.5f)
                }
                t += 4_000_000L + random.nextInt(2_000_000)
                i++
            }
        }
        return Take(bytes, gyro.arrays(), accel.arrays(), mag.arrays())
    }

    private fun input(spool: MotionSpool, timeline: MotionTimeline = timeline(spool)) = MotionRenderInput(
        spool = spool,
        timeline = timeline,
        orientation = "yxz",
        videoFileName = "OCC_0001.mp4",
        unixSeconds = 1_700_000_000L,
        firmware = "1.0.0",
        synced = true,
        readoutDirection = 0,
    )

    private fun timeline(spool: MotionSpool) = MotionTimeline.fromFrames(spool.frames, fileOffsetUs = 0, clockOffsetNs = 0)

    // The former in-memory render, as the oracle.

    private fun oldResample(s: SampleArrays, timeline: MotionTimeline, scaleByRate: Boolean, binUs: Long = 1_000L): MappedSeries {
        val outTimes = LongArray(s.size)
        val outX = FloatArray(s.size)
        val outY = FloatArray(s.size)
        val outZ = FloatArray(s.size)
        var out = 0
        var bin = Long.MIN_VALUE
        var count = 0
        var sumUs = 0L
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        var firstUs = 0L
        var firstX = 0f
        var firstY = 0f
        var firstZ = 0f
        var firstScale = 1.0
        fun emit() {
            if (count == 0) return
            val time: Long
            if (count == 1) {
                time = firstUs
                if (!scaleByRate || Math.abs(firstScale - 1.0) <= MotionResampler.PASSTHROUGH_TOLERANCE) {
                    outX[out] = firstX
                    outY[out] = firstY
                    outZ[out] = firstZ
                } else {
                    outX[out] = (firstX * firstScale).toFloat()
                    outY[out] = (firstY * firstScale).toFloat()
                    outZ[out] = (firstZ * firstScale).toFloat()
                }
            } else {
                time = Math.floorDiv(sumUs, count.toLong())
                outX[out] = (sumX / count).toFloat()
                outY[out] = (sumY / count).toFloat()
                outZ[out] = (sumZ / count).toFloat()
            }
            outTimes[out] = time
            out++
        }
        for (i in s.timeNs.indices) {
            val segment = timeline.segmentOf(s.timeNs[i])
            if (segment < 0) continue
            val us = timeline.fileUsIn(segment, s.timeNs[i])
            val scale = if (scaleByRate) timeline.rateScaleOf(segment) else 1.0
            val sampleBin = Math.floorDiv(us, binUs)
            if (sampleBin != bin) {
                emit()
                bin = sampleBin
                count = 0
                sumUs = 0L
                sumX = 0.0
                sumY = 0.0
                sumZ = 0.0
                firstUs = us
                firstX = s.x[i]
                firstY = s.y[i]
                firstZ = s.z[i]
                firstScale = scale
            }
            count++
            sumUs += us
            sumX += s.x[i] * scale
            sumY += s.y[i] * scale
            sumZ += s.z[i] * scale
        }
        emit()
        return MappedSeries(outTimes.copyOf(out), outX.copyOf(out), outY.copyOf(out), outZ.copyOf(out))
    }

    private fun floorIndex(times: LongArray, from: Int, t: Long): Int {
        var i = from
        while (i + 1 < times.size && times[i + 1] <= t) i++
        return i
    }

    private fun nearer(times: LongArray, floor: Int, t: Long): Int =
        if (floor + 1 < times.size && times[floor] < t && times[floor + 1] - t <= t - times[floor]) floor + 1 else floor

    private fun oldGcsv(take: Take, timeline: MotionTimeline, readoutTimeMs: Double?): String {
        val gyro = oldResample(take.gyro, timeline, scaleByRate = true)
        val accel = oldResample(take.accel, timeline, scaleByRate = false)
        val mag = oldResample(take.mag, timeline, scaleByRate = false)
        val withAccel = accel.size > 0
        val withMag = withAccel && mag.size > 0
        val header = GyroflowLog.Header("yxz", "OCC_0001.mp4", 1_700_000_000L, "1.0.0", readoutTimeMs, 0, true, withAccel,
            hasMagnetometer = withMag)
        val text = StringBuilder(GyroflowLog.header(header))
        var a = 0
        var m = 0
        for (i in 0 until gyro.size) {
            val t = gyro.fileUs[i]
            var ax: Float? = null
            var ay = 0f
            var az = 0f
            if (withAccel) {
                a = floorIndex(accel.fileUs, a, t)
                val k = nearer(accel.fileUs, a, t)
                ax = accel.x[k]; ay = accel.y[k]; az = accel.z[k]
            }
            var mx: Float? = null
            var my = 0f
            var mz = 0f
            if (withMag) {
                m = floorIndex(mag.fileUs, m, t)
                val k = nearer(mag.fileUs, m, t)
                mx = mag.x[k]; my = mag.y[k]; mz = mag.z[k]
            }
            GyroflowLog.appendRow(text, t, gyro.x[i], gyro.y[i], gyro.z[i], ax, ay, az, mx, my, mz)
        }
        return text.toString()
    }

    private fun oldRotate(series: MappedSeries, orientation: String): MappedSeries {
        val axes = GyroflowLog.applyOrientation(orientation, 1f, 2f, 3f)
        val sources = arrayOf(series.x, series.y, series.z)
        fun axis(signed: Float): FloatArray {
            val source = sources[Math.abs(signed).toInt() - 1]
            return if (signed > 0) source.copyOf() else FloatArray(source.size) { -source[it] }
        }
        return MappedSeries(series.fileUs, axis(axes[0]), axis(axes[1]), axis(axes[2]))
    }

    private fun assertSameSeries(expected: MappedSeries, actual: MappedSeries) {
        assertArrayEquals(expected.fileUs, actual.fileUs)
        for (i in 0 until expected.size) {
            assertEquals(expected.x[i].toRawBits(), actual.x[i].toRawBits())
            assertEquals(expected.y[i].toRawBits(), actual.y[i].toRawBits())
            assertEquals(expected.z[i].toRawBits(), actual.z[i].toRawBits())
        }
    }

    private fun assertMatchesOracle(take: Take, spool: MotionSpool) {
        val timeline = timeline(spool)
        assertTrue("the take has a pause and a rate change", timeline.globalRateScale > 0.0)
        val out = StringWriter()
        val result = MotionRender.renderGcsv(input(spool, timeline), out)
        val readout = 11.0 / timeline.globalRateScale
        assertEquals(oldGcsv(take, timeline, readout), out.toString())

        val gyro = oldResample(take.gyro, timeline, scaleByRate = true)
        assertEquals(gyro.size.toLong(), result.gcsvRows)
        assertEquals(gyro.size, result.gyroSamples)
        assertEquals(oldResample(take.accel, timeline, scaleByRate = false).size, result.accelSamples)
        assertEquals(oldResample(take.mag, timeline, scaleByRate = false).size, result.magSamples)
        assertEquals(gyro.fileUs.first(), result.fileStartUs)
        assertEquals(gyro.fileUs.last(), result.fileEndUs)
        val t = take.gyro.timeNs
        assertEquals((t.size - 1) * 1_000_000_000.0 / (t.last() - t.first()), result.measuredGyroHz!!, 0.0)
        assertEquals((1 until t.size).maxOf { t[it] - t[it - 1] }, result.largestGyroGapNs)

        for (bin in MotionCammBudget.CANDIDATE_BINS_US) {
            val camm = MotionRender.cammSeries(input(spool, timeline), bin)
            assertSameSeries(oldRotate(oldResample(take.gyro, timeline, true, bin), "yxz"), camm.gyro)
            assertSameSeries(oldRotate(oldResample(take.accel, timeline, false, bin), "yxz"), camm.accel)
            assertSameSeries(oldRotate(oldResample(take.mag, timeline, false, bin), "yxz"), camm.mag)
        }
    }

    @Test fun streamingRenderEqualsTheInMemoryRenderAcrossAPauseAndARateChange() {
        val take = pausedTake()
        val spool = scanSpool(take.bytes)
        val timeline = timeline(spool)
        // The pause and the off-speed part give segments with rate scales far from 1.
        assertTrue(timeline.rateScale(startNs + 59 * frameNs + 1_000_000L)!! > 10.0)
        assertTrue(Math.abs(timeline.rateScale(spool.frames.sensorNs[70])!! - 2.0) < 1e-6)
        assertMatchesOracle(take, spool)
    }

    @Test fun outOfOrderAndDuplicateSamplesAreDroppedFromTheRender() {
        val take = pausedTake()
        val glitch = take.gyro.timeNs[200]
        // A duplicate of sample 200 and one from the past, both after sample 200, and an accelerometer duplicate.
        val bytes = take.bytes + spoolBytes {
            sample(MotionSpoolFormat.KIND_GYRO, glitch, 9f, 9f, 9f)
        }.copyOfRange(MotionSpoolFormat.HEADER_BYTES, MotionSpoolFormat.HEADER_BYTES + MotionSpoolFormat.RECORD_BYTES)
        val tail = spoolBytes {
            sample(MotionSpoolFormat.KIND_GYRO, take.gyro.timeNs.last(), 9f, 9f, 9f)
            sample(MotionSpoolFormat.KIND_GYRO, glitch, 7f, 7f, 7f)
            sample(MotionSpoolFormat.KIND_ACCEL, take.accel.timeNs.last(), 5f, 5f, 5f)
        }
        val spool = scanSpool(bytes + tail.copyOfRange(MotionSpoolFormat.HEADER_BYTES, tail.size))
        assertEquals(take.gyro.size.toLong(), spool.gyroStats.count)
        assertEquals(take.accel.size.toLong(), spool.accelStats.count)
        assertMatchesOracle(take, spool)
    }

    @Test fun aTornTailEndsTheRenderQuietly() {
        val take = pausedTake()
        // The last record is the last gyro, accelerometer or magnetometer sample; cut it short.
        val torn = take.bytes.copyOf(take.bytes.size - 7)
        val spool = scanSpool(torn)
        val last = readSpool(take.bytes)
        val kept = readSpool(torn)
        val lost = (last.gyro.size - kept.gyro.size) + (last.accel.size - kept.accel.size) + (last.mag.size - kept.mag.size)
        assertEquals(1, lost)
        val trimmed = Take(torn, kept.gyro, kept.accel, kept.mag)
        assertMatchesOracle(trimmed, spool)
    }

    @Test fun cammBinChoiceMatchesBuildingThenCounting() {
        val take = pausedTake()
        val spool = scanSpool(take.bytes)
        val input = input(spool)
        val plan = MotionRender.cammPlan(input, MotionCammBudget.CANDIDATE_BINS_US)
        val built = MotionCammBudget.CANDIDATE_BINS_US.associateWith { MotionCammBudget.packets(MotionRender.cammSeries(input, it)) }
        for (bin in MotionCammBudget.CANDIDATE_BINS_US) assertEquals(built.getValue(bin), plan.packets(bin))
        val largest = built.getValue(MotionCammBudget.DEFAULT_BIN_US)
        for (max in longArrayOf(0L, 10L, largest / 16, largest / 8, largest / 4, largest / 2, largest - 1, largest, Long.MAX_VALUE)) {
            assertEquals(
                "budget $max",
                MotionCammBudget.chooseBinUs(max) { built.getValue(it) },
                MotionCammBudget.chooseBinUs(max) { plan.packets(it) },
            )
        }
    }

    /** Discards what it is given; a long take's log would be hundreds of MB. */
    private class NullWriter : Writer() {
        var chars = 0L
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            chars += len
        }
        override fun write(str: String, off: Int, len: Int) {
            chars += len
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    /**
     * Three hours of 500 Hz gyroscope (5.4 M samples, a 113 MB spool, which the former render read whole) render in
     * one cursor of state per stream: every row comes out and the CAMM track gets the coarsest bin within budget.
     */
    @Test fun aThreeHourSpoolRendersInBoundedMemory() {
        val hours = 3L
        val gyroPeriodNs = 2_000_000L
        val gyroCount = hours * 3_600L * 1_000_000_000L / gyroPeriodNs
        val frameCount = hours * 3_600L * 30L
        val file = temp.newFile("long.ocmo")
        MotionSpoolWriter(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).apply {
            exposure(startNs, 10_000_000, 9_000_000)
            var frame = 0L
            for (i in 0 until gyroCount) {
                val t = startNs + i * gyroPeriodNs
                while (frame < frameCount && startNs + frame * frameNs <= t) {
                    frame(startNs + frame * frameNs, frame * frameNs)
                    frame++
                }
                sample(MotionSpoolFormat.KIND_GYRO, t, 0.01f, -0.02f, (i % 1_000).toFloat() / 1_000f)
            }
            close()
        }
        assertEquals(MotionSpoolFormat.HEADER_BYTES + (gyroCount + frameCount + 1) * MotionSpoolFormat.RECORD_BYTES, file.length())

        val spool = MotionSpoolReader.scan(file)
        assertEquals(frameCount.toInt(), spool.frames.size)
        assertEquals(gyroCount, spool.gyroStats.count)
        val input = input(spool)
        val out = NullWriter()
        val result = MotionRender.renderGcsv(input, out)
        assertEquals("every 2 ms sample has its own 1 ms bin", gyroCount, result.gcsvRows)
        assertEquals(0, result.accelSamples)
        assertEquals(500.0, result.measuredGyroHz!!, 1e-6)
        assertTrue(out.chars > gyroCount * 10)

        val plan = MotionRender.cammPlan(input, MotionCammBudget.CANDIDATE_BINS_US)
        assertEquals(gyroCount, plan.gyro[0])
        val bin = MotionCammBudget.chooseBinUs { plan.packets(it) }
        assertEquals("8 ms bins would be 1.35 M packets", 16_000L, bin)
        val camm = MotionRender.cammSeries(input, bin!!, plan)
        assertNotNull(camm)
        assertEquals(plan.gyro[plan.index(bin)], camm.gyro.size.toLong())
        assertTrue(MotionCammBudget.packets(camm) <= MotionCammBudget.MAX_PACKETS)
        assertEquals(hours * 3_600L * 1_000L / 16, camm.gyro.size.toLong())
    }
}
