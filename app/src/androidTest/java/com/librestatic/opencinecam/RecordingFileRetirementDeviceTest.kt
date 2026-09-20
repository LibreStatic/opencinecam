/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.RecordingFrameSize
import com.librestatic.opencinecam.camera.RecordingGeometry
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** File receipts observe real duplicated descriptors and codec cleanup, not fabricated releases. */
class RecordingFileRetirementDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun geometry(width: Int = 128, height: Int = 96): RecordingGeometry {
        val frame = RecordingFrameSize(width, height)
        return RecordingGeometry(RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, frame, frame, frame)
    }
    private fun descriptor(file: File): ParcelFileDescriptor = ParcelFileDescriptor.open(file,
        ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
    private fun descriptorCount(file: File): Int = requireNotNull(File("/proc/self/fd").listFiles()).count { fd ->
        runCatching { File(Os.readlink(fd.path)).canonicalPath == file.canonicalPath }.getOrDefault(false)
    }

    @Test fun initialReceiptIsCompletedAndEveryViewIsIndependent() {
        SubjectPreviewGpuTest.Fixture().use { fixture ->
            val first = fixture.pipeline.recordingFileRetirement()
            val second = fixture.pipeline.recordingFileRetirement()
            assertNotSame(first, second)
            assertEquals(Unit, first.get(1, TimeUnit.SECONDS))
            assertFalse(first.completeExceptionally(IllegalStateException("consumer mutation")))
            assertFalse(second.cancel(true))
            assertEquals(Unit, fixture.pipeline.recordingFileRetirement().get(1, TimeUnit.SECONDS))
        }
    }

    @Test fun rejectedSourceGeometryRetiresItsRealDuplicateWithoutPoisoningClose() {
        val file = File.createTempFile("recording-retirement-invalid-", ".mp4", context.cacheDir)
        try {
            SubjectPreviewGpuTest.Fixture().use { fixture ->
                descriptor(file).use { output ->
                    assertEquals(1, descriptorCount(file))
                    val before = fixture.pipeline.recordingFileRetirement()
                    assertFalse(fixture.pipeline.startRecording(output, 2_000_000, geometry(64, 48),
                        onStarted = { fail("Invalid source geometry started native recording") },
                        onStopped = { _, _ -> fail("Rejected preparation produced a recording callback") }))
                    val retired = fixture.pipeline.recordingFileRetirement()
                    assertNotSame(before, retired)
                    assertEquals(Unit, retired.get(5, TimeUnit.SECONDS))
                    assertEquals(1, descriptorCount(file))
                    assertTrue(output.fileDescriptor.valid())
                    assertEquals(0L, file.length())
                    assertEquals(1, fixture.failures.size)
                    assertTrue(fixture.failures.single().startsWith("log-recording-prepare-failed:"))
                    fixture.closeSource()
                    assertEquals(Unit, fixture.pipeline.closeAsync().get(8, TimeUnit.SECONDS))
                }
            }
            assertEquals(0, descriptorCount(file))
        } finally { check(file.delete() || !file.exists()) }
    }

    @Test fun pendingReceiptCannotBeForgedAndSuccessfulCallbackObservesAlreadyRetiredFile() {
        val file = File.createTempFile("recording-retirement-success-", ".mp4", context.cacheDir)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable?>()
        try {
            // Existing fixture selects an advertised AVC codec; no media capability is fabricated.
            SubjectPreviewGpuTest.Fixture(embeddedAudio = true, onEncoderSelection = {
                entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
            }).use { fixture ->
                descriptor(file).use { output ->
                    val attempt = FutureTask<Boolean> {
                        fixture.pipeline.startRecording(output, 2_000_000, geometry(), onStarted = {},
                            onStopped = { success, evidence ->
                                try {
                                    assertTrue("Real AVC take must finalize", success)
                                    assertEquals(12L, requireNotNull(evidence).encodedFrames)
                                    val receipt = fixture.pipeline.recordingFileRetirement()
                                    assertTrue("Receipt must precede terminal callback", receipt.isDone)
                                    assertEquals(Unit, receipt.get(1, TimeUnit.SECONDS))
                                    assertEquals("Only the caller descriptor remains", 1, descriptorCount(file))
                                } catch (failure: Throwable) { callbackFailure.set(failure) }
                                finally { stopped.countDown() }
                            })
                    }
                    val caller = Thread(attempt, "RecordingReceiptCaller").apply { start() }
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        assertEquals("Preparation owns a real duplicate", 2, descriptorCount(file))
                        val observed = fixture.pipeline.recordingFileRetirement()
                        val forged = fixture.pipeline.recordingFileRetirement()
                        val cancelled = fixture.pipeline.recordingFileRetirement()
                        assertFalse(observed.isDone)
                        assertTrue(forged.complete(Unit))
                        assertTrue(cancelled.cancel(true))
                        assertFalse("Consumer mutation cannot retire native owner", observed.isDone)
                        assertFalse(fixture.pipeline.recordingFileRetirement().isDone)
                        release.countDown()
                        assertTrue(attempt.get(10, TimeUnit.SECONDS))
                        assertFalse(observed.isDone)
                        repeat(12) {
                            val timestamp = SystemClock.elapsedRealtimeNanos()
                            fixture.sourceFrame(timestamp)
                            awaitConsumed(fixture, timestamp)
                        }
                        assertTrue(fixture.pipeline.stopRecording())
                        assertTrue(stopped.await(12, TimeUnit.SECONDS))
                        callbackFailure.get()?.let { throw it }
                        assertEquals(Unit, observed.get(1, TimeUnit.SECONDS))
                        assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
                    } finally {
                        release.countDown()
                        caller.join(15_000)
                        check(!caller.isAlive) { "Preparation caller has not retired" }
                        fixture.pipeline.stopRecording()
                        fixture.pipeline.recordingFileRetirement().get(15, TimeUnit.SECONDS)
                    }
                }
            }
            assertEquals(0, descriptorCount(file))
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val bitmap = requireNotNull(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                try { assertEquals(128, bitmap.width); assertEquals(96, bitmap.height) }
                finally { bitmap.recycle() }
            } finally { retriever.release() }
        } finally { release.countDown(); check(file.delete() || !file.exists()) }
    }

    /** Wait for GL consumption, not codec lookahead output; no coalesced source frames. */
    private fun awaitConsumed(fixture: SubjectPreviewGpuTest.Fixture, timestamp: Long) {
        val handler = OpenCineLogGpuPipeline::class.java.getDeclaredField("handler").apply { isAccessible = true }
            .get(fixture.pipeline) as Handler
        val texture = OpenCineLogGpuPipeline::class.java.getDeclaredField("surfaceTexture").apply { isAccessible = true }
            .get(fixture.pipeline) as SurfaceTexture
        val completion = CompletableFuture<Unit>()
        val deadline = SystemClock.elapsedRealtime() + 10_000
        val probe = object : Runnable {
            override fun run() {
                if (completion.isDone) return
                try {
                    check(fixture.failures.isEmpty()) { fixture.failures.toString() }
                    val observed = texture.timestamp
                    if (observed == timestamp) completion.complete(Unit)
                    else {
                        check(observed < timestamp && SystemClock.elapsedRealtime() < deadline) { "GL did not consume frame $timestamp; observed $observed" }
                        check(handler.postDelayed(this, 1))
                    }
                } catch (failure: Throwable) { completion.completeExceptionally(failure) }
            }
        }
        check(handler.post(probe))
        try { completion.get(11, TimeUnit.SECONDS) }
        finally { completion.cancel(false); handler.removeCallbacks(probe) }
    }
}
