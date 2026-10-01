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
import com.librestatic.opencinecam.camera.FalseColorPalette
import com.librestatic.opencinecam.camera.MonitorAspectGuide
import com.librestatic.opencinecam.camera.MonitorColor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises settings intent, not sensor calibration or the sampling pipeline. */
class MonitoringSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings = mutableStateOf(CameraSettings(photoQuality = 73))

    @Test fun switchesAndExactValuesPreserveUnrelatedCaptureSettings() {
        show()
        listOf("waveform", "vectorscope", "false-color", "zebra-enabled", "zebra-shadow", "peaking-enabled")
            .forEach { click(it) }
        integer("peaking-threshold", "77")
        integer("opacity", "80")
        click("zebra-color-RED")
        click("peaking-color-YELLOW")
        click("luma-color-GREEN")
        click("palette-HIGH_CONTRAST")
        integer("refresh-hz", "10")
        click("aspect-CINEMA")
        click("safe-enabled")
        integer("safe-percent", "75")
        compose.runOnIdle {
            val actual = settings.value
            val options = actual.monitoring
            assertTrue(options.waveformEnabled)
            assertTrue(options.vectorscopeEnabled)
            assertTrue(options.falseColorEnabled)
            assertTrue(actual.zebraEnabled)
            assertTrue(options.zebraShadowEnabled)
            assertTrue(actual.peakingEnabled)
            assertEquals(77, options.peakingThreshold)
            assertEquals(80, options.opacityPercent)
            assertEquals(MonitorColor.RED, options.zebraColor)
            assertEquals(MonitorColor.YELLOW, options.peakingColor)
            assertEquals(MonitorColor.GREEN, options.lumaColor)
            assertEquals(FalseColorPalette.HIGH_CONTRAST, options.falseColorPalette)
            assertEquals(10, options.refreshHz)
            assertEquals(MonitorAspectGuide.CINEMA, options.aspectGuide)
            assertTrue(options.safeAreaEnabled)
            assertEquals(75, options.safeAreaPercent)
            assertEquals(73, actual.photoQuality)
        }
    }

    @Test fun invalidThresholdDraftsNeverPublishAndValidGroupsApplyTogether() {
        show()
        val original = settings.value
        edit("false-black", "90")
        node("false-apply").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(original, settings.value) }
        edit("false-black", "")
        node("false-apply").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(original, settings.value) }
        edit("false-black", "10")
        edit("false-shadow", "30")
        edit("false-highlight", "70")
        edit("false-clip", "96")
        compose.runOnIdle { assertEquals(original, settings.value) }
        click("false-apply")
        compose.runOnIdle {
            val options = settings.value.monitoring
            assertEquals(listOf(10, 30, 70, 96), listOf(options.falseColorBlackPercent,
                options.falseColorShadowPercent, options.falseColorHighlightPercent, options.falseColorClipPercent))
        }
        edit("zebra-low", "95")
        node("zebra-apply").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(original.monitoring.zebraLowPercent, settings.value.monitoring.zebraLowPercent) }
        edit("zebra-high", "98")
        click("zebra-apply")
        edit("refresh-hz", "11")
        node("refresh-hz-apply").performScrollTo().assertIsNotEnabled()
        edit("safe-percent", "49")
        node("safe-percent-apply").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            val options = settings.value.monitoring
            assertEquals(95, options.zebraLowPercent)
            assertEquals(98, options.zebraHighPercent)
            assertEquals(10, options.falseColorBlackPercent)
            assertEquals(original.monitoring.refreshHz, options.refreshHz)
            assertEquals(original.monitoring.safeAreaPercent, options.safeAreaPercent)
            assertEquals(73, settings.value.photoQuality)
        }
    }

    @Test fun narrowDoubleFontControlsReflowAndRemainReachableAtFortyEightDp() {
        show(fontScale = 2f)
        listOf("waveform", "zebra-shadow", "false-black", "false-apply", "refresh-hz", "safe-percent-apply")
            .forEach { node(it).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
        for (tag in listOf("palette-HIGH_CONTRAST", "aspect-NONE", "aspect-CINEMA")) {
            node(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("monitoring-$tag-label", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            assertFalse("Overflow at $tag: ${layouts.single().size}", layouts.single().hasVisualOverflow)
        }
        click("aspect-CINEMA")
        integer("safe-percent", "100")
        compose.runOnIdle {
            assertEquals(MonitorAspectGuide.CINEMA, settings.value.monitoring.aspectGuide)
            assertEquals(100, settings.value.monitoring.safeAreaPercent)
        }
    }

    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).height(420.dp).verticalScroll(rememberScrollState())) {
                        MonitoringSettings(settings.value) { settings.value = it }
                    }
                }
            }
        }
    }
    // Palette and aspect guide choices live in dialogs behind their value rows.
    private fun node(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        if (tag.startsWith("palette-") || tag.startsWith("aspect-")) compose.openChoice("monitoring-${tag.removeSuffix("-label")}") else compose.closeChoices()
        return compose.onNodeWithTag("monitoring-$tag")
    }
    private fun click(tag: String) { node(tag).performScrollTo().assertIsEnabled().performClick() }
    private fun edit(tag: String, value: String) { node(tag).performScrollTo().performTextReplacement(value) }
    private fun integer(tag: String, value: String) { edit(tag, value); click("$tag-apply") }
}
