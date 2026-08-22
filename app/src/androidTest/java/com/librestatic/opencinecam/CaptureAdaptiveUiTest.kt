/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.io.File
import java.io.FileOutputStream
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.AudioChannelLevel
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse

class CaptureAdaptiveUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun portraitDialKeepsPrimaryActionsVisible() {
        setChrome(landscape = false, selectorStyle = ModeSelectorStyle.DIAL)

        composeRule.onNodeWithTag("media-action", useUnmergedTree = true).assertIsEnabled()
        composeRule.onNodeWithContentDescription("Mode dial: PHOTO").assertIsEnabled()
        composeRule.onNodeWithText("FPS").assertIsEnabled()
        saveScreenshot("mode-wheel-portrait")
    }

    @Test
    fun landscapeDialRendersCenteredWheel() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.DIAL, selectedMode = CaptureMode.VIDEO)

        composeRule.onNodeWithContentDescription("Mode dial: VIDEO").assertIsEnabled()
        composeRule.onNodeWithText("Mbps", substring = true).assertIsEnabled()
        saveScreenshot("mode-wheel-landscape")
    }

    @Test
    fun recordingHidesConsoleButKeepsRecAndStop() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.DIAL, phase = CameraUiPhase.RECORDING)

        composeRule.onNodeWithContentDescription("Stop recording").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Mode dial: PHOTO").assertIsNotDisplayed()
        composeRule.onNodeWithTag("recording-stop-glyph", useUnmergedTree = true).assertIsEnabled()
    }

    @Test
    fun revealingRecordingConsoleKeepsExactlyOneSquareStopControl() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.DIAL, phase = CameraUiPhase.RECORDING)

        composeRule.onNodeWithTag("recording-reveal-surface").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithContentDescription("Stop recording").assertCountEquals(1)
        composeRule.onNodeWithTag("recording-stop-glyph", useUnmergedTree = true).assertIsEnabled()
        composeRule.onNodeWithContentDescription("Mode dial: PHOTO").assertIsEnabled()
    }

    @Test
    fun landscapeButtonsOpenAdaptiveModeGrid() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.BUTTONS)

        composeRule.onNodeWithText("MODE").performClick()
        composeRule.onNodeWithText("MODOS").assertIsDisplayed()
        composeRule.onNodeWithText("Video RAW").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun videoPreviewShowsLiveStereoMeterAndClipLatch() {
        setChrome(
            landscape = true,
            selectorStyle = ModeSelectorStyle.DIAL,
            selectedMode = CaptureMode.VIDEO,
            audioLevels = AudioLevelSnapshot(
                listOf(AudioChannelLevel(-2f, -9f), AudioChannelLevel(-18f, -24f)),
                clipped = true,
                capturedAtElapsedRealtimeMs = 1L,
            ),
        )

        composeRule.onNodeWithText("MIC").assertIsDisplayed()
        composeRule.onNodeWithText("CLIP").assertIsDisplayed()
        composeRule.onNodeWithText("L").assertIsDisplayed()
        composeRule.onNodeWithText("R").assertIsDisplayed()
    }

    @Test
    fun compactLogPreviewKeepsSourceBadgeClearOfAudioMeter() {
        setChrome(
            landscape = false,
            selectorStyle = ModeSelectorStyle.DIAL,
            selectedMode = CaptureMode.LOG,
            audioLevels = AudioLevelSnapshot(
                listOf(AudioChannelLevel(-12f, -20f), AudioChannelLevel(-15f, -24f)),
                clipped = false,
                capturedAtElapsedRealtimeMs = 1L,
            ),
        )

        val audioBounds = composeRule.onNodeWithTag("audio-meter-hud").fetchSemanticsNode().boundsInRoot
        val badgeBounds = composeRule.onNodeWithTag("log-source-badge").fetchSemanticsNode().boundsInRoot
        val overlaps = audioBounds.left < badgeBounds.right &&
            audioBounds.right > badgeBounds.left &&
            audioBounds.top < badgeBounds.bottom &&
            audioBounds.bottom > badgeBounds.top
        assertFalse("LOG source badge overlaps the microphone meter", overlaps)
    }

    private fun setChrome(
        landscape: Boolean,
        selectorStyle: ModeSelectorStyle,
        phase: CameraUiPhase = CameraUiPhase.PREVIEWING,
        selectedMode: CaptureMode = CaptureMode.PHOTO,
        audioLevels: AudioLevelSnapshot? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                AdaptiveCaptureChrome(
                    state = CameraUiState(
                        phase = phase,
                        selectedMode = selectedMode,
                        audioLevels = audioLevels,
                        audioClipLatched = audioLevels?.clipped == true,
                        audioMonitoringActive = audioLevels != null,
                    ),
                    binder = null,
                    settings = CameraSettings(modeSelectorStyle = selectorStyle),
                    landscape = landscape,
                    zebra = false,
                    peaking = false,
                    histogram = true,
                    histogramMode = HistogramMode.RGB,
                    onToggleZebra = {},
                    onTogglePeaking = {},
                    onToggleHistogram = {},
                    onCycleHistogramMode = {},
                    onOpenMedia = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    private fun saveScreenshot(name: String) {
        val image = composeRule.onRoot().captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$name.png")
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
