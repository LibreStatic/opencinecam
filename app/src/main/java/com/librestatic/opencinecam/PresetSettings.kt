/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun readPresetDocument(input: InputStream): String {
    val bytes = ByteArrayOutputStream()
    val chunk = ByteArray(4096)
    while (true) {
        val count = input.read(chunk)
        if (count < 0) break
        require(bytes.size() + count <= CameraPresetCodec.MAX_BYTES) { "Preset exceeds 64 KiB" }
        bytes.write(chunk, 0, count)
    }
    return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
}

@Composable
internal fun PresetSettings(state: CameraUiState, settings: CameraSettings, onApply: ((CameraPreset) -> Unit)?,
    repository: PresetRepository = PresetRepositories.get(LocalContext.current)) {
    val library by repository.states.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedMessage by rememberUpdatedState(stringResource(R.string.presets_saved))
    val exportedMessage by rememberUpdatedState(stringResource(R.string.presets_exported))
    var name by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<CameraPreset?>(null) }
    var imported by remember { mutableStateOf<CameraPreset?>(null) }
    var delete by remember { mutableStateOf<CameraPreset?>(null) }
    var updating by remember { mutableStateOf<CameraPreset?>(null) }
    var renameOnly by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var exportData by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun attempt(block: () -> Unit) { runCatching(block).onFailure { message = it.message }.onSuccess { message = savedMessage } }
    fun snapshot(label: String, id: String? = null) = CameraPreset(name = label.trim(), settings = settings,
        mode = state.selectedMode, focusDiopters = state.requestedFocusDiopters, zoomRatio = state.zoomRatio).let { if (id == null) it else it.copy(id = id) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = exportData; exportData = null
        if (uri != null && data != null) scope.launch {
            busy = true
            message = runCatching { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(data.toByteArray(Charsets.UTF_8)) }
            }; exportedMessage }.getOrElse { it.message }
            busy = false
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { CameraPresetCodec.decode(readPresetDocument(it)) }
            } }.onSuccess { imported = it; name = it.name }.onFailure { message = it.message }
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth().testTag("preset-settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.presets_title), color = Color.White, fontSize = 20.sp)
        SettingsHelp(stringResource(R.string.presets_help))
        if (state.structuralSettingsFrozen) Text(stringResource(R.string.presets_pending), color = Color(0xFFFFCF66), fontSize = 16.sp)
        state.pendingPresetName?.let { Text(stringResource(R.string.presets_pending_name, it), color = Color(0xFFFFCF66), fontSize = 16.sp) }
        library.error?.let {
            Text(stringResource(R.string.presets_library_error), color = Color(0xFFFFCF66), fontSize = 16.sp)
            OutlinedButton({ reset = true }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.presets_reset)) }
        }
        message?.let { Text(it, color = Color(0xFFFFCF66), fontSize = 16.sp) }
        OutlinedTextField(name, { name = it.take(64) }, label = { Text(stringResource(R.string.presets_name)) },
            modifier = Modifier.fillMaxWidth().testTag("preset-name"), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ attempt { repository.save(snapshot(name)) } }, enabled = !busy && library.error == null && name.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.presets_save_new)) }
            OutlinedButton({ importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy && library.error == null,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.presets_import)) }
        }
        library.presets.forEach { preset ->
            Column(Modifier.fillMaxWidth().background(Color(0xFF1A1F21)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(preset.name, color = Color.White, fontSize = 18.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ review = preset }, Modifier.heightIn(min = 48.dp), enabled = onApply != null && !busy) { Text(stringResource(R.string.presets_review)) }
                    OutlinedButton({ updating = preset; renameOnly = false; name = preset.name }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text(stringResource(R.string.presets_update)) }
                    OutlinedButton({ updating = preset; renameOnly = true; name = preset.name }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text(stringResource(R.string.presets_rename)) }
                    OutlinedButton({ exportData = CameraPresetCodec.encode(preset); export.launch(preset.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_") + ".json") },
                        Modifier.heightIn(min = 48.dp), enabled = !busy) { Text(stringResource(R.string.presets_export)) }
                    OutlinedButton({ delete = preset }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text(stringResource(R.string.presets_delete)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("C1", "C2").forEach { slot ->
                        val assigned = library.slots[slot] == preset.id
                        FilterChip(assigned, { attempt { repository.assign(slot, if (assigned) null else preset.id) } }, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp), label = { Text("$slot · ${preset.name}", fontSize = 16.sp) })
                    }
                }
            }
        }
    }
    review?.let { preset -> PresetReviewDialog(preset, state, settings, { review = null }) { onApply?.invoke(preset); review = null } }
    if (imported != null || updating != null) AlertDialog(onDismissRequest = { imported = null; updating = null },
        title = { Text(stringResource(if (imported != null) R.string.presets_import else R.string.presets_update)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (renameOnly && imported == null) R.string.presets_rename_help else R.string.presets_store_only))
            OutlinedTextField(name, { name = it.take(64) }, label = { Text(stringResource(R.string.presets_name)) }, modifier = Modifier.testTag("preset-dialog-name"))
            message?.let { Text(it) }
        } },
        confirmButton = { TextButton({ runCatching {
            repository.save(imported?.copy(name = name.trim()) ?: if (renameOnly) requireNotNull(updating).copy(name = name.trim()) else snapshot(name, requireNotNull(updating).id))
        }.onSuccess { imported = null; updating = null; message = savedMessage }.onFailure { message = it.message } }) { Text(stringResource(R.string.presets_confirm_save)) } },
        dismissButton = { TextButton({ imported = null; updating = null }) { Text(stringResource(android.R.string.cancel)) } })
    if (delete != null || reset) AlertDialog(onDismissRequest = { delete = null; reset = false },
        title = { Text(stringResource(R.string.presets_delete_confirm)) }, text = { Text(delete?.name ?: stringResource(R.string.presets_reset)) },
        confirmButton = { TextButton({ attempt { if (reset) repository.reset() else repository.delete(requireNotNull(delete).id) }; reset = false; delete = null }) { Text(stringResource(R.string.presets_delete)) } },
        dismissButton = { TextButton({ delete = null; reset = false }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
internal fun PresetReviewDialog(preset: CameraPreset, state: CameraUiState, current: CameraSettings, onDismiss: () -> Unit, onApply: () -> Unit) {
    val issues = preset.compatibilityIssues(state)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.presets_review_name, preset.name)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).testTag("preset-review"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.presets_review_help), fontSize = 16.sp)
            if (state.structuralSettingsFrozen) Text(stringResource(R.string.presets_pending), fontSize = 16.sp)
            if (issues.isNotEmpty()) Text(stringResource(R.string.presets_incompatible, issues.joinToString(", ")), fontSize = 16.sp)
            Text("${state.selectedMode} → ${preset.mode}\nFocus: ${state.requestedFocusDiopters} → ${preset.focusDiopters}\nZoom: ${state.zoomRatio} → ${preset.zoomRatio}", fontSize = 16.sp)
            CameraPresetCodec.differences(current, preset.settings).forEach { Text(it, fontSize = 16.sp) }
        } },
        confirmButton = { TextButton(onApply, enabled = state.descriptor != null && !state.captureControlsLocked) { Text(stringResource(R.string.presets_apply)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
internal fun PresetQuickAccess(state: CameraUiState, settings: CameraSettings, onApply: ((CameraPreset) -> Unit)?,
    repository: PresetRepository = PresetRepositories.get(LocalContext.current)) {
    val library by repository.states.collectAsState()
    var review by remember { mutableStateOf<CameraPreset?>(null) }
    if (library.slots.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.85f)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("C1", "C2").forEach { slot -> library.presets.firstOrNull { it.id == library.slots[slot] }?.let { preset ->
            OutlinedButton({ review = preset }, Modifier.heightIn(min = 48.dp).testTag("preset-$slot"), enabled = onApply != null) {
                Text("$slot · ${preset.name}", color = Color.White, fontSize = 16.sp)
            }
        } }
    }
    review?.let { preset -> PresetReviewDialog(preset, state, settings, { review = null }) { onApply?.invoke(preset); review = null } }
}
