/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private val hdrPts = listOf(0L, 400_000L, 1_100_000L, 1_700_000L)
private val hdrCodes = listOf(64, 65, 66, 67, 128, 129, 130, 131, 256, 257, 258, 259, 512, 513, 768, 924)
private const val hdrTag = "E16HdrProbe"

/** Positive P010 acceptance needs API33+ and a HEVC decoder that advertises COLOR_FormatYUVP010.
 * Stock emulator images ship none (c2.goldfish/c2.android/OMX.google), so those cases skip there. */
private fun assumeP010HevcDecoder() {
    assumeTrue("Positive P010 acceptance requires API33+ (device is API ${Build.VERSION.SDK_INT})", Build.VERSION.SDK_INT >= 33)
    val capable = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder && MediaFormat.MIMETYPE_VIDEO_HEVC in info.supportedTypes &&
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 in info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).colorFormats
    }
    assumeTrue("No HEVC decoder advertises P010 output; run on a device/emulator with a 10-bit HEVC decoder", capable)
}

/** Run the three positive methods on API33+, and the explicit API30 rejection separately.
 * Capability absence is a failing acceptance gate, not an assumption/skip or 8-bit substitute. */
@RunWith(AndroidJUnit4::class)
class PreciseHdrFramesDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeP010PreservesAllTenBitsAndDeclaredPqAndHlgTags() {
        assumeP010HevcDecoder()
        for (transfer in PreciseHdrTransfer.entries) {
            val file = createPreciseHdrPlaybackFixture(context, transfer)
            try {
                val before = hdrDigest(file.readBytes())
                inspectNativeP010(file, transfer)
                assertEquals(before, hdrDigest(file.readBytes()))
            } finally { assertTrue(file.delete()) }
        }
    }

    @Test fun precisePqAndHlgFramesRetainActualPtsBackwardFloorBoundsAndOriginalBytes() {
        assumeP010HevcDecoder()
        for (transfer in PreciseHdrTransfer.entries) {
            val file = createPreciseHdrPlaybackFixture(context, transfer)
            try {
                val before = hdrDigest(file.readBytes())
                val invalidPolicy = assertThrows(IllegalArgumentException::class.java) {
                    PreciseVideoFrames(context, Uri.fromFile(file).toString(), PreciseVideoColorPolicy.INTERPRET_TRACK_SDR).close()
                }
                assertEquals("SDR track interpretation does not apply to HDR", invalidPolicy.message)
                val reader = PreciseVideoFrames(context, Uri.fromFile(file).toString())
                try {
                    assertEquals(hdrPts, reader.timeline.timestampsUs)
                    assertEquals(0, reader.timeline.indexAt(-1))
                    assertEquals(1, reader.timeline.indexAt(1_099_999L))
                    assertEquals(2, reader.timeline.indexAt(1_100_000L))
                    assertEquals(3, reader.timeline.indexAt(Long.MAX_VALUE))
                    assertEquals(0, reader.timeline.step(0, -1))
                    assertEquals(3, reader.timeline.step(3, 1))
                    assertThrows(IllegalArgumentException::class.java) { reader.frame(-1) }
                    assertThrows(IllegalArgumentException::class.java) { reader.frame(4) }
                    for (index in listOf(0, 3, 1, reader.timeline.step(1, 1), reader.timeline.step(1, -1))) {
                        val decoded = reader.frame(index)
                        try {
                            assertEquals(index, decoded.index)
                            assertEquals(hdrPts[index], decoded.presentationTimeUs)
                            assertEquals(256, decoded.bitmap.width)
                            assertEquals(64, decoded.bitmap.height)
                            assertEquals(Bitmap.Config.ARGB_8888, decoded.bitmap.config)
                            assertNull("HDR must not masquerade as interpreted SDR source", decoded.color)
                            assertEquals(transfer, decoded.hdrPreview?.transfer)
                            assertEquals(10, decoded.hdrPreview?.decodedBitDepth)
                            assertNeutralBands(decoded.bitmap, transfer, index, false)
                            Log.i(hdrTag, "production transfer=$transfer index=$index ptsUs=${decoded.presentationTimeUs} decodedBits=${decoded.hdrPreview?.decodedBitDepth} preview=SDR_ARGB8888")
                        } finally { decoded.bitmap.recycle() }
                    }
                } finally { reader.close(); reader.close() }
                assertThrows(IllegalStateException::class.java) { reader.frame(0) }
                assertEquals(before, hdrDigest(file.readBytes()))
            } finally { assertTrue(file.delete()) }
        }
    }

    @Test fun preciseHdrRotationUsesTrackOrientationOnceWithoutChangingSampleBytes() {
        assumeP010HevcDecoder()
        for (transfer in PreciseHdrTransfer.entries) {
            val file = createPreciseHdrPlaybackFixture(context, transfer, 90)
            try {
                val before = hdrDigest(file.readBytes())
                PreciseVideoFrames(context, Uri.fromFile(file).toString()).use { reader ->
                    val decoded = reader.frame(0)
                    try {
                        assertEquals(0L, decoded.presentationTimeUs)
                        assertEquals(64, decoded.bitmap.width)
                        assertEquals(256, decoded.bitmap.height)
                        assertEquals(transfer, decoded.hdrPreview?.transfer)
                        assertEquals(10, decoded.hdrPreview?.decodedBitDepth)
                        assertNeutralBands(decoded.bitmap, transfer, 0, true)
                    } finally { decoded.bitmap.recycle() }
                }
                assertEquals(before, hdrDigest(file.readBytes()))
            } finally { assertTrue(file.delete()) }
        }
    }

    @Test fun api30RejectsPreciseHdrExplicitlyWithoutChangingOriginal() {
        assumeTrue("Negative gate runs only on the API30 fixture device (this device is API ${Build.VERSION.SDK_INT})", Build.VERSION.SDK_INT == 30)
        for (transfer in PreciseHdrTransfer.entries) {
            val file = createPreciseHdrPlaybackFixture(context, transfer)
            try {
                val before = hdrDigest(file.readBytes())
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    PreciseVideoFrames(context, Uri.fromFile(file).toString()).close()
                }
                assertEquals("Precise HDR10 requires Android 13+ and a CPU-readable P010 decoder", failure.message)
                assertEquals(before, hdrDigest(file.readBytes()))
                Log.i(hdrTag, "API30 transfer=$transfer rejected=${failure.message} originalUnchanged=true")
            } finally { assertTrue(file.delete()) }
        }
    }

    @Test fun queryNativeP010ProfilesWithoutClaimingDecodeAcceptance() {
        assumeTrue("P010 capability query needs API33+", Build.VERSION.SDK_INT >= 33)
        val file = createPreciseHdrPlaybackFixture(context, PreciseHdrTransfer.PQ)
        val extractor = MediaExtractor()
        try {
            val before = hdrDigest(file.readBytes())
            extractor.setDataSource(file.path)
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                !it.isEncoder && MediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes
            }
            assertTrue(candidates.isNotEmpty())
            for (candidate in candidates) {
                val caps = candidate.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                val format = extractor.getTrackFormat(0)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
                val reportedProfile = format.getInteger(MediaFormat.KEY_PROFILE)
                val asIs = runCatching { caps.isFormatSupported(format) }
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                val baseMain10 = runCatching { caps.isFormatSupported(format) }
                format.removeKey(MediaFormat.KEY_COLOR_FORMAT)
                format.setInteger(MediaFormat.KEY_PROFILE, reportedProfile)
                val surfaceProfile = runCatching { caps.isFormatSupported(format) }
                Log.i(hdrTag, "capability codec=${candidate.name} software=${candidate.isSoftwareOnly} " +
                    "colors=${caps.colorFormats.toList()} profiles=${caps.profileLevels.map { it.profile to it.level }} " +
                    "reportedProfile=$reportedProfile asIs=$asIs baseMain10=$baseMain10 withoutColor=$surfaceProfile")
            }
            assertEquals(before, hdrDigest(file.readBytes()))
        } finally { extractor.release(); assertTrue(file.delete()) }
    }

    @Test fun queryP010AlternativesWithoutReplacingHevcAcceptance() {
        assumeTrue("P010 capability query needs API33+", Build.VERSION.SDK_INT >= 33)
        for ((mime, profiles) in listOf(
            MediaFormat.MIMETYPE_VIDEO_VP9 to (MediaCodecInfo.CodecProfileLevel.VP9Profile2 to MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR),
            MediaFormat.MIMETYPE_VIDEO_AV1 to (MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 to MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10))) {
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && mime in it.supportedTypes }
            assumeTrue("No advertised decoder for $mime on this device; run on hardware with VP9/AV1 decoders", candidates.isNotEmpty())
            for (candidate in candidates) {
                val caps = candidate.getCapabilitiesForType(mime)
                val format = MediaFormat.createVideoFormat(mime, 256, 64).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
                    setInteger(MediaFormat.KEY_PROFILE, profiles.first)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                }
                val hlg = runCatching { caps.isFormatSupported(format) }
                format.setInteger(MediaFormat.KEY_PROFILE, profiles.second)
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                val pq = runCatching { caps.isFormatSupported(format) }
                Log.i(hdrTag, "alternative mime=$mime codec=${candidate.name} software=${candidate.isSoftwareOnly} " +
                    "colors=${caps.colorFormats.toList()} profiles=${caps.profileLevels.map { it.profile to it.level }} " +
                    "hlg=$hlg pq=$pq")
            }
        }
    }

    private fun inspectNativeP010(file: File, transfer: PreciseHdrTransfer) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(file.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, format.getString(MediaFormat.KEY_MIME))
            assertHdrTags(format, transfer)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                !it.isEncoder && MediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes
            }
            val capable = candidates.filter {
                val caps = it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 in caps.colorFormats && caps.isFormatSupported(format)
            }
            val selected = capable.firstOrNull { it.isSoftwareOnly } ?: capable.firstOrNull()
            assertNotNull("No real P010 decoder: candidates=${candidates.map { it.name }} input=$format", selected)
            val decoder = MediaCodec.createByCodecName(requireNotNull(selected).name)
            codec = decoder
            Log.i(hdrTag, "codec=${selected.name} input=$format")
            decoder.configure(format, null, null, 0)
            decoder.start(); started = true
            extractor.selectTrack(0)
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 30_000_000_000L
            var ended = false
            var samples = 0
            while (System.nanoTime() - deadline < 0) {
                if (!ended) {
                    val input = decoder.dequeueInputBuffer(1_000)
                    if (input >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(input))
                        buffer.clear()
                        if (extractor.sampleTime == -1L) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            ended = true
                        } else {
                            assertTrue(samples < hdrPts.size)
                            assertEquals(hdrPts[samples++], extractor.sampleTime)
                            assertTrue(extractor.sampleSize in 1..buffer.capacity().toLong())
                            val count = extractor.readSampleData(buffer, 0)
                            assertEquals(extractor.sampleSize, count.toLong())
                            decoder.queueInputBuffer(input, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 1_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    assertHdrTags(decoder.outputFormat, transfer)
                    Log.i(hdrTag, "codec=${selected.name} output=${decoder.outputFormat}")
                } else if (output >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            assertEquals(0L, info.presentationTimeUs)
                            val outputFormat = decoder.getOutputFormat(output)
                            assertHdrTags(outputFormat, transfer)
                            assertEquals(54, outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT))
                            requireNotNull(decoder.getOutputImage(output)) { "Advertised P010 decoder returned no readable Image" }.use { image ->
                                assertP010Bands(image)
                                Log.i(hdrTag, "codec=${selected.name} transfer=$transfer ptsUs=${info.presentationTimeUs} imageFormat=${image.format} pixelStrides=${image.planes.map { it.pixelStride }} rowStrides=${image.planes.map { it.rowStride }} verifiedAllPixels=true lowBitsPreserved=true output=$outputFormat")
                            }
                            return
                        }
                        assertEquals("EOS without P010 frame", 0, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } finally { decoder.releaseOutputBuffer(output, false) }
                }
            }
            fail("Native P010 decode exceeded 30 seconds")
        } finally {
            try { if (started) codec?.stop() } finally {
                try { codec?.release() } finally { extractor.release() }
            }
        }
    }

    /** Decode packed bytes independently of production readP010Sample and color conversion. */
    private fun assertP010Bands(image: Image) {
        assertEquals(ImageFormat.YCBCR_P010, image.format)
        assertEquals(54, image.format)
        assertEquals(3, image.planes.size)
        assertEquals(listOf(2, 4, 4), image.planes.map { it.pixelStride })
        val crop = image.cropRect
        assertEquals(256, crop.width())
        assertEquals(64, crop.height())
        image.planes.forEachIndexed { planeIndex, plane ->
            val bytes = plane.buffer.duplicate()
            val scale = if (planeIndex == 0) 1 else 2
            for (y in crop.top / scale until crop.bottom / scale) {
                for (x in crop.left / scale until crop.right / scale) {
                    val offset = bytes.position().toLong() + y.toLong() * plane.rowStride + x.toLong() * plane.pixelStride
                    assertTrue("P010 sample exceeds plane bounds", offset >= 0 && offset + 1 < bytes.limit())
                    val packed = (bytes.get(offset.toInt()).toInt() and 255) or
                        ((bytes.get(offset.toInt() + 1).toInt() and 255) shl 8)
                    assertEquals("P010 padding bits", 0, packed and 63)
                    val expected = if (planeIndex == 0) hdrCodes[(x - crop.left) / 16] else 512
                    assertEquals("plane=$planeIndex x=$x y=$y preserves adjacent 10-bit codes", expected, packed ushr 6)
                }
            }
        }
    }

    private fun assertNeutralBands(bitmap: Bitmap, transfer: PreciseHdrTransfer, frame: Int, rotated: Boolean) {
        val values = hdrCodes.indices.map { band ->
            val pixel = if (rotated) bitmap.getPixel(32, band * 16 + 8) else bitmap.getPixel(band * 16 + 8, 32)
            assertEquals(255, Color.alpha(pixel))
            assertTrue("Neutral HDR band must remain neutral", kotlin.math.abs(Color.red(pixel) - Color.green(pixel)) <= 1 &&
                kotlin.math.abs(Color.green(pixel) - Color.blue(pixel)) <= 1)
            Color.red(pixel)
        }
        assertTrue("Tone-mapped bands must remain monotonic: $values", values.zipWithNext().all { (a, b) -> a <= b })
        if (frame == 0) {
            assertEquals("Declared limited-range black", 0, values.first())
            // Independent high-precision reference values for the documented 203-nit Reinhard
            // luminance mapping, PQ 10000 nits or HLG 1000-nit/system-gamma1.2, then sRGB.
            // These constants are not obtained by calling the production conversion function.
            val expected = if (transfer == PreciseHdrTransfer.PQ)
                listOf(0, 0, 0, 0, 2, 2, 3, 3, 34, 34, 35, 35, 157, 158, 242, 252)
            else listOf(0, 0, 0, 0, 8, 8, 9, 9, 51, 52, 52, 52, 126, 126, 201, 233)
            expected.zip(values).forEachIndexed { band, (reference, actual) ->
                assertTrue("$transfer SDR band=$band expected=$reference actual=$actual", kotlin.math.abs(reference - actual) <= 1)
            }
        }
        Log.i(hdrTag, "SDR preview transfer=$transfer frame=$frame neutralBandValues=$values rotated=$rotated")
    }
}

