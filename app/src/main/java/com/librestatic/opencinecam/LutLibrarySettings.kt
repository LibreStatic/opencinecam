/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.camera.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LutImportDraft(val name: String, val kind: LutTransformKind, val input: LutSignalDomain)

/** Only tiny picker correlation data enters saved state: never LUT originals or provider streams. */
internal class LutPickerPending(importDraft: LutImportDraft? = null, exportHash: String? = null) {
    var importDraft by mutableStateOf(importDraft)
    var exportHash by mutableStateOf(exportHash)
    val awaitingResult: Boolean get() = importDraft != null || exportHash != null
    fun takeImport(): LutImportDraft? = importDraft.also { importDraft = null }
    fun takeExport(): String? = exportHash.also { exportHash = null }
    companion object {
        val saver = listSaver<LutPickerPending, String>(
            save = { listOf(it.importDraft?.name.orEmpty(), it.importDraft?.kind?.name.orEmpty(),
                it.importDraft?.input?.name.orEmpty(), it.exportHash.orEmpty()) },
            restore = { values ->
                runCatching {
                    require(values.size == 4)
                    val draft = if (values[0].isEmpty()) {
                        require(values[1].isEmpty() && values[2].isEmpty()); null
                    } else {
                        LutLibrary.requireName(values[0])
                        LutImportDraft(values[0], LutTransformKind.valueOf(values[1]), LutSignalDomain.valueOf(values[2]))
                    }
                    val hash = values[3].takeIf { it.isNotEmpty() }?.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                    require(draft == null || hash == null)
                    LutPickerPending(draft, hash)
                }.getOrNull()
            })
    }
}

@Composable
internal fun rememberLutPickerPending(): LutPickerPending = rememberSaveable(saver = LutPickerPending.saver) { LutPickerPending() }

@Composable
internal fun LutLibrarySettings(state: CameraUiState = CameraUiState()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var library by remember { mutableStateOf<LutLibrary?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    val pending = rememberLutPickerPending()
    LaunchedEffect(context) { library = withContext(Dispatchers.IO) { LutLibraries.get(context) } }
    fun operate(action: () -> Unit) {
        if (busy) return
        busy = true; failed = false; completed = false
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; completed = true }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val draft = pending.takeImport()
        busy = false
        if (uri != null && draft != null) operate {
            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                val buffer = ByteArray(CubeLut.MAX_BYTES + 1)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    if (count == 0) throw IOException("Input made no progress")
                    size += count
                }
                require(size <= CubeLut.MAX_BYTES)
                buffer.copyOf(size)
            }
            // A recreated Activity may receive the picker result before its library-load effect.
            LutLibraries.get(context).importLut(bytes, draft.name, draft.kind, draft.input)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val hash = pending.takeExport()
        busy = false
        if (uri != null && hash != null) operate {
            // Resolve the exact selected original off-main, even after Activity recreation.
            val bytes = LutLibraries.get(context).export(hash)
            requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { it.write(bytes); it.flush() }
        }
    }
    val current = library
    val value = if (current == null) LutLibraryState() else current.states.collectAsState().value
    LutLibraryContent(value, state, busy || pending.awaitingResult || current == null, failed, completed,
        onImport = { name, kind, input ->
            pending.importDraft = LutImportDraft(name, kind, input); busy = true; failed = false; completed = false
            try { importer.launch(arrayOf("*/*")) }
            catch (_: Exception) { pending.importDraft = null; busy = false; failed = true }
        },
        onExport = { hash ->
            if (!busy && !pending.awaitingResult) {
                pending.exportHash = hash; busy = true; failed = false; completed = false
                try { exporter.launch("LUT_${hash.take(16)}.cube") }
                catch (_: Exception) { pending.exportHash = null; busy = false; failed = true }
            }
        },
        onSelect = { hash -> operate { requireNotNull(current).select(hash) } },
        onDelete = { hash -> operate { requireNotNull(current).delete(hash) } },
        onReset = { operate { requireNotNull(current).reset() } },
        onSelectSubject = { hash -> operate { requireNotNull(current).selectSubject(hash) } },
        onSelectRecording = { hash -> operate { requireNotNull(current).selectRecording(hash) } })
}

