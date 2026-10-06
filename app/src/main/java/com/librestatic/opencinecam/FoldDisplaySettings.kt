/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** What the menu shows when no coordinator is provided: the headless renders set it, the app never does. */
internal val LocalFoldDisplayStateWithoutCoordinator = staticCompositionLocalOf { FoldDisplayState() }

/**
 * The exterior display menu: a status card with the one action that applies (or why none does), the
 * content modes as a grid, the chosen mode's own settings under it, then the general ones.
 * [inCapturePane] is the capture screen's pane: it adds each mode's full settings, which the settings
 * hub keeps in their own cards, and leaves out the inner-screen layout options, which only the hub shows.
 */
@Composable
internal fun FoldDisplaySettings(camera: CameraUiState, settings: CameraSettings, onChange: (CameraSettings) -> Unit,
    inCapturePane: Boolean = false) {
    val coordinator = LocalFoldDisplayCoordinator.current
    val offline = LocalFoldDisplayStateWithoutCoordinator.current
    val fallback = remember(offline) { kotlinx.coroutines.flow.MutableStateFlow(offline) }
    val display by (coordinator?.states ?: fallback).collectAsState()
    val subject = settings.subjectDisplay
    fun update(next: SubjectDisplaySettings) = onChange(settings.copy(subjectDisplay = next))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FoldStatusCard(display, subject.mode, camera.phase == CameraUiPhase.RECORDING,
            onPresent = { coordinator?.start(DisplayOperation.PRESENT) },
            onTransfer = { coordinator?.start(DisplayOperation.TRANSFER) },
            onClose = { coordinator?.closeSession() })

        FoldSectionTitle(stringResource(R.string.fold_subject_mode))
        FoldModeGrid(subject.mode) { update(subject.copy(mode = it)) }

        FoldSectionTitle(stringResource(R.string.fold_mode_settings, stringResource(subject.mode.labelRes())))
        if (subject.mode != SubjectDisplayMode.FILL_LIGHT) {
            OutlinedTextField(subject.operatorCue, { update(subject.copy(operatorCue = it.take(200))) }, label = { Text(stringResource(R.string.fold_cue)) },
                modifier = Modifier.fillMaxWidth().testTag("fold-cue"), colors = readableFieldColors())
        }
        when (subject.mode) {
            SubjectDisplayMode.STATUS -> SettingsHelpText(stringResource(R.string.fold_mode_status_help), null)
            SubjectDisplayMode.TELEPROMPTER -> {
                FoldScriptPreview(subject.prompterText) { update(subject.copy(prompterText = it.take(20_000))) }
                FoldSlider(stringResource(R.string.fold_font, subject.prompterFontSp), subject.prompterFontSp.toFloat(), 16f..72f) { update(subject.copy(prompterFontSp = it.roundToInt())) }
                FoldSlider(stringResource(R.string.fold_speed, subject.prompterSpeedDpPerSecond), subject.prompterSpeedDpPerSecond.toFloat(), 5f..120f) { update(subject.copy(prompterSpeedDpPerSecond = it.roundToInt())) }
                FoldToggle(stringResource(R.string.fold_pause), subject.prompterPaused) { update(subject.copy(prompterPaused = it)) }
            }
            SubjectDisplayMode.PREVIEW -> {
                SettingsHelpText(stringResource(R.string.fold_preview_help), null)
                if (inCapturePane) SubjectSelfMonitorSettings(camera, subject, ::update)
                else FoldToggle(stringResource(R.string.fold_preview_mirror), subject.previewMirror) { update(subject.copy(previewMirror = it)) }
                FoldToggle(stringResource(R.string.fold_preview_assist), subject.previewViewAssist) { update(subject.copy(previewViewAssist = it)) }
            }
            SubjectDisplayMode.REVIEW -> SubjectReviewOperatorBar(help = true)
            SubjectDisplayMode.FILL_LIGHT -> if (inCapturePane) SubjectFillLightSettings(camera, subject, ::update)
                else SettingsHelpText(stringResource(R.string.fold_mode_more_below), null)
            SubjectDisplayMode.INTERVIEW -> if (inCapturePane) SubjectInterviewSettings(camera, subject, ::update)
                else SettingsHelpText(stringResource(R.string.fold_mode_more_below), null)
            SubjectDisplayMode.SLATE -> if (inCapturePane) SubjectSlateSettings(camera, subject, ::update)
                else SettingsHelpText(stringResource(R.string.fold_mode_more_below), null)
        }

        FoldSectionTitle(stringResource(R.string.fold_general))
        FoldSlider(stringResource(R.string.fold_brightness, (subject.brightness * 100).roundToInt()), subject.brightness * 100, 0f..100f) { update(subject.copy(brightness = it / 100)) }
        FoldToggle(stringResource(R.string.fold_lock_touch), subject.touchLocked) { update(subject.copy(touchLocked = it)) }
        if (subject.mode != SubjectDisplayMode.STATUS) {
            FoldToggle(stringResource(R.string.fold_show_status), subject.showStatus) { update(subject.copy(showStatus = it)) }
        }

        SettingsSectionTitle(stringResource(R.string.self_title), help = stringResource(R.string.self_timer_help) + "\n\n" + stringResource(R.string.fold_transfer_help))
        SettingsChips(stringResource(R.string.self_timer_label), listOf(0, 3, 5, 10), subject.selfTimerSeconds,
            label = { stringResource(R.string.self_timer_short, it) },
            onSelect = { update(subject.copy(selfTimerSeconds = it)) }, tag = { "self-timer-$it" })
        FoldToggle(stringResource(R.string.self_minimal), subject.selfMinimalControls) { update(subject.copy(selfMinimalControls = it)) }

        // How the inner screen behaves belongs to the operator's layout, not to what the subject sees.
        if (!inCapturePane) {
            FoldSectionTitle(stringResource(R.string.fold_inner_section))
            FoldToggle(stringResource(R.string.fold_continue), subject.continueRecordingOnFold) { update(subject.copy(continueRecordingOnFold = it)) }
            Text(stringResource(when (camera.foldClosureSensorAvailable) {
                true -> R.string.fold_close_policy_help
                false -> R.string.fold_close_sensor_missing
                null -> R.string.fold_close_sensor_unknown
            }), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            FoldToggle(stringResource(R.string.fold_adapt), subject.adaptToHinge) { update(subject.copy(adaptToHinge = it)) }
            FoldToggle(stringResource(R.string.fold_swap), subject.swapPanes) { update(subject.copy(swapPanes = it)) }
        }
    }
}

