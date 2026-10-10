/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CineSliderHapticTest {
    private val range = 0f..10f

    @Test fun reachingAnEndIsALimitOnlyOnTheTransition() {
        assertEquals(HapticEvent.Limit, sliderHapticFor(5f, 10f, 0, range))
        assertEquals(HapticEvent.Limit, sliderHapticFor(5f, 0f, 0, range))
        assertNull(sliderHapticFor(10f, 10f, 0, range))
        assertEquals(HapticEvent.Limit, sliderHapticFor(0f, 10f, 0, range))
    }

    @Test fun stepsTickWhenTheIndexChanges() {
        assertEquals(HapticEvent.SliderStep, sliderHapticFor(2f, 3f, 9, range))
        assertNull(sliderHapticFor(2f, 2.2f, 9, range))
        assertEquals(HapticEvent.Limit, sliderHapticFor(9f, 10f, 9, range))
    }

    @Test fun continuousSlidersOnlyReportLimits() {
        assertNull(sliderHapticFor(2f, 3f, 0, range))
    }

    @Test fun zoomCrossingAnAnchorTicksAndEndsAreLimits() {
        val anchors = listOf(0.5f, 1f, 2f)
        assertEquals(HapticEvent.ZoomAnchor, zoomHapticFor(0.9f, 1.1f, anchors, 0.5f, 10f))
        assertEquals(HapticEvent.ZoomAnchor, zoomHapticFor(1.1f, 1f, anchors, 0.5f, 10f))
        assertNull(zoomHapticFor(1.1f, 1.5f, anchors, 0.5f, 10f))
        assertEquals(HapticEvent.Limit, zoomHapticFor(9f, 10f, anchors, 0.5f, 10f))
        assertNull(zoomHapticFor(10f, 10f, anchors, 0.5f, 10f))
    }
}
