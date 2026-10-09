/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.librestatic.opencinecam.camera.SubjectPreviewStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun SubjectCameraPreview(
    port: SubjectPreviewPort?,
    modifier: Modifier = Modifier,
    state: CameraUiState? = null,
    settings: SubjectDisplaySettings = SubjectDisplaySettings(),
) {
    val fallback = remember { MutableStateFlow(SubjectPreviewStatus()) }
    val status by (port?.statuses ?: fallback).collectAsState()
    val context = LocalContext.current
    val view = remember(context) { SurfaceView(context).apply { isClickable = false; isFocusable = false } }
    var revision by remember { mutableIntStateOf(0) }
    var lease by remember { mutableStateOf<AutoCloseable?>(null) }
    // Rotation of the attached lease, so the self-monitor guides match the geometry the GPU used.
    var attachedRotation by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(view) {
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { revision++ }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { revision++ }
            override fun surfaceDestroyed(holder: SurfaceHolder) { lease?.close(); lease = null; revision++ }
        }
        val displays = context.getSystemService(DisplayManager::class.java)
        var lastRotation = view.display?.rotation
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) { if (displayId == view.display?.displayId) revision++ }
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == view.display?.displayId && view.display?.rotation != lastRotation) {
                    lastRotation = view.display?.rotation
                    revision++
                }
            }
        }
        view.holder.addCallback(callback)
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { view.holder.removeCallback(callback); displays.unregisterDisplayListener(listener); lease?.close(); lease = null }
    }
    LaunchedEffect(view, port, revision) {
        lease?.close()
        lease = null
        withFrameNanos { }
        withFrameNanos { }
        if (view.holder.surface.isValid && view.width > 0 && view.height > 0) {
            val rotation = when (view.display?.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            // Operator panels drop to the content rate (30 Hz in dual), so match their peak mode.
            fun Display.peakRates() = mode.let { physical ->
                supportedModes.filter { it.physicalWidth == physical.physicalWidth && it.physicalHeight == physical.physicalHeight }.map { it.refreshRate }
            }
            val frameRate = view.display?.let { shown ->
                subjectPreviewFrameRate(
                    context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.peakRates()?.maxOrNull(),
                    shown.peakRates(),
                )
            } ?: 0f
            // Ask the subject display to run at that rate; it otherwise idles at its 60 Hz default.
            if (frameRate > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { view.holder.surface.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
            }
            lease = port?.attach(view.holder.surface, rotation, frameRate)
            attachedRotation = rotation
        }
    }
    Box(modifier.background(Color.Black).testTag("subject-camera-preview")) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        // Compose draws over the SurfaceView on the subject window only; the encoder never sees it.
        if (state != null) SubjectSelfMonitorOverlay(state, settings, attachedRotation, Modifier.matchParentSize())
        SubjectFrameBadge(status, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

@Composable
internal fun SubjectFrameBadge(status: SubjectPreviewStatus, modifier: Modifier = Modifier) {
    var tick by remember { mutableIntStateOf(0) }
    val receivedAt = status.sourceReceivedAtMs
    // Ticks only once frames flow: the freshness check needs a clock, the waiting state does not.
    LaunchedEffect(receivedAt != null) { if (receivedAt != null) while (true) { delay(200); tick++ } }
    // Sample the clock per status too: statuses arrive every ~66 ms stamped after the last tick,
    // and isFresh rejects a submission newer than `now`, so a tick-only clock reads live as paused.
    val now = remember(status, tick) { SystemClock.elapsedRealtime() }
    val failure = status.failure
    if (failure == null && receivedAt == null) {
        // No picture yet: a calm centred state rather than a bar over a black panel.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CineGlyph(CineIcon.CAMERA, Color(0xFF7D878D), Modifier.size(56.dp))
                Text(stringResource(R.string.subject_preview_waiting), color = Color(0xFFBDC5CA), fontSize = 20.sp,
                    modifier = Modifier.testTag("subject-preview-age"))
            }
        }
        return
    }
    val live = failure == null && status.isFresh(now)
    val message = when {
        failure != null -> stringResource(R.string.subject_preview_failed, failure)
        !live -> stringResource(R.string.subject_preview_stale)
        else -> stringResource(R.string.subject_preview_age, (now - (receivedAt ?: now)).coerceAtLeast(0))
    }
    // A live picture keeps its latency as a small corner reading; problems get the full bar.
    if (live) Box(modifier, contentAlignment = Alignment.BottomEnd) {
        Text(message, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
            modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp).testTag("subject-preview-age"))
    } else Text(message, color = Color.White, fontSize = 16.sp,
        modifier = modifier.background(Color.Black.copy(alpha = 0.85f)).padding(8.dp).testTag("subject-preview-age"))
}

