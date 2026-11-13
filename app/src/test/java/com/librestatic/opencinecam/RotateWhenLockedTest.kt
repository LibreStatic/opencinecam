/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RotateWhenLockedTest {
    @Test fun onFollowsTheSensorOffHonoursTheSystemLock() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, requestedOrientationFor(rotateWhenLocked = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, requestedOrientationFor(rotateWhenLocked = false))
    }
}
