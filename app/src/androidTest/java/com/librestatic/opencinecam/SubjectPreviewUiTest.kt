/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.librestatic.opencinecam.camera.SubjectPreviewStatus
import org.junit.Rule
import org.junit.Test

class SubjectPreviewUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun badgeExpiresWithoutAnotherFrameOrServiceUpdate() {
        val received = SystemClock.elapsedRealtime()
        compose.setContent { MaterialTheme { SubjectFrameBadge(SubjectPreviewStatus(received, received)) } }
        compose.waitUntil(3_000) {
            compose.onAllNodesWithText("Preview paused", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("subject-preview-age").assertIsDisplayed()
    }
    @Test fun previewRoleHasNoCaptureOrSettingsActionsEvenWhenTouchUnlocked() {
        compose.setContent {
            MaterialTheme { SubjectDisplayScreen(CameraUiState(), SubjectDisplaySettings(mode = SubjectDisplayMode.PREVIEW, touchLocked = false)) }
        }
        compose.onNodeWithTag("subject-camera-preview").assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onNodeWithText("Waiting for camera frames").assertIsDisplayed()
    }
}
