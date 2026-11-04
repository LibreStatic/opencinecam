// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import com.librestatic.opencinecam.media.raw.RawQuantisation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [CinemaDngWriter] on quantised block-pack frames (encoding 3), without the native library. */
class CinemaDngQuantisedTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/cinemadng-test").apply { mkdirs() })

    private val payload = File("../tools/tests/fixtures/ocraw_codecs/blockpackq_64x34_s8_seed1.bin").readBytes()
    private val inverse = checkNotNull(RawQuantisation.readPrefix(ByteBuffer.wrap(payload))).inverse

    /** Writes [codes] as the decoded block-pack container and records what it was given. */
    private inner class CodesDecoder(private val codes: ByteArray) : CinemaDngWriter.BlockPackDecoder {
        var container: ByteArray? = null
        var closed = false

        override fun decode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Boolean {
            container = ByteArray(src.remaining()).also { src.duplicate().get(it) }
            dst.duplicate().put(codes)
            return true
        }

        override fun close() { closed = true }
    }

    @Test
    fun quantisedFramesDecodeToTheReconstructedDn() = withTake(payload) { channel, index ->
        assertEquals(OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED, index.frames[0].header?.encoding)
        val codes = IntArray(WIDTH * HEIGHT) { (it * 7) % inverse.size }
        val decoder = CodesDecoder(packRaw10(codes))
        CinemaDngWriter(channel, index, "Q", blockPackDecoderFactory = { decoder }).use { writer ->
            assertTrue(writer.isConvertible(0))
            assertTrue(writer.isSizeKnown(0))
            val out = ByteArrayOutputStream()
            assertEquals(writer.sizeOf(0), writer.write(0, out))
            // The decoder saw only the block-pack container, after the preamble and table.
            assertArrayEquals(payload.copyOfRange(RawQuantisation.prefixBytes(inverse.size), payload.size), decoder.container)
            val bytes = out.toByteArray()
            val samples = ByteBuffer.wrap(bytes, bytes.size - WIDTH * HEIGHT * 2, WIDTH * HEIGHT * 2).order(ByteOrder.LITTLE_ENDIAN)
            val expected = IntArray(codes.size) { inverse[codes[it]] }
            assertArrayEquals(expected, IntArray(codes.size) { samples.getShort().toInt() and 0xffff })
            // A range is cut from the generated file.
            val part = ByteArrayOutputStream()
            writer.writeRange(0, 8, 40, part)
            assertArrayEquals(bytes.copyOfRange(8, 48), part.toByteArray())
        }
        assertTrue(decoder.closed)
    }

    @Test
    fun aDecoderWithItsOwnQuantisedPathIsUsed() = withTake(payload) { channel, index ->
        val dn = ByteArray(WIDTH / 4 * 5 * HEIGHT) { (it * 3).toByte() }
        var whole: ByteArray? = null
        val decoder = object : CinemaDngWriter.BlockPackDecoder {
            override fun decode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Boolean = error("not the plain path")

            override fun decodeQuantised(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Boolean {
                whole = ByteArray(src.remaining()).also { src.duplicate().get(it) }
                dst.duplicate().put(dn)
                return true
            }

            override fun close() = Unit
        }
        CinemaDngWriter(channel, index, "Q", blockPackDecoderFactory = { decoder }).use { writer ->
            writer.write(0, ByteArrayOutputStream())
            assertArrayEquals(payload, whole)
        }
    }

    @Test
    fun codesPastTheTableOrABadPrefixFail() {
        val tooBig = IntArray(WIDTH * HEIGHT) { if (it == 77) inverse.size else 0 }
        withTake(payload) { channel, index ->
            CinemaDngWriter(channel, index, "Q", blockPackDecoderFactory = { CodesDecoder(packRaw10(tooBig)) }).use { writer ->
                assertDecodeFailed { writer.write(0, ByteArrayOutputStream()) }
            }
        }
        val badVersion = payload.copyOf().also { it[0] = 2 }
        withTake(badVersion) { channel, index ->
            val decoder = CodesDecoder(packRaw10(IntArray(WIDTH * HEIGHT)))
            CinemaDngWriter(channel, index, "Q", blockPackDecoderFactory = { decoder }).use { writer ->
                assertDecodeFailed { writer.write(0, ByteArrayOutputStream()) }
            }
            assertEquals(null, decoder.container)
        }
    }

    private fun assertDecodeFailed(block: () -> Unit) {
        try {
            block()
            fail("expected DECODE_FAILED")
        } catch (error: OcrawDngException) {
            assertEquals(OcrawMissingReason.DECODE_FAILED, error.reason)
        }
    }

    private fun withTake(data: ByteArray, block: (FileChannel, OcrawFrameIndex) -> Unit) {
        val file = temp.newFile()
        RandomAccessFile(file, "rw").use { raf ->
            val writer = OcrawStreamWriter(raf.channel, OcrawHeader(ByteArray(16) { it.toByte() }), NANOS,
                OcrawStreamWriter.Options(forceOnFinish = false), force = null)
            writer.writeMetadata(CinemaDngGoldenTest.staticMetadata(), TS)
            val header = OcrawFrameHeader(WIDTH, HEIGHT, WIDTH / 4 * 5, 8, 4, TS, 0, data.size,
                OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED)
            writer.writeRawFrame(header, ByteBuffer.wrap(data), TS)
            writer.finish()
        }
        RandomAccessFile(file, "r").use { raf ->
            val index = OcrawStreamReader(raf.channel).indexFrames()
            assertFalse(index.tornTail)
            block(raf.channel, index)
        }
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 34
        const val TS = 5_000_000_000L
        val NANOS = OcrawTimeBase(1, 1_000_000_000)

        fun packRaw10(samples: IntArray): ByteArray {
            val out = ByteArray(samples.size / 4 * 5)
            for (g in 0 until samples.size / 4) {
                var low = 0
                for (k in 0 until 4) {
                    out[g * 5 + k] = (samples[g * 4 + k] shr 2).toByte()
                    low = low or ((samples[g * 4 + k] and 3) shl (2 * k))
                }
                out[g * 5 + 4] = low.toByte()
            }
            return out
        }
    }
}
