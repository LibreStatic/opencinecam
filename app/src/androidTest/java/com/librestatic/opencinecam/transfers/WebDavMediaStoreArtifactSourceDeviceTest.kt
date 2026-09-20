/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileDescriptor
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real MediaStore rows/readonly descriptors; wrappers inject deterministic access failures only. */
class WebDavMediaStoreArtifactSourceDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun actualPublishedBytesForAllFourRolesHashAndCloseOnlyOwnedReadonlyDescriptors() = rows { owned ->
        for (role in WebDavArtifactRole.entries) {
            val spec = create(owned, role)
            val wrapper = ForwardingProvider()
            val factory = WebDavMediaStoreArtifactSource(wrapper.resolver())
            val result = hash(factory, bound(factory, spec)) as WebDavHashResult.Hashed
            assertEquals(abcHash, result.sha256)
            assertEquals(spec.sourceUri, result.publication.source.identity)
            assertEquals(spec.sourceName, result.publication.source.displayName)
            assertEquals(3L, result.publication.source.sizeBytes)
            assertTrue(result.publication.source.finalized)
            wrapper.assertClosed()
            assertTrue(wrapper.modes.all { it == "r" })
            assertEquals(0, wrapper.writes)
            assertEquals("abc", resolver.openInputStream(Uri.parse(spec.sourceUri))!!.bufferedReader().use { it.readText() })
        }
    }

    @Test fun pendingWrongRoleWrongNameMimeAndSealedSizeDoNotBecomeHashes() = rows { owned ->
        val spec = create(owned, WebDavArtifactRole.VIDEO_METADATA)
        val factory = WebDavMediaStoreArtifactSource(resolver)
        val bound = bound(factory, spec)
        for (bad in listOf(bound.copy(spec = spec.copy(role = WebDavArtifactRole.VIDEO)),
            bound.copy(spec = spec.copy(sourceName = "renamed.json")), bound.copy(spec = spec.copy(sizeBytes = 4)))) {
            assertUnavailable(WebDavSourceFailureReason.CONTENT_CHANGED, hash(factory, bad))
        }
        assertEquals(1, resolver.update(Uri.parse(spec.sourceUri), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null))
        assertUnavailable(WebDavSourceFailureReason.CONTENT_CHANGED, hash(factory, bound))
        assertEquals(1, resolver.update(Uri.parse(spec.sourceUri), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0); put(MediaStore.MediaColumns.MIME_TYPE, "text/plain") }, null, null))
        assertUnavailable(WebDavSourceFailureReason.CONTENT_CHANGED, hash(factory, bound))
    }

    @Test fun deletionBeforeHashIsMissingAndNeverRecreatesTheMediaRow() = rows { owned ->
        val spec = create(owned, WebDavArtifactRole.VIDEO_METADATA)
        val factory = WebDavMediaStoreArtifactSource(resolver)
        val bound = bound(factory, spec)
        assertEquals(1, resolver.delete(Uri.parse(spec.sourceUri), null, null))
        owned.remove(Uri.parse(spec.sourceUri))
        assertUnavailable(WebDavSourceFailureReason.MISSING, hash(factory, bound))
    }

    @Test fun accessRevokedAtDescriptorOpenReturnsDeniedAndKeepsPublishedBytes() = rows { owned ->
        val spec = create(owned, WebDavArtifactRole.VIDEO_METADATA)
        val bound = bound(WebDavMediaStoreArtifactSource(resolver), spec)
        val wrapper = ForwardingProvider(denyFromOpen = 1)
        assertUnavailable(WebDavSourceFailureReason.ACCESS_DENIED, hash(WebDavMediaStoreArtifactSource(wrapper.resolver()), bound))
        assertTrue(wrapper.descriptors.isEmpty())
        assertEquals("abc", resolver.openInputStream(Uri.parse(spec.sourceUri))!!.bufferedReader().use { it.readText() })
    }

    @Test fun accessRevokedDuringFinalValidationStillClosesTheAlreadyOwnedReadDescriptor() = rows { owned ->
        val spec = create(owned, WebDavArtifactRole.VIDEO_METADATA, body = "123")
        awaitOwnJsonScan(spec)
        val wrapper = ForwardingProvider(diagnostics = true)
        val source = WebDavMediaStoreArtifactSource(wrapper.resolver()).source(spec)
        val input = source.open() // Exactly one tested open, after acknowledged fixture preparation.
        assertEquals('1'.code, input.read())
        wrapper.denyFrom = wrapper.opens + 1
        assertThrows(SecurityException::class.java) { input.close() }
        input.close() // Failed validation has already closed the descriptor; no second provider I/O.
        wrapper.assertClosed()
        assertEquals(0, wrapper.writes)
    }

    @Test fun liveGrowthWhileStreamingIsDetectedByOwnedDescriptorStatAndClosesIt() = rows { owned ->
        val spec = create(owned, WebDavArtifactRole.VIDEO_METADATA)
        val wrapper = ForwardingProvider()
        val source = WebDavMediaStoreArtifactSource(wrapper.resolver()).source(spec)
        val input = source.open()
        assertEquals('a'.code, input.read())
        resolver.openOutputStream(Uri.parse(spec.sourceUri), "wa")!!.use { it.write('d'.code) }
        assertThrows(WebDavArtifactContentChanged::class.java) { input.close() }
        wrapper.assertClosed()
    }

    @Test fun liveRowDeletionWhileStreamingIsDetectedWithoutClosingAnyForeignWriter() = rows { owned ->
        val spec = create(owned, WebDavArtifactRole.VIDEO_METADATA)
        val wrapper = ForwardingProvider()
        val source = WebDavMediaStoreArtifactSource(wrapper.resolver()).source(spec)
        val input = source.open()
        assertEquals('a'.code, input.read())
        assertEquals(1, resolver.delete(Uri.parse(spec.sourceUri), null, null))
        owned.remove(Uri.parse(spec.sourceUri))
        assertThrows(Exception::class.java) { input.close() }
        wrapper.assertClosed()
    }

    @Test fun sameSizeAndSealedTimestampMutationStillFailsTheFixedSha256() = rows { owned ->
        val spec = create(owned, WebDavArtifactRole.VIDEO_METADATA)
        val initialFactory = WebDavMediaStoreArtifactSource(resolver)
        val initial = bound(initialFactory, spec)
        val fixed = initial.copy(sha256 = (hash(initialFactory, initial) as WebDavHashResult.Hashed).sha256)
        resolver.openOutputStream(Uri.parse(spec.sourceUri), "wt")!!.use { it.write("abd".toByteArray()) }
        // Preserve the original sealed metadata observation deliberately. Detection must come from
        // the actual fresh bytes, not from a conveniently different seconds-resolution timestamp.
        val wrapper = ForwardingProvider(modifiedOverride = fixed.modifiedSeconds)
        val factory = WebDavMediaStoreArtifactSource(wrapper.resolver())
        assertEquals(fixed.modifiedSeconds, factory.source(spec).snapshot().modifiedSeconds)
        assertEquals(3L, factory.source(spec).snapshot().sizeBytes)
        assertUnavailable(WebDavSourceFailureReason.CONTENT_CHANGED, hash(factory, fixed))
        wrapper.assertClosed()
    }

    @Test fun providerReturningWritableDescriptorIsRejectedAndThatOwnedDescriptorIsClosed() = rows { owned ->
        val spec = create(owned, WebDavArtifactRole.VIDEO_METADATA)
        val initial = bound(WebDavMediaStoreArtifactSource(resolver), spec)
        val wrapper = ForwardingProvider(mode = "rw")
        assertUnavailable(WebDavSourceFailureReason.CONTENT_CHANGED, hash(WebDavMediaStoreArtifactSource(wrapper.resolver()), initial))
        wrapper.assertClosed()
        assertTrue(wrapper.modes.all { it == "r" })
        assertEquals("abc", resolver.openInputStream(Uri.parse(spec.sourceUri))!!.bufferedReader().use { it.readText() })
    }

    @Test fun cancellationAfterSourceOpenRetainsTheOwnerUntilAllDescriptorsHaveClosed() = rows { owned ->
        val spec = create(owned, WebDavArtifactRole.VIDEO_METADATA)
        val wrapper = ForwardingProvider()
        val strict = WebDavMediaStoreArtifactSource(wrapper.resolver())
        val bound = bound(strict, spec)
        val control = WebDavUploadControl(WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))
        val attempt = requireNotNull(control.enter().attempt)
        var receipt: WebDavRetirementReceipt? = null
        val factory = WebDavArtifactSourceFactory { requested ->
            val source = strict.source(requested)
            object : WebDavClipSource {
                override fun snapshot() = source.snapshot()
                override fun open() = source.open().also {
                    receipt = control.pauseForRecording()
                    assertFalse(receipt!!.isRetired)
                }
            }
        }
        try {
            assertEquals(WebDavHashResult.Stopped(WebDavStopReason.RECORDING), DefaultWebDavArtifactHasher(factory).hash(bound, WebDavOperationScope(attempt)))
            wrapper.assertClosed()
            assertFalse(receipt!!.isRetired)
        } finally { control.leave(attempt) }
        assertTrue(receipt!!.isRetired)
    }

    private fun bound(factory: WebDavArtifactSourceFactory, spec: WebDavArtifactSpec) =
        WebDavOutboxArtifact(spec, modifiedSeconds = factory.source(spec).snapshot().modifiedSeconds)

    private fun hash(factory: WebDavArtifactSourceFactory, artifact: WebDavOutboxArtifact): WebDavHashResult {
        val control = WebDavUploadControl(WebDavUploadPolicy(enabled = true, network = WebDavNetwork.WIFI))
        val attempt = requireNotNull(control.enter().attempt)
        return try { DefaultWebDavArtifactHasher(factory).hash(artifact, WebDavOperationScope(attempt)) }
        finally { control.leave(attempt) }
    }

    private fun assertUnavailable(reason: WebDavSourceFailureReason, result: WebDavHashResult) {
        assertTrue(result.toString(), result is WebDavHashResult.Unavailable)
        assertEquals(reason, (result as WebDavHashResult.Unavailable).failure.reason)
    }

    private fun create(owned: MutableList<Uri>, role: WebDavArtifactRole, body: String = "abc"): WebDavArtifactSpec {
        val (collection, extension, mime) = when (role) {
            WebDavArtifactRole.VIDEO -> Triple(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "mp4", "video/mp4")
            WebDavArtifactRole.AUDIO -> Triple(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "wav", "audio/wav")
            else -> Triple(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "json", "application/json")
        }
        val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "artifact-hash-${UUID.randomUUID()}.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        owned += uri
        resolver.openOutputStream(uri, "w")!!.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
        val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        return WebDavArtifactSpec(UUID.randomUUID().toString(), role, uri.toString(), name, body.toByteArray(Charsets.UTF_8).size.toLong())
    }

    /** Explicit scan completion, not a sleep or a retry of the operation under test. */
    @Suppress("DEPRECATION") // DATA is queried only for the newly created row owned by this fixture.
    private fun awaitOwnJsonScan(spec: WebDavArtifactSpec) {
        val originalUri = Uri.parse(spec.sourceUri)
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DATA)
        data class Row(val id: Long, val volume: String, val name: String, val size: Long,
            val pending: Long, val path: String)
        fun row(uri: Uri): Row = requireNotNull(resolver.query(uri, columns, null, null, null)).use {
            assertEquals(1, it.count)
            assertTrue(it.moveToFirst())
            Row(it.getLong(0), requireNotNull(it.getString(1)), requireNotNull(it.getString(2)),
                it.getLong(3), it.getLong(4), requireNotNull(it.getString(5)))
        }
        val before = row(originalUri)
        assertEquals(ContentUris.parseId(originalUri), before.id)
        assertEquals("external_primary", before.volume)
        assertEquals(spec.sourceName, before.name)
        assertEquals(spec.sizeBytes, before.size)
        assertEquals(0L, before.pending)
        assertTrue(java.io.File(before.path).isAbsolute)
        val completion = CompletableFuture<Pair<String, Uri?>>()
        MediaScannerConnection.scanFile(context, arrayOf(before.path), arrayOf("application/json")) { path, uri ->
            // Assertions stay on the instrumentation thread: scanner callback exceptions must not
            // be swallowed by framework callback dispatch and mistaken for completed preparation.
            completion.complete(path to uri)
        }
        val (scannedPath, callbackUri) = completion.get(15, TimeUnit.SECONDS)
        assertEquals(before.path, scannedPath)
        val returned = requireNotNull(callbackUri) { "The fixture scan did not publish an identity" }
        assertEquals("content", returned.scheme)
        assertEquals(MediaStore.AUTHORITY, returned.authority)
        val after = row(returned)
        // Scanners can return the Files/external URI facade for a Downloads/external_primary row.
        // Compare the database identity and volume, not the spelling of equivalent collection URIs.
        assertEquals(before, after)
        assertEquals(before, row(originalUri))
        Log.i("ArtifactSourceFixture", "scanCompleted=true id=${after.id} volume=${after.volume} name=${after.name} size=${after.size} pending=${after.pending} callback=$returned")
    }

    private fun rows(block: (MutableList<Uri>) -> Unit) {
        val owned = mutableListOf<Uri>()
        try { block(owned) } finally { owned.forEach { resolver.delete(it, null, null) } }
    }

    private inner class ForwardingProvider(
        private val mode: String = "r",
        denyFromOpen: Int = Int.MAX_VALUE,
        private val modifiedOverride: Long? = null,
        private val diagnostics: Boolean = false,
    ) : ContentProvider() {
        var opens = 0
        var denyFrom = denyFromOpen
        var writes = 0
        val modes = mutableListOf<String>()
        val descriptors = mutableListOf<FileDescriptor>()
        fun resolver(): ContentResolver {
            attachInfo(this@WebDavMediaStoreArtifactSourceDeviceTest.context, ProviderInfo().apply { authority = "media"; exported = false })
            return ContentResolver.wrap(this)
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            val cursor = requireNotNull(this@WebDavMediaStoreArtifactSourceDeviceTest.resolver.query(uri, projection, selection, selectionArgs, sortOrder))
            if (diagnostics) {
                val position = cursor.position
                try {
                    val metadata = if (cursor.moveToFirst()) cursor.columnNames.indices.joinToString { column ->
                        "${cursor.getColumnName(column)}=${if (cursor.isNull(column)) "null" else cursor.getString(column)}"
                    } else "empty"
                    Log.i("ArtifactSourceFixture", "query opens=$opens uri=$uri $metadata")
                    logDescriptorStates("query")
                } finally { cursor.moveToPosition(position) }
            }
            if (modifiedOverride == null) return cursor
            return cursor.use {
                MatrixCursor(it.columnNames).apply {
                    while (it.moveToNext()) addRow(Array<Any?>(it.columnCount) { column ->
                        if (it.getColumnName(column) == MediaStore.MediaColumns.DATE_MODIFIED) modifiedOverride
                        else when (it.getType(column)) {
                            Cursor.FIELD_TYPE_INTEGER -> it.getLong(column)
                            Cursor.FIELD_TYPE_STRING -> it.getString(column)
                            Cursor.FIELD_TYPE_FLOAT -> it.getDouble(column)
                            Cursor.FIELD_TYPE_BLOB -> it.getBlob(column)
                            else -> null
                        }
                    })
                }
            }
        }
        override fun openFile(uri: Uri, requestedMode: String): ParcelFileDescriptor {
            opens++; modes += requestedMode
            if (opens >= denyFrom) throw SecurityException("Injected descriptor access revocation")
            return requireNotNull(this@WebDavMediaStoreArtifactSourceDeviceTest.resolver.openFileDescriptor(uri, mode)).also {
                descriptors += it.fileDescriptor
                if (diagnostics) logDescriptorStates("open-$opens")
            }
        }
        override fun getType(uri: Uri) = this@WebDavMediaStoreArtifactSourceDeviceTest.resolver.getType(uri)
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes++; error("Source attempted insert") }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { writes++; error("Source attempted delete") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { writes++; error("Source attempted update") }
        private fun logDescriptorStates(phase: String) {
            for ((index, descriptor) in descriptors.withIndex()) {
                if (!descriptor.valid()) continue
                val state = try {
                    val stat = Os.fstat(descriptor)
                    "dev=${stat.st_dev} ino=${stat.st_ino} mode=${stat.st_mode} nlink=${stat.st_nlink} size=${stat.st_size} mtime=${stat.st_mtim.tv_sec}.${stat.st_mtim.tv_nsec} ctime=${stat.st_ctim.tv_sec}.${stat.st_ctim.tv_nsec}"
                } catch (problem: Exception) { "statFailure=${problem.javaClass.simpleName}" }
                Log.i("ArtifactSourceFixture", "$phase ownedFdIndex=$index $state")
            }
        }
        fun assertClosed() {
            assertTrue(descriptors.isNotEmpty())
            assertTrue("Source leaked a read descriptor", descriptors.none { it.valid() })
            assertEquals(0, writes)
        }
    }
}
