/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import org.junit.Assert.*
import org.junit.Test

class PreparedRecordingTakeTest {
    private class Fixture {
        val calls = mutableListOf<String>()
        var failAt: String? = null
        var emptyAt: String? = null
        var failRegister = emptySet<String>()
        val captureFailure = IllegalStateException("capture")
        val registrationFailure = IllegalStateException("registration")
        fun action(name: String): String? {
            calls += name
            if (name == failAt) throw captureFailure
            return if (name == emptyAt) null else name
        }
        fun register(name: String) { calls += name; if (name in failRegister) throw registrationFailure }
        fun run(success: Boolean = true, audio: Boolean = true) = finalizePreparedRecordingTake(
            success = success,
            prepareAudio = if (audio) ({ action("prepareAudio") }) else null,
            prepareVideo = { action("prepareVideo") },
            publishAudio = { prepared: String -> assertEquals("prepareAudio", prepared); action("publishAudio") },
            publishVideo = { prepared: String -> assertEquals("prepareVideo", prepared); action("publishVideo") },
            discardAudio = { action("discardAudio"); Unit },
            discardVideo = { action("discardVideo"); Unit },
            onPrepared = { v, a -> assertEquals("prepareVideo", v); assertEquals(if (audio) "prepareAudio" else null, a); register("prepared") },
            onPublished = { _, _ -> register("published") },
            onAborted = { register("aborted") },
        )
    }
    @Test fun allArtifactsArePreparedBeforeAnyPublicationAndAudioTimingComesFirst() {
        val f = Fixture(); val result = f.run()
        assertEquals("publishVideo" to "publishAudio", result.outputs)
        assertTrue(result.registrationFailures.isEmpty())
        assertEquals(listOf("prepareAudio", "prepareVideo", "prepared", "publishAudio", "publishVideo", "published"), f.calls)
    }
    @Test fun videoOnlyUsesTheSamePublicationBoundary() {
        val f = Fixture(); assertEquals("publishVideo" to null, f.run(audio = false).outputs)
        assertEquals(listOf("prepareVideo", "prepared", "publishVideo", "published"), f.calls)
    }
    @Test fun optionalPreparedRegistrationFailureStillSavesBothOutputs() {
        val f = Fixture(); f.failRegister = setOf("prepared")
        val result = f.run(); assertNotNull(result.outputs)
        assertEquals(listOf(f.registrationFailure), result.registrationFailures)
        assertEquals("published", f.calls.last()); assertFalse(f.calls.any { it.startsWith("discard") })
    }
    @Test fun optionalCommittedRegistrationFailureNeverCompensatesPublishedOutputs() {
        val f = Fixture(); f.failRegister = setOf("published")
        val result = f.run(); assertNotNull(result.outputs)
        assertEquals(listOf(f.registrationFailure), result.registrationFailures)
        assertFalse(f.calls.any { it.startsWith("discard") })
    }
    @Test fun bothRegistrationFailuresRemainVisibleWithoutChangingSavedOutcome() {
        val f = Fixture(); f.failRegister = setOf("prepared", "published")
        val result = f.run(); assertNotNull(result.outputs); assertEquals(2, result.registrationFailures.size)
        assertFalse(f.calls.any { it.startsWith("discard") })
    }
    @Test fun preparationAndPublicationFailuresCompensateAndNeverCommitRegistration() {
        for (stage in listOf("prepareAudio", "prepareVideo", "publishAudio", "publishVideo")) {
            val f = Fixture(); f.failAt = stage
            assertSame(f.captureFailure, assertThrows(IllegalStateException::class.java) { f.run() })
            assertEquals(listOf("discardAudio", "discardVideo", "aborted"), f.calls.takeLast(3))
            assertFalse("published" in f.calls)
            if (stage.startsWith("prepare")) assertFalse(f.calls.any { it.startsWith("publish") })
        }
    }
    @Test fun missingRequestedOutputsAreNotSuccessfulPublication() {
        for (stage in listOf("prepareAudio", "prepareVideo", "publishAudio", "publishVideo")) {
            val f = Fixture(); f.emptyAt = stage
            assertThrows(IllegalArgumentException::class.java.takeIf { stage.endsWith("Video") } ?: IllegalStateException::class.java) { f.run() }
            assertEquals(listOf("discardAudio", "discardVideo", "aborted"), f.calls.takeLast(3))
            assertFalse("published" in f.calls)
        }
    }
    @Test fun unsuccessfulTakeAbortsWithoutPreparingOrPublishing() {
        val f = Fixture(); assertNull(f.run(success = false).outputs)
        assertEquals(listOf("discardAudio", "discardVideo", "aborted"), f.calls)
    }
    @Test fun abortRegistrationFailureDoesNotReplaceCaptureFailure() {
        val f = Fixture(); f.failAt = "prepareVideo"; f.failRegister = setOf("aborted")
        assertSame(f.captureFailure, assertThrows(IllegalStateException::class.java) { f.run() })
        assertEquals("aborted", f.calls.last())
    }
    @Test fun abortRegistrationFailureIsReportedForAnUnsuccessfulTake() {
        val f = Fixture(); f.failRegister = setOf("aborted")
        val result = f.run(success = false); assertNull(result.outputs)
        assertEquals(listOf(f.registrationFailure), result.registrationFailures)
    }
    @Test fun failedFirstCompensationDoesNotSkipTheSecondOrAbortNotification() {
        val f = Fixture(); f.failAt = "discardAudio"
        assertSame(f.captureFailure, assertThrows(IllegalStateException::class.java) { f.run(success = false) })
        assertEquals(listOf("discardAudio", "discardVideo", "aborted"), f.calls)
    }

