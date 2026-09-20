/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real native recorder ownership; HFR coverage is explicitly callback injection, not qualification. */
class MediaRecorderFailureDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun outputDescriptorFailureReleasesOwnedRecorderAndAllowsDecodedRecoveryTake() = withFixture { f ->
        val rejected = f.output()
        val captured = AtomicReference<MediaRecorder?>()
        val accesses = AtomicInteger()
        val throwing = object : ParcelFileDescriptor(rejected.second) {
            override fun getFileDescriptor(): FileDescriptor {
                accesses.incrementAndGet()
                captured.set(field(f.engine, "recorder") as MediaRecorder?)
                throw IOException("Injected descriptor access failure")
            }
        }
        // Keep the wrapper alive through retirement as well as its borrowed underlying PFD.
        f.wrappers += throwing
        compose.runOnUiThread { assertTrue(f.engine.startVideo(throwing, audio = null)) }
        compose.waitUntil(15_000) { f.probe.failures.isNotEmpty() }
        assertEquals(listOf("video-prepare-failed"), f.probe.failures.toList())
        f.expectedFailures = 1
        assertEquals(1, accesses.get())
        val owned = requireNotNull(captured.get()) { "Recorder must be owned before setOutputFile accesses the descriptor" }
        f.onCamera {
            f.assertRetired()
            try { owned.reset(); fail("Released native recorder reset must fail") }
            catch (_: IllegalStateException) { /* Native object was actually released. */ }
        }
        assertEquals(0, f.probe.recordings.get()); assertTrue(f.probe.stops.isEmpty())
        assertTrue(rejected.second.fileDescriptor.valid()); assertEquals(0L, rejected.second.statSize)
        f.openPreview()
        val recovered = f.output()
        f.start(recovered.second)
        f.stopAndVerify(recovered.first)
        assertTrue(rejected.second.fileDescriptor.valid()); assertEquals(0L, rejected.second.statSize)
        assertEquals(listOf(true), f.probe.stops.toList())
        android.util.Log.i("MediaRecorderFailureProbe", "case=descriptor ownedBeforeSetter=true nativeReleased=true rejectedBytes=0 borrowedPfdOpen=true recoveryMovieDecoded=true")
    }

    @Test fun staleRegularCallbacksCannotRetireReconfiguredRecorderOrNewTake() = withFixture { f ->
        val configurations = CopyOnWriteArrayList<SessionConfiguration>()
        f.observe { configurations += it }
        val first = f.output()
        f.start(first.second)
        val old = f.onCamera { f.snapshot() }
        val initialCallback = configurations.single().stateCallback
        assertEquals(SessionConfiguration.SESSION_REGULAR, configurations.single().sessionType)
        compose.runOnUiThread { assertTrue(f.engine.detachPreviewWhileRecording()) }
        f.awaitMetadata(2)
        assertEquals(2, configurations.size)
        val detached = f.onCamera { f.snapshot() }
        assertSame(old.recorder, detached.recorder)
        assertSame(old.request, detached.request)
        assertNotSame(old.session, detached.session)
        f.replay(initialCallback, old.session, detached)
        f.awaitMetadata(2)
        assertEquals(1, f.probe.recordings.get()); assertTrue(f.probe.stops.isEmpty())
        f.stopAndVerify(first.first)
        // Detach left no preview Surface in the engine; explicitly reopen on the same caller Surface.
        f.openPreview()
        val next = f.output()
        f.start(next.second)
        val current = f.onCamera { f.snapshot() }
        assertNotSame(old.recorder, current.recorder)
        assertNotSame(old.request, current.request)
        f.replay(initialCallback, old.session, current)
        f.replay(configurations[1].stateCallback, detached.session, current)
        f.awaitMetadata(2)
        assertEquals(2, f.probe.recordings.get()); assertEquals(listOf(true), f.probe.stops.toList())
        f.stopAndVerify(next.first)
        assertEquals(listOf(true, true), f.probe.stops.toList())
        assertTrue(first.second.fileDescriptor.valid()); assertTrue(next.second.fileDescriptor.valid())
        android.util.Log.i("MediaRecorderFailureProbe", "case=regularCallback sameRecorderNewEpochProtected=true nextRecorderOwnerProtected=true oldNativeSessionsOnly=true metadataAdvanced=true decodedMovies=2")
    }

    @Test fun injectedHighSpeedConfigurationFailureCannotPoisonNextRegularTake() = withFixture { f ->
        val captured = AtomicReference<SessionConfiguration?>()
        val capturedRecorder = AtomicReference<MediaRecorder?>()
        val oldPreview = f.onCamera { field(f.engine, "session") as CameraCaptureSession }
        f.onCamera {
            setField(f.engine, "activeVideoProfile", Camera2VideoProfile(f.descriptor.previewSize, 30, true))
        }
        f.observe { configuration ->
            assertEquals(SessionConfiguration.SESSION_HIGH_SPEED, configuration.sessionType)
            captured.set(configuration)
            capturedRecorder.set(field(f.engine, "recorder") as MediaRecorder?)
            throw IOException("Injected HFR configuration failure before native submit")
        }
        val failed = f.output()
        compose.runOnUiThread { assertTrue(f.engine.startVideo(failed.second, audio = null)) }
        compose.waitUntil(15_000) { f.probe.failures.isNotEmpty() }
        assertEquals(listOf("high-speed-video-session-exception"), f.probe.failures.toList())
        f.expectedFailures = 1
        val stale = requireNotNull(captured.get())
        val retiredRecorder = requireNotNull(capturedRecorder.get())
        f.onCamera {
            f.assertRetired()
            try { retiredRecorder.reset(); fail("Failed configuration must release the owned native recorder") }
            catch (_: IllegalStateException) { }
            setField(f.engine, "activeVideoProfile", null)
        }
        assertEquals(0, f.probe.recordings.get()); assertTrue(f.probe.stops.isEmpty())
        assertTrue(failed.second.fileDescriptor.valid())
        val regular = CopyOnWriteArrayList<SessionConfiguration>()
        f.observe { regular += it }
        f.openPreview()
        val recovery = f.output()
        f.start(recovery.second)
        assertEquals(SessionConfiguration.SESSION_REGULAR, regular.single().sessionType)
        val current = f.onCamera { f.snapshot() }
        assertNotSame(retiredRecorder, current.recorder)
        assertNotSame(oldPreview, current.session)
        // No HFR session was submitted. Replay its saved callbacks with an OLD REAL preview session.
        f.replay(stale.stateCallback, oldPreview, current)
        f.awaitMetadata(2)
        assertEquals(1, f.probe.recordings.get()); assertTrue(f.probe.stops.isEmpty())
        f.stopAndVerify(recovery.first)
        assertEquals(listOf(true), f.probe.stops.toList())
        assertTrue(failed.second.fileDescriptor.valid()); assertTrue(recovery.second.fileDescriptor.valid())
        android.util.Log.i("MediaRecorderFailureProbe", "case=injectedHfrCallback physicalHfrQualified=false nativeHfrSubmitted=false failedRecorderReleased=true nextRegularOwnerProtected=true recoveryMovieDecoded=true")
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val f = Fixture()
        try { f.openPreview(); block(f) }
        finally { f.close() }
    }

    private inner class Fixture {
        val engine = Camera2PreviewEngine(context)
        val descriptor = engine.descriptors(640, 480).first()
        val probe = Probe()
        val files = mutableListOf<File>()
        val outputs = mutableListOf<ParcelFileDescriptor>()
        val wrappers = mutableListOf<ParcelFileDescriptor>()
        var expectedFailures = 0
        private val surface = AtomicReference<Surface?>()
        init {
            compose.setContent {
                AndroidView(factory = { host -> SurfaceView(host).apply {
                    holder.setFixedSize(descriptor.previewSize.width, descriptor.previewSize.height)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { surface.set(holder.surface) }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surface.set(holder.surface) }
                        override fun surfaceDestroyed(holder: SurfaceHolder) { surface.set(null) }
                    })
                } }, modifier = Modifier.fillMaxSize())
            }
        }
        fun openPreview() {
            compose.waitUntil(10_000) { surface.get()?.isValid == true }
            val count = probe.previews.get()
            compose.runOnUiThread { engine.startPreview(descriptor, requireNotNull(surface.get()), 0, probe, gpuPreview = false) }
            compose.waitUntil(15_000) { probe.previews.get() > count || probe.failures.size > expectedFailures }
            healthy(); assertEquals(count + 1, probe.previews.get())
            assertFalse(engine.usesGpuViewfinder())
            awaitMetadata(1)
        }
        fun output(): Pair<File, ParcelFileDescriptor> {
            val file = File.createTempFile("media-recorder-failure-", ".mp4", context.cacheDir)
            files += file
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            outputs += pfd
            return file to pfd
        }
        fun observe(observer: (SessionConfiguration) -> Unit) = onCamera {
            setField(engine, "recordingSessionObserver", observer)
        }
        fun <T> onCamera(action: () -> T): T {
            val result = CompletableFuture<T>()
            (field(engine, "cameraExecutor") as Executor).execute {
                try { result.complete(action()) } catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
            return result.get(15, TimeUnit.SECONDS)
        }
        fun healthy() = assertEquals(probe.failures.toString(), expectedFailures, probe.failures.size)
        fun awaitMetadata(count: Int) {
            val before = probe.metadata.get()
            compose.waitUntil(10_000) { probe.metadata.get() >= before + count || probe.failures.size > expectedFailures }
            healthy(); assertTrue(probe.metadata.get() >= before + count)
        }
        fun start(output: ParcelFileDescriptor) {
            val before = probe.recordings.get()
            compose.runOnUiThread { assertTrue(engine.startVideo(output, audio = null)) }
            compose.waitUntil(15_000) { probe.recordings.get() > before || probe.failures.size > expectedFailures }
            healthy(); assertEquals(before + 1, probe.recordings.get())
            awaitMetadata(1)
        }
        fun snapshot(): RecordingSnapshot = RecordingSnapshot(
            field(engine, "recorder") as MediaRecorder,
            (field(engine, "recorderRequest") as AtomicReference<*>).get()!!,
            field(engine, "session") as CameraCaptureSession,
            field(engine, "recordSurface") as Surface,
        )
        fun replay(callback: CameraCaptureSession.StateCallback, oldSession: CameraCaptureSession, current: RecordingSnapshot) {
            val starts = probe.recordings.get()
            val stops = probe.stops.size
            onCamera {
                assertNotSame(oldSession, current.session)
                callback.onConfigureFailed(oldSession)
                assertCurrent(current)
                callback.onConfigured(oldSession)
                assertCurrent(current)
                assertEquals(starts, probe.recordings.get()); assertEquals(stops, probe.stops.size)
                healthy()
            }
        }
        private fun assertCurrent(expected: RecordingSnapshot) {
            val actual = snapshot()
            assertSame(expected.recorder, actual.recorder); assertSame(expected.request, actual.request)
            assertSame(expected.session, actual.session); assertSame(expected.surface, actual.surface)
            assertTrue(actual.surface.isValid); assertTrue(field(engine, "recording") as Boolean)
        }
        fun assertRetired() {
            assertNull(field(engine, "recorder")); assertNull(field(engine, "recordSurface"))
            assertNull((field(engine, "recorderRequest") as AtomicReference<*>).get())
            assertFalse(field(engine, "recording") as Boolean)
        }
        fun stopAndVerify(file: File) {
            awaitMetadata(3)
            val stops = probe.stops.size
            compose.runOnUiThread { assertTrue(engine.stopVideo()) }
            compose.waitUntil(15_000) { probe.stops.size > stops || probe.failures.size > expectedFailures }
            healthy(); assertEquals(stops + 1, probe.stops.size); assertTrue(probe.stops.last())
            onCamera { assertRetired() }
            verifyMovie(file)
        }
        fun close() {
            // No held executor or timer grants retirement. PFDs close only after the actual engine future.
            engine.closeAsync().get(20, TimeUnit.SECONDS)
            try { wrappers.forEach { it.close() }; outputs.forEach { it.close() } }
            finally { files.forEach { assertTrue("Delete only fixture bytes after native retirement", it.delete()) } }
        }
    }

    private data class RecordingSnapshot(val recorder: MediaRecorder, val request: Any,
        val session: CameraCaptureSession, val surface: Surface)

    private fun verifyMovie(file: File) {
        assertTrue(file.length() > 0)
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val video = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(video)
            var samples = 0
            while (extractor.sampleTime >= 0) { samples++; extractor.advance() }
            assertTrue("Native MP4 must contain video samples", samples > 0)
        } finally { extractor.release() }
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val frame = retriever.getFrameAtTime(0)
            assertNotNull("Native MP4 must decode", frame)
            frame?.let { try { assertTrue(it.width > 0 && it.height > 0) } finally { it.recycle() } }
        } finally { retriever.release() }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun setField(target: Any, name: String, value: Any?) { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }

    private class Probe : Camera2PreviewListener {
        val previews = AtomicInteger()
        val metadata = AtomicInteger()
        val recordings = AtomicInteger()
        val stops = CopyOnWriteArrayList<Boolean>()
        val failures = CopyOnWriteArrayList<String>()
        override fun onOpening(descriptor: Camera2CameraDescriptor) = Unit
        override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) { previews.incrementAndGet() }
        override fun onMetadata(metadata: Camera2PreviewMetadata) { this.metadata.incrementAndGet() }
        override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
        override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
        override fun onAnalysis(analysis: Camera2Analysis) = Unit
        override fun onRecordingStarted(width: Int, height: Int) { recordings.incrementAndGet() }
        override fun onRecordingStopped(success: Boolean) { stops += success }
        override fun onFailure(code: String, message: String, recoverable: Boolean) { failures += code }
    }
}
