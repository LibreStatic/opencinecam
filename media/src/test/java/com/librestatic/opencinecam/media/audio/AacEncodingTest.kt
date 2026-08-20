/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.media.mux.MuxerTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AacEncodingTest {
    @Test
    fun configuresStereoAndMonoTargetBitrates() {
        assertTrue(configureAac(AacEncodingRequest(channels = 2, bitrate = 192_000)) is AacEncodingConfiguration.Configured)
        assertTrue(configureAac(AacEncodingRequest(channels = 1, bitrate = 96_000)) is AacEncodingConfiguration.Configured)
    }

    @Test
    fun rejectsWrongRateOrBitrateWithoutFallback() {
        val rejected = configureAac(AacEncodingRequest(sampleRate = 44_100, channels = 2, bitrate = 128_000))
        assertEquals(FailureCode.ENCODER_CONFIGURATION_FAILED, (rejected as AacEncodingConfiguration.Rejected).failure.code)
    }

    @Test
    fun audioDisabledRecordingNeedsNoMicrophoneOrAudioTrack() {
        val disabled = AudioTrackPlan(enabled = false)
        assertFalse(disabled.microphoneRequired)
        assertEquals(setOf(MuxerTrack.VIDEO), disabled.expectedMuxerTracks)
        assertTrue(disabled.submitAudioEos { error("must not submit audio EOS") })

        val enabled = AudioTrackPlan(enabled = true)
        assertTrue(enabled.microphoneRequired)
        assertEquals(setOf(MuxerTrack.VIDEO, MuxerTrack.AUDIO), enabled.expectedMuxerTracks)
        assertTrue(enabled.submitAudioEos { it == MuxerTrack.AUDIO })
    }
}
