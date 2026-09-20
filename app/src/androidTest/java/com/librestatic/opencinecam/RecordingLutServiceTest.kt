/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.*
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Network
import android.os.IBinder
import android.provider.MediaStore
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.service.CaptureService
import com.librestatic.opencinecam.storage.CaptureArtifactRole
import com.librestatic.opencinecam.transfers.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real service reservation, Camera2/GLES encoding, MediaStore and platform decode. Three-frame
 * time lapse permits the existing software-codec route; this is not physical LOG qualification. */
class RecordingLutServiceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun committedSelectionWinsBeforeMainCollectorAndFrozenTakeSurvivesDeletion() = fixture { f ->
        val green = f.add("green", "0 1 0")
        val red = f.add("red", "1 0 0")
        val blue = f.add("blue", "0 0 1")
        f.library.select(blue)
        f.library.selectRecording(null)
        f.start(blue)
        val before = f.rows()
        // Complete an actual disk commit without returning main to its queued StateFlow collector.
        // Admission must read the library's coherent publication, not yesterday's service mirror.
        compose.runOnUiThread {
            val commit = FutureTask { f.library.selectRecording(green) }
            Thread(commit, "e14-library-commit").start()
            commit.get(10, TimeUnit.SECONDS)
            assertTrue(f.owner.capturePrimary(false))
        }
        assertTrue(f.owner.cameraStates.value.transferRetirementPending)
        assertEquals(before, f.rows())
        assertNull(f.owner.cameraStates.value.lastSavedUri)
        f.library.selectRecording(red)
        f.library.delete(green)
        compose.waitUntil(10_000) { f.owner.cameraStates.value.recordingLutSelectionPending }
        assertEquals(red, f.library.activeSelections().recording?.cube?.sha256)
        assertEquals(blue, f.library.activeSelections().operator?.cube?.sha256)
        assertTrue(f.owner.cameraStates.value.structuralSettingsFrozen)
        f.release.countDown()
        f.awaitSaved()
        val first = requireNotNull(f.owner.cameraStates.value.lastSavedUri)
        f.verify(first, green, greenChannel = true)
        compose.waitUntil(15_000) {
            val state = f.owner.cameraStates.value
            !state.recordingLutSelectionPending && state.recordingLutStatus.hash == red &&
                !state.recordingFinalizing && !state.transferRetirementPending &&
                state.phase in setOf(CameraUiPhase.SAVED, CameraUiPhase.PREVIEWING)
        }
        assertEquals(blue, f.owner.cameraStates.value.operatorLutStatus.hash)
        compose.runOnUiThread { assertTrue(f.owner.capturePrimary(false)) }
        compose.waitUntil(25_000) {
            f.owner.cameraStates.value.lastSavedUri?.let { it != first } == true ||
                f.owner.cameraStates.value.phase == CameraUiPhase.ERROR
        }
        f.awaitSaved()
        val second = requireNotNull(f.owner.cameraStates.value.lastSavedUri)
        assertNotEquals(first, second)
        f.verify(second, red, greenChannel = false)
    }

    @Test fun incompatibleDomainHasNoOutputAndCancelledReservationNeverStartsLate() = fixture { f ->
        val incompatible = f.add("log", "0 1 0", LutSignalDomain.OCLOG2_CODE)
        val green = f.add("green", "0 1 0")
        val blue = f.add("blue", "0 0 1")
        f.library.select(blue)
        f.library.selectRecording(incompatible)
        f.start(blue)
        val before = f.rows()
        compose.runOnUiThread { assertFalse(f.owner.capturePrimary(false)) }
        assertEquals(before, f.rows())
        assertFalse(f.owner.cameraStates.value.transferRetirementPending)
        assertNull(f.owner.cameraStates.value.lastSavedUri)
        assertEquals(OperatorLutState.INCOMPATIBLE_DOMAIN, f.owner.cameraStates.value.recordingLutStatus.state)
        f.library.selectRecording(green)
        compose.waitUntil(10_000) { f.owner.cameraStates.value.recordingLutStatus.hash == green }
        compose.runOnUiThread {
            assertTrue(f.owner.capturePrimary(false))
            assertTrue(f.owner.cameraStates.value.transferRetirementPending)
            assertTrue(f.owner.stopRecording())
        }
        val preparation = field<Job>(f.service, "transferPreparationJob")
        f.release.countDown()
        runBlocking { withTimeout(15_000) { preparation.join() } }
        compose.waitUntil(15_000) { !f.runtime.states.value.busy }
        f.instrumentation.waitForIdleSync()
        barrier(field(f.engine, "cameraExecutor"))
        compose.runOnUiThread {
            assertEquals(CameraUiPhase.PREVIEWING, f.owner.cameraStates.value.phase)
            assertFalse(f.owner.cameraStates.value.transferRetirementPending)
            assertFalse(f.owner.cameraStates.value.recordingFinalizing)
            assertNull(f.owner.cameraStates.value.lastSavedUri)
        }
        assertEquals(before, f.rows())
        assertNotEquals(WebDavTransferMessage.WAITING_RECORDING, f.runtime.states.value.message)
    }

    private fun fixture(action: (Fixture) -> Unit) {
        val f = Fixture()
        try { action(f) } finally { f.close() }
    }

    private inner class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = SettingsRepositories.get(context)
        private val previous = repository.states.value
        val library = LutLibraries.get(context)
        private val previousSelections = library.states.value
        private val hashes = mutableListOf<String>()
        private val ownedRows = linkedSetOf<String>()
        private val savedByFixture = linkedSetOf<String>()
        private val id = UUID.randomUUID().toString()
        private val settingsFile = File(context.cacheDir, "e14-transfer-$id.json")
        private val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = WebDavTransferRuntime(context, "e14-$id.db", settingsFactory = {
            entered.countDown()
            check(release.await(60, TimeUnit.SECONDS)) { "Fixture transfer holder was not released" }
            WebDavQueueSettings(settingsFile)
        }, networkSource = object : WebDavRuntimeNetworkSource {
            override fun start(changed: (Network?, WebDavNetwork) -> Unit) = changed(null, WebDavNetwork.OFFLINE)
            override fun close() = Unit
        })
        private val binder = AtomicReference<CaptureService.LocalBinder?>()
        private val surface = AtomicReference<Surface?>()
        private var bound = false
        lateinit var owner: CaptureService.LocalBinder
        lateinit var service: CaptureService
        lateinit var engine: Camera2PreviewEngine
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binderValue: IBinder?) { binder.set(binderValue as CaptureService.LocalBinder) }
            override fun onServiceDisconnected(name: ComponentName?) { binder.set(null) }
        }
        init { assertNull("Preserve, never reset, an existing library", library.states.value.error) }

        fun add(name: String, color: String, input: LutSignalDomain = LutSignalDomain.SDR_BT709_CODE): String {
            val label = "e14-$name-$id"
            val bytes = ("TITLE \"$label\"\nLUT_3D_SIZE 2\n" + List(8) { color }.joinToString("\n") + "\n").toByteArray()
            return library.importLut(bytes, label, LutTransformKind.CREATIVE, input).hash.also { hashes += it }
        }

        fun start(operator: String) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
            library.selectSubject(null)
            compose.runOnUiThread {
                repository.set(CameraSettings(audioEnabled = false, videoWidth = 640, videoHeight = 480,
                    timelapseWidth = 640, timelapseHeight = 480, timelapseFps = 30, timelapseIntervalMs = 500,
                    timelapseLimitMode = TimeLapseLimitMode.FRAME_COUNT, timelapseFrameCount = 3,
                    operation = OperatorPreferences(startupMode = StartupMode.VIDEO, lockDuringTake = true)))
            }
            bound = context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            compose.waitUntil(10_000) { binder.get() != null }
            owner = requireNotNull(binder.get())
            service = field(owner, "this\$0")
            engine = CaptureService::class.java.getDeclaredMethod("getPreviewEngine").apply { isAccessible = true }.invoke(service) as Camera2PreviewEngine
            CaptureService::class.java.getDeclaredField("transferRuntime").apply { isAccessible = true }.set(service, runtime)
            runtime.refresh()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            compose.runOnUiThread { owner.prepare(640, 480); owner.selectMode(CaptureMode.TIME_LAPSE, reopen = false) }
            val descriptor = requireNotNull(owner.cameraStates.value.descriptor)
            compose.setContent { AndroidView(factory = { host -> SurfaceView(host).apply {
                holder.setFixedSize(descriptor.previewSize.width, descriptor.previewSize.height)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { surface.set(holder.surface) }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surface.set(holder.surface) }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { surface.set(null) }
                })
            } }, modifier = Modifier.fillMaxSize()) }
            compose.waitUntil(10_000) { surface.get()?.isValid == true }
            compose.runOnUiThread { assertTrue(owner.attachPreview(requireNotNull(surface.get()), 0)) }
            compose.waitUntil(25_000) {
                val state = owner.cameraStates.value
                (state.phase == CameraUiPhase.PREVIEWING && state.operatorLutStatus.state == OperatorLutState.ACTIVE) || state.phase == CameraUiPhase.ERROR
            }
            assertEquals(owner.cameraStates.value.message, CameraUiPhase.PREVIEWING, owner.cameraStates.value.phase)
            assertEquals(operator, owner.cameraStates.value.operatorLutStatus.hash)
            assertEquals(OperatorLutState.ACTIVE, owner.cameraStates.value.operatorLutStatus.state)
        }

        fun rows(): Set<String> = buildSet {
            for (collection in listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Downloads.EXTERNAL_CONTENT_URI)) {
                requireNotNull(context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?", arrayOf(context.packageName), null)).use { cursor ->
                    while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0)).toString())
                }
            }
        }
        fun awaitSaved() {
            compose.waitUntil(30_000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.SAVED, CameraUiPhase.ERROR) }
            assertEquals(owner.cameraStates.value.message, CameraUiPhase.SAVED, owner.cameraStates.value.phase)
            savedByFixture += requireNotNull(owner.cameraStates.value.lastSavedUri)
            assertEquals(3, owner.cameraStates.value.timelapseFramesCaptured)
        }
        private fun receipt(uri: String): CapturePublicationReceipt {
            var after: String? = null
            while (true) {
                val page = CapturePublicationJournal.list(context, limit = 100, afterBundleId = after)
                page.firstOrNull { it.artifacts.any { artifact -> artifact.role == CaptureArtifactRole.VIDEO && artifact.uri == uri } }?.let { return it }
                check(page.isNotEmpty()) { "No receipt for this service take: $uri" }
                after = page.last().bundleId
            }
        }
        fun verify(uri: String, expectedHash: String, greenChannel: Boolean) {
            val receipt = receipt(uri)
            ownedRows += receipt.artifacts.map { it.uri }
            assertEquals(CapturePublicationState.COMMITTED, receipt.state)
            assertEquals(setOf(CaptureArtifactRole.VIDEO, CaptureArtifactRole.VIDEO_METADATA), receipt.artifacts.map { it.role }.toSet())
            for (artifact in receipt.artifacts) {
                requireNotNull(context.contentResolver.query(artifact.uri.toUri(), arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
            val metadataUri = receipt.artifacts.single { it.role == CaptureArtifactRole.VIDEO_METADATA }.uri
            val metadata = requireNotNull(context.contentResolver.openInputStream(metadataUri.toUri())).bufferedReader().use { JSONObject(it.readText()) }
            val lut = metadata.getJSONObject("recordingLut")
            assertTrue(lut.getBoolean("baked")); assertFalse(lut.getBoolean("reapplyInEditor"))
            assertEquals(expectedHash, lut.getString("originalCubeSha256"))
            assertEquals("$expectedHash:CREATIVE:SDR_BT709_CODE:SDR_BT709_CODE", lut.getString("selectionId"))
            assertEquals("CREATIVE", lut.getString("kind")); assertEquals("SDR_BT709_CODE", lut.getString("input"))
            assertEquals("SDR_BT709_CODE", lut.getString("output")); assertEquals(2, lut.getInt("size"))
            assertEquals("BT.709", lut.getString("colorPrimaries")); assertEquals("limited", lut.getString("sampleRange"))
            assertEquals("SDR video", lut.getString("colorTransfer"))
            for (channel in 0..2) {
                assertEquals(0.0, lut.getJSONArray("domainMin").getDouble(channel), 0.0)
                assertEquals(1.0, lut.getJSONArray("domainMax").getDouble(channel), 0.0)
            }
            val movie = File(context.cacheDir, "e14-$id-${receipt.bundleId}.mp4")
            val sidecar = File(context.cacheDir, "e14-$id-${receipt.bundleId}.json")
            requireNotNull(context.contentResolver.openInputStream(uri.toUri())).use { input -> movie.outputStream().use { input.copyTo(it) } }
            sidecar.writeText(metadata.toString(2))
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(movie.path)
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertTrue(requireNotNull(format.getString(MediaFormat.KEY_MIME)).startsWith("video/"))
                assertEquals(MediaFormat.COLOR_STANDARD_BT709, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
                assertEquals(MediaFormat.COLOR_RANGE_LIMITED, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
                extractor.selectTrack(0)
                val pts = mutableListOf<Long>()
                while (extractor.sampleTime >= 0) { pts += extractor.sampleTime; extractor.advance() }
                assertEquals(listOf(0L, 33333L, 66666L), pts)
            } finally { extractor.release() }
            val decoder = MediaMetadataRetriever()
            try {
                decoder.setDataSource(movie.path)
                val bitmap = requireNotNull(decoder.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                try {
                    for (y in 1..6) for (x in 1..6) {
                        val pixel = bitmap.getPixel(x * bitmap.width / 7, y * bitmap.height / 7)
                        val primary = if (greenChannel) Color.green(pixel) else Color.red(pixel)
                        val secondary = if (greenChannel) Color.red(pixel) else Color.green(pixel)
                        assertTrue("Encoded frame must match frozen LUT, not independent blue monitor: ${Integer.toHexString(pixel)}",
                            primary > 210 && secondary < 40 && Color.blue(pixel) < 40)
                    }
                } finally { bitmap.recycle() }
            } finally { decoder.release() }
            val sha = MessageDigest.getInstance("SHA-256").digest(movie.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            android.util.Log.i("RecordingLutServiceProbe", JSONObject().put("bundleId", receipt.bundleId).put("videoUri", uri)
                .put("cubeSha256", expectedHash).put("fileSha256", sha).put("metadataUri", metadataUri)
                .put("movieFixture", movie.name).put("sidecarFixture", sidecar.name).put("frames", 3)
                .put("decodedFrozenColor", if (greenChannel) "GREEN" else "RED").put("baked", true).put("reapplyInEditor", false).toString())
        }

        override fun close() {
            release.countDown() // Always unblock local I/O before any producer retirement.
            try {
                if (::owner.isInitialized) {
                    compose.runOnUiThread { owner.stopRecording(); owner.detachPreview() }
                    if (::engine.isInitialized) engine.closeAsync().get(20, TimeUnit.SECONDS)
                    if (::service.isInitialized) barrier(field(service, "storageExecutor"))
                    instrumentation.waitForIdleSync()
                    // No broad before/after deletion: only exact receipts of this fixture's saves.
                    savedByFixture.forEach { uri -> ownedRows += receipt(uri).artifacts.map { it.uri } }
                }
                compose.waitUntil(20_000) { !runtime.states.value.busy && runtime.states.value.message != WebDavTransferMessage.WAITING_RECORDING }
                runtime.closeForTest()
            } finally {
                try {
                    if (bound) context.unbindService(connection)
                    instrumentation.waitForIdleSync()
                    library.select(previousSelections.operatorHash)
                    library.selectSubject(previousSelections.subjectHash)
                    library.selectRecording(previousSelections.recordingHash)
                    hashes.forEach { hash -> if (library.states.value.entries.any { it.hash == hash }) library.delete(hash) }
                } finally {
                    compose.runOnUiThread { repository.set(previous) }
                    ownedRows.forEach { context.contentResolver.delete(it.toUri(), null, null) }
                    context.deleteDatabase("e14-$id.db")
                    settingsFile.delete()
                }
            }
        }
    }
    private fun barrier(executor: Executor) {
        val done = CountDownLatch(1); executor.execute { done.countDown() }
        assertTrue("Executor did not retire owned work", done.await(20, TimeUnit.SECONDS))
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(owner: Any, name: String): T =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
}
