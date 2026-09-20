/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class StillPhotoTest {
    private fun payload(kind: StillImageKind = StillImageKind.JPEG) = StillImagePayload(kind, byteArrayOf(1, 2, 3), 640, 480)
    private fun report(timestamp: Long = 99) = PhotoFlashReport(PhotoFlashSelection(), 1, 0, null, null, null, null, timestamp)
    private fun capture(images: List<StillImagePayload> = listOf(payload()), id: Long = 1, timestamp: Long = 99,
        orientation: Int = 90, quality: Int = 95, report: PhotoFlashReport = report(timestamp)) =
        CapturedStill(id, timestamp, orientation, quality, report, images)

    @Test fun formatsDeclareExactlyTheirRequiredOutputs() {
        assertEquals(setOf(StillImageKind.JPEG), StillPhotoFormat.JPEG.requiredKinds)
        assertEquals(setOf(StillImageKind.HEIC), StillPhotoFormat.HEIC.requiredKinds)
        assertEquals(setOf(StillImageKind.DNG), StillPhotoFormat.DNG.requiredKinds)
        assertEquals(setOf(StillImageKind.JPEG, StillImageKind.DNG), StillPhotoFormat.RAW_JPEG.requiredKinds)
    }
    @Test fun jpegAndRawAreOneCompleteFrameOnlyWithBothExactTimestamps() {
        assertFalse(StillPhotoFormat.RAW_JPEG.matchesCompleteFrame(99, emptyMap()))
        assertFalse(StillPhotoFormat.RAW_JPEG.matchesCompleteFrame(99, mapOf(StillImageKind.JPEG to 99L)))
        assertFalse(StillPhotoFormat.RAW_JPEG.matchesCompleteFrame(99, mapOf(StillImageKind.DNG to 99L)))
        assertTrue(StillPhotoFormat.RAW_JPEG.matchesCompleteFrame(99, mapOf(StillImageKind.JPEG to 99L, StillImageKind.DNG to 99L)))
    }
    @Test fun oldOrLaterRawImageNeverCompletesTheJpegExposure() {
        for (rawTimestamp in listOf(98L, 100L)) {
            assertFalse(StillPhotoFormat.RAW_JPEG.matchesCompleteFrame(99, mapOf(StillImageKind.JPEG to 99L, StillImageKind.DNG to rawTimestamp)))
        }
    }
    @Test fun jpegCannotMasqueradeAsHeicOrCompleteAHeicFrame() {
        assertFalse(StillPhotoFormat.HEIC.matchesCompleteFrame(99, mapOf(StillImageKind.JPEG to 99L)))
        assertTrue(StillPhotoFormat.HEIC.matchesCompleteFrame(99, mapOf(StillImageKind.HEIC to 99L)))
        assertFalse(StillPhotoFormat.RAW_JPEG.matchesCompleteFrame(99, mapOf(StillImageKind.HEIC to 99L, StillImageKind.DNG to 99L)))
    }
    @Test fun extraPartsAndMissingResultTimestampNeverQualify() {
        assertFalse(StillPhotoFormat.JPEG.matchesCompleteFrame(99, mapOf(StillImageKind.JPEG to 99L, StillImageKind.DNG to 99L)))
        assertFalse(StillPhotoFormat.JPEG.matchesCompleteFrame(0, mapOf(StillImageKind.JPEG to 0L)))
        assertFalse(StillPhotoFormat.DNG.matchesCompleteFrame(-1, mapOf(StillImageKind.DNG to -1L)))
    }
    @Test fun outputArrivalOrderDoesNotAffectExactTimestampAcceptance() {
        assertTrue(StillPhotoFormat.RAW_JPEG.matchesCompleteFrame(99, linkedMapOf(StillImageKind.DNG to 99L, StillImageKind.JPEG to 99L)))
        assertTrue(StillPhotoFormat.RAW_JPEG.matchesCompleteFrame(99, linkedMapOf(StillImageKind.JPEG to 99L, StillImageKind.DNG to 99L)))
    }
    @Test fun callerCannotMutateCapturedBytesThroughEitherArrayReference() {
        val source = byteArrayOf(1, 2, 3)
        val image = StillImagePayload(StillImageKind.JPEG, source, 640, 480)
        source[0] = 7
        val returned = image.bytes
        returned[1] = 8
        assertArrayEquals(byteArrayOf(1, 2, 3), image.bytes)
    }
    @Test fun capturedImageCollectionIsCopiedAndUnmodifiable() {
        val image = payload()
        val source = mutableListOf(image)
        val result = capture(source)
        source.clear()
        assertEquals(1, result.images.size)
        assertSame(image, result.images.single())
        assertThrows(UnsupportedOperationException::class.java) { (result.images as MutableList<StillImagePayload>).clear() }
    }
    @Test fun emptyBytesAndNonpositiveImageGeometryAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { StillImagePayload(StillImageKind.JPEG, byteArrayOf(), 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { StillImagePayload(StillImageKind.DNG, byteArrayOf(1), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { StillImagePayload(StillImageKind.HEIC, byteArrayOf(1), 1, -1) }
    }
    @Test fun compressedAndRawEncodedByteBudgetsAreExplicit() {
        assertEquals(128 * 1024 * 1024, StillImagePayload.maximumBytes(StillImageKind.JPEG))
        assertEquals(128 * 1024 * 1024, StillImagePayload.maximumBytes(StillImageKind.HEIC))
        assertEquals(256 * 1024 * 1024, StillImagePayload.maximumBytes(StillImageKind.DNG))
    }
    @Test fun positiveCaptureIdentityAndMatchedFlashResultAreRequired() {
        assertThrows(IllegalArgumentException::class.java) { capture(id = 0) }
        assertThrows(IllegalArgumentException::class.java) { capture(timestamp = 0) }
        assertThrows(IllegalArgumentException::class.java) { capture(report = report(100)) }
        assertThrows(IllegalArgumentException::class.java) { capture(report = report().copy(sensorTimestampNs = null)) }
    }
    @Test fun qualityAndOrientationMustBeExactSupportedValues() {
        for (quality in listOf(0, 101)) assertThrows(IllegalArgumentException::class.java) { capture(quality = quality) }
        for (orientation in listOf(-90, 1, 360)) assertThrows(IllegalArgumentException::class.java) { capture(orientation = orientation) }
        for (orientation in listOf(0, 90, 180, 270)) assertEquals(orientation, capture(orientation = orientation).orientationDegrees)
        assertEquals(1, capture(quality = 1).quality)
        assertEquals(100, capture(quality = 100).quality)
    }
    @Test fun duplicateOrMixedUnsupportedOutputSetsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { capture(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { capture(listOf(payload(), payload())) }
        assertThrows(IllegalArgumentException::class.java) { capture(listOf(payload(StillImageKind.HEIC), payload(StillImageKind.DNG))) }
        assertThrows(IllegalArgumentException::class.java) { capture(StillImageKind.entries.map(::payload)) }
    }
    @Test fun independentImagesAndTheRawJpegPairKeepTheirFullMetadata() {
        for (kind in StillImageKind.entries) assertEquals(kind, capture(listOf(payload(kind))).images.single().kind)
        val result = capture(listOf(payload(), payload(StillImageKind.DNG)))
        assertEquals(1L, result.captureId)
        assertEquals(99L, result.sensorTimestampNs)
        assertEquals(listOf(StillImageKind.JPEG, StillImageKind.DNG), result.images.map { it.kind })
        assertEquals(95, result.quality)
        assertEquals(90, result.orientationDegrees)
        assertNull(result.flashReport.reportedFlashState)
    }
    @Test fun handoffReservesBeforeAllocationAndRetainsBudgetWhileQueuedAndClaimed() {
        val budget = StillImageHandoff<String>(10)
        val ticket = requireNotNull(budget.reserve(10))
        assertEquals(10L, budget.usedBytes())
        assertNull(budget.reserve(1))
        assertTrue(budget.publish(ticket, "frame"))
        assertNull(budget.reserve(1))
        assertEquals("frame", budget.take(ticket))
        assertEquals(10L, budget.usedBytes())
        assertNull(budget.take(ticket))
        budget.release(ticket)
        assertEquals(0L, budget.usedBytes())
    }
    @Test fun closingDiscardedQueuedCallbackDropsItsValueAndReleasesBudget() {
        val budget = StillImageHandoff<String>(10)
        val ticket = requireNotNull(budget.reserve(10))
        assertTrue(budget.publish(ticket, "never executed"))
        budget.cancelAll()
        assertNull(budget.take(ticket))
        assertEquals(0L, budget.usedBytes())
        assertNotNull(budget.reserve(10))
        budget.release(ticket)
        assertEquals(10L, budget.usedBytes())
    }
    @Test fun closeDoesNotReuseBytesWhileAProducerIsStillAllocatingOrCopying() {
        val budget = StillImageHandoff<String>(10)
        val ticket = requireNotNull(budget.reserve(10))
        budget.cancelAll()
        budget.cancelAll()
        assertEquals(10L, budget.usedBytes())
        assertNull(budget.reserve(1))
        assertFalse(budget.publish(ticket, "discard after copy finishes"))
        assertNull(budget.take(ticket))
        assertEquals(0L, budget.usedBytes())
        assertNotNull(budget.reserve(10))
    }
    @Test fun producerExceptionCanRetireCancelledAllocationExactlyOnce() {
        val budget = StillImageHandoff<String>(10)
        val ticket = requireNotNull(budget.reserve(10))
        budget.cancelAll()
        budget.release(ticket)
        budget.release(ticket)
        assertEquals(0L, budget.usedBytes())
        assertFalse(budget.publish(ticket, "late"))
    }
    @Test fun retiredGraphCannotReserveAfterCloseAlreadyDrainedEarlierTickets() {
        val budget = StillImageHandoff<String>(10)
        budget.cancelAll()
        assertNull(budget.reserve(10) { false })
        assertEquals(0L, budget.usedBytes())
        assertNotNull(budget.reserve(10) { true })
    }
    @Test fun handoffRejectsOversizedAllocationWithoutOverflowOrNegativeAccounting() {
        val budget = StillImageHandoff<String>(Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { budget.reserve(0) }
        val ticket = requireNotNull(budget.reserve(Int.MAX_VALUE))
        assertNull(budget.reserve(Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE.toLong(), budget.usedBytes())
        budget.release(ticket)
        assertEquals(0L, budget.usedBytes())
    }
    @Test fun foreignTicketCannotReadOrReleaseAnotherHandoff() {
        val first = StillImageHandoff<String>(10)
        val second = StillImageHandoff<String>(10)
        val ticket = requireNotNull(first.reserve(10))
        assertFalse(second.publish(ticket, "foreign"))
        assertNull(second.take(ticket))
        second.release(ticket)
        assertEquals(10L, first.usedBytes())
        assertEquals(0L, second.usedBytes())
        first.release(ticket)
    }
    @Test fun concurrentProducersCannotOverbookTheOneImageBudget() {
        val budget = StillImageHandoff<String>(10)
        val start = java.util.concurrent.CountDownLatch(1)
        val ready = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val workers = List(2) {
            Thread {
                try {
                    check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val ticket = budget.reserve(10)
                    if (ticket != null) accepted.incrementAndGet()
                    ready.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    ticket?.let(budget::release)
                } catch (problem: Throwable) { failure.set(problem); ready.countDown() }
            }.apply { start() }
        }
        try {
            start.countDown()
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(1, accepted.get())
            assertEquals(10L, budget.usedBytes())
        } finally { release.countDown(); workers.forEach { it.join(5000) } }
        assertTrue(workers.none { it.isAlive })
        assertNull(failure.get())
        assertEquals(0L, budget.usedBytes())
    }

}
