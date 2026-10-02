/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectInterviewTest {
    @Test fun blankLinesAndSurroundingWhitespaceAreDropped() {
        assertEquals(listOf("One", "Two words"), parseInterviewQuestions("\n  One \r\n\n\t \n  Two words\t\n\n"))
        assertTrue(parseInterviewQuestions(" \n\t\n").isEmpty())
    }

    @Test fun moreThanFiftyQuestionsKeepsTheFirstFifty() {
        val parsed = parseInterviewQuestions((1..51).joinToString("\n") { "Q$it" })
        assertEquals(SUBJECT_INTERVIEW_MAX_QUESTIONS, parsed.size)
        assertEquals("Q50", parsed.last())
    }

    @Test fun overlongQuestionsAreClippedAfterTrimming() {
        assertEquals(300, parseInterviewQuestions("   " + "a".repeat(301) + "   ").single().length)
        assertEquals("b".repeat(300), parseInterviewQuestions("b".repeat(300)).single())
    }

    @Test fun unicodeQuestionsSurviveAndAreValidSettings() {
        val questions = parseInterviewQuestions("¿Qué te trajo aquí? 🎬\nÇa va, l'équipe ?\n日本語の質問")
        assertEquals(listOf("¿Qué te trajo aquí? 🎬", "Ça va, l'équipe ?", "日本語の質問"), questions)
        SubjectDisplaySettings(interviewQuestions = questions)
    }

    @Test fun parsedQuestionsAlwaysSatisfyTheSettingsBounds() {
        val messy = (1..80).joinToString("\n") { if (it % 3 == 0) "   " else "x".repeat(it * 7) }
        SubjectDisplaySettings(interviewQuestions = parseInterviewQuestions(messy))
    }

    @Test fun draftLimitRejectsTextBeyondTheBounds() {
        assertEquals("a".repeat(300), limitInterviewDraft("a".repeat(305)))
        // Indentation does not count against the question; parsing trims it later.
        assertEquals("  " + "c".repeat(300), limitInterviewDraft("  " + "c".repeat(310)))
        val fifty = (1..50).joinToString("\n") { "Q$it" }
        assertEquals("$fifty\n", limitInterviewDraft("$fifty\n"))
        assertEquals(fifty, limitInterviewDraft("$fifty\nQ51\nQ52"))
        assertEquals("$fifty\n", limitInterviewDraft("$fifty\n\nQ51"))
        assertEquals("One\n\nTwo", limitInterviewDraft("One\n\nTwo"))
        assertEquals("", limitInterviewDraft(""))
    }

    @Test fun charactersLeftFollowTheCursorLine() {
        val text = "Hello\n" + "x".repeat(300) + "\n  hi  "
        assertEquals(295, interviewCharactersLeft(text, 0))
        assertEquals(295, interviewCharactersLeft(text, 5))
        assertEquals(0, interviewCharactersLeft(text, 6))
        assertEquals(298, interviewCharactersLeft(text, text.length))
        assertEquals(300, interviewCharactersLeft("", 0))
        assertEquals(300, interviewCharactersLeft("One\n", 4))
    }

    @Test fun indexClampsToTheList() {
        assertEquals(0, clampInterviewIndex(5, 0))
        assertEquals(0, clampInterviewIndex(-3, 4))
        assertEquals(3, clampInterviewIndex(9, 4))
        assertEquals(2, clampInterviewIndex(2, 4))
    }

    @Test fun steppingStopsAtBothEnds() {
        assertEquals(1, stepInterviewIndex(0, 1, 3))
        assertEquals(2, stepInterviewIndex(2, 1, 3))
        assertEquals(0, stepInterviewIndex(0, -1, 3))
        assertEquals(0, stepInterviewIndex(0, 1, 0))
        assertEquals(0, stepInterviewIndex(0, -1, 0))
        assertEquals(2, stepInterviewIndex(0, Int.MAX_VALUE, 3))
    }

    @Test fun staleIndexFromALongerListStepsFromTheLastQuestion() {
        assertEquals(1, stepInterviewIndex(10, -1, 3))
        assertEquals(2, stepInterviewIndex(10, 1, 3))
    }

    @Test fun operatorControlOnlyDuringAnActiveInterviewPresentation() {
        val active = FoldDisplayState(phase = DisplaySessionPhase.ACTIVE, operation = DisplayOperation.PRESENT)
        assertTrue(interviewControlVisible(active, SubjectDisplayMode.INTERVIEW))
        assertFalse(interviewControlVisible(active, SubjectDisplayMode.TELEPROMPTER))
        assertFalse(interviewControlVisible(active.copy(phase = DisplaySessionPhase.STARTING), SubjectDisplayMode.INTERVIEW))
        assertFalse(interviewControlVisible(active.copy(phase = DisplaySessionPhase.IDLE, operation = null), SubjectDisplayMode.INTERVIEW))
        assertFalse(interviewControlVisible(active.copy(operation = DisplayOperation.TRANSFER), SubjectDisplayMode.INTERVIEW))
    }

    @Test fun fontFitPicksTheLargestFittingSize() {
        assertEquals(72, fitInterviewFontSp(72, 16) { true })
        assertEquals(16, fitInterviewFontSp(72, 16) { false })
        assertEquals(41, fitInterviewFontSp(72, 16) { it <= 41 })
        assertEquals(16, fitInterviewFontSp(16, 16) { false })
        var calls = 0
        fitInterviewFontSp(72, 16) { calls++; it <= 30 }
        assertTrue("binary search, not a linear scan: $calls", calls <= 8)
    }

    @Test fun ownWritesAreEchoesInAnyOrderButExternalValuesAreNot() {
        val echoes = InterviewEchoFilter()
        echoes.sent(listOf("Como"))
        echoes.sent(listOf("Como empezo"))
        echoes.sent(listOf("Como empezo?"))
        assertTrue(echoes.isEcho(listOf("Como empezo")))
        // The older write was forgotten together with the matched one; the newest is still pending.
        assertFalse(echoes.isEcho(listOf("Como")))
        assertTrue(echoes.isEcho(listOf("Como empezo?")))
        assertFalse(echoes.isEcho(listOf("Reset?")))
    }
}
