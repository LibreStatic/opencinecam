/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroShutterLagTest {
    private val base = ZeroShutterLagContext(
        requested = true, supported = true, singleStill = true, rawOutput = false,
        manualExposure = false, flashActive = false, needsPrecapture = false,
    )

    @Test fun plainAutoStillIsEligible() = assertTrue(zeroShutterLagEligible(base))

    @Test fun everyBlockerDisablesIt() {
        for (blocked in listOf(
            base.copy(requested = false), base.copy(supported = false), base.copy(singleStill = false),
            base.copy(rawOutput = true), base.copy(manualExposure = true), base.copy(flashActive = true),
            base.copy(needsPrecapture = true),
        )) assertFalse(blocked.toString(), zeroShutterLagEligible(blocked))
    }

    @Test fun flashPlanActivityFollowsFlashMode() {
        assertFalse(photoFlashPlanActive(PhotoFlashResolution.Plan(null, 0, null, false)))
        assertTrue(photoFlashPlanActive(PhotoFlashResolution.Plan(null, 1, null, true)))
        assertTrue(photoFlashPlanActive(PhotoFlashResolution.Plan(null, 2, 3, false)))
    }

    @Test fun latencyIsMillisecondsAndRejectsNegativeSpans() {
        assertEquals(StillShutterLatency(true, 42), measureStillShutterLatency(true, 1_000_000_000L, 1_042_900_000L))
        assertEquals(StillShutterLatency(false, 0), measureStillShutterLatency(false, 5L, 5L))
        assertNull(measureStillShutterLatency(true, 10L, 9L))
    }
}
