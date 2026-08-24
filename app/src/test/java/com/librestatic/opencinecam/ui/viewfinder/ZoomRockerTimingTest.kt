/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.ui.viewfinder

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomRockerTimingTest {

    @Test
    fun regularTickUsesOnlyTimeSincePreviousTick() {
        assertEquals(
            0.033f,
            rockerTickDeltaSeconds(1_000_000_000L, 1_033_000_000L),
            0.0001f,
        )
    }

    @Test
    fun stalledTickCannotCauseCatchUpJump() {
        assertEquals(
            0.05f,
            rockerTickDeltaSeconds(1_000_000_000L, 11_000_000_000L),
            0.0001f,
        )
    }

    @Test
    fun clockRegressionCannotReverseZoom() {
        assertEquals(
            0f,
            rockerTickDeltaSeconds(2_000_000_000L, 1_000_000_000L),
            0f,
        )
    }
}