private val FoldOk = Color(0xFF4BD28A)
private val FoldPending = Color(0xFFFFB300)
private val FoldIdle = Color(0xFF7D878D)

@Composable
private fun FoldSectionTitle(title: String) {
    Text(title.uppercase(LocalConfiguration.current.locales[0]), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 10.dp).semantics { heading() })
}

internal fun SubjectDisplayMode.labelRes(): Int = when (this) {
    SubjectDisplayMode.STATUS -> R.string.fold_mode_status
    SubjectDisplayMode.TELEPROMPTER -> R.string.fold_mode_prompter
    SubjectDisplayMode.PREVIEW -> R.string.fold_mode_preview
    SubjectDisplayMode.FILL_LIGHT -> R.string.fold_mode_fill_light
    SubjectDisplayMode.REVIEW -> R.string.fold_mode_review
    SubjectDisplayMode.INTERVIEW -> R.string.fold_mode_interview
    SubjectDisplayMode.SLATE -> R.string.fold_mode_slate
}

private fun SubjectDisplayMode.icon(): CineIcon = when (this) {
    SubjectDisplayMode.STATUS -> CineIcon.INFO
    SubjectDisplayMode.TELEPROMPTER -> CineIcon.SCRIPT
    SubjectDisplayMode.PREVIEW -> CineIcon.CAMERA
    SubjectDisplayMode.FILL_LIGHT -> CineIcon.TORCH
    SubjectDisplayMode.REVIEW -> CineIcon.PLAY
    SubjectDisplayMode.INTERVIEW -> CineIcon.SPEECH
    SubjectDisplayMode.SLATE -> CineIcon.SLATE
}

/**
 * The exterior display at a glance: what it is doing, and the action that applies. When the action
 * cannot run, the reason takes its place, and the self-recording route stays offered beside it.
 */
