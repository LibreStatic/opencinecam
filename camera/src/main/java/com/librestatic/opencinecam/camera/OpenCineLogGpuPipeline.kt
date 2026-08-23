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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque

data class OpenCineLogEncoderCandidate(
    val codecName: String,
    val profile: Int,
    val size: Size,
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
)

internal data class OpenCineLogPreviewGeometry(
    val positionRotationDegrees: Int,
    val scaleX: Float,
    val scaleY: Float,
    val mirrorHorizontally: Boolean,
)

/**
 * Surface video encoders and AAC encoders do not necessarily preserve the same timestamp epoch.
 * In particular, some devices keep the SurfaceTexture/boottime epoch for video while its AAC codec
 * rebases the first input buffer to zero. MediaMuxer interprets those values on one timeline, so
 * writing the raw epochs makes a short clip appear to be several hours long. Each encoded track
 * is therefore rebased to its own first emitted sample before it reaches the container.
 */
internal class MuxTimestampNormalizer {
    private var videoOriginUs: Long? = null
    private var audioOriginUs: Long? = null
    private var lastVideoUs = 0L
    private var lastAudioUs = 0L

    fun normalize(video: Boolean, presentationTimeUs: Long): Long {
        val origin = if (video) {
            videoOriginUs ?: presentationTimeUs.also { videoOriginUs = it }
        } else {
            audioOriginUs ?: presentationTimeUs.also { audioOriginUs = it }
        }
        val rebased = (presentationTimeUs - origin).coerceAtLeast(0L)
        return if (video) {
            rebased.coerceAtLeast(lastVideoUs).also { lastVideoUs = it }
        } else {
            rebased.coerceAtLeast(lastAudioUs).also { lastAudioUs = it }
        }
    }
}

