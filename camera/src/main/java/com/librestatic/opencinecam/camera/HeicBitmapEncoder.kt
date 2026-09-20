/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File

private data class HeicEncoderChoice(val name: String, val mime: String, val grid: HeicEncodingGrid,
    val qualityLower: Int, val qualityUpper: Int) {
    val nativeHeic get() = mime == MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC
}

/** Capability only, never a claim that encoding or subsequent decoding succeeded. No codec is opened. */
internal fun canEncodeHeicBitmap(width: Int, height: Int): Boolean = selectHeicEncoder(width, height) != null

private fun selectHeicEncoder(width: Int, height: Int): HeicEncoderChoice? {
    require(width > 0 && height > 0 && width.toLong() * height <= HeicEncodingGrid.MAX_PIXELS)
    val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    for (mime in listOf(MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
        for (info in infos) {
            if (!info.isEncoder || info.supportedTypes.none { it.equals(mime, true) }) continue
            val caps = info.getCapabilitiesForType(mime)
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible !in caps.colorFormats) continue
            val encoder = caps.encoderCapabilities ?: continue
            if (!encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) continue
            val range = encoder.qualityRange
            if (range.lower < 0 || range.upper <= range.lower) continue
            val video = caps.videoCapabilities ?: continue
            val native = mime == MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC
            // Native HEIC takes one full input. Odd/custom sizes instead use an aligned HEVC grid.
            val w = if (native) width else 512
            val h = if (native) height else 512
            if (w % 2 != 0 || h % 2 != 0 || !video.isSizeSupported(w, h)) continue
            return HeicEncoderChoice(info.name, mime, HeicEncodingGrid(width, height, w, h), range.lower, range.upper)
        }
    }
    return null
}

/**
 * Synchronous, owned HEIC encoding. HEVC fallback consists of independent intra-coded grid tiles,
 * not a video file or a JPEG conversion. MediaFormat's HEIC contract trims the padded right/bottom
 * edges to the original display dimensions. Input is already oriented, opaque, 8-bit sRGB.
 *
 * A 60-second polling deadline bounds codec progress, not blocking platform stop/release calls:
 * the caller retains ownership until those calls really return, including on cancellation.
 * Only the private temporary file is owned here; the caller retains its Bitmap.
 */
internal fun encodeHeicBitmap(bitmap: Bitmap, quality: Int, tempDirectory: File,
    checkRunning: () -> Unit): ByteArray {
    require(!bitmap.isRecycled && bitmap.config != Bitmap.Config.HARDWARE)
    require(quality in 1..100 && !bitmap.hasAlpha()) { "HEIC input must be opaque SDR pixels" }
    checkRunning()
    val choice = selectHeicEncoder(bitmap.width, bitmap.height)
        ?: throw UnsupportedOperationException("No HEIC or HEVC encoder supports flexible YUV input and constant quality")
    check(tempDirectory.isDirectory || tempDirectory.mkdirs()) { "HEIC temporary directory is unavailable" }
    val file = File.createTempFile("heic-owned-", ".heic", tempDirectory)
    var primary: Throwable? = null
    try {
        encodeHeicFile(bitmap, quality, choice, file, checkRunning)
        checkRunning()
        val size = file.length()
        check(size in 1..HeicEncodingGrid.MAX_BYTES.toLong()) { "HEIC encoded file exceeds its byte budget" }
        val bytes = ByteArray(size.toInt())
        file.inputStream().use { input ->
            var offset = 0
            while (offset < bytes.size) {
                checkRunning()
                val count = input.read(bytes, offset, minOf(64 * 1024, bytes.size - offset))
                check(count > 0) { "HEIC output was truncated" }
                offset += count
            }
            check(input.read() == -1 && file.length() == size) { "HEIC output changed during readback" }
        }
        checkRunning()
        return bytes
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try {
            check(file.delete() || !file.exists()) { "HEIC temporary output cleanup failed" }
        } catch (failure: Throwable) {
            if (primary == null) throw failure else if (failure !== primary) primary.addSuppressed(failure)
        }
    }
}

