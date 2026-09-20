/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProxyCatalogDeviceTest {
    @Test fun directProxySurvivesOriginalDeletionAndSharesWithoutSourceOrQueueJob(): Unit = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val token = "proxy-catalog-${UUID.randomUUID()}"
        val proxyId = UUID.randomUUID().toString()
        val sourceBytes = instrumentation.context.assets.open("e1-video.mp4").use { it.readBytes() }
        assertEquals(5404, sourceBytes.size)
        assertEquals("a32963539eefe4fc0d14e76770d51e49c25108a31b9eda9176a022a94a9a99b6", digest(sourceBytes))
        val source = requireNotNull(resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/OpenCineCam/")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var receipt: AtomicFile? = null
        fun read(uri: String) = requireNotNull(resolver.openInputStream(uri.toUri())).use { it.readBytes() }
        val repository = MediaProxyRepository(context)
        try {
            resolver.openOutputStream(source, "w")!!.use { it.write(sourceBytes) }
            assertEquals(1, resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val selected = withTimeout(10_000) {
                var found: LocalMediaTake? = null
                while (found == null) {
                    found = LocalMediaRepository(context).page(GallerySettings(), token).takes.singleOrNull()
                        ?.takeIf { it.primary.sizeBytes == sourceBytes.size.toLong() }
                    if (found == null) delay(25)
                }
                found
            }
            val queue = MediaProxyQueue.get(context)
            withTimeout(20_000) { queue.states.first { it.loaded || it.error != null } }.also { assertNull(it.error) }
            val proxy = withTimeout(90_000) { repository.create(selected, ProxySettings(640, 1), proxyId) }
            assertTrue(queue.states.value.jobs.none { it.take.id == selected.id })
            val record = AtomicFile(File(context.filesDir, "media-proxies/${digest(selected.id.toByteArray())}.json")).also { receipt = it }
            val committed = record.openRead().use { it.readBytes() }
            val proxyBytes = read(proxy.proxyUri)
            val metadataBytes = read(proxy.metadataUri)
            assertArrayEquals(committed, metadataBytes)
            assertArrayEquals(sourceBytes, read(source.toString()))
            val before = withTimeout(20_000) { repository.catalog() }.single { it.takeId == selected.id }
            assertEquals(proxy, before.result); assertEquals(selected.primary.name, before.originalDisplayName)
            assertEquals(digest(committed), before.receiptSha256)
            val deleted = withContext(Dispatchers.IO) { MediaTakeDeleter(context).delete(selected) }
            assertTrue("Original deletion did not complete: $deleted", deleted.complete)
            @Suppress("DEPRECATION") val allSource = MediaStore.setIncludePending(source)
            resolver.query(allSource, arrayOf("_id"), null, null, null)!!.use { assertEquals(0, it.count) }
            assertTrue(LocalMediaRepository(context).page(GallerySettings(), token).takes.isEmpty())
            assertTrue(queue.states.value.jobs.none { it.take.id == selected.id })
            val retained = withTimeout(20_000) { MediaProxyRepository(context).catalog() }.single { it.takeId == selected.id }
            assertEquals(before, retained) // Source reference did not become an invented catalog original.
            val intent = withTimeout(20_000) { repository.prepareShare(retained) }
            assertEquals(Intent.ACTION_SEND, intent.action); assertEquals("video/mp4", intent.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            assertEquals(setOf(Intent.EXTRA_STREAM), requireNotNull(intent.extras).keySet())
            @Suppress("DEPRECATION") val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertEquals(proxy.proxyUri.toUri(), stream)
            val clip = requireNotNull(intent.clipData)
            assertEquals(1, clip.itemCount); assertEquals(stream, clip.getItemAt(0).uri)
            assertNull(clip.getItemAt(0).intent); assertNull(clip.getItemAt(0).text); assertNull(clip.getItemAt(0).htmlText)
            assertNotEquals(source, stream); assertNotEquals(proxy.metadataUri.toUri(), stream)
            assertArrayEquals(proxyBytes, read(proxy.proxyUri)); assertArrayEquals(metadataBytes, read(proxy.metadataUri))
            assertArrayEquals(committed, record.openRead().use { it.readBytes() })
            expectRejected { repository.prepareShare(retained.copy(originalDisplayName = "stale.mp4")) }
            expectRejected { repository.prepareShare(retained.copy(receiptSha256 = "0".repeat(64))) }

            // Strict receipt parsing rejects duplicate keys and numeric strings before granting.
            val json = Json.parseToJsonElement(committed.decodeToString()).jsonObject
            val malformed = listOf(
                committed.decodeToString().dropLast(1) + ",\"takeId\":\"${selected.id}\"}",
                JsonObject(json + ("frames" to JsonPrimitive(proxy.frames.toString()))).toString(),
                JsonObject(json + ("unknownField" to JsonPrimitive(true))).toString()
            )
            for (text in malformed) {
                try {
                    record.baseFile.writeText(text)
                    expectRejected { repository.catalog() }; expectRejected { repository.prepareShare(retained) }
                } finally { record.baseFile.writeBytes(committed) }
            }
            assertEquals(retained, repository.catalog().single { it.takeId == selected.id })
            val wrongName = File(record.baseFile.parentFile, digest(UUID.randomUUID().toString().toByteArray()) + ".json")
            assertFalse(wrongName.exists())
            try {
                assertTrue(record.baseFile.renameTo(wrongName))
                val mismatch = expectRejected { repository.catalog() }
                assertTrue(mismatch.message.orEmpty().contains("filename"))
            } finally { if (wrongName.exists()) check(wrongName.renameTo(record.baseFile)) }
            // Same-length derivative corruption cannot pass the row-size checks.
            val changed = proxyBytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            resolver.openOutputStream(proxy.proxyUri.toUri(), "wt")!!.use { it.write(changed) }
            assertNotEquals(proxy.proxySha256, digest(read(proxy.proxyUri)))
            expectRejected { repository.catalog() }; expectRejected { repository.prepareShare(retained) }
            assertArrayEquals(changed, read(proxy.proxyUri)); assertArrayEquals(metadataBytes, read(proxy.metadataUri))
            assertArrayEquals(committed, record.openRead().use { it.readBytes() })
            android.util.Log.i("E17ProxyCatalog", "PASS directNoJob=true originalAbsentVerified=true retainedReferenceOnly=true proxyOnlyReadGrant=true malformedReceiptRejected=true currentProxyHashTamperRejected=true recipientDeliveryNotClaimed=true")
        } finally {
            withContext(NonCancellable) {
                for ((collection, root) in listOf(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies",
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download")) {
                    val count = resolver.delete(collection, "relative_path = ? AND owner_package_name = ?",
                        arrayOf("$root/OpenCineCamProxies/$proxyId/", context.packageName))
                    assertTrue(count in 0..1)
                }
                receipt?.delete()
                assertTrue(resolver.delete(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "_id = ? AND owner_package_name = ?", arrayOf(source.lastPathSegment, context.packageName)) in 0..1)
                val candidate = File(context.cacheDir, "proxy-$proxyId.mp4")
                check(!candidate.exists() || candidate.delete())
            }
        }
    }

    @Test fun receiptEnumerationRejectsOverflowUnknownAtomicStateAndSymlinks(): Unit = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val privateRoot = File(context.cacheDir, "proxy-catalog-bounds-${UUID.randomUUID()}")
        check(privateRoot.mkdir())
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = privateRoot
        }
        val receipts = File(privateRoot, "media-proxies")
        val repository = MediaProxyRepository(isolated)
        try {
            assertTrue(repository.catalog().isEmpty())
            check(receipts.mkdir())
            val key = "a".repeat(64)
            for (name in listOf("unknown", "$key.json.bak", "$key.json.new")) {
                val file = File(receipts, name)
                try { file.writeText("{}"); expectRejected { repository.catalog() } }
                finally { check(file.delete()) }
            }
            val target = File(privateRoot, "outside-receipt").apply { writeText("{}") }
            val symlink = File(receipts, "$key.json")
            try {
                android.system.Os.symlink(target.path, symlink.path)
                expectRejected { repository.catalog() }
                assertEquals("{}", target.readText())
            } finally { check(symlink.delete()); check(target.delete()) }
            for (index in 0..1024) File(receipts, index.toString(16).padStart(64, '0') + ".json").writeText("{}")
            val overflow = expectRejected { repository.catalog() }
            assertTrue(overflow.message.orEmpty().contains("1024"))
        } finally { check(privateRoot.deleteRecursively()) }
    }

    private suspend fun expectRejected(action: suspend () -> Any?): Exception {
        val failure = try { withTimeout(20_000) { action() }; null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { problem }
        assertNotNull("Proxy catalog/share unexpectedly accepted invalid state", failure)
        return requireNotNull(failure)
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex()
}
