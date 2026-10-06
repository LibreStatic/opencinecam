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

/** Permission/provider inputs are explicit; these tests never request or grant Android permissions. */
class GeotaggingSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val original = CameraSettings(audioInputDeviceId = 29,
        productionSlate = ProductionSlateSettings(project = "Preserve", location = ProductionSlateLocation.EXTERIOR))
    private val settings = mutableStateOf(original)
    private val precision = mutableStateOf<LocationPermissionPrecision?>(null)
    private val status = mutableStateOf(CaptureLocationStatus.NO_PERMISSION)
    private val denied = mutableStateOf(false)
    private val requesting = mutableStateOf(false)
    private var changes = 0
    private var requests = 0
    private var settingsOpens = 0

    @Test fun defaultOffEvenWithPregrantedPrecisePermissionAndAvailableLocation() {
        precision.value = LocationPermissionPrecision.PRECISE
        status.value = CaptureLocationStatus.AVAILABLE
        show()
        node("enabled").performScrollTo().assertIsOff()
        node("status").performScrollTo().assertTextEquals(text(R.string.geotagging_status_disabled))
        node("permission").performScrollTo().assertTextEquals(text(R.string.geotagging_permission_precise))
        node("retry").assertDoesNotExist(); node("app-settings").assertDoesNotExist()
        assertCounts(0, 0, 0)
        compose.runOnIdle { assertEquals(original, settings.value) }
    }

    @Test fun explicitOptInDoesNotRequestPermissionUntilSeparateAction() {
        show()
        node("enabled").performScrollTo().performClick().assertIsOn()
        assertCounts(1, 0, 0)
        node("status").performScrollTo().assertTextEquals(text(R.string.geotagging_status_no_permission))
        node("retry").performScrollTo().performClick().assertIsNotEnabled()
        assertCounts(1, 1, 0)
        compose.runOnIdle {
            assertEquals(original.copy(geotaggingEnabled = true), settings.value)
            precision.value = LocationPermissionPrecision.APPROXIMATE
            status.value = CaptureLocationStatus.AVAILABLE
            requesting.value = false
        }
        node("enabled").performScrollTo().assertIsOn()
        node("permission").performScrollTo().assertTextEquals(text(R.string.geotagging_permission_approximate))
        node("status").performScrollTo().assertTextEquals(text(R.string.geotagging_status_available))
        node("retry").assertDoesNotExist()
        assertCounts(1, 1, 0)
    }

    @Test fun denialRetryAndSystemSettingsAreExplicitAndNeverChangeConsent() {
        settings.value = original.copy(geotaggingEnabled = true)
        denied.value = true
        show()
        node("denied").performScrollTo().assertTextEquals(text(R.string.geotagging_denied))
        assertCounts(0, 0, 0)
        node("retry").performScrollTo().performClick().assertIsNotEnabled()
        node("app-settings").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { requesting.value = false }
        node("app-settings").performScrollTo().performClick()
        assertCounts(0, 1, 1)
        compose.runOnIdle { assertEquals(original.copy(geotaggingEnabled = true), settings.value) }
        node("enabled").performScrollTo().assertIsOn()
    }

    @Test fun optOutDuringPendingPermissionRemainsOffWhenGrantArrives() {
        settings.value = original.copy(geotaggingEnabled = true)
        show()
        node("retry").performScrollTo().performClick()
        node("enabled").performScrollTo().performClick().assertIsOff()
        compose.runOnIdle {
            precision.value = LocationPermissionPrecision.APPROXIMATE
            status.value = CaptureLocationStatus.AVAILABLE
            requesting.value = false
        }
        node("enabled").performScrollTo().assertIsOff()
        node("status").performScrollTo().assertTextEquals(text(R.string.geotagging_status_disabled))
        assertCounts(1, 1, 0)
        compose.runOnIdle { assertEquals(original, settings.value) }
    }

    @Test fun providerStatesAndAccessRevocationStayVisibleWithoutSettingsWrites() {
        settings.value = original.copy(geotaggingEnabled = true)
        precision.value = LocationPermissionPrecision.APPROXIMATE
        show()
        for ((value, label) in listOf(
            CaptureLocationStatus.UNAVAILABLE to R.string.geotagging_status_unavailable,
            CaptureLocationStatus.AVAILABLE to R.string.geotagging_status_available,
            CaptureLocationStatus.STALE to R.string.geotagging_status_stale,
            CaptureLocationStatus.INACTIVE to R.string.geotagging_status_inactive,
            CaptureLocationStatus.NO_PERMISSION to R.string.geotagging_status_no_permission,
        )) {
            compose.runOnIdle { status.value = value }
            node("status").performScrollTo().assertTextEquals(text(label))
        }
        compose.runOnIdle { precision.value = null }
        node("permission").performScrollTo().assertTextEquals(text(R.string.geotagging_permission_none))
        node("retry").performScrollTo().assertIsEnabled()
        node("enabled").performScrollTo().assertIsOn()
        assertCounts(0, 0, 0)
    }

    @Test fun doubleFontNarrowControlsAndLabelsRemainAccessibleAndOptOutIsImmediate() {
        settings.value = original.copy(geotaggingEnabled = true)
        denied.value = true
        show(doubleFont = true)
        for (tag in listOf("enabled", "retry", "app-settings")) {
            node(tag).performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        node("enabled").performScrollTo().assertContentDescriptionEquals(text(R.string.geotagging_enabled))
        node("help-toggle").performScrollTo().performClick()
        for (tag in listOf("help", "enabled-label", "permission", "status", "denied", "retry-label", "app-settings-label")) {
            val layouts = mutableListOf<TextLayoutResult>()
            node(tag).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            assertFalse("Overflow: $tag ${layouts.single().size}", layouts.single().hasVisualOverflow)
        }
        assertCounts(0, 0, 0)
        node("enabled").performScrollTo().performClick().assertIsOff()
        node("retry").assertDoesNotExist(); node("app-settings").assertDoesNotExist()
        node("status").performScrollTo().assertTextEquals(text(R.string.geotagging_status_disabled))
        assertCounts(1, 0, 0)
        compose.runOnIdle { assertEquals(original, settings.value) }
    }

    @Test fun grantingOrRevokingAccessWhileOffNeverImpliesConsent() {
        show()
        for (value in listOf(LocationPermissionPrecision.APPROXIMATE, LocationPermissionPrecision.PRECISE, null)) {
            compose.runOnIdle {
                precision.value = value
                status.value = if (value == null) CaptureLocationStatus.NO_PERMISSION else CaptureLocationStatus.AVAILABLE
            }
            node("enabled").performScrollTo().assertIsOff()
            node("status").performScrollTo().assertTextEquals(text(R.string.geotagging_status_disabled))
            node("retry").assertDoesNotExist()
        }
        assertCounts(0, 0, 0)
    }

    @Test fun actualSearchRouteFindsGeotaggingWithoutChangingConsentOrRequestingAccess() {
        compose.setContent { MaterialTheme {
            SettingsScreen(CameraUiState(), settings.value, false, {}, {}, onSettingsChange = {
                settings.value = it; changes++
            })
        } }
        compose.onNodeWithTag("settings-search").performTextInput("geotagging")
        node("enabled").performScrollTo().assertIsOff()
        node("help-toggle").performScrollTo().performClick()
        node("help").performScrollTo().assertTextEquals(text(R.string.geotagging_help))
        compose.runOnIdle { assertEquals(original, settings.value); assertEquals(0, changes) }
    }

    private fun show(doubleFont: Boolean = false) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(if (doubleFont) 2f else 1f)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).height(480.dp).verticalScroll(rememberScrollState())) {
                        GeotaggingSettingsContent(settings.value, status.value, precision.value, denied.value, requesting.value,
                            onSettingsChange = { settings.value = it; changes++ },
                            onOpenAppSettings = { settingsOpens++ },
                            onRequestPermission = { requests++; requesting.value = true })
                    }
                }
            }
        }
    }
    private fun assertCounts(writes: Int, permissions: Int, opens: Int) = compose.runOnIdle {
        assertEquals(writes, changes); assertEquals(permissions, requests); assertEquals(opens, settingsOpens)
    }
    private fun node(tag: String) = compose.onNodeWithTag("geotagging-$tag", useUnmergedTree = true)
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
