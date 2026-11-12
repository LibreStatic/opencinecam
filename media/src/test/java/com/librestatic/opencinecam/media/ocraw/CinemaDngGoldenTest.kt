// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import com.librestatic.opencinecam.media.raw.NativeRawCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * OCC-PLAN-071 §8: [CinemaDngWriter] and [OcrawWavWriter] write the same bytes as
 * `desktop-tools/ocraw_to_dng.py --cinemadng`. The fixture take
 * `src/test/resources/ocraw/cinemadng/A001_C001.ocraw` is built by [buildTake] and the
 * expected files beside it by the Python converter; `tools/regen-cinemadng-golden.sh`
 * regenerates both.
 */
class CinemaDngGoldenTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/cinemadng-test").apply { mkdirs() })

    private val goldenDir = File("src/test/resources/ocraw/cinemadng")
    private val take = File(goldenDir, "$TAKE.ocraw")
    private val expected = File(goldenDir, TAKE)

    @Test
    fun builderReproducesTheCheckedInTake() {
        val built = File("build/cinemadng-golden/$TAKE.ocraw")
        built.parentFile?.mkdirs()
        built.delete()
        buildTake(built)
        assertTrue("missing ${take.path}: run tools/regen-cinemadng-golden.sh", take.isFile)
        assertArrayEquals("the fixture builder changed: run tools/regen-cinemadng-golden.sh", take.readBytes(), built.readBytes())
    }

    @Test
    fun goldenFolderHoldsTheExpectedFiles() {
        // Frame 5 has an unknown encoding, so the converter leaves a gap in the numbering.
        val names = (listOf(0, 1, 2, 3, 4, 6).map { CinemaDngWriter.fileName(TAKE, it) } + "$TAKE.wav").sorted()
        assertEquals(names, expected.list()!!.sorted())
        assertEquals("A001_C001_000005.dng", CinemaDngWriter.fileName(TAKE, 5))
        assertEquals("A001_C001_1234567.dng", CinemaDngWriter.fileName(TAKE, 1_234_567))
    }

    @Test
    fun dngsMatchTheConverterByteForByte() = withTake { channel, index ->
        val writer = CinemaDngWriter(channel, index, TAKE)
        assertEquals(7, writer.frameCount)
        assertTrue(writer.hasFrameRate)
        for (frame in 0 until writer.frameCount) {
            val golden = File(expected, writer.fileName(frame))
            val encoding = index.frames[frame].header!!.encoding
            if (encoding == 9) {
                assertFalse(golden.exists())
                assertFalse(writer.isConvertible(frame))
                assertReason(OcrawMissingReason.UNSUPPORTED_ENCODING) { writer.sizeOf(frame) }
                continue
            }
            if (encoding == OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK && !NativeRawCodec.available) {
                assertTrue(golden.exists())
                assertFalse(writer.isConvertible(frame))
                assertReason(OcrawMissingReason.CODEC_UNAVAILABLE) { writer.write(frame, ByteArrayOutputStream()) }
                continue
            }
            val bytes = golden.readBytes()
            assertTrue(writer.isConvertible(frame))
            assertTrue(writer.isSizeKnown(frame))
            val layout = writer.layout(frame)
            assertEquals("frame $frame size", bytes.size.toLong(), writer.sizeOf(frame))
            assertEquals(bytes.size.toLong(), layout.size)
            assertArrayEquals("frame $frame header", bytes.copyOf(layout.headerSize), layout.header())
            assertEquals(if (encoding == OcrawFrameHeader.ENCODING_LJ92) 7 else 1, layout.compression)
            val out = ByteArrayOutputStream()
            assertEquals(bytes.size.toLong(), writer.write(frame, out))
            assertArrayEquals("frame $frame bytes", bytes, out.toByteArray())
        }
    }

    @Test
    fun rangesMatchSlicesOfTheConverterFiles() = withTake { channel, index ->
        val writer = CinemaDngWriter(channel, index, TAKE)
        val random = Random(71)
        for (frame in listOf(0, 1, 2, 4, 6)) {
            val bytes = File(expected, writer.fileName(frame)).readBytes()
            val size = bytes.size.toLong()
            val header = writer.layout(frame).headerSize.toLong()
            val ranges = mutableListOf(0L to 1L, 0L to header, header - 3 to 10L, header to 1L, header + 1 to 7L,
                header + 9 to minOf(4_000L, size - header - 9), size - 1 to 1L, size - 13 to 13L, 0L to size, 5L to size - 5, 7L to 0L)
            repeat(40) {
                val start = (random.nextDouble() * size).toLong()
                ranges += start to (random.nextDouble() * (size - start)).toLong()
            }
            for ((offset, length) in ranges) {
                val out = ByteArrayOutputStream()
                assertEquals(length, writer.writeRange(frame, offset, length, out))
                assertArrayEquals("frame $frame range $offset+$length", bytes.copyOfRange(offset.toInt(), (offset + length).toInt()),
                    out.toByteArray())
            }
            try {
                writer.writeRange(frame, size - 1, 2, ByteArrayOutputStream())
                fail("a range past the end must throw")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun wavMatchesTheConverterAndServesRanges() = withTake { channel, index ->
        val bytes = File(expected, "$TAKE.wav").readBytes()
        assertTrue(OcrawWavWriter.hasAudio(index))
        val wav = OcrawWavWriter(channel, index)
        assertEquals(bytes.size.toLong(), wav.size)
        assertEquals(8000, wav.sampleRate)
        assertEquals(1, wav.channels)
        assertEquals(3040L, wav.sampleFrames)
        assertEquals(160L, wav.paddedFrames)
        assertEquals(320L, wav.trimmedFrames)
        assertEquals(1, wav.skippedChunks)
        val out = ByteArrayOutputStream()
        assertEquals(wav.size, wav.write(out))
        assertArrayEquals(bytes, out.toByteArray())
        val random = Random(7)
        val ranges = mutableListOf(0L to 44L, 40L to 8L, 44L to 1_280L, 1_323L to 2L, wav.size - 1 to 1L)
        repeat(40) {
            val start = (random.nextDouble() * wav.size).toLong()
            ranges += start to (random.nextDouble() * (wav.size - start)).toLong()
        }
        for ((offset, length) in ranges) {
            val part = ByteArrayOutputStream()
            assertEquals(length, wav.writeRange(offset, length, part))
            assertArrayEquals("range $offset+$length", bytes.copyOfRange(offset.toInt(), (offset + length).toInt()), part.toByteArray())
        }
    }

    @Test
    fun wavFromTheFirstAudioSample() = withTake { channel, index ->
        val wav = OcrawWavWriter(channel, index, alignToVideo = false)
        // The first chunk now starts at sample zero, so nothing is trimmed before it: 160 more frames.
        assertEquals(3200L, wav.sampleFrames)
        val out = ByteArrayOutputStream()
        wav.write(out)
        assertEquals(wav.size, out.size().toLong())
    }

    @Test
    fun corruptAudioThrowsOrBecomesSilence() {
        val file = temp.newFile("corrupt.ocraw")
        take.copyTo(file, overwrite = true)
        val chunk = RandomAccessFile(take, "r").use { OcrawStreamReader(it.channel).indexFrames().audio.first() }
        RandomAccessFile(file, "rw").use { raf ->
            val at = chunk.fileOffset + OcrawCodec.CHUNK_HEADER_BYTES + 400
            raf.seek(at)
            val value = raf.read()
            raf.seek(at)
            raf.write(value xor 0xff)
        }
        RandomAccessFile(file, "r").use { raf ->
            val index = OcrawStreamReader(raf.channel).indexFrames()
            try {
                OcrawWavWriter(raf.channel, index).write(ByteArrayOutputStream())
                fail("a corrupt chunk must throw")
            } catch (_: IOException) {
            }
            val wav = OcrawWavWriter(raf.channel, index, corruptChunkAsSilence = true)
            val out = ByteArrayOutputStream()
            wav.write(out)
            val golden = File(expected, "$TAKE.wav").readBytes()
            assertEquals(golden.size, out.size())
            // The first chunk (640 kept frames) is silence; the rest is untouched.
            val written = out.toByteArray()
            assertTrue((44 until 44 + 1280).all { written[it] == 0.toByte() })
            assertArrayEquals(golden.copyOfRange(44 + 1280, golden.size), written.copyOfRange(44 + 1280, written.size))
        }
    }

    @Test
    fun corruptFrameFailsTheWholeFileButNotARange() {
        val file = temp.newFile("corrupt-frame.ocraw")
        take.copyTo(file, overwrite = true)
        val frame = RandomAccessFile(take, "r").use { OcrawStreamReader(it.channel).indexFrames().frames[0] }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(frame.fileOffset + OcrawCodec.CHUNK_HEADER_BYTES + OcrawFrameHeader.BYTES + 100)
            raf.write(0x55)
        }
        RandomAccessFile(file, "r").use { raf ->
            val writer = CinemaDngWriter(raf.channel, OcrawStreamReader(raf.channel).indexFrames(), TAKE)
            assertReason(OcrawMissingReason.CORRUPT) { writer.write(0, ByteArrayOutputStream()) }
            val out = ByteArrayOutputStream()
            writer.writeRange(0, 0, 100, out)
            assertEquals(100, out.size())
        }
    }

    @Test
    fun takeWithoutSoundHasNoWav() {
        val file = temp.newFile("silent.ocraw")
        RandomAccessFile(file, "rw").use { raf ->
            val writer = OcrawStreamWriter(raf.channel, OcrawHeader(ByteArray(16)), NANOS,
                OcrawStreamWriter.Options(forceOnFinish = false), force = null)
            writer.writeMetadata(staticMetadata() - "audioSampleRate" - "audioChannels", frameTs(0))
            writeRaw10(writer, 0)
            writer.finish()
        }
        RandomAccessFile(file, "r").use { raf ->
            val index = OcrawStreamReader(raf.channel).indexFrames()
            assertFalse(OcrawWavWriter.hasAudio(index))
            try {
                OcrawWavWriter(raf.channel, index)
                fail("a silent take has no WAV")
            } catch (_: IllegalArgumentException) {
            }
            val writer = CinemaDngWriter(raf.channel, index, "silent")
            assertTrue(writer.sizeOf(0) > 64 * 34 * 2)
        }
    }

    @Test
    fun reencodedFramesHaveNoSizeUntilWritten() = withTake { channel, index ->
        val container = File(CODEC_FIXTURES, "lj92_64x34_s8_seed1.bin").readBytes()
        val encoder = object : CinemaDngWriter.Lj92Encoder {
            var fits = true
            override fun maxEncodedSize(width: Int, height: Int) = 64L * 1024
            override fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int {
                assertEquals(64 / 4 * 5 * 34, src.remaining())
                if (!fits) return 0
                dst.put(container)
                return container.size
            }
        }
        val writer = CinemaDngWriter(channel, index, TAKE, lj92Encoder = encoder)
        assertFalse(writer.isSizeKnown(0))
        assertTrue(writer.isSizeKnown(1))
        try {
            writer.sizeOf(0)
            fail("a re-encoded frame has no size up front")
        } catch (_: IllegalStateException) {
        }
        val out = ByteArrayOutputStream()
        val size = writer.write(0, out)
        val bytes = out.toByteArray()
        assertEquals(size, bytes.size.toLong())
        // Same container as frame 1, so the same tiles; only the per-frame tags differ.
        val passThrough = File(expected, writer.fileName(1)).readBytes()
        val tiles = container.size - (4 + 8 * 3)
        assertArrayEquals(passThrough.copyOfRange(passThrough.size - tiles, passThrough.size), bytes.copyOfRange(bytes.size - tiles, bytes.size))
        val tags = readIfd0(bytes)
        assertEquals(7, tags.getValue(259))
        assertEquals(10, tags.getValue(258))
        // A range of a re-encoded frame is cut from the generated file.
        val part = ByteArrayOutputStream()
        writer.writeRange(0, 10, 20, part)
        assertArrayEquals(bytes.copyOfRange(10, 30), part.toByteArray())
        // A frame the encoder cannot fit is stored uncompressed, as the converter would.
        encoder.fits = false
        val fallback = ByteArrayOutputStream()
        writer.write(0, fallback)
        assertArrayEquals(File(expected, writer.fileName(0)).readBytes(), fallback.toByteArray())
    }

    /** A block-pack decoder that writes a known RAW10 pattern, so host tests need no native library. */
    private class FakeBlockPackDecoder : CinemaDngWriter.BlockPackDecoder {
        var decoded = 0
        var closed = false
        lateinit var output: ByteArray

        override fun decode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Boolean {
            decoded++
            output = ByteArray(width / 4 * 5 * height) { (it * 7 + 3).toByte() }
            dst.duplicate().put(output)
            return true
        }

        override fun close() { closed = true }
    }

    @Test
    fun blockPackFramesAreDecodedThenReencodedToLj92() = withTake { channel, index ->
        val frame = 3
        assertEquals(OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK, index.frames[frame].header?.encoding)
        val container = File(CODEC_FIXTURES, "lj92_64x34_s8_seed1.bin").readBytes()
        val decoder = FakeBlockPackDecoder()
        val encoder = object : CinemaDngWriter.Lj92Encoder {
            var fits = true
            var input: ByteArray? = null
            override fun maxEncodedSize(width: Int, height: Int) = 64L * 1024
            override fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int {
                input = ByteArray(src.remaining()).also { src.duplicate().get(it) }
                if (!fits) return 0
                dst.put(container)
                return container.size
            }
        }
        val writer = CinemaDngWriter(channel, index, TAKE, lj92Encoder = encoder, blockPackDecoderFactory = { decoder })
        assertTrue(writer.isConvertible(frame))
        // Re-encoded like a RAW10 frame: no size until written, LJ92 pass-through frames keep theirs.
        assertFalse(writer.isSizeKnown(frame))
        assertTrue(writer.isSizeKnown(1))
        try {
            writer.layout(frame)
            fail("a re-encoded block-pack frame has no size up front")
        } catch (_: IllegalStateException) {
        }
        val out = ByteArrayOutputStream()
        val size = writer.write(frame, out)
        val bytes = out.toByteArray()
        assertEquals(size, bytes.size.toLong())
        // The encoder got the decoded RAW10 rows, not the block-pack payload.
        assertArrayEquals(decoder.output, encoder.input)
        val tags = readIfd0(bytes)
        assertEquals(7, tags.getValue(259))
        assertEquals(10, tags.getValue(258))
        val passThrough = File(expected, writer.fileName(1)).readBytes()
        val tiles = container.size - (4 + 8 * 3)
        assertArrayEquals(passThrough.copyOfRange(passThrough.size - tiles, passThrough.size), bytes.copyOfRange(bytes.size - tiles, bytes.size))
        // A range is cut from the generated file.
        val part = ByteArrayOutputStream()
        writer.writeRange(frame, 10, 20, part)
        assertArrayEquals(bytes.copyOfRange(10, 30), part.toByteArray())
        // A frame the encoder cannot fit is stored uncompressed from the decoded rows.
        encoder.fits = false
        val fallback = ByteArrayOutputStream()
        writer.write(frame, fallback)
        assertEquals(1, readIfd0(fallback.toByteArray()).getValue(259))
        assertEquals(1L * fallback.size(), CinemaDngWriter(channel, index, TAKE, blockPackDecoderFactory = { FakeBlockPackDecoder() }).use { it.sizeOf(frame) })
        writer.close()
        assertTrue(decoder.closed)
    }

    @Test
    fun withoutAnEncoderBlockPackFramesStayUncompressed() = withTake { channel, index ->
        val frame = 3
        val decoder = FakeBlockPackDecoder()
        CinemaDngWriter(channel, index, TAKE, blockPackDecoderFactory = { decoder }).use { writer ->
            assertTrue(writer.isSizeKnown(frame))
            val out = ByteArrayOutputStream()
            val size = writer.write(frame, out)
            assertEquals(writer.sizeOf(frame), size)
            val bytes = out.toByteArray()
            assertEquals(1, readIfd0(bytes).getValue(259))
            // The decoded RAW10 rows, unpacked to 16-bit little-endian samples at the end of the file.
            val samples = ByteBuffer.allocate(64 * 34 * 2).order(ByteOrder.LITTLE_ENDIAN)
            CinemaDngWriter.unpackRaw10(ByteBuffer.wrap(decoder.output), 0, decoder.output.size / 5, samples)
            assertArrayEquals(samples.array(), bytes.copyOfRange(bytes.size - samples.capacity(), bytes.size))
        }
    }

    @Test
    fun smpteTimecodesAndFractionsFollowTheConverter() {
        fun tc(frames: Long, n: Long, d: Long = 1) = CinemaDngWriter.smpteTimecode(frames, DngFraction.of(n, d)).toList()
        fun bytes(vararg values: Int) = values.map { it.toByte() } + List(8 - values.size) { 0.toByte() }
        assertEquals(bytes(0x04, 0x03, 0x02, 0x01), tc(((1L * 60 + 2) * 60 + 3) * 24 + 4, 24))
        assertEquals(bytes(0x23, 0x59, 0x59, 0x23), tc(24L * 3600 * 24 - 1, 24))
        assertEquals(bytes(), tc(24L * 3600 * 24, 24))
        assertEquals(bytes(0x00, 0x01), tc(30, 30000, 1001))
        assertEquals(bytes(0x29, 0x80), tc(59, 60))
        assertEquals(bytes(0x24, 0x00, 0x00, 0x80), tc(49, 50))
        val frames = CinemaDngWriter.Companion::timecodeFrames
        assertEquals(2L, frames(5_062_500_000, 5_000_000_000, DngFraction.of(24)))
        assertEquals(0L, frames(4_000_000_000, 5_000_000_000, DngFraction.of(24)))
        // Python: round(Fraction(-5, 2)) == -2, round(Fraction(7, 2)) == 4.
        assertEquals(BigInteger.valueOf(-2), DngFraction.of(-5, 2).roundHalfEven())
        assertEquals(BigInteger.valueOf(4), DngFraction.of(7, 2).roundHalfEven())
        // limit_denominator; the golden take's forwardMatrix2 exercises it at the SRATIONAL limit.
        assertEquals(DngFraction.of(1, 3), DngFraction.parse("0.333333333333333333333").limitDenominator(BigInteger.valueOf(1000)))
        assertEquals(DngFraction.of(7, 10), DngFraction.parse("0.7"))
        assertEquals(DngFraction.of(1), DngFraction.parse("1024/1024"))
        assertEquals(DngFraction.of(-1, 4), DngFraction.parse("-256/1024"))
        assertEquals(DngFraction.of(1500), DngFraction.parse("1.5e3"))
        assertEquals(null, CinemaDngWriter.parseFps("0/1"))
        assertEquals(null, CinemaDngWriter.parseFps("x"))
        assertEquals(DngFraction.of(30000, 1001), CinemaDngWriter.parseFps(" 30000/1001 "))
    }

    @Test
    fun unsupportedCfaIsRejectedUpFront() {
        val file = temp.newFile("mono.ocraw")
        RandomAccessFile(file, "rw").use { raf ->
            val writer = OcrawStreamWriter(raf.channel, OcrawHeader(ByteArray(16)), NANOS,
                OcrawStreamWriter.Options(forceOnFinish = false), force = null)
            writer.writeMetadata(staticMetadata() + ("cfa" to "MONO"), frameTs(0))
            writeRaw10(writer, 0)
            writer.finish()
        }
        RandomAccessFile(file, "r").use { raf ->
            try {
                CinemaDngWriter(raf.channel, OcrawStreamReader(raf.channel).indexFrames(), "mono")
                fail("MONO is not supported, as in the converter")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun withTake(block: (FileChannel, OcrawFrameIndex) -> Unit) {
        RandomAccessFile(take, "r").use { raf ->
            val index = OcrawStreamReader(raf.channel).indexFrames()
            assertFalse(index.tornTail)
            block(raf.channel, index)
        }
    }

    private fun assertReason(reason: OcrawMissingReason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (error: OcrawDngException) {
            assertEquals(reason, error.reason)
        }
    }

    /** IFD 0 tags with a single SHORT or LONG value. */
    private fun readIfd0(bytes: ByteArray): Map<Int, Int> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val offset = buffer.getInt(4)
        val count = buffer.getShort(offset).toInt()
        val tags = HashMap<Int, Int>()
        for (i in 0 until count) {
            val at = offset + 2 + 12 * i
            val type = buffer.getShort(at + 2).toInt()
            if (buffer.getInt(at + 4) != 1) continue
            tags[buffer.getShort(at).toInt() and 0xffff] = if (type == 3) buffer.getShort(at + 8).toInt() and 0xffff else buffer.getInt(at + 8)
        }
        return tags
    }

    companion object {
        const val TAKE = "A001_C001"
        private val NANOS = OcrawTimeBase(1, 1_000_000_000)
        private const val WIDTH = 64
        private const val HEIGHT = 34
        private val CODEC_FIXTURES = File("../tools/tests/fixtures/ocraw_codecs")

        private fun frameTs(index: Int): Long = 5_000_000_000L + index * 41_666_667L + if (index == 6) 25_000_000L else 0L

        fun staticMetadata(): Map<String, String> = mapOf(
            "cfa" to "GRBG",
            "blackLevel" to "64 64 64 64",
            "whiteLevel" to "1023",
            "colorMatrix1" to "1024/1024 -256/1024 0/1024 -128/1024 1152/1024 -64/1024 0/1024 -512/1024 1536/1024",
            "colorMatrix2" to "900/1000 -100/1000 0/1000 -200/1000 1200/1000 0/1000 0/1000 -300/1000 1300/1000",
            "forwardMatrix1" to "0.7 0.2 0.1 0.3 0.6 0.1 0 0.1 0.8",
            "forwardMatrix2" to "3333333333/10000000000 2/10 1/10 3/10 6/10 1/10 0/1 1/10 8/10",
            "calibrationIlluminant1" to "17",
            "calibrationIlluminant2" to "21",
            "sensorWidth" to "4080",
            "sensorHeight" to "3060",
            "activeArray" to "0 0 4080 3060",
            "crop" to "8 4 $WIDTH $HEIGHT",
            "fps" to "24/1",
            "cameraId" to "0",
            "model" to "SM-S928B",
            "appVersion" to "1.0.0",
            "encoding" to "lj92",
            "audioSampleRate" to "8000",
            "audioChannels" to "1",
            "audioSource" to "UNPROCESSED",
            "audioClock" to "boottime",
        )

        private fun writeRaw10(writer: OcrawStreamWriter, index: Int): Long {
            val header = OcrawFrameHeader.raw10(WIDTH, HEIGHT, 8, 4, frameTs(index), index.toLong())
            var state = 0x9E3779B9L xor index.toLong()
            val bytes = ByteArray(header.dataLength) {
                state = (state * 6364136223846793005L + 1442695040888963407L)
                (state ushr 33).toByte()
            }
            return writer.writeRawFrame(header, ByteBuffer.wrap(bytes), frameTs(index))
        }

        private fun writeEncoded(writer: OcrawStreamWriter, index: Int, encoding: Int, fixture: String?, width: Int, height: Int): Long {
            val data = fixture?.let { File(CODEC_FIXTURES, "$it.bin").readBytes() } ?: ByteArray(16) { it.toByte() }
            val header = OcrawFrameHeader(width, height, width / 4 * 5, 8, 4, frameTs(index), index.toLong(), data.size, encoding)
            return writer.writeRawFrame(header, ByteBuffer.wrap(data), frameTs(index))
        }

        private fun writeAudio(writer: OcrawStreamWriter, ticks: Long, samples: Int, first: Int, oddByte: Boolean = false) {
            val buffer = ByteBuffer.allocate(samples * 2 + if (oddByte) 1 else 0).order(ByteOrder.LITTLE_ENDIAN)
            repeat(samples) { buffer.putShort(((first + it) * 37 % 60_000 - 30_000).toShort()) }
            buffer.position(0)
            writer.writeChunk(OcrawChunkId.AUDIO_PCM16.wireId, ticks, buffer)
        }

        /**
         * The golden take: 64x34 frames (RAW10, LJ92 with three strips, block-pack, a 72x26
         * LJ92 frame with one strip, an unknown encoding), late frame metadata, and 8 kHz mono
         * audio that starts before the first frame, overlaps, has a gap, a partial sample frame
         * and arrives out of time order.
         */
        fun buildTake(file: File) {
            RandomAccessFile(file, "rw").use { raf ->
                val writer = OcrawStreamWriter(raf.channel, OcrawHeader(ByteArray(16) { (it * 11).toByte() }), NANOS,
                    OcrawStreamWriter.Options(forceOnFinish = false), force = null)
                writer.writeMetadata(staticMetadata(), frameTs(0))
                writeAudio(writer, 4_980_000_000L, 800, 0)
                val f0 = writeRaw10(writer, 0)
                writeAudio(writer, 5_180_000_000L, 800, 2_000)
                val f1 = writeEncoded(writer, 1, OcrawFrameHeader.ENCODING_LJ92, "lj92_64x34_s8_seed1", WIDTH, HEIGHT)
                writeAudio(writer, 5_100_000_000L, 800, 1_000)
                writer.writeFrameMetadata(f0, mapOf("exposureNs" to "10000000", "iso" to "400",
                    "neutral" to "512/1024 1024/1024 768/1024", "frameNumber" to "9000"))
                writeRaw10(writer, 2)
                writeAudio(writer, 5_280_000_000L, 800, 9_000, oddByte = true)
                writeEncoded(writer, 3, OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK, "blockpack_64x34_s8_seed1", WIDTH, HEIGHT)
                writeAudio(writer, 5_280_000_000L, 800, 3_000)
                val f4 = writeEncoded(writer, 4, OcrawFrameHeader.ENCODING_LJ92, "lj92_72x26_s1_seed3", 72, 26)
                writer.writeFrameMetadata(f1, mapOf("dynamicBlackLevel" to "64.5 64 64 64", "whiteLevel" to "1000",
                    "exposureNs" to "20000000", "iso" to "70000"))
                writeEncoded(writer, 5, 9, null, WIDTH, HEIGHT)
                val f6 = writeRaw10(writer, 6)
                writer.writeFrameMetadata(f4, mapOf("neutral" to "1/2 1/1 3/4"))
                writer.writeFrameMetadata(f6, mapOf("blackLevel" to "60 62 63 66", "exposureNs" to "41666667"))
                writer.writeMetadata(mapOf("audioDroppedSamples" to "0", "audioTimestampBacked" to "true"))
                writer.finish()
            }
        }
    }
}
