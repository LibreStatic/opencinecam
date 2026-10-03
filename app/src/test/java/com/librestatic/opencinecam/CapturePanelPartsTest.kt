/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePanelPartsTest {
    @Test fun gridColumnsFitTheHostWidth() {
        // A 320 dp side column, a 411 dp phone sheet and a narrow 300 dp column.
        assertEquals(4, panelGridColumns(320f, 72f, 6))
        assertEquals(3, panelGridColumns(411f, 104f, 3))
        assertEquals(2, panelGridColumns(300f, 104f, 3))
        assertEquals(6, panelGridColumns(1000f, 72f, 6))
        assertEquals(1, panelGridColumns(50f, 72f, 4))
        assertEquals(1, panelGridColumns(0f, 72f, 4))
        assertEquals(1, panelGridColumns(Float.NaN, 72f, 4))
    }

    @Test fun balancedGridsLeaveNoRowHalfEmpty() {
        assertEquals(4, balancedGridColumns(4, 4))
        assertEquals(2, balancedGridColumns(3, 4))
        assertEquals(4, balancedGridColumns(5, 4))
        assertEquals(3, balancedGridColumns(6, 9))
        // Nothing divides seven evenly: keep the width that fits.
        assertEquals(4, balancedGridColumns(4, 7))
        assertEquals(1, balancedGridColumns(0, 3))
    }

    @Test fun theHighSpeedNoteOnlyDescribesRatesOnOffer() {
        assertFalse(fpsHighSpeedNoteApplies(emptyList()))
        assertFalse(fpsHighSpeedNoteApplies(listOf(FpsOption(30, offered = true, highSpeed = false))))
        // A high-speed rate greyed out at this size is not on offer.
        assertFalse(fpsHighSpeedNoteApplies(listOf(FpsOption(30, true, false), FpsOption(120, offered = false, highSpeed = true))))
        assertTrue(fpsHighSpeedNoteApplies(listOf(FpsOption(30, true, false), FpsOption(120, offered = true, highSpeed = true))))
    }
}
