/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Test

class ListEntranceTest {
    @Test
    fun listStaggersOneStepPerRow() {
        assertEquals(0f, entranceDelayMillis(0, columns = 1))
        assertEquals(40f, entranceDelayMillis(1, columns = 1))
        assertEquals(200f, entranceDelayMillis(5, columns = 1))
    }

    @Test
    fun gridStaggersAlongTheDiagonal() {
        // Row plus column: the second tile of the first row and the first of the second row share a delay.
        assertEquals(entranceDelayMillis(1, columns = 3), entranceDelayMillis(3, columns = 3))
        assertEquals(80f, entranceDelayMillis(4, columns = 3))
    }

    @Test
    fun delayIsCappedSoLongListsDoNotWait() {
        assertEquals(EntranceMaxDelayMillis, entranceDelayMillis(500, columns = 1))
    }

    @Test
    fun anythingComposedAfterTheClockShowsAtOnce() {
        val clockEnd = EntranceMaxDelayMillis + EntranceTileMillis
        (0..40).forEach { slot -> assertEquals(1f, entranceProgress(clockEnd, slot, columns = 1)) }
        assertEquals(0f, entranceProgress(0f, 3, columns = 1))
    }

    @Test
    fun itemsAboveTheFirstVisibleOneNeverWait() {
        assertEquals(0f, entranceDelayMillis(-4, columns = 2))
    }
}
