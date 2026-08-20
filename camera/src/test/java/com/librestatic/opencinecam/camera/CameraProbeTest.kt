/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import com.librestatic.opencinecam.core.model.Knowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraProbeTest {
    @Test
    fun mapsIdentityAndSortsPublicPhysicalIds() {
        val source = object : CameraMetadataSource {
            override fun cameraIds() = listOf("1", "0", "1")
            override fun metadata(cameraId: String) = CameraMetadata(
                cameraId,
                CameraCharacteristics.LENS_FACING_BACK,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                Rect(0, 0, 4000, 3000),
                listOf(4.2f),
                setOf("physical-b", "physical-a"),
                setOf(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            )
        }
        val report = CameraCharacteristicsProbe(source).probe()
        assertEquals(listOf("0", "1"), report.cameras.map { it.cameraId })
        assertEquals(listOf("physical-a", "physical-b"), report.cameras.first().publicPhysicalIds.toList())
        assertEquals(Knowledge.Known("back"), report.cameras.first().lensFacing)
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun missingCharacteristicsStayUnknownAndEnumerationFailureIsStructured() {
        val missing = CameraCharacteristicsProbe(object : CameraMetadataSource {
            override fun cameraIds() = listOf("0")
            override fun metadata(cameraId: String) = CameraMetadata(cameraId, null, null, null, null, null, null)
        }).probe()
        assertEquals(Knowledge.Unknown, missing.cameras.single().lensFacing)
        val denied = CameraCharacteristicsProbe(object : CameraMetadataSource {
            override fun cameraIds(): List<String> = error("not used")
            override fun metadata(cameraId: String): CameraMetadata = error("not used")
        }).probe()
        assertEquals(1, denied.failures.size)
        assertEquals("camera-probe", denied.failures.single().component)
    }
}
