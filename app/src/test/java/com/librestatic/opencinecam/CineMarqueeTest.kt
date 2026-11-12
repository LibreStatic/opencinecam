/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CineMarqueeTest {
    @Test fun marqueeRunsOnlyWithTheSettingOnAndMotionAllowed() {
        assertTrue(marqueeActive(setting = true, reducedMotion = false))
        assertFalse(marqueeActive(setting = true, reducedMotion = true))
        assertFalse(marqueeActive(setting = false, reducedMotion = false))
        assertFalse(marqueeActive(setting = false, reducedMotion = true))
    }

    @Test fun fitTextModeTable() {
        // Marquee off: the ellipsis over the caller's lines, as before the setting existed.
        for (lines in 1..3) for (fits in listOf(true, false)) {
            assertEquals(FitTextMode.ELLIPSIS, fitTextMode(fitsInMaxLines = fits, maxLines = lines, marquee = false))
        }
        // One line: always the marquee, which only moves when the text overflows.
        assertEquals(FitTextMode.MARQUEE, fitTextMode(fitsInMaxLines = true, maxLines = 1, marquee = true))
        assertEquals(FitTextMode.MARQUEE, fitTextMode(fitsInMaxLines = false, maxLines = 1, marquee = true))
        // More lines: wrap while it fits, else one scrolling line.
        assertEquals(FitTextMode.WRAP, fitTextMode(fitsInMaxLines = true, maxLines = 2, marquee = true))
        assertEquals(FitTextMode.MARQUEE, fitTextMode(fitsInMaxLines = false, maxLines = 2, marquee = true))
        assertEquals(FitTextMode.WRAP, fitTextMode(fitsInMaxLines = true, maxLines = 3, marquee = true))
        assertEquals(FitTextMode.MARQUEE, fitTextMode(fitsInMaxLines = false, maxLines = 3, marquee = true))
    }
}
