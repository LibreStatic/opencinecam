/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import org.junit.Assert.*
import org.junit.Test

class PreciseSdrTransferTest {
    // Independent Decimal60 reference, not a call to production code: exact Kr/Kb601=.299/.114,
    // 709=.2126/.0722, inverse709 (knee.081), sRGB OETF (knee.0031308), final HALF_UP only.
    // This transfer conversion is not a transform between601/709 color primaries or HDR mapping.
    @Test fun twelveIndependentGopGrayReferencesBecomeSrgbRatherThanUnconvertedVideoCodes() {
        val expected = listOf(34, 55, 76, 96, 116, 136, 155, 174, 193, 212, 231, 250)
        for (bt709 in listOf(false, true)) for (index in expected.indices) {
            assertRgb(expected[index], expected[index], expected[index],
                sdr8ToSrgbArgb(32 + 18 * index, 128, 128, fullRange = false, bt709 = bt709))
        }
    }

    @Test fun nominalBlackWhiteAndLimitedFootroomHeadroomClampToDisplayEndpoints() {
        for (bt709 in listOf(false, true)) {
            for (y in 0..16) assertRgb(0, 0, 0, sdr8ToSrgbArgb(y, 128, 128, false, bt709))
            for (y in 235..255) assertRgb(255, 255, 255, sdr8ToSrgbArgb(y, 128, 128, false, bt709))
            assertRgb(0, 0, 0, sdr8ToSrgbArgb(0, 128, 128, true, bt709))
            assertRgb(255, 255, 255, sdr8ToSrgbArgb(255, 128, 128, true, bt709))
        }
    }

    @Test fun adjacentIntegerSignalsBracketBothTransferKneesWithoutPrematureQuantization() {
        // LimitedY19/20 bracket the sRGB linear knee; Y33/34 bracket inverse709's .081 knee.
        val limited = listOf(17 to 3, 18 to 7, 19 to 10, 20 to 13, 32 to 34, 33 to 36, 34 to 37, 35 to 38)
        // FullY3/4 bracket the sRGB knee; Y20/21 bracket inverse709's knee.
        val full = listOf(1 to 3, 2 to 6, 3 to 9, 4 to 11, 19 to 35, 20 to 36, 21 to 37, 22 to 38)
        for (bt709 in listOf(false, true)) {
            for ((y, value) in limited) assertRgb(value, value, value, sdr8ToSrgbArgb(y, 128, 128, false, bt709))
            for ((y, value) in full) assertRgb(value, value, value, sdr8ToSrgbArgb(y, 128, 128, true, bt709))
        }
    }

    @Test fun limitedDesaturatedChromaUsesTheSelectedMatrixBeforeChannelTransfer() {
        assertRgb(138, 75, 34, sdr8ToSrgbArgb(80, 100, 160, false, false))
        assertRgb(143, 79, 31, sdr8ToSrgbArgb(80, 100, 160, false, true))
        assertRgb(115, 151, 164, sdr8ToSrgbArgb(128, 140, 110, false, false))
        assertRgb(112, 149, 165, sdr8ToSrgbArgb(128, 140, 110, false, true))
        assertRgb(0, 81, 147, sdr8ToSrgbArgb(60, 170, 90, false, false))
        assertRgb(0, 78, 151, sdr8ToSrgbArgb(60, 170, 90, false, true))
        assertRgb(218, 229, 255, sdr8ToSrgbArgb(210, 145, 120, false, false))
        assertRgb(216, 230, 255, sdr8ToSrgbArgb(210, 145, 120, false, true))
    }

    @Test fun fullRangeUses255ForLumaAndChromaWithoutLimitedRangeExpansion() {
        assertRgb(137, 82, 46, sdr8ToSrgbArgb(80, 100, 160, true, false))
        assertRgb(142, 85, 44, sdr8ToSrgbArgb(80, 100, 160, true, true))
        assertRgb(116, 148, 159, sdr8ToSrgbArgb(128, 140, 110, true, false))
        assertRgb(113, 146, 160, sdr8ToSrgbArgb(128, 140, 110, true, true))
        assertRgb(18, 88, 146, sdr8ToSrgbArgb(60, 170, 90, true, false))
        assertRgb(0, 85, 149, sdr8ToSrgbArgb(60, 170, 90, true, true))
        assertRgb(205, 215, 242, sdr8ToSrgbArgb(210, 145, 120, true, false))
        assertRgb(203, 215, 243, sdr8ToSrgbArgb(210, 145, 120, true, true))
    }

