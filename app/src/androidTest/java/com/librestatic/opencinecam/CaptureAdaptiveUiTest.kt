/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import java.io.File
import java.io.FileOutputStream
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.AudioChannelLevel
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.LockState
import com.librestatic.opencinecam.camera.ZoomAnchor
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class CaptureAdaptiveUiTest {
    private var chromeDensity = 1f
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun portraitDialKeepsPrimaryActionsVisible() {
        setChrome(landscape = false, selectorStyle = ModeSelectorStyle.DIAL)

        composeRule.onNodeWithTag("media-action", useUnmergedTree = true).assertIsEnabled()
        composeRule.onNodeWithContentDescription(modeDial(CaptureMode.PHOTO)).assertIsEnabled()
        composeRule.onNodeWithText("ISO").assertIsEnabled()
        // Stills have no recording rate: the FPS control belongs to video and LOG only.
        composeRule.onNodeWithText("FPS").assertDoesNotExist()
        saveScreenshot("mode-wheel-portrait")
    }

    @Test
    fun portraitDialUpdatesFocusedModeWhileSwiping() {
        setChrome(landscape = false, selectorStyle = ModeSelectorStyle.DIAL)

        composeRule.onNodeWithContentDescription(modeDial(CaptureMode.PHOTO)).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        composeRule.onAllNodes(isSelected()).assertCountEquals(1)
    }

    @Test
    fun landscapeDialRendersCenteredWheel() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.DIAL, selectedMode = CaptureMode.VIDEO)

        composeRule.onNodeWithContentDescription(modeDial(CaptureMode.VIDEO)).assertIsEnabled()
        composeRule.onNodeWithText("Mbps", substring = true).assertIsEnabled()
        saveScreenshot("mode-wheel-landscape")
    }

    @Test
    fun recordingHidesConsoleButKeepsRecAndStop() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.DIAL, phase = CameraUiPhase.RECORDING)

        composeRule.onNodeWithContentDescription("Stop recording").assertIsEnabled()
        composeRule.onNodeWithContentDescription(modeDial(CaptureMode.PHOTO)).assertIsNotDisplayed()
        composeRule.onNodeWithTag("recording-stop-glyph", useUnmergedTree = true).assertIsEnabled()
    }

    @Test
    fun revealingRecordingConsoleKeepsExactlyOneSquareStopControl() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.DIAL, phase = CameraUiPhase.RECORDING)

        composeRule.onNodeWithTag("recording-reveal-surface").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithContentDescription("Stop recording").assertCountEquals(1)
        composeRule.onNodeWithTag("recording-stop-glyph", useUnmergedTree = true).assertIsEnabled()
        composeRule.onNodeWithContentDescription(modeDial(CaptureMode.PHOTO)).assertIsEnabled()
    }

    @Test
    fun landscapeButtonsOpenAdaptiveModeGrid() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.BUTTONS)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.mode_title)).performClick()
        val modesLabel = context.getString(R.string.modes_title)
        composeRule.onNodeWithText(modesLabel).assertIsDisplayed()
        val rawVideoLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.raw_video_mode)
        composeRule.onNodeWithText(rawVideoLabel).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun videoPreviewShowsLiveStereoMeterAndClipLatch() {
        // Portrait: the forced 800x360 landscape fixture keeps the portrait emulator's system-bar
        // insets, which leaves no room above the deck, and the instrument stack omits whatever
        // does not fit whole instead of drawing it under the controls.
        setChrome(
            landscape = false,
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

    @Test
    fun zoomAnchorBarShowsOpticalAnchors() {
        setChrome(landscape = false, selectorStyle = ModeSelectorStyle.DIAL, zoomSupported = true, anchors = listOf(
            ZoomAnchor(0.5f, 1.826f, "3"),
            ZoomAnchor(1f, 6.57f, null),
            ZoomAnchor(2f, 13.3f, "5"),
        ))
        composeRule.onNodeWithTag("zoom-anchor-0.5", useUnmergedTree = true).assertIsEnabled()
        composeRule.onNodeWithTag("zoom-anchor-1.0", useUnmergedTree = true).assertIsEnabled()
        composeRule.onNodeWithTag("zoom-anchor-2.0", useUnmergedTree = true).assertIsEnabled()
    }

    @Test
    fun compactPortraitZoomChromeClearsTheTopBar() {
        setChrome(
            landscape = false,
            selectorStyle = ModeSelectorStyle.DIAL,
            zoomSupported = true,
            anchors = listOf(
                ZoomAnchor(0.5f, 1.826f, "3"),
                ZoomAnchor(1f, 6.57f, null),
                ZoomAnchor(2f, 13.3f, "5"),
            ),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val media = composeRule.onNodeWithContentDescription(context.getString(R.string.media_tab)).fetchSemanticsNode().boundsInRoot
        val settings = composeRule.onNodeWithContentDescription(context.getString(R.string.settings_tab)).fetchSemanticsNode().boundsInRoot
        val anchor = composeRule.onNodeWithTag("zoom-anchor-0.5", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val ratio = composeRule.onNodeWithTag("zoom-ratio", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Zoom anchors overlap the top bar", anchor.top >= maxOf(media.bottom, settings.bottom))
        assertTrue("Zoom ratio overlaps the anchor bar", ratio.top >= anchor.bottom)
    }

    @Test
    fun zoomRockerIsDisplayedWhenZoomSupported() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.DIAL, zoomSupported = true)
        composeRule.onNodeWithTag("zoom-rocker", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun zoomRockerIsAbsentWhenZoomUnsupported() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.DIAL, zoomSupported = false)
        composeRule.onNodeWithTag("zoom-rocker", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun zoomRatioIndicatorShowsCurrentValue() {
        setChrome(landscape = false, selectorStyle = ModeSelectorStyle.DIAL, zoomSupported = true, zoomRatio = 2.4f)
        composeRule.onNodeWithTag("zoom-ratio", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aeLockToggleIsDisplayedWhenSupported() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.DIAL, aeLockSupported = true)
        composeRule.onNodeWithTag("ae-lock-toggle", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun afLockToggleIsDisplayedWhenSupported() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.DIAL, afLockSupported = true)
        composeRule.onNodeWithTag("af-lock-toggle", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun lockTogglesAreAbsentWhenUnsupported() {
        setChrome(landscape = true, selectorStyle = ModeSelectorStyle.DIAL, aeLockSupported = false, afLockSupported = false)
        composeRule.onNodeWithTag("ae-lock-toggle", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("af-lock-toggle", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun afLockToggleShowsPendingState() {
        setChrome(landscape = false, selectorStyle = ModeSelectorStyle.DIAL, afLockSupported = true, afLockState = LockState.PENDING)
        composeRule.onNodeWithTag("af-lock-toggle", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun customLockAndMonitorControlsMeetTouchTargetsAndStayInBounds() {
        setChrome(
            landscape = false,
            selectorStyle = ModeSelectorStyle.DIAL,
            aeLockSupported = true,
            afLockSupported = true,
        )
        composeRule.waitForIdle()
        val minimumPx = 48f * chromeDensity - 1f
        listOf("ae-lock-toggle", "af-lock-toggle").forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag width is below 48dp", bounds.width >= minimumPx)
            assertTrue("$tag height is below 48dp", bounds.height >= minimumPx)
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(context.getString(R.string.monitoring_tools)).performClick()
        composeRule.waitForIdle()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        listOf(
            R.string.monitor_zebra,
            R.string.monitor_peaking,
            R.string.monitor_histogram,
            R.string.monitor_histogram_mode,
            R.string.monitor_grid,
            R.string.monitor_grid_mode,
            R.string.monitor_horizon,
        ).map(context::getString).forEach { description ->
            val bounds = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
            assertTrue("$description width is below 48dp", bounds.width >= minimumPx)
            assertTrue("$description height is below 48dp", bounds.height >= minimumPx)
            assertTrue("$description is horizontally clipped", bounds.left >= root.left && bounds.right <= root.right)
            assertTrue("$description is vertically clipped", bounds.top >= root.top && bounds.bottom <= root.bottom)
        }
    }

    @Test
    fun settingsLastControlCanScrollFullyIntoTheSafeViewport() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = CameraUiState(),
                    settings = CameraSettings(),
                    audioPermissionGranted = false,
                    onRequestAudioPermission = {},
                    onOpenAbout = {},
                    onSettingsChange = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings-category-CAPTURE").performClick()
        val flashLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.flash_torch)
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText(flashLabel))
        val flash = composeRule.onNodeWithText(flashLabel).assertIsDisplayed()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = flash.fetchSemanticsNode().boundsInRoot
        assertTrue("Last settings row is clipped at the bottom", bounds.bottom <= root.bottom)
    }

    @Test
    fun settingsAboutEntryOpensTheAboutDestination() {
        var opened = false
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = CameraUiState(),
                    settings = CameraSettings(),
                    audioPermissionGranted = false,
                    onRequestAudioPermission = {},
                    onOpenAbout = { opened = true },
                    onSettingsChange = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings-category-DIAGNOSTICS").performScrollTo().performClick()
        val about = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.about_title)
        val aboutSummary = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.about_settings_summary)
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText(about))
        composeRule.onNodeWithContentDescription(aboutSummary).performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun aboutScreenShowsContributorVersionAndOpensProjectLink() {
        var openedUrl: String? = null
        composeRule.setContent {
            MaterialTheme {
                AboutScreen(onBack = {}, onOpenUri = { openedUrl = it })
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        composeRule.onNodeWithText("OpenCineCam contributors", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_version, version)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_source_code)).performClick()
        composeRule.runOnIdle {
            assertEquals("https://github.com/librestatic/opencinecam", openedUrl)
        }
    }

    @Test
    fun aboutScreenIncludesAndExpandsTheCompleteOfflineCatalog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val components = loadThirdPartyComponents(context)
        // The authority is verifyThirdPartyLicenses, which re-derives this catalog from
        // app/gradle.lockfile on every preBuild; this number is the deliberate review tripwire,
        // so bump it only together with the dependency that moved it.
        assertEquals(118, components.size)
        assertEquals(components.size, components.map { it.coordinate }.toSet().size)
        assertTrue(components.all { it.licenseId.isNotBlank() && it.licenseTextAsset.isNotBlank() })

        composeRule.setContent {
            MaterialTheme { AboutScreen(onBack = {}, onOpenUri = {}) }
        }
        val component = components.first()
        composeRule.onNodeWithTag("about-list").performScrollToNode(hasText(component.name))
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.about_expand_license, component.name),
        ).performClick()
        val licenseText = context.assets.open(component.licenseTextAsset).bufferedReader().use { it.readText() }
        composeRule.onNodeWithText(licenseText, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun setChrome(
        landscape: Boolean,
        selectorStyle: ModeSelectorStyle,
        phase: CameraUiPhase = CameraUiPhase.PREVIEWING,
        selectedMode: CaptureMode = CaptureMode.PHOTO,
        audioLevels: AudioLevelSnapshot? = null,
        zoomSupported: Boolean = false,
        zoomMinRatio: Float = 1f,
        zoomMaxRatio: Float = 1f,
        zoomRatio: Float = 1f,
        anchors: List<ZoomAnchor> = emptyList(),
        aeLockSupported: Boolean = false,
        aeLockActive: Boolean = false,
        afLockSupported: Boolean = false,
        afLockState: LockState = LockState.OFF,
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(
                if (landscape) DpSize(800.dp, 360.dp) else DpSize(360.dp, 800.dp),
            )) {
            val density = LocalDensity.current.density
            SideEffect { chromeDensity = density }
            // This layout fixture models ongoing PCM; stale receipts have dedicated meter tests.
            val liveAudioLevels = androidx.compose.runtime.produceState(audioLevels, audioLevels) {
                while (audioLevels != null) {
                    value = audioLevels.copy(capturedAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime())
                    kotlinx.coroutines.delay(50L)
                }
            }
            MaterialTheme {
                AdaptiveCaptureChrome(
                    state = CameraUiState(
                        phase = phase,
                        selectedMode = selectedMode,
                        audioLevels = liveAudioLevels.value,
                        audioClipLatched = audioLevels?.clipped == true,
                        audioMonitoringActive = audioLevels != null,
                        zoomSupported = zoomSupported,
                        zoomMinRatio = if (zoomSupported) 0.5f else 1f,
                        zoomMaxRatio = if (zoomSupported) 10f else 1f,
                        zoomRatio = zoomRatio,
                        opticalAnchors = anchors,
                        aeLockSupported = aeLockSupported,
                        aeLockActive = aeLockActive,
                        afLockSupported = afLockSupported,
                        afLockState = afLockState,
                    ),
                    binder = null,
                    settings = CameraSettings(modeSelectorStyle = selectorStyle),
                    landscape = landscape,
                    zebra = false,
                    peaking = false,
                    histogram = true,
                    histogramMode = HistogramMode.RGB,
                    showGrid = false,
                    gridMode = CompositionGridMode.THIRDS,
                    showHorizon = false,
                    onToggleZebra = {},
                    onTogglePeaking = {},
                    onToggleHistogram = {},
                    onCycleHistogramMode = {},
                    onToggleGrid = {},
                    onCycleGridMode = {},
                    onToggleHorizon = {},
                    onSettingsChanged = {},
                    onOpenMedia = {},
                    onOpenSettings = {},
                )
            }
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

    /** Mirrors CameraScreen's localized dial description so the test does not pin an English literal. */
    private fun modeDial(mode: CaptureMode): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(when (mode) {
            CaptureMode.PHOTO -> R.string.photo_mode
            CaptureMode.VIDEO -> R.string.video_mode
            else -> error("Add the label mapping for $mode")
        })
        return context.getString(R.string.mode_dial_description, label)
    }
}
