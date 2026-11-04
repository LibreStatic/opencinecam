/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import com.librestatic.opencinecam.ui.viewfinder.chromePanel
import kotlin.math.roundToLong

/*
 * OCC-PLAN-071 slice F1: the RAW video configuration chooser (capture panel and Settings row), the
 * capacity it reads off the free storage, and the ring meter beside the REC timer.
 */

/** A RAW take's ring occupancy as the REC readout colours it: warning from 50 %, danger from 80 % (the take stops at 90). */
internal enum class RawRingLevel { NORMAL, WARNING, DANGER }

internal fun rawRingLevel(percent: Int): RawRingLevel = when {
    percent >= 80 -> RawRingLevel.DANGER
    percent >= 50 -> RawRingLevel.WARNING
    else -> RawRingLevel.NORMAL
}

/**
 * How long the free storage lasts at [bytesPerSecond], in milliseconds; null when either is unknown.
 * It rounds down, like [recordTimeLeftMs], so the readout never promises more than the card holds.
 */
internal fun rawRecordTimeLeftMs(freeBytes: Long?, bytesPerSecond: Long): Long? =
    if (freeBytes == null || freeBytes <= 0L || bytesPerSecond <= 0L) null
    else if (freeBytes <= Long.MAX_VALUE / 1_000L) freeBytes * 1_000L / bytesPerSecond
    else (freeBytes / bytesPerSecond).let { seconds -> if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L }

/** Whole minutes of RAW the free storage holds at [bytesPerSecond]; null when unknown. */
internal fun rawRecordMinutesLeft(freeBytes: Long?, bytesPerSecond: Long): Long? =
    rawRecordTimeLeftMs(freeBytes, bytesPerSecond)?.let { it / 60_000L }

/** The data rate in whole decimal megabytes per second, as the qualification report states it. */
internal fun rawRateMegabytes(option: RawVideoConfigOption): Long = option.megabytesPerSecond.roundToLong()

/**
 * The choice the chooser marks: the persisted key when it still names one of [options], else the
 * empty key (Automatic), which is also what the service falls back to for a stale key.
 */
internal fun rawVideoChosenKey(settingsKey: String, options: List<RawVideoConfigOption>): String =
    settingsKey.takeIf { key -> key.isNotEmpty() && options.any { it.key == key } }.orEmpty()

/**
 * The configuration the session runs: the service's resolved key, or the first (highest rate) option
 * that is neither risky nor reduced-rate, since only an explicit choice runs one of those.
 */
internal fun rawVideoActiveOption(state: CameraUiState): RawVideoConfigOption? =
    state.rawVideoOptions.firstOrNull { it.key == state.rawVideoSelectedKey }
        ?: state.rawVideoOptions.firstOrNull { !it.isRisky && !it.isReducedRate }

/** RAW video runs a risky configuration (chosen by hand): the capture chrome warns about it. */
internal fun rawVideoActiveRisky(state: CameraUiState): Boolean =
    state.selectedMode == CaptureMode.RAW_VIDEO && rawVideoActiveOption(state)?.isRisky == true

/** The configuration can change only between takes, like every structural setting. */
internal fun rawVideoConfigChangeAllowed(state: CameraUiState): Boolean =
    !state.structuralSettingsFrozen && state.phase != CameraUiPhase.CAPTURING

/** What the capture status line says about a RAW video configuration; the line adds the crop marker from [isCrop]. */
internal data class RawStatusFacts(val label: String, val isCrop: Boolean, val megabytesPerSecond: Long, val timeLeftMs: Long?)

/**
 * The status line's RAW facts: the configuration in use, its uncompressed data rate in MB/s and the
 * time the free space lasts at its capacity rate (a compressed option assumes its conservative ratio).
 * Null outside RAW video or before a configuration is known; never the encoded-video bitrate.
 */
internal fun rawVideoStatusFacts(state: CameraUiState): RawStatusFacts? {
    if (state.selectedMode != CaptureMode.RAW_VIDEO) return null
    val option = rawVideoActiveOption(state) ?: return null
    return RawStatusFacts(option.label, option.isCrop, rawRateMegabytes(option),
        rawRecordTimeLeftMs(state.availableStorageBytes, option.capacityBytesPerSecond))
}

/**
 * "468 MB/s · 3 h 24 min left", or only the rate when the free space is unknown; a reduced-rate
 * option leads with the rate it reaches ("Reaches 51.9 fps · 468 MB/s · …").
 */
@Composable
internal fun rawOptionDetail(option: RawVideoConfigOption, freeBytes: Long?): String {
    val rate = stringResource(R.string.raw_video_rate, rawRateMegabytes(option))
    // A compressed option's capacity assumes a conservative ratio; its rate stays the uncompressed one it qualified at.
    val left = rawRecordTimeLeftMs(freeBytes, option.capacityBytesPerSecond)
    val detail = if (left == null) rate else stringResource(R.string.raw_video_rate_and_time, rate, formatRecordTimeLeft(left))
    return option.achievedFps?.let { stringResource(R.string.raw_video_reduced_detail, it, detail) } ?: detail
}

