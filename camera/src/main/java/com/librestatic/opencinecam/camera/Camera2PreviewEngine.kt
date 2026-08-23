/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.ColorSpace
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.Image
import android.media.MediaRecorder
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.io.ByteArrayOutputStream
import kotlin.math.abs

data class Camera2VideoProfile(
    val size: Size,
    val fps: Int,
    val constrainedHighSpeed: Boolean,
)

data class Camera2LogProfile(
    val size: Size,
    val fps: Int,
    val sourcePath: OpenCineLogSourcePath,
    val constrainedHighSpeed: Boolean,
) {
    init {
        require(constrainedHighSpeed == sourcePath.highSpeedDerived) {
            "Only the explicitly disclosed ISP-derived LOG source may use constrained HFR."
        }
    }
}

data class Camera2CameraDescriptor(
    val cameraId: String,
    val lensFacing: Int,
    val focalLengthsMm: List<Float>,
    val previewSize: Size,
    val jpegSize: Size?,
    val rawSize: Size?,
    val analysisSize: Size?,
    val sensorOrientation: Int,
    val sensitivityRange: Range<Int>?,
    val exposureTimeRangeNs: Range<Long>?,
    val aeCompensationRange: Range<Int>?,
    val aeCompensationStep: Float,
    val minimumFocusDistance: Float?,
    val supportsRaw: Boolean,
    val flashAvailable: Boolean,
    val targetFpsRanges: List<Range<Int>>,
    val availableFixedFps: List<Int>,
    val videoProfiles: List<Camera2VideoProfile>,
    val logProfiles: List<Camera2LogProfile>,
    val sensorActiveArray: Rect? = null,
    val availableAfModes: List<Int> = emptyList(),
    val maxAfRegions: Int = 0,
    val maxAeRegions: Int = 0,
    val aeLockSupported: Boolean = false,
    val afLockSupported: Boolean = false,
    val zoomRatioRange: ClosedFloatingPointRange<Float>? = null,
    val supportsZoomRatioApi: Boolean = false,
    val digitalZoomMaxRatio: Float? = null,
    val opticalAnchors: List<ZoomAnchor> = emptyList(),
    val supportsHfrZoom: Boolean = false,
    val kelvinRange: IntRange? = null,
) {
    val supportsOpenCineLog: Boolean
        get() = logProfiles.isNotEmpty()

    /** Compatibility view for callers that have not selected an explicit LOG profile yet. */
    val preferredLogProfile: Camera2LogProfile?
        get() = logProfiles.firstOrNull {
            it.size.width == 1920 && it.size.height == 1080 &&
                it.fps == Camera2PreviewEngine.DEFAULT_TARGET_FPS &&
                it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020
        } ?: logProfiles.firstOrNull { it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020 }
            ?: logProfiles.firstOrNull()

    val logSize: Size?
        get() = preferredLogProfile?.size

    val logFixedFps: List<Int>
        get() = preferredLogProfile?.size?.let { preferredSize ->
            logProfiles.filter { it.size == preferredSize }.map { it.fps }.distinct().sorted()
        }.orEmpty()

    /** Advertised zoom range coerced to a valid ClosedFloatingPointRange, or null when zoom is unsupported. */
    val effectiveZoomRange: ClosedFloatingPointRange<Float>?
        get() = zoomRatioRange?.takeIf { it.start < it.endInclusive }

    /** True when the camera advertises any usable zoom (optical anchors or a digital range beyond 1x). */
    val zoomSupported: Boolean
        get() = effectiveZoomRange != null
}

enum class TapFocusState { IDLE, SEARCHING, FOCUSED, NOT_FOCUSED }
/**
 * State of an AE/AF lock toggle. OFF = automation running, PENDING = a focus-and-lock scan is in
 * progress, LOCKED = the automatism is frozen and reapplied to every repeating/still request.
 */
enum class LockState { OFF, PENDING, LOCKED }

/**
 * Behaviour of [Camera2PreviewEngine.setAfLock] when engaging the lock.
 * FREEZE_CURRENT captures the last effective focus distance and holds the lens in place.
 * FOCUS_AND_LOCK performs a one-shot AF scan at the last tap point (or center) and freezes once
 * focus is confirmed.
 */
enum class AfLockBehavior { FREEZE_CURRENT, FOCUS_AND_LOCK }

data class Camera2PreviewMetadata(
    val frameNumber: Long,
    val sensorTimestampNs: Long?,
    val sensitivityIso: Int?,
    val exposureTimeNs: Long?,
    val focusDistanceDiopters: Float?,
    val afState: Int?,
    val awbState: Int?,
    val colorTemperatureK: Int? = null,
    val effectiveFps: Double?,
)

data class Camera2Analysis(
    val histogram: List<Float>,
    val redHistogram: List<Float>,
    val greenHistogram: List<Float>,
    val blueHistogram: List<Float>,
    val zebraCells: List<Boolean>,
    val focusCells: List<Boolean>,
    val capturedAtElapsedRealtimeMs: Long,
    val columns: Int = 16,
    val rows: Int = 9,
)

data class Camera2EmbeddedAudioConfig(
    val source: Int,
    val sampleRateHz: Int,
    val channels: Int,
    val bitrateBps: Int,
    val preferredInputDeviceId: Int?,
    val enableAutomaticGainControl: Boolean = false,
    val onAudioLevel: ((AudioLevelSnapshot) -> Unit)? = null,
)

interface Camera2PreviewListener {
    fun onOpening(descriptor: Camera2CameraDescriptor)
    fun onPreviewStarted(descriptor: Camera2CameraDescriptor)
    fun onMetadata(metadata: Camera2PreviewMetadata)
    fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int)
    fun onDngCaptured(bytes: ByteArray, width: Int, height: Int)
    fun onAnalysis(analysis: Camera2Analysis)
    fun onRecordingStarted(width: Int, height: Int)
    fun onRecordingStopped(success: Boolean)
    fun onFailure(code: String, message: String, recoverable: Boolean)
    fun onTapFocusState(state: TapFocusState) = Unit
    fun onZoomRangeAvailable(min: Float, max: Float, anchors: List<ZoomAnchor>) = Unit
    fun onZoomEffective(ratio: Float) = Unit
    fun onZoomRejected(requested: Float, accepted: Float) = Unit
    fun onAeLockChanged(active: Boolean) = Unit
    fun onAfLockChanged(state: LockState) = Unit
    fun onFocusPullFinished() = Unit
    fun onFocusPullStarted(targetDiopters: Float) = Unit
    fun onFocusPullCancelled() = Unit
}

/**
 * Concrete public-API Camera2 preview/still owner. Calls are serialized on one executor and
 * stale callbacks are rejected by generation so a destroyed Surface cannot regain ownership.
 */

private class CloseTolerantCameraExecutor : Executor, AutoCloseable {
    private val delegate = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenCineCamCamera").apply { isDaemon = false }
    }
    @Volatile private var accepting = true

    override fun execute(command: Runnable) {
        if (!accepting) return
        try {
            delegate.execute {
                if (accepting) command.run()
            }
        } catch (_: RejectedExecutionException) {
            // CameraDeviceImpl may deliver late onClosed callbacks after CameraDevice.close().
            // Dropping those callbacks is safer than letting Executor rejection crash the app.
        }
    }

    override fun close() {
        accepting = false
        delegate.shutdown()
    }
}

private data class TapFocusRequestTag(val token: Long)

