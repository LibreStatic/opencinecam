/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librestatic.opencinecam.camera.AudioSpectrum
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqFilterType
import com.librestatic.opencinecam.camera.EqPoint
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.gallery.MediaDialogFrame
import com.librestatic.opencinecam.liveCameraState
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.camera.coerced
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.service.EqAuditionState
import com.librestatic.opencinecam.ui.CineGlyph
import com.librestatic.opencinecam.ui.CineIcon
import com.librestatic.opencinecam.ui.CineSlider
import com.librestatic.opencinecam.ui.HapticEvent
import com.librestatic.opencinecam.ui.LocalAppHaptics
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the input equaliser page shows; everything else is the page's own (mode, selection, dialogs). */
internal data class InputEqualizerUiState(
    val curve: EqCurve,
    val active: ActiveEqProfile,
    /** The user's profiles, by name. */
    val userProfiles: List<com.librestatic.opencinecam.camera.EqProfile> = emptyList(),
    /** This phone's bundled profile, offered first in the list and by Reset. */
    val reference: com.librestatic.opencinecam.camera.EqProfile? = null,
    /** The reference profile is active but the input is not the low-latency one, so the curve waits. */
    val referenceWaiting: Boolean = false,
    val spectrum: AudioSpectrum? = null,
    /** A take is recording: it keeps the curve it started with. */
    val recording: Boolean = false,
    /** A store operation failed. */
    val profileError: Boolean = false,
    /** The held peaks of the "after" layer, one per band of [spectrum]; null with no spectrum or none held yet. */
    val peakDb: List<Float>? = null,
    /** The microphone reached full scale before the equaliser since the last reset. */
    val inputClipped: Boolean = false,
    /** The recorded signal (after the equaliser, AGC and gain) reached full scale since the last reset. */
    val recordedClipped: Boolean = false,
    val listen: ListenUiState = ListenUiState(),
    val snippet: EqSnippetUiState = EqSnippetUiState(),
)

/** The window width from which the graph and the controls sit side by side. */
private const val TWO_COLUMN_MIN_DP = 840

/**
 * The input equaliser page (PLAN-074 decision 13): the profile row, the switch, the mode, the curve over the live
 * spectrum and the controls of every point. Stateless, so Compose Driver can render it with fake state; edits come
 * back as whole curves through [onCurveChange].
 */
