/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Color
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Size
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.SubjectPreviewOptions
import com.librestatic.opencinecam.camera.SubjectPreviewStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Actual GLES textures/fences/window buffers; synthetic pixels, not a physical-foldable verdict. */
class SubjectPreviewGpuTest {
    internal class Fixture(operatorPreview: Boolean = false, analysisCallback: (() -> Unit)? = null, embeddedAudio: Boolean = false, comparableEpoch: Boolean = true, onEncoderSelection: (() -> Unit)? = null, onRecordingFailure: (() -> Unit)? = null, onAnalysisFrame: ((com.librestatic.opencinecam.camera.Camera2Analysis) -> Unit)? = null, onLutStatus: ((com.librestatic.opencinecam.camera.OperatorLutStatus) -> Unit)? = null) : AutoCloseable {
        val failures = CopyOnWriteArrayList<String>()
        val operatorFailures = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<SubjectPreviewStatus>()
        val analysis = AtomicInteger()
        val reader = ImageReader.newInstance(128, 96, PixelFormat.RGBA_8888, 3)
        val operator = if (operatorPreview) ImageReader.newInstance(128, 96, PixelFormat.RGBA_8888, 3) else null
        val pipeline = OpenCineLogGpuPipeline(
            Size(128, 96), OpenCineLogSourcePath.SDR_BT709_ISP, preview = operator?.surface,
            sensorOrientationDegrees = 0, displayRotationDegrees = 0, frontFacing = false,
            viewAssist = false, targetFps = 30, passthroughSdr = true,
            appContext = if (embeddedAudio) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext else null,
            cameraTimestampRealtime = embeddedAudio && comparableEpoch,
            avcEncoderSelector = { size, fps, software -> onEncoderSelection?.invoke(); OpenCineLogGpuPipeline.findAvcEncoder(size,fps,software || embeddedAudio) },
            onAnalysis = { analysis.incrementAndGet(); analysisCallback?.invoke(); onAnalysisFrame?.invoke(it) },
            onPreviewLost = { operatorFailures += it },
            onOperatorLutStatus = onLutStatus,
            onFailure = { code, message -> failures += "$code: $message"; onRecordingFailure?.invoke() },
        )
        private val sourceDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val sourceConfig = arrayOfNulls<EGLConfig>(1).also {
            check(EGL14.eglChooseConfig(sourceDisplay, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE),
                0, it, 0, 1, IntArray(1), 0))
        }[0]!!
        private val sourceContext = EGL14.eglCreateContext(sourceDisplay, sourceConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        private val sourceWindow = EGL14.eglCreateWindowSurface(sourceDisplay, sourceConfig, pipeline.cameraInputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        private var sourceClosed = false
        fun sourceFrame(timestampNs: Long? = null) {
            check(EGL14.eglMakeCurrent(sourceDisplay, sourceWindow, sourceWindow, sourceContext))
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(1f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(64, 0, 64, 96)
            GLES30.glClearColor(0f, 0f, 1f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            timestampNs?.let { check(android.opengl.EGLExt.eglPresentationTimeANDROID(sourceDisplay,sourceWindow,it)) }
            check(EGL14.eglSwapBuffers(sourceDisplay, sourceWindow))
        }
        fun awaitImage(): Image {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < deadline) {
                sourceFrame()
                SystemClock.sleep(80)
                reader.acquireLatestImage()?.let { return it }
                assertTrue(failures.toString(), failures.isEmpty())
                assertTrue(statuses.toString(), statuses.none { it.failure != null })
            }
            error("Subject output did not produce an image: $statuses / $failures")
        }
        fun closeSource() {
            if (sourceClosed) return
            sourceClosed = true
            EGL14.eglMakeCurrent(sourceDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(sourceDisplay, sourceWindow)
            EGL14.eglDestroyContext(sourceDisplay, sourceContext)
        }
        override fun close() {
            closeSource()
            pipeline.closeAsync().get(8, TimeUnit.SECONDS)
            reader.close()
            operator?.close()
            // The renderer releases its process-wide worker lease asynchronously.
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (Thread.getAllStackTraces().keys.any { it.name == "SubjectPreviewGL" && it.isAlive } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
            assertFalse("Subject worker did not retire", Thread.getAllStackTraces().keys.any { it.name == "SubjectPreviewGL" && it.isAlive })
        }
    }

    private fun colorAt(image: Image, x: Int, y: Int): Int {
        val plane = image.planes[0]
        val offset = y * plane.rowStride + x * plane.pixelStride
        val buffer = plane.buffer
        return Color.rgb(buffer.get(offset).toInt() and 255, buffer.get(offset + 1).toInt() and 255, buffer.get(offset + 2).toInt() and 255)
    }

    @Test fun actualPixelsMirrorWithoutChangingTheSourceOrOperatorTransform() {
        Fixture(operatorPreview = true).use { fixture ->
            assertTrue(fixture.pipeline.attachSubjectPreview(fixture.reader.surface, SubjectPreviewOptions()) { fixture.statuses += it })
            repeat(3) { fixture.awaitImage().close() }
            fixture.awaitImage().use { image ->
                val left = colorAt(image, 32, 48)
                val right = colorAt(image, 96, 48)
                assertTrue("left=$left", Color.red(left) > 220 && Color.blue(left) < 30)
                assertTrue("right=$right", Color.blue(right) > 220 && Color.red(right) < 30)
            }
            fixture.pipeline.updateSubjectPreview(SubjectPreviewOptions(mirror = true))
            // Drain any already queued pre-change window buffers.
            repeat(3) { fixture.awaitImage().close() }
            fixture.awaitImage().use { image ->
                assertTrue(Color.blue(colorAt(image, 32, 48)) > 220)
                assertTrue(Color.red(colorAt(image, 96, 48)) > 220)
            }
            checkNotNull(fixture.operator?.acquireLatestImage()).use { image ->
                assertTrue("Subject mirror leaked into operator output", Color.red(colorAt(image, 32, 48)) > 220)
                assertTrue(Color.blue(colorAt(image, 96, 48)) > 220)
            }
            assertTrue(fixture.statuses.any { it.isFresh(SystemClock.elapsedRealtime()) })
            assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
        }
    }

    @Test fun rightAngleRotationUsesAspectFitAndOppositeRotationsReverseThePixels() {
        Fixture().use { fixture ->
            assertTrue(fixture.pipeline.attachSubjectPreview(fixture.reader.surface, SubjectPreviewOptions(displayRotationDegrees = 90)) { fixture.statuses += it })
            repeat(3) { fixture.awaitImage().close() }
            var top = 0
            var bottom = 0
            fixture.awaitImage().use { image ->
                assertEquals(Color.BLACK, colorAt(image, 8, 48))
                assertEquals(Color.BLACK, colorAt(image, 120, 48))
                top = colorAt(image, 64, 24)
                bottom = colorAt(image, 64, 72)
                assertTrue(top != bottom && setOf(top, bottom) == setOf(Color.RED, Color.BLUE))
            }
            fixture.pipeline.updateSubjectPreview(SubjectPreviewOptions(displayRotationDegrees = 270))
            repeat(3) { fixture.awaitImage().close() }
            fixture.awaitImage().use { image ->
                assertEquals(bottom, colorAt(image, 64, 24))
                assertEquals(top, colorAt(image, 64, 72))
            }
        }
    }

    @Test fun retiredBlockedOutputCannotAccumulateWorkersOrTerminateTheNextPipeline() {
        Fixture().use { first ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                first.pipeline.attachSubjectPreview(first.reader.surface, SubjectPreviewOptions()) {
                    first.statuses += it
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                first.awaitImage().close()
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                first.closeSource()
                first.pipeline.close()
                Fixture().use { second ->
                    second.pipeline.attachSubjectPreview(second.reader.surface, SubjectPreviewOptions()) { second.statuses += it }
                    repeat(8) { second.sourceFrame(); SystemClock.sleep(80) }
                    assertTrue(second.statuses.toString(), second.statuses.any { it.failure?.contains("still releasing") == true })
                    assertEquals(1, Thread.getAllStackTraces().keys.count { it.name == "SubjectPreviewGL" && it.isAlive })
                    release.countDown()
                    val deadline = SystemClock.elapsedRealtime() + 3_000
                    while (Thread.getAllStackTraces().keys.any { it.name == "SubjectPreviewGL" && it.isAlive } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
                    second.statuses.clear()
                    second.pipeline.attachSubjectPreview(second.reader.surface, SubjectPreviewOptions()) { second.statuses += it }
                    second.awaitImage().use { image -> assertTrue(Color.red(colorAt(image, 32, 48)) > 220) }
                    assertTrue(second.failures.toString(), second.failures.isEmpty())
                }
            } finally { release.countDown() }
        }
    }

    @Test fun heldNativeWindowBuffersDoNotStopCameraGlFrames() {
        Fixture().use { fixture ->
            assertTrue(fixture.pipeline.attachSubjectPreview(fixture.reader.surface, SubjectPreviewOptions()) { fixture.statuses += it })
            val held = mutableListOf<Image>()
            try {
                repeat(3) { held += fixture.awaitImage() }
                // Retain the maximum acquired buffers. Drivers may drop queued buffers instead of
                // blocking swap, so this is not claimed as proof of a physical native-window stall.
                repeat(10) { fixture.sourceFrame(); SystemClock.sleep(80) }
                val before = fixture.analysis.get()
                repeat(15) { fixture.sourceFrame(); SystemClock.sleep(80) }
                assertTrue("Camera GL analysis stopped behind exterior swap", fixture.analysis.get() >= before + 3)
                assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
            } finally { held.forEach { it.close() } }
        }
    }

    @Test fun stoppedSourceExpiresInsteadOfReportingFrozenPixelsAsFresh() {
        Fixture().use { fixture ->
            assertTrue(fixture.pipeline.attachSubjectPreview(fixture.reader.surface, SubjectPreviewOptions()) { fixture.statuses += it })
            fixture.awaitImage().close()
            SystemClock.sleep(750)
            assertFalse(fixture.statuses.last().isFresh(SystemClock.elapsedRealtime()))
            repeat(3) { fixture.awaitImage().close() }
            assertTrue(fixture.statuses.last().isFresh(SystemClock.elapsedRealtime()))
        }
    }

    @Test fun abandonedOutputReportsFailureWithoutFailingTheCameraPipeline() {
        Fixture().use { fixture ->
            assertTrue(fixture.pipeline.attachSubjectPreview(fixture.reader.surface, SubjectPreviewOptions()) { fixture.statuses += it })
            fixture.awaitImage().close()
            fixture.reader.close()
            val before = fixture.analysis.get()
            repeat(12) { fixture.sourceFrame(); SystemClock.sleep(80) }
            assertTrue(fixture.statuses.toString(), fixture.statuses.any { it.failure != null })
            assertTrue(fixture.analysis.get() > before)
            assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
        }
    }

    @Test fun abandonedOperatorDoesNotFailTheCameraOrExteriorOutput() {
        Fixture(operatorPreview = true).use { fixture ->
            fixture.pipeline.attachSubjectPreview(fixture.reader.surface, SubjectPreviewOptions()) { fixture.statuses += it }
            fixture.awaitImage().close()
            fixture.operator!!.close()
            val before = fixture.analysis.get()
            repeat(10) { fixture.awaitImage().close() }
            assertTrue(fixture.analysis.get() > before)
            assertTrue(fixture.statuses.last().isFresh(SystemClock.elapsedRealtime()))
            assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
        }
    }

    @Test fun slowObserverUsesIndependentWorkerAndDetachDoesNotWaitForIt() {
        Fixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callbackThreads = CopyOnWriteArrayList<String>()
            try {
                assertTrue(fixture.pipeline.attachSubjectPreview(fixture.reader.surface, SubjectPreviewOptions()) {
                    callbackThreads += Thread.currentThread().name
                    fixture.statuses += it
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                })
                fixture.awaitImage().close()
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                fixture.pipeline.detachSubjectPreview()
                val before = fixture.analysis.get()
                repeat(10) { fixture.sourceFrame(); SystemClock.sleep(80) }
                assertTrue(fixture.analysis.get() > before)
                assertEquals(listOf("SubjectPreviewGL"), callbackThreads.toList())
                assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
            } finally { release.countDown() }
        }
    }
    @Test fun closeDoesNotDestroyInputWhileItsGlCallbackIsBlocked() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        Fixture(analysisCallback = { entered.countDown(); check(resume.await(10, TimeUnit.SECONDS)) }).use { fixture ->
            try {
                fixture.sourceFrame(); assertTrue(entered.await(3, TimeUnit.SECONDS)); fixture.closeSource()
                val start = SystemClock.elapsedRealtime(); val closed = fixture.pipeline.closeAsync()
                assertTrue(SystemClock.elapsedRealtime() - start < 1000)
                assertFalse(closed.isDone); assertTrue(fixture.pipeline.cameraInputSurface.isValid)
                resume.countDown(); closed.get(8, TimeUnit.SECONDS)
                assertFalse(fixture.pipeline.cameraInputSurface.isValid)
                android.util.Log.i("GpuRetirementProbe", "blockedGlCloseReturned=true inputRetained=true inputReleasedAfterOwner=true")
            } finally { resume.countDown() }
        }
    }

    @Test fun realAacAndGlesPreserveTheCapturedStartOffset() = audioVideoEpoch(true)
    @Test fun unknownCameraClockIsDisclosedInsteadOfComparedToAudio() = audioVideoEpoch(false)
    @Test fun calibratedAudioCanStartAfterTheFirstVideoCaptureAnchor() = audioVideoEpoch(true, videoFirst = true)
    private fun audioVideoEpoch(comparable: Boolean, videoFirst: Boolean = false) {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.RECORD_AUDIO)
        val file=java.io.File(context.cacheDir,when { videoFirst -> "AV_VIDEO_FIRST.mp4"; comparable -> "AV_SHARED.mp4"; else -> "AV_UNKNOWN.mp4" })
        val stopped=CountDownLatch(1);val saved=java.util.concurrent.atomic.AtomicBoolean(false)
        val evidence=java.util.concurrent.atomic.AtomicReference<com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence?>()
        Fixture(embeddedAudio=true,comparableEpoch=comparable).use { fixture ->
            android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                val frame=com.librestatic.opencinecam.camera.RecordingFrameSize(128,96)
                val geometry=com.librestatic.opencinecam.camera.RecordingGeometry(com.librestatic.opencinecam.camera.RecordingGeometryMode.COMPATIBLE,0,0,0,0,0,frame,frame,frame)
                val audio=com.librestatic.opencinecam.camera.Camera2EmbeddedAudioConfig(android.media.MediaRecorder.AudioSource.MIC,48000,1,96000,null)
                assertTrue(fixture.pipeline.startRecording(descriptor,2000000,geometry,audio=audio,onStarted={},
                    onStopped={ success, report -> saved.set(success);evidence.set(report);stopped.countDown() }))
                SystemClock.sleep(400)
                repeat(18) { fixture.sourceFrame(SystemClock.elapsedRealtimeNanos() - if (videoFirst) 800_000_000L else 0L);SystemClock.sleep(34) }
                assertTrue(fixture.pipeline.stopRecording())
                assertTrue("A/V callback missing: ${fixture.failures}",stopped.await(10,TimeUnit.SECONDS))
                assertTrue("A/V failed: ${fixture.failures}",saved.get())
                val report=requireNotNull(evidence.get()?.avTiming)
                val starts=mutableMapOf<String,Long>();val counts=mutableMapOf<String,Int>()
                val reader=android.media.MediaExtractor()
                try {
                    reader.setDataSource(file.path);assertEquals(2,reader.trackCount)
                    for (track in 0 until reader.trackCount) {
                        reader.selectTrack(track);reader.seekTo(0,android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        val mime=requireNotNull(reader.getTrackFormat(track).getString(android.media.MediaFormat.KEY_MIME))
                        val times=mutableListOf<Long>()
                        while(reader.sampleTime>=0) { times+=reader.sampleTime;reader.advance() }
                        assertTrue("Missing $mime samples",times.isNotEmpty())
                        assertTrue(times.zipWithNext().all { (a,b) -> b>=a });starts[mime]=times.first();counts[mime]=times.size
                        reader.unselectTrack(track)
                    }
                } finally { reader.release() }
                val calibration = requireNotNull(report.aacCalibration)
                val sourceWindow = requireNotNull(report.audioSourceWindow)
                assertEquals(report.submittedPcmFrames, sourceWindow.sourceFrames)
                assertEquals(calibration.primingFrames.toLong(), sourceWindow.primingFrames)
                assertEquals(calibration.drainPaddingFrames.toLong(), report.audioDrainPaddingFrames)
                val rawOffset = requireNotNull(starts["video/avc"])-requireNotNull(starts["audio/mp4a-latm"])
                // Seeking can discard pre-roll packets. Inspect the actual edit instead of guessing from sampleTime.
                val actualWindow = java.io.RandomAccessFile(file, "r").use { input ->
                    com.librestatic.opencinecam.camera.inspectAacSourceWindow(object : com.librestatic.opencinecam.camera.ProjectMp4File {
                        override val size: Long get() = input.length()
                        override fun read(offset: Long, length: Int): ByteArray = ByteArray(length).also { input.seek(offset); input.readFully(it) }
                        override fun write(offset: Long, bytes: ByteArray) { error("Read-only source inspection") }
                    })
                }
                assertEquals(sourceWindow.sourceFrames, actualWindow.sourceFrames)
                assertEquals(calibration.primingFrames.toLong(), actualWindow.primingFrames)
                assertEquals(report.audioPresentationOffsetUs, actualWindow.presentationOffsetUs)
                val actualOffset=requireNotNull(starts["video/avc"])-actualWindow.presentationOffsetUs
                val expectedOffset=if(comparable) (requireNotNull(report.videoFrameZeroNs)-requireNotNull(report.audioFrameZeroNs))/1000 else 0L
                assertTrue("Capture offset lost: expected=$expectedOffset actual=$actualOffset report=$report",kotlin.math.abs(expectedOffset-actualOffset)<=1000)
                assertFalse(report.waveformAlignmentVerified)
                if(comparable) {
                    assertTrue("AudioTimestamp not supplied: $report",report.audioTimestampBacked)
                    assertEquals("SHARED_BOOTTIME_CAPTURE_ANCHORS",report.policy)
                    assertTrue(if (videoFirst) expectedOffset < -150000 else expectedOffset > 150000)
                } else { assertEquals("INDEPENDENT_UNKNOWN_CAMERA_EPOCH",report.policy);assertNull(report.sharedOriginNs) }
                assertTrue(report.submittedPcmFrames>0)
                assertEquals(actualWindow.expectedPackets, report.encodedAudioPackets)
                assertTrue(counts["audio/mp4a-latm"]!!.toLong() in 1..report.encodedAudioPackets)
                val json=captureEpochJson(report).put("expectedOffsetUs",expectedOffset).put("actualOffsetUs",actualOffset).put("rawPacketOffsetUs",rawOffset)
                val output=com.librestatic.opencinecam.storage.VideoOutput.create(context)
                val sidecarName=output.displayName.removeSuffix(".mp4")+".timing.json"
                fun sidecars(): List<android.net.Uri> {
                    val base=android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    return requireNotNull(context.contentResolver.query(base,arrayOf("_id"),
                        "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                        arrayOf(sidecarName,context.packageName),null)).use { cursor -> buildList {
                            while(cursor.moveToNext()) add(android.content.ContentUris.withAppendedId(base,cursor.getLong(0)))
                        } }
                }
                try {
                    requireNotNull(context.contentResolver.openOutputStream(output.uri,"w")).use { target -> file.inputStream().use { it.copyTo(target) } }
                    val sidecarJson=org.json.JSONObject().put("schema","opencinecam.av-timing.v1").put("avTiming",captureEpochJson(report)).toString(2)
                    val uri=requireNotNull(output.finish(true,sidecarJson,geometry,timingSidecar=true))
                    assertEquals(output.uri,uri);assertEquals(1,sidecars().size)
                    val stored=org.json.JSONObject(requireNotNull(context.contentResolver.openInputStream(sidecars().single())).bufferedReader().use { it.readText() }).getJSONObject("avTiming")
                    assertEquals(report.policy,stored.getString("policy"));assertEquals(report.submittedPcmFrames,stored.getLong("submittedPcmFrames"))
                    assertFalse(stored.getBoolean("waveformAlignmentVerified"))
                    assertEquals(sourceWindow.sourceFrames, stored.getJSONObject("audioSourceWindow").getLong("sourceFrames"))
                    assertEquals(calibration.primingFrames, stored.getJSONObject("aacCalibration").getInt("primingFrames"))
                } finally {
                    output.close();context.contentResolver.delete(output.uri,null,null)
                    sidecars().forEach { context.contentResolver.delete(it,null,null) }
                }
                java.io.File(context.cacheDir,file.nameWithoutExtension+".json").writeText(json.toString(2))
                android.util.Log.i("CaptureEpochProbe","file=${file.name} policy=${report.policy} audioTimestamp=${report.audioTimestampBacked} expectedOffsetUs=$expectedOffset actualOffsetUs=$actualOffset starts=$starts counts=$counts priming=${report.audioEncoderDelayFrames} residualNs=${report.audioMaxResidualNs} waveformAlignmentVerified=false metadataPublished=true pcmFrames=${report.submittedPcmFrames}")
            }
        }
    }

    @Test fun recordingOwnsItsDescriptorAfterCallerClosesTheOriginal() = ownedRecording(false)
    @Test fun closeDuringRecordingWaitsForBlockedGlWorkAndFinalizesOwnedDescriptor() = ownedRecording(true)
    private fun ownedRecording(blockedClose: Boolean) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, if (blockedClose) "GPU_OWNER_CLOSED.mp4" else "GPU_OWNER.mp4")
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val stopped = CountDownLatch(1); val saved = java.util.concurrent.atomic.AtomicBoolean(false)
        Fixture(analysisCallback = if (blockedClose) ({ entered.countDown(); check(resume.await(10, TimeUnit.SECONDS)) }) else null).use { fixture ->
            val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE)
            try {
                val frame = com.librestatic.opencinecam.camera.RecordingFrameSize(128, 96)
                val geometry = com.librestatic.opencinecam.camera.RecordingGeometry(com.librestatic.opencinecam.camera.RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, frame, frame, frame)
                assertTrue(fixture.pipeline.startRecording(descriptor, 12000000, geometry,
                    timelapse = com.librestatic.opencinecam.camera.TimelapseCapture(100000000, com.librestatic.opencinecam.camera.CaptureFrameRate(30), 3),
                    onTimelapseProgress = { if (!blockedClose && it.submittedFrames == 3L) fixture.pipeline.stopRecording() },
                    onStarted = {}, onStopped = { success, _ -> saved.set(success); stopped.countDown() }))
                descriptor.close()
                if (blockedClose) {
                    fixture.sourceFrame(); assertTrue(entered.await(3, TimeUnit.SECONDS)); fixture.closeSource()
                    val closed = fixture.pipeline.closeAsync(); assertFalse(closed.isDone)
                    assertTrue(fixture.pipeline.cameraInputSurface.isValid)
                    resume.countDown(); closed.get(8, TimeUnit.SECONDS)
                } else {
                    val deadline = SystemClock.elapsedRealtime() + 8000
                    while (stopped.count > 0 && SystemClock.elapsedRealtime() < deadline) { fixture.sourceFrame(); SystemClock.sleep(120) }
                }
                assertTrue("Recording callback did not complete: ${fixture.failures}", stopped.await(2, TimeUnit.SECONDS)); assertTrue(fixture.failures.toString(), saved.get())
                val reader = android.media.MediaExtractor()
                val pts = mutableListOf<Long>()
                try {
                    reader.setDataSource(file.absolutePath); assertEquals(1, reader.trackCount); reader.selectTrack(0)
                    while (reader.sampleTime >= 0) { pts += reader.sampleTime; reader.advance() }
                } finally { reader.release() }
                if (!blockedClose) assertEquals(listOf(0L,33333L,66666L), pts) else assertEquals(listOf(0L), pts)
                android.util.Log.i("GpuRetirementProbe", "blockedClose=$blockedClose originalFdClosed=true saved=true ptsUs=$pts file=${file.name}")
            } finally { resume.countDown(); descriptor.close() }
        }
    }

    @Test fun sharedCapturePauseRemovesRepeatedIntervalsFromRealAacAndVideo() = sharedCapturePause(false)
    @Test fun sharedCaptureCanStopWhilePausedWithLivePreviewAndMeters() = sharedCapturePause(true)

    private fun sharedCapturePause(stopPaused: Boolean) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val file = java.io.File(context.cacheDir, if (stopPaused) "AV_STOP_PAUSED.mp4" else "AV_REPEATED_PAUSE.mp4")
        val statuses = CopyOnWriteArrayList<com.librestatic.opencinecam.camera.TimelapsePauseStatus>()
        val report = java.util.concurrent.atomic.AtomicReference<com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence?>()
        val stopped = CountDownLatch(1); val saved = java.util.concurrent.atomic.AtomicBoolean(); val levels = AtomicInteger()
        Fixture(embeddedAudio = true).use { fixture ->
            val frame = com.librestatic.opencinecam.camera.RecordingFrameSize(128, 96)
            val geometry = com.librestatic.opencinecam.camera.RecordingGeometry(com.librestatic.opencinecam.camera.RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, frame, frame, frame)
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                val audio = com.librestatic.opencinecam.camera.Camera2EmbeddedAudioConfig(android.media.MediaRecorder.AudioSource.MIC, 48000, 1, 96000, null, onAudioLevel = { levels.incrementAndGet() })
                assertTrue(fixture.pipeline.startRecording(descriptor, 2000000, geometry, audio = audio,
                    onTimelapsePauseChanged = { statuses += it }, onStarted = {}, onStopped = { success, evidence -> saved.set(success); report.set(evidence); stopped.countDown() }))
                val sourcePts = mutableListOf<Long>()
                fun frames(ms: Long) { val until = SystemClock.elapsedRealtime() + ms; while (SystemClock.elapsedRealtime() < until) {
                    val sourceNs = SystemClock.elapsedRealtimeNanos(); sourcePts += sourceNs
                    fixture.sourceFrame(sourceNs); SystemClock.sleep(34)
                } }
                val availableBy = SystemClock.elapsedRealtime() + 2500
                while (statuses.isEmpty() && SystemClock.elapsedRealtime() < availableBy) frames(70)
                assertTrue("Shared pause capability not reported: ${fixture.failures}", statuses.isNotEmpty())
                assertEquals("SHARED_CAPTURE_SAMPLE_WINDOWS", statuses.last().policy)
                fun pause(value: Boolean) {
                    val acknowledged = CountDownLatch(1); val changed = java.util.concurrent.atomic.AtomicBoolean()
                    assertTrue(fixture.pipeline.setTimelapsePaused(value) { changed.set(it); acknowledged.countDown() })
                    assertTrue(acknowledged.await(2, TimeUnit.SECONDS)); assertTrue(changed.get()); assertEquals(value, statuses.last().paused)
                }
                frames(350)
                repeat(2) {
                    pause(true); val analysisBefore = fixture.analysis.get(); val levelsBefore = levels.get()
                    frames(550)
                    assertTrue("Preview stopped while paused", fixture.analysis.get() > analysisBefore)
                    assertTrue("Audio meter stopped while paused", levels.get() > levelsBefore)
                    pause(false); frames(350)
                }
                if (stopPaused) { pause(true); frames(350) }
                assertTrue(fixture.pipeline.stopRecording()); assertTrue(stopped.await(10, TimeUnit.SECONDS))
                assertTrue("Paused recording failed: ${fixture.failures}", saved.get())
                val timing = requireNotNull(report.get()?.avTiming); val cut = requireNotNull(timing.sharedPause)
                assertEquals(if (stopPaused) 3 else 2, cut.windows.size)
                assertTrue(cut.windows.all { it.endFrame != null && it.endFrame!! >= it.startFrame })
                assertNotNull(cut.stopFrame); assertEquals(timing.submittedPcmFrames, cut.retainedPcmFrames)
                assertEquals(timing.capturedPcmFrames, cut.capturedPcmFrames)
                assertTrue(cut.capturedPcmFrames - cut.retainedPcmFrames > 48000)
                assertEquals(timing.submittedPcmFrames, requireNotNull(timing.audioSourceWindow).sourceFrames)
                assertTrue(statuses.last().finished); assertEquals(stopPaused, statuses.last().paused)
                assertTrue(statuses.all { it.takeId == statuses.first().takeId })
                val json = captureEpochJson(timing).put("stopPaused", stopPaused).put("encodedVideoFrames", report.get()!!.encodedFrames)
                    .put("previewFrames", fixture.analysis.get()).put("meterUpdates", levels.get()).put("sourceVideoPtsNs", org.json.JSONArray(sourcePts))
                java.io.File(context.cacheDir, file.nameWithoutExtension + ".json").writeText(json.toString(2))
                android.util.Log.i("SharedPauseProbe", json.toString())
            }
        }
    }

    @Test fun failedPreparationRetainsAdmissionUntilItsCallerFailureCallbackReturns() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val selected = AtomicInteger(); val failures = AtomicInteger(); val started = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val stopped = CountDownLatch(1)
        val file = java.io.File(context.cacheDir, "START_FAILURE_RETIRING.mp4")
        Fixture(onEncoderSelection = { if (selected.incrementAndGet() == 1) error("Injected encoder-selection failure") },
            onRecordingFailure = { if (failures.incrementAndGet() == 1) { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) } }).use { fixture ->
            val frame = com.librestatic.opencinecam.camera.RecordingFrameSize(128, 96)
            val geometry = com.librestatic.opencinecam.camera.RecordingGeometry(com.librestatic.opencinecam.camera.RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, frame, frame, frame)
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                fun begin() = fixture.pipeline.startRecording(output, 12000000, geometry,
                    timelapse = com.librestatic.opencinecam.camera.TimelapseCapture(100000000, com.librestatic.opencinecam.camera.CaptureFrameRate(30)),
                    onStarted = { started.incrementAndGet() }, onStopped = { _, _ -> stopped.countDown() })
                val attempt = java.util.concurrent.FutureTask<Boolean> { begin() }
                val caller = Thread(attempt, "StartFailureCaller").apply { start() }
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    val at = SystemClock.elapsedRealtime(); assertFalse(begin())
                    assertTrue(SystemClock.elapsedRealtime() - at < 500)
                    assertEquals(1, selected.get()); assertEquals(0, started.get()); assertFalse(attempt.isDone)
                    release.countDown(); assertFalse(attempt.get(3, TimeUnit.SECONDS)); caller.join(1000)
                    assertTrue(begin()); assertEquals(2, selected.get()); assertEquals(1, started.get())
                    assertTrue(fixture.pipeline.stopRecording()); assertTrue(stopped.await(8, TimeUnit.SECONDS))
                    android.util.Log.i("StartCommitProbe", "callerFailureBlocked=true replacementRejected=true callerReturned=true recoveryAccepted=true")
                } finally { release.countDown(); caller.join(3000) }
            }
        }
        file.delete()
    }

    @Test fun timedOutPreparationCannotStartLateOrAdmitAnOverlappingReplacement() = recordingStartDecision(false)
    @Test fun committedRecordingRemainsAcceptedWhenStartedCallbackExceedsObservationDeadline() = recordingStartDecision(true)

    private fun recordingStartDecision(committed: Boolean) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val selected = AtomicInteger()
        val started = AtomicInteger(); val stopped = CountDownLatch(1)
        val saved = java.util.concurrent.atomic.AtomicBoolean(false)
        val file = java.io.File(context.cacheDir, if (committed) "START_COMMITTED.mp4" else "START_RECOVERED.mp4")
        val aborted = java.io.File(context.cacheDir, "START_ABORTED.mp4")
        Fixture(onEncoderSelection = if (committed) null else ({
            if (selected.incrementAndGet() == 1) { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        })).use { fixture ->
            val frame = com.librestatic.opencinecam.camera.RecordingFrameSize(128, 96)
            val geometry = com.librestatic.opencinecam.camera.RecordingGeometry(com.librestatic.opencinecam.camera.RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, frame, frame, frame)
            val interval = com.librestatic.opencinecam.camera.TimelapseCapture(100000000, com.librestatic.opencinecam.camera.CaptureFrameRate(30), frameLimit = 3)
            fun begin(descriptor: android.os.ParcelFileDescriptor, blockedCallback: Boolean): Boolean = fixture.pipeline.startRecording(
                descriptor, 12000000, geometry, timelapse = interval,
                onTimelapseProgress = { if (it.submittedFrames == 3L) fixture.pipeline.stopRecording() },
                onStarted = { started.incrementAndGet(); if (blockedCallback) { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) } },
                onStopped = { success, _ -> saved.set(success); stopped.countDown() })
            fun descriptor(target: java.io.File) = android.os.ParcelFileDescriptor.open(target,
                android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE)
            try {
                val elapsed = SystemClock.elapsedRealtime()
                val accepted = descriptor(if (committed) file else aborted).use { begin(it, committed) }
                val observationMs = SystemClock.elapsedRealtime() - elapsed
                assertTrue(entered.await(1, TimeUnit.SECONDS)); assertTrue(observationMs >= 7500)
                assertEquals(committed, accepted)
                if (committed) {
                    assertEquals(1, started.get()); assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
                    release.countDown()
                } else {
                    assertEquals(0, started.get()); assertEquals(1L, stopped.count)
                    val replacementAt = SystemClock.elapsedRealtime()
                    descriptor(file).use { assertFalse(begin(it, false)) }
                    assertTrue("Replacement waited behind abandoned native owner", SystemClock.elapsedRealtime() - replacementAt < 500)
                    assertEquals(1, selected.get())
                    release.countDown()
                    descriptor(file).use { output ->
                        var recovered = false
                        val deadline = SystemClock.elapsedRealtime() + 3000
                        while (!recovered && SystemClock.elapsedRealtime() < deadline) { recovered = begin(output, false); if (!recovered) SystemClock.sleep(20) }
                        assertTrue("Preparation lease did not retire: ${fixture.failures}", recovered)
                    }
                    assertEquals(1, started.get()); assertEquals(2, selected.get()); assertEquals(0L, aborted.length())
                    assertEquals(1, fixture.failures.count { it.startsWith("log-recording-prepare-failed:") })
                }
                val deadline = SystemClock.elapsedRealtime() + 8000
                while (stopped.count > 0 && SystemClock.elapsedRealtime() < deadline) { fixture.sourceFrame(); SystemClock.sleep(120) }
                assertTrue(stopped.await(2, TimeUnit.SECONDS)); assertTrue(fixture.failures.toString(), saved.get())
                val reader = android.media.MediaExtractor(); val pts = mutableListOf<Long>()
                try { reader.setDataSource(file.path); reader.selectTrack(0); while (reader.sampleTime >= 0) { pts += reader.sampleTime; reader.advance() } }
                finally { reader.release() }
                assertEquals(listOf(0L, 33333L, 66666L), pts)
                val json = org.json.JSONObject().put("committedBeforeTimeout", committed).put("accepted", accepted)
                    .put("observationMs", observationMs).put("startedCallbacks", started.get()).put("saved", saved.get())
                    .put("ptsUs", org.json.JSONArray(pts)).put("file", file.name)
                java.io.File(context.cacheDir, file.nameWithoutExtension + ".json").writeText(json.toString(2))
                android.util.Log.i("StartCommitProbe", json.toString())
            } finally { release.countDown() }
        }
    }

    @Test fun expiredGlObservationStillRetainsNativeRecordingUntilOwnerReturns() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "GPU_OWNER_REJECTED.mp4")
        val entered = CountDownLatch(1); val resume = CountDownLatch(1); val stopped = CountDownLatch(1)
        val saved = java.util.concurrent.atomic.AtomicBoolean(true)
        Fixture(analysisCallback = { entered.countDown(); check(resume.await(20, TimeUnit.SECONDS)) }).use { fixture ->
            val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE)
            try {
                val frame = com.librestatic.opencinecam.camera.RecordingFrameSize(128, 96)
                val geometry = com.librestatic.opencinecam.camera.RecordingGeometry(com.librestatic.opencinecam.camera.RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0, frame, frame, frame)
                assertTrue(fixture.pipeline.startRecording(descriptor, 12000000, geometry,
                    timelapse = com.librestatic.opencinecam.camera.TimelapseCapture(100000000, com.librestatic.opencinecam.camera.CaptureFrameRate(30)),
                    onStarted = {}, onStopped = { success, _ -> saved.set(success); stopped.countDown() }))
                descriptor.close(); fixture.sourceFrame(); assertTrue(entered.await(3, TimeUnit.SECONDS)); fixture.closeSource()
                assertTrue(fixture.pipeline.stopRecording())
                val deadline = SystemClock.elapsedRealtime() + 12000
                while (fixture.failures.none { it.startsWith("log-retirement-pending:") } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                assertTrue(fixture.failures.toString(), fixture.failures.any { it.startsWith("log-retirement-pending:") })
                assertEquals(1L, stopped.count); assertTrue(fixture.pipeline.cameraInputSurface.isValid)
                val closed = fixture.pipeline.closeAsync(); assertFalse(closed.isDone)
                resume.countDown(); closed.get(8, TimeUnit.SECONDS)
                assertTrue(stopped.await(1, TimeUnit.SECONDS)); assertFalse(saved.get())
                assertFalse(fixture.pipeline.cameraInputSurface.isValid)
                android.util.Log.i("GpuRetirementProbe", "glDeadlineObserved=true pendingOwnerRetained=true callbackAfterReturn=true saved=false resourcesRetired=true")
            } finally { resume.countDown(); descriptor.close(); file.delete() }
        }
    }

}
