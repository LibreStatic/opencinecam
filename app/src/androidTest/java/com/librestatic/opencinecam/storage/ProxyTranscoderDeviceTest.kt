/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DebugTraceUtil
import androidx.media3.transformer.ExportResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Media3 exports of existing hash-verified assets. No generated codec/fixture oracle.
 * All bundled videos are silent: these cases verify no invented audio, not audio passthrough
 * acceptance. The service's real AAC capture integration must verify encoded audio preservation.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ProxyTranscoderDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualAvcTranscodePreservesEveryVfrGopFrameAndSameSizeStillEncodes() = runBlocking<Unit> {
        val source = createPreciseGopFixture(context)
        val output = candidate()
        val oldWorkers = workers()
        val before = digest(source)
        val previousTracing = DebugTraceUtil.enableTracing
        try {
            DebugTraceUtil.reset()
            DebugTraceUtil.enableTracing = true
            val report = withTimeout(90_000) { ProxyTranscoder(context).transcode(Uri.fromFile(source), output, 96, 64, 1_000_000) }
            Log.i("E17ProxyProbe", "gop12BeforeAssertions report=$report expectedPtsUs=$preciseGopPtsUs")
            logGopOutputBeforeAssertions(output)
            // Stop tracing before the independent CPU reader so its decoder cannot contaminate
            // the export's decoder/SurfaceTexture/encoder/muxer event counts.
            logGopTrace()
            DebugTraceUtil.enableTracing = false
            assertReport(report, output, 12)
            assertSamplePts(output, preciseGopPtsUs)
            assertVideoDuration(source, output)
            PreciseVideoFrames(context, Uri.fromFile(output).toString()).use { reader ->
                assertEquals(preciseGopPtsUs, reader.timeline.timestampsUs)
                // Cold final picture proves drain to EOS; subsequent visits cover both input GOPs.
                for (index in listOf(11) + (0..10)) {
                    val frame = reader.frame(index)
                    try {
                        assertEquals(preciseGopPtsUs[index], frame.presentationTimeUs)
                        assertEquals(96, frame.bitmap.width); assertEquals(64, frame.bitmap.height)
                        assertGray(preciseGopExpectedGray[index], frame.bitmap.getPixel(24, 32), 4)
                        assertGray(255, frame.bitmap.getPixel(72, 32), 4)
                    } finally { frame.bitmap.recycle() }
                }
            }
            assertEquals(before, digest(source))
            assertRetired(oldWorkers)
            Log.i("E17ProxyProbe", "sameSizeTranscode=true frames=12 ptsUs=$preciseGopPtsUs allDistinctPixels=true sourceSha=$before report=$report retired=true")
        } finally {
            try {
                // Includes errors during export; successful export was already dumped above.
                if (DebugTraceUtil.enableTracing) logGopTrace()
            } finally {
                DebugTraceUtil.enableTracing = previousTracing
                assertRetired(oldWorkers); output.delete(); assertTrue(source.delete())
            }
        }
    }

    @Test fun anamorphicCropAndSarRemainLetterboxedAtLandscapePresentationSize() = runBlocking<Unit> { checkAnamorphic(0) }

    @Test fun rotatedAnamorphicCropAndSarRemainPillarboxedAtPortraitPresentationSize() = runBlocking<Unit> { checkAnamorphic(90) }

    private suspend fun checkAnamorphic(rotation: Int) {
        val source = createPreciseAnamorphicFixture(context, rotation)
        val output = candidate()
        val before = digest(source)
        val oldWorkers = workers()
        val width = if (rotation == 0) 192 else 128
        val height = if (rotation == 0) 128 else 192
        val pts = listOf(0L, 400_000L, 1_100_000L, 1_700_000L)
        try {
            val report = withTimeout(90_000) { ProxyTranscoder(context).transcode(Uri.fromFile(source), output, width, height, 1_000_000) }
            if (rotation == 90) {
                Log.i("E17ProxyProbe", "rotatedSarBeforeAssertions report=$report expectedPresentation=${width}x$height expectedContentWidth=62 expectedPadding=33")
                logRotatedSarFormats("source", source)
                logRotatedSarFormats("output", output)
            }
            assertReport(report, output, 4)
            assertSamplePts(output, pts)
            assertVideoDuration(source, output)
            PreciseVideoFrames(context, Uri.fromFile(output).toString()).use { reader ->
                assertEquals(pts, reader.timeline.timestampsUs)
                for ((index, color) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
                    val frame = reader.frame(index)
                    try {
                        assertEquals(pts[index], frame.presentationTimeUs)
                        assertEquals(width, frame.bitmap.width); assertEquals(height, frame.bitmap.height)
                        assertEquals(width, frame.displayWidth); assertEquals(height, frame.displayHeight)
                        val bitmap = frame.bitmap
                        if (rotation == 90) {
                            for (y in listOf(0, 20, 48, 96, 144, 171, 191)) {
                                val row = (0 until bitmap.width).map { x -> bitmap.getPixel(x, y) }
                                val nonblack = row.indices.filter { x ->
                                    val pixel = row[x]
                                    maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 16
                                }
                                val samples = listOf(0, 10, 20, 30, 32, 33, 38, 48, 64, 80, 90, 94, 95, 96, 108, 117, 127)
                                    .joinToString { x -> "$x:${Integer.toHexString(row[x])}" }
                                Log.i("E17ProxyProbe", "rotatedSar index=$index ptsUs=${frame.presentationTimeUs} bitmap=${bitmap.width}x${bitmap.height} display=${frame.displayWidth}x${frame.displayHeight} y=$y nonblackFirst=${nonblack.firstOrNull()} nonblackLast=${nonblack.lastOrNull()} samples=$samples")
                            }
                        }
                        // Source visible DAR192:62, not coded96:64. FIT yields content62 high
                        // (or62 wide after90°), with33px padding on either side of that axis.
                        if (rotation == 0) {
                            assertColor(color, bitmap.getPixel(48, 64), 12)
                            assertColor(Color.WHITE, bitmap.getPixel(144, 64), 12)
                            assertGray(0, bitmap.getPixel(144, 20), 4)
                            assertGray(0, bitmap.getPixel(144, 108), 4)
                            assertColor(Color.WHITE, bitmap.getPixel(144, 38), 12)
                            assertColor(Color.WHITE, bitmap.getPixel(144, 90), 12)
                        } else {
                            assertColor(color, bitmap.getPixel(64, 48), 12)
                            assertColor(Color.WHITE, bitmap.getPixel(64, 144), 12)
                            assertGray(0, bitmap.getPixel(20, 144), 4)
                            assertGray(0, bitmap.getPixel(108, 144), 4)
                            assertColor(Color.WHITE, bitmap.getPixel(38, 144), 12)
                            assertColor(Color.WHITE, bitmap.getPixel(90, 144), 12)
                        }
                    } finally { frame.bitmap.recycle() }
                }
            }
            assertEquals(before, digest(source)); assertRetired(oldWorkers)
            Log.i("E17ProxyProbe", "rotation=$rotation sourceDar=192:62 outputPresentation=${width}x$height fitPadding33=true all4PtsAndColors=true sourceSha=$before report=$report retired=true")
        } finally { assertRetired(oldWorkers); output.delete(); assertTrue(source.delete()) }
    }

    @Test fun cancellationAfterCandidateReservationRetiresBeforeCleanupAndNextExportWorks() = runBlocking<Unit> {
        val source = createPreciseGopFixture(context)
        val cancelled = candidate()
        val next = candidate()
        val before = digest(source)
        val oldWorkers = workers()
        val job = async(Dispatchers.Default) {
            ProxyTranscoder(context).transcode(Uri.fromFile(source), cancelled, 1920, 1280, 8_000_000)
        }
        try {
            withTimeout(20_000) {
                while (!cancelled.exists() || (workers() - oldWorkers).isEmpty()) {
                    check(!job.isCompleted) { "Export finished before the cancellation checkpoint" }
                    delay(1)
                }
            }
            assertFalse("Cancel a live adapter operation, not a completed export", job.isCompleted)
            // Reservation + live application looper is the cancellation boundary. This test
            // deliberately does not claim any particular decoder/encoder frame was submitted.
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(cancelled.exists())
            assertRetired(oldWorkers)
            assertEquals(before, digest(source))
            val report = withTimeout(90_000) { ProxyTranscoder(context).transcode(Uri.fromFile(source), next, 192, 128, 1_000_000) }
            assertReport(report, next, 12); assertSamplePts(next, preciseGopPtsUs)
            assertVideoDuration(source, next)
            assertRetired(oldWorkers); assertEquals(before, digest(source))
            Log.i("E17ProxyProbe", "cancelAfterReservation=true nativeFrameSubmissionNotClaimed=true cancelledCandidateRemoved=true successorActualExport=true sourceSha=$before retired=true")
        } finally {
            job.cancelAndJoin()
            assertRetired(oldWorkers)
            cancelled.delete(); next.delete(); assertTrue(source.delete())
        }
    }

    @Test fun existingCandidateCorruptSourceAndBothHdrTransfersFailWithoutChangingOriginals() = runBlocking<Unit> {
        val source = createPreciseGopFixture(context)
        val corrupt = File(context.cacheDir, "proxy-corrupt-${UUID.randomUUID()}.mp4").apply { writeBytes(source.readBytes().copyOf(40)) }
        val existing = candidate().apply { writeText("existing candidate must survive") }
        val output = candidate()
        val next = candidate()
        val before = digest(source)
        val corruptBefore = digest(corrupt)
        val existingBefore = digest(existing)
        val oldWorkers = workers()
        try {
            val existingFailure = failure { ProxyTranscoder(context).transcode(Uri.fromFile(source), existing, 96, 64, 1_000_000) }
            assertTrue(existingFailure is IllegalArgumentException)
            assertEquals("Proxy candidate already exists", existingFailure.message)
            assertEquals(existingBefore, digest(existing))
            failure { ProxyTranscoder(context).transcode(Uri.fromFile(corrupt), output, 96, 64, 1_000_000) }
            assertFalse(output.exists()); assertEquals(corruptBefore, digest(corrupt))
            for (transfer in PreciseHdrTransfer.entries) {
                val hdr = createPreciseHdrPlaybackFixture(context, transfer)
                try {
                    val hdrBefore = digest(hdr)
                    val problem = failure { ProxyTranscoder(context).transcode(Uri.fromFile(hdr), output, 256, 64, 1_000_000) }
                    assertTrue(problem is IllegalArgumentException)
                    assertEquals("HDR proxy conversion is not supported", problem.message)
                    assertFalse(output.exists()); assertEquals(hdrBefore, digest(hdr))
                } finally { assertTrue(hdr.delete()) }
            }
            val report = withTimeout(90_000) { ProxyTranscoder(context).transcode(Uri.fromFile(source), next, 96, 64, 1_000_000) }
            assertReport(report, next, 12); assertSamplePts(next, preciseGopPtsUs)
            assertVideoDuration(source, next)
            assertRetired(oldWorkers); assertEquals(before, digest(source))
            Log.i("E17ProxyProbe", "existingCandidatePreserved=true corruptSourceRejected=true pqAndHlgRejectedWithoutToneMap=true successorActualExport=true sourceSha=$before retired=true")
        } finally {
            assertRetired(oldWorkers)
            output.delete(); next.delete(); assertTrue(existing.delete()); assertTrue(corrupt.delete()); assertTrue(source.delete())
        }
    }

    private suspend fun failure(action: suspend () -> Unit): Throwable {
        try { withTimeout(90_000) { action() } }
        catch (problem: Throwable) { if (problem is CancellationException) throw problem; return problem }
        throw AssertionError("Expected an explicit failure, not successful fallback")
    }

    private fun assertReport(report: ProxyTranscodeResult, output: File, frames: Int) {
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, report.videoMimeType)
        assertEquals(ExportResult.CONVERSION_PROCESS_TRANSCODED, report.videoConversionProcess)
        assertTrue(report.videoEncoderName.isNotBlank())
        assertNull(report.audioMimeType); assertNull(report.audioEncoderName)
        assertEquals(ExportResult.CONVERSION_PROCESS_NA, report.audioConversionProcess)
        assertEquals(frames, report.videoFrameCount)
        assertEquals(output.length(), report.fileSizeBytes)
        assertTrue(report.fileSizeBytes > 0)
    }

    private fun logGopOutputBeforeAssertions(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            for (track in 0 until extractor.trackCount) {
                Log.i("E17ProxyProbe", "gop12ActualTrack=$track format=${extractor.getTrackFormat(track)} bytes=${file.length()}")
                extractor.selectTrack(track)
                val samples = ArrayList<String>()
                while (extractor.sampleTime >= 0) {
                    check(samples.size < 256) { "Diagnostic output exceeds bounded GOP12 sample count" }
                    samples += "ptsUs=${extractor.sampleTime},flags=${extractor.sampleFlags},bytes=${extractor.sampleSize}"
                    if (!extractor.advance()) break
                }
                Log.i("E17ProxyProbe", "gop12ActualTrack=$track sampleCount=${samples.size} decodeOrderSamples=$samples")
                extractor.unselectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
        } finally { extractor.release() }
    }

    private fun logRotatedSarFormats(role: String, file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            for (track in 0 until extractor.trackCount) {
                Log.i("E17ProxyProbe", "rotatedSar $role track=$track format=${extractor.getTrackFormat(track)} bytes=${file.length()}")
            }
        } finally { extractor.release() }
    }

    private fun logGopTrace() {
        // Media3 1.11.0 exposes only generateTraceSummary, not printTraceToWriter. Keep
        // every returned character (numbered chunks avoid Android's per-line truncation).
        val trace = DebugTraceUtil.generateTraceSummary()
        val chunks = trace.chunked(2400)
        Log.i("E17ProxyProbe", "gop12TraceBegin chars=${trace.length} chunks=${chunks.size} source=Media3DebugTraceUtilSummary")
        chunks.forEachIndexed { index, chunk -> Log.i("E17ProxyProbe", "gop12TraceChunk=${index + 1}/${chunks.size} $chunk") }
        Log.i("E17ProxyProbe", "gop12TraceEnd")
    }

    private fun assertSamplePts(file: File, expected: List<Long>) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            assertEquals("Do not invent or lose tracks in a silent fixture", 1, extractor.trackCount)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(0)
            val pts = ArrayList<Long>()
            while (extractor.sampleTime >= 0) {
                assertTrue("No duplicated frames", pts.size < expected.size)
                assertTrue(extractor.sampleSize > 0)
                pts += extractor.sampleTime
                if (!extractor.advance()) break
            }
            assertEquals("VFR timing and all frames must survive", expected, pts.sorted())
        } finally { extractor.release() }
    }

    private fun assertVideoDuration(source: File, output: File) {
        fun duration(file: File): Long {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val video = (0 until extractor.trackCount).single {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                val format = extractor.getTrackFormat(video)
                assertTrue(format.containsKey(MediaFormat.KEY_DURATION))
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                assertTrue(durationUs > 0)
                extractor.selectTrack(video)
                var count = 0
                while (extractor.sampleTime >= 0) {
                    assertTrue(++count <= 12)
                    assertTrue("Declared duration must include sample ${extractor.sampleTime}", extractor.sampleTime < durationUs)
                    if (!extractor.advance()) break
                }
                assertTrue(count > 0)
                return durationUs
            } finally { extractor.release() }
        }
        val sourceUs = duration(source)
        val outputUs = duration(output)
        Log.i("E17ProxyProbe", "sourceVideoDurationUs=$sourceUs outputVideoDurationUs=$outputUs exactDurationRequired=true")
        assertEquals("Retain final-frame duration, not the previous VFR interval", sourceUs, outputUs)
    }

    private fun assertGray(expected: Int, actual: Int, tolerance: Int) =
        assertColor(Color.rgb(expected, expected, expected), actual, tolerance)

    private fun assertColor(expected: Int, actual: Int, tolerance: Int) {
        assertEquals(255, Color.alpha(actual))
        for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) {
            assertTrue("Expected pixel ${Integer.toHexString(expected)} actual ${Integer.toHexString(actual)} tolerance=$tolerance",
                kotlin.math.abs(channel(expected) - channel(actual)) <= tolerance)
        }
    }

    private fun candidate() = File(context.cacheDir, "proxy-candidate-${UUID.randomUUID()}.mp4")
    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun workers() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("proxy-transcoder-") }.toSet()
    private fun assertRetired(before: Set<Thread>) = assertTrue("Adapter looper must retire before return/throw/cancel", (workers() - before).isEmpty())
}
