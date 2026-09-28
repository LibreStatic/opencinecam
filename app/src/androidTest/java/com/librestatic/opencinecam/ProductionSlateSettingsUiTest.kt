/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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

/** This verifies next-capture intent, not publication or automatic take completion. */
class ProductionSlateSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings = mutableStateOf(CameraSettings(photoQuality = 73, audioInputDeviceId = 51))
    private val state = mutableStateOf(CameraUiState())
    private var updates = 0

    @Test fun everySlateFieldAndEnumEditsIndependentlyWithoutChangingCaptureSettings() {
        show()
        for ((field, value) in listOf("project" to "Project ñ", "camera" to "Cam A", "scene" to "23B",
            "reel" to "R007", "lens" to "35 mm", "take" to "999999")) edit(field, value)
        for (location in ProductionSlateLocation.entries) {
            click("location-$location")
            compose.runOnIdle { assertEquals(location, settings.value.productionSlate.location) }
        }
        for (time in ProductionSlateTimeOfDay.entries) {
            click("time-$time")
            compose.runOnIdle { assertEquals(time, settings.value.productionSlate.timeOfDay) }
        }
        click("good"); click("increment")
        compose.runOnIdle {
            assertEquals(ProductionSlateSettings("Project ñ", "Cam A", "23B", "R007", "35 mm", 999999,
                ProductionSlateLocation.EXTERIOR, ProductionSlateTimeOfDay.NIGHT, true, true), settings.value.productionSlate)
            assertEquals(73, settings.value.photoQuality); assertEquals(51, settings.value.audioInputDeviceId)
        }
    }
    @Test fun invalidTextAndTakeDraftsRemainVisibleButNeverPublishTruncatedOrCoercedValues() {
        show()
        for (field in listOf("project", "camera", "scene", "reel", "lens")) {
            edit(field, "valid")
            for (invalid in listOf("x".repeat(129), "line\nbreak")) {
                edit(field, invalid)
                node("$field-invalid").performScrollTo().assertTextEquals(text(R.string.production_slate_invalid_text))
                compose.runOnIdle {
                    val slate = settings.value.productionSlate
                    assertEquals("valid", when (field) { "project" -> slate.project; "camera" -> slate.camera; "scene" -> slate.scene; "reel" -> slate.reel; else -> slate.lens })
                }
            }
            edit(field, "")
            node("$field-invalid").assertDoesNotExist()
        }
        edit("take", "42")
        for (invalid in listOf("", "0", "1000000", "01", "1.5", "-1")) {
            edit("take", invalid)
            node("take-invalid").performScrollTo().assertTextEquals(text(R.string.production_slate_invalid_take))
            compose.runOnIdle { assertEquals(42, settings.value.productionSlate.takeNumber) }
        }
        edit("take", "1")
        node("take-invalid").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, settings.value.productionSlate.takeNumber) }
    }
    @Test fun recEditsArePendingAndRecompositionOrRestoredAutoIncrementDoesNotAdvanceTake() {
        val initial = settings.value.copy(productionSlate = ProductionSlateSettings(scene = "A", takeNumber = 8, autoIncrementTake = true))
        settings.value = initial
        state.value = state.value.copy(phase = CameraUiPhase.RECORDING, effectiveSettings = initial)
        show()
        compose.runOnIdle { assertEquals(0, updates); assertEquals(8, settings.value.productionSlate.takeNumber) }
        node("pending").assertDoesNotExist()
        edit("scene", "B")
        node("pending").performScrollTo().assertTextEquals(text(R.string.production_slate_pending))
        click("good")
        var beforeRecompose = 0
        compose.runOnIdle {
            assertEquals(initial.productionSlate, state.value.effectiveSettings!!.productionSlate)
            assertEquals(8, settings.value.productionSlate.takeNumber)
            beforeRecompose = updates
            state.value = state.value.copy(message = "Recompose")
        }
        compose.runOnIdle { assertEquals(beforeRecompose, updates) }
        compose.runOnIdle { state.value = state.value.copy(phase = CameraUiPhase.PREVIEWING, effectiveSettings = settings.value) }
        node("pending").assertDoesNotExist()
        compose.runOnIdle { assertEquals(8, settings.value.productionSlate.takeNumber) }
    }
    @Test fun doubleFontFieldsAndButtonsHaveAccessibleTargetsAndUnclippedLabels() {
        show(2f)
        val fields = listOf("project", "camera", "scene", "reel", "lens", "take")
        val choices = ProductionSlateLocation.entries.map { "location-$it" } + ProductionSlateTimeOfDay.entries.map { "time-$it" }
        for (tag in fields + choices + listOf("good", "increment")) {
            node(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        node("help-toggle").performScrollTo().performClick()
        for (tag in listOf("help", "good-label", "increment-label") + fields.map { "$it-label" } + choices.map { "$it-label" }) {
            val results = mutableListOf<TextLayoutResult>()
            node(tag).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
            assertEquals(1, results.size)
            val layout = results.single()
            val diagnostic = "Overflow: $tag constraints=${layout.layoutInput.constraints}" +
                " fontSize=${layout.layoutInput.style.fontSize} lineHeight=${layout.layoutInput.style.lineHeight}" +
                " size=${layout.size} didOverflowWidth=${layout.didOverflowWidth} didOverflowHeight=${layout.didOverflowHeight}" +
                " lineCount=${layout.lineCount} line0bottom=${if (layout.lineCount > 0) layout.getLineBottom(0) else null}" +
                " line0right=${if (layout.lineCount > 0) layout.getLineRight(0) else null}" +
                " maxIntrinsicWidth=${layout.multiParagraph.intrinsics.maxIntrinsicWidth}" +
                " paragraphWidth=${layout.multiParagraph.width} paragraphHeight=${layout.multiParagraph.height}"
            assertFalse(diagnostic, layout.hasVisualOverflow)
        }
    }

    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).height(420.dp).verticalScroll(rememberScrollState())) {
                        ProductionSlateSettingsControls(state.value, settings.value, { updates++; settings.value = it })
                    }
                }
            }
        }
    }
    private fun node(tag: String) = compose.onNodeWithTag("slate-$tag", useUnmergedTree = true)
    private fun click(tag: String) { node(tag).performScrollTo().assertIsEnabled().performClick() }
    private fun edit(tag: String, value: String) { node(tag).performScrollTo().performTextReplacement(value) }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
