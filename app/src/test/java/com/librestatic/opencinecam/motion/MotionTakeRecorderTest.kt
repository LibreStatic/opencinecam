/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MotionTakeRecorderTest {
    @get:Rule val folder = TemporaryFolder()

    private val startNs = 40_000_000_000L
    private val frameNs = 33_333_333L

    private val info = MotionTakeInfo(
        takeId = "take-1", videoFileName = "A001.mp4", orientation = "yxz", readoutDirection = 0, frontFacing = false,
        sensorOrientationDegrees = 90, storedRotationDegrees = 0, cameraClockRealtime = true, clockOffsetNs = 0L,
        startedAtUnixMs = 1_700_000_000_000L, firmware = "OpenCineCam test", route = MotionTakeRoute.GPU, captureFps = 30.0,
    )

    /** A spool file whose [failAt]th write (1-based, the header is two) throws once without writing. */
    private class FailingOnce(file: File, private val failAt: Int) : OutputStream() {
        private val out = FileOutputStream(file)
        private var writes = 0
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (++writes == failAt) throw IOException("no space left")
            out.write(b, off, len)
        }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    private class FakeAcquisition : MotionAcquisition {
        override val sensors = MotionSensorSet(200.0, false, null, false, null)
        var stops = 0
        var ends = true
        var pending: (() -> Unit)? = null
        override fun stop(): Boolean {
            stops++
            return ends
        }
        override fun stopThen(after: () -> Unit) {
            pending = after
        }
    }

    private fun recorder(stream: (File) -> OutputStream = ::FileOutputStream): Pair<MotionTakeRecorder, FakeAcquisition> {
        val spool = File(folder.root, "take-1.ocmo")
        val recorder = MotionTakeRecorder(info, spool, File(folder.root, "take-1.json"), File(folder.root, "take-1.gcsv"),
            MotionTakeRecorder.openWriter(spool, stream))
        val acquisition = FakeAcquisition()
        recorder.attachAcquisition(acquisition)
        return recorder to acquisition
    }

    /** 2.5 s of a 30 fps take with the gyroscope at 200 Hz; a sample whose write fails is passed over, as the source does. */
    private fun record(recorder: MotionTakeRecorder): Int {
        for (i in 0 until 75) recorder.onFrame(startNs + i * frameNs, 9_000_000_000L + i * frameNs)
        var failures = 0
        for (i in 0 until 500) {
            try {
                recorder.consumer.onSample(MotionSpoolFormat.KIND_GYRO, startNs + 1_000_000L + i * 5_000_000L, 0f, 0f, 1f)
            } catch (_: IOException) {
                failures++
            }
        }
        return failures
    }

    @Test fun aSensorThreadWriteFailureEndsTheTakesMotionData() {
        // The header is writes 1 and 2, the flush after 1 s is write 3: the flush after 2 s fails, then storage recovers.
        val (recorder, _) = recorder { FailingOnce(it, failAt = 4) }
        assertEquals("the failure reaches the sensor source once, which stops delivery", 1, record(recorder))
        assertNull("a spool that lost samples renders no log", recorder.render(MotionTakeTiming.EncodedFrames(0L), withCamm = false))
    }

    @Test fun aHealthySpoolRendersForComparison() {
        val (recorder, _) = recorder()
        assertEquals(0, record(recorder))
        assertNotNull(recorder.render(MotionTakeTiming.EncodedFrames(0L), withCamm = false))
    }

    @Test fun stoppingAcquisitionNeverWaitsAndKeepsTheSpool() {
        val (recorder, acquisition) = recorder()
        record(recorder)
        recorder.stopAcquisition()
        recorder.stopAcquisition()
        assertNotNull(acquisition.pending)
        val closeOnSensorThread = acquisition.pending!!
        assertEquals("the blocking stop is not used", 0, acquisition.stops)
        // The sensor thread closes the spool; it stays on disk for the seal or a later salvage.
        closeOnSensorThread.invoke()
        assertTrue(recorder.spoolFile.isFile)
        val length = recorder.spoolFile.length()
        assertTrue(length > MotionSpoolFormat.HEADER_BYTES)
        // Late samples and frames after the detach change nothing.
        record(recorder)
        assertEquals(length, recorder.spoolFile.length())
        assertNotNull(recorder.render(MotionTakeTiming.EncodedFrames(0L), withCamm = false))
        // The render waits for the sensor thread to hand over what it still held before reading the spool.
        assertEquals(1, acquisition.stops)
    }

    @Test fun aRenderBeforeTheSensorThreadClosesTheSpoolClosesItItself() {
        val (recorder, acquisition) = recorder()
        record(recorder)
        recorder.stopAcquisition()
        val rendered = recorder.render(MotionTakeTiming.EncodedFrames(0L), withCamm = false)
        assertNotNull(rendered)
        assertEquals(500, rendered!!.result.gyroSamples)
        // The sensor thread's close comes later and is harmless.
        acquisition.pending!!.invoke()
    }

    @Test fun aSensorThreadThatOutlastsTheStopLeavesNoMotionData() {
        val (recorder, acquisition) = recorder()
        record(recorder)
        // The thread is stalled in storage: samples it accepted may never reach the spool.
        acquisition.ends = false
        assertNull(recorder.render(MotionTakeTiming.EncodedFrames(0L), withCamm = false))
    }

    @Test fun aFullFrameQueueStopsTheSensorsAtTheNextSample() {
        val (recorder, _) = recorder()
        // No sample drains the queue, so one frame more than it holds is lost.
        for (i in 0..MotionSpoolFormat.QUEUE_RECORDS) recorder.onFrame(startNs + i * frameNs, i * frameNs)
        assertThrows(IOException::class.java) {
            recorder.consumer.onSample(MotionSpoolFormat.KIND_GYRO, startNs + 1_000_000L, 0f, 0f, 1f)
        }
        // Delivery has stopped: later samples are passed over without another failure.
        recorder.consumer.onSample(MotionSpoolFormat.KIND_GYRO, startNs + 6_000_000L, 0f, 0f, 1f)
        assertNull(recorder.render(MotionTakeTiming.EncodedFrames(0L), withCamm = false))
    }

    @Test fun aWriterThatCannotStartClosesItsStream() {
        var closed = false
        val stream = object : OutputStream() {
            override fun write(b: Int) = throw IOException("no space left")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("no space left")
            override fun close() {
                closed = true
            }
        }
        assertThrows(IOException::class.java) { MotionTakeRecorder.openWriter(File(folder.root, "x.ocmo")) { stream } }
        assertTrue(closed)
        assertFalse(File(folder.root, "x.ocmo").exists())
    }
}
