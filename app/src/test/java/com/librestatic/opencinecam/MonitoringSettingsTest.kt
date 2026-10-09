/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam
import com.librestatic.opencinecam.camera.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MonitoringSettingsTest {
    private val options = MonitoringOptions(true, true, true, 87, true, 8, 19, 65,
        MonitorColor.GREEN, MonitorColor.RED, MonitorColor.CYAN, FalseColorPalette.HIGH_CONTRAST,
        3, 19, 68, 94, 7, MonitorAspectGuide.CINEMA, true, 85)
    @Test fun exactAllTwentyFieldsPersistAndCurrentPortableRoundTrips() {
        val original = CameraSettings(monitoring = options, photoQuality = 71, zebraEnabled = true)
        val prefs = PresetPreferences(); val store = CameraSettingsStore(prefs); store.save(original)
        assertEquals(original, store.load())
        assertEquals(20, prefs.all.keys.count { it.startsWith("monitor-") })
        assertEquals(original, CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name = "Monitor", settings = original))).settings)
        assertEquals(21, CameraPresetCodec.VERSION); assertEquals(166, CameraPresetCodec.portableKeys.size)
    }
    @Test fun versionNineKeeps107KeysAndDefaultsOnlyMonitoring() {
        val original = CameraSettings(photoQuality = 71, zebraEnabled = true)
        val root = Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name = "V9", settings = original))).jsonObject
        val old = root.getValue("settings").jsonObject.filterKeys { it !in PRESET_V20_KEYS && !it.startsWith("monitor-") && !it.startsWith("audio-recording-gain-") && !it.startsWith("audio-listening-") && !it.startsWith("audio-meter-") && !it.startsWith("slate-") && !it.startsWith("gallery-") && !it.startsWith("media-share-") && !it.startsWith("capture-naming-") && !it.startsWith("playback-") }
        assertEquals(107, old.size)
        val text = JsonObject(root + mapOf("version" to JsonPrimitive(9), "settings" to JsonObject(old))).toString()
        assertEquals(original, CameraPresetCodec.decode(text).settings)
        assertThrows(Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("version" to JsonPrimitive(9))).toString()) }
    }
    @Test fun malformedLocalGroupResetsButPresetRejectsRatherThanNormalizing() {
        val mutations = listOf("monitor-refresh-hz" to JsonPrimitive(0), "monitor-opacity" to JsonPrimitive(101),
            "monitor-zebra-high" to JsonPrimitive(5), "monitor-false-shadow" to JsonPrimitive(75),
            "monitor-safe-percent" to JsonPrimitive(49), "monitor-peaking-color" to JsonPrimitive("BLUE"),
            "monitor-waveform" to JsonPrimitive("true"), "monitor-peaking-threshold" to JsonPrimitive(2.5))
        val root = Json.parseToJsonElement(CameraPresetCodec.encode(CameraPreset(name = "Invalid", settings = CameraSettings()))).jsonObject
        for ((key, value) in mutations) {
            val values = root.getValue("settings").jsonObject + (key to value)
            assertThrows(key, Exception::class.java) { CameraPresetCodec.decode(JsonObject(root + ("settings" to JsonObject(values))).toString()) }
        }
        val prefs = PresetPreferences(); CameraSettingsStore(prefs).save(CameraSettings(monitoring = options))
        prefs.edit().putInt("monitor-refresh-hz", 11).apply()
        assertEquals(MonitoringOptions(), CameraSettingsStore(prefs).load().monitoring)
    }
    @Test fun liveMonitoringChangesDoNotChangeAcceptedCaptureIntent() {
        val original = CameraSettings(photoQuality = 71)
        val requested = original.copy(monitoring = options, photoQuality = 98)
        assertEquals(original.copy(monitoring = options), original.withLivePreferencesFrom(requested))
        assertTrue("monitoring-scopes" in SettingsCatalog.search("waveform", SettingsCategory.MONITORING) { "" })
    }
}
