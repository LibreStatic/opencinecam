/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class SubjectPreviewDitherTest {
    @Test fun fullLevelDrawsNoBlackCells() {
        assertEquals(0, ditherBlackCells(100).count { it })
    }

    @Test fun lowestLevelBlacksOutMostCells() {
        val cells = ditherBlackCells(10)
        assertEquals(16, cells.size)
        assertEquals(14, cells.count { it })
    }

    @Test fun blackCountNeverGrowsWithLevel() {
        var previous = Int.MAX_VALUE
        for (level in 10..100) {
            val black = ditherBlackCells(level).count { it }
            assertTrue("level $level", black <= previous)
            previous = black
        }
    }

    @Test fun halfLevelBlacksHalfTheCells() {
        assertEquals(8, ditherBlackCells(50).count { it })
    }
}
