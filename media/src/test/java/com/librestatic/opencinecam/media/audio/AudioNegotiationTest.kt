/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.Knowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioNegotiationTest {
    @Test
    fun prefersUnprocessedThenVoiceThenMicAndStereo() {
        val result = AudioNegotiator().negotiate(request(), capability()) as AudioNegotiation.Configured

        assertEquals(AudioSourceKind.UNPROCESSED, result.configuration.source)
        assertEquals(48_000, result.configuration.sampleRate)
        assertEquals(2, result.configuration.channelCount)
        assertTrue(result.configuration.noiseSuppressor.enabled)
        assertFalse(result.configuration.automaticGainControl.enabled)
    }

    @Test
    fun fallsBackExplicitlyWhenUnprocessedUnavailable() {
        val capability = capability().copy(unprocessed = Knowledge.Known(false))

        val result = AudioNegotiator().negotiate(request(), capability)
        assertEquals(AudioSourceKind.VOICE_RECOGNITION, (result as AudioNegotiation.Configured).configuration.source)

        val micOnly = capability.copy(voiceRecognition = Knowledge.Known(false))
        assertEquals(AudioSourceKind.MIC, ((AudioNegotiator().negotiate(request(), micOnly) as AudioNegotiation.Configured).configuration.source))
    }

    @Test
    fun unknownAndUnsupportedBranchesRemainDistinct() {
        val unknown = AudioNegotiator().negotiate(request(), capability().copy(sampleRates = Knowledge.Unknown))
        assertEquals(FailureCode.UNKNOWN_CAPABILITY, (unknown as AudioNegotiation.Rejected).failure.code)

        val unsupported = AudioNegotiator().negotiate(request(), capability().copy(sampleRates = Knowledge.Unsupported("no PCM")))
        assertEquals(FailureCode.UNSUPPORTED_CAPABILITY, (unsupported as AudioNegotiation.Rejected).failure.code)
    }

    @Test
    fun monoFallbackAndInputDeviceSelectionAreExplicit() {
        val request = request().copy(preferStereo = true, preferredInputDeviceId = 3, enableAutomaticGainControl = true)
        val capability = capability().copy(
            channelCounts = Knowledge.Known(setOf(1)),
            inputDevices = Knowledge.Known(listOf(AudioInputDevice(3, "USB mic"))),
            automaticGainControl = Knowledge.Known(false),
        )
        val configuration = (AudioNegotiator().negotiate(request, capability) as AudioNegotiation.Configured).configuration
        assertEquals(1, configuration.channelCount)
        assertEquals(3, configuration.inputDevice?.id)
        assertTrue(configuration.automaticGainControl.requested)
        assertFalse(configuration.automaticGainControl.enabled)
    }

    private fun request() = AudioRequest(enableNoiseSuppressor = true)

    private fun capability() = AudioCapability(
        unprocessed = Knowledge.Known(true),
        voiceRecognition = Knowledge.Known(true),
        mic = Knowledge.Known(true),
        sampleRates = Knowledge.Known(setOf(48_000)),
        channelCounts = Knowledge.Known(setOf(1, 2)),
        noiseSuppressor = Knowledge.Known(true),
        automaticGainControl = Knowledge.Known(true),
        acousticEchoCanceler = Knowledge.Known(false),
        inputDevices = Knowledge.Known(listOf(AudioInputDevice(1, "Built-in mic"))),
    )
}
