/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.settings

import com.librestatic.opencinecam.capture.StartupMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupModeMigrationTest {
    /** PresetPreferences seeds the migration flags; an install that has not run this migration lacks it. */
    private fun unmigrated(stored: StartupMode? = null) = PresetPreferences().also { prefs ->
        prefs.edit().remove("startup-mode-last-migrated-v1").apply()
        if (stored != null) prefs.edit().putString("operator-startup-mode", stored.name).apply()
    }

    private fun startup(prefs: android.content.SharedPreferences) = CameraSettingsStore(prefs).load().operation.startupMode

    @Test fun aFreshInstallStartsInTheLastMode() {
        val prefs = unmigrated()
        assertEquals(StartupMode.LAST, startup(prefs))
        assertEquals(true, prefs.all["startup-mode-last-migrated-v1"])
    }

    @Test fun theOldPhotoDefaultBecomesLastOnce() {
        val prefs = unmigrated(StartupMode.PHOTO)
        assertEquals(StartupMode.LAST, startup(prefs))
        assertEquals("LAST", prefs.all["operator-startup-mode"])
        // Photo chosen after the migration is the user's and stays.
        CameraSettingsStore(prefs).let { store -> store.save(store.load().let { it.copy(operation = it.operation.copy(startupMode = StartupMode.PHOTO)) }) }
        assertEquals(StartupMode.PHOTO, startup(prefs))
    }

    @Test fun videoAndLastWereChosenAndStay() {
        assertEquals(StartupMode.VIDEO, startup(unmigrated(StartupMode.VIDEO)))
        assertEquals(StartupMode.LAST, startup(unmigrated(StartupMode.LAST)))
    }

    @Test fun aPresetKeepsItsPhotoStartup() {
        val preset = CameraPreset(name = "Stills", settings = CameraSettings().let { it.copy(operation = it.operation.copy(startupMode = StartupMode.PHOTO)) })
        assertEquals(StartupMode.PHOTO, CameraPresetCodec.decode(CameraPresetCodec.encode(preset)).settings.operation.startupMode)
    }
}