/** SHA-bound independently generated lossless Main10 asset. Rotation remux preserves every sample
 * and CSD byte; no missing or contradictory color tags are repaired by this fixture. */
internal fun createPreciseHdrPlaybackFixture(context: Context, transfer: PreciseHdrTransfer, rotation: Int = 0): File {
    require(rotation in setOf(0, 90))
    val key = if (transfer == PreciseHdrTransfer.PQ) "pq" else "hlg"
    val expectedHash = if (transfer == PreciseHdrTransfer.PQ)
        "cb5b094f7751f77eca4472f5624441ed1226d45611bb39a22c0d3f6ea813aad3"
    else "1eb17ddff33a7c3118d28158608e9ab3ff0eed5f78995612d8c8ba35450c5b05"
    val file = File(context.cacheDir, "precise-hdr-$key-${UUID.randomUUID()}.mp4")
    val rotated = File(context.cacheDir, "precise-hdr-$key-rotated-${UUID.randomUUID()}.mp4")
    try {
        val asset = InstrumentationRegistry.getInstrumentation().context.assets.open("e16-precise-${key}10.mp4").use { input ->
            val bytes = ByteArray(12_298)
            var total = 0
            while (total < bytes.size) {
                val count = input.read(bytes, total, bytes.size - total)
                if (count == -1) break
                check(count > 0)
                total += count
            }
            assertEquals(12_297, total)
            bytes.copyOf(total)
        }
        assertEquals(expectedHash, hdrDigest(asset))
        file.writeBytes(asset)
        assertTrue(file.setReadOnly())
        val originalSamples = hdrSampleIdentity(file, transfer, 0)
        if (rotation == 0) return file
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(file.path)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, format.getString(MediaFormat.KEY_MIME))
            assertHdrTags(format, transfer)
            extractor.selectTrack(0)
            val writer = MediaMuxer(rotated.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            writer.setOrientationHint(rotation)
            val track = writer.addTrack(format)
            writer.start(); started = true
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var count = 0
            while (extractor.sampleTime != -1L) {
                assertTrue(count < hdrPts.size)
                assertEquals(hdrPts[count++], extractor.sampleTime)
                buffer.clear()
                assertTrue(extractor.sampleSize in 1..buffer.capacity().toLong())
                val size = extractor.readSampleData(buffer, 0)
                assertEquals(extractor.sampleSize, size.toLong())
                info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                buffer.position(0); buffer.limit(size)
                writer.writeSampleData(track, buffer, info)
                if (!extractor.advance()) break
            }
            assertEquals(4, count)
            writer.stop(); started = false
        } finally {
            try { extractor.release() } finally {
                try { if (started) muxer?.stop() } finally { muxer?.release() }
            }
        }
        assertEquals(originalSamples, hdrSampleIdentity(rotated, transfer, rotation))
        assertEquals(expectedHash, hdrDigest(file.readBytes()))
        assertTrue(rotated.setReadOnly())
        assertTrue(file.delete())
        return rotated
    } catch (failure: Throwable) {
        file.delete(); rotated.delete()
        throw failure
    }
}

