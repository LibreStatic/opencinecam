/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.*
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.service.CaptureService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real JPEG publication and request/result identity, not physical illumination qualification. */
class PhotoFlashServiceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sharedJpegReaderStillPublishesTheCompleteBurst() = exercise(PhotoFlashSelection(), legacyMode = CaptureMode.BURST)
    @Test fun sharedJpegReaderStillPublishesAllBracketImages() = exercise(PhotoFlashSelection(), legacyMode = CaptureMode.BRACKET)
    @Test fun sharedJpegReaderStillPublishesLongExposure() = exercise(PhotoFlashSelection(), legacyMode = CaptureMode.LIGHT_TRAIL)
    @Test fun offPublishesTwoCorrelatedJpegsAndRejectsDuplicateAdmission() = exercise(PhotoFlashSelection(), duplicate = true)
    @Test fun cancelledQueuedPhotoNeverSubmitsOrPublishes() = exercise(PhotoFlashSelection(), cancelBeforeRun = true)
    @Test fun offKeepsTheRequestedContinuousTorchWithoutAddingAPulse() = exercise(PhotoFlashSelection(), torch = true)
    @Test fun autoCapturesOrRejectsItsActualAdvertisedCapability() = exercise(PhotoFlashSelection(PhotoFlashMode.AUTO))
    @Test fun forcedCapturesOrRejectsItsActualAdvertisedCapability() = exercise(PhotoFlashSelection(PhotoFlashMode.ON))
    @Test fun manualForcedPreservesManualExposureOrRejectsMissingCapability() = exercise(PhotoFlashSelection(PhotoFlashMode.ON), manual = true)
    @Test fun queuedManualControlsAndForcedFlashUseOneExposureSnapshot() = exercise(PhotoFlashSelection(PhotoFlashMode.ON), queuedManual = true)
    @Test fun outOfRangePulseNeverCreatesAnImage() = exercise(PhotoFlashSelection(PhotoFlashMode.ON, Int.MAX_VALUE))

    @Test fun operatorLutKeepsForcedFlashPrecaptureOnCameraInput() = withOperatorLutFixture { hash ->
        exercise(PhotoFlashSelection(PhotoFlashMode.ON), operatorHash = hash)
    }

    @Test fun photographicLutCancellationWithExteriorLeaseDoesNotLeaveAnOwnerlessCapture() = withOperatorLutFixture { hash ->
        exercise(PhotoFlashSelection(), cancelBeforeRun = true, operatorHash = hash, exteriorLease = true)
    }

    private fun exercise(selection: PhotoFlashSelection, duplicate: Boolean = false, manual: Boolean = false, queuedManual: Boolean = false, torch: Boolean = false, legacyMode: CaptureMode? = null, cancelBeforeRun: Boolean = false, operatorHash: String? = null, exteriorLease: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val repository = SettingsRepositories.get(context)
        val before = repository.states.value
        val binder = AtomicReference<CaptureService.LocalBinder?>()
        val surface = AtomicReference<android.view.Surface?>()
        val release = CountDownLatch(1)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun rows(): Set<Long> = requireNotNull(context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?", arrayOf(context.packageName), null)).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
        val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        fun metadataRows(): Set<Long> = requireNotNull(context.contentResolver.query(downloads, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?", arrayOf(context.packageName), null)).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
        val initialMetadata = metadataRows()
        val initialRows = rows()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder.set(service as CaptureService.LocalBinder) }
            override fun onServiceDisconnected(name: ComponentName?) { binder.set(null) }
        }
        var bound = false
        var auxiliary: android.media.ImageReader? = null
        var auxiliaryLease: AutoCloseable? = null
        try {
            compose.runOnUiThread { repository.set(CameraSettings(audioEnabled = false, photoFlash = selection, flashEnabled = torch, burstCount = 3,
                exposure = ExposureSelection(if (manual) ExposureMode.MANUAL else ExposureMode.AUTO, iso = 100, timeNs = 10_000_000),
                operation = OperatorPreferences(startupMode = StartupMode.PHOTO, restoreExposureWhiteBalance = true, restoreTorch = torch))) }
            bound = context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound); compose.waitUntil(10_000) { binder.get() != null }
            val owner = requireNotNull(binder.get())
            compose.runOnUiThread { owner.prepare(640, 480); owner.selectMode(legacyMode ?: CaptureMode.PHOTO, reopen = false) }
            val d = requireNotNull(owner.cameraStates.value.descriptor)
            compose.setContent { AndroidView(factory = { host -> SurfaceView(host).apply {
                holder.setFixedSize(d.previewSize.width, d.previewSize.height)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { surface.set(holder.surface) }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surface.set(holder.surface) }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { surface.set(null) }
                })
            } }, modifier = Modifier.fillMaxSize()) }
            compose.waitUntil(10_000) { surface.get()?.isValid == true }
            compose.runOnUiThread { assertTrue(owner.attachPreview(requireNotNull(surface.get()), 0)) }
            compose.waitUntil(15_000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.ERROR) }
            assertEquals(owner.cameraStates.value.message, CameraUiPhase.PREVIEWING, owner.cameraStates.value.phase)
            if (operatorHash != null) {
                compose.waitUntil(15_000) { owner.cameraStates.value.operatorLutStatus.state == OperatorLutState.ACTIVE }
                assertEquals(operatorHash, owner.cameraStates.value.operatorLutStatus.hash)
                assertTrue(owner.cameraStates.value.gpuViewfinder)
            }
            if (torch) assertTrue(requireNotNull(owner.cameraStates.value.effectiveSettings).flashEnabled)
            val mode = if (manual || queuedManual) ExposureMode.MANUAL else ExposureMode.AUTO
            val expected = selection.resolve(d.photoFlashCapabilities, mode)
            val outer = owner.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(owner)
            val engine = CaptureService::class.java.getDeclaredMethod("getPreviewEngine").apply { isAccessible = true }.invoke(outer) as Camera2PreviewEngine
            if (exteriorLease) {
                auxiliary = android.media.ImageReader.newInstance(128, 96, android.graphics.PixelFormat.RGBA_8888, 2)
                compose.runOnUiThread {
                    repository.update { it.copy(subjectDisplay = it.subjectDisplay.copy(mode = SubjectDisplayMode.PREVIEW)) }
                    auxiliaryLease = owner.subjectPreview.attach(requireNotNull(auxiliary).surface, 0)
                }
                compose.waitUntil(5_000) { owner.cameraStates.value.effectiveSettings?.subjectDisplay?.mode == SubjectDisplayMode.PREVIEW }
            }
            if (duplicate || queuedManual || cancelBeforeRun) {
                val entered = CountDownLatch(1)
                val executor = Camera2PreviewEngine::class.java.getDeclaredField("cameraExecutor").apply { isAccessible = true }.get(engine) as java.util.concurrent.Executor
                executor.execute { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
            }
            compose.runOnUiThread {
                if (queuedManual) engine.setProfessionalControls(ExposureSelection(ExposureMode.MANUAL, iso=100, timeNs=10_000_000), WhiteBalanceSelection.Auto)
                owner.capturePrimary(false)
                if (cancelBeforeRun) {
                    owner.detachPreview(); assertFalse(engine.cancelJpeg())
                    if (exteriorLease) {
                        assertEquals("No capture owner remains after queued cancellation", CameraUiPhase.READY, owner.cameraStates.value.phase)
                        assertFalse(owner.cameraStates.value.stillCapturePending)
                        assertFalse(owner.cameraStates.value.captureControlsLocked)
                    }
                }
                if (duplicate) assertFalse("Duplicate queued JPEG must reject synchronously", engine.captureJpeg(selection))
            }
            if (duplicate) {
                compose.runOnUiThread { repository.update { it.copy(photoFlash = PhotoFlashSelection(PhotoFlashMode.ON), flashEnabled = true) } }
                compose.waitUntil(5_000) { owner.cameraStates.value.settingsPending }
                compose.runOnUiThread {
                    val persisted = repository.states.value
                    owner.applyPreset(CameraPreset(name="Blocked during photo",settings=CameraSettings(),mode=CaptureMode.VIDEO))
                    assertEquals(persisted,repository.states.value)
                    assertEquals(CaptureMode.PHOTO,owner.cameraStates.value.selectedMode)
                    assertTrue(owner.cameraStates.value.captureControlsLocked)
                }
                compose.runOnIdle {
                    assertEquals(selection, requireNotNull(owner.cameraStates.value.effectiveSettings).photoFlash)
                    assertFalse(requireNotNull(owner.cameraStates.value.effectiveSettings).flashEnabled)
                }
            }
            release.countDown()
            if (cancelBeforeRun) {
                val retired=CountDownLatch(1)
                val executor=Camera2PreviewEngine::class.java.getDeclaredField("cameraExecutor").apply {isAccessible=true}.get(engine) as java.util.concurrent.Executor
                executor.execute {retired.countDown()};assertTrue(retired.await(5,TimeUnit.SECONDS))
                assertNull(Camera2PreviewEngine::class.java.getDeclaredField("photoRequest").apply {isAccessible=true}.get(engine).let {it as java.util.concurrent.atomic.AtomicReference<*>}.get())
                assertEquals(initialRows,rows());assertNull(owner.cameraStates.value.photoFlashReport)
                android.util.Log.i("PhotoFlashProbe","cancelQueued=true nativeSubmission=false photoOwnerRetired=true images=0")
                return
            }
            compose.waitUntil(20_000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.SAVED, CameraUiPhase.ERROR) }
            if (legacyMode != null) {
                val expectedCount = if (legacyMode == CaptureMode.LIGHT_TRAIL) 1 else 3
                compose.waitUntil(20_000) {
                    val added = rows()-initialRows
                    added.size == expectedCount && added.all { id ->
                        requireNotNull(context.contentResolver.query(ContentUris.withAppendedId(collection,id),arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)).use { it.moveToFirst() && it.getInt(0)==0 }
                    }
                }
                assertEquals(owner.cameraStates.value.message,CameraUiPhase.SAVED,owner.cameraStates.value.phase)
                assertNull(owner.cameraStates.value.photoFlashReport)
                for (id in rows()-initialRows) {
                    val bytes=requireNotNull(context.contentResolver.openInputStream(ContentUris.withAppendedId(collection,id))).use {it.readBytes()}
                    requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)).recycle()
                }
                android.util.Log.i("PhotoFlashProbe","legacy=$legacyMode published=$expectedCount decoded=true flashReportAbsent=true")
                return
            }
            if (expected is PhotoFlashResolution.Rejected) {
                assertEquals(CameraUiPhase.ERROR, owner.cameraStates.value.phase)
                assertNull(owner.cameraStates.value.photoFlashReport); assertEquals(initialRows, rows())
                android.util.Log.i("PhotoFlashProbe", "requested=$selection result=REJECTED reason=${expected.reason} images=0 capabilities=${d.photoFlashCapabilities}")
                return
            }
            assertEquals(owner.cameraStates.value.message, CameraUiPhase.SAVED, owner.cameraStates.value.phase)
            fun verify(index: Int): Uri {
                val state = owner.cameraStates.value
                val report = requireNotNull(state.photoFlashReport)
                val plan = expected as PhotoFlashResolution.Plan
                assertEquals(selection, report.requested); assertEquals(if (torch && d.torchCapabilities.available) 2 else plan.flashMode, report.submittedFlashMode)
                if (plan.aeMode != null) assertEquals(plan.aeMode, report.submittedAeMode)
                assertEquals(if (torch && d.torchCapabilities.adjustable) d.torchCapabilities.defaultLevel else plan.strength, report.requestedStrength)
                assertTrue(requireNotNull(report.sensorTimestampNs) > 0)
                val uri = Uri.parse(requireNotNull(state.lastSavedUri))
                requireNotNull(context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE), null,null,null)).use {
                    assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0));assertEquals("image/jpeg",it.getString(1))
                }
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
                assertTrue(bitmap.width > 0 && bitmap.height > 0); bitmap.recycle()
                java.io.File(context.cacheDir,"photo-flash-${selection.mode}-${selection.strength}-$manual-$queuedManual-$torch-$index.jpg").writeBytes(bytes)
                android.util.Log.i("PhotoFlashProbe", "requested=$selection report=$report published=true decoded=true bytes=${bytes.size} uri=$uri physicalIlluminationAccepted=false")
                return uri
            }
            val first = verify(1); assertEquals(1, (rows()-initialRows).size)
            if (duplicate) {
                compose.waitUntil(5_000) { !owner.cameraStates.value.settingsPending }
                compose.runOnUiThread { repository.update { it.copy(photoFlash = selection, flashEnabled = false) } }
                compose.waitUntil(5_000) { owner.cameraStates.value.effectiveSettings?.photoFlash == selection && owner.cameraStates.value.effectiveSettings?.flashEnabled == false }
                compose.runOnUiThread { assertTrue(owner.capturePrimary(false)) }
                compose.waitUntil(20_000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.SAVED,CameraUiPhase.ERROR) }
                assertEquals(owner.cameraStates.value.message,CameraUiPhase.SAVED,owner.cameraStates.value.phase)
                assertNotEquals(first, verify(2)); assertEquals(2,(rows()-initialRows).size)
            }
        } finally {
            release.countDown()
            try {
                compose.runOnUiThread {
                    binder.get()?.detachPreview()
                    if (cancelBeforeRun) assertEquals(CameraUiPhase.READY, binder.get()?.cameraStates?.value?.phase)
                }
            } finally {
                try {
                    compose.runOnUiThread { auxiliaryLease?.close(); repository.set(before) }
                } finally {
                    auxiliary?.close()
                    if (bound) context.unbindService(connection)
                    (rows()-initialRows).forEach { context.contentResolver.delete(ContentUris.withAppendedId(collection,it),null,null) }
                    (metadataRows()-initialMetadata).forEach { context.contentResolver.delete(ContentUris.withAppendedId(downloads,it),null,null) }
                }
            }
        }
    }
}