    @Test fun saturatedAndExcursionChannelsClipBeforeFractionalPowers() {
        assertRgb(255, 0, 0, sdr8ToSrgbArgb(81, 90, 240, false, false))
        assertRgb(1, 255, 3, sdr8ToSrgbArgb(145, 54, 34, false, false))
        assertRgb(1, 0, 255, sdr8ToSrgbArgb(41, 240, 110, false, false))
        assertRgb(255, 255, 0, sdr8ToSrgbArgb(210, 16, 146, false, false))
        assertRgb(255, 40, 0, sdr8ToSrgbArgb(81, 90, 240, false, true))
        assertRgb(0, 220, 0, sdr8ToSrgbArgb(145, 54, 34, false, true))
        assertRgb(0, 30, 255, sdr8ToSrgbArgb(41, 240, 110, false, true))
        assertRgb(255, 242, 0, sdr8ToSrgbArgb(210, 16, 146, false, true))
        assertRgb(208, 0, 0, sdr8ToSrgbArgb(16, 0, 255, false, false))
        assertRgb(231, 0, 0, sdr8ToSrgbArgb(16, 0, 255, false, true))
        assertRgb(66, 255, 255, sdr8ToSrgbArgb(235, 255, 0, false, false))
        assertRgb(41, 255, 255, sdr8ToSrgbArgb(235, 255, 0, false, true))
        assertRgb(200, 0, 0, sdr8ToSrgbArgb(16, 0, 255, true, false))
        assertRgb(220, 0, 0, sdr8ToSrgbArgb(16, 0, 255, true, true))
        assertRgb(71, 255, 255, sdr8ToSrgbArgb(235, 255, 0, true, false))
        assertRgb(49, 255, 255, sdr8ToSrgbArgb(235, 255, 0, true, true))
    }

    @Test fun roundingOnlyTheFinalSrgbCodesPreservesFractionalMatrixAndLumaResults() {
        // Independently calculated premature-rounding counterexamples: Y20 limited would be14,
        // and (80,100,160)709 limited would be144,78,30 after an intermediate8-bit RGB step.
        assertRgb(13, 13, 13, sdr8ToSrgbArgb(20, 128, 128, false, false))
        assertRgb(143, 79, 31, sdr8ToSrgbArgb(80, 100, 160, false, true))
        // Full-range chroma also retains fractions: premature results would be114,145,160.
        assertRgb(113, 146, 160, sdr8ToSrgbArgb(128, 140, 110, true, true))
    }

    @Test fun everyNeutralCodeIsOpaqueNeutralAndMonotonicInBothRangesAndMatrices() {
        for (full in listOf(false, true)) for (bt709 in listOf(false, true)) {
            var previous = -1
            for (y in 0..255) {
                val actual = sdr8ToSrgbArgb(y, 128, 128, full, bt709)
                val code = actual and 255
                assertRgb(code, code, code, actual)
                assertTrue("Neutral y=$y must not reverse luminance", code >= previous)
                previous = code
            }
            assertEquals(255, previous)
        }
    }

    @Test fun allOutOfEightBitDomainSamplesFailRatherThanWrappingOrSilentlyClamping() {
        for (bad in listOf(-1, 256, Int.MIN_VALUE, Int.MAX_VALUE)) {
            for (full in listOf(false, true)) for (bt709 in listOf(false, true)) {
                assertThrows(IllegalArgumentException::class.java) { sdr8ToSrgbArgb(bad, 128, 128, full, bt709) }
                assertThrows(IllegalArgumentException::class.java) { sdr8ToSrgbArgb(64, bad, 128, full, bt709) }
                assertThrows(IllegalArgumentException::class.java) { sdr8ToSrgbArgb(64, 128, bad, full, bt709) }
            }
        }
    }

    private fun assertRgb(red: Int, green: Int, blue: Int, actual: Int) {
        val expected = (255 shl 24) or (red shl 16) or (green shl 8) or blue
        assertEquals("Expected sRGB=$red,$green,$blue; actual=${actual ushr 16 and 255},${actual ushr 8 and 255},${actual and 255}", expected, actual)
    }
}
