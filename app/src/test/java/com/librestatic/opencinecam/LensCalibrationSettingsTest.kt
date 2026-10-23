/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCalibrationSettingsTest {
    private val keys = listOf("lens-correction-video", "lens-correction-photos", "lens-correction-alignment", "lens-lock")

    @Test fun defaultsAndRoundTrip() {
        val d = CameraSettingsStore(PresetPreferences()).load()
        assertFalse(d.lensCorrectionVideo)
        assertFalse(d.lensCorrectionPhotos)
        assertTrue(d.lensCorrectionAlignment)
        assertEquals(emptyMap<String, String>(), d.lensLock)
        val store = CameraSettingsStore(PresetPreferences())
        val settings = CameraSettings(
            lensCorrectionVideo = true, lensCorrectionPhotos = true, lensCorrectionAlignment = false,
            lensLock = mapOf("0" to "2", "1" to "4"),
        )
        store.save(settings)
        assertEquals(settings, store.load())
    }

    @Test fun storedUnderTheirOwnKeys() {
        val memory = PresetPreferences()
        CameraSettingsStore(memory).save(CameraSettings(lensCorrectionVideo = true, lensLock = mapOf("1" to "4", "0" to "2")))
        assertEquals(true, memory.all["lens-correction-video"])
        assertEquals("0=2,1=4", memory.all["lens-lock"])
    }

    @Test fun malformedLensLockIsEmpty() {
        for (bad in listOf("garbage", "0=", "=2", "0=2,", "0=2=3", "0=2,0=3", "0=a b", ",")) {
            val memory = PresetPreferences()
            CameraSettingsStore(memory).save(CameraSettings())
            memory.edit().putString("lens-lock", bad).apply()
            assertEquals(bad, emptyMap<String, String>(), CameraSettingsStore(memory).load().lensLock)
        }
    }

    @Test fun areNotPartOfAPortablePreset() {
        for (key in keys) assertFalse(key, key in CameraPresetCodec.portableKeys)
        val device = CameraSettings(lensCorrectionVideo = true, lensCorrectionPhotos = true, lensCorrectionAlignment = false, lensLock = mapOf("0" to "2"))
        val decoded = CameraPresetCodec.decode(CameraPresetCodec.encode(CameraPreset(name = "Dark", settings = device))).settings
        assertFalse(decoded.lensCorrectionVideo)
        assertFalse(decoded.lensCorrectionPhotos)
        assertTrue(decoded.lensCorrectionAlignment)
        assertTrue(decoded.lensLock.isEmpty())
    }

    @Test fun applyingAPresetKeepsTheDeviceValues() {
        val device = CameraSettings(lensCorrectionVideo = true, lensCorrectionPhotos = true, lensCorrectionAlignment = false, lensLock = mapOf("0" to "2"))
        val merged = CameraPresetCodec.mergeLocal(CameraSettings(), device)
        assertTrue(merged.lensCorrectionVideo)
        assertTrue(merged.lensCorrectionPhotos)
        assertFalse(merged.lensCorrectionAlignment)
        assertEquals(mapOf("0" to "2"), merged.lensLock)
    }

    @Test fun aRunningTakeKeepsItsLensSetup() {
        val running = CameraSettings(lensCorrectionVideo = true, lensLock = mapOf("0" to "2"))
        val next = CameraSettings(lensCorrectionVideo = false, lensCorrectionPhotos = true, lensCorrectionAlignment = false, lensLock = mapOf("0" to "3"))
        val live = running.withLivePreferencesFrom(next)
        assertTrue(live.lensCorrectionVideo)
        assertFalse(live.lensCorrectionPhotos)
        assertTrue(live.lensCorrectionAlignment)
        assertEquals(mapOf("0" to "2"), live.lensLock)
    }
}
