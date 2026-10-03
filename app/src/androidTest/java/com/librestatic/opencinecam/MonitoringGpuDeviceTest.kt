/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.opengl.EGL14
import android.opengl.EGLDisplay
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES30
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Real SDR GLES readback in texture/signal coordinates; not a physical display-alignment verdict. */
class MonitoringGpuDeviceTest {
    private val enabled = MonitoringOptions(waveformEnabled = true, vectorscopeEnabled = true,
        falseColorEnabled = true, refreshHz = 10, zebraHighPercent = 15,
        falseColorBlackPercent = 10, falseColorShadowPercent = 20,
        falseColorHighlightPercent = 70, falseColorClipPercent = 90)
    private fun <T> field(owner: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
    private fun handler(fixture: SubjectPreviewGpuTest.Fixture) = field<Handler>(fixture.pipeline, "handler")
    private fun onGl(fixture: SubjectPreviewGpuTest.Fixture, action: () -> Unit) {
        val result = CompletableFuture<Unit>()
        check(handler(fixture).post { try { action(); result.complete(Unit) } catch (failure: Throwable) { result.completeExceptionally(failure) } })
        result.get(10, TimeUnit.SECONDS)
    }
    private fun options(fixture: SubjectPreviewGpuTest.Fixture, frames: MutableList<Camera2Analysis>, value: MonitoringOptions) =
        onGl(fixture) { fixture.pipeline.setMonitoringOptions(value); frames.clear() }

    private fun submit(fixture: SubjectPreviewGpuTest.Fixture, stripes: Boolean = false) {
        val timestamp = SystemClock.elapsedRealtimeNanos()
        if (!stripes) fixture.sourceFrame(timestamp) else {
            // Borrow the fixture's existing producer owner without changing or releasing its graph.
            val display = field<EGLDisplay>(fixture, "sourceDisplay")
            val window = field<EGLSurface>(fixture, "sourceWindow")
            check(EGL14.eglMakeCurrent(display, window, window, field<EGLContext>(fixture, "sourceContext")))
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(1f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(0f, 0f, 1f, 1f)
            for (x in 2 until 128 step 4) { GLES30.glScissor(x, 0, 2, 96); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT) }
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
            check(EGLExt.eglPresentationTimeANDROID(display, window, timestamp)); check(EGL14.eglSwapBuffers(display, window))
        }
        val texture = field<SurfaceTexture>(fixture.pipeline, "surfaceTexture")
        val done = CompletableFuture<Unit>(); val until = SystemClock.elapsedRealtime() + 10_000
        val probe = object : Runnable {
            override fun run() {
                if (done.isDone) return
                try {
                    check(fixture.failures.isEmpty()) { fixture.failures.toString() }
                    val observed = texture.timestamp
                    if (observed == timestamp) done.complete(Unit) else {
                        check(observed < timestamp && SystemClock.elapsedRealtime() < until)
                        check(handler(fixture).postDelayed(this, 1))
                    }
                } catch (failure: Throwable) { done.completeExceptionally(failure) }
            }
        }
        check(handler(fixture).post(probe))
        try { done.get(11, TimeUnit.SECONDS) }
        finally { done.cancel(false); handler(fixture).removeCallbacks(probe) }
    }
    private fun awaitFrames(fixture: SubjectPreviewGpuTest.Fixture, frames: List<Camera2Analysis>, count: Int = 1,
        stripes: Boolean = false): Camera2Analysis {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (frames.size < count && SystemClock.elapsedRealtime() < until) submit(fixture, stripes)
        assertTrue("GLES analysis did not reach $count frames: ${fixture.failures}", frames.size >= count)
        return frames.last()
    }

    @Test fun realReadbackProducesRequestedGridsAndUsesConfiguredZebraAndFalseColorThresholds() {
        val frames = CopyOnWriteArrayList<Camera2Analysis>()
        SubjectPreviewGpuTest.Fixture(onAnalysisFrame = { frames += it }).use { fixture ->
            options(fixture, frames, enabled)
            val first = awaitFrames(fixture, frames)
            val scope = requireNotNull(first.scopes)
            assertEquals(MonitoringSignalDomain.SDR_BT709_CODE, scope.domain)
            assertEquals(160, scope.sampledWidth); assertEquals(90, scope.sampledHeight); assertEquals(14400, scope.sampleCount)
            assertEquals(4096, scope.waveformDensity.size); assertEquals(4096, scope.vectorscopeCounts.size)
            assertEquals(2304, scope.falseColorBands.size)
            assertEquals(14400, scope.waveformDensity.sum()); assertEquals(14400, scope.vectorscopeCounts.sum())
            assertTrue(scope.vectorscopeCounts[24] > 0); assertTrue(scope.vectorscopeCounts[34 * 64 + 63] > 0)
            assertEquals(1f, first.histogram.sum(), 0.001f)
            assertTrue(first.zebraCells[0]); assertFalse(first.zebraCells[15])
            assertEquals(FalseColorBand.MID, scope.falseColorBands[0]); assertEquals(FalseColorBand.BLACK, scope.falseColorBands[63])
            options(fixture, frames, enabled.copy(zebraHighPercent = 30))
            assertTrue(awaitFrames(fixture, frames).zebraCells.none { it })
            options(fixture, frames, enabled.copy(zebraHighPercent = 30, zebraShadowEnabled = true, zebraLowPercent = 10))
            val shadow = awaitFrames(fixture, frames)
            assertFalse(shadow.zebraCells[0]); assertTrue(shadow.zebraCells[15])
            options(fixture, frames, MonitoringOptions(refreshHz = 10))
            val disabled = requireNotNull(awaitFrames(fixture, frames).scopes)
            assertTrue(disabled.waveformDensity.isEmpty()); assertTrue(disabled.vectorscopeCounts.isEmpty()); assertTrue(disabled.falseColorBands.isEmpty())
        }
    }

    @Test fun spatialPeakingThresholdAndRequestedCadenceAreObservedWithoutAssumingExactRate() {
        val frames = CopyOnWriteArrayList<Camera2Analysis>()
        SubjectPreviewGpuTest.Fixture(onAnalysisFrame = { frames += it }).use { fixture ->
            options(fixture, frames, enabled.copy(peakingThreshold = 1))
            assertNull(awaitFrames(fixture, frames, stripes = true).focusPeaking)
            onGl(fixture) { fixture.pipeline.setFocusPeakingEnabled(true); frames.clear() }
            val peaking = awaitFrames(fixture, frames, stripes = true)
            val mask = requireNotNull(peaking.focusPeaking)
            assertEquals(320, mask.width); assertEquals(180, mask.height); assertTrue(mask.edgeCount > 0)
            assertEquals(MonitoringSignalDomain.SDR_BT709_CODE, mask.domain)
            // The finer readback is for the mask only; the scopes keep their sample grid.
            val scope = requireNotNull(peaking.scopes)
            assertEquals(160, scope.sampledWidth); assertEquals(90, scope.sampledHeight)
            options(fixture, frames, enabled.copy(peakingThreshold = 255))
            assertEquals(0, requireNotNull(awaitFrames(fixture, frames, stripes = true).focusPeaking).edgeCount)
            onGl(fixture) { fixture.pipeline.setFocusPeakingEnabled(false); frames.clear() }
            val slow = enabled.copy(refreshHz = 1)
            options(fixture, frames, slow)
            awaitFrames(fixture, frames, count = 3)
            val timestamps = frames.map { it.capturedAtElapsedRealtimeMs }
            assertTrue(timestamps.size >= 3)
            assertTrue(timestamps.zipWithNext().all { (a, b) -> b - a >= slow.periodMs })
            assertTrue(monitoringSampleFresh(timestamps.last(), SystemClock.elapsedRealtime(), slow))
        }
    }

    @Test fun monitorControlsDoNotBurnScopeGraphicsIntoTheRealEncodedSdrPixels() {
        val frames = CopyOnWriteArrayList<Camera2Analysis>()
        val stopped = CountDownLatch(1); val error = AtomicReference<Throwable?>()
        val file = File.createTempFile("monitoring-sdr-", ".mp4", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            SubjectPreviewGpuTest.Fixture(embeddedAudio = true, onAnalysisFrame = { frames += it }).use { fixture ->
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { output ->
                    val size = RecordingFrameSize(128, 96)
                    val geometry = RecordingGeometry(RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, size, size, size)
                    try {
                        assertTrue(fixture.pipeline.startRecording(output, 2_000_000, geometry, onStarted = {}, onStopped = { success, evidence ->
                            try { assertTrue(success); assertEquals(12L, requireNotNull(evidence).encodedFrames) }
                            catch (failure: Throwable) { error.set(failure) } finally { stopped.countDown() }
                        }))
                        repeat(12) { index ->
                            fixture.pipeline.setViewAssist(index % 2 == 0)
                            fixture.pipeline.setMonitoringOptions(if (index % 2 == 0) enabled else MonitoringOptions(refreshHz = 10))
                            submit(fixture)
                        }
                        assertTrue(fixture.pipeline.stopRecording()); assertTrue(stopped.await(12, TimeUnit.SECONDS))
                        error.get()?.let { throw it }
                        assertEquals(Unit, fixture.pipeline.recordingFileRetirement().get(1, TimeUnit.SECONDS))
                    } finally { fixture.pipeline.stopRecording(); fixture.pipeline.recordingFileRetirement().get(15, TimeUnit.SECONDS) }
                }
            }
            val times = mutableListOf<Long>()
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path); extractor.selectTrack(0)
                while (extractor.sampleTime >= 0) { times += extractor.sampleTime; extractor.advance() }
            } finally { extractor.release() }
            assertEquals(12, times.size)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                for (time in listOf(times.first(), times.last())) {
                    val bitmap = requireNotNull(retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST))
                    try {
                        assertEquals(128, bitmap.width); assertEquals(96, bitmap.height)
                        for ((x, expected) in listOf(16 to Color.RED, 112 to Color.BLUE)) {
                            val actual = bitmap.getPixel(x, 48)
                            for (shift in listOf(0, 8, 16)) assertTrue(kotlin.math.abs((expected shr shift and 255) - (actual shr shift and 255)) <= 35)
                        }
                    } finally { bitmap.recycle() }
                }
            } finally { retriever.release() }
        } finally { check(file.delete() || !file.exists()) }
    }
}
