/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.ZoomLensSwitchMode
import org.junit.Assert.*
import org.junit.Test

/** Lens switching is a persisted preset key, so Settings must expose a row that can change it. */
class CameraSettingsZoomLensTest {
    @Test fun lensSwitchingHasASearchableCaptureRow() {
        assertEquals(1, SettingsCatalog.entries.count { it.id == "zoom-lens" })
        for (query in listOf("lens", "lente", "zoom automatico")) {
            assertTrue(query, "zoom-lens" in SettingsCatalog.search(query, SettingsCategory.CAPTURE) { "" })
        }
    }
    @Test fun everyLensSwitchModeSurvivesPersistence() {
        for (mode in ZoomLensSwitchMode.entries) {
            val memory = PresetPreferences()
            CameraSettingsStore(memory).save(CameraSettings(zoomLensSwitchMode = mode))
            assertEquals(mode, CameraSettingsStore(memory).load().zoomLensSwitchMode)
        }
    }
}
