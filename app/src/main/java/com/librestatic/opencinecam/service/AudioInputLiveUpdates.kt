/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.CameraSettings

/** What a settings change does to a running preview audio monitor. */
internal enum class PreviewAudioUpdate {
    /** Only what the monitor does not read changed (meters, listening, slate, gallery, sharing). */
    NONE,
    /** Only the equaliser changed: the running monitor adopts the curve at its next buffer, the microphone stays open. */
    EQ_CURVE,
    /** Anything else: the monitor is retired and reopened. */
    RESTART,
}

/**
 * PLAN-074 decision 13: an equaliser being edited must not reopen the microphone at every drag, so a change
 * to [CameraSettings.audioEqCurve] or [CameraSettings.audioEqProfileId] alone updates the running monitor.
 */
internal fun previewAudioUpdate(previous: CameraSettings, next: CameraSettings): PreviewAudioUpdate {
    val display = previous.copy(audioListening = next.audioListening,
        audioListeningOutputDeviceId = next.audioListeningOutputDeviceId,
        audioMeter = next.audioMeter, productionSlate = next.productionSlate,
        gallery = next.gallery, mediaSharing = next.mediaSharing)
    return when {
        display == next -> PreviewAudioUpdate.NONE
        display.copy(audioEqCurve = next.audioEqCurve, audioEqProfileId = next.audioEqProfileId) == next -> PreviewAudioUpdate.EQ_CURVE
        else -> PreviewAudioUpdate.RESTART
    }
}

/**
 * PLAN-074 decision 14: the overload notice holds [holdMs] after the last snapshot that flagged it, so a brief
 * burst reads, and clears by itself once the input stays clean. Times are elapsed-realtime milliseconds, as the
 * meter stamps its snapshots. Not thread-safe: the service uses it on the main thread.
 */
internal class AudioOverloadHold(private val holdMs: Long = AUDIO_OVERLOAD_HOLD_MS) {
    private var lastFlaggedAtMs = Long.MIN_VALUE

    /** One snapshot; returns whether the notice shows after it. */
    fun observe(flagged: Boolean, atMs: Long): Boolean {
        if (flagged) lastFlaggedAtMs = atMs
        return shown(atMs)
    }

    /** True within [holdMs] of the last flagged snapshot, or when the clock reads earlier than it. */
    fun shown(nowMs: Long): Boolean = lastFlaggedAtMs != Long.MIN_VALUE && nowMs - lastFlaggedAtMs < holdMs

    /** Milliseconds from [nowMs] until the notice may clear; null when it is not showing. */
    fun clearsInMs(nowMs: Long): Long? = if (!shown(nowMs)) null else (lastFlaggedAtMs + holdMs - nowMs).coerceIn(1L, holdMs)

    fun reset() { lastFlaggedAtMs = Long.MIN_VALUE }
}

internal const val AUDIO_OVERLOAD_HOLD_MS = 5_000L
