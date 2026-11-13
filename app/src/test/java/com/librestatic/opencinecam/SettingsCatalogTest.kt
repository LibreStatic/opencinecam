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
    @Test fun hapticsAreSearchableInBothLanguages() {
        for (query in listOf("vibracion", "haptic", "vibración")) assertTrue(query, "haptics" in SettingsCatalog.search(query, null) { "" })
        assertTrue("haptics" in SettingsCatalog.search("", SettingsCategory.CONTROLS) { "" })
    }
    @Test fun volumeKeyGesturesAreSearchableInEveryLanguage() {
        for (query in listOf("volume hold", "doble pulsación", "both keys", "deux touches", "doppio", "beide Tasten", "volumen zoom", "pair ISO", "obturador", "Fokus"))
            assertTrue(query, "operator-controls" in SettingsCatalog.search(query, null) { "" })
    }
    @Test fun rawVideoIsSearchableByTheWordsEachLocaleShows() {
        for (query in listOf("RAW", "calificar", "qualify", "compression", "qualifizieren", "qualifier", "qualificare", "qualificar", "Komprimierung", "compressão")) {
            assertTrue(query, "raw-video" in SettingsCatalog.search(query, null) { "" })
        }
    }
    @Test fun rawVideoRowCountsOnlyWhereItIsDrawn() {
        assertTrue(rawVideoSettingsShown(ModeGateState.AVAILABLE))
        assertTrue(rawVideoSettingsShown(ModeGateState.CANDIDATE))
        assertFalse(rawVideoSettingsShown(ModeGateState.FAILED))
        assertFalse(rawVideoSettingsShown(ModeGateState.UNSUPPORTED))
        assertFalse(rawVideoSettingsShown(null))
    }
    @Test fun rotateWhenLockedIsSearchable() {
        for (query in listOf("rotation", "girar", "rotación", "drehen")) assertTrue(query, "rotate-when-locked" in SettingsCatalog.search(query, null) { "" })
        assertTrue("rotate-when-locked" in SettingsCatalog.search("", SettingsCategory.CONTROLS) { "" })
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

    @Test
    fun aShortWordMustStartAWord() {
        val found = SettingsCatalog.search("raw", null) { "" }
        assertTrue("raw-video" in found)
        assertTrue("photo-format" in found)
        assertTrue("lens-lock" !in found)
        assertTrue("lens-lock" in SettingsCatalog.search("ultrawide", null) { "" })
        assertTrue("lens-lock" in SettingsCatalog.search("wide", null) { "" })
    }

    @Test
    fun titleMatchesAreTheSettingsNamedByTheQuery() {
        val titles = mapOf(R.string.raw_video_config_title to "RAW video", R.string.photo_aspect_title to "Photo aspect and crop")
        val label = { id: Int -> titles[id] ?: "" }
        assertEquals(setOf("raw-video"), SettingsCatalog.titleMatches("raw", label))
        assertEquals(setOf("raw-video"), SettingsCatalog.titleMatches("video RAW", label))
        assertEquals(setOf("photo-aspect"), SettingsCatalog.titleMatches("aspect", label))
        assertTrue(SettingsCatalog.titleMatches("  ", label).isEmpty())
        // A keyword match is found, but does not lead.
        assertTrue("photo-format" in SettingsCatalog.search("raw", null, label))
    }
}
