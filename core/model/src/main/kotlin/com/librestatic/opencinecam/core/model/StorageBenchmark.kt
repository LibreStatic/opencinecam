/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** A destination is opened, measured, closed, and cleaned before the next one starts. */
interface BenchmarkDestination {
    val id: String
    fun openSink(): BenchmarkSink
    fun cleanup()
}

interface BenchmarkSink : AutoCloseable {
    fun write(bytes: ByteArray)
    fun flush()
    val bytesWritten: Long

    /**
     * True when [write] with a `ByteBuffer` is the sink's native path (e.g. a `FileChannel`); the
     * runner then writes from one reused direct buffer instead of a heap array.
     */
    val supportsByteBuffer: Boolean
        get() = false

    /** Writes every remaining byte of [buffer]. By default it copies them and calls the array [write]. */
    fun write(buffer: ByteBuffer) {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        write(bytes)
    }

    /**
     * Makes what has been written durable, so the measurement reflects the flash rather than the
     * page cache. By default it only [flush]es.
     */
    fun sync() = flush()
}

fun interface BenchmarkCancellation {
    fun isCancelled(): Boolean
}

object NeverCancelled : BenchmarkCancellation {
    override fun isCancelled(): Boolean = false
}

interface BenchmarkClock {
    fun nanoTime(): Long
    fun sleepNanos(nanos: Long)
}

object SystemBenchmarkClock : BenchmarkClock {
    override fun nanoTime(): Long = System.nanoTime()

