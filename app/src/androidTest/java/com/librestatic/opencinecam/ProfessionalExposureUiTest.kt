/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfessionalExposureUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun unknownCameraNeverEnablesNativePriorityOrManualExposure() {
        content(CameraSettings())
        for (mode in listOf(ExposureMode.MANUAL, ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY)) {
            compose.onNodeWithText(context.getString(mode.titleResource())).assertIsNotEnabled()
        }
        compose.onNodeWithText(context.getString(R.string.pro_mode_auto)).assertIsEnabled().assertIsDisplayed()
    }
    @Test fun doubleFontKeepsAccessibleAutomaticControlAndUnavailableExplanation() {
        content(CameraSettings(exposure = ExposureSelection(ExposureMode.ISO_PRIORITY)), doubleFont = true)
        compose.onNodeWithText(context.getString(R.string.pro_mode_auto)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(context.getString(R.string.pro_exposure_unavailable)).performScrollTo().assertIsDisplayed()
    }
    @Test fun returnToAutoUpdatesCanonicalIntentWithoutDiscardingStoredManualValues() {
        var updated: CameraSettings? = null
        val original = CameraSettings(exposure = ExposureSelection(ExposureMode.MANUAL, 800), whiteBalance = WhiteBalanceSelection.Kelvin(4300, 17))
        content(original, onChange = { updated = it })
        compose.onNodeWithText(context.getString(R.string.pro_restore_auto)).performScrollTo().performClick()
        assertEquals(ExposureMode.AUTO, updated?.exposure?.mode)
        assertEquals(800, updated?.exposure?.iso)
        assertEquals(WhiteBalanceSelection.Auto, updated?.whiteBalance)
    }
    @Test fun tintAndAngleSearchResolveTheCanonicalSettingsEntry() {
        assertEquals(setOf("professional-exposure"), SettingsCatalog.search("tinte angulo", null) { context.getString(it) })
    }
    private fun content(settings: CameraSettings, doubleFont: Boolean = false, onChange: (CameraSettings) -> Unit = {}) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(420.dp, 740.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(if (doubleFont) 2f else 1f)) {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            ProfessionalExposureSettings(CameraUiState(phase = CameraUiPhase.PREVIEWING), settings, onChange)
                        }
                    }
                }
            }
        }
    }
}
