/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Camera2 graph switch on the connected device; no claim of physical double-screen qualification. */
class SubjectCameraGraphTest {
    @get:Rule val compose = createComposeRule()
    @Test fun directToGpuToDirectReusesSurfaceOnlyAfterCameraClosesAndFeedsExterior() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val engine = Camera2PreviewEngine(context)
        val exterior = ImageReader.newInstance(128, 96, PixelFormat.RGBA_8888, 3)
        val surface = AtomicReference<android.view.Surface?>()
        val started = AtomicInteger()
        val pullsStarted = AtomicInteger()
        val pullsFinished = AtomicInteger()
        val pullsCancelled = AtomicInteger()
        val focusSelection = AtomicReference<Float?>()
        val failures = CopyOnWriteArrayList<String>()
        val frames = CopyOnWriteArrayList<SubjectPreviewStatus>()
        val metadata = CopyOnWriteArrayList<Camera2PreviewMetadata>()
        val listener = object : Camera2PreviewListener {
            override fun onOpening(descriptor: Camera2CameraDescriptor) = Unit
            override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) { started.incrementAndGet() }
            override fun onFocusPullStarted(targetDiopters: Float) { pullsStarted.incrementAndGet() }
            override fun onFocusPullFinished() { pullsFinished.incrementAndGet() }
            override fun onFocusPullCancelled() { pullsCancelled.incrementAndGet() }
            override fun onFocusSelectionChanged(diopters: Float?) { focusSelection.set(diopters) }
            override fun onMetadata(value: Camera2PreviewMetadata) { if (metadata.size >= 100) metadata.removeAt(0); metadata += value }
            override fun onProfessionalControlsRejected(message: String) { failures += "professional: $message" }
            override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
            override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
            override fun onAnalysis(analysis: Camera2Analysis) = Unit
            override fun onRecordingStarted(width: Int, height: Int) = Unit
            override fun onRecordingStopped(success: Boolean) = Unit
            override fun onFailure(code: String, message: String, recoverable: Boolean) { failures += "$code: $message" }
        }
        try {
            val descriptor = engine.descriptors(640, 480).first()
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
            compose.waitUntil(10_000) { surface.get()?.isValid == true }
            val output = requireNotNull(surface.get())
            engine.startPreview(descriptor, output, 0, listener)
            compose.waitUntil(15_000) { started.get() >= 1 || failures.isNotEmpty() }
            assertTrue(failures.toString(), failures.isEmpty())
            engine.attachSubjectPreview(1, exterior.surface, SubjectPreviewOptions()) { frames += it }
            engine.startPreview(descriptor, output, 0, listener, gpuPreview = true)
            compose.waitUntil(15_000) { (started.get() >= 2 && frames.any { it.isFresh(SystemClock.elapsedRealtime()) }) || failures.isNotEmpty() }
            assertTrue(failures.toString(), failures.isEmpty())
            assertTrue(frames.toString(), frames.any { it.isFresh(SystemClock.elapsedRealtime()) })
            exterior.acquireLatestImage()?.close()
            assertTrue(engine.detachOperatorFromActiveGpuPreview())
            val withoutOperator = frames.size
            compose.waitUntil(5_000) { frames.size > withoutOperator && frames.last().isFresh(SystemClock.elapsedRealtime()) }
            assertTrue(engine.attachOperatorToActiveGpuPreview(output, 0))
            assertEquals(2, started.get())
            // A stale lease must not detach the newer owner.
            engine.attachSubjectPreview(2, exterior.surface, SubjectPreviewOptions(mirror = true)) { frames += it }
            engine.detachSubjectPreview(1)
            val initial = frames.size
            compose.waitUntil(10_000) { frames.size > initial && frames.last().isFresh(SystemClock.elapsedRealtime()) }
            engine.detachSubjectPreview(2)
            engine.startPreview(descriptor, output, 0, listener, gpuPreview = false)
            compose.waitUntil(15_000) { started.get() >= 3 || failures.isNotEmpty() }
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(3, started.get())
            // Exercise the connected professional request path, not only the pure settings model.
            if (descriptor.exposureCapabilities.supports(ExposureMode.MANUAL)) {
                metadata.clear()
                engine.setProfessionalControls(ExposureSelection(ExposureMode.MANUAL, iso = 400, shutterUnit = ShutterUnit.ANGLE, angleTenths = 900), WhiteBalanceSelection.Auto)
                compose.waitUntil(10_000) { metadata.any { it.exposureMode == ExposureMode.MANUAL } || failures.isNotEmpty() }
                assertTrue(failures.toString(), failures.isEmpty())
                val actual = metadata.first { it.exposureMode == ExposureMode.MANUAL }
                assertTrue(requireNotNull(actual.exposureTimeNs) in requireNotNull(descriptor.exposureCapabilities.timeRangeNs))
                assertTrue(requireNotNull(actual.sensitivityIso) in requireNotNull(descriptor.exposureCapabilities.isoRange))
                android.util.Log.i("ProfessionalControlProbe", "MANUAL advertised=true reported=$actual")
            } else android.util.Log.i("ProfessionalControlProbe", "MANUAL advertised=false; unavailable branch retained")
            metadata.clear()
            engine.setProfessionalControls(ExposureSelection(), WhiteBalanceSelection.Auto)
            compose.waitUntil(10_000) { metadata.any { it.exposureMode == ExposureMode.AUTO } || failures.isNotEmpty() }
            assertTrue(failures.toString(), failures.isEmpty())
            assertTrue(metadata.any { it.exposureMode == ExposureMode.AUTO })
            android.util.Log.i("ProfessionalControlProbe", "AUTO restored; priorities=${descriptor.exposureCapabilities.priorities}; CCT=${descriptor.kelvinRange}; tint=${descriptor.tintSupported}")
            val originalProcessing = requireNotNull(metadata.last().submittedImageProcessing)
            val processingCaps = descriptor.imageProcessingCapabilities
            val noise = if (processingCaps.supports(IspMode.HIGH_QUALITY, ImageProcessingControl.NOISE_REDUCTION)) IspMode.HIGH_QUALITY else IspMode.DEFAULT
            val edge = if (processingCaps.supports(IspMode.OFF, ImageProcessingControl.EDGE)) IspMode.OFF else IspMode.DEFAULT
            val stabilization = if (processingCaps.supports(StabilizationMode.VIDEO)) StabilizationMode.VIDEO else null
            val requested = ImageProcessingSelection(stabilization = stabilization, noiseReduction = noise, edge = edge)
            val expected = requested.resolve(processingCaps, originalProcessing).values
            metadata.clear()
            engine.setProfessionalControls(ExposureSelection(), WhiteBalanceSelection.Auto, requested)
            compose.waitUntil(10_000) { metadata.any { it.submittedImageProcessing == expected } || failures.isNotEmpty() }
            assertTrue(failures.toString(), failures.isEmpty())
            val processingResult = metadata.first { it.submittedImageProcessing == expected }
            android.util.Log.i("ImageProcessingProbe", "caps=$processingCaps; requested=$requested; submitted=$expected; reported=$processingResult")
            metadata.clear()
            engine.setProfessionalControls(ExposureSelection(), WhiteBalanceSelection.Auto, ImageProcessingSelection())
            compose.waitUntil(10_000) { metadata.any { it.submittedImageProcessing == originalProcessing } || failures.isNotEmpty() }
            assertTrue(failures.toString(), failures.isEmpty())
            assertTrue(metadata.any { it.submittedImageProcessing == originalProcessing })
            android.util.Log.i("ImageProcessingProbe", "TEMPLATE restored: $originalProcessing")
            // Cancellation and camera recreation fence already-enqueued focus ticks.
            val focusLimit = descriptor.minimumFocusDistance
            if (focusLimit != null && focusLimit.isFinite() && focusLimit > 0f) {
                assertFalse(engine.startFocusPull(Float.NaN, 1000, FocusPullEasing.LINEAR))
                assertFalse(engine.startFocusPull(0f, 0, FocusPullEasing.LINEAR))
                assertFalse(engine.setFocusMark("A", Float.NaN))
                assertTrue(engine.setFocusMark("A", focusLimit / 2))
                assertTrue(engine.startFocusPull(focusLimit, 1500, FocusPullEasing.LINEAR))
                compose.waitUntil(3_000) { pullsStarted.get() == 1 }
                engine.setManualControls(null, null, null)
                compose.waitUntil(3_000) { pullsCancelled.get() == 1 && focusSelection.get() == null }
                SystemClock.sleep(1700)
                assertEquals(0, pullsFinished.get())
                assertNull(focusSelection.get())
                assertTrue(engine.startFocusPull(focusLimit / 2, 1500, FocusPullEasing.LINEAR))
                compose.waitUntil(3_000) { pullsStarted.get() == 2 }
                engine.startPreview(descriptor, output, 0, listener, gpuPreview = false)
                compose.waitUntil(15_000) { started.get() >= 4 || failures.isNotEmpty() }
                assertTrue(failures.toString(), failures.isEmpty())
                assertEquals(2, pullsCancelled.get())
                SystemClock.sleep(1700)
                assertEquals(0, pullsFinished.get())
                assertEquals(focusLimit / 2, engine.getFocusMarks()["A"])
                assertTrue(engine.startFocusPull(focusLimit / 4, 500, FocusPullEasing.LINEAR))
                compose.waitUntil(3_000) { pullsFinished.get() == 1 }
                assertEquals(focusLimit / 4, focusSelection.get())
                metadata.clear()
                engine.setManualControls(null, null, null)
                compose.waitUntil(3_000) { focusSelection.get() == null && metadata.any {
                    it.afMode != null && it.afMode != android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF &&
                        it.afMode == it.submittedAfMode && it.submittedFocusDistanceDiopters == null
                } }
                val autoResult = metadata.last { it.afMode != null && it.afMode == it.submittedAfMode && it.submittedFocusDistanceDiopters == null }
                android.util.Log.i("FocusSessionProbe", "AUTO request/result: submittedAf=${autoResult.submittedAfMode} reportedAf=${autoResult.afMode} submittedDistance=${autoResult.submittedFocusDistanceDiopters} reportedDistance=${autoResult.focusDistanceDiopters}")
                val other = engine.descriptors(640, 480).firstOrNull { it.cameraId != descriptor.cameraId }
                if (other != null) {
                    engine.setManualControls(null, null, focusLimit / 4)
                    compose.waitUntil(3_000) { focusSelection.get() == focusLimit / 4 }
                    engine.startPreview(other, output, 0, listener)
                    compose.waitUntil(15_000) { started.get() >= 5 || failures.isNotEmpty() }
                    assertTrue(failures.toString(), failures.isEmpty())
                    assertNull(focusSelection.get())
                    assertTrue(engine.getFocusMarks().isEmpty())
                    engine.startPreview(descriptor, output, 0, listener)
                    compose.waitUntil(15_000) { started.get() >= 6 || failures.isNotEmpty() }
                    assertTrue(failures.toString(), failures.isEmpty())
                    assertNull(focusSelection.get())
                    assertEquals(focusLimit / 2, engine.getFocusMarks()["A"])
                    android.util.Log.i("FocusSessionProbe", "cameraSwitchReset=true perCameraMarksRestored=true")
                }
                android.util.Log.i("FocusSessionProbe", "starts=${pullsStarted.get()} cancelled=${pullsCancelled.get()} finished=${pullsFinished.get()} oldTicksRejected=true sameCameraMark=${engine.getFocusMarks()["A"]} autoRestored=${focusSelection.get() == null}")
            } else android.util.Log.i("FocusSessionProbe", "manualFocusAdvertised=false; no physical focus claim")

            val prepared = AtomicReference<RecordingWhiteBalanceResult?>()
            engine.prepareRecordingWhiteBalance { prepared.set(it) }
            compose.waitUntil(6_000) { prepared.get() != null }
            val wbResult = requireNotNull(prepared.get())
            android.util.Log.i("RecordingWhiteBalanceProbe", "advertised=${descriptor.awbLockSupported}; result=$wbResult")
            if (descriptor.awbLockSupported) {
                assertEquals(RecordingWhiteBalanceStatus.LOCKED, wbResult.status)
                assertTrue(requireNotNull(wbResult.minimumSensorTimestampNs) > 0)
                compose.waitUntil(5_000) { metadata.any { it.awbLocked == true } }
                metadata.clear()
                engine.releaseRecordingWhiteBalance()
                compose.waitUntil(5_000) { metadata.any { it.awbLocked == false } }
                android.util.Log.i("RecordingWhiteBalanceProbe", "AUTO unlocked after release: ${metadata.last()}")
                // Generation cancellation must never report a late successful preparation.
                val cancelled = AtomicReference<RecordingWhiteBalanceResult?>()
                engine.prepareRecordingWhiteBalance { cancelled.set(it) }
                engine.releaseRecordingWhiteBalance()
                compose.waitUntil(5_000) { cancelled.get() != null }
                assertFalse(requireNotNull(cancelled.get()).ready)
                val preset = android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                if (preset in descriptor.availableAwbModes) {
                    engine.setProfessionalControls(ExposureSelection(), WhiteBalanceSelection.Preset(preset))
                    prepared.set(null)
                    engine.prepareRecordingWhiteBalance { prepared.set(it) }
                    compose.waitUntil(6_000) { prepared.get() != null }
                    assertEquals(RecordingWhiteBalanceStatus.FIXED, prepared.get()?.status)
                    assertTrue(requireNotNull(prepared.get()?.minimumSensorTimestampNs) > 0)
                    android.util.Log.i("RecordingWhiteBalanceProbe", "Fixed daylight confirmed: ${prepared.get()}")
                    engine.releaseRecordingWhiteBalance()
                    engine.setProfessionalControls(ExposureSelection(), WhiteBalanceSelection.Auto)
                }
            } else {
                assertFalse(wbResult.ready)
                assertEquals("white-balance-lock-unavailable", wbResult.failure)
            }

        } finally {
            engine.close()
            exterior.close()
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (Thread.getAllStackTraces().keys.any { it.name in setOf("OpenCineCamCamera", "SubjectPreviewGL") && it.isAlive } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertFalse("Camera or output worker survived closure", Thread.getAllStackTraces().keys.any { it.name in setOf("OpenCineCamCamera", "SubjectPreviewGL") && it.isAlive })
        }
    }
}
