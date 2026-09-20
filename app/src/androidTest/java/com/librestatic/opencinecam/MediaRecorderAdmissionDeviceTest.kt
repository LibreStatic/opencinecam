/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A real direct Camera2 session distinguishes skipped admission from later resource cleanup. */
class MediaRecorderAdmissionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clicks = AtomicInteger()

    @Test fun queuedMediaRecorderStartLeavesNativePreviewUntouchedAfterCloseIntentBeforeCleanup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val admission = CaptureOwnerAdmission()
        val first = Camera2PreviewEngine(context, ownerAdmission = admission)
        val successor = Camera2PreviewEngine(context, ownerAdmission = admission)
        val firstProbe = Probe()
        val successorProbe = Probe()
        val release = CountDownLatch(1)
        val holdFailure = AtomicReference<Throwable?>()
        val outputFile = File.createTempFile("media-recorder-admission-", ".mp4", context.cacheDir)
        try {
            ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                try {
                    val descriptor = first.descriptors(640, 480).first()
                    val surface = showSurface(descriptor)
                    compose.runOnUiThread { first.startPreview(descriptor, surface, 0, firstProbe, gpuPreview = false) }
                    awaitPreview(firstProbe)
                    assertFalse("This regression must exercise MediaRecorder, not GPU", first.usesGpuViewfinder())
                    val executor = field(first, "cameraExecutor") as Executor
                    val held = CompletableFuture<NativePreview>()
                    executor.execute {
                        try {
                            assertNull(field(first, "logPipeline"))
                            assertNull(field(first, "recorder"))
                            assertNull(field(first, "recordSurface"))
                            val camera = field(first, "camera") as CameraDevice
                            val session = field(first, "session") as CameraCaptureSession
                            assertSame(camera, session.device)
                            held.complete(NativePreview(camera, session, field(first, "jpegReader"),
                                field(first, "rawReader"), field(first, "analysisReader")))
                            check(release.await(60, TimeUnit.SECONDS)) { "Camera executor hold deadline expired" }
                        } catch (failure: Throwable) {
                            holdFailure.set(failure)
                            held.completeExceptionally(failure)
                        }
                    }
                    val before = held.get(5, TimeUnit.SECONDS)
                    assertNotNull("Direct preview owns its real JPEG reader", before.jpegReader)
                    val checkpoint = CompletableFuture<Unit>()
                    // FIFO order is hold -> accepted start -> checkpoint -> close cleanup.
                    // disposed is set synchronously by closeAsync while all four are pending.
                    compose.runOnUiThread {
                        assertTrue("Request was accepted before close intent", first.startVideo(output, audio = null))
                    }
                    executor.execute {
                        try {
                            holdFailure.get()?.let { throw it }
                            assertTrue((field(first, "disposed") as java.util.concurrent.atomic.AtomicBoolean).get())
                            assertFalse("Observe admission BEFORE cleanup, not cleanup's empty snapshot", field(first, "closeStarted") as Boolean)
                            assertSame(before.camera, field(first, "camera"))
                            assertSame("Stale admission must not close or replace the real session", before.session, field(first, "session"))
                            assertSame(before.jpegReader, field(first, "jpegReader"))
                            assertSame(before.rawReader, field(first, "rawReader"))
                            assertSame(before.analysisReader, field(first, "analysisReader"))
                            assertNull(field(first, "recorder"))
                            assertNull(field(first, "recordSurface"))
                            assertFalse(field(first, "recording") as Boolean)
                            assertEquals(0, firstProbe.recordings.get())
                            assertTrue(firstProbe.failures.toString(), firstProbe.failures.isEmpty())
                            assertTrue(output.fileDescriptor.valid())
                            assertEquals("Skipped recorder must not write a container header", 0L, output.statSize)
                            checkpoint.complete(Unit)
                        } catch (failure: Throwable) { checkpoint.completeExceptionally(failure) }
                    }
                    val closed = first.closeAsync()
                    assertFalse(closed.isDone)
                    var acceptedAfterClose = true
                    compose.runOnUiThread {
                        acceptedAfterClose = first.startVideo(output, audio = null)
                    }
                    compose.onNodeWithTag("recorder-admission-heartbeat").performClick()
                    compose.runOnIdle { assertEquals(1, clicks.get()) }
                    release.countDown()
                    checkpoint.get(10, TimeUnit.SECONDS)
                    closed.get(15, TimeUnit.SECONDS)
                    assertFalse("Close intent rejects new calls even before native cleanup", acceptedAfterClose)
                    assertNull(holdFailure.get())
                    assertNull(field(first, "camera")); assertNull(field(first, "closingCamera"))
                    assertNull(field(first, "recorder")); assertNull(field(first, "recordSurface"))
                    assertEquals(0, firstProbe.recordings.get())
                    assertEquals(0, firstProbe.stopped.get())
                    assertTrue(firstProbe.failures.toString(), firstProbe.failures.isEmpty())
                    assertTrue("Engine never owns the caller output descriptor", output.fileDescriptor.valid())
                    assertEquals(0L, output.statSize)
                    assertTrue("Engine never owns the caller preview Surface", surface.isValid)
                    compose.runOnUiThread { successor.startPreview(descriptor, surface, 0, successorProbe, gpuPreview = false) }
                    awaitPreview(successorProbe)
                    assertFalse(successor.usesGpuViewfinder())
                    assertEquals(0, successorProbe.recordings.get())
                    android.util.Log.i("MediaRecorderAdmissionProbe", "realDirectPreview=true queuedBeforeClose=true checkpointBeforeCleanup=true sameSessionAndReaders=true recorderAbsent=true outputBytes=0 lateCallRejected=true uiResponsive=true nativeCloseAwaited=true sameSurfaceSuccessorPreview=true")
                } finally {
                    release.countDown()
                    val completions = listOf(first.closeAsync(), successor.closeAsync())
                    completions.forEach { it.get(15, TimeUnit.SECONDS) }
                }
            }
        } finally {
            release.countDown()
            // Also cover failure while opening the PFD before entering its use block.
            val completions = listOf(first.closeAsync(), successor.closeAsync())
            try { completions.forEach { it.get(15, TimeUnit.SECONDS) } }
            finally { assertTrue("Delete only this fixture's output", outputFile.delete()) }
        }
    }

    @Test fun duplicateQueuedMediaRecorderStartRejectsSecondOutputAndRetiresBeforeThirdTake() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val engine = Camera2PreviewEngine(context)
        val probe = Probe()
        val release = CountDownLatch(1)
        val holdFailure = AtomicReference<Throwable?>()
        val files = mutableListOf<File>()
        val outputs = mutableListOf<ParcelFileDescriptor>()
        try {
            repeat(3) {
                val file = File.createTempFile("media-recorder-duplicate-$it-", ".mp4", context.cacheDir)
                files += file
                outputs += ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            }
            val first = outputs[0]
            val rejected = outputs[1]
            val third = outputs[2]
            val descriptor = engine.descriptors(640, 480).first()
            val surface = showSurface(descriptor)
            compose.runOnUiThread { engine.startPreview(descriptor, surface, 0, probe, gpuPreview = false) }
            awaitPreview(probe)
            assertFalse("Exercise real MediaRecorder rather than GPU", engine.usesGpuViewfinder())
            val executor = field(engine, "cameraExecutor") as Executor
            val held = CompletableFuture<NativePreview>()
            executor.execute {
                try {
                    assertNull(field(engine, "logPipeline"))
                    assertNull(field(engine, "recorder"))
                    assertNull(field(engine, "recordSurface"))
                    val camera = field(engine, "camera") as CameraDevice
                    val session = field(engine, "session") as CameraCaptureSession
                    assertSame(camera, session.device)
                    held.complete(NativePreview(camera, session, field(engine, "jpegReader"),
                        field(engine, "rawReader"), field(engine, "analysisReader")))
                    check(release.await(60, TimeUnit.SECONDS)) { "Camera executor hold deadline expired" }
                } catch (failure: Throwable) {
                    holdFailure.set(failure)
                    held.completeExceptionally(failure)
                }
            }
            val before = held.get(5, TimeUnit.SECONDS)
            assertNotNull(before.jpegReader)
            val metadataBefore = probe.metadata.get()
            compose.runOnUiThread {
                assertTrue("First request reserves admission before returning", engine.startVideo(first, audio = null))
                assertFalse("Duplicate queued request must reject synchronously", engine.startVideo(rejected, audio = null))
            }
            assertTrue(first.fileDescriptor.valid())
            assertTrue("Rejected caller PFD remains open", rejected.fileDescriptor.valid())
            assertEquals("Rejected request never writes even a header", 0L, rejected.statSize)
            assertEquals(0, probe.recordings.get())
            val checkpoint = CompletableFuture<Pair<Any, Any>>()
            // FIFO hold -> first native prepare -> checkpoint; no close/cleanup is requested.
            // This observes actual ownership, not a historical count of recorder constructions.
            executor.execute {
                try {
                    holdFailure.get()?.let { throw it }
                    assertFalse((field(engine, "disposed") as java.util.concurrent.atomic.AtomicBoolean).get())
                    assertFalse(field(engine, "closeStarted") as Boolean)
                    assertSame(before.camera, field(engine, "camera"))
                    val recorder = requireNotNull(field(engine, "recorder"))
                    val recordSurface = requireNotNull(field(engine, "recordSurface"))
                    assertTrue(first.fileDescriptor.valid())
                    assertTrue(rejected.fileDescriptor.valid())
                    assertEquals(0L, rejected.statSize)
                    assertTrue(probe.failures.toString(), probe.failures.isEmpty())
                    checkpoint.complete(recorder to recordSurface)
                } catch (failure: Throwable) { checkpoint.completeExceptionally(failure) }
            }
            compose.onNodeWithTag("recorder-admission-heartbeat").performClick()
            compose.runOnIdle { assertEquals(1, clicks.get()) }
            release.countDown()
            val nativeOwner = checkpoint.get(15, TimeUnit.SECONDS)
            assertNull(holdFailure.get())
            compose.waitUntil(15_000) { probe.recordings.get() > 0 || probe.failures.isNotEmpty() }
            assertTrue(probe.failures.toString(), probe.failures.isEmpty())
            assertEquals("Exactly one accepted take starts", 1, probe.recordings.get())
            val activeCheckpoint = CompletableFuture<Unit>()
            executor.execute {
                try {
                    assertSame(nativeOwner.first, field(engine, "recorder"))
                    assertSame(nativeOwner.second, field(engine, "recordSurface"))
                    assertTrue(field(engine, "recording") as Boolean)
                    activeCheckpoint.complete(Unit)
                } catch (failure: Throwable) { activeCheckpoint.completeExceptionally(failure) }
            }
            activeCheckpoint.get(5, TimeUnit.SECONDS)
            val metadataAtStart = probe.metadata.get()
            // Real repeating results, not a sleep or assumed codec frame count, pace the take.
            compose.waitUntil(10_000) { probe.metadata.get() >= metadataAtStart + 3 || probe.failures.isNotEmpty() }
            assertTrue(probe.failures.toString(), probe.failures.isEmpty())
            assertTrue(probe.metadata.get() > metadataBefore)
            compose.runOnUiThread { assertTrue(engine.stopVideo()) }
            compose.waitUntil(15_000) { probe.stopped.get() > 0 || probe.failures.isNotEmpty() }
            assertTrue(probe.failures.toString(), probe.failures.isEmpty())
            assertEquals(1, probe.stopped.get())
            assertEquals(listOf(true), probe.stopResults.toList())
            verifyRecorderMovie(files[0])
            assertTrue(first.fileDescriptor.valid())
            assertTrue(rejected.fileDescriptor.valid())
            assertEquals(0L, rejected.statSize)
            compose.waitUntil(15_000) { probe.previews.get() >= 2 || probe.failures.isNotEmpty() }
            assertTrue(probe.failures.toString(), probe.failures.isEmpty())
            val retired = CompletableFuture<Unit>()
            executor.execute {
                try {
                    assertNull(field(engine, "recorder")); assertNull(field(engine, "recordSurface"))
                    assertFalse(field(engine, "recording") as Boolean)
                    retired.complete(Unit)
                } catch (failure: Throwable) { retired.completeExceptionally(failure) }
            }
            retired.get(5, TimeUnit.SECONDS)
            compose.runOnUiThread { assertTrue("Retired reservation admits a third request", engine.startVideo(third, audio = null)) }
            compose.waitUntil(15_000) { probe.recordings.get() >= 2 || probe.failures.isNotEmpty() }
            assertTrue(probe.failures.toString(), probe.failures.isEmpty())
            assertEquals(2, probe.recordings.get())
            val thirdMetadata = probe.metadata.get()
            compose.waitUntil(10_000) { probe.metadata.get() >= thirdMetadata + 3 || probe.failures.isNotEmpty() }
            assertTrue(probe.failures.toString(), probe.failures.isEmpty())
            compose.runOnUiThread { assertTrue(engine.stopVideo()) }
            compose.waitUntil(15_000) { probe.stopped.get() >= 2 || probe.failures.isNotEmpty() }
            assertTrue(probe.failures.toString(), probe.failures.isEmpty())
            assertEquals(2, probe.recordings.get()); assertEquals(2, probe.stopped.get())
            assertEquals(listOf(true, true), probe.stopResults.toList())
            verifyRecorderMovie(files[2])
            outputs.forEach { assertTrue("Engine does not close caller PFDs", it.fileDescriptor.valid()) }
            assertEquals(0L, rejected.statSize)
            assertTrue(surface.isValid)
            android.util.Log.i("MediaRecorderAdmissionProbe", "realDirectPreview=true duplicateQueuedRejectedSynchronously=true rejectedOutputBytes=0 checkpointBeforeCleanup=true firstStartStop=1 successfulStops=2 thirdTakeAfterRetirement=true decodedMovies=2 nativeMetadataAdvanced=true uiResponsive=true")
        } finally {
            // Retirement must precede closing borrowed PFDs, including baseline RED assertion exit.
            release.countDown()
            try { engine.closeAsync().get(20, TimeUnit.SECONDS) }
            finally {
                outputs.forEach { it.close() }
                files.forEach { assertTrue("Delete only this fixture's output", it.delete()) }
            }
        }
    }

    @Test fun duplicateStopWhileExecutorIsHeldHasOneSuccessfulTerminalCallbackAndOneMovie() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val engine = Camera2PreviewEngine(context)
        val probe = Probe()
        val release = CountDownLatch(1)
        val holdFailure = AtomicReference<Throwable?>()
        val file = File.createTempFile("media-recorder-duplicate-stop-", ".mp4", context.cacheDir)
        var output: ParcelFileDescriptor? = null
        try {
            val borrowed = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            output = borrowed
            val descriptor = engine.descriptors(640, 480).first()
            val surface = showSurface(descriptor)
            compose.runOnUiThread { engine.startPreview(descriptor, surface, 0, probe, gpuPreview = false) }
            awaitPreview(probe)
            assertFalse(engine.usesGpuViewfinder())
            compose.runOnUiThread { assertTrue(engine.startVideo(borrowed, audio = null)) }
            compose.waitUntil(15_000) { probe.recordings.get() == 1 || probe.failures.isNotEmpty() }
            assertTrue(probe.failures.toString(), probe.failures.isEmpty())
            assertEquals(1, probe.recordings.get())
            val metadataAtStart = probe.metadata.get()
            compose.waitUntil(10_000) { probe.metadata.get() >= metadataAtStart + 3 || probe.failures.isNotEmpty() }
            assertTrue(probe.failures.toString(), probe.failures.isEmpty())
            val executor = field(engine, "cameraExecutor") as Executor
            val held = CompletableFuture<List<Any>>()
            executor.execute {
                try {
                    assertTrue(field(engine, "recording") as Boolean)
                    assertNull(field(engine, "logPipeline"))
                    held.complete(listOf(requireNotNull(field(engine, "recorder")), requireNotNull(field(engine, "recordSurface")),
                        requireNotNull(field(engine, "session"))))
                    check(release.await(60, TimeUnit.SECONDS)) { "Camera executor hold deadline expired" }
                } catch (failure: Throwable) { holdFailure.set(failure); held.completeExceptionally(failure) }
            }
            val owner = held.get(5, TimeUnit.SECONDS)
            compose.runOnUiThread {
                assertTrue("First stop reserves terminal ownership synchronously", engine.stopVideo())
                assertFalse("Second stop must reject before the first runnable executes", engine.stopVideo())
            }
            assertEquals(0, probe.stopped.get())
            assertTrue(probe.stopResults.isEmpty())
            assertTrue(field(engine, "recording") as Boolean)
            assertSame(owner[0], field(engine, "recorder"))
            assertSame(owner[1], field(engine, "recordSurface"))
            assertSame(owner[2], field(engine, "session"))
            val checkpoint = CompletableFuture<Unit>()
            executor.execute {
                try {
                    holdFailure.get()?.let { throw it }
                    assertEquals(1, probe.recordings.get())
                    assertEquals(1, probe.stopped.get())
                    assertEquals(listOf(true), probe.stopResults.toList())
                    assertNull(field(engine, "recorder"))
                    assertNull(field(engine, "recordSurface"))
                    assertFalse(field(engine, "recording") as Boolean)
                    assertNull((field(engine, "recorderRequest") as AtomicReference<*>).get())
                    assertTrue(borrowed.fileDescriptor.valid())
                    checkpoint.complete(Unit)
                } catch (failure: Throwable) { checkpoint.completeExceptionally(failure) }
            }
            compose.onNodeWithTag("recorder-admission-heartbeat").performClick()
            compose.runOnIdle { assertEquals(1, clicks.get()) }
            release.countDown()
            checkpoint.get(15, TimeUnit.SECONDS)
            compose.waitUntil(15_000) { probe.previews.get() >= 2 || probe.failures.isNotEmpty() }
            assertTrue(probe.failures.toString(), probe.failures.isEmpty())
            assertEquals(1, probe.recordings.get())
            assertEquals(1, probe.stopped.get())
            assertEquals(listOf(true), probe.stopResults.toList())
            verifyRecorderMovie(file)
            assertTrue(borrowed.fileDescriptor.valid())
            assertTrue(surface.isValid)
            android.util.Log.i("MediaRecorderAdmissionProbe", "duplicateStopRejectedBeforeExecutorRelease=true successfulTerminalCallbacks=1 decodedMovies=1 directPreviewRestored=true callerPfdAndSurfaceRetained=true")
        } finally {
            release.countDown()
            try { engine.closeAsync().get(20, TimeUnit.SECONDS) }
            finally { try { output?.close() } finally { assertTrue("Delete only this fixture output", file.delete()) } }
        }
    }

    @Test fun queuedStartObsoleteByPreviewGenerationSkipsBeforeReopenAndSameEngineRecordsSuccessor() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val engine = Camera2PreviewEngine(context)
        val initialProbe = Probe()
        val successorProbe = Probe()
        val release = CountDownLatch(1)
        val holdFailure = AtomicReference<Throwable?>()
        val files = mutableListOf<File>()
        val outputs = mutableListOf<ParcelFileDescriptor>()
        try {
            repeat(2) { index ->
                val file = File.createTempFile("media-recorder-generation-$index-", ".mp4", context.cacheDir)
                files += file
                outputs += ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            }
            val skipped = outputs[0]
            val successful = outputs[1]
            val descriptor = engine.descriptors(640, 480).first()
            val surface = showSurface(descriptor)
            compose.runOnUiThread { engine.startPreview(descriptor, surface, 0, initialProbe, gpuPreview = false) }
            awaitPreview(initialProbe)
            assertFalse(engine.usesGpuViewfinder())
            val executor = field(engine, "cameraExecutor") as Executor
            val held = CompletableFuture<Pair<Long, CameraDevice>>()
            executor.execute {
                try {
                    assertNull(field(engine, "recorder"))
                    assertNull(field(engine, "logPipeline"))
                    held.complete((field(engine, "generation") as Long) to (field(engine, "camera") as CameraDevice))
                    check(release.await(60, TimeUnit.SECONDS)) { "Camera executor hold deadline expired" }
                } catch (failure: Throwable) { holdFailure.set(failure); held.completeExceptionally(failure) }
            }
            val initial = held.get(5, TimeUnit.SECONDS)
            val afterReplacement = CompletableFuture<List<Any?>>()
            val afterSkippedStart = CompletableFuture<Unit>()
            val unchangedFields = listOf("camera", "closingCamera", "session", "pendingPreviewStart", "jpegReader", "rawReader", "analysisReader", "recorder", "recordSurface")
            // All tasks are queued while held, so onClosed/reopen callbacks caused by replacement
            // enter behind these checkpoints. Replacement itself legitimately closes the old preview.
            compose.runOnUiThread { engine.startPreview(descriptor, surface, 0, successorProbe, gpuPreview = false) }
            executor.execute {
                try {
                    holdFailure.get()?.let { throw it }
                    assertEquals(initial.first + 1L, field(engine, "generation"))
                    assertFalse((field(engine, "disposed") as java.util.concurrent.atomic.AtomicBoolean).get())
                    assertFalse(field(engine, "closeStarted") as Boolean)
                    assertNull(field(engine, "camera"))
                    assertSame(initial.second, field(engine, "closingCamera"))
                    assertNotNull(field(engine, "pendingPreviewStart"))
                    assertNull(field(engine, "recorder")); assertNull(field(engine, "recordSurface"))
                    assertNull((field(engine, "recorderRequest") as AtomicReference<*>).get())
                    assertEquals(0L, skipped.statSize)
                    assertEquals(0, successorProbe.previews.get())
                    afterReplacement.complete(unchangedFields.map { field(engine, it) })
                } catch (failure: Throwable) { afterReplacement.completeExceptionally(failure) }
            }
            compose.runOnUiThread {
                // startPreview has not executed yet: startVideo captures the old live camera and
                // generation here, then must reject that same snapshot inside its queued runnable.
                assertEquals(initial.first, field(engine, "generation"))
                assertTrue("The request is accepted against the still-live old preview", engine.startVideo(skipped, audio = null))
            }
            executor.execute {
                try {
                    holdFailure.get()?.let { throw it }
                    val beforeSkip = afterReplacement.getNow(null)
                    assertNotNull("Replacement checkpoint must have run first", beforeSkip)
                    unchangedFields.forEachIndexed { index, name ->
                        assertSame("Stale start mutated $name after replacement and before reopen", requireNotNull(beforeSkip)[index], field(engine, name))
                    }
                    assertEquals(initial.first + 1L, field(engine, "generation"))
                    assertFalse((field(engine, "disposed") as java.util.concurrent.atomic.AtomicBoolean).get())
                    assertFalse(field(engine, "closeStarted") as Boolean)
                    assertNull((field(engine, "recorderRequest") as AtomicReference<*>).get())
                    assertFalse(field(engine, "recording") as Boolean)
                    assertEquals(0, initialProbe.recordings.get()); assertEquals(0, initialProbe.stopped.get())
                    assertEquals(0, successorProbe.recordings.get()); assertEquals(0, successorProbe.stopped.get())
                    assertEquals(0, successorProbe.previews.get())
                    assertTrue(initialProbe.failures.toString(), initialProbe.failures.isEmpty())
                    assertTrue(successorProbe.failures.toString(), successorProbe.failures.isEmpty())
                    assertEquals(0L, skipped.statSize)
                    assertTrue(skipped.fileDescriptor.valid())
                    afterSkippedStart.complete(Unit)
                } catch (failure: Throwable) { afterSkippedStart.completeExceptionally(failure) }
            }
            compose.onNodeWithTag("recorder-admission-heartbeat").performClick()
            compose.runOnIdle { assertEquals(1, clicks.get()) }
            release.countDown()
            afterReplacement.get(15, TimeUnit.SECONDS)
            afterSkippedStart.get(15, TimeUnit.SECONDS)
            awaitPreview(successorProbe)
            assertNull(holdFailure.get())
            assertTrue(surface.isValid)
            assertSame("The same borrowed Surface serves the new generation", surface, field(engine, "previewSurface"))
            assertFalse((field(engine, "disposed") as java.util.concurrent.atomic.AtomicBoolean).get())
            assertFalse(engine.usesGpuViewfinder())
            assertEquals(0L, skipped.statSize)
            compose.runOnUiThread { assertTrue("Live successor generation accepts a real take", engine.startVideo(successful, audio = null)) }
            compose.waitUntil(15_000) { successorProbe.recordings.get() == 1 || successorProbe.failures.isNotEmpty() }
            assertTrue(successorProbe.failures.toString(), successorProbe.failures.isEmpty())
            assertEquals(1, successorProbe.recordings.get())
            val metadataAtStart = successorProbe.metadata.get()
            compose.waitUntil(10_000) { successorProbe.metadata.get() >= metadataAtStart + 3 || successorProbe.failures.isNotEmpty() }
            assertTrue(successorProbe.failures.toString(), successorProbe.failures.isEmpty())
            compose.runOnUiThread { assertTrue(engine.stopVideo()) }
            compose.waitUntil(15_000) { successorProbe.stopped.get() == 1 || successorProbe.failures.isNotEmpty() }
            assertTrue(successorProbe.failures.toString(), successorProbe.failures.isEmpty())
            assertEquals(listOf(true), successorProbe.stopResults.toList())
            verifyRecorderMovie(files[1])
            assertEquals(0L, skipped.statSize)
            outputs.forEach { assertTrue("Engine never closes borrowed caller descriptors", it.fileDescriptor.valid()) }
            assertTrue(surface.isValid)
            android.util.Log.i("MediaRecorderAdmissionProbe", "previewGenerationReplacementWithoutDispose=true staleStartSkippedBeforeReopen=true checkpointFieldsUnchanged=true skippedOutputBytes=0 sameEngineAndSurfaceSuccessor=true decodedSuccessorMovies=1 successfulStops=1")
        } finally {
            release.countDown()
            try { engine.closeAsync().get(20, TimeUnit.SECONDS) }
            finally {
                outputs.forEach { it.close() }
                files.forEach { assertTrue("Delete only this fixture output", it.delete()) }
            }
        }
    }

    private fun verifyRecorderMovie(file: File) {
        assertTrue("Recorder produces a nonempty MP4", file.length() > 0)
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            var samples = 0
            var previousPts = -1L
            while (extractor.sampleTime >= 0) {
                assertTrue("Video PTS must advance", extractor.sampleTime > previousPts)
                previousPts = extractor.sampleTime
                samples++
                extractor.advance()
            }
            assertTrue("Native encoder must produce video samples", samples > 0)
        } finally { extractor.release() }
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val frame = retriever.getFrameAtTime(0)
            assertNotNull("Finalized recorder MP4 must decode", frame)
            frame?.let { try { assertTrue(it.width > 0 && it.height > 0) } finally { it.recycle() } }
        } finally { retriever.release() }
    }

    private data class NativePreview(val camera: CameraDevice, val session: CameraCaptureSession,
        val jpegReader: Any?, val rawReader: Any?, val analysisReader: Any?)

    private fun showSurface(descriptor: Camera2CameraDescriptor): Surface {
        val output = AtomicReference<Surface?>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { host -> SurfaceView(host).apply {
                        holder.setFixedSize(descriptor.previewSize.width, descriptor.previewSize.height)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) { output.set(holder.surface) }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { output.set(holder.surface) }
                            override fun surfaceDestroyed(holder: SurfaceHolder) { output.set(null) }
                        })
                    } }, modifier = Modifier.fillMaxSize())
                    Button(onClick = { clicks.incrementAndGet() }, modifier = Modifier.align(Alignment.TopStart).testTag("recorder-admission-heartbeat")) {
                        Text("UI heartbeat")
                    }
                }
            }
        }
        compose.waitUntil(10_000) { output.get()?.isValid == true }
        return requireNotNull(output.get())
    }

    private fun awaitPreview(probe: Probe) {
        compose.waitUntil(15_000) { probe.previews.get() > 0 || probe.failures.isNotEmpty() }
        assertTrue(probe.failures.toString(), probe.failures.isEmpty())
        assertEquals(1, probe.previews.get())
        compose.waitUntil(5_000) { probe.metadata.get() > 0 || probe.failures.isNotEmpty() }
        assertTrue(probe.failures.toString(), probe.failures.isEmpty())
        assertTrue("Actual Camera2 results must follow preview", probe.metadata.get() > 0)
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private class Probe : Camera2PreviewListener {
        val previews = AtomicInteger()
        val metadata = AtomicInteger()
        val recordings = AtomicInteger()
        val stopped = AtomicInteger()
        val stopResults = CopyOnWriteArrayList<Boolean>()
        val failures = CopyOnWriteArrayList<String>()
        override fun onOpening(descriptor: Camera2CameraDescriptor) = Unit
        override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) { previews.incrementAndGet() }
        override fun onMetadata(metadata: Camera2PreviewMetadata) { this.metadata.incrementAndGet() }
        override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
        override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
        override fun onAnalysis(analysis: Camera2Analysis) = Unit
        override fun onRecordingStarted(width: Int, height: Int) { recordings.incrementAndGet() }
        override fun onRecordingStopped(success: Boolean) { stopResults += success; stopped.incrementAndGet() }
        override fun onFailure(code: String, message: String, recoverable: Boolean) { failures += "$code: $message" }
    }
}
