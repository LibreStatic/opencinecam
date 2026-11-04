/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageBenchmarkTest {
    @Test
    fun runsSequentialWindowsAndCalculatesQualificationMargin() {
        val events = mutableListOf<String>()
        val clock = FakeClock()
        val first = FakeDestination("first", events, clock)
        val second = FakeDestination("second", events, clock)
        val request = StorageBenchmarkRequest(
            benchmarkId = "bench-1",
            windowCount = 2,
            requiredBytesPerSecond = 64_000,
            chunkSizeBytes = 64,
        )

        val report = StorageBenchmarkRunner(clock).run(request, listOf(first, second))

        assertEquals(listOf("open:first", "close:first", "cleanup:first", "open:second"), events.take(4))
        assertEquals(listOf("first", "second"), report.results.map { it.destinationId })
        assertTrue(report.results.all { it.outcome == StorageBenchmarkOutcome.QUALIFIED })
        assertEquals(2, report.results.first().windows.size)
        assertEquals(1_000L, report.results.first().windows.first().durationMillis)
        assertTrue(report.results.first().qualificationMarginBytesPerSecond!! >= 0)
        assertTrue(events.containsAll(listOf("close:second", "cleanup:second")))
    }

    @Test
    fun unsupportedDestinationIsExplicitAndCleaned() {
        val destination = FakeDestination("unsupported", mutableListOf(), FakeClock(), unsupported = true)

        val result = StorageBenchmarkRunner(FakeClock()).run(
            StorageBenchmarkRequest("bench-unsupported", requiredBytesPerSecond = 1),
            listOf(destination),
        ).results.single()

        assertEquals(StorageBenchmarkOutcome.UNSUPPORTED, result.outcome)
        assertEquals(FailureCode.UNSUPPORTED_CAPABILITY, result.failure?.code)
        assertTrue(destination.cleaned)
    }

    @Test
    fun cancellationStopsWritesAndRunsCleanup() {
        val destination = FakeDestination("cancel", mutableListOf(), FakeClock())
        val cancellation = BenchmarkCancellationSource().also { it.cancel() }

        val result = StorageBenchmarkRunner(FakeClock()).run(
            StorageBenchmarkRequest("bench-cancel", requiredBytesPerSecond = 1),
            listOf(destination),
            cancellation,
        ).results.single()

        assertEquals(StorageBenchmarkOutcome.CANCELLED, result.outcome)
        assertEquals(FailureCode.CANCELLATION, result.failure?.code)
        assertEquals(0, result.bytesWritten)
        assertTrue(destination.cleaned)
    }

    @Test
    fun duplicateCommandIsRejectedWhileOriginalIsRunning() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val destination = object : BenchmarkDestination {
            override val id = "blocking"
            override fun openSink(): BenchmarkSink {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                return FakeSink()
            }

            override fun cleanup() = Unit
        }
        val runner = StorageBenchmarkRunner(FakeClock())
        val request = StorageBenchmarkRequest("bench-duplicate", requiredBytesPerSecond = 1, windowDurationMillis = 1)
        val original = Thread { runner.run(request, listOf(destination)) }
        original.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val duplicate = runner.run(request, listOf(destination)).results.single()
        release.countDown()
        original.join(2_000)

        assertEquals(FailureCode.DUPLICATE_COMMAND, duplicate.failure?.code)
    }

    @Test
    fun resultPersistsCanonicalOneSecondWindows() {
        val output = ByteArrayOutputStream()
        val destination = FakeDestination("persist", mutableListOf(), FakeClock())
        val request = StorageBenchmarkRequest("bench-persist", requiredBytesPerSecond = 1, chunkSizeBytes = 64)
        val report = StorageBenchmarkRunner(
            FakeClock(),
            JsonBenchmarkResultRepository(output) { Instant.parse("2026-10-19T10:00:00Z") },
        ).run(request, listOf(destination))

        val encoded = output.toString(Charsets.UTF_8)
        val document = decodeCanonical<BenchmarkResultDocument>(encoded)
        assertEquals(report.results.single().bytesWritten, document.bytesWritten)
        assertEquals(1, document.windows.size)
        assertEquals("pass", document.outcome)
        assertEquals("QUALIFIED", document.outcomeDetail)
        assertNotNull(document.requiredBytesPerSecond)
        assertEquals(report.results.single().qualificationMarginBytesPerSecond, document.qualificationMarginBytesPerSecond)
        assertEquals("2026-10-19T10:00:00Z", document.createdAt)
        assertEquals(1, Instant.parse(document.createdAt).epochSecond.compareTo(0))
    }

    @Test
    fun outcomesMapOntoTheSchemaVocabulary() {
        assertEquals(
            listOf("pass", "fail", "cancelled", "error", "error"),
            listOf(
                StorageBenchmarkOutcome.QUALIFIED,
                StorageBenchmarkOutcome.NOT_QUALIFIED,
                StorageBenchmarkOutcome.CANCELLED,
                StorageBenchmarkOutcome.UNSUPPORTED,
                StorageBenchmarkOutcome.FAILED,
            ).map { it.schemaOutcome() },
        )
    }

    @Test
    fun byteBufferSinkGetsOneReusedDirectBufferAndPeriodicSyncs() {
        val sink = BufferSink()
        val result = StorageBenchmarkRunner(FakeClock()).run(
            StorageBenchmarkRequest("bench-buffer", requiredBytesPerSecond = 1, chunkSizeBytes = 64, syncIntervalBytes = 256),
            listOf(SinkDestination("buffer", sink)),
        ).results.single()

        assertEquals(StorageBenchmarkOutcome.QUALIFIED, result.outcome)
        assertEquals(0, sink.arrayWrites)
        assertTrue(sink.bufferWrites > 0)
        assertTrue(sink.allDirect)
        assertEquals(1, sink.distinctBuffers.size)
        assertEquals(sink.bufferWrites * 64L, result.bytesWritten)
        // One sync per four 64-byte chunks.
        assertEquals(sink.bufferWrites / 4, sink.syncs)
        assertEquals(result.bytesWritten, result.windows.sumOf { it.bytesWritten })
    }

    @Test
    fun arraySinkKeepsTheHeapPathAndSyncsOnTheSameCadence() {
        val sink = ArraySink()
        StorageBenchmarkRunner(FakeClock()).run(
            StorageBenchmarkRequest("bench-array", requiredBytesPerSecond = 1, chunkSizeBytes = 100, syncIntervalBytes = 250),
            listOf(SinkDestination("array", sink)),
        )

        assertTrue(sink.writes > 0)
        // 100-byte chunks against a 250-byte interval: a sync after every third chunk.
        assertEquals(sink.writes / 3, sink.syncs)
    }

    @Test
    fun zeroSyncIntervalNeverSyncs() {
        val sink = BufferSink()
        StorageBenchmarkRunner(FakeClock()).run(
            StorageBenchmarkRequest("bench-nosync", requiredBytesPerSecond = 1, chunkSizeBytes = 64, syncIntervalBytes = 0),
            listOf(SinkDestination("nosync", sink)),
        )

        assertTrue(sink.bufferWrites > 0)
        assertEquals(0, sink.syncs)
    }

    @Test
    fun defaultBufferWriteFallsBackToTheArrayPath() {
        val sink = ArraySink()
        assertFalse(sink.supportsByteBuffer)
        val buffer = ByteBuffer.allocateDirect(8).apply { put(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)); flip(); position(2) }
        sink.write(buffer)
        assertEquals(6L, sink.bytesWritten)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8), sink.last)
        assertFalse(buffer.hasRemaining())
        sink.sync()
        assertEquals(1, sink.flushes)
    }

    @Test
    fun defaultSyncIntervalIs256MiB() {
        assertEquals(256L * 1024 * 1024, StorageBenchmarkRequest("bench-default", requiredBytesPerSecond = 1).syncIntervalBytes)
    }

    private class SinkDestination(override val id: String, private val sink: BenchmarkSink) : BenchmarkDestination {
        override fun openSink(): BenchmarkSink = sink
        override fun cleanup() = Unit
    }

    private class BufferSink : BenchmarkSink {
        var arrayWrites = 0
        var bufferWrites = 0
        var syncs = 0
        var allDirect = true
        val distinctBuffers = mutableSetOf<Int>()
        private var count = 0L
        override val supportsByteBuffer = true
        override fun write(bytes: ByteArray) {
            arrayWrites++
            count += bytes.size
        }

        override fun write(buffer: ByteBuffer) {
            bufferWrites++
            allDirect = allDirect && buffer.isDirect
            distinctBuffers += System.identityHashCode(buffer)
            count += buffer.remaining()
            buffer.position(buffer.limit())
        }

        override fun sync() {
            syncs++
        }

        override fun flush() = Unit
        override val bytesWritten: Long get() = count
        override fun close() = Unit
    }

    private class ArraySink : BenchmarkSink {
        var writes = 0
        var syncs = 0
        var flushes = 0
        var last: ByteArray? = null
        private var count = 0L
        override fun write(bytes: ByteArray) {
            writes++
            last = bytes.copyOf()
            count += bytes.size
        }

        override fun sync() {
            syncs++
            super.sync()
        }

        override fun flush() {
            flushes++
        }

        override val bytesWritten: Long get() = count
        override fun close() = Unit
    }

    private class FakeClock : BenchmarkClock {
        private var now = 0L
        override fun nanoTime(): Long = now
        override fun sleepNanos(nanos: Long) {
            now += nanos.coerceAtLeast(1L)
        }
    }

    private class FakeDestination(
        override val id: String,
        private val events: MutableList<String>,
        private val clock: BenchmarkClock,
        private val unsupported: Boolean = false,
    ) : BenchmarkDestination {
        var cleaned = false

        override fun openSink(): BenchmarkSink {
            events += "open:$id"
            if (unsupported) throw UnsupportedOperationException("unsupported")
            return object : FakeSink(clock) {
                override fun close() {
                    events += "close:$id"
                    super.close()
                }
            }
        }

        override fun cleanup() {
            cleaned = true
            events += "cleanup:$id"
        }
    }

    private open class FakeSink(private val clock: BenchmarkClock? = null) : BenchmarkSink {
        private var count = 0L
        override fun write(bytes: ByteArray) {
            count += bytes.size
        }

        override fun flush() = Unit
        override val bytesWritten: Long get() = count
        override fun close() = Unit
    }
}
