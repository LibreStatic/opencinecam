/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam
import android.Manifest
import android.content.*
import android.os.IBinder
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.service.CaptureService
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OperatorLutServiceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun librarySelectionReachesActualOperatorAndDisablingRestoresDirectPreview() = verifySelection(false)
    @Test fun independentSubjectSelectionReachesPresentedSurfaceAndRetiresOnDetach() = verifySelection(true)

    @Test fun oldPresentedCallbackCannotReviveLutAfterSubjectModeRoundTrip() = verifySelection(true, checkStaleCallback = true)

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T

    private fun <T> onCamera(executor: java.util.concurrent.Executor, action: () -> T): java.util.concurrent.CompletableFuture<T> {
        val result = java.util.concurrent.CompletableFuture<T>()
        executor.execute { try { result.complete(action()) } catch (failure: Throwable) { result.completeExceptionally(failure) } }
        return result
    }

    private fun verifySelection(subjectEnabled: Boolean, checkStaleCallback: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        val repository = SettingsRepositories.get(context); val previous = repository.states.value
        val library = LutLibraries.get(context); val selectedBefore = library.states.value.operatorHash
        val subjectBefore = library.states.value.subjectHash
        var subjectLease: AutoCloseable? = null
        val subjectReader = if (subjectEnabled) android.media.ImageReader.newInstance(128,96,android.graphics.PixelFormat.RGBA_8888,3) else null
        val subjectPixel = AtomicReference<Int?>()
        val created = mutableListOf<String>()
        val bound = AtomicReference<CaptureService.LocalBinder?>(); val surface = AtomicReference<Surface?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { bound.set(service as CaptureService.LocalBinder) }
            override fun onServiceDisconnected(name: ComponentName?) { bound.set(null) }
        }
        var connected = false
        var engine: Camera2PreviewEngine? = null
        var subjectPipeline: OpenCineLogGpuPipeline? = null
        fun addLut(input: LutSignalDomain, rgb: String = "0 1 0"): String {
            val name = "Probe-${java.util.UUID.randomUUID()}"
            val bytes = ("TITLE \"$name\"\nLUT_3D_SIZE 2\n" + List(8) { rgb }.joinToString("\n") + "\n").toByteArray()
            val hash = library.importLut(bytes,name,LutTransformKind.CREATIVE,input).hash
            created += hash; return hash
        }
        try {
            val sdr = addLut(LutSignalDomain.SDR_BT709_CODE); library.select(sdr)
            library.selectSubject(null)
            compose.runOnUiThread { repository.set(CameraSettings(audioEnabled=false,
                operation=OperatorPreferences(startupMode=StartupMode.VIDEO))) }
            connected = context.bindService(Intent(context,CaptureService::class.java),connection,Context.BIND_AUTO_CREATE)
            assertTrue(connected); compose.waitUntil(10_000) { bound.get()!=null }
            val owner = requireNotNull(bound.get())
            engine = CaptureService::class.java.getDeclaredMethod("getPreviewEngine").apply { isAccessible = true }
                .invoke(field<CaptureService>(owner, "this$0")) as Camera2PreviewEngine
            compose.runOnUiThread { owner.prepare(640,480); owner.selectMode(CaptureMode.VIDEO,reopen=false) }
            compose.setContent { AndroidView(factory={ host -> SurfaceView(host).apply {
                holder.setSizeFromLayout()
                holder.addCallback(object:SurfaceHolder.Callback {
                    override fun surfaceCreated(holder:SurfaceHolder) { surface.set(holder.surface) }
                    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int) { surface.set(holder.surface) }
                    override fun surfaceDestroyed(holder:SurfaceHolder) { surface.set(null) }
                })
            } }, modifier=Modifier.fillMaxSize()) }
            compose.waitUntil(10_000) { surface.get()?.isValid==true }
            compose.runOnUiThread { assertTrue(owner.attachPreview(requireNotNull(surface.get()),0)) }
            compose.waitUntil(20_000) { owner.cameraStates.value.operatorLutStatus.state==OperatorLutState.ACTIVE || owner.cameraStates.value.phase==CameraUiPhase.ERROR }
            assertEquals(owner.cameraStates.value.message,OperatorLutState.ACTIVE,owner.cameraStates.value.operatorLutStatus.state)
            assertEquals(sdr,owner.cameraStates.value.operatorLutStatus.hash); assertTrue(owner.cameraStates.value.gpuViewfinder)
            if (subjectEnabled) {
                val cameraExecutor = field<java.util.concurrent.Executor>(requireNotNull(engine), "cameraExecutor")
                subjectPipeline = onCamera<OpenCineLogGpuPipeline>(cameraExecutor) { field(requireNotNull(engine), "logPipeline") }.get(10, java.util.concurrent.TimeUnit.SECONDS)
                val red = addLut(LutSignalDomain.SDR_BT709_CODE, "1 0 0")
                library.selectSubject(red)
                compose.waitUntil(10_000) { owner.cameraStates.value.subjectLutStatus.hash == red }
                assertEquals(OperatorLutState.WAITING_FOR_GPU, owner.cameraStates.value.subjectLutStatus.state)
                compose.runOnUiThread {
                    requireNotNull(subjectReader).setOnImageAvailableListener({ reader ->
                        reader.acquireLatestImage()?.use { image ->
                            val plane = image.planes[0]; val offset = 48*plane.rowStride + 64*plane.pixelStride
                            subjectPixel.set(android.graphics.Color.rgb(plane.buffer.get(offset).toInt() and 255,
                                plane.buffer.get(offset+1).toInt() and 255, plane.buffer.get(offset+2).toInt() and 255))
                        }
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                    repository.update { it.copy(subjectDisplay = it.subjectDisplay.copy(mode = SubjectDisplayMode.PREVIEW)) }
                    subjectLease = owner.subjectPreview.attach(subjectReader.surface, 0)
                }
                compose.waitUntil(20_000) { owner.cameraStates.value.subjectLutStatus.state == OperatorLutState.ACTIVE &&
                    subjectPixel.get() == android.graphics.Color.RED }
                assertEquals(red, owner.cameraStates.value.subjectLutStatus.hash)
                assertEquals(sdr, owner.cameraStates.value.operatorLutStatus.hash)
                if (checkStaleCallback) {
                    val callback = onCamera<(SubjectPreviewStatus) -> Unit>(cameraExecutor) {
                        field(field<Any>(requireNotNull(engine), "subjectTarget"), "onStatus")
                    }.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    val old = owner.subjectPreview.statuses.value
                    assertEquals(OperatorLutState.ACTIVE, requireNotNull(old.lutStatus).state)
                    val entered = java.util.concurrent.CountDownLatch(1)
                    val release = java.util.concurrent.CountDownLatch(1)
                    val worker = onCamera(cameraExecutor) {
                        entered.countDown(); check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    try {
                        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        val observed = java.util.concurrent.CompletableFuture<OperatorLutStatus>()
                        compose.runOnUiThread {
                            callback(old) // Enqueues the actual old receipt before invalidating its target.
                            val settings = repository.states.value
                            owner.applySettings(settings.copy(subjectDisplay = settings.subjectDisplay.copy(mode = SubjectDisplayMode.STATUS)))
                            owner.applySettings(settings)
                            android.os.Handler(android.os.Looper.getMainLooper()).post { observed.complete(owner.cameraStates.value.subjectLutStatus) }
                        }
                        assertEquals("Old presented receipt revived a replaced target", OperatorLutState.WAITING_FOR_GPU,
                            observed.get(10, java.util.concurrent.TimeUnit.SECONDS).state)
                    } finally { release.countDown(); worker.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                    compose.waitUntil(20_000) { owner.cameraStates.value.subjectLutStatus.state == OperatorLutState.ACTIVE }
                }
                val mismatch = addLut(LutSignalDomain.OCLOG2_CODE, "0 0 1")
                library.selectSubject(mismatch)
                compose.waitUntil(10_000) { owner.cameraStates.value.subjectLutStatus.state == OperatorLutState.INCOMPATIBLE_DOMAIN }
                assertEquals(OperatorLutState.ACTIVE, owner.cameraStates.value.operatorLutStatus.state)
                subjectPixel.set(null); library.selectSubject(red)
                compose.waitUntil(10_000) { owner.cameraStates.value.subjectLutStatus.state == OperatorLutState.ACTIVE && subjectPixel.get() == android.graphics.Color.RED }
                compose.runOnUiThread { subjectLease?.close(); subjectLease = null }
                compose.waitUntil(10_000) { owner.cameraStates.value.subjectLutStatus.state == OperatorLutState.WAITING_FOR_GPU }
                library.selectSubject(null)
                compose.waitUntil(10_000) { owner.cameraStates.value.subjectLutStatus == OperatorLutStatus() }
                compose.runOnUiThread { repository.update { it.copy(subjectDisplay = it.subjectDisplay.copy(mode = SubjectDisplayMode.STATUS)) } }
            }
            val log = addLut(LutSignalDomain.OCLOG2_CODE); library.select(log)
            compose.waitUntil(10_000) { owner.cameraStates.value.operatorLutStatus.state==OperatorLutState.INCOMPATIBLE_DOMAIN }
            assertEquals(log,owner.cameraStates.value.operatorLutStatus.hash)
            library.select(sdr)
            compose.waitUntil(10_000) { owner.cameraStates.value.operatorLutStatus.state==OperatorLutState.ACTIVE }
            library.select(null)
            compose.waitUntil(20_000) { owner.cameraStates.value.phase==CameraUiPhase.PREVIEWING && owner.cameraStates.value.operatorLutStatus == OperatorLutStatus() }
            // Video keeps the GPU viewfinder without a LUT, so REC never rebuilds the preview graph.
            assertTrue(owner.cameraStates.value.gpuViewfinder)
            assertEquals(OperatorLutStatus(),owner.cameraStates.value.operatorLutStatus)
            assertEquals(CaptureMode.VIDEO,owner.cameraStates.value.selectedMode)
        } finally {
            compose.runOnUiThread { subjectLease?.close(); bound.get()?.detachPreview() }
            // Camera retirement intentionally does not wait for the optional consumer. The
            // fixture owns this reader and also waits for that consumer's actual receipt.
            try {
                engine?.closeAsync()?.get(15, java.util.concurrent.TimeUnit.SECONDS)
                subjectPipeline?.let { pipeline ->
                    pipeline.closeAsync().get(15, java.util.concurrent.TimeUnit.SECONDS)
                    val retirements = field<List<java.util.concurrent.CompletableFuture<Unit>>>(pipeline, "subjectRetirements")
                    java.util.concurrent.CompletableFuture.allOf(*retirements.toTypedArray()).get(15, java.util.concurrent.TimeUnit.SECONDS)
                }
                compose.runOnUiThread { subjectReader?.setOnImageAvailableListener(null, null); subjectReader?.close() }
            } finally {
                if (connected) context.unbindService(connection)
                library.select(selectedBefore)
                library.selectSubject(subjectBefore)
                created.forEach { hash -> if (library.states.value.entries.any { it.hash==hash }) library.delete(hash) }
                compose.runOnUiThread { repository.set(previous) }
            }
        }
    }
}
