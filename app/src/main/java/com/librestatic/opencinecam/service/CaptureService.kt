/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.view.Surface
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.normalizedFor
import com.librestatic.opencinecam.toSpec
import com.librestatic.opencinecam.VideoGeometryPolicy
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.Camera2PreviewListener
import com.librestatic.opencinecam.camera.Camera2PreviewMetadata
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import com.librestatic.opencinecam.camera.adaptTo
import com.librestatic.opencinecam.camera.Camera2Analysis
import com.librestatic.opencinecam.camera.Camera2EmbeddedAudioConfig
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.AfLockBehavior
import com.librestatic.opencinecam.camera.LockState
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingGeometry
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.TapFocusState
import com.librestatic.opencinecam.camera.ZoomAnchor
import com.librestatic.opencinecam.camera.ZoomMath
import com.librestatic.opencinecam.core.model.CaptureCommand
import com.librestatic.opencinecam.core.model.CaptureState
import com.librestatic.opencinecam.core.model.CaptureStateMachine
import com.librestatic.opencinecam.core.model.SerializedCaptureActor
import com.librestatic.opencinecam.storage.StillImageSaver
import com.librestatic.opencinecam.storage.VideoOutput
import com.librestatic.opencinecam.storage.AudioSidecarRecorder
import com.librestatic.opencinecam.storage.FlacAudioSidecarRecorder
import com.librestatic.opencinecam.storage.WavAudioSidecarRecorder
import com.librestatic.opencinecam.media.audio.AndroidProfessionalAudioProbe
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

const val ACTION_START_RECORDING = "com.librestatic.opencinecam.action.START_RECORDING"

