/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimecodeTest {
    @Test fun framesUseTheNominalRate() {
        assertEquals("00:00:04:06", playbackTimecode(0, 102, 24.0))
        assertEquals("00:01:22:00", playbackTimecode(0, 1968, 24000.0 / 1001))
        assertEquals("01:00:00:29", playbackTimecode(0, 30L * 3600 + 29, 29.97))
    }

    @Test fun withoutARateWholeSecondsOfPositionAreShown() {
        assertEquals("00:01:05:00", playbackTimecode(65_900_000, null, null))
        assertEquals("00:00:03:00", playbackTimecode(3_000_000, 72, 0.0))
        assertEquals("00:00:00:00", playbackTimecode(-5, -3, 24.0))
    }
}
