/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One question per non-blank line, trimmed and clipped to the persisted bounds. */
internal fun parseInterviewQuestions(text: String): List<String> = text.lines().map(String::trim).filter(String::isNotEmpty)
    .map { it.take(SUBJECT_INTERVIEW_MAX_QUESTION_LENGTH) }.take(SUBJECT_INTERVIEW_MAX_QUESTIONS)

/**
 * OCC-PLAN-068 U5 placeholder. The current position arrives as [SubjectSessionCues.interviewIndex];
 * the subject never advances it.
 */
@Composable
internal fun SubjectInterviewContent(state: CameraUiState, settings: SubjectDisplaySettings, cues: SubjectSessionCues, modifier: Modifier = Modifier) {
    Box(modifier.testTag("subject-interview")) {
        Text(stringResource(if (settings.interviewQuestions.isEmpty()) R.string.subject_interview_empty else R.string.fold_mode_interview),
            color = Color.LightGray, fontSize = 16.sp)
    }
}

@Composable
internal fun SubjectInterviewSettings(state: CameraUiState, subject: SubjectDisplaySettings, onChange: (SubjectDisplaySettings) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(subject.interviewQuestions.joinToString("\n")) }
    // Keep blank lines while typing, but follow external changes such as a reset or another editor.
    LaunchedEffect(subject.interviewQuestions) {
        if (parseInterviewQuestions(draft) != subject.interviewQuestions) draft = subject.interviewQuestions.joinToString("\n")
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsHelp(stringResource(R.string.subject_interview_help))
        OutlinedTextField(draft, {
            draft = it.take(SUBJECT_INTERVIEW_MAX_QUESTIONS * (SUBJECT_INTERVIEW_MAX_QUESTION_LENGTH + 1))
            onChange(subject.copy(interviewQuestions = parseInterviewQuestions(draft)))
        }, label = { Text(stringResource(R.string.subject_interview_questions)) }, minLines = 3, maxLines = 8,
            modifier = Modifier.fillMaxWidth().testTag("subject-interview-editor"),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface))
    }
}
