/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Main10QualificationTest {
    private val signal = Main10FileSignal("video/hevc", "Main10", "BT.2020", "HLG", 10)

    @Test
    fun profileAndEffectiveDepthArePromotedIndependently() {
        val samples = IntArray(1024) { it }
        val qualified = Main10FileQualifier.qualify(signal, samples, 10)
        assertEquals(QualificationStatus.PASS, qualified.fileSignal.status)
        assertEquals(QualificationStatus.PASS, qualified.effectivePrecision.status)
        assertTrue(qualified.promoted)
        val unknown = Main10FileQualifier.qualify(signal.copy(profile = null), samples, 10)
        assertEquals(QualificationStatus.UNKNOWN, unknown.fileSignal.status)
        assertFalse(unknown.promoted)
    }

    @Test
    fun mismatchedSignalingAndLowEffectiveDepthNeverPromote() {
        val wrongSignal = Main10FileQualifier.validateSignal(signal.copy(colorTransfer = "SDR"))
        assertEquals(QualificationStatus.FAIL, wrongSignal.status)
        val lowDepth = Main10FileQualifier.measureEffectivePrecision(intArrayOf(0, 1, 2), 10)
        assertEquals(QualificationStatus.FAIL, lowDepth.status)
        assertFalse(Main10FileQualifier.qualify(signal, intArrayOf(0, 1), 10).promoted)
    }

    @Test
    fun lifecycleExposesDuplicateCancellationStaleAndCleanup() {
        val session = Main10QualificationSession(maxCommands = 1)
        assertEquals(QualificationCommandStatus.CANCELLED, session.cancel("cancel"))
        assertEquals(QualificationCommandStatus.CANCELLED,
            session.qualify("cancel", signal, null, null))
        assertEquals(QualificationCommandStatus.STARTED,
            session.qualify("one", signal, intArrayOf(0, 1), 10))
        assertEquals(QualificationCommandStatus.DUPLICATE,
            session.qualify("one", signal, intArrayOf(0, 1), 10))
        assertEquals(QualificationCommandStatus.STALE,
            session.qualify("two", signal, intArrayOf(0, 1), 10))
        session.close()
        assertEquals(QualificationCommandStatus.CLOSED,
            session.qualify("after", signal, null, null))
    }
}
