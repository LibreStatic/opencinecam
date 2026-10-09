/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProductionSlateSettingsTest {
    private val keys = setOf("slate-project", "slate-camera", "slate-scene", "slate-reel", "slate-lens",
        "slate-take-number", "slate-location", "slate-time-of-day", "slate-good-take", "slate-auto-increment")
    private fun document(value: CameraSettings = CameraSettings()) = Json.parseToJsonElement(
        CameraPresetCodec.encode(CameraPreset(name = "Slate", settings = value))).jsonObject
    private val example = ProductionSlateSettings("Project ñ", "A", "23B", "R007", "35 mm", 42,
        ProductionSlateLocation.INTERIOR, ProductionSlateTimeOfDay.NIGHT, true, true)

    @Test fun defaultAndAllEnumsFlagsAndBoundaryTakesRoundTripExactly() {
        assertEquals(ProductionSlateSettings(), CameraSettingsStore(PresetPreferences()).load().productionSlate)
        for (location in ProductionSlateLocation.entries) for (time in ProductionSlateTimeOfDay.entries) {
            for (good in listOf(false, true)) for (increment in listOf(false, true)) for (take in listOf(1, 42, 999999)) {
                val original = CameraSettings(productionSlate = example.copy(location = location, timeOfDay = time,
                    goodTake = good, autoIncrementTake = increment, takeNumber = take), audioInputKey = com.librestatic.opencinecam.media.audio.AudioInputKey(22, "Lav", "card=17"))
                val store = CameraSettingsStore(PresetPreferences()); store.save(original)
                assertEquals(original, store.load())
                assertEquals(original.productionSlate, CameraPresetCodec.decode(document(original).toString()).settings.productionSlate)
            }
        }
    }
    @Test fun textBoundsAndUnicodeAreExactNotTrimmedOrSilentlyRepaired() {
        val texts = listOf("", " x ", "á漢字", "x".repeat(128), "\uD83D\uDE00".repeat(64))
        for (text in texts) {
            val value = example.copy(project = text, camera = text, scene = text, reel = text, lens = text)
            val original = CameraSettings(productionSlate = value)
            val store = CameraSettingsStore(PresetPreferences()); store.save(original)
            assertEquals(original, store.load())
            assertEquals(value, CameraPresetCodec.decode(document(original).toString()).settings.productionSlate)
        }
        val edits: List<(String) -> ProductionSlateSettings> = listOf(
            { example.copy(project = it) }, { example.copy(camera = it) }, { example.copy(scene = it) },
            { example.copy(reel = it) }, { example.copy(lens = it) })
        for (invalid in listOf("x".repeat(129), "a\nb", "\u0000", "\u007f", "\u0085", "\u2028", "\u202e", "\uD800", "\uDC00", "\uD800x")) {
            assertFalse(validProductionSlateText(invalid))
            for (edit in edits) assertThrows(IllegalArgumentException::class.java) { edit(invalid) }
        }
        for (take in listOf(Int.MIN_VALUE, 0, 1000000, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { example.copy(takeNumber = take) }
    }
    @Test fun malformedLocalGroupFallsBackWithoutPartiallyAcceptingSlate() {
        for (invalid in listOf(mapOf("slate-take-number" to 0), mapOf("slate-take-number" to 1000000),
            mapOf("slate-location" to "GPS"), mapOf("slate-time-of-day" to "DAWN"), mapOf("slate-camera" to "\n"),
            mapOf("slate-good-take" to "true"))) {
            val store = CameraSettingsStore(PresetPreferences(invalid + ("slate-project" to "Keep only if valid")))
            assertEquals(ProductionSlateSettings(), store.load().productionSlate)
        }
    }
    @Test fun versionFourteenHas160KeysAndVersionThirteenMigratesExactlyTenDefaults() {
        assertEquals(21, CameraPresetCodec.VERSION); assertEquals(166, CameraPresetCodec.portableKeys.size)
        assertTrue(CameraPresetCodec.portableKeys.containsAll(keys))
        val source = CameraSettings(audioMeter = AudioMeterSettings(mode = AudioMeterMode.VU, showValues = true))
        val root = document(source); val historical = root.getValue("settings").jsonObject.filterKeys { it !in PRESET_V20_KEYS && !it.startsWith("gallery-") && !it.startsWith("media-share-") && !it.startsWith("capture-naming-") && !it.startsWith("playback-") } - keys
        assertEquals(137, historical.size)
        val legacy = JsonObject(root + mapOf("version" to JsonPrimitive(13), "settings" to JsonObject(historical)))
        assertEquals(source, CameraPresetCodec.decode(legacy.toString()).settings)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(13))).toString()) }
    }
    @Test fun portableRejectsMalformedFieldsAndKeepsLocalRoutesOnMerge() {
        val root = document(CameraSettings(productionSlate = example))
        for ((key, value) in listOf("slate-project" to JsonPrimitive("x".repeat(129)), "slate-scene" to JsonPrimitive("\n"),
            "slate-take-number" to JsonPrimitive(0), "slate-take-number" to JsonPrimitive(1000000),
            "slate-take-number" to JsonPrimitive(1.5), "slate-take-number" to JsonPrimitive("1"),
            "slate-location" to JsonPrimitive("GPS"), "slate-time-of-day" to JsonPrimitive("SUNSET"),
            "slate-good-take" to JsonPrimitive("true"), "slate-auto-increment" to JsonPrimitive(1))) {
            assertThrows(key, Exception::class.java) { CameraPresetCodec.decode(JsonObject(root +
                ("settings" to JsonObject(root.getValue("settings").jsonObject + (key to value)))).toString()) }
        }
        for (key in keys) assertThrows(key, Exception::class.java) { CameraPresetCodec.decode(JsonObject(root +
            ("settings" to JsonObject(root.getValue("settings").jsonObject - key))).toString()) }
        val local = CameraSettings(audioInputDeviceId = 14, audioListeningOutputDeviceId = 15)
        val merged = CameraPresetCodec.mergeLocal(CameraPresetCodec.decode(root.toString()).settings, local)
        assertEquals(example, merged.productionSlate); assertEquals(14, merged.audioInputDeviceId); assertEquals(15, merged.audioListeningOutputDeviceId)
    }
    @Test fun captureFreezeRetainsSlateWhileLivePreferencesStillChange() {
        val current = CameraSettings(productionSlate = example)
        val next = current.copy(productionSlate = example.copy(scene = "24", takeNumber = 43, goodTake = false),
            audioMeter = AudioMeterSettings(visible = false))
        val effective = current.withLivePreferencesFrom(next)
        assertEquals(example, effective.productionSlate)
        assertEquals(next.audioMeter, effective.audioMeter)
        assertEquals(listOf("slate-good-take", "slate-scene", "slate-take-number"),
            CameraPresetCodec.differences(current, next).filter { it.startsWith("slate-") }.map { it.substringBefore(":") }.sorted())
        assertTrue("production-slate" in SettingsCatalog.search("claqueta", SettingsCategory.MEDIA) { "" })
        assertTrue("production-slate" in SettingsCatalog.search("reel", SettingsCategory.MEDIA) { "" })
    }
    @Test fun failedPreferenceWriteDoesNotPublishSlateAndReloadDoesNotIncrement() {
        var fail = false
        val persistence = object : SettingsPersistence {
            var value = CameraSettings()
            override fun load() = value
            override fun save(settings: CameraSettings) { check(!fail); value = settings }
        }
        val repository = SettingsRepository(persistence)
        repository.update { it.copy(productionSlate = example) }
        assertEquals(example, SettingsRepository(persistence).states.value.productionSlate)
        fail = true
        assertThrows(IllegalStateException::class.java) { repository.update { it.copy(productionSlate = example.copy(takeNumber = 43)) } }
        assertEquals(example, repository.states.value.productionSlate)
    }
}
