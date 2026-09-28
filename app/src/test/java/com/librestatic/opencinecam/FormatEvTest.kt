/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatEvTest {
    @Test
    fun zeroAndNullAreBareZero() {
        assertEquals("0", formatEv(0f))
        assertEquals("0", formatEv(null))
    }

    @Test
    fun negativeValuesCarryASingleMinusSign() {
        assertEquals("−3", formatEv(-3f))
        assertEquals("−1", formatEv(-1f))
        assertTrue(formatEv(-1.33f).matches(Regex("−1[.,]33")))
    }

    @Test
    fun positiveValuesCarryAPlusSign() {
        assertEquals("+3", formatEv(3f))
        assertTrue(formatEv(0.5f).matches(Regex("\\+0[.,]50")))
    }
}
