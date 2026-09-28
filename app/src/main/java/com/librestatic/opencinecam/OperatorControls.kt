/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.service.CaptureService

internal data class OperatorActions(val capture: () -> Unit, val perform: (OperatorAction) -> Unit, val available: (OperatorAction) -> Boolean,
    /** Latched state of a toggle action, or null when the action is momentary. */
    val latched: (OperatorAction) -> Boolean? = { null }, val setEditing: (Boolean) -> Unit = {})
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
        if (!available(action)) Toast.makeText(context,
            if (operatorActionThermallyPaused(action, state)) R.string.operator_action_thermal_help else R.string.operator_unavailable, Toast.LENGTH_SHORT).show()
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
    // The display session lives in the UI layer, so only this scope can latch EXTERIOR.
    val latched: (OperatorAction) -> Boolean? = { action ->
        if (action == OperatorAction.EXTERIOR) fold.phase != DisplaySessionPhase.IDLE
        else operatorActionToggleState(action, settings, state)
    }
    return OperatorActions(capture, perform, available, latched) { editing = it }
}

@Composable
internal fun OperatorButtonRow(state: CameraUiState, settings: CameraSettings, actions: OperatorActions? = LocalOperatorActions.current) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF101417)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (state.captureControlsLocked) Text(stringResource(R.string.operator_locked), color = Color(0xFFFFCF66), fontSize = 14.sp)
        // Wrapped rows (and the one-per-row column in the side rail) keep a gap between chips.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OperatorButtons(state, settings, actions, compact = false)
        }
    }
}

/**
 * The same F-keys as a column of icon keys laid over the viewfinder edge, for portrait windows
 * where a full-width row would cost a line of viewfinder. Each key still states ON/OFF in text.
 */
@Composable
internal fun OperatorButtonColumn(state: CameraUiState, settings: CameraSettings, modifier: Modifier = Modifier,
    actions: OperatorActions? = LocalOperatorActions.current) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
        OperatorButtons(state, settings, actions, compact = true)
    }
}

@Composable
private fun OperatorButtons(state: CameraUiState, settings: CameraSettings, actions: OperatorActions?, compact: Boolean) {
    settings.operation.buttons.forEachIndexed { index, action ->
        val paused = operatorActionThermallyPaused(action, state)
        OperatorButton(
            index = index,
            action = action,
            available = !paused && actions?.available?.invoke(action) == true,
            // A row rendered without the action bundle still reads the settings it was given.
            latched = actions?.latched?.invoke(action) ?: operatorActionToggleState(action, settings, state),
            thermallyPaused = paused,
            compact = compact,
            onClick = { actions?.perform?.invoke(action) },
        )
    }
}

