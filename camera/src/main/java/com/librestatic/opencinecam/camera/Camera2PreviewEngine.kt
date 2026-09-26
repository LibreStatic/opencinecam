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
import android.os.PowerManager
import android.os.Build
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.CompletableFuture
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
    val qualificationStage: OpenCineLogQualificationStage = OpenCineLogQualificationStage.EXPERIMENTAL,
    val qualificationReason: String = OpenCineLogQualificationPolicy.NOT_RUN_REASON,
    val qualificationEvidenceId: String? = null,
) {
    init {
        require(constrainedHighSpeed == sourcePath.highSpeedDerived) {
            "Only the explicitly disclosed ISP-derived LOG source may use constrained HFR."
        }
        require(
            qualificationStage != OpenCineLogQualificationStage.VERIFIED ||
                OpenCineLogQualificationPolicy.isVerified(qualificationStage, qualificationEvidenceId),
        ) { "A verified LOG profile must reference exact qualification evidence." }
    }

    val isVerified: Boolean
        get() = OpenCineLogQualificationPolicy.isVerified(qualificationStage, qualificationEvidenceId)
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
    val tintSupported: Boolean = false,
    val availableAwbModes: Set<Int> = emptySet(),
    val exposureCapabilities: ExposureCapabilities = ExposureCapabilities(),
    val imageProcessingCapabilities: ImageProcessingCapabilities = ImageProcessingCapabilities(),
    val awbLockSupported: Boolean = false,
    val torchCapabilities: TorchCapabilities = TorchCapabilities(flashAvailable),
    val timestampSourceRealtime: Boolean = false,
    val photoFlashCapabilities: PhotoFlashCapabilities = PhotoFlashCapabilities(),
    val heicSize: Size? = null,
    val aeCompensationStepNumerator: Int = 0,
    val aeCompensationStepDenominator: Int = 1,
) {
    val supportsOpenCineLog: Boolean
        get() = logProfiles.isNotEmpty()

    /** True when at least one exact profile is explicitly tied to accepted evidence. */
    val hasVerifiedOpenCineLog: Boolean
        get() = logProfiles.any { it.isVerified }

    /** Mode-wide promotion is safe only when every advertised LOG tuple is verified. */
    val allOpenCineLogProfilesVerified: Boolean
        get() = logProfiles.isNotEmpty() && logProfiles.all { it.isVerified }

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
    val torchEnabled: Boolean? = null,
    val torchStrengthLevel: Int? = null,
    val colorTint: Int? = null,
    val exposureMode: ExposureMode? = null,
    val antibanding: Int? = null,
    val opticalStabilization: Int? = null,
    val videoStabilization: Int? = null,
    val noiseReduction: Int? = null,
    val edgeEnhancement: Int? = null,
    val cropRegion: List<Int>? = null,
    val submittedImageProcessing: ImageProcessingDefaults? = null,
    val awbLocked: Boolean? = null,
    val afMode: Int? = null,
    val submittedAfMode: Int? = null,
    val submittedFocusDistanceDiopters: Float? = null,
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
    val scopes: MonitoringScopeFrame? = null,
)

data class Camera2EmbeddedAudioConfig(
    val source: Int,
    val sampleRateHz: Int,
    val channels: Int,
    val bitrateBps: Int,
    val preferredInputDeviceId: Int?,
    val enableAutomaticGainControl: Boolean = false,
    val onAudioLevel: ((AudioLevelSnapshot) -> Unit)? = null,
    val recordingGain: DigitalRecordingGain = DigitalRecordingGain(),
    val listeningSink: PcmListeningSink? = null,
    val enableNoiseSuppressor: Boolean = false,
    val enableAcousticEchoCanceler: Boolean = false,
)

data class PhotoFlashReport(
    val requested: PhotoFlashSelection,
    val submittedAeMode: Int?,
    val submittedFlashMode: Int,
    val requestedStrength: Int?,
    val reportedAeState: Int?,
    val reportedFlashState: Int?,
    val reportedStrength: Int?,
    val sensorTimestampNs: Long?,
)

interface Camera2PreviewListener {
    fun onOperatorLutStatus(status: OperatorLutStatus) = Unit
    fun onPhotoFlashResult(report: PhotoFlashReport) = Unit
    fun onStillCaptured(capture: CapturedStill) = Unit
    fun onBurstCaptured(capture: CapturedBurst) = Unit
    fun onBurstProgress(completed: Int, total: Int) = Unit
    fun onBracketCaptured(capture: CapturedBracket) = Unit
    fun onBracketProgress(completed: Int, total: Int) = Unit
    fun onAccumulationProgress(completed: Int, elapsedMs: Long, targetMs: Long) = Unit
    fun onAccumulationCaptured(capture: CapturedAccumulation) = Unit
    fun onProfessionalControlsRejected(message: String) {}
    fun onOpening(descriptor: Camera2CameraDescriptor)
    fun onPreviewStarted(descriptor: Camera2CameraDescriptor)
    fun onMetadata(metadata: Camera2PreviewMetadata)
    fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int)
    fun onDngCaptured(bytes: ByteArray, width: Int, height: Int)
    fun onAnalysis(analysis: Camera2Analysis)
    fun onRecordingLutApplied(evidence: BakedLutEvidence) { }
    fun onRecordingStarted(width: Int, height: Int)
    fun onRecordingStopped(success: Boolean)
    fun onFailure(code: String, message: String, recoverable: Boolean)
    fun onTorchRejected(message: String) = Unit
    fun onPreviewSurfaceLost(message: String) = Unit
    fun onTapFocusState(state: TapFocusState) = Unit
    fun onZoomRangeAvailable(min: Float, max: Float, anchors: List<ZoomAnchor>) = Unit
    fun onZoomEffective(ratio: Float) = Unit
    fun onZoomRejected(requested: Float, accepted: Float) = Unit
    fun onAeLockChanged(active: Boolean) = Unit
    fun onAfLockChanged(state: LockState) = Unit
    fun onFocusPullFinished() = Unit
    fun onFocusPullStarted(targetDiopters: Float) = Unit
    fun onFocusPullCancelled() = Unit
    fun onTimelapseProgress(progress: TimelapseProgress) = Unit
    fun onTimelapsePauseChanged(status: TimelapsePauseStatus) = Unit
    fun onFocusSelectionChanged(diopters: Float?) = Unit
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

