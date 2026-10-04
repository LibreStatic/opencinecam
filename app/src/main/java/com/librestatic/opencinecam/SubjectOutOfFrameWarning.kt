/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.librestatic.opencinecam.camera.FaceDetectCapability
import com.librestatic.opencinecam.camera.FaceDetectGraph
import com.librestatic.opencinecam.camera.FramingEdge
import com.librestatic.opencinecam.camera.OutOfFrameWarningPolicy
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * OCC-PLAN-068 U7. The service publishes only SubjectFramingStatus (a Boolean, a coarse edge and
 * a monotonic timestamp) through CameraUiState; face rectangles never leave the camera callback,
 * and nothing here is persisted or written to metadata.
 */

private val LIVE_PHASES = setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.CAPTURING, CameraUiPhase.RECORDING, CameraUiPhase.SAVED)
private val WarningAmber = Color(0xFFFFB300)

/** Graph the selected mode/profile will use, for the settings capability check. */
internal fun faceDetectGraphFor(state: CameraUiState): FaceDetectGraph {
    val descriptor = state.descriptor
    fun matches(width: Int, height: Int, fps: Int) = fps == state.targetFps && width == state.targetVideoWidth && height == state.targetVideoHeight
    // HFR only when every matching profile is constrained high-speed (no regular route exists).
    val video = descriptor?.videoProfiles.orEmpty().filter { matches(it.size.width, it.size.height, it.fps) }
    val log = descriptor?.logProfiles.orEmpty().filter { matches(it.size.width, it.size.height, it.fps) }
    val hfrVideo = video.isNotEmpty() && video.all { it.constrainedHighSpeed }
    val hfrLog = log.isNotEmpty() && log.all { it.constrainedHighSpeed }
    return when (state.selectedMode) {
        CaptureMode.SLOW_MOTION -> FaceDetectGraph.HIGH_SPEED
        CaptureMode.LOG, CaptureMode.HLG -> if (hfrLog) FaceDetectGraph.HIGH_SPEED else FaceDetectGraph.LOG
        CaptureMode.VIDEO, CaptureMode.TIME_LAPSE -> if (hfrVideo) FaceDetectGraph.HIGH_SPEED else FaceDetectGraph.VIDEO
        else -> FaceDetectGraph.PREVIEW
    }
}

/**
 * Whether the out-of-frame option can work on the current camera and graph: the camera must
 * advertise SIMPLE/FULL face statistics, the graph must not be constrained high-speed, and the
 * engine must not have reported the graph as rejecting them.
 */
internal fun outOfFrameCapable(state: CameraUiState): Boolean {
    val modes = state.descriptor?.faceDetectModes.orEmpty()
    if (FaceDetectCapability.select(modes, faceDetectGraphFor(state)) == null) return false
    // Before the engine evaluates a frame, trust the static capability.
    return !state.subjectFraming.evaluated || state.subjectFraming.supported
}

/** Monotonic re-check: true once no face has been inside the recorded area for longer than the delay. */
@Composable
private fun rememberOutOfFrameWarning(state: CameraUiState, settings: SubjectDisplaySettings): Boolean {
    val status = state.subjectFraming
    val enabled = settings.outOfFrameWarning && state.phase in LIVE_PHASES
    val delaySeconds = settings.outOfFrameDelaySeconds
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(status, enabled, delaySeconds) {
        now = SystemClock.elapsedRealtimeNanos()
        val wait = OutOfFrameWarningPolicy.nanosUntilWarning(status, enabled, delaySeconds, now) ?: return@LaunchedEffect
        if (wait > 0) delay((wait + 999_999) / 1_000_000)
        now = SystemClock.elapsedRealtimeNanos()
    }
    return OutOfFrameWarningPolicy.shouldWarn(status, enabled, delaySeconds, now)
}

/**
 * Arrow toward where the subject should move, from the subject's own point of view while facing
 * the camera: leaving through the left of the recorded image means moving back to their left.
 */
internal fun subjectReturnArrow(edge: FramingEdge): String = when (edge) {
    FramingEdge.LEFT -> "←"
    FramingEdge.RIGHT -> "→"
    FramingEdge.TOP -> "↓"
    FramingEdge.BOTTOM -> "↑"
    FramingEdge.NONE -> ""
}

/** Operator view: the side of the recorded image the subject left through. */
internal fun operatorExitArrow(edge: FramingEdge): String = when (edge) {
    FramingEdge.LEFT -> "←"
    FramingEdge.RIGHT -> "→"
    FramingEdge.TOP -> "↑"
    FramingEdge.BOTTOM -> "↓"
    FramingEdge.NONE -> ""
}

/** Subject-screen banner inside [SubjectOverlayLayer]; output-only, no pointer input. */
@Composable
internal fun SubjectOutOfFrameWarning(state: CameraUiState, settings: SubjectDisplaySettings, modifier: Modifier = Modifier) {
    if (!rememberOutOfFrameWarning(state, settings)) return
    val arrow = subjectReturnArrow(state.subjectFraming.exitEdge)
    val text = stringResource(R.string.subject_out_of_frame_banner)
    Row(
        modifier
            .padding(24.dp)
            .background(WarningAmber, RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = text }
            .testTag("subject-out-of-frame"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (arrow.isNotEmpty()) Text(arrow, color = Color.Black, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Color.Black, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (arrow.isNotEmpty()) Text(arrow, color = Color.Black, fontSize = 44.sp, fontWeight = FontWeight.Bold)
    }
}

/** Small operator HUD chip; draws nothing unless the warning is active. */
@Composable
internal fun OutOfFrameHudChip(state: CameraUiState, modifier: Modifier = Modifier) {
    val settings = state.effectiveSettings?.subjectDisplay ?: return
    if (!rememberOutOfFrameWarning(state, settings)) return
    val text = stringResource(R.string.subject_out_of_frame_hud)
    val arrow = operatorExitArrow(state.subjectFraming.exitEdge)
    Text(
        if (arrow.isEmpty()) text else "$arrow $text",
        color = Color.Black,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .background(WarningAmber, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = text }
            .testTag("operator-out-of-frame"),
    )
}

/** Toggle and delay; disabled with an explanation when the current camera/graph lacks face statistics. */
@Composable
internal fun SubjectOutOfFrameSettings(state: CameraUiState, subject: SubjectDisplaySettings, onChange: (SubjectDisplaySettings) -> Unit) {
    val capable = outOfFrameCapable(state)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (capable) {
            FoldToggle(stringResource(R.string.subject_out_of_frame), subject.outOfFrameWarning) { onChange(subject.copy(outOfFrameWarning = it)) }
            if (subject.outOfFrameWarning) {
                FoldSlider(stringResource(R.string.subject_out_of_frame_delay, subject.outOfFrameDelaySeconds), subject.outOfFrameDelaySeconds.toFloat(), 1f..10f) {
                    onChange(subject.copy(outOfFrameDelaySeconds = it.roundToInt().coerceIn(1, 10)))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("subject-out-of-frame-unavailable"), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.subject_out_of_frame), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(subject.outOfFrameWarning, onCheckedChange = null, enabled = false)
            }
            SettingsHelp(stringResource(R.string.subject_out_of_frame_unavailable))
        }
    }
}
