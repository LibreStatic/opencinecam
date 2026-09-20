/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.ExposureSelection
import com.librestatic.opencinecam.camera.WhiteBalanceSelection

enum class OperatorAction {
    NONE, SYSTEM_VOLUME, CAPTURE, TORCH, TORCH_LEVEL, PEAKING, ZEBRA, HISTOGRAM,
    VIEW_ASSIST, AUTO_FOCUS, FOCUS_A, FOCUS_B, PRESET_C1, PRESET_C2, EXTERIOR, CONTROL_LOCK,
}
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

fun operatorActionAvailable(action: OperatorAction, state: CameraUiState): Boolean {
    if (action == OperatorAction.NONE || action == OperatorAction.SYSTEM_VOLUME) return false
    if (action == OperatorAction.CONTROL_LOCK) return true
    if (action == OperatorAction.CAPTURE) return state.whiteBalancePreparing || state.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING)
    if (action in setOf(OperatorAction.PEAKING, OperatorAction.ZEBRA, OperatorAction.HISTOGRAM, OperatorAction.VIEW_ASSIST, OperatorAction.EXTERIOR)) return true
    if (state.captureControlsLocked) return false
    if (action in setOf(OperatorAction.PRESET_C1, OperatorAction.PRESET_C2)) return state.descriptor != null
    if (state.phase !in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.SAVED, CameraUiPhase.RECORDING) || state.whiteBalancePreparing || state.recordingFinalizing) return false
    val highSpeed = if (state.selectedMode == CaptureMode.LOG) state.activeLogProfile?.constrainedHighSpeed == true else state.selectedMode in CameraUiState.videoProfileModes && state.activeVideoProfile?.constrainedHighSpeed == true
    return when (action) {
        OperatorAction.TORCH -> !highSpeed && state.descriptor?.torchCapabilities?.available == true
        OperatorAction.TORCH_LEVEL -> !highSpeed && state.descriptor?.torchCapabilities?.adjustable == true
        OperatorAction.AUTO_FOCUS -> state.descriptor?.availableAfModes?.any { it != 0 } == true
        OperatorAction.FOCUS_A -> !highSpeed && "A" in state.focusMarks
        OperatorAction.FOCUS_B -> !highSpeed && "B" in state.focusMarks
        else -> false
    }
}
