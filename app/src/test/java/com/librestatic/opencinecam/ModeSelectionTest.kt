/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.CaptureFrameRate
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

    private val project30 = CaptureFrameRate(30)

    @Test fun anyRateFasterThanTheProjectIsSlowMotion() {
        assertTrue(slowsDown(60, project30))
        assertFalse(slowsDown(30, project30))
        assertFalse(slowsDown(24, project30))
        // 29.97 project: 30 fps is barely faster, still slow motion.
        assertTrue(slowsDown(30, CaptureFrameRate(30000, 1001)))
        assertTrue(slowsDown(48, CaptureFrameRate(24)))
    }

    @Test fun slowMotionOffersTheSizesWithARateFasterThanTheProject() {
        assertEquals(setOf(3840 to 2160, 1920 to 1080, 1280 to 720), slowMotionSizes(sampleDevice, project30))
        // 1080p60 counts in a 30 fps project, 4K30 does not.
        assertEquals(setOf(1920 to 1080), slowMotionSizes(listOf(profile(3840, 2160, 30), profile(1920, 1080, 60)), project30))
        // A 60 fps project leaves only the high-speed sizes.
        assertEquals(setOf(3840 to 2160, 1920 to 1080, 1280 to 720), slowMotionSizes(sampleDevice, CaptureFrameRate(60)))
        assertTrue(slowMotionSizes(listOf(profile(1920, 1080, 60)), CaptureFrameRate(60)).isEmpty())
    }

    @Test fun aSlowMotionSizeChangeKeepsARateFasterThanTheProject() {
        // 4K120 to 1080p keeps 120, which 1080p also offers.
        assertEquals(120, slowMotionRateAt(sampleDevice, 1920, 1080, currentFps = 120, projectRate = project30))
        // 1080p60 is slow motion too: a move back to 1080p keeps it.
        assertEquals(60, slowMotionRateAt(sampleDevice, 1920, 1080, currentFps = 60, projectRate = project30))
        // 1080p240 to 4K: 240 is not offered there, so the fastest 4K rate, never 30.
        assertEquals(120, slowMotionRateAt(sampleDevice, 3840, 2160, currentFps = 240, projectRate = project30))
        // 720p has 240 only.
        assertEquals(240, slowMotionRateAt(sampleDevice, 1280, 720, currentFps = 120, projectRate = project30))
        assertNull(slowMotionRateAt(sampleDevice, 2560, 1440, currentFps = 120, projectRate = project30))
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
