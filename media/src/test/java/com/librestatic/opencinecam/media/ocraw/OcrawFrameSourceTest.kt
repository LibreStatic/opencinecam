// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import com.librestatic.opencinecam.media.raw.NativeRawCodec
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrawFrameSourceTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/ocraw-frame-source-test").apply { mkdirs() })

    private val uuid = ByteArray(16) { (it * 3).toByte() }
    private val nanos = OcrawTimeBase(1, 1_000_000_000)
    private val width = 64
    private val height = 12
    private val frameInterval = 33_333_333L
    private val staticMetadata = mapOf("cfa" to "RGGB", "blackLevel" to "64 64 64 64", "whiteLevel" to "1023")

    @Test
    fun indexEqualsScanOnACleanFile() {
        val file = temp.newFile("clean.ocraw")
        writeTake(file, (0 until 7).map(::frame), options = OcrawStreamWriter.Options(periodicIndexFrames = 3))
        FileChannel.open(file.toPath()).use { channel ->
            val reader = OcrawStreamReader(channel)
            val scan = reader.scan()
            val index = reader.indexFrames()
            assertTrue(scan.cleanlyClosed)
            assertEquals(OcrawHeader(uuid), index.header)
            assertEquals(scan.frames, index.frames)
            assertEquals(scan.metadata, index.metadata)
            assertEquals(scan.frameMetadata, index.frameMetadata)
            assertEquals(file.length(), index.validEnd)
            assertEquals(file.length(), index.fileSize)
            assertFalse(index.tornTail)
            assertTrue(index.hasEnd)
            assertEquals(nanos, index.timeBase)
            assertTrue(index.errors.isEmpty())
        }
        val empty = temp.newFile("empty.ocraw")
        FileChannel.open(empty.toPath()).use { channel ->
            val index = OcrawStreamReader(channel).indexFrames()
            assertNull(index.header)
            assertTrue(index.tornTail)
            assertTrue(index.frames.isEmpty())
        }
    }

    @Test
    fun aTornTailIndexesItsCompleteFrames() {
        val file = temp.newFile("full.ocraw")
        writeTake(file, (0 until 5).map(::frame))
        val full = file.readBytes()
        val boundaries = OcrawCodec.scanContainer(full).chunks.runningFold(OcrawCodec.HEADER_BYTES) { end, chunk ->
            end + OcrawCodec.CHUNK_HEADER_BYTES + chunk.payload.size
        }
        val cuts = sortedSetOf<Int>()
        boundaries.zipWithNext().forEach { (start, end) ->
            val payload = start + OcrawCodec.CHUNK_HEADER_BYTES
            cuts += listOf(start, start + 1, payload, payload + 10, (start + end) / 2, end - 1)
        }
        // END has no payload, so its start + chunk header is the whole file: keep only real cuts.
        for (cut in cuts.filter { it < full.size }) {
            val prefix = temp.newFile("cut-$cut.ocraw")
            prefix.writeBytes(full.copyOf(cut))
            FileChannel.open(prefix.toPath()).use { channel ->
                val reader = OcrawStreamReader(channel)
                val scan = reader.scan()
                val index = reader.indexFrames()
                assertEquals("cut $cut", scan.frames, index.frames)
                assertEquals("cut $cut", scan.frameMetadata, index.frameMetadata)
                assertEquals("cut $cut", scan.lastValidOffset, index.validEnd)
                assertEquals("cut $cut", scan.staleEvidence, index.tornTail)
                assertEquals("cut $cut", cut.toLong() != index.validEnd, index.tornTail)
                assertFalse(index.hasEnd)
                index.frames.forEachIndexed { i, info -> assertEquals(frame(i).first, info.header) }
            }
        }
    }

    @Test
    fun lateFrameMetadataAttachesToItsFrame() {
        val file = temp.newFile("late.ocraw")
        val frames = (0 until 4).map(::frame)
        val sequences = FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            val writer = OcrawStreamWriter(channel, OcrawHeader(uuid), nanos)
            writer.writeMetadata(staticMetadata)
            val sequences = frames.mapIndexed { i, (header, data) ->
                writer.writeRawFrame(header, ByteBuffer.wrap(data), i * frameInterval)
            }
            // Frame 0's result arrives after frame 2; a later static key wins.
            writer.writeFrameMetadata(sequences[0], mapOf("neutral" to "1/2 1/1 3/4", "iso" to "100"))
            writer.writeMetadata(mapOf("whiteLevel" to "4095", "fps" to "30/1"))
            writer.writeFrameMetadata(sequences[3], mapOf("iso" to "800"))
            writer.writeFrameMetadata(sequences[1], mapOf("iso" to "200"))
            writer.finish()
            sequences
        }
        FileChannel.open(file.toPath()).use { channel ->
            val reader = OcrawStreamReader(channel)
            val index = reader.indexFrames()
            assertEquals(reader.scan().frameMetadata, index.frameMetadata)
            assertEquals(staticMetadata + mapOf("whiteLevel" to "4095", "fps" to "30/1"), index.metadata)
            assertEquals(mapOf("neutral" to "1/2 1/1 3/4", "iso" to "100"), index.frameMetadata[sequences[0]])
            assertEquals(mapOf("iso" to "800"), index.frameMetadata[sequences[3]])
            assertNull(index.frameMetadata[sequences[2]])
            OcrawFrameSource(channel, index).use { source ->
                val slot = source.acquire()!!
                val first = source.read(0, slot) as OcrawFrameRead.Frame
                assertEquals("100", first.metadata["iso"])
                val third = source.read(2, slot) as OcrawFrameRead.Frame
                assertEquals(emptyMap<String, String>(), third.metadata)
                assertEquals("1/2 1/1 3/4", third.neutral)
                assertEquals(mapOf("iso" to "200"), (source.read(1, slot) as OcrawFrameRead.Frame).metadata)
            }
        }
    }

    @Test
    fun mixedEncodingsAreIndexedWithTheirEncodings() {
        val file = temp.newFile("mixed.ocraw")
        val raw = frame(0)
        val lj92 = frame(1).first.copy(encoding = OcrawFrameHeader.ENCODING_LJ92, dataLength = 777) to
            Random(1).nextBytes(777)
        val packed = frame(2).first.copy(encoding = OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK, dataLength = 401) to
            Random(2).nextBytes(401)
        val future = frame(3).first.copy(encoding = 9, dataLength = 50) to Random(3).nextBytes(50)
        writeTake(file, listOf(raw, lj92, packed, future))
        FileChannel.open(file.toPath()).use { channel ->
            val index = OcrawStreamReader(channel).indexFrames()
            assertEquals(listOf(0, 1, 2, 9), index.frames.map { it.header!!.encoding })
            assertEquals(listOf(raw, lj92, packed, future).map { it.first }, index.frames.map { it.header })
            OcrawFrameSource(channel, index).use { source ->
                assertEquals(width / 4 * 5 * height, source.slotBytes)
                val slot = source.acquire()!!
                assertTrue(source.read(0, slot) is OcrawFrameRead.Frame)
                assertEquals(OcrawFrameRead.Missing(3, OcrawMissingReason.UNSUPPORTED_ENCODING), source.read(3, slot))
                if (!NativeRawCodec.available) {
                    assertEquals(OcrawFrameRead.Missing(1, OcrawMissingReason.CODEC_UNAVAILABLE), source.read(1, slot))
                    assertEquals(OcrawFrameRead.Missing(2, OcrawMissingReason.CODEC_UNAVAILABLE), source.read(2, slot))
                } else {
                    // Random bytes are not a valid container.
                    assertEquals(OcrawFrameRead.Missing(1, OcrawMissingReason.DECODE_FAILED), source.read(1, slot))
                }
            }
        }
    }

    @Test
    fun frameSourceReturnsExactRaw10BytesFromABoundedPool() {
        val file = temp.newFile("exact.ocraw")
        val frames = (0 until 5).map(::frame)
        writeTake(file, frames)
        FileChannel.open(file.toPath()).use { channel ->
            OcrawFrameSource(channel, OcrawStreamReader(channel).indexFrames()).use { source ->
                assertEquals(5, source.frameCount)
                assertEquals(3, OcrawFrameSource.DEFAULT_SLOTS)
                repeat(OcrawFrameSource.DEFAULT_SLOTS) { assertNotNull(source.acquire()) }
                assertNull(source.acquire())
                source.close()
                assertThrows(IllegalStateException::class.java) { source.acquire() }
            }
            OcrawFrameSource(channel, OcrawStreamReader(channel).indexFrames()).use { source ->
                val a = source.acquire()!!
                val b = source.acquire()!!
                // Reading out of order, as a scrubbing viewer does.
                for (i in listOf(4, 0, 2, 2, 1, 3)) {
                    val slot = if (i % 2 == 0) a else b
                    val read = source.read(i, slot) as OcrawFrameRead.Frame
                    assertEquals(frames[i].first, read.header)
                    assertEquals(width * 5 / 4, read.rowBytes)
                    assertEquals(0, slot.buffer.position())
                    assertEquals(read.rowBytes * height, slot.buffer.limit())
                    assertArrayEquals(frames[i].second, ByteArray(slot.buffer.remaining()).also(slot.buffer::get))
                }
                source.release(b)
                assertTrue(source.acquire() === b)
                source.release(a)
                assertThrows(IllegalArgumentException::class.java) { source.read(0, a) }
                assertThrows(IllegalArgumentException::class.java) { source.read(5, b) }
            }
        }
    }

    @Test
    fun aCrcCorruptedFrameIsMissingAndTheTakeStillOpens() {
        val file = temp.newFile("flip.ocraw")
        val frames = (0 until 5).map(::frame)
        writeTake(file, frames)
        val target = FileChannel.open(file.toPath()).use { OcrawStreamReader(it).indexFrames() }.frames[3]
        val flipAt = target.fileOffset + OcrawCodec.CHUNK_HEADER_BYTES + OcrawFrameHeader.BYTES + 100
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(flipAt)
            val value = raf.read()
            raf.seek(flipAt)
            raf.write(value xor 0x10)
        }
        FileChannel.open(file.toPath()).use { channel ->
            val index = OcrawStreamReader(channel).indexFrames()
            // The index does not CRC frames: all five are listed and the tail is not torn.
            assertEquals(5, index.frames.size)
            assertFalse(index.tornTail)
            OcrawFrameSource(channel, index).use { source ->
                val slot = source.acquire()!!
                assertEquals(OcrawFrameRead.Missing(3, OcrawMissingReason.CORRUPT), source.read(3, slot))
                val next = source.read(4, slot) as OcrawFrameRead.Frame
                assertArrayEquals(frames[4].second, ByteArray(slot.buffer.remaining()).also(slot.buffer::get))
                assertEquals(frames[4].first, next.header)
            }
        }
    }

    @Test
    fun neutralComesFromTheNearestFrameThatHasOne() {
        val file = temp.newFile("neutral.ocraw")
        val neutrals = mapOf(2 to "2/4 1/1 3/4", 4 to "4/4 1/1 1/4")
        writeTake(file, (0 until 6).map(::frame)) { i ->
            neutrals[i]?.let { mapOf("neutral" to it, "iso" to "$i") } ?: mapOf("iso" to "$i")
        }
        FileChannel.open(file.toPath()).use { channel ->
            OcrawFrameSource(channel, OcrawStreamReader(channel).indexFrames()).use { source ->
                val slot = source.acquire()!!
                val resolved = (0 until 6).map { (source.read(it, slot) as OcrawFrameRead.Frame).neutral }
                // Before the first neutral: the later one; after it: the nearest earlier one.
                assertEquals(listOf(2, 2, 2, 2, 4, 4).map(neutrals::getValue), resolved)
                val own = source.read(3, slot) as OcrawFrameRead.Frame
                assertEquals(mapOf("iso" to "3"), own.metadata)
            }
        }
        val none = temp.newFile("no-neutral.ocraw")
        writeTake(none, (0 until 3).map(::frame))
        FileChannel.open(none.toPath()).use { channel ->
            OcrawFrameSource(channel, OcrawStreamReader(channel).indexFrames()).use { source ->
                val slot = source.acquire()!!
                assertTrue((0 until 3).all { (source.read(it, slot) as OcrawFrameRead.Frame).neutral == null })
            }
        }
    }

    @Test
    fun lj92FramesDecodeToPackedRaw10() {
        assumeTrue("libocc_rawcodec is not loaded on the host JVM", NativeRawCodec.available)
        val file = temp.newFile("lj92.ocraw")
        val (rawHeader, data) = frame(0)
        val encoded = NativeRawCodec.newEncoder(NativeRawCodec.Codec.LJ92, threads = 1).use { encoder ->
            val src = ByteBuffer.allocateDirect(data.size).put(data).also { it.flip() }
            val dst = ByteBuffer.allocateDirect(encoder.maxEncodedSize(width, height).toInt())
            val size = encoder.encode(src, width, height, dst)
            ByteArray(size).also { dst.get(it) }
        }
        val header = rawHeader.copy(encoding = OcrawFrameHeader.ENCODING_LJ92, dataLength = encoded.size)
        writeTake(file, listOf(header to encoded, frame(1)))
        FileChannel.open(file.toPath()).use { channel ->
            OcrawFrameSource(channel, OcrawStreamReader(channel).indexFrames(), decoderThreads = 2).use { source ->
                val slot = source.acquire()!!
                assertTrue(source.read(0, slot) is OcrawFrameRead.Frame)
                assertArrayEquals(data, ByteArray(slot.buffer.remaining()).also(slot.buffer::get))
            }
        }
    }

    private fun frame(index: Int): Pair<OcrawFrameHeader, ByteArray> {
        val header = OcrawFrameHeader.raw10(width, height, 8, 4, 1_000_000_000L + index * frameInterval, 100L + index)
        return header to Random(index).nextBytes(header.dataLength)
    }

    private fun writeTake(
        file: File,
        frames: List<Pair<OcrawFrameHeader, ByteArray>>,
        options: OcrawStreamWriter.Options = OcrawStreamWriter.Options(),
        frameMetadata: (Int) -> Map<String, String> = { mapOf("iso" to "400", "exposureNs" to "${10_000_000 + it}") },
    ): OcrawStreamStats = FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
        val writer = OcrawStreamWriter(channel, OcrawHeader(uuid), nanos, options)
        writer.writeMetadata(staticMetadata)
        frames.forEachIndexed { index, (header, data) ->
            val sequence = writer.writeRawFrame(header, ByteBuffer.wrap(data), index * frameInterval)
            writer.writeFrameMetadata(sequence, frameMetadata(index))
        }
        writer.finish()
    }
}
