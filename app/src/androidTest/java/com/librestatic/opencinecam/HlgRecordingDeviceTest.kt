/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.content.Context
import android.graphics.ColorSpace
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical OCC-PLAN-035 gate. This deliberately records only a short private
 * fixture; file/effective-depth promotion remains owned by OCC-PLAN-036.
 */
@RunWith(AndroidJUnit4::class)
class HlgRecordingDeviceTest {
    // The adopted shell identity is process-wide; leaving it set breaks later cases that need the app's own uid.
    @org.junit.After fun dropAdoptedShellIdentity() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()

    @Test
    fun recordsShortHlgMain10ClipAndWritesEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation
        uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val result = runCatching { record(context) }.getOrElse { error ->
            RecordingResult(
                status = "FAILED",
                reasonCode = "recording-exception",
                safeMessage = "HLG recording fixture failed: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(180)}",
            )
        }
        val output = result.toJson(context)
        val directory = requireNotNull(context.getExternalFilesDir(null)) { "external files directory unavailable" }
        File(directory, OUTPUT_NAME).writeText(output.toString(2) + "\n", Charsets.UTF_8)
        val encoded = Base64.encodeToString((output.toString(2) + "\n").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        encoded.chunked(LOG_PART_BYTES).forEachIndexed { index, part ->
            Log.i(TAG, "json-part=${index + 1}/${(encoded.length + LOG_PART_BYTES - 1) / LOG_PART_BYTES}:$part")
        }
        Log.i(TAG, "status=${result.status} reasonCode=${result.reasonCode}")
        assertTrue("HLG recording evidence was not written", File(directory, OUTPUT_NAME).isFile)
        assertTrue("HLG recording fixture crashed", result.status != "FAILED")
    }

    private fun record(context: Context): RecordingResult {
        if (Build.VERSION.SDK_INT < 33) {
            return unsupported("api-level-below-33", "HLG10 recording requires API 33 or newer")
        }
        val manager = context.getSystemService(CameraManager::class.java)
        val camera = findCamera(manager) ?: return unsupported(
            "camera-hlg10-or-bt2020-unsupported",
            "No public camera exposes HLG10 with BT.2020_HLG PRIVATE output.",
        )
        val codec = findCodec(camera.privateSizes) ?: return unsupported(
            "hevc-main10-surface-unsupported",
            "No hardware HEVC Main10 Surface encoder accepted the candidate graph.",
        )
        return recordGraph(context, manager, camera, codec)
    }

