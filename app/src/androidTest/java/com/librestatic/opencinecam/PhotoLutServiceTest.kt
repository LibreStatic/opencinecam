/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
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
import com.librestatic.opencinecam.storage.StillPublication
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Camera2 stills and MediaStore stay native while PixelCopy observes the real GPU operator output.
 * DNG platform decoding is not a claim about physical RAW quality or a calibrated color response.
 */
class PhotoLutServiceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun greenOperatorJpegStaysNativeWhileDisableWaitsForTheHeldPublication() = exercise(StillPhotoFormat.JPEG, holdWriter = true)
    @Test fun greenOperatorRawJpegPairPublishesBothUnmodifiedMembers() = exercise(StillPhotoFormat.RAW_JPEG)
    @Test fun greenOperatorRawModePublishesNativeDngAndReturnsToDirectPreview() = exercise(StillPhotoFormat.DNG)

    private fun exercise(format: StillPhotoFormat, holdWriter: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val repository = SettingsRepositories.get(context)
        val previous = repository.states.value
        val library = LutLibraries.get(context)
        assertNull("Fixture must preserve, not reset, an existing valid library", library.states.value.error)
        val previousHash = library.states.value.operatorHash
        val ownedHashes = mutableListOf<String>()
        val ownedRows = linkedSetOf<String>()
        val binder = AtomicReference<CaptureService.LocalBinder?>()
        val surface = AtomicReference<Surface?>()
        val delivered = AtomicReference<CapturedStill?>()
        val deliveryCount = AtomicInteger()
        val release = CountDownLatch(1)
        var cameraExecutor: Executor? = null
        var storageWriter: ThreadPoolExecutor? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder.set(service as CaptureService.LocalBinder) }
            override fun onServiceDisconnected(name: ComponentName?) { binder.set(null) }
        }
        var bound = false
        fun rememberPublication(publication: StillPublication?) {
            if (publication != null && publication.sensorTimestampNs == delivered.get()?.sensorTimestampNs) {
                ownedRows += publication.metadataUri
                ownedRows += publication.images.map { it.uri }
            }
        }
        try {
            val fixtureName = "Photo-green-${UUID.randomUUID()}"
            val bytes = ("TITLE \"$fixtureName\"\nLUT_3D_SIZE 2\n" + List(8) { "0 1 0" }.joinToString("\n") + "\n").toByteArray()
            val hash = library.importLut(bytes, fixtureName, LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE).hash
            ownedHashes += hash
            library.select(hash)
            compose.runOnUiThread {
                repository.set(CameraSettings(audioEnabled = false, photoQuality = 73,
                    photoFormat = if (format == StillPhotoFormat.DNG) StillPhotoFormat.JPEG else format,
                    operation = OperatorPreferences(startupMode = StartupMode.PHOTO, lockDuringTake = false)))
            }
            bound = context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound); compose.waitUntil(10_000) { binder.get() != null }
            val owner = requireNotNull(binder.get())
            compose.runOnUiThread { owner.prepare(640, 480) }
            val descriptor = requireNotNull(owner.cameraStates.value.descriptor)
            if (format != StillPhotoFormat.JPEG) {
                assertTrue("This RAW acceptance fixture requires advertised native RAW, not a JPEG fallback", descriptor.supportsRaw)
                assertNotNull(descriptor.rawSize)
            }
            val mode = if (format == StillPhotoFormat.DNG) CaptureMode.RAW_PHOTO else CaptureMode.PHOTO
            compose.runOnUiThread { owner.selectMode(mode, reopen = false) }
            assertEquals(mode, owner.cameraStates.value.selectedMode)
            val outer = owner.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(owner)
            val engine = CaptureService::class.java.getDeclaredMethod("getPreviewEngine").apply { isAccessible = true }.invoke(outer) as Camera2PreviewEngine
            val executor = Camera2PreviewEngine::class.java.getDeclaredField("cameraExecutor").apply { isAccessible = true }.get(engine) as Executor
            val writer = CaptureService::class.java.getDeclaredField("storageExecutor").apply { isAccessible = true }.get(outer) as ThreadPoolExecutor
            cameraExecutor = executor; storageWriter = writer
            compose.setContent {
                AndroidView(factory = { host -> SurfaceView(host).apply {
                    // Keep a Camera2-advertised buffer size valid for both GPU and direct preview.
                    holder.setFixedSize(descriptor.previewSize.width, descriptor.previewSize.height)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { surface.set(holder.surface) }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surface.set(holder.surface) }
                        override fun surfaceDestroyed(holder: SurfaceHolder) { surface.set(null) }
                    })
                } }, modifier = Modifier.fillMaxSize())
            }
            compose.waitUntil(10_000) { surface.get()?.isValid == true }
            compose.runOnUiThread { assertTrue(owner.attachPreview(requireNotNull(surface.get()), 0)) }
            compose.waitUntil(20_000) {
                val state = owner.cameraStates.value
                (state.operatorLutStatus.state == OperatorLutState.ACTIVE && state.phase == CameraUiPhase.PREVIEWING) || state.phase == CameraUiPhase.ERROR
            }
            assertEquals(owner.cameraStates.value.message, OperatorLutState.ACTIVE, owner.cameraStates.value.operatorLutStatus.state)
            assertEquals(hash, owner.cameraStates.value.operatorLutStatus.hash)
            assertTrue(owner.cameraStates.value.gpuViewfinder)
            awaitOperatorColor(requireNotNull(surface.get()), green = true)
            if (holdWriter) {
                fun generation(): Long = Camera2PreviewEngine::class.java.getDeclaredField("generation")
                    .apply { isAccessible = true }.getLong(engine)
                fun reopened(change: () -> Unit) {
                    val beforeGeneration = generation()
                    compose.runOnUiThread(change)
                    compose.waitUntil(20_000) {
                        generation() > beforeGeneration && owner.cameraStates.value.phase == CameraUiPhase.PREVIEWING &&
                            owner.cameraStates.value.operatorLutStatus.state == OperatorLutState.ACTIVE
                    }
                    assertEquals(hash, owner.cameraStates.value.operatorLutStatus.hash)
                    assertTrue(owner.cameraStates.value.gpuViewfinder)
                    awaitOperatorColor(requireNotNull(surface.get()), green = true)
                }
                assertTrue("Mode round-trip fixture requires advertised RAW", descriptor.supportsRaw)
                reopened { owner.selectMode(CaptureMode.RAW_PHOTO) }
                assertEquals(CaptureMode.RAW_PHOTO, owner.cameraStates.value.selectedMode)
                reopened { owner.selectMode(CaptureMode.PHOTO) }
                // FPS is a video-only control; exercise its supported route, then return to stills.
                reopened { owner.selectMode(CaptureMode.VIDEO) }
                val videoSize = requireNotNull(owner.cameraStates.value.activeVideoProfile).size
                val otherFps = requireNotNull(descriptor.videoProfiles.firstOrNull {
                    it.size == videoSize && !it.constrainedHighSpeed && it.fps != owner.cameraStates.value.targetFps
                }?.fps) { "Video FPS reopen fixture requires another advertised regular range at this size" }
                reopened { owner.selectTargetFps(otherFps) }
                assertEquals(otherFps, owner.cameraStates.value.targetFps)
                reopened { owner.selectMode(CaptureMode.PHOTO) }
                assertEquals(CaptureMode.PHOTO, owner.cameraStates.value.selectedMode)
            }
            barrier(writer); instrumentation.waitForIdleSync()
            val installed = CountDownLatch(1)
            val installFailure = AtomicReference<Throwable?>()
            executor.execute {
                try {
                    fun fieldValue(name: String) = Camera2PreviewEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)
                    val repeat = fieldValue("repeatingBuilder") as CaptureRequest.Builder
                    assertEquals("Photographic GPU preview must retain picture AF", CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                        repeat.get(CaptureRequest.CONTROL_AF_MODE))
                    assertEquals(true, fieldValue("gpuPhotoPreviewEnabled"))
                    assertNull("GPU supplies pre-LUT analysis; no redundant Camera2 YUV stream", fieldValue("analysisReader"))
                    assertNull("Still readers must not be replaced by a video profile", fieldValue("activeVideoProfile"))
                    assertNotNull(fieldValue("jpegReader") as? ImageReader)
                    if (format != StillPhotoFormat.JPEG) assertNotNull(fieldValue("rawReader") as? ImageReader)
                    val field = Camera2PreviewEngine::class.java.getDeclaredField("listener").apply { isAccessible = true }
                    val original = field.get(engine) as Camera2PreviewListener
                    field.set(engine, object : Camera2PreviewListener by original {
                        override fun onStillCaptured(capture: CapturedStill) {
                            deliveryCount.incrementAndGet(); delivered.set(capture)
                            original.onStillCaptured(capture)
                        }
                    })
                } catch (failure: Throwable) { installFailure.set(failure) }
                finally { installed.countDown() }
            }
            assertTrue(installed.await(5, TimeUnit.SECONDS))
            installFailure.get()?.let { throw AssertionError("Listener installation failed", it) }
            if (holdWriter) {
                val held = CountDownLatch(1)
                writer.execute { held.countDown(); check(release.await(35, TimeUnit.SECONDS)) }
                assertTrue(held.await(5, TimeUnit.SECONDS))
            }
            compose.runOnUiThread {
                assertTrue(owner.capturePrimary(false))
                assertTrue(owner.cameraStates.value.stillCapturePending)
                assertFalse("Only one service photo owner may be admitted", owner.capturePrimary(false))
            }
            if (holdWriter) {
                compose.waitUntil(15_000) { writer.queue.size == 1 || owner.cameraStates.value.phase == CameraUiPhase.ERROR }
                assertEquals(owner.cameraStates.value.message, 1, writer.queue.size)
                assertNotNull(delivered.get()); assertNull(owner.cameraStates.value.lastStillPublication)
                library.select(null)
                compose.runOnUiThread { repository.update { it.copy(photoQuality = 81) } }
                compose.waitUntil(5_000) { owner.cameraStates.value.settingsPending && owner.cameraStates.value.operatorLutStatus.state == OperatorLutState.DISABLED }
                compose.runOnUiThread {
                    assertTrue(owner.cameraStates.value.stillCapturePending)
                    assertTrue(owner.cameraStates.value.captureControlsLocked)
                    assertTrue(owner.cameraStates.value.structuralSettingsFrozen)
                    assertEquals(73, owner.cameraStates.value.effectiveSettings?.photoQuality)
                    assertFalse("LUT change cannot retire the pending publication owner", owner.capturePrimary(false))
                }
            }
            release.countDown()
            compose.waitUntil(25_000) { owner.cameraStates.value.lastStillPublication != null || owner.cameraStates.value.phase == CameraUiPhase.ERROR }
            val publication = requireNotNull(owner.cameraStates.value.lastStillPublication) { owner.cameraStates.value.message ?: "No still publication" }
            rememberPublication(publication)
            val native = requireNotNull(delivered.get())
            assertEquals(1, deliveryCount.get())
            assertEquals(73, native.quality)
            assertEquals(native.sensorTimestampNs, publication.sensorTimestampNs)
            val expectedKinds = when (format) {
                StillPhotoFormat.JPEG -> setOf(StillImageKind.JPEG)
                StillPhotoFormat.RAW_JPEG -> setOf(StillImageKind.JPEG, StillImageKind.DNG)
                StillPhotoFormat.DNG -> setOf(StillImageKind.DNG)
                else -> error("Not part of this fixture")
            }
            assertEquals(expectedKinds, publication.images.map { it.kind }.toSet())
            assertEquals(expectedKinds, native.images.map { it.kind }.toSet())
            val resolver = context.contentResolver
            fun read(uri: String) = requireNotNull(resolver.openInputStream(uri.toUri())).use { it.readBytes() }
            val metadata = Json.parseToJsonElement(read(publication.metadataUri).toString(Charsets.UTF_8)).jsonObject
            assertEquals(native.captureId, metadata.getValue("captureId").jsonPrimitive.long)
            assertEquals(73, metadata.getValue("quality").jsonPrimitive.int)
            assertEquals(publication.id, metadata.getValue("bundleId").jsonPrimitive.content)
            for (image in publication.images) {
                val content = read(image.uri)
                val source = native.images.single { it.kind == image.kind }
                assertArrayEquals("GPU operator LUT must never alter ${image.kind} bytes", source.bytes, content)
                assertEquals(content.size.toLong(), image.bytes)
                assertEquals(image.sha256, MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) })
                val relation = metadata.getValue("images").jsonArray.single { it.jsonObject.getValue("kind").jsonPrimitive.content == image.kind.name }.jsonObject
                assertEquals(image.sha256, relation.getValue("sha256").jsonPrimitive.content)
                assertEquals(image.uri, relation.getValue("uri").jsonPrimitive.content)
                assertEquals(native.sensorTimestampNs, relation.getValue("sensorTimestampNs").jsonPrimitive.long)
                val decoded = requireNotNull(BitmapFactory.decodeByteArray(content, 0, content.size)) { "Platform decode failed: ${image.kind}" }
                try {
                    assertTrue(decoded.width > 0 && decoded.height > 0)
                    assertTrue("Saved ${image.kind} is the native scene, not the constant-green operator LUT", greenSampleCount(decoded, centered = false) < 27)
                } finally { decoded.recycle() }
                android.util.Log.i("PhotoLutProbe", "format=$format kind=${image.kind} bundle=${publication.id} sensor=${native.sensorTimestampNs} " +
                    "sha256=${image.sha256} byteIdenticalToNative=true decoded=true operatorConstantGreenNotBaked=true")
            }
            for (uri in ownedRows) {
                requireNotNull(resolver.query(uri.toUri(), arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
            if (!holdWriter) library.select(null)
            compose.waitUntil(20_000) {
                val state = owner.cameraStates.value
                state.phase == CameraUiPhase.PREVIEWING && !state.gpuViewfinder && !state.stillCapturePending && !state.settingsPending
            }
            assertEquals(OperatorLutStatus(), owner.cameraStates.value.operatorLutStatus)
            assertEquals(mode, owner.cameraStates.value.selectedMode)
            if (holdWriter) assertEquals(81, owner.cameraStates.value.effectiveSettings?.photoQuality)
            awaitOperatorColor(requireNotNull(surface.get()), green = false)
        } finally {
            release.countDown()
            try {
                compose.runOnUiThread { binder.get()?.detachPreview() }
                cameraExecutor?.let(::barrier)
                storageWriter?.let(::barrier)
                instrumentation.waitForIdleSync()
                rememberPublication(binder.get()?.cameraStates?.value?.lastStillPublication)
            } finally {
                try {
                    if (bound) context.unbindService(connection)
                    instrumentation.waitForIdleSync()
                    library.select(previousHash)
                    ownedHashes.forEach { hash -> if (library.states.value.entries.any { it.hash == hash }) library.delete(hash) }
                } finally {
                    compose.runOnUiThread { repository.set(previous) }
                    ownedRows.forEach { context.contentResolver.delete(it.toUri(), null, null) }
                }
            }
        }
    }

    private fun awaitOperatorColor(surface: Surface, green: Boolean) {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val handler = Handler(Looper.getMainLooper())
        var lastResult = -1
        var lastGreen = -1
        val copyLock = Any()
        var inFlight = false
        var recycleAfterCallback = false
        try {
            compose.waitUntil(20_000) {
                val copied = CountDownLatch(1)
                val result = AtomicInteger(-1)
                synchronized(copyLock) { inFlight = true }
                try {
                    PixelCopy.request(surface, bitmap, { code ->
                        result.set(code)
                        synchronized(copyLock) { inFlight = false; if (recycleAfterCallback) bitmap.recycle() }
                        copied.countDown()
                    }, handler)
                } catch (failure: Exception) { synchronized(copyLock) { inFlight = false }; throw failure }
                check(copied.await(5, TimeUnit.SECONDS)) { "PixelCopy callback did not retire" }
                lastResult = result.get()
                if (lastResult == PixelCopy.SUCCESS) lastGreen = greenSampleCount(bitmap, centered = true)
                lastResult == PixelCopy.SUCCESS && if (green) lastGreen == 36 else lastGreen < 27
            }
            assertEquals(PixelCopy.SUCCESS, lastResult)
            assertTrue("Operator pixels: expectedGreen=$green actualGreenSamples=$lastGreen/36", if (green) lastGreen == 36 else lastGreen < 27)
        } finally { synchronized(copyLock) {
            // A timeout is not retirement: a pending PixelCopy owns the bitmap until its callback.
            if (inFlight) recycleAfterCallback = true else bitmap.recycle()
        } }
    }
    private fun greenSampleCount(bitmap: Bitmap, centered: Boolean): Int {
        var count = 0
        for (row in 0 until 6) for (column in 0 until 6) {
            val x = if (centered) ((0.4 + column * 0.04) * bitmap.width).toInt() else ((column + 0.5) * bitmap.width / 6).toInt()
            val y = if (centered) ((0.4 + row * 0.04) * bitmap.height).toInt() else ((row + 0.5) * bitmap.height / 6).toInt()
            val color = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
            if (Color.red(color) < 30 && Color.green(color) > 220 && Color.blue(color) < 30) count++
        }
        return count
    }
    private fun barrier(executor: Executor) {
        val done = CountDownLatch(1); executor.execute { done.countDown() }
        assertTrue("Owned work must retire before fixture cleanup", done.await(25, TimeUnit.SECONDS))
    }
}
