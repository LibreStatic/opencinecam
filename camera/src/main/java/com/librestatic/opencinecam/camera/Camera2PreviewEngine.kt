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
import android.hardware.camera2.CameraMetadata
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
import com.librestatic.opencinecam.core.model.Knowledge
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.core.model.QuirkEffect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import com.librestatic.opencinecam.media.video.VideoCodec
import com.librestatic.opencinecam.media.video.VideoCodecPreference
import com.librestatic.opencinecam.media.video.hevcSdrSupported
import com.librestatic.opencinecam.media.video.resolveVideoCodec
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
    /**
     * HLG mode can record HDR10 (PQ) at this size and rate: the camera advertises HDR10 and a hardware
     * encoder advertises HEVC Main10 HDR10 ([Hdr10Video.isOffered]). Never set for the ISP-derived tier.
     */
    val hdr10: Boolean = false,
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
    /** API 35+ with CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY advertised. */
    val lowLightBoostSupported: Boolean = false,
    val afLockSupported: Boolean = false,
    val zoomRatioRange: ClosedFloatingPointRange<Float>? = null,
    val supportsZoomRatioApi: Boolean = false,
    val supportsZoomSettingsOverride: Boolean = false,
    val digitalZoomMaxRatio: Float? = null,
    val opticalAnchors: List<ZoomAnchor> = emptyList(),
    val supportsHfrZoom: Boolean = false,
    val kelvinRange: IntRange? = null,
    /** SENSOR_INFO_PHYSICAL_SIZE in mm (width, height), when advertised. */
    val sensorSizeMm: Pair<Float, Float>? = null,
    val tintSupported: Boolean = false,
    val availableAwbModes: Set<Int> = emptySet(),
    val exposureCapabilities: ExposureCapabilities = ExposureCapabilities(),
    val imageProcessingCapabilities: ImageProcessingCapabilities = ImageProcessingCapabilities(),
    val awbLockSupported: Boolean = false,
    val torchCapabilities: TorchCapabilities = TorchCapabilities(flashAvailable),
    val timestampSourceRealtime: Boolean = false,
    val photoFlashCapabilities: PhotoFlashCapabilities = PhotoFlashCapabilities(),
    val heicSize: Size? = null,
    /** Standard `SCALER_AVAILABLE_STREAM_USE_CASES`; empty below API 33 or without the STREAM_USE_CASE capability. */
    val streamUseCases: Set<Long> = emptySet(),
    /** Every advertised JPEG output, largest first; [jpegSize] is its head. */
    val jpegSizes: List<Size> = listOfNotNull(jpegSize),
    /** Every advertised HEIC output, largest first; [heicSize] is its head. */
    val heicSizes: List<Size> = listOfNotNull(heicSize),
    /** Compressed still sizes only public physical cameras advertise, keyed by physical id (OCC-PLAN-069). */
    val physicalJpegSizes: Map<String, List<Size>> = emptyMap(),
    /** HEIC counterparts of [physicalJpegSizes]; empty when the logical camera has no HEIC output. */
    val physicalHeicSizes: Map<String, List<Size>> = emptyMap(),
    val aeCompensationStepNumerator: Int = 0,
    val aeCompensationStepDenominator: Int = 1,
    /** OCC-PLAN-068 U7: advertised STATISTICS_FACE_DETECT_MODE values; empty when the request key is absent. */
    val faceDetectModes: Set<Int> = emptySet(),
    /** CONTROL_ENABLE_ZSL is an advertised capture request key; stills may ask for the zero-shutter-lag path. */
    val zslSupported: Boolean = false,
    /** The camera's night CameraExtensionCharacteristics outputs (API 31+); null when it offers none. */
    val nightExtension: NightExtensionCapability? = null,
    /** Qualcomm/Motorola auto frame rate (IQdynamicFPS) can be switched both ways; see [DynamicFps]. */
    val lowLightAutoFps: Boolean = false,
    /** Set on the descriptor of a lens-locked session, built from the physical camera (PLAN-070); null otherwise. */
    val lensLock: LensLockBinding? = null,
) {
    /** The id the app knows this camera by: the logical id of a lens-locked session, else [cameraId]. */
    val logicalCameraId: String get() = lensLock?.logicalId ?: cameraId

    /** The id device-profile quirks resolve against; they are keyed by the logical id. */
    val quirkCameraId: String get() = quirkCameraIdOf(cameraId, lensLock)

    /** The first focal length as a 35 mm equivalent; inferred from the sensor size, null without it. */
    val equivalentFocalLengthMm: Float? get() = focalLengthsMm.firstOrNull()?.let { equivalentFocalLengthMm(it, sensorSizeMm?.first, sensorSizeMm?.second) }

    /** True when [width]x[height] is a compressed still only a public physical camera advertises (OCC-PLAN-069). */
    fun isPhysicalOnlyStill(width: Int, height: Int, heic: Boolean): Boolean {
        val size = Size(width, height)
        val logical = if (heic) heicSizes else jpegSizes
        if (size in logical) return false
        val physical = if (heic) physicalHeicSizes else physicalJpegSizes
        return physical.values.any { size in it }
    }

    /** FPS LOCK can hold the shutter at 1/fps through Android 16 exposure-time priority; see [CadenceGuard]. */
    val fpsLockSupported: Boolean
        get() = CadenceGuard.supported(Build.VERSION.SDK_INT, exposureCapabilities)

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
    /** Null unless the submitted request used the low-light boost AE mode and the camera reported its state. */
    val lowLightBoostActive: Boolean? = null,
    /** Qualcomm exposure_metering_mode from the result, when the device reports it. */
    val meteringMode: Int? = null,
    val opticalStabilization: Int? = null,
    val videoStabilization: Int? = null,
    val noiseReduction: Int? = null,
    val edgeEnhancement: Int? = null,
    val toneMapMode: Int? = null,
    val submittedToneMapMode: Int? = null,
    val cropRegion: List<Int>? = null,
    val submittedImageProcessing: ImageProcessingDefaults? = null,
    val pictureControls: PictureControlReadback? = null,
    val awbLocked: Boolean? = null,
    val afMode: Int? = null,
    val submittedAfMode: Int? = null,
    val submittedFocusDistanceDiopters: Float? = null,
    /** Time the sensor takes to read the frame out, from first to last row; null when not reported. */
    val rollingShutterSkewNs: Long? = null,
    /** COLOR_CORRECTION_GAINS (R, Geven, Godd, B) of the frame; the lens calibration compares them with the calibrated scene's. */
    val awbGains: List<Float>? = null,
)

