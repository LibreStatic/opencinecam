/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import java.util.Locale

/** Subject-only content: no camera/service actions and no route into operator settings. */
@Composable
internal fun SubjectDisplayScreen(
    state: CameraUiState,
    settings: SubjectDisplaySettings,
    previewPort: SubjectPreviewPort? = null,
    cues: SubjectSessionCues = SubjectSessionCues(),
    productionSlate: ProductionSlateSettings = ProductionSlateSettings(),
    timecodeRate: com.librestatic.opencinecam.camera.TimecodeRate? = null,
    syncFlash: Boolean = false,
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
    // The REC header is for a take in progress; at rest the modes keep the whole panel.
    val active = state.phase == CameraUiPhase.RECORDING || state.recordingFinalizing || state.phase == CameraUiPhase.ERROR
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("subject-display")) {
        if (settings.mode == SubjectDisplayMode.FILL_LIGHT) {
            // The light is full bleed, so it ignores the safe-drawing padding of the other modes.
            SubjectFillLightContent(state, settings, Modifier.fillMaxSize())
        } else Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (subjectShowsCountdownBadge(state, settings)) CountdownBadge(state.countdownSeconds, Modifier.align(Alignment.CenterHorizontally))
            val hero = settings.mode == SubjectDisplayMode.STATUS
            if (!hero && settings.showStatus && active) SubjectRecHeader(state, status)
            if (!hero && settings.operatorCue.isNotBlank()) SubjectCue(settings.operatorCue)
            val content = Modifier.weight(1f).fillMaxWidth()
            when (settings.mode) {
                SubjectDisplayMode.TELEPROMPTER -> Text(
                    settings.prompterText.ifBlank { stringResource(R.string.subject_empty_script) },
                    color = Color.White, fontSize = settings.prompterFontSp.sp,
                    lineHeight = (settings.prompterFontSp * 1.4f).sp,
                    modifier = content.verticalScroll(scroll, enabled = !settings.touchLocked).padding(horizontal = 8.dp).testTag("subject-script"),
                )
                SubjectDisplayMode.PREVIEW -> SubjectCameraPreview(previewPort, content, state, settings)
                SubjectDisplayMode.REVIEW -> SubjectReviewContent(state, settings, cues, content)
                SubjectDisplayMode.INTERVIEW -> SubjectInterviewContent(state, settings, cues, content)
                SubjectDisplayMode.SLATE -> SubjectSlateContent(state, settings, productionSlate, content, timecodeRate)
                SubjectDisplayMode.STATUS, SubjectDisplayMode.FILL_LIGHT -> SubjectStatusHero(state, status, settings.operatorCue, content)
            }
        }
        // Tally, countdown and warnings layer above every mode, including the full-bleed fill light.
        SubjectOverlayLayer(state, settings, cues, Modifier.matchParentSize())
        // The U6 sync flash is a full white frame, so it covers every layer for its ~100 ms.
        if (syncFlash) SubjectSyncFlash(Modifier.matchParentSize())
    }
}

private val SubjectAmber = Color(0xFFFFB300)

private fun elapsedLabel(state: CameraUiState): String {
    val seconds = state.recordingElapsedMs.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

/** The state the subject reads from across the room: Ready at rest, a large red REC and the take time while recording. */
@Composable
private fun SubjectStatusHero(state: CameraUiState, status: Int, cue: String, modifier: Modifier) {
    val recording = state.phase == CameraUiPhase.RECORDING && !state.recordingFinalizing
    val record = LocalCineColors.current.record
    val paused = state.recordingPauseStatus?.paused == true
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (recording && !paused) Box(Modifier.size(28.dp).clip(CircleShape).background(record))
            Text(stringResource(status), color = when {
                state.phase == CameraUiPhase.ERROR -> MaterialTheme.colorScheme.error
                recording && !paused -> record
                else -> Color.White
            }, fontSize = if (recording) 64.sp else 56.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                lineHeight = 64.sp, modifier = Modifier.testTag("subject-capture-status"))
        }
        if (state.phase == CameraUiPhase.RECORDING || state.recordingFinalizing) {
            Text(elapsedLabel(state), color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace)
        }
        if (cue.isNotBlank()) SubjectCue(cue, centered = true)
        else if (state.phase == CameraUiPhase.PREVIEWING) {
            Text(stringResource(R.string.subject_ready_hint), color = Color(0xFF9AA4AA), fontSize = 20.sp, textAlign = TextAlign.Center)
        }
    }
}

/** A slim REC line over the other modes while a take runs: a red dot, the state and the take time. */
@Composable
private fun SubjectRecHeader(state: CameraUiState, status: Int) {
    val record = LocalCineColors.current.record
    val recording = state.phase == CameraUiPhase.RECORDING && state.recordingPauseStatus?.paused != true && !state.recordingFinalizing
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (recording) Box(Modifier.size(14.dp).clip(CircleShape).background(record))
        Text(stringResource(status), color = if (state.phase == CameraUiPhase.ERROR) MaterialTheme.colorScheme.error else if (recording) record else Color.White,
            fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false).testTag("subject-capture-status"))
        Spacer(Modifier.weight(1f))
        if (state.phase == CameraUiPhase.RECORDING || state.recordingFinalizing) {
            Text(elapsedLabel(state), color = Color.White, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/** The operator's cue, in amber: the one colour on the cover that means "this is for you". */
@Composable
private fun SubjectCue(cue: String, centered: Boolean = false) {
    Text(cue, color = SubjectAmber, fontSize = if (centered) 28.sp else 24.sp, fontWeight = FontWeight.Medium,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1806), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("subject-cue"))
}
