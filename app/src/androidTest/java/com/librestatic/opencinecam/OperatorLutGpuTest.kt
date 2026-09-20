/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.Image
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
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
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/** Real SDR GLES and real AVC decode. Synthetic chart, not a hardware OCLog qualification. */
class OperatorLutGpuTest {
    private val chart = listOf(Color.rgb(64, 96, 192), Color.rgb(192, 64, 128),
        Color.rgb(32, 224, 80), Color.rgb(224, 160, 32))
    private val lut = MonitorLut(CubeLut.parse(buildString {
        append("TITLE \"Nonlinear channel mapping\"\nLUT_3D_SIZE 3\n")
        for (b in 0..2) for (g in 0..2) for (r in 0..2)
            append("${b*b/4f} ${1-r/2f} ${g/4f}\n")
    }.toByteArray()), LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE)

    private fun <T> field(owner: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
    private fun onGl(f: SubjectPreviewGpuTest.Fixture, block: () -> Unit) {
        val done = CompletableFuture<Unit>()
        check(field<Handler>(f.pipeline, "handler").post {
            try { block(); done.complete(Unit) } catch (failure: Throwable) { done.completeExceptionally(failure) }
        })
        done.get(10, TimeUnit.SECONDS)
    }
    private fun drain(reader: ImageReader?) { reader?.acquireLatestImage()?.close() }
    private fun awaitImage(reader: ImageReader): Image {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            reader.acquireLatestImage()?.let { return it }
            SystemClock.sleep(1) // Bounded readiness polling; never assumes render completion from a sleep.
        }
        error("Rendered window image did not arrive")
    }
    private fun colorAt(image: Image, x: Int): Int {
        val plane = image.planes[0]; val i = 48 * plane.rowStride + x * plane.pixelStride
        return Color.rgb(plane.buffer.get(i).toInt() and 255, plane.buffer.get(i + 1).toInt() and 255,
            plane.buffer.get(i + 2).toInt() and 255)
    }
    private fun assertColor(expected: Int, actual: Int, tolerance: Int) {
        for (shift in listOf(0, 8, 16)) assertTrue("expected=$expected actual=$actual tolerance=$tolerance",
            abs((expected shr shift and 255) - (actual shr shift and 255)) <= tolerance)
    }
    private fun mapped(color: Int): Int {
        val rgb = lut.cube.sample(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        return Color.rgb((rgb[0]*255).roundToInt(), (rgb[1]*255).roundToInt(), (rgb[2]*255).roundToInt())
    }
    private fun assertChart(image: Image, transformed: Boolean) {
        assertEquals(128, image.width); assertEquals(96, image.height)
        chart.forEachIndexed { i, color -> assertColor(if (transformed) mapped(color) else color, colorAt(image, i*32+16), 5) }
    }
    private fun submit(f: SubjectPreviewGpuTest.Fixture): Long {
        drain(f.operator)
        val stamp = SystemClock.elapsedRealtimeNanos()
        val display = field<EGLDisplay>(f, "sourceDisplay")
        val window = field<EGLSurface>(f, "sourceWindow")
        check(EGL14.eglMakeCurrent(display, window, window, field<EGLContext>(f, "sourceContext")))
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        chart.forEachIndexed { i, color ->
            GLES30.glScissor(i*32, 0, 32, 96)
            GLES30.glClearColor(Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        check(EGLExt.eglPresentationTimeANDROID(display, window, stamp)); check(EGL14.eglSwapBuffers(display, window))
        val texture = field<SurfaceTexture>(f.pipeline, "surfaceTexture")
        val handler = field<Handler>(f.pipeline, "handler")
        val done = CompletableFuture<Unit>(); val deadline = SystemClock.elapsedRealtime() + 10_000
        val probe = object : Runnable {
            override fun run() {
                if (done.isDone) return
                try {
                    check(f.failures.isEmpty() && f.operatorFailures.isEmpty()) { "${f.failures} / ${f.operatorFailures}" }
                    if (texture.timestamp == stamp) done.complete(Unit) else {
                        check(texture.timestamp < stamp && SystemClock.elapsedRealtime() < deadline)
                        check(handler.postDelayed(this, 1))
                    }
                } catch (failure: Throwable) { done.completeExceptionally(failure) }
            }
        }
        check(handler.post(probe))
        try { done.get(11, TimeUnit.SECONDS) }
        finally { done.cancel(false); handler.removeCallbacks(probe) }
        return stamp
    }
    private fun analysis(f: SubjectPreviewGpuTest.Fixture, frames: MutableList<Camera2Analysis>): Camera2Analysis {
        onGl(f) { frames.clear() }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (frames.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            submit(f); drain(f.operator)
        }
        return frames.lastOrNull() ?: error("No pre-LUT scope analysis")
    }

    @Test fun realGpuChartMatchesCpuTrilinearAndDisableRestoresOriginal() {
        val statuses = CopyOnWriteArrayList<OperatorLutStatus>()
        SubjectPreviewGpuTest.Fixture(operatorPreview = true, onLutStatus = { statuses += it }).use { f ->
            submit(f); awaitImage(requireNotNull(f.operator)).use { assertChart(it, false) }
            onGl(f) { f.pipeline.setOperatorLut(lut) }
            submit(f); awaitImage(requireNotNull(f.operator)).use { assertChart(it, true) }
            assertEquals(OperatorLutStatus(lut.cube.sha256, OperatorLutState.ACTIVE, selectionId = monitorLutIdentity(lut)), statuses.last())
            onGl(f) { f.pipeline.setOperatorLut(null) }
            submit(f); awaitImage(requireNotNull(f.operator)).use { assertChart(it, false) }
            assertEquals(OperatorLutState.DISABLED, statuses.last().state)
        }
    }

    @Test fun maximumTableCustomDomainAndSmallerReplacementMatchCpu() {
        val maximum = CubeLut.parse(buildString {
            append("LUT_3D_SIZE 33\nDOMAIN_MIN -0.25 0.125 0\nDOMAIN_MAX 0.75 0.875 1\n")
            for (b in 0..32) for (g in 0..32) for (r in 0..32)
                append("${b*b/1024f} ${1-r/32f} ${g/64f}\n")
        }.toByteArray())
        val smaller = CubeLut.parse(buildString {
            append("LUT_3D_SIZE 2\n")
            for (b in 0..1) for (g in 0..1) for (r in 0..1)
                append("$r $g $b\n")
        }.toByteArray())
        SubjectPreviewGpuTest.Fixture(operatorPreview = true).use { f ->
            for (cube in listOf(maximum, smaller)) {
                onGl(f) { f.pipeline.setOperatorLut(lut.copy(cube = cube)) }
                submit(f)
                awaitImage(requireNotNull(f.operator)).use { image ->
                    chart.forEachIndexed { index, color ->
                        val expected = cube.sample(Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f)
                        assertColor(Color.rgb((expected[0]*255).roundToInt(), (expected[1]*255).roundToInt(),
                            (expected[2]*255).roundToInt()), colorAt(image, index*32+16), 5)
                    }
                }
            }
        }
    }

    @Test fun mismatchedSignalDomainIsDisclosedAndNeverAppliedThenCompatibleSelectionRecovers() {
        val statuses = CopyOnWriteArrayList<OperatorLutStatus>()
        SubjectPreviewGpuTest.Fixture(operatorPreview = true, onLutStatus = { statuses += it }).use { f ->
            onGl(f) { f.pipeline.setOperatorLut(lut.copy(input = LutSignalDomain.OCLOG2_CODE)) }
            submit(f); awaitImage(requireNotNull(f.operator)).use { assertChart(it, false) }
            assertEquals(OperatorLutStatus(lut.cube.sha256, OperatorLutState.INCOMPATIBLE_DOMAIN, selectionId = monitorLutIdentity(lut.copy(input=LutSignalDomain.OCLOG2_CODE))), statuses.last())
            onGl(f) { f.pipeline.setOperatorLut(lut.copy(kind = LutTransformKind.TECHNICAL)) }
            submit(f); awaitImage(requireNotNull(f.operator)).use { assertChart(it, true) }
            assertEquals(OperatorLutState.ACTIVE, statuses.last().state)
        }
    }

    @Test fun independentSubjectSelectionDisableAndDomainRecoveryMatchPresentedPixels() {
        val operatorStatuses = CopyOnWriteArrayList<OperatorLutStatus>()
        SubjectPreviewGpuTest.Fixture(operatorPreview = true, onLutStatus = { operatorStatuses += it }).use { f ->
            val green = lut.copy(cube = CubeLut.parse(("LUT_3D_SIZE 2\n" +
                List(8) { "0 1 0" }.joinToString("\n") + "\n").toByteArray()))
            assertTrue(f.pipeline.attachSubjectPreview(f.reader.surface, SubjectPreviewOptions()) { f.statuses += it })
            fun checkOutputs(operator: MonitorLut?, subject: MonitorLut?, subjectState: OperatorLutState) {
                onGl(f) { f.pipeline.setOperatorLut(operator); f.pipeline.setSubjectLut(subject) }
                drain(f.reader)
                val deadline = SystemClock.elapsedRealtime() + 10_000
                val since = SystemClock.elapsedRealtime()
                var checked = false
                while (!checked && SystemClock.elapsedRealtime() < deadline) {
                    submit(f)
                    awaitImage(requireNotNull(f.operator)).use { image ->
                        chart.forEachIndexed { i, color ->
                            val rgb = operator?.cube?.sample(Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f)
                            val expected = rgb?.let { Color.rgb((it[0]*255).roundToInt(), (it[1]*255).roundToInt(), (it[2]*255).roundToInt()) } ?: color
                            assertColor(expected, colorAt(image, i*32+16), 5)
                        }
                    }
                    val status = f.statuses.lastOrNull()
                    f.reader.acquireLatestImage()?.use { image ->
                        if (status != null && (status.sourceReceivedAtMs ?: Long.MIN_VALUE) >= since &&
                            status.lutStatus?.selectionId == monitorLutIdentity(subject) && status.lutStatus?.state == subjectState) {
                            chart.forEachIndexed { i, color ->
                                val rgb = subject?.takeIf { subjectState == OperatorLutState.ACTIVE }?.cube?.sample(
                                    Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f)
                                val expected = rgb?.let { Color.rgb((it[0]*255).roundToInt(), (it[1]*255).roundToInt(), (it[2]*255).roundToInt()) } ?: color
                                assertColor(expected, colorAt(image, i*32+16), 5)
                            }
                            checked = true
                        }
                    }
                    assertTrue(f.statuses.toString(), f.statuses.none { it.failure != null })
                }
                assertTrue("No matching presented subject frame: ${f.statuses.lastOrNull()}", checked)
                assertEquals(monitorLutIdentity(operator), operatorStatuses.last().selectionId)
                assertEquals(if (operator == null) OperatorLutState.DISABLED else OperatorLutState.ACTIVE, operatorStatuses.last().state)
            }
            checkOutputs(lut, green, OperatorLutState.ACTIVE)
            checkOutputs(null, green, OperatorLutState.ACTIVE)
            checkOutputs(green, lut, OperatorLutState.ACTIVE)
            checkOutputs(green, null, OperatorLutState.DISABLED)
            checkOutputs(green, lut.copy(input = LutSignalDomain.OCLOG2_CODE), OperatorLutState.INCOMPATIBLE_DOMAIN)
            checkOutputs(green, lut, OperatorLutState.ACTIVE)
        }
    }

    @Test fun operatorLutLeavesSubjectScopesAndTwelveRealEncodedFramesUnchanged() =
        verifyUnbakedOutputs(null)

    @Test fun independentSubjectLutLeavesScopesAndTwelveRealEncodedFramesUnchanged() =
        verifyUnbakedOutputs(lut.copy(cube = CubeLut.parse(("LUT_3D_SIZE 2\n" +
            List(8) { "0.1 0.8 0.3" }.joinToString("\n") + "\n").toByteArray())))

    private val fileLutBytes = buildString {
        append("TITLE \"Independent file transform\"\nLUT_3D_SIZE 3\n")
        for (b in 0..2) for (g in 0..2) for (r in 0..2)
            append("${r*r/4f} ${b/2f} ${1-g/2f}\n")
    }.toByteArray()

    @Test fun recordingLutBakesRealFramesButLeavesMonitorsAndScopesIndependent() = verifyUnbakedOutputs(
        lut.copy(cube = CubeLut.parse(("LUT_3D_SIZE 2\n" + List(8) { "0.1 0.8 0.3" }.joinToString("\n") + "\n").toByteArray())),
        lut.copy(cube = CubeLut.parse(fileLutBytes)))

    private fun verifyUnbakedOutputs(subjectLut: MonitorLut?, recordingLut: MonitorLut? = null) {
        val frames = CopyOnWriteArrayList<Camera2Analysis>()
        val statuses = CopyOnWriteArrayList<OperatorLutStatus>()
        val applied = CopyOnWriteArrayList<BakedLutEvidence>()
        val finalBaking = AtomicReference<BakedLutEvidence?>()
        val file = File.createTempFile("operator-lut-", ".mp4", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            SubjectPreviewGpuTest.Fixture(operatorPreview = true, embeddedAudio = true,
                onAnalysisFrame = { frames += it }, onLutStatus = { statuses += it }).use { f ->
                onGl(f) { f.pipeline.setMonitoringOptions(MonitoringOptions(waveformEnabled = true,
                    vectorscopeEnabled = true, falseColorEnabled = true, refreshHz = 10)) }
                val before = analysis(f, frames)
                val subject = CompletableFuture<List<Int>>()
                val duringSubject = CompletableFuture<List<Int>>()
                val duringIdentity = AtomicReference<String?>()
                val duringSourceFloor = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
                f.reader.setOnImageAvailableListener({ reader ->
                    try {
                        reader.acquireLatestImage()?.use { image ->
                            val colors = chart.indices.map { colorAt(image, it*32+16) }
                            subject.complete(colors)
                            val receipt = f.statuses.lastOrNull()
                            if (receipt?.lutStatus?.selectionId == duringIdentity.get() &&
                                (receipt?.sourceReceivedAtMs ?: Long.MIN_VALUE) >= duringSourceFloor.get()) duringSubject.complete(colors)
                        }
                    } catch (error: Throwable) { subject.completeExceptionally(error) }
                }, field<Handler>(f.pipeline, "handler"))
                assertTrue(f.pipeline.attachSubjectPreview(f.reader.surface, SubjectPreviewOptions()) { f.statuses += it })
                onGl(f) { f.pipeline.setOperatorLut(lut); f.pipeline.setSubjectLut(subjectLut) }
                val after = analysis(f, frames)
                assertEquals(MonitoringSignalDomain.SDR_BT709_CODE, requireNotNull(after.scopes).domain)
                assertEquals(before.histogram, after.histogram)
                assertEquals(requireNotNull(before.scopes).waveformDensity, requireNotNull(after.scopes).waveformDensity)
                assertEquals(requireNotNull(before.scopes).vectorscopeCounts, requireNotNull(after.scopes).vectorscopeCounts)
                assertEquals(requireNotNull(before.scopes).falseColorBands, requireNotNull(after.scopes).falseColorBands)
                // Wait for a real subject frame, not just accepted attach; all source pixels stay identical.
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (!subject.isDone && SystemClock.elapsedRealtime() < deadline) {
                    submit(f); drain(f.operator)
                }
                val subjectColors = subject.get(1, TimeUnit.SECONDS)
                chart.forEachIndexed { i, color ->
                    val rgb = subjectLut?.cube?.sample(Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f)
                    val expected = rgb?.let { Color.rgb((it[0]*255).roundToInt(), (it[1]*255).roundToInt(), (it[2]*255).roundToInt()) } ?: color
                    assertColor(expected, subjectColors[i], 5)
                }
                val stopped = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { output ->
                    val size = RecordingFrameSize(128, 96)
                    val geometry = RecordingGeometry(RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, size, size, size)
                    try {
                        assertTrue(f.pipeline.startRecording(output, 2_000_000, geometry, recordingLut = recordingLut,
                            onRecordingLutApplied = { applied += it }, onStarted = {}, onStopped = { success, evidence ->
                            try {
                                assertTrue(success); assertEquals(12L, requireNotNull(evidence).encodedFrames)
                                finalBaking.set(evidence.recordingLut)
                                assertEquals(monitorLutIdentity(recordingLut), evidence.recordingLut?.selectionId)
                            }
                            catch (error: Throwable) { failure.set(error) } finally { stopped.countDown() }
                        }))
                        val changedSubject = subjectLut?.copy(cube = CubeLut.parse(("LUT_3D_SIZE 2\n" +
                            List(8) { "0.6 0.2 0.9" }.joinToString("\n") + "\n").toByteArray()))
                        if (recordingLut != null) {
                            var nextAnalysisDue = 0L
                            onGl(f) {
                                nextAnalysisDue = requireNotNull(frames.lastOrNull()).capturedAtElapsedRealtimeMs + 110
                                frames.clear()
                                duringIdentity.set(monitorLutIdentity(changedSubject))
                                duringSourceFloor.set(SystemClock.elapsedRealtime())
                                f.pipeline.setSubjectLut(changedSubject)
                            }
                            // Schedule against the observed 10Hz analysis deadline. No extra encoded
                            // frames or arbitrary sleeps: the first of exactly12 must refresh scopes.
                            val ready = CompletableFuture<Unit>()
                            val waitMs = (nextAnalysisDue - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                            assertTrue(field<Handler>(f.pipeline, "handler").postDelayed({ ready.complete(Unit) }, waitMs))
                            ready.get(waitMs + 5_000, TimeUnit.MILLISECONDS)
                        }
                        repeat(12) {
                            submit(f)
                            awaitImage(requireNotNull(f.operator)).use { image -> assertChart(image, true) }
                        }
                        if (recordingLut != null) {
                            val actual = requireNotNull(frames.lastOrNull())
                            assertTrue(actual.capturedAtElapsedRealtimeMs >= duringSourceFloor.get())
                            assertEquals(before.histogram, actual.histogram)
                            assertEquals(requireNotNull(before.scopes).waveformDensity, requireNotNull(actual.scopes).waveformDensity)
                            assertEquals(requireNotNull(before.scopes).vectorscopeCounts, requireNotNull(actual.scopes).vectorscopeCounts)
                            assertEquals(requireNotNull(before.scopes).falseColorBands, requireNotNull(actual.scopes).falseColorBands)
                            val colors = duringSubject.get(5, TimeUnit.SECONDS)
                            chart.forEachIndexed { i, color ->
                                val expected = requireNotNull(changedSubject).cube.sample(Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f)
                                assertColor(Color.rgb((expected[0]*255).roundToInt(), (expected[1]*255).roundToInt(), (expected[2]*255).roundToInt()), colors[i], 5)
                            }
                        }
                        assertTrue(f.pipeline.stopRecording()); assertTrue(stopped.await(12, TimeUnit.SECONDS))
                        failure.get()?.let { throw it }
                        assertEquals(Unit, f.pipeline.recordingFileRetirement().get(1, TimeUnit.SECONDS))
                        assertEquals(OperatorLutState.ACTIVE, statuses.last().state)
                    } finally { f.pipeline.stopRecording(); f.pipeline.recordingFileRetirement().get(15, TimeUnit.SECONDS) }
                }
            }
            val times = mutableListOf<Long>(); val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path); extractor.selectTrack(0)
                while (extractor.sampleTime >= 0) { times += extractor.sampleTime; extractor.advance() }
            } finally { extractor.release() }
            assertEquals(12, times.size)
            if (recordingLut != null) {
                assertEquals(1, applied.size)
                assertEquals(monitorLutIdentity(recordingLut), applied.single().selectionId)
                val tags = MediaExtractor()
                try {
                    tags.setDataSource(file.path)
                    val format = tags.getTrackFormat(0)
                    assertEquals(android.media.MediaFormat.COLOR_STANDARD_BT709, format.getInteger(android.media.MediaFormat.KEY_COLOR_STANDARD))
                    assertEquals(android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO, format.getInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER))
                    assertEquals(android.media.MediaFormat.COLOR_RANGE_LIMITED, format.getInteger(android.media.MediaFormat.KEY_COLOR_RANGE))
                } finally { tags.release() }
            } else { assertTrue(applied.isEmpty()); assertNull(finalBaking.get()) }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                for (time in listOf(times.first(), times.last())) {
                    val bitmap = requireNotNull(retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST))
                    try {
                        assertEquals(128, bitmap.width); assertEquals(96, bitmap.height)
                        chart.forEachIndexed { i, color ->
                            val rgb = recordingLut?.cube?.sample(Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f)
                            val expected = rgb?.let { Color.rgb((it[0]*255).roundToInt(), (it[1]*255).roundToInt(), (it[2]*255).roundToInt()) } ?: color
                            assertColor(expected, bitmap.getPixel(i*32+16, 48), 35)
                        }
                    } finally { bitmap.recycle() }
                }
            } finally { retriever.release() }
            if (recordingLut != null) {
                val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "e14-editor-fixture")
                check(directory.mkdirs() || directory.isDirectory)
                file.copyTo(File(directory, "baked.mp4"), overwrite = true)
                File(directory, "original.cube").writeBytes(fileLutBytes)
                File(directory, "baking.json").writeText(recordingLutJson(requireNotNull(finalBaking.get())).toString(2))
                File(directory, "source-chart.json").writeText(org.json.JSONArray(chart).toString())
            }
        } finally { check(file.delete() || !file.exists()) }
    }
}
