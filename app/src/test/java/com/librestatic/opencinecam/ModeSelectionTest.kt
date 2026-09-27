/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeSelectionTest {
    private fun profile(w: Int, h: Int, fps: Int) = VideoProfileSpec(w, h, fps)

    // Sample foldable main camera: 60 fps normal sessions; 120/240 only through high-speed sessions.
    private val sampleDevice = listOf(
        profile(3840, 2160, 30), profile(1920, 1080, 30), profile(1920, 1080, 60),
        profile(3840, 2160, 120), profile(1920, 1080, 120), profile(1920, 1080, 240), profile(1280, 720, 240),
    )

    @Test fun offSpeedVideoReadsAsSlowMotion() {
        assertEquals(CaptureMode.SLOW_MOTION, displayedCaptureMode(CaptureMode.VIDEO, videoOffSpeed = true))
        assertEquals(CaptureMode.VIDEO, displayedCaptureMode(CaptureMode.VIDEO, videoOffSpeed = false))
        assertEquals(CaptureMode.LOG, displayedCaptureMode(CaptureMode.LOG, videoOffSpeed = true))
    }

    @Test fun slowMotionNeedsAHighSpeedProfile() {
        assertTrue(supportsSlowMotion(sampleDevice))
        assertFalse(supportsSlowMotion(listOf(profile(1920, 1080, 30), profile(1920, 1080, 60))))
    }

    @Test fun slowMotionPicksTheFastestRateAtTheCurrentSize() {
        assertEquals(profile(1920, 1080, 240), slowMotionProfile(sampleDevice, 1920, 1080))
        assertEquals(profile(3840, 2160, 120), slowMotionProfile(sampleDevice, 3840, 2160))
        // No high-speed profile at 1440p: the fastest rate, at its largest frame.
        assertEquals(profile(1920, 1080, 240), slowMotionProfile(sampleDevice, 2560, 1440))
        assertNull(slowMotionProfile(listOf(profile(1920, 1080, 60)), 1920, 1080))
    }

    @Test fun unsupportedAndUnintegratedModesStayOffTheDial() {
        val gates = CameraUiState.defaultModeGates + (CaptureMode.SLOW_MOTION to ModeGateState.AVAILABLE)
        val visible = visibleCaptureModes(gates, CaptureMode.VIDEO)
        assertFalse(CaptureMode.APV in visible)
        assertFalse(CaptureMode.RAW_VIDEO in visible)
        assertTrue(CaptureMode.SLOW_MOTION in visible)
        // The displayed mode never disappears from under the selection marker.
        assertTrue(CaptureMode.APV in visibleCaptureModes(gates, CaptureMode.APV))
    }

    @Test fun onlyRecordingModesChooseAFrameRate() {
        assertTrue(CaptureMode.VIDEO in CameraUiState.frameRateModes)
        assertTrue(CaptureMode.LOG in CameraUiState.frameRateModes)
        listOf(CaptureMode.PHOTO, CaptureMode.RAW_PHOTO, CaptureMode.BURST, CaptureMode.BRACKET, CaptureMode.TIME_LAPSE)
            .forEach { assertFalse(it.name, it in CameraUiState.frameRateModes) }
    }
}
