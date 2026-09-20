/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TimecodeArithmeticTest {
    @Test fun forwardAndInverseMatchIndependentFfmpegVectors() {
        val lines = requireNotNull(javaClass.getResourceAsStream("/timecode-ffmpeg-vectors.tsv")).bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") }
        assertEquals(11535, lines.size)
        for (line in lines) {
            val parts = line.split('\t'); val rate = TimecodeRate(parts[0].toInt(), parts[1] == "1")
            val label = SmpteTimecode.fromTotalFrames(parts[2].toLong(), rate)
            assertEquals(line, parts[3], label.format())
            assertEquals(line, parts[4].toLong(), label.toTotalFrames(rate))
        }
    }
    @Test fun df60OneHourContains215784Frames() {
        assertEquals(215784L, SmpteTimecode(1,0,0,0,true).toTotalFrames(TimecodeRate(60,true)))
    }
    @Test fun skippedLabelsAreRejectedAtEveryNonTenthMinute() {
        for (fps in listOf(30,60)) for (minute in 0..59) for (frame in 0 until fps/15) {
            val label=SmpteTimecode(0,minute,0,frame,true);val rate=TimecodeRate(fps,true)
            if(minute%10==0) assertEquals(label,SmpteTimecode.fromTotalFrames(label.toTotalFrames(rate),rate))
            else assertThrows(IllegalArgumentException::class.java){label.toTotalFrames(rate)}
        }
    }
    @Test fun labelFlagAndFrameRangeMustMatchRate() {
        assertThrows(IllegalArgumentException::class.java){SmpteTimecode(0,0,0,24).toTotalFrames(TimecodeRate(24))}
        assertThrows(IllegalArgumentException::class.java){SmpteTimecode(0,0,0,0,true).toTotalFrames(TimecodeRate(30))}
        assertThrows(IllegalArgumentException::class.java){SmpteTimecode(0,0,0,0).toTotalFrames(TimecodeRate(30,true))}
    }
    @Test fun invalidRatesFailBeforeDivisionOrFormatting() {
        for(fps in listOf(0,23,29,120))assertThrows(IllegalArgumentException::class.java){TimecodeRate(fps)}
        for(fps in listOf(24,25,50))assertThrows(IllegalArgumentException::class.java){TimecodeRate(fps,true)}
    }
    @Test fun dayWrapHandlesNegativeAndExtremeOffsetsWithoutOverflow() {
        for(rate in listOf(TimecodeRate(24),TimecodeRate(30,true),TimecodeRate(60,true))) {
            for(value in listOf(-1L,Long.MIN_VALUE,Long.MAX_VALUE,rate.framesPerDay,rate.framesPerDay+1)) {
                assertEquals(Math.floorMod(value,rate.framesPerDay),SmpteTimecode.fromTotalFrames(value,rate).toTotalFrames(rate))
            }
            assertEquals("00:00:00"+(if(rate.dropFrame)";00" else ":00"),SmpteTimecode.fromTotalFrames(rate.framesPerDay,rate).format())
        }
    }
    @Test fun exactElapsedFramesDoNotAccumulateTruncatedMicroseconds() {
        assertEquals(108000L,TimecodeRate(30).framesForElapsedNs(3600000000000L))
        assertEquals(107892L,TimecodeRate(30,true).framesForElapsedNs(3600000000000L))
        assertEquals(215784L,TimecodeRate(60,true).framesForElapsedNs(3600000000000L))
        assertEquals(0L,TimecodeRate(30,true).framesForElapsedNs(33366666))
        assertEquals(1L,TimecodeRate(30,true).framesForElapsedNs(33366667))
    }
    @Test fun elapsedFramesRejectNegativeTimeAndHandleLongMaximum() {
        assertThrows(IllegalArgumentException::class.java){TimecodeRate(30).framesForElapsedNs(-1)}
        assertEquals(276701161105L,TimecodeRate(30).framesForElapsedNs(Long.MAX_VALUE))
    }
    @Test fun canonicalLabelsUseAsciiRegardlessOfDeviceLocale() {
        val old=Locale.getDefault()
        try {Locale.setDefault(Locale.forLanguageTag("ar-EG"));assertEquals("01:02:03;04",SmpteTimecode(1,2,3,4,true).format())}
        finally{Locale.setDefault(old)}
    }
    @Test fun allFramesOfEachDropTenMinuteCycleRoundTrip() {
        for(fps in listOf(30,60)){val rate=TimecodeRate(fps,true)
            for(frame in 0 until fps*600L-9*(fps/15))assertEquals(frame,SmpteTimecode.fromTotalFrames(frame,rate).toTotalFrames(rate))
        }
    }
}
