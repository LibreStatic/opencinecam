/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure

/** Android AudioTimestamp's clock domain. Only MONOTONIC is accepted for file timing. */
enum class AudioTimestampTimebase { MONOTONIC, OTHER, UNKNOWN }

data class AudioTimestampSample(
    val framePosition: Long,
    val nanoTime: Long,
    val timebase: AudioTimestampTimebase,
) {
    init {
        require(framePosition >= 0) { "audio frame position must not be negative" }
        require(nanoTime >= 0) { "audio timestamp must not be negative" }
    }
}

data class MonotonicAudioTimestamp(
    val framePosition: Long,
    val timeUs: Long,
)

sealed interface AudioTimestampMapping {
    data class Mapped(val timestamp: MonotonicAudioTimestamp) : AudioTimestampMapping
    data class Rejected(val failure: StableFailure) : AudioTimestampMapping
}

/** Converts AudioTimestamp only when its timebase is explicitly MONOTONIC. */
class AudioTimestampMapper(private val correlationId: String = "audio-clock") {
    fun map(sample: AudioTimestampSample): AudioTimestampMapping = when (sample.timebase) {
        AudioTimestampTimebase.MONOTONIC -> AudioTimestampMapping.Mapped(
            MonotonicAudioTimestamp(sample.framePosition, sample.nanoTime / NANOS_PER_MICROSECOND),
        )
        AudioTimestampTimebase.UNKNOWN -> AudioTimestampMapping.Rejected(
            failure(FailureCode.UNKNOWN_CAPABILITY, Recoverability.USER_ACTION, "Audio timestamp timebase is unknown."),
        )
        AudioTimestampTimebase.OTHER -> AudioTimestampMapping.Rejected(
            failure(FailureCode.UNSUPPORTED_CAPABILITY, Recoverability.UNSUPPORTED, "Only a monotonic audio timestamp is supported."),
        )
    }

    private fun failure(code: FailureCode, recoverability: Recoverability, message: String) = StableFailure(
        component = "audio-clock",
        code = code,
        severity = if (code == FailureCode.UNKNOWN_CAPABILITY) FailureSeverity.WARNING else FailureSeverity.ERROR,
        recoverability = recoverability,
        correlationId = correlationId,
        userMessage = message,
    )

    private companion object { const val NANOS_PER_MICROSECOND = 1_000L }
}

data class AacPresentationTimestamp(val ptsUs: Long, val framePosition: Long)

sealed interface AacPtsResult {
    data class Generated(val timestamp: AacPresentationTimestamp) : AacPtsResult
    data class Rejected(val failure: StableFailure) : AacPtsResult
}

/** Anchors AAC presentation timestamps to the first monotonic AudioTimestamp without time stretching. */
class AacPtsGenerator(
    private val sampleRate: Int = 48_000,
    private val correlationId: String = "aac-pts",
    private val mapper: AudioTimestampMapper = AudioTimestampMapper(correlationId),
) {
    init { require(sampleRate > 0) }

    private var originTimeUs: Long? = null
    private var lastTimeUs = -1L
    private var lastFramePosition = -1L

    fun next(sample: AudioTimestampSample): AacPtsResult {
        val mapped = mapper.map(sample)
        if (mapped is AudioTimestampMapping.Rejected) return AacPtsResult.Rejected(mapped.failure)
        val timestamp = (mapped as AudioTimestampMapping.Mapped).timestamp
        if (timestamp.timeUs < lastTimeUs || timestamp.framePosition < lastFramePosition) {
            return AacPtsResult.Rejected(
                failure(FailureCode.CADENCE_DISCONTINUITY, "Audio timestamp moved backwards."),
            )
        }
        val origin = originTimeUs ?: timestamp.timeUs.also { originTimeUs = it }
        lastTimeUs = timestamp.timeUs
        lastFramePosition = timestamp.framePosition
        return AacPtsResult.Generated(
            AacPresentationTimestamp(
                ptsUs = timestamp.timeUs - origin,
                framePosition = timestamp.framePosition,
            ),
        )
    }

    fun reset() {
        originTimeUs = null
        lastTimeUs = -1L
        lastFramePosition = -1L
    }

    private fun failure(code: FailureCode, message: String) = StableFailure(
        component = "aac-pts",
        code = code,
        severity = FailureSeverity.ERROR,
        recoverability = if (code == FailureCode.CANCELLATION) Recoverability.CANCELLED else Recoverability.RETRYABLE,
        correlationId = correlationId,
        userMessage = message,
    )
}

data class AudioGap(
    val startPtsUs: Long,
    val durationUs: Long,
    val missingFrames: Long,
    val reason: String,
) {
    init {
        require(startPtsUs >= 0 && durationUs > 0 && missingFrames > 0 && reason.isNotBlank())
    }
}

data class AudioOverrun(
    val atPtsUs: Long,
    val droppedFrames: Long,
) {
    init { require(atPtsUs >= 0 && droppedFrames > 0) }
}

enum class AvDiagnosticAction { NONE, WARNING, STOP }

data class AvDiagnosticSnapshot(
    val videoPtsUs: Long,
    val audioPtsUs: Long,
    val driftUs: Long,
    val sustainedDriftUs: Long,
    val overruns: List<AudioOverrun>,
    val gaps: List<AudioGap>,
    val warningPersistent: Boolean,
    val action: AvDiagnosticAction,
    val failure: StableFailure? = null,
) {
    init { require(videoPtsUs >= 0 && audioPtsUs >= 0 && sustainedDriftUs >= 0) }
}

