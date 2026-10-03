/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librestatic.opencinecam.ui.viewfinder.chromePanel

/** Smallest question size; the teleprompter preference never goes below it either. */
private const val INTERVIEW_MIN_FONT_SP = 16

/** A hard cap on the editor text so blank lines or indentation cannot grow it without bound. */
private const val INTERVIEW_DRAFT_MAX_CHARS = 64_000

/** One question per non-blank line, trimmed and clipped to the persisted bounds. */
internal fun parseInterviewQuestions(text: String): List<String> = text.lines().map(String::trim).filter(String::isNotEmpty)
    .map { it.take(SUBJECT_INTERVIEW_MAX_QUESTION_LENGTH) }.take(SUBJECT_INTERVIEW_MAX_QUESTIONS)

/**
 * Questions this editor stored that can still come back through the settings flow. The store
 * echoes a frame late while the IME keeps committing, so an echo of an older keystroke is not an
 * external change and must not overwrite newer typing (Razr U8: fast input lost characters).
 */
internal class InterviewEchoFilter {
    private val pending = ArrayDeque<List<String>>()

    fun sent(questions: List<String>) {
        pending.addLast(questions)
        while (pending.size > 64) pending.removeFirst()
    }

    /** True when [incoming] is one of this editor's writes; it and every older write are forgotten. */
    fun isEcho(incoming: List<String>): Boolean {
        val index = pending.lastIndexOf(incoming)
        if (index < 0) return false
        repeat(index + 1) { pending.removeFirst() }
        return true
    }
}

/**
 * Applies the bounds while typing: each line keeps at most [SUBJECT_INTERVIEW_MAX_QUESTION_LENGTH]
 * characters after its indentation, and text after the last allowed question is rejected. Blank
 * lines between questions are kept so the editor does not fight the cursor.
 */
internal fun limitInterviewDraft(text: String): String {
    val kept = ArrayList<String>()
    var questions = 0
    for (line in text.take(INTERVIEW_DRAFT_MAX_CHARS).lines()) {
        if (line.isNotBlank()) {
            if (questions == SUBJECT_INTERVIEW_MAX_QUESTIONS) break
            questions++
        }
        val indent = line.length - line.trimStart().length
        kept += line.take(indent + SUBJECT_INTERVIEW_MAX_QUESTION_LENGTH)
    }
    return kept.joinToString("\n")
}

/** Characters still allowed on the line holding [cursor]; a blank line has the full allowance. */
internal fun interviewCharactersLeft(text: String, cursor: Int): Int {
    val start = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(-1)) + 1
    val end = text.indexOf('\n', cursor.coerceIn(0, text.length)).let { if (it < 0) text.length else it }
    return (SUBJECT_INTERVIEW_MAX_QUESTION_LENGTH - text.substring(start.coerceAtMost(end), end).trim().length).coerceAtLeast(0)
}

/** The current position for a list of [count] questions; an empty list stays at zero. */
internal fun clampInterviewIndex(index: Int, count: Int): Int = if (count <= 0) 0 else index.coerceIn(0, count - 1)

/** Moves by [step] questions without leaving the list, first clamping a stale index from a shorter list. */
internal fun stepInterviewIndex(index: Int, step: Int, count: Int): Int =
    clampInterviewIndex(clampInterviewIndex(index, count).toLong().plus(step).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), count)

/** The operator control appears only while a presentation session shows the interview on the cover. */
internal fun interviewControlVisible(fold: FoldDisplayState, mode: SubjectDisplayMode): Boolean =
    mode == SubjectDisplayMode.INTERVIEW && fold.phase == DisplaySessionPhase.ACTIVE && fold.operation == DisplayOperation.PRESENT

/** Largest size in [minSp]..[maxSp] for which [fits] holds, or [minSp] when nothing fits. */
internal fun fitInterviewFontSp(maxSp: Int, minSp: Int, fits: (Int) -> Boolean): Int {
    if (maxSp <= minSp || fits(maxSp)) return maxSp
    var low = minSp
    var high = maxSp - 1
    var best = minSp
    while (low <= high) {
        val middle = (low + high) / 2
        if (fits(middle)) { best = middle; low = middle + 1 } else high = middle - 1
    }
    return best
}

private fun questionStyle(sp: Int) = TextStyle(color = Color.White, fontSize = sp.sp, lineHeight = (sp * 1.25f).sp, fontWeight = FontWeight.Medium)

/**
 * OCC-PLAN-068 U5: the subject sees the current question in large type and its position. The
 * position arrives as [SubjectSessionCues.interviewIndex] from the operator; the subject never
 * advances it, and the content has no touch actions.
 */
