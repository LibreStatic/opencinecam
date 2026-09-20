/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentValues
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
import org.junit.Assert.*
import org.junit.Test

/** Real MediaStore deletion and reconstructed partial deletion; not a process-kill claim. */
class ProxyDeletionDeviceTest {
    @Test fun removesOnlyPairAndTerminalJobThenAllowsExplicitNewSettings() = exercise(false)
    @Test fun recoversMissingVideoBeforeQueueAdmissionWithoutRegeneration() = exercise(true)

    private fun exercise(partial: Boolean): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val token = "proxy-delete-$id"
        val bytes = instrumentation.context.assets.open("e1-video.mp4").use { it.readBytes() }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val source = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/OpenCineCam/")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var take: LocalMediaTake? = null
        var queue: ProxyJobQueue? = null
        val directory = File(context.filesDir, token)
        val journal = ProxyDeletionJournal(File(context.filesDir, "proxy-deletions"))
        try {
            resolver.openOutputStream(source, "w")!!.use { it.write(bytes) }
            assertEquals(1, resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val selected = LocalMediaRepository(context).page(GallerySettings(), token).takes.single().also { take = it }
            val repository = MediaProxyRepository(context)
            val result = withTimeout(90_000) { repository.create(selected, ProxySettings(640, 1), id) }
            val store = ProxyJobStore(directory)
            store.write(listOf(ProxyJob(id, selected, ProxySettings(640, 1), ProxyJobStatus.SUCCEEDED, 1)))
            var exports = 0
            val engine = object : ProxyJobEngine {
                override suspend fun create(job: ProxyJob): MediaProxyResult { exports++; return repository.create(job.take, job.settings, job.id) }
                override suspend fun reconcile(job: ProxyJob) = repository.reconcile(job.take, job.id)
                override suspend fun recoverDeletions(committed: suspend (String, String) -> Unit) = repository.recoverDeletions(committed)
                override suspend fun deleteProxy(take: LocalMediaTake, expected: MediaProxyResult, committed: suspend (String, String) -> Unit) = repository.deleteProxy(take, expected, committed)
            }
            if (partial) {
                val metadata = resolver.openInputStream(result.metadataUri.toUri())!!.use { it.readBytes() }
                val intent = ProxyDeletionIntent(id, selected.id, source.toString(), result.originalSha256,
                    ProxyDeletionMember(result.proxyUri, "proxy-$id.mp4", "video/mp4", "Movies/OpenCineCamProxies/$id/", context.packageName, result.proxyBytes, result.proxySha256),
                    ProxyDeletionMember(result.metadataUri, "proxy-$id.json", "application/json", "Download/OpenCineCamProxies/$id/", context.packageName, metadata.size.toLong(), digest(metadata)))
                // Seed a confirmed journal boundary using the production writer, then interrupt before effects.
                var interrupted = false
                try { journal.begin(intent, object : ProxyDeletionAccess {
                    override suspend fun validate(intent: ProxyDeletionIntent) { assertEquals(result, repository.existing(selected)) }
                    override suspend fun isAbsent(member: ProxyDeletionMember) = false
                    override suspend fun delete(member: ProxyDeletionMember) { error("fixture interruption before first provider delete") }
                    override suspend fun verifyEmpty(intent: ProxyDeletionIntent) { error("unexpected") }
                    override suspend fun commitDeletion(intent: ProxyDeletionIntent) { error("unexpected") }
                }) } catch (failure: IllegalStateException) {
                    assertEquals("fixture interruption before first provider delete", failure.message); interrupted = true
                }
                assertTrue(interrupted); assertEquals(intent, journal.read(id))
                assertEquals(1, resolver.delete(result.proxyUri.toUri(), null, null))
                assertArrayEquals(metadata, resolver.openInputStream(result.metadataUri.toUri())!!.use { it.readBytes() })
            }
            val owner = ProxyJobQueue(store, engine, admission = { com.librestatic.opencinecam.ProxyWaitReason.CHARGING }).also { queue = it; it.start() }
            withTimeout(20_000) { owner.states.first { it.loaded || it.error != null } }
            if (!partial) owner.deleteProxy(selected, result)
            // A loaded emission can precede recovery completion; this reads after the queue gate retires.
            withTimeout(20_000) { owner.states.first { it.error != null || it.jobs.isEmpty() } }
            assertNull(owner.states.value.error)
            assertTrue(store.read().isEmpty()); assertEquals(0, exports)
            for (uri in listOf(result.proxyUri, result.metadataUri)) {
                @Suppress("DEPRECATION") val all = MediaStore.setIncludePending(uri.toUri())
                resolver.query(all, arrayOf("_id"), null, null, null)!!.use { assertEquals(0, it.count) }
            }
            assertNull(journal.read(id)); assertNull(repository.existing(selected))
            assertArrayEquals(bytes, resolver.openInputStream(source)!!.use { it.readBytes() })
            assertEquals(listOf(selected), LocalMediaRepository(context).page(GallerySettings(), token).takes)
            val nextSettings = ProxySettings(1920, 5)
            val next = owner.enqueue(selected, nextSettings)
            assertNotEquals(id, next)
            withTimeout(20_000) { owner.states.first { it.waiting.containsKey(next) || it.error != null } }
            assertNull(owner.states.value.error); assertEquals(nextSettings, store.read().single().settings)
            assertEquals(0, exports)
            owner.cancel(next)
            withTimeout(20_000) { owner.states.first { it.jobs.single().status == ProxyJobStatus.CANCELLED || it.error != null } }
            assertNull(owner.states.value.error)
            android.util.Log.i("E17ProxyActions", "partial=$partial pairAbsent=true originalHash=${digest(bytes)} queueRemoved=true automaticExports=$exports explicitNewSettings=true")
        } finally {
            queue?.shutdown()
            for ((target, root) in listOf(collection to "Movies", MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download")) {
                resolver.delete(target, "relative_path = ? AND owner_package_name = ?", arrayOf("$root/OpenCineCamProxies/$id/", context.packageName))
            }
            take?.let { AtomicFile(File(context.filesDir, "media-proxies/${digest(it.id.toByteArray())}.json")).delete() }
            for (suffix in listOf("json", "tmp")) File(context.filesDir, "proxy-deletions/$id.$suffix").let { check(!it.exists() || it.delete()) }
            check(!directory.exists() || directory.deleteRecursively())
            assertEquals(1, resolver.delete(source, null, null))
        }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).proxyHex()
}
