/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
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

    /** The header counter ("5 / 6 · ...") names the settled page; tapping again before the pager and its shared-axis buttons settle races them. */
    private fun awaitPage(number: Int) {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("$number / 6", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    /**
     * A tap that lands while the shared-axis button swap is still running is dropped (the page header already
     * shows the new page by then), so retry after a bounded wait instead of assuming the first tap is delivered.
     */
    private fun tapUntilPage(text: Int, number: Int) {
        repeat(3) {
            tap(text)
            val reached = runCatching {
                rule.waitUntil(3_000) { rule.onAllNodesWithText("$number / 6", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }.isSuccess
            if (reached) { rule.waitForIdle(); return }
        }
        awaitPage(number)
    }

    private fun awaitCamera() {
        // Leaving waits for the backdrop to scatter and the camera to open; with animations off both snap.
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("onboarding").fetchSemanticsNodes().isEmpty() }
        assertTrue(OnboardingStore(context).isCompleted())
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("operator-button-1").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun walkingEveryStepEndsOnTheCamera() {
        rule.onNodeWithTag("onboarding").assertIsDisplayed()
        tap(R.string.onb_get_started); awaitPage(2)
        tap(R.string.onb_next); awaitPage(3)
        tap(R.string.onb_next); awaitPage(4)
        rule.onNodeWithTag("perm-camera").assertIsDisplayed()
        // With every optional permission still open the continue action is the text button.
        tap(R.string.onb_continue); awaitPage(5)
        tapUntilPage(R.string.onb_next, 6)
        rule.onNodeWithText(context.getString(R.string.onb_ready_title)).assertIsDisplayed()
        rule.onNodeWithTag("onboarding-finish").performClick()
        awaitCamera()
    }

    @Test
    fun skipLeavesOnceTheCameraIsAllowed() {
        rule.onNodeWithTag("onboarding-skip").performClick()
        awaitCamera()
    }

    /** Finishes the first run, then starts the tour again from Settings > About. */
    private fun replayTourFromAbout() {
        rule.onNodeWithTag("onboarding-skip").performClick()
        awaitCamera()
        rule.onNodeWithContentDescription(context.getString(R.string.settings_tab)).performClick()
        rule.onNodeWithTag("settings-category-DIAGNOSTICS").performScrollTo().performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText(context.getString(R.string.about_title)))
        rule.onNodeWithContentDescription(context.getString(R.string.about_settings_summary)).performClick()
        rule.onNodeWithTag("about-list").performScrollToNode(hasTestTag("about-replay-tour"))
        rule.onNodeWithTag("about-replay-tour").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("onboarding-skip").fetchSemanticsNodes().isNotEmpty() }
        awaitPage(1)
    }

    /** Replayed from Settings > About, the tour ends like the first run: on the camera, not back in Settings. */
    @Test
    fun skippingAReplayedTourReturnsToTheCamera() {
        replayTourFromAbout()
        rule.onNodeWithTag("onboarding-skip").performClick()
        awaitCamera()
        assertTrue(rule.onAllNodesWithTag("settings-categories").fetchSemanticsNodes().isEmpty())
    }

    /** On a replayed tour's first page there is an app to return to, so Back closes the tour instead of leaving the app. */
    @Test
    fun backOnTheFirstPageOfAReplayedTourClosesIt() {
        replayTourFromAbout()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        awaitCamera()
        assertFalse(rule.activity.isFinishing)
    }
}
