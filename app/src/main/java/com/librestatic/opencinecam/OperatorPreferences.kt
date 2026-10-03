/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.ExposureSelection
import com.librestatic.opencinecam.camera.WhiteBalanceSelection

enum class OperatorAction {
    NONE, SYSTEM_VOLUME, CAPTURE, TORCH, TORCH_LEVEL, PEAKING, ZEBRA, HISTOGRAM,
    VIEW_ASSIST, AUTO_FOCUS, FOCUS_A, FOCUS_B, PRESET_C1, PRESET_C2, EXTERIOR, CONTROL_LOCK,
    WAVEFORM, VECTORSCOPE, FALSE_COLOR,
}

/**
 * Scope toggles the capture row always offers after the three F-keys, so a scope never hides in
 * Settings; one already assigned to an F-key is not repeated.
 */
val OperatorQuickToggles: List<OperatorAction> = listOf(OperatorAction.WAVEFORM, OperatorAction.VECTORSCOPE, OperatorAction.FALSE_COLOR)

/** Actions that only drive scope analysis, which device heat can suspend. */
private val ScopeActions = setOf(OperatorAction.PEAKING, OperatorAction.ZEBRA, OperatorAction.HISTOGRAM,
    OperatorAction.WAVEFORM, OperatorAction.VECTORSCOPE, OperatorAction.FALSE_COLOR)
enum class StartupMode { PHOTO, VIDEO, LAST }

data class OperatorPreferences(
    val button1: OperatorAction = OperatorAction.TORCH,
    val button2: OperatorAction = OperatorAction.PEAKING,
    val button3: OperatorAction = OperatorAction.VIEW_ASSIST,
    val volumeUp: OperatorAction = OperatorAction.SYSTEM_VOLUME,
    val volumeDown: OperatorAction = OperatorAction.SYSTEM_VOLUME,
    val startupMode: StartupMode = StartupMode.PHOTO,
    val restoreExposureWhiteBalance: Boolean = true,
    val restoreTorch: Boolean = false,
    val lockDuringTake: Boolean = false,
) {
    val buttons: List<OperatorAction> get() = listOf(button1, button2, button3)
    init { require(buttons.none { it == OperatorAction.SYSTEM_VOLUME }) }
}

/** Applied once on service creation, never on window recreation or a fold transition. */
fun CameraSettings.forOperatorStartup(): CameraSettings = copy(
    flashEnabled = flashEnabled && operation.restoreTorch,
    exposure = if (operation.restoreExposureWhiteBalance) exposure else ExposureSelection(),
    whiteBalance = if (operation.restoreExposureWhiteBalance) whiteBalance else WhiteBalanceSelection.Auto,
)
fun OperatorPreferences.startupCaptureMode(last: CaptureMode?, available: Set<CaptureMode>): CaptureMode {
    val requested = when (startupMode) { StartupMode.PHOTO -> CaptureMode.PHOTO; StartupMode.VIDEO -> CaptureMode.VIDEO; StartupMode.LAST -> last ?: CaptureMode.PHOTO }
    return requested.takeIf { it in available } ?: CaptureMode.PHOTO
}

/** Main-thread input latch: one down activates; repeats/up never activate another action. */
class OperatorKeyLatch {
    private val held = mutableSetOf<Int>()
    fun clear() { held.clear() }
    fun dispatch(key: Int, down: Boolean, repeat: Int, cancelled: Boolean, eligible: Boolean,
        action: OperatorAction, perform: (OperatorAction) -> Unit): Boolean {
        if (!down) return held.remove(key)
        if (key in held) return true
        if (!eligible || cancelled || repeat != 0 || action == OperatorAction.SYSTEM_VOLUME) return false
        held.add(key)
        if (action != OperatorAction.NONE) perform(action)
        return true
    }
}

/**
 * Latched state of the actions that turn something on and leave it on; momentary actions
 * (a capture, a focus pull, a level step) have none and return null. [OperatorAction.EXTERIOR]
 * latches on a display session that only the UI layer observes, so it is resolved there.
 *
 * The state is what the engine is doing, not what was last requested (ADR-0030): a locked take
 * keeps the torch it started with, a torch the camera or profile cannot light is off, and view
 * assist only exists in LOG.
 */
fun operatorActionToggleState(action: OperatorAction, settings: CameraSettings, state: CameraUiState): Boolean? {
    val effective = state.effectiveSettings ?: settings
    return when (action) {
        OperatorAction.TORCH -> effective.flashEnabled && !state.operatorHighSpeed() && state.descriptor?.torchCapabilities?.available == true
        OperatorAction.PEAKING -> effective.peakingEnabled
        OperatorAction.ZEBRA -> effective.zebraEnabled
        OperatorAction.HISTOGRAM -> effective.histogramEnabled
        OperatorAction.WAVEFORM -> effective.monitoring.waveformEnabled
        OperatorAction.VECTORSCOPE -> effective.monitoring.vectorscopeEnabled
        OperatorAction.FALSE_COLOR -> effective.monitoring.falseColorEnabled
        OperatorAction.VIEW_ASSIST -> effective.logViewAssistEnabled && state.selectedMode == CaptureMode.LOG
        OperatorAction.CONTROL_LOCK -> effective.operation.lockDuringTake
        else -> null
    }
}

