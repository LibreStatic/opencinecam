/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class RawZoomCropTest {
    private fun fov(crop: CropRect? = null, zoom: Float? = null) = RawZoomCrop.processedFieldOfView(4000, 3000, crop, zoom)
    private fun assertRect(r: NormRect?, l: Double, t: Double, rt: Double, b: Double) {
        assertNotNull(r)
        assertEquals(l, r!!.left, 1e-6); assertEquals(t, r.top, 1e-6)
        assertEquals(rt, r.right, 1e-6); assertEquals(b, r.bottom, 1e-6)
    }

    @Test fun fullFrameIsNull() {
        assertNull(RawZoomCrop.rawCrop(fov(CropRect(0, 0, 4000, 3000), 1f), 1.0))
        assertNull(RawZoomCrop.rawCrop(fov(), 1.0))
    }

    @Test fun zoomTwoIsCentredHalf() = assertRect(RawZoomCrop.rawCrop(fov(CropRect(0, 0, 4000, 3000), 2f), 1.0), 0.25, 0.25, 0.75, 0.75)

    @Test fun api29CropRegionOnly() = assertRect(RawZoomCrop.rawCrop(fov(CropRect(1000, 750, 3000, 2250)), 1.0), 0.25, 0.25, 0.75, 0.75)

    @Test fun offCentreCropWithZoom() {
        // Crop centred at (0.25, 0.5) of width 0.5, then zoom 2 about the centre.
        val r = RawZoomCrop.rawCrop(fov(CropRect(0, 0, 2000, 3000), 2f), 1.0)
        assertRect(r, 0.25, 0.25, 0.5, 0.75)
    }

    @Test fun teleActiveAtMatchingZoomIsNull() = assertNull(RawZoomCrop.rawCrop(fov(zoom = 2f), 2.0))

    @Test fun teleAtZoomThree() {
        val r = RawZoomCrop.rawCrop(fov(zoom = 3f), 2.0)!!
        assertEquals(1 / 1.5, r.width, 1e-6)
    }

    @Test fun ultrawideBelowOne() {
        val r = RawZoomCrop.rawCrop(fov(zoom = 0.6f), 0.5)!!
        assertEquals(0.833333, r.width, 1e-5)
    }

    @Test fun scaleNearUnityIsOne() {
        assertEquals(1.0, RawZoomCrop.rawLensScale(1.04, 1.0), 0.0)
        assertEquals(1.0, RawZoomCrop.rawLensScale(1.0, 1.04), 0.0)
        assertEquals(2.0, RawZoomCrop.rawLensScale(2.0, 1.0), 0.0)
        assertEquals(1.0, RawZoomCrop.rawLensScale(null, 1.0), 0.0)
    }
}
