/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.media.mux.Mp4Bytes
import com.librestatic.opencinecam.media.mux.Mp4CammAppender
import com.librestatic.opencinecam.motion.CammSeries
import com.librestatic.opencinecam.motion.MappedSeries
import com.librestatic.opencinecam.motion.MotionSpoolFormat
import com.librestatic.opencinecam.motion.MotionSpoolWriter
import com.librestatic.opencinecam.motion.MotionTakeInfo
import com.librestatic.opencinecam.motion.MotionTakeRecorder
import com.librestatic.opencinecam.motion.MotionTakeRoute
import com.librestatic.opencinecam.motion.MotionTakeTiming
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TakeCammAppendTest {
    @get:Rule val temp = TemporaryFolder()

    private class Recorded(val timeUs: Long, val type: Int)

    private class RecordingSink : CammPacketSink {
        val packets = ArrayList<Recorded>()
        override fun vector(timeUs: Long, type: Int, a: Float, b: Float, c: Float) {
            packets += Recorded(timeUs, type)
        }
        override fun exposure(timeUs: Long, exposureNs: Int, skewNs: Int) {
            packets += Recorded(timeUs, Mp4CammAppender.TYPE_EXPOSURE)
        }
    }

    private fun series(vararg timesUs: Long) =
        MappedSeries(timesUs, FloatArray(timesUs.size), FloatArray(timesUs.size), FloatArray(timesUs.size))

    @Test fun packetsMergeInNonDecreasingTimeWithTiesInTypeOrder() {
        val camm = CammSeries(
            gyro = series(0, 1_000, 2_000, 3_000),
            accel = series(1_000, 2_500),
            mag = series(2_000),
            exposureFileUs = longArrayOf(0, 2_000),
            exposureNs = intArrayOf(10_000_000, 8_000_000),
            skewNs = intArrayOf(0, 0),
        )
        val sink = RecordingSink()
        assertEquals(9, mergeCammPackets(camm, sink))
        val e = Mp4CammAppender.TYPE_EXPOSURE
        val g = Mp4CammAppender.TYPE_GYRO
        val a = Mp4CammAppender.TYPE_ACCELERATION
        val m = Mp4CammAppender.TYPE_MAGNETIC_FIELD
        assertEquals(listOf(0L, 0L, 1_000L, 1_000L, 2_000L, 2_000L, 2_000L, 2_500L, 3_000L), sink.packets.map { it.timeUs })
        assertEquals(listOf(e, g, g, a, e, g, m, a, g), sink.packets.map { it.type })
    }

    @Test fun packetsBeforeTheMovieStartAreLeftOut() {
        val camm = CammSeries(
            gyro = series(-2_000, -1_000, 0, 1_000),
            accel = series(-500, 500),
            mag = MappedSeries.EMPTY,
            exposureFileUs = longArrayOf(-3_000, 1_000),
            exposureNs = intArrayOf(1, 2),
            skewNs = intArrayOf(0, 0),
        )
        val sink = RecordingSink()
        assertEquals(4, mergeCammPackets(camm, sink))
        assertEquals(listOf(0L, 500L, 1_000L, 1_000L), sink.packets.map { it.timeUs })
        assertEquals(Mp4CammAppender.TYPE_EXPOSURE, sink.packets[2].type)
    }

    @Test fun anEmptySeriesLeavesTheFileUntouched() {
        val file = MemoryMp4(videoOnlyMp4())
        val before = file.bytes()
        val none = CammSeries(MappedSeries.EMPTY, MappedSeries.EMPTY, MappedSeries.EMPTY, LongArray(0), IntArray(0), IntArray(0))
        assertEquals(0, appendCamm(file, none))
        assertTrue(before.contentEquals(file.bytes()))
    }

    @Test fun runningOutOfMemoryWhileReadingTheMovieLeavesTheFileAsItWas() {
        val file = MemoryMp4(videoOnlyMp4())
        val before = file.bytes()
        // Mp4CammAppender.begin reads the movie: the allocation that fails there is the one this simulates.
        val starved = object : Mp4Bytes by file {
            override fun readFully(position: Long, buffer: ByteBuffer) = throw OutOfMemoryError("simulated")
        }
        val camm = CammSeries(series(0, 1_000), MappedSeries.EMPTY, MappedSeries.EMPTY, LongArray(0), IntArray(0), IntArray(0))
        assertEquals(0, appendCamm(starved, camm))
        assertTrue(before.contentEquals(file.bytes()))
    }

    /**
     * A 2 s take at 30 fps whose muxer put the first frame 20 ms into the file: the spool is rendered as the stop does,
     * the CAMM track appended to a real MP4, and the gyroscope packets read back from the track land on the Gyroflow
     * log's rows.
     */
    @Test fun spoolRendersToALogAndACammTrackOnTheSameTimes() {
        val startNs = 40_000_000_000L
        val frameNs = 33_333_333L
        val spool = temp.newFile("take.ocmo")
        MotionSpoolWriter(FileOutputStream(spool)).apply {
            exposure(startNs, 10_000_000, 9_000_000)
            for (i in 0 until 60) frame(startNs + i * frameNs, 5_000_000_000L + i * frameNs)
            // Gyroscope at 200 Hz from 1 ms after the first frame, accelerometer at 100 Hz.
            for (i in 0 until 380) {
                val t = startNs + 1_000_000L + i * 5_000_000L
                sample(MotionSpoolFormat.KIND_GYRO, t, 0.1f, 0.2f, 0.3f)
                if (i % 2 == 0) sample(MotionSpoolFormat.KIND_ACCEL, t, 0f, 9.8f, 0f)
            }
            exposure(startNs + 30 * frameNs, 5_000_000, 9_000_000)
            close()
        }
        val info = MotionTakeInfo(
            takeId = "take-1",
            videoFileName = "OCC_0001.mp4",
            orientation = "yxz",
            readoutDirection = 0,
            frontFacing = false,
            sensorOrientationDegrees = 90,
            storedRotationDegrees = 0,
            cameraClockRealtime = true,
            clockOffsetNs = 0L,
            startedAtUnixMs = 1_700_000_000_000L,
            firmware = "OpenCineCam test",
            route = MotionTakeRoute.GPU,
            captureFps = 30.0,
            gyroMaxHz = 200.0,
            accelerometer = true,
            accelMaxHz = 100.0,
        )
        val outputs = MotionTakeRecorder.renderSpool(spool, info, MotionTakeTiming.EncodedFrames(20_000L), temp.root.resolve("take.gcsv"), withCamm = true)
        assertNotNull(outputs)
        outputs!!
        assertEquals(1_000L, outputs.cammBinUs)
        assertTrue(outputs.synced)

        val lines = outputs.gcsv.readLines()
        val rows = lines.drop(lines.indexOf("t,gx,gy,gz,ax,ay,az") + 1).filter { it.isNotEmpty() }
        val rowTimes = rows.map { it.substringBefore(',').toLong() }
        assertEquals(380, rowTimes.size)
        assertEquals(21_000L, rowTimes.first())
        assertEquals(21_000L + 379 * 5_000L, rowTimes.last())

        val file = MemoryMp4(videoOnlyMp4())
        val written = appendCamm(file, outputs.camm!!)
        assertEquals(380 + 190 + 2, written)

        val camm = readCammTrack(file.bytes())
        assertEquals(written, camm.size)
        assertTrue("times never go back", camm.zipWithNext().all { (a, b) -> b.timeUs >= a.timeUs })
        val gyro = camm.filter { it.type == Mp4CammAppender.TYPE_GYRO }
        assertEquals(rowTimes.first(), gyro.first().timeUs)
        assertEquals(rowTimes.last(), gyro.last().timeUs)
        assertEquals(rowTimes, gyro.map { it.timeUs })
        assertEquals(rowTimes.last(), camm.last().timeUs)
        val exposures = camm.filter { it.type == Mp4CammAppender.TYPE_EXPOSURE }.map { it.timeUs }
        assertEquals("the first frame and frame 30, both 20 ms late", listOf(20_000L, 20_000L + 999_999L), exposures)
        assertEquals(Mp4CammAppender.TYPE_EXPOSURE, camm.first().type)
    }

    // A minimal MP4: ftyp, mdat with 60 one-byte samples, then a moov with one 30 fps video track, all at 1 MHz.
    private fun videoOnlyMp4(): ByteArray {
        val samples = 60
        val delta = 33_333L
        val ftyp = box("ftyp") { fourcc("isom"); u32(0x200); fourcc("isom"); fourcc("mp42") }
        val mdat = box("mdat") { write(ByteArray(samples) { it.toByte() }) }
        val dataOffset = (ftyp.size + 8).toLong()
        val duration = samples * delta
        val matrix: DataOutputStream.() -> Unit = { intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).forEach { writeInt(it) } }
        val moov = box("moov") {
            write(fullBox("mvhd", 0, 0) {
                u32(0); u32(0); u32(TIMESCALE); u32(duration)
                u32(0x10000); writeShort(0x100); writeShort(0); u32(0); u32(0)
                matrix()
                repeat(6) { u32(0) }
                u32(2)
            })
            write(box("trak") {
                write(fullBox("tkhd", 0, 3) {
                    u32(0); u32(0); u32(1); u32(0); u32(duration); u32(0); u32(0)
                    writeShort(0); writeShort(0); writeShort(0); writeShort(0)
                    matrix()
                    u32(1920L shl 16); u32(1080L shl 16)
                })
                write(box("mdia") {
                    write(fullBox("mdhd", 0, 0) { u32(0); u32(0); u32(TIMESCALE); u32(duration); writeShort(0x55C4); writeShort(0) })
                    write(fullBox("hdlr", 0, 0) { u32(0); fourcc("vide"); u32(0); u32(0); u32(0); write("Video\u0000".toByteArray()) })
                    write(box("minf") {
                        write(fullBox("vmhd", 0, 1) { repeat(4) { writeShort(0) } })
                        write(box("dinf") { write(fullBox("dref", 0, 0) { u32(1); write(fullBox("url ", 0, 1) {}) }) })
                        write(box("stbl") {
                            write(fullBox("stsd", 0, 0) { u32(1); write(box("avc1") { write(ByteArray(78)) }) })
                            write(fullBox("stts", 0, 0) { u32(1); u32(samples.toLong()); u32(delta) })
                            write(fullBox("stsc", 0, 0) { u32(1); u32(1); u32(samples.toLong()); u32(1) })
                            write(fullBox("stsz", 0, 0) { u32(1); u32(samples.toLong()) })
                            write(fullBox("stco", 0, 0) { u32(1); u32(dataOffset) })
                        })
                    })
                })
            })
        }
        return ftyp + mdat + moov
    }

    private fun DataOutputStream.u32(value: Long) = writeInt(value.toInt())
    private fun DataOutputStream.u32(value: Int) = writeInt(value)
    private fun DataOutputStream.fourcc(type: String) = write(type.toByteArray(Charsets.ISO_8859_1))

    private fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).apply(body).flush()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(8 + payload.size())
            fourcc(type)
            write(payload.toByteArray())
        }.flush()
        return out.toByteArray()
    }

    private fun fullBox(type: String, version: Int, flags: Int, body: DataOutputStream.() -> Unit): ByteArray =
        box(type) { writeInt((version shl 24) or flags); body() }

    private class Box(val type: String, val payload: Int, val end: Int)

    private fun u32(d: ByteArray, at: Int): Long = ByteBuffer.wrap(d, at, 4).int.toLong() and 0xFFFFFFFFL
    private fun s32(d: ByteArray, at: Int): Int = ByteBuffer.wrap(d, at, 4).int

    private fun children(d: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = start
        while (at + 8 <= end) {
            var size = u32(d, at)
            var header = 8
            if (size == 1L) {
                size = ByteBuffer.wrap(d, at + 8, 8).long
                header = 16
            } else if (size == 0L) {
                size = (end - at).toLong()
            }
            out += Box(String(d, at + 4, 4, Charsets.ISO_8859_1), at + header, (at + size).toInt())
            at += size.toInt()
        }
        return out
    }

    private fun child(d: ByteArray, parent: Box, vararg path: String): Box? {
        var box = parent
        for (type in path) box = children(d, box.payload, box.end).firstOrNull { it.type == type } ?: return null
        return box
    }

    /** Every CAMM packet of the file's track, with the presentation time its edit list and `stts` give it. */
    private fun readCammTrack(d: ByteArray): List<Recorded> {
        val moov = children(d, 0, d.size).last { it.type == "moov" }
        val movieTimescale = u32(d, child(d, moov, "mvhd")!!.payload + 12)
        val trak = children(d, moov.payload, moov.end).filter { it.type == "trak" }.single { trak ->
            val stsd = child(d, trak, "mdia", "minf", "stbl", "stsd")!!
            children(d, stsd.payload + 8, stsd.end).first().type == "camm"
        }
        // An empty edit (media time -1) delays the track; its duration is in the movie timescale.
        var startUs = 0L
        child(d, trak, "edts", "elst")?.let { elst ->
            for (i in 0 until u32(d, elst.payload + 4).toInt()) {
                val at = elst.payload + 8 + i * 12
                if (s32(d, at + 4) == -1) startUs += u32(d, at) * 1_000_000L / movieTimescale
            }
        }
        val mediaTimescale = u32(d, child(d, trak, "mdia", "mdhd")!!.payload + 12)
        val stbl = child(d, trak, "mdia", "minf", "stbl")!!
        val stts = child(d, stbl, "stts")!!
        val times = ArrayList<Long>()
        var t = 0L
        for (i in 0 until u32(d, stts.payload + 4).toInt()) {
            val count = u32(d, stts.payload + 8 + i * 8).toInt()
            val delta = u32(d, stts.payload + 12 + i * 8)
            repeat(count) {
                times += startUs + t * 1_000_000L / mediaTimescale
                t += delta
            }
        }
        val stsz = child(d, stbl, "stsz")!!
        val fixed = u32(d, stsz.payload + 4).toInt()
        val count = u32(d, stsz.payload + 8).toInt()
        val sizes = IntArray(count) { if (fixed != 0) fixed else u32(d, stsz.payload + 12 + it * 4).toInt() }
        val stsc = child(d, stbl, "stsc")!!
        val stscRuns = (0 until u32(d, stsc.payload + 4).toInt()).map { u32(d, stsc.payload + 8 + it * 12) to u32(d, stsc.payload + 12 + it * 12) }
        val stco = child(d, stbl, "stco")
        val offsets = if (stco != null) {
            LongArray(u32(d, stco.payload + 4).toInt()) { u32(d, stco.payload + 8 + it * 4) }
        } else {
            val co64 = child(d, stbl, "co64")!!
            LongArray(u32(d, co64.payload + 4).toInt()) { ByteBuffer.wrap(d, co64.payload + 8 + it * 8, 8).long }
        }
        assertEquals(count, times.size)
        val out = ArrayList<Recorded>()
        var sample = 0
        for (chunk in 1..offsets.size) {
            val perChunk = stscRuns.last { it.first <= chunk }.second.toInt()
            var position = offsets[chunk - 1].toInt()
            repeat(perChunk) {
                // A CAMM packet starts with a reserved u16 and its type, little-endian.
                out += Recorded(times[sample], ByteBuffer.wrap(d, position + 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
                position += sizes[sample]
                sample++
            }
        }
        assertEquals(count, sample)
        return out
    }

    /** A growable in-memory file. */
    private class MemoryMp4(initial: ByteArray) : Mp4Bytes {
        private var data = initial.copyOf()
        private var length = initial.size

        fun bytes(): ByteArray = data.copyOf(length)

        override fun size(): Long = length.toLong()

        override fun readFully(position: Long, buffer: ByteBuffer) {
            val count = buffer.remaining()
            if (position + count > length) throw EOFException("The file ends at $length")
            buffer.put(data, position.toInt(), count)
        }

        override fun writeFully(position: Long, buffer: ByteBuffer) {
            val end = position.toInt() + buffer.remaining()
            if (end > data.size) data = data.copyOf(maxOf(end, data.size * 2))
            buffer.get(data, position.toInt(), buffer.remaining())
            length = maxOf(length, end)
        }

        override fun sync() {}
    }

    private companion object {
        const val TIMESCALE = 1_000_000L
    }
}
