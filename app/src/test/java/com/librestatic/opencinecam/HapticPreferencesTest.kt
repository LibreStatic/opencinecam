/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPreferencesTest {
    private val all = HapticPreferences(HapticLevel.FULL)

    @Test fun offNeverFires() {
        val off = all.copy(level = HapticLevel.OFF)
        for (event in HapticEvent.entries) for (recording in listOf(false, true)) assertFalse(off.allows(event, recording))
    }

    @Test fun disabledCategoryNeverFires() {
        for (category in HapticCategory.entries) {
            val prefs = when (category) {
                HapticCategory.CAPTURE -> all.copy(capture = false)
                HapticCategory.CONTROLS -> all.copy(controls = false)
                HapticCategory.FOCUS_EXPOSURE -> all.copy(focusExposure = false)
                HapticCategory.NAVIGATION -> all.copy(navigation = false)
            }
            for (event in HapticEvent.entries) assertEquals(event.category != category, prefs.allows(event, false))
        }
    }

    @Test fun subtleDropsTicksOnly() {
        val subtle = all.copy(level = HapticLevel.SUBTLE)
        for (event in HapticEvent.entries) assertEquals(event.weight == HapticWeight.EVENT, subtle.allows(event, false))
    }

    @Test fun fullFiresEverythingWhenIdle() {
        for (event in HapticEvent.entries) assertTrue(all.allows(event, false))
    }

    @Test fun muteDuringTakeKeepsOnlyCaptureEvents() {
        for (event in HapticEvent.entries) {
            val expected = event.category == HapticCategory.CAPTURE && event.weight == HapticWeight.EVENT
            assertEquals(event.name, expected, all.allows(event, true))
        }
        assertTrue(all.allows(HapticEvent.RecordStop, true))
        assertTrue(all.allows(HapticEvent.PauseToggle, true))
        assertFalse(all.allows(HapticEvent.SliderStep, true))
        assertFalse(all.allows(HapticEvent.FocusConfirmed, true))
    }

    @Test fun withoutMuteRecordingBehavesLikeIdle() {
        val open = all.copy(muteDuringTake = false)
        for (event in HapticEvent.entries) assertTrue(open.allows(event, true))
    }
}
