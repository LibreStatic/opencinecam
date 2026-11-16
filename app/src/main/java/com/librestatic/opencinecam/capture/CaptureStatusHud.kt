/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.librestatic.opencinecam.capture

import androidx.compose.material3.LocalTextStyle
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import android.content.Context
import android.os.SystemClock
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.librestatic.opencinecam.media.video.hevcSdrSupported
import com.librestatic.opencinecam.media.video.resolveVideoCodec
import com.librestatic.opencinecam.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.librestatic.opencinecam.ui.viewfinder.chromePanel
import com.librestatic.opencinecam.ui.WindowWidthClass
import com.librestatic.opencinecam.ui.windowWidthClass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CameraUiState
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.liveCameraState
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.settings.audioClipLabel
import com.librestatic.opencinecam.settings.audioInputTypeLabel
import com.librestatic.opencinecam.settings.AudioMeterMode
import com.librestatic.opencinecam.settings.AudioMeterPeakHold
import com.librestatic.opencinecam.settings.AudioMeterSettings
import com.librestatic.opencinecam.settings.audioOutputLabel
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.settings.captureStatusFreeBytes
import com.librestatic.opencinecam.settings.currentAudioMeterSnapshot
import com.librestatic.opencinecam.settings.displayDb
import com.librestatic.opencinecam.settings.formatAudioRate
import com.librestatic.opencinecam.settings.HistogramMode
import com.librestatic.opencinecam.subject.OutOfFrameHudChip
import com.librestatic.opencinecam.ui.CineIcon
import com.librestatic.opencinecam.ui.cineMarquee
import com.librestatic.opencinecam.ui.FitText
import com.librestatic.opencinecam.ui.rememberMarqueeActive
import kotlinx.coroutines.delay
import kotlin.math.max
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle

