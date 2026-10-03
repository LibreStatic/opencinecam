/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.ui.viewfinder.chromePanel

/** One mode in the mode sheet. [gateLabel] says why a mode is not ready yet ("Probing…"), if it is not. */
internal class CaptureModeChoice(
    val mode: CaptureMode,
    val label: String,
    val gateLabel: String?,
    val gateColor: Color,
    val enabled: Boolean,
    val selected: Boolean,
)

/**
 * MODE ▾: the current mode in amber over a small MODE caption. Every layout but compact portrait
 * (which keeps the wheel) opens the mode sheet from here; it stays tappable during a take so the
 * resolution and frame rate can be read, though not changed.
 */
@Composable
internal fun CaptureModeButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, expanded: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.capture_mode_button, label)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerHigh.chromePanel())
            .border(1.dp, if (expanded) colors.primary else colors.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("capture-mode-button"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(stringResource(R.string.mode_title), color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, maxLines = 1)
            Text(label, color = colors.primary, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CineGlyph(if (expanded) CineIcon.COLLAPSE else CineIcon.EXPAND, colors.onSurfaceVariant, Modifier.size(18.dp))
    }
}

/**
 * The modes as a grid of two per row, with the resolution of the selected mode under them when it
 * has one (RES lives here now, not in the slots). Modes cannot change during a take.
 */
@Composable
internal fun CaptureModeContent(
    choices: List<CaptureModeChoice>,
    recording: Boolean,
    onSelect: (CaptureMode) -> Unit,
    onClose: (() -> Unit)?,
    resolution: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.verticalScroll(rememberScrollState()).testTag("capture-mode-content"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapturePaneHeader(stringResource(R.string.modes_title), onClose)
        choices.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { choice ->
                    val enabled = choice.enabled && !recording
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (choice.selected) colors.primaryContainer else colors.surfaceContainerHigh)
                            .border(
                                if (choice.selected) 2.dp else 1.dp,
                                if (choice.selected) colors.primary else colors.outlineVariant,
                                RoundedCornerShape(12.dp),
                            )
                            .selectable(selected = choice.selected, enabled = enabled, role = Role.RadioButton) {
                                onSelect(choice.mode)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("capture-mode-${choice.mode.name.lowercase()}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            choice.label,
                            color = when {
                                choice.selected -> colors.onPrimaryContainer
                                enabled -> colors.onSurface
                                else -> colors.onSurface.copy(alpha = 0.45f)
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        choice.gateLabel?.let { Text(it, color = choice.gateColor, fontSize = 12.sp, maxLines = 1) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (resolution != null) Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 4.dp)) { resolution() }
    }
}

/** The mode sheet on compact windows: a modal bottom sheet that Esc and Back close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureModeBottomSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Opens straight to full height and closes in one step: no half-open stop.
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag("capture-mode-sheet"),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .dialogShortcuts { action -> if (action == ShortcutAction.DISMISS) { onDismiss(); true } else false },
        ) { content() }
    }
}
