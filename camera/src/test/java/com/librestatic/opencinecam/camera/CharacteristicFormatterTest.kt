/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacteristicFormatterTest {
    @Test fun wellKnownEnumsAreDecodedWithTheirConstant() {
        assertEquals(
            "BACKWARD_COMPATIBLE (0), RAW (3), DYNAMIC_RANGE_TEN_BIT (18), 99",
            CharacteristicFormatter.format("android.request.availableCapabilities", intArrayOf(0, 3, 18, 99)),
        )
        assertEquals("FULL (1)", CharacteristicFormatter.format("android.info.supportedHardwareLevel", 1))
        assertEquals("HLG10 (2)", CharacteristicFormatter.format("android.request.recommendedTenBitDynamicRangeProfile", 2L))
        assertEquals("PREVIEW (1), VIDEO_RECORD (3)", CharacteristicFormatter.format("android.scaler.availableStreamUseCases", longArrayOf(1, 3)))
    }

    @Test fun unknownKeysUseGenericFormatting() {
        assertEquals("1, 2, 3", CharacteristicFormatter.format("vendor.x.modes", intArrayOf(1, 2, 3)))
        assertEquals("1.8, 4", CharacteristicFormatter.format("android.lens.info.availableApertures", floatArrayOf(1.8f, 4f)))
        assertEquals("—", CharacteristicFormatter.format("android.control.aeAvailableModes", intArrayOf()))
        assertEquals("0a ff", CharacteristicFormatter.format("vendor.blob", byteArrayOf(10, -1)))
        assertEquals("100 bytes", CharacteristicFormatter.format("vendor.blob", ByteArray(100)))
        assertEquals("true", CharacteristicFormatter.format("android.flash.info.available", true))
    }

    @Test fun entriesAreGroupedBySectionAndVendorTagsApart() {
        assertEquals("control", CharacteristicEntry("android.control.aeAvailableModes", "").section)
        assertEquals("lens", CharacteristicEntry("android.lens.info.availableApertures", "").section)
        assertTrue(CharacteristicEntry("com.vendor.feature.x", "").vendor)
        assertFalse(CharacteristicEntry("android.sensor.orientation", "").vendor)
    }

    @Test fun cameraIdsSortNumerically() {
        assertEquals(listOf("0", "2", "10", "ext"), listOf("10", "ext", "2", "0").sortedWith(Camera2CapabilityInventory.cameraIdOrder))
    }
}
