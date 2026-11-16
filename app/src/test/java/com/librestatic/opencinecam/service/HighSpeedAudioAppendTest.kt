/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.AppendedAudioPlacement
import com.librestatic.opencinecam.camera.AudioCaptureEpoch
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighSpeedAudioAppendTest {
    private fun appends(
        constrainedHighSpeed: Boolean = true,
        offSpeed: Boolean = false,
        audioRequested: Boolean = true,
        format: AudioOutputFormat = AudioOutputFormat.AAC_MP4,
        recordingLut: Boolean = false,
        anamorphic: Boolean = false,
    ) = appendsHighSpeedAudio(constrainedHighSpeed, offSpeed, audioRequested, format, recordingLut, anamorphic)

    @Test fun aRealSpeedHighSpeedTakeWithAacAudioAppendsIt() = assertTrue(appends())

    @Test fun everyOtherTakeKeepsItsOwnAudioPath() {
        assertFalse(appends(constrainedHighSpeed = false))
        // Slow motion has no audio.
        assertFalse(appends(offSpeed = true))
        assertFalse(appends(audioRequested = false))
        // Lossless audio stays a separate file.
        assertFalse(appends(format = AudioOutputFormat.WAV_PCM))
        assertFalse(appends(format = AudioOutputFormat.FLAC))
        // These go through the GPU path, which embeds the audio itself.
        assertFalse(appends(recordingLut = true))
        assertFalse(appends(anamorphic = true))
    }

    @Test fun theReportCarriesThePlacementAndTheEncodersPriming() {
        val placement = AppendedAudioPlacement(35_000L, AppendedAudioPlacement.SHARED_ANCHORS, 50_020_000_000L, 50_055_000_000L)
        val report = appendedAudioEpochReport(placement, cameraRealtime = true, AudioCaptureEpoch(50_055_000_000L, true, 40_000L),
            primingFrames = 2_048, pcmFrames = 480_000L, accessUnits = 471L)
        assertEquals(AppendedAudioPlacement.SHARED_ANCHORS, report.policy)
        assertEquals(50_020_000_000L, report.sharedOriginNs)
        assertEquals(35_000L, report.audioPresentationOffsetUs)
        assertEquals(2_048, report.audioEncoderDelayFrames)
        assertEquals(40_000L, report.audioMaxResidualNs)
        assertEquals("EMBEDDED_AAC", report.audioStorage)
        assertEquals(480_000L, report.submittedPcmFrames)
    }

    @Test fun theReportCarriesTheManualSyncOffsetAndKeepsTheMeasuredAudioZero() {
        // Audio measured 35 ms after the video, placed 80 ms earlier by hand: it starts 45 ms before the video.
        val placement = AppendedAudioPlacement(-45_000L, AppendedAudioPlacement.SHARED_ANCHORS, 50_020_000_000L, 50_055_000_000L,
            audioSyncOffsetUs = -80_000L)
        val report = appendedAudioEpochReport(placement, cameraRealtime = true, AudioCaptureEpoch(50_055_000_000L, true, 40_000L),
            primingFrames = 2_048, pcmFrames = 480_000L, accessUnits = 471L)
        assertEquals(-80_000L, report.audioSyncOffsetUs)
        assertEquals(50_055_000_000L, report.audioFrameZeroNs)
        assertEquals(49_975_000_000L, report.sharedOriginNs)
        assertEquals(-45_000L, report.audioPresentationOffsetUs)
        assertEquals(0L, appendedAudioEpochReport(placement.copy(audioSyncOffsetUs = 0L), cameraRealtime = true, null,
            primingFrames = 2_048, pcmFrames = 1L, accessUnits = 2L).audioSyncOffsetUs)
    }

    @Test fun anEstimatedPlacementClaimsNoTimestampResidual() {
        val placement = AppendedAudioPlacement(-12_000L, AppendedAudioPlacement.ESTIMATED_VIDEO_START, 50_000_000_000L, 49_988_000_000L)
        val report = appendedAudioEpochReport(placement, cameraRealtime = false, AudioCaptureEpoch(49_988_000_000L, false),
            primingFrames = 1_024, pcmFrames = 1L, accessUnits = 2L)
        assertEquals(49_988_000_000L, report.sharedOriginNs)
        assertFalse(report.audioTimestampBacked)
        assertNull(report.audioMaxResidualNs)
    }
}
