/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.system.Os
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Device-local files only: no singleton, account, endpoint request, or existing user settings. */
class WebDavQueueSettingsDeviceTest {
    @Test fun legacyFileKeepsIdentityAndOnlyExplicitLanOptInMigratesTrust() = fixture { _, file ->
        file.parentFile!!.mkdirs()
        val id = "00000000-0000-0000-0000-000000000001"
        val url = "https://192.168.1.20/takes/"
        val legacy = """{"version":1,"revision":7,"enabled":true,"allowCellular":false,"activeEndpointId":"$id","endpoints":[{"id":"$id","url":"$url"}]}""".toByteArray()
        file.writeBytes(legacy)
        val settings = WebDavQueueSettings(file)
        assertFalse(settings.states.value.storageFailed)
        assertFalse(settings.states.value.preferences.activeEndpoint!!.ignoreTlsErrors)
        assertArrayEquals(legacy, file.readBytes()) // Reading never silently edits old configuration.
        val changed = settings.save(url, true, false, ignoreTlsErrors = true)
        assertEquals(id, changed.activeEndpointId)
        assertEquals(8L, changed.revision)
        val reopened = WebDavQueueSettings(file).snapshotForAdmission()
        assertEquals(changed, reopened.preferences)
        assertFalse(reopened.storageFailed)
        assertTrue(reopened.preferences.activeEndpoint!!.ignoreTlsErrors)
        assertTrue(file.readText().startsWith("{\"version\":2,"))
        assertFalse(WebDavQueueSettings(file).save(url, true, false, ignoreTlsErrors = false).activeEndpoint!!.ignoreTlsErrors)
    }

    @Test fun admissionReopensDiskAndRejectsCorruptionInsteadOfUsingCachedEnabledConsent() = fixture { _, file ->
        val store = WebDavQueueSettings(file)
        store.save("https://dav.example/takes/", true, false)
        assertTrue(store.states.value.preferences.enabled)
        val corrupt = "{damaged after settings screen closed".toByteArray()
        file.writeBytes(corrupt)
        val admission = store.snapshotForAdmission()
        assertTrue(admission.storageFailed)
        assertFalse(admission.preferences.enabled)
        assertEquals(admission, store.states.value)
        assertArrayEquals(corrupt, file.readBytes())
        assertThrows(IllegalStateException::class.java) { store.save("https://dav.example/takes/", true, false) }
        assertArrayEquals(corrupt, file.readBytes())
    }