private fun CameraUiState.operatorHighSpeed(): Boolean =
    if (selectedMode == CaptureMode.LOG) activeLogProfile?.constrainedHighSpeed == true
    else selectedMode in CameraUiState.videoProfileModes && activeVideoProfile?.constrainedHighSpeed == true

/**
 * True when [action] only drives scope analysis that the engine has suspended because the device is
 * too hot. The setting itself is kept; the button reads as unavailable until analysis resumes.
 */
fun operatorActionThermallyPaused(action: OperatorAction, state: CameraUiState): Boolean =
    state.analysisSuspension == com.librestatic.opencinecam.camera.AnalysisSuspension.THERMAL &&
        action in ScopeActions

/**
 * Why an operator key cannot act right now, so a tap can say so instead of doing nothing. The keys
 * stay on screen in every mode: a LOG-only key seen in PHOTO teaches the operator it exists.
 */
enum class OperatorUnavailableReason {
    /** NONE, SYSTEM_VOLUME or an action this check does not drive. */
    NOT_ASSIGNABLE,
    THERMAL,
    LOG_ONLY,
    LOCKED,
    NOT_READY,
    HIGH_SPEED,
    NO_TORCH,
    NO_TORCH_LEVEL,
    NO_AUTOFOCUS,
    NO_FOCUS_MARK,
    /** Self-recording with minimal controls leaves only capture and the lock. */
    SELF_MINIMAL,
    NO_EXTERIOR,
    NO_PRESET,
}

fun operatorActionAvailable(action: OperatorAction, state: CameraUiState): Boolean =
    operatorActionUnavailableReason(action, state) == null

/** The reason [action] is unavailable in [state], or null when it can act. */
fun operatorActionUnavailableReason(action: OperatorAction, state: CameraUiState): OperatorUnavailableReason? {
    if (action == OperatorAction.NONE || action == OperatorAction.SYSTEM_VOLUME) return OperatorUnavailableReason.NOT_ASSIGNABLE
    if (operatorActionThermallyPaused(action, state)) return OperatorUnavailableReason.THERMAL
    if (action == OperatorAction.CONTROL_LOCK) return null
    if (action == OperatorAction.CAPTURE) return if (state.whiteBalancePreparing ||
        state.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING)) null else OperatorUnavailableReason.NOT_READY
    // Color view assist is applied only by the OCLog2 shader. Every other GPU viewfinder (time-lapse,
    // VIDEO with a LUT or subject preview, PHOTO with an operator LUT) is SDR passthrough and ignores
    // it, so offering it there would be a silent no-op.
    if (action == OperatorAction.VIEW_ASSIST) return if (state.selectedMode == CaptureMode.LOG) null else OperatorUnavailableReason.LOG_ONLY
    if (action in ScopeActions || action == OperatorAction.EXTERIOR) return null
    if (state.captureControlsLocked) return OperatorUnavailableReason.LOCKED
    if (action in setOf(OperatorAction.PRESET_C1, OperatorAction.PRESET_C2)) return if (state.descriptor != null) null else OperatorUnavailableReason.NOT_READY
    if (state.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING) || state.whiteBalancePreparing || state.recordingFinalizing) return OperatorUnavailableReason.NOT_READY
    val highSpeed = state.operatorHighSpeed()
    fun unless(ok: Boolean, reason: OperatorUnavailableReason) = if (ok) null else reason
    return when (action) {
        OperatorAction.TORCH -> if (highSpeed) OperatorUnavailableReason.HIGH_SPEED
            else unless(state.descriptor?.torchCapabilities?.available == true, OperatorUnavailableReason.NO_TORCH)
        OperatorAction.TORCH_LEVEL -> if (highSpeed) OperatorUnavailableReason.HIGH_SPEED
            else unless(state.descriptor?.torchCapabilities?.adjustable == true, OperatorUnavailableReason.NO_TORCH_LEVEL)
        OperatorAction.AUTO_FOCUS -> unless(state.descriptor?.availableAfModes?.any { it != 0 } == true, OperatorUnavailableReason.NO_AUTOFOCUS)
        OperatorAction.FOCUS_A -> if (highSpeed) OperatorUnavailableReason.HIGH_SPEED else unless("A" in state.focusMarks, OperatorUnavailableReason.NO_FOCUS_MARK)
        OperatorAction.FOCUS_B -> if (highSpeed) OperatorUnavailableReason.HIGH_SPEED else unless("B" in state.focusMarks, OperatorUnavailableReason.NO_FOCUS_MARK)
        else -> OperatorUnavailableReason.NOT_ASSIGNABLE
    }
}
