/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.DigitalRecordingGain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AudioListeningSettingsTest {
    private val keys = setOf("audio-listening-enabled", "audio-listening-volume", "audio-listening-output")
    private fun document(settings: CameraSettings = CameraSettings()) = Json.parseToJsonElement(
        CameraPresetCodec.encode(CameraPreset(name = "Listen", settings = settings))).jsonObject

    @Test fun defaultsNeitherEnableListeningNorSelectSpeakerOrAPhysicalDevice() {
        val loaded = CameraSettingsStore(PresetPreferences()).load()
        assertEquals(AudioListeningSettings(), loaded.audioListening)
        assertFalse(loaded.audioListening.enabled)
        assertNull(loaded.audioListeningOutputDeviceId)
        assertEquals(AudioListeningPhase.DISABLED, CameraUiState().audioListeningStatus.phase)
    }
    @Test fun allVolumesOutputKindsAndEnableStatesRoundTripIndependentlyOfInputAndGain() {
        for (volume in 0..100) for (output in AudioListeningOutput.entries) for (enabled in listOf(false, true)) {
            val original = CameraSettings(audioListening = AudioListeningSettings(enabled, volume, output),
                audioListeningOutputDeviceId = 212, audioInputDeviceId = 111,
                audioRecordingGain = DigitalRecordingGain(true, -6), automaticGainControlEnabled = true)
            val store = CameraSettingsStore(PresetPreferences()); store.save(original)
            assertEquals(original, store.load())
        }
    }
    @Test fun localMalformedGroupRestoresDisabledDefaultAndInvalidRouteIdBecomesUnselected() {
        for (values in listOf(mapOf("audio-listening-volume" to -1), mapOf("audio-listening-volume" to 101),
            mapOf("audio-listening-output" to "UNKNOWN"))) {
            val store = CameraSettingsStore(PresetPreferences(values + ("audio-listening-enabled" to true)))
            assertEquals(AudioListeningSettings(), store.load().audioListening)
        }
        assertNull(CameraSettingsStore(PresetPreferences(mapOf("audio-listening-output-device-id" to -9))).load().audioListeningOutputDeviceId)
        assertThrows(IllegalArgumentException::class.java) { AudioListeningSettings(volumePercent = -1) }
        assertThrows(IllegalArgumentException::class.java) { AudioListeningSettings(volumePercent = 101) }
    }
    @Test fun portableV18Has160KeysAndPhysicalOutputIsLocalAcrossImport() {
        val original = CameraSettings(audioListening = AudioListeningSettings(true, 34, AudioListeningOutput.BLUETOOTH), audioListeningOutputDeviceId = 212)
        val root = document(original)
        assertEquals(20, CameraPresetCodec.VERSION); assertEquals(164, CameraPresetCodec.portableKeys.size)
        assertTrue(CameraPresetCodec.portableKeys.containsAll(keys))
        assertFalse(root.getValue("settings").jsonObject.containsKey("audio-listening-output-device-id"))
        val decoded = CameraPresetCodec.decode(root.toString()).settings
        assertEquals(original.audioListening, decoded.audioListening); assertNull(decoded.audioListeningOutputDeviceId)
        val merged = CameraPresetCodec.mergeLocal(decoded, CameraSettings(audioListeningOutputDeviceId = 313, audioInputDeviceId = 101))
        assertEquals(313, merged.audioListeningOutputDeviceId); assertEquals(101, merged.audioInputDeviceId)
        // Runtime connection is deliberately not part of preferences or portable state.
        assertEquals(AudioListeningStatus(), CameraUiState(effectiveSettings = merged).audioListeningStatus)
    }
    @Test fun versionElevenRetains129KeysAndMigratesListeningDisabledAtFiftyPercent() {
        val original = CameraSettings(audioRecordingGain = DigitalRecordingGain(true, 12))
        val root = document(original); val values = root.getValue("settings").jsonObject.filterKeys { it !in PRESET_V20_KEYS && !it.startsWith("audio-meter-") && !it.startsWith("slate-") && !it.startsWith("gallery-") && !it.startsWith("media-share-") && !it.startsWith("capture-naming-") && !it.startsWith("playback-") } - keys
        assertEquals(129, values.size)
        val historical = JsonObject(root + mapOf("version" to JsonPrimitive(11), "settings" to JsonObject(values)))
        assertEquals(original, CameraPresetCodec.decode(historical.toString()).settings)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(11))).toString()) }
    }
    @Test fun portableRejectsMalformedTypesBoundsUnknownOutputsAndDeviceIds() {
        val root = document()
        for ((key, value) in listOf("audio-listening-volume" to JsonPrimitive(-1), "audio-listening-volume" to JsonPrimitive(101),
            "audio-listening-volume" to JsonPrimitive(1.5), "audio-listening-enabled" to JsonPrimitive("true"),
            "audio-listening-output" to JsonPrimitive("UNKNOWN"), "audio-listening-output-device-id" to JsonPrimitive(212))) {
            val invalid = JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject + (key to value))))
            assertThrows(key, Exception::class.java) { CameraPresetCodec.decode(invalid.toString()) }
        }
    }
    @Test fun listeningChangesStayLiveWhileGainAndInputStayFrozen() {
        val current = CameraSettings(audioRecordingGain = DigitalRecordingGain(true, -6), audioInputDeviceId = 101)
        val next = current.copy(audioListening = AudioListeningSettings(true, 0, AudioListeningOutput.SPEAKER),
            audioListeningOutputDeviceId = 202, audioRecordingGain = DigitalRecordingGain(true, 18), audioInputDeviceId = 303)
        val effective = current.withLivePreferencesFrom(next)
        assertEquals(next.audioListening, effective.audioListening)
        assertEquals(next.audioListeningOutputDeviceId, effective.audioListeningOutputDeviceId)
        assertEquals(current.audioRecordingGain, effective.audioRecordingGain)
        assertEquals(current.audioInputDeviceId, effective.audioInputDeviceId)
    }
    @Test fun repositoryFailureCannotPublishNewListeningIntentAndLongCountersStayExact() {
        var fail = false
        val persistence = object : SettingsPersistence {
            var saved = CameraSettings()
            override fun load() = saved
            override fun save(settings: CameraSettings) { check(!fail); saved = settings }
        }
        val repository = SettingsRepository(persistence)
        repository.update { it.copy(audioListening = AudioListeningSettings(true, 76)) }
        val committed = repository.states.value
        assertEquals(committed, SettingsRepository(persistence).states.value)
        fail = true
        assertThrows(Exception::class.java) { repository.update { it.copy(audioListening = AudioListeningSettings(false, 0)) } }
        assertEquals(committed, repository.states.value)
        val count = (1L shl 53) + 9L
        assertEquals(count, AudioListeningStatus(acceptedFrames = count).acceptedFrames)
        assertThrows(IllegalArgumentException::class.java) { AudioListeningStatus(droppedPackets = -1) }
        assertTrue("audio-format" in SettingsCatalog.search("listening", SettingsCategory.AUDIO) { "" })
    }
}