    override fun sleepNanos(nanos: Long) {
        if (nanos <= 0) return
        try {
            val millis = nanos / 1_000_000
            val remainder = nanos % 1_000_000
            Thread.sleep(millis, remainder.toInt())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

data class StorageBenchmarkRequest(
    val benchmarkId: String,
    val windowCount: Int = 1,
    val windowDurationMillis: Long = 1_000,
    val requiredBytesPerSecond: Long,
    val chunkSizeBytes: Int = 64 * 1024,
    /** The runner calls [BenchmarkSink.sync] each time this many bytes have been written; 0 never syncs. */
    val syncIntervalBytes: Long = DEFAULT_SYNC_INTERVAL_BYTES,
) {
    init {
        require(benchmarkId.isNotBlank()) { "benchmark ID must not be blank" }
        require(windowCount > 0) { "window count must be positive" }
        require(windowDurationMillis > 0) { "window duration must be positive" }
        require(requiredBytesPerSecond >= 0) { "required throughput must not be negative" }
        require(chunkSizeBytes > 0) { "chunk size must be positive" }
        require(syncIntervalBytes >= 0) { "sync interval must not be negative" }
    }

    companion object {
        /** 256 MiB: measured on the S24, a force this often gives realistic flash rates. */
        const val DEFAULT_SYNC_INTERVAL_BYTES = 256L * 1024 * 1024
    }
}

data class StorageBenchmarkWindow(
    val index: Int,
    val startedAtNanos: Long,
    val durationMillis: Long,
    val bytesWritten: Long,
    val bytesPerSecond: Long,
)

enum class StorageBenchmarkOutcome {
    QUALIFIED,
    NOT_QUALIFIED,
    CANCELLED,
    UNSUPPORTED,
    FAILED,
}

data class StorageBenchmarkResult(
    val benchmarkId: String,
    val destinationId: String,
    val windows: List<StorageBenchmarkWindow>,
    val bytesWritten: Long,
    val requiredBytesPerSecond: Long,
    val qualificationMarginBytesPerSecond: Long?,
    val outcome: StorageBenchmarkOutcome,
    val failure: StableFailure? = null,
)

data class StorageBenchmarkReport(val results: List<StorageBenchmarkResult>)

sealed interface BenchmarkPersistenceOutcome {
    data object Persisted : BenchmarkPersistenceOutcome
    data class Failed(val reason: String) : BenchmarkPersistenceOutcome
}

fun interface BenchmarkResultRepository {
    fun persist(result: StorageBenchmarkResult): BenchmarkPersistenceOutcome
}

/** Runs one-second (or explicitly configured) windows in destination order. */
class StorageBenchmarkRunner(
    private val clock: BenchmarkClock = SystemBenchmarkClock,
    private val repository: BenchmarkResultRepository? = null,
) {
    private val runningIds = mutableSetOf<String>()

    fun run(
        request: StorageBenchmarkRequest,
        destinations: List<BenchmarkDestination>,
        cancellation: BenchmarkCancellation = NeverCancelled,
    ): StorageBenchmarkReport {
        val duplicate = synchronized(this) { !runningIds.add(request.benchmarkId) }
        if (duplicate) {
            val failure = failure(
                request,
                FailureCode.DUPLICATE_COMMAND,
                Recoverability.USER_ACTION,
                "A benchmark with this ID is already running.",
            )
            return StorageBenchmarkReport(
                destinations.map { destination ->
                    StorageBenchmarkResult(
                        request.benchmarkId,
                        destination.id,
                        emptyList(),
                        0,
                        request.requiredBytesPerSecond,
                        null,
                        StorageBenchmarkOutcome.FAILED,
                        failure,
                    )
                },
            )
        }
        try {
            require(destinations.isNotEmpty()) { "at least one destination is required" }
            require(destinations.map { it.id }.distinct().size == destinations.size) {
                "destination IDs must be unique"
            }
            return StorageBenchmarkReport(destinations.map { destination ->
                runDestination(request, destination, cancellation)
            })
        } finally {
            synchronized(this) { runningIds.remove(request.benchmarkId) }
        }
    }

    private fun runDestination(
        request: StorageBenchmarkRequest,
        destination: BenchmarkDestination,
        cancellation: BenchmarkCancellation,
    ): StorageBenchmarkResult {
        var sink: BenchmarkSink? = null
        val windows = mutableListOf<StorageBenchmarkWindow>()
        var outcome = StorageBenchmarkOutcome.FAILED
        var failure: StableFailure? = null
        try {
            sink = destination.openSink()
            val writer = chunkWriter(sink, request)
            repeat(request.windowCount) { index ->
                if (cancellation.isCancelled()) {
                    outcome = StorageBenchmarkOutcome.CANCELLED
                    failure = failure(
                        request,
                        FailureCode.CANCELLATION,
                        Recoverability.CANCELLED,
                        "Storage benchmark was cancelled before completion.",
                    )
                    return@repeat
                }
                val started = clock.nanoTime()
                val deadline = started + request.windowDurationMillis * 1_000_000
                while (clock.nanoTime() < deadline) {
                    if (cancellation.isCancelled()) {
                        outcome = StorageBenchmarkOutcome.CANCELLED
                        failure = failure(
                            request,
                            FailureCode.CANCELLATION,
                            Recoverability.CANCELLED,
                            "Storage benchmark was cancelled before completion.",
                        )
                        return@repeat
                    }
                    writer()
                    val remaining = deadline - clock.nanoTime()
                    clock.sleepNanos(minOf(remaining, 1_000_000))
                }
                sink.flush()
                val durationMillis = request.windowDurationMillis
                val written = sink.bytesWritten - windows.sumOf { it.bytesWritten }
                windows += StorageBenchmarkWindow(
                    index = index,
                    startedAtNanos = started,
                    durationMillis = durationMillis,
                    bytesWritten = written,
                    bytesPerSecond = written * 1_000 / durationMillis,
                )
            }
            if (outcome != StorageBenchmarkOutcome.CANCELLED) {
                val qualified = windows.size == request.windowCount &&
                    windows.all { it.bytesPerSecond >= request.requiredBytesPerSecond }
                outcome = if (qualified) StorageBenchmarkOutcome.QUALIFIED else StorageBenchmarkOutcome.NOT_QUALIFIED
            }
        } catch (_: UnsupportedOperationException) {
            outcome = StorageBenchmarkOutcome.UNSUPPORTED
            failure = failure(
                request,
                FailureCode.UNSUPPORTED_CAPABILITY,
                Recoverability.UNSUPPORTED,
                "The selected storage destination is unsupported.",
            )
        } catch (_: Throwable) {
            outcome = StorageBenchmarkOutcome.FAILED
            failure = failure(
                request,
                FailureCode.STORAGE_WRITE_FAILED,
                Recoverability.RETRYABLE,
                "The storage benchmark could not write its test data.",
            )
        } finally {
            runCatching { sink?.close() }
            runCatching { destination.cleanup() }
        }
        val bytesWritten = sink?.bytesWritten ?: 0
        val measured = windows.minOfOrNull { it.bytesPerSecond }
        val result = StorageBenchmarkResult(
            request.benchmarkId,
            destination.id,
            windows.toList(),
            bytesWritten,
            request.requiredBytesPerSecond,
            measured?.minus(request.requiredBytesPerSecond),
            outcome,
            failure,
        )
        repository?.persist(result)?.let { persistence ->
            if (persistence is BenchmarkPersistenceOutcome.Failed) {
                return result.copy(
                    outcome = StorageBenchmarkOutcome.FAILED,
                    failure = failure(
                        request,
                        FailureCode.STORAGE_WRITE_FAILED,
                        Recoverability.RETRYABLE,
                        "The benchmark result could not be persisted.",
                        mapOf("reason" to persistence.reason),
                    ),
                )
            }
        }
        return result
    }

    /**
     * One chunk per call: from a reused direct buffer when the sink takes one (no heap copy), from a
     * heap array otherwise, with a durable [BenchmarkSink.sync] every [StorageBenchmarkRequest.syncIntervalBytes].
     */
    private fun chunkWriter(sink: BenchmarkSink, request: StorageBenchmarkRequest): () -> Unit {
        val chunkBytes = request.chunkSizeBytes
        val direct = if (sink.supportsByteBuffer) ByteBuffer.allocateDirect(chunkBytes) else null
        val heap = if (direct == null) ByteArray(chunkBytes) else null
        var sinceSync = 0L
        return {
            if (direct != null) {
                direct.clear()
                sink.write(direct)
            } else {
                sink.write(heap!!)
            }
            if (request.syncIntervalBytes > 0) {
                sinceSync += chunkBytes
                if (sinceSync >= request.syncIntervalBytes) {
                    sink.sync()
                    sinceSync = 0
                }
            }
        }
    }

    private fun failure(
        request: StorageBenchmarkRequest,
        code: FailureCode,
        recoverability: Recoverability,
        message: String,
        details: Map<String, String> = emptyMap(),
    ) = StableFailure(
        component = "storage-benchmark",
        code = code,
        severity = if (recoverability == Recoverability.UNSUPPORTED) FailureSeverity.WARNING else FailureSeverity.ERROR,
        recoverability = recoverability,
        correlationId = request.benchmarkId,
        userMessage = message,
        details = details,
    )
}

/** The schema's outcome vocabulary (`pass`, `fail`, `cancelled`, `error`); [StorageBenchmarkOutcome.name] rides along as detail. */
fun StorageBenchmarkOutcome.schemaOutcome(): String = when (this) {
    StorageBenchmarkOutcome.QUALIFIED -> "pass"
    StorageBenchmarkOutcome.NOT_QUALIFIED -> "fail"
    StorageBenchmarkOutcome.CANCELLED -> "cancelled"
    StorageBenchmarkOutcome.UNSUPPORTED, StorageBenchmarkOutcome.FAILED -> "error"
}

/** Serializes a result with the accepted BenchmarkResultDocument schema; `createdAt` is an ISO-8601 instant. */
class JsonBenchmarkResultRepository(
    private val output: OutputStream,
    private val now: () -> Instant = Instant::now,
) : BenchmarkResultRepository {
    override fun persist(result: StorageBenchmarkResult): BenchmarkPersistenceOutcome = runCatching {
        val document = BenchmarkResultDocument(
            schemaVersion = "1.0.0",
            benchmarkId = result.benchmarkId,
            createdAt = now().toString(),
            destination = buildJsonObject {
                put("id", result.destinationId)
            },
            bytesWritten = result.bytesWritten,
            windows = result.windows.map { window ->
                buildJsonObject {
                    put("index", window.index)
                    put("startedAtNanos", window.startedAtNanos)
                    put("durationMillis", window.durationMillis)
                    put("bytesWritten", window.bytesWritten)
                    put("bytesPerSecond", window.bytesPerSecond)
                }
            },
            requiredBytesPerSecond = result.requiredBytesPerSecond,
            qualificationMarginBytesPerSecond = result.qualificationMarginBytesPerSecond,
            outcome = result.outcome.schemaOutcome(),
            outcomeDetail = result.outcome.name,
        )
        output.write(encodeCanonical(document).toByteArray())
        output.flush()
        BenchmarkPersistenceOutcome.Persisted
    }.getOrElse { error ->
        BenchmarkPersistenceOutcome.Failed(error.message ?: "write failed")
    }
}

/** A testable cancellation source for UI/service owners. */
class BenchmarkCancellationSource : BenchmarkCancellation {
    private val cancelled = AtomicBoolean(false)

    override fun isCancelled(): Boolean = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }
}
