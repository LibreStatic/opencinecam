/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import java.io.ByteArrayOutputStream
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRenderTest {
    private val startNs = 10_000_000_000L
    private val frameNs = 33_333_333L

    /** 2 s turning at 1 rad/s about device z (200 Hz), gravity on device y (100 Hz), 30 fps frames. */
    private fun spool(): MotionSpool {
        val bytes = ByteArrayOutputStream()
        val writer = MotionSpoolWriter(bytes)
        writer.exposure(startNs, 10_000_000, 0)
        for (i in 0 until 60) writer.frame(startNs + i * frameNs, 7_000_000_000L + i * frameNs)
        for (i in 0 until 400) {
            writer.sample(MotionSpoolFormat.KIND_GYRO, startNs + i * 5_000_000L, 0f, 0f, 1f)
            if (i % 2 == 0) writer.sample(MotionSpoolFormat.KIND_ACCEL, startNs + i * 5_000_000L, 0f, 9.80665f, 0f)
        }
        writer.exposure(startNs + 1_000_000_000L, 10_000_000, 12_000_000)
        writer.exposure(startNs + 1_500_000_000L, 8_000_000, 0)
        writer.close()
        return scanSpool(bytes.toByteArray())
    }

    private fun input(spool: MotionSpool, timeline: MotionTimeline) = MotionRenderInput(
        spool = spool,
        timeline = timeline,
        orientation = "yxz",
        videoFileName = "OCC_0001.mp4",
        unixSeconds = 1_700_000_000L,
        firmware = "1.0.0",
        synced = true,
        readoutDirection = 0,
    )

    @Test fun rendersANormalTakeEndToEnd() {
        val spool = spool()
        val timeline = MotionTimeline.fromFrames(spool.frames, fileOffsetUs = 0, clockOffsetNs = 0)
        val out = StringWriter()
        val result = MotionRender.renderGcsv(input(spool, timeline), out)

        assertEquals(400L, result.gcsvRows)
        assertEquals(400, result.gyroSamples)
        assertEquals(200, result.accelSamples)
        assertEquals(0, result.magSamples)
        assertEquals(200.0, result.measuredGyroHz!!, 1e-9)
        assertEquals(100.0, result.measuredAccelHz!!, 1e-9)
        assertNull(result.measuredMagHz)
        assertEquals(5_000_000L, result.largestGyroGapNs)
        assertEquals(1.0, result.globalRateScale, 0.0)
        assertEquals(0L, result.fileStartUs)
        assertEquals(1_995_000L, result.fileEndUs)

        val lines = out.toString().lines()
        assertEquals("GYROFLOW IMU LOG", lines[0])
        assertTrue(lines.contains("orientation,yxz"))
        assertTrue(lines.contains("videofilename,OCC_0001.mp4"))
        assertTrue("the latest known skew", lines.contains("frame_readout_time,12.0000"))
        assertTrue(lines.contains("frame_readout_direction,0"))
        assertFalse(lines.any { it.startsWith("mscale") })
        val columns = lines.indexOf("t,gx,gy,gz,ax,ay,az")
        assertTrue(columns > 0)
        val rows = lines.drop(columns + 1).dropLast(1)
        assertEquals(400, rows.size)
        // Raw device axes: the gyro stays on z and gravity on y.
        assertEquals("0,0,0,100000,0,100000,0", rows.first())
        assertEquals("1995000,0,0,100000,0,100000,0", rows.last())
        assertEquals("", lines.last())
    }

    @Test fun slowMotionStretchesTimesAndScalesRateAndReadout() {
        val spool = spool()
        val timeline = MotionTimeline.linear(startNs, 0L, startNs + 1_000_000_000L, 8_000_000L, clockOffsetNs = 0)
        val out = StringWriter()
        val result = MotionRender.renderGcsv(input(spool, timeline), out)

        assertEquals(400L, result.gcsvRows)
        assertEquals(0.125, result.globalRateScale, 0.0)
        assertEquals(15_960_000L, result.fileEndUs)
        assertEquals("raw clock, not file clock", 200.0, result.measuredGyroHz!!, 1e-9)
        val lines = out.toString().lines()
        assertTrue("12 ms of skew is 96 ms of file time", lines.contains("frame_readout_time,96.0000"))
        assertTrue(lines.contains("40000,0,0,12500,0,100000,0"))
    }

    @Test fun cammSeriesIsInImageAxesWithExposureChanges() {
        val spool = spool()
        val timeline = MotionTimeline.fromFrames(spool.frames, fileOffsetUs = 0, clockOffsetNs = 0)
        val camm = MotionRender.cammSeries(input(spool, timeline))

        assertEquals(400, camm.gyro.size)
        // "yxz": image X = -device y, Y = -device x, Z = -device z.
        assertEquals(0f, camm.gyro.x[10], 0f)
        assertEquals(0f, camm.gyro.y[10], 0f)
        assertEquals(-1f, camm.gyro.z[10], 0f)
        assertEquals(-9.80665f, camm.accel.x[10], 0f)
        assertEquals(0f, camm.accel.y[10], 0f)
        assertEquals(0, camm.mag.size)

        // One entry per change, at the first frame it applies to: frames 0, 31 and 46.
        assertEquals(listOf(0L, 1_033_333L, 1_533_333L), camm.exposureFileUs.toList())
        assertEquals(listOf(10_000_000, 10_000_000, 8_000_000), camm.exposureNs.toList())
        assertEquals(listOf(0, 12_000_000, 0), camm.skewNs.toList())
    }

    @Test fun cammSeriesBinsCoarserWhenAsked() {
        val spool = spool()
        val timeline = MotionTimeline.fromFrames(spool.frames, fileOffsetUs = 0, clockOffsetNs = 0)
        val fine = MotionRender.cammSeries(input(spool, timeline))
        val coarse = MotionRender.cammSeries(input(spool, timeline), binUs = 10_000)

        assertEquals(400, fine.gyro.size)
        assertEquals("5 ms gyro samples, two per 10 ms bin", 200, coarse.gyro.size)
        assertEquals(-1f, coarse.gyro.z[50], 0f)
        assertEquals("exposure changes do not depend on the bin", fine.exposureFileUs.toList(), coarse.exposureFileUs.toList())
    }

    @Test fun cammSeriesWithoutFramesPlacesEachExposureRecord() {
        val spool = spool()
        val noFrames = MotionSpool(
            FrameTrack(LongArray(0), LongArray(0)), spool.exposures, spool.gyroStats, spool.accelStats, spool.magStats, spool.open,
        )
        val timeline = MotionTimeline.linear(startNs, 0L, startNs + 2_000_000_000L, 2_000_000L, clockOffsetNs = 0)
        val camm = MotionRender.cammSeries(input(noFrames, timeline))

        assertEquals(listOf(0L, 1_000_000L, 1_500_000L), camm.exposureFileUs.toList())
        assertEquals(listOf(0, 12_000_000, 0), camm.skewNs.toList())
    }

    @Test fun cammSeriesWithoutFramesKeepsTheExposureSetBeforeTheFirstFrame() {
        // Motion capture starts before MediaRecorder: the exposure arrives before the first frame and never changes.
        val exposures = ExposureTrack(longArrayOf(startNs - 300_000_000L, startNs - 200_000_000L), intArrayOf(8_000_000, 10_000_000),
            intArrayOf(0, 9_000_000))
        val spool = spool()
        val noFrames = MotionSpool(
            FrameTrack(LongArray(0), LongArray(0)), exposures, spool.gyroStats, spool.accelStats, spool.magStats, spool.open,
        )
        val timeline = MotionTimeline.linear(startNs, 40_000L, startNs + 2_000_000_000L, 2_040_000L, clockOffsetNs = 0)
        val camm = MotionRender.cammSeries(input(noFrames, timeline))

        assertEquals("the latest one in force at the first frame, at its file time", listOf(40_000L), camm.exposureFileUs.toList())
        assertEquals(listOf(10_000_000), camm.exposureNs.toList())
        assertEquals(listOf(9_000_000), camm.skewNs.toList())

        // One that applies from the first frame itself replaces the one before it.
        val atStart = MotionSpool(
            FrameTrack(LongArray(0), LongArray(0)),
            ExposureTrack(longArrayOf(startNs - 1L, startNs), intArrayOf(8_000_000, 10_000_000), intArrayOf(0, 0)),
            spool.gyroStats, spool.accelStats, spool.magStats, spool.open,
        )
        val replaced = MotionRender.cammSeries(input(atStart, timeline))
        assertEquals(listOf(40_000L), replaced.exposureFileUs.toList())
        assertEquals(listOf(10_000_000), replaced.exposureNs.toList())
    }
}
