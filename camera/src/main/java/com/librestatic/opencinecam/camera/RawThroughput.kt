/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

data class RawThroughputSample(
    val durationMs: Long,
    val bytesWritten: Long,
    val droppedFrames: Int,
) {
    init {
        require(durationMs > 0 && bytesWritten >= 0 && droppedFrames >= 0) {
            "throughput sample values are invalid"
        }
    }

    val bytesPerSecond: Long
        get() = bytesWritten * 1000L / durationMs
}

enum class RawBenchmarkStatus {
    PASS,
    FAIL,
    UNKNOWN,
}

data class RawThroughputQualification(
    val status: RawBenchmarkStatus,
    val windowSeconds: Long,
    val streamBytesPerSecond: Long,
    val p01BytesPerSecond: Long?,
    val requiredBytesPerSecond: Long?,
    val droppedFrames: Int,
    val reasonCode: String,
)

object RawThroughputBenchmark {
    fun qualify(
        windowSeconds: Long,
        streamBytesPerSecond: Long?,
        samples: List<RawThroughputSample>,
    ): RawThroughputQualification {
        if (streamBytesPerSecond == null || samples.isEmpty()) {
            return RawThroughputQualification(
                RawBenchmarkStatus.UNKNOWN, windowSeconds, streamBytesPerSecond ?: 0,
                null, null, samples.sumOf { it.droppedFrames }, "benchmark-evidence-unknown",
            )
        }
        if (windowSeconds < 60 || streamBytesPerSecond <= 0) {
            return RawThroughputQualification(
                RawBenchmarkStatus.FAIL, windowSeconds, streamBytesPerSecond,
                null, null, samples.sumOf { it.droppedFrames }, "sustained-window-invalid",
            )
        }
        val sortedRates = samples.map(RawThroughputSample::bytesPerSecond).sorted()
        val p01Index = ((sortedRates.size - 1) * 0.01).toInt()
        val p01 = sortedRates[p01Index]
        val required = streamBytesPerSecond * 125L / 100L
        val dropped = samples.sumOf { it.droppedFrames }
        val pass = dropped == 0 && p01 >= required
        return RawThroughputQualification(
            if (pass) RawBenchmarkStatus.PASS else RawBenchmarkStatus.FAIL,
            windowSeconds,
            streamBytesPerSecond,
            p01,
            required,
            dropped,
            if (pass) "raw-throughput-pass" else if (dropped > 0) "raw-frame-loss" else "raw-throughput-margin-fail",
        )
    }
}

enum class RawBenchmarkCommandStatus {
    STARTED,
    DUPLICATE,
    CANCELLED,
    CLOSED,
}

class RawBenchmarkSession(private val maxRuns: Int = 4) {
    private val results = LinkedHashMap<String, RawThroughputQualification>()
    private val cancelled = HashSet<String>()
    private var closed = false

    init {
        require(maxRuns > 0) { "maxRuns must be positive" }
    }

    fun run(
        runId: String,
        windowSeconds: Long,
        streamBytesPerSecond: Long?,
        samples: List<RawThroughputSample>,
    ): RawBenchmarkCommandStatus {
        if (closed) return RawBenchmarkCommandStatus.CLOSED
        if (cancelled.contains(runId)) return RawBenchmarkCommandStatus.CANCELLED
        if (runId.isBlank() || results.containsKey(runId) || results.size >= maxRuns) {
            return if (results.containsKey(runId)) RawBenchmarkCommandStatus.DUPLICATE
            else RawBenchmarkCommandStatus.CLOSED
        }
        results[runId] = RawThroughputBenchmark.qualify(windowSeconds, streamBytesPerSecond, samples)
        return RawBenchmarkCommandStatus.STARTED
    }

    fun cancel(runId: String): RawBenchmarkCommandStatus {
        if (closed) return RawBenchmarkCommandStatus.CLOSED
        if (runId.isBlank() || results.containsKey(runId)) return RawBenchmarkCommandStatus.CLOSED
        cancelled += runId
        return RawBenchmarkCommandStatus.CANCELLED
    }

    fun result(runId: String): RawThroughputQualification? = results[runId]

    fun close() {
        results.clear()
        cancelled.clear()
        closed = true
    }
}
