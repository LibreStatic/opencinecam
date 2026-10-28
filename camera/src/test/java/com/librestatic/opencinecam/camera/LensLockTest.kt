/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensLockTest {
    // Razr Fold (blanc) characteristics: physical size width, active width, pixel array width, focal.
    private val logical0 = RawZoomCrop.fovWidth(9.76f, 4000, 4000, 6.57f)
    private val wide2 = RawZoomCrop.fovWidth(9.76f, 4000, 4000, 6.57f)
    private val ultraWide3 = RawZoomCrop.fovWidth(4.93568f, 3840, 3856, 1.826f)
    private val tele5 = RawZoomCrop.fovWidth(6.5536f, 4096, 4096, 13.3f)

    @Test fun razrAnchorsFollowTheFieldOfView() {
        assertEquals(0.55f, lensAnchorRatio(logical0, ultraWide3)!!, 0.02f)
        assertEquals(1.0f, lensAnchorRatio(logical0, wide2)!!, 0.02f)
        assertEquals(3.0f, lensAnchorRatio(logical0, tele5)!!, 0.02f)
    }

    @Test fun anchorNeedsBothWidths() {
        assertNull(lensAnchorRatio(null, tele5))
        assertNull(lensAnchorRatio(logical0, null))
        assertNull(lensAnchorRatio(logical0, 0.0))
        assertNull(lensAnchorRatio(-1.0, tele5))
    }

    @Test fun zoomRoundTripsBetweenScales() {
        val range = 1f..8f
        assertEquals(2f, LensLockZoom.toPhysical(6f, 3f, range), 1e-5f)
        assertEquals(6f, LensLockZoom.toUi(2f, 3f), 1e-5f)
        for (ui in listOf(0.55f, 1f, 2.2f, 4.4f)) {
            val physical = LensLockZoom.toPhysical(ui, 0.55f, range)
            assertEquals(ui, LensLockZoom.toUi(physical, 0.55f), 1e-5f)
        }
    }

    @Test fun zoomIsClampedToThePhysicalRange() {
        val range = 1f..8f
        // The tele locked at 3x: 1x on the logical scale is below its widest framing.
        assertEquals(1f, LensLockZoom.toPhysical(1f, 3f, range), 1e-5f)
        assertEquals(8f, LensLockZoom.toPhysical(100f, 3f, range), 1e-5f)
        assertEquals(3f..24f, LensLockZoom.uiRange(3f, range))
        assertEquals(0.55f, LensLockZoom.uiRange(0.55f, range).start, 1e-6f)
        // A degenerate anchor leaves the ratio unscaled.
        assertEquals(2f, LensLockZoom.toPhysical(2f, 0f, range), 1e-5f)
        assertEquals(1f, LensLockZoom.toPhysical(Float.NaN, 3f, range), 1e-5f)
    }

    private val profiles = listOf(
        Triple(3840, 2160, 30), Triple(3840, 2160, 60),
        Triple(1920, 1080, 30), Triple(1920, 1080, 60),
        Triple(1280, 720, 30), Triple(1440, 1080, 30),
    )

    @Test fun profileKeepsTheSameSizeAndRate() {
        assertEquals(3, LensLockMapping.profileIndex(1920, 1080, 60, profiles))
    }

    @Test fun profileFallsToTheLargestSmallerSizeOfTheSameAspectAndRate() {
        // No 2560x1440 on the physical: 1920x1080 at the same 30 fps, not the larger 4K.
        assertEquals(2, LensLockMapping.profileIndex(2560, 1440, 30, profiles))
        // 4:3 request keeps 4:3.
        assertEquals(5, LensLockMapping.profileIndex(2880, 2160, 30, profiles))
    }

    @Test fun profileFallsToTheFirstWithTheSameRate() {
        // No 16:9 or 4:3 profile fits a 1:1 request: the first 60 fps profile.
        assertEquals(1, LensLockMapping.profileIndex(1080, 1080, 60, profiles))
        assertNull(LensLockMapping.profileIndex(1080, 1080, 60, profiles, anyRate = false))
        assertNull(LensLockMapping.profileIndex(1920, 1080, 24, profiles))
    }

    @Test fun stillSizeIsTheLargestWithTheSameAspect() {
        val sizes = listOf(4096 to 3072, 4000 to 3000, 4096 to 2304, 1920 to 1080)
        assertEquals(4096 to 3072, LensLockMapping.stillSize(4000, 3000, sizes))
        assertEquals(4096 to 2304, LensLockMapping.stillSize(3840, 2160, sizes))
        assertNull(LensLockMapping.stillSize(3000, 3000, sizes))
        assertNull(LensLockMapping.stillSize(null, null, sizes))
    }

    @Test fun quirksResolveAgainstTheLogicalId() {
        assertEquals("0", quirkCameraIdOf("3", LensLockBinding("0", "3", 0.55f)))
        assertEquals("1", quirkCameraIdOf("1", null))
    }
}
