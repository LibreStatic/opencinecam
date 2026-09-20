/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class PhotoAspectTest {
    private fun selection(width: Int = 4, height: Int = 3) = PhotoAspectSelection(true, width, height)
    private fun still(images: List<StillImagePayload>, aspect: PhotoAspectSelection = PhotoAspectSelection()) =
        CapturedStill(1, 100, 90, 95, PhotoFlashReport(PhotoFlashSelection(), 1, 0, null, null, null, null, 100), images, aspect)

    @Test fun defaultIsDisabledAndCanonicalFourByThree() {
        assertEquals(PhotoAspectSelection(false, 4, 3), PhotoAspectSelection())
        assertEquals(PhotoCropRect(0, 0, 640, 480), PhotoAspectSelection().centeredCrop(640, 480))
    }
    @Test fun normalizationIsExactAndPreservesTheEnabledFlag() {
        assertEquals(selection(4, 3), PhotoAspectSelection.normalized(true, 400, 300))
        assertEquals(selection(239, 100), PhotoAspectSelection.normalized(true, 2390, 1000))
        assertEquals(PhotoAspectSelection(false, 1, 1), PhotoAspectSelection.normalized(false, 10_000, 10_000))
        assertEquals(selection(10_000, 1), PhotoAspectSelection.normalized(true, 10_000, 1))
    }
    @Test fun constructorAndCopyRejectNonCanonicalRatios() {
        assertThrows(IllegalArgumentException::class.java) { PhotoAspectSelection(true, 8, 6) }
        assertThrows(IllegalArgumentException::class.java) { selection().copy(width = 6) }
    }
    @Test fun invalidBoundsAreRejectedBeforeGcdOrMultiplication() {
        for ((w, h) in listOf(0 to 1, 1 to 0, -1 to 1, 1 to -1, 10_001 to 1, Int.MAX_VALUE to 1)) {
            assertThrows(IllegalArgumentException::class.java) { PhotoAspectSelection(true, w, h) }
            assertThrows(IllegalArgumentException::class.java) { PhotoAspectSelection.normalized(true, w, h) }
        }
    }
    @Test fun customCinemaRatioUsesLargestExactIntegerRectangle() {
        val rect = selection(239, 100).centeredCrop(4000, 3000)
        assertEquals(PhotoCropRect(88, 700, 3824, 1600), rect)
        assertEquals(0L, rect.width.toLong() * 100 - rect.height.toLong() * 239)
    }
    @Test fun squareAndPortraitRatiosCropDifferentCenteredPixels() {
        assertEquals(PhotoCropRect(80, 0, 480, 480), selection(1, 1).centeredCrop(640, 480))
        assertEquals(PhotoCropRect(140, 0, 360, 480), selection(3, 4).centeredCrop(640, 480))
        assertEquals(PhotoCropRect(0, 0, 640, 480), selection(4, 3).centeredCrop(640, 480))
    }
    @Test fun finalOrientedDimensionsDetermineTheRatioNotSensorAxes() {
        val desired = selection(4, 3)
        assertEquals(PhotoCropRect(0, 0, 640, 480), desired.centeredCrop(640, 480))
        assertEquals(PhotoCropRect(0, 140, 480, 360), desired.centeredCrop(480, 640))
    }
    @Test fun oddRemaindersStayCenteredWithinOnePixelWithoutResizing() {
        assertEquals(PhotoCropRect(0, 1, 4, 3), selection(4, 3).centeredCrop(5, 6))
        val rect = selection(1, 1).centeredCrop(643, 480)
        assertEquals(81, rect.left)
        assertEquals(82, 643 - rect.left - rect.width)
    }
    @Test fun unrepresentableRatioRejectsInsteadOfUpscalingOrRounding() {
        assertThrows(IllegalArgumentException::class.java) { selection(9999, 10_000).centeredCrop(640, 480) }
        assertThrows(IllegalArgumentException::class.java) { selection(239, 100).centeredCrop(238, 100) }
        assertThrows(IllegalArgumentException::class.java) { selection().centeredCrop(0, 480) }
    }
    @Test fun largeSourceArithmeticNeverOverflowsTheRectangle() {
        val rect = selection(9999, 10_000).centeredCrop(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(rect.left.toLong() + rect.width <= Int.MAX_VALUE)
        assertTrue(rect.top.toLong() + rect.height <= Int.MAX_VALUE)
        assertEquals(rect.width.toLong() * 10_000, rect.height.toLong() * 9999)
    }
    @Test fun enabledMatchingRatioStillReportsItsExplicitApplication() {
        val report = selection().appliedReport(640, 480)
        assertEquals(PhotoAspectDisposition.APPLIED, report.disposition)
        assertEquals(PhotoCropRect(0, 0, 640, 480), report.crop)
        assertEquals(0, report.outputOrientationDegrees)
    }
    @Test fun disabledPlanAndFullFramePixelsHaveNoMutationOrAllocation() {
        val pixels = intArrayOf(1, 2, 3, 4)
        val report = PhotoAspectSelection().appliedReport(2, 2)
        assertEquals(PhotoAspectDisposition.FULL_FRAME, report.disposition)
        assertSame(pixels, cropAspectPixels(pixels, 2, 2, report.crop))
        assertArrayEquals(intArrayOf(1, 2, 3, 4), pixels)
    }
    @Test fun cropCopiesOnlyTheDeclaredCenterWithoutInterpolation() {
        val pixels = IntArray(20) { it }
        val cropped = cropAspectPixels(pixels, 5, 4, selection(1, 1).centeredCrop(5, 4))
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12, 13, 15, 16, 17, 18), cropped)
        assertArrayEquals(IntArray(20) { it }, pixels)
    }
    @Test fun physicalOrientationThenCropPreservesCorrectSourcePixels() {
        val raw = intArrayOf(1, 2, 3, 4, 5, 6)
        val oriented = orientAccumulationPixels(raw, 3, 2, 6)
        assertArrayEquals(intArrayOf(4, 1, 5, 2), cropAspectPixels(oriented, 2, 3, selection(1, 1).centeredCrop(2, 3)))
    }
    @Test fun cancellationNeverReturnsAPartialCrop() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            cropAspectPixels(IntArray(12), 4, 3, PhotoCropRect(1, 0, 2, 3)) { if (++checks == 3) throw CancellationException() }
        }
    }
    @Test fun invalidPixelBoundsRejectBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) { cropAspectPixels(IntArray(1), Int.MAX_VALUE, 2, PhotoCropRect(0, 0, 1, 1)) }
        assertThrows(IllegalArgumentException::class.java) { cropAspectPixels(IntArray(3), 2, 2, PhotoCropRect(0, 0, 1, 1)) }
        assertThrows(IllegalArgumentException::class.java) { cropAspectPixels(IntArray(4), 2, 2, PhotoCropRect(1, 0, 2, 2)) }
    }
    @Test fun reportRejectsOutOfBoundsMismatchedOrOffCenterClaims() {
        val requested = selection(1, 1)
        val valid = requested.appliedReport(640, 480)
        assertThrows(IllegalArgumentException::class.java) { valid.copy(resultWidth = 479) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(crop = PhotoCropRect(81, 0, 480, 480)) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(crop = PhotoCropRect(Int.MAX_VALUE, 0, 480, 480)) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(outputOrientationDegrees = 90) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(requested = PhotoAspectSelection()) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(disposition = PhotoAspectDisposition.FULL_FRAME) }
    }
    @Test fun rawReportRetainsFullNativeDimensionsAndMetadataOrientation() {
        val report = selection(1, 1).rawReport(4032, 3024, 90)
        assertEquals(PhotoAspectDisposition.RAW_UNCHANGED, report.disposition)
        assertEquals(PhotoCropRect(0, 0, 4032, 3024), report.crop)
        assertEquals(4032, report.resultWidth)
        assertEquals(3024, report.resultHeight)
        assertEquals(90, report.outputOrientationDegrees)
    }
    @Test fun payloadRejectsFalseResultDimensionsAndRawConversionButAllowsHeicCrop() {
        val applied = selection(1, 1).appliedReport(640, 480)
        assertThrows(IllegalArgumentException::class.java) { StillImagePayload(StillImageKind.JPEG, byteArrayOf(1), 640, 480, applied) }
        assertThrows(IllegalArgumentException::class.java) { StillImagePayload(StillImageKind.DNG, byteArrayOf(1), 480, 480, applied) }
        val heic = StillImagePayload(StillImageKind.HEIC, byteArrayOf(1), 480, 480, applied)
        assertEquals(StillImageKind.HEIC, heic.kind); assertEquals(applied, heic.aspectReport)
        assertEquals(applied.requested, still(listOf(heic), applied.requested).aspectSelection)
        assertThrows(IllegalArgumentException::class.java) { StillImagePayload(StillImageKind.HEIC, byteArrayOf(1), 480, 480, selection().rawReport(480, 480, 0)) }
        assertThrows(IllegalArgumentException::class.java) { StillImagePayload(StillImageKind.JPEG, byteArrayOf(1), 640, 480, selection().rawReport(640, 480, 90)) }
    }
    @Test fun captureRequiresRequestedAspectReportOnEveryMemberOfThePair() {
        val requested = selection(1, 1)
        val jpeg = StillImagePayload(StillImageKind.JPEG, byteArrayOf(1), 480, 480, requested.appliedReport(640, 480))
        val raw = StillImagePayload(StillImageKind.DNG, byteArrayOf(2), 640, 480, requested.rawReport(640, 480, 90))
        assertEquals(2, still(listOf(jpeg, raw), requested).images.size)
        assertThrows(IllegalArgumentException::class.java) { still(listOf(jpeg, StillImagePayload(StillImageKind.DNG, byteArrayOf(2), 640, 480)), requested) }
        assertThrows(IllegalArgumentException::class.java) { still(listOf(jpeg), selection(4, 3)) }
    }
    @Test fun disabledDefaultsKeepOriginalBytesAndNullReportsForEveryLegacyFormat() {
        val bytes = byteArrayOf(3, 4, 5)
        for (kind in StillImageKind.entries) {
            val payload = StillImagePayload.owned(kind, bytes, 640, 480)
            val capture = still(listOf(payload))
            assertArrayEquals(bytes, capture.images.single().bytes)
            assertNull(capture.images.single().aspectReport)
            assertFalse(capture.aspectSelection.enabled)
        }
    }
    @Test fun annotatedRawBytesRemainUntouchedAndDefensivelyExposed() {
        val bytes = byteArrayOf(5, 6, 7)
        val payload = StillImagePayload(StillImageKind.DNG, bytes, 640, 480, selection(1, 1).rawReport(640, 480, 90))
        assertArrayEquals(bytes, payload.bytes)
        bytes[0] = 9
        assertArrayEquals(byteArrayOf(5, 6, 7), payload.bytes)
    }
}
