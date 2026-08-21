/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import android.view.OrientationEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalOrientationTrackerTest {
    @Test fun quantizesStableQuadrants() {
        assertEquals(0, PhysicalOrientationTracker.quantizeStableOrientation(5))
        assertEquals(90, PhysicalOrientationTracker.quantizeStableOrientation(88))
        assertEquals(180, PhysicalOrientationTracker.quantizeStableOrientation(191))
        assertEquals(270, PhysicalOrientationTracker.quantizeStableOrientation(274))
        assertEquals(0, PhysicalOrientationTracker.quantizeStableOrientation(359))
    }

    @Test fun rejectsUnknownAndBoundaryAngles() {
        assertNull(PhysicalOrientationTracker.quantizeStableOrientation(OrientationEventListener.ORIENTATION_UNKNOWN))
        assertNull(PhysicalOrientationTracker.quantizeStableOrientation(45))
    }
}
