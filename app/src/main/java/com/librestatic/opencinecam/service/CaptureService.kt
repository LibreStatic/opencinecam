/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.CapturedAccumulation
import com.librestatic.opencinecam.camera.CapturedBurst
import com.librestatic.opencinecam.camera.CapturedBracket
import com.librestatic.opencinecam.camera.CapturedStill
import com.librestatic.opencinecam.camera.StillPhotoFormat
import com.librestatic.opencinecam.camera.StillImageKind
import com.librestatic.opencinecam.camera.TimelapseCapture
import com.librestatic.opencinecam.camera.TimelapseProgress

import com.librestatic.opencinecam.fpsLockActive
import com.librestatic.opencinecam.lowLightFpsActive
import com.librestatic.opencinecam.recordingTimecodeJson
import com.librestatic.opencinecam.reviewBlockedByTake
import com.librestatic.opencinecam.camera.RecordingTimecodeReport
import com.librestatic.opencinecam.captureEpochJson
import com.librestatic.opencinecam.OperatorAction
import com.librestatic.opencinecam.forOperatorStartup
import com.librestatic.opencinecam.startupCaptureMode
import com.librestatic.opencinecam.operatorActionAvailable

import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ExposureCapabilities
import com.librestatic.opencinecam.camera.CaptureFrameRate
import com.librestatic.opencinecam.camera.ShutterUnit
import com.librestatic.opencinecam.camera.resolve
import com.librestatic.opencinecam.CaptureActionTicket
import com.librestatic.opencinecam.CaptureCountdown

import com.librestatic.opencinecam.SubjectPreviewPort
import com.librestatic.opencinecam.SubjectSurfaceRegistry
import com.librestatic.opencinecam.subjectPreviewBlock
import com.librestatic.opencinecam.wantsCameraPreview
import com.librestatic.opencinecam.camera.SubjectPreviewOptions
import com.librestatic.opencinecam.camera.SubjectPreviewStatus

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Environment
import android.os.StatFs
import android.util.Size
import android.view.Surface
import com.librestatic.opencinecam.timelapseProjectRate
import com.librestatic.opencinecam.videoProjectRate
import com.librestatic.opencinecam.projectLabel
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.HardwareTruthHudState
import com.librestatic.opencinecam.hardwareTruthTake
import com.librestatic.opencinecam.TakeVideoEncoder
import com.librestatic.opencinecam.withHardwareTruth
import com.librestatic.opencinecam.RouteTruth
import com.librestatic.opencinecam.routeTruthOf
import com.librestatic.opencinecam.readbackSamples
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.DeviceQuirksProvider
import com.librestatic.opencinecam.logProfilesFor
import com.librestatic.opencinecam.usesLogGraph
import com.librestatic.opencinecam.TimeLapseLimitMode
import com.librestatic.opencinecam.normalizedFor
import com.librestatic.opencinecam.toSpec
import com.librestatic.opencinecam.VideoGeometryPolicy
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.Camera2PreviewListener
import com.librestatic.opencinecam.camera.Camera2PreviewMetadata
import com.librestatic.opencinecam.camera.RecordingWhiteBalancePolicy
import com.librestatic.opencinecam.camera.RecordingWhiteBalanceStatus
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import com.librestatic.opencinecam.camera.adaptTo
import com.librestatic.opencinecam.camera.Camera2Analysis
import com.librestatic.opencinecam.recordingGainJson
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.camera.Camera2EmbeddedAudioConfig
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.AfLockBehavior
import com.librestatic.opencinecam.camera.LockState
import com.librestatic.opencinecam.camera.FocusPullEasing
import com.librestatic.opencinecam.camera.SmpteTimecode
import com.librestatic.opencinecam.camera.TimecodeRate
import com.librestatic.opencinecam.camera.TimecodeMode
import com.librestatic.opencinecam.camera.TimecodeTracker
import com.librestatic.opencinecam.camera.HdrVideoFormat
import com.librestatic.opencinecam.camera.Hdr10Video
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingGeometry
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.RecordingFrameSize
import com.librestatic.opencinecam.camera.TapFocusState
import com.librestatic.opencinecam.camera.ZoomAnchor
import com.librestatic.opencinecam.camera.ZoomMath
import com.librestatic.opencinecam.core.model.CaptureCommand
import com.librestatic.opencinecam.core.model.CaptureState
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.media.audio.AvClockDiagnostics
import com.librestatic.opencinecam.media.video.softwareVideoEncoderDisclosure
import com.librestatic.opencinecam.core.model.StableFailure
import com.librestatic.opencinecam.core.model.CaptureStateMachine
import com.librestatic.opencinecam.core.model.SerializedCaptureActor
import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.afterSavedProductionSlate
import com.librestatic.opencinecam.storage.StillImageSaver
import com.librestatic.opencinecam.RawVideoConfigKeys
import com.librestatic.opencinecam.captureFileStem
import com.librestatic.opencinecam.continuationFileStem
import com.librestatic.opencinecam.RawVideoConfigOption
import com.librestatic.opencinecam.rawConfigLabelLocalized
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoQualificationSet
import com.librestatic.opencinecam.camera.RawVideoReasons
import com.librestatic.opencinecam.camera.RawVideoSessionConfig
import com.librestatic.opencinecam.rawvideo.AudioRecordRawAudioSource
import com.librestatic.opencinecam.rawvideo.RawAudioSource
import com.librestatic.opencinecam.rawvideo.RawRingBudget
import com.librestatic.opencinecam.rawvideo.RawTakeAudio
import com.librestatic.opencinecam.rawvideo.RawTakeInfo
import com.librestatic.opencinecam.rawvideo.RawTakeResult
import com.librestatic.opencinecam.rawvideo.RawVideoRecorder
import com.librestatic.opencinecam.storage.RawVideoOutput
import com.librestatic.opencinecam.storage.RawVideoQualificationStore
import com.librestatic.opencinecam.storage.RawVideoRecovery
import com.librestatic.opencinecam.storage.RawVideoRecoveryReport
import com.librestatic.opencinecam.storage.MotionLogRecorder
import com.librestatic.opencinecam.storage.MotionLogTarget
import com.librestatic.opencinecam.storage.VideoOutput
import com.librestatic.opencinecam.storage.DriveDisconnectPolicy
import com.librestatic.opencinecam.storage.RecordingDestination
import com.librestatic.opencinecam.storage.RecordingDestinationStore
import com.librestatic.opencinecam.storage.RecordingVolumeEvent
import com.librestatic.opencinecam.storage.RecordingVolumeMonitor
import com.librestatic.opencinecam.storage.releaseDriveDescriptors
import com.librestatic.opencinecam.storage.ResolvedRecordingDestination
import com.librestatic.opencinecam.storage.driveFreeBytes
import com.librestatic.opencinecam.storage.IncompleteTake
import com.librestatic.opencinecam.storage.ClipContinuation
import com.librestatic.opencinecam.storage.ClipIntent
import com.librestatic.opencinecam.storage.ClipSidecarProvider
import com.librestatic.opencinecam.storage.clipSidecarProvider
import com.librestatic.opencinecam.storage.finalizePreparedRecordingTake
import com.librestatic.opencinecam.transfers.CapturePublicationJournal
import com.librestatic.opencinecam.transfers.CaptureTransferReservation
import com.librestatic.opencinecam.transfers.WebDavTransferRuntime
import com.librestatic.opencinecam.storage.AudioRetirementGate
import java.util.concurrent.atomic.AtomicReference
import com.librestatic.opencinecam.transfers.CaptureTransferPublication
import com.librestatic.opencinecam.storage.AudioSidecarRecorder
import com.librestatic.opencinecam.storage.FlacAudioSidecarRecorder
import com.librestatic.opencinecam.storage.WavAudioSidecarRecorder
import com.librestatic.opencinecam.media.audio.AndroidProfessionalAudioProbe
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.ActiveAudioInput
import com.librestatic.opencinecam.media.audio.AudioInputKey
import com.librestatic.opencinecam.media.audio.AudioInputLossAction
import com.librestatic.opencinecam.media.audio.AudioInputLossPolicy
import com.librestatic.opencinecam.media.audio.AudioInputRouteGuard
import com.librestatic.opencinecam.media.audio.audioInputKind
import com.librestatic.opencinecam.audioInputTypeLabel
import com.librestatic.opencinecam.audioInputUnavailable
import com.librestatic.opencinecam.resolveAudioInputSelection
import com.librestatic.opencinecam.camera.PcmListeningSink
import com.librestatic.opencinecam.AudioListeningStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.Locale
import androidx.annotation.StringRes
import com.librestatic.opencinecam.CameraPreset
import com.librestatic.opencinecam.CameraPresetCodec
import com.librestatic.opencinecam.withLivePreferencesFrom
import com.librestatic.opencinecam.LutLibraries
import com.librestatic.opencinecam.camera.monitorLutIdentity
import com.librestatic.opencinecam.camera.MonitorLut
import com.librestatic.opencinecam.camera.BakedLutEvidence
import com.librestatic.opencinecam.recordingLutJson
import com.librestatic.opencinecam.camera.OperatorLutStatus
import com.librestatic.opencinecam.camera.OperatorLutState
import com.librestatic.opencinecam.SettingsRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import com.librestatic.opencinecam.batteryPercentOf
import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.rawQualificationOutcome
import com.librestatic.opencinecam.RawQualificationOutcomeKind
import com.librestatic.opencinecam.RawQualificationRefusals
import com.librestatic.opencinecam.rawQualificationRunAbort
import com.librestatic.opencinecam.rawQualificationShouldPost
import com.librestatic.opencinecam.rawQualificationGateQualifiable
import com.librestatic.opencinecam.rawQualificationStartRefusal
import com.librestatic.opencinecam.rawQualificationStorageBusy
import com.librestatic.opencinecam.RawQualificationStopReasons
import com.librestatic.opencinecam.rawQualificationUiOf
import com.librestatic.opencinecam.rawVideoModeFallback
import com.librestatic.opencinecam.RawVideoQualificationInfo
import com.librestatic.opencinecam.RawVideoQualificationUi
import com.librestatic.opencinecam.readRawQualificationConditions
import com.librestatic.opencinecam.rawvideo.RawQualificationEstimates
import com.librestatic.opencinecam.rawvideo.RawQualificationPhase
import com.librestatic.opencinecam.rawvideo.RawQualificationPlan
import com.librestatic.opencinecam.rawvideo.RawQualificationProgress
import com.librestatic.opencinecam.rawvideo.RawQualificationRisk
import com.librestatic.opencinecam.rawvideo.RawQualificationTiming
import com.librestatic.opencinecam.rawvideo.RawQualificationSpecs
import com.librestatic.opencinecam.rawvideo.RawQualificationStorageModel
import com.librestatic.opencinecam.rawvideo.RawVideoQualificationOutcome
import com.librestatic.opencinecam.rawvideo.RawVideoQualifier
import com.librestatic.opencinecam.RawQualificationItem
import com.librestatic.opencinecam.rawQualificationItemsCameraChecked
import com.librestatic.opencinecam.rawQualificationItemsFinished
import com.librestatic.opencinecam.rawQualificationItemsOnProgress
import com.librestatic.opencinecam.rawQualificationItemsWith
import com.librestatic.opencinecam.rawQualificationPlanItems
import com.librestatic.opencinecam.rawQualificationRunStatus

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
    private val stateCommands = CaptureStateCommands(OWNER_ID)
    private val cameraState = MutableStateFlow(CameraUiState())
    private val lensCalibrationState = MutableStateFlow(com.librestatic.opencinecam.LensCalibrationUiState())
    private val localBinder = LocalBinder()
    private val foreground = RecordingForegroundController(this)
    private val previewEngine by lazy { CaptureStartup.adoptEngine(this) }
    private val stillSaver by lazy { StillImageSaver(this) }
    // Take-owned fields are written on main before dispatch and detached by engine callbacks.
    @Volatile private var videoOutput: VideoOutput? = null
    @Volatile private var latestRollingShutterSkewNs: Long? = null
    private val takeOwnershipLock = Any()
    private val recordingRecoveryGuards = java.util.concurrent.ConcurrentHashMap<VideoOutput, AutoCloseable>()

    private fun reserveRecordingRecoveryGuard(output: VideoOutput) {
        val guard = output.recoveryGroup.retainNativeWriter()
        if (recordingRecoveryGuards.putIfAbsent(output, guard) != null) {
            guard.close()
            error("Recording output already has a native guard")
        }
    }

    private fun releaseRecordingRecoveryGuard(output: VideoOutput?) {
        if (output == null) return
        recordingRecoveryGuards.remove(output)?.close()
    }

    private fun retireRecordingRecoveryGuards(receipt: java.util.concurrent.CompletableFuture<Unit>) {
        val guards = recordingRecoveryGuards.entries.map { it.key to it.value }
        receipt.whenComplete { _, failure ->
            if (failure == null) guards.forEach { (output, guard) ->
                if (recordingRecoveryGuards.remove(output, guard)) runCatching { guard.close() }
                    .onFailure { android.util.Log.e("RecordingRecovery", "Retired take cleanup retained for retry", it) }
            }
        }
    }
    @Volatile private var activeTransferPublication: CaptureTransferPublication? = null
    private var transferRuntime: WebDavTransferRuntime? = null
    private val transferCapture = AtomicReference<CaptureTransferReservation?>(null)
    private var transferPreparationToken = 0L
    private var transferWaiting = false
    private var transferPreparationJob: kotlinx.coroutines.Job? = null
    private var transferCaptureDispatched = false
    private val dispatchedTake = DispatchedTakeAdmission<CaptureTransferReservation>()
    @Volatile private var serviceDestroyed = false

    private fun transferCoordinator(): WebDavTransferRuntime = transferRuntime
        ?: WebDavTransferRuntime.get(this).also { transferRuntime = it }

    /** An error/timeout is not native retirement; callers choose a proven terminal boundary. */
    private fun releaseTransferCapture(reservation: CaptureTransferReservation?, onReleased: (() -> Unit)? = null) {
        if (reservation == null) return
        AudioRetirementGate.whenIdle().whenComplete { _, failure ->
            if (failure == null && transferCapture.compareAndSet(reservation, null)) {
                val intent = recordingLutIntent.get()
                if (intent?.owner === reservation && recordingLutIntent.compareAndSet(intent, null)) mainHandler.post {
                    if (!serviceDestroyed && recordingLutIntent.get() == null) {
                        cameraState.update { it.copy(recordingLutStatus = waitingRecordingLutStatus(), recordingLutSelectionPending = false) }
                        localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
                    }
                }
                reservation.close()
                onReleased?.invoke()
            }
        }
    }

    private fun dispatchTransferCapture() {
        transferCaptureDispatched = true
        dispatchedTake.dispatch(requireNotNull(transferCapture.get()) { "Recording reservation missing" })
        submitCaptureState(stateCommands.takeDispatched())
    }

    /** Actor state is reporting only: a full queue or a retired actor never blocks capture. */
    private fun submitCaptureState(commands: List<CaptureCommand>) {
        for (command in commands) {
            try {
                actor.submit(command).whenComplete { transition, error ->
                    if (error == null && transition != null) state.value = transition.current
                }
            } catch (_: RejectedExecutionException) {
                return
            }
        }
    }

    /**
     * A dispatched start that never reached RECORDING releases its exact admission once the
     * engine proves native retirement. A failed receipt (preparation still retiring) keeps it
     * held for onRecordingStopped, a later failure/recovery receipt, or engine closure.
     */
    private fun releaseFailedTakeStart(receipt: CompletableFuture<Unit>,
        dispatch: DispatchedTakeAdmission.Dispatch<CaptureTransferReservation>? = dispatchedTake.pendingStart()) {
        if (dispatch == null) return
        receipt.whenComplete { _, failure ->
            if (failure != null) return@whenComplete
            dispatchedTake.claimFailedStart(dispatch)?.let { reservation ->
                releaseTransferCapture(reservation) { startPreviewAudioMonitorIfEligible() }
            }
        }
    }

    /**
     * Opt-in motion log for plain video, LOG and HLG takes (never high speed, slow motion or time-lapse).
     * Silent by design: no gyroscope or no sensor access just means no log.
     */
    private fun attachMotionLog(output: VideoOutput, wanted: Boolean, descriptor: Camera2CameraDescriptor?, geometry: RecordingGeometry?) {
        if (!wanted || descriptor == null || geometry == null) return
        MotionLogRecorder.start(this, MotionLogTarget(
            videoFileName = output.displayName,
            sensorOrientationDegrees = descriptor.sensorOrientation,
            frontFacing = descriptor.lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT,
            storedPixelRotationDegrees = geometry.pixelRotationDegrees,
            timestampsSynced = descriptor.timestampSourceRealtime,
            rollingShutterSkewNs = latestRollingShutterSkewNs,
        ))?.let(output::attachMotionRecorder)
    }

    private class DetachedTake(
        val video: VideoOutput?,
        val audio: AudioSidecarRecorder?,
        val publication: CaptureTransferPublication?,
        val audioStartFailure: String?,
        val geometry: RecordingGeometry?,
        /** The take's link to the drive take it continues (PLAN-072 Delivery 4); null when it is not a continuation. */
        val continuation: ClipContinuation?,
    )

    /** Engine callbacks and main detach the take-owned outputs exactly once; the caller finishes them. */
    private fun detachTake(): DetachedTake = synchronized(takeOwnershipLock) {
        // Read before clearTakeDestination() below forgets it.
        DetachedTake(videoOutput, audioSidecarRecorder, activeTransferPublication, audioStartFailure, activeRecordingGeometry,
            takeContinuation?.link).also {
            videoOutput = null
            audioSidecarRecorder = null
            activeTransferPublication = null
            audioStartFailure = null
            activeRecordingGeometry = null
            clearTakeDestination()
        }
    }

    /** Stable failure codes pass through as incomplete reasons; anything else is an encoder failure. */
    private fun failureReason(code: String): String =
        code.takeIf { it.length in 1..48 && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } } ?: IncompleteTake.ENCODER_FAILED

    /** The operator's request, frozen at the take start from the settings and the capture state. */
    private fun clipIntentSnapshot(): ClipIntent {
        val state = cameraState.value
        val separateAudio = settings.audioOutputFormat != AudioOutputFormat.AAC_MP4
        return ClipIntent(
            recordingPolicy = settings.recordingPolicy.name,
            captureMode = state.selectedMode.name,
            requestedWidth = state.targetVideoWidth,
            requestedHeight = state.targetVideoHeight,
            requestedFps = state.targetFps,
            projectRate = state.recordingProjectRate?.projectLabel(),
            videoCodecPreference = settings.videoCodec.name,
            videoBitrateMbps = settings.videoBitrateMbps,
            audioEnabled = settings.audioEnabled,
            audioFormat = if (settings.audioEnabled) settings.audioOutputFormat.name else null,
            audioSampleRateHz = settings.audioSampleRateHz.takeIf { settings.audioEnabled && separateAudio },
            audioBitDepth = settings.audioBitDepth.bits.takeIf { settings.audioEnabled && separateAudio },
            audioChannels = settings.audioChannels.takeIf { settings.audioEnabled && separateAudio },
            lutSelectionId = monitorLutIdentity(recordingLutIntent.get()?.lut),
        )
    }

    /** The device profile the camera resolved to; never fails a take. */
    private fun clipDeviceProfile(cameraId: String?) = cameraId?.let { id ->
        runCatching { clipDeviceProfileOf(DeviceQuirksProvider.get(this).resolve(id)) }.getOrNull()
    }

    /**
     * A clip sidecar for a take that was cut short and has nothing but what its outputs hold: no engine evidence,
     * only the intent and the state read now, and the [continuation] link the take had. Null when no take start was recorded.
     */
    private fun interruptedClipSidecar(reasons: List<String>, encodedFrames: Long?, continuation: ClipContinuation?): ClipSidecarProvider? {
        val started = takeClipStartedAt ?: return null
        val intent = takeClipIntent
        val codecMime = takeClipCodecMime
        val mediaRecorder = takeClipMediaRecorder
        val state = cameraState.value
        val camera = takeCameraTracker?.also { it.sample(state) }?.result()
        takeClipMediaRecorder = null
        takeClipStartedAt = null
        takeClipIntent = null
        takeClipCodecMime = null
        takeCameraTracker = null
        val facts = assembleClipFacts(ClipFactsInput(
            startedAt = started, intent = intent, cameraId = state.selectedCameraId, hardware = state.hardwareTruth,
            recordingWidth = state.recordingWidth, recordingHeight = state.recordingHeight, targetFps = state.targetFps,
            graphEvidence = null, hdr = state.selectedMode == CaptureMode.HLG, lutBaked = false,
            timelapse = state.selectedMode == CaptureMode.TIME_LAPSE, mediaRecorder = mediaRecorder, recorderCodecMime = codecMime,
            softwareEncoderName = takeSoftwareEncoder, sharedPause = null, separateAudio = null, embeddedAudio = false,
            deviceProfile = clipDeviceProfile(state.selectedCameraId),
            noticeCodes = listOfNotNull(runtimeNotice, avNotice, cadenceNotice).map { it.name.lowercase().replace('_', '-') },
            hardwareWarnings = emptyList(), stopReason = reasons.firstOrNull(), durationMs = activeRecordingElapsedMs(),
            encodedFrames = encodedFrames, success = false, nativeRetired = false, camera = camera,
        )).copy(continuation = continuation)
        return clipSidecarProvider { facts }
    }

    /**
     * ADR-0011 for a take that recorded and then ended abnormally (engine failure, preview recovery, a
     * native writer that is not yet retired). Nothing that holds bytes is deleted. The audio is sealed
     * at once so no microphone keeps running; once the engine proves native retirement the retained
     * members are published as incomplete on the storage executor and the group is committed. Until
     * then the rows stay pending and journalled, so a process death is covered by crash recovery.
     * [run] does the MediaStore work; [onSaved] is called on main only after something was published.
     */
    private fun salvageAfterRetirement(
        output: VideoOutput?,
        audio: AudioSidecarRecorder?,
        retirement: CompletableFuture<Unit>,
        earlyReasons: List<String>,
        run: () -> com.librestatic.opencinecam.storage.PreparedTakeResult<android.net.Uri, com.librestatic.opencinecam.storage.AudioSidecarRecordingResult>,
        onSaved: (List<String>) -> Unit,
    ) {
        if (output == null && audio == null) return
        runCatching { output?.recoveryGroup?.beginSalvage() }
        if (audio != null) try {
            storageExecutor.execute {
                runCatching { audio.prepareIncompleteCompletion(earlyReasons) }
                    .onFailure { android.util.Log.e("RecordingRecovery", "Audio of an interrupted take could not be sealed", it) }
            }
        } catch (_: RejectedExecutionException) {
            runCatching { audio.prepareIncompleteCompletion(earlyReasons) }
        }
        retirement.whenComplete { _, failure ->
            if (failure != null) {
                android.util.Log.e("RecordingRecovery", "Native retirement failed; the interrupted take stays pending for recovery", failure)
                return@whenComplete
            }
            // Explicit, so the publication below never depends on the order of the future's dependents.
            runCatching { releaseRecordingRecoveryGuard(output) }
            try {
                storageExecutor.execute {
                    val result = try { run() } catch (problem: Throwable) {
                        android.util.Log.e("RecordingRecovery", "Incomplete publication failed; rows stay pending for recovery", problem)
                        return@execute
                    } finally {
                        // Published or not, nothing in this process touches the take again: recovery may resolve it.
                        runCatching { output?.recoveryGroup?.relinquish() }
                    }
                    result.incompleteFailures.forEach {
                        android.util.Log.e("RecordingRecovery", "Incomplete publication step failed; rows stay pending for recovery", it)
                    }
                    if (result.incomplete) mainHandler.post { if (!serviceDestroyed) onSaved(result.incompleteReasons) }
                }
            } catch (_: RejectedExecutionException) {
                android.util.Log.w("RecordingRecovery", "Storage executor is closed; the interrupted take stays pending for recovery")
                runCatching { output?.recoveryGroup?.relinquish() }
            }
        }
    }

    /** The Take-saved-as-incomplete message, once published, replaces only the failure message still on screen. */
    private fun announceIncompleteSaved(expected: String?, reasons: List<String>) {
        cameraState.update { current ->
            if (current.message != expected) current else current.copy(
                message = getString(R.string.take_saved_incomplete, reasons.joinToString(", ")), messageTransient = false)
        }
    }

    /** [salvageAfterRetirement] for a take with nothing but what the outputs hold: no sidecar from the engine's evidence. */
    private fun salvageInterruptedTake(take: DetachedTake, retirement: CompletableFuture<Unit>, reasons: List<String>,
        encodedFrames: Long?, expectedMessage: String?) {
        val output = take.video
        val audio = take.audio
        val clipSidecar = interruptedClipSidecar(reasons, encodedFrames, take.continuation)
        salvageAfterRetirement(output, audio, retirement, reasons, run = {
            finalizePreparedRecordingTake<
                com.librestatic.opencinecam.storage.PreparedVideoOutput,
                com.librestatic.opencinecam.storage.PreparedAudioSidecar,
                android.net.Uri,
                com.librestatic.opencinecam.storage.AudioSidecarRecordingResult,
            >(
                success = false,
                prepareAudio = null,
                prepareVideo = { null },
                publishAudio = { _ -> audio?.publishPrepared() },
                publishVideo = { _ -> output?.publishPrepared() },
                discardAudio = { audio?.finish(false); Unit },
                discardVideo = { output?.finish(false); Unit },
                salvage = com.librestatic.opencinecam.storage.IncompleteTakeSalvage(
                    reasons = reasons,
                    prepareAudio = audio?.let { sidecar -> { r -> sidecar.prepareIncompleteCompletion(r) } },
                    prepareVideo = { r ->
                        output?.sealMotionLog(keep = false)
                        output?.prepareIncomplete(r, null, false, false, encodedFrames, clipSidecar)
                    },
                ),
            )
        }, onSaved = { announceIncompleteSaved(expectedMessage, it) })
    }

    private fun cancelTransferPreparation(): Boolean {
        if (!transferWaiting) return false
        transferWaiting = false
        transferPreparationToken++
        releaseTransferCapture(transferCapture.get()) // No capture resources were admitted yet.
        cameraState.value = cameraState.value.let { it.copy(
            phase = if (it.phase == CameraUiPhase.CAPTURING) CameraUiPhase.PREVIEWING else it.phase,
            transferRetirementPending = false, audioRetirementPending = false, message = getString(R.string.pro_wb_cancelled)) }
        return true
    }

    private fun admitTransferPublication() {
        abortTransferPublication()
        // PLAN-072: a drive take is enrolled like a phone take; its artifacts are probed and read through SAF.
        activeTransferPublication = try { CaptureTransferPublication.admit(this) }
        catch (problem: Exception) {
            android.util.Log.e("CapturePublication", "Admission bookkeeping failed; capture remains independent", problem)
            null
        }
    }

    private fun abortTransferPublication(publication: CaptureTransferPublication? =
        synchronized(takeOwnershipLock) { activeTransferPublication.also { activeTransferPublication = null } }) {
        publication?.abort()?.forEach { problem ->
            android.util.Log.e("CapturePublication", "Aborted take bookkeeping failed", problem)
        }
    }

    @Volatile private var audioSidecarRecorder: AudioSidecarRecorder? = null
    // Report-only A/V clock drift of the last separate WAV/FLAC take (ADAPTIVE; never stops a take).
    @Volatile private var lastSeparateAudioAvDrift: com.librestatic.opencinecam.media.audio.AvDiagnosticSnapshot? = null
    private var previewAudioMonitor: PreviewAudioMonitor? = null
    private var audioInputMonitor: AudioInputMonitor? = null
    /** Only the newest guard may publish [CameraUiState.activeAudioInput]; retired guards may still post. */
    private var audioRouteOwner: AudioInputRouteGuard? = null
    private var audioInputRefreshDeferred = false
    private var lastAutoAudioInput: AudioInputKey? = null
    @Volatile private var audioListeningController: AudioListeningController? = null
    private val latestListeningStatus = AtomicReference<AudioListeningStatus?>(null)
    private var audioListeningRetirement = CompletableFuture.completedFuture(Unit)
    // Owned on main; even a failed factory keeps its native cleanup receipt here.
    private var previewAudioRetirement = CompletableFuture.completedFuture(Unit)
    private var previewAudioGeneration = 0L
    @Volatile private var audioStartFailure: String? = null
    private var activeRecordingAudioLabel: String = "no audio"
    private var activeRecordingGain: DigitalRecordingGain? = null
    /** The HUD state when the take began, so the sidecar lists only what the take itself saw. */
    @Volatile private var takeStartHardwareTruth = HardwareTruthHudState()
    private val audioLevelEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var recordingStartedAtMs = 0L
    /** OCC-PLAN-068 U6: optional operator-side sync-marker evidence for the active take's sidecar. */
    private val subjectSyncMarkers = com.librestatic.opencinecam.SubjectSyncMarkerSlot()
    private var timelapseAutoStopRunnable: Runnable? = null
    private var attachedPreviewSurface: Surface? = null
    /**
     * The RAW qualification step covers the viewfinder, so the camera rests while it is up: the surface waits in
     * [parkedPreviewSurface] and [attachedPreviewSurface] stays null, which keeps every preview start away until the step leaves.
     */
    private var rawQualificationPreviewHeld = false
    private var parkedPreviewSurface: Surface? = null
    /** The viewfinder surface was lost or replaced during a RAW take, which cannot reconfigure the session: reattach when it ends. */
    private var rawTakePreviewStale = false
    private var attachedPreviewRotationDegrees: Int = 0
    private var cameraSwitchGeneration = 0L
    private var recoveryGeneration = 0L
    private var pendingSwitchTarget: String? = null
    private var pendingSwitchPrevious: String? = null
    private var pendingStatusMessage: String? = null
    private val perCameraZoom = mutableMapOf<String, Float>()
    private val timecodeTracker by lazy { TimecodeTracker(
        continuationStore = com.librestatic.opencinecam.TimecodeContinuationFile(java.io.File(filesDir, "timecode-continuation.json"))) }
    private var settings = CameraSettings()
    private var operatorStartupPending = true
    private var foldClosureTracker: FoldClosureTracker? = null
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var geometrySeeds = GeometrySeeds()
    private lateinit var physicalOrientationTracker: PhysicalOrientationTracker
    @Volatile private var activeRecordingGeometry: RecordingGeometry? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun incrementSavedSlate(saved: ProductionSlateSettings) {
        try {
            SettingsRepositories.get(this).update { afterSavedProductionSlate(it, saved) }
        } catch (failure: Exception) {
            android.util.Log.e("ProductionSlate", "Saved media retained; next take number was not persisted", failure)
            mainHandler.post {
                if (!serviceDestroyed) cameraState.update { it.copy(message =
                    listOfNotNull(it.message, getString(R.string.production_slate_increment_failed)).joinToString(" · ")) }
            }
        }
    }
    private fun admittedCaptureNames(): CaptureNameSnapshot {
        val intent = SettingsRepositories.get(this).states.value
        return CaptureNameSnapshot(intent.captureNaming, intent.productionSlate, System.currentTimeMillis(),
            com.librestatic.opencinecam.CaptureLocations.get(this).snapshot())
    }
    private class StillOwner(val captureNames: CaptureNameSnapshot) {
        val productionSlate get() = captureNames.slate
        val publishing = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    private val stillOwner = java.util.concurrent.atomic.AtomicReference<StillOwner?>()
    private val storageExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16),
        ThreadPoolExecutor.AbortPolicy(),
    )
    /**
     * Interrupted takes are recovered here, not on [storageExecutor]: repairing a RAW take on a drive scans the whole
     * file, which would hold up still saves and take finalization, and fill their queue, for minutes.
     */
    private val recoveryExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(4),
        ThreadPoolExecutor.AbortPolicy(),
    )
    /** Free bytes on the primary external volume MediaStore writes takes to (not the app's private files), RAW video's included. */
    @Suppress("DEPRECATION")
    private fun phoneVolumeFreeBytes(): Long? = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes }.getOrNull()

    /**
     * PLAN-072: free bytes where the running take writes, or where the next encoded take would when idle; any thread,
     * never blocking on a drive. A drive's figure is its last sample ([driveFreeSpace]: null before the first one or
     * when the provider gave none, so the policy reuses the last good one), and the call starts a fresh one.
     */
    private fun recordingVolumeFreeBytes(): Long? =
        cachedDestinationFreeBytes(freeSpaceDestination(takeDestination?.destination, nextTakeDestination.destination))

    private fun cachedDestinationFreeBytes(destination: RecordingDestination): Long? = when (destination) {
        RecordingDestination.Phone -> phoneVolumeFreeBytes()
        is RecordingDestination.UsbFolder -> driveFreeSpace.read(destination)
    }

    /** Free bytes on [destination] read now; a drive's blocks on its provider (one IPC) and becomes its cached sample. */
    private fun destinationFreeBytes(destination: RecordingDestination): Long? = when (destination) {
        RecordingDestination.Phone -> phoneVolumeFreeBytes()
        is RecordingDestination.UsbFolder -> driveFreeBytes(contentResolver, destination.treeUri).also { driveFreeSpace.store(destination, it) }
    }

    /**
     * PLAN-072: the drive's free space for the main thread (the REC ticker, the take start), sampled on its own thread so
     * a provider call never runs on main, least of all while a failing drive's loss must be handled there. Not
     * [storageExecutor], whose bounded queue refuses work when full. Two threads at most: a sample stalled on a failing
     * drive must not hold back the one of the drive chosen after it, and a third is refused until one returns.
     */
    private val driveFreeSpaceExecutor = java.util.concurrent.ThreadPoolExecutor(0, 2, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue()) { task -> Thread(task, "occ-drive-free") }
    private val driveFreeSpace = DriveFreeSpaceCache(driveFreeSpaceExecutor) { driveFreeBytes(contentResolver, it.treeUri) }

    /** PLAN-072: removable volumes, for the destination and its disconnects; null when StorageManager is unavailable. */
    private var recordingVolumeMonitor: RecordingVolumeMonitor? = null
    /** PLAN-072: USB storage devices attached recently, so an absent drive reads as starting; null when it could not be created. */
    private var driveArrivalMonitor: com.librestatic.opencinecam.storage.UsbDriveArrivalMonitor? = null
    /** The destination the next encoded take would use, refreshed on volume and settings changes; resolved afresh at take start. */
    @Volatile private var nextTakeDestination = ResolvedRecordingDestination(RecordingDestination.Phone, RecordingDestination.Phone, null)
    /** The running encoded take's destination, fixed at its start; null outside a take. */
    @Volatile private var takeDestination: ResolvedRecordingDestination? = null
    private var destinationRefreshJob: kotlinx.coroutines.Job? = null

    /**
     * PLAN-072 Delivery 4: the continuation owed to a drive take that lost its drive ([DriveContinuation]), the one being
     * started now (set on main only around the start call, read by [LocalBinder.capturePrimary] and [toggleRawTake]), and
     * the running take's own link when it is a continuation (its sidecar and the HUD read it; null otherwise).
     */
    private val driveContinuation = DriveContinuation({ android.os.SystemClock.elapsedRealtime() })
    private var continuationStarting: ContinuationRequest? = null
    @Volatile private var takeContinuation: ContinuationRequest? = null

    /** The take ended or never started: the next take's destination shows again. */
    private fun clearTakeDestination() {
        takeContinuation = null
        if (takeDestination == null) return
        takeDestination = null
        mainHandler.post { refreshRecordingDestination() }
    }

    private fun mountedRecordingVolumes(): Set<String> = recordingVolumeMonitor?.mounted?.value ?: emptySet()

    private fun driveArriving(): Boolean = driveArrivalMonitor?.arriving?.value ?: false

    /** Resolves the destination for a take or still starting now (one preferences read and one permission IPC); any thread. */
    private fun resolveTakeDestination(): ResolvedRecordingDestination =
        runCatching { RecordingDestinationStore(this).current(mountedRecordingVolumes(), driveArriving()) }
            .onFailure { android.util.Log.e("RecordingDestination", "Destination unreadable; the take goes to the phone", it) }
            .getOrDefault(ResolvedRecordingDestination(RecordingDestination.Phone, RecordingDestination.Phone, null))

    /** Storage executor: where a still being saved now goes; a missing drive falls back to the phone, as for a take. */
    private fun stillDestination(): RecordingDestination = resolveTakeDestination().destination

    /**
     * Main: a VideoOutput on the destination resolved now; the take keeps it until it ends. A missing drive falls back to
     * the phone. A continuation (PLAN-072 Delivery 4, carried by the take's [RecordingLutIntent]) always goes to the phone,
     * named after the drive take it continues.
     */
    private fun createTakeOutput(slate: ProductionSlateSettings?, captureNames: CaptureNameSnapshot): VideoOutput {
        val continuation = recordingLutIntent.get()?.takeIf { it.owner === transferCapture.get() }?.continuation
        val resolved = resolveTakeDestination().let { if (continuation != null) continuationDestination(it) else it }
        resolved.fallback?.let { android.util.Log.w("RecordingDestination", "Take falls back to the phone: $it") }
        val output = VideoOutput.create(this, slate, captureNames, resolved.destination, stem = continuation?.stem)
        holdTakeDestination(resolved, continuation)
        return output
    }

    /** The take-start message for a storage refusal (ADR-0013): free space unreadable, or under the reserve. */
    private fun storageRefusalText(admission: StorageAdmission.Refuse): String =
        if (admission.availableBytes == null) getString(R.string.storage_unknown_refused)
        else getString(R.string.storage_reserve_insufficient, android.text.format.Formatter.formatFileSize(this, admission.requiredBytes),
            android.text.format.Formatter.formatFileSize(this, admission.availableBytes))

    /**
     * Main, before an encoded take's foreground start and output: the take was admitted for the destination resolved
     * then, before the awaited transfer and audio retirement and the white balance convergence. When the one resolved now
     * differs (the drive left or arrived meanwhile), the take is admitted again against its free space; false, with the
     * refusal shown, when it does not fit. A continuation was admitted for the phone, where it always goes.
     */
    private fun admitChangedTakeDestination(): Boolean {
        val intent = recordingLutIntent.get()?.takeIf { it.owner === transferCapture.get() } ?: return true
        if (intent.continuation != null) return true
        val destination = resolveTakeDestination().destination
        if (destination == intent.admittedDestination) return true
        android.util.Log.w("RecordingDestination", "The take's destination changed since its admission; admitting it again")
        when (val admission = admitRecordingStorage(settings.recordingPolicy, takeProjectedBytesPerMinute, destinationFreeBytes(destination))) {
            StorageAdmission.Admit -> admissionWarning = null
            StorageAdmission.AdmitUnread -> admissionWarning = getString(R.string.storage_unknown_warning)
            is StorageAdmission.Refuse -> {
                refuseTakeStart(admission.errorCode, storageRefusalText(admission))
                return false
            }
        }
        return true
    }

    /** Main: the take starting now writes to [resolved] until it ends; the HUD describes it, and says when it is a [continuation]. */
    private fun holdTakeDestination(resolved: ResolvedRecordingDestination, continuation: ContinuationRequest? = null) {
        takeDestination = resolved
        nextTakeDestination = resolved
        takeContinuation = continuation
        cameraState.update { it.copy(recordingDestination = recordingDestinationStatus(resolved, it.recordingDestination.driveLabel,
            it.recordingDestination.driveFreeBytes, continuedOnPhone = continuation != null)) }
    }

    /**
     * PLAN-072 Delivery 3: the qualification records RAW video is gated by, those of the destination the next RAW take
     * would use ([rawDestinationId]); [rawGateDestinationId] is the one the gate on screen was computed for.
     */
    private fun nextRawDestinationId(): String = rawDestinationId(nextTakeDestination.destination)
    @Volatile private var rawGateDestinationId = RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS

    /** The destination RAW lookups read: the next take's, but a running RAW take or qualification keeps the gate's. */
    private fun rawLookupDestinationId(): String =
        if (rawTake != null || rawQualificationSession != null) rawGateDestinationId else nextRawDestinationId()

    /**
     * Main: the next RAW take's destination changed (a drive mounted or left, a folder adopted or cleared): the RAW gate,
     * the chooser, Automatic and the armed ring follow the new destination's qualification. Never during a RAW take or a
     * qualification, which keep theirs; their end refreshes the destination again.
     */
    private fun refreshRawGateForDestination() {
        if (nextRawDestinationId() != rawGateDestinationId) refreshRawGate()
    }

    /**
     * Main: recomputes the RAW gate and lets the mode, the session and the armed ring follow a selection that changed.
     * Also when a continuation ended, was refused or was dropped: the gate held the drive take's configuration for it
     * ([rawVideoGateKey]) and goes back to the operator's choice.
     */
    private fun refreshRawGate() {
        if (serviceDestroyed || rawTake != null || rawQualificationSession != null) return
        val before = cameraState.value.rawVideoSelectedKey
        cameraState.value = withRawVideoGate(cameraState.value, cameraState.value.selectedCameraId)
        if (followRawSelection(before)) return
        syncRawVideoArm()
    }

    /**
     * Main: after a gate recompute, RAW_VIDEO leaves for its fallback mode when nothing is selected, or reopens the
     * session when the selection changed from [before]; neither while structural settings are frozen. True when the mode
     * changed (its reopen arms what it needs).
     */
    private fun followRawSelection(before: String?): Boolean {
        val current = cameraState.value
        if (current.selectedMode != CaptureMode.RAW_VIDEO) return false
        val fallback = rawVideoModeFallback(current.selectedMode, current.modeGates, rawModeFallback, hasConfig = current.rawVideoSelectedKey != null)
        if (fallback != null && !current.structuralSettingsFrozen) { localBinder.selectMode(fallback, reopen = true); return true }
        if (current.rawVideoSelectedKey != before && !current.structuralSettingsFrozen) {
            attachedPreviewSurface?.takeIf { it.isValid }?.let { localBinder.attachPreview(it, attachedPreviewRotationDegrees) }
        }
        return false
    }

    /**
     * Re-reads the configured destination, the drive's label and its free space off the main thread, then publishes the
     * status. A running take keeps its own destination; the next take's follows. Settings call it after adopting or
     * clearing a folder ([LocalBinder.refreshRecordingDestination]).
     */
    private fun refreshRecordingDestination() {
        if (serviceDestroyed) return
        val mounted = mountedRecordingVolumes()
        val arriving = driveArriving()
        destinationRefreshJob?.cancel()
        destinationRefreshJob = settingsScope.launch {
            val (resolved, status, phoneFree) = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val store = RecordingDestinationStore(this@CaptureService)
                    val resolved = store.current(mounted, arriving)
                    val free = (resolved.destination as? RecordingDestination.UsbFolder)?.let { driveFreeBytes(contentResolver, it.treeUri) }
                    Triple(resolved, recordingDestinationStatus(resolved, store.label(), free), phoneVolumeFreeBytes())
                }
            } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
            catch (failure: Exception) {
                android.util.Log.e("RecordingDestination", "Destination unreadable", failure)
                return@launch
            }
            if (serviceDestroyed) return@launch
            // Only once the read was not cancelled: a refresh overtaken by a volume event must not seed its drive's sample.
            (resolved.destination as? RecordingDestination.UsbFolder)?.let { driveFreeSpace.store(it, status.driveFreeBytes) }
            nextTakeDestination = resolved
            // While a take runs the HUD keeps describing that take; the next take's state is published when it ends.
            // With a drive configured (or just cleared), an idle free figure left by the last drive take would mislead: the
            // phone's replaces it when the next take goes there; the HUD reads the drive's own figure when it goes to the drive.
            // No take holds a destination here (every take does while it runs), so a drive take still finalizing in the
            // RECORDING phase must not keep its drive's figure: the next take goes to the phone.
            if (takeDestination == null) cameraState.update {
                val phoneNext = status.configuredDrive && !status.onDrive || it.recordingDestination.configuredDrive && !status.configuredDrive
                it.copy(recordingDestination = status, availableStorageBytes = if (phoneNext) phoneFree else it.availableStorageBytes)
            }
            else cameraState.update { it.copy(recordingDestination = it.recordingDestination.copy(driveLabel = status.driveLabel)) }
            refreshRawGateForDestination()
        }
    }

    /** Main: a removable volume left or mounted. A leaving drive ends the take on it (Stop and save); a mounted one is recovered. */
    private fun onRecordingVolumeEvent(event: RecordingVolumeEvent) {
        if (serviceDestroyed) return
        // A drive that left, or mounted again, has no free-space sample until its next one.
        driveFreeSpace.invalidate(event.volumeId)
        when (event) {
            is RecordingVolumeEvent.Leaving -> {
                // ADR-0037: the monitor already closed the drive's descriptors on its own thread; this is a cheap backstop.
                releaseDriveDescriptors(event.volumeId, "the drive left")
                // A qualification of that drive has nothing left to measure; it ends as cancelled, so nothing is stored.
                if (rawQualificationSession != null && rawGateDestinationId == RawVideoQualificationStore.destinationIdForDrive(event.volumeId)) {
                    localBinder.cancelRawVideoQualification(RawQualificationStopReasons.DRIVE_LOST)
                }
                if (driveLeavingStopsTake(takeDestination?.destination, event.volumeId)) stopTakeForLostDrive(writeFailed = false)
            }
            is RecordingVolumeEvent.Mounted -> recoverRecordingsOnMount(event.volumeId)
        }
        refreshRecordingDestination()
    }

    /**
     * Main: ends the running drive take through the policy stop path; what reached the drive is recovered when it returns.
     * Whether a continuation follows on the phone is decided where every drive stop converges, once per take:
     * [decideDriveContinuation] from the encoded take's `onRecordingStopped` or from [finishRawTake].
     */
    private fun stopTakeForLostDrive(writeFailed: Boolean) {
        localBinder.stopRecording(driveLostFailure(writeFailed))
    }

    /**
     * PLAN-072 Delivery 4, main: the take is being stopped for another reason than its drive, or the operator pressed record
     * in the gap before the continuation: drop any pending continuation and keep the take from arming one. True when one
     * was pending (the press is consumed as the stop, and the message says the take was not continued).
     */
    private fun cancelPendingContinuation(): Boolean {
        val dropped = driveContinuation.veto() ?: return false
        android.util.Log.i("RecordingDestination", "Continuation ${dropped.stem} cancelled")
        releaseContinuationForeground()
        if (dropped.mode == CaptureMode.RAW_VIDEO) refreshRawGate()
        cameraState.update { it.copy(message = listOfNotNull(it.message, getString(R.string.recording_drive_continue_cancelled))
            .joinToString(" · "), messageTransient = false) }
        return true
    }

    /** The operator's Drive-disconnect choice; Stop and save when unreadable. Any thread (one preferences read). */
    private fun driveDisconnectPolicy(): DriveDisconnectPolicy =
        runCatching { RecordingDestinationStore(this).policy() }.getOrDefault(DriveDisconnectPolicy.DEFAULT)

    /**
     * PLAN-072 Delivery 4, any thread: a drive take stopped because its drive was lost ([stopReason] in
     * [RECORDING_DRIVE_COMPONENT]). Decides with [driveLossAction] and, on Continue, arms the continuation [request] builds;
     * a vetoed take (the operator or another reason stopped it first) or a second trigger arms nothing. Returns the text
     * the end message appends: why continuing is not available, or null.
     */
    private fun decideDriveContinuation(stopReason: StableFailure?, onDrive: Boolean, mode: CaptureMode, highSpeed: Boolean,
        rawQualifiedOnPhone: Boolean, phoneRoom: Boolean, request: () -> ContinuationRequest?): String? {
        if (!onDrive || stopReason?.component != RECORDING_DRIVE_COMPONENT) return null
        return when (val action = driveLossAction(driveDisconnectPolicy(), mode, highSpeed, rawQualifiedOnPhone, phoneRoom)) {
            DriveLossAction.StopAndSave -> null
            DriveLossAction.Continue -> {
                val next = runCatching(request).onFailure { android.util.Log.e("RecordingDestination", "Continuation not prepared", it) }.getOrNull()
                when {
                    next == null -> getString(R.string.recording_drive_continue_failed)
                    driveContinuation.arm(next) -> { android.util.Log.i("RecordingDestination", "Continuing on the phone as ${next.stem}"); null }
                    else -> { android.util.Log.i("RecordingDestination", "Continuation not armed: the take was stopped or already continues"); null }
                }
            }
            is DriveLossAction.StopUnavailable -> getString(when (action.reason) {
                ContinuationUnavailable.TIME_LAPSE -> R.string.recording_drive_continue_unavailable_timelapse
                ContinuationUnavailable.HIGH_SPEED -> R.string.recording_drive_continue_unavailable_high_speed
                ContinuationUnavailable.RAW_NOT_QUALIFIED -> R.string.recording_drive_continue_unavailable_raw
                ContinuationUnavailable.PHONE_FULL -> R.string.recording_drive_continue_unavailable_space
                ContinuationUnavailable.MODE -> R.string.recording_drive_continue_failed
            }).also { android.util.Log.w("RecordingDestination", "Continue on the phone unavailable: ${action.reason}") }
        }
    }

    /**
     * PLAN-072 Delivery 4, main: starts the armed continuation once the drive part has fully finished (its publication
     * attempt done, its reservation released, `recordingFinalizing` cleared). It goes through the ordinary start path
     * ([LocalBinder.capturePrimary] or [toggleRawTake]) with [continuationStarting] set, so admission, the foreground
     * service, the encoder policy and the RAW gate all apply; any refusal ends as a clean stop and save with the reason
     * the start path shows, plus a line saying the take could not continue. Never while another take or a qualification runs.
     */
    private fun startDriveContinuation() {
        val request = driveContinuation.consume() ?: run { refreshRawGate(); return releaseContinuationForeground() }
        if (serviceDestroyed) return
        val current = cameraState.value
        val busy = current.selectedMode != request.mode || current.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) ||
            current.recordingFinalizing || rawTake != null || rawQualificationSession != null || transferCapture.get() != null ||
            stillOwner.get() != null || captureCountdown.active != null || recordingWbPreparing
        val accepted = !busy && try {
            continuationStarting = request
            if (request.mode == CaptureMode.RAW_VIDEO) toggleRawTake(request.audio) else localBinder.capturePrimary(request.audio)
        } finally {
            continuationStarting = null
        }
        android.util.Log.i("RecordingDestination", "Continuation ${request.stem}: ${if (accepted) "starting" else "refused (busy=$busy)"}")
        if (!accepted) {
            cameraState.update { it.copy(message = listOfNotNull(it.message, getString(R.string.recording_drive_continue_failed))
                .joinToString(" · "), messageTransient = false) }
            if (request.mode == CaptureMode.RAW_VIDEO) refreshRawGate()
            releaseContinuationForeground()
        }
    }

    /**
     * Main: the foreground service the drive part kept for its continuation is dropped once no continuation will start
     * (cancelled, expired or refused). Not while a take records or the drive part still finalizes: that path stops it.
     */
    private fun releaseContinuationForeground() {
        val current = cameraState.value
        if (serviceDestroyed || rawTake != null || current.recordingFinalizing ||
            current.phase in setOf(CameraUiPhase.RECORDING, CameraUiPhase.CAPTURING)) return
        foreground.stop()
    }

    /** Takes interrupted on a drive wait in the journal until it mounts again: recover them now, as at service start. */
    private fun recoverRecordingsOnMount(volumeId: String) {
        try {
            storageExecutor.execute {
                // Interrupted still groups on the drive are discarded, as at service start; a still being saved holds the journal lock.
                runCatching { stillSaver.recoverInterrupted() }
                    .onFailure { android.util.Log.e("StillRecovery", "Interrupted still journal unavailable after $volumeId mounted", it) }
                // A RAW qualification killed mid-run left scratch files in the drive's .occ-qualify folder; nothing else is touched.
                runCatching {
                    val configured = RecordingDestinationStore(this).current(setOf(volumeId)).destination as? RecordingDestination.UsbFolder
                    if (configured != null && configured.volumeId.equals(volumeId, ignoreCase = true)) {
                        com.librestatic.opencinecam.storage.RawQualificationDriveScratch(contentResolver, configured.treeUri).purgeOrphans()
                    }
                }.onFailure { android.util.Log.w("RawVideoQualifier", "Qualification scratch on $volumeId not purged", it) }
            }
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            android.util.Log.w("StillRecovery", "Still recovery after $volumeId mounted not queued; the service's next start retries it", failure)
        }
        // Queued on its own: a full storage queue must not cost the drive's takes their recovery pass.
        try {
            recoveryExecutor.execute {
                // A take running now is in the live-group registry, so recovery leaves it alone. RAW takes (PLAN-072
                // Delivery 3) are repaired in place, then published with their frames or discarded when they hold none.
                val report = try { com.librestatic.opencinecam.storage.RecordingCaptureRecovery(this).recover() } catch (failure: Exception) {
                    android.util.Log.e("RecordingRecovery", "Interrupted take journal unavailable after $volumeId mounted", failure)
                    return@execute
                }
                val recovered = report.incompleteGroups + report.rawTakes.published
                if (report.discardedGroups + report.unresolvedGroups + recovered + report.rawTakes.deleted == 0) return@execute
                mainHandler.post {
                    if (!serviceDestroyed) cameraState.update { state ->
                        val idle = state.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED)
                        state.copy(recordingRecovery = mergeRecordingRecovery(state.recordingRecovery, report)).let {
                            if (idle && recovered > 0) it.copy(message = getString(R.string.recording_drive_takes_recovered, recovered),
                                messageTransient = true) else it
                        }
                    }
                }
            }
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            android.util.Log.w("RecordingRecovery", "Recovery after $volumeId mounted not queued; the drive's next mount or the service's next start retries it", failure)
        }
    }

    /** Set when Adaptive admitted a take it could not size; shown for the whole take. */
    @Volatile private var admissionWarning: String? = null
    /** Codec of the software AVC encoder the running Adaptive take records with; null for a hardware take. */
    @Volatile private var takeSoftwareEncoder: String? = null
    /** The running take asked for HEVC and the engine fell back to H.264. */
    @Volatile private var takeCodecFallback = false
    /** What the running take was asked to do, its wall-clock start and the codec the engine reported; the clip sidecar's intent. */
    @Volatile private var takeClipIntent: ClipIntent? = null
    @Volatile private var takeClipStartedAt: java.time.Instant? = null
    @Volatile private var takeClipCodecMime: String? = null
    @Volatile private var takeClipMediaRecorder: Boolean? = null
    /** Samples the running take's camera settings from [cameraState] for the clip sidecar; null outside a take. */
    @Volatile private var takeCameraTracker: ClipCameraTracker? = null
    /** Stable code of a Strict encoder refusal reported by the engine, consumed by the failed-start branch. */
    @Volatile private var pendingStartRefusal: String? = null
    /** Bytes per minute the admitted take was projected to write; the live storage policy measures against it. */
    @Volatile private var takeProjectedBytesPerMinute = 0L
    /** The policy the running take started under; a setting changed mid-take stays pending. */
    @Volatile private var activeRecordingPolicy = PolicyMode.STRICT
    @Volatile private var lastKnownFreeBytes: Long? = null
    @Volatile private var runtimeNotice: RuntimeNotice? = null
    @Volatile private var avNotice: RuntimeNotice? = null
    @Volatile private var cadenceNotice: RuntimeNotice? = null
    /** Live A/V drift of a take whose audio is a separate WAV/FLAC file (PLAN-023); embedded AAC exposes no observable audio PTS. */
    @Volatile private var avDiagnostics: AvClockDiagnostics? = null
    /** Why the running take is being stopped; the final message names it, and the file is still saved. */
    @Volatile private var pendingStopReason: StableFailure? = null

    private val recordingTicker = object : Runnable {
        override fun run() {
            if (cameraState.value.phase != CameraUiPhase.RECORDING) return
            previewEngine.encodedRecordingProgress()?.let(timecodeTracker::observeEncodedProgress)
            val free = recordingVolumeFreeBytes()
            cameraState.value = cameraState.value.copy(
                recordingElapsedMs = activeRecordingElapsedMs(),
                availableStorageBytes = free,
                timecodeDisplay = timecodeTracker.currentDisplayTc()?.format(),
                recordingDestination = cameraState.value.recordingDestination.let {
                    if (takeDestination?.destination is RecordingDestination.UsbFolder && free != null) it.copy(driveFreeBytes = free) else it },
            )
            publishRawVideoStats()
            // ENODEV can reach a sidecar writer before any volume event: its thread stops and the take would go on without audio.
            if (takeDestination?.destination is RecordingDestination.UsbFolder && pendingStopReason == null &&
                audioSidecarRecorder?.writerFailed == true) {
                // The drive is failing: close every descriptor on it before vold finds one (ADR-0037).
                (takeDestination?.destination as? RecordingDestination.UsbFolder)?.let { releaseDriveDescriptors(it.volumeId, "a sidecar write failed") }
                stopTakeForLostDrive(writeFailed = true)
            }
            enforceRuntimePolicy(free)
            mainHandler.postDelayed(this, RECORDING_TICK_MS)
        }
    }

    /**
     * Live thermal and storage policy (ADR-0016) for the running take. A stop verdict ends the take
     * with its reason; a notice stays on screen. A one-off unreadable free-space sample reuses the
     * last good one, so a StatFs hiccup cannot stop a take in Strict.
     */
    private fun enforceRuntimePolicy(free: Long?) {
        if (pendingStopReason != null) return
        if (free != null) lastKnownFreeBytes = free
        val thermal = runCatching { getSystemService(android.os.PowerManager::class.java).currentThermalStatus }.getOrNull()
        val verdict = evaluateRuntimePolicy(activeRecordingPolicy, thermal, free ?: lastKnownFreeBytes, takeProjectedBytesPerMinute)
        verdict.stop?.let { localBinder.stopRecording(it); return }
        val drift = avDiagnostics?.let { diagnostics ->
            val sidecar = audioSidecarRecorder ?: return@let null
            avClockSample(sidecar.captureClock?.cameraRealtime == true, sidecar.liveCaptureTiming())
                ?.let { (videoClockUs, audioClockUs) -> avDriftVerdict(diagnostics.observe(videoClockUs, audioClockUs, videoClockUs)) }
        }
        drift?.stop?.let { localBinder.stopRecording(it); return }
        runtimeNotice = verdict.notice
        avNotice = drift?.notice ?: avNotice
        publishRecordingWarning()
    }

    private fun publishRecordingWarning() {
        val text = recordingWarningText()
        if (cameraState.value.recordingWarning != text) cameraState.update { it.copy(recordingWarning = text) }
    }

    private fun recordingWarningText(): String? {
        return recordingWarningLine(admissionWarning, takeSoftwareEncoder?.let { getString(R.string.software_video_encoder_warning, it) },
            getString(R.string.video_codec_fallback_warning).takeIf { takeCodecFallback },
            listOfNotNull(runtimeNotice, avNotice, cadenceNotice).map { getString(runtimeNoticeText(it)) } +
                listOfNotNull(destinationFallbackNotice(takeDestination?.fallback, getString(R.string.recording_drive_fallback_not_connected),
                    getString(R.string.recording_drive_fallback_permission_lost), getString(R.string.recording_drive_fallback_starting),
                    getString(R.string.recording_drive_fallback_renamed))))
    }

    private fun runtimeNoticeText(notice: RuntimeNotice): Int = when (notice) {
        RuntimeNotice.MONITORING_REDUCED -> R.string.runtime_notice_monitoring_reduced
        RuntimeNotice.STATE_UNKNOWN -> R.string.runtime_notice_state_unknown
        RuntimeNotice.AV_DRIFT -> R.string.runtime_notice_av_drift
        RuntimeNotice.CADENCE -> R.string.runtime_notice_cadence
    }

    private fun stopReasonText(failure: StableFailure): String = when (takeStopReason(failure)) {
        TakeStopReason.THERMAL -> getString(R.string.stop_reason_thermal)
        TakeStopReason.STORAGE -> getString(R.string.stop_reason_storage)
        TakeStopReason.STATE_UNKNOWN -> getString(R.string.stop_reason_state_unknown)
        TakeStopReason.AV_DRIFT -> getString(R.string.stop_reason_av_drift)
        TakeStopReason.CADENCE -> getString(R.string.stop_reason_cadence)
        TakeStopReason.AUDIO_INPUT_LOST -> getString(R.string.stop_reason_audio_input)
        TakeStopReason.DRIVE_LOST -> getString(R.string.stop_reason_drive_disconnected)
        TakeStopReason.OTHER -> getString(R.string.stop_reason_other)
    }

    /** Last geometry the user chose per profile mode, seeded from persisted settings. */
    private data class GeometrySeeds(
        val videoWidth: Int = 1920,
        val videoHeight: Int = 1080,
        val videoFps: Int = 30,
        val logWidth: Int = 1920,
        val logHeight: Int = 1080,
        val logFps: Int = 30,
        val timelapseWidth: Int = 1920,
        val timelapseHeight: Int = 1080,
    ) {
        fun width(mode: CaptureMode): Int = when (mode) { CaptureMode.LOG, CaptureMode.HLG -> logWidth; CaptureMode.TIME_LAPSE -> timelapseWidth; else -> videoWidth }
        fun height(mode: CaptureMode): Int = when (mode) { CaptureMode.LOG, CaptureMode.HLG -> logHeight; CaptureMode.TIME_LAPSE -> timelapseHeight; else -> videoHeight }
        fun fps(mode: CaptureMode): Int = when (mode) { CaptureMode.LOG, CaptureMode.HLG -> logFps; CaptureMode.TIME_LAPSE -> 30; else -> videoFps }
    }

    @Volatile private var currentOperatorLut: MonitorLut? = null
    @Volatile private var currentSubjectLut: MonitorLut? = null
    @Volatile private var currentRecordingLut: MonitorLut? = null
    @Volatile private var recordingLutLibrary: com.librestatic.opencinecam.LutLibrary? = null
    /**
     * [continuation]: set when this take is a continuation on the phone (PLAN-072 Delivery 4); its output reads it.
     * [admittedDestination]: where the storage admission measured the take; the start admits again if it changed.
     */
    private data class RecordingLutIntent(val owner: CaptureTransferReservation, val lut: MonitorLut?, val captureNames: CaptureNameSnapshot,
        val continuation: ContinuationRequest? = null, val admittedDestination: RecordingDestination = RecordingDestination.Phone) {
        val productionSlate get() = captureNames.slate
    }
    private val recordingLutIntent = java.util.concurrent.atomic.AtomicReference<RecordingLutIntent?>()
    private val subjectPreviewEpoch = java.util.concurrent.atomic.AtomicLong()

    /**
     * No preview start may leave the operator on "Preparing camera" indefinitely. A session that
     * stays OPENING/READY with a live surface past the timeout is declared failed, which brings
     * up the error sheet and its way back to the last configuration that previewed.
     */
    private var previewWaitSinceMs = 0L
    private val previewStartWatchdog = object : Runnable {
        override fun run() {
            if (serviceDestroyed) return
            // A RAW video qualification owns the camera and parks the phase at READY: no preview is due until it ends.
            val waiting = cameraState.value.phase in setOf(CameraUiPhase.OPENING, CameraUiPhase.READY) &&
                attachedPreviewSurface?.isValid == true && rawQualificationSession == null
            val now = android.os.SystemClock.elapsedRealtime()
            when {
                !waiting -> previewWaitSinceMs = 0L
                previewWaitSinceMs == 0L -> previewWaitSinceMs = now
                now - previewWaitSinceMs >= PREVIEW_START_TIMEOUT_MS -> {
                    previewWaitSinceMs = 0L
                    fail("preview-start-timeout", getString(R.string.preview_start_timeout))
                }
            }
            mainHandler.postDelayed(this, PREVIEW_WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioInputMonitor = AudioInputMonitor(this, ::onAudioInputsChanged).also { it.register() }
        mainHandler.postDelayed(previewStartWatchdog, PREVIEW_WATCHDOG_INTERVAL_MS)
        audioListeningController = AudioListeningController(this, onStatus = { status ->
            latestListeningStatus.set(status)
            val publish = Runnable {
                if (!serviceDestroyed && latestListeningStatus.get() === status) cameraState.update {
                    if (serviceDestroyed || latestListeningStatus.get() !== status ||
                        audioListeningController?.isCurrentStatus(status) != true
                    ) it else it.copy(audioListeningStatus = status)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) publish.run() else mainHandler.post(publish)
        }, onDevices = { devices ->
            mainHandler.post { if (!serviceDestroyed) cameraState.update { it.copy(audioListeningOutputs = devices) } }
        })
        storageExecutor.execute {
            val report = try { stillSaver.recoverInterrupted() } catch (failure: Exception) {
                android.util.Log.e("StillRecovery", "Interrupted capture journal unavailable", failure)
                com.librestatic.opencinecam.storage.StillRecoveryReport(unresolvedGroups = 1)
            }
            mainHandler.post { if (!serviceDestroyed) cameraState.update { it.copy(stillRecovery = report) } }
        }
        recoveryExecutor.execute {
            val recordingReport = try { com.librestatic.opencinecam.storage.RecordingCaptureRecovery(this).recover() } catch (failure: Exception) {
                android.util.Log.e("RecordingRecovery", "Interrupted take journal unavailable", failure)
                com.librestatic.opencinecam.storage.RecordingRecoveryReport(unresolvedGroups = 1)
            }.let { it.copy(rawTakes = it.rawTakes + recoverRawTakes()) }
            // Merged: a drive mounted meanwhile may have reported its own pass already.
            mainHandler.post {
                if (!serviceDestroyed) cameraState.update { it.copy(recordingRecovery = mergeRecordingRecovery(it.recordingRecovery, recordingReport)) }
            }
        }
        recordingVolumeMonitor = try { RecordingVolumeMonitor(this) } catch (failure: Exception) {
            android.util.Log.e("RecordingDestination", "Removable volumes cannot be watched; takes go to the phone", failure)
            null
        }
        recordingVolumeMonitor?.let { monitor -> settingsScope.launch { monitor.events.collect { onRecordingVolumeEvent(it) } } }
        driveArrivalMonitor = try { com.librestatic.opencinecam.storage.UsbDriveArrivalMonitor(this) } catch (failure: Exception) {
            android.util.Log.e("RecordingDestination", "USB drives arriving cannot be watched; a starting drive reads as not connected", failure)
            null
        }
        // A drive starting, or its window closing, changes the next take's fallback wording; the first value is the refresh below.
        driveArrivalMonitor?.let { monitor -> settingsScope.launch { monitor.arriving.drop(1).collect { refreshRecordingDestination() } } }
        refreshRecordingDestination()
        physicalOrientationTracker = PhysicalOrientationTracker(this)
        physicalOrientationTracker.start()
        foldClosureTracker = FoldClosureTracker(this) {
            if (!settings.subjectDisplay.continueRecordingOnFold) localBinder.stopRecording()
        }.also { tracker -> cameraState.value = cameraState.value.copy(foldClosureSensorAvailable = tracker.start()) }
        val repository = SettingsRepositories.get(this)
        repository.update { it.forOperatorStartup() }
        localBinder.applySettings(repository.states.value)
        settingsScope.launch { repository.states.collect { localBinder.applySettings(it) } }
        // The network drive (PLAN-071 §8) never runs while a take prepares, records or finalises.
        settingsScope.launch { cameraState.collect { com.librestatic.opencinecam.share.NetworkShare.reportTakeActive(it.reviewBlockedByTake()) } }
        // The clip sidecar's camera block follows the reported exposure, white balance and zoom while a take records.
        settingsScope.launch { cameraState.collect { takeCameraTracker?.sample(it) } }
        settingsScope.launch {
            val library = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LutLibraries.get(this@CaptureService) }
            recordingLutLibrary = library
            library.states.collect {
                val selections = library.activeSelections()
                val next = selections.operator
                if (!serviceDestroyed && monitorLutIdentity(next) != monitorLutIdentity(currentOperatorLut)) {
                    currentOperatorLut = next
                    cameraState.update { state -> state.copy(operatorLutStatus = currentOperatorLut?.let { selected ->
                        OperatorLutStatus(selected.cube.sha256, OperatorLutState.WAITING_FOR_GPU, selectionId = monitorLutIdentity(selected))
                    } ?: OperatorLutStatus()) }
                    previewEngine.setOperatorLut(currentOperatorLut)
                    // Reuse existing freeze/reopen policy; selecting a monitor must not stop a take.
                    localBinder.applySettings(repository.states.value)
                }
                if (!serviceDestroyed && monitorLutIdentity(selections.subject) != monitorLutIdentity(currentSubjectLut)) {
                    currentSubjectLut = selections.subject
                    resetSubjectLutStatus()
                    previewEngine.setSubjectLut(currentSubjectLut)
                }
                if (!serviceDestroyed && monitorLutIdentity(selections.recording) != monitorLutIdentity(currentRecordingLut)) {
                    currentRecordingLut = selections.recording
                    cameraState.update { state ->
                        val intent = recordingLutIntent.get()
                        state.copy(recordingLutStatus = if (intent == null) waitingRecordingLutStatus() else state.recordingLutStatus,
                            recordingLutSelectionPending = intent != null && monitorLutIdentity(intent.lut) != monitorLutIdentity(currentRecordingLut))
                    }
                    localBinder.applySettings(repository.states.value)
                }
            }
        }
    }

    private fun waitingRecordingLutStatus(): OperatorLutStatus = currentRecordingLut?.let {
        OperatorLutStatus(it.cube.sha256, OperatorLutState.WAITING_FOR_GPU, selectionId = monitorLutIdentity(it))
    } ?: OperatorLutStatus()

    private fun frozenRecordingLut(): MonitorLut? {
        val reservation = requireNotNull(transferCapture.get()) { "Recording reservation missing" }
        return requireNotNull(recordingLutIntent.get()?.takeIf { it.owner === reservation }) { "Recording LUT intent missing" }.lut
    }

    private fun waitingSubjectLutStatus(): OperatorLutStatus = currentSubjectLut?.let {
        OperatorLutStatus(it.cube.sha256, OperatorLutState.WAITING_FOR_GPU, selectionId = monitorLutIdentity(it))
    } ?: OperatorLutStatus()

    private fun resetSubjectLutStatus() {
        subjectStatus.value = subjectStatus.value.copy(lutStatus = null)
        cameraState.update { it.copy(subjectLutStatus = waitingSubjectLutStatus()) }
    }

    /** Only the current PCM producer may publish an applied-gain receipt. */
    private fun newAudioLevelConsumer(): (AudioLevelSnapshot) -> Unit {
        retireAudioLevelConsumer(clearLevels = true)
        val epoch = audioLevelEpoch.get()
        return { snapshot ->
            mainHandler.post {
                if (!serviceDestroyed && audioLevelEpoch.get() == epoch) cameraState.update { current ->
                    if (serviceDestroyed || audioLevelEpoch.get() != epoch) current else current.copy(
                        audioLevels = snapshot,
                        audioClipLatched = current.audioClipLatched || snapshot.clipped,
                        audioMonitoringActive = true,
                    )
                }
            }
        }
    }

    private fun newAudioListeningSink(): PcmListeningSink {
        val epoch = audioLevelEpoch.get()
        return PcmListeningSink { buffer, byteCount, encoding, rate, channels ->
            if (serviceDestroyed || audioLevelEpoch.get() != epoch) false
            else audioListeningController?.offer(buffer, byteCount, encoding, rate, channels,
                isCurrent = { !serviceDestroyed && audioLevelEpoch.get() == epoch }) ?: false
        }
    }

    private fun retireAudioLevelConsumer(clearLevels: Boolean = false) {
        audioLevelEpoch.incrementAndGet()
        audioListeningController?.clearProducer()
        cameraState.update { it.copy(audioLevels = if (clearLevels) null else it.audioLevels, audioMonitoringActive = false) }
    }

    private fun rememberPreviewAudioRetirement(receipt: CompletableFuture<Unit>) {
        previewAudioRetirement = CompletableFuture.allOf(previewAudioRetirement, receipt).thenApply { Unit }
    }

    private fun stopPreviewAudioMonitor(clearLevels: Boolean = false): CompletableFuture<Unit> {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Fence queued PCM immediately; native owner changes remain serialized on main.
            retireAudioLevelConsumer(clearLevels)
            val receipt = CompletableFuture<Unit>()
            mainHandler.post {
                stopPreviewAudioMonitor(clearLevels).whenComplete { _, failure ->
                    if (failure == null) receipt.complete(Unit) else receipt.completeExceptionally(failure)
                }
            }
            return receipt
        }
        previewAudioGeneration++
        val monitor = previewAudioMonitor
        previewAudioMonitor = null
        if (monitor != null || clearLevels) retireAudioLevelConsumer(clearLevels)
        if (monitor != null) rememberPreviewAudioRetirement(monitor.closeAsync())
        cameraState.update { it.copy(
            audioLevels = if (clearLevels) null else it.audioLevels,
            audioMonitoringActive = false,
        ) }
        // A caller may cancel its wait, never the actual owner's retirement.
        return previewAudioRetirement.thenApply { Unit }
    }

    private fun previewAudioEligible(): Boolean {
        val current = cameraState.value
        return !serviceDestroyed && attachedPreviewSurface?.isValid == true &&
            current.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) &&
            current.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG) &&
            transferCapture.get() == null && !transferWaiting && !recordingWbPreparing &&
            settings.audioEnabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startPreviewAudioMonitorIfEligible() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startPreviewAudioMonitorIfEligible() }
            return
        }
        val retirement = stopPreviewAudioMonitor(clearLevels = false)
        val generation = previewAudioGeneration
        if (!previewAudioEligible()) {
            if (!settings.audioEnabled || cameraState.value.selectedMode !in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG)) {
                cameraState.update { it.copy(audioLevels = null, audioMonitoringActive = false, activeAudioInput = null) }
            }
            return
        }
        retirement.whenComplete { _, retiredFailure ->
            mainHandler.post {
                if (generation != previewAudioGeneration || !previewAudioEligible()) return@post
                if (retiredFailure != null) {
                    cameraState.update { it.copy(audioMonitoringActive = false,
                        message = getString(R.string.audio_retirement_failed)) }
                    return@post
                }
                try {
                    AudioRetirementGate.requireIdle()
                    val onLevel = newAudioLevelConsumer()
                    val epoch = audioLevelEpoch.get()
                    if (audioInputRefreshDeferred) {
                        audioInputRefreshDeferred = false
                        localBinder.refreshAudioCapabilities()
                    }
                    val monitorSettings = cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings
                    val monitor = PreviewAudioMonitor.create(
                        this,
                        monitorSettings,
                        onLevel,
                        onFailure = { failure ->
                            mainHandler.post failedAudio@ {
                                if (serviceDestroyed || audioLevelEpoch.get() != epoch) return@failedAudio
                                stopPreviewAudioMonitor(clearLevels = false)
                                cameraState.update { it.copy(audioMonitoringActive = false,
                                    message = getString(R.string.capture_audio_monitor_unavailable, failure.message ?: failure.javaClass.simpleName)) }
                            }
                        },
                        listeningSink = newAudioListeningSink(),
                        inputGuard = newAudioRouteGuard(monitorSettings, AudioInputLossAction.IGNORE),
                    )
                    previewAudioMonitor = monitor
                    monitor.start()
                } catch (failure: Throwable) {
                    if (failure is PreviewAudioMonitorCreationFailure) rememberPreviewAudioRetirement(failure.retirement)
                    stopPreviewAudioMonitor(clearLevels = false)
                    cameraState.update { it.copy(audioMonitoringActive = false,
                        message = getString(R.string.capture_audio_monitor_unavailable, failure.message ?: failure.javaClass.simpleName)) }
                }
            }
        }
    }

    private suspend fun awaitPreviewAudioRetirement(receipt: CompletableFuture<Unit>) {
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            receipt.whenComplete { _, failure ->
                if (failure == null) continuation.resumeWith(Result.success(Unit))
                else continuation.resumeWith(Result.failure(failure))
            }
            // Coroutine cancellation does not cancel or release the native owner.
        }
    }

    private val subjectSurfaces = SubjectSurfaceRegistry<Surface>()
    private val subjectStatus = MutableStateFlow(SubjectPreviewStatus())
    private var subjectPortClosed = false
    @Volatile private var activePreviewKey: List<Any?>? = null
    private val subjectPreviewPort = object : SubjectPreviewPort {
        override val statuses = subjectStatus.asStateFlow()
        override fun attach(surface: Surface, rotationDegrees: Int, frameRate: Float): AutoCloseable {
            if (subjectPortClosed) return AutoCloseable { }
            subjectSurfaces.current?.let { previewEngine.detachSubjectPreview(it.token) }
            val lease = subjectSurfaces.attach(surface, rotationDegrees, frameRate)
            subjectStatus.value = SubjectPreviewStatus()
            resetSubjectLutStatus()
            updateSubjectTarget()
            return AutoCloseable {
                mainHandler.post {
                    if (!subjectPortClosed && subjectSurfaces.release(lease.token)) {
                        previewEngine.detachSubjectPreview(lease.token)
                        subjectStatus.value = SubjectPreviewStatus()
                        resetSubjectLutStatus()
                        if (attachedPreviewSurface == null && parkedPreviewSurface == null && cameraState.value.phase != CameraUiPhase.RECORDING) localBinder.detachPreview()
                    }
                }
            }
        }
    }

    /** The last calibration read from the store, keyed by logical camera id; null value = none saved. */
    @Volatile private var lensCalibrationCache: Pair<String, com.librestatic.opencinecam.core.model.CalibrationSet?>? = null

    // ---- Lens lock (PLAN-070 slice 3a, ADR-0036) ----

    /**
     * The engine refused the lock of the session being opened (high speed, night, or a lens it
     * cannot serve at all): that session runs on the logical camera unlocked. A failed physical
     * open or configuration is not a fallback here: the engine serves it by the GPU crop
     * (`onLensLockCrop`), which still counts as a locked session. Reset by every opening.
     */
    @Volatile private var lensLockFallback = false

    /** The lenses [cameraId] can be locked to, widest first (the engine caches the characteristics read). */
    private fun lensLockOptions(cameraId: String?): List<com.librestatic.opencinecam.LensLockOption> =
        cameraId?.let { id ->
            previewEngine.lensLockCandidates(id).map { com.librestatic.opencinecam.LensLockOption(it.physicalId, it.anchorRatio, it.focalMm) }
        }.orEmpty()

    /**
     * The lock every preview start of [cameraId] passes to the engine, or null to open the logical
     * camera: no saved or valid lock, a calibration run, constrained high speed or night photo.
     */
    private fun currentLensLockBinding(cameraId: String? = cameraState.value.selectedCameraId): com.librestatic.opencinecam.camera.LensLockBinding? {
        val current = cameraState.value
        return lensLockBindingFor(
            cameraId,
            lensLockOptions(cameraId),
            cameraId?.let { settings.lensLock[it] },
            calibrating = calibrationRun != null,
            highSpeed = lensLockHighSpeed(current.selectedMode, current.activeVideoProfile?.constrainedHighSpeed == true,
                current.activeLogProfile?.constrainedHighSpeed == true),
            night = nightExtensionActive(current.selectedMode),
        )
    }

    /** True while [cameraId]'s session opens (or runs on) a locked physical lens. */
    private fun lensLockedSession(cameraId: String?): Boolean = !lensLockFallback && currentLensLockBinding(cameraId) != null

    /**
     * Ends a direct-physical or GPU-crop route: puts back the logical camera's zoom range and anchors
     * that a locked session replaced, and drops the switched-lens warning. A no-op when no lock was
     * being served.
     */
    private fun clearLensLockRoute() {
        cameraState.update { state ->
            if (state.lensLock.route == null && !state.lensLock.lensSwitchedWarning) return@update state
            if (state.lensLock.route == null) return@update state.copy(lensLock = state.lensLock.copy(lensSwitchedWarning = false))
            val range = state.descriptor?.effectiveZoomRange
            state.copy(
                lensLock = state.lensLock.copy(route = null, lensSwitchedWarning = false),
                zoomMinRatio = range?.start ?: state.zoomMinRatio,
                zoomMaxRatio = range?.endInclusive ?: state.zoomMaxRatio,
                zoomSupported = range?.let { it.start < it.endInclusive } ?: state.zoomSupported,
                opticalAnchors = state.descriptor?.opticalAnchors ?: state.opticalAnchors,
                zoomRatio = range?.let { state.zoomRatio.coerceIn(it.start, it.endInclusive) } ?: state.zoomRatio,
            )
        }
    }

    /** Publishes the lock options of [cameraId] and its saved lock when it is one of them. */
    private fun refreshLensLockState(cameraId: String? = cameraState.value.selectedCameraId) {
        val options = lensLockOptions(cameraId)
        val locked = cameraId?.let { settings.lensLock[it] }?.takeIf { id -> options.size >= 2 && options.any { it.physicalId == id } }
        cameraState.update { state ->
            val route = state.lensLock.route.takeIf { locked != null && state.lensLock.lockedPhysicalId == locked }
            val next = state.lensLock.copy(options = options, lockedPhysicalId = locked, route = route,
                lensSwitchedWarning = lensLockSwitchedWarning(state.lensLock.lensSwitchedWarning, route))
            if (next == state.lensLock) state else state.copy(lensLock = next)
        }
    }

    /**
     * The calibration of [cameraId] on this build when the video correction is on. Read lazily so
     * the GPU-viewfinder decision for a camera being switched to sees its own calibration.
     */
    private fun lensCalibrationFor(cameraId: String?): com.librestatic.opencinecam.core.model.CalibrationSet? =
        if (settings.lensCorrectionVideo) loadLensCalibration(cameraId) else null

    /** Like [lensCalibrationFor] for still photos, gated by the separate "apply to photos" setting. */
    private fun photoLensCalibrationFor(cameraId: String?): com.librestatic.opencinecam.core.model.CalibrationSet? =
        if (settings.lensCorrectionPhotos) loadLensCalibration(cameraId) else null

    /** The cached store read shared by the video and photo gates; null while a calibration run is active. */
    private fun loadLensCalibration(cameraId: String?): com.librestatic.opencinecam.core.model.CalibrationSet? {
        // A run measures the uncorrected signal, so nothing is applied while it is going.
        if (calibrationRun != null) return null
        if (cameraId == null) return null
        // The logical calibration describes the HAL's lens switching; a locked lens is not corrected by it.
        if (lensLockedSession(cameraId)) return null
        lensCalibrationCache?.takeIf { it.first == cameraId }?.let { return it.second }
        val set = runCatching {
            com.librestatic.opencinecam.LensCalibrationStore.forContext(this)
                .load(Build.FINGERPRINT, cameraId, com.librestatic.opencinecam.core.model.CalibrationSessionKind.LOGICAL)
        }.getOrNull()
        lensCalibrationCache = cameraId to set
        return set
    }

    /**
     * Hands the calibration of [cameraId] (or null) to the engine. Called on camera open and when
     * the correction settings change; the engine applies it to the current and later pipelines and
     * freezes it for the length of a take.
     */
    private fun refreshLensCorrection(cameraId: String? = cameraState.value.selectedCameraId) {
        previewEngine.setLensCorrection(lensCalibrationFor(cameraId), settings.lensCorrectionAlignment)
        previewEngine.setPhotoLensCorrection(photoLensCalibrationFor(cameraId), settings.lensCorrectionAlignment)
    }

    // ---- Lens calibration run (PLAN-070) ----

    private class CalibrationRestore(val cameraId: String, val mode: CaptureMode, val zoom: Float)

    /** One run, from the binder call to its end. Fields are written by main and by the worker thread. */
    private class CalibrationRun(val restore: CalibrationRestore) {
        @Volatile var runner: com.librestatic.opencinecam.camera.calibration.LensCalibrationRunner? = null
        @Volatile var thread: Thread? = null
        @Volatile var cancelled = false
        /** The camera went away, reopened under the run or the service is being destroyed. */
        @Volatile var lost = false
        /** The GPU readback could not be provided. */
        @Volatile var unsupported = false
        /** True once the session the run measures is up; a reopen after that ends the run. */
        @Volatile var sessionLocked = false
    }

    /** Non-null from the start call until the run's outcome has been applied (main thread writes). */
    @Volatile private var calibrationRun: CalibrationRun? = null
    /** The finished run's set, waiting for Save or Discard. Main thread only. */
    private var calibrationPending: com.librestatic.opencinecam.core.model.CalibrationSet? = null
    /** The calibration saved for the selected camera on this build, loaded whether or not the correction is on. */
    @Volatile private var savedCalibration: com.librestatic.opencinecam.core.model.CalibrationSet? = null
    @Volatile private var liveRedBlueRatio: Float? = null
    private val openingCount = AtomicInteger()

    private val calibrationPrepareTimeoutMs = 15_000L
    private val calibrationCaptureTimeoutMs = 10_000L
    private val calibrationModeSwitchGraceMs = 2_000L
    private val calibrationQueueSize = 8
    /** How long a start waits for the viewfinder to attach and preview; 0 when no start is waiting. Main thread. */
    private var calibrationStartDeadlineMs = 0L
    private val calibrationStartWaitMs = 10_000L
    private val calibrationStartRetry = Runnable { localBinder.beginLensCalibration() }
    private val illuminantMismatchLimit = 0.15f

    private fun calibrationLensesOf(descriptor: Camera2CameraDescriptor?): List<com.librestatic.opencinecam.camera.calibration.CalibrationLens> =
        descriptor?.opticalAnchors.orEmpty()
            .filter { it.physicalCameraId != null }
            .distinctBy { it.physicalCameraId }
            .map { com.librestatic.opencinecam.camera.calibration.CalibrationLens(it.physicalCameraId!!, it.ratio) }

    /** Logs each calibration cycle and, in debuggable builds, keeps its frames under the app's external files. */
    private fun logCalibrationCycle(c: com.librestatic.opencinecam.camera.calibration.CalibrationCycle) {
        val g = c.geometry
        android.util.Log.i("LensCalibration", "cycle ${c.targetId}#${c.cycle} zoom ${c.referenceZoom}/${c.targetZoom} " +
            "geometry patches=${g.patchesUsed} inliers=${g.inliers} rms=${g.residualPx} parallax=${g.parallaxSuspect} " +
            "color cells=${c.color.cellsUsed} cond=${c.color.conditionNumber} fallback=${c.color.diagonalFallback} dE=${c.color.residualDeltaEBefore}->${c.color.residualDeltaE}")
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val dir = getExternalFilesDir("calibration-debug") ?: return
        runCatching {
            for ((name, frame) in listOf("ref" to c.reference, "tgt" to c.target)) {
                val bitmap = android.graphics.Bitmap.createBitmap(frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(frame.pixels))
                java.io.File(dir, "${c.targetId}-${c.cycle}-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    private fun calibrationRows(progress: com.librestatic.opencinecam.camera.calibration.RunProgress) = progress.lenses.map {
        com.librestatic.opencinecam.LensCalibrationLensRow(
            physicalId = it.physicalId,
            nominalRatio = it.nominalRatio,
            status = com.librestatic.opencinecam.LensCalibrationLensStatus.valueOf(it.status.name),
            progress = it.progress,
            colorDeltaEBefore = it.colorDeltaEBefore,
            colorDeltaEAfter = it.colorDeltaEAfter,
            alignPxBefore = it.alignPxBefore,
            alignPxAfter = it.alignPxAfter,
            score = it.score,
            flags = it.flags,
        )
    }

    private fun savedCalibrationRows(
        set: com.librestatic.opencinecam.core.model.CalibrationSet,
        anchors: List<ZoomAnchor>,
    ) = set.lenses.map { lens ->
        val reference = lens.physicalId == set.referencePhysicalId
        com.librestatic.opencinecam.LensCalibrationLensRow(
            physicalId = lens.physicalId,
            nominalRatio = anchors.firstOrNull { it.physicalCameraId == lens.physicalId }?.ratio ?: 0f,
            status = if (reference) com.librestatic.opencinecam.LensCalibrationLensStatus.REFERENCE
                else com.librestatic.opencinecam.LensCalibrationLensStatus.DONE,
            progress = 1f,
            colorDeltaEAfter = lens.quality.colorResidualDeltaE.takeIf { !reference },
            alignPxAfter = lens.quality.alignResidualPx.takeIf { !reference },
            score = lens.quality.score.takeIf { !reference },
            flags = lens.quality.flags,
        )
    }.sortedBy { it.nominalRatio }

    /**
     * Reads the saved calibration of [descriptor]'s camera for the saved-state fields and the
     * illuminant check, whatever the video correction setting. A separate read from
     * [lensCalibrationFor], which stays gated by that setting.
     */
    private fun refreshSavedCalibration(descriptor: Camera2CameraDescriptor?) {
        val cameraId = descriptor?.cameraId
        val set = cameraId?.let {
            runCatching {
                com.librestatic.opencinecam.LensCalibrationStore.forContext(this)
                    .load(Build.FINGERPRINT, it, com.librestatic.opencinecam.core.model.CalibrationSessionKind.LOGICAL)
            }.getOrNull()
        }
        savedCalibration = set
        liveRedBlueRatio = null
        lensCalibrationState.update {
            it.copy(
                savedCreatedAtMs = set?.createdAtMs,
                savedLenses = if (set != null && descriptor != null) savedCalibrationRows(set, descriptor.opticalAnchors) else emptyList(),
                illuminantMismatch = false,
            )
        }
    }

    /** Compares the scene's AWB red/blue gain ratio with the saved calibration's; more than 15% apart is a mismatch. */
    private fun noteLiveIlluminant(gains: List<Float>) {
        if (calibrationRun != null || gains.size < 4) return
        val saved = savedCalibration?.illuminantHint?.awbGains?.takeIf { it.size >= 4 && it[3] > 0f && it[0] > 0f }
        val live = gains[0] / gains[3]
        val mismatch = if (saved == null || !live.isFinite() || live <= 0f) false else {
            val smoothed = liveRedBlueRatio?.let { it * 0.9f + live * 0.1f } ?: live
            liveRedBlueRatio = smoothed
            kotlin.math.abs(smoothed / (saved[0] / saved[3]) - 1f) > illuminantMismatchLimit
        }
        if (lensCalibrationState.value.illuminantMismatch != mismatch) lensCalibrationState.update { it.copy(illuminantMismatch = mismatch) }
    }

    /** The camera went away (or a reopen invalidated the measurement) while a run was going. */
    private fun calibrationCameraLost() {
        val run = calibrationRun ?: return
        run.lost = true
        run.runner?.abort(com.librestatic.opencinecam.camera.calibration.RunFailure.CAMERA_LOST)
    }

    private fun calibrationWaitUntil(run: CalibrationRun, timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!run.lost && !run.cancelled && !serviceDestroyed) {
            if (condition()) return true
            if (android.os.SystemClock.elapsedRealtime() >= deadline) return false
            try { Thread.sleep(50) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private inner class ServiceCalibrationRig(
        private val queue: LinkedBlockingQueue<com.librestatic.opencinecam.camera.calibration.CalibrationSample>,
    ) : com.librestatic.opencinecam.camera.calibration.CalibrationRig {
        override fun setZoom(ratio: Float) {
            queue.clear()
            previewEngine.setZoomRatio(ratio)
        }

        override fun nextSample(timeoutMs: Long): com.librestatic.opencinecam.camera.calibration.CalibrationSample? =
            try { queue.poll(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }

        override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
    }

    /** Worker thread: waits for the video graph, runs the solver, then hands the outcome to main. */
    private fun runCalibration(
        run: CalibrationRun,
        lenses: List<com.librestatic.opencinecam.camera.calibration.CalibrationLens>,
        mode: CaptureMode,
        zoomRange: ClosedFloatingPointRange<Float>,
        openedBefore: Int,
        switched: Boolean,
    ) {
        var outcome: com.librestatic.opencinecam.camera.calibration.RunOutcome? = null
        try {
            if (switched) calibrationWaitUntil(run, calibrationModeSwitchGraceMs) { openingCount.get() > openedBefore }
            val ready = calibrationWaitUntil(run, calibrationPrepareTimeoutMs) {
                val s = cameraState.value
                s.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) && s.selectedMode == mode &&
                    s.selectedCameraId == run.restore.cameraId && previewEngine.usesGpuViewfinder()
            }
            val queue = LinkedBlockingQueue<com.librestatic.opencinecam.camera.calibration.CalibrationSample>(calibrationQueueSize)
            val started = ready && calibrationWaitUntil(run, calibrationCaptureTimeoutMs) {
                previewEngine.startCalibrationCapture(
                    sink = { sample -> while (!queue.offer(sample)) queue.poll() },
                    onUnavailable = {
                        run.unsupported = true
                        run.runner?.abort(com.librestatic.opencinecam.camera.calibration.RunFailure.INTERNAL)
                    },
                )
            }
            if (started && !run.lost && !run.cancelled) {
                run.sessionLocked = true
                val runner = com.librestatic.opencinecam.camera.calibration.LensCalibrationRunner(
                    rig = ServiceCalibrationRig(queue),
                    lenses = lenses,
                    logicalId = run.restore.cameraId,
                    fingerprint = Build.FINGERPRINT,
                    sourcePath = previewEngine.calibrationSourcePath() ?: com.librestatic.opencinecam.core.model.CalibrationSourcePath.SDR,
                    zoomRange = zoomRange,
                    onProgress = { progress ->
                        if (calibrationRun === run && !run.cancelled) lensCalibrationState.update {
                            it.copy(
                                phase = com.librestatic.opencinecam.LensCalibrationPhase.valueOf(progress.phase.name),
                                referencePhysicalId = progress.referencePhysicalId,
                                lenses = calibrationRows(progress),
                            )
                        }
                    },
                    onCycle = ::logCalibrationCycle,
                )
                run.runner = runner
                // A flag set before the runner existed is carried over.
                if (run.lost) runner.abort(com.librestatic.opencinecam.camera.calibration.RunFailure.CAMERA_LOST)
                if (run.cancelled) runner.cancel()
                outcome = runner.run()
            }
        } catch (_: Throwable) {
            outcome = null
        } finally {
            previewEngine.stopCalibrationCapture()
        }
        mainHandler.post { finishCalibration(run, outcome) }
    }

    /**
     * Main thread. Ends [run]: releases the controls, restores the mode, zoom and lens correction
     * the operator had, and publishes the outcome (a result waiting for Save, a failure, or idle).
     */
    private fun finishCalibration(run: CalibrationRun, outcome: com.librestatic.opencinecam.camera.calibration.RunOutcome?) {
        if (calibrationRun !== run || serviceDestroyed) return
        calibrationRun = null
        run.runner = null
        cameraState.update { it.copy(lensCalibrationRunning = false) }
        val restore = run.restore
        // The camera may be gone; the mode and zoom are still put back, without a reopen then.
        // A saved lens lock was set aside for the run; it returns with one restart.
        val relock = !run.lost && currentLensLockBinding() != null
        if (cameraState.value.selectedMode != restore.mode) localBinder.selectMode(restore.mode, reopen = !run.lost && !relock)
        if (relock) attachedPreviewSurface?.takeIf { it.isValid }?.let { localBinder.attachPreview(it, attachedPreviewRotationDegrees) }
        localBinder.setZoomRatio(restore.zoom)
        refreshLensCorrection()
        val success = outcome as? com.librestatic.opencinecam.camera.calibration.RunOutcome.Success
        val failure = outcome as? com.librestatic.opencinecam.camera.calibration.RunOutcome.Failure
        (success?.progress ?: failure?.progress)?.lenses?.forEach {
            android.util.Log.i("LensCalibration", "lens ${it.physicalId} ${it.status} score=${it.score} dE=${it.colorDeltaEBefore}->${it.colorDeltaEAfter} " +
                "px=${it.alignPxBefore}->${it.alignPxAfter} flags=${it.flags}")
        }
        failure?.let { android.util.Log.i("LensCalibration", "run failed: ${it.failure}") }
        when {
            run.cancelled -> lensCalibrationState.update {
                it.copy(phase = com.librestatic.opencinecam.LensCalibrationPhase.IDLE, referencePhysicalId = null, lenses = emptyList(), failure = null)
            }
            success != null && !run.lost -> {
                calibrationPending = success.set
                lensCalibrationState.update {
                    it.copy(
                        phase = com.librestatic.opencinecam.LensCalibrationPhase.RESULT,
                        logicalCameraId = restore.cameraId,
                        referencePhysicalId = success.set.referencePhysicalId,
                        lenses = calibrationRows(success.progress),
                        failure = null,
                    )
                }
            }
            else -> {
                val reason = when {
                    run.lost -> com.librestatic.opencinecam.LensCalibrationFailure.CAMERA_LOST
                    run.unsupported || outcome == null -> com.librestatic.opencinecam.LensCalibrationFailure.UNSUPPORTED_MODE
                    failure != null -> com.librestatic.opencinecam.LensCalibrationFailure.valueOf(failure.failure.name)
                    else -> com.librestatic.opencinecam.LensCalibrationFailure.INTERNAL
                }
                lensCalibrationState.update {
                    it.copy(
                        phase = com.librestatic.opencinecam.LensCalibrationPhase.FAILED,
                        logicalCameraId = restore.cameraId,
                        lenses = failure?.let { f -> calibrationRows(f.progress) } ?: it.lenses,
                        failure = reason,
                    )
                }
            }
        }
    }

    private fun photoPreviewMode(mode: CaptureMode = cameraState.value.selectedMode): Boolean =
        mode in setOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL)

    private fun desiredGpuViewfinder(mode: CaptureMode = cameraState.value.selectedMode): Boolean =
        // The extension session renders straight into the viewfinder surface, never through the GPU path.
        !nightExtensionActive(mode) && (
        // Video always previews through the GPU viewfinder so REC only starts the encoder on the
        // live graph. A direct preview had to be torn down at REC (EGL cannot reconnect to the
        // SurfaceView the camera just released), which froze the viewfinder for the whole take.
        // A constrained high-speed take still reaches the encoder directly (startVideo); only the
        // 30 fps preview share goes through the GPU to the operator and subject windows.
        // RAW_VIDEO previews through the GPU path too (the engine forces it for a RAW session).
        mode in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG, CaptureMode.TIME_LAPSE, CaptureMode.RAW_VIDEO) ||
        // The subject preview rides the GPU viewfinder in every mode it supports.
        (settings.subjectDisplay.wantsCameraPreview && subjectPreviewBlock(mode) == null) ||
        // A lens correction shows only on the GPU path.
        (photoPreviewMode(mode) && (currentOperatorLut != null || lensCalibrationFor(cameraState.value.selectedCameraId) != null)) ||
        // The lens lock's GPU crop zooms only on the GPU path. The restart this causes reopens on
        // the same route: the engine remembers the failed lens and the route survives onOpening.
        lensLockCropNeedsGpuViewfinder(photoPreviewMode(mode), cameraState.value.lensLock.route))

    /** Night photo applies to PHOTO only, on a camera that offers the extension (API 31+). */
    private fun nightExtensionActive(mode: CaptureMode = cameraState.value.selectedMode): Boolean {
        if (mode != CaptureMode.PHOTO) return false
        val requested = com.librestatic.opencinecam.camera.nightPhotoRequested(settings.nightPhotoMode, nightTransientEngaged)
        return com.librestatic.opencinecam.camera.nightExtensionEngaged(requested, Build.VERSION.SDK_INT, cameraState.value.descriptor?.nightExtension)
    }

    /** The quick toggle's session-only state: counts in AUTO only and is dropped with the mode, camera or service. */
    @Volatile private var nightTransientEngaged = false
    private val nightSceneDetector = com.librestatic.opencinecam.camera.NightSceneDetector()

    private fun clearNightTransient() {
        nightTransientEngaged = false
        cameraState.update { if (it.nightTransientEngaged) it.copy(nightTransientEngaged = false) else it }
    }

    private fun selectedStillPhotoFormat(mode: CaptureMode = cameraState.value.selectedMode): StillPhotoFormat = when (mode) {
        // The extension session delivers JPEG only: no RAW companion and no HEIC.
        CaptureMode.PHOTO -> if (nightExtensionActive(mode)) StillPhotoFormat.JPEG else settings.photoFormat
        CaptureMode.RAW_PHOTO -> StillPhotoFormat.DNG
        else -> StillPhotoFormat.JPEG
    }

    /** RAW photo keeps the camera's own companion size; the DNG is the sensor readout either way. */
    private fun selectedStillSize(): Size? = cameraState.value.takeIf { it.selectedMode != CaptureMode.RAW_PHOTO }
        ?.activeStillSize?.let { (width, height) -> Size(width, height) }

    private fun previewKey(): List<Any?> = cameraState.value.let {
        listOf(it.selectedCameraId, it.selectedMode, it.targetVideoWidth, it.targetVideoHeight, it.targetFps, desiredGpuViewfinder(), selectedStillPhotoFormat(), selectedStillSize(), nightExtensionActive(), lowLightFpsActive(it, settings), currentLensLockBinding(), rawVideoSessionConfig())
    }

    // OCC-PLAN-071 RAW video. Main-thread state unless marked otherwise.
    private val rawRecorder by lazy { RawVideoRecorder() }
    /** Serialises arming, disarming, take start and take stop (which joins the writer) off main. */
    private val rawControl = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "occ-raw-control") }
    private val rawQualificationStore by lazy { RawVideoQualificationStore.forContext(this) }
    /** Loaded on first use (one small file); [LocalBinder.refreshRawVideoQualifications] reloads it. */
    @Volatile private var rawQualifications: RawVideoQualificationSet? = null
    private val rawRaw10Sizes = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Int, Int>>>()
    /** Cameras that refused the RAW_VIDEO graph in this service session (gate FAILED). */
    private val rawVideoFailedCameras = mutableSetOf<String>()
    /** The configuration (and near-lossless noise profile) the ring was last asked to arm for; null while disarmed. */
    private var rawArmTarget: Pair<RawVideoConfig, com.librestatic.opencinecam.rawvideo.RawNoiseProfile?>? = null
    /** Set (on the control thread) when the ring for this configuration could not be planned or allocated. */
    @Volatile private var rawArmNoMemory: RawVideoConfig? = null
    /** Why arming [rawArmNoMemory] failed: no RAM for the ring, or no encoder for a compressed configuration. */
    @Volatile private var rawArmFailure: String = RawVideoServiceReasons.MEMORY
    /** The ring armed now (set on the control thread), for the runtime risk ([rawVideoRingTooSmall]); null while disarmed. */
    @Volatile private var rawArmedRing: com.librestatic.opencinecam.rawvideo.RawVideoArmState? = null
    /** The mode RAW_VIDEO was entered from, restored when the camera refuses the RAW graph. */
    private var rawModeFallback: CaptureMode? = null
    /** [destination] is where the take writes, resolved when it was admitted (PLAN-072 Delivery 3). */
    /**
     * [captureNames] and [audio] are what the take was admitted with; [continuation] is set when it continues on the phone
     * a RAW take whose drive was lost (PLAN-072 Delivery 4), and names its file and metadata.
     */
    private class RawTakeSession(val takeId: String, val config: RawVideoConfig, val destination: ResolvedRecordingDestination,
        val captureNames: CaptureNameSnapshot, val audio: Boolean, val continuation: ContinuationRequest? = null) {
        var started = false
        var stopRequested = false
    }
    private var rawTake: RawTakeSession? = null

    /**
     * A RAW video qualification run (PLAN-071 decision 10). It owns the camera while it lasts: the
     * preview is stopped, the RAW ring disarmed, and nothing reopens the camera until it ends.
     */
    private class RawQualificationSession(
        val cameraId: String,
        val sensorWidth: Int,
        val sensorHeight: Int,
        val estimateMs: Long,
        /** A pass selects RAW_VIDEO (the selector and the onboarding); a requalification from Settings leaves the mode alone. */
        val selectOnPass: Boolean,
    ) {
        lateinit var qualifier: RawVideoQualifier
        /** Why the app stopped it ([RawQualificationStopReasons]); the first reason wins. */
        @Volatile var stopReason: String? = null
        /** Qualification thread only: the last progress posted to main, and when. */
        var lastPosted: RawVideoQualificationUi? = null
        var lastPostAtMs = 0L
        var batteryReceiver: android.content.BroadcastReceiver? = null
        /** Qualification thread (seeded on main before it starts): every configuration with its status. */
        @Volatile var items: List<RawQualificationItem> = emptyList()
    }
    /** Set and cleared on main; non-null while a qualification runs. */
    @Volatile private var rawQualificationSession: RawQualificationSession? = null
    /** A qualification passed before the cameras were prepared (onboarding): RAW_VIDEO is selected once they are. */
    private var rawQualificationSelectPending = false

    private fun rawControlExecute(task: Runnable): Boolean = try {
        rawControl.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun rawQualifiedConfigs(cameraId: String): List<RawVideoConfig> = runCatching {
        val set = rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it }
        set.qualifiedConfigs(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion,
            rawLookupDestinationId(), cameraId)
    }.getOrElse { failure ->
        android.util.Log.w("CaptureService", "RAW video qualifications unavailable", failure)
        emptyList()
    }

    /** Configurations of [cameraId] the storage is too slow for, still choosable by hand ([RawQualificationRisk]). */
    private fun rawRiskyRecordedConfigs(cameraId: String): List<RawVideoConfig> = runCatching {
        val set = rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it }
        RawQualificationRisk.riskyConfigs(set, rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion,
            rawLookupDestinationId(), cameraId)
    }.getOrElse { emptyList() }

    /** RAW10 output sizes of [cameraId]; cached once read, an unreadable camera reads as none. */
    private fun raw10Sizes(cameraId: String): List<Pair<Int, Int>> = rawRaw10Sizes[cameraId] ?: runCatching {
        getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(cameraId)
            .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.RAW10)?.map { it.width to it.height }.orEmpty()
    }.getOrNull()?.also { rawRaw10Sizes[cameraId] = it }.orEmpty()

    /** Configurations of [cameraId] whose newest record failed for the camera, not the storage: their compressed twins stay out. */
    private fun rawCameraFailedConfigs(cameraId: String): List<RawVideoConfig> = runCatching {
        val set = rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it }
        RawQualificationRisk.cameraFailedConfigs(set, rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion,
            rawLookupDestinationId(), cameraId)
    }.getOrElse { emptyList() }

    /**
     * PLAN-072 Delivery 4: [config] is qualified for the phone (`mediastore-documents`), and not only as a risky rate, so a
     * RAW take on a drive that was lost may continue on the phone with it. Reads the store directly: during the take the
     * gate still describes the drive.
     */
    private fun rawQualifiedOnPhone(config: RawVideoConfig): Boolean = runCatching {
        val set = rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it }
        val phone = RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS
        val key = RawVideoConfigKeys.key(config)
        val fingerprint = rawQualificationStore.fingerprint
        val protocol = rawQualificationStore.protocolVersion
        set.qualifiedConfigs(fingerprint, protocol, phone, config.cameraId).any { RawVideoConfigKeys.key(it) == key } &&
            RawQualificationRisk.riskyConfigs(set, fingerprint, protocol, phone, config.cameraId).none { RawVideoConfigKeys.key(it) == key }
    }.getOrDefault(false)

    /** The configurations a RAW_VIDEO session can run on [cameraId]: the qualified ones and the risky ones. */
    private fun rawUsableConfigs(cameraId: String): List<RawVideoConfig> {
        val sizes = raw10Sizes(cameraId)
        return if (sizes.isEmpty()) emptyList() else rawVideoUsableConfigs(cameraId, sizes,
            rawQualifiedConfigs(cameraId) + rawRiskyRecordedConfigs(cameraId), cameraFailed = rawCameraFailedConfigs(cameraId))
    }

    /**
     * The armed configuration of [cameraId] when its ring is smaller than its newest qualification
     * needed ([rawVideoRingTooSmall]): shown as risky while that ring stays armed, but never
     * steering Automatic, which keeps choosing among the passed configurations.
     */
    private fun rawRuntimeRiskyConfig(cameraId: String, usable: List<RawVideoConfig>): RawVideoConfig? {
        val armed = rawArmedRing ?: return null
        val config = armed.config.takeIf { it.cameraId == cameraId && it in usable } ?: return null
        val current = runCatching {
            (rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it })
                .current(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion)
        }.getOrElse { return null }
        val record = rawVideoNewestRecord(current, rawLookupDestinationId(), config)
        return config.takeIf { rawVideoRingTooSmall(record, armed.ringBytes) }
    }

    /**
     * The burst estimate of each [risky] configuration whose newest record has one: how long a take
     * lasts before the write cache fills and frames may drop ([RawVideoQualificationRecord.burstSeconds]).
     */
    private fun rawRiskyBurstSeconds(risky: Set<RawVideoConfig>): Map<RawVideoConfig, Long> {
        if (risky.isEmpty()) return emptyMap()
        val current = runCatching {
            (rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it })
                .current(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion)
        }.getOrElse { return emptyMap() }
        return risky.mapNotNull { config ->
            rawVideoNewestRecord(current, rawLookupDestinationId(), config)?.burstSeconds?.let { config to it }
        }.toMap()
    }

    /**
     * The bundled RAW noise profile a block-pack [config] records near-lossless with: its camera's,
     * measured at its sensor size, with the native codec loaded and, unless [ignoreSetting], near-lossless
     * chosen ([CameraSettings.rawVideoNearLossless]). Null means lossless block-pack.
     */
    private fun rawNearLosslessProfile(config: RawVideoConfig, ignoreSetting: Boolean = false): com.librestatic.opencinecam.rawvideo.RawNoiseProfile? =
        if ((!ignoreSetting && !settings.rawVideoNearLossless) || config.encoding != RawVideoConfig.ENCODING_BLOCK_PACK ||
            !com.librestatic.opencinecam.media.raw.NativeRawCodec.available) null
        else com.librestatic.opencinecam.rawvideo.RawNoiseProfiles.forCamera(this, config.cameraId, config.sensorWidth, config.sensorHeight)

    /** The risky ones among [usable] configurations of [cameraId]. */
    private fun rawRiskyConfigs(cameraId: String, usable: List<RawVideoConfig>): Set<RawVideoConfig> =
        if (usable.isEmpty()) emptySet() else rawVideoRiskyConfigs(usable, rawRiskyRecordedConfigs(cameraId))

    /** The reduced-rate ones among [usable] configurations, with the rate each achieved ([rawVideoReducedRates]). */
    private fun rawReducedRates(usable: List<RawVideoConfig>): Map<RawVideoConfig, Double?> {
        if (usable.isEmpty()) return emptyMap()
        val current = runCatching {
            (rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it })
                .current(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion)
        }.getOrElse { return emptyMap() }
        return rawVideoReducedRates(usable, current, rawLookupDestinationId())
    }

    /**
     * [state] with the RAW_VIDEO gate, the qualified configurations and the selected one for
     * [cameraId]. In RAW_VIDEO the take geometry and rate follow the selected configuration.
     */
    private fun withRawVideoGate(state: CameraUiState, cameraId: String?): CameraUiState {
        rawGateDestinationId = rawLookupDestinationId()
        val sizes = cameraId?.let(::raw10Sizes).orEmpty()
        val usable = if (cameraId == null || sizes.isEmpty()) emptyList() else rawUsableConfigs(cameraId)
        val risky = if (cameraId == null) emptySet() else rawRiskyConfigs(cameraId, usable)
        val runtimeRisky = cameraId?.let { rawRuntimeRiskyConfig(it, usable) }
        val burst = rawRiskyBurstSeconds(risky)
        val reduced = rawReducedRates(usable)
        val gate = rawVideoGate(sizes, usable, cameraId in rawVideoFailedCameras)
        val available = gate == ModeGateState.AVAILABLE
        // A risky or reduced-rate configuration runs only when chosen by hand: Automatic never resolves
        // to one, so with only such ones and Automatic chosen nothing is selected and a take is refused.
        val manualOnly = risky + reduced.keys
        // A drive take's continuation on the phone keeps that take's configuration, whatever Automatic would choose here.
        val wantedKey = rawVideoGateKey(settings.rawVideoConfig, continuationStarting ?: rawTake?.continuation ?: driveContinuation.pendingRequest)
        val selected = selectRawVideoConfig(usable, wantedKey, manualOnly).takeIf { available }
        val next = state.copy(
            modeGates = state.modeGates + (CaptureMode.RAW_VIDEO to gate),
            // The runtime risk only warns (option and capture notice); selection above ignores it.
            rawVideoOptions = if (available) usable.map {
                RawVideoConfigOption.of(it, risky = it in risky || it == runtimeRisky, burstSeconds = burst[it], achievedFps = reduced[it])
            } else emptyList(),
            rawVideoSelectedKey = selected?.let(RawVideoConfigKeys::key),
            rawVideoAutoKey = selectRawVideoConfig(usable, "", manualOnly).takeIf { available }?.let(RawVideoConfigKeys::key),
            rawVideoNearLosslessAvailable = available && usable.any { rawNearLosslessProfile(it, ignoreSetting = true) != null },
        )
        return if (next.selectedMode == CaptureMode.RAW_VIDEO && selected != null) {
            // The status line shows the RAW time left before a take too, so the free space is read here, not only by the REC ticker.
            // Idle, this is the phone's figure; with the next take on a drive the HUD reads the drive's own (captureStatusFreeBytes).
            next.copy(targetFps = selected.fps, targetVideoWidth = selected.cropWidth, targetVideoHeight = selected.cropHeight,
                availableStorageBytes = if (next.phase == CameraUiPhase.RECORDING) next.availableStorageBytes else phoneVolumeFreeBytes())
        } else next
    }

    /** The configuration a RAW_VIDEO session runs, or null outside RAW_VIDEO or without a qualified one. */
    private fun currentRawVideoConfig(state: CameraUiState = cameraState.value): RawVideoConfig? {
        if (state.selectedMode != CaptureMode.RAW_VIDEO || state.modeGates[CaptureMode.RAW_VIDEO] != ModeGateState.AVAILABLE) return null
        val cameraId = state.selectedCameraId ?: return null
        return rawUsableConfigs(cameraId).firstOrNull { RawVideoConfigKeys.key(it) == state.rawVideoSelectedKey }
    }

    /** The RAW10 stream of the session: the full sensor readout at the selected rate (the recorder crops). */
    private fun rawVideoSessionConfig(): RawVideoSessionConfig? =
        currentRawVideoConfig()?.let { RawVideoSessionConfig(it.sensorWidth, it.sensorHeight, it.fps) }

    /**
     * Keeps the RAM ring armed for the current configuration while RAW_VIDEO is selected with a
     * viewfinder, and frees it otherwise. Never during a take. The work runs on [rawControl].
     */
    private fun syncRawVideoArm() {
        if (serviceDestroyed || rawTake != null) return
        // A qualification allocates its own ring: the two never coexist.
        val wanted = currentRawVideoConfig().takeIf { attachedPreviewSurface != null && rawQualificationSession == null }
        // A block-pack configuration records near-lossless when its camera has a noise profile and the setting is on.
        val noiseProfile = wanted?.let { rawNearLosslessProfile(it) }
        val target = wanted?.let { it to noiseProfile }
        if (target == rawArmTarget) return
        rawArmTarget = target
        rawControlExecute {
            if (rawRecorder.isRecording) return@rawControlExecute
            runCatching { rawRecorder.disarm() }
            val wasArmed = rawArmedRing != null
            rawArmedRing = null
            if (wanted == null) {
                rawArmNoMemory = null
                if (wasArmed) postRawVideoGate()
                return@rawControlExecute
            }
            val plan = runCatching { RawRingBudget.plan(this, wanted.bytesPerFrame.toInt()) }.getOrNull()
            try {
                checkNotNull(plan) { "no RAM budget for a ring of ${RawRingBudget.MIN_SLOTS} frames" }
                val armed = rawRecorder.arm(wanted, plan.ringBytes, noiseProfile)
                rawArmNoMemory = null
                // Re-evaluate the runtime risk against the ring actually armed.
                rawArmedRing = armed
                postRawVideoGate()
                android.util.Log.i("CaptureService", "RAW ring armed: ${armed.slotCount} slots of ${armed.slotBytes} bytes (${plan.limitedBy})" +
                    if (armed.nearLossless) ", near-lossless (${noiseProfile?.id})" else "")
            } catch (failure: Exception) {
                rawArmFailure = if (failure is com.librestatic.opencinecam.rawvideo.RawVideoArmException) RawVideoServiceReasons.CODEC else RawVideoServiceReasons.MEMORY
                rawArmNoMemory = wanted
                android.util.Log.w("CaptureService", "RAW ring could not be armed", failure)
                if (wasArmed) postRawVideoGate()
            } catch (failure: OutOfMemoryError) {
                rawArmFailure = RawVideoServiceReasons.MEMORY
                rawArmNoMemory = wanted
                android.util.Log.w("CaptureService", "RAW ring could not be allocated", failure)
                if (wasArmed) postRawVideoGate()
            }
        }
    }

    /** Any thread: recompute the RAW_VIDEO gate and options on main (the armed ring changed). */
    private fun postRawVideoGate() {
        mainHandler.post {
            if (!serviceDestroyed) cameraState.value = withRawVideoGate(cameraState.value, cameraState.value.selectedCameraId)
        }
    }

    /** The camera a qualification runs on: the selected one, or before the cameras are prepared (onboarding) the first back camera with RAW10. */
    private fun rawQualificationCameraId(): String? = cameraState.value.selectedCameraId ?: runCatching {
        val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK && raw10Sizes(id).isNotEmpty()
        }
    }.getOrNull()

    /** The largest RAW10 size the qualifier reads, as [RawVideoQualifier] picks it. */
    private fun rawQualificationSensorSize(cameraId: String): Pair<Int, Int>? =
        raw10Sizes(cameraId).filter { (width, height) -> width > 0 && height > 0 && width % 4 == 0 }.maxByOrNull { (width, height) -> width.toLong() * height }

    /** Main: what the qualification prompt and step show; a few small reads (cached characteristics, the store, StatFs). */
    private fun rawQualificationInfo(): RawVideoQualificationInfo {
        val cameraId = rawQualificationCameraId()
            ?: return RawVideoQualificationInfo(null, ModeGateState.UNSUPPORTED, null)
        val usable = rawUsableConfigs(cameraId)
        val gate = rawVideoGate(raw10Sizes(cameraId), usable, cameraId in rawVideoFailedCameras)
        val sensor = rawQualificationSensorSize(cameraId)
        val destination = nextTakeDestination
        val free = destinationFreeBytes(destination.destination)
        val storage = rawQualificationStorageModel(cameraId, destination.destination)
        val space = if (sensor != null && free != null) {
            RawQualificationEstimates.forFreeSpace(
                RawQualificationPlan.DEFAULT, cameraId, sensor.first, sensor.second,
                com.librestatic.opencinecam.media.raw.NativeRawCodec.available, free, storage = storage,
            )
        } else null
        val previousFailure = usable.isEmpty() && runCatching {
            (rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it })
                .current(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion)
                .any { it.config.cameraId == cameraId && it.destinationId == rawDestinationId(destination.destination) }
        }.getOrDefault(false)
        return RawVideoQualificationInfo(cameraId, gate, space, previousFailure,
            rawQualificationTarget(destination, cameraState.value.recordingDestination.driveLabel), storage)
    }

    /**
     * Main: what is known of [destination]'s storage before a qualification of [cameraId] tests it, so the estimate
     * plans the long runs' drain and read-back. The write speed is the sweep median of the destination's newest record on this
     * build, else its rest reference, else a drive's speed measured at adoption; the ring is the newest record's of the camera.
     * Null when no speed is known (the nominal timings).
     */
    private fun rawQualificationStorageModel(cameraId: String, destination: RecordingDestination): RawQualificationStorageModel? {
        val destinationId = rawDestinationId(destination)
        val records = runCatching {
            (rawQualifications ?: rawQualificationStore.load().also { rawQualifications = it })
                .current(rawQualificationStore.fingerprint, rawQualificationStore.protocolVersion)
        }.getOrDefault(emptyList())
        val driveSpeed = (destination as? RecordingDestination.UsbFolder)?.let { drive ->
            runCatching { RecordingDestinationStore(this).speed(drive.volumeId)?.medianBytesPerSecond }.getOrNull()
        }
        // The last sweep on this destination wrote the way the run will; the speed measured at adoption is a shorter, kinder test.
        val speed = records.filter { it.destinationId == destinationId && (it.qualification.sweepMedianBytesPerSecond ?: 0L) > 0 }
            .maxByOrNull { it.measuredAtEpochMs }?.qualification?.sweepMedianBytesPerSecond
            ?: runCatching { rawQualificationStore.restReference(destinationId) }.getOrNull()?.takeIf { it > 0 }
            ?: driveSpeed?.takeIf { it > 0 }
        val ringBytes = records.filter { it.config.cameraId == cameraId && it.ringBytes > 0 }.maxByOrNull { it.measuredAtEpochMs }?.ringBytes
        return RawQualificationStorageModel.of(speed, ringBytes)
    }

    /** Qualification thread: one progress report, posted to main at most about four times a second (and on every new step). */
    private fun postRawQualificationProgress(session: RawQualificationSession, progress: RawQualificationProgress) {
        if (progress.phase == RawQualificationPhase.DONE) return
        val config = progress.spec?.let { spec ->
            RawQualificationSpecs.parse(spec, session.cameraId, session.sensorWidth, session.sensorHeight)?.config
        }
        val label = config?.let { RawVideoConfigOption.of(it).label }
        session.items = rawQualificationItemsOnProgress(session.items, progress, label,
            config?.let { RawQualificationTiming().neededBytes(it) } ?: 0L)
        val ui = rawQualificationUiOf(progress, label).copy(items = session.items)
        val now = android.os.SystemClock.elapsedRealtime()
        if (!rawQualificationShouldPost(session.lastPosted, ui, now - session.lastPostAtMs)) return
        postRawQualificationUi(session, ui, now)
    }

    /** Qualification thread: one run's result row; its configuration takes its status at once. */
    private fun recordRawQualificationResult(session: RawQualificationSession, result: org.json.JSONObject) {
        val kind = result.optString("kind")
        if (kind != "live" && kind != RAW_QUALIFICATION_CAMERA_KIND) return
        val spec = result.optString("spec").takeIf { it.isNotEmpty() } ?: return
        val config = RawQualificationSpecs.parse(spec, session.cameraId, session.sensorWidth, session.sensorHeight)?.config
        if (kind == RAW_QUALIFICATION_CAMERA_KIND) {
            // A camera check's verdict comes at the end; until then its configuration reads as measured.
            session.items = rawQualificationItemsCameraChecked(session.items, spec, config?.let { RawVideoConfigOption.of(it).label } ?: spec,
                config?.let { RawQualificationTiming().neededBytes(it) } ?: 0L, result.optDouble("achievedFps").takeIf { !it.isNaN() })
            val last = session.lastPosted ?: return
            postRawQualificationUi(session, last.copy(items = session.items), android.os.SystemClock.elapsedRealtime())
            return
        }
        val codes = result.optJSONArray("reasonCodes")?.let { array -> (0 until array.length()).map(array::optString) }
        val status = rawQualificationRunStatus(result.optString("status"), result.optString("reasonCode").ifEmpty { null },
            codes ?: listOfNotNull(result.optString("reasonCode").ifEmpty { null }))
        // The skip row carries the figure the qualifier decided on; otherwise the same timing gives it.
        val needed = result.optLong("neededBytes", config?.let { RawQualificationTiming().neededBytes(it) } ?: 0L)
        val burstSeconds = if (result.isNull("burstSeconds")) null else result.optLong("burstSeconds")
        val achievedFps = result.optDouble("achievedFps").takeIf { !it.isNaN() }
        session.items = rawQualificationItemsWith(session.items, spec, status,
            config?.let { RawVideoConfigOption.of(it).label } ?: spec, needed, burstSeconds, achievedFps)
        val last = session.lastPosted ?: return
        postRawQualificationUi(session, last.copy(items = session.items), android.os.SystemClock.elapsedRealtime())
    }

    private fun postRawQualificationUi(session: RawQualificationSession, ui: RawVideoQualificationUi, now: Long) {
        session.lastPosted = ui
        session.lastPostAtMs = now
        mainHandler.post {
            if (rawQualificationSession === session && !serviceDestroyed) cameraState.update { it.copy(rawVideoQualification = ui) }
        }
    }

    /**
     * Main: the run ended. Reloads the records, updates the gate, selects RAW_VIDEO when a configuration
     * passed (or once the cameras are prepared), and reopens the viewfinder when a surface is attached.
     */
    private fun finishRawQualification(
        session: RawQualificationSession,
        outcome: RawVideoQualificationOutcome,
        records: RawVideoQualificationSet,
    ) {
        if (rawQualificationSession !== session) return
        session.batteryReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
        session.batteryReceiver = null
        if (serviceDestroyed) {
            rawQualificationSession = null
            return
        }
        rawQualifications = records
        // Read while the session still holds the lookups on the run's destination: a drive that left during the run
        // must not be judged by the phone's records (PLAN-072).
        val usable = rawUsableConfigs(session.cameraId).let { all -> all - rawRiskyConfigs(session.cameraId, all) }
        val previousKept = rawQualificationStorageBusy(outcome.reasonCode) && rawUsableConfigs(session.cameraId).isNotEmpty()
        rawQualificationSession = null
        val kind = rawQualificationOutcome(outcome.status, usable.size, outcome.reasonCode)
        val reason = when (kind) {
            // The drive's descriptors can be released (cancelling the run) before the volume event reaches main.
            RawQualificationOutcomeKind.CANCELLED -> session.stopReason
                ?: if (session.qualifier.driveLost) RawQualificationStopReasons.DRIVE_LOST else RawQualificationStopReasons.USER
            RawQualificationOutcomeKind.FAILED_TO_RUN -> outcome.reasonCode
            else -> null
        }
        val result = RawVideoQualificationUi(
            phase = RawQualificationPhase.DONE,
            fraction = cameraState.value.rawVideoQualification?.fraction ?: 0f,
            outcome = kind,
            passedLabels = if (kind == RawQualificationOutcomeKind.PASSED) usable.map { RawVideoConfigOption.of(it).label } else emptyList(),
            reason = reason,
            remainingMs = 0L,
            items = rawQualificationItemsFinished(session.items),
            // The run stored nothing: whatever the camera was qualified for before still holds.
            previousKept = previousKept,
        )
        cameraState.value = withRawVideoGate(cameraState.value.copy(rawVideoQualification = result), cameraState.value.selectedCameraId)
        // A requalification may fail every configuration that had passed: RAW video, if selected, gives way.
        val fallback = cameraState.value.let { rawVideoModeFallback(it.selectedMode, it.modeGates, rawModeFallback, hasConfig = it.rawVideoSelectedKey != null) }
        if (fallback != null) {
            localBinder.selectMode(fallback, reopen = false)
        } else if (kind == RawQualificationOutcomeKind.PASSED && session.selectOnPass) {
            val current = cameraState.value
            if (current.cameras.isNotEmpty() && current.selectedCameraId == session.cameraId &&
                CameraUiState.isModeSelectable(current.modeGates[CaptureMode.RAW_VIDEO])) {
                localBinder.selectMode(CaptureMode.RAW_VIDEO, reopen = false)
            } else if (current.cameras.isEmpty()) {
                rawQualificationSelectPending = true
            }
        }
        val message = when (kind) {
            RawQualificationOutcomeKind.PASSED ->
                resources.getQuantityString(R.plurals.raw_qualification_message_ready, usable.size, usable.size)
            RawQualificationOutcomeKind.NO_CONFIG ->
                getString(R.string.raw_qualification_message_none, modeDisplayName(cameraState.value.selectedMode))
            else -> null
        }
        if (message != null) cameraState.update { it.copy(message = message, messageTransient = true) }
        val surface = attachedPreviewSurface?.takeIf { it.isValid }
        if (surface == null || cameraState.value.descriptor == null || !localBinder.attachPreview(surface, attachedPreviewRotationDegrees)) syncRawVideoArm()
    }

    /** One RAW recovery pass; at service start and before each RAW take, never on main. */
    private fun recoverRawTakes(): RawVideoRecoveryReport = try {
        RawVideoRecovery(this).recover()
    } catch (failure: Exception) {
        android.util.Log.e("RawVideoRecovery", "Interrupted RAW takes could not be recovered", failure)
        RawVideoRecoveryReport(unresolved = 1)
    }

    /** Main: the camera refused the RAW graph. RAW_VIDEO becomes FAILED for this camera and the operator returns to the previous mode. */
    private fun rawVideoGraphRejected(reason: String, message: String) {
        val cameraId = cameraState.value.selectedCameraId
        if (reason == RawVideoReasons.GRAPH_REJECTED && cameraId != null) rawVideoFailedCameras += cameraId
        cameraState.value = withRawVideoGate(cameraState.value, cameraId)
        android.util.Log.w("CaptureService", "RAW video unavailable on camera $cameraId ($reason): $message")
        syncRawVideoArm()
        leaveFailedRawVideo()
    }

    /** Leaves RAW_VIDEO for the mode it was entered from (or VIDEO) once its gate is no longer AVAILABLE. */
    private fun leaveFailedRawVideo() {
        val current = cameraState.value
        if (current.selectedMode != CaptureMode.RAW_VIDEO || rawTake != null || current.structuralSettingsFrozen ||
            current.modeGates[CaptureMode.RAW_VIDEO] != ModeGateState.FAILED) return
        val fallback = rawModeFallback?.takeIf { it != CaptureMode.RAW_VIDEO && CameraUiState.isModeSelectable(current.modeGates[it]) }
            ?: CaptureMode.VIDEO
        val text = getString(R.string.raw_video_unavailable, modeDisplayName(fallback))
        pendingStatusMessage = text
        localBinder.selectMode(fallback)
        cameraState.update { it.copy(message = text, errorCode = RawVideoServiceReasons.FAILED) }
    }

    private fun formatBytes(bytes: Long): String = android.text.format.Formatter.formatFileSize(this, bytes)

    private fun rawRefusalText(reason: String, config: RawVideoConfig?, freeBytes: Long?): String = when (reason) {
        RawVideoServiceReasons.UNSUPPORTED -> getString(R.string.raw_video_unsupported)
        RawVideoServiceReasons.FAILED -> getString(R.string.raw_video_failed)
        RawVideoServiceReasons.MEMORY -> getString(R.string.raw_video_memory)
        RawVideoServiceReasons.CODEC -> getString(R.string.raw_video_codec_unavailable)
        RawVideoServiceReasons.STORAGE -> if (config == null || freeBytes == null) getString(R.string.raw_video_storage_unknown)
            else getString(R.string.raw_video_storage, formatBytes(rawTakeMinimumFreeBytes(config)), formatBytes(freeBytes))
        RawVideoServiceReasons.START_FAILED -> getString(R.string.raw_video_start_failed)
        else -> getString(R.string.raw_video_not_qualified)
    }

    private fun rawEndText(reason: String): String = getString(when (rawTakeEndKind(reason)) {
        RawTakeEndKind.RING_FULL -> R.string.raw_video_end_ring_full
        RawTakeEndKind.WRITER -> R.string.raw_video_end_writer
        RawTakeEndKind.STREAM -> R.string.raw_video_end_stream
        RawTakeEndKind.FRAME -> R.string.raw_video_end_frame
        RawTakeEndKind.STALLED -> R.string.raw_video_end_stalled
        RawTakeEndKind.OTHER -> R.string.raw_video_end_other
    })

    /**
     * Main: the record button in RAW_VIDEO. Starts a take (admission, foreground service, then the
     * control thread opens the file, starts the recorder and only then the engine) or stops the
     * running one. Never MediaRecorder, never an encoded fallback. Sound follows the video take's
     * settings and permission check (PLAN-071 §7.1) and goes into the `.ocraw` as PCM16.
     */
    private fun toggleRawTake(audioForThisTake: Boolean? = null): Boolean {
        rawTake?.let { cancelPendingContinuation(); return stopRawTake(it, null) }
        val current = cameraState.value
        if (current.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) || current.recordingFinalizing ||
            transferCapture.get() != null) return false
        // PLAN-072 Delivery 3: the take goes where an encoded take would, gated by that destination's qualification and free
        // space. A destination that changed since the gate was computed (a refresh still on its way) recomputes it first.
        // Delivery 4: a continuation always goes to the phone, gated by the phone's qualification and free space.
        val continuation = continuationStarting?.takeIf { it.mode == CaptureMode.RAW_VIDEO }
        val destination = resolveTakeDestination().let { if (continuation != null) continuationDestination(it) else it }
        destination.fallback?.let { android.util.Log.w("RecordingDestination", "RAW take falls back to the phone: $it") }
        val destinationChanged = destination != nextTakeDestination
        if (destinationChanged) nextTakeDestination = destination
        // A gate computed before the drive take armed its continuation may hold another selection: the same recompute.
        val staleSelection = continuation != null && cameraState.value.rawVideoSelectedKey != continuation.rawConfigKey
        if ((destinationChanged && rawDestinationId(destination.destination) != rawGateDestinationId) || staleSelection) {
            val selectedBefore = cameraState.value.rawVideoSelectedKey
            cameraState.value = withRawVideoGate(cameraState.value, cameraState.value.selectedCameraId)
            if (cameraState.value.rawVideoSelectedKey != selectedBefore) {
                // The live session still streams the configuration selected before, at its size and rate: reopen it for the
                // new one, as refreshRawGate does, and refuse this press; the next one records the new configuration. A
                // continuation is refused too, and ends with its "could not continue" outcome (startDriveContinuation).
                if (!followRawSelection(selectedBefore)) syncRawVideoArm()
                android.util.Log.w("CaptureService", "RAW take refused: the selection changed from $selectedBefore to " +
                    "${cameraState.value.rawVideoSelectedKey}; the session reopens")
                val reason = if (cameraState.value.rawVideoSelectedKey == null) RawVideoServiceReasons.NOT_QUALIFIED
                    else RawVideoServiceReasons.START_FAILED
                cameraState.update { it.copy(message = rawRefusalText(reason, null, null), errorCode = reason, messageTransient = false) }
                return false
            }
            syncRawVideoArm()
        }
        val gated = cameraState.value
        val config = currentRawVideoConfig(gated)
        // The continuation records the drive take's configuration or nothing. The gate keeps that configuration selected
        // while the continuation is pending ([rawVideoGateKey]), so this only fails when the phone cannot use it.
        if (continuation != null && config?.let(RawVideoConfigKeys::key) != continuation.rawConfigKey) {
            android.util.Log.w("RecordingDestination", "RAW continuation refused: the phone selects ${config?.let(RawVideoConfigKeys::key)}, not ${continuation.rawConfigKey}")
            return false
        }
        val free = destinationFreeBytes(destination.destination)
        val admission = admitRawTake(gated.modeGates[CaptureMode.RAW_VIDEO], config, config != null && rawArmNoMemory == config, free)
        if (admission is RawTakeAdmission.Refuse) {
            val reason = if (admission.reason == RawVideoServiceReasons.MEMORY) rawArmFailure else admission.reason
            cameraState.update { it.copy(message = rawRefusalText(reason, config, free), errorCode = reason, messageTransient = false) }
            return false
        }
        val admitted = checkNotNull(config)
        val audioRequested = when (RawTakeAudio.decide(audioForThisTake ?: settings.audioEnabled,
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)) {
            RawTakeAudio.NONE -> false
            RawTakeAudio.RECORD -> true
            RawTakeAudio.REFUSE_PERMISSION -> {
                cameraState.update { it.copy(message = getString(R.string.capture_microphone_permission_required),
                    errorCode = "microphone-permission-required", messageTransient = false) }
                return false
            }
        }
        val audio: RawAudioSource? = if (audioRequested) {
            val takeInput = takeAudioInput(cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings, true) ?: return false
            stopPreviewAudioMonitor(clearLevels = false)
            AudioRecordRawAudioSource(this, takeInput.settings, current.descriptor?.timestampSourceRealtime == true,
                takeInput.guard, newAudioLevelConsumer(), newAudioListeningSink())
        } else null
        when (val start = foreground.start(audioRequested)) {
            is ForegroundRecordingStart.Rejected -> {
                cameraState.update { it.copy(message = start.failure.userMessage, errorCode = "recording-foreground-failed", messageTransient = false) }
                return false
            }
            is ForegroundRecordingStart.Started -> startService(Intent(this, CaptureService::class.java))
        }
        // A continuation keeps the drive take's names and slate (the same take number).
        val captureNames = continuation?.captureNames ?: admittedCaptureNames()
        val session = RawTakeSession(java.util.UUID.randomUUID().toString(), admitted, destination, captureNames, audioRequested, continuation)
        rawTake = session
        holdTakeDestination(destination, continuation)
        pendingStopReason = null
        cameraState.update { it.copy(phase = CameraUiPhase.CAPTURING, message = null, errorCode = null) }
        submitCaptureState(stateCommands.takeDispatched())
        if (!rawControlExecute { startRawTakeOnControl(session, captureNames, audio) }) rawTakeRefused(session, RawVideoServiceReasons.START_FAILED)
        return true
    }

    /** Control thread: recovery pass, output, recorder (and its audio), then the engine (PLAN-071 order). */
    private fun startRawTakeOnControl(session: RawTakeSession, captureNames: CaptureNameSnapshot, audio: RawAudioSource?) {
        fun refuse(reason: String) = mainHandler.post { if (!serviceDestroyed) rawTakeRefused(session, reason) }
        val recovered = recoverRawTakes()
        // Interrupted takes on a drive wait in the take journal for its next mount or the service's next start: scanning
        // a large RAW take here would hold this one in CAPTURING, and reading the drive meanwhile competes with its writes.
        val pass = com.librestatic.opencinecam.storage.RecordingRecoveryReport(rawTakes = recovered)
        if (pass != com.librestatic.opencinecam.storage.RecordingRecoveryReport()) mainHandler.post {
            if (!serviceDestroyed) cameraState.update { state ->
                state.copy(recordingRecovery = mergeRecordingRecovery(state.recordingRecovery, pass)
                    .copy(deferredGroups = state.recordingRecovery?.deferredGroups ?: 0))
            }
        }
        if (serviceDestroyed) { refuse(RawVideoServiceReasons.START_FAILED); return }
        if (rawRecorder.armState?.config != session.config) {
            refuse(if (rawArmNoMemory == session.config) rawArmFailure else RawVideoServiceReasons.START_FAILED)
            return
        }
        var output: com.librestatic.opencinecam.rawvideo.RawVideoTakeOutput? = null
        try {
            output = when (val destination = session.destination.destination) {
                RecordingDestination.Phone -> RawVideoOutput.create(this, captureNames, session.takeId, stem = session.continuation?.stem)
                is RecordingDestination.UsbFolder -> com.librestatic.opencinecam.storage.SafRawVideoOutput.create(this, destination, captureNames, session.takeId)
            }
            val appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
            // PLAN-072 Delivery 4: a continuation's `.ocraw` names the drive take it continues in static metadata keys.
            val link = session.continuation?.link?.let { mapOf("continuationOf" to it.of, "continuationPreviousName" to it.previousName,
                "continuationIndex" to it.index.toString(), "continuationReason" to it.reason) }.orEmpty()
            rawRecorder.start(output, RawTakeInfo(session.takeId, Build.MODEL, appVersion, mapOf("fingerprint" to Build.FINGERPRINT) + link), audio) { reason ->
                // The recorder cannot keep up or failed: end the take; its reason comes back from stop().
                android.util.Log.w("CaptureService", "RAW take must end: $reason")
                // A write that failed on a drive (ENODEV can come before any volume event) is the drive going: Stop and save.
                val rawDrive = session.destination.destination as? RecordingDestination.UsbFolder
                if (reason == com.librestatic.opencinecam.rawvideo.RawTakeReasons.WRITER_ERROR && rawDrive != null) {
                    // On the writer's thread, before the main hop: the drive is failing, so close every descriptor on it (ADR-0037).
                    releaseDriveDescriptors(rawDrive.volumeId, "a RAW write failed")
                    mainHandler.post { if (rawTake === session && pendingStopReason == null) pendingStopReason = driveLostFailure(writeFailed = true) }
                }
                previewEngine.stopRawVideo()
            }
        } catch (failure: Exception) {
            android.util.Log.e("CaptureService", "RAW take could not start", failure)
            if (!rawRecorder.isRecording) runCatching { output?.discard() }
            refuse(RawVideoServiceReasons.START_FAILED)
            return
        }
        if (!previewEngine.startRawVideo(rawRecorder)) {
            // Nothing reached the file: stop() discards the empty output.
            runCatching { rawRecorder.stop() }.onFailure { android.util.Log.e("CaptureService", "Refused RAW take did not close", it) }
            refuse(RawVideoServiceReasons.START_FAILED)
            return
        }
        mainHandler.post { if (!serviceDestroyed) rawTakeStarted(session) }
    }

    /** Main: the engine accepted the take; the REC state, timer and live policy are the video take's. */
    private fun rawTakeStarted(session: RawTakeSession) {
        if (rawTake !== session) return
        session.started = true
        driveContinuation.takeStarted()
        if (cameraState.value.phase != CameraUiPhase.CAPTURING) {
            // The camera failed or reopened meanwhile; the engine's stop callback finishes the take.
            stopRawTake(session, null)
            return
        }
        submitCaptureState(stateCommands.takeStarted())
        val config = session.config
        recordingStartedAtMs = android.os.SystemClock.elapsedRealtime()
        activeRecordingPolicy = settings.recordingPolicy
        // The live storage policy stops a take when less than its projected figure is left. A RAW
        // take is admitted with RAW_TAKE_MIN_SECONDS of room, so that is its horizon, not a minute.
        takeProjectedBytesPerMinute = rawTakeMinimumFreeBytes(config)
        admissionWarning = null
        takeSoftwareEncoder = null
        takeCodecFallback = false
        runtimeNotice = null
        avNotice = null
        cadenceNotice = null
        avDiagnostics = null
        val free = recordingVolumeFreeBytes()
        lastKnownFreeBytes = free
        val option = RawVideoConfigOption.of(config)
        mainHandler.removeCallbacks(recordingTicker)
        cameraState.update { it.copy(
            phase = CameraUiPhase.RECORDING,
            message = getString(R.string.raw_video_recording, rawConfigLabelLocalized(option.label,
                getString(if (rawArmedRing?.nearLossless == true) R.string.raw_video_label_near_lossless else R.string.raw_video_label_compressed)),
                kotlin.math.round(option.megabytesPerSecond).toLong()) +
                " · " + rawTakeAudioLabel(),
            recordingElapsedMs = 0L,
            recordingPauseStatus = null,
            recordingAvTiming = null,
            recordingPausePending = false,
            recordingWidth = config.cropWidth,
            recordingHeight = config.cropHeight,
            availableStorageBytes = free,
            recordingWarning = null,
            errorCode = null,
            rawVideoRingPercent = 0,
            rawVideoFramesWritten = 0L,
        ) }
        mainHandler.post(recordingTicker)
        if (session.stopRequested) stopRawTake(session, null)
    }

    /** The REC line's audio part, as a video take shows it: the PCM format, or "no audio". */
    private fun rawTakeAudioLabel(): String {
        val sampleRate = rawRecorder.liveAudioSampleRate()
        val channels = rawRecorder.liveAudioChannels()
        return if (sampleRate == null || channels == null) getString(R.string.capture_no_audio)
            else "PCM 16-bit · ${kiloHertzText(sampleRate, messageLocale)} kHz · $channels ch"
    }

    /** The saved notice's audio part: the PCM format, and a note when the sound did not start or has gaps. */
    private fun rawSavedAudioSuffix(result: RawTakeResult): String {
        val format = result.audioSampleRate?.let { savedAudioSuffix(SavedSidecarAudio(16, "PCM", it), "", messageLocale) }.orEmpty()
        val note = when {
            result.audioError != null && result.audioSampleRate == null -> getString(R.string.capture_lossless_audio_start_failed)
            result.audioSampleRate != null && (result.audioDroppedFrames > 0 || result.audioError != null) ->
                getString(R.string.raw_video_audio_incomplete)
            else -> null
        }
        return format + note?.let { " · $it" }.orEmpty()
    }

    /** Main: stops [session]; before the engine accepted it the stop is remembered and applied then. */
    private fun stopRawTake(session: RawTakeSession, reason: StableFailure?): Boolean {
        if (reason != null && pendingStopReason == null) {
            android.util.Log.w("CaptureService", "Stopping RAW take: ${reason.code} (${reason.component}) ${reason.userMessage}")
            pendingStopReason = reason
        }
        if (!session.started) {
            session.stopRequested = true
            return true
        }
        if (cameraState.value.recordingFinalizing) return false
        cameraState.value = cameraState.value.copy(recordingFinalizing = true)
        previewEngine.stopRawVideo()
        submitCaptureState(stateCommands.takeStopRequested())
        return true
    }

    /** RAW thread: the engine ended the take. The recorder drains and closes on the control thread. */
    private fun rawVideoTakeStopped(engineReason: String?) {
        val finish = Runnable {
            val result = if (rawRecorder.isRecording) {
                try { rawRecorder.stop() } catch (failure: Exception) {
                    android.util.Log.e("CaptureService", "RAW take did not finish", failure)
                    null
                }
            } else null
            mainHandler.post { if (!serviceDestroyed) finishRawTake(result, engineReason) }
        }
        if (!rawControlExecute(finish)) finish.run()
    }

    /** Main: publishes the end of the take, with its reason when it ended early. */
    private fun finishRawTake(result: RawTakeResult?, engineReason: String?) {
        val session = rawTake
        if (session == null) {
            android.util.Log.w("CaptureService", "RAW take ended without a session: ${result?.reason ?: engineReason}")
            return
        }
        rawTake = null
        mainHandler.removeCallbacks(recordingTicker)
        val policy = pendingStopReason
        pendingStopReason = null
        submitCaptureState(stateCommands.takeEnded())
        val reason = rawTakeEndReason(result?.reason, engineReason, null)
        val onDrive = session.destination.destination is RecordingDestination.UsbFolder
        // PLAN-072 Delivery 4: the volume event and a writer's ENODEV both end here with the drive's reason, once per take.
        val continuationNote = decideDriveContinuation(policy, onDrive, CaptureMode.RAW_VIDEO, highSpeed = false,
            rawQualifiedOnPhone = rawQualifiedOnPhone(session.config),
            phoneRoom = phoneVolumeFreeBytes()?.let { it >= rawTakeMinimumFreeBytes(session.config) } == true) {
            val parentName = result?.displayName ?: (captureFileStem(session.takeId, session.captureNames) + RawVideoOutput.EXTENSION)
            ContinuationRequest(CaptureMode.RAW_VIDEO, continuationFileStem(parentName.removeSuffix(RawVideoOutput.EXTENSION), 1),
                ClipContinuation(session.takeId, parentName, 1, requireNotNull(policy).correlationId), session.captureNames, session.audio,
                rawConfigKey = RawVideoConfigKeys.key(session.config))
        }
        val outcome = when {
            result?.uri != null -> getString(R.string.raw_video_saved, result.framesWritten,
                String.format(java.util.Locale.getDefault(), "%.1f", result.mediaDurationNs / 1e9)) + rawSavedAudioSuffix(result)
            // A drive take left unfinished stays journalled on the drive and is repaired when the drive is mounted again.
            result == null || result.framesWritten > 0 || result.outputError != null ->
                getString(if (onDrive) R.string.raw_video_not_finalised_drive else R.string.raw_video_not_finalised)
            else -> getString(R.string.raw_video_empty)
        }
        val message = when {
            // The drive going is the root cause of a writer failure on it, so its reason wins.
            policy?.component == RECORDING_DRIVE_COMPONENT -> getString(R.string.take_stopped_reason, stopReasonText(policy), outcome)
            reason != null -> getString(R.string.take_stopped_reason, rawEndText(reason), outcome)
            policy != null -> getString(R.string.take_stopped_reason, stopReasonText(policy), outcome)
            else -> outcome
        }.let { text -> continuationNote?.let { "$text · $it" } ?: text }
        android.util.Log.i("CaptureService", "RAW take ${session.takeId} ended: reason=$reason policy=${policy?.code} " +
            "frames=${result?.framesWritten} captured=${result?.framesCaptured} peakRing=${result?.peakRingFraction} error=${result?.outputError} " +
            "audio=${result?.audioSampleRate} audioFrames=${result?.audioFramesWritten} audioDropped=${result?.audioDroppedFrames} audioError=${result?.audioError}")
        retireAudioLevelConsumer(clearLevels = true)
        runtimeNotice = null
        avNotice = null
        cadenceNotice = null
        cameraState.update { it.copy(
            phase = if (it.phase in setOf(CameraUiPhase.RECORDING, CameraUiPhase.CAPTURING)) {
                if (result?.uri != null) CameraUiPhase.SAVED else CameraUiPhase.PREVIEWING
            } else it.phase,
            recordingFinalizing = false,
            lastSavedUri = result?.uri?.toString() ?: it.lastSavedUri,
            message = message,
            messageTransient = false,
            errorCode = reason,
            recordingWarning = null,
            rawVideoRingPercent = null,
            rawVideoFramesWritten = null,
        ) }
        clearTakeDestination()
        // As for an encoded take, a pending continuation keeps the foreground service through the gap.
        if (!driveContinuation.isPending) foreground.stop()
        if (session.continuation != null) refreshRawGate()
        syncRawVideoArm()
        leaveFailedRawVideo()
        mainHandler.post { if (!serviceDestroyed) localBinder.applySettings(SettingsRepositories.get(this).states.value) }
        // After the foreground stop above and the settings: the continuation's own start must come last.
        if (driveContinuation.isPending) mainHandler.post { startDriveContinuation() }
        if (rawTakePreviewStale) {
            // A screen lock mid-take destroyed the SurfaceView; the session kept rendering to it and
            // nothing could swap it. The camera is free again, so reopen the viewfinder on the live surface.
            rawTakePreviewStale = false
            mainHandler.post {
                if (!serviceDestroyed) attachedPreviewSurface?.takeIf { it.isValid }?.let { localBinder.attachPreview(it, attachedPreviewRotationDegrees) }
            }
        }
    }

    /** Main: the take never started; back to previewing with the reason. */
    private fun rawTakeRefused(session: RawTakeSession, reason: String) {
        if (rawTake !== session) return
        rawTake = null
        pendingStopReason = null
        retireAudioLevelConsumer(clearLevels = true)
        foreground.stop()
        submitCaptureState(stateCommands.takeEnded())
        val text = rawRefusalText(reason, session.config, cachedDestinationFreeBytes(session.destination.destination))
        if (cameraState.value.phase == CameraUiPhase.CAPTURING) refuseTakeStart(reason, text)
        else cameraState.update { it.copy(message = text, errorCode = reason) }
        clearTakeDestination()
        if (session.continuation != null) refreshRawGate()
        syncRawVideoArm()
        leaveFailedRawVideo()
    }

    /** Main, from the recording ticker: the ring fill and the frames on storage of the running take. */
    private fun publishRawVideoStats() {
        if (rawTake?.started != true) return
        val stats = rawRecorder.liveStats()
        if (!stats.recording) return
        val percent = (stats.ringUsedFraction * 100).toInt().coerceIn(0, 100)
        cameraState.update { it.copy(rawVideoRingPercent = percent, rawVideoFramesWritten = stats.framesWritten) }
    }

    private fun updateSubjectTarget() {
        // Invalidate receipts queued on main even when the same camera/lease survives a mode round trip.
        val epoch = subjectPreviewEpoch.incrementAndGet()
        if (subjectPortClosed) return
        val lease = subjectSurfaces.current ?: return
        val block = subjectPreviewBlock(cameraState.value.selectedMode)
        if (block != null || !settings.subjectDisplay.wantsCameraPreview) {
            previewEngine.detachSubjectPreview(lease.token)
            subjectStatus.value = SubjectPreviewStatus(failure = getString(R.string.subject_preview_mode_unavailable))
            resetSubjectLutStatus()
            return
        }
        val preferences = settings.subjectDisplay
        previewEngine.attachSubjectPreview(lease.token, lease.surface,
            SubjectPreviewOptions(lease.rotationDegrees, preferences.previewMirror, preferences.previewViewAssist, settings.anamorphicSqueeze.factor, lease.frameRate)) { status ->
            mainHandler.post {
                fun ownsTarget(state: CameraUiState): Boolean = !serviceDestroyed && !subjectPortClosed &&
                    subjectSurfaces.owns(lease.token) && subjectPreviewEpoch.get() == epoch &&
                    subjectPreviewBlock(state.selectedMode) == null && settings.subjectDisplay.wantsCameraPreview
                if (ownsTarget(cameraState.value)) {
                    val presented = status.lutStatus?.takeIf { it.selectionId == monitorLutIdentity(currentSubjectLut) }
                    subjectStatus.value = status.copy(lutStatus = presented)
                    cameraState.update { state ->
                        // onOpening can race this CAS from cameraExecutor. Recheck on every retry.
                        if (!ownsTarget(state)) return@update state
                        val matching = presented?.takeIf { it.selectionId == monitorLutIdentity(currentSubjectLut) }
                        state.copy(subjectLutStatus = if (status.failure == null) matching ?: waitingSubjectLutStatus()
                            else currentSubjectLut?.let { selected -> OperatorLutStatus(selected.cube.sha256,
                                OperatorLutState.FAILED, status.failure, monitorLutIdentity(selected)) } ?: OperatorLutStatus())
                    }
                }
            }
        }
    }

    private val captureCountdown = CaptureCountdown<List<Any?>>()
    private var countdownTick: Runnable? = null
    private var selfRecordingRole = false

    private fun countdownContext(): List<Any?> = cameraState.value.let {
        listOf(it.selectedCameraId, it.selectedMode, it.targetVideoWidth, it.targetVideoHeight, it.targetFps)
    }

    private fun cancelCountdown() {
        captureCountdown.cancel()
        countdownTick?.let(mainHandler::removeCallbacks)
        countdownTick = null
        cameraState.value = cameraState.value.copy(countdownSeconds = 0, captureActionGeneration = cameraState.value.captureActionGeneration + 1)
    }

    private fun applyProfessionalSettings() {
        val current = cameraState.value
        val descriptor = current.descriptor
        val hfr = current.activeVideoProfile?.constrainedHighSpeed == true || current.activeLogProfile?.constrainedHighSpeed == true
        val resolved = settings.exposure.resolve(if (hfr) ExposureCapabilities() else descriptor?.exposureCapabilities ?: ExposureCapabilities(), CaptureFrameRate(current.targetFps))
        val wb = settings.whiteBalance.adaptTo(descriptor?.kelvinRange.takeUnless { hfr }, descriptor?.tintSupported == true,
            if (hfr) emptySet() else descriptor?.availableAwbModes)
        cameraState.update { it.copy(
            requestedIso = resolved.iso,
            requestedExposureTimeNs = resolved.timeNs,
            requestedExposureMode = settings.exposure.mode,
            exposureControlUnavailable = resolved.unavailable,
            exposureClamped = resolved.clamped,
            requestedWhiteBalance = wb,
        ) }
        previewEngine.setControlPolicy(settings.recordingPolicy)
        previewEngine.setProfessionalControls(settings.exposure, settings.whiteBalance, settings.imageProcessing)
    }

    private val previewListener = object : Camera2PreviewListener {
        override fun onProfessionalControlsRejected(message: String) {
            mainHandler.post { cameraState.value = cameraState.value.copy(message = getString(com.librestatic.opencinecam.R.string.pro_controls_rejected, message)) }
        }
        override fun onPreviewSurfaceLost(message: String) {
            calibrationCameraLost()
            mainHandler.post {
                cancelCountdown()
                cameraState.value = cameraState.value.copy(message = getString(R.string.operator_preview_lost))
            }
        }
        override fun onOpening(descriptor: Camera2CameraDescriptor) {
            openingCount.incrementAndGet()
            if (calibrationRun?.sessionLocked == true) calibrationCameraLost()
            lensLockFallback = false
            // An unlocked start: a night session never reports a zoom range, so the logical one returns now.
            if (currentLensLockBinding(descriptor.cameraId) == null) clearLensLockRoute()
            refreshLensLockState(descriptor.cameraId)
            refreshLensCorrection(descriptor.cameraId)
            refreshSavedCalibration(descriptor)
            subjectPreviewEpoch.incrementAndGet()
            mainHandler.post { cancelCountdown(); subjectStatus.value = SubjectPreviewStatus() }
            activePreviewKey = null
            mainHandler.post { updateSubjectTarget() }
            stopPreviewAudioMonitor(clearLevels = true)
            cameraState.update { it.copy(
                phase = CameraUiPhase.OPENING,
                hardwareTruth = HardwareTruthHudState(),
                gpuViewfinder = desiredGpuViewfinder(),
                nightExtensionRunning = false,
                nightProgressPercent = null,
                torchEnabled = false,
                torchReported = null,
                photoFlashReport = null,
                torchStrengthReported = null,
                selectedCameraId = descriptor.cameraId,
                previewGraphLoss = com.librestatic.opencinecam.camera.PreviewGraphLoss.NONE,
                histogram = emptyList(),
                redHistogram = emptyList(),
                greenHistogram = emptyList(),
                blueHistogram = emptyList(),
                histogramSource = com.librestatic.opencinecam.camera.HistogramSource.FRAME,
                analysisUpdatedAtMs = 0L,
                monitoringScopes = null,
                focusPeakingMask = null,
                operatorLutStatus = currentOperatorLut?.let { OperatorLutStatus(it.cube.sha256, OperatorLutState.WAITING_FOR_GPU, selectionId = monitorLutIdentity(it)) } ?: OperatorLutStatus(),
                subjectLutStatus = waitingSubjectLutStatus(),
                analysisIntervalMs = 0L,
                tapFocusState = TapFocusState.IDLE,
                focusPullActive = false,
                focusPullTargetDiopters = null,
                focusMarks = emptyMap(),
                errorCode = null,
                message = null,
            ) }
        }

        override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) {
            CaptureStartup.previewStarted()
            activePreviewKey = previewKey()
            // A preview (re)started outside CAPTURING means a never-started take already failed
            // (fail() left CAPTURING) and the engine drops its stale-generation start silently.
            // Re-observe retirement so a receipt that failed while preparation was retiring
            // cannot keep the admission after the operator changed settings instead of retrying.
            dispatchedTake.pendingStart()?.takeIf { cameraState.value.phase != CameraUiPhase.CAPTURING }?.let { dispatch ->
                releaseFailedTakeStart(previewEngine.recordingOutputRetirement(), dispatch)
            }
            mainHandler.post { updateSubjectTarget() }
            if (pendingSwitchTarget == descriptor.cameraId) {
                pendingSwitchTarget = null
                pendingSwitchPrevious = null
                cameraSwitchGeneration++
            }
            submitCaptureState(stateCommands.previewStarted())
            val pendingMessage = pendingStatusMessage.also { pendingStatusMessage = null }
            val gpuPath = previewEngine.usesGpuViewfinder()
            cameraState.update { it.copy(
                phase = CameraUiPhase.PREVIEWING,
                gpuViewfinder = gpuPath,
                nightExtensionRunning = previewEngine.nightExtensionActive(),
                selectedCameraId = descriptor.cameraId,
                aeLockSupported = descriptor.aeLockSupported,
                afLockSupported = descriptor.afLockSupported,
                focusMarks = previewEngine.getFocusMarks(),
                errorCode = null,
                message = pendingMessage,
            ) }
            startPreviewAudioMonitorIfEligible()
            // Restore the remembered zoom for this camera after the session starts.
            val remembered = perCameraZoom[descriptor.cameraId]
            if (remembered != null && remembered != 1f && descriptor.zoomSupported) {
                previewEngine.setZoomRatio(remembered)
            }
            mainHandler.post {
                applyProfessionalSettings()
                pendingPresetFocus?.let { preset ->
                    pendingPresetFocus = null
                    val limit = cameraState.value.descriptor?.minimumFocusDistance
                    localBinder.setManualFocus(preset.focusDiopters?.let { value -> limit?.takeIf { it > 0 }?.let { value.coerceIn(0f, it) } })
                    localBinder.setZoomRatio(preset.zoomRatio)
                }
            }
        }

        override fun onRecordingFirstFrame(sensorTimestampNs: Long) {
            synchronized(takeOwnershipLock) { videoOutput?.motionRecorder() }?.onFirstFrame(sensorTimestampNs)
        }

        override fun onMetadata(metadata: Camera2PreviewMetadata) {
            metadata.awbGains?.let { noteLiveIlluminant(it) }
            metadata.rollingShutterSkewNs?.let { skew ->
                latestRollingShutterSkewNs = skew
                synchronized(takeOwnershipLock) { videoOutput?.motionRecorder() }?.updateRollingShutterSkew(skew)
            }
            // Judge the scene only from regular results: the extension session reports none, so the
            // verdict (and the quick toggle with it) stays put while Night is engaged.
            val nightSuggestedNow = if (cameraState.value.selectedMode == CaptureMode.PHOTO && !nightExtensionActive() &&
                cameraState.value.descriptor?.nightExtension != null) {
                nightSceneDetector.update(System.nanoTime(), metadata.sensitivityIso, metadata.exposureTimeNs)
            } else nightSceneDetector.dark
            cameraState.update { it.copy(
                nightSuggested = nightSuggestedNow,
                sensitivityIso = metadata.sensitivityIso,
                exposureTimeNs = metadata.exposureTimeNs,
                focusDistanceDiopters = metadata.focusDistanceDiopters,
                afState = metadata.afState,
                reportedAfMode = metadata.afMode,
                submittedAfMode = metadata.submittedAfMode,
                awbState = metadata.awbState,
                reportedAwbLocked = metadata.awbLocked,
                reportedColorTemperatureK = metadata.colorTemperatureK,
                reportedColorTint = metadata.colorTint,
                reportedOpticalStabilization = metadata.opticalStabilization,
                reportedVideoStabilization = metadata.videoStabilization,
                reportedNoiseReduction = metadata.noiseReduction,
                reportedEdgeEnhancement = metadata.edgeEnhancement,
                reportedToneMapMode = metadata.toneMapMode,
                submittedToneMapMode = metadata.submittedToneMapMode,
                reportedCropRegion = metadata.cropRegion,
                submittedImageProcessing = metadata.submittedImageProcessing,
                pictureControlReadback = metadata.pictureControls,
                reportedExposureMode = metadata.exposureMode,
                reportedAntibanding = metadata.antibanding,
                reportedLowLightBoost = metadata.lowLightBoostActive,
                reportedMeteringMode = metadata.meteringMode,
                torchEnabled = metadata.torchEnabled == true,
                torchReported = metadata.torchEnabled,
                torchStrengthReported = metadata.torchStrengthLevel,
                effectiveFps = metadata.effectiveFps,
            ).let { next -> next.copy(hardwareTruth = next.hardwareTruth.withReadback(readbackSamples(next))) } }
        }

        override fun onPhotoFlashResult(report: com.librestatic.opencinecam.camera.PhotoFlashReport) {
            mainHandler.post { cameraState.value = cameraState.value.copy(photoFlashReport = report) }
        }

        override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) {
            val captureNames = stillOwner.get()?.captureNames ?: settings.let {
                CaptureNameSnapshot(it.captureNaming, it.productionSlate, System.currentTimeMillis())
            }
            val productionSlate = captureNames.slate
            try {
                storageExecutor.execute {
                    try {
                        val uri = stillSaver.saveJpeg(bytes, productionSlate, captureNames, stillDestination())
                        incrementSavedSlate(productionSlate)
                        mainHandler.post {
                            if (serviceDestroyed) return@post
                            cameraState.update { it.copy(phase = CameraUiPhase.SAVED, lastSavedUri = uri.toString(),
                                message = getString(R.string.capture_jpeg_saved, width, height), errorCode = null) }
                            localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
                        }
                    } catch (failure: Throwable) {
                        fail("jpeg-save-failed", failureText(R.string.capture_jpeg_save_failed, failure))
                    }
                }
            } catch (_: RejectedExecutionException) {
                fail("jpeg-save-busy", getString(R.string.capture_still_writer_busy))
            }
        }

        override fun onStillCaptured(capture: CapturedStill) {
            val owner = stillOwner.get() ?: return
            if (serviceDestroyed || !owner.publishing.compareAndSet(false, true)) return
            try {
                storageExecutor.execute {
                    try {
                        val publication = stillSaver.saveCapture(capture, owner.productionSlate, owner.captureNames, stillDestination())
                        incrementSavedSlate(owner.productionSlate)
                        val preview = publication.images.firstOrNull { it.kind != StillImageKind.DNG } ?: publication.images.first()
                        mainHandler.post {
                            if (serviceDestroyed || !stillOwner.compareAndSet(owner, null)) return@post
                            cameraState.update { it.copy(phase = CameraUiPhase.SAVED, stillCapturePending = false, nightProgressPercent = null,
                                lastSavedUri = preview.uri, lastStillPublication = publication,
                                photoFlashReport = capture.flashReport, errorCode = null,
                                message = getString(R.string.photo_capture_saved, publication.images.joinToString(" + ") { image -> image.kind.name })) }
                            localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
                            if (activePreviewKey == null && cameraState.value.phase != CameraUiPhase.OPENING) attachedPreviewSurface?.takeIf { it.isValid }?.let {
                                localBinder.attachPreview(it, attachedPreviewRotationDegrees)
                            }
                        }
                    } catch (failure: Throwable) {
                        mainHandler.post { finishStillFailure(owner, "still-save-failed", failureText(R.string.capture_still_save_failed, failure)) }
                    }
                }
            } catch (_: RejectedExecutionException) {
                mainHandler.post { finishStillFailure(owner, "still-save-busy", getString(R.string.capture_still_writer_busy)) }
            }
        }

        override fun onNightCaptureProgress(percent: Int) {
            mainHandler.post {
                if (serviceDestroyed || stillOwner.get() == null) return@post
                cameraState.update { it.copy(nightProgressPercent = percent) }
            }
        }

        override fun onBurstProgress(completed: Int, total: Int) {
            val owner = stillOwner.get() ?: return
            mainHandler.post {
                if (serviceDestroyed || stillOwner.get() !== owner) return@post
                cameraState.update { it.copy(burstCaptured = completed, burstExpected = total,
                    message = getString(R.string.burst_progress, completed, total)) }
            }
        }

        override fun onBurstCaptured(capture: CapturedBurst) {
            val owner = stillOwner.get() ?: return
            if (serviceDestroyed || !owner.publishing.compareAndSet(false, true)) return
            mainHandler.post {
                if (!serviceDestroyed && stillOwner.get() === owner) cameraState.update {
                    it.copy(burstSaving = true, message = getString(R.string.burst_capture_saving))
                }
            }
            try {
                storageExecutor.execute {
                    try {
                        val publication = stillSaver.saveBurst(capture, owner.productionSlate, owner.captureNames, stillDestination())
                        incrementSavedSlate(owner.productionSlate)
                        mainHandler.post {
                            if (serviceDestroyed || !stillOwner.compareAndSet(owner, null)) return@post
                            cameraState.update { it.copy(phase = CameraUiPhase.SAVED, stillCapturePending = false,
                                burstSaving = false, burstCaptured = publication.images.size,
                                lastSavedUri = publication.images.first().uri,
                                lastBurstPublication = publication, photoFlashReport = null, errorCode = null,
                                message = getString(R.string.burst_saved, publication.images.size)) }
                            localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
                            if (activePreviewKey == null && cameraState.value.phase != CameraUiPhase.OPENING) attachedPreviewSurface?.takeIf { it.isValid }?.let {
                                localBinder.attachPreview(it, attachedPreviewRotationDegrees)
                            }
                        }
                    } catch (failure: Throwable) {
                        mainHandler.post { finishStillFailure(owner, "burst-save-failed", failureText(R.string.capture_burst_save_failed, failure)) }
                    }
                }
            } catch (_: RejectedExecutionException) {
                mainHandler.post { finishStillFailure(owner, "burst-save-busy", getString(R.string.capture_still_writer_busy)) }
            }
        }

        override fun onBracketProgress(completed: Int, total: Int) {
            val owner = stillOwner.get() ?: return
            mainHandler.post {
                if (serviceDestroyed || stillOwner.get() !== owner) return@post
                cameraState.update { it.copy(bracketCaptured = completed, bracketExpected = total,
                    message = getString(R.string.bracket_capture_progress, completed, total)) }
            }
        }

        override fun onBracketCaptured(capture: CapturedBracket) {
            val owner = stillOwner.get() ?: return
            if (serviceDestroyed || !owner.publishing.compareAndSet(false, true)) return
            mainHandler.post {
                if (!serviceDestroyed && stillOwner.get() === owner) cameraState.update {
                    it.copy(bracketSaving = true, message = getString(R.string.bracket_capture_saving))
                }
            }
            try {
                storageExecutor.execute {
                    try {
                        val publication = stillSaver.saveBracket(capture, owner.productionSlate, owner.captureNames, stillDestination())
                        incrementSavedSlate(owner.productionSlate)
                        mainHandler.post {
                            if (serviceDestroyed || !stillOwner.compareAndSet(owner, null)) return@post
                            cameraState.update { it.copy(phase = CameraUiPhase.SAVED, stillCapturePending = false,
                                bracketSaving = false, bracketCaptured = publication.images.size,
                                lastSavedUri = publication.images[publication.images.size / 2].uri,
                                lastBracketPublication = publication, photoFlashReport = null, errorCode = null,
                                message = getString(R.string.bracket_capture_saved, publication.images.size)) }
                            localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
                            if (activePreviewKey == null && cameraState.value.phase != CameraUiPhase.OPENING) attachedPreviewSurface?.takeIf { it.isValid }?.let {
                                localBinder.attachPreview(it, attachedPreviewRotationDegrees)
                            }
                        }
                    } catch (failure: Throwable) {
                        mainHandler.post { finishStillFailure(owner, "bracket-save-failed", failureText(R.string.capture_bracket_save_failed, failure)) }
                    }
                }
            } catch (_: RejectedExecutionException) {
                mainHandler.post { finishStillFailure(owner, "bracket-save-busy", getString(R.string.capture_still_writer_busy)) }
            }
        }

        override fun onAccumulationProgress(completed: Int, elapsedMs: Long, targetMs: Long) {
            val owner = stillOwner.get() ?: return
            mainHandler.post {
                if (serviceDestroyed || stillOwner.get() !== owner) return@post
                cameraState.update { it.copy(accumulationFrames = completed, accumulationElapsedMs = elapsedMs, accumulationTargetMs = targetMs,
                    message = getString(R.string.accumulation_capture_progress, completed, elapsedMs / 1000, targetMs / 1000)) }
            }
        }

        override fun onAccumulationCaptured(capture: CapturedAccumulation) {
            val owner = stillOwner.get() ?: return
            if (serviceDestroyed || !owner.publishing.compareAndSet(false, true)) return
            mainHandler.post {
                if (!serviceDestroyed && stillOwner.get() === owner) cameraState.update {
                    it.copy(accumulationSaving = true, message = getString(R.string.accumulation_capture_saving))
                }
            }
            try {
                storageExecutor.execute {
                    try {
                        val publication = stillSaver.saveAccumulation(capture, owner.productionSlate, owner.captureNames, stillDestination())
                        incrementSavedSlate(owner.productionSlate)
                        mainHandler.post {
                            if (serviceDestroyed || !stillOwner.compareAndSet(owner, null)) return@post
                            cameraState.update { it.copy(phase = CameraUiPhase.SAVED, stillCapturePending = false,
                                accumulationSaving = false, accumulationFrames = capture.frames.size,
                                lastSavedUri = publication.image.uri,
                                lastAccumulationPublication = publication, photoFlashReport = null, errorCode = null,
                                message = getString(R.string.accumulation_capture_saved, capture.frames.size)) }
                            localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
                            if (activePreviewKey == null && cameraState.value.phase != CameraUiPhase.OPENING) attachedPreviewSurface?.takeIf { it.isValid }?.let {
                                localBinder.attachPreview(it, attachedPreviewRotationDegrees)
                            }
                        }
                    } catch (failure: Throwable) {
                        mainHandler.post { finishStillFailure(owner, "accumulation-save-failed", failureText(R.string.capture_accumulation_save_failed, failure)) }
                    }
                }
            } catch (_: RejectedExecutionException) {
                mainHandler.post { finishStillFailure(owner, "accumulation-save-busy", getString(R.string.capture_still_writer_busy)) }
            }
        }

        override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) {
            val captureNames = stillOwner.get()?.captureNames ?: settings.let {
                CaptureNameSnapshot(it.captureNaming, it.productionSlate, System.currentTimeMillis())
            }
            val productionSlate = captureNames.slate
            try {
                storageExecutor.execute {
                    try {
                        val uri = stillSaver.saveDng(bytes, productionSlate, captureNames, stillDestination())
                        incrementSavedSlate(productionSlate)
                        mainHandler.post {
                            if (serviceDestroyed) return@post
                            cameraState.update { it.copy(phase = CameraUiPhase.SAVED, lastSavedUri = uri.toString(),
                                message = getString(R.string.capture_dng_saved, width, height), errorCode = null) }
                            localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
                        }
                    } catch (failure: Throwable) {
                        fail("dng-save-failed", failureText(R.string.capture_dng_save_failed, failure))
                    }
                }
            } catch (_: RejectedExecutionException) {
                fail("dng-save-busy", getString(R.string.capture_still_writer_busy))
            }
        }

        override fun onOperatorLutStatus(status: OperatorLutStatus) {
            // Engine delivery is already fenced and serialized with onOpening on cameraExecutor.
            cameraState.update {
                if (!serviceDestroyed && status.selectionId == monitorLutIdentity(currentOperatorLut))
                    it.copy(operatorLutStatus = status) else it
            }
        }

        override fun onAnalysis(analysis: Camera2Analysis) {
            if (serviceDestroyed) return
            cameraState.update { it.copy(
                histogram = analysis.histogram,
                redHistogram = analysis.redHistogram,
                greenHistogram = analysis.greenHistogram,
                blueHistogram = analysis.blueHistogram,
                histogramSource = analysis.histogramSource,
                analysisIntervalMs = if (it.analysisUpdatedAtMs > 0) (analysis.capturedAtElapsedRealtimeMs - it.analysisUpdatedAtMs).coerceAtLeast(0) else 0,
                analysisUpdatedAtMs = analysis.capturedAtElapsedRealtimeMs,
                monitoringScopes = analysis.scopes,
                zebraCells = analysis.zebraCells,
                focusPeakingMask = analysis.focusPeaking,
            ) }
        }

        override fun onAnalysisSuspended(reason: com.librestatic.opencinecam.camera.AnalysisSuspension) {
            // cameraExecutor, on change only. The UI hides stale scopes and marks the scope
            // buttons unavailable from this field; preview and any take are unaffected.
            if (serviceDestroyed) return
            cameraState.update { it.copy(analysisSuspension = reason) }
        }

        override fun onTimelapseProgress(progress: TimelapseProgress) {
            mainHandler.post {
                val current = cameraState.value
                if (current.selectedMode != CaptureMode.TIME_LAPSE || current.phase !in setOf(CameraUiPhase.CAPTURING, CameraUiPhase.RECORDING) || current.recordingFinalizing) return@post
                cameraState.value = current.copy(timelapseFramesCaptured = progress.submittedFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    timelapseMissedIntervals = progress.missedIntervals, timelapseEncoder = progress.codecName, timelapseHardwareEncoder = progress.hardwareAccelerated)
                if (current.timelapseLimitMode == TimeLapseLimitMode.FRAME_COUNT && progress.submittedFrames >= current.timelapseFrameCount) localBinder.stopRecording()
            }
        }

        override fun onTimelapsePauseChanged(status: com.librestatic.opencinecam.camera.TimelapsePauseStatus) {
            mainHandler.post {
                val current = cameraState.value
                if (current.phase != CameraUiPhase.RECORDING || current.recordingFinalizing ||
                    current.recordingPauseStatus?.takeId?.let { it != status.takeId } == true) return@post
                cameraState.value = current.copy(recordingPauseStatus = status, recordingPausePending = false,
                    recordingElapsedMs = status.activeElapsedAt(android.os.SystemClock.elapsedRealtime()))
                scheduleTimelapseAutoStop()
            }
        }

        override fun onRecordingLutApplied(evidence: BakedLutEvidence) {
            cameraState.update { state ->
                val intent = recordingLutIntent.get()
                if (!serviceDestroyed && intent != null && transferCapture.get() === intent.owner &&
                    evidence.selectionId == monitorLutIdentity(intent.lut))
                    state.copy(recordingLutStatus = OperatorLutStatus(evidence.hash, OperatorLutState.ACTIVE, selectionId = evidence.selectionId)) else state
            }
        }

        override fun onRecordingStarted(width: Int, height: Int) {
            dispatchedTake.markStarted()
            driveContinuation.takeStarted()
            submitCaptureState(stateCommands.takeStarted())
            timecodeTracker.onRecordingStarted(previewEngine.encodedRecordingProgress()?.takeId)
            previewEngine.encodedRecordingProgress()?.let(timecodeTracker::observeEncodedProgress)
            previewEngine.consumeTimelapsePauseStatus()
            previewEngine.consumeCaptureEpochReport()
            recordingStartedAtMs = android.os.SystemClock.elapsedRealtime()
            takeClipStartedAt = java.time.Instant.now()
            takeClipIntent = runCatching { clipIntentSnapshot() }.getOrNull()
            takeClipCodecMime = previewEngine.activeVideoCodec()?.mime
            takeClipMediaRecorder = previewEngine.recordsThroughMediaRecorder()
            takeCameraTracker = ClipCameraTracker().also { it.sample(cameraState.value) }
            subjectSyncMarkers.begin(android.os.SystemClock.elapsedRealtimeNanos())
            audioSidecarRecorder?.let { sidecar ->
                try {
                    sidecar.start()
                } catch (failure: Throwable) {
                    synchronized(takeOwnershipLock) {
                        // A concurrent failure/recovery may already own (and finish) this sidecar.
                        if (audioSidecarRecorder === sidecar) {
                            audioStartFailure = failureText(R.string.capture_lossless_audio_start_failed, failure)
                            audioSidecarRecorder = null
                        }
                    }
                    sidecar.finish(false)
                    previewEngine.stopVideo()
                    return
                }
            }
            mainHandler.removeCallbacks(recordingTicker)
            cameraState.value = cameraState.value.copy(
                phase = CameraUiPhase.RECORDING,
                message = if (cameraState.value.selectedMode == CaptureMode.HLG) {
                    "REC ${width}×$height · ${hdrTakeFormat(cameraState.value).label} BT.2020 · HEVC Main10 · ${cameraState.value.targetFps} fps · $activeRecordingAudioLabel"
                } else if (cameraState.value.selectedMode.usesLogGraph) {
                    val source = if (cameraState.value.activeLogProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                        getString(R.string.capture_source_hfr_isp_derived) + " · " + getString(R.string.capture_source_depth_not_claimed)
                    } else getString(R.string.capture_source_hlg_derived)
                    "REC ${width}×$height · ${if (recordingLutIntent.get()?.lut != null) "LUT → SDR BT.709" else "OCLog2"} · HEVC Main10 · $source · ${cameraState.value.targetFps} fps · $activeRecordingAudioLabel"
                } else if (cameraState.value.selectedMode == CaptureMode.TIME_LAPSE) {
                    val intervalLabel = formatTimelapseInterval(cameraState.value.timelapseIntervalMs)
                    getString(R.string.timelapse_recording, intervalLabel, cameraState.value.recordingProjectRate?.projectLabel() ?: settings.timelapseProjectRate.projectLabel())
                } else {
                    "REC ${width}×$height · ${previewEngine.activeVideoCodec()?.label ?: "H.264"} · $activeRecordingAudioLabel" +
                        cameraState.value.recordingProjectRate?.let { " · " + getString(R.string.project_active_rate, it.projectLabel()) }.orEmpty()
                },
                recordingElapsedMs = 0L,
                recordingPauseStatus = null,
                recordingAvTiming = null,
                recordingPausePending = false,
                recordingWidth = width,
                recordingHeight = height,
                availableStorageBytes = recordingVolumeFreeBytes(),
                recordingWarning = recordingWarningText(),
                errorCode = null,
            )
            activeRecordingPolicy = settings.recordingPolicy
            takeStartHardwareTruth = cameraState.value.hardwareTruth
            lastKnownFreeBytes = cameraState.value.availableStorageBytes
            runtimeNotice = null
            avNotice = null
            cadenceNotice = null
            previewEngine.setRecordingCadencePolicy(activeRecordingPolicy)
            avDiagnostics = if (audioSidecarRecorder != null) AvClockDiagnostics(activeRecordingPolicy, correlationId = "recording-av-drift") else null
            pendingStopReason = null
            scheduleTimelapseAutoStop()
            mainHandler.post(recordingTicker)
        }

        override fun onRecordingStopped(success: Boolean) {
            android.util.Log.i("CaptureService", "Recording stopped: success=$success mode=${cameraState.value.selectedMode} fps=${cameraState.value.targetFps}")
            retireAudioLevelConsumer()
            // A drive take whose muxer failed on ENODEV ends here before the volume event; name the drive as the reason.
            val takeDrive = takeDestination?.destination as? RecordingDestination.UsbFolder
            val lostDrive = takeDrive?.takeIf { drive -> !success && mountedRecordingVolumes().none { it.equals(drive.volumeId, ignoreCase = true) } }
            val stopReason = pendingStopReason ?: lostDrive?.let { driveLostFailure(writeFailed = true) }
            pendingStopReason = null
            // ADR-0037: a take ended by its drive closes every descriptor on that drive before finishing or publishing.
            if (takeDrive != null && stopReason?.component == RECORDING_DRIVE_COMPONENT) releaseDriveDescriptors(takeDrive.volumeId, "the take lost its drive")
            // PLAN-072 Delivery 4: every way a drive take ends because the drive went (the volume event, a sidecar writer's
            // ENODEV, the muxer's) converges here once per take; the continuation, if any, is armed now and started once this
            // stop has fully finished. The take's own continuation link is read before detachTake() clears it.
            val continuedFrom = takeContinuation
            val continuationNote = run {
                val parent = videoOutput ?: return@run null
                val mode = cameraState.value.selectedMode
                val intent = recordingLutIntent.get()
                decideDriveContinuation(stopReason, onDrive = takeDrive != null, mode = mode,
                    highSpeed = cameraState.value.activeVideoProfile?.constrainedHighSpeed == true || takeClipMediaRecorder == true,
                    rawQualifiedOnPhone = false,
                    phoneRoom = admitRecordingStorage(activeRecordingPolicy, takeProjectedBytesPerMinute, phoneVolumeFreeBytes()) !is StorageAdmission.Refuse,
                ) {
                    val names = intent?.captureNames ?: return@decideDriveContinuation null
                    // A continuation records on the phone and cannot lose a drive, so index is 1 in practice; kept general.
                    val index = (continuedFrom?.link?.index ?: 0) + 1
                    val rootStem = continuedFrom?.let { it.stem.removeSuffix("_cont${it.link.index}") } ?: parent.displayName.removeSuffix(".mp4")
                    ContinuationRequest(mode, continuationFileStem(rootStem, index),
                        ClipContinuation(parent.recoveryGroup.id, parent.displayName, index, requireNotNull(stopReason).correlationId),
                        names, audio = activeRecordingAudioLabel != getString(R.string.capture_no_audio))
                }
            }
            previewEngine.setRecordingCadencePolicy(null)
            // The main-thread ticker may be mid-observe on this instance: close it on main, after that tick.
            val finishedDiagnostics = avDiagnostics
            avDiagnostics = null
            if (finishedDiagnostics != null) mainHandler.post { finishedDiagnostics.close() }
            val recordingGain = activeRecordingGain
            val hardwareTruth = hardwareTruthTake(takeStartHardwareTruth, cameraState.value.hardwareTruth, takeSoftwareEncoder?.let { TakeVideoEncoder(it) })
            // Only the exact dispatched admission; a failed start may already have released it.
            val transferReservation = dispatchedTake.claimStopped()
            submitCaptureState(stateCommands.takeEnded())
            val frozenLut = recordingLutIntent.get()?.takeIf { it.owner === transferReservation }?.lut
            previewEngine.releaseRecordingWhiteBalance()
            cameraState.value = cameraState.value.copy(recordingFinalizing = true)
            mainHandler.removeCallbacks(recordingTicker)
            cancelTimelapseAutoStop()
            val take = detachTake()
            val output = take.video
            // A failed content callback alone never proves native retirement. The exact file
            // receipt can succeed even when encoding failed, but release/close failures keep it fenced.
            val retirement = previewEngine.stoppedRecordingOutputRetirement()
            val nativeRetired = retirement.isDone && !retirement.isCompletedExceptionally && !retirement.isCancelled
            if (nativeRetired) releaseRecordingRecoveryGuard(output)
            else {
                val guard = output?.let(recordingRecoveryGuards::get)
                retirement.whenComplete { _, failure ->
                    if (failure == null && output != null && guard != null && recordingRecoveryGuards.remove(output, guard)) {
                        runCatching { guard.close() }.onFailure {
                            android.util.Log.e("RecordingRecovery", "Retired take cleanup retained for retry", it)
                        }
                    }
                }
            }
            val transferPublication = take.publication
            var avTiming = previewEngine.consumeCaptureEpochReport()
            lastSeparateAudioAvDrift = null
            val timing = previewEngine.consumeTimelapsePauseStatus()
            val durationMs = timing?.activeElapsedMs ?: activeRecordingElapsedMs()
            val encodedProgress = previewEngine.encodedRecordingProgress()
            encodedProgress?.let(timecodeTracker::observeEncodedProgress)
            // Read now: a later take resets the engine's progress before a deferred salvage can look at it.
            val encodedFrames = encodedProgress?.frameCount
            val projectRateAtStop = cameraState.value.recordingProjectRate
            var startDeferredSalvage: (() -> Unit)? = null
            val timecodeReport = timecodeTracker.recordingReport()
            val subjectSyncMarker = subjectSyncMarkers.consume()
            // Everything the engine or the next take can overwrite is read here, on the camera thread.
            val graphEvidence = previewEngine.consumeLastOpenCineLogEvidence()
            val bakedEvidence = previewEngine.consumeLastRecordingLutEvidence()
            val takeUsedLogGraph = cameraState.value.selectedMode.usesLogGraph
            val takeAudioLabel = activeRecordingAudioLabel
            // The clip sidecar's inputs, read here for the same reason: the next take overwrites them.
            val clipStartedAt = takeClipStartedAt
            val clipIntent = takeClipIntent?.copy(timecodeEnabled = timecodeReport?.config?.enabled == true)
            val clipCodecMime = takeClipCodecMime
            val clipMediaRecorder = takeClipMediaRecorder
            val clipState = cameraState.value
            val clipCamera = takeCameraTracker?.also { it.sample(clipState) }?.result()
            takeClipMediaRecorder = null
            takeClipStartedAt = null
            takeClipIntent = null
            takeClipCodecMime = null
            takeCameraTracker = null
            val clipDeviceProfile = clipDeviceProfile(clipState.selectedCameraId)
            val clipNoticeCodes = listOfNotNull(runtimeNotice, avNotice, cadenceNotice).map { it.name.lowercase().replace('_', '-') }
            val clipStopReason = stopReason?.let { takeStopReason(it).name.lowercase().replace('_', '-') }
            val clipSoftwareEncoder = takeSoftwareEncoder
            // PLAN-072 Delivery 4: the continuation starts after both the reservation release and the end of [finishTake].
            val continuationGate = java.util.concurrent.atomic.AtomicInteger(2)
            fun continuationStep() {
                if (continuationGate.decrementAndGet() == 0 && driveContinuation.isPending) mainHandler.post { startDriveContinuation() }
            }
            // Publication reads the MP4 back, writes MediaStore and the sidecars. It runs on the storage
            // thread so the engine can bring the preview back at once; recordingFinalizing keeps the next
            // take out until it is done.
            val finishTake = Runnable {
                try {
                    val requestedAudioFailure = take.audioStartFailure
                    val audioOutput = take.audio
                    // HLG takes run through the same graph but are standard HDR video: no OCLog sidecar.
                    val hlgEvidence = graphEvidence?.takeIf { HdrVideoFormat.fromCurve(it.curve) != null }
                    val logEvidence = graphEvidence?.takeUnless { HdrVideoFormat.fromCurve(it.curve) != null }
                    val bakingVerified = bakedEvidence?.selectionId == monitorLutIdentity(frozenLut)
                    // ADR-0011: stable reasons a take is not fully successful; any of them makes it incomplete.
                    val muxerFinalized = success && nativeRetired
                    val incompleteReasons = buildList {
                        if (requestedAudioFailure != null) add(IncompleteTake.AUDIO_START_FAILED)
                        if (!success) add(IncompleteTake.ENCODER_FAILED)
                        if (!nativeRetired) add(IncompleteTake.NATIVE_RETIREMENT_PENDING)
                        if (!muxerFinalized) add(IncompleteTake.MUXER_NOT_FINALIZED)
                        if (!bakingVerified) add(IncompleteTake.RECORDING_LUT_UNVERIFIED)
                        if (takeUsedLogGraph && graphEvidence == null) add(IncompleteTake.LOG_EVIDENCE_MISSING)
                    }
                    val finalizedSuccess = incompleteReasons.isEmpty()
                    val geometry = take.geometry
                    var registrationCreationFailed = transferPublication == null || transferPublication.registrationFailed
                    val publicationObserver = transferPublication?.observer ?: try { CapturePublicationJournal(this@CaptureService) }
                        catch (problem: Throwable) {
                            registrationCreationFailed = true
                            android.util.Log.e("CapturePublication", "Publication journal creation failed; capture remains independent", problem)
                            null
                        }
                    var clipSeparateAudio: ClipSeparateAudio? = null
                    fun noteAudioPrepared(sidecar: AudioSidecarRecorder, prepared: com.librestatic.opencinecam.storage.PreparedAudioSidecar?):
                        com.librestatic.opencinecam.storage.PreparedAudioSidecar? {
                        val result = prepared?.result
                        if (prepared != null && result != null) clipSeparateAudio = ClipSeparateAudio(
                            result.container, result.sampleRateHz, result.bitDepth.bits, prepared.artifacts.map { it.displayName })
                        if (result != null) avTiming = sidecar.captureClock?.report(avTiming?.videoEncoderFirstPtsUs, null, null)
                            ?.copy(submittedPcmFrames = result.frames, audioStorage = "SEPARATE_${result.container}") ?: avTiming
                        lastSeparateAudioAvDrift = result?.let {
                            separateAudioAvDrift(sidecar.captureClock?.cameraRealtime == true, it.captureTiming, it.displayName)
                        }?.also { android.util.Log.i("AvClockDiagnostics", separateAudioAvDriftLog(it)) }
                        return prepared
                    }
                    fun buildSidecarJson(): String? {
                        val metadata = logEvidence?.copy(avTiming = avTiming)?.let { logSidecarJson(it, timecodeReport) }
                            ?: hlgEvidence?.let { hlgSidecarJson(it, avTiming) }
                            ?: avTiming?.let { JSONObject().put("schema", "opencinecam.av-timing.v1").put("avTiming", captureEpochJson(it)).toString(2) }
                            ?: timing?.let { timelapseTimingJson(it, requireNotNull(projectRateAtStop)) }
                        val withBaking = if (bakedEvidence != null) {
                            (metadata?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-color.v1"))
                                .put("recordingLut", recordingLutJson(bakedEvidence)).toString(2)
                        } else metadata
                        val withGain = if (recordingGain?.enabled == true) {
                            (withBaking?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-audio.v1"))
                                .put("recordingGain", recordingGainJson(recordingGain)).toString(2)
                        } else withBaking
                        val withTimecode = if (timecodeReport?.config?.enabled == true) {
                            (withGain?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-timing.v1"))
                                .put("timecode", recordingTimecodeJson(timecodeReport)).toString(2)
                        } else withGain
                        val withMarker = subjectSyncMarker?.let { marker ->
                            (withTimecode?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-timing.v1"))
                                .put(com.librestatic.opencinecam.SUBJECT_SYNC_MARKER_KEY,
                                    JSONObject(com.librestatic.opencinecam.subjectSyncMarkerJson(marker).toString())).toString(2)
                        } ?: withTimecode
                        val withLens = graphEvidence?.lensCorrection?.let { lens ->
                            (withMarker?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-color.v1"))
                                .put("lensCorrection", lensCorrectionJson(lens)).toString(2)
                        } ?: withMarker
                        val withExposure = graphEvidence?.exposureCompensation?.let { compensation ->
                            (withLens?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-color.v1"))
                                .put("exposureCompensation", exposureCompensationJson(compensation)).toString(2)
                        } ?: withLens
                        return withHardwareTruth(withExposure, hardwareTruth)
                    }
                    fun timingSidecarFlag() = logEvidence == null && (graphEvidence?.lensCorrection != null || graphEvidence?.exposureCompensation != null || hlgEvidence != null || bakedEvidence != null || recordingGain?.enabled == true || timing != null || avTiming != null || timecodeReport?.config?.enabled == true || subjectSyncMarker != null || hardwareTruth != null)
                    // The canonical clip sidecar; evaluated when the video is prepared, after the audio is, so it sees both.
                    val clipSidecar: ClipSidecarProvider? = clipStartedAt?.let { started ->
                        clipSidecarProvider {
                            assembleClipFacts(ClipFactsInput(
                                startedAt = started, intent = clipIntent, cameraId = clipState.selectedCameraId,
                                hardware = clipState.hardwareTruth, recordingWidth = clipState.recordingWidth,
                                recordingHeight = clipState.recordingHeight, targetFps = clipState.targetFps,
                                graphEvidence = graphEvidence, hdr = hlgEvidence != null,
                                lutBaked = bakedEvidence != null, timelapse = timing != null,
                                mediaRecorder = clipMediaRecorder, recorderCodecMime = clipCodecMime, softwareEncoderName = clipSoftwareEncoder,
                                sharedPause = avTiming?.sharedPause, separateAudio = clipSeparateAudio,
                                embeddedAudio = clipIntent?.audioEnabled == true && audioOutput == null &&
                                    requestedAudioFailure == null && takeAudioLabel != "no audio",
                                deviceProfile = clipDeviceProfile, noticeCodes = clipNoticeCodes,
                                hardwareWarnings = hardwareTruth?.warnings.orEmpty(), stopReason = clipStopReason,
                                durationMs = durationMs, encodedFrames = encodedFrames,
                                success = success, nativeRetired = nativeRetired, camera = clipCamera,
                            )).copy(continuation = continuedFrom?.link)
                        }
                    }
                    fun salvagePlan(reasons: List<String>) = com.librestatic.opencinecam.storage.IncompleteTakeSalvage<
                        com.librestatic.opencinecam.storage.PreparedVideoOutput, com.librestatic.opencinecam.storage.PreparedAudioSidecar>(
                        reasons = reasons,
                        prepareAudio = audioOutput?.let { sidecar -> { r -> noteAudioPrepared(sidecar, sidecar.prepareIncompleteCompletion(r)) } },
                        prepareVideo = { r ->
                            output?.sealMotionLog(keep = false)
                            output?.prepareIncomplete(r, buildSidecarJson(), timingSidecarFlag(), muxerFinalized, encodedFrames, clipSidecar)
                        },
                    )
                    val finalization = if (!nativeRetired) {
                        // Never publish, and never delete, under a possibly live native writer. Rows stay pending and
                        // journalled; once retirement is proven they are published as incomplete (ADR-0011), and a
                        // process death before that is covered by crash recovery.
                        runCatching { publicationObserver?.onAborted() }
                        val published = incompleteReasons.filter { it != IncompleteTake.NATIVE_RETIREMENT_PENDING }
                        startDeferredSalvage = {
                            val expected = cameraState.value.message
                            salvageAfterRetirement(output, audioOutput, retirement, incompleteReasons, run = {
                                finalizePreparedRecordingTake<
                                    com.librestatic.opencinecam.storage.PreparedVideoOutput,
                                    com.librestatic.opencinecam.storage.PreparedAudioSidecar,
                                    android.net.Uri,
                                    com.librestatic.opencinecam.storage.AudioSidecarRecordingResult,
                                >(
                                    success = false,
                                    prepareAudio = null,
                                    prepareVideo = { null },
                                    publishAudio = { _ -> audioOutput?.publishPrepared() },
                                    publishVideo = { _ -> output?.publishPrepared() },
                                    discardAudio = { audioOutput?.finish(false); Unit },
                                    discardVideo = { output?.finish(false); Unit },
                                    salvage = salvagePlan(published),
                                )
                            }, onSaved = { announceIncompleteSaved(expected, it) })
                        }
                        com.librestatic.opencinecam.storage.PreparedTakeResult<android.net.Uri, com.librestatic.opencinecam.storage.AudioSidecarRecordingResult>(null, emptyList())
                    } else finalizePreparedRecordingTake<
                        com.librestatic.opencinecam.storage.PreparedVideoOutput,
                        com.librestatic.opencinecam.storage.PreparedAudioSidecar,
                        android.net.Uri,
                        com.librestatic.opencinecam.storage.AudioSidecarRecordingResult,
                    >(
                        success = finalizedSuccess,
                        prepareAudio = audioOutput?.let { sidecar -> { noteAudioPrepared(sidecar, sidecar.prepareCompletion()) } },
                        discardAudio = { audioOutput?.finish(false); Unit },
                        discardVideo = { output?.finish(false); Unit },
                        publishAudio = { _ -> audioOutput?.publishPrepared() },
                        publishVideo = { _ -> output?.publishPrepared() },
                        prepareVideo = {
                            // A pause removes spans from the file timeline, so sensor time can no longer be mapped to it.
                            output?.sealMotionLog(keep = finalizedSuccess && avTiming?.sharedPause?.windows.isNullOrEmpty())
                            output?.prepareCompletion(
                            buildSidecarJson(),
                            expectedGeometry = geometry,
                            timingSidecar = timingSidecarFlag(),
                            clipSidecar = clipSidecar,
                        ) },
                        onPrepared = { video, audio -> publicationObserver?.onPrepared(video.artifacts + audio?.artifacts.orEmpty()); Unit },
                        onPublished = { video, audio -> publicationObserver?.onPublished(video.artifacts + audio?.artifacts.orEmpty()); Unit },
                        onAborted = { publicationObserver?.onAborted(); Unit },
                        salvage = salvagePlan(incompleteReasons),
                    )
                    val completedTake = finalization.outputs
                    finalization.incompleteFailures.forEach { problem ->
                        android.util.Log.e("RecordingRecovery", "Incomplete publication step failed; affected outputs stay pending for recovery", problem)
                    }
                    val savedIncomplete = finalization.incomplete
                    finalization.registrationFailures.forEach { problem ->
                        android.util.Log.e("CapturePublication", "Publication journal update failed; originals retained", problem)
                    }
                    val registrationFailed = registrationCreationFailed || finalization.registrationFailures.isNotEmpty() || transferPublication?.registrationFailed == true
                    timecodeReport?.let { timecodeTracker.onRecordingStopped(completedTake != null, it.lifecycleToken) }
                    val uri = completedTake?.first
                    val sidecarResult = completedTake?.second
                    val registrationNotice = if (completedTake == null || finalization.incomplete) "" else buildString {
                        if (transferPublication?.registered == true) append(" · " + getString(R.string.webdav_queue_registered))
                        if (registrationFailed) append(" · " + getString(if (transferPublication?.registrationFailed == true)
                            R.string.webdav_queue_registration_failed else R.string.capture_publication_registration_failed))
                    }
                    if (uri != null) output?.productionSlate?.let(::incrementSavedSlate)
                    // PLAN-036: judge the published Main10 file's signalling off every service thread; evidence only.
                    if (uri != null && !finalization.incomplete) {
                        val checkKind = if (hlgEvidence != null) com.librestatic.opencinecam.camera.Main10TakeKind.HLG
                            else if (logEvidence != null) com.librestatic.opencinecam.camera.Main10TakeKind.LOG else null
                        if (checkKind != null) com.librestatic.opencinecam.Main10FileCheckScheduler.schedule(this@CaptureService, uri.toString(), checkKind, output?.displayName)
                    }
                    // A SAVED observer must see the registration outcome in the same state emission.
                    cameraState.update { current -> current.copy(
                        // A drive part about to continue on the phone is not an error to recover from: the preview is back.
                        phase = if (finalizedSuccess) CameraUiPhase.SAVED
                            else if (driveContinuation.isPending) CameraUiPhase.PREVIEWING else CameraUiPhase.ERROR,
                        recordingFinalizing = false,
                        recordingWarning = null,
                        recordingPauseStatus = timing,
                        recordingAvTiming = avTiming,
                        recordingPausePending = false,
                        recordingWhiteBalanceStatus = RecordingWhiteBalanceStatus.IDLE,
                        lastSavedUri = uri?.toString(),
                        message = ((if (finalizedSuccess) {
                            val audioSaved = savedAudioSuffix(
                                sidecarResult?.let { SavedSidecarAudio(it.bitDepth.bits, it.container, it.sampleRateHz) },
                                takeAudioLabel,
                                messageLocale,
                            )
                            val seconds = durationMs / 1000.0
                            if (hlgEvidence != null) {
                                getString(R.string.capture_hdr_video_saved, HdrVideoFormat.fromCurve(hlgEvidence.curve)?.label ?: "HLG", audioSaved, seconds) + captureEpochLabel(avTiming)
                            } else if (logEvidence != null) {
                                val source = getString(if (logEvidence.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                                    R.string.capture_source_hfr_isp_derived
                                } else R.string.capture_source_hlg_derived)
                                val outputColor = if (bakedEvidence != null) "LUT → SDR BT.709" else "OCLog2"
                                getString(R.string.capture_log_video_saved, outputColor, source, audioSaved, seconds) + captureEpochLabel(avTiming)
                            }
                            else getString(R.string.capture_video_saved, audioSaved, seconds) + captureEpochLabel(avTiming)
                        } else if (savedIncomplete) {
                            getString(R.string.take_saved_incomplete,
                                requestedAudioFailure ?: (finalization.incompleteReasons.ifEmpty { incompleteReasons }).joinToString(", "))
                        } else requestedAudioFailure ?: getString(R.string.capture_video_finalize_failed)) + registrationNotice)
                            .let { outcome -> if (stopReason == null) outcome else getString(R.string.take_stopped_reason, stopReasonText(stopReason), outcome) }
                            .let { message -> continuationNote?.let { "$message · $it" } ?: message },
                        errorCode = if (finalizedSuccess) null else incompleteReasons.first(),
                        recordingElapsedMs = durationMs,
                    ) }
                    // After the state above, so a publication that completes at once finds the message to replace.
                    startDeferredSalvage?.invoke()
                } catch (failure: Throwable) {
                    // ADR-0011: nothing that holds bytes is deleted here; it stays pending for crash recovery.
                    runCatching { output?.recoveryGroup?.beginSalvage() }
                    runCatching { take.audio?.prepareIncompleteCompletion(listOf(com.librestatic.opencinecam.storage.PUBLICATION_FAILED_REASON)) }
                    runCatching { output?.finish(false) }
                    // PLAN-072: a drive that has gone cannot be published to (`No root for <VOLUME>`). That is the expected
                    // end of a drive take, not an error: its folder stays pending for recovery when the drive returns, and
                    // a continuation, if armed, must find the preview back rather than an error phase.
                    val driveGone = takeDrive != null && mountedRecordingVolumes().none { it.equals(takeDrive.volumeId, ignoreCase = true) }
                    if (driveGone) {
                        android.util.Log.w("RecordingDestination", "Drive take left for recovery: ${failure.message}")
                        val outcome = getString(R.string.capture_video_not_finalised_drive)
                        cameraState.update { current -> current.copy(
                            phase = CameraUiPhase.SAVED,
                            recordingFinalizing = false,
                            recordingWarning = null,
                            recordingPausePending = false,
                            recordingWhiteBalanceStatus = RecordingWhiteBalanceStatus.IDLE,
                            message = (if (stopReason == null) outcome else getString(R.string.take_stopped_reason, stopReasonText(stopReason), outcome))
                                .let { message -> continuationNote?.let { "$message · $it" } ?: message },
                            errorCode = stopReason?.correlationId ?: "drive-disconnected",
                        ) }
                    } else fail("video-save-failed", failureText(R.string.capture_video_save_failed, failure))
                } finally {
                    // A take left pending (a drive pulled mid-take) becomes recoverable now, not only in a new process;
                    // a deferred salvage relinquishes it itself once it has run.
                    if (startDeferredSalvage == null) runCatching { output?.recoveryGroup?.relinquish() }
                    try { transferPublication?.close() } catch (problem: Exception) {
                        android.util.Log.e("CapturePublication", "Optional queue handle cleanup failed", problem)
                    }
                    timecodeReport?.let { timecodeTracker.onRecordingStopped(false, it.lifecycleToken) }
                    // The preview may already be back; the monitor waited for this reservation. A continuation (PLAN-072
                    // Delivery 4) starts instead, once the reservation is released and this runnable has stopped the
                    // foreground service, so that stop can never land on the continuation's own.
                    if (transferReservation == null) continuationStep()
                    releaseTransferCapture(transferReservation) { if (driveContinuation.isPending) continuationStep() else startPreviewAudioMonitorIfEligible() }
                }
                if (timecodeTracker.continuationStorageFailed) cameraState.value = cameraState.value.copy(
                    message = cameraState.value.message.orEmpty() + " · " + getString(R.string.timecode_continuity_failed))
                // A pending continuation keeps the foreground service through the gap: Android may refuse a camera one
                // started again from the background (screen off), and the continuation's start updates this one.
                if (!driveContinuation.isPending) {
                    foreground.stop()
                    // Drop only the started-service lifetime; a bound operator/exterior keeps the service.
                    stopSelf()
                } else mainHandler.postDelayed({
                    // A reservation release that never called back leaves the token to expire: drop the service with it.
                    if (!driveContinuation.isPending) releaseContinuationForeground()
                }, DriveContinuation.DEFAULT_TTL_MS + 1_000L)
                mainHandler.post { localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value) }
                continuationStep()
            }
            try {
                storageExecutor.execute(finishTake)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // A full queue or a destroyed service: finish here, as before, rather than lose the take.
                finishTake.run()
            }
        }

        override fun onTapFocusState(state: TapFocusState) {
            cameraState.value = cameraState.value.copy(tapFocusState = state)
        }

        override fun onPhysicalRoute(requestedPhysicalId: String?, reportedPhysicalId: String?) {
            val truth = routeTruthOf(requestedPhysicalId, reportedPhysicalId)
            // Reported once per still, so a mismatch is logged once per still, never per preview frame.
            if (truth == RouteTruth.MISMATCH) {
                android.util.Log.w("PhysicalRoute", "evidence=physical-route-mismatch requested=$requestedPhysicalId reported=$reportedPhysicalId")
            }
            cameraState.update { it.copy(hardwareTruth = it.hardwareTruth.withRoute(requestedPhysicalId, reportedPhysicalId)) }
        }

        override fun onAuditWarning(message: String) {
            cameraState.update { it.copy(hardwareTruth = it.hardwareTruth.withWarning(message)) }
        }

        override fun onActiveLens(physicalId: String?) {
            cameraState.update { it.copy(hardwareTruth = it.hardwareTruth.withActiveLens(physicalId)) }
        }

        override fun onSustainedCadenceLoss(action: com.librestatic.opencinecam.camera.CaptureAuditAction, detail: String) {
            mainHandler.post {
                if (serviceDestroyed || cameraState.value.phase != CameraUiPhase.RECORDING) return@post
                if (action == com.librestatic.opencinecam.camera.CaptureAuditAction.STOP) localBinder.stopRecording(cadenceStopFailure(detail))
                else { cadenceNotice = RuntimeNotice.CADENCE; publishRecordingWarning() }
            }
        }

        override fun onCadenceGuardChanged(engaged: Boolean) {
            if (cameraState.value.fpsLockHolding != engaged) cameraState.value = cameraState.value.copy(fpsLockHolding = engaged)
        }

        override fun onAeLockChanged(active: Boolean) {
            cameraState.value = cameraState.value.copy(aeLockActive = active)
        }

        override fun onAfLockChanged(state: LockState) {
            cameraState.value = cameraState.value.copy(afLockState = state)
        }

        override fun onFocusSelectionChanged(diopters: Float?) {
            cameraState.value = cameraState.value.copy(requestedFocusDiopters = diopters)
        }

        override fun onFocusPullStarted(targetDiopters: Float) {
            cameraState.value = cameraState.value.copy(
                focusPullActive = true,
                focusPullTargetDiopters = targetDiopters,
                requestedFocusDiopters = targetDiopters,
                tapFocusState = TapFocusState.IDLE,
            )
        }

        override fun onFocusPullFinished() {
            cameraState.value = cameraState.value.copy(
                focusPullActive = false,
                focusPullTargetDiopters = null,
                focusMarks = previewEngine.getFocusMarks(),
            )
        }

        override fun onFocusPullCancelled() {
            cameraState.value = cameraState.value.copy(
                focusPullActive = false,
                focusPullTargetDiopters = null,
            )
        }

        override fun onZoomRangeAvailable(min: Float, max: Float, anchors: List<ZoomAnchor>) {
            val current = cameraState.value
            cameraState.value = current.copy(
                zoomMinRatio = min,
                zoomMaxRatio = max,
                opticalAnchors = anchors,
                zoomSupported = min < max,
                zoomHfrSupported = current.descriptor?.supportsHfrZoom == true,
                zoomRatio = (perCameraZoom[current.selectedCameraId] ?: 1f).coerceIn(min, max),
            )
        }

        override fun onLensLock(physicalId: String?, uiZoomRange: ClosedFloatingPointRange<Float>?) {
            if (physicalId == null || uiZoomRange == null) {
                clearLensLockRoute()
                return
            }
            serveLensLock(physicalId, uiZoomRange, com.librestatic.opencinecam.LensLockRoute.DIRECT_PHYSICAL)
        }

        override fun onLensLockCrop(physicalId: String, uiZoomRange: ClosedFloatingPointRange<Float>) {
            android.util.Log.i("CaptureService", "Lens lock on $physicalId served by the GPU crop over $uiZoomRange")
            // The lock is served, so this is not a fallback: the logical calibration stays off.
            lensLockFallback = false
            serveLensLock(physicalId, uiZoomRange, com.librestatic.opencinecam.LensLockRoute.GPU_CROP)
            // Photo previews straight to the window by default; the crop needs the GPU viewfinder.
            mainHandler.post {
                if (cameraState.value.lensLock.route != com.librestatic.opencinecam.LensLockRoute.GPU_CROP ||
                    cameraState.value.structuralSettingsFrozen || previewEngine.usesGpuViewfinder() || !desiredGpuViewfinder()) return@post
                attachedPreviewSurface?.takeIf { it.isValid }?.let { localBinder.attachPreview(it, attachedPreviewRotationDegrees) }
            }
        }

        override fun onPreviewGraphLoss(loss: com.librestatic.opencinecam.camera.PreviewGraphLoss) {
            cameraState.update { if (it.previewGraphLoss == loss) it else it.copy(previewGraphLoss = loss) }
        }

        override fun onVideoCodecFallback(reason: String) {
            android.util.Log.w("CaptureService", "Take records H.264: $reason")
            takeCodecFallback = true
            publishRecordingWarning()
        }

        override fun onLensLockSwitched(switched: Boolean) {
            cameraState.update { state ->
                val warning = lensLockSwitchedWarning(switched, state.lensLock.route)
                if (state.lensLock.lensSwitchedWarning == warning) state
                else state.copy(lensLock = state.lensLock.copy(lensSwitchedWarning = warning))
            }
        }

        /** Publishes a served lock: the locked lens' own range on the logical scale; the anchors are the lock options. */
        private fun serveLensLock(physicalId: String, uiZoomRange: ClosedFloatingPointRange<Float>, route: com.librestatic.opencinecam.LensLockRoute) {
            refreshLensLockState()
            cameraState.update { state ->
                val anchors = state.lensLock.options.map { ZoomAnchor(it.anchorRatio, it.focalLengthMm, it.physicalId) }
                state.copy(
                    lensLock = state.lensLock.copy(lockedPhysicalId = physicalId, route = route, lensSwitchedWarning = false),
                    zoomMinRatio = uiZoomRange.start,
                    zoomMaxRatio = uiZoomRange.endInclusive,
                    opticalAnchors = anchors,
                    zoomSupported = uiZoomRange.start < uiZoomRange.endInclusive,
                    zoomHfrSupported = false,
                    zoomRatio = (perCameraZoom[state.selectedCameraId] ?: 1f).coerceIn(uiZoomRange.start, uiZoomRange.endInclusive),
                )
            }
        }

        override fun onLensLockUnavailable(physicalId: String, reason: String) {
            android.util.Log.i("CaptureService", "Lens lock on $physicalId unavailable ($reason); the logical camera is open")
            // The session now runs on the logical camera, so its calibration applies again. The
            // saved lock is kept and tried again by the next start.
            lensLockFallback = true
            refreshLensCorrection()
            pendingStatusMessage = getString(R.string.lens_lock_unavailable)
            clearLensLockRoute()
            cameraState.update { it.copy(messageTransient = true) }
        }

        override fun onZoomEffective(ratio: Float) {
            cameraState.update { it.copy(zoomEffectiveRatio = ratio) }
        }

        override fun onSubjectFramingChanged(status: com.librestatic.opencinecam.camera.SubjectFramingStatus) {
            cameraState.update { it.copy(subjectFraming = status) }
        }

        override fun onZoomRejected(requested: Float, accepted: Float) {
            cameraState.value = cameraState.value.copy(
                zoomRatio = accepted,
                zoomEffectiveRatio = accepted,
                message = getString(R.string.capture_zoom_rejected, ratioText(requested, messageLocale), ratioText(accepted, messageLocale)),
                messageTransient = true,
            )
        }

        override fun onControlRejected(code: String, message: String) {
            // The session kept its previous request: a refused control must never end or discard a take.
            android.util.Log.w("CaptureService", "Control rejected ($code): $message")
            // A Strict exposure gate falls back to auto exposure instead of keeping the previous request.
            val text = if (com.librestatic.opencinecam.camera.ExposureGateCode.entries.any { it.code == code }) R.string.control_rejected_auto else R.string.control_rejected
            cameraState.update { it.copy(message = getString(text, message), messageTransient = true) }
        }

        override fun onControlAdapted(code: String, message: String) {
            android.util.Log.i("CaptureService", "Control adapted ($code): $message")
            // Recorded as a hardware-truth warning too, so the take sidecar keeps the disclosure.
            cameraState.update { it.copy(message = getString(R.string.control_adapted, message), messageTransient = true, hardwareTruth = it.hardwareTruth.withWarning(message)) }
        }

        override fun onSoftwareVideoEncoder(codecName: String) {
            android.util.Log.w("CaptureService", "Software video encoder in use: $codecName")
            takeSoftwareEncoder = codecName
            // Recorded as a hardware-truth warning, so the take sidecar keeps the disclosure; never hardware evidence.
            cameraState.update { it.copy(hardwareTruth = it.hardwareTruth.withWarning(softwareVideoEncoderDisclosure(codecName))) }
        }

        override fun onRecordingStartRefused(code: String, message: String) {
            android.util.Log.w("CaptureService", "Take start refused ($code): $message")
            pendingStartRefusal = code
        }

        override fun onTorchRejected(message: String) {
            // A rejected optional light request must never finalize or discard a running clip.
            cameraState.value = cameraState.value.copy(
                message = getString(R.string.settings_torch_rejected, message),
                messageTransient = true,
                torchReported = null,
                photoFlashReport = null,
                torchStrengthReported = null,
            )
        }

        // OCC-PLAN-071: a refused RAW graph is not a camera failure. RAW is never dropped from the
        // session, so the mode turns FAILED for this camera and the operator goes back to the previous one.
        override fun onRawVideoUnavailable(reason: String, message: String) {
            mainHandler.post { if (!serviceDestroyed) rawVideoGraphRejected(reason, message) }
        }

        override fun onRawVideoStopped(reason: String?) = rawVideoTakeStopped(reason)

        override fun onFailure(code: String, message: String, recoverable: Boolean) {
            calibrationCameraLost()
            retireAudioLevelConsumer()
            submitCaptureState(stateCommands.engineFailed(code, message, recoverable))
            stillOwner.get()?.takeUnless { it.publishing.get() }?.let { owner ->
                if (stillOwner.compareAndSet(owner, null)) cameraState.update { it.copy(stillCapturePending = false, burstSaving = false, bracketSaving = false, accumulationSaving = false) }
            }
            // Observed on the failing engine thread so the receipt queues behind its cleanup.
            val retirement = previewEngine.recordingOutputRetirement()
            releaseFailedTakeStart(retirement)
            val cleanup = Runnable {
                // Take-owned and main-only state (transferWaiting, WB, timers) is mutated on main.
                stopPreviewAudioMonitor(clearLevels = false)
                mainHandler.removeCallbacks(recordingTicker)
                cancelTimelapseAutoStop()
                val take = detachTake()
                val encodedFrames = previewEngine.encodedRecordingProgress()?.frameCount
                abortTransferPublication(take.publication)
                foreground.stop()
                fail(code, engineFailureText(code, message) { getString(it) })
                // ADR-0011: what the take recorded is published as incomplete once native retirement is proven.
                salvageInterruptedTake(take, retirement, listOf(failureReason(code)), encodedFrames, cameraState.value.message)
                retireRecordingRecoveryGuards(retirement)
                stopSelf()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
            else mainHandler.post { if (!serviceDestroyed) cleanup.run() }
        }
    }

    inner class LocalBinder : Binder() {
        /** PLAN-072: the settings adopted or cleared the recording folder; re-reads it for the next take and the HUD. */
        fun refreshRecordingDestination() { mainHandler.post { this@CaptureService.refreshRecordingDestination() } }

        fun reconnectAudioListening() {
            if (serviceDestroyed) return
            audioListeningController?.configure(settings.audioListening, settings.audioListeningOutputDeviceId)
            audioListeningController?.reconnect()
        }

        val states: StateFlow<CaptureState> = state.asStateFlow()
        val cameraStates: StateFlow<CameraUiState> = cameraState.asStateFlow()
        val lensCalibrationStates: StateFlow<com.librestatic.opencinecam.LensCalibrationUiState> = lensCalibrationState.asStateFlow()

        /**
         * Locks the selected logical camera to [physicalId] (one of `CameraUiState.lensLock.options`),
         * or unlocks it with null. Persisted per logical camera in `CameraSettings.lensLock`; refused
         * while recording or while the capture controls are locked. Implemented by PLAN-070 slice 3a.
         */
        fun setLensLock(physicalId: String?) {
            if (serviceDestroyed) return
            val current = cameraState.value
            val cameraId = current.selectedCameraId ?: return
            // The lens is structural: never changed under a take or a calibration run.
            if (current.phase == CameraUiPhase.RECORDING || current.structuralSettingsFrozen || calibrationRun != null || rawQualificationSession != null) return
            if (rejectLockedControl()) return
            if (physicalId != null && (!current.lensLock.available || current.lensLock.options.none { it.physicalId == physicalId })) return
            if (settings.lensLock[cameraId] == physicalId) return
            val repository = SettingsRepositories.get(this@CaptureService)
            repository.update {
                it.copy(lensLock = if (physicalId == null) it.lensLock - cameraId else it.lensLock + (cameraId to physicalId))
            }
            // applySettings sees the change and restarts the preview on the new lens.
            applySettings(repository.states.value)
        }
        /** Starts a calibration run on the selected logical camera; the capture screen must be showing. */
        fun startLensCalibration() {
            if (serviceDestroyed || calibrationRun != null || rawQualificationSession != null) return
            calibrationStartDeadlineMs = android.os.SystemClock.elapsedRealtime() + calibrationStartWaitMs
            mainHandler.removeCallbacks(calibrationStartRetry)
            beginLensCalibration()
        }

        /** Stops a start that is still waiting for the viewfinder, or the run in progress. */
        fun cancelLensCalibration() {
            if (calibrationStartDeadlineMs != 0L) {
                calibrationStartDeadlineMs = 0L
                mainHandler.removeCallbacks(calibrationStartRetry)
                lensCalibrationState.update {
                    it.copy(phase = com.librestatic.opencinecam.LensCalibrationPhase.IDLE, referencePhysicalId = null, lenses = emptyList(), failure = null)
                }
            }
            val run = calibrationRun ?: return
            run.cancelled = true
            run.runner?.cancel()
        }

        /**
         * The start proper. The page that asks for a run hands over to the capture screen in the same
         * gesture, so the viewfinder is usually still attaching: a camera that is not yet previewing
         * (or has no surface) is waited for, showing PREPARING, until [calibrationStartDeadlineMs].
         */
        internal fun beginLensCalibration() {
            if (serviceDestroyed || calibrationRun != null || calibrationStartDeadlineMs == 0L) return
            val current = cameraState.value
            val cameraId = current.selectedCameraId
            val lenses = calibrationLensesOf(current.descriptor)
            fun refuse(reason: com.librestatic.opencinecam.LensCalibrationFailure) {
                calibrationPending = null
                lensCalibrationState.update {
                    it.copy(phase = com.librestatic.opencinecam.LensCalibrationPhase.FAILED, logicalCameraId = cameraId,
                        referencePhysicalId = null, lenses = emptyList(), failure = reason)
                }
            }
            if (cameraId == null || !current.zoomSupported || lenses.size < 2) {
                if (current.descriptor == null && android.os.SystemClock.elapsedRealtime() < calibrationStartDeadlineMs) {
                    mainHandler.postDelayed(calibrationStartRetry, 250L)
                    return
                }
                calibrationStartDeadlineMs = 0L
                refuse(com.librestatic.opencinecam.LensCalibrationFailure.NO_MULTI_CAMERA)
                return
            }
            val idle = current.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) && !current.structuralSettingsFrozen &&
                !current.captureControlsLocked && stillOwner.get() == null && transferCapture.get() == null && !recordingWbPreparing &&
                captureCountdown.active == null
            if (!idle || attachedPreviewSurface?.isValid != true) {
                val waiting = current.phase !in setOf(CameraUiPhase.RECORDING, CameraUiPhase.CAPTURING) && !current.structuralSettingsFrozen
                if (waiting && android.os.SystemClock.elapsedRealtime() < calibrationStartDeadlineMs) {
                    if (lensCalibrationState.value.phase != com.librestatic.opencinecam.LensCalibrationPhase.PREPARING) lensCalibrationState.update {
                        it.copy(phase = com.librestatic.opencinecam.LensCalibrationPhase.PREPARING, logicalCameraId = cameraId,
                            referencePhysicalId = null, lenses = emptyList(), failure = null)
                    }
                    mainHandler.postDelayed(calibrationStartRetry, 250L)
                    return
                }
                calibrationStartDeadlineMs = 0L
                refuse(com.librestatic.opencinecam.LensCalibrationFailure.UNSUPPORTED_MODE)
                return
            }
            calibrationStartDeadlineMs = 0L
            val run = CalibrationRun(CalibrationRestore(cameraId, current.selectedMode, current.zoomRatio))
            // A locked lens is set aside: the run measures the logical camera (currentLensLockBinding
            // is null from here until finishCalibration).
            val wasLensLocked = currentLensLockBinding() != null
            calibrationRun = run
            calibrationPending = null
            lensCalibrationState.update {
                it.copy(
                    phase = com.librestatic.opencinecam.LensCalibrationPhase.PREPARING,
                    logicalCameraId = cameraId,
                    referencePhysicalId = null,
                    lenses = lenses.sortedBy { lens -> lens.nominalRatio }.map { lens ->
                        com.librestatic.opencinecam.LensCalibrationLensRow(lens.physicalId, lens.nominalRatio,
                            com.librestatic.opencinecam.LensCalibrationLensStatus.PENDING)
                    },
                    failure = null,
                    illuminantMismatch = false,
                )
            }
            // The run reads the uncorrected signal.
            previewEngine.setLensCorrection(null as com.librestatic.opencinecam.core.model.CalibrationSet?, settings.lensCorrectionAlignment)
            val mode = if (current.selectedMode == CaptureMode.HLG) CaptureMode.HLG else CaptureMode.VIDEO
            val openedBefore = openingCount.get()
            val switched = current.selectedMode != mode
            // The mode changes before the controls lock, since the lock freezes mode changes.
            // A locked session always reopens once, on the logical camera, whatever the mode change does.
            if (switched) selectMode(mode, reopen = !wasLensLocked)
            if (cameraState.value.selectedMode != mode) {
                finishCalibration(run, null)
                return
            }
            val relocked = wasLensLocked
            if (relocked) attachedPreviewSurface?.takeIf { it.isValid }?.let { attachPreview(it, attachedPreviewRotationDegrees) }
            // While locked the state holds the locked lens' range; the run sweeps the logical one.
            val zoomRange = current.descriptor?.effectiveZoomRange?.takeIf { wasLensLocked } ?: (current.zoomMinRatio..current.zoomMaxRatio)
            cameraState.update { it.copy(lensCalibrationRunning = true) }
            run.thread = Thread({
                runCalibration(run, lenses, mode, zoomRange, openedBefore, switched || relocked)
            }, "lens-calibration").also { it.isDaemon = true; it.start() }
        }

        /** Saves the run's result for this build and camera, and applies it when the correction is on. */
        fun saveLensCalibration() {
            val set = calibrationPending ?: return
            if (calibrationRun != null || lensCalibrationState.value.phase != com.librestatic.opencinecam.LensCalibrationPhase.RESULT) return
            calibrationPending = null
            try {
                storageExecutor.execute {
                    val saved = runCatching { com.librestatic.opencinecam.LensCalibrationStore.forContext(this@CaptureService).save(set) }.isSuccess
                    mainHandler.post {
                        if (serviceDestroyed) return@post
                        lensCalibrationCache = null
                        lensCalibrationState.update {
                            if (saved) it.copy(phase = com.librestatic.opencinecam.LensCalibrationPhase.IDLE, referencePhysicalId = null, lenses = emptyList(), failure = null)
                            else it.copy(phase = com.librestatic.opencinecam.LensCalibrationPhase.FAILED, failure = com.librestatic.opencinecam.LensCalibrationFailure.INTERNAL)
                        }
                        refreshSavedCalibration(cameraState.value.descriptor)
                        refreshLensCorrection()
                    }
                }
            } catch (_: RejectedExecutionException) {
                calibrationPending = set
            }
        }

        fun discardLensCalibration() {
            if (calibrationRun != null) return
            calibrationPending = null
            lensCalibrationState.update {
                it.copy(phase = com.librestatic.opencinecam.LensCalibrationPhase.IDLE, referencePhysicalId = null, lenses = emptyList(), failure = null)
            }
        }

        /** Deletes the saved calibration of the selected camera. */
        fun deleteLensCalibration() {
            if (calibrationRun != null) return
            val cameraId = cameraState.value.selectedCameraId ?: return
            try {
                storageExecutor.execute {
                    runCatching {
                        com.librestatic.opencinecam.LensCalibrationStore.forContext(this@CaptureService)
                            .delete(Build.FINGERPRINT, cameraId, com.librestatic.opencinecam.core.model.CalibrationSessionKind.LOGICAL)
                    }
                    mainHandler.post {
                        if (serviceDestroyed) return@post
                        lensCalibrationCache = null
                        refreshSavedCalibration(cameraState.value.descriptor)
                        refreshLensCorrection()
                    }
                }
            } catch (_: RejectedExecutionException) { }
        }
        /** A/V clock drift of the last separate WAV/FLAC take, or null (no such take, or not measurable). Diagnostic only. */
        val separateAudioAvDrift: com.librestatic.opencinecam.media.audio.AvDiagnosticSnapshot? get() = lastSeparateAudioAvDrift

        /** OCC-PLAN-068 U6 evidence for the active take's sidecar. Not a capture command: it never starts, stops or delays a take. */
        fun recordSubjectSyncMarker(report: com.librestatic.opencinecam.SubjectSyncMarkerReport): Boolean =
            !serviceDestroyed && subjectSyncMarkers.offer(report, android.os.SystemClock.elapsedRealtimeNanos())

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
            if (stillOwner.get() != null) return
            cameraState.value = cameraState.value.copy(phase = CameraUiPhase.PREPARING, message = null)
            val descriptors = CaptureStartup.descriptors(previewEngine, targetWidth, targetHeight)
                ?: previewEngine.descriptors(targetWidth, targetHeight)
            val preferred = descriptors.firstOrNull()
            cameraState.value = if (preferred == null) {
                cameraState.value.copy(
                    phase = CameraUiPhase.ERROR,
                    cameras = emptyList(),
                    selectedCameraId = null,
                    errorCode = "no-public-camera",
                    message = getString(R.string.capture_no_public_camera),
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
                    cameraState.value.selectedMode,
                    geometrySeeds.logWidth,
                    geometrySeeds.logHeight,
                    geometrySeeds.logFps,
                ).takeIf { cameraState.value.selectedMode.usesLogGraph }
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
                        CaptureMode.RAW_PHOTO to if (preferred.supportsRaw) ModeGateState.AVAILABLE else ModeGateState.UNSUPPORTED,
                        CaptureMode.LOG to if (preferred.supportsOpenCineLog) ModeGateState.AVAILABLE else ModeGateState.UNSUPPORTED,
                        CaptureMode.HLG to if (preferred.supportsOpenCineLog && preferred.logProfilesFor(CaptureMode.HLG).isNotEmpty()) ModeGateState.AVAILABLE else ModeGateState.UNSUPPORTED,
                        CaptureMode.SLOW_MOTION to if (com.librestatic.opencinecam.supportsSlowMotion(preferred.videoProfiles.map { it.toSpec() })) ModeGateState.AVAILABLE else ModeGateState.UNSUPPORTED,
                    ),
                    errorCode = null,
                    message = null,
                ).let { withRawVideoGate(it, selectedId) }
            }
            if (operatorStartupPending && preferred != null) {
                operatorStartupPending = false
                val saved = getSharedPreferences("operator-session", MODE_PRIVATE).getString("last-mode", null)
                val last = CaptureMode.entries.firstOrNull { it.name == saved }
                val available = cameraState.value.modeGates.filterValues { it == ModeGateState.AVAILABLE }.keys
                selectMode(settings.operation.startupCaptureMode(last, available), reopen = false)
            }
            // A qualification that passed in the onboarding wizard, before there were cameras, opens in RAW video.
            if (rawQualificationSelectPending && preferred != null) {
                rawQualificationSelectPending = false
                if (CameraUiState.isModeSelectable(cameraState.value.modeGates[CaptureMode.RAW_VIDEO])) selectMode(CaptureMode.RAW_VIDEO, reopen = false)
            }
            refreshLensLockState()
            refreshAudioCapabilities()
            syncRawVideoArm()
        }

        /** Fully recovers the retained viewfinder after a capture or permission failure. */
        fun recoverPreview(targetWidth: Int, targetHeight: Int) {
            val token = ++recoveryGeneration
            mainHandler.removeCallbacks(recordingTicker)
            val take = detachTake()
            val encodedFrames = previewEngine.encodedRecordingProgress()?.frameCount
            abortTransferPublication(take.publication)
            foreground.stop()
            previewEngine.stopPreview()
            submitCaptureState(stateCommands.takeEnded() + stateCommands.previewStopped())
            val retirement = previewEngine.recordingOutputRetirement()
            // ADR-0011: a take cut short by the recovery keeps what it recorded, published once retired.
            salvageInterruptedTake(take, retirement, listOf(IncompleteTake.RECORDING_INTERRUPTED), encodedFrames, null)
            retireRecordingRecoveryGuards(retirement)
            releaseFailedTakeStart(retirement)
            prepare(targetWidth, targetHeight)
            if (cameraState.value.phase == CameraUiPhase.ERROR) return
            val surface = attachedPreviewSurface
            if (surface?.isValid != true || !attachPreview(surface, attachedPreviewRotationDegrees)) {
                fail("preview-recovery-surface-unavailable", getString(R.string.capture_preview_surface_unavailable))
                return
            }
            mainHandler.postDelayed({
                if (token == recoveryGeneration && cameraState.value.phase != CameraUiPhase.PREVIEWING) {
                    fail("preview-recovery-timeout", getString(R.string.capture_preview_recovery_timeout))
                }
            }, PREVIEW_RECOVERY_TIMEOUT_MS)
        }

        fun refreshAudioCapabilities() {
            if (serviceDestroyed) return
            val requestedInput = settings.audioInputKey
            try {
                storageExecutor.execute {
                    val capabilities = runCatching { AndroidProfessionalAudioProbe(this@CaptureService).probe(requestedInput) }
                        .getOrElse {
                            val message = getString(R.string.capture_audio_probe_failed, it.message ?: it.javaClass.simpleName)
                            mainHandler.post {
                                if (!serviceDestroyed) cameraState.update { state -> state.copy(message = message) }
                            }
                            return@execute
                        }
                    mainHandler.post {
                        // A probe accepted before unbind can finish after onDestroy removed
                        // queued callbacks and retired the actor. Never revive that service.
                        if (serviceDestroyed) return@post
                        cameraState.value = cameraState.value.copy(audioCapabilities = capabilities)
                        announceAutoAudioInput(capabilities)
                        val repository = SettingsRepositories.get(this@CaptureService)
                        repository.update { it.normalizedFor(capabilities) }
                        localBinder.applySettings(repository.states.value)
                    }
                }
            } catch (_: RejectedExecutionException) {
                mainHandler.post {
                    if (!serviceDestroyed) cameraState.update { it.copy(message = getString(R.string.capture_audio_probe_busy)) }
                }
            }
        }

        fun attachPreview(surface: Surface, displayRotationDegrees: Int): Boolean {
            val descriptor = cameraState.value.descriptor ?: return false
            if (!surface.isValid) return false
            // The qualification step is over the viewfinder: the surface waits until it leaves.
            if (rawQualificationPreviewHeld) {
                parkedPreviewSurface = surface
                attachedPreviewRotationDegrees = displayRotationDegrees
                return true
            }
            attachedPreviewSurface = surface
            attachedPreviewRotationDegrees = displayRotationDegrees
            // A RAW video qualification owns the camera: the viewfinder opens when it ends.
            if (rawQualificationSession != null) return true
            syncRawVideoArm()
            if (cameraState.value.structuralSettingsFrozen) {
                val attached = previewEngine.attachPreviewWhileRecording(surface, displayRotationDegrees)
                if (!attached && rawTake != null) rawTakePreviewStale = true
                return attached
            }
            // A layout/rotation callback can reattach an already present GPU output after
            // onPreviewStarted. Replace only its EGL window, not the configured camera graph:
            // reopening here would briefly revoke a ready capture's admission a second time.
            val current = cameraState.value
            if (current.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) &&
                activePreviewKey == previewKey() && current.gpuViewfinder &&
                previewEngine.attachOperatorToActiveGpuPreview(surface, displayRotationDegrees)) return true
            submitCaptureState(stateCommands.previewOpening(descriptor.cameraId))
            cameraState.update { it.copy(phase = CameraUiPhase.OPENING) }
            previewEngine.startPreview(
                descriptor,
                surface,
                displayRotationDegrees,
                previewListener,
                stillFormat = selectedStillPhotoFormat(),
                stillSize = selectedStillSize(),
                nightExtension = nightExtensionActive(),
                lowLightAutoFps = lowLightFpsActive(cameraState.value, settings),
                fpsLock = fpsLockActive(cameraState.value, settings),
                openCineLog = cameraState.value.selectedMode.usesLogGraph,
                gpuPreview = desiredGpuViewfinder() && !cameraState.value.selectedMode.usesLogGraph,
                gpuPhotoPreview = photoPreviewMode(cameraState.value.selectedMode),
                viewAssist = settings.previewViewAssist(cameraState.value.selectedMode),
                targetFps = cameraState.value.targetFps,
                videoProfile = cameraState.value.activeVideoProfile.takeIf {
                    cameraState.value.selectedMode in CameraUiState.videoProfileModes
                },
                logProfile = cameraState.value.activeLogProfile.takeIf {
                    cameraState.value.selectedMode.usesLogGraph
                },
                lensLock = currentLensLockBinding(),
                rawVideo = rawVideoSessionConfig(),
            )
            return true
        }

        fun detachPreview(surface: Surface? = null) {
            if (surface != null && attachedPreviewSurface !== surface && parkedPreviewSurface !== surface) return
            cancelCountdown()
            cancelRecordingWhiteBalancePreparation()
            stopPreviewAudioMonitor(clearLevels = true)
            attachedPreviewSurface = null
            parkedPreviewSurface = null
            // The capture screen went away under a qualification (the app left): stop it.
            if (rawQualificationSession != null) {
                cancelRawVideoQualification(RawQualificationStopReasons.PREVIEW_LOST)
                return
            }
            syncRawVideoArm()
            val photoPreparing = cameraState.value.phase == CameraUiPhase.CAPTURING && cameraState.value.selectedMode in setOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL)
            val ownedStill = stillOwner.get()
            if (photoPreparing && previewEngine.cancelStill() && ownedStill != null) {
                stillOwner.compareAndSet(ownedStill, null)
                cameraState.update { it.copy(stillCapturePending = stillOwner.get() != null) }
            }
            if (cameraState.value.structuralSettingsFrozen && !photoPreparing) {
                if (!previewEngine.detachPreviewWhileRecording() && rawTake != null) rawTakePreviewStale = true
                return
            }
            // A live subject lease keeps the GPU graph (photo modes included) for the cover window.
            if (subjectSurfaces.current != null && settings.subjectDisplay.wantsCameraPreview &&
                subjectPreviewBlock(cameraState.value.selectedMode) == null &&
                previewEngine.detachOperatorFromActiveGpuPreview()) {
                // A still cancelled above has no owner left, so CAPTURING would stick without this.
                if (photoPreparing && stillOwner.get() == null) {
                    cameraState.update { it.copy(phase = CameraUiPhase.READY, stillCapturePending = false) }
                }
                return
            }
            previewEngine.stopPreview()
            activePreviewKey = null
            submitCaptureState(stateCommands.previewStopped())
            cameraState.value = cameraState.value.copy(phase = if (stillOwner.get() == null) CameraUiPhase.READY else CameraUiPhase.CAPTURING)
        }

        /** Discard an unpublished sequence; once native delivery wins, let its writer finish. */
        fun cancelBurstCapture(): Boolean {
            val owner = stillOwner.get() ?: return false
            if (cameraState.value.selectedMode != CaptureMode.BURST || owner.publishing.get()) return false
            if (!previewEngine.cancelStill() || !stillOwner.compareAndSet(owner, null)) return false
            cameraState.update { it.copy(phase = if (attachedPreviewSurface?.isValid == true) CameraUiPhase.PREVIEWING else CameraUiPhase.READY,
                stillCapturePending = false, burstSaving = false, lastBurstPublication = null,
                message = getString(R.string.burst_capture_cancelled)) }
            applySettings(SettingsRepositories.get(this@CaptureService).states.value)
            return true
        }

        fun cancelBracketCapture(): Boolean {
            val owner = stillOwner.get() ?: return false
            if (cameraState.value.selectedMode != CaptureMode.BRACKET || owner.publishing.get()) return false
            if (!previewEngine.cancelStill() || !stillOwner.compareAndSet(owner, null)) return false
            cameraState.update { it.copy(phase = if (attachedPreviewSurface?.isValid == true) CameraUiPhase.PREVIEWING else CameraUiPhase.READY,
                stillCapturePending = false, bracketSaving = false, lastBracketPublication = null,
                message = getString(R.string.bracket_capture_cancelled)) }
            applySettings(SettingsRepositories.get(this@CaptureService).states.value)
            return true
        }

        fun cancelAccumulationCapture(): Boolean {
            val owner = stillOwner.get() ?: return false
            if (cameraState.value.selectedMode != CaptureMode.LIGHT_TRAIL || owner.publishing.get()) return false
            if (!previewEngine.cancelStill() || !stillOwner.compareAndSet(owner, null)) return false
            cameraState.update { it.copy(phase = if (attachedPreviewSurface?.isValid == true) CameraUiPhase.PREVIEWING else CameraUiPhase.READY,
                stillCapturePending = false, accumulationSaving = false, accumulationFinishing = false, lastAccumulationPublication = null,
                message = getString(R.string.accumulation_capture_cancelled)) }
            applySettings(SettingsRepositories.get(this@CaptureService).states.value)
            return true
        }

        fun finishAccumulationCapture(): Boolean {
            val owner = stillOwner.get() ?: return false
            if (cameraState.value.selectedMode != CaptureMode.LIGHT_TRAIL || owner.publishing.get()) return false
            return previewEngine.finishAccumulation().also { accepted ->
                if (accepted) cameraState.update { it.copy(accumulationFinishing = true) }
            }
        }

        fun selectCamera(cameraId: String) {
            cancelCountdown()
            val current = cameraState.value
            if (current.structuralSettingsFrozen || rawQualificationSession != null) return
            val descriptor = current.cameras.firstOrNull { it.cameraId == cameraId } ?: return
            val videoProfile = normalizedVideoProfile(
                descriptor, geometrySeeds.width(current.selectedMode), geometrySeeds.height(current.selectedMode), geometrySeeds.fps(current.selectedMode),
            )
            val logProfile = normalizedLogProfile(
                descriptor, current.selectedMode, geometrySeeds.logWidth, geometrySeeds.logHeight, geometrySeeds.logFps,
            ).takeIf { current.selectedMode.usesLogGraph }
            val selectedWidth = logProfile?.size?.width ?: videoProfile?.size?.width ?: geometrySeeds.width(current.selectedMode)
            val selectedHeight = logProfile?.size?.height ?: videoProfile?.size?.height ?: geometrySeeds.height(current.selectedMode)
            val previous = current.selectedCameraId ?: return
            if (previous == cameraId) return
            val surface = attachedPreviewSurface
            // The new lens meters its own scene and Night from the quick toggle does not follow it.
            nightTransientEngaged = false
            nightSceneDetector.reset()
            cameraState.value = current.copy(
                nightTransientEngaged = false,
                nightSuggested = false,
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
                message = getString(R.string.capture_camera_selected, cameraId),
                modeGates = current.modeGates + mapOf(
                    CaptureMode.RAW_PHOTO to if (descriptor.supportsRaw) ModeGateState.AVAILABLE else ModeGateState.UNSUPPORTED,
                    CaptureMode.LOG to if (descriptor.supportsOpenCineLog) ModeGateState.AVAILABLE else ModeGateState.UNSUPPORTED,
                    CaptureMode.HLG to if (descriptor.supportsOpenCineLog && descriptor.logProfilesFor(CaptureMode.HLG).isNotEmpty()) ModeGateState.AVAILABLE else ModeGateState.UNSUPPORTED,
                    CaptureMode.SLOW_MOTION to if (com.librestatic.opencinecam.supportsSlowMotion(descriptor.videoProfiles.map { it.toSpec() })) ModeGateState.AVAILABLE else ModeGateState.UNSUPPORTED,
                ),
            ).let { withRawVideoGate(it, cameraId) }
            refreshLensLockState(cameraId)
            syncRawVideoArm()
            if (surface?.isValid != true) return
            pendingSwitchPrevious = previous
            pendingSwitchTarget = cameraId
            val switchToken = ++cameraSwitchGeneration
            previewEngine.startPreview(
                descriptor,
                surface,
                attachedPreviewRotationDegrees,
                previewListener,
                stillFormat = selectedStillPhotoFormat(),
                stillSize = selectedStillSize(),
                nightExtension = nightExtensionActive(),
                lowLightAutoFps = lowLightFpsActive(cameraState.value, settings),
                fpsLock = fpsLockActive(cameraState.value, settings),
                openCineLog = cameraState.value.selectedMode.usesLogGraph,
                gpuPreview = desiredGpuViewfinder() && !cameraState.value.selectedMode.usesLogGraph,
                gpuPhotoPreview = photoPreviewMode(cameraState.value.selectedMode),
                viewAssist = settings.previewViewAssist(cameraState.value.selectedMode),
                targetFps = cameraState.value.targetFps,
                videoProfile = cameraState.value.activeVideoProfile.takeIf {
                    cameraState.value.selectedMode in CameraUiState.videoProfileModes
                },
                logProfile = cameraState.value.activeLogProfile.takeIf {
                    cameraState.value.selectedMode.usesLogGraph
                },
                lensLock = currentLensLockBinding(),
                rawVideo = rawVideoSessionConfig(),
            )
            mainHandler.postDelayed({
                if (switchToken != cameraSwitchGeneration || pendingSwitchTarget != cameraId) return@postDelayed
                val fallbackId = pendingSwitchPrevious ?: return@postDelayed
                val fallback = cameraState.value.cameras.firstOrNull { it.cameraId == fallbackId } ?: return@postDelayed
                pendingSwitchTarget = null
                pendingSwitchPrevious = null
                cameraSwitchGeneration++
                pendingStatusMessage = getString(R.string.camera_switch_timeout, cameraId, fallbackId)
                cameraState.value = withRawVideoGate(cameraState.value.copy(selectedCameraId = fallbackId, phase = CameraUiPhase.OPENING), fallbackId)
                refreshLensLockState(fallbackId)
                syncRawVideoArm()
                attachedPreviewSurface?.takeIf { it.isValid }?.let {
                    previewEngine.startPreview(
                        fallback,
                        it,
                        attachedPreviewRotationDegrees,
                        previewListener,
                        stillFormat = selectedStillPhotoFormat(),
                        stillSize = selectedStillSize(),
                        nightExtension = nightExtensionActive(),
                        lowLightAutoFps = lowLightFpsActive(cameraState.value, settings),
                        fpsLock = fpsLockActive(cameraState.value, settings),
                        openCineLog = cameraState.value.selectedMode.usesLogGraph,
                        gpuPreview = desiredGpuViewfinder() && !cameraState.value.selectedMode.usesLogGraph,
                        gpuPhotoPreview = photoPreviewMode(cameraState.value.selectedMode),
                        viewAssist = settings.previewViewAssist(cameraState.value.selectedMode),
                        targetFps = cameraState.value.targetFps,
                        videoProfile = cameraState.value.activeVideoProfile.takeIf {
                            cameraState.value.selectedMode in CameraUiState.videoProfileModes
                        },
                        logProfile = cameraState.value.activeLogProfile.takeIf {
                            cameraState.value.selectedMode.usesLogGraph
                        },
                        lensLock = currentLensLockBinding(),
                        rawVideo = rawVideoSessionConfig(),
                    )
                }
            }, CAMERA_SWITCH_TIMEOUT_MS)
        }

        fun selectMode(mode: CaptureMode, reopen: Boolean = true) {
            cancelCountdown()
            if (cameraState.value.structuralSettingsFrozen || rawQualificationSession != null) return
            val current = cameraState.value
            if (!CameraUiState.isModeSelectable(current.modeGates[mode])) {
                cameraState.value = current.copy(message = getString(R.string.gate_requires_preflight, modeDisplayName(mode)))
                return
            }
            val descriptor = current.descriptor
            val selectedLogProfile = descriptor?.let {
                normalizedLogProfile(it, mode, geometrySeeds.logWidth, geometrySeeds.logHeight, geometrySeeds.logFps)
            }.takeIf { mode.usesLogGraph }
            val changedSignalPath =
                desiredGpuViewfinder(current.selectedMode) != desiredGpuViewfinder(mode) ||
                selectedStillPhotoFormat(current.selectedMode) != selectedStillPhotoFormat(mode) ||
                nightExtensionActive(current.selectedMode) != nightExtensionActive(mode) ||
                (current.selectedMode.usesLogGraph) != (mode.usesLogGraph) ||
                    // The RAW10 stream is part of the session graph.
                    (current.selectedMode == CaptureMode.RAW_VIDEO) != (mode == CaptureMode.RAW_VIDEO) ||
                    (settings.subjectDisplay.wantsCameraPreview && (current.selectedMode == CaptureMode.VIDEO) != (mode == CaptureMode.VIDEO)) ||
                    (current.selectedMode in CameraUiState.videoProfileModes) != (mode in CameraUiState.videoProfileModes) ||
                    // HLG takes only the HLG10 profiles, so LOG's ISP-derived ones need a new session.
                    (current.selectedMode.usesLogGraph && mode.usesLogGraph && current.activeLogProfile != selectedLogProfile)
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
            previewEngine.setTorch(settings.flashEnabled, settings.torchStrengthLevel)
            // Night from the quick toggle is for the photo screen it was switched on in.
            nightTransientEngaged = false
            cameraState.value = cameraState.value.copy(
                nightTransientEngaged = false,
                selectedMode = mode,
                recordingProjectRate = null,
                recordingPauseStatus = null,
                recordingAvTiming = null,
                recordingPausePending = false,
                targetFps = targetFps,
                targetVideoWidth = selectedWidth,
                targetVideoHeight = selectedHeight,
                effectiveFps = null,
                torchReported = null,
                photoFlashReport = null,
                torchStrengthReported = null,
                message = null,
            ).let { withRawVideoGate(it, it.selectedCameraId) }
            if (mode == CaptureMode.RAW_VIDEO && current.selectedMode != CaptureMode.RAW_VIDEO) rawModeFallback = current.selectedMode
            getSharedPreferences("operator-session", MODE_PRIVATE).edit().putString("last-mode", mode.name).apply()
            // LOG and HLG share the session; HLG always monitors through the Rec.709 view, LOG as set.
            if (mode.usesLogGraph) previewEngine.setOpenCineLogViewAssist(settings.previewViewAssist(mode))
            if (changedSignalPath && reopen) {
                val descriptor = cameraState.value.descriptor
                val surface = attachedPreviewSurface
                if (descriptor != null && surface?.isValid == true) {
                    cameraState.value = cameraState.value.copy(phase = CameraUiPhase.OPENING)
                    val start = Runnable {
                    // A later mode change supersedes this deferred start.
                    if (cameraState.value.selectedMode != mode || !surface.isValid) return@Runnable
                    previewEngine.startPreview(
                        descriptor,
                        surface,
                        attachedPreviewRotationDegrees,
                        previewListener,
                        stillFormat = selectedStillPhotoFormat(),
                        stillSize = selectedStillSize(),
                        nightExtension = nightExtensionActive(),
                        lowLightAutoFps = lowLightFpsActive(cameraState.value, settings),
                        fpsLock = fpsLockActive(cameraState.value, settings),
                        openCineLog = mode.usesLogGraph,
                        gpuPreview = desiredGpuViewfinder() && !mode.usesLogGraph,
                        gpuPhotoPreview = photoPreviewMode(mode),
                        viewAssist = settings.previewViewAssist(mode),
                        targetFps = cameraState.value.targetFps,
                        videoProfile = cameraState.value.activeVideoProfile.takeIf {
                            mode in CameraUiState.videoProfileModes
                        },
                        logProfile = cameraState.value.activeLogProfile.takeIf { mode.usesLogGraph },
                        lensLock = currentLensLockBinding(),
                        rawVideo = rawVideoSessionConfig(),
                    )
                    }
                    // As in selectTargetFps: let the panel apply its high refresh-rate vote before a
                    // constrained high-speed stream starts.
                    if (cameraState.value.activeVideoProfile?.constrainedHighSpeed == true && mode in CameraUiState.videoProfileModes) {
                        mainHandler.postDelayed(start, 350L)
                    } else start.run()
                }
            }
            syncRawVideoArm()
        }

        /**
         * OCC-PLAN-071: persists the RAW video configuration [key] (one of [CameraUiState.rawVideoOptions],
         * or empty for the highest data rate). Refused during a take; applySettings reopens the session.
         */
        fun selectRawVideoConfig(key: String): Boolean {
            if (serviceDestroyed || !RawVideoConfigKeys.isValid(key) || cameraState.value.structuralSettingsFrozen) return false
            if (key.isNotEmpty() && cameraState.value.rawVideoOptions.none { it.key == key }) return false
            SettingsRepositories.get(this@CaptureService).update { it.copy(rawVideoConfig = key) }
            return true
        }

        /** Rereads the RAW video qualification records (after a qualification run) and updates the gate. */
        fun refreshRawVideoQualifications() {
            if (serviceDestroyed) return
            try {
                storageExecutor.execute {
                    val set = runCatching { rawQualificationStore.load() }.getOrElse { RawVideoQualificationSet() }
                    mainHandler.post {
                        if (serviceDestroyed) return@post
                        rawQualifications = set
                        val before = cameraState.value.rawVideoSelectedKey
                        cameraState.value = withRawVideoGate(cameraState.value, cameraState.value.selectedCameraId)
                        if (cameraState.value.selectedMode == CaptureMode.RAW_VIDEO && cameraState.value.rawVideoSelectedKey != before &&
                            !cameraState.value.structuralSettingsFrozen) {
                            attachedPreviewSurface?.takeIf { it.isValid }?.let { attachPreview(it, attachedPreviewRotationDegrees) }
                        }
                        syncRawVideoArm()
                    }
                }
            } catch (_: RejectedExecutionException) {
                // The service is closing.
            }
        }

        /** OCC-PLAN-071: the camera a qualification would run on, its gate, and the duration and space for the free space now. Main thread. */
        internal fun rawVideoQualificationEstimate(): RawVideoQualificationInfo = rawQualificationInfo()

        /**
         * Starts the RAW video qualification of [rawVideoQualificationEstimate]'s camera; null when it
         * started, otherwise the refusal ([RawQualificationRefusals]), also
         * shown on [CameraUiState.rawVideoQualification]. Works with or without a viewfinder: the
         * camera the engine holds is released first and reopened at the end if a surface is attached.
         * A camera qualified before is measured again (Settings); [selectOnPass] false keeps the mode
         * as it is when something passes.
         */
        fun startRawVideoQualification(selectOnPass: Boolean = true): String? {
            if (serviceDestroyed) return RawQualificationRefusals.BUSY
            val current = cameraState.value
            val conditions = readRawQualificationConditions(this@CaptureService)
            val info = rawQualificationInfo()
            val sensor = info.cameraId?.let(::rawQualificationSensorSize)
            val refusal = rawQualificationStartRefusal(
                running = rawQualificationSession != null,
                phase = current.phase,
                structuralSettingsFrozen = current.structuralSettingsFrozen,
                takeActive = rawTake != null || stillOwner.get() != null || calibrationRun != null || captureCountdown.active != null,
                transferring = transferCapture.get() != null || recordingWbPreparing,
                blockers = conditions.blockers,
                hasQualifiableCamera = info.cameraId != null && sensor != null && rawQualificationGateQualifiable(info.gate),
            )
            if (refusal != null) {
                if (refusal != RawQualificationRefusals.RUNNING) cameraState.update {
                    it.copy(rawVideoQualification = RawVideoQualificationUi(
                        outcome = RawQualificationOutcomeKind.FAILED_TO_RUN, reason = refusal))
                }
                return refusal
            }
            val cameraId = checkNotNull(info.cameraId)
            val size = checkNotNull(sensor)
            val estimateMs = info.space?.estimate?.durationMs ?: 0L
            val session = RawQualificationSession(cameraId, size.first, size.second, estimateMs, selectOnPass)
            session.qualifier = RawVideoQualifier(
                this@CaptureService,
                cameraIds = listOf(cameraId),
                // PLAN-072 Delivery 3: with the next RAW take going to a drive, the drive is what gets qualified.
                driveTree = (nextTakeDestination.destination as? RecordingDestination.UsbFolder)?.treeUri,
                // The model the screen's estimate was planned with, so the run's first figure matches it.
                storageModel = info.storage,
                listener = object : RawVideoQualifier.Listener {
                    override fun onProgress(progress: RawQualificationProgress) =
                        postRawQualificationProgress(session, progress)

                    override fun onResult(result: org.json.JSONObject) = recordRawQualificationResult(session, result)
                },
            )
            session.items = rawQualificationPlanItems(info.space)
            cancelCountdown()
            rawQualificationSession = session
            rawQualificationSelectPending = false
            // The RAW ring is freed (on rawControl) before the qualifier allocates its own.
            syncRawVideoArm()
            // Release the camera, as detachPreview does; nothing reopens it until the run ends.
            previewEngine.stopPreview()
            activePreviewKey = null
            submitCaptureState(stateCommands.previewStopped())
            cameraState.update {
                it.copy(
                    phase = if (it.cameras.isEmpty()) it.phase else CameraUiPhase.READY,
                    rawVideoQualification = RawVideoQualificationUi(remainingMs = estimateMs, items = session.items),
                    message = null,
                )
            }
            // The battery running low stops the run; the qualifier handles heat itself.
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: Intent?) {
                    rawQualificationRunAbort(batteryPercentOf(intent))?.let { cancelRawVideoQualification(it) }
                }
            }
            session.batteryReceiver = receiver
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(this@CaptureService, receiver,
                    android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            }.onFailure { session.batteryReceiver = null }
            Thread({
                // The engine closes the camera on its own executor, and the ring is disarmed on rawControl:
                // both are done before the qualifier opens the camera and allocates its ring.
                var waitedMs = 0L
                while (waitedMs < RAW_QUALIFICATION_RELEASE_WAIT_MS && !session.qualifier.isCancelled) {
                    Thread.sleep(100L)
                    waitedMs += 100L
                }
                runCatching { rawControl.submit {}.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                val outcome = session.qualifier.run(rawQualificationStore)
                val records = runCatching { rawQualificationStore.load() }.getOrElse { RawVideoQualificationSet() }
                mainHandler.post { finishRawQualification(session, outcome, records) }
            }, "occ-raw-qualification").start()
            return null
        }

        /** Stops the running qualification; [reason] says why ([RawQualificationStopReasons]). Any thread. */
        fun cancelRawVideoQualification(reason: String = RawQualificationStopReasons.USER) {
            val session = rawQualificationSession ?: return
            if (session.stopReason == null) session.stopReason = reason
            session.qualifier.cancel()
        }

        /**
         * [hold] true while the qualification step is on screen: it covers the viewfinder, so the camera closes
         * instead of heating the phone before a run and under its result. The surface is parked and the preview
         * reopens, in whatever mode is selected by then, when the step leaves. Refused during a take or a still.
         */
        fun holdPreviewForRawQualification(hold: Boolean) {
            if (hold == rawQualificationPreviewHeld) return
            if (!hold) {
                rawQualificationPreviewHeld = false
                val surface = parkedPreviewSurface
                parkedPreviewSurface = null
                // A run still going keeps the camera; attachPreview only records the surface for its end.
                if (surface?.isValid == true && !serviceDestroyed) attachPreview(surface, attachedPreviewRotationDegrees)
                return
            }
            val current = cameraState.value
            if (serviceDestroyed || rawTake != null || stillOwner.get() != null || current.structuralSettingsFrozen ||
                current.phase == CameraUiPhase.RECORDING || current.phase == CameraUiPhase.CAPTURING) return
            rawQualificationPreviewHeld = true
            parkedPreviewSurface = attachedPreviewSurface
            attachedPreviewSurface = null
            cancelCountdown()
            stopPreviewAudioMonitor(clearLevels = true)
            // A run already has the camera, and its end finds no surface to reopen for.
            if (rawQualificationSession != null) return
            syncRawVideoArm()
            previewEngine.stopPreview()
            activePreviewKey = null
            submitCaptureState(stateCommands.previewStopped())
            cameraState.update { it.copy(phase = CameraUiPhase.READY) }
        }

        /** Clears a finished qualification's result from the state; a running one stays. */
        fun dismissRawVideoQualification() {
            if (rawQualificationSession != null) return
            cameraState.update { it.copy(rawVideoQualification = null) }
        }

        fun selectTargetFps(fps: Int) {
            cancelCountdown()
            val current = cameraState.value
            if (current.structuralSettingsFrozen || rawQualificationSession != null) return
            if (fps == current.targetFps) return
            val descriptor = current.descriptor ?: return
            var effectiveFps = fps
            val transient: Boolean
            when (current.selectedMode) {
                CaptureMode.LOG, CaptureMode.HLG -> {
                    val snap = VideoGeometryPolicy.snapLog(
                        descriptor.logProfilesFor(current.selectedMode).map { it.toSpec() },
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
                    ?: getString(R.string.capture_target_fps, effectiveFps),
            )
            val surface = attachedPreviewSurface?.takeIf { it.isValid } ?: return
            val reopen = {
                // The high-speed reopen is deferred; a take admitted meanwhile owns the graph.
                if (cameraState.value.targetFps == effectiveFps && surface.isValid && !cameraState.value.structuralSettingsFrozen) {
                    previewEngine.startPreview(
                        descriptor,
                        surface,
                        attachedPreviewRotationDegrees,
                        previewListener,
                        stillFormat = selectedStillPhotoFormat(),
                        stillSize = selectedStillSize(),
                        nightExtension = nightExtensionActive(),
                        lowLightAutoFps = lowLightFpsActive(cameraState.value, settings),
                        fpsLock = fpsLockActive(cameraState.value, settings),
                        openCineLog = current.selectedMode.usesLogGraph,
                        gpuPreview = desiredGpuViewfinder(current.selectedMode) && !current.selectedMode.usesLogGraph,
                        gpuPhotoPreview = photoPreviewMode(current.selectedMode),
                        viewAssist = settings.previewViewAssist(current.selectedMode),
                        targetFps = effectiveFps,
                        videoProfile = cameraState.value.activeVideoProfile.takeIf {
                            current.selectedMode in CameraUiState.videoProfileModes
                        },
                        logProfile = cameraState.value.activeLogProfile.takeIf {
                            current.selectedMode.usesLogGraph
                        },
                        lensLock = currentLensLockBinding(),
                        rawVideo = rawVideoSessionConfig(),
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

        fun selectPhotoResolution(width: Int, height: Int) {
            val current = cameraState.value
            if (rejectLockedControl() || current.structuralSettingsFrozen || current.phase == CameraUiPhase.CAPTURING || rawQualificationSession != null) return
            if (current.selectedMode !in CameraUiState.photoResolutionModes || current.selectedMode == CaptureMode.RAW_PHOTO) return
            val size = width to height
            if (size !in current.availableStillSizes || size == current.activeStillSize) return
            cameraState.update { it.copy(targetStillSize = size) }
            attachedPreviewSurface?.takeIf { it.isValid }?.let { attachPreview(it, attachedPreviewRotationDegrees) }
        }

        /** [requestedFps] is the rate to keep at the new size when it offers it; slow motion passes a high-speed one. */
        fun selectVideoResolution(width: Int, height: Int, requestedFps: Int = cameraState.value.targetFps) {
            val current = cameraState.value
            if (current.structuralSettingsFrozen || rawQualificationSession != null || current.selectedMode !in CameraUiState.resolutionProfileModes) return
            val descriptor = current.descriptor ?: return
            val selectedFps: Int
            val selectedHighSpeed: Boolean
            val sourceLabel: String
            var snappedFps = false
            if (current.selectedMode.usesLogGraph) {
                val choices = descriptor.logProfilesFor(current.selectedMode).filter { it.size.width == width && it.size.height == height }
                if (choices.isEmpty()) return
                val snap = VideoGeometryPolicy.snapLog(
                    choices.map { it.toSpec() },
                    width,
                    height,
                    requestedFps,
                    Camera2PreviewEngine.DEFAULT_TARGET_FPS,
                )
                val selected = snap.profile ?: return
                snappedFps = snap.snapped
                selectedFps = selected.fps
                selectedHighSpeed = choices.first { it.size.width == width && it.size.height == height && it.fps == selected.fps }.constrainedHighSpeed
                sourceLabel = if (current.selectedMode == CaptureMode.HLG) "HLG · BT.2020 10-bit"
                else if (selected.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                    "OCLog HFR · " + getString(R.string.capture_source_isp_derived)
                } else "OCLog · HLG 10-bit"
            } else {
                val choices = descriptor.videoProfiles.filter { it.size.width == width && it.size.height == height }
                if (choices.isEmpty()) return
                val selected = VideoGeometryPolicy.snapVideo(
                    choices.map { it.toSpec() },
                    width,
                    height,
                    requestedFps,
                    Camera2PreviewEngine.DEFAULT_TARGET_FPS,
                ) ?: return
                selectedFps = selected.fps
                selectedHighSpeed = choices.first { it.size.width == width && it.size.height == height && it.fps == selected.fps }.constrainedHighSpeed
                sourceLabel = getString(if (current.selectedMode == CaptureMode.TIME_LAPSE) R.string.time_lapse_mode else R.string.video_mode)
            }
            cameraState.value = current.copy(
                phase = if (attachedPreviewSurface?.isValid == true) CameraUiPhase.OPENING else current.phase,
                targetVideoWidth = width,
                targetVideoHeight = height,
                targetFps = selectedFps,
                effectiveFps = null,
                messageTransient = snappedFps || selectedFps != current.targetFps,
                message = fpsUnavailableNotice(snappedFps || selectedFps != current.targetFps, current.selectedMode, current.targetFps, selectedFps)
                    ?: "$sourceLabel ${width}×$height · $selectedFps fps${if (selectedHighSpeed) " · " + getString(R.string.capture_high_speed) else ""}",
            )
            if (current.selectedMode.usesLogGraph) {
                attachedPreviewSurface?.takeIf { it.isValid }?.let { surface ->
                    previewEngine.startPreview(
                        descriptor,
                        surface,
                        attachedPreviewRotationDegrees,
                        previewListener,
                        stillFormat = selectedStillPhotoFormat(),
                        stillSize = selectedStillSize(),
                        nightExtension = nightExtensionActive(),
                        lowLightAutoFps = lowLightFpsActive(cameraState.value, settings),
                        fpsLock = fpsLockActive(cameraState.value, settings),
                        openCineLog = true,
                        viewAssist = settings.previewViewAssist(current.selectedMode),
                        targetFps = selectedFps,
                        logProfile = cameraState.value.activeLogProfile,
                        lensLock = currentLensLockBinding(),
                    )
                }
            }
            // Video waits for CameraScreen to resize its direct SurfaceView before reopening.
            // LOG renders through EGL into a layout-sized monitor Surface, so its camera-input
            // SurfaceTexture can be reopened immediately at the selected profile dimensions.
        }

        val subjectPreview: SubjectPreviewPort get() = subjectPreviewPort

        internal fun applySettings(requested: CameraSettings) {
            val previous = settings
            val wasLocked = cameraState.value.captureControlsLocked
            val nightBefore = nightExtensionActive()
            if (previous.subjectDisplay.selfTimerSeconds != requested.subjectDisplay.selfTimerSeconds) cancelCountdown()
            settings = if (cameraState.value.phase == CameraUiPhase.CAPTURING && cameraState.value.selectedMode in setOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL)) {
                // A metered still uses one immutable exposure/illumination intent through JPEG completion.
                // Monitoring/navigation remain live; camera preferences are applied after publication.
                settings.withLivePreferencesFrom(requested).copy(exposure = previous.exposure,
                    whiteBalance = previous.whiteBalance, flashEnabled = previous.flashEnabled,
                    torchStrengthLevel = previous.torchStrengthLevel)
            } else if (cameraState.value.structuralSettingsFrozen || (cameraState.value.phase == CameraUiPhase.CAPTURING && requested.operation.lockDuringTake)) {
                settings.withLivePreferencesFrom(requested)
            } else requested
            cameraState.update { it.copy(
                effectiveSettings = settings,
                settingsPending = settings != requested || pendingPreset != null,
                pendingPresetName = pendingPreset?.name,
            ) }
            audioListeningController?.configure(settings.audioListening, settings.audioListeningOutputDeviceId)
            if (previous.lensCorrectionVideo != settings.lensCorrectionVideo ||
                previous.lensCorrectionPhotos != settings.lensCorrectionPhotos ||
                previous.lensCorrectionAlignment != settings.lensCorrectionAlignment) refreshLensCorrection()
            if (!wasLocked && cameraState.value.captureControlsLocked) previewEngine.cancelFocusPull()
            // The lens lock is frozen with the structural settings, so it changes only between takes.
            val lensLockChanged = previous.lensLock[cameraState.value.selectedCameraId] != settings.lensLock[cameraState.value.selectedCameraId]
            if (previous.lensLock != settings.lensLock) refreshLensLockState()
            geometrySeeds = GeometrySeeds(
                videoWidth = settings.videoWidth,
                videoHeight = settings.videoHeight,
                videoFps = settings.videoFps,
                logWidth = settings.logWidth,
                logHeight = settings.logHeight,
                logFps = settings.logFps,
                timelapseWidth = settings.timelapseWidth,
                timelapseHeight = settings.timelapseHeight,
            )
            if (previous.exposure != settings.exposure || previous.whiteBalance != settings.whiteBalance || previous.imageProcessing != settings.imageProcessing) applyProfessionalSettings()
            if (previous.flashEnabled != settings.flashEnabled || previous.torchStrengthLevel != settings.torchStrengthLevel) {
                previewEngine.setTorch(settings.flashEnabled, settings.torchStrengthLevel)
            }
            previewEngine.setMonitoringOptions(settings.monitoring)
            previewEngine.setSmoothLensSwitch(settings.smoothLensSwitch)
            previewEngine.setFocusPeakingEnabled(settings.peakingEnabled)
            previewEngine.setSubjectFramingEnabled(settings.subjectDisplay.outOfFrameWarning)
            if (previous.logViewAssistEnabled != settings.logViewAssistEnabled) previewEngine.setOpenCineLogViewAssist(settings.previewViewAssist(cameraState.value.selectedMode))
            if (previous.logGreyReference != settings.logGreyReference) previewEngine.setOpenCineLogGreyReference(settings.logGreyReference)
            if (previous.anamorphicSqueeze != settings.anamorphicSqueeze) previewEngine.setOpenCineLogSqueezeFactor(settings.anamorphicSqueeze.factor)
            timecodeTracker.configure(
                TimecodeRate(settings.timecodeNominalFps, settings.timecodeDropFrame),
                settings.timecodeMode,
                SmpteTimecode(settings.timecodeStartHours, settings.timecodeStartMinutes, settings.timecodeStartSeconds, settings.timecodeStartFrames, settings.timecodeDropFrame),
                settings.timecodeEnabled, settings.timecodeRememberPosition, settings.timecodeResetRevision,
            )
            if (timecodeTracker.continuationStorageFailed) cameraState.value = cameraState.value.copy(
                message = getString(R.string.timecode_continuity_failed), messageTransient = true)
            updateSubjectTarget()
            // FPS LOCK follows the setting on the live graph, even mid-take: no session restart.
            if (previous.fpsLock != settings.fpsLock) previewEngine.setFpsLock(fpsLockActive(cameraState.value, settings))
            if (!cameraState.value.structuralSettingsFrozen) {
                if (cameraState.value.selectedMode == CaptureMode.TIME_LAPSE &&
                    (previous.timelapseWidth != settings.timelapseWidth || previous.timelapseHeight != settings.timelapseHeight)) {
                    selectMode(CaptureMode.TIME_LAPSE, reopen = false)
                    attachedPreviewSurface?.takeIf { it.isValid }?.let { attachPreview(it, attachedPreviewRotationDegrees) }
                }
                pendingPreset?.let { preset ->
                    pendingPreset = null
                    applyPresetCapture(preset)
                    cameraState.value = cameraState.value.copy(settingsPending = settings != requested)
                }
                // A take may finish after both windows disappeared. The last lease was released
                // during REC, so retire its now-idle graph here rather than keeping a hidden camera.
                if (attachedPreviewSurface == null && subjectSurfaces.current == null &&
                    cameraState.value.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED)) detachPreview()
                if (settings.nightPhotoMode != com.librestatic.opencinecam.camera.NightPhotoMode.AUTO && nightTransientEngaged) clearNightTransient()
                if ((lensLockChanged && calibrationRun == null) || cameraState.value.gpuViewfinder != desiredGpuViewfinder() ||
                    (cameraState.value.selectedMode == CaptureMode.PHOTO &&
                        (previous.photoFormat != settings.photoFormat || nightBefore != nightExtensionActive())) ||
                    (cameraState.value.selectedMode == CaptureMode.VIDEO && previous.lowLightAutoFps != settings.lowLightAutoFps)) {
                    attachedPreviewSurface?.takeIf { it.isValid }?.let { attachPreview(it, attachedPreviewRotationDegrees) }
                }
                // OCC-PLAN-071: a new RAW configuration changes the RAW10 stream, so the session reopens
                // when it resolves to another configuration; the ring is re-planned for its frame size.
                // Lossless or near-lossless block-pack: the ring re-arms with the other encoder (same configuration).
                if (previous.rawVideoNearLossless != settings.rawVideoNearLossless) syncRawVideoArm()
                if (previous.rawVideoConfig != settings.rawVideoConfig) {
                    val before = cameraState.value.rawVideoSelectedKey
                    cameraState.value = withRawVideoGate(cameraState.value, cameraState.value.selectedCameraId)
                    if (cameraState.value.selectedMode == CaptureMode.RAW_VIDEO && cameraState.value.rawVideoSelectedKey != before) {
                        attachedPreviewSurface?.takeIf { it.isValid }?.let { attachPreview(it, attachedPreviewRotationDegrees) }
                    }
                    syncRawVideoArm()
                }
                val onlyAudioDisplayChanged = previous.copy(audioListening = settings.audioListening,
                    audioListeningOutputDeviceId = settings.audioListeningOutputDeviceId,
                    audioMeter = settings.audioMeter, productionSlate = settings.productionSlate,
                    gallery = settings.gallery, mediaSharing = settings.mediaSharing) == settings
                if (previous.audioInputKey != settings.audioInputKey && !takeInProgress()) refreshAudioCapabilities()
                if (!onlyAudioDisplayChanged || previewAudioMonitor == null) startPreviewAudioMonitorIfEligible()
            }
        }

        fun applyPreset(preset: CameraPreset) {
            if (rejectLockedControl()) return
            cancelCountdown()
            val repository = SettingsRepositories.get(this@CaptureService)
            repository.set(CameraPresetCodec.mergeLocal(preset.settings, repository.states.value))
            pendingPreset = preset
            applySettings(repository.states.value)
        }

        private fun applyPresetCapture(preset: CameraPreset) {
            pendingPresetFocus = preset
            val target = preset.mode.takeIf { cameraState.value.modeGates[it] == ModeGateState.AVAILABLE }
                ?: cameraState.value.selectedMode
            selectMode(target, reopen = false)
            // selectMode resolves the preset geometry against this lens; re-open once so same-mode
            // FPS changes also reach Camera2. No physical camera ID or saved focus mark is imported.
            attachedPreviewSurface?.takeIf { it.isValid }?.let { attachPreview(it, attachedPreviewRotationDegrees) }
            cameraState.value = cameraState.value.copy(pendingPresetName = null)
        }

        private fun rejectLockedControl(): Boolean {
            if (!cameraState.value.captureControlsLocked) return false
            cameraState.value = cameraState.value.copy(message = getString(R.string.operator_locked))
            return true
        }

        /** Capture/preset/display actions retain their UI authorization and review flows. */
        fun performOperatorAction(action: OperatorAction): Boolean {
            val current = cameraState.value
            if (!operatorActionAvailable(action, current)) return false
            val repository = SettingsRepositories.get(this@CaptureService)
            when (action) {
                OperatorAction.TORCH -> repository.update { it.copy(flashEnabled = !it.flashEnabled) }
                OperatorAction.TORCH_LEVEL -> repository.update {
                    val caps = requireNotNull(current.descriptor).torchCapabilities
                    val level = (it.torchStrengthLevel ?: caps.defaultLevel).coerceIn(1, caps.maxLevel)
                    it.copy(torchStrengthLevel = if (level >= caps.maxLevel) 1 else level + 1)
                }
                OperatorAction.PEAKING -> repository.update { it.copy(peakingEnabled = !it.peakingEnabled) }
                OperatorAction.ZEBRA -> repository.update { it.copy(zebraEnabled = !it.zebraEnabled) }
                OperatorAction.HISTOGRAM -> repository.update { it.copy(histogramEnabled = !it.histogramEnabled) }
                OperatorAction.WAVEFORM -> repository.update { it.copy(monitoring = it.monitoring.copy(waveformEnabled = !it.monitoring.waveformEnabled)) }
                OperatorAction.VECTORSCOPE -> repository.update { it.copy(monitoring = it.monitoring.copy(vectorscopeEnabled = !it.monitoring.vectorscopeEnabled)) }
                OperatorAction.FALSE_COLOR -> repository.update { it.copy(monitoring = it.monitoring.copy(falseColorEnabled = !it.monitoring.falseColorEnabled)) }
                OperatorAction.VIEW_ASSIST -> repository.update { it.copy(logViewAssistEnabled = !it.logViewAssistEnabled) }
                OperatorAction.CONTROL_LOCK -> repository.update { it.copy(operation = it.operation.copy(lockDuringTake = !it.operation.lockDuringTake)) }
                OperatorAction.AUTO_FOCUS -> setManualFocus(null)
                OperatorAction.FOCUS_A, OperatorAction.FOCUS_B -> {
                    val label = if (action == OperatorAction.FOCUS_A) "A" else "B"
                    return startFocusPull(current.focusMarks.getValue(label), settings.focusPullDurationMs, settings.focusPullEasing)
                }
                else -> return false
            }
            applySettings(repository.states.value)
            return true
        }

        fun resetAudioClip() {
            cameraState.value = cameraState.value.copy(audioClipLatched = false)
        }

        fun setManualExposure(iso: Int?, exposureTimeNs: Long?) {
            if (rejectLockedControl()) return
            val current = cameraState.value
            val next = if (iso == null && exposureTimeNs == null) settings.exposure.copy(mode = ExposureMode.AUTO) else settings.exposure.copy(
                mode = ExposureMode.MANUAL,
                iso = iso ?: current.sensitivityIso ?: settings.exposure.iso,
                timeNs = exposureTimeNs ?: current.exposureTimeNs ?: settings.exposure.timeNs,
                shutterUnit = ShutterUnit.TIME,
            )
            val repository = SettingsRepositories.get(this@CaptureService)
            repository.update { it.copy(exposure = next) }
            applySettings(repository.states.value)
        }

        fun setExposureCompensation(index: Int) {
            if (rejectLockedControl()) return
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
            val repository = SettingsRepositories.get(this@CaptureService)
            repository.update { it.copy(exposure = it.exposure.copy(mode = ExposureMode.AUTO)) }
            applySettings(repository.states.value)
            previewEngine.setExposureCompensation(clamped)
        }

        fun setManualFocus(focusDiopters: Float?) {
            if (rejectLockedControl()) return
            val current = cameraState.value
            cameraState.value = current.copy(
                requestedFocusDiopters = focusDiopters,
                tapFocusState = TapFocusState.IDLE,
                focusPullActive = false,
                focusPullTargetDiopters = null,
            )
            previewEngine.setManualControls(current.requestedIso, current.requestedExposureTimeNs, focusDiopters)
        }

        fun setFocusMark(label: String, diopters: Float): Boolean {
            if (rejectLockedControl()) return false
            val ok = previewEngine.setFocusMark(label, diopters)
            if (ok) cameraState.value = cameraState.value.copy(focusMarks = previewEngine.getFocusMarks())
            return ok
        }

        fun clearFocusMark(label: String): Boolean {
            if (rejectLockedControl()) return false
            val ok = previewEngine.clearFocusMark(label)
            if (ok) cameraState.value = cameraState.value.copy(focusMarks = previewEngine.getFocusMarks())
            return ok
        }

        fun startFocusPull(toDiopters: Float, durationMs: Long, easing: FocusPullEasing): Boolean {
            if (rejectLockedControl()) return false
            return previewEngine.startFocusPull(toDiopters, durationMs, easing)
        }

        fun cancelFocusPull() {
            previewEngine.cancelFocusPull()
        }

        /**
         * Night photo from the capture screen's quick toggle, for now only (AUTO mode). Restarts the
         * photo graph when it changes what the session is; a no-op while a take holds the controls.
         */
        fun setNightPhotoEngaged(engaged: Boolean) {
            if (rejectLockedControl()) return
            val current = cameraState.value
            if (current.structuralSettingsFrozen || current.selectedMode != CaptureMode.PHOTO) return
            if (settings.nightPhotoMode != com.librestatic.opencinecam.camera.NightPhotoMode.AUTO) return
            val before = nightExtensionActive()
            nightTransientEngaged = engaged
            cameraState.update { it.copy(nightTransientEngaged = engaged) }
            if (before != nightExtensionActive()) {
                attachedPreviewSurface?.takeIf { it.isValid }?.let { attachPreview(it, attachedPreviewRotationDegrees) }
            }
        }

        fun setAeLock(enabled: Boolean) {
            if (rejectLockedControl()) return
            val current = cameraState.value
            if (current.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING)) return
            previewEngine.setAeLock(enabled)
        }

        fun setAfLock(enabled: Boolean, behavior: AfLockBehavior) {
            if (rejectLockedControl()) return
            val current = cameraState.value
            if (current.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING)) return
            previewEngine.setAfLock(enabled, behavior)
        }

        fun tapToFocus(normalizedX: Float, normalizedY: Float, meterExposure: Boolean): Boolean {
            if (rejectLockedControl()) return false
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
            if (rejectLockedControl()) return
            val current = cameraState.value
            if (!current.zoomSupported) return
            val min = current.zoomMinRatio
            val max = current.zoomMaxRatio
            val coerced = ratio.coerceIn(min, max)
            cameraState.value = current.copy(zoomRatio = coerced, zoomEffectiveRatio = coerced)
            current.selectedCameraId?.let { perCameraZoom[it] = coerced }
            previewEngine.setZoomRatio(coerced)
            rebuildGraphWhenPhysicalStillZoomWindowCrosses(current, coerced)
        }

        /**
         * OCC-PLAN-069: the full-FOV still rides a public physical camera's stream and cannot
         * follow lens switches, so crossing the base-zoom window rebuilds the graph; the RES list
         * loses the physical sizes and the session falls back to logical ones on its own.
         */
        private fun rebuildGraphWhenPhysicalStillZoomWindowCrosses(previous: CameraUiState, ratio: Float) {
            // A lens-locked session (direct or GPU crop, whose HAL zoom is pinned) has no physical still routing.
            if (lensLockSkipsStillZoomRebuild(previous.lensLock.route)) return
            if ((previous.zoomRatio in CameraUiState.PHYSICAL_STILL_ZOOM_WINDOW) ==
                (ratio in CameraUiState.PHYSICAL_STILL_ZOOM_WINDOW)
            ) return
            val current = cameraState.value
            if (current.selectedMode !in CameraUiState.photoResolutionModes || current.selectedMode == CaptureMode.RAW_PHOTO) return
            val active = current.activeStillSize ?: return
            val heic = current.selectedMode == CaptureMode.PHOTO &&
                current.effectiveSettings?.photoFormat == StillPhotoFormat.HEIC
            if (current.descriptor?.isPhysicalOnlyStill(active.first, active.second, heic) != true) return
            attachedPreviewSurface?.takeIf { it.isValid }?.let { attachPreview(it, attachedPreviewRotationDegrees) }
        }

        fun selectZoomAnchor(ratio: Float) {
            if (rejectLockedControl()) return
            val current = cameraState.value
            if (!current.zoomSupported) return
            val anchors = current.opticalAnchors
            val target = if (anchors.isNotEmpty()) ZoomMath.nearestAnchor(ratio, anchors).ratio else ratio
            setZoomRatio(target)
        }

        fun setWhiteBalance(selection: WhiteBalanceSelection) {
            if (rejectLockedControl()) return
            val repository = SettingsRepositories.get(this@CaptureService)
            repository.update { it.copy(whiteBalance = selection) }
            applySettings(repository.states.value)
        }

        /** [audioForThisTake] overrides the persisted audio switch for one recording only. */
        /** UI actions retain their originating take and display role, not the next live take. */
        fun setRecordingPausedForRole(paused: Boolean, takeId: Long, ticket: CaptureActionTicket): Boolean {
            if (!ticket.isCurrent(selfRecordingRole, cameraState.value.captureActionGeneration) ||
                cameraState.value.recordingPauseStatus?.takeId != takeId) return false
            return setRecordingPaused(paused)
        }

        fun setRecordingPaused(paused: Boolean): Boolean {
            val current = cameraState.value
            val status = current.recordingPauseStatus ?: return false
            if (current.phase != CameraUiPhase.RECORDING || current.recordingFinalizing || current.recordingPausePending ||
                status.finished || status.paused == paused) return false
            cameraState.value = current.copy(recordingPausePending = true)
            val accepted = previewEngine.setTimelapsePaused(paused) { changed ->
                mainHandler.post {
                    if (cameraState.value.recordingPauseStatus?.takeId == status.takeId) {
                        cameraState.update { it.copy(recordingPausePending = false) }
                        if (!changed) scheduleTimelapseAutoStop()
                    }
                }
            }
            if (!accepted) cameraState.update { it.copy(recordingPausePending = false) }
            return accepted
        }

        /** [reason] is the policy failure that ends the take; the take is still finalized and saved. */
        fun stopRecording(reason: StableFailure? = null): Boolean {
            // PLAN-072 Delivery 4: any stop but the drive's own (the operator, the notification, a policy) means no continuation.
            if (reason?.component != RECORDING_DRIVE_COMPONENT) cancelPendingContinuation()
            cancelCountdown()
            rawTake?.let { return stopRawTake(it, reason) }
            if (cancelRecordingWhiteBalancePreparation()) return true
            if (cameraState.value.phase != CameraUiPhase.RECORDING || cameraState.value.recordingFinalizing) return false
            // Publish before dispatch: a fast finalization callback must not be overwritten as pending.
            cameraState.value = cameraState.value.copy(recordingFinalizing = true)
            if (reason != null) {
                android.util.Log.w("CaptureService", "Stopping take: ${reason.code} (${reason.component}) ${reason.userMessage}")
                pendingStopReason = reason
            }
            val accepted = previewEngine.stopVideo()
            if (!accepted) {
                cameraState.value = cameraState.value.copy(recordingFinalizing = false)
                if (reason != null) pendingStopReason = null
            } else submitCaptureState(stateCommands.takeStopRequested())
            return accepted
        }

        fun setSelfRecordingActive(active: Boolean) {
            if (selfRecordingRole == active) return
            selfRecordingRole = active
            cancelCountdown()
            cancelRecordingWhiteBalancePreparation()
            cameraState.value = cameraState.value.copy(selfRecordingActive = active)
        }

        fun cancelSelfTimer() { cancelCountdown() }

        fun captureForRole(ticket: CaptureActionTicket, audioForThisTake: Boolean? = null): Boolean {
            if (!ticket.isCurrent(selfRecordingRole, cameraState.value.captureActionGeneration)) return false
            return if (ticket.selfRole) captureSelf(audioForThisTake) else capturePrimary(audioForThisTake)
        }

        fun captureSelf(audioForThisTake: Boolean? = null): Boolean {
            if (!selfRecordingRole || rawQualificationSession != null) return false
            if (cancelRecordingWhiteBalancePreparation()) return true
            if (cameraState.value.phase == CameraUiPhase.RECORDING) return stopRecording()
            if (captureCountdown.active != null) { cancelCountdown(); return true }
            val current = cameraState.value
            if (current.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED)) return false
            val delay = settings.subjectDisplay.selfTimerSeconds
            if (delay == 0) return capturePrimaryNow(audioForThisTake)
            val requestedAudio = audioForThisTake ?: settings.audioEnabled
            val ticket = captureCountdown.start(android.os.SystemClock.elapsedRealtime(), delay, countdownContext())
            cameraState.value = current.copy(countdownSeconds = delay, message = null)
            countdownTick = object : Runnable {
                override fun run() {
                    if (captureCountdown.active != ticket) return
                    val tick = captureCountdown.tick(ticket, android.os.SystemClock.elapsedRealtime(), countdownContext(),
                        selfRecordingRole && cameraState.value.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED))
                    cameraState.value = cameraState.value.copy(countdownSeconds = tick.seconds)
                    if (tick.fire) {
                        countdownTick = null
                        if (selfRecordingRole && countdownContext() == ticket.context) capturePrimaryNow(requestedAudio)
                    }
                    else if (captureCountdown.active != null) mainHandler.postDelayed(this, 100)
                    else {
                        countdownTick = null
                        if (selfRecordingRole && cameraState.value.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED)) {
                            cameraState.value = cameraState.value.copy(message = getString(R.string.self_timer_cancelled))
                        }
                    }
                }
            }.also { mainHandler.postDelayed(it, 100) }
            return true
        }

        fun capturePrimary(audioForThisTake: Boolean? = null): Boolean {
            if (captureCountdown.active != null) { cancelCountdown(); return true }
            return capturePrimaryNow(audioForThisTake)
        }

        private fun capturePrimaryNow(audioForThisTake: Boolean?, whiteBalancePrepared: Boolean = false): Boolean {
            if (serviceDestroyed || stillOwner.get() != null || rawQualificationSession != null) return false
            // PLAN-072 Delivery 4: a press between a drive take's stop and its continuation's start is a stop: no continuation.
            if (continuationStarting == null && cancelPendingContinuation()) return true
            if (whiteBalancePrepared) {
                val reservation = transferCapture.get() ?: return false
                if (!reservation.isRetired) return false
                return captureAfterTransferRetirement(audioForThisTake, true, reservation)
            }
            if (cancelTransferPreparation()) return true
            // OCC-PLAN-071: RAW_VIDEO has its own take path and never falls back to an encoded take.
            if (rawTake != null || cameraState.value.selectedMode == CaptureMode.RAW_VIDEO) return toggleRawTake(audioForThisTake)
            val current = cameraState.value
            // STOP and WB cancellation remain immediate. Still-photo ownership is a separate H4 gate.
            if (recordingWbPreparing || current.phase == CameraUiPhase.RECORDING ||
                current.selectedMode !in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG, CaptureMode.TIME_LAPSE)) {
                return capturePrimaryAfterTransfer(audioForThisTake)
            }
            if (current.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) || transferCapture.get() != null) return false
            // Read the publication directly: an IO commit can precede this main runnable
            // while its StateFlow collector is still queued. Health and selection are one snapshot.
            val admissionSelection = recordingLutLibrary?.activeSelections()
            if (admissionSelection?.available != true) {
                cameraState.update { it.copy(message = getString(R.string.lut_recording_library_unavailable), errorCode = "recording-lut-library") }
                return false
            }
            // HLG files carry the camera's own HDR signal; a recording LUT would make them SDR.
            val lut = admissionSelection.recording.takeUnless { current.selectedMode == CaptureMode.HLG }
            currentRecordingLut = lut
            if (lut != null && !com.librestatic.opencinecam.camera.monitorLutCompatible(lut, !current.selectedMode.usesLogGraph)) {
                cameraState.update { it.copy(recordingLutStatus = OperatorLutStatus(lut.cube.sha256,
                    OperatorLutState.INCOMPATIBLE_DOMAIN, selectionId = monitorLutIdentity(lut)),
                    message = getString(R.string.lut_recording_domain_mismatch), errorCode = "recording-lut-domain") }
                return false
            }
            val admissionAudio = current.selectedMode != CaptureMode.TIME_LAPSE && (audioForThisTake ?: settings.audioEnabled)
            val projectedBytesPerMinute = projectedTakeBytesPerMinute(current.selectedMode, settings, current.targetFps,
                current.activeVideoProfile?.constrainedHighSpeed == true, admissionAudio)
            admissionWarning = null
            takeSoftwareEncoder = null
            takeCodecFallback = false
            pendingStartRefusal = null
            takeProjectedBytesPerMinute = projectedBytesPerMinute
            // PLAN-072: measured where the take will go (the drive, or the phone when it falls back; a continuation, the phone).
            val continuation = continuationStarting?.takeIf { it.mode == current.selectedMode }
            val admittedDestination = if (continuation != null) RecordingDestination.Phone else resolveTakeDestination().destination
            val admissionFree = if (continuation != null) phoneVolumeFreeBytes() else destinationFreeBytes(admittedDestination)
            when (val admission = admitRecordingStorage(settings.recordingPolicy, projectedBytesPerMinute, admissionFree)) {
                StorageAdmission.Admit -> Unit
                StorageAdmission.AdmitUnread -> admissionWarning = getString(R.string.storage_unknown_warning)
                is StorageAdmission.Refuse -> {
                    cameraState.update { it.copy(message = storageRefusalText(admission), errorCode = admission.errorCode) }
                    return false
                }
            }
            val reservation = transferCoordinator().reserveCapture()
            check(transferCapture.compareAndSet(null, reservation))
            // A continuation keeps the drive take's names and slate: the same take number, and no increment of its own.
            val captureNames = continuation?.captureNames ?: admittedCaptureNames()
            recordingLutIntent.set(RecordingLutIntent(reservation, lut, captureNames, continuation, admittedDestination))
            settings = settings.copy(productionSlate = captureNames.slate, captureNaming = captureNames.settings)
            cameraState.update { it.copy(recordingLutStatus = waitingRecordingLutStatus(), recordingLutSelectionPending = false) }
            transferCaptureDispatched = false
            val audioRetirement = stopPreviewAudioMonitor(clearLevels = false)
            if (reservation.isRetired && audioRetirement.isDone) {
                try { audioRetirement.join() } catch (_: Exception) {
                    releaseTransferCapture(reservation)
                    fail("preview-audio-retirement-failed", getString(R.string.audio_retirement_failed))
                    return false
                }
                return captureAfterTransferRetirement(audioForThisTake, false, reservation)
            }
            val token = ++transferPreparationToken
            val context = countdownContext()
            transferWaiting = true
            cameraState.update { it.copy(phase = CameraUiPhase.CAPTURING,
                transferRetirementPending = !reservation.isRetired, audioRetirementPending = !audioRetirement.isDone,
                message = getString(if (!audioRetirement.isDone) R.string.audio_retirement_preparing
                    else R.string.webdav_transfer_preparing_capture)) }
            transferPreparationJob = settingsScope.launch {
                try {
                    reservation.awaitRetired()
                    if (transferCapture.get() === reservation && token == transferPreparationToken && transferWaiting) {
                        cameraState.update { it.copy(transferRetirementPending = false) }
                    }
                    awaitPreviewAudioRetirement(audioRetirement)
                    if (serviceDestroyed || !transferWaiting || token != transferPreparationToken ||
                        transferCapture.get() !== reservation) return@launch
                    if (context != countdownContext() || cameraState.value.phase != CameraUiPhase.CAPTURING) {
                        cancelTransferPreparation()
                        return@launch
                    }
                    transferWaiting = false
                    cameraState.value = cameraState.value.copy(phase = current.phase, transferRetirementPending = false, audioRetirementPending = false, message = null)
                    captureAfterTransferRetirement(audioForThisTake, false, reservation)
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // The cancelling owner closes its exact reservation; never start after disposal.
                } catch (_: Exception) {
                    if (transferCapture.get() === reservation && token == transferPreparationToken) {
                        transferWaiting = false
                        releaseTransferCapture(reservation)
                        cameraState.value = cameraState.value.copy(phase = CameraUiPhase.PREVIEWING, transferRetirementPending = false, audioRetirementPending = false,
                            message = getString(if (audioRetirement.isCompletedExceptionally) R.string.audio_retirement_failed
                                else R.string.webdav_transfer_prepare_failed))
                    }
                }
            }
            return true
        }

        private fun captureAfterTransferRetirement(audioForThisTake: Boolean?, whiteBalancePrepared: Boolean,
            reservation: CaptureTransferReservation): Boolean {
            var accepted = false
            try {
                accepted = capturePrimaryAfterTransfer(audioForThisTake, whiteBalancePrepared)
                return accepted
            } finally {
                // An engine timeout can return false while native preparation is still retiring.
                // Such a reservation stays held until the engine proves retirement, onRecordingStopped
                // or actual engine closure; a rejected dispatch never keeps it forever.
                if (!accepted && !transferCaptureDispatched) releaseTransferCapture(reservation)
                else if (!accepted) {
                    // A rejected start never reached RECORDING; an engine failure already moved the actor.
                    submitCaptureState(stateCommands.takeEnded())
                    releaseFailedTakeStart(previewEngine.recordingOutputRetirement())
                }
            }
        }

        private fun capturePrimaryAfterTransfer(audioForThisTake: Boolean?, whiteBalancePrepared: Boolean = false): Boolean {
            if (!whiteBalancePrepared && cancelRecordingWhiteBalancePreparation()) return true
            val current = cameraState.value.let {
                if (!whiteBalancePrepared && settings.recordingWhiteBalance == RecordingWhiteBalancePolicy.CONTINUOUS)
                    it.copy(recordingWhiteBalanceStatus = RecordingWhiteBalanceStatus.IDLE) else it
            }
            if (current.phase == CameraUiPhase.RECORDING && current.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.TIME_LAPSE, CaptureMode.LOG, CaptureMode.HLG)) {
                return stopRecording()
            }
            // The preview comes back before the previous take is published; no capture overlaps that.
            if (current.recordingFinalizing) return false
            if (current.phase != CameraUiPhase.PREVIEWING && current.phase != CameraUiPhase.SAVED && !(whiteBalancePrepared && current.phase == CameraUiPhase.CAPTURING)) return false
            // Every branch below starts clean; time-lapse never sets a label and must not inherit the last take's.
            activeRecordingAudioLabel = getString(R.string.capture_no_audio)
            if (settings.operation.lockDuringTake) previewEngine.cancelFocusPull()
            if (!whiteBalancePrepared && settings.recordingWhiteBalance == RecordingWhiteBalancePolicy.LOCK_ON_RECORD &&
                current.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG, CaptureMode.TIME_LAPSE)) {
                val token = ++recordingWbGeneration
                val context = countdownContext()
                recordingWbPreparing = true
                cameraState.value = current.copy(phase = CameraUiPhase.CAPTURING,
                    recordingWhiteBalanceStatus = RecordingWhiteBalanceStatus.CONVERGING, message = getString(R.string.pro_wb_preparing))
                // Fence pending preferences before asynchronous work. No output or audio recorder exists yet.
                previewEngine.prepareRecordingWhiteBalance { result ->
                    mainHandler.post {
                        if (token != recordingWbGeneration || !recordingWbPreparing) return@post
                        if (context != countdownContext() || cameraState.value.phase != CameraUiPhase.CAPTURING) {
                            cancelRecordingWhiteBalancePreparation()
                            return@post
                        }
                        recordingWbPreparing = false
                        cameraState.value = cameraState.value.copy(recordingWhiteBalanceStatus = result.status)
                        if (result.ready) capturePrimaryNow(audioForThisTake, whiteBalancePrepared = true)
                        else {
                            previewEngine.releaseRecordingWhiteBalance()
                            releaseTransferCapture(transferCapture.get())
                            cameraState.value = cameraState.value.copy(phase = CameraUiPhase.PREVIEWING,
                                message = getString(R.string.pro_wb_prepare_failed), errorCode = result.failure)
                            applySettings(SettingsRepositories.get(this@CaptureService).states.value)
                        }
                    }
                }
                return true
            }
            // PLAN-072: a drive that left or arrived since the admission changes where the take goes; admit it there again.
            if (current.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG, CaptureMode.TIME_LAPSE) &&
                !admitChangedTakeDestination()) return false
            return when (current.selectedMode) {
                CaptureMode.PHOTO, CaptureMode.RAW_PHOTO -> {
                    val owner = StillOwner(admittedCaptureNames())
                    if (!stillOwner.compareAndSet(null, owner)) return false
                    settings = settings.copy(productionSlate = owner.productionSlate, captureNaming = owner.captureNames.settings)
                    cameraState.value = current.copy(phase = CameraUiPhase.CAPTURING, message = null,
                        photoFlashReport = null, lastStillPublication = null, stillCapturePending = true, nightProgressPercent = null)
                    previewEngine.captureStill(selectedStillPhotoFormat(), settings.photoFlash, settings.photoQuality, aspect = settings.photoAspect,
                        zeroShutterLag = settings.zeroShutterLag && current.selectedMode == CaptureMode.PHOTO).also { accepted ->
                        if (!accepted) {
                            if (com.librestatic.opencinecam.stillFormatLostRaw(selectedStillPhotoFormat(), cameraState.value.previewGraphLoss)) finishStillFailure(owner, "still-raw-unavailable", getString(R.string.graph_fallback_raw))
                            else finishStillFailure(owner, "still-capture-not-ready", getString(R.string.capture_still_not_ready))
                        }
                    }
                }
                CaptureMode.BURST -> {
                    val owner = StillOwner(admittedCaptureNames())
                    if (!stillOwner.compareAndSet(null, owner)) return false
                    settings = settings.copy(productionSlate = owner.productionSlate, captureNaming = owner.captureNames.settings)
                    cameraState.value = current.copy(phase = CameraUiPhase.CAPTURING, stillCapturePending = true,
                        burstCaptured = 0, burstExpected = settings.burstCount, burstSaving = false,
                        lastBurstPublication = null, photoFlashReport = null,
                        message = getString(R.string.burst_progress, 0, settings.burstCount))
                    previewEngine.captureBurst(settings.burstCount, aspect = settings.photoAspect, quality = settings.photoQuality).also { accepted ->
                        if (!accepted) finishStillFailure(owner, "burst-not-ready", getString(R.string.capture_burst_not_ready))
                    }
                }
                CaptureMode.BRACKET -> {
                    val owner = StillOwner(admittedCaptureNames())
                    if (!stillOwner.compareAndSet(null, owner)) return false
                    settings = settings.copy(productionSlate = owner.productionSlate, captureNaming = owner.captureNames.settings)
                    cameraState.value = current.copy(phase = CameraUiPhase.CAPTURING, stillCapturePending = true,
                        bracketCaptured = 0, bracketExpected = settings.bracket.count, bracketSaving = false,
                        lastBracketPublication = null, photoFlashReport = null,
                        message = getString(R.string.bracket_capture_progress, 0, settings.bracket.count))
                    previewEngine.captureBracket(settings.bracket, settings.photoQuality, aspect = settings.photoAspect).also { accepted ->
                        if (!accepted) finishStillFailure(owner, "bracket-not-ready", getString(R.string.capture_bracket_not_ready))
                    }
                }
                CaptureMode.LIGHT_TRAIL -> {
                    val owner = StillOwner(admittedCaptureNames())
                    if (!stillOwner.compareAndSet(null, owner)) return false
                    settings = settings.copy(productionSlate = owner.productionSlate, captureNaming = owner.captureNames.settings)
                    cameraState.value = current.copy(phase = CameraUiPhase.CAPTURING, stillCapturePending = true,
                        accumulationFrames = 0, accumulationElapsedMs = 0, accumulationTargetMs = settings.accumulation.durationMs,
                        accumulationSaving = false, accumulationFinishing = false, lastAccumulationPublication = null, photoFlashReport = null,
                        message = getString(R.string.accumulation_capture_progress, 0, 0L, settings.accumulation.durationMs / 1000))
                    previewEngine.captureAccumulation(settings.accumulation, settings.photoQuality, aspect = settings.photoAspect).also { accepted ->
                        if (!accepted) finishStillFailure(owner, "accumulation-not-ready", getString(R.string.capture_accumulation_not_ready))
                    }
                }
                CaptureMode.VIDEO -> {
                    try {
                        stopPreviewAudioMonitor(clearLevels = false)
                        cameraState.value = cameraState.value.copy(audioClipLatched = false, audioLevels = null)
                        val requestedAudioEnabled = !settings.videoOffSpeed && (audioForThisTake ?: settings.audioEnabled)
                        val orientation = recordingOrientationDegrees()
                        val descriptor = current.descriptor
                        val sourceSize = current.activeVideoProfile?.size ?: descriptor?.previewSize
                        if (sourceSize == null || descriptor == null) {
                            fail("recording-geometry-unavailable", getString(R.string.capture_recording_geometry_unavailable))
                            return false
                        }
                        val geometry = RecordingGeometryCalculator.calculate(
                            sourceSize = sourceSize,
                            sensorOrientationDegrees = descriptor.sensorOrientation,
                            deviceOrientationDegrees = orientation,
                            lensFacing = descriptor.lensFacing,
                            mode = settings.recordingGeometryMode,
                            anamorphicSqueeze = settings.anamorphicSqueeze,
                            anamorphicOutputMode = settings.anamorphicOutputMode,
                            supportsEncodedSize = ::encoderSupportsRaster,
                        )
                        activeRecordingGeometry = geometry
                        if (requestedAudioEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            fail("microphone-permission-required", getString(R.string.capture_microphone_permission_required))
                            return false
                        }
                        val takeInput = takeAudioInput(cameraState.value.audioCapabilities
                            ?.let(settings::normalizedFor)
                            ?: settings, requestedAudioEnabled) ?: return false
                        val audioSettings = takeInput.settings
                        val audioRequested = requestedAudioEnabled
                        activeRecordingGain = audioSettings.audioRecordingGain.takeIf { audioRequested }
                        val onAudioLevel = newAudioLevelConsumer()
                        val listeningSink = newAudioListeningSink()
                        val embeddedAudio = if (audioRequested && audioSettings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
                            Camera2EmbeddedAudioConfig(
                                source = audioSettings.audioSource.androidSource,
                                sampleRateHz = audioSettings.audioSampleRateHz,
                                channels = audioSettings.audioChannels,
                                bitrateBps = audioSettings.audioBitrateKbps * 1_000,
                                preferredInputDeviceId = audioSettings.audioInputDeviceId,
                                enableAutomaticGainControl = audioSettings.automaticGainControlEnabled,
                                enableNoiseSuppressor = audioSettings.noiseSuppressorEnabled,
                                enableAcousticEchoCanceler = audioSettings.acousticEchoCancelerEnabled,
                                recordingGain = audioSettings.audioRecordingGain,
                                onAudioLevel = onAudioLevel,
                                listeningSink = listeningSink,
                                inputGuard = takeInput.guard,
                            )
                        } else null
                        when (val start = foreground.start(audioRequested)) {
                            is ForegroundRecordingStart.Rejected -> {
                                fail("recording-foreground-failed", start.failure.userMessage)
                                return false
                            }
                            is ForegroundRecordingStart.Started -> startService(Intent(this@CaptureService, CaptureService::class.java))
                        }
                        val captureNames = requireNotNull(recordingLutIntent.get()?.takeIf { it.owner === transferCapture.get() }).captureNames
                        val admittedSlate = captureNames.slate
                        val output = createTakeOutput(admittedSlate, captureNames)
                        videoOutput = output
                        admitTransferPublication()
                        val separateClock = if (embeddedAudio == null) com.librestatic.opencinecam.camera.CaptureEpochClock(
                            current.descriptor?.timestampSourceRealtime == true, audioSettings.audioSampleRateHz) else null
                        audioSidecarRecorder = when {
                            !audioRequested -> null
                            audioSettings.audioOutputFormat == AudioOutputFormat.WAV_PCM ->
                                WavAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings.copy(productionSlate = admittedSlate), captureClock = separateClock, onAudioLevel = onAudioLevel, recoveryGroup = output.recoveryGroup, listeningSink = listeningSink, inputGuard = takeInput.guard)
                            audioSettings.audioOutputFormat == AudioOutputFormat.FLAC ->
                                FlacAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings.copy(productionSlate = admittedSlate), captureClock = separateClock, onAudioLevel = onAudioLevel, recoveryGroup = output.recoveryGroup, listeningSink = listeningSink, inputGuard = takeInput.guard)
                            else -> null
                        }
                        activeRecordingAudioLabel = when {
                            !audioRequested -> getString(R.string.capture_no_audio)
                            embeddedAudio != null -> "AAC ${kiloHertzText(audioSettings.audioSampleRateHz, messageLocale)} kHz · ${audioSettings.audioBitrateKbps} kbps · ${audioSettings.audioChannels} ch"
                            else -> "${audioSettings.audioOutputFormat.name.substringBefore('_')} ${audioSettings.audioBitDepth.bits}-bit · ${kiloHertzText(audioSettings.audioSampleRateHz, messageLocale)} kHz · ${audioSettings.audioChannels} ch"
                        }
                        cameraState.update { it.copy(phase = CameraUiPhase.CAPTURING, recordingProjectRate = settings.videoProjectRate.takeIf { settings.videoOffSpeed },
                            message = getString(R.string.capture_preparing_video, activeRecordingAudioLabel)) }
                        dispatchTransferCapture()
                        reserveRecordingRecoveryGuard(output)
                        // Constrained high-speed takes must reach the encoder surface directly: the GPU
                        // path's camera texture only receives the 30 fps preview share of each batch.
                        // Off-speed then plays the high-speed capture back at the project rate.
                        val recordingLutForTake = frozenRecordingLut()
                        val directHighSpeed = current.activeVideoProfile?.constrainedHighSpeed == true &&
                            recordingLutForTake == null && audioSidecarRecorder == null &&
                            activeRecordingGain?.enabled != true &&
                            !settings.anamorphicSqueeze.isActive
                        // The encoder then stores the sensor raster and the MP4 matrix rotates it,
                        // so the take is described (and validated) as native raster.
                        val takeGeometry = if (directHighSpeed) RecordingGeometryCalculator.calculate(
                            sourceSize = sourceSize,
                            sensorOrientationDegrees = descriptor.sensorOrientation,
                            deviceOrientationDegrees = orientation,
                            lensFacing = descriptor.lensFacing,
                            mode = RecordingGeometryMode.NATIVE_RASTER,
                        ).also { activeRecordingGeometry = it } else geometry
                        val projectFps = settings.videoProjectRate.let { rate ->
                            kotlin.math.round(rate.numerator.toDouble() / rate.denominator).toInt().coerceAtLeast(1)
                        }
                        attachMotionLog(output, settings.gyroflowLogEnabled && !directHighSpeed && !settings.videoOffSpeed &&
                            current.activeVideoProfile?.constrainedHighSpeed != true, descriptor, takeGeometry)
                        previewEngine.startVideo(
                            output.descriptor,
                            embeddedAudio,
                            separateAudioClock = audioSidecarRecorder?.captureClock,
                            projectRateOverride = settings.videoProjectRate.takeIf { settings.videoOffSpeed && !directHighSpeed },
                            recordingGeometry = takeGeometry,
                            captureRate = current.targetFps.toDouble().takeIf { directHighSpeed },
                            outputFrameRate = projectFps.takeIf { directHighSpeed && settings.videoOffSpeed },
                            videoBitrate = settings.videoBitrateMbps * 1_000_000,
                            recordingLut = recordingLutForTake,
                            videoCodec = settings.videoCodec,
                        ).also { accepted ->
                            if (!accepted) {
                                // A preparation timeout may still own a duplicated native descriptor.
                                retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                                videoOutput = null
                                clearTakeDestination()
                                abortTransferPublication()
                                audioSidecarRecorder?.finish(false)
                                audioSidecarRecorder = null
                                output.discardNeverRecorded()
                                foreground.stop()
                                val refusal = pendingStartRefusal
                                pendingStartRefusal = null
                                // A policy refusal is a soft one: the preview keeps running and the user can change the policy.
                                if (refusal != null) refuseTakeStart(refusal, getString(R.string.video_hardware_encoder_unavailable))
                                else if (cameraState.value.phase != CameraUiPhase.ERROR) fail("video-capture-not-ready", getString(R.string.capture_video_surface_not_ready))
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { audioSidecarRecorder?.finish(false) }
                        audioSidecarRecorder = null
                        runCatching { videoOutput?.discardNeverRecorded() }
                        videoOutput = null
                        clearTakeDestination()
                        abortTransferPublication()
                        foreground.stop()
                        retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                        fail("video-output-failed", failureText(R.string.capture_video_output_failed, failure))
                        false
                    }
                }
                CaptureMode.LOG, CaptureMode.HLG -> {
                    try {
                        val hlgTake = current.selectedMode == CaptureMode.HLG
                        stopPreviewAudioMonitor(clearLevels = false)
                        cameraState.value = cameraState.value.copy(audioClipLatched = false, audioLevels = null)
                        val requestedAudioEnabled = audioForThisTake ?: settings.audioEnabled
                        val orientation = recordingOrientationDegrees()
                        val descriptor = current.descriptor
                        val sourceSize = current.activeLogProfile?.size
                            ?: descriptor?.preferredLogProfile?.size
                        if (sourceSize == null || descriptor == null) {
                            fail("recording-geometry-unavailable", getString(R.string.capture_log_geometry_unavailable))
                            return false
                        }
                        val geometry = RecordingGeometryCalculator.calculate(
                            sourceSize = sourceSize,
                            sensorOrientationDegrees = descriptor.sensorOrientation,
                            deviceOrientationDegrees = orientation,
                            lensFacing = descriptor.lensFacing,
                            mode = settings.recordingGeometryMode,
                            anamorphicSqueeze = settings.anamorphicSqueeze,
                            anamorphicOutputMode = settings.anamorphicOutputMode,
                            supportsEncodedSize = ::hevcEncoderSupportsRaster,
                        )
                        activeRecordingGeometry = geometry
                        if (requestedAudioEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            fail("microphone-permission-required", getString(R.string.capture_microphone_permission_required))
                            return false
                        }
                        val takeInput = takeAudioInput(cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings,
                            requestedAudioEnabled) ?: return false
                        val audioSettings = takeInput.settings
                        activeRecordingGain = audioSettings.audioRecordingGain.takeIf { requestedAudioEnabled }
                        val onAudioLevel = newAudioLevelConsumer()
                        val listeningSink = newAudioListeningSink()
                        val embeddedAudio = if (requestedAudioEnabled && audioSettings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
                            Camera2EmbeddedAudioConfig(
                                source = audioSettings.audioSource.androidSource,
                                sampleRateHz = audioSettings.audioSampleRateHz,
                                channels = audioSettings.audioChannels,
                                bitrateBps = audioSettings.audioBitrateKbps * 1_000,
                                preferredInputDeviceId = audioSettings.audioInputDeviceId,
                                enableAutomaticGainControl = audioSettings.automaticGainControlEnabled,
                                enableNoiseSuppressor = audioSettings.noiseSuppressorEnabled,
                                enableAcousticEchoCanceler = audioSettings.acousticEchoCancelerEnabled,
                                recordingGain = audioSettings.audioRecordingGain,
                                onAudioLevel = onAudioLevel,
                                listeningSink = listeningSink,
                                inputGuard = takeInput.guard,
                            )
                        } else null
                        when (val start = foreground.start(requestedAudioEnabled)) {
                            is ForegroundRecordingStart.Rejected -> {
                                fail("recording-foreground-failed", start.failure.userMessage)
                                return false
                            }
                            is ForegroundRecordingStart.Started -> startService(Intent(this@CaptureService, CaptureService::class.java))
                        }
                        val captureNames = requireNotNull(recordingLutIntent.get()?.takeIf { it.owner === transferCapture.get() }).captureNames
                        val admittedSlate = captureNames.slate
                        val output = createTakeOutput(admittedSlate, captureNames)
                        videoOutput = output
                        admitTransferPublication()
                        val separateClock = if (embeddedAudio == null) com.librestatic.opencinecam.camera.CaptureEpochClock(
                            current.descriptor?.timestampSourceRealtime == true, audioSettings.audioSampleRateHz) else null
                        audioSidecarRecorder = when {
                            !requestedAudioEnabled || embeddedAudio != null -> null
                            audioSettings.audioOutputFormat == AudioOutputFormat.WAV_PCM ->
                                WavAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings.copy(productionSlate = admittedSlate), captureClock = separateClock, onAudioLevel = onAudioLevel, recoveryGroup = output.recoveryGroup, listeningSink = listeningSink, inputGuard = takeInput.guard)
                            audioSettings.audioOutputFormat == AudioOutputFormat.FLAC ->
                                FlacAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings.copy(productionSlate = admittedSlate), captureClock = separateClock, onAudioLevel = onAudioLevel, recoveryGroup = output.recoveryGroup, listeningSink = listeningSink, inputGuard = takeInput.guard)
                            else -> error("Unsupported LOG audio format ${audioSettings.audioOutputFormat}.")
                        }
                        activeRecordingAudioLabel = when {
                            !requestedAudioEnabled -> getString(R.string.capture_no_audio)
                            embeddedAudio != null -> "AAC ${kiloHertzText(audioSettings.audioSampleRateHz, messageLocale)} kHz · ${audioSettings.audioBitrateKbps} kbps · ${audioSettings.audioChannels} ch"
                            else -> "${audioSettings.audioOutputFormat.name.substringBefore('_')} ${audioSettings.audioBitDepth.bits}-bit · ${kiloHertzText(audioSettings.audioSampleRateHz, messageLocale)} kHz · ${audioSettings.audioChannels} ch"
                        }
                        cameraState.update { it.copy(
                            phase = CameraUiPhase.CAPTURING,
                            recordingProjectRate = null,
                            message = if (hlgTake) {
                                if (hdrTakeFormat(current) == HdrVideoFormat.HDR10) {
                                    getString(R.string.capture_preparing_hdr10, current.targetFps, activeRecordingAudioLabel)
                                } else getString(R.string.capture_preparing_hlg, current.targetFps, activeRecordingAudioLabel)
                            } else if (current.activeLogProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                                getString(R.string.capture_preparing_oclog_hfr, current.targetFps, activeRecordingAudioLabel)
                            } else {
                                getString(R.string.capture_preparing_oclog, current.targetFps, activeRecordingAudioLabel)
                            },
                        ) }
                        dispatchTransferCapture()
                        reserveRecordingRecoveryGuard(output)
                        attachMotionLog(output, settings.gyroflowLogEnabled && current.activeVideoProfile?.constrainedHighSpeed != true,
                            descriptor, geometry)
                        previewEngine.startOpenCineLogVideo(
                            output.descriptor,
                            recordingGeometry = geometry,
                            videoBitrate = settings.videoBitrateMbps * 1_000_000,
                            audio = embeddedAudio,
                            separateAudioClock = audioSidecarRecorder?.captureClock,
                            recordingLut = frozenRecordingLut(),
                            hlgOutput = hlgTake,
                            hdr10Output = hlgTake && hdrTakeFormat(current) == HdrVideoFormat.HDR10,
                        ).also { accepted ->
                            if (!accepted) {
                                // A preparation timeout may still own a duplicated native descriptor.
                                retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                                videoOutput = null
                                clearTakeDestination()
                                abortTransferPublication()
                                audioSidecarRecorder?.finish(false)
                                audioSidecarRecorder = null
                                output.discardNeverRecorded()
                                foreground.stop()
                                if (cameraState.value.phase != CameraUiPhase.ERROR) {
                                    fail("log-capture-not-ready", getString(R.string.capture_log_pipeline_not_ready))
                                }
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { audioSidecarRecorder?.finish(false) }
                        audioSidecarRecorder = null
                        runCatching { videoOutput?.discardNeverRecorded() }
                        videoOutput = null
                        clearTakeDestination()
                        abortTransferPublication()
                        foreground.stop()
                        retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                        fail("log-output-failed", failureText(R.string.capture_log_output_failed, failure))
                        false
                    }
                }
                CaptureMode.TIME_LAPSE -> {
                    try {
                        activeRecordingGain = null
                        val descriptor = current.descriptor ?: return false
                        val sourceSize = current.activeVideoProfile?.size ?: descriptor.previewSize
                        val orientation = recordingOrientationDegrees()
                        val projectRate = settings.timelapseProjectRate
                        val geometry = RecordingGeometryCalculator.calculate(sourceSize, descriptor.sensorOrientation, orientation, descriptor.lensFacing,
                            settings.recordingGeometryMode, settings.anamorphicSqueeze, settings.anamorphicOutputMode, ::encoderSupportsRaster)
                        activeRecordingGeometry = geometry
                        val intervalCapture = TimelapseCapture(settings.timelapseIntervalMs * 1_000_000L, projectRate,
                            settings.timelapseFrameCount.toLong().takeIf { settings.timelapseLimitMode == TimeLapseLimitMode.FRAME_COUNT })
                        when (val start = foreground.start(false)) {
                            is ForegroundRecordingStart.Rejected -> {
                                fail("recording-foreground-failed", start.failure.userMessage)
                                return false
                            }
                            is ForegroundRecordingStart.Started -> startService(Intent(this@CaptureService, CaptureService::class.java))
                        }
                        val captureNames = requireNotNull(recordingLutIntent.get()?.takeIf { it.owner === transferCapture.get() }).captureNames
                        val admittedSlate = captureNames.slate
                        val output = createTakeOutput(admittedSlate, captureNames)
                        videoOutput = output
                        admitTransferPublication()
                        val captureRate = 1000.0 / settings.timelapseIntervalMs
                        cameraState.value = current.copy(
                            phase = CameraUiPhase.CAPTURING,
                            message = getString(R.string.timelapse_preparing, captureRate, projectRate.projectLabel()),
                            timelapseIntervalMs = settings.timelapseIntervalMs,
                            timelapseLimitMode = settings.timelapseLimitMode,
                            timelapseFrameCount = settings.timelapseFrameCount,
                            timelapseDurationMs = settings.timelapseDurationMs,
                            recordingProjectRate = projectRate,
                            timelapseFramesCaptured = 0,
                            timelapseMissedIntervals = 0,
                            timelapseEncoder = null,
                            timelapseHardwareEncoder = null,
                        )
                        dispatchTransferCapture()
                        reserveRecordingRecoveryGuard(output)
                        previewEngine.startVideo(
                            output.descriptor,
                            null,
                            recordingGeometry = geometry,
                            timelapse = intervalCapture,
                            videoBitrate = settings.videoBitrateMbps * 1_000_000,
                            recordingLut = frozenRecordingLut(),
                            videoCodec = settings.videoCodec,
                        ).also { accepted ->
                            if (!accepted) {
                                // A preparation timeout may still own a duplicated native descriptor.
                                retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                                videoOutput = null
                                clearTakeDestination()
                                abortTransferPublication()
                                output.discardNeverRecorded()
                                foreground.stop()
                                fail("timelapse-not-ready", getString(R.string.capture_timelapse_surface_not_ready))
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { videoOutput?.discardNeverRecorded() }
                        videoOutput = null
                        clearTakeDestination()
                        abortTransferPublication()
                        foreground.stop()
                        retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                        fail("timelapse-output-failed", failureText(R.string.capture_timelapse_output_failed, failure))
                        false
                    }
                }
                else -> {
                    val gate = current.modeGates.getValue(current.selectedMode)
                    cameraState.value = current.copy(
                        message = if (gate == ModeGateState.AVAILABLE) {
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
            ACTION_STOP_RECORDING -> if (!localBinder.stopRecording() && cameraState.value.phase != CameraUiPhase.RECORDING) foreground.stop()
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // The last client left: a qualification has no one to report to.
        localBinder.cancelRawVideoQualification(RawQualificationStopReasons.APP_LEFT)
        if (!cameraState.value.structuralSettingsFrozen && cameraState.value.phase != CameraUiPhase.CAPTURING) stopSelf()
        return true
    }

    override fun onDestroy() {
        serviceDestroyed = true
        // A drive take's continuation never outlives the service (PLAN-072 Delivery 4).
        driveContinuation.veto()
        recordingVolumeMonitor?.close()
        recordingVolumeMonitor = null
        driveArrivalMonitor?.close()
        driveArrivalMonitor = null
        rawQualificationSession?.let { session ->
            // The qualifier ends on its own thread (within about two seconds) and releases the camera; nothing is stored.
            localBinder.cancelRawVideoQualification(RawQualificationStopReasons.SERVICE_CLOSED)
            session.batteryReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
            session.batteryReceiver = null
        }
        calibrationRun?.let { run ->
            run.lost = true
            run.runner?.abort(com.librestatic.opencinecam.camera.calibration.RunFailure.CAMERA_LOST)
            run.thread?.interrupt()
            calibrationRun = null
            lensCalibrationState.update {
                it.copy(phase = com.librestatic.opencinecam.LensCalibrationPhase.FAILED,
                    failure = com.librestatic.opencinecam.LensCalibrationFailure.CAMERA_LOST)
            }
            cameraState.update { it.copy(lensCalibrationRunning = false) }
        }
        previewEngine.stopCalibrationCapture()
        audioInputMonitor?.unregister()
        audioInputMonitor = null
        audioRouteOwner = null
        mainHandler.removeCallbacks(previewStartWatchdog)
        transferPreparationToken++
        transferWaiting = false
        val transferReservation = transferCapture.get()
        recordingWbGeneration++
        recordingWbPreparing = false
        cancelCountdown()
        subjectPortClosed = true
        subjectSurfaces.current?.let { subjectSurfaces.release(it.token) }
        foldClosureTracker?.close()
        settingsScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        stopPreviewAudioMonitor(clearLevels = true)
        audioListeningController?.let { audioListeningRetirement = it.closeAsync() }
        foreground.stop()
        val take = detachTake()
        // ADR-0011: nothing that holds bytes is deleted. The storage executor closes with the service,
        // so what the take recorded stays pending and journalled for crash recovery to publish.
        runCatching { take.video?.recoveryGroup?.beginSalvage() }
        runCatching { take.audio?.prepareIncompleteCompletion(listOf(IncompleteTake.RECORDING_INTERRUPTED)) }
        runCatching { take.video?.finish(false) }
        abortTransferPublication(take.publication)
        val retired = previewEngine.closeAsync()
        // The closing engine ends a RAW take; whatever is still recording is finalized, then the ring is freed.
        retired.whenComplete { _, _ ->
            val teardown = Runnable {
                runCatching { if (rawRecorder.isRecording) rawRecorder.stop() }
                runCatching { rawRecorder.disarm() }
            }
            if (!rawControlExecute(teardown)) teardown.run()
            rawControl.shutdown()
        }
        CaptureStartup.released(previewEngine)
        retireRecordingRecoveryGuards(retired)
        retired.whenComplete { _, failure ->
            if (failure == null) releaseTransferCapture(transferReservation)
        }
        if (::physicalOrientationTracker.isInitialized) physicalOrientationTracker.close()
        stillOwner.set(null)
        // Accepted still publications finish on orderly service destruction. New jobs reject;
        // completion callbacks are fenced by serviceDestroyed and the exact still owner.
        storageExecutor.shutdown()
        recoveryExecutor.shutdown()
        driveFreeSpaceExecutor.shutdown()
        actor.close()
        com.librestatic.opencinecam.share.NetworkShare.reportTakeActive(false)
        super.onDestroy()
    }

    private var pendingPreset: CameraPreset? = null
    private var pendingPresetFocus: CameraPreset? = null
    private var recordingWbGeneration = 0L
    private var recordingWbPreparing = false

    private fun cancelRecordingWhiteBalancePreparation(): Boolean {
        if (cancelTransferPreparation()) return true
        if (!recordingWbPreparing) return false
        recordingWbPreparing = false
        recordingWbGeneration++
        previewEngine.releaseRecordingWhiteBalance()
        releaseTransferCapture(transferCapture.get())
        cameraState.value = cameraState.value.copy(phase = CameraUiPhase.PREVIEWING,
            recordingWhiteBalanceStatus = RecordingWhiteBalanceStatus.IDLE, message = getString(R.string.pro_wb_cancelled))
        localBinder.applySettings(SettingsRepositories.get(this).states.value)
        return true
    }

    private fun finishStillFailure(owner: StillOwner, code: String, message: String) {
        if (serviceDestroyed || !stillOwner.compareAndSet(owner, null)) return
        cameraState.update { it.copy(stillCapturePending = false, burstSaving = false, bracketSaving = false, accumulationSaving = false) }
        fail(code, message)
    }

    /**
     * The chassis orientation that locks the file's rotation. A phone held flat (pointing up or down)
     * never reports a stable quadrant, so recording proceeds as upright portrait with a warning
     * instead of refusing the take.
     */
    private fun recordingOrientationDegrees(): Int =
        physicalOrientationTracker.snapshot()?.degrees ?: run {
            cameraState.update { state -> state.copy(message = getString(R.string.recording_orientation_assumed_upright), messageTransient = true) }
            0
        }

    private fun takeInProgress(): Boolean = cameraState.value.phase in setOf(CameraUiPhase.RECORDING, CameraUiPhase.CAPTURING)

    private class TakeAudioInput(val settings: CameraSettings, val guard: AudioInputRouteGuard?)

    /**
     * Applies the loss policy to a remembered input that is missing before REC (OCC-AUDIO-012).
     * Null means the take is refused; the failure is already published.
     */
    private fun takeAudioInput(audioSettings: CameraSettings, audioRequested: Boolean): TakeAudioInput? {
        if (!audioRequested) return TakeAudioInput(audioSettings, null)
        val requested = AudioInputRouteGuard.inputDevice(this, audioSettings.audioInputDeviceId)
        val missing = (audioSettings.audioInputDeviceId != null && requested == null) ||
            cameraState.value.audioCapabilities?.let(audioSettings::audioInputUnavailable) == true
        if (!missing) return TakeAudioInput(audioSettings, newAudioRouteGuard(audioSettings, AudioInputLossAction.IGNORE, requested))
        val startDegraded = when (audioSettings.audioInputLossPolicy) {
            AudioInputLossPolicy.STOP_TAKE -> {
                fail("audio-input-missing", getString(R.string.audio_input_missing_refused))
                return null
            }
            AudioInputLossPolicy.FALLBACK_BUILTIN -> AudioInputLossAction.FALLBACK
            AudioInputLossPolicy.CONTINUE_SILENT -> AudioInputLossAction.SILENCE
        }
        val degraded = audioSettings.copy(audioInputDeviceId = null)
        return TakeAudioInput(degraded, newAudioRouteGuard(degraded, startDegraded, null))
    }

    private fun newAudioRouteGuard(
        audioSettings: CameraSettings,
        startDegraded: AudioInputLossAction,
        requested: android.media.AudioDeviceInfo? = AudioInputRouteGuard.inputDevice(this, audioSettings.audioInputDeviceId),
    ): AudioInputRouteGuard {
        lateinit var guard: AudioInputRouteGuard
        guard = AudioInputRouteGuard(
            this, requested, audioSettings.audioInputLossPolicy, startDegraded,
            onRoute = { route -> mainHandler.post { publishAudioRoute(guard, route) } },
            onLoss = { action -> mainHandler.post { onAudioInputLost(guard, action) } },
        )
        audioRouteOwner = guard
        return guard
    }

    private fun publishAudioRoute(guard: AudioInputRouteGuard, route: ActiveAudioInput) {
        if (serviceDestroyed || audioRouteOwner !== guard) return
        cameraState.update { it.copy(activeAudioInput = route) }
    }

    private fun onAudioInputLost(guard: AudioInputRouteGuard, action: AudioInputLossAction) {
        if (serviceDestroyed || audioRouteOwner !== guard) return
        when (action) {
            AudioInputLossAction.STOP_TAKE -> {
                android.util.Log.w("CaptureService", "Stopping take: audio-input-lost")
                stopTakeForLostInput(guard, attemptsLeft = LOST_INPUT_STOP_ATTEMPTS)
            }
            AudioInputLossAction.FALLBACK ->
                cameraState.update { it.copy(message = getString(R.string.audio_input_lost_fallback)) }
            AudioInputLossAction.SILENCE ->
                cameraState.update { it.copy(message = getString(R.string.audio_input_lost_silent)) }
            AudioInputLossAction.IGNORE -> Unit
        }
    }

    /** A loss can arrive while the take is still starting; stopRecording only accepts a RECORDING take. */
    private fun stopTakeForLostInput(guard: AudioInputRouteGuard, attemptsLeft: Int) {
        if (serviceDestroyed || audioRouteOwner !== guard || !takeInProgress()) return
        if (localBinder.stopRecording(audioInputLostFailure())) {
            cameraState.update { it.copy(message = getString(R.string.audio_input_lost_stopped)) }
        } else if (attemptsLeft > 0) {
            mainHandler.postDelayed({ stopTakeForLostInput(guard, attemptsLeft - 1) }, LOST_INPUT_STOP_RETRY_MS)
        }
    }

    /** Hotplug: a microphone appeared or disappeared. A running take keeps its guard; the probe waits. */
    private fun onAudioInputsChanged() {
        if (serviceDestroyed) return
        if (takeInProgress()) {
            audioInputRefreshDeferred = true
            return
        }
        localBinder.refreshAudioCapabilities()
        startPreviewAudioMonitorIfEligible()
    }

    /** "Using USB microphone: <name>" when Auto moves to a newly connected external input. */
    private fun announceAutoAudioInput(capabilities: com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities) {
        val choice = settings.resolveAudioInputSelection(capabilities.inputs).autoChoice
        val previous = lastAutoAudioInput
        lastAutoAudioInput = choice?.key
        if (settings.audioInputKey != null || takeInProgress() || choice == null || !choice.isExternal) return
        if (previous == null || previous == choice.key) return
        val label = choice.label.ifBlank { getString(audioInputTypeLabel(audioInputKind(choice.type))) }
        cameraState.update { it.copy(message = getString(R.string.audio_input_auto_using,
            getString(audioInputTypeLabel(audioInputKind(choice.type))), label)) }
    }

    /** A start refusal that is not a failure (ADR-0016): back to previewing with the reason, like the storage reserve refusal. */
    private fun refuseTakeStart(code: String, message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!serviceDestroyed) refuseTakeStart(code, message) }
            return
        }
        val wasFrozen = cameraState.value.structuralSettingsFrozen || cameraState.value.captureControlsLocked
        cameraState.update {
            it.copy(phase = CameraUiPhase.PREVIEWING, transferRetirementPending = false, audioRetirementPending = false,
                recordingFinalizing = false, errorCode = code, message = message, messageTransient = false)
        }
        mainHandler.post {
            recordingWbGeneration++
            recordingWbPreparing = false
            previewEngine.releaseRecordingWhiteBalance()
            cancelCountdown()
            if (wasFrozen) localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
        }
    }

    /** The localized notice first; the platform's own detail, when it has one, follows for diagnosis. */
    private fun failureText(@StringRes notice: Int, failure: Throwable): String =
        listOfNotNull(getString(notice), failure.message?.takeIf { it.isNotBlank() }).joinToString(" · ")

    private val messageLocale: Locale get() = resources.configuration.locales[0]

    private fun fail(code: String, message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // transferWaiting and WB preparation are main-owned; worker failures hop like other callbacks.
            mainHandler.post { if (!serviceDestroyed) fail(code, message) }
            return
        }
        if (transferWaiting) {
            transferWaiting = false
            transferPreparationToken++
            releaseTransferCapture(transferCapture.get()) // Native capture was never dispatched.
        }
        val wasFrozen = cameraState.value.structuralSettingsFrozen || cameraState.value.captureControlsLocked ||
            (cameraState.value.phase == CameraUiPhase.CAPTURING && cameraState.value.selectedMode in setOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL))
        cameraState.value = cameraState.value.copy(
            phase = CameraUiPhase.ERROR,
            transferRetirementPending = false,
            audioRetirementPending = false,
            recordingFinalizing = false,
            errorCode = code,
            message = message,
        )
        mainHandler.post {
            recordingWbGeneration++
            recordingWbPreparing = false
            previewEngine.releaseRecordingWhiteBalance()
            cancelCountdown()
            if (wasFrozen) localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
        }
    }

    /**
     * True when at least one hardware encoder can raster the given encoded frame size at 30 fps.
     * Used by [RecordingGeometryCalculator.calculate] to decide whether a DESQUEEZED
     * anamorphic output fits the SoC codec limits or must degrade to SQUEEZED + SAR.
     * Uses the same predicate as profile enumeration; AVC because the VIDEO branch
     * goes through the passthrough-SDR GPU pipeline, which encodes H.264.
     */
    private fun encoderSupportsRaster(size: RecordingFrameSize): Boolean =
        previewEngine.encoderCanReachFps(Size(size.width, size.height))

    /**
     * Same as [encoderSupportsRaster] but validates against HEVC surface-encoder caps,
     * because the LOG pipeline ([OpenCineLogGpuPipeline] with passthroughSdr=false)
     * encodes Main10/HEVC, not AVC.
     */
    private fun hevcEncoderSupportsRaster(size: RecordingFrameSize): Boolean =
        previewEngine.hevcEncoderCanReachFps(Size(size.width, size.height))

    private fun captureEpochLabel(report: com.librestatic.opencinecam.camera.CaptureEpochReport?): String = report?.let {
        " · " + getString(when (it.policy) {
            "SHARED_BOOTTIME_CAPTURE_ANCHORS" -> if (it.audioSourceWindow != null) R.string.av_epoch_capture_anchors_calibrated else R.string.av_epoch_capture_anchors
            "BOOTTIME_ESTIMATED_AUDIO_START" -> R.string.av_epoch_estimated
            else -> R.string.av_epoch_unknown
        }) + if (it.audioSourceWindow != null) " · " + getString(R.string.aac_source_window_applied) else ""
    }.orEmpty()

    /** The transfer a HLG-mode take records: HDR10 only when chosen and the active profile offers it. */
    private fun hdrTakeFormat(state: CameraUiState): HdrVideoFormat =
        Hdr10Video.effectiveFormat(settings.hdrVideoFormat, state.activeLogProfile?.hdr10 == true)

    /** Provenance for an HLG take. The file's own BT.2020/HLG tags are what players read; this records how they were made. */
    private fun hlgSidecarJson(evidence: OpenCineLogRecordingEvidence, avTiming: com.librestatic.opencinecam.camera.CaptureEpochReport?): String = JSONObject()
        .put("schema", "opencinecam.hdr-video.v1")
        .put("hdr", JSONObject()
            .put("transfer", evidence.curve)
            .put("transferStandard", evidence.curveVersion)
            .put("primaries", evidence.gamut)
            .put("range", evidence.range)
            .put("sourceDynamicRange", evidence.sourceDynamicRange)
            .put("sourcePrimaries", "BT.709")
            .put("codecName", evidence.codecName)
            .put("codecProfile", evidence.codecProfile)
            .put("encoderVuiTransfer", evidence.encoderVuiTransfer ?: JSONObject.NULL)
            .put("containerVuiTransfer", evidence.containerVuiTransfer ?: JSONObject.NULL)
            .put("ycbcrConversion", evidence.ycbcrConversion ?: JSONObject.NULL)
            .put("transformSha256", evidence.transformSha256)
            .put("sourceDataSpaceMismatchedFrames", evidence.sourceDataSpaceMismatchedFrames ?: JSONObject.NULL)
            .put("encodedFrames", evidence.encodedFrames)
            .put("targetFps", evidence.targetFps)
            .put("maxVideoPtsGapUs", evidence.maxVideoPtsGapUs ?: JSONObject.NULL)
            .put("videoPtsGapsOverThreshold", evidence.videoPtsGapsOverThreshold))
        .apply { avTiming?.let { put("avTiming", com.librestatic.opencinecam.captureEpochJson(it)) } }
        .toString(2)

    /** The lens correction a take was encoded with; present only when one was active. */
    private fun lensCorrectionJson(lens: com.librestatic.opencinecam.camera.calibration.LensCorrectionEvidence): JSONObject = JSONObject()
        .put("setSha256", lens.setSha256)
        .put("splicedTransformSha256", lens.splicedTransformSha256)
        .put("alignment", lens.alignment)
        .put("perLensFrameCounts", JSONObject().apply { lens.perLensFrameCounts.forEach { (id, n) -> put(id, n) } })
        .put("fallbackFrames", lens.fallbackFrames)

    /** The per-frame exposure gain a take could apply (the FPS LOCK hold over lens switches); present only when it was on. */
    private fun exposureCompensationJson(compensation: com.librestatic.opencinecam.camera.calibration.ExposureCompensationEvidence): JSONObject = JSONObject()
        .put("splicedTransformSha256", compensation.splicedTransformSha256)
        .put("compensatedFrames", compensation.compensatedFrames)
        .put("minGain", compensation.minGain.toDouble())
        .put("maxGain", compensation.maxGain.toDouble())

    private fun logSidecarJson(evidence: OpenCineLogRecordingEvidence, timecodeReport: RecordingTimecodeReport?): String = JSONObject()
        .put("schema", "opencinecam-oclog-sidecar-v2")
        .put("avTiming", evidence.avTiming?.let(::captureEpochJson) ?: JSONObject.NULL)
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
            // Primaries the decode assumed before converting to BT.2020. Clips without it are HLG takes
            // that read the BT.709-primaried stream as BT.2020 (see OpenCineLogGpuPipeline).
            .put("primaries", "BT.709")
            .put("androidDataSpace", evidence.sourceDataSpace ?: JSONObject.NULL)
            .put("dataSpaceMismatchedFrames", evidence.sourceDataSpaceMismatchedFrames ?: JSONObject.NULL)
            .put("unexpectedAndroidDataSpace", evidence.unexpectedSourceDataSpace ?: JSONObject.NULL)
            .put(
                "interpretation",
                if (evidence.sourcePath == OpenCineLogSourcePath.HLG10_BT2020) {
                    "HLG OETF decoded to scene-linear, BT.709 primaries measured on device converted to BT.2020 in highp GLES"
                } else {
                    "Standard-range ISP output interpreted with the BT.709 video transfer, linearized, and converted to BT.2020 in highp GLES; runtime dataspace is recorded and no source gamut, 10-bit, or HDR highlight claim is made"
                },
            ))
        .put("transform", JSONObject()
            .put("curve", evidence.curve)
            .put("version", evidence.curveVersion)
            .put("domain", evidence.recordingLut?.input?.name ?: "scene-linear")
            .put("gamut", evidence.gamut)
            .put("range", evidence.range)
            .put(if (evidence.recordingLut != null) "sourceShaderSha256" else "shaderSha256", evidence.transformSha256)
            // Runtime shader parameter, part of the qualified tuple: scene-linear gain before OCLog2.
            .put("greyReference", evidence.greyReference.name)
            .put("sceneGain", evidence.sceneGain.toDouble())
            // Matrix/range/bit depth the shader decoded the camera YCbCr with; null means the
            // driver's sampler chose them and the code values are not verifiable.
            .put("ycbcrConversion", evidence.ycbcrConversion ?: JSONObject.NULL))
        .put("encoding", JSONObject()
            .put("mime", evidence.codecMime)
            .put("profile", evidence.codecProfile)
            .put("codecName", evidence.codecName)
            .put("eglRenderTargetBits", evidence.eglRenderTargetBits)
            .put("colorPrimaries", if (evidence.recordingLut != null) "BT.709" else evidence.gamut)
            .put("transfer", when {
                evidence.recordingLut != null -> "BT.709 SDR video"
                evidence.containerVuiTransfer == 2 -> "unspecified (H.273 2); OCLog2 sidecar authoritative"
                else -> "encoder tag H.273 ${evidence.containerVuiTransfer ?: "absent"} kept; OCLog2 sidecar authoritative"
            })
            // H.273 transfer the encoder wrote, and the one the app left in the file.
            .put("encoderVuiTransfer", evidence.encoderVuiTransfer ?: JSONObject.NULL)
            .put("containerVuiTransfer", evidence.containerVuiTransfer ?: JSONObject.NULL)
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
        .put("maxVideoPtsGapUs", evidence.maxVideoPtsGapUs)
        .put("videoPtsGapsOverThreshold", evidence.videoPtsGapsOverThreshold)
        .put("timecode", timecodeReport?.let(::recordingTimecodeJson) ?: JSONObject.NULL)
        .put("qualification", cameraState.value.activeLogProfile?.let { profile ->
            JSONObject()
                .put("stage", profile.qualificationStage.name)
                .put("reason", profile.qualificationReason)
                .put("evidenceId", profile.qualificationEvidenceId ?: JSONObject.NULL)
                .put("exactProfileVerified", profile.isVerified)
        } ?: JSONObject()
            .put("stage", "UNKNOWN")
            .put("reason", "active-log-profile-missing")
            .put("evidenceId", JSONObject.NULL)
            .put("exactProfileVerified", false))
        .toString(2)

    private fun activeRecordingElapsedMs(): Long = cameraState.value.recordingPauseStatus
        ?.activeElapsedAt(android.os.SystemClock.elapsedRealtime())
        ?: (android.os.SystemClock.elapsedRealtime() - recordingStartedAtMs).coerceAtLeast(0L)

    private fun timelapseTimingJson(status: com.librestatic.opencinecam.camera.TimelapsePauseStatus,
        rate: com.librestatic.opencinecam.camera.CaptureFrameRate): String = org.json.JSONObject()
        .put("schema", "opencinecam.timelapse-timing.v1")
        .put("audio", "silent")
        .put("projectNumerator", rate.numerator).put("projectDenominator", rate.denominator)
        .put("submittedFrames", status.submittedFrames).put("missedIntervals", status.missedIntervals)
        .put("activeCaptureMs", status.activeElapsedMs).put("pausedMs", status.pausedElapsedMs)
        .put("durationLimitClock", "active-monotonic-command-time")
        .put("resumeIntervalPolicy", "first-real-image-starts-new-interval")
        .put("projectPtsPolicy", "absolute-frame-index-rational")
        .put("pauseEvents", org.json.JSONArray().apply {
            status.events.forEach { event -> put(org.json.JSONObject().put("paused", event.paused)
                .put("commandElapsedMs", event.elapsedMs).put("nextProjectFrameIndex", event.frameIndex)) }
        }).toString(2)

    private fun scheduleTimelapseAutoStop() {
        cancelTimelapseAutoStop()
        if (cameraState.value.selectedMode != CaptureMode.TIME_LAPSE || cameraState.value.recordingPauseStatus?.paused == true) return
        val delayMs = when (cameraState.value.timelapseLimitMode) {
            TimeLapseLimitMode.UNLIMITED -> return
            TimeLapseLimitMode.FRAME_COUNT -> return // Stop on actual submitted-frame progress, not elapsed-time estimates.
            TimeLapseLimitMode.DURATION -> cameraState.value.timelapseDurationMs - activeRecordingElapsedMs()
        }.coerceAtLeast(1L)
        val runnable = Runnable {
            if (cameraState.value.phase == CameraUiPhase.RECORDING &&
                cameraState.value.selectedMode == CaptureMode.TIME_LAPSE
            ) {
                localBinder.stopRecording()
            }
        }
        timelapseAutoStopRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelTimelapseAutoStop() {
        timelapseAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        timelapseAutoStopRunnable = null
    }

    private fun formatTimelapseInterval(intervalMs: Long): String {
        val totalSeconds = intervalMs / 1000.0
        return when {
            totalSeconds < 1.0 -> "${"%.1f".format(totalSeconds)} s"
            totalSeconds < 60.0 -> "${totalSeconds.toInt()} s"
            totalSeconds < 3600.0 -> {
                val minutes = totalSeconds.toInt() / 60
                val seconds = totalSeconds.toInt() % 60
                if (seconds == 0) "$minutes min" else "$minutes min $seconds s"
            }
            else -> {
                val hours = totalSeconds.toInt() / 3600
                val minutes = (totalSeconds.toInt() % 3600) / 60
                if (minutes == 0) "$hours h" else "$hours h $minutes min"
            }
        }
    }

    private fun fpsUnavailableNotice(transient: Boolean, mode: CaptureMode, requestedFps: Int, effectiveFps: Int): String? {
        if (!transient) return null
        val ispNote = if (cameraState.value.activeLogProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) " · " + getString(R.string.capture_source_isp_derived) else ""
        return getString(R.string.capture_fps_unavailable, requestedFps, effectiveFps) + ispNote
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
        CaptureMode.HLG -> getString(R.string.hlg_mode)
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
            mode.usesLogGraph -> VideoGeometryPolicy.supportedLogFps(descriptor.logProfilesFor(mode).map { it.toSpec() }, videoWidth, videoHeight)
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
        mode: CaptureMode,
        width: Int,
        height: Int,
        fps: Int,
    ): com.librestatic.opencinecam.camera.Camera2LogProfile? = descriptor.logProfilesFor(mode).normalizedLogProfile(width, height, fps)

    private fun List<com.librestatic.opencinecam.camera.Camera2LogProfile>.normalizedLogProfile(
        width: Int,
        height: Int,
        fps: Int,
    ) = firstOrNull {
        it.size.width == width && it.size.height == height && it.fps == fps
    } ?: firstOrNull {
        it.size.width == width && it.size.height == height &&
            it.fps == Camera2PreviewEngine.DEFAULT_TARGET_FPS &&
            it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020
    } ?: firstOrNull {
        it.size.width == 1920 && it.size.height == 1080 &&
            it.fps == Camera2PreviewEngine.DEFAULT_TARGET_FPS &&
            it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020
    } ?: minWithOrNull(
        compareBy<com.librestatic.opencinecam.camera.Camera2LogProfile> {
            if (it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020) 0 else 1
        }.thenBy {
            kotlin.math.abs(it.size.width.toLong() * it.size.height - width.toLong() * height)
        }.thenBy { kotlin.math.abs(it.fps - fps) },
    )

    companion object {
        /** The `kind` of a camera check's result row ([com.librestatic.opencinecam.rawvideo.RawVideoQualifier]'s CAMERA stage). */
        private const val RAW_QUALIFICATION_CAMERA_KIND = "camera"
        private const val LOST_INPUT_STOP_ATTEMPTS = 20
        private const val LOST_INPUT_STOP_RETRY_MS = 250L
        private const val OWNER_ID = "capture-service"
        private const val RECORDING_TICK_MS = 500L
        private const val CAMERA_SWITCH_TIMEOUT_MS = 6_000L
        private const val PREVIEW_RECOVERY_TIMEOUT_MS = 4_000L
        /** How long a qualification waits for the engine to close the camera before opening it. */
        private const val RAW_QUALIFICATION_RELEASE_WAIT_MS = 600L
        // High-speed sessions on some devices take ~2-3 s to open; 12 s is well past any healthy start.
        private const val PREVIEW_START_TIMEOUT_MS = 12_000L
        private const val PREVIEW_WATCHDOG_INTERVAL_MS = 1_000L
    }
}

/** HLG takes are monitored through the Rec.709 view assist; the flat OCLog2 view is LOG-only. */
private fun CameraSettings.previewViewAssist(mode: CaptureMode): Boolean =
    logViewAssistEnabled || mode == CaptureMode.HLG
