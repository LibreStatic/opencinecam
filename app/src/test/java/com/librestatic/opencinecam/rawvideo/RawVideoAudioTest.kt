/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoSessionConfig
import com.librestatic.opencinecam.media.ocraw.OcrawChunk
import com.librestatic.opencinecam.media.ocraw.OcrawChunkId
import com.librestatic.opencinecam.media.ocraw.OcrawCodec
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PLAN-071 §7.1: the PCM16 chunks a RAW take writes beside its frames. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RawVideoAudioTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("raw-audio").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private inner class FileOutput : RawVideoTakeOutput {
        val file = File(dir, "take.ocraw")
        private val raf = RandomAccessFile(file, "rw")
        override val displayName: String = file.name
        override val channel: FileChannel = raf.channel
        var discarded = false
        override fun publish(): Uri {
            channel.close(); raf.close()
            return Uri.fromFile(file)
        }
        override fun discard() {
            channel.close(); raf.close(); file.delete(); discarded = true
        }
    }

    private class HeapSlots : RawSlotMemory {
        override fun allocate(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes)
        override fun close() = Unit
    }

    /** A test-driven [RawAudioSource]: the test offers chunks through [sink]; [stop] can offer a tail. */
    private class FakeAudio(
        private val tail: Pair<Long, ByteArray>? = null,
        private val startFailure: Throwable? = null,
        override val failure: String? = null,
    ) : RawAudioSource {
        var sink: RawAudioSink? = null
        var stops = 0
        val events = mutableListOf<String>()

        override fun start(sink: RawAudioSink): Map<String, String> {
            events += "start"
            startFailure?.let { throw it }
            this.sink = sink
            return mapOf(RawAudioKeys.SAMPLE_RATE to "48000", RawAudioKeys.CHANNELS to "2", RawAudioKeys.SOURCE to "UNPROCESSED",
                RawAudioKeys.CLOCK to RawAudioKeys.CLOCK_BOOTTIME)
        }

        override fun stop() {
            events += "stop"
            stops++
            tail?.let { (ns, payload) -> sink?.offer(ns, payload, payload.size / FRAME_BYTES) }
        }

        override fun finalMetadata(): Map<String, String> = mapOf(RawAudioKeys.TIMESTAMP_BACKED to "true")

        fun offer(ns: Long, payload: ByteArray): Boolean = requireNotNull(sink).offer(ns, payload, payload.size / FRAME_BYTES)
    }

    private fun recorder(queue: Int = RawVideoRecorder.AUDIO_QUEUE_CHUNKS) = RawVideoRecorder(
        slotMemoryFactory = { HeapSlots() }, finalResultWaitMs = 0, writerJoinTimeoutMs = 10_000, audioQueueChunks = queue,
    ).also { it.arm(CONFIG, ringBytes = CONFIG.bytesPerFrame * 8) }

    private fun info() = RawTakeInfo(UUID.randomUUID().toString(), "TestPhone", "9.9.9")

    private fun plane(): ByteBuffer = ByteBuffer.allocateDirect(ROW_BYTES * HEIGHT)

    /** [frames] stereo PCM16 frames whose bytes all equal [tag]. */
    private fun pcm(tag: Int, frames: Int = CHUNK_FRAMES) = ByteArray(frames * FRAME_BYTES) { tag.toByte() }

    private fun chunks(file: File): List<OcrawChunk> = OcrawCodec.scanContainer(file.readBytes()).chunks

    private fun frame(recorder: RawVideoRecorder, index: Int) =
        assertTrue(recorder.onFrame(plane(), ROW_BYTES, WIDTH, HEIGHT, ts(index)))

    private fun awaitWritten(recorder: RawVideoRecorder, frames: Long) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (recorder.liveStats().framesWritten < frames && System.nanoTime() < deadline) Thread.sleep(2)
    }

    @Test
    fun audioChunksInterleaveWithFramesAfterTheMetadataInTheFrameTimeBase() {
        val recorder = recorder()
        val output = FileOutput()
        val audio = FakeAudio(tail = audioNs(3) to pcm(9, frames = 100))
        recorder.start(output, info(), audio)
        recorder.onTakeStarted("0", RawVideoSessionConfig(WIDTH, HEIGHT, 30), mapOf("cfa" to "RGGB"))
        assertEquals(48_000, recorder.liveAudioSampleRate())
        assertEquals(2, recorder.liveAudioChannels())

        // A chunk captured before the first frame waits for the metadata, then the two streams interleave.
        assertTrue(audio.offer(audioNs(0), pcm(1)))
        frame(recorder, 0)
        awaitWritten(recorder, 1)
        assertTrue(audio.offer(audioNs(1), pcm(2)))
        frame(recorder, 1)
        frame(recorder, 2)
        awaitWritten(recorder, 3)
        assertTrue(audio.offer(audioNs(2), pcm(3)))
        frame(recorder, 3)

        val result = recorder.stop()
        assertEquals(listOf("start", "stop"), audio.events)
        assertNull(result.reason)
        assertEquals(48_000, result.audioSampleRate)
        assertEquals(2, result.audioChannels)
        assertEquals(3L * CHUNK_FRAMES + 100, result.audioFramesWritten)
        assertEquals(0, result.audioDroppedFrames)
        assertNull(result.audioError)

        val all = chunks(output.file)
        val metadataAt = all.indexOfFirst { it.wireId == OcrawChunkId.METADATA.wireId }
        val audioChunks = all.filter { it.wireId == OcrawChunkId.AUDIO_PCM16.wireId }
        val firstAudioAt = all.indexOfFirst { it.wireId == OcrawChunkId.AUDIO_PCM16.wireId }
        val lastFrameAt = all.indexOfLast { it.wireId == OcrawChunkId.RAW_FRAME.wireId }
        assertTrue("metadata precedes audio", metadataAt in 0 until firstAudioAt)
        assertTrue("audio interleaves with frames", firstAudioAt < lastFrameAt)
        assertEquals(listOf(audioNs(0), audioNs(1), audioNs(2), audioNs(3)), audioChunks.map { it.timestampTicks })
        assertArrayEquals(pcm(1), audioChunks[0].payload)
        assertArrayEquals(pcm(9, frames = 100), audioChunks[3].payload)
        // Same time base as the frames: a frame's ticks are its sensor timestamp in ns.
        val frameChunks = all.filter { it.wireId == OcrawChunkId.RAW_FRAME.wireId }
        assertEquals(ts(0), frameChunks.first().timestampTicks)
        assertEquals(frameChunks.first().timeBase, audioChunks.first().timeBase)

        RandomAccessFile(output.file, "r").use { raf ->
            val reader = OcrawStreamReader(raf.channel)
            val scan = reader.scan()
            assertTrue(scan.errors.toString(), scan.cleanlyClosed)
            assertEquals(4, scan.frameCount)
            assertEquals("48000", scan.metadata[RawAudioKeys.SAMPLE_RATE])
            assertEquals("2", scan.metadata[RawAudioKeys.CHANNELS])
            assertEquals("UNPROCESSED", scan.metadata[RawAudioKeys.SOURCE])
            assertEquals(RawAudioKeys.CLOCK_BOOTTIME, scan.metadata[RawAudioKeys.CLOCK])
            assertEquals("0", scan.metadata[RawAudioKeys.DROPPED_SAMPLES])
            assertEquals("true", scan.metadata[RawAudioKeys.TIMESTAMP_BACKED])
            assertEquals("RGGB", scan.metadata["cfa"])
            // The playback index skips the audio chunks and still finds every frame and the merged metadata.
            val index = reader.indexFrames()
            assertFalse(index.tornTail)
            assertEquals(4, index.frames.size)
            assertEquals((0 until 4).map(::ts), index.frames.map { it.timestampTicks })
            assertEquals("48000", index.metadata[RawAudioKeys.SAMPLE_RATE])
            assertEquals("0", index.metadata[RawAudioKeys.DROPPED_SAMPLES])
        }
        // The static keys are already in the first METADATA chunk, before any audio.
        val firstMetadata = com.librestatic.opencinecam.media.ocraw.OcrawMetadataCbor.decode(all[metadataAt].payload)
        assertEquals("48000", firstMetadata[RawAudioKeys.SAMPLE_RATE])
        assertNull(firstMetadata[RawAudioKeys.DROPPED_SAMPLES])
    }

    @Test
    fun aFullAudioQueueDropsChunksAndCountsTheirSamplesWithoutEndingTheTake() {
        val recorder = recorder(queue = 2)
        val output = FileOutput()
        val audio = FakeAudio()
        val ended = mutableListOf<String>()
        recorder.start(output, info(), audio) { ended += it }
        // Before the first frame nothing drains the queue: the third and fourth chunks do not fit.
        assertTrue(audio.offer(audioNs(0), pcm(1)))
        assertTrue(audio.offer(audioNs(1), pcm(2)))
        assertFalse(audio.offer(audioNs(2), pcm(3)))
        assertFalse(audio.offer(audioNs(3), pcm(4, frames = 10)))
        frame(recorder, 0)
        frame(recorder, 1)

        val result = recorder.stop()
        assertTrue(ended.isEmpty())
        assertNull(result.reason)
        assertEquals(2, result.framesWritten)
        assertEquals(2L * CHUNK_FRAMES, result.audioFramesWritten)
        assertEquals(CHUNK_FRAMES + 10L, result.audioDroppedFrames)

        val all = chunks(output.file)
        assertEquals(listOf(audioNs(0), audioNs(1)), all.filter { it.wireId == OcrawChunkId.AUDIO_PCM16.wireId }.map { it.timestampTicks })
        val scan = RandomAccessFile(output.file, "r").use { OcrawStreamReader(it.channel).scan() }
        assertTrue(scan.cleanlyClosed)
        assertEquals("${CHUNK_FRAMES + 10}", scan.metadata[RawAudioKeys.DROPPED_SAMPLES])
    }

    @Test
    fun offersAfterStopAreRefusedAndNotCounted() {
        val recorder = recorder()
        val output = FileOutput()
        val audio = FakeAudio()
        recorder.start(output, info(), audio)
        frame(recorder, 0)
        recorder.stop()
        assertFalse(audio.offer(audioNs(5), pcm(1)))
        assertEquals(1, audio.stops)
    }

    @Test
    fun anAudioSourceThatDoesNotStartLeavesASilentTake() {
        val recorder = recorder()
        val output = FileOutput()
        val audio = FakeAudio(startFailure = IllegalStateException("AudioRecord initialization failed"))
        recorder.start(output, info(), audio)
        assertNull(recorder.liveAudioSampleRate())
        frame(recorder, 0)
        val result = recorder.stop()

        assertNull(result.reason)
        assertNotNull(result.uri)
        assertNull(result.audioSampleRate)
        assertTrue(result.audioError!!.startsWith("audio-start-failed"))
        val scan = RandomAccessFile(output.file, "r").use { OcrawStreamReader(it.channel).scan() }
        assertTrue(scan.cleanlyClosed)
        assertTrue(scan.metadata.keys.none { it.startsWith("audio") })
        assertTrue(chunks(output.file).none { it.wireId == OcrawChunkId.AUDIO_PCM16.wireId })
    }

    @Test
    fun anAudioFailureMidTakeIsRecordedAndTheTakeGoesOn() {
        val recorder = recorder()
        val output = FileOutput()
        val audio = FakeAudio(failure = "audio-read--6")
        recorder.start(output, info(), audio)
        assertTrue(audio.offer(audioNs(0), pcm(1)))
        frame(recorder, 0)
        val result = recorder.stop()
        assertNull(result.reason)
        assertEquals("audio-read--6", result.audioError)
        val scan = RandomAccessFile(output.file, "r").use { OcrawStreamReader(it.channel).scan() }
        assertEquals("audio-read--6", scan.metadata[RawAudioKeys.ERROR])
    }

    @Test
    fun aTakeWithoutAudioWritesNoAudioChunksOrKeys() {
        val recorder = recorder()
        val output = FileOutput()
        recorder.start(output, info())
        assertNull(recorder.liveAudioSampleRate())
        frame(recorder, 0)
        frame(recorder, 1)
        val result = recorder.stop()
        assertNull(result.audioSampleRate)
        assertNull(result.audioError)
        assertEquals(0, result.audioFramesWritten)
        val all = chunks(output.file)
        assertTrue(all.none { it.wireId == OcrawChunkId.AUDIO_PCM16.wireId })
        val scan = RandomAccessFile(output.file, "r").use { OcrawStreamReader(it.channel).scan() }
        assertTrue(scan.metadata.keys.none { it.startsWith("audio") })
        // A silent take has exactly one METADATA chunk, as before audio existed.
        assertEquals(1, all.count { it.wireId == OcrawChunkId.METADATA.wireId })
    }

    @Test
    fun aTakeWithoutFramesStopsItsAudioAndIsDiscarded() {
        val recorder = recorder()
        val output = FileOutput()
        val audio = FakeAudio()
        recorder.start(output, info(), audio)
        assertTrue(audio.offer(audioNs(0), pcm(1)))
        val result = recorder.stop()
        assertEquals(1, audio.stops)
        assertNull(result.uri)
        assertTrue(output.discarded)
        assertEquals(CHUNK_FRAMES.toLong(), result.audioDroppedFrames)
    }

    @Test
    fun ticksAreTheSensorTimeInNanosecondsAndNeverNegative() {
        assertEquals(1_234_567_890L, RawVideoRecorder.ticksOf(1_234_567_890L))
        assertEquals(0L, RawVideoRecorder.ticksOf(-5L))
        assertEquals(1L, RawVideoRecorder.TIME_BASE.numerator)
        assertEquals(1_000_000_000L, RawVideoRecorder.TIME_BASE.denominator)
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 16
        const val ROW_BYTES = WIDTH / 4 * 5
        const val FRAME_BYTES = 4
        const val CHUNK_FRAMES = 4_800
        val CONFIG = RawVideoConfig("0", WIDTH, HEIGHT, 32, 8, 30)

        fun ts(index: Int): Long = 1_000_000_000L + index * 33_333_333L
        fun audioNs(index: Int): Long = 990_000_000L + index * 100_000_000L
    }
}