class Camera2PreviewEngine(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val appContext = context.applicationContext
    private val cameraExecutor = CloseTolerantCameraExecutor()
    private val imageThread = HandlerThread("OpenCineCamImage").apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    private var generation = 0L
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var analysisReader: ImageReader? = null
    private var pendingRawImage: Image? = null
    private var pendingRawResult: TotalCaptureResult? = null
    private var repeatingBuilder: CaptureRequest.Builder? = null
    private var recorder: MediaRecorder? = null
    private var recordSurface: Surface? = null
    private var recording = false
    private var recordingSessionGeneration = 0L
    private var activeDescriptor: Camera2CameraDescriptor? = null
    private var listener: Camera2PreviewListener? = null
    private var lastMetadataAtMs = 0L
    private var lastAnalysisAtMs = 0L
    private var displayRotationDegrees = 0
    private var requestedIso: Int? = null
    private var requestedExposureNs: Long? = null
    private var requestedFocusDiopters: Float? = null
    private var requestedWhiteBalance: WhiteBalanceSelection = WhiteBalanceSelection.Auto
    private var requestedAeCompensationIndex: Int? = null
    private var requestedTorchEnabled = false
    private var aeLockActive = false
    private var afLockState = LockState.OFF
    private var afLockFrozenDiopters: Float? = null
    private var afLockScanToken: Long? = null
    private var afLockResultReported = false
    private var lastTapFocusForAfLock: MeteringRectangle? = null
    private var tapFocusGeneration = 0L
    private var activeTapFocusToken: Long? = null
    private var tapAfRegion: MeteringRectangle? = null
    private var tapAeRegion: MeteringRectangle? = null
    private var tapFocusResultReported = false
    private var requestedZoomRatio: Float = 1f
    private var lastAcceptedZoomRatio: Float = 1f
    private var effectiveZoomRatio: Float = 1f
    private var lastZoomReportedAtMs = 0L
    private val restoreTapFocus = Runnable {
        cameraExecutor.execute { restoreContinuousFocusLocked() }
    }
    private var logPipeline: OpenCineLogGpuPipeline? = null
    private var passthroughVideoPipeline = false
    private var logPreviewEnabled = false
    private var logViewAssistEnabled = false
    private var requestedTargetFps = DEFAULT_TARGET_FPS
    private var activeVideoProfile: Camera2VideoProfile? = null
    private var activeLogProfile: Camera2LogProfile? = null
    private var previousSensorTimestampNs: Long? = null
    private var effectiveFps: Double? = null
    private var lastReportedFocusDiopters: Float? = null
    private val focusPullAnimator = FocusPullAnimator()
    private val focusPullCallback = object : Runnable {
        override fun run() {
            if (!focusPullAnimator.isActive) return
            val now = android.os.SystemClock.elapsedRealtime()
            val diopters = focusPullAnimator.tick(now)
            if (diopters != null) {
                requestedFocusDiopters = diopters
                reapplyRepeating()
            }
            if (focusPullAnimator.isComplete(now)) {
                focusPullAnimator.complete()
                listener?.onFocusPullFinished()
            } else {
                imageHandler.postDelayed(this, FOCUS_PULL_TICK_MS)
            }
        }
    }
    private val perCameraFocusMarks = mutableMapOf<String, Map<String, Float>>()
    private val avcSurfaceEncoderCapabilities by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && !it.isAlias }
            .mapNotNull { info -> runCatching { info.getCapabilitiesForType("video/avc") }.getOrNull() }
            .filter { it.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) }
            .mapNotNull { it.videoCapabilities }
            .toList()
    }
    @Volatile private var lastLogRecordingEvidence: OpenCineLogRecordingEvidence? = null

    fun descriptors(targetWidth: Int, targetHeight: Int): List<Camera2CameraDescriptor> =
        manager.cameraIdList.mapNotNull { cameraId -> descriptor(cameraId, targetWidth, targetHeight) }
            .sortedWith(compareBy<Camera2CameraDescriptor> { lensOrder(it.lensFacing) }.thenBy { it.cameraId })

    fun preferredDescriptor(targetWidth: Int, targetHeight: Int): Camera2CameraDescriptor? =
        descriptors(targetWidth, targetHeight).firstOrNull()

    @SuppressLint("MissingPermission")
    fun startPreview(
        descriptor: Camera2CameraDescriptor,
        surface: Surface,
        displayRotationDegrees: Int,
        listener: Camera2PreviewListener,
        openCineLog: Boolean = false,
        viewAssist: Boolean = false,
        targetFps: Int = DEFAULT_TARGET_FPS,
        videoProfile: Camera2VideoProfile? = null,
        logProfile: Camera2LogProfile? = null,
    ) {
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onFailure("camera-permission-denied", "Camera permission is required.", true)
            return
        }
        cameraExecutor.execute {
            val currentGeneration = ++generation
            closeResources()
            this.listener = listener
            this.previewSurface = surface
            this.activeDescriptor = descriptor
            this.displayRotationDegrees = displayRotationDegrees
            this.logPreviewEnabled = openCineLog
            this.logViewAssistEnabled = viewAssist
            this.activeVideoProfile = videoProfile?.takeIf { it in descriptor.videoProfiles }
            this.activeLogProfile = if (openCineLog) {
                logProfile?.takeIf { it in descriptor.logProfiles }
                    ?: preferredLogProfile(descriptor.logProfiles, targetFps)
            } else null
            val supportedFps = activeLogProfile?.let { listOf(it.fps) }
                ?: activeVideoProfile?.let { listOf(it.fps) }
                ?: descriptor.availableFixedFps
            this.requestedTargetFps = targetFps.takeIf { it in supportedFps }
                ?: preferredTargetFps(supportedFps)
            this.previousSensorTimestampNs = null
            this.effectiveFps = null
            listener.onOpening(descriptor)
            if (openCineLog) {
                val selectedLogProfile = activeLogProfile
                if (!descriptor.supportsOpenCineLog || selectedLogProfile == null) {
                    listener.onFailure("log-capability-unavailable", "This camera has no capability-backed OCLog profile.", false)
                    return@execute
                }
                try {
                    logPipeline = OpenCineLogGpuPipeline(
                        selectedLogProfile.size,
                        selectedLogProfile.sourcePath,
                        surface,
                        descriptor.sensorOrientation,
                        displayRotationDegrees,
                        descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
                        viewAssist,
                        requestedTargetFps,
                        appContext = appContext,
                        onAnalysis = listener::onAnalysis,
                    ) { code, message -> listener.onFailure(code, message, false) }
                } catch (failure: Throwable) {
                    listener.onFailure("log-gpu-init-failed", failure.cause?.message ?: failure.message ?: "The OCLog GPU path could not start.", false)
                    return@execute
                }
            }
            try {
                val highSpeedProfile = activeLogProfile?.takeIf { it.constrainedHighSpeed }?.let {
                    Camera2VideoProfile(it.size, it.fps, true)
                } ?: activeVideoProfile?.takeIf { it.constrainedHighSpeed }
                val cameraIdToOpen = if (highSpeedProfile != null) {
                    if (Build.MANUFACTURER.equals("motorola", ignoreCase = true)) {
                        preferredHighSpeedPhysicalId(descriptor, highSpeedProfile)
                            ?: descriptor.cameraId
                    } else descriptor.cameraId
                } else descriptor.cameraId
                manager.openCamera(cameraIdToOpen, cameraExecutor, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (currentGeneration != generation) {
                            device.close()
                            return
                        }
                        camera = device
                        if (openCineLog) configureLogSession(device, descriptor, currentGeneration)
                        else if (activeVideoProfile?.constrainedHighSpeed == true) {
                            configureHighSpeedPreviewSession(
                                device,
                                descriptor,
                                surface,
                                currentGeneration,
                            )
                        } else configureSession(device, descriptor, surface, currentGeneration)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        if (currentGeneration == generation) {
                            camera = null
                            listener.onFailure("camera-disconnected", "The selected camera disconnected.", true)
                        }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        if (currentGeneration == generation) {
                            camera = null
                            listener.onFailure("camera-open-$error", "The selected camera could not be opened.", true)
                        }
                    }
                })
            } catch (failure: Exception) {
                listener.onFailure("camera-open-exception", failure.message ?: "Camera open failed.", true)
            }
        }
    }

    fun captureJpeg(): Boolean {
        val device = camera ?: return false
        val currentSession = session ?: return false
        val reader = jpegReader ?: return false
        val descriptor = activeDescriptor ?: return false
        cameraExecutor.execute {
            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(descriptor))
                    applyManualControls(this)
                }
                currentSession.captureSingleRequest(builder.build(), cameraExecutor, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        listener?.onFailure("jpeg-capture-failed", "JPEG capture failed (${failure.reason}).", true)
                    }
                })
            } catch (failure: Exception) {
                listener?.onFailure("jpeg-capture-exception", failure.message ?: "JPEG capture failed.", true)
            }
        }
        return true
    }

    fun captureDng(): Boolean {
        val device = camera ?: return false
        val currentSession = session ?: return false
        val reader = rawReader ?: return false
        if (pendingRawImage != null || pendingRawResult != null) return false
        cameraExecutor.execute {
            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                    set(CaptureRequest.JPEG_ORIENTATION, activeDescriptor?.let(::jpegOrientation) ?: 0)
                    applyManualControls(this)
                }
                currentSession.captureSingleRequest(builder.build(), cameraExecutor, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        pendingRawResult = result
                        emitDngIfReady()
                    }
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        clearPendingRaw()
                        listener?.onFailure("raw-capture-failed", "RAW still capture failed (${failure.reason}).", true)
                    }
                })
            } catch (failure: Exception) {
                clearPendingRaw()
                listener?.onFailure("raw-capture-exception", failure.message ?: "RAW still capture failed.", true)
            }
        }
        return true
    }

    fun captureBracket(): Boolean {
        val device = camera ?: return false
        val currentSession = session ?: return false
        val reader = jpegReader ?: return false
        val descriptor = activeDescriptor ?: return false
        cameraExecutor.execute {
            try {
                val step = descriptor.aeCompensationStep.takeIf { it > 0f } ?: 1f
                val indices = listOf(-2f, 0f, 2f).map { ev ->
                    val raw = (ev / step).toInt()
                    descriptor.aeCompensationRange?.let { raw.coerceIn(it.lower, it.upper) } ?: raw
                }
                val requests = indices.map { compensation ->
                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
                        set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(descriptor))
                    }.build()
                }
                currentSession.captureBurstRequests(requests, cameraExecutor, object : CameraCaptureSession.CaptureCallback() {})
            } catch (failure: Exception) {
                listener?.onFailure("bracket-capture-failed", failure.message ?: "Exposure bracket failed.", true)
            }
        }
        return true
    }

    fun captureBurst(count: Int): Boolean {
        val device = camera ?: return false
        val currentSession = session ?: return false
        val reader = jpegReader ?: return false
        val descriptor = activeDescriptor ?: return false
        val boundedCount = count.coerceIn(3, MAX_BURST_IMAGES)
        cameraExecutor.execute {
            try {
                val requests = List(boundedCount) {
                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                        set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(descriptor))
                        applyManualControls(this)
                    }.build()
                }
                currentSession.captureBurstRequests(requests, cameraExecutor, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        listener?.onFailure("burst-capture-failed", "Burst capture failed (${failure.reason}).", true)
                    }
                })
            } catch (failure: Exception) {
                listener?.onFailure("burst-capture-exception", failure.message ?: "Burst capture failed.", true)
            }
        }
        return true
    }

    fun captureLongExposure(): Boolean {
        val device = camera ?: return false
        val currentSession = session ?: return false
        val reader = jpegReader ?: return false
        val descriptor = activeDescriptor ?: return false
        cameraExecutor.execute {
            try {
                val exposure = descriptor.exposureTimeRangeNs?.let { 1_000_000_000L.coerceIn(it.lower, it.upper) } ?: 1_000_000_000L
                val iso = descriptor.sensitivityRange?.lower ?: 100
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(descriptor))
                }.build()
                currentSession.captureSingleRequest(request, cameraExecutor, object : CameraCaptureSession.CaptureCallback() {})
            } catch (failure: Exception) {
                listener?.onFailure("long-exposure-failed", failure.message ?: "Long exposure failed.", true)
            }
        }
        return true
    }

    fun setManualControls(iso: Int?, exposureTimeNs: Long?, focusDiopters: Float?) {
        cameraExecutor.execute {
            // Manual focus override cancels any active focus pull.
            if (focusDiopters != null) {
                imageHandler.removeCallbacks(focusPullCallback)
                focusPullAnimator.cancel()
            }
            val descriptor = activeDescriptor
            requestedIso = iso?.let { value -> descriptor?.sensitivityRange?.let { value.coerceIn(it.lower, it.upper) } ?: value }
            requestedExposureNs = exposureTimeNs?.let { value -> descriptor?.exposureTimeRangeNs?.let { value.coerceIn(it.lower, it.upper) } ?: value }
            requestedFocusDiopters = focusDiopters?.let { value -> value.coerceIn(0f, descriptor?.minimumFocusDistance ?: value) }
            clearTapFocusLocked(notify = true)
            // Locks are mutually exclusive with manual controls: engaging manual exposure
            // releases AE lock, and engaging manual focus releases AF lock.
            if ((requestedIso != null && requestedExposureNs != null) && aeLockActive) {
                aeLockActive = false
                listener?.onAeLockChanged(false)
            }
            if (requestedFocusDiopters != null && afLockState != LockState.OFF) {
                disableAfLock(notify = true)
            }
            val builder = repeatingBuilder ?: return@execute
            val configured = session ?: return@execute
            applyManualControls(builder)
            runCatching {
                configured.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
            }.onFailure { listener?.onFailure("manual-control-failed", it.message ?: "Manual control update failed.", true) }
        }
    }

    /**
     * Saves a focus mark for the active camera. Up to 4 marks (A-D) are remembered per camera
     * for the lifetime of this engine. Returns false when no camera is active or the
     * distance is invalid.
     */
    fun setFocusMark(label: String, diopters: Float): Boolean {
        val cameraId = activeDescriptor?.cameraId ?: return false
        val minDistance = activeDescriptor?.minimumFocusDistance ?: return false
        if (minDistance <= 0f) return false
        val clamped = diopters.coerceIn(0f, minDistance)
        val marks = perCameraFocusMarks[cameraId] ?: emptyMap()
        if (marks.size >= 4 && label !in marks) return false
        perCameraFocusMarks[cameraId] = marks + (label to clamped)
        return true
    }

    /** Removes a single focus mark. Returns false when the mark does not exist. */
    fun clearFocusMark(label: String): Boolean {
        val cameraId = activeDescriptor?.cameraId ?: return false
        val marks = perCameraFocusMarks[cameraId] ?: return false
        if (label !in marks) return false
        perCameraFocusMarks[cameraId] = marks - label
        return true
    }

    /** Returns the focus marks saved for the active camera, or an empty map when none exist. */
    fun getFocusMarks(): Map<String, Float> {
        val cameraId = activeDescriptor?.cameraId ?: return emptyMap()
        return perCameraFocusMarks[cameraId] ?: emptyMap()
    }

    /**
     * Starts an animated focus pull from the current focus distance to [toDiopters] over
     * [durationMs] with the given [easing]. Returns false when manual focus is unsupported,
     * the session is constrained high-speed, or no camera is active.
     */
    fun startFocusPull(toDiopters: Float, durationMs: Long, easing: FocusPullEasing): Boolean {
        val descriptor = activeDescriptor ?: return false
        val minDistance = descriptor.minimumFocusDistance ?: return false
        if (minDistance <= 0f || isHighSpeedSession()) return false
        val to = toDiopters.coerceIn(0f, minDistance)
        val from = requestedFocusDiopters ?: lastReportedFocusDiopters ?: 0f
        cameraExecutor.execute {
            // Cancel any active tap-to-focus or AF lock before starting the pull.
            if (activeTapFocusToken != null) {
                clearTapFocusLocked(notify = true)
            }
            if (afLockState != LockState.OFF) {
                disableAfLock(notify = true)
            }
            imageHandler.removeCallbacks(focusPullCallback)
            focusPullAnimator.start(
                FocusPullPlan(
                    fromDiopters = from,
                    toDiopters = to,
                    durationMs = durationMs,
                    easing = easing,
                ),
                android.os.SystemClock.elapsedRealtime(),
            )
            listener?.onFocusPullStarted(to)
            imageHandler.post(focusPullCallback)
        }
        return true
    }

    /** Cancels any active focus pull and clears the animator state. */
    fun cancelFocusPull() {
        cameraExecutor.execute {
            imageHandler.removeCallbacks(focusPullCallback)
            focusPullAnimator.cancel()
            listener?.onFocusPullCancelled()
        }
    }

    /**
     * Starts point autofocus using coordinates normalized to the displayed preview.
     * Returns false without enqueuing work when the active graph cannot truthfully support it.
     */
    fun tapToFocus(normalizedX: Float, normalizedY: Float, meterExposure: Boolean): Boolean {
        val descriptor = activeDescriptor ?: return false
        val configured = session ?: return false
        // Cancel any active focus pull before starting tap-to-focus.
        imageHandler.removeCallbacks(focusPullCallback)
        focusPullAnimator.cancel()
        if (configured is CameraConstrainedHighSpeedCaptureSession ||
            descriptor.sensorActiveArray == null || descriptor.maxAfRegions <= 0 ||
            CaptureRequest.CONTROL_AF_MODE_AUTO !in descriptor.availableAfModes
        ) return false

        cameraExecutor.execute {
            val currentDescriptor = activeDescriptor ?: return@execute
            val currentSession = session ?: return@execute
            val builder = repeatingBuilder ?: return@execute
            if (currentSession is CameraConstrainedHighSpeedCaptureSession) return@execute
            // AF lock freezes the lens; reject tap-to-focus while locked so the lock survives.
            if (afLockState == LockState.LOCKED) return@execute
            val active = currentDescriptor.sensorActiveArray ?: return@execute
            val stream = activeLogProfile?.size ?: activeVideoProfile?.size ?: currentDescriptor.previewSize
            // When the API 29 crop-region fallback is active, map the tap into the visible crop
            // so the focus point matches the zoomed viewfinder. With CONTROL_ZOOM_RATIO (API 30+)
            // the HAL applies the crop itself, so we map into the full active array as before.
            val zoomCrop: SensorBounds? = if (!currentDescriptor.supportsZoomRatioApi &&
                currentDescriptor.zoomSupported && requestedZoomRatio > 1f) {
                ZoomMath.cropRegionFor(active, requestedZoomRatio)?.let { rect ->
                    SensorBounds(rect.left, rect.top, rect.right, rect.bottom)
                }
            } else null
            val area = FocusMeteringMapper.map(
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                activeArray = SensorBounds(active.left, active.top, active.right, active.bottom),
                streamWidth = stream.width,
                streamHeight = stream.height,
                sensorOrientationDegrees = currentDescriptor.sensorOrientation,
                displayRotationDegrees = displayRotationDegrees,
                frontFacing = currentDescriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
                cropRegion = zoomCrop,
            )
            val metering = MeteringRectangle(
                Rect(area.left, area.top, area.right, area.bottom),
                MeteringRectangle.METERING_WEIGHT_MAX,
            )
            lastTapFocusForAfLock = metering
            imageHandler.removeCallbacks(restoreTapFocus)
            val token = ++tapFocusGeneration
            activeTapFocusToken = token
            tapAfRegion = metering
            tapAeRegion = metering.takeIf {
                meterExposure && currentDescriptor.maxAeRegions > 0 && !aeLockActive &&
                    (requestedIso == null || requestedExposureNs == null)
            }
            tapFocusResultReported = false
            requestedFocusDiopters = null
            listener?.onTapFocusState(TapFocusState.SEARCHING)
            try {
                builder.setTag(TapFocusRequestTag(token))
                applyManualControls(builder)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                currentSession.captureSingleRequest(builder.build(), cameraExecutor, previewCaptureCallback(currentDescriptor, false))
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                currentSession.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(currentDescriptor, false))
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                currentSession.captureSingleRequest(builder.build(), cameraExecutor, previewCaptureCallback(currentDescriptor, false))
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                currentSession.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(currentDescriptor, false))
                imageHandler.postDelayed(restoreTapFocus, TAP_FOCUS_HOLD_MS)
            } catch (failure: Exception) {
                clearTapFocusLocked(notify = true)
                listener?.onFailure("tap-focus-failed", failure.message ?: "Point autofocus failed.", true)
            }
        }
        return true
    }

    fun setWhiteBalance(selection: WhiteBalanceSelection) {
        cameraExecutor.execute {
            requestedWhiteBalance = selection
            val builder = repeatingBuilder ?: return@execute
            val configured = session ?: return@execute
            applyManualControls(builder)
            runCatching { configured.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false)) }
                .onFailure { listener?.onFailure("white-balance-failed", it.message ?: "White balance update failed.", true) }
        }
    }

    /**
     * Sets the discrete AE exposure compensation index reported by the active camera's
     * CONTROL_AE_COMPENSATION_RANGE. Passing null clears the request so the camera uses its
     * default metering. When the device advertises no compensation range the call is ignored.
     */
    fun setExposureCompensation(index: Int?) {
        cameraExecutor.execute {
            val descriptor = activeDescriptor ?: return@execute
            requestedAeCompensationIndex = descriptor.aeCompensationRange?.let { range ->
                index?.coerceIn(range.lower, range.upper)
            }
            val builder = repeatingBuilder ?: return@execute
            val configured = session ?: return@execute
            applyManualControls(builder)
            runCatching { configured.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false)) }
                .onFailure { listener?.onFailure("exposure-compensation-failed", it.message ?: "Exposure compensation update failed.", true) }
        }
    }

    fun setTorchEnabled(enabled: Boolean) {
        cameraExecutor.execute {
            requestedTorchEnabled = enabled && activeDescriptor?.flashAvailable == true
            val builder = repeatingBuilder ?: return@execute
            val configured = session ?: return@execute
            applyManualControls(builder)
            runCatching { configured.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false)) }
                .onFailure { listener?.onFailure("flash-control-failed", it.message ?: "Flash control update failed.", true) }
        }
    }

    /**
     * Toggles AE lock. When [enabled] the active AE algorithm freezes its current exposure and
     * that lock is reapplied to every repeating, still, and recording request. Manual exposure
     * overrides (ISO/shutter) and constrained high-speed sessions are incompatible with the lock
     * and leave it inactive.
     */
    fun setAeLock(enabled: Boolean) {
        cameraExecutor.execute {
            val descriptor = activeDescriptor ?: return@execute
            val desired = enabled && descriptor.aeLockSupported && requestedIso == null &&
                requestedExposureNs == null && !isHighSpeedSession()
            if (desired == aeLockActive) return@execute
            aeLockActive = desired
            listener?.onAeLockChanged(desired)
            val builder = repeatingBuilder ?: return@execute
            val configured = session ?: return@execute
            applyManualControls(builder)
            runCatching { configured.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false)) }
                .onFailure {
                    aeLockActive = false
                    listener?.onAeLockChanged(false)
                    listener?.onFailure("ae-lock-failed", it.message ?: "AE lock update failed.", true)
                }
        }
    }

    /**
     * Toggles AF lock. With [AfLockBehavior.FREEZE_CURRENT] the engine takes the last effective
     * focus distance and switches AF to OFF so the lens holds position. With
     * [AfLockBehavior.FOCUS_AND_LOCK] it performs a one-shot AF scan at the last tap point (or
     * center) and freezes the distance once focus is confirmed. Constrained high-speed sessions,
     * manual focus, and an active tap-to-focus scan are incompatible and leave the lock inactive.
     */
    fun setAfLock(enabled: Boolean, behavior: AfLockBehavior) {
        cameraExecutor.execute {
            val descriptor = activeDescriptor ?: return@execute
            // AF lock and focus pull are mutually exclusive.
            imageHandler.removeCallbacks(focusPullCallback)
            focusPullAnimator.cancel()
            if (!enabled) {
                disableAfLock(notify = true)
                reapplyRepeating()
                return@execute
            }
            if (!descriptor.afLockSupported || isHighSpeedSession()) {
                listener?.onFailure("af-lock-unsupported", "AF lock is not supported in this session.", true)
                return@execute
            }
            if (requestedFocusDiopters != null || activeTapFocusToken != null) {
                listener?.onFailure("af-lock-conflict", "AF lock is incompatible with manual or tap focus.", true)
                return@execute
            }
            when (behavior) {
                AfLockBehavior.FREEZE_CURRENT -> {
                    val frozen = lastReportedFocusDiopters
                        ?: descriptor.minimumFocusDistance?.takeIf { it > 0f }?.let { 0f }
                    if (frozen == null) {
                        listener?.onFailure("af-lock-no-distance", "No focus distance to freeze yet.", true)
                        return@execute
                    }
                    afLockFrozenDiopters = frozen
                    afLockState = LockState.LOCKED
                    afLockScanToken = null
                    afLockResultReported = false
                    listener?.onAfLockChanged(LockState.LOCKED)
                    reapplyRepeating()
                }
                AfLockBehavior.FOCUS_AND_LOCK -> startAfLockScan(descriptor)
            }
        }
    }

    /**
     * Applies the requested zoom ratio to the active repeating request. Returns false when no
     * descriptor/session is active or the descriptor advertises no zoom range; in those cases the
     * internal state is left untouched. Coerces [ratio] into the active descriptor's range.
     */
    fun setZoomRatio(ratio: Float): Boolean {
        val descriptor = activeDescriptor ?: return false
        val range = descriptor.effectiveZoomRange ?: return false
        cameraExecutor.execute {
            requestedZoomRatio = ZoomMath.coerce(ratio, range)
            val builder = repeatingBuilder ?: return@execute
            val configured = session ?: return@execute
            reissueRepeating(builder, configured, descriptor)
        }
        return true
    }

    /** Current effective (last-accepted) zoom ratio, or 1f when no session is active. */
    fun currentEffectiveZoomRatio(): Float = effectiveZoomRatio

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val descriptor = activeDescriptor ?: return
        val range = descriptor.effectiveZoomRange ?: return
        val ratio = requestedZoomRatio
        if (ratio <= range.start && range.start >= 1f) {
            // At or below the wide end: no crop needed.
            if (descriptor.supportsZoomRatioApi) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, range.start)
            } else {
                builder.set(CaptureRequest.SCALER_CROP_REGION, null)
            }
            return
        }
        if (descriptor.supportsZoomRatioApi) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
        } else {
            // API 29 fallback: only digital zoom >= 1x is representable via SCALER_CROP_REGION.
            val cropRatio = ratio.coerceAtLeast(1f)
            builder.set(CaptureRequest.SCALER_CROP_REGION, ZoomMath.cropRegionFor(descriptor.sensorActiveArray, cropRatio))
        }
    }

    private fun reissueRepeating(
        builder: CaptureRequest.Builder,
        configured: CameraCaptureSession,
        descriptor: Camera2CameraDescriptor,
    ) {
        applyManualControls(builder)
        try {
            configured.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
            lastAcceptedZoomRatio = requestedZoomRatio
        } catch (failure: Throwable) {
            if (isZoomRejection(failure)) {
                requestedZoomRatio = lastAcceptedZoomRatio
                listener?.onZoomRejected(requestedZoomRatio, lastAcceptedZoomRatio)
            } else {
                listener?.onFailure("zoom-request-failed", failure.message ?: "Zoom update failed.", true)
            }
        }
    }

    private fun isZoomRejection(failure: Throwable): Boolean {
        val message = failure.message.orEmpty()
        return message.contains("SCALER_CROP_REGION", ignoreCase = true) ||
            message.contains("CONTROL_ZOOM_RATIO", ignoreCase = true) ||
            message.contains("zoom", ignoreCase = true)
    }

    private fun reportEffectiveZoom(result: TotalCaptureResult) {
        val descriptor = activeDescriptor ?: return
        val range = descriptor.effectiveZoomRange ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastZoomReportedAtMs < ZOOM_REPORT_PERIOD_MS) return
        lastZoomReportedAtMs = now
        val ratio = if (descriptor.supportsZoomRatioApi) {
            result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
        } else {
            ZoomMath.ratioFromCropRegion(
                descriptor.sensorActiveArray,
                result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION),
            )
        } ?: return
        val coerced = ZoomMath.coerce(ratio, range)
        effectiveZoomRatio = coerced
        listener?.onZoomEffective(coerced)
    }

    private fun notifyZoomRange() {
        val descriptor = activeDescriptor ?: return
        val range = descriptor.effectiveZoomRange ?: return
        listener?.onZoomRangeAvailable(range.start, range.endInclusive, descriptor.opticalAnchors)
    }

    fun startVideo(
        output: ParcelFileDescriptor,
        audio: Camera2EmbeddedAudioConfig?,
        recordingGeometry: RecordingGeometry? = null,
        captureRate: Double? = null,
        videoBitrate: Int = 20_000_000,
    ): Boolean {
        if (recordingGeometry != null && captureRate == null) {
            return startGpuVideo(output, audio, recordingGeometry, videoBitrate)
        }
        val device = camera ?: return false
        val surface = previewSurface ?: return false
        val descriptor = activeDescriptor ?: return false
        if (recording) return false
        cameraExecutor.execute {
            try {
                session?.stopRepeating()
                session?.close()
                session = null
                jpegReader?.close()
                jpegReader = null
                rawReader?.close()
                rawReader = null
                clearPendingRaw()
                analysisReader?.close()
                analysisReader = null
                val size = activeVideoProfile?.size ?: descriptor.previewSize
                @Suppress("DEPRECATION")
                val configuredRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(appContext)
                } else {
                    MediaRecorder()
                }).apply {
                    if (audio != null) setAudioSource(audio.source)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    if (audio != null) {
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioChannels(audio.channels)
                        setAudioEncodingBitRate(audio.bitrateBps)
                        setAudioSamplingRate(audio.sampleRateHz)
                        audio.preferredInputDeviceId?.let { requestedId ->
                            val input = appContext.getSystemService(AudioManager::class.java)
                                .getDevices(AudioManager.GET_DEVICES_INPUTS)
                                .firstOrNull { it.id == requestedId }
                                ?: error("Requested audio input $requestedId is no longer connected.")
                            check(setPreferredDevice(input)) { "MediaRecorder rejected audio input $requestedId." }
                        }
                    }
                    setVideoSize(size.width, size.height)
                    setVideoFrameRate(requestedTargetFps)
                    captureRate?.let { setCaptureRate(it) }
                    val rateScaledBitrate = videoBitrate.toLong() * requestedTargetFps / DEFAULT_TARGET_FPS
                    setVideoEncodingBitRate(rateScaledBitrate.coerceIn(8_000_000L, 200_000_000L).toInt())
                    setOrientationHint(recordingGeometry?.containerRotationDegrees ?: jpegOrientation(descriptor))
                    setOutputFile(output.fileDescriptor)
                    prepare()
                }
                recorder = configuredRecorder
                recordSurface = configuredRecorder.surface
                configureRecordingSession(device, descriptor, surface, requireNotNull(recordSurface), startRecorder = true)
            } catch (failure: Exception) {
                releaseRecorder()
                listener?.onFailure("video-prepare-failed", failure.message ?: "Video recording could not be prepared.", true)
            }
        }
        return true
    }

    /** Records normal SDR video through the same one-generation GPU geometry path as LOG. */
    private fun startGpuVideo(
        output: ParcelFileDescriptor,
        audio: Camera2EmbeddedAudioConfig?,
        recordingGeometry: RecordingGeometry,
        videoBitrate: Int,
    ): Boolean {
        val device = camera ?: return false
        val preview = previewSurface ?: return false
        val descriptor = activeDescriptor ?: return false
        if (recording || logPipeline != null) return false
        cameraExecutor.execute {
            try {
                session?.stopRepeating()
                session?.close()
                session = null
                jpegReader?.close()
                jpegReader = null
                rawReader?.close()
                rawReader = null
                clearPendingRaw()
                analysisReader?.close()
                analysisReader = null
                val pipeline = OpenCineLogGpuPipeline(
                    size = Size(recordingGeometry.sourceSize.width, recordingGeometry.sourceSize.height),
                    sourcePath = OpenCineLogSourcePath.SDR_BT709_ISP,
                    // The direct Camera2 preview Surface can remain connected to the camera
                    // producer briefly after its session closes. Connecting EGL to that same
                    // Surface races with the OEM disconnect and fails with EGL_BAD_ALLOC. Keep
                    // the last viewfinder frame during the one-generation GPU recording and
                    // restore the direct preview after finalization instead.
                    preview = null,
                    sensorOrientationDegrees = descriptor.sensorOrientation,
                    displayRotationDegrees = displayRotationDegrees,
                    frontFacing = descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
                    viewAssist = false,
                    targetFps = requestedTargetFps,
                    passthroughSdr = true,
                    appContext = appContext,
                    onAnalysis = { listener?.onAnalysis(it) },
                ) { code, message -> listener?.onFailure(code, message, true) }
                logPipeline = pipeline
                passthroughVideoPipeline = true
                val accepted = pipeline.startRecording(
                    output = output,
                    bitrate = videoBitrate,
                    geometry = recordingGeometry,
                    audio = audio,
                    onStarted = { recording = true },
                    onStopped = { success, _ ->
                        recording = false
                        cameraExecutor.execute {
                            if (logPipeline === pipeline) {
                                pipeline.close()
                                logPipeline = null
                                passthroughVideoPipeline = false
                                previewSurface?.takeIf { it.isValid }?.let { surface ->
                                    camera?.let { activeCamera ->
                                        if (activeVideoProfile?.constrainedHighSpeed == true) {
                                            configureHighSpeedPreviewSession(activeCamera, descriptor, surface, generation)
                                        } else {
                                            configureSession(activeCamera, descriptor, surface, generation)
                                        }
                                    }
                                }
                            }
                        }
                        listener?.onRecordingStopped(success)
                        if (!success) listener?.onFailure(
                            "video-recording-finalize-failed",
                            "GPU video recording could not be finalized.",
                            true,
                        )
                    },
                )
                if (!accepted) {
                    pipeline.close()
                    logPipeline = null
                    passthroughVideoPipeline = false
                    return@execute
                }
                configureGpuInputSession(device, descriptor, pipeline, recordingGeometry)
            } catch (failure: Throwable) {
                logPipeline?.takeIf { passthroughVideoPipeline }?.let { runCatching { it.close() } }
                logPipeline = null
                passthroughVideoPipeline = false
                recording = false
                listener?.onFailure("video-gpu-prepare-failed", failure.message ?: "GPU video recording could not be prepared.", true)
            }
        }
        return true
    }

    /** Starts the already-running GPU OCLog graph without rebuilding the Camera2 session. */
    fun startOpenCineLogVideo(
        output: ParcelFileDescriptor,
        recordingGeometry: RecordingGeometry,
        videoBitrate: Int = 20_000_000,
        audio: Camera2EmbeddedAudioConfig? = null,
    ): Boolean {
        val pipeline = logPipeline ?: return false
        val descriptor = activeDescriptor ?: return false
        if (!logPreviewEnabled || recording) return false
        lastLogRecordingEvidence = null
        val accepted = pipeline.startRecording(
            output = output,
            bitrate = videoBitrate,
            geometry = recordingGeometry,
            audio = audio,
            onStarted = {
                recording = true
                val size = recordingGeometry.encodedSize
                listener?.onRecordingStarted(size.width, size.height)
            },
            onStopped = { success, evidence ->
                recording = false
                lastLogRecordingEvidence = evidence
                listener?.onRecordingStopped(success)
                if (!success) listener?.onFailure("log-recording-finalize-failed", "OCLog recording could not be finalized.", true)
            },
        )
        return accepted
    }

    fun consumeLastOpenCineLogEvidence(): OpenCineLogRecordingEvidence? =
        lastLogRecordingEvidence.also { lastLogRecordingEvidence = null }

   fun setOpenCineLogViewAssist(enabled: Boolean) {
       logViewAssistEnabled = enabled
       logPipeline?.setViewAssist(enabled)
   }

    fun setOpenCineLogSqueezeFactor(factor: Float) {
        logPipeline?.setPreviewSqueezeFactor(factor)
    }

    fun stopVideo(): Boolean {
        if (!recording) return false
        logPipeline?.let { return it.stopRecording() }
        cameraExecutor.execute {
            val descriptor = activeDescriptor
            val device = camera
            val surface = previewSurface
            var success = true
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            session = null
            try { recorder?.stop() } catch (_: RuntimeException) { success = false }
            releaseRecorder()
            listener?.onRecordingStopped(success)
            if (success && descriptor != null && device != null && surface?.isValid == true) {
                if (activeVideoProfile?.constrainedHighSpeed == true) {
                    configureHighSpeedPreviewSession(device, descriptor, surface, generation)
                } else configureSession(device, descriptor, surface, generation)
            } else if (!success) {
                listener?.onFailure("video-stop-failed", "The recording was too short or could not be finalized.", true)
            }
        }
        return true
    }

    fun detachPreviewWhileRecording(): Boolean {
        if (!recording) return false
        logPipeline?.let {
            previewSurface = null
            it.detachPreview()
            return true
        }
        cameraExecutor.execute {
            val device = camera ?: return@execute
            val descriptor = activeDescriptor ?: return@execute
            val recordingSurface = recordSurface ?: return@execute
            previewSurface = null
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            session = null
            configureRecordingSession(device, descriptor, null, recordingSurface, startRecorder = false)
        }
        return true
    }

    fun attachPreviewWhileRecording(surface: Surface, displayRotationDegrees: Int): Boolean {
        if (!recording || !surface.isValid) return false
        logPipeline?.let {
            previewSurface = surface
            this.displayRotationDegrees = displayRotationDegrees
            return it.attachPreview(surface, displayRotationDegrees)
        }
        cameraExecutor.execute {
            val device = camera ?: return@execute
            val descriptor = activeDescriptor ?: return@execute
            val recordingSurface = recordSurface ?: return@execute
            this.previewSurface = surface
            this.displayRotationDegrees = displayRotationDegrees
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            session = null
            configureRecordingSession(device, descriptor, surface, recordingSurface, startRecorder = false)
        }
        return true
    }

    fun stopPreview() {
        cameraExecutor.execute {
            generation++
            closeResources()
            listener = null
        }
    }

    private fun configureGpuInputSession(
        device: CameraDevice,
        descriptor: Camera2CameraDescriptor,
        pipeline: OpenCineLogGpuPipeline,
        geometry: RecordingGeometry,
    ) {
        val input = pipeline.cameraInputSurface
        val constrained = activeVideoProfile?.constrainedHighSpeed == true
        val configuration = SessionConfiguration(
            if (constrained) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(input)),
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (logPipeline !== pipeline || !recording) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(input)
                            if (constrained) {
                                applyHighSpeedControls(this)
                            } else {
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                applyTargetFps(this, descriptor)
                                applyManualControls(this, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                        }
                        repeatingBuilder = builder
                        val recordingCallback = recordingCaptureCallback(descriptor) {
                            listener?.onRecordingStarted(geometry.encodedSize.width, geometry.encodedSize.height)
                        }
                        if (constrained) {
                            val highSpeed = configured as? CameraConstrainedHighSpeedCaptureSession
                                ?: error("Camera did not return a constrained high-speed session.")
                            highSpeed.setRepeatingBurstRequests(
                                highSpeed.createHighSpeedRequestList(builder.build()),
                                cameraExecutor,
                                recordingCallback,
                            )
                        } else {
                            configured.setSingleRepeatingRequest(
                                builder.build(),
                                cameraExecutor,
                                recordingCallback,
                            )
                        }
                    } catch (failure: Throwable) {
                        pipeline.stopRecording()
                        listener?.onFailure("video-gpu-session-request-failed", failure.message ?: "GPU video request failed.", true)
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    pipeline.stopRecording()
                    listener?.onFailure("video-gpu-session-failed", "CameraService rejected the GPU video session.", true)
                }
            },
        )
        device.createCaptureSession(configuration)
    }

    private fun configureRecordingSession(
        device: CameraDevice,
        descriptor: Camera2CameraDescriptor,
        preview: Surface?,
        recordingSurface: Surface,
        startRecorder: Boolean,
    ) {
        if (activeVideoProfile?.constrainedHighSpeed == true) {
            configureHighSpeedRecordingSession(device, descriptor, preview, recordingSurface, startRecorder)
            return
        }
        val currentRecordingGeneration = ++recordingSessionGeneration
        val surfaces = buildList {
            preview?.takeIf { it.isValid }?.let(::add)
            add(recordingSurface)
        }
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            surfaces.map(::OutputConfiguration),
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (currentRecordingGeneration != recordingSessionGeneration || recorder == null) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            preview?.takeIf { it.isValid }?.let(::addTarget)
                            addTarget(recordingSurface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            applyTargetFps(this, descriptor)
                            applyManualControls(this, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        repeatingBuilder = builder
                        configured.setSingleRepeatingRequest(
                            builder.build(),
                            cameraExecutor,
                            previewCaptureCallback(descriptor, false),
                        )
                        if (startRecorder) {
                            requireNotNull(recorder).start()
                            recording = true
                            listener?.onRecordingStarted(descriptor.previewSize.width, descriptor.previewSize.height)
                        }
                    } catch (failure: Exception) {
                        if (startRecorder) {
                            releaseRecorder()
                            listener?.onFailure("video-start-failed", failure.message ?: "Video recording could not start.", true)
                        } else {
                            finalizeRecordingAfterSessionFailure()
                        }
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    if (startRecorder) {
                        releaseRecorder()
                        listener?.onFailure("video-session-failed", "Video recording configuration failed.", true)
                    } else {
                        finalizeRecordingAfterSessionFailure()
                    }
                }
            },
        )
        try {
            device.createCaptureSession(configuration)
        } catch (failure: Exception) {
            if (startRecorder) {
                releaseRecorder()
                listener?.onFailure("video-session-exception", failure.message ?: "Video recording configuration failed.", true)
            } else {
                finalizeRecordingAfterSessionFailure()
            }
        }
    }

    private fun configureHighSpeedPreviewSession(
        device: CameraDevice,
        descriptor: Camera2CameraDescriptor,
        surface: Surface,
        currentGeneration: Long,
    ) {
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_HIGH_SPEED,
            listOf(OutputConfiguration(surface)),
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (currentGeneration != generation || configured !is CameraConstrainedHighSpeedCaptureSession) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            applyHighSpeedControls(this)
                        }
                        repeatingBuilder = builder
                        val burst = configured.createHighSpeedRequestList(builder.build())
                        configured.setRepeatingBurstRequests(burst, cameraExecutor, previewCaptureCallback(descriptor, false))
                        listener?.onPreviewStarted(descriptor)
                        notifyZoomRange()
                    } catch (failure: Throwable) {
                        listener?.onFailure("high-speed-preview-request-failed", failure.message ?: "High-speed preview request failed.", true)
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    listener?.onFailure("high-speed-preview-session-failed", "CameraService rejected the constrained high-speed preview.", true)
                }
            },
        )
        // CONTROL_AE_TARGET_FPS_RANGE is advertised as a session key on Motorola devices.
        // Supplying it only after configuration lets the Qualcomm HAL choose the HFR graph
        // without knowing which fixed sensor cadence the first request will require.
        configuration.setSessionParameters(
            device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                applyHighSpeedControls(this)
            }.build(),
        )
        runCatching { device.createCaptureSession(configuration) }
            .onFailure { listener?.onFailure("high-speed-preview-session-exception", it.message ?: "High-speed preview could not be created.", true) }
    }

    private fun configureHighSpeedRecordingSession(
        device: CameraDevice,
        descriptor: Camera2CameraDescriptor,
        preview: Surface?,
        recordingSurface: Surface,
        startRecorder: Boolean,
    ) {
        val currentRecordingGeneration = ++recordingSessionGeneration
        val surfaces = buildList {
            preview?.takeIf { it.isValid }?.let(::add)
            add(recordingSurface)
        }
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_HIGH_SPEED,
            surfaces.map(::OutputConfiguration),
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (currentRecordingGeneration != recordingSessionGeneration || recorder == null ||
                        configured !is CameraConstrainedHighSpeedCaptureSession
                    ) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            preview?.takeIf { it.isValid }?.let(::addTarget)
                            addTarget(recordingSurface)
                            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                            applyHighSpeedControls(this)
                        }
                        repeatingBuilder = builder
                        val burst = configured.createHighSpeedRequestList(builder.build())
                        configured.setRepeatingBurstRequests(burst, cameraExecutor, previewCaptureCallback(descriptor, false))
                        if (startRecorder) {
                            requireNotNull(recorder).start()
                            recording = true
                            val size = activeVideoProfile?.size ?: descriptor.previewSize
                            listener?.onRecordingStarted(size.width, size.height)
                        }
                    } catch (failure: Throwable) {
                        if (startRecorder) {
                            releaseRecorder()
                            listener?.onFailure("high-speed-video-start-failed", failure.message ?: "High-speed video could not start.", true)
                        } else finalizeRecordingAfterSessionFailure()
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    if (startRecorder) {
                        releaseRecorder()
                        listener?.onFailure("high-speed-video-session-failed", "CameraService rejected the constrained high-speed recording.", true)
                    } else finalizeRecordingAfterSessionFailure()
                }
            },
        )
        configuration.setSessionParameters(
            device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                surfaces.forEach(::addTarget)
                applyHighSpeedControls(this)
            }.build(),
        )
        runCatching { device.createCaptureSession(configuration) }
            .onFailure {
                if (startRecorder) {
                    releaseRecorder()
                    listener?.onFailure("high-speed-video-session-exception", it.message ?: "High-speed recording could not be created.", true)
                } else finalizeRecordingAfterSessionFailure()
            }
    }

    private fun finalizeRecordingAfterSessionFailure() {
        var success = true
        try {
            recorder?.stop()
        } catch (_: RuntimeException) {
            success = false
        }
        releaseRecorder()
        listener?.onRecordingStopped(success)
    }

    private fun configureLogSession(
        device: CameraDevice,
        descriptor: Camera2CameraDescriptor,
        currentGeneration: Long,
    ) {
        val profile = activeLogProfile ?: run {
            listener?.onFailure("log-profile-missing", "The selected OCLog profile is unavailable.", false)
            return
        }
        if (profile.constrainedHighSpeed) {
            configureHighSpeedLogSession(device, descriptor, profile, currentGeneration)
            return
        }
        if (profile.sourcePath != OpenCineLogSourcePath.HLG10_BT2020) {
            listener?.onFailure("log-source-invalid", "Regular OCLog requires the HLG10 source path.", false)
            return
        }
        val input = logPipeline?.cameraInputSurface ?: run {
            listener?.onFailure("log-input-surface-missing", "The OCLog camera input surface is unavailable.", false)
            return
        }
        val output = OutputConfiguration(input)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            output.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
        }
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output),
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (currentGeneration != generation || !logPreviewEnabled) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(input)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            applyTargetFps(this, descriptor)
                            applyManualControls(this, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        repeatingBuilder = builder
                        configured.setSingleRepeatingRequest(
                            builder.build(),
                            cameraExecutor,
                            previewCaptureCallback(descriptor, true),
                        )
                        notifyZoomRange()
                    } catch (failure: Throwable) {
                        listener?.onFailure("log-request-failed", failure.message ?: "The HLG10 source request failed.", false)
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    listener?.onFailure("log-session-failed", "CameraService rejected the HLG10 OCLog graph.", false)
                }
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            configuration.setColorSpace(ColorSpace.Named.BT2020_HLG)
        }
        try {
            device.createCaptureSession(configuration)
        } catch (failure: Throwable) {
            listener?.onFailure("log-session-exception", failure.message ?: "The HLG10 OCLog graph could not be created.", false)
        }
    }

    /**
     * HFR cannot be combined with HLG/HDR on the validated Motorola HAL. This session therefore uses
     * the standard-range camera stream and leaves the explicit SDR→linear→OCLog conversion to the
     * GPU pipeline. The profile and sidecar retain that reduced-provenance disclosure.
     */
    private fun configureHighSpeedLogSession(
        device: CameraDevice,
        descriptor: Camera2CameraDescriptor,
        profile: Camera2LogProfile,
        currentGeneration: Long,
    ) {
        if (profile.sourcePath != OpenCineLogSourcePath.SDR_BT709_ISP) {
            listener?.onFailure("log-hfr-source-invalid", "High-speed OCLog requires the disclosed ISP-derived source.", false)
            return
        }
        val input = logPipeline?.cameraInputSurface ?: run {
            listener?.onFailure("log-input-surface-missing", "The OCLog camera input surface is unavailable.", false)
            return
        }
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_HIGH_SPEED,
            listOf(OutputConfiguration(input)),
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (currentGeneration != generation || !logPreviewEnabled ||
                        configured !is CameraConstrainedHighSpeedCaptureSession
                    ) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(input)
                            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                            applyHighSpeedControls(this)
                        }
                        repeatingBuilder = builder
                        configured.setRepeatingBurstRequests(
                            configured.createHighSpeedRequestList(builder.build()),
                            cameraExecutor,
                            previewCaptureCallback(descriptor, true),
                        )
                        notifyZoomRange()
                    } catch (failure: Throwable) {
                        listener?.onFailure(
                            "log-hfr-request-failed",
                            failure.message ?: "The ISP-derived OCLog HFR request failed.",
                            true,
                        )
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    listener?.onFailure(
                        "log-hfr-session-failed",
                        "CameraService rejected the ISP-derived OCLog high-speed graph.",
                        true,
                    )
                }
            },
        )
        configuration.setSessionParameters(
            device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(input)
                applyHighSpeedControls(this)
            }.build(),
        )
        runCatching { device.createCaptureSession(configuration) }
            .onFailure {
                listener?.onFailure(
                    "log-hfr-session-exception",
                    it.message ?: "The ISP-derived OCLog high-speed graph could not be created.",
                    true,
                )
            }
    }

    private fun configureSession(
        device: CameraDevice,
        descriptor: Camera2CameraDescriptor,
        surface: Surface,
        currentGeneration: Long,
    ) {
        val outputs = mutableListOf(OutputConfiguration(surface))
        if (activeVideoProfile == null) {
        descriptor.jpegSize?.let { size ->
            jpegReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, MAX_BURST_IMAGES + 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    image.use {
                        val buffer = it.planes.first().buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        listener?.onJpegCaptured(bytes, it.width, it.height)
                    }
                }, imageHandler)
            }
            outputs += OutputConfiguration(requireNotNull(jpegReader).surface)
        }
        descriptor.rawSize?.let { size ->
            rawReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val received = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    pendingRawImage?.close()
                    pendingRawImage = received
                    emitDngIfReady()
                }, imageHandler)
            }
            outputs += OutputConfiguration(requireNotNull(rawReader).surface)
        }
        descriptor.analysisSize?.let { size ->
            analysisReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    image.use {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastAnalysisAtMs >= ANALYSIS_PERIOD_MS) {
                            lastAnalysisAtMs = now
                            listener?.onAnalysis(analyzeImage(it))
                        }
                    }
                }, imageHandler)
            }
            outputs += OutputConfiguration(requireNotNull(analysisReader).surface)
        }
        }
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (currentGeneration != generation) {
                        configured.close()
                        return
                    }
                    session = configured
                    startRepeating(device, configured, descriptor)
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    listener?.onFailure("preview-session-failed", "Camera preview configuration failed.", true)
                }
            },
        )
        try {
            device.createCaptureSession(configuration)
        } catch (failure: Exception) {
            listener?.onFailure("preview-session-exception", failure.message ?: "Camera preview configuration failed.", true)
        }
    }

    private fun startRepeating(
        device: CameraDevice,
        configured: CameraCaptureSession,
        descriptor: Camera2CameraDescriptor,
    ) {
        val surface = previewSurface ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                analysisReader?.surface?.let(::addTarget)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyTargetFps(this, descriptor)
                applyManualControls(this)
            }
            repeatingBuilder = builder
            configured.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, true))
            notifyZoomRange()
        } catch (failure: Exception) {
            listener?.onFailure("preview-request-failed", failure.message ?: "Camera preview request failed.", true)
        }
    }

    private fun previewCaptureCallback(
        descriptor: Camera2CameraDescriptor?,
        notifyStarted: Boolean,
    ): CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
            var firstFrame = notifyStarted
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (firstFrame) {
                        firstFrame = false
                        descriptor?.let { listener?.onPreviewStarted(it) }
                    }
                    val sensorTimestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                    updateEffectiveFps(sensorTimestamp)
                    reportTapFocusResult(request, result)
                    reportAfLockResult(request, result)
                    reportEffectiveZoom(result)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastMetadataAtMs >= METADATA_PERIOD_MS) {
                        lastMetadataAtMs = now
                        listener?.onMetadata(
                            Camera2PreviewMetadata(
                                result.frameNumber,
                                sensorTimestamp,
                                result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY),
                                result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
                                result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE).also {
                                    it?.let { distance -> lastReportedFocusDiopters = distance }
                                },
                                result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE),
                                result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE),
                                if (Build.VERSION.SDK_INT >= 36) {
                                    result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE)
                                } else null,
                                effectiveFps,
                            ),
                        )
                    }
                }
            }

    private fun recordingCaptureCallback(
        descriptor: Camera2CameraDescriptor,
        onFirstFrame: () -> Unit,
    ): CameraCaptureSession.CaptureCallback {
        val metadataDelegate = previewCaptureCallback(descriptor, false)
        return object : CameraCaptureSession.CaptureCallback() {
            var pendingStart = true

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long,
            ) {
                if (pendingStart) {
                    pendingStart = false
                    onFirstFrame()
                }
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) = metadataDelegate.onCaptureCompleted(session, request, result)
        }
    }

    private fun applyManualControls(
        builder: CaptureRequest.Builder,
        defaultAfMode: Int = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
    ) {
        val iso = requestedIso
        val exposure = requestedExposureNs
        if (iso != null && exposure != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            val frameDurationNs = 1_000_000_000L / requestedTargetFps.coerceAtLeast(1)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.coerceAtMost(frameDurationNs - 100_000L))
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, if (aeLockActive) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE)
            requestedAeCompensationIndex?.let { index ->
                if (activeDescriptor?.aeCompensationRange != null) {
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
                }
            }
        }
        when {
            requestedFocusDiopters != null -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, requestedFocusDiopters!!)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            }
            afLockState == LockState.LOCKED -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, afLockFrozenDiopters ?: 0f)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            }
            activeTapFocusToken != null -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, tapAfRegion?.let { arrayOf(it) })
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, tapAeRegion?.let { arrayOf(it) })
            }
            else -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, defaultAfMode)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            }
        }
        when (val wb = requestedWhiteBalance) {
            is WhiteBalanceSelection.Auto -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                if (Build.VERSION.SDK_INT >= 36) {
                    builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
                    builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, null)
                    builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, null)
                }
            }
            is WhiteBalanceSelection.Kelvin -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                if (Build.VERSION.SDK_INT >= 36) {
                    val range = activeDescriptor?.kelvinRange
                    val clamped = if (range != null) wb.kelvin.coerceIn(range.first, range.last) else wb.kelvin
                    builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_CCT)
                    builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, clamped)
                    builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, 0)
                }
            }
            is WhiteBalanceSelection.Preset -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, wb.awbMode)
                if (Build.VERSION.SDK_INT >= 36) {
                    builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
                    builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, null)
                    builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, null)
                }
            }
        }
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (requestedTorchEnabled && activeDescriptor?.flashAvailable == true) {
                CaptureRequest.FLASH_MODE_TORCH
            } else {
                CaptureRequest.FLASH_MODE_OFF
            },
        )
        applyZoom(builder)
    }

    private fun reportTapFocusResult(request: CaptureRequest, result: TotalCaptureResult) {
        val token = activeTapFocusToken ?: return
        if ((request.tag as? TapFocusRequestTag)?.token != token || tapFocusResultReported) return
        when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)) {
            android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                tapFocusResultReported = true
                listener?.onTapFocusState(TapFocusState.FOCUSED)
            }
            android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                tapFocusResultReported = true
                listener?.onTapFocusState(TapFocusState.NOT_FOCUSED)
            }
        }
    }

    private fun restoreContinuousFocusLocked() {
        if (activeTapFocusToken == null) return
        clearTapFocusLocked(notify = true)
        val builder = repeatingBuilder ?: return
        val configured = session ?: return
        applyManualControls(
            builder,
            if (recording || logPreviewEnabled || activeVideoProfile != null) {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            } else {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            },
        )
        runCatching {
            configured.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
        }.onFailure {
            listener?.onFailure("tap-focus-restore-failed", it.message ?: "Continuous autofocus could not be restored.", true)
        }
    }

    private fun clearTapFocusLocked(notify: Boolean) {
        imageHandler.removeCallbacks(restoreTapFocus)
        tapFocusGeneration++
        activeTapFocusToken = null
        tapAfRegion = null
        tapAeRegion = null
        tapFocusResultReported = false
        repeatingBuilder?.apply {
            setTag(null)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            set(CaptureRequest.CONTROL_AF_REGIONS, null)
            set(CaptureRequest.CONTROL_AE_REGIONS, null)
        }
        if (notify) listener?.onTapFocusState(TapFocusState.IDLE)
    }

    private fun applyTargetFps(builder: CaptureRequest.Builder, descriptor: Camera2CameraDescriptor) {
        descriptor.targetFpsRanges
            .firstOrNull { it.lower == requestedTargetFps && it.upper == requestedTargetFps }
            ?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
    }

    /** True when the active repeating session is constrained high-speed and rejects control locks. */
    private fun isHighSpeedSession(): Boolean =
        session is CameraConstrainedHighSpeedCaptureSession ||
            activeVideoProfile?.constrainedHighSpeed == true ||
            activeLogProfile?.constrainedHighSpeed == true

    private fun reapplyRepeating() {
        val builder = repeatingBuilder ?: return
        val configured = session ?: return
        applyManualControls(builder)
        runCatching {
            configured.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
        }.onFailure {
            listener?.onFailure("lock-reapply-failed", it.message ?: "Lock update could not be applied.", true)
        }
    }

    private fun disableAfLock(notify: Boolean) {
        if (afLockState == LockState.OFF && afLockFrozenDiopters == null && afLockScanToken == null) return
        afLockState = LockState.OFF
        afLockFrozenDiopters = null
        afLockScanToken = null
        afLockResultReported = false
        repeatingBuilder?.setTag(null)
        if (notify) listener?.onAfLockChanged(LockState.OFF)
    }

    private fun startAfLockScan(descriptor: Camera2CameraDescriptor) {
        val configured = session ?: return
        val builder = repeatingBuilder ?: return
        if (configured is CameraConstrainedHighSpeedCaptureSession) return
        val active = descriptor.sensorActiveArray ?: return
        val region = lastTapFocusForAfLock ?: run {
            val cx = active.width() / 2
            val cy = active.height() / 2
            val half = (minOf(active.width(), active.height()) / 6).coerceAtLeast(1)
            MeteringRectangle(
                (cx - half).coerceAtLeast(0),
                (cy - half).coerceAtLeast(0),
                half * 2,
                half * 2,
                MeteringRectangle.METERING_WEIGHT_MAX,
            )
        }
        imageHandler.removeCallbacks(restoreTapFocus)
        afLockState = LockState.PENDING
        afLockScanToken = ++tapFocusGeneration
        afLockResultReported = false
        listener?.onAfLockChanged(LockState.PENDING)
        try {
            builder.setTag(TapFocusRequestTag(afLockScanToken!!))
            tapAfRegion = region
            applyManualControls(builder)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            configured.captureSingleRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            configured.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            configured.captureSingleRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            configured.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
        } catch (failure: Exception) {
            disableAfLock(notify = true)
            listener?.onFailure("af-lock-scan-failed", failure.message ?: "AF lock scan failed.", true)
        }
    }

    private fun reportAfLockResult(request: CaptureRequest, result: TotalCaptureResult) {
        if (afLockState != LockState.PENDING) return
        val token = afLockScanToken ?: return
        if ((request.tag as? TapFocusRequestTag)?.token != token || afLockResultReported) return
        when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)) {
            android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                afLockResultReported = true
                afLockFrozenDiopters = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)
                    ?: lastReportedFocusDiopters ?: 0f
                afLockState = LockState.LOCKED
                tapAfRegion = null
                afLockScanToken = null
                listener?.onAfLockChanged(LockState.LOCKED)
                reapplyRepeating()
            }
            android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                afLockResultReported = true
                disableAfLock(notify = true)
                reapplyRepeating()
                listener?.onFailure("af-lock-unfocused", "AF lock failed: focus could not be confirmed.", true)
            }
        }
    }

    /** Constrained high-speed sessions accept only a restricted request-control subset. */
    private fun applyHighSpeedControls(builder: CaptureRequest.Builder) {
        // Keep TEMPLATE_RECORD defaults intact. Some Qualcomm HALs advertise these keys but
        // reject an HFR request when AF/AWB/AE mode is redundantly overridden.
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(requestedTargetFps, requestedTargetFps))
        applyMotorolaHighSpeedSessionControls(builder)
        // Constrained high-speed sessions accept only a restricted request-control subset; only
        // apply zoom when the descriptor explicitly advertises HFR zoom support, otherwise the
        // HAL may reject the entire burst.
        if (activeDescriptor?.supportsHfrZoom == true) applyZoom(builder)
    }

    /**
     * Motorola's public Camera2 characteristics expose these vendor session keys and its own
     * slow-motion client supplies both before configuring the constrained session. Without them,
     * the device advertises and configures 120/240 fps but its Qualcomm HAL rejects request zero.
     */
    private fun applyMotorolaHighSpeedSessionControls(builder: CaptureRequest.Builder) {
        if (!Build.MANUFACTURER.equals("motorola", ignoreCase = true)) return
        val descriptor = activeDescriptor ?: return
        val keys = runCatching {
            manager.getCameraCharacteristics(descriptor.cameraId).availableCaptureRequestKeys
        }.getOrNull().orEmpty()
        keys.firstOrNull { it.name == MOTOROLA_IS_CAMERA2_KEY }?.let { key ->
            @Suppress("UNCHECKED_CAST")
            builder.set(key as CaptureRequest.Key<ByteArray>, byteArrayOf(1))
        }
        keys.firstOrNull { it.name == MOTOROLA_CURRENT_MODE_KEY }?.let { key ->
            @Suppress("UNCHECKED_CAST")
            builder.set(key as CaptureRequest.Key<IntArray>, intArrayOf(MOTOROLA_SLOW_MOTION_MODE))
        }
    }

    /** Selects the physical member behind a logical camera that truly owns the HFR sensor mode. */
    private fun preferredHighSpeedPhysicalId(
        descriptor: Camera2CameraDescriptor,
        profile: Camera2VideoProfile,
    ): String? {
        val logical = runCatching { manager.getCameraCharacteristics(descriptor.cameraId) }.getOrNull()
            ?: return null
        val targetFocal = descriptor.focalLengthsMm.firstOrNull()
        return logical.physicalCameraIds.mapNotNull { id ->
            val physical = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: return@mapNotNull null
            val map = physical.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@mapNotNull null
            val supportsProfile = runCatching {
                map.highSpeedVideoSizes.any { it == profile.size } &&
                    map.getHighSpeedVideoFpsRangesFor(profile.size).any {
                        it.lower == profile.fps && it.upper == profile.fps
                    }
            }.getOrDefault(false)
            if (!supportsProfile) return@mapNotNull null
            val focal = physical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()
            id to if (targetFocal != null && focal != null) abs(targetFocal - focal) else Float.MAX_VALUE
        }.minByOrNull { it.second }?.first
    }

    private fun updateEffectiveFps(sensorTimestampNs: Long?) {
        val previous = previousSensorTimestampNs
        previousSensorTimestampNs = sensorTimestampNs
        if (previous == null || sensorTimestampNs == null) return
        val delta = sensorTimestampNs - previous
        if (delta !in 1_000_000L..1_000_000_000L) return
        val sample = 1_000_000_000.0 / delta.toDouble()
        effectiveFps = effectiveFps?.let { it * 0.8 + sample * 0.2 } ?: sample
    }

    private fun descriptor(cameraId: String, targetWidth: Int, targetHeight: Int): Camera2CameraDescriptor? =
        runCatching {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val previews = runCatching { map.getOutputSizes(SurfaceHolder::class.java)?.toList() }.getOrNull()
                .orEmpty()
                .ifEmpty { map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty() }
            if (previews.isEmpty()) return null
            val previewSize = choosePreviewSize(previews, targetWidth, targetHeight)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val targetFpsRanges = characteristics
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.distinctBy { it.lower to it.upper }
                ?.sortedWith(compareBy<Range<Int>> { it.lower }.thenBy { it.upper })
                .orEmpty()
            val fixedFps = targetFpsRanges
                .filter { it.lower == it.upper && it.lower in MIN_SELECTABLE_FPS..MAX_SELECTABLE_FPS }
                .map { it.lower }
                .distinct()
                .sorted()
                .filter { streamCanReachFps(map, previewSize, it) }
            val videoSizes = runCatching { map.getOutputSizes(MediaRecorder::class.java)?.toList() }
                .getOrNull().orEmpty().ifEmpty { previews }
                .distinctBy { it.width to it.height }
                .filter { candidate -> previews.any { it.width == candidate.width && it.height == candidate.height } }
            val regularProfiles = videoSizes.flatMap { size ->
                fixedFps.mapNotNull { fps ->
                    Camera2VideoProfile(size, fps, false).takeIf {
                        streamCanReachFps(map, size, fps) && encoderCanReachFps(size, fps)
                    }
                }
            }
            val highSpeedProfiles = if (
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in capabilities
            ) {
                runCatching { map.highSpeedVideoSizes.toList() }.getOrDefault(emptyList()).flatMap { size ->
                    runCatching { map.getHighSpeedVideoFpsRangesFor(size).toList() }.getOrDefault(emptyList())
                        .filter { it.lower == it.upper && it.upper > MAX_SELECTABLE_FPS }
                        .mapNotNull { range ->
                            Camera2VideoProfile(size, range.upper, true)
                                .takeIf { encoderCanReachFps(size, range.upper) }
                        }
                }
            } else emptyList()
            val videoProfiles = (regularProfiles + highSpeedProfiles)
                .distinctBy { Triple(it.size.width, it.size.height, it.fps) }
                .sortedWith(
                    compareByDescending<Camera2VideoProfile> { it.size.width.toLong() * it.size.height }
                        .thenBy { it.size.width }
                        .thenBy { it.fps },
                )
            val hlgProfiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            } else null
            val hasHlg10 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                hlgProfiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
            val hasBt2020Hlg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasHlg10) {
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
                    ?.getSupportedColorSpacesForDynamicRange(ImageFormat.PRIVATE, DynamicRangeProfiles.HLG10)
                    ?.contains(ColorSpace.Named.BT2020_HLG) == true
            } else false
            val regularLogProfiles = if (hasHlg10 && hasBt2020Hlg) {
                videoSizes.flatMap { size ->
                    fixedFps.mapNotNull { fps ->
                        Camera2LogProfile(
                            size = size,
                            fps = fps,
                            sourcePath = OpenCineLogSourcePath.HLG10_BT2020,
                            constrainedHighSpeed = false,
                        ).takeIf {
                            streamCanReachFps(map, size, fps) &&
                                OpenCineLogGpuPipeline.findEncoder(size, fps) != null
                        }
                    }
                }
            } else emptyList()
            val highSpeedLogProfiles = highSpeedProfiles.mapNotNull { profile ->
                Camera2LogProfile(
                    size = profile.size,
                    fps = profile.fps,
                    sourcePath = OpenCineLogSourcePath.SDR_BT709_ISP,
                    constrainedHighSpeed = true,
                ).takeIf { OpenCineLogGpuPipeline.findEncoder(profile.size, profile.fps) != null }
            }
            val logProfiles = (regularLogProfiles + highSpeedLogProfiles)
                .distinctBy { Triple(it.size.width, it.size.height, it.fps) }
                .sortedWith(
                    compareByDescending<Camera2LogProfile> { it.size.width.toLong() * it.size.height }
                        .thenBy { it.size.width }
                        .thenBy { it.fps },
                )
            val zoomRatioRangeArr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else null
            val supportsZoomRatioApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                zoomRatioRangeArr != null &&
                zoomRatioRangeArr.lower < zoomRatioRangeArr.upper
            val zoomRange: ClosedFloatingPointRange<Float>? = if (supportsZoomRatioApi) {
                zoomRatioRangeArr!!.lower..zoomRatioRangeArr.upper
            } else {
                // API 29 fallback: digital zoom only (>= 1x) via SCALER_CROP_REGION.
                val maxDigitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?.takeIf { it > 1f }
                if (maxDigitalZoom != null) 1f..maxDigitalZoom else null
            }
            val logicalFocal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()
            val physicalFocals = runCatching { characteristics.physicalCameraIds }.getOrDefault(emptySet())
                .filter { it != cameraId }
                .mapNotNull { id ->
                    runCatching {
                        manager.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            ?.firstOrNull()
                    }.getOrNull()?.let { focal -> id to focal }
                }
            val anchors = ZoomMath.deriveAnchors(
                logicalFocal, physicalFocals,
                rangeMin = zoomRange?.start ?: Float.NEGATIVE_INFINITY,
                rangeMax = zoomRange?.endInclusive ?: Float.POSITIVE_INFINITY,
            )
            // HFR zoom is only safe when the camera uses the CONTROL_ZOOM_RATIO API (API 30+)
            // and the key is present in availableCaptureRequestKeys. Constrained sessions reject
            // unknown keys, so we gate this conservatively.
            val hfrZoomKeys = if (supportsZoomRatioApi) {
                runCatching { characteristics.availableCaptureRequestKeys }.getOrDefault(emptyList())
            } else emptyList()
            val supportsHfrZoom = supportsZoomRatioApi &&
                hfrZoomKeys.any { it.name == "zoomRatio" }
            // Direct Kelvin (COLOR_CORRECTION_MODE_CCT) requires API 36+, the CCT mode advertised
            // in available color-correction modes, the temperature key present in the request
            // keys, and a non-empty COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE with lower < upper.
            val kelvinRange: IntRange? = if (Build.VERSION.SDK_INT >= 36) {
                runCatching {
                    val cctSupported = characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)
                        ?.contains(CaptureRequest.COLOR_CORRECTION_MODE_CCT) == true
                    val tempKeyPresent = characteristics.availableCaptureRequestKeys
                        .any { it == CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE }
                    val range = characteristics.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)
                    val valid = range != null && range.lower < range.upper
                    if (cctSupported && tempKeyPresent && valid) range!!.lower..range.upper else null
                }.getOrNull()
            } else null
            Camera2CameraDescriptor(
                cameraId = cameraId,
                lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_EXTERNAL,
                focalLengthsMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
                previewSize = previewSize,
                jpegSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height },
                rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height },
                analysisSize = map.getOutputSizes(ImageFormat.YUV_420_888)?.minByOrNull { abs(it.width.toLong() * it.height - 320L * 240L) },
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                exposureTimeRangeNs = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
                aeCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
                minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
                supportsRaw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities,
                flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                targetFpsRanges = targetFpsRanges,
                availableFixedFps = fixedFps,
                videoProfiles = videoProfiles,
                logProfiles = logProfiles,
                sensorActiveArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                availableAfModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty(),
                maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
                maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                aeLockSupported = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) != null,
                afLockSupported = runCatching {
                    val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
                    CaptureRequest.CONTROL_AF_MODE_OFF in afModes && CaptureRequest.CONTROL_AF_MODE_AUTO in afModes
                }.getOrDefault(false),
                zoomRatioRange = zoomRange,
                supportsZoomRatioApi = supportsZoomRatioApi,
                digitalZoomMaxRatio = if (!supportsZoomRatioApi) zoomRange?.endInclusive else null,
                opticalAnchors = anchors,
                supportsHfrZoom = supportsHfrZoom,
                kelvinRange = kelvinRange,
            )
        }.getOrNull()

    private fun choosePreviewSize(sizes: List<Size>, targetWidth: Int, targetHeight: Int): Size {
        // The display may be extremely tall/unfolded; never turn its panel ratio into a
        // non-standard recording size. Keep the production default interoperable 16:9.
        val targetRatio = 16.0 / 9.0
        val bounded = sizes.filter { it.width.toLong() * it.height <= MAX_PREVIEW_PIXELS }
            .ifEmpty { sizes }
        return bounded.minWithOrNull(
            compareBy<Size> { abs((maxOf(it.width, it.height).toDouble() / minOf(it.width, it.height)) - targetRatio) }
                .thenBy { abs(it.width.toLong() * it.height - TARGET_PREVIEW_PIXELS) },
        ) ?: sizes.first()
    }

    private fun streamCanReachFps(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        size: Size,
        fps: Int,
    ): Boolean {
        val minimumDuration = runCatching {
            map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
        }.getOrDefault(0L)
        return minimumDuration <= 0L || minimumDuration <= 1_000_000_000L / fps.coerceAtLeast(1)
    }

    private fun encoderCanReachFps(size: Size, fps: Int): Boolean =
        avcSurfaceEncoderCapabilities.any {
            it.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
        }

    private fun jpegOrientation(descriptor: Camera2CameraDescriptor): Int =
        if (descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (descriptor.sensorOrientation + displayRotationDegrees) % 360
        } else {
            (descriptor.sensorOrientation - displayRotationDegrees + 360) % 360
        }

    private fun closeResources() {
        recordingSessionGeneration++
        clearTapFocusLocked(notify = true)
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { jpegReader?.close() }
        jpegReader = null
        runCatching { rawReader?.close() }
        rawReader = null
        runCatching { analysisReader?.close() }
        analysisReader = null
        clearPendingRaw()
        repeatingBuilder = null
        if (recording) runCatching { recorder?.stop() }
        releaseRecorder()
        runCatching { logPipeline?.close() }
        logPipeline = null
        passthroughVideoPipeline = false
        logPreviewEnabled = false
        activeLogProfile = null
        previewSurface = null
        activeDescriptor = null
        requestedAeCompensationIndex = null
        requestedWhiteBalance = WhiteBalanceSelection.Auto
        requestedZoomRatio = 1f
        lastAcceptedZoomRatio = 1f
        effectiveZoomRatio = 1f
        aeLockActive = false
        afLockState = LockState.OFF
        afLockFrozenDiopters = null
        afLockScanToken = null
        afLockResultReported = false
        lastTapFocusForAfLock = null
        lastReportedFocusDiopters = null
    }

    private fun releaseRecorder() {
        recording = false
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        recordSurface = null
    }

    private fun emitDngIfReady() {
        val image = pendingRawImage ?: return
        val result = pendingRawResult ?: return
        pendingRawImage = null
        pendingRawResult = null
        val descriptor = activeDescriptor
        if (descriptor == null) {
            image.close()
            return
        }
        try {
            val characteristics = manager.getCameraCharacteristics(descriptor.cameraId)
            val width = image.width
            val height = image.height
            val output = ByteArrayOutputStream()
            image.use { DngCreator(characteristics, result).writeImage(output, it) }
            listener?.onDngCaptured(output.toByteArray(), width, height)
        } catch (failure: Throwable) {
            runCatching { image.close() }
            listener?.onFailure("dng-write-failed", failure.message ?: "DNG could not be encoded.", true)
        }
    }

    private fun clearPendingRaw() {
        runCatching { pendingRawImage?.close() }
        pendingRawImage = null
        pendingRawResult = null
    }

    private fun analyzeImage(image: Image): Camera2Analysis {
        val yPlane = image.planes[0]
        val uPlane = image.planes.getOrNull(1)
        val vPlane = image.planes.getOrNull(2)
        val yBuffer = yPlane.buffer
        val lumaHistogram = IntArray(SCOPE_HISTOGRAM_BINS)
        val redHistogram = IntArray(SCOPE_HISTOGRAM_BINS)
        val greenHistogram = IntArray(SCOPE_HISTOGRAM_BINS)
        val blueHistogram = IntArray(SCOPE_HISTOGRAM_BINS)
        val zebraHits = IntArray(16 * 9)
        val focusHits = IntArray(16 * 9)
        val counts = IntArray(16 * 9)
        for (y in 0 until image.height step 4) {
            var previous = -1
            for (x in 0 until image.width step 4) {
                val yIndex = y * yPlane.rowStride + x * yPlane.pixelStride
                if (yIndex >= yBuffer.limit()) continue
                val luma = yBuffer.get(yIndex).toInt() and 0xff
                val chromaX = x / 2
                val chromaY = y / 2
                val u = uPlane?.let { plane ->
                    val index = chromaY * plane.rowStride + chromaX * plane.pixelStride
                    if (index < plane.buffer.limit()) plane.buffer.get(index).toInt() and 0xff else 128
                } ?: 128
                val v = vPlane?.let { plane ->
                    val index = chromaY * plane.rowStride + chromaX * plane.pixelStride
                    if (index < plane.buffer.limit()) plane.buffer.get(index).toInt() and 0xff else 128
                } ?: 128
                val c = (luma - 16).coerceAtLeast(0)
                val d = u - 128
                val e = v - 128
                val red = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val green = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val blue = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                lumaHistogram[luma * SCOPE_HISTOGRAM_BINS / 256]++
                redHistogram[red * SCOPE_HISTOGRAM_BINS / 256]++
                greenHistogram[green * SCOPE_HISTOGRAM_BINS / 256]++
                blueHistogram[blue * SCOPE_HISTOGRAM_BINS / 256]++
                val cell = (y * 9 / image.height).coerceIn(0, 8) * 16 + (x * 16 / image.width).coerceIn(0, 15)
                counts[cell]++
                if (luma >= 235) zebraHits[cell]++
                if (previous >= 0 && kotlin.math.abs(luma - previous) >= 35) focusHits[cell]++
                previous = luma
            }
        }
        val total = lumaHistogram.sum().coerceAtLeast(1).toFloat()
        return Camera2Analysis(
            histogram = lumaHistogram.map { it / total },
            redHistogram = redHistogram.map { it / total },
            greenHistogram = greenHistogram.map { it / total },
            blueHistogram = blueHistogram.map { it / total },
            zebraCells = counts.indices.map { counts[it] > 0 && zebraHits[it].toFloat() / counts[it] >= .20f },
            focusCells = counts.indices.map { counts[it] > 0 && focusHits[it].toFloat() / counts[it] >= .12f },
            capturedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
    }

    override fun close() {
        generation++
        closeResources()
        cameraExecutor.close()
        imageThread.quitSafely()
    }

    private fun lensOrder(lensFacing: Int): Int = when (lensFacing) {
        CameraCharacteristics.LENS_FACING_BACK -> 0
        CameraCharacteristics.LENS_FACING_FRONT -> 1
        else -> 2
    }

    companion object {
        const val FOCUS_PULL_TICK_MS = 33L
        const val DEFAULT_TARGET_FPS = 30
        private const val MIN_SELECTABLE_FPS = 10
        private const val MAX_SELECTABLE_FPS = 60
        private const val METADATA_PERIOD_MS = 500L
        private const val ZOOM_REPORT_PERIOD_MS = 33L
        private const val ANALYSIS_PERIOD_MS = 250L
        private const val TAP_FOCUS_HOLD_MS = 3_000L
        internal const val SCOPE_HISTOGRAM_BINS = 64
        private const val MAX_PREVIEW_PIXELS = 1920L * 1080L
        private const val TARGET_PREVIEW_PIXELS = 1920L * 1080L
        private const val MAX_BURST_IMAGES = 10
        private const val MOTOROLA_IS_CAMERA2_KEY = "com.lenovo.moto.clientapp.is_motcamera2"
        private const val MOTOROLA_CURRENT_MODE_KEY = "com.lenovo.moto.clientapp.current_mode"
        private const val MOTOROLA_SLOW_MOTION_MODE = 3
    }
}

private fun preferredTargetFps(values: List<Int>): Int = when {
    Camera2PreviewEngine.DEFAULT_TARGET_FPS in values -> Camera2PreviewEngine.DEFAULT_TARGET_FPS
    values.isNotEmpty() -> values.minBy { abs(it - Camera2PreviewEngine.DEFAULT_TARGET_FPS) }
    else -> Camera2PreviewEngine.DEFAULT_TARGET_FPS
}

private fun preferredLogProfile(values: List<Camera2LogProfile>, requestedFps: Int): Camera2LogProfile? =
    values.firstOrNull {
        it.size.width == 1920 && it.size.height == 1080 && it.fps == requestedFps &&
            it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020
    } ?: values.firstOrNull {
        it.size.width == 1920 && it.size.height == 1080 &&
            it.fps == Camera2PreviewEngine.DEFAULT_TARGET_FPS &&
            it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020
    } ?: values.minWithOrNull(
        compareBy<Camera2LogProfile> { if (it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020) 0 else 1 }
            .thenBy { abs(it.size.width.toLong() * it.size.height - 1920L * 1080L) }
            .thenBy { abs(it.fps - requestedFps) },
    )
