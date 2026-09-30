/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class OnboardingUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A first run with the camera already allowed, so leaving the wizard is never refused. */
    @get:Rule(order = 0)
    val firstRun = object : ExternalResource() {
        override fun before() {
            OnboardingStore(context).reset()
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private fun tap(text: Int) = rule.onNodeWithText(context.getString(text)).performClick()

    private fun awaitCamera() {
        // Leaving waits for the backdrop to scatter and the camera to open; with animations off both snap.
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("onboarding").fetchSemanticsNodes().isEmpty() }
        assertTrue(OnboardingStore(context).isCompleted())
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("operator-button-1").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun walkingEveryStepEndsOnTheCamera() {
        rule.onNodeWithTag("onboarding").assertIsDisplayed()
        tap(R.string.onb_get_started)
        tap(R.string.onb_next)
        tap(R.string.onb_next)
        rule.onNodeWithTag("perm-camera").assertIsDisplayed()
        // With every optional permission still open the continue action is the text button.
        tap(R.string.onb_continue)
        tap(R.string.onb_next)
        rule.onNodeWithText(context.getString(R.string.onb_ready_title)).assertIsDisplayed()
        rule.onNodeWithTag("onboarding-finish").performClick()
        awaitCamera()
    }

    @Test
    fun skipLeavesOnceTheCameraIsAllowed() {
        rule.onNodeWithTag("onboarding-skip").performClick()
        awaitCamera()
    }
}
