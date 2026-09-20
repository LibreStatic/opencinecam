/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.ProxySettings
import com.librestatic.opencinecam.transfers.WebDavTransferRuntime
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real retained derivative actions. Original identity remains historical, never a fabricated take. */
class ProxyCatalogActionsDeviceTest {
    @Test fun absentOriginalAllowsNfcRenameOpenAndConfirmedPairHistoryDeletion(): Unit = exercise(0)
    @Test fun actualExpandedMetadataWriteFailureRestoresKnownBytesAndNameWithoutOriginal(): Unit = exercise(1)
    @Test fun interruptedCommitRecoversExactTombstoneAndRetiresHistoryWithoutOriginal(): Unit = exercise(2)

    private fun exercise(fault: Int): Unit = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val token = "proxy-actions-${UUID.randomUUID()}"
        val id = UUID.randomUUID().toString()
        val video = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val sourceBytes = instrumentation.context.assets.open("e1-video.mp4").use { it.readBytes() }
        assertEquals(5404, sourceBytes.size)
        assertEquals("a32963539eefe4fc0d14e76770d51e49c25108a31b9eda9176a022a94a9a99b6", digest(sourceBytes))
        val original = requireNotNull(resolver.insert(video, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/OpenCineCam/")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val privateRoot = File(context.cacheDir, "proxy-actions-$id")
        check(privateRoot.mkdir())
        var record: AtomicFile? = null
        var queue: ProxyJobQueue? = null
        val repository = MediaProxyRepository(context)
        fun read(uri: String) = resolver.openInputStream(uri.toUri())!!.use { it.readBytes() }
        fun absent(uri: String) {
            val identity = requireNotNull(mediaDeleteIdentity(uri))
            val collection = if (identity.collection == MediaDeleteCollection.VIDEO) video else downloads
            @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(collection)
            resolver.query(all, arrayOf("_id"), "_id = ?", arrayOf(identity.id.toString()), null)!!.use { assertEquals(0, it.count) }
        }
        try {
            resolver.openOutputStream(original, "w")!!.use { it.write(sourceBytes) }
            assertEquals(1, resolver.update(original, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val take = withTimeout(10_000) {
                var found: LocalMediaTake? = null
                while (found == null) {
                    found = LocalMediaRepository(context).page(GallerySettings(), token).takes.singleOrNull()
                        ?.takeIf { it.primary.sizeBytes == sourceBytes.size.toLong() }
                    if (found == null) delay(25)
                }
                found
            }
            val global = MediaProxyQueue.get(context)
            withTimeout(20_000) { global.states.first { it.loaded || it.error != null } }.also { assertNull(it.error) }
            val proxy = withTimeout(90_000) { repository.create(take, ProxySettings(640, 1), id) }
            val proxyBefore = read(proxy.proxyUri)
            val metadataBefore = read(proxy.metadataUri)
            val receipt = AtomicFile(File(context.filesDir, "media-proxies/${digest(take.id.toByteArray())}.json")).also { record = it }
            val receiptBefore = receipt.openRead().use { it.readBytes() }
            assertArrayEquals(metadataBefore, receiptBefore)
            assertArrayEquals(sourceBytes, read(original.toString()))
            val deletion = withContext(Dispatchers.IO) { MediaTakeDeleter(context).delete(take) }
            assertTrue(deletion.toString(), deletion.complete); absent(original.toString())
            val entry = repository.catalog().single { it.takeId == take.id }
            assertEquals(proxy, entry.result)
            val store = ProxyJobStore(File(privateRoot, "queue"))
            val history = ProxyJob(id, take, ProxySettings(640, 1), ProxyJobStatus.SUCCEEDED, 1)
            store.write(listOf(history))
            var commits = 0
            val jobs = ProxyJobQueue(store, object : ProxyJobEngine {
                override suspend fun reconcile(job: ProxyJob): MediaProxyResult? = error("Committed history must not reconcile source")
                override suspend fun create(job: ProxyJob): MediaProxyResult = error("Derivative action must not encode")
                override suspend fun recoverDeletions(committed: suspend (String, String) -> Unit) = repository.recoverDeletions(committed)
                override suspend fun deleteProxy(entry: ProxyCatalogEntry, committed: suspend (String, String) -> Unit) {
                    repository.deleteProxy(entry) { jobId, takeId ->
                        commits++
                        if (fault == 2 && commits == 1) error("fixture interrupted durable queue commit")
                        committed(jobId, takeId)
                    }
                }
            }).also { queue = it }
            jobs.start()
            withTimeout(20_000) { jobs.states.first { it.loaded || it.error != null } }.also { assertNull(it.error) }
            if (fault == 1) {
                val platform = MediaStoreRenameAccess(context)
                var writes = 0
                val access = object : MediaRenameAccess by platform {
                    override fun writeMetadata(row: MediaDeleteRow, expected: ByteArray, replacement: ByteArray): MediaDeleteRow {
                        val changed = platform.writeMetadata(row, expected, replacement)
                        writes++
                        if (writes == 1) {
                            assertArrayEquals(replacement, read(row.artifact.uri))
                            assertTrue(replacement.size > expected.size)
                            error("fixture after real retained proxy JSON write")
                        }
                        return changed
                    }
                }
                val failure = withContext(NonCancellable + Dispatchers.IO) {
                    val reservation = WebDavTransferRuntime.get(context).reserveIdleMediaMutation()
                    try { expectRejected { MediaProxyVideoRenamer(context, access).rename(entry, "Á".repeat(90)) } }
                    finally { reservation.close() }
                }
                assertEquals("fixture after real retained proxy JSON write", failure.cause?.message)
                assertTrue(failure.message.orEmpty().contains("previous filename and relation restored"))
                assertEquals(2, writes)
                assertArrayEquals(proxyBefore, read(proxy.proxyUri)); assertArrayEquals(metadataBefore, read(proxy.metadataUri))
                assertArrayEquals(receiptBefore, receipt.openRead().use { it.readBytes() })
                assertEquals(entry, repository.catalog().single { it.takeId == take.id })
                assertEquals(listOf(history), store.read()); absent(original.toString())
                return@runBlocking
            }
            val renamed = repository.renameProxy(entry, "Kept cafe\u0301")
            assertEquals("Kept café.mp4", renamed.result.proxyDisplayName)
            assertEquals(entry.result.copy(proxyDisplayName = renamed.result.proxyDisplayName), renamed.result)
            assertEquals(entry.takeId, renamed.takeId); assertEquals(entry.originalDisplayName, renamed.originalDisplayName)
            assertArrayEquals(proxyBefore, read(proxy.proxyUri))
            val metadataAfter = read(proxy.metadataUri)
            assertArrayEquals(metadataAfter, receipt.openRead().use { it.readBytes() })
            val expectedJson = JsonObject(Json.parseToJsonElement(metadataBefore.decodeToString()).jsonObject +
                ("proxyDisplayName" to JsonPrimitive(renamed.result.proxyDisplayName)))
            assertEquals(expectedJson, Json.parseToJsonElement(metadataAfter.decodeToString()))
            assertEquals(digest(metadataAfter), renamed.receiptSha256)
            assertEquals(renamed, repository.catalog().single { it.takeId == take.id })
            val opened = repository.prepareOpen(renamed)
            assertEquals(Intent.ACTION_VIEW, opened.action); assertEquals("video/mp4", opened.type)
            assertEquals(proxy.proxyUri.toUri(), opened.data)
            assertFalse(opened.hasExtra(Intent.EXTRA_STREAM))
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, opened.flags)
            val clip = requireNotNull(opened.clipData)
            assertEquals(1, clip.itemCount); assertEquals(opened.data, clip.getItemAt(0).uri)
            assertNull(clip.getItemAt(0).intent); assertNull(clip.getItemAt(0).text); assertNull(clip.getItemAt(0).htmlText)
            assertNotEquals(original, opened.data); assertNotEquals(proxy.metadataUri.toUri(), opened.data)
            expectRejected { repository.prepareOpen(entry) }
            expectRejected { repository.renameProxy(entry, "Stale") }
            expectRejected { jobs.deleteProxy(entry) }
            assertEquals(0, commits); assertEquals(listOf(history), store.read())
            if (fault == 0) {
                val changed = proxyBefore.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
                resolver.openOutputStream(proxy.proxyUri.toUri(), "wt")!!.use { it.write(changed) }
                try {
                    expectRejected { repository.prepareOpen(renamed) }
                    expectRejected { repository.renameProxy(renamed, "Tampered") }
                    expectRejected { jobs.deleteProxy(renamed) }
                    assertArrayEquals(changed, read(proxy.proxyUri))
                    assertArrayEquals(metadataAfter, read(proxy.metadataUri))
                    assertArrayEquals(metadataAfter, receipt.openRead().use { it.readBytes() })
                    assertEquals(0, commits)
                } finally { resolver.openOutputStream(proxy.proxyUri.toUri(), "wt")!!.use { it.write(proxyBefore) } }
            } else {
                val failure = expectRejected { jobs.deleteProxy(renamed) }
                assertEquals("fixture interrupted durable queue commit", failure.message)
                assertEquals(1, commits); assertEquals(listOf(history), store.read())
                assertFalse(receipt.baseFile.exists()); absent(proxy.proxyUri); absent(proxy.metadataUri)
                assertNotNull(ProxyDeletionJournal(File(context.filesDir, "proxy-deletions")).read(id))
                expectRejected { jobs.deleteProxy(renamed.copy(receiptSha256 = "0".repeat(64))) }
                assertEquals(1, commits)
            }
            jobs.deleteProxy(renamed)
            assertEquals(if (fault == 2) 2 else 1, commits)
            assertTrue(store.read().isEmpty()); assertTrue(jobs.states.value.jobs.isEmpty())
            assertFalse(receipt.baseFile.exists()); absent(proxy.proxyUri); absent(proxy.metadataUri); absent(original.toString())
            assertNull(ProxyDeletionJournal(File(context.filesDir, "proxy-deletions")).read(id))
            assertTrue(repository.catalog().none { it.takeId == take.id })
            android.util.Log.i("E17ProxyCatalogActions", "PASS fault=$fault sourceAbsent=true nfcName=true exactUrisAndProxyBytes=true onlyProxyOpenGrant=true staleRejected=true pairReceiptHistoryRetired=true")
        } finally {
            withContext(NonCancellable) {
                queue?.shutdown()
                for ((collection, root) in listOf(video to "Movies", downloads to "Download")) {
                    assertTrue(resolver.delete(collection, "relative_path = ? AND owner_package_name = ?",
                        arrayOf("$root/OpenCineCamProxies/$id/", context.packageName)) in 0..1)
                }
                record?.delete()
                for (suffix in listOf("json", "tmp")) File(context.filesDir, "proxy-deletions/$id.$suffix").let {
                    check(!it.exists() || it.delete())
                }
                assertTrue(resolver.delete(video, "_id = ? AND owner_package_name = ?",
                    arrayOf(ContentUris.parseId(original).toString(), context.packageName)) in 0..1)
                check(privateRoot.deleteRecursively())
                File(context.cacheDir, "proxy-$id.mp4").let { check(!it.exists() || it.delete()) }
            }
        }
    }
    private suspend fun expectRejected(action: suspend () -> Any?): Exception {
        val failure = try { withTimeout(20_000) { action() }; null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { problem }
        assertNotNull("Retained proxy action unexpectedly accepted invalid state", failure)
        return requireNotNull(failure)
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex()
}
