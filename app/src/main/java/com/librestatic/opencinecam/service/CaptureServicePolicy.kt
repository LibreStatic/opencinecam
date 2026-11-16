/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.CameraUiPhase
import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.TimeLapseLimitMode
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.core.model.StableFailure
import kotlinx.coroutines.flow.update

// Part of CaptureService: the recording runtime policy and the time-lapse and take-length auto stops.
// These are extensions so the service's state stays in one object; they keep the threading rules of its members.

/**
 * PLAN-072: free bytes where the running take writes, or where the next encoded take would when idle; any thread,
 * never blocking on a drive. A drive's figure is its last sample ([driveFreeSpace]: null before the first one or
 * when the provider gave none, so the policy reuses the last good one), and the call starts a fresh one.
 */
internal fun CaptureService.recordingVolumeFreeBytes(): Long? =
    cachedDestinationFreeBytes(freeSpaceDestination(takeDestination?.destination, nextTakeDestination.destination))

/**
 * Live thermal and storage policy (ADR-0016) for the running take. A stop verdict ends the take
 * with its reason; a notice stays on screen. A one-off unreadable free-space sample reuses the
 * last good one, so a StatFs hiccup cannot stop a take in Strict.
 */
internal fun CaptureService.enforceRuntimePolicy(free: Long?) {
    if (pendingStopReason != null) return
    if (free != null) lastKnownFreeBytes = free
    val thermal = runCatching { getSystemService(android.os.PowerManager::class.java).currentThermalStatus }.getOrNull()
    val verdict = evaluateRuntimePolicy(activeRecordingPolicy, thermal, free ?: lastKnownFreeBytes, takeProjectedBytesPerMinute, takeLengthLimitMs, takeStopHeadroomBytes)
    verdict.stop?.let { localBinder.stopRecording(it); return }
    val drift = avDiagnostics?.let { diagnostics ->
        val sidecar = audioSidecarRecorder ?: return@let null
        avClockSample(sidecar.captureClock?.cameraRealtime == true, sidecar.liveCaptureTiming())
            ?.let { (videoClockUs, audioClockUs) -> avDriftVerdict(diagnostics.observe(videoClockUs, audioClockUs, videoClockUs)) }
    }
    drift?.stop?.let { localBinder.stopRecording(it); return }
    runtimeNotice = verdict.notice
    avNotice = drift?.notice ?: avNotice
    publishRecordingWarning()
}

internal fun CaptureService.publishRecordingWarning() {
    val text = recordingWarningText()
    if (cameraState.value.recordingWarning != text) cameraState.update { it.copy(recordingWarning = text) }
}

internal fun CaptureService.recordingWarningText(): String? {
    return recordingWarningLine(admissionWarning, takeSoftwareEncoder?.let { getString(R.string.software_video_encoder_warning, it) },
        getString(R.string.video_codec_fallback_warning).takeIf { takeCodecFallback },
        listOfNotNull(runtimeNotice, avNotice, cadenceNotice).map { getString(runtimeNoticeText(it)) } +
            listOfNotNull(destinationFallbackNotice(takeDestination?.fallback,
                getString(R.string.recording_drive_fallback_permission_lost), getString(R.string.recording_drive_fallback_starting),
                getString(R.string.recording_drive_fallback_renamed))))
}

internal fun CaptureService.runtimeNoticeText(notice: RuntimeNotice): Int = when (notice) {
    RuntimeNotice.MONITORING_REDUCED -> R.string.runtime_notice_monitoring_reduced
    RuntimeNotice.STATE_UNKNOWN -> R.string.runtime_notice_state_unknown
    RuntimeNotice.AV_DRIFT -> R.string.runtime_notice_av_drift
    RuntimeNotice.CADENCE -> R.string.runtime_notice_cadence
}

internal fun CaptureService.stopReasonText(failure: StableFailure): String = when (takeStopReason(failure)) {
    TakeStopReason.THERMAL -> getString(R.string.stop_reason_thermal)
    TakeStopReason.STORAGE -> getString(R.string.stop_reason_storage)
    TakeStopReason.STATE_UNKNOWN -> getString(R.string.stop_reason_state_unknown)
    TakeStopReason.AV_DRIFT -> getString(R.string.stop_reason_av_drift)
    TakeStopReason.CADENCE -> getString(R.string.stop_reason_cadence)
    TakeStopReason.AUDIO_INPUT_LOST -> getString(R.string.stop_reason_audio_input)
    TakeStopReason.DRIVE_LOST -> getString(R.string.stop_reason_drive_disconnected)
    TakeStopReason.OTHER -> getString(R.string.stop_reason_other)
}

internal fun CaptureService.scheduleTimelapseAutoStop() {
    cancelTimelapseAutoStop()
    if (cameraState.value.selectedMode != CaptureMode.TIME_LAPSE || cameraState.value.recordingPauseStatus?.paused == true) return
    val delayMs = when (cameraState.value.timelapseLimitMode) {
        TimeLapseLimitMode.UNLIMITED -> return
        TimeLapseLimitMode.FRAME_COUNT -> return // Stop on actual submitted-frame progress, not elapsed-time estimates.
        TimeLapseLimitMode.DURATION -> cameraState.value.timelapseDurationMs - activeRecordingElapsedMs()
    }.coerceAtLeast(1L)
    val runnable = Runnable {
        if (cameraState.value.phase == CameraUiPhase.RECORDING &&
            cameraState.value.selectedMode == CaptureMode.TIME_LAPSE
        ) {
            localBinder.stopRecording()
        }
    }
    timelapseAutoStopRunnable = runnable
    mainHandler.postDelayed(runnable, delayMs)
}

internal fun CaptureService.cancelTimelapseAutoStop() {
    timelapseAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
    timelapseAutoStopRunnable = null
}

/** Stops a take bounded to a capture length when it reaches it, with no reason, so it ends as an ordinary save. */
internal fun CaptureService.scheduleTakeLengthAutoStop() {
    cancelTakeLengthAutoStop()
    val limitMs = takeLengthLimitMs ?: return
    if (cameraState.value.recordingPauseStatus?.paused == true) return
    val runnable = Runnable {
        if (cameraState.value.phase == CameraUiPhase.RECORDING && takeLengthLimitMs == limitMs) localBinder.stopRecording()
    }
    takeLengthAutoStopRunnable = runnable
    mainHandler.postDelayed(runnable, (limitMs - activeRecordingElapsedMs()).coerceAtLeast(1L))
}

internal fun CaptureService.cancelTakeLengthAutoStop() {
    takeLengthAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
    takeLengthAutoStopRunnable = null
}
