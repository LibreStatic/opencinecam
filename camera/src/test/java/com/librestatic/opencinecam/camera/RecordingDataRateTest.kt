/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDataRateTest {
    @Test fun recorderScalesByCaptureRateAndClamps() {
        assertEquals(20_000_000, RecordingDataRate.recorderVideoBps(20_000_000, 30))
        assertEquals(80_000_000, RecordingDataRate.recorderVideoBps(20_000_000, 120))
        assertEquals(8_000_000, RecordingDataRate.recorderVideoBps(1_000_000, 30))
        assertEquals(200_000_000, RecordingDataRate.recorderVideoBps(100_000_000, 240))
    }

    @Test fun gpuPathScalesByEncoderRateAndClampsAt12Mbps() {
        assertEquals(40_000_000, RecordingDataRate.gpuVideoBps(20_000_000, 60))
        assertEquals(12_000_000, RecordingDataRate.gpuVideoBps(8_000_000, 30))
        assertEquals(200_000_000, RecordingDataRate.gpuVideoBps(100_000_000, 120))
    }

    @Test fun timelapseWritesOnlyTheCapturedShareOfTheNominalRate() {
        // One frame per second into a 30 fps timeline: a thirtieth, held at the tenth floor.
        assertEquals(2_000_000L, RecordingDataRate.timelapseVideoBps(20_000_000, 30.0, 1000))
        // Capturing as fast as the project plays is the nominal rate.
        assertEquals(20_000_000L, RecordingDataRate.timelapseVideoBps(20_000_000, 30.0, 33))
        assertTrue(RecordingDataRate.timelapseVideoBps(20_000_000, 30.0, 500) in 2_000_000L..20_000_000L)
    }

    @Test fun bytesPerMinuteAddsAudioAndNeverGoesNegative() {
        assertEquals((20_000_000L + 128_000L) / 8 * 60, RecordingDataRate.bytesPerMinute(20_000_000L, 128_000L))
        assertEquals(0L, RecordingDataRate.bytesPerMinute(-5L, -5L))
    }
}
