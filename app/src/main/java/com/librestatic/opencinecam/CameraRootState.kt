/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * What the root screen shows: the section, the Settings page and whether the onboarding tour is up.
 *
 * The camera itself is owned by [com.librestatic.opencinecam.service.CaptureService]; this holds
 * only the navigation around it, so the transitions can be tested without composing the screen.
 * The section, the page and a replayed tour survive configuration changes and process death;
 * whether the tour is finished always comes from [onboarding].
 */
@Stable
internal class CameraRootNavigation(
    private val onboarding: OnboardingStore,
    section: AppSection = AppSection.CAPTURE,
    settingsPage: SettingsPage = SettingsPage.MAIN,
    tourReplay: Boolean = false,
) {
    var section by mutableStateOf(section)
        private set
    var settingsPage by mutableStateOf(settingsPage)
        private set
    var onboardingDone by mutableStateOf(onboarding.isCompleted())
        private set

    /** The tour was started again from About rather than on a first run. */
    var tourReplay by mutableStateOf(tourReplay)
        private set

    /** Back leaves Media or the Settings home for the viewfinder. */
    val sectionBackEnabled: Boolean get() = section != AppSection.CAPTURE && settingsPage == SettingsPage.MAIN

    /** Back leaves a Settings subpage for the Settings home. */
    val pageBackEnabled: Boolean get() = section == AppSection.SETTINGS && settingsPage != SettingsPage.MAIN

    fun backToCapture() {
        section = AppSection.CAPTURE
    }

    fun backToSettingsHome() {
        settingsPage = SettingsPage.MAIN
    }

    /** A section chosen from the shell's bar or rail always opens at its home. */
    fun select(target: AppSection) {
        settingsPage = SettingsPage.MAIN
        section = target
    }

    fun openMedia() {
        section = AppSection.MEDIA
    }

    fun openSettings() = select(AppSection.SETTINGS)

    fun openSettingsPage(page: SettingsPage) {
        settingsPage = page
    }

    /** The calibration run happens on the viewfinder. */
    fun showCaptureForCalibration() = select(AppSection.CAPTURE)

    /** Like the first run, a tour replayed from About ends (Skip or Done) on the viewfinder. */
    fun replayTour() {
        onboarding.reset()
        select(AppSection.CAPTURE)
        tourReplay = true
        onboardingDone = false
    }

    fun finishTour() {
        onboarding.markCompleted()
        onboardingDone = true
        tourReplay = false
    }

    companion object {
        fun saver(onboarding: OnboardingStore): Saver<CameraRootNavigation, Any> = listSaver(
            save = { listOf(it.section.name, it.settingsPage.name, it.tourReplay) },
            restore = {
                CameraRootNavigation(
                    onboarding = onboarding,
                    section = AppSection.valueOf(it[0] as String),
                    settingsPage = SettingsPage.valueOf(it[1] as String),
                    tourReplay = it[2] as Boolean,
                )
            },
        )
    }
}

@Composable
internal fun rememberCameraRootNavigation(onboarding: OnboardingStore): CameraRootNavigation =
    rememberSaveable(onboarding, saver = CameraRootNavigation.saver(onboarding)) { CameraRootNavigation(onboarding) }

/**
 * Settings that follow what the camera settled on for the selected mode, so the next start of the
 * mode opens at the same size and rate. Unchanged while recording, and for modes that keep none.
 */
internal fun CameraSettings.followingCameraSelection(state: CameraUiState): CameraSettings {
    if (state.phase == CameraUiPhase.RECORDING) return this
    return when (state.selectedMode) {
        CaptureMode.LOG, CaptureMode.HLG -> copy(
            logWidth = state.targetVideoWidth,
            logHeight = state.targetVideoHeight,
            logFps = state.targetFps,
        )
        CaptureMode.TIME_LAPSE -> copy(
            timelapseWidth = state.targetVideoWidth,
            timelapseHeight = state.targetVideoHeight,
        )
        in CameraUiState.videoProfileModes -> copy(
            videoWidth = state.targetVideoWidth,
            videoHeight = state.targetVideoHeight,
            videoFps = state.targetFps,
        )
        else -> this
    }
}
