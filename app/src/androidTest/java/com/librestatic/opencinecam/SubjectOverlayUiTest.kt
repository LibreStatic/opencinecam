/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.librestatic.opencinecam.camera.AudioChannelLevel
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** OCC-PLAN-068 U1/U2 subject overlays: output-only, derived from service status. */
class SubjectOverlayUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tallyFollowsTheServiceStateInEveryMode() {
        val state = mutableStateOf(CameraUiState(phase = CameraUiPhase.PREVIEWING))
        val mode = mutableStateOf(SubjectDisplayMode.STATUS)
        compose.setContent { MaterialTheme { SubjectDisplayScreen(state.value, SubjectDisplaySettings(mode = mode.value)) } }
        for (current in SubjectDisplayMode.entries) {
            mode.value = current
            state.value = CameraUiState(phase = CameraUiPhase.PREVIEWING)
            compose.onNodeWithTag("subject-tally-recording").assertDoesNotExist()
            compose.onNodeWithTag("subject-tally-pending").assertDoesNotExist()
            state.value = CameraUiState(phase = CameraUiPhase.RECORDING)
            compose.onNodeWithTag("subject-tally-recording").assertExists()
            state.value = CameraUiState(phase = CameraUiPhase.SAVED, recordingFinalizing = true)
            compose.onNodeWithTag("subject-tally-pending").assertExists()
            compose.onNodeWithTag("subject-tally-recording").assertDoesNotExist()
            state.value = CameraUiState(phase = CameraUiPhase.SAVED)
            compose.onNodeWithTag("subject-tally-pending").assertDoesNotExist()
        }
    }

    @Test fun tallyToggleHidesTheBorder() {
        compose.setContent {
            MaterialTheme { SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.RECORDING), SubjectDisplaySettings(tallyBorder = false)) }
        }
        compose.onNodeWithTag("subject-tally-recording").assertDoesNotExist()
    }

    @Test fun giantCountdownAnnouncesAndReplacesTheSmallBadgeAt200PercentFont() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme { SubjectDisplayScreen(CameraUiState(countdownSeconds = 3), SubjectDisplaySettings(mode = SubjectDisplayMode.TELEPROMPTER)) }
            }
        }
        val numeral = compose.onNodeWithTag("subject-giant-countdown").assertIsDisplayed()
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite,
            numeral.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        compose.onNodeWithTag("capture-countdown").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun smallBadgeReturnsWhenTheGiantCountdownIsOff() {
        compose.setContent {
            MaterialTheme { SubjectDisplayScreen(CameraUiState(countdownSeconds = 3), SubjectDisplaySettings(giantCountdown = false)) }
        }
        compose.onNodeWithTag("subject-giant-countdown").assertDoesNotExist()
        compose.onNodeWithTag("capture-countdown").assertIsDisplayed()
    }

    @Test fun selfMonitorDrawsFrameGuideAndMeterOnlyWhenAsked() {
        val now = SystemClock.elapsedRealtime()
        val levels = AudioLevelSnapshot(listOf(AudioChannelLevel(peakDbfs = -20f, rmsDbfs = -30f)), clipped = false, capturedAtElapsedRealtimeMs = now)
        val settings = mutableStateOf(SubjectDisplaySettings(previewRecordedAreaBands = false))
        val state = CameraUiState(selectedMode = CaptureMode.VIDEO, audioLevels = levels, audioMonitoringActive = true)
        compose.setContent { MaterialTheme { SubjectSelfMonitorOverlay(state, settings.value, rotationDegrees = 0, Modifier.fillMaxSize()) } }
        // Without a camera descriptor the stream is unknown, so no frame is guessed.
        compose.onNodeWithTag("subject-self-monitor-frame").assertDoesNotExist()
        compose.onNodeWithTag("subject-audio-meter").assertDoesNotExist()
        settings.value = SubjectDisplaySettings(previewAudioMeter = true, previewGuide = SubjectPreviewGuide.THIRDS)
        compose.onNodeWithTag("subject-audio-meter").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
