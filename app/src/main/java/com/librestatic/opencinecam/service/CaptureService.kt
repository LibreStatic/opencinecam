/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.appliedAudioEqCurve
import com.librestatic.opencinecam.appliedAudioEqProfileId
import com.librestatic.opencinecam.camera.StillPhotoFormat
import com.librestatic.opencinecam.camera.TimelapseCapture

import com.librestatic.opencinecam.fpsLockActive
import com.librestatic.opencinecam.lowLightFpsActive
import com.librestatic.opencinecam.reviewBlockedByTake
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
import android.util.Size
import android.view.Surface
import com.librestatic.opencinecam.timelapseProjectRate
import com.librestatic.opencinecam.videoProjectRate
import com.librestatic.opencinecam.projectLabel
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.HardwareTruthHudState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.logProfilesFor
import com.librestatic.opencinecam.usesLogGraph
import com.librestatic.opencinecam.TimeLapseLimitMode
import com.librestatic.opencinecam.normalizedFor
import com.librestatic.opencinecam.forDevice
import com.librestatic.opencinecam.toSpec
import com.librestatic.opencinecam.VideoGeometryPolicy
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
import com.librestatic.opencinecam.camera.TimecodeMode
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
import com.librestatic.opencinecam.CaptureNameSnapshot
import com.librestatic.opencinecam.storage.StillImageSaver
import com.librestatic.opencinecam.RawVideoConfigKeys
import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.camera.RawVideoQualificationSet
import com.librestatic.opencinecam.rawvideo.RawVideoRecorder
import com.librestatic.opencinecam.storage.RawVideoQualificationStore
import com.librestatic.opencinecam.storage.VideoOutput
import com.librestatic.opencinecam.storage.RecordingDestination
import com.librestatic.opencinecam.storage.RecordingVolumeEvent
import com.librestatic.opencinecam.storage.RecordingVolumeMonitor
import com.librestatic.opencinecam.storage.releaseDriveDescriptors
import com.librestatic.opencinecam.storage.ResolvedRecordingDestination
import com.librestatic.opencinecam.storage.driveFreeBytes
import com.librestatic.opencinecam.storage.IncompleteTake
import com.librestatic.opencinecam.storage.ClipContinuation
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
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import com.librestatic.opencinecam.batteryPercentOf
import com.librestatic.opencinecam.ModeGateState
import com.librestatic.opencinecam.RawQualificationOutcomeKind
import com.librestatic.opencinecam.RawQualificationRefusals
import com.librestatic.opencinecam.rawQualificationRunAbort
import com.librestatic.opencinecam.rawQualificationGateQualifiable
import com.librestatic.opencinecam.rawQualificationStartRefusal
import com.librestatic.opencinecam.RawQualificationStopReasons
import com.librestatic.opencinecam.RawVideoQualificationInfo
import com.librestatic.opencinecam.RawVideoQualificationUi
import com.librestatic.opencinecam.readRawQualificationConditions
import com.librestatic.opencinecam.rawvideo.RawQualificationProgress
import com.librestatic.opencinecam.rawvideo.RawVideoQualifier
import com.librestatic.opencinecam.RawQualificationItem
import com.librestatic.opencinecam.rawQualificationPlanItems

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
    internal val stateCommands = CaptureStateCommands(OWNER_ID)
    internal val cameraState = MutableStateFlow(CameraUiState())
    internal val lensCalibrationState = MutableStateFlow(com.librestatic.opencinecam.LensCalibrationUiState())
    /** The equaliser editor's live spectrum from the preview monitor (PLAN-074 decision 13); null when not wanted or none runs. Declared before the binder, which wraps it as it is built. */
    internal val audioSpectrum = MutableStateFlow<com.librestatic.opencinecam.camera.AudioSpectrum?>(null)
    /** The equaliser editor's snippet audition; declared before the binder for the same reason. */
    private val eqAuditionState = MutableStateFlow(EqAuditionState())
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
        /** The take's link to the drive take it continues (PLAN-072 Delivery 4); null when it is not a continuation. */
        val continuation: ClipContinuation?,
    )

    @Volatile internal var audioSidecarRecorder: AudioSidecarRecorder? = null
    // Report-only A/V clock drift of the last separate WAV/FLAC take (ADAPTIVE; never stops a take).
    @Volatile internal var lastSeparateAudioAvDrift: com.librestatic.opencinecam.media.audio.AvDiagnosticSnapshot? = null
    internal var previewAudioMonitor: PreviewAudioMonitor? = null
    /** Main thread: the audio input has been settled for this phone (PLAN-074 decision 1); see onCreate. */
    internal var audioInputSettled = false
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
    /** Main thread: holds [CameraUiState.audioInputOverloaded] after the last flagged snapshot (PLAN-074 decision 14). */
    internal val audioOverloadHold = AudioOverloadHold()
    internal val audioOverloadClear = object : Runnable {
        override fun run() {
            if (serviceDestroyed) return
            val wait = audioOverloadHold.clearsInMs(android.os.SystemClock.elapsedRealtime())
            if (wait != null) mainHandler.postDelayed(this, wait) else cameraState.update { it.copy(audioInputOverloaded = false) }
        }
    }
    /** Main thread: an editor asked for [audioSpectrum]; applied to every monitor that starts. */
    internal var audioSpectrumWanted = false
    /** Main thread: the editor's snippet audition (PLAN-074 decision 13); while it plays, the preview monitor rests. */
    internal val eqSnippetAudition = EqAudition(eqAuditionState, { mainHandler.post(it) }, object : EqAudition.Hooks {
        override fun allowed() = eqAuditionAllowed()
        override fun monitor() = previewAudioMonitor
        override fun monitorEligible() = previewAudioEligible()
        override fun stopMonitor() { stopPreviewAudioMonitor(clearLevels = false, endEqAudition = false) }
        override fun restartMonitor() = startPreviewAudioMonitorIfEligible()
        override fun startPlayer(snippet: EqSnippet, onProgress: () -> Unit, onFailure: (Throwable) -> Unit): EqSnippetPlayback {
            // The playback is the level producer now: its meters latch the clip as the monitor's would.
            val onLevel = newAudioLevelConsumer()
            val applied = cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings
            return EqSnippetPlayer(snippet, applied.appliedAudioEqCurve, applied.audioRecordingGain,
                newAudioSpectrumConsumer(audioLevelEpoch.get()), onLevel, onProgress, onFailure).also { it.start() }
        }
        override fun playbackStopped() {
            retireAudioLevelConsumer(clearLevels = true)
            audioSpectrum.value = null
        }
    })
    @Volatile internal var recordingStartedAtMs = 0L
    /** OCC-PLAN-068 U6: optional operator-side sync-marker evidence for the active take's sidecar. */
    internal val subjectSyncMarkers = com.librestatic.opencinecam.SubjectSyncMarkerSlot()
    internal var timelapseAutoStopRunnable: Runnable? = null
    internal var takeLengthAutoStopRunnable: Runnable? = null
    internal var attachedPreviewSurface: Surface? = null
    /**
     * The RAW qualification step covers the viewfinder, so the camera rests while it is up: the surface waits in
     * [parkedPreviewSurface] and [attachedPreviewSurface] stays null, which keeps every preview start away until the step leaves.
     */
    private var rawQualificationPreviewHeld = false
    private var parkedPreviewSurface: Surface? = null
    /** The viewfinder surface was lost or replaced during a RAW take, which cannot reconfigure the session: reattach when it ends. */
    internal var rawTakePreviewStale = false
    internal var attachedPreviewRotationDegrees: Int = 0
    internal var cameraSwitchGeneration = 0L
    private var recoveryGeneration = 0L
    internal var pendingSwitchTarget: String? = null
    internal var pendingSwitchPrevious: String? = null
    internal var pendingStatusMessage: String? = null
    internal val perCameraZoom = mutableMapOf<String, Float>()
    internal val timecodeTracker by lazy { TimecodeTracker(
        continuationStore = com.librestatic.opencinecam.TimecodeContinuationFile(java.io.File(filesDir, "timecode-continuation.json"))) }
    internal var settings = CameraSettings()
    private var operatorStartupPending = true
    private var foldClosureTracker: FoldClosureTracker? = null
    internal val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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

    /**
     * PLAN-072: the drive's free space for the main thread (the REC ticker, the take start), sampled on its own thread so
     * a provider call never runs on main, least of all while a failing drive's loss must be handled there. Not
     * [storageExecutor], whose bounded queue refuses work when full. Two threads at most: a sample stalled on a failing
     * drive must not hold back the one of the drive chosen after it, and a third is refused until one returns.
     */
    private val driveFreeSpaceExecutor = java.util.concurrent.ThreadPoolExecutor(0, 2, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue()) { task -> Thread(task, "occ-drive-free") }
    internal val driveFreeSpace = DriveFreeSpaceCache(driveFreeSpaceExecutor) { driveFreeBytes(contentResolver, it.treeUri) }

    /** PLAN-072: removable volumes, for the destination and its disconnects; null when StorageManager is unavailable. */
    internal var recordingVolumeMonitor: RecordingVolumeMonitor? = null
    /** PLAN-072: USB storage devices attached recently, so an absent drive reads as starting; null when it could not be created. */
    internal var driveArrivalMonitor: com.librestatic.opencinecam.storage.UsbDriveArrivalMonitor? = null
    /** The destination the next encoded take would use, refreshed on volume and settings changes; resolved afresh at take start. */
    @Volatile internal var nextTakeDestination = ResolvedRecordingDestination(RecordingDestination.Phone, RecordingDestination.Phone, null)
    /** The running encoded take's destination, fixed at its start; null outside a take. */
    @Volatile internal var takeDestination: ResolvedRecordingDestination? = null
    internal var destinationRefreshJob: kotlinx.coroutines.Job? = null

    /**
     * PLAN-072 Delivery 4: the continuation owed to a drive take that lost its drive ([DriveContinuation]), the one being
     * started now (set on main only around the start call, read by [LocalBinder.capturePrimary] and [toggleRawTake]), and
     * the running take's own link when it is a continuation (its sidecar and the HUD read it; null otherwise).
     */
    internal val driveContinuation = DriveContinuation({ android.os.SystemClock.elapsedRealtime() })
    internal var continuationStarting: ContinuationRequest? = null
    @Volatile internal var takeContinuation: ContinuationRequest? = null

    /**
     * PLAN-072, main: the encoded take's drive left while the take was still starting (CAPTURING, its output already on
     * the drive), when the stop path refuses anything but RECORDING. The take is stopped for its lost drive as soon as it
     * records, so the drive stop, and its continuation decision, runs as for a drive lost mid-take. Cleared with the take.
     */
    @Volatile internal var driveLostAtTakeStart = false

    /**
     * Main: the volume events emitted before the running take's destination was resolved. A queued Leaving among them is
     * about a mount the take did not see: the drive mounted again before the take resolved it, so it does not stop the take.
     */
    internal var takeVolumeEvents = 0L

    /** A take start refused with its reason shown (ADR-0016): its output's cleanup runs, but it is not reported as a failure. */
    internal class TakeStartRefused(cause: Throwable) : java.io.IOException(cause)

    @Volatile internal var rawGateDestinationId = RawVideoQualificationStore.DESTINATION_MEDIASTORE_DOCUMENTS

    /** The take recording to a drive ended, and an interrupted RAW take's repair had waited for it: queue its pass now. */
    private val rawRepairDue: () -> Unit = { if (!serviceDestroyed) queueRecordingRecovery("a RAW repair waited for a take on a drive") }

    /** A drive take left pending was released only when its last native writer closed: queue the pass [relinquishTake] could not. */
    private val driveTakeReleased: () -> Unit = { if (!serviceDestroyed) queueRecordingRecovery("a drive take was left pending") }

    /** What a still recovery pass was asked for: the startup report published, or a mounted drive's scratch purged. */
    internal data class StillRecoveryRequest(val publishReport: Boolean = false, val scratchVolume: String? = null)
    /** What a journal pass was asked for: [cause] for the log; [startup] also runs the phone's RAW recovery and always publishes. */
    internal data class JournalRecoveryRequest(val cause: String, val startup: Boolean = false)

    /*
     * PLAN-072: [recoveryExecutor] runs one thread over a short queue, and a RAW repair can hold it for minutes, so its
     * work is coalesced instead of queued per request ([CoalescedPass]): at most one still pass and one journal pass wait
     * at a time, a request arriving while a pass runs queues exactly one follow-up, and every request's item (a volume
     * to purge, a cause, the startup report) reaches a pass. Not [storageExecutor]: recovery asks drives, and one that
     * stalls must not hold up the still saves queued there.
     */
    internal val stillRecoveryPass = CoalescedPass<StillRecoveryRequest>(recoveryExecutor) { runStillRecoveryPass(it) }
    internal val journalRecoveryPass = CoalescedPass<JournalRecoveryRequest>(recoveryExecutor) { runRecordingRecoveryPass(it) }

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
    /** What the take's embedded AAC path opened (PLAN-074 decision 11); set by the engine as it opens the input. */
    @Volatile internal var takeClipAudioInput: com.librestatic.opencinecam.camera.PcmInputEvidence? = null
    /** The AAC bitrate of a take whose audio is appended when it ends (PLAN-074 decision 10); null for any other take. */
    @Volatile internal var takeAppendedAudioBitrateBps: Int? = null
    /** The manual sync offset of that appended audio (PLAN-074 decision 12), read at take start; nanoseconds. */
    @Volatile internal var takeAppendedAudioSyncOffsetNs: Long = 0
    /** Samples the running take's camera settings from [cameraState] for the clip sidecar; null outside a take. */
    @Volatile internal var takeCameraTracker: ClipCameraTracker? = null
    /** Stable code of a Strict encoder refusal reported by the engine, consumed by the failed-start branch. */
    @Volatile internal var pendingStartRefusal: String? = null
    /** Bytes per minute the admitted take was projected to write; the live storage policy measures against it. */
    @Volatile internal var takeProjectedBytesPerMinute = 0L
    /** The free space a RAW take stops below: the room it was admitted with. Null for encoded takes, which use the capped headroom. */
    @Volatile internal var takeStopHeadroomBytes: Long? = null
    /** Capture-time limit of the admitted take (slow motion with a take length); null when unbounded. Frozen at admission. */
    @Volatile internal var takeLengthLimitMs: Long? = null
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
                recordingDestination = cameraState.value.recordingDestination.let {
                    if (takeDestination?.destination is RecordingDestination.UsbFolder && free != null) it.copy(driveFreeBytes = free) else it },
            )
            publishRawVideoStats()
            // PLAN-072: backstop for a drive lost while the take started (the flag, or a volume event this service missed).
            if (rawTake == null) stopTakeLostAtStart()
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
    @Volatile private var recordingLutLibrary: com.librestatic.opencinecam.LutLibrary? = null
    /**
     * [continuation]: set when this take is a continuation on the phone (PLAN-072 Delivery 4); its output reads it.
     * [admittedDestination]: where the storage admission measured the take; the start admits again if it changed.
     */
    internal data class RecordingLutIntent(val owner: CaptureTransferReservation, val lut: MonitorLut?, val captureNames: CaptureNameSnapshot,
        val continuation: ContinuationRequest? = null, val admittedDestination: RecordingDestination = RecordingDestination.Phone) {
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
        com.librestatic.opencinecam.storage.RecordingCaptureRecovery.onRawRepairDue = rawRepairDue
        com.librestatic.opencinecam.storage.RecordingCaptureRecovery.onDriveTakeReleased = driveTakeReleased
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
        queueStillRecovery(StillRecoveryRequest(publishReport = true), "service start")
        queueRecordingRecovery("service start", startup = true)
        recordingVolumeMonitor = try { RecordingVolumeMonitor(this) } catch (failure: Exception) {
            android.util.Log.e("RecordingDestination", "Removable volumes cannot be watched; takes go to the phone", failure)
            null
        }
        // The monitor's first read may report a drive mounted before this subscribes, and its events are not replayed, while
        // the startup pass above may have deferred that drive's takes: each drive mounted at subscription goes through the
        // mount path (recovery pass, still recovery, scratch purge), which is cheap when nothing waits on it. A drive
        // mounting meanwhile may arrive twice; the passes are idempotent.
        recordingVolumeMonitor?.let { monitor ->
            settingsScope.launch {
                monitor.events.onSubscription { monitor.mounted.value.forEach { emit(RecordingVolumeEvent.Mounted(it)) } }
                    .collect { onRecordingVolumeEvent(it) }
            }
        }
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
        // PLAN-074 decision 1: the audio input default comes from the device profile, which reads assets, so it is
        // resolved off the main thread; the preview audio monitor waits for it (previewAudioEligible), so it never
        // opens the pre-migration source. The settle goes through the repository, so the UI and applySettings follow.
        settingsScope.launch {
            val policy = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.librestatic.opencinecam.AudioInputPolicies.get(this@CaptureService)
            }
            if (serviceDestroyed) return@launch
            repository.settleAudioInput(policy)
            audioInputSettled = true
            startPreviewAudioMonitorIfEligible()
        }
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
                        if (attachedPreviewSurface == null && parkedPreviewSurface == null && cameraState.value.phase != CameraUiPhase.RECORDING) localBinder.detachPreview()
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
        listOf(it.selectedCameraId, it.selectedMode, it.targetVideoWidth, it.targetVideoHeight, it.targetFps, desiredGpuViewfinder(), selectedStillPhotoFormat(), selectedStillSize(), nightExtensionActive(), lowLightFpsActive(it, settings), currentLensLockBinding(), rawVideoSessionConfig())
    }

    // OCC-PLAN-071 RAW video. Main-thread state unless marked otherwise.
    internal val rawRecorder by lazy { RawVideoRecorder() }
    /** Serialises arming, disarming, take start and take stop (which joins the writer) off main. */
    internal val rawControl = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "occ-raw-control") }
    internal val rawQualificationStore by lazy { RawVideoQualificationStore.forContext(this) }
    /** Loaded on first use (one small file); [LocalBinder.refreshRawVideoQualifications] reloads it. */
    @Volatile internal var rawQualifications: RawVideoQualificationSet? = null
    internal val rawRaw10Sizes = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Int, Int>>>()
    /** Cameras that refused the RAW_VIDEO graph in this service session (gate FAILED). */
    internal val rawVideoFailedCameras = mutableSetOf<String>()
    /** The configuration (and near-lossless noise profile) the ring was last asked to arm for; null while disarmed. */
    internal var rawArmTarget: Pair<RawVideoConfig, com.librestatic.opencinecam.rawvideo.RawNoiseProfile?>? = null
    /** Set (on the control thread) when the ring for this configuration could not be planned or allocated. */
    @Volatile internal var rawArmNoMemory: RawVideoConfig? = null
    /** Why arming [rawArmNoMemory] failed: no RAM for the ring, or no encoder for a compressed configuration. */
    @Volatile internal var rawArmFailure: String = RawVideoServiceReasons.MEMORY
    /** The ring armed now (set on the control thread), for the runtime risk ([rawVideoRingTooSmall]); null while disarmed. */
    @Volatile internal var rawArmedRing: com.librestatic.opencinecam.rawvideo.RawVideoArmState? = null
    /** The mode RAW_VIDEO was entered from, restored when the camera refuses the RAW graph. */
    internal var rawModeFallback: CaptureMode? = null
    /** [destination] is where the take writes, resolved when it was admitted (PLAN-072 Delivery 3). */
    /**
     * [captureNames] and [audio] are what the take was admitted with; [continuation] is set when it continues on the phone
     * a RAW take whose drive was lost (PLAN-072 Delivery 4), and names its file and metadata.
     */
    internal class RawTakeSession(val takeId: String, val config: RawVideoConfig, val destination: ResolvedRecordingDestination,
        val captureNames: CaptureNameSnapshot, val audio: Boolean, val continuation: ContinuationRequest? = null) {
        var started = false
        var stopRequested = false
    }
    internal var rawTake: RawTakeSession? = null

    /**
     * A RAW video qualification run (PLAN-071 decision 10). It owns the camera while it lasts: the
     * preview is stopped, the RAW ring disarmed, and nothing reopens the camera until it ends.
     */
    internal class RawQualificationSession(
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
    @Volatile internal var rawQualificationSession: RawQualificationSession? = null
    /** A qualification passed before the cameras were prepared (onboarding): RAW_VIDEO is selected once they are. */
    internal var rawQualificationSelectPending = false

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

    internal val captureCountdown = CaptureCountdown<List<Any?>>()
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
        /** PLAN-072: the settings adopted or cleared the recording folder; re-reads it for the next take and the HUD. */
        fun refreshRecordingDestination() { mainHandler.post { this@CaptureService.refreshRecordingDestination() } }

        fun reconnectAudioListening() {
            if (serviceDestroyed) return
            audioListeningController?.configure(settings.audioListening, settings.audioListeningOutputDeviceId)
            audioListeningController?.reconnect()
        }

        val states: StateFlow<CaptureState> = state.asStateFlow()
        val cameraStates: StateFlow<CameraUiState> = cameraState.asStateFlow()
        /** The preview monitor's spectrum before and after the equaliser, while [setAudioSpectrumWanted] is on (PLAN-074 decision 13). */
        val audioSpectra: StateFlow<com.librestatic.opencinecam.camera.AudioSpectrum?> = audioSpectrum.asStateFlow()

        /**
         * The equaliser editor is showing: the preview monitor (this one and each later one) computes [audioSpectra].
         * With no viewfinder attached, the monitor runs for the editor alone and stops with it.
         */
        fun setAudioSpectrumWanted(wanted: Boolean) {
            if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { setAudioSpectrumWanted(wanted) }; return }
            if (serviceDestroyed) return
            audioSpectrumWanted = wanted
            previewAudioMonitor?.spectrumWanted = wanted
            if (wanted) {
                // Away from the viewfinder no monitor runs: the editor starts one for itself.
                if (previewAudioMonitor == null) startPreviewAudioMonitorIfEligible()
            } else {
                // The page left: the snippet is the user's voice, so it goes with it.
                eqSnippetAudition.end(keepSnippet = false)
                audioSpectrum.value = null
                if (previewAudioMonitor != null && !previewAudioEligible()) stopPreviewAudioMonitor(clearLevels = true)
            }
        }

        /** The equaliser editor's snippet audition (PLAN-074 decision 13), held in memory only. */
        val eqAudition: StateFlow<EqAuditionState> = eqAuditionState.asStateFlow()

        /** Records the raw input for up to [EQ_SNIPPET_MAX_MS], replacing any kept snippet; refused when no monitor runs for the editor. */
        fun startEqSnippetRecording() = onMainForEqAudition { startRecording() }

        /** Keeps what was recorded, unless it is shorter than half a second. */
        fun stopEqSnippetRecording() = onMainForEqAudition { stopRecording() }

        /** Loops the kept snippet through the applied curve until stopped; the microphone rests meanwhile. */
        fun playEqSnippet() = onMainForEqAudition { play() }

        fun stopEqSnippetPlayback() = onMainForEqAudition { stopPlayback() }

        fun discardEqSnippet() = onMainForEqAudition { discard() }

        private fun onMainForEqAudition(action: EqAudition.() -> Unit) {
            if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { onMainForEqAudition(action) }; return }
            if (!serviceDestroyed) eqSnippetAudition.action()
        }

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
                    // The device profile drops a source that clips and names the fallback (PLAN-074 decision 1).
                    val capabilities = runCatching {
                        AndroidProfessionalAudioProbe(this@CaptureService).probe(requestedInput)
                            .forDevice(com.librestatic.opencinecam.AudioInputPolicies.get(this@CaptureService))
                    }
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
            // Settings changes detach an absent viewfinder too; the editor's snippet audition carries on.
            stopPreviewAudioMonitor(clearLevels = true, endEqAudition = false)
            attachedPreviewSurface = null
            parkedPreviewSurface = null
            // The equaliser editor opened before the viewfinder's surface went: it keeps a monitor of its own.
            if (audioSpectrumWanted) startPreviewAudioMonitorIfEligible()
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
                val audioUpdate = previewAudioUpdate(previous, settings)
                if (previous.audioInputKey != settings.audioInputKey && !takeInProgress()) refreshAudioCapabilities()
                val monitor = previewAudioMonitor
                // An equaliser being edited updates the running monitor; reopening the microphone at each drag would drop it.
                // While the snippet plays no monitor may run; a curve dragged meanwhile must not clear the levels.
                if ((monitor == null && !eqSnippetAudition.playing) || audioUpdate == PreviewAudioUpdate.RESTART) startPreviewAudioMonitorIfEligible()
                else if (audioUpdate == PreviewAudioUpdate.EQ_CURVE)
                    (cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings).let { applied ->
                        monitor?.setEqCurve(applied.appliedAudioEqCurve, applied.audioEqProfileId)
                    }
                // The snippet plays through the curve being edited, live, with the gain the monitor would apply.
                (cameraState.value.audioCapabilities?.let(settings::normalizedFor) ?: settings).let { applied ->
                    eqSnippetAudition.updatePlayback(applied.appliedAudioEqCurve, applied.audioRecordingGain)
                }
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
            resetAudioOverload()
            cameraState.value = cameraState.value.copy(audioClipLatched = false, audioInputClipLatched = false, audioRecordedClipLatched = false,
                audioInputOverloaded = false)
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
                        if (!changed) { scheduleTimelapseAutoStop(); scheduleTakeLengthAutoStop() }
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
            val takeLimitMs = com.librestatic.opencinecam.takeLengthLimitMs(current.selectedMode, settings,
                current.activeVideoProfile?.constrainedHighSpeed == true)
            admissionWarning = null
            takeSoftwareEncoder = null
            takeCodecFallback = false
            pendingStartRefusal = null
            takeProjectedBytesPerMinute = projectedBytesPerMinute
            // PLAN-072: measured where the take will go (the drive, or the phone when it falls back; a continuation, the phone).
            val continuation = continuationStarting?.takeIf { it.mode == current.selectedMode }
            val admittedDestination = if (continuation != null) RecordingDestination.Phone else resolveTakeDestination().destination
            val admissionFree = if (continuation != null) phoneVolumeFreeBytes() else destinationFreeBytes(admittedDestination)
            takeStopHeadroomBytes = null
            takeLengthLimitMs = takeLimitMs
            when (val admission = admitRecordingStorage(settings.recordingPolicy, projectedBytesPerMinute, admissionFree, takeLimitMs)) {
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
                        resetAudioOverload()
                        cameraState.value = cameraState.value.copy(audioClipLatched = false, audioInputClipLatched = false, audioRecordedClipLatched = false,
                            audioInputOverloaded = false, audioLevels = null)
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
                        val reportInputFallback = newTakeInputFallbackReporter()
                        takeClipAudioInput = null
                        val recordingLutForTake = frozenRecordingLut()
                        // PLAN-074 decision 10: the recorder takes video only; the audio is captured here and appended
                        // to the MP4 when the take ends.
                        val appendsAudio = appendsHighSpeedAudio(
                            constrainedHighSpeed = current.activeVideoProfile?.constrainedHighSpeed == true,
                            offSpeed = settings.videoOffSpeed,
                            audioRequested = audioRequested,
                            format = audioSettings.audioOutputFormat,
                            recordingLut = recordingLutForTake != null,
                            anamorphic = settings.anamorphicSqueeze.isActive,
                        )
                        takeAppendedAudioBitrateBps = (audioSettings.audioBitrateKbps * 1_000).takeIf { appendsAudio }
                        takeAppendedAudioSyncOffsetNs = if (appendsAudio) audioSettings.audioSyncOffsetMs * 1_000_000L else 0
                        val embeddedAudio = if (audioRequested && !appendsAudio && audioSettings.audioOutputFormat == AudioOutputFormat.AAC_MP4) {
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
                                sourceSelection = audioSettings.audioSource,
                                eqCurve = audioSettings.appliedAudioEqCurve,
                                eqProfileId = audioSettings.appliedAudioEqProfileId,
                                onInputOpened = { evidence -> takeClipAudioInput = evidence; reportInputFallback(evidence.inputFallbackReason) },
                                syncOffsetNs = audioSettings.audioSyncOffsetMs * 1_000_000L,
                            )
                        } else null
                        when (val start = foreground.start(audioRequested)) {
                            is ForegroundRecordingStart.Rejected -> {
                                clearTakeAppendedAudio()
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
                        val separateClock = if (embeddedAudio == null && !appendsAudio) com.librestatic.opencinecam.camera.CaptureEpochClock(
                            current.descriptor?.timestampSourceRealtime == true, audioSettings.audioSampleRateHz) else null
                        audioSidecarRecorder = when {
                            !audioRequested -> null
                            // The take's own WAV until the append: 16-bit, what the AAC encoder takes.
                            appendsAudio ->
                                WavAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings.copy(productionSlate = admittedSlate, audioBitDepth = com.librestatic.opencinecam.media.audio.AudioBitDepth.PCM_16), captureClock = null, onAudioLevel = onAudioLevel, recoveryGroup = output.recoveryGroup, listeningSink = listeningSink, inputGuard = takeInput.guard)
                            audioSettings.audioOutputFormat == AudioOutputFormat.WAV_PCM ->
                                WavAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings.copy(productionSlate = admittedSlate), captureClock = separateClock, onAudioLevel = onAudioLevel, recoveryGroup = output.recoveryGroup, listeningSink = listeningSink, inputGuard = takeInput.guard)
                            audioSettings.audioOutputFormat == AudioOutputFormat.FLAC ->
                                FlacAudioSidecarRecorder.create(this@CaptureService, output.displayName, audioSettings.copy(productionSlate = admittedSlate), captureClock = separateClock, onAudioLevel = onAudioLevel, recoveryGroup = output.recoveryGroup, listeningSink = listeningSink, inputGuard = takeInput.guard)
                            else -> null
                        }
                        audioSidecarRecorder?.let { reportInputFallback(it.inputFallbackReason) }
                        activeRecordingAudioLabel = when {
                            !audioRequested -> getString(R.string.capture_no_audio)
                            embeddedAudio != null || appendsAudio -> "AAC ${kiloHertzText(audioSettings.audioSampleRateHz, messageLocale)} kHz · ${audioSettings.audioBitrateKbps} kbps · ${audioSettings.audioChannels} ch"
                            else -> "${audioSettings.audioOutputFormat.name.substringBefore('_')} ${audioSettings.audioBitDepth.bits}-bit · ${kiloHertzText(audioSettings.audioSampleRateHz, messageLocale)} kHz · ${audioSettings.audioChannels} ch"
                        }
                        cameraState.update { it.copy(phase = CameraUiPhase.CAPTURING, recordingProjectRate = settings.videoProjectRate.takeIf { settings.videoOffSpeed },
                            message = getString(R.string.capture_preparing_video, activeRecordingAudioLabel)) }
                        dispatchTransferCapture()
                        reserveRecordingRecoveryGuard(output)
                        // Constrained high-speed takes must reach the encoder surface directly: the GPU
                        // path's camera texture only receives the 30 fps preview share of each batch.
                        // Off-speed then plays the high-speed capture back at the project rate.
                        // Audio the app appends afterwards (and its gain) never touches the recorder.
                        val directHighSpeed = current.activeVideoProfile?.constrainedHighSpeed == true &&
                            recordingLutForTake == null && (appendsAudio || audioSidecarRecorder == null &&
                            activeRecordingGain?.enabled != true) &&
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
                                clearTakeAppendedAudio()
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
                        clearTakeAppendedAudio()
                        runCatching { videoOutput?.discardNeverRecorded() }
                        videoOutput = null
                        clearTakeDestination()
                        abortTransferPublication()
                        foreground.stop()
                        retireRecordingRecoveryGuards(previewEngine.recordingOutputRetirement())
                        if (failure !is TakeStartRefused) fail("video-output-failed", failureText(R.string.capture_video_output_failed, failure))
                        false
                    }
                }
                CaptureMode.LOG, CaptureMode.HLG -> {
                    try {
                        val hlgTake = current.selectedMode == CaptureMode.HLG
                        stopPreviewAudioMonitor(clearLevels = false)
                        resetAudioOverload()
                        cameraState.value = cameraState.value.copy(audioClipLatched = false, audioInputClipLatched = false, audioRecordedClipLatched = false,
                            audioInputOverloaded = false, audioLevels = null)
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
                        val reportInputFallback = newTakeInputFallbackReporter()
                        takeClipAudioInput = null
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
                                sourceSelection = audioSettings.audioSource,
                                eqCurve = audioSettings.appliedAudioEqCurve,
                                eqProfileId = audioSettings.appliedAudioEqProfileId,
                                onInputOpened = { evidence -> takeClipAudioInput = evidence; reportInputFallback(evidence.inputFallbackReason) },
                                syncOffsetNs = audioSettings.audioSyncOffsetMs * 1_000_000L,
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
                        audioSidecarRecorder?.let { reportInputFallback(it.inputFallbackReason) }
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
                        if (failure !is TakeStartRefused) fail("log-output-failed", failureText(R.string.capture_log_output_failed, failure))
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
                        if (failure !is TakeStartRefused) fail("timelapse-output-failed", failureText(R.string.capture_timelapse_output_failed, failure))
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
        com.librestatic.opencinecam.storage.RecordingCaptureRecovery.run {
            if (onRawRepairDue === rawRepairDue) onRawRepairDue = null
            if (onDriveTakeReleased === driveTakeReleased) onDriveTakeReleased = null
        }
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
        eqSnippetAudition.end(keepSnippet = false)
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
    internal fun refuseTakeStart(code: String, message: String) {
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
    internal fun failureText(@StringRes notice: Int, failure: Throwable): String =
        listOfNotNull(getString(notice), failure.message?.takeIf { it.isNotBlank() }).joinToString(" · ")

    internal val messageLocale: Locale get() = resources.configuration.locales[0]

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
        /** The `kind` of a camera check's result row ([com.librestatic.opencinecam.rawvideo.RawVideoQualifier]'s CAMERA stage). */
        internal const val RAW_QUALIFICATION_CAMERA_KIND = "camera"
        internal const val LOST_INPUT_STOP_ATTEMPTS = 20
        internal const val LOST_INPUT_STOP_RETRY_MS = 250L
        /** How long a take's admission waits on main for the drive's free space (one provider IPC when it answers). */
        internal const val DRIVE_ADMISSION_WAIT_MS = 500L
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
