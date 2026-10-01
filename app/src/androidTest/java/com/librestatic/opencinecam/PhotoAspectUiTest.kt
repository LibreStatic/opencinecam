/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.camera.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhotoAspectUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun narrowSettingsCustomRatioValidationNormalizationSwapAndDisable() {
        val settings = mutableStateOf(CameraSettings(photoQuality = 73))
        compose.setContent { MaterialTheme { Column(Modifier.width(280.dp).height(420.dp).verticalScroll(rememberScrollState())) {
            PhotoAspectSettings(CameraUiState(), settings.value) { settings.value = it }
        } } }
        compose.pickChoice("photo-aspect-16-9")
        compose.runOnIdle { assertEquals(PhotoAspectSelection(true, 16, 9), settings.value.photoAspect) }
        compose.onNodeWithTag("photo-aspect-width").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("photo-aspect-apply").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(PhotoAspectSelection(true, 16, 9), settings.value.photoAspect) }
        compose.onNodeWithTag("photo-aspect-width").performScrollTo().performTextReplacement("478")
        compose.onNodeWithTag("photo-aspect-height").performScrollTo().performTextReplacement("200")
        compose.onNodeWithTag("photo-aspect-apply").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PhotoAspectSelection(true, 239, 100), settings.value.photoAspect) }
        compose.onNodeWithTag("photo-aspect-swap").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PhotoAspectSelection(true, 100, 239), settings.value.photoAspect) }
        compose.onNodeWithTag("photo-aspect-enabled").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PhotoAspectSelection(false, 100, 239), settings.value.photoAspect); assertEquals(73, settings.value.photoQuality) }
    }
    @Test fun heicReencodingNoticeIsVisibleWithoutChangingFormatOrStoredIntent() {
        val settings = mutableStateOf(CameraSettings(photoFormat = StillPhotoFormat.HEIC, photoAspect = PhotoAspectSelection(true)))
        compose.setContent { MaterialTheme { Column(Modifier.width(280.dp).height(420.dp).verticalScroll(rememberScrollState())) {
            PhotoAspectSettings(CameraUiState(selectedMode = CaptureMode.PHOTO), settings.value) { settings.value = it }
        } } }
        compose.onNodeWithTag("photo-aspect-heic").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("photo-aspect-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("photo-aspect-heic").assertDoesNotExist()
        compose.runOnIdle { assertEquals(StillPhotoFormat.HEIC, settings.value.photoFormat); assertFalse(settings.value.photoAspect.enabled) }
    }
}
