/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class SettingsCatalogTest {
    @Test fun keysAreUnique() {
        assertEquals(SettingsCatalog.entries.size, SettingsCatalog.entries.map { it.id }.toSet().size)
    }
    @Test fun searchHandlesAccentsAndAliases() {
        for (query in listOf("intensidad", "INTENSITY", "antórcha", "LOG luz")) {
            assertTrue(query, "torch" in SettingsCatalog.search(query, null) { "" })
        }
    }
    @Test fun categoryFiltersAndUnknownSearchIsEmpty() {
        assertFalse("torch" in SettingsCatalog.search("", SettingsCategory.AUDIO) { "" })
        assertTrue(SettingsCatalog.search("no-such-setting", null) { "" }.isEmpty())
    }
    @Test fun liveChangesLeaveRecordingFormatAndTimecodeUntouched() {
        val old = CameraSettings()
        val next = old.copy(videoBitrateMbps = 40, flashEnabled = true, torchStrengthLevel = 2, timecodeEnabled = true)
        val effective = old.withLivePreferencesFrom(next)
        assertEquals(old.videoBitrateMbps, effective.videoBitrateMbps)
        assertEquals(old.timecodeEnabled, effective.timecodeEnabled)
        assertTrue(effective.flashEnabled)
        assertEquals(2, effective.torchStrengthLevel)
    }
}
