/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.SmpteTimecode
import com.librestatic.opencinecam.camera.TimecodeRate

/** One visible slate cell. Values are display text only; nothing here feeds take numbering. */
internal data class SubjectSlateLine(val field: SubjectSlateField, val value: String)

/** Shown for an empty slate text field, so a blank cell is not mistaken for a missing one. */
internal const val SLATE_EMPTY_VALUE = "—"

/** Shown while no take timecode is published (idle, disabled, or before the first muxed frame). */
internal const val SLATE_TIMECODE_IDLE = "--:--:--:--"

/**
 * The service publishes the take timecode about twice a second. The slate extrapolates between
 * those updates at the timecode rate for a smooth display, but never more than this far past the
 * last update, so a stalled service freezes the slate instead of inventing a running count.
 */
internal const val SLATE_TIMECODE_EXTRAPOLATION_CAP_NS = 1_000_000_000L

/**
 * Visible slate cells in canonical [SubjectSlateField] order. The values come from the operator's
 * [ProductionSlateSettings]; the take number is shown as stored and is never advanced here.
 */
internal fun subjectSlateLines(slate: ProductionSlateSettings, fields: Set<SubjectSlateField>, timecode: String): List<SubjectSlateLine> =
    SubjectSlateField.entries.filter { it in fields }.map { field ->
        SubjectSlateLine(field, when (field) {
            SubjectSlateField.PROJECT -> slate.project.slateText()
            SubjectSlateField.SCENE -> slate.scene.slateText()
            SubjectSlateField.TAKE -> slate.takeNumber.toString()
            SubjectSlateField.CAMERA -> slate.camera.slateText()
            SubjectSlateField.REEL -> slate.reel.slateText()
            SubjectSlateField.TIMECODE -> timecode
        })
    }

private fun String.slateText(): String = trim().ifEmpty { SLATE_EMPTY_VALUE }

/** Parses the canonical HH:MM:SS:FF (or HH:MM:SS;FF drop-frame) label the service publishes. */
internal fun parseSmpteLabel(label: String): SmpteTimecode? {
    val match = Regex("([0-9]{2}):([0-9]{2}):([0-9]{2})([:;])([0-9]{2})").matchEntire(label) ?: return null
    val (hours, minutes, seconds, separator, frames) = match.destructured
    return runCatching { SmpteTimecode(hours.toInt(), minutes.toInt(), seconds.toInt(), frames.toInt(), separator == ";") }.getOrNull()
}

/**
 * Display label for the slate's running timecode.
 *
 * Source: [CameraUiState.timecodeDisplay], which the service publishes only while recording (the
 * take's last muxed frame for RECORD_RUN/REGEN, the receipt clock for FREE_RUN). The tracker does
 * not expose the idle free-run or next-take value, so the idle slate shows [SLATE_TIMECODE_IDLE]
 * rather than inventing one. While [running], the label advances at [rate] for [sinceUpdateNs]
 * (capped). A label that does not parse at [rate] (for example after a mid-take rate edit, which
 * only applies to the next take) is shown exactly as published, without extrapolation.
 */
internal fun slateTimecodeLabel(published: String?, rate: TimecodeRate?, recording: Boolean, running: Boolean, sinceUpdateNs: Long): String {
    if (!recording || published == null) return SLATE_TIMECODE_IDLE
    if (!running || rate == null) return published
    val start = parseSmpteLabel(published) ?: return published
    val total = runCatching { start.toTotalFrames(rate) }.getOrNull() ?: return published
    val advance = rate.framesForElapsedNs(sinceUpdateNs.coerceIn(0L, SLATE_TIMECODE_EXTRAPOLATION_CAP_NS))
    return SmpteTimecode.fromTotalFrames(total + advance, rate).format()
}

/** Rows of the clapperboard layout: project; scene and take; camera and reel; timecode. */
internal fun subjectSlateRows(lines: List<SubjectSlateLine>): List<List<SubjectSlateLine>> {
    val byField = lines.associateBy { it.field }
    return listOf(
        listOf(SubjectSlateField.PROJECT),
        listOf(SubjectSlateField.SCENE, SubjectSlateField.TAKE),
        listOf(SubjectSlateField.CAMERA, SubjectSlateField.REEL),
        listOf(SubjectSlateField.TIMECODE),
    ).map { row -> row.mapNotNull(byField::get) }.filter { it.isNotEmpty() }
}

/** Rate used only to extrapolate the slate display between service updates; null when timecode is off. */
internal fun CameraSettings.slateTimecodeRate(): TimecodeRate? =
    if (!timecodeEnabled) null else runCatching { TimecodeRate(timecodeNominalFps, timecodeDropFrame) }.getOrNull()