/** OCC-PLAN-068 U1: recorded-area bands, framing guide and a small audio meter over the preview. */
@Composable
internal fun SubjectSelfMonitorOverlay(state: CameraUiState, settings: SubjectDisplaySettings, rotationDegrees: Int?, modifier: Modifier = Modifier) {
    val stream = subjectPreviewStreamSize(state)
    val squeeze = state.effectiveSettings?.anamorphicSqueeze?.factor ?: 1f
    val sensor = state.descriptor?.sensorOrientation ?: 90
    val drawsFrame = settings.previewRecordedAreaBands || settings.previewGuide != SubjectPreviewGuide.NONE
    Box(modifier) {
        if (drawsFrame && stream != null && rotationDegrees != null) {
            Canvas(Modifier.matchParentSize().testTag("subject-self-monitor-frame")) {
                val frame = subjectRecordedFrame(size.width, size.height, stream.first, stream.second, squeeze, sensor, rotationDegrees)
                    ?: return@Canvas
                if (settings.previewRecordedAreaBands) {
                    // Grey bands make the letterbox read as "outside the file" rather than as dark picture.
                    val band = Color(0xFF2B2B2B)
                    if (frame.top > 0f) drawRect(band, Offset.Zero, Size(size.width, frame.top))
                    if (frame.bottom < size.height) drawRect(band, Offset(0f, frame.bottom), Size(size.width, size.height - frame.bottom))
                    if (frame.left > 0f) drawRect(band, Offset(0f, frame.top), Size(frame.left, frame.height))
                    if (frame.right < size.width) drawRect(band, Offset(frame.right, frame.top), Size(size.width - frame.right, frame.height))
                    drawRect(Color.White.copy(alpha = 0.8f), Offset(frame.left, frame.top), Size(frame.width, frame.height), style = Stroke(2.dp.toPx()))
                }
                val guide = subjectGuideShape(frame, settings.previewGuide)
                val guideColor = Color.White.copy(alpha = 0.55f)
                val stroke = 1.5.dp.toPx()
                guide.lines.forEach { (start, end) -> drawLine(guideColor, Offset(start.first, start.second), Offset(end.first, end.second), stroke) }
                guide.rects.forEach { rect -> drawRect(guideColor, Offset(rect.left, rect.top), Size(rect.width, rect.height), style = Stroke(stroke)) }
            }
        }
        if (settings.previewAudioMeter) SubjectAudioMeter(state, Modifier.align(Alignment.TopStart).padding(12.dp))
    }
}

/** Peak bars from the service's immutable level snapshot; expires like the operator meter. */
@Composable
internal fun SubjectAudioMeter(state: CameraUiState, modifier: Modifier = Modifier) {
    val state = liveCameraState(state)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(100) } }
    val levels = currentAudioMeterSnapshot(state.audioLevels, state.audioMonitoringActive, now)?.channels.orEmpty()
    val label = stringResource(R.string.subject_audio_meter_label)
    Column(modifier.width(120.dp).background(Color.Black.copy(alpha = 0.7f)).padding(6.dp)
        .testTag("subject-audio-meter").semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(maxOf(1, levels.size)) { index ->
            val peak = levels.getOrNull(index)?.peakDbfs
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                drawRect(Color(0xFF283033))
                val color = when {
                    (peak ?: -120f) >= -3f -> Color(0xFFFF3B30)
                    (peak ?: -120f) >= -12f -> Color(0xFFFFB000)
                    else -> Color(0xFF46C36F)
                }
                drawRect(color, size = Size(size.width * subjectMeterFraction(peak), size.height))
            }
        }
    }
}
