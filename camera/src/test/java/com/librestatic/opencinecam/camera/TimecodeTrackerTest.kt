/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera
import org.junit.Assert.*
import org.junit.Test

class TimecodeTrackerTest {
    @Test fun freeRunUsesExactRationalClockForOneHour() {
        for(rate in listOf(TimecodeRate(30),TimecodeRate(30,true),TimecodeRate(60,true))){
            var now=123000000000L;val tracker=TimecodeTracker{now}
            tracker.configure(rate,TimecodeMode.FREE_RUN,SmpteTimecode(0,0,0,0,rate.dropFrame),true)
            now+=3600000000000L
            assertEquals(SmpteTimecode(1,0,0,0,rate.dropFrame),tracker.currentDisplayTc())
        }
    }
    @Test fun freeRunFrameObservationDoesNotAddClipIndexTwice() {
        var now=0L;val tracker=TimecodeTracker{now}
        tracker.configure(TimecodeRate(30),TimecodeMode.FREE_RUN,SmpteTimecode(0,0,0,0),true)
        now=10000000000L
        assertEquals("00:00:10:00",tracker.onFrame(10000000,300)!!.format())
    }
    @Test fun unchangedConfigurationDoesNotResetClock() {
        var now=100L;val tracker=TimecodeTracker{now};val rate=TimecodeRate(30)
        tracker.configure(rate,TimecodeMode.FREE_RUN,SmpteTimecode(1,0,0,0),true)
        now+=1000000000L;tracker.configure(rate,TimecodeMode.FREE_RUN,SmpteTimecode(1,0,0,0),true)
        assertEquals("01:00:01:00",tracker.currentDisplayTc()!!.format())
    }
    @Test fun invalidStartLabelDoesNotReplaceExistingConfiguration() {
        val tracker=TimecodeTracker{100L};tracker.configure(TimecodeRate(30),TimecodeMode.FREE_RUN,SmpteTimecode(1,0,0,0),true)
        assertThrows(IllegalArgumentException::class.java){tracker.configure(TimecodeRate(30,true),TimecodeMode.FREE_RUN,SmpteTimecode(0,1,0,0,true),true)}
        assertEquals("01:00:00:00",tracker.currentDisplayTc()!!.format())
    }
    @Test fun disabledTrackerDoesNotPublishTimecode() {
        val tracker=TimecodeTracker{100L};tracker.configure(TimecodeRate(30),TimecodeMode.FREE_RUN,SmpteTimecode(1,0,0,0),false)
        assertNull(tracker.currentDisplayTc());assertNull(tracker.onFrame(0,0))
    }
}
