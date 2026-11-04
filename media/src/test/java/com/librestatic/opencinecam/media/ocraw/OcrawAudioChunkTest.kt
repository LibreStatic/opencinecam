// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** PLAN-071 §7.1: `AUDIO_PCM16` chunks interleaved with RAW frames, as a RAW take with sound writes them. */
class OcrawAudioChunkTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/ocraw-audio-test").apply { mkdirs() })

    private val nanos = OcrawTimeBase(1, 1_000_000_000)
    private val width = 32
    private val height = 4

    private fun frameTs(index: Int) = 2_000_000_000L + index * 33_333_333L
    private fun audioTs(index: Int) = 1_990_000_000L + index * 100_000_000L
    private fun pcm(index: Int) = ByteArray(4_800 * 4) { (index * 17 + it).toByte() }

    /** Writes [frames] frames with one audio chunk after the metadata and one after every third frame. */
    private fun writeTake(file: File, frames: Int, finish: Boolean = true): List<Int> {
        val audioAfterFrame = ArrayList<Int>()
        RandomAccessFile(file, "rw").use { raf ->
            val writer = OcrawStreamWriter(raf.channel, OcrawHeader(ByteArray(16)), nanos, OcrawStreamWriter.Options(forceOnFinish = false), force = null)
            writer.writeMetadata(mapOf("cfa" to "RGGB", "audioSampleRate" to "48000", "audioChannels" to "2", "audioSource" to "UNPROCESSED"), frameTs(0))
            var audio = 0
            writer.writeChunk(OcrawChunkId.AUDIO_PCM16.wireId, audioTs(audio), ByteBuffer.wrap(pcm(audio++)))
            for (index in 0 until frames) {
                val header = OcrawFrameHeader.raw10(width, height, 0, 0, frameTs(index), index.toLong())
                val data = ByteBuffer.allocateDirect(header.dataLength)
                while (data.hasRemaining()) data.put(index.toByte())
                data.flip()
                writer.writeRawFrame(header, data, frameTs(index))
                if (index % 3 == 2) {
                    writer.writeChunk(OcrawChunkId.AUDIO_PCM16.wireId, audioTs(audio), ByteBuffer.wrap(pcm(audio++)))
                    audioAfterFrame += index
                }
            }
            writer.writeMetadata(mapOf("audioDroppedSamples" to "0"))
            if (finish) writer.finish() else writer.abort()
        }
        return audioAfterFrame
    }

    @Test
    fun indexFramesSkipsAudioChunksAndScanAcceptsThem() {
        val file = temp.newFile("audio.ocraw")
        writeTake(file, frames = 10)
        RandomAccessFile(file, "r").use { raf ->
            val reader = OcrawStreamReader(raf.channel)
            val index = reader.indexFrames()
            assertFalse(index.tornTail)
            assertTrue(index.hasEnd)
            assertTrue(index.errors.toString(), index.errors.isEmpty())
            assertEquals((0 until 10).map(::frameTs), index.frames.map { it.timestampTicks })
            assertEquals(nanos, index.timeBase)
            assertEquals("48000", index.metadata["audioSampleRate"])
            assertEquals("0", index.metadata["audioDroppedSamples"])
            // Frame data is still read from the indexed offsets.
            val dst = ByteBuffer.allocate(index.frames[4].header!!.dataLength)
            reader.readRawFrame(index.frames[4].fileOffset, dst)
            assertTrue(dst.array().all { it == 4.toByte() })

            val scan = reader.scan()
            assertTrue(scan.errors.toString(), scan.cleanlyClosed)
            assertEquals(10, scan.frameCount)
            assertTrue(scan.unknownChunkIds.isEmpty())
            assertEquals("2", scan.metadata["audioChannels"])
        }
        val chunks = OcrawCodec.scanContainer(file.readBytes()).chunks.filter { it.wireId == OcrawChunkId.AUDIO_PCM16.wireId }
        assertEquals(4, chunks.size)
        assertEquals((0 until 4).map(::audioTs), chunks.map { it.timestampTicks })
        assertArrayEquals(pcm(2), chunks[2].payload)
    }

    @Test
    fun aTornTakeWithAudioKeepsItsCompleteFramesAndChunks() {
        val file = temp.newFile("torn.ocraw")
        writeTake(file, frames = 6, finish = false)
        // Cut into the last chunk (the closing metadata).
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 3) }
        RandomAccessFile(file, "r").use { raf ->
            val index = OcrawStreamReader(raf.channel).indexFrames()
            assertEquals(6, index.frames.size)
            assertEquals("48000", index.metadata["audioSampleRate"])
        }
        RandomAccessFile(file, "rw").use { raf ->
            val recovered = OcrawStreamReader.recover(raf.channel)
            assertEquals(6, recovered.scan.frameCount)
        }
    }
}
