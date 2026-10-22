/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.core.model.BenchmarkPersistenceOutcome
import com.librestatic.opencinecam.core.model.StorageBenchmarkOutcome
import com.librestatic.opencinecam.core.model.StorageBenchmarkResult
import com.librestatic.opencinecam.core.model.StorageBenchmarkWindow
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageBenchmarkModelTest {
    @get:Rule val folder = TemporaryFolder()

    private fun result(outcome: StorageBenchmarkOutcome = StorageBenchmarkOutcome.QUALIFIED) = StorageBenchmarkResult(
        benchmarkId = "123e4567-e89b-42d3-a456-426614174002",
        destinationId = "saf-destination",
        windows = listOf(StorageBenchmarkWindow(0, 10L, 1_000L, 90_000_000L, 90_000_000L)),
        bytesWritten = 90_000_000L,
        requiredBytesPerSecond = 40_000_000L,
        qualificationMarginBytesPerSecond = 50_000_000L,
        outcome = outcome,
    )

    @Test fun requiredRateRoundsTheProjectedMinuteUpToWholeBytesPerSecond() {
        val settings = CameraSettings()
        val perSecond = benchmarkRequiredBytesPerSecond(CaptureMode.VIDEO, settings, 30, constrainedHighSpeed = false)
        assertTrue(perSecond > 0)
        val faster = benchmarkRequiredBytesPerSecond(CaptureMode.VIDEO, settings.copy(videoBitrateMbps = settings.videoBitrateMbps * 2), 30, false)
        assertTrue(faster > perSecond)
        // A photo mode is measured as the video take it would be if recording started.
        assertEquals(perSecond, benchmarkRequiredBytesPerSecond(CaptureMode.PHOTO, settings, 30, false))
    }

    @Test fun requestUsesThreeWindowsAndLargeChunks() {
        val request = benchmarkRequest("id", 1_000)
        assertEquals(BENCHMARK_WINDOWS, request.windowCount)
        assertTrue(request.chunkSizeBytes >= 1 shl 20)
    }

    @Test fun storeKeepsTheLatestResultAndReadsItBack() {
        val store = BenchmarkResultStore(File(folder.root, "benchmarks/latest.json"))
        assertNull(store.latest())
        assertEquals(BenchmarkPersistenceOutcome.Persisted, store.repository().persist(result()))
        val document = store.latest()!!
        assertEquals("pass", document.outcome)
        assertEquals("QUALIFIED", document.outcomeDetail)
        assertEquals(50_000_000L, document.qualificationMarginBytesPerSecond)
        Instant.parse(document.createdAt)
        store.repository().persist(result(StorageBenchmarkOutcome.NOT_QUALIFIED))
        assertEquals("NOT_QUALIFIED", store.latest()!!.outcomeDetail)
    }

    @Test fun outcomeLabelsFallBackToFailedForUnknownDetail() {
        assertEquals(R.string.bench_outcome_failed, benchmarkOutcomeLabel(null))
        assertEquals(R.string.bench_outcome_failed, benchmarkOutcomeLabel("nonsense"))
        assertEquals(R.string.bench_outcome_not_qualified, benchmarkOutcomeLabel("NOT_QUALIFIED"))
    }

    @Test fun rateIsShownInDecimalMegabytes() {
        assertEquals("12.5 MB/s", megabytesPerSecond(12_500_000))
    }
}
