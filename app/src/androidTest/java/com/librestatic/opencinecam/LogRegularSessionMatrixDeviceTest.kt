/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.graphics.ColorSpace
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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

/** Physical HAL intersection check for regular HLG10/BT.2020 LOG resolutions at 60 fps. */
@RunWith(AndroidJUnit4::class)
class LogRegularSessionMatrixDeviceTest {
    // The adopted shell identity is process-wide; leaving it set breaks later cases that need the app's own uid.
    @org.junit.After fun dropAdoptedShellIdentity() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()

    @Test
    fun verifiesRegularHlg10ResolutionCeilings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val context = instrumentation.targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val results = JSONArray().also { array -> SIZES.forEach { array.put(probe(manager, it)) } }
        val output = JSONObject()
            .put("schema", "opencinecam-log-regular-session-matrix-v1")
            .put("cameraId", CAMERA_ID)
            .put("fps", FPS)
            .put("results", results)
        val file = File(requireNotNull(context.getExternalFilesDir(null)), OUTPUT_NAME)
        val text = output.toString(2) + "\n"
        file.writeText(text, Charsets.UTF_8)
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            .chunked(LOG_PART_BYTES)
            .forEachIndexed { index, part -> Log.i(TAG, "json-part=${index + 1}:$part") }
        assertTrue(file.isFile && file.length() > 0)
    }

    private fun probe(manager: CameraManager, size: Size): JSONObject {
        val executor = Executors.newSingleThreadExecutor()
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(size.width, size.height) }
        val surface = Surface(texture)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var supportQuery: Boolean? = null
        var status = "FAILED"
        var reason = "unknown"
        val closed = CountDownLatch(1)
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
                    closed.countDown()
                }
            })
            if (!opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) || device == null) {
                return result(size, status, reason, supportQuery)
            }
            val configured = CountDownLatch(1)
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(surface).apply { setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(created: CameraCaptureSession) {
                        session = created
                        status = "PASS"
                        reason = "hlg-regular-session-configured"
                        configured.countDown()
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        reason = "session-configure-failed"
                        failed.close()
                        configured.countDown()
                    }
                },
            ).apply {
                setColorSpace(ColorSpace.Named.BT2020_HLG)
                sessionParameters = requireNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
                }.build()
            }
            supportQuery = runCatching { requireNotNull(device).isSessionConfigurationSupported(configuration) }.getOrNull()
            requireNotNull(device).createCaptureSession(configuration)
            if (!configured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) reason = "session-configure-timeout"
            result(size, status, reason, supportQuery)
        } catch (error: Throwable) {
            result(size, status, "${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}", supportQuery)
        } finally {
            runCatching { session?.close() }
            runCatching {
                device?.close()
                if (device != null) closed.await(2, TimeUnit.SECONDS)
            }
            surface.release()
            texture.release()
            executor.shutdownNow()
            Thread.sleep(CLOSE_SETTLE_MILLIS)
        }
    }

    private fun result(size: Size, status: String, reason: String, supportQuery: Boolean?) = JSONObject()
        .put("size", size.toString())
        .put("status", status)
        .put("reason", reason)
        .put("isSessionConfigurationSupported", supportQuery ?: JSONObject.NULL)

    private companion object {
        const val TAG = "OCC_LOG_REG_SESSION"
        const val OUTPUT_NAME = "occ-log-regular-session-matrix.json"
        const val CAMERA_ID = "0"
        const val FPS = 60
        const val TIMEOUT_SECONDS = 8L
        const val CLOSE_SETTLE_MILLIS = 500L
        const val LOG_PART_BYTES = 3000
        val SIZES = listOf(Size(1280, 720), Size(1920, 1080), Size(3840, 2160))
    }
}
