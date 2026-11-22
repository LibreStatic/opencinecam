/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTakeTest {
    private val frameNs = 33_333_333L
    private val startNs = 50_000_000_000L

    private fun info(
        route: MotionTakeRoute = MotionTakeRoute.GPU,
        captureFps: Double = 30.0,
        realtime: Boolean = true,
        clockOffsetNs: Long = 0L,
    ) = MotionTakeInfo(
        takeId = "take-1",
        videoFileName = "OCC_0001.mp4",
        orientation = "yxz",
        readoutDirection = 0,
        frontFacing = false,
        sensorOrientationDegrees = 90,
        storedRotationDegrees = 0,
        cameraClockRealtime = realtime,
        clockOffsetNs = clockOffsetNs,
        startedAtUnixMs = 1_700_000_000_123L,
        firmware = "OpenCineCam 2.1.0",
        route = route,
        captureFps = captureFps,
    )

    private fun frames(sensor: LongArray, pts: LongArray) = FrameTrack(sensor, pts)

    @Test fun takeDescriptionRoundTrips() {
        val full = info(route = MotionTakeRoute.MEDIA_RECORDER, captureFps = 240.0, realtime = false, clockOffsetNs = 1_234_567_890L)
            .copy(gyroMaxHz = 500.0, accelerometer = true, accelMaxHz = 400.0, magnetometer = true, magMaxHz = 100.0)
        assertEquals(full, MotionTakeInfo.fromJson(full.toJson()))
        val bare = info()
        assertEquals(bare, MotionTakeInfo.fromJson(bare.toJson()))
        assertFalse(bare.toJson().contains("gyroMaxHz"))
    }

    @Test fun takeDescriptionRejectsOtherSchemas() {
        val json = info().toJson().replace(MotionTakeInfo.SCHEMA, "opencinecam.other.v1")
        assertThrows(IllegalArgumentException::class.java) { MotionTakeInfo.fromJson(json) }
        assertThrows(IllegalArgumentException::class.java) { MotionTakeInfo.fromJson("[]") }
    }

    @Test fun gpuTakeIsOffsetByTheMuxerConstant() {
        val sensor = LongArray(30) { startNs + it * frameNs }
        val pts = LongArray(30) { 9_000_000_000L + it * frameNs }
        val timeline = MotionTakeRender.timeline(MotionTakeTiming.EncodedFrames(fileOffsetUs = 20_000L), frames(sensor, pts), info())!!
        assertFalse(timeline.estimated)
        assertEquals(20_000L, timeline.timeline.fileUs(startNs))
        assertEquals(20_000L + 333_333L, timeline.timeline.fileUs(sensor[10]))
        assertEquals(1.0, timeline.timeline.globalRateScale, 0.0)
    }

    @Test fun gpuTakeOnAMonotonicCameraClockAddsTheClockOffset() {
        val sensor = LongArray(30) { startNs + it * frameNs }
        val pts = LongArray(30) { it * frameNs }
        val offset = 7_000_000_000L
        val take = info(realtime = false, clockOffsetNs = offset)
        val timeline = MotionTakeRender.timeline(MotionTakeTiming.EncodedFrames(0L), frames(sensor, pts), take)!!
        assertEquals("sensor events are on boot time", 0L, timeline.timeline.fileUs(startNs + offset))
        assertNull(timeline.timeline.fileUs(startNs))
        val input = MotionTakeRender.input(emptySpool(), take, timeline)
        assertFalse(input.synced)
        assertEquals("sync estimated: camera clock is not the sensor clock", input.note)
    }

    @Test fun gpuTakeAcrossAPauseFoldsThePauseIntoOneFrame() {
        val before = LongArray(30) { startNs + it * frameNs }
        val resume = before.last() + 4_000_000_000L
        val sensor = before + LongArray(30) { resume + it * frameNs }
        // The shared pause removes the paused span: presentation times stay one frame apart across it.
        val pts = LongArray(60) { it * frameNs }
        val timeline = MotionTakeRender.timeline(MotionTakeTiming.EncodedFrames(0L), frames(sensor, pts), info())!!.timeline
        assertEquals(966_666L, timeline.fileUs(before.last()))
        assertEquals(999_999L, timeline.fileUs(resume))
        assertEquals(4_000_000_000.0 / frameNs, timeline.rateScale(before.last() + 1)!!, 1e-9)
        assertEquals(1.0, timeline.globalRateScale, 0.0)
    }

    @Test fun timeLapseFramesOneSecondApartPlayAtThirtyTimes() {
        val sensor = LongArray(20) { startNs + it * 1_000_000_000L }
        // TimelapseTimeline gives frame n the project time n / 30 s, which finalizeProjectMp4Timing writes exactly.
        val pts = LongArray(20) { it * 1_000_000_000L / 30 }
        val timeline = MotionTakeRender.timeline(MotionTakeTiming.EncodedFrames(0L), frames(sensor, pts), info(captureFps = 1.0))!!.timeline
        assertEquals(0L, timeline.fileUs(startNs))
        assertEquals(16_666L, timeline.fileUs(startNs + 500_000_000L))
        assertEquals(30.0, timeline.globalRateScale, 1e-6)
    }

    @Test fun anIncompleteGpuTakeNeedsTwoFrames() {
        val one = frames(longArrayOf(startNs), longArrayOf(0L))
        assertNull(MotionTakeRender.timeline(MotionTakeTiming.EncodedFrames(0L, minFrames = 2), one, info()))
        assertNotNull(MotionTakeRender.timeline(MotionTakeTiming.EncodedFrames(0L, minFrames = 1), one, info()))
        assertNull(MotionTakeRender.timeline(MotionTakeTiming.EncodedFrames(0L), frames(LongArray(0), LongArray(0)), info()))
    }

    private fun recorderTiming(
        frames: Long,
        drained: Boolean,
        fileSamples: Long,
        lastSampleTimeUs: Long,
        captureStepNs: Long,
        first: Long? = startNs,
    ) = MotionTakeTiming.RecorderAnchors(
        firstFrameSensorNs = first,
        lastFrameSensorNs = first?.let { it + (frames - 1) * captureStepNs },
        frames = frames,
        drained = drained,
        recorderStartElapsedNs = startNs - 100_000_000L,
        fileSamples = fileSamples,
        lastSampleTimeUs = lastSampleTimeUs,
    )

    @Test fun drainedHighSpeedTakeIsPinnedToTheFileEnds() {
        // 240 fps real time: 480 captured frames, the file holds the last 478 (two lost at the start).
        val step = 4_166_666L
        val timing = recorderTiming(frames = 480, drained = true, fileSamples = 478, lastSampleTimeUs = 477 * 4_167L, captureStepNs = step)
        val take = info(MotionTakeRoute.MEDIA_RECORDER, captureFps = 240.0)
        val result = MotionTakeRender.timeline(timing, FrameTrack(LongArray(0), LongArray(0)), take)!!
        assertFalse(result.estimated)
        val last = startNs + 479 * step
        assertEquals(477 * 4_167L, result.timeline.fileUs(last))
        assertEquals("the first frame lands where the frame count puts it", 0L, result.timeline.fileUs(startNs))
        assertEquals(1.0, result.timeline.globalRateScale, 0.01)
        assertTrue(MotionTakeRender.input(emptySpool(), take, result).synced)
        assertNull(MotionTakeRender.input(emptySpool(), take, result).note)
        // The frame interval after the last still maps, up to its exclusive end; a second past the stop does not.
        assertNotNull(result.timeline.fileUs(last + step - 1))
        assertNull(result.timeline.fileUs(last + step))
        assertNull(result.timeline.fileUs(last + 1_000_000_000L))
    }

    @Test fun aRecorderTimelineEndsOneFrameIntervalAfterTheLastFrame() {
        // 120 fps: a frame interval is 8.33 ms, so a sample 8 ms after the last frame maps and one 12 ms after does not.
        val step = 8_333_333L
        val take = info(MotionTakeRoute.MEDIA_RECORDER, captureFps = 120.0)
        for (drained in listOf(true, false)) {
            val timing = recorderTiming(frames = 240, drained = drained, fileSamples = 240, lastSampleTimeUs = 239 * 8_333L, captureStepNs = step)
            val timeline = MotionTakeRender.timeline(timing, FrameTrack(LongArray(0), LongArray(0)), take)!!.timeline
            val last = startNs + 239 * step
            assertNotNull("drained=$drained", timeline.fileUs(last + 8_000_000L))
            assertNull("drained=$drained", timeline.fileUs(last + 12_000_000L))
        }
    }

    @Test fun aGpuTakeWithAnAssumedMuxOffsetIsEstimated() {
        val sensor = LongArray(30) { startNs + it * frameNs }
        val pts = LongArray(30) { it * frameNs }
        val take = info()
        val assumed = MotionTakeRender.timeline(MotionTakeTiming.EncodedFrames(0L, minFrames = 2, offsetKnown = false),
            frames(sensor, pts), take)!!
        assertTrue(assumed.estimated)
        assertEquals(0L, assumed.timeline.fileUs(startNs))
        val input = MotionTakeRender.input(emptySpool(), take, assumed)
        assertFalse(input.synced)
        assertEquals("sync estimated: file offset not measured", input.note)
        // The muxer's own offset stays measured.
        val known = MotionTakeRender.timeline(MotionTakeTiming.EncodedFrames(20_000L, minFrames = 2), frames(sensor, pts), take)!!
        assertFalse(known.estimated)
        assertTrue(MotionTakeRender.input(emptySpool(), take, known).synced)
        assertNull(MotionTakeRender.input(emptySpool(), take, known).note)
    }

    @Test fun undrainedHighSpeedTakeStartsAtFileZeroAndIsEstimated() {
        val timing = recorderTiming(frames = 300, drained = false, fileSamples = 241, lastSampleTimeUs = 1_000_000L, captureStepNs = 4_166_666L)
        val take = info(MotionTakeRoute.MEDIA_RECORDER, captureFps = 240.0)
        val result = MotionTakeRender.timeline(timing, FrameTrack(LongArray(0), LongArray(0)), take)!!
        assertTrue(result.estimated)
        assertEquals(0L, result.timeline.fileUs(startNs))
        assertEquals(1_000_000L, result.timeline.fileUs(startNs + 1_000_000_000L))
        assertEquals(1.0, result.timeline.globalRateScale, 1e-3)
        val input = MotionTakeRender.input(emptySpool(), take, result)
        assertFalse(input.synced)
        assertEquals("sync estimated: recorder timing not measured", input.note)
    }

    @Test fun undrainedHighSpeedTakeWithoutAFrameStartsAtTheRecorderStart() {
        val timing = recorderTiming(frames = 0, drained = false, fileSamples = 241, lastSampleTimeUs = 1_000_000L,
            captureStepNs = 4_166_666L, first = null)
        val result = MotionTakeRender.timeline(timing, FrameTrack(LongArray(0), LongArray(0)), info(MotionTakeRoute.MEDIA_RECORDER, 240.0))!!
        assertEquals(0L, result.timeline.fileUs(startNs - 100_000_000L))
    }

    @Test fun offSpeedHighSpeedFileIsStretchedEightTimes() {
        // 240 fps captured, written at 30 fps: 240 frames span 239 / 30 s of file.
        val step = 4_166_666L
        val lastUs = 239 * 33_333L
        val take = info(MotionTakeRoute.MEDIA_RECORDER, captureFps = 240.0)
        for (drained in listOf(true, false)) {
            val timing = recorderTiming(frames = 240, drained = drained, fileSamples = 240, lastSampleTimeUs = lastUs, captureStepNs = step)
            val result = MotionTakeRender.timeline(timing, FrameTrack(LongArray(0), LongArray(0)), take)!!
            assertEquals(drained, !result.estimated)
            assertEquals(0.125, result.timeline.globalRateScale, 0.001)
            assertEquals(0L, result.timeline.fileUs(startNs))
            assertEquals(lastUs.toDouble(), result.timeline.fileUs(startNs + 239 * step)!!.toDouble(), 10.0)
            // 10 ms of real time is 80 ms of file time.
            assertEquals(80_000.0, result.timeline.fileUs(startNs + 10_000_000L)!!.toDouble(), 10.0)
        }
    }

    @Test fun aRecorderFileWithoutTwoSamplesHasNoTimeline() {
        val timing = recorderTiming(frames = 10, drained = true, fileSamples = 1, lastSampleTimeUs = 0L, captureStepNs = frameNs)
        assertNull(MotionTakeRender.timeline(timing, FrameTrack(LongArray(0), LongArray(0)), info(MotionTakeRoute.MEDIA_RECORDER)))
    }

    @Test fun fileStemsAreSafe() {
        assertEquals("a_b_c-1.2", MotionTakeRecorder.fileStem("a/b c-1.2"))
        assertEquals("take", MotionTakeRecorder.fileStem(""))
    }

    private fun emptySpool(): MotionSpool = scanSpool(spoolBytes { })
}
