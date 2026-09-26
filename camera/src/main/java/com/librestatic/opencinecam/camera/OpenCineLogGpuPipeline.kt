/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AcousticEchoCanceler
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque

data class OpenCineLogEncoderCandidate(
    val codecName: String,
    val profile: Int,
    val size: Size,
    val hardwareAccelerated: Boolean = true,
    val level: Int? = null,
)

/**
 * Camera source that feeds the OCLog2 transform. HLG is the qualified ten-bit path; the SDR
 * branch exists only to preserve HFR availability and must retain its ISP-derived disclosure.
 */
enum class OpenCineLogSourcePath(
    val displayName: String,
    val dynamicRange: String,
    val colorSpace: String,
    val transfer: String,
    val sourcePrecision: String,
    val highSpeedDerived: Boolean,
) {
    HLG10_BT2020(
        displayName = "HLG 10-bit",
        dynamicRange = "HLG10",
        colorSpace = "BT2020_HLG",
        transfer = "HLG",
        sourcePrecision = "10-bit dynamic-range camera profile",
        highSpeedDerived = false,
    ),
    SDR_BT709_ISP(
        displayName = "HFR ISP-derived",
        dynamicRange = "STANDARD",
        colorSpace = "BT709_ASSUMED",
        transfer = "BT.709 OETF assumed from standard video output",
        sourcePrecision = "standard-range ISP output; source gamut and bit depth are not claimed",
        highSpeedDerived = true,
    ),
}

data class OpenCineLogRecordingEvidence(
    val encodedProgress: EncodedRecordingProgress? = null,
    val avTiming: CaptureEpochReport? = null,
    val provenance: LogProvenance = LogProvenance.ISP_DERIVED,
    val sourcePath: OpenCineLogSourcePath,
    val sourceDynamicRange: String,
    val sourceColorSpace: String,
    val sourceTransfer: String,
    val sourcePrecision: String,
    val sourceSurface: String = "PRIVATE",
    val sourceDataSpace: Int?,
    val curve: String = "OCLog2",
    val curveVersion: String = "2.0",
    val gamut: String = "BT.2020",
    val range: String = "full",
    val codecName: String,
    val codecMime: String = MediaFormat.MIMETYPE_VIDEO_HEVC,
    val codecProfile: String = "Main10",
    val eglRenderTargetBits: Int = 10,
    val targetFps: Int,
    val transformSha256: String,
    val encodedFrames: Long,
    val firstPtsUs: Long?,
    val lastPtsUs: Long?,
    val maxVideoPtsGapUs: Long?,
    val videoPtsGapsOverThreshold: Long,
    val geometryMode: RecordingGeometryMode,
    val deviceOrientationDegrees: Int,
    val pixelRotationDegrees: Int,
    val rendererRotationDegrees: Int,
    val containerRotationDegrees: Int,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val pixelAspectRatioWidth: Int = 1,
    val pixelAspectRatioHeight: Int = 1,
    val recordingLut: BakedLutEvidence? = null,
)

internal data class OpenCineLogPreviewGeometry(
    val positionRotationDegrees: Int,
    val scaleX: Float,
    val scaleY: Float,
    val mirrorHorizontally: Boolean,
)

/** Restore codec-local output deltas to explicit capture anchors; unknown camera clocks stay disclosed. */
internal class MuxTimestampNormalizer(private val captureEpoch: CaptureEpochClock? = null) {
    var videoOriginUs: Long? = null
        private set
    var audioOriginUs: Long? = null
        private set
    private var lastVideoUs = 0L
    private var lastAudioUs = 0L
    fun normalize(video: Boolean, presentationTimeUs: Long): Long {
        check(captureEpoch?.ready() != false) { "Capture epoch is not ready" }
        val origin = if (video) videoOriginUs ?: presentationTimeUs.also { videoOriginUs = it }
            else audioOriginUs ?: presentationTimeUs.also { audioOriginUs = it }
        val offset = captureEpoch?.offsetUs(video) ?: 0L
        val rebased = Math.addExact(Math.subtractExact(presentationTimeUs, origin).coerceAtLeast(0L), offset)
        return if (video) rebased.coerceAtLeast(lastVideoUs).also { lastVideoUs = it }
            else rebased.coerceAtLeast(lastAudioUs).also { lastAudioUs = it }
    }
}

/** Converts a small recorded-signal RGBA readback into the same bounded scope model as YUV preview. */
internal fun analyzeRgbaFrame(
    width: Int,
    height: Int,
    rgba: ByteArray,
    capturedAtElapsedRealtimeMs: Long,
    options: MonitoringOptions = MonitoringOptions(),
    domain: MonitoringSignalDomain = MonitoringSignalDomain.SDR_BT709_CODE,
): Camera2Analysis {
    require(width > 0 && height > 0 && width.toLong() * height in 1..MonitoringScopeFrame.MAX_PIXELS.toLong() &&
        rgba.size.toLong() == width.toLong() * height * 4)
    val rgbSamples = ByteArray(width * height * 3)
    val bins = Camera2PreviewEngine.SCOPE_HISTOGRAM_BINS
    val lumaHistogram = IntArray(bins)
    val redHistogram = IntArray(bins)
    val greenHistogram = IntArray(bins)
    val blueHistogram = IntArray(bins)
    val zebraHits = IntArray(16 * 9)
    val focusHits = IntArray(16 * 9)
    val counts = IntArray(16 * 9)
    for (displayY in 0 until height) {
        val sourceY = height - 1 - displayY
        var previous = -1
        for (x in 0 until width) {
            val offset = (sourceY * width + x) * 4
            val red = rgba[offset].toInt() and 0xff
            val green = rgba[offset + 1].toInt() and 0xff
            val blue = rgba[offset + 2].toInt() and 0xff
            val sample = (displayY * width + x) * 3
            rgbSamples[sample] = red.toByte(); rgbSamples[sample + 1] = green.toByte(); rgbSamples[sample + 2] = blue.toByte()
            val luma = ((54 * red + 183 * green + 19 * blue + 128) shr 8).coerceIn(0, 255)
            lumaHistogram[luma * bins / 256]++
            redHistogram[red * bins / 256]++
            greenHistogram[green * bins / 256]++
            blueHistogram[blue * bins / 256]++
            val cell = (displayY * 9 / height).coerceIn(0, 8) * 16 + (x * 16 / width).coerceIn(0, 15)
            counts[cell]++
            if (luma * 100 >= options.zebraHighPercent * 255 || options.zebraShadowEnabled && luma * 100 <= options.zebraLowPercent * 255) zebraHits[cell]++
            if (previous >= 0 && kotlin.math.abs(luma - previous) >= options.peakingThreshold) focusHits[cell]++
            previous = luma
        }
    }
    val total = (width * height).toFloat().coerceAtLeast(1f)
    return Camera2Analysis(
        histogram = lumaHistogram.map { it / total },
        redHistogram = redHistogram.map { it / total },
        greenHistogram = greenHistogram.map { it / total },
        blueHistogram = blueHistogram.map { it / total },
        zebraCells = counts.indices.map { counts[it] > 0 && zebraHits[it].toFloat() / counts[it] >= .20f },
        focusCells = counts.indices.map { counts[it] > 0 && focusHits[it].toFloat() / counts[it] >= .12f },
        capturedAtElapsedRealtimeMs = capturedAtElapsedRealtimeMs,
        scopes = analyzeMonitoringRgb(width, height, rgbSamples, options, domain),
    )
}

/** Pure preview geometry shared by the GL renderer and local unit tests. */
internal object OpenCineLogPreviewGeometryCalculator {
    fun calculate(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
        squeezeFactor: Float = 1f,
    ): OpenCineLogPreviewGeometry {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(targetWidth > 0 && targetHeight > 0)
        val sensor = normalizeRightAngle(sensorOrientationDegrees)
        val display = normalizeRightAngle(displayRotationDegrees)
        // Camera2's SurfaceTexture matrix on the tested device already owns the sensor rotation,
        // but not the current display compensation. Therefore applying sensor - display to the
        // position quad double-applies the sensor angle. Rotate only by the display component.
        val positionRotation = if (frontFacing) {
            display
        } else {
            (360 - display) % 360
        }
        val currentSensorToDisplay = if (frontFacing) {
            (sensor + display) % 360
        } else {
            (sensor - display + 360) % 360
        }
        // The target aspect still follows the current sensor/display orientation even though the
        // SurfaceTexture matrix, rather than the position quad, performs the absolute rotation.
        val quarterTurn = currentSensorToDisplay == 90 || currentSensorToDisplay == 270
        val contentWidth = if (quarterTurn) sourceHeight else sourceWidth
        val contentHeight = if (quarterTurn) sourceWidth else sourceHeight
        // When a squeeze factor > 1 is active, the content is horizontally compressed.
        // De-squeeze by dividing the content aspect by the factor before aspect-fit.
        val effectiveContentAspect = contentWidth.toFloat() / contentHeight / squeezeFactor.coerceAtLeast(1f)
        val targetAspect = targetWidth.toFloat() / targetHeight
        val scaleX: Float
        val scaleY: Float
        if (effectiveContentAspect > targetAspect) {
            scaleX = 1f
            scaleY = targetAspect / effectiveContentAspect
        } else {
            scaleX = effectiveContentAspect / targetAspect
            scaleY = 1f
        }
        return OpenCineLogPreviewGeometry(
            positionRotationDegrees = positionRotation,
            scaleX = scaleX,
            scaleY = scaleY,
            // Front viewfinders follow the conventional mirrored-selfie contract. Recording
            // renders through the separate file branch below and remains unmirrored/readable.
            mirrorHorizontally = frontFacing,
        )
    }

    private fun normalizeRightAngle(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "Preview rotations must be multiples of 90 degrees." }
        return normalized
    }
}

/** Constructor failure retains ownership until its asynchronous native cleanup completes. */
internal class GpuPipelineInitializationFailure(
    cause: Throwable,
    private val completion: CompletableFuture<Unit>,
) : IllegalStateException(cause.message ?: "GPU pipeline initialization failed.", cause) {
    fun retirement(): CompletableFuture<Unit> = completion.thenApply { it }
}

/**
 * Live, public-API LOG pixel path.
 *
 * Camera2 produces an HLG10/BT.2020 PRIVATE stream into an external SurfaceTexture. GLES
 * explicitly decodes HLG to scene-linear BT.2020 and applies OCLog2. The OCLog image is sent
 * to a ten-bit EGL encoder surface. Monitoring is a separate branch: either the same flat
 * OCLog code values or a Rec.709 view-assist transform is drawn to the UI Surface.
 */
