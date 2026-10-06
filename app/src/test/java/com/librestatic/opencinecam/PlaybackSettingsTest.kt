/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackSettingsTest {
    private val keys = setOf("playback-muted", "playback-loop", "playback-show-frame-position")
    private fun document(settings: CameraSettings = CameraSettings()) = Json.parseToJsonElement(
        CameraPresetCodec.encode(CameraPreset(name = "Playback", settings = settings))).jsonObject
    @Test fun allEightCombinationsRoundTripWithoutAddingAutoplayOrPositionPreferences() {
        assertEquals(PlaybackSettings(), CameraSettingsStore(PresetPreferences()).load().playback)
        for (muted in listOf(false, true)) for (loop in listOf(false, true)) for (position in listOf(false, true)) {
            val original = CameraSettings(playback = PlaybackSettings(muted, loop, position),
                audioEnabled = false, audioInputKey = com.librestatic.opencinecam.media.audio.AudioInputKey(22, "Lav", "card=32"), captureNaming = CaptureNamingSettings(true))
            val memory = PresetPreferences(); val store = CameraSettingsStore(memory); store.save(original)
            assertEquals(original, store.load())
            assertEquals(keys, memory.all.keys.filter { it.startsWith("playback-") }.toSet())
            assertEquals(original.playback, CameraPresetCodec.decode(document(original).toString()).settings.playback)
        }
    }
    @Test fun ocLogReviewViewPersistsLocallyButStaysOutOfPresets() {
        val original = CameraSettings(playback = PlaybackSettings(logView = com.librestatic.opencinecam.storage.PreciseLogView.REC709))
        val store = CameraSettingsStore(PresetPreferences()); store.save(original)
        assertEquals(original.playback, store.load().playback)
        assertFalse("review-log-view" in CameraPresetCodec.portableKeys)
        assertEquals(com.librestatic.opencinecam.storage.PreciseLogView.REC709, CameraPresetCodec.mergeLocal(CameraSettings(), original).playback.logView)
        assertEquals(PlaybackSettings(), CameraSettingsStore(PresetPreferences(mapOf("review-log-view" to "SEPIA"))).load().playback)
    }
    @Test fun directDisplayOutputPersistsLocallyButStaysOutOfPresets() {
        assertFalse(PlaybackSettings().nativeSurfaceFrames)
        val original = CameraSettings(playback = PlaybackSettings(muted = true, nativeSurfaceFrames = true))
        val memory = PresetPreferences(); val store = CameraSettingsStore(memory); store.save(original)
        assertEquals(original.playback, store.load().playback)
        assertEquals(true, memory.all["review-native-surface"])
        assertFalse("review-native-surface" in CameraPresetCodec.portableKeys)
        assertFalse(CameraPresetCodec.encode(CameraPreset(name = "Direct", settings = original)).contains("review-native-surface"))
        assertFalse(CameraPresetCodec.decode(document(original).toString()).settings.playback.nativeSurfaceFrames)
        // Applying a preset keeps this device's choice either way.
        assertTrue(CameraPresetCodec.mergeLocal(CameraSettings(), original).playback.nativeSurfaceFrames)
        assertFalse(CameraPresetCodec.mergeLocal(original, CameraSettings()).playback.nativeSurfaceFrames)
    }
    @Test fun versionEighteenHas160KeysAndSeventeenRetains157WithExactDefaults() {
        assertEquals(20, CameraPresetCodec.VERSION); assertEquals(164, CameraPresetCodec.portableKeys.size)
        val original = CameraSettings(captureNaming = CaptureNamingSettings(true, "{scene}"))
        val root = document(original); val old = root.getValue("settings").jsonObject - keys - "gallery-auto-thumbnails" - PRESET_V20_KEYS
        assertEquals(157, old.size)
        val historical = JsonObject(root + mapOf("version" to JsonPrimitive(17), "settings" to JsonObject(old)))
        assertEquals(original, CameraPresetCodec.decode(historical.toString()).settings)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(17))).toString()) }
        for (key in keys) assertThrows(Exception::class.java) {
            CameraPresetCodec.decode(JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject - key))).toString())
        }
    }
    @Test fun malformedStorageDefaultsAsAGroupAndMalformedOrExtraPresetFieldsReject() {
        val root = document()
        for (key in keys) {
            assertEquals(PlaybackSettings(), CameraSettingsStore(PresetPreferences(mapOf("playback-muted" to true, key to "false"))).load().playback)
            assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root +
                ("settings" to JsonObject(root.getValue("settings").jsonObject + (key to JsonPrimitive("false"))))).toString()) }
        }
        for (key in listOf("playback-autoplay", "playback-position-ms")) assertThrows(Exception::class.java) {
            CameraPresetCodec.decode(JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject + (key to JsonPrimitive(1))))).toString())
        }
    }
    @Test fun livePreferencesAndRepositoryUpdatesPreserveFrozenCaptureAudioAndOtherSettings() {
        val initial = CameraSettings(productionSlate = ProductionSlateSettings(scene = "Keep"), audioInputDeviceId = 11)
        val requested = initial.copy(playback = PlaybackSettings(true, true, false), audioInputDeviceId = 22,
            productionSlate = ProductionSlateSettings(scene = "Pending"))
        val live = initial.withLivePreferencesFrom(requested)
        assertEquals(requested.playback, live.playback)
        assertEquals(initial.audioInputDeviceId, live.audioInputDeviceId); assertEquals(initial.productionSlate, live.productionSlate)
        var saved = initial
        val repository = SettingsRepository(object : SettingsPersistence {
            override fun load() = saved
            override fun save(settings: CameraSettings) { saved = settings }
        })
        repository.update { it.copy(playback = requested.playback) }
        assertEquals(initial.copy(playback = requested.playback), repository.states.value)
        assertTrue("playback" in SettingsCatalog.search("reproduccion visor", SettingsCategory.MEDIA) { "" })
    }
}
