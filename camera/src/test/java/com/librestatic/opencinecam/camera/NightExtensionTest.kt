/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraExtensionCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightExtensionTest {
    private val preview = listOf(1920 to 1080, 1440 to 1080, 1280 to 720, 640 to 480)
    private val jpeg = listOf(4080 to 3072, 4096 to 2304, 2040 to 1536, 4000 to 3000)

    @Test fun extensionIdMatchesThePlatformConstant() {
        assertEquals(CameraExtensionCharacteristics.EXTENSION_NIGHT, NIGHT_EXTENSION_ID)
        assertEquals(android.os.Build.VERSION_CODES.S, NIGHT_EXTENSION_MIN_SDK)
    }

    @Test fun capabilityNeedsTheNightExtensionAndBothOutputs() {
        assertNull(nightExtensionCapability(listOf(0, 2, 3), preview, jpeg))
        assertNull(nightExtensionCapability(emptyList(), preview, jpeg))
        assertNull(nightExtensionCapability(listOf(NIGHT_EXTENSION_ID), emptyList(), jpeg))
        assertNull(nightExtensionCapability(listOf(NIGHT_EXTENSION_ID), preview, emptyList()))
        assertNull(nightExtensionCapability(listOf(NIGHT_EXTENSION_ID), listOf(0 to 0), jpeg))
        val capability = nightExtensionCapability(listOf(0, NIGHT_EXTENSION_ID), preview + preview, jpeg)
        assertEquals(preview, capability?.previewSizes)
    }

    @Test fun engagementRequiresRequestSdkAndCapability() {
        val capability = nightExtensionCapability(listOf(NIGHT_EXTENSION_ID), preview, jpeg)
        assertTrue(nightExtensionEngaged(true, 31, capability))
        assertTrue(nightExtensionEngaged(true, 36, capability))
        assertFalse(nightExtensionEngaged(false, 36, capability))
        assertFalse(nightExtensionEngaged(true, 30, capability))
        assertFalse(nightExtensionEngaged(true, 36, null))
    }

    @Test fun jpegIsTheLargestSizeInTheReferenceAspect() {
        val capability = requireNotNull(nightExtensionCapability(listOf(NIGHT_EXTENSION_ID), preview, jpeg))
        // 4000x3000, 4080x3072 and 2040x1536 are all 4:3 within tolerance; the 16:9 size is ignored.
        assertEquals(4080 to 3072, capability.selectSizes(4000 to 3000, null).jpeg)
        assertEquals(4096 to 2304, capability.selectSizes(3840 to 2160, null).jpeg)
    }

    @Test fun jpegFallsBackToTheLargestWhenTheAspectIsNotOffered() {
        val capability = requireNotNull(nightExtensionCapability(listOf(NIGHT_EXTENSION_ID), preview, jpeg))
        assertEquals(4080 to 3072, capability.selectSizes(1000 to 1000, null).jpeg)
        assertEquals(4080 to 3072, capability.selectSizes(null, null).jpeg)
    }

    @Test fun previewKeepsTheViewfinderSizeWhenTheExtensionOffersIt() {
        val capability = requireNotNull(nightExtensionCapability(listOf(NIGHT_EXTENSION_ID), preview, jpeg))
        assertEquals(1280 to 720, capability.selectSizes(4000 to 3000, 1280 to 720).preview)
    }

    @Test fun previewOtherwiseFollowsTheViewfinderAspectNearest1080p() {
        val capability = requireNotNull(nightExtensionCapability(listOf(NIGHT_EXTENSION_ID),
            listOf(4000 to 3000, 1440 to 1080, 1920 to 1080, 800 to 600), jpeg))
        // 1600x900 is not offered: the 16:9 size wins over the 4:3 ones.
        assertEquals(1920 to 1080, capability.selectSizes(4000 to 3000, 1600 to 900).preview)
        // An unknown reference falls back to the JPEG's 4:3, bounded to 1080p's pixel count.
        assertEquals(1440 to 1080, capability.selectSizes(4000 to 3000, null).preview)
    }

    @Test fun previewTakesTheSmallestWhenEverythingExceeds1080p() {
        val capability = requireNotNull(nightExtensionCapability(listOf(NIGHT_EXTENSION_ID),
            listOf(4000 to 3000, 3200 to 2400), jpeg))
        assertEquals(3200 to 2400, capability.selectSizes(4000 to 3000, null).preview)
    }
}
