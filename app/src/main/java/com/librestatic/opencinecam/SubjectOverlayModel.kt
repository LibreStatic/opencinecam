/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

/**
 * Pure models behind the subject tally, countdown and self-monitor overlays (OCC-PLAN-068 U1/U2).
 * Everything derives from the immutable service status the subject already receives (ADR-0031).
 */
internal enum class SubjectTally { NONE, PENDING, RECORDING }

/**
 * Red only while the service reports an unpaused take. Amber covers a countdown, a capture being
 * prepared, a paused take and finalization, which is "saving" and never "saved". A saved or idle
 * camera shows nothing, so a stale REC can never outlive the service state.
 */
internal fun subjectTally(state: CameraUiState): SubjectTally = when {
    state.recordingFinalizing -> SubjectTally.PENDING
    state.phase == CameraUiPhase.RECORDING && state.recordingPauseStatus?.paused == true -> SubjectTally.PENDING
    state.phase == CameraUiPhase.RECORDING -> SubjectTally.RECORDING
    state.countdownSeconds > 0 || state.phase == CameraUiPhase.CAPTURING -> SubjectTally.PENDING
    else -> SubjectTally.NONE
}

internal fun subjectShowsGiantCountdown(state: CameraUiState, settings: SubjectDisplaySettings): Boolean =
    settings.giantCountdown && state.countdownSeconds > 0

/** The small in-flow badge yields to the giant numeral so the subject never sees two counts. */
internal fun subjectShowsCountdownBadge(state: CameraUiState, settings: SubjectDisplaySettings): Boolean =
    !settings.giantCountdown && state.countdownSeconds > 0

/** Stream that feeds the subject GPU output, chosen the same way as the operator viewfinder. */
internal fun subjectPreviewStreamSize(state: CameraUiState): Pair<Int, Int>? {
    val descriptor = state.descriptor ?: return null
    return when (state.selectedMode) {
        CaptureMode.LOG -> (state.activeLogProfile?.size ?: descriptor.preferredLogProfile?.size)?.let { it.width to it.height }
        CaptureMode.VIDEO -> state.targetVideoWidth to state.targetVideoHeight
        // The exterior preview is routed only for VIDEO and LOG; other modes show a message.
        else -> null
    }
}

/**
 * Where the recorded frame sits inside the subject surface. The subject GPU output aspect-fits the
 * whole source after de-squeeze and the encoder renders that same whole source, so this rect is
 * exactly the recorded picture and everything outside it is letterbox. Mirroring is symmetric and
 * does not move it. Null when the stream or the surface is not known yet.
 */
internal fun subjectRecordedFrame(
    containerWidth: Float,
    containerHeight: Float,
    streamWidth: Int,
    streamHeight: Int,
    squeezeFactor: Float,
    sensorOrientationDegrees: Int,
    displayRotationDegrees: Int,
): PreviewViewport? {
    if (containerWidth <= 0f || containerHeight <= 0f || streamWidth <= 0 || streamHeight <= 0) return null
    if (!squeezeFactor.isFinite() || squeezeFactor <= 0f) return null
    val ratio = previewDisplayRatio(streamWidth, streamHeight, squeezeFactor, sensorOrientationDegrees, displayRotationDegrees)
    return fittedPreviewViewport(containerWidth, containerHeight, ratio)
}

/** Guide geometry in container pixels: straight segments plus centred safe-area rectangles. */
internal data class SubjectGuideShape(
    val lines: List<Pair<Pair<Float, Float>, Pair<Float, Float>>> = emptyList(),
    val rects: List<PreviewViewport> = emptyList(),
)

/** Broadcast action-safe and title-safe proportions of the recorded frame. */
internal val SUBJECT_SAFE_AREA_FRACTIONS = listOf(0.9f, 0.8f)

internal fun subjectGuideShape(frame: PreviewViewport, guide: SubjectPreviewGuide): SubjectGuideShape = when (guide) {
    SubjectPreviewGuide.NONE -> SubjectGuideShape()
    SubjectPreviewGuide.THIRDS -> SubjectGuideShape(lines = listOf(1f / 3f, 2f / 3f).flatMap { fraction ->
        val x = frame.left + frame.width * fraction
        val y = frame.top + frame.height * fraction
        listOf((x to frame.top) to (x to frame.bottom), (frame.left to y) to (frame.right to y))
    })
    SubjectPreviewGuide.SAFE_AREA -> SubjectGuideShape(rects = SUBJECT_SAFE_AREA_FRACTIONS.map { fraction ->
        val width = frame.width * fraction
        val height = frame.height * fraction
        PreviewViewport(frame.left + (frame.width - width) / 2f, frame.top + (frame.height - height) / 2f, width, height)
    })
}

/** Peak dBFS mapped onto a -60..0 dB bar; null or non-finite levels draw an empty bar. */
internal fun subjectMeterFraction(peakDbfs: Float?): Float =
    peakDbfs?.takeIf { it.isFinite() }?.let { ((it + 60f) / 60f).coerceIn(0f, 1f) } ?: 0f
