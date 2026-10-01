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
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical public Camera2 RAW_SENSOR/DNG gate for OCC-PLAN-040. */
@RunWith(AndroidJUnit4::class)
class RawStillDeviceTest {
    // The adopted shell identity is process-wide; leaving it set breaks later cases that need the app's own uid.
    @org.junit.After fun dropAdoptedShellIdentity() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()

    @Test
    fun writesRawStillDngEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val result = runCatching { capture(context) }.getOrElse { error ->
            RawResult("FAILED", "raw-capture-exception", "RAW still fixture failed: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(180)}")
        }
        val output = result.toJson(context)
        val directory = requireNotNull(context.getExternalFilesDir(null)) { "external files directory unavailable" }
        File(directory, JSON_NAME).writeText(output.toString(2) + "\n", Charsets.UTF_8)
        val encoded = Base64.encodeToString((output.toString(2) + "\n").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        encoded.chunked(LOG_PART_BYTES).forEachIndexed { index, part ->
            Log.i(TAG, "json-part=${index + 1}/${(encoded.length + LOG_PART_BYTES - 1) / LOG_PART_BYTES}:$part")
        }
        Log.i(TAG, "status=${result.status} reasonCode=${result.reasonCode}")
        assertTrue("RAW still evidence was not written", File(directory, JSON_NAME).isFile)
        assertTrue("RAW still fixture crashed", result.status != "FAILED")
    }

