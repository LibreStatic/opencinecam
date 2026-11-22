/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.media.ocraw

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

/** `IMU_SAMPLES` chunks interleaved with RAW frames and audio, as a RAW take with motion data writes them. */
class OcrawImuChunkTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/ocraw-imu-test").apply { mkdirs() })

    private val nanos = OcrawTimeBase(1, 1_000_000_000)
    private val width = 32
    private val height = 4

    private fun frameTs(index: Int) = 2_000_000_000L + index * 33_333_333L
    private fun imuTs(index: Int) = 1_995_000_000L + index * 50_000_000L

    /** Four records per batch: gyro, accel, magnetometer, gyro, 5 ms apart from [first]. */
    private fun batch(index: Int): OcrawImuSamplesData {
        val first = imuTs(index)
        val kinds = byteArrayOf(1, 2, 3, 1)
        val times = LongArray(4) { first + it * 5_000_000L }
        val xyz = FloatArray(12) { index * 100f + it * 0.25f - 1f }
        return OcrawImuSamplesData(4, kinds, times, xyz)
    }

    private fun payload(data: OcrawImuSamplesData): ByteBuffer =
        OcrawImuSamples.encode(data.kinds, data.timesNs, data.xyz, data.count)

    /** Writes [frames] frames with an IMU batch after every second frame and an audio chunk after every third. */
    private fun writeTake(file: File, frames: Int, finish: Boolean = true): Int {
        var imu = 0
        RandomAccessFile(file, "rw").use { raf ->
            val writer = OcrawStreamWriter(raf.channel, OcrawHeader(ByteArray(16)), nanos, OcrawStreamWriter.Options(forceOnFinish = false), force = null)
            writer.writeMetadata(
                mapOf("cfa" to "RGGB", OcrawImuMetadata.ORIENTATION to "yxz", OcrawImuMetadata.READOUT_DIRECTION to "0"),
                frameTs(0),
            )
            writer.writeImuSamples(imuTs(imu), payload(batch(imu++)))
            for (index in 0 until frames) {
                val header = OcrawFrameHeader.raw10(width, height, 0, 0, frameTs(index), index.toLong())
                val data = ByteBuffer.allocateDirect(header.dataLength)
                while (data.hasRemaining()) data.put(index.toByte())
                data.flip()
                writer.writeRawFrame(header, data, frameTs(index))
                if (index % 2 == 1) writer.writeImuSamples(imuTs(imu), payload(batch(imu++)))
                if (index % 3 == 2) writer.writeChunk(OcrawChunkId.AUDIO_PCM16.wireId, frameTs(index), ByteBuffer.wrap(ByteArray(64)))
            }
            writer.writeMetadata(mapOf(OcrawImuMetadata.CLOCK to OcrawImuMetadata.CLOCK_SHARED))
            if (finish) writer.finish() else writer.abort()
        }
        return imu
    }

    private fun assertSameRecords(expected: OcrawImuSamplesData, actual: OcrawImuSamplesData?) {
        assertNotNull(actual)
        assertEquals(expected.count, actual!!.count)
        assertArrayEquals(expected.kinds.copyOf(expected.count), actual.kinds.copyOf(actual.count))
        assertArrayEquals(expected.timesNs.copyOf(expected.count), actual.timesNs.copyOf(actual.count))
        assertArrayEquals(expected.xyz.copyOf(expected.count * 3), actual.xyz.copyOf(actual.count * 3), 0f)
    }

    @Test
    fun payloadRoundTripsAndHasTheDocumentedLayout() {
        val data = batch(3)
        val encoded = payload(data)
        assertEquals(OcrawImuSamples.HEADER_BYTES + 4 * OcrawImuSamples.RECORD_BYTES, encoded.remaining())
        val le = encoded.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, le.get(0).toInt())
        assertEquals(0, le.get(1).toInt())
        assertEquals(0, le.getShort(2).toInt())
        assertEquals(4, le.getInt(4))
        assertEquals(1, le.get(8).toInt())
        assertEquals(data.timesNs[0], le.getLong(9))
        assertEquals(data.xyz[0], le.getFloat(17), 0f)
        assertEquals(data.xyz[2], le.getFloat(25), 0f)
        assertEquals(2, le.get(29).toInt())
        assertSameRecords(data, OcrawImuSamples.decode(encoded))
        // decode does not consume the buffer.
        assertEquals(0, encoded.position())

        // A sub-range encodes into a reused buffer at its position.
        val reused = ByteBuffer.allocate(100).order(ByteOrder.BIG_ENDIAN)
        reused.position(3)
        val written = OcrawImuSamples.encodeInto(reused, data.kinds, data.timesNs, data.xyz, count = 2, from = 1)
        assertEquals(OcrawImuSamples.HEADER_BYTES + 2 * OcrawImuSamples.RECORD_BYTES, written)
        assertEquals(3 + written, reused.position())
        val slice = ByteBuffer.wrap(reused.array(), 3, written)
        val decoded = OcrawImuSamples.decode(slice)!!
        assertEquals(2, decoded.count)
        assertEquals(data.timesNs[1], decoded.timesNs[0])
        assertEquals(OcrawImuSamples.KIND_MAGNETOMETER, decoded.kind(1))
        assertEquals(data.xyz[6], decoded.xyz[3], 0f)
        assertEquals(1, decoded.countOf(OcrawImuSamples.KIND_ACCELEROMETER))
    }

    @Test
    fun unknownKindsAreSkippedAndUnknownVersionsOrMalformedPayloadsIgnored() {
        val encoded = OcrawImuSamples.encode(byteArrayOf(1, 9, 3), longArrayOf(10, 20, 30), FloatArray(9) { it.toFloat() })
        val decoded = OcrawImuSamples.decode(encoded)!!
        assertEquals(2, decoded.count)
        assertArrayEquals(longArrayOf(10, 30), decoded.timesNs.copyOf(2))
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 6f, 7f, 8f), decoded.xyz.copyOf(6), 0f)

        val bytes = ByteArray(encoded.remaining()).also { encoded.duplicate().get(it) }
        assertNull(OcrawImuSamples.decode(bytes.copyOf().also { it[0] = 2 }))
        assertNull(OcrawImuSamples.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(OcrawImuSamples.decode(bytes + 0.toByte()))
        assertNull(OcrawImuSamples.decode(ByteArray(5)))
        assertEquals(0, OcrawImuSamples.decode(OcrawImuSamples.encode(ByteArray(0), LongArray(0), FloatArray(0)))!!.count)
    }

    @Test
    fun indexListsImuChunksBetweenFramesAndAudioAndReadsThemChecked() {
        val file = temp.newFile("imu.ocraw")
        val batches = writeTake(file, frames = 10)
        val chunks = OcrawCodec.scanContainer(file.readBytes()).chunks.filter { it.wireId == OcrawChunkId.IMU_SAMPLES.wireId }
        assertEquals(batches, chunks.size)
        assertSameRecords(batch(1), OcrawImuSamples.decode(chunks[1].payload))
        RandomAccessFile(file, "rw").use { raf ->
            val reader = OcrawStreamReader(raf.channel)
            val index = reader.indexFrames()
            assertFalse(index.tornTail)
            assertTrue(index.hasEnd)
            assertTrue(index.errors.toString(), index.errors.isEmpty())
            assertEquals((0 until 10).map(::frameTs), index.frames.map { it.timestampTicks })
            assertEquals(3, index.audio.size)
            assertEquals(batches, index.imu.size)
            assertEquals((0 until batches).map(::imuTs), index.imu.map { it.timestampTicks })
            assertEquals(List(batches) { OcrawImuSamples.payloadBytes(4) }, index.imu.map { it.payloadLength })
            assertEquals("yxz", index.metadata[OcrawImuMetadata.ORIENTATION])
            assertEquals("shared", index.metadata[OcrawImuMetadata.CLOCK])
            // Frames are still read from their indexed offsets.
            val dst = ByteBuffer.allocate(index.frames[7].header!!.dataLength)
            reader.readRawFrame(index.frames[7].fileOffset, dst)
            assertTrue(dst.array().all { it == 7.toByte() })

            assertSameRecords(batch(2), reader.readImu(index.imu[2].fileOffset))
            val raw = ByteBuffer.allocate(200)
            raw.position(5)
            assertEquals(OcrawImuSamples.payloadBytes(4), reader.readImuChunk(index.imu[1].fileOffset, raw))
            assertEquals(5 + OcrawImuSamples.payloadBytes(4), raw.position())
            assertThrows(IllegalArgumentException::class.java) { reader.readImuChunk(index.frames[0].fileOffset, ByteBuffer.allocate(1 shl 16)) }
            assertThrows(IllegalArgumentException::class.java) { reader.readImuChunk(index.audio[0].fileOffset, ByteBuffer.allocate(1 shl 16)) }
            assertThrows(IllegalArgumentException::class.java) { reader.readAudioChunk(index.imu[0].fileOffset, ByteBuffer.allocate(1 shl 16)) }
            assertThrows(IllegalArgumentException::class.java) { reader.readImuChunk(index.imu[0].fileOffset, ByteBuffer.allocate(10)) }

            val scan = reader.scan()
            assertTrue(scan.errors.toString(), scan.cleanlyClosed)
            assertEquals(10, scan.frameCount)
            assertTrue(scan.unknownChunkIds.isEmpty())

            // Flip one payload byte: the read fails its CRC instead of returning noise.
            val corrupt = index.imu[3].fileOffset + OcrawCodec.CHUNK_HEADER_BYTES + 20
            val one = ByteBuffer.allocate(1)
            raf.channel.read(one, corrupt)
            raf.channel.write(ByteBuffer.wrap(byteArrayOf((one.get(0).toInt() xor 0x5a).toByte())), corrupt)
            assertThrows(IOException::class.java) { reader.readImu(index.imu[3].fileOffset) }
        }
    }

    @Test
    fun aReaderThatSkipsUnknownIdsStillIndexesTheFrames() {
        // What a reader from before IMU_SAMPLES sees: an id it does not know, between the frames.
        val file = temp.newFile("forward.ocraw")
        RandomAccessFile(file, "rw").use { raf ->
            val writer = OcrawStreamWriter(raf.channel, OcrawHeader(ByteArray(16)), nanos, OcrawStreamWriter.Options(forceOnFinish = false), force = null)
            writer.writeMetadata(mapOf("cfa" to "RGGB"), frameTs(0))
            for (index in 0 until 4) {
                val header = OcrawFrameHeader.raw10(width, height, 0, 0, frameTs(index), index.toLong())
                writer.writeRawFrame(header, ByteBuffer.allocate(header.dataLength), frameTs(index))
                writer.writeImuSamples(frameTs(index), payload(batch(index)))
                writer.writeChunk(0x0051, frameTs(index), ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
            }
            writer.finish()
        }
        RandomAccessFile(file, "r").use { raf ->
            val reader = OcrawStreamReader(raf.channel)
            val index = reader.indexFrames()
            assertEquals(4, index.frames.size)
            assertEquals(4, index.imu.size)
            assertTrue(index.hasEnd)
            val scan = reader.scan()
            assertTrue(scan.cleanlyClosed)
            assertEquals(setOf(0x0051), scan.unknownChunkIds)
        }
        val verification = OcrawDesktopReader(file.readBytes()).verify()
        assertTrue(verification.errors.toString(), verification.valid)
    }

    @Test
    fun aTornTakeWithImuKeepsItsChunksThroughRecovery() {
        val file = temp.newFile("torn.ocraw")
        val batches = writeTake(file, frames = 6, finish = false)
        // Cut into the last chunk (the closing metadata).
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 3) }
        RandomAccessFile(file, "r").use { raf ->
            val index = OcrawStreamReader(raf.channel).indexFrames()
            assertTrue(index.tornTail)
            assertEquals(6, index.frames.size)
            assertEquals(batches, index.imu.size)
        }
        RandomAccessFile(file, "rw").use { raf ->
            val recovered = OcrawStreamReader.recover(raf.channel)
            assertEquals(OcrawRecoveryAction.FINALIZED, recovered.action)
            assertEquals(6, recovered.scan.frameCount)
        }
        RandomAccessFile(file, "r").use { raf ->
            val reader = OcrawStreamReader(raf.channel)
            val index = reader.indexFrames()
            assertFalse(index.tornTail)
            assertTrue(index.hasEnd)
            assertEquals(batches, index.imu.size)
            assertSameRecords(batch(batches - 1), reader.readImu(index.imu.last().fileOffset))
            // The appended final index lists the IMU chunks like any other.
            val finalIndex = reader.scan().finalIndex!!
            assertTrue(index.imu.all { chunk -> finalIndex.any { it.fileOffset == chunk.fileOffset } })
        }
        val verification = OcrawDesktopReader(file.readBytes()).verify()
        assertTrue(verification.errors.toString(), verification.valid)
    }

    @Test
    fun desktopReaderExtractsEveryReadableRecord() {
        val good = batch(0)
        val future = payload(batch(1)).let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }.also { it[0] = 2 }
        val chunks = listOf(
            OcrawChunk(OcrawChunkId.METADATA.wireId, 0, 0, nanos, OcrawMetadataCbor.encode(mapOf("cfa" to "RGGB"))),
            OcrawChunk(OcrawChunkId.IMU_SAMPLES.wireId, 1, imuTs(0), nanos, payload(good).let { b -> ByteArray(b.remaining()).also(b::get) }),
            OcrawChunk(OcrawChunkId.IMU_SAMPLES.wireId, 2, imuTs(1), nanos, future),
            OcrawChunk(OcrawChunkId.IMU_SAMPLES.wireId, 3, imuTs(2), nanos, payload(batch(2)).let { b -> ByteArray(b.remaining()).also(b::get) }),
        )
        val extracted = OcrawDesktopReader(OcrawCodec.encodeContainer(OcrawHeader(ByteArray(16)), chunks)).extractImu()
        assertEquals(8, extracted.count)
        assertArrayEquals(good.timesNs + batch(2).timesNs, extracted.timesNs)
        assertTrue(OcrawImuMetadata.isValidOrientation("yxZ"))
        assertFalse(OcrawImuMetadata.isValidOrientation("xxz"))
        assertFalse(OcrawImuMetadata.isValidOrientation(null))
    }
}
