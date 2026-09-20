/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapturePublicationJournalDeviceTest {
    private val video = PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, "content://media/external/video/media/1", "take.mp4")
    private val metadata = PreparedCaptureArtifact(CaptureArtifactRole.VIDEO_METADATA, "content://media/external/downloads/2", "take.timing.json")

    @Test fun observerPersistsPreparedThenCommittedCompleteSetAcrossReopen() = directory { root ->
        val id = id(1)
        val observer = CapturePublicationJournal(root, id)
        assertNull(observer.load())
        observer.onPrepared(listOf(video, metadata))
        val prepared = requireNotNull(CapturePublicationJournal(root, id).load())
        assertEquals(CapturePublicationState.PREPARED, prepared.state)
        assertEquals(2, prepared.artifacts.size)
        CapturePublicationJournal(root, id).onPublished(listOf(metadata, video))
        val committed = requireNotNull(CapturePublicationJournal(root, id).load())
        assertEquals(CapturePublicationState.COMMITTED, committed.state)
        assertEquals(prepared.artifacts, committed.artifacts)
        assertEquals(listOf(committed), CapturePublicationJournal.listDirectory(root))
    }

    @Test fun contextApiStoresOnlyPrivateNoBackupReceiptAndSupportsLoadAndList() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val journal = CapturePublicationJournal(context)
        val root = File(context.noBackupFilesDir, "capture-publication")
        try {
            journal.onPrepared(listOf(video))
            journal.onPublished(listOf(video))
            assertEquals(journal.load(), CapturePublicationJournal.load(context, journal.bundleId))
            assertTrue(CapturePublicationJournal.list(context, CapturePublicationJournal.MAX_RECEIPTS).any { it.bundleId == journal.bundleId })
            assertTrue(File(root, "${journal.bundleId}.json").isFile)
        } finally {
            for (suffix in listOf(".json", ".json.bak", ".json.new")) File(root, journal.bundleId + suffix).delete()
        }
    }

    @Test fun abortedTombstonesSurviveReopenAndNeverPermitLatePublication() = directory { root ->
        val early = CapturePublicationJournal(root, id(1))
        early.onAborted()
        val empty = requireNotNull(CapturePublicationJournal(root, id(1)).load())
        assertEquals(CapturePublicationState.ABORTED, empty.state)
        assertTrue(empty.artifacts.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { early.onPrepared(listOf(video)) }
        val later = CapturePublicationJournal(root, id(2))
        later.onPrepared(listOf(video, metadata))
        later.onAborted()
        val retained = requireNotNull(CapturePublicationJournal(root, id(2)).load())
        assertEquals(2, retained.artifacts.size)
        assertEquals(CapturePublicationState.ABORTED, retained.state)
        assertThrows(IllegalArgumentException::class.java) { later.onPublished(listOf(video, metadata)) }
    }

    @Test fun failedAtomicWriteRestoresPreviousPreparedReceiptRatherThanCommittingPartialData() = directory { root ->
        val journal = CapturePublicationJournal(root, id(1))
        journal.onPrepared(listOf(video))
        val previous = requireNotNull(journal.load())
        AtomicFile(File(root, "${id(1)}.json")).startWrite().use { stream ->
            stream.write("{\"version\":1,\"state\":\"COMMITTED\"".toByteArray())
            stream.fd.sync()
            // Deliberately no finishWrite: emulate writer death after a partial durable write.
        }
        assertEquals(previous, CapturePublicationJournal(root, id(1)).load())
        assertEquals(CapturePublicationState.PREPARED, journal.load()?.state)
        journal.onPublished(listOf(video))
        assertEquals(CapturePublicationState.COMMITTED, journal.load()?.state)
    }

    @Test fun corruptedAndFutureSchemaBytesArePreservedWithoutReset() = directory { root ->
        val journal = CapturePublicationJournal(root, id(1))
        journal.onPrepared(listOf(video))
        val file = File(root, "${id(1)}.json")
        val valid = file.readBytes()
        for (bad in listOf("not-json".toByteArray(), valid.toString(Charsets.UTF_8).replace("\"version\":1", "\"version\":2").toByteArray(),
            ByteArray(CapturePublicationJournalCodec.MAX_BYTES + 1))) {
            file.writeBytes(bad)
            assertThrows(CapturePublicationJournalCorruptData::class.java) { journal.load() }
            assertThrows(CapturePublicationJournalCorruptData::class.java) { journal.onAborted() }
            assertThrows(CapturePublicationJournalCorruptData::class.java) { CapturePublicationJournal.listDirectory(root) }
            assertArrayEquals(bad, file.readBytes())
        }
    }

    @Test fun incompleteFirstWriteOrOrphanTemporaryFileIsNotSilentlyForgotten() = directory { root ->
        root.mkdirs()
        val first = File(root, "${id(1)}.json")
        AtomicFile(first).startWrite().use { it.write("{partial".toByteArray()); it.fd.sync() }
        val present = root.listFiles().orEmpty().associate { it.name to it.readBytes() }
        assertThrows(CapturePublicationJournalCorruptData::class.java) { CapturePublicationJournal(root, id(1)).load() }
        for ((name, bytes) in present) assertArrayEquals(bytes, File(root, name).readBytes())
        val pending = File(root, "${id(2)}.json.new")
        pending.writeBytes("{orphan".toByteArray())
        assertThrows(CapturePublicationJournalCorruptData::class.java) { CapturePublicationJournal(root, id(2)).onPrepared(listOf(video)) }
        assertEquals("{orphan", pending.readText())
    }

    @Test fun capacityIsVisibleExistingReceiptsRemainAndCanFinishWithoutAutomaticPurge() = directory { root ->
        val first = CapturePublicationJournal(root, id(1), capacity = 2)
        val second = CapturePublicationJournal(root, id(2), capacity = 2)
        first.onPrepared(listOf(video))
        second.onAborted()
        val retained = root.listFiles().orEmpty().associate { it.name to it.readBytes() }
        assertThrows(CapturePublicationJournalCapacity::class.java) { CapturePublicationJournal(root, id(3), capacity = 2).onPrepared(listOf(video)) }
        for ((name, bytes) in retained) assertArrayEquals(bytes, File(root, name).readBytes())
        first.onPublished(listOf(video))
        val page = CapturePublicationJournal.listDirectory(root, limit = 1, capacity = 2)
        assertEquals(id(1), page.single().bundleId)
        val next = CapturePublicationJournal.listDirectory(root, limit = 1, afterBundleId = page.single().bundleId, capacity = 2)
        assertEquals(id(2), next.single().bundleId)
        assertEquals(CapturePublicationState.COMMITTED, first.load()?.state)
    }

    @Test fun observerAbortDoesNotDeleteOrPublishItsActualMediaStoreRows() = directory { root ->
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Journal-abort-${System.nanoTime()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst()); cursor.getString(0)
            }
            val artifact = PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, uri.toString(), name)
            val journal = CapturePublicationJournal(root, id(1))
            journal.onPrepared(listOf(artifact))
            journal.onAborted()
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0))
            }
            assertEquals(listOf(artifact), journal.load()?.artifacts)
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun mismatchedOrLateCallbacksCannotOverwriteCommittedBytes() = directory { root ->
        val journal = CapturePublicationJournal(root, id(1))
        journal.onPrepared(listOf(video, metadata))
        val prepared = File(root, "${id(1)}.json").readBytes()
        assertThrows(IllegalArgumentException::class.java) { journal.onPublished(listOf(video)) }
        assertArrayEquals(prepared, File(root, "${id(1)}.json").readBytes())
        journal.onPublished(listOf(video, metadata))
        val committed = File(root, "${id(1)}.json").readBytes()
        assertThrows(IllegalArgumentException::class.java) { journal.onAborted() }
        assertArrayEquals(committed, File(root, "${id(1)}.json").readBytes())
    }

    @Test fun receiptFilenameCannotBeReboundByAValidDifferentBundleDocument() = directory { root ->
        val journal = CapturePublicationJournal(root, id(1))
        journal.onPrepared(listOf(video))
        val swapped = CapturePublicationJournalCodec.encode(CapturePublicationReceipt(id(2), CapturePublicationState.COMMITTED, listOf(video)))
        val file = File(root, "${id(1)}.json")
        file.writeBytes(swapped)
        assertThrows(CapturePublicationJournalCorruptData::class.java) { journal.load() }
        assertArrayEquals(swapped, file.readBytes())
    }

    @Test fun unavailableDirectoryFailsExplicitlyWithoutReplacingItsBytes() = directory { root ->
        root.writeBytes("occupied".toByteArray())
        val journal = CapturePublicationJournal(root, id(1))
        assertThrows(IOException::class.java) { journal.onPrepared(listOf(video)) }
        assertEquals("occupied", root.readText())
    }

    @Test fun concurrentObserversCannotBindDifferentSetsToOneBundle() = directory { root ->
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val writes = (0..1).map { index -> executor.submit<Boolean> {
                start.await()
                try {
                    CapturePublicationJournal(root, id(1)).onPrepared(listOf(video.copy(uri = "content://media/external/video/media/${index + 1}")))
                    true
                } catch (_: IllegalArgumentException) { false }
            } }
            start.countDown()
            assertEquals(1, writes.map { it.get(10, TimeUnit.SECONDS) }.count { it })
            assertEquals(CapturePublicationState.PREPARED, CapturePublicationJournal(root, id(1)).load()?.state)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun directory(test: (File) -> Unit) {
        val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "publication-journal-${UUID.randomUUID()}")
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
