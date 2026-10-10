/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.Antibanding
import com.librestatic.opencinecam.camera.ExposureMetering
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ImageProcessingDefaults
import com.librestatic.opencinecam.camera.PictureControlRange
import com.librestatic.opencinecam.camera.PictureControlReadback
import com.librestatic.opencinecam.camera.ResolvedExposure
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraReadbackTest {
    private val manual = ResolvedExposure(ExposureMode.MANUAL, iso = 400, timeNs = 10_000_000L, antibanding = Antibanding.HZ50)

    @Test fun automaticExposureNeverReportsADifference() {
        val auto = ResolvedExposure(ExposureMode.AUTO)
        assertTrue(exposureReadbackDifferences(auto, ExposureMode.AUTO, 200, 10_000_000L, null).isEmpty())
    }

    @Test fun matchingManualExposureStaysQuiet() {
        // Within 1 %: sensors round exposure to whole lines. Code 1 is CONTROL_AE_ANTIBANDING_MODE_50HZ.
        assertTrue(exposureReadbackDifferences(manual, ExposureMode.MANUAL, 400, 10_050_000L, 1).isEmpty())
        assertTrue(exposureReadbackDifferences(manual, null, null, null, null).isEmpty())
    }

    @Test fun eachDisagreementIsListedOnce() {
        assertEquals(
            listOf(ReadbackDifference.Mode(ExposureMode.AUTO), ReadbackDifference.Iso(800), ReadbackDifference.ExposureTime(20_000_000L),
                ReadbackDifference.Banding(Antibanding.HZ60)),
            exposureReadbackDifferences(manual, ExposureMode.AUTO, 800, 20_000_000L, 2),
        )
        assertEquals(listOf(ReadbackDifference.Banding(null)), exposureReadbackDifferences(manual, ExposureMode.MANUAL, 400, 10_000_000L, 9))
    }

    @Test fun meteringIsComparedOnlyWhenRequestedAndReported() {
        val spot = ResolvedExposure(ExposureMode.AUTO, metering = ExposureMetering.SPOT)
        assertTrue(exposureReadbackDifferences(spot, ExposureMode.AUTO, null, null, null, 2).isEmpty())
        assertTrue(exposureReadbackDifferences(spot, ExposureMode.AUTO, null, null, null, null).isEmpty())
        assertTrue(exposureReadbackDifferences(ResolvedExposure(ExposureMode.AUTO), ExposureMode.AUTO, null, null, null, 1).isEmpty())
        assertEquals(listOf(ReadbackDifference.Metering(ExposureMetering.CENTER_WEIGHTED)), exposureReadbackDifferences(spot, ExposureMode.AUTO, null, null, null, 1))
        assertEquals(listOf(ReadbackDifference.Metering(null)), exposureReadbackDifferences(spot, ExposureMode.AUTO, null, null, null, 7))
    }

    @Test fun onlyAFixedTemperatureIsCompared() {
        assertTrue(whiteBalanceReadbackDifferences(WhiteBalanceSelection.Auto, 4000).isEmpty())
        assertTrue(whiteBalanceReadbackDifferences(WhiteBalanceSelection.Kelvin(5600), 5650).isEmpty())
        assertEquals(listOf(ReadbackDifference.Temperature(6000)), whiteBalanceReadbackDifferences(WhiteBalanceSelection.Kelvin(5600), 6000))
    }

    @Test fun imageProcessingComparesTheSubmittedRequest() {
        val reported = ImageProcessingDefaults(optical = 0, video = 0, noise = 2, edge = null)
        assertTrue(imageProcessingReadbackDifferences(null, reported).isEmpty())
        assertEquals(listOf(ReadbackDifference.Optical(0)),
            imageProcessingReadbackDifferences(ImageProcessingDefaults(optical = 1, video = 0, noise = 2, edge = 1), reported))
    }

    @Test fun capabilitiesTableKeepsEveryValueUnderItsCameraKey() {
        val lines = requestedReportedLines(CameraUiState(sensitivityIso = 200, exposureTimeNs = 10_000_000L,
            submittedImageProcessing = ImageProcessingDefaults(optical = 1), reportedOpticalStabilization = 0)).toMap()
        assertEquals("AUTO → 200", lines["android.sensor.sensitivity"])
        assertEquals("AUTO → 10000000 ns (1/100)", lines["android.sensor.exposureTime"])
        assertEquals("ON (1) → OFF (0)", lines["android.lens.opticalStabilizationMode"])
        assertEquals("— → —", lines["android.scaler.cropRegion"])
        assertEquals("AUTO → —", lines["android.control.aeMode"])
        assertFalse("org.codeaurora.qcamera3.exposure_metering.exposure_metering_mode" in lines)
        val metered = requestedReportedLines(CameraUiState(reportedMeteringMode = 2)).toMap()
        assertEquals("— → SPOT (2)", metered["org.codeaurora.qcamera3.exposure_metering.exposure_metering_mode"])
    }

    @Test fun lowLightBoostStateIsReportedOnlyWhenTheCameraSaidSo() {
        fun line(state: CameraUiState) = requestedReportedLines(state).toMap()["android.control.lowLightBoostState"]
        assertEquals("— → —", line(CameraUiState()))
        assertEquals("— → ACTIVE", line(CameraUiState(reportedLowLightBoost = true)))
        assertEquals("— → INACTIVE", line(CameraUiState(reportedLowLightBoost = false)))
    }
}

