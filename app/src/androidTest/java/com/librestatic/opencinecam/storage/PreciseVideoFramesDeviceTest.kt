/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreciseVideoFramesDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pts = listOf(0L, 400_000L, 1_100_000L, 1_700_000L)
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)

    @Test fun actualVariablePtsFirstLastBackwardAndFloorSeekReturnExactColoredFramesWithoutChangingSource() {
        val file = fixture(0)
        try {
            val before = digest(file)
            PreciseVideoFrames(context, Uri.fromFile(file).toString()).use { reader ->
                assertEquals(pts, reader.timeline.timestampsUs)
                assertEquals(1, reader.timeline.indexAt(1_099_999L))
                for (index in listOf(0, 3, 1, reader.timeline.step(1, 1), reader.timeline.step(1, -1))) {
                    val decoded = reader.frame(index)
                    try {
                        assertEquals(index, decoded.index)
                        assertEquals(pts[index], decoded.presentationTimeUs)
                        assertEquals(96, decoded.bitmap.width)
                        assertEquals(64, decoded.bitmap.height)
                        assertColor(colors[index], decoded.bitmap.getPixel(24, 32))
                        assertColor(Color.WHITE, decoded.bitmap.getPixel(72, 32))
                    } finally { decoded.bitmap.recycle() }
                }
            }
            assertArrayEquals(before, digest(file))
        } finally { assertTrue(file.delete()) }
    }

    @Test fun rotationUsesTrackOrientationOnceAndPreservesSpatialColorIdentity() {
        val file = fixture(90)
        try {
            PreciseVideoFrames(context, Uri.fromFile(file).toString()).use { reader ->
                val decoded = reader.frame(2)
                try {
                    assertEquals(1_100_000L, decoded.presentationTimeUs)
                    assertEquals(64, decoded.bitmap.width)
                    assertEquals(96, decoded.bitmap.height)
                    assertColor(Color.BLUE, decoded.bitmap.getPixel(32, 24))
                    assertColor(Color.WHITE, decoded.bitmap.getPixel(32, 72))
                } finally { decoded.bitmap.recycle() }
            }
        } finally { assertTrue(file.delete()) }
    }

    @Test fun invalidIndexInterruptionAndClosedReaderFailExplicitlyAndPermitFreshRead() {
        val file = fixture(0)
        try {
            val uri = Uri.fromFile(file).toString()
            val reader = PreciseVideoFrames(context, uri)
            try {
                assertThrows(IllegalArgumentException::class.java) { reader.frame(-1) }
                assertThrows(IllegalArgumentException::class.java) { reader.frame(4) }
                Thread.currentThread().interrupt()
                try {
                    assertThrows(InterruptedIOException::class.java) { reader.frame(0) }
                    assertTrue(Thread.currentThread().isInterrupted)
                } finally { Thread.interrupted() }
                reader.frame(3).bitmap.recycle()
            } finally { reader.close(); reader.close() }
            assertThrows(IllegalStateException::class.java) { reader.frame(0) }
            Thread.currentThread().interrupt()
            try { assertThrows(InterruptedIOException::class.java) { PreciseVideoFrames(context, uri) } }
            finally { Thread.interrupted() }
            PreciseVideoFrames(context, uri).use { assertEquals(pts, it.timeline.timestampsUs) }
        } finally { assertTrue(file.delete()) }
    }

    @Test fun corruptOrMissingSourceNeverProducesAnApproximateFrame() {
        val file = File(context.cacheDir, "precise-corrupt-${UUID.randomUUID()}.mp4")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            val before = digest(file)
            assertThrows(Exception::class.java) { PreciseVideoFrames(context, Uri.fromFile(file).toString()) }
            assertArrayEquals(before, digest(file))
        } finally { assertTrue(file.delete()) }
        assertThrows(Exception::class.java) { PreciseVideoFrames(context, Uri.fromFile(file).toString()) }
    }

    /** A real small AVC MP4: nonuniform PTS, interframes, declared SDR/BT601/limited range,
     * two spatial color regions, and a rotation hint. No camera availability or asset guesswork. */
    private fun fixture(rotation: Int): File = createPrecisePlaybackFixture(context, rotation)

    private fun assertColor(expected: Int, actual: Int) {
        assertTrue("Expected RGB ${Color.red(expected)},${Color.green(expected)},${Color.blue(expected)} but got ${Color.red(actual)},${Color.green(actual)},${Color.blue(actual)}",
            kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 12 &&
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 12 &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 12)
    }
    private fun digest(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}

