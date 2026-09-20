/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import org.junit.Assert.*
import org.junit.Test

class VideoDisplayGeometryTest {
    @Test fun squarePixelsAndRotationPreserveExistingRaster() {
        assertEquals(VideoDisplayGeometry(96, 64), videoDisplayGeometry(96, 64))
        assertEquals(VideoDisplayGeometry(64, 96), videoDisplayGeometry(96, 64, rotation = 90))
        assertEquals(VideoDisplayGeometry(96, 64), videoDisplayGeometry(96, 64, rotation = 180))
        assertEquals(VideoDisplayGeometry(64, 96), videoDisplayGeometry(96, 64, rotation = 270))
    }
    @Test fun inclusiveCropThenSarThenRotationMatchesIndependentAnamorphicRaster() {
        val landscape = videoDisplayGeometry(96, 64, cropBottom = 61, sarWidth = 2)
        val portrait = videoDisplayGeometry(96, 64, cropBottom = 61, sarWidth = 2, rotation = 90)
        assertEquals(VideoDisplayGeometry(192, 62), landscape)
        assertEquals(VideoDisplayGeometry(62, 192), portrait)
        assertEquals(192.0 / 62, landscape.width.toDouble() / landscape.height, 0.0)
        assertEquals(62.0 / 192, portrait.width.toDouble() / portrait.height, 0.0)
    }
    @Test fun croppedOriginAndSinglePixelUseInclusiveEdgesWithoutSecondCrop() {
        assertEquals(VideoDisplayGeometry(80, 50), videoDisplayGeometry(96, 64, 8, 7, 87, 56))
        assertEquals(VideoDisplayGeometry(1, 1), videoDisplayGeometry(96, 64, 8, 7, 8, 7))
        // Image.cropRect.width/height are already visible; pass as a new full visible raster.
        assertEquals(VideoDisplayGeometry(192, 62), videoDisplayGeometry(96, 62, sarWidth = 2))
    }
    @Test fun fractionalSarIsNotRoundedBeforeFitAndEquivalentPairsAgree() {
        val ntsc = videoDisplayGeometry(720, 480, sarWidth = 8, sarHeight = 9)
        assertEquals(4.0 / 3, ntsc.width.toDouble() / ntsc.height, 0.0)
        assertEquals(ntsc, videoDisplayGeometry(720, 480, sarWidth = 16, sarHeight = 18))
        val rotated = videoDisplayGeometry(720, 480, sarWidth = 8, sarHeight = 9, rotation = 270)
        assertEquals(3.0 / 4, rotated.width.toDouble() / rotated.height, 0.0)
    }
    @Test fun invalidRasterCropSarAndRotationFailInsteadOfStretchingUnknownGeometry() {
        val invalid = listOf<() -> Unit>(
            { videoDisplayGeometry(0, 64) }, { videoDisplayGeometry(96, -1) },
            { videoDisplayGeometry(96, 64, cropLeft = -1) }, { videoDisplayGeometry(96, 64, cropTop = -1) },
            { videoDisplayGeometry(96, 64, cropRight = 96) }, { videoDisplayGeometry(96, 64, cropBottom = 64) },
            { videoDisplayGeometry(96, 64, cropLeft = 3, cropRight = 2) },
            { videoDisplayGeometry(96, 64, cropTop = 3, cropBottom = 2) },
            { videoDisplayGeometry(96, 64, sarWidth = 0) }, { videoDisplayGeometry(96, 64, sarHeight = -1) },
            { videoDisplayGeometry(96, 64, rotation = 45) },
        )
        invalid.forEach { operation -> assertThrows(IllegalArgumentException::class.java) { operation() } }
    }
    @Test fun largeRationalProductsReduceWithoutIntegerOverflowOrSilentTruncation() {
        assertEquals(VideoDisplayGeometry(Int.MAX_VALUE, 1), videoDisplayGeometry(2, 2, sarWidth = Int.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) {
            videoDisplayGeometry(Int.MAX_VALUE, 1, sarWidth = Int.MAX_VALUE, sarHeight = 2)
        }
    }
    @Test fun legacyDisplayRatioIsNotRecroppedOrAppliedAsSampleAspect() {
        assertEquals(VideoDisplayGeometry(192, 62), videoDisplayGeometry(96, 64, cropBottom = 61,
            legacyDisplayWidth = 192, legacyDisplayHeight = 62))
        assertEquals(VideoDisplayGeometry(62, 192), videoDisplayGeometry(96, 64, cropBottom = 61, rotation = 90,
            legacyDisplayWidth = 192, legacyDisplayHeight = 62))
        assertEquals(VideoDisplayGeometry(62, 192), videoDisplayGeometry(96, 62, rotation = 270,
            legacyDisplayWidth = 192, legacyDisplayHeight = 62))
    }
    @Test fun incompleteInvalidOrDoubleAppliedLegacyRatiosFail() {
        assertThrows(IllegalArgumentException::class.java) { videoDisplayGeometry(96, 64, legacyDisplayWidth = 192) }
        assertThrows(IllegalArgumentException::class.java) { videoDisplayGeometry(96, 64, legacyDisplayHeight = 62) }
        assertThrows(IllegalArgumentException::class.java) { videoDisplayGeometry(96, 64, legacyDisplayWidth = 192, legacyDisplayHeight = 0) }
        assertThrows(IllegalArgumentException::class.java) { videoDisplayGeometry(96, 64, sarWidth = 2, legacyDisplayWidth = 192, legacyDisplayHeight = 62) }
        assertThrows(IllegalArgumentException::class.java) { videoDisplayGeometry(96, 64, cropBottom = 64, legacyDisplayWidth = 192, legacyDisplayHeight = 62) }
    }

}
