/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.CubeLut
import com.librestatic.opencinecam.camera.LutSignalDomain
import com.librestatic.opencinecam.camera.LutTransformKind
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class LutLibraryTest {
    @Test fun originalCommentsWhitespaceAndCrLfSurviveExportAndReopenExactly() {
        val storage = MemoryStorage(); val library = LutLibrary(storage)
        val raw = cube("original").toString(Charsets.UTF_8).replace("0 0 0", " 0.0\t0 0 ").replace("\n", "\r\n").toByteArray()
        val expected = raw.copyOf()
        val entry = library.importLut(raw, "Declared name", LutTransformKind.TECHNICAL, LutSignalDomain.OCLOG2_CODE)
        assertEquals(sha(expected), entry.hash); assertEquals(expected.size, entry.bytes)
        raw.fill(0)
        assertArrayEquals(expected, library.export(entry.hash))
        library.export(entry.hash).fill(0)
        library.select(entry.hash)
        val reopened = LutLibrary(storage)
        assertNull(reopened.states.value.error)
        assertEquals(entry.hash, reopened.states.value.operatorHash)
        assertArrayEquals(expected, reopened.export(entry.hash))
        val active = requireNotNull(reopened.active())
        assertEquals(LutTransformKind.TECHNICAL, active.kind)
        assertEquals(LutSignalDomain.OCLOG2_CODE, active.input)
        assertEquals(LutSignalDomain.SDR_BT709_CODE, active.output)
        assertEquals(entry.hash, active.cube.sha256)
    }
    @Test fun importDoesNotSelectAndDeleteSelectedDisablesWithoutRemovingOtherOriginals() {
        val storage = MemoryStorage(); val library = LutLibrary(storage)
        val first = add(library, "first"); val second = add(library, "second")
        assertNull(library.active()); assertNull(library.states.value.operatorHash)
        assertNull(library.activeRecording()); assertNull(library.states.value.recordingHash)
        library.select(first.hash); assertEquals(first.hash, library.active()?.cube?.sha256)
        library.delete(first.hash)
        val reopened = LutLibrary(storage)
        assertNull(reopened.active()); assertNull(reopened.states.value.operatorHash)
        assertEquals(listOf(second.hash), reopened.states.value.entries.map { it.hash })
        assertArrayEquals(cube("second"), reopened.export(second.hash))
    }
    @Test fun duplicateHashIsIdempotentOnlyWithTheSameExplicitDeclaration() {
        val storage = MemoryStorage(); val library = LutLibrary(storage)
        val entry = add(library, "one"); val committed = requireNotNull(storage.bytes).copyOf()
        assertEquals(entry.hash, add(library, "one").hash)
        for ((name, kind, input) in listOf(Triple("changed", LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE),
            Triple("one", LutTransformKind.TECHNICAL, LutSignalDomain.SDR_BT709_CODE),
            Triple("one", LutTransformKind.CREATIVE, LutSignalDomain.OCLOG2_CODE))) {
            assertThrows(IllegalArgumentException::class.java) { library.importLut(cube("one"), name, kind, input) }
        }
        assertArrayEquals(committed, storage.bytes); assertEquals(1, library.states.value.entries.size)
    }
    @Test fun boundedImportsRejectNinthEntryOversizeMalformedAndInvalidNamesWithoutMutation() {
        val storage = MemoryStorage(); val library = LutLibrary(storage)
        repeat(8) { add(library, "fixture-$it") }
        val committed = requireNotNull(storage.bytes).copyOf()
        assertThrows(IllegalArgumentException::class.java) { add(library, "ninth") }
        assertThrows(IllegalArgumentException::class.java) { library.importLut(ByteArray(CubeLut.MAX_BYTES + 1), "large", LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE) }
        for (name in listOf("", " ", "a".repeat(121), "bad\nname")) {
            assertThrows(IllegalArgumentException::class.java) { library.importLut(cube("valid"), name, LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE) }
        }
        assertThrows(IllegalArgumentException::class.java) { library.importLut("invalid".toByteArray(), "bad", LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE) }
        assertArrayEquals(committed, storage.bytes); assertNull(library.states.value.error)
        assertEquals(8, LutLibrary(storage).states.value.entries.size)
    }
    @Test fun failedAtomicWriteNeverPublishesImportOrSelectionAndOldCommitReopens() {
        for (select in listOf(false, true)) {
            val storage = MemoryStorage(); val library = LutLibrary(storage); val first = add(library, "first")
            val committed = requireNotNull(storage.bytes).copyOf()
            storage.failWrites = true
            assertThrows(IOException::class.java) { if (select) library.select(first.hash) else add(library, "second") }
            assertArrayEquals(committed, storage.bytes)
            assertEquals(listOf(first.hash), library.states.value.entries.map { it.hash })
            assertNull(library.states.value.operatorHash); assertNull(library.active())
            assertEquals(LutLibraryError.STORAGE, library.states.value.error)
            assertEquals(listOf(first.hash), LutLibrary(storage).states.value.entries.map { it.hash })
        }
    }
    @Test fun corruptFutureSchemaAndTrailingBytesAreRetainedUntilExplicitReset() {
        val valid = MemoryStorage().also { add(LutLibrary(it), "original") }.bytes!!
        val future = valid.copyOf().also { it[7] = 4 }
        for (bad in listOf(byteArrayOf(1, 2, 3), future, valid + byteArrayOf(0))) {
            val storage = MemoryStorage(bad.copyOf()); val library = LutLibrary(storage)
            assertEquals(LutLibraryError.CORRUPT, library.states.value.error); assertNull(library.active())
            assertThrows(IllegalStateException::class.java) { library.select(null) }
            assertArrayEquals(bad, storage.bytes)
            library.reset()
            assertNull(library.states.value.error); assertTrue(library.states.value.entries.isEmpty())
            assertTrue(LutLibrary(storage).states.value.entries.isEmpty())
        }
    }
    @Test fun persistedHashTamperingIsNotReinterpretedAsAnotherValidOriginal() {
        val storage = MemoryStorage(); val entry = add(LutLibrary(storage), "one")
        val encoded = storage.bytes!!.copyOf()
        val hashAt = encoded.toString(Charsets.ISO_8859_1).indexOf(entry.hash)
        assertTrue(hashAt >= 0)
        encoded[hashAt] = (if (encoded[hashAt] == '0'.code.toByte()) '1' else '0').code.toByte()
        storage.bytes = encoded
        val reopened = LutLibrary(storage)
        assertEquals(LutLibraryError.CORRUPT, reopened.states.value.error)
        assertArrayEquals(encoded, storage.bytes)
    }
    @Test fun invalidBinaryCountSelectionAndEnumNeverAllocateUnboundedPayloadsOrReset() {
        fun header(count: Int, flag: Int, tail: (DataOutputStream) -> Unit = {}): ByteArray = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out -> out.writeInt(0x4c555442); out.writeInt(1); out.writeInt(count); out.writeByte(flag); tail(out) }
        }.toByteArray()
        val cases = listOf(header(Int.MAX_VALUE, 0), header(-1, 0), header(0, 2),
            header(0, 1) { it.writeUTF("a".repeat(64)) },
            header(1, 0) { it.writeUTF("name"); it.writeUTF("UNDECLARED") })
        for (bytes in cases) {
            val storage = MemoryStorage(bytes)
            assertEquals(LutLibraryError.CORRUPT, LutLibrary(storage).states.value.error)
            assertArrayEquals(bytes, storage.bytes)
        }
    }
    @Test fun concurrentInstanceCannotOverwriteNewerSelectionAndCachedActivationRetiresOnFailure() {
        val storage = MemoryStorage(); val first = LutLibrary(storage); val entry = add(first, "one")
        val stale = LutLibrary(storage)
        first.select(entry.hash); val committed = storage.bytes!!.copyOf()
        assertThrows(IllegalStateException::class.java) { stale.delete(entry.hash) }
        assertEquals(LutLibraryError.CORRUPT, stale.states.value.error); assertNull(stale.active())
        assertArrayEquals(committed, storage.bytes)
        assertEquals(entry.hash, LutLibrary(storage).active()?.cube?.sha256)
    }
    @Test fun declaredRangesAndPublishedCollectionsAreDefensive() {
        val library = LutLibrary(MemoryStorage())
        val raw = cube("ranges").toString(Charsets.UTF_8).replace("LUT_3D_SIZE 2", "LUT_3D_SIZE 2\nDOMAIN_MIN -1 0 0\nDOMAIN_MAX 2 1 1").toByteArray()
        val entry = library.importLut(raw, "ranges", LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE)
        assertEquals(listOf(-1f, 0f, 0f), entry.domainMin); assertEquals(listOf(2f, 1f, 1f), entry.domainMax)
        assertThrows(UnsupportedOperationException::class.java) { (entry.domainMin as MutableList<Float>)[0] = 10f }
        assertThrows(UnsupportedOperationException::class.java) { (library.states.value.entries as MutableList<LutLibraryEntry>).clear() }
        library.select(entry.hash); library.active()!!.cube.domainMin.fill(77f)
        assertEquals(-1f, library.active()!!.cube.domainMin[0], 0f)
    }
    @Test fun versionOnePreservesOperatorOriginalAndDefaultsSubjectOffUntilAtomicMigration() {
        val raw = cube("legacy-original")
        val hash = sha(raw)
        val legacy = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x4c555442); out.writeInt(1); out.writeInt(1)
            out.writeBoolean(true); out.writeUTF(hash)
            out.writeUTF("Legacy"); out.writeUTF("TECHNICAL"); out.writeUTF("OCLOG2_CODE"); out.writeUTF(hash)
            out.writeInt(raw.size); out.write(raw)
        } }.toByteArray()
        val storage = MemoryStorage(legacy.copyOf())
        val library = LutLibrary(storage)
        assertNull(library.states.value.error)
        assertEquals(hash, library.states.value.operatorHash)
        assertNull(library.states.value.subjectHash); assertNull(library.activeSubject())
        assertNull(library.states.value.recordingHash); assertNull(library.activeRecording())
        assertEquals(hash, library.active()?.cube?.sha256)
        assertArrayEquals(legacy, storage.bytes) // Reading never upgrades or resets the file.
        assertArrayEquals(raw, library.export(hash))
        library.selectSubject(hash)
        assertEquals(3, storage.bytes!![7].toInt())
        val reopened = LutLibrary(storage)
        assertEquals(hash, reopened.states.value.operatorHash)
        assertEquals(hash, reopened.states.value.subjectHash)
        assertNull(reopened.states.value.recordingHash); assertNull(reopened.activeRecording())
        assertEquals(LutTransformKind.TECHNICAL, reopened.activeSubject()?.kind)
        assertEquals(LutSignalDomain.OCLOG2_CODE, reopened.activeSubject()?.input)
        assertArrayEquals(raw, reopened.export(hash))
    }
    @Test fun independentSelectionsPersistDisableSeparatelyAndDeleteOnlyTheirOwnReferences() {
        val storage = MemoryStorage(); val library = LutLibrary(storage)
        val operator = add(library, "operator"); val subject = add(library, "subject")
        library.select(operator.hash); library.selectSubject(subject.hash)
        val reopened = LutLibrary(storage)
        val pair = reopened.activeSelections()
        assertEquals(operator.hash, pair.operator?.cube?.sha256)
        assertEquals(subject.hash, pair.subject?.cube?.sha256)
        reopened.select(null)
        assertNull(reopened.active()); assertEquals(subject.hash, reopened.activeSubject()?.cube?.sha256)
        reopened.select(operator.hash); reopened.selectSubject(null)
        assertEquals(operator.hash, reopened.active()?.cube?.sha256); assertNull(reopened.activeSubject())
        reopened.selectSubject(subject.hash); reopened.delete(operator.hash)
        assertNull(reopened.states.value.operatorHash)
        assertEquals(subject.hash, reopened.states.value.subjectHash)
        assertArrayEquals(cube("subject"), reopened.export(subject.hash))
        reopened.select(subject.hash); reopened.delete(subject.hash)
        assertEquals(LutLibrarySelection(), reopened.activeSelections())
        val empty = LutLibrary(storage)
        assertNull(empty.states.value.operatorHash); assertNull(empty.states.value.subjectHash)
        assertTrue(empty.states.value.entries.isEmpty())
        assertEquals(operator.hash, pair.operator?.cube?.sha256) // Earlier pair is immutable.
        assertEquals(subject.hash, pair.subject?.cube?.sha256)
    }
    @Test fun subjectFailureRetainsBothSelectionsOnDiskButDisablesBothCachedActivations() {
        val storage = MemoryStorage(); val library = LutLibrary(storage)
        val one = add(library, "one"); val two = add(library, "two")
        library.select(one.hash); library.selectSubject(one.hash)
        val committed = storage.bytes!!.copyOf()
        storage.failWrites = true
        assertThrows(IOException::class.java) { library.selectSubject(two.hash) }
        assertEquals(LutLibraryError.STORAGE, library.states.value.error)
        assertEquals(one.hash, library.states.value.operatorHash); assertEquals(one.hash, library.states.value.subjectHash)
        assertEquals(LutLibrarySelection(available = false), library.activeSelections())
        assertArrayEquals(committed, storage.bytes)
        val reopened = LutLibrary(storage)
        assertEquals(one.hash, reopened.active()?.cube?.sha256); assertEquals(one.hash, reopened.activeSubject()?.cube?.sha256)
    }
    @Test fun corruptV2SubjectSelectorIsRetainedAndExplicitResetClearsBothTargets() {
        fun badSubject(flag: Int, hash: String? = null) = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x4c555442); out.writeInt(2); out.writeInt(0); out.writeBoolean(false)
            out.writeByte(flag); hash?.let(out::writeUTF)
        } }.toByteArray()
        for (bad in listOf(badSubject(2), badSubject(1, "a".repeat(64)), badSubject(1, "bad"))) {
            val storage = MemoryStorage(bad.copyOf()); val library = LutLibrary(storage)
            assertEquals(LutLibraryError.CORRUPT, library.states.value.error)
            assertEquals(LutLibrarySelection(available = false), library.activeSelections())
            assertArrayEquals(bad, storage.bytes)
            library.reset()
            assertNull(library.states.value.operatorHash); assertNull(library.states.value.subjectHash)
            assertNull(LutLibrary(storage).states.value.error)
        }
        val library = LutLibrary(MemoryStorage()); val entry = add(library, "one")
        library.select(entry.hash); library.selectSubject(entry.hash); library.reset()
        assertEquals(LutLibrarySelection(), library.activeSelections())
        assertTrue(library.states.value.entries.isEmpty())
    }
    @Test fun versionTwoPreservesBothMonitorsWithoutImplicitlyBakingAndNextMutationWritesV3() {
        val raw = cube("v2-original"); val hash = sha(raw)
        val legacy = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x4c555442); out.writeInt(2); out.writeInt(1)
            out.writeBoolean(true); out.writeUTF(hash); out.writeBoolean(true); out.writeUTF(hash)
            out.writeUTF("V2"); out.writeUTF("CREATIVE"); out.writeUTF("SDR_BT709_CODE"); out.writeUTF(hash)
            out.writeInt(raw.size); out.write(raw)
        } }.toByteArray()
        val storage = MemoryStorage(legacy.copyOf()); val library = LutLibrary(storage)
        assertArrayEquals(legacy, storage.bytes)
        assertEquals(hash, library.active()?.cube?.sha256); assertEquals(hash, library.activeSubject()?.cube?.sha256)
        assertNull(library.activeRecording()); assertNull(library.states.value.recordingHash)
        library.selectRecording(hash)
        assertEquals(3, storage.bytes!![7].toInt())
        val reopened = LutLibrary(storage)
        assertEquals(hash, reopened.activeSelections().operator?.cube?.sha256)
        assertEquals(hash, reopened.activeSelections().subject?.cube?.sha256)
        assertEquals(hash, reopened.activeSelections().recording?.cube?.sha256)
        assertArrayEquals(raw, reopened.export(hash))
    }
    @Test fun recordingSelectionIsIndependentAndDeletionClearsOnlyReferencesToThatEntry() {
        val storage = MemoryStorage(); val library = LutLibrary(storage)
        val first = add(library, "operator"); val second = add(library, "subject"); val file = add(library, "file")
        library.select(first.hash); library.selectSubject(second.hash)
        assertNull(library.activeRecording())
        library.selectRecording(file.hash)
        val frozen = library.activeSelections()
        library.select(null); library.selectSubject(null)
        assertEquals(file.hash, library.activeRecording()?.cube?.sha256)
        library.select(first.hash); library.selectSubject(second.hash); library.selectRecording(null)
        assertEquals(first.hash, library.active()?.cube?.sha256); assertEquals(second.hash, library.activeSubject()?.cube?.sha256)
        library.selectRecording(file.hash); library.delete(first.hash)
        assertNull(library.active()); assertEquals(second.hash, library.activeSubject()?.cube?.sha256)
        assertEquals(file.hash, library.activeRecording()?.cube?.sha256)
        library.delete(file.hash)
        val reopened = LutLibrary(storage)
        assertNull(reopened.states.value.recordingHash); assertEquals(second.hash, reopened.states.value.subjectHash)
        assertEquals(file.hash, frozen.recording?.cube?.sha256) // Already admitted tuple remains immutable.
        reopened.select(second.hash); reopened.selectRecording(second.hash); reopened.delete(second.hash)
        assertEquals(LutLibrarySelection(), reopened.activeSelections())
    }
    @Test fun recordingWriteFailureDisablesNewActivationWithoutPublishingChangedFileIntent() {
        val storage = MemoryStorage(); val library = LutLibrary(storage)
        val first = add(library, "first"); val second = add(library, "second")
        library.select(first.hash); library.selectSubject(first.hash); library.selectRecording(first.hash)
        val committed = storage.bytes!!.copyOf(); val admitted = library.activeSelections()
        storage.failWrites = true
        assertThrows(IOException::class.java) { library.selectRecording(second.hash) }
        assertEquals(LutLibraryError.STORAGE, library.states.value.error)
        assertEquals(first.hash, library.states.value.recordingHash)
        assertEquals(LutLibrarySelection(available = false), library.activeSelections())
        assertArrayEquals(committed, storage.bytes)
        val reopened = LutLibrary(storage)
        assertEquals(first.hash, reopened.activeRecording()?.cube?.sha256)
        assertEquals(first.hash, admitted.recording?.cube?.sha256)
        assertThrows(IllegalStateException::class.java) { library.selectRecording(null) }
    }
    @Test fun corruptRecordingSelectorNeverEnablesBakingAndResetClearsAllThreeTargets() {
        fun bad(flag: Int, hash: String? = null) = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x4c555442); out.writeInt(3); out.writeInt(0)
            out.writeBoolean(false); out.writeBoolean(false); out.writeByte(flag); hash?.let(out::writeUTF)
        } }.toByteArray()
        for (bytes in listOf(bad(2), bad(1, "bad"), bad(1, "a".repeat(64)))) {
            val storage = MemoryStorage(bytes); val library = LutLibrary(storage)
            assertEquals(LutLibraryError.CORRUPT, library.states.value.error)
            assertEquals(LutLibrarySelection(available = false), library.activeSelections()); assertArrayEquals(bytes, storage.bytes)
        }
        val storage = MemoryStorage(); val library = LutLibrary(storage); val entry = add(library, "one")
        library.select(entry.hash); library.selectSubject(entry.hash); library.selectRecording(entry.hash)
        library.reset()
        val empty = LutLibrary(storage)
        assertNull(empty.states.value.operatorHash); assertNull(empty.states.value.subjectHash); assertNull(empty.states.value.recordingHash)
        assertEquals(LutLibrarySelection(), empty.activeSelections())
    }
    @Test fun cachedActivationNeverWaitsForAnInProgressDiskCommit() {
        val storage = MemoryStorage(); val library = LutLibrary(storage); val entry = add(library, "one")
        library.select(entry.hash)
        library.selectSubject(entry.hash); library.selectRecording(entry.hash)
        val active = library.active()
        val cachedPair = library.activeSelections()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val read = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        storage.beforeWrite = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val writer = Thread { try { library.select(null) } catch (error: Throwable) { failure.set(error) } }
        val reader = Thread { try { assertSame(active, library.active()); assertSame(active, library.activeSubject()); assertSame(active, library.activeRecording()); assertSame(cachedPair, library.activeSelections()) } catch (error: Throwable) { failure.set(error) } finally { read.countDown() } }
        writer.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            reader.start()
            assertTrue("Cached activation must not acquire the disk transaction lock", read.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); writer.join(10_000); if (reader.state != Thread.State.NEW) reader.join(10_000) }
        assertFalse(writer.isAlive); assertFalse(reader.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertNull(library.active()); assertEquals(entry.hash, library.activeSubject()?.cube?.sha256)
        assertEquals(entry.hash, library.activeRecording()?.cube?.sha256)
    }
    private fun add(library: LutLibrary, name: String) = library.importLut(cube(name), name, LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE)
    private fun cube(name: String): ByteArray = ("# $name\nTITLE \"fixture\"\nLUT_3D_SIZE 2\n" +
        "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n").toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private class MemoryStorage(var bytes: ByteArray? = null) : LutLibraryStorage {
        var failWrites = false
        var beforeWrite: (() -> Unit)? = null
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) { beforeWrite?.invoke(); if (failWrites) throw IOException("Injected before atomic commit"); this.bytes = bytes.copyOf() }
    }
    @Test fun captureAdmissionSnapshotPublishesSelectionAndHealthTogether() {
        val storage = MemoryStorage()
        val library = LutLibrary(storage)
        assertTrue(library.activeSelections().available)
        assertFalse(LutLibrary(MemoryStorage(byteArrayOf(1, 2, 3))).activeSelections().available)
        val entry = library.importLut(cube("snapshot"), "Snapshot", LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE)
        library.selectRecording(entry.hash)
        val selected = library.activeSelections()
        assertTrue(selected.available); assertEquals(entry.hash, selected.recording?.cube?.sha256)
        storage.bytes = byteArrayOf(1, 2, 3)
        assertTrue(runCatching { library.selectRecording(null) }.isFailure)
        val unavailable = library.activeSelections()
        assertFalse(unavailable.available); assertNull(unavailable.recording)
        // A previously admitted immutable transform remains identifiable after later disk failure.
        assertEquals(entry.hash, selected.recording?.cube?.sha256)
        library.reset()
        val reset = library.activeSelections()
        assertTrue(reset.available); assertNull(reset.recording)
    }

}
