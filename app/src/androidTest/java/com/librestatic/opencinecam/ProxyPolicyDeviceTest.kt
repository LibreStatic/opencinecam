/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
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

/** Real local preferences and controls; no proxy engine or device-condition acceptance claim. */
class ProxyPolicyDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val policies get() = ProxyPolicies.get(context)
    private val mounted = mutableStateOf(true)
    private var encoderSettingChanges = 0

    @Test fun rapidControlChangesPreserveBothFieldsAndCommitActualPreferences() {
        val before = policies.states.value
        try {
            policies.update { ProxyPolicy() }
            show()
            assertPersisted(ProxyPolicy())
            // Obtain the real callbacks while each control is visible, then dispatch exactly
            // once each in one main turn. No recompose or persistence wait separates them.
            node("charging").performScrollTo()
            val charging = requireNotNull(node("charging").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            node("battery-50").performScrollTo()
            val battery = requireNotNull(node("battery-50").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            compose.runOnIdle { assertTrue(charging()); assertTrue(battery()) }
            val combined = ProxyPolicy(requireCharging = true, minimumBatteryPercent = 50)
            compose.waitUntil(10_000) { policies.states.value == combined }
            assertPersisted(combined)
            node("battery-50").performScrollTo().assertIsSelected()
            node("space-1024").performScrollTo().performClick()
            val all = combined.copy(reserveSpaceMiB = 1024)
            compose.waitUntil(10_000) { policies.states.value == all }
            assertPersisted(all)
            node("space-1024").assertIsSelected()
            compose.runOnIdle { assertEquals(0, encoderSettingChanges) }
        } finally { restore(before) }
    }

    @Test fun doubleFontNarrowPolicyControlsHaveReadableLabelsAndFortyEightDpTargets() {
        val before = policies.states.value
        val initial = ProxyPolicy(requireCharging = true, minimumBatteryPercent = 30, reserveSpaceMiB = 512)
        try {
            policies.update { initial }
            show(doubleFont = true)
            val tags = listOf("charging", "battery-0", "battery-10", "battery-20", "battery-30", "battery-50",
                "space-64", "space-256", "space-512", "space-1024", "space-2048")
            for (tag in tags) {
                node(tag).performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                val layouts = mutableListOf<TextLayoutResult>()
                node("$tag-label").performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    assertTrue(it(layouts))
                }
                assertEquals(1, layouts.size)
                assertFalse("Policy label clipped: $tag ${layouts.single().size}", layouts.single().hasVisualOverflow)
            }
            assertPersisted(initial)
            compose.runOnIdle { assertEquals(0, encoderSettingChanges) }
        } finally { restore(before) }
    }

    private fun assertPersisted(expected: ProxyPolicy) {
        assertEquals(expected, policies.states.value)
        val prefs = context.getSharedPreferences("proxy-policy", Context.MODE_PRIVATE)
        assertTrue(prefs.contains("charging")); assertTrue(prefs.contains("battery")); assertTrue(prefs.contains("reserve-mib"))
        assertEquals(expected.requireCharging, prefs.getBoolean("charging", !expected.requireCharging))
        assertEquals(expected.minimumBatteryPercent, prefs.getInt("battery", -1))
        assertEquals(expected.reserveSpaceMiB, prefs.getInt("reserve-mib", -1))
    }

    private fun restore(before: ProxyPolicy) {
        try {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        } finally {
            policies.update { before }
            assertPersisted(before)
        }
    }

    private fun show(doubleFont: Boolean = false) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(if (doubleFont) 2f else 1f)) {
                MaterialTheme {
                    if (mounted.value) Column(Modifier.width(280.dp).height(480.dp).verticalScroll(rememberScrollState())) {
                        ProxySettingsControls(ProxySettings()) { encoderSettingChanges++ }
                    }
                }
            }
        }
    }
    private fun node(tag: String) = compose.onNodeWithTag("proxy-settings-$tag", useUnmergedTree = true)
}
