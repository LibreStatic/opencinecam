/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class RawDevelopTest {
    /** Packs codes as Android RAW10: four MSB bytes, then the four 2-bit remainders (pixel 0 in bits 0-1). */
    private fun packRaw10(width: Int, height: Int, codes: IntArray, leading: Int = 0): ByteBuffer {
        val rowBytes = width / 4 * 5
        val buffer = ByteBuffer.allocateDirect(leading + rowBytes * height)
        for (y in 0 until height) for (group in 0 until width / 4) {
            var low = 0
            for (lane in 0 until 4) {
                val code = codes[y * width + group * 4 + lane]
                buffer.put(leading + y * rowBytes + group * 5 + lane, (code ushr 2).toByte())
                low = low or ((code and 3) shl (lane * 2))
            }
            buffer.put(leading + y * rowBytes + group * 5 + 4, low.toByte())
        }
        buffer.position(leading)
        return buffer
    }

    private fun header(width: Int, height: Int) = OcrawFrameHeader.raw10(width, height, 0, 0, 0L, 0L)

    private fun identityParams(cfa: RawCfa = RawCfa.RGGB, black: Float = 0f, white: Float = 1023f) = RawDevelopParams(
        cfa, FloatArray(4) { black }, white, floatArrayOf(1f, 1f, 1f), RawDevelop.IDENTITY.map { it.toFloat() }.toFloatArray(),
    )

    private fun assertMatrix(expected: DoubleArray, actual: DoubleArray, delta: Double) {
        for (i in 0 until 9) assertEquals("element $i", expected[i], actual[i], delta)
    }

    private fun assertMatrix(expected: DoubleArray, actual: FloatArray, delta: Double) =
        assertMatrix(expected, DoubleArray(9) { actual[it].toDouble() }, delta)

    // ------------------------------------------------------------ RAW10 unpack

    @Test fun raw10UnpacksTheAndroidLayout() {
        // MSB bytes 0x40 0x80 0xC0 0xFF, remainders pixel0=1, pixel1=2, pixel2=3, pixel3=0 -> 0b00_11_10_01.
        val buffer = ByteBuffer.allocateDirect(5).put(byteArrayOf(0x40, 0x80.toByte(), 0xC0.toByte(), 0xFF.toByte(), 0b00111001))
        buffer.position(0)
        val codes = IntArray(4) { RawDevelop.raw10At(buffer, 5, it, 0) }
        assertArrayEquals(intArrayOf(0x40 * 4 + 1, 0x80 * 4 + 2, 0xC0 * 4 + 3, 0xFF * 4), codes)
    }

    @Test fun packAndUnpackRoundTripFromTheBufferPosition() {
        val codes = IntArray(8 * 2) { (it * 67 + 5) % 1024 }
        val buffer = packRaw10(8, 2, codes, leading = 3)
        assertArrayEquals(codes, RawDevelop.unpackRaw10(buffer, header(8, 2)))
        assertEquals(3, buffer.position())
    }

    // ------------------------------------------------------------ CFA

    @Test fun cfaParsesNamesAndAndroidCodesWithTheConverterDefault() {
        assertEquals(RawCfa.RGGB, RawCfa.parse(null))
        assertEquals(RawCfa.GRBG, RawCfa.parse(" grbg "))
        assertEquals(RawCfa.GBRG, RawCfa.parse("2"))
        assertEquals(RawCfa.BGGR, RawCfa.parse("BGGR"))
        assertEquals(RawCfa.MONO, RawCfa.parse("MONO"))
        assertEquals(RawCfa.MONO, RawCfa.parse("6"))
        assertEquals(RawCfa.RGGB, RawCfa.parse("RGB"))
    }

    @Test fun cfaSitesFollowTheTiffPatternOrder() {
        assertArrayEquals(intArrayOf(0, 1, 1, 2), RawCfa.RGGB.siteChannels)
        assertArrayEquals(intArrayOf(1, 0, 2, 1), RawCfa.GRBG.siteChannels)
        assertArrayEquals(intArrayOf(1, 2, 0, 1), RawCfa.GBRG.siteChannels)
        assertArrayEquals(intArrayOf(2, 1, 1, 0), RawCfa.BGGR.siteChannels)
        assertEquals(0, RawCfa.RGGB.site(2, 4))
        assertEquals(1, RawCfa.RGGB.site(3, 0))
        assertEquals(2, RawCfa.RGGB.site(0, 5))
        assertEquals(3, RawCfa.RGGB.site(5, 7))
    }

    // ------------------------------------------------------------ static defaults and per-frame overrides

    @Test fun staticColourDefaultsMatchTheDngConverter() {
        val colour = RawStaticColour.from(emptyMap())
        assertEquals(RawCfa.RGGB, colour.cfa)
        assertEquals(1023.0, colour.whiteLevel, 0.0)
        assertArrayEquals(DoubleArray(4), colour.blackLevel, 0.0)
        assertMatrix(RawDevelop.IDENTITY, colour.colorMatrix1, 0.0)
        assertNull(colour.forwardMatrix1)
        assertEquals(1023.0, RawStaticColour.from(mapOf("whiteLevel" to "0")).whiteLevel, 0.0)
        // Malformed lists count as missing rather than failing the preview.
        assertArrayEquals(DoubleArray(4), RawStaticColour.from(mapOf("blackLevel" to "64 64 64")).blackLevel, 0.0)
    }

    @Test fun staticColourParsesRationalsAndDecimals() {
        val colour = RawStaticColour.from(mapOf(
            "cfa" to "BGGR", "blackLevel" to "64 63 65 64", "whiteLevel" to "1000",
            "forwardMatrix1" to "1/2 0/1 0/1 0/1 3/4 0/1 0/1 0/1 -5/4",
        ))
        assertEquals(RawCfa.BGGR, colour.cfa)
        assertArrayEquals(doubleArrayOf(64.0, 63.0, 65.0, 64.0), colour.blackLevel, 0.0)
        assertEquals(1000.0, colour.whiteLevel, 0.0)
        assertMatrix(doubleArrayOf(0.5, 0.0, 0.0, 0.0, 0.75, 0.0, 0.0, 0.0, -1.25), colour.forwardMatrix1!!, 0.0)
    }

    @Test fun perFrameLevelsOverrideTheStaticOnes() {
        val colour = RawStaticColour.from(mapOf("blackLevel" to "64 64 64 64", "whiteLevel" to "1023"))
        val plain = RawDevelop.params(colour, emptyMap())
        assertArrayEquals(floatArrayOf(64f, 64f, 64f, 64f), plain.blackLevel, 0f)
        assertEquals(1023f, plain.whiteLevel, 0f)
        val dynamic = RawDevelop.params(colour, mapOf("dynamicBlackLevel" to "63.5 64 64.25 65", "whiteLevel" to "1000"))
        assertArrayEquals(floatArrayOf(63.5f, 64f, 64.25f, 65f), dynamic.blackLevel, 0f)
        assertEquals(1000f, dynamic.whiteLevel, 0f)
        val bad = RawDevelop.params(colour, mapOf("dynamicBlackLevel" to "1 2", "whiteLevel" to "x"))
        assertArrayEquals(floatArrayOf(64f, 64f, 64f, 64f), bad.blackLevel, 0f)
        assertEquals(1023f, bad.whiteLevel, 0f)
    }

    // ------------------------------------------------------------ white balance

    @Test fun whiteBalanceIsTheInverseNeutralWithGreenAtOne() {
        val gains = RawDevelop.whiteBalanceGains(doubleArrayOf(0.5, 1.0, 0.25))
        assertArrayEquals(doubleArrayOf(2.0, 1.0, 4.0), gains, 1e-12)
        // The neutral's scale does not matter.
        assertArrayEquals(gains, RawDevelop.whiteBalanceGains(doubleArrayOf(0.25, 0.5, 0.125)), 1e-12)
        assertArrayEquals(doubleArrayOf(1.0, 1.0, 1.0), RawDevelop.whiteBalanceGains(null), 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 1.0, 1.0), RawDevelop.whiteBalanceGains(doubleArrayOf(0.0, 1.0, 1.0)), 0.0)
    }

    @Test fun frameNeutralWinsOverTheFallbackAndUnityIsTheLastResort() {
        val colour = RawStaticColour.from(emptyMap())
        assertArrayEquals(floatArrayOf(2f, 1f, 4f), RawDevelop.params(colour, mapOf("neutral" to "512/1024 1024/1024 256/1024")).wbGains, 1e-6f)
        assertArrayEquals(floatArrayOf(4f, 1f, 2f),
            RawDevelop.params(colour, emptyMap(), fallbackNeutral = doubleArrayOf(0.25, 1.0, 0.5)).wbGains, 1e-6f)
        assertArrayEquals(floatArrayOf(2f, 1f, 4f),
            RawDevelop.params(colour, mapOf("neutral" to "1/2 1/1 1/4"), fallbackNeutral = doubleArrayOf(0.25, 1.0, 0.5)).wbGains, 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), RawDevelop.params(colour, mapOf("neutral" to "1/0 1/1 1/1")).wbGains, 0f)
    }

    // ------------------------------------------------------------ matrices

    @Test fun xyzD50ToRec709MatchesTheBradfordReference() {
        // Lindbloom's Bradford-adapted XYZ (D50) to sRGB matrix.
        val reference = doubleArrayOf(
            3.1338561, -1.6168667, -0.4906146,
            -0.9787684, 1.9161415, 0.0334540,
            0.0719453, -0.2289914, 1.4052427,
        )
        assertMatrix(reference, RawDevelop.XYZ_D50_TO_REC709, 2e-3)
        val white = RawDevelop.apply(RawDevelop.XYZ_D50_TO_REC709, RawDevelop.D50)
        assertArrayEquals(doubleArrayOf(1.0, 1.0, 1.0), white, 1e-4)
    }

    @Test fun inversionUndoesAMatrixAndRejectsSingularOnes() {
        val m = doubleArrayOf(2.0, 1.0, 0.0, 0.5, 3.0, 1.0, 0.0, -1.0, 4.0)
        assertMatrix(RawDevelop.IDENTITY, RawDevelop.multiply(m, RawDevelop.invert(m)), 1e-12)
        assertNull(RawDevelop.invertOrNull(doubleArrayOf(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 0.0, 0.0, 1.0)))
    }

    @Test fun forwardMatrixIsUsedAsIs() {
        val forward = doubleArrayOf(0.6, 0.3, 0.06, 0.25, 0.7, 0.05, 0.0, 0.05, 0.77)
        val colour = RawStaticColour(RawCfa.RGGB, DoubleArray(4), 1023.0, doubleArrayOf(9.0, 0.0, 0.0, 0.0, 9.0, 0.0, 0.0, 0.0, 9.0), forward)
        assertMatrix(forward, RawDevelop.cameraToXyzD50(colour, doubleArrayOf(0.5, 1.0, 0.7)), 0.0)
        val params = RawDevelop.params(colour, emptyMap())
        assertMatrix(RawDevelop.multiply(RawDevelop.XYZ_D50_TO_REC709, forward), params.cameraToRec709, 1e-6)
    }

    @Test fun colorMatrixIsInvertedNormalisedAndAdaptedToD50() {
        // A camera whose XYZ -> camera matrix is exactly XYZ -> linear Rec.709 (D65), shot under D65:
        // the camera neutral is Rec.709 white, so the develop must land on the identity.
        val rec709ToXyz = doubleArrayOf(0.4124564, 0.3575761, 0.1804375, 0.2126729, 0.7151522, 0.0721750, 0.0193339, 0.1191920, 0.9503041)
        val xyzToCamera = RawDevelop.invert(rec709ToXyz).map { it * 3.0 }.toDoubleArray() // DNG scale is arbitrary
        val colour = RawStaticColour(RawCfa.RGGB, DoubleArray(4), 1023.0, xyzToCamera, null)
        val toXyz = RawDevelop.cameraToXyzD50(colour, doubleArrayOf(1.0, 1.0, 1.0))
        assertArrayEquals(RawDevelop.D50, RawDevelop.apply(toXyz, doubleArrayOf(1.0, 1.0, 1.0)), 1e-6)
        val params = RawDevelop.params(colour, mapOf("neutral" to "1/1 1/1 1/1"))
        assertMatrix(RawDevelop.IDENTITY, params.cameraToRec709, 2e-3)
    }

    @Test fun colorMatrixPathMapsTheBalancedNeutralToWhite() {
        val xyzToCamera = doubleArrayOf(0.9, -0.3, -0.05, -0.4, 1.3, 0.1, -0.1, 0.2, 0.6)
        val colour = RawStaticColour.from(mapOf("colorMatrix1" to xyzToCamera.joinToString(" ")))
        val neutral = RawDevelop.apply(xyzToCamera, RawDevelop.D65) // the camera's response to D65
        val toXyz = RawDevelop.cameraToXyzD50(colour, neutral)
        assertArrayEquals(RawDevelop.D50, RawDevelop.apply(toXyz, doubleArrayOf(1.0, 1.0, 1.0)), 1e-6)
    }

    @Test fun missingColourDataReadsTheCameraAsXyzLikeTheConverter() {
        // The converter writes an identity ColorMatrix1: camera RGB is XYZ, and its neutral still develops white.
        val params = RawDevelop.params(RawStaticColour.from(emptyMap()), emptyMap())
        val expected = RawDevelop.multiply(RawDevelop.XYZ_D50_TO_REC709, RawDevelop.bradford(doubleArrayOf(1.0, 1.0, 1.0), RawDevelop.D50))
        assertMatrix(expected, params.cameraToRec709, 1e-6)
        val m = params.cameraToRec709
        for (row in 0 until 3) assertEquals(1f, m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2], 1e-4f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), params.wbGains, 0f)
    }

    @Test fun monoIsGreyWithoutGainOrMatrix() {
        val colour = RawStaticColour.from(mapOf("cfa" to "MONO", "colorMatrix1" to "2 0 0 0 2 0 0 0 2"))
        val params = RawDevelop.params(colour, mapOf("neutral" to "1/2 1/1 1/4"))
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), params.wbGains, 0f)
        assertMatrix(RawDevelop.IDENTITY, params.cameraToRec709, 0.0)
    }

    @Test fun columnMajorTransposes() {
        val params = RawDevelopParams(RawCfa.RGGB, FloatArray(4), 1023f, floatArrayOf(1f, 1f, 1f), FloatArray(9) { it.toFloat() })
        assertArrayEquals(floatArrayOf(0f, 3f, 6f, 1f, 4f, 7f, 2f, 5f, 8f), params.cameraToRec709ColumnMajor(), 0f)
    }

    // ------------------------------------------------------------ tone

    @Test fun toneIsSrgbBelowTheKneeAndRollsOffAbove() {
        assertEquals(0f, RawDevelop.tone(-0.5f), 0f)
        assertEquals(12.92f * 0.001f, RawDevelop.tone(0.001f), 1e-6f)
        assertEquals(1.055f * Math.pow(0.5, 1 / 2.4).toFloat() - 0.055f, RawDevelop.tone(0.5f), 1e-6f)
        assertEquals(RawDevelop.SHOULDER_KNEE, RawDevelop.shoulder(RawDevelop.SHOULDER_KNEE), 0f)
        // Continuous slope 1 at the knee, monotonic, never reaching 1.
        val k = RawDevelop.SHOULDER_KNEE
        assertEquals(1f, (RawDevelop.shoulder(k + 1e-4f) - k) / 1e-4f, 2e-3f)
        assertTrue(RawDevelop.shoulder(1f) < 1f && RawDevelop.shoulder(1f) > 0.9f)
        assertTrue(RawDevelop.shoulder(4f) > RawDevelop.shoulder(2f) && RawDevelop.shoulder(4f) < 1f)
        assertTrue(RawDevelop.tone(1f) < 1f)
    }

    // ------------------------------------------------------------ CPU reference develop

    @Test fun halfDevelopAveragesGreensPerQuad() {
        // One RGGB quad, black 64, white 1023 -> normalised by 959.
        val codes = intArrayOf(64 + 959 / 2, 64 + 100, 64 + 300, 64 + 959 / 4)
        val buffer = packRaw10(4, 2, intArrayOf(codes[0], codes[1], 64, 64, codes[2], codes[3], 64, 64))
        val params = identityParams(black = 64f)
        val out = RawDevelop.developHalf(buffer, header(4, 2), params)
        assertEquals(2 * 1 * 3, out.size)
        val expected = floatArrayOf((959 / 2) / 959f, (100 + 300) / 2f / 959f, (959 / 4) / 959f).map(RawDevelop::tone)
        assertArrayEquals(expected.toFloatArray(), out.copyOfRange(0, 3), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), out.copyOfRange(3, 6), 0f)
    }

    @Test fun halfDevelopFollowsThePhaseAndPerSiteBlack() {
        // BGGR: site 0 is blue, site 3 red. Per-site black levels.
        val buffer = packRaw10(4, 2, intArrayOf(500, 400, 0, 0, 300, 200, 0, 0))
        val params = RawDevelopParams(RawCfa.BGGR, floatArrayOf(10f, 20f, 30f, 40f), 1010f, floatArrayOf(1f, 1f, 1f),
            RawDevelop.IDENTITY.map { it.toFloat() }.toFloatArray())
        val out = RawDevelop.developHalf(buffer, header(4, 2), params)
        val red = (200 - 40) / (1010f - 40f)
        val green = ((400 - 20) / (1010f - 20f) + (300 - 30) / (1010f - 30f)) / 2f
        val blue = (500 - 10) / (1010f - 10f)
        assertArrayEquals(floatArrayOf(red, green, blue).map(RawDevelop::tone).toFloatArray(), out.copyOfRange(0, 3), 1e-6f)
    }

    @Test fun whiteBalanceClipsSaturatedHighlightsToWhite() {
        val buffer = packRaw10(4, 2, IntArray(8) { 1023 })
        val params = RawDevelopParams(RawCfa.RGGB, FloatArray(4), 1023f, floatArrayOf(2f, 1f, 1.6f),
            RawDevelop.IDENTITY.map { it.toFloat() }.toFloatArray())
        val out = RawDevelop.developHalf(buffer, header(4, 2), params)
        val white = RawDevelop.tone(1f)
        assertArrayEquals(floatArrayOf(white, white, white), out.copyOfRange(0, 3), 1e-6f)
    }

    @Test fun matrixAndNegativeClipApplyAfterBalance() {
        val params = RawDevelopParams(RawCfa.RGGB, FloatArray(4), 1023f, floatArrayOf(2f, 1f, 1f),
            floatArrayOf(1f, 0f, 0f, 0f, 1f, -1f, 0.5f, 0.5f, 0f))
        val out = RawDevelop.finish(floatArrayOf(0.25f, 0.2f, 0.4f), params)
        assertArrayEquals(floatArrayOf(RawDevelop.tone(0.5f), 0f, RawDevelop.tone(0.35f)), out, 1e-6f)
    }

    @Test fun monoHalfDevelopIsTheQuadMean() {
        val buffer = packRaw10(4, 2, intArrayOf(100, 200, 0, 0, 300, 400, 0, 0))
        val out = RawDevelop.developHalf(buffer, header(4, 2), identityParams(cfa = RawCfa.MONO))
        val grey = RawDevelop.tone(250f / 1023f)
        assertArrayEquals(floatArrayOf(grey, grey, grey), out.copyOfRange(0, 3), 1e-6f)
    }

    @Test fun bilinearDevelopIsFlatOnAFlatField() {
        // Every site at the same level with identity colour must develop to the same grey everywhere.
        val buffer = packRaw10(8, 6, IntArray(48) { 512 })
        val out = RawDevelop.developBilinear(buffer, header(8, 6), identityParams())
        val grey = RawDevelop.tone(512f / 1023f)
        for (value in out) assertEquals(grey, value, 1e-6f)
    }

    @Test fun bilinearDevelopInterpolatesNeighbours() {
        // RGGB 8x4: R sites hold 400, G sites 200, B sites 100, except the B site at (3,1) holds 300.
        val codes = IntArray(32) { index ->
            val x = index % 8; val y = index / 8
            when (RawCfa.RGGB.siteChannels[RawCfa.RGGB.site(x, y)]) { 0 -> 400; 1 -> 200; else -> 100 }
        }
        codes[1 * 8 + 3] = 300
        val out = RawDevelop.developBilinear(packRaw10(8, 4, codes), header(8, 4), identityParams())
        fun pixel(x: Int, y: Int) = out.copyOfRange((y * 8 + x) * 3, (y * 8 + x) * 3 + 3)
        fun t(code: Float) = RawDevelop.tone(code / 1023f)
        // R site (2,2): own red, green from four sides, blue from four diagonals: (1,1),(3,1),(1,3),(3,3).
        assertArrayEquals(floatArrayOf(t(400f), t(200f), t((100f + 300f + 100f + 100f) / 4f)), pixel(2, 2), 1e-6f)
        // G site (3,2) on a red row: red left/right, blue above/below (3,1) and (3,3).
        assertArrayEquals(floatArrayOf(t(400f), t(200f), t((300f + 100f) / 2f)), pixel(3, 2), 1e-6f)
        // Corner (0,0) mirrors by two: blue neighbour (1,1) counted, the mirrored one is (1,1) again.
        assertArrayEquals(floatArrayOf(t(400f), t(200f), t(100f)), pixel(0, 0), 1e-6f)
    }

    @Test fun mirrorKeepsThePhase() {
        assertEquals(1, RawDevelop.mirror(-1, 8))
        assertEquals(6, RawDevelop.mirror(8, 8))
        assertEquals(4, RawDevelop.mirror(4, 8))
    }

    // ------------------------------------------------------------ layout helpers

    @Test fun letterboxFitsTheFrameAspect() {
        assertArrayEquals(intArrayOf(0, 540, 1920, 1080), RawDevelop.letterbox(4096, 2304, 1920, 2160))
        assertArrayEquals(intArrayOf(240, 0, 1440, 1080), RawDevelop.letterbox(4080, 3060, 1920, 1080))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), RawDevelop.letterbox(4080, 3060, 0, 1080))
    }

    @Test fun mipLevelsReachOnePixel() {
        assertEquals(1, RawDevelop.mipLevels(1, 1))
        assertEquals(11, RawDevelop.mipLevels(2040, 1530))
        assertEquals(12, RawDevelop.mipLevels(4080, 3060))
    }
}
