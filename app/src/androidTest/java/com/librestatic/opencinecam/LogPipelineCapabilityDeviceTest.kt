/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical, read-only gate for choosing a technically honest live Log route. */
@RunWith(AndroidJUnit4::class)
class LogPipelineCapabilityDeviceTest {
    @Test
    fun writesCameraP010TonemapAndGpuEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val cameras = JSONArray()
        manager.cameraIdList.sorted().forEach { cameraId ->
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val dynamicRanges = characteristics
                .get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            val colors = characteristics
                .get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
            val capabilities = characteristics
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()
            val tonemapModes = characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                ?: intArrayOf()
            val p010Sizes = runCatching { map?.getOutputSizes(ImageFormat.YCBCR_P010).orEmpty() }
                .getOrDefault(emptyArray())
            val p010HlgColors = if (Build.VERSION.SDK_INT >= 34 && dynamicRanges != null) {
                runCatching {
                    colors?.getSupportedColorSpacesForDynamicRange(
                        ImageFormat.YCBCR_P010,
                        DynamicRangeProfiles.HLG10,
                    ).orEmpty()
                }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
            val privateHlgColors = if (Build.VERSION.SDK_INT >= 34 && dynamicRanges != null) {
                runCatching {
                    colors?.getSupportedColorSpacesForDynamicRange(
                        ImageFormat.PRIVATE,
                        DynamicRangeProfiles.HLG10,
                    ).orEmpty()
                }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
            val hlgConstraints = runCatching {
                dynamicRanges?.getProfileCaptureRequestConstraints(DynamicRangeProfiles.HLG10).orEmpty()
            }.getOrDefault(emptySet())
            cameras.put(
                JSONObject()
                    .put("cameraId", cameraId)
                    .put("capabilities", ints(capabilities.toList()))
                    .put(
                        "tenBitDynamicRangeCapability",
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT in capabilities,
                    )
                    .put(
                        "manualPostProcessing",
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities,
                    )
                    .put("tonemapModes", ints(tonemapModes.toList()))
                    .put("tonemapContrastCurve", CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE in tonemapModes)
                    .put("tonemapMaxCurvePoints", characteristics.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: JSONObject.NULL)
                    .put("p010Sizes", strings(p010Sizes.map { "${it.width}x${it.height}" }))
                    .put("p010HlgColorSpaces", strings(p010HlgColors.map(ColorSpace.Named::name).sorted()))
                    .put("privateHlgColorSpaces", strings(privateHlgColors.map(ColorSpace.Named::name).sorted()))
                    .put("hlgCaptureRequestConstraints", longs(hlgConstraints.sorted()))
                    .put("hlgCanMixStandard", DynamicRangeProfiles.STANDARD in hlgConstraints),
            )
        }

        val output = JSONObject()
            .put("schema", "opencinecam-log-pipeline-capability-v1")
            .put("protocolVersion", 1)
            .put("collectedAtEpochMs", System.currentTimeMillis())
            .put(
                "device",
                JSONObject()
                    .put("fingerprint", Build.FINGERPRINT)
                    .put("model", Build.MODEL)
                    .put("sdk", Build.VERSION.SDK_INT),
            )
            .put("cameras", cameras)
            .put("gpu", gpuEvidence())

        val directory = requireNotNull(context.getExternalFilesDir(null))
        val file = File(directory, OUTPUT_NAME)
        val encoded = output.toString(2) + "\n"
        file.writeText(encoded, Charsets.UTF_8)
        Base64.encodeToString(encoded.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            .chunked(LOG_PART_BYTES)
            .forEachIndexed { index, part -> Log.i(TAG, "json-part=${index + 1}:$part") }
        assertTrue(file.isFile && file.length() > 0)
    }

    private fun gpuEvidence(): JSONObject {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
            return JSONObject().put("status", "UNAVAILABLE")
        }
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,
                EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            val eglExtensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty().split(' ').filter(String::isNotBlank)
            val glExtensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().split(' ').filter(String::isNotBlank)
            JSONObject()
                .put("status", "AVAILABLE")
                .put("eglVersion", "${version[0]}.${version[1]}")
                .put("glVersion", GLES30.glGetString(GLES30.GL_VERSION))
                .put("renderer", GLES30.glGetString(GLES30.GL_RENDERER))
                .put("eglExtensions", strings(eglExtensions.sorted()))
                .put("glExtensions", strings(glExtensions.sorted()))
                .put("externalOesEssl3", "GL_OES_EGL_image_external_essl3" in glExtensions)
                .put("yuvTarget", "GL_EXT_YUV_target" in glExtensions)
                .put("eglBt2020Hlg", "EGL_EXT_gl_colorspace_bt2020_hlg" in eglExtensions)
                .put("eglNativeBuffer", "EGL_ANDROID_image_native_buffer" in eglExtensions)
                .put("halfFloatRenderTarget", "GL_EXT_color_buffer_half_float" in glExtensions)
                .put("floatRenderTarget", "GL_EXT_color_buffer_float" in glExtensions)
        } catch (error: Throwable) {
            JSONObject().put("status", "FAILED").put("error", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun strings(values: List<String>) = JSONArray().also { array -> values.forEach(array::put) }
    private fun ints(values: List<Int>) = JSONArray().also { array -> values.forEach(array::put) }
    private fun longs(values: List<Long>) = JSONArray().also { array -> values.forEach(array::put) }

    private companion object {
        const val TAG = "OCC_LOG_PIPELINE_GATE"
        const val OUTPUT_NAME = "occ-log-pipeline-capability.json"
        const val LOG_PART_BYTES = 3000
    }
}
