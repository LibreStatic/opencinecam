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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The static `orientation` key: the reader parses it to a quarter turn (0 for older takes and
 * junk), and the CinemaDNG export maps it to the TIFF Orientation tag.
 */
class CinemaDngOrientationTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/cinemadng-test").apply { mkdirs() })

    @Test
    fun orientationParsesQuarterTurnsAndFallsBackToZero() {
        assertEquals(0, OcrawOrientation.degrees(emptyMap()))
        assertEquals(0, OcrawOrientation.degrees(mapOf("orientation" to "0")))
        assertEquals(90, OcrawOrientation.degrees(mapOf("orientation" to "90")))
        assertEquals(180, OcrawOrientation.degrees(mapOf("orientation" to "180")))
        assertEquals(270, OcrawOrientation.degrees(mapOf("orientation" to "270")))
        for (junk in listOf("", "45", "-90", "360", "90.0", "ninety")) {
            assertEquals(junk, 0, OcrawOrientation.degrees(mapOf("orientation" to junk)))
        }
    }

    @Test
    fun indexReadsTheTakesOrientation() {
        assertEquals(90, index(mapOf("orientation" to "90")).orientationDegrees)
        assertEquals(0, index(emptyMap()).orientationDegrees)
        assertEquals(0, index(mapOf("orientation" to "sideways")).orientationDegrees)
    }

    @Test
    fun dngOrientationTagFollowsTheTake() {
        val expected = mapOf<String?, Long>(null to 1, "0" to 1, "90" to 6, "180" to 3, "270" to 8, "junk" to 1)
        for ((value, tag) in expected) {
            val extra = value?.let { mapOf("orientation" to it) }.orEmpty()
            assertEquals("orientation $value", listOf(tag), firstDngIfd0(extra)[TAG_ORIENTATION])
        }
    }

    private fun index(extra: Map<String, String>): OcrawFrameIndex {
        val file = writeTake(extra)
        return RandomAccessFile(file, "r").use { OcrawStreamReader(it.channel).indexFrames() }
    }

    private fun firstDngIfd0(extra: Map<String, String>): Map<Int, List<Long>> {
        val file = writeTake(extra)
        return RandomAccessFile(file, "r").use { raf ->
            val writer = CinemaDngWriter(raf.channel, OcrawStreamReader(raf.channel).indexFrames(), "A001_C002")
            val bytes = ByteArrayOutputStream().also { writer.write(0, it) }.toByteArray()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            readShortTags(buffer, buffer.getInt(4))
        }
    }

    private fun writeTake(extra: Map<String, String>): File {
        val file = temp.newFile()
        RandomAccessFile(file, "rw").use { raf ->
            val writer = OcrawStreamWriter(raf.channel, OcrawHeader(ByteArray(16) { it.toByte() }), NANOS,
                OcrawStreamWriter.Options(forceOnFinish = false), force = null)
            writer.writeMetadata(CinemaDngGoldenTest.staticMetadata() + extra, 0)
            val ts = 5_000_000_000L
            val header = OcrawFrameHeader.raw10(WIDTH, HEIGHT, 8, 4, ts, 0)
            writer.writeRawFrame(header, ByteBuffer.wrap(Random(0).nextBytes(header.dataLength)), ts)
            writer.finish()
        }
        return file
    }

    /** The SHORT tags of the IFD at [offset]. */
    private fun readShortTags(buffer: ByteBuffer, offset: Int): Map<Int, List<Long>> {
        val tags = HashMap<Int, List<Long>>()
        for (i in 0 until buffer.getShort(offset).toInt()) {
            val at = offset + 2 + 12 * i
            val tag = buffer.getShort(at).toInt() and 0xffff
            val type = buffer.getShort(at + 2).toInt()
            val count = buffer.getInt(at + 4)
            if (type != 3) continue
            val data = if (2 * count <= 4) at + 8 else buffer.getInt(at + 8)
            tags[tag] = List(count) { buffer.getShort(data + 2 * it).toLong() and 0xffff }
        }
        return tags
    }

    private companion object {
        val NANOS = OcrawTimeBase(1, 1_000_000_000)
        const val WIDTH = 64
        const val HEIGHT = 34
        const val TAG_ORIENTATION = 274
    }
}
