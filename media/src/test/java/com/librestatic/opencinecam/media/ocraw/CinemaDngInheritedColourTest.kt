// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A frame without a `FRAME_METADATA` record (the last frame of takes recorded before
 * the engine kept the final capture result) inherits the neutral and levels of its
 * nearest neighbour, through [OcrawFrameIndex.effectiveFrameMetadata], and never its
 * exposure or ISO.
 */
class CinemaDngInheritedColourTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/cinemadng-test").apply { mkdirs() })

    @Test
    fun lastFrameWithoutMetadataTakesThePreviousColour() {
        val dngs = convert(
            mapOf("exposureNs" to "10000000", "iso" to "400", "neutral" to "1/2 1/1 3/4",
                "dynamicBlackLevel" to "64.5 64 64 64", "whiteLevel" to "1000"),
            mapOf("exposureNs" to "20000000", "iso" to "800", "neutral" to "1/3 1/1 2/3",
                "dynamicBlackLevel" to "63 63 65 65", "whiteLevel" to "1010"),
            null,
        )
        val last = dngs[2]
        assertEquals(dngs[1].ifd0[TAG_NEUTRAL], last.ifd0[TAG_NEUTRAL])
        assertEquals(listOf(1L, 3L, 1L, 1L, 2L, 3L), last.ifd0[TAG_NEUTRAL])
        assertEquals(dngs[1].ifd0[TAG_BLACK], last.ifd0[TAG_BLACK])
        assertEquals(listOf(1010L), last.ifd0[TAG_WHITE])
        assertFalse(TAG_EXPOSURE in last.exif)
        assertFalse(TAG_ISO in last.exif)
        assertTrue(TAG_EXPOSURE in dngs[1].exif)
    }

    @Test
    fun firstFrameWithoutMetadataTakesTheNextColour() {
        val dngs = convert(
            null,
            mapOf("exposureNs" to "10000000", "iso" to "400", "neutral" to "1/2 1/1 3/4", "blackLevel" to "60 62 63 66"),
            mapOf("neutral" to "1/3 1/1 2/3"),
        )
        val first = dngs[0]
        assertEquals(listOf(1L, 2L, 1L, 1L, 3L, 4L), first.ifd0[TAG_NEUTRAL])
        assertEquals(dngs[1].ifd0[TAG_BLACK], first.ifd0[TAG_BLACK])
        assertEquals(listOf(1023L), first.ifd0[TAG_WHITE])
        assertFalse(TAG_EXPOSURE in first.exif)
        assertFalse(TAG_ISO in first.exif)
    }

    @Test
    fun takeWithoutAnyFrameMetadataIsUnchanged() {
        val dngs = convert(null, null)
        for (dng in dngs) {
            assertNull(dng.ifd0[TAG_NEUTRAL])
            assertEquals(listOf(1023L), dng.ifd0[TAG_WHITE])
            assertEquals(dngs[0].ifd0[TAG_BLACK], dng.ifd0[TAG_BLACK])
            assertFalse(TAG_EXPOSURE in dng.exif)
        }
    }

    @Test
    fun eachGroupComesFromItsNearestRecordAndRunsShareOneMap() {
        val index = index(
            null,
            mapOf("neutral" to "1/2 1/1 3/4", "iso" to "100"),
            mapOf("dynamicBlackLevel" to "64 64 64 64", "whiteLevel" to "1000", "neutral" to ""),
            null,
            null,
            mapOf("neutral" to "1/3 1/1 2/3", "blackLevel" to "60 60 60 60"),
        )
        val sequences = index.frames.map { it.sequence }
        val inherited = mapOf("neutral" to "1/2 1/1 3/4", "dynamicBlackLevel" to "64 64 64 64", "whiteLevel" to "1000")
        assertEquals(inherited, index.effectiveFrameMetadata(sequences[0]))
        assertEquals(inherited, index.effectiveFrameMetadata(sequences[3]))
        assertSame(index.effectiveFrameMetadata(sequences[3]), index.effectiveFrameMetadata(sequences[4]))
        // A frame with its own record keeps it as is, even an empty neutral.
        assertEquals(mapOf("neutral" to "1/2 1/1 3/4", "iso" to "100"), index.effectiveFrameMetadata(sequences[1]))
        assertEquals("", index.effectiveFrameMetadata(sequences[2])["neutral"])
    }

    @Test
    fun reviewFrameSourceSeesTheInheritedLevels() {
        val file = writeTake(
            mapOf("neutral" to "1/2 1/1 3/4", "dynamicBlackLevel" to "64.5 64 64 64", "whiteLevel" to "1000", "iso" to "400"),
            null,
        )
        RandomAccessFile(file, "r").use { raf ->
            OcrawFrameSource(raf.channel, OcrawStreamReader(raf.channel).indexFrames()).use { source ->
                val slot = source.acquire()!!
                val last = source.read(1, slot) as OcrawFrameRead.Frame
                assertEquals(mapOf("neutral" to "1/2 1/1 3/4", "dynamicBlackLevel" to "64.5 64 64 64", "whiteLevel" to "1000"),
                    last.metadata)
                assertEquals("1/2 1/1 3/4", last.neutral)
            }
        }
    }

    private class Dng(val ifd0: Map<Int, List<Long>>, val exif: Map<Int, List<Long>>)

    private fun convert(vararg metas: Map<String, String>?): List<Dng> {
        val file = writeTake(*metas)
        return RandomAccessFile(file, "r").use { raf ->
            val writer = CinemaDngWriter(raf.channel, OcrawStreamReader(raf.channel).indexFrames(), "A001_C002")
            (0 until writer.frameCount).map { frame ->
                val bytes = ByteArrayOutputStream().also { writer.write(frame, it) }.toByteArray()
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val ifd0 = readIfd(buffer, buffer.getInt(4))
                Dng(ifd0, readIfd(buffer, ifd0.getValue(TAG_EXIF).single().toInt()))
            }
        }
    }

    private fun index(vararg metas: Map<String, String>?): OcrawFrameIndex {
        val file = writeTake(*metas)
        return RandomAccessFile(file, "r").use { OcrawStreamReader(it.channel).indexFrames() }
    }

    private fun writeTake(vararg metas: Map<String, String>?): File {
        val file = temp.newFile()
        RandomAccessFile(file, "rw").use { raf ->
            val writer = OcrawStreamWriter(raf.channel, OcrawHeader(ByteArray(16) { it.toByte() }), NANOS,
                OcrawStreamWriter.Options(forceOnFinish = false), force = null)
            writer.writeMetadata(CinemaDngGoldenTest.staticMetadata(), 0)
            metas.forEachIndexed { i, meta ->
                val ts = 5_000_000_000L + i * 41_666_667L
                val header = OcrawFrameHeader.raw10(WIDTH, HEIGHT, 8, 4, ts, i.toLong())
                val sequence = writer.writeRawFrame(header, ByteBuffer.wrap(Random(i).nextBytes(header.dataLength)), ts)
                meta?.let { writer.writeFrameMetadata(sequence, it) }
            }
            writer.finish()
        }
        return file
    }

    /** Every tag as a list of numbers: SHORT/LONG values, RATIONALs as numerator, denominator pairs. */
    private fun readIfd(buffer: ByteBuffer, offset: Int): Map<Int, List<Long>> {
        val tags = HashMap<Int, List<Long>>()
        for (i in 0 until buffer.getShort(offset).toInt()) {
            val at = offset + 2 + 12 * i
            val tag = buffer.getShort(at).toInt() and 0xffff
            val type = buffer.getShort(at + 2).toInt()
            val count = buffer.getInt(at + 4)
            val size = when (type) { 3 -> 2; 4 -> 4; 5, 10 -> 8; else -> 1 } * count
            val data = if (size <= 4) at + 8 else buffer.getInt(at + 8)
            tags[tag] = when (type) {
                3 -> List(count) { (buffer.getShort(data + 2 * it).toLong() and 0xffff) }
                4 -> List(count) { buffer.getInt(data + 4 * it).toLong() and 0xffffffffL }
                5, 10 -> List(count * 2) { buffer.getInt(data + 4 * it).toLong() }
                else -> List(count) { buffer.get(data + it).toLong() and 0xff }
            }
        }
        return tags
    }

    private companion object {
        val NANOS = OcrawTimeBase(1, 1_000_000_000)
        const val WIDTH = 64
        const val HEIGHT = 34
        const val TAG_EXIF = 34665
        const val TAG_BLACK = 50714
        const val TAG_WHITE = 50717
        const val TAG_NEUTRAL = 50728
        const val TAG_EXPOSURE = 33434
        const val TAG_ISO = 34855
    }
}
