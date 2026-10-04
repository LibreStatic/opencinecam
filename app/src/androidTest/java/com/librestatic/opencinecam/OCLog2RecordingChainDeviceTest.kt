/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:Suppress("DEPRECATION")

package com.librestatic.opencinecam

import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageWriter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.HevcVuiTransfer
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingFrameSize
import com.librestatic.opencinecam.camera.RecordingGeometry
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end numeric proof of the production OCLog2 recording chain:
 * known P010 frames -> ImageWriter -> the pipeline's SurfaceTexture (samplerExternalOES) ->
 * production fragment shader -> RGBA1010102 BT2020_LINEAR EGL window -> HEVC Main10 encoder ->
 * MP4 -> on-device 10-bit decode -> patch means against a CPU Double reference.
 *
 * The camera is replaced by an [ImageWriter] on [OpenCineLogGpuPipeline.cameraInputSurface]; the
 * pipeline, shaders and encoder configuration are the production ones, unmodified.
 *
 * Sampler assumption. The GPU converts the external YCbCr buffer to R'G'B' from the buffer's
 * dataspace, which this test sets explicitly: DATASPACE_BT2020_HLG (BT.2020 NCL matrix, FULL
 * range) for the HLG tier, DATASPACE_BT709 (BT.709 matrix, LIMITED range) for the SDR tier. The
 * nominal reference applies exactly that conversion. Whether the driver honours it is the
 * device-dependent part, so the result also fits every matrix/range pair on both the sampler and
 * the encoder side and reports the best fit, plus the measured output range from the neutral
 * patches at code 0.10 and 0.90. A best fit other than the nominal one is a finding, not a pass.
 */
@RunWith(AndroidJUnit4::class)
@RequiresDevice
class OCLog2RecordingChainDeviceTest {
    @Test
    fun hlgTierFileCodesMatchCpuReference() = runTier(Tier.HLG)

    @Test
    fun sdrTierFileCodesMatchCpuReference() = runTier(Tier.SDR)

    private fun runTier(tier: Tier) {
        assumeTrue("Explicit ImageWriter dataspaces need API 33", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        assumeTrue("No hardware HEVC Main10 Surface encoder for ${WIDTH}x$HEIGHT@$FPS",
            OpenCineLogGpuPipeline.findEncoder(Size(WIDTH, HEIGHT), FPS) != null)
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val clip = File(directory, "oclog2-recording-chain-${tier.label}.mp4").apply { delete() }
        val resultFile = File(directory, "oclog2-recording-chain-${tier.label}.json").apply { delete() }
        val result = runCatching { measure(tier, clip) }.getOrElse { error ->
            JSONObject()
                .put("schema", SCHEMA)
                .put("tier", tier.label)
                .put("status", "FAILED")
                .put("reasons", JSONArray().put("${error.javaClass.simpleName}:${error.message.orEmpty().take(300)}"))
        }
        val text = result.toString(2) + "\n"
        resultFile.writeText(text, Charsets.UTF_8)
        Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP).chunked(LOG_PART_BYTES)
            .forEachIndexed { index, part -> Log.i(TAG, "${tier.label}-json-part=${index + 1}:$part") }
        assertEquals(text, "PASS", result.getString("status"))
    }

