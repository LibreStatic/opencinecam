/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam

import androidx.compose.runtime.ReadOnlyComposable
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource

@Composable
internal fun modeLabel(mode: CaptureMode): String = when (mode) {
    CaptureMode.PHOTO -> stringResource(R.string.photo_mode)
    CaptureMode.RAW_PHOTO -> stringResource(R.string.raw_photo_mode)
    CaptureMode.BURST -> stringResource(R.string.burst_mode)
    CaptureMode.VIDEO -> stringResource(R.string.video_mode)
    CaptureMode.SLOW_MOTION -> stringResource(R.string.slow_motion_mode)
    CaptureMode.TIME_LAPSE -> stringResource(R.string.time_lapse_mode)
    CaptureMode.BRACKET -> stringResource(R.string.bracket_mode)
    CaptureMode.LIGHT_TRAIL -> stringResource(R.string.light_trail_mode)
    CaptureMode.LOG -> stringResource(R.string.log_mode)
    CaptureMode.HLG -> stringResource(R.string.hlg_mode)
    CaptureMode.APV -> stringResource(R.string.apv_mode)
    CaptureMode.RAW_VIDEO -> stringResource(R.string.raw_video_mode)
}

/**
 * An AVAILABLE gate on an experimental mode is shown as "Experimental" rather than as ready. LOG and
 * HLG leave that state once the camera has an OpenCineLog profile backed by accepted evidence.
 */
internal fun showsExperimentalGate(mode: CaptureMode, gate: ModeGateState, hasVerifiedOpenCineLog: Boolean): Boolean =
    gate == ModeGateState.AVAILABLE && mode.experimental && !(mode.usesLogGraph && hasVerifiedOpenCineLog)

@Composable
internal fun gateLabel(gate: ModeGateState, experimental: Boolean = false): String = when {
    experimental -> stringResource(R.string.gate_experimental)
    else -> gateLabel(gate)
}

@Composable
internal fun gateLabel(gate: ModeGateState): String = when (gate) {
    ModeGateState.AVAILABLE -> stringResource(R.string.available)
    ModeGateState.CANDIDATE -> stringResource(R.string.candidate)
    ModeGateState.UNSUPPORTED -> stringResource(R.string.unsupported)
    ModeGateState.FAILED -> stringResource(R.string.failed)
}

@Composable
@ReadOnlyComposable
internal fun gateColor(gate: ModeGateState, experimental: Boolean): Color =
    if (experimental) MaterialTheme.colorScheme.onSurface else gateColor(gate)

@Composable
@ReadOnlyComposable
internal fun gateColor(gate: ModeGateState): Color = when (gate) {
    // Red belongs to REC; a failed mode is a warning, like a pending one.
    ModeGateState.AVAILABLE -> VerifiedCyan
    ModeGateState.CANDIDATE -> MaterialTheme.colorScheme.onSurface
    ModeGateState.UNSUPPORTED -> Muted
    ModeGateState.FAILED -> LocalCineColors.current.pending
}

internal fun CaptureMode.isStillMode(): Boolean = this in setOf(
    CaptureMode.PHOTO,
    CaptureMode.RAW_PHOTO,
    CaptureMode.BURST,
    CaptureMode.BRACKET,
    CaptureMode.LIGHT_TRAIL,
)

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

internal fun formatBytes(bytes: Long?): String = when {
    bytes == null -> "—"
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "${bytes / 1_000L} KB"
}

internal fun rotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
