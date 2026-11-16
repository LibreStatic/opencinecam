/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.settings.AUDIO_SYNC_OFFSET_RANGE_MS
import com.librestatic.opencinecam.settings.AUDIO_SYNC_OFFSET_SETTING_ID
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.settings.CameraSettingsStore
import com.librestatic.opencinecam.settings.PresetPreferences
import com.librestatic.opencinecam.settings.SettingsCatalog
import com.librestatic.opencinecam.settings.SettingsCategory
import com.librestatic.opencinecam.settings.withLivePreferencesFrom
import org.junit.Assert.*
import org.junit.Test

/** The manual audio sync offset (PLAN-074 decision 12): a device-local, signed millisecond setting. */
class AudioSyncOffsetSettingsTest {
    @Test fun defaultsToNoOffsetAndAnInstallWithoutTheKeyLoadsZero() {
        assertEquals(0, CameraSettings().audioSyncOffsetMs)
        assertEquals(0, CameraSettingsStore(PresetPreferences(mapOf("audio-recording-gain-db" to 6))).load().audioSyncOffsetMs)
        assertEquals(-500..500, AUDIO_SYNC_OFFSET_RANGE_MS)
    }

    @Test fun everyEdgeAndSignRoundTripsThroughTheStore() {
        for (offset in listOf(-500, -499, -1, 0, 1, 137, 500)) {
            val prefs = PresetPreferences(); val store = CameraSettingsStore(prefs)
            val original = CameraSettings(audioSyncOffsetMs = offset)
            store.save(original)
            assertEquals(offset, prefs.all["audio-sync-offset-ms"])
            assertEquals(original, store.load())
        }
    }

    @Test fun aStoredValueOutOfRangeLoadsCoercedIntoIt() {
        for ((stored, loaded) in listOf(-501 to -500, Int.MIN_VALUE to -500, 501 to 500, Int.MAX_VALUE to 500))
            assertEquals(loaded, CameraSettingsStore(PresetPreferences(mapOf("audio-sync-offset-ms" to stored))).load().audioSyncOffsetMs)
    }

    @Test fun theModelRejectsAValueOutOfRange() {
        for (offset in listOf(-501, 501)) assertThrows(IllegalArgumentException::class.java) { CameraSettings(audioSyncOffsetMs = offset) }
    }

    @Test fun aRunningTakeKeepsItsOffsetSoAChangeIsPending() {
        val current = CameraSettings(audioSyncOffsetMs = 20)
        val effective = current.withLivePreferencesFrom(current.copy(audioSyncOffsetMs = -40, zebraEnabled = true))
        assertEquals(20, effective.audioSyncOffsetMs)
        assertTrue(effective.zebraEnabled)
    }

    @Test fun searchFindsTheOffsetInTheAudioCategory() {
        for (query in listOf("sync", "latency", "desfase", "retardo", "bluetooth", "lip"))
            assertTrue(query, AUDIO_SYNC_OFFSET_SETTING_ID in SettingsCatalog.search(query, null) { "" })
        assertTrue(AUDIO_SYNC_OFFSET_SETTING_ID in SettingsCatalog.search("", SettingsCategory.AUDIO) { "" })
        assertEquals(SettingsCategory.AUDIO, SettingsCatalog.entries.single { it.id == AUDIO_SYNC_OFFSET_SETTING_ID }.category)
    }
}