    private fun measure(tier: Tier, clip: File): JSONObject {
        val failures = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var stopSuccess = false
        var evidence: OpenCineLogRecordingEvidence? = null
        val pipeline = OpenCineLogGpuPipeline(
            Size(WIDTH, HEIGHT), tier.sourcePath, preview = null,
            sensorOrientationDegrees = 0, displayRotationDegrees = 0, frontFacing = false,
            viewAssist = false, targetFps = FPS,
            onFailure = { code, message -> failures += "$code:$message"; started.countDown(); stopped.countDown() },
        )
        val patches = tier.patches()
        try {
            val writer = ImageWriter.Builder(pipeline.cameraInputSurface)
                .setWidthAndHeight(WIDTH, HEIGHT)
                .setMaxImages(3)
                .setHardwareBufferFormat(HardwareBuffer.YCBCR_P010)
                .setDataSpace(tier.dataSpace)
                .setUsage(HardwareBuffer.USAGE_CPU_WRITE_OFTEN)
                .build()
            val frame = RecordingFrameSize(WIDTH, HEIGHT)
            val geometry = RecordingGeometry(RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, frame, frame, frame)
            writer.use {
                ParcelFileDescriptor.open(clip, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                    check(pipeline.startRecording(output, BITRATE, geometry, onStarted = { started.countDown() },
                        onStopped = { success, observed -> stopSuccess = success; evidence = observed; stopped.countDown() })) {
                        "pipeline-rejected-recording:$failures"
                    }
                    check(started.await(10, TimeUnit.SECONDS) && failures.isEmpty()) { "recording-start:$failures" }
                    val baseNs = SystemClock.elapsedRealtimeNanos()
                    repeat(FEED_FRAMES) { index ->
                        val image = writer.dequeueInputImage()
                        try {
                            fillP010(image, patches)
                            image.timestamp = baseNs + index * FRAME_NS
                        } catch (failure: Throwable) { image.close(); throw failure }
                        writer.queueInputImage(image)
                        SystemClock.sleep(FRAME_NS / 1_000_000)
                    }
                    // Let the last queued buffer reach the encoder before EOS is signalled.
                    SystemClock.sleep(250)
                    check(pipeline.stopRecording()) { "recording-stop-rejected:$failures" }
                    check(stopped.await(20, TimeUnit.SECONDS)) { "recording-stop-timeout" }
                }
            }
        } finally {
            runCatching { pipeline.closeAsync().get(10, TimeUnit.SECONDS) }
        }
        check(failures.isEmpty()) { "pipeline-failure:$failures" }
        check(stopSuccess) { "recording-finalize-failed" }
        val observed = evidence ?: error("recording-evidence-missing")
        val decoded = decode(clip)
        return analyze(tier, patches, observed, decoded)
    }

    /** Flat patches in a 4x3 grid; P010 stores each 10-bit code in the high bits of a little-endian 16-bit word. */
    private fun fillP010(image: Image, patches: List<Patch>) {
        check(image.width == WIDTH && image.height == HEIGHT) { "writer-size:${image.width}x${image.height}" }
        val planes = image.planes
        check(planes.size == 3) { "p010-plane-count:${planes.size}:format=${image.format}" }
        val luma = planes[0]
        check(luma.pixelStride == 2 && luma.rowStride % 2 == 0) { "p010-luma-layout:${luma.pixelStride}/${luma.rowStride}" }
        val lumaWords = luma.buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val rows = Array(GRID_ROWS) { row -> ShortArray(WIDTH) { x -> (patches[row * GRID_COLUMNS + x / CELL_WIDTH].y shl 6).toShort() } }
        for (y in 0 until HEIGHT) {
            lumaWords.position(y * luma.rowStride / 2)
            lumaWords.put(rows[y / CELL_HEIGHT])
        }
        val cb = planes[1].buffer.order(ByteOrder.LITTLE_ENDIAN)
        val cr = planes[2].buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (cy in 0 until HEIGHT / 2) {
            val row = cy * 2 / CELL_HEIGHT
            for (cx in 0 until WIDTH / 2) {
                val patch = patches[row * GRID_COLUMNS + cx * 2 / CELL_WIDTH]
                cb.putShort(cy * planes[1].rowStride + cx * planes[1].pixelStride, (patch.cb shl 6).toShort())
                cr.putShort(cy * planes[2].rowStride + cx * planes[2].pixelStride, (patch.cr shl 6).toShort())
            }
        }
    }

    private class CellStats {
        var y = 0.0; var yy = 0.0; var cb = 0.0; var cr = 0.0; var lumaCount = 0L; var chromaCount = 0L
        fun meanY() = y / lumaCount
        fun meanCb() = cb / chromaCount
        fun meanCr() = cr / chromaCount
        fun stdY() = sqrt((yy / lumaCount - meanY() * meanY()).coerceAtLeast(0.0))
    }

    private data class Decoded(
        val cells: List<CellStats>,
        val frames: Int,
        val decoder: String,
        val trackFormat: MediaFormat,
        val imageFormat: Int,
    )