/** Same-process bound service; all hardware commands are serialized off the main thread. */
class CaptureService : Service() {
    private val machine = CaptureStateMachine()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32),
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val actor = SerializedCaptureActor(machine, executor)
    private val state = MutableStateFlow<CaptureState>(CaptureState.Stopped)
    private val cameraState = MutableStateFlow(CameraUiState())
    private val localBinder = LocalBinder()
    private val foreground = RecordingForegroundController(this)
    private val previewEngine by lazy { Camera2PreviewEngine(this) }
    private val stillSaver by lazy { StillImageSaver(this) }
    private var videoOutput: VideoOutput? = null
    private var audioSidecarRecorder: AudioSidecarRecorder? = null
    private var previewAudioMonitor: PreviewAudioMonitor? = null
    private var audioStartFailure: String? = null
    private var activeRecordingAudioLabel: String = "no audio"
    private var recordingStartedAtMs = 0L
    private var attachedPreviewSurface: Surface? = null
    private var attachedPreviewRotationDegrees: Int = 0
    private var cameraSwitchGeneration = 0L
    private var recoveryGeneration = 0L
    private var pendingSwitchTarget: String? = null
    private var pendingSwitchPrevious: String? = null
    private var pendingStatusMessage: String? = null
    private val perCameraZoom = mutableMapOf<String, Float>()
    private val perCameraWhiteBalance = mutableMapOf<String, WhiteBalanceSelection>()
    private var settings = CameraSettings()
    private var geometrySeeds = GeometrySeeds()
    private lateinit var physicalOrientationTracker: PhysicalOrientationTracker
    private var activeRecordingGeometry: RecordingGeometry? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val storageExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16),
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val recordingTicker = object : Runnable {
        override fun run() {
            if (cameraState.value.phase != CameraUiPhase.RECORDING) return
            cameraState.value = cameraState.value.copy(
                recordingElapsedMs = (android.os.SystemClock.elapsedRealtime() - recordingStartedAtMs).coerceAtLeast(0L),
                availableStorageBytes = runCatching { StatFs(filesDir.absolutePath).availableBytes }.getOrNull(),
            )
            mainHandler.postDelayed(this, RECORDING_TICK_MS)
        }
    }

    /** Last geometry the user chose per profile mode, seeded from persisted settings. */
    private data class GeometrySeeds(
        val videoWidth: Int = 1920,
        val videoHeight: Int = 1080,
        val videoFps: Int = 30,
        val logWidth: Int = 1920,
        val logHeight: Int = 1080,
        val logFps: Int = 30,
    ) {
        fun width(mode: CaptureMode): Int = if (mode == CaptureMode.LOG) logWidth else videoWidth
        fun height(mode: CaptureMode): Int = if (mode == CaptureMode.LOG) logHeight else videoHeight
        fun fps(mode: CaptureMode): Int = if (mode == CaptureMode.LOG) logFps else videoFps
    }

    override fun onCreate() {
        super.onCreate()
        physicalOrientationTracker = PhysicalOrientationTracker(this)
        physicalOrientationTracker.start()
    }

    private fun publishAudioLevel(snapshot: AudioLevelSnapshot) {
        val current = cameraState.value
        cameraState.value = current.copy(
            audioLevels = snapshot,
            audioClipLatched = current.audioClipLatched || snapshot.clipped,
            audioMonitoringActive = true,
        )
    }

    private fun stopPreviewAudioMonitor(clearLevels: Boolean = false) {
        val monitor = previewAudioMonitor
        previewAudioMonitor = null
        runCatching { monitor?.close() }
        val current = cameraState.value
        cameraState.value = current.copy(
            audioLevels = if (clearLevels) null else current.audioLevels,
            audioMonitoringActive = false,
        )
    }

    private fun startPreviewAudioMonitorIfEligible() {
        stopPreviewAudioMonitor(clearLevels = false)
        val current = cameraState.value
        val eligible = attachedPreviewSurface?.isValid == true &&
            current.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) &&
            current.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG) &&
            settings.audioEnabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!eligible) {
            if (!settings.audioEnabled || current.selectedMode !in setOf(CaptureMode.VIDEO, CaptureMode.LOG)) {
                cameraState.value = current.copy(audioLevels = null, audioMonitoringActive = false)
            }
            return
        }
        try {
            val monitor = PreviewAudioMonitor.create(
                this,
                cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings,
                ::publishAudioLevel,
            ) { failure ->
                mainHandler.post {
                    stopPreviewAudioMonitor(clearLevels = false)
                    cameraState.value = cameraState.value.copy(
                        audioMonitoringActive = false,
                        message = "Audio monitor unavailable: ${failure.message}",
                    )
                }
            }
            previewAudioMonitor = monitor
            monitor.start()
            cameraState.value = cameraState.value.copy(audioMonitoringActive = true)
        } catch (failure: Throwable) {
            previewAudioMonitor = null
            cameraState.value = cameraState.value.copy(
                audioMonitoringActive = false,
                message = "Audio monitor unavailable: ${failure.message}",
            )
        }
    }

    private val previewListener = object : Camera2PreviewListener {
        override fun onOpening(descriptor: Camera2CameraDescriptor) {
            stopPreviewAudioMonitor(clearLevels = true)
            cameraState.value = cameraState.value.copy(
                phase = CameraUiPhase.OPENING,
                selectedCameraId = descriptor.cameraId,
                histogram = emptyList(),
                redHistogram = emptyList(),
                greenHistogram = emptyList(),
                blueHistogram = emptyList(),
                analysisUpdatedAtMs = 0L,
                tapFocusState = TapFocusState.IDLE,
                errorCode = null,
                message = null,
            )
        }

        override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) {
            if (pendingSwitchTarget == descriptor.cameraId) {
                pendingSwitchTarget = null
                pendingSwitchPrevious = null
                cameraSwitchGeneration++
            }
            actor.submit(CaptureCommand.PreviewConfigured).whenComplete { transition, error ->
                if (error == null && transition?.accepted == true) state.value = transition.current
            }
            cameraState.value = cameraState.value.copy(
                phase = CameraUiPhase.PREVIEWING,
                selectedCameraId = descriptor.cameraId,
                errorCode = null,
                message = pendingStatusMessage.also { pendingStatusMessage = null },
            )
            startPreviewAudioMonitorIfEligible()
            // Restore the remembered zoom for this camera after the session starts.
            val remembered = perCameraZoom[descriptor.cameraId]
            if (remembered != null && remembered != 1f && descriptor.zoomSupported) {
                previewEngine.setZoomRatio(remembered)
            }
            // Restore the remembered white-balance selection for this camera, adapting it to
            // the new camera's Kelvin capability (a Kelvin request falls back to Auto when the
            // camera has no direct CCT support).
            val rememberedWb = perCameraWhiteBalance[descriptor.cameraId]
            if (rememberedWb != null && rememberedWb != WhiteBalanceSelection.Auto) {
                val adapted = rememberedWb.adaptTo(descriptor.kelvinRange)
                if (adapted != WhiteBalanceSelection.Auto) {
                    cameraState.value = cameraState.value.copy(requestedWhiteBalance = adapted)
                    previewEngine.setWhiteBalance(adapted)
                }
            }
        }

        override fun onMetadata(metadata: Camera2PreviewMetadata) {
            cameraState.value = cameraState.value.copy(
                sensitivityIso = metadata.sensitivityIso,
                exposureTimeNs = metadata.exposureTimeNs,
                focusDistanceDiopters = metadata.focusDistanceDiopters,
                afState = metadata.afState,
                awbState = metadata.awbState,
                reportedColorTemperatureK = metadata.colorTemperatureK,
                effectiveFps = metadata.effectiveFps,
            )
        }

        override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) {
            try {
                storageExecutor.execute {
                    try {
                        val uri = stillSaver.saveJpeg(bytes)
                        val current = cameraState.value
                        if (current.selectedMode == CaptureMode.BURST && current.burstExpected > 0) {
                            val captured = (current.burstCaptured + 1).coerceAtMost(current.burstExpected)
                            cameraState.value = current.copy(
                                phase = if (captured == current.burstExpected) CameraUiPhase.SAVED else CameraUiPhase.CAPTURING,
                                lastSavedUri = uri.toString(),
                                burstCaptured = captured,
                                message = if (captured == current.burstExpected) {
                                    getString(R.string.burst_saved, captured)
                                } else {
                                    getString(R.string.burst_progress, captured, current.burstExpected)
                                },
                                errorCode = null,
                            )
                        } else {
                            cameraState.value = current.copy(
                                phase = CameraUiPhase.SAVED,
                                lastSavedUri = uri.toString(),
                                message = "JPEG saved: ${width}×$height",
                                errorCode = null,
                            )
                        }
                    } catch (failure: Throwable) {
                        fail("jpeg-save-failed", failure.message ?: "JPEG could not be saved.")
                    }
                }
            } catch (_: RejectedExecutionException) {
                fail("jpeg-save-busy", "The still-image writer is busy.")
            }
        }

        override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) {
            try {
                storageExecutor.execute {
                    try {
                        val uri = stillSaver.saveDng(bytes)
                        cameraState.value = cameraState.value.copy(
                            phase = CameraUiPhase.SAVED,
                            lastSavedUri = uri.toString(),
                            message = "DNG saved: ${width}×$height",
                            errorCode = null,
                        )
                    } catch (failure: Throwable) {
                        fail("dng-save-failed", failure.message ?: "DNG could not be saved.")
                    }
                }
            } catch (_: RejectedExecutionException) {
                fail("dng-save-busy", "The still-image writer is busy.")
            }
        }

        override fun onAnalysis(analysis: Camera2Analysis) {
            cameraState.value = cameraState.value.copy(
                histogram = analysis.histogram,
                redHistogram = analysis.redHistogram,
                greenHistogram = analysis.greenHistogram,
                blueHistogram = analysis.blueHistogram,
                analysisUpdatedAtMs = analysis.capturedAtElapsedRealtimeMs,
                zebraCells = analysis.zebraCells,
                focusCells = analysis.focusCells,
            )
        }

        override fun onRecordingStarted(width: Int, height: Int) {
            recordingStartedAtMs = android.os.SystemClock.elapsedRealtime()
            audioSidecarRecorder?.let { sidecar ->
                try {
                    sidecar.start()
                } catch (failure: Throwable) {
                    audioStartFailure = failure.message ?: "Lossless audio could not start."
                    sidecar.finish(false)
                    audioSidecarRecorder = null
                    previewEngine.stopVideo()
                    return
                }
            }
            mainHandler.removeCallbacks(recordingTicker)
            cameraState.value = cameraState.value.copy(
                phase = CameraUiPhase.RECORDING,
                message = if (cameraState.value.selectedMode == CaptureMode.LOG) {
                    val source = if (cameraState.value.activeLogProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                        "HFR ISP-derived · source depth not claimed"
                    } else "HLG-derived 10-bit"
                    "REC ${width}×$height · OCLog2 · HEVC Main10 · $source · ${cameraState.value.targetFps} fps · $activeRecordingAudioLabel"
                } else {
                    "REC ${width}×$height · H.264 · $activeRecordingAudioLabel"
                },
                recordingElapsedMs = 0L,
                recordingWidth = width,
                recordingHeight = height,
                availableStorageBytes = runCatching { StatFs(filesDir.absolutePath).availableBytes }.getOrNull(),
                errorCode = null,
            )
            mainHandler.post(recordingTicker)
        }

        override fun onRecordingStopped(success: Boolean) {
            mainHandler.removeCallbacks(recordingTicker)
            val output = videoOutput
            videoOutput = null
            val durationMs = (android.os.SystemClock.elapsedRealtime() - recordingStartedAtMs).coerceAtLeast(0L)
            try {
                val requestedAudioFailure = audioStartFailure
                audioStartFailure = null
                val sidecarResult = audioSidecarRecorder?.finish(success && requestedAudioFailure == null)
                audioSidecarRecorder = null
                val logEvidence = previewEngine.consumeLastOpenCineLogEvidence()
                val finalizedSuccess = success && requestedAudioFailure == null &&
                    (cameraState.value.selectedMode != CaptureMode.LOG || logEvidence != null)
                val geometry = activeRecordingGeometry
                activeRecordingGeometry = null
                val uri = output?.finish(
                    finalizedSuccess,
                    logEvidence?.let(::logSidecarJson),
                    expectedGeometry = geometry,
                )
                cameraState.value = cameraState.value.copy(
                    phase = if (finalizedSuccess) CameraUiPhase.SAVED else CameraUiPhase.ERROR,
                    lastSavedUri = uri?.toString(),
                    message = if (finalizedSuccess) {
                        val audioSaved = sidecarResult?.let { " + ${it.bitDepth.bits}-bit ${it.container} ${it.sampleRateHz / 1000.0} kHz" }
                            ?: if (activeRecordingAudioLabel.startsWith("AAC")) " + $activeRecordingAudioLabel" else ""
                        if (logEvidence != null) {
                            val source = if (logEvidence.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                                "HFR ISP-derived"
                            } else "HLG-derived 10-bit"
                            "OCLog2 Main10 saved · $source + provenance$audioSaved · ${durationMs / 1000.0}s"
                        }
                        else "Video saved$audioSaved · ${durationMs / 1000.0}s"
                    } else requestedAudioFailure ?: "Video could not be finalized with verified provenance.",
                    errorCode = if (finalizedSuccess) null else if (requestedAudioFailure != null) "audio-start-failed" else "video-finalize-failed",
                    recordingElapsedMs = durationMs,
                )
            } catch (failure: Throwable) {
                runCatching { audioSidecarRecorder?.finish(false) }
                audioSidecarRecorder = null
                runCatching { output?.finish(false) }
                fail("video-save-failed", failure.message ?: "Video could not be saved.")
            }
            foreground.stop()
            startPreviewAudioMonitorIfEligible()
        }

        override fun onTapFocusState(state: TapFocusState) {
            cameraState.value = cameraState.value.copy(tapFocusState = state)
        }

        override fun onAeLockChanged(active: Boolean) {
            cameraState.value = cameraState.value.copy(aeLockActive = active)
        }

        override fun onAfLockChanged(state: LockState) {
            cameraState.value = cameraState.value.copy(afLockState = state)
        }

        override fun onZoomRangeAvailable(min: Float, max: Float, anchors: List<ZoomAnchor>) {
            val current = cameraState.value
            cameraState.value = current.copy(
                zoomMinRatio = min,
                zoomMaxRatio = max,
                opticalAnchors = anchors,
                zoomSupported = min < max,
                zoomHfrSupported = current.descriptor?.supportsHfrZoom == true,
                zoomRatio = perCameraZoom[current.selectedCameraId] ?: 1f,
            )
        }

        override fun onZoomEffective(ratio: Float) {
            cameraState.value = cameraState.value.copy(zoomEffectiveRatio = ratio)
        }

        override fun onZoomRejected(requested: Float, accepted: Float) {
            cameraState.value = cameraState.value.copy(
                zoomRatio = accepted,
                zoomEffectiveRatio = accepted,
                message = "Zoom $requested× not accepted; kept ${accepted}×",
                messageTransient = true,
            )
        }

        override fun onFailure(code: String, message: String, recoverable: Boolean) {
            stopPreviewAudioMonitor(clearLevels = false)
            mainHandler.removeCallbacks(recordingTicker)
            videoOutput?.finish(false)
            videoOutput = null
            activeRecordingGeometry = null
            runCatching { audioSidecarRecorder?.finish(false) }
            audioSidecarRecorder = null
            audioStartFailure = null
            foreground.stop()
            fail(code, message)
        }
    }

    inner class LocalBinder : Binder() {
        val states: StateFlow<CaptureState> = state.asStateFlow()
        val cameraStates: StateFlow<CameraUiState> = cameraState.asStateFlow()

        /** Returns false when the bounded actor queue is full or the service is shutting down. */
        fun submit(command: CaptureCommand): Boolean = try {
            actor.submit(command).whenComplete { transition, error ->
                if (error == null && transition != null) state.value = transition.current
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }

        fun prepare(targetWidth: Int, targetHeight: Int) {
            cameraState.value = cameraState.value.copy(phase = CameraUiPhase.PREPARING, message = null)
            val descriptors = previewEngine.descriptors(targetWidth, targetHeight)
            val preferred = descriptors.firstOrNull()
            cameraState.value = if (preferred == null) {
                cameraState.value.copy(
                    phase = CameraUiPhase.ERROR,
                    cameras = emptyList(),
                    selectedCameraId = null,
                    errorCode = "no-public-camera",
                    message = "No public Camera2 camera is available.",
                )
            } else {
                val selectedId = cameraState.value.selectedCameraId
                    ?.takeIf { id -> descriptors.any { it.cameraId == id } }
                    ?: preferred.cameraId
                val selectedDescriptor = descriptors.first { it.cameraId == selectedId }
                val videoProfile = normalizedVideoProfile(
                    selectedDescriptor,
                    geometrySeeds.videoWidth,
                    geometrySeeds.videoHeight,
                    geometrySeeds.videoFps,
                )
                val logProfile = normalizedLogProfile(
                    selectedDescriptor,
                    geometrySeeds.logWidth,
                    geometrySeeds.logHeight,
                    geometrySeeds.logFps,
                ).takeIf { cameraState.value.selectedMode == CaptureMode.LOG }
                val selectedWidth = logProfile?.size?.width ?: videoProfile?.size?.width
                    ?: cameraState.value.targetVideoWidth
                val selectedHeight = logProfile?.size?.height ?: videoProfile?.size?.height
                    ?: cameraState.value.targetVideoHeight
                val targetFps = normalizedTargetFps(
                    selectedDescriptor,
                    cameraState.value.selectedMode,
                    geometrySeeds.fps(cameraState.value.selectedMode),
                    selectedWidth,
                    selectedHeight,
                )
                cameraState.value.copy(
                    phase = CameraUiPhase.READY,
                    cameras = descriptors,
                    selectedCameraId = selectedId,
                    targetFps = targetFps,
                    targetVideoWidth = selectedWidth,
                    targetVideoHeight = selectedHeight,
                    effectiveFps = null,
                    modeGates = cameraState.value.modeGates + mapOf(
                        CaptureMode.RAW_PHOTO to if (preferred.supportsRaw) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                        CaptureMode.LOG to if (preferred.supportsOpenCineLog) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                    ),
                    errorCode = null,
                    message = null,
                )
            }
            refreshAudioCapabilities()
        }

        /** Fully recovers the retained viewfinder after a capture or permission failure. */
        fun recoverPreview(targetWidth: Int, targetHeight: Int) {
            val token = ++recoveryGeneration
            mainHandler.removeCallbacks(recordingTicker)
            runCatching { audioSidecarRecorder?.finish(false) }
            audioSidecarRecorder = null
            runCatching { videoOutput?.finish(false) }
            videoOutput = null
            activeRecordingGeometry = null
            audioStartFailure = null
            foreground.stop()
            previewEngine.stopPreview()
            prepare(targetWidth, targetHeight)
            if (cameraState.value.phase == CameraUiPhase.ERROR) return
            val surface = attachedPreviewSurface
            if (surface?.isValid != true || !attachPreview(surface, attachedPreviewRotationDegrees)) {
                fail("preview-recovery-surface-unavailable", "The camera surface is not available yet.")
                return
            }
            mainHandler.postDelayed({
                if (token == recoveryGeneration && cameraState.value.phase != CameraUiPhase.PREVIEWING) {
                    fail("preview-recovery-timeout", "The camera could not recover the preview.")
                }
            }, PREVIEW_RECOVERY_TIMEOUT_MS)
        }

        fun refreshAudioCapabilities() {
            try {
                storageExecutor.execute {
                    val capabilities = runCatching { AndroidProfessionalAudioProbe(this@CaptureService).probe() }
                        .getOrElse {
                            cameraState.value = cameraState.value.copy(message = "Audio capability probe failed: ${it.message}")
                            return@execute
                        }
                    val normalized = settings.normalizedFor(capabilities)
                    settings = normalized
                    cameraState.value = cameraState.value.copy(audioCapabilities = capabilities)
                    startPreviewAudioMonitorIfEligible()
                }
            } catch (_: RejectedExecutionException) {
                cameraState.value = cameraState.value.copy(message = "Audio capability probe is busy.")
            }
        }

        fun attachPreview(surface: Surface, displayRotationDegrees: Int): Boolean {
            val descriptor = cameraState.value.descriptor ?: return false
            if (!surface.isValid) return false
            attachedPreviewSurface = surface
            attachedPreviewRotationDegrees = displayRotationDegrees
            if (cameraState.value.phase == CameraUiPhase.RECORDING) {
                return previewEngine.attachPreviewWhileRecording(surface, displayRotationDegrees)
            }
            actor.submit(CaptureCommand.Open(OWNER_ID, descriptor.cameraId)).whenComplete { transition, error ->
                if (error == null && transition?.accepted == true) state.value = transition.current
            }
            previewEngine.startPreview(
                descriptor,
                surface,
                displayRotationDegrees,
                previewListener,
                openCineLog = cameraState.value.selectedMode == CaptureMode.LOG,
                viewAssist = settings.logViewAssistEnabled,
                targetFps = cameraState.value.targetFps,
                videoProfile = cameraState.value.activeVideoProfile.takeIf {
                    cameraState.value.selectedMode in CameraUiState.videoProfileModes
                },
                logProfile = cameraState.value.activeLogProfile.takeIf {
                    cameraState.value.selectedMode == CaptureMode.LOG
                },
            )
            return true
        }

        fun detachPreview(surface: Surface? = null) {
            if (surface != null && attachedPreviewSurface !== surface) return
            stopPreviewAudioMonitor(clearLevels = true)
            attachedPreviewSurface = null
            if (cameraState.value.phase == CameraUiPhase.RECORDING) {
                previewEngine.detachPreviewWhileRecording()
                return
            }
            previewEngine.stopPreview()
            actor.submit(CaptureCommand.RequestStop).thenCompose { transition ->
                if (transition.accepted) actor.submit(CaptureCommand.StopCompleted) else java.util.concurrent.CompletableFuture.completedFuture(transition)
            }.whenComplete { transition, error ->
                if (error == null && transition != null) state.value = transition.current
            }
            cameraState.value = cameraState.value.copy(phase = CameraUiPhase.READY)
        }

        fun selectCamera(cameraId: String) {
            val current = cameraState.value
            if (current.phase == CameraUiPhase.RECORDING) return
            val descriptor = current.cameras.firstOrNull { it.cameraId == cameraId } ?: return
            val videoProfile = normalizedVideoProfile(
                descriptor, geometrySeeds.width(current.selectedMode), geometrySeeds.height(current.selectedMode), geometrySeeds.fps(current.selectedMode),
            )
            val logProfile = normalizedLogProfile(
                descriptor, geometrySeeds.logWidth, geometrySeeds.logHeight, geometrySeeds.logFps,
            ).takeIf { current.selectedMode == CaptureMode.LOG }
            val selectedWidth = logProfile?.size?.width ?: videoProfile?.size?.width ?: geometrySeeds.width(current.selectedMode)
            val selectedHeight = logProfile?.size?.height ?: videoProfile?.size?.height ?: geometrySeeds.height(current.selectedMode)
            val previous = current.selectedCameraId ?: return
            if (previous == cameraId) return
            val surface = attachedPreviewSurface
            cameraState.value = current.copy(
                selectedCameraId = cameraId,
                targetFps = normalizedTargetFps(
                    descriptor,
                    current.selectedMode,
                    geometrySeeds.fps(current.selectedMode),
                    selectedWidth,
                    selectedHeight,
                ),
                targetVideoWidth = selectedWidth,
                targetVideoHeight = selectedHeight,
                effectiveFps = null,
                requestedAeCompensationIndex = 0,
                requestedWhiteBalance = WhiteBalanceSelection.Auto,
                phase = CameraUiPhase.OPENING,
                message = "Camera $cameraId selected",
                modeGates = current.modeGates + mapOf(
                    CaptureMode.RAW_PHOTO to if (descriptor.supportsRaw) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                    CaptureMode.LOG to if (descriptor.supportsOpenCineLog) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                ),
            )
            if (surface?.isValid != true) return
            pendingSwitchPrevious = previous
            pendingSwitchTarget = cameraId
            val switchToken = ++cameraSwitchGeneration
            previewEngine.startPreview(
                descriptor,
                surface,
                attachedPreviewRotationDegrees,
                previewListener,
                openCineLog = cameraState.value.selectedMode == CaptureMode.LOG,
                viewAssist = settings.logViewAssistEnabled,
                targetFps = cameraState.value.targetFps,
                videoProfile = cameraState.value.activeVideoProfile.takeIf {
                    cameraState.value.selectedMode in CameraUiState.videoProfileModes
                },
                logProfile = cameraState.value.activeLogProfile.takeIf {
                    cameraState.value.selectedMode == CaptureMode.LOG
                },
            )
            mainHandler.postDelayed({
                if (switchToken != cameraSwitchGeneration || pendingSwitchTarget != cameraId) return@postDelayed
                val fallbackId = pendingSwitchPrevious ?: return@postDelayed
                val fallback = cameraState.value.cameras.firstOrNull { it.cameraId == fallbackId } ?: return@postDelayed
                pendingSwitchTarget = null
                pendingSwitchPrevious = null
                cameraSwitchGeneration++
                pendingStatusMessage = getString(R.string.camera_switch_timeout, cameraId, fallbackId)
                cameraState.value = cameraState.value.copy(selectedCameraId = fallbackId, phase = CameraUiPhase.OPENING)
                attachedPreviewSurface?.takeIf { it.isValid }?.let {
                    previewEngine.startPreview(
                        fallback,
                        it,
                        attachedPreviewRotationDegrees,
                        previewListener,
                        openCineLog = cameraState.value.selectedMode == CaptureMode.LOG,
                        viewAssist = settings.logViewAssistEnabled,
                        targetFps = cameraState.value.targetFps,
                        videoProfile = cameraState.value.activeVideoProfile.takeIf {
                            cameraState.value.selectedMode in CameraUiState.videoProfileModes
                        },
                        logProfile = cameraState.value.activeLogProfile.takeIf {
                            cameraState.value.selectedMode == CaptureMode.LOG
                        },
                    )
                }
            }, CAMERA_SWITCH_TIMEOUT_MS)
        }

        fun selectMode(mode: CaptureMode) {
            if (cameraState.value.phase == CameraUiPhase.RECORDING) return
            val current = cameraState.value
            if (current.modeGates[mode] != com.librestatic.opencinecam.ModeGateState.AVAILABLE) {
                cameraState.value = current.copy(message = getString(R.string.gate_requires_preflight, modeDisplayName(mode)))
                return
            }
            val changedSignalPath =
                (current.selectedMode == CaptureMode.LOG) != (mode == CaptureMode.LOG) ||
                    (current.selectedMode in CameraUiState.videoProfileModes) != (mode in CameraUiState.videoProfileModes)
            val descriptor = current.descriptor
            val selectedLogProfile = descriptor?.let {
                normalizedLogProfile(it, geometrySeeds.logWidth, geometrySeeds.logHeight, geometrySeeds.logFps)
            }.takeIf { mode == CaptureMode.LOG }
            val selectedVideoProfile = descriptor?.let {
                normalizedVideoProfile(it, geometrySeeds.width(mode), geometrySeeds.height(mode), geometrySeeds.fps(mode))
            }.takeIf { mode in CameraUiState.videoProfileModes }
            val selectedWidth = selectedLogProfile?.size?.width ?: selectedVideoProfile?.size?.width
                ?: geometrySeeds.width(mode)
            val selectedHeight = selectedLogProfile?.size?.height ?: selectedVideoProfile?.size?.height
                ?: geometrySeeds.height(mode)
            val targetFps = descriptor?.let {
                normalizedTargetFps(it, mode, geometrySeeds.fps(mode), selectedWidth, selectedHeight)
            }
                ?: geometrySeeds.fps(mode)
            if (mode == CaptureMode.LOG) {
                previewEngine.setTorchEnabled(false)
                cameraState.value = cameraState.value.copy(
                    selectedMode = mode,
                    targetFps = targetFps,
                    targetVideoWidth = selectedWidth,
                    targetVideoHeight = selectedHeight,
                    effectiveFps = null,
                    torchEnabled = false,
                    message = getString(R.string.log_flash_forced_off),
                )
            } else {
                val torchAllowed = settings.flashEnabled && cameraState.value.descriptor?.flashAvailable == true
                previewEngine.setTorchEnabled(torchAllowed)
                cameraState.value = cameraState.value.copy(
                    selectedMode = mode,
                    targetFps = targetFps,
                    targetVideoWidth = selectedWidth,
                    targetVideoHeight = selectedHeight,
                    effectiveFps = null,
                    torchEnabled = torchAllowed,
                    message = null,
                )
            }
            if (changedSignalPath) {
                val descriptor = cameraState.value.descriptor
                val surface = attachedPreviewSurface
                if (descriptor != null && surface?.isValid == true) {
                    cameraState.value = cameraState.value.copy(phase = CameraUiPhase.OPENING)
                    previewEngine.startPreview(
                        descriptor,
                        surface,
                        attachedPreviewRotationDegrees,
                        previewListener,
                        openCineLog = mode == CaptureMode.LOG,
                        viewAssist = settings.logViewAssistEnabled,
                        targetFps = cameraState.value.targetFps,
                        videoProfile = cameraState.value.activeVideoProfile.takeIf {
                            mode in CameraUiState.videoProfileModes
                        },
                        logProfile = cameraState.value.activeLogProfile.takeIf { mode == CaptureMode.LOG },
                    )
                }
            }
        }

        fun selectTargetFps(fps: Int) {
            val current = cameraState.value
            if (current.phase == CameraUiPhase.RECORDING) return
            if (fps == current.targetFps) return
            val descriptor = current.descriptor ?: return
            var effectiveFps = fps
            val transient: Boolean
            when (current.selectedMode) {
                CaptureMode.LOG -> {
                    val snap = VideoGeometryPolicy.snapLog(
                        descriptor.logProfiles.map { it.toSpec() },
                        current.targetVideoWidth,
                        current.targetVideoHeight,
                        fps,
                        Camera2PreviewEngine.DEFAULT_TARGET_FPS,
                    )
                    val selected = snap.profile ?: return
                    effectiveFps = selected.fps
                    transient = snap.snapped
                }
                in CameraUiState.videoProfileModes -> {
                    val selected = VideoGeometryPolicy.snapVideo(
                        descriptor.videoProfiles.map { it.toSpec() },
                        current.targetVideoWidth,
                        current.targetVideoHeight,
                        fps,
                        Camera2PreviewEngine.DEFAULT_TARGET_FPS,
                    ) ?: return
                    effectiveFps = selected.fps
                    transient = selected.fps != fps
                }
                else -> return
            }
            cameraState.value = current.copy(
                phase = if (attachedPreviewSurface?.isValid == true) CameraUiPhase.OPENING else current.phase,
                targetFps = effectiveFps,
                effectiveFps = null,
                messageTransient = transient,
                message = fpsUnavailableNotice(transient, current.selectedMode, fps, effectiveFps)
                    ?: "Target FPS $effectiveFps · fixed Camera2 range $effectiveFps–$effectiveFps",
            )
            val surface = attachedPreviewSurface?.takeIf { it.isValid } ?: return
            val reopen = {
                if (cameraState.value.targetFps == effectiveFps && surface.isValid) {
                    previewEngine.startPreview(
                        descriptor,
                        surface,
                        attachedPreviewRotationDegrees,
                        previewListener,
                        openCineLog = current.selectedMode == CaptureMode.LOG,
                        viewAssist = settings.logViewAssistEnabled,
                        targetFps = effectiveFps,
                        videoProfile = cameraState.value.activeVideoProfile.takeIf {
                            current.selectedMode in CameraUiState.videoProfileModes
                        },
                        logProfile = cameraState.value.activeLogProfile.takeIf {
                            current.selectedMode == CaptureMode.LOG
                        },
                    )
                }
            }
            if (cameraState.value.activeVideoProfile?.constrainedHighSpeed == true ||
                cameraState.value.activeLogProfile?.constrainedHighSpeed == true
            ) {
                // Give Compose/SurfaceFlinger time to apply the active panel's 120/165 Hz vote
                // before CameraService starts the constrained high-speed stream.
                mainHandler.postDelayed(reopen, 350L)
            } else reopen()
        }

        fun selectVideoResolution(width: Int, height: Int) {
            val current = cameraState.value
            if (current.phase == CameraUiPhase.RECORDING || current.selectedMode !in CameraUiState.resolutionProfileModes) return
            val descriptor = current.descriptor ?: return
            val selectedFps: Int
            val selectedHighSpeed: Boolean
            val sourceLabel: String
            var snappedFps = false
            if (current.selectedMode == CaptureMode.LOG) {
                val choices = descriptor.logProfiles.filter { it.size.width == width && it.size.height == height }
                if (choices.isEmpty()) return
                val snap = VideoGeometryPolicy.snapLog(
                    choices.map { it.toSpec() },
                    width,
                    height,
                    current.targetFps,
                    Camera2PreviewEngine.DEFAULT_TARGET_FPS,
                )
                val selected = snap.profile ?: return
                snappedFps = snap.snapped
                selectedFps = selected.fps
                selectedHighSpeed = choices.first { it.size.width == width && it.size.height == height && it.fps == selected.fps }.constrainedHighSpeed
                sourceLabel = if (selected.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                    "OCLog HFR · ISP-derived"
                } else "OCLog · HLG 10-bit"
            } else {
                val choices = descriptor.videoProfiles.filter { it.size.width == width && it.size.height == height }
                if (choices.isEmpty()) return
                val selected = VideoGeometryPolicy.snapVideo(
                    choices.map { it.toSpec() },
                    width,
                    height,
                    current.targetFps,
                    Camera2PreviewEngine.DEFAULT_TARGET_FPS,
                ) ?: return
                selectedFps = selected.fps
                selectedHighSpeed = choices.first { it.size.width == width && it.size.height == height && it.fps == selected.fps }.constrainedHighSpeed
                sourceLabel = if (current.selectedMode == CaptureMode.TIME_LAPSE) "Timelapse" else "Video"
            }
            cameraState.value = current.copy(
                phase = if (attachedPreviewSurface?.isValid == true) CameraUiPhase.OPENING else current.phase,
                targetVideoWidth = width,
                targetVideoHeight = height,
                targetFps = selectedFps,
                effectiveFps = null,
                messageTransient = snappedFps || selectedFps != current.targetFps,
                message = fpsUnavailableNotice(snappedFps || selectedFps != current.targetFps, current.selectedMode, current.targetFps, selectedFps)
                    ?: "$sourceLabel ${width}×$height · $selectedFps fps${if (selectedHighSpeed) " · HIGH SPEED" else ""}",
            )
            if (current.selectedMode == CaptureMode.LOG) {
                attachedPreviewSurface?.takeIf { it.isValid }?.let { surface ->
                    previewEngine.startPreview(
                        descriptor,
                        surface,
                        attachedPreviewRotationDegrees,
                        previewListener,
                        openCineLog = true,
                        viewAssist = settings.logViewAssistEnabled,
                        targetFps = selectedFps,
                        logProfile = cameraState.value.activeLogProfile,
                    )
                }
            }
            // Video waits for CameraScreen to resize its direct SurfaceView before reopening.
            // LOG renders through EGL into a layout-sized monitor Surface, so its camera-input
            // SurfaceTexture can be reopened immediately at the selected profile dimensions.
        }

        fun updateSettings(updated: CameraSettings) {
            settings = cameraState.value.audioCapabilities?.let(updated::normalizedFor) ?: updated
            geometrySeeds = GeometrySeeds(
                videoWidth = settings.videoWidth,
                videoHeight = settings.videoHeight,
                videoFps = settings.videoFps,
                logWidth = settings.logWidth,
                logHeight = settings.logHeight,
                logFps = settings.logFps,
            )
            val torchAllowed = updated.flashEnabled &&
                cameraState.value.selectedMode != CaptureMode.LOG &&
                cameraState.value.descriptor?.flashAvailable == true
            previewEngine.setTorchEnabled(torchAllowed)
            previewEngine.setOpenCineLogViewAssist(updated.logViewAssistEnabled)
            cameraState.value = cameraState.value.copy(torchEnabled = torchAllowed)
            if (cameraState.value.phase != CameraUiPhase.RECORDING) startPreviewAudioMonitorIfEligible()
        }

        fun resetAudioClip() {
            cameraState.value = cameraState.value.copy(audioClipLatched = false)
        }

        fun setManualExposure(iso: Int?, exposureTimeNs: Long?) {
            val current = cameraState.value
            cameraState.value = current.copy(requestedIso = iso, requestedExposureTimeNs = exposureTimeNs)
            previewEngine.setManualControls(iso, exposureTimeNs, current.requestedFocusDiopters)
        }

        fun setExposureCompensation(index: Int) {
            val current = cameraState.value
            val descriptor = current.descriptor
            val clamped = descriptor?.aeCompensationRange?.let { range ->
                index.coerceIn(range.lower, range.upper)
            } ?: return
            // Adjusting EV returns exposure to AE so the compensation can take effect.
            cameraState.value = current.copy(
                requestedAeCompensationIndex = clamped,
                requestedIso = null,
                requestedExposureTimeNs = null,
            )
            previewEngine.setManualControls(null, null, current.requestedFocusDiopters)
            previewEngine.setExposureCompensation(clamped)
        }

        fun setManualFocus(focusDiopters: Float?) {
            val current = cameraState.value
            cameraState.value = current.copy(
                requestedFocusDiopters = focusDiopters,
                tapFocusState = TapFocusState.IDLE,
            )
            previewEngine.setManualControls(current.requestedIso, current.requestedExposureTimeNs, focusDiopters)
        }

        fun setAeLock(enabled: Boolean) {
            val current = cameraState.value
            if (current.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING)) return
            previewEngine.setAeLock(enabled)
        }

        fun setAfLock(enabled: Boolean, behavior: AfLockBehavior) {
            val current = cameraState.value
            if (current.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING)) return
            previewEngine.setAfLock(enabled, behavior)
        }

        fun tapToFocus(normalizedX: Float, normalizedY: Float, meterExposure: Boolean): Boolean {
            val current = cameraState.value
            if (current.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING)) {
                return false
            }
            val accepted = previewEngine.tapToFocus(
                normalizedX.coerceIn(0f, 1f),
                normalizedY.coerceIn(0f, 1f),
                meterExposure,
            )
            if (accepted) {
                cameraState.value = current.copy(
                    requestedFocusDiopters = null,
                    tapFocusState = TapFocusState.SEARCHING,
                )
            }
            return accepted
        }

        fun setZoomRatio(ratio: Float) {
            val current = cameraState.value
            if (!current.zoomSupported) return
            val min = current.zoomMinRatio
            val max = current.zoomMaxRatio
            val coerced = ratio.coerceIn(min, max)
            cameraState.value = current.copy(zoomRatio = coerced, zoomEffectiveRatio = coerced)
            current.selectedCameraId?.let { perCameraZoom[it] = coerced }
            previewEngine.setZoomRatio(coerced)
        }

        fun selectZoomAnchor(ratio: Float) {
            val current = cameraState.value
            if (!current.zoomSupported) return
            val anchors = current.opticalAnchors
            val target = if (anchors.isNotEmpty()) ZoomMath.nearestAnchor(ratio, anchors).ratio else ratio
            setZoomRatio(target)
        }

        fun setWhiteBalance(selection: WhiteBalanceSelection) {
            val current = cameraState.value
            cameraState.value = current.copy(requestedWhiteBalance = selection)
            current.selectedCameraId?.let { perCameraWhiteBalance[it] = selection }
            previewEngine.setWhiteBalance(selection)
        }

        /** [audioForThisTake] overrides the persisted audio switch for one recording only. */
        fun capturePrimary(audioForThisTake: Boolean? = null): Boolean {
            val current = cameraState.value
            if (current.phase == CameraUiPhase.RECORDING && current.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.TIME_LAPSE, CaptureMode.LOG)) {
                return previewEngine.stopVideo()
            }
            if (current.phase != CameraUiPhase.PREVIEWING && current.phase != CameraUiPhase.SAVED) return false
            return when (current.selectedMode) {
                CaptureMode.PHOTO -> {
                    cameraState.value = current.copy(phase = CameraUiPhase.CAPTURING, message = null)
                    previewEngine.captureJpeg().also { accepted ->
                        if (!accepted) fail("jpeg-capture-not-ready", "The still capture surface is not ready.")
                    }
                }
                CaptureMode.RAW_PHOTO -> {
                    cameraState.value = current.copy(phase = CameraUiPhase.CAPTURING, message = null)
                    previewEngine.captureDng().also { accepted ->
                        if (!accepted) fail("raw-capture-not-ready", "The RAW capture surface is not ready.")
                    }
                }
                CaptureMode.BURST -> {
                    cameraState.value = current.copy(
                        phase = CameraUiPhase.CAPTURING,
                        burstCaptured = 0,
                        burstExpected = settings.burstCount,
                        message = getString(R.string.burst_progress, 0, settings.burstCount),
                    )
                    previewEngine.captureBurst(settings.burstCount).also { accepted ->
                        if (!accepted) fail("burst-not-ready", "Burst capture is not ready.")
                    }
                }
                CaptureMode.BRACKET -> {
                    cameraState.value = current.copy(phase = CameraUiPhase.CAPTURING, message = "Capturing −2 / 0 / +2 EV…")
                    previewEngine.captureBracket().also { accepted ->
                        if (!accepted) fail("bracket-not-ready", "Exposure bracket is not ready.")
                    }
                }
                CaptureMode.LIGHT_TRAIL -> {
                    cameraState.value = current.copy(phase = CameraUiPhase.CAPTURING, message = "Long exposure · 1 s…")
                    previewEngine.captureLongExposure().also { accepted ->
                        if (!accepted) fail("long-exposure-not-ready", "Long exposure is not ready.")
                    }
                }
                CaptureMode.VIDEO -> {
                    try {
                        stopPreviewAudioMonitor(clearLevels = false)
                        cameraState.value = cameraState.value.copy(audioClipLatched = false, audioLevels = null)
                        val requestedAudioEnabled = audioForThisTake ?: settings.audioEnabled
                        val orientation = physicalOrientationTracker.snapshot()?.degrees
                        if (orientation == null) {
                            fail("recording-orientation-unknown", "Hold the phone upright for a moment before recording to lock the file orientation.")
                            return false
                        }
                        val descriptor = current.descriptor
                        val sourceSize = current.activeVideoProfile?.size ?: descriptor?.previewSize
                        if (sourceSize == null || descriptor == null) {
                            fail("recording-geometry-unavailable", "Recording geometry is not available yet.")
                            return false
                        }
                        val geometry = RecordingGeometryCalculator.calculate(
                            sourceSize = sourceSize,
                            sensorOrientationDegrees = descriptor.sensorOrientation,
                            deviceOrientationDegrees = orientation,
                            lensFacing = descriptor.lensFacing,
                            mode = settings.recordingGeometryMode,
                        )
                        activeRecordingGeometry = geometry
                        if (requestedAudioEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            fail("microphone-permission-required", "Grant microphone permission or disable audio before recording.")
                            return false
                        }
                        val audioSettings = cameraState.value.audioCapabilities
                            ?.let(settings::normalizedFor)
                            ?: settings
                        val audioRequested = requestedAudioEnabled
                        val embeddedAudio = if (audioRequested && audioSettings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
                            Camera2EmbeddedAudioConfig(
                                source = audioSettings.audioSource.androidSource,
                                sampleRateHz = audioSettings.audioSampleRateHz,
                                channels = audioSettings.audioChannels,
                                bitrateBps = audioSettings.audioBitrateKbps * 1_000,
                                preferredInputDeviceId = audioSettings.audioInputDeviceId,
                                enableAutomaticGainControl = audioSettings.automaticGainControlEnabled,
                                onAudioLevel = ::publishAudioLevel,
                            )
                        } else null
                        when (val start = foreground.start(audioRequested)) {
                            is ForegroundRecordingStart.Rejected -> {
                                fail("recording-foreground-failed", start.failure.userMessage)
                                return false
                            }
                            is ForegroundRecordingStart.Started -> startService(Intent(this@CaptureService, CaptureService::class.java))
                        }
                        val output = VideoOutput.create(this@CaptureService)
                        videoOutput = output
                        audioSidecarRecorder = when {
                            !audioRequested -> null
                            audioSettings.audioOutputFormat == AudioOutputFormat.WAV_PCM ->
                                WavAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings, ::publishAudioLevel)
                            audioSettings.audioOutputFormat == AudioOutputFormat.FLAC ->
                                FlacAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings, ::publishAudioLevel)
                            else -> null
                        }
                        activeRecordingAudioLabel = when {
                            !audioRequested -> "no audio"
                            embeddedAudio != null -> "AAC ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioBitrateKbps} kbps · ${audioSettings.audioChannels} ch"
                            else -> "${audioSettings.audioOutputFormat.name.substringBefore('_')} ${audioSettings.audioBitDepth.bits}-bit · ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioChannels} ch"
                        }
                        cameraState.value = current.copy(phase = CameraUiPhase.CAPTURING, message = "Preparing H.264 · $activeRecordingAudioLabel…")
                        previewEngine.startVideo(
                            output.descriptor,
                            embeddedAudio,
                            recordingGeometry = geometry,
                            videoBitrate = settings.videoBitrateMbps * 1_000_000,
                        ).also { accepted ->
                            if (!accepted) {
                                videoOutput = null
                                audioSidecarRecorder?.finish(false)
                                audioSidecarRecorder = null
                                output.finish(false)
                                foreground.stop()
                                fail("video-capture-not-ready", "The video surface is not ready.")
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { audioSidecarRecorder?.finish(false) }
                        audioSidecarRecorder = null
                        runCatching { videoOutput?.finish(false) }
                        videoOutput = null
                        foreground.stop()
                        fail("video-output-failed", failure.message ?: "Video output could not be created.")
                        false
                    }
                }
                CaptureMode.LOG -> {
                    try {
                        stopPreviewAudioMonitor(clearLevels = false)
                        cameraState.value = cameraState.value.copy(audioClipLatched = false, audioLevels = null)
                        val requestedAudioEnabled = audioForThisTake ?: settings.audioEnabled
                        val orientation = physicalOrientationTracker.snapshot()?.degrees
                        if (orientation == null) {
                            fail("recording-orientation-unknown", "Hold the phone upright for a moment before recording to lock the file orientation.")
                            return false
                        }
                        val descriptor = current.descriptor
                        val sourceSize = current.activeLogProfile?.size
                            ?: descriptor?.preferredLogProfile?.size
                        if (sourceSize == null || descriptor == null) {
                            fail("recording-geometry-unavailable", "LOG geometry is not available yet.")
                            return false
                        }
                        val geometry = RecordingGeometryCalculator.calculate(
                            sourceSize = sourceSize,
                            sensorOrientationDegrees = descriptor.sensorOrientation,
                            deviceOrientationDegrees = orientation,
                            lensFacing = descriptor.lensFacing,
                            mode = settings.recordingGeometryMode,
                        )
                        activeRecordingGeometry = geometry
                        if (requestedAudioEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            fail("microphone-permission-required", "Grant microphone permission or disable audio before recording.")
                            return false
                        }
                        val audioSettings = cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings
                        val embeddedAudio = if (requestedAudioEnabled && audioSettings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
                            Camera2EmbeddedAudioConfig(
                                source = audioSettings.audioSource.androidSource,
                                sampleRateHz = audioSettings.audioSampleRateHz,
                                channels = audioSettings.audioChannels,
                                bitrateBps = audioSettings.audioBitrateKbps * 1_000,
                                preferredInputDeviceId = audioSettings.audioInputDeviceId,
                                enableAutomaticGainControl = audioSettings.automaticGainControlEnabled,
                                onAudioLevel = ::publishAudioLevel,
                            )
                        } else null
                        when (val start = foreground.start(requestedAudioEnabled)) {
                            is ForegroundRecordingStart.Rejected -> {
                                fail("recording-foreground-failed", start.failure.userMessage)
                                return false
                            }
                            is ForegroundRecordingStart.Started -> startService(Intent(this@CaptureService, CaptureService::class.java))
                        }
                        val output = VideoOutput.create(this@CaptureService)
                        videoOutput = output
                        audioSidecarRecorder = when {
                            !requestedAudioEnabled || embeddedAudio != null -> null
                            audioSettings.audioOutputFormat == AudioOutputFormat.WAV_PCM ->
                                WavAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings, ::publishAudioLevel)
                            audioSettings.audioOutputFormat == AudioOutputFormat.FLAC ->
                                FlacAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings, ::publishAudioLevel)
                            else -> error("Unsupported LOG audio format ${audioSettings.audioOutputFormat}.")
                        }
                        activeRecordingAudioLabel = when {
                            !requestedAudioEnabled -> "no audio"
                            embeddedAudio != null -> "AAC ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioBitrateKbps} kbps · ${audioSettings.audioChannels} ch"
                            else -> "${audioSettings.audioOutputFormat.name.substringBefore('_')} ${audioSettings.audioBitDepth.bits}-bit · ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioChannels} ch"
                        }
                        cameraState.value = current.copy(
                            phase = CameraUiPhase.CAPTURING,
                            message = if (current.activeLogProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                                "Preparing OCLog2 HFR · ${current.targetFps} fps · ISP SDR → GLES → HEVC Main10 · source depth not claimed · $activeRecordingAudioLabel…"
                            } else {
                                "Preparing OCLog2 · ${current.targetFps} fps · HLG10 → scene-linear BT.2020 → HEVC Main10 · $activeRecordingAudioLabel…"
                            },
                        )
                        previewEngine.startOpenCineLogVideo(
                            output.descriptor,
                            recordingGeometry = geometry,
                            videoBitrate = settings.videoBitrateMbps * 1_000_000,
                            audio = embeddedAudio,
                        ).also { accepted ->
                            if (!accepted) {
                                videoOutput = null
                                audioSidecarRecorder?.finish(false)
                                audioSidecarRecorder = null
                                output.finish(false)
                                foreground.stop()
                                if (cameraState.value.phase != CameraUiPhase.ERROR) {
                                    fail("log-capture-not-ready", "The verified OCLog2 pipeline is not ready.")
                                }
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { audioSidecarRecorder?.finish(false) }
                        audioSidecarRecorder = null
                        runCatching { videoOutput?.finish(false) }
                        videoOutput = null
                        foreground.stop()
                        fail("log-output-failed", failure.message ?: "OCLog2 output could not be created.")
                        false
                    }
                }
                CaptureMode.TIME_LAPSE -> {
                    try {
                        when (val start = foreground.start(false)) {
                            is ForegroundRecordingStart.Rejected -> {
                                fail("recording-foreground-failed", start.failure.userMessage)
                                return false
                            }
                            is ForegroundRecordingStart.Started -> startService(Intent(this@CaptureService, CaptureService::class.java))
                        }
                        val output = VideoOutput.create(this@CaptureService)
                        videoOutput = output
                        val captureRate = 1000.0 / settings.timelapseIntervalMs
                        cameraState.value = current.copy(
                            phase = CameraUiPhase.CAPTURING,
                            message = getString(R.string.timelapse_preparing, captureRate, current.targetFps),
                            timelapseIntervalMs = settings.timelapseIntervalMs,
                            timelapseLimitMode = settings.timelapseLimitMode,
                            timelapseFrameCount = settings.timelapseFrameCount,
                            timelapseDurationMs = settings.timelapseDurationMs,
                            timelapseFramesCaptured = 0,
                        )
                        previewEngine.startVideo(
                            output.descriptor,
                            null,
                            captureRate = captureRate,
                            videoBitrate = settings.videoBitrateMbps * 1_000_000,
                        ).also { accepted ->
                            if (!accepted) {
                                videoOutput = null
                                output.finish(false)
                                foreground.stop()
                                fail("timelapse-not-ready", "The timelapse surface is not ready.")
                            }
                        }
                    } catch (failure: Throwable) {
                        foreground.stop()
                        fail("timelapse-output-failed", failure.message ?: "Timelapse output could not be created.")
                        false
                    }
                }
                else -> {
                    val gate = current.modeGates.getValue(current.selectedMode)
                    cameraState.value = current.copy(
                        message = if (gate == com.librestatic.opencinecam.ModeGateState.AVAILABLE) {
                            getString(R.string.mode_integrating, modeDisplayName(current.selectedMode))
                        } else {
                            getString(R.string.gate_requires_preflight, modeDisplayName(current.selectedMode))
                        },
                    )
                    false
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> foreground.start(intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, true))
            ACTION_STOP_RECORDING -> if (!previewEngine.stopVideo()) foreground.stop()
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean = true

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopPreviewAudioMonitor(clearLevels = true)
        foreground.stop()
        runCatching { audioSidecarRecorder?.finish(false) }
        audioSidecarRecorder = null
        runCatching { videoOutput?.finish(false) }
        videoOutput = null
        activeRecordingGeometry = null
        previewEngine.close()
        if (::physicalOrientationTracker.isInitialized) physicalOrientationTracker.close()
        storageExecutor.shutdownNow()
        actor.close()
        super.onDestroy()
    }

    private fun fail(code: String, message: String) {
        cameraState.value = cameraState.value.copy(
            phase = CameraUiPhase.ERROR,
            errorCode = code,
            message = message,
        )
    }

    private fun logSidecarJson(evidence: OpenCineLogRecordingEvidence): String = JSONObject()
        .put("schema", "opencinecam-oclog-sidecar-v2")
        .put("createdAtEpochMs", System.currentTimeMillis())
        .put("device", JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("fingerprint", Build.FINGERPRINT)
            .put("sdk", Build.VERSION.SDK_INT))
        .put("cameraId", cameraState.value.selectedCameraId)
        .put("provenance", evidence.provenance.name)
        .put("source", JSONObject()
            .put("path", evidence.sourcePath.name)
            .put("surface", evidence.sourceSurface)
            .put("dynamicRange", evidence.sourceDynamicRange)
            .put("colorSpace", evidence.sourceColorSpace)
            .put("transfer", evidence.sourceTransfer)
            .put("precision", evidence.sourcePrecision)
            .put("androidDataSpace", evidence.sourceDataSpace ?: JSONObject.NULL)
            .put(
                "interpretation",
                if (evidence.sourcePath == OpenCineLogSourcePath.HLG10_BT2020) {
                    "HLG OETF decoded to scene-linear BT.2020 in highp GLES"
                } else {
                    "Standard-range ISP output interpreted with the BT.709 video transfer, linearized, and converted to BT.2020 in highp GLES; runtime dataspace is recorded and no source gamut, 10-bit, or HDR highlight claim is made"
                },
            ))
        .put("transform", JSONObject()
            .put("curve", evidence.curve)
            .put("version", evidence.curveVersion)
            .put("domain", "scene-linear")
            .put("gamut", evidence.gamut)
            .put("range", evidence.range)
            .put("shaderSha256", evidence.transformSha256))
        .put("encoding", JSONObject()
            .put("mime", evidence.codecMime)
            .put("profile", evidence.codecProfile)
            .put("codecName", evidence.codecName)
            .put("eglRenderTargetBits", evidence.eglRenderTargetBits)
            .put("colorPrimaries", evidence.gamut)
            .put("transfer", "linear-container-surrogate; OCLog2 sidecar authoritative")
            .put("range", evidence.range))
        .put("geometry", JSONObject()
            .put("mode", evidence.geometryMode.name)
            .put("deviceOrientationDegrees", evidence.deviceOrientationDegrees)
            .put("pixelRotationDegrees", evidence.pixelRotationDegrees)
            .put("rendererRotationDegrees", evidence.rendererRotationDegrees)
            .put("containerRotationDegrees", evidence.containerRotationDegrees)
            .put("encodedWidth", evidence.encodedWidth)
            .put("encodedHeight", evidence.encodedHeight)
            .put("displayWidth", evidence.displayWidth)
            .put("displayHeight", evidence.displayHeight)
            .put("sarWidth", evidence.pixelAspectRatioWidth)
            .put("sarHeight", evidence.pixelAspectRatioHeight))
        .put("monitoring", JSONObject()
            .put("viewAssist", "Rec.709 monitoring-only")
            .put("flatPreview", "OCLog2 after BT.2020-to-Rec.709 display-gamut conversion and bounded saturation compression")
            .put("bakedIntoRecording", false))
        .put("encodedFrames", evidence.encodedFrames)
        .put("targetFps", evidence.targetFps)
        .put("firstPtsUs", evidence.firstPtsUs)
        .put("lastPtsUs", evidence.lastPtsUs)
        .put(
            "qualification",
            if (evidence.sourcePath == OpenCineLogSourcePath.HLG10_BT2020) {
                "runtime HLG10 graph provenance recorded; file/effective-precision verification required"
            } else {
                "runtime ISP-derived HFR graph recorded; Main10 output does not imply 10-bit source precision"
            },
        )
        .toString(2)

    private fun fpsUnavailableNotice(transient: Boolean, mode: CaptureMode, requestedFps: Int, effectiveFps: Int): String? {
        if (!transient) return null
        val ispNote = if (cameraState.value.activeLogProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) " · ISP-derived" else ""
        return "$requestedFps fps unavailable at this resolution · using $effectiveFps fps$ispNote"
    }

    private fun modeDisplayName(mode: CaptureMode): String = when (mode) {
        CaptureMode.PHOTO -> getString(R.string.photo_mode)
        CaptureMode.RAW_PHOTO -> getString(R.string.raw_photo_mode)
        CaptureMode.BURST -> getString(R.string.burst_mode)
        CaptureMode.VIDEO -> getString(R.string.video_mode)
        CaptureMode.SLOW_MOTION -> getString(R.string.slow_motion_mode)
        CaptureMode.TIME_LAPSE -> getString(R.string.time_lapse_mode)
        CaptureMode.BRACKET -> getString(R.string.bracket_mode)
        CaptureMode.LIGHT_TRAIL -> getString(R.string.light_trail_mode)
        CaptureMode.LOG -> getString(R.string.log_mode)
        CaptureMode.APV -> getString(R.string.apv_mode)
        CaptureMode.RAW_VIDEO -> getString(R.string.raw_video_mode)
    }

    private fun normalizedTargetFps(
        descriptor: Camera2CameraDescriptor,
        mode: CaptureMode,
        requested: Int,
        videoWidth: Int = 1920,
        videoHeight: Int = 1080,
    ): Int {
        val supported = when {
            mode == CaptureMode.LOG -> VideoGeometryPolicy.supportedLogFps(descriptor.logProfiles.map { it.toSpec() }, videoWidth, videoHeight)
            mode in CameraUiState.videoProfileModes -> VideoGeometryPolicy.supportedFps(descriptor.videoProfiles.map { it.toSpec() }, videoWidth, videoHeight)
            else -> descriptor.availableFixedFps
        }
        return when {
            requested in supported -> requested
            Camera2PreviewEngine.DEFAULT_TARGET_FPS in supported -> Camera2PreviewEngine.DEFAULT_TARGET_FPS
            supported.isNotEmpty() -> supported.minBy { kotlin.math.abs(it - requested) }
            else -> Camera2PreviewEngine.DEFAULT_TARGET_FPS
        }
    }

    private fun normalizedVideoProfile(
        descriptor: Camera2CameraDescriptor,
        width: Int,
        height: Int,
        fps: Int,
    ) = descriptor.videoProfiles.firstOrNull {
        it.size.width == width && it.size.height == height && it.fps == fps
    } ?: descriptor.videoProfiles.firstOrNull {
        it.size.width == width && it.size.height == height && it.fps == Camera2PreviewEngine.DEFAULT_TARGET_FPS
    } ?: descriptor.videoProfiles.firstOrNull {
        it.size.width == 1920 && it.size.height == 1080 && it.fps == Camera2PreviewEngine.DEFAULT_TARGET_FPS
    } ?: descriptor.videoProfiles.minWithOrNull(
        compareBy<com.librestatic.opencinecam.camera.Camera2VideoProfile> {
            kotlin.math.abs(it.size.width.toLong() * it.size.height - width.toLong() * height)
        }.thenBy { kotlin.math.abs(it.fps - fps) },
    )

    private fun normalizedLogProfile(
        descriptor: Camera2CameraDescriptor,
        width: Int,
        height: Int,
        fps: Int,
    ) = descriptor.logProfiles.firstOrNull {
        it.size.width == width && it.size.height == height && it.fps == fps
    } ?: descriptor.logProfiles.firstOrNull {
        it.size.width == width && it.size.height == height &&
            it.fps == Camera2PreviewEngine.DEFAULT_TARGET_FPS &&
            it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020
    } ?: descriptor.logProfiles.firstOrNull {
        it.size.width == 1920 && it.size.height == 1080 &&
            it.fps == Camera2PreviewEngine.DEFAULT_TARGET_FPS &&
            it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020
    } ?: descriptor.logProfiles.minWithOrNull(
        compareBy<com.librestatic.opencinecam.camera.Camera2LogProfile> {
            if (it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020) 0 else 1
        }.thenBy {
            kotlin.math.abs(it.size.width.toLong() * it.size.height - width.toLong() * height)
        }.thenBy { kotlin.math.abs(it.fps - fps) },
    )

    companion object {
        private const val OWNER_ID = "capture-service"
        private const val RECORDING_TICK_MS = 500L
        private const val CAMERA_SWITCH_TIMEOUT_MS = 6_000L
        private const val PREVIEW_RECOVERY_TIMEOUT_MS = 4_000L
    }
}