@Composable
internal fun InputEqualizerContent(
    state: InputEqualizerUiState,
    onCurveChange: (EqCurve) -> Unit,
    onPickProfile: (com.librestatic.opencinecam.camera.EqProfile?) -> Unit,
    onSaveAs: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    initialSelected: Int? = null,
    onListenStart: (AudioListeningDevice) -> Unit = {},
    onListenStop: () -> Unit = {},
    onListenVolume: (Int) -> Unit = {},
    onResetPeaks: () -> Unit = {},
    onSnippetRecord: () -> Unit = {},
    onSnippetStopRecording: () -> Unit = {},
    onSnippetPlay: () -> Unit = {},
    onSnippetStopPlayback: () -> Unit = {},
    onSnippetDiscard: () -> Unit = {},
) {
    val curve = state.curve
    var chosenMode by rememberSaveable { mutableStateOf(initialEditorMode(curve)) }
    val simpleOffered = simpleModeOffered(curve)
    // A picked profile that is not simple-shaped moves the page to the full editor.
    LaunchedEffect(simpleOffered) { if (!simpleOffered) chosenMode = EqEditorMode.FULL }
    val mode = if (simpleOffered) chosenMode else EqEditorMode.FULL
    var selectedRaw by rememberSaveable { mutableStateOf(initialSelected) }
    val selected = selectedRaw?.takeIf { it in curve.points.indices && mode == EqEditorMode.FULL }
    val select: (Int) -> Unit = { selectedRaw = it }
    val add: (EqPoint) -> Unit = { point -> curve.withAddedPoint(point)?.let { onCurveChange(it); selectedRaw = curve.points.size } }
    val move: (Int, EqPoint) -> Unit = { index, point -> onCurveChange(curve.withPoint(index, point)) }
    val remove: (Int) -> Unit = { index ->
        onCurveChange(curve.withoutPoint(index))
        selectedRaw = selectedRaw?.let { if (it == index) null else if (it > index) it - 1 else it }
    }
    val description = stringResource(if (curve.enabled) R.string.input_eq_graph_on else R.string.input_eq_graph_bypassed,
        pluralStringResource(R.plurals.input_eq_graph_points, curve.points.size, curve.points.size))

    val header: @Composable () -> Unit = { SettingsTopBar(stringResource(R.string.input_eq_title), onBack, backTag = "input-eq-back") }
    val profile: @Composable () -> Unit = { EqProfileCard(state, onPickProfile, onSaveAs, onRename, onDelete, onReset) }
    val switches: @Composable () -> Unit = {
        SettingsCard {
            SettingsSwitchRow(stringResource(R.string.input_eq_enabled), curve.enabled, { onCurveChange(curve.copy(enabled = it)) }, "input-eq-enabled")
            if (state.referenceWaiting) Text(stringResource(R.string.input_eq_reference_waiting), Modifier.testTag("input-eq-reference-waiting"),
                color = LocalCineColors.current.pending, fontSize = 13.sp)
            if (state.recording) Text(stringResource(R.string.input_eq_pending), Modifier.testTag("input-eq-pending"),
                color = LocalCineColors.current.pending, fontSize = 13.sp)
            SettingsPillRow {
                SettingsPill(stringResource(R.string.input_eq_mode_simple), "input-eq-mode-simple", selected = mode == EqEditorMode.SIMPLE, enabled = simpleOffered) {
                    chosenMode = EqEditorMode.SIMPLE
                }
                SettingsPill(stringResource(R.string.input_eq_mode_full), "input-eq-mode-full", selected = mode == EqEditorMode.FULL) {
                    chosenMode = EqEditorMode.FULL
                }
            }
            if (!simpleOffered) Text(stringResource(R.string.input_eq_simple_unavailable), Modifier.testTag("input-eq-mode-simple-unavailable"),
                color = SettingsMuted, fontSize = 13.sp)
        }
    }
    // Right after the bypass switch, so comparing with and without the equaliser is one reach from the listening.
    val snippetActions = EqSnippetActions(onSnippetRecord, onSnippetStopRecording, onSnippetPlay, onSnippetStopPlayback, onSnippetDiscard)
    val listen: @Composable () -> Unit = {
        EqListenCard(state.listen, state.snippet, state.recording, onListenStart, onListenStop, onListenVolume, snippetActions)
    }
    val graph: @Composable (Boolean) -> Unit = { wide ->
        SettingsCard {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val height = (maxWidth.value * 0.62f).coerceIn(200f, if (wide) 360f else 300f)
                InputEqualizerGraph(curve, selected, state.spectrum, editable = mode == EqEditorMode.FULL, description = description,
                    onSelect = select, onAdd = add, onMove = move, onRemove = remove, modifier = Modifier.fillMaxWidth().height(height.dp),
                    peakDb = state.peakDb, clipped = state.recordedClipped)
            }
            if (state.spectrum != null) {
                SpectrumLegend(state.recordedClipped)
                SettingsPillRow {
                    SettingsPill(stringResource(R.string.input_eq_peaks_reset), "input-eq-peaks-reset", role = Role.Button, onClick = onResetPeaks)
                }
            } else Text(stringResource(R.string.input_eq_spectrum_hint),
                Modifier.testTag("input-eq-spectrum-hint"), color = SettingsMuted, fontSize = 13.sp)
            eqClipNotice(state.inputClipped, state.recordedClipped)?.let { notice ->
                Text(stringResource(when (notice) {
                    EqClipNotice.INPUT -> R.string.input_eq_clip_input
                    EqClipNotice.RECORDED -> R.string.input_eq_clip_recorded
                    EqClipNotice.BOTH -> R.string.input_eq_clip_both
                }), Modifier.testTag("input-eq-clip").semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            if (mode == EqEditorMode.FULL) SettingsHelp(stringResource(R.string.input_eq_gestures_summary), stringResource(R.string.input_eq_gestures_help),
                tag = "input-eq-gestures-help")
        }
    }
    val controls: @Composable () -> Unit = {
        if (mode == EqEditorMode.SIMPLE) {
            SettingsCard(Modifier.testTag("input-eq-simple")) {
                SimpleEqBand.entries.forEach { band ->
                    val label = stringResource(when (band) {
                        SimpleEqBand.LOW -> R.string.input_eq_simple_low
                        SimpleEqBand.MID -> R.string.input_eq_simple_mid
                        SimpleEqBand.HIGH -> R.string.input_eq_simple_high
                    })
                    val gain = curve.simpleGainDb(band)
                    EqSliderRow(label, formatEqGain(gain), gain.toFloat(), EqPoint.MIN_GAIN_DB.toFloat()..EqPoint.MAX_GAIN_DB.toFloat(),
                        { onCurveChange(curve.withSimpleGain(band, roundEqGain(it.toDouble()))) }, "input-eq-simple-${band.name.lowercase()}")
                }
            }
        } else {
            SettingsCard(Modifier.testTag("input-eq-bands")) {
                SettingsSectionTitle(stringResource(R.string.input_eq_bands_title))
                if (curve.points.isEmpty()) Text(stringResource(R.string.input_eq_no_points), Modifier.testTag("input-eq-no-points"), color = SettingsMuted, fontSize = 13.sp)
                curve.points.forEachIndexed { index, point ->
                    key(index) { EqBandItem(index, point, index == selected, onSelect = { select(index) }, onChange = { move(index, it) }, onRemove = { remove(index) }) }
                }
                val full = curve.points.size >= EqCurve.MAX_POINTS
                SettingsPillRow {
                    SettingsPill(stringResource(R.string.input_eq_add), "input-eq-add", enabled = !full, role = Role.Button) {
                        add(EqPoint(EqFilterType.BELL, NEW_POINT_FREQUENCY_HZ, 0.0, NEW_POINT_Q))
                    }
                }
                if (full) Text(stringResource(R.string.input_eq_add_cap), Modifier.testTag("input-eq-add-cap"), color = SettingsMuted, fontSize = 13.sp)
            }
        }
        SettingsCard {
            EqSliderRow(stringResource(R.string.input_eq_trim), formatEqGain(curve.trimDb), curve.trimDb.toFloat(),
                EqCurve.MIN_TRIM_DB.toFloat()..EqCurve.MAX_TRIM_DB.toFloat(),
                { onCurveChange(curve.copy(trimDb = roundEqGain(it.toDouble()).coerceIn(EqCurve.TRIM_RANGE_DB))) }, "input-eq-trim")
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("input-eq-page")) {
        val gap = Arrangement.spacedBy(12.dp)
        if (maxWidth.value >= TWO_COLUMN_MIN_DP) {
            // Wide: the curve and what it is on the left, the controls beside it; each column scrolls alone.
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                Box(Modifier.padding(top = 16.dp)) { header() }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = gap) {
                        profile(); switches(); listen(); graph(true)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = gap) {
                        controls()
                    }
                }
            }
        } else {
            val gutter = settingsSideGutterDp(maxWidth.value)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter.dp, vertical = 16.dp), verticalArrangement = gap) {
                header(); profile(); switches(); listen(); graph(false); controls()
            }
        }
    }
}

