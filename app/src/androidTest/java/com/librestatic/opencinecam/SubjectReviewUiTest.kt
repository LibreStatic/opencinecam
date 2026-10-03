/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** OCC-PLAN-068 U4 subject-side review: nothing without the operator's cue, no controls, and a take start releases it. */
class SubjectReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val review = SubjectDisplaySettings(mode = SubjectDisplayMode.REVIEW, showStatus = false)

    private class Feed(pick: SubjectReviewPick?) : SubjectReviewFeed {
        override val picks: StateFlow<SubjectReviewPick?> = MutableStateFlow(pick)
        val failures = mutableListOf<String>()
        override fun playbackFailed(uri: String) { failures += uri }
    }

    @Test fun reviewWithoutAnOperatorPickShowsOnlyTheWaitingLineAtDoubleFont() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme { SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING), review) }
            }
        }
        compose.onNodeWithText(context.getString(R.string.subject_review_waiting)).assertIsDisplayed()
        compose.onNodeWithTag("subject-review-player").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun aPickWithoutTheMatchingCueIsNeverShown() {
        val feed = Feed(SubjectReviewPick("content://media/external/video/media/1", "A001.mp4", "video/mp4"))
        compose.setContent {
            CompositionLocalProvider(LocalSubjectReviewFeed provides feed) {
                MaterialTheme { SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING), review, cues = SubjectSessionCues()) }
            }
        }
        compose.onNodeWithTag("subject-review-player").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.subject_review_waiting)).assertIsDisplayed()
    }

    @Test fun aRecordingTakeReleasesThePlayerAtOnce() {
        val uri = "content://media/external/video/media/1"
        val feed = Feed(SubjectReviewPick(uri, "A001.mp4", "video/mp4"))
        compose.setContent {
            CompositionLocalProvider(LocalSubjectReviewFeed provides feed) {
                MaterialTheme { SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.RECORDING, selectedMode = CaptureMode.VIDEO), review,
                    cues = SubjectSessionCues(reviewUri = uri)) }
            }
        }
        compose.onNodeWithTag("subject-review-player").assertDoesNotExist()
    }

    @Test fun anUnreadablePickReportsAFailureWithoutControls() {
        val uri = "content://com.librestatic.opencinecam.missing/1"
        val feed = Feed(SubjectReviewPick(uri, "missing.jpg", "image/jpeg"))
        compose.setContent {
            CompositionLocalProvider(LocalSubjectReviewFeed provides feed) {
                MaterialTheme { SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.PREVIEWING), review, cues = SubjectSessionCues(reviewUri = uri)) }
            }
        }
        compose.waitUntil(10_000) { feed.failures.isNotEmpty() }
        assertEquals(uri, feed.failures.first())
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun operatorBarIsAbsentWithoutACoordinator() {
        compose.setContent { MaterialTheme { SubjectReviewOperatorBar(help = true) } }
        compose.onNodeWithTag("subject-review-operator-bar").assertDoesNotExist()
        // Settings help starts collapsed; open it before checking the how-to line.
        compose.onNodeWithContentDescription(context.getString(R.string.settings_help_show)).performClick()
        compose.onNodeWithText(context.getString(R.string.subject_review_help)).assertIsDisplayed()
    }
}
