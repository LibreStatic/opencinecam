/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the user asked the export dialog to do with the report. */
internal enum class ReportExportAction { SAVE, SHARE, COPY }

/** Consent only means something for the full export, so going back to redacted drops it. */
internal fun consentAfterModeChange(mode: ReportExportMode, consent: Boolean): Boolean =
    consent && mode == ReportExportMode.FULL

/**
 * One dialog for save, share and copy. Redacted is the default (OCC-PRIV-005); the full report needs
 * the warning about fingerprints and identifiers ticked (OCC-PRIV-004) before any action unlocks.
 */
@Composable
internal fun ReportExportDialog(onDismiss: () -> Unit, onAction: (ReportExportAction, ReportExportMode, Boolean) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(ReportExportMode.REDACTED) }
    var consent by rememberSaveable { mutableStateOf(false) }
    val allowed = canExport(mode, consent)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("capabilities-export-dialog"),
        title = { Text(stringResource(R.string.caps_export_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.caps_export_help), color = SettingsMuted, fontSize = 13.sp)
                ModeRow(ReportExportMode.REDACTED, mode, R.string.caps_export_redacted, R.string.caps_export_redacted_help) {
                    mode = it; consent = consentAfterModeChange(it, consent)
                }
                ModeRow(ReportExportMode.FULL, mode, R.string.caps_export_full, R.string.caps_export_full_help) {
                    mode = it; consent = consentAfterModeChange(it, consent)
                }
                if (mode == ReportExportMode.FULL) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                            .toggleable(consent, role = Role.Checkbox) { consent = it }
                            .testTag("capabilities-export-consent"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(consent, onCheckedChange = null)
                        Text(stringResource(R.string.caps_export_consent), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
                listOf(
                    Triple(ReportExportAction.SAVE, R.string.caps_export_save, "capabilities-export-save"),
                    Triple(ReportExportAction.SHARE, R.string.caps_export_share, "capabilities-export-share"),
                    Triple(ReportExportAction.COPY, R.string.caps_export_copy, "capabilities-export-copy"),
                ).forEach { (action, label, tag) ->
                    OutlinedButton(
                        onClick = { onAction(action, mode, consent) },
                        enabled = allowed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
                    ) { Text(stringResource(label)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun ModeRow(mode: ReportExportMode, selected: ReportExportMode, title: Int, help: Int, onSelect: (ReportExportMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
            .selectable(mode == selected, role = Role.RadioButton) { onSelect(mode) }
            .testTag("capabilities-export-${mode.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(mode == selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Text(stringResource(help), color = SettingsMuted, fontSize = 12.sp)
        }
    }
}
