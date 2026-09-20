/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class SubjectPreviewTest {
    @Test fun newestFrameWinsButSupersededFenceMustBeReturnedBeforeReuse() {
        val queue = LatestFrameExchange<String>()
        val a = queue.acquire()!!
        queue.publish(a, "old-fence")
        val b = queue.acquire()!!
        queue.publish(b, "new-fence")
        assertEquals(listOf(a to "old-fence"), queue.returned())
        assertEquals(b to "new-fence", queue.takeLatest())
        assertNull(queue.takeLatest())
        val c = queue.acquire()!!
        assertNull(queue.acquire())
        queue.release(a)
        assertEquals(a, queue.acquire())
        queue.cancelWrite(a)
        queue.cancelWrite(c)
        queue.complete(b, "consumer-fence")
        assertEquals(listOf(b to "consumer-fence"), queue.returned())
    }

    @Test fun stalledConsumerDoesNotGrowTheQueueAcrossTenThousandFrames() {
        val queue = LatestFrameExchange<Int>()
        val stalled = queue.acquire()!!
        queue.publish(stalled, 0)
        assertEquals(stalled to 0, queue.takeLatest())
        repeat(10_000) { frame ->
            queue.returned().forEach { (slot, _) -> queue.release(slot) }
            val slot = queue.acquire()!!
            assertNotEquals(stalled, slot)
            queue.publish(slot, frame + 1)
            assertNull(queue.takeLatest())
            assertTrue(queue.returned().size <= 1)
        }
        queue.complete(stalled, -1)
        assertEquals(10_000, queue.takeLatest()!!.second)
    }

    @Test fun closeRejectsNewFramesAndReturnsInFlightWorkWithoutResurrection() {
        val queue = LatestFrameExchange<Int>()
        val a = queue.acquire()!!
        val b = queue.acquire()!!
        queue.publish(a, 1)
        queue.takeLatest()
        queue.close()
        queue.publish(b, 2)
        queue.complete(a, 3)
        assertNull(queue.acquire())
        assertNull(queue.takeLatest())
        assertEquals(setOf(a to 3, b to 2), queue.returned().toSet())
        queue.returned().forEach { (slot, _) -> queue.release(slot) }
        assertNull(queue.acquire())
    }

    @Test(expected = IllegalStateException::class) fun consumerLeaseCannotBeReusedBeforeItsFenceIsReturned() {
        val queue = LatestFrameExchange<Int>()
        val slot = queue.acquire()!!
        queue.publish(slot, 1)
        queue.takeLatest()
        queue.release(slot)
    }

    @Test fun staleFrozenFutureAndFailedFramesAreNeverLive() {
        assertFalse(SubjectPreviewStatus().isFresh(100))
        val status = SubjectPreviewStatus(sourceReceivedAtMs = 100, submittedAtMs = 150)
        assertTrue(status.isFresh(150))
        assertTrue(status.isFresh(600))
        assertFalse(status.isFresh(601))
        assertFalse(status.isFresh(149))
        assertFalse(status.copy(failure = "disconnected").isFresh(150))
        assertFalse(status.copy(submittedAtMs = 50).isFresh(150))
        assertFalse(status.copy(submittedAtMs = 800).isFresh(800))
    }

    @Test fun optionsRejectUnboundedTransforms() {
        for (rotation in listOf(-90, 45, 360)) assertTrue(runCatching { SubjectPreviewOptions(displayRotationDegrees = rotation) }.isFailure)
        for (squeeze in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0.9f, 3.1f)) {
            assertTrue(runCatching { SubjectPreviewOptions(squeezeFactor = squeeze) }.isFailure)
        }
        assertEquals(270, SubjectPreviewOptions(displayRotationDegrees = 270, mirror = true, squeezeFactor = 2f).displayRotationDegrees)
    }
}
