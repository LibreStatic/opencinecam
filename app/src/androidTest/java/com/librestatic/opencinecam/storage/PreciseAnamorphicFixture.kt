/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*

/** SHA-bound AVC: SPS raster96x64 cropped bottom2, visible96x62, SAR2:1, four known colors.
 * Both orientations remux the identical CSD/sample bytes/PTS; no decoder/camera produces fixtures. */
internal fun createPreciseAnamorphicFixture(context: Context, rotation: Int = 0): File {
    require(rotation in setOf(0, 90))
    val file = File(context.cacheDir, "precise-anamorphic-${UUID.randomUUID()}.mp4")
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var started = false
    try {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open("e16-precise-anamorphic.mp4").use { input ->
            val buffer = ByteArray(1706)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read == -1) break
                check(read > 0)
                count += read
            }
            assertEquals(1705, count)
            buffer.copyOf(count)
        }
        assertEquals("bb16a93f0033562bc6aa80b54b059116fe9894215e3bf9dbbc3469eed807464e", anamorphicDigest(bytes))
        assets.openFd("e16-precise-anamorphic.mp4").use { descriptor ->
            assertEquals(1705L, descriptor.declaredLength)
            extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
        }
        assertEquals(1, extractor.trackCount)
        val format = extractor.getTrackFormat(0)
        assertAnamorphicGeometry(format)
        // Android30 can omit container color keys. Missing-only completion is bound to the
        // exact SHA and independent SPS ISO6/1/6 limited verification; contradictions fail.
        val completedColorKeys = anamorphicColors.keys.filterNot { format.containsKey(it) }
        anamorphicColors.forEach { (key, expected) ->
            if (format.containsKey(key)) assertEquals("Fixture contradicts $key", expected, format.getInteger(key))
            else format.setInteger(key, expected)
        }
        Log.i("E16AnamorphicProbe", "fixtureInput=$format missingOnlyColorCompletion=$completedColorKeys expectedSourceSar=2:1 spsCodedRaster=96x64 visible=96x62")
        val originalCsd = anamorphicCsd(format)
        assertEquals(listOf("csd-0", "csd-1"), originalCsd.map { it.substringBefore(':') })
        extractor.selectTrack(0)
        val writer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = writer
        writer.setOrientationHint(rotation)
        val track = writer.addTrack(format)
        writer.start(); started = true
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        val samples = ArrayList<String>()
        while (extractor.sampleTime != -1L) {
            assertTrue(samples.size < anamorphicPts.size)
            assertEquals(anamorphicPts[samples.size], extractor.sampleTime)
            buffer.clear()
            assertTrue(extractor.sampleSize in 1..buffer.capacity().toLong())
            assertEquals(0, extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))
            val size = extractor.readSampleData(buffer, 0)
            assertEquals(extractor.sampleSize, size.toLong())
            samples += "${extractor.sampleTime}:${extractor.sampleFlags}:${anamorphicDigest(buffer.array().copyOf(size))}"
            info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            buffer.position(0); buffer.limit(size)
            writer.writeSampleData(track, buffer, info)
            if (!extractor.advance()) break
        }
        assertEquals(4, samples.size)
        writer.stop(); started = false
        val check = MediaExtractor()
        try {
            check.setDataSource(file.path)
            assertEquals(1, check.trackCount)
            val result = check.getTrackFormat(0)
            assertAnamorphicGeometry(result)
            assertEquals(rotation, if (result.containsKey(MediaFormat.KEY_ROTATION)) result.getInteger(MediaFormat.KEY_ROTATION) else 0)
            anamorphicColors.forEach { (key, expected) -> assertEquals("Remux $key", expected, result.getInteger(key)) }
            assertEquals("Remux must retain SPS including crop/SAR/color declarations", originalCsd, anamorphicCsd(result))
            check.selectTrack(0)
            val remuxed = ArrayList<String>()
            while (check.sampleTime != -1L) {
                assertTrue(remuxed.size < anamorphicPts.size)
                buffer.clear()
                assertTrue(check.sampleSize in 1..buffer.capacity().toLong())
                val size = check.readSampleData(buffer, 0)
                assertEquals(check.sampleSize, size.toLong())
                remuxed += "${check.sampleTime}:${check.sampleFlags}:${anamorphicDigest(buffer.array().copyOf(size))}"
                if (!check.advance()) break
            }
            assertEquals(samples, remuxed)
            Log.i("E16AnamorphicProbe", "fixtureRemux=$result rotation=$rotation samplesAndCsdUnchanged=true expectedSourceSar=2:1")
        } finally { check.release() }
        return file
    } catch (failure: Throwable) {
        file.delete()
        throw failure
    } finally {
        try { extractor.release() } finally {
            try { if (started) muxer?.stop() } finally { muxer?.release() }
        }
    }
}

private val anamorphicPts = listOf(0L, 400_000L, 1_100_000L, 1_700_000L)
private val anamorphicColors = mapOf(MediaFormat.KEY_COLOR_STANDARD to MediaFormat.COLOR_STANDARD_BT601_NTSC,
    MediaFormat.KEY_COLOR_RANGE to MediaFormat.COLOR_RANGE_LIMITED, MediaFormat.KEY_COLOR_TRANSFER to MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
private fun assertAnamorphicGeometry(format: MediaFormat) {
    assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
    assertEquals(96, format.getInteger(MediaFormat.KEY_WIDTH))
    assertEquals(62, format.getInteger(MediaFormat.KEY_HEIGHT))
    if (format.containsKey("sar-width") || format.containsKey("sar-height")) {
        assertTrue("Observed SAR pair must be complete: $format", format.containsKey("sar-width") && format.containsKey("sar-height"))
        assertEquals(2, format.getInteger("sar-width")); assertEquals(1, format.getInteger("sar-height"))
    } else {
        assertTrue("Extractor must expose the observed legacy display pair: $format",
            format.containsKey("display-width") && format.containsKey("display-height"))
        assertEquals(192, format.getInteger("display-width")); assertEquals(62, format.getInteger("display-height"))
    }
}
private fun anamorphicCsd(format: MediaFormat): List<String> = listOf("csd-0", "csd-1", "csd-2").mapNotNull { key ->
    if (!format.containsKey(key)) null else {
        val buffer = requireNotNull(format.getByteBuffer(key)).duplicate()
        val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
        "$key:${anamorphicDigest(bytes)}"
    }
}
private fun anamorphicDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
