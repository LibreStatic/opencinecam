/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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

class ImageProcessingUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun unknownCameraOnlyOffersTemplateDefaults() {
        content(CameraUiState(), CameraSettings())
        compose.onNodeWithTag("stabilization-DEFAULT").assertIsEnabled()
        compose.onNodeWithTag("stabilization-OPTICAL").assertIsNotEnabled()
        compose.onNodeWithTag("stabilization-VIDEO").assertIsNotEnabled()
        compose.onNodeWithTag("image-noise-HIGH_QUALITY").assertIsNotEnabled()
        compose.onNodeWithTag("image-edge-OFF").assertIsNotEnabled()
    }
    @Test fun independentNoiseChoiceDoesNotChangeEdgeOrStabilization() {
        var changed: CameraSettings? = null
        val original = CameraSettings(imageProcessing = ImageProcessingSelection(StabilizationMode.OPTICAL, IspMode.FAST, IspMode.OFF))
        content(state(), original) { changed = it }
        compose.onNodeWithTag("image-noise-HIGH_QUALITY").performScrollTo().performClick()
        assertEquals(IspMode.HIGH_QUALITY, changed?.imageProcessing?.noiseReduction)
        assertEquals(IspMode.OFF, changed?.imageProcessing?.edge)
        assertEquals(StabilizationMode.OPTICAL, changed?.imageProcessing?.stabilization)
    }
    @Test fun videoPreparationDisplaysTheDeferredChangeInsteadOfClaimingApplied() {
        val old = CameraSettings()
        val next = old.copy(imageProcessing = ImageProcessingSelection(StabilizationMode.VIDEO))
        content(state().copy(phase = CameraUiPhase.CAPTURING, selectedMode = CaptureMode.VIDEO, effectiveSettings = old), next)
        compose.onNodeWithText(context.getString(R.string.image_processing_deferred)).assertIsDisplayed()
    }
    @Test fun resetRemainsReachableAtTwoHundredPercentFont() {
        var changed: CameraSettings? = null
        content(state(), CameraSettings(imageProcessing = ImageProcessingSelection(edge = IspMode.OFF)), doubleFont = true) { changed = it }
        compose.onNodeWithTag("image-reset").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(ImageProcessingSelection(), changed?.imageProcessing)
    }
    @Test fun imageNoiseAndSharpnessSearchFindTheCanonicalSettings() {
        assertEquals(setOf("image-processing"), SettingsCatalog.search("nitidez imagen", null) { context.getString(it) })
    }
    private fun state(): CameraUiState {
        val descriptor = Camera2CameraDescriptor(cameraId = "test", lensFacing = 0, focalLengthsMm = listOf(4f), previewSize = Size(640, 480),
            jpegSize = null, rawSize = null, analysisSize = null, sensorOrientation = 90, sensitivityRange = null, exposureTimeRangeNs = null,
            aeCompensationRange = null, aeCompensationStep = 0f, minimumFocusDistance = null, supportsRaw = false, flashAvailable = false,
            targetFpsRanges = emptyList(), availableFixedFps = listOf(30), videoProfiles = emptyList(), logProfiles = emptyList(),
            imageProcessingCapabilities = ImageProcessingCapabilities(setOf(0, 1), setOf(0, 1), setOf(0, 1, 2), setOf(0, 1, 2)))
        return CameraUiState(phase = CameraUiPhase.PREVIEWING, cameras = listOf(descriptor), selectedCameraId = "test")
    }
    private fun content(state: CameraUiState, settings: CameraSettings, doubleFont: Boolean = false, onChange: (CameraSettings) -> Unit = {}) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(420.dp, 740.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(if (doubleFont) 2f else 1f)) {
                    MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        ImageProcessingSettings(state, settings, onChange)
                    } }
                }
            }
        }
    }
}
