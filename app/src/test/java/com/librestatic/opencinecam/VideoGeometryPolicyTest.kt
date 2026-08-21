/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGeometryPolicyTest {
    private val sizes = mapOf(
        "uhd" to Pair(3840, 2160),
        "fhd" to Pair(1920, 1080),
        "hd" to Pair(1280, 720),
    )
    private fun video(key: String, fps: Int) = VideoProfileSpec(sizes.getValue(key).first, sizes.getValue(key).second, fps)
    private fun log(key: String, fps: Int, source: OpenCineLogSourcePath) = LogProfileSpec(sizes.getValue(key).first, sizes.getValue(key).second, fps, source)

    @Test
    fun supportedFpsFiltersAndSortsRatesForSelectedSize() {
        val profiles = listOf(video("uhd", 30), video("uhd", 60), video("fhd", 120))
        assertEquals(listOf(30, 60), VideoGeometryPolicy.supportedFps(profiles, 3840, 2160))
        assertEquals(listOf(120), VideoGeometryPolicy.supportedFps(profiles, 1920, 1080))
    }

    @Test
    fun unionFpsDeduplicatesAcrossSizes() {
        val profiles = listOf(video("uhd", 30), video("fhd", 30), video("fhd", 120), video("hd", 60))
        assertEquals(listOf(30, 60, 120), VideoGeometryPolicy.unionFps(profiles))
    }

    @Test
    fun snapVideoKeepsExactMatch() {
        val profiles = listOf(video("uhd", 24), video("uhd", 30), video("uhd", 60))
        val snap = VideoGeometryPolicy.snapVideo(profiles, 3840, 2160, 60, 30)
        assertEquals(60, snap?.fps)
    }

    @Test
    fun snapVideoFallsBackToClosestRate() {
        val profiles = listOf(video("uhd", 24), video("fhd", 60))
        val snap = VideoGeometryPolicy.snapVideo(profiles, 3840, 2160, 60, 30)
        assertEquals(24, snap?.fps)
    }

    @Test
    fun snapVideoReturnsNullForUnknownSize() {
        val profiles = listOf(video("uhd", 30))
        assertNull(VideoGeometryPolicy.snapVideo(profiles, 1280, 720, 30, 30))
    }

    @Test
    fun snapLogKeepsExactRequestedRateWithoutNotice() {
        val profiles = listOf(
            log("fhd", 30, OpenCineLogSourcePath.HLG10_BT2020),
            log("fhd", 60, OpenCineLogSourcePath.SDR_BT709_ISP),
            log("uhd", 30, OpenCineLogSourcePath.HLG10_BT2020),
        )
        val snap = VideoGeometryPolicy.snapLog(profiles, 1920, 1080, 60, 30)
        assertEquals(60, snap.profile?.fps)
        assertFalse(snap.snapped)
    }

    @Test
    fun snapLogSnapsToClosestWithHlgPreferredOnTie() {
        val profiles = listOf(
            log("fhd", 24, OpenCineLogSourcePath.SDR_BT709_ISP),
            log("fhd", 36, OpenCineLogSourcePath.HLG10_BT2020),
        )
        val snap = VideoGeometryPolicy.snapLog(profiles, 1920, 1080, 30, 30)
        assertEquals(36, snap.profile?.fps)
        assertTrue(snap.snapped)
    }

    @Test
    fun snapLogUsesDefaultHlgWhenRequestedMissing() {
        val profiles = listOf(
            log("fhd", 24, OpenCineLogSourcePath.SDR_BT709_ISP),
            log("fhd", 30, OpenCineLogSourcePath.HLG10_BT2020),
            log("fhd", 120, OpenCineLogSourcePath.SDR_BT709_ISP),
        )
        val snap = VideoGeometryPolicy.snapLog(profiles, 1920, 1080, 60, 30)
        assertEquals(30, snap.profile?.fps)
        assertTrue(snap.snapped)
    }

    @Test
    fun supportedLogFpsListsOnlyRatesForSize() {
        val profiles = listOf(
            log("fhd", 30, OpenCineLogSourcePath.HLG10_BT2020),
            log("fhd", 60, OpenCineLogSourcePath.SDR_BT709_ISP),
            log("uhd", 30, OpenCineLogSourcePath.HLG10_BT2020),
        )
        assertEquals(listOf(30, 60), VideoGeometryPolicy.supportedLogFps(profiles, 1920, 1080))
    }
}
