/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import org.junit.Assert.*
import org.junit.Test

class RecordingTakeFinalizationTest {
    @Test fun successKeepsBothOutputsAndFinishesAudioBeforeVideo() {
        val calls = mutableListOf<String>()
        val result = finalizeRecordingTake(true, { ok -> assertTrue(ok); calls += "audio"; "wav" },
            { fail("discarded") }, { ok -> assertTrue(ok); calls += "video"; "mp4" })
        assertEquals("mp4" to "wav", result)
        assertEquals(listOf("audio", "video"), calls)
    }
    @Test fun silentSuccessDoesNotRequireAnAudioResult() {
        assertEquals("mp4" to null, finalizeRecordingTake<String, String>(true, null, { fail("discarded") }, { "mp4" }))
    }
    @Test fun missingRequestedAudioAbortsVideoAndRevokesAudio() {
        var discarded = false
        val accepted = mutableListOf<Boolean>()
        assertThrows(IllegalStateException::class.java) {
            finalizeRecordingTake<String, String>(true, { null }, { discarded = true }, { accepted += it; null })
        }
        assertTrue(discarded)
        assertEquals(listOf(false), accepted)
    }
    @Test fun videoFailureRevokesAlreadyCompletedAudio() {
        val failure = IllegalStateException("video")
        val calls = mutableListOf<String>()
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            finalizeRecordingTake(true, { "wav" }, { calls += "discard" }, { ok ->
                calls += "video:$ok"
                if (ok) throw failure else null
            })
        })
        assertEquals(listOf("video:true", "video:false", "discard"), calls)
    }
    @Test fun audioFailureRetainsBothCleanupFailures() {
        val first = IllegalStateException("audio")
        val video = IllegalStateException("video cleanup")
        val audio = IllegalStateException("audio cleanup")
        assertSame(first, assertThrows(IllegalStateException::class.java) {
            finalizeRecordingTake<String, String>(true, { throw first }, { throw audio }, { throw video })
        })
        assertArrayEquals(arrayOf(video, audio), first.suppressed)
    }
    @Test fun unsuccessfulTakeExplicitlyDiscardsEvenPreviouslyCompletedAudio() {
        var discarded = false
        assertNull(finalizeRecordingTake(false, { ok -> assertFalse(ok); "previously saved" },
            { discarded = true }, { ok -> assertFalse(ok); null as String? }))
        assertTrue(discarded)
    }
    @Test fun ownedRowsDeleteOnceDespiteDuplicateRegistration() {
        val removed = mutableListOf<String>()
        val rows = OwnedOutputRows<String> { removed += it }
        rows.add("audio"); rows.add("metadata"); rows.add("audio")
        rows.deleteAll(); rows.deleteAll()
        assertEquals(listOf("audio", "metadata"), removed)
    }
    @Test fun deletionFailureStillAttemptsMetadataAndRetriesOnlyFailedRows() {
        var fails = true
        val removed = mutableListOf<String>()
        val rows = OwnedOutputRows<String> { removed += it; if (fails && it == "audio") error("delete") }
        rows.add("audio"); rows.add("metadata")
        assertThrows(IllegalStateException::class.java) { rows.deleteAll() }
        fails = false; rows.deleteAll()
        assertEquals(listOf("audio", "metadata", "audio"), removed)
    }
    @Test fun allDeletionFailuresAreReportedInRegistrationOrder() {
        val first = IllegalStateException("audio"); val second = IllegalStateException("metadata")
        val rows = OwnedOutputRows<String> { throw if (it == "audio") first else second }
        rows.add("audio"); rows.add("metadata")
        assertSame(first, assertThrows(IllegalStateException::class.java) { rows.deleteAll() })
        assertArrayEquals(arrayOf(second), first.suppressed)
    }
}