/**
 * Compares the file clock against the monotonic audio clock. A drift greater than 40 ms
 * sustained for five seconds is a critical Strict stop; Adaptive records a persistent warning.
 */
class AvClockDiagnostics(
    private val mode: PolicyMode,
    private val thresholdUs: Long = DEFAULT_THRESHOLD_US,
    private val sustainUs: Long = DEFAULT_SUSTAIN_US,
    private val correlationId: String = "av-diagnostics",
) : AutoCloseable {
    init { require(thresholdUs > 0 && sustainUs > 0) }

    private val overruns = mutableListOf<AudioOverrun>()
    private val gaps = mutableListOf<AudioGap>()
    private var violationSinceUs: Long? = null
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var closed = false
    private var warningPersistent = false
    private var terminalFailure: StableFailure? = null

    fun observe(videoPtsUs: Long, audioPtsUs: Long, nowUs: Long = maxOf(videoPtsUs, audioPtsUs)): AvDiagnosticSnapshot {
        check(!closed) { "diagnostics are closed" }
        require(videoPtsUs >= 0 && audioPtsUs >= 0 && nowUs >= 0)
        if (videoPtsUs < lastVideoPtsUs || audioPtsUs < lastAudioPtsUs) {
            terminalFailure = failure(FailureCode.CADENCE_DISCONTINUITY, FailureSeverity.CRITICAL, "A/V presentation timestamps moved backwards.")
        }
        lastVideoPtsUs = videoPtsUs
        lastAudioPtsUs = audioPtsUs
        val driftUs = videoPtsUs - audioPtsUs
        val absoluteDriftUs = kotlin.math.abs(driftUs)
        val sustained = if (absoluteDriftUs > thresholdUs) {
            val since = violationSinceUs ?: nowUs.also { violationSinceUs = it }
            (nowUs - since).coerceAtLeast(0)
        } else {
            violationSinceUs = null
            0L
        }
        if (sustained >= sustainUs) {
            warningPersistent = true
            if (terminalFailure == null) {
                terminalFailure = failure(
                    FailureCode.CADENCE_DISCONTINUITY,
                    if (mode == PolicyMode.STRICT) FailureSeverity.CRITICAL else FailureSeverity.WARNING,
                    if (mode == PolicyMode.STRICT) "Sustained A/V drift exceeded the capture limit." else "Sustained A/V drift detected; recording continues with a warning.",
                )
            }
        }
        val action = when {
            terminalFailure != null && mode == PolicyMode.STRICT -> AvDiagnosticAction.STOP
            warningPersistent -> AvDiagnosticAction.WARNING
            else -> AvDiagnosticAction.NONE
        }
        return snapshot(videoPtsUs, audioPtsUs, driftUs, sustained, action)
    }

    fun recordOverrun(atPtsUs: Long, droppedFrames: Long): AvDiagnosticSnapshot {
        check(!closed)
        overruns += AudioOverrun(atPtsUs, droppedFrames)
        return snapshot(lastVideoPtsUs.coerceAtLeast(0), lastAudioPtsUs.coerceAtLeast(0), lastVideoPtsUs - lastAudioPtsUs, 0, AvDiagnosticAction.NONE)
    }

    fun recordGap(startPtsUs: Long, missingFrames: Long, sampleRate: Int = 48_000, reason: String = "audio overrun"): AudioGap {
        check(!closed)
        require(sampleRate > 0)
        val gap = AudioGap(startPtsUs, missingFrames * 1_000_000L / sampleRate, missingFrames, reason)
        gaps += gap
        return gap
    }

    fun snapshot(): AvDiagnosticSnapshot {
        check(!closed) { "diagnostics are closed" }
        return snapshot(
        lastVideoPtsUs.coerceAtLeast(0),
        lastAudioPtsUs.coerceAtLeast(0),
        lastVideoPtsUs - lastAudioPtsUs,
        0,
        if (terminalFailure != null && mode == PolicyMode.STRICT) AvDiagnosticAction.STOP else if (warningPersistent) AvDiagnosticAction.WARNING else AvDiagnosticAction.NONE,
        )
    }

    override fun close() { closed = true }

    private fun snapshot(video: Long, audio: Long, drift: Long, sustained: Long, action: AvDiagnosticAction) = AvDiagnosticSnapshot(
        videoPtsUs = video,
        audioPtsUs = audio,
        driftUs = drift,
        sustainedDriftUs = sustained,
        overruns = overruns.toList(),
        gaps = gaps.toList(),
        warningPersistent = warningPersistent,
        action = action,
        failure = terminalFailure,
    )

    private fun failure(code: FailureCode, severity: FailureSeverity, message: String) = StableFailure(
        component = "av-diagnostics",
        code = code,
        severity = severity,
        recoverability = if (code == FailureCode.CADENCE_DISCONTINUITY) Recoverability.RETRYABLE else Recoverability.USER_ACTION,
        correlationId = correlationId,
        userMessage = message,
    )

    private companion object {
        const val DEFAULT_THRESHOLD_US = 40_000L
        const val DEFAULT_SUSTAIN_US = 5_000_000L
    }
}