/**
 * One assignable button. A toggle action carries its latched state in the container colour and in
 * an ON/OFF pill, so the operator never has to press one to find out where it stands; a long press
 * explains what the action does, including whether it only affects monitoring. An unavailable
 * button ignores taps but still answers the long press, because that help is what explains why.
 * A scope action paused by device heat reads PAUSED rather than its latched ON, because the engine
 * is not drawing it; TalkBack and the long press say why.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OperatorButton(index: Int, action: OperatorAction, available: Boolean, latched: Boolean?, onClick: () -> Unit,
    thermallyPaused: Boolean = false, compact: Boolean = false) {
    val context = LocalContext.current
    val on = latched == true
    val content = when {
        !available -> Color.Gray
        on -> OperatorActiveAccent
        else -> Color.White
    }
    val border = when {
        !available -> Color(0xFF2A3034)
        on -> OperatorActiveAccent
        else -> Color(0xFF49535A)
    }
    val actionName = "F${index + 1} · ${stringResource(action.labelResource())}"
    val help = stringResource(action.helpResource()).let { if (thermallyPaused) it + " " + stringResource(R.string.operator_action_thermal_help) else it }
    val stateWord = if (thermallyPaused) stringResource(R.string.operator_state_thermal_description)
        else latched?.let { stringResource(if (it) R.string.operator_state_on_description else R.string.operator_state_off_description) }
    val stateText: (@Composable (androidx.compose.ui.unit.TextUnit) -> Unit)? = latched?.let { isOn -> { size ->
        Text(
            stringResource(when { thermallyPaused -> R.string.operator_state_paused; isOn -> R.string.operator_state_on; else -> R.string.operator_state_off }),
            color = if (available && isOn) Color(0xFF101417) else content,
            fontSize = size,
            lineHeight = size,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (available && isOn) OperatorActiveAccent else Color.Transparent)
                .border(BorderStroke(1.dp, if (available && isOn) OperatorActiveAccent else border), RoundedCornerShape(4.dp))
                .padding(horizontal = if (compact) 3.dp else 5.dp, vertical = 1.dp)
                .testTag("operator-button-${index + 1}-state"),
        )
    } }
    val key = Modifier
        .clip(RoundedCornerShape(if (compact) 14.dp else 10.dp))
        // Over the viewfinder the key needs its own backing to stay readable on bright scenes.
        .background(if (on && available) OperatorActiveContainer else if (compact) Color(0xCC101417) else Color.Transparent)
        .border(BorderStroke(if (on && available) 2.dp else 1.dp, border), RoundedCornerShape(if (compact) 14.dp else 10.dp))
        .combinedClickable(
            role = if (latched != null) Role.Switch else Role.Button,
            onClick = { if (available) onClick() },
            onLongClick = { Toast.makeText(context, help, Toast.LENGTH_LONG).show() },
        )
        .semantics {
            contentDescription = actionName
            if (stateWord != null) stateDescription = stateWord
            if (!available) disabled()
        }
    if (compact) Column(
        key.size(52.dp).testTag("operator-button-${index + 1}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OperatorActionIcon(action, tint = content, modifier = Modifier.size(22.dp))
        stateText?.let { Spacer(Modifier.height(3.dp)); it(9.sp) }
    } else Row(
        key
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("operator-button-${index + 1}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Compact chip: symbol and state only, so the row costs one line of viewfinder. The name
        // stays in the semantics and in the long-press help.
        OperatorActionIcon(action, tint = content, modifier = Modifier.size(22.dp))
        stateText?.let { Spacer(Modifier.width(8.dp)); it(11.sp) }
    }
}

private val OperatorActiveAccent = Color(0xFFFFCF66)
private val OperatorActiveContainer = Color(0xFF3A2E12)

/** Geometric glyphs matching the app's stroke language; letters where a letter is the symbol. */
@Composable
private fun OperatorActionIcon(action: OperatorAction, tint: Color, modifier: Modifier = Modifier) {
    val letter = when (action) {
        OperatorAction.FOCUS_A -> "A"; OperatorAction.FOCUS_B -> "B"
        OperatorAction.PRESET_C1 -> "C1"; OperatorAction.PRESET_C2 -> "C2"
        else -> null
    }
    if (letter != null) {
        androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.Center) {
            Text(letter, color = tint, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        return
    }
    androidx.compose.foundation.Canvas(modifier) { drawOperatorGlyph(action, tint) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOperatorGlyph(action: OperatorAction, color: Color) {
    val w = size.width; val h = size.height; val cx = w / 2f
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.09f)
    fun rect(left: Float, top: Float, width: Float, height: Float, filled: Boolean = false) {
        val style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else stroke
        drawRect(color, Offset(left, top), androidx.compose.ui.geometry.Size(width, height), style = style)
    }
    when (action) {
        OperatorAction.TORCH -> {
            rect(cx - w * .20f, h * .14f, w * .40f, h * .24f)
            rect(cx - w * .13f, h * .38f, w * .26f, h * .48f)
            drawLine(color, Offset(cx - w * .26f, h * .04f), Offset(cx - w * .18f, h * .12f), strokeWidth = w * .07f)
            drawLine(color, Offset(cx, 0f), Offset(cx, h * .10f), strokeWidth = w * .07f)
            drawLine(color, Offset(cx + w * .26f, h * .04f), Offset(cx + w * .18f, h * .12f), strokeWidth = w * .07f)
        }
        OperatorAction.TORCH_LEVEL -> {
            rect(cx - w * .20f, h * .08f, w * .40f, h * .24f)
            rect(cx - w * .13f, h * .32f, w * .26f, h * .60f)
            drawLine(color, Offset(cx, h * .48f), Offset(cx, h * .76f), strokeWidth = w * .08f)
            drawLine(color, Offset(cx - w * .14f, h * .62f), Offset(cx + w * .14f, h * .62f), strokeWidth = w * .08f)
        }
        OperatorAction.PEAKING -> {
            val inset = w * .10f; val arm = w * .30f; val strokeW = w * .09f
            val right = w - inset; val bottom = h - inset
            // Four corner brackets around the in-focus center dot.
            drawLine(color, Offset(inset, inset), Offset(inset + arm, inset), strokeWidth = strokeW)
            drawLine(color, Offset(inset, inset), Offset(inset, inset + arm), strokeWidth = strokeW)
            drawLine(color, Offset(right, inset), Offset(right - arm, inset), strokeWidth = strokeW)
            drawLine(color, Offset(right, inset), Offset(right, inset + arm), strokeWidth = strokeW)
            drawLine(color, Offset(inset, bottom), Offset(inset + arm, bottom), strokeWidth = strokeW)
            drawLine(color, Offset(inset, bottom), Offset(inset, bottom - arm), strokeWidth = strokeW)
            drawLine(color, Offset(right, bottom), Offset(right - arm, bottom), strokeWidth = strokeW)
            drawLine(color, Offset(right, bottom), Offset(right, bottom - arm), strokeWidth = strokeW)
            drawCircle(color, radius = w * .10f)
        }
        OperatorAction.ZEBRA -> {
            drawLine(color, Offset(w * .05f, h * .85f), Offset(w * .45f, h * .05f), strokeWidth = w * .09f)
            drawLine(color, Offset(w * .35f, h * .95f), Offset(w * .75f, h * .15f), strokeWidth = w * .09f)
            drawLine(color, Offset(w * .65f, h * .95f), Offset(w * .95f, h * .45f), strokeWidth = w * .09f)
        }
        OperatorAction.HISTOGRAM -> {
            rect(w * .10f, h * .55f, w * .22f, h * .35f, filled = true)
            rect(w * .40f, h * .20f, w * .22f, h * .70f, filled = true)
            rect(w * .70f, h * .38f, w * .22f, h * .52f, filled = true)
        }
        OperatorAction.VIEW_ASSIST -> {
            drawCircle(color, radius = w * .40f, style = stroke)
            drawArc(color, startAngle = -90f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(cx - w * .40f, h * .10f), size = androidx.compose.ui.geometry.Size(w * .80f, h * .80f))
        }
        OperatorAction.AUTO_FOCUS -> {
            drawCircle(color, radius = w * .40f, style = stroke)
            drawCircle(color, radius = w * .12f)
        }
        OperatorAction.CAPTURE -> {
            drawCircle(color, radius = w * .38f, style = Stroke(width = w * .16f))
        }
        OperatorAction.CONTROL_LOCK -> {
            drawArc(color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(cx - w * .22f, h * .12f), size = androidx.compose.ui.geometry.Size(w * .44f, h * .40f),
                style = stroke)
            rect(cx - w * .30f, h * .42f, w * .60f, h * .44f, filled = true)
        }
        OperatorAction.EXTERIOR -> {
            rect(w * .06f, h * .16f, w * .40f, h * .68f, filled = true)
            rect(w * .54f, h * .16f, w * .40f, h * .68f)
        }
        OperatorAction.NONE -> {
            drawCircle(color, radius = w * .40f, style = stroke)
            drawLine(color, Offset(w * .16f, h * .84f), Offset(w * .84f, h * .16f), strokeWidth = w * .09f)
        }
        OperatorAction.SYSTEM_VOLUME -> {
            drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(w * .10f, h * .38f); lineTo(w * .32f, h * .38f); lineTo(w * .55f, h * .16f)
                lineTo(w * .55f, h * .84f); lineTo(w * .32f, h * .62f); lineTo(w * .10f, h * .62f); close()
            }, color)
            drawArc(color, startAngle = -55f, sweepAngle = 110f, useCenter = false,
                topLeft = Offset(w * .58f, h * .22f), size = androidx.compose.ui.geometry.Size(w * .34f, h * .56f), style = stroke)
        }
        else -> drawCircle(color, radius = w * .30f, style = stroke)
    }
}

