// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrawStreamTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/ocraw-stream-test").apply { mkdirs() })

    private val uuid = ByteArray(16) { (it * 7).toByte() }
    private val nanos = OcrawTimeBase(1, 1_000_000_000)
    private val width = 64
    private val height = 12
    private val frameInterval = 33_333_333L

    private val staticMetadata = mapOf(
        "cfa" to "RGGB",
        "blackLevel" to "64 64 64 64",
        "whiteLevel" to "1023",
        "colorMatrix1" to "1/1 0/1 0/1 0/1 1/1 0/1 0/1 0/1 1/1",
        "fps" to "30/1",
    )

    @Test
    fun frameHeaderRoundTripAndValidation() {
        val header = OcrawFrameHeader.raw10(4080, 3060, 0, 0, 123_456_789_000L, 42)
        assertEquals(5100, header.rowBytes)
        assertEquals(15_606_000, header.dataLength)
        val encoded = header.encode()
        assertEquals(OcrawFrameHeader.BYTES, encoded.size)
        assertEquals(header, OcrawFrameHeader.decode(encoded))
        val le = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, le.getShort(0).toInt())
        assertEquals(0, le.getShort(2).toInt())
        assertEquals(4080, le.getInt(4))
        assertEquals(42L, le.getLong(32))
        assertTrue((44 until 64).all { encoded[it] == 0.toByte() })
        // Big-endian destinations still get little-endian bytes and keep their order.
        val big = ByteBuffer.allocate(70).order(ByteOrder.BIG_ENDIAN)
        big.position(3)
        header.writeTo(big)
        assertEquals(67, big.position())
        assertEquals(ByteOrder.BIG_ENDIAN, big.order())
        assertArrayEquals(encoded, big.array().copyOfRange(3, 67))

        assertThrows(IllegalArgumentException::class.java) { OcrawFrameHeader.raw10(4082, 2, 0, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { OcrawFrameHeader.raw10(8, 2, 1, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            OcrawFrameHeader(8, 2, 11, 0, 0, 0, 0, 22)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrawFrameHeader(8, 2, 10, 0, 0, 0, 0, 21)
        }
        assertThrows(IllegalArgumentException::class.java) { OcrawFrameHeader.decode(encoded.copyOf(63)) }
        val wrongVersion = encoded.copyOf().also { it[0] = 2 }
        assertThrows(IllegalArgumentException::class.java) { OcrawFrameHeader.decode(wrongVersion) }
        // A reserved encoding is carried without RAW10 geometry checks.
        val lj92 = OcrawFrameHeader(8, 2, 7, 0, 0, 0, 0, 13, encoding = OcrawFrameHeader.ENCODING_LJ92)
        assertEquals(lj92, OcrawFrameHeader.decode(lj92.encode()))
        assertEquals(OcrawChunkId.FRAME_METADATA, OcrawChunk(0x0040, 0, 0, nanos, ByteArray(0)).knownId())
    }

    @Test
    fun writerReaderRoundTrip() {
        val file = temp.newFile("take.ocraw")
        val frames = (0 until 7).map(::frame)
        val stats = writeTake(file, frames)
        assertEquals(7, stats.framesWritten)
        assertTrue(stats.finalIndexWritten && stats.endWritten)
        assertEquals(file.length(), stats.bytesWritten)

        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val reader = OcrawStreamReader(channel)
            val scan = reader.scan()
            assertEquals(OcrawHeader(uuid), scan.header)
            assertTrue(scan.cleanlyClosed)
            assertFalse(scan.staleEvidence)
            assertEquals(file.length(), scan.lastValidOffset)
            assertEquals(7, scan.frameCount)
            assertEquals(staticMetadata, scan.metadata)
            assertTrue(scan.sequencesMonotonic)
            assertTrue(scan.errors.isEmpty())
            assertEquals(scan.rebuiltIndex.toList(), scan.finalIndex)
            val dst = ByteBuffer.allocateDirect(frames[0].second.size)
            scan.frames.forEachIndexed { index, info ->
                val (header, data) = frames[index]
                assertEquals(header, info.header)
                assertEquals(index * frameInterval, info.timestampTicks)
                dst.clear()
                assertEquals(header, reader.readRawFrame(info.fileOffset, dst))
                assertEquals(data.size, dst.position())
                dst.flip()
                val back = ByteArray(dst.remaining()).also(dst::get)
                assertArrayEquals(data, back)
                val metadata = scan.frameMetadata.getValue(info.sequence)
                assertEquals(mapOf("exposureNs" to "${10_000_000 + index}", "iso" to "400"), metadata)
            }
        }
        // The in-memory reference reader agrees, which is the desktop CLI's contract too.
        val verification = OcrawDesktopReader(file.readBytes()).verify()
        assertTrue(verification.errors.toString(), verification.valid)
        assertTrue(verification.hasFinalIndex && verification.hasEndChunk)
    }

    @Test
    fun framesOfDifferentEncodingsShareOneFile() {
        // Encoding is per frame: a compressed take stores a frame that did not fit as RAW10.
        val file = temp.newFile("mixed.ocraw")
        val raw = frame(0)
        val lj92Header = frame(1).first.copy(encoding = OcrawFrameHeader.ENCODING_LJ92, dataLength = 777)
        val lj92 = lj92Header to Random(1).nextBytes(777)
        val packed = frame(2).first.copy(encoding = OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK, dataLength = 401)
        val frames = listOf(raw, lj92, packed to Random(2).nextBytes(401))
        writeTake(file, frames)
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val reader = OcrawStreamReader(channel)
            val scan = reader.scan()
            assertTrue(scan.errors.toString(), scan.cleanlyClosed)
            assertEquals(3, scan.frameCount)
            assertEquals(listOf(0, 1, 2), scan.frames.map { it.header!!.encoding })
            val dst = ByteBuffer.allocateDirect(raw.second.size)
            scan.frames.forEachIndexed { index, info ->
                val (header, data) = frames[index]
                assertEquals(header, info.header)
                // Encoded frames keep the RAW10 row length of the decoded frame.
                assertEquals(width / 4 * 5, info.header!!.rowBytes)
                dst.clear()
                assertEquals(header, reader.readRawFrame(info.fileOffset, dst))
                dst.flip()
                assertArrayEquals(data, ByteArray(dst.remaining()).also(dst::get))
            }
        }
        assertTrue(OcrawDesktopReader(file.readBytes()).verify().valid)
    }

    @Test
    fun periodicIndexCadenceByFramesAndByTime() {
        val byFrames = temp.newFile("frames.ocraw")
        writeTake(byFrames, (0 until 100).map(::frame), frameMetadata = false,
            options = OcrawStreamWriter.Options(periodicIndexFrames = 32))
        val bytes = byFrames.readBytes()
        val scan = OcrawCodec.scanContainer(bytes)
        val periodic = scan.chunks.filter { it.knownId() == OcrawChunkId.PERIODIC_INDEX }
        assertEquals(3, periodic.size)
        val union = periodic.flatMap { OcrawIndexCodec.decode(it.payload) }
        val rebuilt = OcrawDesktopReader(bytes).rebuildIndex()
        // Each periodic index lists only the entries since the previous one.
        assertEquals(rebuilt.take(union.size), union)
        assertEquals(1 + 32, OcrawIndexCodec.decode(periodic.first().payload).size)

        // Half a second between frames: the 1 s media-time cadence wins.
        val byTime = temp.newFile("time.ocraw")
        val slow = (0 until 9).map { index -> frame(index) }
        writeTake(byTime, slow, frameMetadata = false, interval = 500_000_000L)
        val slowScan = FileChannel.open(byTime.toPath()).use { OcrawStreamReader(it).scan() }
        assertEquals(4, slowScan.periodicIndexCount)
        assertTrue(slowScan.cleanlyClosed)
    }

    @Test
    fun truncationAnywhereRecoversEveryCompleteFrame() {
        val file = temp.newFile("full.ocraw")
        writeTake(file, (0 until 6).map(::frame), options = OcrawStreamWriter.Options(periodicIndexFrames = 2))
        val full = file.readBytes()
        val scan = OcrawCodec.scanContainer(full)
        val ends = ArrayList<Pair<Int, Int>>() // chunk end offset to frames complete by then
        var offset = OcrawCodec.HEADER_BYTES
        var framesSoFar = 0
        scan.chunks.forEach { chunk ->
            offset += OcrawCodec.CHUNK_HEADER_BYTES + chunk.payload.size
            if (chunk.knownId() == OcrawChunkId.RAW_FRAME) framesSoFar++
            ends += offset to framesSoFar
        }
        val cuts = sortedSetOf<Int>()
        var start = OcrawCodec.HEADER_BYTES
        ends.forEach { (end, _) ->
            cuts += listOf(start, start + 1, start + 20, start + OcrawCodec.CHUNK_HEADER_BYTES, (start + end) / 2,
                end - 1, end)
            start = end
        }
        cuts += listOf(0, 5, OcrawCodec.HEADER_BYTES - 1)
        for (cut in cuts) {
            val expectedFrames = ends.lastOrNull { it.first <= cut }?.second ?: 0
            val expectedOffset = ends.lastOrNull { it.first <= cut }?.first?.toLong()
                ?: if (cut >= OcrawCodec.HEADER_BYTES) OcrawCodec.HEADER_BYTES.toLong() else 0L
            val prefix = temp.newFile("cut-$cut.ocraw")
            prefix.writeBytes(full.copyOf(cut))
            FileChannel.open(prefix.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                val before = OcrawStreamReader(channel).scan()
                assertEquals("cut $cut", expectedFrames, before.frameCount)
                assertEquals("cut $cut", expectedOffset, before.lastValidOffset)
                val result = OcrawStreamReader.recover(channel)
                if (cut < OcrawCodec.HEADER_BYTES) {
                    assertEquals(OcrawRecoveryAction.UNRECOVERABLE, result.action)
                    assertEquals(cut.toLong(), channel.size())
                    return@use
                }
                val expectedAction = if (cut == full.size) {
                    OcrawRecoveryAction.ALREADY_CLEAN
                } else if (before.endsWithEnd) {
                    OcrawRecoveryAction.TRUNCATED
                } else {
                    OcrawRecoveryAction.FINALIZED
                }
                assertEquals("cut $cut", expectedAction, result.action)
                assertEquals(expectedFrames, result.framesRecovered)
                val after = OcrawStreamReader(channel).scan()
                assertTrue("cut $cut", after.cleanlyClosed)
                assertEquals(expectedFrames, after.frameCount)
                assertEquals(after.rebuiltIndex.toList(), after.finalIndex)
                after.frames.forEachIndexed { index, info -> assertEquals(frame(index).first, info.header) }
                // Recovering again is a no-op.
                assertEquals(OcrawRecoveryAction.ALREADY_CLEAN, OcrawStreamReader.recover(channel).action)
            }
            if (cut >= OcrawCodec.HEADER_BYTES) {
                val verification = OcrawDesktopReader(prefix.readBytes()).verify()
                assertTrue("cut $cut ${verification.errors}", verification.valid)
            }
        }
    }

    @Test
    fun recoverWithoutFinalizeOnlyTruncates() {
        val file = temp.newFile("open.ocraw")
        val frames = (0 until 3).map(::frame)
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            val writer = OcrawStreamWriter(channel, OcrawHeader(uuid), nanos)
            frames.forEachIndexed { index, (header, data) ->
                writer.writeRawFrame(header, ByteBuffer.wrap(data), index * frameInterval)
            }
            val stats = writer.abort()
            assertFalse(stats.endWritten)
            assertThrows(IllegalStateException::class.java) {
                writer.writeRawFrame(frames[0].first, ByteBuffer.wrap(frames[0].second), 0)
            }
        }
        RandomAccessFile(file, "rw").use { it.write(ByteArray(10)); it.seek(file.length()); it.write(ByteArray(10)) }
        FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            // Ten zero bytes overwrote the file header: unrecoverable and untouched.
            assertEquals(OcrawRecoveryAction.UNRECOVERABLE, OcrawStreamReader.recover(channel).action)
        }
        val good = temp.newFile("open2.ocraw")
        FileChannel.open(good.toPath(), StandardOpenOption.WRITE).use { channel ->
            val writer = OcrawStreamWriter(channel, OcrawHeader(uuid), nanos)
            frames.forEachIndexed { index, (header, data) ->
                writer.writeRawFrame(header, ByteBuffer.wrap(data), index * frameInterval)
            }
            writer.abort()
            channel.write(ByteBuffer.wrap(ByteArray(17) { 0x55 }))
        }
        FileChannel.open(good.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val result = OcrawStreamReader.recover(channel, finalize = false)
            assertEquals(OcrawRecoveryAction.TRUNCATED, result.action)
            assertEquals(17, result.truncatedBytes)
            val scan = OcrawStreamReader(channel).scan()
            assertEquals(3, scan.frameCount)
            assertFalse(scan.staleEvidence)
            assertFalse(scan.hasEnd)
        }
    }

    @Test
    fun bitFlipIsDetected() {
        val file = temp.newFile("flip.ocraw")
        writeTake(file, (0 until 5).map(::frame), frameMetadata = false)
        val clean = FileChannel.open(file.toPath()).use { OcrawStreamReader(it).scan() }
        val target = clean.frames[3]
        val flipAt = target.fileOffset + OcrawCodec.CHUNK_HEADER_BYTES + OcrawFrameHeader.BYTES + 100
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(flipAt)
            val value = raf.read()
            raf.seek(flipAt)
            raf.write(value xor 0x10)
        }
        FileChannel.open(file.toPath()).use { channel ->
            val reader = OcrawStreamReader(channel)
            val scan = reader.scan()
            assertTrue(scan.staleEvidence)
            assertEquals(3, scan.frameCount)
            assertEquals(target.fileOffset, scan.lastValidOffset)
            assertFalse(scan.cleanlyClosed)
            val dst = ByteBuffer.allocate(frame(0).second.size)
            assertThrows(IOException::class.java) { reader.readRawFrame(target.fileOffset, dst) }
            dst.clear()
            assertEquals(frame(2).first, reader.readRawFrame(clean.frames[2].fileOffset, dst))
            assertThrows(IllegalArgumentException::class.java) {
                reader.readRawFrame(OcrawCodec.HEADER_BYTES.toLong(), ByteBuffer.allocate(16))
            }
        }
    }

    @Test
    fun writerValidatesAndForces() {
        val file = temp.newFile("force.ocraw")
        var forces = 0
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            val writer = OcrawStreamWriter(channel, OcrawHeader(uuid), nanos,
                OcrawStreamWriter.Options(forceEveryBytes = 2000), force = { forces++ })
            val (header, data) = frame(0)
            assertThrows(IllegalArgumentException::class.java) {
                writer.writeRawFrame(header, ByteBuffer.wrap(data, 0, data.size - 1), 0)
            }
            assertThrows(IllegalArgumentException::class.java) { writer.writeFrameMetadata(0, mapOf("iso" to "1")) }
            assertThrows(IllegalArgumentException::class.java) {
                writer.writeChunk(OcrawChunkId.END.wireId, 0, ByteBuffer.allocate(0))
            }
            repeat(4) { index ->
                val sequence = writer.writeRawFrame(frame(index).first, ByteBuffer.wrap(frame(index).second), 0)
                assertEquals(index.toLong(), sequence)
            }
            assertThrows(IllegalArgumentException::class.java) {
                writer.writeFrameMetadata(0, mapOf(OcrawStreamWriter.SEQUENCE_KEY to "1"))
            }
            val stats = writer.finish()
            assertEquals(stats, writer.finish())
            assertTrue(stats.forceCount >= 3)
            assertEquals(stats.forceCount, forces)
            assertThrows(IllegalStateException::class.java) { writer.writeMetadata(mapOf("a" to "b")) }
        }
        val nonEmpty = temp.newFile("nonempty.ocraw").apply { writeBytes(ByteArray(3)) }
        FileChannel.open(nonEmpty.toPath(), StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            assertThrows(IllegalArgumentException::class.java) { OcrawStreamWriter(channel, OcrawHeader(uuid), nanos) }
        }
    }

    @Test
    fun unknownChunksAreSkippedAndPlainChannelsWork() {
        val file = temp.newFile("plain.ocraw")
        RandomAccessFile(file, "rw").use { raf ->
            // A WritableByteChannel that is not a GatheringByteChannel.
            val plain = object : java.nio.channels.WritableByteChannel {
                override fun write(src: ByteBuffer): Int = raf.channel.write(src)
                override fun isOpen() = true
                override fun close() = Unit
            }
            val writer = OcrawStreamWriter(plain, OcrawHeader(uuid), nanos, force = null)
            writer.writeChunk(0x7123, 0, ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
            writer.writeRawFrame(frame(0).first, ByteBuffer.wrap(frame(0).second), 5)
            writer.finish()
        }
        FileChannel.open(file.toPath()).use { channel ->
            val scan = OcrawStreamReader(channel).scan()
            assertTrue(scan.cleanlyClosed)
            assertEquals(setOf(0x7123), scan.unknownChunkIds)
            assertEquals(1, scan.frameCount)
            assertNotNull(scan.finalIndex)
            assertEquals(2, scan.finalIndex!!.size)
        }
        val empty = temp.newFile("empty.ocraw")
        FileChannel.open(empty.toPath()).use { assertNull(OcrawStreamReader(it).scan().header) }
    }

    @Test
    fun writerThroughputSmoke() {
        val file = temp.newFile("throughput.ocraw")
        val header = OcrawFrameHeader.raw10(4080, 3060, 0, 0, 0, 0)
        val data = ByteBuffer.allocateDirect(header.dataLength)
        val random = Random(3)
        while (data.remaining() >= 8) data.putLong(random.nextLong())
        val frames = 16
        val stats = FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            val writer = OcrawStreamWriter(channel, OcrawHeader(uuid), nanos)
            val start = System.nanoTime()
            repeat(frames) { index ->
                data.clear()
                val frameHeader = header.copy(frameNumber = index.toLong(), sensorTimestampNs = index * 16_666_667L)
                writer.writeRawFrame(frameHeader, data, frameHeader.sensorTimestampNs)
            }
            val stats = writer.finish()
            val seconds = (System.nanoTime() - start) / 1e9
            println(
                "OcrawStreamWriter: %d x %.1f MB in %.2f s = %.0f MB/s (max write %.1f ms, %d forces, max force %.1f ms)"
                    .format(frames, header.dataLength / 1e6, seconds, stats.bytesWritten / 1e6 / seconds,
                        stats.maxWriteMs, stats.forceCount, stats.maxForceMs),
            )
            stats
        }
        assertEquals(frames.toLong(), stats.framesWritten)
        val scanStart = System.nanoTime()
        val scan = FileChannel.open(file.toPath()).use { OcrawStreamReader(it).scan() }
        val scanSeconds = (System.nanoTime() - scanStart) / 1e9
        println("OcrawStreamReader scan: %.0f MB/s".format(file.length() / 1e6 / scanSeconds))
        assertTrue(scan.cleanlyClosed)
        assertEquals(frames, scan.frameCount)
        // Opening for playback reads only the headers.
        val indexStart = System.nanoTime()
        val index = FileChannel.open(file.toPath()).use { OcrawStreamReader(it).indexFrames() }
        println("OcrawStreamReader indexFrames: %.2f ms for %.0f MB".format((System.nanoTime() - indexStart) / 1e6,
            file.length() / 1e6))
        assertEquals(scan.frames, index.frames)
        file.delete()
    }

    @Test
    fun indexListsAudioChunksBetweenFramesAndReadsThemChecked() {
        val file = temp.newFile("audio.ocraw")
        val pcm = List(3) { Random(100 + it).nextBytes(9_600) }
        val offsets = ArrayList<Long>()
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            val writer = OcrawStreamWriter(channel, OcrawHeader(uuid), nanos)
            writer.writeMetadata(staticMetadata + mapOf("audioSampleRate" to "48000", "audioChannels" to "1"))
            (0 until 3).forEach { index ->
                val (header, data) = frame(index)
                writer.writeRawFrame(header, ByteBuffer.wrap(data), index * frameInterval)
                offsets += channel.position()
                writer.writeChunk(OcrawChunkId.AUDIO_PCM16.wireId, index * 100_000_000L, ByteBuffer.wrap(pcm[index]))
            }
            writer.finish()
        }
        FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val reader = OcrawStreamReader(channel)
            val index = reader.indexFrames()
            assertEquals(3, index.frames.size)
            assertEquals(offsets, index.audio.map { it.fileOffset })
            assertEquals(listOf(0L, 100_000_000L, 200_000_000L), index.audio.map { it.timestampTicks })
            assertEquals(List(3) { 9_600 }, index.audio.map { it.payloadLength })
            assertFalse(index.tornTail)
            assertEquals("48000", index.metadata["audioSampleRate"])
            val dst = ByteBuffer.allocate(10_000)
            dst.position(7)
            assertEquals(9_600, reader.readAudioChunk(index.audio[1].fileOffset, dst))
            assertEquals(7 + 9_600, dst.position())
            assertArrayEquals(pcm[1], dst.array().copyOfRange(7, 7 + 9_600))
            assertThrows(IllegalArgumentException::class.java) { reader.readAudioChunk(index.frames[0].fileOffset, ByteBuffer.allocate(1 shl 16)) }
            assertThrows(IllegalArgumentException::class.java) { reader.readAudioChunk(index.audio[0].fileOffset, ByteBuffer.allocate(10)) }
            // Flip one payload byte: the read fails its CRC instead of returning noise.
            val corrupt = index.audio[2].fileOffset + OcrawCodec.CHUNK_HEADER_BYTES + 50
            val one = ByteBuffer.allocate(1)
            channel.read(one, corrupt); one.flip()
            val flipped = ByteBuffer.wrap(byteArrayOf((one.get(0).toInt() xor 0x5a).toByte()))
            channel.write(flipped, corrupt)
            assertThrows(IOException::class.java) { reader.readAudioChunk(index.audio[2].fileOffset, ByteBuffer.allocate(10_000)) }
        }
    }

    private fun frame(index: Int): Pair<OcrawFrameHeader, ByteArray> {
        val header = OcrawFrameHeader.raw10(width, height, 8, 4, 1_000_000_000L + index * frameInterval, 100L + index)
        return header to Random(index).nextBytes(header.dataLength)
    }

    private fun writeTake(
        file: File,
        frames: List<Pair<OcrawFrameHeader, ByteArray>>,
        frameMetadata: Boolean = true,
        options: OcrawStreamWriter.Options = OcrawStreamWriter.Options(),
        interval: Long = frameInterval,
    ): OcrawStreamStats = FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
        val writer = OcrawStreamWriter(channel, OcrawHeader(uuid), nanos, options)
        writer.writeMetadata(staticMetadata)
        frames.forEachIndexed { index, (header, data) ->
            val sequence = writer.writeRawFrame(header, ByteBuffer.wrap(data), index * interval)
            if (frameMetadata) {
                writer.writeFrameMetadata(sequence, mapOf("exposureNs" to "${10_000_000 + index}", "iso" to "400"))
            }
        }
        writer.finish()
    }
}
