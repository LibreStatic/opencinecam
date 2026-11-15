/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp4AudioAppenderTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/mp4-appender-test").apply { mkdirs() })

    /** AAC-LC, 48 kHz, stereo. */
    private val asc = byteArrayOf(0x11, 0x90.toByte())

    private fun aacTrack(validFrames: Long, startOffsetUs: Long, primingFrames: Int = 1024, channels: Int = 2) =
        Mp4AacTrack(48_000, channels, asc, 128_000, primingFrames, validFrames, startOffsetUs)

    /** Enough units to hold the track's priming and valid frames. */
    private fun unitCountFor(track: Mp4AacTrack): Int = ((track.primingFrames + track.validFrames + 1023) / 1024).toInt()

    private fun units(count: Int, size: (Int) -> Int = { 200 + (it * 37) % 300 }): List<ByteArray> =
        List(count) { i -> ByteArray(size(i)) { j -> (i * 13 + j * 7).toByte() } }

    private fun newFile(spec: TestMp4Spec = TestMp4Spec()): File = temp.newFile().apply { writeBytes(TestMp4.build(spec)) }

    private fun append(file: File, units: List<ByteArray>, track: Mp4AacTrack) {
        withMp4(file, write = true) { bytes ->
            Mp4AudioAppender.begin(bytes).use { appender ->
                units.forEach { appender.writeAccessUnit(ByteBuffer.wrap(it)) }
                appender.finish(track)
            }
        }
    }

    private fun boxes(file: File): List<Mp4Box> = withMp4(file) { scanTopLevelBoxes(it) }

    private fun movie(file: File): Mp4Movie = withMp4(file) { readMovie(it, findSingleMoov(scanTopLevelBoxes(it))) }

    private fun movie(bytes: Mp4Bytes): Mp4Movie = readMovie(bytes, findSingleMoov(scanTopLevelBoxes(bytes)))

    private class AudioView(
        val trackId: Long,
        val tkhdFlags: Long,
        val tkhdDuration: Long,
        val edits: List<TestEdit>,
        val mdhdTimescale: Long,
        val mdhdDuration: Long,
        val handlerName: String,
        val channels: Int,
        val sampleRateFixed: Long,
        val esds: ByteArray,
        val stts: List<Run>,
        val stsc: List<List<Long>>,
        val sizes: IntArray,
        val chunkOffsets: LongArray,
        val co64: Boolean,
    )

    private fun audioView(movie: Mp4Movie): AudioView {
        val d = movie.bytes
        val trak = movie.tracks.single { it.handlerType == "soun" }.trak
        val tkhd = findChild(d, trak, "tkhd")!!
        assertEquals(0, u8(d, tkhd.payload))
        val elst = findPath(d, trak, "edts", "elst")!!
        val elstVersion = u8(d, elst.payload)
        val entrySize = if (elstVersion == 1) 20 else 12
        val edits = (0 until u32(d, elst.payload + 4).toInt()).map { i ->
            val at = elst.payload + 8 + i * entrySize
            if (elstVersion == 1) TestEdit(u64(d, at), u64(d, at + 8)) else TestEdit(u32(d, at), s32(d, at + 4).toLong())
        }
        val mdhd = findPath(d, trak, "mdia", "mdhd")!!
        assertEquals(0, u8(d, mdhd.payload))
        val hdlr = findPath(d, trak, "mdia", "hdlr")!!
        val stbl = findPath(d, trak, "mdia", "minf", "stbl")!!
        val stsd = findChild(d, stbl, "stsd")!!
        val mp4a = childBoxes(d, stsd.payload + 8, stsd.end).single()
        assertEquals("mp4a", mp4a.type)
        val esds = childBoxes(d, mp4a.payload + 28, mp4a.end).single()
        assertEquals("esds", esds.type)
        val stts = findChild(d, stbl, "stts")!!
        val stsc = findChild(d, stbl, "stsc")!!
        val stsz = findChild(d, stbl, "stsz")!!
        assertEquals(0L, u32(d, stsz.payload + 4))
        val stco = findChild(d, stbl, "stco")
        val co64 = findChild(d, stbl, "co64")
        assertTrue((stco == null) != (co64 == null))
        val offsetsBox = stco ?: co64!!
        val chunkCount = u32(d, offsetsBox.payload + 4).toInt()
        return AudioView(
            trackId = u32(d, tkhd.payload + 12),
            tkhdFlags = u32(d, tkhd.payload) and 0xFFFFFF,
            tkhdDuration = u32(d, tkhd.payload + 20),
            edits = edits,
            mdhdTimescale = u32(d, mdhd.payload + 12),
            mdhdDuration = u32(d, mdhd.payload + 16),
            handlerName = String(d, hdlr.payload + 24, hdlr.end - hdlr.payload - 24, Charsets.US_ASCII),
            channels = u16(d, mp4a.payload + 16),
            sampleRateFixed = u32(d, mp4a.payload + 24),
            esds = d.copyOfRange(esds.payload + 4, esds.end),
            stts = (0 until u32(d, stts.payload + 4).toInt()).map { Run(u32(d, stts.payload + 8 + it * 8).toInt(), u32(d, stts.payload + 12 + it * 8)) },
            stsc = (0 until u32(d, stsc.payload + 4).toInt()).map { i -> (0 until 3).map { u32(d, stsc.payload + 8 + i * 12 + it * 4) } },
            sizes = IntArray(u32(d, stsz.payload + 8).toInt()) { u32(d, stsz.payload + 12 + it * 4).toInt() },
            chunkOffsets = LongArray(chunkCount) {
                if (co64 != null) u64(d, co64.payload + 8 + it * 8) else u32(d, stco!!.payload + 8 + it * 4)
            },
            co64 = co64 != null,
        )
    }

    /** Every access unit, read back through the chunk offsets, the sample-to-chunk table and the sizes. */
    private fun readUnits(view: AudioView, read: (Long, Int) -> ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var sample = 0
        for (chunk in 1..view.chunkOffsets.size) {
            val perChunk = view.stsc.last { it[0] <= chunk }[1].toInt()
            var position = view.chunkOffsets[chunk - 1]
            repeat(perChunk) {
                out += read(position, view.sizes[sample])
                position += view.sizes[sample]
                sample++
            }
        }
        assertEquals(view.sizes.size, sample)
        return out
    }

    private fun fileReader(file: File): (Long, Int) -> ByteArray {
        val bytes = file.readBytes()
        return { position, count -> bytes.copyOfRange(position.toInt(), position.toInt() + count) }
    }

    private fun assertUnitsEqual(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertArrayEquals("unit $it", expected[it], actual[it]) }
    }

    private fun movieHeaderValues(movie: Mp4Movie): Pair<Long, Long> = movie.header.nextTrackId to movie.header.duration

    @Test
    fun appendsAnAudioTrackWithoutTouchingTheVideo() {
        val file = newFile(TestMp4Spec(mvhdDuration = 500))
        val original = file.readBytes()
        val originalBoxes = boxes(file)
        val oldMoov = originalBoxes.single { it.type == "moov" }
        val videoMdat = originalBoxes.single { it.type == "mdat" }
        val oldMovie = movie(file)
        val track = aacTrack(validFrames = 24_000, startOffsetUs = 100_000)
        val written = units(unitCountFor(track))
        assertEquals(25, written.size)

        append(file, written, track)

        val after = file.readBytes()
        val newBoxes = boxes(file)
        assertEquals(listOf("ftyp", "mdat", "free", "mdat", "moov"), newBoxes.map { it.type })
        val free = newBoxes[2]
        assertEquals(oldMoov.offset, free.offset)
        assertEquals(oldMoov.size, free.size)
        // Everything up to the original end is unchanged but the four bytes of the old moov's type.
        val restored = after.copyOfRange(0, original.size)
        "moov".toByteArray(Charsets.ISO_8859_1).copyInto(restored, (oldMoov.offset + 4).toInt())
        assertArrayEquals(original, restored)
        val videoFrom = videoMdat.offset.toInt()
        val videoTo = videoMdat.end.toInt()
        assertEquals(sha256(original, videoFrom, videoTo), sha256(after, videoFrom, videoTo))
        val newMdat = newBoxes[3]
        assertEquals(original.size.toLong(), newMdat.offset)
        assertEquals(16, newMdat.headerSize)
        assertEquals(16L + written.sumOf { it.size }, newMdat.size)
        assertEquals(newMdat.end, newBoxes[4].offset)
        assertEquals(after.size.toLong(), newBoxes[4].end)

        val newMovie = movie(file)
        assertEquals(listOf("mvhd", "trak", "trak", "udta"), newMovie.children.map { it.type })
        val oldTrak = oldMovie.tracks.single().trak
        val newVideoTrak = newMovie.tracks.first().trak
        assertArrayEquals(
            oldMovie.bytes.copyOfRange(oldTrak.offset, oldTrak.end),
            newMovie.bytes.copyOfRange(newVideoTrak.offset, newVideoTrak.end),
        )
        assertEquals("vide", newMovie.tracks[0].handlerType)
        assertEquals("soun", newMovie.tracks[1].handlerType)
        assertEquals(3L to 600L, movieHeaderValues(newMovie))
        assertEquals(TestMp4.CREATION_TIME, newMovie.header.creationTime)

        val view = audioView(newMovie)
        assertEquals(2L, view.trackId)
        assertEquals(7L, view.tkhdFlags)
        assertEquals(600L, view.tkhdDuration)
        assertEquals(listOf(TestEdit(100, -1), TestEdit(500, 1024)), view.edits)
        assertEquals(48_000L, view.mdhdTimescale)
        assertEquals(25L * 1024, view.mdhdDuration)
        assertEquals("SoundHandler\u0000", view.handlerName)
        assertEquals(2, view.channels)
        assertEquals(48_000L shl 16, view.sampleRateFixed)
        val expectedEsds = byteArrayOf(
            0x03, 0x19, 0x00, 0x00, 0x00,
            0x04, 0x11, 0x40, 0x15, 0x00, 0x06, 0x00, 0x00, 0x01, 0xF4.toByte(), 0x00, 0x00, 0x01, 0xF4.toByte(), 0x00,
            0x05, 0x02, 0x11, 0x90.toByte(),
            0x06, 0x01, 0x02,
        )
        assertArrayEquals(expectedEsds, view.esds)
        assertEquals(listOf(Run(25, 1024)), view.stts)
        assertEquals(listOf(listOf(1L, 25L, 1L)), view.stsc)
        assertArrayEquals(written.map { it.size }.toIntArray(), view.sizes)
        assertFalse(view.co64)
        assertArrayEquals(longArrayOf(original.size + 16L), view.chunkOffsets)
        assertUnitsEqual(written, readUnits(view, fileReader(file)))

        val timing = withMp4(file) { readMp4VideoTiming(it) }
        assertEquals(1_160_000L, timing.lastSampleTimeUs)
    }

    private fun appendedEdits(track: Mp4AacTrack, spec: TestMp4Spec = TestMp4Spec()): AudioView {
        val file = newFile(spec)
        append(file, units(unitCountFor(track)), track)
        return audioView(movie(file))
    }

    @Test
    fun zeroOffsetHasNoEmptyEdit() {
        val view = appendedEdits(aacTrack(validFrames = 24_000, startOffsetUs = 0))

        assertEquals(listOf(TestEdit(500, 1024)), view.edits)
        assertEquals(500L, view.tkhdDuration)
    }

    @Test
    fun negativeOffsetSkipsTheAudioBeforeTheVideo() {
        // 100 ms before the video is 4800 frames at 48 kHz.
        val view = appendedEdits(aacTrack(validFrames = 24_000, startOffsetUs = -100_000))

        assertEquals(listOf(TestEdit(400, 1024L + 4800)), view.edits)
        assertEquals(400L, view.tkhdDuration)
    }

    @Test
    fun audioLongerThanTheVideoIsTrimmed() {
        val view = appendedEdits(aacTrack(validFrames = 96_000, startOffsetUs = 100_000))

        assertEquals(listOf(TestEdit(100, -1), TestEdit(1_100, 1024)), view.edits)
        assertEquals(1_200L, view.tkhdDuration)
    }

    @Test
    fun audioEntirelyBeforeTheVideoIsRefused() {
        val file = newFile()
        val original = file.readBytes()
        val oldMoov = boxes(file).single { it.type == "moov" }
        val track = aacTrack(validFrames = 24_000, startOffsetUs = -1_000_000)

        val error = withMp4(file, write = true) { bytes ->
            Mp4AudioAppender.begin(bytes).use { appender ->
                units(unitCountFor(track)).forEach { appender.writeAccessUnit(ByteBuffer.wrap(it)) }
                assertThrows(Mp4FormatException::class.java) { appender.finish(track) }
            }
        }

        assertEquals("audio-before-video", error.reason)
        val after = boxes(file)
        assertEquals("moov", after.single { it.offset == oldMoov.offset }.type)
        assertEquals(sha256(original), sha256(file.readBytes()))
    }

    @Test
    fun unitsAreChunkedByThirtyTwo() {
        val cases = mapOf(
            1 to listOf(listOf(1L, 1L, 1L)),
            31 to listOf(listOf(1L, 31L, 1L)),
            32 to listOf(listOf(1L, 32L, 1L)),
            33 to listOf(listOf(1L, 32L, 1L), listOf(2L, 1L, 1L)),
            64 to listOf(listOf(1L, 32L, 1L)),
        )
        val chunks = mapOf(1 to 1, 31 to 1, 32 to 1, 33 to 2, 64 to 2)
        for ((count, stsc) in cases) {
            val file = newFile()
            val originalSize = file.length()
            val written = units(count)
            append(file, written, aacTrack(validFrames = count * 1024L, startOffsetUs = 0, primingFrames = 0))

            val view = audioView(movie(file))
            assertEquals("stsc for $count", stsc, view.stsc)
            assertEquals("chunks for $count", chunks.getValue(count), view.chunkOffsets.size)
            assertEquals(originalSize + 16, view.chunkOffsets[0])
            if (count > 32) assertEquals(originalSize + 16 + written.take(32).sumOf { it.size }, view.chunkOffsets[1])
            assertUnitsEqual(written, readUnits(view, fileReader(file)))
        }
    }

    @Test
    fun chunkOffsetsPast4GiBUseCo64() {
        val sparse = SparseMp4Bytes()
        val spec = TestMp4Spec()
        val ftyp = TestMp4.ftyp()
        val hugePayload = 5L shl 30
        sparse.writeFully(0, ByteBuffer.wrap(ftyp))
        sparse.writeFully(ftyp.size.toLong(), ByteBuffer.wrap(TestMp4.mdatHeader(TestMp4Spec(largeMdatHeader = true), hugePayload)))
        sparse.writeFully(ftyp.size + 16 + hugePayload, ByteBuffer.wrap(TestMp4.moov(spec, ftyp.size + 16L)))
        val originalSize = sparse.size()
        val written = units(40)

        Mp4AudioAppender.begin(sparse).use { appender ->
            written.forEach { appender.writeAccessUnit(ByteBuffer.wrap(it)) }
            appender.finish(aacTrack(validFrames = 40 * 1024L, startOffsetUs = 0, primingFrames = 0))
        }

        assertEquals(listOf("ftyp", "mdat", "free", "mdat", "moov"), scanTopLevelBoxes(sparse).map { it.type })
        val view = audioView(movie(sparse))
        assertTrue(view.co64)
        assertArrayEquals(
            longArrayOf(originalSize + 16, originalSize + 16 + written.take(32).sumOf { it.size }),
            view.chunkOffsets,
        )
        assertUnitsEqual(written, readUnits(view) { position, count -> sparse.read(position, count) })
    }

    private fun assertRefused(spec: TestMp4Spec, reason: String) {
        val file = newFile(spec)
        val before = file.readBytes()

        val error = withMp4(file, write = true) { bytes ->
            assertThrows(Mp4FormatException::class.java) { Mp4AudioAppender.begin(bytes) }
        }

        assertEquals(reason, error.reason)
        assertEquals(sha256(before), sha256(file.readBytes()))
    }

    @Test
    fun refusesAFileWithoutMoov() = assertRefused(TestMp4Spec(omitMoov = true), "no-moov")

    @Test
    fun refusesAFileWithTwoMoov() = assertRefused(TestMp4Spec(secondMoov = true), "several-moov")

    @Test
    fun refusesAnOpenEndedBox() = assertRefused(TestMp4Spec(openEndedLast = true), "open-ended-box")

    @Test
    fun refusesAFileWithASoundTrack() = assertRefused(TestMp4Spec(extraTraks = listOf("soun"), nextTrackId = 3), "has-audio-track")

    @Test
    fun refusesTrailingBytes() = assertRefused(TestMp4Spec(trailing = byteArrayOf(1, 2, 3, 4, 5)), "trailing-bytes")

    @Test
    fun finishWithoutUnitsChangesNothing() {
        val file = newFile()
        val before = file.readBytes()

        val error = withMp4(file, write = true) { bytes ->
            Mp4AudioAppender.begin(bytes).use { appender ->
                assertThrows(Mp4FormatException::class.java) { appender.finish(aacTrack(validFrames = 24_000, startOffsetUs = 0)) }
            }
        }

        assertEquals("no-audio", error.reason)
        assertEquals(sha256(before), sha256(file.readBytes()))
    }

    @Test
    fun refusesEmptyAndOversizedUnits() {
        val file = newFile()
        withMp4(file, write = true) { bytes ->
            Mp4AudioAppender.begin(bytes).use { appender ->
                val empty = assertThrows(Mp4FormatException::class.java) { appender.writeAccessUnit(ByteBuffer.allocate(0)) }
                assertEquals("access-unit-size", empty.reason)
                appender.writeAccessUnit(ByteBuffer.wrap(ByteArray(9_000)))
                val mono = assertThrows(Mp4FormatException::class.java) {
                    appender.finish(aacTrack(validFrames = 1_000, startOffsetUs = 0, primingFrames = 0, channels = 1))
                }
                assertEquals("access-unit-size", mono.reason)
            }
        }
        assertEquals("moov", boxes(file).last().type)
    }

    @Test
    fun aFailedWriteAtAnyPointLeavesTheOldMoov() {
        val template = newFile()
        val original = template.readBytes()
        val originalBoxes = boxes(template)
        val oldMoov = originalBoxes.single { it.type == "moov" }
        val videoMdat = originalBoxes.single { it.type == "mdat" }
        // About 800 KB: several buffer flushes before the finish writes.
        val written = units(200) { 4_000 }
        val track = aacTrack(validFrames = 199 * 1024L, startOffsetUs = 0)

        val counting = temp.newFile().apply { writeBytes(original) }
        val totalWrites = withMp4(counting, write = true) { bytes ->
            val failing = FailingMp4Bytes(bytes, Int.MAX_VALUE)
            Mp4AudioAppender.begin(failing).use { appender ->
                written.forEach { appender.writeAccessUnit(ByteBuffer.wrap(it)) }
                appender.finish(track)
            }
            failing.writes
        }
        assertTrue("flushes plus size, moov and retype: $totalWrites", totalWrites >= 6)

        for (failOn in 1..totalWrites) {
            val file = temp.newFile().apply { writeBytes(original) }
            withMp4(file, write = true) { bytes ->
                val appender = Mp4AudioAppender.begin(FailingMp4Bytes(bytes, failOn))
                assertThrows("write $failOn", IOException::class.java) {
                    written.forEach { appender.writeAccessUnit(ByteBuffer.wrap(it)) }
                    appender.finish(track)
                }
                appender.close()
            }

            val after = boxes(file)
            val moov = withMp4(file) { findSingleMoov(scanTopLevelBoxes(it)) }
            assertEquals("write $failOn", oldMoov.offset, moov.offset)
            assertEquals("write $failOn", oldMoov.size, moov.size)
            assertEquals("write $failOn", file.length(), after.last().end)
            assertTrue("write $failOn", movie(file).tracks.none { it.handlerType == "soun" })
            val bytes = file.readBytes()
            assertEquals(
                "write $failOn",
                sha256(original, videoMdat.offset.toInt(), videoMdat.end.toInt()),
                sha256(bytes, videoMdat.offset.toInt(), videoMdat.end.toInt()),
            )
            assertEquals("write $failOn", 1_160_000L, withMp4(file) { readMp4VideoTiming(it) }.lastSampleTimeUs)
        }
    }

    @Test
    fun closeAfterPartialWritesEndsOnABoxBoundary() {
        val file = newFile()
        val originalSize = file.length()
        val oldMoov = boxes(file).single { it.type == "moov" }

        withMp4(file, write = true) { bytes ->
            val appender = Mp4AudioAppender.begin(bytes)
            // About 400 KB, so at least one flush reaches the file.
            units(100) { 4_000 }.forEach { appender.writeAccessUnit(ByteBuffer.wrap(it)) }
            appender.close()
            appender.close()
        }

        val after = boxes(file)
        assertTrue(file.length() > originalSize)
        assertEquals(listOf("ftyp", "mdat", "moov", "mdat"), after.map { it.type })
        assertEquals(originalSize, after.last().offset)
        assertEquals(file.length(), after.last().end)
        assertEquals("moov", after.single { it.offset == oldMoov.offset }.type)
    }

    @Test
    fun moovBeforeMdatIsRetypedInPlace() {
        val file = newFile(TestMp4Spec(moovFirst = true))
        val original = file.readBytes()
        val oldMovie = movie(file)
        val oldMoov = boxes(file).single { it.type == "moov" }
        val track = aacTrack(validFrames = 24_000, startOffsetUs = 0)
        val written = units(unitCountFor(track))

        append(file, written, track)

        val after = boxes(file)
        assertEquals(listOf("ftyp", "free", "mdat", "mdat", "moov"), after.map { it.type })
        assertEquals(oldMoov.offset, after[1].offset)
        val restored = file.readBytes().copyOfRange(0, original.size)
        "moov".toByteArray(Charsets.ISO_8859_1).copyInto(restored, (oldMoov.offset + 4).toInt())
        assertArrayEquals(original, restored)
        val newMovie = movie(file)
        val oldTrak = oldMovie.tracks.single().trak
        val newVideoTrak = newMovie.tracks.first().trak
        assertArrayEquals(
            oldMovie.bytes.copyOfRange(oldTrak.offset, oldTrak.end),
            newMovie.bytes.copyOfRange(newVideoTrak.offset, newVideoTrak.end),
        )
        assertUnitsEqual(written, readUnits(audioView(newMovie), fileReader(file)))
        assertEquals(1_160_000L, withMp4(file) { readMp4VideoTiming(it) }.lastSampleTimeUs)
    }

    @Test
    fun a64BitVideoMdatHeaderIsKept() {
        val file = newFile(TestMp4Spec(largeMdatHeader = true))
        val track = aacTrack(validFrames = 24_000, startOffsetUs = 0)
        val written = units(unitCountFor(track))

        append(file, written, track)

        assertEquals(listOf("ftyp", "mdat", "free", "mdat", "moov"), boxes(file).map { it.type })
        assertUnitsEqual(written, readUnits(audioView(movie(file)), fileReader(file)))
    }

    @Test
    fun descriptorLengths() {
        assertArrayEquals(byteArrayOf(0x02), Mp4AudioAppender.encodeDescriptorLength(2))
        assertArrayEquals(byteArrayOf(0x7F), Mp4AudioAppender.encodeDescriptorLength(127))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x00), Mp4AudioAppender.encodeDescriptorLength(128))
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0x2C), Mp4AudioAppender.encodeDescriptorLength(300))
    }
}
