/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.capture.formatShutter
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatShutterTest {
    @Test
    fun theDenominatorRoundsInsteadOfTruncating() {
        assertEquals("1/60", formatShutter(16_666_667))
        assertEquals("1/30", formatShutter(33_333_334))
        assertEquals("1/60", formatShutter(16_683_333))
        assertEquals("1/48", formatShutter(20_854_166))
        assertEquals("1/1000", formatShutter(1_000_000))
    }

    @Test
    fun noExposureIsADash() {
        assertEquals("—", formatShutter(0))
    }

    @Test
    fun aSecondOrLongerReadsInMilliseconds() {
        assertEquals("1/1", formatShutter(1_000_000_000))
        assertEquals("2000ms", formatShutter(2_000_000_000))
    }
}
