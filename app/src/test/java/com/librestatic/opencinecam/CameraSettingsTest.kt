/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.AfLockBehavior
import com.librestatic.opencinecam.camera.LockState
import com.librestatic.opencinecam.camera.ZoomLensSwitchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettingsTest {
    @Test(expected = IllegalArgumentException::class)
    fun timelapseProjectFpsRejectsValuesBeyondTheBoundedEncoderClock() { CameraSettings(timelapseFps = 121) }

    @Test fun timelapseStructuralIntentStaysPendingDuringRecording() {
        val before = CameraSettings()
        val next = before.copy(timelapseIntervalMs = 2000, timelapseFps = 25, timelapseFrameCount = 10, timelapseWidth = 640, timelapseHeight = 480)
        assertEquals(before, before.withLivePreferencesFrom(next))
        assertEquals(30, next.videoFps)
    }

    @Test
    fun defaultsAreSafeForFoldedLogPreflight() {
        val settings = CameraSettings()
        assertFalse(settings.flashEnabled)
        assertEquals(5, settings.burstCount)
        assertEquals(20, settings.videoBitrateMbps)
        assertTrue(settings.audioEnabled)
        assertTrue(settings.tapExposureMeteringEnabled)
        assertTrue(settings.compositionGridEnabled)
        assertEquals(CompositionGridMode.THIRDS, settings.compositionGridMode)
        assertFalse(settings.horizonLevelEnabled)
        assertEquals(ModeSelectorStyle.DIAL, settings.modeSelectorStyle)
        assertFalse(settings.translucentChrome)
        assertEquals(ViewfinderScale.FIT, settings.viewfinderScale)
        assertEquals(DEFAULT_CHROME_OPACITY, settings.chromeOpacity, 0f)
        assertEquals(RecordingGeometryMode.COMPATIBLE, settings.recordingGeometryMode)
        assertEquals(ZoomLensSwitchMode.MANUAL_PRESETS, settings.zoomLensSwitchMode)
        assertEquals(AfLockBehavior.FREEZE_CURRENT, settings.afLockBehavior)
    }

    @Test
    fun zoomLensSwitchModeAcceptsBothPolicies() {
        assertEquals(
            ZoomLensSwitchMode.AUTOMATIC,
            CameraSettings(zoomLensSwitchMode = ZoomLensSwitchMode.AUTOMATIC).zoomLensSwitchMode,
        )
        assertEquals(
            ZoomLensSwitchMode.MANUAL_PRESETS,
            CameraSettings(zoomLensSwitchMode = ZoomLensSwitchMode.MANUAL_PRESETS).zoomLensSwitchMode,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun burstCountRejectsUnboundedQueues() {
        CameraSettings(burstCount = 11)
    }

    @Test(expected = IllegalArgumentException::class)
    fun bitrateRejectsValuesOutsideQualifiedChoices() {
        CameraSettings(videoBitrateMbps = 25)
    }

    @Test
    fun burstIsAnAvailableFirstClassMode() {
        assertEquals(ModeGateState.AVAILABLE, CameraUiState.defaultModeGates.getValue(CaptureMode.BURST))
    }

    @Test(expected = IllegalArgumentException::class)
    fun videoGeometryRejectsNonPositiveDimensions() {
        CameraSettings(videoWidth = 0, videoHeight = 1080, videoFps = 30)
    }

    @Test(expected = IllegalArgumentException::class)
    fun logGeometryRejectsNonPositiveFps() {
        CameraSettings(logWidth = 1920, logHeight = 1080, logFps = 0)
    }

    @Test
    fun afLockBehaviorAcceptsBothModes() {
        assertEquals(
            AfLockBehavior.FREEZE_CURRENT,
            CameraSettings(afLockBehavior = AfLockBehavior.FREEZE_CURRENT).afLockBehavior,
        )
        assertEquals(
            AfLockBehavior.FOCUS_AND_LOCK,
            CameraSettings(afLockBehavior = AfLockBehavior.FOCUS_AND_LOCK).afLockBehavior,
        )
    }

    @Test
    fun lockStatesAreOrdered() {
        assertEquals(0, LockState.OFF.ordinal)
        assertEquals(1, LockState.PENDING.ordinal)
        assertEquals(2, LockState.LOCKED.ordinal)
    }

    @Test
    fun timelapseDefaultsAreSafe() {
        val settings = CameraSettings()
        assertEquals(500L, settings.timelapseIntervalMs)
        assertEquals(TimeLapseLimitMode.UNLIMITED, settings.timelapseLimitMode)
        assertEquals(300, settings.timelapseFrameCount)
        assertEquals(3_600_000L, settings.timelapseDurationMs)
        assertEquals(1920, settings.timelapseWidth)
        assertEquals(1080, settings.timelapseHeight)
        assertEquals(30, settings.timelapseFps)
    }

    @Test
    fun timelapseIntervalAcceptsValidRange() {
        assertEquals(100L, CameraSettings(timelapseIntervalMs = 100L).timelapseIntervalMs)
        assertEquals(3_600_000L, CameraSettings(timelapseIntervalMs = 3_600_000L).timelapseIntervalMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun timelapseIntervalRejectsBelowMinimum() {
        CameraSettings(timelapseIntervalMs = 99L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun timelapseIntervalRejectsAboveMaximum() {
        CameraSettings(timelapseIntervalMs = 3_600_001L)
    }

    @Test
    fun timelapseFrameCountAcceptsValidRange() {
        assertEquals(2, CameraSettings(timelapseFrameCount = 2).timelapseFrameCount)
        assertEquals(100_000, CameraSettings(timelapseFrameCount = 100_000).timelapseFrameCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun timelapseFrameCountRejectsBelowMinimum() {
        CameraSettings(timelapseFrameCount = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun timelapseFrameCountRejectsAboveMaximum() {
        CameraSettings(timelapseFrameCount = 100_001)
    }

    @Test
    fun timelapseDurationAcceptsValidRange() {
        assertEquals(1_000L, CameraSettings(timelapseDurationMs = 1_000L).timelapseDurationMs)
        assertEquals(86_400_000L, CameraSettings(timelapseDurationMs = 86_400_000L).timelapseDurationMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun timelapseDurationRejectsBelowMinimum() {
        CameraSettings(timelapseDurationMs = 999L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun timelapseDurationRejectsAboveMaximum() {
        CameraSettings(timelapseDurationMs = 86_400_001L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun timelapseGeometryRejectsNonPositiveDimensions() {
        CameraSettings(timelapseWidth = 0, timelapseHeight = 1080, timelapseFps = 30)
    }

    @Test
    fun timelapseLimitModeAcceptsAllModes() {
        assertEquals(TimeLapseLimitMode.UNLIMITED, CameraSettings(timelapseLimitMode = TimeLapseLimitMode.UNLIMITED).timelapseLimitMode)
        assertEquals(TimeLapseLimitMode.FRAME_COUNT, CameraSettings(timelapseLimitMode = TimeLapseLimitMode.FRAME_COUNT).timelapseLimitMode)
        assertEquals(TimeLapseLimitMode.DURATION, CameraSettings(timelapseLimitMode = TimeLapseLimitMode.DURATION).timelapseLimitMode)
    }

    @Test
    fun timelapseIsProfileBackedMode() {
        assertTrue(CaptureMode.TIME_LAPSE in CameraUiState.videoProfileModes)
        assertTrue(CaptureMode.TIME_LAPSE in CameraUiState.resolutionProfileModes)
    }

    @Test
    fun chromeOpacityIsClampedToALegibleRange() {
        assertEquals(MIN_CHROME_OPACITY, clampChromeOpacity(0f), 0f)
        assertEquals(MAX_CHROME_OPACITY, clampChromeOpacity(1f), 0f)
        assertEquals(0.6f, clampChromeOpacity(0.6f), 0f)
        assertEquals(DEFAULT_CHROME_OPACITY, clampChromeOpacity(Float.NaN), 0f)
    }

    @Test
    fun translucentChromeChangesApplyLive() {
        val next = CameraSettings().copy(translucentChrome = true, viewfinderScale = ViewfinderScale.FILL, chromeOpacity = 0.4f)
        val live = CameraSettings().withLivePreferencesFrom(next)
        assertTrue(live.translucentChrome)
        assertEquals(ViewfinderScale.FILL, live.viewfinderScale)
        assertEquals(0.4f, live.chromeOpacity, 0f)
    }
}
