/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsLockSettingsTest {
    @Test fun onByDefaultAndRoundTrips() {
        assertTrue(CameraSettings().fpsLock)
        assertTrue(CameraSettingsStore(PresetPreferences()).load().fpsLock)
        val store = CameraSettingsStore(PresetPreferences())
        val settings = CameraSettings(fpsLock = false)
        store.save(settings)
        assertFalse(store.load().fpsLock)
        assertEquals(settings, store.load())
    }

    @Test fun storedUnderItsOwnKey() {
        val memory = PresetPreferences()
        CameraSettingsStore(memory).save(CameraSettings(fpsLock = false))
        assertEquals(false, memory.all["video-fps-lock"])
    }

    @Test fun isNotPartOfAPortablePreset() {
        assertFalse("video-fps-lock" in CameraPresetCodec.portableKeys)
        val decoded = CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name = "Dark", settings = CameraSettings(fpsLock = false))))
        assertTrue(decoded.settings.fpsLock)
    }

    @Test fun applyingAPresetKeepsTheDeviceChoice() {
        assertFalse(CameraPresetCodec.mergeLocal(CameraSettings(), CameraSettings(fpsLock = false)).fpsLock)
        assertTrue(CameraPresetCodec.mergeLocal(CameraSettings(fpsLock = false), CameraSettings(fpsLock = true)).fpsLock)
    }

    @Test fun aRunningTakeFollowsTheSwitch() {
        assertFalse(CameraSettings().withLivePreferencesFrom(CameraSettings(fpsLock = false)).fpsLock)
    }

    @Test fun onlyVideoLogAndHlgOfferTheKey() {
        for (mode in CaptureMode.entries) {
            val expected = mode == CaptureMode.VIDEO || mode == CaptureMode.LOG || mode == CaptureMode.HLG
            assertEquals("$mode", expected, fpsLockMode(mode))
        }
    }

    @Test fun theKeyNeedsASupportingCamera() {
        // Without a descriptor (or on a plain JVM, below Android 16) the key is hidden and the engine is not asked.
        val state = CameraUiState(selectedMode = CaptureMode.VIDEO)
        assertFalse(fpsLockKeyVisible(state))
        assertFalse(fpsLockActive(state, CameraSettings()))
    }

    @Test fun switchedOffNeverRunsTheGuard() {
        assertFalse(fpsLockActive(CameraUiState(selectedMode = CaptureMode.VIDEO), CameraSettings(fpsLock = false)))
    }
}
