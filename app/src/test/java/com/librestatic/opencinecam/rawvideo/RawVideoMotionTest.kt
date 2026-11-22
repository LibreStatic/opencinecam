/* SPDX-License-Identifier: Apache-2.0 */

package com.librestatic.opencinecam.rawvideo

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoSessionConfig
import com.librestatic.opencinecam.media.ocraw.OcrawChunkId
import com.librestatic.opencinecam.media.ocraw.OcrawCodec
import com.librestatic.opencinecam.media.ocraw.OcrawImuMetadata
import com.librestatic.opencinecam.media.ocraw.OcrawImuSamples
import com.librestatic.opencinecam.media.ocraw.OcrawMetadataCbor
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.motion.MotionSpoolFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The `IMU_SAMPLES` chunks and IMU metadata a RAW take with motion data writes beside its frames. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RawVideoMotionTest {
    private lateinit var dir: File

    @Before fun setUp() {
        // Under the module's build directory: /tmp is RAM-backed on the dev hosts.
        dir = File("build/tmp/raw-motion-test/${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private inner class FileOutput : RawVideoTakeOutput {
        val file = File(dir, "take.ocraw")
        private val raf = RandomAccessFile(file, "rw")
        override val displayName: String = file.name
        override val channel: FileChannel = raf.channel
        override fun publish(): Uri {
            channel.close(); raf.close()
            return Uri.fromFile(file)
        }
        override fun discard() {
            channel.close(); raf.close(); file.delete()
        }
    }

    private class HeapSlots : RawSlotMemory {
        override fun allocate(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes)
        override fun close() = Unit
    }

    /** A test-driven [RawMotionSource]: the test sends samples through [sink]; [stop] sends a last one. */
    private class FakeMotion(
        override val clockOffsetNs: Long,
        private val starts: Boolean = true,
        private val tailNs: Long? = null,
    ) : RawMotionSource {
        var sink: RawMotionSink? = null
        val events = mutableListOf<String>()
        override val metadata: Map<String, String> =
            RawImuBuffer.takeMetadata("yxz", 0, sensorRealtime = clockOffsetNs == 0L, takeStartUnixMs = 1_763_700_000_000L)

        override fun start(sink: RawMotionSink): Boolean {
            events += "start"
            if (starts) this.sink = sink
            return starts
        }

        override fun stop() {
            events += "stop"
            tailNs?.let { sink?.onSample(MotionSpoolFormat.KIND_GYRO, it, 9f, 9f, 9f) }
            sink = null
        }

        fun gyro(ns: Long, v: Float) = requireNotNull(sink).onSample(MotionSpoolFormat.KIND_GYRO, ns, v, v + 1, v + 2)
        fun accel(ns: Long, v: Float) = requireNotNull(sink).onSample(MotionSpoolFormat.KIND_ACCEL, ns, v, v, v)
    }

    private fun recorder() = RawVideoRecorder(
        slotMemoryFactory = { HeapSlots() }, finalResultWaitMs = 0, writerJoinTimeoutMs = 10_000,
    ).also { it.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 8) }

    private fun info() = RawTakeInfo(UUID.randomUUID().toString(), "TestPhone", "9.9.9")

    private fun frame(recorder: RawVideoRecorder, index: Int) =
        assertTrue(recorder.onFrame(ByteBuffer.allocateDirect(ROW_BYTES * HEIGHT), ROW_BYTES, WIDTH, HEIGHT, ts(index)))

    @Test
    fun samplesBecomeImuChunksOnTheFrameClockWithTheTakeKeys() {
        val recorder = recorder()
        val output = FileOutput()
        val offset = 500_000L
        val motion = FakeMotion(clockOffsetNs = offset, tailNs = ts(3) + offset + 2_000_000L)
        recorder.start(output, info(), motion = motion)
        recorder.onTakeStarted("0", RawVideoSessionConfig(WIDTH, HEIGHT, 30), mapOf("cfa" to "RGGB"))

        // A second of samples before and around the first frames makes one batch; the rest goes at the stop.
        motion.gyro(ts(0) + offset - 10_000_000L, 1f)
        motion.accel(ts(0) + offset - 9_000_000L, 2f)
        frame(recorder, 0)
        motion.gyro(ts(0) + offset + 990_000_000L, 3f)
        frame(recorder, 1)
        motion.gyro(ts(1) + offset + 5_000_000L, 4f)
        frame(recorder, 2)
        frame(recorder, 3)
        recorder.onRollingShutterSkew(12_345_678L)

        val result = recorder.stop()
        assertNull(result.reason)
        assertEquals(listOf("start", "stop"), motion.events)

        val all = OcrawCodec.scanContainer(output.file.readBytes()).chunks
        val firstMetadataAt = all.indexOfFirst { it.wireId == OcrawChunkId.METADATA.wireId }
        val imuChunks = all.filter { it.wireId == OcrawChunkId.IMU_SAMPLES.wireId }
        assertTrue("metadata precedes the motion data", firstMetadataAt < all.indexOfFirst { it.wireId == OcrawChunkId.IMU_SAMPLES.wireId })
        assertEquals(2, imuChunks.size)

        val first = OcrawImuSamples.decode(imuChunks[0].payload)!!
        assertEquals(3, first.count)
        assertArrayEquals(longArrayOf(ts(0) - 10_000_000L, ts(0) - 9_000_000L, ts(0) + 990_000_000L), first.timesNs.copyOf(3))
        assertEquals(OcrawImuSamples.KIND_ACCELEROMETER, first.kind(1))
        assertEquals(first.timesNs[0], imuChunks[0].timestampTicks)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), first.xyz.copyOf(3), 0f)
        val second = OcrawImuSamples.decode(imuChunks[1].payload)!!
        // The sample sent while the source stopped is written too.
        assertArrayEquals(longArrayOf(ts(1) + 5_000_000L, ts(3) + 2_000_000L), second.timesNs.copyOf(second.count))
        assertEquals(all.first { it.wireId == OcrawChunkId.RAW_FRAME.wireId }.timeBase, imuChunks[0].timeBase)

        val firstMetadata = OcrawMetadataCbor.decode(all[firstMetadataAt].payload)
        assertEquals("yxz", firstMetadata[OcrawImuMetadata.ORIENTATION])
        assertEquals("0", firstMetadata[OcrawImuMetadata.READOUT_DIRECTION])
        assertEquals(OcrawImuMetadata.CLOCK_ESTIMATED, firstMetadata[OcrawImuMetadata.CLOCK])
        assertEquals("1763700000000", firstMetadata[OcrawImuMetadata.TAKE_START_UNIX_MS])
        assertNull(firstMetadata[OcrawImuMetadata.ROLLING_SHUTTER_SKEW_NS])

        RandomAccessFile(output.file, "r").use { raf ->
            val reader = OcrawStreamReader(raf.channel)
            val index = reader.indexFrames()
            assertTrue(index.hasEnd)
            assertEquals(4, index.frames.size)
            assertEquals(2, index.imu.size)
            // The closing chunk adds the skew the capture results reported.
            assertEquals("12345678", index.metadata[OcrawImuMetadata.ROLLING_SHUTTER_SKEW_NS])
            assertEquals("RGGB", index.metadata["cfa"])
        }
    }

    @Test
    fun aSourceThatDoesNotStartLeavesTheTakeWithoutMotionData() {
        val recorder = recorder()
        val output = FileOutput()
        val motion = FakeMotion(clockOffsetNs = 0, starts = false)
        recorder.start(output, info(), motion = motion)
        recorder.onTakeStarted("0", RawVideoSessionConfig(WIDTH, HEIGHT, 30), emptyMap())
        frame(recorder, 0)
        recorder.onRollingShutterSkew(1_000L)
        val result = recorder.stop()
        assertNull(result.reason)
        assertEquals(1, result.framesWritten)
        // A source that did not start is stopped right away and not again at the take's stop.
        assertEquals(listOf("start", "stop"), motion.events)

        val all = OcrawCodec.scanContainer(output.file.readBytes()).chunks
        assertTrue(all.none { it.wireId == OcrawChunkId.IMU_SAMPLES.wireId })
        RandomAccessFile(output.file, "r").use { raf ->
            val metadata = OcrawStreamReader(raf.channel).indexFrames().metadata
            assertTrue(metadata.keys.none { it.startsWith("imu") || it == OcrawImuMetadata.ROLLING_SHUTTER_SKEW_NS || it == OcrawImuMetadata.TAKE_START_UNIX_MS })
        }
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 16
        const val ROW_BYTES = WIDTH / 4 * 5
        val CONFIG = RawVideoConfig("0", WIDTH, HEIGHT, 32, 8, 30)

        fun ts(index: Int): Long = 1_000_000_000L + index * 33_333_333L
    }
}
