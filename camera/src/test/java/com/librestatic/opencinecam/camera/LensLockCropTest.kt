/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.camera.calibration.multiplyColumnMajor4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN-070 slice 4: the lens lock's GPU-crop route. */
class LensLockCropTest {
    // Razr Fold logical "0" zoom range and its lenses' own ranges.
    private val logicalRange = 0.5f..24f
    private val ultraWide = 0.55f to (1f..8f)
    private val wide = 1f to (1f..8f)
    private val tele = 3f to (1f..8f)

    private fun apply(m: FloatArray, u: Float, v: Float): Pair<Float, Float> =
        (m[0] * u + m[4] * v + m[12]) to (m[1] * u + m[5] * v + m[13])

    @Test fun theHalIsPinnedAtTheAnchorInsideTheLogicalRange() {
        assertEquals(0.55f, LensLockCrop.pinnedRatio(0.55f, logicalRange), 1e-6f)
        assertEquals(3f, LensLockCrop.pinnedRatio(3f, logicalRange), 1e-6f)
        // An anchor outside the logical range is coerced into it.
        assertEquals(0.6f, LensLockCrop.pinnedRatio(0.55f, 0.6f..10f), 1e-6f)
        assertEquals(1f, LensLockCrop.pinnedRatio(Float.NaN, null), 1e-6f)
    }

    @Test fun theUiRangeMatchesTheDirectRoute() {
        // Razr: UW 0.55-4.4, W 1-8, T 3-24.
        assertEquals(0.55f..4.4f, LensLockZoom.uiRange(ultraWide.first, ultraWide.second).let { it.start..(Math.round(it.endInclusive * 100) / 100f) })
        assertEquals(1f..8f, LensLockZoom.uiRange(wide.first, wide.second))
        assertEquals(3f..24f, LensLockZoom.uiRange(tele.first, tele.second))
    }

    @Test fun cropFactorIsUiOverAnchorClampedToThePhysicalRange() {
        val uiRange = LensLockZoom.uiRange(tele.first, tele.second)
        assertEquals(1f, LensLockCrop.cropFactor(3f, 3f, uiRange), 1e-6f)
        assertEquals(2f, LensLockCrop.cropFactor(6f, 3f, uiRange), 1e-6f)
        assertEquals(8f, LensLockCrop.cropFactor(24f, 3f, uiRange), 1e-6f)
        // Below the anchor there is nothing to crop; above the top it stops at the physical end.
        assertEquals(1f, LensLockCrop.cropFactor(1f, 3f, uiRange), 1e-6f)
        assertEquals(8f, LensLockCrop.cropFactor(100f, 3f, uiRange), 1e-6f)
        assertEquals(1f, LensLockCrop.cropFactor(Float.NaN, 3f, uiRange), 1e-6f)
        assertEquals(1f, LensLockCrop.cropFactor(6f, 0f, uiRange), 1e-6f)
    }

    @Test fun theReportedUiRatioIsTheRequestedOne() {
        val uiRange = LensLockZoom.uiRange(ultraWide.first, ultraWide.second)
        for (ui in listOf(0.55f, 1f, 2.2f, 4.4f)) {
            val factor = LensLockCrop.cropFactor(ui, 0.55f, uiRange)
            assertEquals(ui, LensLockCrop.uiRatio(0.55f, factor), 1e-5f)
        }
    }

    @Test fun aPinAwayFromTheAnchorStillReportsTheRequestedRatio() {
        // The logical range starts at 0.6, so the 0.55 ultra-wide is pinned at 0.6.
        val uiRange = LensLockZoom.uiRange(0.55f, 1f..8f)
        val pinned = LensLockCrop.pinnedRatio(0.55f, 0.6f..10f)
        val factor = LensLockCrop.cropFactor(2.4f, pinned, uiRange)
        assertEquals(4f, factor, 1e-5f)
        assertEquals(2.4f, LensLockCrop.uiRatio(pinned, factor), 1e-5f)
        // The top of the range stays reachable.
        assertEquals(uiRange.endInclusive, LensLockCrop.uiRatio(pinned, LensLockCrop.cropFactor(99f, pinned, uiRange)), 1e-5f)
    }

    @Test fun theUvCropIsCentred() {
        val m = LensLockCrop.uvMatrix(2f)
        val (cu, cv) = apply(m, 0.5f, 0.5f)
        assertEquals(0.5f, cu, 1e-6f); assertEquals(0.5f, cv, 1e-6f)
        val (u0, v0) = apply(m, 0f, 0f)
        assertEquals(0.25f, u0, 1e-6f); assertEquals(0.25f, v0, 1e-6f)
        val (u1, v1) = apply(m, 1f, 1f)
        assertEquals(0.75f, u1, 1e-6f); assertEquals(0.75f, v1, 1e-6f)
    }

