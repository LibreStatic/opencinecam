/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroShutterLagSettingsTest {
    @Test fun defaultsOnAndPersistsLocally() {
        assertTrue(CameraSettings().zeroShutterLag)
        assertTrue(CameraSettingsStore(PresetPreferences()).load().zeroShutterLag)
        val store = CameraSettingsStore(PresetPreferences())
        store.save(CameraSettings(zeroShutterLag = false))
        assertEquals(false, store.load().zeroShutterLag)
    }

    @Test fun presetApplyKeepsTheLocalChoice() {
        assertEquals(false, CameraPresetCodec.mergeLocal(CameraSettings(), CameraSettings(zeroShutterLag = false)).zeroShutterLag)
        assertTrue("zero-shutter-lag" !in CameraPresetCodec.portableKeys)
    }
}
