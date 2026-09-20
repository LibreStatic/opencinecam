/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TimelapseUiTest {
    @get:Rule val compose = createComposeRule()
    private val memory = PresetPreferences()
    private val repository = SettingsRepository(CameraSettingsStore(memory))
    private fun content(recording: Boolean = false) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(420.dp, 740.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    val settings by repository.states.collectAsState()
                    MaterialTheme(colorScheme = darkColorScheme()) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        TimelapseSettings(CameraUiState(selectedMode = CaptureMode.TIME_LAPSE,
                            phase = if (recording) CameraUiPhase.RECORDING else CameraUiPhase.PREVIEWING,
                            timelapseFramesCaptured = 3, timelapseMissedIntervals = 2), settings, repository::set)
                    } }
                }
            }
        }
    }
    @Test fun pauseControlUsesAcknowledgedStateAndRemainsSeparateFromStopAtDoubleFont() {
        var state by mutableStateOf(CameraUiState(phase = CameraUiPhase.RECORDING,
            recordingPauseStatus = com.librestatic.opencinecam.camera.TimelapsePauseStatus(1,false,false,0,0,0,emptyList())))
        var requested: Boolean? = null
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme { RecordingPauseButton(state) { requested = it } }
            }
        }
        compose.onNodeWithTag("recording-pause").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(true,requested)
        compose.runOnIdle { state = state.copy(recordingPausePending = true) }
        compose.onNodeWithTag("recording-pause").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(recordingPausePending = false, recordingPauseStatus = state.recordingPauseStatus!!.copy(paused = true)) }
        compose.onNodeWithTag("recording-pause").performClick();assertEquals(false,requested)
        compose.runOnIdle { state = state.copy(recordingFinalizing = true) }
        compose.onNodeWithTag("recording-pause").assertIsNotEnabled()
    }
    @Test fun modeEnumAloneDoesNotExposeAnUnqualifiedPauseButton() {
        compose.setContent { MaterialTheme { RecordingPauseButton(CameraUiState(phase = CameraUiPhase.RECORDING, selectedMode = CaptureMode.TIME_LAPSE)) {} } }
        compose.onNodeWithTag("recording-pause").assertDoesNotExist()
    }
    @Test fun subjectStatusAcknowledgesPauseWithoutExposingOperatorActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.RECORDING,
            recordingPauseStatus = com.librestatic.opencinecam.camera.TimelapsePauseStatus(1,true,false,0,0,0,emptyList())),
            SubjectDisplaySettings(mode = SubjectDisplayMode.STATUS)) } }
        compose.onNodeWithTag("subject-capture-status").assertTextEquals(context.getString(R.string.recording_paused))
        compose.onNodeWithTag("recording-pause").assertDoesNotExist()
    }
    @Test fun narrowPortraitTransportSeparatesPauseAndStopAtDoubleFont() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(240.dp, 340.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        PortraitCaptureTransport(CameraUiState(phase = CameraUiPhase.RECORDING,
                            recordingPauseStatus = com.librestatic.opencinecam.camera.TimelapsePauseStatus(1,true,false,0,0,0,emptyList())),
                            null, CameraSettings()) {}
                    }
                }
            }
        }
        val pause = compose.onNodeWithTag("recording-pause").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val stop = compose.onNodeWithContentDescription(context.getString(R.string.stop_recording)).assertIsDisplayed()
        assertTrue(pause.getUnclippedBoundsInRoot().top >= stop.getUnclippedBoundsInRoot().bottom)
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            java.io.File(context.cacheDir,"PAUSE_TRANSPORT.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
            }
        }
    }
    @Test fun compactPauseUsesAnAccessibleGlyphInsteadOfSplittingItsLabel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme { Box(Modifier.width(96.dp)) {
                    RecordingPauseButton(CameraUiState(phase = CameraUiPhase.RECORDING,
                        recordingPauseStatus = com.librestatic.opencinecam.camera.TimelapsePauseStatus(1,true,false,0,0,0,emptyList()))) {}
                } }
            }
        }
        compose.onNodeWithTag("recording-pause").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals(context.getString(R.string.recording_resume))
        val bounds = compose.onNodeWithTag("recording-pause").getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left <= 96.dp)
    }
    @Test fun invalidIntervalDoesNotSaveAndValidIntegerPersistsAtDoubleFont() {
        content()
        compose.onNodeWithTag("timelapse-interval").performScrollTo().performTextReplacement("99")
        compose.onNodeWithTag("timelapse-interval-apply").performScrollTo().assertIsNotEnabled()
        assertEquals(500L, repository.states.value.timelapseIntervalMs)
        compose.onNodeWithTag("timelapse-interval").performScrollTo().performTextReplacement("1250")
        compose.onNodeWithTag("timelapse-interval-apply").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1250L, CameraSettingsStore(memory).load().timelapseIntervalMs)
    }
    @Test fun projectRateIsIndependentOfTheSensorVideoPreferenceAndRoundTripsInPresets() {
        content()
        compose.onNodeWithTag("timelapse-fps-25").performScrollTo().performClick()
        assertEquals(25, CameraSettingsStore(memory).load().timelapseFps)
        assertEquals(30, repository.states.value.videoFps)
        val preset = CameraPreset(name = "Interval", settings = repository.states.value)
        assertEquals(preset.settings, CameraPresetCodec.decode(CameraPresetCodec.encode(preset)).settings)
    }
    @Test fun frameLimitAndActualProgressRemainReachableWhilePending() {
        content(recording = true)
        compose.onNodeWithTag("timelapse-pending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("timelapse-limit-FRAME_COUNT").performScrollTo().performClick()
        compose.onNodeWithTag("timelapse-frames").performScrollTo().performTextReplacement("3")
        compose.onNodeWithTag("timelapse-frames-apply").performScrollTo().performClick()
        compose.onNodeWithTag("timelapse-actual").performScrollTo().assertIsDisplayed()
        assertEquals(3, CameraSettingsStore(memory).load().timelapseFrameCount)
        assertEquals(TimeLapseLimitMode.FRAME_COUNT, CameraSettingsStore(memory).load().timelapseLimitMode)
    }
    @Test fun rationalProjectSelectionPersistsAndRemainsDistinctFromTheActiveTake() {
        content(recording = true)
        compose.onNodeWithTag("timelapse-fps-30000/1001").performScrollTo().performClick()
        assertEquals(com.librestatic.opencinecam.camera.CaptureFrameRate(30000,1001),CameraSettingsStore(memory).load().timelapseProjectRate)
        assertEquals(30,repository.states.value.videoFps)
    }
    @Test fun offSpeedIsAnExplicitSilentVideoChoiceAndItsRatePersists() {
        compose.setContent {
            val settings by repository.states.collectAsState()
            MaterialTheme(colorScheme = darkColorScheme()) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ProjectTimingSettings(CameraUiState(), settings, repository::set)
            } }
        }
        compose.onNodeWithTag("video-off-speed").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("video-fps-24000/1001").performScrollTo().performClick()
        assertTrue(repository.states.value.audioEnabled);assertTrue(CameraSettingsStore(memory).load().videoOffSpeed)
        assertEquals(com.librestatic.opencinecam.camera.CaptureFrameRate(24000,1001),CameraSettingsStore(memory).load().videoProjectRate)
    }
    @Test fun intervalAndProjectSearchReachRecordingSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (query in listOf("intervalo", "timelapse", "proyecto FPS", "limite duracion", "pausa", "resume")) {
            assertTrue(SettingsCatalog.search(query, SettingsCategory.RECORDING) { context.getString(it) }.contains("timelapse"))
        }
    }
}