/** The localized word that ends a compressed configuration's label ("4080×3060 · 30 fps · Compressed"). */
@Composable
internal fun rawCompressedWord(): String = stringResource(R.string.raw_video_label_compressed)

/** [label] as the UI shows it: a compressed configuration's suffix in the user's language. */
@Composable
internal fun rawLocalizedLabel(label: String): String = rawConfigLabelLocalized(label, rawCompressedWord())

/** The option's label, with the crop marker for a centre crop ("1920×1080 · 60 fps · crop"). */
@Composable
internal fun rawOptionLabel(option: RawVideoConfigOption): String = rawLocalizedLabel(option.label).let { label ->
    if (option.isCrop) stringResource(R.string.raw_video_label_crop, label) else label
}

/** [rawOptionLabel] with the rate a reduced-rate option reaches ("4080×3060 · 60 fps · reaches 51.9 fps"). */
@Composable
internal fun rawOptionChoiceLabel(option: RawVideoConfigOption): String = rawOptionLabel(option).let { label ->
    option.achievedFps?.let { stringResource(R.string.raw_video_reduced_label, label, it) } ?: label
}

/** The spoken warning of a risky [option] ("4000×3000 · 60 fps, risky rate"); null when it is not risky. */
@Composable
internal fun rawOptionWarning(option: RawVideoConfigOption): String? =
    if (option.isRisky) stringResource(R.string.raw_video_risky_label, rawOptionLabel(option)) else null

/**
 * A risky configuration's burst estimate as the warnings say it: whole minutes from a minute on
 * (rounded, so 90 s reads "2 min"), seconds under one. The pair is the number and whether it is minutes.
 */
internal fun rawBurstDurationParts(seconds: Long): Pair<Long, Boolean> {
    val clamped = seconds.coerceAtLeast(0L)
    return if (clamped < 60L) clamped to false else Math.round(clamped / 60.0) to true
}

/** "1 min" or "45 s", from [rawBurstDurationParts]. */
@Composable
internal fun rawBurstDuration(seconds: Long): String {
    val (value, minutes) = rawBurstDurationParts(seconds)
    return stringResource(if (minutes) R.string.raw_video_burst_minutes else R.string.raw_video_burst_seconds, value.toInt())
}

/**
 * Placed like the lens lock's crop notice, in the instrument stack under the zoom: RAW video runs a
 * risky configuration. With its [burstSeconds] known it says when frames may start to drop instead
 * of the generic hazards: the notice has two lines, and both texts do not fit in them.
 */
@Composable
internal fun RawVideoRiskyNotice(modifier: Modifier = Modifier, burstSeconds: Long? = null) =
    InstrumentNotice(
        if (burstSeconds == null) stringResource(R.string.raw_video_risky_notice)
        else stringResource(R.string.raw_video_risky_notice_burst, rawBurstDuration(burstSeconds)),
        CineIcon.WARNING, LocalCineColors.current.pending, "raw-video-risky-notice", modifier,
    )

/**
 * The capture panel behind RES and FPS in RAW video: Automatic, then the full-sensor and the crop
 * configurations, each tile its size and rate over its data rate and the time left at it.
 */
@Composable
internal fun RawVideoConfigDial(
    state: CameraUiState,
    settings: CameraSettings,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    val options = state.rawVideoOptions
    val chosen = rawVideoChosenKey(settings.rawVideoConfig, options)
    val enabled = rawVideoConfigChangeAllowed(state)
    val free = state.availableStorageBytes
    PanelColumn(Modifier.testTag("raw-video-config-panel")) {
        PanelHeader(stringResource(R.string.raw_video_config_title), onClose)
        val auto = options.firstOrNull { it.key == state.rawVideoAutoKey }
        ChoiceGrid(listOf(""), minTileWidth = 150.dp, maxColumns = 1) { _, modifier ->
            // Automatic never resolves to a risky or reduced-rate configuration; with only such ones it resolves to none.
            ChoiceTile(stringResource(R.string.raw_video_automatic), chosen.isEmpty(), modifier.testTag("raw-video-config-auto"),
                enabled = enabled, detail = auto?.let { rawOptionLabel(it) } ?: stringResource(R.string.raw_video_automatic_detail)) {
                onSelect(""); onClose()
            }
        }
        val (crops, full) = options.partition { it.isCrop }
        listOf(R.string.raw_video_group_full to full, R.string.raw_video_group_crop to crops).forEach { (title, group) ->
            if (group.isEmpty()) return@forEach
            PanelSectionLabel(stringResource(title))
            ChoiceGrid(group, minTileWidth = 150.dp, maxColumns = 3) { option, modifier ->
                ChoiceTile(rawLocalizedLabel(option.label), option.key == chosen, modifier.testTag("raw-video-config-${option.key}"), enabled = enabled,
                    detail = rawOptionDetail(option, free), warning = rawOptionWarning(option)) {
                    onSelect(option.key); onClose()
                }
            }
        }
        if (options.any { it.isRisky }) {
            PanelNote(stringResource(R.string.raw_video_config_risk_hint), Modifier.testTag("raw-video-config-risk-hint"))
        }
        if (crops.isNotEmpty()) PanelNote(stringResource(R.string.raw_video_crop_note), Modifier.testTag("raw-video-crop-note"))
        if (!enabled) PanelNote(stringResource(R.string.raw_video_config_frozen), Modifier.testTag("raw-video-config-frozen"))
    }
}