/** The profile row: what is active, and pick, save as, rename, delete and reset. */
@Composable
private fun EqProfileCard(
    state: InputEqualizerUiState,
    onPickProfile: (com.librestatic.opencinecam.camera.EqProfile?) -> Unit,
    onSaveAs: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val active = state.active
    val user = (active as? ActiveEqProfile.User)?.profile
    val reference = (active as? ActiveEqProfile.Reference)?.profile
    val atCap = state.userProfiles.size >= EqProfileStore.MAX_PROFILES
    SettingsCard(Modifier.testTag("input-eq-profile")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(stringResource(R.string.input_eq_profile_label), color = SettingsMuted, fontSize = 12.sp)
                Text(activeEqProfileLabel(active), Modifier.testTag("input-eq-profile-name"),
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                reference?.let { Text(it.name, color = SettingsMuted, fontSize = 13.sp) }
            }
            if (reference != null) Row(
                Modifier.border(1.dp, SettingsBorder, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp).testTag("input-eq-read-only"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CineGlyph(CineIcon.LOCK, SettingsMuted, Modifier.size(14.dp))
                Text(stringResource(R.string.input_eq_read_only), color = SettingsMuted, fontSize = 12.sp)
            }
        }
        if (reference != null) Text(stringResource(R.string.input_eq_reference_detail), color = SettingsMuted, fontSize = 13.sp)
        if (state.profileError) Text(stringResource(R.string.input_eq_save_failed), Modifier.testTag("input-eq-profile-error"),
            color = LocalCineColors.current.pending, fontSize = 13.sp)
        SettingsPillRow {
            SettingsPill(stringResource(R.string.input_eq_profile_pick), "input-eq-profile-pick", role = Role.Button) { picking = true }
            SettingsPill(stringResource(R.string.input_eq_save_as), "input-eq-profile-save-as", role = Role.Button) { naming = "" }
            if (user != null) {
                SettingsPill(stringResource(R.string.input_eq_rename), "input-eq-profile-rename", role = Role.Button) { naming = user.name }
                SettingsPill(stringResource(R.string.input_eq_delete), "input-eq-profile-delete", role = Role.Button) { confirmDelete = true }
            }
            SettingsPill(stringResource(R.string.input_eq_reset), "input-eq-profile-reset", role = Role.Button, onClick = onReset)
        }
    }
    if (picking) {
        val choices = listOfNotNull(state.reference) + state.userProfiles
        val labels = choices.map { if (it.id == state.reference?.id) stringResource(R.string.input_eq_profile_reference) else it.name } +
            stringResource(R.string.input_eq_profile_flat)
        val current = when (active) {
            is ActiveEqProfile.Reference -> 0
            is ActiveEqProfile.User -> choices.indexOfFirst { it.id == active.profile.id }
            ActiveEqProfile.Flat -> choices.size
            ActiveEqProfile.Custom -> -1
        }
        SettingsChoiceDialog(stringResource(R.string.input_eq_profile_pick_title), labels, current,
            tags = choices.map { "input-eq-profile-item-${it.id}" } + "input-eq-profile-item-flat",
            onDismiss = { picking = false }) { index -> picking = false; onPickProfile(choices.getOrNull(index)) }
    }
    naming?.let { initial ->
        val renaming = user != null && initial.isNotEmpty()
        EqNameDialog(
            title = stringResource(if (renaming) R.string.input_eq_rename_title else R.string.input_eq_save_as_title),
            initial = initial,
            atCap = !renaming && atCap,
            onDismiss = { naming = null },
        ) { name -> naming = null; if (renaming) onRename(name) else onSaveAs(name) }
    }
    if (confirmDelete && user != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.input_eq_delete_title)) },
        text = { Text(stringResource(R.string.input_eq_delete_body, user.name)) },
        confirmButton = { TextButton({ confirmDelete = false; onDelete() }, Modifier.testTag("input-eq-delete-confirm")) { Text(stringResource(R.string.input_eq_delete)) } },
        dismissButton = { TextButton({ confirmDelete = false }, Modifier.testTag("input-eq-delete-cancel")) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun activeEqProfileLabel(active: ActiveEqProfile): String = when (active) {
    is ActiveEqProfile.Reference -> stringResource(R.string.input_eq_profile_reference)
    is ActiveEqProfile.User -> active.profile.name
    ActiveEqProfile.Custom -> stringResource(R.string.input_eq_profile_custom)
    ActiveEqProfile.Flat -> stringResource(R.string.input_eq_profile_flat)
}

/**
 * A name for Save as or Rename, checked as it is typed; at the profile cap Save as only says so. It uses the app's
 * own dialog frame, as the take rename does, rather than an AlertDialog around a text field.
 */
@Composable
private fun EqNameDialog(title: String, initial: String, atCap: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val problem = eqProfileNameProblem(name)
    // An empty field is not an error until something was typed.
    val shown = problem.takeUnless { it == EqNameProblem.EMPTY && name.isEmpty() }
    MediaDialogFrame("input-eq-name-dialog", onDismiss) {
        Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
        if (atCap) Text(stringResource(R.string.input_eq_profile_cap), Modifier.testTag("input-eq-profile-cap"), color = LocalCineColors.current.pending)
        else {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.input_eq_name_label)) }, singleLine = true,
                isError = shown != null, modifier = Modifier.fillMaxWidth().testTag("input-eq-name-field"))
            shown?.let {
                Text(stringResource(when (it) {
                    EqNameProblem.EMPTY -> R.string.input_eq_name_empty
                    EqNameProblem.TOO_LONG -> R.string.input_eq_name_too_long
                    EqNameProblem.UNREADABLE -> R.string.input_eq_name_unreadable
                }), Modifier.testTag("input-eq-name-error"), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onDismiss, Modifier.testTag("input-eq-name-cancel")) { Text(stringResource(android.R.string.cancel)) }
            TextButton({ onConfirm(name.trim()) }, Modifier.testTag("input-eq-name-confirm"), enabled = !atCap && problem == null) {
                Text(stringResource(R.string.input_eq_save))
            }
        }
    }
}

