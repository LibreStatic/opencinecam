/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exploratory: can a Qualcomm HAL that caps public RAW at 30 fps be pushed to 60 fps RAW with
 * standard or vendor controls? Each case logs one JSON line under [TAG]; it asserts nothing.
 * Optional arguments: `sixtyCamera` (default 0), `sixtyCases` (comma-separated case names),
 * `sixtyProbeMs` (measured window per case, default [PROBE_MS]).
 */
@RunWith(AndroidJUnit4::class)
class RawSixtyProbeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @After fun cleanup() = instrumentation.uiAutomation.dropShellPermissionIdentity()

    private class Case(
        val name: String,
        val format: Int = ImageFormat.RAW10,
        val width: Int = 0,
        val height: Int = 0,
        val preview: Boolean = true,
        val aeRange: Range<Int>? = null,
        val manualFrameNs: Long = 0L,
        val exposureNs: Long = 4_000_000L,
        val iso: Int = 800,
        val request: Map<String, Any> = emptyMap(),
        val session: Map<String, Any> = emptyMap(),
        /** Rows of each RAW10 frame copied to a direct buffer before close, as the qualifier's reader does (0: none). */
        val copyRows: Int = 0,
        /** Whether the session gets the AE range (and [session]) as session parameters, as SessionConfiguration allows. */
        val sessionParameters: Boolean = true,
        /** Copy into an ashmem mapping ([android.os.SharedMemory]), as the qualifier's ring slots are, instead of a direct buffer. */
        val copyShared: Boolean = false,
        /** Run the reader listener on its own thread, as the qualifier does, instead of the camera callback thread. */
        val readerThread: Boolean = false,
        val maxImages: Int = 12,
        /** Create the session with the deprecated createCaptureSession(List, ...) the qualifier uses. */
        val legacySession: Boolean = false,
        /** Size the preview with [com.librestatic.opencinecam.rawvideo.rawQualificationPreviewSize], as the qualifier does. */
        val qualifierPreview: Boolean = false,
        /** Copy into this many prefaulted ashmem slots in turn, as the qualifier's ring does, instead of one. */
        val copySlots: Int = 1,
        /** Copy through the qualifier's own pieces: a [RawVideoRing] of [copySlots] shared-memory slots, copyRaw10Rows and a consumer thread. */
        val qualifierRing: Boolean = false,
        /** Create the repeating request only once the session is configured, as the qualifier does. */
        val requestAfterSession: Boolean = false,
    )

    @Test
    fun sixty() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        val id = args.getString("sixtyCamera") ?: "0"
        val characteristics = manager.getCameraCharacteristics(id)
        val map = requireNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val raw = map.getOutputSizes(ImageFormat.RAW10).maxBy { it.width.toLong() * it.height }
        Log.i(TAG, "camera=$id raw10=$raw minFrameMs=${map.getOutputMinFrameDuration(ImageFormat.RAW10, raw) / 1e6}")
        val r60 = Range(60, 60)
        val frame60 = 16_666_666L
        val qcom60 = mapOf<String, Any>(
            "org.quic.camera.manualExposure.disableFPSLimits" to 1.toByte(),
            "org.quic.camera.manualExposure.overrideSensorFrameDuration" to frame60)
        val cases = listOf(
            Case("ae30", aeRange = Range(30, 30)),
            Case("ae60", aeRange = r60),
            Case("ae60-rawOnly", aeRange = r60, preview = false),
            Case("ae60-rawSensor", format = ImageFormat.RAW_SENSOR, aeRange = r60),
            Case("manual60", manualFrameNs = frame60),
            Case("manual60-disableFpsLimits", manualFrameNs = frame60, request = mapOf(
                "org.quic.camera.manualExposure.disableFPSLimits" to 1.toByte(),
                "org.quic.camera.manualExposure.overrideSensorFrameDuration" to frame60)),
            Case("manual60-disableFpsLimits-bright", manualFrameNs = frame60, exposureNs = 15_000_000L, iso = 3200, request = mapOf(
                "org.quic.camera.manualExposure.disableFPSLimits" to 1.toByte(),
                "org.quic.camera.manualExposure.overrideSensorFrameDuration" to frame60)),
            Case("manual30-bright", manualFrameNs = 33_333_333L, exposureNs = 15_000_000L, iso = 3200),
            Case("ae60-disable", aeRange = r60, request = qcom60),
            Case("ae30-override60", aeRange = Range(30, 30), request = qcom60),
            Case("ae30-override60-rawOnly", aeRange = Range(30, 30), preview = false, request = qcom60),
            Case("ae30-override60-noSessionParams", aeRange = Range(30, 30), request = qcom60, sessionParameters = false),
            Case("ae30-override60-copyFull", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height),
            Case("ae30-override60-copyShared", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height, copyShared = true),
            Case("ae30-override60-copyThread", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height, readerThread = true),
            Case("ae30-override60-copy8", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height, maxImages = 8),
            Case("ae30-override60-copyAll", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height, copyShared = true,
                readerThread = true, maxImages = 8),
            Case("ae30-override60-legacy", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height, legacySession = true),
            Case("ae30-override60-qualPreview", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height, qualifierPreview = true),
            Case("ae30-override60-legacyQualPreview", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height,
                legacySession = true, qualifierPreview = true),
            Case("ae30-override60-copy1080", aeRange = Range(30, 30), request = qcom60, copyRows = 1080),
            Case("ae15to60-disable", aeRange = Range(15, 60), request = qcom60),
            Case("ae60-disable-session", aeRange = r60, request = qcom60, session = qcom60),
            Case("ae60-costOverride", aeRange = r60, session = mapOf(
                "org.codeaurora.qcamera3.sessionParameters.overrideResourceCostValidation" to 1.toByte())),
            Case("ae60-mode7-session", aeRange = r60, session = mapOf(SENSOR_MODE to 7)),
            Case("ae60-mode8-session", aeRange = r60, session = mapOf(SENSOR_MODE to 8)),
            Case("ae60-mode7-both", aeRange = r60, session = mapOf(SENSOR_MODE to 7), request = mapOf(SENSOR_MODE to 7)),
            Case("manual60-mode7-vendor", manualFrameNs = frame60, session = mapOf(SENSOR_MODE to 7), request = mapOf(
                SENSOR_MODE to 7,
                "org.quic.camera.manualExposure.disableFPSLimits" to 1.toByte(),
                "org.quic.camera.manualExposure.overrideSensorFrameDuration" to frame60)),
            Case("ae60-targetFps", aeRange = r60, session = mapOf("org.codeaurora.qcamera3.sensor_meta_data.targetFPS" to 60),
                request = mapOf("org.codeaurora.qcamera3.sensor_meta_data.targetFPS" to 60)),
            Case("ae60-16x9-unlisted", width = raw.width, height = raw.width * 9 / 16 and 1.inv(), aeRange = r60),
            // Does an exposure close to the 60 fps frame period make the HAL skip frames? AE picks 16.23 ms
            // indoors; these hold the same brightness (exposure x ISO) at shorter exposures.
            Case("ae30-override60-copyAll-ae", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height, copyShared = true),
            Case("manual60-exp16", manualFrameNs = frame60, exposureNs = 16_230_000L, iso = 968, request = qcom60,
                copyRows = raw.height, copyShared = true),
            Case("manual60-exp14", manualFrameNs = frame60, exposureNs = 14_000_000L, iso = 1122, request = qcom60,
                copyRows = raw.height, copyShared = true),
            Case("manual60-exp12", manualFrameNs = frame60, exposureNs = 12_000_000L, iso = 1309, request = qcom60,
                copyRows = raw.height, copyShared = true),
            Case("manual60-exp8", manualFrameNs = frame60, exposureNs = 8_333_333L, iso = 1886, request = qcom60,
                copyRows = raw.height, copyShared = true),
            // The qualifier copies each frame into the next of many ring slots, never twice into the same memory.
            Case("ae30-override60-slots1", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height, copyShared = true,
                readerThread = true, maxImages = 8),
            Case("ae30-override60-slots40", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height, copyShared = true,
                readerThread = true, maxImages = 8, copySlots = 40),
            Case("ae30-override60-slots40-legacyQual", aeRange = Range(30, 30), request = qcom60, copyRows = raw.height, copyShared = true,
                readerThread = true, maxImages = 8, copySlots = 40, legacySession = true, qualifierPreview = true),
            Case("ae30-override60-qualRing", aeRange = Range(30, 30), request = qcom60, readerThread = true, maxImages = 8,
                copySlots = 40, legacySession = true, qualifierPreview = true, qualifierRing = true),
            Case("ae30-override60-qualRing-late", aeRange = Range(30, 30), request = qcom60, readerThread = true, maxImages = 8,
                copySlots = 40, legacySession = true, qualifierPreview = true, qualifierRing = true, requestAfterSession = true),
            Case("ae30-override60-qualRing-late-params", aeRange = Range(30, 30), request = qcom60, readerThread = true, maxImages = 8,
                copySlots = 40, qualifierPreview = true, qualifierRing = true, requestAfterSession = true),
        )
        val only = args.getString("sixtyCases")?.split(',')?.map(String::trim)?.toSet()
        for (case in cases) {
            if (only != null && case.name !in only) continue
            val json = runCatching { probe(manager, id, characteristics, case, raw.width, raw.height) }
                .getOrElse { JSONObject().put("case", case.name).put("error", "${it.javaClass.simpleName}: ${it.message?.take(200)}") }
            Log.i(TAG, json.toString())
            SystemClock.sleep(1_500)
        }
    }

    private fun probe(manager: CameraManager, id: String, characteristics: CameraCharacteristics, case: Case, rawW: Int, rawH: Int): JSONObject {
        val width = if (case.width > 0) case.width else rawW
        val height = if (case.height > 0) case.height else rawH
        val out = JSONObject().put("case", case.name).put("size", "${width}x$height").put("format", case.format)
        val thread = HandlerThread("occ-sixty").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { handler.post(it) }
        val reader = ImageReader.newInstance(width, height, case.format, case.maxImages)
        val readerThread = if (case.readerThread) HandlerThread("occ-sixty-reader").apply { start() } else null
        val readerHandler = readerThread?.let { Handler(it.looper) } ?: handler
        val previewSize = if (case.qualifierPreview) {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)
                ?.map { it.width to it.height }?.let { com.librestatic.opencinecam.rawvideo.rawQualificationPreviewSize(it) } ?: (1920 to 1080)
        } else {
            1920 to 1080
        }
        out.put("preview", "${previewSize.first}x${previewSize.second}")
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(previewSize.first, previewSize.second) }
        val preview = Surface(texture)
        val frames = AtomicInteger()
        val firstTs = AtomicLong(-1)
        val lastTs = AtomicLong(-1)
        var imageSize = ""
        val stamps = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val copyBytes = width * 10 / 8 * case.copyRows
        val sharedMemories = if (case.copyRows > 0 && case.copyShared) {
            List(case.copySlots) { android.os.SharedMemory.create("occ-sixty-$it", copyBytes) }
        } else emptyList()
        val sharedMemory = sharedMemories.firstOrNull()
        val slotTargets = sharedMemories.map { memory ->
            memory.mapReadWrite().also { b -> var i = 0; while (i < b.capacity()) { b.put(i, 0); i += 4096 } }
        }
        val copyTarget = when {
            sharedMemory != null -> slotTargets[0]
            case.copyRows > 0 -> java.nio.ByteBuffer.allocateDirect(copyBytes)
            else -> null
        }
        val copyTimes = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val ringMemory = if (case.qualifierRing) com.librestatic.opencinecam.media.raw.SharedMemorySlots() else null
        val ring = ringMemory?.let {
            com.librestatic.opencinecam.media.raw.RawVideoRing(
                com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader.raw10RowBytes(width) * height, case.copySlots, it::allocate)
        }
        val consumerStop = java.util.concurrent.atomic.AtomicBoolean()
        val consumer = ring?.let { r ->
            Thread({
                while (true) {
                    val slot = r.take(100L)
                    if (slot == null) { if (consumerStop.get() && r.queuedSlots == 0) break else continue }
                    r.release(slot)
                }
            }, "occ-sixty-consumer").apply { start() }
        }
        var ringLost = 0
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val n = frames.getAndIncrement()
            if (n == 0) imageSize = "${image.width}x${image.height} stride=${image.planes[0].rowStride}"
            stamps += image.timestamp
            if (n == 90 || n == 91) runCatching {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                java.io.File(context.getExternalFilesDir(null), "sixty-${case.name}-$n-${image.planes[0].rowStride}.raw").writeBytes(bytes)
            }
            if (ring != null) {
                val slot = ring.tryAcquire()
                if (slot == null) ringLost++ else {
                    val plane = image.planes[0]
                    val copyStart = System.nanoTime()
                    com.librestatic.opencinecam.media.raw.copyRaw10Rows(plane.buffer.duplicate().clear() as java.nio.ByteBuffer, plane.rowStride, image.width, image.height,
                        0, 0, width, height, slot.buffer)
                    copyTimes += System.nanoTime() - copyStart
                    ring.publish(slot, com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader.raw10(width, height, 0, 0,
                        image.timestamp, n.toLong()))
                }
            } else if (copyTarget != null) {
                val plane = image.planes[0]
                val rowBytes = width * 10 / 8
                val top = (height - case.copyRows) / 2
                val source = plane.buffer
                val target = if (slotTargets.size > 1) slotTargets[n % slotTargets.size] else copyTarget
                val copyStart = System.nanoTime()
                target.clear()
                for (row in 0 until case.copyRows) {
                    source.limit(source.capacity()).position((top + row) * plane.rowStride)
                    source.limit(source.position() + rowBytes)
                    target.put(source)
                }
                copyTimes += System.nanoTime() - copyStart
            }
            firstTs.compareAndSet(-1, image.timestamp)
            lastTs.set(image.timestamp)
            image.close()
        }, readerHandler)
        val completed = AtomicInteger()
        val failed = AtomicInteger()
        val lastFrameDuration = AtomicLong()
        val lastExposure = AtomicLong()
        var lastIso = 0
        var noiseProfile: String? = null
        var blackLevel: String? = null
        var lastMode: Any? = null
        var lastVendorFps: Any? = null
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            val opened = CountDownLatch(1)
            var error: String? = null
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
                override fun onDisconnected(d: CameraDevice) { error = "disconnected"; d.close(); opened.countDown() }
                override fun onError(d: CameraDevice, code: Int) { error = "open-error-$code"; d.close(); opened.countDown() }
            }, handler)
            check(opened.await(10, TimeUnit.SECONDS)) { "open timeout" }
            error?.let { return out.put("error", it) }
            val cam = requireNotNull(device)
            fun buildRequest() = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                if (case.preview) addTarget(preview)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                case.aeRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                if (case.manualFrameNs > 0) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, case.manualFrameNs)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, case.exposureNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, case.iso)
                }
                for ((name, value) in case.request) setVendor(this, name, value)
            }
            val earlyRequest = if (case.requestAfterSession) null else buildRequest()
            // A legacy session never reads them; creating the request anyway could change what the HAL configures with.
            val sessionParams = if (case.legacySession && case.requestAfterSession) null else cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                case.aeRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                for ((name, value) in case.session) setVendor(this, name, value)
            }.build()
            val outputs = listOfNotNull(OutputConfiguration(reader.surface), if (case.preview) OutputConfiguration(preview) else null)
            val configured = CountDownLatch(1)
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) { error = "configure-failed"; configured.countDown() }
                })
            if (case.sessionParameters && sessionParams != null) config.sessionParameters = sessionParams
            if (case.legacySession) {
                @Suppress("DEPRECATION")
                cam.createCaptureSession(listOfNotNull(reader.surface, if (case.preview) preview else null),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                        override fun onConfigureFailed(s: CameraCaptureSession) { error = "configure-failed"; configured.countDown() }
                    }, handler)
            } else {
                cam.createCaptureSession(config)
            }
            check(configured.await(10, TimeUnit.SECONDS)) { "configure timeout" }
            error?.let { return out.put("error", it) }
            val s = requireNotNull(session)
            val modeKey = resultKey(SENSOR_MODE)
            val fpsKey = resultKey("org.codeaurora.qcamera3.sensor_meta_data.targetFPS")
            s.setRepeatingRequest((earlyRequest ?: buildRequest()).build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    completed.incrementAndGet()
                    result.get(CaptureResult.SENSOR_FRAME_DURATION)?.let(lastFrameDuration::set)
                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let(lastExposure::set)
                    result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
                    result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { p -> noiseProfile = p.joinToString(";") { "${it.first},${it.second}" } }
                    result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { blackLevel = it.joinToString(",") }
                    lastMode = runCatching { modeKey?.let(result::get) }.getOrNull() ?: lastMode
                    lastVendorFps = runCatching { fpsKey?.let(result::get) }.getOrNull() ?: lastVendorFps
                }

                override fun onCaptureFailed(cs: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) { failed.incrementAndGet() }
            }, handler)
            SystemClock.sleep(1_000)
            val f0 = frames.get()
            val t0 = lastTs.get()
            SystemClock.sleep(args.getString("sixtyProbeMs")?.toLongOrNull()?.coerceAtLeast(500L) ?: PROBE_MS)
            val f1 = frames.get()
            val t1 = lastTs.get()
            val fps = if (f1 - f0 > 1 && t0 > 0 && t1 > t0) (f1 - f0) * 1e9 / (t1 - t0) else 0.0
            runCatching { s.stopRepeating() }
            val gaps = synchronized(stamps) { stamps.zipWithNext { a, b -> b - a } }.sorted()
            val copies = synchronized(copyTimes) { copyTimes.sorted() }
            if (copies.isNotEmpty()) out.put("copyMs", "p50=${copies[copies.size / 2] / 1e6} p99=${copies[copies.size * 99 / 100] / 1e6}")
            out.put("lateGaps", gaps.count { it > 25_000_000L })
            if (gaps.isNotEmpty()) out.put("gapMs", "min=${gaps.first() / 1e6} med=${gaps[gaps.size / 2] / 1e6} max=${gaps.last() / 1e6}")
            out.put("rawFrames", f1).put("rawFps", Math.round(fps * 10) / 10.0).put("imageSize", imageSize)
                .put("completed", completed.get()).put("failed", failed.get())
                .put("frameDurationMs", lastFrameDuration.get() / 1e6).put("exposureMs", lastExposure.get() / 1e6)
                .put("sensorModeIndex", lastMode?.toString() ?: JSONObject.NULL)
                .put("vendorTargetFps", lastVendorFps?.toString() ?: JSONObject.NULL)
                .put("iso", lastIso).put("noiseProfile", noiseProfile ?: JSONObject.NULL).put("blackLevel", blackLevel ?: JSONObject.NULL)
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            SystemClock.sleep(500)
            reader.close()
            preview.release(); texture.release()
            thread.quitSafely()
            readerThread?.quitSafely()
            consumerStop.set(true)
            consumer?.join(2_000L)
            if (ring != null) out.put("ringLost", ringLost)
            ringMemory?.close()
            slotTargets.forEach { android.os.SharedMemory.unmap(it) }
            sharedMemories.forEach { it.close() }
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun setVendor(builder: CaptureRequest.Builder, name: String, value: Any) {
        when (value) {
            is Byte -> builder.set(CaptureRequest.Key(name, Byte::class.javaObjectType), value)
            is Int -> builder.set(CaptureRequest.Key(name, Int::class.javaObjectType), value)
            is Long -> builder.set(CaptureRequest.Key(name, Long::class.javaObjectType), value)
            else -> error("unsupported vendor value $value")
        }
    }

    private fun resultKey(name: String): CaptureResult.Key<Int>? = runCatching { CaptureResult.Key(name, Int::class.javaObjectType) }.getOrNull()

    private companion object {
        const val TAG = "OCC_RAW_SIXTY"
        const val SENSOR_MODE = "org.codeaurora.qcamera3.sensor_meta_data.sensor_mode_index"
        const val PROBE_MS = 3_000L
    }
}