/** Converts a small recorded-signal RGBA readback into the same bounded scope model as YUV preview. */
internal fun analyzeRgbaFrame(
    width: Int,
    height: Int,
    rgba: ByteArray,
    capturedAtElapsedRealtimeMs: Long,
): Camera2Analysis {
    require(width > 0 && height > 0 && rgba.size == width * height * 4)
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
            val luma = ((54 * red + 183 * green + 19 * blue) shr 8).coerceIn(0, 255)
            lumaHistogram[luma * bins / 256]++
            redHistogram[red * bins / 256]++
            greenHistogram[green * bins / 256]++
            blueHistogram[blue * bins / 256]++
            val cell = (displayY * 9 / height).coerceIn(0, 8) * 16 + (x * 16 / width).coerceIn(0, 15)
            counts[cell]++
            if (luma >= 235) zebraHits[cell]++
            if (previous >= 0 && kotlin.math.abs(luma - previous) >= 35) focusHits[cell]++
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
        val contentAspect = contentWidth.toFloat() / contentHeight
        val targetAspect = targetWidth.toFloat() / targetHeight
        val scaleX: Float
        val scaleY: Float
        if (contentAspect > targetAspect) {
            scaleX = 1f
            scaleY = targetAspect / contentAspect
        } else {
            scaleX = contentAspect / targetAspect
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
    private val onAnalysis: ((Camera2Analysis) -> Unit)? = null,
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
    private var textureMatrixLocation = -1
    private var outputModeLocation = -1
    private var positionScaleLocation = -1
    private var previewRotationLocation = -1
    private var mirrorPreviewLocation = -1
    private val textureMatrix = FloatArray(16)
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    @Volatile private var viewAssistEnabled = viewAssist
    @Volatile private var previewDisplayRotationDegrees = displayRotationDegrees
    @Volatile private var recording: Recording? = null
    @Volatile private var lastSourceDataSpace: Int? = null
    private var lastAnalysisAtMs = 0L
    private val closed = AtomicBoolean(false)

    val cameraInputSurface: Surface = callOnGlThread {
        initializeEgl()
        initializeTextureAndProgram()
        preview?.let(::attachPreviewInternal)
        requireNotNull(inputSurface)
    }

    fun setViewAssist(enabled: Boolean) {
        viewAssistEnabled = enabled
    }

    fun attachPreview(surface: Surface, displayRotationDegrees: Int): Boolean {
        if (closed.get() || !surface.isValid) return false
        previewNativeSurface = surface
        handler.post {
            previewDisplayRotationDegrees = displayRotationDegrees
            runCatching { attachPreviewInternal(surface) }.onFailure(::reportGlFailure)
        }
        return true
    }

    fun detachPreview() {
        previewNativeSurface = null
        handler.post { destroyPreviewSurface() }
    }

    fun startRecording(
        output: ParcelFileDescriptor,
        bitrate: Int,
        geometry: RecordingGeometry,
        audio: Camera2EmbeddedAudioConfig? = null,
        onStarted: () -> Unit,
        onStopped: (Boolean, OpenCineLogRecordingEvidence?) -> Unit,
    ): Boolean {
        if (closed.get() || recording != null) return false
        return try {
            callOnGlThread {
                check(geometry.sourceSize.width == size.width && geometry.sourceSize.height == size.height) {
                    "Recording geometry does not match the active source."
                }
                val encodedSize = geometry.encodedSize
                val platformEncodedSize = Size(encodedSize.width, encodedSize.height)
                val mime = if (passthroughSdr) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
                val candidate = (if (passthroughSdr) findAvcEncoder(platformEncodedSize, targetFps) else findEncoder(platformEncodedSize, targetFps))
                    ?: error("No hardware $mime Surface encoder accepts ${encodedSize.width}x${encodedSize.height} at $targetFps fps.")
                val format = MediaFormat.createVideoFormat(mime, encodedSize.width, encodedSize.height).apply {
                    setInteger(MediaFormat.KEY_PROFILE, candidate.profile)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    val rateScaledBitrate = bitrate.toLong() * targetFps / 30
                    setInteger(MediaFormat.KEY_BIT_RATE, rateScaledBitrate.coerceIn(12_000_000L, 200_000_000L).toInt())
                    setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    if (passthroughSdr) {
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
                val codec = MediaCodec.createByCodecName(candidate.codecName)
                var codecSurface: Surface? = null
                var muxer: MediaMuxer? = null
                var embeddedAac: EmbeddedAac? = null
                try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codecSurface = codec.createInputSurface()
                    muxer = MediaMuxer(output.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                        setOrientationHint(geometry.containerRotationDegrees)
                    }
                    embeddedAac = audio?.let {
                        EmbeddedAac.create(requireNotNull(appContext) { "An Android context is required for embedded AAC." }, it)
                    }
                    val eglSurface = createWindowSurface(
                        if (passthroughSdr) requireNotNull(config8) else requireNotNull(config10),
                        codecSurface,
                        if (passthroughSdr) intArrayOf(EGL14.EGL_NONE) else {
                            intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_LINEAR_EXT, EGL14.EGL_NONE)
                        },
                    )
                    check(eglSurface != EGL14.EGL_NO_SURFACE) { "Encoder did not accept the EGL window surface." }
                    encoderEglSurface = eglSurface
                    val active = Recording(codec, codecSurface, muxer, candidate, geometry, embeddedAac, onStopped)
                    recording = active
                    codec.start()
                    embeddedAac?.start { failure ->
                        active.failure = failure
                        stopRecording()
                    }
                    active.drainThread = Thread({ drain(active) }, "OpenCineLogCodecDrain").apply { start() }
                    onStarted()
                } catch (failure: Throwable) {
                    recording = null
                    if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                        runCatching { EGL14.eglDestroySurface(display, encoderEglSurface) }
                        encoderEglSurface = EGL14.EGL_NO_SURFACE
                    }
                    runCatching { embeddedAac?.close() }
                    runCatching { muxer?.release() }
                    runCatching { codecSurface?.release() }
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                    throw failure
                }
            }
            true
        } catch (failure: Throwable) {
            onFailure("log-recording-prepare-failed", failure.message ?: "OCLog recording could not be prepared.")
            false
        }
    }

    fun stopRecording(): Boolean {
        val active = recording ?: return false
        if (!active.stopRequested.compareAndSet(false, true)) return false
        handler.post {
            // No draw may occur after EOS is signalled.
            active.acceptFrames.set(false)
            active.audio?.requestStop()
            runCatching { active.codec.signalEndOfInputStream() }
                .onFailure { active.failure = it }
        }
        return true
    }

    private fun initializeEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL display is unavailable." }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL initialization failed." }
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
            texture.getTransformMatrix(textureMatrix)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lastSourceDataSpace = texture.dataSpace
            }
            val timestampNs = texture.timestamp
            recording?.takeIf { it.acceptFrames.get() }?.let { active ->
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE && makeCurrent(encoderEglSurface)) {
                    draw(
                        outputMode = OUTPUT_OCLOG,
                        width = active.geometry.encodedSize.width,
                        height = active.geometry.encodedSize.height,
                        previewOutput = false,
                        recordingGeometry = active.geometry,
                    )
                    EGLExt.eglPresentationTimeANDROID(display, encoderEglSurface, timestampNs)
                    check(EGL14.eglSwapBuffers(display, encoderEglSurface)) { "Encoder EGL swap failed." }
                }
            }
            renderScopeAnalysisIfDue()
            if (previewNativeSurface?.isValid == true && previewEglSurface != EGL14.EGL_NO_SURFACE && makeCurrent(previewEglSurface)) {
                val width = querySurface(EGL14.EGL_WIDTH).coerceAtLeast(1)
                val height = querySurface(EGL14.EGL_HEIGHT).coerceAtLeast(1)
                draw(
                    if (viewAssistEnabled) OUTPUT_VIEW_ASSIST else OUTPUT_FLAT_MONITOR,
                    width,
                    height,
                    previewOutput = true,
                )
                check(EGL14.eglSwapBuffers(display, previewEglSurface)) { "Preview EGL swap failed." }
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
        if (now - lastAnalysisAtMs < ANALYSIS_PERIOD_MS) return
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
        callback(analyzeRgbaFrame(ANALYSIS_WIDTH, ANALYSIS_HEIGHT, bytes, now))
    }

    private fun draw(
        outputMode: Int,
        width: Int,
        height: Int,
        previewOutput: Boolean,
        recordingGeometry: RecordingGeometry? = null,
    ) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0)
        GLES30.glUniform1i(outputModeLocation, outputMode)
        val geometry = if (previewOutput) {
            OpenCineLogPreviewGeometryCalculator.calculate(
                sourceWidth = size.width,
                sourceHeight = size.height,
                targetWidth = width,
                targetHeight = height,
                sensorOrientationDegrees = sensorOrientationDegrees,
                displayRotationDegrees = previewDisplayRotationDegrees,
                frontFacing = frontFacing,
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
        GLES30.glUniform2f(positionScaleLocation, geometry.scaleX, geometry.scaleY)
        GLES30.glUniform1i(previewRotationLocation, geometry.positionRotationDegrees / 90)
        GLES30.glUniform1i(mirrorPreviewLocation, if (geometry.mirrorHorizontally) 1 else 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "OCLog shader failed with GL error 0x${error.toString(16)}." }
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
        val timestampNormalizer = MuxTimestampNormalizer()
        var stopDeadlineNs: Long? = null

        fun startMuxerIfReady() {
            if (muxerStarted || videoTrack < 0 || (active.audio != null && audioTrack < 0)) return
            active.muxer.start()
            muxerStarted = true
            pending.sortedBy { it.presentationTimeUs }.forEach { sample ->
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
                        check(videoTrack < 0) { "Video encoder emitted its format twice." }
                        videoTrack = active.muxer.addTrack(format)
                    } else {
                        check(audioTrack < 0) { "AAC encoder emitted its format twice." }
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
                        val normalizedPtsUs = timestampNormalizer.normalize(video, info.presentationTimeUs)
                        val normalizedInfo = MediaCodec.BufferInfo().apply {
                            set(info.offset, info.size, normalizedPtsUs, info.flags)
                        }
                        if (muxerStarted) {
                            active.muxer.writeSampleData(if (video) videoTrack else audioTrack, buffer, normalizedInfo)
                        } else {
                            check(pending.size < MAX_PENDING_MUX_SAMPLES) { "Muxer format negotiation buffer overflowed." }
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            pending.add(PendingMuxSample(video, bytes, normalizedPtsUs, info.flags))
                        }
                        if (video) {
                            active.frames++
                            active.firstPtsUs = active.firstPtsUs ?: normalizedPtsUs
                            active.lastPtsUs = normalizedPtsUs
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
                if (active.stopRequested.get()) {
                    val deadline = stopDeadlineNs ?: (System.nanoTime() + CODEC_STOP_TIMEOUT_NS).also { stopDeadlineNs = it }
                    if (System.nanoTime() > deadline) error("Codec EOS timed out while finalizing the recording.")
                    if (!audioProgress && !videoProgress && active.failure != null) break
                }
            }
        } catch (failure: Throwable) {
            active.failure = failure
        } finally {
            val glReleased = CountDownLatch(1)
            handler.post {
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                    if (EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == encoderEglSurface) makeCurrent(pbuffer)
                    EGL14.eglDestroySurface(display, encoderEglSurface)
                    encoderEglSurface = EGL14.EGL_NO_SURFACE
                }
                glReleased.countDown()
            }
            glReleased.await(3, TimeUnit.SECONDS)
            val success = active.failure == null && muxerStarted && videoEos && audioEos && active.frames > 0
            if (muxerStarted) runCatching { active.muxer.stop() }.onFailure { active.failure = it }
            runCatching { active.muxer.release() }
            runCatching { active.audio?.close() }
            runCatching { active.codec.stop() }
            runCatching { active.codec.release() }
            runCatching { active.codecSurface.release() }
            val evidence = if (success && active.failure == null) {
                OpenCineLogRecordingEvidence(
                    sourcePath = sourcePath,
                    sourceDynamicRange = sourcePath.dynamicRange,
                    sourceColorSpace = sourcePath.colorSpace,
                    sourceTransfer = sourcePath.transfer,
                    sourcePrecision = sourcePath.sourcePrecision,
                    codecName = active.candidate.codecName,
                    targetFps = targetFps,
                    sourceDataSpace = lastSourceDataSpace,
                    transformSha256 = transformSha256(sourcePath),
                    encodedFrames = active.frames,
                    firstPtsUs = active.firstPtsUs,
                    lastPtsUs = active.lastPtsUs,
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
            if (EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == previewEglSurface) makeCurrent(pbuffer)
            EGL14.eglDestroySurface(display, previewEglSurface)
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
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(shader) }
            return shader
        }
        val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val linked = GLES30.glCreateProgram()
        GLES30.glAttachShader(linked, vertex)
        GLES30.glAttachShader(linked, fragment)
        GLES30.glLinkProgram(linked)
        val status = IntArray(1)
        GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(linked) }
        return linked
    }

    private fun reportGlFailure(failure: Throwable) {
        onFailure("log-gpu-pipeline-failed", failure.message ?: "The OCLog GPU pipeline failed.")
    }

    private fun <T> callOnGlThread(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        val task = FutureTask(block)
        check(handler.post(task)) { "OCLog GL thread is closed." }
        return task.get(GL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        recording?.let { active ->
            if (active.stopRequested.compareAndSet(false, true)) {
                active.acceptFrames.set(false)
                active.audio?.requestStop()
                handler.post { runCatching { active.codec.signalEndOfInputStream() } }
            }
            active.drainThread?.join(4_000)
        }
        runCatching {
            callOnGlThread {
                destroyPreviewSurface()
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderEglSurface)
                surfaceTexture?.setOnFrameAvailableListener(null)
                surfaceTexture?.release()
                inputSurface?.release()
                if (program != 0) GLES30.glDeleteProgram(program)
                if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                if (previewContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, previewContext)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
            }
        }
        thread.quitSafely()
    }

    private class EmbeddedAac(
        val codec: MediaCodec,
        private val audioRecord: AudioRecord,
        private val sampleRateHz: Int,
        private val channels: Int,
        private val hardwareAgc: AutomaticGainControl?,
        private val softAgc: SoftAgc?,
        private val onAudioLevel: ((AudioLevelSnapshot) -> Unit)?,
    ) : AutoCloseable {
        private val stopRequested = AtomicBoolean(false)
        private val levelMeter = AudioLevelMeter(PcmMeterEncoding.PCM_16, channels)
        @Volatile private var feederThread: Thread? = null

        fun start(onFailure: (Throwable) -> Unit) {
            codec.start()
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter RECORDING state."
            }
            val anchorUs = System.nanoTime() / 1_000L
            feederThread = Thread({
                var submittedFrames = 0L
                try {
                    while (!stopRequested.get()) {
                        val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (index < 0) continue
                        val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                        val read = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                        if (read <= 0) {
                            codec.queueInputBuffer(index, 0, 0, anchorUs + submittedFrames * 1_000_000L / sampleRateHz, 0)
                            if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
                                error("AudioRecord read failed with code $read.")
                            }
                            continue
                        }
                        levelMeter.analyze(buffer, read, android.os.SystemClock.elapsedRealtime())
                            ?.let { onAudioLevel?.invoke(it) }
                        softAgc?.processPcm16(buffer, read)
                        val ptsUs = anchorUs + submittedFrames * 1_000_000L / sampleRateHz
                        codec.queueInputBuffer(index, 0, read, ptsUs, 0)
                        submittedFrames += read / (PCM_BYTES_PER_SAMPLE * channels)
                    }
                } catch (failure: Throwable) {
                    if (!stopRequested.get()) onFailure(failure)
                } finally {
                    runCatching {
                        val eosIndex = generateSequence { codec.dequeueInputBuffer(CODEC_TIMEOUT_US) }
                            .first { it >= 0 }
                        codec.queueInputBuffer(
                            eosIndex,
                            0,
                            0,
                            anchorUs + submittedFrames * 1_000_000L / sampleRateHz,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    }.onFailure(onFailure)
                }
            }, "OpenCineCamAacFeeder").apply { start() }
        }

        fun requestStop() {
            if (!stopRequested.compareAndSet(false, true)) return
            runCatching { audioRecord.stop() }
        }

        override fun close() {
            requestStop()
            feederThread?.join(2_000)
            runCatching { hardwareAgc?.release() }
            runCatching { audioRecord.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        companion object {
            private const val PCM_BYTES_PER_SAMPLE = 2

            @SuppressLint("MissingPermission")
            fun create(context: Context, config: Camera2EmbeddedAudioConfig): EmbeddedAac {
                check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "RECORD_AUDIO permission is required for embedded AAC."
                }
                val channelMask = if (config.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val minimum = AudioRecord.getMinBufferSize(config.sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "The requested PCM input is unsupported." }
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
                try {
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed." }
                var softwareAgc: SoftAgc? = null
                if (config.enableAutomaticGainControl) {
                    // Prefer the HAL effect; fall back to in-process gain control on devices
                    // whose audio HAL does not expose AutomaticGainControl (seen on a tested device).
                    val candidate = runCatching { AutomaticGainControl.create(audioRecord.audioSessionId) }.getOrNull()
                    if (candidate != null) {
                        val status = runCatching { candidate.setEnabled(true) }.getOrDefault(AudioEffect.ERROR)
                        if (status == AudioEffect.SUCCESS && candidate.enabled) hardwareAgc = candidate
                        else runCatching { candidate.release() }
                    }
                    if (hardwareAgc == null) softwareAgc = SoftAgc(config.sampleRateHz, config.channels)
                }
                config.preferredInputDeviceId?.let { requestedId ->
                        val device = context.getSystemService(AudioManager::class.java)
                            .getDevices(AudioManager.GET_DEVICES_INPUTS)
                            .firstOrNull { it.id == requestedId }
                            ?: error("Requested audio input $requestedId is no longer connected.")
                        check(audioRecord.setPreferredDevice(device)) { "AudioRecord rejected audio input $requestedId." }
                    }
                    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    try {
                        codec.configure(
                            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRateHz, config.channels).apply {
                                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
                                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minimum * 2)
                            },
                            null,
                            null,
                            MediaCodec.CONFIGURE_FLAG_ENCODE,
                        )
                        return EmbeddedAac(codec, audioRecord, config.sampleRateHz, config.channels, hardwareAgc, softwareAgc, config.onAudioLevel)
                    } catch (failure: Throwable) {
                        codec.release()
                        throw failure
                    }
                } catch (failure: Throwable) {
                    runCatching { hardwareAgc?.release() }
                    audioRecord.release()
                    throw failure
                }
            }
        }
    }

    private class Recording(
        val codec: MediaCodec,
        val codecSurface: Surface,
        val muxer: MediaMuxer,
        val candidate: OpenCineLogEncoderCandidate,
        val geometry: RecordingGeometry,
        val audio: EmbeddedAac?,
        val onStopped: (Boolean, OpenCineLogRecordingEvidence?) -> Unit,
    ) {
        val stopRequested = AtomicBoolean(false)
        val acceptFrames = AtomicBoolean(true)
        @Volatile var failure: Throwable? = null
        @Volatile var drainThread: Thread? = null
        var frames = 0L
        var firstPtsUs: Long? = null
        var lastPtsUs: Long? = null
    }

    private data class PendingMuxSample(
        val video: Boolean,
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    companion object {
        private const val CODEC_TIMEOUT_US = 20_000L
        private const val CODEC_STOP_TIMEOUT_NS = 5_000_000_000L
        private const val MAX_PENDING_MUX_SAMPLES = 64
        private const val GL_CALL_TIMEOUT_SECONDS = 8L
        private const val OUTPUT_OCLOG = 0
        private const val OUTPUT_VIEW_ASSIST = 1
        private const val OUTPUT_FLAT_MONITOR = 2
        private const val ANALYSIS_WIDTH = 160
        private const val ANALYSIS_HEIGHT = 90
        private const val ANALYSIS_PERIOD_MS = 250L
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

        fun findAvcEncoder(size: Size, targetFps: Int): OpenCineLogEncoderCandidate? =
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence()
                .filter { it.isEncoder && !it.isAlias && it.isHardwareAccelerated }
                .filter { it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                .sortedBy { it.name }
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
                    OpenCineLogEncoderCandidate(info.name, profile, size)
                }
                .firstOrNull()

        private val VERTEX_SHADER = """
            #version 300 es
            uniform mat4 uTextureMatrix;
            uniform vec2 uPositionScale;
            uniform int uPreviewRotation;
            uniform int uMirrorPreview;
            out vec2 vTexCoord;
            const vec2 positions[4] = vec2[4](vec2(-1.0,-1.0), vec2(1.0,-1.0), vec2(-1.0,1.0), vec2(1.0,1.0));
            const vec2 texCoords[4] = vec2[4](vec2(0.0,0.0), vec2(1.0,0.0), vec2(0.0,1.0), vec2(1.0,1.0));
            void main() {
                vec2 position = positions[gl_VertexID];
                if (uPreviewRotation == 1) {
                    position = vec2(position.y, -position.x);
                } else if (uPreviewRotation == 2) {
                    position = -position;
                } else if (uPreviewRotation == 3) {
                    position = vec2(-position.y, position.x);
                }
                if (uMirrorPreview == 1) position.x = -position.x;
                gl_Position = vec4(position * uPositionScale, 0.0, 1.0);
                vTexCoord = (uTextureMatrix * vec4(texCoords[gl_VertexID], 0.0, 1.0)).xy;
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
