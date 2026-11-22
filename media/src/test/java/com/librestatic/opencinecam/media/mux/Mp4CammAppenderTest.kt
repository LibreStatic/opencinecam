/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp4CammAppenderTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/mp4-camm-appender-test").apply { mkdirs() })

    /** One packet to write: [type] 1 carries [exposureNs] and [skewNs], the others the vector. */
    private class Packet(val timeUs: Long, val type: Int, val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val exposureNs: Int = 0, val skewNs: Int = 0)

    private fun gyro(count: Int, startUs: Long = 0, stepUs: Long = 5_000): List<Packet> =
        List(count) { i -> Packet(startUs + i * stepUs, 2, 0.01f * i, -0.02f * i, 0.5f + i) }

    private fun newFile(spec: TestMp4Spec = TestMp4Spec()): File = temp.newFile().apply { writeBytes(TestMp4.build(spec)) }

    private fun write(appender: Mp4CammAppender, packets: List<Packet>) {
        for (p in packets) {
            if (p.type == 1) appender.writeExposure(p.timeUs, p.exposureNs, p.skewNs) else appender.writePacket(p.timeUs, p.type, p.x, p.y, p.z)
        }
    }

    private fun append(file: File, packets: List<Packet>): Int = withMp4(file, write = true) { bytes ->
        Mp4CammAppender.begin(bytes).use { appender ->
            write(appender, packets)
            appender.finish()
        }
    }

    /** The bytes the specification lays out for [p]: little-endian reserved, type, payload. */
    private fun expectedBytes(p: Packet): ByteArray {
        val buffer = ByteBuffer.allocate(if (p.type == 1) 12 else 16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0.toShort()).putShort(p.type.toShort())
        if (p.type == 1) buffer.putInt(p.exposureNs).putInt(p.skewNs) else buffer.putFloat(p.x).putFloat(p.y).putFloat(p.z)
        return buffer.array()
    }

    private fun boxes(file: File): List<Mp4Box> = withMp4(file) { scanTopLevelBoxes(it) }

    private fun movie(file: File): Mp4Movie = withMp4(file) { readMovie(it, findSingleMoov(scanTopLevelBoxes(it))) }

    private class CammView(
        val trackId: Long,
        val tkhdFlags: Long,
        val tkhdDuration: Long,
        val volume: Int,
        val width: Long,
        val height: Long,
        val edits: List<TestEdit>?,
        val mdhdTimescale: Long,
        val mdhdDuration: Long,
        val handlerName: String,
        val mediaHeader: String,
        val dataReference: Long,
        val sampleEntry: ByteArray,
        val stts: List<Run>,
        val stsc: List<List<Long>>,
        val sizes: IntArray,
        val constantSize: Boolean,
        val chunkOffsets: LongArray,
    )

    private fun cammView(movie: Mp4Movie): CammView {
        val d = movie.bytes
        val track = movie.tracks.single { it.handlerType == "meta" }
        assertTrue(Mp4CammAppender.isCammTrack(d, track))
        val trak = track.trak
        val tkhd = findChild(d, trak, "tkhd")!!
        assertEquals(0, u8(d, tkhd.payload))
        val elst = findPath(d, trak, "edts", "elst")
        val edits = elst?.let {
            assertEquals(0, u8(d, it.payload))
            (0 until u32(d, it.payload + 4).toInt()).map { i ->
                val at = it.payload + 8 + i * 12
                assertEquals(0x00010000L, u32(d, at + 8))
                TestEdit(u32(d, at), s32(d, at + 4).toLong())
            }
        }
        val mdhd = findPath(d, trak, "mdia", "mdhd")!!
        assertEquals(0, u8(d, mdhd.payload))
        val hdlr = findPath(d, trak, "mdia", "hdlr")!!
        val minf = findPath(d, trak, "mdia", "minf")!!
        val dref = findPath(d, minf, "dinf", "dref")!!
        val url = childBoxes(d, dref.payload + 8, dref.end).single()
        assertEquals("url ", url.type)
        val stbl = findChild(d, minf, "stbl")!!
        val stsd = findChild(d, stbl, "stsd")!!
        assertEquals(1L, u32(d, stsd.payload + 4))
        val entry = childBoxes(d, stsd.payload + 8, stsd.end).single()
        val stts = findChild(d, stbl, "stts")!!
        val stsc = findChild(d, stbl, "stsc")!!
        val stsz = findChild(d, stbl, "stsz")!!
        val fixed = u32(d, stsz.payload + 4).toInt()
        val sampleCount = u32(d, stsz.payload + 8).toInt()
        assertNull(findChild(d, stbl, "co64"))
        val stco = findChild(d, stbl, "stco")!!
        return CammView(
            trackId = u32(d, tkhd.payload + 12),
            tkhdFlags = u32(d, tkhd.payload) and 0xFFFFFF,
            tkhdDuration = u32(d, tkhd.payload + 20),
            volume = u16(d, tkhd.payload + 36),
            width = u32(d, tkhd.payload + 76),
            height = u32(d, tkhd.payload + 80),
            edits = edits,
            mdhdTimescale = u32(d, mdhd.payload + 12),
            mdhdDuration = u32(d, mdhd.payload + 16),
            handlerName = String(d, hdlr.payload + 24, hdlr.end - hdlr.payload - 24, Charsets.US_ASCII),
            mediaHeader = childBoxes(d, minf).first().type,
            dataReference = u32(d, url.payload) and 0xFFFFFF,
            sampleEntry = d.copyOfRange(entry.offset, entry.end),
            stts = (0 until u32(d, stts.payload + 4).toInt()).map { Run(u32(d, stts.payload + 8 + it * 8).toInt(), u32(d, stts.payload + 12 + it * 8)) },
            stsc = (0 until u32(d, stsc.payload + 4).toInt()).map { i -> (0 until 3).map { u32(d, stsc.payload + 8 + i * 12 + it * 4) } },
            sizes = if (fixed != 0) IntArray(sampleCount) { fixed } else IntArray(sampleCount) { u32(d, stsz.payload + 12 + it * 4).toInt() },
            constantSize = fixed != 0,
            chunkOffsets = LongArray(u32(d, stco.payload + 4).toInt()) { u32(d, stco.payload + 8 + it * 4) },
        )
    }

    /** Every packet, read back through the chunk offsets, the sample-to-chunk table and the sizes. */
    private fun readPackets(view: CammView, file: File): List<ByteArray> {
        val bytes = file.readBytes()
        val out = ArrayList<ByteArray>()
        var sample = 0
        for (chunk in 1..view.chunkOffsets.size) {
            val perChunk = view.stsc.last { it[0] <= chunk }[1].toInt()
            var position = view.chunkOffsets[chunk - 1].toInt()
            repeat(perChunk) {
                out += bytes.copyOfRange(position, position + view.sizes[sample])
                position += view.sizes[sample]
                sample++
            }
        }
        assertEquals(view.sizes.size, sample)
        return out
    }

    private fun assertPackets(expected: List<Packet>, view: CammView, file: File) {
        val actual = readPackets(view, file)
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertArrayEquals("packet $it", expectedBytes(expected[it]), actual[it]) }
    }

    private fun trakBytes(movie: Mp4Movie, handler: String): ByteArray {
        val trak = movie.tracks.single { it.handlerType == handler }.trak
        return movie.bytes.copyOfRange(trak.offset, trak.end)
    }

    @Test
    fun appendsACammTrackWithoutTouchingTheVideo() {
        val file = newFile()
        val original = file.readBytes()
        val originalBoxes = boxes(file)
        val oldMoov = originalBoxes.single { it.type == "moov" }
        val videoMdat = originalBoxes.single { it.type == "mdat" }
        val oldMovie = movie(file)
        val packets = gyro(10)

        assertEquals(10, append(file, packets))

        val after = file.readBytes()
        val newBoxes = boxes(file)
        assertEquals(listOf("ftyp", "mdat", "free", "mdat", "moov"), newBoxes.map { it.type })
        assertEquals(oldMoov.offset, newBoxes[2].offset)
        assertEquals(oldMoov.size, newBoxes[2].size)
        // Everything up to the original end is unchanged but the four bytes of the old moov's type.
        val restored = after.copyOfRange(0, original.size)
        "moov".toByteArray(Charsets.ISO_8859_1).copyInto(restored, (oldMoov.offset + 4).toInt())
        assertArrayEquals(original, restored)
        assertEquals(sha256(original, videoMdat.offset.toInt(), videoMdat.end.toInt()), sha256(after, videoMdat.offset.toInt(), videoMdat.end.toInt()))
        val newMdat = newBoxes[3]
        assertEquals(original.size.toLong(), newMdat.offset)
        assertEquals(16, newMdat.headerSize)
        assertEquals(16L + 10 * 16, newMdat.size)
        assertEquals(after.size.toLong(), newBoxes[4].end)

        val newMovie = movie(file)
        assertEquals(listOf("mvhd", "trak", "trak", "udta"), newMovie.children.map { it.type })
        assertArrayEquals(trakBytes(oldMovie, "vide"), trakBytes(newMovie, "vide"))
        assertEquals(listOf("vide", "meta"), newMovie.tracks.map { it.handlerType })
        // The video's 1200 ms is longer than the 50 ms of motion data: only the next track id changes.
        assertEquals(3L, newMovie.header.nextTrackId)
        assertEquals(1_200L, newMovie.header.duration)

        val view = cammView(newMovie)
        assertEquals(2L, view.trackId)
        assertEquals(3L, view.tkhdFlags)
        assertEquals(50L, view.tkhdDuration)
        assertEquals(0, view.volume)
        assertEquals(0L, view.width)
        assertEquals(0L, view.height)
        assertNull("a track starting at 0 needs no edit", view.edits)
        assertEquals(1_000_000L, view.mdhdTimescale)
        assertEquals(50_000L, view.mdhdDuration)
        assertEquals("CameraMetadataMotionHandler\u0000", view.handlerName)
        assertEquals("nmhd", view.mediaHeader)
        assertEquals(1L, view.dataReference)
        assertArrayEquals(byteArrayOf(0, 0, 0, 16, 'c'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'm'.code.toByte(), 0, 0, 0, 0, 0, 0, 0, 1), view.sampleEntry)
        assertEquals(listOf(Run(10, 5_000)), view.stts)
        assertEquals(listOf(listOf(1L, 10L, 1L)), view.stsc)
        assertTrue(view.constantSize)
        assertArrayEquals(longArrayOf(original.size + 16L), view.chunkOffsets)
        assertPackets(packets, view, file)

        assertEquals(1_160_000L, withMp4(file) { readMp4VideoTiming(it) }.lastSampleTimeUs)
    }

    @Test
    fun mixedPacketsKeepTheirOrderSizesAndRunLengthDeltas() {
        val file = newFile()
        val packets = listOf(
            Packet(0, 1, exposureNs = 8_000_000, skewNs = 20_000_000),
            Packet(0, 2, 1f, 2f, 3f),
            Packet(2_500, 3, 0.1f, 9.81f, -0.2f),
            Packet(5_000, 2, -1f, -2f, -3f),
            Packet(7_500, 7, 20f, -30f, 40f),
            Packet(10_000, 2, 4f, 5f, 6f),
            Packet(13_000, 1, exposureNs = -1, skewNs = Int.MIN_VALUE),
        )

        append(file, packets)

        val view = cammView(movie(file))
        // Equal times give a zero delta; the last packet repeats the delta before it.
        assertEquals(listOf(Run(1, 0), Run(4, 2_500), Run(2, 3_000)), view.stts)
        assertFalse(view.constantSize)
        assertArrayEquals(intArrayOf(12, 16, 16, 16, 16, 16, 12), view.sizes)
        assertEquals(16_000L, view.mdhdDuration)
        assertPackets(packets, view, file)
    }

    @Test
    fun aLateFirstPacketIsPlacedWithAnEmptyEdit() {
        val file = newFile()
        append(file, gyro(11, startUs = 300_000, stepUs = 10_000))

        val view = cammView(movie(file))
        assertEquals(listOf(TestEdit(300, -1), TestEdit(110, 0)), view.edits)
        assertEquals(410L, view.tkhdDuration)
        assertEquals(110_000L, view.mdhdDuration)
    }

    @Test
    fun aLongerTrackExtendsTheMovieDuration() {
        val file = newFile(TestMp4Spec(nextTrackId = 7))
        append(file, gyro(301, stepUs = 5_000))

        val newMovie = movie(file)
        assertEquals(1_505L, newMovie.header.duration)
        assertEquals(8L, newMovie.header.nextTrackId)
        assertEquals(7L, cammView(newMovie).trackId)
    }

    @Test
    fun packetsAreChunkedByThousandTwentyFour() {
        val cases = mapOf(
            1 to listOf(listOf(1L, 1L, 1L)),
            1024 to listOf(listOf(1L, 1024L, 1L)),
            1025 to listOf(listOf(1L, 1024L, 1L), listOf(2L, 1L, 1L)),
            2048 to listOf(listOf(1L, 1024L, 1L)),
            2500 to listOf(listOf(1L, 1024L, 1L), listOf(3L, 452L, 1L)),
        )
        for ((count, stsc) in cases) {
            val file = newFile()
            val originalSize = file.length()
            val packets = gyro(count, stepUs = 1_000)
            append(file, packets)

            val view = cammView(movie(file))
            assertEquals("stsc for $count", stsc, view.stsc)
            assertEquals("chunks for $count", (count + 1023) / 1024, view.chunkOffsets.size)
            assertEquals(originalSize + 16, view.chunkOffsets[0])
            if (count > 1024) assertEquals(originalSize + 16 + 1024 * 16, view.chunkOffsets[1])
            assertEquals("a single packet lasts 1 µs", if (count == 1) listOf(Run(1, 1)) else listOf(Run(count, 1_000)), view.stts)
            assertPackets(packets, view, file)
        }
    }

    @Test
    fun timesMustBeNonNegativeAndNonDecreasing() {
        val file = newFile()
        val before = file.readBytes()
        withMp4(file, write = true) { bytes ->
            Mp4CammAppender.begin(bytes).use { appender ->
                assertThrows(IllegalArgumentException::class.java) { appender.writePacket(-1, 2, 0f, 0f, 0f) }
                appender.writePacket(1_000, 2, 0f, 0f, 0f)
                appender.writePacket(1_000, 3, 0f, 0f, 0f)
                assertThrows(IllegalArgumentException::class.java) { appender.writePacket(999, 2, 0f, 0f, 0f) }
                assertThrows(IllegalArgumentException::class.java) { appender.writeExposure(999, 1, 1) }
                assertThrows(IllegalArgumentException::class.java) { appender.writePacket(2_000, 1, 0f, 0f, 0f) }
                assertThrows(IllegalArgumentException::class.java) { appender.writePacket(2_000, 4, 0f, 0f, 0f) }
                appender.writePacket(2_000, 7, 0f, 0f, 0f)
                assertEquals(3, appender.finish())
            }
        }
        assertEquals(listOf(Run(1, 0), Run(2, 1_000)), cammView(movie(file)).stts)
        assertTrue(file.length() > before.size)
    }

    private fun assertRefused(file: File, reason: String) {
        val before = file.readBytes()

        val error = withMp4(file, write = true) { bytes ->
            assertThrows(Mp4FormatException::class.java) { Mp4CammAppender.begin(bytes) }
        }

        assertEquals(reason, error.reason)
        assertEquals(sha256(before), sha256(file.readBytes()))
    }

    @Test
    fun refusesAFileWithoutMoov() = assertRefused(newFile(TestMp4Spec(omitMoov = true)), "no-moov")

    @Test
    fun refusesAFileWithTwoMoov() = assertRefused(newFile(TestMp4Spec(secondMoov = true)), "several-moov")

    @Test
    fun refusesAnOpenEndedBox() = assertRefused(newFile(TestMp4Spec(openEndedLast = true)), "open-ended-box")

    @Test
    fun refusesTrailingBytes() = assertRefused(newFile(TestMp4Spec(trailing = byteArrayOf(1, 2, 3, 4, 5))), "trailing-bytes")

    @Test
    fun refusesASecondCammTrack() {
        val file = newFile()
        append(file, gyro(4))
        assertRefused(file, "has-camm-track")
    }

    @Test
    fun acceptsAMetaTrackThatIsNotCamm() {
        // A minimal 'meta' track has no sample description, so it is some other metadata, not CAMM.
        val file = newFile(TestMp4Spec(extraTraks = listOf("meta"), nextTrackId = 3))
        append(file, gyro(4))
        assertEquals(listOf("vide", "meta", "meta"), movie(file).tracks.map { it.handlerType })
    }

    @Test
    fun finishWithoutPacketsChangesNothing() {
        val file = newFile()
        val before = file.readBytes()

        withMp4(file, write = true) { bytes ->
            Mp4CammAppender.begin(bytes).use { appender ->
                assertThrows(IllegalStateException::class.java) { appender.finish() }
            }
        }

        assertEquals(sha256(before), sha256(file.readBytes()))
    }

    @Test
    fun closeWithoutFinishSealsTheNewMdat() {
        val file = newFile()
        val originalSize = file.length()
        val oldMoov = boxes(file).single { it.type == "moov" }

        withMp4(file, write = true) { bytes ->
            val appender = Mp4CammAppender.begin(bytes)
            // 32 000 packets of 16 bytes: the buffer flushes at least once.
            write(appender, gyro(32_000, stepUs = 100))
            appender.close()
            appender.close()
            assertThrows(IllegalStateException::class.java) { appender.writePacket(0, 2, 0f, 0f, 0f) }
        }

        val after = boxes(file)
        assertTrue(file.length() > originalSize)
        assertEquals(listOf("ftyp", "mdat", "moov", "mdat"), after.map { it.type })
        assertEquals(originalSize, after.last().offset)
        assertEquals(file.length(), after.last().end)
        assertEquals("moov", after.single { it.offset == oldMoov.offset }.type)
        assertTrue(movie(file).tracks.none { it.handlerType == "meta" })
    }

    @Test
    fun aFailedWriteAtAnyPointLeavesTheOldMoov() {
        val original = newFile().readBytes()
        val oldMoov = withMp4(newFile()) { findSingleMoov(scanTopLevelBoxes(it)) }
        val packets = gyro(40_000, stepUs = 100)

        val counting = temp.newFile().apply { writeBytes(original) }
        val totalWrites = withMp4(counting, write = true) { bytes ->
            val failing = FailingMp4Bytes(bytes, Int.MAX_VALUE)
            Mp4CammAppender.begin(failing).use { appender ->
                write(appender, packets)
                appender.finish()
            }
            failing.writes
        }
        assertTrue("flushes plus size, moov and retype: $totalWrites", totalWrites >= 5)

        for (failOn in 1..totalWrites) {
            val label = "write $failOn"
            val file = temp.newFile().apply { writeBytes(original) }
            withMp4(file, write = true) { bytes ->
                val appender = Mp4CammAppender.begin(FailingMp4Bytes(bytes, failOn, 1))
                assertThrows(label, IOException::class.java) {
                    write(appender, packets)
                    appender.finish()
                }
                appender.close()
            }

            val moov = withMp4(file) { findSingleMoov(scanTopLevelBoxes(it)) }
            assertEquals(label, oldMoov.offset, moov.offset)
            assertEquals(label, file.length(), boxes(file).last().end)
            assertTrue(label, movie(file).tracks.none { it.handlerType == "meta" })
            assertEquals(label, 1_160_000L, withMp4(file) { readMp4VideoTiming(it) }.lastSampleTimeUs)
        }
    }

    private fun appendAudio(file: File) {
        val asc = byteArrayOf(0x11, 0x90.toByte())
        withMp4(file, write = true) { bytes ->
            Mp4AudioAppender.begin(bytes).use { appender ->
                repeat(25) { appender.writeAccessUnit(ByteBuffer.wrap(ByteArray(300) { j -> (it + j).toByte() })) }
                appender.finish(Mp4AacTrack(48_000, 2, asc, 128_000, 1024, 24_000, 0))
            }
        }
    }

    @Test
    fun aFileWithAppendedAudioTakesCammAndKeepsBothTracks() {
        val file = newFile()
        appendAudio(file)
        val withAudio = movie(file)
        val packets = gyro(20)

        append(file, packets)

        val newMovie = movie(file)
        assertEquals(listOf("vide", "soun", "meta"), newMovie.tracks.map { it.handlerType })
        assertArrayEquals(trakBytes(withAudio, "vide"), trakBytes(newMovie, "vide"))
        assertArrayEquals(trakBytes(withAudio, "soun"), trakBytes(newMovie, "soun"))
        val view = cammView(newMovie)
        assertEquals(3L, view.trackId)
        assertEquals(4L, newMovie.header.nextTrackId)
        assertPackets(packets, view, file)
        assertEquals(listOf("ftyp", "mdat", "free", "mdat", "free", "mdat", "moov"), boxes(file).map { it.type })
    }

    @Test
    fun aFileWithCammTakesAudioAndKeepsTheCammTrack() {
        val file = newFile()
        val packets = gyro(20)
        append(file, packets)
        val withCamm = movie(file)

        appendAudio(file)

        val newMovie = movie(file)
        assertEquals(listOf("vide", "meta", "soun"), newMovie.tracks.map { it.handlerType })
        assertArrayEquals(trakBytes(withCamm, "meta"), trakBytes(newMovie, "meta"))
        assertEquals(3L, newMovie.tracks.single { it.handlerType == "soun" }.trackId)
        assertPackets(packets, cammView(newMovie), file)
        // The audio appender still refuses a second sound track on a file that carries CAMM.
        val error = withMp4(file, write = true) { bytes -> assertThrows(Mp4FormatException::class.java) { Mp4AudioAppender.begin(bytes) } }
        assertEquals("has-audio-track", error.reason)
    }
}