/** Remux the independently generated AVC fixture without changing its samples or PTS.
 * The asset carries matching BT601 SDR limited-range container and bitstream declarations.
 * Caller owns this isolated cache file and deletes it when all readers have closed. */
internal fun createPrecisePlaybackFixture(context: Context, rotation: Int = 0): File {
    require(rotation in setOf(0, 90))
    val expectedPts = listOf(0L, 400_000L, 1_100_000L, 1_700_000L)
    val file = File(context.cacheDir, "precise-frames-${UUID.randomUUID()}.mp4")
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var started = false
    try {
        try {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            val assetBytes = assets.open("e16-precise-sdr.mp4").use { input ->
                val bytes = ByteArray(1689)
                var total = 0
                while (total < bytes.size) {
                    val count = input.read(bytes, total, bytes.size - total)
                    if (count == -1) break
                    check(count > 0)
                    total += count
                }
                assertEquals(1688, total)
                bytes.copyOf(total)
            }
            assertEquals("f2baae46850ccd0b5eb2d05cc2df4ca6256d961b9b98803045dc4312ee3ca7a2",
                MessageDigest.getInstance("SHA-256").digest(assetBytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            assets.openFd("e16-precise-sdr.mp4").use { asset ->
                assertEquals(1688L, asset.declaredLength)
                extractor.setDataSource(asset.fileDescriptor, asset.startOffset, asset.declaredLength)
            }
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(96, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(64, format.getInteger(MediaFormat.KEY_HEIGHT))
            // API30 may omit container color keys. These declarations come from the exact
            // SHA-bound asset above; ffprobe and SPS trace independently verify ISO 6/1/6 limited.
            val colors = mapOf(MediaFormat.KEY_COLOR_STANDARD to MediaFormat.COLOR_STANDARD_BT601_NTSC,
                MediaFormat.KEY_COLOR_RANGE to MediaFormat.COLOR_RANGE_LIMITED,
                MediaFormat.KEY_COLOR_TRANSFER to MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            colors.forEach { (key, expected) ->
                if (format.containsKey(key)) assertEquals("Fixture $key contradicts verified bitstream", expected, format.getInteger(key))
                else format.setInteger(key, expected)
            }
            extractor.selectTrack(0)
            val writer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            writer.setOrientationHint(rotation)
            val track = writer.addTrack(format)
            writer.start(); started = true
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var sample = 0
            val deadline = System.nanoTime() + 20_000_000_000L
            while (extractor.sampleTime != -1L) {
                check(System.nanoTime() - deadline < 0) { "Fixture remux timed out" }
                check(sample < expectedPts.size) { "Fixture has extra samples" }
                assertEquals(expectedPts[sample], extractor.sampleTime)
                check(extractor.sampleSize in 1..buffer.capacity().toLong())
                check(extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) == 0)
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                assertEquals(extractor.sampleSize, size.toLong())
                info.set(0, size, extractor.sampleTime,
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                buffer.position(0); buffer.limit(size)
                writer.writeSampleData(track, buffer, info)
                sample++
                if (!extractor.advance()) break
            }
            assertEquals(expectedPts.size, sample)
            writer.stop(); started = false
        } finally {
            try { extractor.release() } finally {
                try { if (started) muxer?.stop() } finally { muxer?.release() }
            }
        }
        return file
    } catch (failure: Throwable) {
        file.delete()
        throw failure
    }
}
