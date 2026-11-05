/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.StillPhotoFormat
import com.librestatic.opencinecam.camera.TimelapseCapture

import com.librestatic.opencinecam.settings.fpsLockActive
import com.librestatic.opencinecam.settings.lowLightFpsActive
import com.librestatic.opencinecam.capture.OperatorAction
import com.librestatic.opencinecam.capture.forOperatorStartup
import com.librestatic.opencinecam.capture.startupCaptureMode
import com.librestatic.opencinecam.capture.operatorActionAvailable

import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ExposureCapabilities
import com.librestatic.opencinecam.camera.CaptureFrameRate
import com.librestatic.opencinecam.camera.ShutterUnit
import com.librestatic.opencinecam.camera.resolve
import com.librestatic.opencinecam.capture.CaptureActionTicket
import com.librestatic.opencinecam.capture.CaptureCountdown

import com.librestatic.opencinecam.subject.SubjectPreviewPort
import com.librestatic.opencinecam.subject.SubjectSurfaceRegistry
import com.librestatic.opencinecam.subject.subjectPreviewBlock
import com.librestatic.opencinecam.fold.wantsCameraPreview
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
import android.util.Size
import android.view.Surface
import com.librestatic.opencinecam.settings.timelapseProjectRate
import com.librestatic.opencinecam.settings.videoProjectRate
import com.librestatic.opencinecam.settings.projectLabel
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.capture.HardwareTruthHudState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.logProfilesFor
import com.librestatic.opencinecam.usesLogGraph
import com.librestatic.opencinecam.settings.TimeLapseLimitMode
import com.librestatic.opencinecam.settings.normalizedFor
import com.librestatic.opencinecam.capture.toSpec
import com.librestatic.opencinecam.capture.VideoGeometryPolicy
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.RecordingWhiteBalancePolicy
import com.librestatic.opencinecam.camera.RecordingWhiteBalanceStatus
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import com.librestatic.opencinecam.camera.adaptTo
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.camera.Camera2EmbeddedAudioConfig
import com.librestatic.opencinecam.camera.AfLockBehavior
import com.librestatic.opencinecam.camera.FocusPullEasing
import com.librestatic.opencinecam.camera.SmpteTimecode
import com.librestatic.opencinecam.camera.TimecodeRate
import com.librestatic.opencinecam.camera.TimecodeTracker
import com.librestatic.opencinecam.camera.HdrVideoFormat
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingGeometry
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.TapFocusState
import com.librestatic.opencinecam.camera.ZoomMath
import com.librestatic.opencinecam.core.model.CaptureCommand
import com.librestatic.opencinecam.core.model.CaptureState
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.media.audio.AvClockDiagnostics
import com.librestatic.opencinecam.core.model.StableFailure
import com.librestatic.opencinecam.core.model.CaptureStateMachine
import com.librestatic.opencinecam.core.model.SerializedCaptureActor
import com.librestatic.opencinecam.settings.CaptureNameSnapshot
import com.librestatic.opencinecam.storage.StillImageSaver
import com.librestatic.opencinecam.storage.VideoOutput
import com.librestatic.opencinecam.storage.IncompleteTake
import com.librestatic.opencinecam.storage.ClipIntent
import com.librestatic.opencinecam.transfers.CaptureTransferReservation
import com.librestatic.opencinecam.transfers.WebDavTransferRuntime
import java.util.concurrent.atomic.AtomicReference
import com.librestatic.opencinecam.transfers.CaptureTransferPublication
import com.librestatic.opencinecam.storage.AudioSidecarRecorder
import com.librestatic.opencinecam.storage.FlacAudioSidecarRecorder
import com.librestatic.opencinecam.storage.WavAudioSidecarRecorder
import com.librestatic.opencinecam.media.audio.AndroidProfessionalAudioProbe
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioInputKey
import com.librestatic.opencinecam.media.audio.AudioInputRouteGuard
import com.librestatic.opencinecam.settings.AudioListeningStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.librestatic.opencinecam.settings.CameraPreset
import com.librestatic.opencinecam.settings.CameraPresetCodec
import com.librestatic.opencinecam.settings.withLivePreferencesFrom
import com.librestatic.opencinecam.settings.LutLibraries
import com.librestatic.opencinecam.camera.monitorLutIdentity
import com.librestatic.opencinecam.camera.MonitorLut
import com.librestatic.opencinecam.camera.OperatorLutStatus
import com.librestatic.opencinecam.camera.OperatorLutState
import com.librestatic.opencinecam.settings.SettingsRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

const val ACTION_START_RECORDING = "com.librestatic.opencinecam.action.START_RECORDING"

