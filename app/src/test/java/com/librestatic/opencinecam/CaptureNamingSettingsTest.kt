/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CaptureNamingSettingsTest {
    private val keys = setOf("capture-naming-enabled", "capture-naming-template")
    private fun document(settings: CameraSettings = CameraSettings()) = Json.parseToJsonElement(
        CameraPresetCodec.encode(CameraPreset(name = "Naming", settings = settings))).jsonObject

    @Test fun defaultsAndCustomTemplatesRoundTripWithUnrelatedSettingsPreserved() {
        assertEquals(CaptureNamingSettings(), CameraSettingsStore(PresetPreferences()).load().captureNaming)
        for (enabled in listOf(false, true)) for (template in listOf("{project}_{scene}_T{take}", "{camera}_{reel}_{date}_{time}", "Noche Á")) {
            val original = CameraSettings(captureNaming = CaptureNamingSettings(enabled, template),
                productionSlate = ProductionSlateSettings(project = "Preserve"), audioInputKey = com.librestatic.opencinecam.media.audio.AudioInputKey(22, "Lav", "card=41"),
                mediaSharing = MediaSharingSettings(MediaShareContent.ORIGINALS_ONLY))
            val store = CameraSettingsStore(PresetPreferences()); store.save(original)
            assertEquals(original, store.load())
            assertEquals(original.captureNaming, CameraPresetCodec.decode(document(original).toString()).settings.captureNaming)
        }
    }
    @Test fun versionEighteenHas160KeysAndSixteenRetains155AndDisabledNamingDefaults() {
        assertEquals(21, CameraPresetCodec.VERSION); assertEquals(166, CameraPresetCodec.portableKeys.size)
        assertTrue(CameraPresetCodec.portableKeys.containsAll(keys))
        val original = CameraSettings(mediaSharing = MediaSharingSettings(MediaShareContent.METADATA_ONLY))
        val root = document(original); val old = root.getValue("settings").jsonObject.filterKeys { it !in PRESET_V20_KEYS && !it.startsWith("playback-") && it != "gallery-auto-thumbnails" } - keys
        assertEquals(155, old.size)
        val historical = JsonObject(root + mapOf("version" to JsonPrimitive(16), "settings" to JsonObject(old)))
        assertEquals(original, CameraPresetCodec.decode(historical.toString()).settings)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(16))).toString()) }
        for (key in keys) assertThrows(Exception::class.java) {
            CameraPresetCodec.decode(JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject - key))).toString())
        }
    }
    @Test fun malformedLocalGroupDefaultsRatherThanKeepingAnEnabledInvalidTemplate() {
        for (bad in listOf(mapOf("capture-naming-enabled" to "true"),
            mapOf("capture-naming-enabled" to true, "capture-naming-template" to "{unknown}"),
            mapOf("capture-naming-enabled" to true, "capture-naming-template" to "../folder"),
            mapOf("capture-naming-template" to 12))) {
            assertEquals(CaptureNamingSettings(), CameraSettingsStore(PresetPreferences(bad)).load().captureNaming)
        }
    }
    @Test fun malformedPresetTypesTokensRangesAndControlsRejectInsteadOfDefaulting() {
        val root = document()
        val invalid = listOf("capture-naming-enabled" to JsonPrimitive("true"),
            "capture-naming-template" to JsonPrimitive(12), "capture-naming-template" to JsonPrimitive("{unknown}"),
            "capture-naming-template" to JsonPrimitive("a".repeat(129)), "capture-naming-template" to JsonPrimitive("x\n"))
        for ((key, value) in invalid) assertThrows(Exception::class.java) {
            CameraPresetCodec.decode(JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject + (key to value)))).toString())
        }
    }
    @Test fun livePreferenceChangesNeverMutateAlreadyFrozenNamingOrSlate() {
        val before = CameraSettings(productionSlate = ProductionSlateSettings(project = "Before"))
        val frozen = CaptureNameSnapshot(before.captureNaming, before.productionSlate, 1767323045006L)
        val next = before.copy(captureNaming = CaptureNamingSettings(true, "{project}_{take}"),
            productionSlate = ProductionSlateSettings(project = "After"), audioInputDeviceId = 7)
        val live = before.withLivePreferencesFrom(next)
        assertEquals(next.captureNaming, live.captureNaming)
        assertEquals(before.productionSlate, live.productionSlate); assertEquals(before.audioInputDeviceId, live.audioInputDeviceId)
        assertEquals("OCC_00000000-0000-4000-8000-000000000000", captureFileStem("00000000-0000-4000-8000-000000000000", frozen))
        assertEquals(before.captureNaming, frozen.settings)
        assertTrue("capture-naming" in SettingsCatalog.search("plantilla nombres", SettingsCategory.MEDIA) { "" })
    }
    @Test fun failedPreferenceWriteDoesNotPublishNewCaptureNaming() {
        val before = CameraSettings()
        val repository = SettingsRepository(object : SettingsPersistence {
            override fun load() = before
            override fun save(settings: CameraSettings) { throw IllegalStateException("Fixture write failed") }
        })
        assertThrows(IllegalStateException::class.java) { repository.update { it.copy(captureNaming = CaptureNamingSettings(true)) } }
        assertEquals(before, repository.states.value)
    }
}
