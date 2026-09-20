/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class AccumulationTest {
    private val red = 0xffff0000.toInt()
    private val green = 0xff00ff00.toInt()
    private val blue = 0xff0000ff.toInt()
    private val black = 0xff000000.toInt()
    private val white = 0xffffffff.toInt()
    private fun accumulated(mode: AccumulationMode, vararg pixels: Int, threshold: Int = 16): Int {
        val accumulator = LinearLightAccumulator(1, 1, AccumulationSelection(mode = mode, starsThreshold = threshold))
        return try { pixels.forEach { accumulator.add(intArrayOf(it)) }; accumulator.pixels().single() }
        finally { accumulator.close() }
    }
    private fun frame(index: Int) = AccumulationFrame(index, index + 1L, 100L + index, 1_000_000L, 100)
    private fun image(kind: StillImageKind = StillImageKind.JPEG, size: Int = 3, width: Int = 640, height: Int = 480) =
        StillImagePayload(kind, ByteArray(size), width, height)
    private fun capture(frames: List<AccumulationFrame> = listOf(frame(0), frame(1)),
        image: StillImagePayload = image(), orientation: Int = 0, quality: Int = 95, user: Boolean = false) =
        CapturedAccumulation(1, AccumulationSelection(), frames, image, orientation, quality, user)

    @Test fun defaultsAndEveryDeclaredModeAreExplicit() {
        assertEquals(AccumulationSelection(AccumulationMode.LIGHT, 10_000, 250, 2048, 16), AccumulationSelection())
        assertEquals(setOf("LIGHT", "WATER", "STARS", "BULB"), AccumulationMode.entries.map { it.name }.toSet())
    }
    @Test fun durationIntervalGeometryAndThresholdAreBounded() {
        for (duration in listOf(999L, 300_001L, Long.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { AccumulationSelection(durationMs = duration) }
        for (interval in listOf(99L, 10_001L, Long.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { AccumulationSelection(intervalMs = interval) }
        assertThrows(IllegalArgumentException::class.java) { AccumulationSelection(durationMs = 1_000, intervalMs = 501) }
        for (edge in listOf(0, 719, 721, 4096)) assertThrows(IllegalArgumentException::class.java) { AccumulationSelection(maxEdge = edge) }
        for (threshold in listOf(-1, 256)) assertThrows(IllegalArgumentException::class.java) { AccumulationSelection(starsThreshold = threshold) }
    }
    @Test fun exactSelectionBoundariesAreAccepted() {
        for (edge in listOf(720, 1080, 2048)) {
            AccumulationSelection(durationMs = 1_000, intervalMs = 500, maxEdge = edge, starsThreshold = 0)
            AccumulationSelection(durationMs = 300_000, intervalMs = 10_000, maxEdge = edge, starsThreshold = 255)
        }
    }
    @Test fun lightCombinesChannelMaximaWhereStarsPreservesTheWholeWinningRgb() {
        assertEquals(0xffffff00.toInt(), accumulated(AccumulationMode.LIGHT, red, green))
        assertEquals(green, accumulated(AccumulationMode.STARS, red, green))
    }
    @Test fun waterAveragesInLinearLightRatherThanEncodedGamma() {
        val result = accumulated(AccumulationMode.WATER, black, white)
        assertEquals(188, result and 255)
        assertEquals(188, (result ushr 8) and 255)
        assertEquals(188, (result ushr 16) and 255)
        assertNotEquals(128, result and 255)
    }
    @Test fun bulbSumsLinearLightAndClipsInsteadOfAveragingOrTakingMax() {
        val half = 0xff808080.toInt()
        assertEquals(128, accumulated(AccumulationMode.LIGHT, half, half) and 255)
        assertEquals(128, accumulated(AccumulationMode.WATER, half, half) and 255)
        assertEquals(176, accumulated(AccumulationMode.BULB, half, half) and 255)
        assertEquals(white, accumulated(AccumulationMode.BULB, white, white, white))
    }
    @Test fun starsRetainsTheFirstFrameAsBackgroundEvenBelowThreshold() {
        assertEquals(blue, accumulated(AccumulationMode.STARS, blue, black, threshold = 255))
        assertEquals(red, accumulated(AccumulationMode.STARS, red, green, threshold = 255))
    }
    @Test fun starsThresholdIsLinearLuminanceAndComparisonIsStrict() {
        assertEquals(black, accumulated(AccumulationMode.STARS, black, green, threshold = 183))
        assertEquals(green, accumulated(AccumulationMode.STARS, black, green, threshold = 182))
        assertEquals(black, accumulated(AccumulationMode.STARS, black, black, threshold = 0))
        assertEquals(white, accumulated(AccumulationMode.STARS, black, white, threshold = 254))
        assertEquals(black, accumulated(AccumulationMode.STARS, black, white, threshold = 255))
    }
    @Test fun waterWeightsEveryFrameEquallyWithoutRetainingPreviousInputs() {
        val input = intArrayOf(white)
        val accumulator = LinearLightAccumulator(1, 1, AccumulationSelection(mode = AccumulationMode.WATER))
        accumulator.add(input)
        input[0] = black
        accumulator.add(input)
        accumulator.add(input)
        assertEquals(156, accumulator.pixels().single() and 255)
        assertEquals(3, accumulator.frameCount)
        accumulator.close()
    }
    @Test fun channelExtremaStayBoundedOverTheMaximumFrameCount() {
        val accumulator = LinearLightAccumulator(1, 1, AccumulationSelection(mode = AccumulationMode.BULB))
        repeat(CapturedAccumulation.MAX_FRAMES) { accumulator.add(intArrayOf(white)) }
        assertEquals(white, accumulator.pixels().single())
        assertThrows(IllegalStateException::class.java) { accumulator.add(intArrayOf(white)) }
        accumulator.close()
    }
    @Test fun finiteInputsAlwaysEncodeWithOpaqueAlphaAndRoundTripSrgb() {
        for (value in 0..255) {
            val color = (value shl 16) or (value shl 8) or value
            val result = accumulated(AccumulationMode.LIGHT, color, color)
            assertEquals((255 shl 24) or color, result)
        }
    }
    @Test fun noResultIsAvailableBeforeTwoWholeFrames() {
        val accumulator = LinearLightAccumulator(1, 1, AccumulationSelection())
        assertThrows(IllegalStateException::class.java) { accumulator.pixels() }
        accumulator.add(intArrayOf(white))
        assertThrows(IllegalStateException::class.java) { accumulator.pixels() }
        accumulator.add(intArrayOf(white))
        assertEquals(white, accumulator.pixels().single())
    }
    @Test fun cancellationDuringAnInputPoisonsPartialStateInsteadOfPublishingIt() {
        val accumulator = LinearLightAccumulator(2048, 1, AccumulationSelection())
        accumulator.add(IntArray(2048) { black })
        var checks = 0
        assertThrows(CancellationException::class.java) {
            accumulator.add(IntArray(2048) { white }) { if (++checks == 3) throw CancellationException() }
        }
        assertEquals(1, accumulator.frameCount)
        assertThrows(IllegalStateException::class.java) { accumulator.pixels() }
        assertThrows(IllegalStateException::class.java) { accumulator.add(IntArray(2048)) }
        accumulator.close()
    }
    @Test fun outputCancellationAndClosureNeverExposePartialArrays() {
        val accumulator = LinearLightAccumulator(2048, 1, AccumulationSelection())
        repeat(2) { accumulator.add(IntArray(2048)) }
        var checks = 0
        assertThrows(CancellationException::class.java) { accumulator.pixels { if (++checks == 3) throw CancellationException() } }
        accumulator.close()
        accumulator.close()
        assertThrows(IllegalStateException::class.java) { accumulator.pixels() }
        assertThrows(IllegalStateException::class.java) { accumulator.add(IntArray(2048)) }
    }
    @Test fun dimensionsAndBufferLengthsRejectBeforeLargeAllocation() {
        assertThrows(IllegalArgumentException::class.java) { LinearLightAccumulator(Int.MAX_VALUE, 2, AccumulationSelection()) }
        assertThrows(IllegalArgumentException::class.java) { LinearLightAccumulator(0, 1, AccumulationSelection()) }
        assertThrows(IllegalArgumentException::class.java) { LinearLightAccumulator(721, 1, AccumulationSelection(maxEdge = 720)) }
        assertThrows(IllegalArgumentException::class.java) { LinearLightAccumulator(2, 2, AccumulationSelection()).add(IntArray(3)) }
    }
    @Test fun decoderSampleNeverUpscalesAndHonorsNonPowerOfTwoEdges() {
        assertEquals(1, accumulationSampleSize(640, 480, 720))
        assertEquals(2, accumulationSampleSize(4032, 3024, 2048))
        assertEquals(4, accumulationSampleSize(4032, 3024, 1080))
        assertEquals(8, accumulationSampleSize(4032, 3024, 720))
        assertEquals(2, accumulationSampleSize(2049, 1, 2048))
        assertThrows(IllegalArgumentException::class.java) { accumulationSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 2048) }
        assertThrows(IllegalArgumentException::class.java) { accumulationSampleSize(0, 480, 720) }
    }
    @Test fun orientationTransformsCoverEveryExifVariantAndPreservePixelIdentity() {
        val pixels = intArrayOf(1, 2, 3, 4, 5, 6)
        val expected = listOf(pixels, intArrayOf(3, 2, 1, 6, 5, 4), intArrayOf(6, 5, 4, 3, 2, 1),
            intArrayOf(4, 5, 6, 1, 2, 3), intArrayOf(1, 4, 2, 5, 3, 6), intArrayOf(4, 1, 5, 2, 6, 3),
            intArrayOf(6, 3, 5, 2, 4, 1), intArrayOf(3, 6, 2, 5, 1, 4))
        for (orientation in 1..8) assertArrayEquals(expected[orientation - 1], orientAccumulationPixels(pixels, 3, 2, orientation))
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6), pixels)
    }
    @Test fun orientationRotationCancelsBeforeReturningAnyPartiallyMovedPixels() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            orientAccumulationPixels(IntArray(2048), 2048, 1, 6) { if (++checks == 3) throw CancellationException() }
        }
        assertThrows(IllegalArgumentException::class.java) { orientAccumulationPixels(IntArray(1), 1, 1, 9) }
    }
    @Test fun captureRequiresTwoOrderedUniqueMetadataEntries() {
        assertThrows(IllegalArgumentException::class.java) { capture(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { capture(listOf(frame(0))) }
        assertThrows(IllegalArgumentException::class.java) { capture(listOf(frame(1), frame(0))) }
        assertThrows(IllegalArgumentException::class.java) { capture(listOf(frame(0), frame(1).copy(captureId = 1))) }
        assertThrows(IllegalArgumentException::class.java) { capture(listOf(frame(0), frame(1).copy(sensorTimestampNs = 100))) }
        assertEquals(2, capture().frames.size)
    }
    @Test fun sensorFieldsRemainObservedOrUnknownRatherThanInventedExposureLocks() {
        val result = capture(listOf(frame(0).copy(exposureTimeNs = null, sensitivityIso = null), frame(1)))
        assertNull(result.frames.first().exposureTimeNs)
        assertNull(result.frames.first().sensitivityIso)
        assertEquals(1_000_000L, result.frames.last().exposureTimeNs)
        assertThrows(IllegalArgumentException::class.java) { frame(0).copy(exposureTimeNs = 0) }
        assertThrows(IllegalArgumentException::class.java) { frame(0).copy(sensitivityIso = -1) }
    }
    @Test fun captureMetadataIsDefensiveAndManualFinishIsExplicit() {
        val frames = mutableListOf(frame(0), frame(1))
        val result = capture(frames, user = true)
        frames.clear()
        assertTrue(result.completedByUser)
        assertFalse(capture().completedByUser)
        assertEquals(2, result.frames.size)
        assertThrows(UnsupportedOperationException::class.java) { (result.frames as MutableList<AccumulationFrame>).clear() }
    }
    @Test fun jpegOutputMustBePhysicallyOrientedBoundedAndHaveAValidQuality() {
        for (kind in listOf(StillImageKind.DNG, StillImageKind.HEIC)) assertThrows(IllegalArgumentException::class.java) { capture(image = image(kind)) }
        assertThrows(IllegalArgumentException::class.java) { capture(image = image(width = 2049)) }
        assertThrows(IllegalArgumentException::class.java) { capture(orientation = 90) }
        assertThrows(IllegalArgumentException::class.java) { capture(quality = 0) }
        assertThrows(IllegalArgumentException::class.java) { capture(quality = 101) }
        assertThrows(IllegalArgumentException::class.java) { capture(image = image(size = CapturedAccumulation.MAX_ENCODED_BYTES + 1)) }
    }
    @Test fun metadataAndEncodedBoundariesAreAcceptedExactly() {
        assertEquals(4096, capture((0 until 4096).map(::frame)).frames.size)
        assertThrows(IllegalArgumentException::class.java) { frame(4096) }
        assertEquals(CapturedAccumulation.MAX_ENCODED_BYTES, capture(image = image(size = CapturedAccumulation.MAX_ENCODED_BYTES)).image.byteCount)
    }
}
