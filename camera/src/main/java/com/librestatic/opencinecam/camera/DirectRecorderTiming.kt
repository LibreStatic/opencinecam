/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/**
 * Frame anchors of a take `MediaRecorder` recorded straight from the camera (a constrained high-speed take), which
 * reports no timestamps of its own. Sensor timestamps are on the camera's clock; [recorderStartElapsedNs] is boot time.
 */
data class DirectRecorderTiming(
    /** `elapsedRealtimeNanos` right after `MediaRecorder.start()` returned. */
    val recorderStartElapsedNs: Long,
    /** Sensor timestamp of the first frame reported after the recorder started. */
    val firstFrameSensorNs: Long?,
    /** Sensor timestamp of the latest frame reported before the recorder stopped. */
    val lastFrameSensorNs: Long?,
    val frames: Long,
    /** No capture was in flight when the recorder stopped, so [lastFrameSensorNs] is the last frame in the file. */
    val drained: Boolean,
)

/** Where audio captured beside a direct recorder take goes on the file's timeline (PLAN-074 decision 10). */
data class AppendedAudioPlacement(
    /** The first PCM frame's time on the file's timeline, microseconds; negative when the audio started first. */
    val startOffsetUs: Long,
    /** [SHARED_ANCHORS] when both ends are measured on the boot-time clock, otherwise [ESTIMATED_VIDEO_START]. */
    val policy: String,
    /** The file's time zero on the boot-time clock: measured, or the estimate that was used. */
    val videoFrameZeroNs: Long,
    /** The audio's frame zero as measured or estimated; the manual sync offset is not in it. */
    val audioFrameZeroNs: Long,
    /** The manual sync offset (PLAN-074 decision 12) [startOffsetUs] includes, microseconds; positive is later. */
    val audioSyncOffsetUs: Long = 0,
) {
    val measured: Boolean get() = policy == SHARED_ANCHORS

    companion object {
        const val SHARED_ANCHORS = "SHARED_BOOTTIME_CAPTURE_ANCHORS"
        const val ESTIMATED_VIDEO_START = "BOOTTIME_ESTIMATED_VIDEO_START"

        /** A measured time zero this far from the recorder's start is not believed: frames were lost at one end. */
        internal const val EARLIEST_ZERO_BEFORE_START_NS = 250_000_000L
        internal const val LATEST_ZERO_AFTER_START_NS = 1_000_000_000L
    }
}

/**
 * The stop drains the session before the recorder stops, so the last sample in the file is the last frame captured:
 * the file's time zero is that frame's sensor timestamp minus its presentation time, [lastSampleTimeUs]. That holds
 * when the sensor clock is boot time ([sensorRealtime]), the session drained and the audio epoch comes from the input's
 * own timestamps. Otherwise the file's zero is taken to be the first frame after the recorder started, or the
 * recorder's start itself, and the audio's [audioStartEstimateNs]: frame zero as [PcmReadEpochEstimate] has it, or
 * failing that the moment the input started. [audioSyncOffsetNs], the manual sync offset (PLAN-074 decision 12),
 * moves only where the audio starts on the file's timeline.
 */
fun placeAppendedAudio(
    timing: DirectRecorderTiming,
    sensorRealtime: Boolean,
    lastSampleTimeUs: Long,
    audioEpoch: AudioCaptureEpoch?,
    audioStartEstimateNs: Long,
    audioSyncOffsetNs: Long = 0,
): AppendedAudioPlacement {
    require(lastSampleTimeUs >= 0 && audioStartEstimateNs > 0)
    val audioZeroNs = audioEpoch?.frameZeroNs ?: audioStartEstimateNs
    val measuredZeroNs = timing.lastFrameSensorNs
        ?.takeIf { sensorRealtime && timing.drained && audioEpoch?.timestampBacked == true }
        ?.let { it - lastSampleTimeUs * 1_000L }
        ?.takeIf {
            it - timing.recorderStartElapsedNs in
                -AppendedAudioPlacement.EARLIEST_ZERO_BEFORE_START_NS..AppendedAudioPlacement.LATEST_ZERO_AFTER_START_NS
        }
    val videoZeroNs = measuredZeroNs
        ?: timing.firstFrameSensorNs?.takeIf { sensorRealtime && it >= timing.recorderStartElapsedNs }
        ?: timing.recorderStartElapsedNs
    return AppendedAudioPlacement(
        startOffsetUs = (audioZeroNs + audioSyncOffsetNs - videoZeroNs) / 1_000L,
        policy = if (measuredZeroNs != null) AppendedAudioPlacement.SHARED_ANCHORS else AppendedAudioPlacement.ESTIMATED_VIDEO_START,
        videoFrameZeroNs = videoZeroNs,
        audioFrameZeroNs = audioZeroNs,
        audioSyncOffsetUs = audioSyncOffsetNs / 1_000L,
    )
}