    @Test fun noCropIsTheIdentity() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        assertArrayEquals(identity, LensLockCrop.uvMatrix(1f), 0f)
        assertArrayEquals(identity, LensLockCrop.uvMatrix(0.5f), 0f)
        assertArrayEquals(identity, LensLockCrop.uvMatrix(Float.NaN), 0f)
    }

    @Test fun theCropComposesAfterTheTextureMatrix() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val crop = LensLockCrop.uvMatrix(4f)
        assertArrayEquals(crop, multiplyColumnMajor4(identity, crop), 1e-7f)
        // A SurfaceTexture matrix with a vertical flip: T x C still maps the centre to the centre
        // and the output corners onto the flipped quarter-size window.
        val flip = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        val tc = multiplyColumnMajor4(flip, crop)
        val (cu, cv) = apply(tc, 0.5f, 0.5f)
        assertEquals(0.5f, cu, 1e-6f); assertEquals(0.5f, cv, 1e-6f)
        val (u0, v0) = apply(tc, 0f, 0f)
        assertEquals(0.375f, u0, 1e-6f); assertEquals(0.625f, v0, 1e-6f)
    }

    @Test fun theStillKeepsEvenSizesWithoutUpscaling() {
        assertEquals(2000 to 1500, LensLockCrop.stillSize(4000, 3000, 2f))
        // 4000/3 = 1333.3 -> 1334, 3000/3 = 1000.
        assertEquals(1334 to 1000, LensLockCrop.stillSize(4000, 3000, 3f))
        assertEquals(4000 to 3000, LensLockCrop.stillSize(4000, 3000, 1f))
        assertEquals(4000 to 3000, LensLockCrop.stillSize(4000, 3000, 0.5f))
        val (w, h) = LensLockCrop.stillSize(4001, 3001, 1.0001f)
        assertTrue(w <= 4001 && h <= 3001 && w % 2 == 0 && h % 2 == 0)
        assertEquals(2 to 2, LensLockCrop.stillSize(4000, 3000, 100_000f))
    }

    @Test fun theStillCropIsCentredAndKeepsTheAspect() {
        val rect = LensLockCrop.stillCrop(4000, 3000, 2.5f)
        assertEquals(PhotoCropRect(1200, 900, 1600, 1200), rect)
        assertEquals(4.0 / 3.0, rect.width.toDouble() / rect.height, 0.002)
    }

    @Test fun theStillCropComposesWithTheAspectCrop() {
        val aspect = PhotoAspectSelection(true, 16, 9)
        val rect = LensLockCrop.stillCrop(4000, 3000, 2f, aspect)
        // Inside the 2000x1500 zoomed frame the 16:9 crop is 2000x1125, centred in the source.
        assertEquals(PhotoCropRect(1000, 937, 2000, 1125), rect)
        assertTrue(rect.width <= 2000 && rect.height <= 1500)
        // The aspect report accepts it as an applied, centred crop of the full frame.
        val report = aspect.appliedReport(4000, 3000, 2f)
        assertEquals(PhotoAspectDisposition.APPLIED, report.disposition)
        assertEquals(rect, report.crop)
        assertEquals(4000, report.sourceWidth)
        // Without a zoom crop the report is the plain aspect crop.
        assertEquals(aspect.appliedReport(4000, 3000), aspect.appliedReport(4000, 3000, 1f))
    }

    @Test fun rawDefaultCropShrinksByTheCropFactor() {
        val fov = RawZoomCrop.processedFieldOfView(4000, 3000, CropRect(0, 0, 4000, 3000), 1f)
        val rect = RawZoomCrop.rawCrop(RawZoomCrop.digitalCrop(fov, 2.0), 1.0)!!
        assertEquals(0.25, rect.left, 1e-9); assertEquals(0.25, rect.top, 1e-9)
        assertEquals(0.75, rect.right, 1e-9); assertEquals(0.75, rect.bottom, 1e-9)
        // No crop leaves the full frame (no DefaultCrop).
        assertNull(RawZoomCrop.rawCrop(RawZoomCrop.digitalCrop(fov, 1.0), 1.0))
    }

    @Test fun rawDefaultCropComposesWithTheHalZoom() {
        // HAL zoom 2 then a GPU crop of 2: a quarter of the frame, centred.
        val fov = RawZoomCrop.processedFieldOfView(4000, 3000, null, 2f)
        val rect = RawZoomCrop.rawCrop(RawZoomCrop.digitalCrop(fov, 2.0), 1.0)!!
        assertEquals(0.375, rect.left, 1e-9); assertEquals(0.625, rect.right, 1e-9)
        // An off-centre processed field of view shrinks about its own centre.
        val off = RawZoomCrop.digitalCrop(NormRect(0.0, 0.0, 0.5, 1.0), 2.0)
        assertEquals(0.125, off.left, 1e-9); assertEquals(0.375, off.right, 1e-9)
        assertEquals(0.25, off.top, 1e-9); assertEquals(0.75, off.bottom, 1e-9)
    }

    @Test fun theSwitchWarningIsDebouncedBothWays() {
        val debounce = LensSwitchDebounce(threshold = 15)
        repeat(14) { assertNull(debounce.offer("2", "5")) }
        assertEquals(true, debounce.offer("2", "5"))
        assertTrue(debounce.switched)
        // Further mismatches do not repeat it.
        assertNull(debounce.offer("2", "5"))
        repeat(14) { assertNull(debounce.offer("5", "5")) }
        assertEquals(false, debounce.offer("5", "5"))
        assertFalse(debounce.switched)
    }

    @Test fun aBrokenStreakStartsOver() {
        val debounce = LensSwitchDebounce(threshold = 15)
        repeat(10) { debounce.offer("2", "5") }
        assertNull(debounce.offer("5", "5"))
        repeat(14) { assertNull(debounce.offer("2", "5")) }
        assertEquals(true, debounce.offer("2", "5"))
    }

    @Test fun resultsWithoutAnActiveIdAreIgnored() {
        val debounce = LensSwitchDebounce(threshold = 3)
        debounce.offer("2", "5"); debounce.offer("2", "5")
        assertNull(debounce.offer(null, "5"))
        assertEquals(true, debounce.offer("2", "5"))
        debounce.reset()
        assertFalse(debounce.switched)
    }
}
