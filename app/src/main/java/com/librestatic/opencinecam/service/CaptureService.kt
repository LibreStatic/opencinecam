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

import com.librestatic.opencinecam.recordingTimecodeJson
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
import com.librestatic.opencinecam.SubjectDisplayMode
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
import android.os.StatFs
import android.util.Size
import android.view.Surface
import com.librestatic.opencinecam.timelapseProjectRate
import com.librestatic.opencinecam.videoProjectRate
import com.librestatic.opencinecam.projectLabel
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
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
import com.librestatic.opencinecam.core.model.CaptureStateMachine
import com.librestatic.opencinecam.core.model.SerializedCaptureActor
import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.ProductionSlateSettings
import com.librestatic.opencinecam.afterSavedProductionSlate
import com.librestatic.opencinecam.storage.StillImageSaver
import com.librestatic.opencinecam.storage.VideoOutput
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
import com.librestatic.opencinecam.camera.PcmListeningSink
import com.librestatic.opencinecam.AudioListeningStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.flow.update
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
    private val stateCommands = CaptureStateCommands(OWNER_ID)
    private val cameraState = MutableStateFlow(CameraUiState())
    private val localBinder = LocalBinder()
    private val foreground = RecordingForegroundController(this)
    private val previewEngine by lazy { Camera2PreviewEngine(this) }
    private val stillSaver by lazy { StillImageSaver(this) }
    // Take-owned fields are written on main before dispatch and detached by engine callbacks.
    @Volatile private var videoOutput: VideoOutput? = null
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

    private class DetachedTake(
        val video: VideoOutput?,
        val audio: AudioSidecarRecorder?,
        val publication: CaptureTransferPublication?,
        val audioStartFailure: String?,
        val geometry: RecordingGeometry?,
    )

    /** Engine callbacks and main detach the take-owned outputs exactly once; the caller finishes them. */
    private fun detachTake(): DetachedTake = synchronized(takeOwnershipLock) {
        DetachedTake(videoOutput, audioSidecarRecorder, activeTransferPublication, audioStartFailure, activeRecordingGeometry).also {
            videoOutput = null
            audioSidecarRecorder = null
            activeTransferPublication = null
            audioStartFailure = null
            activeRecordingGeometry = null
        }
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
    @Volatile private var audioListeningController: AudioListeningController? = null
    private val latestListeningStatus = AtomicReference<AudioListeningStatus?>(null)
    private var audioListeningRetirement = CompletableFuture.completedFuture(Unit)
    // Owned on main; even a failed factory keeps its native cleanup receipt here.
    private var previewAudioRetirement = CompletableFuture.completedFuture(Unit)
    private var previewAudioGeneration = 0L
    @Volatile private var audioStartFailure: String? = null
    private var activeRecordingAudioLabel: String = "no audio"
    private var activeRecordingGain: DigitalRecordingGain? = null
    private val audioLevelEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var recordingStartedAtMs = 0L
    /** OCC-PLAN-068 U6: optional operator-side sync-marker evidence for the active take's sidecar. */
    private val subjectSyncMarkers = com.librestatic.opencinecam.SubjectSyncMarkerSlot()
    private var timelapseAutoStopRunnable: Runnable? = null
    private var attachedPreviewSurface: Surface? = null
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
    private val recordingTicker = object : Runnable {
        override fun run() {
            if (cameraState.value.phase != CameraUiPhase.RECORDING) return
            previewEngine.encodedRecordingProgress()?.let(timecodeTracker::observeEncodedProgress)
            cameraState.value = cameraState.value.copy(
                recordingElapsedMs = activeRecordingElapsedMs(),
                availableStorageBytes = runCatching { StatFs(filesDir.absolutePath).availableBytes }.getOrNull(),
                timecodeDisplay = timecodeTracker.currentDisplayTc()?.format(),
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
        val timelapseWidth: Int = 1920,
        val timelapseHeight: Int = 1080,
    ) {
        fun width(mode: CaptureMode): Int = when (mode) { CaptureMode.LOG -> logWidth; CaptureMode.TIME_LAPSE -> timelapseWidth; else -> videoWidth }
        fun height(mode: CaptureMode): Int = when (mode) { CaptureMode.LOG -> logHeight; CaptureMode.TIME_LAPSE -> timelapseHeight; else -> videoHeight }
        fun fps(mode: CaptureMode): Int = when (mode) { CaptureMode.LOG -> logFps; CaptureMode.TIME_LAPSE -> 30; else -> videoFps }
    }

    @Volatile private var currentOperatorLut: MonitorLut? = null
    @Volatile private var currentSubjectLut: MonitorLut? = null
    @Volatile private var currentRecordingLut: MonitorLut? = null
    @Volatile private var recordingLutLibrary: com.librestatic.opencinecam.LutLibrary? = null
    private data class RecordingLutIntent(val owner: CaptureTransferReservation, val lut: MonitorLut?, val captureNames: CaptureNameSnapshot) {
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
            val waiting = cameraState.value.phase in setOf(CameraUiPhase.OPENING, CameraUiPhase.READY) &&
                attachedPreviewSurface?.isValid == true
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
            val recordingReport = try { com.librestatic.opencinecam.storage.RecordingCaptureRecovery(this).recover() } catch (failure: Exception) {
                android.util.Log.e("RecordingRecovery", "Interrupted take journal unavailable", failure)
                com.librestatic.opencinecam.storage.RecordingRecoveryReport(unresolvedGroups = 1)
            }
            mainHandler.post {
                if (!serviceDestroyed) cameraState.value = cameraState.value.copy(stillRecovery = report, recordingRecovery = recordingReport)
            }
        }
        physicalOrientationTracker = PhysicalOrientationTracker(this)
        physicalOrientationTracker.start()
        foldClosureTracker = FoldClosureTracker(this) {
            if (!settings.subjectDisplay.continueRecordingOnFold) localBinder.stopRecording()
        }.also { tracker -> cameraState.value = cameraState.value.copy(foldClosureSensorAvailable = tracker.start()) }
        val repository = SettingsRepositories.get(this)
        repository.update { it.forOperatorStartup() }
        localBinder.applySettings(repository.states.value)
        settingsScope.launch { repository.states.collect { localBinder.applySettings(it) } }
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
            current.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG) &&
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
            if (!settings.audioEnabled || cameraState.value.selectedMode !in setOf(CaptureMode.VIDEO, CaptureMode.LOG)) {
                cameraState.update { it.copy(audioLevels = null, audioMonitoringActive = false) }
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
                    val monitor = PreviewAudioMonitor.create(
                        this,
                        cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings,
                        onLevel,
                        onFailure = { failure ->
                            mainHandler.post failedAudio@ {
                                if (serviceDestroyed || audioLevelEpoch.get() != epoch) return@failedAudio
                                stopPreviewAudioMonitor(clearLevels = false)
                                cameraState.update { it.copy(audioMonitoringActive = false,
                                    message = "Audio monitor unavailable: ${failure.message}") }
                            }
                        },
                        listeningSink = newAudioListeningSink(),
                    )
                    previewAudioMonitor = monitor
                    monitor.start()
                } catch (failure: Throwable) {
                    if (failure is PreviewAudioMonitorCreationFailure) rememberPreviewAudioRetirement(failure.retirement)
                    stopPreviewAudioMonitor(clearLevels = false)
                    cameraState.update { it.copy(audioMonitoringActive = false,
                        message = "Audio monitor unavailable: ${failure.message}") }
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
                        if (attachedPreviewSurface == null && cameraState.value.phase != CameraUiPhase.RECORDING) localBinder.detachPreview()
                    }
                }
            }
        }
    }

    private fun photoPreviewMode(mode: CaptureMode = cameraState.value.selectedMode): Boolean =
        mode in setOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL)

    private fun desiredGpuViewfinder(mode: CaptureMode = cameraState.value.selectedMode): Boolean =
        mode in setOf(CaptureMode.LOG, CaptureMode.TIME_LAPSE) ||
        // The subject preview rides the GPU viewfinder in every mode it supports. A constrained
        // high-speed take still reaches the encoder directly (startVideo); only the 30 fps preview
        // share goes through the GPU to the operator and subject windows.
        (settings.subjectDisplay.mode == SubjectDisplayMode.PREVIEW && subjectPreviewBlock(mode) == null) ||
        // Off-speed conforms frames on the GPU only at regular rates: in a constrained high-speed
        // session Camera2 feeds any non-encoder surface 30 fps, so those takes go to the encoder directly.
        (mode == CaptureMode.VIDEO && ((settings.videoOffSpeed && cameraState.value.activeVideoProfile?.constrainedHighSpeed != true) ||
            currentOperatorLut != null || currentRecordingLut != null || recordingLutIntent.get()?.lut != null)) ||
        (photoPreviewMode(mode) && currentOperatorLut != null)

    private fun selectedStillPhotoFormat(mode: CaptureMode = cameraState.value.selectedMode): StillPhotoFormat = when (mode) {
        CaptureMode.PHOTO -> settings.photoFormat
        CaptureMode.RAW_PHOTO -> StillPhotoFormat.DNG
        else -> StillPhotoFormat.JPEG
    }

    /** RAW photo keeps the camera's own companion size; the DNG is the sensor readout either way. */
    private fun selectedStillSize(): Size? = cameraState.value.takeIf { it.selectedMode != CaptureMode.RAW_PHOTO }
        ?.activeStillSize?.let { (width, height) -> Size(width, height) }

    private fun previewKey(): List<Any?> = cameraState.value.let {
        listOf(it.selectedCameraId, it.selectedMode, it.targetVideoWidth, it.targetVideoHeight, it.targetFps, desiredGpuViewfinder(), selectedStillPhotoFormat(), selectedStillSize())
    }

    private fun updateSubjectTarget() {
        // Invalidate receipts queued on main even when the same camera/lease survives a mode round trip.
        val epoch = subjectPreviewEpoch.incrementAndGet()
        if (subjectPortClosed) return
        val lease = subjectSurfaces.current ?: return
        val block = subjectPreviewBlock(cameraState.value.selectedMode)
        if (block != null || settings.subjectDisplay.mode != SubjectDisplayMode.PREVIEW) {
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
                    subjectPreviewBlock(state.selectedMode) == null && settings.subjectDisplay.mode == SubjectDisplayMode.PREVIEW
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
        previewEngine.setProfessionalControls(settings.exposure, settings.whiteBalance, settings.imageProcessing)
    }

    private val previewListener = object : Camera2PreviewListener {
        override fun onProfessionalControlsRejected(message: String) {
            mainHandler.post { cameraState.value = cameraState.value.copy(message = getString(com.librestatic.opencinecam.R.string.pro_controls_rejected, message)) }
        }
        override fun onPreviewSurfaceLost(message: String) {
            mainHandler.post {
                cancelCountdown()
                cameraState.value = cameraState.value.copy(message = getString(R.string.operator_preview_lost))
            }
        }
        override fun onOpening(descriptor: Camera2CameraDescriptor) {
            subjectPreviewEpoch.incrementAndGet()
            mainHandler.post { cancelCountdown(); subjectStatus.value = SubjectPreviewStatus() }
            activePreviewKey = null
            mainHandler.post { updateSubjectTarget() }
            stopPreviewAudioMonitor(clearLevels = true)
            cameraState.update { it.copy(
                phase = CameraUiPhase.OPENING,
                gpuViewfinder = desiredGpuViewfinder(),
                torchEnabled = false,
                torchReported = null,
                photoFlashReport = null,
                torchStrengthReported = null,
                selectedCameraId = descriptor.cameraId,
                histogram = emptyList(),
                redHistogram = emptyList(),
                greenHistogram = emptyList(),
                blueHistogram = emptyList(),
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

        override fun onMetadata(metadata: Camera2PreviewMetadata) {
            cameraState.update { it.copy(
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
                reportedCropRegion = metadata.cropRegion,
                submittedImageProcessing = metadata.submittedImageProcessing,
                reportedExposureMode = metadata.exposureMode,
                reportedAntibanding = metadata.antibanding,
                torchEnabled = metadata.torchEnabled == true,
                torchReported = metadata.torchEnabled,
                torchStrengthReported = metadata.torchStrengthLevel,
                effectiveFps = metadata.effectiveFps,
            ) }
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
                        val uri = stillSaver.saveJpeg(bytes, productionSlate, captureNames)
                        incrementSavedSlate(productionSlate)
                        mainHandler.post {
                            if (serviceDestroyed) return@post
                            cameraState.update { it.copy(phase = CameraUiPhase.SAVED, lastSavedUri = uri.toString(),
                                message = "JPEG saved: ${width}×$height", errorCode = null) }
                            localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
                        }
                    } catch (failure: Throwable) {
                        fail("jpeg-save-failed", failure.message ?: "JPEG could not be saved.")
                    }
                }
            } catch (_: RejectedExecutionException) {
                fail("jpeg-save-busy", "The still-image writer is busy.")
            }
        }

        override fun onStillCaptured(capture: CapturedStill) {
            val owner = stillOwner.get() ?: return
            if (serviceDestroyed || !owner.publishing.compareAndSet(false, true)) return
            try {
                storageExecutor.execute {
                    try {
                        val publication = stillSaver.saveCapture(capture, owner.productionSlate, owner.captureNames)
                        incrementSavedSlate(owner.productionSlate)
                        val preview = publication.images.firstOrNull { it.kind != StillImageKind.DNG } ?: publication.images.first()
                        mainHandler.post {
                            if (serviceDestroyed || !stillOwner.compareAndSet(owner, null)) return@post
                            cameraState.update { it.copy(phase = CameraUiPhase.SAVED, stillCapturePending = false,
                                lastSavedUri = preview.uri, lastStillPublication = publication,
                                photoFlashReport = capture.flashReport, errorCode = null,
                                message = getString(R.string.photo_capture_saved, publication.images.joinToString(" + ") { image -> image.kind.name })) }
                            localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
                            if (activePreviewKey == null && cameraState.value.phase != CameraUiPhase.OPENING) attachedPreviewSurface?.takeIf { it.isValid }?.let {
                                localBinder.attachPreview(it, attachedPreviewRotationDegrees)
                            }
                        }
                    } catch (failure: Throwable) {
                        mainHandler.post { finishStillFailure(owner, "still-save-failed", failure.message ?: "The still capture could not be saved.") }
                    }
                }
            } catch (_: RejectedExecutionException) {
                mainHandler.post { finishStillFailure(owner, "still-save-busy", "The still-image writer is busy.") }
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
                        val publication = stillSaver.saveBurst(capture, owner.productionSlate, owner.captureNames)
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
                        mainHandler.post { finishStillFailure(owner, "burst-save-failed", failure.message ?: "The burst could not be saved.") }
                    }
                }
            } catch (_: RejectedExecutionException) {
                mainHandler.post { finishStillFailure(owner, "burst-save-busy", "The still-image writer is busy.") }
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
                        val publication = stillSaver.saveBracket(capture, owner.productionSlate, owner.captureNames)
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
                        mainHandler.post { finishStillFailure(owner, "bracket-save-failed", failure.message ?: "The bracket could not be saved.") }
                    }
                }
            } catch (_: RejectedExecutionException) {
                mainHandler.post { finishStillFailure(owner, "bracket-save-busy", "The still-image writer is busy.") }
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
                        val publication = stillSaver.saveAccumulation(capture, owner.productionSlate, owner.captureNames)
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
                        mainHandler.post { finishStillFailure(owner, "accumulation-save-failed", failure.message ?: "The accumulation could not be saved.") }
                    }
                }
            } catch (_: RejectedExecutionException) {
                mainHandler.post { finishStillFailure(owner, "accumulation-save-busy", "The still-image writer is busy.") }
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
                        val uri = stillSaver.saveDng(bytes, productionSlate, captureNames)
                        incrementSavedSlate(productionSlate)
                        mainHandler.post {
                            if (serviceDestroyed) return@post
                            cameraState.update { it.copy(phase = CameraUiPhase.SAVED, lastSavedUri = uri.toString(),
                                message = "DNG saved: ${width}×$height", errorCode = null) }
                            localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value)
                        }
                    } catch (failure: Throwable) {
                        fail("dng-save-failed", failure.message ?: "DNG could not be saved.")
                    }
                }
            } catch (_: RejectedExecutionException) {
                fail("dng-save-busy", "The still-image writer is busy.")
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
            submitCaptureState(stateCommands.takeStarted())
            timecodeTracker.onRecordingStarted(previewEngine.encodedRecordingProgress()?.takeId)
            previewEngine.encodedRecordingProgress()?.let(timecodeTracker::observeEncodedProgress)
            previewEngine.consumeTimelapsePauseStatus()
            previewEngine.consumeCaptureEpochReport()
            recordingStartedAtMs = android.os.SystemClock.elapsedRealtime()
            subjectSyncMarkers.begin(android.os.SystemClock.elapsedRealtimeNanos())
            audioSidecarRecorder?.let { sidecar ->
                try {
                    sidecar.start()
                } catch (failure: Throwable) {
                    synchronized(takeOwnershipLock) {
                        // A concurrent failure/recovery may already own (and finish) this sidecar.
                        if (audioSidecarRecorder === sidecar) {
                            audioStartFailure = failure.message ?: "Lossless audio could not start."
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
                message = if (cameraState.value.selectedMode == CaptureMode.LOG) {
                    val source = if (cameraState.value.activeLogProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                        "HFR ISP-derived · source depth not claimed"
                    } else "HLG-derived 10-bit"
                    "REC ${width}×$height · ${if (recordingLutIntent.get()?.lut != null) "LUT → SDR BT.709" else "OCLog2"} · HEVC Main10 · $source · ${cameraState.value.targetFps} fps · $activeRecordingAudioLabel"
                } else if (cameraState.value.selectedMode == CaptureMode.TIME_LAPSE) {
                    val intervalLabel = formatTimelapseInterval(cameraState.value.timelapseIntervalMs)
                    getString(R.string.timelapse_recording, intervalLabel, cameraState.value.recordingProjectRate?.projectLabel() ?: settings.timelapseProjectRate.projectLabel())
                } else {
                    "REC ${width}×$height · H.264 · $activeRecordingAudioLabel" +
                        cameraState.value.recordingProjectRate?.let { " · " + getString(R.string.project_active_rate, it.projectLabel()) }.orEmpty()
                },
                recordingElapsedMs = 0L,
                recordingPauseStatus = null,
                recordingAvTiming = null,
                recordingPausePending = false,
                recordingWidth = width,
                recordingHeight = height,
                availableStorageBytes = runCatching { StatFs(filesDir.absolutePath).availableBytes }.getOrNull(),
                errorCode = null,
            )
            scheduleTimelapseAutoStop()
            mainHandler.post(recordingTicker)
        }

        override fun onRecordingStopped(success: Boolean) {
            android.util.Log.i("CaptureService", "Recording stopped: success=$success mode=${cameraState.value.selectedMode} fps=${cameraState.value.targetFps}")
            retireAudioLevelConsumer()
            val recordingGain = activeRecordingGain
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
            previewEngine.encodedRecordingProgress()?.let(timecodeTracker::observeEncodedProgress)
            val timecodeReport = timecodeTracker.recordingReport()
            val subjectSyncMarker = subjectSyncMarkers.consume()
            try {
                val requestedAudioFailure = take.audioStartFailure
                val audioOutput = take.audio
                val logEvidence = previewEngine.consumeLastOpenCineLogEvidence()
                val bakedEvidence = previewEngine.consumeLastRecordingLutEvidence()
                val bakingVerified = bakedEvidence?.selectionId == monitorLutIdentity(frozenLut)
                val finalizedSuccess = success && nativeRetired && requestedAudioFailure == null && bakingVerified &&
                    (cameraState.value.selectedMode != CaptureMode.LOG || logEvidence != null)
                val geometry = take.geometry
                var registrationCreationFailed = transferPublication == null || transferPublication.registrationFailed
                val publicationObserver = transferPublication?.observer ?: try { CapturePublicationJournal(this@CaptureService) }
                    catch (problem: Throwable) {
                        registrationCreationFailed = true
                        android.util.Log.e("CapturePublication", "Publication journal creation failed; capture remains independent", problem)
                        null
                    }
                val finalization = finalizePreparedRecordingTake<
                    com.librestatic.opencinecam.storage.PreparedVideoOutput,
                    com.librestatic.opencinecam.storage.PreparedAudioSidecar,
                    android.net.Uri,
                    com.librestatic.opencinecam.storage.AudioSidecarRecordingResult,
                >(
                    success = finalizedSuccess,
                    prepareAudio = audioOutput?.let { sidecar -> {
                        sidecar.prepareCompletion().also { prepared ->
                            val result = prepared?.result
                            if (result != null) avTiming = sidecar.captureClock?.report(avTiming?.videoEncoderFirstPtsUs, null, null)
                                ?.copy(submittedPcmFrames = result.frames, audioStorage = "SEPARATE_${result.container}") ?: avTiming
                            lastSeparateAudioAvDrift = result?.let {
                                separateAudioAvDrift(sidecar.captureClock?.cameraRealtime == true, it.captureTiming, it.displayName)
                            }?.also { android.util.Log.i("AvClockDiagnostics", separateAudioAvDriftLog(it)) }
                        }
                    } },
                    discardAudio = { audioOutput?.finish(false); Unit },
                    discardVideo = { output?.finish(false); Unit },
                    publishAudio = { _ -> audioOutput?.publishPrepared() },
                    publishVideo = { _ -> output?.publishPrepared() },
                    prepareVideo = { output?.prepareCompletion(
                        run {
                            val metadata = logEvidence?.copy(avTiming = avTiming)?.let { logSidecarJson(it, timecodeReport) }
                                ?: avTiming?.let { JSONObject().put("schema", "opencinecam.av-timing.v1").put("avTiming", captureEpochJson(it)).toString(2) }
                                ?: timing?.let { timelapseTimingJson(it, requireNotNull(cameraState.value.recordingProjectRate)) }
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
                            subjectSyncMarker?.let { marker ->
                                (withTimecode?.let(::JSONObject) ?: JSONObject().put("schema", "opencinecam.recording-timing.v1"))
                                    .put(com.librestatic.opencinecam.SUBJECT_SYNC_MARKER_KEY,
                                        JSONObject(com.librestatic.opencinecam.subjectSyncMarkerJson(marker).toString())).toString(2)
                            } ?: withTimecode
                        },
                        expectedGeometry = geometry,
                        timingSidecar = logEvidence == null && (bakedEvidence != null || recordingGain?.enabled == true || timing != null || avTiming != null || timecodeReport?.config?.enabled == true || subjectSyncMarker != null),
                    ) },
                    onPrepared = { video, audio -> publicationObserver?.onPrepared(video.artifacts + audio?.artifacts.orEmpty()); Unit },
                    onPublished = { video, audio -> publicationObserver?.onPublished(video.artifacts + audio?.artifacts.orEmpty()); Unit },
                    onAborted = { publicationObserver?.onAborted(); Unit },
                )
                val completedTake = finalization.outputs
                finalization.registrationFailures.forEach { problem ->
                    android.util.Log.e("CapturePublication", "Publication journal update failed; originals retained", problem)
                }
                val registrationFailed = registrationCreationFailed || finalization.registrationFailures.isNotEmpty() || transferPublication?.registrationFailed == true
                timecodeReport?.let { timecodeTracker.onRecordingStopped(completedTake != null, it.lifecycleToken) }
                val uri = completedTake?.first
                val sidecarResult = completedTake?.second
                val registrationNotice = if (completedTake == null) "" else buildString {
                    if (transferPublication?.registered == true) append(" · " + getString(R.string.webdav_queue_registered))
                    if (registrationFailed) append(" · " + getString(if (transferPublication?.registrationFailed == true)
                        R.string.webdav_queue_registration_failed else R.string.capture_publication_registration_failed))
                }
                if (uri != null) output?.productionSlate?.let(::incrementSavedSlate)
                // A SAVED observer must see the registration outcome in the same state emission.
                cameraState.update { current -> current.copy(
                    phase = if (finalizedSuccess) CameraUiPhase.SAVED else CameraUiPhase.ERROR,
                    recordingFinalizing = false,
                    recordingPauseStatus = timing,
                    recordingAvTiming = avTiming,
                    recordingPausePending = false,
                    recordingWhiteBalanceStatus = RecordingWhiteBalanceStatus.IDLE,
                    lastSavedUri = uri?.toString(),
                    message = (if (finalizedSuccess) {
                        val audioSaved = sidecarResult?.let { " + ${it.bitDepth.bits}-bit ${it.container} ${it.sampleRateHz / 1000.0} kHz" }
                            ?: if (activeRecordingAudioLabel.startsWith("AAC")) " + $activeRecordingAudioLabel" else ""
                        if (logEvidence != null) {
                            val source = if (logEvidence.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                                "HFR ISP-derived"
                            } else "HLG-derived 10-bit"
                            val outputColor = if (bakedEvidence != null) "LUT → SDR BT.709" else "OCLog2"
                            "$outputColor Main10 saved · $source + provenance$audioSaved · ${durationMs / 1000.0}s" + captureEpochLabel(avTiming)
                        }
                        else "Video saved$audioSaved · ${durationMs / 1000.0}s" + captureEpochLabel(avTiming)
                    } else requestedAudioFailure ?: "Video could not be finalized with verified provenance.") + registrationNotice,
                    errorCode = if (finalizedSuccess) null else if (requestedAudioFailure != null) "audio-start-failed" else "video-finalize-failed",
                    recordingElapsedMs = durationMs,
                ) }
            } catch (failure: Throwable) {
                runCatching { take.audio?.finish(false) }
                runCatching { output?.finish(false) }
                fail("video-save-failed", failure.message ?: "Video could not be saved.")
            } finally {
                try { transferPublication?.close() } catch (problem: Exception) {
                    android.util.Log.e("CapturePublication", "Optional queue handle cleanup failed", problem)
                }
                timecodeReport?.let { timecodeTracker.onRecordingStopped(false, it.lifecycleToken) }
                releaseTransferCapture(transferReservation)
            }
            if (timecodeTracker.continuationStorageFailed) cameraState.value = cameraState.value.copy(
                message = cameraState.value.message.orEmpty() + " · " + getString(R.string.timecode_continuity_failed))
            foreground.stop()
            // Drop only the started-service lifetime; a bound operator/exterior keeps the service.
            stopSelf()
            mainHandler.post { localBinder.applySettings(SettingsRepositories.get(this@CaptureService).states.value) }
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
                zoomRatio = perCameraZoom[current.selectedCameraId] ?: 1f,
            )
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
                message = "Zoom $requested× not accepted; kept ${accepted}×",
                messageTransient = true,
            )
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

        override fun onFailure(code: String, message: String, recoverable: Boolean) {
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
                take.video?.finish(false)
                abortTransferPublication(take.publication)
                runCatching { take.audio?.finish(false) }
                foreground.stop()
                fail(code, message)
                retireRecordingRecoveryGuards(retirement)
                stopSelf()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
            else mainHandler.post { if (!serviceDestroyed) cleanup.run() }
        }
    }

    inner class LocalBinder : Binder() {
        fun reconnectAudioListening() {
            if (serviceDestroyed) return
            audioListeningController?.configure(settings.audioListening, settings.audioListeningOutputDeviceId)
            audioListeningController?.reconnect()
        }

        val states: StateFlow<CaptureState> = state.asStateFlow()
        val cameraStates: StateFlow<CameraUiState> = cameraState.asStateFlow()
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
                        CaptureMode.SLOW_MOTION to if (com.librestatic.opencinecam.supportsSlowMotion(preferred.videoProfiles.map { it.toSpec() })) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                    ),
                    errorCode = null,
                    message = null,
                )
            }
            if (operatorStartupPending && preferred != null) {
                operatorStartupPending = false
                val saved = getSharedPreferences("operator-session", MODE_PRIVATE).getString("last-mode", null)
                val last = CaptureMode.entries.firstOrNull { it.name == saved }
                val available = cameraState.value.modeGates.filterValues { it == com.librestatic.opencinecam.ModeGateState.AVAILABLE }.keys
                selectMode(settings.operation.startupCaptureMode(last, available), reopen = false)
            }
            refreshAudioCapabilities()
        }

        /** Fully recovers the retained viewfinder after a capture or permission failure. */
        fun recoverPreview(targetWidth: Int, targetHeight: Int) {
            val token = ++recoveryGeneration
            mainHandler.removeCallbacks(recordingTicker)
            val take = detachTake()
            runCatching { take.audio?.finish(false) }
            runCatching { take.video?.finish(false) }
            abortTransferPublication(take.publication)
            foreground.stop()
            previewEngine.stopPreview()
            submitCaptureState(stateCommands.takeEnded() + stateCommands.previewStopped())
            val retirement = previewEngine.recordingOutputRetirement()
            retireRecordingRecoveryGuards(retirement)
            releaseFailedTakeStart(retirement)
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
            if (serviceDestroyed) return
            try {
                storageExecutor.execute {
                    val capabilities = runCatching { AndroidProfessionalAudioProbe(this@CaptureService).probe() }
                        .getOrElse {
                            val message = "Audio capability probe failed: ${it.message}"
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
                        val repository = SettingsRepositories.get(this@CaptureService)
                        repository.update { it.normalizedFor(capabilities) }
                        localBinder.applySettings(repository.states.value)
                    }
                }
            } catch (_: RejectedExecutionException) {
                mainHandler.post {
                    if (!serviceDestroyed) cameraState.update { it.copy(message = "Audio capability probe is busy.") }
                }
            }
        }

        fun attachPreview(surface: Surface, displayRotationDegrees: Int): Boolean {
            val descriptor = cameraState.value.descriptor ?: return false
            if (!surface.isValid) return false
            attachedPreviewSurface = surface
            attachedPreviewRotationDegrees = displayRotationDegrees
            if (cameraState.value.structuralSettingsFrozen) {
                return previewEngine.attachPreviewWhileRecording(surface, displayRotationDegrees)
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
                openCineLog = cameraState.value.selectedMode == CaptureMode.LOG,
                gpuPreview = desiredGpuViewfinder() && cameraState.value.selectedMode != CaptureMode.LOG,
                gpuPhotoPreview = photoPreviewMode(cameraState.value.selectedMode),
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
            cancelCountdown()
            cancelRecordingWhiteBalancePreparation()
            stopPreviewAudioMonitor(clearLevels = true)
            attachedPreviewSurface = null
            val photoPreparing = cameraState.value.phase == CameraUiPhase.CAPTURING && cameraState.value.selectedMode in setOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL)
            val ownedStill = stillOwner.get()
            if (photoPreparing && previewEngine.cancelStill() && ownedStill != null) {
                stillOwner.compareAndSet(ownedStill, null)
                cameraState.update { it.copy(stillCapturePending = stillOwner.get() != null) }
            }
            if (cameraState.value.structuralSettingsFrozen && !photoPreparing) {
                previewEngine.detachPreviewWhileRecording()
                return
            }
            // A live subject lease keeps the GPU graph (photo modes included) for the cover window.
            if (subjectSurfaces.current != null && settings.subjectDisplay.mode == SubjectDisplayMode.PREVIEW &&
                subjectPreviewBlock(cameraState.value.selectedMode) == null &&
                previewEngine.detachOperatorFromActiveGpuPreview()) return
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
            if (current.structuralSettingsFrozen) return
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
                    CaptureMode.SLOW_MOTION to if (com.librestatic.opencinecam.supportsSlowMotion(descriptor.videoProfiles.map { it.toSpec() })) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
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
                stillFormat = selectedStillPhotoFormat(),
                stillSize = selectedStillSize(),
                openCineLog = cameraState.value.selectedMode == CaptureMode.LOG,
                gpuPreview = desiredGpuViewfinder() && cameraState.value.selectedMode != CaptureMode.LOG,
                gpuPhotoPreview = photoPreviewMode(cameraState.value.selectedMode),
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
                        stillFormat = selectedStillPhotoFormat(),
                        stillSize = selectedStillSize(),
                        openCineLog = cameraState.value.selectedMode == CaptureMode.LOG,
                        gpuPreview = desiredGpuViewfinder() && cameraState.value.selectedMode != CaptureMode.LOG,
                        gpuPhotoPreview = photoPreviewMode(cameraState.value.selectedMode),
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

        fun selectMode(mode: CaptureMode, reopen: Boolean = true) {
            cancelCountdown()
            if (cameraState.value.structuralSettingsFrozen) return
            val current = cameraState.value
            if (!CameraUiState.isModeSelectable(current.modeGates[mode])) {
                cameraState.value = current.copy(message = getString(R.string.gate_requires_preflight, modeDisplayName(mode)))
                return
            }
            val changedSignalPath =
                desiredGpuViewfinder(current.selectedMode) != desiredGpuViewfinder(mode) ||
                selectedStillPhotoFormat(current.selectedMode) != selectedStillPhotoFormat(mode) ||
                (current.selectedMode == CaptureMode.LOG) != (mode == CaptureMode.LOG) ||
                    (settings.subjectDisplay.mode == SubjectDisplayMode.PREVIEW && (current.selectedMode == CaptureMode.VIDEO) != (mode == CaptureMode.VIDEO)) ||
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
            previewEngine.setTorch(settings.flashEnabled, settings.torchStrengthLevel)
            cameraState.value = cameraState.value.copy(
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
            )
            getSharedPreferences("operator-session", MODE_PRIVATE).edit().putString("last-mode", mode.name).apply()
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
                        openCineLog = mode == CaptureMode.LOG,
                        gpuPreview = desiredGpuViewfinder() && mode != CaptureMode.LOG,
                        gpuPhotoPreview = photoPreviewMode(mode),
                        viewAssist = settings.logViewAssistEnabled,
                        targetFps = cameraState.value.targetFps,
                        videoProfile = cameraState.value.activeVideoProfile.takeIf {
                            mode in CameraUiState.videoProfileModes
                        },
                        logProfile = cameraState.value.activeLogProfile.takeIf { mode == CaptureMode.LOG },
                    )
                    }
                    // As in selectTargetFps: let the panel apply its high refresh-rate vote before a
                    // constrained high-speed stream starts.
                    if (cameraState.value.activeVideoProfile?.constrainedHighSpeed == true && mode in CameraUiState.videoProfileModes) {
                        mainHandler.postDelayed(start, 350L)
                    } else start.run()
                }
            }
        }

        fun selectTargetFps(fps: Int) {
            cancelCountdown()
            val current = cameraState.value
            if (current.structuralSettingsFrozen) return
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
                // The high-speed reopen is deferred; a take admitted meanwhile owns the graph.
                if (cameraState.value.targetFps == effectiveFps && surface.isValid && !cameraState.value.structuralSettingsFrozen) {
                    previewEngine.startPreview(
                        descriptor,
                        surface,
                        attachedPreviewRotationDegrees,
                        previewListener,
                        stillFormat = selectedStillPhotoFormat(),
                        stillSize = selectedStillSize(),
                        openCineLog = current.selectedMode == CaptureMode.LOG,
                        gpuPreview = desiredGpuViewfinder(current.selectedMode) && current.selectedMode != CaptureMode.LOG,
                        gpuPhotoPreview = photoPreviewMode(current.selectedMode),
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

        fun selectPhotoResolution(width: Int, height: Int) {
            val current = cameraState.value
            if (rejectLockedControl() || current.structuralSettingsFrozen || current.phase == CameraUiPhase.CAPTURING) return
            if (current.selectedMode !in CameraUiState.photoResolutionModes || current.selectedMode == CaptureMode.RAW_PHOTO) return
            val size = width to height
            if (size !in current.availableStillSizes || size == current.activeStillSize) return
            cameraState.update { it.copy(targetStillSize = size) }
            attachedPreviewSurface?.takeIf { it.isValid }?.let { attachPreview(it, attachedPreviewRotationDegrees) }
        }

        fun selectVideoResolution(width: Int, height: Int) {
            val current = cameraState.value
            if (current.structuralSettingsFrozen || current.selectedMode !in CameraUiState.resolutionProfileModes) return
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
                        stillFormat = selectedStillPhotoFormat(),
                        stillSize = selectedStillSize(),
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

        val subjectPreview: SubjectPreviewPort get() = subjectPreviewPort

        internal fun applySettings(requested: CameraSettings) {
            val previous = settings
            val wasLocked = cameraState.value.captureControlsLocked
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
            if (!wasLocked && cameraState.value.captureControlsLocked) previewEngine.cancelFocusPull()
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
            previewEngine.setFocusPeakingEnabled(settings.peakingEnabled)
            previewEngine.setSubjectFramingEnabled(settings.subjectDisplay.outOfFrameWarning)
            if (previous.logViewAssistEnabled != settings.logViewAssistEnabled) previewEngine.setOpenCineLogViewAssist(settings.logViewAssistEnabled)
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
                if (cameraState.value.gpuViewfinder != desiredGpuViewfinder() ||
                    (cameraState.value.selectedMode == CaptureMode.PHOTO && previous.photoFormat != settings.photoFormat)) {
                    attachedPreviewSurface?.takeIf { it.isValid }?.let { attachPreview(it, attachedPreviewRotationDegrees) }
                }
                val onlyAudioDisplayChanged = previous.copy(audioListening = settings.audioListening,
                    audioListeningOutputDeviceId = settings.audioListeningOutputDeviceId,
                    audioMeter = settings.audioMeter, productionSlate = settings.productionSlate,
                    gallery = settings.gallery, mediaSharing = settings.mediaSharing) == settings
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
            val target = preset.mode.takeIf { cameraState.value.modeGates[it] == com.librestatic.opencinecam.ModeGateState.AVAILABLE }
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

        fun stopRecording(): Boolean {
            cancelCountdown()
            if (cancelRecordingWhiteBalancePreparation()) return true
            if (cameraState.value.phase != CameraUiPhase.RECORDING || cameraState.value.recordingFinalizing) return false
            // Publish before dispatch: a fast finalization callback must not be overwritten as pending.
            cameraState.value = cameraState.value.copy(recordingFinalizing = true)
            val accepted = previewEngine.stopVideo()
            if (!accepted) cameraState.value = cameraState.value.copy(recordingFinalizing = false)
            else submitCaptureState(stateCommands.takeStopRequested())
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
            if (!selfRecordingRole) return false
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
            if (serviceDestroyed || stillOwner.get() != null) return false
            if (whiteBalancePrepared) {
                val reservation = transferCapture.get() ?: return false
                if (!reservation.isRetired) return false
                return captureAfterTransferRetirement(audioForThisTake, true, reservation)
            }
            if (cancelTransferPreparation()) return true
            val current = cameraState.value
            // STOP and WB cancellation remain immediate. Still-photo ownership is a separate H4 gate.
            if (recordingWbPreparing || current.phase == CameraUiPhase.RECORDING ||
                current.selectedMode !in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.TIME_LAPSE)) {
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
            val lut = admissionSelection.recording
            currentRecordingLut = lut
            if (lut != null && !com.librestatic.opencinecam.camera.monitorLutCompatible(lut, current.selectedMode != CaptureMode.LOG)) {
                cameraState.update { it.copy(recordingLutStatus = OperatorLutStatus(lut.cube.sha256,
                    OperatorLutState.INCOMPATIBLE_DOMAIN, selectionId = monitorLutIdentity(lut)),
                    message = getString(R.string.lut_recording_domain_mismatch), errorCode = "recording-lut-domain") }
                return false
            }
            val reservation = transferCoordinator().reserveCapture()
            check(transferCapture.compareAndSet(null, reservation))
            val captureNames = admittedCaptureNames()
            recordingLutIntent.set(RecordingLutIntent(reservation, lut, captureNames))
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
            if (current.phase == CameraUiPhase.RECORDING && current.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.TIME_LAPSE, CaptureMode.LOG)) {
                return stopRecording()
            }
            if (current.phase != CameraUiPhase.PREVIEWING && current.phase != CameraUiPhase.SAVED && !(whiteBalancePrepared && current.phase == CameraUiPhase.CAPTURING)) return false
            if (settings.operation.lockDuringTake) previewEngine.cancelFocusPull()
            if (!whiteBalancePrepared && settings.recordingWhiteBalance == RecordingWhiteBalancePolicy.LOCK_ON_RECORD &&
                current.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.TIME_LAPSE)) {
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
            return when (current.selectedMode) {
                CaptureMode.PHOTO, CaptureMode.RAW_PHOTO -> {
                    val owner = StillOwner(admittedCaptureNames())
                    if (!stillOwner.compareAndSet(null, owner)) return false
                    settings = settings.copy(productionSlate = owner.productionSlate, captureNaming = owner.captureNames.settings)
                    cameraState.value = current.copy(phase = CameraUiPhase.CAPTURING, message = null,
                        photoFlashReport = null, lastStillPublication = null, stillCapturePending = true)
                    previewEngine.captureStill(selectedStillPhotoFormat(), settings.photoFlash, settings.photoQuality, aspect = settings.photoAspect).also { accepted ->
                        if (!accepted) finishStillFailure(owner, "still-capture-not-ready", "The selected still format is not ready on this camera.")
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
                        if (!accepted) finishStillFailure(owner, "burst-not-ready", "Burst capture is not ready.")
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
                        if (!accepted) finishStillFailure(owner, "bracket-not-ready", "The selected bracket is not available on this camera.")
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
                        if (!accepted) finishStillFailure(owner, "accumulation-not-ready", "Accumulation is not ready on this camera.")
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
                            fail("recording-geometry-unavailable", "Recording geometry is not available yet.")
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
                            fail("microphone-permission-required", "Grant microphone permission or disable audio before recording.")
                            return false
                        }
                        val audioSettings = cameraState.value.audioCapabilities
                            ?.let(settings::normalizedFor)
                            ?: settings
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
                        val output = VideoOutput.create(this@CaptureService, admittedSlate, captureNames)
                        videoOutput = output
                        admitTransferPublication()
                        val separateClock = if (embeddedAudio == null) com.librestatic.opencinecam.camera.CaptureEpochClock(
                            current.descriptor?.timestampSourceRealtime == true, audioSettings.audioSampleRateHz) else null
                        audioSidecarRecorder = when {
                            !audioRequested -> null
                            audioSettings.audioOutputFormat == AudioOutputFormat.WAV_PCM ->
                                WavAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings.copy(productionSlate = admittedSlate), captureClock = separateClock, onAudioLevel = onAudioLevel, recoveryGroup = output.recoveryGroup, listeningSink = listeningSink)
                            audioSettings.audioOutputFormat == AudioOutputFormat.FLAC ->
                                FlacAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings.copy(productionSlate = admittedSlate), captureClock = separateClock, onAudioLevel = onAudioLevel, recoveryGroup = output.recoveryGroup, listeningSink = listeningSink)
                            else -> null
                        }
                        activeRecordingAudioLabel = when {
                            !audioRequested -> "no audio"
                            embeddedAudio != null -> "AAC ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioBitrateKbps} kbps · ${audioSettings.audioChannels} ch"
                            else -> "${audioSettings.audioOutputFormat.name.substringBefore('_')} ${audioSettings.audioBitDepth.bits}-bit · ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioChannels} ch"
                        }
                        cameraState.update { it.copy(phase = CameraUiPhase.CAPTURING, recordingProjectRate = settings.videoProjectRate.takeIf { settings.videoOffSpeed },
                            message = "Preparing H.264 · $activeRecordingAudioLabel…") }
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
                        ).also { accepted ->
                            if (!accepted) {
                                // A preparation timeout may still own a duplicated native descriptor.
                                retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                                videoOutput = null
                                abortTransferPublication()
                                audioSidecarRecorder?.finish(false)
                                audioSidecarRecorder = null
                                output.finish(false)
                                foreground.stop()
                                if (cameraState.value.phase != CameraUiPhase.ERROR) fail("video-capture-not-ready", "The video surface is not ready.")
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { audioSidecarRecorder?.finish(false) }
                        audioSidecarRecorder = null
                        runCatching { videoOutput?.finish(false) }
                        videoOutput = null
                        abortTransferPublication()
                        foreground.stop()
                        retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                        fail("video-output-failed", failure.message ?: "Video output could not be created.")
                        false
                    }
                }
                CaptureMode.LOG -> {
                    try {
                        stopPreviewAudioMonitor(clearLevels = false)
                        cameraState.value = cameraState.value.copy(audioClipLatched = false, audioLevels = null)
                        val requestedAudioEnabled = audioForThisTake ?: settings.audioEnabled
                        val orientation = recordingOrientationDegrees()
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
                            anamorphicSqueeze = settings.anamorphicSqueeze,
                            anamorphicOutputMode = settings.anamorphicOutputMode,
                            supportsEncodedSize = ::hevcEncoderSupportsRaster,
                        )
                        activeRecordingGeometry = geometry
                        if (requestedAudioEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            fail("microphone-permission-required", "Grant microphone permission or disable audio before recording.")
                            return false
                        }
                        val audioSettings = cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings
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
                        val output = VideoOutput.create(this@CaptureService, admittedSlate, captureNames)
                        videoOutput = output
                        admitTransferPublication()
                        val separateClock = if (embeddedAudio == null) com.librestatic.opencinecam.camera.CaptureEpochClock(
                            current.descriptor?.timestampSourceRealtime == true, audioSettings.audioSampleRateHz) else null
                        audioSidecarRecorder = when {
                            !requestedAudioEnabled || embeddedAudio != null -> null
                            audioSettings.audioOutputFormat == AudioOutputFormat.WAV_PCM ->
                                WavAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings.copy(productionSlate = admittedSlate), captureClock = separateClock, onAudioLevel = onAudioLevel, recoveryGroup = output.recoveryGroup, listeningSink = listeningSink)
                            audioSettings.audioOutputFormat == AudioOutputFormat.FLAC ->
                                FlacAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings.copy(productionSlate = admittedSlate), captureClock = separateClock, onAudioLevel = onAudioLevel, recoveryGroup = output.recoveryGroup, listeningSink = listeningSink)
                            else -> error("Unsupported LOG audio format ${audioSettings.audioOutputFormat}.")
                        }
                        activeRecordingAudioLabel = when {
                            !requestedAudioEnabled -> "no audio"
                            embeddedAudio != null -> "AAC ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioBitrateKbps} kbps · ${audioSettings.audioChannels} ch"
                            else -> "${audioSettings.audioOutputFormat.name.substringBefore('_')} ${audioSettings.audioBitDepth.bits}-bit · ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioChannels} ch"
                        }
                        cameraState.update { it.copy(
                            phase = CameraUiPhase.CAPTURING,
                            recordingProjectRate = null,
                            message = if (current.activeLogProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                                "Preparing OCLog2 HFR · ${current.targetFps} fps · ISP SDR → GLES → HEVC Main10 · source depth not claimed · $activeRecordingAudioLabel…"
                            } else {
                                "Preparing OCLog2 · ${current.targetFps} fps · HLG10 → scene-linear BT.2020 → HEVC Main10 · $activeRecordingAudioLabel…"
                            },
                        ) }
                        dispatchTransferCapture()
                        reserveRecordingRecoveryGuard(output)
                        previewEngine.startOpenCineLogVideo(
                            output.descriptor,
                            recordingGeometry = geometry,
                            videoBitrate = settings.videoBitrateMbps * 1_000_000,
                            audio = embeddedAudio,
                            separateAudioClock = audioSidecarRecorder?.captureClock,
                            recordingLut = frozenRecordingLut(),
                        ).also { accepted ->
                            if (!accepted) {
                                // A preparation timeout may still own a duplicated native descriptor.
                                retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                                videoOutput = null
                                abortTransferPublication()
                                audioSidecarRecorder?.finish(false)
                                audioSidecarRecorder = null
                                output.finish(false)
                                foreground.stop()
                                if (cameraState.value.phase != CameraUiPhase.ERROR) {
                                    fail("log-capture-not-ready", "The experimental OCLog2 pipeline is not ready.")
                                }
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { audioSidecarRecorder?.finish(false) }
                        audioSidecarRecorder = null
                        runCatching { videoOutput?.finish(false) }
                        videoOutput = null
                        abortTransferPublication()
                        foreground.stop()
                        retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                        fail("log-output-failed", failure.message ?: "OCLog2 output could not be created.")
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
                        val output = VideoOutput.create(this@CaptureService, admittedSlate, captureNames)
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
                        ).also { accepted ->
                            if (!accepted) {
                                // A preparation timeout may still own a duplicated native descriptor.
                                retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                                videoOutput = null
                                abortTransferPublication()
                                output.finish(false)
                                foreground.stop()
                                fail("timelapse-not-ready", "The timelapse surface is not ready.")
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { videoOutput?.finish(false) }
                        videoOutput = null
                        abortTransferPublication()
                        foreground.stop()
                        retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
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
            ACTION_STOP_RECORDING -> if (!localBinder.stopRecording() && cameraState.value.phase != CameraUiPhase.RECORDING) foreground.stop()
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (!cameraState.value.structuralSettingsFrozen && cameraState.value.phase != CameraUiPhase.CAPTURING) stopSelf()
        return true
    }

    override fun onDestroy() {
        serviceDestroyed = true
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
        runCatching { take.audio?.finish(false) }
        runCatching { take.video?.finish(false) }
        abortTransferPublication(take.publication)
        val retired = previewEngine.closeAsync()
        retireRecordingRecoveryGuards(retired)
        retired.whenComplete { _, failure ->
            if (failure == null) releaseTransferCapture(transferReservation)
        }
        if (::physicalOrientationTracker.isInitialized) physicalOrientationTracker.close()
        stillOwner.set(null)
        // Accepted still publications finish on orderly service destruction. New jobs reject;
        // completion callbacks are fenced by serviceDestroyed and the exact still owner.
        storageExecutor.shutdown()
        actor.close()
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
            .put("androidDataSpace", evidence.sourceDataSpace ?: JSONObject.NULL)
            .put("dataSpaceMismatchedFrames", evidence.sourceDataSpaceMismatchedFrames ?: JSONObject.NULL)
            .put("unexpectedAndroidDataSpace", evidence.unexpectedSourceDataSpace ?: JSONObject.NULL)
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
        // High-speed sessions on some devices take ~2-3 s to open; 12 s is well past any healthy start.
        private const val PREVIEW_START_TIMEOUT_MS = 12_000L
        private const val PREVIEW_WATCHDOG_INTERVAL_MS = 1_000L
    }
}
