/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.util.Size
import com.librestatic.opencinecam.camera.Camera2VideoProfile
import com.librestatic.opencinecam.driver.DriverCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.librestatic.opencinecam.capture.ModeSwitchPlan
import com.librestatic.opencinecam.capture.planModeSwitch
import com.librestatic.opencinecam.settings.CameraSettings

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

    private fun state(mode: CaptureMode, fps: Int, width: Int = 1920, height: Int = 1080) = CameraUiState(
        cameras = listOf(camera), selectedCameraId = camera.cameraId, selectedMode = mode,
        targetFps = fps, targetVideoWidth = width, targetVideoHeight = height,
    )

    @Test fun slowMotionFromVideoSettlesSizeAndRateWithoutReopening() {
        val settings = CameraSettings(videoOffSpeed = false)
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.VIDEO, fps = 30), settings, normalVideoFps = 24)

        val stored = plan.settings!!
        assertEquals(true, stored.videoOffSpeed)
        assertEquals(Triple(1920, 1080, 240), Triple(stored.videoWidth, stored.videoHeight, stored.videoFps))
        // Already on the VIDEO pipeline: only the rate changes, and the real-time rate is remembered.
        assertNull(plan.pipelineMode)
        assertEquals(240, plan.targetFps)
        assertEquals(30, plan.normalVideoFps)
    }

    @Test fun slowMotionFromPhotoOpensVideoOnce() {
        val plan = planModeSwitch(CaptureMode.SLOW_MOTION, state(CaptureMode.PHOTO, fps = 30), CameraSettings(), normalVideoFps = 30)
        assertEquals(CaptureMode.VIDEO, plan.pipelineMode)
        assertNull(plan.targetFps)
        assertEquals(240, plan.settings!!.videoFps)
    }

    @Test fun videoAfterSlowMotionReturnsToTheRealTimeRate() {
        val settings = CameraSettings(videoOffSpeed = true, videoFps = 240)
        val plan = planModeSwitch(CaptureMode.VIDEO, state(CaptureMode.VIDEO, fps = 240), settings, normalVideoFps = 25)

        assertEquals(false, plan.settings!!.videoOffSpeed)
        assertEquals(25, plan.settings!!.videoFps)
        assertNull(plan.pipelineMode)
        assertEquals(25, plan.targetFps)
        assertEquals(25, plan.normalVideoFps)
    }

    @Test fun anotherModeOnlySelectsIt() {
        val settings = CameraSettings()
        val plan = planModeSwitch(CaptureMode.PHOTO, state(CaptureMode.VIDEO, fps = 30), settings, normalVideoFps = 30)
        assertNull(plan.settings)
        assertEquals(CaptureMode.PHOTO, plan.pipelineMode)
        assertNull(plan.targetFps)
    }

    @Test fun theSelectedModeChangesNothing() {
        val plan = planModeSwitch(CaptureMode.PHOTO, state(CaptureMode.PHOTO, fps = 30), CameraSettings(), normalVideoFps = 30)
        assertEquals(ModeSwitchPlan(settings = null, pipelineMode = null, targetFps = null, normalVideoFps = 30), plan)
    }
}