/**
 * Settings > Capture: the same choice as the capture panel, with the time the free storage holds
 * at the configuration in use. Without a qualified configuration it explains why; a candidate is
 * qualified by choosing RAW video in the mode selector, a camera judged before again here ([onRequalify]).
 */
@Composable
internal fun RawVideoSettings(state: CameraUiState, settings: CameraSettings, onSelect: ((String) -> Unit)?, onRequalify: (() -> Unit)? = null) {
    val gate = state.modeGates[CaptureMode.RAW_VIDEO]
    val title = stringResource(R.string.raw_video_config_title)
    Column(Modifier.fillMaxWidth().testTag("settings-raw-video"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (gate == ModeGateState.AVAILABLE && state.rawVideoOptions.isNotEmpty()) {
            val options = state.rawVideoOptions
            val active = rawVideoActiveOption(state)
            val summary = active?.let { option ->
                stringResource(R.string.raw_video_settings_active, rawOptionLabel(option), rawOptionDetail(option, state.availableStorageBytes))
            }
            SettingsOptionRow(
                title = title,
                summary = summary,
                choices = listOf("") + options.map { it.key },
                selected = rawVideoChosenKey(settings.rawVideoConfig, options),
                label = { key -> options.firstOrNull { it.key == key }?.let { rawOptionChoiceLabel(it) } ?: stringResource(R.string.raw_video_automatic) },
                tag = { key -> "raw-video-option-${key.ifEmpty { "auto" }}" },
                onSelect = { key -> onSelect?.invoke(key) },
                enabled = onSelect != null && rawVideoConfigChangeAllowed(state),
                choiceWarning = { key -> options.firstOrNull { it.key == key }?.let { rawOptionWarning(it) } },
            )
            if (options.any { it.isRisky }) {
                Text(stringResource(R.string.raw_video_config_risk_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.testTag("settings-raw-video-risk-hint"))
            }
        } else {
            val reason = when (gate) {
                ModeGateState.UNSUPPORTED -> R.string.raw_video_unsupported
                ModeGateState.FAILED -> R.string.raw_video_failed
                else -> R.string.raw_video_not_qualified_summary
            }
            SettingsRowLabel(title, stringResource(reason))
            if (gate == ModeGateState.CANDIDATE) {
                Text(stringResource(R.string.raw_video_qualify_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.testTag("settings-raw-video-hint"))
            }
        }
        if (onRequalify != null && rawVideoRequalifyOffered(gate)) {
            OutlinedButton(onClick = onRequalify, enabled = rawVideoConfigChangeAllowed(state),
                modifier = Modifier.padding(top = 4.dp).testTag("settings-raw-video-requalify")) {
                Text(stringResource(R.string.raw_video_requalify))
            }
            Text(stringResource(R.string.raw_video_requalify_note), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.testTag("settings-raw-video-requalify-note"))
        }
        SettingsHelp(stringResource(R.string.raw_video_config_help_summary), stringResource(R.string.raw_video_config_help), tag = "raw-video-help")
    }
}

/**
 * The RAW take's buffer beside the REC timer: a short fill bar, its percentage and the frames
 * written. Amber from 50 %, red from 80 %; the take stops cleanly at 90 % (ADR-0019).
 */
@Composable
internal fun RawRingMeter(percent: Int, framesWritten: Long, modifier: Modifier = Modifier) {
    val clamped = percent.coerceIn(0, 100)
    val tint = when (rawRingLevel(clamped)) {
        RawRingLevel.NORMAL -> MaterialTheme.colorScheme.onSurface
        RawRingLevel.WARNING -> LocalCineColors.current.pending
        RawRingLevel.DANGER -> MaterialTheme.colorScheme.error
    }
    val description = stringResource(R.string.raw_video_ring_description, clamped, framesWritten)
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f).chromePanel(), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("raw-ring-meter"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(28.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(clamped / 100f).background(tint))
        }
        Text(stringResource(R.string.raw_video_ring, clamped), color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(stringResource(R.string.raw_video_frames, framesWritten), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp, maxLines = 1)
    }
}