    @Test fun freshReaderWaitsForProcessWriterInsteadOfRecoveringItsUnfinishedAtomicFile() = fixture { _, file ->
        val repository = WebDavQueueSettings(file)
        val old = repository.save("https://dav.example/first/", false, false)
        val next = old.updated("https://dav.example/second/", true, false)
        val lock = requireNotNull(WebDavQueueSettings::class.java.getDeclaredField("processLock").apply { isAccessible = true }.get(null))
        val entered = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<WebDavQueueSettingsState>()
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val reader = Thread {
            entered.countDown()
            try { result.set(WebDavQueueSettings(file).states.value) } catch (failure: Throwable) { error.set(failure) }
        }
        try {
            synchronized(lock) {
                val atomic = AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    stream.write(WebDavQueuePreferencesCodec.encode(next))
                    reader.start()
                    assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                    while (reader.state != Thread.State.BLOCKED && reader.isAlive && android.os.SystemClock.elapsedRealtime() < deadline) Thread.yield()
                    assertEquals(Thread.State.BLOCKED, reader.state)
                    assertTrue(File(file.path + ".new").exists())
                    assertArrayEquals(WebDavQueuePreferencesCodec.encode(old), file.readBytes())
                    atomic.finishWrite(stream)
                } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
            }
        } finally { reader.join(5_000) }
        assertFalse(reader.isAlive); assertNull(error.get())
        assertEquals(WebDavQueueSettingsState(next), result.get())
        assertArrayEquals(WebDavQueuePreferencesCodec.encode(next), file.readBytes())
    }

    @Test fun absentSettingsDefaultOffWithoutCreatingAFileOrConsent() = fixture { _, file ->
        val store = WebDavQueueSettings(file)
        assertEquals(WebDavQueuePreferences(), store.states.value.preferences)
        assertFalse(store.states.value.storageFailed)
        assertFalse(store.states.value.preferences.enabled)
        assertFalse(store.states.value.preferences.allowCellular)
        assertNull(store.states.value.preferences.activeEndpoint)
        store.reload()
        assertFalse(file.exists())
        assertFalse(File(file.path + ".new").exists())
        assertFalse(File(file.path + ".bak").exists())
    }

    @Test fun enabledAndCellularOptInPersistAcrossFreshInstancesAndReload() = fixture { _, file ->
        val store = WebDavQueueSettings(file)
        val first = store.save("https://dav.example/first/", enabled = true, allowCellular = true)
        assertTrue(first.enabled); assertTrue(first.allowCellular)
        assertEquals(1L, first.revision)
        assertEquals(first, WebDavQueueSettings(file).states.value.preferences)
        assertArrayEquals(WebDavQueuePreferencesCodec.encode(first), file.readBytes())
        val other = WebDavQueueSettings(file)
        val disabled = other.save("https://dav.example/first/", enabled = false, allowCellular = false)
        assertEquals(first.activeEndpointId, disabled.activeEndpointId)
        assertEquals(2L, disabled.revision)
        assertTrue("Original snapshot must remain unchanged until reload", store.states.value.preferences.enabled)
        store.reload()
        assertEquals(disabled, store.states.value.preferences)
        assertFalse(store.states.value.storageFailed)
        assertFalse(store.states.value.preferences.enabled)
        assertFalse(store.states.value.preferences.allowCellular)
    }

    @Test fun changingDestinationKeepsOldEndpointAndFrozenTakePreferences() = fixture { _, file ->
        val store = WebDavQueueSettings(file)
        val firstTake = store.save("https://dav.example/first/", true, false)
        val firstEndpoint = requireNotNull(firstTake.activeEndpoint)
        val secondTake = store.save("https://dav.example/second/", true, true)
        assertNotEquals(firstEndpoint.id, secondTake.activeEndpointId)
        assertEquals(listOf(firstEndpoint), firstTake.endpoints)
        assertEquals(firstEndpoint.id, firstTake.activeEndpointId)
        assertFalse(firstTake.allowCellular)
        assertEquals(2, secondTake.endpoints.size)
        assertEquals(firstEndpoint, secondTake.endpoints.single { it.id == firstEndpoint.id })
        val reopened = WebDavQueueSettings(file)
        assertEquals(secondTake, reopened.states.value.preferences)
        val returned = reopened.save("https://dav.example/first/", true, false)
        assertEquals(firstEndpoint.id, returned.activeEndpointId)
        assertEquals(2, returned.endpoints.size)
        assertEquals(3L, returned.revision)
        assertThrows(UnsupportedOperationException::class.java) { (returned.endpoints as MutableList<WebDavQueueEndpoint>).clear() }
        assertEquals(2, WebDavQueueSettings(file).states.value.preferences.endpoints.size)
    }

    @Test fun disablingAndClearingActiveDestinationRetainsHistoricalEndpointIds() = fixture { _, file ->
        val store = WebDavQueueSettings(file)
        val saved = store.save("https://dav.example/takes/", true, false)
        val off = store.save("", false, false)
        assertFalse(off.enabled); assertNull(off.activeEndpointId)
        assertEquals(saved.endpoints, off.endpoints)
        val restored = WebDavQueueSettings(file).save("https://dav.example/takes/", true, false)
        assertEquals(saved.activeEndpointId, restored.activeEndpointId)
        assertEquals(saved.endpoints, restored.endpoints)
    }

    @Test fun identicalSaveReturnsSameSnapshotWithoutRewritingBytesOrTimestamp() = fixture { _, file ->
        val store = WebDavQueueSettings(file)
        val first = store.save("https://dav.example/takes/", true, false)
        val bytes = file.readBytes()
        assertTrue(file.setLastModified(1_234_567_890_000L))
        val modified = file.lastModified()
        assertSame(first, store.save("  https://dav.example/takes/  ", true, false))
        assertEquals(modified, file.lastModified())
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(first.revision, store.states.value.preferences.revision)
    }

    @Test fun invalidEndpointsAndMissingEnabledDestinationNeverOverwriteValidBytes() = fixture { _, file ->
        val store = WebDavQueueSettings(file)
        val valid = store.save("https://dav.example/takes/", true, false)
        val before = file.readBytes()
        for (invalid in listOf("", "http://dav.example/takes/", "https://user:secret@dav.example/takes/",
            "https://dav.example/takes/?token=secret", "https://dav.example/takes/#secret", "https://dav.example:0/",
            "https://dav.example:65536/", "https:///missing-host", "https://dav.example/line\nitem", "https://" + "x".repeat(2048))) {
            assertThrows("Invalid endpoint must fail: $invalid", Exception::class.java) { store.save(invalid, true, true) }
            assertEquals(valid, store.states.value.preferences)
            assertFalse("Input validation is not a storage failure", store.states.value.storageFailed)
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun endpointCapacityAndRevisionOverflowRejectWithoutStorageMutation() = fixture { _, file ->
        val store = WebDavQueueSettings(file)
        repeat(32) { store.save("https://dav.example/destination-$it/", true, false) }
        val full = store.states.value.preferences
        val bytes = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save("https://dav.example/overflow/", true, false) }
        assertEquals(full, store.states.value.preferences); assertFalse(store.states.value.storageFailed)
        assertArrayEquals(bytes, file.readBytes())
        val maxRevision = WebDavQueuePreferences(Long.MAX_VALUE, full.enabled, full.allowCellular, full.activeEndpointId, full.endpoints)
        file.writeBytes(WebDavQueuePreferencesCodec.encode(maxRevision))
        val maximum = WebDavQueueSettings(file)
        assertEquals(Long.MAX_VALUE, maximum.states.value.preferences.revision)
        val maxBytes = file.readBytes()
        assertThrows(ArithmeticException::class.java) { maximum.save(requireNotNull(maxRevision.activeEndpoint).url, false, false) }
        assertFalse(maximum.states.value.storageFailed)
        assertArrayEquals(maxBytes, file.readBytes())
    }

    @Test fun corruptionFutureSchemaInvalidUtf8AndOversizeFailClosedAndPreserveEvidence() = fixture { _, file ->
        assertTrue(file.parentFile!!.mkdirs())
        val canonical = String(WebDavQueuePreferencesCodec.encode(WebDavQueuePreferences()), Charsets.UTF_8)
        val corruptions = listOf("broken".toByteArray(), canonical.replace("\"version\":2", "\"version\":3").toByteArray(),
            canonical.replace("\"revision\":0", "\"revision\":\"0\"").toByteArray(),
            canonical.replace("\"enabled\":false", "\"enabled\":\"false\"").toByteArray(),
            canonical.replace("\"version\":2", "\"unknown\":1,\"version\":2").toByteArray(),
            byteArrayOf(0xC3.toByte(), 0x28), ByteArray(WebDavQueuePreferencesCodec.MAX_BYTES + 1))
        for (bytes in corruptions) {
            file.writeBytes(bytes)
            val store = WebDavQueueSettings(file)
            assertTrue(store.states.value.storageFailed)
            assertFalse(store.states.value.preferences.enabled)
            assertFalse(store.states.value.preferences.allowCellular)
            assertNull(store.states.value.preferences.activeEndpoint)
            assertThrows(IllegalStateException::class.java) { store.save("https://dav.example/replacement/", true, true) }
            store.reload()
            assertTrue(store.states.value.storageFailed)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun partialFirstAtomicWriteIsPreservedAndDoesNotCreateConsent() = fixture { _, file ->
        assertTrue(file.parentFile!!.mkdirs())
        AtomicFile(file).startWrite().use { it.write("{partial".toByteArray()); it.fd.sync() }
        val unfinished = File(file.path + ".new")
        assertTrue(unfinished.isFile); assertFalse(file.exists())
        val bytes = unfinished.readBytes()
        val store = WebDavQueueSettings(file)
        assertTrue(store.states.value.storageFailed)
        assertFalse(store.states.value.preferences.enabled)
        assertThrows(IllegalStateException::class.java) { store.save("https://dav.example/replacement/", true, false) }
        assertArrayEquals(bytes, unfinished.readBytes())
        assertFalse(file.exists())
    }

    @Test fun occupiedParentReportsStorageFailureWithoutReplacingForeignBytes() = fixture { root, _ ->
        val parent = File(root, "occupied").apply { writeText("do not replace") }
        val store = WebDavQueueSettings(File(parent, "queue.json"))
        assertThrows(Exception::class.java) { store.save("https://dav.example/takes/", true, false) }
        assertTrue(store.states.value.storageFailed)
        assertFalse(store.states.value.preferences.enabled)
        assertEquals("do not replace", parent.readText())
        assertThrows(IllegalStateException::class.java) { store.save("https://dav.example/other/", true, true) }
        assertEquals("do not replace", parent.readText())
    }

    @Test fun actualReadOnlyDirectoryRejectsWriteAndReloadRestoresLastCommittedPreferences() = fixture { _, file ->
        val store = WebDavQueueSettings(file)
        val original = store.save("https://dav.example/original/", true, false)
        val bytes = file.readBytes()
        val parent = requireNotNull(file.parentFile)
        try {
            Os.chmod(parent.path, 0b101000000) // Owner read/execute only, no write permission.
            assertFalse("Fixture must enforce a real write denial", parent.canWrite())
            assertThrows(Exception::class.java) { store.save("https://dav.example/rejected/", true, true) }
            assertTrue(store.states.value.storageFailed)
            assertEquals(original, store.states.value.preferences)
            assertArrayEquals(bytes, file.readBytes())
        } finally { Os.chmod(parent.path, 0b111000000) }
        store.reload()
        assertFalse(store.states.value.storageFailed)
        assertEquals(original, store.states.value.preferences)
        val next = store.save("https://dav.example/after-recovery/", true, false)
        assertEquals(2, next.endpoints.size)
        assertEquals(original.activeEndpoint, next.endpoints.single { it.id == original.activeEndpointId })
    }

    @Test fun staleInstanceCannotOverwriteCorruptionIntroducedAfterItsValidLoad() = fixture { _, file ->
        val store = WebDavQueueSettings(file)
        store.save("https://dav.example/original/", true, false)
        val corrupt = "retain corrupt evidence".toByteArray()
        file.writeBytes(corrupt)
        assertThrows(Exception::class.java) { store.save("https://dav.example/replacement/", true, true) }
        assertTrue(store.states.value.storageFailed)
        assertArrayEquals(corrupt, file.readBytes())
        assertThrows(IllegalStateException::class.java) { store.save("https://dav.example/another/", true, false) }
        assertArrayEquals(corrupt, file.readBytes())
    }

    @Test fun staleInstanceCannotDropEndpointSavedByAnotherFreshInstance() = fixture { _, file ->
        val first = WebDavQueueSettings(file)
        first.save("https://dav.example/original/", true, false)
        val stale = WebDavQueueSettings(file)
        val committed = first.save("https://dav.example/new-endpoint/", true, true)
        val bytes = file.readBytes()
        assertThrows(Exception::class.java) { stale.save("https://dav.example/stale-replacement/", true, false) }
        assertTrue(stale.states.value.storageFailed)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(committed, WebDavQueueSettings(file).states.value.preferences)
        stale.reload()
        assertFalse(stale.states.value.storageFailed)
        val next = stale.save("https://dav.example/after-reload/", true, false)
        assertEquals(3, next.endpoints.size)
        assertTrue(next.endpoints.containsAll(committed.endpoints))
    }

    private fun fixture(block: (File, File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "queue-settings-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        try { block(root, File(root, "private/settings.json")) }
        finally { assertTrue("Only this test directory must be removed", root.deleteRecursively()) }
    }
}
