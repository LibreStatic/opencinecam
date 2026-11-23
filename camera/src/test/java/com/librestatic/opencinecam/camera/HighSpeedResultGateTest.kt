/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class HighSpeedResultGateTest {
    private val ms = 1_000_000L

    @Test fun firstResultIsProcessed() = assertTrue(HighSpeedResultGate().shouldProcessFully(5_000 * ms))

    @Test fun resultsWithinThePeriodAreSkippedAndTheNextAfterItIsProcessed() {
        val gate = HighSpeedResultGate()
        assertTrue(gate.shouldProcessFully(1_000 * ms))
        // 240 fps: a result every ~4.17 ms.
        assertFalse(gate.shouldProcessFully(1_004 * ms))
        assertFalse(gate.shouldProcessFully(1_029 * ms))
        assertTrue(gate.shouldProcessFully(1_030 * ms))
        assertFalse(gate.shouldProcessFully(1_034 * ms))
    }

    @Test fun nullTimestampIsProcessedAndKeepsTheGate() {
        val gate = HighSpeedResultGate()
        assertTrue(gate.shouldProcessFully(1_000 * ms))
        assertTrue(gate.shouldProcessFully(null))
        assertFalse(gate.shouldProcessFully(1_004 * ms))
    }

    @Test fun timestampGoingBackwardsResetsTheGate() {
        val gate = HighSpeedResultGate()
        assertTrue(gate.shouldProcessFully(9_000 * ms))
        assertTrue(gate.shouldProcessFully(100 * ms))
        assertFalse(gate.shouldProcessFully(104 * ms))
    }

    @Test fun neededForHighSpeedAndForRegularSessionsAboveSixtyFps() {
        assertTrue(HighSpeedResultGate.needed(highSpeed = true, targetFps = 30))
        assertFalse(HighSpeedResultGate.needed(highSpeed = false, targetFps = 30))
        assertFalse(HighSpeedResultGate.needed(highSpeed = false, targetFps = 60))
        // RAW video at 90 and 120 fps runs in a regular session.
        assertTrue(HighSpeedResultGate.needed(highSpeed = false, targetFps = 90))
        assertTrue(HighSpeedResultGate.needed(highSpeed = false, targetFps = 120))
    }
}