/** One point's controls: the accessible equivalent of every gesture on its node. */
@Composable
private fun EqBandItem(index: Int, point: EqPoint, selected: Boolean, onSelect: () -> Unit, onChange: (EqPoint) -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val number = index + 1
    val tag = "input-eq-band-$index"
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) colors.primary.copy(alpha = 0.08f) else colors.surfaceContainer)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else SettingsBorder, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val selectLabel = stringResource(R.string.input_eq_point_select, number)
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = selectLabel, role = Role.Button, onClick = onSelect).testTag("$tag-select"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(if (selected) colors.primary else colors.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Text(number.toString(), color = if (selected) colors.onPrimary else colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(stringResource(R.string.input_eq_point, number), color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            val onLabel = stringResource(R.string.input_eq_point_on, number)
            val haptics = LocalAppHaptics.current
            Switch(point.enabled, { haptics.perform(HapticEvent.Toggle); onChange(point.copy(enabled = it)) },
                Modifier.heightIn(min = 48.dp).testTag("$tag-enabled").semantics { contentDescription = onLabel })
            val removeLabel = stringResource(R.string.input_eq_point_remove, number)
            Box(
                Modifier.padding(start = 4.dp).size(48.dp).clip(CircleShape)
                    .clickable(onClickLabel = removeLabel, role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = removeLabel }.testTag("$tag-remove"),
                contentAlignment = Alignment.Center,
            ) { CineGlyph(CineIcon.DELETE, colors.onSurfaceVariant, Modifier.size(20.dp)) }
        }
        SettingsChips(stringResource(R.string.input_eq_type), EqFilterType.entries, point.type, label = { eqTypeLabel(it) },
            onSelect = { onChange(point.copy(type = it)) }, tag = { "$tag-type-${it.name.lowercase()}" }, rowTag = "$tag-type")
        EqSliderRow(stringResource(R.string.input_eq_frequency), formatEqFrequency(point.frequencyHz), frequencyFraction(point.frequencyHz), 0f..1f,
            { onChange(point.coerced(frequencyHz = roundEqFrequency(fractionFrequency(it)))) }, "$tag-frequency")
        // High-pass, low-pass and notch have no gain to set.
        if (point.type.hasGain) EqSliderRow(stringResource(R.string.input_eq_gain), formatEqGain(point.gainDb), point.gainDb.toFloat(),
            EqPoint.MIN_GAIN_DB.toFloat()..EqPoint.MAX_GAIN_DB.toFloat(), { onChange(point.coerced(gainDb = roundEqGain(it.toDouble()))) }, "$tag-gain")
        EqSliderRow(stringResource(R.string.input_eq_width), formatEqQ(point.q), qFraction(point.q), 0f..1f,
            { onChange(point.coerced(q = roundEqQ(fractionQ(it)))) }, "$tag-q")
    }
}

@Composable
internal fun eqTypeLabel(type: EqFilterType): String = stringResource(when (type) {
    EqFilterType.BELL -> R.string.input_eq_type_bell
    EqFilterType.LOW_SHELF -> R.string.input_eq_type_low_shelf
    EqFilterType.HIGH_SHELF -> R.string.input_eq_type_high_shelf
    EqFilterType.HIGH_PASS -> R.string.input_eq_type_high_pass
    EqFilterType.LOW_PASS -> R.string.input_eq_type_low_pass
    EqFilterType.NOTCH -> R.string.input_eq_type_notch
})

/** A label with its readout on one line and the slider under both; TalkBack reads the label and the readout. */
@Composable
internal fun EqSliderRow(label: String, value: String, position: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, tag: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f).padding(end = 12.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Text(value, Modifier.testTag("$tag-value"), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        CineSlider(position, onChange, valueRange = range,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(tag).semantics { contentDescription = label; stateDescription = value })
    }
}

/** What the two spectrum layers and the peak trace are; the trace's swatch turns red with it when the recording clipped. */
@Composable
private fun SpectrumLegend(clipped: Boolean) {
    val colors = MaterialTheme.colorScheme
    FlowRow(Modifier.fillMaxWidth().testTag("input-eq-spectrum-legend"), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LegendItem(colors.onSurfaceVariant.copy(alpha = 0.45f), stringResource(R.string.input_eq_spectrum_input))
        LegendItem(colors.secondary, stringResource(R.string.input_eq_spectrum_output))
        LegendItem(if (clipped) colors.error else colors.secondary, stringResource(R.string.input_eq_spectrum_peak), line = true)
    }
}

/** A square swatch for a filled layer, two dashes for a dashed line. */
@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String, line: Boolean = false) {
    Row(Modifier.heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (line) Row(Modifier.size(width = 12.dp, height = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(2) { Box(Modifier.weight(1f).fillMaxHeight().background(color)) }
        } else Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, color = SettingsMuted, fontSize = 12.sp)
    }
}

