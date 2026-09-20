/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.service.CaptureService

internal data class OperatorActions(val capture: () -> Unit, val perform: (OperatorAction) -> Unit, val available: (OperatorAction) -> Boolean, val setEditing: (Boolean) -> Unit = {})
internal val LocalOperatorActions = staticCompositionLocalOf<OperatorActions?> { null }
private tailrec fun Context.operatorActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.operatorActivity() else null
    else -> null
}

@Composable
internal fun rememberOperatorActions(state: CameraUiState, settings: CameraSettings, binder: CaptureService.LocalBinder?,
    coordinator: FoldDisplayCoordinator?, fold: FoldDisplayState, enabled: Boolean): OperatorActions {
    val context = LocalContext.current
    val repository = remember(context) { PresetRepositories.get(context) }
    val library by repository.states.collectAsState()
    var review by remember { mutableStateOf<CameraPreset?>(null) }
    var editing by remember { mutableStateOf(false) }
    val capture = rememberCaptureAction(state, binder, settings, enabled)
    fun preset(action: OperatorAction): CameraPreset? {
        val slot = if (action == OperatorAction.PRESET_C1) "C1" else "C2"
        return library.presets.firstOrNull { it.id == library.slots[slot] }
    }
    val available: (OperatorAction) -> Boolean = { action ->
        enabled && binder != null && operatorActionAvailable(action, state) && when {
            state.selfRecordingActive && settings.subjectDisplay.selfMinimalControls -> action in setOf(OperatorAction.CAPTURE, OperatorAction.CONTROL_LOCK)
            action == OperatorAction.EXTERIOR -> coordinator != null && (fold.phase != DisplaySessionPhase.IDLE || fold.presentation == DisplayCapability.AVAILABLE)
            action in setOf(OperatorAction.PRESET_C1, OperatorAction.PRESET_C2) -> preset(action) != null
            else -> true
        }
    }
    val perform: (OperatorAction) -> Unit = { action ->
        if (!available(action)) Toast.makeText(context, R.string.operator_unavailable, Toast.LENGTH_SHORT).show()
        else when (action) {
            OperatorAction.CAPTURE -> capture()
            OperatorAction.PRESET_C1, OperatorAction.PRESET_C2 -> review = preset(action)
            OperatorAction.EXTERIOR -> if (fold.phase != DisplaySessionPhase.IDLE) coordinator?.closeSession() else coordinator?.start(DisplayOperation.PRESENT)
            else -> binder?.performOperatorAction(action)
        }
    }
    val currentPerform by rememberUpdatedState(perform)
    val currentSettings by rememberUpdatedState(settings)
    val currentEnabled by rememberUpdatedState(enabled && review == null && !editing)
    val activity = context.operatorActivity()
    DisposableEffect(activity) {
        val owner = Any()
        activity?.installOperatorKeys(owner) { key ->
            if (!currentEnabled) null else if (key == KeyEvent.KEYCODE_VOLUME_UP) currentSettings.operation.volumeUp else currentSettings.operation.volumeDown
        }
        activity?.operatorAction = { currentPerform(it) }
        onDispose { activity?.removeOperatorKeys(owner) }
    }
    // Navigating away cancels review; a changed role/camera never applies the old dialog silently.
    LaunchedEffect(enabled, state.selectedCameraId, state.selfRecordingActive) { review = null }
    review?.let { selected -> PresetReviewDialog(selected, state, settings, { review = null }) {
        if (enabled && !state.captureControlsLocked) binder?.applyPreset(selected)
        review = null
    } }
    return OperatorActions(capture, perform, available) { editing = it }
}

