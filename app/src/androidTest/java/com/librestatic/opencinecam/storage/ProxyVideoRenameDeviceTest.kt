/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.ProxySettings
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.*
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real derivative rename, exact compensation, unknown-byte preservation and downstream actions. */
class ProxyVideoRenameDeviceTest {
    @Test fun renamedProxyKeepsIdentityAndSupportsOriginalRenameShareAndDelete() = exercise(0)
    @Test fun legacyReceiptWithoutProxyNameRemainsRenameable() = exercise(3)
    @Test fun failedMetadataWriteRestoresExactProxyNameRelationAndReceipt() = exercise(1)
    @Test fun unknownMetadataIsPreservedAndReportedPartialWithoutChangingOriginal() = exercise(2)

    private fun exercise(fault: Int): Unit = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val token = "proxy-share-${UUID.randomUUID()}"
        val operation = UUID.randomUUID().toString()
        val sourceBytes = instrumentation.context.assets.open("e1-video.mp4").use { it.readBytes() }
        val source = requireNotNull(resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/OpenCineCam/")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var take: LocalMediaTake? = null
        val repository = MediaProxyRepository(context)
        fun bytes(uri: String) = requireNotNull(resolver.openInputStream(uri.toUri())).use { it.readBytes() }
        try {
            requireNotNull(resolver.openOutputStream(source, "w")).use { it.write(sourceBytes) }
            assertEquals(1, resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val selected = LocalMediaRepository(context).page(GallerySettings(), token).takes.single().also { take = it }
            assertEquals(source.toString(), selected.primary.uri)
            val result = withTimeout(90_000) { repository.create(selected, ProxySettings(640, 1), operation) }
            val proxyBefore = bytes(result.proxyUri)
            val receiptFile = File(context.filesDir, "media-proxies/${digest(selected.id.toByteArray())}.json")
            if (fault == 3) {
                val old = Json.parseToJsonElement(bytes(result.metadataUri).decodeToString()).jsonObject
                val legacy = JsonObject(old.filterKeys { it != "proxyDisplayName" }).toString().toByteArray()
                resolver.openOutputStream(result.metadataUri.toUri(), "wt")!!.use { it.write(legacy) }
                val record = AtomicFile(receiptFile); val output = record.startWrite()
                try { output.write(legacy); record.finishWrite(output) }
                catch (failure: Throwable) { record.failWrite(output); throw failure }
                assertEquals(result, repository.existing(selected))
            }
            val metadataBefore = bytes(result.metadataUri)
            val receiptBefore = receiptFile.readBytes()
            assertEquals(digest(sourceBytes), result.originalSha256)
            assertEquals(digest(proxyBefore), result.proxySha256)
            if (fault in 1..2) {
                val platform = MediaStoreRenameAccess(context)
                var writes = 0
                val foreign = "foreign proxy relation bytes".toByteArray()
                val access = object : MediaRenameAccess by platform {
                    override fun writeMetadata(row: MediaDeleteRow, expected: ByteArray, replacement: ByteArray): MediaDeleteRow {
                        val changed = platform.writeMetadata(row, expected, replacement)
                        if (++writes == 1) {
                            assertArrayEquals(replacement, bytes(row.artifact.uri))
                            if (fault == 2) resolver.openOutputStream(row.artifact.uri.toUri(), "wt")!!.use { it.write(foreign) }
                            error("fixture after actual proxy metadata write")
                        }
                        return changed
                    }
                }
                var failure: Exception? = null
                withContext(NonCancellable + Dispatchers.IO) {
                    val reservation = com.librestatic.opencinecam.transfers.WebDavTransferRuntime.get(context).reserveIdleMediaMutation()
                    try { MediaProxyVideoRenamer(context, access).rename(selected, result, "custom-$operation") }
                    catch (caught: Exception) { failure = caught }
                    finally { reservation.close() }
                }
                assertNotNull(failure)
                assertEquals("fixture after actual proxy metadata write", failure!!.cause?.message)
                assertEquals(if (fault == 1) 2 else 1, writes)
                assertArrayEquals(sourceBytes, bytes(source.toString())); assertArrayEquals(proxyBefore, bytes(result.proxyUri))
                assertArrayEquals(receiptBefore, receiptFile.readBytes())
                if (fault == 1) {
                    assertTrue(failure!!.message.orEmpty().contains("previous filename and relation restored"))
                    assertArrayEquals(metadataBefore, bytes(result.metadataUri)); assertEquals(result, repository.existing(selected))
                } else {
                    assertTrue(failure!!.message.orEmpty().contains("partial or unknown"))
                    assertArrayEquals(foreign, bytes(result.metadataUri))
                }
                return@runBlocking
            }
            val collision = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Custom_é_$operation.mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/OpenCineCamProxies/$operation/")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            try {
                resolver.openOutputStream(collision, "w")!!.use { it.write(sourceBytes) }
                assertEquals(1, resolver.update(collision, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
                var collisionRejected = false
                try { repository.renameProxy(selected, result, "Custom_é_$operation") } catch (_: IllegalStateException) { collisionRejected = true }
                assertTrue(collisionRejected); assertEquals(result, repository.existing(selected))
                assertArrayEquals(metadataBefore, bytes(result.metadataUri)); assertArrayEquals(receiptBefore, receiptFile.readBytes())
                assertArrayEquals(sourceBytes, resolver.openInputStream(collision)!!.use { it.readBytes() })
            } finally { assertEquals(1, resolver.delete(collision, null, null)) }
            val renamed = repository.renameProxy(selected, result, "Custom_é_$operation")
            assertEquals("Custom_é_$operation.mp4", renamed.proxyDisplayName)
            assertEquals(result.copy(proxyDisplayName = renamed.proxyDisplayName), renamed)
            assertArrayEquals(sourceBytes, bytes(source.toString())); assertArrayEquals(proxyBefore, bytes(result.proxyUri))
            val relation = bytes(result.metadataUri)
            val before = Json.parseToJsonElement(metadataBefore.decodeToString()).jsonObject
            val after = Json.parseToJsonElement(relation.decodeToString()).jsonObject
            assertEquals(renamed.proxyDisplayName, after.getValue("proxyDisplayName").jsonPrimitive.content)
            assertEquals(before.filterKeys { it != "proxyDisplayName" }, after.filterKeys { it != "proxyDisplayName" })
            assertArrayEquals(relation, receiptFile.readBytes())
            assertEquals(renamed, repository.existing(selected)); assertEquals(renamed, repository.reconcile(selected, operation))
            val noop = repository.renameProxy(selected, renamed, renamed.proxyDisplayName.removeSuffix(".mp4"))
            assertEquals(renamed, noop); assertArrayEquals(relation, bytes(result.metadataUri)); assertArrayEquals(relation, receiptFile.readBytes())
            val share = repository.prepareShare(selected, renamed)
            assertEquals(Intent.ACTION_SEND, share.action); assertEquals(1, share.clipData!!.itemCount)
            assertEquals(result.proxyUri.toUri(), share.clipData!!.getItemAt(0).uri)
            var staleRejected = false
            try { repository.renameProxy(selected, result, "Stale") } catch (_: IllegalStateException) { staleRejected = true }
            assertTrue(staleRejected); assertEquals(renamed, repository.existing(selected))

            // Original rename must keep the custom derivative filename and its unchanged bytes.
            val originalRename = withContext(Dispatchers.IO) { MediaTakeRenamer(context).rename(selected, "original-$token") }
            assertTrue(originalRename.toString(), originalRename.complete)
            val refreshed = LocalMediaRepository(context).page(GallerySettings(), token).takes.single()
            assertEquals(renamed, repository.existing(refreshed))
            assertArrayEquals(sourceBytes, bytes(source.toString())); assertArrayEquals(proxyBefore, bytes(result.proxyUri))
            var committed = 0
            repository.deleteProxy(refreshed, renamed) { job, takeId -> assertEquals(operation, job); assertEquals(selected.id, takeId); committed++ }
            assertEquals(1, committed); assertNull(repository.existing(refreshed))
            for (uri in listOf(result.proxyUri, result.metadataUri)) {
                @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(uri.toUri())
                resolver.query(all, arrayOf("_id"), null, null, null)!!.use { assertEquals(0, it.count) }
            }
            assertArrayEquals(sourceBytes, bytes(source.toString()))
            android.util.Log.i("E17ProxyVideoRename", "customName=true onlyProxyNameFieldChanged=true urisAndVideoHashesUnchanged=true noOpExact=true staleRejected=true originalRenameShareDelete=true")
        } finally {
            // Stable operation UUID confines even a publication whose caller failed before return.
            val failures = mutableListOf<Throwable>()
            for ((collection, root) in listOf(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies",
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download")) {
                runCatching { resolver.delete(collection, "relative_path = ? AND owner_package_name = ?",
                    arrayOf("$root/OpenCineCamProxies/$operation/", context.packageName)) }.exceptionOrNull()?.let(failures::add)
            }
            take?.let { selected ->
                runCatching { AtomicFile(File(context.filesDir, "media-proxies/${digest(selected.id.toByteArray())}.json")).delete() }
                    .exceptionOrNull()?.let(failures::add)
            }
            runCatching { assertEquals(1, resolver.delete(source, null, null)) }.exceptionOrNull()?.let(failures::add)
            assertTrue("Owned proxy share fixture cleanup failed: $failures", failures.isEmpty())
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex()
}
