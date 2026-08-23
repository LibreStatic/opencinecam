/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.media.audio.PcmAudioConfiguration
import com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities
import org.junit.Assert.assertEquals
 import org.junit.Assert.assertFalse
 import org.junit.Assert.assertTrue
import org.junit.Test

class ProfessionalAudioSettingsTest {
    private val capabilities = ProfessionalAudioCapabilities(
        permissionGranted = true,
        formats = listOf(AudioOutputFormat.AAC_MP4, AudioOutputFormat.WAV_PCM, AudioOutputFormat.FLAC),
        sampleRates = listOf(44_100, 48_000),
        bitDepths = listOf(AudioBitDepth.PCM_16, AudioBitDepth.PCM_24),
        channelCounts = listOf(1, 2),
        pcmConfigurations = listOf(
            PcmAudioConfiguration(44_100, AudioBitDepth.PCM_16, 1),
            PcmAudioConfiguration(48_000, AudioBitDepth.PCM_24, 2),
        ),
        aacSampleRates = listOf(44_100, 48_000),
        aacChannelCounts = listOf(1, 2),
        aacBitratesKbps = listOf(96, 128, 192),
        sources = listOf(AudioSourceSelection.VOICE_RECOGNITION, AudioSourceSelection.MIC),
        inputs = emptyList(),
        noiseSuppressorAvailable = true,
        automaticGainControlAvailable = false,
        acousticEchoCancelerAvailable = false,
    )

    @Test
    fun wavNormalizationKeepsOnlyAnInitializedTuple() {
        val normalized = CameraSettings(
            audioOutputFormat = AudioOutputFormat.WAV_PCM,
            audioSampleRateHz = 48_000,
            audioBitDepth = AudioBitDepth.PCM_24,
            audioChannels = 1,
        ).normalizedFor(capabilities)
        assertEquals(48_000, normalized.audioSampleRateHz)
        assertEquals(AudioBitDepth.PCM_24, normalized.audioBitDepth)
        assertEquals(2, normalized.audioChannels)
    }

    @Test
    fun unavailableSourceAndEffectsAreNotSilentlyClaimed() {
        val normalized = CameraSettings(
            audioSource = AudioSourceSelection.UNPROCESSED,
            automaticGainControlEnabled = true,
            acousticEchoCancelerEnabled = true,
        ).normalizedFor(capabilities)
        assertEquals(AudioSourceSelection.VOICE_RECOGNITION, normalized.audioSource)
        // SoftAgc fallback keeps the AGC intent even when the HAL lacks the effect.
        assertTrue(normalized.automaticGainControlEnabled)
        assertFalse(normalized.acousticEchoCancelerEnabled)
    }

    @Test
    fun flacRejectsFloatAndKeepsAnInitializedIntegerTuple() {
        val withFloat = capabilities.copy(
            bitDepths = capabilities.bitDepths + AudioBitDepth.PCM_FLOAT,
            pcmConfigurations = capabilities.pcmConfigurations +
                PcmAudioConfiguration(48_000, AudioBitDepth.PCM_FLOAT, 2),
        )
        val normalized = CameraSettings(
            audioOutputFormat = AudioOutputFormat.FLAC,
            audioSampleRateHz = 48_000,
            audioBitDepth = AudioBitDepth.PCM_FLOAT,
            audioChannels = 2,
        ).normalizedFor(withFloat)
        assertEquals(AudioOutputFormat.FLAC, normalized.audioOutputFormat)
        assertEquals(AudioBitDepth.PCM_16, normalized.audioBitDepth)
        assertEquals(1, normalized.audioChannels)
    }

    @Test
    fun agcIsOnByDefaultAndAlwaysSurvivesNormalization() {
        assertTrue(CameraSettings().automaticGainControlEnabled)

        val withAgc = capabilities.copy(automaticGainControlAvailable = true)
        val aacNormalized = CameraSettings(audioOutputFormat = AudioOutputFormat.AAC_MP4).normalizedFor(withAgc)
        assertTrue(aacNormalized.automaticGainControlEnabled)

        // Even without HAL support the user intent is preserved (SoftAgc delivers it).
        val withoutHal = CameraSettings().normalizedFor(capabilities)
        assertTrue(withoutHal.automaticGainControlEnabled)
    }
}
