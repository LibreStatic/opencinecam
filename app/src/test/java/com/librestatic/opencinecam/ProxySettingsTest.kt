/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProxySettingsTest {
    private val keys = setOf("proxy-max-long-edge", "proxy-video-bitrate-mbps")

    @Test fun defaultsAndAllFifteenChoicesPersistWithoutAdditionalFlags() {
        assertEquals(ProxySettings(1280, 3), CameraSettings().proxy)
        assertEquals(ProxySettings(), CameraSettingsStore(PresetPreferences()).load().proxy)
        for (edge in listOf(640, 1280, 1920)) for (bitrate in listOf(1, 2, 3, 5, 8)) {
            val value = CameraSettings(proxy = ProxySettings(edge, bitrate), geotaggingEnabled = true,
                productionSlate = ProductionSlateSettings(scene = "Keep"), audioInputKey = com.librestatic.opencinecam.media.audio.AudioInputKey(22, "Lav", "card=29"))
            val memory = PresetPreferences(); val store = CameraSettingsStore(memory)
            store.save(value)
            assertEquals(value, store.load())
            assertEquals(keys, memory.all.keys.filter { it.startsWith("proxy-") }.toSet())
            assertEquals(edge, memory.all["proxy-max-long-edge"])
            assertEquals(bitrate, memory.all["proxy-video-bitrate-mbps"])
        }
    }

    @Test fun constructorRejectsUnsupportedValuesWithoutClamping() {
        for (edge in listOf(Int.MIN_VALUE, -1, 0, 639, 641, 1279, 1281, 1919, 1921, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ProxySettings(maxLongEdge = edge) }
        }
        for (bitrate in listOf(Int.MIN_VALUE, -1, 0, 4, 6, 7, 9, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ProxySettings(videoBitrateMbps = bitrate) }
        }
    }

    @Test fun corruptTypeOrValueDefaultsWholeProxyGroupWithoutChangingOtherSettings() {
        for (bad in listOf(
            mapOf("proxy-max-long-edge" to 641, "proxy-video-bitrate-mbps" to 8),
            mapOf("proxy-max-long-edge" to 1920, "proxy-video-bitrate-mbps" to 4),
            mapOf("proxy-max-long-edge" to "1280", "proxy-video-bitrate-mbps" to 8),
            mapOf("proxy-max-long-edge" to 1920, "proxy-video-bitrate-mbps" to "3"),
            mapOf("proxy-max-long-edge" to true), mapOf("proxy-video-bitrate-mbps" to 3L),
        )) {
            val loaded = CameraSettingsStore(PresetPreferences(bad + ("geotagging-enabled" to true))).load()
            assertEquals(ProxySettings(), loaded.proxy)
            assertTrue(loaded.geotaggingEnabled)
        }
    }

    @Test fun presetsExcludeProxyKeysRejectInjectionAndPreserveCurrentLocalProxy() {
        val original = CameraPreset(name = "Local proxy", settings = CameraSettings())
        val changed = original.copy(settings = original.settings.copy(proxy = ProxySettings(640, 8)))
        assertEquals(CameraPresetCodec.encode(original), CameraPresetCodec.encode(changed))
        assertEquals(20, CameraPresetCodec.VERSION)
        assertEquals(164, CameraPresetCodec.portableKeys.size)
        assertTrue(CameraPresetCodec.portableKeys.intersect(keys).isEmpty())
        assertTrue(CameraPresetCodec.differences(original.settings, changed.settings).isEmpty())
        assertEquals(ProxySettings(), CameraPresetCodec.decode(CameraPresetCodec.encode(changed)).settings.proxy)
        val root = Json.parseToJsonElement(CameraPresetCodec.encode(original)).jsonObject
        for (key in keys) {
            val injected = JsonObject(root + ("settings" to JsonObject(root.getValue("settings").jsonObject + (key to JsonPrimitive(1280)))))
            assertThrows(Exception::class.java) { CameraPresetCodec.decode(injected.toString()) }
        }
        for (current in listOf(ProxySettings(), ProxySettings(640, 8))) for (incoming in listOf(ProxySettings(), ProxySettings(1920, 1))) {
            val merged = CameraPresetCodec.mergeLocal(CameraSettings(proxy = incoming, videoBitrateMbps = 40), CameraSettings(proxy = current))
            assertEquals(current, merged.proxy)
            assertEquals(40, merged.videoBitrateMbps)
        }
    }

    @Test fun liveProxyPreferenceAndRepositoryUpdatePreserveCaptureConfiguration() {
        val initial = CameraSettings(geotaggingEnabled = true, productionSlate = ProductionSlateSettings(project = "Keep"), audioInputDeviceId = 29)
        val requested = initial.copy(proxy = ProxySettings(1920, 8), videoBitrateMbps = 40,
            audioInputDeviceId = 35, productionSlate = ProductionSlateSettings(project = "Next"))
        val live = initial.withLivePreferencesFrom(requested)
        assertEquals(initial.copy(proxy = requested.proxy), live)
        var saved = initial
        val repository = SettingsRepository(object : SettingsPersistence {
            override fun load() = saved
            override fun save(settings: CameraSettings) { saved = settings }
        })
        repository.update { it.copy(proxy = requested.proxy) }
        assertEquals(initial.copy(proxy = requested.proxy), repository.states.value)
        assertEquals(repository.states.value, saved)
    }

    @Test fun searchFindsBilingualCaptureEntry() {
        for (query in listOf("proxy", "proxies", "edición", "resolution", "resolucion", "bitrate", "manual")) {
            assertTrue(query, "proxy" in SettingsCatalog.search(query, SettingsCategory.MEDIA) { "" })
        }
        assertFalse("proxy" in SettingsCatalog.search("proxy", SettingsCategory.AUDIO) { "" })
        assertEquals(1, SettingsCatalog.entries.count { it.id == "proxy" })
    }

    @Test fun failedStoreDoesNotPublishProxyChanges() {
        val repository = SettingsRepository(object : SettingsPersistence {
            override fun load() = CameraSettings()
            override fun save(settings: CameraSettings) { error("write failed") }
        })
        assertThrows(IllegalStateException::class.java) { repository.update { it.copy(proxy = ProxySettings(640, 1)) } }
        assertEquals(ProxySettings(), repository.states.value.proxy)
    }
}
