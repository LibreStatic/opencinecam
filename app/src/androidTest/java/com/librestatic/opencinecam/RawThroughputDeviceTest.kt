/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.RawBenchmarkStatus
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.camera.RawThroughputSample
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded 60-second RAW10 graph/storage benchmark; never promotes RAW video by itself. */
@RunWith(AndroidJUnit4::class)
class RawThroughputDeviceTest {
    // The adopted shell identity is process-wide; leaving it set breaks later cases that need the app's own uid.
    @org.junit.After fun dropAdoptedShellIdentity() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()

    @Test
    fun qualifiesSixtySecondRaw10Window() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val result = runCatching { benchmark(context) }.getOrElse { error ->
            ThroughputResult(
                status = "UNKNOWN",
                reasonCode = "raw-throughput-exception",
                safeMessage = "RAW throughput benchmark could not run: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(180)}",
            )
        }
        val output = result.toJson()
        val directory = requireNotNull(context.getExternalFilesDir(null)) { "external files directory unavailable" }
        File(directory, JSON_NAME).writeText(output.toString(2) + "\n", Charsets.UTF_8)
        val encoded = Base64.encodeToString((output.toString(2) + "\n").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        encoded.chunked(LOG_PART_BYTES).forEachIndexed { index, part ->
            Log.i(TAG, "json-part=${index + 1}/${(encoded.length + LOG_PART_BYTES - 1) / LOG_PART_BYTES}:$part")
        }
        Log.i(TAG, "status=${result.status} reasonCode=${result.reasonCode}")
        assertTrue("RAW throughput evidence was not written", File(directory, JSON_NAME).isFile)
        // Unsupported/failed physical branches are valid evidence; an exception is not.
        assertTrue("RAW throughput fixture crashed", result.reasonCode != "raw-throughput-exception")
    }

