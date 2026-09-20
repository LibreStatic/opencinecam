/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera
import org.junit.Assert.assertEquals
import org.junit.Test
class TimecodeInverseRegressionTest {
    @Test fun df30OneHourContains107892Frames() {
        assertEquals(107892L, SmpteTimecode(1,0,0,0,true).toTotalFrames(TimecodeRate(30,true)))
    }
}
