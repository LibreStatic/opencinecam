/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.core.util.AtomicFile
import com.librestatic.opencinecam.core.model.BenchmarkPersistenceOutcome
import com.librestatic.opencinecam.core.model.BenchmarkResultDocument
import com.librestatic.opencinecam.core.model.BenchmarkResultRepository
import com.librestatic.opencinecam.core.model.JsonBenchmarkResultRepository
import com.librestatic.opencinecam.core.model.StorageBenchmarkOutcome
import com.librestatic.opencinecam.core.model.StorageBenchmarkRequest
import com.librestatic.opencinecam.core.model.StorageBenchmarkResult
import com.librestatic.opencinecam.core.model.canonicalJson
import com.librestatic.opencinecam.core.model.checkSchemaCompatibility
import com.librestatic.opencinecam.service.projectedTakeBytesPerMinute
import java.io.File
import java.util.Locale

/** One-second windows, three of them: one slow burst must not qualify a destination. */
internal const val BENCHMARK_WINDOWS = 3

/** Big chunks, so the loop's 1 ms pause between writes does not cap the throughput it measures. */
private const val BENCHMARK_CHUNK_BYTES = 1 shl 20

/**
 * The rate the current settings write at, from the same projection the storage preflight uses. A
 * mode that does not record (a photo mode) is measured as a video take.
 */
internal fun benchmarkRequiredBytesPerSecond(
    mode: CaptureMode,
    settings: CameraSettings,
    targetFps: Int,
    constrainedHighSpeed: Boolean,
): Long {
    val recordingMode = if (mode == CaptureMode.TIME_LAPSE || mode.usesLogGraph || mode == CaptureMode.VIDEO) mode else CaptureMode.VIDEO
    val perMinute = projectedTakeBytesPerMinute(recordingMode, settings, targetFps, constrainedHighSpeed, settings.audioEnabled)
    return (perMinute + 59) / 60
}

internal fun benchmarkRequest(benchmarkId: String, requiredBytesPerSecond: Long) = StorageBenchmarkRequest(
    benchmarkId = benchmarkId,
    windowCount = BENCHMARK_WINDOWS,
    requiredBytesPerSecond = requiredBytesPerSecond,
    chunkSizeBytes = BENCHMARK_CHUNK_BYTES,
)

internal fun megabytesPerSecond(bytesPerSecond: Long): String =
    String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1_000_000.0)

internal fun benchmarkOutcomeLabel(detail: String?): Int = when (detail?.let { runCatching { StorageBenchmarkOutcome.valueOf(it) }.getOrNull() }) {
    StorageBenchmarkOutcome.QUALIFIED -> R.string.bench_outcome_qualified
    StorageBenchmarkOutcome.NOT_QUALIFIED -> R.string.bench_outcome_not_qualified
    StorageBenchmarkOutcome.CANCELLED -> R.string.bench_outcome_cancelled
    StorageBenchmarkOutcome.UNSUPPORTED -> R.string.bench_outcome_unsupported
    StorageBenchmarkOutcome.FAILED, null -> R.string.bench_outcome_failed
}

/** The newest result, in `filesDir/benchmarks/latest.json`, replaced atomically. */
internal class BenchmarkResultStore(file: File) {
    private val atomic = AtomicFile(file)

    @Synchronized fun latest(): BenchmarkResultDocument? = runCatching {
        if (!atomic.baseFile.exists()) return null
        canonicalJson.decodeFromString(BenchmarkResultDocument.serializer(), atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            .also { checkSchemaCompatibility(it.schemaVersion) }
    }.getOrNull()

    /** Persists each destination's result, which for one benchmark is the whole report. */
    fun repository() = BenchmarkResultRepository { result -> write(result) }

    @Synchronized private fun write(result: StorageBenchmarkResult): BenchmarkPersistenceOutcome {
        atomic.baseFile.parentFile?.mkdirs()
        val stream = atomic.startWrite()
        return try {
            val outcome = JsonBenchmarkResultRepository(stream).persist(result)
            if (outcome is BenchmarkPersistenceOutcome.Persisted) atomic.finishWrite(stream) else atomic.failWrite(stream)
            outcome
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            BenchmarkPersistenceOutcome.Failed(failure.message ?: "write failed")
        }
    }

    companion object {
        fun inFilesDir(filesDir: File) = BenchmarkResultStore(File(File(filesDir, "benchmarks"), "latest.json"))
    }
}
