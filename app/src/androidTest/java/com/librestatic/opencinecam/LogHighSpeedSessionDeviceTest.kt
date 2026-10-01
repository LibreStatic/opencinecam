/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.util.Base64
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Folded-hardware probe for an HLG10 SurfaceTexture in Motorola's constrained HFR route. */
@RunWith(AndroidJUnit4::class)
class LogHighSpeedSessionDeviceTest {
    // The adopted shell identity is process-wide; leaving it set breaks later cases that need the app's own uid.
    @org.junit.After fun dropAdoptedShellIdentity() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()

    @Test
    fun verifiesHlg10HighSpeedSessions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val context = instrumentation.targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val results = JSONArray()
        CANDIDATES.forEach { candidate -> results.put(probe(manager, candidate)) }
        val output = JSONObject()
            .put("schema", "opencinecam-log-high-speed-session-v1")
            .put("cameraId", CAMERA_ID)
            .put("results", results)
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val file = File(directory, OUTPUT_NAME)
        val text = output.toString(2) + "\n"
        file.writeText(text, Charsets.UTF_8)
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            .chunked(LOG_PART_BYTES)
            .forEachIndexed { index, part -> Log.i(TAG, "json-part=${index + 1}:$part") }
        assertTrue(file.isFile && file.length() > 0)
    }

    private fun probe(manager: CameraManager, candidate: Candidate): JSONObject {
        val size = candidate.size
        val fps = candidate.fps
        val executor = Executors.newSingleThreadExecutor()
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(size.width, size.height) }
        val surface = Surface(texture)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var status = "FAILED"
        var reason = "unknown"
        var supportQuery: Boolean? = null
        var frames = 0L
        var firstTimestamp: Long? = null
        var lastTimestamp: Long? = null
        val cameraClosed = CountDownLatch(1)
        return try {
            val opened = CountDownLatch(1)
            manager.openCamera(CAMERA_ID, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    opened.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    reason = "camera-disconnected"
                    camera.close()
                    opened.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    reason = "camera-error-$error"
                    camera.close()
                    opened.countDown()
                }

                override fun onClosed(camera: CameraDevice) {
                    cameraClosed.countDown()
                }
            })
            if (!opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) || device == null) {
                return result(candidate, null, status, reason, supportQuery, frames, firstTimestamp, lastTimestamp)
            }
            val output = OutputConfiguration(surface).apply {
                setDynamicRangeProfile(candidate.dynamicRangeProfile)
            }
            val colorSpace = manager.getCameraCharacteristics(CAMERA_ID)
                .get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
                ?.getSupportedColorSpacesForDynamicRange(ImageFormat.PRIVATE, candidate.dynamicRangeProfile)
                ?.let { spaces -> candidate.preferredColorSpace.takeIf { it in spaces } ?: spaces.firstOrNull() }
            val configured = CountDownLatch(1)
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_HIGH_SPEED,
                listOf(output),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(created: CameraCaptureSession) {
                        session = created
                        configured.countDown()
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        reason = "session-configure-failed"
                        failed.close()
                        configured.countDown()
                    }
                },
            ).apply {
                colorSpace?.let(::setColorSpace)
                sessionParameters = requireNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                    applyMotorolaSessionKeys(manager, this)
                }.build()
            }
            supportQuery = runCatching { requireNotNull(device).isSessionConfigurationSupported(configuration) }.getOrNull()
            requireNotNull(device).createCaptureSession(configuration)
            if (!configured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) || session == null) {
                return result(candidate, colorSpace, status, reason, supportQuery, frames, firstTimestamp, lastTimestamp)
            }
            val highSpeed = session as? CameraConstrainedHighSpeedCaptureSession
                ?: return result(candidate, colorSpace, status, "wrong-session-type", supportQuery, frames, firstTimestamp, lastTimestamp)
            val request = requireNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                applyMotorolaSessionKeys(manager, this)
            }.build()
            val firstFrame = CountDownLatch(1)
            highSpeed.setRepeatingBurstRequests(
                highSpeed.createHighSpeedRequestList(request),
                executor,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        captureSession: CameraCaptureSession,
                        captureRequest: CaptureRequest,
                        captureResult: TotalCaptureResult,
                    ) {
                        val timestamp = captureResult.get(CaptureResult.SENSOR_TIMESTAMP)
                        if (timestamp != null) {
                            firstTimestamp = firstTimestamp ?: timestamp
                            lastTimestamp = timestamp
                            frames++
                            firstFrame.countDown()
                        }
                    }
                },
            )
            if (!firstFrame.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                reason = "no-capture-results"
            } else {
                Thread.sleep(STREAM_MILLIS)
                status = "PASS"
                reason = "high-speed-streamed"
            }
            runCatching { highSpeed.stopRepeating() }
            result(candidate, colorSpace, status, reason, supportQuery, frames, firstTimestamp, lastTimestamp)
        } catch (error: Throwable) {
            result(
                candidate,
                null,
                status,
                "${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}",
                supportQuery,
                frames,
                firstTimestamp,
                lastTimestamp,
            )
        } finally {
            runCatching { session?.close() }
            runCatching {
                device?.close()
                if (device != null) cameraClosed.await(2, TimeUnit.SECONDS)
            }
            surface.release()
            texture.release()
            executor.shutdownNow()
            Thread.sleep(CLOSE_SETTLE_MILLIS)
        }
    }

    private fun applyMotorolaSessionKeys(manager: CameraManager, builder: CaptureRequest.Builder) {
        val keys = manager.getCameraCharacteristics(LOGICAL_CAMERA_ID).availableCaptureRequestKeys
        keys.firstOrNull { it.name == IS_MOT_CAMERA2 }?.let { key ->
            @Suppress("UNCHECKED_CAST")
            builder.set(key as CaptureRequest.Key<ByteArray>, byteArrayOf(1))
        }
        keys.firstOrNull { it.name == CURRENT_MODE }?.let { key ->
            @Suppress("UNCHECKED_CAST")
            builder.set(key as CaptureRequest.Key<IntArray>, intArrayOf(3))
        }
    }

    private fun result(
        candidate: Candidate,
        colorSpace: ColorSpace.Named?,
        status: String,
        reason: String,
        supportQuery: Boolean?,
        frames: Long,
        firstTimestamp: Long?,
        lastTimestamp: Long?,
    ): JSONObject {
        val elapsed = if (firstTimestamp != null && lastTimestamp != null && lastTimestamp > firstTimestamp) {
            (lastTimestamp - firstTimestamp) / 1_000_000_000.0
        } else null
        return JSONObject()
            .put("size", candidate.size.toString())
            .put("fps", candidate.fps)
            .put("dynamicRange", candidate.dynamicRangeName)
            .put("dynamicRangeProfile", candidate.dynamicRangeProfile)
            .put("colorSpace", colorSpace?.name ?: JSONObject.NULL)
            .put("status", status)
            .put("reason", reason)
            .put("isSessionConfigurationSupported", supportQuery ?: JSONObject.NULL)
            .put("captureResults", frames)
            .put("sensorElapsedSeconds", elapsed ?: JSONObject.NULL)
            .put("effectiveResultRate", if (elapsed != null && elapsed > 0) (frames - 1) / elapsed else JSONObject.NULL)
    }

    private companion object {
        const val TAG = "OCC_LOG_HFR_SESSION"
        const val OUTPUT_NAME = "occ-log-high-speed-session.json"
        const val LOG_PART_BYTES = 3000
        const val CAMERA_ID = "2"
        const val LOGICAL_CAMERA_ID = "0"
        const val IS_MOT_CAMERA2 = "com.lenovo.moto.clientapp.is_motcamera2"
        const val CURRENT_MODE = "com.lenovo.moto.clientapp.current_mode"
        const val TIMEOUT_SECONDS = 8L
        const val STREAM_MILLIS = 2_000L
        const val CLOSE_SETTLE_MILLIS = 500L
        val CANDIDATES = listOf(
            Candidate(Size(1920, 1080), 120, DynamicRangeProfiles.STANDARD, "STANDARD", ColorSpace.Named.SRGB),
            Candidate(Size(1920, 1080), 120, DynamicRangeProfiles.HLG10, "HLG10", ColorSpace.Named.BT2020_HLG),
            Candidate(Size(1920, 1080), 240, DynamicRangeProfiles.HLG10, "HLG10", ColorSpace.Named.BT2020_HLG),
            Candidate(Size(3840, 2160), 120, DynamicRangeProfiles.HLG10, "HLG10", ColorSpace.Named.BT2020_HLG),
            Candidate(Size(1920, 1080), 120, DynamicRangeProfiles.HDR10, "HDR10", ColorSpace.Named.BT2020_PQ),
            Candidate(Size(1920, 1080), 240, DynamicRangeProfiles.HDR10, "HDR10", ColorSpace.Named.BT2020_PQ),
            Candidate(Size(3840, 2160), 120, DynamicRangeProfiles.HDR10, "HDR10", ColorSpace.Named.BT2020_PQ),
            Candidate(
                Size(1920, 1080),
                120,
                DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM,
                "DOLBY_VISION_10B_HDR_OEM",
                ColorSpace.Named.BT2020_PQ,
            ),
        )
    }

    private data class Candidate(
        val size: Size,
        val fps: Int,
        val dynamicRangeProfile: Long,
        val dynamicRangeName: String,
        val preferredColorSpace: ColorSpace.Named,
    )
}
