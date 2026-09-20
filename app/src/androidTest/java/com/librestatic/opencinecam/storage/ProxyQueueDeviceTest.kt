/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.GallerySettings
import com.librestatic.opencinecam.ProxySettings
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ProxyQueueDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private fun insert(collection: Uri, name: String, path: String, mime: String, bytes: ByteArray, pending: Int): Uri {
        val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.MIME_TYPE, mime); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        if (pending == 0) assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
        return uri
    }
    private suspend fun terminal(queue: ProxyJobQueue, id: String): ProxyJob = withTimeout(90_000) {
        queue.states.first { s -> s.error != null || s.jobs.any { it.id == id && it.status in setOf(ProxyJobStatus.SUCCEEDED, ProxyJobStatus.FAILED, ProxyJobStatus.CANCELLED) } }
            .also { check(it.error == null) { it.error.orEmpty() } }.jobs.single { it.id == id }
    }
    @Test fun recoveredRunningRequestCleansBothPublicationWindowsAndCommitsOnlyOnce() = runBlocking<Unit> {
        exercise(cancelled = false)
    }
    @Test fun recoveredCancellationCleansCandidateAndRowsWithoutTranscodingOriginal() = runBlocking<Unit> {
        exercise(cancelled = true)
    }
    private suspend fun exercise(cancelled: Boolean) {
        val id = UUID.randomUUID().toString(); val token = "queue-test-$id"
        val bytes = instrumentation.context.assets.open("e1-video.mp4").use { it.readBytes() }
        val video = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val metadata = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val original = insert(video, "$token.mp4", "DCIM/OpenCineCam/", "video/mp4", bytes, 0)
        val staged = mutableListOf<Uri>()
        val directory = File(context.filesDir, token)
        val candidate = File(context.cacheDir, "proxy-$id.mp4")
        var result: MediaProxyResult? = null
        var take: LocalMediaTake? = null
        var queue: ProxyJobQueue? = null
        try {
            take = LocalMediaRepository(context).page(GallerySettings(), token).takes.single()
            // Native provider state from both crash windows: published video + still-pending metadata.
            staged += insert(video, "proxy-$id.mp4", "Movies/OpenCineCamProxies/$id/", "video/mp4", bytes, 0)
            staged += insert(metadata, "proxy-$id.json", "Download/OpenCineCamProxies/$id/", "application/json", "{}".toByteArray(), 1)
            candidate.writeBytes(bytes)
            val store = ProxyJobStore(directory)
            val request = ProxyJob(id, take, ProxySettings(640, 1), if (cancelled) ProxyJobStatus.CANCELLING else ProxyJobStatus.RUNNING, 1)
            store.write(listOf(request))
            val repository = MediaProxyRepository(context); val exports = AtomicInteger()
            fun engine() = object : ProxyJobEngine {
                override suspend fun reconcile(job: ProxyJob) = repository.reconcile(job.take, job.id)
                override suspend fun create(job: ProxyJob): MediaProxyResult {
                    exports.incrementAndGet(); return repository.create(job.take, job.settings, job.id)
                }
            }
            queue = ProxyJobQueue(ProxyJobStore(directory), engine()).also { it.start() }
            val done = terminal(queue, id)
            assertEquals(done.error, if (cancelled) ProxyJobStatus.CANCELLED else ProxyJobStatus.SUCCEEDED, done.status)
            queue.shutdown(); queue = null
            assertFalse(candidate.exists())
            for (uri in staged) {
                @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(uri)
                resolver.query(all, arrayOf("_id"), null, null, null)!!.use { assertEquals(0, it.count) }
            }
            result = repository.existing(take)
            assertArrayEquals(bytes, resolver.openInputStream(original)!!.use { it.readBytes() })
            if (cancelled) {
                assertNull(result); assertEquals(0, exports.get())
            } else {
                val proxy = requireNotNull(result)
                verifyProxyCorrespondence(probeProxyMedia(context, original), probeProxyMedia(context, proxy.proxyUri.toUri()), VideoDisplayGeometry(128, 96))
                assertEquals(1, exports.get())
                val relation = resolver.openInputStream(proxy.metadataUri.toUri())!!.use { it.readBytes().decodeToString() }
                assertTrue(relation.contains(id))
                // Model death after receipt commit but before queue terminal state became durable.
                store.write(listOf(done.copy(status = ProxyJobStatus.RUNNING)))
                queue = ProxyJobQueue(ProxyJobStore(directory), engine()).also { it.start() }
                assertEquals(ProxyJobStatus.SUCCEEDED, terminal(queue, id).status)
                queue.shutdown(); queue = null
                assertEquals(1, exports.get()); assertEquals(proxy, repository.existing(take))
                try { repository.reconcile(take, UUID.randomUUID().toString()); fail("Different job adopted a committed receipt") }
                catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("different job")) }
                assertEquals(proxy, repository.existing(take))
            }
            Log.i("E17ProxyQueue", "PASS id=$id cancelled=$cancelled originalBytes=${bytes.size} exports=${exports.get()} candidateAbsent=${!candidate.exists()} receipt=${result?.proxyUri} durable=${ProxyJobStore(directory).read().single().status}")
        } finally {
            queue?.shutdown()
            result?.let { resolver.delete(it.proxyUri.toUri(), null, null); resolver.delete(it.metadataUri.toUri(), null, null) }
            staged.forEach { uri ->
                val identity = requireNotNull(mediaDeleteIdentity(uri.toString()))
                val collection = if (identity.collection == MediaDeleteCollection.VIDEO) video else metadata
                resolver.delete(collection, "_id = ? AND owner_package_name = ?", arrayOf(identity.id.toString(), context.packageName))
            }
            resolver.delete(original, null, null)
            take?.let { selected ->
                val key = MessageDigest.getInstance("SHA-256").digest(selected.id.toByteArray()).joinToString("") { "%02x".format(it) }
                android.util.AtomicFile(File(context.filesDir, "media-proxies/$key.json")).delete()
            }
            assertTrue(!candidate.exists() || candidate.delete())
            assertTrue(!directory.exists() || directory.deleteRecursively())
        }
    }
}