/** The snippet section's actions, as one value so the card's signature stays readable. */
internal class EqSnippetActions(
    val record: () -> Unit = {},
    val stopRecording: () -> Unit = {},
    val play: () -> Unit = {},
    val stopPlayback: () -> Unit = {},
    val discard: () -> Unit = {},
)

/**
 * Hearing the curve from the editor. Live listening plays the microphone after the equaliser on headphones, so
 * flipping "Equaliser on" can be heard; only headphone outputs are offered and the service does the playing
 * (AudioListeningController). The snippet below it works with the phone's speaker too: a few seconds recorded before
 * the equaliser and looped through it.
 */
@Composable
private fun EqListenCard(
    listen: ListenUiState,
    snippet: EqSnippetUiState,
    recording: Boolean,
    onStart: (AudioListeningDevice) -> Unit,
    onStop: () -> Unit,
    onVolume: (Int) -> Unit,
    snippetActions: EqSnippetActions,
) {
    SettingsCard(Modifier.testTag("input-eq-listen")) {
        Column {
            Text(stringResource(R.string.input_eq_listen_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.input_eq_listen_help), color = SettingsMuted, fontSize = 13.sp)
        }
        ListenControls(listen, "input-eq-listen", onStart, onStop, onVolume)
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(SettingsBorder))
        EqSnippetSection(snippet, recording, snippetActions)
    }
}

