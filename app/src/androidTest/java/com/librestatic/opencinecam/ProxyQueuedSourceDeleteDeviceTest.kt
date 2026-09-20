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
class ProxyQueuedSourceDeleteDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Test fun deletingWaitingOriginalStopsSameRequestBeforeProviderMutation() = runBlocking<Unit> {
        check(android.os.Build.FINGERPRINT.contains("generic")) { "Battery override fixture requires emulator" }
        val batteryBefore = shell("dumpsys battery")
        check(!batteryBefore.contains("UPDATES STOPPED")) { "Another battery override must not be overwritten" }
        val policies = ProxyPolicies.get(context); val before = policies.states.value
        val token = "proxy-source-delete-${UUID.randomUUID()}"
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
            val deleted = withContext(Dispatchers.IO) { MediaTakeDeleter(context).delete(take) }
            assertTrue(deleted.toString(), deleted.complete)
            val stopped = queue.states.value.jobs.single { it.id == id }
            assertEquals(ProxyJobStatus.FAILED, stopped.status)
            assertEquals(request.settings, stopped.settings)
            assertEquals(request.attempts, stopped.attempts)
            assertEquals(request.take, stopped.take)
            assertNotNull(stopped.error)
            assertNull(queue.states.value.waiting[id])
            assertTrue(LocalMediaRepository(context).page(GallerySettings(), token).takes.isEmpty())
            resolver.query(uri, arrayOf("_id"), null, null, null)!!.use { assertFalse(it.moveToFirst()) }
            policies.update { it.copy(minimumBatteryPercent = 0) }
            queue.conditionsChanged()
            assertEquals(stopped, queue.states.value.jobs.single { it.id == id })
            assertFalse(File(context.cacheDir, "proxy-$id.mp4").exists())
            assertNull(MediaProxyRepository(context).existing(take))
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