@Composable
internal fun OperatorSettings(state: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit) {
    val operation = settings.operation
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.operator_title), color = Color.White, fontSize = 20.sp)
        SettingsHelp(stringResource(R.string.operator_help) + "\n\n" + stringResource(R.string.operator_help_hint))
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
        SettingsHelp(stringResource(R.string.operator_startup_help))
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
            options.forEach { action -> TextButton({ onChange(action); open = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("operator-choice-$action")) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(stringResource(action.labelResource()), fontSize = 16.sp)
                    Text(stringResource(action.helpResource()), color = Color.LightGray, fontSize = 13.sp)
                }
            } }
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
/** What the action actually does, including whether it only affects monitoring. */
internal fun OperatorAction.helpResource(): Int = when (this) {
    OperatorAction.NONE -> R.string.operator_action_none_help
    OperatorAction.SYSTEM_VOLUME -> R.string.operator_action_system_volume_help
    OperatorAction.CAPTURE -> R.string.operator_action_capture_help
    OperatorAction.TORCH -> R.string.operator_action_torch_help
    OperatorAction.TORCH_LEVEL -> R.string.operator_action_torch_level_help
    OperatorAction.PEAKING -> R.string.operator_action_peaking_help
    OperatorAction.ZEBRA -> R.string.operator_action_zebra_help
    OperatorAction.HISTOGRAM -> R.string.operator_action_histogram_help
    OperatorAction.VIEW_ASSIST -> R.string.operator_action_view_assist_help
    OperatorAction.AUTO_FOCUS -> R.string.operator_action_auto_focus_help
    OperatorAction.FOCUS_A -> R.string.operator_action_focus_a_help
    OperatorAction.FOCUS_B -> R.string.operator_action_focus_b_help
    OperatorAction.PRESET_C1 -> R.string.operator_action_preset_c1_help
    OperatorAction.PRESET_C2 -> R.string.operator_action_preset_c2_help
    OperatorAction.EXTERIOR -> R.string.operator_action_exterior_help
    OperatorAction.CONTROL_LOCK -> R.string.operator_action_control_lock_help
}