    private fun recordGraph(
        context: Context,
        manager: CameraManager,
        camera: CameraCandidate,
        codecCandidate: CodecCandidate,
    ): RecordingResult {
        val directory = requireNotNull(context.getExternalFilesDir(null)) { "external files directory unavailable" }
        val outputFile = File(directory, CLIP_NAME)
        outputFile.delete()
        val executor = Executors.newSingleThreadExecutor()
        val thread = HandlerThread("occ-hlg-record").apply { start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null
        var track = -1
        var muxerStarted = false
        var frames = 0
        var bytes = 0L
        var firstPtsUs: Long? = null
        var lastPtsUs: Long? = null
        var formatDescription: String? = null
        val cameraClosedLatch = CountDownLatch(1)
        return try {
            val format = codecCandidate.format
            codec = MediaCodec.createByCodecName(codecCandidate.name)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()

            val cameraLatch = CountDownLatch(1)
            var cameraError: String? = null
            manager.openCamera(camera.cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) {
                    device = opened
                    cameraLatch.countDown()
                }

                override fun onDisconnected(closed: CameraDevice) {
                    cameraError = "camera-disconnected"
                    closed.close()
                    cameraLatch.countDown()
                }

                override fun onError(closed: CameraDevice, error: Int) {
                    cameraError = "camera-open-error-$error"
                    closed.close()
                    cameraLatch.countDown()
                }

                override fun onClosed(closed: CameraDevice) {
                    cameraClosedLatch.countDown()
                }
            })
            if (!cameraLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return unsupported("camera-open-timeout", "Camera did not open within the bounded gate timeout.")
            }
            cameraError?.let { return unsupported(it, "Camera could not be opened for the HLG graph.") }
            val opened = requireNotNull(device)
            val outputConfiguration = OutputConfiguration(requireNotNull(inputSurface)).apply {
                setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
            }
            val sessionLatch = CountDownLatch(1)
            var sessionError: String? = null
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(created: CameraCaptureSession) {
                    session = created
                    sessionLatch.countDown()
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    sessionError = "session-configure-failed"
                    sessionLatch.countDown()
                }
            }
            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfiguration),
                executor,
                callback,
            )
            if (Build.VERSION.SDK_INT >= 34) sessionConfiguration.setColorSpace(ColorSpace.Named.BT2020_HLG)
            opened.createCaptureSession(sessionConfiguration)
            if (!sessionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return unsupported("session-configure-timeout", "HLG capture session did not configure within the bounded timeout.")
            }
            sessionError?.let { return unsupported(it, "The HLG capture session was rejected by CameraService.") }
            val created = requireNotNull(session)
            val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(requireNotNull(inputSurface))
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                camera.fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }.build()
            created.setRepeatingRequest(request, null, handler)
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + RECORD_MILLIS
            while (SystemClock.elapsedRealtime() < deadline) {
                when (val index = requireNotNull(codec).dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) return unsupported("duplicate-output-format", "Encoder emitted duplicate output formats.")
                        val outputFormat = requireNotNull(codec).outputFormat
                        track = requireNotNull(muxer).addTrack(outputFormat)
                        requireNotNull(muxer).start()
                        muxerStarted = true
                        formatDescription = outputFormat.toString()
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        if (info.size > 0 && muxerStarted) {
                            requireNotNull(muxer).writeSampleData(track, requireNotNull(requireNotNull(codec).getOutputBuffer(index)), info)
                            frames++
                            bytes += info.size
                            firstPtsUs = firstPtsUs ?: info.presentationTimeUs
                            lastPtsUs = info.presentationTimeUs
                        }
                        requireNotNull(codec).releaseOutputBuffer(index, false)
                    }
                }
            }
            runCatching { created.stopRepeating() }
            runCatching { created.abortCaptures() }
            requireNotNull(codec).signalEndOfInputStream()
            val eosDeadline = SystemClock.elapsedRealtime() + EOS_MILLIS
            var eos = false
            while (!eos && SystemClock.elapsedRealtime() < eosDeadline) {
                when (val index = requireNotNull(codec).dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        if (info.size > 0 && muxerStarted) {
                            requireNotNull(muxer).writeSampleData(track, requireNotNull(requireNotNull(codec).getOutputBuffer(index)), info)
                            frames++
                            bytes += info.size
                            firstPtsUs = firstPtsUs ?: info.presentationTimeUs
                            lastPtsUs = info.presentationTimeUs
                        }
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        requireNotNull(codec).releaseOutputBuffer(index, false)
                    }
                }
            }
            if (!muxerStarted || frames == 0 || bytes == 0L) {
                return unsupported("no-encoded-frames", "The HLG graph configured but produced no encoded frames.")
            }
            RecordingResult(
                status = "PASS",
                reasonCode = "hlg-graph-recorded",
                safeMessage = "Short HLG10/BT.2020 HEVC Main10 graph recorded; file qualification remains pending.",
                cameraId = camera.cameraId,
                codecName = codecCandidate.name,
                size = "${codecCandidate.size.width}x${codecCandidate.size.height}",
                fps = codecCandidate.fps,
                output = CLIP_NAME,
                outputBytes = outputFile.length(),
                frames = frames,
                muxerStarted = muxerStarted,
                encoderFormat = formatDescription,
                firstPtsUs = firstPtsUs,
                lastPtsUs = lastPtsUs,
            )
        } finally {
            runCatching { session?.close() }
            runCatching {
                device?.close()
                cameraClosedLatch.await(2, TimeUnit.SECONDS)
            }
            runCatching { inputSurface?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            executor.shutdownNow()
            thread.quitSafely()
        }
    }

    private fun findCamera(manager: CameraManager): CameraCandidate? = manager.cameraIdList.sorted().asSequence().mapNotNull { id ->
        val characteristics = manager.getCameraCharacteristics(id)
        val profiles = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        if (profiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) != true) return@mapNotNull null
        if (Build.VERSION.SDK_INT < 34) return@mapNotNull null
        val colors = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
            ?.getSupportedColorSpacesForDynamicRange(android.graphics.ImageFormat.PRIVATE, DynamicRangeProfiles.HLG10)
            .orEmpty()
        if (ColorSpace.Named.BT2020_HLG !in colors) return@mapNotNull null
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaCodec::class.java)
            ?.filter { it.width > 0 && it.height > 0 }
            ?.distinct()
            ?.sortedWith(compareBy<Size> { if (it.width == 1920 && it.height == 1080) 0 else 1 }
                .thenByDescending { it.width.toLong() * it.height })
            .orEmpty()
        if (sizes.isEmpty()) return@mapNotNull null
        val fps = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.firstOrNull { it.lower <= TARGET_FPS && it.upper >= TARGET_FPS }
            ?.let { Range(TARGET_FPS, TARGET_FPS) }
        CameraCandidate(id, sizes, fps)
    }.firstOrNull()

    private fun findCodec(sizes: List<Size>): CodecCandidate? = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        .asSequence()
        .filter { it.isEncoder && !it.isAlias && it.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) } }
        .sortedBy { it.name }
        .mapNotNull { info ->
            val capabilities = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull() ?: return@mapNotNull null
            if (!info.isHardwareAccelerated || MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in capabilities.colorFormats) {
                return@mapNotNull null
            }
            val profile = capabilities.profileLevels.firstOrNull { it.profile in MAIN10_PROFILES }?.profile
                ?: return@mapNotNull null
            val size = sizes.firstOrNull { candidate ->
                capabilities.videoCapabilities?.areSizeAndRateSupported(candidate.width, candidate.height, TARGET_FPS.toDouble()) == true
            } ?: return@mapNotNull null
            val format = candidateFormat(size, profile)
            if (!capabilities.isFormatSupported(format)) return@mapNotNull null
            val probeCodec = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull()
                ?: return@mapNotNull null
            val probeSucceeded = runCatching {
                probeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                probeCodec.createInputSurface().release()
                true
            }.getOrDefault(false)
            runCatching { probeCodec.release() }
            if (!probeSucceeded) return@mapNotNull null
            CodecCandidate(info.name, size, TARGET_FPS, profile, format)
        }
        .firstOrNull()

    private fun candidateFormat(size: Size, profile: Int) = MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
        setInteger(MediaFormat.KEY_PROFILE, profile)
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    }

    private fun unsupported(code: String, message: String) = RecordingResult("UNSUPPORTED", code, message)

    private fun RecordingResult.toJson(context: Context) = JSONObject()
        .put("schema", "opencinecam-hlg-recording-v1")
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
        .put("graph", JSONObject()
            .put("dynamicRange", "HLG10")
            .put("colorSpace", "BT.2020")
            .put("transfer", "HLG")
            .put("mime", MIME)
            .put("profile", "Main10")
            .put("input", "Surface"))
        .put("cameraId", cameraId ?: JSONObject.NULL)
        .put("codecName", codecName ?: JSONObject.NULL)
        .put("size", size ?: JSONObject.NULL)
        .put("fps", fps ?: JSONObject.NULL)
        .put("output", output ?: JSONObject.NULL)
        .put("outputBytes", outputBytes)
        .put("encodedFrames", frames)
        .put("muxerStarted", muxerStarted)
        .put("encoderFormat", encoderFormat ?: JSONObject.NULL)
        .put("firstPtsUs", firstPtsUs ?: JSONObject.NULL)
        .put("lastPtsUs", lastPtsUs ?: JSONObject.NULL)
        .put("qualification", "file-and-effective-precision-pending-occ-plan-036")

    private data class CameraCandidate(val cameraId: String, val privateSizes: List<Size>, val fpsRange: Range<Int>?)
    private data class CodecCandidate(val name: String, val size: Size, val fps: Int, val profile: Int, val format: MediaFormat)
    private data class RecordingResult(
        val status: String,
        val reasonCode: String,
        val safeMessage: String,
        val cameraId: String? = null,
        val codecName: String? = null,
        val size: String? = null,
        val fps: Int? = null,
        val output: String? = null,
        val outputBytes: Long = 0,
        val frames: Int = 0,
        val muxerStarted: Boolean = false,
        val encoderFormat: String? = null,
        val firstPtsUs: Long? = null,
        val lastPtsUs: Long? = null,
    )

    private companion object {
        const val TAG = "OCC_HLG_RECORD"
        const val OUTPUT_NAME = "occ-plan-035-hlg-recording.json"
        const val CLIP_NAME = "occ-plan-035-hlg-recording.mp4"
        const val LOG_PART_BYTES = 3000
        const val TIMEOUT_SECONDS = 12L
        const val RECORD_MILLIS = 2500L
        const val EOS_MILLIS = 5000L
        const val CODEC_TIMEOUT_US = 10_000L
        const val TARGET_FPS = 30
        const val MIME = MediaFormat.MIMETYPE_VIDEO_HEVC
        val MAIN10_PROFILES = setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        )
    }
}