@Composable
internal fun SubjectInterviewContent(state: CameraUiState, settings: SubjectDisplaySettings, cues: SubjectSessionCues, modifier: Modifier = Modifier) {
    val questions = settings.interviewQuestions
    Column(modifier.testTag("subject-interview"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (questions.isEmpty()) {
            Text(stringResource(R.string.subject_interview_empty), color = Color.LightGray, fontSize = 22.sp,
                modifier = Modifier.testTag("subject-interview-empty"))
            return@Column
        }
        val index = clampInterviewIndex(cues.interviewIndex, questions.size)
        Text(stringResource(R.string.subject_interview_counter, index + 1, questions.size), color = Color(0xFFFFCF66),
            fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("subject-interview-counter"))
        FittedQuestion(questions[index], settings.prompterFontSp, Modifier.weight(1f).fillMaxWidth())
    }
}

/** Starts at the teleprompter size and shrinks a long question until it fits the safe area. */
@Composable
private fun FittedQuestion(text: String, maxSp: Int, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val measurer = rememberTextMeasurer()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight
        val fontSp = remember(measurer, text, maxSp, width, height, bounded) {
            if (!bounded) maxSp else fitInterviewFontSp(maxSp, INTERVIEW_MIN_FONT_SP) { sp ->
                measurer.measure(text, questionStyle(sp), constraints = Constraints(maxWidth = width)).size.height <= height
            }
        }
        Text(text, style = questionStyle(fontSp), overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("subject-interview-question"))
    }
}

/**
 * The operator's compact previous/next control on the capture screen. It reaches the subject only
 * through the coordinator's session cues; it has no capture action, so it works during a take.
 */
@Composable
internal fun InterviewOperatorControl(subject: SubjectDisplaySettings, modifier: Modifier = Modifier) {
    val coordinator = LocalFoldDisplayCoordinator.current ?: return
    val fold by coordinator.states.collectAsStateWithLifecycle()
    val cues by coordinator.subjectCues.collectAsStateWithLifecycle()
    val count = subject.interviewQuestions.size
    // A shorter list (edited, reset or imported) pulls the position back inside it.
    LaunchedEffect(coordinator, count) { coordinator.updateSubjectCues { it.copy(interviewIndex = clampInterviewIndex(it.interviewIndex, count)) } }
    if (!interviewControlVisible(fold, subject.mode)) return
    val index = clampInterviewIndex(cues.interviewIndex, count)
    val step = { delta: Int -> coordinator.updateSubjectCues { it.copy(interviewIndex = stepInterviewIndex(it.interviewIndex, delta, count)) } }
    val position = if (count == 0) stringResource(R.string.subject_interview_operator_empty)
        else stringResource(R.string.subject_interview_position, index + 1, count)
    Row(
        modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = STACKED_TOP_BAR_HEIGHT_DP.dp + 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f).chromePanel(), RoundedCornerShape(16.dp))
            .padding(4.dp).testTag("interview-operator-control"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CineIconButton("interview-previous", CineIcon.CHEVRON_LEFT, R.string.subject_interview_previous, Modifier.size(56.dp),
            enabled = count > 0 && index > 0) { step(-1) }
        Column(Modifier.widthIn(max = 220.dp).padding(horizontal = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = position; liveRegion = LiveRegionMode.Polite }) {
            Text(if (count == 0) position else stringResource(R.string.subject_interview_counter, index + 1, count),
                color = Color(0xFFFFCF66), fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1,
                modifier = Modifier.testTag("interview-operator-counter"))
            if (count > 0) Text(subject.interviewQuestions[index], color = Color.White, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        CineIconButton("interview-next", CineIcon.CHEVRON_RIGHT, R.string.subject_interview_next, Modifier.size(56.dp),
            enabled = index < count - 1) { step(1) }
    }
}

@Composable
internal fun SubjectInterviewSettings(state: CameraUiState, subject: SubjectDisplaySettings, onChange: (SubjectDisplaySettings) -> Unit) {
    val coordinator = LocalFoldDisplayCoordinator.current
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(subject.interviewQuestions.joinToString("\n"))) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    val echoes = remember { InterviewEchoFilter() }
    // Keep blank lines while typing, but follow external changes such as a reset or another editor.
    LaunchedEffect(subject.interviewQuestions) {
        if (echoes.isEcho(subject.interviewQuestions)) return@LaunchedEffect
        if (parseInterviewQuestions(draft.text) != subject.interviewQuestions) {
            val text = subject.interviewQuestions.joinToString("\n")
            draft = TextFieldValue(text, TextRange(text.length))
        }
    }
    val count = parseInterviewQuestions(draft.text).size
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(draft, { edited ->
            val limited = limitInterviewDraft(edited.text)
            rejected = limited != edited.text
            draft = if (limited == edited.text) edited else TextFieldValue(limited, TextRange(minOf(edited.selection.end, limited.length)))
            val questions = parseInterviewQuestions(limited)
            if (questions != subject.interviewQuestions) {
                echoes.sent(questions)
                onChange(subject.copy(interviewQuestions = questions))
                coordinator?.updateSubjectCues { it.copy(interviewIndex = clampInterviewIndex(it.interviewIndex, questions.size)) }
            }
        }, label = { Text(stringResource(R.string.subject_interview_questions)) }, minLines = 3, maxLines = 8,
            modifier = Modifier.fillMaxWidth().testTag("subject-interview-editor"),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface),
            supportingText = {
                Column {
                    Text(stringResource(R.string.subject_interview_count, count, SUBJECT_INTERVIEW_MAX_QUESTIONS),
                        modifier = Modifier.testTag("subject-interview-count"))
                    Text(stringResource(R.string.subject_interview_remaining, interviewCharactersLeft(draft.text, draft.selection.end)),
                        modifier = Modifier.testTag("subject-interview-remaining"))
                    if (rejected) Text(stringResource(R.string.subject_interview_rejected), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("subject-interview-rejected"))
                }
            })
    }
}
