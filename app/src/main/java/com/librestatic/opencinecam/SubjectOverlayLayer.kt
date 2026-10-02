/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val SubjectTallyRed = Color(0xFFFF1A1A)
internal val SubjectTallyAmber = Color(0xFFFFB000)

/** Wide enough to read from about 3 m on the cover screen; it follows the window brightness request. */
internal val SubjectTallyWidth = 20.dp

/**
 * Drawn above every subject mode, including the full-bleed fill light. It is output-only: no
 * pointer input and no capture or settings actions (ADR-0031). The tally border and the giant
 * countdown (OCC-PLAN-068 U2) belong here; the self-monitor guides (U1) draw inside the preview.
 */
@Composable
internal fun SubjectOverlayLayer(
    state: CameraUiState,
    settings: SubjectDisplaySettings,
    cues: SubjectSessionCues,
    modifier: Modifier = Modifier,
) {
    Box(modifier.testTag("subject-overlay")) {
        if (subjectShowsGiantCountdown(state, settings)) SubjectGiantCountdown(state.countdownSeconds, Modifier.matchParentSize())
        SubjectOutOfFrameWarning(state, settings, Modifier.align(Alignment.BottomCenter))
        val tally = subjectTally(state)
        if (settings.tallyBorder && tally != SubjectTally.NONE) SubjectTallyBorder(tally, Modifier.matchParentSize())
    }
}

@Composable
internal fun SubjectTallyBorder(tally: SubjectTally, modifier: Modifier = Modifier) {
    val color = if (tally == SubjectTally.RECORDING) SubjectTallyRed else SubjectTallyAmber
    // Decorative: the status text already announces recording, preparing and saving.
    Canvas(modifier.testTag(if (tally == SubjectTally.RECORDING) "subject-tally-recording" else "subject-tally-pending")) {
        val width = SubjectTallyWidth.toPx()
        drawRect(color, Offset(width / 2f, width / 2f), Size(size.width - width, size.height - width), style = Stroke(width))
    }
}

@Composable
internal fun SubjectGiantCountdown(seconds: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.self_countdown, seconds)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Sized from the window, not the font scale, so 200% font cannot push the numeral off screen.
        val fontSize = with(LocalDensity.current) { (minOf(maxWidth, maxHeight) * 0.6f).toSp() }
        Text(seconds.toString(), color = Color.White,
            style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold,
                shadow = Shadow(Color.Black, Offset(0f, 4f), blurRadius = 24f)),
            modifier = Modifier.testTag("subject-giant-countdown").clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            })
    }
}
