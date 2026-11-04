/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusLineLayoutTest {
    private fun layout(available: Int, oneLine: Int, top: Int, bottom: Int?, maxLines: Int = 1, marquee: Boolean = true) =
        statusLineLayout(available, oneLine, top, bottom, maxLines, marquee)

    @Test fun everythingOnOneLineWhenItFits() {
        assertEquals(StatusLineLayout.ONE_LINE, layout(available = 400, oneLine = 400, top = 300, bottom = 90))
        assertEquals(StatusLineLayout.ONE_LINE, layout(available = 400, oneLine = 250, top = 250, bottom = null))
        assertEquals(StatusLineLayout.ONE_LINE, layout(available = 400, oneLine = 380, top = 300, bottom = 70, maxLines = 3, marquee = false))
    }

    @Test fun theTimeLeftMovesToASecondRowWhenBothRowsFit() {
        for (lines in 1..3) for (marquee in listOf(true, false)) {
            assertEquals(StatusLineLayout.TWO_ROWS, layout(available = 300, oneLine = 420, top = 300, bottom = 110, maxLines = lines, marquee = marquee))
        }
    }

    @Test fun withoutABottomRowAnOverflowingLineScrollsOrEndsInAnEllipsis() {
        assertEquals(StatusLineLayout.MARQUEE, layout(available = 200, oneLine = 260, top = 260, bottom = null))
        assertEquals(StatusLineLayout.ELLIPSIS, layout(available = 200, oneLine = 260, top = 260, bottom = null, marquee = false))
        assertEquals(StatusLineLayout.MARQUEE, layout(available = 200, oneLine = 260, top = 260, bottom = null, maxLines = 2))
        assertEquals(StatusLineLayout.ELLIPSIS, layout(available = 200, oneLine = 260, top = 260, bottom = null, maxLines = 2, marquee = false))
    }

    @Test fun aCallerWithThreeLinesWrapsWhatTwoRowsCannotHold() {
        assertEquals(StatusLineLayout.WRAP, layout(available = 200, oneLine = 260, top = 260, bottom = null, maxLines = 3))
        assertEquals(StatusLineLayout.WRAP, layout(available = 200, oneLine = 400, top = 280, bottom = 110, maxLines = 3))
        assertEquals(StatusLineLayout.WRAP, layout(available = 200, oneLine = 400, top = 280, bottom = 110, maxLines = 3, marquee = false))
    }

    @Test fun aTopRowTooWideEvenAloneScrolls() {
        assertEquals(StatusLineLayout.MARQUEE, layout(available = 200, oneLine = 400, top = 280, bottom = 110))
        assertEquals(StatusLineLayout.ELLIPSIS, layout(available = 200, oneLine = 400, top = 280, bottom = 110, marquee = false))
        // The bottom row alone too wide also rules out the two rows.
        assertEquals(StatusLineLayout.MARQUEE, layout(available = 200, oneLine = 400, top = 150, bottom = 230, maxLines = 2))
    }

    @Test fun factsWrapOnlyBetweenThemselves() {
        assertEquals("3\u00A0min\u00A0left", "3 min left".unbroken())
        assertEquals("96.0\u00A0GB\u00A0free", "96.0 GB free".unbroken())
    }
}
