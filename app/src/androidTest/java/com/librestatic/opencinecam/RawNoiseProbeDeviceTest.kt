/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exploratory: measures the main camera's real RAW temporal noise as a photon transfer curve per ISO, the
 * calibration for a noise-adaptive quantised RAW video mode (the declared SENSOR_NOISE_PROFILE is not trusted).
 *
 * Point the camera at a static, evenly varied scene (a tripod, a scene with shadows to highlights). For each ISO
 * it captures consecutive frame pairs with a fixed manual exposure and, over 16x16 same-channel blocks, takes the
 * pair mean and var(A-B)/2. Blocks are binned by level above black and fitted to sigma² = a + b·(x − black).
 * Frames are processed in memory only; nothing but the JSON report is written.
 *
 * Optional arguments:
 * - `noiseCamera`: camera id (default 0).
 * - `noiseIsos`: comma list (default 100,200,400,800,1600,3200,6400), clamped to the sensitivity range.
 * - `noisePairs`: frame pairs per ISO (default 6).
 * - `noiseExposureNs`: a fixed exposure for every ISO (default: from AE, scaled by e0·iso0/iso, at most 1/30 s).
 * - `noiseFormat`: `raw10` or `raw_sensor` (default RAW10 when offered, else RAW_SENSOR).
 * - `noisePreview`: `false` drops the preview SurfaceTexture output (default true).
 *
 * Logcat under [TAG]: one `iso=` summary line per ISO, the report as base64 `json-part=i/n:` lines and a final
 * `status=` line. The report is also written to `getExternalFilesDir(null)/occ-raw-noise.json`. Fit quality
 * never fails the test; camera errors do.
 */
