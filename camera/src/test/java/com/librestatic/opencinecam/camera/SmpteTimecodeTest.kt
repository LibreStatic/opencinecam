/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmpteTimecodeTest {

    @Test fun ndf30RoundTripFromZero() {
        val rate = TimecodeRate(30, false)
        val tc = SmpteTimecode.fromTotalFrames(0, rate)
        assertEquals("00:00:00:00", tc.format())
    }

    @Test fun ndf30OneSecond() {
        val rate = TimecodeRate(30, false)
        val tc = SmpteTimecode.fromTotalFrames(30, rate)
        assertEquals("00:00:01:00", tc.format())
    }

    @Test fun ndf30OneMinute() {
        val rate = TimecodeRate(30, false)
        val tc = SmpteTimecode.fromTotalFrames(30 * 60, rate)
        assertEquals("00:01:00:00", tc.format())
    }

    @Test fun ndf30OneHour() {
        val rate = TimecodeRate(30, false)
        val tc = SmpteTimecode.fromTotalFrames(30 * 3600, rate)
        assertEquals("01:00:00:00", tc.format())
    }

    @Test fun ndf24FormatsCorrectly() {
        val rate = TimecodeRate(24, false)
        val tc = SmpteTimecode.fromTotalFrames(24, rate)
        assertEquals("00:00:01:00", tc.format())
    }

    @Test fun ndf25FormatsCorrectly() {
        val rate = TimecodeRate(25, false)
        val tc = SmpteTimecode.fromTotalFrames(25 * 60, rate)
        assertEquals("00:01:00:00", tc.format())
    }

    @Test fun df30FirstMinuteSkipsTwoFrames() {
        val rate = TimecodeRate(30, true)
       val tc = SmpteTimecode.fromTotalFrames(1798, rate)
        assertEquals("00:00:59;28", tc.format())
       val tc2 = SmpteTimecode.fromTotalFrames(1800, rate)
        assertEquals("00:01:00;02", tc2.format())
    }

    @Test fun df30UsesSemicolonSeparator() {
        val rate = TimecodeRate(30, true)
        val tc = SmpteTimecode.fromTotalFrames(0, rate)
        assertTrue(tc.format().contains(";"))
    }

    @Test fun ndfUsesColonSeparator() {
        val rate = TimecodeRate(30, false)
        val tc = SmpteTimecode.fromTotalFrames(0, rate)
        val formatted = tc.format()
        assertTrue(formatted.contains(":"))
        assertTrue(!formatted.contains(";"))
    }

    @Test fun rolloverAt24Hours() {
        val rate = TimecodeRate(30, false)
       val total = 30 * 3600 * 24
        val tc = SmpteTimecode.fromTotalFrames(total.toLong(), rate)
        assertEquals(0, tc.hours)
    }

    @Test fun startValueFormatsCorrectly() {
        val tc = SmpteTimecode(1, 0, 0, 0, false)
        assertEquals("01:00:00:00", tc.format())
    }

    @Test fun frameDurationUsFor30Ndf() {
        val rate = TimecodeRate(30, false)
        assertEquals(33333L, rate.frameDurationUs)
    }

    @Test fun frameDurationUsFor2997Df() {
        val rate = TimecodeRate(30, true)
        assertEquals(33366L, rate.frameDurationUs)
    }
}
