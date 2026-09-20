/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.DigitalRecordingGain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AudioRecordingGainSettingsTest {
    private val keys = setOf("audio-recording-gain-enabled", "audio-recording-gain-db")
    private fun document(settings: CameraSettings = CameraSettings()) = Json.parseToJsonElement(
        CameraPresetCodec.encode(CameraPreset(name = "Audio gain", settings = settings))).jsonObject

    @Test fun disabledDefaultDoesNotChangeExistingAgcIntent() {
        val old = CameraSettingsStore(PresetPreferences(mapOf("audio-automatic-gain-control" to true))).load()
        assertEquals(DigitalRecordingGain(), old.audioRecordingGain)
        assertTrue(old.automaticGainControlEnabled)
    }
    @Test fun everyDecibelAndEnabledStateRoundTripsWithoutErasingAgcOrLocalInput() {
        for (db in -24..24) for (enabled in listOf(false, true)) for (agc in listOf(false, true)) {
            val original = CameraSettings(audioRecordingGain = DigitalRecordingGain(enabled, db),
                automaticGainControlEnabled = agc, audioInputDeviceId = 101, photoQuality = 71)
            val prefs = PresetPreferences(); val store = CameraSettingsStore(prefs)
            store.save(original)
            assertEquals(original, store.load())
            assertEquals(enabled, prefs.all["audio-recording-gain-enabled"])
            assertEquals(db, prefs.all["audio-recording-gain-db"])
        }
    }
    @Test fun currentPortableHasExactly160KeysIncludingExplicitZeroDbManualMode() {
        val original = CameraSettings(audioRecordingGain = DigitalRecordingGain(true, 0))
        val decoded = CameraPresetCodec.decode(document(original).toString())
        assertEquals(original, decoded.settings)
        assertEquals(18, CameraPresetCodec.VERSION)
        assertEquals(160, CameraPresetCodec.portableKeys.size)
        assertTrue(CameraPresetCodec.portableKeys.containsAll(keys))
    }
    @Test fun versionTenRetainsExact127FieldsAndManualIsNeverEnabledByMigration() {
        val root = document(CameraSettings(automaticGainControlEnabled = false, photoQuality = 71))
        val historical = root.getValue("settings").jsonObject.filterKeys { !it.startsWith("audio-listening-") && !it.startsWith("audio-meter-") && !it.startsWith("slate-") && !it.startsWith("gallery-") && !it.startsWith("media-share-") && !it.startsWith("capture-naming-") && !it.startsWith("playback-") } - keys
        assertEquals(127, historical.size)
        val old = JsonObject(root + mapOf("version" to JsonPrimitive(10), "settings" to JsonObject(historical)))
        val decoded = CameraPresetCodec.decode(old.toString()).settings
        assertEquals(DigitalRecordingGain(), decoded.audioRecordingGain)
        assertFalse(decoded.automaticGainControlEnabled); assertEquals(71, decoded.photoQuality)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(10))).toString()) }
    }
    @Test fun malformedLocalGroupResetsBothFieldsButPresetRejectsNoncanonicalValues() {
        for (db in listOf(-25, 25, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val prefs = PresetPreferences(mapOf("audio-recording-gain-enabled" to true, "audio-recording-gain-db" to db))
            assertEquals(DigitalRecordingGain(), CameraSettingsStore(prefs).load().audioRecordingGain)
        }
        val root = document()
        for ((key, value) in listOf("audio-recording-gain-db" to JsonPrimitive(-25),
            "audio-recording-gain-db" to JsonPrimitive(25), "audio-recording-gain-db" to JsonPrimitive(0.5),
            "audio-recording-gain-db" to JsonPrimitive("6"), "audio-recording-gain-enabled" to JsonPrimitive("true"))) {
            val mutated = JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject + (key to value))))
            assertThrows(key, Exception::class.java) { CameraPresetCodec.decode(mutated.toString()) }
        }
    }
    @Test fun manualAndAgcIntentStayFrozenButLiveMonitorChangesStillApply() {
        val current = CameraSettings(audioRecordingGain = DigitalRecordingGain(true, -3), automaticGainControlEnabled = true)
        val next = current.copy(audioRecordingGain = DigitalRecordingGain(true, 24), automaticGainControlEnabled = false, zebraEnabled = true)
        val effective = current.withLivePreferencesFrom(next)
        assertEquals(current.audioRecordingGain, effective.audioRecordingGain)
        assertTrue(effective.automaticGainControlEnabled)
        assertTrue(effective.zebraEnabled)
        assertEquals(2, CameraPresetCodec.differences(current, next).count { it.startsWith("audio-") })
    }
    @Test fun repositoryPublishesOnlyAfterPersistenceAcceptsTheWholeGainIntent() {
        val initial = CameraSettings()
        var fail = false
        val persistence = object : SettingsPersistence {
            var persisted = initial
            override fun load() = persisted
            override fun save(settings: CameraSettings) { check(!fail); persisted = settings }
        }
        val repository = SettingsRepository(persistence)
        repository.update { it.copy(audioRecordingGain = DigitalRecordingGain(true, 12)) }
        val committed = repository.states.value
        assertEquals(committed, SettingsRepository(persistence).states.value)
        fail = true
        assertThrows(Exception::class.java) { repository.update { it.copy(audioRecordingGain = DigitalRecordingGain(true, -12)) } }
        assertEquals(committed, repository.states.value)
        assertEquals(committed, persistence.persisted)
    }
    @Test fun searchAndPresetLocalMergeNeverConflateGainWithPhysicalInput() {
        val current = CameraSettings(audioInputDeviceId = 101)
        val requested = CameraSettings(audioRecordingGain = DigitalRecordingGain(true, 18), audioInputDeviceId = 202)
        val merged = CameraPresetCodec.mergeLocal(requested, current)
        assertEquals(101, merged.audioInputDeviceId)
        assertEquals(requested.audioRecordingGain, merged.audioRecordingGain)
        for (query in listOf("digital", "manual", "dB"))
            assertTrue("audio-format" in SettingsCatalog.search(query, SettingsCategory.AUDIO) { "" })
    }
}