@RunWith(AndroidJUnit4::class)
class RawNoiseProbeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @After fun cleanup() = instrumentation.uiAutomation.dropShellPermissionIdentity()

    @Test
    fun noise() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val report = JSONObject()
            .put("kind", "raw-noise")
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("device", Build.DEVICE).put("fingerprint", Build.FINGERPRINT))
        val failure = runCatching { probe(report) }.exceptionOrNull()
        if (failure != null) report.put("error", "${failure.javaClass.simpleName}: ${failure.message?.take(300)}")
        val text = report.toString(2) + "\n"
        val directory = requireNotNull(context.getExternalFilesDir(null)) { "external files directory unavailable" }
        val file = File(directory, JSON_NAME)
        file.writeText(text, Charsets.UTF_8)
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val parts = (encoded.length + LOG_PART_BYTES - 1) / LOG_PART_BYTES
        encoded.chunked(LOG_PART_BYTES).forEachIndexed { index, part -> Log.i(TAG, "json-part=${index + 1}/$parts:$part") }
        Log.i(TAG, "status=${if (failure == null) "OK" else "FAILED"} file=${file.path}")
        if (failure != null) throw AssertionError("RAW noise probe failed: ${failure.message}", failure)
    }

    private fun probe(report: JSONObject) {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = args.getString("noiseCamera") ?: "0"
        val ch = manager.getCameraCharacteristics(id)
        val map = requireNotNull(ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)) { "no stream map" }
        val format = when (args.getString("noiseFormat")?.trim()?.lowercase()) {
            "raw10" -> ImageFormat.RAW10
            "raw_sensor", "raw16" -> ImageFormat.RAW_SENSOR
            else -> if (ImageFormat.RAW10 in map.outputFormats) ImageFormat.RAW10 else ImageFormat.RAW_SENSOR
        }
        val size = requireNotNull(map.getOutputSizes(format)?.maxByOrNull { it.width.toLong() * it.height }) { "no RAW size for format $format" }
        val width = size.width
        val height = size.height
        val minFrameNs = map.getOutputMinFrameDuration(format, size)

        // Blocks stay inside the active array when the image covers the full pixel array.
        val pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val region = if (active != null && pixelArray != null && pixelArray.width == width && pixelArray.height == height) {
            Rect(active).apply { intersect(0, 0, width, height) }
        } else Rect(0, 0, width, height)
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1
        val labels = cfaLabels(cfa)
        val sensRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val staticWhite = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val staticBlack = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val controlModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES)?.toSet().orEmpty()
        val nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet().orEmpty()
        val hotModes = ch.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.toSet().orEmpty()
        val shadingModes = ch.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)?.toSet().orEmpty()
        // Weakest first; ZERO_SHUTTER_LAG is never chosen.
        val nrMode = listOf(
            CameraMetadata.NOISE_REDUCTION_MODE_OFF, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL,
            CameraMetadata.NOISE_REDUCTION_MODE_FAST, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        ).firstOrNull { it in nrModes }
        val usePreview = args.getString("noisePreview")?.trim()?.toBoolean() ?: true
        val pairs = args.getString("noisePairs")?.trim()?.toIntOrNull()?.coerceIn(1, 100) ?: 6
        val fixedExposure = args.getString("noiseExposureNs")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        val isos = (args.getString("noiseIsos") ?: "100,200,400,800,1600,3200,6400").split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { if (sensRange != null) it.coerceIn(sensRange.lower, sensRange.upper) else it }
            .distinct()

        report.put("cameraId", id).put("format", if (format == ImageFormat.RAW10) "RAW10" else "RAW_SENSOR")
            .put("size", "${width}x$height").put("minFrameNs", minFrameNs)
            .put("region", JSONArray(listOf(region.left, region.top, region.right, region.bottom)))
            .put("cfa", cfa).put("cfaLabels", JSONArray(labels.toList()))
            .put("sensitivityRange", sensRange?.toString() ?: JSONObject.NULL)
            .put("exposureRange", expRange?.toString() ?: JSONObject.NULL)
            .put("staticWhiteLevel", staticWhite)
            .put("staticBlackLevel", staticBlack?.let { p -> JSONArray(List(4) { p.getOffsetForIndex(it % 2, it / 2) }) } ?: JSONObject.NULL)
            .put("noiseReductionMode", nrMode ?: JSONObject.NULL).put("controlModeOff", CameraMetadata.CONTROL_MODE_OFF in controlModes)
            .put("hotPixelOff", CameraMetadata.HOT_PIXEL_MODE_OFF in hotModes).put("shadingOff", CameraMetadata.SHADING_MODE_OFF in shadingModes)
            .put("pairs", pairs).put("preview", usePreview)
        Log.i(TAG, "camera=$id format=${report.getString("format")} size=${width}x$height region=$region cfa=$cfa isos=$isos pairs=$pairs")

        val thread = HandlerThread("occ-raw-noise").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { handler.post(it) }
        val reader = ImageReader.newInstance(width, height, format, MAX_IMAGES)
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(1920, 1080) }
        val preview = Surface(texture)
        val cameraError = AtomicReference<String?>()
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            val opened = CountDownLatch(1)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
                override fun onDisconnected(d: CameraDevice) { cameraError.set("disconnected"); d.close(); opened.countDown() }
                override fun onError(d: CameraDevice, code: Int) { cameraError.set("camera-error-$code"); d.close(); opened.countDown() }
            }, handler)
            check(opened.await(10, TimeUnit.SECONDS)) { "open timeout" }
            cameraError.get()?.let { error(it) }
            val cam = requireNotNull(device)
            val outputs = listOfNotNull(OutputConfiguration(reader.surface), if (usePreview) OutputConfiguration(preview) else null)
            val configured = CountDownLatch(1)
            cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) { cameraError.set("configure-failed"); configured.countDown() }
                }))
            check(configured.await(10, TimeUnit.SECONDS)) { "configure timeout" }
            cameraError.get()?.let { error(it) }
            val s = requireNotNull(session)

            fun drain() { while (true) (runCatching { reader.acquireNextImage() }.getOrNull() ?: return).close() }
            fun nextImage(): Image {
                val deadline = SystemClock.elapsedRealtime() + FRAME_TIMEOUT_MS
                while (true) {
                    cameraError.get()?.let { error(it) }
                    reader.acquireNextImage()?.let { return it }
                    check(SystemClock.elapsedRealtime() < deadline) { "frame timeout" }
                    SystemClock.sleep(2)
                }
            }
            fun stop() { runCatching { s.stopRepeating() }; SystemClock.sleep(300); drain() }

            // 1. Auto exposure, for the reference exposure × ISO.
            val aeResult = AtomicReference<TotalCaptureResult?>()
            val aeRequest = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(if (usePreview) preview else reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }.build()
            s.setRepeatingRequest(aeRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) = aeResult.set(result)
            }, handler)
            val aeStart = SystemClock.elapsedRealtime()
            var aeConverged = false
            while (SystemClock.elapsedRealtime() - aeStart < AE_TIMEOUT_MS) {
                cameraError.get()?.let { error(it) }
                drain()
                val state = aeResult.get()?.get(CaptureResult.CONTROL_AE_STATE)
                if (SystemClock.elapsedRealtime() - aeStart > 300 && (state == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                        state == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED || state == CameraMetadata.CONTROL_AE_STATE_LOCKED)) {
                    aeConverged = true
                    break
                }
                SystemClock.sleep(20)
            }
            stop()
            val e0 = aeResult.get()?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
            val iso0 = aeResult.get()?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            report.put("ae", JSONObject().put("converged", aeConverged).put("exposureNs", e0).put("iso", iso0)
                .put("fixedExposureNs", fixedExposure ?: JSONObject.NULL))
            Log.i(TAG, "ae converged=$aeConverged exposureNs=$e0 iso=$iso0")

            // 2. One manual run per ISO; at most two frames' arrays are alive.
            val a = ShortArray(width * height)
            val b = ShortArray(width * height)
            val rowBytes = if (format == ImageFormat.RAW10) width * 5 / 4 else width * 2
            val row = ByteArray(rowBytes)
            val results = ConcurrentHashMap<Long, TotalCaptureResult>()
            val failures = AtomicInteger()
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { results[it] = result }
                }
                override fun onCaptureFailed(cs: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) { failures.incrementAndGet() }
            }
            fun unpack(image: Image, out: ShortArray) {
                val plane = image.planes[0]
                val buffer = plane.buffer
                for (y in 0 until height) {
                    buffer.position(y * plane.rowStride)
                    buffer.get(row, 0, rowBytes)
                    if (format == ImageFormat.RAW10) RawNoiseStats.unpackRaw10Row(row, 0, width, out, y * width)
                    else RawNoiseStats.unpackRaw16Row(row, 0, width, out, y * width)
                }
            }
            fun awaitResult(ts: Long): TotalCaptureResult? {
                val deadline = SystemClock.elapsedRealtime() + 1_000
                while (SystemClock.elapsedRealtime() < deadline) { results[ts]?.let { return it }; SystemClock.sleep(5) }
                return null
            }

            val maxExposure = minOf(expRange?.upper ?: MAX_EXPOSURE_NS, MAX_EXPOSURE_NS)
            val isoReports = JSONArray()
            report.put("isos", isoReports)
            for (iso in isos) {
                val wanted = fixedExposure ?: (e0.toDouble() * iso0 / iso).toLong()
                val upper = if (fixedExposure != null) (expRange?.upper ?: Long.MAX_VALUE) else maxExposure
                val lower = expRange?.lower ?: 1L
                val exposure = wanted.coerceIn(lower, maxOf(lower, upper))
                val clamped = exposure != wanted
                val frameNs = maxOf(exposure, minFrameNs)
                val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    if (usePreview) addTarget(preview)
                    if (CameraMetadata.CONTROL_MODE_OFF in controlModes) set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
                    nrMode?.let { set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
                    if (CameraMetadata.HOT_PIXEL_MODE_OFF in hotModes) set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
                    if (CameraMetadata.SHADING_MODE_OFF in shadingModes) set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_OFF)
                    set(CaptureRequest.BLACK_LEVEL_LOCK, true)
                }.build()
                results.clear()
                failures.set(0)
                s.setRepeatingRequest(request, callback, handler)
                repeat(DISCARD_FRAMES) { nextImage().close() }
                val blocks = RawNoiseStats.Blocks()
                var black = FloatArray(4)
                var white = staticWhite
                var firstResult: TotalCaptureResult? = null
                var gappedPairs = 0
                var maxPairDtNs = 0L
                var saturated = 0
                for (k in 0 until pairs) {
                    val imageA = nextImage()
                    val imageB = nextImage()
                    val tsA = imageA.timestamp
                    val tsB = imageB.timestamp
                    unpack(imageA, a); imageA.close()
                    unpack(imageB, b); imageB.close()
                    val dt = tsB - tsA
                    maxPairDtNs = maxOf(maxPairDtNs, dt)
                    if (dt > frameNs * 3 / 2) gappedPairs++
                    val result = awaitResult(tsA)
                    if (firstResult == null) firstResult = result
                    // Per image-parity position: (row % 2) * 2 + column % 2.
                    black = result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.takeIf { it.size >= 4 }
                        ?: staticBlack?.let { p -> FloatArray(4) { p.getOffsetForIndex(it % 2, it / 2).toFloat() } }
                        ?: FloatArray(4) { 64f }
                    white = result?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: staticWhite
                    val minBlack = black.min()
                    val saturation = (white - 0.05f * (white - minBlack)).toInt()
                    saturated += RawNoiseStats.accumulatePair(a, b, width, region, black, saturation, blocks)
                }
                stop()
                cameraError.get()?.let { error(it) }
                val range = (white - black.min()).toDouble()
                val bins = RawNoiseStats.bins(blocks, range)
                val fit = RawNoiseStats.fit(bins)
                val usedBins = bins.filter { it.count >= RawNoiseStats.MIN_BIN_BLOCKS }
                val channelFits = JSONObject()
                val measuredPerChannel = arrayOfNulls<RawNoiseStats.Fit>(4)
                for (c in 0 until 4) {
                    val cf = RawNoiseStats.fit(RawNoiseStats.bins(blocks, range, channel = c))
                    measuredPerChannel[c] = cf
                    channelFits.put(labels[c], fitJson(cf))
                }
                val resultIso = firstResult?.get(CaptureResult.SENSOR_SENSITIVITY)
                val resultExposure = firstResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val profile = firstResult?.get(CaptureResult.SENSOR_NOISE_PROFILE)
                val mid = range / 2
                val declared = JSONArray()
                if (profile != null && profile.isNotEmpty()) {
                    for (c in 0 until 4) {
                        val p = profile[c % profile.size]
                        val da = p.second * range * range
                        val db = p.first * range
                        val declaredMid = da + db * mid
                        val pooledMid = fit?.let { it.a + it.b * mid }
                        val channelMid = measuredPerChannel[c]?.let { it.a + it.b * mid }
                        declared.put(JSONObject().put("channel", labels[c]).put("S", num(p.first)).put("O", num(p.second))
                            .put("a", num(da)).put("b", num(db))
                            .put("ratioMidPooled", num(pooledMid?.let { declaredMid / it }))
                            .put("ratioMidChannel", num(channelMid?.let { declaredMid / it })))
                    }
                }
                val binsJson = JSONArray()
                for (bin in bins) {
                    if (bin.count == 0) continue
                    binsJson.put(JSONObject().put("centre", num(bin.centre)).put("meanX", num(bin.meanX)).put("count", bin.count)
                        .put("medianVar", num(bin.medianVar)))
                }
                val levelMin = usedBins.minOfOrNull { it.meanX }
                val levelMax = usedBins.maxOfOrNull { it.meanX }
                isoReports.put(JSONObject().put("iso", iso).put("resultIso", resultIso ?: JSONObject.NULL)
                    .put("exposureNs", exposure).put("resultExposureNs", resultExposure ?: JSONObject.NULL)
                    .put("wantedExposureNs", wanted).put("clamped", clamped).put("frameDurationNs", frameNs)
                    .put("black", JSONArray(black.map { it.toDouble() })).put("white", white)
                    .put("blocks", blocks.size).put("saturatedBlocks", saturated)
                    .put("blocksInFit", usedBins.filter { it.meanX >= RawNoiseStats.MIN_FIT_X }.sumOf { it.count })
                    .put("gappedPairs", gappedPairs).put("maxPairDtNs", maxPairDtNs).put("captureFailures", failures.get())
                    .put("levelRange", JSONArray(listOf(num(levelMin), num(levelMax))))
                    .put("fit", fitJson(fit)).put("channelFits", channelFits)
                    .put("declaredNoiseProfile", declared).put("bins", binsJson))
                Log.i(TAG, "iso=$iso resultIso=$resultIso exposureNs=$exposure clamped=$clamped black=${black.joinToString(",")} " +
                    "white=$white a=${fit?.a?.fmt()} b=${fit?.b?.fmt()} r2=${fit?.r2?.fmt()} blocks=${blocks.size} " +
                    "binsUsed=${fit?.bins ?: 0} level=${levelMin?.fmt()}..${levelMax?.fmt()} gapped=$gappedPairs failures=${failures.get()}")
                SystemClock.sleep(300)
            }
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            SystemClock.sleep(300)
            reader.close()
            preview.release(); texture.release()
            thread.quitSafely()
        }
    }

    private fun fitJson(fit: RawNoiseStats.Fit?): Any =
        fit?.let { JSONObject().put("a", num(it.a)).put("b", num(it.b)).put("r2", num(it.r2)).put("bins", it.bins) } ?: JSONObject.NULL

    private fun num(value: Double?): Any = if (value != null && value.isFinite()) value else JSONObject.NULL

    private fun Double.fmt() = String.format(java.util.Locale.ROOT, "%.4g", this)

    /** Labels of the 2x2 positions, row-major from the active array's top-left. */
    private fun cfaLabels(cfa: Int): Array<String> = when (cfa) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> arrayOf("R", "Gr", "Gb", "B")
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> arrayOf("Gr", "R", "B", "Gb")
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> arrayOf("Gb", "B", "R", "Gr")
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> arrayOf("B", "Gb", "Gr", "R")
        else -> arrayOf("p00", "p01", "p10", "p11")
    }

    private companion object {
        const val TAG = "OCC_RAW_NOISE"
        const val JSON_NAME = "occ-raw-noise.json"
        const val LOG_PART_BYTES = 3000
        const val MAX_IMAGES = 4
        const val DISCARD_FRAMES = 3
        const val FRAME_TIMEOUT_MS = 5_000L
        const val AE_TIMEOUT_MS = 2_000L
        const val MAX_EXPOSURE_NS = 33_333_333L
    }
}

