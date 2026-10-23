/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.CaptureMode
import com.librestatic.opencinecam.LensLockOption
import com.librestatic.opencinecam.camera.LensLockBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensLockGateTest {
    private val razr = listOf(
        LensLockOption("3", 0.55f, 1.826f),
        LensLockOption("2", 1.0f, 6.57f),
        LensLockOption("5", 3.0f, 13.3f),
    )

    @Test fun aSavedOptionBindsTheLogicalCamera() {
        assertEquals(LensLockBinding("0", "5", 3.0f), lensLockBindingFor("0", razr, "5", calibrating = false, highSpeed = false))
    }

    @Test fun noSettingOrAnUnknownLensStaysUnlocked() {
        assertNull(lensLockBindingFor("0", razr, null, calibrating = false, highSpeed = false))
        assertNull(lensLockBindingFor("0", razr, "7", calibrating = false, highSpeed = false))
        assertNull(lensLockBindingFor(null, razr, "5", calibrating = false, highSpeed = false))
    }

    @Test fun fewerThanTwoOptionsIsNoLensLock() {
        assertNull(lensLockBindingFor("0", razr.take(1), "3", calibrating = false, highSpeed = false))
        assertNull(lensLockBindingFor("1", emptyList(), "3", calibrating = false, highSpeed = false))
    }

    @Test fun aCalibrationRunOpensTheLogicalCamera() {
        assertNull(lensLockBindingFor("0", razr, "5", calibrating = true, highSpeed = false))
    }

    @Test fun highSpeedOpensTheLogicalCamera() {
        assertNull(lensLockBindingFor("0", razr, "5", calibrating = false, highSpeed = true))
    }

    @Test fun nightPhotoOpensTheLogicalCamera() {
        assertNull(lensLockBindingFor("0", razr, "5", calibrating = false, highSpeed = false, night = true))
    }

    @Test fun highSpeedFollowsTheModeAndItsProfile() {
        assertTrue(lensLockHighSpeed(CaptureMode.SLOW_MOTION, videoProfileHighSpeed = false, logProfileHighSpeed = false))
        assertTrue(lensLockHighSpeed(CaptureMode.VIDEO, videoProfileHighSpeed = true, logProfileHighSpeed = false))
        assertTrue(lensLockHighSpeed(CaptureMode.LOG, videoProfileHighSpeed = false, logProfileHighSpeed = true))
        assertFalse(lensLockHighSpeed(CaptureMode.VIDEO, videoProfileHighSpeed = false, logProfileHighSpeed = true))
        assertFalse(lensLockHighSpeed(CaptureMode.PHOTO, videoProfileHighSpeed = true, logProfileHighSpeed = false))
        assertFalse(lensLockHighSpeed(CaptureMode.HLG, videoProfileHighSpeed = false, logProfileHighSpeed = false))
    }
}
