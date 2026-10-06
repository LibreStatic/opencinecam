/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioInputKey
import com.librestatic.opencinecam.media.audio.AudioInputLossPolicy
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.media.audio.PcmAudioConfiguration
import com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities
import com.librestatic.opencinecam.media.audio.SelectableAudioInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputSettingsTest {
    private val builtIn = SelectableAudioInput(7, "", 15, listOf(48_000), listOf(1, 2), emptyList(), "bottom")
    private val lav = SelectableAudioInput(42, "USB-C Lavalier", 11, listOf(48_000), listOf(1), emptyList(), "card=2;device=0")

    private fun capabilities(vararg inputs: SelectableAudioInput) = ProfessionalAudioCapabilities(
        permissionGranted = true, formats = listOf(AudioOutputFormat.WAV_PCM), sampleRates = listOf(48_000),
        bitDepths = listOf(AudioBitDepth.PCM_16), channelCounts = listOf(1),
        pcmConfigurations = listOf(PcmAudioConfiguration(48_000, AudioBitDepth.PCM_16, 1)),
        aacSampleRates = listOf(48_000), aacChannelCounts = listOf(1), aacBitratesKbps = listOf(192),
        sources = listOf(AudioSourceSelection.MIC), inputs = inputs.toList(),
        noiseSuppressorAvailable = false, automaticGainControlAvailable = false, acousticEchoCancelerAvailable = false,
    )

    @Test fun autoBindsAConnectedUsbMicrophoneButLeavesTheBuiltInToThePlatform() {
        assertEquals(42, CameraSettings().normalizedFor(capabilities(builtIn, lav)).audioInputDeviceId)
        assertNull(CameraSettings().normalizedFor(capabilities(builtIn)).audioInputDeviceId)
        assertNull(CameraSettings().normalizedFor(capabilities(builtIn, lav)).audioInputKey)
    }

    @Test fun aRememberedKeyResolvesToTheLiveIdAndAMissingOneIsKeptAndUnavailable() {
        val key = AudioInputKey(11, "USB-C Lavalier", "card=1;device=0")
        val connected = CameraSettings(audioInputKey = key).normalizedFor(capabilities(builtIn, lav))
        assertEquals(42, connected.audioInputDeviceId); assertEquals(key, connected.audioInputKey)
        val missing = CameraSettings(audioInputKey = key, audioInputDeviceId = 42).normalizedFor(capabilities(builtIn))
        assertEquals(key, missing.audioInputKey); assertNull(missing.audioInputDeviceId)
        assertTrue(missing.audioInputUnavailable(capabilities(builtIn)))
        assertFalse(connected.audioInputUnavailable(capabilities(builtIn, lav)))
    }

    @Test fun aLegacyIdMigratesToTheKeyOfTheMatchingDevice() {
        val migrated = CameraSettings(legacyAudioInputDeviceId = 42).normalizedFor(capabilities(builtIn, lav))
        assertEquals(lav.key, migrated.audioInputKey); assertNull(migrated.legacyAudioInputDeviceId)
        assertEquals(42, migrated.audioInputDeviceId)
        val stale = CameraSettings(legacyAudioInputDeviceId = 99).normalizedFor(capabilities(builtIn, lav))
        assertNull(stale.audioInputKey); assertNull(stale.legacyAudioInputDeviceId)
    }

    @Test fun keyAndPolicyRoundTripAndTheRuntimeIdIsNotPersisted() {
        val store = CameraSettingsStore(PresetPreferences())
        val value = CameraSettings(audioInputKey = lav.key, audioInputLossPolicy = AudioInputLossPolicy.CONTINUE_SILENT)
        store.save(value.copy(audioInputDeviceId = 42))
        assertEquals(value, store.load())
        store.save(CameraSettings())
        assertNull(store.load().audioInputKey)
        assertEquals(AudioInputLossPolicy.STOP_TAKE, store.load().audioInputLossPolicy)
    }

    @Test fun aLegacyStoredIdLoadsAsLegacyOnly() {
        val loaded = CameraSettingsStore(PresetPreferences(mapOf("audio-input-device-id" to 42))).load()
        assertEquals(42, loaded.legacyAudioInputDeviceId); assertNull(loaded.audioInputDeviceId); assertNull(loaded.audioInputKey)
    }

    @Test fun keysWithLineBreaksInTheNameStillDecode() {
        val key = AudioInputKey(11, "Odd\nName", "a")
        assertEquals(AudioInputKey(11, "Odd Name", "a"), decodeAudioInputKey(encodeAudioInputKey(key)))
        assertNull(decodeAudioInputKey("garbage"))
    }

    @Test fun presetsCarryNoInputIdentity() {
        val local = CameraSettings(audioInputKey = lav.key, audioInputLossPolicy = AudioInputLossPolicy.FALLBACK_BUILTIN)
        val merged = CameraPresetCodec.mergeLocal(CameraSettings(), local)
        assertEquals(lav.key, merged.audioInputKey); assertEquals(AudioInputLossPolicy.FALLBACK_BUILTIN, merged.audioInputLossPolicy)
    }

    @Test fun identicalInputLabelsAreNumberedInOrder() {
        assertEquals(
            listOf("Built-in microphone", "External input (1)", "USB · Lav", "External input (2)"),
            uniqueAudioInputLabels(listOf("Built-in microphone", "External input", "USB · Lav", "External input")),
        )
    }

    @Test fun builtInMicrophonePositionsComeFromTheAddress() {
        assertEquals(R.string.audio_input_position_bottom, builtInMicPositionLabel("bottom"))
        assertEquals(R.string.audio_input_position_back, builtInMicPositionLabel(" Back "))
        assertNull(builtInMicPositionLabel(""))
        assertNull(builtInMicPositionLabel("card=2;device=0"))
    }
}
