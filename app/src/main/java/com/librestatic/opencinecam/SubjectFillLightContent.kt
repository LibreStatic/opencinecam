/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The fill light's live timeout/thermal policy, provided by the coordinator inside the presentation window.
 * Null outside it (tests, previews): the light then shows at full colour with no notice.
 */
internal val LocalSubjectFillLightOutput = compositionLocalOf<FillLightOutput?> { null }

internal fun FillLightColor.toComposeColor(): Color = Color(red, green, blue)

/**
 * OCC-PLAN-068 U3: a uniform full-bleed soft light at the chosen colour temperature and tint. A timeout or
 * thermal throttle dims it and shows a notice to the subject. It receives immutable status only.
 */
@Composable
internal fun SubjectFillLightContent(state: CameraUiState, settings: SubjectDisplaySettings, modifier: Modifier = Modifier) {
    val output = LocalSubjectFillLightOutput.current ?: fillLightOutput(settings, 0, PowerManager.THERMAL_STATUS_NONE)
    val color = remember(settings.fillLightKelvin, settings.fillLightTint) { fillLightColor(settings.fillLightKelvin, settings.fillLightTint) }
    Box(modifier.background(color.scaled(output.colorScale).toComposeColor()).testTag("subject-fill-light")) {
        if (state.countdownSeconds > 0) CountdownBadge(state.countdownSeconds, Modifier.align(Alignment.Center))
        output.notice?.let { notice ->
            val timedOut = notice == FillLightNotice.TIMED_OUT
            Text(fillLightNoticeText(notice), color = if (timedOut) Color(0xFFBDBDBD) else Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.align(if (timedOut) Alignment.Center else Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
                    .background(Color.Black.copy(alpha = if (timedOut) 0f else 0.75f), RoundedCornerShape(12.dp)).padding(12.dp)
                    .testTag("subject-fill-light-notice")
                    .semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
internal fun fillLightNoticeText(notice: FillLightNotice): String = stringResource(when (notice) {
    FillLightNotice.TIMED_OUT -> R.string.subject_fill_light_notice_timeout
    FillLightNotice.THERMAL_WARM -> R.string.subject_fill_light_notice_warm
    FillLightNotice.THERMAL_HOT -> R.string.subject_fill_light_notice_hot
})

@Composable
internal fun SubjectFillLightSettings(state: CameraUiState, subject: SubjectDisplaySettings, onChange: (SubjectDisplaySettings) -> Unit) {
    val coordinator = LocalFoldDisplayCoordinator.current
    val fallback = remember { kotlinx.coroutines.flow.MutableStateFlow<FillLightOutput?>(null) }
    val live by (coordinator?.fillLight ?: fallback).collectAsState()
    val active = subject.mode == SubjectDisplayMode.FILL_LIGHT
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsHelp(stringResource(R.string.subject_fill_light_help))
        FoldToggle(stringResource(R.string.subject_fill_light_enable), active) {
            onChange(subject.copy(mode = if (it) SubjectDisplayMode.FILL_LIGHT else SubjectDisplayMode.STATUS))
        }
        if (active) {
            // A suggestion only: the app never locks AE/AWB on its own.
            val locked = state.aeLockActive
            Text(stringResource(if (locked) R.string.subject_fill_light_lock_awb_suggestion else R.string.subject_fill_light_lock_suggestion),
                color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                    .padding(12.dp).testTag("subject-fill-light-lock-suggestion"))
        }
        live?.notice?.let { notice ->
            Text(fillLightNoticeText(notice), color = MaterialTheme.colorScheme.error, fontSize = 14.sp,
                modifier = Modifier.testTag("subject-fill-light-operator-notice").semantics { liveRegion = LiveRegionMode.Polite })
            if (notice == FillLightNotice.TIMED_OUT) Button(onClick = { coordinator?.restartFillLight() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("subject-fill-light-restart")) { Text(stringResource(R.string.subject_fill_light_restart)) }
        }
        val swatchDescription = stringResource(R.string.subject_fill_light_swatch, subject.fillLightKelvin, subject.fillLightTint)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).background(fillLightColor(subject.fillLightKelvin, subject.fillLightTint).toComposeColor(), RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .testTag("subject-fill-light-swatch").semantics { contentDescription = swatchDescription })
            Text(stringResource(R.string.subject_fill_light_swatch_note), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.weight(1f))
        }
        FoldSlider(stringResource(R.string.subject_fill_light_kelvin, subject.fillLightKelvin), subject.fillLightKelvin.toFloat(),
            FILL_LIGHT_MIN_KELVIN.toFloat()..FILL_LIGHT_MAX_KELVIN.toFloat()) {
            onChange(subject.copy(fillLightKelvin = ((it / 100).roundToInt() * 100).coerceIn(FILL_LIGHT_MIN_KELVIN, FILL_LIGHT_MAX_KELVIN)))
        }
        FoldSlider(stringResource(R.string.subject_fill_light_tint, subject.fillLightTint), subject.fillLightTint.toFloat(),
            -FILL_LIGHT_TINT_LIMIT.toFloat()..FILL_LIGHT_TINT_LIMIT.toFloat()) {
            onChange(subject.copy(fillLightTint = it.roundToInt().coerceIn(-FILL_LIGHT_TINT_LIMIT, FILL_LIGHT_TINT_LIMIT)))
        }
        // The same brightness preference as the other modes, labelled as a request rather than measured light.
        FoldSlider(stringResource(R.string.subject_fill_light_brightness, (subject.brightness * 100).roundToInt()), subject.brightness * 100, 0f..100f) {
            onChange(subject.copy(brightness = (it / 100).coerceIn(0f, 1f)))
        }
        SettingsChips(stringResource(R.string.subject_fill_light_timeout), listOf(0, 60, 300, 600, 1800), subject.fillLightTimeoutSeconds,
            label = { if (it == 0) stringResource(R.string.subject_fill_light_timeout_off) else stringResource(R.string.subject_fill_light_timeout_minutes, it / 60) },
            onSelect = { onChange(subject.copy(fillLightTimeoutSeconds = it)) }, tag = { "subject-fill-timeout-$it" })
        Text(stringResource(R.string.subject_fill_light_thermal_help), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}
