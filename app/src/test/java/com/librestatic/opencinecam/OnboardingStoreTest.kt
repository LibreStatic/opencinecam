/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingStoreTest {
    @Test
    fun leavingWithoutCameraLandsOnPermissions() {
        assertEquals(OnboardingPage.PERMISSIONS, onboardingExitTarget(cameraGranted = false))
    }

    @Test
    fun leavingWithCameraFinishes() {
        assertNull(onboardingExitTarget(cameraGranted = true))
    }
}
