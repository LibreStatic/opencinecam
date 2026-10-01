/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import java.io.Closeable
import java.io.InterruptedIOException
import java.util.concurrent.TimeoutException

/** The caller owns and must recycle bitmap after display. No original bytes are modified. */
data class DecodedVideoFrame(val bitmap: Bitmap, val presentationTimeUs: Long, val index: Int, val color: PreciseVideoColor? = null, val hdrPreview: PreciseHdrPreview? = null, val displayWidth: Int = bitmap.width, val displayHeight: Int = bitmap.height, val logView: PreciseLogView? = null)

data class RenderedVideoFrame(val presentationTimeUs: Long, val index: Int, val renderedAtNs: Long, val displayWidth: Int, val displayHeight: Int)

/** Construct and call frame on serial IO. A missing exact PTS is an error, never nearest-frame fallback.
 * Bounds: 250000 samples, 30 seconds per index/decode, 64 MiB per compressed sample,
 * 8192 pixels per edge and 4096*2160 output pixels. SDR8 YUV420 and API33+ P010 PQ/HLG
 * are supported. P010 decoded samples retain10 bits until explicitly labelled SDR tone mapping.
 * [log] comes only from the clip's OCLog2 sidecar: BT.2020 P010 with an unspecified container transfer. */
class PreciseVideoFrames(context: Context, uri: String, private val colorPolicy: PreciseVideoColorPolicy = PreciseVideoColorPolicy.STRICT, requireCpuPreview: Boolean = true,
    private val log: PreciseLogSignal? = null) : Closeable {
    private val extractor = MediaExtractor()
    private val format: MediaFormat
    private val mime: String
    private val rotation: Int
    private val hdr: PreciseHdrTransfer?
    private var closed = false
    val hdrTransfer: PreciseHdrTransfer? get() = hdr
    /** Applies to CPU frames decoded after the change; Surface output is interpreted by its consumer. */
    @Volatile var logView: PreciseLogView = PreciseLogView.FLAT_LOG
    val displayWidth: Int get() = displayGeometry(format).width
    val displayHeight: Int get() = displayGeometry(format).height
    val timeline: VideoFrameTimeline

    init {
        val deadline = deadline()
        try {
            checkWork(deadline)
            val source = uri.toUri()
            require(source.scheme in setOf("content", "file")) { "Precise video requires a local readable URI" }
            requireNotNull(context.contentResolver.openAssetFileDescriptor(source, "r")) { "Video descriptor unavailable" }.use { descriptor ->
                if (descriptor.declaredLength < 0) {
                    require(descriptor.startOffset == 0L) { "Unknown-length video descriptor has a nonzero offset" }
                    extractor.setDataSource(descriptor.fileDescriptor)
                }
                else extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
            }
            require(extractor.trackCount in 1..32) { "Video track count is unsupported" }
            val tracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            require(tracks.size == 1) { "Precise playback requires exactly one video track" }
            val track = tracks.single()
            format = extractor.getTrackFormat(track)
            mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            requireDimensions(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
            // The OCLog2 sidecar outranks the track's transfer tag: some encoders (c2.qti.hevc on the
            // Razr Fold) write PQ even though the recorder requested an unspecified transfer.
            hdr = if (log != null) { requireLog(format); null } else when (format.integerOr(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)) {
                MediaFormat.COLOR_TRANSFER_ST2084 -> PreciseHdrTransfer.PQ
                MediaFormat.COLOR_TRANSFER_HLG -> PreciseHdrTransfer.HLG
                else -> { requireSdr(format); null }
            }
            if (log != null) {
                if (requireCpuPreview) require(Build.VERSION.SDK_INT >= 33) { "Precise OCLog2 requires Android 13+ and a CPU-readable P010 decoder" }
                require(colorPolicy == PreciseVideoColorPolicy.STRICT) { "SDR track interpretation does not apply to OCLog2" }
            }
            if (hdr != null) {
                if (requireCpuPreview) require(Build.VERSION.SDK_INT >= 33) { "Precise HDR10 requires Android 13+ and a CPU-readable P010 decoder" }
                requireHdr(format, hdr)
                require(colorPolicy == PreciseVideoColorPolicy.STRICT) { "SDR track interpretation does not apply to HDR" }
            }
            rotation = format.integerOr(MediaFormat.KEY_ROTATION, 0)
            require(rotation in setOf(0, 90, 180, 270)) { "Unsupported video rotation" }
            displayGeometry(format) // Validate presentation metadata independently of CPU decoding.
            extractor.selectTrack(track)
            val timestamps = ArrayList<Long>()
            while (extractor.sampleTime != -1L) {
                checkWork(deadline)
                require(timestamps.size < VideoFrameTimeline.MAX_FRAMES) { "Precise frame index exceeds ${VideoFrameTimeline.MAX_FRAMES} samples" }
                requireSample()
                timestamps.add(extractor.sampleTime)
                if (!extractor.advance()) break
            }
            timeline = VideoFrameTimeline(timestamps)
            checkWork(deadline)
        } catch (failure: Throwable) {
            try { extractor.release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    @Synchronized
    fun frame(index: Int): DecodedVideoFrame = requireNotNull(decode(index, null).cpu)

    /** Caller serializes all producers on this Surface. No CPU image or P010 requirement.
     * Exactness is confirmed by codec identity + output PTS + render callback, not queue success.
     * API23-33 may omit callbacks; that is an unconfirmed render error, never exact acceptance. */
    @Synchronized
    fun frameToSurface(index: Int, surface: Surface): RenderedVideoFrame = requireNotNull(decode(index, surface).rendered)

    private data class Output(val cpu: DecodedVideoFrame? = null, val rendered: RenderedVideoFrame? = null)

    private fun decode(index: Int, surface: Surface?): Output {
        check(!closed) { "Precise video reader is closed" }
        require(index in timeline.timestampsUs.indices) { "Frame index is out of range" }
        val deadline = deadline()
        checkWork(deadline)
        val target = timeline.timestampsUs[index]
        if (surface != null) require(surface.isValid) { "Exact frame Surface is unavailable" }
        if (surface == null && (hdr != null || log != null)) require(Build.VERSION.SDK_INT >= 33) {
            "Precise 10-bit review requires Android 13+ and a CPU-readable P010 decoder"
        }
        val decodeFormat = MediaFormat(format)
        if (surface == null) {
            // Only CPU output rotates manually. Surface output retains track rotation and CSD.
            decodeFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            decodeFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, if (Build.VERSION.SDK_INT >= 33 && (hdr != null || log != null))
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        } else decodeFormat.removeKey(MediaFormat.KEY_COLOR_FORMAT)
        extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        check(extractor.sampleTime >= 0 && extractor.sampleTime <= target &&
            extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { "No preceding video sync sample is available" }
        // This reader needs CPU-visible images, unlike Surface playback. Prefer an advertised
        // software decoder with compatible format rather than a Surface-oriented default codec.
        val compatible = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { candidate ->
            !candidate.isEncoder && candidate.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                candidate.getCapabilitiesForType(mime).let { capabilities ->
                    capabilities.isFormatSupported(decodeFormat) && (surface != null || hdr == null && log == null || (Build.VERSION.SDK_INT >= 33 &&
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 in capabilities.colorFormats))
                }
        }
        val decoderName = requireNotNull(if (surface != null) compatible.firstOrNull() else compatible.firstOrNull { it.isSoftwareOnly } ?: compatible.firstOrNull()) {
            "No advertised precise-frame decoder supports $decodeFormat"
        }.name
        val codec = MediaCodec.createByCodecName(decoderName)
        var started = false
        var decoded: DecodedVideoFrame? = null
        var rendered: RenderedVideoFrame? = null
        val renderActive = AtomicBoolean(false)
        val renderedNs = AtomicLong(-1)
        val renderLatch = CountDownLatch(1)
        try {
          try {
            codec.configure(decodeFormat, surface, null, 0)
            if (surface != null) codec.setOnFrameRenderedListener({ owner, pts, nanoTime ->
                if (renderActive.get() && owner === codec && pts == target && surface.isValid) {
                    renderedNs.set(nanoTime); renderLatch.countDown()
                }
            }, Handler(Looper.getMainLooper()))
            codec.start()
            started = true
            var inputEnded = false
            var inputs = 0
            var outputs = 0
            val info = MediaCodec.BufferInfo()
            decode@ while (true) {
                checkWork(deadline)
                if (!inputEnded) {
                    val input = codec.dequeueInputBuffer(1_000)
                    if (input >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(input)) { "Decoder input unavailable" }
                        buffer.clear()
                        if (extractor.sampleTime == -1L) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            requireSample()
                            check(++inputs <= VideoFrameTimeline.MAX_FRAMES) { "Decode input bound exceeded" }
                            require(extractor.sampleSize <= buffer.capacity()) { "Compressed sample exceeds decoder input capacity" }
                            val pts = extractor.sampleTime
                            check(timeline.timestampsUs.binarySearch(pts) >= 0) { "Video timeline changed during decoding" }
                            val count = extractor.readSampleData(buffer, 0)
                            check(count.toLong() == extractor.sampleSize && count > 0) { "Video sample changed or could not be read completely" }
                            codec.queueInputBuffer(input, 0, count, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val output = codec.dequeueOutputBuffer(info, 1_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        requireOutput(codec.outputFormat)
                        requireDimensions(codec.outputFormat.getInteger(MediaFormat.KEY_WIDTH), codec.outputFormat.getInteger(MediaFormat.KEY_HEIGHT))
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (output >= 0) {
                        var released = false
                        try {
                            if ((info.size > 0 || surface != null && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(++outputs <= VideoFrameTimeline.MAX_FRAMES) { "Decode output bound exceeded" }
                                check(timeline.timestampsUs.binarySearch(info.presentationTimeUs) >= 0) { "Decoder returned an unindexed presentation timestamp" }
                                if (info.presentationTimeUs == target) {
                                    if (surface != null) {
                                        val outputFormat = codec.getOutputFormat(output)
                                        requireOutput(outputFormat)
                                        val geometry = displayGeometry(outputFormat)
                                        checkWork(deadline)
                                        require(surface.isValid) { "Exact frame Surface retired" }
                                        renderActive.set(true)
                                        codec.releaseOutputBuffer(output, true); released = true
                                        while (!renderLatch.await(50, TimeUnit.MILLISECONDS)) {
                                            if (System.nanoTime() - deadline >= 0) throw TimeoutException("Exact frame render unconfirmed: PTS=$target")
                                            checkWork(deadline)
                                            require(surface.isValid) { "Exact frame Surface retired before render confirmation" }
                                        }
                                        checkWork(deadline)
                                        require(surface.isValid) { "Exact frame Surface retired after render confirmation" }
                                        rendered = RenderedVideoFrame(target, index, renderedNs.get(), geometry.width, geometry.height)
                                    } else requireNotNull(codec.getOutputImage(output)) { "Decoder does not expose readable YUV420 images" }.use { image ->
                                        val view = log?.let { logView }
                                        val (bitmap, color) = imageBitmap(image, codec.getOutputFormat(output), rotation, deadline, decoderName, view)
                                        try {
                                            // Image.cropRect has exclusive right/bottom and imageBitmap already
                                            // applied that crop and rotation. Only its presentation ratio changes.
                                            val geometry = displayGeometry(codec.getOutputFormat(output), image.cropRect.width(), image.cropRect.height())
                                            decoded = DecodedVideoFrame(bitmap, info.presentationTimeUs, index, color, hdr?.let { PreciseHdrPreview(it) }, geometry.width, geometry.height, view)
                                        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
                                    }
                                    break@decode
                                }
                            }
                            check(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "Decoder reached the end without the exact requested frame" }
                        } finally { if (!released) codec.releaseOutputBuffer(output, false) }
                    }
                }
            }
          } finally {
            renderActive.set(false)
            try { if (started) codec.stop() } finally { codec.release() }
          }
          checkWork(deadline)
          return Output(decoded, rendered)
        } catch (failure: Throwable) {
            decoded?.bitmap?.recycle()
            throw failure
        }
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; extractor.release() }
    }

    private fun requireSample() {
        require(extractor.sampleTime >= 0 && extractor.sampleSize in 1L..MAX_SAMPLE_BYTES) { "Unsupported video sample timestamp or size" }
        require(extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) == 0) {
            "Encrypted or partial video samples are unsupported"
        }
    }

    private fun imageBitmap(image: Image, output: MediaFormat, rotation: Int, deadline: Long, decoderName: String, view: PreciseLogView?): Pair<Bitmap, PreciseVideoColor?> {
        if (hdr != null) {
            requireHdr(output, hdr)
            val full = output.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
            return p010Bitmap(image, output, rotation, deadline) { y, u, v -> hdr10ToSdrArgb(y, u, v, full, hdr) } to null
        }
        if (log != null) {
            requireLog(output)
            val selected = requireNotNull(view)
            return p010Bitmap(image, output, rotation, deadline) { y, u, v -> oclog2P010ToArgb(y, u, v, log.fullRange, selected) } to null
        }
        requireSdr(output)
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "Decoder output is not 8-bit YUV420" }
        val crop = image.cropRect
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= image.width && crop.bottom <= image.height)
        requireDimensions(crop.width(), crop.height())
        val planes = image.planes.map { plane ->
            require(plane.rowStride > 0 && plane.pixelStride > 0)
            Triple(plane.buffer.duplicate(), plane.rowStride, plane.pixelStride)
        }
        fun sample(plane: Int, x: Int, y: Int): Int {
            val (buffer, rowStride, pixelStride) = planes[plane]
            val at = buffer.position().toLong() + y.toLong() * rowStride + x.toLong() * pixelStride
            require(at in 0 until buffer.limit().toLong()) { "YUV plane stride/crop exceeds available bytes" }
            return buffer.get(at.toInt()).toInt() and 255
        }
        val color = try {
            resolvePreciseVideoColor(output.integerOr(MediaFormat.KEY_COLOR_STANDARD, 0), output.integerOr(MediaFormat.KEY_COLOR_RANGE, 0),
                format.integerOr(MediaFormat.KEY_COLOR_STANDARD, 0), format.integerOr(MediaFormat.KEY_COLOR_RANGE, 0),
                format.integerOr(MediaFormat.KEY_COLOR_TRANSFER, 0), colorPolicy)
        } catch (failure: PreciseVideoColorException) {
            throw PreciseVideoColorException("${failure.message} decoder=$decoderName output=$output input=$format", failure.trackSdrAvailable)
        }
        val standard = color.standard
        val range = color.range
        val full = range == MediaFormat.COLOR_RANGE_FULL
        val bt709 = standard == MediaFormat.COLOR_STANDARD_BT709
        val bitmap = createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(crop.width())
            for (y in 0 until crop.height()) {
                if (y % 16 == 0) checkWork(deadline)
                for (x in row.indices) {
                    val px = crop.left + x; val py = crop.top + y
                    row[x] = sdr8ToSrgbArgb(sample(0, px, py), sample(1, px / 2, py / 2),
                        sample(2, px / 2, py / 2), full, bt709)
                }
                bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            checkWork(deadline)
            if (rotation == 0) return bitmap to color
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, false)
            if (rotated !== bitmap) bitmap.recycle()
            return rotated to color
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    /** P010 is three logical planes even though Cb/Cr share semiplanar storage. No 8-bit fallback. */
    private fun p010Bitmap(image: Image, output: MediaFormat, rotation: Int, deadline: Long, pixel: (Int, Int, Int) -> Int): Bitmap {
        require(Build.VERSION.SDK_INT >= 33 && image.format == ImageFormat.YCBCR_P010 && image.planes.size == 3 &&
            output.integerOr(MediaFormat.KEY_COLOR_FORMAT, 0) == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010) {
            "Precise 10-bit decoder must expose real10-bit P010 images; output=$output imageFormat=${image.format}"
        }
        val crop = image.cropRect
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= image.width && crop.bottom <= image.height)
        requireDimensions(crop.width(), crop.height())
        val planes = image.planes.map { Triple(it.buffer.duplicate(), it.rowStride, it.pixelStride) }
        require(planes[0].third == 2 && planes[1].third == 4 && planes[2].third == 4) { "Unsupported P010 plane layout" }
        fun sample(plane: Int, x: Int, y: Int): Int {
            val (buffer, rowStride, pixelStride) = planes[plane]
            return readP010Sample(buffer, rowStride, pixelStride, x, y)
        }
        val bitmap = createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(crop.width())
            for (y in 0 until crop.height()) {
                if (y % 16 == 0) checkWork(deadline)
                for (x in row.indices) {
                    val px = crop.left + x; val py = crop.top + y
                    row[x] = pixel(sample(0, px, py), sample(1, px / 2, py / 2), sample(2, px / 2, py / 2))
                }
                bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            checkWork(deadline)
            if (rotation == 0) return bitmap
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, false)
            if (rotated !== bitmap) bitmap.recycle()
            return rotated
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    private fun displayGeometry(value: MediaFormat, visibleWidth: Int? = null, visibleHeight: Int? = null): VideoDisplayGeometry {
        // Literal keys are already used by Android29; public constants were added in API30.
        fun sar(metadata: MediaFormat): Pair<Int, Int>? {
            val hasWidth = metadata.containsKey("sar-width"); val hasHeight = metadata.containsKey("sar-height")
            require(hasWidth == hasHeight) { "Incomplete video sample aspect ratio" }
            if (!hasWidth) return null
            val width = metadata.getInteger("sar-width"); val height = metadata.getInteger("sar-height")
            require(width > 0 && height > 0) { "Invalid video sample aspect ratio" }
            return width to height
        }
        val trackSar = sar(format); val outputSar = sar(value)
        if (trackSar != null && outputSar != null) require(
            trackSar.first.toLong() * outputSar.second == outputSar.first.toLong() * trackSar.second
        ) { "Decoder changed the declared sample aspect ratio" }
        val explicitAspect = outputSar ?: trackSar
        val sampleAspect = explicitAspect ?: (1 to 1)
        // Android29/30 may expose legacy tkhd display dimensions instead of SAR. Those are
        // presentation proportions, not coded pixels, and are still before track rotation.
        val legacyWidth = if (explicitAspect == null && format.containsKey("display-width")) format.getInteger("display-width") else null
        val legacyHeight = if (explicitAspect == null && format.containsKey("display-height")) format.getInteger("display-height") else null
        // Never reuse track crop coordinates on an output raster, or recrop an Image.
        if (visibleWidth != null && visibleHeight != null) return videoDisplayGeometry(visibleWidth, visibleHeight,
            sarWidth = sampleAspect.first, sarHeight = sampleAspect.second, rotation = rotation,
            legacyDisplayWidth = legacyWidth, legacyDisplayHeight = legacyHeight)
        val width = value.getInteger(MediaFormat.KEY_WIDTH); val height = value.getInteger(MediaFormat.KEY_HEIGHT)
        return videoDisplayGeometry(width, height,
            value.integerOr("crop-left", 0), value.integerOr("crop-top", 0),
            value.integerOr("crop-right", width - 1), value.integerOr("crop-bottom", height - 1),
            sampleAspect.first, sampleAspect.second, rotation, legacyWidth, legacyHeight)
    }

    private fun requireOutput(value: MediaFormat) {
        if (hdr != null) requireHdr(value, hdr) else if (log != null) requireLog(value) else requireSdr(value)
    }

    /**
     * OCLog2 tracks carry BT.2020; the sidecar declares the curve and range. The transfer tag is
     * ignored because the samples are OCLog2 codes whatever the encoder wrote there.
     */
    private fun requireLog(value: MediaFormat) {
        val signal = requireNotNull(log)
        require(value.integerOr(MediaFormat.KEY_COLOR_STANDARD, 0) in setOf(0, MediaFormat.COLOR_STANDARD_BT2020)) { "OCLog2 track is not BT.2020: $value" }
        val range = value.integerOr(MediaFormat.KEY_COLOR_RANGE, 0)
        require(range == 0 || range == if (signal.fullRange) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED) {
            "OCLog2 track range contradicts its sidecar: $value"
        }
    }

    private fun requireHdr(value: MediaFormat, transfer: PreciseHdrTransfer) {
        val expected = if (transfer == PreciseHdrTransfer.PQ) MediaFormat.COLOR_TRANSFER_ST2084 else MediaFormat.COLOR_TRANSFER_HLG
        require(value.integerOr(MediaFormat.KEY_COLOR_STANDARD, 0) == MediaFormat.COLOR_STANDARD_BT2020 &&
            value.integerOr(MediaFormat.KEY_COLOR_TRANSFER, 0) == expected &&
            value.integerOr(MediaFormat.KEY_COLOR_RANGE, 0) in setOf(MediaFormat.COLOR_RANGE_FULL, MediaFormat.COLOR_RANGE_LIMITED)) {
            "Precise HDR requires explicit matching BT2020/PQ or HLG/range declarations: $value"
        }
        require(value.getInteger(MediaFormat.KEY_COLOR_RANGE) == format.getInteger(MediaFormat.KEY_COLOR_RANGE)) {
            "HDR decoder changed the declared range"
        }
    }

    private companion object {
        const val MAX_SAMPLE_BYTES = 64L * 1024 * 1024
        fun deadline() = System.nanoTime() + 30_000_000_000L
        fun checkWork(deadline: Long) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Precise video operation interrupted")
            if (System.nanoTime() - deadline >= 0) throw TimeoutException("Precise video operation exceeded 30 seconds")
        }
        fun requireDimensions(width: Int, height: Int) {
            require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 4096L * 2160) { "Precise frame raster exceeds the supported pixel bound" }
        }
        fun requireSdr(format: MediaFormat) {
            val transfer = format.integerOr(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            require(transfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO || transfer == 0) { "HDR/linear or unknown video transfer is unsupported: transfer=$transfer format=$format" }
        }
        fun MediaFormat.integerOr(key: String, fallback: Int): Int = if (containsKey(key)) getInteger(key) else fallback
    }
}
