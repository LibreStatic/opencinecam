/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicFpsTest {
    @Test fun configMatchesTheMotorolaValuesForSixty() {
        assertArrayEquals(floatArrayOf(2f, 30f, 60f), DynamicFps.config(60), 0f)
    }

    @Test fun configFloorsAtFifteen() {
        assertArrayEquals(floatArrayOf(2f, 15f, 30f), DynamicFps.config(30), 0f)
        assertArrayEquals(floatArrayOf(2f, 15f, 24f), DynamicFps.config(24), 0f)
        assertArrayEquals(floatArrayOf(2f, 10f, 10f), DynamicFps.config(10), 0f)
    }

    @Test fun staticSupportNeedsAnExplicitOne() {
        assertTrue(DynamicFps.staticSupport(intArrayOf(1)))
        assertFalse(DynamicFps.staticSupport(intArrayOf(0)))
        assertFalse(DynamicFps.staticSupport(intArrayOf()))
        assertFalse(DynamicFps.staticSupport(null))
    }

    @Test fun offeredNeedsEveryPart() {
        assertTrue(DynamicFps.offered(true, true, true, true))
        assertFalse(DynamicFps.offered(false, true, true, true))
        assertFalse(DynamicFps.offered(true, false, true, true))
        assertFalse(DynamicFps.offered(true, true, false, true))
        assertFalse(DynamicFps.offered(true, true, true, false))
    }

    @Test fun onlyRegularVideoMayUseIt() {
        assertTrue(DynamicFps.allowed(videoMode = true, logGraph = false, constrainedHighSpeed = false))
        assertFalse(DynamicFps.allowed(videoMode = false, logGraph = false, constrainedHighSpeed = false))
        assertFalse(DynamicFps.allowed(videoMode = true, logGraph = true, constrainedHighSpeed = false))
        assertFalse(DynamicFps.allowed(videoMode = true, logGraph = false, constrainedHighSpeed = true))
    }

    @Test fun offIsTheDefaultEvenWhenAllowed() {
        assertFalse(DynamicFps.enabled(requested = false, allowed = true))
        assertTrue(DynamicFps.enabled(requested = true, allowed = true))
        assertFalse(DynamicFps.enabled(requested = true, allowed = false))
    }
}
