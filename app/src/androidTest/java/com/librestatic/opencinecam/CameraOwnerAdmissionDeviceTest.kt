/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.os.Handler
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Camera2/EGL owners; latches control retirement, never an elapsed-time approximation. */
class CameraOwnerAdmissionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val uiClicks = AtomicInteger()

    @Test fun queuedEngineCreatesNoCameraOrEglUntilPriorLeaseRetiresWhileUiRemainsResponsive() {
        grantCamera()
        val admission = CaptureOwnerAdmission()
        val prior = admission.acquire()
        prior.ready().get(5, TimeUnit.SECONDS)
        val retirement = CompletableFuture<Unit>()
        prior.releaseAfter(retirement)
        val engine = Camera2PreviewEngine(context, ownerAdmission = admission)
        val probe = Probe()
        try {
            val descriptor = engine.descriptors(640, 480).first()
            val surface = showSurface(descriptor)
            compose.runOnUiThread { engine.startPreview(descriptor, surface, 0, probe, gpuPreview = true) }
            assertTrue("Opening event must acknowledge queued request", probe.opening.await(5, TimeUnit.SECONDS))
            assertNoNativeOwner(engine)
            assertEquals(0, probe.started.get())
            assertEquals(0, probe.metadata.get())
            clickResponsiveUi()
            retirement.complete(Unit)
            awaitPreview(probe)
            onCameraQueue(engine) {
                assertNotNull(field(engine, "camera"))
                assertNotNull(field(engine, "logPipeline"))
            }
            assertTrue(engine.usesGpuViewfinder())
            android.util.Log.i("CameraOwnerAdmissionProbe", "queued=true cameraSnapshotEmptyBeforeReady=true eglPipelineSnapshotEmptyBeforeReady=true uiClick=true nativePreviewAfterRetirement=true")
        } finally {
            retirement.complete(Unit)
            engine.closeAsync().get(15, TimeUnit.SECONDS)
        }
    }

    @Test fun closingQueuedEngineWaitsForPredecessorAndNeverResurrectsItsStalePreview() {
        grantCamera()
        val admission = CaptureOwnerAdmission()
        val prior = admission.acquire()
        prior.ready().get(5, TimeUnit.SECONDS)
        val retirement = CompletableFuture<Unit>()
        prior.releaseAfter(retirement)
        val engine = Camera2PreviewEngine(context, ownerAdmission = admission)
        val probe = Probe()
        var next: CaptureOwnerAdmission.Lease? = null
        try {
            val descriptor = engine.descriptors(640, 480).first()
            val surface = showSurface(descriptor)
            compose.runOnUiThread { engine.startPreview(descriptor, surface, 0, probe, gpuPreview = true) }
            assertTrue(probe.opening.await(5, TimeUnit.SECONDS))
            assertNoNativeOwner(engine)
            val closed = engine.closeAsync()
            assertFalse("Closed queued owner must retain the predecessor fence", closed.isDone)
            val successor = admission.acquire().also { next = it }
            assertFalse(successor.ready().isDone)
            // Wait for local cleanup, rather than mistaking an unprocessed close task for a
            // retained predecessor fence. Even after native retirement, the lease must wait.
            (field(engine, "nativeRetirement") as CompletableFuture<*>).get(5, TimeUnit.SECONDS)
            assertFalse("Native cleanup alone must not complete the queued owner", closed.isDone)
            assertFalse("A successor must still await the held predecessor", successor.ready().isDone)
            clickResponsiveUi()
            retirement.complete(Unit)
            closed.get(15, TimeUnit.SECONDS)
            successor.ready().get(5, TimeUnit.SECONDS)
            assertEquals("Cancelled queued preview must never start", 0, probe.started.get())
            assertEquals(0, probe.metadata.get())
            assertNull(field(engine, "camera"))
            assertNull(field(engine, "logPipeline"))
            assertTrue(probe.failures.toString(), probe.failures.isEmpty())
            // Calling the disposed instance again must not acquire or reopen anything.
            compose.runOnUiThread { engine.startPreview(descriptor, surface, 0, probe, gpuPreview = true) }
            assertEquals(0, probe.started.get())
            assertNull(field(engine, "camera"))
            android.util.Log.i("CameraOwnerAdmissionProbe", "queuedClose=true predecessorFenceRetained=true uiClick=true nativeSnapshotEmptyBeforeAndAfterClose=true localCleanupCompletedBeforePredecessor=true stalePreviewResurrected=false")
        } finally {
            retirement.complete(Unit)
            next?.releaseAfter(CompletableFuture.completedFuture(Unit))
            engine.closeAsync().get(15, TimeUnit.SECONDS)
        }
    }

    @Test fun sameSurfaceSuccessorWaitsUntilCameraAndBlockedNativeGpuRetirementFinish() {
        grantCamera()
        val admission = CaptureOwnerAdmission()
        // Construction alone must not reserve the native owner: start first despite creating
        // the later successor first. Eager acquisition would deadlock this initial preview.
        val second = Camera2PreviewEngine(context, ownerAdmission = admission)
        val first = Camera2PreviewEngine(context, ownerAdmission = admission)
        val firstProbe = Probe()
        val secondProbe = Probe()
        val glEntered = CountDownLatch(1)
        val releaseGl = CountDownLatch(1)
        val glWaitTimedOut = AtomicBoolean()
        try {
            val descriptor = first.descriptors(640, 480).first()
            val surface = showSurface(descriptor)
            compose.runOnUiThread { first.startPreview(descriptor, surface, 0, firstProbe, gpuPreview = true) }
            awaitPreview(firstProbe)
            val pipelineRef = AtomicReference<OpenCineLogGpuPipeline>()
            onCameraQueue(first) { pipelineRef.set(field(first, "logPipeline") as OpenCineLogGpuPipeline) }
            val pipeline = requireNotNull(pipelineRef.get())
            val input = pipeline.cameraInputSurface
            assertTrue(input.isValid)
            val glHandler = field(pipeline, "handler") as Handler
            assertTrue(glHandler.post {
                glEntered.countDown()
                if (!releaseGl.await(60, TimeUnit.SECONDS)) glWaitTimedOut.set(true)
            })
            assertTrue("Native GL worker must enter controlled hold", glEntered.await(5, TimeUnit.SECONDS))
            val closed = first.closeAsync()
            compose.runOnUiThread { second.startPreview(descriptor, surface, 0, secondProbe, gpuPreview = true) }
            assertTrue(secondProbe.opening.await(5, TimeUnit.SECONDS))
            assertNoNativeOwner(second)
            assertFalse("closeAsync must await held EGL worker", closed.isDone)
            assertTrue("Camera input remains owned until GL retirement", input.isValid)
            assertEquals(0, secondProbe.started.get())
            clickResponsiveUi()
            releaseGl.countDown()
            closed.get(15, TimeUnit.SECONDS)
            assertFalse("Completion must follow native input release", input.isValid)
            assertNull(field(first, "camera"))
            assertNull(field(first, "closingCamera"))
            awaitPreview(secondProbe)
            assertFalse("Latch deadline is not the release mechanism", glWaitTimedOut.get())
            onCameraQueue(second) {
                assertSame("The successor uses the exact same Surface object", surface, field(second, "previewSurface"))
                assertNotNull(field(second, "camera"))
                assertNotNull(field(second, "logPipeline"))
            }
            android.util.Log.i("CameraOwnerAdmissionProbe", "sameSurface=true heldGl=true closeFutureWaited=true inputReleasedBeforeCompletion=true cameraOnClosedBeforeCompletion=true successorNativePreview=true uiClick=true")
        } finally {
            releaseGl.countDown()
            val firstClosed = first.closeAsync()
            val secondClosed = second.closeAsync()
            firstClosed.get(15, TimeUnit.SECONDS)
            secondClosed.get(15, TimeUnit.SECONDS)
        }
    }

    @Test fun occupiedNativeWindowInitializationFailureRetiresBeforeTheNextOwnerReusesIt() {
        grantCamera()
        // Distinct coordinators deliberately reproduce an external owner of the native window.
        val occupied = Camera2PreviewEngine(context, ownerAdmission = CaptureOwnerAdmission())
        val admission = CaptureOwnerAdmission()
        val failed = Camera2PreviewEngine(context, ownerAdmission = admission)
        val successor = Camera2PreviewEngine(context, ownerAdmission = admission)
        val occupiedProbe = Probe()
        val successorProbe = Probe()
        val receiptCaptured = CountDownLatch(1)
        val receipt = AtomicReference<CompletableFuture<*>?>()
        val receiptFailure = AtomicReference<Throwable?>()
        val failedProbe = Probe(onError = { code ->
            if (code == "video-preview-gpu-init-failed") {
                try {
                    // onFailure runs on the camera executor immediately after the receipt is
                    // registered. Its queued completion callback cannot remove it until return.
                    val retirements = field(failed, "initializationRetirements") as Set<*>
                    receipt.set(retirements.single() as CompletableFuture<*>)
                } catch (error: Throwable) { receiptFailure.set(error) }
                finally { receiptCaptured.countDown() }
            }
        })
        try {
            val descriptor = occupied.descriptors(640, 480).first()
            val surface = showSurface(descriptor)
            compose.runOnUiThread { occupied.startPreview(descriptor, surface, 0, occupiedProbe, gpuPreview = true) }
            awaitPreview(occupiedProbe)
            compose.runOnUiThread { failed.startPreview(descriptor, surface, 0, failedProbe, gpuPreview = true) }
            compose.waitUntil(15_000) { failedProbe.failures.isNotEmpty() }
            assertTrue(failedProbe.failures.toString(), receiptCaptured.await(5, TimeUnit.SECONDS))
            assertNull("Initialization receipt inspection failed", receiptFailure.get())
            val nativeReceipt = requireNotNull(receipt.get())
            assertEquals(0, failedProbe.started.get())
            assertEquals(0, failedProbe.metadata.get())
            assertNoNativeOwner(failed)
            clickResponsiveUi()
            failed.closeAsync().thenRun {
                assertTrue("Engine completion must follow failed-constructor native cleanup", nativeReceipt.isDone)
                assertFalse(nativeReceipt.isCompletedExceptionally)
                assertFalse(nativeReceipt.isCancelled)
            }.get(15, TimeUnit.SECONDS)
            val nextLease = admission.acquire()
            try { nextLease.ready().get(5, TimeUnit.SECONDS) }
            finally { nextLease.releaseAfter(CompletableFuture.completedFuture(Unit)) }
            val occupiedMetadataBefore = occupiedProbe.metadata.get()
            val occupiedAnalysisBefore = occupiedProbe.analysis.get()
            compose.waitUntil(5_000) {
                (occupiedProbe.metadata.get() > occupiedMetadataBefore && occupiedProbe.analysis.get() > occupiedAnalysisBefore) ||
                    occupiedProbe.failures.isNotEmpty()
            }
            assertTrue("Failed constructor cleanup must preserve the external owner: ${occupiedProbe.failures}", occupiedProbe.failures.isEmpty())
            assertTrue("External owner must keep delivering capture results after failed cleanup", occupiedProbe.metadata.get() > occupiedMetadataBefore)
            assertTrue("External GPU must keep delivering analysis after failed cleanup", occupiedProbe.analysis.get() > occupiedAnalysisBefore)
            occupied.closeAsync().get(15, TimeUnit.SECONDS)
            assertTrue("Owner retirement must not release the caller Surface", surface.isValid)
            compose.runOnUiThread { successor.startPreview(descriptor, surface, 0, successorProbe, gpuPreview = true) }
            awaitPreview(successorProbe)
            onCameraQueue(successor) { assertSame(surface, field(successor, "previewSurface")) }
            android.util.Log.i("CameraOwnerAdmissionProbe", "occupiedWindowInitFailed=true receiptTracked=true nativeCleanupBeforeClose=true failedCameraSnapshotEmpty=true admissionAdvanced=true externalOwnerMetadataContinued=true externalOwnerGpuAnalysisContinued=true sameSurfaceRecoveryPreview=true")
        } finally {
            val completions = listOf(occupied.closeAsync(), failed.closeAsync(), successor.closeAsync())
            completions.forEach { it.get(15, TimeUnit.SECONDS) }
        }
    }

    private fun grantCamera() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
    }

    private fun showSurface(descriptor: Camera2CameraDescriptor): Surface {
        val output = AtomicReference<Surface?>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { host ->
                        SurfaceView(host).apply {
                            holder.setFixedSize(descriptor.previewSize.width, descriptor.previewSize.height)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) { output.set(holder.surface) }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { output.set(holder.surface) }
                                override fun surfaceDestroyed(holder: SurfaceHolder) { output.set(null) }
                            })
                        }
                    }, modifier = Modifier.fillMaxSize())
                    Button(onClick = { uiClicks.incrementAndGet() }, modifier = Modifier.align(Alignment.TopStart).testTag("owner-ui-heartbeat")) {
                        Text("UI heartbeat")
                    }
                }
            }
        }
        compose.waitUntil(10_000) { output.get()?.isValid == true }
        return requireNotNull(output.get())
    }

    private fun clickResponsiveUi() {
        val before = uiClicks.get()
        compose.onNodeWithTag("owner-ui-heartbeat").performClick()
        compose.runOnIdle { assertEquals(before + 1, uiClicks.get()) }
    }

    private fun awaitPreview(probe: Probe) {
        compose.waitUntil(15_000) { probe.started.get() > 0 || probe.failures.isNotEmpty() }
        assertTrue(probe.failures.toString(), probe.failures.isEmpty())
        assertEquals(1, probe.started.get())
        compose.waitUntil(5_000) { probe.metadata.get() > 0 || probe.failures.isNotEmpty() }
        assertTrue(probe.failures.toString(), probe.failures.isEmpty())
        assertTrue("Native capture results must follow preview", probe.metadata.get() > 0)
    }

    private fun assertNoNativeOwner(engine: Camera2PreviewEngine) = onCameraQueue(engine) {
        assertNull("Admission must precede CameraDevice creation", field(engine, "camera"))
        assertNull("Admission must precede EGL pipeline creation", field(engine, "logPipeline"))
        assertFalse(field(engine, "cameraOpening") as Boolean)
    }

    private fun onCameraQueue(engine: Camera2PreviewEngine, check: () -> Unit) {
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        (field(engine, "cameraExecutor") as Executor).execute {
            try { check() } catch (error: Throwable) { failure.set(error) } finally { finished.countDown() }
        }
        assertTrue("Camera executor barrier must remain responsive", finished.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private class Probe(private val onError: (String) -> Unit = {}) : Camera2PreviewListener {
        val opening = CountDownLatch(1)
        val started = AtomicInteger()
        val metadata = AtomicInteger()
        val analysis = AtomicInteger()
        val failures = CopyOnWriteArrayList<String>()
        override fun onOpening(descriptor: Camera2CameraDescriptor) { opening.countDown() }
        override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) { started.incrementAndGet() }
        override fun onMetadata(metadata: Camera2PreviewMetadata) { this.metadata.incrementAndGet() }
        override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
        override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
        override fun onAnalysis(analysis: Camera2Analysis) { this.analysis.incrementAndGet() }
        override fun onRecordingStarted(width: Int, height: Int) = Unit
        override fun onRecordingStopped(success: Boolean) = Unit
        override fun onFailure(code: String, message: String, recoverable: Boolean) {
            failures += "$code: $message"
            onError(code)
        }
    }
}
