/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalGateTest {
    @Test fun logAndHlgAreExperimentalModes() {
        assertTrue(CaptureMode.LOG.experimental)
        assertTrue(CaptureMode.HLG.experimental)
        assertFalse(CaptureMode.VIDEO.experimental)
    }

    @Test fun availableLogAndHlgShowExperimentalUntilAProfileIsVerified() {
        for (mode in listOf(CaptureMode.LOG, CaptureMode.HLG)) {
            assertTrue(showsExperimentalGate(mode, ModeGateState.AVAILABLE, hasVerifiedOpenCineLog = false))
            assertFalse(showsExperimentalGate(mode, ModeGateState.AVAILABLE, hasVerifiedOpenCineLog = true))
        }
    }

    @Test fun otherGateStatesKeepTheirOwnLabel() {
        for (gate in ModeGateState.entries.filter { it != ModeGateState.AVAILABLE }) {
            assertFalse(showsExperimentalGate(CaptureMode.LOG, gate, hasVerifiedOpenCineLog = false))
        }
    }

    @Test fun otherExperimentalModesAreNotExemptedByLogEvidence() {
        assertTrue(showsExperimentalGate(CaptureMode.RAW_PHOTO, ModeGateState.AVAILABLE, hasVerifiedOpenCineLog = true))
        assertEquals(false, showsExperimentalGate(CaptureMode.PHOTO, ModeGateState.AVAILABLE, hasVerifiedOpenCineLog = false))
    }
}
