/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The left side of a settings row: the title and, under it, a one-line muted summary. */
@Composable
internal fun SettingsRowLabel(title: String, summary: String? = null, enabled: Boolean = true) {
    Column {
        Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else SettingsMuted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        summary?.let { Text(it, color = SettingsMuted, fontSize = 14.sp) }
    }
}

/**
 * A setting with a few short exclusive choices: title and summary on the left, the choices as pills
 * in the control column, so every such setting in the list shares one layout. Each pill carries
 * [tag] of its choice and its label "[tag]-label".
 */
@Composable
internal fun <T> SettingsOptionRow(
    title: String,
    summary: String?,
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    tag: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SettingsRow(modifier,
        label = { SettingsRowLabel(title, summary, enabled) },
        control = {
            SettingsPillRow(Modifier.selectableGroup()) {
                choices.forEach { choice ->
                    SettingsPill(label(choice), tag(choice), selected = choice == selected, enabled = enabled) { onSelect(choice) }
                }
            }
        })
}
