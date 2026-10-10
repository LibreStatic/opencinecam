/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrVideoFormatTest {
    @Test
    fun formatsCarryTheirOwnH273AndMediaFormatTransfer() {
        assertEquals(18, HdrVideoFormat.HLG.h273Transfer)
        assertEquals(16, HdrVideoFormat.HDR10.h273Transfer)
        // MediaFormat.COLOR_TRANSFER_HLG = 7, COLOR_TRANSFER_ST2084 = 6.
        assertEquals(7, HdrVideoFormat.HLG.mediaFormatTransfer)
        assertEquals(6, HdrVideoFormat.HDR10.mediaFormatTransfer)
        assertEquals("HLG", HdrVideoFormat.HLG.curve)
        assertEquals("PQ", HdrVideoFormat.HDR10.curve)
    }

    @Test
    fun evidenceCurvesMapBackToTheirFormat() {
        assertEquals(HdrVideoFormat.HLG, HdrVideoFormat.fromCurve("HLG"))
        assertEquals(HdrVideoFormat.HDR10, HdrVideoFormat.fromCurve("PQ"))
        assertNull(HdrVideoFormat.fromCurve("OCLog2"))
        assertNull(HdrVideoFormat.fromCurve("LUT_BAKED_SDR"))
    }

    @Test
    fun hdr10IsOfferedOnlyWithCameraEncoderAndSdk() {
        assertTrue(Hdr10Video.isOffered(33, cameraAdvertisesHdr10 = true, encoderAdvertisesHdr10 = true))
        assertTrue(Hdr10Video.isOffered(36, true, true))
        assertFalse(Hdr10Video.isOffered(32, true, true))
        assertFalse(Hdr10Video.isOffered(36, cameraAdvertisesHdr10 = false, encoderAdvertisesHdr10 = true))
        assertFalse(Hdr10Video.isOffered(36, cameraAdvertisesHdr10 = true, encoderAdvertisesHdr10 = false))
    }

    @Test
    fun aTakeFallsBackToHlgUnlessHdr10IsRequestedAndOffered() {
        assertEquals(HdrVideoFormat.HDR10, Hdr10Video.effectiveFormat(HdrVideoFormat.HDR10, offered = true))
        assertEquals(HdrVideoFormat.HLG, Hdr10Video.effectiveFormat(HdrVideoFormat.HDR10, offered = false))
        assertEquals(HdrVideoFormat.HLG, Hdr10Video.effectiveFormat(HdrVideoFormat.HLG, offered = true))
        assertEquals(HdrVideoFormat.HLG, Hdr10Video.effectiveFormat(HdrVideoFormat.HLG, offered = false))
    }

    @Test
    fun staticInfoIsTheCta8613Type1DescriptorOfABt2020D65GradingDisplay() {
        val bytes = Hdr10Video.staticInfo()
        assertEquals(25, bytes.size)
        assertEquals(0, bytes[0].toInt())
        fun word(index: Int) = (bytes[1 + index * 2].toInt() and 0xFF) or ((bytes[2 + index * 2].toInt() and 0xFF) shl 8)
        // Primaries R, G, B then white point, in 0.00002 units.
        assertEquals(listOf(0.708, 0.292), listOf(word(0), word(1)).map { it * 0.00002 }.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(listOf(0.170, 0.797), listOf(word(2), word(3)).map { it * 0.00002 }.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(listOf(0.131, 0.046), listOf(word(4), word(5)).map { it * 0.00002 }.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(listOf(0.3127, 0.3290), listOf(word(6), word(7)).map { it * 0.00002 }.map { Math.round(it * 10000) / 10000.0 })
        assertEquals(1000, word(8))
        assertEquals(0.005, word(9) * 0.0001, 1e-9)
        assertEquals(1000, word(10))
        assertEquals(400, word(11))
        assertTrue("MaxFALL cannot exceed MaxCLL", word(11) <= word(10))
    }

    @Test
    fun pqCurveMatchesSt2084ReferencePoints() {
        // ST 2084 black is c1^m2 (about 7.3e-7), not exactly zero.
        assertEquals(Math.pow(0.8359375, 78.84375), HlgToPq.pqInverseEotf(0.0), 1e-12)
        assertEquals(1.0, HlgToPq.pqInverseEotf(10_000.0), 1e-9)
        // Well-known code values: 100 cd/m2 = 0.5081, 1000 cd/m2 = 0.7518, 203 cd/m2 = 0.5807.
        assertEquals(0.5081, HlgToPq.pqInverseEotf(100.0), 5e-4)
        assertEquals(0.7518, HlgToPq.pqInverseEotf(1000.0), 5e-4)
        assertEquals(0.5807, HlgToPq.pqInverseEotf(203.0), 5e-4)
        listOf(0.5, 5.0, 203.0, 1000.0, 4000.0).forEach {
            assertEquals(it, HlgToPq.pqEotf(HlgToPq.pqInverseEotf(it)), it * 1e-9)
        }
    }

    @Test
    fun hlgReferenceWhiteLandsAt203NitsAndPeakAt1000() {
        // BT.2408: HLG 75% signal is the 203 cd/m2 graphics white of a 1000 cd/m2 display.
        assertEquals(203.0, HlgToPq.pqEotf(HlgToPq.greyHlgToPq(0.75)), 1.0)
        assertEquals(1000.0, HlgToPq.pqEotf(HlgToPq.greyHlgToPq(1.0)), 1.0)
        assertEquals(0.0, HlgToPq.greyHlgToPq(0.0), 1e-6)
    }

    @Test
    fun greyRampIsMonotonicAndStaysNeutral() {
        var previous = -1.0
        for (step in 0..100) {
            val signal = step / 100.0
            val pq = HlgToPq.greyHlgToPq(signal)
            assertTrue("PQ must not decrease at $signal", pq >= previous)
            previous = pq
            val scene = HlgToPq.hlgInverseOetf(signal)
            val rgb = HlgToPq.sceneLinearToPq(doubleArrayOf(scene, scene, scene))
            assertEquals(rgb[0], rgb[1], 1e-12)
            assertEquals(rgb[1], rgb[2], 1e-12)
        }
    }

    @Test
    fun saturatedColourKeepsItsChannelOrderAndNeverExceedsTheCodeRange() {
        val red = HlgToPq.sceneLinearToPq(doubleArrayOf(1.0, 0.0, 0.0))
        assertTrue(red[0] > red[1])
        assertEquals(HlgToPq.pqInverseEotf(0.0), red[1], 1e-12)
        listOf(doubleArrayOf(2.0, 2.0, 2.0), doubleArrayOf(-1.0, 0.5, 3.0)).forEach { scene ->
            HlgToPq.sceneLinearToPq(scene).forEach { assertTrue(it in 0.0..1.0) }
        }
    }

    @Test
    fun pqShaderConvertsAndDiffersFromTheHlgShader() {
        listOf(true, false).forEach { ycbcr ->
            val pq = OpenCineLogGpuPipeline.pqSignalShader(ycbcr)
            val hlg = OpenCineLogGpuPipeline.hlgSignalShader(ycbcr)
            assertNotEquals(hlg, pq)
            assertTrue(pq.contains("pqInverseEotf(hlgOotf(sceneLinear))"))
            assertFalse(pq.contains("outColor = vec4(hlgOetf(sceneLinear)"))
            assertTrue(pq.contains("1000.0 * pow(max(ys, 1e-6), 0.2)"))
            assertTrue(pq.contains("0.1593017578125") && pq.contains("78.84375") && pq.contains("18.8515625"))
        }
        assertTrue(OpenCineLogGpuPipeline.pqSignalShader(true).contains("uYcbcrToRgb"))
        assertNotEquals(OpenCineLogGpuPipeline.hlgSignalSha256(), OpenCineLogGpuPipeline.pqSignalSha256())
    }
}
