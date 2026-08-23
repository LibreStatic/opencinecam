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
    LOG(true),
    APV(true),
    RAW_VIDEO(true),
}

enum class ModeGateState { AVAILABLE, CANDIDATE, UNSUPPORTED, FAILED }

data class CameraUiState(
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
    val awbState: Int? = null,
    val reportedColorTemperatureK: Int? = null,
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
    val targetFps: Int = 30,
    val targetVideoWidth: Int = 1920,
    val targetVideoHeight: Int = 1080,
    val effectiveFps: Double? = null,
    val recordingElapsedMs: Long = 0L,
    val recordingWidth: Int? = null,
    val recordingHeight: Int? = null,
    val availableStorageBytes: Long? = null,
    val burstCaptured: Int = 0,
    val burstExpected: Int = 0,
    val histogram: List<Float> = emptyList(),
    val redHistogram: List<Float> = emptyList(),
    val greenHistogram: List<Float> = emptyList(),
    val blueHistogram: List<Float> = emptyList(),
    val analysisUpdatedAtMs: Long = 0L,
    val audioLevels: AudioLevelSnapshot? = null,
    val audioClipLatched: Boolean = false,
    val audioMonitoringActive: Boolean = false,
    val zebraCells: List<Boolean> = emptyList(),
    val focusCells: List<Boolean> = emptyList(),
    val lastSavedUri: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val messageTransient: Boolean = false,
    val focusPullActive: Boolean = false,
    val focusPullTargetDiopters: Float? = null,
    val focusMarks: Map<String, Float> = emptyMap(),
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
) {
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
        val videoProfileModes = setOf(CaptureMode.VIDEO, CaptureMode.TIME_LAPSE)
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