class OpenCineLogGpuPipeline(
    private val size: Size,
    private val sourcePath: OpenCineLogSourcePath,
    preview: Surface?,
    private val sensorOrientationDegrees: Int,
    displayRotationDegrees: Int,
    private val frontFacing: Boolean,
    viewAssist: Boolean,
    private val targetFps: Int,
    private val passthroughSdr: Boolean = false,
    private val appContext: Context? = null,
    private val cameraTimestampRealtime: Boolean = false,
    /** Codec selection seam; production retains its hardware gate, tests inject advertised software AVC. */
    private val avcEncoderSelector: (Size, Int, Boolean) -> OpenCineLogEncoderCandidate? = { size, fps, software -> findAvcEncoder(size, fps, software) },
    private val onAnalysis: ((Camera2Analysis) -> Unit)? = null,
    private val onPreviewLost: ((String) -> Unit)? = null,
    private val onOperatorLutStatus: ((OperatorLutStatus) -> Unit)? = null,
    private val onFailure: (String, String) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("OpenCineLogGL").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var previewContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config8: EGLConfig? = null
    private var config10: EGLConfig? = null
    private var previewNativeSurface: Surface? = preview
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var program = 0
    @Volatile private var operatorLut: MonitorLut? = null
    @Volatile private var subjectLut: MonitorLut? = null
    private var recordingLutTexture = 0
    private var recordingLutTextureHash: String? = null
    private var subjectLutTexture = 0
    private var subjectLutTextureHash: String? = null
    private var subjectLutFailure: OperatorLutStatus? = null
    private var operatorLutProgram = 0
    private var operatorLutTexture = 0
    private var operatorLutTextureHash: String? = null
    private var failedOperatorLutHash: String? = null
    private var lastOperatorLutStatus: OperatorLutStatus? = null
    private var pendingOperatorLutStatus: OperatorLutStatus? = null
    fun setOperatorLut(lut: MonitorLut?) {
        if (operatorLut == lut) return
        operatorLut = lut
        if (!closed.get()) handler.post {
            if (operatorLut === lut) { failedOperatorLutHash = null; lastOperatorLutStatus = null }
        }
    }
    fun setSubjectLut(lut: MonitorLut?) {
        if (subjectLut == lut) return
        subjectLut = lut
        if (!closed.get()) handler.post {
            if (subjectLut === lut) subjectLutFailure = null
        }
    }

    private fun reportOperatorLut(status: OperatorLutStatus) {
        if (lastOperatorLutStatus == status) return
        lastOperatorLutStatus = status
        runCatching { onOperatorLutStatus?.invoke(status) }
    }

    private var quad: PreviewQuad? = null
    private var textureMatrixLocation = -1
    private var outputModeLocation = -1
    private var positionScaleLocation = -1
    private var previewRotationLocation = -1
    private var mirrorPreviewLocation = -1
    private val textureMatrix = FloatArray(16)
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    @Volatile private var viewAssistEnabled = viewAssist
    @Volatile private var previewSqueezeFactor = 1f
    @Volatile private var previewDisplayRotationDegrees = displayRotationDegrees
    @Volatile private var recording: Recording? = null
    private val recordingPreparation = java.util.concurrent.atomic.AtomicReference<RecordingPreparation?>()
    @Volatile private var lastSourceDataSpace: Int? = null
    private var lastAnalysisAtMs = 0L
    @Volatile private var monitoringOptions = MonitoringOptions()
    fun setMonitoringOptions(options: MonitoringOptions) { monitoringOptions = options }
    private val closed = AtomicBoolean(false)
    private val retirement = CompletableFuture<Unit>()
    private val recordingFileLock = Any()
    private var lastRecordingFile = RecordingFileRetirement().apply { finish() }

    /** Last accepted file intent, even after recording becomes null. Caller cannot forge retirement. */
    fun recordingFileRetirement(): CompletableFuture<Unit> = synchronized(recordingFileLock) {
        lastRecordingFile.completion.thenApply { it }
    }

    private class RecordingFileRetirement {
        val completion = CompletableFuture<Unit>()
        private var failure: Throwable? = null
        @Synchronized fun noteFailure(problem: Throwable) {
            val first = failure
            if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
        }
        fun retire(action: () -> Unit): Throwable? = try { action(); null } catch (problem: Throwable) {
            noteFailure(problem); problem
        }
        @Synchronized fun finish() {
            val problem = failure
            if (problem == null) completion.complete(Unit) else completion.completeExceptionally(problem)
        }
    }
    private val previewRequestGeneration = java.util.concurrent.atomic.AtomicLong()
    private var subjectOutput: SubjectPreviewOutput? = null
    private val subjectRetirements = mutableListOf<java.util.concurrent.CompletableFuture<Unit>>()

    val cameraInputSurface: Surface = try {
        callOnGlThread {
            initializeEgl()
            initializeTextureAndProgram()
            preview?.let(::attachPreviewInternal)
            requireNotNull(inputSurface)
        }
    } catch (error: Throwable) { throw GpuPipelineInitializationFailure(error, closeAsync()) }

    fun setViewAssist(enabled: Boolean) {
        viewAssistEnabled = enabled
    }

    fun setPreviewSqueezeFactor(factor: Float) {
        previewSqueezeFactor = factor.coerceAtLeast(1f)
    }

    fun attachPreview(surface: Surface, displayRotationDegrees: Int): Boolean {
        if (closed.get() || !surface.isValid) return false
        val request = previewRequestGeneration.incrementAndGet()
        return handler.post {
            // A caller can be preempted between the public check and posting, including by
            // close(). Never reconnect a native window behind its retirement receipt.
            if (closed.get() || request != previewRequestGeneration.get() || !surface.isValid) return@post
            previewNativeSurface = surface
            previewDisplayRotationDegrees = displayRotationDegrees
            runCatching { attachPreviewInternal(surface) }.onFailure(::reportPreviewFailure)
        }
    }

    fun detachPreview() {
        val request = previewRequestGeneration.incrementAndGet()
        handler.post {
            if (closed.get() || request != previewRequestGeneration.get()) return@post
            previewNativeSurface = null
            runCatching { destroyPreviewSurface() }.onFailure(::reportPreviewFailure)
        }
    }

    /** Asynchronous output request; submission is reported after swap, not physical scan-out. */
    fun attachSubjectPreview(surface: Surface, options: SubjectPreviewOptions, onStatus: (SubjectPreviewStatus) -> Unit): Boolean {
        if (closed.get() || !surface.isValid) return false
        return handler.post {
            if (closed.get()) return@post
            runCatching {
                check(subjectOutput == null) { "Detach the previous subject output before attaching another." }
                check(makeCurrent(pbuffer)) { "Subject producer context is unavailable." }
                subjectOutput = SubjectPreviewOutput.create(display, context, requireNotNull(config8), surface, options, onStatus)
                if (subjectOutput == null) Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { onStatus(SubjectPreviewStatus(failure = "The previous subject output is still releasing its window. Retry after it has closed.", failureKind = SubjectPreviewFailure.BUSY)) }
                }
            }.onFailure { error ->
                Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { onStatus(SubjectPreviewStatus(failure = error.message ?: "Subject attachment failed.")) }
                }
            }
        }
    }

    fun updateSubjectPreview(options: SubjectPreviewOptions) {
        handler.post { subjectOutput?.updateOptions(options) }
    }

    fun detachSubjectPreview() {
        handler.post {
            if (!closed.get()) {
                makeCurrent(pbuffer)
                retireSubjectOutput()
            }
        }
    }

    private fun retireSubjectOutput() {
        subjectRetirements.removeAll { it.isDone }
        subjectOutput?.let { subjectRetirements += it.closeOnProducer() }
        subjectOutput = null
    }

    fun startRecording(
        output: ParcelFileDescriptor,
        bitrate: Int,
        geometry: RecordingGeometry,
        audio: Camera2EmbeddedAudioConfig? = null,
        separateAudioClock: CaptureEpochClock? = null,
        minimumSensorTimestampNs: Long? = null,
        timelapse: TimelapseCapture? = null,
        projectRateOverride: CaptureFrameRate? = null,
        onTimelapseProgress: ((TimelapseProgress) -> Unit)? = null,
        onTimelapsePauseChanged: ((TimelapsePauseStatus) -> Unit)? = null,
        onEncodedProgress: ((EncodedRecordingProgress) -> Unit)? = null,
        onStarted: () -> Unit,
        onStopped: (Boolean, OpenCineLogRecordingEvidence?) -> Unit,
        onRecordingLutApplied: ((BakedLutEvidence) -> Unit)? = null,
        recordingLut: MonitorLut? = null,
    ): Boolean {
        require(separateAudioClock == null || audio == null && timelapse == null && projectRateOverride == null &&
            separateAudioClock.cameraRealtime == cameraTimestampRealtime) { "Separate audio clock requires a matching regular capture route" }
        val preparation = RecordingPreparation()
        val fileRetirement = synchronized(recordingFileLock) {
            if (closed.get() || recording != null || !lastRecordingFile.completion.isDone ||
                lastRecordingFile.completion.isCompletedExceptionally ||
                !recordingPreparation.compareAndSet(null, preparation)) return false
            RecordingFileRetirement().also { lastRecordingFile = it }
        }
        var preparedOutput: ParcelFileDescriptor? = null
        var posted = false
        return try {
            // Own the descriptor before a queued/native preparation can outlive the caller.
            preparedOutput = ParcelFileDescriptor.dup(output.fileDescriptor)
            // AAC calibration runs 2-6 synchronous encode/decode probes. Run it on the calling thread, before the GL
            // task, so preview/scopes/subject output keep rendering; the probes still observe cancellation (close()
            // or caller failure). The GL_CALL_TIMEOUT_SECONDS deadline below covers only the GL-side preparation;
            // calibration is bounded by its own per-probe encode/decode deadlines.
            val aacCalibration = audio?.let {
                EmbeddedAac.calibrate(requireNotNull(appContext) { "An Android context is required for embedded AAC." }, it) {
                    preparation.isCancelled || closed.get()
                }
            }
            val task = FutureTask<Unit> {
              try {
                check(!preparation.isCancelled && !closed.get() && recording == null) { "Recording owner is closed or busy" }
                check(geometry.sourceSize.width == size.width && geometry.sourceSize.height == size.height) {
                    "Recording geometry does not match the active source."
                }
                require(timelapse == null || (passthroughSdr && audio == null)) { "Interval capture requires silent SDR." }
                require(projectRateOverride == null || (timelapse == null && passthroughSdr && audio == null)) { "Off-speed requires silent SDR and a single project clock." }
                require(recordingLut == null || monitorLutCompatible(recordingLut, passthroughSdr)) {
                    "Recording LUT input domain does not match the active source."
                }
                if (recordingLut != null) {
                    check(makeCurrent(pbuffer)) { "Recording LUT upload context is unavailable." }
                    // This is mandatory output processing, not an optional monitor. Failure
                    // aborts preparation before any native onStarted admission or file samples.
                    operatorProgram(recordingLut, forRecording = true)
                }
                val projectRate = timelapse?.projectRate ?: projectRateOverride ?: CaptureFrameRate(targetFps)
                val projectFps = projectRate.numerator.toDouble() / projectRate.denominator
                val encoderRate = kotlin.math.ceil(projectFps).toInt()
                val encodedSize = geometry.encodedSize
                val platformEncodedSize = Size(encodedSize.width, encodedSize.height)
                val mime = if (passthroughSdr) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
                val candidate = (if (passthroughSdr) avcEncoderSelector(platformEncodedSize, encoderRate, timelapse != null) else findEncoder(platformEncodedSize, targetFps))
                    ?: error("No ${if (timelapse == null) "hardware " else "advertised "}$mime Surface encoder accepts ${encodedSize.width}x${encodedSize.height} at $encoderRate fps.")
                val format = MediaFormat.createVideoFormat(mime, encodedSize.width, encodedSize.height).apply {
                    setInteger(MediaFormat.KEY_PROFILE, candidate.profile)
                    // OMX AVC requires the advertised level whenever a profile is supplied.
                    candidate.level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    val rateScaledBitrate = bitrate.toLong() * encoderRate / 30
                    setInteger(MediaFormat.KEY_BIT_RATE, rateScaledBitrate.coerceIn(12_000_000L, 200_000_000L).toInt())
                    if (timelapse == null && projectRateOverride == null) setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                    else setFloat(MediaFormat.KEY_FRAME_RATE, projectFps.toFloat())
                    if (timelapse != null || projectRateOverride != null) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    if (passthroughSdr || recordingLut != null) {
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    } else {
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_LINEAR)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, geometry.pixelAspectRatioWidth)
                        setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, geometry.pixelAspectRatioHeight)
                    }
                }
                check(!preparation.isCancelled && !closed.get()) { "Recording preparation was cancelled" }
                val ownedOutput = requireNotNull(preparedOutput)
                val codec = MediaCodec.createByCodecName(candidate.codecName)
                var codecSurface: Surface? = null
                var muxer: MediaMuxer? = null
                var embeddedAac: EmbeddedAac? = null
                var preparedDrain: Thread? = null
                var codecStarted = false
                val active = try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codecSurface = codec.createInputSurface()
                    // Own before configuration: a throwing setter must not hide a live muxer.
                    muxer = MediaMuxer(ownedOutput.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    requireNotNull(muxer).setOrientationHint(geometry.containerRotationDegrees)
                    val captureEpoch = separateAudioClock ?: audio?.let { CaptureEpochClock(cameraTimestampRealtime, it.sampleRateHz) }
                    embeddedAac = audio?.let {
                        EmbeddedAac.create(requireNotNull(appContext) { "An Android context is required for embedded AAC." }, it,
                            requireNotNull(aacCalibration), requireNotNull(captureEpoch), fileRetirement::noteFailure) { preparation.isCancelled || closed.get() }
                    }
                    check(!preparation.isCancelled && !closed.get()) { "Recording preparation was cancelled" }
                    val eglSurface = createWindowSurface(
                        if (passthroughSdr) requireNotNull(config8) else requireNotNull(config10),
                        codecSurface,
                        if (passthroughSdr || recordingLut != null) intArrayOf(EGL14.EGL_NONE) else {
                            intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_LINEAR_EXT, EGL14.EGL_NONE)
                        },
                    )
                    check(eglSurface != EGL14.EGL_NO_SURFACE) { "Encoder did not accept the EGL window surface." }
                    encoderEglSurface = eglSurface
                    val active = Recording(requireNotNull(ownedOutput), codec, codecSurface, muxer, candidate, geometry, embeddedAac, captureEpoch, minimumSensorTimestampNs, timelapse?.let(::TimelapseTimeline), projectRateOverride?.let(::ProjectFrameTimeline), onTimelapseProgress, onTimelapsePauseChanged, onEncodedProgress, onStopped, fileRetirement, recordingLut, onRecordingLutApplied)
                    recording = active
                    codec.start()
                    codecStarted = true
                    check(!preparation.isCancelled && !closed.get()) { "Recording preparation was cancelled" }
                    embeddedAac?.start { failure ->
                        active.failure = failure
                        stopRecording()
                    }
                    check(active.failure == null && !closed.get()) { "Recording preparation failed or closed" }
                    preparedDrain = Thread({ if (preparation.awaitCommit()) drain(active) }, "OpenCineLogCodecDrain")
                    active.drainThread = preparedDrain
                    preparedDrain.start()
                    check(preparation.commit()) { "Recording preparation was cancelled before commit" }
                    active
                } catch (failure: Throwable) {
                    preparation.cancel()
                    // A cancelled drain has never touched native state; join it before owner cleanup.
                    joinOwnedWorker(preparedDrain)
                    recording = null
                    fun retire(action: () -> Unit) {
                        fileRetirement.retire(action)?.let { if (it !== failure) failure.addSuppressed(it) }
                    }
                    if (encoderEglSurface != EGL14.EGL_NO_SURFACE) retire {
                        if (EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == encoderEglSurface) check(makeCurrent(pbuffer))
                        check(EGL14.eglDestroySurface(display, encoderEglSurface)) { "Preparing encoder EGL surface did not retire" }
                        encoderEglSurface = EGL14.EGL_NO_SURFACE
                    }
                    retire { embeddedAac?.close() }
                    retire { muxer?.release() }
                    retire { codecSurface?.release() }
                    // configure may have failed before start; release is mandatory in every state.
                    if (codecStarted) retire { codec.stop() }
                    retire { codec.release() }
                    throw failure
                }
                // Commit owns the take before external callbacks, which can themselves block.
                runCatching { active.reportEncodedProgress(); onStarted(); active.reportPauseStatus() }.onFailure { active.failure = it; stopRecording() }
              } finally {
                try {
                    if (!preparation.isCommitted) {
                        fileRetirement.retire { releaseRecordingLutTexture() }
                        fileRetirement.retire { preparedOutput?.close() }
                        fileRetirement.finish()
                    }
                } finally { if (preparation.ownerFinished()) recordingPreparation.compareAndSet(preparation, null) }
              }
            }
            if (Thread.currentThread() === thread) { posted = true; task.run() }
            else { check(handler.post(task)) { "OCLog GL thread is closed." }; posted = true }
            task.get(GL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            true
        } catch (failure: Throwable) {
            val cancelled = preparation.cancel()
            if (!posted) {
                fileRetirement.retire { preparedOutput?.close() }?.let { if (it !== failure) failure.addSuppressed(it) }
                fileRetirement.finish()
                preparation.ownerFinished()
            }
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (!cancelled) true else {
                onFailure("log-recording-prepare-failed", failure.message ?: "OCLog recording could not be prepared.")
                false
            }
        } finally {
            if (preparation.callerFinished()) recordingPreparation.compareAndSet(preparation, null)
        }
    }

    /**
     * Why the most recent pause/resume request was rejected (synchronously or via `onComplete(false)`);
     * null after an applied request. The boolean API is unchanged so callers can surface this later.
     */
    @Volatile var lastPauseRejection: CapturePauseRejection? = null
        private set

    private fun rejectPause(reason: CapturePauseRejection): Boolean { lastPauseRejection = reason; return false }

    /** Acceptance is asynchronous; a stale/finished take receives no state change. */
    fun setTimelapsePaused(paused: Boolean, onComplete: (Boolean) -> Unit): Boolean {
        val active = recording ?: return rejectPause(CapturePauseRejection.NOT_RECORDING)
        if (closed.get()) return rejectPause(CapturePauseRejection.NOT_RECORDING)
        if (active.stopRequested.get()) return rejectPause(CapturePauseRejection.STOPPED)
        if (active.timelapse == null && active.captureEpoch?.pauseAvailable() != true) {
            return rejectPause(active.captureEpoch?.pauseUnavailableReason() ?: CapturePauseRejection.UNSUPPORTED_CLOCK)
        }
        return handler.post {
            if (closed.get() || recording !== active || active.stopRequested.get()) {
                rejectPause(if (active.stopRequested.get()) CapturePauseRejection.STOPPED else CapturePauseRejection.NOT_RECORDING)
                onComplete(false)
            } else {
                val timelapse = active.timelapse
                val captureEpoch = active.captureEpoch
                val rejection = when {
                    timelapse != null -> when {
                        timelapse.setPaused(paused) -> null
                        timelapse.complete -> CapturePauseRejection.STOPPED
                        else -> CapturePauseRejection.UNCHANGED
                    }
                    captureEpoch != null -> captureEpoch.requestPaused(paused, android.os.SystemClock.elapsedRealtimeNanos())
                    else -> CapturePauseRejection.UNSUPPORTED_CLOCK
                }
                lastPauseRejection = rejection
                val changed = rejection == null
                if (changed) {
                    requireNotNull(active.pauseClock).setPaused(paused, active.timelapse?.selectedFrames ?: active.frames)
                    active.reportPauseStatus()
                }
                onComplete(changed)
            }
        }.also { posted -> if (!posted) lastPauseRejection = CapturePauseRejection.NOT_RECORDING }
    }

    fun stopRecording(): Boolean {
        val active = recording ?: return false
        if (!active.stopRequested.compareAndSet(false, true)) return false
        handler.post {
            // No draw may occur after EOS is signalled.
            active.acceptFrames.set(false)
            active.captureEpoch?.finishPause(android.os.SystemClock.elapsedRealtimeNanos())
            active.reportPauseStatus(finished = true)
            active.audio?.requestStop()
            runCatching { active.codec.signalEndOfInputStream() }
                .onFailure { active.failure = it }
        }
        return true
    }

    private fun initializeEgl() {
        display = GpuEglDisplayLease.acquire()
        config10 = chooseConfig(10, 10, 10, 2)
        config8 = chooseConfig(8, 8, 8, 8) ?: error("An 8-bit EGL preview config is unavailable.")
        if (!passthroughSdr && config10 == null) {
            error("A 10-bit RGBA EGL render target is required for honest Main10 LOG recording.")
        }
        val renderConfig = if (passthroughSdr) requireNotNull(config8) else requireNotNull(config10)
        context = EGL14.eglCreateContext(
            display,
            renderConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "OpenGL ES 3 context creation failed." }
        previewContext = EGL14.eglCreateContext(
            display,
            requireNotNull(config8),
            context,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        check(previewContext != EGL14.EGL_NO_CONTEXT) { "Shared 8-bit preview context creation failed." }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display,
            renderConfig,
            intArrayOf(EGL14.EGL_WIDTH, ANALYSIS_WIDTH, EGL14.EGL_HEIGHT, ANALYSIS_HEIGHT, EGL14.EGL_NONE),
            0,
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE && makeCurrent(pbuffer)) { "EGL pbuffer creation failed." }
    }

    private fun chooseConfig(red: Int, green: Int, blue: Int, alpha: Int): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(16)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, red,
                EGL14.EGL_GREEN_SIZE, green,
                EGL14.EGL_BLUE_SIZE, blue,
                EGL14.EGL_ALPHA_SIZE, alpha,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            ),
            0,
            configs,
            0,
            configs.size,
            count,
            0,
        )
        return if (ok && count[0] > 0) configs[0] else null
    }

    private fun initializeTextureAndProgram() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        program = linkProgram(VERTEX_SHADER, if (passthroughSdr) PASSTHROUGH_FRAGMENT_SHADER else fragmentShader(sourcePath))
        quad = PreviewQuad()
        textureMatrixLocation = GLES30.glGetUniformLocation(program, "uTextureMatrix")
        outputModeLocation = GLES30.glGetUniformLocation(program, "uOutputMode")
        positionScaleLocation = GLES30.glGetUniformLocation(program, "uPositionScale")
        previewRotationLocation = GLES30.glGetUniformLocation(program, "uPreviewRotation")
        mirrorPreviewLocation = GLES30.glGetUniformLocation(program, "uMirrorPreview")
        val createdTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(size.width, size.height)
            setOnFrameAvailableListener({ renderLatestFrame() }, handler)
        }
        surfaceTexture = createdTexture
        inputSurface = Surface(createdTexture)
    }

    private fun renderLatestFrame() {
        if (closed.get()) return
        val texture = surfaceTexture ?: return
        try {
            makeCurrent(pbuffer)
            texture.updateTexImage()
            val sourceReceivedAtMs = android.os.SystemClock.elapsedRealtime()
            texture.getTransformMatrix(textureMatrix)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lastSourceDataSpace = texture.dataSpace
            }
            val timestampNs = texture.timestamp
            recording?.takeIf { it.acceptFrames.get() && recordingFrameMeetsWhiteBalanceBoundary(timestampNs, it.minimumSensorTimestampNs) }?.let { active ->
                val capturePts = active.captureEpoch?.mapVideoInput(timestampNs)
                if (!active.videoRegressionLogged && (active.captureEpoch?.regressedVideoFrames() ?: 0L) > 0L) {
                    active.videoRegressionLogged = true
                    android.util.Log.w(LOG_TAG, "Camera timestamp regressed to $timestampNs ns; frame dropped (counted in sharedPause.regressedVideoFrames).")
                }
                if (!active.pauseStatusPublished && active.captureEpoch?.pauseAvailable() == true) active.reportPauseStatus()
                val selectedPts = when {
                    active.timelapse != null -> active.timelapse.select(timestampNs)
                    active.projectTimeline != null -> active.projectTimeline.select(timestampNs)
                    else -> if (active.captureEpoch == null) timestampNs else capturePts
                }
                if (selectedPts != null && encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                    check(makeCurrent(encoderEglSurface)) { "Encoder EGL surface is unavailable." }
                    draw(
                        outputMode = OUTPUT_OCLOG,
                        width = active.geometry.encodedSize.width,
                        height = active.geometry.encodedSize.height,
                        previewOutput = false,
                        recordingGeometry = active.geometry,
                        recordingLut = active.recordingLut,
                    )
                    check(EGLExt.eglPresentationTimeANDROID(display, encoderEglSurface, selectedPts)) { "Encoder rejected presentation timestamp." }
                    check(EGL14.eglSwapBuffers(display, encoderEglSurface)) { "Encoder EGL swap failed." }
                    if (!active.lutApplied && active.recordingLut != null) {
                        active.lutApplied = true
                        active.onRecordingLutApplied?.invoke(requireNotNull(active.lutEvidence))
                    }
                    active.timelapse?.let { clock ->
                        active.onTimelapseProgress?.invoke(TimelapseProgress(clock.selectedFrames, clock.missedIntervals, active.candidate.codecName, active.candidate.hardwareAccelerated))
                    }
                }
            }
            renderScopeAnalysisIfDue()
            subjectOutput?.let { output ->
                check(makeCurrent(pbuffer)) { "Subject producer pbuffer is unavailable." }
                output.render(sourceReceivedAtMs) { width, height, options ->
                    val geometry = OpenCineLogPreviewGeometryCalculator.calculate(
                        sourceWidth = size.width, sourceHeight = size.height,
                        targetWidth = width, targetHeight = height,
                        sensorOrientationDegrees = sensorOrientationDegrees,
                        displayRotationDegrees = options.displayRotationDegrees,
                        frontFacing = frontFacing, squeezeFactor = options.squeezeFactor,
                    ).copy(mirrorHorizontally = options.mirror)
                    requireNotNull(draw(if (options.viewAssist) OUTPUT_VIEW_ASSIST else OUTPUT_FLAT_MONITOR,
                        width, height, previewOutput = false, outputGeometry = geometry, subjectLutOutput = true))
                }
            }
            if (previewNativeSurface?.isValid == true && previewEglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching {
                    check(makeCurrent(previewEglSurface)) { "Preview EGL window is unavailable." }
                    val width = querySurface(EGL14.EGL_WIDTH).coerceAtLeast(1)
                    val height = querySurface(EGL14.EGL_HEIGHT).coerceAtLeast(1)
                    pendingOperatorLutStatus = null
                    draw(if (viewAssistEnabled) OUTPUT_VIEW_ASSIST else OUTPUT_FLAT_MONITOR, width, height, previewOutput = true)
                    check(EGL14.eglSwapBuffers(display, previewEglSurface)) { "Preview EGL swap failed." }
                    pendingOperatorLutStatus?.let(::reportOperatorLut)
                }.onFailure(::reportPreviewFailure)
            }
        } catch (failure: Throwable) {
            reportGlFailure(failure)
        } finally {
            if (pbuffer != EGL14.EGL_NO_SURFACE) makeCurrent(pbuffer)
        }
    }

    private fun renderScopeAnalysisIfDue() {
        val callback = onAnalysis ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val options = monitoringOptions
        if (now - lastAnalysisAtMs < options.periodMs) return
        lastAnalysisAtMs = now
        check(makeCurrent(pbuffer)) { "Scope analysis pbuffer is unavailable." }
        draw(
            outputMode = OUTPUT_OCLOG,
            width = ANALYSIS_WIDTH,
            height = ANALYSIS_HEIGHT,
            previewOutput = false,
            recordingGeometry = null,
        )
        val rgba = ByteBuffer.allocateDirect(ANALYSIS_WIDTH * ANALYSIS_HEIGHT * 4)
        GLES30.glReadPixels(
            0,
            0,
            ANALYSIS_WIDTH,
            ANALYSIS_HEIGHT,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            rgba,
        )
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "Scope readback failed with GL error 0x${error.toString(16)}." }
        val bytes = ByteArray(rgba.capacity())
        rgba.position(0)
        rgba.get(bytes)
        callback(analyzeRgbaFrame(ANALYSIS_WIDTH, ANALYSIS_HEIGHT, bytes, now, options,
            if (passthroughSdr) MonitoringSignalDomain.SDR_BT709_CODE else MonitoringSignalDomain.OCLOG2_CODE))
    }

    // This boundary returns the encoded 16-bit payload, not an integer conversion of its value.
    private fun nativeHalfBits(value: Float): Short = android.util.Half.toHalf(value)

    private fun releaseRecordingLutTexture() {
        if (recordingLutTexture == 0) return
        check(makeCurrent(pbuffer)) { "Recording LUT retirement context is unavailable." }
        GLES30.glDeleteTextures(1, intArrayOf(recordingLutTexture), 0)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Recording LUT texture did not retire." }
        recordingLutTexture = 0
        recordingLutTextureHash = null
    }

    private fun operatorProgram(lut: MonitorLut, forSubject: Boolean = false, forRecording: Boolean = false): Int {
        check(!forSubject || !forRecording)
        if (operatorLutProgram == 0) {
            val source = if (passthroughSdr) PASSTHROUGH_FRAGMENT_SHADER else fragmentShader(sourcePath)
            val declarations = """
                uniform highp sampler3D uOperatorLut;
                uniform vec3 uLutMin;
                uniform vec3 uLutMax;
                uniform float uLutSize;
                vec3 applyOperatorLut(vec3 signal) {
                    vec3 normalized = clamp((signal - uLutMin) / (uLutMax - uLutMin), 0.0, 1.0);
                    // Eight texel fetches implement the same trilinear interpolation as the CPU
                    // without depending on the sampler's fixed-function filtering precision.
                    vec3 coordinate = normalized * (uLutSize - 1.0);
                    ivec3 lo = ivec3(floor(coordinate));
                    ivec3 hi = min(lo + ivec3(1), ivec3(int(uLutSize) - 1));
                    vec3 weight = fract(coordinate);
                    vec3 c00 = mix(texelFetch(uOperatorLut, ivec3(lo.x,lo.y,lo.z),0).rgb,
                                   texelFetch(uOperatorLut, ivec3(hi.x,lo.y,lo.z),0).rgb, weight.x);
                    vec3 c10 = mix(texelFetch(uOperatorLut, ivec3(lo.x,hi.y,lo.z),0).rgb,
                                   texelFetch(uOperatorLut, ivec3(hi.x,hi.y,lo.z),0).rgb, weight.x);
                    vec3 c01 = mix(texelFetch(uOperatorLut, ivec3(lo.x,lo.y,hi.z),0).rgb,
                                   texelFetch(uOperatorLut, ivec3(hi.x,lo.y,hi.z),0).rgb, weight.x);
                    vec3 c11 = mix(texelFetch(uOperatorLut, ivec3(lo.x,hi.y,hi.z),0).rgb,
                                   texelFetch(uOperatorLut, ivec3(hi.x,hi.y,hi.z),0).rgb, weight.x);
                    return mix(mix(c00,c10,weight.y),mix(c01,c11,weight.y),weight.z);
                }
            """.trimIndent()
            val augmented = source.replace("void main() {", declarations + "\nvoid main() {")
            val signal = if (passthroughSdr) "texture(uTexture, vTexCoord).rgb" else "ocLog"
            val end = augmented.lastIndexOf('}')
            operatorLutProgram = linkProgram(VERTEX_SHADER, augmented.substring(0, end) +
                "outColor = vec4(applyOperatorLut($signal), 1.0);\n}")
        }
        val previousTextureHash = when { forRecording -> recordingLutTextureHash; forSubject -> subjectLutTextureHash; else -> operatorLutTextureHash }
        if (previousTextureHash != lut.cube.sha256) {
            if (forRecording) check(recordingLutTexture == 0) { "Previous recording LUT did not retire." }
            val texture = IntArray(1); GLES30.glGenTextures(1, texture, 0)
            // Keep the file's native allocation reachable even if upload/configuration throws;
            // the preparation finally must verify its retirement before releasing the receipt.
            if (forRecording) recordingLutTexture = texture[0]
            try {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texture[0])
                for (parameter in listOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER))
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, parameter, GLES30.GL_NEAREST)
                for (parameter in listOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R))
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, parameter, GLES30.GL_CLAMP_TO_EDGE)
                val values = lut.cube.values
                // Upload native half-floats: FLOAT conversion corrupted nonzero texels on
                // the API30 GLES translator. RGBA rows remain naturally four-byte aligned.
                val halves = ShortArray(values.size / 3 * 4)
                for (index in values.indices step 3) {
                    val offset = index / 3 * 4
                    halves[offset] = nativeHalfBits(values[index])
                    halves[offset + 1] = nativeHalfBits(values[index + 1])
                    halves[offset + 2] = nativeHalfBits(values[index + 2])
                    halves[offset + 3] = nativeHalfBits(1f)
                }
                val buffer = ByteBuffer.allocateDirect(halves.size * 2)
                    .order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                buffer.put(halves).position(0)
                GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F,
                    lut.cube.size, lut.cube.size, lut.cube.size, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buffer)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Operator LUT texture upload failed" }
                val previousTexture = when { forRecording -> 0; forSubject -> subjectLutTexture; else -> operatorLutTexture }
                if (previousTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(previousTexture), 0)
                if (forRecording) {
                    recordingLutTexture = texture[0]; recordingLutTextureHash = lut.cube.sha256
                } else if (forSubject) {
                    subjectLutTexture = texture[0]; subjectLutTextureHash = lut.cube.sha256
                } else {
                    operatorLutTexture = texture[0]; operatorLutTextureHash = lut.cube.sha256
                }
            } catch (failure: Throwable) {
                if (!forRecording) GLES30.glDeleteTextures(1, texture, 0)
                throw failure
            } finally { GLES30.glActiveTexture(GLES30.GL_TEXTURE0) }
        }
        return operatorLutProgram
    }

    private fun draw(
        outputMode: Int,
        width: Int,
        height: Int,
        previewOutput: Boolean,
        recordingGeometry: RecordingGeometry? = null,
        outputGeometry: OpenCineLogPreviewGeometry? = null,
        subjectLutOutput: Boolean = false,
        recordingLut: MonitorLut? = null,
    ): OperatorLutStatus? {
        check(!previewOutput || !subjectLutOutput)
        check(recordingLut == null || !previewOutput && !subjectLutOutput)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        // Selection is frozen with these pixels; the consumer may swap after selection changes.
        val selection = if (previewOutput) operatorLut else if (subjectLutOutput) subjectLut else recordingLut
        val lut = selection?.takeIf { monitorLutCompatible(it, passthroughSdr) }
        val previousFailure = if (subjectLutOutput) subjectLutFailure?.hash else failedOperatorLutHash
        val selectedProgram = if (recordingLut != null) {
            check(lut === recordingLut) { "Recording LUT domain changed." }
            // No fallback is permitted for requested file pixels, even if monitor uploads fail.
            check(recordingLutTexture != 0 && recordingLutTextureHash == recordingLut.cube.sha256) {
                "Recording LUT texture is not prepared."
            }
            check(operatorLutProgram != 0) { "Recording LUT program is not prepared." }
            operatorLutProgram
        } else if (lut != null && previousFailure != lut.cube.sha256) {
            try { operatorProgram(lut, forSubject = subjectLutOutput) } catch (failure: Exception) {
                val status = OperatorLutStatus(lut.cube.sha256, OperatorLutState.FAILED, failure.message, monitorLutIdentity(lut))
                if (subjectLutOutput) subjectLutFailure = status else {
                    failedOperatorLutHash = lut.cube.sha256
                    reportOperatorLut(status)
                }
                // Optional monitors cannot poison the encoder, scopes or each other's output.
                for (attempt in 0 until 8) if (GLES30.glGetError() == GLES30.GL_NO_ERROR) break
                program
            }
        } else program
        GLES30.glUseProgram(selectedProgram)
        fun location(name: String, original: Int): Int = if (selectedProgram == program) original else GLES30.glGetUniformLocation(selectedProgram, name)
        if (selectedProgram != program && lut != null) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, when { recordingLut != null -> recordingLutTexture; subjectLutOutput -> subjectLutTexture; else -> operatorLutTexture })
            GLES30.glUniform1i(GLES30.glGetUniformLocation(selectedProgram, "uOperatorLut"), 1)
            GLES30.glUniform3fv(GLES30.glGetUniformLocation(selectedProgram, "uLutMin"), 1, lut.cube.domainMin, 0)
            GLES30.glUniform3fv(GLES30.glGetUniformLocation(selectedProgram, "uLutMax"), 1, lut.cube.domainMax, 0)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(selectedProgram, "uLutSize"), lut.cube.size.toFloat())
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniformMatrix4fv(location("uTextureMatrix", textureMatrixLocation), 1, false, textureMatrix, 0)
        GLES30.glUniform1i(location("uOutputMode", outputModeLocation), outputMode)
        val geometry = outputGeometry ?: if (previewOutput) {
            OpenCineLogPreviewGeometryCalculator.calculate(
                sourceWidth = size.width,
                sourceHeight = size.height,
                targetWidth = width,
                targetHeight = height,
                sensorOrientationDegrees = sensorOrientationDegrees,
                displayRotationDegrees = previewDisplayRotationDegrees,
                frontFacing = frontFacing,
                squeezeFactor = previewSqueezeFactor,
            )
        } else if (recordingGeometry != null) {
            OpenCineLogPreviewGeometry(
                positionRotationDegrees = recordingGeometry.rendererRotationDegrees,
                scaleX = 1f,
                scaleY = 1f,
                mirrorHorizontally = false,
            )
        } else {
            OpenCineLogPreviewGeometry(0, 1f, 1f, false)
        }
        GLES30.glUniform2f(location("uPositionScale", positionScaleLocation), geometry.scaleX, geometry.scaleY)
        GLES30.glUniform1i(location("uPreviewRotation", previewRotationLocation), geometry.positionRotationDegrees / 90)
        GLES30.glUniform1i(location("uMirrorPreview", mirrorPreviewLocation), if (geometry.mirrorHorizontally) 1 else 0)
        requireNotNull(quad).draw()
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "OCLog shader failed with GL error 0x${error.toString(16)}." }
        if (previewOutput) {
            // A concurrent selection change cannot relabel the pixels just drawn. The next
            // frame will apply it; only an unchanged selection can produce a pending swap status.
            pendingOperatorLutStatus = if (operatorLut !== selection) null else when {
                selection == null -> OperatorLutStatus()
                lut == null -> OperatorLutStatus(selection.cube.sha256, OperatorLutState.INCOMPATIBLE_DOMAIN,
                    selectionId = monitorLutIdentity(selection))
                selectedProgram != program -> OperatorLutStatus(selection.cube.sha256, OperatorLutState.ACTIVE,
                    selectionId = monitorLutIdentity(selection))
                else -> null
            }
        }
        return if (!subjectLutOutput) null else when {
            selection == null -> OperatorLutStatus()
            lut == null -> OperatorLutStatus(selection.cube.sha256, OperatorLutState.INCOMPATIBLE_DOMAIN,
                selectionId = monitorLutIdentity(selection))
            selectedProgram != program -> OperatorLutStatus(selection.cube.sha256, OperatorLutState.ACTIVE,
                selectionId = monitorLutIdentity(selection))
            else -> requireNotNull(subjectLutFailure).copy(selectionId = monitorLutIdentity(selection))
        }
    }

    private fun attachPreviewInternal(surface: Surface) {
        destroyPreviewSurface()
        if (!surface.isValid) return
        previewEglSurface = createWindowSurface(requireNotNull(config8), surface)
        check(previewEglSurface != EGL14.EGL_NO_SURFACE) { "Preview EGL surface creation failed." }
    }

    private fun createWindowSurface(
        config: EGLConfig,
        surface: Surface,
        attributes: IntArray = intArrayOf(EGL14.EGL_NONE),
    ): EGLSurface = EGL14.eglCreateWindowSurface(display, config, surface, attributes, 0)

    private fun querySurface(attribute: Int): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(display, previewEglSurface, attribute, value, 0)
        return value[0]
    }

    private fun drain(active: Recording) {
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()
        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false
        var videoEos = false
        var audioEos = active.audio == null
        val pending = ArrayDeque<PendingMuxSample>()
        val timestampNormalizer = MuxTimestampNormalizer(active.captureEpoch.takeIf { active.audio != null })
        val maxPendingSamples = maxPendingMuxSamples(targetFps, active.audio?.calibration?.config?.sampleRateHz,
            active.audio?.calibration?.config?.let { it.maxInputBytes / (2 * it.channels) }) // PCM16 feeder read size
        var audioEncoderDelayFrames: Int? = null
        var stopDeadlineNs: Long? = null

        fun noteVideoSample(video: Boolean, normalizedPtsUs: Long) {
            if (!video) { active.audioPackets++; return }
            active.frames++
            active.lastPtsUs?.let { previousPtsUs ->
                val gapUs = (normalizedPtsUs - previousPtsUs).coerceAtLeast(0L)
                active.maxVideoPtsGapUs = maxOf(active.maxVideoPtsGapUs ?: 0L, gapUs)
                if (gapUs > 1_500_000L / targetFps) active.videoPtsGapsOverThreshold++
            }
            active.firstPtsUs = active.firstPtsUs ?: normalizedPtsUs
            active.lastPtsUs = normalizedPtsUs
            active.reportEncodedProgress()
        }

        fun startMuxerIfReady() {
            if (muxerStarted || videoTrack < 0 || (active.audio != null && audioTrack < 0) || active.audio != null && active.captureEpoch?.ready() == false) return
            active.muxer.start()
            muxerStarted = true
            val normalizedPending = pending.map {
                it.copy(presentationTimeUs = timestampNormalizer.normalize(it.video, it.presentationTimeUs))
            }.sortedBy { it.presentationTimeUs }
            normalizedPending.forEach { sample ->
                val track = if (sample.video) videoTrack else audioTrack
                active.muxer.writeSampleData(
                    track,
                    ByteBuffer.wrap(sample.data),
                    MediaCodec.BufferInfo().apply {
                        offset = 0
                        size = sample.data.size
                        presentationTimeUs = sample.presentationTimeUs
                        flags = sample.flags
                    },
                )
                noteVideoSample(sample.video, sample.presentationTimeUs)
            }
            pending.clear()
        }

        fun drainOne(codec: MediaCodec, info: MediaCodec.BufferInfo, video: Boolean, timeoutUs: Long): Boolean {
            when (val index = codec.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    if (video && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        format.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, active.geometry.pixelAspectRatioWidth)
                        format.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, active.geometry.pixelAspectRatioHeight)
                    }
                    if (video) {
                        if (active.recordingLut != null) {
                            check(format.getInteger(MediaFormat.KEY_COLOR_STANDARD, -1) == MediaFormat.COLOR_STANDARD_BT709 &&
                                format.getInteger(MediaFormat.KEY_COLOR_RANGE, -1) == MediaFormat.COLOR_RANGE_LIMITED &&
                                format.getInteger(MediaFormat.KEY_COLOR_TRANSFER, -1) == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) {
                                "Baked LUT encoder did not report the required BT709 limited SDR output contract."
                            }
                        }
                        check(videoTrack < 0) { "Video encoder emitted its format twice." }
                        videoTrack = active.muxer.addTrack(format)
                    } else {
                        check(audioTrack < 0) { "AAC encoder emitted its format twice." }
                        val csd = requireNotNull(format.getByteBuffer("csd-0")).duplicate().let { value -> ByteArray(value.remaining()).also { value.get(it) } }
                        AacCodecCalibrator.requireCalibratedCodecConfig(requireNotNull(active.audio).calibration, csd)
                        audioEncoderDelayFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) {
                            format.getInteger(MediaFormat.KEY_ENCODER_DELAY)
                        } else null
                        audioTrack = active.muxer.addTrack(format)
                    }
                    startMuxerIfReady()
                    return true
                }
                else -> if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    val isCodecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && buffer != null && !isCodecConfig) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (muxerStarted) {
                            val normalizedPtsUs = timestampNormalizer.normalize(video, info.presentationTimeUs)
                            val normalizedInfo = MediaCodec.BufferInfo().apply { set(info.offset, info.size, normalizedPtsUs, info.flags) }
                            active.muxer.writeSampleData(if (video) videoTrack else audioTrack, buffer, normalizedInfo)
                            noteVideoSample(video, normalizedPtsUs)
                        } else {
                            check(pending.size < maxPendingSamples) { "Muxer format/epoch negotiation buffer overflowed." }
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            pending.add(PendingMuxSample(video, bytes, info.presentationTimeUs, info.flags))
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (video) videoEos = true else audioEos = true
                    }
                    codec.releaseOutputBuffer(index, false)
                    return true
                }
            }
            return false
        }
        try {
            while (!videoEos || !audioEos) {
                val audioProgress = active.audio?.takeUnless { audioEos }?.let {
                    drainOne(it.codec, audioInfo, video = false, timeoutUs = 0)
                } ?: false
                val videoProgress = if (!videoEos) {
                    drainOne(active.codec, videoInfo, video = true, timeoutUs = if (audioProgress) 0 else CODEC_TIMEOUT_US)
                } else false
                startMuxerIfReady()
                if (active.stopRequested.get()) {
                    val deadline = stopDeadlineNs ?: (System.nanoTime() + CODEC_STOP_TIMEOUT_NS).also { stopDeadlineNs = it }
                    if (System.nanoTime() > deadline) error("Codec EOS timed out while finalizing the recording.")
                    if (!audioProgress && !videoProgress && active.failure != null) break
                }
            }
        } catch (failure: Throwable) {
            active.failure = failure
        } finally {
            val glReleased = CompletableFuture<Unit>()
            val posted = handler.post {
                try {
                    active.acceptFrames.set(false)
                    if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                        if (EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == encoderEglSurface) check(makeCurrent(pbuffer))
                        check(EGL14.eglDestroySurface(display, encoderEglSurface)) { "Encoder EGL surface did not detach" }
                        encoderEglSurface = EGL14.EGL_NO_SURFACE
                    }
                    releaseRecordingLutTexture()
                    glReleased.complete(Unit)
                } catch (failure: Throwable) { glReleased.completeExceptionally(failure) }
            }
            if (!posted) {
                active.failure = IllegalStateException("GL retirement task was rejected").also(active.fileRetirement::noteFailure)
                // A quitting looper can still be inside a native call. Wait for actual thread exit.
                joinOwnedWorker(thread)
            } else {
                try {
                    awaitOwnedCompletion(glReleased, 3000) {
                        active.failure = IllegalStateException("Encoder GL retirement is still pending")
                        if (!closed.get()) onFailure("log-retirement-pending", "Encoder GL work is still retiring.")
                    }
                } catch (failure: Throwable) { active.failure = failure; active.fileRetirement.noteFailure(failure) }
            }
            // No AAC codec release while its feeder/stop thread can still call into native code.
            active.fileRetirement.retire { active.audio?.close() }?.let(active::noteFailure)
            if (active.timelapse != null && active.frames != active.timelapse.selectedFrames) {
                active.failure = IllegalStateException("Timelapse encoder emitted ${active.frames} frames for ${active.timelapse.selectedFrames} selected inputs.")
            }
            if (active.projectTimeline != null && active.frames != active.projectTimeline.selectedFrames) {
                active.failure = IllegalStateException("Off-speed encoder frame count differs from selected inputs.")
            }
            val success = active.failure == null && muxerStarted && videoEos && audioEos &&
                (active.recordingLut == null || active.lutApplied) &&
                hasRequiredEncodedSamples(active.frames, active.audio?.submittedPcmFrames, active.audioPackets)
            val projectRate = active.timelapse?.capture?.projectRate ?: active.projectTimeline?.rate
            var audioSourceWindow: AacSourceWindowResult? = null
            val timelineFinalizer: (() -> Unit)? = when {
                projectRate != null -> { { finalizeProjectMp4Timing(ProjectMp4Descriptor(active.outputDescriptor.fileDescriptor), projectRate, active.frames) } }
                active.audio != null -> { {
                    val audio = active.audio
                    requireNotNull(active.captureEpoch).requireRetainedAudioFrames(audio.submittedPcmFrames)
                    check(audio.drainPaddingFrames == audio.calibration.drainPaddingFrames.toLong()) { "AAC drain padding did not complete" }
                    audioSourceWindow = finalizeAacSourceWindow(ProjectMp4Descriptor(active.outputDescriptor.fileDescriptor),
                        AacSourceWindow(audio.calibration.config.sampleRateHz, audio.submittedPcmFrames,
                            audio.calibration.primingFrames.toLong(), active.audioPackets, requireNotNull(active.captureEpoch).offsetUs(false)))
                } }
                else -> null
            }
            active.failure = finalizeEncodedRecording(
                ready = success,
                initialFailure = active.failure,
                muxerStarted = muxerStarted,
                stopMuxer = { active.fileRetirement.retire(active.muxer::stop)?.let { throw it } },
                releaseMuxer = { active.fileRetirement.retire(active.muxer::release)?.let { throw it } },
                finalizeTimeline = timelineFinalizer,
            )
            active.fileRetirement.retire { active.codec.stop() }?.let(active::noteFailure)
            active.fileRetirement.retire { active.codec.release() }?.let(active::noteFailure)
            active.fileRetirement.retire { active.codecSurface.release() }?.let(active::noteFailure)
            active.fileRetirement.retire { active.outputDescriptor.close() }?.let(active::noteFailure)
            active.fileRetirement.finish()
            val evidence = if (success && active.failure == null) {
                OpenCineLogRecordingEvidence(
                    encodedProgress = active.encodedProgress(),
                    avTiming = active.captureEpoch?.report(timestampNormalizer.videoOriginUs, timestampNormalizer.audioOriginUs, audioEncoderDelayFrames)
                        ?.let { report -> active.audio?.let { audio -> report.copy(submittedPcmFrames = audio.submittedPcmFrames,
                            encodedAudioPackets = active.audioPackets, aacCalibration = audio.calibration,
                            audioDrainPaddingFrames = audio.drainPaddingFrames, audioSourceWindow = audioSourceWindow,
                            audioPresentationOffsetUs = active.captureEpoch.offsetUs(false)) } ?: report.copy(audioStorage = "SEPARATE_LOSSLESS") },
                    sourcePath = sourcePath,
                    sourceDynamicRange = sourcePath.dynamicRange,
                    sourceColorSpace = sourcePath.colorSpace,
                    sourceTransfer = sourcePath.transfer,
                    sourcePrecision = sourcePath.sourcePrecision,
                    recordingLut = active.lutEvidence,
                    curve = if (active.recordingLut != null) "LUT_BAKED_SDR" else "OCLog2",
                    curveVersion = if (active.recordingLut != null) "1" else "2.0",
                    gamut = if (active.recordingLut != null) "BT.709" else "BT.2020",
                    range = if (active.recordingLut != null) "limited" else "full",
                    codecMime = if (passthroughSdr) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC,
                    codecProfile = if (passthroughSdr) "AVC_${active.candidate.profile}" else "Main10",
                    eglRenderTargetBits = if (passthroughSdr) 8 else 10,
                    codecName = active.candidate.codecName,
                    targetFps = targetFps,
                    sourceDataSpace = lastSourceDataSpace,
                    transformSha256 = transformSha256(sourcePath),
                    encodedFrames = active.frames,
                    firstPtsUs = active.firstPtsUs,
                    lastPtsUs = active.lastPtsUs,
                    maxVideoPtsGapUs = active.maxVideoPtsGapUs,
                    videoPtsGapsOverThreshold = active.videoPtsGapsOverThreshold,
                    geometryMode = active.geometry.mode,
                    deviceOrientationDegrees = active.geometry.deviceOrientationDegrees,
                    pixelRotationDegrees = active.geometry.pixelRotationDegrees,
                    rendererRotationDegrees = active.geometry.rendererRotationDegrees,
                    containerRotationDegrees = active.geometry.containerRotationDegrees,
                    encodedWidth = active.geometry.encodedSize.width,
                    encodedHeight = active.geometry.encodedSize.height,
                    displayWidth = active.geometry.displaySize.width,
                    displayHeight = active.geometry.displaySize.height,
                    pixelAspectRatioWidth = active.geometry.pixelAspectRatioWidth,
                    pixelAspectRatioHeight = active.geometry.pixelAspectRatioHeight,
                )
            } else null
            if (recording === active) recording = null
            active.onStopped(success && active.failure == null, evidence)
        }
    }

    private fun destroyPreviewSurface() {
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            if (EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == previewEglSurface ||
                EGL14.eglGetCurrentSurface(EGL14.EGL_READ) == previewEglSurface) {
                check(makeCurrent(pbuffer)) { "Operator EGL window could not be unbound." }
            }
            check(EGL14.eglDestroySurface(display, previewEglSurface)) { "Operator EGL window did not retire." }
            previewEglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun makeCurrent(surface: EGLSurface): Boolean = EGL14.eglMakeCurrent(
        display,
        surface,
        surface,
        if (surface == previewEglSurface) previewContext else context,
    )

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            try {
                GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader)
                val status = IntArray(1); GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
                check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(shader) }
                return shader
            } catch (failure: Throwable) { GLES30.glDeleteShader(shader); throw failure }
        }
        var vertex = 0; var fragment = 0; var linked = 0
        try {
            vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
            fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            linked = GLES30.glCreateProgram()
            GLES30.glAttachShader(linked, vertex); GLES30.glAttachShader(linked, fragment); GLES30.glLinkProgram(linked)
            val status = IntArray(1); GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(linked) }
            return linked
        } catch (failure: Throwable) {
            if (linked != 0) GLES30.glDeleteProgram(linked)
            throw failure
        } finally {
            if (vertex != 0) GLES30.glDeleteShader(vertex)
            if (fragment != 0) GLES30.glDeleteShader(fragment)
        }
    }

    private fun reportGlFailure(failure: Throwable) {
        recording?.let { active ->
            active.failure = failure
            active.acceptFrames.set(false)
            stopRecording()
        }
        onFailure("log-gpu-pipeline-failed", failure.message ?: "The OCLog GPU pipeline failed.")
    }

    private fun reportPreviewFailure(failure: Throwable) {
        operatorLut?.let { reportOperatorLut(OperatorLutStatus(it.cube.sha256, OperatorLutState.WAITING_FOR_GPU, failure.message, monitorLutIdentity(it))) }
        previewNativeSurface = null
        runCatching { destroyPreviewSurface() }
        runCatching { onPreviewLost?.invoke(failure.message ?: "The operator preview window was lost.") }
    }

    private fun <T> callOnGlThread(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        val task = FutureTask(block)
        check(handler.post(task)) { "OCLog GL thread is closed." }
        return task.get(GL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** Defensive completion view: callers cannot complete the pipeline's owning future. */
    fun closeAsync(): CompletableFuture<Unit> {
        close()
        return retirement.thenCombine(recordingFileRetirement()) { _, _ -> Unit }
    }

    override fun close() {
        synchronized(recordingFileLock) { if (!closed.compareAndSet(false, true)) return }
        previewRequestGeneration.incrementAndGet()
        recordingPreparation.get()?.cancel()
        val accepted = handler.post {
            val active = recording
            if (active != null) stopRecording()
            Thread({
                joinOwnedWorker(active?.drainThread)
                val posted = handler.post {
                    try {
                    if (pbuffer != EGL14.EGL_NO_SURFACE) check(makeCurrent(pbuffer))
                    retireSubjectOutput()
                    destroyPreviewSurface()
                    if (encoderEglSurface != EGL14.EGL_NO_SURFACE) check(EGL14.eglDestroySurface(display, encoderEglSurface))
                    surfaceTexture?.setOnFrameAvailableListener(null)
                    surfaceTexture?.release()
                    inputSurface?.release()
                    if (operatorLutProgram != 0) GLES30.glDeleteProgram(operatorLutProgram)
                    if (operatorLutTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(operatorLutTexture), 0)
                    if (subjectLutTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(subjectLutTexture), 0)
                    releaseRecordingLutTexture()
                    if (program != 0) GLES30.glDeleteProgram(program)
                    quad?.delete()
                    if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                    if (display != EGL14.EGL_NO_DISPLAY) check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
                    if (pbuffer != EGL14.EGL_NO_SURFACE) check(EGL14.eglDestroySurface(display, pbuffer))
                    if (previewContext != EGL14.EGL_NO_CONTEXT) check(EGL14.eglDestroyContext(display, previewContext))
                    if (context != EGL14.EGL_NO_CONTEXT) check(EGL14.eglDestroyContext(display, context))
                    check(EGL14.eglReleaseThread())
                    if (display != EGL14.EGL_NO_DISPLAY) {
                        // A stuck auxiliary swap may outlive the camera worker. Do not tear its EGL
                        // display down underneath it, and do not join it on the recording path.
                        val closingDisplay = display
                        java.util.concurrent.CompletableFuture.allOf(*subjectRetirements.toTypedArray())
                            .whenComplete { _, _ -> GpuEglDisplayLease.release(closingDisplay) }
                    }

                        retirement.complete(Unit)
                    } catch (failure: Throwable) { retirement.completeExceptionally(failure) }
                    finally { thread.quitSafely() }
                }
                if (!posted) retirement.completeExceptionally(IllegalStateException("GL pipeline retirement task was rejected"))
            }, "OpenCineLogRetirement").apply { isDaemon = true; start() }
        }
        if (!accepted) retirement.completeExceptionally(IllegalStateException("GL close request was rejected"))
    }

    private class EmbeddedAac(
        val codec: MediaCodec,
        private val audioRecord: AudioRecord,
        private val sampleRateHz: Int,
        private val channels: Int,
        private val hardwareAgc: AutomaticGainControl?,
        private val effects: List<AudioEffect>,
        private val effectReaders: List<AudioEffectObservationReader>,
        private val hardwareAgcReader: AudioEffectObservationReader,
        private val softAgc: SoftAgc?,
        private val onAudioLevel: ((AudioLevelSnapshot) -> Unit)?,
        private val recordingGain: DigitalRecordingGain,
        private val listeningSink: PcmListeningSink?,
        private val captureEpoch: CaptureEpochClock,
        val calibration: AacCodecCalibration,
    ) : AutoCloseable {
        private val stopRequested = AtomicBoolean(false)
        private val stopDeadline = CodecStopDeadline(CODEC_STOP_TIMEOUT_NS)
        @Volatile private var stopThread: Thread? = null
        @Volatile private var stopFailure: Throwable? = null
        private var codecStarted = false
        private val levelMeter = AudioLevelMeter(PcmMeterEncoding.PCM_16, channels, sampleRateHz = audioRecord.sampleRate)
        @Volatile private var feederThread: Thread? = null
        @Volatile var submittedPcmFrames = 0L
            private set
        @Volatile var drainPaddingFrames = 0L
            private set

        fun start(onFailure: (Throwable) -> Unit) {
            codec.start()
            codecStarted = true
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter RECORDING state."
            }
            val startedAtNs = android.os.SystemClock.elapsedRealtimeNanos()
            val pcmEpoch = PcmCaptureEpoch(sampleRateHz)
            val timestamp = android.media.AudioTimestamp()
            feederThread = Thread({
                var submittedFrames = 0L
                var capturedFrames = 0L
                var inFlightReadFrames = 0
                val frameBytes = PCM_BYTES_PER_SAMPLE * channels
                val buffer = ByteBuffer.allocateDirect(calibration.config.maxInputBytes / frameBytes * frameBytes)
                try {
                    while (!stopRequested.get()) {
                        buffer.clear()
                        val read = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                        if (read <= 0) {
                            if (stopRequested.get()) break
                            if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
                                error("AudioRecord read failed with code $read.")
                            }
                            continue
                        }
                        check(read % (PCM_BYTES_PER_SAMPLE * channels) == 0) { "AudioRecord returned a partial PCM frame" }
                        inFlightReadFrames = read / frameBytes
                        val stampResult = audioRecord.getTimestamp(timestamp, android.media.AudioTimestamp.TIMEBASE_BOOTTIME)
                        if (stampResult == AudioRecord.SUCCESS) {
                            captureEpoch.audioInput(pcmEpoch.observe(timestamp.framePosition, timestamp.nanoTime))
                        } else if (pcmEpoch.current() == null && android.os.SystemClock.elapsedRealtimeNanos() - startedAtNs >= ESTIMATED_AUDIO_ANCHOR_DELAY_NS) {
                            captureEpoch.audioInput(pcmEpoch.estimate(startedAtNs))
                        }
                        val platformAgc = hardwareAgcReader.read()
                        requireExclusiveAgcObservation(platformAgc, hardwareAgc != null, recordingGain.enabled, softAgc != null)
                        val appliedAgc = if (softAgc == null) platformAgc else platformAgc.copy(
                            state = AudioEffectState.ENABLED, implementation = AudioEffectImplementation.SOFTWARE, hasControl = null)
                        val observedEffects = AudioEffectsSnapshot(effectReaders[0].read(), appliedAgc, effectReaders[2].read())
                        levelMeter.observeInput(buffer, read)
                        softAgc?.processPcm16(buffer, read)
                        recordingGain.process(buffer, read, PcmMeterEncoding.PCM_16, channels)
                        listeningSink.offerListening(buffer, read, PcmMeterEncoding.PCM_16, sampleRateHz, channels)
                        levelMeter.analyze(buffer, read, android.os.SystemClock.elapsedRealtime())
                            ?.let { onAudioLevel?.invoke(it.copy(appliedRecordingGain = recordingGain,
                                effects = observedEffects)) }
                        val readCount = read / frameBytes
                        val spans = captureEpoch.selectAudio(capturedFrames, readCount)
                        capturedFrames = Math.addExact(capturedFrames, readCount.toLong())
                        inFlightReadFrames = 0
                        val retainedBytes = compactPcm16(buffer, read, channels, spans)
                        var sent = 0
                        while (sent < retainedBytes) {
                            if (stopRequested.get()) stopDeadline.check()
                            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                            if (index < 0) continue
                            val input = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                            val count = minOf(input.capacity() / frameBytes, (retainedBytes - sent) / frameBytes) * frameBytes
                            check(count > 0)
                            input.put(buffer.duplicate().apply { position(sent); limit(sent + count) })
                            codec.queueInputBuffer(index, 0, count, pcmFrameDurationNs(submittedFrames, sampleRateHz) / 1000, 0)
                            sent += count
                            submittedFrames = Math.addExact(submittedFrames, (count / frameBytes).toLong())
                            submittedPcmFrames = submittedFrames
                        }
                    }
                } catch (failure: Throwable) {
                    if (!stopRequested.get()) onFailure(failure)
                    // Post-stop failures stay non-fatal, but an unselected read may precede the sealed stop
                    // boundary; make the resulting tail shortfall observable instead of silent.
                    else android.util.Log.w(LOG_TAG, "AAC feeder failure after stop request; $inFlightReadFrames read PCM frames " +
                        "were not selected (captured=$capturedFrames, submitted=$submittedFrames).", failure)
                } finally {
                    if (pcmEpoch.current() == null) captureEpoch.audioInput(pcmEpoch.estimate(startedAtNs))
                    stopDeadline.begin()
                    runCatching {
                        // Explicit codec drain samples are not captured PCM and never enter the meter or capture clock.
                        while (submittedFrames > 0 && drainPaddingFrames < calibration.drainPaddingFrames) {
                            stopDeadline.check()
                            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                            if (index < 0) continue
                            val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                            val frameBytes = PCM_BYTES_PER_SAMPLE * channels
                            val frames = minOf(buffer.capacity() / frameBytes, (calibration.drainPaddingFrames - drainPaddingFrames).toInt())
                            check(frames > 0)
                            buffer.put(ByteArray(frames * frameBytes))
                            codec.queueInputBuffer(index, 0, frames * frameBytes,
                                pcmFrameDurationNs(Math.addExact(submittedFrames, drainPaddingFrames), sampleRateHz) / 1000, 0)
                            drainPaddingFrames += frames
                        }
                        var eosIndex: Int
                        do { stopDeadline.check(); eosIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US) } while (eosIndex < 0)
                        codec.queueInputBuffer(
                            eosIndex,
                            0,
                            0,
                            pcmFrameDurationNs(Math.addExact(submittedFrames, drainPaddingFrames), sampleRateHz) / 1000,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    }.onFailure(onFailure)
                }
            }, "OpenCineCamAacFeeder").apply { start() }
        }

        @Synchronized fun requestStop() {
            if (!stopRequested.compareAndSet(false, true)) return
            stopDeadline.begin()
            stopThread = Thread({
                try { if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop() }
                catch (failure: Throwable) { stopFailure = failure }
            }, "OpenCineCamAacStop").apply { isDaemon = true; start() }
        }

        override fun close() {
            requestStop()
            joinOwnedWorker(stopThread)
            joinOwnedWorker(feederThread)
            var failure = stopFailure
            fun release(action: () -> Unit) {
                try { action() } catch (problem: Throwable) {
                    val first = failure
                    if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
                }
            }
            effects.forEach { effect -> release { effect.release() } }
            release { audioRecord.release() }
            release { if (codecStarted) codec.stop() }
            release { codec.release() }
            failure?.let { throw it }
        }

        companion object {
            private const val PCM_BYTES_PER_SAMPLE = 2

            private fun channelMask(config: Camera2EmbeddedAudioConfig) =
                if (config.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

            private fun minimumBufferBytes(config: Camera2EmbeddedAudioConfig): Int =
                AudioRecord.getMinBufferSize(config.sampleRateHz, channelMask(config), AudioFormat.ENCODING_PCM_16BIT)
                    .also { check(it > 0) { "The requested PCM input is unsupported." } }

            private fun requireRecordAudio(context: Context) {
                check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "RECORD_AUDIO permission is required for embedded AAC."
                }
            }

            /** Opens no microphone and touches no GL state; callers run it off the GL looper. */
            fun calibrate(context: Context, config: Camera2EmbeddedAudioConfig, isCancelled: () -> Boolean): AacCodecCalibration {
                requireRecordAudio(context)
                val calibrationConfig = AacCodecCalibrator.selectConfig(config.sampleRateHz, config.channels, config.bitrateBps, minimumBufferBytes(config) * 2)
                return AacCodecCalibrator.qualify(calibrationConfig, isCancelled)
            }

            @SuppressLint("MissingPermission")
            fun create(context: Context, config: Camera2EmbeddedAudioConfig, calibration: AacCodecCalibration, captureEpoch: CaptureEpochClock,
                onRetirementFailure: (Throwable) -> Unit, isCancelled: () -> Boolean): EmbeddedAac {
                requireRecordAudio(context)
                val channelMask = channelMask(config)
                val minimum = minimumBufferBytes(config)
                check(calibration.config.sampleRateHz == config.sampleRateHz && calibration.config.channels == config.channels &&
                    calibration.config.bitrateBps == config.bitrateBps && calibration.config.maxInputBytes == minimum * 2) {
                    "AAC calibration does not match the requested audio configuration"
                }
                check(!isCancelled()) { "Recording preparation was cancelled" }
                val audioRecord = AudioRecord.Builder()
                    .setAudioSource(config.source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(config.sampleRateHz)
                            .setChannelMask(channelMask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(minimum * 2)
                    .build()
                var hardwareAgc: AutomaticGainControl? = null
                val effects = mutableListOf<AudioEffect>()
                try {
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed." }
                check(audioRecord.sampleRate == config.sampleRateHz) { "AudioRecord sample rate differs from the configured AAC rate" }
                fun observeOptional(available: () -> Boolean, create: () -> AudioEffect?, requested: Boolean): Pair<AudioEffect?, AudioEffectObservationReader> {
                    var failed = false
                    val supported = try { available() } catch (_: Throwable) { failed = true; null }
                    val effect = try { create() } catch (_: Throwable) { failed = true; null }
                    if (effect != null) {
                        effects += effect
                        try {
                            val status = effect.setEnabled(requested)
                            if (status != AudioEffect.SUCCESS || effect.enabled != requested) failed = true
                        } catch (_: Throwable) { failed = true }
                    }
                    return effect to AudioEffectObservationReader(requested, effect, supported, failed)
                }
                val ns = observeOptional(NoiseSuppressor::isAvailable, { NoiseSuppressor.create(audioRecord.audioSessionId) }, config.enableNoiseSuppressor)
                val aec = observeOptional(AcousticEchoCanceler::isAvailable, { AcousticEchoCanceler.create(audioRecord.audioSessionId) }, config.enableAcousticEchoCanceler)
                val agc = if (config.recordingGain.enabled) {
                    val supported = AutomaticGainControl.isAvailable()
                    createDisabledManualAgc(audioRecord.audioSessionId) { hardwareAgc = it; effects += it }
                    hardwareAgc to AudioEffectObservationReader(config.enableAutomaticGainControl, hardwareAgc, supported)
                } else observeOptional(AutomaticGainControl::isAvailable, { AutomaticGainControl.create(audioRecord.audioSessionId) }, config.enableAutomaticGainControl)
                hardwareAgc = agc.first as AutomaticGainControl?
                val initialAgc = agc.second.read()
                val softwareAgc = if (!config.recordingGain.enabled && config.enableAutomaticGainControl &&
                    (hardwareAgc == null || initialAgc.state == AudioEffectState.DISABLED)) SoftAgc(config.sampleRateHz, config.channels) else null
                config.preferredInputDeviceId?.let { requestedId ->
                        val device = context.getSystemService(AudioManager::class.java)
                            .getDevices(AudioManager.GET_DEVICES_INPUTS)
                            .firstOrNull { it.id == requestedId }
                            ?: error("Requested audio input $requestedId is no longer connected.")
                        check(audioRecord.setPreferredDevice(device)) { "AudioRecord rejected audio input $requestedId." }
                    }
                    val codec = MediaCodec.createByCodecName(calibration.config.codecName)
                    try {
                        codec.configure(
                            AacCodecCalibrator.encoderFormat(calibration.config),
                            null,
                            null,
                            MediaCodec.CONFIGURE_FLAG_ENCODE,
                        )
                        return EmbeddedAac(codec, audioRecord, config.sampleRateHz, config.channels, hardwareAgc,
                            effects, listOf(ns.second, agc.second, aec.second), agc.second, softwareAgc, config.onAudioLevel, config.recordingGain, config.listeningSink, captureEpoch, calibration)
                    } catch (failure: Throwable) {
                        try { codec.release() } catch (cleanup: Throwable) {
                            onRetirementFailure(cleanup); if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                        throw failure
                    }
                } catch (failure: Throwable) {
                    for (release in effects.map { effect -> { effect.release() } } + { audioRecord.release() }) {
                        try { release() } catch (cleanup: Throwable) {
                            onRetirementFailure(cleanup); if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                    }
                    throw failure
                }
            }
        }
    }

    private class Recording(
        val outputDescriptor: ParcelFileDescriptor,
        val codec: MediaCodec,
        val codecSurface: Surface,
        val muxer: MediaMuxer,
        val candidate: OpenCineLogEncoderCandidate,
        val geometry: RecordingGeometry,
        val audio: EmbeddedAac?,
        val captureEpoch: CaptureEpochClock?,
        val minimumSensorTimestampNs: Long?,
        val timelapse: TimelapseTimeline?,
        val projectTimeline: ProjectFrameTimeline?,
        val onTimelapseProgress: ((TimelapseProgress) -> Unit)?,
        val onTimelapsePauseChanged: ((TimelapsePauseStatus) -> Unit)?,
        val onEncodedProgress: ((EncodedRecordingProgress) -> Unit)?,
        val onStopped: (Boolean, OpenCineLogRecordingEvidence?) -> Unit,
        val fileRetirement: RecordingFileRetirement,
        val recordingLut: MonitorLut?,
        val onRecordingLutApplied: ((BakedLutEvidence) -> Unit)?,
    ) {
        val lutEvidence = recordingLut?.let(::BakedLutEvidence)
        var lutApplied = false
        val takeId = takeIds.incrementAndGet()
        fun encodedProgress() = EncodedRecordingProgress(takeId, frames, firstPtsUs, lastPtsUs)
        fun reportEncodedProgress() { onEncodedProgress?.invoke(encodedProgress()) }
        // First reported after codec.start/onStarted: native preparation is not active capture time.
        // Every access stays on the GL owner, including pause and terminal stop.
        val pauseClock by lazy(LazyThreadSafetyMode.NONE) {
            if (timelapse != null || captureEpoch != null) TimelapsePauseClock(takeId, android.os.SystemClock::elapsedRealtime) else null
        }
        var pauseStatusPublished = false
        var videoRegressionLogged = false
        fun reportPauseStatus(finished: Boolean = false) {
            val clock = pauseClock ?: return
            if (timelapse == null && captureEpoch?.pauseAvailable() != true) return
            pauseStatusPublished = true
            val status = if (finished) clock.finish() else clock.snapshot()
            onTimelapsePauseChanged?.invoke(status.copy(submittedFrames = timelapse?.selectedFrames ?: frames,
                missedIntervals = timelapse?.missedIntervals ?: 0,
                policy = if (timelapse != null) "TIMELAPSE_COMMAND_CLOCK" else "SHARED_CAPTURE_SAMPLE_WINDOWS"))
        }
        val stopRequested = AtomicBoolean(false)
        val acceptFrames = AtomicBoolean(true)
        @Volatile var failure: Throwable? = null
        @Synchronized fun noteFailure(problem: Throwable) {
            val first = failure
            if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
        }
        @Volatile var drainThread: Thread? = null
        @Volatile var frames = 0L
        var audioPackets = 0L
        var firstPtsUs: Long? = null
        var lastPtsUs: Long? = null
        var maxVideoPtsGapUs: Long? = null
        var videoPtsGapsOverThreshold = 0L
    }

    private data class PendingMuxSample(
        val video: Boolean,
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    companion object {
        private val takeIds = java.util.concurrent.atomic.AtomicLong()
        private const val CODEC_TIMEOUT_US = 20_000L
        private const val CODEC_STOP_TIMEOUT_NS = 5_000_000_000L
        private const val LOG_TAG = "OpenCineLogGpuPipeline"
        /** Without an AudioTimestamp the PCM origin is estimated on the first read completing after this delay. */
        internal const val ESTIMATED_AUDIO_ANCHOR_DELAY_NS = 500_000_000L
        internal const val MIN_PENDING_MUX_SAMPLES = 64

        /**
         * Pre-start mux queue bound. Samples queue until both formats and both capture anchors exist; with no
         * AudioTimestamp the audio anchor arrives on the first PCM read completing after the estimate delay, i.e.
         * up to delay + one read period after the feeder starts. Allow that window plus 0.5 s of drain/encoder
         * latency at the video rate and the AAC packet rate (1024 PCM frames per packet). Examples: 60 fps,
         * 48 kHz, 80 ms reads -> 1.08 s -> 65 + 51 = 116; 30 fps -> 84; video-only keeps the historical 64.
         */
        internal fun maxPendingMuxSamples(videoFps: Int, audioRateHz: Int?, audioReadFrames: Int?): Int {
            // Non-throwing: evaluated before drain()'s cleanup scope.
            if (audioRateHz == null || audioReadFrames == null || audioRateHz <= 0 || audioReadFrames <= 0) return MIN_PENDING_MUX_SAMPLES
            val windowNs = ESTIMATED_AUDIO_ANCHOR_DELAY_NS + pcmFrameDurationNs(audioReadFrames.toLong(), audioRateHz) + 500_000_000L
            fun perWindow(ratePerSecond: Double) = kotlin.math.ceil(ratePerSecond * windowNs / 1_000_000_000.0).toInt()
            return maxOf(MIN_PENDING_MUX_SAMPLES, perWindow(videoFps.coerceAtLeast(1).toDouble()) + perWindow(audioRateHz / 1024.0))
        }
        private const val GL_CALL_TIMEOUT_SECONDS = 8L
        private const val OUTPUT_OCLOG = 0
        private const val OUTPUT_VIEW_ASSIST = 1
        private const val OUTPUT_FLAT_MONITOR = 2
        private const val ANALYSIS_WIDTH = 160
        private const val ANALYSIS_HEIGHT = 90
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_LINEAR_EXT = 0x333F

        fun findEncoder(size: Size, targetFps: Int): OpenCineLogEncoderCandidate? =
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence()
                .filter { it.isEncoder && !it.isAlias && it.isHardwareAccelerated }
                .filter { it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } }
                .sortedBy { it.name }
                .mapNotNull { info ->
                    val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC) }.getOrNull()
                        ?: return@mapNotNull null
                    if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) return@mapNotNull null
                    val profile = caps.profileLevels.map { it.profile }.firstOrNull {
                        it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    } ?: return@mapNotNull null
                    if (caps.videoCapabilities?.areSizeAndRateSupported(size.width, size.height, targetFps.toDouble()) != true) {
                        return@mapNotNull null
                    }
                    OpenCineLogEncoderCandidate(info.name, profile, size)
                }
                .firstOrNull()

        fun findAvcEncoder(size: Size, targetFps: Int, allowSoftware: Boolean = false): OpenCineLogEncoderCandidate? =
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence()
                .filter { it.isEncoder && !it.isAlias && (allowSoftware || it.isHardwareAccelerated) }
                .filter { it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                .sortedWith(compareByDescending<MediaCodecInfo> { it.isHardwareAccelerated }.thenBy { it.name })
                .mapNotNull { info ->
                    val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull()
                        ?: return@mapNotNull null
                    if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) return@mapNotNull null
                    val profile = caps.profileLevels.map { it.profile }.firstOrNull {
                        it == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    } ?: caps.profileLevels.maxOfOrNull { it.profile } ?: return@mapNotNull null
                    if (caps.videoCapabilities?.areSizeAndRateSupported(size.width, size.height, targetFps.toDouble()) != true) {
                        return@mapNotNull null
                    }
                    OpenCineLogEncoderCandidate(info.name, profile, size, info.isHardwareAccelerated,
                        caps.profileLevels.filter { it.profile == profile }.maxOf { it.level })
                }
                .firstOrNull()

        private val VERTEX_SHADER = """
            #version 300 es
            uniform mat4 uTextureMatrix;
            uniform vec2 uPositionScale;
            uniform int uPreviewRotation;
            uniform int uMirrorPreview;
            layout(location = 0) in vec2 aPosition;
            out vec2 vTexCoord;
            void main() {
                vec2 position = aPosition;
                if (uPreviewRotation == 1) {
                    position = vec2(position.y, -position.x);
                } else if (uPreviewRotation == 2) {
                    position = -position;
                } else if (uPreviewRotation == 3) {
                    position = vec2(-position.y, position.x);
                }
                if (uMirrorPreview == 1) position.x = -position.x;
                gl_Position = vec4(position * uPositionScale, 0.0, 1.0);
                vTexCoord = (uTextureMatrix * vec4((aPosition + 1.0) * 0.5, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val PASSTHROUGH_FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform samplerExternalOES uTexture;
            uniform int uOutputMode;
            in vec2 vTexCoord;
            out vec4 outColor;
            void main() {
                outColor = texture(uTexture, vTexCoord);
            }
        """.trimIndent()

        // The exact source is hashed into every clip sidecar. Changing it is a pipeline-version change.
        private val HLG_FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform samplerExternalOES uTexture;
            uniform int uOutputMode;
            in vec2 vTexCoord;
            out vec4 outColor;
            const float HLG_A = 0.17883277;
            const float HLG_B = 0.28466892;
            const float HLG_C = 0.55991073;
            vec3 inverseHlg(vec3 e) {
                bvec3 low = lessThanEqual(e, vec3(0.5));
                vec3 lowPart = (e * e) / 3.0;
                vec3 highPart = (exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0;
                return mix(highPart, lowPart, low);
            }
            vec3 encodeOcLog2(vec3 linearBt2020) {
                return vec3(0.10) + vec3(0.80) * log(vec3(1.0) + 50.0 * clamp(linearBt2020, 0.0, 1.0)) / log(vec3(51.0));
            }
            vec3 decodeOcLog2(vec3 code) {
                vec3 normalized = clamp((code - vec3(0.10)) / vec3(0.80), 0.0, 1.0);
                return (pow(vec3(51.0), normalized) - 1.0) / 50.0;
            }
            vec3 bt2020ToBt709(vec3 c) {
                return mat3(
                    1.660491, -0.124550, -0.018151,
                   -0.587641,  1.132900, -0.100579,
                   -0.072850, -0.008349,  1.118730
                ) * c;
            }
            vec3 rec709Oetf(vec3 x) {
                x = clamp(x, 0.0, 1.0);
                bvec3 low = lessThan(x, vec3(0.018));
                vec3 lowPart = 4.5 * x;
                vec3 highPart = 1.099 * pow(x, vec3(0.45)) - 0.099;
                return mix(highPart, lowPart, low);
            }
            void main() {
                vec3 sceneLinearBt2020 = inverseHlg(texture(uTexture, vTexCoord).rgb);
                vec3 ocLog = encodeOcLog2(sceneLinearBt2020);
                vec3 displayLinear = max(bt2020ToBt709(decodeOcLog2(ocLog)), vec3(0.0));
                vec3 monitored = rec709Oetf(displayLinear);
                float luma = dot(displayLinear, vec3(0.2126, 0.7152, 0.0722));
                vec3 gamutCompressed = mix(vec3(luma), displayLinear, 0.68);
                vec3 flatMonitor = encodeOcLog2(gamutCompressed);
                outColor = vec4(uOutputMode == 1 ? monitored : (uOutputMode == 2 ? flatMonitor : ocLog), 1.0);
            }
        """.trimIndent()

        private val SDR_FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform samplerExternalOES uTexture;
            uniform int uOutputMode;
            in vec2 vTexCoord;
            out vec4 outColor;
            vec3 inverseRec709(vec3 e) {
                bvec3 low = lessThan(e, vec3(0.081));
                vec3 lowPart = e / 4.5;
                vec3 highPart = pow((e + 0.099) / 1.099, vec3(1.0 / 0.45));
                return mix(highPart, lowPart, low);
            }
            vec3 bt709ToBt2020(vec3 c) {
                return mat3(
                    0.627404, 0.069097, 0.016391,
                    0.329283, 0.919540, 0.088013,
                    0.043313, 0.011362, 0.895595
                ) * c;
            }
            vec3 encodeOcLog2(vec3 linearBt2020) {
                return vec3(0.10) + vec3(0.80) * log(vec3(1.0) + 50.0 * clamp(linearBt2020, 0.0, 1.0)) / log(vec3(51.0));
            }
            vec3 decodeOcLog2(vec3 code) {
                vec3 normalized = clamp((code - vec3(0.10)) / vec3(0.80), 0.0, 1.0);
                return (pow(vec3(51.0), normalized) - 1.0) / 50.0;
            }
            vec3 bt2020ToBt709(vec3 c) {
                return mat3(
                    1.660491, -0.124550, -0.018151,
                   -0.587641,  1.132900, -0.100579,
                   -0.072850, -0.008349,  1.118730
                ) * c;
            }
            vec3 rec709Oetf(vec3 x) {
                x = clamp(x, 0.0, 1.0);
                bvec3 low = lessThan(x, vec3(0.018));
                vec3 lowPart = 4.5 * x;
                vec3 highPart = 1.099 * pow(x, vec3(0.45)) - 0.099;
                return mix(highPart, lowPart, low);
            }
            void main() {
                vec3 sceneLinearBt2020 = bt709ToBt2020(inverseRec709(texture(uTexture, vTexCoord).rgb));
                vec3 ocLog = encodeOcLog2(sceneLinearBt2020);
                vec3 displayLinear = max(bt2020ToBt709(decodeOcLog2(ocLog)), vec3(0.0));
                vec3 monitored = rec709Oetf(displayLinear);
                float luma = dot(displayLinear, vec3(0.2126, 0.7152, 0.0722));
                vec3 gamutCompressed = mix(vec3(luma), displayLinear, 0.68);
                vec3 flatMonitor = encodeOcLog2(gamutCompressed);
                outColor = vec4(uOutputMode == 1 ? monitored : (uOutputMode == 2 ? flatMonitor : ocLog), 1.0);
            }
        """.trimIndent()

        private fun fragmentShader(sourcePath: OpenCineLogSourcePath): String = when (sourcePath) {
            OpenCineLogSourcePath.HLG10_BT2020 -> HLG_FRAGMENT_SHADER
            OpenCineLogSourcePath.SDR_BT709_ISP -> SDR_FRAGMENT_SHADER
        }

        fun transformSha256(sourcePath: OpenCineLogSourcePath): String = MessageDigest.getInstance("SHA-256")
            .digest(fragmentShader(sourcePath).toByteArray())
            .joinToString("") { "%02x".format(it) }

        /** Backward-compatible identity of the qualified HLG transform. */
        val TRANSFORM_SHA256: String = transformSha256(OpenCineLogSourcePath.HLG10_BT2020)
        val SDR_TRANSFORM_SHA256: String = transformSha256(OpenCineLogSourcePath.SDR_BT709_ISP)
    }
}
