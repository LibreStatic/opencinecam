/* SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(androidx.window.core.ExperimentalWindowApi::class)

package com.librestatic.opencinecam

import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.WindowSdkExtensions
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Public capability inventory only. Never starts a display session or opens a camera. */
class FoldDisplayProbeTest {
    @Test fun recordAdvertisedWindowAreasWithoutPromotingPhysicalSupport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val areas = withTimeout(5_000) {
            withContext(Dispatchers.Main) { WindowAreaController.getOrCreate().windowAreaInfos.first() }
        }
        val report = JSONObject()
            .put("schema", "opencinecam-fold-display-probe-v1")
            .put("createdAtEpochMs", System.currentTimeMillis())
            .put("sdk", Build.VERSION.SDK_INT)
            .put("model", Build.MODEL)
            .put("fingerprint", Build.FINGERPRINT)
            .put("windowExtensionVersion", WindowSdkExtensions.getInstance().extensionVersion)
            .put("physicalQualification", "NOT_RUN")
            .put("cameraIds", JSONArray(context.getSystemService(CameraManager::class.java).cameraIdList.toList()))
            .put("hingeAngleSensorAdvertised", if (Build.VERSION.SDK_INT >= 30) context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null else false)
            .put("areas", JSONArray().apply {
                areas.forEach { info ->
                    put(JSONObject()
                        .put("type", info.type.toString())
                        .put("bounds", info.metrics.bounds.toShortString())
                        .put("presentation", info.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA).status.toString())
                        .put("transfer", info.getCapability(WindowAreaCapability.Operation.OPERATION_TRANSFER_ACTIVITY_TO_AREA).status.toString()))
                }
            })
        Log.i("FoldDisplayProbe", "FOLD_DISPLAY_PROBE_JSON:$report")
        assertTrue(report.has("areas"))
    }
}
