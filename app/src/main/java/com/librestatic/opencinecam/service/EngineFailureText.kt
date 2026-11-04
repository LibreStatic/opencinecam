/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam.service

import androidx.annotation.StringRes
import com.librestatic.opencinecam.R

/**
 * The operator's notice for a camera engine failure. [text] is the localized string; [detail] says
 * whether the engine's own message carries a platform exception or a reason worth appending. The
 * other engine messages are fixed English sentences the localized text already says.
 */
internal data class EngineFailureNotice(@StringRes val text: Int, val detail: Boolean)

private val exactNotices: Map<String, Int> = mapOf(
    "camera-permission-denied" to R.string.engine_failure_camera_permission,
    "camera-disconnected" to R.string.engine_failure_camera_disconnected,
    "camera-open-exception" to R.string.engine_failure_camera_open,
    "camera-owner-retirement-failed" to R.string.engine_failure_retiring,
    "gpu-retirement-failed" to R.string.engine_failure_retiring,
    "gpu-initialization-retirement-failed" to R.string.engine_failure_retiring,
    "log-retirement-pending" to R.string.engine_failure_retiring,
    "preview-session-failed" to R.string.engine_failure_preview,
    "preview-session-exception" to R.string.engine_failure_preview,
    "preview-request-failed" to R.string.engine_failure_preview,
    "video-preview-gpu-init-failed" to R.string.engine_failure_preview,
    "video-preview-gpu-session-exception" to R.string.engine_failure_preview,
    "video-preview-gpu-request-failed" to R.string.engine_failure_preview,
    "video-preview-gpu-session-failed" to R.string.engine_failure_preview,
    "high-speed-preview-session-exception" to R.string.engine_failure_high_speed_preview,
    "high-speed-preview-request-failed" to R.string.engine_failure_high_speed_preview,
    "high-speed-preview-session-failed" to R.string.engine_failure_high_speed_preview,
    "log-capability-unavailable" to R.string.engine_failure_log_unavailable,
    "log-profile-missing" to R.string.engine_failure_log_unavailable,
    "log-source-invalid" to R.string.engine_failure_log_unavailable,
    "log-hfr-source-invalid" to R.string.engine_failure_log_unavailable,
    "log-gpu-init-failed" to R.string.engine_failure_log_failed,
    "log-input-surface-missing" to R.string.engine_failure_log_failed,
    "log-session-exception" to R.string.engine_failure_log_failed,
    "log-request-failed" to R.string.engine_failure_log_failed,
    "log-session-failed" to R.string.engine_failure_log_failed,
    "log-hfr-session-exception" to R.string.engine_failure_log_failed,
    "log-hfr-request-failed" to R.string.engine_failure_log_failed,
    "log-hfr-session-failed" to R.string.engine_failure_log_failed,
    "log-gpu-pipeline-failed" to R.string.engine_failure_log_failed,
    "video-prepare-failed" to R.string.engine_failure_video_prepare,
    "video-gpu-prepare-failed" to R.string.engine_failure_video_prepare,
    "log-recording-prepare-failed" to R.string.engine_failure_video_prepare,
    "audio-manual-gain-requires-pcm" to R.string.engine_failure_manual_gain_route,
    "video-start-failed" to R.string.engine_failure_video_start,
    "video-session-failed" to R.string.engine_failure_video_start,
    "video-session-exception" to R.string.engine_failure_video_start,
    "video-gpu-session-request-failed" to R.string.engine_failure_video_start,
    "video-gpu-session-failed" to R.string.engine_failure_video_start,
    "high-speed-video-session-failed" to R.string.engine_failure_video_start,
    "high-speed-video-start-failed" to R.string.engine_failure_video_start,
    "high-speed-video-session-exception" to R.string.engine_failure_video_start,
    "video-stop-failed" to R.string.engine_failure_video_stop,
    "still-format-unavailable" to R.string.engine_failure_still_format,
    "still-sequence-recording-busy" to R.string.engine_failure_still_sequence_busy,
    "still-encode-failed" to R.string.engine_failure_photo,
    "still-image-read-failed" to R.string.engine_failure_photo,
    "long-exposure-failed" to R.string.engine_failure_photo,
    "bracket-exposure-unsupported" to R.string.engine_failure_bracket_exposure,
    "burst-byte-limit" to R.string.engine_failure_sequence_too_large,
    "bracket-byte-limit" to R.string.engine_failure_sequence_too_large,
    "accumulation-insufficient-frames" to R.string.engine_failure_accumulation_frames,
)

/** Engine messages that carry a platform exception or a reason (`failure.message ?: …`, `…: $reason`). */
private val detailCodes: Set<String> = setOf(
    "camera-open-exception", "camera-owner-retirement-failed", "gpu-retirement-failed", "gpu-initialization-retirement-failed",
    "preview-session-exception", "preview-request-failed", "video-preview-gpu-init-failed", "video-preview-gpu-session-exception",
    "video-preview-gpu-request-failed", "high-speed-preview-session-exception", "high-speed-preview-request-failed",
    "log-gpu-init-failed", "log-session-exception", "log-request-failed", "log-hfr-session-exception", "log-hfr-request-failed",
    "log-gpu-pipeline-failed", "video-prepare-failed", "video-gpu-prepare-failed", "log-recording-prepare-failed",
    "video-start-failed", "video-session-exception", "video-gpu-session-request-failed", "high-speed-video-start-failed",
    "high-speed-video-session-exception", "still-encode-failed", "still-image-read-failed", "long-exposure-failed",
    "photo-flash-rejected", "photo-flash-prepare-failed", "photo-flash-precapture-failed", "photo-flash-capture-failed",
    "photo-flash-submit-failed", "photo-flash-restore-failed", "bracket-rejected", "bracket-metering-failed",
    "bracket-result-invalid", "bracket-restore-failed", "burst-result-invalid", "burst-restore-failed",
    "accumulation-image-processing-failed", "accumulation-result-invalid", "accumulation-restore-failed", "night-capture-failed",
)

private val sequencePrefixes = listOf(
    "burst-" to R.string.engine_failure_burst,
    "bracket-" to R.string.engine_failure_bracket,
    "accumulation-" to R.string.engine_failure_accumulation,
)

/**
 * The notice for the stable engine failure [code], or null for a code this table does not know
 * (the caller then shows the engine's message as it is). A still sequence prefixes the code of
 * the photo step that failed (`burst-photo-flash-timeout`), so the sequence names the notice.
 */
internal fun engineFailureNotice(code: String): EngineFailureNotice? {
    exactNotices[code]?.let { return EngineFailureNotice(it, code in detailCodes) }
    for ((prefix, text) in sequencePrefixes) if (code.startsWith(prefix)) {
        return EngineFailureNotice(text, code in detailCodes || code.removePrefix(prefix) in detailCodes)
    }
    return when {
        code.startsWith("camera-open-") -> EngineFailureNotice(R.string.engine_failure_camera_open, false)
        code.startsWith("photo-flash-") -> EngineFailureNotice(R.string.engine_failure_photo, code in detailCodes)
        code.startsWith("night-") -> EngineFailureNotice(R.string.engine_failure_night, code in detailCodes)
        else -> null
    }
}

/** The localized engine failure for the operator, the raw detail after " · " when it informs; unknown codes keep [message]. */
internal fun engineFailureText(code: String, message: String, text: (Int) -> String): String {
    val detail = message.replace(Regex("\\s*\\R\\s*"), " ").trim()
    val notice = engineFailureNotice(code) ?: return detail.ifBlank { code }
    return if (notice.detail && detail.isNotEmpty()) text(notice.text) + " · " + detail else text(notice.text)
}
