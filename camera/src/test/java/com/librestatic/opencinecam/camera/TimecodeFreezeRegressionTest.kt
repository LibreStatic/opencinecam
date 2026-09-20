/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera
import org.junit.Assert.assertEquals
import org.junit.Test

class TimecodeFreezeRegressionTest {
    @Test fun configurationDuringRecMustNotRelabelTheActiveTake() {
        val tracker = TimecodeTracker { 0 }
        tracker.configure(TimecodeRate(30), TimecodeMode.RECORD_RUN, SmpteTimecode(1, 0, 0, 0), true)
        tracker.onRecordingStarted()
        tracker.configure(TimecodeRate(24), TimecodeMode.REGEN, SmpteTimecode(2, 0, 0, 0), true)
        assertEquals("01:00:00:00", tracker.onFrame(0, 0)!!.format())
    }
}
