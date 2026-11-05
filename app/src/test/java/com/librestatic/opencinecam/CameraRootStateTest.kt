/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.librestatic.opencinecam.onboarding.OnboardingStore
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.settings.PresetPreferences

class CameraRootStateTest {
    private fun store(completed: Boolean) = OnboardingStore(PresetPreferences()).apply { if (completed) markCompleted() }

    @Test fun startsOnTheViewfinderWithTheStoredTourState() {
        val first = CameraRootNavigation(store(completed = false))
        assertEquals(AppSection.CAPTURE, first.section)
        assertEquals(SettingsPage.MAIN, first.settingsPage)
        assertFalse(first.onboardingDone)
        assertFalse(first.tourReplay)
        assertTrue(CameraRootNavigation(store(completed = true)).onboardingDone)
    }

    @Test fun aSectionFromTheShellOpensAtItsHome() {
        val navigation = CameraRootNavigation(store(true), section = AppSection.SETTINGS, settingsPage = SettingsPage.ABOUT)
        navigation.select(AppSection.MEDIA)
        assertEquals(AppSection.MEDIA, navigation.section)
        assertEquals(SettingsPage.MAIN, navigation.settingsPage)
    }

    @Test fun backLeavesTheSubpageBeforeTheSection() {
        val navigation = CameraRootNavigation(store(true))
        assertFalse(navigation.sectionBackEnabled)
        assertFalse(navigation.pageBackEnabled)

        navigation.openSettings()
        navigation.openSettingsPage(SettingsPage.CAPABILITIES)
        assertTrue(navigation.pageBackEnabled)
        assertFalse(navigation.sectionBackEnabled)

        navigation.backToSettingsHome()
        assertEquals(SettingsPage.MAIN, navigation.settingsPage)
        assertTrue(navigation.sectionBackEnabled)
        assertFalse(navigation.pageBackEnabled)

        navigation.backToCapture()
        assertEquals(AppSection.CAPTURE, navigation.section)
        assertFalse(navigation.sectionBackEnabled)
    }

    @Test fun mediaKeepsTheSettingsPageForItsReturn() {
        val navigation = CameraRootNavigation(store(true), settingsPage = SettingsPage.ABOUT)
        navigation.openMedia()
        assertEquals(AppSection.MEDIA, navigation.section)
        assertEquals(SettingsPage.ABOUT, navigation.settingsPage)
    }

    @Test fun calibrationRunsOnTheViewfinder() {
        val navigation = CameraRootNavigation(store(true), section = AppSection.SETTINGS, settingsPage = SettingsPage.LENS_CALIBRATION)
        navigation.showCaptureForCalibration()
        assertEquals(AppSection.CAPTURE, navigation.section)
        assertEquals(SettingsPage.MAIN, navigation.settingsPage)
    }

    @Test fun aReplayedTourResetsTheStoreAndEndsOnTheViewfinder() {
        val onboarding = store(completed = true)
        val navigation = CameraRootNavigation(onboarding, section = AppSection.SETTINGS, settingsPage = SettingsPage.ABOUT)

        navigation.replayTour()
        assertFalse(onboarding.isCompleted())
        assertFalse(navigation.onboardingDone)
        assertTrue(navigation.tourReplay)
        assertEquals(AppSection.CAPTURE, navigation.section)
        assertEquals(SettingsPage.MAIN, navigation.settingsPage)

        navigation.finishTour()
        assertTrue(onboarding.isCompleted())
        assertTrue(navigation.onboardingDone)
        assertFalse(navigation.tourReplay)
    }

    @Test fun theSaverKeepsNavigationAndRereadsTheTourFromTheStore() {
        val onboarding = store(completed = false)
        val saver = CameraRootNavigation.saver(onboarding)
        val navigation = CameraRootNavigation(onboarding, section = AppSection.SETTINGS, settingsPage = SettingsPage.STORAGE_BENCHMARK, tourReplay = true)
        val saved = with(saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(navigation) }!!
        onboarding.markCompleted()

        val restored = saver.restore(saved)!!
        assertEquals(AppSection.SETTINGS, restored.section)
        assertEquals(SettingsPage.STORAGE_BENCHMARK, restored.settingsPage)
        assertTrue(restored.tourReplay)
        assertTrue(restored.onboardingDone)
    }

    private val settings = CameraSettings()
    private fun state(mode: CaptureMode, phase: CameraUiPhase = CameraUiPhase.PREVIEWING) = CameraUiState(
        phase = phase, selectedMode = mode, targetVideoWidth = 1920, targetVideoHeight = 1080, targetFps = 60,
    )

    @Test fun videoSettingsFollowTheSelectedProfile() {
        val updated = settings.followingCameraSelection(state(CaptureMode.VIDEO))
        assertEquals(Triple(1920, 1080, 60), Triple(updated.videoWidth, updated.videoHeight, updated.videoFps))
    }

    @Test fun logAndHlgSettingsFollowTheirOwnProfile() {
        for (mode in listOf(CaptureMode.LOG, CaptureMode.HLG)) {
            val updated = settings.followingCameraSelection(state(mode))
            assertEquals(Triple(1920, 1080, 60), Triple(updated.logWidth, updated.logHeight, updated.logFps))
            assertEquals(settings.videoFps, updated.videoFps)
        }
    }

    @Test fun timeLapseFollowsTheSizeButNotTheRate() {
        val updated = settings.followingCameraSelection(state(CaptureMode.TIME_LAPSE))
        assertEquals(1920 to 1080, updated.timelapseWidth to updated.timelapseHeight)
        assertEquals(settings.videoFps, updated.videoFps)
    }

    @Test fun nothingFollowsWhileRecordingOrInStillModes() {
        assertSame(settings, settings.followingCameraSelection(state(CaptureMode.VIDEO, CameraUiPhase.RECORDING)))
        assertSame(settings, settings.followingCameraSelection(state(CaptureMode.PHOTO)))
    }
}
