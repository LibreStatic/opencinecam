/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the Motorola high-speed decisions the Razr relies on, independent of what enables them. */
class HighSpeedQuirksTest {
    @Test
    fun bothSessionKeysAreAppliedInOrderWithTheSlowMotionValues() {
        val values = MotorolaHighSpeed.sessionValues(
            listOf("android.control.mode", MotorolaHighSpeed.CURRENT_MODE_KEY, MotorolaHighSpeed.IS_CAMERA2_KEY),
        )
        assertEquals(
            listOf("com.lenovo.moto.clientapp.is_motcamera2", "com.lenovo.moto.clientapp.current_mode"),
            values.map { it.keyName },
        )
        assertArrayEquals(byteArrayOf(1), (values[0] as HighSpeedSessionValue.Bytes).value)
        assertArrayEquals(intArrayOf(3), (values[1] as HighSpeedSessionValue.Ints).value)
    }

    @Test
    fun aKeyTheCameraDoesNotAdvertiseIsSkipped() {
        assertEquals(
            listOf(MotorolaHighSpeed.CURRENT_MODE_KEY),
            MotorolaHighSpeed.sessionValues(listOf(MotorolaHighSpeed.CURRENT_MODE_KEY)).map { it.keyName },
        )
        assertTrue(MotorolaHighSpeed.sessionValues(listOf("android.control.mode")).isEmpty())
        assertTrue(MotorolaHighSpeed.sessionValues(emptyList()).isEmpty())
    }

    @Test
    fun theClosestFocalLengthPhysicalWinsAndTheFirstWinsATie() {
        val candidates = listOf(
            PhysicalHighSpeedCandidate("3", 2.2f),
            PhysicalHighSpeedCandidate("2", 6.57f),
            PhysicalHighSpeedCandidate("5", 13.3f),
        )
        assertEquals("2", MotorolaHighSpeed.closestFocalPhysicalId(candidates, 6.57f))
        assertEquals("5", MotorolaHighSpeed.closestFocalPhysicalId(candidates, 14f))
        assertEquals("3", MotorolaHighSpeed.closestFocalPhysicalId(candidates, 3f))
        val tied = listOf(PhysicalHighSpeedCandidate("7", 5f), PhysicalHighSpeedCandidate("4", 5f))
        assertEquals("7", MotorolaHighSpeed.closestFocalPhysicalId(tied, 5f))
    }

    @Test
    fun withoutAKnownFocalLengthTheFirstCandidateIsChosen() {
        val candidates = listOf(PhysicalHighSpeedCandidate("3", null), PhysicalHighSpeedCandidate("2", 6.57f))
        assertEquals("3", MotorolaHighSpeed.closestFocalPhysicalId(candidates, null))
        // A candidate with an unknown focal length ranks behind one that is known.
        assertEquals("2", MotorolaHighSpeed.closestFocalPhysicalId(candidates, 6.57f))
        assertNull(MotorolaHighSpeed.closestFocalPhysicalId(emptyList(), 6.57f))
    }
}