/** RAW unpacking and photon-transfer block statistics, on plain arrays. */
internal object RawNoiseStats {
    /** Same-channel samples per block side; a block covers 2·BLOCK pixels. */
    const val BLOCK = 16
    const val BINS = 48
    const val MIN_BIN_BLOCKS = 20
    /** Levels closer to black than this are biased by clipping and left out of fits. */
    const val MIN_FIT_X = 2.0

    /** RAW10: 4 pixels in 5 bytes, the high 8 bits of each then one byte with the four 2-bit remainders. */
    fun unpackRaw10Row(src: ByteArray, srcOffset: Int, width: Int, out: ShortArray, outOffset: Int) {
        var s = srcOffset
        var o = outOffset
        val groups = width / 4
        for (g in 0 until groups) {
            val low = src[s + 4].toInt() and 0xFF
            out[o] = (((src[s].toInt() and 0xFF) shl 2) or (low and 3)).toShort()
            out[o + 1] = (((src[s + 1].toInt() and 0xFF) shl 2) or ((low shr 2) and 3)).toShort()
            out[o + 2] = (((src[s + 2].toInt() and 0xFF) shl 2) or ((low shr 4) and 3)).toShort()
            out[o + 3] = (((src[s + 3].toInt() and 0xFF) shl 2) or ((low shr 6) and 3)).toShort()
            s += 5
            o += 4
        }
    }

