/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GallerySettingsTest {
    private val keys = setOf("gallery-kind", "gallery-newest-first", "gallery-good-takes-only", "gallery-show-slate", "gallery-show-technical")
    private fun document(value: CameraSettings = CameraSettings()) = Json.parseToJsonElement(
        CameraPresetCodec.encode(CameraPreset(name = "Gallery", settings = value))).jsonObject
    @Test fun defaultsAndAllKindsFlagsPersistAndRoundTripIndependently() {
        assertEquals(GallerySettings(), CameraSettingsStore(PresetPreferences()).load().gallery)
        for (kind in GalleryMediaKind.entries) for (bits in 0..15) {
            val value = CameraSettings(gallery = GallerySettings(kind, bits and 1 != 0, bits and 2 != 0,
                bits and 4 != 0, bits and 8 != 0), productionSlate = ProductionSlateSettings(scene = "A", takeNumber = 42))
            val store = CameraSettingsStore(PresetPreferences()); store.save(value)
            assertEquals(value, store.load())
            assertEquals(value.gallery, CameraPresetCodec.decode(document(value).toString()).settings.gallery)
        }
    }
    @Test fun corruptGroupUsesDefaultsAndPortableDoesNotCoerceUnknownEnumsOrBooleans() {
        val root = document()
        for ((key, value) in listOf("gallery-kind" to JsonPrimitive("IMAGE"), "gallery-newest-first" to JsonPrimitive("true"),
            "gallery-good-takes-only" to JsonPrimitive(1), "gallery-show-slate" to JsonPrimitive(0))) {
            assertThrows(key, Exception::class.java) { CameraPresetCodec.decode(JsonObject(root +
                ("settings" to JsonObject(root.getValue("settings").jsonObject + (key to value)))).toString()) }
        }
        for (bad in listOf(mapOf("gallery-kind" to "IMAGE"), mapOf("gallery-show-technical" to "true"))) {
            assertEquals(GallerySettings(), CameraSettingsStore(PresetPreferences(bad)).load().gallery)
        }
    }
    @Test fun versionFifteenHas160KeysAndVersionFourteenRetains147AndMigratesDefaults() {
        assertEquals(18, CameraPresetCodec.VERSION); assertEquals(160, CameraPresetCodec.portableKeys.size)
        assertTrue(CameraPresetCodec.portableKeys.containsAll(keys))
        val source = CameraSettings(productionSlate = ProductionSlateSettings(project = "Keep", goodTake = true))
        val root = document(source); val fields = root.getValue("settings").jsonObject.filterKeys { !it.startsWith("media-share-") && !it.startsWith("capture-naming-") && !it.startsWith("playback-") } - keys
        assertEquals(147, fields.size)
        val old = JsonObject(root + mapOf("version" to JsonPrimitive(14), "settings" to JsonObject(fields)))
        assertEquals(source, CameraPresetCodec.decode(old.toString()).settings)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(14))).toString()) }
        for (key in keys) assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root +
            ("settings" to JsonObject(root.getValue("settings").jsonObject - key))).toString()) }
    }
    @Test fun galleryRemainsLiveDuringCaptureButSlateAndAudioConfigurationRemainFrozen() {
        val original = CameraSettings(productionSlate = ProductionSlateSettings(scene = "Before"), audioInputDeviceId = 45)
        val requested = original.copy(gallery = GallerySettings(GalleryMediaKind.VIDEO, false, true, false, true),
            productionSlate = ProductionSlateSettings(scene = "Next"), audioInputDeviceId = 56)
        val effective = original.withLivePreferencesFrom(requested)
        assertEquals(requested.gallery, effective.gallery)
        assertEquals(original.productionSlate, effective.productionSlate)
        assertEquals(45, effective.audioInputDeviceId)
        assertTrue("media-gallery" in SettingsCatalog.search("galería", SettingsCategory.CAPTURE) { "" })
    }
    @Test fun failedStoreDoesNotPublishGalleryAndQueryIsBoundedWithoutSilentTruncation() {
        val persistence = object : SettingsPersistence {
            override fun load() = CameraSettings()
            override fun save(settings: CameraSettings) { error("write failure") }
        }
        val repository = SettingsRepository(persistence)
        assertThrows(IllegalStateException::class.java) { repository.update { it.copy(gallery = GallerySettings(goodTakesOnly = true)) } }
        assertEquals(GallerySettings(), repository.states.value.gallery)
        assertTrue(validGalleryQuery("")); assertTrue(validGalleryQuery("ñ".repeat(128)))
        assertFalse(validGalleryQuery("x".repeat(129))); assertFalse(validGalleryQuery("a\nb")); assertFalse(validGalleryQuery("\u0000"))
    }
}
