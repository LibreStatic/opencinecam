/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.Knowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamProbeTest {
    @Test
    fun reportsSortedStreamsAndManualCapabilities() {
        val report = StreamCapabilityProbe {
            StreamCapabilityMetadata(
                cameraId = "0",
                previewSizes = listOf(StreamSize(1920, 1080), StreamSize(1280, 720), StreamSize(1920, 1080)),
                rawSizes = listOf(StreamSize(4000, 3000)),
                highSpeedSizes = emptyList(),
                highSpeedFpsRanges = emptyMap(),
                targetFpsRanges = listOf(30..30, 24..60),
                manualSensor = true,
                manualPostProcessing = false,
                dynamicRangeProfiles = setOf("SDR"),
                colorSpaceProfiles = setOf("0"),
            )
        }.probe("0")
        assertEquals(Knowledge.Known(listOf(StreamSize(1280, 720), StreamSize(1920, 1080))), report.previewSizes)
        assertEquals(Knowledge.Known(true), report.manualSensor)
        assertTrue(report.dynamicRangeProfiles is Knowledge.Known)
    }

    @Test
    fun apiUnavailableDynamicRangeRemainsUnknown() {
        val report = StreamCapabilityProbe {
            StreamCapabilityMetadata("0", null, null, null, null, null, null, null, null, null)
        }.probe("0")
        assertEquals(Knowledge.Unknown, report.dynamicRangeProfiles)
        assertEquals(Knowledge.Unknown, report.rawSizes)
    }
}