    fun unpackRaw10(src: ByteArray, rowStride: Int, width: Int, height: Int, out: ShortArray) {
        for (y in 0 until height) unpackRaw10Row(src, y * rowStride, width, out, y * width)
    }

    /** RAW_SENSOR: one little-endian 16-bit sample per pixel. */
    fun unpackRaw16Row(src: ByteArray, srcOffset: Int, width: Int, out: ShortArray, outOffset: Int) {
        for (x in 0 until width) {
            val s = srcOffset + 2 * x
            out[outOffset + x] = ((src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8)).toShort()
        }
    }

    /** Growable per-block records: CFA position (row-major from the region origin), level above black, temporal variance. */
    class Blocks {
        var size = 0
            private set
        var channel = ByteArray(1 shl 16)
            private set
        var x = FloatArray(1 shl 16)
            private set
        var variance = FloatArray(1 shl 16)
            private set

        fun add(c: Int, level: Float, v: Float) {
            if (size == x.size) {
                channel = channel.copyOf(size * 2); x = x.copyOf(size * 2); variance = variance.copyOf(size * 2)
            }
            channel[size] = c.toByte(); x[size] = level; variance[size] = v
            size++
        }
    }

    /**
     * One pass over a frame pair: for every 2x2 position, non-overlapping [BLOCK]² same-channel blocks inside
     * [region]; mean = mean of (A+B)/2 minus that position's black, variance = var(A−B)/2. [black] is indexed by
     * image parity, (row % 2) · 2 + column % 2. Blocks whose A or B maximum reaches [saturation] are skipped;
     * returns how many were.
     */
    fun accumulatePair(a: ShortArray, b: ShortArray, width: Int, region: Rect, black: FloatArray, saturation: Int, out: Blocks): Int =
        accumulatePair(a, b, width, region.left, region.top, region.right, region.bottom, black, saturation, out)

