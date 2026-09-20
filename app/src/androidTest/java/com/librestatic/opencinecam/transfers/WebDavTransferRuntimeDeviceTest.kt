/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.net.Network
import android.net.Uri
import android.os.Parcel
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.storage.PreparedCaptureArtifact
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual private SQLite/Keystore/MediaStore and runtime; injected sockets do not qualify TLS. */
@RunWith(AndroidJUnit4::class)
class WebDavTransferRuntimeDeviceTest {
    @Test fun idleMediaMutationBlocksRequestsWithoutIoAndReleasesExactlyOnce() = Fixture().use { f ->
        val reads = AtomicInteger()
        val runtime = f.runtime(settingsFactory = { reads.incrementAndGet(); f.settings })
        val reservation = runtime.reserveIdleMediaMutation()
        try {
            runtime.refresh(); runtime.send(f.id)
            assertEquals(0, reads.get()); assertEquals(0, f.network.starts)
            assertFalse(runtime.states.value.busy)
            assertEquals(WebDavTransferMessage.WAITING_MEDIA, runtime.states.value.message)
            try { runtime.reserveIdleMediaMutation().close(); fail("Concurrent deletion must reject") }
            catch (_: IllegalStateException) { }
            val capture = runtime.reserveCapture()
            assertFalse("Capture must wait for actual local mutation retirement", capture.isRetired)
            capture.close()
            assertEquals(WebDavTransferMessage.WAITING_MEDIA, runtime.states.value.message)
            reservation.close(); reservation.close()
            assertTrue(capture.isRetired)
            assertEquals(WebDavTransferMessage.IDLE, runtime.states.value.message)
            val captureOnly = runtime.reserveCapture()
            try {
                try { runtime.reserveIdleMediaMutation().close(); fail("Active capture must reject deletion") }
                catch (_: IllegalStateException) { }
                assertEquals(WebDavTransferMessage.WAITING_RECORDING, runtime.states.value.message)
            } finally { captureOnly.close() }
            runtime.refresh(); awaitIdle(runtime)
            assertEquals(1, reads.get()); assertEquals(1, f.network.starts)
            assertEquals(WebDavTransferMessage.IDLE, runtime.states.value.message)
        } finally { reservation.close(); awaitIdle(runtime); runtime.closeForTest() }
    }

    @Test fun localRenameHoldRefreshesCachedQueueOnlyUnderReservationWithoutNewIo() = Fixture().use { f ->
        f.prepare()
        val reads = AtomicInteger()
        val runtime = f.runtime(settingsFactory = { reads.incrementAndGet(); f.settings })
        try {
            runtime.refresh(); awaitIdle(runtime)
            val before = runtime.states.value.bundles.single { it.id == f.id }
            try { runtime.reflectLocalMutation(listOf(before)); fail("Mutation reflection requires reservation") }
            catch (_: IllegalStateException) { }
            val settingsReads = reads.get(); val networkStarts = f.network.starts
            runtime.reserveIdleMediaMutation().use {
                val changed = WebDavSqliteOutbox(f.context, f.database).use { store ->
                    store.holdSourcesForLocalRename(before.artifacts.map { it.spec.sourceUri }.toSet())
                }
                runtime.reflectLocalMutation(changed)
                assertEquals(WebDavTransferMessage.WAITING_MEDIA, runtime.states.value.message)
                assertEquals(changed.single(), runtime.states.value.bundles.single { it.id == f.id })
                assertEquals(WebDavBundleState.ATTENTION, runtime.states.value.bundles.single { it.id == f.id }.state)
                assertEquals(settingsReads, reads.get()); assertEquals(networkStarts, f.network.starts)
            }
            assertEquals(WebDavBundleState.ATTENTION, runtime.states.value.bundles.single { it.id == f.id }.state)
        } finally { awaitIdle(runtime); runtime.closeForTest() }
    }

