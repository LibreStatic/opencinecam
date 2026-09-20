/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Mp4AacSourceWindowTest {
    private class Memory(initial: ByteArray) : ProjectMp4File {
        var bytes = initial.copyOf()
        var writes = 0
        var rejectWrites = false
        override val size: Long get() = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        override fun write(offset: Long, bytes: ByteArray) {
            if (rejectWrites) error("Injected write failure")
            writes++
            val end = offset.toInt() + bytes.size
            if (end > this.bytes.size) this.bytes = this.bytes.copyOf(end)
            bytes.copyInto(this.bytes, offset.toInt())
        }
    }
    private fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()
    private fun box(type: String, bytes: ByteArray) = ints(bytes.size + 8) + type.toByteArray() + bytes
    private fun descriptor(tag: Int, payload: ByteArray) = byteArrayOf(tag.toByte(), payload.size.toByte()) + payload
    private fun header(type: String, scale: Int, duration: Int): ByteArray {
        val payload = ByteArray(if (type == "mvhd") 100 else if (type == "tkhd") 84 else 24)
        val b = ByteBuffer.wrap(payload)
        if (type == "tkhd") { b.putInt(12, 1); b.putInt(20, duration) }
        else { b.putInt(12, scale); b.putInt(16, duration) }
        return box(type, payload)
    }
    private fun handler(kind: String) = box("hdlr", ByteArray(8) + kind.toByteArray() + ByteArray(12))
    private fun fixture(packets: Int = 55, video: Boolean = false, scale: Int = 10000, objectType: Int = 2, groups: Boolean = false): ByteArray {
        val asc = byteArrayOf(((objectType shl 3) or 1).toByte(), 0x88.toByte())
        val config = descriptor(4, byteArrayOf(0x40, 0x15) + ByteArray(11) + descriptor(5, asc))
        val esds = box("esds", ints(0) + descriptor(3, byteArrayOf(0, 1, 0) + config + descriptor(6, byteArrayOf(2))))
        val entry = ByteArray(28).also { ByteBuffer.wrap(it).putShort(16, 1).putShort(18, 16).putInt(24, 48000 shl 16) }
        val stsd = box("stsd", ints(0, 1) + box("mp4a", entry + esds))
        val stts = box("stts", ints(0, 1, packets, 1024))
        val stsz = box("stsz", ints(0, 1, packets))
        val co64 = box("co64", ints(0, 1) + ByteBuffer.allocate(8).putLong(32).array())
        val stsc = box("stsc", ints(0, 1, 1, packets, 1))
        val stbl = box("stbl", stsd + stts + stsz + stsc + co64 + if (groups) box("sgpd", ints(0)) else byteArrayOf())
        val audio = box("trak", header("tkhd", scale, scale * 2) + box("mdia", header("mdhd", 48000, packets * 1024) + handler("soun") + box("minf", stbl)))
        val videoTrack = if (video) box("trak", header("tkhd", scale, scale * 2) +
            box("edts", box("elst", ints(0, 2, scale / 4, -1, 0x10000, scale * 7 / 4, 0, 0x10000))) +
            box("mdia", header("mdhd", 90000, 180000) + handler("vide") + box("minf", box("stbl", box("ctts", ints(0, 1, 4, 3000)))))) else byteArrayOf()
        return box("ftyp", "isom".toByteArray() + ByteArray(12)) + box("mdat", ByteArray(64) { it.toByte() }) +
            box("moov", header("mvhd", scale, scale * 2) + audio + videoTrack)
    }
    private fun payloads(data: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>(); var at = 0
        while (at < data.size) {
            val size = ByteBuffer.wrap(data, at, 4).int
            result += String(data, at + 4, 4) to data.copyOfRange(at + 8, at + size)
            at += size
        }
        return result
    }
    private fun child(data: ByteArray, kind: String) = payloads(data).single { it.first == kind }.second
    private fun moov(file: Memory) = child(file.bytes, "moov")
    private val window = AacSourceWindow(48000, 52661, 2048, 55)

    @Test fun exactSourceWindowRetainsEncodedPayloadAndOffsets() {
        val input = fixture(); val file = Memory(input)
        val result = finalizeAacSourceWindow(file, window)
        assertEquals(56320, result.encodedFrames)
        assertEquals(1611, result.remainderFrames)
        assertEquals(6000000, result.movieTimescale)
        assertEquals(52661 * 125L, result.presentationDurationTicks)
        assertArrayEquals(child(input, "mdat"), child(file.bytes, "mdat"))
        assertEquals(2, file.writes)
        val oldMoov = child(input, "moov")
        assertArrayEquals(oldMoov, child(file.bytes, "free"))
        val track = child(moov(file), "trak")
        val originalTable = child(child(child(child(oldMoov, "trak"), "mdia"), "minf"), "stbl")
        val table = child(child(child(track, "mdia"), "minf"), "stbl")
        for (name in listOf("stsz", "stsc", "co64", "stts", "stsd")) assertArrayEquals(child(originalTable, name), child(table, name))
        assertEquals(-1, ByteBuffer.wrap(child(table, "sgpd")).getShort(16).toInt())
        val edit = child(child(track, "edts"), "elst")
        assertEquals(1, edit[0].toInt())
        assertEquals(2048, ByteBuffer.wrap(edit).getLong(16))
    }

    @Test fun preservesVideoMediaTimingAndRescalesItsExistingEmptyEdit() {
        val file = Memory(fixture(video = true))
        finalizeAacSourceWindow(file, window.copy(presentationOffsetUs = 250123))
        val movie = moov(file)
        assertEquals(12000000, ByteBuffer.wrap(child(movie, "mvhd")).getLong(24))
        val tracks = payloads(movie).filter { it.first == "trak" }.map { it.second }
        val video = tracks.single { String(child(child(it, "mdia"), "hdlr"), 8, 4) == "vide" }
        assertEquals(180000, ByteBuffer.wrap(child(child(video, "mdia"), "mdhd")).getInt(16))
        val edit = ByteBuffer.wrap(child(child(video, "edts"), "elst"))
        assertEquals(1500000, edit.getLong(8)); assertEquals(-1, edit.getLong(16))
        assertEquals(10500000, edit.getLong(28)); assertEquals(0, edit.getLong(36))
        val audio = tracks.single { it !== video }
        val audioEdit = ByteBuffer.wrap(child(child(audio, "edts"), "elst"))
        assertEquals(1500738, audioEdit.getLong(8))
        assertEquals(2048, audioEdit.getLong(36))
    }

    @Test fun wideMovieDurationsDoNotOverflowAtLongRecordingLengths() {
        val file = Memory(fixture(video = true))
        val result = finalizeAacSourceWindow(file, window.copy(presentationOffsetUs = 4_000_000_000L))
        assertTrue(result.presentationDurationTicks > 0xffffffffL)
        assertEquals(result.presentationDurationTicks, ByteBuffer.wrap(child(moov(file), "mvhd")).getLong(24))
    }

    @Test fun rejectsMissingTailBeforeWriting() {
        val file = Memory(fixture(packets = 51)); val original = file.bytes.copyOf()
        assertThrows(IllegalArgumentException::class.java) { finalizeAacSourceWindow(file, window.copy(expectedPackets = 51)) }
        assertEquals(0, file.writes); assertArrayEquals(original, file.bytes)
    }

    @Test fun exactBlockInputStillNeedsPrimingCoverage() {
        val file = Memory(fixture(packets = 51))
        assertThrows(IllegalArgumentException::class.java) { finalizeAacSourceWindow(file, window.copy(sourceFrames = 52224, expectedPackets = 51)) }
        assertEquals(0, file.writes)
    }

    @Test fun rejectsWrongSourceRateAndPacketCountWithoutMutating() {
        for (wrong in listOf(window.copy(sampleRateHz = 44100), window.copy(expectedPackets = 54))) {
            val file = Memory(fixture())
            assertThrows(IllegalArgumentException::class.java) { finalizeAacSourceWindow(file, wrong) }
            assertEquals(0, file.writes)
        }
    }

    @Test fun rejectsOtherAacProfilesAndExistingGroupPolicies() {
        for (input in listOf(fixture(objectType = 5), fixture(groups = true))) {
            val file = Memory(input)
            assertThrows(IllegalArgumentException::class.java) { finalizeAacSourceWindow(file, window) }
            assertEquals(0, file.writes)
        }
    }

    @Test fun malformedAndOpenEndedBoxesLeaveInputUntouched() {
        for (input in listOf(byteArrayOf(0, 1, 2), ints(0) + "mdat".toByteArray(), ints(4) + "moov".toByteArray())) {
            val file = Memory(input)
            assertThrows(RuntimeException::class.java) { finalizeAacSourceWindow(file, window) }
            assertEquals(0, file.writes)
        }
    }

    @Test fun duplicateAudioIsRejectedBeforeAnyWrite() {
        val original = fixture(); val movie = child(original, "moov")
        val file = Memory(box("mdat", byteArrayOf(1)) + box("moov", movie + box("trak", child(movie, "trak"))))
        assertThrows(IllegalArgumentException::class.java) { finalizeAacSourceWindow(file, window) }
        assertEquals(0, file.writes)
    }

    @Test fun repeatedFinalizationDoesNotStackAnotherTrim() {
        val file = Memory(fixture())
        finalizeAacSourceWindow(file, window)
        val before = file.bytes.copyOf()
        assertThrows(IllegalArgumentException::class.java) { finalizeAacSourceWindow(file, window) }
        assertArrayEquals(before, file.bytes)
    }

    @Test fun appendFailureNeverRetiresOriginalMoov() {
        val file = Memory(fixture()).apply { rejectWrites = true }
        assertThrows(IllegalStateException::class.java) { finalizeAacSourceWindow(file, window) }
        assertTrue(payloads(file.bytes).any { it.first == "moov" })
        assertFalse(payloads(file.bytes).any { it.first == "free" })
    }

    @Test fun chunkOffsetsOutsideMdatAreRejectedBeforeWrites() {
        val data = fixture()
        val index = data.toString(Charsets.ISO_8859_1).indexOf("co64")
        ByteBuffer.wrap(data).putLong(index + 12, data.size.toLong() + 100)
        val file = Memory(data)
        assertThrows(IllegalArgumentException::class.java) { finalizeAacSourceWindow(file, window) }
        assertEquals(0, file.writes)
    }

    @Test fun chunkSampleCountsMustMatchStszAndStts() {
        val data = fixture()
        val index = data.toString(Charsets.ISO_8859_1).indexOf("stsc")
        ByteBuffer.wrap(data).putInt(index + 16, 54)
        val file = Memory(data)
        assertThrows(IllegalArgumentException::class.java) { finalizeAacSourceWindow(file, window) }
        assertEquals(0, file.writes)
    }

    @Test fun delayedTrackDurationUsesSampleTableInsteadOfCountingEmptyEditTwice() {
        val input = fixture()
        val at = input.toString(Charsets.ISO_8859_1).indexOf("mdhd")
        ByteBuffer.wrap(input).putInt(at + 20, 68326) // 56320 media frames plus the muxer's 12006-frame start offset.
        val file = Memory(input)
        val result = finalizeAacSourceWindow(file, window.copy(presentationOffsetUs = 250123))
        val mdhd = child(child(child(moov(file), "trak"), "mdia"), "mdhd")
        assertEquals(1, mdhd[0].toInt())
        assertEquals(56320, ByteBuffer.wrap(mdhd).getLong(24))
        assertEquals(56320, result.encodedFrames)
    }

    @Test fun inspectionReadsExactAudioPresentationInsteadOfRawPacketPreroll() {
        val file = Memory(fixture(video = true))
        val requested = window.copy(presentationOffsetUs = 250123)
        finalizeAacSourceWindow(file, requested)
        assertEquals(requested, inspectAacSourceWindow(file))
    }

    @Test fun inspectionRequiresExplicitWindowGroups() {
        assertThrows(NoSuchElementException::class.java) { inspectAacSourceWindow(Memory(fixture())) }
    }

    @Test fun inspectionRejectsInvalidEditRateWithoutWriting() {
        val file = Memory(fixture())
        finalizeAacSourceWindow(file, window)
        val at = file.bytes.toString(Charsets.ISO_8859_1).lastIndexOf("elst")
        ByteBuffer.wrap(file.bytes).putInt(at + 28, 0)
        val writes = file.writes
        assertThrows(IllegalArgumentException::class.java) { inspectAacSourceWindow(file) }
        assertEquals(writes, file.writes)
    }

    @Test fun sourceWindowRejectsInvalidAndOverflowingAccounting() {
        assertThrows(IllegalArgumentException::class.java) { window.copy(sourceFrames = 0) }
        assertThrows(IllegalArgumentException::class.java) { window.copy(primingFrames = -1) }
        assertThrows(IllegalArgumentException::class.java) { window.copy(presentationOffsetUs = -1) }
        assertThrows(ArithmeticException::class.java) { window.copy(sourceFrames = Long.MAX_VALUE) }
    }
}
