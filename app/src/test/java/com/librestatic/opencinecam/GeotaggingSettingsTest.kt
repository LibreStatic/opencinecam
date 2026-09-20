/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeotaggingSettingsTest {
    @Test fun absentConsentDefaultsOffAndExplicitChoicePersistsSeparatelyFromSlate() {
        val memory = PresetPreferences()
        val store = CameraSettingsStore(memory)
        assertFalse(CameraSettings().geotaggingEnabled)
        assertFalse(store.load().geotaggingEnabled)
        for (enabled in listOf(true, false)) {
            val value = CameraSettings(geotaggingEnabled = enabled,
                productionSlate = ProductionSlateSettings(location = ProductionSlateLocation.EXTERIOR))
            store.save(value)
            assertEquals(value, store.load())
            assertEquals(enabled, memory.all["geotagging-enabled"])
            assertEquals(setOf("geotagging-enabled"), memory.all.keys.filter { it.startsWith("geotagging-") }.toSet())
        }
    }

    @Test fun presetExportsNeverContainConsentAndImportNeverEnablesIt() {
        val off = CameraPreset(name = "Location", settings = CameraSettings())
        val on = off.copy(settings = off.settings.copy(geotaggingEnabled = true))
        assertEquals(CameraPresetCodec.encode(off), CameraPresetCodec.encode(on))
        assertFalse("geotagging-enabled" in CameraPresetCodec.portableKeys)
        assertFalse(CameraPresetCodec.decode(CameraPresetCodec.encode(on)).settings.geotaggingEnabled)
        assertTrue(CameraPresetCodec.differences(off.settings, on.settings).isEmpty())
        val root = Json.parseToJsonElement(CameraPresetCodec.encode(on)).jsonObject
        for (enabled in listOf(false, true)) {
            val injected = JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject +
                ("geotagging-enabled" to JsonPrimitive(enabled)))))
            assertThrows(Exception::class.java) { CameraPresetCodec.decode(injected.toString()) }
        }
    }

    @Test fun everyPresetConsentCombinationPreservesCurrentLocalChoice() {
        for (current in listOf(false, true)) for (incoming in listOf(false, true)) {
            val merged = CameraPresetCodec.mergeLocal(
                CameraSettings(geotaggingEnabled = incoming, videoBitrateMbps = 40),
                CameraSettings(geotaggingEnabled = current))
            assertEquals(current, merged.geotaggingEnabled)
            assertEquals(40, merged.videoBitrateMbps)
        }
    }

    @Test fun consentChangesAreLiveWithoutChangingAdmittedSlateOrRecordingFormat() {
        for (enabled in listOf(false, true)) {
            val old = CameraSettings(geotaggingEnabled = !enabled)
            val next = old.copy(geotaggingEnabled = enabled, videoBitrateMbps = 40,
                productionSlate = ProductionSlateSettings(scene = "Next"))
            val effective = old.withLivePreferencesFrom(next)
            assertEquals(enabled, effective.geotaggingEnabled)
            assertEquals(old.productionSlate, effective.productionSlate)
            assertEquals(old.videoBitrateMbps, effective.videoBitrateMbps)
        }
    }

    @Test fun captureSearchFindsEnglishSpanishAndAccentedAliases() {
        for (query in listOf("geolocation", "geotagging", "GPS", "ubicación", "localizacion", "coordenadas", "approximate", "privacidad")) {
            assertTrue(query, "geotagging" in SettingsCatalog.search(query, SettingsCategory.CAPTURE) { "" })
        }
        assertFalse("geotagging" in SettingsCatalog.search("", SettingsCategory.AUDIO) { "" })
        assertEquals(1, SettingsCatalog.entries.count { it.id == "geotagging" })
    }

    @Test fun failedPersistenceDoesNotPublishNewConsent() {
        val repository = SettingsRepository(object : SettingsPersistence {
            override fun load() = CameraSettings()
            override fun save(settings: CameraSettings) { error("write failure") }
        })
        assertThrows(IllegalStateException::class.java) { repository.update { it.copy(geotaggingEnabled = true) } }
        assertFalse(repository.states.value.geotaggingEnabled)
    }
}