/**
 * Same-process bound service; all hardware commands are serialized off the main thread.
 *
 * The lifecycle, the shared state and [LocalBinder] live here; the internal steps are extensions
 * grouped by responsibility in the CaptureService*.kt files of this package.
 */
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
    internal val stateCommands = CaptureStateCommands(OWNER_ID)
    internal val cameraState = MutableStateFlow(CameraUiState())
    internal val lensCalibrationState = MutableStateFlow(com.librestatic.opencinecam.calibration.LensCalibrationUiState())
    internal val localBinder = LocalBinder()
    internal val foreground = RecordingForegroundController(this)
    internal val previewEngine by lazy { CaptureStartup.adoptEngine(this) }
    internal val stillSaver by lazy { StillImageSaver(this) }
    // Take-owned fields are written on main before dispatch and detached by engine callbacks.
    @Volatile internal var videoOutput: VideoOutput? = null
    @Volatile internal var latestRollingShutterSkewNs: Long? = null
    internal val takeOwnershipLock = Any()
    internal val recordingRecoveryGuards = java.util.concurrent.ConcurrentHashMap<VideoOutput, AutoCloseable>()

    @Volatile internal var activeTransferPublication: CaptureTransferPublication? = null
    internal var transferRuntime: WebDavTransferRuntime? = null
    internal val transferCapture = AtomicReference<CaptureTransferReservation?>(null)
    internal var transferPreparationToken = 0L
    internal var transferWaiting = false
    private var transferPreparationJob: kotlinx.coroutines.Job? = null
    internal var transferCaptureDispatched = false
    internal val dispatchedTake = DispatchedTakeAdmission<CaptureTransferReservation>()
    @Volatile internal var serviceDestroyed = false

    /** Actor state is reporting only: a full queue or a retired actor never blocks capture. */
    internal fun submitCaptureState(commands: List<CaptureCommand>) {
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

    internal class DetachedTake(
        val video: VideoOutput?,
        val audio: AudioSidecarRecorder?,
        val publication: CaptureTransferPublication?,
        val audioStartFailure: String?,
        val geometry: RecordingGeometry?,
    )

    @Volatile internal var audioSidecarRecorder: AudioSidecarRecorder? = null
    // Report-only A/V clock drift of the last separate WAV/FLAC take (ADAPTIVE; never stops a take).
    @Volatile internal var lastSeparateAudioAvDrift: com.librestatic.opencinecam.media.audio.AvDiagnosticSnapshot? = null
    internal var previewAudioMonitor: PreviewAudioMonitor? = null
    private var audioInputMonitor: AudioInputMonitor? = null
    /** Only the newest guard may publish [CameraUiState.activeAudioInput]; retired guards may still post. */
    internal var audioRouteOwner: AudioInputRouteGuard? = null
    internal var audioInputRefreshDeferred = false
    internal var lastAutoAudioInput: AudioInputKey? = null
    @Volatile internal var audioListeningController: AudioListeningController? = null
    private val latestListeningStatus = AtomicReference<AudioListeningStatus?>(null)
    private var audioListeningRetirement = CompletableFuture.completedFuture(Unit)
    // Owned on main; even a failed factory keeps its native cleanup receipt here.
    internal var previewAudioRetirement = CompletableFuture.completedFuture(Unit)
    internal var previewAudioGeneration = 0L
    @Volatile internal var audioStartFailure: String? = null
    internal var activeRecordingAudioLabel: String = "no audio"
    internal var activeRecordingGain: DigitalRecordingGain? = null
    /** The HUD state when the take began, so the sidecar lists only what the take itself saw. */
    @Volatile internal var takeStartHardwareTruth = HardwareTruthHudState()
    internal val audioLevelEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile internal var recordingStartedAtMs = 0L
    /** OCC-PLAN-068 U6: optional operator-side sync-marker evidence for the active take's sidecar. */
    internal val subjectSyncMarkers = com.librestatic.opencinecam.subject.SubjectSyncMarkerSlot()
    internal var timelapseAutoStopRunnable: Runnable? = null
    internal var attachedPreviewSurface: Surface? = null
    internal var attachedPreviewRotationDegrees: Int = 0
    internal var cameraSwitchGeneration = 0L
    private var recoveryGeneration = 0L
    internal var pendingSwitchTarget: String? = null
    internal var pendingSwitchPrevious: String? = null
    internal var pendingStatusMessage: String? = null
    internal val perCameraZoom = mutableMapOf<String, Float>()
    internal val timecodeTracker by lazy { TimecodeTracker(
        continuationStore = com.librestatic.opencinecam.metadata.TimecodeContinuationFile(java.io.File(filesDir, "timecode-continuation.json"))) }
    internal var settings = CameraSettings()
    private var operatorStartupPending = true
    private var foldClosureTracker: FoldClosureTracker? = null
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var geometrySeeds = GeometrySeeds()
    private lateinit var physicalOrientationTracker: PhysicalOrientationTracker
    @Volatile internal var activeRecordingGeometry: RecordingGeometry? = null
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal class StillOwner(val captureNames: CaptureNameSnapshot) {
        val productionSlate get() = captureNames.slate
        val publishing = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    internal val stillOwner = java.util.concurrent.atomic.AtomicReference<StillOwner?>()
    internal val storageExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16),
        ThreadPoolExecutor.AbortPolicy(),
    )
    /** Set when Adaptive admitted a take it could not size; shown for the whole take. */
    @Volatile internal var admissionWarning: String? = null
    /** Codec of the software AVC encoder the running Adaptive take records with; null for a hardware take. */
    @Volatile internal var takeSoftwareEncoder: String? = null
    /** The running take asked for HEVC and the engine fell back to H.264. */
    @Volatile internal var takeCodecFallback = false
    /** What the running take was asked to do, its wall-clock start and the codec the engine reported; the clip sidecar's intent. */
    @Volatile internal var takeClipIntent: ClipIntent? = null
    @Volatile internal var takeClipStartedAt: java.time.Instant? = null
    @Volatile internal var takeClipCodecMime: String? = null
    @Volatile internal var takeClipMediaRecorder: Boolean? = null
    /** Stable code of a Strict encoder refusal reported by the engine, consumed by the failed-start branch. */
    @Volatile internal var pendingStartRefusal: String? = null
    /** Bytes per minute the admitted take was projected to write; the live storage policy measures against it. */
    @Volatile internal var takeProjectedBytesPerMinute = 0L
    /** The policy the running take started under; a setting changed mid-take stays pending. */
    @Volatile internal var activeRecordingPolicy = PolicyMode.STRICT
    @Volatile internal var lastKnownFreeBytes: Long? = null
    @Volatile internal var runtimeNotice: RuntimeNotice? = null
    @Volatile internal var avNotice: RuntimeNotice? = null
    @Volatile internal var cadenceNotice: RuntimeNotice? = null
    /** Live A/V drift of a take whose audio is a separate WAV/FLAC file (PLAN-023); embedded AAC exposes no observable audio PTS. */
    @Volatile internal var avDiagnostics: AvClockDiagnostics? = null
    /** Why the running take is being stopped; the final message names it, and the file is still saved. */
    @Volatile internal var pendingStopReason: StableFailure? = null

    internal val recordingTicker = object : Runnable {
        override fun run() {
            if (cameraState.value.phase != CameraUiPhase.RECORDING) return
            previewEngine.encodedRecordingProgress()?.let(timecodeTracker::observeEncodedProgress)
            val free = recordingVolumeFreeBytes()
            cameraState.value = cameraState.value.copy(
                recordingElapsedMs = activeRecordingElapsedMs(),
                availableStorageBytes = free,
                timecodeDisplay = timecodeTracker.currentDisplayTc()?.format(),
            )
            enforceRuntimePolicy(free)
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
        fun width(mode: CaptureMode): Int = when (mode) { CaptureMode.LOG, CaptureMode.HLG -> logWidth; CaptureMode.TIME_LAPSE -> timelapseWidth; else -> videoWidth }
        fun height(mode: CaptureMode): Int = when (mode) { CaptureMode.LOG, CaptureMode.HLG -> logHeight; CaptureMode.TIME_LAPSE -> timelapseHeight; else -> videoHeight }
        fun fps(mode: CaptureMode): Int = when (mode) { CaptureMode.LOG, CaptureMode.HLG -> logFps; CaptureMode.TIME_LAPSE -> 30; else -> videoFps }
    }

    @Volatile internal var currentOperatorLut: MonitorLut? = null
    @Volatile private var currentSubjectLut: MonitorLut? = null
    @Volatile private var currentRecordingLut: MonitorLut? = null
    @Volatile private var recordingLutLibrary: com.librestatic.opencinecam.settings.LutLibrary? = null
    internal data class RecordingLutIntent(val owner: CaptureTransferReservation, val lut: MonitorLut?, val captureNames: CaptureNameSnapshot) {
        val productionSlate get() = captureNames.slate
    }
    internal val recordingLutIntent = java.util.concurrent.atomic.AtomicReference<RecordingLutIntent?>()
    internal val subjectPreviewEpoch = java.util.concurrent.atomic.AtomicLong()

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

    internal fun waitingRecordingLutStatus(): OperatorLutStatus = currentRecordingLut?.let {
        OperatorLutStatus(it.cube.sha256, OperatorLutState.WAITING_FOR_GPU, selectionId = monitorLutIdentity(it))
    } ?: OperatorLutStatus()

    private fun frozenRecordingLut(): MonitorLut? {
        val reservation = requireNotNull(transferCapture.get()) { "Recording reservation missing" }
        return requireNotNull(recordingLutIntent.get()?.takeIf { it.owner === reservation }) { "Recording LUT intent missing" }.lut
    }

    internal fun waitingSubjectLutStatus(): OperatorLutStatus = currentSubjectLut?.let {
        OperatorLutStatus(it.cube.sha256, OperatorLutState.WAITING_FOR_GPU, selectionId = monitorLutIdentity(it))
    } ?: OperatorLutStatus()

    private fun resetSubjectLutStatus() {
        subjectStatus.value = subjectStatus.value.copy(lutStatus = null)
        cameraState.update { it.copy(subjectLutStatus = waitingSubjectLutStatus()) }
    }

    private val subjectSurfaces = SubjectSurfaceRegistry<Surface>()
    internal val subjectStatus = MutableStateFlow(SubjectPreviewStatus())
    private var subjectPortClosed = false
    @Volatile internal var activePreviewKey: List<Any?>? = null
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

    /** The last calibration read from the store, keyed by logical camera id; null value = none saved. */
    @Volatile internal var lensCalibrationCache: Pair<String, com.librestatic.opencinecam.core.model.CalibrationSet?>? = null

    // ---- Lens lock (PLAN-070 slice 3a, ADR-0036) ----

    /**
     * The engine refused the lock of the session being opened (high speed, night, or a lens it
     * cannot serve at all): that session runs on the logical camera unlocked. A failed physical
     * open or configuration is not a fallback here: the engine serves it by the GPU crop
     * (`onLensLockCrop`), which still counts as a locked session. Reset by every opening.
     */
    @Volatile internal var lensLockFallback = false

    // ---- Lens calibration run (PLAN-070) ----

    internal class CalibrationRestore(val cameraId: String, val mode: CaptureMode, val zoom: Float)

    /** One run, from the binder call to its end. Fields are written by main and by the worker thread. */
    internal class CalibrationRun(val restore: CalibrationRestore) {
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
    @Volatile internal var calibrationRun: CalibrationRun? = null
    /** The finished run's set, waiting for Save or Discard. Main thread only. */
    internal var calibrationPending: com.librestatic.opencinecam.core.model.CalibrationSet? = null
    /** The calibration saved for the selected camera on this build, loaded whether or not the correction is on. */
    @Volatile internal var savedCalibration: com.librestatic.opencinecam.core.model.CalibrationSet? = null
    @Volatile internal var liveRedBlueRatio: Float? = null
    internal val openingCount = AtomicInteger()

    internal val calibrationPrepareTimeoutMs = 15_000L
    internal val calibrationCaptureTimeoutMs = 10_000L
    internal val calibrationModeSwitchGraceMs = 2_000L
    internal val calibrationQueueSize = 8
    /** How long a start waits for the viewfinder to attach and preview; 0 when no start is waiting. Main thread. */
    private var calibrationStartDeadlineMs = 0L
    private val calibrationStartWaitMs = 10_000L
    private val calibrationStartRetry = Runnable { localBinder.beginLensCalibration() }
    internal val illuminantMismatchLimit = 0.15f

    internal inner class ServiceCalibrationRig(
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

    private fun photoPreviewMode(mode: CaptureMode = cameraState.value.selectedMode): Boolean =
        mode in setOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL)

    internal fun desiredGpuViewfinder(mode: CaptureMode = cameraState.value.selectedMode): Boolean =
        // The extension session renders straight into the viewfinder surface, never through the GPU path.
        !nightExtensionActive(mode) && (
        // Video always previews through the GPU viewfinder so REC only starts the encoder on the
        // live graph. A direct preview had to be torn down at REC (EGL cannot reconnect to the
        // SurfaceView the camera just released), which froze the viewfinder for the whole take.
        // A constrained high-speed take still reaches the encoder directly (startVideo); only the
        // 30 fps preview share goes through the GPU to the operator and subject windows.
        mode in setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.HLG, CaptureMode.TIME_LAPSE) ||
        // The subject preview rides the GPU viewfinder in every mode it supports.
        (settings.subjectDisplay.wantsCameraPreview && subjectPreviewBlock(mode) == null) ||
        // A lens correction shows only on the GPU path.
        (photoPreviewMode(mode) && (currentOperatorLut != null || lensCalibrationFor(cameraState.value.selectedCameraId) != null)) ||
        // The lens lock's GPU crop zooms only on the GPU path. The restart this causes reopens on
        // the same route: the engine remembers the failed lens and the route survives onOpening.
        lensLockCropNeedsGpuViewfinder(photoPreviewMode(mode), cameraState.value.lensLock.route))

    /** Night photo applies to PHOTO only, on a camera that offers the extension (API 31+). */
    internal fun nightExtensionActive(mode: CaptureMode = cameraState.value.selectedMode): Boolean {
        if (mode != CaptureMode.PHOTO) return false
        val requested = com.librestatic.opencinecam.camera.nightPhotoRequested(settings.nightPhotoMode, nightTransientEngaged)
        return com.librestatic.opencinecam.camera.nightExtensionEngaged(requested, Build.VERSION.SDK_INT, cameraState.value.descriptor?.nightExtension)
    }

    /** The quick toggle's session-only state: counts in AUTO only and is dropped with the mode, camera or service. */
    @Volatile private var nightTransientEngaged = false
    internal val nightSceneDetector = com.librestatic.opencinecam.camera.NightSceneDetector()

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

    internal fun previewKey(): List<Any?> = cameraState.value.let {
        listOf(it.selectedCameraId, it.selectedMode, it.targetVideoWidth, it.targetVideoHeight, it.targetFps, desiredGpuViewfinder(), selectedStillPhotoFormat(), selectedStillSize(), nightExtensionActive(), lowLightFpsActive(it, settings), currentLensLockBinding())
    }

    internal fun updateSubjectTarget() {
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

    internal fun cancelCountdown() {
        captureCountdown.cancel()
        countdownTick?.let(mainHandler::removeCallbacks)
        countdownTick = null
        cameraState.value = cameraState.value.copy(countdownSeconds = 0, captureActionGeneration = cameraState.value.captureActionGeneration + 1)
    }

    internal fun applyProfessionalSettings() {
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

    private val previewListener = newPreviewListener()

    inner class LocalBinder : Binder() {
        fun reconnectAudioListening() {
            if (serviceDestroyed) return
            audioListeningController?.configure(settings.audioListening, settings.audioListeningOutputDeviceId)
            audioListeningController?.reconnect()
        }

        val states: StateFlow<CaptureState> = state.asStateFlow()
        val cameraStates: StateFlow<CameraUiState> = cameraState.asStateFlow()
        val lensCalibrationStates: StateFlow<com.librestatic.opencinecam.calibration.LensCalibrationUiState> = lensCalibrationState.asStateFlow()

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
            if (current.phase == CameraUiPhase.RECORDING || current.structuralSettingsFrozen || calibrationRun != null) return
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
            if (serviceDestroyed || calibrationRun != null) return
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
                    it.copy(phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.IDLE, referencePhysicalId = null, lenses = emptyList(), failure = null)
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
            fun refuse(reason: com.librestatic.opencinecam.calibration.LensCalibrationFailure) {
                calibrationPending = null
                lensCalibrationState.update {
                    it.copy(phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.FAILED, logicalCameraId = cameraId,
                        referencePhysicalId = null, lenses = emptyList(), failure = reason)
                }
            }
            if (cameraId == null || !current.zoomSupported || lenses.size < 2) {
                if (current.descriptor == null && android.os.SystemClock.elapsedRealtime() < calibrationStartDeadlineMs) {
                    mainHandler.postDelayed(calibrationStartRetry, 250L)
                    return
                }
                calibrationStartDeadlineMs = 0L
                refuse(com.librestatic.opencinecam.calibration.LensCalibrationFailure.NO_MULTI_CAMERA)
                return
            }
            val idle = current.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED) && !current.structuralSettingsFrozen &&
                !current.captureControlsLocked && stillOwner.get() == null && transferCapture.get() == null && !recordingWbPreparing &&
                captureCountdown.active == null
            if (!idle || attachedPreviewSurface?.isValid != true) {
                val waiting = current.phase !in setOf(CameraUiPhase.RECORDING, CameraUiPhase.CAPTURING) && !current.structuralSettingsFrozen
                if (waiting && android.os.SystemClock.elapsedRealtime() < calibrationStartDeadlineMs) {
                    if (lensCalibrationState.value.phase != com.librestatic.opencinecam.calibration.LensCalibrationPhase.PREPARING) lensCalibrationState.update {
                        it.copy(phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.PREPARING, logicalCameraId = cameraId,
                            referencePhysicalId = null, lenses = emptyList(), failure = null)
                    }
                    mainHandler.postDelayed(calibrationStartRetry, 250L)
                    return
                }
                calibrationStartDeadlineMs = 0L
                refuse(com.librestatic.opencinecam.calibration.LensCalibrationFailure.UNSUPPORTED_MODE)
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
                    phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.PREPARING,
                    logicalCameraId = cameraId,
                    referencePhysicalId = null,
                    lenses = lenses.sortedBy { lens -> lens.nominalRatio }.map { lens ->
                        com.librestatic.opencinecam.calibration.LensCalibrationLensRow(lens.physicalId, lens.nominalRatio,
                            com.librestatic.opencinecam.calibration.LensCalibrationLensStatus.PENDING)
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
            if (calibrationRun != null || lensCalibrationState.value.phase != com.librestatic.opencinecam.calibration.LensCalibrationPhase.RESULT) return
            calibrationPending = null
            try {
                storageExecutor.execute {
                    val saved = runCatching { com.librestatic.opencinecam.calibration.LensCalibrationStore.forContext(this@CaptureService).save(set) }.isSuccess
                    mainHandler.post {
                        if (serviceDestroyed) return@post
                        lensCalibrationCache = null
                        lensCalibrationState.update {
                            if (saved) it.copy(phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.IDLE, referencePhysicalId = null, lenses = emptyList(), failure = null)
                            else it.copy(phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.FAILED, failure = com.librestatic.opencinecam.calibration.LensCalibrationFailure.INTERNAL)
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
                it.copy(phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.IDLE, referencePhysicalId = null, lenses = emptyList(), failure = null)
            }
        }

        /** Deletes the saved calibration of the selected camera. */
        fun deleteLensCalibration() {
            if (calibrationRun != null) return
            val cameraId = cameraState.value.selectedCameraId ?: return
            try {
                storageExecutor.execute {
                    runCatching {
                        com.librestatic.opencinecam.calibration.LensCalibrationStore.forContext(this@CaptureService)
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
        fun recordSubjectSyncMarker(report: com.librestatic.opencinecam.subject.SubjectSyncMarkerReport): Boolean =
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
                        CaptureMode.RAW_PHOTO to if (preferred.supportsRaw) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                        CaptureMode.LOG to if (preferred.supportsOpenCineLog) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                        CaptureMode.HLG to if (preferred.supportsOpenCineLog && preferred.logProfilesFor(CaptureMode.HLG).isNotEmpty()) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                        CaptureMode.SLOW_MOTION to if (com.librestatic.opencinecam.capture.supportsSlowMotion(preferred.videoProfiles.map { it.toSpec() })) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
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
            refreshLensLockState()
            refreshAudioCapabilities()
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
            val requestedInput = settings.audioInputKey
            try {
                storageExecutor.execute {
                    val capabilities = runCatching { AndroidProfessionalAudioProbe(this@CaptureService).probe(requestedInput) }
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
                        announceAutoAudioInput(capabilities)
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
            if (subjectSurfaces.current != null && settings.subjectDisplay.wantsCameraPreview &&
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
                message = "Camera $cameraId selected",
                modeGates = current.modeGates + mapOf(
                    CaptureMode.RAW_PHOTO to if (descriptor.supportsRaw) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                    CaptureMode.LOG to if (descriptor.supportsOpenCineLog) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                    CaptureMode.HLG to if (descriptor.supportsOpenCineLog && descriptor.logProfilesFor(CaptureMode.HLG).isNotEmpty()) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                    CaptureMode.SLOW_MOTION to if (com.librestatic.opencinecam.capture.supportsSlowMotion(descriptor.videoProfiles.map { it.toSpec() })) com.librestatic.opencinecam.ModeGateState.AVAILABLE else com.librestatic.opencinecam.ModeGateState.UNSUPPORTED,
                ),
            )
            refreshLensLockState(cameraId)
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
                refreshLensLockState(fallbackId)
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
                nightExtensionActive(current.selectedMode) != nightExtensionActive(mode) ||
                (current.selectedMode.usesLogGraph) != (mode.usesLogGraph) ||
                    (settings.subjectDisplay.wantsCameraPreview && (current.selectedMode == CaptureMode.VIDEO) != (mode == CaptureMode.VIDEO)) ||
                    (current.selectedMode in CameraUiState.videoProfileModes) != (mode in CameraUiState.videoProfileModes)
            val descriptor = current.descriptor
            val selectedLogProfile = descriptor?.let {
                normalizedLogProfile(it, mode, geometrySeeds.logWidth, geometrySeeds.logHeight, geometrySeeds.logFps)
            }.takeIf { mode.usesLogGraph }
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

        /** [requestedFps] is the rate to keep at the new size when it offers it; slow motion passes a high-speed one. */
        fun selectVideoResolution(width: Int, height: Int, requestedFps: Int = cameraState.value.targetFps) {
            val current = cameraState.value
            if (current.structuralSettingsFrozen || current.selectedMode !in CameraUiState.resolutionProfileModes) return
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
                    "OCLog HFR · ISP-derived"
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
                    ?: "$sourceLabel $width×$height · $selectedFps fps${if (selectedHighSpeed) " · HIGH SPEED" else ""}",
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
            cancelCountdown()
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

        /** [audioForThisTake] overrides the persisted audio switch for one recording only. */
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
            when (val admission = admitRecordingStorage(settings.recordingPolicy, projectedBytesPerMinute, recordingVolumeFreeBytes())) {
                StorageAdmission.Admit -> Unit
                StorageAdmission.AdmitUnread -> admissionWarning = getString(R.string.storage_unknown_warning)
                is StorageAdmission.Refuse -> {
                    val needed = android.text.format.Formatter.formatFileSize(this@CaptureService, admission.requiredBytes)
                    val message = if (admission.availableBytes == null) getString(R.string.storage_unknown_refused)
                        else getString(R.string.storage_reserve_insufficient,
                            needed, android.text.format.Formatter.formatFileSize(this@CaptureService, admission.availableBytes))
                    cameraState.update { it.copy(message = message, errorCode = admission.errorCode) }
                    return false
                }
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
            if (current.phase == CameraUiPhase.RECORDING && current.selectedMode in setOf(CaptureMode.VIDEO, CaptureMode.TIME_LAPSE, CaptureMode.LOG, CaptureMode.HLG)) {
                return stopRecording()
            }
            // The preview comes back before the previous take is published; no capture overlaps that.
            if (current.recordingFinalizing) return false
            if (current.phase != CameraUiPhase.PREVIEWING && current.phase != CameraUiPhase.SAVED && !(whiteBalancePrepared && current.phase == CameraUiPhase.CAPTURING)) return false
            // Every branch below starts clean; time-lapse never sets a label and must not inherit the last take's.
            activeRecordingAudioLabel = "no audio"
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
                            if (com.librestatic.opencinecam.capture.stillFormatLostRaw(selectedStillPhotoFormat(), cameraState.value.previewGraphLoss)) finishStillFailure(owner, "still-raw-unavailable", getString(R.string.graph_fallback_raw))
                            else finishStillFailure(owner, "still-capture-not-ready", "The selected still format is not ready on this camera.")
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
                        val output = VideoOutput.create(this@CaptureService, admittedSlate, captureNames)
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
                            !audioRequested -> "no audio"
                            embeddedAudio != null -> "AAC ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioBitrateKbps} kbps · ${audioSettings.audioChannels} ch"
                            else -> "${audioSettings.audioOutputFormat.name.substringBefore('_')} ${audioSettings.audioBitDepth.bits}-bit · ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioChannels} ch"
                        }
                        cameraState.update { it.copy(phase = CameraUiPhase.CAPTURING, recordingProjectRate = settings.videoProjectRate.takeIf { settings.videoOffSpeed },
                            message = "Preparing video · $activeRecordingAudioLabel…") }
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
                                abortTransferPublication()
                                audioSidecarRecorder?.finish(false)
                                audioSidecarRecorder = null
                                output.discardNeverRecorded()
                                foreground.stop()
                                val refusal = pendingStartRefusal
                                pendingStartRefusal = null
                                // A policy refusal is a soft one: the preview keeps running and the user can change the policy.
                                if (refusal != null) refuseTakeStart(refusal, getString(R.string.video_hardware_encoder_unavailable))
                                else if (cameraState.value.phase != CameraUiPhase.ERROR) fail("video-capture-not-ready", "The video surface is not ready.")
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { audioSidecarRecorder?.finish(false) }
                        audioSidecarRecorder = null
                        runCatching { videoOutput?.discardNeverRecorded() }
                        videoOutput = null
                        abortTransferPublication()
                        foreground.stop()
                        retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                        fail("video-output-failed", failure.message ?: "Video output could not be created.")
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
                        val output = VideoOutput.create(this@CaptureService, admittedSlate, captureNames)
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
                            !requestedAudioEnabled -> "no audio"
                            embeddedAudio != null -> "AAC ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioBitrateKbps} kbps · ${audioSettings.audioChannels} ch"
                            else -> "${audioSettings.audioOutputFormat.name.substringBefore('_')} ${audioSettings.audioBitDepth.bits}-bit · ${audioSettings.audioSampleRateHz / 1000.0} kHz · ${audioSettings.audioChannels} ch"
                        }
                        cameraState.update { it.copy(
                            phase = CameraUiPhase.CAPTURING,
                            recordingProjectRate = null,
                            message = if (hlgTake) {
                                if (hdrTakeFormat(current) == HdrVideoFormat.HDR10) {
                                    "Preparing HDR10 · ${current.targetFps} fps · HLG10 → BT.2020 PQ → HEVC Main10 HDR10 · $activeRecordingAudioLabel…"
                                } else "Preparing HLG · ${current.targetFps} fps · HLG10 → BT.2020 HLG → HEVC Main10 · $activeRecordingAudioLabel…"
                            } else if (current.activeLogProfile?.sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) {
                                "Preparing OCLog2 HFR · ${current.targetFps} fps · ISP SDR → GLES → HEVC Main10 · source depth not claimed · $activeRecordingAudioLabel…"
                            } else {
                                "Preparing OCLog2 · ${current.targetFps} fps · HLG10 → scene-linear BT.2020 → HEVC Main10 · $activeRecordingAudioLabel…"
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
                                abortTransferPublication()
                                audioSidecarRecorder?.finish(false)
                                audioSidecarRecorder = null
                                output.discardNeverRecorded()
                                foreground.stop()
                                if (cameraState.value.phase != CameraUiPhase.ERROR) {
                                    fail("log-capture-not-ready", "The experimental OCLog2 pipeline is not ready.")
                                }
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { audioSidecarRecorder?.finish(false) }
                        audioSidecarRecorder = null
                        runCatching { videoOutput?.discardNeverRecorded() }
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
                            videoCodec = settings.videoCodec,
                        ).also { accepted ->
                            if (!accepted) {
                                // A preparation timeout may still own a duplicated native descriptor.
                                retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                                videoOutput = null
                                abortTransferPublication()
                                output.discardNeverRecorded()
                                foreground.stop()
                                fail("timelapse-not-ready", "The timelapse surface is not ready.")
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { videoOutput?.discardNeverRecorded() }
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
        calibrationRun?.let { run ->
            run.lost = true
            run.runner?.abort(com.librestatic.opencinecam.camera.calibration.RunFailure.CAMERA_LOST)
            run.thread?.interrupt()
            calibrationRun = null
            lensCalibrationState.update {
                it.copy(phase = com.librestatic.opencinecam.calibration.LensCalibrationPhase.FAILED,
                    failure = com.librestatic.opencinecam.calibration.LensCalibrationFailure.CAMERA_LOST)
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
        actor.close()
        super.onDestroy()
    }

    private var pendingPreset: CameraPreset? = null
    internal var pendingPresetFocus: CameraPreset? = null
    private var recordingWbGeneration = 0L
    internal var recordingWbPreparing = false

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

    internal fun finishStillFailure(owner: StillOwner, code: String, message: String) {
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

    internal fun takeInProgress(): Boolean = cameraState.value.phase in setOf(CameraUiPhase.RECORDING, CameraUiPhase.CAPTURING)

    internal class TakeAudioInput(val settings: CameraSettings, val guard: AudioInputRouteGuard?)

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

    internal fun fail(code: String, message: String) {
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

    internal fun activeRecordingElapsedMs(): Long = cameraState.value.recordingPauseStatus
        ?.activeElapsedAt(android.os.SystemClock.elapsedRealtime())
        ?: (android.os.SystemClock.elapsedRealtime() - recordingStartedAtMs).coerceAtLeast(0L)

    internal fun List<com.librestatic.opencinecam.camera.Camera2LogProfile>.normalizedLogProfile(
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
        internal const val LOST_INPUT_STOP_ATTEMPTS = 20
        internal const val LOST_INPUT_STOP_RETRY_MS = 250L
        private const val OWNER_ID = "capture-service"
        private const val RECORDING_TICK_MS = 500L
        private const val CAMERA_SWITCH_TIMEOUT_MS = 6_000L
        private const val PREVIEW_RECOVERY_TIMEOUT_MS = 4_000L
        // High-speed sessions on some devices take ~2-3 s to open; 12 s is well past any healthy start.
        private const val PREVIEW_START_TIMEOUT_MS = 12_000L
        private const val PREVIEW_WATCHDOG_INTERVAL_MS = 1_000L
    }
}

/** HLG takes are monitored through the Rec.709 view assist; the flat OCLog2 view is LOG-only. */
private fun CameraSettings.previewViewAssist(mode: CaptureMode): Boolean =
    logViewAssistEnabled || mode == CaptureMode.HLG