class Camera2PreviewEngine(
    context: Context,
    private val ownerAdmission: CaptureOwnerAdmission = CaptureOwnerAdmission.processGlobal,
) : AutoCloseable {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val appContext = context.applicationContext
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val cameraExecutor = CloseTolerantCameraExecutor()
    private val imageThread = HandlerThread("OpenCineCamImage").apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    @Volatile private var generation = 0L
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
        set(value) {
            if (field !== value) pendingPreviewStartedReceipt = null
            field = value
        }
    private data class PreviewStartedReceipt(val session: CameraCaptureSession, val generation: Long,
        val descriptor: Camera2CameraDescriptor)
    // cameraExecutor-confined: request replacements share the real graph's one start receipt.
    private var pendingPreviewStartedReceipt: PreviewStartedReceipt? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var analysisReader: ImageReader? = null
    private var analysisReaderLease: ReaderLease<ImageReader>? = null
    private val rawImageLock = Any()
    private val queuedRawImages = mutableSetOf<Image>()
    private val pendingRawFrames = linkedMapOf<Long, Image>()
    private var activeStillFormat = StillPhotoFormat.JPEG
    private var repeatingBuilder: CaptureRequest.Builder? = null
    private class RecorderRequest(val generation: Long, val device: CameraDevice) {
        val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    // Held from synchronous acceptance until native reset/release finishes on cameraExecutor.
    private val recorderRequest = java.util.concurrent.atomic.AtomicReference<RecorderRequest?>(null)
    private data class PhotoTag(val id: Long, val trigger: Boolean = false)
    private data class JpegPayload(val bytes: ByteArray, val width: Int, val height: Int,
        val kind: StillImageKind, val ticket: StillImageHandoff.Ticket<JpegPayload>)
    private enum class LegacyPhotoDelivery { NONE, JPEG, DNG }
    private class PhotoRequest(
        val id: Long, val generation: Long, val device: CameraDevice,
        val session: CameraCaptureSession, val reader: ImageReader,
        val descriptor: Camera2CameraDescriptor, val listener: Camera2PreviewListener,
        val selection: PhotoFlashSelection, val format: StillPhotoFormat,
        val quality: Int, val raw: ImageReader?, val legacy: LegacyPhotoDelivery,
        val bracket: BracketRequest? = null, val bracketIndex: Int = 0,
        val accumulation: AccumulationRequest? = null,
        val aspect: PhotoAspectSelection = PhotoAspectSelection(),
        val burst: BurstRequest? = null,
    ) {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var sequence: PhotoCaptureSequence? = null
        var plan: PhotoFlashResolution.Plan? = null
        var still: CaptureRequest? = null
        var report: PhotoFlashReport? = null
        var result: TotalCaptureResult? = null
        var timeout: Runnable? = null
        var repeatingChanged = false
        var stillSubmitted = false
        var desiredOrientationDegrees = 0
        var deadlineUptimeMs = Long.MAX_VALUE
    }
    private class BurstRequest(
        val id: Long, val generation: Long, val device: CameraDevice, val session: CameraCaptureSession,
        val reader: ImageReader, val descriptor: Camera2CameraDescriptor, val listener: Camera2PreviewListener,
        val count: Int, val quality: Int, val aspect: PhotoAspectSelection,
    ) {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var orientationDegrees: Int? = null
        var controls: CaptureRequest? = null
        var timeout: Runnable? = null
        var repeatingChanged = false
        val frames = mutableListOf<BurstFrame>()
        @Volatile var encodedBytes = 0L
    }
    private val burstRequest = java.util.concurrent.atomic.AtomicReference<BurstRequest?>(null)
    private class BracketRequest(
        val id: Long, val generation: Long, val device: CameraDevice, val session: CameraCaptureSession,
        val reader: ImageReader, val descriptor: Camera2CameraDescriptor, val listener: Camera2PreviewListener,
        val selection: BracketSelection, val quality: Int, val aspect: PhotoAspectSelection,
    ) {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var plan: BracketResolution.Plan? = null
        var aspectOrientationDegrees: Int? = null
        var controls: CaptureRequest? = null
        var timeout: Runnable? = null
        var repeatingChanged = false
        val frames = mutableListOf<BracketFrame>()
        @Volatile var encodedBytes = 0L
    }
    private class AccumulationRequest(
        val id: Long, val generation: Long, val device: CameraDevice, val session: CameraCaptureSession,
        val reader: ImageReader, val descriptor: Camera2CameraDescriptor, val listener: Camera2PreviewListener,
        val selection: AccumulationSelection, val quality: Int, val aspect: PhotoAspectSelection,
    ) {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val finishRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var completedFrames = 0
        @Volatile var finishing = false
        var startedAtMs = 0L
        var lastFrameStartedAtMs = 0L
        var outputOrientationDegrees = 0
        var controls: CaptureRequest? = null
        var timeout: Runnable? = null
        var tick: Runnable? = null
        var repeatingChanged = false
        var processor: AccumulationBitmapProcessor? = null
        val frames = mutableListOf<AccumulationFrame>()
    }
    private val accumulationRequest = java.util.concurrent.atomic.AtomicReference<AccumulationRequest?>(null)
    private val stillAdmissionLock = Any()
    private val bracketRequest = java.util.concurrent.atomic.AtomicReference<BracketRequest?>(null)
    private val legacyStillRequest = java.util.concurrent.atomic.AtomicReference<Any?>(null)
    private val photoIds = java.util.concurrent.atomic.AtomicLong()
    private val photoRequest = java.util.concurrent.atomic.AtomicReference<PhotoRequest?>(null)
    // Payloads may precede their Camera2 result callbacks. Only an explicitly classified
    // legacy request or the matching PHOTO result can release one to the saver.
    private val pendingJpegs = linkedMapOf<Long, JpegPayload>()
    private val compressedHandoff = StillImageHandoff<JpegPayload>(StillImagePayload.MAX_COMPRESSED_BYTES)
    private val compressedReadFailures = linkedMapOf<Long, String>()
    private val legacyJpegTimestamps = linkedSetOf<Long>()
    private var recorder: MediaRecorder? = null
    private var recordSurface: Surface? = null
    @Volatile private var recording = false
    private var recordingSessionGeneration = 0L
    // Fault qualification observes the real platform callback; production leaves this unset.
    private var recordingSessionObserver: ((SessionConfiguration) -> Unit)? = null
    @Volatile private var activeDescriptor: Camera2CameraDescriptor? = null
    private var listener: Camera2PreviewListener? = null
    private var lastMetadataAtMs = 0L
    private var lastAnalysisAtMs = 0L
    private var displayRotationDegrees = 0
    private var requestedIso: Int? = null
    private var requestedExposureNs: Long? = null
    private var requestedFocusDiopters: Float? = null
    private var requestedFocusCameraId: String? = null
    private var requestedWhiteBalance: WhiteBalanceSelection = WhiteBalanceSelection.Auto
    private val recordingWbGate = RecordingWhiteBalanceGate()
    private var recordingWbToken = 0L
    private var heldRecordingWb: WhiteBalanceSelection? = null
    private var recordingWbCallback: ((RecordingWhiteBalanceResult) -> Unit)? = null
    private var recordingWbTimeout: Runnable? = null
    @Volatile private var recordingWbMinimumTimestampNs: Long? = null
    private var requestedAeCompensationIndex: Int? = null
    private var requestedTorchEnabled = false
    private var requestedTorchStrength: Int? = null
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
    @Volatile private var operatorLutSelection: MonitorLut? = null
    @Volatile private var subjectLutSelection: MonitorLut? = null
    @Volatile private var monitoringOptions = MonitoringOptions()
    private var logPipeline: OpenCineLogGpuPipeline? = null
        set(value) {
            field = value
            value?.setMonitoringOptions(monitoringOptions)
            value?.setOperatorLut(operatorLutSelection)
            value?.setSubjectLut(subjectLutSelection)
        }

    fun setOperatorLut(lut: MonitorLut?) {
        operatorLutSelection = lut
        logPipeline?.setOperatorLut(lut)
    }
    fun setSubjectLut(lut: MonitorLut?) {
        subjectLutSelection = lut
        logPipeline?.setSubjectLut(lut)
    }

    private fun deliverGpuLutStatus(expectedGeneration: Long, owner: () -> OpenCineLogGpuPipeline?, status: OperatorLutStatus) {
        if (disposed.get()) return
        try { cameraExecutor.execute {
            val pipeline = owner()
            if (!disposed.get() && expectedGeneration == generation && pipeline != null && logPipeline === pipeline &&
                status.selectionId == monitorLutIdentity(operatorLutSelection)) listener?.onOperatorLutStatus(status)
        } } catch (_: java.util.concurrent.RejectedExecutionException) { /* Owner retired. */ }
    }

    private fun deliverGpuAnalysis(expectedGeneration: Long, owner: () -> OpenCineLogGpuPipeline?, analysis: Camera2Analysis) {
        // Queue behind open/close so a check cannot race a new descriptor or a cleared UI sample.
        if (disposed.get()) return
        try { cameraExecutor.execute {
            val pipeline = owner()
            if (!disposed.get() && expectedGeneration == generation && pipeline != null && logPipeline === pipeline)
                listener?.onAnalysis(analysis)
        } } catch (_: java.util.concurrent.RejectedExecutionException) { /* Terminal owner, no sample delivery. */ }
    }

    fun setMonitoringOptions(options: MonitoringOptions) {
        monitoringOptions = options
        logPipeline?.setMonitoringOptions(options)
    }
    private var passthroughVideoPipeline = false
    private var gpuPreviewEnabled = false
    @Volatile private var gpuPhotoPreviewEnabled = false
    private var cameraOpening = false
    private var closingCamera: CameraDevice? = null
    private val retiringPipelines = mutableSetOf<OpenCineLogGpuPipeline>()
    private val initializationRetirements = mutableSetOf<CompletableFuture<Unit>>()
    private var ownerLease: CaptureOwnerAdmission.Lease? = null
    private var ownerReady = false
    private var retirementFailure: Throwable? = null
    private val nativeRetirement = CompletableFuture<Unit>()
    private val closeCompletion = CompletableFuture<Unit>()
    private var closeStarted = false
    private var pendingPreviewStart: Runnable? = null
    private val disposed = java.util.concurrent.atomic.AtomicBoolean(false)
    private data class SubjectTarget(val token: Long, val surface: Surface, val options: SubjectPreviewOptions, val onStatus: (SubjectPreviewStatus) -> Unit)
    private var subjectTarget: SubjectTarget? = null
    private var subjectPipeline: OpenCineLogGpuPipeline? = null
    private var attachedSubjectToken: Long? = null
    private var subjectAttempt = 0L
    private var subjectRetry: Runnable? = null
    private var logPreviewEnabled = false
    private var logViewAssistEnabled = false
    private var imageProcessing = ImageProcessingSelection()
    private val processingDefaults = java.util.WeakHashMap<CaptureRequest.Builder, ImageProcessingDefaults>()
    private var professionalExposure: ExposureSelection? = null
    private var requestedTargetFps = DEFAULT_TARGET_FPS
    private var activeVideoProfile: Camera2VideoProfile? = null
    private var activeLogProfile: Camera2LogProfile? = null
    private var previousSensorTimestampNs: Long? = null
    private var effectiveFps: Double? = null
    private var lastReportedFocusDiopters: Float? = null
    private val focusPullSession = FocusPullSession()
    private var focusPullTask: Runnable? = null
    private val perCameraFocusMarks = java.util.concurrent.ConcurrentHashMap<String, Map<String, Float>>()

    private fun cancelFocusPullLocked(notify: Boolean = true) {
        focusPullTask?.let(imageHandler::removeCallbacks)
        focusPullTask = null
        if (focusPullSession.cancel() && notify) {
            listener?.onFocusSelectionChanged(requestedFocusDiopters)
            listener?.onFocusPullCancelled()
        }
    }

    private fun scheduleFocusPull(token: Long, cameraGeneration: Long, delayMs: Long) {
        focusPullTask = Runnable {
            cameraExecutor.execute {
                if (generation != cameraGeneration || !focusPullSession.isCurrent(token)) return@execute
                val step = focusPullSession.tick(token, SystemClock.elapsedRealtime()) ?: return@execute
                requestedFocusDiopters = step.diopters
                requestedFocusCameraId = activeDescriptor?.cameraId
                reapplyRepeating()
                if (step.finished) {
                    focusPullTask = null
                    listener?.onFocusSelectionChanged(step.diopters)
                    listener?.onFocusPullFinished()
                } else scheduleFocusPull(token, cameraGeneration, FOCUS_PULL_TICK_MS)
            }
        }.also { imageHandler.postDelayed(it, delayMs) }
    }
    private val avcSurfaceEncoderCapabilities by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && !it.isAlias }
            .mapNotNull { info -> runCatching { info.getCapabilitiesForType("video/avc") }.getOrNull() }
            .filter { it.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) }
            .mapNotNull { it.videoCapabilities }
            .toList()
    }
    private val hevcSurfaceEncoderCapabilities by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && !it.isAlias }
            .mapNotNull { info -> runCatching { info.getCapabilitiesForType("video/hevc") }.getOrNull() }
            .filter { it.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) }
            .mapNotNull { it.videoCapabilities }
            .toList()
    }
    @Volatile private var lastLogRecordingEvidence: OpenCineLogRecordingEvidence? = null
    @Volatile private var lastRecordingLutEvidence: BakedLutEvidence? = null

    fun descriptors(targetWidth: Int, targetHeight: Int): List<Camera2CameraDescriptor> =
        manager.cameraIdList.mapNotNull { cameraId -> descriptor(cameraId, targetWidth, targetHeight) }
            .sortedWith(compareBy<Camera2CameraDescriptor> { lensOrder(it.lensFacing) }.thenBy { it.cameraId })

    fun preferredDescriptor(targetWidth: Int, targetHeight: Int): Camera2CameraDescriptor? =
        descriptors(targetWidth, targetHeight).firstOrNull()

    /** Selected session path; readiness is reported separately by onPreviewStarted. */
    fun usesGpuViewfinder(): Boolean = logPreviewEnabled || gpuPreviewEnabled

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
        gpuPreview: Boolean = false,
        stillFormat: StillPhotoFormat = StillPhotoFormat.JPEG,
        gpuPhotoPreview: Boolean = false,
    ) {
        if (disposed.get()) return
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onFailure("camera-permission-denied", "Camera permission is required.", true)
            return
        }
        cameraExecutor.execute {
            if (disposed.get()) return@execute
            acquireOwnerIfNeeded()
            val currentGeneration = ++generation
            pendingPreviewStart = null
            closeResources()
            this.listener = listener
            if (requestedFocusCameraId != descriptor.cameraId) {
                requestedFocusDiopters = null
                requestedFocusCameraId = null
            }
            listener.onFocusSelectionChanged(requestedFocusDiopters)
            listener.onOpening(descriptor)
            pendingPreviewStart = Runnable {
                if (disposed.get() || currentGeneration != generation || !surface.isValid) return@Runnable
                this.listener = listener
                if (stillFormat == StillPhotoFormat.HEIC && descriptor.heicSize == null) {
                    listener.onFailure("still-format-unavailable", "This camera does not advertise a native HEIC output.", true)
                    return@Runnable
                }
                this.activeStillFormat = stillFormat
                this.previewSurface = surface
                this.activeDescriptor = descriptor
                this.displayRotationDegrees = displayRotationDegrees
                this.logPreviewEnabled = openCineLog
                this.logViewAssistEnabled = viewAssist
                this.gpuPhotoPreviewEnabled = gpuPhotoPreview && gpuPreview && !openCineLog
                // A photographic GPU monitor retains regular still readers, not a video/HFR graph.
                this.activeVideoProfile = if (gpuPhotoPreviewEnabled) null
                    else videoProfile?.takeIf { it in descriptor.videoProfiles }
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
                this.gpuPreviewEnabled = gpuPreview && !openCineLog
                var analysisPipeline: OpenCineLogGpuPipeline? = null
                if (openCineLog) {
                    val selectedLogProfile = activeLogProfile
                    if (!descriptor.supportsOpenCineLog || selectedLogProfile == null) {
                        listener.onFailure("log-capability-unavailable", "This camera has no capability-backed OCLog profile.", false)
                        return@Runnable
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
                            cameraTimestampRealtime = descriptor.timestampSourceRealtime,
                        onAnalysis = { analysis -> deliverGpuAnalysis(currentGeneration, { analysisPipeline }, analysis) },
                        onOperatorLutStatus = { status -> deliverGpuLutStatus(currentGeneration, { analysisPipeline }, status) },
                        onPreviewLost = { message -> listener.onPreviewSurfaceLost(message) },
                        ) { code, message -> listener.onFailure(code, message, false) }
                    } catch (failure: Throwable) {
                        observeFailedInitialization(failure)
                        listener.onFailure("log-gpu-init-failed", failure.cause?.message ?: failure.message ?: "The OCLog GPU path could not start.", false)
                        return@Runnable
                    }
                }
                if (gpuPreviewEnabled) {
                    try {
                        val size = activeVideoProfile?.size ?: descriptor.previewSize
                        logPipeline = OpenCineLogGpuPipeline(size, OpenCineLogSourcePath.SDR_BT709_ISP,
                            surface, descriptor.sensorOrientation, displayRotationDegrees,
                            descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
                            false, requestedTargetFps, passthroughSdr = true, appContext = appContext,
                            cameraTimestampRealtime = descriptor.timestampSourceRealtime,
                        onAnalysis = { analysis -> deliverGpuAnalysis(currentGeneration, { analysisPipeline }, analysis) },
                        onOperatorLutStatus = { status -> deliverGpuLutStatus(currentGeneration, { analysisPipeline }, status) },
                        onPreviewLost = { message -> listener.onPreviewSurfaceLost(message) },
                            onFailure = { code, message -> listener.onFailure(code, message, true) })
                        passthroughVideoPipeline = true
                    } catch (failure: Throwable) {
                        observeFailedInitialization(failure)
                        listener.onFailure("video-preview-gpu-init-failed", failure.message ?: "GPU preview initialization failed.", true)
                        return@Runnable
                    }
                }
                analysisPipeline = logPipeline
                synchronizeSubjectOutput()
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
                    cameraOpening = true
                    manager.openCamera(cameraIdToOpen, cameraExecutor, object : CameraDevice.StateCallback() {
                        override fun onOpened(device: CameraDevice) {
                            cameraOpening = false
                            if (disposed.get() || currentGeneration != generation) {
                                closeCameraDevice(device)
                                return
                            }
                            camera = device
                            if (openCineLog) configureLogSession(device, descriptor, currentGeneration)
                            else if (gpuPhotoPreviewEnabled) configureSession(device, descriptor,
                                requireNotNull(logPipeline).cameraInputSurface, currentGeneration)
                            else if (gpuPreviewEnabled) configureGpuPreviewSession(device, descriptor, requireNotNull(logPipeline), currentGeneration)
                            else if (activeVideoProfile?.constrainedHighSpeed == true) {
                                configureHighSpeedPreviewSession(
                                    device,
                                    descriptor,
                                    surface,
                                    currentGeneration,
                                )
                            } else configureSession(device, descriptor, surface, currentGeneration)
                        }

                        override fun onClosed(device: CameraDevice) {
                            if (closingCamera === device) closingCamera = null
                            runPendingPreviewStart()
                            finishCloseIfIdle()
                        }

                        override fun onDisconnected(device: CameraDevice) {
                            cameraOpening = false
                            closeCameraDevice(device)
                            if (currentGeneration == generation) {
                                camera = null
                                listener.onFailure("camera-disconnected", "The selected camera disconnected.", true)
                            }
                        }

                        override fun onError(device: CameraDevice, error: Int) {
                            cameraOpening = false
                            closeCameraDevice(device)
                            if (currentGeneration == generation) {
                                camera = null
                                listener.onFailure("camera-open-$error", "The selected camera could not be opened.", true)
                            }
                        }
                    })
                } catch (failure: Exception) {
                    cameraOpening = false
                    listener.onFailure("camera-open-exception", failure.message ?: "Camera open failed.", true)
                    finishCloseIfIdle()
                }
            }
            runPendingPreviewStart()
        }
    }

    /** The admission callback never waits on the UI, camera worker, or a native-window worker. */
    private fun acquireOwnerIfNeeded() {
        if (ownerLease != null) return
        val lease = ownerAdmission.acquire()
        ownerLease = lease
        lease.ready().whenComplete { _, failure ->
            cameraExecutor.execute {
                if (disposed.get()) return@execute
                if (failure != null) {
                    failNativeRetirement(failure)
                    pendingPreviewStart = null
                    listener?.onFailure("camera-owner-retirement-failed", failure.message ?: "The previous camera owner remains unretired.", false)
                } else {
                    ownerReady = true
                    runPendingPreviewStart()
                }
            }
        }
    }

    private fun runPendingPreviewStart() {
        if (disposed.get() || !ownerReady || retirementFailure != null || cameraOpening || closingCamera != null ||
            retiringPipelines.isNotEmpty() || initializationRetirements.isNotEmpty()) return
        val action = pendingPreviewStart ?: return
        pendingPreviewStart = null
        action.run()
    }

    fun attachSubjectPreview(token: Long, surface: Surface, options: SubjectPreviewOptions, onStatus: (SubjectPreviewStatus) -> Unit) {
        cameraExecutor.execute {
            subjectTarget = SubjectTarget(token, surface, options, onStatus)
            synchronizeSubjectOutput()
        }
    }

    fun updateSubjectPreview(token: Long, options: SubjectPreviewOptions) {
        cameraExecutor.execute {
            val target = subjectTarget?.takeIf { it.token == token } ?: return@execute
            subjectTarget = target.copy(options = options)
            synchronizeSubjectOutput()
        }
    }

    fun detachSubjectPreview(token: Long) {
        cameraExecutor.execute {
            if (subjectTarget?.token != token) return@execute
            subjectTarget = null
            synchronizeSubjectOutput()
        }
    }

    /** Camera executor only. Camera lifetime is independent of this optional output's lifetime. */
    private fun synchronizeSubjectOutput() {
        val target = subjectTarget
        val pipeline = logPipeline
        if (target != null && pipeline != null && subjectPipeline === pipeline && attachedSubjectToken == target.token) {
            pipeline.updateSubjectPreview(target.options)
            return
        }
        val attempt = ++subjectAttempt
        val callbackGeneration = generation
        subjectRetry?.let(imageHandler::removeCallbacks)
        subjectRetry = null
        subjectPipeline?.detachSubjectPreview()
        subjectPipeline = null
        attachedSubjectToken = null
        if (target == null) return
        if (pipeline == null) {
            target.onStatus(SubjectPreviewStatus())
            return
        }
        subjectPipeline = pipeline
        attachedSubjectToken = target.token
        fun attach(remaining: Int) {
            if (disposed.get() || callbackGeneration != generation || attempt != subjectAttempt || logPipeline !== pipeline || subjectTarget?.token != target.token) return
            if (!target.surface.isValid) return
            val accepted = pipeline.attachSubjectPreview(target.surface, subjectTarget?.options ?: target.options) { status ->
                runCatching { cameraExecutor.execute {
                    if (disposed.get() || callbackGeneration != generation || attempt != subjectAttempt || subjectTarget?.token != target.token || logPipeline !== pipeline) return@execute
                    // A fast-path options update can replace the service's callback/epoch while
                    // keeping this native output. Deliver to the current validated target.
                    val currentTarget = subjectTarget ?: return@execute
                    if (status.failureKind == SubjectPreviewFailure.BUSY && remaining > 0) {
                        currentTarget.onStatus(SubjectPreviewStatus())
                        subjectRetry = Runnable { runCatching { cameraExecutor.execute { attach(remaining - 1) } } }
                            .also { imageHandler.postDelayed(it, 100) }
                    } else currentTarget.onStatus(status)
                } }
            }
            if (!accepted) target.onStatus(SubjectPreviewStatus(failure = "Subject surface attachment was rejected."))
        }
        attach(50)
    }

    private fun configureGpuPreviewSession(device: CameraDevice, descriptor: Camera2CameraDescriptor, pipeline: OpenCineLogGpuPipeline, currentGeneration: Long) {
        val constrained = activeVideoProfile?.constrainedHighSpeed == true
        val input = pipeline.cameraInputSurface
        val configuration = SessionConfiguration(
            if (constrained) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(input)), cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (currentGeneration != generation || logPipeline !== pipeline || !gpuPreviewEnabled) { configured.close(); return }
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(input)
                            if (constrained) applyHighSpeedControls(this) else {
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                applyTargetFps(this, descriptor)
                                applyManualControls(this, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                        }
                        repeatingBuilder = builder
                        val callback = previewCaptureCallback(descriptor, true)
                        if (constrained) {
                            val highSpeed = configured as CameraConstrainedHighSpeedCaptureSession
                            highSpeed.setRepeatingBurstRequests(highSpeed.createHighSpeedRequestList(builder.build()), cameraExecutor, callback)
                        } else configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, callback)
                        notifyZoomRange()
                    } catch (failure: Throwable) {
                        listener?.onFailure("video-preview-gpu-request-failed", failure.message ?: "GPU preview request failed.", true)
                    }
                }
                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    if (currentGeneration == generation) listener?.onFailure("video-preview-gpu-session-failed", "The camera rejected the GPU preview stream.", true)
                }
            },
        )
        configureProcessingSession(configuration, device, CameraDevice.TEMPLATE_RECORD)
        device.createCaptureSession(configuration)
    }

    /** Starting/stopping the encoder leaves the preview camera session and subject leases intact. */
    private fun startExistingGpuVideo(output: ParcelFileDescriptor, audio: Camera2EmbeddedAudioConfig?, geometry: RecordingGeometry, bitrate: Int, timelapse: TimelapseCapture? = null, projectRateOverride: CaptureFrameRate? = null, separateAudioClock: CaptureEpochClock? = null, recordingLut: MonitorLut? = null): Boolean {
        val pipeline = logPipeline ?: return false
        if (recording || session == null || !gpuPreviewEnabled) return false
        val cameraGeneration = generation
        return pipeline.startRecording(output, bitrate, geometry, audio, minimumSensorTimestampNs = recordingWbMinimumTimestampNs,
            timelapse = timelapse, projectRateOverride = projectRateOverride, separateAudioClock = separateAudioClock,
            recordingLut = recordingLut,
            onRecordingLutApplied = { evidence ->
                if (generation == cameraGeneration && logPipeline === pipeline && recording) listener?.onRecordingLutApplied(evidence)
            },
            onTimelapseProgress = { if (generation == cameraGeneration && logPipeline === pipeline) listener?.onTimelapseProgress(it) },
            onEncodedProgress = { progress ->
                if (generation == cameraGeneration && logPipeline === pipeline) latestEncodedProgress = progress
            },
            onTimelapsePauseChanged = { status ->
                if (generation == cameraGeneration && logPipeline === pipeline) {
                    lastTimelapsePauseStatus = status
                    listener?.onTimelapsePauseChanged(status)
                }
            },
            onStarted = {
                check(generation == cameraGeneration && logPipeline === pipeline) { "GPU recording owner changed during preparation" }
                lastRecordingLutEvidence = null
                recording = true
                listener?.onRecordingStarted(geometry.encodedSize.width, geometry.encodedSize.height)
            },
            onStopped = { success, evidence ->
                cameraExecutor.execute {
                    if (generation != cameraGeneration || logPipeline !== pipeline) return@execute
                    evidence?.encodedProgress?.let { latestEncodedProgress = it }
                    lastCaptureEpochReport = evidence?.avTiming
                    lastRecordingLutEvidence = evidence?.recordingLut
                    recording = false
                    reportRecordingStopped(success, pipeline.recordingFileRetirement())
                }
            })
    }

    /** OFF disables photographic pulses, not the independently requested continuous torch. */
    fun captureJpeg(flash: PhotoFlashSelection = PhotoFlashSelection()): Boolean =
        captureStillInternal(StillPhotoFormat.JPEG, flash, 95, LegacyPhotoDelivery.JPEG)

    fun captureStill(format: StillPhotoFormat, flash: PhotoFlashSelection = PhotoFlashSelection(), quality: Int = 95,
        aspect: PhotoAspectSelection = PhotoAspectSelection()): Boolean =
        captureStillInternal(format, flash, quality, LegacyPhotoDelivery.NONE, aspect = aspect)

    private fun captureStillInternal(format: StillPhotoFormat, flash: PhotoFlashSelection, quality: Int, legacy: LegacyPhotoDelivery, bracket: BracketRequest? = null, accumulation: AccumulationRequest? = null, aspect: PhotoAspectSelection = PhotoAspectSelection(), burst: BurstRequest? = null): Boolean {
        require(quality in 1..100)
        if (disposed.get()) return false
        val raw = if (StillImageKind.DNG in format.requiredKinds) rawReader ?: return false else null
        val reader = if (format == StillPhotoFormat.DNG) requireNotNull(raw) else jpegReader ?: return false
        val expectedFormat = when (format) {
            StillPhotoFormat.HEIC -> ImageFormat.HEIC
            StillPhotoFormat.DNG -> ImageFormat.RAW_SENSOR
            else -> ImageFormat.JPEG
        }
        if (reader.imageFormat != expectedFormat || raw?.imageFormat?.let { it != ImageFormat.RAW_SENSOR } == true) return false
        val request = PhotoRequest(photoIds.incrementAndGet(), generation, camera ?: return false,
            session ?: return false, reader, activeDescriptor ?: return false,
            listener ?: return false, flash, format, quality, raw, legacy, bracket, bracket?.frames?.size ?: 0, accumulation, aspect, burst)
        synchronized(stillAdmissionLock) {
            if (bracketRequest.get() !== bracket || accumulationRequest.get() !== accumulation || burstRequest.get() !== burst || legacyStillRequest.get() != null || recording || recorderRequest.get() != null ||
                bracket != null && !ownsBracket(bracket) || accumulation != null && !ownsAccumulation(accumulation) ||
                burst != null && !ownsBurst(burst) || !photoRequest.compareAndSet(null, request)) return false
        }
        if (!ownsPhoto(request)) {
            photoRequest.compareAndSet(request, null)
            return false
        }
        cameraExecutor.execute {
            if (!ownsPhoto(request)) { retirePhoto(request, restore = false); return@execute }
            try {
                request.desiredOrientationDegrees = request.bracket?.aspectOrientationDegrees ?: request.burst?.orientationDegrees ?: jpegOrientation(request.descriptor)
                request.bracket?.let { if (it.aspectOrientationDegrees == null) it.aspectOrientationDegrees = request.desiredOrientationDegrees }
                request.burst?.let { if (it.orientationDegrees == null) it.orientationDegrees = request.desiredOrientationDegrees }
                val builder = request.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(request.reader.surface)
                    request.raw?.takeIf { it !== request.reader }?.surface?.let(::addTarget)
                    set(CaptureRequest.JPEG_QUALITY, request.quality.toByte())
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(request.descriptor))
                    val frozen = request.bracket?.controls ?: request.accumulation?.controls ?: request.burst?.controls
                    if (frozen != null) copyPhotoControls(frozen, this) else applyManualControls(this)
                    if (request.accumulation != null || request.aspect.enabled && request.format != StillPhotoFormat.DNG)
                        set(CaptureRequest.JPEG_ORIENTATION, 0)
                    setTag(PhotoTag(request.id))
                }
                request.burst?.let { if (it.controls == null) it.controls = builder.build() }
                request.accumulation?.let { group ->
                    if (group.controls == null) {
                        group.controls = builder.build()
                        group.outputOrientationDegrees = jpegOrientation(request.descriptor)
                        group.processor = AccumulationBitmapProcessor(group.selection, group.outputOrientationDegrees) {
                            if (!ownsAccumulation(group)) throw java.util.concurrent.CancellationException("Accumulation owner retired")
                        }
                    }
                }
                if (request.bracket != null) {
                    val group = request.bracket
                    if (group.controls == null) {
                        if (professionalExposure?.mode?.let { it != ExposureMode.AUTO } == true ||
                            builder.get(CaptureRequest.CONTROL_AE_MODE) != CaptureRequest.CONTROL_AE_MODE_ON) {
                            failPhoto(request, "bracket-exposure-unsupported", "Exposure bracketing requires automatic exposure.")
                            return@execute
                        }
                        group.controls = builder.build()
                    }
                    builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        requireNotNull(group.plan).exposures[request.bracketIndex].compensationIndex)
                }
                // Resolve from the same effective request snapshot, after earlier queued setters.
                // Legacy ISO+shutter also produces manual AE without professionalExposure.
                val exposureMode = photoExposureMode(professionalExposure?.mode, builder.get(CaptureRequest.CONTROL_AE_MODE))
                if (exposureMode == null && request.selection.mode != PhotoFlashMode.OFF) {
                    failPhoto(request, "photo-flash-rejected", "Photographic flash rejected: ${PhotoFlashRejection.EXPOSURE_UNSUPPORTED}")
                    return@execute
                }
                val resolved = when (val resolution = request.selection.resolve(request.descriptor.photoFlashCapabilities, exposureMode ?: ExposureMode.AUTO)) {
                    is PhotoFlashResolution.Plan -> resolution
                    is PhotoFlashResolution.Rejected -> {
                        failPhoto(request, "photo-flash-rejected", "Photographic flash rejected: ${resolution.reason}")
                        return@execute
                    }
                }
                val plan = photoStillPlan(request.selection, resolved,
                    builder.get(CaptureRequest.FLASH_MODE),
                    if (Build.VERSION.SDK_INT >= 35) builder.get(CaptureRequest.FLASH_STRENGTH_LEVEL) else null)
                applyPhotoPlan(builder, plan)
                val still = builder.build()
                request.plan = plan
                request.sequence = PhotoCaptureSequence(plan.needsPrecapture)
                request.still = still
                val exposureMs = (still.get(CaptureRequest.SENSOR_EXPOSURE_TIME) ?: 0L).coerceAtLeast(0L) / 1_000_000L
                val deadline = (8_000L + exposureMs.coerceAtMost(120_000L))
                request.deadlineUptimeMs = android.os.SystemClock.uptimeMillis() + deadline
                request.timeout = Runnable { cameraExecutor.execute {
                    if (ownsPhoto(request)) failPhoto(request, "photo-flash-timeout", "Photographic capture did not complete before its deadline.")
                } }.also { check(imageHandler.postDelayed(it, deadline)) { "Photo deadline owner is unavailable" } }
                if (request.bracket != null) startBracketMetering(request)
                else if (plan.needsPrecapture) startPhotoPrecapture(request, plan) else submitPhotoStill(request)
            } catch (failure: Exception) {
                failPhoto(request, "photo-flash-prepare-failed", failure.message ?: "Photographic capture preparation failed.")
            }
        }
        return true
    }

    /** Cancellation latches synchronously; queued callbacks cannot publish the cancelled image. */
    fun cancelJpeg(): Boolean = cancelStill()

    fun cancelStill(): Boolean {
        if (burstRequest.get() != null) return cancelBurst()
        accumulationRequest.get()?.let { owner ->
            if (!owner.cancelled.compareAndSet(false, true)) return false
            cameraExecutor.execute { retireAccumulation(owner, restore = true) }
            return true
        }
        if (bracketRequest.get() != null) return cancelBracket()
        val request = photoRequest.get() ?: return false
        if (!request.cancelled.compareAndSet(false, true)) return false
        cameraExecutor.execute { retirePhoto(request, restore = true) }
        return true
    }

    private fun ownsPhoto(request: PhotoRequest): Boolean = photoRequest.get() === request &&
        !request.cancelled.get() && (request.bracket == null || ownsBracket(request.bracket)) &&
        (request.accumulation == null || ownsAccumulation(request.accumulation)) &&
        (request.burst == null || ownsBurst(request.burst)) &&
        !disposed.get() && request.generation == generation &&
        request.device === camera && request.session === session &&
        request.reader === (if (request.format == StillPhotoFormat.DNG) rawReader else jpegReader) &&
        (request.raw == null || request.raw === rawReader)

    private fun samePhotoGraph(request: PhotoRequest): Boolean = !disposed.get() && request.generation == generation &&
        request.device === camera && request.session === session &&
        request.reader === (if (request.format == StillPhotoFormat.DNG) rawReader else jpegReader) &&
        (request.raw == null || request.raw === rawReader)

    /** Settings keep their latest intent/builder while a PHOTO owns the physical repeat. */
    private fun CameraCaptureSession.setPhotoAwareRepeatingRequest(
        request: CaptureRequest, executor: Executor, callback: CameraCaptureSession.CaptureCallback,
    ) {
        val burst = burstRequest.get()
        if (burst != null && sameBurstGraph(burst) && this === burst.session &&
            (request.tag !is PhotoTag || (request.tag as PhotoTag).id != photoRequest.get()?.id)) {
            burst.repeatingChanged = true
            return
        }
        val accumulation = accumulationRequest.get()
        if (accumulation != null && sameAccumulationGraph(accumulation) && this === accumulation.session &&
            (request.tag !is PhotoTag || (request.tag as PhotoTag).id != photoRequest.get()?.id)) {
            accumulation.repeatingChanged = true
            return
        }
        val group = bracketRequest.get()
        if (group != null && sameBracketGraph(group) && this === group.session &&
            (request.tag !is PhotoTag || (request.tag as PhotoTag).id != photoRequest.get()?.id)) {
            group.repeatingChanged = true
            return
        }
        val owner = photoRequest.get()
        if (owner != null && samePhotoGraph(owner) && this === owner.session &&
            (request.tag as? PhotoTag)?.id != owner.id) {
            owner.repeatingChanged = true
            return
        }
        setSingleRepeatingRequest(request, executor, callback)
    }

    private fun applyPhotoPlan(builder: CaptureRequest.Builder, plan: PhotoFlashResolution.Plan) {
        plan.aeMode?.let { builder.set(CaptureRequest.CONTROL_AE_MODE, it) }
        builder.set(CaptureRequest.FLASH_MODE, plan.flashMode)
        if (plan.needsPrecapture) builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        // applyManualControls may have written a TORCH level; never carry it into SINGLE.
        if (Build.VERSION.SDK_INT >= 35 && (activeDescriptor?.torchCapabilities?.adjustable == true ||
                activeDescriptor?.photoFlashCapabilities?.adjustable == true)) {
            builder.set(CaptureRequest.FLASH_STRENGTH_LEVEL, plan.strength)
        }
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
    }

    /** Display EGL windows never become Camera2 targets in a GPU photographic graph. */
    private fun targetPreviewCameraSurface(): Surface? =
        if (gpuPreviewEnabled || logPreviewEnabled) logPipeline?.cameraInputSurface else previewSurface

    private fun startPhotoPrecapture(request: PhotoRequest, plan: PhotoFlashResolution.Plan) {
        val preview = requireNotNull(targetPreviewCameraSurface())
        val builder = request.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            analysisReader?.surface?.let(::addTarget)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyTargetFps(this, request.descriptor)
            applyManualControls(this)
            applyPhotoPlan(this, photoMeteringRepeatPlan(plan))
            setTag(PhotoTag(request.id))
        }
        // This private request never replaces/mutates the user's repeatingBuilder or light intent.
        request.repeatingChanged = true
        val callback = photoPrecaptureCallback(request)
        request.session.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, callback)
        // SINGLE belongs only to the one-shot metering trigger and final still, never
        // every preview frame. Trigger and still share the exact frozen AE/flash/level.
        applyPhotoPlan(builder, plan)
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        builder.setTag(PhotoTag(request.id, trigger = true))
        request.session.captureSingleRequest(builder.build(), cameraExecutor, callback)
    }

    private fun photoPrecaptureCallback(owner: PhotoRequest) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (!ownsPhoto(owner) || session !== owner.session) return
            val tag = request.tag as? PhotoTag ?: return
            if (tag.id != owner.id) return
            val sequence = owner.sequence ?: return
            if (tag.trigger) sequence.triggerCompleted(result.frameNumber)
            val ae = when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)) {
                android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED -> PhotoCaptureSequence.Ae.CONVERGED
                android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> PhotoCaptureSequence.Ae.FLASH_REQUIRED
                android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> PhotoCaptureSequence.Ae.PRECAPTURE
                else -> PhotoCaptureSequence.Ae.OTHER
            }
            if (sequence.observe(result.frameNumber, ae,
                    result.get(android.hardware.camera2.CaptureResult.FLASH_STATE) == android.hardware.camera2.CaptureResult.FLASH_STATE_CHARGING)) {
                submitPhotoStill(owner)
            }
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            if (ownsPhoto(owner) && owner.sequence?.stage in setOf(PhotoCaptureSequence.Stage.TRIGGER_PENDING, PhotoCaptureSequence.Stage.METERING))
                failPhoto(owner, "photo-flash-precapture-failed", "Flash metering failed (${failure.reason}).")
        }
        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            if (ownsPhoto(owner) && owner.sequence?.stage in setOf(PhotoCaptureSequence.Stage.TRIGGER_PENDING, PhotoCaptureSequence.Stage.METERING))
                failPhoto(owner, "photo-flash-precapture-aborted", "Flash metering was aborted.")
        }
    }

    private fun submitPhotoStill(owner: PhotoRequest) {
        if (!ownsPhoto(owner)) return
        try {
            owner.stillSubmitted = true
            owner.repeatingChanged = true
            owner.session.captureSingleRequest(requireNotNull(owner.still), cameraExecutor,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        if (!ownsPhoto(owner) || session !== owner.session || (request.tag as? PhotoTag)?.id != owner.id) return
                        val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                        if (timestamp == null || owner.sequence?.result(timestamp) != true) {
                            failPhoto(owner, "photo-flash-result-invalid", "JPEG capture has no unique sensor timestamp.")
                            return
                        }
                        if (owner.bracket != null && result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) !=
                                request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)) {
                            failPhoto(owner, "bracket-compensation-mismatch", "The still result does not confirm the requested exposure compensation.")
                            return
                        }
                        owner.result = result
                        purgeUnmatchedRaw(owner, timestamp)
                        owner.report = PhotoFlashReport(owner.selection, request.get(CaptureRequest.CONTROL_AE_MODE),
                            requireNotNull(request.get(CaptureRequest.FLASH_MODE)), owner.plan?.strength,
                            result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE),
                            result.get(android.hardware.camera2.CaptureResult.FLASH_STATE),
                            if (Build.VERSION.SDK_INT >= 35) result.get(android.hardware.camera2.CaptureResult.FLASH_STRENGTH_LEVEL) else null,
                            timestamp)
                        deliverPhotoIfReady(owner)
                    }
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        if (ownsPhoto(owner)) failPhoto(owner, "photo-flash-capture-failed", "JPEG capture failed (${failure.reason}).")
                    }
                    override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                        if (ownsPhoto(owner)) failPhoto(owner, "photo-flash-capture-aborted", "JPEG capture was aborted.")
                    }
                })
        } catch (failure: Exception) {
            failPhoto(owner, "photo-flash-submit-failed", failure.message ?: "JPEG submission failed.")
        }
    }

    private fun deliverPhotoIfReady(owner: PhotoRequest) {
        if (!ownsPhoto(owner)) return
        val report = owner.report ?: return
        val timestamp = report.sensorTimestampNs ?: return
        compressedReadFailures.remove(timestamp)?.let {
            failPhoto(owner, "still-image-read-failed", it)
            return
        }
        val compressedKind = owner.format.requiredKinds.firstOrNull { it != StillImageKind.DNG }
        val compressed = if (compressedKind != null) pendingJpegs[timestamp]?.takeIf { it.kind == compressedKind } ?: return else null
        val raw = if (owner.raw != null) pendingRawFrames[timestamp] ?: return else null
        val parts = buildMap {
            compressed?.let { put(it.kind, timestamp) }
            raw?.let { put(StillImageKind.DNG, it.timestamp) }
        }
        if (!owner.format.matchesCompleteFrame(timestamp, parts)) return
        if (owner.accumulation != null) {
            completeAccumulationFrame(owner, requireNotNull(compressed))
            return
        }
        if (owner.burst != null && compressed != null &&
            compressed.bytes.size.toLong() > CapturedBurst.MAX_ENCODED_BYTES - owner.burst.encodedBytes) {
            failPhoto(owner, "burst-byte-limit", "The complete burst exceeds its encoded byte budget.")
            return
        }
        if (owner.bracket != null && compressed != null &&
            compressed.bytes.size.toLong() > CapturedBracket.MAX_ENCODED_BYTES - owner.bracket.encodedBytes) {
            failPhoto(owner, "bracket-byte-limit", "The complete bracket exceeds its encoded byte budget.")
            return
        }
        val images = try {
            buildList<StillImagePayload> {
                compressed?.let {
                    val checkRunning = {
                        if (!ownsPhoto(owner)) throw java.util.concurrent.CancellationException("Aspect crop owner retired")
                        // The codec runs synchronously on the owner queue; its polling must also
                        // observe the existing request deadline while that queue is occupied.
                        if (it.kind == StillImageKind.HEIC && android.os.SystemClock.uptimeMillis() >= owner.deadlineUptimeMs)
                            throw java.util.concurrent.TimeoutException("HEIC processing exceeded the still request deadline")
                    }
                    add(if (!owner.aspect.enabled) StillImagePayload.owned(it.kind, it.bytes, it.width, it.height)
                    else if (it.kind == StillImageKind.HEIC) cropPhotoAspectHeic(it.bytes, owner.aspect,
                        owner.desiredOrientationDegrees, owner.quality, java.io.File(appContext.cacheDir, "photo-aspect"), checkRunning)
                    else cropPhotoAspectJpeg(it.bytes, owner.aspect, owner.desiredOrientationDegrees, owner.quality, checkRunning))
                }
                raw?.let { image ->
                    pendingRawFrames.remove(timestamp)
                    // Ownership starts immediately after removal, before any Image getter,
                    // output allocation, characteristics lookup or DNG constructor can fail.
                    try {
                        val width = image.width
                        val height = image.height
                        val orientation = if (owner.aspect.enabled) owner.desiredOrientationDegrees
                            else requireNotNull(owner.still).get(CaptureRequest.JPEG_ORIENTATION) ?: 0
                        val output = BoundedDngOutput()
                        val creator = DngCreator(manager.getCameraCharacteristics(owner.descriptor.cameraId), requireNotNull(owner.result))
                        try {
                            creator.setOrientation(when (orientation) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 })
                            creator.writeImage(output, image)
                        } finally { creator.close() }
                        add(StillImagePayload.owned(StillImageKind.DNG, output.toByteArray(), width, height,
                            owner.aspect.takeIf { it.enabled }?.rawReport(width, height, orientation)))
                    } finally { image.close() }
                }
            }
        } catch (failure: Exception) {
            failPhoto(owner, "still-encode-failed", failure.message ?: "The complete still capture could not be encoded.")
            return
        }
        if (owner.bracket != null && images.sumOf { it.byteCount.toLong() } > CapturedBracket.MAX_ENCODED_BYTES - owner.bracket.encodedBytes) {
            failPhoto(owner, "bracket-byte-limit", "The processed bracket exceeds its encoded byte budget.")
            return
        }
        if (owner.burst != null && images.sumOf { it.byteCount.toLong() } > CapturedBurst.MAX_ENCODED_BYTES - owner.burst.encodedBytes) {
            failPhoto(owner, "burst-byte-limit", "The processed burst exceeds its encoded byte budget.")
            return
        }
        val capture = CapturedStill(owner.id, timestamp,
            if (owner.aspect.enabled) { if (owner.raw != null) owner.desiredOrientationDegrees else 0 }
            else requireNotNull(owner.still).get(CaptureRequest.JPEG_ORIENTATION) ?: 0,
            owner.quality, report, images, owner.aspect)
        if (owner.sequence?.complete(timestamp) != true) return
        // Linearize delivery against public cancellation before releasing the reservation.
        if (!owner.cancelled.compareAndSet(false, true)) { retirePhoto(owner, restore = true); return }
        if (!retirePhoto(owner, restore = owner.bracket == null && owner.burst == null) || !samePhotoGraph(owner)) return
        if (owner.burst != null) {
            completeBurstFrame(owner, capture)
            return
        }
        if (owner.bracket != null) {
            completeBracketFrame(owner, capture, capture.images.single().byteCount)
            return
        }
        when (owner.legacy) {
            LegacyPhotoDelivery.NONE -> owner.listener.onStillCaptured(capture)
            LegacyPhotoDelivery.JPEG -> {
                owner.listener.onPhotoFlashResult(report)
                val image = capture.images.single()
                owner.listener.onJpegCaptured(image.bytes, image.width, image.height)
            }
            LegacyPhotoDelivery.DNG -> {
                val image = capture.images.single()
                owner.listener.onDngCaptured(image.bytes, image.width, image.height)
            }
        }
    }

    private fun retirePhoto(owner: PhotoRequest, restore: Boolean): Boolean {
        if (photoRequest.get() !== owner) return false
        owner.timeout?.let(imageHandler::removeCallbacks)
        owner.timeout = null
        val completed = owner.sequence?.stage == PhotoCaptureSequence.Stage.COMPLETE
        owner.sequence?.cancel()
        owner.report?.sensorTimestampNs?.let { removeCompressed(it); compressedReadFailures.remove(it) }
        clearPendingRaw()
        var restorationFailure: Exception? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (failure: Exception) {
                if (restorationFailure == null) restorationFailure = failure else restorationFailure?.addSuppressed(failure)
            }
        }
        if (restore && samePhotoGraph(owner) && owner.repeatingChanged) {
            if (!completed && owner.stillSubmitted) attempt { owner.session.abortCaptures() }
            if (!completed && owner.plan?.needsPrecapture == true) attempt {
                val cancel = owner.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(requireNotNull(targetPreviewCameraSurface()))
                    analysisReader?.surface?.let(::addTarget)
                    applyManualControls(this)
                    set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL)
                }.build()
                owner.session.captureSingleRequest(cancel, cameraExecutor, object : CameraCaptureSession.CaptureCallback() {})
            }
            // Even failed abort/CANCEL submission must not skip ordinary preview restoration.
            attempt {
                val builder = requireNotNull(repeatingBuilder)
                applyManualControls(builder)
                owner.session.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(owner.descriptor, false))
            }
        }
        photoRequest.compareAndSet(owner, null)
        restorationFailure?.let {
            owner.listener.onFailure("photo-flash-restore-failed", it.message ?: "Preview light restoration failed.", true)
        }
        return restorationFailure == null
    }

    private fun failPhoto(owner: PhotoRequest, code: String, message: String) {
        val notify = ownsPhoto(owner)
        val restored = retirePhoto(owner, restore = true)
        owner.bracket?.let { retireBracket(it, restore = true) }
        owner.accumulation?.let { retireAccumulation(it, restore = true) }
        owner.burst?.let { retireBurst(it, restore = true) }
        if (notify && restored) owner.listener.onFailure(when {
            owner.burst != null && !code.startsWith("burst-") -> "burst-$code"
            owner.accumulation != null && !code.startsWith("accumulation-") -> "accumulation-$code"
            owner.bracket != null && !code.startsWith("bracket-") -> "bracket-$code"
            else -> code
        }, message, true)
    }

    private fun removeCompressed(timestamp: Long): JpegPayload? = pendingJpegs.remove(timestamp)?.also {
        compressedHandoff.release(it.ticket)
    }

    private fun receiveJpeg(reader: ImageReader, readerGeneration: Long, timestamp: Long, payload: JpegPayload) {
        if (disposed.get() || reader !== jpegReader || readerGeneration != generation) {
            compressedHandoff.release(payload.ticket)
            return
        }
        if (payload.kind == StillImageKind.JPEG && legacyJpegTimestamps.remove(timestamp)) {
            try { listener?.onJpegCaptured(payload.bytes, payload.width, payload.height) }
            finally { compressedHandoff.release(payload.ticket) }
            return
        }
        pendingJpegs.put(timestamp, payload)?.let { compressedHandoff.release(it.ticket) }
        while (pendingJpegs.size > MAX_BURST_IMAGES + 2) removeCompressed(pendingJpegs.keys.first())
        photoRequest.get()?.let(::deliverPhotoIfReady)
    }

    private fun receiveCompressedFailure(reader: ImageReader, readerGeneration: Long, timestamp: Long, message: String) {
        if (disposed.get() || reader !== jpegReader || readerGeneration != generation) return
        if (legacyJpegTimestamps.remove(timestamp)) {
            listener?.onFailure("still-image-read-failed", message, true)
            return
        }
        compressedReadFailures[timestamp] = message
        while (compressedReadFailures.size > MAX_BURST_IMAGES + 2) compressedReadFailures.remove(compressedReadFailures.keys.first())
        photoRequest.get()?.let(::deliverPhotoIfReady)
    }

    private fun legacyJpegStarted(reader: ImageReader, readerGeneration: Long, timestamp: Long) {
        if (disposed.get() || reader !== jpegReader || readerGeneration != generation || reader.imageFormat != ImageFormat.JPEG) return
        compressedReadFailures.remove(timestamp)?.let {
            listener?.onFailure("still-image-read-failed", it, true)
            return
        }
        val payload = pendingJpegs.remove(timestamp)
        if (payload != null) {
            try { listener?.onJpegCaptured(payload.bytes, payload.width, payload.height) }
            finally { compressedHandoff.release(payload.ticket) }
        }
        else {
            legacyJpegTimestamps += timestamp
            while (legacyJpegTimestamps.size > MAX_BURST_IMAGES + 2) legacyJpegTimestamps.remove(legacyJpegTimestamps.first())
        }
    }

    fun captureDng(): Boolean =
        captureStillInternal(StillPhotoFormat.DNG, PhotoFlashSelection(), 95, LegacyPhotoDelivery.DNG)

    /** JPEG exposures remain separate; no partial bracket is delivered or merged. */
    fun captureBracket(selection: BracketSelection = BracketSelection(), quality: Int = 95,
        aspect: PhotoAspectSelection = PhotoAspectSelection()): Boolean {
        require(quality in 1..100)
        val owner = BracketRequest(photoIds.incrementAndGet(), generation, camera ?: return false,
            session ?: return false, jpegReader?.takeIf { it.imageFormat == ImageFormat.JPEG } ?: return false,
            activeDescriptor ?: return false, listener ?: return false, selection, quality, aspect)
        synchronized(stillAdmissionLock) {
            if (disposed.get() || recording || recorderRequest.get() != null || photoRequest.get() != null ||
                accumulationRequest.get() != null || burstRequest.get() != null || legacyStillRequest.get() != null || (gpuPreviewEnabled && !gpuPhotoPreviewEnabled) || logPreviewEnabled ||
                !bracketRequest.compareAndSet(null, owner)) return false
        }
        cameraExecutor.execute {
            if (!ownsBracket(owner)) { retireBracket(owner, restore = false); return@execute }
            val descriptor = owner.descriptor
            when (val plan = selection.resolve(descriptor.aeCompensationRange?.lower, descriptor.aeCompensationRange?.upper,
                descriptor.aeCompensationStepNumerator, descriptor.aeCompensationStepDenominator,
                CaptureRequest.CONTROL_AE_MODE_ON in descriptor.photoFlashCapabilities.aeModes)) {
                is BracketResolution.Rejected -> {
                    failBracket(owner, "bracket-rejected", "Exposure bracket rejected: ${plan.reason}")
                    return@execute
                }
                is BracketResolution.Plan -> owner.plan = plan
            }
            owner.timeout = Runnable { cameraExecutor.execute {
                if (ownsBracket(owner)) failBracket(owner, "bracket-timeout", "The exposure bracket did not finish before its deadline.")
            } }
            if (!imageHandler.postDelayed(requireNotNull(owner.timeout), selection.count * 9_000L)) {
                failBracket(owner, "bracket-deadline-unavailable", "The bracket deadline owner is unavailable.")
                return@execute
            }
            owner.listener.onBracketProgress(0, selection.count)
            startBracketFrame(owner)
        }
        return true
    }

    fun cancelBracket(): Boolean {
        val owner = bracketRequest.get() ?: return false
        if (!owner.cancelled.compareAndSet(false, true)) return false
        cameraExecutor.execute { retireBracket(owner, restore = true) }
        return true
    }

    private fun sameBracketGraph(owner: BracketRequest): Boolean = !disposed.get() && owner.generation == generation &&
        owner.device === camera && owner.session === session && owner.reader === jpegReader

    private fun ownsBracket(owner: BracketRequest): Boolean = bracketRequest.get() === owner &&
        !owner.cancelled.get() && sameBracketGraph(owner)

    private fun startBracketFrame(owner: BracketRequest) {
        if (!ownsBracket(owner)) { retireBracket(owner, restore = true); return }
        if (!captureStillInternal(StillPhotoFormat.JPEG, PhotoFlashSelection(), owner.quality, LegacyPhotoDelivery.NONE, owner, aspect = owner.aspect))
            failBracket(owner, "bracket-frame-unavailable", "The next exposure could not acquire its native still request.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyPhotoControls(source: CaptureRequest, target: CaptureRequest.Builder) {
        source.keys.forEach { key ->
            val typed = key as CaptureRequest.Key<Any>
            target.set(typed, source.get(typed))
        }
    }

    private fun startBracketMetering(owner: PhotoRequest) {
        val group = requireNotNull(owner.bracket)
        val compensation = requireNotNull(group.plan).exposures[owner.bracketIndex].compensationIndex
        val gate = BracketMeteringGate(compensation)
        val request = owner.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            copyPhotoControls(requireNotNull(owner.still), this)
            addTarget(requireNotNull(targetPreviewCameraSurface()))
            analysisReader?.surface?.let(::addTarget)
            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
            setTag(PhotoTag(owner.id))
        }.build()
        group.repeatingChanged = true
        owner.repeatingChanged = true
        owner.session.setPhotoAwareRepeatingRequest(request, cameraExecutor, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (!ownsPhoto(owner) || session !== owner.session || (request.tag as? PhotoTag)?.id != owner.id) return
                if (gate.observe(result.frameNumber,
                        result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
                        result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE))) submitPhotoStill(owner)
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                if (ownsPhoto(owner) && !owner.stillSubmitted)
                    failPhoto(owner, "bracket-metering-failed", "Exposure bracket metering failed (${failure.reason}).")
            }
            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                if (ownsPhoto(owner) && !owner.stillSubmitted)
                    failPhoto(owner, "bracket-metering-aborted", "Exposure bracket metering was aborted.")
            }
        })
    }

    private fun completeBracketFrame(owner: PhotoRequest, capture: CapturedStill, encodedBytes: Int) {
        val group = requireNotNull(owner.bracket)
        if (!ownsBracket(group)) { retireBracket(group, restore = true); return }
        try {
            val exposure = requireNotNull(group.plan).exposures[owner.bracketIndex]
            val result = requireNotNull(owner.result)
            check(group.frames.lastOrNull()?.capture?.sensorTimestampNs?.let { it < capture.sensorTimestampNs } != false)
            group.frames += BracketFrame(owner.bracketIndex, exposure.ev, exposure.compensationIndex,
                result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
                result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
                result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY), capture)
            group.encodedBytes += encodedBytes
            group.listener.onBracketProgress(group.frames.size, group.selection.count)
            if (!ownsBracket(group)) { retireBracket(group, restore = true); return }
            if (group.frames.size < group.selection.count) { startBracketFrame(group); return }
            val completed = CapturedBracket(group.id, group.selection, group.frames)
            if (!group.cancelled.compareAndSet(false, true)) { retireBracket(group, restore = true); return }
            if (retireBracket(group, restore = true) && sameBracketGraph(group)) group.listener.onBracketCaptured(completed)
        } catch (failure: Exception) {
            failBracket(group, "bracket-result-invalid", failure.message ?: "The complete exposure bracket is invalid.")
        }
    }

    private fun retireBracket(owner: BracketRequest, restore: Boolean): Boolean {
        if (bracketRequest.get() !== owner) return false
        owner.cancelled.set(true)
        owner.timeout?.let(imageHandler::removeCallbacks)
        owner.timeout = null
        val active = photoRequest.get()?.takeIf { it.bracket === owner }
        val photoRestored = active?.let { retirePhoto(it, restore) } ?: true
        var failure: Exception? = null
        if (restore && photoRestored && owner.repeatingChanged && sameBracketGraph(owner)) {
            try {
                val builder = requireNotNull(repeatingBuilder)
                applyManualControls(builder)
                owner.session.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(owner.descriptor, false))
            } catch (problem: Exception) { failure = problem }
        }
        owner.frames.clear()
        owner.controls = null
        owner.encodedBytes = 0L
        bracketRequest.compareAndSet(owner, null)
        failure?.let { owner.listener.onFailure("bracket-restore-failed", it.message ?: "Preview restoration failed.", true) }
        return photoRestored && failure == null
    }

    private fun failBracket(owner: BracketRequest, code: String, message: String) {
        val notify = ownsBracket(owner)
        if (retireBracket(owner, restore = true) && notify) owner.listener.onFailure(code, message, true)
    }

    /** Incremental computational accumulation; BULB is not a physical continuous exposure. */
    fun captureAccumulation(selection: AccumulationSelection, quality: Int = 95,
        aspect: PhotoAspectSelection = PhotoAspectSelection()): Boolean {
        require(quality in 1..100)
        val owner = AccumulationRequest(photoIds.incrementAndGet(), generation, camera ?: return false,
            session ?: return false, jpegReader?.takeIf { it.imageFormat == ImageFormat.JPEG } ?: return false,
            activeDescriptor ?: return false, listener ?: return false, selection, quality, aspect)
        synchronized(stillAdmissionLock) {
            if (disposed.get() || recording || recorderRequest.get() != null || photoRequest.get() != null ||
                bracketRequest.get() != null || burstRequest.get() != null || legacyStillRequest.get() != null || (gpuPreviewEnabled && !gpuPhotoPreviewEnabled) || logPreviewEnabled ||
                !accumulationRequest.compareAndSet(null, owner)) return false
        }
        cameraExecutor.execute {
            if (!ownsAccumulation(owner)) { retireAccumulation(owner, restore = false); return@execute }
            owner.startedAtMs = android.os.SystemClock.elapsedRealtime()
            owner.timeout = Runnable { cameraExecutor.execute {
                if (ownsAccumulation(owner)) failAccumulation(owner, "accumulation-timeout", "Accumulation did not complete before its deadline.")
            } }
            // The target stops new frames; allow the bounded in-flight still to retire, not a
            // fictitious shutter cut at the requested wall-clock duration.
            if (!imageHandler.postDelayed(requireNotNull(owner.timeout), selection.durationMs + 130_000L)) {
                failAccumulation(owner, "accumulation-deadline-unavailable", "The accumulation deadline owner is unavailable.")
                return@execute
            }
            owner.listener.onAccumulationProgress(0, 0, selection.durationMs)
            startAccumulationFrame(owner)
        }
        return true
    }

    /** Finish preserves at least two already completed inputs; cancelStill instead discards. */
    fun finishAccumulation(): Boolean {
        val owner = accumulationRequest.get() ?: return false
        if (!ownsAccumulation(owner) || owner.completedFrames < 2 || owner.finishing ||
            !owner.finishRequested.compareAndSet(false, true)) return false
        cameraExecutor.execute {
            if (!ownsAccumulation(owner)) return@execute
            if (photoRequest.get()?.accumulation !== owner) finishAccumulationGroup(owner)
        }
        return true
    }

    private fun sameAccumulationGraph(owner: AccumulationRequest): Boolean = !disposed.get() && owner.generation == generation &&
        owner.device === camera && owner.session === session && owner.reader === jpegReader

    private fun ownsAccumulation(owner: AccumulationRequest): Boolean = accumulationRequest.get() === owner &&
        !owner.cancelled.get() && sameAccumulationGraph(owner)

    private fun accumulationElapsed(owner: AccumulationRequest): Long =
        (android.os.SystemClock.elapsedRealtime() - owner.startedAtMs).coerceAtLeast(0L)

    private fun startAccumulationFrame(owner: AccumulationRequest) {
        if (!ownsAccumulation(owner)) { retireAccumulation(owner, restore = true); return }
        owner.tick?.let(imageHandler::removeCallbacks)
        owner.tick = null
        if (owner.finishRequested.get() || accumulationElapsed(owner) >= owner.selection.durationMs ||
            owner.frames.size == CapturedAccumulation.MAX_FRAMES) {
            finishAccumulationGroup(owner)
            return
        }
        owner.lastFrameStartedAtMs = android.os.SystemClock.elapsedRealtime()
        if (!captureStillInternal(StillPhotoFormat.JPEG, PhotoFlashSelection(), owner.quality, LegacyPhotoDelivery.NONE,
                accumulation = owner)) failAccumulation(owner, "accumulation-frame-unavailable", "The next accumulation frame is unavailable.")
    }

    private fun completeAccumulationFrame(owner: PhotoRequest, payload: JpegPayload) {
        val group = requireNotNull(owner.accumulation)
        if (!ownsPhoto(owner)) return
        try {
            require(payload.kind == StillImageKind.JPEG && payload.bytes.size <= CapturedAccumulation.MAX_ENCODED_BYTES)
            val result = requireNotNull(owner.result)
            val timestamp = requireNotNull(owner.report?.sensorTimestampNs)
            check(group.frames.lastOrNull()?.sensorTimestampNs?.let { it < timestamp } != false)
            group.repeatingChanged = true
            requireNotNull(group.processor).add(payload.bytes)
            if (!ownsPhoto(owner)) { retireAccumulation(group, restore = true); return }
            val frame = AccumulationFrame(group.frames.size, owner.id, timestamp,
                result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
                result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY))
            if (owner.sequence?.complete(timestamp) != true || !owner.cancelled.compareAndSet(false, true)) {
                retireAccumulation(group, restore = true)
                return
            }
            if (!retirePhoto(owner, restore = false)) { retireAccumulation(group, restore = true); return }
            group.frames += frame
            group.completedFrames = group.frames.size
            val elapsed = accumulationElapsed(group)
            group.listener.onAccumulationProgress(group.completedFrames, elapsed, group.selection.durationMs)
            if (!ownsAccumulation(group)) { retireAccumulation(group, restore = true); return }
            if (group.finishRequested.get() || elapsed >= group.selection.durationMs || group.frames.size == CapturedAccumulation.MAX_FRAMES) {
                finishAccumulationGroup(group)
                return
            }
            val untilNext = (group.lastFrameStartedAtMs + group.selection.intervalMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val untilTarget = (group.selection.durationMs - accumulationElapsed(group)).coerceAtLeast(0L)
            group.tick = Runnable { cameraExecutor.execute { startAccumulationFrame(group) } }
            check(imageHandler.postDelayed(requireNotNull(group.tick), minOf(untilNext, untilTarget))) { "Accumulation scheduler unavailable" }
        } catch (failure: Exception) {
            failAccumulation(group, "accumulation-image-processing-failed", failure.message ?: "Accumulation frame processing failed.")
        }
    }

    private fun finishAccumulationGroup(owner: AccumulationRequest) {
        if (!ownsAccumulation(owner) || owner.finishing) return
        if (owner.frames.size < 2) {
            failAccumulation(owner, "accumulation-insufficient-frames", "Accumulation requires at least two completed frames.")
            return
        }
        owner.finishing = true
        owner.tick?.let(imageHandler::removeCallbacks)
        owner.tick = null
        try {
            val image = requireNotNull(owner.processor).finish(owner.quality, owner.aspect)
            val capture = CapturedAccumulation(owner.id, owner.selection, owner.frames, image, 0, owner.quality, owner.finishRequested.get())
            if (!owner.cancelled.compareAndSet(false, true)) { retireAccumulation(owner, restore = true); return }
            if (retireAccumulation(owner, restore = true) && sameAccumulationGraph(owner)) owner.listener.onAccumulationCaptured(capture)
        } catch (failure: Exception) {
            failAccumulation(owner, "accumulation-result-invalid", failure.message ?: "Accumulation output could not be encoded.")
        }
    }

    private fun retireAccumulation(owner: AccumulationRequest, restore: Boolean): Boolean {
        if (accumulationRequest.get() !== owner) return false
        owner.cancelled.set(true)
        owner.timeout?.let(imageHandler::removeCallbacks)
        owner.tick?.let(imageHandler::removeCallbacks)
        owner.timeout = null
        owner.tick = null
        val active = photoRequest.get()?.takeIf { it.accumulation === owner }
        val photoRestored = active?.let { retirePhoto(it, restore) } ?: true
        var failure: Exception? = null
        if (restore && photoRestored && owner.repeatingChanged && sameAccumulationGraph(owner)) {
            try {
                val builder = requireNotNull(repeatingBuilder)
                applyManualControls(builder)
                owner.session.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(owner.descriptor, false))
            } catch (problem: Exception) { failure = problem }
        }
        owner.processor?.close()
        owner.processor = null
        owner.controls = null
        owner.frames.clear()
        owner.completedFrames = 0
        accumulationRequest.compareAndSet(owner, null)
        failure?.let { owner.listener.onFailure("accumulation-restore-failed", it.message ?: "Preview restoration failed.", true) }
        return photoRestored && failure == null
    }

    private fun failAccumulation(owner: AccumulationRequest, code: String, message: String) {
        val notify = ownsAccumulation(owner)
        if (retireAccumulation(owner, restore = true) && notify) owner.listener.onFailure(code, message, true)
    }

    private fun reserveLegacyStill(): Any? = synchronized(stillAdmissionLock) {
        if (disposed.get() || bracketRequest.get() != null || accumulationRequest.get() != null || burstRequest.get() != null || photoRequest.get() != null ||
            recording || recorderRequest.get() != null) null
        else Any().takeIf { legacyStillRequest.compareAndSet(null, it) }
    }

    /** Complete sequential JPEG burst; native captureBurst cadence/FPS is not promised. */
    fun captureBurst(count: Int, aspect: PhotoAspectSelection = PhotoAspectSelection(), quality: Int = 95): Boolean {
        require(quality in 1..100)
        if (count !in CapturedBurst.MIN_FRAMES..CapturedBurst.MAX_FRAMES) return false
        val owner = BurstRequest(photoIds.incrementAndGet(), generation, camera ?: return false,
            session ?: return false, jpegReader?.takeIf { it.imageFormat == ImageFormat.JPEG } ?: return false,
            activeDescriptor ?: return false, listener ?: return false, count, quality, aspect)
        synchronized(stillAdmissionLock) {
            if (disposed.get() || recording || recorderRequest.get() != null || photoRequest.get() != null ||
                bracketRequest.get() != null || accumulationRequest.get() != null || legacyStillRequest.get() != null ||
                (gpuPreviewEnabled && !gpuPhotoPreviewEnabled) || logPreviewEnabled || !burstRequest.compareAndSet(null, owner)) return false
        }
        cameraExecutor.execute {
            if (!ownsBurst(owner)) { retireBurst(owner, restore = false); return@execute }
            owner.timeout = Runnable { cameraExecutor.execute {
                if (ownsBurst(owner)) failBurst(owner, "burst-timeout", "The complete burst did not finish before its deadline.")
            } }
            // Each PhotoRequest has its own 8s + bounded exposure deadline. The group also
            // has an absolute ceiling, including processing and gaps between those owners.
            if (!imageHandler.postDelayed(requireNotNull(owner.timeout), count * 129_000L)) {
                failBurst(owner, "burst-deadline-unavailable", "The burst deadline owner is unavailable.")
                return@execute
            }
            owner.listener.onBurstProgress(0, count)
            startBurstFrame(owner)
        }
        return true
    }

    fun cancelBurst(): Boolean {
        val owner = burstRequest.get() ?: return false
        if (!owner.cancelled.compareAndSet(false, true)) return false
        cameraExecutor.execute { retireBurst(owner, restore = true) }
        return true
    }

    private fun sameBurstGraph(owner: BurstRequest): Boolean = !disposed.get() && owner.generation == generation &&
        owner.device === camera && owner.session === session && owner.reader === jpegReader

    private fun ownsBurst(owner: BurstRequest): Boolean = burstRequest.get() === owner &&
        !owner.cancelled.get() && sameBurstGraph(owner)

    private fun startBurstFrame(owner: BurstRequest) {
        if (!ownsBurst(owner)) { retireBurst(owner, restore = true); return }
        if (!captureStillInternal(StillPhotoFormat.JPEG, PhotoFlashSelection(), owner.quality, LegacyPhotoDelivery.NONE,
                aspect = owner.aspect, burst = owner)) failBurst(owner, "burst-frame-unavailable", "The next burst frame could not acquire a still request.")
    }

    private fun completeBurstFrame(owner: PhotoRequest, capture: CapturedStill) {
        val group = requireNotNull(owner.burst)
        if (!ownsBurst(group)) { retireBurst(group, restore = true); return }
        try {
            val result = requireNotNull(owner.result)
            check(group.frames.lastOrNull()?.capture?.sensorTimestampNs?.let { it < capture.sensorTimestampNs } != false)
            group.frames += BurstFrame(group.frames.size, capture,
                result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
                result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY))
            group.encodedBytes += capture.images.single().byteCount
            group.repeatingChanged = true
            group.listener.onBurstProgress(group.frames.size, group.count)
            if (!ownsBurst(group)) { retireBurst(group, restore = true); return }
            if (group.frames.size < group.count) { startBurstFrame(group); return }
            val completed = CapturedBurst(group.id, group.count, group.frames, group.quality, group.aspect)
            if (!group.cancelled.compareAndSet(false, true)) { retireBurst(group, restore = true); return }
            if (retireBurst(group, restore = true) && sameBurstGraph(group)) group.listener.onBurstCaptured(completed)
        } catch (failure: Exception) {
            failBurst(group, "burst-result-invalid", failure.message ?: "The complete burst is invalid.")
        }
    }

    private fun retireBurst(owner: BurstRequest, restore: Boolean): Boolean {
        if (burstRequest.get() !== owner) return false
        owner.cancelled.set(true)
        owner.timeout?.let(imageHandler::removeCallbacks)
        owner.timeout = null
        val active = photoRequest.get()?.takeIf { it.burst === owner }
        val photoRestored = active?.let { retirePhoto(it, restore) } ?: true
        var failure: Exception? = null
        if (restore && photoRestored && owner.repeatingChanged && sameBurstGraph(owner)) {
            try {
                val builder = requireNotNull(repeatingBuilder)
                applyManualControls(builder)
                owner.session.setSingleRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(owner.descriptor, false))
            } catch (problem: Exception) { failure = problem }
        }
        owner.frames.clear()
        owner.controls = null
        owner.encodedBytes = 0L
        burstRequest.compareAndSet(owner, null)
        failure?.let { owner.listener.onFailure("burst-restore-failed", it.message ?: "Preview restoration failed.", true) }
        return photoRestored && failure == null
    }

    private fun failBurst(owner: BurstRequest, code: String, message: String) {
        val notify = ownsBurst(owner)
        if (retireBurst(owner, restore = true) && notify) owner.listener.onFailure(code, message, true)
    }

    fun captureLongExposure(): Boolean {
        val device = camera ?: return false
        val currentSession = session ?: return false
        val reader = jpegReader?.takeIf { it.imageFormat == ImageFormat.JPEG } ?: return false
        val descriptor = activeDescriptor ?: return false
        val readerGeneration = generation
        val admission = reserveLegacyStill() ?: return false
        cameraExecutor.execute {
            if (disposed.get() || generation != readerGeneration || device !== camera || currentSession !== session || reader !== jpegReader) {
                legacyStillRequest.compareAndSet(admission, null)
                return@execute
            }
            try {
                val exposure = descriptor.exposureTimeRangeNs?.let { 1_000_000_000L.coerceIn(it.lower, it.upper) } ?: 1_000_000_000L
                val iso = descriptor.sensitivityRange?.lower ?: 100
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    applyImageProcessing(this)
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(descriptor))
                }.build()
                currentSession.captureSingleRequest(request, cameraExecutor, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                        legacyJpegStarted(reader, readerGeneration, timestamp)
                    }
                    override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                        legacyStillRequest.compareAndSet(admission, null)
                    }
                    override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                        legacyStillRequest.compareAndSet(admission, null)
                    }
                })
            } catch (failure: Exception) {
                legacyStillRequest.compareAndSet(admission, null)
                listener?.onFailure("long-exposure-failed", failure.message ?: "Long exposure failed.", true)
            }
        }
        return true
    }

    fun setManualControls(iso: Int?, exposureTimeNs: Long?, focusDiopters: Float?) {
        cameraExecutor.execute {
            if (focusDiopters != null && !focusDiopters.isFinite()) {
                listener?.onProfessionalControlsRejected("Manual focus must be finite.")
                return@execute
            }
            // Returning to autofocus is also an explicit focus override.
            cancelFocusPullLocked()
            val descriptor = activeDescriptor
            requestedIso = iso?.let { value -> descriptor?.sensitivityRange?.let { value.coerceIn(it.lower, it.upper) } ?: value }
            requestedExposureNs = exposureTimeNs?.let { value -> descriptor?.exposureTimeRangeNs?.let { value.coerceIn(it.lower, it.upper) } ?: value }
            requestedFocusDiopters = focusDiopters?.let { value -> value.coerceIn(0f, descriptor?.minimumFocusDistance ?: value) }
            requestedFocusCameraId = descriptor?.cameraId.takeIf { requestedFocusDiopters != null }
            listener?.onFocusSelectionChanged(requestedFocusDiopters)
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
                configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
            }.onFailure { listener?.onFailure("manual-control-failed", it.message ?: "Manual control update failed.", true) }
        }
    }

    /**
     * Saves a focus mark for the active camera. Up to 4 marks (A-D) are remembered per camera
     * for the lifetime of this engine. Returns false when no camera is active or the
     * distance is invalid.
     */
    fun setFocusMark(label: String, diopters: Float): Boolean {
        val descriptor = activeDescriptor ?: return false
        val minDistance = descriptor.minimumFocusDistance ?: return false
        if (!diopters.isFinite() || label !in setOf("A", "B", "C", "D") || !minDistance.isFinite() || minDistance <= 0f) return false
        val clamped = diopters.coerceIn(0f, minDistance)
        perCameraFocusMarks.compute(descriptor.cameraId) { _, marks -> (marks ?: emptyMap()) + (label to clamped) }
        return true
    }

    /** Removes a single focus mark. Returns false when the mark does not exist. */
    fun clearFocusMark(label: String): Boolean {
        val cameraId = activeDescriptor?.cameraId ?: return false
        var removed = false
        perCameraFocusMarks.computeIfPresent(cameraId) { _, marks ->
            removed = label in marks
            marks - label
        }
        return removed
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
        if (!toDiopters.isFinite() || durationMs !in 500L..10_000L || !minDistance.isFinite() || minDistance <= 0f || isHighSpeedSession() || session == null) return false
        val cameraGeneration = generation
        cameraExecutor.execute {
            if (cameraGeneration != generation || activeDescriptor?.cameraId != descriptor.cameraId || session == null || isHighSpeedSession()) return@execute
            val to = toDiopters.coerceIn(0f, minDistance)
            val from = (requestedFocusDiopters ?: lastReportedFocusDiopters ?: 0f).takeIf { it.isFinite() }?.coerceIn(0f, minDistance) ?: 0f
            if (activeTapFocusToken != null) clearTapFocusLocked(notify = true)
            if (afLockState != LockState.OFF) disableAfLock(notify = true)
            cancelFocusPullLocked()
            val token = focusPullSession.start(FocusPullPlan(from, to, durationMs, easing), SystemClock.elapsedRealtime())
            listener?.onFocusPullStarted(to)
            scheduleFocusPull(token, cameraGeneration, 0L)
        }
        return true
    }

    /** Cancellation is serialized with ticks, manual controls and camera resource changes. */
    fun cancelFocusPull() { cameraExecutor.execute { cancelFocusPullLocked() } }

    /**
     * Starts point autofocus using coordinates normalized to the displayed preview.
     * Returns false without enqueuing work when the active graph cannot truthfully support it.
     */
    fun tapToFocus(normalizedX: Float, normalizedY: Float, meterExposure: Boolean): Boolean {
        val descriptor = activeDescriptor ?: return false
        val configured = session ?: return false
        if (configured is CameraConstrainedHighSpeedCaptureSession ||
            descriptor.sensorActiveArray == null || descriptor.maxAfRegions <= 0 ||
            CaptureRequest.CONTROL_AF_MODE_AUTO !in descriptor.availableAfModes
        ) return false

        cameraExecutor.execute {
            val currentDescriptor = activeDescriptor ?: return@execute
            val currentSession = session ?: return@execute
            val builder = repeatingBuilder ?: return@execute
            if (currentSession is CameraConstrainedHighSpeedCaptureSession) return@execute
            cancelFocusPullLocked()
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
            requestedFocusCameraId = null
            listener?.onFocusSelectionChanged(null)
            listener?.onTapFocusState(TapFocusState.SEARCHING)
            try {
                builder.setTag(TapFocusRequestTag(token))
                applyManualControls(builder)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                currentSession.captureSingleRequest(builder.build(), cameraExecutor, previewCaptureCallback(currentDescriptor, false))
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                currentSession.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(currentDescriptor, false))
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                currentSession.captureSingleRequest(builder.build(), cameraExecutor, previewCaptureCallback(currentDescriptor, false))
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                currentSession.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(currentDescriptor, false))
                imageHandler.postDelayed(restoreTapFocus, TAP_FOCUS_HOLD_MS)
            } catch (failure: Exception) {
                clearTapFocusLocked(notify = true)
                listener?.onFailure("tap-focus-failed", failure.message ?: "Point autofocus failed.", true)
            }
        }
        return true
    }

    /** Atomically replace professional intent. A rejected live update restores the prior request. */
    fun setProfessionalControls(exposure: ExposureSelection, whiteBalance: WhiteBalanceSelection, processing: ImageProcessingSelection? = null) {
        cameraExecutor.execute {
            val previousExposure = professionalExposure
            val previousProcessing = imageProcessing
            if (!recording && processing != null) imageProcessing = processing
            val previousWb = requestedWhiteBalance
            val previousLock = aeLockActive
            professionalExposure = exposure
            requestedWhiteBalance = whiteBalance
            if (isHighSpeedSession()) return@execute
            if (exposure.mode != ExposureMode.AUTO) aeLockActive = false
            val builder = repeatingBuilder ?: return@execute
            val configured = session ?: return@execute
            runCatching {
                applyManualControls(builder)
                configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
            }.onFailure { failure ->
                professionalExposure = previousExposure
                imageProcessing = previousProcessing
                requestedWhiteBalance = previousWb
                aeLockActive = previousLock
                runCatching {
                    applyManualControls(builder)
                    configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
                }
                listener?.onProfessionalControlsRejected(failure.message ?: "Professional controls were rejected.")
            }
            if (previousLock != aeLockActive) listener?.onAeLockChanged(aeLockActive)
        }
    }

    /** No media output is needed until this asynchronous preparation confirms the HAL lock. */
    fun prepareRecordingWhiteBalance(onReady: (RecordingWhiteBalanceResult) -> Unit) {
        cameraExecutor.execute {
            if (recording || heldRecordingWb != null || session == null || repeatingBuilder == null) {
                onReady(RecordingWhiteBalanceResult(RecordingWhiteBalanceStatus.FAILED, failure = "white-balance-not-ready"))
                return@execute
            }
            val descriptor = activeDescriptor
            val effective = requestedWhiteBalance.adaptTo(descriptor?.kelvinRange.takeUnless { isHighSpeedSession() },
                descriptor?.tintSupported == true, if (isHighSpeedSession()) emptySet() else descriptor?.availableAwbModes)
            val auto = effective == WhiteBalanceSelection.Auto || effective == WhiteBalanceSelection.Preset(CaptureRequest.CONTROL_AWB_MODE_AUTO)
            if (isHighSpeedSession() || (auto && descriptor?.awbLockSupported != true)) {
                onReady(RecordingWhiteBalanceResult(RecordingWhiteBalanceStatus.FAILED, failure = "white-balance-lock-unavailable"))
                return@execute
            }
            heldRecordingWb = effective
            recordingWbCallback = onReady
            recordingWbToken = recordingWbGate.begin(SystemClock.elapsedRealtime())
            val token = recordingWbToken
            recordingWbTimeout = Runnable {
                cameraExecutor.execute {
                    if (recordingWbGate.expire(token, SystemClock.elapsedRealtime())) failRecordingWhiteBalance("white-balance-lock-timeout")
                }
            }.also { imageHandler.postDelayed(it, 3_000L) }
            submitRecordingWhiteBalance()
        }
    }

    /** Restore the latest requested WB after cancellation, preparation failure or finalization. */
    fun releaseRecordingWhiteBalance() {
        cameraExecutor.execute { clearRecordingWhiteBalance(resubmit = true) }
    }

    private fun clearRecordingWhiteBalance(resubmit: Boolean) {
        val held = heldRecordingWb != null
        val callback = recordingWbCallback
        recordingWbCallback = null
        recordingWbTimeout?.let(imageHandler::removeCallbacks)
        recordingWbTimeout = null
        heldRecordingWb = null
        recordingWbGate.reset()
        recordingWbMinimumTimestampNs = null
        if (resubmit && held) submitRecordingWhiteBalance()
        callback?.invoke(RecordingWhiteBalanceResult(RecordingWhiteBalanceStatus.FAILED, failure = "white-balance-preparation-cancelled"))
    }

    private fun failRecordingWhiteBalance(reason: String) {
        val callback = recordingWbCallback
        recordingWbCallback = null
        clearRecordingWhiteBalance(resubmit = true)
        callback?.invoke(RecordingWhiteBalanceResult(RecordingWhiteBalanceStatus.FAILED, failure = reason))
    }

    private fun submitRecordingWhiteBalance() {
        val builder = repeatingBuilder ?: return
        val configured = session ?: return
        if (configured is CameraConstrainedHighSpeedCaptureSession) return
        runCatching {
            applyManualControls(builder)
            configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
        }.onFailure {
            if (recordingWbCallback != null) failRecordingWhiteBalance("white-balance-request-rejected")
            else listener?.onProfessionalControlsRejected(it.message ?: "White balance restoration was rejected.")
        }
    }

    private fun reportRecordingWhiteBalance(request: CaptureRequest, result: TotalCaptureResult) {
        if (recordingWbCallback == null) return
        val before = recordingWbGate.status
        val held = heldRecordingWb
        val fixed = held != WhiteBalanceSelection.Auto && held != WhiteBalanceSelection.Preset(CaptureRequest.CONTROL_AWB_MODE_AUTO)
        val status = if (fixed) recordingWbGate.observeFixed(recordingWbToken, SystemClock.elapsedRealtime(),
            fixedRecordingWhiteBalanceMatches(held, request, result), result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP))
        else recordingWbGate.observe(recordingWbToken, SystemClock.elapsedRealtime(),
            request.get(CaptureRequest.CONTROL_AWB_MODE) == CaptureRequest.CONTROL_AWB_MODE_AUTO,
            result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE) == CaptureRequest.CONTROL_AWB_MODE_AUTO,
            request.get(CaptureRequest.CONTROL_AWB_LOCK) == true,
            result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_LOCK),
            result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE),
            result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP))
        when {
            status == RecordingWhiteBalanceStatus.FAILED -> failRecordingWhiteBalance("white-balance-lock-timeout")
            status == RecordingWhiteBalanceStatus.LOCKING && before != status -> submitRecordingWhiteBalance()
            status in setOf(RecordingWhiteBalanceStatus.LOCKED, RecordingWhiteBalanceStatus.FIXED) -> {
                recordingWbTimeout?.let(imageHandler::removeCallbacks)
                recordingWbTimeout = null
                recordingWbMinimumTimestampNs = recordingWbGate.minimumSensorTimestampNs
                val callback = recordingWbCallback
                recordingWbCallback = null
                callback?.invoke(RecordingWhiteBalanceResult(status, recordingWbMinimumTimestampNs))
            }
        }
    }

    private fun fixedRecordingWhiteBalanceMatches(held: WhiteBalanceSelection?, request: CaptureRequest, result: TotalCaptureResult): Boolean {
        val mode = when (held) {
            is WhiteBalanceSelection.Preset -> held.awbMode
            is WhiteBalanceSelection.Kelvin -> CaptureRequest.CONTROL_AWB_MODE_OFF
            else -> return false
        }
        if (request.get(CaptureRequest.CONTROL_AWB_MODE) != mode || result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_MODE) != mode) return false
        if (held is WhiteBalanceSelection.Kelvin) {
            if (Build.VERSION.SDK_INT < 36) return false
            if (request.get(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE) != held.kelvin ||
                result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE) != held.kelvin) return false
            if (activeDescriptor?.tintSupported == true && (request.get(CaptureRequest.COLOR_CORRECTION_COLOR_TINT) != held.tint ||
                    result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_COLOR_TINT) != held.tint)) return false
        }
        return true
    }

    fun setWhiteBalance(selection: WhiteBalanceSelection) {
        cameraExecutor.execute {
            requestedWhiteBalance = selection
            val builder = repeatingBuilder ?: return@execute
            val configured = session ?: return@execute
            applyManualControls(builder)
            runCatching { configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false)) }
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
            runCatching { configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false)) }
                .onFailure { listener?.onFailure("exposure-compensation-failed", it.message ?: "Exposure compensation update failed.", true) }
        }
    }

    fun setTorchEnabled(enabled: Boolean) = setTorch(enabled, requestedTorchStrength)

    fun setTorch(enabled: Boolean, strengthLevel: Int?) {
        cameraExecutor.execute {
            // Persist intent even before the camera opens; each request resolves current capability.
            val previousEnabled = requestedTorchEnabled
            val previousStrength = requestedTorchStrength
            requestedTorchEnabled = enabled
            requestedTorchStrength = strengthLevel
            val builder = repeatingBuilder ?: return@execute
            if (isHighSpeedSession()) return@execute
            runCatching {
                applyTorch(builder)
                session?.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
            }.onFailure {
                requestedTorchEnabled = previousEnabled
                requestedTorchStrength = previousStrength
                runCatching {
                    applyTorch(builder)
                    session?.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
                }
                listener?.onTorchRejected(it.message ?: "Flash control update failed.")
            }
        }
    }

    private fun applyTorch(builder: CaptureRequest.Builder) {
        val descriptor = activeDescriptor ?: return
        val resolved = descriptor.torchCapabilities.resolve(requestedTorchEnabled, requestedTorchStrength, isHighSpeedSession())
        builder.set(CaptureRequest.FLASH_MODE, if (resolved.enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        if (Build.VERSION.SDK_INT >= 35 && descriptor.torchCapabilities.adjustable) {
            builder.set(CaptureRequest.FLASH_STRENGTH_LEVEL, resolved.strengthLevel ?: descriptor.torchCapabilities.defaultLevel)
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
            val desired = enabled && descriptor.aeLockSupported && (professionalExposure?.mode?.let { it == ExposureMode.AUTO }
                ?: (requestedIso == null && requestedExposureNs == null)) && !isHighSpeedSession()
            if (desired == aeLockActive) return@execute
            aeLockActive = desired
            listener?.onAeLockChanged(desired)
            val builder = repeatingBuilder ?: return@execute
            val configured = session ?: return@execute
            applyManualControls(builder)
            runCatching { configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false)) }
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
            cancelFocusPullLocked()
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
            if (descriptor.supportsZoomRatioApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, range.start)
            } else {
                builder.set(CaptureRequest.SCALER_CROP_REGION, null)
            }
            return
        }
        if (descriptor.supportsZoomRatioApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
            configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
            lastAcceptedZoomRatio = requestedZoomRatio
        } catch (failure: Throwable) {
            if (isZoomRejection(failure, zoomChanged = requestedZoomRatio != lastAcceptedZoomRatio)) {
                requestedZoomRatio = lastAcceptedZoomRatio
                listener?.onZoomRejected(requestedZoomRatio, lastAcceptedZoomRatio)
            } else {
                listener?.onFailure("zoom-request-failed", failure.message ?: "Zoom update failed.", true)
            }
        }
    }

    private fun reportEffectiveZoom(result: TotalCaptureResult) {
        val descriptor = activeDescriptor ?: return
        val range = descriptor.effectiveZoomRange ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastZoomReportedAtMs < ZOOM_REPORT_PERIOD_MS) return
        lastZoomReportedAtMs = now
        val ratio = if (descriptor.supportsZoomRatioApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        timelapse: TimelapseCapture? = null,
        projectRateOverride: CaptureFrameRate? = null,
        separateAudioClock: CaptureEpochClock? = null,
        recordingLut: MonitorLut? = null,
    ): Boolean {
        if (disposed.get() || gpuPhotoPreviewEnabled || bracketRequest.get() != null || accumulationRequest.get() != null || burstRequest.get() != null) return false
        require(separateAudioClock == null || audio == null && timelapse == null && projectRateOverride == null && recordingGeometry != null && captureRate == null)

        require(projectRateOverride == null || (timelapse == null && captureRate == null && recordingGeometry != null))
        if (timelapse != null) {
            require(captureRate == null && recordingGeometry != null)
            return startGpuVideo(output, audio, recordingGeometry, videoBitrate, timelapse, recordingLut = recordingLut)
        }
        if (recordingGeometry != null && captureRate == null) {
            return startGpuVideo(output, audio, recordingGeometry, videoBitrate, projectRateOverride = projectRateOverride, separateAudioClock = separateAudioClock, recordingLut = recordingLut)
        }
        // MediaRecorder exposes no owned PCM processing stage: never ignore manual gain.
        if (audio?.recordingGain?.enabled == true) {
            listener?.onFailure("audio-manual-gain-requires-pcm", "Manual digital recording gain requires the AudioRecord/MediaCodec route.", true)
            return false
        }
        // MediaRecorder has no LUT stage: never accept an intent which would be ignored.
        if (recordingLut != null) return false
        val cameraGeneration = generation
        val device = camera ?: return false
        val surface = previewSurface ?: return false
        val descriptor = activeDescriptor ?: return false
        if (recording || gpuPreviewEnabled) return false
        val request = RecorderRequest(cameraGeneration, device)
        synchronized(stillAdmissionLock) {
            if (bracketRequest.get() != null || accumulationRequest.get() != null || burstRequest.get() != null || !recorderRequest.compareAndSet(null, request)) return false
        }
        if (!ownsRecorderRequest(request)) {
            recorderRequest.compareAndSet(request, null)
            return false
        }
        latestEncodedProgress = null
        lastRecordingLutEvidence = null
        cameraExecutor.execute {
            // Queue acceptance is not native admission: close or a preview replacement may
            // supersede this request before it gets the camera executor.
            if (!ownsRecorderRequest(request)) {
                recorderRequest.compareAndSet(request, null)
                return@execute
            }
            try {
                session?.stopRepeating()
                session?.close()
                session = null
                jpegReader?.close()
                jpegReader = null
                rawReader?.close()
                rawReader = null
                clearPendingRaw()
                closeAnalysisReader()
                analysisReader = null
                val size = activeVideoProfile?.size ?: descriptor.previewSize
                @Suppress("DEPRECATION")
                val configuredRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(appContext)
                } else {
                    MediaRecorder()
                }).also {
                    // Own the instance before any setter or prepare can throw.
                    recorder = it
                }.apply {
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
                val reportFailure = ownsRecorderRequest(request)
                releaseRecorder(request)
                if (reportFailure) listener?.onFailure("video-prepare-failed", failure.message ?: "Video recording could not be prepared.", true)
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
        timelapse: TimelapseCapture? = null,
        projectRateOverride: CaptureFrameRate? = null,
        separateAudioClock: CaptureEpochClock? = null,
        recordingLut: MonitorLut? = null,
    ): Boolean {
        val device = camera ?: return false
        val preview = previewSurface ?: return false
        val descriptor = activeDescriptor ?: return false
        if (recording) return false
        if (gpuPreviewEnabled) return startExistingGpuVideo(output, audio, recordingGeometry, videoBitrate, timelapse, projectRateOverride, separateAudioClock, recordingLut)
        if (logPipeline != null) return false
        val cameraGeneration = generation
        cameraExecutor.execute {
            // Superseded take: only startPreview/stopPreview/close advance the generation, so the
            // owner that did so already sees the newer graph (or no listener); reporting this stale
            // take to it would be the spurious-failure pattern the onConfigureFailed guards avoid.
            if (disposed.get() || cameraGeneration != generation) return@execute
            if (bracketRequest.get() != null || accumulationRequest.get() != null || burstRequest.get() != null) {
                listener?.onFailure("still-sequence-recording-busy", "A still sequence owns the camera graph.", true)
                return@execute
            }
            try {
                session?.stopRepeating()
                session?.close()
                session = null
                jpegReader?.close()
                jpegReader = null
                rawReader?.close()
                rawReader = null
                clearPendingRaw()
                closeAnalysisReader()
                analysisReader = null
                var analysisPipeline: OpenCineLogGpuPipeline? = null
                // Set when the pipeline already reported its own failure (e.g. log-recording-prepare-failed),
                // so a rejected start below does not report twice.
                val pipelineFailureReported = java.util.concurrent.atomic.AtomicBoolean(false)
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
                    cameraTimestampRealtime = descriptor.timestampSourceRealtime,
                    onAnalysis = { analysis -> deliverGpuAnalysis(cameraGeneration, { analysisPipeline }, analysis) },
                    onOperatorLutStatus = { status -> deliverGpuLutStatus(cameraGeneration, { analysisPipeline }, status) },
                    onPreviewLost = { listener?.onPreviewSurfaceLost(it) },
                ) { code, message ->
                    // A retired/superseded pipeline's late failure must not reach a newer owner's listener.
                    val owner = analysisPipeline
                    if (generation == cameraGeneration && owner != null && logPipeline === owner) {
                        pipelineFailureReported.set(true)
                        listener?.onFailure(code, message, true)
                    }
                }
                logPipeline = pipeline
                analysisPipeline = pipeline
                synchronizeSubjectOutput()
                passthroughVideoPipeline = true
                val accepted = pipeline.startRecording(
                    output = output,
                    bitrate = videoBitrate,
                    geometry = recordingGeometry,
                    audio = audio,
                    separateAudioClock = separateAudioClock,
                    recordingLut = recordingLut,
                    onRecordingLutApplied = { evidence ->
                        if (generation == cameraGeneration && logPipeline === pipeline && recording) listener?.onRecordingLutApplied(evidence)
                    },
                    minimumSensorTimestampNs = recordingWbMinimumTimestampNs,
                    timelapse = timelapse,
                    projectRateOverride = projectRateOverride,
                    onTimelapseProgress = { progress -> if (generation == cameraGeneration && logPipeline === pipeline) listener?.onTimelapseProgress(progress) },
                    onEncodedProgress = { progress ->
                        if (generation == cameraGeneration && logPipeline === pipeline) latestEncodedProgress = progress
                    },
                    onTimelapsePauseChanged = { status ->
                        if (generation == cameraGeneration && logPipeline === pipeline) {
                            lastTimelapsePauseStatus = status
                            listener?.onTimelapsePauseChanged(status)
                        }
                    },
                    onStarted = { lastRecordingLutEvidence = null; recording = true },
                    onStopped = { success, evidence ->
                        cameraExecutor.execute {
                            if (generation != cameraGeneration || logPipeline !== pipeline) return@execute
                            evidence?.encodedProgress?.let { latestEncodedProgress = it }
                            lastCaptureEpochReport = evidence?.avTiming
                            lastRecordingLutEvidence = evidence?.recordingLut
                            recording = false
                            logPipeline = null
                            synchronizeSubjectOutput()
                            passthroughVideoPipeline = false
                            retirePipeline(pipeline) {
                                if (generation == cameraGeneration) previewSurface?.takeIf { it.isValid }?.let { surface ->
                                    camera?.let { activeCamera ->
                                        if (activeVideoProfile?.constrainedHighSpeed == true) {
                                            configureHighSpeedPreviewSession(activeCamera, descriptor, surface, generation)
                                        } else configureSession(activeCamera, descriptor, surface, generation)
                                    }
                                }
                            }
                            reportRecordingStopped(success, pipeline.recordingFileRetirement())
                        }
                    },
                )
                if (!accepted) {
                    retirePipeline(pipeline)
                    logPipeline = null
                    passthroughVideoPipeline = false
                    recording = false
                    // Still this generation's take (checked above on this executor): the caller was
                    // already told `true`, so a rejected start must terminate it via onFailure.
                    if (!pipelineFailureReported.get()) {
                        listener?.onFailure("video-gpu-prepare-failed", "The GPU video recorder rejected the take.", true)
                    }
                    return@execute
                }
                configureGpuInputSession(device, descriptor, pipeline, recordingGeometry)
            } catch (failure: Throwable) {
                observeFailedInitialization(failure)
                logPipeline?.takeIf { passthroughVideoPipeline }?.let(::retirePipeline)
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
        separateAudioClock: CaptureEpochClock? = null,
        recordingLut: MonitorLut? = null,
    ): Boolean {
        val pipeline = logPipeline ?: return false
        val descriptor = activeDescriptor ?: return false
        if (!logPreviewEnabled || recording) return false
        val cameraGeneration = generation
        lastLogRecordingEvidence = null
        val accepted = pipeline.startRecording(
            output = output,
            bitrate = videoBitrate,
            geometry = recordingGeometry,
            audio = audio,
            separateAudioClock = separateAudioClock,
            recordingLut = recordingLut,
            onRecordingLutApplied = { evidence ->
                if (generation == cameraGeneration && logPipeline === pipeline && recording) listener?.onRecordingLutApplied(evidence)
            },
            minimumSensorTimestampNs = recordingWbMinimumTimestampNs,
            onEncodedProgress = { progress ->
                if (generation == cameraGeneration && logPipeline === pipeline) latestEncodedProgress = progress
            },
            onTimelapsePauseChanged = { status ->
                if (generation == cameraGeneration && logPipeline === pipeline) {
                    lastTimelapsePauseStatus = status
                    listener?.onTimelapsePauseChanged(status)
                }
            },
            onStarted = {
                check(generation == cameraGeneration && logPipeline === pipeline) { "GPU recording owner changed during preparation" }
                lastRecordingLutEvidence = null
                recording = true
                val size = recordingGeometry.encodedSize
                listener?.onRecordingStarted(size.width, size.height)
            },
            onStopped = { success, evidence ->
                cameraExecutor.execute {
                    if (generation != cameraGeneration || logPipeline !== pipeline) return@execute
                    evidence?.encodedProgress?.let { latestEncodedProgress = it }
                    lastCaptureEpochReport = evidence?.avTiming
                    lastRecordingLutEvidence = evidence?.recordingLut
                    recording = false
                    lastLogRecordingEvidence = evidence
                    reportRecordingStopped(success, pipeline.recordingFileRetirement())
                }
            },
        )
        return accepted
    }

    @Volatile private var latestEncodedProgress: EncodedRecordingProgress? = null
    fun encodedRecordingProgress(): EncodedRecordingProgress? = latestEncodedProgress

    @Volatile private var lastCaptureEpochReport: CaptureEpochReport? = null
    fun consumeCaptureEpochReport(): CaptureEpochReport? = lastCaptureEpochReport.also { lastCaptureEpochReport = null }

    fun consumeLastRecordingLutEvidence(): BakedLutEvidence? =
        lastRecordingLutEvidence.also { lastRecordingLutEvidence = null }

    fun consumeLastOpenCineLogEvidence(): OpenCineLogRecordingEvidence? =
        lastLogRecordingEvidence.also { lastLogRecordingEvidence = null }

   fun setOpenCineLogViewAssist(enabled: Boolean) {
       logViewAssistEnabled = enabled
       logPipeline?.setViewAssist(enabled)
   }

    fun setOpenCineLogSqueezeFactor(factor: Float) {
        logPipeline?.setPreviewSqueezeFactor(factor)
    }

    @Volatile private var lastTimelapsePauseStatus: TimelapsePauseStatus? = null

    fun consumeTimelapsePauseStatus(): TimelapsePauseStatus? = lastTimelapsePauseStatus.also { lastTimelapsePauseStatus = null }

    fun setTimelapsePaused(paused: Boolean, onComplete: (Boolean) -> Unit): Boolean {
        if (!recording) return false
        val pipeline = logPipeline ?: return false
        val ownerGeneration = generation
        return pipeline.setTimelapsePaused(paused) { accepted ->
            onComplete(accepted && generation == ownerGeneration && logPipeline === pipeline)
        }
    }

    fun stopVideo(): Boolean {
        if (!recording) return false
        logPipeline?.let { return it.stopRecording() }
        val request = recorderRequest.get() ?: return false
        if (!request.stopRequested.compareAndSet(false, true)) return false
        cameraExecutor.execute {
            if (!ownsRecorderRequest(request)) return@execute
            val descriptor = activeDescriptor
            val device = camera
            val surface = previewSurface
            var success = true
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            session = null
            try { recorder?.stop() } catch (_: RuntimeException) { success = false }
            releaseRecorder(request)
            reportRecordingStopped(success, recorderOutputRetirement())
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
        val request = recorderRequest.get() ?: return false
        cameraExecutor.execute {
            if (!ownsRecorderRequest(request) || request.stopRequested.get()) return@execute
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

    fun detachOperatorFromActiveGpuPreview(): Boolean {
        val pipeline = logPipeline ?: return false
        if (session == null || (!gpuPreviewEnabled && !logPreviewEnabled)) return false
        previewSurface = null
        pipeline.detachPreview()
        return true
    }

    fun attachOperatorToActiveGpuPreview(surface: Surface, rotation: Int): Boolean {
        val pipeline = logPipeline ?: return false
        if (session == null || !surface.isValid || (!gpuPreviewEnabled && !logPreviewEnabled)) return false
        previewSurface = surface
        displayRotationDegrees = rotation
        return pipeline.attachPreview(surface, rotation)
    }

    fun attachPreviewWhileRecording(surface: Surface, displayRotationDegrees: Int): Boolean {
        if (!recording || !surface.isValid) return false
        logPipeline?.let {
            previewSurface = surface
            this.displayRotationDegrees = displayRotationDegrees
            return it.attachPreview(surface, displayRotationDegrees)
        }
        val request = recorderRequest.get() ?: return false
        cameraExecutor.execute {
            if (!ownsRecorderRequest(request) || request.stopRequested.get()) return@execute
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

    @Volatile private var stoppedOutputRetirement = CompletableFuture.completedFuture(Unit)

    /** Exact file receipt associated with the latest stop callback, not the Boolean content result. */
    fun stoppedRecordingOutputRetirement(): CompletableFuture<Unit> = stoppedOutputRetirement.thenApply { it }

    private fun recorderOutputRetirement(): CompletableFuture<Unit> {
        val receipt = CompletableFuture<Unit>()
        val failure = retirementFailure
        if (failure != null) receipt.completeExceptionally(failure)
        else if (recorder != null || recorderRequest.get() != null) {
            receipt.completeExceptionally(IllegalStateException("MediaRecorder output remains owned"))
        } else receipt.complete(Unit)
        return receipt
    }

    private fun reportRecordingStopped(success: Boolean, retirement: CompletableFuture<Unit>) {
        stoppedOutputRetirement = retirement
        val retired = retirement.isDone && !retirement.isCompletedExceptionally && !retirement.isCancelled
        listener?.onRecordingStopped(success && retired)
    }

    /** Observer only: call after stopPreview (or a failed recording has already retired).
     * A closed PFD wrapper, timeout, or queued stop is not a native retirement receipt. */
    fun recordingOutputRetirement(): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        // close may retire/shut down the executor before this observer gets its queued turn.
        closeCompletion.whenComplete { _, failure ->
            if (failure == null) completion.complete(Unit) else completion.completeExceptionally(failure)
        }
        if (!disposed.get()) cameraExecutor.execute {
            val failure = retirementFailure
            if (failure != null) completion.completeExceptionally(failure)
            else if (recorder != null || recorderRequest.get() != null || recording) {
                completion.completeExceptionally(IllegalStateException("Recording output still has a native owner"))
            } else {
                val receipts = retiringPipelines.map { it.closeAsync() } + initializationRetirements.toList() +
                    listOfNotNull(logPipeline?.recordingFileRetirement())
                CompletableFuture.allOf(*receipts.toTypedArray()).whenComplete { _, error ->
                    if (error == null) completion.complete(Unit) else completion.completeExceptionally(error)
                }
            }
        }
        return completion.thenApply { it }
    }

    fun stopPreview() {
        cameraExecutor.execute {
            generation++
            pendingPreviewStart = null
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
        val currentGeneration = generation
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
                            configured.setPhotoAwareRepeatingRequest(
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
                    // A replaced graph's late failure must not stop or report on the current owner.
                    if (currentGeneration != generation || logPipeline !== pipeline || !recording) return
                    pipeline.stopRecording()
                    listener?.onFailure("video-gpu-session-failed", "CameraService rejected the GPU video session.", true)
                }
            },
        )
        configureProcessingSession(configuration, device, CameraDevice.TEMPLATE_RECORD)
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
        val request = requireNotNull(recorderRequest.get())
        val currentRecordingGeneration = ++recordingSessionGeneration
        fun ownsConfiguration(): Boolean = ownsRecorderRequest(request) &&
            currentRecordingGeneration == recordingSessionGeneration && recorder != null && !request.stopRequested.get()
        val surfaces = buildList {
            preview?.takeIf { it.isValid }?.let(::add)
            add(recordingSurface)
        }
        try {
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                surfaces.map(::OutputConfiguration),
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (!ownsConfiguration()) {
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
                            configured.setPhotoAwareRepeatingRequest(
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
                                releaseRecorder(request)
                                listener?.onFailure("video-start-failed", failure.message ?: "Video recording could not start.", true)
                            } else {
                                finalizeRecordingAfterSessionFailure(request)
                            }
                        }
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        configured.close()
                        if (!ownsConfiguration()) return
                        if (startRecorder) {
                            releaseRecorder(request)
                            listener?.onFailure("video-session-failed", "Video recording configuration failed.", true)
                        } else {
                            finalizeRecordingAfterSessionFailure(request)
                        }
                    }
                },
            )
            configureProcessingSession(configuration, device, CameraDevice.TEMPLATE_RECORD)
            recordingSessionObserver?.invoke(configuration)
            device.createCaptureSession(configuration)
        } catch (failure: Exception) {
            if (!ownsConfiguration()) return
            if (startRecorder) {
                releaseRecorder(request)
                listener?.onFailure("video-session-exception", failure.message ?: "Video recording configuration failed.", true)
            } else {
                finalizeRecordingAfterSessionFailure(request)
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
                    if (currentGeneration != generation) return
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
        val request = requireNotNull(recorderRequest.get())
        val currentRecordingGeneration = ++recordingSessionGeneration
        fun ownsConfiguration(): Boolean = ownsRecorderRequest(request) &&
            currentRecordingGeneration == recordingSessionGeneration && recorder != null && !request.stopRequested.get()
        val surfaces = buildList {
            preview?.takeIf { it.isValid }?.let(::add)
            add(recordingSurface)
        }
        try {
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_HIGH_SPEED,
                surfaces.map(::OutputConfiguration),
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (!ownsConfiguration()) {
                            configured.close()
                            return
                        }
                        if (configured !is CameraConstrainedHighSpeedCaptureSession) {
                            configured.close()
                            if (startRecorder) {
                                releaseRecorder(request)
                                listener?.onFailure("high-speed-video-session-failed", "CameraService returned a non-high-speed recording session.", true)
                            } else finalizeRecordingAfterSessionFailure(request)
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
                                releaseRecorder(request)
                                listener?.onFailure("high-speed-video-start-failed", failure.message ?: "High-speed video could not start.", true)
                            } else finalizeRecordingAfterSessionFailure(request)
                        }
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        configured.close()
                        if (!ownsConfiguration()) return
                        if (startRecorder) {
                            releaseRecorder(request)
                            listener?.onFailure("high-speed-video-session-failed", "CameraService rejected the constrained high-speed recording.", true)
                        } else finalizeRecordingAfterSessionFailure(request)
                    }
                },
            )
            configuration.setSessionParameters(
                device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    surfaces.forEach(::addTarget)
                    applyHighSpeedControls(this)
                }.build(),
            )
            recordingSessionObserver?.invoke(configuration)
            device.createCaptureSession(configuration)
        } catch (failure: Exception) {
            if (!ownsConfiguration()) return
            if (startRecorder) {
                releaseRecorder(request)
                listener?.onFailure("high-speed-video-session-exception", failure.message ?: "High-speed recording could not be created.", true)
            } else finalizeRecordingAfterSessionFailure(request)
        }
    }

    private fun finalizeRecordingAfterSessionFailure(request: RecorderRequest) {
        if (!ownsRecorderRequest(request)) return
        var success = true
        try {
            recorder?.stop()
        } catch (_: RuntimeException) {
            success = false
        }
        releaseRecorder(request)
        reportRecordingStopped(success, recorderOutputRetirement())
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
                        configured.setPhotoAwareRepeatingRequest(
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
                    if (currentGeneration != generation || !logPreviewEnabled) return
                    listener?.onFailure("log-session-failed", "CameraService rejected the HLG10 OCLog graph.", false)
                }
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            configuration.setColorSpace(ColorSpace.Named.BT2020_HLG)
        }
        try {
            configureProcessingSession(configuration, device, CameraDevice.TEMPLATE_RECORD)
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
                    if (currentGeneration != generation || !logPreviewEnabled) return
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
        val photoPipeline = logPipeline.takeIf { gpuPhotoPreviewEnabled }
        if (gpuPhotoPreviewEnabled) check(photoPipeline != null && surface === photoPipeline.cameraInputSurface)
        val outputs = mutableListOf(OutputConfiguration(surface))
        if (activeVideoProfile == null) {
        val compressedFormat = if (activeStillFormat == StillPhotoFormat.HEIC) ImageFormat.HEIC else ImageFormat.JPEG
        val compressedSize = if (activeStillFormat == StillPhotoFormat.HEIC) descriptor.heicSize else descriptor.jpegSize
        compressedSize?.let { size ->
            jpegReader = ImageReader.newInstance(size.width, size.height, compressedFormat, MAX_BURST_IMAGES + 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = runCatching { reader.acquireNextImage() }
                        .onFailure { Log.w(TAG, "Compressed still acquire failed; image dropped.", it) }
                        .getOrNull() ?: return@setOnImageAvailableListener
                    var imageTimestamp: Long? = null
                    var ticket: StillImageHandoff.Ticket<JpegPayload>? = null
                    var transferred = false
                    try {
                        image.use {
                            imageTimestamp = it.timestamp
                            val buffer = it.planes.first().buffer
                            if (photoRequest.get()?.aspect?.enabled == true) require(buffer.remaining() <= PhotoAspectBounds.MAX_ENCODED_BYTES) {
                                "Aspect source JPEG exceeds its encoded budget"
                            }
                            if (accumulationRequest.get() != null) require(buffer.remaining() <= CapturedAccumulation.MAX_ENCODED_BYTES) {
                                "Accumulation source JPEG exceeds its encoded budget"
                            }
                            burstRequest.get()?.let { group ->
                                require(buffer.remaining().toLong() <= CapturedBurst.MAX_ENCODED_BYTES - group.encodedBytes) {
                                    "The complete burst exceeds its encoded byte budget"
                                }
                            }
                            bracketRequest.get()?.let { group ->
                                require(buffer.remaining().toLong() <= CapturedBracket.MAX_ENCODED_BYTES - group.encodedBytes) {
                                    "The complete bracket exceeds its encoded byte budget"
                                }
                            }
                            require(buffer.remaining() in 1..StillImagePayload.MAX_COMPRESSED_BYTES) { "Compressed still exceeds its byte limit" }
                            val reservation = checkNotNull(compressedHandoff.reserve(buffer.remaining()) {
                                !disposed.get() && currentGeneration == generation && reader === jpegReader
                            }) { "Compressed still handoff byte budget is full or its graph retired" }
                            ticket = reservation
                            // Allocate only after reserving across producer, queue and pending map.
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val timestamp = it.timestamp
                            val payload = JpegPayload(bytes, it.width, it.height,
                                if (compressedFormat == ImageFormat.HEIC) StillImageKind.HEIC else StillImageKind.JPEG, reservation)
                            transferred = compressedHandoff.publish(reservation, payload)
                            if (transferred) cameraExecutor.execute {
                                // The runnable retains only the ticket; close can discard its
                                // payload even if this executor intentionally drops the callback.
                                compressedHandoff.take(reservation)?.let { value -> receiveJpeg(reader, currentGeneration, timestamp, value) }
                            }
                        }
                    } catch (failure: Exception) {
                        imageTimestamp?.let { timestamp -> cameraExecutor.execute {
                            receiveCompressedFailure(reader, currentGeneration, timestamp, failure.message ?: "Still image could not be read.")
                        } }
                    } finally { if (!transferred) ticket?.let(compressedHandoff::release) }
                }, imageHandler)
            }
            outputs += OutputConfiguration(requireNotNull(jpegReader).surface)
        }
        descriptor.rawSize?.takeIf { activeStillFormat != StillPhotoFormat.HEIC }?.let { size ->
            rawReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val received = runCatching { reader.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    queueRawImage(reader, currentGeneration, received)
                }, imageHandler)
            }
            outputs += OutputConfiguration(requireNotNull(rawReader).surface)
        }
        }
        // GPU photo monitor already supplies pre-LUT analysis; avoid a redundant fourth Camera2
        // stream there. Video preview keeps the YUV analysis stream too so peaking/zebra/histogram
        // do not silently die when the mode leaves photo. `logPreviewEnabled` is belt-and-braces
        // only: LOG graphs are built by configureLogSession and never reach this function.
        var analysisOutput: OutputConfiguration? = null
        var analysisLease: ReaderLease<ImageReader>? = null
        descriptor.analysisSize?.takeUnless { gpuPhotoPreviewEnabled || logPreviewEnabled }?.let { size ->
            analysisReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { reader ->
                val lease = ReaderLease(reader)
                // Per graph: once thermal pressure turns the scopes off they stay off until the
                // next preview graph, so they do not flap at the SEVERE boundary.
                val governor = AnalysisPerformanceGovernor(queueCapacity = 2)
                analysisReaderLease = lease
                analysisLease = lease
                reader.setOnImageAvailableListener({
                    val analysis = lease.read { ownedReader ->
                        val image = runCatching { ownedReader.acquireLatestImage() }
                            .onFailure { Log.w(TAG, "Analysis image acquire failed; frame dropped.", it) }
                            .getOrNull()
                        image?.use {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastAnalysisAtMs >= monitoringOptions.periodMs) {
                                lastAnalysisAtMs = now
                                if (analysisAllowed(governor)) analyzeImage(it) else null
                            } else null
                        }
                    }
                    if (analysis != null && !disposed.get()) {
                        try { cameraExecutor.execute {
                            if (!disposed.get() && currentGeneration == generation && analysisReaderLease === lease) listener?.onAnalysis(analysis)
                        } } catch (_: java.util.concurrent.RejectedExecutionException) { /* Terminal owner. */ }
                    }
                }, imageHandler)
            }
            analysisOutput = OutputConfiguration(requireNotNull(analysisReader).surface).also { outputs += it }
        }
        fun ownsGraph(): Boolean = currentGeneration == generation &&
            (photoPipeline == null || !disposed.get() && logPipeline === photoPipeline)
        fun createSession(sessionOutputs: List<OutputConfiguration>, withAnalysis: Boolean) {
            // The extra YUV stream is optional: a HAL rejecting the pair must cost the scopes,
            // never the viewfinder. Retry once with the analysis stream dropped.
            fun retryWithoutAnalysis(reason: String) {
                Log.w(TAG, "Preview graph rejected the YUV analysis stream ($reason); scopes unavailable.")
                if (analysisReaderLease === analysisLease) closeAnalysisReader()
                createSession(
                    sessionOutputs.filterNot { it === analysisOutput }.mapNotNull { it.surface }.map(::OutputConfiguration),
                    withAnalysis = false,
                )
            }
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                sessionOutputs,
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (currentGeneration != generation ||
                            photoPipeline != null && (disposed.get() || logPipeline !== photoPipeline || !gpuPhotoPreviewEnabled)) {
                            configured.close()
                            return
                        }
                        session = configured
                        startRepeating(device, configured, descriptor)
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        configured.close()
                        if (!ownsGraph()) return
                        if (withAnalysis) {
                            retryWithoutAnalysis("configure failed")
                            return
                        }
                        listener?.onFailure("preview-session-failed", "Camera preview configuration failed.", true)
                    }
                },
            )
            try {
                configureProcessingSession(configuration, device, CameraDevice.TEMPLATE_PREVIEW)
                device.createCaptureSession(configuration)
            } catch (failure: Exception) {
                if (!ownsGraph()) return
                if (withAnalysis) {
                    retryWithoutAnalysis(failure.message ?: failure.javaClass.simpleName)
                    return
                }
                listener?.onFailure("preview-session-exception", failure.message ?: "Camera preview configuration failed.", true)
            }
        }
        createSession(outputs, withAnalysis = analysisOutput != null)
    }

    private fun startRepeating(
        device: CameraDevice,
        configured: CameraCaptureSession,
        descriptor: Camera2CameraDescriptor,
    ) {
        val surface = targetPreviewCameraSurface() ?: return
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
            configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, true))
            notifyZoomRange()
        } catch (failure: Exception) {
            listener?.onFailure("preview-request-failed", failure.message ?: "Camera preview request failed.", true)
        }
    }

    private fun previewCaptureCallback(
        descriptor: Camera2CameraDescriptor?,
        notifyStarted: Boolean,
    ): CameraCaptureSession.CaptureCallback {
        val callbackGeneration = generation
        if (notifyStarted && descriptor != null && !disposed.get()) {
            session?.let { pendingPreviewStartedReceipt = PreviewStartedReceipt(it, callbackGeneration, descriptor) }
        }
        return object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (disposed.get() || callbackGeneration != generation || session !== this@Camera2PreviewEngine.session) return
                    pendingPreviewStartedReceipt?.takeIf { it.generation == callbackGeneration && it.session === session }?.let { receipt ->
                        // Consume before notifying: reentrant callbacks or a late original
                        // request cannot announce the same armed graph a second time.
                        pendingPreviewStartedReceipt = null
                        listener?.onPreviewStarted(receipt.descriptor)
                    }
                    val sensorTimestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                    updateEffectiveFps(sensorTimestamp)
                    reportTapFocusResult(request, result)
                    reportAfLockResult(request, result)
                    reportRecordingWhiteBalance(request, result)
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
                                result.get(android.hardware.camera2.CaptureResult.FLASH_MODE)?.let { it == CaptureRequest.FLASH_MODE_TORCH },
                                if (Build.VERSION.SDK_INT >= 35 && descriptor?.torchCapabilities?.adjustable == true) {
                                    result.get(android.hardware.camera2.CaptureResult.FLASH_STRENGTH_LEVEL)
                                } else null,
                                afMode = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_MODE),
                                submittedAfMode = request.get(CaptureRequest.CONTROL_AF_MODE),
                                submittedFocusDistanceDiopters = request.get(CaptureRequest.LENS_FOCUS_DISTANCE),
                                awbLocked = result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_LOCK),
                                colorTint = if (Build.VERSION.SDK_INT >= 36) result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_COLOR_TINT) else null,
                                exposureMode = when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_MODE)) {
                                    CaptureRequest.CONTROL_AE_MODE_OFF -> ExposureMode.MANUAL
                                    null -> null
                                    else -> if (Build.VERSION.SDK_INT >= 36) when (result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_PRIORITY_MODE)) {
                                        CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY -> ExposureMode.ISO_PRIORITY
                                        CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY -> ExposureMode.SHUTTER_PRIORITY
                                        CaptureRequest.CONTROL_AE_PRIORITY_MODE_OFF -> ExposureMode.AUTO
                                        else -> if (professionalExposure?.mode in setOf(ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY)) null else ExposureMode.AUTO
                                    } else ExposureMode.AUTO
                                },
                                antibanding = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_ANTIBANDING_MODE),
                                opticalStabilization = result.get(android.hardware.camera2.CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
                                videoStabilization = result.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
                                noiseReduction = result.get(android.hardware.camera2.CaptureResult.NOISE_REDUCTION_MODE),
                                edgeEnhancement = result.get(android.hardware.camera2.CaptureResult.EDGE_MODE),
                                cropRegion = result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)?.let { listOf(it.left, it.top, it.right, it.bottom) },
                                submittedImageProcessing = ImageProcessingDefaults(
                                    request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE), request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE),
                                    request.get(CaptureRequest.NOISE_REDUCTION_MODE), request.get(CaptureRequest.EDGE_MODE)),
                            ),
                        )
                    }
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
        defaultAfMode: Int = if (recording || logPreviewEnabled || (gpuPreviewEnabled && !gpuPhotoPreviewEnabled) || activeVideoProfile != null)
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
    ) {
        val pro = professionalExposure?.resolve(
            if (isHighSpeedSession()) ExposureCapabilities() else activeDescriptor?.exposureCapabilities ?: ExposureCapabilities(),
            CaptureFrameRate(requestedTargetFps.coerceAtLeast(1)),
        )
        val iso = if (pro != null) pro.iso else requestedIso
        val exposure = if (pro != null) pro.timeNs else requestedExposureNs
        if (pro == null && Build.VERSION.SDK_INT >= 36 && activeDescriptor?.exposureCapabilities?.priorities?.isNotEmpty() == true) {
            builder.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CaptureRequest.CONTROL_AE_PRIORITY_MODE_OFF)
        }
        if (pro != null) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, if (pro.mode == ExposureMode.MANUAL) CaptureRequest.CONTROL_AE_MODE_OFF else CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, pro.iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, pro.timeNs)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, pro.frameDurationNs)
            if (Build.VERSION.SDK_INT >= 36 && activeDescriptor?.exposureCapabilities?.priorities?.isNotEmpty() == true) {
                builder.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, when (pro.mode) {
                    ExposureMode.ISO_PRIORITY -> CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY
                    ExposureMode.SHUTTER_PRIORITY -> CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
                    else -> CaptureRequest.CONTROL_AE_PRIORITY_MODE_OFF
                })
            }
            builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLockActive && pro.mode == ExposureMode.AUTO)
            requestedAeCompensationIndex?.let { builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
            pro.antibanding?.let { band -> builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, when (band) {
                Antibanding.AUTO -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
                Antibanding.OFF -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
                Antibanding.HZ50 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
                Antibanding.HZ60 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            }) }
        } else if (iso != null && exposure != null) {
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
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, tapAfRegion?.let { arrayOf(it) })
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, tapAeRegion?.let { arrayOf(it) })
            }
            else -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, defaultAfMode)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            }
        }
        val wbDescriptor = activeDescriptor
        val effectiveWb = (heldRecordingWb ?: requestedWhiteBalance).adaptTo(
            wbDescriptor?.kelvinRange.takeUnless { isHighSpeedSession() },
            wbDescriptor?.tintSupported == true,
            if (isHighSpeedSession()) emptySet() else wbDescriptor?.availableAwbModes,
        )
        when (val wb = effectiveWb) {
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
                    if (wbDescriptor?.tintSupported == true) builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, wb.tint)
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
        if (wbDescriptor?.awbLockSupported == true) builder.set(CaptureRequest.CONTROL_AWB_LOCK, recordingWbGate.lockRequested)
        applyImageProcessing(builder)
        applyTorch(builder)
        applyZoom(builder)
    }

    private fun applyImageProcessing(builder: CaptureRequest.Builder) {
        val caps = activeDescriptor?.imageProcessingCapabilities ?: return
        val defaults = processingDefaults.getOrPut(builder) {
            ImageProcessingDefaults(
                builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE),
                builder.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE),
                builder.get(CaptureRequest.NOISE_REDUCTION_MODE),
                builder.get(CaptureRequest.EDGE_MODE),
            )
        }
        val resolved = imageProcessing.resolve(caps, defaults, isHighSpeedSession()).values
        if (caps.opticalModes.isNotEmpty()) builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, resolved.optical)
        if (caps.videoModes.isNotEmpty()) builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, resolved.video)
        if (caps.noiseModes.isNotEmpty()) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, resolved.noise)
        if (caps.edgeModes.isNotEmpty()) builder.set(CaptureRequest.EDGE_MODE, resolved.edge)
    }

    /** Supply advertised expensive controls up front; later between-take changes may reconfigure HAL. */
    private fun configureProcessingSession(configuration: SessionConfiguration, device: CameraDevice, template: Int) {
        if (configuration.sessionType == SessionConfiguration.SESSION_HIGH_SPEED || configuration.sessionParameters != null) return
        if (activeDescriptor?.imageProcessingCapabilities?.sessionControls.isNullOrEmpty()) return
        configuration.setSessionParameters(device.createCaptureRequest(template).apply { applyImageProcessing(this) }.build())
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
            configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
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
            configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
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
            configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            configured.captureSingleRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(descriptor, false))
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
                timestampSourceRealtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_EXTERNAL,
                focalLengthsMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
                previewSize = previewSize,
                jpegSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height },
                heicSize = if (ImageFormat.HEIC in map.outputFormats)
                    map.getOutputSizes(ImageFormat.HEIC)?.maxByOrNull { it.width.toLong() * it.height } else null,
                rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height },
                analysisSize = map.getOutputSizes(ImageFormat.YUV_420_888)?.minByOrNull { abs(it.width.toLong() * it.height - 320L * 240L) },
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                exposureTimeRangeNs = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
                aeCompensationStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
                aeCompensationStepNumerator = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.numerator ?: 0,
                aeCompensationStepDenominator = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.denominator ?: 1,
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
                aeLockSupported = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
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
                tintSupported = Build.VERSION.SDK_INT >= 36 && kelvinRange != null && characteristics.availableCaptureRequestKeys.contains(CaptureRequest.COLOR_CORRECTION_COLOR_TINT),
                awbLockSupported = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true &&
                    CaptureRequest.CONTROL_AWB_LOCK in characteristics.availableCaptureRequestKeys,
                availableAwbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty(),
                imageProcessingCapabilities = ImageProcessingCapabilities(
                    opticalModes = if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE in characteristics.availableCaptureRequestKeys)
                        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toSet().orEmpty() else emptySet(),
                    videoModes = if (CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in characteristics.availableCaptureRequestKeys)
                        characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty() else emptySet(),
                    noiseModes = if (CaptureRequest.NOISE_REDUCTION_MODE in characteristics.availableCaptureRequestKeys)
                        characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet().orEmpty() else emptySet(),
                    edgeModes = if (CaptureRequest.EDGE_MODE in characteristics.availableCaptureRequestKeys)
                        characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet().orEmpty() else emptySet(),
                    sessionControls = characteristics.availableSessionKeys.orEmpty().mapNotNull { key -> when (key) {
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE -> ImageProcessingControl.STABILIZATION
                        CaptureRequest.NOISE_REDUCTION_MODE -> ImageProcessingControl.NOISE_REDUCTION
                        CaptureRequest.EDGE_MODE -> ImageProcessingControl.EDGE
                        else -> null
                    } }.toSet(),
                ),
                exposureCapabilities = ExposureCapabilities(
                    manual = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true,
                    isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper },
                    timeRangeNs = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper },
                    priorities = if (Build.VERSION.SDK_INT >= 36 && characteristics.availableCaptureRequestKeys.contains(CaptureRequest.CONTROL_AE_PRIORITY_MODE)) {
                        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)?.toList()?.mapNotNull { when (it) {
                            CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY -> ExposureMode.ISO_PRIORITY.takeIf { characteristics.availableCaptureRequestKeys.contains(CaptureRequest.SENSOR_SENSITIVITY) }
                            CaptureRequest.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY -> ExposureMode.SHUTTER_PRIORITY.takeIf { characteristics.availableCaptureRequestKeys.contains(CaptureRequest.SENSOR_EXPOSURE_TIME) }
                            else -> null
                        } }?.toSet().orEmpty()
                    } else emptySet(),
                    antibanding = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.toList()?.mapNotNull { when (it) {
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO -> Antibanding.AUTO
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF -> Antibanding.OFF
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ -> Antibanding.HZ50
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ -> Antibanding.HZ60
                        else -> null
                    } }?.toSet().orEmpty(),
                ),
                photoFlashCapabilities = run {
                    val available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    val modes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet().orEmpty()
                    val hasStrength = Build.VERSION.SDK_INT >= 35 &&
                        CaptureRequest.FLASH_STRENGTH_LEVEL in characteristics.availableCaptureRequestKeys
                    val max = if (Build.VERSION.SDK_INT >= 35 && hasStrength) (characteristics.get(CameraCharacteristics.FLASH_SINGLE_STRENGTH_MAX_LEVEL) ?: 1).coerceAtLeast(1) else 1
                    val default = if (Build.VERSION.SDK_INT >= 35 && hasStrength) (characteristics.get(CameraCharacteristics.FLASH_SINGLE_STRENGTH_DEFAULT_LEVEL) ?: 1).coerceIn(1, max) else 1
                    PhotoFlashCapabilities(available, modes, max, default)
                },
                torchCapabilities = if (Build.VERSION.SDK_INT >= 35) {
                    val available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    val hasKey = characteristics.availableCaptureRequestKeys.contains(CaptureRequest.FLASH_STRENGTH_LEVEL)
                    val max = if (hasKey) (characteristics.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1).coerceAtLeast(1) else 1
                    val default = (characteristics.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_DEFAULT_LEVEL) ?: 1).coerceIn(1, max)
                    TorchCapabilities(available, max, default)
                } else TorchCapabilities(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true),
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

    private fun encoderCanReachFps(size: Size, fps: Int, mime: String = MIME_AVC): Boolean {
        val caps = when (mime) {
            MIME_HEVC -> hevcSurfaceEncoderCapabilities
            else -> avcSurfaceEncoderCapabilities
        }
        return caps.any {
            it.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
        }
    }

    /** Public wrapper so the service can validate recording geometry against encoder caps. */
    fun encoderCanReachFps(size: Size): Boolean = encoderCanReachFps(size, requestedTargetFps)

    /** HEVC variant for pipelines that encode Main10 (LOG mode). */
    fun hevcEncoderCanReachFps(size: Size): Boolean =
        encoderCanReachFps(size, requestedTargetFps, MIME_HEVC)

    private fun jpegOrientation(descriptor: Camera2CameraDescriptor): Int =
        if (descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (descriptor.sensorOrientation + displayRotationDegrees) % 360
        } else {
            (descriptor.sensorOrientation - displayRotationDegrees + 360) % 360
        }

    /** Image-thread check before the CPU scopes run; logs once when thermal pressure disables them. */
    private fun analysisAllowed(governor: AnalysisPerformanceGovernor): Boolean {
        if (governor.isDisabled()) return false
        val status = powerManager?.currentThermalStatus ?: -1
        val decision = governor.observeLatestFrameReader(status)
        if (decision.action == AnalysisGovernorAction.KEEP) return true
        Log.w(TAG, "YUV analysis disabled (thermal status $status): ${decision.reason}")
        return false
    }

    private fun closeAnalysisReader() {
        val lease = analysisReaderLease
        analysisReaderLease = null
        analysisReader = null
        lease?.close()
    }

    private fun retirePipeline(pipeline: OpenCineLogGpuPipeline, after: () -> Unit = {}) {
        if (!retiringPipelines.add(pipeline)) return
        pipeline.closeAsync().whenComplete { _, failure ->
            cameraExecutor.execute {
                if (failure != null) {
                    failNativeRetirement(failure)
                    listener?.onFailure("gpu-retirement-failed", failure.message ?: "GPU resources remain unretired.", false)
                    return@execute
                }
                retiringPipelines.remove(pipeline)
                after()
                runPendingPreviewStart()
                finishCloseIfIdle()
            }
        }
    }

    /** A throwing constructor still owns its GL cleanup; do not lose that retirement receipt. */
    private fun observeFailedInitialization(failure: Throwable) {
        if (failure !is GpuPipelineInitializationFailure) return
        val completion = failure.retirement()
        initializationRetirements.add(completion)
        completion.whenComplete { _, retirementError ->
            cameraExecutor.execute {
                if (retirementError != null) {
                    failNativeRetirement(retirementError)
                    listener?.onFailure("gpu-initialization-retirement-failed", retirementError.message ?: "GPU initialization resources remain unretired.", false)
                    return@execute
                }
                initializationRetirements.remove(completion)
                runPendingPreviewStart()
                finishCloseIfIdle()
            }
        }
    }

    private fun failNativeRetirement(failure: Throwable) {
        if (retirementFailure == null) retirementFailure = failure
        nativeRetirement.completeExceptionally(requireNotNull(retirementFailure))
    }

    private fun closeCameraDevice(device: CameraDevice) {
        closingCamera = device
        runCatching { device.close() }.onFailure(::failNativeRetirement)
    }

    private fun closeResources() {
        pendingPreviewStartedReceipt = null
        burstRequest.get()?.let { retireBurst(it, restore = false) }
        accumulationRequest.get()?.let { retireAccumulation(it, restore = false) }
        bracketRequest.get()?.let { retireBracket(it, restore = false) }
        legacyStillRequest.set(null)
        photoRequest.get()?.let { retirePhoto(it, restore = false) }
        pendingJpegs.values.forEach { compressedHandoff.release(it.ticket) }
        pendingJpegs.clear()
        compressedHandoff.cancelAll()
        compressedReadFailures.clear()
        legacyJpegTimestamps.clear()
        cancelFocusPullLocked()
        clearRecordingWhiteBalance(resubmit = false)
        recordingSessionGeneration++
        clearTapFocusLocked(notify = true)
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { session?.close() }
        session = null
        camera?.let(::closeCameraDevice)
        camera = null
        runCatching { jpegReader?.close() }
        jpegReader = null
        runCatching { rawReader?.close() }
        rawReader = null
        runCatching { closeAnalysisReader() }
        analysisReader = null
        clearPendingRaw()
        repeatingBuilder = null
        processingDefaults.clear()
        if (recording) runCatching { recorder?.stop() }
        releaseRecorder()
        logPipeline?.let(::retirePipeline)
        logPipeline = null
        synchronizeSubjectOutput()
        passthroughVideoPipeline = false
        gpuPreviewEnabled = false
        gpuPhotoPreviewEnabled = false
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

    private fun ownsRecorderRequest(request: RecorderRequest): Boolean =
        recorderRequest.get() === request && !disposed.get() && generation == request.generation && camera === request.device

    private fun releaseRecorder(expected: RecorderRequest? = recorderRequest.get()) {
        if (recorderRequest.get() !== expected) return
        recording = false
        runCatching { recorder?.reset() }
        val releaseFailure = runCatching { recorder?.release() }.exceptionOrNull()
        if (releaseFailure != null) {
            failNativeRetirement(releaseFailure)
            return // Keep the recorder/admission fence; an attempted release is not retirement.
        }
        recorder = null
        recordSurface = null
        // Release the acceptance fence last, never while MediaRecorder still owns native I/O.
        recorderRequest.compareAndSet(expected, null)
    }

    private class BoundedDngOutput : ByteArrayOutputStream() {
        override fun write(value: Int) {
            check(count < StillImagePayload.MAX_DNG_BYTES) { "DNG exceeds its byte limit" }
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length >= 0 && length <= StillImagePayload.MAX_DNG_BYTES - count) { "DNG exceeds its byte limit" }
            super.write(bytes, offset, length)
        }
    }

    private fun scheduleRawDrain(owner: PhotoRequest) {
        val reader = owner.raw ?: return
        imageHandler.post {
            if (photoRequest.get() !== owner || owner.cancelled.get() || disposed.get() ||
                owner.generation != generation || reader !== rawReader) return@post
            repeat(2) {
                val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return@post
                queueRawImage(reader, owner.generation, image)
            }
        }
    }

    private fun purgeUnmatchedRaw(owner: PhotoRequest, timestamp: Long) {
        if (owner.raw == null || !ownsPhoto(owner)) return
        pendingRawFrames.keys.filter { it != timestamp }.forEach { pendingRawFrames.remove(it)?.close() }
        val staleQueued = synchronized(rawImageLock) {
            queuedRawImages.filter { it.timestamp != timestamp }.also { queuedRawImages.removeAll(it.toSet()) }
        }
        staleQueued.forEach { it.close() }
        // acquireNextImage may previously have hit its two-owned-image limit. Explicitly
        // drain after freeing slots instead of waiting for an unrelated future frame.
        scheduleRawDrain(owner)
    }

    /** Register before posting: close also owns frames whose executor callback is discarded. */
    private fun queueRawImage(reader: ImageReader, readerGeneration: Long, image: Image) {
        val queued = synchronized(rawImageLock) {
            if (disposed.get() || readerGeneration != generation || reader !== rawReader) false
            else queuedRawImages.add(image)
        }
        if (!queued) { image.close(); return }
        cameraExecutor.execute {
            if (!synchronized(rawImageLock) { queuedRawImages.remove(image) }) return@execute
            val owner = photoRequest.get()
            if (owner == null || !ownsPhoto(owner) || owner.raw !== reader || readerGeneration != generation) {
                image.close()
                return@execute
            }
            val timestamp = image.timestamp
            val expected = owner.result?.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
            if (expected != null && timestamp != expected) {
                image.close()
                scheduleRawDrain(owner)
                return@execute
            }
            pendingRawFrames.put(timestamp, image)?.close()
            while (pendingRawFrames.size > 2) pendingRawFrames.remove(pendingRawFrames.keys.first())?.close()
            deliverPhotoIfReady(owner)
        }
    }

    private fun clearPendingRaw() {
        val queued = synchronized(rawImageLock) { queuedRawImages.toList().also { queuedRawImages.clear() } }
        (pendingRawFrames.values.toList() + queued).forEach { runCatching { it.close() } }
        pendingRawFrames.clear()
    }

    private fun analyzeImage(image: Image): Camera2Analysis {
        val options = monitoringOptions
        val step = maxOf(4, (image.width + 319) / 320, (image.height + 179) / 180)
        val sampledWidth = (image.width + step - 1) / step
        val sampledHeight = (image.height + step - 1) / step
        val rgbSamples = ByteArray(sampledWidth * sampledHeight * 3)
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
        for (y in 0 until image.height step step) {
            var previous = -1
            for (x in 0 until image.width step step) {
                val yIndex = y * yPlane.rowStride + x * yPlane.pixelStride
                if (yIndex >= yBuffer.limit()) continue
                val yCode = yBuffer.get(yIndex).toInt() and 0xff
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
                val c = (yCode - 16).coerceAtLeast(0)
                val d = u - 128
                val e = v - 128
                val red = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val green = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val blue = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                val luma = (54 * red + 183 * green + 19 * blue + 128) shr 8
                val sample = ((y / step) * sampledWidth + x / step) * 3
                rgbSamples[sample] = red.toByte(); rgbSamples[sample + 1] = green.toByte(); rgbSamples[sample + 2] = blue.toByte()
                lumaHistogram[luma * SCOPE_HISTOGRAM_BINS / 256]++
                redHistogram[red * SCOPE_HISTOGRAM_BINS / 256]++
                greenHistogram[green * SCOPE_HISTOGRAM_BINS / 256]++
                blueHistogram[blue * SCOPE_HISTOGRAM_BINS / 256]++
                val cell = (y * 9 / image.height).coerceIn(0, 8) * 16 + (x * 16 / image.width).coerceIn(0, 15)
                counts[cell]++
                if (luma * 100 >= options.zebraHighPercent * 255 || options.zebraShadowEnabled && luma * 100 <= options.zebraLowPercent * 255) zebraHits[cell]++
                if (previous >= 0 && kotlin.math.abs(luma - previous) >= options.peakingThreshold) focusHits[cell]++
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
            scopes = analyzeMonitoringRgb(sampledWidth, sampledHeight, rgbSamples, options, MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR),
        )
    }

    /**
     * Completes after Camera2 and the operator GPU window retire, and after any predecessor
     * admission. Auxiliary subject output keeps its separate worker/display lease: a stuck
     * optional consumer must not block an otherwise retired camera/operator owner.
     * A defensive view prevents callers from granting admission by completing this future.
     */
    fun closeAsync(): CompletableFuture<Unit> {
        close()
        return closeCompletion.thenApply { it }
    }

    override fun close() {
        if (!disposed.compareAndSet(false, true)) return
        cameraExecutor.execute {
            closeStarted = true
            val released = ownerLease?.releaseAfter(nativeRetirement) ?: nativeRetirement
            released.whenComplete { _, failure ->
                if (failure == null) closeCompletion.complete(Unit) else closeCompletion.completeExceptionally(failure)
            }
            generation++
            pendingPreviewStart = null
            subjectTarget = null
            listener = null
            try {
                closeResources()
                finishCloseIfIdle()
            } catch (failure: Throwable) {
                failNativeRetirement(failure)
            }
        }
    }

    private fun finishCloseIfIdle() {
        if (closeStarted && !cameraOpening && closingCamera == null && retiringPipelines.isEmpty() &&
            initializationRetirements.isEmpty()) {
            cameraExecutor.close()
            imageThread.quitSafely()
            nativeRetirement.complete(Unit)
        }
    }

    private fun lensOrder(lensFacing: Int): Int = when (lensFacing) {
        CameraCharacteristics.LENS_FACING_BACK -> 0
        CameraCharacteristics.LENS_FACING_FRONT -> 1
        else -> 2
    }

    companion object {
        const val FOCUS_PULL_TICK_MS = 33L
        const val DEFAULT_TARGET_FPS = 30
        internal const val MIME_AVC = "video/avc"
        internal const val MIME_HEVC = "video/hevc"
        private const val MIN_SELECTABLE_FPS = 10
        private const val MAX_SELECTABLE_FPS = 60
        private const val METADATA_PERIOD_MS = 500L
        private const val ZOOM_REPORT_PERIOD_MS = 33L
        private const val TAP_FOCUS_HOLD_MS = 3_000L
        internal const val SCOPE_HISTOGRAM_BINS = 64
        private const val MAX_PREVIEW_PIXELS = 1920L * 1080L
        private const val TARGET_PREVIEW_PIXELS = 1920L * 1080L
        private const val MAX_BURST_IMAGES = 10
        private const val MOTOROLA_IS_CAMERA2_KEY = "com.lenovo.moto.clientapp.is_motcamera2"
        private const val MOTOROLA_CURRENT_MODE_KEY = "com.lenovo.moto.clientapp.current_mode"
        private const val MOTOROLA_SLOW_MOTION_MODE = 3
        private const val TAG = "Camera2PreviewEngine"
    }
}

/**
 * True only when a repeating-request failure is Camera2 refusing the zoom value just requested:
 * the ratio changed since the last accepted request and the framework raised
 * [IllegalArgumentException] naming a zoom key. Anything else (closed session, access errors,
 * unrelated invalid arguments) is a real failure and must not be silently rolled back.
 */
internal fun isZoomRejection(failure: Throwable, zoomChanged: Boolean): Boolean {
    if (!zoomChanged || failure !is IllegalArgumentException) return false
    val message = failure.message.orEmpty()
    return message.contains("SCALER_CROP_REGION", ignoreCase = true) ||
        message.contains("CONTROL_ZOOM_RATIO", ignoreCase = true) ||
        message.contains("zoom", ignoreCase = true)
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
