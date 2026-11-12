/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoSessionConfig
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamScan
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RawVideoRecorderTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("raw-recorder").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    /** A temp-file output; [gate] can wrap the channel so writes block or fail. */
    private inner class FileOutput(
        name: String = "take.ocraw",
        wrap: (FileChannel) -> FileChannel = { it },
    ) : RawVideoTakeOutput {
        val file = File(dir, name)
        private val raf = RandomAccessFile(file, "rw")
        override val displayName: String = name
        override val channel: FileChannel = wrap(raf.channel)
        var published = false
        var discarded = false

        override fun publish(): Uri {
            channel.close(); raf.close(); published = true
            return Uri.fromFile(file)
        }

        override fun discard() {
            channel.close(); raf.close(); file.delete(); discarded = true
        }
    }

    private class HeapSlots(private val failAfter: Int = Int.MAX_VALUE) : RawSlotMemory {
        var allocated = 0
        var closed = false
        override fun allocate(bytes: Int): ByteBuffer {
            if (allocated >= failAfter) throw OutOfMemoryError("test")
            allocated++
            return ByteBuffer.allocateDirect(bytes)
        }
        override fun close() { closed = true }
    }

    private fun recorder(slots: HeapSlots = HeapSlots(), finalWaitMs: Long = 100) =
        RawVideoRecorder(slotMemoryFactory = { slots }, finalResultWaitMs = finalWaitMs, writerJoinTimeoutMs = 10_000)

    private fun info() = RawTakeInfo(UUID.randomUUID().toString(), "TestPhone", "9.9.9", mapOf("fingerprint" to "fp"))

    /** A RAW10 plane of [WIDTH]x[HEIGHT] whose bytes encode the frame [index], with stride padding. */
    private fun plane(index: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(STRIDE * HEIGHT)
        for (row in 0 until HEIGHT) for (col in 0 until STRIDE) {
            buffer.put(row * STRIDE + col, if (col >= ROW_BYTES) 0x7f else ((index * 31 + row * 7 + col) and 0xff).toByte())
        }
        return buffer
    }

    /** What the cropped frame [index] must hold on disk. */
    private fun expectedCrop(index: Int, crop: RawVideoCrop): ByteArray {
        val rowBytes = crop.width / 4 * 5
        val skip = crop.left / 4 * 5
        return ByteArray(rowBytes * crop.height) { offset ->
            val row = crop.top + offset / rowBytes
            val col = skip + offset % rowBytes
            ((index * 31 + row * 7 + col) and 0xff).toByte()
        }
    }

    private fun scan(file: File): OcrawStreamScan =
        RandomAccessFile(file, "r").use { OcrawStreamReader(it.channel).scan() }

    private fun frameData(file: File, offset: Long, bytes: Int): Pair<OcrawFrameHeader, ByteArray> =
        RandomAccessFile(file, "r").use { raf ->
            val dst = ByteBuffer.allocate(bytes)
            val header = OcrawStreamReader(raf.channel).readRawFrame(offset, dst)
            dst.flip()
            header to ByteArray(dst.remaining()).also(dst::get)
        }

    @Test
    fun takeWritesCroppedFramesStaticMetadataAndMatchedFrameMetadata() {
        val recorder = recorder()
        val armed = recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 8)
        assertEquals(RawVideoCrop(16, 4, 32, 8), armed.crop)
        assertEquals(8, armed.slotCount)
        val output = FileOutput()
        recorder.start(output, info())
        recorder.onTakeStarted(
            "2",
            RawVideoSessionConfig(WIDTH, HEIGHT, 30),
            mapOf("cfa" to "RGGB", "blackLevel" to "64 64 64 64"),
            orientationDegrees = 90,
        )

        // Frame 0's result arrives before its frame; frames 1-3 get theirs afterwards; frame 4 never does.
        recorder.onResult(ts(0), 1000, mapOf("iso" to "100"))
        for (index in 0 until 5) assertTrue(recorder.onFrame(plane(index), STRIDE, WIDTH, HEIGHT, ts(index)))
        for (index in 1 until 4) recorder.onResult(ts(index), 1000L + index, mapOf("iso" to "${(index + 1) * 100}"))
        recorder.onResult(999_999, 5000, mapOf("iso" to "1")) // a result whose frame never comes

        val live = recorder.liveStats()
        assertTrue(live.recording)
        assertEquals(5, live.framesCaptured)
        assertEquals(0, live.droppedFrames)

        val result = recorder.stop()
        assertNull(result.reason)
        assertTrue(output.published)
        assertEquals(Uri.fromFile(output.file), result.uri)
        assertEquals(5, result.framesCaptured)
        assertEquals(5, result.framesWritten)
        assertEquals(0, result.droppedFrames)
        assertEquals(4, result.frameMetadataWritten)
        assertEquals(1, result.unmatchedResults)
        assertEquals(ts(4) - ts(0), result.mediaDurationNs)
        assertFalse(result.recovered)
        assertEquals(output.file.length(), result.bytesWritten)
        assertFalse(recorder.liveStats().recording)

        val scan = scan(output.file)
        assertTrue(scan.errors.toString(), scan.cleanlyClosed)
        assertEquals(5, scan.frameCount)
        val metadata = scan.metadata
        assertEquals("16 4 32 8", metadata["crop"])
        assertEquals("30/1", metadata["fps"])
        assertEquals("2", metadata["cameraId"])
        assertEquals("$WIDTH", metadata["sensorWidth"])
        assertEquals("$HEIGHT", metadata["sensorHeight"])
        assertEquals("TestPhone", metadata["model"])
        assertEquals("9.9.9", metadata["appVersion"])
        assertEquals("RGGB", metadata["cfa"])
        assertEquals("64 64 64 64", metadata["blackLevel"])
        assertEquals("fp", metadata["fingerprint"])
        assertEquals("90", metadata[RawVideoMetadata.KEY_ORIENTATION])

        scan.frames.forEachIndexed { index, frame ->
            val header = requireNotNull(frame.header)
            assertEquals(ts(index), header.sensorTimestampNs)
            assertEquals(ts(index), frame.timestampTicks)
            assertEquals(16, header.cropLeft)
            assertEquals(4, header.cropTop)
            assertEquals(32, header.width)
            assertEquals(8, header.height)
            val (_, data) = frameData(output.file, frame.fileOffset, header.dataLength)
            assertArrayEquals("frame $index", expectedCrop(index, armed.crop), data)
            val values = scan.frameMetadata[frame.sequence]
            if (index < 4) assertEquals("frame $index", "${(index + 1) * 100}", requireNotNull(values)["iso"])
            else assertNull(values)
        }
        // The header's frame number is always the take-local index; the Camera2 one is frame metadata.
        scan.frames.forEachIndexed { index, frame -> assertEquals(index.toLong(), frame.header!!.frameNumber) }
        for (index in 0 until 4) {
            assertEquals("${1000L + index}", scan.frameMetadata.getValue(scan.frames[index].sequence)[RawVideoRecorder.FRAME_NUMBER_KEY])
        }
    }

    @Test
    fun theLastFramesResultArrivingAfterItWasWrittenStillBecomesItsMetadata() {
        // The engine forwards the last frame's result before it reports the take stopped, so the
        // result reaches the recorder after the frame is on disk and before stop(); no backstop wait.
        val recorder = recorder(finalWaitMs = 0)
        recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 8)
        val output = FileOutput()
        recorder.start(output, info())
        recorder.onTakeStarted("0", RawVideoSessionConfig(WIDTH, HEIGHT, 30), emptyMap())
        for (index in 0 until 3) assertTrue(recorder.onFrame(plane(index), STRIDE, WIDTH, HEIGHT, ts(index)))
        for (index in 0 until 2) recorder.onResult(ts(index), index.toLong(), mapOf("iso" to "100"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (recorder.liveStats().framesWritten < 3 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(3, recorder.liveStats().framesWritten)
        recorder.onResult(ts(2), 2, mapOf("iso" to "400"))

        val result = recorder.stop()
        assertEquals(3, result.frameMetadataWritten)
        assertEquals(0, result.unmatchedResults)
        val scan = scan(output.file)
        assertEquals("400", scan.frameMetadata.getValue(scan.frames[2].sequence)["iso"])
    }

    @Test
    fun resultsThatStallAtTheTakeStartAndArriveInABurstStillBecomeTheirFramesMetadata() {
        // Seen on the Razr: the first results arrive, then none for about 45 frames, then all at once.
        val recorder = recorder(finalWaitMs = 0)
        recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 8)
        val output = FileOutput()
        recorder.start(output, info())
        recorder.onTakeStarted("0", RawVideoSessionConfig(WIDTH, HEIGHT, 30), emptyMap())
        val frames = 60
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        for (index in 0 until frames) {
            assertTrue(recorder.onFrame(plane(index), STRIDE, WIDTH, HEIGHT, ts(index)))
            if (index < 4) recorder.onResult(ts(index), index.toLong(), mapOf("iso" to "100"))
            // Keep the ring from filling: the burst must come after the frames are on disk.
            while (recorder.liveStats().framesWritten < index + 1 && System.nanoTime() < deadline) Thread.sleep(2)
        }
        assertEquals(frames.toLong(), recorder.liveStats().framesWritten.toLong())
        for (index in 4 until frames) recorder.onResult(ts(index), index.toLong(), mapOf("iso" to "200"))

        val result = recorder.stop()
        assertEquals(frames.toLong(), result.frameMetadataWritten.toLong())
        assertEquals(0, result.unmatchedResults)
        val scan = scan(output.file)
        assertEquals("200", scan.frameMetadata.getValue(scan.frames[4].sequence)["iso"])
    }

    @Test
    fun ringHighWaterEndsTheTakeWithoutDroppingAFrame() {
        val gate = CountDownLatch(1)
        val recorder = recorder()
        recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 4)
        val ended = CopyOnWriteArrayList<String>()
        val output = FileOutput(wrap = { GatedChannel(it, gate) })
        recorder.start(output, info()) { ended += it }

        // The writer is stuck on its first write: slots 1-3 fill, the 4th reaches 90 % and ends the take.
        assertTrue(recorder.onFrame(plane(0), STRIDE, WIDTH, HEIGHT, ts(0)))
        assertTrue(recorder.onFrame(plane(1), STRIDE, WIDTH, HEIGHT, ts(1)))
        assertTrue(recorder.onFrame(plane(2), STRIDE, WIDTH, HEIGHT, ts(2)))
        assertFalse(recorder.onFrame(plane(3), STRIDE, WIDTH, HEIGHT, ts(3)))
        assertFalse(recorder.onFrame(plane(4), STRIDE, WIDTH, HEIGHT, ts(4)))
        assertEquals(listOf(RawTakeReasons.RING_FULL), ended)
        assertEquals(1.0, recorder.liveStats().ringUsedFraction, 0.0)

        gate.countDown()
        val result = recorder.stop()
        assertEquals(RawTakeReasons.RING_FULL, result.reason)
        assertEquals(4, result.framesCaptured)
        assertEquals(4, result.framesWritten)
        assertEquals(0, result.droppedFrames)
        assertEquals(1.0, result.peakRingFraction, 0.0)
        assertTrue(output.published)
        val scan = scan(output.file)
        assertTrue(scan.cleanlyClosed)
        assertEquals(4, scan.frameCount)
    }

    @Test
    fun writerErrorEndsTheTakeAndKeepsTheFramesOnDisk() {
        val recorder = recorder(finalWaitMs = 0)
        recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 8)
        val ended = CopyOnWriteArrayList<String>()
        // Header + metadata + about two frames fit, then every streamed write fails like a full volume.
        val budget = 32L + 600 + 2 * (44 + 64 + CONFIG.bytesPerFrame) + 50
        val output = FileOutput(wrap = { FailingChannel(it, budget) })
        recorder.start(output, info()) { ended += it }
        recorder.onTakeStarted("0", RawVideoSessionConfig(WIDTH, HEIGHT, 30), emptyMap())

        var accepted = 0
        for (index in 0 until 200) {
            if (!recorder.onFrame(plane(index % 7), STRIDE, WIDTH, HEIGHT, ts(index))) break
            accepted++
            Thread.sleep(5)
        }
        assertTrue("the producer must learn about the failure", accepted < 200)
        assertEquals(listOf(RawTakeReasons.WRITER_ERROR), ended)

        val result = recorder.stop()
        assertEquals(RawTakeReasons.WRITER_ERROR, result.reason)
        assertTrue(result.recovered)
        assertTrue(output.published)
        assertTrue(result.framesWritten >= 1)
        val scan = scan(output.file)
        assertTrue(scan.errors.toString(), scan.cleanlyClosed)
        assertEquals(result.framesWritten, scan.frameCount.toLong())
    }

    @Test
    fun streamFailureEndsTheTakeAndFinalisesWhatWasCaptured() {
        val recorder = recorder()
        recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 8)
        val output = FileOutput()
        recorder.start(output, info())
        assertTrue(recorder.onFrame(plane(0), STRIDE, WIDTH, HEIGHT, ts(0)))
        assertTrue(recorder.onFrame(plane(1), STRIDE, WIDTH, HEIGHT, ts(1)))
        recorder.onRawStreamFailed("camera-disconnected")
        assertFalse(recorder.onFrame(plane(2), STRIDE, WIDTH, HEIGHT, ts(2)))

        val result = recorder.stop()
        assertEquals("raw-stream-failed:camera-disconnected", result.reason)
        assertEquals(2, result.framesWritten)
        assertFalse(result.recovered)
        val scan = scan(output.file)
        assertTrue(scan.cleanlyClosed)
        assertEquals(2, scan.frameCount)
        // Without onRawTakeStarted the metadata still has the configuration's keys.
        assertEquals("0", scan.metadata["cameraId"])
        assertEquals("16 4 32 8", scan.metadata["crop"])
        // No take start, no orientation: readers take an absent key as 0.
        assertNull(scan.metadata[RawVideoMetadata.KEY_ORIENTATION])
    }

    @Test
    fun orientationValuesAreQuarterTurns() {
        assertEquals("0", RawVideoMetadata.orientationValue(0))
        assertEquals("90", RawVideoMetadata.orientationValue(90))
        assertEquals("180", RawVideoMetadata.orientationValue(180))
        assertEquals("270", RawVideoMetadata.orientationValue(270))
        assertEquals("0", RawVideoMetadata.orientationValue(360))
        assertEquals("270", RawVideoMetadata.orientationValue(-90))
    }

    @Test
    fun aTakeWithoutFramesIsDiscarded() {
        val recorder = recorder()
        recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 8)
        val output = FileOutput()
        recorder.start(output, info())
        val result = recorder.stop()
        assertNull(result.uri)
        assertTrue(output.discarded)
        assertFalse(output.file.exists())
        assertEquals(0, result.framesWritten)
    }

    @Test
    fun aFrameThatDoesNotFitTheCropEndsTheTake() {
        val recorder = recorder()
        recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 8)
        val output = FileOutput()
        recorder.start(output, info())
        assertTrue(recorder.onFrame(plane(0), STRIDE, WIDTH, HEIGHT, ts(0)))
        val small = ByteBuffer.allocateDirect(20 * 4)
        assertFalse(recorder.onFrame(small, 20, 16, 4, ts(1)))
        val result = recorder.stop()
        assertEquals(RawTakeReasons.FRAME_INVALID, result.reason)
        assertEquals(1, result.framesWritten)
    }

    @Test
    fun framesOutsideATakeAreRefusedAndTheRingIsReusedAcrossTakes() {
        val slots = HeapSlots()
        val recorder = recorder(slots)
        assertFalse(recorder.onFrame(plane(0), STRIDE, WIDTH, HEIGHT, ts(0)))
        assertThrows(IllegalStateException::class.java) { recorder.start(FileOutput(), info()) }
        // More slots than a take's frames, so no frame waits on the writer: the point is that both takes share one ring.
        recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 12)
        repeat(2) { take ->
            val output = FileOutput("take$take.ocraw")
            recorder.start(output, info())
            for (index in 0 until 10) assertTrue(recorder.onFrame(plane(index), STRIDE, WIDTH, HEIGHT, ts(index)))
            assertEquals(10, recorder.stop().framesWritten)
            assertEquals(10, scan(output.file).frameCount)
        }
        assertEquals(12, slots.allocated)
        assertThrows(IllegalStateException::class.java) { recorder.stop() }
        recorder.disarm()
        assertTrue(slots.closed)
        assertNull(recorder.armState)
    }

    @Test
    fun armKeepsAPartialRingAndRefusesOneTooSmall() {
        val partial = HeapSlots(failAfter = 5)
        assertEquals(5, recorder(partial).arm(CONFIG, CONFIG.bytesPerFrame * 10).slotCount)
        assertFalse(partial.closed)

        val tooSmall = HeapSlots(failAfter = 2)
        assertThrows(OutOfMemoryError::class.java) { recorder(tooSmall).arm(CONFIG, CONFIG.bytesPerFrame * 10) }
        assertTrue(tooSmall.closed)

        assertThrows(IllegalArgumentException::class.java) { recorder().arm(CONFIG, CONFIG.bytesPerFrame * 3) }
    }

    /**
     * Stands in for the native LJ92 encoder: "encodes" a frame as its first half XOR 0x5a, and returns
     * 0 (does not fit) for the calls listed in [doesNotFit].
     */
    private class FakeEncoder(
        private val doesNotFit: Set<Int> = emptySet(),
        override val encoding: Int = OcrawFrameHeader.ENCODING_LJ92,
    ) : RawFrameEncoder {
        var calls = 0
        var closed = false
        override val metadata = if (encoding == OcrawFrameHeader.ENCODING_LJ92) {
            mapOf(RawFrameEncoders.KEY_LJ92_PREDICTOR to "1", RawFrameEncoders.KEY_LJ92_STRIPS to "8")
        } else {
            mapOf(RawFrameEncoders.KEY_BLOCK_PACK_STRIPS to "8")
        }
        override fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int {
            check(!closed)
            assertEquals(width / 4 * 5 * height, src.remaining())
            if (calls++ in doesNotFit) return 0
            val size = src.remaining() / 2
            for (i in 0 until size) dst.put(dst.position() + i, (src.get(src.position() + i).toInt() xor 0x5a).toByte())
            return size
        }
        override fun close() { closed = true }
    }

    @Test
    fun anLj92TakeEncodesOnTheWriterAndStoresAFrameThatDoesNotFitAsRaw10() {
        val encoder = FakeEncoder(doesNotFit = setOf(2))
        val slots = HeapSlots()
        val recorder = RawVideoRecorder(slotMemoryFactory = { slots }, finalResultWaitMs = 0, writerJoinTimeoutMs = 10_000,
            encoderFactory = { encoder })
        val armed = recorder.arm(LJ92, ringBytes = LJ92.bytesPerFrame * 10)
        assertTrue(armed.compressed)
        assertEquals(10, armed.slotCount)
        assertEquals("the output buffer comes from the slot memory", 11, slots.allocated)
        val output = FileOutput()
        recorder.start(output, info())
        for (index in 0 until 6) assertTrue(recorder.onFrame(plane(index), STRIDE, WIDTH, HEIGHT, ts(index)))
        val result = recorder.stop()
        assertNull(result.reason)
        assertEquals(6, result.framesWritten)
        assertEquals(RawVideoConfig.ENCODING_LJ92, result.encoding)
        assertEquals(5, result.framesEncoded)
        assertEquals(1, result.framesStoredUncompressed)
        val raw = LJ92.bytesPerFrame
        // Five frames at half size plus one stored whole.
        assertEquals(6.0 * raw / (5 * raw / 2 + raw), requireNotNull(result.compressionRatio), 1e-9)
        assertNotNull(result.encodeMeanMs)
        assertEquals(6, encoder.calls)

        val scan = scan(output.file)
        assertTrue(scan.errors.toString(), scan.cleanlyClosed)
        assertEquals(6, scan.frameCount)
        assertEquals(RawVideoConfig.ENCODING_LJ92, scan.metadata[RawFrameEncoders.KEY_ENCODING])
        assertEquals("1", scan.metadata[RawFrameEncoders.KEY_LJ92_PREDICTOR])
        assertEquals("8", scan.metadata[RawFrameEncoders.KEY_LJ92_STRIPS])
        scan.frames.forEachIndexed { index, frame ->
            val header = requireNotNull(frame.header)
            // Order is preserved: the take-local frame number and the timestamps rise with the index.
            assertEquals(index.toLong(), header.frameNumber)
            assertEquals(ts(index), header.sensorTimestampNs)
            assertEquals(32 / 4 * 5, header.rowBytes)
            val expected = expectedCrop(index, armed.crop)
            val (_, data) = frameData(output.file, frame.fileOffset, header.dataLength)
            if (index == 2) {
                assertEquals(OcrawFrameHeader.ENCODING_RAW10, header.encoding)
                assertArrayEquals(expected, data)
            } else {
                assertEquals(OcrawFrameHeader.ENCODING_LJ92, header.encoding)
                assertEquals(expected.size / 2, header.dataLength)
                assertArrayEquals(ByteArray(expected.size / 2) { (expected[it].toInt() xor 0x5a).toByte() }, data)
            }
        }

        // The encoder lives as long as the ring, across takes, and its numbers restart per take.
        val second = FileOutput("take2.ocraw")
        recorder.start(second, info())
        assertTrue(recorder.onFrame(plane(9), STRIDE, WIDTH, HEIGHT, ts(0)))
        val again = recorder.stop()
        assertEquals(1, again.framesEncoded)
        assertEquals(0, again.framesStoredUncompressed)
        assertEquals(2.0, requireNotNull(again.compressionRatio), 1e-9)
        assertFalse(encoder.closed)
        recorder.disarm()
        assertTrue(encoder.closed)
        assertTrue(slots.closed)
    }

    @Test
    fun eachCompressedEncodingArmsItsOwnEncoder() {
        val asked = mutableListOf<String>()
        val recorder = RawVideoRecorder(slotMemoryFactory = { HeapSlots() }, finalResultWaitMs = 0, writerJoinTimeoutMs = 10_000,
            encoderFactory = { encoding ->
                asked += encoding
                FakeEncoder(encoding = if (encoding == RawVideoConfig.ENCODING_BLOCK_PACK) OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK else OcrawFrameHeader.ENCODING_LJ92)
            })
        assertTrue(recorder.arm(BLOCK_PACK, ringBytes = BLOCK_PACK.bytesPerFrame * 6).compressed)
        val output = FileOutput("bp.ocraw")
        recorder.start(output, info())
        for (index in 0 until 2) assertTrue(recorder.onFrame(plane(index), STRIDE, WIDTH, HEIGHT, ts(index)))
        val result = recorder.stop()
        assertEquals(RawVideoConfig.ENCODING_BLOCK_PACK, result.encoding)
        assertEquals(2, result.framesEncoded)
        val scan = scan(output.file)
        assertEquals(RawVideoConfig.ENCODING_BLOCK_PACK, scan.metadata[RawFrameEncoders.KEY_ENCODING])
        assertEquals("8", scan.metadata[RawFrameEncoders.KEY_BLOCK_PACK_STRIPS])
        assertNull(scan.metadata[RawFrameEncoders.KEY_LJ92_PREDICTOR])
        scan.frames.forEach { assertEquals(OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK, requireNotNull(it.header).encoding) }

        recorder.arm(LJ92, ringBytes = LJ92.bytesPerFrame * 6)
        assertEquals(listOf(RawVideoConfig.ENCODING_BLOCK_PACK, RawVideoConfig.ENCODING_LJ92), asked)
        // RAW10 never asks for an encoder.
        recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 6)
        assertEquals(2, asked.size)
        recorder.disarm()
    }

    @Test
    fun liveStatsCarryTheRunningRatioOnlyForACompressedTake() {
        val recorder = RawVideoRecorder(slotMemoryFactory = { HeapSlots() }, finalResultWaitMs = 0, writerJoinTimeoutMs = 10_000,
            encoderFactory = { FakeEncoder() })
        recorder.arm(LJ92, ringBytes = LJ92.bytesPerFrame * 6)
        recorder.start(FileOutput(), info())
        assertNull(recorder.liveStats().compressionRatio)
        for (index in 0 until 3) assertTrue(recorder.onFrame(plane(index), STRIDE, WIDTH, HEIGHT, ts(index)))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (recorder.liveStats().framesWritten < 3 && System.nanoTime() < deadline) Thread.sleep(5)
        val live = recorder.liveStats()
        assertEquals(3, live.framesWritten)
        assertEquals(2.0, requireNotNull(live.compressionRatio), 1e-9)
        assertNotNull(live.encodeMeanMs)
        recorder.stop()

        val plain = recorder()
        plain.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 6)
        plain.start(FileOutput("plain.ocraw"), info())
        assertTrue(plain.onFrame(plane(0), STRIDE, WIDTH, HEIGHT, ts(0)))
        assertNull(plain.liveStats().compressionRatio)
        assertNull(plain.liveStats().encodeMeanMs)
        val result = plain.stop()
        assertEquals(RawVideoConfig.ENCODING_RAW10, result.encoding)
        assertNull(result.compressionRatio)
        val scan = scan(File(dir, "plain.ocraw"))
        assertEquals(RawVideoConfig.ENCODING_RAW10, scan.metadata[RawFrameEncoders.KEY_ENCODING])
        assertNull(scan.metadata[RawFrameEncoders.KEY_LJ92_PREDICTOR])
    }

    @Test
    fun aCompressedConfigIsNotArmedWithoutTheCodec() {
        val slots = HeapSlots()
        val recorder = RawVideoRecorder(slotMemoryFactory = { slots }, encoderFactory = { null })
        val refused = assertThrows(RawVideoArmException::class.java) { recorder.arm(LJ92, ringBytes = LJ92.bytesPerFrame * 6) }
        assertEquals(RawTakeReasons.CODEC_UNAVAILABLE, refused.reason)
        assertEquals(RawTakeReasons.CODEC_UNAVAILABLE,
            assertThrows(RawVideoArmException::class.java) { recorder.arm(BLOCK_PACK, ringBytes = BLOCK_PACK.bytesPerFrame * 6) }.reason)
        assertEquals("raw-codec-unavailable", RawTakeReasons.CODEC_UNAVAILABLE)
        assertNull(recorder.armState)
        assertEquals(0, slots.allocated)
        // The uncompressed configuration needs no codec.
        assertFalse(recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 6).compressed)
        // An unknown encoding is a programming error.
        assertThrows(IllegalArgumentException::class.java) { recorder.arm(CONFIG.copy(encoding = "deflate"), CONFIG.bytesPerFrame * 6) }
    }

    /** Stands in for the native quantised block-pack codec: the frame's first half XOR 0xa5, after tables are set. */
    private class FakeQuantising : QuantisingFrameCodec {
        val isos = mutableListOf<Int>()
        var tables: com.librestatic.opencinecam.media.raw.RawQuantisation.Tables? = null
        var calls = 0
        var closed = false
        override fun setQuantisation(tables: com.librestatic.opencinecam.media.raw.RawQuantisation.Tables, iso: Int) {
            this.tables = tables
            isos += iso
        }
        override fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int {
            check(!closed)
            checkNotNull(tables) { "setQuantisation first" }
            calls++
            val size = src.remaining() / 2
            for (i in 0 until size) dst.put(dst.position() + i, (src.get(src.position() + i).toInt() xor 0xa5).toByte())
            return size
        }
        override fun close() { closed = true }
    }

    private fun profile() = RawNoiseProfile("testdevice", "0", WIDTH, HEIGHT, 64,
        listOf(RawNoisePoint(100, 0.4, 0.04), RawNoisePoint(1600, 0.0, 0.43)))

    private fun awaitWritten(recorder: RawVideoRecorder, frames: Long) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (recorder.liveStats().framesWritten < frames && System.nanoTime() < deadline) Thread.sleep(2)
        assertEquals(frames, recorder.liveStats().framesWritten)
    }

    @Test
    fun nearLosslessEncoderBuildsTablesOnlyOnAnIsoChangeAndIsLosslessUntilTheFirstIso() {
        val lossless = FakeEncoder(encoding = OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK)
        val quantised = FakeQuantising()
        val encoder = NearLosslessFrameEncoder(lossless, quantised, profile())
        val src = ByteBuffer.allocateDirect(ROW_BYTES * HEIGHT)
        val dst = ByteBuffer.allocateDirect(ROW_BYTES * HEIGHT)
        encoder.encode(src, WIDTH, HEIGHT, dst)
        assertEquals(OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK, encoder.encoding)
        assertNull(encoder.tablesIso)
        encoder.setIso(400)
        encoder.setIso(400)
        encoder.encode(src, WIDTH, HEIGHT, dst)
        assertEquals(OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED, encoder.encoding)
        encoder.setIso(800)
        encoder.setIso(800)
        encoder.setIso(0) // not a sensitivity: ignored
        encoder.encode(src, WIDTH, HEIGHT, dst)
        assertEquals(listOf(400, 800), quantised.isos)
        assertEquals(2, encoder.tableBuilds)
        assertEquals(800, encoder.tablesIso)
        assertEquals(1, lossless.calls)
        assertEquals(2, quantised.calls)
        // The tables are the profile's model at that ISO, over its black level.
        val expected = com.librestatic.opencinecam.media.raw.RawQuantisation.build(profile().modelAt(800), 64)
        assertArrayEquals(expected.forward, requireNotNull(quantised.tables).forward)
        assertEquals(64, requireNotNull(quantised.tables).blackLevel)
        assertEquals(RawFrameEncoders.ENCODING_NEAR_LOSSLESS, encoder.metadata[RawFrameEncoders.KEY_ENCODING])
        assertEquals("0.5", encoder.metadata[RawFrameEncoders.KEY_QUANT_K])
        assertEquals("testdevice/0", encoder.metadata[RawFrameEncoders.KEY_QUANT_NOISE_PROFILE])
        assertEquals("8", encoder.metadata[RawFrameEncoders.KEY_BLOCK_PACK_STRIPS])
        // A new take on the same encoder is lossless until it gives its own ISO, even the same one.
        RawFrameCompressor(encoder, dst).reset()
        assertNull(encoder.tablesIso)
        encoder.encode(src, WIDTH, HEIGHT, dst)
        assertEquals(OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK, encoder.encoding)
        assertEquals(2, lossless.calls)
        encoder.setIso(800)
        encoder.encode(src, WIDTH, HEIGHT, dst)
        assertEquals(OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED, encoder.encoding)
        assertEquals(listOf(400, 800, 800), quantised.isos)
        encoder.close()
        assertTrue(lossless.closed)
        assertTrue(quantised.closed)
    }

    @Test
    fun aBlockPackTakeWithANoiseProfileRecordsNearLosslessFromTheFirstKnownIso() {
        val quantised = FakeQuantising()
        val lossless = FakeEncoder(encoding = OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK)
        val profiles = mutableListOf<RawNoiseProfile>()
        val asked = mutableListOf<String>()
        val recorder = RawVideoRecorder(slotMemoryFactory = { HeapSlots() }, finalResultWaitMs = 0, writerJoinTimeoutMs = 10_000,
            encoderFactory = { asked += it; FakeEncoder(encoding = OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK) },
            nearLosslessEncoderFactory = { profiles += it; NearLosslessFrameEncoder(lossless, quantised, it) })
        val armed = recorder.arm(BLOCK_PACK, ringBytes = BLOCK_PACK.bytesPerFrame * 8, noiseProfile = profile())
        assertTrue(armed.compressed)
        assertTrue(armed.nearLossless)
        assertEquals(listOf(profile()), profiles)
        assertEquals(emptyList<String>(), asked)
        val output = FileOutput("bpq.ocraw")
        recorder.start(output, info())
        // Frame 0: no result yet, so lossless block-pack.
        assertTrue(recorder.onFrame(plane(0), STRIDE, WIDTH, HEIGHT, ts(0)))
        awaitWritten(recorder, 1)
        // Frame 0's result (ISO 800) arrives after it was written: frame 1 uses it before its own result comes.
        recorder.onResult(ts(0), 100, mapOf("iso" to "800"))
        assertTrue(recorder.onFrame(plane(1), STRIDE, WIDTH, HEIGHT, ts(1)))
        awaitWritten(recorder, 2)
        recorder.onResult(ts(1), 101, mapOf("iso" to "800"))
        // Frame 2's own result arrives first: its ISO wins.
        recorder.onResult(ts(2), 102, mapOf("iso" to "1600"))
        assertTrue(recorder.onFrame(plane(2), STRIDE, WIDTH, HEIGHT, ts(2)))
        awaitWritten(recorder, 3)
        // A result without an ISO keeps the last one.
        recorder.onResult(ts(3), 103, emptyMap())
        assertTrue(recorder.onFrame(plane(3), STRIDE, WIDTH, HEIGHT, ts(3)))
        val result = recorder.stop()
        assertNull(result.reason)
        assertTrue(result.nearLossless)
        assertEquals(RawVideoConfig.ENCODING_BLOCK_PACK, result.encoding)
        assertEquals(4, result.framesEncoded)
        assertEquals("tables are built only when the ISO changes", listOf(800, 1600), quantised.isos)

        val scan = scan(output.file)
        assertTrue(scan.errors.toString(), scan.cleanlyClosed)
        assertEquals(RawFrameEncoders.ENCODING_NEAR_LOSSLESS, scan.metadata[RawFrameEncoders.KEY_ENCODING])
        assertEquals("0.5", scan.metadata[RawFrameEncoders.KEY_QUANT_K])
        assertEquals("testdevice/0", scan.metadata[RawFrameEncoders.KEY_QUANT_NOISE_PROFILE])
        assertEquals("8", scan.metadata[RawFrameEncoders.KEY_BLOCK_PACK_STRIPS])
        val encodings = scan.frames.map { requireNotNull(it.header).encoding }
        assertEquals(listOf(OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK) + List(3) { OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED }, encodings)
        scan.frames.forEachIndexed { index, frame ->
            val header = requireNotNull(frame.header)
            val expected = expectedCrop(index, armed.crop)
            val key = if (index == 0) 0x5a else 0xa5
            assertArrayEquals(ByteArray(expected.size / 2) { (expected[it].toInt() xor key).toByte() },
                frameData(output.file, frame.fileOffset, header.dataLength).second)
        }
        recorder.disarm()
        assertTrue(lossless.closed)
        assertTrue(quantised.closed)
    }

    @Test
    fun withoutAProfileOrForAnotherEncodingTheTakeStaysLossless() {
        val asked = mutableListOf<String>()
        var nearAsked = 0
        val recorder = RawVideoRecorder(slotMemoryFactory = { HeapSlots() }, finalResultWaitMs = 0, writerJoinTimeoutMs = 10_000,
            encoderFactory = { encoding ->
                asked += encoding
                FakeEncoder(encoding = if (encoding == RawVideoConfig.ENCODING_BLOCK_PACK) OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK else OcrawFrameHeader.ENCODING_LJ92)
            },
            nearLosslessEncoderFactory = { nearAsked++; null })
        // Near-lossless off (no profile passed): lossless block-pack, encoding `bp`.
        assertFalse(recorder.arm(BLOCK_PACK, ringBytes = BLOCK_PACK.bytesPerFrame * 6).nearLossless)
        val output = FileOutput("bp.ocraw")
        recorder.start(output, info())
        recorder.onResult(ts(0), 1, mapOf("iso" to "800"))
        assertTrue(recorder.onFrame(plane(0), STRIDE, WIDTH, HEIGHT, ts(0)))
        val result = recorder.stop()
        assertFalse(result.nearLossless)
        val scan = scan(output.file)
        assertEquals(RawVideoConfig.ENCODING_BLOCK_PACK, scan.metadata[RawFrameEncoders.KEY_ENCODING])
        assertNull(scan.metadata[RawFrameEncoders.KEY_QUANT_K])
        assertEquals(OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK, requireNotNull(scan.frames.single().header).encoding)
        // A profile only applies to block-pack: LJ92 and RAW10 ignore it.
        assertFalse(recorder.arm(LJ92, ringBytes = LJ92.bytesPerFrame * 6, noiseProfile = profile()).nearLossless)
        assertFalse(recorder.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 6, noiseProfile = profile()).nearLossless)
        assertEquals(listOf(RawVideoConfig.ENCODING_BLOCK_PACK, RawVideoConfig.ENCODING_LJ92), asked)
        assertEquals(0, nearAsked)
        // Without the codec a near-lossless arm is refused like any compressed one.
        assertEquals(RawTakeReasons.CODEC_UNAVAILABLE, assertThrows(RawVideoArmException::class.java) {
            recorder.arm(BLOCK_PACK, ringBytes = BLOCK_PACK.bytesPerFrame * 6, noiseProfile = profile())
        }.reason)
        assertEquals(1, nearAsked)
        assertNull(recorder.armState)
    }

    @Test
    fun anEncoderFailureEndsTheTakeAsAWriterError() {
        val failing = object : RawFrameEncoder {
            override val encoding = OcrawFrameHeader.ENCODING_LJ92
            override val metadata = emptyMap<String, String>()
            override fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int = throw IllegalStateException("native")
            override fun close() = Unit
        }
        val recorder = RawVideoRecorder(slotMemoryFactory = { HeapSlots() }, finalResultWaitMs = 0, writerJoinTimeoutMs = 10_000,
            encoderFactory = { failing })
        recorder.arm(LJ92, ringBytes = LJ92.bytesPerFrame * 6)
        val ended = CopyOnWriteArrayList<String>()
        recorder.start(FileOutput(), info()) { ended += it }
        recorder.onFrame(plane(0), STRIDE, WIDTH, HEIGHT, ts(0))
        val result = recorder.stop()
        assertEquals(RawTakeReasons.WRITER_ERROR, result.reason)
        assertEquals(listOf(RawTakeReasons.WRITER_ERROR), ended)
        assertEquals(0, result.framesWritten)
    }

    /** Blocks every write until [gate] opens. */
    private class GatedChannel(delegate: FileChannel, private val gate: CountDownLatch) : DelegatingChannel(delegate) {
        override fun beforeWrite(bytes: Long) {
            check(gate.await(10, TimeUnit.SECONDS)) { "gate never opened" }
        }
    }

    /** Fails streamed writes once [budget] bytes went through; positional writes (recovery) still work. */
    private class FailingChannel(delegate: FileChannel, private var budget: Long) : DelegatingChannel(delegate) {
        override fun beforeWrite(bytes: Long) {
            if (bytes > budget) throw IOException("No space left on device")
            budget -= bytes
        }
    }

    private abstract class DelegatingChannel(private val delegate: FileChannel) : FileChannel() {
        abstract fun beforeWrite(bytes: Long)
        override fun read(dst: ByteBuffer) = delegate.read(dst)
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int) = delegate.read(dsts, offset, length)
        override fun read(dst: ByteBuffer, position: Long) = delegate.read(dst, position)
        override fun write(src: ByteBuffer): Int {
            beforeWrite(src.remaining().toLong())
            return delegate.write(src)
        }
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
            beforeWrite((offset until offset + length).sumOf { srcs[it].remaining().toLong() })
            return delegate.write(srcs, offset, length)
        }
        override fun write(src: ByteBuffer, position: Long) = delegate.write(src, position)
        override fun position() = delegate.position()
        override fun position(newPosition: Long): FileChannel = apply { delegate.position(newPosition) }
        override fun size() = delegate.size()
        override fun truncate(size: Long): FileChannel = apply { delegate.truncate(size) }
        override fun force(metaData: Boolean) = delegate.force(metaData)
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel) = delegate.transferTo(position, count, target)
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long) = delegate.transferFrom(src, position, count)
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = throw UnsupportedOperationException()
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
        override fun implCloseChannel() = delegate.close()
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 16
        const val ROW_BYTES = WIDTH / 4 * 5
        const val STRIDE = ROW_BYTES + 16
        val CONFIG = RawVideoConfig("0", WIDTH, HEIGHT, 32, 8, 30)
        val LJ92 = CONFIG.copy(encoding = RawVideoConfig.ENCODING_LJ92)
        val BLOCK_PACK = CONFIG.copy(encoding = RawVideoConfig.ENCODING_BLOCK_PACK)

        fun ts(index: Int): Long = 1_000_000_000L + index * 33_333_333L
    }
}
