/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_DEVICE
import android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_DISABLED
import android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
import android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_SERVICE
import android.hardware.camera2.CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraReopenPolicyTest {
    @Test fun deviceAndServiceErrorsAreReopenedTwiceInARowThenFail() {
        val policy = CameraReopenPolicy()
        assertTrue(policy.shouldReopen(ERROR_CAMERA_DEVICE, takeInProgress = false, nowMs = 1_000))
        assertTrue(policy.shouldReopen(ERROR_CAMERA_SERVICE, takeInProgress = false, nowMs = 5_000))
        // The second reopen failed too: the dialog.
        assertFalse(policy.shouldReopen(ERROR_CAMERA_DEVICE, takeInProgress = false, nowMs = 9_000))
        assertFalse(policy.shouldReopen(ERROR_CAMERA_DEVICE, takeInProgress = false, nowMs = 20_000))
    }

    @Test fun aFailureLongAfterTheLastStartsANewRow() {
        val policy = CameraReopenPolicy()
        assertTrue(policy.shouldReopen(ERROR_CAMERA_DEVICE, false, 1_000))
        assertTrue(policy.shouldReopen(ERROR_CAMERA_DEVICE, false, 2_000))
        assertFalse(policy.shouldReopen(ERROR_CAMERA_DEVICE, false, 3_000))
        assertTrue(policy.shouldReopen(ERROR_CAMERA_DEVICE, false, 3_000 + CameraReopenPolicy.RESET_AFTER_MS))
    }

    @Test fun errorsThatWouldRepeatAreNotRetried() {
        val policy = CameraReopenPolicy()
        for (error in listOf(ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE, ERROR_CAMERA_DISABLED)) {
            assertFalse("$error", policy.shouldReopen(error, takeInProgress = false, nowMs = 1_000))
        }
        // And they used no attempt.
        assertTrue(policy.shouldReopen(ERROR_CAMERA_DEVICE, takeInProgress = false, nowMs = 1_100))
    }

    @Test fun aTakeInProgressIsLeftToTheFailurePath() {
        val policy = CameraReopenPolicy()
        assertFalse(policy.shouldReopen(ERROR_CAMERA_DEVICE, takeInProgress = true, nowMs = 1_000))
        assertTrue(policy.shouldReopen(ERROR_CAMERA_DEVICE, takeInProgress = false, nowMs = 2_000))
    }
}
