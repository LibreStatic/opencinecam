/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCineLogViewAssistTest {
    @Test
    fun viewGainPutsEachTierGreyAt018() {
        val hlg = OpenCineLogViewAssist.viewGain(OpenCineLogSourcePath.HLG10_BT2020, 1.0)
        assertEquals(0.18, OpenCineLogGreyReference.HLG_REFERENCE_GREY_LINEAR * hlg, 1e-12)
        assertEquals(OpenCineLogGreyReference.TIER_GREY_RATIO, hlg, 1e-12)
        assertEquals(1.0, OpenCineLogViewAssist.viewGain(OpenCineLogSourcePath.SDR_BT709_ISP, 1.0), 1e-12)
        // A take already scaled to the other tier's grey needs no further gain.
        val matched = OpenCineLogGreyReference.MATCH_SDR.sceneGain(OpenCineLogSourcePath.HLG10_BT2020).toDouble()
        assertEquals(1.0, OpenCineLogViewAssist.viewGain(OpenCineLogSourcePath.HLG10_BT2020, matched), 1e-6)
    }

    @Test
    fun greyBelowTheKneeIsThePlainRec709Oetf() {
        val grey = OpenCineLogViewAssist.encode(doubleArrayOf(0.18, 0.18, 0.18), 1.0)
        for (channel in grey) assertEquals(1.099 * Math.pow(0.18, 0.45) - 0.099, channel, 1e-12)
    }

    @Test
    fun highlightsRollOffWithoutClippingOrHueShift() {
        // Display white keeps headroom; HLG peak white (about 3.7x it in the view) only reaches 1.
        val white = OpenCineLogViewAssist.encode(doubleArrayOf(1.0, 1.0, 1.0), 1.0)
        for (channel in white) assertEquals(1.099 * Math.pow(0.5 + 0.5 * kotlin.math.tanh(1.0), 0.45) - 0.099, channel, 1e-12)
        val peak = OpenCineLogViewAssist.encode(doubleArrayOf(1.0, 1.0, 1.0), OpenCineLogGreyReference.TIER_GREY_RATIO)
        for (channel in peak) assertTrue(channel in white[0]..1.0)
        // A bright green whose green channel alone would clip keeps G > R > B instead of turning yellow.
        val green = OpenCineLogViewAssist.encode(doubleArrayOf(0.5, 1.6, 0.2), 1.0)
        assertTrue(green.all { it in 0.0..1.0 })
        assertTrue(green[1] > green[0] && green[0] > green[2])
        assertTrue("the green still reads as green", green[1] - green[0] > 0.05)
    }

    @Test
    fun outOfGamutColoursDesaturateTowardsTheirLuminance() {
        val luma = 0.2126 * 0.9 + 0.7152 * 0.1 + 0.0722 * -0.05
        val encoded = OpenCineLogViewAssist.encode(doubleArrayOf(0.9, 0.1, -0.05), 1.0)
        assertEquals(0.0, encoded[2], 1e-9)
        assertTrue(encoded[0] > encoded[1])
        val linear = encoded.map { if (it < 0.081) it / 4.5 else Math.pow((it + 0.099) / 1.099, 1 / 0.45) }
        assertEquals(luma, 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2], 1e-9)
    }

    @Test
    fun captureShadersCarryTheSameViewAssist() {
        val body = { shader: String -> shader.substringAfter("vec3 viewAssist709(").substringBefore("void main()").lines().map(String::trim) }
        val hlg = body(OpenCineLogGpuPipeline.transformShader(OpenCineLogSourcePath.HLG10_BT2020, shaderYcbcr = true))
        val sdr = body(OpenCineLogGpuPipeline.transformShader(OpenCineLogSourcePath.SDR_BT709_ISP, shaderYcbcr = true))
        assertEquals(hlg, sdr)
        assertTrue(hlg.any { it.contains("0.5 + 0.5 * tanh((y - 0.5) / 0.5)") })
        assertEquals(0.5, OpenCineLogViewAssist.KNEE, 0.0)
    }
}