    @Test fun mediaMutationRejectsHeldSettingsWithoutCancellingTheWorker() = Fixture().use { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val runtime = f.runtime(settingsFactory = {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); f.settings
        })
        try {
            runtime.refresh(); assertTrue(entered.await(10, TimeUnit.SECONDS))
            try { runtime.reserveIdleMediaMutation().close(); fail("Pending settings owns transfer IO") }
            catch (_: IllegalStateException) { }
            assertTrue(runtime.states.value.busy)
            release.countDown(); awaitIdle(runtime)
            assertEquals(WebDavTransferMessage.IDLE, runtime.states.value.message)
            assertEquals(1, f.network.starts)
            runtime.reserveIdleMediaMutation().close()
        } finally { release.countDown(); awaitIdle(runtime); runtime.closeForTest() }
    }

    @Test fun mediaMutationRejectsHeldUploadWithoutCancellingOrTruncatingIt() = Fixture().use { f ->
        f.prepare()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val requests = mutableListOf<String>(); val uploaded = ByteArrayOutputStream()
        val runtime = f.runtime(connections = { _, _ -> Socket(uploaded, requests, onWrite = {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
        }) })
        try {
            runtime.refresh(); awaitIdle(runtime); runtime.send(f.id)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            try { runtime.reserveIdleMediaMutation().close(); fail("Live source ownership must reject deletion") }
            catch (_: IllegalStateException) { }
            assertTrue(runtime.states.value.busy)
            release.countDown(); awaitIdle(runtime)
            assertEquals(listOf("PUT", "GET"), requests)
            assertArrayEquals(Fixture.BYTES, uploaded.toByteArray())
            assertEquals(WebDavTransferMessage.COMPLETE, runtime.states.value.message)
            WebDavSqliteOutbox(f.context, f.database).use { assertEquals(WebDavBundleState.COMPLETE, it.load(f.id)!!.state) }
            runtime.reserveIdleMediaMutation().close()
        } finally { release.countDown(); awaitIdle(runtime); runtime.closeForTest() }
    }

    @Test fun idleCaptureReservationAndRejectedUiRequestsPerformNoSettingsOrNetworkIo() = Fixture().use { f ->
        val reads = AtomicInteger()
        val runtime = f.runtime(settingsFactory = { reads.incrementAndGet(); error("No settings during reserved REC") })
        lateinit var reservation: CaptureTransferReservation
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            reservation = runtime.reserveCapture()
            runtime.refresh(); runtime.send(f.id); runtime.cancel()
        }
        assertTrue(reservation.isRetired)
        assertEquals(0, reads.get())
        assertEquals(0, f.network.starts)
        assertEquals(WebDavTransferMessage.WAITING_RECORDING, runtime.states.value.message)
        reservation.close(); reservation.close()
        runtime.closeForTest()
    }

    @Test fun reservationCoversPendingSettingsIoNotJustAnUploadAttempt() = Fixture().use { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = f.runtime(settingsFactory = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); f.settings })
        runtime.refresh()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val reservation = runtime.reserveCapture()
        try {
            assertFalse(reservation.isRetired)
            assertEquals(WebDavTransferMessage.WAITING_RECORDING, runtime.states.value.message)
            release.countDown()
            runBlocking { withTimeout(10_000) { reservation.awaitRetired() } }
            assertTrue(reservation.isRetired)
            assertEquals(0, f.network.starts)
        } finally {
            release.countDown(); reservation.close()
            awaitIdle(runtime); runtime.closeForTest()
        }
    }

    @Test fun closingCaptureWaitBeforeHeldJobRetiresDoesNotLeaveStaleRecordingMessage() = Fixture().use { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val runtime = f.runtime(settingsFactory = {
            if (reads.incrementAndGet() == 1) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            f.settings
        })
        runtime.refresh()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val reservation = runtime.reserveCapture()
        try {
            assertFalse(reservation.isRetired)
            reservation.close() // User cancels capture preparation before old runtime I/O ends.
            assertTrue(runtime.states.value.busy)
            assertNotEquals(WebDavTransferMessage.WAITING_RECORDING, runtime.states.value.message)
            release.countDown()
            awaitIdle(runtime)
            assertEquals(WebDavTransferMessage.CANCELLED, runtime.states.value.message)
            assertTrue(reservation.isRetired)
            runtime.refresh()
            awaitIdle(runtime)
            assertEquals(2, reads.get())
            assertEquals(WebDavTransferMessage.IDLE, runtime.states.value.message)
        } finally {
            release.countDown(); reservation.close()
            awaitIdle(runtime); runtime.closeForTest()
        }
    }

    @Test fun explicitBundlePerformsOneConditionalPutAndOneGetThenCompletes() = Fixture().use { f ->
        f.prepare()
        val requests = mutableListOf<String>()
        val uploaded = ByteArrayOutputStream()
        val runtime = f.runtime(connections = { selected, uri ->
            assertEquals(f.network.selected, selected)
            assertEquals("https", uri.scheme)
            assertTrue(uri.rawPath.endsWith("remote-take.mp4"))
            Socket(uploaded, requests)
        })
        try {
            runtime.refresh(); awaitIdle(runtime)
            assertEquals(1, runtime.states.value.bundles.size)
            assertTrue(requests.isEmpty())
            runtime.send(f.id); awaitIdle(runtime)
            assertEquals(WebDavTransferMessage.COMPLETE, runtime.states.value.message)
            assertEquals(listOf("PUT", "GET"), requests)
            assertArrayEquals(Fixture.BYTES, uploaded.toByteArray())
            WebDavSqliteOutbox(f.context, f.database).use { assertEquals(WebDavBundleState.COMPLETE, it.load(f.id)!!.state) }
        } finally { runtime.closeForTest() }
    }

    @Test fun verified404ReturnsQueuedWithoutAutomaticSecondPut() = Fixture().use { f ->
        f.prepare()
        val requests = mutableListOf<String>()
        val runtime = f.runtime(connections = { _, _ -> Socket(ByteArrayOutputStream(), requests, getStatus = 404) })
        try {
            runtime.refresh(); awaitIdle(runtime)
            runtime.send(f.id); awaitIdle(runtime)
            assertEquals(listOf("PUT", "GET"), requests)
            assertEquals(WebDavTransferMessage.UNCERTAIN, runtime.states.value.message)
            WebDavSqliteOutbox(f.context, f.database).use { assertEquals(WebDavArtifactState.QUEUED, it.load(f.id)!!.artifacts.single().state) }
        } finally { runtime.closeForTest() }
    }

    @Test fun captureReservationWaitsForHeldTransferAndNeverResumesBatchAfterRelease() = Fixture().use { f ->
        f.prepare()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = mutableListOf<String>()
        val runtime = f.runtime(connections = { _, _ -> Socket(ByteArrayOutputStream(), requests, onWrite = {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
        }) })
        runtime.refresh(); awaitIdle(runtime)
        runtime.send(f.id)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        lateinit var reservation: CaptureTransferReservation
        InstrumentationRegistry.getInstrumentation().runOnMainSync { reservation = runtime.reserveCapture() }
        try {
            assertFalse(reservation.isRetired)
            release.countDown()
            runBlocking { withTimeout(10_000) { reservation.awaitRetired() } }
            assertTrue(reservation.isRetired)
            assertEquals(listOf("PUT"), requests)
            assertEquals(WebDavTransferMessage.WAITING_RECORDING, runtime.states.value.message)
            WebDavSqliteOutbox(f.context, f.database).use { assertEquals(WebDavArtifactState.UNCERTAIN, it.load(f.id)!!.artifacts.single().state) }
        } finally {
            release.countDown(); reservation.close()
            awaitIdle(runtime); runtime.closeForTest()
        }
    }

    @Test fun cellularAndOfflinePreventAnyRequestWithoutConsent() = Fixture().use { f ->
        f.prepare()
        f.network.kind = WebDavNetwork.CELLULAR
        val requests = mutableListOf<String>()
        val runtime = f.runtime(connections = { _, _ -> Socket(ByteArrayOutputStream(), requests) })
        try {
            runtime.refresh(); awaitIdle(runtime)
            runtime.send(f.id); awaitIdle(runtime)
            assertEquals(WebDavTransferMessage.CELLULAR_CONSENT_REQUIRED, runtime.states.value.message)
            assertTrue(requests.isEmpty())
            f.network.emit(null, WebDavNetwork.OFFLINE)
            runtime.send(f.id); awaitIdle(runtime)
            assertEquals(WebDavTransferMessage.NETWORK_UNAVAILABLE, runtime.states.value.message)
            assertTrue(requests.isEmpty())
        } finally { runtime.closeForTest() }
    }

    @Test fun activeNetworkIdentityChangeStopsWithoutMigratingToTheNewNetwork() = Fixture().use { f ->
        f.prepare()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val selected = mutableListOf<Network>()
        val requests = mutableListOf<String>()
        val runtime = f.runtime(connections = { network, _ ->
            selected.add(network)
            Socket(ByteArrayOutputStream(), requests, onWrite = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) })
        })
        try {
            runtime.refresh(); awaitIdle(runtime)
            runtime.send(f.id)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            f.network.emit(fixtureNetwork(456), WebDavNetwork.WIFI)
            release.countDown(); awaitIdle(runtime)
            assertEquals(listOf(f.network.selected), selected)
            assertEquals(listOf("PUT"), requests)
            assertEquals(WebDavTransferMessage.NETWORK_UNAVAILABLE, runtime.states.value.message)
        } finally {
            release.countDown(); awaitIdle(runtime); runtime.closeForTest()
        }
    }

    @Test fun liveConsentRevocationStopsHeldPutAndNeverAutomaticallyVerifies() = Fixture().use { f ->
        f.prepare()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = mutableListOf<String>()
        val runtime = f.runtime(connections = { _, _ -> Socket(ByteArrayOutputStream(), requests, onWrite = {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
        }) })
        try {
            runtime.refresh(); awaitIdle(runtime)
            runtime.send(f.id)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            f.settings.save("https://runtime.example/takes/", false, false)
            runBlocking { withTimeout(10_000) { runtime.states.first { it.message == WebDavTransferMessage.DISABLED } } }
            release.countDown(); awaitIdle(runtime)
            assertEquals(listOf("PUT"), requests)
            assertEquals(WebDavTransferMessage.DISABLED, runtime.states.value.message)
        } finally {
            release.countDown(); awaitIdle(runtime); runtime.closeForTest()
        }
    }

    private companion object {
        /**
         * Test-only parcel identities; injected sockets never route through these synthetic networks.
         * AOSP android-11.0.0_r1 Network.java lines 451-460 writes/reads exactly one netId Int:
         * https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/core/java/android/net/Network.java
         * No hidden constructor or fabricated fromNetworkHandle magic is used.
         */
        fun fixtureNetwork(id: Int): Network {
            require(id > 0)
            val parcel = Parcel.obtain()
            return try {
                parcel.writeInt(id)
                parcel.setDataPosition(0)
                val network = Network.CREATOR.createFromParcel(parcel)
                check(parcel.dataAvail() == 0) { "Network fixture parcel schema changed" }
                parcel.setDataSize(0)
                parcel.setDataPosition(0)
                network.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                check(parcel.readInt() == id && parcel.dataAvail() == 0) { "Network fixture parcel roundtrip changed" }
                network
            } finally { parcel.recycle() }
        }
    }

    private fun awaitIdle(runtime: WebDavTransferRuntime) = runBlocking {
        withTimeout(10_000) { runtime.states.first { !it.busy } }
    }

    private class TestNetwork : WebDavRuntimeNetworkSource {
        val selected = fixtureNetwork(123)
        var kind = WebDavNetwork.WIFI
        var starts = 0
        private var changed: ((Network?, WebDavNetwork) -> Unit)? = null
        override fun start(changed: (Network?, WebDavNetwork) -> Unit) { starts++; this.changed = changed; changed(selected, kind) }
        fun emit(network: Network?, type: WebDavNetwork) { changed!!.invoke(network, type) }
        override fun close() { changed = null }
    }

    private class Fixture : AutoCloseable {
        private val base = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val database = "runtime-$id.db"
        private val directory = File(base.cacheDir, "runtime-$id").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
        }
        val settings = WebDavQueueSettings(File(directory, "settings.json"))
        val network = TestNetwork()
        private var row: Uri? = null
        private var endpoint: String? = null
        fun runtime(settingsFactory: (Context) -> WebDavQueueSettings = { settings },
            connections: (Network, URI) -> HttpURLConnection = { _, _ -> error("Unexpected socket") }) =
            WebDavTransferRuntime(context, database, settingsFactory, network, connections)
        fun prepare() {
            val preferences = settings.save("https://runtime.example/takes/", true, false)
            endpoint = preferences.activeEndpointId!!
            WebDavCredentialStore(context).save(endpoint!!, "operator", "fixture-secret")
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "runtime-$id.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            row = uri
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(BYTES) }
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val name = requireNotNull(context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)).use {
                check(it.moveToFirst()); it.getString(0)
            }
            val snapshot = MediaStorePreparedArtifactProbe(context.contentResolver).inspect(PreparedCaptureArtifact(CaptureArtifactRole.VIDEO, uri.toString(), name), false)
            val spec = WebDavArtifactSpec(UUID.randomUUID().toString(), WebDavArtifactRole.VIDEO, uri.toString(), name, BYTES.size.toLong(), "remote-take.mp4")
            WebDavSqliteOutbox(context, database).use { db ->
                val staged = db.stage(WebDavBundlePlan(id, endpoint!!, 0L, listOf(spec)))
                assertNotNull(db.seal(id, staged.revision, listOf(WebDavArtifactPublication(spec.id, snapshot))))
            }
            CaptureTransferEnrollmentFile(context).enroll(CaptureTransferEnrollment(id, endpoint!!, 0, preferences.revision))
        }
        override fun close() {
            try { row?.let { context.contentResolver.delete(it, null, null) } }
            finally {
                try { endpoint?.let { WebDavCredentialStore(context).clear(it) } }
                finally { base.deleteDatabase(database); directory.deleteRecursively() }
            }
        }
        companion object { val BYTES = ByteArray(512) { it.toByte() } }
    }

    private class Socket(private val stored: ByteArrayOutputStream, private val requests: MutableList<String>,
        private val getStatus: Int = 200, private val onWrite: () -> Unit = {}) : HttpURLConnection(URI("https://runtime.example/").toURL()) {
        private var counted = false
        private fun count() { if (!counted) { requests.add(requestMethod); counted = true } }
        override fun getOutputStream(): OutputStream { count(); return object : OutputStream() {
            override fun write(value: Int) { stored.write(value); onWrite() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { stored.write(bytes, offset, length); onWrite() }
        } }
        override fun getResponseCode(): Int { count(); return if (requestMethod == "PUT") 201 else getStatus }
        override fun getHeaderFields(): Map<String, List<String>> = mapOf("Content-Length" to listOf(stored.size().toString()))
        override fun getInputStream() = ByteArrayInputStream(stored.toByteArray())
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
