/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.LockState
import com.librestatic.opencinecam.camera.Camera2LogProfile
import com.librestatic.opencinecam.camera.Camera2VideoProfile
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.TapFocusState
import com.librestatic.opencinecam.camera.ZoomAnchor
import com.librestatic.opencinecam.camera.FocusPullEasing
import com.librestatic.opencinecam.camera.SmpteTimecode
import com.librestatic.opencinecam.camera.TimecodeMode
import com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities

enum class CameraUiPhase {
    PERMISSION_REQUIRED,
    PREPARING,
    READY,
    OPENING,
    PREVIEWING,
    CAPTURING,
    RECORDING,
    SAVED,
    ERROR,
}

enum class CaptureMode(val experimental: Boolean = false) {
    PHOTO,
    RAW_PHOTO(true),
    BURST,
    VIDEO,
    SLOW_MOTION,
    TIME_LAPSE,
    BRACKET,
    LIGHT_TRAIL,
    LOG,
    APV(true),
    RAW_VIDEO(true),
}

enum class ModeGateState { AVAILABLE, CANDIDATE, UNSUPPORTED, FAILED }

data class CameraUiState(
    val operatorLutStatus: com.librestatic.opencinecam.camera.OperatorLutStatus = com.librestatic.opencinecam.camera.OperatorLutStatus(),
    val subjectLutStatus: com.librestatic.opencinecam.camera.OperatorLutStatus = com.librestatic.opencinecam.camera.OperatorLutStatus(),
    val recordingLutStatus: com.librestatic.opencinecam.camera.OperatorLutStatus = com.librestatic.opencinecam.camera.OperatorLutStatus(),
    val recordingLutSelectionPending: Boolean = false,
    val recordingRecovery: com.librestatic.opencinecam.storage.RecordingRecoveryReport? = null,
    val stillRecovery: com.librestatic.opencinecam.storage.StillRecoveryReport? = null,
    val transferRetirementPending: Boolean = false,
    val audioRetirementPending: Boolean = false,
    val recordingWhiteBalanceStatus: com.librestatic.opencinecam.camera.RecordingWhiteBalanceStatus = com.librestatic.opencinecam.camera.RecordingWhiteBalanceStatus.IDLE,
    val reportedAwbLocked: Boolean? = null,
    val gpuViewfinder: Boolean = false,
    val selfRecordingActive: Boolean = false,
    val countdownSeconds: Int = 0,
    val captureActionGeneration: Long = 0L,
    val phase: CameraUiPhase = CameraUiPhase.PREPARING,
    val cameras: List<Camera2CameraDescriptor> = emptyList(),
    val audioCapabilities: ProfessionalAudioCapabilities? = null,
    val selectedCameraId: String? = null,
    val selectedMode: CaptureMode = CaptureMode.PHOTO,
    val modeGates: Map<CaptureMode, ModeGateState> = defaultModeGates,
    val sensitivityIso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val afState: Int? = null,
    val reportedAfMode: Int? = null,
    val submittedAfMode: Int? = null,
    val awbState: Int? = null,
    val reportedColorTemperatureK: Int? = null,
    val reportedColorTint: Int? = null,
    val reportedOpticalStabilization: Int? = null,
    val reportedVideoStabilization: Int? = null,
    val reportedNoiseReduction: Int? = null,
    val reportedEdgeEnhancement: Int? = null,
    val reportedCropRegion: List<Int>? = null,
    val submittedImageProcessing: com.librestatic.opencinecam.camera.ImageProcessingDefaults? = null,
    val reportedExposureMode: com.librestatic.opencinecam.camera.ExposureMode? = null,
    val reportedAntibanding: Int? = null,
    val requestedExposureMode: com.librestatic.opencinecam.camera.ExposureMode = com.librestatic.opencinecam.camera.ExposureMode.AUTO,
    val exposureControlUnavailable: Boolean = false,
    val exposureClamped: Boolean = false,
    val requestedIso: Int? = null,
    val requestedExposureTimeNs: Long? = null,
    val requestedFocusDiopters: Float? = null,
    val tapFocusState: TapFocusState = TapFocusState.IDLE,
    val aeLockSupported: Boolean = false,
    val aeLockActive: Boolean = false,
    val afLockSupported: Boolean = false,
    val afLockState: LockState = LockState.OFF,
    val requestedWhiteBalance: WhiteBalanceSelection = WhiteBalanceSelection.Auto,
    val requestedAeCompensationIndex: Int = 0,
    val torchEnabled: Boolean = false,
    val effectiveSettings: CameraSettings? = null,
    val settingsPending: Boolean = false,
    val pendingPresetName: String? = null,
    val photoFlashReport: com.librestatic.opencinecam.camera.PhotoFlashReport? = null,
    val torchReported: Boolean? = null,
    val torchStrengthReported: Int? = null,
    val targetFps: Int = 30,
    val targetVideoWidth: Int = 1920,
    val targetVideoHeight: Int = 1080,
    val effectiveFps: Double? = null,
    val recordingFinalizing: Boolean = false,
    val recordingPauseStatus: com.librestatic.opencinecam.camera.TimelapsePauseStatus? = null,
    val recordingPausePending: Boolean = false,
    val recordingAvTiming: com.librestatic.opencinecam.camera.CaptureEpochReport? = null,
    val foldClosureSensorAvailable: Boolean? = null,
    val recordingElapsedMs: Long = 0L,
    val recordingProjectRate: com.librestatic.opencinecam.camera.CaptureFrameRate? = null,
    val recordingWidth: Int? = null,
    val recordingHeight: Int? = null,
    val availableStorageBytes: Long? = null,
    val accumulationFrames: Int = 0,
    val accumulationElapsedMs: Long = 0,
    val accumulationTargetMs: Long = 0,
    val accumulationSaving: Boolean = false,
    val accumulationFinishing: Boolean = false,
    val lastAccumulationPublication: com.librestatic.opencinecam.storage.AccumulationPublication? = null,
    val bracketCaptured: Int = 0,
    val bracketExpected: Int = 0,
    val bracketSaving: Boolean = false,
    val lastBracketPublication: com.librestatic.opencinecam.storage.BracketPublication? = null,
    val burstCaptured: Int = 0,
    val burstExpected: Int = 0,
    val burstSaving: Boolean = false,
    val lastBurstPublication: com.librestatic.opencinecam.storage.BurstPublication? = null,
    val histogram: List<Float> = emptyList(),
    val redHistogram: List<Float> = emptyList(),
    val greenHistogram: List<Float> = emptyList(),
    val blueHistogram: List<Float> = emptyList(),
    val analysisUpdatedAtMs: Long = 0L,
    val monitoringScopes: com.librestatic.opencinecam.camera.MonitoringScopeFrame? = null,
    val analysisIntervalMs: Long = 0L,
    /** Why the engine has stopped producing scope analysis (THERMAL under severe device heat); NONE while it runs. */
    val analysisSuspension: com.librestatic.opencinecam.camera.AnalysisSuspension = com.librestatic.opencinecam.camera.AnalysisSuspension.NONE,
    val audioLevels: AudioLevelSnapshot? = null,
    val audioClipLatched: Boolean = false,
    val audioMonitoringActive: Boolean = false,
    val audioListeningStatus: AudioListeningStatus = AudioListeningStatus(),
    val audioListeningOutputs: List<AudioListeningDevice> = emptyList(),
    val zebraCells: List<Boolean> = emptyList(),
    val focusCells: List<Boolean> = emptyList(),
    val stillCapturePending: Boolean = false,
    val lastStillPublication: com.librestatic.opencinecam.storage.StillPublication? = null,
    val lastSavedUri: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val messageTransient: Boolean = false,
    val focusPullActive: Boolean = false,
    val focusPullTargetDiopters: Float? = null,
    val focusMarks: Map<String, Float> = emptyMap(),
    val timecodeDisplay: String? = null,
    val zoomMinRatio: Float = 1f,
    val zoomMaxRatio: Float = 1f,
    val zoomRatio: Float = 1f,
    val zoomEffectiveRatio: Float? = null,
    val opticalAnchors: List<ZoomAnchor> = emptyList(),
    val zoomSupported: Boolean = false,
    val zoomHfrSupported: Boolean = false,
    val timelapseIntervalMs: Long = 500L,
    val timelapseLimitMode: TimeLapseLimitMode = TimeLapseLimitMode.UNLIMITED,
    val timelapseFrameCount: Int = 300,
    val timelapseDurationMs: Long = 3_600_000L,
    val timelapseFramesCaptured: Int = 0,
    val timelapseMissedIntervals: Long = 0,
    val timelapseEncoder: String? = null,
    val timelapseHardwareEncoder: Boolean? = null,
    /** OCC-PLAN-068 U7: derived framing only (no face geometry); never persisted. */
    val subjectFraming: com.librestatic.opencinecam.camera.SubjectFramingStatus = com.librestatic.opencinecam.camera.SubjectFramingStatus(),
) {
    val whiteBalancePreparing: Boolean
        get() = phase == CameraUiPhase.CAPTURING && recordingWhiteBalanceStatus in setOf(
            com.librestatic.opencinecam.camera.RecordingWhiteBalanceStatus.CONVERGING,
            com.librestatic.opencinecam.camera.RecordingWhiteBalanceStatus.LOCKING)

    val capturePreparationCancelable: Boolean get() = phase == CameraUiPhase.CAPTURING && (transferRetirementPending || audioRetirementPending || whiteBalancePreparing)

    val captureControlsLocked: Boolean get() = stillCapturePending || (phase == CameraUiPhase.CAPTURING && selectedMode in setOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL)) ||
        ((structuralSettingsFrozen || phase == CameraUiPhase.CAPTURING) && effectiveSettings?.operation?.lockDuringTake == true)

    val structuralSettingsFrozen: Boolean
        get() = stillCapturePending || recordingFinalizing || phase == CameraUiPhase.RECORDING ||
            (phase == CameraUiPhase.CAPTURING && selectedMode in setOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.LIGHT_TRAIL, CaptureMode.VIDEO, CaptureMode.LOG,
                CaptureMode.TIME_LAPSE, CaptureMode.SLOW_MOTION, CaptureMode.APV, CaptureMode.RAW_VIDEO))

    val descriptor: Camera2CameraDescriptor?
        get() = cameras.firstOrNull { it.cameraId == selectedCameraId }

    val availableTargetFps: List<Int>
        get() = descriptor?.let { descriptor ->
            when {
                selectedMode == CaptureMode.LOG -> descriptor.logProfiles
                    .filter { it.size.width == targetVideoWidth && it.size.height == targetVideoHeight }
                    .map { it.fps }.distinct().sorted()
                selectedMode in videoProfileModes -> descriptor.videoProfiles
                    .filter { it.size.width == targetVideoWidth && it.size.height == targetVideoHeight }
                    .map { it.fps }.distinct().sorted()
                else -> descriptor.availableFixedFps
            }
        }.orEmpty()

    val availableVideoProfiles: List<Camera2VideoProfile>
        get() = descriptor?.videoProfiles.orEmpty()

    val availableLogProfiles: List<Camera2LogProfile>
        get() = descriptor?.logProfiles.orEmpty()

    val availableVideoSizes: List<Pair<Int, Int>>
        get() = when (selectedMode) {
            CaptureMode.LOG -> availableLogProfiles.map { it.size.width to it.size.height }
            else -> availableVideoProfiles.map { it.size.width to it.size.height }
        }
            .distinct().sortedWith(compareByDescending<Pair<Int, Int>> { it.first.toLong() * it.second }.thenByDescending { it.first })

    val activeVideoProfile: Camera2VideoProfile?
        get() = availableVideoProfiles.firstOrNull {
            it.size.width == targetVideoWidth && it.size.height == targetVideoHeight && it.fps == targetFps
        }

    val activeLogProfile: Camera2LogProfile?
        get() = availableLogProfiles.firstOrNull {
            it.size.width == targetVideoWidth && it.size.height == targetVideoHeight && it.fps == targetFps
        }

    /** True when the active camera advertises a non-trivial AE compensation range. */
    val aeCompensationSupported: Boolean
        get() = descriptor?.aeCompensationRange?.let { it.lower < it.upper } == true

    /** Discrete index range for the EV slider, or null when unsupported. */
    val aeCompensationIndexRange: IntRange?
        get() = descriptor?.aeCompensationRange?.let { it.lower..it.upper }

    /** Current compensation value expressed in EV units (e.g. -1.33, +0.5), or null when unsupported. */
    val aeCompensationEv: Float?
        get() = descriptor?.let { d ->
            d.aeCompensationRange?.let { requestedAeCompensationIndex * d.aeCompensationStep }
        }

    companion object {
        /**
         * The single rule the mode selectors and the service share for manual mode selection. A
         * candidate, such as slow motion, has no integrated capture path yet: offering it would
         * accept a tap the service then refuses.
         */
        fun isModeSelectable(gate: ModeGateState?): Boolean = gate == ModeGateState.AVAILABLE

        val videoProfileModes = setOf(CaptureMode.VIDEO, CaptureMode.TIME_LAPSE)
        /** Modes whose recording rate the operator chooses (slow motion runs on VIDEO). */
        val frameRateModes = setOf(CaptureMode.VIDEO, CaptureMode.LOG)
        val resolutionProfileModes = setOf(CaptureMode.VIDEO, CaptureMode.LOG, CaptureMode.TIME_LAPSE)
        val defaultModeGates: Map<CaptureMode, ModeGateState> = mapOf(
            CaptureMode.PHOTO to ModeGateState.AVAILABLE,
            CaptureMode.RAW_PHOTO to ModeGateState.AVAILABLE,
            CaptureMode.BURST to ModeGateState.AVAILABLE,
            CaptureMode.VIDEO to ModeGateState.AVAILABLE,
            CaptureMode.SLOW_MOTION to ModeGateState.CANDIDATE,
            CaptureMode.TIME_LAPSE to ModeGateState.AVAILABLE,
            CaptureMode.BRACKET to ModeGateState.AVAILABLE,
            CaptureMode.LIGHT_TRAIL to ModeGateState.AVAILABLE,
            CaptureMode.LOG to ModeGateState.CANDIDATE,
            CaptureMode.APV to ModeGateState.UNSUPPORTED,
            CaptureMode.RAW_VIDEO to ModeGateState.FAILED,
        )
    }
}
