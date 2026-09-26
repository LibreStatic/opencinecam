/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equivalence evidence: each [EngineRequestMode] resolves to exactly the (key, value) set the
 * engine hand-set before it switched to the composer (Camera2PreviewEngine at a32e0fe).
 */
class EngineRequestCompositionTest {
    private val composer = CaptureRequestComposer()

    /** The key map the engine's applyEngineRequest adapter writes, in order. */
    private fun keyMap(mode: EngineRequestMode, parameters: EngineRequestParameters = EngineRequestParameters()): List<Pair<String, Any>> =
        composer.composeEngineRequest(mode, parameters).map { (key, value) ->
            key to when (value) {
                is RequestValue.TextValue -> camera2RequestEnum(key, value.value)
                is RequestValue.IntValue -> if (key == "JPEG_QUALITY") value.value.toByte() else value.value
                is RequestValue.LongValue -> value.value
                else -> error("unexpected $value")
            }
        }

    private val auto3a = listOf(
        "CONTROL_MODE" to CaptureRequest.CONTROL_MODE_AUTO,
    )

    @Test
    fun previewMatchesStartRepeating() {
        assertEquals(
            auto3a + listOf(
                "CONTROL_AF_MODE" to CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                "CONTROL_AE_MODE" to CaptureRequest.CONTROL_AE_MODE_ON,
                "CONTROL_AWB_MODE" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
            ),
            keyMap(EngineRequestMode.PREVIEW),
        )
    }

    @Test
    fun photoPrecaptureSetsOnlyModeAndContinuousPictureAf() {
        assertEquals(
            auto3a + listOf("CONTROL_AF_MODE" to CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
            keyMap(EngineRequestMode.PHOTO_PRECAPTURE),
        )
    }

    @Test
    fun videoAndRecordingMatchGpuPreviewGpuRecordingAndDirectRecording() {
        assertEquals(
            auto3a + listOf(
                "CONTROL_AF_MODE" to CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                "CONTROL_AE_MODE" to CaptureRequest.CONTROL_AE_MODE_ON,
                "CONTROL_AWB_MODE" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
            ),
            keyMap(EngineRequestMode.VIDEO_RECORD),
        )
    }

    @Test
    fun logMatchesConfigureLogSession() {
        assertEquals(
            auto3a + listOf(
                "CONTROL_CAPTURE_INTENT" to CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD,
                "CONTROL_AF_MODE" to CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                "CONTROL_AE_MODE" to CaptureRequest.CONTROL_AE_MODE_ON,
                "CONTROL_AWB_MODE" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
            ),
            keyMap(EngineRequestMode.LOG_RECORD),
        )
    }

    @Test
    fun highSpeedKeepsTemplateDefaults() {
        // Qualcomm HALs reject redundant AF/AE/AWB overrides in HFR; only the record intent is set.
        assertEquals(emptyList<Pair<String, Any>>(), keyMap(EngineRequestMode.HIGH_SPEED_PREVIEW))
        assertEquals(
            listOf("CONTROL_CAPTURE_INTENT" to CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD),
            keyMap(EngineRequestMode.HIGH_SPEED_RECORD),
        )
    }

    @Test
    fun stillCaptureMatchesSubmitPhotoStill() {
        assertEquals(
            listOf(
                "JPEG_QUALITY" to 95.toByte(),
                "CONTROL_MODE" to CaptureRequest.CONTROL_MODE_AUTO,
                "CONTROL_AF_MODE" to CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                "CONTROL_AE_MODE" to CaptureRequest.CONTROL_AE_MODE_ON,
                "JPEG_ORIENTATION" to 90,
            ),
            keyMap(EngineRequestMode.STILL_CAPTURE, EngineRequestParameters(jpegQuality = 95, jpegOrientationDegrees = 90)),
        )
    }

    @Test
    fun longExposureStillBracketAndCancelMatchTheirSites() {
        assertEquals(
            listOf(
                "CONTROL_AE_MODE" to CaptureRequest.CONTROL_AE_MODE_OFF,
                "CONTROL_AF_MODE" to CaptureRequest.CONTROL_AF_MODE_OFF,
                "SENSOR_SENSITIVITY" to 100,
                "SENSOR_EXPOSURE_TIME" to 1_000_000_000L,
                "JPEG_ORIENTATION" to 270,
            ),
            keyMap(EngineRequestMode.LONG_EXPOSURE_STILL,
                EngineRequestParameters(jpegOrientationDegrees = 270, sensitivityIso = 100, exposureTimeNs = 1_000_000_000L)),
        )
        assertEquals(listOf("CONTROL_CAPTURE_INTENT" to CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW), keyMap(EngineRequestMode.BRACKET_METERING))
        assertEquals(
            listOf("CONTROL_AE_PRECAPTURE_TRIGGER" to CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL),
            keyMap(EngineRequestMode.PRECAPTURE_CANCEL),
        )
    }

    @Test
    fun noModeAddsIspOrStabilizationKeysTheEngineNeverSetInItsBaseBlock() {
        val forbidden = setOf("EDGE_MODE", "NOISE_REDUCTION_MODE", "CONTROL_VIDEO_STABILIZATION_MODE", "LENS_OPTICAL_STABILIZATION_MODE")
        val parameters = EngineRequestParameters(jpegQuality = 90, jpegOrientationDegrees = 0, sensitivityIso = 50, exposureTimeNs = 1L)
        for (mode in EngineRequestMode.entries) {
            val keys = keyMap(mode, parameters).map { it.first }
            assertTrue("$mode: $keys", keys.none(forbidden::contains))
            assertEquals("$mode sets a key twice", keys.size, keys.toSet().size)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun stillCaptureWithoutQualityFailsLoudly() {
        composer.composeEngineRequest(EngineRequestMode.STILL_CAPTURE, EngineRequestParameters(jpegOrientationDegrees = 0))
    }
}
