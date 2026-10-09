/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
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
 * modes can be read, though not changed.
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

/** The modes as a grid of two per row; resolution is the RES slot's. Modes cannot change during a take. */
@Composable
internal fun CaptureModeContent(
    choices: List<CaptureModeChoice>,
    recording: Boolean,
    onSelect: (CaptureMode) -> Unit,
    onClose: (() -> Unit)?,
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
    }
}

/**
 * The mode sheet on compact windows: a sheet over the chrome that the scrim, a downward drag,
 * Esc and Back close (the chrome handles the last two). It draws in the activity's window rather
 * than a dialog's: creating a dialog window made the first frame of every open take ~100 ms.
 */
@Composable
internal fun CaptureModeSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    val title = stringResource(R.string.modes_title)
    val closeLabel = stringResource(R.string.close_panel, title)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(sheetTween(reducedMotion)), exit = fadeOut(sheetTween(reducedMotion))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                    .semantics { onClick(closeLabel) { onDismiss(); true } },
            )
        }
        AnimatedVisibility(
            visible,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(sheetTween(reducedMotion)) { it },
            exit = slideOutVertically(sheetTween(reducedMotion)) { it },
        ) {
            val density = LocalDensity.current
            var dragPx by remember { mutableFloatStateOf(0f) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .offset { IntOffset(0, dragPx.roundToInt()) }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .draggable(
                        rememberDraggableState { delta -> dragPx = (dragPx + delta).coerceAtLeast(0f) },
                        Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (dragPx > with(density) { SHEET_DISMISS_DRAG.toPx() } || velocity > 1500f) onDismiss() else dragPx = 0f
                        },
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .semantics { paneTitle = title }
                    .testTag("capture-mode-sheet"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                )
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) { content() }
            }
        }
    }
}

private val SHEET_DISMISS_DRAG = 96.dp

private fun <T> sheetTween(reducedMotion: Boolean) = tween<T>(if (reducedMotion) 0 else 250, easing = FastOutSlowInEasing)
