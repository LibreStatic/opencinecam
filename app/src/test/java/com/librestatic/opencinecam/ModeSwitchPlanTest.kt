/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.util.Size
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2VideoProfile
import com.librestatic.opencinecam.capture.ModeSwitchPlan
import com.librestatic.opencinecam.capture.planModeSwitch
import com.librestatic.opencinecam.driver.DriverCamera
import com.librestatic.opencinecam.settings.CameraSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModeSwitchPlanTest {
    // The driver camera plus high-speed 1080p120/240 and 720p240.
    private val camera = DriverCamera.copy(
        videoProfiles = DriverCamera.videoProfiles + listOf(
            Camera2VideoProfile(Size(1920, 1080), 120, constrainedHighSpeed = true),
            Camera2VideoProfile(Size(1920, 1080), 240, constrainedHighSpeed = true),
            Camera2VideoProfile(Size(1280, 720), 240, constrainedHighSpeed = true),
        ),
    )

    private fun state(mode: CaptureMode, fps: Int, width: Int = 1920, height: Int = 1080, descriptor: Camera2CameraDescriptor = camera) =
        CameraUiState(
            cameras = listOf(descriptor), selectedCameraId = descriptor.cameraId, selectedMode = mode,
            targetFps = fps, targetVideoWidth = width, targetVideoHeight = height,
        )

    @Test fun slowMotionFromVideoSettlesSizeAndRateWithoutReopening() {
        val settings = CameraSettings(videoOffSpeed = false)
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.VIDEO, fps = 30), settings, normalVideoFps = 24)

        assertEquals(settings.copy(videoOffSpeed = true, videoWidth = 1920, videoHeight = 1080, videoFps = 240), plan.settings)
        // Already on the VIDEO pipeline: only the rate changes, and the real-time rate is remembered.
        assertNull(plan.pipelineMode)
        assertEquals(240, plan.targetFps)
        assertEquals(30, plan.normalVideoFps)
    }

    @Test fun slowMotionAtASizeWithoutHighSpeedTakesTheLargestFastest() {
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.VIDEO, fps = 60, width = 3840, height = 2160), CameraSettings(), 30)
        val stored = plan.settings!!
        assertEquals(Triple(1920, 1080, 240), Triple(stored.videoWidth, stored.videoHeight, stored.videoFps))
        assertEquals(240, plan.targetFps)
        // The size moves with the rate: a rate alone would be snapped against 4K and stay there.
        assertEquals(1920 to 1080, plan.targetSize)
        assertEquals(60, plan.normalVideoFps)
    }

    @Test fun slowMotionAtASizeWithHighSpeedOnlyChangesTheRate() {
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.VIDEO, fps = 30), CameraSettings(), 24)
        assertEquals(240, plan.targetFps)
        assertNull(plan.targetSize)
    }

    @Test fun slowMotionFromAnotherModeAtAnotherSizeLeavesTheSizeToTheModeChange() {
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.PHOTO, fps = 30, width = 3840, height = 2160), CameraSettings(), 25)
        assertEquals(CaptureMode.VIDEO, plan.pipelineMode)
        assertEquals(1920 to 1080, plan.settings!!.videoWidth to plan.settings!!.videoHeight)
        assertNull(plan.targetSize)
    }

    @Test fun slowMotionFromLogRemembersVideosStoredRateNotLogs() {
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.LOG, fps = 24), CameraSettings(videoFps = 30), 25)
        assertEquals(30, plan.normalVideoFps)
    }

    @Test fun slowMotionFromTimeLapseRemembersVideosStoredRate() {
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.TIME_LAPSE, fps = 30), CameraSettings(videoFps = 25), 24)
        assertEquals(25, plan.normalVideoFps)
    }

    @Test fun slowMotionFromAnotherModeOpensVideoOnce() {
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.PHOTO, fps = 30), CameraSettings(), normalVideoFps = 25)
        assertEquals(CaptureMode.VIDEO, plan.pipelineMode)
        // The mode change opens the camera at the stored rate; no separate rate step.
        assertNull(plan.targetFps)
        assertEquals(true, plan.settings!!.videoOffSpeed)
        assertEquals(240, plan.settings!!.videoFps)
        assertEquals(30, plan.normalVideoFps)
    }

    @Test fun slowMotionFromAHighSpeedRateKeepsTheRememberedRealTimeRate() {
        // A rate already at or above the high-speed threshold is not a real-time rate to return to.
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.VIDEO, fps = 120), CameraSettings(), normalVideoFps = 25)
        assertEquals(25, plan.normalVideoFps)
        assertEquals(240, plan.targetFps)
    }

    @Test fun slowMotionWithoutAHighSpeedProfileOnlyTurnsOffSpeedOn() {
        val settings = CameraSettings()
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.VIDEO, fps = 30, descriptor = DriverCamera), settings, 24)
        assertEquals(settings.copy(videoOffSpeed = true), plan.settings)
        assertNull(plan.pipelineMode)
        assertNull(plan.targetFps)
        assertEquals(30, plan.normalVideoFps)
    }

    @Test fun slowMotionChosenAgainRemembersNothingNew() {
        val settings = CameraSettings(videoOffSpeed = true, videoFps = 240)
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.VIDEO, fps = 60), settings, normalVideoFps = 25)
        assertEquals(25, plan.normalVideoFps)
    }

    @Test fun videoAfterSlowMotionReturnsToTheRealTimeRate() {
        val settings = CameraSettings(videoOffSpeed = true, videoFps = 240)
        val plan = planModeSwitch(CaptureMode.VIDEO, state(CaptureMode.VIDEO, fps = 240), settings, normalVideoFps = 25)

        assertEquals(settings.copy(videoOffSpeed = false, videoFps = 25), plan.settings)
        assertNull(plan.pipelineMode)
        assertEquals(25, plan.targetFps)
        assertNull(plan.targetSize)
        assertEquals(25, plan.normalVideoFps)
    }

    @Test fun videoFromAnotherModeAfterSlowMotionSelectsVideoAtTheRealTimeRate() {
        val settings = CameraSettings(videoOffSpeed = true, videoFps = 240)
        val plan = planModeSwitch(CaptureMode.VIDEO, state(CaptureMode.PHOTO, fps = 30), settings, normalVideoFps = 25)
        assertEquals(settings.copy(videoOffSpeed = false, videoFps = 25), plan.settings)
        assertEquals(CaptureMode.VIDEO, plan.pipelineMode)
        assertNull(plan.targetFps)
    }

    @Test fun anotherModeOnlySelectsIt() {
        val plan = planModeSwitch(CaptureMode.PHOTO, state(CaptureMode.VIDEO, fps = 30), CameraSettings(), normalVideoFps = 30)
        assertEquals(ModeSwitchPlan(settings = null, pipelineMode = CaptureMode.PHOTO, targetFps = null, targetSize = null, normalVideoFps = 30), plan)
    }

    @Test fun anotherModeKeepsTheOffSpeedSetting() {
        // Leaving slow motion for a mode other than Video keeps it for the return to the dial's slow motion.
        val settings = CameraSettings(videoOffSpeed = true, videoFps = 240)
        val plan = planModeSwitch(CaptureMode.LOG, state(CaptureMode.VIDEO, fps = 240), settings, normalVideoFps = 25)
        assertEquals(ModeSwitchPlan(settings = null, pipelineMode = CaptureMode.LOG, targetFps = null, targetSize = null, normalVideoFps = 25), plan)
    }

    @Test fun theSelectedModeChangesNothing() {
        val plan = planModeSwitch(CaptureMode.PHOTO, state(CaptureMode.PHOTO, fps = 30), CameraSettings(), normalVideoFps = 30)
        assertEquals(ModeSwitchPlan(settings = null, pipelineMode = null, targetFps = null, targetSize = null, normalVideoFps = 30), plan)
        val video = planModeSwitch(CaptureMode.VIDEO, state(CaptureMode.VIDEO, fps = 30), CameraSettings(), normalVideoFps = 24)
        assertEquals(ModeSwitchPlan(settings = null, pipelineMode = null, targetFps = null, targetSize = null, normalVideoFps = 24), video)
    }
}
