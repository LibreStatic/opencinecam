/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CaptureNamingSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val original = CameraSettings(productionSlate = ProductionSlateSettings(project = "Film Name", camera = "Cam A",
        scene = "Scene", reel = "R1", takeNumber = 7), audioInputDeviceId = 21)
    private val settings = mutableStateOf(original)
    private var writes = 0

    @Test fun editingAndEnablingRemainDraftUntilExplicitSavePreservingOtherSettings() {
        show(); node("save").performScrollTo().assertIsNotEnabled()
        node("enabled").performScrollTo().performClick().assertIsOn()
        enter("{project}_{take}")
        compose.runOnIdle { assertEquals(0, writes); assertEquals(original, settings.value) }
        node("save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, writes)
            assertEquals(original.copy(captureNaming = CaptureNamingSettings(true, "{project}_{take}")), settings.value)
        }
        node("save").assertIsNotEnabled()
    }
    @Test fun invalidDraftAndOversizedEchoCannotSavePreviousValidTemplate() {
        show()
        for (invalid in listOf("{unknown}", "../path", "a".repeat(129), "")) {
            enter(invalid); node("invalid").performScrollTo().assertExists(); node("save").performScrollTo().assertIsNotEnabled()
        }
        enter("Valid"); node("save").performScrollTo().assertIsEnabled()
        enter("b".repeat(513)); node("save").performScrollTo().assertIsNotEnabled()
        node("template").performScrollTo().performImeAction()
        node("save").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, writes); assertEquals(original, settings.value) }
        enter("Recovered"); node("save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(CaptureNamingSettings(false, "Recovered"), settings.value.captureNaming) }
    }
    @Test fun purePreviewUsesCurrentSlateFixedUtcAndMandatoryFullExampleUuid() {
        show(); node("enabled").performScrollTo().performClick()
        enter("{project}_{camera}_{scene}_{take}_{reel}_{date}_{time}")
        val stem = "Film_Name_Cam_A_Scene_0007_R1_20260102_030405006_00000000-0000-4000-8000-000000000000"
        node("preview").performScrollTo().assertTextEquals(text(R.string.capture_naming_preview, stem))
        node("example").performScrollTo().assertTextEquals(text(R.string.capture_naming_example))
        compose.runOnIdle { assertEquals(0, writes) }
        node("enabled").performScrollTo().performClick()
        node("preview").performScrollTo().assertTextEquals(text(R.string.capture_naming_preview, "OCC_$CAPTURE_NAMING_EXAMPLE_UUID"))
    }
    @Test fun slateChangesUpdateExampleWithoutResettingUnsavedTemplate() {
        show(); node("enabled").performScrollTo().performClick(); enter("{scene}_{take}")
        compose.runOnIdle { settings.value = settings.value.copy(productionSlate = settings.value.productionSlate.copy(scene = "Next", takeNumber = 12)) }
        node("preview").performScrollTo().assertTextEquals(text(R.string.capture_naming_preview, "Next_0012_$CAPTURE_NAMING_EXAMPLE_UUID"))
        node("template").performScrollTo().assertTextContains("{scene}_{take}")
        compose.runOnIdle { assertEquals(0, writes); assertEquals(CaptureNamingSettings(), settings.value.captureNaming) }
    }
    @Test fun doubleFontLabelsAndControlsRemainReadableWithFortyEightDpTargets() {
        show(doubleFont = true)
        for (tag in listOf("enabled", "template", "save")) node(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
        for (tag in listOf("help", "enabled-label", "tokens", "template-label", "identity", "example", "preview", "save-label")) {
            val layouts = mutableListOf<TextLayoutResult>()
            node(tag).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            assertFalse("Overflow: $tag ${layouts.single().size}", layouts.single().hasVisualOverflow)
        }
        assertEquals(0, writes)
    }
    private fun show(doubleFont: Boolean = false) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(if (doubleFont) 2f else 1f)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).height(540.dp).verticalScroll(rememberScrollState())) {
                        CaptureNamingSettingsControls(settings.value) { settings.value = it; writes++ }
                    }
                }
            }
        }
    }
    private fun enter(value: String) { node("template").performScrollTo().performTextReplacement(value); node("template").performImeAction() }
    private fun node(tag: String) = compose.onNodeWithTag("capture-naming-$tag", useUnmergedTree = true)
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
}
