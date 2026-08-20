/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.PolicyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvClockDiagnosticsTest {
    @Test
    fun mapsOnlyMonotonicTimestampsAndGeneratesRelativeAacPts() {
        val mapper = AudioTimestampMapper()
        assertEquals(
            AudioTimestampMapping.Mapped(MonotonicAudioTimestamp(3, 2_000_000)),
            mapper.map(AudioTimestampSample(3, 2_000_000_000, AudioTimestampTimebase.MONOTONIC)),
        )
        assertEquals(FailureCode.UNKNOWN_CAPABILITY, ((mapper.map(AudioTimestampSample(3, 1, AudioTimestampTimebase.UNKNOWN)) as AudioTimestampMapping.Rejected).failure.code))
        assertEquals(FailureCode.UNSUPPORTED_CAPABILITY, ((mapper.map(AudioTimestampSample(3, 1, AudioTimestampTimebase.OTHER)) as AudioTimestampMapping.Rejected).failure.code))

        val generator = AacPtsGenerator()
        assertEquals(0, ((generator.next(AudioTimestampSample(10, 5_000_000_000, AudioTimestampTimebase.MONOTONIC)) as AacPtsResult.Generated).timestamp.ptsUs))
        assertEquals(20_000, ((generator.next(AudioTimestampSample(11, 5_020_000_000, AudioTimestampTimebase.MONOTONIC)) as AacPtsResult.Generated).timestamp.ptsUs))
        assertEquals(FailureCode.CADENCE_DISCONTINUITY, (generator.next(AudioTimestampSample(9, 5_030_000_000, AudioTimestampTimebase.MONOTONIC)) as AacPtsResult.Rejected).failure.code)
    }

    @Test
    fun strictStopsAfterFortyMillisecondsForFiveSeconds() {
        val diagnostics = AvClockDiagnostics(PolicyMode.STRICT)
        assertEquals(AvDiagnosticAction.NONE, diagnostics.observe(0, 0, 0).action)
        assertEquals(AvDiagnosticAction.NONE, diagnostics.observe(40_001, 0, 0).action)
        val stopped = diagnostics.observe(5_040_001, 0, 5_000_000)
        assertEquals(AvDiagnosticAction.STOP, stopped.action)
        assertEquals(FailureCode.CADENCE_DISCONTINUITY, stopped.failure?.code)
        assertEquals(5_040_001L, stopped.driftUs)
    }

    @Test
    fun adaptiveKeepsRecordingWithPersistentWarning() {
        val diagnostics = AvClockDiagnostics(PolicyMode.ADAPTIVE)
        diagnostics.observe(50_000, 0, 0)
        val warning = diagnostics.observe(5_050_000, 0, 5_000_000)
        assertEquals(AvDiagnosticAction.WARNING, warning.action)
        assertTrue(warning.warningPersistent)
        assertEquals(FailureCode.CADENCE_DISCONTINUITY, warning.failure?.code)
    }

    @Test
    fun recordsOverrunsAsExplicitGapsAndClosesDeterministically() {
        val diagnostics = AvClockDiagnostics(PolicyMode.STRICT)
        diagnostics.observe(100_000, 90_000)
        diagnostics.recordOverrun(100_000, 480)
        diagnostics.recordGap(100_000, 480, reason = "AudioRecord overrun")
        val snapshot = diagnostics.snapshot()
        assertEquals(1, snapshot.overruns.size)
        assertEquals(1, snapshot.gaps.size)
        assertEquals(10_000L, snapshot.gaps.single().durationUs)
        diagnostics.close()
        var closedFailure = false
        try {
            diagnostics.snapshot()
        } catch (_: IllegalStateException) {
            closedFailure = true
        }
        assertTrue(closedFailure)
    }
}
