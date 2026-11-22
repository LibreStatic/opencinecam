// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.export

import com.librestatic.opencinecam.media.ocraw.CinemaDngWriter
import com.librestatic.opencinecam.media.ocraw.OcrawChunkId
import com.librestatic.opencinecam.media.ocraw.OcrawCodec
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawImuSamples
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamWriter
import com.librestatic.opencinecam.media.ocraw.OcrawTimeBase
import com.librestatic.opencinecam.media.raw.NativeRawCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import kotlin.math.ceil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * OCC-PLAN-071 §8 route 2: [CinemaDngExporter] writes the golden CinemaDNG folder of the `:media`
 * fixture take (built by `tools/regen-cinemadng-golden.sh`) through a destination, and cleans up
 * whenever it stops part-way.
 */
class CinemaDngExporterTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/cinemadng-export-test").apply { mkdirs() })

    private val goldenDir = File("../media/src/test/resources/ocraw/cinemadng")
    private val take = File(goldenDir, "$TAKE.ocraw")
    private val expected = File(goldenDir, TAKE)

    /**
     * The frames the JVM converts: 3 is block-pack and 1 and 4 are LJ92 not in full tiles (native codec
     * only), 5 an unknown encoding.
     */
    private val convertedFrames = if (NativeRawCodec.available) listOf(0, 1, 2, 3, 4, 6, 7) else listOf(0, 2, 6, 7)

    @Test
    fun exportWritesTheGoldenFolder() = withTake { channel ->
        val destination = FakeDestination()
        val progress = mutableListOf<CinemaDngExportProgress>()
        val outcome = CinemaDngExporter(channel, TAKE).run(destination, onProgress = { progress += it })

        outcome as CinemaDngExportOutcome.Exported
        val target = destination.targets.single()
        assertEquals(TAKE, target.folder)
        assertTrue(target.published)
        assertFalse(target.discarded)
        assertTrue(outcome.noMedia)
        assertTrue(target.noMedia)
        val names = convertedFrames.map { "${TAKE}_%06d.dng".format(it) }
        assertEquals(names + "$TAKE.wav", target.files.keys.toList())
        for (name in names) {
            assertArrayEquals(name, File(expected, name).readBytes(), target.files.getValue(name))
            assertEquals(CinemaDngExporter.DNG_MIME_TYPE, target.mimeTypes.getValue(name))
        }
        assertArrayEquals(File(expected, "$TAKE.wav").readBytes(), target.files.getValue("$TAKE.wav"))
        assertEquals(CinemaDngExporter.WAV_MIME_TYPE, target.mimeTypes.getValue("$TAKE.wav"))

        assertEquals(convertedFrames.size, outcome.frames)
        assertEquals(FRAMES - convertedFrames.size, outcome.skipped)
        assertTrue(outcome.audio)
        assertFalse(outcome.audioOmitted)
        assertEquals(target.files.values.sumOf { it.size.toLong() }, outcome.bytes)
        assertEquals("CinemaDNG/$TAKE", outcome.location)

        assertEquals(CinemaDngExportProgress(0, FRAMES, 0, 0), progress.first())
        assertEquals(FRAMES, progress.last().done)
        assertEquals(FRAMES - convertedFrames.size, progress.last().skipped)
        assertEquals(1f, progress.last().fraction)
    }

    @Test
    fun planMatchesWhatTheExportWrites() = withTake { channel ->
        val plan = requireNotNull(CinemaDngExporter(channel, TAKE).plan())
        assertEquals(FRAMES, plan.frames)
        assertEquals(convertedFrames.size, plan.convertible)
        assertEquals(FRAMES - convertedFrames.size, plan.skipped)
        assertTrue(plan.hasAudio)
        assertEquals(File(expected, "$TAKE.wav").length(), plan.wavBytes)
        val written = convertedFrames.sumOf { File(expected, "${TAKE}_%06d.dng".format(it)).length() } +
            File(expected, "$TAKE.wav").length()
        assertEquals(written, plan.bytes(compress = false))
        assertFalse(plan.isEstimate(compress = false))
        assertTrue(plan.reencodedFrames > 0)
        assertTrue(plan.isEstimate(compress = true))
        assertTrue(plan.bytes(compress = true) < plan.bytes(compress = false))
    }

    @Test
    fun lj92NotInFullTilesIsPlannedAndWrittenAsReencoded() = withTake { channel ->
        fun size(frame: Int) = File(expected, "${TAKE}_%06d.dng".format(frame)).length()
        val plan = requireNotNull(CinemaDngExporter(channel, TAKE, decoderFactory = { GoldenDecoder() }).plan())
        assertEquals(FRAMES - 1, plan.convertible)
        // Only frame 7's LJ92 strips are full tiles and pass through; 1 and 4 count with the frames to re-encode.
        assertEquals(size(7), plan.fixedBytes)
        assertEquals(listOf(0, 1, 2, 3, 4, 6).sumOf(::size), plan.reencodedBytes)
        assertEquals(6, plan.reencodedFrames)
        val wav = File(expected, "$TAKE.wav").length()
        assertEquals(size(7) + ceil(plan.reencodedBytes * CinemaDngExportPlan.LJ92_ESTIMATE).toLong() + wav, plan.bytes(compress = true))

        val destination = FakeDestination()
        val outcome = CinemaDngExporter(channel, TAKE, decoderFactory = { GoldenDecoder() }).run(destination)
        outcome as CinemaDngExportOutcome.Exported
        assertEquals(plan.bytes(compress = false), outcome.bytes)
        val files = destination.targets.single().files
        for (frame in listOf(0, 1, 2, 3, 4, 6, 7)) {
            val name = "${TAKE}_%06d.dng".format(frame)
            assertArrayEquals(name, File(expected, name).readBytes(), files.getValue(name))
        }
    }

    @Test
    fun damagedFrameIsSkippedAndCounted() {
        val file = temp.newFile("damaged.ocraw")
        take.copyTo(file, overwrite = true)
        val frame = RandomAccessFile(take, "r").use { OcrawStreamReader(it.channel).indexFrames().frames[0] }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(frame.fileOffset + OcrawCodec.CHUNK_HEADER_BYTES + OcrawFrameHeader.BYTES + 100)
            raf.write(0x55)
        }
        RandomAccessFile(file, "r").use { raf ->
            val destination = FakeDestination()
            val outcome = CinemaDngExporter(raf.channel, TAKE).run(destination)
            outcome as CinemaDngExportOutcome.Exported
            val target = destination.targets.single()
            assertFalse("the damaged frame leaves no file", "${TAKE}_000000.dng" in target.files)
            assertEquals(convertedFrames.size - 1, outcome.frames)
            assertEquals(FRAMES - convertedFrames.size + 1, outcome.skipped)
            assertArrayEquals(File(expected, "${TAKE}_000002.dng").readBytes(), target.files.getValue("${TAKE}_000002.dng"))
        }
    }

    @Test
    fun cancelDiscardsThePartialFolder() = withTake { channel ->
        val destination = FakeDestination()
        var cancelled = false
        val outcome = CinemaDngExporter(channel, TAKE).run(destination, isCancelled = { cancelled },
            onProgress = { if (it.done == 2) cancelled = true })
        assertEquals(CinemaDngExportOutcome.Stopped(CinemaDngStopReason.CANCELLED, cleaned = true, location = "CinemaDNG/$TAKE"), outcome)
        val target = destination.targets.single()
        assertTrue(target.discarded)
        assertFalse(target.published)
        assertTrue(target.files.isEmpty())
    }

    @Test
    fun cancelBeforeStartingCreatesNothing() = withTake { channel ->
        val destination = FakeDestination()
        val outcome = CinemaDngExporter(channel, TAKE).run(destination, isCancelled = { true })
        assertEquals(CinemaDngExportOutcome.Stopped(CinemaDngStopReason.CANCELLED, cleaned = true), outcome)
        assertTrue(destination.targets.isEmpty())
    }

    @Test
    fun failedDiscardIsReported() = withTake { channel ->
        val destination = FakeDestination(discardSucceeds = false)
        var cancelled = false
        val outcome = CinemaDngExporter(channel, TAKE).run(destination, isCancelled = { cancelled },
            onProgress = { if (it.done == 1) cancelled = true })
        outcome as CinemaDngExportOutcome.Stopped
        assertFalse(outcome.cleaned)
        assertEquals("CinemaDNG/$TAKE", outcome.location)
    }

    @Test
    fun writeFailureStopsAndDiscards() = withTake { channel ->
        val destination = FakeDestination(failOn = "${TAKE}_000002.dng")
        val outcome = CinemaDngExporter(channel, TAKE).run(destination)
        outcome as CinemaDngExportOutcome.Stopped
        assertEquals(CinemaDngStopReason.FAILED, outcome.reason)
        assertTrue(outcome.cleaned)
        assertEquals("disk full", outcome.detail)
        assertTrue(destination.targets.single().discarded)
        assertTrue(destination.targets.single().files.isEmpty())
    }

    @Test
    fun openFailureIsAStopWithNothingToClean() = withTake { channel ->
        val destination = object : CinemaDngDestination {
            override fun freeBytes(): Long? = null
            override fun open(takeName: String): CinemaDngTarget = throw IOException("no folder")
        }
        assertEquals(CinemaDngExportOutcome.Stopped(CinemaDngStopReason.FAILED, cleaned = true, detail = "no folder"),
            CinemaDngExporter(channel, TAKE).run(destination))
    }

    @Test
    fun notEnoughSpaceRefusesBeforeCreatingAnything() = withTake { channel ->
        val plan = requireNotNull(CinemaDngExporter(channel, TAKE).plan())
        val free = plan.bytes(compress = false) + CinemaDngExportPlan.SPACE_RESERVE_BYTES - 1
        val destination = FakeDestination(free = free)
        assertEquals(CinemaDngExportOutcome.Refused(CinemaDngRefusal.NO_SPACE, plan.bytes(compress = false), free),
            CinemaDngExporter(channel, TAKE).run(destination))
        assertTrue(destination.targets.isEmpty())
    }

    @Test
    fun exactSpaceAndUnknownSpaceBothProceed() = withTake { channel ->
        val plan = requireNotNull(CinemaDngExporter(channel, TAKE).plan())
        val exact = FakeDestination(free = plan.bytes(compress = false) + CinemaDngExportPlan.SPACE_RESERVE_BYTES)
        assertTrue(CinemaDngExporter(channel, TAKE).run(exact) is CinemaDngExportOutcome.Exported)
        val unknown = FakeDestination(free = null)
        assertTrue(CinemaDngExporter(channel, TAKE).run(unknown) is CinemaDngExportOutcome.Exported)
    }

    @Test
    fun planSpaceRules() {
        val plan = CinemaDngExportPlan(TAKE, frames = 4, convertible = 3, fixedBytes = 1_000, reencodedBytes = 10_000,
            reencodedFrames = 2, wavBytes = 500)
        assertEquals(11_500, plan.bytes(compress = false))
        assertEquals(1_000 + 6_000 + 500L, plan.bytes(compress = true))
        assertTrue(plan.fits(compress = false, freeBytes = null))
        assertTrue(plan.fits(compress = false, freeBytes = 11_500 + CinemaDngExportPlan.SPACE_RESERVE_BYTES))
        assertFalse(plan.fits(compress = false, freeBytes = 11_499 + CinemaDngExportPlan.SPACE_RESERVE_BYTES))
        assertTrue(plan.fits(compress = true, freeBytes = 7_500 + CinemaDngExportPlan.SPACE_RESERVE_BYTES))
        assertEquals(1, plan.skipped)
        assertFalse(plan.copy(reencodedFrames = 0, reencodedBytes = 0).isEstimate(compress = true))
        assertFalse(plan.copy(wavBytes = null).hasAudio)
    }

    @Test
    fun recordingRefusesTheExport() = withTake { channel ->
        val destination = FakeDestination()
        assertEquals(CinemaDngExportOutcome.Refused(CinemaDngRefusal.RECORDING),
            CinemaDngExporter(channel, TAKE).run(destination, isRecording = { true }))
        assertTrue(destination.targets.isEmpty())
    }

    @Test
    fun recordingStartingMidExportStopsAndDiscards() = withTake { channel ->
        val destination = FakeDestination()
        var recording = false
        val outcome = CinemaDngExporter(channel, TAKE).run(destination, isRecording = { recording },
            onProgress = { if (it.done == 3) recording = true })
        outcome as CinemaDngExportOutcome.Stopped
        assertEquals(CinemaDngStopReason.RECORDING, outcome.reason)
        assertTrue(outcome.cleaned)
        assertTrue(destination.targets.single().discarded)
    }

    @Test
    fun refusedNoMediaIsReportedButExports() = withTake { channel ->
        val outcome = CinemaDngExporter(channel, TAKE).run(FakeDestination(noMediaSucceeds = false))
        outcome as CinemaDngExportOutcome.Exported
        assertFalse(outcome.noMedia)
    }

    @Test
    fun unreadableTakeIsRefused() {
        val file = temp.newFile("empty.ocraw")
        file.writeBytes(ByteArray(64))
        RandomAccessFile(file, "r").use { raf ->
            val exporter = CinemaDngExporter(raf.channel, "empty")
            assertNull(exporter.plan())
            assertEquals(CinemaDngExportOutcome.Refused(CinemaDngRefusal.UNREADABLE), exporter.run(FakeDestination()))
        }
    }

    @Test
    fun takeAndFolderNames() {
        assertEquals("A001_C001", CinemaDngExporter.takeName("A001_C001.ocraw"))
        assertEquals("A001_C001", CinemaDngExporter.takeName("Documents/OpenCineCam/RAW/A001_C001.OCRAW"))
        assertEquals(".ocraw", CinemaDngExporter.takeName(".ocraw"))
        assertEquals("clip.mp4", CinemaDngExporter.takeName("clip.mp4"))
        assertEquals("A", CinemaDngExporter.folderName("A") { false })
        assertEquals("A_2", CinemaDngExporter.folderName("A") { it == "A" })
        assertEquals("A_4", CinemaDngExporter.folderName("A") { it in setOf("A", "A_2", "A_3") })
        try {
            CinemaDngExporter.folderName("A") { true }
            throw AssertionError("every name taken must fail")
        } catch (_: IOException) {
        }
    }

    @Test
    fun aTakeWithMotionDataExportsItsGyroflowLog() {
        val file = takeWithMotion()
        RandomAccessFile(file, "r").use { raf ->
            val destination = FakeDestination()
            val outcome = CinemaDngExporter(raf.channel, TAKE).run(destination)
            outcome as CinemaDngExportOutcome.Exported
            assertTrue(outcome.motionLog)
            val target = destination.targets.single()
            val gcsv = "$TAKE.gcsv"
            assertEquals(convertedFrames.map { "${TAKE}_%06d.dng".format(it) } + "$TAKE.wav" + gcsv, target.files.keys.toList())
            assertEquals(CinemaDngExporter.GCSV_MIME_TYPE, target.mimeTypes.getValue(gcsv))
            assertEquals(target.files.values.sumOf { it.size.toLong() }, outcome.bytes)
            // The pictures are not compared with the golden folder: the rewrite renumbers the chunks after the
            // first frame, which the DNGs' frame metadata references by sequence.

            val reader = OcrawStreamReader(raf.channel)
            val rendered = java.io.StringWriter()
            assertTrue(OcrawGyroflowLog.render(rendered, reader, reader.indexFrames(), "$TAKE.ocraw")!!.rows > 0)
            val text = String(target.files.getValue(gcsv), Charsets.UTF_8)
            assertEquals(rendered.toString(), text)
            assertTrue(text.startsWith("GYROFLOW IMU LOG\n"))
            assertTrue("videofilename,$TAKE.ocraw\n" in text)
        }
    }

    @Test
    fun aMotionLogThatCannotBeWrittenCostsOnlyTheLog() {
        val file = takeWithMotion()
        RandomAccessFile(file, "r").use { raf ->
            val destination = FakeDestination(failOn = "$TAKE.gcsv")
            val outcome = CinemaDngExporter(raf.channel, TAKE).run(destination)
            outcome as CinemaDngExportOutcome.Exported
            assertFalse(outcome.motionLog)
            val target = destination.targets.single()
            assertTrue(target.published)
            assertFalse("$TAKE.gcsv" in target.files)
            assertEquals(convertedFrames.size, outcome.frames)
            assertTrue(outcome.audio)
        }
    }

    @Test
    fun aMotionLogNeverUsesTheSpaceReserve() {
        val file = takeWithMotion()
        RandomAccessFile(file, "r").use { raf ->
            val plan = requireNotNull(CinemaDngExporter(raf.channel, TAKE).plan())
            // Room for the DNGs, the WAV and the reserve, not one byte more: the log is left out.
            val destination = FakeDestination(free = plan.bytes(compress = false) + CinemaDngExportPlan.SPACE_RESERVE_BYTES)
            val outcome = CinemaDngExporter(raf.channel, TAKE).run(destination)
            outcome as CinemaDngExportOutcome.Exported
            assertFalse(outcome.motionLog)
            val target = destination.targets.single()
            assertTrue(target.published)
            assertFalse("$TAKE.gcsv" in target.files)
            assertEquals(target.files.values.sumOf { it.size.toLong() }, outcome.bytes)
            assertEquals(plan.bytes(compress = false), outcome.bytes)

            // A few bytes over the reserve are not enough for the log either: it is started and removed.
            val tight = FakeDestination(free = plan.bytes(compress = false) + CinemaDngExportPlan.SPACE_RESERVE_BYTES + 64)
            val tightOutcome = CinemaDngExporter(raf.channel, TAKE).run(tight)
            tightOutcome as CinemaDngExportOutcome.Exported
            assertFalse(tightOutcome.motionLog)
            assertFalse("$TAKE.gcsv" in tight.targets.single().files)
            assertTrue("$TAKE.gcsv" in tight.targets.single().failed)
        }
    }

    @Test
    fun cancellingDuringTheMotionLogStopsItBeforeItIsRendered() {
        val file = takeWithMotion()
        RandomAccessFile(file, "r").use { raf ->
            var cancelled = false
            val destination = FakeDestination(onWrite = { if (it == "$TAKE.gcsv") cancelled = true })
            val outcome = CinemaDngExporter(raf.channel, TAKE).run(destination, isCancelled = { cancelled })
            outcome as CinemaDngExportOutcome.Stopped
            assertEquals(CinemaDngStopReason.CANCELLED, outcome.reason)
            assertTrue(outcome.cleaned)
            val target = destination.targets.single()
            assertTrue(target.discarded)
            assertFalse(target.published)
            // The render gave up at its first sample chunk instead of finishing the log.
            assertTrue("$TAKE.gcsv" in target.failed)
        }
    }

    @Test
    fun recordingStartingDuringTheMotionLogStopsTheExport() {
        val file = takeWithMotion()
        RandomAccessFile(file, "r").use { raf ->
            var recording = false
            val destination = FakeDestination(onWrite = { if (it == "$TAKE.gcsv") recording = true })
            val outcome = CinemaDngExporter(raf.channel, TAKE).run(destination, isRecording = { recording })
            outcome as CinemaDngExportOutcome.Stopped
            assertEquals(CinemaDngStopReason.RECORDING, outcome.reason)
            assertTrue("$TAKE.gcsv" in destination.targets.single().failed)
        }
    }

    @Test
    fun aMotionLogWithoutRowsIsLeftOut() {
        val file = takeWithMotion(beforeFirstFrame = true)
        RandomAccessFile(file, "r").use { raf ->
            val destination = FakeDestination()
            val outcome = CinemaDngExporter(raf.channel, TAKE).run(destination)
            outcome as CinemaDngExportOutcome.Exported
            assertFalse(outcome.motionLog)
            val target = destination.targets.single()
            assertTrue(target.published)
            assertFalse("$TAKE.gcsv" in target.files)
            assertEquals(target.files.values.sumOf { it.size.toLong() }, outcome.bytes)
        }
    }

    @Test
    fun aTakeWithoutMotionDataHasNoGyroflowLog() = withTake { channel ->
        val outcome = CinemaDngExporter(channel, TAKE).run(FakeDestination())
        outcome as CinemaDngExportOutcome.Exported
        assertFalse(outcome.motionLog)
    }

    private fun withTake(block: (FileChannel) -> Unit) = RandomAccessFile(take, "r").use { block(it.channel) }

    /**
     * The fixture take rewritten chunk by chunk with `IMU_SAMPLES` after its first frame: gyroscope and
     * accelerometer samples every 5 ms from 10 ms before its first frame to 10 ms after its last; with
     * [beforeFirstFrame], ten samples ending 10 ms before its first frame, which leave the log no row.
     */
    private fun takeWithMotion(beforeFirstFrame: Boolean = false): File {
        val scan = OcrawCodec.scanContainer(take.readBytes())
        val frames = scan.chunks.filter { it.wireId == OcrawChunkId.RAW_FRAME.wireId }
        val timeBase = frames.first().timeBase
        assertEquals("the fixture's ticks are ns", OcrawTimeBase(1, 1_000_000_000), timeBase)
        val first = frames.first().timestampTicks - if (beforeFirstFrame) 55_000_000L else 10_000_000L
        val count = if (beforeFirstFrame) 10 else ((frames.last().timestampTicks + 10_000_000L - first) / 5_000_000L + 1).toInt()
        val kinds = ByteArray(count * 2) { if (it % 2 == 0) OcrawImuSamples.KIND_GYROSCOPE.toByte() else OcrawImuSamples.KIND_ACCELEROMETER.toByte() }
        val times = LongArray(count * 2) { first + (it / 2) * 5_000_000L }
        val xyz = FloatArray(count * 6) { (it % 7) * 0.125f }

        val file = temp.newFile("motion.ocraw")
        RandomAccessFile(file, "rw").use { raf ->
            val writer = OcrawStreamWriter(raf.channel, requireNotNull(scan.header), timeBase, OcrawStreamWriter.Options(forceOnFinish = false), force = null)
            var imuWritten = false
            for (chunk in scan.chunks) {
                when (chunk.wireId) {
                    OcrawChunkId.PERIODIC_INDEX.wireId, OcrawChunkId.FINAL_INDEX.wireId, OcrawChunkId.END.wireId -> Unit
                    OcrawChunkId.RAW_FRAME.wireId -> {
                        val header = OcrawFrameHeader.decode(chunk.payload, 0)
                        val data = ByteBuffer.allocateDirect(chunk.payload.size - OcrawFrameHeader.BYTES)
                        data.put(chunk.payload, OcrawFrameHeader.BYTES, data.capacity()).flip()
                        writer.writeRawFrame(header, data, chunk.timestampTicks)
                        if (!imuWritten) {
                            // Two chunks: the samples' first and second halves.
                            val half = count / 2 * 2
                            writer.writeImuSamples(times[0], OcrawImuSamples.encode(kinds, times, xyz, half))
                            writer.writeImuSamples(times[half], OcrawImuSamples.encode(kinds, times, xyz, kinds.size - half, from = half))
                            imuWritten = true
                        }
                    }
                    else -> writer.writeChunk(chunk.wireId, chunk.timestampTicks, ByteBuffer.wrap(chunk.payload))
                }
            }
            writer.finish()
        }
        return file
    }

    /**
     * Decodes the fixture take's block-pack and LJ92 frames without the native codec: their rows are
     * the samples of the converter's uncompressed DNG of the frame with the same dimensions and source.
     */
    private inner class GoldenDecoder : CinemaDngWriter.BlockPackDecoder {
        override fun decode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Boolean {
            val frame = when (width to height) {
                64 to 34 -> 1
                72 to 26 -> 4
                else -> return false
            }
            val dng = File(expected, "${TAKE}_%06d.dng".format(frame)).readBytes()
            val samples = ByteBuffer.wrap(dng, dng.size - width * height * 2, width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
            val out = dst.duplicate()
            repeat(width * height / 4) {
                var low = 0
                for (k in 0 until 4) {
                    val sample = samples.short.toInt()
                    out.put((sample shr 2).toByte())
                    low = low or ((sample and 3) shl (2 * k))
                }
                out.put(low.toByte())
            }
            return true
        }

        override fun decodeLj92(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer) = decode(src, width, height, dst)

        override fun close() = Unit
    }

    private class FakeDestination(
        private val free: Long? = Long.MAX_VALUE,
        private val failOn: String? = null,
        private val discardSucceeds: Boolean = true,
        private val noMediaSucceeds: Boolean = true,
        private val onWrite: (String) -> Unit = {},
    ) : CinemaDngDestination {
        val targets = mutableListOf<FakeTarget>()
        override fun freeBytes(): Long? = free
        override fun open(takeName: String): CinemaDngTarget =
            FakeTarget(CinemaDngExporter.folderName(takeName) { false }, failOn, discardSucceeds, noMediaSucceeds, onWrite).also { targets += it }
    }

    private class FakeTarget(
        val folder: String,
        private val failOn: String?,
        private val discardSucceeds: Boolean,
        private val noMediaSucceeds: Boolean,
        private val onWrite: (String) -> Unit,
    ) : CinemaDngTarget {
        val files = LinkedHashMap<String, ByteArray>()
        /** Files whose body threw, so the target removed them. */
        val failed = mutableListOf<String>()
        val mimeTypes = mutableMapOf<String, String>()
        var noMedia = false
        var published = false
        var discarded = false
        override val location: String get() = "CinemaDNG/$folder"

        override fun writeNoMedia(): Boolean {
            noMedia = noMediaSucceeds
            return noMediaSucceeds
        }

        override fun write(name: String, mimeType: String, body: (WritableByteChannel) -> Unit) {
            if (name == failOn) throw IOException("disk full")
            onWrite(name)
            val out = ByteArrayOutputStream()
            try {
                Channels.newChannel(out).use(body)
            } catch (failure: Throwable) {
                files -= name
                failed += name
                throw failure
            }
            files[name] = out.toByteArray()
            mimeTypes[name] = mimeType
        }

        override fun publish() {
            published = true
        }

        override fun discard(): Boolean {
            discarded = true
            files.clear()
            return discardSucceeds
        }
    }

    private companion object {
        const val TAKE = "A001_C001"
        const val FRAMES = 8
    }
}