private fun hdrSampleIdentity(file: File, transfer: PreciseHdrTransfer, rotation: Int): List<String> {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        assertEquals(1, extractor.trackCount)
        val format = extractor.getTrackFormat(0)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, format.getString(MediaFormat.KEY_MIME))
        assertHdrTags(format, transfer)
        assertEquals(256, format.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(64, format.getInteger(MediaFormat.KEY_HEIGHT))
        assertEquals(rotation, if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0)
        val identity = ArrayList<String>()
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            if (format.containsKey(key)) {
                val csd = requireNotNull(format.getByteBuffer(key)).duplicate()
                val bytes = ByteArray(csd.remaining()); csd.get(bytes)
                identity.add("$key:${hdrDigest(bytes)}")
            }
        }
        extractor.selectTrack(0)
        val buffer = ByteBuffer.allocate(1024 * 1024)
        var sample = 0
        while (extractor.sampleTime != -1L) {
            assertTrue(sample < hdrPts.size)
            assertEquals(hdrPts[sample++], extractor.sampleTime)
            assertTrue(extractor.sampleSize in 1..buffer.capacity().toLong())
            assertEquals(0, extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            assertEquals(extractor.sampleSize, size.toLong())
            identity.add("${extractor.sampleTime}:${extractor.sampleFlags}:${hdrDigest(buffer.array().copyOf(size))}")
            if (!extractor.advance()) break
        }
        assertEquals(hdrPts.size, sample)
        return identity
    } finally { extractor.release() }
}

private fun assertHdrTags(format: MediaFormat, transfer: PreciseHdrTransfer) {
    assertEquals(MediaFormat.COLOR_STANDARD_BT2020, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
    assertEquals(MediaFormat.COLOR_RANGE_LIMITED, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
    assertEquals(if (transfer == PreciseHdrTransfer.PQ) MediaFormat.COLOR_TRANSFER_ST2084 else MediaFormat.COLOR_TRANSFER_HLG,
        format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
}

private fun hdrDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