    /** Decodes to P010 (software decoder preferred) and accumulates the interior of every cell. */
    private fun decode(clip: File): Decoded {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(clip.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: error("no-video-track")
            val trackFormat = extractor.getTrackFormat(track)
            val mime = requireNotNull(trackFormat.getString(MediaFormat.KEY_MIME))
            val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .filter { info ->
                    runCatching { info.getCapabilitiesForType(mime).colorFormats.contains(P010_COLOR_FORMAT) }.getOrDefault(false)
                }
                .sortedBy { if (it.isSoftwareOnly) 0 else 1 }
                .map { it.name }
            check(candidates.isNotEmpty()) { "no-p010-decoder:$mime" }
            val attempts = mutableListOf<String>()
            for (name in candidates) {
                extractor.unselectTrack(track)
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val outcome = runCatching { decodeWith(name, extractor, trackFormat) }
                outcome.getOrNull()?.let { return it }
                attempts += "$name:${outcome.exceptionOrNull()?.message.orEmpty().take(120)}"
            }
            error("no-decoder-produced-p010:$attempts")
        } finally {
            extractor.release()
        }
    }

    private fun decodeWith(name: String, extractor: MediaExtractor, trackFormat: MediaFormat): Decoded {
        val codec = MediaCodec.createByCodecName(name)
        val cells = List(GRID_COLUMNS * GRID_ROWS) { CellStats() }
        var analyzed = 0
        var outputIndex = 0
        var imageFormat = -1
        try {
            val format = MediaFormat(trackFormat).apply { setInteger(MediaFormat.KEY_COLOR_FORMAT, P010_COLOR_FORMAT) }
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS
            while (!outputDone) {
                check(SystemClock.elapsedRealtime() < deadline) { "decode-timeout" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(requireNotNull(codec.getInputBuffer(index)), 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index < 0) continue
                if (info.size > 0) {
                    if (outputIndex >= SKIP_DECODED_FRAMES && analyzed < ANALYZED_FRAMES) {
                        val image = codec.getOutputImage(index) ?: error("decoder-image-unavailable")
                        image.use {
                            imageFormat = it.format
                            check(it.format == android.graphics.ImageFormat.YCBCR_P010) { "decoder-output-not-p010:${it.format}" }
                            accumulate(it, cells)
                        }
                        analyzed++
                    }
                    outputIndex++
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        check(analyzed >= MIN_ANALYZED_FRAMES) { "decoded-frames-low:$analyzed/$outputIndex" }
        return Decoded(cells, analyzed, name, trackFormat, imageFormat)
    }

    private fun accumulate(image: Image, cells: List<CellStats>) {
        val crop = image.cropRect
        check(crop.width() == WIDTH && crop.height() == HEIGHT) { "decoded-size:${crop.width()}x${crop.height()}" }
        val planes = image.planes
        val luma = planes[0].buffer.order(ByteOrder.LITTLE_ENDIAN)
        val cbPlane = planes[1].buffer.order(ByteOrder.LITTLE_ENDIAN)
        val crPlane = planes[2].buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (row in 0 until GRID_ROWS) for (column in 0 until GRID_COLUMNS) {
            val stats = cells[row * GRID_COLUMNS + column]
            val x0 = column * CELL_WIDTH + MARGIN
            val y0 = row * CELL_HEIGHT + MARGIN
            for (y in y0 until y0 + CELL_HEIGHT - 2 * MARGIN) {
                val base = (y + crop.top) * planes[0].rowStride
                for (x in x0 until x0 + CELL_WIDTH - 2 * MARGIN) {
                    val value = (luma.getShort(base + (x + crop.left) * planes[0].pixelStride).toInt() and 0xffff) ushr 6
                    stats.y += value; stats.yy += value.toDouble() * value; stats.lumaCount++
                }
            }
            for (cy in y0 / 2 until (y0 + CELL_HEIGHT - 2 * MARGIN) / 2) {
                val cbBase = (cy + crop.top / 2) * planes[1].rowStride
                val crBase = (cy + crop.top / 2) * planes[2].rowStride
                for (cx in x0 / 2 until (x0 + CELL_WIDTH - 2 * MARGIN) / 2) {
                    stats.cb += (cbPlane.getShort(cbBase + (cx + crop.left / 2) * planes[1].pixelStride).toInt() and 0xffff) ushr 6
                    stats.cr += (crPlane.getShort(crBase + (cx + crop.left / 2) * planes[2].pixelStride).toInt() and 0xffff) ushr 6
                    stats.chromaCount++
                }
            }
        }
    }

    private fun analyze(tier: Tier, patches: List<Patch>, evidence: OpenCineLogRecordingEvidence, decoded: Decoded): JSONObject {
        val reasons = mutableListOf<String>()
        // Pick the cell permutation that matches the nominal luma best; anything but identity is a geometry finding.
        val orientation = Orientation.entries.minBy { candidate ->
            patches.indices.sumOf { abs(decoded.cells[candidate.map(it)].meanY() - expected(tier, patches[it], tier.nominalSampler, NOMINAL_OUTPUT)[0]) }
        }
        if (orientation != Orientation.IDENTITY) reasons += "decoded-orientation:${orientation.name}"
        val actual = patches.indices.map { decoded.cells[orientation.map(it)] }
        val patchReports = JSONArray()
        var maxErrorLsb = 0.0
        patches.forEachIndexed { index, patch ->
            val cell = actual[index]
            val want = expected(tier, patch, tier.nominalSampler, NOMINAL_OUTPUT)
            val got = doubleArrayOf(cell.meanY(), cell.meanCb(), cell.meanCr())
            val errors = DoubleArray(3) { got[it] - want[it] }
            val patchMax = errors.maxOf { abs(it) }
            maxErrorLsb = maxOf(maxErrorLsb, patchMax)
            if (patchMax > TOLERANCE_LSB) reasons += "patch-${patch.name}:%.2fLSB".format(patchMax)
            val wantRgb = shaderCodes(tier, tier.nominalSampler.toSignal(patch)).map { quantize(it) * CODE_MAX }
            val gotRgb = NOMINAL_OUTPUT.toSignal(got).map { it * CODE_MAX }
            patchReports.put(JSONObject()
                .put("name", patch.name)
                .put("inputYCbCr", JSONArray(listOf(patch.y, patch.cb, patch.cr)))
                .put("expectedYCbCr", round2(want))
                .put("actualYCbCr", round2(got))
                .put("errorLsb", round2(errors))
                .put("maxAbsErrorLsb", round2(patchMax))
                .put("expectedOcLogRgbCodes", round2(wantRgb.toDoubleArray()))
                .put("actualOcLogRgbCodes", round2(gotRgb.toDoubleArray()))
                .put("lumaStdDevLsb", round2(cell.stdY())))
        }
        val black = actual[patches.indexOfFirst { it.name == "code-0.10" }].meanY()
        val white = actual[patches.indexOfFirst { it.name == "code-0.90" }].meanY()
        val fullBlack = NOMINAL_OUTPUT.toCodes(doubleArrayOf(0.10, 0.10, 0.10))[0]
        val limitedBlack = YcbcrContract(YcbcrMatrix.BT2020, fullRange = false).toCodes(doubleArrayOf(0.10, 0.10, 0.10))[0]
        val fullWhite = NOMINAL_OUTPUT.toCodes(doubleArrayOf(0.90, 0.90, 0.90))[0]
        val limitedWhite = YcbcrContract(YcbcrMatrix.BT2020, fullRange = false).toCodes(doubleArrayOf(0.90, 0.90, 0.90))[0]
        val measuredRange = when {
            abs(black - fullBlack) <= RANGE_DECISION_LSB && abs(white - fullWhite) <= RANGE_DECISION_LSB -> "full"
            abs(black - limitedBlack) <= RANGE_DECISION_LSB && abs(white - limitedWhite) <= RANGE_DECISION_LSB -> "limited"
            else -> "indeterminate"
        }
        if (measuredRange != "full") reasons += "measured-range:$measuredRange"
        val fits = YcbcrContract.ALL.flatMap { sampler -> YcbcrContract.ALL.map { output -> sampler to output } }
            .map { (sampler, output) ->
                val error = patches.indices.maxOf { index ->
                    val want = expected(tier, patches[index], sampler, output)
                    val cell = actual[index]
                    maxOf(abs(cell.meanY() - want[0]), abs(cell.meanCb() - want[1]), abs(cell.meanCr() - want[2]))
                }
                Triple(sampler, output, error)
            }
            .sortedBy { it.third }
        val best = fits.first()
        if (best.first != tier.nominalSampler || best.second != NOMINAL_OUTPUT) {
            reasons += "best-fit-contract:sampler=${best.first.label},output=${best.second.label}"
        }
        if (evidence.transformSha256 != OpenCineLogGpuPipeline.transformSha256(tier.sourcePath)) reasons += "transform-sha256-mismatch"
        if (evidence.sourcePath != tier.sourcePath) reasons += "source-path:${evidence.sourcePath}"
        when (val mismatched = evidence.sourceDataSpaceMismatchedFrames) {
            null -> reasons += "source-dataspace-unreported"
            0L -> Unit
            else -> reasons += "source-dataspace-mismatch:$mismatched:${evidence.unexpectedSourceDataSpace}"
        }
        if (evidence.sourceDataSpace != tier.dataSpace) reasons += "source-dataspace:${evidence.sourceDataSpace}"
        if (evidence.range != "full" || evidence.codecProfile != "Main10" || evidence.eglRenderTargetBits != 10) {
            reasons += "evidence-contract:${evidence.range}/${evidence.codecProfile}/${evidence.eglRenderTargetBits}"
        }
        if (evidence.encodedFrames < MIN_ENCODED_FRAMES) reasons += "encoded-frames-low:${evidence.encodedFrames}"
        val trackRange = decoded.trackFormat.integerOrNull(MediaFormat.KEY_COLOR_RANGE)
        val trackStandard = decoded.trackFormat.integerOrNull(MediaFormat.KEY_COLOR_STANDARD)
        val trackTransfer = decoded.trackFormat.integerOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        if (trackRange != MediaFormat.COLOR_RANGE_FULL) reasons += "container-color-range:$trackRange"
        if (trackStandard != MediaFormat.COLOR_STANDARD_BT2020) reasons += "container-color-standard:$trackStandard"
        // OCLog2 claims no standard transfer: an HDR tag would make players tone-map the log image.
        if (evidence.containerVuiTransfer != HevcVuiTransfer.UNSPECIFIED) reasons += "container-vui-transfer:${evidence.containerVuiTransfer}"
        if (trackTransfer == MediaFormat.COLOR_TRANSFER_ST2084 || trackTransfer == MediaFormat.COLOR_TRANSFER_HLG) reasons += "container-color-transfer:$trackTransfer"
        return JSONObject()
            .put("schema", SCHEMA)
            .put("tier", tier.label)
            .put("status", if (reasons.isEmpty()) "PASS" else "FAILED")
            .put("reasons", JSONArray(reasons))
            .put("device", JSONObject()
                .put("fingerprint", Build.FINGERPRINT)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("soc", Build.SOC_MODEL))
            .put("width", WIDTH)
            .put("height", HEIGHT)
            .put("feedFormat", "P010")
            .put("feedDataSpace", tier.dataSpace)
            .put("nominalSamplerContract", tier.nominalSampler.label)
            .put("nominalOutputContract", NOMINAL_OUTPUT.label)
            .put("toleranceLsb", TOLERANCE_LSB)
            .put("maxAbsErrorLsb", round2(maxErrorLsb))
            .put("orientation", orientation.name)
            .put("measuredRange", measuredRange)
            .put("measuredBlackY", round2(black))
            .put("measuredWhiteY", round2(white))
            .put("fullRangeBlackWhiteY", round2(doubleArrayOf(fullBlack, fullWhite)))
            .put("limitedRangeBlackWhiteY", round2(doubleArrayOf(limitedBlack, limitedWhite)))
            .put("bestFits", JSONArray(fits.take(4).map { "sampler=${it.first.label},output=${it.second.label}:%.2fLSB".format(it.third) }))
            .put("patches", patchReports)
            .put("evidence", JSONObject()
                .put("codecName", evidence.codecName)
                .put("encodedFrames", evidence.encodedFrames)
                .put("sourcePath", evidence.sourcePath.name)
                .put("sourceDataSpace", evidence.sourceDataSpace ?: JSONObject.NULL)
                .put("sourceDataSpaceMismatchedFrames", evidence.sourceDataSpaceMismatchedFrames ?: JSONObject.NULL)
                .put("range", evidence.range)
                .put("transformSha256", evidence.transformSha256)
                .put("ycbcrConversion", evidence.ycbcrConversion ?: JSONObject.NULL)
                .put("encoderVuiTransfer", evidence.encoderVuiTransfer ?: JSONObject.NULL)
                .put("containerVuiTransfer", evidence.containerVuiTransfer ?: JSONObject.NULL)
                .put("expectedTransformSha256", OpenCineLogGpuPipeline.transformSha256(tier.sourcePath)))
            .put("decode", JSONObject()
                .put("decoder", decoded.decoder)
                .put("imageFormat", decoded.imageFormat)
                .put("analyzedFrames", decoded.frames)
                .put("containerColorRange", trackRange ?: JSONObject.NULL)
                .put("containerColorStandard", trackStandard ?: JSONObject.NULL)
                .put("containerColorTransfer", trackTransfer ?: JSONObject.NULL))
    }

    private fun MediaFormat.integerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
    private fun round2(value: Double): Double = (value * 100).roundToInt() / 100.0
    private fun round2(values: DoubleArray): JSONArray = JSONArray(values.map(::round2))

    /** Reference file codes for one patch: sampler conversion -> shader in Double -> RGBA1010102 -> output matrix. */
    private fun expected(tier: Tier, patch: Patch, sampler: YcbcrContract, output: YcbcrContract): DoubleArray =
        output.toCodes(shaderCodes(tier, sampler.toSignal(patch)).map(::quantize).toDoubleArray())

    private fun quantize(code: Double): Double = (code.coerceIn(0.0, 1.0) * CODE_MAX).roundToInt() / CODE_MAX

    /** Double mirror of the production OUTPUT_OCLOG branch of HLG_FRAGMENT_SHADER / SDR_FRAGMENT_SHADER. */
    private fun shaderCodes(tier: Tier, signal: DoubleArray): List<Double> = when (tier) {
        Tier.HLG -> bt709ToBt2020(signal.map(::inverseHlg)).map(::encodeOcLog2)
        Tier.SDR -> bt709ToBt2020(signal.map(::inverseRec709)).map(::encodeOcLog2)
    }

    private fun bt709ToBt2020(linear: List<Double>): List<Double> = listOf(
        0.627404 * linear[0] + 0.329283 * linear[1] + 0.043313 * linear[2],
        0.069097 * linear[0] + 0.919540 * linear[1] + 0.011362 * linear[2],
        0.016391 * linear[0] + 0.088013 * linear[1] + 0.895595 * linear[2],
    )

    private enum class Orientation(val map: (Int) -> Int) {
        IDENTITY({ it }),
        FLIP_VERTICAL({ (GRID_ROWS - 1 - it / GRID_COLUMNS) * GRID_COLUMNS + it % GRID_COLUMNS }),
        FLIP_HORIZONTAL({ it / GRID_COLUMNS * GRID_COLUMNS + GRID_COLUMNS - 1 - it % GRID_COLUMNS }),
        ROTATE_180({ GRID_COLUMNS * GRID_ROWS - 1 - it }),
    }

    private data class Patch(val name: String, val y: Int, val cb: Int, val cr: Int)

    private enum class YcbcrMatrix(val kr: Double, val kb: Double) {
        BT2020(0.2627, 0.0593), BT709(0.2126, 0.0722), BT601(0.299, 0.114),
    }

    /** Ten-bit Y'CbCr quantization per BT.2100 / BT.709 (full: D = 1023E (+512); limited: 4(219E+16), 4(224E+128)). */
    private data class YcbcrContract(val matrix: YcbcrMatrix, val fullRange: Boolean) {
        val label = "${matrix.name}/${if (fullRange) "full" else "limited"}"

        fun toCodes(rgb: DoubleArray): DoubleArray {
            val kg = 1.0 - matrix.kr - matrix.kb
            val y = matrix.kr * rgb[0] + kg * rgb[1] + matrix.kb * rgb[2]
            val cb = (rgb[2] - y) / (2 * (1 - matrix.kb))
            val cr = (rgb[0] - y) / (2 * (1 - matrix.kr))
            return if (fullRange) doubleArrayOf(y * CODE_MAX, cb * CODE_MAX + 512, cr * CODE_MAX + 512)
            else doubleArrayOf(4 * (219 * y + 16), 4 * (224 * cb + 128), 4 * (224 * cr + 128))
        }

        fun toSignal(patch: Patch): DoubleArray =
            toSignal(doubleArrayOf(patch.y.toDouble(), patch.cb.toDouble(), patch.cr.toDouble())).map { it.coerceIn(0.0, 1.0) }.toDoubleArray()

        /** Unclamped R'G'B'; the patch overload clamps, as a normalized texture fetch does. */
        fun toSignal(codes: DoubleArray): DoubleArray {
            val (y, cb, cr) = if (fullRange) Triple(codes[0] / CODE_MAX, (codes[1] - 512) / CODE_MAX, (codes[2] - 512) / CODE_MAX)
            else Triple((codes[0] / 4 - 16) / 219, (codes[1] / 4 - 128) / 224, (codes[2] / 4 - 128) / 224)
            val kg = 1.0 - matrix.kr - matrix.kb
            val r = y + 2 * (1 - matrix.kr) * cr
            val b = y + 2 * (1 - matrix.kb) * cb
            val g = (y - matrix.kr * r - matrix.kb * b) / kg
            return doubleArrayOf(r, g, b)
        }

        fun fromSignal(name: String, r: Double, g: Double, b: Double): Patch {
            val codes = toCodes(doubleArrayOf(r, g, b)).map { it.roundToInt().coerceIn(0, 1023) }
            return Patch(name, codes[0], codes[1], codes[2])
        }

        companion object {
            val ALL = YcbcrMatrix.entries.flatMap { listOf(YcbcrContract(it, true), YcbcrContract(it, false)) }
        }
    }

    private enum class Tier(val label: String, val sourcePath: OpenCineLogSourcePath, val dataSpace: Int, val nominalSampler: YcbcrContract) {
        HLG("hlg", OpenCineLogSourcePath.HLG10_BT2020, DataSpace.DATASPACE_BT2020_HLG, YcbcrContract(YcbcrMatrix.BT2020, fullRange = true)),
        SDR("sdr", OpenCineLogSourcePath.SDR_BT709_ISP, DataSpace.DATASPACE_BT709, YcbcrContract(YcbcrMatrix.BT709, fullRange = false));

        /** Twelve patches, row-major in the 4x3 grid; neutral ones are defined by scene-linear exposure. */
        fun patches(): List<Patch> {
            val oetf: (Double) -> Double = if (this == HLG) ::hlgOetf else ::rec709Oetf
            fun scene(name: String, r: Double, g: Double = r, b: Double = r) = nominalSampler.fromSignal(name, oetf(r), oetf(g), oetf(b))
            return when (this) {
                HLG -> listOf(
                    scene("code-0.10", 0.0),
                    // R' < 0 at Y' = 0: exercises the inverse-HLG input clamp on one channel only.
                    Patch("below-black-red", 0, 512, 412),
                    scene("near-black", 0.002),
                    scene("shadow", 0.02),
                    scene("mid-gray", 0.18),
                    scene("highlight", 0.5),
                    scene("near-white", 0.9),
                    scene("code-0.90", 1.0),
                    scene("red", PRIMARY, 0.0, 0.0),
                    scene("green", 0.0, PRIMARY, 0.0),
                    scene("blue", 0.0, 0.0, PRIMARY),
                    // R' > 1 at Y' = 1023: the upper clamp, with G' just below one.
                    Patch("above-white-red", 1023, 512, 612),
                )
                SDR -> listOf(
                    scene("code-0.10", 0.0),
                    // Limited-range footroom: negative R'G'B' must clamp to code 0.10, not wrap.
                    Patch("below-black", 40, 512, 512),
                    scene("near-black", 0.002),
                    scene("shadow", 0.02),
                    scene("mid-gray", 0.18),
                    scene("highlight", 0.5),
                    scene("near-white", 0.9),
                    scene("code-0.90", 1.0),
                    scene("red", PRIMARY, 0.0, 0.0),
                    scene("green", 0.0, PRIMARY, 0.0),
                    scene("blue", 0.0, 0.0, PRIMARY),
                    // Limited-range headroom: R'G'B' > 1 must clamp to code 0.90.
                    Patch("super-white", 1000, 512, 512),
                )
            }
        }
    }

    companion object {
        private const val TAG = "OCLog2RecordingChain"
        private const val SCHEMA = "opencinecam-oclog2-recording-chain-v1"
        private const val LOG_PART_BYTES = 3000
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val FPS = 30
        private const val FRAME_NS = 1_000_000_000L / FPS
        private const val GRID_COLUMNS = 4
        private const val GRID_ROWS = 3
        private const val CELL_WIDTH = WIDTH / GRID_COLUMNS
        private const val CELL_HEIGHT = HEIGHT / GRID_ROWS
        /** Luma pixels skipped on every cell edge: clears 4:2:0 chroma siting, bilinear sampling and HEVC block edges. */
        private const val MARGIN = 64
        private const val FEED_FRAMES = 60
        private const val MIN_ENCODED_FRAMES = 30L
        private const val SKIP_DECODED_FRAMES = 10
        private const val ANALYZED_FRAMES = 20
        private const val MIN_ANALYZED_FRAMES = 10
        private const val DECODE_TIMEOUT_MS = 60_000L
        /** Scaled by the pipeline to rate/30 and clamped to its 12..200 Mb/s window; flat patches need far less. */
        private const val BITRATE = 100_000_000
        private const val CODE_MAX = 1023.0
        private const val P010_COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010

        /**
         * Patch-mean tolerance per Y'/Cb/Cr component, in 10-bit LSB. The transform itself is pinned
         * to 2e-5 (0.02 LSB) by OCLog2GpuNumericTest; the remaining budget is the chain:
         * sampler YCbCr->RGB arithmetic and normalization of the 16-bit P010 word (<= 1 LSB, gain
         * <= ~1.1 through inverse-HLG + OCLog2 at every patch level), RGBA1010102 render-target
         * rounding (0.5 LSB), the encoder's fixed-point RGB->YCbCr conversion (<= 1 LSB) and HEVC
         * DC drift on a flat interior at high bitrate (<= 1 LSB of the mean). 4 LSB bounds that
         * sum, while every alternative contract it must reject differs by far more: limited range
         * moves code 0.10 from Y 102 to 152, BT.709 vs BT.2020 matrices move saturated chroma by
         * tens of LSB. Do not widen this to make a device pass.
         */
        private const val TOLERANCE_LSB = 4.0
        /**
         * Scene-linear level of the pure-primary patches. Saturated primaries keep every wrong
         * sampler/encoder matrix-and-range pair >= 20 LSB away from the nominal codes in both
         * tiers; with milder colours a BT.709 matrix on both sides nearly cancels (~5 LSB).
         */
        private const val PRIMARY = 0.7
        /** Black/white luma must sit this close to one range hypothesis to call the range measured. */
        private const val RANGE_DECISION_LSB = 8.0
        private val NOMINAL_OUTPUT = YcbcrContract(YcbcrMatrix.BT2020, fullRange = true)

        private const val HLG_A = 0.17883277
        private const val HLG_B = 0.28466892
        private const val HLG_C = 0.55991073

        private fun hlgOetf(x: Double): Double =
            if (x <= 1.0 / 12) sqrt(3 * x) else HLG_A * ln(12 * x - HLG_B) + HLG_C

        private fun inverseHlg(signal: Double): Double {
            val e = signal.coerceIn(0.0, 1.0)
            return if (e <= 0.5) e * e / 3 else (exp((e - HLG_C) / HLG_A) + HLG_B) / 12
        }

        private fun rec709Oetf(x: Double): Double = if (x < 0.018) 4.5 * x else 1.099 * x.pow(0.45) - 0.099

        /** Unclamped, like the shader: footroom stays negative until the OCLog2 clamp. */
        private fun inverseRec709(e: Double): Double = if (e < 0.081) e / 4.5 else ((e + 0.099) / 1.099).pow(1 / 0.45)

        private fun encodeOcLog2(x: Double): Double = 0.10 + 0.80 * ln(1 + 50 * x.coerceIn(0.0, 1.0)) / ln(51.0)
    }
}
