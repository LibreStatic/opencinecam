/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * MainActivity composes CameraRootScreen directly, so the app name is not on this screen; it
     * belongs to the About screen, which CaptureAdaptiveUiTest covers. What this smoke test owns is
     * that launching actually renders the branch the camera permission selects, rather than nothing.
     */
    @Test
    fun launchRendersTheBranchTheCameraPermissionSelects() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) composeRule.onNodeWithTag("operator-button-1").assertIsDisplayed()
        else composeRule.onNodeWithText(context.getString(R.string.camera_permission_title)).assertIsDisplayed()
    }
}
