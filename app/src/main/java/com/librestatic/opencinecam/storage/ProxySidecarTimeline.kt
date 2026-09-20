/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.CaptureEpochReport
import com.librestatic.opencinecam.camera.PcmSourceTimingReport
import com.librestatic.opencinecam.camera.pcmFrameDurationNs

internal enum class ProxySidecarContainer { WAV, FLAC }

/** Typed observations only: caller must independently decode PCM and observe video first/end PTS.
 * videoEndUs is the exclusive presentation endpoint, not an ambiguous container duration/span.
 * videoFirstPtsUs is the stored track PTS, not sharedTiming.videoEncoderFirstPtsUs (codec-local).
 * sharedTiming comes from captureEpochJson; captureTiming comes from pcmSourceTimingJson.
 * Association, JSON parsing, source hashes and agreement between documents remain caller duties. */
internal data class ProxySidecarObservation(
    val container: ProxySidecarContainer,
    val sharedTiming: CaptureEpochReport,
    val captureTiming: PcmSourceTimingReport,
    val decodedSampleRateHz: Int,
    val decodedFrames: Long,
    val declaredSampleRateHz: Int,
    val declaredFrames: Long,
    val videoFirstPtsUs: Long,
    val videoEndUs: Long,
)

/** A composition plan, not a mux/decoder result or measured lip-sync certification.
 * PCM frame i maps to audioStartUs + i*1_000_000/sampleRateHz, without cumulative rounding.
 * Anchor offsets floor nanoseconds to microseconds exactly as CaptureEpochClock.offsetUs does.
 * Video PTS stay unchanged. Pause cuts are already present in both published sources.
 * Fractional PCM endpoint is exact: floor + numerator/denominator microseconds.
 * Integer endpoints/gaps use its ceiling; the sub-microsecond remainder is never hidden.
 * Gaps describe absent source samples; this plan inserts no silence or synthetic video. */
internal data class ProxySidecarTimeline(
    val sampleRateHz: Int,
    val pcmFrames: Long,
    val audioStartUs: Long,
    val videoFirstPtsUs: Long,
    val videoEndUs: Long,
    val audioDurationUsFloor: Long,
    val audioEndUsFloor: Long,
    val audioEndSubMicroNumerator: Long,
    val audioEndSubMicroDenominator: Int,
    val audioEndUsCeiling: Long,
    val outputEndUsCeiling: Long,
    val audioLeadingGapUs: Long,
    val videoLeadingGapUs: Long,
    val audioTrailingGapUs: Long,
    val videoTrailingGapUs: Long,
) {
    val videoPtsShiftUs: Long get() = 0
    val trimmedPcmFrames: Long get() = 0
    val insertedSilenceFrames: Long get() = 0
    val pauseCutsToApply: Int get() = 0
    val waveformAlignmentVerified: Boolean get() = false
}

internal fun proxySidecarTimeline(observed: ProxySidecarObservation): ProxySidecarTimeline {
    val shared = observed.sharedTiming
    val pcm = observed.captureTiming
    val rate = observed.decodedSampleRateHz
    val frames = observed.decodedFrames
    require(rate in 1..384_000 && rate == observed.declaredSampleRateHz && rate == pcm.sampleRateHz) {
        "Sidecar decoded and declared sample rates differ"
    }
    require(frames > 0 && frames == observed.declaredFrames && frames == pcm.writtenFrames &&
        frames == shared.submittedPcmFrames && pcm.capturedFrames >= frames &&
        pcm.capturedFrames == shared.capturedPcmFrames) { "Sidecar decoded, declared or retained frame counts differ" }
    require(pcm.durationNs == pcmFrameDurationNs(frames, rate)) { "Sidecar PCM duration differs from its frame grid" }
    require(shared.policy == "SHARED_BOOTTIME_CAPTURE_ANCHORS" && shared.cameraRealtime && shared.audioTimestampBacked) {
        "Sidecar composition needs measured comparable capture anchors"
    }
    require(shared.audioStorage == "SEPARATE_${observed.container.name}") { "Sidecar container/timing storage differs" }
    val videoZero = requireNotNull(shared.videoFrameZeroNs)
    val audioZero = requireNotNull(shared.audioFrameZeroNs)
    val origin = requireNotNull(shared.sharedOriginNs)
    val epoch = requireNotNull(pcm.epoch)
    require(videoZero > 0 && audioZero > 0 && origin == minOf(videoZero, audioZero) &&
        epoch.timestampBacked && epoch.frameZeroNs == audioZero && pcm.timestampObservations > 0 &&
        epoch.maxResidualNs >= 0 && shared.audioMaxResidualNs == epoch.maxResidualNs) { "Sidecar capture anchors disagree" }
    shared.sharedPause?.let { pause ->
        require(pause.sampleRateHz == rate && pause.audioFrameZeroNs == audioZero &&
            pause.capturedPcmFrames == pcm.capturedFrames && pause.retainedPcmFrames == frames &&
            pause.windows.size <= 10_000) { "Sidecar shared-pause geometry or counts differ" }
        val stop = requireNotNull(pause.stopFrame) { "Sidecar shared pause is not finalized" }
        require(stop >= 0)
        val retainedEnd = minOf(stop, pause.capturedPcmFrames)
        var removed = 0L
        var previousEnd = 0L
        for (window in pause.windows) {
            val end = requireNotNull(window.endFrame) { "Sidecar has an open pause window" }
            require(window.startFrame >= previousEnd && end >= window.startFrame && end <= stop) {
                "Sidecar pause windows are unordered or outside the stop boundary"
            }
            removed = Math.addExact(removed, (minOf(end, retainedEnd) - minOf(window.startFrame, retainedEnd)))
            previousEnd = end
        }
        require(Math.subtractExact(retainedEnd, removed) == frames) { "Sidecar retained frames disagree with published pause windows" }
    } ?: require(pcm.capturedFrames == frames) { "Sidecar discarded PCM without shared-pause evidence" }
    // MuxTimestampNormalizer already applied video offset; never add it to stored video PTS again.
    val videoOffset = Math.subtractExact(videoZero, origin) / 1000
    val audioOffset = Math.subtractExact(audioZero, origin) / 1000
    require(observed.videoFirstPtsUs == videoOffset && observed.videoEndUs > observed.videoFirstPtsUs) {
        "Observed video PTS/end do not match the shared capture mapping"
    }
    // Quotient/remainder avoids overflowing frames*1_000_000 for an otherwise representable grid.
    val fraction = Math.multiplyExact(frames % rate, 1_000_000L)
    val durationFloor = Math.addExact(Math.multiplyExact(frames / rate, 1_000_000L), fraction / rate)
    val remainder = fraction % rate
    val audioEndFloor = Math.addExact(audioOffset, durationFloor)
    val audioEndCeiling = Math.addExact(audioEndFloor, if (remainder == 0L) 0L else 1L)
    val outputEnd = maxOf(observed.videoEndUs, audioEndCeiling)
    return ProxySidecarTimeline(rate, frames, audioOffset, observed.videoFirstPtsUs, observed.videoEndUs,
        durationFloor, audioEndFloor, remainder, rate, audioEndCeiling, outputEnd,
        audioOffset, observed.videoFirstPtsUs,
        Math.subtractExact(outputEnd, audioEndCeiling), Math.subtractExact(outputEnd, observed.videoEndUs))
}
