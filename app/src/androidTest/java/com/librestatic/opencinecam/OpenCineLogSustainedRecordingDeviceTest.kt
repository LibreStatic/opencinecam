/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:Suppress("DEPRECATION")

package com.librestatic.opencinecam

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.Camera2Analysis
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.Camera2PreviewListener
import com.librestatic.opencinecam.camera.Camera2PreviewMetadata
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.roundToLong

/** Physical Plan-061 fixture: the production OCLog2 GPU path sustained for at least 30 seconds. */
@RunWith(AndroidJUnit4::class)
@RequiresDevice
class OpenCineLogSustainedRecordingDeviceTest {
    @Test
    fun recordsProductionHlgOcLog2ForThirtySeconds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        try {
            val context = instrumentation.targetContext
            val directory = requireNotNull(context.getExternalFilesDir(null))
            val clip = File(directory, CLIP_NAME).apply { delete() }
            val sidecar = File(directory, SIDECAR_NAME).apply { delete() }
            val resultFile = File(directory, RESULT_NAME).apply { delete() }
            val result = runCatching { record(context, clip, sidecar) }.getOrElse { error ->
                JSONObject()
                    .put("status", "FAILED")
                    .put("reason", "${error.javaClass.simpleName}:${error.message.orEmpty().take(240)}")
            }
            val text = result.toString(2) + "\n"
            resultFile.writeText(text, Charsets.UTF_8)
            Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP).chunked(LOG_PART_BYTES)
                .forEachIndexed { index, part -> Log.i(TAG, "json-part=${index + 1}:$part") }
            assertEquals(result.toString(2), "PASS", result.getString("status"))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun record(context: Context, clip: File, sidecar: File): JSONObject {
        val engine = Camera2PreviewEngine(context)
        var reader: ImageReader? = null
        val imageThread = HandlerThread("oclog2-test-preview").apply { start() }
        val imageHandler = Handler(imageThread.looper)
        var descriptor: Camera2CameraDescriptor? = null
        var evidence: OpenCineLogRecordingEvidence? = null
        val previewStarted = CountDownLatch(1)
        val previewCadenceStable = CountDownLatch(1)
        val stablePreviewSamples = AtomicInteger(0)
        val recordingStarted = CountDownLatch(1)
        val recordingStopped = CountDownLatch(1)
        var failure: String? = null
        val listener = object : Camera2PreviewListener {
            override fun onOpening(descriptor: Camera2CameraDescriptor) = Unit
            override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) { previewStarted.countDown() }
            override fun onMetadata(metadata: Camera2PreviewMetadata) {
                val effectiveFps = metadata.effectiveFps ?: return
                val stable = if (effectiveFps in MIN_STABLE_PREVIEW_FPS..MAX_STABLE_PREVIEW_FPS) {
                    stablePreviewSamples.incrementAndGet()
                } else {
                    stablePreviewSamples.getAndSet(0)
                }
                if (stable >= PREVIEW_STABLE_SAMPLES) previewCadenceStable.countDown()
            }
            override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
            override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
            override fun onAnalysis(analysis: Camera2Analysis) = Unit
            override fun onRecordingStarted(width: Int, height: Int) { recordingStarted.countDown() }
            override fun onRecordingStopped(success: Boolean) {
                if (!success) failure = "recording-finalize-failed"
                recordingStopped.countDown()
            }
            override fun onFailure(code: String, message: String, recoverable: Boolean) {
                failure = "$code:${message.take(180)}"
                previewStarted.countDown()
                recordingStarted.countDown()
                recordingStopped.countDown()
            }
        }
        return try {
            descriptor = engine.descriptors(WIDTH, HEIGHT).firstOrNull { it.cameraId == CAMERA_ID }
                ?: error("camera-$CAMERA_ID-not-enumerated")
            val profile = descriptor.logProfiles.firstOrNull {
                it.size == Size(WIDTH, HEIGHT) && it.fps == FPS && it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020
            } ?: error("exact-hlg-profile-not-enumerated")
            reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.PRIVATE, 3).apply {
                setOnImageAvailableListener(
                    { source -> source.acquireLatestImage()?.close() },
                    imageHandler,
                )
            }
            engine.startPreview(
                descriptor = descriptor,
                surface = reader.surface,
                displayRotationDegrees = 0,
                listener = listener,
                openCineLog = true,
                targetFps = FPS,
                logProfile = profile,
            )
            check(previewStarted.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "preview-timeout" }
            failure?.let(::error)
            check(previewCadenceStable.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "preview-cadence-not-stable:${stablePreviewSamples.get()}"
            }
            failure?.let(::error)
            val previewStableSamplesAtStart = stablePreviewSamples.get()
            val geometry = RecordingGeometryCalculator.calculate(
                sourceSize = profile.size,
                sensorOrientationDegrees = descriptor.sensorOrientation,
                deviceOrientationDegrees = 0,
                lensFacing = descriptor.lensFacing,
                mode = RecordingGeometryMode.NATIVE_RASTER,
            )
            ParcelFileDescriptor.open(
                clip,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE,
            ).use { output ->
                check(engine.startOpenCineLogVideo(output, geometry, BITRATE)) { "pipeline-rejected-recording" }
                check(recordingStarted.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "recording-start-timeout" }
                failure?.let(::error)
                SystemClock.sleep(RECORD_MILLIS)
                check(engine.stopVideo()) { "recording-stop-rejected" }
                check(recordingStopped.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "recording-stop-timeout" }
                failure?.let(::error)
                evidence = engine.consumeLastOpenCineLogEvidence() ?: error("recording-evidence-missing")
            }
            val observed = requireNotNull(evidence)
            val durationSeconds = observed.firstPtsUs?.let { first ->
                observed.lastPtsUs?.let { last -> (last - first) / 1_000_000.0 }
            } ?: 0.0
            val expectedFrames = observed.firstPtsUs?.let { first ->
                observed.lastPtsUs?.let { last ->
                    (((last - first) / 1_000_000.0) * observed.targetFps).roundToLong() + 1L
                }
            } ?: 0L
            val droppedFrames = max(0L, expectedFrames - observed.encodedFrames)
            val sidecarJson = JSONObject()
                .put("schema", "opencinecam-oclog-sidecar-v2")
                .put("cameraId", CAMERA_ID)
                .put("source", JSONObject()
                    .put("path", observed.sourcePath.name)
                    .put("dynamicRange", observed.sourceDynamicRange)
                    .put("colorSpace", observed.sourceColorSpace)
                    .put("transfer", observed.sourceTransfer)
                    .put("precision", observed.sourcePrecision)
                    .put("surface", observed.sourceSurface)
                    .put("androidDataSpace", observed.sourceDataSpace ?: JSONObject.NULL)
                    .put("dataSpaceMismatchedFrames", observed.sourceDataSpaceMismatchedFrames ?: JSONObject.NULL)
                    .put("unexpectedAndroidDataSpace", observed.unexpectedSourceDataSpace ?: JSONObject.NULL))
                .put("transform", JSONObject()
                    .put("curve", observed.curve)
                    .put("version", "2.0.0")
                    .put("domain", "scene-linear")
                    .put("gamut", observed.gamut)
                    .put("range", observed.range)
                    .put("shaderSha256", observed.transformSha256))
                .put("qualification", JSONObject()
                    .put("stage", "EXPERIMENTAL")
                    .put("reason", "plan-061-physical-fixture-under-evaluation")
                    .put("evidenceId", JSONObject.NULL)
                    .put("exactProfileVerified", false))
                .put("encodedFrames", observed.encodedFrames)
                .put("expectedFrames", expectedFrames)
                .put("droppedFrames", droppedFrames)
                .put("dropMetric", "expected-from-first-last-pts")
                .put("previewWarmupPolicy", PREVIEW_WARMUP_POLICY)
                .put("previewStableSamples", previewStableSamplesAtStart)
                .put("targetFps", observed.targetFps)
                .put("firstPtsUs", observed.firstPtsUs)
                .put("lastPtsUs", observed.lastPtsUs)
                .put("maxVideoPtsGapUs", observed.maxVideoPtsGapUs)
                .put("videoPtsGapsOverThreshold", observed.videoPtsGapsOverThreshold)
            sidecar.writeText(sidecarJson.toString(2) + "\n", Charsets.UTF_8)
            val validationFailures = buildList {
                if (durationSeconds < MIN_ENCODED_DURATION_SECONDS) {
                    add("encoded-duration-short:$durationSeconds")
                }
                if (observed.encodedFrames < MIN_FRAMES) {
                    add("encoded-frame-count-low:${observed.encodedFrames}")
                }
                if (droppedFrames != 0L) add("encoded-frame-drop-count:$droppedFrames")
                if (observed.videoPtsGapsOverThreshold != 0L) {
                    add("video-pts-gap-count:${observed.videoPtsGapsOverThreshold}")
                }
                when (val mismatched = observed.sourceDataSpaceMismatchedFrames) {
                    null -> add("source-dataspace-unreported")
                    0L -> Unit
                    else -> add("source-dataspace-mismatch:$mismatched:${observed.unexpectedSourceDataSpace}")
                }
            }
            JSONObject()
                .put("status", if (validationFailures.isEmpty()) "PASS" else "FAILED")
                .put(
                    "reason",
                    if (validationFailures.isEmpty()) "sustained-pass" else validationFailures.joinToString(","),
                )
                .put("schema", "opencinecam-oclog2-sustained-device-v1")
                .put("device", JSONObject()
                    .put("fingerprint", Build.FINGERPRINT)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL))
                .put("cameraId", CAMERA_ID)
                .put("width", WIDTH)
                .put("height", HEIGHT)
                .put("fps", FPS)
                .put("sourcePath", observed.sourcePath.name)
                .put("sourceDataSpace", observed.sourceDataSpace)
                .put("sourceDataSpaceMismatchedFrames", observed.sourceDataSpaceMismatchedFrames)
                .put("sourceTransfer", observed.sourceTransfer)
                .put("sourcePrecision", observed.sourcePrecision)
                .put("shaderSha256", observed.transformSha256)
                .put("expectedShaderSha256", OpenCineLogGpuPipeline.transformSha256(observed.sourcePath))
                .put("durationSeconds", durationSeconds)
                .put("encodedFrames", observed.encodedFrames)
                .put("expectedFrames", expectedFrames)
                .put("droppedFrames", droppedFrames)
                .put("dropMetric", "expected-from-first-last-pts")
                .put("previewWarmupPolicy", PREVIEW_WARMUP_POLICY)
                .put("previewStableSamples", previewStableSamplesAtStart)
                .put("firstPtsUs", observed.firstPtsUs)
                .put("lastPtsUs", observed.lastPtsUs)
                .put("maxVideoPtsGapUs", observed.maxVideoPtsGapUs)
                .put("videoPtsGapsOverThreshold", observed.videoPtsGapsOverThreshold)
                .put("codecName", observed.codecName)
                .put("codecMime", observed.codecMime)
                .put("codecProfile", observed.codecProfile)
                .put("requestedBitrate", BITRATE)
                .put("geometryMode", observed.geometryMode.name)
                .put("encodedWidth", observed.encodedWidth)
                .put("encodedHeight", observed.encodedHeight)
                .put("clip", CLIP_NAME)
                .put("clipSha256", sha256(clip))
                .put("sidecar", SIDECAR_NAME)
                .put("sidecarSha256", sha256(sidecar))
        } finally {
            engine.close()
            reader?.close()
            imageThread.quitSafely()
            runCatching { imageThread.join(STOP_TIMEOUT_SECONDS * 1_000L) }
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "OCC_OCLOG2_SUSTAINED"
        const val CAMERA_ID = "0"
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val FPS = 30
        const val BITRATE = 20_000_000
        const val RECORD_MILLIS = 32_000L
        const val MIN_ENCODED_DURATION_SECONDS = 30.0
        const val MIN_FRAMES = 882
        const val START_TIMEOUT_SECONDS = 15L
        const val STOP_TIMEOUT_SECONDS = 12L
        const val LOG_PART_BYTES = 3000
        const val CLIP_NAME = "occ-plan-061-oclog2-hlg-1920x1080-30.mp4"
        const val SIDECAR_NAME = "occ-plan-061-oclog2-hlg-1920x1080-30.oclog.json"
        const val RESULT_NAME = "occ-plan-061-oclog2-hlg-1920x1080-30.result.json"
        const val PREVIEW_STABLE_SAMPLES = 3
        const val MIN_STABLE_PREVIEW_FPS = FPS * 0.98
        const val MAX_STABLE_PREVIEW_FPS = FPS * 1.02
        const val PREVIEW_WARMUP_POLICY = "3-consecutive-effective-fps-samples-within-2-percent"
    }
}
