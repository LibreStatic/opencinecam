/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import org.junit.Assert.*
import org.junit.Test

class CapturePublicationJournalTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val video = PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, "content://media/external/video/media/1", "take.mp4")
    private val metadata = PreparedCaptureArtifact(CaptureArtifactRole.VIDEO_METADATA, "content://media/external/downloads/2", "take.timing.json")

    @Test fun preparedAndPublishedCallbacksBindTheSameOrderIndependentArtifactSet() {
        val prepared = CapturePublicationTransitions.prepared(id, null, listOf(video, metadata))
        assertEquals(CapturePublicationState.PREPARED, prepared.state)
        val committed = CapturePublicationTransitions.published(id, prepared, listOf(metadata, video))
        assertEquals(CapturePublicationState.COMMITTED, committed.state)
        assertEquals(prepared.artifacts, committed.artifacts)
        assertEquals(committed, CapturePublicationTransitions.published(id, committed, listOf(video, metadata)))
        assertEquals(committed, CapturePublicationTransitions.prepared(id, committed, listOf(video, metadata)))
    }

    @Test fun publicationWithoutPreparationCannotInventACommittedReceipt() {
        assertThrows(IllegalArgumentException::class.java) { CapturePublicationTransitions.published(id, null, listOf(video)) }
    }

    @Test fun callbackWithChangedArtifactSetCannotReplacePreparedIdentity() {
        val prepared = CapturePublicationTransitions.prepared(id, null, listOf(video, metadata))
        for (changed in listOf(listOf(video), listOf(video, metadata.copy(displayName = "other.json")),
            listOf(video.copy(uri = "content://media/external/video/media/3"), metadata))) {
            assertThrows(IllegalArgumentException::class.java) { CapturePublicationTransitions.prepared(id, prepared, changed) }
            assertThrows(IllegalArgumentException::class.java) { CapturePublicationTransitions.published(id, prepared, changed) }
        }
    }

    @Test fun earlyAbortPersistsAnEmptyTombstoneAndRejectsLatePreparation() {
        val aborted = CapturePublicationTransitions.aborted(id, null)
        assertEquals(CapturePublicationState.ABORTED, aborted.state)
        assertTrue(aborted.artifacts.isEmpty())
        assertEquals(aborted, CapturePublicationTransitions.aborted(id, aborted))
        assertThrows(IllegalArgumentException::class.java) { CapturePublicationTransitions.prepared(id, aborted, listOf(video)) }
        assertThrows(IllegalArgumentException::class.java) { CapturePublicationTransitions.published(id, aborted, listOf(video)) }
    }

    @Test fun abortAfterPreparationRetainsEveryOwnedArtifactIdentity() {
        val prepared = CapturePublicationTransitions.prepared(id, null, listOf(video, metadata))
        val aborted = CapturePublicationTransitions.aborted(id, prepared)
        assertEquals(prepared.artifacts, aborted.artifacts)
        assertEquals(CapturePublicationState.ABORTED, aborted.state)
        assertThrows(IllegalArgumentException::class.java) { CapturePublicationTransitions.published(id, aborted, prepared.artifacts) }
    }

    @Test fun lateAbortCannotRevokeCommittedPublication() {
        val prepared = CapturePublicationTransitions.prepared(id, null, listOf(video))
        val committed = CapturePublicationTransitions.published(id, prepared, listOf(video))
        assertThrows(IllegalArgumentException::class.java) { CapturePublicationTransitions.aborted(id, committed) }
    }

    @Test fun receiptsRequireCanonicalUuidAndCompleteUniqueRolesAndUris() {
        assertThrows(IllegalArgumentException::class.java) { CapturePublicationReceipt("https://user:secret@host/", CapturePublicationState.PREPARED, listOf(video)) }
        for (bad in listOf(emptyList(), listOf(metadata), listOf(video, video), listOf(video, metadata.copy(uri = video.uri)),
            listOf(video, metadata.copy(role = CaptureArtifactRole.AUDIO_METADATA)))) {
            assertThrows(IllegalArgumentException::class.java) { CapturePublicationReceipt(id, CapturePublicationState.PREPARED, bad) }
        }
    }

    @Test fun canonicalMediaStoreItemValidationRejectsAliasesAndArbitraryUris() {
        for (uri in listOf("file:///private/take.mp4", "content://other/external/video/media/1", "content://media/external/video/media",
            "content://media/external/video/media/0", "content://media/external/video/media/01", "content://media/external/video/media/%31",
            "content://media/external/video/media/+1", "content://media/external/video/media/1?token=secret", "content://media/external/video/media/1#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { CapturePublicationReceipt(id, CapturePublicationState.PREPARED, listOf(video.copy(uri = uri))) }
        }
    }

    @Test fun filenameValidationPreservesValidUnicodeButRejectsTraversalAndControls() {
        val name = "take ñ :50%?#.mp4"
        assertEquals(name, CapturePublicationReceipt(id, CapturePublicationState.PREPARED, listOf(video.copy(displayName = name))).artifacts.single().displayName)
        for (bad in listOf("", "..", "a/b", "a\\b", "x\r\ny", "a".repeat(256), "bad\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { CapturePublicationReceipt(id, CapturePublicationState.PREPARED, listOf(video.copy(displayName = bad))) }
        }
    }

    @Test fun receiptCopiesAndFreezesCallerCollection() {
        val supplied = mutableListOf(video)
        val receipt = CapturePublicationReceipt(id, CapturePublicationState.PREPARED, supplied)
        supplied.clear()
        assertEquals(listOf(video), receipt.artifacts)
        assertThrows(UnsupportedOperationException::class.java) { (receipt.artifacts as MutableList<PreparedCaptureArtifact>).clear() }
    }

    @Test fun codecRoundTripsEveryStateIncludingEarlyAbort() {
        for (state in CapturePublicationState.entries) {
            val receipt = CapturePublicationReceipt(id, state, listOf(video, metadata))
            assertEquals(receipt, CapturePublicationJournalCodec.decode(CapturePublicationJournalCodec.encode(receipt)))
        }
        val empty = CapturePublicationTransitions.aborted(id, null)
        assertEquals(empty, CapturePublicationJournalCodec.decode(CapturePublicationJournalCodec.encode(empty)))
    }

    @Test fun futureSchemaUnknownFieldsDuplicateKeysAndCoercibleVersionsAreRejected() {
        val valid = CapturePublicationJournalCodec.encode(CapturePublicationReceipt(id, CapturePublicationState.PREPARED, listOf(video))).toString(Charsets.UTF_8)
        for (invalid in listOf(valid.replace("\"version\":1", "\"version\":2"), valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"version\":1", "\"version\":1.0"), valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"credential\":\"secret\""), valid.replace("PREPARED", "FUTURE_STATE"),
            valid.replace("\"VIDEO\"", "\"FUTURE_ROLE\""))) {
            assertThrows(CapturePublicationJournalCorruptData::class.java) { CapturePublicationJournalCodec.decode(invalid.toByteArray()) }
        }
    }

    @Test fun codecRejectsOversizedInvalidUtf8TruncatedAndDeeplyNestedData() {
        for (invalid in listOf(ByteArray(CapturePublicationJournalCodec.MAX_BYTES + 1), byteArrayOf(0xc3.toByte(), 0x28), "{".toByteArray(),
            ("[".repeat(1000) + "]".repeat(1000)).toByteArray())) {
            assertThrows(CapturePublicationJournalCorruptData::class.java) { CapturePublicationJournalCodec.decode(invalid) }
        }
    }

    @Test fun lexicalGuardRejectsThousandsOfLevelsWithinByteBudgetBeforeRecursiveParsing() {
        for (document in listOf("[".repeat(8192) + "]".repeat(8192), "{\"x\":".repeat(2000) + "0" + "}".repeat(2000))) {
            val bytes = document.toByteArray(Charsets.UTF_8)
            assertTrue(bytes.size <= CapturePublicationJournalCodec.MAX_BYTES)
            assertThrows(CapturePublicationJournalCorruptData::class.java) { CapturePublicationJournalCodec.decode(bytes) }
        }
    }

    @Test fun lexicalGuardDoesNotCountBracesBracketsOrEscapedQuotesInsideValidNames() {
        val name = "take [{\"quoted\":[[[{}]]]}].mp4"
        val receipt = CapturePublicationReceipt(id, CapturePublicationState.PREPARED, listOf(video.copy(displayName = name)))
        val encoded = CapturePublicationJournalCodec.encode(receipt)
        assertTrue(encoded.toString(Charsets.UTF_8).contains("\\\"quoted\\\""))
        assertEquals(receipt, CapturePublicationJournalCodec.decode(encoded))
        assertEquals(name, CapturePublicationJournalCodec.decode(encoded).artifacts.single().displayName)
    }

    @Test fun callbacksCannotRebindAnotherBundleId() {
        val previous = CapturePublicationTransitions.prepared(id, null, listOf(video))
        val other = "00000000-0000-0000-0000-000000000002"
        assertThrows(IllegalArgumentException::class.java) { CapturePublicationTransitions.prepared(other, previous, listOf(video)) }
        assertThrows(IllegalArgumentException::class.java) { CapturePublicationTransitions.published(other, previous, listOf(video)) }
        assertThrows(IllegalArgumentException::class.java) { CapturePublicationTransitions.aborted(other, previous) }
    }
}
