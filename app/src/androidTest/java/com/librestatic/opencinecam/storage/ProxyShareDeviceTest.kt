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
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real published proxy and read-only share preparation. Recipient delivery is not claimed. */
class ProxyShareDeviceTest {
    @Test fun sharesOnlyVerifiedProxyAndRejectsStaleResultAndAlteredBytes(): Unit = runBlocking<Unit> {
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
            val intent = withTimeout(20_000) { repository.prepareShare(selected, result) }
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("video/mp4", intent.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            assertEquals(setOf(Intent.EXTRA_STREAM), requireNotNull(intent.extras).keySet())
            @Suppress("DEPRECATION")
            val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertEquals(result.proxyUri.toUri(), stream)
            val clip = requireNotNull(intent.clipData)
            assertEquals(1, clip.itemCount)
            val item = clip.getItemAt(0)
            assertEquals(stream, item.uri)
            assertNull(item.intent); assertNull(item.text); assertNull(item.htmlText)
            assertNotEquals(source, stream); assertNotEquals(result.metadataUri.toUri(), stream)
            assertArrayEquals(sourceBytes, bytes(source.toString()))
            assertArrayEquals(proxyBefore, bytes(result.proxyUri))
            assertArrayEquals(metadataBefore, bytes(result.metadataUri))
            assertArrayEquals(receiptBefore, receiptFile.readBytes())

            val stalePrimary = selected.primary.copy(name = "stale-${selected.primary.name}")
            val staleTake = selected.copy(primary = stalePrimary,
                originals = selected.originals.map { if (it == selected.primary) stalePrimary else it })
            expectRejection { repository.prepareShare(staleTake, result) }
            assertArrayEquals(sourceBytes, bytes(source.toString()))
            assertArrayEquals(proxyBefore, bytes(result.proxyUri))
            assertArrayEquals(metadataBefore, bytes(result.metadataUri))
            assertArrayEquals(receiptBefore, receiptFile.readBytes())

            expectRejection { repository.prepareShare(selected, result.copy(proxyId = UUID.randomUUID().toString())) }
            assertArrayEquals(sourceBytes, bytes(source.toString()))
            assertArrayEquals(proxyBefore, bytes(result.proxyUri))
            assertArrayEquals(metadataBefore, bytes(result.metadataUri))
            assertArrayEquals(receiptBefore, receiptFile.readBytes())

            // Same length, different hash: do not confuse a size check with byte verification.
            assertTrue(proxyBefore.isNotEmpty())
            val changed = proxyBefore.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            requireNotNull(resolver.openOutputStream(result.proxyUri.toUri(), "wt")).use { it.write(changed) }
            assertEquals(proxyBefore.size, changed.size)
            assertNotEquals(result.proxySha256, digest(bytes(result.proxyUri)))
            expectRejection { repository.prepareShare(selected, result) }
            assertArrayEquals(changed, bytes(result.proxyUri))
            assertArrayEquals(sourceBytes, bytes(source.toString()))
            assertArrayEquals(metadataBefore, bytes(result.metadataUri))
            assertArrayEquals(receiptBefore, receiptFile.readBytes())
            assertEquals(listOf(selected), LocalMediaRepository(context).page(GallerySettings(), token).takes)
            android.util.Log.i("E17ProxyShareProbe",
                "actualPublishedProxy=true actionSend=true proxyOnlyStreamAndClip=true readOnlyGrantFlags=true staleResultRejected=true changedProxyHashRejected=true originalsMetadataReceiptUnchanged=true recipientDeliveryNotClaimed=true")
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

    private suspend fun expectRejection(action: suspend () -> Intent) {
        val failure = try { withTimeout(20_000) { action() }; null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { problem }
        assertNotNull("Share preparation unexpectedly accepted stale or changed proxy", failure)
        assertTrue("Expected explicit validation rejection, actual=$failure", failure is IllegalStateException || failure is IllegalArgumentException)
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex()
}
