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

/** Synthetic descriptors test UI choices only, never actual RAW/HEIC encoding acceptance. */
class PhotoFormatUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun advertisedFormatsAndAccessibleQualityControlsUpdateOnlyTheirPreference() {
        val settings=show(raw=true,heic=true)
        compose.onNodeWithTag("photo-format-RAW_JPEG").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(StillPhotoFormat.RAW_JPEG,settings.value.photoFormat) }
        compose.onNodeWithTag("photo-format-HEIC").performScrollTo().performClick()
        compose.onNodeWithTag("photo-quality").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(100f)) }
        compose.onNodeWithTag("photo-quality-increase").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("photo-quality-decrease").performClick()
        compose.runOnIdle {
            assertEquals(StillPhotoFormat.HEIC,settings.value.photoFormat);assertEquals(99,settings.value.photoQuality)
            assertEquals(PhotoFlashSelection(PhotoFlashMode.AUTO),settings.value.photoFlash);assertTrue(settings.value.flashEnabled)
        }
    }
    @Test fun unsupportedPersistedFormatRemainsVisibleUntilUserChoosesJpeg() {
        val settings=show(raw=false,heic=false,format=StillPhotoFormat.HEIC)
        compose.onNodeWithTag("photo-format-HEIC").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithTag("photo-format-RAW_JPEG").assertIsNotEnabled()
        compose.onNodeWithTag("photo-format-unavailable").assertExists()
        compose.runOnIdle { assertEquals(StillPhotoFormat.HEIC,settings.value.photoFormat) }
        compose.onNodeWithTag("photo-format-JPEG").performScrollTo().performClick()
        compose.onNodeWithTag("photo-format-unavailable").assertDoesNotExist()
    }
    private fun show(raw:Boolean,heic:Boolean,format:StillPhotoFormat=StillPhotoFormat.JPEG): androidx.compose.runtime.MutableState<CameraSettings> {
        val d=Camera2CameraDescriptor("format-ui",0,listOf(4f),Size(640,480),Size(640,480),if(raw) Size(640,480) else null,null,90,null,null,null,0f,null,raw,true,
            emptyList(),listOf(30),emptyList(),emptyList(),heicSize=if(heic) Size(640,480) else null)
        val state=CameraUiState(cameras=listOf(d),selectedCameraId=d.cameraId,selectedMode=CaptureMode.PHOTO)
        val settings=mutableStateOf(CameraSettings(photoFormat=format,photoFlash=PhotoFlashSelection(PhotoFlashMode.AUTO),flashEnabled=true))
        compose.setContent { MaterialTheme { Column(Modifier.widthIn(max=280.dp).fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
            PhotoFormatSettings(state,settings.value) { settings.value=it }
        } } }
        return settings
    }
}
