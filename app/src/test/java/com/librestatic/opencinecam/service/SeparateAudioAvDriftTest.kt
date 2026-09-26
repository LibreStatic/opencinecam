/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.AudioCaptureEpoch
import com.librestatic.opencinecam.camera.PcmSourceTimingReport
import com.librestatic.opencinecam.media.audio.AvDiagnosticAction
import org.junit.Assert.*
import org.junit.Test

class SeparateAudioAvDriftTest {
    private fun timing(firstFrame: Long?, firstNs: Long?, lastFrame: Long?, lastNs: Long?, observations: Long = 100, rate: Int = 48_000) =
        PcmSourceTimingReport(rate, 4, lastFrame ?: 0, lastFrame ?: 0, 0, AudioCaptureEpoch(firstNs ?: 1, true),
            observations, 0, firstFrame, firstNs, lastFrame, lastNs)

    @Test fun matchedClocksReportNoDrift() {
        val snapshot = requireNotNull(separateAudioAvDrift(true, timing(0, 1_000_000_000, 480_000, 11_000_000_000), "take-1"))
        assertEquals(10_000_000, snapshot.videoPtsUs)
        assertEquals(10_000_000, snapshot.audioPtsUs)
        assertEquals(0, snapshot.driftUs)
        assertEquals(AvDiagnosticAction.NONE, snapshot.action)
        assertNull(snapshot.failure)
    }

    @Test fun slowMicrophoneClockIsMeasuredAsPositiveDriftAndNeverStopsTheTake() {
        // 10 s of BOOTTIME, but the microphone delivered 50 ms fewer frames than 48 kHz predicts.
        val snapshot = requireNotNull(separateAudioAvDrift(true, timing(4_800, 2_000_000_000, 4_800 + 477_600, 12_000_000_000), "take-2"))
        assertEquals(10_000_000, snapshot.videoPtsUs)
        assertEquals(9_950_000, snapshot.audioPtsUs)
        assertEquals(50_000, snapshot.driftUs)
        // ADAPTIVE is report-only: even a drift beyond the 40 ms threshold is never a STOP.
        assertNotEquals(AvDiagnosticAction.STOP, snapshot.action)
        assertTrue(separateAudioAvDriftLog(snapshot).contains("50000 us"))
        // A fast microphone reads as negative drift.
        val fast = requireNotNull(separateAudioAvDrift(true, timing(0, 1_000_000_000, 480_960, 11_000_000_000), "take-3"))
        assertEquals(-20_000, fast.driftUs)
    }

    @Test fun unmeasurableTakesReportNothing() {
        val good = timing(0, 1_000_000_000, 480_000, 11_000_000_000)
        // Without a BOOTTIME camera the two clocks share no domain.
        assertNull(separateAudioAvDrift(false, good, "t"))
        assertNull(separateAudioAvDrift(true, null, "t"))
        assertNull(separateAudioAvDrift(true, good.copy(timestampObservations = 1), "t"))
        assertNull(separateAudioAvDrift(true, timing(null, null, null, null), "t"))
        assertNull(separateAudioAvDrift(true, timing(480_000, 1_000_000_000, 480_000, 11_000_000_000), "t"))
        assertNull(separateAudioAvDrift(true, timing(0, 1_000_000_000, 480_000, 1_000_000_000), "t"))
    }
}
