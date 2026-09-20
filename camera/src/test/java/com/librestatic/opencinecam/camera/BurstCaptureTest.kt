/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class BurstCaptureTest {
    private fun capture(index: Int, quality: Int = 95, aspect: PhotoAspectSelection = PhotoAspectSelection(),
        bytes: Int = 3, timestamp: Long = 100L + index, id: Long = index + 1L,
        kind: StillImageKind = StillImageKind.JPEG, width: Int = 640, height: Int = 480,
        orientation: Int = if (aspect.enabled) 0 else 90): CapturedStill {
        val report = aspect.takeIf { it.enabled }?.appliedReport(width, height)
        return CapturedStill(id, timestamp, orientation, quality,
            PhotoFlashReport(PhotoFlashSelection(), 1, 0, null, null, null, null, timestamp),
            listOf(StillImagePayload(kind, ByteArray(bytes) { index.toByte() },
                report?.resultWidth ?: width, report?.resultHeight ?: height, report)), aspect)
    }
    private fun frame(index: Int, capture: CapturedStill = capture(index)) = BurstFrame(index, capture, 1_000_000L + index, 100 + index)
    private fun burst(count: Int = 3, frames: List<BurstFrame> = (0 until count).map(::frame),
        quality: Int = 95, aspect: PhotoAspectSelection = PhotoAspectSelection()) = CapturedBurst(10, count, frames, quality, aspect)

    @Test fun everySupportedCountPreservesAllOrderedFrames() {
        for (count in 3..10) {
            val result = burst(count)
            assertEquals(count, result.requestedCount)
            assertEquals((0 until count).toList(), result.frames.map { it.index })
            assertEquals((0 until count).map { 100L + it }, result.frames.map { it.capture.sensorTimestampNs })
        }
    }
    @Test fun unsupportedCountsAreRejectedInsteadOfClamped() {
        for (count in listOf(-1, 0, 1, 2, 11, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { CapturedBurst(1, count, emptyList(), 95, PhotoAspectSelection()) }
    }
    @Test fun partialOrExcessFramesCannotConstructACompleteBurst() {
        val frames = (0 until 3).map(::frame)
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames.take(2)) }
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames + frame(3)) }
        assertThrows(IllegalArgumentException::class.java) { burst(frames = emptyList()) }
    }
    @Test fun duplicatesAndOutOfOrderIndicesAreRejected() {
        val frames = (0 until 3).map(::frame)
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames.reversed()) }
        assertThrows(IllegalArgumentException::class.java) { burst(frames = listOf(frames[0], frames[0], frames[2])) }
        assertThrows(IllegalArgumentException::class.java) { frame(0).copy(index = -1) }
        assertThrows(IllegalArgumentException::class.java) { frame(0).copy(index = 10) }
    }
    @Test fun aUniqueTimestampAndCaptureIdAreRequiredForEveryJpeg() {
        val frames = (0 until 3).map(::frame).toMutableList()
        frames[1] = frame(1, capture(1, id = 1))
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames) }
        frames[1] = frame(1, capture(1, timestamp = 100))
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames) }
        frames[1] = frame(1, capture(1, timestamp = 99))
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames) }
    }
    @Test fun everyFrameRetainsTheFrozenQuality() {
        val frames = (0 until 3).map(::frame).toMutableList()
        frames[1] = frame(1, capture(1, quality = 73))
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames) }
        assertThrows(IllegalArgumentException::class.java) { burst(quality = 0) }
        assertThrows(IllegalArgumentException::class.java) { burst(quality = 101) }
    }
    @Test fun cropSelectionAndPerImageReportAreRetainedInTheTypedGroup() {
        val aspect = PhotoAspectSelection(true, 1, 1)
        val frames = (0 until 3).map { frame(it, capture(it, aspect = aspect)) }
        val result = burst(frames = frames, aspect = aspect)
        result.frames.forEach {
            assertEquals(aspect, it.capture.aspectSelection)
            assertEquals(PhotoCropRect(80, 0, 480, 480), it.capture.images.single().aspectReport?.crop)
            assertEquals(0, it.capture.orientationDegrees)
        }
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames) }
    }
    @Test fun changesToOneFramesAspectCannotSilentlyChangeTheGroupIntent() {
        val aspect = PhotoAspectSelection(true, 1, 1)
        val frames = (0 until 3).map { frame(it, capture(it, aspect = aspect)) }.toMutableList()
        frames[1] = frame(1, capture(1, aspect = PhotoAspectSelection(true, 4, 3)))
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames, aspect = aspect) }
    }
    @Test fun frameOrientationAndGeometryAreStableAcrossTheSequence() {
        val frames = (0 until 3).map(::frame).toMutableList()
        frames[1] = frame(1, capture(1, orientation = 0))
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames) }
        frames[1] = frame(1, capture(1, width = 320, height = 240))
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames) }
    }
    @Test fun rawOrHeicCannotMasqueradeAsAJpegBurst() {
        for (kind in listOf(StillImageKind.HEIC, StillImageKind.DNG))
            assertThrows(IllegalArgumentException::class.java) { frame(0, capture(0, kind = kind)) }
    }
    @Test fun reportedExposureRemainsPerFrameAndUnknownIsNotInvented() {
        val frames = listOf(frame(0).copy(exposureTimeNs = null, sensitivityIso = null), frame(1), frame(2))
        val result = burst(frames = frames)
        assertNull(result.frames.first().exposureTimeNs)
        assertNull(result.frames.first().sensitivityIso)
        assertEquals(1_000_002L, result.frames.last().exposureTimeNs)
        assertEquals(102, result.frames.last().sensitivityIso)
        assertThrows(IllegalArgumentException::class.java) { frame(0).copy(exposureTimeNs = 0) }
        assertThrows(IllegalArgumentException::class.java) { frame(0).copy(sensitivityIso = -1) }
    }
    @Test fun sourceFrameCollectionCannotMutateAnAlreadyCompletedGroup() {
        val source = (0 until 3).map(::frame).toMutableList()
        val result = burst(frames = source)
        source.clear()
        assertEquals(3, result.frames.size)
        assertThrows(UnsupportedOperationException::class.java) { (result.frames as MutableList<BurstFrame>).clear() }
        val pixels = result.frames[1].capture.images.single().bytes
        pixels[0] = 99
        assertEquals(1.toByte(), result.frames[1].capture.images.single().bytes[0])
    }
    @Test fun aggregateEncodedBudgetIsCheckedWithoutAcceptingPartialFrames() {
        val frames = (0 until 3).map { frame(it, capture(it, bytes = 11 * 1024 * 1024)) }
        assertThrows(IllegalArgumentException::class.java) { burst(frames = frames) }
    }
    @Test fun exactEncodedBudgetIsAcceptedAndDefaultImagesRemainUncropped() {
        val frames = (0 until 3).map { frame(it, capture(it, bytes = if (it == 0) CapturedBurst.MAX_ENCODED_BYTES - 2 else 1)) }
        val result = burst(frames = frames)
        assertEquals(CapturedBurst.MAX_ENCODED_BYTES.toLong(), result.frames.sumOf { it.capture.images.single().byteCount.toLong() })
        result.frames.forEach { assertNull(it.capture.images.single().aspectReport) }
    }
}
