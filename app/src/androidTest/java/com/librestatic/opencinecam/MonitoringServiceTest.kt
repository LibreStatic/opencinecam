/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.MonitorColor
import com.librestatic.opencinecam.camera.MonitoringOptions
import com.librestatic.opencinecam.camera.MonitoringScopeFrame
import com.librestatic.opencinecam.camera.MonitoringSignalDomain
import com.librestatic.opencinecam.service.CaptureService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Camera2 YUV preview through the production settings repository and binder. This fixture
 * does not encode media or qualify physical color accuracy, HDR, LOG or a guaranteed update rate.
 */
class MonitoringServiceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun persistedLiveMonitoringChangesReachScopesWithoutReplacingThePreviewGraph() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val repository = SettingsRepositories.get(context)
        val before = repository.states.value
        val binder = AtomicReference<CaptureService.LocalBinder?>()
        val surface = AtomicReference<Surface?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder.set(service as CaptureService.LocalBinder) }
            override fun onServiceDisconnected(name: ComponentName?) { binder.set(null) }
        }
        var bound = false
        var cameraExecutor: Executor? = null
        var storageExecutor: Executor? = null
        try {
            val enabled = MonitoringOptions(waveformEnabled = true, vectorscopeEnabled = true,
                falseColorEnabled = true, refreshHz = 5, zebraColor = MonitorColor.RED, opacityPercent = 73)
            compose.runOnUiThread {
                repository.set(CameraSettings(audioEnabled = false, photoQuality = 73, monitoring = enabled,
                    operation = OperatorPreferences(startupMode = StartupMode.PHOTO, restoreExposureWhiteBalance = true)))
            }
            assertEquals(enabled, CameraSettingsStore(context).load().monitoring)
            bound = context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            compose.waitUntil(10_000) { binder.get() != null }
            val owner = requireNotNull(binder.get())
            compose.runOnUiThread { owner.prepare(640, 480); owner.selectMode(CaptureMode.PHOTO, reopen = false) }
            val descriptor = requireNotNull(owner.cameraStates.value.descriptor)
            assertNotNull("This fixture requires the actual YUV analysis output", descriptor.analysisSize)
            val outer = owner.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(owner)
            val engine = CaptureService::class.java.getDeclaredMethod("getPreviewEngine").apply { isAccessible = true }
                .invoke(outer) as Camera2PreviewEngine
            val executor = field(engine, "cameraExecutor") as Executor
            cameraExecutor = executor
            storageExecutor = CaptureService::class.java.getDeclaredField("storageExecutor").apply { isAccessible = true }.get(outer) as Executor
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
            compose.runOnUiThread { assertTrue(owner.attachPreview(requireNotNull(surface.get()), 0)) }
            compose.waitUntil(15_000) { owner.cameraStates.value.phase in setOf(CameraUiPhase.PREVIEWING, CameraUiPhase.ERROR) }
            assertEquals(owner.cameraStates.value.message, CameraUiPhase.PREVIEWING, owner.cameraStates.value.phase)
            // Drain the real startup capability probe before fixing unrelated settings for comparison.
            barrier(requireNotNull(storageExecutor))
            instrumentation.waitForIdleSync()
            var last = awaitFrame(owner, enabled, 0)
            val originalGraph = graph(engine, executor)
            val unrelated = requireNotNull(owner.cameraStates.value.effectiveSettings)
            assertEquals(73, unrelated.photoQuality)
            assertFalse(unrelated.audioEnabled)
            assertEquals(enabled, unrelated.monitoring)

            val configurations = listOf(
                enabled.copy(waveformEnabled = false, vectorscopeEnabled = false, falseColorEnabled = false, refreshHz = 2),
                enabled.copy(waveformEnabled = false, falseColorEnabled = false, refreshHz = 10, peakingThreshold = 81),
                enabled.copy(refreshHz = 4, zebraShadowEnabled = true, zebraLowPercent = 8,
                    falseColorBlackPercent = 3, falseColorShadowPercent = 20,
                    falseColorHighlightPercent = 80, falseColorClipPercent = 97),
            )
            for (options in configurations) {
                compose.runOnUiThread {
                    repository.update { it.copy(monitoring = options) }
                    // The existing binder API is applySettings; it is the same path used by its collector.
                    owner.applySettings(repository.states.value)
                }
                val persisted = CameraSettingsStore(context).load()
                assertEquals(repository.states.value, persisted)
                assertEquals(options, persisted.monitoring)
                val next = awaitFrame(owner, options, last.analysisUpdatedAtMs)
                assertEquals(unrelated.copy(monitoring = options), next.effectiveSettings)
                assertEquals(CameraUiPhase.PREVIEWING, next.phase)
                assertFalse(next.settingsPending)
                val currentGraph = graph(engine, executor)
                assertEquals(originalGraph.generation, currentGraph.generation)
                assertSame("Camera device must remain live", originalGraph.camera, currentGraph.camera)
                assertSame("Monitoring must not reconfigure the capture session", originalGraph.session, currentGraph.session)
                assertSame("The same native YUV reader supplies the new options", originalGraph.reader, currentGraph.reader)
                android.util.Log.i("MonitoringProbe", "domain=${requireNotNull(next.monitoringScopes).domain} " +
                    "samples=${next.monitoringScopes?.sampleCount} waveform=${options.waveformEnabled} " +
                    "vectorscope=${options.vectorscopeEnabled} falseColor=${options.falseColorEnabled} " +
                    "requestedHz=${options.refreshHz} updatedAtMs=${next.analysisUpdatedAtMs} graphUnchanged=true")
                last = next
            }
        } finally {
            try {
                compose.runOnUiThread { binder.get()?.detachPreview() }
                cameraExecutor?.let(::barrier)
                storageExecutor?.let(::barrier)
            } finally {
                try {
                    if (bound) context.unbindService(connection)
                    instrumentation.waitForIdleSync()
                } finally {
                    compose.runOnUiThread { repository.set(before) }
                }
            }
        }
    }

    private fun awaitFrame(owner: CaptureService.LocalBinder, options: MonitoringOptions, after: Long): CameraUiState {
        var accepted: CameraUiState? = null
        var acceptedAge = -1L
        compose.waitUntil(15_000) {
            val value = owner.cameraStates.value
            val scopes = value.monitoringScopes
            val age = SystemClock.elapsedRealtime() - value.analysisUpdatedAtMs
            val ready = scopes != null && scopes.options == options && value.analysisUpdatedAtMs > after && value.effectiveSettings?.monitoring == options &&
                age in 0..options.staleAfterMs &&
                scopes.waveformDensity.isNotEmpty() == options.waveformEnabled &&
                scopes.vectorscopeCounts.isNotEmpty() == options.vectorscopeEnabled &&
                scopes.falseColorBands.isNotEmpty() == options.falseColorEnabled
            if (ready) { accepted = value; acceptedAge = age }
            ready
        }
        val snapshot = requireNotNull(accepted)
        val scopes = requireNotNull(snapshot.monitoringScopes)
        assertEquals(MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR, scopes.domain)
        assertTrue(scopes.sampleCount in 1..MonitoringScopeFrame.MAX_PIXELS)
        assertEquals(scopes.sampleCount.toLong(), scopes.sampledWidth.toLong() * scopes.sampledHeight)
        for ((active, grid) in listOf(options.waveformEnabled to scopes.waveformDensity,
            options.vectorscopeEnabled to scopes.vectorscopeCounts)) {
            assertEquals(if (active) MonitoringScopeFrame.GRID_SIZE * MonitoringScopeFrame.GRID_SIZE else 0, grid.size)
            assertTrue(grid.all { it >= 0 })
            assertEquals(if (active) scopes.sampleCount.toLong() else 0L, grid.sumOf { it.toLong() })
        }
        assertEquals(if (options.falseColorEnabled) MonitoringScopeFrame.FALSE_COLOR_WIDTH * MonitoringScopeFrame.FALSE_COLOR_HEIGHT else 0,
            scopes.falseColorBands.size)
        assertTrue("The observed frame must be fresh, not a cached stale result", acceptedAge in 0..options.staleAfterMs)
        assertTrue(snapshot.analysisUpdatedAtMs > after)
        return snapshot
    }

    private data class Graph(val generation: Long, val camera: Any, val session: Any, val reader: Any)
    private fun graph(engine: Camera2PreviewEngine, executor: Executor): Graph {
        val result = AtomicReference<Graph?>()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        executor.execute {
            try { result.set(Graph(field(engine, "generation") as Long, requireNotNull(field(engine, "camera")),
                requireNotNull(field(engine, "session")), requireNotNull(field(engine, "analysisReader")))) }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("Native preview graph was unavailable", it) }
        return requireNotNull(result.get())
    }
    private fun field(engine: Camera2PreviewEngine, name: String): Any? =
        Camera2PreviewEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)
    private fun barrier(executor: Executor) {
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        assertTrue("Accepted fixture work must retire before teardown", done.await(20, TimeUnit.SECONDS))
    }
}
