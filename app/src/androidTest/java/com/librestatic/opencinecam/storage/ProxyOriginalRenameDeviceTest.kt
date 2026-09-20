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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real original rename must preserve both videos and update only the proxy relation name. */
class ProxyOriginalRenameDeviceTest {
    @Test fun originalRenameUpdatesProxyRelationAndReceiptWithoutChangingVideos() = exercise(false)
    @Test fun failureAfterLinkedCommitRestoresExactRelationReceiptAndOriginalName() = exercise(true)

    private fun exercise(failAfterLink: Boolean): Unit = runBlocking<Unit> {
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
            val metadataBefore = bytes(result.metadataUri)
            val receiptFile = File(context.filesDir, "media-proxies/${digest(selected.id.toByteArray())}.json")
            val receiptBefore = receiptFile.readBytes()
            assertEquals(digest(sourceBytes), result.originalSha256)
            assertEquals(digest(proxyBefore), result.proxySha256)
            val newStem = "renamed-$token"
            val renamed = withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!failAfterLink) MediaTakeRenamer(context).rename(selected, newStem) else {
                    val platform = MediaStoreRenameAccess(context)
                    executeMediaRename(selected, mediaRenamePreview(selected, newStem), object : MediaRenameAccess by platform {
                        override fun applyLinked(plan: MediaRenamePlan) {
                            platform.applyLinked(plan)
                            assertEquals(newStem + ".mp4", kotlinx.serialization.json.Json.parseToJsonElement(bytes(result.metadataUri).decodeToString()).jsonObject.getValue("originalDisplayName").jsonPrimitive.content)
                            assertArrayEquals(bytes(result.metadataUri), receiptFile.readBytes())
                            error("fixture failure after linked commit")
                        }
                    })
                }
            }
            if (failAfterLink) {
                assertFalse(renamed.complete); assertTrue(renamed.compensationAttempted); assertTrue(renamed.compensationComplete)
                assertFalse(renamed.partial); assertEquals("fixture failure after linked commit", renamed.error)
                assertEquals(selected.primary.name, LocalMediaRepository(context).page(GallerySettings(), token).takes.single().primary.name)
                assertArrayEquals(sourceBytes, bytes(source.toString())); assertArrayEquals(proxyBefore, bytes(result.proxyUri))
                assertArrayEquals(metadataBefore, bytes(result.metadataUri)); assertArrayEquals(receiptBefore, receiptFile.readBytes())
                assertEquals(result, repository.existing(selected))
                return@runBlocking
            }
            assertTrue("Original rename failed: $renamed", renamed.complete)
            val refreshed = LocalMediaRepository(context).page(GallerySettings(), token).takes.single()
            assertEquals(newStem + ".mp4", refreshed.primary.name)
            assertEquals(selected.id, refreshed.id); assertEquals(selected.primary.uri, refreshed.primary.uri)
            assertArrayEquals(sourceBytes, bytes(source.toString()))
            assertArrayEquals(proxyBefore, bytes(result.proxyUri))
            val metadataAfter = bytes(result.metadataUri)
            val before = kotlinx.serialization.json.Json.parseToJsonElement(metadataBefore.decodeToString()).jsonObject
            val after = kotlinx.serialization.json.Json.parseToJsonElement(metadataAfter.decodeToString()).jsonObject
            assertEquals(refreshed.primary.name, after.getValue("originalDisplayName").jsonPrimitive.content)
            assertEquals(before.filterKeys { it != "originalDisplayName" }, after.filterKeys { it != "originalDisplayName" })
            assertArrayEquals(metadataAfter, receiptFile.readBytes())
            assertEquals(result, repository.existing(refreshed))
            val intent = repository.prepareShare(refreshed, result)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals(1, intent.clipData!!.itemCount)
            assertEquals(result.proxyUri.toUri(), intent.clipData!!.getItemAt(0).uri)
            android.util.Log.i("E17ProxyRename", "renameComplete=true originalAndProxyBytesUnchanged=true onlyOriginalDisplayNameChanged=true receiptMatches=true shareVerified=true")
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
