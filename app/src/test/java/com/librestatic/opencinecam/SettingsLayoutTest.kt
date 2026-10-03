/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutTest {
    @Test fun twoPanesStartAtTheExpandedClassAndYieldToLargeText() {
        assertFalse(settingsTwoPane(411f, 1f))
        assertFalse(settingsTwoPane(800f, 1f))
        assertTrue(settingsTwoPane(840f, 1f))
        assertTrue(settingsTwoPane(1280f, 1.3f))
        assertFalse(settingsTwoPane(1280f, 1.5f))
    }

    @Test fun contentIsCappedAndCentredPerWidthClass() {
        assertEquals(Float.POSITIVE_INFINITY, settingsContentMaxWidthDp(411f))
        assertEquals(SETTINGS_GUTTER_DP, settingsSideGutterDp(411f))
        // A tablet in portrait: 720 dp centred in 800, not a narrow list with most of the width empty.
        assertEquals(720f, settingsContentMaxWidthDp(800f))
        assertEquals(40f, settingsSideGutterDp(800f))
        assertEquals(1100f, settingsContentMaxWidthDp(1656f))
        assertEquals(278f, settingsSideGutterDp(1656f))
        // Never less than the normal gutter, even right at a cap.
        assertEquals(SETTINGS_GUTTER_DP, settingsSideGutterDp(720f))
    }

    @Test fun rowsGoSideBySideOnlyWhenLabelAndControlBothFit() {
        // Phone portrait: the card is too narrow, so the control sits under its label.
        assertFalse(settingsRowLayout(settingsRowWidthDp(411f)).sideBySide)
        // Tablet portrait: 688 dp rows.
        val tablet = settingsRowLayout(settingsRowWidthDp(800f))
        assertTrue(tablet.sideBySide)
        assertEquals(275.2f, tablet.controlWidthDp, 0.01f)
        // Desktop: the control column stops at its maximum, the description keeps the rest.
        val desktop = settingsRowLayout(settingsRowWidthDp(1016f))
        assertTrue(desktop.sideBySide)
        assertEquals(SETTINGS_CONTROL_MAX_DP, desktop.controlWidthDp)
        // Boundary: control minimum + gap + description minimum.
        val edge = SETTINGS_CONTROL_MIN_DP + SETTINGS_ROW_GAP_DP + SETTINGS_DESCRIPTION_MIN_DP
        assertTrue(settingsRowLayout(edge).sideBySide)
        assertFalse(settingsRowLayout(edge - 1f).sideBySide)
    }

    @Test fun tabletPortraitTilesCategoriesInTwoColumns() {
        assertEquals(1, settingsCategoryColumns(411f, twoPane = false))
        assertEquals(2, settingsCategoryColumns(800f, twoPane = false))
        assertEquals(1, settingsCategoryColumns(1280f, twoPane = true))
    }

    @Test fun searchingMarksMatchingCategoriesInsteadOfTheLastOpenPage() {
        val results = SettingsCatalog.search("zebra", null) { "" }
        val marks = settingsCategoryMarks(SettingsCategory.DIAGNOSTICS, "zebra", results, twoPane = true)
        assertNull(marks.open)
        assertEquals(setOf(SettingsCategory.MONITORING), marks.matches.keys)
        assertEquals(results.size, marks.matches.values.sum())
    }

    @Test fun withoutSearchTheOpenPageIsMarkedAndTwoPanesDefaultToCapture() {
        assertEquals(SettingsCategory.DIAGNOSTICS, settingsCategoryMarks(SettingsCategory.DIAGNOSTICS, "", emptySet(), true).open)
        assertEquals(SettingsCategory.CAPTURE, settingsCategoryMarks(null, " ", emptySet(), true).open)
        assertNull(settingsCategoryMarks(null, "", emptySet(), false).open)
        assertTrue(settingsCategoryMarks(SettingsCategory.AUDIO, "", emptySet(), false).matches.isEmpty())
    }
}
