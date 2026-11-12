/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import android.app.ActivityManager
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.camera.RawVideoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RawVideoMetadataTest {
    @Test
    fun staticValuesUseTheSpecFormats() {
        // android.util.Rational reduces fractions, so the numerators are odd.
        val matrix = ColorSpaceTransform(Array(9) { index -> Rational(index * 2 - 7, if (index == 8) -1024 else 1024) })
        val values = RawVideoMetadata.staticValues(
            cfaArrangement = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG,
            blackLevel = BlackLevelPattern(intArrayOf(64, 65, 66, 67)),
            whiteLevel = 1023,
            colorMatrix1 = matrix,
            colorMatrix2 = null,
            forwardMatrix1 = matrix,
            forwardMatrix2 = null,
            illuminant1 = 21,
            illuminant2 = 17,
            activeArray = Rect(8, 8, 4088, 3068),
            focalLengthMm = 6.3f,
            aperture = 1.7f,
        )
        assertEquals("GRBG", values["cfa"])
        assertEquals("6.3", values["focalLength"])
        assertEquals("1.7", values["aperture"])
        assertEquals("64 65 66 67", values["blackLevel"])
        assertEquals("1023", values["whiteLevel"])
        assertEquals("-7/1024 -5/1024 -3/1024 -1/1024 1/1024 3/1024 5/1024 7/1024 -9/1024", values["colorMatrix1"])
        assertEquals(values["colorMatrix1"], values["forwardMatrix1"])
        assertNull(values["colorMatrix2"])
        assertEquals("21", values["calibrationIlluminant1"])
        assertEquals("17", values["calibrationIlluminant2"])
        assertEquals("8 8 4088 3068", values["activeArray"])
    }

    @Test
    fun lensKeysAreLeftOutWhenTheCameraDoesNotAdvertiseThem() {
        val values = RawVideoMetadata.staticValues(null, null, null, null, null, null, null, null, null, null, aperture = 0f)
        assertNull(values["focalLength"])
        assertNull(values["aperture"])
    }

    @Test
    fun frameValuesCarryTheDynamicLevels() {
        val values = RawVideoMetadata.frameValues(
            exposureNs = 16_666_667,
            iso = 400,
            frameDurationNs = 33_333_333,
            neutral = arrayOf(Rational(511, 1024), Rational(1023, 1024), Rational(701, -1024)),
            ccGains = RggbChannelVector(2.0f, 1.0f, 1.0f, 1.75f),
            focusDistance = 0.00001f,
            dynamicBlackLevel = floatArrayOf(64f, 64.5f, 63.75f, 64f),
            dynamicWhiteLevel = 1020,
        )
        assertEquals("16666667", values["exposureNs"])
        assertEquals("400", values["iso"])
        assertEquals("33333333", values["frameDurationNs"])
        assertEquals("511/1024 1023/1024 -701/1024", values["neutral"])
        assertEquals("2 1 1 1.75", values["ccGains"])
        assertEquals("0.00001", values["focusDistance"])
        assertEquals("64 64.5 63.75 64", values["dynamicBlackLevel"])
        assertEquals("1020", values["whiteLevel"])

        val empty = RawVideoMetadata.frameValues(null, null, null, null, null, null, floatArrayOf(1f, 2f), null)
        assertEquals(emptyMap<String, String>(), empty)
    }

    @Test
    fun decimalsHaveNoExponent() {
        assertEquals("0", RawVideoMetadata.decimal(0f))
        assertEquals("0", RawVideoMetadata.decimal(-0f))
        assertEquals("0", RawVideoMetadata.decimal(Float.NaN))
        assertEquals("12345680", RawVideoMetadata.decimal(1.234568e7f))
        assertEquals("-0.5", RawVideoMetadata.decimal(-0.5f))
        assertEquals("0/1", RawVideoMetadata.rational(Rational(1, 0)))
    }

    @Test
    fun centredCropKeepsPackingAndCfaPhase() {
        assertEquals(RawVideoCrop(120, 450, 3840, 2160), RawVideoCrop.centred(RawVideoConfig("0", 4080, 3060, 3840, 2160, 30)))
        assertEquals(RawVideoCrop(0, 0, 4080, 3060), RawVideoCrop.centred(RawVideoConfig("0", 4080, 3060, 4080, 3060, 30)))
        // (4000 - 1924) / 2 = 1038 -> 1036 (multiple of 4); (3000 - 1081) / 2 = 959 -> 958 (even).
        assertEquals(RawVideoCrop(1036, 958, 1924, 1081), RawVideoCrop.centred(RawVideoConfig("1", 4000, 3000, 1924, 1081, 30)))
        assertThrows(IllegalArgumentException::class.java) { RawVideoCrop.centred(RawVideoConfig("0", 4080, 3060, 1922, 1080, 30)) }
    }

    @Test
    fun ringBudgetFollowsDecisionFour() {
        val gib = 1L shl 30
        val slot = 15_606_000
        // 10 GiB device, plenty free: the 1 GiB cap binds.
        val big = RawRingBudget.plan(totalMem = 10 * gib, availMem = 6 * gib, threshold = gib / 4, slotBytes = slot)!!
        assertEquals(RawRingLimit.TARGET, big.limitedBy)
        assertEquals(gib, big.targetBytes)
        assertEquals((gib / slot).toInt(), big.slotCount)
        assertEquals(slot, big.slotBytes)
        // 4 GiB device: 1/8 of total is the target.
        val small = RawRingBudget.plan(4 * gib, 3 * gib, gib / 4, slot)!!
        assertEquals(gib / 2, small.targetBytes)
        assertEquals((gib / 2 / slot).toInt(), small.slotCount)
        // Little free memory: availMem - threshold binds.
        val tight = RawRingBudget.plan(10 * gib, gib / 4 + 50L * slot, gib / 4, slot)!!
        assertEquals(RawRingLimit.AVAILABLE_MEMORY, tight.limitedBy)
        assertEquals(50, tight.slotCount)
        assertEquals(50L * slot, tight.memoryCapBytes)
        // Fewer than four frames, or availMem under the threshold: no ring.
        assertNull(RawRingBudget.plan(12 * gib, gib / 4 + 3L * slot, gib / 4, slot))
        assertNull(RawRingBudget.plan(12 * gib, gib / 8, gib / 4, slot))
        assertThrows(IllegalArgumentException::class.java) { RawRingBudget.plan(gib, gib, 0, 0) }

        val memory = ActivityManager.MemoryInfo().apply { totalMem = 8 * gib; availMem = 4 * gib; threshold = gib / 4 }
        assertEquals(RawRingBudget.plan(8 * gib, 4 * gib, gib / 4, slot), RawRingBudget.plan(memory, slot))
    }

    @Test
    fun ringTargetGrowsWithTotalMemoryTiers() {
        val gib = 1L shl 30
        // Below 11 GiB: min(1 GiB, totalMem / 8).
        assertEquals(gib / 2, RawRingBudget.targetBytes(4 * gib))
        assertEquals(gib, RawRingBudget.targetBytes(11 * gib - 1))
        // 12 GB phones (about 11 GiB reported, the S24 Ultra): 2 GiB.
        assertEquals(2 * gib, RawRingBudget.targetBytes(11 * gib))
        assertEquals(2 * gib, RawRingBudget.targetBytes(14 * gib - 1))
        // 16 GB phones (about 15 GiB reported, the Razr Fold): 4 GiB.
        assertEquals(4 * gib, RawRingBudget.targetBytes(14 * gib))
        assertEquals(4 * gib, RawRingBudget.targetBytes(15 * gib + gib / 2))
        assertEquals(0L, RawRingBudget.targetBytes(-1))
    }

    @Test
    fun largeTiersStayBelowAvailableMemory() {
        val gib = 1L shl 30
        // Razr full 4000x3000 RAW10 frame: 15 MB a slot.
        val slot = 15_000_000
        val razr = RawRingBudget.plan(totalMem = 15 * gib, availMem = 9 * gib, threshold = gib / 2, slotBytes = slot)!!
        assertEquals(RawRingLimit.TARGET, razr.limitedBy)
        assertEquals(4 * gib, razr.targetBytes)
        assertEquals((4 * gib / slot).toInt(), razr.slotCount)
        assertTrue(razr.ringBytes > Int.MAX_VALUE)
        assertTrue(razr.ringBytes <= 4 * gib)
        val s24 = RawRingBudget.plan(totalMem = 11 * gib + gib / 4, availMem = 5 * gib, threshold = gib / 2, slotBytes = slot)!!
        assertEquals(RawRingLimit.TARGET, s24.limitedBy)
        assertEquals((2 * gib / slot).toInt(), s24.slotCount)
        // Less free than the tier: availMem - threshold binds, the tier stays the recorded target.
        val busy = RawRingBudget.plan(totalMem = 15 * gib, availMem = 3 * gib, threshold = gib / 2, slotBytes = slot)!!
        assertEquals(RawRingLimit.AVAILABLE_MEMORY, busy.limitedBy)
        assertEquals(4 * gib, busy.targetBytes)
        assertEquals(gib * 5 / 2, busy.memoryCapBytes)
        assertEquals((gib * 5 / 2 / slot).toInt(), busy.slotCount)
    }
}
