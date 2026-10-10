/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ZoomSettingsOverrideTest {
    @Test
    fun eligibleOnlyWithApi34RatioAdvertisedAndNotHighSpeed() {
        assertTrue(ZoomSettingsOverride.eligible(34, true, true, false))
        assertFalse(ZoomSettingsOverride.eligible(33, true, true, false))
        assertFalse(ZoomSettingsOverride.eligible(34, false, true, false))
        assertFalse(ZoomSettingsOverride.eligible(34, true, false, false))
        assertFalse(ZoomSettingsOverride.eligible(34, true, true, true))
    }

    @Test
    fun armsOnlyOnRealChange() {
        assertTrue(ZoomSettingsOverride.shouldArm(true, 1f, 1.5f))
        assertFalse(ZoomSettingsOverride.shouldArm(true, 1f, 1f))
        assertFalse(ZoomSettingsOverride.shouldArm(false, 1f, 2f))
    }

    @Test
    fun releasesAfterIdleOnceRatioReached() {
        assertFalse(ZoomSettingsOverride.shouldRelease(false, 1000, 0, 2f, 2f, true))
        assertFalse(ZoomSettingsOverride.shouldRelease(true, 100, 0, 2f, 2f, true))
        assertTrue(ZoomSettingsOverride.shouldRelease(true, 250, 0, 2f, 2f, false))
        assertTrue(ZoomSettingsOverride.shouldRelease(true, 250, 0, 1.2f, 2f, true))
    }

    @Test
    fun keepsOverrideWhileRatioLagsUntilHardCap() {
        assertFalse(ZoomSettingsOverride.shouldRelease(true, 250, 0, 1.2f, 2f, false))
        assertTrue(ZoomSettingsOverride.shouldRelease(true, 1000, 0, 1.2f, 2f, false))
    }
}
