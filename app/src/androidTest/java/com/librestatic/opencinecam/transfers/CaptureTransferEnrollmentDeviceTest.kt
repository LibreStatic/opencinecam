/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class CaptureTransferEnrollmentDeviceTest {
    @Test fun durableReopenRetainsExactLongRevisionsAndIdempotencyDoesNotRewrite() = directory { root ->
        val value = value(1).copy(endpointRevision = 9_007_199_254_740_993L, consentRevision = Long.MAX_VALUE)
        assertEquals(value, CaptureTransferEnrollmentFile(root).enroll(value))
        val file = file(root, value.bundleId)
        val bytes = file.readBytes()
        assertTrue(file.setLastModified(1_234_567_890_000L))
        val modified = file.lastModified()
        assertEquals(value, CaptureTransferEnrollmentFile(root).load(value.bundleId))
        assertEquals(value, CaptureTransferEnrollmentFile(root).enroll(value))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(modified, file.lastModified())
    }

    @Test fun contextConstructorUsesPrivateNoBackupStorageWithoutTouchingOtherEnrollments() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val value = value(1).copy(bundleId = UUID.randomUUID().toString())
        val root = File(context.noBackupFilesDir, "capture-transfer-enrollment")
        try {
            val store: CaptureTransferEnrollmentStore = CaptureTransferEnrollmentFile(context)
            assertEquals(value, store.enroll(value))
            assertEquals(value, CaptureTransferEnrollmentFile(context).load(value.bundleId))
            assertTrue(file(root, value.bundleId).isFile)
        } finally {
            for (suffix in listOf(".json", ".json.new", ".json.bak")) File(root, value.bundleId + suffix).delete()
        }
    }

    @Test fun missingEnrollmentIsNotCreatedByLoad() = directory { root ->
        assertNull(CaptureTransferEnrollmentFile(root).load(id(1)))
        assertFalse(root.exists())
    }

    @Test fun endpointOrConsentRebindingFailsAndPreservesBothOwnersBytes() = directory { root ->
        val store = CaptureTransferEnrollmentFile(root)
        val original = value(1)
        store.enroll(original)
        store.enroll(value(2))
        val before = snapshot(root)
        for (conflict in listOf(original.copy(endpointId = id(9)), original.copy(endpointRevision = 4), original.copy(consentRevision = 8))) {
            assertThrows(CaptureTransferEnrollmentConflict::class.java) { CaptureTransferEnrollmentFile(root).enroll(conflict) }
            assertSnapshot(before, root)
        }
    }

    @Test fun corruptFutureOversizedAndDeepBytesStayIntactWithoutResetOrRebind() = directory { root ->
        val store = CaptureTransferEnrollmentFile(root)
        val enrollment = value(1)
        store.enroll(enrollment)
        store.enroll(value(2))
        val other = file(root, id(2)).readBytes()
        val valid = file(root, enrollment.bundleId).readText()
        for (bytes in listOf("broken".toByteArray(), valid.replace("\"version\":1", "\"version\":2").toByteArray(),
            ByteArray(CaptureTransferEnrollmentCodec.MAX_BYTES + 1), ("[".repeat(400) + "0" + "]".repeat(400)).toByteArray())) {
            file(root, enrollment.bundleId).writeBytes(bytes)
            assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { store.load(enrollment.bundleId) }
            assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { store.enroll(enrollment) }
            assertArrayEquals(bytes, file(root, enrollment.bundleId).readBytes())
            assertArrayEquals(other, file(root, id(2)).readBytes())
        }
    }

    @Test fun filenameAndDocumentBundleMustMatch() = directory { root ->
        val store = CaptureTransferEnrollmentFile(root)
        store.enroll(value(1))
        val swapped = CaptureTransferEnrollmentCodec.encode(value(2))
        file(root, id(1)).writeBytes(swapped)
        assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { store.load(id(1)) }
        assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { store.enroll(value(1)) }
        assertArrayEquals(swapped, file(root, id(1)).readBytes())
    }

    @Test fun partialFirstAtomicWriteIsPreservedAndNeverBecomesConsent() = directory { root ->
        root.mkdirs()
        AtomicFile(file(root, id(1))).startWrite().use { it.write("{partial".toByteArray()); it.fd.sync() }
        val before = snapshot(root)
        val store = CaptureTransferEnrollmentFile(root)
        assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { store.load(id(1)) }
        assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { store.enroll(value(1)) }
        assertSnapshot(before, root)
    }

    @Test fun pendingOrBackupBesideCommittedBytesDoesNotCauseDestructiveRecovery() = directory { root ->
        val store = CaptureTransferEnrollmentFile(root)
        store.enroll(value(1))
        for (suffix in listOf(".new", ".bak")) {
            val residual = File(root, "${id(1)}.json$suffix")
            residual.writeText("incomplete competing record")
            val before = snapshot(root)
            assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { store.load(id(1)) }
            assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { store.enroll(value(1)) }
            assertSnapshot(before, root)
            assertTrue(residual.delete())
        }
        assertEquals(value(1), store.load(id(1)))
    }

    @Test fun capacityIsVisibleDoesNotPurgeAndAllowsIdenticalEnrollment() = directory { root ->
        val store = CaptureTransferEnrollmentFile(root, capacity = 2)
        store.enroll(value(1)); store.enroll(value(2))
        val before = snapshot(root)
        assertThrows(CaptureTransferEnrollmentCapacity::class.java) { store.enroll(value(3)) }
        assertEquals(value(1), store.enroll(value(1)))
        assertEquals(value(2), CaptureTransferEnrollmentFile(root, capacity = 2).load(id(2)))
        assertSnapshot(before, root)
    }

    @Test fun occupiedDirectoryAndUnexpectedEntryAreNotReplacedOrDeleted() = directory { root ->
        root.writeText("occupied")
        val store = CaptureTransferEnrollmentFile(root)
        assertThrows(IOException::class.java) { store.enroll(value(1)) }
        assertEquals("occupied", root.readText())
        assertTrue(root.delete()); assertTrue(root.mkdirs())
        File(root, "unexpected").writeText("retain")
        val before = snapshot(root)
        assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { store.enroll(value(1)) }
        assertSnapshot(before, root)
    }

    @Test fun concurrentConflictingInstancesBindExactlyOneImmutableEnrollment() = directory { root ->
        val choices = listOf(value(1), value(1).copy(endpointId = id(9)))
        val accepted = concurrent(2) { index ->
            try { CaptureTransferEnrollmentFile(root).enroll(choices[index]) }
            catch (_: CaptureTransferEnrollmentConflict) { null }
        }.filterNotNull()
        assertEquals(1, accepted.size)
        assertEquals(accepted.single(), CaptureTransferEnrollmentFile(root).load(id(1)))
        assertEquals(1, root.listFiles().orEmpty().size)
    }

    @Test fun concurrentNewBundlesRespectCapacityAcrossInstancesWithoutEviction() = directory { root ->
        val accepted = concurrent(8) { index ->
            try { CaptureTransferEnrollmentFile(root, capacity = 2).enroll(value(index + 1)) }
            catch (_: CaptureTransferEnrollmentCapacity) { null }
        }.filterNotNull()
        assertEquals(2, accepted.size)
        assertEquals(2, root.listFiles().orEmpty().size)
        accepted.forEach { assertEquals(it, CaptureTransferEnrollmentFile(root, capacity = 2).load(it.bundleId)) }
    }

    private fun <T> concurrent(count: Int, task: (Int) -> T): List<T> {
        val executor = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until count).map { index -> executor.submit<T> { check(start.await(5, TimeUnit.SECONDS)); task(index) } }
            start.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun snapshot(root: File) = root.listFiles().orEmpty().associate { it.name to it.readBytes() }
    private fun assertSnapshot(expected: Map<String, ByteArray>, root: File) {
        assertEquals(expected.keys, root.listFiles().orEmpty().map { it.name }.toSet())
        expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(root, name).readBytes()) }
    }
    private fun directory(test: (File) -> Unit) {
        val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "transfer-enrollment-${UUID.randomUUID()}")
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun file(root: File, id: String) = File(root, "$id.json")
    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
    private fun value(bundle: Int) = CaptureTransferEnrollment(id(bundle), id(100), 3, 7)
}
