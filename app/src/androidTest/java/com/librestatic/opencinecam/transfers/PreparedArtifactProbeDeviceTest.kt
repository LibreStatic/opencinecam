/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import java.io.FileDescriptor
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Real MediaStore descriptors; provider wrappers inject deterministic observation races only. */
class PreparedArtifactProbeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    @Test fun allFourRolesAreObservedPendingAndPublishedWithoutWritesOrHashClaims() = withRows { rows ->
        for (role in CaptureArtifactRole.entries) {
            val item = create(rows, role)
            val wrapper = ForwardingProvider()
            val probe = MediaStorePreparedArtifactProbe(wrapper.resolver())
            val artifact = artifact(item)
            val pending = probe.inspect(artifact, pending = true)
            assertFalse(pending.finalized)
            assertEquals(item.uri.toString(), pending.identity)
            assertEquals(artifact.displayName, pending.displayName)
            assertEquals(128L, pending.sizeBytes)
            publish(item)
            val published = probe.inspect(artifact(item), pending = false)
            assertTrue(published.finalized)
            assertEquals(pending.sizeBytes, published.sizeBytes)
            assertEquals(0, wrapper.writes.get())
            wrapper.assertAllClosed()
        }
        android.util.Log.i("PreparedArtifactProbe", "roles=4 pendingAndPublished=true statBytes=128 ownReadDescriptorsClosed=true providerWrites=0 noHashClaim=true")
    }

    @Test fun pendingNullOrStaleQuerySizeUsesDescriptorButPublishedMismatchIsRejected() = withRows { rows ->
        val item = create(rows, CaptureArtifactRole.VIDEO)
        for (stale in listOf(null, 0L, 12L, Long.MAX_VALUE)) {
            val wrapper = ForwardingProvider(transform = { cursor -> copyCursor(cursor, mapOf(MediaStore.MediaColumns.SIZE to stale)) })
            assertEquals(128L, MediaStorePreparedArtifactProbe(wrapper.resolver()).inspect(artifact(item), true).sizeBytes)
            wrapper.assertAllClosed()
        }
        publish(item)
        val wrapper = ForwardingProvider(transform = { cursor -> copyCursor(cursor, mapOf(MediaStore.MediaColumns.SIZE to 12L)) })
        assertThrows(IllegalStateException::class.java) { MediaStorePreparedArtifactProbe(wrapper.resolver()).inspect(artifact(item), false) }
        wrapper.assertAllClosed()
    }

    @Test fun actualProviderRenameRejectsOldNameRatherThanRebindingIdentity() = withRows { rows ->
        val item = create(rows, CaptureArtifactRole.VIDEO)
        val original = artifact(item)
        val requested = "renamed-${System.nanoTime()}.mp4"
        assertEquals(1, resolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, requested) }, null, null))
        val renamed = artifact(item)
        assertNotEquals(original.displayName, renamed.displayName)
        val probe = MediaStorePreparedArtifactProbe(resolver)
        assertThrows(IllegalStateException::class.java) { probe.inspect(original, true) }
        assertEquals(renamed.displayName, probe.inspect(renamed, true).displayName)
        assertEquals(original.uri, renamed.uri)
    }

    @Test fun collidingRequestedNamesAlwaysUseEachActualProviderIdentity() = withRows { rows ->
        val requested = "collision-${System.nanoTime()}.mp4"
        val first = create(rows, CaptureArtifactRole.VIDEO, name = requested)
        publish(first)
        val second = create(rows, CaptureArtifactRole.VIDEO, name = requested)
        assertNotEquals(first.uri, second.uri)
        val probe = MediaStorePreparedArtifactProbe(resolver)
        val firstArtifact = artifact(first)
        val secondArtifact = artifact(second)
        assertEquals(firstArtifact.displayName, probe.inspect(firstArtifact, false).displayName)
        assertEquals(secondArtifact.displayName, probe.inspect(secondArtifact, true).displayName)
        publish(second)
        val publishedSecond = artifact(second)
        assertEquals(publishedSecond.displayName, probe.inspect(publishedSecond, false).displayName)
        if (publishedSecond.displayName != secondArtifact.displayName) {
            assertThrows(IllegalStateException::class.java) { probe.inspect(secondArtifact, false) }
        }
        android.util.Log.i("PreparedArtifactProbe", "collisionRequested=$requested firstActual=${firstArtifact.displayName} secondPrepared=${secondArtifact.displayName} secondPublished=${publishedSecond.displayName} distinctUris=true")
    }

    @Test fun emptyMissingAndWrongPublicationStateNeverProduceUsableSnapshots() = withRows { rows ->
        val empty = create(rows, CaptureArtifactRole.VIDEO, byteCount = 0)
        val emptyWrapper = ForwardingProvider()
        assertThrows(IllegalStateException::class.java) { MediaStorePreparedArtifactProbe(emptyWrapper.resolver()).inspect(artifact(empty), true) }
        emptyWrapper.assertAllClosed()
        val item = create(rows, CaptureArtifactRole.VIDEO)
        val captured = artifact(item)
        val probe = MediaStorePreparedArtifactProbe(resolver)
        assertThrows(IllegalStateException::class.java) { probe.inspect(captured, false) }
        publish(item)
        assertThrows(IllegalStateException::class.java) { probe.inspect(artifact(item), true) }
        assertEquals(1, resolver.delete(item.uri, null, null))
        assertThrows(Exception::class.java) { probe.inspect(captured, false) }
    }

    @Test fun noncanonicalUrisNamesAndWrongRoleRejectBeforeAnyProviderCall() {
        val wrapper = ForwardingProvider()
        val probe = MediaStorePreparedArtifactProbe(wrapper.resolver())
        val base = "content://media/external/video/media/1"
        val invalidUris = listOf("$base?", "$base#x", "$base/", "content://media/external/video/media/01",
            "content://media/external/video/media/+1", "content://media/external/video/media/0",
            "content://media/external/video/media/9223372036854775808", "content://media/external/video/media/%31",
            "content://other/external/video/media/1", "file:///external/video/media/1", "content://media/external/../video/media/1")
        for (uri in invalidUris) assertThrows(uri, IllegalArgumentException::class.java) {
            probe.inspect(PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, uri, "fixture.mp4"), true)
        }
        for (name in listOf("", ".", "..", "a/b.mp4", "a\\b.mp4", "line\n.mp4", "x".repeat(256), "bad\uD800.mp4")) {
            assertThrows(IllegalArgumentException::class.java) { probe.inspect(PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, base, name), true) }
        }
        assertThrows(IllegalArgumentException::class.java) { probe.inspect(PreparedCaptureArtifact(CaptureArtifactRole.AUDIO, base, "fixture.wav"), true) }
        assertEquals(0, wrapper.queries.get()); assertEquals(0, wrapper.opens.get()); assertEquals(0, wrapper.writes.get())
    }

    @Test fun numericStringsFloatsOverflowNullAndDuplicateRowsAreRejectedWithoutCoercion() = withRows { rows ->
        val item = create(rows, CaptureArtifactRole.VIDEO)
        val bad = listOf(
            MediaStore.MediaColumns.SIZE to "128", MediaStore.MediaColumns.SIZE to 128.0,
            MediaStore.MediaColumns.SIZE to -1L, MediaStore.MediaColumns.SIZE to BigInteger("9223372036854775808"),
            MediaStore.MediaColumns.DATE_MODIFIED to "1", MediaStore.MediaColumns.DATE_MODIFIED to 1.0,
            MediaStore.MediaColumns.IS_PENDING to "1", MediaStore.MediaColumns.IS_PENDING to 2L,
            MediaStore.MediaColumns._ID to "${ContentUris.parseId(item.uri)}",
        )
        for ((column, value) in bad) {
            val wrapper = ForwardingProvider(transform = { cursor -> copyCursor(cursor, mapOf(column to value)) })
            assertThrows("$column=$value", Exception::class.java) { MediaStorePreparedArtifactProbe(wrapper.resolver()).inspect(artifact(item), true) }
            assertEquals(0, wrapper.opens.get())
        }
        val duplicate = ForwardingProvider(transform = { cursor -> copyCursor(cursor, duplicate = true) })
        assertThrows(Exception::class.java) { MediaStorePreparedArtifactProbe(duplicate.resolver()).inspect(artifact(item), true) }
        assertEquals(0, duplicate.opens.get())
    }

    @Test fun pendingNullModificationTimeUsesStableDescriptorButPublishedNullIsRejected() = withRows { rows ->
        val item = create(rows, CaptureArtifactRole.VIDEO)
        val expected = requireNotNull(resolver.openFileDescriptor(item.uri, "r")).use { Os.fstat(it.fileDescriptor).st_mtim.tv_sec }
        val wrapper = ForwardingProvider(transform = { copyCursor(it, mapOf(MediaStore.MediaColumns.DATE_MODIFIED to null)) })
        val observed = MediaStorePreparedArtifactProbe(wrapper.resolver()).inspect(artifact(item), true)
        assertEquals(expected, observed.modifiedSeconds)
        wrapper.assertAllClosed()
        requireNotNull(resolver.query(includePending(item.uri), arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)).use {
            assertTrue(it.moveToFirst())
            android.util.Log.i("PreparedArtifactProbe", "pendingDateModifiedType=${it.getType(0)} null=${it.isNull(0)} descriptorMtime=$expected")
        }
        publish(item)
        val publishedWrapper = ForwardingProvider(transform = { copyCursor(it, mapOf(MediaStore.MediaColumns.DATE_MODIFIED to null)) })
        assertThrows(IllegalStateException::class.java) { MediaStorePreparedArtifactProbe(publishedWrapper.resolver()).inspect(artifact(item), false) }
        assertEquals(0, publishedWrapper.opens.get())
    }

    @Test fun renameDuringOpenIsDetectedAndTheOwnedReadDescriptorCloses() = withRows { rows ->
        val item = create(rows, CaptureArtifactRole.VIDEO)
        val original = artifact(item)
        val wrapper = ForwardingProvider(afterOpen = {
            assertEquals(1, resolver.update(item.uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "changed-${System.nanoTime()}.mp4")
            }, null, null))
        })
        assertThrows(IllegalStateException::class.java) { MediaStorePreparedArtifactProbe(wrapper.resolver()).inspect(original, true) }
        assertEquals(1, wrapper.opens.get())
        wrapper.assertAllClosed()
    }

    @Test fun byteGrowthBetweenDescriptorStatsIsDetectedEvenWithTheOldCursorSnapshot() = withRows { rows ->
        val item = create(rows, CaptureArtifactRole.VIDEO)
        val wrapper = ForwardingProvider(afterSecondQuery = {
            requireNotNull(resolver.openOutputStream(item.uri, "wa")).use { it.write(ByteArray(17)) }
        })
        assertThrows(IllegalStateException::class.java) { MediaStorePreparedArtifactProbe(wrapper.resolver()).inspect(artifact(item), true) }
        assertEquals(1, wrapper.opens.get())
        wrapper.assertAllClosed()
    }

    @Test fun descriptorMustBeReadOnlyAndClosesWhenProviderViolatesRequestedMode() = withRows { rows ->
        val item = create(rows, CaptureArtifactRole.VIDEO)
        val wrapper = ForwardingProvider(actualOpenMode = "rw")
        assertThrows(IllegalStateException::class.java) { MediaStorePreparedArtifactProbe(wrapper.resolver()).inspect(artifact(item), true) }
        assertEquals(listOf("r"), wrapper.requestedModes)
        wrapper.assertAllClosed()
    }

    @Test fun deniedDescriptorAccessPropagatesTheOriginalFailureWithoutASnapshot() = withRows { rows ->
        val item = create(rows, CaptureArtifactRole.VIDEO)
        val denied = SecurityException("Injected descriptor access denial")
        val wrapper = ForwardingProvider(openFailure = denied)
        val actual = assertThrows(SecurityException::class.java) {
            MediaStorePreparedArtifactProbe(wrapper.resolver()).inspect(artifact(item), true)
        }
        assertSame(denied, actual)
        assertEquals(1, wrapper.opens.get()); assertEquals(0, wrapper.writes.get())
    }

    @Test fun mimeAndExtensionMustMatchThePreparedRole() = withRows { rows ->
        val item = create(rows, CaptureArtifactRole.VIDEO)
        val captured = artifact(item)
        for (mime in listOf("audio/wav", "application/json", "application/octet-stream", null)) {
            val wrapper = ForwardingProvider(transform = { cursor -> copyCursor(cursor, mapOf(MediaStore.MediaColumns.MIME_TYPE to mime)) })
            assertThrows(Exception::class.java) { MediaStorePreparedArtifactProbe(wrapper.resolver()).inspect(captured, true) }
            assertEquals(0, wrapper.opens.get())
        }
        val jsonName = "wrong-${System.nanoTime()}.json"
        val wrapper = ForwardingProvider(transform = { cursor -> copyCursor(cursor, mapOf(MediaStore.MediaColumns.DISPLAY_NAME to jsonName)) })
        assertThrows(IllegalStateException::class.java) { MediaStorePreparedArtifactProbe(wrapper.resolver()).inspect(captured.copy(displayName = jsonName), true) }
        assertEquals(0, wrapper.opens.get())
    }

    @Test fun sparseDescriptorLargerThanIntMaxRetainsItsActual64BitLength() = withRows { rows ->
        val item = create(rows, CaptureArtifactRole.VIDEO)
        val size = Int.MAX_VALUE.toLong() + 4097L
        requireNotNull(resolver.openFileDescriptor(item.uri, "rw")).use { Os.ftruncate(it.fileDescriptor, size) }
        val wrapper = ForwardingProvider(transform = { cursor -> copyCursor(cursor, mapOf(MediaStore.MediaColumns.SIZE to 0L)) })
        val snapshot = MediaStorePreparedArtifactProbe(wrapper.resolver()).inspect(artifact(item), true)
        assertEquals(size, snapshot.sizeBytes)
        assertFalse(snapshot.finalized)
        wrapper.assertAllClosed()
        android.util.Log.i("PreparedArtifactProbe", "sparseDescriptorBytes=$size querySizeIgnored=0 actual64bit=true noBytesReadOrHashed=true")
    }

    private data class Item(val uri: Uri, val role: CaptureArtifactRole)

    private fun withRows(block: (MutableList<Item>) -> Unit) {
        val rows = mutableListOf<Item>()
        try { block(rows) } finally {
            var failure: Throwable? = null
            for (item in rows) try {
                val exists = requireNotNull(resolver.query(includePending(collection(item.role)), arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns._ID} = ?", arrayOf(ContentUris.parseId(item.uri).toString()), null)).use { it.moveToFirst() }
                if (exists) assertEquals(1, resolver.delete(item.uri, null, null))
            } catch (problem: Throwable) {
                val first = failure
                if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
            }
            failure?.let { throw it }
        }
    }

    private fun create(rows: MutableList<Item>, role: CaptureArtifactRole, name: String? = null, byteCount: Int = 128): Item {
        val extension = when (role) { CaptureArtifactRole.VIDEO -> "mp4"; CaptureArtifactRole.AUDIO -> "wav"; else -> "json" }
        val mime = when (role) { CaptureArtifactRole.VIDEO -> "video/mp4"; CaptureArtifactRole.AUDIO -> "audio/wav"; else -> "application/json" }
        val path = when (role) { CaptureArtifactRole.VIDEO -> "Movies"; CaptureArtifactRole.AUDIO -> "Music"; else -> "Download" }
        val uri = requireNotNull(resolver.insert(collection(role), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name ?: "Probe-${role.name}-${System.nanoTime()}.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$path/OpenCineCamTests")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val item = Item(uri, role).also { rows += it }
        requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(ByteArray(byteCount) { index -> index.toByte() }) }
        return item
    }

    private fun artifact(item: Item): PreparedCaptureArtifact {
        val name = requireNotNull(resolver.query(includePending(item.uri), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null))
            .use { assertTrue(it.moveToFirst()); it.getString(0) }
        return PreparedCaptureArtifact(item.role, item.uri.toString(), name)
    }

    private fun publish(item: Item) { assertEquals(1, resolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)) }

    private fun collection(role: CaptureArtifactRole): Uri = when (role) {
        CaptureArtifactRole.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        CaptureArtifactRole.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }

    @Suppress("DEPRECATION")
    private fun includePending(uri: Uri): Uri = MediaStore.setIncludePending(uri)

    private fun copyCursor(source: Cursor, changes: Map<String, Any?> = emptyMap(), duplicate: Boolean = false): Cursor = source.use {
        val result = MatrixCursor(it.columnNames)
        while (it.moveToNext()) {
            val values = Array<Any?>(it.columnCount) { column ->
                val name = it.getColumnName(column)
                if (changes.containsKey(name)) changes[name] else when (it.getType(column)) {
                    Cursor.FIELD_TYPE_INTEGER -> it.getLong(column)
                    Cursor.FIELD_TYPE_FLOAT -> it.getDouble(column)
                    Cursor.FIELD_TYPE_STRING -> it.getString(column)
                    Cursor.FIELD_TYPE_BLOB -> it.getBlob(column)
                    else -> null
                }
            }
            result.addRow(values)
            if (duplicate) result.addRow(values)
        }
        result
    }

    private inner class ForwardingProvider(
        private val transform: ((Cursor) -> Cursor)? = null,
        private val afterOpen: (() -> Unit)? = null,
        private val afterSecondQuery: (() -> Unit)? = null,
        private val actualOpenMode: String = "r",
        private val openFailure: Exception? = null,
    ) : ContentProvider() {
        val queries = AtomicInteger()
        val opens = AtomicInteger()
        val writes = AtomicInteger()
        val requestedModes = mutableListOf<String>()
        private val descriptors = mutableListOf<FileDescriptor>()
        fun resolver(): ContentResolver {
            attachInfo(this@PreparedArtifactProbeDeviceTest.context, ProviderInfo().apply { authority = "media"; exported = false })
            return ContentResolver.wrap(this)
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            val count = queries.incrementAndGet()
            val cursor = requireNotNull(this@PreparedArtifactProbeDeviceTest.resolver.query(uri, projection, selection, selectionArgs, sortOrder))
            // Materialize the real cursor before the injected mutation: only the descriptor
            // stat, not a refreshed metadata size, can detect the second-query byte growth.
            val result = transform?.invoke(cursor) ?: copyCursor(cursor)
            if (count == 2) afterSecondQuery?.invoke()
            return result
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens.incrementAndGet(); requestedModes += mode
            openFailure?.let { throw it }
            val descriptor = requireNotNull(this@PreparedArtifactProbeDeviceTest.resolver.openFileDescriptor(uri, actualOpenMode))
            descriptors += descriptor.fileDescriptor
            try { afterOpen?.invoke() } catch (failure: Throwable) { descriptor.close(); throw failure }
            return descriptor
        }
        override fun getType(uri: Uri): String? = this@PreparedArtifactProbeDeviceTest.resolver.getType(uri)
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes.incrementAndGet(); error("Probe attempted provider insert") }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { writes.incrementAndGet(); error("Probe attempted provider delete") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { writes.incrementAndGet(); error("Probe attempted provider update") }
        fun assertAllClosed() {
            assertTrue("At least one owned descriptor must have opened", descriptors.isNotEmpty())
            assertTrue("Probe leaked an owned descriptor", descriptors.none { it.valid() })
            assertEquals(0, writes.get())
        }
    }
}
