package com.librestatic.opencinecam.camera

import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantAecTest {
    @Test fun prefersAggressiveThenFastAndNeverNormal() {
        assertEquals(1, InstantAec.boostMode(intArrayOf(0, 1, 2)))
        assertEquals(2, InstantAec.boostMode(intArrayOf(0, 2)))
        assertNull(InstantAec.boostMode(intArrayOf(0)))
        assertNull(InstantAec.boostMode(intArrayOf()))
        assertNull(InstantAec.boostMode(null))
    }

    @Test fun duringATakeOnlyFastIsUsed() {
        assertEquals(2, InstantAec.boostMode(intArrayOf(0, 1, 2), recording = true))
        assertNull(InstantAec.boostMode(intArrayOf(0, 1), recording = true))
    }

    @Test fun eligibilityExcludesHighSpeedManualExposureAndAeLock() {
        assertTrue(InstantAec.eligible(highSpeed = false, manualExposure = false, aeLocked = false))
        assertFalse(InstantAec.eligible(true, false, false))
        assertFalse(InstantAec.eligible(false, true, false))
        assertFalse(InstantAec.eligible(false, false, true))
    }

    @Test fun onlyARealPhysicalChangeIsALensSwitch() {
        assertTrue(InstantAec.physicalLensSwitched("2", "4"))
        assertFalse(InstantAec.physicalLensSwitched("2", "2"))
        assertFalse(InstantAec.physicalLensSwitched(null, "2"))
        assertFalse(InstantAec.physicalLensSwitched("2", null))
    }

    @Test fun takeBoostTimesOutSooner() {
        val m = InstantAecMachine()
        assertTrue(m.begin(0, 2, eligible = true, recording = true))
        assertNull(m.onResult(499, CaptureResult.CONTROL_AE_STATE_SEARCHING, true, recording = true))
        assertEquals(InstantAecMachine.Release.TIMEOUT to 500L, m.onResult(500, CaptureResult.CONTROL_AE_STATE_SEARCHING, true, recording = true))
    }

    @Test fun takeStartingOrEndingMidBoostReleasesIt() {
        val m = InstantAecMachine()
        m.begin(0, 1, true, recording = false)
        assertEquals(InstantAecMachine.Release.TAKE_CHANGED to 30L, m.onResult(30, CaptureResult.CONTROL_AE_STATE_SEARCHING, true, recording = true))
        m.begin(0, 2, true, recording = true)
        assertEquals(InstantAecMachine.Release.TAKE_CHANGED to 40L, m.onResult(40, CaptureResult.CONTROL_AE_STATE_SEARCHING, true, recording = false))
    }

    @Test fun convergedFrameFromBeforeTheBoostDoesNotReleaseIt() {
        val m = InstantAecMachine()
        m.begin(0, 1, true)
        assertEquals(1, m.mode)
        assertNull(m.onResult(10, CaptureResult.CONTROL_AE_STATE_CONVERGED, true, boostApplied = false))
        assertEquals(InstantAecMachine.Release.CONVERGED to 40L, m.onResult(40, CaptureResult.CONTROL_AE_STATE_CONVERGED, true, boostApplied = true))
        assertNull(m.mode)
    }

    @Test fun beginRequiresModeAndEligibility() {
        assertFalse(InstantAecMachine().begin(0, null, true))
        assertFalse(InstantAecMachine().begin(0, 1, false))
        val m = InstantAecMachine()
        assertTrue(m.begin(0, 1, true))
        assertTrue(m.active)
        assertFalse(m.begin(10, 1, true))
    }

    @Test fun releasesOnConvergedOrLocked() {
        val m = InstantAecMachine()
        m.begin(100, 1, true)
        assertNull(m.onResult(150, CaptureResult.CONTROL_AE_STATE_SEARCHING, true))
        assertEquals(InstantAecMachine.Release.CONVERGED to 200L, m.onResult(300, CaptureResult.CONTROL_AE_STATE_CONVERGED, true))
        assertFalse(m.active)
        m.begin(0, 1, true)
        assertEquals(InstantAecMachine.Release.CONVERGED to 5L, m.onResult(5, CaptureResult.CONTROL_AE_STATE_LOCKED, true))
    }

    @Test fun releasesAfterTimeoutWhenNeverConverging() {
        val m = InstantAecMachine()
        m.begin(0, 2, true)
        assertNull(m.onResult(999, CaptureResult.CONTROL_AE_STATE_SEARCHING, true))
        assertNull(m.onResult(999, null, true))
        assertEquals(InstantAecMachine.Release.TIMEOUT to 1000L, m.onResult(1000, CaptureResult.CONTROL_AE_STATE_SEARCHING, true))
        assertNull(m.onResult(1100, CaptureResult.CONTROL_AE_STATE_CONVERGED, true))
    }

    @Test fun releasesImmediatelyWhenNoLongerEligible() {
        val m = InstantAecMachine()
        m.begin(0, 1, true)
        assertEquals(InstantAecMachine.Release.INELIGIBLE to 20L, m.onResult(20, CaptureResult.CONTROL_AE_STATE_SEARCHING, false))
        assertFalse(m.active)
    }

    @Test fun resetDropsActiveBoost() {
        val m = InstantAecMachine()
        m.begin(0, 1, true)
        m.reset()
        assertFalse(m.active)
        assertTrue(m.begin(5, 1, true))
    }
}