class LowLightBoostSettingsTest {
    @Test fun lowLightBoostDefaultsOffPersistsAndStaysOutOfPresets() {
        val memory = PresetPreferences()
        val store = CameraSettingsStore(memory)
        assertFalse(store.load().exposure.lowLightBoost)
        val on = CameraSettings(exposure = com.librestatic.opencinecam.camera.ExposureSelection(lowLightBoost = true))
        store.save(on)
        assertEquals(true, memory.all["low-light-boost"])
        assertEquals(on, store.load())
        assertFalse("low-light-boost" in CameraPresetCodec.portableKeys)
        val merged = CameraPresetCodec.mergeLocal(CameraSettings(), on)
        assertTrue(merged.exposure.lowLightBoost)
        assertFalse(CameraPresetCodec.mergeLocal(on, CameraSettings()).exposure.lowLightBoost)
    }

    @Test fun pictureControlsOnlyDifferWhereTheRequestSetAValue() {
        assertTrue(pictureControlReadbackDifferences(null).isEmpty())
        assertTrue(pictureControlReadbackDifferences(PictureControlReadback(null, 5, null, 2)).isEmpty())
        assertTrue(pictureControlReadbackDifferences(PictureControlReadback(7, 7, 4, 4)).isEmpty())
        assertEquals(listOf(ReadbackDifference.Saturation(5), ReadbackDifference.Sharpness(2)),
            pictureControlReadbackDifferences(PictureControlReadback(7, 5, 4, 2)))
    }

    @Test fun pictureControlLinesAppearOnlyWhenTheCameraReportsThem() {
        assertNull(requestedReportedLines(CameraUiState()).toMap()["org.codeaurora.qcamera3.saturation.use_saturation"])
        val lines = requestedReportedLines(CameraUiState(pictureControlReadback = PictureControlReadback(7, 7, null, 2))).toMap()
        assertEquals("7 → 7", lines["org.codeaurora.qcamera3.saturation.use_saturation"])
        assertEquals("— → 2", lines["org.codeaurora.qcamera3.sharpness.strength"])
    }

    @Test fun pictureControlsExplainWhyTheyCannotAct() {
        assertEquals(PictureControlBlock.NONE, pictureControlBlock(true, CaptureMode.VIDEO, hfr = false, edgeMode = 2))
        assertEquals(PictureControlBlock.NONE, pictureControlBlock(true, CaptureMode.PHOTO, hfr = false, edgeMode = null))
        assertEquals(PictureControlBlock.EDGE_OFF, pictureControlBlock(true, CaptureMode.VIDEO, hfr = false, edgeMode = 0))
        assertEquals(PictureControlBlock.NONE, pictureControlBlock(false, CaptureMode.VIDEO, hfr = false, edgeMode = 0))
        for (mode in listOf(CaptureMode.LOG, CaptureMode.HLG, CaptureMode.RAW_PHOTO)) {
            assertEquals(PictureControlBlock.NEUTRAL, pictureControlBlock(false, mode, hfr = false, edgeMode = 2))
        }
        assertEquals(PictureControlBlock.NEUTRAL, pictureControlBlock(false, CaptureMode.VIDEO, hfr = true, edgeMode = 2))
    }

    @Test fun theAdvertisedDefaultStoresNoOverride() {
        val saturation = PictureControlRange(0, 10, 1, 5, true)
        assertNull(pictureControlSelection(saturation, 5))
        assertEquals(8, pictureControlSelection(saturation, 8))
        assertEquals(3, pictureControlSelection(PictureControlRange(0, 6, 1, 3, false), 3))
    }
}
