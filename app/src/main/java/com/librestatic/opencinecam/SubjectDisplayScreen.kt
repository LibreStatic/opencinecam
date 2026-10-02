/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Subject-only content: no camera/service actions and no route into operator settings. */
@Composable
internal fun SubjectDisplayScreen(
    state: CameraUiState,
    settings: SubjectDisplaySettings,
    previewPort: SubjectPreviewPort? = null,
    cues: SubjectSessionCues = SubjectSessionCues(),
    productionSlate: ProductionSlateSettings = ProductionSlateSettings(),
) {
    val status = when {
        state.recordingFinalizing -> R.string.subject_finalizing
        state.phase == CameraUiPhase.RECORDING && state.recordingPauseStatus?.paused == true -> R.string.recording_paused
        state.phase == CameraUiPhase.RECORDING -> R.string.subject_recording
        state.phase == CameraUiPhase.SAVED -> R.string.subject_saved
        state.phase == CameraUiPhase.ERROR -> R.string.subject_error
        state.phase == CameraUiPhase.CAPTURING -> R.string.subject_capturing
        state.phase == CameraUiPhase.PREVIEWING -> R.string.subject_ready
        else -> R.string.subject_preparing
    }
    val scroll = rememberScrollState()
    val pixelsPerSecond = settings.prompterSpeedDpPerSecond * LocalDensity.current.density
    LaunchedEffect(settings.prompterText) { scroll.scrollTo(0) }
    LaunchedEffect(settings.mode, settings.prompterPaused, pixelsPerSecond, settings.prompterText) {
        if (settings.mode != SubjectDisplayMode.TELEPROMPTER || settings.prompterPaused) return@LaunchedEffect
        var previous = withFrameNanos { it }
        var remainder = 0f
        while (true) {
            val now = withFrameNanos { it }
            remainder += ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.1f) * pixelsPerSecond
            previous = now
            val delta = remainder.toInt()
            remainder -= delta
            if (delta > 0) scroll.scrollTo((scroll.value + delta).coerceAtMost(scroll.maxValue))
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("subject-display")) {
        if (settings.mode == SubjectDisplayMode.FILL_LIGHT) {
            // The light is full bleed, so it ignores the safe-drawing padding of the other modes.
            SubjectFillLightContent(state, settings, Modifier.fillMaxSize())
        } else Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (subjectShowsCountdownBadge(state, settings)) CountdownBadge(state.countdownSeconds)
            if (settings.showStatus || settings.mode == SubjectDisplayMode.STATUS) {
                Text(stringResource(status), color = if (state.phase == CameraUiPhase.RECORDING) Color(0xFFFF6666) else Color.White,
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("subject-capture-status"))
                if (state.phase == CameraUiPhase.RECORDING || state.recordingFinalizing) {
                    val seconds = state.recordingElapsedMs.coerceAtLeast(0) / 1000
                    Text(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60), color = Color.White, fontSize = 24.sp)
                }
            }
            if (settings.operatorCue.isNotBlank()) Text(settings.operatorCue, color = Color(0xFFFFCF66), fontSize = 22.sp)
            val content = Modifier.weight(1f).fillMaxWidth()
            when (settings.mode) {
                SubjectDisplayMode.TELEPROMPTER -> Text(
                    settings.prompterText.ifBlank { stringResource(R.string.subject_empty_script) },
                    color = Color.White, fontSize = settings.prompterFontSp.sp,
                    lineHeight = (settings.prompterFontSp * 1.4f).sp,
                    modifier = content.verticalScroll(scroll, enabled = !settings.touchLocked).testTag("subject-script"),
                )
                SubjectDisplayMode.PREVIEW -> SubjectCameraPreview(previewPort, content, state, settings)
                SubjectDisplayMode.REVIEW -> SubjectReviewContent(state, settings, cues, content)
                SubjectDisplayMode.INTERVIEW -> SubjectInterviewContent(state, settings, cues, content)
                SubjectDisplayMode.SLATE -> SubjectSlateContent(state, settings, productionSlate, content)
                SubjectDisplayMode.STATUS, SubjectDisplayMode.FILL_LIGHT ->
                    Text(stringResource(R.string.subject_status_only), color = Color.LightGray, fontSize = 16.sp)
            }
        }
        // Tally, countdown and warnings layer above every mode, including the full-bleed fill light.
        SubjectOverlayLayer(state, settings, cues, Modifier.matchParentSize())
    }
}