data class Camera2Analysis(
    val histogram: List<Float>,
    val redHistogram: List<Float>,
    val greenHistogram: List<Float>,
    val blueHistogram: List<Float>,
    val zebraCells: List<Boolean>,
    val capturedAtElapsedRealtimeMs: Long,
    val columns: Int = 16,
    val rows: Int = 9,
    val scopes: MonitoringScopeFrame? = null,
    /** In-focus edges; null while focus peaking is off, so the cost is only paid when shown. */
    val focusPeaking: FocusPeakingMask? = null,
    /** Where [histogram] (luma only) came from; the red, green and blue ones are always the frame's. */
    val histogramSource: HistogramSource = HistogramSource.FRAME,
    /** Sensor timestamp of the analysed frame (the same clock as the capture results' SENSOR_TIMESTAMP); null when unknown. */
    val sensorTimestampNs: Long? = null,
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
    /** Watches the take's routed input and applies the loss policy (OCC-AUDIO-012). */
    val inputGuard: com.librestatic.opencinecam.media.audio.AudioInputRouteGuard? = null,
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

/** Why the engine's CPU (YUV) and GPU scope analysis is currently suspended; NONE while it runs. */
enum class AnalysisSuspension { NONE, THERMAL }

interface Camera2PreviewListener {
    fun onOperatorLutStatus(status: OperatorLutStatus) = Unit
    fun onPhotoFlashResult(report: PhotoFlashReport) = Unit
    fun onStillCaptured(capture: CapturedStill) = Unit
    /** Night extension processing progress, 0..100 (API 34+; never called where the platform omits it). */
    fun onNightCaptureProgress(percent: Int) = Unit
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
    /** The sensor timestamp of the first frame that went into the recorded file (GL thread). */
    fun onRecordingFirstFrame(sensorTimestampNs: Long) = Unit
    fun onRecordingStopped(success: Boolean)
    fun onFailure(code: String, message: String, recoverable: Boolean)
    /**
     * A control update (locks, focus, exposure, white balance, zoom) was refused while the session
     * kept running on its previous request. Never a reason to finalize or discard a running take.
     */
    fun onControlRejected(code: String, message: String) = onFailure(code, message, true)
    /** Adaptive policy applied a control other than requested (clamped, or fell back to auto); a transient disclosure, never a failure. */
    fun onControlAdapted(code: String, message: String) = Unit
    /**
     * Adaptive recorded a normal SDR take with a software AVC encoder because no hardware encoder fits
     * (before [onRecordingStarted]); a persistent disclosure for the take, never a failure.
     */
    fun onSoftwareVideoEncoder(codecName: String) = Unit
    /**
     * Strict refused to start a take (stable [code], e.g. `video-hardware-encoder-unavailable`). The session
     * and preview are unaffected, so the default is not an error.
     */
    fun onRecordingStartRefused(code: String, message: String) = onFailure(code, message, true)
    fun onTorchRejected(message: String) = Unit
    fun onPreviewSurfaceLost(message: String) = Unit
    fun onTapFocusState(state: TapFocusState) = Unit
    fun onZoomRangeAvailable(min: Float, max: Float, anchors: List<ZoomAnchor>) = Unit
    fun onZoomEffective(ratio: Float) = Unit
    fun onZoomRejected(requested: Float, accepted: Float) = Unit
    fun onAeLockChanged(active: Boolean) = Unit
    /**
     * The physical camera the compressed still is routed to ([requestedPhysicalId], null on the
     * logical route) against the one the still's own result confirms ([reportedPhysicalId], null when
     * the result carries no physical results). At configuration (unconfirmed) and once per still. Camera thread.
     */
    fun onPhysicalRoute(requestedPhysicalId: String?, reportedPhysicalId: String?) = Unit
    /**
     * The physical lens serving the logical streams, from the preview results. Informational: it follows
     * zoom and is never a discrepancy. Only on change, camera thread.
     */
    fun onActiveLens(physicalId: String?) = Unit
    /**
     * Once the session is configured: the lens-locked physical camera [physicalId] and its zoom
     * range on the logical scale (anchor x physical range), or both null when the session is not
     * lens-locked. While locked, [onZoomRangeAvailable] is not called. Camera thread.
     */
    fun onLensLock(physicalId: String?, uiZoomRange: ClosedFloatingPointRange<Float>?) = Unit
    /**
     * A lens lock was requested but the logical camera was opened instead; [reason] is one of
     * [LensLockUnavailable]. Camera thread.
     */
    fun onLensLockUnavailable(physicalId: String, reason: String) = Unit
    /**
     * Once the session is configured on the lens lock's GPU-crop route (PLAN-070 slice 4): the
     * logical camera runs with its zoom pinned at [physicalId]'s anchor and zooms by a GPU crop over
     * [uiZoomRange], the same logical-scale range as the direct route. Replaces [onLensLock] and
     * [onZoomRangeAvailable] for that session; [onLensLockUnavailable] is not called. Camera thread.
     */
    fun onLensLockCrop(physicalId: String, uiZoomRange: ClosedFloatingPointRange<Float>) = Unit
    /**
     * On the GPU-crop route, the HAL's active physical camera left (true) or came back to (false)
     * the locked lens, debounced over [LensSwitchDebounce.DEFAULT_THRESHOLD] results. Camera thread.
     */
    fun onLensLockSwitched(switched: Boolean) = Unit
    /**
     * Once the regular preview session is configured: what its graph lost to the fallbacks, or
     * [PreviewGraphLoss.NONE]. The HFR, LOG and night sessions do not report it. Camera thread.
     */
    fun onPreviewGraphLoss(loss: PreviewGraphLoss) = Unit
    /**
     * A take that asked for HEVC records H.264 because the encoder refused the exact configuration
     * ([reason] is for logs); before [onRecordingStarted].
     */
    fun onVideoCodecFallback(reason: String) = Unit
    /** A capture-result audit finding at WARNING level (cadence, dropped frames); repeats are the listener's to deduplicate. Camera thread. */
    fun onAuditWarning(message: String) = Unit
    /** FPS LOCK is holding the shutter at 1/fps because auto exposure was stretching the frame time. Camera thread. */
    fun onCadenceGuardChanged(engaged: Boolean) = Unit
    /** Frame delivery broke down for several seconds of the take (see [SustainedCadenceGuard]); [action] follows the recording policy. Camera thread. */
    fun onSustainedCadenceLoss(action: CaptureAuditAction, detail: String) = Unit
    fun onAfLockChanged(state: LockState) = Unit
    fun onFocusPullFinished() = Unit
    fun onFocusPullStarted(targetDiopters: Float) = Unit
    fun onFocusPullCancelled() = Unit
    fun onTimelapseProgress(progress: TimelapseProgress) = Unit
    fun onTimelapsePauseChanged(status: TimelapsePauseStatus) = Unit
    fun onFocusSelectionChanged(diopters: Float?) = Unit
    /** Emitted on the camera executor only when the analysis suspension state changes. */
    fun onAnalysisSuspended(reason: AnalysisSuspension) {}
    /** OCC-PLAN-068 U7, camera thread: framing transitions only; never carries face geometry. */
    fun onSubjectFramingChanged(status: SubjectFramingStatus) = Unit
}

internal class CloseTolerantCameraExecutor : Executor, AutoCloseable {
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

internal data class TapFocusRequestTag(val token: Long)

/**
 * Concrete public-API Camera2 preview/still owner. Calls are serialized on one executor and
 * stale callbacks are rejected by generation so a destroyed Surface cannot regain ownership.
 *
 * The public API and the shared state live here; the internal steps are extensions grouped by
 * responsibility in Camera2StillCapture, Camera2SessionGraphs, Camera2RequestControls,
 * Camera2FocusControls, Camera2ZoomAndLens, Camera2CadenceGuard, Camera2Recording and
 * Camera2Descriptors.
 */
class Camera2PreviewEngine(
    context: Context,
    private val ownerAdmission: CaptureOwnerAdmission = CaptureOwnerAdmission.processGlobal,
    internal val deviceQuirks: DeviceQuirks = DeviceQuirks.None,
) : AutoCloseable {
    internal val manager = context.getSystemService(CameraManager::class.java)
    internal val appContext = context.applicationContext
    private val powerManager = context.getSystemService(PowerManager::class.java)
    internal val cameraExecutor = CloseTolerantCameraExecutor()
    internal val recorderLevelHandler = Handler(android.os.Looper.getMainLooper())
    @Volatile internal var recorderLevelSink: ((AudioLevelSnapshot) -> Unit)? = null
    private val requestComposer = CaptureRequestComposer()
    private val imageThread = HandlerThread("OpenCineCamImage").apply { start() }
    internal val imageHandler = Handler(imageThread.looper)
    @Volatile internal var generation = 0L
    internal var camera: CameraDevice? = null
    internal var session: CameraCaptureSession? = null
        set(value) {
            if (field !== value) pendingPreviewStartedReceipt = null
            field = value
        }
    internal data class PreviewStartedReceipt(val session: CameraCaptureSession, val generation: Long,
        val descriptor: Camera2CameraDescriptor)
    // cameraExecutor-confined: request replacements share the real graph's one start receipt.
    internal var pendingPreviewStartedReceipt: PreviewStartedReceipt? = null
    internal var previewSurface: Surface? = null
    /** The live night extension session (API 31+), which replaces [session] and every still reader. */
    @Volatile internal var nightSession: NightExtensionSession? = null
    internal val nightExtensionCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<NightExtensionCapability>>()
    internal var jpegReader: ImageReader? = null
    internal var rawReader: ImageReader? = null
    internal var analysisReader: ImageReader? = null
    internal var analysisReaderLease: ReaderLease<ImageReader>? = null
    internal val rawImageLock = Any()
    internal val queuedRawImages = mutableSetOf<Image>()
    internal val pendingRawFrames = linkedMapOf<Long, Image>()
    internal var activeStillFormat = StillPhotoFormat.JPEG
    /** Requested compressed still size; null or unadvertised falls back to the largest output. */
    internal var activeStillSize: Size? = null

    /** True once a rejected physical-still routing retired this graph's physical stills (OCC-PLAN-069). */
    internal var physicalStillFallbackTaken = false
    /** The physical camera the current graph routes the compressed still to; null on the logical route. */
    @Volatile internal var routedStillPhysicalId: String? = null
    /** Audit warnings already forwarded for the current graph; cameraExecutor only, so a warning repeating every frame reaches the listener once. */
    internal val forwardedAuditWarnings = HashSet<String>()
    internal var lastActiveLensReport: Pair<Boolean, String?>? = null
    internal var repeatingBuilder: CaptureRequest.Builder? = null
    internal val instantAec = InstantAecMachine()
    internal var lensSwitchTraceSession: CameraCaptureSession? = null
    internal var lensSwitchTrace: LensSwitchTrace? = null
    internal val zoomLag = ZoomLagCompensator()
    internal var zoomLagSession: CameraCaptureSession? = null
    @Volatile internal var smoothLensSwitch = false
    internal var instantAecSession: CameraCaptureSession? = null
    internal var instantAecKey: CaptureRequest.Key<Int>? = null
    internal var instantAecModes: IntArray? = null
    internal var instantAecPhysicalId: String? = null
    internal class RecorderRequest(val generation: Long, val device: CameraDevice) {
        val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    // Held from synchronous acceptance until native reset/release finishes on cameraExecutor.
    internal val recorderRequest = java.util.concurrent.atomic.AtomicReference<RecorderRequest?>(null)
    internal data class PhotoTag(val id: Long, val trigger: Boolean = false)
    internal data class JpegPayload(val bytes: ByteArray, val width: Int, val height: Int,
        val kind: StillImageKind, val ticket: StillImageHandoff.Ticket<JpegPayload>)
    internal enum class LegacyPhotoDelivery { NONE, JPEG, DNG }
    internal class PhotoRequest(
        val id: Long, val generation: Long, val device: CameraDevice,
        val session: CameraCaptureSession, val reader: ImageReader,
        val descriptor: Camera2CameraDescriptor, val listener: Camera2PreviewListener,
        val selection: PhotoFlashSelection, val format: StillPhotoFormat,
        val quality: Int, val raw: ImageReader?, val legacy: LegacyPhotoDelivery,
        val bracket: BracketRequest? = null, val bracketIndex: Int = 0,
        val accumulation: AccumulationRequest? = null,
        val aspect: PhotoAspectSelection = PhotoAspectSelection(),
        val burst: BurstRequest? = null,
        val zeroShutterLag: Boolean = false,
        /** The lens lock's GPU crop at the shutter (PLAN-070 slice 4); null off that route. */
        val lensCropFactor: Float? = null,
    ) {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        /** Engine admission time, the closest the camera layer gets to the shutter press. */
        val pressedAtNs = SystemClock.elapsedRealtimeNanos()
        var zslApplied = false
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
    internal class BurstRequest(
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
    internal val burstRequest = java.util.concurrent.atomic.AtomicReference<BurstRequest?>(null)
    internal class BracketRequest(
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
    internal class AccumulationRequest(
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
    internal val accumulationRequest = java.util.concurrent.atomic.AtomicReference<AccumulationRequest?>(null)
    internal val stillAdmissionLock = Any()
    internal val bracketRequest = java.util.concurrent.atomic.AtomicReference<BracketRequest?>(null)
    internal val legacyStillRequest = java.util.concurrent.atomic.AtomicReference<Any?>(null)
    internal val photoIds = java.util.concurrent.atomic.AtomicLong()
    internal val photoRequest = java.util.concurrent.atomic.AtomicReference<PhotoRequest?>(null)
    // Payloads may precede their Camera2 result callbacks. Only an explicitly classified
    // legacy request or the matching PHOTO result can release one to the saver.
    internal val pendingJpegs = linkedMapOf<Long, JpegPayload>()
    internal val compressedHandoff = StillImageHandoff<JpegPayload>(StillImagePayload.MAX_COMPRESSED_BYTES)
    internal val compressedReadFailures = linkedMapOf<Long, String>()
    internal val legacyJpegTimestamps = linkedSetOf<Long>()
    internal var recorder: MediaRecorder? = null
    @Volatile internal var activeRecorderGuard: com.librestatic.opencinecam.media.audio.AudioInputRouteGuard? = null
    internal var recordSurface: Surface? = null
    @Volatile internal var recording = false
    internal var recordingSessionGeneration = 0L
    // Camera thread only: the recorder session whose repeating request was stopped, and the
    // continuation that stops MediaRecorder once that session reports no captures in flight.
    internal var drainingRecorderSession: CameraCaptureSession? = null
    internal var recorderDrainContinuation: (() -> Unit)? = null
    // Fault qualification observes the real platform callback; production leaves this unset.
    internal var recordingSessionObserver: ((SessionConfiguration) -> Unit)? = null
    @Volatile internal var activeDescriptor: Camera2CameraDescriptor? = null
    internal var listener: Camera2PreviewListener? = null
    internal var lastMetadataAtMs = 0L
    internal var lastAnalysisAtMs = 0L
    internal var displayRotationDegrees = 0
    internal var requestedIso: Int? = null
    internal var requestedExposureNs: Long? = null
    internal var requestedFocusDiopters: Float? = null
    internal var requestedFocusCameraId: String? = null
    internal var requestedWhiteBalance: WhiteBalanceSelection = WhiteBalanceSelection.Auto
    internal val recordingWbGate = RecordingWhiteBalanceGate()
    internal var recordingWbToken = 0L
    internal var heldRecordingWb: WhiteBalanceSelection? = null
    internal var recordingWbCallback: ((RecordingWhiteBalanceResult) -> Unit)? = null
    internal var recordingWbTimeout: Runnable? = null
    @Volatile internal var recordingWbMinimumTimestampNs: Long? = null
    internal var requestedAeCompensationIndex: Int? = null
    internal var requestedTorchEnabled = false
    internal var requestedTorchStrength: Int? = null
    internal var aeLockActive = false
    internal var afLockState = LockState.OFF
    internal var afLockFrozenDiopters: Float? = null
    internal var afLockScanToken: Long? = null
    internal var afLockResultReported = false
    internal var lastTapFocusForAfLock: MeteringRectangle? = null
    internal var tapFocusGeneration = 0L
    internal var activeTapFocusToken: Long? = null
    internal var tapAfRegion: MeteringRectangle? = null
    internal var tapAeRegion: MeteringRectangle? = null
    internal var tapFocusResultReported = false
    internal var requestedZoomRatio: Float = 1f
    private var lastAcceptedZoomRatio: Float = 1f
    internal var zoomOverrideArmed = false
    internal var lastZoomChangeAtMs = 0L
    internal var effectiveZoomRatio: Float = 1f
    internal var lastZoomReportedAtMs = 0L
    internal val restoreTapFocus = Runnable {
        cameraExecutor.execute { restoreContinuousFocusLocked() }
    }
    @Volatile private var operatorLutSelection: MonitorLut? = null
    @Volatile private var subjectLutSelection: MonitorLut? = null
    @Volatile internal var monitoringOptions = MonitoringOptions()
    @Volatile private var focusPeakingEnabled = false
    @Volatile internal var lensCorrectionTable: com.librestatic.opencinecam.camera.calibration.LensCorrectionTable? = null
    @Volatile internal var lensCalibration: Pair<com.librestatic.opencinecam.core.model.CalibrationSet, Boolean>? = null
    internal var logPipeline: OpenCineLogGpuPipeline? = null
        set(value) {
            field = value
            // The logical calibration never applies to a lens-locked session (PLAN-070).
            if (!lensLockEngaged()) {
                lensCorrectionTable?.let { value?.setLensCorrection(it) }
                lensCalibration?.let { (set, alignment) -> value?.setLensCalibration(set, alignment) }
            }
            calibrationReadback?.let { value?.setCalibrationReadback(it) }
            value?.setCropZoom(lensLockCrop?.factor ?: 1f)
            value?.setMonitoringOptions(monitoringOptions)
            value?.setFocusPeakingEnabled(focusPeakingEnabled)
            value?.setOperatorLut(operatorLutSelection)
            value?.setSubjectLut(subjectLutSelection)
            value?.setAnalysisSuspended(analysisSuspended)
            value?.setAnalysisBurst(analysisBurst)
            value?.setExposureCompensation(fpsLockRequested)
        }

    /**
     * Sets the per-physical-lens correction for the GPU preview and recording (null disables). It
     * applies to the current pipeline and to every pipeline the engine creates later. Direct
     * constrained high-speed recording has no GPU stage before the encoder and is not corrected.
     */
    fun setLensCorrection(table: com.librestatic.opencinecam.camera.calibration.LensCorrectionTable?) {
        lensCalibration = null
        lensCorrectionTable = table
        logPipeline?.setLensCorrection(table.takeIf { !lensLockEngaged() })
    }

    /**
     * Like [setLensCorrection], but each pipeline builds its own table for the aspect of the frames
     * it renders, so a session restart at another size keeps the margin right.
     */
    fun setLensCorrection(set: com.librestatic.opencinecam.core.model.CalibrationSet?, alignmentEnabled: Boolean) {
        lensCorrectionTable = null
        lensCalibration = set?.let { it to alignmentEnabled }
        logPipeline?.setLensCalibration(set.takeIf { !lensLockEngaged() }, alignmentEnabled)
    }

    @Volatile internal var photoLensCalibration: Pair<com.librestatic.opencinecam.core.model.CalibrationSet, Boolean>? = null

    /**
     * Sets the calibration applied to JPEG (and aspect-cropped HEIC) stills, or null for none. Each
     * still is corrected by the physical lens its own result names. DNG pixels and night-extension
     * stills are never corrected.
     */
    fun setPhotoLensCorrection(set: com.librestatic.opencinecam.core.model.CalibrationSet?, alignmentEnabled: Boolean) {
        photoLensCalibration = set?.let { it to alignmentEnabled }
    }

    @Volatile internal var calibrationReadback: OpenCineLogGpuPipeline.CalibrationReadbackListener? = null
    /** Touched on [cameraExecutor] only, except for the volatile read in the capture callbacks (same thread). */
    @Volatile internal var calibrationJoiner: com.librestatic.opencinecam.camera.calibration.CalibrationFrameJoiner? = null

    /**
     * Starts delivering calibration samples to [sink] (about 10 Hz): the uncorrected camera signal
     * in its source curve, 640 px wide, joined with the frame's capture metadata by sensor
     * timestamp. Needs the GPU pipeline; returns false without one. [onUnavailable] fires if the
     * readback fails. The sink runs on the camera executor; keep it short. Survives a pipeline
     * rebuild but not [stopCalibrationCapture].
     */
    fun startCalibrationCapture(
        sink: (com.librestatic.opencinecam.camera.calibration.CalibrationSample) -> Unit,
        onUnavailable: (String) -> Unit,
    ): Boolean {
        val pipeline = logPipeline ?: return false
        val joiner = com.librestatic.opencinecam.camera.calibration.CalibrationFrameJoiner { sink(it) }
        val listener = object : OpenCineLogGpuPipeline.CalibrationReadbackListener {
            override fun onFrame(width: Int, height: Int, rgba: java.nio.ByteBuffer, timestampNs: Long) {
                // The GL thread only reads pixels; the copy and the join happen on the camera executor.
                try {
                    cameraExecutor.execute {
                        if (calibrationJoiner !== joiner) return@execute
                        val bytes = ByteArray(width * height * 4)
                        rgba.get(bytes)
                        joiner.offerFrame(timestampNs, com.librestatic.opencinecam.camera.calibration.RgbaFrame(width, height, bytes))
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) { }
            }

            override fun onUnavailable(reason: String) { onUnavailable(reason) }
        }
        calibrationJoiner = joiner
        calibrationReadback = listener
        pipeline.setCalibrationReadback(listener)
        return true
    }

    /** The source curve of the GPU pipeline's signal, or null without a pipeline. */
    fun calibrationSourcePath(): com.librestatic.opencinecam.core.model.CalibrationSourcePath? = logPipeline?.lensSourcePath()

    fun stopCalibrationCapture() {
        calibrationReadback = null
        calibrationJoiner = null
        logPipeline?.setCalibrationReadback(null)
    }

    fun setOperatorLut(lut: MonitorLut?) {
        operatorLutSelection = lut
        logPipeline?.setOperatorLut(lut)
    }
    fun setSubjectLut(lut: MonitorLut?) {
        subjectLutSelection = lut
        logPipeline?.setSubjectLut(lut)
    }

    internal fun deliverGpuLutStatus(expectedGeneration: Long, owner: () -> OpenCineLogGpuPipeline?, status: OperatorLutStatus) {
        if (disposed.get()) return
        try { cameraExecutor.execute {
            val pipeline = owner()
            if (!disposed.get() && expectedGeneration == generation && pipeline != null && logPipeline === pipeline &&
                status.selectionId == monitorLutIdentity(operatorLutSelection)) listener?.onOperatorLutStatus(status)
        } } catch (_: java.util.concurrent.RejectedExecutionException) { /* Owner retired. */ }
    }

    /** Burst frames only feed the cadence loop; the UI and scopes keep their normal rate. */
    internal fun deliverAnalysisToListener(analysis: Camera2Analysis) {
        val now = SystemClock.elapsedRealtime()
        if (analysisBurst && now - lastListenerAnalysisMs < monitoringOptions.periodMs) return
        lastListenerAnalysisMs = now
        listener?.onAnalysis(ispHistogram.merge(analysis))
    }

    internal fun deliverGpuAnalysis(expectedGeneration: Long, owner: () -> OpenCineLogGpuPipeline?, analysis: Camera2Analysis) {
        // Queue behind open/close so a check cannot race a new descriptor or a cleared UI sample.
        if (disposed.get()) return
        try { cameraExecutor.execute {
            val pipeline = owner()
            if (!disposed.get() && expectedGeneration == generation && pipeline != null && logPipeline === pipeline) {
                observeCadenceLuma(analysis, "gpu")
                deliverAnalysisToListener(analysis)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { /* Terminal owner, no sample delivery. */ }
    }

    fun setMonitoringOptions(options: MonitoringOptions) {
        monitoringOptions = options
        logPipeline?.setMonitoringOptions(options)
    }

    /**
     * Whether analysis also builds the focus-peaking edge mask. It is kept apart from
     * [MonitoringOptions] because the UI matches a scope frame's options against the live ones to
     * decide freshness, and toggling peaking must not make the scopes look stale.
     */
    fun setFocusPeakingEnabled(enabled: Boolean) {
        focusPeakingEnabled = enabled
        logPipeline?.setFocusPeakingEnabled(enabled)
    }
    internal var passthroughVideoPipeline = false
    internal var gpuPreviewEnabled = false
    @Volatile internal var gpuPhotoPreviewEnabled = false
    // A constrained high-speed MediaRecorder take running under the GPU viewfinder: the encoder
    // surface stays direct and the pipeline only receives the preview share for its displays.
    @Volatile internal var recorderOverGpuPreview = false
    private var cameraOpening = false
    private var closingCamera: CameraDevice? = null
    private val retiringPipelines = mutableSetOf<OpenCineLogGpuPipeline>()
    private val initializationRetirements = mutableSetOf<CompletableFuture<Unit>>()
    private var ownerLease: CaptureOwnerAdmission.Lease? = null
    private var ownerReady = false
    internal var retirementFailure: Throwable? = null
    private val nativeRetirement = CompletableFuture<Unit>()
    private val closeCompletion = CompletableFuture<Unit>()
    private var closeStarted = false
    private var pendingPreviewStart: Runnable? = null
    internal val disposed = java.util.concurrent.atomic.AtomicBoolean(false)
    // Thermal scope governor. The platform delivers status changes on cameraExecutor, which owns
    // the governor, the active pipeline and the listener; the image thread only reads the flag.
    private val thermalGovernor = ThermalAnalysisGovernor()
    @Volatile internal var analysisSuspended = false
    /** Fast analysis around the emulated hold's handoff; frames still reach the UI only at the normal period. */
    @Volatile internal var analysisBurst = false
    private var lastListenerAnalysisMs = 0L
    internal var cadenceEngagedAtNs = 0L
    private var suspensionNotified: Pair<Camera2PreviewListener, AnalysisSuspension>? = null
    // cameraExecutor: DIAGNOSTIC-only capture-result audit of the current graph.
    internal var graphAudit: Pair<CameraCaptureSession, CaptureResultAuditor>? = null
    /** Set by the service for the length of a take; null (preview, photo) leaves the audit diagnostic only. */
    @Volatile internal var cadenceLossGuard: Pair<PolicyMode, SustainedCadenceGuard>? = null

    /**
     * Arms the sustained-cadence check for a take under [policy], or disarms it with null. The
     * auditor itself stays DIAGNOSTIC: its STRICT mode demands a request correlation a repeating
     * request does not have and stops on a single outlier, which would end ordinary takes.
     */
    fun setRecordingCadencePolicy(policy: PolicyMode?) {
        cadenceLossGuard = policy?.let { it to SustainedCadenceGuard() }
    }
    /** Strict (default) rejects out-of-range professional controls; Adaptive clamps and discloses. Camera-thread owned. */
    internal var controlPolicy = PolicyMode.STRICT
    /** The last (selection, camera, policy, outcome) reported, so request rebuilds do not repeat a notice. */
    internal var lastExposureGateKey: Any? = null

    /** Sets the policy for professional controls; call before [setProfessionalControls] so the next request honours it. */
    fun setControlPolicy(policy: PolicyMode) {
        cameraExecutor.execute { controlPolicy = policy; encoderPolicy = policy }
    }
    /** Set with [controlPolicy] on the camera thread; read by [startExistingGpuVideo] for the encoder gate. */
    @Volatile internal var encoderPolicy = PolicyMode.STRICT
    internal var lastCaptureAuditSummaryAtMs = 0L
    @Volatile internal var latestCaptureDiagnostics: CaptureAuditStats? = null
    @Volatile internal var latestStillShutterLatency: StillShutterLatency? = null
    private val thermalStatusListener = PowerManager.OnThermalStatusChangedListener(::observeThermalStatus)
    init {
        // minSdk 29: the listener API is always present, so no polling fallback is needed. The
        // platform posts the current status once on registration.
        runCatching { powerManager?.addThermalStatusListener(cameraExecutor, thermalStatusListener) }
            .onFailure { Log.w(TAG, "Thermal status listener unavailable; scopes are not thermally governed.", it) }
    }
    private data class SubjectTarget(val token: Long, val surface: Surface, val options: SubjectPreviewOptions, val onStatus: (SubjectPreviewStatus) -> Unit)
    private var subjectTarget: SubjectTarget? = null
    private var subjectPipeline: OpenCineLogGpuPipeline? = null
    private var attachedSubjectToken: Long? = null
    private var subjectAttempt = 0L
    private var subjectRetry: Runnable? = null
    internal var logPreviewEnabled = false
    private var logViewAssistEnabled = false
    @Volatile private var logGreyReference = OpenCineLogGreyReference.NATIVE
    internal var imageProcessing = ImageProcessingSelection()
    internal val processingDefaults = java.util.WeakHashMap<CaptureRequest.Builder, ImageProcessingDefaults>()
    internal val toneMapDefaults = java.util.WeakHashMap<CaptureRequest.Builder, Pair<Int?, android.hardware.camera2.params.TonemapCurve?>>()
    internal val pictureKeyCache = java.util.concurrent.ConcurrentHashMap<String, PictureControlKeys>()
    // OCC-PLAN-068 U7 (cameraExecutor-confined): optional face statistics for the out-of-frame warning.
    @Volatile internal var subjectFramingRequested = false
    internal val faceDetectGate = FaceDetectGraphGate()
    internal val subjectFramingTracker = SubjectFramingTracker()
    internal val faceDetectDefaults = java.util.WeakHashMap<CaptureRequest.Builder, Int?>()
    internal var professionalExposure: ExposureSelection? = null
    internal var requestedTargetFps = DEFAULT_TARGET_FPS
    /** Low-light auto frame rate is on for the active graph; every other graph sends an explicit 0. */
    @Volatile internal var dynamicFpsOn = false
    /** FPS LOCK is wanted for the active graph (the service only asks in VIDEO, LOG and HLG). */
    @Volatile internal var fpsLockRequested = false
    internal val cadenceGuard = CadenceGuard()
    /** The guard's verdict as applied to the repeating request; written on the camera executor only. */
    @Volatile internal var cadenceEngaged = false
    /** A re-issue was refused: stay out of the way until the next session or toggle. */
    internal var cadenceFaulted = false
    @Volatile internal var timelapseTake = false
    internal val cadenceIsoLoop = CadenceIsoLoop()
    internal val cadenceExposureRamp = CadenceExposureRamp()
    internal var cadenceRampStartedNs = 0L
    /** CONTROL_AE_MODE of the latest capture result, and what the hidden-gain measurement has seen since engage. */
    @Volatile internal var lastResultAeMode: Int? = null
    internal val cadenceAeLumas = ArrayDeque<Double>()
    internal var cadenceSawManual = false
    internal var cadenceManualFrames = 0
    internal var cadenceGainMeasured = true
    internal var cadenceQcomLogged = true
    /** ISO sent while the emulated hold (AE off) is engaged; null otherwise. Camera executor writes. */
    @Volatile internal var cadenceHeldIso: Int? = null
    /** The physical lens serving the logical streams per the latest result, and the last one the held ISO was measured on. */
    internal var cadenceLensId: String? = null
    internal val cadenceSensorTopCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    internal var cadenceMeasuredLensId: String? = null
    /** The lens the loop was last told about (engage or [CadenceIsoLoop.lensChanged]), and the one a ratio it reports starts from. */
    internal var cadenceLoopLensId: String? = null
    internal var cadenceRatioFromLensId: String? = null
    /** When the latest lens switch was seen, and the effective ISO of the latest result (for the logs). */
    internal var cadenceLensSwitchAtNs = 0L
    /** Until when [cadenceTrace] logs every result and analysed luma after a lens switch (debug builds, opt-in). */
    internal var cadenceTraceUntilNs = 0L
    internal var cadenceLastResultIso: String? = null
    internal var cadenceEvIndexAtEngage = 0
    internal var cadenceFirstFrameLogged = true
    internal var activeVideoProfile: Camera2VideoProfile? = null
    internal var activeLogProfile: Camera2LogProfile? = null
    internal var previousSensorTimestampNs: Long? = null
    internal var effectiveFps: Double? = null
    internal var lastReportedFocusDiopters: Float? = null
    internal val focusPullSession = FocusPullSession()
    internal var focusPullTask: Runnable? = null
    private val perCameraFocusMarks = java.util.concurrent.ConcurrentHashMap<String, Map<String, Float>>()

    internal val avcSurfaceEncoderCapabilities by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && !it.isAlias }
            .mapNotNull { info -> runCatching { info.getCapabilitiesForType("video/avc") }.getOrNull() }
            .filter { it.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) }
            .mapNotNull { it.videoCapabilities }
            .toList()
    }
    internal val hevcSurfaceEncoderCapabilities by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && !it.isAlias }
            .mapNotNull { info -> runCatching { info.getCapabilitiesForType("video/hevc") }.getOrNull() }
            .filter { it.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) }
            .mapNotNull { it.videoCapabilities }
            .toList()
    }
    @Volatile internal var recorderVideoCodec: VideoCodec? = null
    @Volatile private var recorderCodecFallback: String? = null
    @Volatile private var lastLogRecordingEvidence: OpenCineLogRecordingEvidence? = null
    @Volatile internal var lastRecordingLutEvidence: BakedLutEvidence? = null

    fun descriptors(targetWidth: Int, targetHeight: Int): List<Camera2CameraDescriptor> {
        descriptorTarget = targetWidth to targetHeight
        return manager.cameraIdList.mapNotNull { cameraId -> descriptor(cameraId, targetWidth, targetHeight) }
            .sortedWith(compareBy<Camera2CameraDescriptor> { lensOrder(it.lensFacing) }.thenBy { it.cameraId })
    }

    // ---- Lens lock (PLAN-070 slice 3, ADR-0036) ----

    /** The target size the app's descriptors were built for; a lens-locked physical descriptor uses the same. */
    @Volatile internal var descriptorTarget: Pair<Int, Int>? = null
    private val lensLockCandidateCache = java.util.concurrent.ConcurrentHashMap<String, List<LensLockCandidate>>()
    internal val physicalDescriptorCache = java.util.concurrent.ConcurrentHashMap<Triple<String, Int, Int>, java.util.Optional<Camera2CameraDescriptor>>()
    /** The logical descriptor the app passed for the current lens-locked session; null when not locked. */
    @Volatile internal var lockedLogicalDescriptor: Camera2CameraDescriptor? = null
    /**
     * Set by a lens-locked start until its session is configured. The single fallback point: a
     * failed physical open or configuration reopens the logical camera on the GPU-crop route
     * (slice 4) in the same generation. cameraExecutor only.
     */
    internal var lensLockWithdraw: ((String) -> Unit)? = null

    /**
     * The lens lock's GPU-crop route (PLAN-070 slice 4): the logical camera with its HAL zoom pinned
     * at [pinnedRatio] so SAT selects [binding]'s lens, and every zoom a GPU crop of [factor].
     */
    internal class LensLockCropRoute(
        val binding: LensLockBinding,
        val physicalZoomRange: ClosedFloatingPointRange<Float>,
        val pinnedRatio: Float,
    ) {
        /** The direct route's range on the logical scale: anchor x physical range. */
        val uiRange: ClosedFloatingPointRange<Float> = LensLockZoom.uiRange(binding.anchorRatio, physicalZoomRange)
        @Volatile var factor = 1f
    }

    /** The current session's GPU-crop route; null off it. Written on cameraExecutor. */
    @Volatile internal var lensLockCrop: LensLockCropRoute? = null

    /**
     * Physical ids whose direct open or configuration failed in this engine. Later starts of the
     * same lock (a mode change, the service forcing the GPU viewfinder) go straight to the GPU crop
     * instead of retrying the failing open each time. Cleared only with the engine ([close]).
     */
    internal val lensLockCropFallbacks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The GPU-crop route's lens-switch debounce; touched on the capture callback thread only. */
    internal val lensSwitchDebounce = LensSwitchDebounce()

    /** A system property on debug builds; null on release builds or when it cannot be read. */
    internal fun debugProperty(name: String): String? {
        if (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
        }.getOrNull()
    }

    /**
     * The lenses [logicalCameraId] can be locked to, sorted by anchor ratio: ids listed by its
     * `getPhysicalCameraIds()` that face the same way and have a computable field of view. Empty for
     * a camera that is not a logical multi-camera, or below API 28.
     */
    fun lensLockCandidates(logicalCameraId: String): List<LensLockCandidate> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        lensLockCandidateCache[logicalCameraId]?.let { return it }
        val candidates = runCatching {
            val logical = manager.getCameraCharacteristics(logicalCameraId)
            val facing = logical.get(CameraCharacteristics.LENS_FACING)
            val logicalFov = fovWidth(logical)
            logical.physicalCameraIds.filter { it != logicalCameraId }.mapNotNull { id ->
                val physical = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return@mapNotNull null
                if (physical.get(CameraCharacteristics.LENS_FACING) != facing) return@mapNotNull null
                val anchor = lensAnchorRatio(logicalFov, fovWidth(physical)) ?: return@mapNotNull null
                LensLockCandidate(
                    physicalId = id,
                    anchorRatio = anchor,
                    focalMm = physical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f,
                    physicalZoomRange = zoomRangeOf(physical) ?: (1f..1f),
                )
            }.sortedBy { it.anchorRatio }
        }.getOrDefault(emptyList())
        lensLockCandidateCache[logicalCameraId] = candidates
        return candidates
    }

    internal class LensLockPlan(
        val descriptor: Camera2CameraDescriptor,
        val videoProfile: Camera2VideoProfile?,
        val logProfile: Camera2LogProfile?,
        val stillSize: Size?,
    )

    fun preferredDescriptor(targetWidth: Int, targetHeight: Int): Camera2CameraDescriptor? =
        descriptors(targetWidth, targetHeight).firstOrNull()

    /**
     * The camera [descriptors] would most likely list first, from the lens facing alone. It skips
     * the stream and encoder checks, so a start can open the camera while those run.
     */
    fun likelyPreferredCameraId(): String? = runCatching {
        manager.cameraIdList.sortedWith(compareBy<String> { cameraId ->
            lensOrder(manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) ?: -1)
        }.thenBy { it }).firstOrNull()
    }.getOrNull()

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
        stillSize: Size? = null,
        nightExtension: Boolean = false,
        lowLightAutoFps: Boolean = false,
        fpsLock: Boolean = false,
        lensLock: LensLockBinding? = null,
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
            // An early open that is already under way or done is kept for adoption below.
            closeResources(keepWarm = warmOpen?.started == true)
            this.listener = listener
            // closeResources released both locks; a UI still showing them would toggle a no-op.
            listener.onAeLockChanged(false)
            listener.onCadenceGuardChanged(false)
            listener.onAfLockChanged(LockState.OFF)
            // A listener that joins while the scopes are suspended (or missed a resume) learns it once.
            notifyAnalysisSuspension(listener)
            // A lock for another camera is ignored. Manual focus distances belong to the lens that is opened.
            val requestedLock = lensLock?.takeIf { it.logicalId == descriptor.cameraId }
            if (requestedFocusCameraId != (requestedLock?.physicalId ?: descriptor.cameraId)) {
                requestedFocusDiopters = null
                requestedFocusCameraId = null
            }
            listener.onFocusSelectionChanged(requestedFocusDiopters)
            // The app's per-camera state stays on the logical camera, locked or not.
            listener.onOpening(descriptor)
            // The lock plan is resolved once per start; a failed physical open or configuration
            // withdraws it, and the start runs again on the logical camera in the same generation.
            var lockResolved = requestedLock == null
            var lockPlan: LensLockPlan? = null
            var cropRoute: LensLockCropRoute? = null
            lateinit var start: Runnable
            start = Runnable {
                if (disposed.get() || currentGeneration != generation || !surface.isValid) return@Runnable
                this.listener = listener
                if (!lockResolved) {
                    lockResolved = true
                    lockPlan = lensLockPlan(requestedLock!!, descriptor, listener, openCineLog, nightExtension,
                        gpuPhotoPreview, videoProfile, logProfile, stillFormat, stillSize, targetFps) { cropRoute = it }
                }
                val plan = lockPlan
                val logicalDescriptor = descriptor
                // From here the session runs on the physical descriptor when the lock applies.
                @Suppress("NAME_SHADOWING") val descriptor = plan?.descriptor ?: logicalDescriptor
                @Suppress("NAME_SHADOWING") val videoProfile = if (plan != null) plan.videoProfile else videoProfile
                @Suppress("NAME_SHADOWING") val logProfile = if (plan != null) plan.logProfile else logProfile
                @Suppress("NAME_SHADOWING") val stillSize = if (plan != null) plan.stillSize else stillSize
                this.lockedLogicalDescriptor = logicalDescriptor.takeIf { plan != null }
                val crop = cropRoute.takeIf { plan == null }
                this.lensLockCrop = crop
                if (crop != null) {
                    // The HAL zoom stays at the anchor for the whole session; setZoomRatio only moves the GPU crop.
                    requestedZoomRatio = crop.pinnedRatio
                    lastAcceptedZoomRatio = crop.pinnedRatio
                }
                this.lensLockWithdraw = if (plan == null) null else { reason ->
                    if (!disposed.get() && currentGeneration == generation && lockPlan === plan) {
                        lensLockWithdraw = null
                        lockPlan = null
                        val fallback = lensLockCropRoute(requestedLock!!, logicalDescriptor)
                        if (fallback != null) {
                            Log.w(TAG, "Lens lock on ${plan.descriptor.cameraId} failed ($reason); camera ${logicalDescriptor.cameraId} serves it by the GPU crop")
                            // Remembered, so the next start of this lock does not retry the failing open.
                            lensLockCropFallbacks += requestedLock.physicalId
                            cropRoute = fallback
                        } else {
                            Log.w(TAG, "Lens lock on ${plan.descriptor.cameraId} withdrawn ($reason); opening camera ${logicalDescriptor.cameraId}")
                            listener.onLensLockUnavailable(plan.descriptor.cameraId, reason)
                        }
                        closeResources()
                        this.listener = listener
                        pendingPreviewStart = start
                        runPendingPreviewStart()
                    }
                }
                if (stillFormat == StillPhotoFormat.HEIC && descriptor.heicSize == null) {
                    listener.onFailure("still-format-unavailable", "This camera does not advertise a native HEIC output.", true)
                    return@Runnable
                }
                this.activeStillFormat = stillFormat
                this.activeStillSize = stillSize
                this.physicalStillFallbackTaken = false
                this.previewSurface = surface
                this.activeDescriptor = descriptor
                this.displayRotationDegrees = displayRotationDegrees
                this.logPreviewEnabled = openCineLog
                this.logViewAssistEnabled = viewAssist
                // Night photo hands the viewfinder surface straight to the extension session, so it
                // never shares a graph with the GPU monitor, video or LOG paths.
                val nightRequested = nightExtensionEngaged(nightExtension, Build.VERSION.SDK_INT, descriptor.nightExtension) &&
                    !openCineLog && videoProfile == null && logProfile == null && stillFormat == StillPhotoFormat.JPEG
                this.gpuPhotoPreviewEnabled = gpuPhotoPreview && gpuPreview && !openCineLog && !nightRequested
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
                val highSpeedProfile = activeLogProfile?.takeIf { it.constrainedHighSpeed }?.let {
                    Camera2VideoProfile(it.size, it.fps, true)
                } ?: activeVideoProfile?.takeIf { it.constrainedHighSpeed }
                val cameraIdToOpen = if (highSpeedProfile != null) {
                    if (deviceQuirks.resolve(descriptor.quirkCameraId).applies(QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA)) {
                        preferredHighSpeedPhysicalId(descriptor, highSpeedProfile)
                            ?: descriptor.cameraId
                    } else descriptor.cameraId
                } else descriptor.cameraId
                // An early open of another camera is closed first; this start runs again once it has.
                if (warmOpen != null && warmOpen?.cameraId != cameraIdToOpen) {
                    retireWarm("the preview needs camera $cameraIdToOpen")
                    pendingPreviewStart = start
                    runPendingPreviewStart()
                    return@Runnable
                }
                this.previousSensorTimestampNs = null
                this.effectiveFps = null
                this.fpsLockRequested = fpsLock
                this.dynamicFpsOn = DynamicFps.enabled(
                    lowLightAutoFps && descriptor.lowLightAutoFps,
                    // The service offers it for regular VIDEO only; LOG/HLG and constrained HFR are refused here too.
                    DynamicFps.allowed(videoMode = true, logGraph = openCineLog,
                        constrainedHighSpeed = activeVideoProfile?.constrainedHighSpeed == true),
                )
                this.gpuPreviewEnabled = gpuPreview && !openCineLog && !nightRequested
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
                            greyReference = logGreyReference,
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
                    val callback = object : CameraDevice.StateCallback() {
                        override fun onOpened(device: CameraDevice) {
                            cameraOpening = false
                            if (disposed.get() || currentGeneration != generation) {
                                closeCameraDevice(device)
                                return
                            }
                            camera = device
                            if (nightRequested && startNightExtension(device, descriptor, surface, currentGeneration)) return
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
                                if (withdrawLensLock(LensLockUnavailable.OPEN_FAILED)) return
                                listener.onFailure("camera-open-$error", "The selected camera could not be opened.", true)
                            }
                        }
                    }
                    val warmDevice = warmOpen?.device
                    if (warmDevice != null) {
                        // The camera opened early for this start; from here it behaves as if this call opened it.
                        requireNotNull(warmOpen).delegate = callback
                        warmOpen = null
                        Log.i(TAG, "Preview adopted camera $cameraIdToOpen, opened at startup")
                        callback.onOpened(warmDevice)
                    } else {
                        cameraOpening = true
                        manager.openCamera(cameraIdToOpen, cameraExecutor, callback)
                    }
                } catch (failure: Exception) {
                    cameraOpening = false
                    if (withdrawLensLock(LensLockUnavailable.OPEN_FAILED)) return@Runnable
                    listener.onFailure("camera-open-exception", failure.message ?: "Camera open failed.", true)
                    finishCloseIfIdle()
                }
            }
            pendingPreviewStart = start
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
        val action = pendingPreviewStart
        if (action == null) {
            warmOpen?.takeIf { !it.started }?.let(::openWarm)
            return
        }
        pendingPreviewStart = null
        action.run()
    }

    /**
     * Starts opening [cameraId] before there is a preview surface, so a cold start overlaps the open
     * with the UI's first frames. The next [startPreview] for that camera adopts the device; any
     * other start, [stopPreview] or [close] closes it, and so does nobody claiming it in time.
     */
    fun preopen(cameraId: String) {
        if (disposed.get() || appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraExecutor.execute {
            if (disposed.get() || camera != null || cameraOpening || pendingPreviewStart != null || warmOpen != null) return@execute
            acquireOwnerIfNeeded()
            warmOpen = WarmOpen(cameraId)
            runPendingPreviewStart()
        }
    }

    /** Closes a [preopen] camera no preview has adopted yet; an adopted one is left alone. */
    fun dropPreopen(reason: String) {
        cameraExecutor.execute { retireWarm(reason) }
    }

    /** A camera opened ahead of its preview. cameraExecutor-confined, like every device callback. */
    private inner class WarmOpen(val cameraId: String) : CameraDevice.StateCallback() {
        var started = false
        var device: CameraDevice? = null
        var retired = false
        /** The adopting start's callback, which receives every later event of the device. */
        var delegate: CameraDevice.StateCallback? = null

        override fun onOpened(device: CameraDevice) {
            delegate?.let { return it.onOpened(device) }
            cameraOpening = false
            if (retired || disposed.get()) {
                closeCameraDevice(device)
                finishCloseIfIdle()
                return
            }
            this.device = device
            imageHandler.postDelayed({
                cameraExecutor.execute { if (warmOpen === this && delegate == null) retireWarm("no preview claimed it") }
            }, WARM_OPEN_UNCLAIMED_MS)
            runPendingPreviewStart()
        }

        override fun onClosed(device: CameraDevice) {
            delegate?.let { return it.onClosed(device) }
            if (closingCamera === device) closingCamera = null
            runPendingPreviewStart()
            finishCloseIfIdle()
        }

        override fun onDisconnected(device: CameraDevice) {
            delegate?.let { return it.onDisconnected(device) }
            failed(device)
        }

        override fun onError(device: CameraDevice, error: Int) {
            delegate?.let { return it.onError(device, error) }
            failed(device)
        }

        /** A failed early open is dropped; a waiting start then opens the camera itself and reports any error. */
        private fun failed(device: CameraDevice) {
            cameraOpening = false
            if (warmOpen === this) warmOpen = null
            this.device = null
            closeCameraDevice(device)
        }
    }

    // cameraExecutor-confined: the early open waiting for adoption, if any.
    private var warmOpen: WarmOpen? = null

    @SuppressLint("MissingPermission")
    private fun openWarm(warm: WarmOpen) {
        warm.started = true
        try {
            cameraOpening = true
            manager.openCamera(warm.cameraId, cameraExecutor, warm)
        } catch (_: Exception) {
            cameraOpening = false
            if (warmOpen === warm) warmOpen = null
            finishCloseIfIdle()
        }
    }

    /** Drops the early open: closes its device, or lets it close itself if it is still opening. */
    private fun retireWarm(reason: String) {
        val warm = warmOpen ?: return
        warmOpen = null
        Log.i(TAG, "Closing camera ${warm.cameraId}, opened at startup: $reason")
        warm.retired = true
        warm.device?.let { device ->
            warm.device = null
            closeCameraDevice(device)
        }
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
    internal fun synchronizeSubjectOutput() {
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

    /** True while the live graph is the night extension session rather than a regular capture session. */
    fun nightExtensionActive(): Boolean = nightSession != null

    /** OFF disables photographic pulses, not the independently requested continuous torch. */
    fun captureJpeg(flash: PhotoFlashSelection = PhotoFlashSelection()): Boolean =
        captureStillInternal(StillPhotoFormat.JPEG, flash, 95, LegacyPhotoDelivery.JPEG)

    fun captureStill(format: StillPhotoFormat, flash: PhotoFlashSelection = PhotoFlashSelection(), quality: Int = 95,
        aspect: PhotoAspectSelection = PhotoAspectSelection(), zeroShutterLag: Boolean = false): Boolean =
        captureStillInternal(format, flash, quality, LegacyPhotoDelivery.NONE, aspect = aspect, zeroShutterLag = zeroShutterLag)

    /** Cancellation latches synchronously; queued callbacks cannot publish the cancelled image. */
    fun cancelJpeg(): Boolean = cancelStill()

    fun cancelStill(): Boolean {
        nightSession?.let { return it.cancel() }
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

    /** Settings keep their latest intent/builder while a PHOTO owns the physical repeat. */
    internal fun CameraCaptureSession.setPhotoAwareRepeatingRequest(
        request: CaptureRequest, executor: Executor, callback: CameraCaptureSession.CaptureCallback,
    ) {
        // Constrained high-speed sessions reject setRepeatingRequest; they only accept bursts
        // built by createHighSpeedRequestList (zoom/lock reissues land here too).
        if (this is CameraConstrainedHighSpeedCaptureSession) {
            setRepeatingBurstRequests(createHighSpeedRequestList(request), executor, callback)
            return
        }
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
                    applyEngineRequest(EngineRequestMode.LONG_EXPOSURE_STILL, EngineRequestParameters(
                        jpegOrientationDegrees = jpegOrientation(descriptor), sensitivityIso = iso, exposureTimeNs = exposure))
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
            }.onFailure { listener?.onControlRejected("manual-control-failed", it.message ?: "Manual control update failed.") }
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
        perCameraFocusMarks.compute(descriptor.logicalCameraId) { _, marks -> (marks ?: emptyMap()) + (label to clamped) }
        return true
    }

    /** Removes a single focus mark. Returns false when the mark does not exist. */
    fun clearFocusMark(label: String): Boolean {
        val cameraId = activeDescriptor?.logicalCameraId ?: return false
        var removed = false
        perCameraFocusMarks.computeIfPresent(cameraId) { _, marks ->
            removed = label in marks
            marks - label
        }
        return removed
    }

    /** Returns the focus marks saved for the active camera, or an empty map when none exist. */
    fun getFocusMarks(): Map<String, Float> {
        val cameraId = activeDescriptor?.logicalCameraId ?: return emptyMap()
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
                listener?.onControlRejected("tap-focus-failed", failure.message ?: "Point autofocus failed.")
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

    fun setWhiteBalance(selection: WhiteBalanceSelection) {
        cameraExecutor.execute {
            requestedWhiteBalance = selection
            val builder = repeatingBuilder ?: return@execute
            val configured = session ?: return@execute
            applyManualControls(builder)
            runCatching { configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false)) }
                .onFailure { listener?.onControlRejected("white-balance-failed", it.message ?: "White balance update failed.") }
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
                .onFailure { listener?.onControlRejected("exposure-compensation-failed", it.message ?: "Exposure compensation update failed.") }
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
                    listener?.onControlRejected("ae-lock-failed", it.message ?: "AE lock update failed.")
                }
        }
    }

    /**
     * Turns FPS LOCK on or off for the live graph without restarting the session. The service only
     * asks for it in VIDEO, LOG and HLG. Turning it off while the shutter is held puts the
     * request back to plain automatic exposure; a take in progress keeps recording.
     */
    fun setFpsLock(enabled: Boolean) {
        cameraExecutor.execute {
            if (fpsLockRequested == enabled) return@execute
            fpsLockRequested = enabled
            logPipeline?.setExposureCompensation(enabled)
            cadenceGuard.reset()
            cadenceFaulted = false
            if (!enabled && cadenceEngaged) setCadenceEngaged(false, null, null)
        }
    }

    /**
     * Turns the lens-switch smoothing ([ZoomLagCompensator]) on or off for the live graph. Only the
     * viewfinder crop changes, so it needs no session restart and applies mid-take.
     */
    fun setSmoothLensSwitch(enabled: Boolean) {
        smoothLensSwitch = enabled
    }

    internal class QcomPriorityKeys(val select: CaptureRequest.Key<Int>, val use: CaptureRequest.Key<Long>)
    internal val qcomPriorityKeyCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<QcomPriorityKeys>>()

    /**
     * Toggles AF lock. With [AfLockBehavior.FREEZE_CURRENT] the engine takes the last effective
     * focus distance and switches AF to OFF so the lens holds position. With
     * [AfLockBehavior.FOCUS_AND_LOCK] it performs a one-shot AF scan at the last tap point (or
     * center) and freezes the distance once focus is confirmed. Engaging it during a tap-to-focus
     * always scans at the tapped point. Constrained high-speed sessions and manual focus are
     * incompatible and leave the lock inactive.
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
                listener?.onControlRejected("af-lock-unsupported", "AF lock is not supported in this session.")
                return@execute
            }
            if (requestedFocusDiopters != null) {
                listener?.onControlRejected("af-lock-conflict", "AF lock is incompatible with manual focus.")
                return@execute
            }
            if (activeTapFocusToken != null) {
                // Tap, then lock: lock where the operator tapped rather than refusing the lock.
                clearTapFocusLocked(notify = true)
                startAfLockScan(descriptor)
                return@execute
            }
            when (behavior) {
                AfLockBehavior.FREEZE_CURRENT -> {
                    val frozen = lastReportedFocusDiopters
                        ?: descriptor.minimumFocusDistance?.takeIf { it > 0f }?.let { 0f }
                    if (frozen == null) {
                        listener?.onControlRejected("af-lock-no-distance", "No focus distance to freeze yet.")
                        return@execute
                    }
                    afLockFrozenDiopters = frozen
                    afLockState = LockState.LOCKED
                    afLockScanToken = null
                    afLockResultReported = false
                    listener?.onAfLockChanged(LockState.LOCKED)
                    // The refused request left the lens on continuous AF; do not report a lock.
                    if (!reapplyRepeating()) disableAfLock(notify = true)
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
        // The GPU-crop route keeps the HAL zoom pinned at the anchor; the ratio moves the crop only.
        lensLockCrop?.let { crop ->
            val factor = LensLockCrop.cropFactor(ratio, crop.pinnedRatio, crop.uiRange)
            crop.factor = factor
            cameraExecutor.execute { if (lensLockCrop === crop) logPipeline?.setCropZoom(factor) }
            return true
        }
        // A lens-locked session takes the ratio on the logical scale; the physical camera gets its own.
        val target = descriptor.lensLock?.let { LensLockZoom.toPhysical(ratio, it.anchorRatio, range) } ?: ratio
        cameraExecutor.execute {
            val previousRatio = requestedZoomRatio
            requestedZoomRatio = ZoomMath.coerce(target, range)
            if (ZoomSettingsOverride.shouldArm(zoomOverrideEligible(descriptor), previousRatio, requestedZoomRatio)) {
                zoomOverrideArmed = true
                lastZoomChangeAtMs = SystemClock.elapsedRealtime()
            }
            val builder = repeatingBuilder ?: return@execute
            val configured = session ?: return@execute
            reissueRepeating(builder, configured, descriptor)
        }
        return true
    }

    /** Current effective (last-accepted) zoom ratio, or 1f when no session is active; on the logical scale while lens-locked. */
    fun currentEffectiveZoomRatio(): Float = effectiveZoomRatio

    internal fun reissueRepeating(
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
                listener?.onZoomRejected(uiZoom(requestedZoomRatio), uiZoom(lastAcceptedZoomRatio))
            } else {
                listener?.onControlRejected("zoom-request-failed", failure.message ?: "Zoom update failed.")
            }
        }
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
        // Playback rate written to the file when it differs from the capture rate: a high-speed
        // capture played at the project rate is slow motion.
        outputFrameRate: Int? = null,
        // SDR codec choice for the GPU and the direct high-speed routes; LOG and HLG are unaffected.
        videoCodec: VideoCodecPreference = VideoCodecPreference.H264,
    ): Boolean {
        if (disposed.get() || gpuPhotoPreviewEnabled || bracketRequest.get() != null || accumulationRequest.get() != null || burstRequest.get() != null) return false
        require(outputFrameRate == null || (captureRate != null && outputFrameRate > 0))
        recorderCodecFallback = null
        require(separateAudioClock == null || audio == null && timelapse == null && projectRateOverride == null && recordingGeometry != null && captureRate == null)

        require(projectRateOverride == null || (timelapse == null && captureRate == null && recordingGeometry != null))
        if (timelapse != null) {
            require(captureRate == null && recordingGeometry != null)
            return startGpuVideo(output, audio, recordingGeometry, videoBitrate, timelapse, recordingLut = recordingLut, videoCodec = videoCodec)
        }
        if (recordingGeometry != null && captureRate == null) {
            return startGpuVideo(output, audio, recordingGeometry, videoBitrate, projectRateOverride = projectRateOverride, separateAudioClock = separateAudioClock, recordingLut = recordingLut, videoCodec = videoCodec)
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
        val descriptor = activeDescriptor ?: return false
        if (recording) return false
        val overGpu = gpuPreviewEnabled
        // Only a constrained high-speed take may bypass an active GPU viewfinder: its encoder needs
        // every frame, while the GPU camera texture keeps the preview share for the displays.
        if (overGpu && activeVideoProfile?.constrainedHighSpeed != true) return false
        val surface = (if (overGpu) logPipeline?.cameraInputSurface else previewSurface) ?: return false
        val request = RecorderRequest(cameraGeneration, device)
        synchronized(stillAdmissionLock) {
            if (bracketRequest.get() != null || accumulationRequest.get() != null || burstRequest.get() != null || !recorderRequest.compareAndSet(null, request)) return false
        }
        recorderOverGpuPreview = overGpu
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
                // The encoder sees every captured frame, so HEVC must reach the capture rate, not the playback rate.
                val encoderFps = (captureRate ?: requestedTargetFps.toDouble()).toInt()
                val chosenCodec = resolveVideoCodec(videoCodec, videoCodec != VideoCodecPreference.H264 && hevcSdrSupported(size.width, size.height, encoderFps))
                recorderCodecFallback = null
                @Suppress("DEPRECATION")
                fun configureRecorder(codec: VideoCodec): MediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
                    setVideoEncoder(if (codec == VideoCodec.HEVC) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
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
                    setVideoFrameRate(outputFrameRate ?: requestedTargetFps)
                    captureRate?.let { setCaptureRate(it) }
                    setVideoEncodingBitRate(RecordingDataRate.recorderVideoBps(videoBitrate, requestedTargetFps))
                    // The encoder receives raw sensor frames here (no GPU pass bakes rotation into
                    // pixels), so the container carries the whole content rotation.
                    setOrientationHint(recordingGeometry?.containerRotationDegrees ?: jpegOrientation(descriptor))
                    setOutputFile(output.fileDescriptor)
                    prepare()
                }
                val configuredRecorder = try {
                    configureRecorder(chosenCodec)
                } catch (failure: Exception) {
                    if (chosenCodec != VideoCodec.HEVC) throw failure
                    // A hardware HEVC encoder may still refuse the exact configuration: retry once with H.264.
                    runCatching { recorder?.reset() }
                    runCatching { recorder?.release() }.exceptionOrNull()?.let { throw failure.also { primary -> primary.addSuppressed(it) } }
                    recorder = null
                    recorderCodecFallback = "HEVC recorder prepare failed (${failure.message ?: failure.javaClass.simpleName}); fell back to H.264."
                    Log.w(TAG, requireNotNull(recorderCodecFallback))
                    configureRecorder(VideoCodec.H264).also { listener?.onVideoCodecFallback(requireNotNull(recorderCodecFallback)) }
                }
                recorderVideoCodec = if (recorderCodecFallback == null) chosenCodec else VideoCodec.H264
                recorder = configuredRecorder
                recordSurface = configuredRecorder.surface
                recorderLevelSink = audio?.onAudioLevel
                configureRecordingSession(device, descriptor, surface, requireNotNull(recordSurface), startRecorder = true)
                audio?.inputGuard?.let { guard ->
                    activeRecorderGuard = guard
                    // The route is read again by the guard once the recorder actually starts.
                    guard.attach(configuredRecorder, com.librestatic.opencinecam.media.audio.AudioCapturePath.MEDIA_RECORDER)
                }
            } catch (failure: Exception) {
                val reportFailure = ownsRecorderRequest(request)
                releaseRecorder(request)
                if (reportFailure) listener?.onFailure("video-prepare-failed", failure.message ?: "Video recording could not be prepared.", true)
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
        /** Record the camera's HLG signal as BT.2020 HLG (HDR) instead of OCLog2. */
        hlgOutput: Boolean = false,
        /** With [hlgOutput]: record HDR10 (PQ) instead of HLG; only honoured for a profile that offers it. */
        hdr10Output: Boolean = false,
    ): Boolean {
        val pipeline = logPipeline ?: return false
        val descriptor = activeDescriptor ?: return false
        if (!logPreviewEnabled || recording) return false
        val cameraGeneration = generation
        lastLogRecordingEvidence = null
        pipeline.onFirstRecordedFrame = { timestampNs ->
            if (generation == cameraGeneration && logPipeline === pipeline) listener?.onRecordingFirstFrame(timestampNs)
        }
        val accepted = pipeline.startRecording(
            output = output,
            bitrate = videoBitrate,
            geometry = recordingGeometry,
            audio = audio,
            separateAudioClock = separateAudioClock,
            recordingLut = recordingLut,
            hlgOutput = hlgOutput,
            hdr10Output = hdr10Output && hlgOutput && activeLogProfile?.hdr10 == true,
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

    @Volatile internal var latestEncodedProgress: EncodedRecordingProgress? = null
    fun encodedRecordingProgress(): EncodedRecordingProgress? = latestEncodedProgress

    @Volatile internal var lastCaptureEpochReport: CaptureEpochReport? = null
    fun consumeCaptureEpochReport(): CaptureEpochReport? = lastCaptureEpochReport.also { lastCaptureEpochReport = null }

    fun consumeLastRecordingLutEvidence(): BakedLutEvidence? =
        lastRecordingLutEvidence.also { lastRecordingLutEvidence = null }

    fun consumeLastOpenCineLogEvidence(): OpenCineLogRecordingEvidence? =
        lastLogRecordingEvidence.also { lastLogRecordingEvidence = null }

    fun setOpenCineLogViewAssist(enabled: Boolean) {
        logViewAssistEnabled = enabled
        logPipeline?.setViewAssist(enabled)
    }

    /** Middle-grey reference for OCLog2; an active take keeps the gain it started with. */
    fun setOpenCineLogGreyReference(reference: OpenCineLogGreyReference) {
        logGreyReference = reference
        logPipeline?.setGreyReference(reference)
    }

    fun setOpenCineLogSqueezeFactor(factor: Float) {
        logPipeline?.setPreviewSqueezeFactor(factor)
    }

    @Volatile internal var lastTimelapsePauseStatus: TimelapsePauseStatus? = null

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
        if (!recorderOverGpuPreview) logPipeline?.let { return it.stopRecording() }
        val request = recorderRequest.get() ?: return false
        if (!request.stopRequested.compareAndSet(false, true)) return false
        cameraExecutor.execute {
            if (!ownsRecorderRequest(request)) return@execute
            // Stop feeding the recorder first and let the frames already in the pipeline reach it.
            // MediaRecorder stops only once the session is idle, so the encoder gets every frame up
            // to the stop and the camera never queues into a released surface.
            val draining = session
            session = null
            if (draining == null || runCatching { draining.stopRepeating() }.isFailure) {
                finishRecorderStop(request, draining)
                return@execute
            }
            var finished = false
            lateinit var timeout: Runnable
            val finish = {
                if (!finished) {
                    finished = true
                    imageHandler.removeCallbacks(timeout)
                    if (drainingRecorderSession === draining) {
                        drainingRecorderSession = null
                        recorderDrainContinuation = null
                    }
                    finishRecorderStop(request, draining)
                }
            }
            timeout = Runnable {
                cameraExecutor.execute {
                    if (!finished) Log.w(TAG, "Recorder session did not drain within $RECORDER_STOP_DRAIN_TIMEOUT_MS ms; stopping anyway")
                    finish()
                }
            }
            drainingRecorderSession = draining
            recorderDrainContinuation = finish
            imageHandler.postDelayed(timeout, RECORDER_STOP_DRAIN_TIMEOUT_MS)
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
            Log.i(TAG, "Reattaching preview during a recorder take")
            this.previewSurface = surface
            this.displayRotationDegrees = displayRotationDegrees
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            session = null
            configureRecordingSession(device, descriptor, surface, recordingSurface, startRecorder = false)
        }
        return true
    }

    @Volatile internal var stoppedOutputRetirement = CompletableFuture.completedFuture(Unit)

    /** Exact file receipt associated with the latest stop callback, not the Boolean content result. */
    fun stoppedRecordingOutputRetirement(): CompletableFuture<Unit> = stoppedOutputRetirement.thenApply { it }

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

    /** The ISP histogram gate for one camera: its enable key and bucket count, or null when it lacks either. */
    internal class IspHistogramSupport(val cameraId: String, val buckets: Int, val enableKey: CaptureRequest.Key<ByteArray>)

    @Volatile internal var ispSupport: Pair<String, IspHistogramSupport?>? = null
    @Volatile internal var ispStatsKey: android.hardware.camera2.CaptureResult.Key<*>? = null
    @Volatile internal var ispStatsTypeKey: android.hardware.camera2.CaptureResult.Key<*>? = null
    internal val ispHistogram = IspHistogramHold()
    @Volatile internal var ispHistogramLogged = false

    /** Latest DIAGNOSTIC capture-result statistics of the current graph, or null before its first result. */
    fun captureDiagnostics(): CaptureAuditStats? = latestCaptureDiagnostics

    /** Latest single-still press-to-start latency and whether ZSL was requested, for on/off device comparison. */
    fun stillShutterLatency(): StillShutterLatency? = latestStillShutterLatency

    internal class DynamicFpsKeys(
        val enable: CaptureRequest.Key<IntArray>? = null,
        val legacyEnable: CaptureRequest.Key<IntArray>? = null,
        val viull: CaptureRequest.Key<IntArray>? = null,
        val config: CaptureRequest.Key<FloatArray>? = null,
        val offered: Boolean = false,
    )

    internal val dynamicFpsKeyCache = java.util.concurrent.ConcurrentHashMap<String, DynamicFpsKeys>()

    /**
     * Wraps a session output, or hands [onFailure] the reason. The UI can abandon the preview
     * surface (a density or rotation change) and a retiring GPU pipeline its input surface while a
     * session is being built; the constructor then throws on the camera executor, where an uncaught
     * exception kills the process.
     */
    internal inline fun sessionOutput(surface: Surface, onFailure: (Exception) -> Unit): OutputConfiguration? =
        try { OutputConfiguration(surface) } catch (failure: Exception) { onFailure(failure); null }

    /** True when the active repeating session is constrained high-speed and rejects control locks. */
    internal fun isHighSpeedSession(): Boolean =
        session is CameraConstrainedHighSpeedCaptureSession ||
            activeVideoProfile?.constrainedHighSpeed == true ||
            activeLogProfile?.constrainedHighSpeed == true

    /** Returns false when the session refused the request and kept running on its previous one. */
    internal fun reapplyRepeating(): Boolean {
        val builder = repeatingBuilder ?: return true
        val configured = session ?: return true
        applyManualControls(builder)
        return runCatching {
            configured.setPhotoAwareRepeatingRequest(builder.build(), cameraExecutor, previewCaptureCallback(activeDescriptor, false))
        }.onFailure {
            listener?.onControlRejected("lock-reapply-failed", it.message ?: "Lock update could not be applied.")
        }.isSuccess
    }

    /**
     * OCC-PLAN-068 U7: requests HAL face statistics for the out-of-frame warning. Applied only where
     * the active camera advertises SIMPLE/FULL and the graph is not constrained high-speed; a graph
     * that ignores or fails with the key falls back without it. Off (the default) leaves every
     * request exactly as before.
     */
    fun setSubjectFramingEnabled(enabled: Boolean) {
        cameraExecutor.execute {
            if (subjectFramingRequested == enabled) return@execute
            subjectFramingRequested = enabled
            reissueFaceDetect()
        }
    }

    /**
     * Applies the composer's base block for [mode] where the engine used to hand-set the same
     * keys; the stateful appliers that follow on the builder are unchanged.
     */
    internal fun CaptureRequest.Builder.applyEngineRequest(
        mode: EngineRequestMode,
        parameters: EngineRequestParameters = EngineRequestParameters(),
    ) {
        for ((key, value) in requestComposer.composeEngineRequest(mode, parameters)) when (key) {
            "JPEG_QUALITY" -> set(CaptureRequest.JPEG_QUALITY, (value as RequestValue.IntValue).value.toByte())
            "JPEG_ORIENTATION" -> set(CaptureRequest.JPEG_ORIENTATION, (value as RequestValue.IntValue).value)
            "SENSOR_SENSITIVITY" -> set(CaptureRequest.SENSOR_SENSITIVITY, (value as RequestValue.IntValue).value)
            "SENSOR_EXPOSURE_TIME" -> set(CaptureRequest.SENSOR_EXPOSURE_TIME, (value as RequestValue.LongValue).value)
            else -> set(engineEnumKey(key), camera2RequestEnum(key, (value as RequestValue.TextValue).value))
        }
    }

    internal class VendorMeteringKeys(val request: CaptureRequest.Key<Int>?, val result: android.hardware.camera2.CaptureResult.Key<Int>?)

    internal val vendorMeteringKeyCache = java.util.concurrent.ConcurrentHashMap<String, VendorMeteringKeys>()

    /** Codec of the SDR take in flight (GPU or direct MediaRecorder route); null when none or LOG/HLG. */
    fun activeVideoCodec(): VideoCodec? = logPipeline?.activeVideoCodec() ?: recorderVideoCodec

    /** True while a MediaRecorder owns the take (off-speed and high-speed); false for the MediaCodec graphs. */
    fun recordsThroughMediaRecorder(): Boolean = recorderRequest.get() != null

    /** Why the latest SDR take fell back from HEVC to H.264, or null. */
    fun videoCodecFallbackReason(): String? = logPipeline?.videoCodecFallbackReason() ?: recorderCodecFallback

    /** Public wrapper so the service can validate recording geometry against encoder caps. */
    fun encoderCanReachFps(size: Size): Boolean = encoderCanReachFps(size, requestedTargetFps)

    /** HEVC variant for pipelines that encode Main10 (LOG mode). */
    fun hevcEncoderCanReachFps(size: Size): Boolean =
        encoderCanReachFps(size, requestedTargetFps, MIME_HEVC)

    /**
     * cameraExecutor: applies a thermal status change to both scope sources (the direct YUV
     * reader through [analysisSuspended], the active GPU pipeline through its own flag) and
     * reports only actual state changes. Graphs and pipelines created later read the same flag.
     */
    private fun observeThermalStatus(platformStatus: Int) {
        if (disposed.get()) return
        thermalGovernor.observePlatformStatus(platformStatus) ?: return
        analysisSuspended = thermalGovernor.state == AnalysisSuspension.THERMAL
        logPipeline?.setAnalysisSuspended(analysisSuspended)
        Log.w(TAG, "Scope analysis ${if (analysisSuspended) "suspended" else "resumed"} (thermal status $platformStatus).")
        listener?.let(::notifyAnalysisSuspension)
    }

    /** cameraExecutor: emits the governor state to [target] only if it differs from what [target] last saw. */
    private fun notifyAnalysisSuspension(target: Camera2PreviewListener) {
        val state = thermalGovernor.state
        val seen = suspensionNotified?.takeIf { it.first === target }?.second ?: AnalysisSuspension.NONE
        if (seen == state) return
        suspensionNotified = target to state
        target.onAnalysisSuspended(state)
    }

    internal fun closeAnalysisReader() {
        val lease = analysisReaderLease
        analysisReaderLease = null
        analysisReader = null
        lease?.close()
    }

    internal fun retirePipeline(pipeline: OpenCineLogGpuPipeline, after: () -> Unit = {}) {
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
    internal fun observeFailedInitialization(failure: Throwable) {
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

    internal fun failNativeRetirement(failure: Throwable) {
        if (retirementFailure == null) retirementFailure = failure
        nativeRetirement.completeExceptionally(requireNotNull(retirementFailure))
    }

    private fun closeCameraDevice(device: CameraDevice) {
        closingCamera = device
        runCatching { device.close() }.onFailure(::failNativeRetirement)
    }

    internal data class CompressedStillPlan(val size: Size, val physicalId: String?)

    /** Closes the readers the preview graph owns so a rebuilt graph can create fresh ones (OCC-PLAN-069). */
    internal fun retireGraphReaders() {
        runCatching { jpegReader?.close() }
        jpegReader = null
        runCatching { rawReader?.close() }
        rawReader = null
        closeAnalysisReader()
    }

    private fun closeResources(keepWarm: Boolean = false) {
        if (!keepWarm) retireWarm("the camera was released")
        pendingPreviewStartedReceipt = null
        cadenceGuard.reset()
        cadenceIsoLoop.reset()
        cadenceExposureRamp.stop()
        cadenceAeLumas.clear()
        analysisBurst = false
        logPipeline?.setAnalysisBurst(false)
        cadenceHeldIso = null
        cadenceLensId = null
        cadenceMeasuredLensId = null
        cadenceLoopLensId = null
        cadenceRatioFromLensId = null
        cadenceEngaged = false
        cadenceFaulted = false
        timelapseTake = false
        nightSession?.close()
        nightSession = null
        retireGraphAudit()
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
        lockedLogicalDescriptor = null
        lensLockWithdraw = null
        lensLockCrop = null
        lensSwitchDebounce.reset()
        requestedAeCompensationIndex = null
        requestedWhiteBalance = WhiteBalanceSelection.Auto
        requestedZoomRatio = 1f
        lastAcceptedZoomRatio = 1f
        zoomOverrideArmed = false
        effectiveZoomRatio = 1f
        aeLockActive = false
        afLockState = LockState.OFF
        afLockFrozenDiopters = null
        afLockScanToken = null
        afLockResultReported = false
        lastTapFocusForAfLock = null
        lastReportedFocusDiopters = null
    }

    internal class BoundedDngOutput : ByteArrayOutputStream() {
        override fun write(value: Int) {
            check(count < StillImagePayload.MAX_DNG_BYTES) { "DNG exceeds its byte limit" }
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length >= 0 && length <= StillImagePayload.MAX_DNG_BYTES - count) { "DNG exceeds its byte limit" }
            super.write(bytes, offset, length)
        }
    }

    /** Image thread only: the luma raster [analyzeImage] reuses between frames. */
    private var peakingLuma = ByteArray(0)

    internal fun analyzeImage(image: Image): Camera2Analysis {
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
        val counts = IntArray(16 * 9)
        for (y in 0 until image.height step step) {
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
            }
        }
        val total = lumaHistogram.sum().coerceAtLeast(1).toFloat()
        return Camera2Analysis(
            histogram = lumaHistogram.map { it / total },
            redHistogram = redHistogram.map { it / total },
            greenHistogram = greenHistogram.map { it / total },
            blueHistogram = blueHistogram.map { it / total },
            zebraCells = counts.indices.map { counts[it] > 0 && zebraHits[it].toFloat() / counts[it] >= .20f },
            capturedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            scopes = analyzeMonitoringRgb(sampledWidth, sampledHeight, rgbSamples, options, MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR),
            focusPeaking = if (focusPeakingEnabled) focusPeakingMask(image, options.peakingThreshold) else null,
            sensorTimestampNs = image.timestamp,
        )
    }

    /** Edges of the Y plane at full analysis resolution, or the finest step the mask budget allows. */
    private fun focusPeakingMask(image: Image, threshold: Int): FocusPeakingMask {
        val plane = image.planes[0]
        val step = focusPeakingStep(image.width, image.height)
        val width = (image.width + step - 1) / step
        val height = (image.height + step - 1) / step
        if (peakingLuma.size < width * height) peakingLuma = ByteArray(width * height)
        copyLumaPlane(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height, step, peakingLuma)
        return detectFocusEdges(peakingLuma, width, height, threshold, MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR)
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
        runCatching { powerManager?.removeThermalStatusListener(thermalStatusListener) }
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
            lensLockCropFallbacks.clear()
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

    companion object {
        /** An early open nobody adopts within this long is closed, so it never holds the camera. */
        const val WARM_OPEN_UNCLAIMED_MS = 30_000L
        const val FOCUS_PULL_TICK_MS = 33L
        /** The standard stream use cases the camera advertises, or empty without API 33 and the STREAM_USE_CASE capability. */
        internal fun advertisedStreamUseCases(characteristics: CameraCharacteristics, capabilities: IntArray): Set<Long> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || StreamUseCase.CAPABILITY !in capabilities) return emptySet()
            val advertised = runCatching { characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)?.toSet() }
                .getOrNull().orEmpty()
            return if (streamUseCasesApplicable(Build.VERSION.SDK_INT, capabilities.toSet(), advertised, highSpeed = false)) {
                advertised.filterTo(HashSet()) { it < StreamUseCase.VENDOR_BASE }
            } else emptySet()
        }

        const val DEFAULT_TARGET_FPS = 30
        internal const val RECORDER_LEVEL_INTERVAL_MS = 100L
        // A few frames at 24 fps; a high-speed burst drains far sooner.
        private const val RECORDER_STOP_DRAIN_TIMEOUT_MS = 500L
        internal const val MIME_AVC = "video/avc"
        internal const val MIME_HEVC = "video/hevc"
        internal const val MIN_SELECTABLE_FPS = 10
        internal const val MAX_SELECTABLE_FPS = 60
        internal const val METADATA_PERIOD_MS = 500L
        internal const val CAPTURE_AUDIT_SUMMARY_MS = 10_000L
        internal const val ZOOM_REPORT_PERIOD_MS = 33L
        private const val TAP_FOCUS_HOLD_MS = 3_000L
        internal const val SCOPE_HISTOGRAM_BINS = 64
        internal const val MAX_BURST_IMAGES = 10
        internal const val TAG = "Camera2PreviewEngine"
        internal const val CADENCE_TAG = "CadenceGuard"
        internal const val CADENCE_TRACE_TAG = "CadenceTrace"
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

internal fun preferredLogProfile(values: List<Camera2LogProfile>, requestedFps: Int): Camera2LogProfile? =
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
