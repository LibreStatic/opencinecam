/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SubjectInterviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Composable
    private fun DoubleFont(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) { MaterialTheme { content() } }
    }

    private fun interview(questions: List<String>, fontSp: Int = 32) = SubjectDisplaySettings(
        mode = SubjectDisplayMode.INTERVIEW, interviewQuestions = questions, prompterFontSp = fontSp, showStatus = false)

    @Test fun currentQuestionAndCounterAreShownAtDoubleFontScaleWithoutActions() {
        compose.setContent {
            DoubleFont { SubjectDisplayScreen(CameraUiState(phase = CameraUiPhase.RECORDING), interview(listOf("First?", "Second?", "Third?")),
                cues = SubjectSessionCues(interviewIndex = 1)) }
        }
        compose.onNodeWithTag("subject-interview-counter").assertTextEquals("2 / 3")
        compose.onNodeWithText("Second?").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun emptyListShowsTheEmptyState() {
        compose.setContent { DoubleFont { SubjectDisplayScreen(CameraUiState(), interview(emptyList())) } }
        compose.onNodeWithText(context.getString(R.string.subject_interview_empty)).assertIsDisplayed()
        compose.onNodeWithTag("subject-interview-counter").assertDoesNotExist()
    }

    @Test fun staleIndexShowsTheLastQuestion() {
        compose.setContent { MaterialTheme { SubjectDisplayScreen(CameraUiState(), interview(listOf("A?", "B?")), cues = SubjectSessionCues(interviewIndex = 9)) } }
        compose.onNodeWithTag("subject-interview-counter").assertTextEquals("2 / 2")
        compose.onNodeWithText("B?").assertIsDisplayed()
    }

    @Test fun longestQuestionFitsAtTheLargestFontAndDoubleScale() {
        val longest = "Would you describe the moment you decided to stay? ".repeat(6).take(SUBJECT_INTERVIEW_MAX_QUESTION_LENGTH).trim()
        compose.setContent { DoubleFont { SubjectDisplayScreen(CameraUiState(), interview(listOf(longest), fontSp = 72)) } }
        compose.onNodeWithTag("subject-interview-question").assertIsDisplayed()
        compose.onNodeWithTag("subject-interview-counter").assertIsDisplayed()
    }

    @Test fun operatorControlNeedsAPresentationCoordinator() {
        compose.setContent { MaterialTheme { InterviewOperatorControl(interview(listOf("A?"))) } }
        compose.onNodeWithTag("interview-operator-control").assertDoesNotExist()
    }

    @Test fun editorReportsCountAndRejectsTextBeyondTheBounds() {
        var saved = SubjectDisplaySettings()
        compose.setContent {
            var subject by remember { mutableStateOf(SubjectDisplaySettings()) }
            DoubleFont { SubjectInterviewSettings(CameraUiState(), subject) { subject = it; saved = it } }
        }
        compose.onNodeWithTag("subject-interview-editor").performTextInput("One\n\nTwo")
        compose.runOnIdle { assertEquals(listOf("One", "Two"), saved.interviewQuestions) }
        compose.onNodeWithTag("subject-interview-count", useUnmergedTree = true).assertTextEquals(context.getString(R.string.subject_interview_count, 2, 50))
        compose.onNodeWithTag("subject-interview-rejected", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("subject-interview-editor").performTextReplacement("x".repeat(320))
        compose.runOnIdle { assertEquals(listOf("x".repeat(300)), saved.interviewQuestions) }
        compose.onNodeWithTag("subject-interview-rejected", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("subject-interview-remaining", useUnmergedTree = true).assertTextEquals(context.getString(R.string.subject_interview_remaining, 0))
    }
}
