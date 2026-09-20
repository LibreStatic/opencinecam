/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.TorchCapabilities
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Software accessibility evidence; this does not qualify physical TalkBack or luminance. */
class TorchAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var inputModeManager: InputModeManager
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sliderExposesDistinctLabelCurrentLevelAndNativeProgressAction() {
        val settings = show()
        compose.onNodeWithTag("torch-toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNodeWithTag("torch-strength")
            .assertContentDescriptionEquals(context.getString(R.string.settings_torch_strength_label))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.settings_torch_level, 2, 3)))
            .assertRangeInfoEquals(ProgressBarRangeInfo(2f, 1f..3f, 1))
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(3f)) }
        compose.runOnIdle { assertEquals(3, settings.value.torchStrengthLevel) }
        compose.onNodeWithTag("torch-strength").assertRangeInfoEquals(ProgressBarRangeInfo(3f, 1f..3f, 1))
    }

    @Test fun discreteButtonsAnnounceActionsRespectBoundsAndPreserveDisabledTorchIntent() {
        val settings = show(initial = CameraSettings(flashEnabled = false, torchStrengthLevel = 2))
        compose.onNodeWithTag("torch-decrease")
            .assertContentDescriptionEquals(context.getString(R.string.settings_torch_decrease)).performClick()
        compose.onNodeWithTag("torch-decrease").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, settings.value.torchStrengthLevel); assertFalse(settings.value.flashEnabled) }
        compose.onNodeWithTag("torch-increase")
            .assertContentDescriptionEquals(context.getString(R.string.settings_torch_increase)).performClick().performClick()
        compose.onNodeWithTag("torch-increase").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(3, settings.value.torchStrengthLevel); assertFalse(settings.value.flashEnabled) }
    }

    @Test fun keyboardActivatesDiscreteControlExactlyOnceAndTraversesToNextControl() {
        var changes = 0
        val settings = show(onChange = { changes++ })
        enterKeyboardMode()
        compose.onNodeWithTag("torch-decrease").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            .assertIsFocused().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        compose.runOnIdle { assertEquals(1, settings.value.torchStrengthLevel); assertEquals(1, changes) }
        // Start at the still-enabled slider and use native Tab focus traversal.
        compose.onNodeWithTag("torch-strength").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            .performKeyInput { keyDown(Key.Tab); keyUp(Key.Tab) }
        compose.onNodeWithTag("torch-increase").assertIsFocused()
            .performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        compose.runOnIdle { assertEquals(2, settings.value.torchStrengthLevel); assertEquals(2, changes) }
    }

    @Test fun keyboardArrowChangesSliderWithoutTogglingTorch() {
        val settings = show()
        enterKeyboardMode()
        compose.onNodeWithTag("torch-strength").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            .assertIsFocused().performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(3, settings.value.torchStrengthLevel); assertTrue(settings.value.flashEnabled) }
    }

    @Test fun doubleFontNarrowPanelKeepsLabelsReadableAndActionsReachable() {
        show(fontScale = 2f)
        for (tag in listOf("torch-toggle", "torch-strength", "torch-decrease", "torch-increase")) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val semantics = node.fetchSemanticsNode()
            val unclipped = node.getUnclippedBoundsInRoot()
            val clipped = node.getBoundsInRoot()
            assertTrue(
                "$tag: unclipped=$unclipped, clipped=$clipped, density=${semantics.layoutInfo.density}, size=${semantics.size}",
                unclipped.bottom - unclipped.top >= 47.5.dp,
            )
            node.assertHeightIsAtLeast(48.dp)
        }
        for (tag in listOf("torch-decrease", "torch-increase")) {
            compose.onNodeWithTag(tag).assertWidthIsAtLeast(48.dp)
        }
        for (tag in listOf("torch-level-label", "torch-reported")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            assertFalse(
                "$tag must wrap without clipping: size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
                    "lineCount=${layout.lineCount}, paragraphWidth=${layout.multiParagraph.width}, paragraphHeight=${layout.multiParagraph.height}, " +
                    "maxLines=${layout.layoutInput.maxLines}, exceededMaxLines=${layout.multiParagraph.didExceedMaxLines}, " +
                    "fontSize=${layout.layoutInput.style.fontSize}, lineHeight=${layout.layoutInput.style.lineHeight}, " +
                    "text=${layout.layoutInput.text}",
                layout.hasVisualOverflow,
            )
        }
    }

    @Test fun unavailableCameraAllowsKeyboardOffButNeverOffersStrengthControls() {
        val settings = show(state = CameraUiState(), initial = CameraSettings(flashEnabled = true, torchStrengthLevel = 2))
        enterKeyboardMode()
        compose.onNodeWithTag("torch-toggle").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            .performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        compose.onNodeWithTag("torch-toggle").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("torch-strength").assertDoesNotExist()
        compose.onNodeWithTag("torch-decrease").assertDoesNotExist()
        compose.onNodeWithTag("torch-increase").assertDoesNotExist()
        compose.runOnIdle { assertFalse(settings.value.flashEnabled); assertEquals(2, settings.value.torchStrengthLevel) }
    }

    private fun show(
        state: CameraUiState = state(),
        initial: CameraSettings = CameraSettings(flashEnabled = true, torchStrengthLevel = 2),
        fontScale: Float = 1f,
        onChange: () -> Unit = {},
    ): MutableState<CameraSettings> {
        val settings = mutableStateOf(initial)
        compose.setContent {
            val modeManager = LocalInputModeManager.current
            SideEffect { inputModeManager = modeManager }
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    // Constrain real dp, rather than ForcedSize which rescales the density when
                    // its requested viewport is larger than the device. Touch targets must be
                    // measured at the device density, not at a scaled-down virtual viewport.
                    Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().heightIn(max = 480.dp).padding(12.dp)) {
                        TorchSettings(state, settings.value) { settings.value = it; onChange() }
                    }
                }
            }
        }
        return settings
    }

    private fun enterKeyboardMode() {
        compose.runOnIdle {
            // Clickable/toggleable controls intentionally reject non-touch focus while the
            // Android window is in touch mode. A keyboard test must activate keyboard mode.
            assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard))
            assertEquals(InputMode.Keyboard, inputModeManager.inputMode)
        }
    }

    private fun state(): CameraUiState {
        val descriptor = Camera2CameraDescriptor(
            cameraId = "torch-accessibility-fixture", lensFacing = 0, focalLengthsMm = listOf(4f), previewSize = Size(640, 480),
            jpegSize = null, rawSize = null, analysisSize = null, sensorOrientation = 90, sensitivityRange = null,
            exposureTimeRangeNs = null, aeCompensationRange = null, aeCompensationStep = 0f, minimumFocusDistance = null,
            supportsRaw = false, flashAvailable = true, targetFpsRanges = emptyList(), availableFixedFps = listOf(30),
            videoProfiles = emptyList(), logProfiles = emptyList(), torchCapabilities = TorchCapabilities(true, 3, 2),
        )
        return CameraUiState(phase = CameraUiPhase.PREVIEWING, cameras = listOf(descriptor), selectedCameraId = descriptor.cameraId)
    }
}