@Composable
internal fun OperatorButtonRow(state: CameraUiState, settings: CameraSettings, actions: OperatorActions? = LocalOperatorActions.current) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF101417)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (state.captureControlsLocked) Text(stringResource(R.string.operator_locked), color = Color(0xFFFFCF66), fontSize = 14.sp)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            settings.operation.buttons.forEachIndexed { index, action ->
                OutlinedButton(onClick = { actions?.perform?.invoke(action) }, enabled = actions?.available?.invoke(action) == true,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("operator-button-${index + 1}")) {
                    Text("F${index + 1} · ${stringResource(action.labelResource())}", color = if (actions?.available?.invoke(action) == true) Color.White else Color.Gray, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
internal fun OperatorSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val operation = settings.operation
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.operator_title), color = Color.White, fontSize = 20.sp)
        Text(stringResource(R.string.operator_help), color = Color.LightGray, fontSize = 16.sp)
        operation.buttons.forEachIndexed { index, action ->
            OperatorChoice(stringResource(R.string.operator_button, index + 1), action, OperatorAction.entries.filter { it != OperatorAction.SYSTEM_VOLUME }, "operator-map-${index + 1}") { selected ->
                onChange(settings.copy(operation = when (index) { 0 -> operation.copy(button1 = selected); 1 -> operation.copy(button2 = selected); else -> operation.copy(button3 = selected) }))
            }
        }
        OperatorChoice(stringResource(R.string.operator_volume_up), operation.volumeUp, OperatorAction.entries, "operator-volume-up") { onChange(settings.copy(operation = operation.copy(volumeUp = it))) }
        OperatorChoice(stringResource(R.string.operator_volume_down), operation.volumeDown, OperatorAction.entries, "operator-volume-down") { onChange(settings.copy(operation = operation.copy(volumeDown = it))) }
        Text(stringResource(R.string.operator_startup), color = Color.White, fontSize = 18.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StartupMode.entries.forEach { mode ->
                FilterChip(selected = operation.startupMode == mode, onClick = { onChange(settings.copy(operation = operation.copy(startupMode = mode))) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("operator-startup-$mode"), label = { Text(stringResource(when (mode) { StartupMode.PHOTO -> R.string.operator_startup_photo; StartupMode.VIDEO -> R.string.operator_startup_video; StartupMode.LAST -> R.string.operator_startup_last })) })
            }
        }
        OperatorToggle(R.string.operator_restore_manual, "operator-restore-manual", operation.restoreExposureWhiteBalance) { onChange(settings.copy(operation = operation.copy(restoreExposureWhiteBalance = it))) }
        OperatorToggle(R.string.operator_restore_torch, "operator-restore-torch", operation.restoreTorch) { onChange(settings.copy(operation = operation.copy(restoreTorch = it))) }
        Text(stringResource(R.string.operator_startup_help), color = Color.LightGray, fontSize = 16.sp)
        OperatorToggle(R.string.operator_lock, "operator-lock", operation.lockDuringTake) { onChange(settings.copy(operation = operation.copy(lockDuringTake = it))) }
        Text(stringResource(if (state.captureControlsLocked) R.string.operator_locked else R.string.operator_lock_help), color = Color.LightGray, fontSize = 16.sp)
        OutlinedButton({ onChange(settings.copy(operation = OperatorPreferences())) }, Modifier.heightIn(min = 48.dp).testTag("operator-reset")) { Text(stringResource(R.string.operator_reset)) }
    }
}
@Composable
private fun OperatorChoice(title: String, value: OperatorAction, options: List<OperatorAction>, tag: String, onChange: (OperatorAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton({ open = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        Text("$title: ${stringResource(value.labelResource())}", fontSize = 16.sp)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            options.forEach { action -> TextButton({ onChange(action); open = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("operator-choice-$action")) { Text(stringResource(action.labelResource()), fontSize = 16.sp) } }
        } }, confirmButton = { TextButton({ open = false }) { Text(stringResource(android.R.string.cancel)) } })
}
@Composable
private fun OperatorToggle(label: Int, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), color = Color.White, fontSize = 16.sp)
        Switch(checked, onCheckedChange = null)
    }
}
internal fun OperatorAction.labelResource(): Int = when (this) {
    OperatorAction.NONE -> R.string.operator_action_none
    OperatorAction.SYSTEM_VOLUME -> R.string.operator_action_system_volume
    OperatorAction.CAPTURE -> R.string.operator_action_capture
    OperatorAction.TORCH -> R.string.operator_action_torch
    OperatorAction.TORCH_LEVEL -> R.string.operator_action_torch_level
    OperatorAction.PEAKING -> R.string.operator_action_peaking
    OperatorAction.ZEBRA -> R.string.operator_action_zebra
    OperatorAction.HISTOGRAM -> R.string.operator_action_histogram
    OperatorAction.VIEW_ASSIST -> R.string.operator_action_view_assist
    OperatorAction.AUTO_FOCUS -> R.string.operator_action_auto_focus
    OperatorAction.FOCUS_A -> R.string.operator_action_focus_a
    OperatorAction.FOCUS_B -> R.string.operator_action_focus_b
    OperatorAction.PRESET_C1 -> R.string.operator_action_preset_c1
    OperatorAction.PRESET_C2 -> R.string.operator_action_preset_c2
    OperatorAction.EXTERIOR -> R.string.operator_action_exterior
    OperatorAction.CONTROL_LOCK -> R.string.operator_action_control_lock
}
