/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.PolicyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SustainedCadenceGuardTest {
    private val frameNs = 1_000_000_000L / 30

    /** Feeds [seconds] of 30 fps frames, each preceded by [dropPerFrame] frames that never arrived. */
    private fun feed(guard: SustainedCadenceGuard, startNs: Long, seconds: Int, dropPerFrame: Long = 0): Long {
        var t = startNs
        repeat(seconds * 30) {
            guard.observe(t, dropPerFrame)
            t += frameNs
        }
        return t
    }

    @Test fun aHealthyTakeNeverTrips() {
        val guard = SustainedCadenceGuard()
        feed(guard, 0, 60)
        assertFalse(guard.tripped)
    }

    @Test fun aSingleOutlierOrShortBurstOfDropsNeverTrips() {
        val guard = SustainedCadenceGuard()
        var t = feed(guard, 0, 3)
        guard.observe(t, 25) // a quarter of a second lost at once
        t = feed(guard, t + frameNs, 20)
        assertFalse(guard.tripped)
        // Four broken seconds are still below the five-second bar, and a healthy second resets the run.
        t = feed(guard, t, 4, dropPerFrame = 1)
        t = feed(guard, t, 2)
        feed(guard, t, 4, dropPerFrame = 1)
        assertFalse(guard.tripped)
    }

    @Test fun dropsHeldForFiveSecondsTrip() {
        val guard = SustainedCadenceGuard()
        val t = feed(guard, 0, 2)
        feed(guard, t, 7, dropPerFrame = 1) // one frame lost per delivered frame: half missing
        assertTrue(guard.tripped)
    }

    @Test fun aLongStallCountsEveryEmptySecond() {
        val guard = SustainedCadenceGuard()
        val t = feed(guard, 0, 2)
        guard.observe(t + 8_000_000_000L, 240)
        guard.observe(t + 8_000_000_000L + 1_100_000_000L, 0)
        assertTrue(guard.tripped)
    }

    @Test fun resetClearsALatchedGuard() {
        val guard = SustainedCadenceGuard()
        feed(guard, feed(guard, 0, 1), 8, dropPerFrame = 1)
        assertTrue(guard.tripped)
        guard.reset()
        assertFalse(guard.tripped)
        feed(guard, 0, 5)
        assertFalse(guard.tripped)
    }

    @Test fun strictStopsAndAdaptiveWarns() {
        assertEquals(CaptureAuditAction.STOP, cadenceLossAction(PolicyMode.STRICT))
        assertEquals(CaptureAuditAction.WARNING, cadenceLossAction(PolicyMode.ADAPTIVE))
    }
}