@Composable
private fun FoldStatusCard(display: FoldDisplayState, mode: SubjectDisplayMode, recording: Boolean,
    onPresent: () -> Unit, onTransfer: () -> Unit, onClose: () -> Unit) {
    val active = display.phase != DisplaySessionPhase.IDLE
    val transferring = display.operation == DisplayOperation.TRANSFER
    val (dot, state) = when {
        display.phase == DisplaySessionPhase.STARTING -> FoldPending to stringResource(R.string.fold_state_starting)
        active && transferring -> FoldOk to stringResource(R.string.fold_state_self)
        active && !display.visible -> FoldPending to stringResource(R.string.fold_hidden)
        active -> FoldOk to stringResource(R.string.fold_state_showing, stringResource(mode.labelRes()))
        else -> when (display.presentation) {
            DisplayCapability.AVAILABLE, DisplayCapability.ACTIVE -> FoldOk to stringResource(R.string.fold_state_ready)
            DisplayCapability.UNAVAILABLE -> FoldPending to stringResource(R.string.fold_state_unfold)
            DisplayCapability.UNSUPPORTED -> FoldIdle to stringResource(R.string.fold_unsupported)
            DisplayCapability.UNKNOWN -> FoldIdle to stringResource(R.string.fold_state_checking)
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp).testTag("fold-status-card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center) { CineGlyph(CineIcon.DISPLAYS, MaterialTheme.colorScheme.onSurface, Modifier.size(24.dp)) }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.fold_exterior_display), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Text(state, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.testTag("fold-status"))
                }
            }
        }
        display.failure?.let { Text(stringResource(R.string.fold_failure, it), color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
        when {
            active -> Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fold-close")) {
                Text(stringResource(if (transferring) R.string.fold_return else R.string.fold_close))
            }
            display.presentation == DisplayCapability.AVAILABLE -> Button(onClick = onPresent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fold-present")) { Text(stringResource(R.string.fold_start_subject)) }
            else -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(12.dp).testTag("fold-present-reason"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CineGlyph(CineIcon.INFO, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(20.dp))
                Text(stringResource(when (display.presentation) {
                    DisplayCapability.UNAVAILABLE -> R.string.fold_reason_unfold
                    DisplayCapability.UNSUPPORTED -> R.string.fold_reason_unsupported
                    else -> R.string.fold_reason_checking
                }), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
        }
        if (!active) {
            val transferReady = display.transfer == DisplayCapability.AVAILABLE && !recording
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = transferReady, role = Role.Button, onClick = onTransfer).padding(horizontal = 4.dp)
                    .testTag("fold-transfer"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val tint = if (transferReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                CineGlyph(CineIcon.SWITCH_CAMERA, tint, Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.fold_start_self), color = tint, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (!transferReady) Text(stringResource(when {
                        recording -> R.string.fold_transfer_recording
                        display.transfer == DisplayCapability.UNSUPPORTED -> R.string.fold_unsupported
                        display.transfer == DisplayCapability.UNKNOWN -> R.string.fold_state_checking
                        else -> R.string.fold_unavailable
                    }), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                CineGlyph(CineIcon.CHEVRON_RIGHT, tint, Modifier.size(18.dp))
            }
        }
        Text(stringResource(R.string.fold_posture, postureLabel(display.posture)), color = FoldIdle, fontSize = 12.sp)
    }
}

/** The seven contents as a grid of icon cards; the chosen one is outlined in amber. */
@Composable
private fun FoldModeGrid(selected: SubjectDisplayMode, onSelect: (SubjectDisplayMode) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val columns = (maxWidth / 132.dp).toInt().coerceIn(2, 4)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SubjectDisplayMode.entries.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { mode -> FoldModeCard(mode, mode == selected, Modifier.weight(1f)) { onSelect(mode) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun FoldModeCard(mode: SubjectDisplayMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier.heightIn(min = 84.dp).clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .testTag("fold-mode-${mode.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        CineGlyph(mode.icon(), if (selected) accent else MaterialTheme.colorScheme.onSurface, Modifier.size(26.dp))
        Text(stringResource(mode.labelRes()), color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Two lines of the script with a pencil; editing a long script happens in a full-screen editor. */
@Composable
private fun FoldScriptPreview(text: String, onChange: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = { editing = true }).padding(12.dp).testTag("fold-script-edit"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.fold_script), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(text.ifBlank { stringResource(R.string.fold_script_empty) },
                color = if (text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        CineGlyph(CineIcon.EDIT, MaterialTheme.colorScheme.primary, Modifier.size(22.dp))
    }
    if (editing) Dialog(onDismissRequest = { editing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fold_script), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.fold_script_count, text.length), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
                Button(onClick = { editing = false }, modifier = Modifier.testTag("fold-script-done")) { Text(stringResource(R.string.fold_script_done)) }
            }
            OutlinedTextField(text, { onChange(it.take(20_000)) }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("fold-script-editor"),
                colors = readableFieldColors(), textStyle = LocalTextStyle.current.copy(fontSize = 18.sp, lineHeight = 26.sp))
        }
    }
}

@Composable
private fun readableFieldColors() = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface)

/** A slider that commits once the drag ends, so the exterior screen is not re-laid out per frame. */
@Composable
internal fun FoldSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    SettingsSliderRow(label, draft, { draft = it }, range, onValueChangeFinished = { onChange(draft) })
}

/** A whole-row switch; the gap keeps a long label from running into the switch. */
@Composable
internal fun FoldToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(end = 16.dp))
        Switch(value, onCheckedChange = null)
    }
}

@Composable
private fun postureLabel(posture: FoldPosture): String = stringResource(when (posture) {
    FoldPosture.NONE_REPORTED -> R.string.fold_no_posture
    FoldPosture.FLAT -> R.string.fold_flat
    FoldPosture.TABLETOP -> R.string.fold_tabletop
    FoldPosture.BOOK -> R.string.fold_book
    FoldPosture.SEPARATING -> R.string.fold_separating
})
