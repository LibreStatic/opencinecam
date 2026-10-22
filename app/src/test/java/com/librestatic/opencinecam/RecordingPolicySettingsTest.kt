/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.core.model.PolicyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPolicySettingsTest {
    /** PresetPreferences seeds the migration flags; a real fresh install holds nothing. */
    private fun freshInstall() = PresetPreferences().also {
        it.edit().remove("audio-aac-log-migrated-v1").remove("mode-selector-carousel-migrated-v1").remove("recording-policy-migrated-v1").apply()
    }

    @Test fun strictByDefaultOnAFreshInstall() {
        assertEquals(PolicyMode.STRICT, CameraSettings().recordingPolicy)
        val memory = freshInstall()
        assertEquals(PolicyMode.STRICT, CameraSettingsStore(memory).load().recordingPolicy)
        // The decision sticks: later loads, now that migrations wrote keys, stay Strict.
        assertEquals(PolicyMode.STRICT, CameraSettingsStore(memory).load().recordingPolicy)
        assertEquals(true, memory.all["recording-policy-migrated-v1"])
    }

    @Test fun anExistingInstallMigratesToAdaptive() {
        val memory = freshInstall()
        memory.edit().putInt("onboarding-version", 1).apply()
        assertEquals(PolicyMode.ADAPTIVE, CameraSettingsStore(memory).load().recordingPolicy)
        assertEquals(PolicyMode.ADAPTIVE, CameraSettingsStore(memory).load().recordingPolicy)
    }

    @Test fun anyStoredSettingMarksAnExistingInstall() {
        val memory = freshInstall()
        memory.edit().putBoolean("audio-enabled", true).apply()
        assertEquals(PolicyMode.ADAPTIVE, CameraSettingsStore(memory).load().recordingPolicy)
    }

    @Test fun theUsersChoiceSurvivesTheMigrationAndRoundTrips() {
        val store = CameraSettingsStore(PresetPreferences())
        store.save(CameraSettings(recordingPolicy = PolicyMode.ADAPTIVE))
        assertEquals(PolicyMode.ADAPTIVE, store.load().recordingPolicy)
        store.save(CameraSettings(recordingPolicy = PolicyMode.STRICT))
        assertEquals(PolicyMode.STRICT, store.load().recordingPolicy)
    }

    @Test fun storedUnderItsOwnKey() {
        val memory = PresetPreferences()
        CameraSettingsStore(memory).save(CameraSettings(recordingPolicy = PolicyMode.ADAPTIVE))
        assertEquals("ADAPTIVE", memory.all["recording-policy"])
    }

    @Test fun isNotPartOfAPortablePreset() {
        assertFalse("recording-policy" in CameraPresetCodec.portableKeys)
        val decoded = CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name = "Dim", settings = CameraSettings(recordingPolicy = PolicyMode.ADAPTIVE))))
        assertEquals(PolicyMode.STRICT, decoded.settings.recordingPolicy)
    }

    @Test fun applyingAPresetKeepsTheDeviceChoice() {
        val merged = CameraPresetCodec.mergeLocal(CameraSettings(), CameraSettings(recordingPolicy = PolicyMode.ADAPTIVE))
        assertEquals(PolicyMode.ADAPTIVE, merged.recordingPolicy)
    }

    @Test fun isStructuralSoARunningTakeKeepsItsPolicy() {
        val running = CameraSettings(recordingPolicy = PolicyMode.STRICT)
        assertEquals(PolicyMode.STRICT, running.withLivePreferencesFrom(CameraSettings(recordingPolicy = PolicyMode.ADAPTIVE)).recordingPolicy)
    }

    @Test fun hasACatalogEntryInTheRecordingCategory() {
        assertTrue(SettingsCatalog.entries.any { it.id == RECORDING_POLICY_SETTING_ID && it.category == SettingsCategory.RECORDING })
    }
}
