/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContentValues
import android.provider.MediaStore
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.net.toUri
import com.librestatic.opencinecam.storage.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual platform battery observation and real queue/repository/codec, not injected admission. */
class ProxyQueuedSourceRenameDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Test fun waitingOriginalRenameUpdatesSameRequestBeforeRealEncoding() = runBlocking<Unit> {
        check(android.os.Build.FINGERPRINT.contains("generic")) { "Battery override fixture requires emulator" }
        val batteryBefore = shell("dumpsys battery")
        check(!batteryBefore.contains("UPDATES STOPPED")) { "Another battery override must not be overwritten" }
        val policies = ProxyPolicies.get(context); val before = policies.states.value
        val token = "proxy-source-rename-${UUID.randomUUID()}"
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val bytes = instrumentation.context.assets.open("e1-video.mp4").use { it.readBytes() }
        val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.mp4"); put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/OpenCineCam/")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var id: String? = null; var take: LocalMediaTake? = null; var result: MediaProxyResult? = null
        val queue = MediaProxyQueue.get(context)
        try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            take = LocalMediaRepository(context).page(GallerySettings(), token).takes.single()
            shell("dumpsys battery set level 10")
            policies.update { ProxyPolicy(false, 50, 64) }
            queue.enqueue(take, ProxySettings(640, 1))
            val takeId = take.id
            val waiting = withTimeout(20_000) { queue.states.first { state -> state.jobs.any { it.take.id == takeId && state.waiting[it.id] == ProxyWaitReason.BATTERY } } }
            id = waiting.jobs.single { it.take.id == take.id }.id
            val request = waiting.jobs.single { it.id == id }
            assertEquals(ProxyJobStatus.QUEUED, request.status); assertEquals(0, request.attempts)
            assertFalse(File(context.cacheDir, "proxy-$id.mp4").exists())
            assertNull(MediaProxyRepository(context).existing(take))
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            val renamedStem = "renamed-$token"
            val renamed = withContext(Dispatchers.IO) { MediaTakeRenamer(context).rename(take, renamedStem) }
            assertTrue(renamed.toString(), renamed.complete)
            take = LocalMediaRepository(context).page(GallerySettings(), renamedStem).takes.single()
            val updated = queue.states.value.jobs.single { it.id == id }
            assertEquals(take, updated.take)
            assertEquals(request.settings, updated.settings)
            assertEquals(request.attempts, updated.attempts)
            assertEquals(ProxyJobStatus.QUEUED, updated.status)
            assertEquals(ProxyWaitReason.BATTERY, queue.states.value.waiting[id])
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            // Live local policy releases the same job, not a newly enqueued request or retry.
            policies.update { it.copy(minimumBatteryPercent = 0) }
            val done = withTimeout(90_000) { queue.states.first { state -> state.error != null || state.jobs.any { it.id == id && it.status in setOf(ProxyJobStatus.SUCCEEDED, ProxyJobStatus.FAILED) } } }
            assertNull(done.error)
            val completed = done.jobs.single { it.id == id }
            assertEquals(completed.error, ProxyJobStatus.SUCCEEDED, completed.status)
            assertEquals(1, completed.attempts); assertEquals(request.settings, completed.settings)
            result = MediaProxyRepository(context).existing(take)
            val proxy = requireNotNull(result)
            assertEquals(id, proxy.proxyId)
            verifyProxyCorrespondence(probeProxyMedia(context, uri), probeProxyMedia(context, proxy.proxyUri.toUri()), VideoDisplayGeometry(128, 96))
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            val metadata = resolver.openInputStream(proxy.metadataUri.toUri())!!.bufferedReader().use { it.readText() }
            assertEquals(take.primary.name, org.json.JSONObject(metadata).getString("originalDisplayName"))
        } finally {
            withContext(NonCancellable) {
                try {
                val cleanupId = id ?: queue.states.value.jobs.firstOrNull { it.take.id == take?.id }?.id
                cleanupId?.let { selected ->
                    queue.cancel(selected)
                    withTimeout(30_000) { queue.states.first { it.jobs.any { j -> j.id == selected && j.status in setOf(ProxyJobStatus.SUCCEEDED, ProxyJobStatus.FAILED, ProxyJobStatus.CANCELLED) } } }
                }
                    take = cleanupId?.let { selected -> queue.states.value.jobs.firstOrNull { it.id == selected }?.take } ?: take
                    val committed = result ?: take?.let { MediaProxyRepository(context).existing(it) }
                    committed?.let { resolver.delete(it.proxyUri.toUri(), null, null); resolver.delete(it.metadataUri.toUri(), null, null) }
                    resolver.delete(collection, "_id = ? AND owner_package_name = ?", arrayOf(uri.lastPathSegment, context.packageName))
                    take?.let { selected ->
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(selected.id.toByteArray()).proxyHex()
                        android.util.AtomicFile(File(context.filesDir, "media-proxies/$hash.json")).delete()
                    }
                } finally {
                    policies.update { before }
                    shell("dumpsys battery reset")
                    val restored = shell("dumpsys battery")
                    assertFalse(restored.contains("UPDATES STOPPED"))
                    android.util.Log.i("E17ProxyPolicy", "BATTERY_OVERRIDE_RETIRED policiesRestored=${policies.states.value == before} $restored")
                }
            }
        }
    }
}
