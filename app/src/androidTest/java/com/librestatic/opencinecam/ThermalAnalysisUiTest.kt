/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.AnalysisSuspension
import com.librestatic.opencinecam.camera.MonitoringOptions
import com.librestatic.opencinecam.camera.MonitoringSignalDomain
import com.librestatic.opencinecam.camera.analyzeMonitoringRgb
import com.librestatic.opencinecam.service.CaptureService
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Thermal scope suspension as the operator sees it. The first test renders the production
 * composables from a THERMAL state; the second drives the real platform thermal status through
 * `cmd thermalservice` and waits for the service to report it. Neither qualifies how a physical
 * device heats up or how long it takes to cool down.
 */
class ThermalAnalysisUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thermalSuspensionShowsTheNoticeHidesStaleScopesAndPausesTheScopeButtons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = MonitoringOptions(waveformEnabled = true)
        val frame = analyzeMonitoringRgb(1, 1, byteArrayOf(127, 127, 127), options, MonitoringSignalDomain.SDR_BT709_CODE)
        val settings = CameraSettings(peakingEnabled = true, zebraEnabled = true, histogramEnabled = true, monitoring = options,
            operation = OperatorPreferences(button1 = OperatorAction.PEAKING, button2 = OperatorAction.ZEBRA, button3 = OperatorAction.HISTOGRAM))
        val state = mutableStateOf(CameraUiState(monitoringScopes = frame, analysisIntervalMs = 250,
            analysisSuspension = AnalysisSuspension.THERMAL))
        compose.setContent { MaterialTheme { Column(Modifier.width(360.dp).height(640.dp)) {
            Box(Modifier.weight(1f)) {
                // The samples are "fresh" by age; suspension alone must keep them off screen.
                ProfessionalScopesPanel(state.value, options, fresh = true)
                AnalysisSuspensionNotice(state.value)
            }
            OperatorButtonRow(state.value, settings, OperatorActions({}, {}, { operatorActionAvailable(it, state.value) }))
        } } }
        compose.onNodeWithTag("analysis-suspended-thermal").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.analysis_suspended_thermal))
        compose.onNodeWithTag("monitoring-waveform-graph").assertDoesNotExist()
        compose.onNodeWithTag("monitoring-freshness").assertTextEquals(context.getString(R.string.scope_suspended))
        for (index in 1..3) {
            compose.onNodeWithTag("operator-button-$index")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                    context.getString(R.string.operator_state_thermal_description)))
        }

        compose.runOnIdle { state.value = state.value.copy(analysisSuspension = AnalysisSuspension.NONE) }
        compose.onNodeWithTag("analysis-suspended-thermal").assertDoesNotExist()
        compose.onNodeWithTag("monitoring-waveform-graph").assertExists()
        for (index in 1..3) {
            compose.onNodeWithTag("operator-button-$index")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                    context.getString(R.string.operator_state_on_description)))
        }
    }

    @Test fun platformThermalStatusSuspendsAndResumesScopeAnalysisThroughTheService() {
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
        try {
            shell("cmd thermalservice override-status 0")
            compose.runOnUiThread {
                repository.set(CameraSettings(audioEnabled = false, peakingEnabled = true, zebraEnabled = true,
                    operation = OperatorPreferences(startupMode = StartupMode.PHOTO)))
            }
            bound = context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            compose.waitUntil(10_000) { binder.get() != null }
            val owner = requireNotNull(binder.get())
            compose.runOnUiThread { owner.prepare(640, 480); owner.selectMode(CaptureMode.PHOTO, reopen = false) }
            compose.waitUntil(15_000) { owner.cameraStates.value.descriptor != null }
            val descriptor = requireNotNull(owner.cameraStates.value.descriptor)
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
            assertEquals(AnalysisSuspension.NONE, owner.cameraStates.value.analysisSuspension)

            // PowerManager.THERMAL_STATUS_SEVERE.
            shell("cmd thermalservice override-status 5")
            compose.waitUntil(10_000) { owner.cameraStates.value.analysisSuspension == AnalysisSuspension.THERMAL }
            val hot = owner.cameraStates.value
            assertEquals("Suspending scopes must not stop the preview", CameraUiPhase.PREVIEWING, hot.phase)
            assertFalse(operatorActionAvailable(OperatorAction.PEAKING, hot))
            assertFalse(operatorActionAvailable(OperatorAction.ZEBRA, hot))

            shell("cmd thermalservice override-status 0")
            compose.waitUntil(10_000) { owner.cameraStates.value.analysisSuspension == AnalysisSuspension.NONE }
            assertEquals(CameraUiPhase.PREVIEWING, owner.cameraStates.value.phase)
            assertTrue(operatorActionAvailable(OperatorAction.PEAKING, owner.cameraStates.value))
            if (descriptor.analysisSize != null) {
                // The YUV scopes produce samples again once analysis resumes.
                val resumedAt = android.os.SystemClock.elapsedRealtime()
                compose.waitUntil(10_000) { owner.cameraStates.value.analysisUpdatedAtMs >= resumedAt }
            }
        } finally {
            try {
                shell("cmd thermalservice reset")
            } finally {
                try {
                    compose.runOnUiThread { binder.get()?.detachPreview() }
                    if (bound) context.unbindService(connection)
                    instrumentation.waitForIdleSync()
                } finally {
                    compose.runOnUiThread { repository.set(before) }
                }
            }
        }
    }

    /** Runs a shell command as the shell user and waits for it by reading its output to the end. */
    @Test fun thermalHudChipTracksPlatformThermalStatus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // While recording the chip appears as soon as the heat matters; a cool device shows none.
        compose.setContent { MaterialTheme { ThermalHudChip(recording = true) } }
        val critical = context.getString(R.string.thermal_hud_critical)
        try {
            shell("cmd thermalservice override-status 4")
            compose.waitUntil(10_000) { chipDescription()?.endsWith(critical) == true }
            compose.onNodeWithTag("thermal-hud").assertIsDisplayed()
            shell("cmd thermalservice override-status 0")
            // Headroom may keep a real forecast, which still shows an elevated chip while
            // recording; only the status-driven critical level must go away.
            compose.waitUntil(10_000) { chipDescription()?.endsWith(critical) != true }
            chipDescription()?.let { assertTrue(it, it.endsWith(context.getString(R.string.thermal_hud_elevated))) }
        } finally {
            shell("cmd thermalservice reset")
        }
    }

    /** The chip's description, or null while the chip is hidden. */
    private fun chipDescription(): String? = compose.onAllNodesWithTag("thermal-hud").fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }?.joinToString()

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }
}
