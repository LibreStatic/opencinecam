/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QcomExposurePriorityTest {
    private val held = 16_566_667L
    private val manual = ExposureCapabilities(
        manual = true, isoRange = 71..12_800, timeRangeNs = 32_000L..250_000_000L)
    private val qcom = manual.copy(qcomExposureRangeNs = 32_317L..249_994_248L)
    private val native = qcom.copy(priorities = setOf(ExposureMode.SHUTTER_PRIORITY))

    @Test fun parsesAValidTwoElementRange() {
        assertEquals(51_265L..170_494_792L, QcomExposurePriority.parseRange(longArrayOf(51_265, 170_494_792)))
    }

    @Test fun rejectsMissingShortInvertedOrZeroRanges() {
        assertNull(QcomExposurePriority.parseRange(null))
        assertNull(QcomExposurePriority.parseRange(longArrayOf(1)))
        assertNull(QcomExposurePriority.parseRange(longArrayOf(1, 2, 3)))
        assertNull(QcomExposurePriority.parseRange(longArrayOf(500, 100)))
        assertNull(QcomExposurePriority.parseRange(longArrayOf(0, 100)))
    }

    @Test fun nativeThenQcomThenEmulated() {
        assertEquals(CadenceHoldPath.NATIVE, cadenceHoldPath(36, native, held, false))
        assertEquals(CadenceHoldPath.QCOM, cadenceHoldPath(36, qcom, held, false))
        assertEquals(CadenceHoldPath.EMULATED, cadenceHoldPath(36, manual, held, false))
    }

    @Test fun nativePriorityNeedsApi36() {
        assertEquals(CadenceHoldPath.QCOM, cadenceHoldPath(35, native, held, false))
    }

    @Test fun theQcomRangeMustContainTheHeldExposure() {
        assertEquals(CadenceHoldPath.EMULATED, cadenceHoldPath(36, qcom.copy(qcomExposureRangeNs = 50_000L..10_000_000L), held, false))
        assertEquals(CadenceHoldPath.QCOM, cadenceHoldPath(36, qcom.copy(qcomExposureRangeNs = held..held), held, false))
    }

    @Test fun anUnusableQcomCameraFallsBackToTheEmulatedHold() {
        assertEquals(CadenceHoldPath.EMULATED, cadenceHoldPath(36, qcom, held, true))
    }

    @Test fun noPathWithoutManualControlOrPriority() {
        assertNull(cadenceHoldPath(36, ExposureCapabilities(), held, false))
        assertNull(cadenceHoldPath(36, ExposureCapabilities(), held, true))
    }

    @Test fun theFpsLockIsSupportedByQcomPriorityAlone() {
        assertTrue(CadenceGuard.supported(36, ExposureCapabilities(qcomExposureRangeNs = 1L..100L)))
        assertFalse(CadenceGuard.supported(36, ExposureCapabilities()))
        assertNotNull(qcom.qcomExposureRangeNs)
    }

    @Test fun theHalIgnoringTheRequestIsDetectedAbove110PercentOfTheHeldExposure() {
        assertTrue(qcomHoldIgnored(30_000_000L, held))
        assertFalse(qcomHoldIgnored(held, held))
        assertFalse(qcomHoldIgnored((held * 1.1).toLong(), held))
        assertFalse(qcomHoldIgnored(null, held))
    }
}