    private fun capture(context: Context): RawResult {
        val manager = context.getSystemService(CameraManager::class.java)
        val candidate = manager.cameraIdList.sorted().asSequence().mapNotNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            val packedSizes = mapOf(
                "RAW10" to characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW10).orEmpty().map { "${it.width}x${it.height}" },
                "RAW12" to characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW12).orEmpty().map { "${it.width}x${it.height}" },
            )
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.filter { it.width > 0 && it.height > 0 }
                ?.sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenByDescending { it.width })
                .orEmpty()
            if (sizes.isEmpty()) null else RawCandidate(id, characteristics, sizes.first(), packedSizes)
        }.firstOrNull() ?: return unsupported("raw-sensor-unsupported", "No public Camera2 RAW_SENSOR output is advertised.")

        val directory = requireNotNull(context.getExternalFilesDir(null)) { "external files directory unavailable" }
        val dngFile = File(directory, DNG_NAME).also { it.delete() }
        val thread = HandlerThread("occ-raw-still").apply { start() }
        val handler = Handler(thread.looper)
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val reader = ImageReader.newInstance(candidate.size.width, candidate.size.height, ImageFormat.RAW_SENSOR, 2)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var image: Image? = null
        var captureResult: TotalCaptureResult? = null
        var imageError: String? = null
        val cameraClosedLatch = CountDownLatch(1)
        val imageLatch = CountDownLatch(1)
        reader.setOnImageAvailableListener({ source ->
            runCatching { source.acquireLatestImage() }.onSuccess { received ->
                if (received != null && image == null) {
                    image = received
                    imageLatch.countDown()
                } else {
                    received?.close()
                }
            }.onFailure { error ->
                imageError = "image-reader-${error.javaClass.simpleName}"
                imageLatch.countDown()
            }
        }, handler)
        return try {
            val openLatch = CountDownLatch(1)
            var openError: String? = null
            manager.openCamera(candidate.cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) { camera = opened; openLatch.countDown() }
                override fun onDisconnected(closed: CameraDevice) { openError = "camera-disconnected"; closed.close(); openLatch.countDown() }
                override fun onError(closed: CameraDevice, error: Int) { openError = "camera-open-error-$error"; closed.close(); openLatch.countDown() }
                override fun onClosed(closed: CameraDevice) { cameraClosedLatch.countDown() }
            })
            if (!openLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return unsupported("camera-open-timeout", "Camera did not open within the bounded timeout.")
            openError?.let { return unsupported(it, "RAW camera could not be opened.") }
            val opened = requireNotNull(camera)
            val sessionLatch = CountDownLatch(1)
            var sessionError: String? = null
            opened.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(created: CameraCaptureSession) { session = created; sessionLatch.countDown() }
                override fun onConfigureFailed(failed: CameraCaptureSession) { sessionError = "session-configure-failed"; sessionLatch.countDown() }
            }, handler)
            if (!sessionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return unsupported("session-configure-timeout", "RAW session did not configure within the bounded timeout.")
            sessionError?.let { return unsupported(it, "CameraService rejected the RAW still session.") }
            val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
            }.build()
            val captureLatch = CountDownLatch(1)
            requireNotNull(session).capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                    captureResult = r
                    captureLatch.countDown()
                }

                override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    imageError = "capture-failed-${failure.reason}"
                    captureLatch.countDown()
                }
            }, handler)
            if (!captureLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return unsupported("capture-timeout", "RAW still capture did not complete within the bounded timeout.")
            if (!imageLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return unsupported("image-timeout", "RAW image was not delivered within the bounded timeout.")
            imageError?.let { return unsupported(it, "RAW still capture did not produce a valid image.") }
            val received = requireNotNull(image)
            val completed = requireNotNull(captureResult)
            val writeStart = System.nanoTime()
            FileOutputStream(dngFile).use { stream -> DngCreator(candidate.characteristics, completed).writeImage(stream, received) }
            val writeMillis = (System.nanoTime() - writeStart) / 1_000_000L
            val plane = received.planes.firstOrNull()
            RawResult(
                status = "PASS",
                reasonCode = "raw-still-dng-pass",
                safeMessage = "Public RAW_SENSOR still captured and written as DNG; RAW video remains separately gated.",
                cameraId = candidate.cameraId,
                size = "${candidate.size.width}x${candidate.size.height}",
                dng = DNG_NAME,
                dngBytes = dngFile.length(),
                rowStride = plane?.rowStride,
                pixelStride = plane?.pixelStride,
                writeMillis = writeMillis,
                packedSizes = candidate.packedSizes,
            )
        } finally {
            runCatching { image?.close() }
            runCatching { session?.close() }
            runCatching {
                camera?.close()
                cameraClosedLatch.await(2, TimeUnit.SECONDS)
            }
            runCatching { reader.close() }
            executor.shutdownNow()
            thread.quitSafely()
        }
    }

    private fun unsupported(code: String, message: String) = RawResult("UNSUPPORTED", code, message)

    private fun RawResult.toJson(context: Context) = JSONObject()
        .put("schema", "opencinecam-raw-still-v1")
        .put("protocolVersion", 1)
        .put("collectedAtEpochMs", System.currentTimeMillis())
        .put("status", status)
        .put("reasonCode", reasonCode)
        .put("safeMessage", safeMessage)
        .put("device", JSONObject()
            .put("fingerprint", Build.FINGERPRINT)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName))
        .put("cameraId", cameraId ?: JSONObject.NULL)
        .put("format", "RAW_SENSOR")
        .put("size", size ?: JSONObject.NULL)
        .put("dng", dng ?: JSONObject.NULL)
        .put("dngBytes", dngBytes)
        .put("rowStride", rowStride ?: JSONObject.NULL)
        .put("pixelStride", pixelStride ?: JSONObject.NULL)
        .put("writeMillis", writeMillis)
        .put("packedSizes", JSONObject(packedSizes))
        .put("rawVideoPromotion", "not evaluated")

    private data class RawCandidate(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val size: Size,
        val packedSizes: Map<String, List<String>>,
    )
    private data class RawResult(
        val status: String,
        val reasonCode: String,
        val safeMessage: String,
        val cameraId: String? = null,
        val size: String? = null,
        val dng: String? = null,
        val dngBytes: Long = 0,
        val rowStride: Int? = null,
        val pixelStride: Int? = null,
        val writeMillis: Long = 0,
        val packedSizes: Map<String, List<String>> = emptyMap(),
    )

    private companion object {
        const val TAG = "OCC_RAW_STILL"
        const val JSON_NAME = "occ-plan-040-raw-still.json"
        const val DNG_NAME = "occ-plan-040-raw-still.dng"
        const val LOG_PART_BYTES = 3000
        const val TIMEOUT_SECONDS = 15L
    }
}