    fun accumulatePair(
        a: ShortArray, b: ShortArray, width: Int, left: Int, top: Int, right: Int, bottom: Int,
        black: FloatArray, saturation: Int, out: Blocks,
    ): Int {
        val span = 2 * BLOCK
        val n = BLOCK * BLOCK
        val blocksX = (right - left) / span
        val blocksY = (bottom - top) / span
        var skipped = 0
        for (by in 0 until blocksY) {
            val y0 = top + by * span
            for (bx in 0 until blocksX) {
                val x0 = left + bx * span
                for (dy in 0..1) for (dx in 0..1) {
                    var sumAB = 0L
                    var sumD = 0L
                    var sumD2 = 0L
                    var max = 0
                    for (i in 0 until BLOCK) {
                        var idx = (y0 + dy + 2 * i) * width + x0 + dx
                        for (j in 0 until BLOCK) {
                            val va = a[idx].toInt() and 0xFFFF
                            val vb = b[idx].toInt() and 0xFFFF
                            sumAB += va + vb
                            val d = va - vb
                            sumD += d
                            sumD2 += d.toLong() * d
                            if (va > max) max = va
                            if (vb > max) max = vb
                            idx += 2
                        }
                    }
                    if (max >= saturation) { skipped++; continue }
                    val parity = ((y0 + dy) and 1) * 2 + ((x0 + dx) and 1)
                    val mean = sumAB.toDouble() / (2 * n)
                    val varD = (sumD2 - sumD.toDouble() * sumD / n) / (n - 1)
                    out.add(dy * 2 + dx, (mean - black[parity]).toFloat(), (varD / 2).toFloat())
                }
            }
        }
        return skipped
    }

