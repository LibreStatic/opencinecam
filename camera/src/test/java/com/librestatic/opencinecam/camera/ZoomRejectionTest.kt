/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomRejectionTest {

    @Test
    fun invalidZoomKeyArgumentAfterZoomChangeIsARejection() {
        assertTrue(isZoomRejection(IllegalArgumentException("CONTROL_ZOOM_RATIO 12.0 out of range"), zoomChanged = true))
        assertTrue(isZoomRejection(IllegalArgumentException("Invalid SCALER_CROP_REGION"), zoomChanged = true))
        assertTrue(isZoomRejection(IllegalArgumentException("zoom ratio unsupported"), zoomChanged = true))
    }

    @Test
    fun zoomWordedFailureWithoutZoomChangeIsARealFailure() {
        assertFalse(isZoomRejection(IllegalArgumentException("CONTROL_ZOOM_RATIO out of range"), zoomChanged = false))
    }

    @Test
    fun nonArgumentFailuresMentioningZoomAreRealFailures() {
        assertFalse(isZoomRejection(IllegalStateException("Session closed during zoom"), zoomChanged = true))
        assertFalse(isZoomRejection(RuntimeException("zoom"), zoomChanged = true))
    }

    @Test
    fun unrelatedInvalidArgumentIsARealFailure() {
        assertFalse(isZoomRejection(IllegalArgumentException("Surface was abandoned"), zoomChanged = true))
        assertFalse(isZoomRejection(IllegalArgumentException(), zoomChanged = true))
    }
}
