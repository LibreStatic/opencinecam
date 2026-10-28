/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.core.model.CalibrationFlag
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import com.librestatic.opencinecam.ui.viewfinder.chromePanel

private val CardShape = RoundedCornerShape(10.dp)

/**
 * The lens calibration run over the viewfinder: progress while it measures, the numbers once it has
 * a result to keep or throw away, or why it stopped. It swallows touches so a stray tap does not
 * reach the viewfinder underneath; [maxHeight] caps it and the card scrolls inside.
 */
@Composable
internal fun LensCalibrationCard(
    state: LensCalibrationUiState,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .heightIn(max = maxHeight)
            .clip(CardShape)
            .background(colors.surfaceContainerHigh.chromePanel())
            .border(1.dp, colors.primary.copy(alpha = .35f), CardShape)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("lens-calibration-card"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.phase) {
            LensCalibrationPhase.RESULT -> ResultBody(state, onSave, onDiscard)
            LensCalibrationPhase.FAILED -> FailedBody(state, onDiscard)
            else -> RunningBody(state, onCancel)
        }
    }
}

@Composable
private fun RunningBody(state: LensCalibrationUiState, onCancel: () -> Unit) {
    Text(stringResource(R.string.lens_cal_card_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(state.phase.titleRes()), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.testTag("lens-calibration-phase"))
    state.lenses.forEach { LensCalibrationLensLine(it, state, running = true) }
    Text(stringResource(R.string.lens_cal_keep_still), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    OutlinedButton(onCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lens-calibration-cancel")) {
        Text(stringResource(android.R.string.cancel))
    }
}

@Composable
private fun ResultBody(state: LensCalibrationUiState, onSave: () -> Unit, onDiscard: () -> Unit) {
    Text(stringResource(R.string.lens_cal_result_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    state.lenses.forEach { LensCalibrationLensLine(it, state, running = false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onDiscard, Modifier.weight(1f).heightIn(min = 48.dp).testTag("lens-calibration-discard")) {
            Text(stringResource(R.string.lens_cal_discard))
        }
        Button(onSave, Modifier.weight(1f).heightIn(min = 48.dp).testTag("lens-calibration-save")) {
            Text(stringResource(R.string.lens_cal_save))
        }
    }
}

@Composable
private fun FailedBody(state: LensCalibrationUiState, onDiscard: () -> Unit) {
    Text(stringResource(R.string.lens_cal_failed_title), color = LocalCineColors.current.record, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource((state.failure ?: LensCalibrationFailure.INTERNAL).messageRes()), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
        modifier = Modifier.testTag("lens-calibration-failure"))
    state.lenses.filter { it.status == LensCalibrationLensStatus.FAILED || it.status == LensCalibrationLensStatus.DONE }
        .forEach { LensCalibrationLensLine(it, state, running = false) }
    OutlinedButton(onDiscard, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lens-calibration-discard")) {
        Text(stringResource(R.string.lens_cal_close))
    }
}

/**
 * One lens: its zoom ratio and reference mark, then either the live status with a bar (while
 * [running]) or the measured colour and alignment error before and after, the quality and any flags.
 */
@Composable
internal fun LensCalibrationLensLine(
    row: LensCalibrationLensRow,
    state: LensCalibrationUiState,
    running: Boolean,
    modifier: Modifier = Modifier,
    muted: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val reference = row.isReferenceIn(state)
    val ratio = lensRatioLabel(row.nominalRatio)
    Column(modifier.fillMaxWidth().testTag("lens-calibration-lens-${row.physicalId}"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (reference) stringResource(R.string.lens_cal_ratio_reference, ratio) else ratio, Modifier.weight(1f).padding(end = 8.dp),
                color = onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (running) {
                Text(lensStatusText(row), color = if (row.status == LensCalibrationLensStatus.FAILED) LocalCineColors.current.record else muted, fontSize = 12.sp)
            } else row.qualityPercent()?.let {
                Text(stringResource(R.string.lens_cal_quality, it), color = muted, fontSize = 12.sp)
            }
        }
        if (running) {
            if (row.status == LensCalibrationLensStatus.MEASURING) LinearProgressIndicator(
                progress = { row.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        } else {
            val colour = calibrationBeforeAfter(row.colorDeltaEBefore, row.colorDeltaEAfter)
            val align = calibrationBeforeAfter(row.alignPxBefore, row.alignPxAfter)
            if (colour != null) Text(stringResource(R.string.lens_cal_delta_e, colour), color = muted, fontSize = 12.sp)
            if (align != null) Text(stringResource(R.string.lens_cal_align_px, align), color = muted, fontSize = 12.sp)
            if (row.flags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                row.flags.forEach { LensCalibrationFlagChip(it) }
            }
        }
    }
}

@Composable
private fun lensStatusText(row: LensCalibrationLensRow): String = when (row.status) {
    LensCalibrationLensStatus.PENDING -> stringResource(R.string.lens_cal_status_pending)
    LensCalibrationLensStatus.REFERENCE -> stringResource(R.string.lens_cal_status_reference)
    LensCalibrationLensStatus.MEASURING -> stringResource(R.string.lens_cal_status_measuring, (row.progress.coerceIn(0f, 1f) * 100).toInt())
    LensCalibrationLensStatus.DONE -> stringResource(R.string.lens_cal_status_done)
    LensCalibrationLensStatus.SKIPPED -> stringResource(R.string.lens_cal_status_skipped)
    LensCalibrationLensStatus.FAILED -> stringResource(R.string.lens_cal_status_failed)
}

/** A short flag as a quiet outlined chip; the word carries it, not the colour. */
@Composable
internal fun LensCalibrationFlagChip(flag: CalibrationFlag) {
    val shape = RoundedCornerShape(6.dp)
    Text(stringResource(flag.labelRes()), color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp,
        modifier = Modifier.clip(shape).border(1.dp, MaterialTheme.colorScheme.outline, shape).padding(horizontal = 6.dp, vertical = 2.dp))
}
