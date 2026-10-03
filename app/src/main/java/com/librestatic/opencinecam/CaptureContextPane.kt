/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.ui.viewfinder.chromePanel

/**
 * The one host for capture panels (a control dial, the modes, the monitoring toggles). As a
 * [ContextPanePlacement.BOTTOM_SHEET] it docks above the deck with rounded top corners; as
 * [ContextPanePlacement.SIDE] it fills the column it replaces. Taps on its background stay in it, so
 * a stray touch between two tiles never focuses the viewfinder underneath. It does not scroll: the
 * dials scroll their own lists.
 */
@Composable
internal fun CaptureContextPane(
    placement: ContextPanePlacement,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = if (placement == ContextPanePlacement.BOTTOM_SHEET) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    else RoundedCornerShape(0.dp)
    Column(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer.chromePanel())
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(12.dp)
            .testTag("capture-context-pane"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A pane's title, with × when the pane can be closed from here. */
@Composable
internal fun CapturePaneHeader(title: String, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onClose != null) {
            val description = stringResource(R.string.close_panel, title)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp)
            }
        }
    }
}

/**
 * "Controls locked" with an Unlock button, in the layout flow above the controls it blocks, so it
 * never lies over the status lines the way a floating banner did.
 */
@Composable
internal fun CaptureLockBanner(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val locked = stringResource(R.string.operator_locked)
    val unlock = stringResource(R.string.operator_unlock)
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.secondaryContainer.chromePanel())
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            .testTag("operator-locked-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.capture_locked_banner),
            color = colors.onSecondaryContainer,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { contentDescription = locked },
        )
        Button(
            onClick = onUnlock,
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier.semantics { contentDescription = unlock }.testTag("operator-unlock"),
        ) { Text(stringResource(R.string.capture_unlock)) }
    }
}

/**
 * How far the status bar reaches below the safe drawing area's top, which a display cutout can
 * leave shorter than the status bar. The chrome adds it above its top bar so the media and settings
 * keys never sit under the clock.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun statusBarClearance(): Dp {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    val safeTop = WindowInsets.safeDrawing.getTop(density)
    return with(density) { (statusTop - safeTop).coerceAtLeast(0).toDp() }
}
