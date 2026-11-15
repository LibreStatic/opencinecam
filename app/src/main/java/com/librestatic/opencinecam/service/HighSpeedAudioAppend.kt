/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.AppendedAudioPlacement
import com.librestatic.opencinecam.camera.AudioCaptureEpoch
import com.librestatic.opencinecam.camera.CaptureEpochReport
import com.librestatic.opencinecam.media.audio.AudioOutputFormat

/**
 * PLAN-074 decision 10: a constrained high-speed take reaches `MediaRecorder` straight from the camera, and the
 * recorder's own microphone path cannot use the app's input, equaliser or gain. Such a take with AAC audio records
 * video only; the app captures the audio beside it and appends it to the MP4 when the take ends. A recording LUT or an
 * anamorphic desqueeze sends the take through the GPU path, which embeds its audio itself; WAV and FLAC stay separate
 * files; off-speed slow motion has no audio.
 */
internal fun appendsHighSpeedAudio(
    constrainedHighSpeed: Boolean,
    offSpeed: Boolean,
    audioRequested: Boolean,
    format: AudioOutputFormat,
    recordingLut: Boolean,
    anamorphic: Boolean,
): Boolean = constrainedHighSpeed && !offSpeed && audioRequested && format == AudioOutputFormat.AAC_MP4 &&
    !recordingLut && !anamorphic

/** The take's A/V timing once its audio was appended at [placement] (see [appendsHighSpeedAudio]). */
internal fun appendedAudioEpochReport(
    placement: AppendedAudioPlacement,
    cameraRealtime: Boolean,
    audioEpoch: AudioCaptureEpoch?,
    primingFrames: Int,
    pcmFrames: Long,
    accessUnits: Long,
): CaptureEpochReport = CaptureEpochReport(
    policy = placement.policy,
    cameraRealtime = cameraRealtime,
    videoFrameZeroNs = placement.videoFrameZeroNs,
    audioFrameZeroNs = placement.audioFrameZeroNs,
    // As CaptureEpochClock has it: the origin the audio was placed against, so it moves with the sync offset.
    sharedOriginNs = minOf(placement.videoFrameZeroNs, Math.addExact(placement.audioFrameZeroNs, placement.audioSyncOffsetUs * 1_000L)),
    audioTimestampBacked = audioEpoch?.timestampBacked == true,
    audioMaxResidualNs = audioEpoch?.takeIf { it.timestampBacked }?.maxResidualNs,
    videoEncoderFirstPtsUs = null,
    audioEncoderFirstPtsUs = null,
    audioEncoderDelayFrames = primingFrames,
    submittedPcmFrames = pcmFrames,
    encodedAudioPackets = accessUnits,
    audioPresentationOffsetUs = placement.startOffsetUs,
    capturedPcmFrames = pcmFrames,
    audioStorage = "EMBEDDED_AAC",
    audioSyncOffsetUs = placement.audioSyncOffsetUs,
)
