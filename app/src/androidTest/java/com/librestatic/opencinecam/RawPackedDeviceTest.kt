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
import com.librestatic.opencinecam.camera.RawFormat
import com.librestatic.opencinecam.camera.RawFrame
import com.librestatic.opencinecam.camera.RawFrameMetadata
import com.librestatic.opencinecam.camera.RawUnpacker
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One bounded RAW10 frame validates the public packed layout without claiming RAW video. */
@RunWith(AndroidJUnit4::class)
class RawPackedDeviceTest {
    @Test
    fun parsesOnePublicRaw10Frame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val result = runCatching { capture(context) }.getOrElse { error ->
            PackedResult("FAILED", "raw10-capture-exception", "RAW10 fixture failed: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(180)}")
        }
        val output = result.toJson(context)
        val directory = requireNotNull(context.getExternalFilesDir(null)) { "external files directory unavailable" }
        File(directory, JSON_NAME).writeText(output.toString(2) + "\n", Charsets.UTF_8)
        val encoded = Base64.encodeToString((output.toString(2) + "\n").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        encoded.chunked(LOG_PART_BYTES).forEachIndexed { index, part ->
            Log.i(TAG, "json-part=${index + 1}/${(encoded.length + LOG_PART_BYTES - 1) / LOG_PART_BYTES}:$part")
        }
        Log.i(TAG, "status=${result.status} reasonCode=${result.reasonCode}")
        assertTrue("RAW10 evidence was not written", File(directory, JSON_NAME).isFile)
        assertTrue("RAW10 fixture crashed", result.status != "FAILED")
    }

    private fun capture(context: Context): PackedResult {
        val manager = context.getSystemService(CameraManager::class.java)
        val candidate = manager.cameraIdList.sorted().asSequence().mapNotNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW10)
                ?.filter { it.width > 0 && it.height > 0 }
                ?.maxByOrNull { it.width.toLong() * it.height }
            size?.let { PackedCandidate(id, characteristics, it) }
        }.firstOrNull() ?: return PackedResult("UNSUPPORTED", "raw10-unsupported", "No public Camera2 RAW10 stream is advertised.")
        val thread = HandlerThread("occ-raw10").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executors.newSingleThreadExecutor()
        val reader = ImageReader.newInstance(candidate.size.width, candidate.size.height, ImageFormat.RAW10, 2)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var image: Image? = null
        var failure: String? = null
        val imageLatch = CountDownLatch(1)
        val cameraClosed = CountDownLatch(1)
        reader.setOnImageAvailableListener({ source ->
            runCatching { source.acquireLatestImage() }.onSuccess { received ->
                if (received != null && image == null) { image = received; imageLatch.countDown() } else received?.close()
            }.onFailure { error -> failure = "image-reader-${error.javaClass.simpleName}"; imageLatch.countDown() }
        }, handler)
        return try {
            val openLatch = CountDownLatch(1)
            var openError: String? = null
            manager.openCamera(candidate.cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) { camera = opened; openLatch.countDown() }
                override fun onDisconnected(closed: CameraDevice) { openError = "camera-disconnected"; closed.close(); openLatch.countDown() }
                override fun onError(closed: CameraDevice, error: Int) { openError = "camera-open-error-$error"; closed.close(); openLatch.countDown() }
                override fun onClosed(closed: CameraDevice) { cameraClosed.countDown() }
            })
            if (!openLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return unsupported("camera-open-timeout", "Camera did not open within the bounded timeout.")
            openError?.let { return unsupported(it, "RAW10 camera could not be opened.") }
            val opened = requireNotNull(camera)
            val sessionLatch = CountDownLatch(1)
            opened.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(created: CameraCaptureSession) { session = created; sessionLatch.countDown() }
                override fun onConfigureFailed(failed: CameraCaptureSession) { failure = "session-configure-failed"; sessionLatch.countDown() }
            }, handler)
            if (!sessionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return unsupported("session-timeout", "RAW10 session did not configure within the bounded timeout.")
            failure?.let { return unsupported(it, "CameraService rejected the RAW10 session.") }
            val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
            }.build()
            val captureLatch = CountDownLatch(1)
            requireNotNull(session).capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { captureLatch.countDown() }
                override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, captureFailure: CaptureFailure) { failure = "capture-failed-${captureFailure.reason}"; captureLatch.countDown() }
            }, handler)
            if (!captureLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return unsupported("capture-timeout", "RAW10 capture did not complete within the bounded timeout.")
            if (!imageLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return unsupported("image-timeout", "RAW10 image was not delivered within the bounded timeout.")
            failure?.let { return unsupported(it, "RAW10 capture did not produce a valid image.") }
            val received = requireNotNull(image)
            val plane = received.planes.firstOrNull() ?: return unsupported("plane-missing", "RAW10 image plane is missing.")
            val bytes = ByteArray(plane.buffer.remaining())
            plane.buffer.get(bytes)
            val metadata = RawFrameMetadata(
                width = received.width,
                height = received.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                cfaPattern = "PUBLIC_CAMERA2",
                blackLevel = 0,
                whiteLevel = 1023,
                timestampNs = received.timestamp,
                format = RawFormat.RAW10,
            )
            val pixels = RawUnpacker.unpack(RawFrame(1, metadata, bytes))
            PackedResult(
                status = "PASS",
                reasonCode = "raw10-parse-pass",
                safeMessage = "One public RAW10 frame parsed with preserved stride; RAW video remains separately gated.",
                cameraId = candidate.cameraId,
                size = "${candidate.size.width}x${candidate.size.height}",
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                pixelCount = pixels.size,
                minSample = pixels.minOrNull(),
                maxSample = pixels.maxOrNull(),
                distinctSamples = pixels.toSet().size,
                droppedFrames = 0,
            )
        } finally {
            runCatching { image?.close() }
            runCatching { session?.close() }
            runCatching { camera?.close(); cameraClosed.await(2, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            executor.shutdownNow()
            thread.quitSafely()
        }
    }

    private fun unsupported(code: String, message: String) = PackedResult("UNSUPPORTED", code, message)

    private fun PackedResult.toJson(context: Context) = JSONObject()
        .put("schema", "opencinecam-raw10-frame-v1")
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
        .put("pixelCount", pixelCount)
        .put("minSample", minSample ?: JSONObject.NULL)
        .put("maxSample", maxSample ?: JSONObject.NULL)
        .put("distinctSamples", distinctSamples)
        .put("droppedFrames", droppedFrames)
        .put("rawVideoPromotion", "not evaluated")

    private data class PackedCandidate(val cameraId: String, val characteristics: CameraCharacteristics, val size: Size)
    private data class PackedResult(
        val status: String,
        val reasonCode: String,
        val safeMessage: String,
        val cameraId: String? = null,
        val size: String? = null,
        val rowStride: Int? = null,
        val pixelStride: Int? = null,
        val pixelCount: Int = 0,
        val minSample: Int? = null,
        val maxSample: Int? = null,
        val distinctSamples: Int = 0,
        val droppedFrames: Int = 0,
    )

    private companion object {
        const val TAG = "OCC_RAW10"
        const val JSON_NAME = "occ-plan-041-raw10-frame.json"
        const val LOG_PART_BYTES = 3000
        const val TIMEOUT_SECONDS = 15L
    }
}
