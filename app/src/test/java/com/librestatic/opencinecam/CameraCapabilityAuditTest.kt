/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.util.Size
import com.librestatic.opencinecam.camera.Antibanding
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.CharacteristicEntry
import com.librestatic.opencinecam.camera.ExposureCapabilities
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.TorchCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityAuditTest {
    private fun camera(
        exposure: ExposureCapabilities = ExposureCapabilities(),
        torch: TorchCapabilities = TorchCapabilities(false),
        kelvin: IntRange? = null,
        awbModes: Set<Int> = emptySet(),
        afModes: List<Int> = emptyList(),
        minimumFocus: Float? = null,
        realtime: Boolean = false,
    ) = Camera2CameraDescriptor(
        cameraId = "audit", lensFacing = 1, focalLengthsMm = listOf(5f), previewSize = Size(1920, 1080),
        jpegSize = null, rawSize = null, analysisSize = null, sensorOrientation = 90, sensitivityRange = null,
        exposureTimeRangeNs = null, aeCompensationRange = null, aeCompensationStep = 0f, minimumFocusDistance = minimumFocus,
        supportsRaw = false, flashAvailable = torch.available, targetFpsRanges = emptyList(), availableFixedFps = listOf(30),
        videoProfiles = emptyList(), logProfiles = emptyList(), torchCapabilities = torch, kelvinRange = kelvin,
        availableAwbModes = awbModes, availableAfModes = afModes, timestampSourceRealtime = realtime,
        exposureCapabilities = exposure,
    )

    private fun List<AppFeatureFinding>.of(feature: AppFeature) = first { it.feature == feature }

    @Test fun everyFeatureIsReportedOnceInOrder() {
        assertEquals(AppFeature.entries, auditCameraCapabilities(camera()).map { it.feature })
    }

    @Test fun bareCameraLeavesProFeaturesUnavailable() {
        val audit = auditCameraCapabilities(camera())
        for (feature in listOf(AppFeature.VIDEO, AppFeature.HIGH_SPEED, AppFeature.OCLOG, AppFeature.MANUAL_EXPOSURE,
            AppFeature.WHITE_BALANCE, AppFeature.RAW, AppFeature.TORCH, AppFeature.LENS_SWITCH, AppFeature.FOCUS)) {
            assertEquals(feature.name, AppFeatureStatus.UNAVAILABLE, audit.of(feature).status)
        }
        assertEquals(AppFeatureStatus.PARTIAL, audit.of(AppFeature.SENSOR_CLOCK).status)
    }

    @Test fun manualSensorReportsItsRanges() {
        val audit = auditCameraCapabilities(camera(exposure = ExposureCapabilities(
            manual = true, isoRange = 50..6400, timeRangeNs = 125_000L..30_000_000_000L,
            priorities = setOf(ExposureMode.SHUTTER_PRIORITY, ExposureMode.ISO_PRIORITY),
            antibanding = setOf(Antibanding.AUTO, Antibanding.HZ50, Antibanding.HZ60),
        )))
        assertEquals(AppFeatureFinding(AppFeature.MANUAL_EXPOSURE, AppFeatureStatus.ACTIVE, "ISO 50–6400 · 1/8000–30 s"), audit.of(AppFeature.MANUAL_EXPOSURE))
        assertEquals("ISO · Shutter", audit.of(AppFeature.EXPOSURE_PRIORITY).detail)
        assertEquals(AppFeatureStatus.ACTIVE, audit.of(AppFeature.ANTIBANDING).status)
    }

    @Test fun whiteBalanceFallsBackToPresetsAndTorchToOnOff() {
        val presetsOnly = auditCameraCapabilities(camera(awbModes = setOf(0, 1, 2, 5, 6), torch = TorchCapabilities(true)))
        assertEquals(AppFeatureFinding(AppFeature.WHITE_BALANCE, AppFeatureStatus.PARTIAL, "3 presets"), presetsOnly.of(AppFeature.WHITE_BALANCE))
        assertEquals(AppFeatureStatus.PARTIAL, presetsOnly.of(AppFeature.TORCH).status)
        val kelvin = auditCameraCapabilities(camera(kelvin = 2000..10000, torch = TorchCapabilities(true, 5, 3)))
        assertEquals(AppFeatureStatus.ACTIVE, kelvin.of(AppFeature.WHITE_BALANCE).status)
        assertTrue(kelvin.of(AppFeature.WHITE_BALANCE).detail.startsWith("2000–10000 K"))
        assertEquals("1–5", kelvin.of(AppFeature.TORCH).detail)
    }

    @Test fun manualFocusNeedsAFocusRangeAndAfOff() {
        val manual = auditCameraCapabilities(camera(afModes = listOf(0, 1, 3), minimumFocus = 10f)).of(AppFeature.FOCUS)
        assertEquals(AppFeatureStatus.PARTIAL, manual.status) // no AF lock without the engine's afLockSupported flag
        assertEquals("≥ 10 cm", manual.detail)
        assertEquals(AppFeatureStatus.PARTIAL, auditCameraCapabilities(camera(afModes = listOf(3))).of(AppFeature.FOCUS).status)
    }

    @Test fun shutterReadsLikeACamera() {
        assertEquals("1/48", shutter(20_833_333L))
        assertEquals("1 s", shutter(1_000_000_000L))
        assertEquals("2.5 s", shutter(2_500_000_000L))
    }

    @Test fun focusDistanceSwitchesToMetres() {
        assertEquals("10 cm", focusDistance(10f))
        assertEquals("10 m", focusDistance(0.1f))
        assertEquals("1.5 m", focusDistance(0.6667f))
    }

    @Test fun rawFilterMatchesKeysAndValuesWordByWord() {
        val entries = listOf(
            CharacteristicEntry("android.control.aeAvailableModes", "OFF (0), ON (1)"),
            CharacteristicEntry("android.sensor.info.sensitivityRange", "50 – 6400"),
        )
        assertEquals(entries, filterEntries(entries, "  "))
        assertEquals(listOf(entries[1]), filterEntries(entries, "SENSOR 6400"))
        assertEquals(listOf(entries[0]), filterEntries(entries, "control on"))
        assertEquals("OFF, ON, 3x", withoutApiCodes("OFF (0), ON (1), 3x"))
    }

    @Test fun settingsSearchFindsThePageInBothLanguages() {
        for (query in listOf("capacidades", "capabilities", "camera2 parámetros")) {
            assertTrue(query, "camera-capabilities" in SettingsCatalog.search(query, null) { "" })
        }
    }
}
