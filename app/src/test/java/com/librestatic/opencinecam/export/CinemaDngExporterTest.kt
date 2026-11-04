// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.export

import com.librestatic.opencinecam.media.ocraw.OcrawCodec
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.media.raw.NativeRawCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
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

    /** The frames the JVM converts: 3 is block-pack (native codec only), 5 an unknown encoding. */
    private val convertedFrames = if (NativeRawCodec.available) listOf(0, 1, 2, 3, 4, 6) else listOf(0, 1, 2, 4, 6)

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
        assertEquals(7 - convertedFrames.size, outcome.skipped)
        assertTrue(outcome.audio)
        assertFalse(outcome.audioOmitted)
        assertEquals(target.files.values.sumOf { it.size.toLong() }, outcome.bytes)
        assertEquals("CinemaDNG/$TAKE", outcome.location)

        assertEquals(CinemaDngExportProgress(0, 7, 0, 0), progress.first())
        assertEquals(7, progress.last().done)
        assertEquals(7 - convertedFrames.size, progress.last().skipped)
        assertEquals(1f, progress.last().fraction)
    }

    @Test
    fun planMatchesWhatTheExportWrites() = withTake { channel ->
        val plan = requireNotNull(CinemaDngExporter(channel, TAKE).plan())
        assertEquals(7, plan.frames)
        assertEquals(convertedFrames.size, plan.convertible)
        assertEquals(7 - convertedFrames.size, plan.skipped)
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
            assertEquals(7 - convertedFrames.size + 1, outcome.skipped)
            assertArrayEquals(File(expected, "${TAKE}_000001.dng").readBytes(), target.files.getValue("${TAKE}_000001.dng"))
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

    private fun withTake(block: (FileChannel) -> Unit) = RandomAccessFile(take, "r").use { block(it.channel) }

    private class FakeDestination(
        private val free: Long? = Long.MAX_VALUE,
        private val failOn: String? = null,
        private val discardSucceeds: Boolean = true,
        private val noMediaSucceeds: Boolean = true,
    ) : CinemaDngDestination {
        val targets = mutableListOf<FakeTarget>()
        override fun freeBytes(): Long? = free
        override fun open(takeName: String): CinemaDngTarget =
            FakeTarget(CinemaDngExporter.folderName(takeName) { false }, failOn, discardSucceeds, noMediaSucceeds).also { targets += it }
    }

    private class FakeTarget(
        val folder: String,
        private val failOn: String?,
        private val discardSucceeds: Boolean,
        private val noMediaSucceeds: Boolean,
    ) : CinemaDngTarget {
        val files = LinkedHashMap<String, ByteArray>()
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
            val out = ByteArrayOutputStream()
            try {
                Channels.newChannel(out).use(body)
            } catch (failure: Throwable) {
                files -= name
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
    }
}
