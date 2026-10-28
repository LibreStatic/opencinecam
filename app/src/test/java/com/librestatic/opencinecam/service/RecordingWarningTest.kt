/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingWarningTest {
    private val fallback = "Recording in H.264: HEVC encoder unavailable"

    @Test fun theCodecFallbackShowsOnlyForATakeThatFellBack() {
        assertNull(recordingWarningLine(null, null, null, emptyList()))
        assertEquals(fallback, recordingWarningLine(null, null, fallback, emptyList()))
    }

    @Test fun theCodecFallbackJoinsTheOtherWarningsInOrder() {
        assertEquals("Storage unknown · Software video encoder: c2.android.avc.encoder · $fallback · Cadence",
            recordingWarningLine("Storage unknown", "Software video encoder: c2.android.avc.encoder", fallback, listOf("Cadence")))
        assertEquals("Storage unknown · Cadence", recordingWarningLine("Storage unknown", null, null, listOf("Cadence")))
    }
}