    // ADR-0011: a take that is not fully successful keeps outputs with bytes, as incomplete.
    private class SalvageFixture(val reasons: List<String> = listOf("audio-start-failed")) {
        val calls = mutableListOf<String>()
        var audioBytes = true
        var videoBytes = true
        var failAt: String? = null
        val failure = IllegalStateException("salvage")
        val seenReasons = mutableListOf<List<String>>()
        private fun step(name: String): String? {
            calls += name
            if (name == failAt) throw failure
            return name
        }
        fun salvage(audio: Boolean = true) = IncompleteTakeSalvage(
            reasons = reasons,
            prepareAudio = if (audio) ({ r: List<String> -> seenReasons += r; calls += "salvageAudio"; if (audioBytes) step("preparedAudio") else null }) else null,
            prepareVideo = { r: List<String> -> seenReasons += r; calls += "salvageVideo"; if (videoBytes) step("preparedVideo") else null },
        )
        fun run(success: Boolean, audio: Boolean = true, failPublish: String? = null) = finalizePreparedRecordingTake(
            success = success,
            prepareAudio = if (audio) ({ "preparedAudio" }) else null,
            prepareVideo = { "preparedVideo" },
            publishAudio = { prepared: String -> calls += "publish:$prepared"; if (failPublish == "audio") throw failure; "audio" },
            publishVideo = { prepared: String -> calls += "publish:$prepared"; if (failPublish == "video") throw failure; "video" },
            discardAudio = { calls += "discardAudio" },
            discardVideo = { calls += "discardVideo" },
            onPrepared = { _, _ -> calls += "prepared" },
            onPublished = { _, _ -> calls += "published" },
            onAborted = { calls += "aborted" },
            salvage = salvage(audio),
        )
    }

    @Test fun unsuccessfulTakeWithBytesPublishesBothOutputsAsIncompleteAndNeverDiscards() {
        val f = SalvageFixture(); val result = f.run(success = false)
        assertEquals("video" to "audio", result.outputs)
        assertTrue(result.incomplete)
        assertEquals(listOf("audio-start-failed"), result.incompleteReasons)
        assertEquals(listOf("salvageAudio", "preparedAudio", "salvageVideo", "preparedVideo", "publish:preparedAudio", "publish:preparedVideo", "aborted"), f.calls)
        assertFalse(f.calls.any { it.startsWith("discard") || it == "published" || it == "prepared" })
    }
    @Test fun emptyAudioIsDroppedAndVideoWithBytesIsKept() {
        val f = SalvageFixture(); f.audioBytes = false
        val result = f.run(success = false)
        assertEquals("video" to null, result.outputs); assertTrue(result.incomplete)
        assertNull(result.salvagedAudio)
    }
    @Test fun emptyVideoIsDroppedAndAudioWithBytesIsKept() {
        val f = SalvageFixture(); f.videoBytes = false
        val result = f.run(success = false)
        assertNull(result.outputs); assertEquals("audio", result.salvagedAudio); assertTrue(result.incomplete)
        assertFalse(f.calls.contains("publish:preparedVideo"))
    }
    @Test fun nothingWithBytesIsNotAnIncompleteTake() {
        val f = SalvageFixture(); f.audioBytes = false; f.videoBytes = false
        val result = f.run(success = false)
        assertNull(result.outputs); assertFalse(result.incomplete); assertTrue(result.incompleteReasons.isEmpty())
        assertFalse(f.calls.any { it.startsWith("publish") })
    }
    @Test fun failedSalvageStepsKeepRowsPendingAndReportTheCauseWhenNothingWasKept() {
        val f = SalvageFixture(); f.failAt = "preparedVideo"; f.audioBytes = false
        assertSame(f.failure, assertThrows(IllegalStateException::class.java) { f.run(success = false) })
        assertFalse(f.calls.any { it.startsWith("discard") })
    }
    @Test fun oneFailedSalvageStepDoesNotLoseTheOtherOutput() {
        val f = SalvageFixture(); f.failAt = "preparedAudio"
        val result = f.run(success = false)
        assertEquals("video" to null, result.outputs); assertEquals(listOf(f.failure), result.incompleteFailures)
    }
    @Test fun publicationFailureSalvagesInsteadOfCompensatingAndAddsItsReason() {
        for (stage in listOf("audio", "video")) {
            val f = SalvageFixture(reasons = emptyList())
            val result = f.run(success = true, failPublish = stage)
            assertTrue(result.incomplete)
            assertEquals(listOf(PUBLICATION_FAILED_REASON), result.incompleteReasons)
            assertEquals(listOf(listOf(PUBLICATION_FAILED_REASON), listOf(PUBLICATION_FAILED_REASON)), f.seenReasons)
            assertFalse(f.calls.any { it.startsWith("discard") })
            assertFalse("published" in f.calls)
        }
    }
    @Test fun incompleteTakesStayOutOfTheTransferJournal() {
        val f = SalvageFixture(); f.run(success = false)
        assertFalse("prepared" in f.calls); assertFalse("published" in f.calls); assertTrue("aborted" in f.calls)
    }
}