@Composable
private fun rememberBatteryPercent(): Int? {
    val context = LocalContext.current
    fun read() = (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    // Read once up front so the first frame already carries the value.
    var batteryPercent by remember { mutableStateOf(read()) }
    LaunchedEffect(Unit) {
        while (true) {
            batteryPercent = read()
            delay(5_000)
        }
    }
    return batteryPercent
}

/**
 * The top bar's camera state: timecode when one runs, free space, the time left of a RAW video take
 * and battery. A value the device does not report is left out rather than shown as a dash. When the
 * one line does not fit, the time left moves to a second row ([statusLineLayout]); when even that
 * does not fit, the line wraps over [maxLines] (three or more), scrolls ([cineMarquee]) or ends in
 * an ellipsis.
 */
@Composable
internal fun CaptureStatusLine(state: CameraUiState, modifier: Modifier = Modifier, maxLines: Int = 1, textAlign: TextAlign? = null) {
    val battery = rememberBatteryPercent()
    val muted = Muted
    // Low battery is a warning, not a failure: amber, never the recording red.
    val batteryColor = if (battery != null && battery <= 15) LocalCineColors.current.pending else OkGreen
    val free = captureStatusFreeBytes(state)?.let { stringResource(R.string.capture_status_free, formatBytes(it)) }
    // RAW video fills a card in minutes, so the time left at its configuration sits beside the free space (PLAN-071).
    val rawLeft = rawVideoStatusFacts(state)?.timeLeftMs?.let { stringResource(R.string.status_time_left, formatRecordTimeLeft(it)) }
    fun line(withTimeLeft: Boolean, withRest: Boolean) = androidx.compose.ui.text.buildAnnotatedString {
        fun separator() { if (length > 0) append("  ·  ") }
        if (withRest) {
            state.timecodeDisplay?.let { tc ->
                withStyle(androidx.compose.ui.text.SpanStyle(color = muted)) { append("TC\u00A0") }
                append(tc.unbroken())
            }
            free?.let { separator(); append(it.unbroken()) }
        }
        if (withTimeLeft) rawLeft?.let { separator(); append(it.unbroken()) }
        if (withRest) battery?.let {
            separator()
            withStyle(androidx.compose.ui.text.SpanStyle(color = muted)) { append("BAT\u00A0") }
            withStyle(androidx.compose.ui.text.SpanStyle(color = batteryColor)) { append("$it%") }
        }
    }
    val oneLine = line(withTimeLeft = true, withRest = true)
    val top = line(withTimeLeft = false, withRest = true)
    val bottom = rawLeft?.let { line(withTimeLeft = true, withRest = false) }
    val style = LocalTextStyle.current.merge(TextStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = textAlign ?: TextAlign.Unspecified,
    ))
    val marquee = rememberMarqueeActive()
    val measurer = rememberTextMeasurer()
    // The caller's size reaches the text as a minimum, so a centred line stays centred in its slot.
    BoxWithConstraints(modifier.testTag("capture-status-line"), propagateMinConstraints = true) {
        fun width(text: androidx.compose.ui.text.AnnotatedString) =
            measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
        val layout = if (constraints.maxWidth == Constraints.Infinity) StatusLineLayout.ONE_LINE else statusLineLayout(
            availablePx = constraints.maxWidth,
            oneLinePx = width(oneLine),
            topPx = width(top),
            bottomPx = bottom?.let(::width),
            maxLines = maxLines,
            marquee = marquee,
        )
        when (layout) {
            StatusLineLayout.ONE_LINE -> Text(oneLine, style = style, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
            StatusLineLayout.TWO_ROWS -> Column(
                horizontalAlignment = when (textAlign) {
                    TextAlign.Center -> Alignment.CenterHorizontally
                    TextAlign.End, TextAlign.Right -> Alignment.End
                    else -> Alignment.Start
                },
            ) {
                Text(top, Modifier.testTag("capture-status-line-top"), style = style, softWrap = false, maxLines = 1)
                Text(requireNotNull(bottom), Modifier.testTag("capture-status-line-bottom"), style = style, softWrap = false, maxLines = 1)
            }
            StatusLineLayout.WRAP, StatusLineLayout.ELLIPSIS ->
                Text(oneLine, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            StatusLineLayout.MARQUEE ->
                Text(oneLine, Modifier.cineMarquee(), style = style, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

/**
 * One line under the capture controls with what the next take records: codec, bitrate, sound, the
 * time left on the storage and, when set, the anamorphic squeeze and the LOG view. Stills modes
 * only list what applies to them; free space and battery live in the top bar's status line.
 */
@Composable
internal fun StatusInfoBar(state: CameraUiState, settings: CameraSettings) {
    val video = !state.selectedMode.isStillMode()
    // Off-speed takes are silent by design.
    val silent = !settings.audioEnabled || (state.selectedMode == CaptureMode.VIDEO && settings.videoOffSpeed)
    val sdrProfile = state.activeVideoProfile
    val hevcAvailable = remember(sdrProfile?.size, sdrProfile?.fps) {
        sdrProfile != null && hevcSdrSupported(sdrProfile.size.width, sdrProfile.size.height, sdrProfile.fps)
    }
    val raw = state.selectedMode == CaptureMode.RAW_VIDEO
    val parts = buildList {
        if (raw) {
            // RAW video is uncompressed: never the encoder's bitrate, but the configuration's data rate
            // and the time the free space lasts at it (PLAN-071). Its sound is PCM, on with the audio setting.
            add("RAW10")
            val facts = rawVideoStatusFacts(state)
            facts?.let { val label = rawLocalizedLabel(it.label, it.isBlockPack && rawVideoNearLosslessActive(state, settings)); add(if (it.isCrop) stringResource(R.string.raw_video_label_crop, label) else label); add(stringResource(R.string.raw_video_rate, it.megabytesPerSecond)) }
            add(if (settings.audioEnabled) "PCM " + formatAudioRate(settings.audioSampleRateHz) else stringResource(R.string.status_audio_off))
            facts?.timeLeftMs?.let { add(stringResource(R.string.status_time_left, formatRecordTimeLeft(it))) }
        } else if (video) {
            add(when (state.selectedMode) {
                CaptureMode.LOG -> "HEVC 10-bit LOG"
                CaptureMode.HLG -> "HEVC 10-bit HLG"
                else -> resolveVideoCodec(settings.videoCodec, hevcAvailable).label
            })
            add("${settings.videoBitrateMbps} Mbps")
            add(if (silent) stringResource(R.string.status_audio_off)
                else audioOutputLabel(settings.audioOutputFormat) + " " + formatAudioRate(settings.audioSampleRateHz))
            recordTimeLeftMs(captureStatusFreeBytes(state), settings.videoBitrateMbps)?.let {
                add(stringResource(R.string.status_time_left, formatRecordTimeLeft(it)))
            }
        }
        anamorphicStatusLabel(settings.anamorphicSqueeze, settings.anamorphicOutputMode)?.let(::add)
        if (state.selectedMode == CaptureMode.LOG) {
            add(stringResource(R.string.status_view_assist, if (settings.logViewAssistEnabled) "Rec.709" else "LOG"))
        }
    }
    if (parts.isEmpty()) return
    // Two lines when the facts fit in them; otherwise one line that scrolls, or the ellipsis with the marquee off.
    FitText(
        parts.joinToString(" · "),
        color = Muted,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth().testTag("status-info-bar"),
    )
}

@Composable
internal fun RecordingOverlay(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    showStop: Boolean,
    zebra: Boolean, peaking: Boolean, histogram: Boolean,
    histogramMode: HistogramMode,
    showGrid: Boolean, gridMode: CompositionGridMode, showHorizon: Boolean,
    onToggleZebra: () -> Unit, onTogglePeaking: () -> Unit, onToggleHistogram: () -> Unit,
    onCycleHistogramMode: () -> Unit,
    onToggleGrid: () -> Unit, onCycleGridMode: () -> Unit, onToggleHorizon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMonitors by remember { mutableStateOf(false) }
    val stopRecordingDescription = stringResource(R.string.stop_recording)
    val finalizing = state.recordingFinalizing
    val finalizingLabel = stringResource(R.string.rec_state_finalizing)
    BoxWithConstraints(modifier.padding(top = 8.dp, start = 10.dp, end = 10.dp).fillMaxWidth()) {
        // A phone-width strip drops the frame size and the monitoring shortcut to keep REC, the
        // meter and stop on one row.
        val compact = windowWidthClass(maxWidth.value) == WindowWidthClass.COMPACT
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        ) {
            Row(
                Modifier.background(Panel, RoundedCornerShape(8.dp)).padding(horizontal = if (compact) 7.dp else 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (finalizing) RecordButtonState.FINALIZING.color else RecordRed))
                // The clock stops meaning anything once the take is closing, so the strip says so instead.
                Text(if (finalizing) finalizingLabel
                    else (if (state.recordingPauseStatus?.paused == true) stringResource(R.string.recording_paused) else "REC") + " " + formatDuration(state.recordingElapsedMs) +
                        state.takeLengthLimitMs?.let { " / " + formatDuration(it) }.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (!compact && state.recordingWidth != null && state.recordingHeight != null) {
                    Text(formatFrameSize(state.recordingWidth, state.recordingHeight), color = Muted, fontSize = 12.sp, maxLines = 1)
                }
            }
            val ringPercent = state.rawVideoRingPercent
            if (ringPercent != null) {
                // A RAW take records no sound: its ring buffer takes the audio meter's place (PLAN-071).
                RawRingMeter(ringPercent, state.rawVideoFramesWritten ?: 0L)
            } else {
                // Full width even on a phone: a narrower meter cut its "no signal" line short.
                AudioMeterHud(state, binder)
            }
            ThermalHudChip(recording = true)
            OutOfFrameHudChip(state)
            Spacer(Modifier.weight(1f))
            if (!compact) TopAction(CineIcon.MONITORING, stringResource(R.string.monitoring_tools)) { showMonitors = !showMonitors }
            if (showStop) {
                // Like the full chrome's record button: a closing take answers no second stop.
                Box(
                    Modifier.size(48.dp)
                        .semantics {
                            contentDescription = stopRecordingDescription
                            if (finalizing) stateDescription = finalizingLabel
                        }
                        .border(2.dp, if (finalizing) Muted else MaterialTheme.colorScheme.onSurface, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !finalizing) { binder?.capturePrimary() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        if (finalizing) Modifier.size(16.dp).clip(RoundedCornerShape(2.dp)).background(Muted)
                        else Modifier.size(16.dp).testTag("recording-stop-glyph").clip(RoundedCornerShape(2.dp)).background(RecordRed),
                    )
                    RecordBusyRing(finalizing, RecordButtonState.FINALIZING.color, "recording-stop-busy", Modifier.matchParentSize())
                }
            }
        }
        // The Adaptive policy's live warning, on every layout: CaptureStatus is hidden while a take runs.
        state.recordingWarning?.let { warning ->
            FitText(
                warning,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 3,
                modifier = Modifier.testTag("recording-warning")
                    .background(Panel, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        }
    }
    if (showMonitors) {
        Box(modifier.fillMaxWidth().padding(top = 60.dp, start = 10.dp, end = 10.dp), contentAlignment = Alignment.TopEnd) {
            MonitoringToggleGrid(
                zebra, peaking, histogram, histogramMode, showGrid, gridMode, showHorizon,
                onToggleZebra, onTogglePeaking, onToggleHistogram, onCycleHistogramMode,
                onToggleGrid, onCycleGridMode, onToggleHorizon,
            )
        }
    }
}

@Composable
internal fun AudioMeterHud(
    state: CameraUiState,
    binder: CaptureService.LocalBinder?,
    modifier: Modifier = Modifier,
    meterWidth: androidx.compose.ui.unit.Dp = 132.dp,
    meterSettings: AudioMeterSettings = state.effectiveSettings?.audioMeter ?: AudioMeterSettings(),
    nowElapsedRealtimeMs: Long? = null,
    onResetClip: () -> Unit = { binder?.resetAudioClip() },
) {
    val state = liveCameraState(state)
    if (!meterSettings.visible) return
    var clockMs by remember { androidx.compose.runtime.mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(nowElapsedRealtimeMs) {
        if (nowElapsedRealtimeMs == null) while (true) { clockMs = SystemClock.elapsedRealtime(); delay(100) }
    }
    val now = nowElapsedRealtimeMs ?: maxOf(clockMs, SystemClock.elapsedRealtime())
    val snapshot = currentAudioMeterSnapshot(state.audioLevels, state.audioMonitoringActive, now)
    val levels = snapshot?.channels.orEmpty()
    val holder = remember(meterSettings, state.audioMonitoringActive, levels.size) { AudioMeterPeakHold() }
    val displayed = levels.map { meterSettings.displayDb(it) }
    val held = if (snapshot != null) holder.observe(snapshot.capturedAtElapsedRealtimeMs, now, displayed, meterSettings.peakHoldMs)
        else { holder.clear(); emptyList() }
    val resetLabel = stringResource(R.string.audio_meter_reset_clip)
    // Every line is single and ellipsised, and the panel clips, so a narrow portrait slot never
    // lets the meter spill over the instruments next to it.
    val activeInput = state.activeAudioInput
    // The badge sits outside the clickable column, so it stays its own semantics node rather than
    // being merged into the clip-reset action.
    Box(modifier.width(meterWidth)) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("audio-meter-hud")
                .background(Panel, RoundedCornerShape(7.dp))
                .clipToBounds()
                .clickable(enabled = state.audioClipLatched, onClickLabel = resetLabel, onClick = onResetClip)
                .semantics { if (state.audioClipLatched) contentDescription = resetLabel }
                .padding(horizontal = 7.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(if (snapshot != null) "MIC" else stringResource(R.string.audio_meter_no_pcm),
                Modifier.padding(end = if (activeInput != null) 40.dp else 0.dp).testTag("audio-meter-current"),
                color = if (snapshot != null) VerifiedCyan else Muted,
                fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(audioMeterModeLabel(meterSettings.mode)), Modifier.testTag("audio-meter-mode"), color = Muted,
                fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.audioClipLatched) {
                // IN: the microphone's samples hit full scale. REC: the processed samples that are recorded did.
                val clipDescription = when {
                    state.audioInputClipLatched && state.audioRecordedClipLatched -> stringResource(R.string.audio_meter_clip_both)
                    state.audioInputClipLatched -> stringResource(R.string.audio_meter_clip_input)
                    state.audioRecordedClipLatched -> stringResource(R.string.audio_meter_clip_recorded)
                    else -> null
                }
                Text(audioClipLabel(state.audioInputClipLatched, state.audioRecordedClipLatched),
                    Modifier.testTag("audio-meter-clip").semantics { clipDescription?.let { stateDescription = it } },
                    color = RecordRed, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            val meterTrack = MaterialTheme.colorScheme.surfaceContainerHighest
            val meterLine = MaterialTheme.colorScheme.onSurface
            val meterGreen = OkGreen
            repeat(max(1, levels.size)) { index ->
                val level = levels.getOrNull(index)
                val value = displayed.getOrNull(index)
                val label = if (levels.size <= 1) "M" else if (index == 0) "L" else "R"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, color = Muted, fontSize = 12.sp, lineHeight = 12.sp, maxLines = 1)
                    val meterRed = RecordRed; val meterAmber = Amber
                    Canvas(Modifier.weight(1f).height(8.dp).testTag("audio-meter-channel-$index")) {
                        drawRect(meterTrack)
                        if (value != null) {
                            val minimum = if (meterSettings.mode == AudioMeterMode.VU) -30f else -60f
                            val maximum = if (meterSettings.mode == AudioMeterMode.VU) 6f else 0f
                            fun fraction(db: Float) = ((db - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
                            val signalColor = when {
                                (level?.peakDbfs ?: -120f) >= -3f -> meterRed
                                (level?.peakDbfs ?: -120f) >= -12f -> meterAmber
                                else -> meterGreen
                            }
                            drawRect(signalColor, size = androidx.compose.ui.geometry.Size(size.width * fraction(value), size.height))
                            if (meterSettings.mode == AudioMeterMode.PEAK_RMS) level?.rmsDbfs?.takeIf { it.isFinite() }?.let { rms ->
                                drawLine(meterLine, androidx.compose.ui.geometry.Offset(size.width * fraction(rms), 0f),
                                    androidx.compose.ui.geometry.Offset(size.width * fraction(rms), size.height), strokeWidth = 1.dp.toPx())
                            }
                            held.getOrNull(index)?.let { peak ->
                                drawLine(meterAmber, androidx.compose.ui.geometry.Offset(size.width * fraction(peak), 0f),
                                    androidx.compose.ui.geometry.Offset(size.width * fraction(peak), size.height), strokeWidth = 2.dp.toPx())
                            }
                        }
                    }
                }
                if (meterSettings.showValues) {
                    fun number(value: Float?) = value?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "—"
                    val valueText = when (meterSettings.mode) {
                        AudioMeterMode.PEAK_RMS -> stringResource(R.string.audio_meter_peak_rms_values, number(value), number(level?.rmsDbfs?.takeIf { it.isFinite() }))
                        AudioMeterMode.VU -> stringResource(R.string.audio_meter_vu_value, number(value))
                        AudioMeterMode.PPM -> stringResource(R.string.audio_meter_ppm_value, number(value))
                    }
                    Text(valueText, Modifier.testTag("audio-meter-value-$index"), color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (value != null && meterSettings.peakHoldMs > 0) Text(stringResource(R.string.audio_meter_hold_value, number(held.getOrNull(index))),
                        Modifier.testTag("audio-meter-hold-$index"), color = Amber, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        activeInput?.let { AudioInputBadge(it, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 7.dp)) }
    }
}

internal fun audioMeterModeLabel(mode: AudioMeterMode): Int = when (mode) {
    AudioMeterMode.PEAK_RMS -> R.string.audio_meter_peak_rms
    AudioMeterMode.VU -> R.string.audio_meter_vu
    AudioMeterMode.PPM -> R.string.audio_meter_ppm
}

@Composable
internal fun CaptureStatus(state: CameraUiState, modifier: Modifier = Modifier, chip: Boolean = false) {
    var noticeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(state.message, state.messageTransient) {
        if (state.messageTransient) {
            noticeVisible = true
            delay(2_500)
            noticeVisible = false
        } else {
            noticeVisible = true
        }
    }
    val controlNotice = if (state.exposureControlUnavailable || state.effectiveSettings?.whiteBalance?.let { it != state.requestedWhiteBalance } == true)
        stringResource(R.string.pro_capture_notice) else null
    val baseStatus = when {
        state.phase == CameraUiPhase.RECORDING && state.recordingWidth != null && state.recordingHeight != null ->
            stringResource(R.string.recording_status, formatFrameSize(state.recordingWidth, state.recordingHeight), state.targetFps,
                formatDuration(state.recordingElapsedMs), formatBytes(state.availableStorageBytes))
        // The error sheet already states a failure; repeating it here would show it twice.
        state.phase == CameraUiPhase.ERROR -> null
        else -> state.message
    }?.takeIf { !state.messageTransient || noticeVisible }
    val recoveryNotice = state.stillRecovery?.takeIf { it.discardedGroups > 0 || it.unresolvedGroups > 0 }?.let {
        stringResource(R.string.still_recovery_notice, it.discardedGroups, it.unresolvedGroups)
    }
    val recordingRecoveryNotice = state.recordingRecovery?.takeIf { it.discardedGroups > 0 || it.unresolvedGroups > 0 }?.let {
        stringResource(R.string.recording_recovery_notice, it.discardedGroups, it.unresolvedGroups)
    }
    val status = listOfNotNull(recoveryNotice, recordingRecoveryNotice, baseStatus, controlNotice).joinToString(" · ").takeIf { it.isNotBlank() } ?: return
    FitText(
        status,
        color = if (state.errorCode == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        maxLines = 2,
        textAlign = if (chip) TextAlign.Center else null,
        modifier = if (chip) modifier
            .testTag("capture-status-chip")
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f).chromePanel(), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
        else modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

/**
 * INT, USB, BT or WIRED: the input the platform actually routed. Amber while the route is not yet
 * confirmed, or after the requested input was lost and the take fell back or went silent.
 */
@Composable
private fun AudioInputBadge(input: com.librestatic.opencinecam.media.audio.ActiveAudioInput, modifier: Modifier = Modifier) {
    val kind = input.type?.let { com.librestatic.opencinecam.media.audio.audioInputKind(it) }
    val healthy = input.confirmed && !input.fallback && !input.silenced
    val color = if (healthy) VerifiedCyan else Amber
    val type = kind?.let { stringResource(audioInputTypeLabel(it)) } ?: stringResource(R.string.audio_input_type_other)
    val status = when {
        input.silenced -> stringResource(R.string.audio_input_badge_silenced)
        input.fallback -> stringResource(R.string.audio_input_badge_fallback)
        !input.confirmed -> stringResource(R.string.audio_input_badge_unconfirmed)
        else -> null
    }
    val description = listOfNotNull(type, input.label?.takeIf { it.isNotBlank() && kind != com.librestatic.opencinecam.media.audio.AudioInputKind.BUILT_IN }, status)
        .joinToString(", ")
    Text(
        kind?.badge ?: "—",
        modifier.testTag("audio-input-badge")
            .semantics { contentDescription = description }
            .border(1.dp, color, RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp),
        color = color, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    )
}
