/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.Knowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEncoderSelectorTest {
    @Test
    fun selectsHardwareEncoderDeterministically() {
        val result = VideoEncoderSelector().select(request(), listOf(software(), hardware("z-hardware"), hardware("a-hardware")))

        val selected = result as VideoEncoderSelection.Selected
        assertEquals("a-hardware", selected.encoder.capability.name)
        assertEquals(1, selected.encoder.request.fps)
    }

    @Test
    fun rejectsUnknownAndUnsupportedRequirementsWithoutFallback() {
        val unknown = hardware("unknown").copy(profileLevels = Knowledge.Unknown)
        val unknownResult = VideoEncoderSelector().select(request(profileLevel = "main10"), listOf(unknown))
        assertEquals(FailureCode.ENCODER_CONFIGURATION_FAILED, (unknownResult as VideoEncoderSelection.Rejected).failure.code)

        val misaligned = hardware("misaligned").copy(widthAlignment = Knowledge.Known(16))
        val unsupportedResult = VideoEncoderSelector().select(request(width = 1921), listOf(misaligned))
        assertEquals(FailureCode.ENCODER_CONFIGURATION_FAILED, (unsupportedResult as VideoEncoderSelection.Rejected).failure.code)
    }

    @Test
    fun profileRatePerformanceAndSurfaceRequirementsAreEnforced() {
        val profileMissing = hardware("profile").copy(profileLevels = Knowledge.Known(setOf("baseline")))
        val result = VideoEncoderSelector().select(request(profileLevel = "main10", minPerformanceScore = 50), listOf(profileMissing))

        assertTrue(result is VideoEncoderSelection.Rejected)
        assertEquals(FailureCode.ENCODER_CONFIGURATION_FAILED, (result as VideoEncoderSelection.Rejected).failure.code)
    }

    @Test
    fun encoderSelectionDoesNotPromoteUnknownHardwareEvidence() {
        val unknownHardware = hardware("unknown-hardware").copy(hardwareAccelerated = Knowledge.Unknown)
        val software = software()

        val result = VideoEncoderSelector().select(request(), listOf(unknownHardware, software))

        assertEquals("software", (result as VideoEncoderSelection.Selected).encoder.capability.name)
    }

    private fun request(
        width: Int = 1920,
        profileLevel: String? = null,
        minPerformanceScore: Int = 0,
    ) = VideoEncoderRequest(
        requestId = "request-1",
        mime = "video/avc",
        width = width,
        height = 1080,
        fps = 1,
        bitrate = 2_000_000,
        profileLevel = profileLevel,
        minPerformanceScore = minPerformanceScore,
    )

    private fun hardware(name: String) = VideoEncoderCapability(
        name = name,
        mime = "video/avc",
        hardwareAccelerated = Knowledge.Known(true),
        profileLevels = Knowledge.Known(setOf("main10")),
        colorFormats = Knowledge.Known(setOf(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)),
        widthAlignment = Knowledge.Known(2),
        heightAlignment = Knowledge.Known(2),
        bitrateRange = Knowledge.Known(500_000L..10_000_000L),
        fpsRange = Knowledge.Known(1..60),
        performanceScore = Knowledge.Known(100),
    )

    private fun software() = hardware("software").copy(hardwareAccelerated = Knowledge.Known(false))
}