    private fun benchmark(context: Context): ThroughputResult {
        val manager = context.getSystemService(CameraManager::class.java)
        val candidate = manager.cameraIdList.sorted().asSequence().mapNotNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW10)
                ?.filter { it.width > 0 && it.height > 0 }
                ?.maxByOrNull { it.width.toLong() * it.height }
            size?.let { ThroughputCandidate(id, characteristics, it) }
        }.firstOrNull() ?: return ThroughputResult(
            status = "UNKNOWN",
            reasonCode = "raw-throughput-unsupported",
            safeMessage = "No public Camera2 RAW10 stream is advertised; RAW-video mode remains disabled.",
        )

        val thread = HandlerThread("occ-raw-throughput").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(candidate.size.width, candidate.size.height, ImageFormat.RAW10, 4)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val storage = File(requireNotNull(context.getExternalFilesDir(null)), STORAGE_NAME)
        val circularBytes = 64L * 1024L * 1024L
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var file: RandomAccessFile? = null
        val openLatch = CountDownLatch(1)
        val sessionLatch = CountDownLatch(1)
        val cameraClosed = CountDownLatch(1)
        val completedFrames = AtomicInteger(0)
        val deliveredFrames = AtomicInteger(0)
        val failedFrames = AtomicInteger(0)
        var openError: String? = null
        var configureError = false
        var writeError: String? = null
        var payloadBytesPerFrame: Int? = null
        var sourceRowStride: Int? = null
        var sourcePixelStride: Int? = null
        var streamBytesPerSecond: Long? = null
        val windowBytes = LongArray(WINDOWS)
        val windowCaptures = IntArray(WINDOWS)
        val windowDelivered = IntArray(WINDOWS)
        val windowFailed = IntArray(WINDOWS)
        var writeOffset = 0L
        var benchmarkStart = 0L
        val fpsRange = chooseFpsRange(candidate.characteristics)

        reader.setOnImageAvailableListener({ source ->
            runCatching { source.acquireLatestImage() }.onSuccess { image ->
                if (image == null) return@onSuccess
                image.use { received ->
                    val now = SystemClock.elapsedRealtime()
                    val window = ((now - benchmarkStart) / 1_000L).toInt().coerceIn(0, WINDOWS - 1)
                    val plane = received.planes.firstOrNull()
                    if (plane == null) {
                        windowFailed[window]++
                        failedFrames.incrementAndGet()
                        return@use
                    }
                    payloadBytesPerFrame = payloadBytesPerFrame ?: plane.buffer.remaining()
                    sourceRowStride = sourceRowStride ?: plane.rowStride
                    sourcePixelStride = sourcePixelStride ?: plane.pixelStride
                    val bytes = ByteArray(plane.buffer.remaining())
                    plane.buffer.get(bytes)
                    try {
                        val sink = requireNotNull(file)
                        var remaining = bytes.size
                        var sourceOffset = 0
                        while (remaining > 0) {
                            val chunk = minOf(remaining.toLong(), circularBytes - writeOffset).toInt()
                            sink.seek(writeOffset)
                            sink.write(bytes, sourceOffset, chunk)
                            writeOffset = (writeOffset + chunk) % circularBytes
                            sourceOffset += chunk
                            remaining -= chunk
                        }
                        windowBytes[window] += bytes.size
                        windowDelivered[window]++
                        deliveredFrames.incrementAndGet()
                    } catch (error: Exception) {
                        writeError = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}"
                        windowFailed[window]++
                        failedFrames.incrementAndGet()
                    }
                }
            }.onFailure {
                val window = ((SystemClock.elapsedRealtime() - benchmarkStart) / 1_000L).toInt().coerceIn(0, WINDOWS - 1)
                windowFailed[window]++
                failedFrames.incrementAndGet()
            }
        }, handler)

        return try {
            manager.openCamera(candidate.cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) { camera = opened; openLatch.countDown() }
                override fun onDisconnected(closed: CameraDevice) { openError = "camera-disconnected"; closed.close(); openLatch.countDown() }
                override fun onError(closed: CameraDevice, error: Int) { openError = "camera-open-error-$error"; closed.close(); openLatch.countDown() }
                override fun onClosed(closed: CameraDevice) { cameraClosed.countDown() }
            })
            if (!openLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return unsupported("camera-open-timeout", "RAW throughput camera did not open within the bounded timeout.")
            }
            openError?.let { return unsupported(it, "RAW throughput camera could not be opened.") }
            val opened = requireNotNull(camera)
            opened.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(created: CameraCaptureSession) { session = created; sessionLatch.countDown() }
                override fun onConfigureFailed(failed: CameraCaptureSession) { configureError = true; sessionLatch.countDown() }
            }, handler)
            if (!sessionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return unsupported("session-timeout", "RAW throughput session did not configure within the bounded timeout.")
            }
            if (configureError) return unsupported("session-configure-failed", "CameraService rejected the RAW10 throughput session.")

            file = RandomAccessFile(storage, "rw").apply { setLength(circularBytes) }
            val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }.build()
            val captureError = AtomicInteger(0)
            benchmarkStart = SystemClock.elapsedRealtime()
            requireNotNull(session).setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val index = ((SystemClock.elapsedRealtime() - benchmarkStart) / 1_000L).toInt().coerceIn(0, WINDOWS - 1)
                    windowCaptures[index]++
                    completedFrames.incrementAndGet()
                }

                override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    val index = ((SystemClock.elapsedRealtime() - benchmarkStart) / 1_000L).toInt().coerceIn(0, WINDOWS - 1)
                    windowFailed[index]++
                    failedFrames.incrementAndGet()
                    captureError.incrementAndGet()
                }
            }, handler)
            SystemClock.sleep(WINDOWS * 1_000L)
            requireNotNull(session).stopRepeating()
            handler.post { }
            SystemClock.sleep(1_000L)
            file?.fd?.sync()
            streamBytesPerSecond = payloadBytesPerFrame?.toLong()?.let { bytes ->
                val fps = fpsRange?.upper?.toLong() ?: DEFAULT_FPS
                bytes * fps
            }
            val samples = (0 until WINDOWS).map { index ->
                val captures = windowCaptures[index]
                val delivered = windowDelivered[index]
                val dropped = (captures - delivered).coerceAtLeast(0) + windowFailed[index]
                RawThroughputSample(1_000L, windowBytes[index], dropped)
            }
            val qualification = RawThroughputBenchmark.qualify(WINDOWS.toLong(), streamBytesPerSecond, samples)
            ThroughputResult(
                status = qualification.status.name,
                reasonCode = if (writeError != null) "raw-throughput-write-error" else qualification.reasonCode,
                safeMessage = "60-second RAW10 graph/storage benchmark completed; RAW-video promotion remains separately gated.",
                cameraId = candidate.cameraId,
                size = "${candidate.size.width}x${candidate.size.height}",
                rowStride = sourceRowStride,
                pixelStride = sourcePixelStride,
                requestedFps = fpsRange?.upper ?: DEFAULT_FPS.toInt(),
                completedFrames = completedFrames.get(),
                deliveredFrames = deliveredFrames.get(),
                failedFrames = failedFrames.get(),
                storageBytes = windowBytes.sum(),
                streamBytesPerSecond = qualification.streamBytesPerSecond,
                p01BytesPerSecond = qualification.p01BytesPerSecond,
                requiredBytesPerSecond = qualification.requiredBytesPerSecond,
                droppedFrames = qualification.droppedFrames,
                samples = samples,
            )
        } finally {
            runCatching { session?.close() }
            runCatching { camera?.close(); cameraClosed.await(2, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            runCatching { file?.close() }
            runCatching { storage.delete() }
            executor.shutdownNow()
            thread.quitSafely()
        }
    }

    private fun chooseFpsRange(characteristics: CameraCharacteristics): Range<Int>? =
        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.lower > 0 && it.upper in 1..30 }
            ?.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })

    private fun unsupported(code: String, message: String) = ThroughputResult("UNKNOWN", code, message)

    private fun ThroughputResult.toJson() = JSONObject()
        .put("schema", "opencinecam-raw-throughput-v1")
        .put("protocolVersion", 1)
        .put("collectedAtEpochMs", System.currentTimeMillis())
        .put("status", status)
        .put("reasonCode", reasonCode)
        .put("safeMessage", safeMessage)
        .put("device", JSONObject().put("fingerprint", Build.FINGERPRINT).put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL))
        .put("cameraId", cameraId ?: JSONObject.NULL)
        .put("format", "RAW10")
        .put("size", size ?: JSONObject.NULL)
        .put("rowStride", rowStride ?: JSONObject.NULL)
        .put("pixelStride", pixelStride ?: JSONObject.NULL)
        .put("requestedFps", requestedFps)
        .put("windowSeconds", WINDOWS)
        .put("completedFrames", completedFrames)
        .put("deliveredFrames", deliveredFrames)
        .put("failedFrames", failedFrames)
        .put("storageBytes", storageBytes)
        .put("streamBytesPerSecond", streamBytesPerSecond ?: JSONObject.NULL)
        .put("p01BytesPerSecond", p01BytesPerSecond ?: JSONObject.NULL)
        .put("requiredBytesPerSecond", requiredBytesPerSecond ?: JSONObject.NULL)
        .put("droppedFrames", droppedFrames)
        .put("rawVideoPromotion", "not evaluated")
        .put("samples", JSONArray().apply { samples.forEach { sample ->
            put(JSONObject().put("durationMs", sample.durationMs).put("bytesWritten", sample.bytesWritten).put("droppedFrames", sample.droppedFrames))
        } })

    private data class ThroughputCandidate(val cameraId: String, val characteristics: CameraCharacteristics, val size: Size)

    private data class ThroughputResult(
        val status: String,
        val reasonCode: String,
        val safeMessage: String,
        val cameraId: String? = null,
        val size: String? = null,
        val rowStride: Int? = null,
        val pixelStride: Int? = null,
        val requestedFps: Int = 0,
        val completedFrames: Int = 0,
        val deliveredFrames: Int = 0,
        val failedFrames: Int = 0,
        val storageBytes: Long = 0,
        val streamBytesPerSecond: Long? = null,
        val p01BytesPerSecond: Long? = null,
        val requiredBytesPerSecond: Long? = null,
        val droppedFrames: Int = 0,
        val samples: List<RawThroughputSample> = emptyList(),
    )

    private companion object {
        const val TAG = "OCC_RAW_THROUGHPUT"
        const val JSON_NAME = "occ-plan-042-raw-throughput.json"
        const val STORAGE_NAME = "occ-plan-042-raw-throughput-ring.bin"
        const val LOG_PART_BYTES = 3000
        const val TIMEOUT_SECONDS = 15L
        const val WINDOWS = 60
        const val DEFAULT_FPS = 30L
    }
}
