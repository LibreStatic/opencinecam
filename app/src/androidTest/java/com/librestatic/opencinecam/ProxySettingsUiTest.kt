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

class ProxySettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val original = CameraSettings(audioInputDeviceId = 29, geotaggingEnabled = true,
        productionSlate = ProductionSlateSettings(project = "Keep"))
    private val settings = mutableStateOf(original)
    private var changes = 0

    @Test fun openingAndSelectingCurrentDefaultsDoNotWriteOrStartWork() {
        show()
        node("edge-1280").performScrollTo().assertIsSelected().performClick()
        node("bitrate-3").performScrollTo().assertIsSelected().performClick()
        node("help-toggle").performScrollTo().performClick()
        node("help").performScrollTo().assertTextEquals(text(R.string.proxy_settings_help))
        node("audio").performScrollTo().assertTextEquals(text(R.string.proxy_settings_audio))
        compose.runOnIdle { assertEquals(0, changes); assertEquals(original, settings.value) }
    }

    @Test fun allFifteenChoicesUpdateOnlyProxyAndKeepOneSelectionPerGroup() {
        show()
        for (edge in listOf(640, 1280, 1920)) {
            node("edge-$edge").performScrollTo().performClick(); node("edge-$edge").assertIsSelected()
            for (other in listOf(640, 1280, 1920).filter { it != edge }) node("edge-$other").assertIsNotSelected()
            for (bitrate in listOf(1, 2, 3, 5, 8)) {
                node("bitrate-$bitrate").performScrollTo().performClick(); node("bitrate-$bitrate").assertIsSelected()
                for (other in listOf(1, 2, 3, 5, 8).filter { it != bitrate }) node("bitrate-$other").assertIsNotSelected()
                compose.runOnIdle { assertEquals(original.copy(proxy = ProxySettings(edge, bitrate)), settings.value) }
            }
        }
        compose.runOnIdle { assertEquals(18, changes) }
    }

    @Test fun callerUpdatesImmediatelyReflectInBothGroupsWithoutDraftOrExtraWrites() {
        show()
        compose.runOnIdle { settings.value = original.copy(proxy = ProxySettings(640, 8)) }
        node("edge-640").performScrollTo().assertIsSelected()
        node("edge-1280").assertIsNotSelected()
        node("bitrate-8").performScrollTo().assertIsSelected()
        node("bitrate-3").assertIsNotSelected()
        compose.runOnIdle { assertEquals(0, changes) }
        node("bitrate-1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(original.copy(proxy = ProxySettings(640, 1)), settings.value); assertEquals(1, changes) }
    }

    @Test fun doubleFontNarrowOptionsWrapAndProvideFortyEightDpTargets() {
        show(doubleFont = true)
        val options = listOf("edge-640", "edge-1280", "edge-1920", "bitrate-1", "bitrate-2", "bitrate-3", "bitrate-5", "bitrate-8")
        for (tag in options) node(tag).performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        node("help-toggle").performScrollTo().performClick()
        for (tag in listOf("help", "audio") + options.map { "$it-label" }) {
            val layouts = mutableListOf<TextLayoutResult>()
            node(tag).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            assertFalse("Overflow: $tag ${layouts.single().size}", layouts.single().hasVisualOverflow)
        }
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun searchableSettingsRouteEditsSameCameraSettingsProxyOnly() {
        compose.setContent { MaterialTheme {
            SettingsScreen(CameraUiState(), settings.value, false, {}, {}, onSettingsChange = { settings.value = it; changes++ })
        } }
        compose.onNodeWithTag("settings-search").performTextInput("proxy")
        node("edge-640").performScrollTo().performClick()
        node("bitrate-5").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(original.copy(proxy = ProxySettings(640, 5)), settings.value); assertEquals(2, changes) }
    }

    private fun show(doubleFont: Boolean = false) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(if (doubleFont) 2f else 1f)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).height(480.dp).verticalScroll(rememberScrollState())) {
                        ProxySettingsControls(settings.value.proxy) { settings.value = settings.value.copy(proxy = it); changes++ }
                    }
                }
            }
        }
    }
    private fun node(tag: String) = compose.settingsNode("proxy-settings-$tag", Regex("^(edge|bitrate)-\\d+(-label)?$").matches(tag))
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
