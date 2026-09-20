/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.Camera2PreviewListener
import com.librestatic.opencinecam.camera.CaptureOwnerAdmission
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Camera2 session and result; only callback delivery order is controlled by this fixture. */
class PreviewStartCallbackDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun replacementCallbackAnnouncesRealSessionOnceEvenIfOriginalCallbackArrivesLate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val engine = Camera2PreviewEngine(context, ownerAdmission = CaptureOwnerAdmission())
        val initialStarts = AtomicInteger()
        val replacementStarts = AtomicInteger()
        val failures = CopyOnWriteArrayList<String>()
        val initialListener = listener(initialStarts, failures)
        val replacementListener = listener(replacementStarts, failures)
        val surface = AtomicReference<Surface?>()
        try {
            val descriptor = engine.descriptors(640, 480).first { it.jpegSize != null }
            compose.setContent {
                AndroidView(factory = { host ->
                    SurfaceView(host).apply {
                        holder.setFixedSize(descriptor.previewSize.width, descriptor.previewSize.height)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) { surface.set(holder.surface) }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surface.set(holder.surface) }
                            override fun surfaceDestroyed(holder: SurfaceHolder) { surface.set(null) }
                        })
                    }
                }, modifier = Modifier.fillMaxSize())
            }
            compose.waitUntil(10_000) { surface.get()?.isValid == true }
            val target = requireNotNull(surface.get())
            compose.runOnUiThread { engine.startPreview(descriptor, target, 0, initialListener, gpuPreview = false) }
            compose.waitUntil(15_000) { initialStarts.get() > 0 || failures.isNotEmpty() }
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(1, initialStarts.get())
            assertFalse(engine.usesGpuViewfinder())
            val executor = field(engine, "cameraExecutor") as Executor
            val receipt = CompletableFuture<NativeResult>()
            executor.execute {
                try {
                    val session = field(engine, "session") as CameraCaptureSession
                    val request = (field(engine, "repeatingBuilder") as CaptureRequest.Builder).build()
                    assertSame("The request builder belongs to this live viewfinder graph", target, field(engine, "previewSurface"))
                    session.captureSingleRequest(request, executor, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            receipt.complete(NativeResult(session, request, result))
                        }
                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            receipt.completeExceptionally(AssertionError("Native single request failed: ${failure.reason}"))
                        }
                        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                            receipt.completeExceptionally(AssertionError("Native single request aborted: $sequenceId"))
                        }
                    })
                } catch (failure: Throwable) { receipt.completeExceptionally(failure) }
            }
            val native = receipt.get(10, TimeUnit.SECONDS)
            assertTrue(native.result.frameNumber >= 0)
            assertTrue(requireNotNull(native.result.get(CaptureResult.SENSOR_TIMESTAMP)) > 0)
            val checked = CompletableFuture<Unit>()
            executor.execute {
                val listenerField = Camera2PreviewEngine::class.java.getDeclaredField("listener").apply { isAccessible = true }
                val previous = listenerField.get(engine)
                try {
                    assertSame(native.session, field(engine, "session"))
                    native.session.stopRepeating()
                    listenerField.set(engine, replacementListener)
                    val factory = Camera2PreviewEngine::class.java.getDeclaredMethod("previewCaptureCallback",
                        Camera2CameraDescriptor::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }
                    // The initial callback is armed but deliberately not delivered before its
                    // repeating request is replaced. No capability/result/elapsed time is forged.
                    val original = factory.invoke(engine, descriptor, true) as CameraCaptureSession.CaptureCallback
                    val replacement = factory.invoke(engine, descriptor, false) as CameraCaptureSession.CaptureCallback
                    assertEquals(0, replacementStarts.get())
                    replacement.onCaptureCompleted(native.session, native.request, native.result)
                    assertEquals("Replacing the initial repeat must not lose the session's preview-start receipt", 1, replacementStarts.get())
                    original.onCaptureCompleted(native.session, native.request, native.result)
                    assertEquals("A late original callback must not announce the same preview twice", 1, replacementStarts.get())
                    replacement.onCaptureCompleted(native.session, native.request, native.result)
                    assertEquals("Later results do not reannounce an already-started session", 1, replacementStarts.get())
                    assertTrue(failures.toString(), failures.isEmpty())
                    checked.complete(Unit)
                } catch (failure: Throwable) { checked.completeExceptionally(failure) }
                finally { listenerField.set(engine, previous) }
            }
            checked.get(10, TimeUnit.SECONDS)
            android.util.Log.i("PreviewStartCallbackProbe", "nativeSingleResult=true originalWithheld=true replacementStart=1 lateOriginalDuplicate=false")
        } finally {
            // The Surface belongs to SurfaceView. Retire Camera2 before the Compose fixture
            // releases that view; never release another owner's Surface or touch MediaStore.
            engine.closeAsync().get(20, TimeUnit.SECONDS)
        }
    }

    private data class NativeResult(val session: CameraCaptureSession, val request: CaptureRequest, val result: TotalCaptureResult)
    private fun field(engine: Camera2PreviewEngine, name: String): Any? =
        Camera2PreviewEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)
    private fun listener(starts: AtomicInteger, failures: MutableList<String>): Camera2PreviewListener =
        Proxy.newProxyInstance(Camera2PreviewListener::class.java.classLoader, arrayOf(Camera2PreviewListener::class.java)) { _, method, args ->
            when (method.name) {
                "onPreviewStarted" -> starts.incrementAndGet()
                "onFailure" -> failures.add("${args?.getOrNull(0)}: ${args?.getOrNull(1)}")
            }
            null
        } as Camera2PreviewListener
}
