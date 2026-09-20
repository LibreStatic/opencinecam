/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.*
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.service.CaptureService
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual repository → service → Camera2 path; no encoder or physical-media qualification claim. */
class PresetServiceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun applyingPortablePresetReconfiguresPreviewAndReportsManualExposure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val repository = SettingsRepositories.get(context)
        val before = repository.states.value
        val binder = AtomicReference<CaptureService.LocalBinder?>()
        val surface = AtomicReference<android.view.Surface?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder.set(service as CaptureService.LocalBinder) }
            override fun onServiceDisconnected(name: ComponentName?) { binder.set(null) }
        }
        var bound = false
        try {
            compose.runOnUiThread { repository.set(CameraSettings(audioEnabled = false, videoWidth = 640, videoHeight = 480)) }
            bound = context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            compose.waitUntil(10_000) { binder.get() != null }
            val owner = requireNotNull(binder.get())
            compose.runOnUiThread { owner.prepare(640, 480) }
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
            val manual = d.exposureCapabilities.supports(ExposureMode.MANUAL)
            val profile = d.videoProfiles.first { !it.constrainedHighSpeed }
            val settings = repository.states.value.copy(videoWidth = profile.size.width, videoHeight = profile.size.height, videoFps = profile.fps,
                exposure = ExposureSelection(if (manual) ExposureMode.MANUAL else ExposureMode.AUTO, iso = 400, shutterUnit = ShutterUnit.ANGLE, angleTenths = 900),
                zebraEnabled = true)
            val preset = CameraPreset(name = "Service probe", settings = settings, mode = CaptureMode.VIDEO)
            compose.runOnUiThread { owner.applyPreset(preset) }
            compose.waitUntil(15_000) { owner.cameraStates.value.phase == CameraUiPhase.ERROR ||
                (owner.cameraStates.value.phase == CameraUiPhase.PREVIEWING && owner.cameraStates.value.selectedMode == CaptureMode.VIDEO &&
                    (!manual || owner.cameraStates.value.reportedExposureMode == ExposureMode.MANUAL)) }
            val state = owner.cameraStates.value
            assertEquals(state.message, CameraUiPhase.PREVIEWING, state.phase)
            assertEquals(CaptureMode.VIDEO, state.selectedMode)
            assertEquals(profile.size.width, state.targetVideoWidth); assertEquals(profile.fps, state.targetFps)
            assertTrue(requireNotNull(state.effectiveSettings).zebraEnabled)
            assertNull(state.pendingPresetName)
            if (manual) assertEquals(400, state.sensitivityIso)
            android.util.Log.i("PresetServiceProbe", "mode=${state.selectedMode} target=${state.targetVideoWidth}x${state.targetVideoHeight}@${state.targetFps} manual=$manual reported=${state.reportedExposureMode} ISO=${state.sensitivityIso} zebra=${state.effectiveSettings?.zebraEnabled} pending=${state.pendingPresetName}")
        } finally {
            compose.runOnUiThread { binder.get()?.detachPreview(); repository.set(before) }
            if (bound) context.unbindService(connection)
        }
    }
}