private fun encodeHeicFile(bitmap: Bitmap, quality: Int, choice: HeicEncoderChoice, file: File,
    checkRunning: () -> Unit) {
    var codec: MediaCodec? = null
    var codecStarted = false
    var muxer: MediaMuxer? = null
    var muxerStarted = false
    var primary: Throwable? = null
    val deadline = SystemClock.elapsedRealtime() + 60_000L
    fun checkpoint() {
        checkRunning()
        check(SystemClock.elapsedRealtime() < deadline) { "HEIC encoding deadline expired" }
    }
    try {
        checkpoint()
        val grid = choice.grid
        val format = MediaFormat.createVideoFormat(choice.mime, grid.tileWidth, grid.tileHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
            setInteger(MediaFormat.KEY_QUALITY, heicCodecQuality(quality, choice.qualityLower, choice.qualityUpper))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        val encoder = MediaCodec.createByCodecName(choice.name).also { codec = it }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        checkpoint()
        encoder.start()
        codecStarted = true
        val output = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF).also { muxer = it }
        var inputCount = 0
        val inputTarget = if (choice.nativeHeic) 1 else grid.count
        var eosQueued = false
        var eosReceived = false
        var track = -1
        var expectedSamples = 0
        var sampleCount = 0
        var encodedBytes = 0L
        val info = MediaCodec.BufferInfo()
        while (!eosReceived) {
            checkpoint()
            if (!eosQueued) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    if (inputCount < inputTarget) {
                        val image = requireNotNull(encoder.getInputImage(index)) { "HEIC encoder has no writable YUV input image" }
                        heicRetiring({ image.close() }) {
                            val (x, y) = if (choice.nativeHeic) 0 to 0 else grid.origin(inputCount)
                            fillHeicInput(bitmap, image, x, y, grid.tileWidth, grid.tileHeight, ::checkpoint)
                        }
                        encoder.queueInputBuffer(index, 0, grid.tileWidth * grid.tileHeight * 3 / 2,
                            inputCount * 1_000_000L / 30, 0)
                        inputCount++
                    } else {
                        encoder.queueInputBuffer(index, 0, 0, inputCount * 1_000_000L / 30,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                }
            }
            checkpoint()
            when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(track == -1) { "HEIC encoder changed format twice" }
                    val actual = encoder.outputFormat
                    check(actual.getString(MediaFormat.KEY_MIME) == choice.mime)
                    if (choice.nativeHeic) {
                        check(actual.getInteger(MediaFormat.KEY_WIDTH) == bitmap.width &&
                            actual.getInteger(MediaFormat.KEY_HEIGHT) == bitmap.height)
                        val gridKeys = listOf(MediaFormat.KEY_TILE_WIDTH, MediaFormat.KEY_TILE_HEIGHT,
                            MediaFormat.KEY_GRID_COLUMNS, MediaFormat.KEY_GRID_ROWS)
                        if (gridKeys.any { actual.containsKey(it) }) {
                            check(gridKeys.all { actual.containsKey(it) })
                            val resultGrid = HeicEncodingGrid(bitmap.width, bitmap.height,
                                actual.getInteger(MediaFormat.KEY_TILE_WIDTH), actual.getInteger(MediaFormat.KEY_TILE_HEIGHT))
                            check(actual.getInteger(MediaFormat.KEY_GRID_COLUMNS) == resultGrid.columns &&
                                actual.getInteger(MediaFormat.KEY_GRID_ROWS) == resultGrid.rows)
                            expectedSamples = resultGrid.count
                        } else expectedSamples = 1
                    } else {
                        check(actual.getInteger(MediaFormat.KEY_WIDTH) == grid.tileWidth &&
                            actual.getInteger(MediaFormat.KEY_HEIGHT) == grid.tileHeight)
                        actual.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC)
                        actual.setInteger(MediaFormat.KEY_WIDTH, bitmap.width)
                        actual.setInteger(MediaFormat.KEY_HEIGHT, bitmap.height)
                        actual.setInteger(MediaFormat.KEY_TILE_WIDTH, grid.tileWidth)
                        actual.setInteger(MediaFormat.KEY_TILE_HEIGHT, grid.tileHeight)
                        actual.setInteger(MediaFormat.KEY_GRID_COLUMNS, grid.columns)
                        actual.setInteger(MediaFormat.KEY_GRID_ROWS, grid.rows)
                        expectedSamples = grid.count
                    }
                    check(actual.containsKey("csd-0")) { "HEIC encoder omitted codec initialization data" }
                    actual.setInteger(MediaFormat.KEY_IS_DEFAULT, 1)
                    track = output.addTrack(actual)
                    output.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    heicRetiring({ encoder.releaseOutputBuffer(index, false) }) {
                        check(info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME == 0) { "HEIC partial codec samples are unsupported" }
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(track >= 0 && sampleCount < expectedSamples)
                            if (!choice.nativeHeic) {
                                check(info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) { "HEIC grid tile is not independent" }
                                check(info.presentationTimeUs == sampleCount * 1_000_000L / 30) { "HEIC grid tile order changed" }
                            }
                            encodedBytes += info.size.toLong()
                            check(encodedBytes <= HeicEncodingGrid.MAX_BYTES) { "HEIC samples exceed their byte budget" }
                            val data = requireNotNull(encoder.getOutputBuffer(index))
                            val sample = MediaCodec.BufferInfo().apply { set(info.offset, info.size, 0, 0) }
                            output.writeSampleData(track, data, sample)
                            sampleCount++
                        }
                        eosReceived = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }
            }
        }
        checkpoint()
        check(eosQueued && inputCount == inputTarget && sampleCount == expectedSamples && sampleCount > 0) {
            "HEIC encoding ended without the complete image"
        }
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        // None of these retirements is skipped if another one throws; no asynchronous close receipt.
        var cleanupFailure: Throwable? = null
        fun retire(action: () -> Unit) {
            try { action() } catch (failure: Throwable) {
                val first = cleanupFailure
                if (first == null) cleanupFailure = failure else if (first !== failure) first.addSuppressed(failure)
            }
        }
        if (codecStarted) retire { codec!!.stop() }
        codec?.let { retire { it.release() } }
        if (muxerStarted) retire { muxer!!.stop() }
        muxer?.let { retire { it.release() } }
        cleanupFailure?.let { if (primary == null) throw it else if (primary !== it) primary.addSuppressed(it) }
    }
}

private fun fillHeicInput(bitmap: Bitmap, image: Image, originX: Int, originY: Int, width: Int, height: Int,
    checkRunning: () -> Unit) {
    check(image.format == ImageFormat.YUV_420_888 && image.width >= width && image.height >= height)
    val planes = image.planes
    check(planes.size == 3)
    planes.forEachIndexed { index, plane ->
        val columns = if (index == 0) width else width / 2
        check(plane.pixelStride > 0 && (columns - 1L) * plane.pixelStride < plane.rowStride) {
            "HEIC input plane has overlapping rows"
        }
    }
    val buffers = planes.map { it.buffer.duplicate() }
    val bases = buffers.map { it.position() }
    fun put(plane: Int, x: Int, y: Int, value: Int) {
        val layout = planes[plane]
        val buffer = buffers[plane]
        buffer.put(heicPlaneOffset(x, y, layout.rowStride, layout.pixelStride, bases[plane], buffer.limit()), value.toByte())
    }
    writeHeicYuv420(bitmap.width, bitmap.height, originX, originY, width, height,
        readRow = { x, y, count, pixels -> bitmap.getPixels(pixels, 0, width, x, y, count, 1) },
        put = ::put, checkRunning = checkRunning)
}