/** Picker-free presentation seam: callbacks carry the explicit declaration, never inferred titles. */
@Composable
internal fun LutLibraryContent(library: LutLibraryState, state: CameraUiState = CameraUiState(),
    busy: Boolean = false, failed: Boolean = false, completed: Boolean = false,
    onImport: (String, LutTransformKind, LutSignalDomain) -> Unit,
    onExport: (String) -> Unit, onSelect: (String?) -> Unit, onDelete: (String) -> Unit, onReset: () -> Unit,
    onSelectSubject: (String?) -> Unit, onSelectRecording: (String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<LutTransformKind?>(null) }
    var input by remember { mutableStateOf<LutSignalDomain?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var entryActions by remember { mutableStateOf<String?>(null) }
    var resetting by remember { mutableStateOf(false) }
    var recordingChoice by remember { mutableStateOf<String?>(null) }
    val recordingFrozen = state.structuralSettingsFrozen || state.phase == CameraUiPhase.CAPTURING || state.recordingFinalizing
    val recordingEditable = !busy && library.error == null && !recordingFrozen
    fun canDelete(hash: String) = !busy && library.error == null &&
        (!recordingFrozen || hash != library.recordingHash && hash != state.recordingLutStatus.hash)
    val available = !busy && library.error == null
    val validName = runCatching { LutLibrary.requireName(name) }.isSuccess
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionTitle(stringResource(R.string.lut_library_title), help = stringResource(R.string.lut_library_help) + "\n\n" + stringResource(R.string.lut_preview_help), helpTag = "lut-preview-help")
        val statusLabel = when (state.operatorLutStatus.state) {
            OperatorLutState.DISABLED -> R.string.lut_status_disabled
            OperatorLutState.WAITING_FOR_GPU -> R.string.lut_status_waiting
            OperatorLutState.INCOMPATIBLE_DOMAIN -> R.string.lut_status_incompatible
            OperatorLutState.ACTIVE -> R.string.lut_status_active
            OperatorLutState.FAILED -> R.string.lut_status_failed
        }
        Text(stringResource(statusLabel), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("lut-operator-status"))
        SettingsHelp(stringResource(R.string.lut_subject_help), tag = "lut-subject-help")
        val subjectStatusLabel = when (state.subjectLutStatus.state) {
            OperatorLutState.DISABLED -> R.string.lut_subject_status_disabled
            OperatorLutState.WAITING_FOR_GPU -> R.string.lut_subject_status_waiting
            OperatorLutState.INCOMPATIBLE_DOMAIN -> R.string.lut_subject_status_incompatible
            OperatorLutState.ACTIVE -> R.string.lut_subject_status_active
            OperatorLutState.FAILED -> R.string.lut_subject_status_failed
        }
        Text(stringResource(subjectStatusLabel), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("lut-subject-status"))
        Text(stringResource(R.string.lut_recording_help), color = LocalCineColors.current.pending, modifier = Modifier.testTag("lut-recording-help"))
        val recordingStatusLabel = when (state.recordingLutStatus.state) {
            OperatorLutState.DISABLED -> R.string.lut_recording_status_disabled
            OperatorLutState.WAITING_FOR_GPU -> R.string.lut_recording_status_waiting
            OperatorLutState.INCOMPATIBLE_DOMAIN -> R.string.lut_recording_status_incompatible
            OperatorLutState.ACTIVE -> R.string.lut_recording_status_active
            OperatorLutState.FAILED -> R.string.lut_recording_status_failed
        }
        Text(stringResource(recordingStatusLabel), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("lut-recording-status"))
        if (state.recordingLutSelectionPending) Text(stringResource(R.string.lut_recording_pending), color = LocalCineColors.current.pending,
            modifier = Modifier.testTag("lut-recording-pending"))
        if (recordingFrozen) Text(stringResource(R.string.lut_recording_locked), color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("lut-recording-locked"))
        if (busy) Text(stringResource(R.string.lut_busy), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("lut-busy"))
        if (failed) Text(stringResource(R.string.lut_error), color = LocalCineColors.current.pending, modifier = Modifier.testTag("lut-operation-error"))
        if (completed) Text(stringResource(R.string.lut_done), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("lut-complete"))
        if (library.error != null) {
            Text(stringResource(R.string.lut_corrupt), color = LocalCineColors.current.pending, modifier = Modifier.testTag("lut-storage-error"))
            LutButton(R.string.lut_reset, "reset", !busy && !recordingFrozen) { resetting = true }
        }
        val nameLabel = stringResource(R.string.lut_name)
        Text(nameLabel, color = MaterialTheme.colorScheme.onSurface)
        OutlinedTextField(value = name, onValueChange = { name = it.take(121) }, singleLine = false,
            enabled = available, isError = name.isNotEmpty() && !validName,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lut-name").semantics { contentDescription = nameLabel })
        SettingsChips(stringResource(R.string.lut_kind), LutTransformKind.entries, kind,
            label = { stringResource(if (it == LutTransformKind.TECHNICAL) R.string.lut_technical else R.string.lut_creative) },
            onSelect = { kind = it }, tag = { "lut-kind-${it?.name}" }, rowEnabled = available)
        SettingsChips(stringResource(R.string.lut_input), LutSignalDomain.entries, input,
            label = { stringResource(if (it == LutSignalDomain.SDR_BT709_CODE) R.string.lut_sdr else R.string.lut_log) },
            onSelect = { input = it }, tag = { "lut-input-${it?.name}" }, rowEnabled = available)
        Button({ onImport(name, requireNotNull(kind), requireNotNull(input)) },
            enabled = available && validName && kind != null && input != null && library.entries.size < LutLibrary.MAX_ENTRIES,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lut-import")) { Text(stringResource(R.string.lut_import)) }
        var disableActions by remember { mutableStateOf(false) }
        val disableTitle = stringResource(R.string.lut_disable_title)
        SettingsValueRow(disableTitle, null, Modifier.testTag("lut-disable-row")) { disableActions = true }
        val disables = listOf(
            SettingsAction(stringResource(R.string.lut_disable), "lut-disable", available && library.operatorHash != null) { onSelect(null) },
            SettingsAction(stringResource(R.string.lut_subject_disable), "lut-subject-disable", available && library.subjectHash != null) { onSelectSubject(null) },
            SettingsAction(stringResource(R.string.lut_recording_disable), "lut-recording-disable", recordingEditable && library.recordingHash != null) { onSelectRecording(null) })
        if (disableActions) SettingsActionsDialog(disableTitle, disables) { disableActions = false }
        if (library.entries.isEmpty()) Text(stringResource(R.string.lut_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (entry in library.entries) {
            HorizontalDivider()
            SettingsValueRow(entry.name, null, Modifier.testTag("lut-name-${entry.hash}")) { entryActions = entry.hash }
            Text(stringResource(R.string.lut_entry_details, entry.size, entry.bytes, entry.hash), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if (entry.kind == LutTransformKind.TECHNICAL) R.string.lut_technical else R.string.lut_creative), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if (entry.input == LutSignalDomain.SDR_BT709_CODE) R.string.lut_sdr else R.string.lut_log), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.lut_domain_range, entry.domainMin.joinToString(), entry.domainMax.joinToString()), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val selected = library.operatorHash == entry.hash
            val subjectSelected = library.subjectHash == entry.hash
            val recordingSelected = library.recordingHash == entry.hash
            if (selected) Text(stringResource(R.string.lut_selected), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("lut-selected-${entry.hash}"))
            if (subjectSelected) Text(stringResource(R.string.lut_subject_selected), color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("lut-subject-selected-${entry.hash}"))
            if (recordingSelected) Text(stringResource(R.string.lut_recording_selected), color = LocalCineColors.current.pending,
                modifier = Modifier.testTag("lut-recording-selected-${entry.hash}"))
            if (entryActions == entry.hash) SettingsActionsDialog(entry.name, listOf(
                SettingsAction(stringResource(R.string.lut_select), "lut-select-${entry.hash}", available && !selected) { onSelect(entry.hash) },
                SettingsAction(stringResource(R.string.lut_subject_select), "lut-subject-select-${entry.hash}", available && !subjectSelected) { onSelectSubject(entry.hash) },
                SettingsAction(stringResource(R.string.lut_recording_select), "lut-recording-select-${entry.hash}", recordingEditable && !recordingSelected) {
                    recordingChoice = entry.hash
                },
                SettingsAction(stringResource(R.string.lut_export), "lut-export-${entry.hash}", available) { onExport(entry.hash) },
                SettingsAction(stringResource(R.string.lut_delete), "lut-delete-${entry.hash}", canDelete(entry.hash)) { deleting = entry.hash },
            )) { entryActions = null }
        }
    }
    if (deleting != null || resetting) {
        AlertDialog(onDismissRequest = { deleting = null; resetting = false },
            text = { Text(stringResource(if (resetting) R.string.lut_reset_confirm else R.string.lut_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    val hash = deleting; val reset = resetting
                    deleting = null; resetting = false
                    if (reset && !busy && !recordingFrozen) onReset() else if (!reset && hash != null && canDelete(hash)) onDelete(hash)
                }, enabled = if (resetting) !busy && !recordingFrozen else deleting?.let(::canDelete) == true,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("lut-confirm")) {
                    Text(stringResource(if (resetting) R.string.lut_reset else R.string.lut_delete))
                }
            }, dismissButton = {
                TextButton(onClick = { deleting = null; resetting = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("lut-cancel")) {
                    Text(stringResource(R.string.lut_cancel))
                }
            })
    }
    if (recordingChoice != null) {
        val hash = requireNotNull(recordingChoice)
        val canConfirm = recordingEditable && library.entries.any { it.hash == hash }
        AlertDialog(onDismissRequest = { recordingChoice = null },
            text = { Text(stringResource(R.string.lut_recording_confirm_help), modifier = Modifier.testTag("lut-recording-confirm-help")) },
            confirmButton = {
                TextButton(onClick = { recordingChoice = null; if (canConfirm) onSelectRecording(hash) }, enabled = canConfirm,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("lut-recording-confirm")) {
                    Text(stringResource(R.string.lut_recording_confirm))
                }
            }, dismissButton = {
                TextButton(onClick = { recordingChoice = null }, modifier = Modifier.heightIn(min = 48.dp).testTag("lut-recording-cancel")) {
                    Text(stringResource(R.string.lut_cancel))
                }
            })
    }
}

@Composable
private fun LutButton(label: Int, tag: String, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    SettingsPill(stringResource(label), "lut-$tag", selected, enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
}
