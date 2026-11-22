/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class MuxTimestampNormalizerTest {
    @Test
    fun `rebases video and audio epochs independently`() {
        val normalizer = MuxTimestampNormalizer()

        assertEquals(0L, normalizer.normalize(video = false, presentationTimeUs = 0L))
        assertEquals(0L, normalizer.normalize(video = true, presentationTimeUs = 26_832_161_600L))
        assertEquals(21_333L, normalizer.normalize(video = false, presentationTimeUs = 21_333L))
        assertEquals(33_333L, normalizer.normalize(video = true, presentationTimeUs = 26_832_194_933L))
    }

    @Test
    fun `never emits a negative or regressing timestamp`() {
        val normalizer = MuxTimestampNormalizer()

        assertEquals(0L, normalizer.normalize(video = true, presentationTimeUs = 10_000L))
        assertEquals(5_000L, normalizer.normalize(video = true, presentationTimeUs = 15_000L))
        assertEquals(5_000L, normalizer.normalize(video = true, presentationTimeUs = 14_000L))
    }

    @Test
    fun `exposes the offset it adds to video for the motion data`() {
        val epoch = CaptureEpochClock(cameraRealtime = true, sampleRateHz = 48_000)
        epoch.videoInput(1_000_000_000L)
        epoch.audioInput(AudioCaptureEpoch(frameZeroNs = 980_000_000L, timestampBacked = true))
        val normalizer = MuxTimestampNormalizer(epoch)

        assertEquals(0L, normalizer.videoOffsetUs)
        assertEquals(0L, normalizer.normalize(video = false, presentationTimeUs = 500L))
        assertEquals("audio leaves the video offset alone", 0L, normalizer.videoOffsetUs)
        assertEquals(20_000L, normalizer.normalize(video = true, presentationTimeUs = 1_000_000L))
        assertEquals(53_333L, normalizer.normalize(video = true, presentationTimeUs = 1_033_333L))
        assertEquals(20_000L, normalizer.videoOffsetUs)

        val report = epoch.report(videoEncoderFirstPtsUs = 1_000_000L, audioEncoderFirstPtsUs = 500L, encoderDelayFrames = null)
        assertEquals("the clock leaves it to the pipeline", 0L, report.videoMuxOffsetUs)
        assertEquals(20_000L, report.copy(videoMuxOffsetUs = normalizer.videoOffsetUs).videoMuxOffsetUs)
    }

    @Test
    fun `without a capture epoch the video offset stays zero`() {
        val normalizer = MuxTimestampNormalizer()
        normalizer.normalize(video = true, presentationTimeUs = 7_000_000L)
        assertEquals(0L, normalizer.videoOffsetUs)
    }
}
