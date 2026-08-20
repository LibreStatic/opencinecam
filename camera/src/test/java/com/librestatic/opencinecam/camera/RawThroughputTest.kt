/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawThroughputTest {
    @Test
    fun qualifiesSixtySecondWindowWithP01MarginAndZeroLoss() {
        val samples = listOf(
            RawThroughputSample(1000, 1_300_000, 0),
            RawThroughputSample(1000, 1_260_000, 0),
            RawThroughputSample(1000, 1_280_000, 0),
        )
        val result = RawThroughputBenchmark.qualify(60, 1_000_000, samples)
        assertEquals(RawBenchmarkStatus.PASS, result.status)
        assertEquals(1_260_000L, result.p01BytesPerSecond)
        assertEquals(1_250_000L, result.requiredBytesPerSecond)
        assertEquals(0, result.droppedFrames)
    }

    @Test
    fun lossAndShortWindowFailWhileUnknownStaysDistinct() {
        val sample = RawThroughputSample(1000, 1_000, 1)
        val loss = RawThroughputBenchmark.qualify(60, 1_000, listOf(sample))
        assertEquals(RawBenchmarkStatus.FAIL, loss.status)
        assertEquals("raw-frame-loss", loss.reasonCode)
        val short = RawThroughputBenchmark.qualify(10, 1_000, listOf(sample.copy(droppedFrames = 0)))
        assertEquals(RawBenchmarkStatus.FAIL, short.status)
        val unknown = RawThroughputBenchmark.qualify(60, null, listOf(sample))
        assertEquals(RawBenchmarkStatus.UNKNOWN, unknown.status)
        assertNull(unknown.p01BytesPerSecond)
    }

    @Test
    fun benchmarkLifecycleExposesCancellationDuplicateAndCleanup() {
        val session = RawBenchmarkSession()
        val sample = listOf(RawThroughputSample(1000, 1000, 0))
        assertEquals(RawBenchmarkCommandStatus.CANCELLED, session.cancel("cancel"))
        assertEquals(RawBenchmarkCommandStatus.CANCELLED, session.run("cancel", 60, 1000, sample))
        assertEquals(RawBenchmarkCommandStatus.STARTED, session.run("one", 60, 1000, sample))
        assertEquals(RawBenchmarkCommandStatus.DUPLICATE, session.run("one", 60, 1000, sample))
        assertTrue(session.result("one") != null)
        session.close()
        assertEquals(RawBenchmarkCommandStatus.CLOSED, session.run("two", 60, 1000, sample))
    }
}
