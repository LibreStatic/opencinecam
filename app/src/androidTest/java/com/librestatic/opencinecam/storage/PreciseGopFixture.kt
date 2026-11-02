/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*

internal val preciseGopPtsUs = listOf(0L, 90_000L, 230_000L, 410_000L, 650_000L, 910_000L,
    1_200_000L, 1_540_000L, 1_920_000L, 2_350_000L, 2_810_000L, 3_300_000L)
// Independent display-space oracle: inverse709/SMPTE170M followed by sRGB, not raw R'G'B'.
// Original YUV corpus and PTS remain byte-identical; both CPU bitmap and native getPixel use sRGB.
internal val preciseGopExpectedGray = listOf(34, 55, 76, 96, 116, 136, 155, 174, 193, 212, 231, 250)
// The same frames range-expanded but left in the BT.709 transfer: what a compositor shows when it
// scans a video Surface out without converting transfer (the S24 Ultra's direct output does). Within
// the ±4 match no value equals another frame's, so either rendering still identifies its own frame.
internal val preciseGopCodeGray = listOf(19, 40, 61, 82, 102, 123, 144, 165, 186, 207, 228, 249)
internal val preciseGopPacketPtsUs = listOf(0L, 410_000L, 90_000L, 230_000L, 910_000L, 650_000L,
    1_200_000L, 2_350_000L, 1_540_000L, 1_920_000L, 3_300_000L, 2_810_000L)
internal val preciseGopSyncPtsUs = listOf(0L, 1_200_000L)

/** Copy the independently verified Main AVC asset byte-for-byte. No remux, metadata completion,
 * camera or encoder participates in native tests. Six B pictures and two IDRs are proven by the
 * generator's ffprobe/trace evidence; runtime separately verifies decode-order PTS and sync flags.
 * Generator: tools/fixtures/generate_precise_assets.sh (192x128, >=96 px for Qualcomm c2.qti). */
internal fun createPreciseGopFixture(context: Context): File {
    val file = File(context.cacheDir, "precise-gop-${UUID.randomUUID()}.mp4")
    try {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("e16-precise-gop.mp4").use { input ->
            val buffer = ByteArray(2201)
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count == -1) break
                check(count > 0)
                total += count
            }
            assertEquals(2200, total)
            buffer.copyOf(total)
        }
        assertEquals("c17f3858f4cc4e89d739f0886a91ec2c15fcc336012d9e06222349f569429f4b", gopDigest(bytes))
        file.writeBytes(bytes)
        assertTrue(file.setReadOnly())
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(192, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(128, format.getInteger(MediaFormat.KEY_HEIGHT))
            for ((key, expected) in mapOf(MediaFormat.KEY_COLOR_STANDARD to MediaFormat.COLOR_STANDARD_BT601_NTSC,
                MediaFormat.KEY_COLOR_RANGE to MediaFormat.COLOR_RANGE_LIMITED, MediaFormat.KEY_COLOR_TRANSFER to MediaFormat.COLOR_TRANSFER_SDR_VIDEO)) {
                if (format.containsKey(key)) assertEquals("Original metadata contradicts verified SPS: $key", expected, format.getInteger(key))
                else Log.i("E16GopProbe", "extractorOmitted=$key originalSpsVerified=true metadataCompleted=false format=$format")
            }
            extractor.selectTrack(0)
            val packetPts = ArrayList<Long>()
            val syncPts = ArrayList<Long>()
            while (extractor.sampleTime != -1L) {
                assertTrue("No extra samples", packetPts.size < preciseGopPacketPtsUs.size)
                assertTrue(extractor.sampleSize in 1..file.length())
                assertEquals(0, extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))
                packetPts.add(extractor.sampleTime)
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncPts.add(extractor.sampleTime)
                if (!extractor.advance()) break
            }
            assertEquals(preciseGopPacketPtsUs, packetPts)
            assertEquals(preciseGopPtsUs, packetPts.sorted())
            assertNotEquals("B frames must produce nonmonotonic presentation timestamps in decode order", packetPts.sorted(), packetPts)
            assertEquals(preciseGopSyncPtsUs, syncPts)
            Log.i("E16GopProbe", "originalFormat=$format packetPtsUs=$packetPts sortedPtsUs=${packetPts.sorted()} syncPtsUs=$syncPts metadataCompleted=false")
        } finally { extractor.release() }
        assertArrayEquals(bytes, file.readBytes())
        return file
    } catch (failure: Throwable) {
        file.delete()
        throw failure
    }
}

private fun gopDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
