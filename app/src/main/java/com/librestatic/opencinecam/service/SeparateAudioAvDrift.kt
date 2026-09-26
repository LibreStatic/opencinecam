/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.PcmSourceTimingReport
import com.librestatic.opencinecam.camera.pcmFrameDurationNs
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.media.audio.AvClockDiagnostics
import com.librestatic.opencinecam.media.audio.AvDiagnosticSnapshot

/**
 * End-of-take A/V clock drift of a separate WAV/FLAC take. The video frames carry the camera's
 * BOOTTIME sensor clock and the sidecar starts on the same BOOTTIME anchor, so what can still move
 * the two apart is the microphone's sample clock: over the AudioTimestamp observations, BOOTTIME
 * elapsed (the video clock) is compared with the PCM frames the microphone delivered (the file's
 * audio clock). driftUs > 0 means the audio file runs short of the picture by that much.
 *
 * ADAPTIVE only: the result is report-only and never stops or fails a take. Returns null when the
 * comparison has no meaning (camera not on BOOTTIME, or fewer than two distinct timestamps).
 */
internal fun separateAudioAvDrift(cameraRealtime: Boolean, timing: PcmSourceTimingReport?, correlationId: String): AvDiagnosticSnapshot? {
    if (!cameraRealtime || timing == null || timing.timestampObservations < 2) return null
    val firstFrame = timing.firstTimestampFrame ?: return null
    val lastFrame = timing.lastTimestampFrame ?: return null
    val firstNs = timing.firstTimestampNs ?: return null
    val lastNs = timing.lastTimestampNs ?: return null
    if (lastFrame <= firstFrame || lastNs <= firstNs) return null
    val videoClockUs = (lastNs - firstNs) / 1_000
    val audioClockUs = pcmFrameDurationNs(lastFrame - firstFrame, timing.sampleRateHz) / 1_000
    return AvClockDiagnostics(PolicyMode.ADAPTIVE, correlationId = correlationId).use { diagnostics ->
        diagnostics.observe(0, 0, 0)
        diagnostics.observe(videoClockUs, audioClockUs, videoClockUs)
    }
}

/** One log line for the take; the snapshot itself stays in memory for the binder. */
internal fun separateAudioAvDriftLog(snapshot: AvDiagnosticSnapshot): String =
    "Separate audio A/V drift ${snapshot.driftUs} us over ${snapshot.videoPtsUs} us " +
        "(video BOOTTIME ${snapshot.videoPtsUs} us, audio sample clock ${snapshot.audioPtsUs} us, action ${snapshot.action})"