    class Bin(val index: Int, val centre: Double, val meanX: Double, val count: Int, val medianVar: Double)

    /** [BINS] bins over 0..[range] above black (levels outside fall in the end bins); median variance per bin. */
    fun bins(blocks: Blocks, range: Double, channel: Int? = null, bins: Int = BINS): List<Bin> {
        val width = range / bins
        val index = IntArray(blocks.size)
        val counts = IntArray(bins)
        val sumX = DoubleArray(bins)
        for (i in 0 until blocks.size) {
            if (channel != null && blocks.channel[i].toInt() != channel) { index[i] = -1; continue }
            val k = (blocks.x[i] / width).toInt().coerceIn(0, bins - 1)
            index[i] = k
            counts[k]++
            sumX[k] += blocks.x[i]
        }
        val values = Array(bins) { FloatArray(counts[it]) }
        val fill = IntArray(bins)
        for (i in 0 until blocks.size) {
            val k = index[i]
            if (k >= 0) values[k][fill[k]++] = blocks.variance[i]
        }
        return List(bins) { k ->
            val v = values[k].apply { sort() }
            val median = when {
                v.isEmpty() -> Double.NaN
                v.size % 2 == 1 -> v[v.size / 2].toDouble()
                else -> (v[v.size / 2 - 1].toDouble() + v[v.size / 2]) / 2
            }
            Bin(k, (k + 0.5) * width, if (counts[k] > 0) sumX[k] / counts[k] else Double.NaN, counts[k], median)
        }
    }

    class Fit(val a: Double, val b: Double, val r2: Double, val bins: Int)

    /** Weighted least squares of medianVar = a + b·meanX over bins with enough blocks, weight = block count. */
    fun fit(bins: List<Bin>, minBlocks: Int = MIN_BIN_BLOCKS, minX: Double = MIN_FIT_X): Fit? {
        val used = bins.filter { it.count >= minBlocks && it.meanX >= minX && it.medianVar.isFinite() }
        if (used.size < 2) return null
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        for (bin in used) { sw += bin.count; sx += bin.count * bin.meanX; sy += bin.count * bin.medianVar }
        val mx = sx / sw
        val my = sy / sw
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (bin in used) {
            val dx = bin.meanX - mx
            val dy = bin.medianVar - my
            sxx += bin.count * dx * dx; sxy += bin.count * dx * dy; syy += bin.count * dy * dy
        }
        if (sxx <= 0.0) return null
        val slope = sxy / sxx
        val intercept = my - slope * mx
        var ssRes = 0.0
        for (bin in used) { val r = bin.medianVar - (intercept + slope * bin.meanX); ssRes += bin.count * r * r }
        val r2 = if (syy > 0.0) 1 - ssRes / syy else Double.NaN
        return Fit(intercept, slope, r2, used.size)
    }
}