/** Record a few seconds, then loop them through the curve: the way to compare by ear with only the phone's speaker. */
@Composable
private fun EqSnippetSection(snippet: EqSnippetUiState, recording: Boolean, actions: EqSnippetActions) {
    val stop = stringResource(R.string.input_eq_listen_stop)
    Column(Modifier.fillMaxWidth().testTag("input-eq-snippet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(stringResource(R.string.input_eq_snippet_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.input_eq_snippet_help, eqSnippetMaxSeconds(snippet)), Modifier.testTag("input-eq-snippet-help"),
                color = SettingsMuted, fontSize = 13.sp)
        }
        val status = when (eqSnippetView(snippet)) {
            EqSnippetView.RECORDING -> stringResource(R.string.input_eq_snippet_recording, eqSnippetRecordedSeconds(snippet), eqSnippetMaxSeconds(snippet))
            EqSnippetView.PLAYING -> stringResource(R.string.input_eq_snippet_playing)
            EqSnippetView.EMPTY, EqSnippetView.READY -> null
        }
        status?.let {
            Text(it, Modifier.testTag("input-eq-snippet-status").semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        // A take holds the microphone, so nothing can be recorded or looped until it ends; stopping stays possible.
        SettingsPillRow {
            when (eqSnippetView(snippet)) {
                EqSnippetView.EMPTY -> SettingsPill(stringResource(R.string.input_eq_snippet_record), "input-eq-snippet-record",
                    enabled = !recording, role = Role.Button, onClick = actions.record)
                EqSnippetView.RECORDING -> SettingsPill(stop, "input-eq-snippet-stop", role = Role.Button, onClick = actions.stopRecording)
                EqSnippetView.READY -> {
                    SettingsPill(stringResource(R.string.input_eq_snippet_play, eqSnippetSeconds(snippet)), "input-eq-snippet-play",
                        enabled = !recording, role = Role.Button, onClick = actions.play)
                    SettingsPill(stringResource(R.string.input_eq_snippet_record_again), "input-eq-snippet-record",
                        enabled = !recording, role = Role.Button, onClick = actions.record)
                    SettingsPill(stringResource(R.string.input_eq_snippet_discard), "input-eq-snippet-discard", role = Role.Button, onClick = actions.discard)
                }
                EqSnippetView.PLAYING -> SettingsPill(stop, "input-eq-snippet-stop-playback", role = Role.Button, onClick = actions.stopPlayback)
            }
        }
        if (snippet.failed) Text(stringResource(R.string.input_eq_snippet_failed), Modifier.testTag("input-eq-snippet-failed"),
            color = LocalCineColors.current.pending, fontSize = 13.sp)
    }
}

private const val TAG = "InputEqualizer"

/** How long a user profile's edits gather before one store write. */
private const val PROFILE_SAVE_DELAY_MS = 500L

/** Every store call of the page, in order on one background thread, so a save, rename or delete never overtake each other. */
private val eqStoreDispatcher by lazy {
    Executors.newSingleThreadExecutor { Thread(it, "eq-profile-store").apply { isDaemon = true } }.asCoroutineDispatcher()
}

/** The store as the page uses it: reads and actions in order, and a user profile's edits coalesced into one write. */
private class EqProfileWrites(
    private val store: EqProfileStore,
    private val scope: CoroutineScope,
    /** On the main thread after each edit's write: whether it failed. */
    private val onSaved: (failed: Boolean) -> Unit,
) {
    private var pending: com.librestatic.opencinecam.camera.EqProfile? = null
    private var job: Job? = null
    /** Counts the edits queued, so a failed write knows whether a newer one was queued or written meanwhile. */
    private var revision = 0

    /** Called on the main thread for each edit; the write lands [PROFILE_SAVE_DELAY_MS] after the last one. */
    fun queue(profile: com.librestatic.opencinecam.camera.EqProfile) {
        pending = profile
        revision++
        job?.cancel()
        job = scope.launch { delay(PROFILE_SAVE_DELAY_MS); flush() }
    }

    /**
     * Writes the pending profile's curve now; on leaving the page and before any other store call. Only the curve: a
     * rename or delete running meanwhile wins. A failed write is kept for the next flush unless a newer edit came.
     */
    fun flush() {
        job?.cancel()
        job = null
        val profile = pending ?: return
        pending = null
        val written = revision
        eqStoreDispatcher.executor.execute {
            val failure = runCatching { store.saveCurve(profile.id, profile.curve) }.exceptionOrNull()
            failure?.let { Log.w(TAG, "equaliser profile ${profile.id} could not be saved", it) }
            scope.launch {
                if (failure != null && revision == written) pending = profile
                onSaved(failure != null)
            }
        }
    }

    suspend fun <T> run(block: (EqProfileStore) -> T): T {
        flush()
        return withContext(eqStoreDispatcher) { block(store) }
    }
}

/**
 * The page wired to the settings, the profile store (off the main thread), this phone's reference profile and the
 * service's live spectrum: while this page is shown the service monitors the microphone for it, viewfinder or not.
 */
@Composable
internal fun InputEqualizerScreen(
    state: CameraUiState,
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    binder: CaptureService.LocalBinder?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profileError by remember { mutableStateOf(false) }
    val writes = remember(context) {
        EqProfileWrites(EqProfileStore.forContext(context.applicationContext), scope) { failed -> profileError = failed }
    }
    val latest by rememberUpdatedState(settings)
    var profiles by remember { mutableStateOf<List<com.librestatic.opencinecam.camera.EqProfile>?>(null) }
    val reference by produceState<com.librestatic.opencinecam.camera.EqProfile?>(null, context) {
        value = withContext(Dispatchers.IO) { AudioInputPolicies.get(context).bundledProfile }
    }
    suspend fun reload() { profiles = runCatching { writes.run { it.all() } }.getOrDefault(profiles ?: emptyList()) }
    LaunchedEffect(writes) { reload() }
    DisposableEffect(writes) { onDispose { writes.flush() } }
    val spectrumFallback = remember { MutableStateFlow<AudioSpectrum?>(null) }
    val spectrum by (binder?.audioSpectra ?: spectrumFallback).collectAsStateWithLifecycle()
    // Only while the page is on screen: the service opens the microphone for it, and must not keep it in the background.
    LifecycleStartEffect(binder) {
        binder?.setAudioSpectrumWanted(true)
        onStopOrDispose { binder?.setAudioSpectrumWanted(false) }
    }
    val live = liveCameraState(state)
    val recording by rememberUpdatedState(live.structuralSettingsFrozen)
    // The held peaks and the clip latches belong to the curve on screen: they start over when it changes, when the
    // spectrum stops, or on Reset peaks; merely opening the page keeps a latch that was already set.
    var peaks by remember { mutableStateOf<EqPeakHold?>(null) }
    var peaksCurve by remember { mutableStateOf(settings.audioEqCurve) }
    var hadSpectrum by remember { mutableStateOf(false) }
    fun resetPeaks() {
        peaks = null
        // A take's clip warning is about the take, not this curve, so it stays until the take ends.
        if (!recording) binder?.resetAudioClip()
    }
    LaunchedEffect(spectrum) {
        val current = spectrum
        if (current == null) {
            if (hadSpectrum) resetPeaks()
            hadSpectrum = false
        } else {
            hadSpectrum = true
            peaks = peaks.including(current)
        }
    }
    LaunchedEffect(settings.audioEqCurve) {
        if (settings.audioEqCurve != peaksCurve) {
            peaksCurve = settings.audioEqCurve
            resetPeaks()
        }
    }
    // The snippet audition; the service drops the snippet itself when the page stops wanting the spectrum.
    val auditionFallback = remember { MutableStateFlow(EqAuditionState()) }
    val audition by (binder?.eqAudition ?: auditionFallback).collectAsStateWithLifecycle()
    val snippet = eqSnippetUiState(audition)
    // Looping the snippet replaces the microphone as the spectrum's source, so peaks of one are not kept for the other.
    var peaksSource by remember { mutableStateOf(snippet.phase) }
    LaunchedEffect(snippet.phase) {
        if (eqSnippetSourceChanged(peaksSource, snippet.phase)) resetPeaks()
        peaksSource = snippet.phase
    }
    val listenStatus = live.audioListeningStatus
    val listener = rememberListenStarter(settings, listenStatus, onSettingsChange) { binder?.reconnectAudioListening() }
    val active = activeEqProfile(settings.audioEqProfileId, settings.audioEqCurve, reference, profiles.orEmpty())
    val uiState = InputEqualizerUiState(
        curve = settings.audioEqCurve,
        active = active,
        userProfiles = profiles.orEmpty(),
        reference = reference,
        referenceWaiting = active is ActiveEqProfile.Reference && !settings.audioSource.lowLatency,
        spectrum = spectrum,
        recording = live.structuralSettingsFrozen,
        profileError = profileError,
        peakDb = peaks?.takeIf { held -> spectrum?.bandCentresHz == held.bandCentresHz }?.peakDb,
        inputClipped = live.audioInputClipLatched,
        recordedClipped = live.audioRecordedClipLatched,
        listen = eqListenUiState(settings, listenStatus, live.audioListeningOutputs, snippetPlaying = snippet.phase == EqSnippetPhase.PLAYING),
        snippet = snippet,
    )
    fun storeAction(block: suspend () -> Unit) {
        scope.launch {
            profileError = runCatching { block() }.onFailure { Log.w(TAG, "equaliser profile store failed", it) }.isFailure
            reload()
        }
    }
    InputEqualizerContent(
        state = uiState,
        onCurveChange = { curve ->
            val edit = latest.withEditedEqCurve(curve, profiles)
            onSettingsChange(edit.settings)
            edit.save?.let { saved ->
                writes.queue(saved)
                profiles = profiles?.map { if (it.id == saved.id) saved else it }
            }
        },
        onPickProfile = { picked -> writes.flush(); onSettingsChange(latest.withEqProfile(picked)) },
        onSaveAs = { name ->
            storeAction {
                val from = latest.audioEqProfileId
                val curve = latest.audioEqCurve.forProfile()
                val created = writes.run { it.create(name, curve) }
                onSettingsChange(latest.withSavedEqProfile(created, from))
            }
        },
        onRename = { name -> latest.audioEqProfileId?.let { id -> storeAction { writes.run { it.rename(id, name) } } } },
        onDelete = {
            latest.audioEqProfileId?.let { id ->
                storeAction {
                    writes.run { it.delete(id) }
                    onSettingsChange(latest.withDeletedEqProfile(id))
                }
            }
        },
        onReset = { writes.flush(); onSettingsChange(latest.withResetEq(reference)) },
        onBack = onBack,
        onListenStart = listener.start,
        onListenStop = listener.stop,
        onListenVolume = listener.volume,
        onResetPeaks = { resetPeaks() },
        onSnippetRecord = { binder?.startEqSnippetRecording() },
        onSnippetStopRecording = { binder?.stopEqSnippetRecording() },
        onSnippetPlay = { binder?.playEqSnippet() },
        onSnippetStopPlayback = { binder?.stopEqSnippetPlayback() },
        onSnippetDiscard = { binder?.discardEqSnippet() },
    )
}

/** The Settings row's summary: the active profile's name, Custom, Flat, or Off when bypassed. */
@Composable
internal fun rememberInputEqSummary(settings: CameraSettings): String {
    val context = LocalContext.current
    val id = settings.audioEqProfileId
    val name by produceState<String?>(null, id) {
        value = if (id == null || com.librestatic.opencinecam.camera.isBundledEqProfileId(id)) null
        else withContext(Dispatchers.IO) { runCatching { EqProfileStore.forContext(context.applicationContext).load(id)?.name }.getOrNull() }
    }
    return when (val summary = inputEqSummary(id, settings.audioEqCurve, name)) {
        InputEqSummary.Off -> stringResource(R.string.input_eq_off)
        InputEqSummary.Reference -> stringResource(R.string.input_eq_profile_reference)
        is InputEqSummary.User -> summary.name
        InputEqSummary.Custom -> stringResource(R.string.input_eq_profile_custom)
        InputEqSummary.Flat -> stringResource(R.string.input_eq_profile_flat)
    }
}
