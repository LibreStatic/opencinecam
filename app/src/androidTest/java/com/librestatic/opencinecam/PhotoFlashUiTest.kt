/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.Size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.librestatic.opencinecam.camera.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhotoFlashUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun changingFlashAndStrengthNeverChangesTorch() {
        val settings = show(PhotoFlashCapabilities(true,setOf(0,1,2,3),3,2), CameraSettings(flashEnabled=true,torchStrengthLevel=2))
        compose.onNodeWithTag("photo-flash-ON").performScrollTo().performClick()
        compose.onNodeWithTag("photo-flash-strength").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(3f)) }
        compose.runOnIdle { assertEquals(PhotoFlashSelection(PhotoFlashMode.ON,3),settings.value.photoFlash); assertTrue(settings.value.flashEnabled); assertEquals(2,settings.value.torchStrengthLevel) }
        compose.onNodeWithTag("photo-flash-increase").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("photo-flash-AUTO").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PhotoFlashSelection(PhotoFlashMode.AUTO),settings.value.photoFlash) }
        compose.onNodeWithTag("photo-flash-strength").assertDoesNotExist()
    }
    @Test fun unavailableLensStillAllowsOffWithoutOfferingUnsupportedModes() {
        val settings = show(PhotoFlashCapabilities(),CameraSettings(photoFlash=PhotoFlashSelection(PhotoFlashMode.ON,2)))
        compose.onNodeWithTag("photo-flash-AUTO").assertIsNotEnabled()
        compose.onNodeWithTag("photo-flash-ON").assertIsNotEnabled()
        compose.onNodeWithTag("photo-flash-rejected").assertExists()
        compose.onNodeWithTag("photo-flash-OFF").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PhotoFlashSelection(),settings.value.photoFlash) }
        compose.onNodeWithTag("photo-flash-rejected").assertDoesNotExist()
    }
    @Test fun fixedStrengthDoesNotExposeSliderAndUnknownResultIsNotFired() {
        show(PhotoFlashCapabilities(true,setOf(0,1,2,3)),CameraSettings(photoFlash=PhotoFlashSelection(PhotoFlashMode.ON)))
        compose.onNodeWithTag("photo-flash-strength").assertDoesNotExist()
        compose.onNodeWithTag("photo-flash-report").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("photo-flash-ON").assertIsSelected()
    }
    private fun show(caps: PhotoFlashCapabilities,initial:CameraSettings): androidx.compose.runtime.MutableState<CameraSettings> {
        val d = Camera2CameraDescriptor("photo-ui",0,listOf(4f),Size(640,480),null,null,null,90,null,null,null,0f,null,false,caps.available,
            emptyList(),listOf(30),emptyList(),emptyList(),photoFlashCapabilities=caps)
        val state = CameraUiState(cameras=listOf(d),selectedCameraId=d.cameraId,selectedMode=CaptureMode.PHOTO)
        val settings=mutableStateOf(initial)
        compose.setContent { MaterialTheme { Column(Modifier.widthIn(max=280.dp).fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
            PhotoFlashSettings(state,settings.value) {settings.value=it}
        } } }
        return settings
    }
}
