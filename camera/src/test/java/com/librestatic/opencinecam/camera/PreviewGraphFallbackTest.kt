/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.Knowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fallback order Camera2PreviewEngine.configureSession iterates, built from its real graph inputs. */
class PreviewGraphFallbackTest {
    private val preview = StreamSize(1920, 1080)
    private val jpeg = StreamSize(4000, 3000)
    private val raw = StreamSize(4032, 3024)
    private val yuv = StreamSize(640, 480)

    private fun ids(graphs: List<CaptureGraphRequest>) = graphs.map { graph -> graph.outputs.map { it.id } }

    private fun plan(request: CaptureGraphRequest, streams: StreamCapabilityReport?) =
        GraphNegotiator().fallbackGraphs(request, streams, PREVIEW_GRAPH_DROP_ORDER)

    @Test
    fun photoGraphDropsYuvThenRawAndNeverPreviewOrStill() {
        val request = previewGraphRequest("0", 30, preview, jpeg, raw, yuv)
        assertEquals(
            listOf(
                listOf("preview", "still", "raw", "analysis"),
                listOf("preview", "still", "raw"),
                listOf("preview", "still"),
            ),
            ids(plan(request, probe(yuvSizes = listOf(yuv), rawSizes = listOf(raw)))),
        )
        // The first attempt is the unchanged request the engine builds today.
        assertEquals(request, plan(request, null).first())
    }

    @Test
    fun gpuPhotoAndHeicGraphsFallBackOnlyThroughWhatTheyCarry() {
        // GPU photo monitor: no YUV stream.
        assertEquals(
            listOf(listOf("preview", "still", "raw"), listOf("preview", "still")),
            ids(plan(previewGraphRequest("0", 30, preview, jpeg, raw, null), null)),
        )
        // HEIC: no RAW reader, so only the YUV stream can be dropped.
        assertEquals(
            listOf(listOf("preview", "still", "analysis"), listOf("preview", "still")),
            ids(plan(previewGraphRequest("0", 30, preview, jpeg, null, yuv), null)),
        )
    }

    @Test
    fun videoGraphDropsYuvToPreviewOnly() {
        assertEquals(
            listOf(listOf("preview", "analysis"), listOf("preview")),
            ids(plan(previewGraphRequest("0", 60, preview, null, null, yuv), probe())),
        )
        // A graph without optional streams has exactly one attempt (today's failure path).
        assertEquals(listOf(listOf("preview")), ids(plan(previewGraphRequest("0", 30, preview, null, null, null), probe())))
    }

    @Test
    fun unknownCapabilitiesAllowEveryCandidate() {
        val request = previewGraphRequest("0", 240, preview, jpeg, raw, yuv)
        // All-null metadata (e.g. no stream map): every Knowledge is Unknown, and fps is not checked.
        val unknown = probe(yuvSizes = null, rawSizes = null)
        assertTrue(unknown.previewSizes is Knowledge.Unknown && unknown.rawSizes is Knowledge.Unknown)
        assertEquals(3, plan(request, unknown).size)
        assertEquals(request, plan(request, unknown).first())
        // Missing report and a report for another camera also allow.
        assertEquals(3, plan(request, null).size)
        assertEquals(3, plan(request, probe(cameraId = "1", yuvSizes = emptyList(), rawSizes = emptyList())).size)
    }

    @Test
    fun knownExclusionsSkipCandidatesButTheLastAttemptSurvives() {
        val request = previewGraphRequest("0", 30, preview, jpeg, raw, yuv)
        // YUV size not advertised: skip straight to the graph without it.
        assertEquals(
            listOf(listOf("preview", "still", "raw"), listOf("preview", "still")),
            ids(plan(request, probe(yuvSizes = listOf(StreamSize(320, 240)), rawSizes = listOf(raw)))),
        )
        // Preview surface is PRIVATE: its size is never checked against the YUV list.
        val video = previewGraphRequest("0", 30, StreamSize(3840, 2160), null, null, null)
        assertEquals(listOf(listOf("preview")), ids(plan(video, probe(yuvSizes = listOf(yuv)))))
    }

    @Test
    fun sessionPreCheckSkipsOnlyOnExplicitFalseWithAFallback() {
        assertTrue(skipBySessionPreCheck(sessionConfigurationSupport { false }, hasFallback = true))
        assertFalse(skipBySessionPreCheck(sessionConfigurationSupport { false }, hasFallback = false))
        assertFalse(skipBySessionPreCheck(sessionConfigurationSupport { true }, hasFallback = true))
        val unsupportedQuery = sessionConfigurationSupport { throw UnsupportedOperationException("HAL query not implemented") }
        assertEquals(Knowledge.Unknown, unsupportedQuery)
        assertFalse(skipBySessionPreCheck(unsupportedQuery, hasFallback = true))
        assertFalse(skipBySessionPreCheck(sessionConfigurationSupport { throw IllegalArgumentException("bad") }, hasFallback = true))
    }

    @Test
    fun abandonedSurfaceOnARetryIsReportedInsteadOfEscaping() {
        // The emulator crash: createCaptureSession rejects the first graph because the preview
        // surface was abandoned, and every retry then throws while re-wrapping that surface.
        val walk = Walk(graphCount = 3) { throw IllegalArgumentException("Surface was abandoned") }
        walk.fallback.start()
        assertEquals(listOf(0, 1, 2), walk.attempts)
        assertEquals(listOf(0 to "Surface was abandoned", 1 to "Surface was abandoned"), walk.retries)
        assertEquals(listOf("preview-session-exception" to "Surface was abandoned"), walk.failures)
    }

    @Test
    fun aThrowingAttemptFallsBackToTheNextGraph() {
        val walk = Walk(graphCount = 3) { index ->
            if (index == 0) throw IllegalStateException()
            true
        }
        walk.fallback.start()
        assertEquals(listOf(0, 1), walk.attempts)
        // A message-less exception still names its type in the retry log.
        assertEquals(listOf(0 to "IllegalStateException"), walk.retries)
        assertTrue(walk.failures.isEmpty())
    }

    @Test
    fun preCheckSkipsToTheNextGraph() {
        val walk = Walk(graphCount = 2) { index -> index == 1 }
        walk.fallback.start()
        assertEquals(listOf(0, 1), walk.attempts)
        assertEquals(listOf(0 to "isSessionConfigurationSupported=false"), walk.retries)
        assertTrue(walk.failures.isEmpty())
    }

    @Test
    fun configureFailedRetriesWithoutThrowingAndReportsOnTheLastGraph() {
        val walk = Walk(graphCount = 3) { index ->
            if (index > 0) throw IllegalArgumentException("Surface was abandoned")
            true
        }
        walk.fallback.start()
        // onConfigureFailed of graph 0 arrives later on the camera executor; the retries throw.
        walk.fallback.configureFailed(0)
        assertEquals(listOf(0, 1, 2), walk.attempts)
        assertEquals(listOf(0 to "configure failed", 1 to "Surface was abandoned"), walk.retries)
        assertEquals(listOf("preview-session-exception" to "Surface was abandoned"), walk.failures)

        val last = Walk(graphCount = 1) { true }
        last.fallback.start()
        last.fallback.configureFailed(0)
        assertEquals(listOf("preview-session-failed" to "Camera preview configuration failed."), last.failures)
    }

    @Test
    fun aRetiredGraphNeitherRetriesNorReports() {
        val walk = Walk(graphCount = 3, owns = false) { throw IllegalArgumentException("Surface was abandoned") }
        walk.fallback.start()
        walk.fallback.configureFailed(0)
        assertEquals(listOf(0), walk.attempts)
        assertTrue(walk.retries.isEmpty() && walk.failures.isEmpty())
    }

    /** Records what [PreviewGraphFallback] does with an engine-shaped [attempt]. */
    private class Walk(graphCount: Int, owns: Boolean = true, attempt: (Int) -> Boolean) {
        val attempts = mutableListOf<Int>()
        val retries = mutableListOf<Pair<Int, String>>()
        val failures = mutableListOf<Pair<String, String>>()
        val fallback = PreviewGraphFallback(
            graphCount = graphCount,
            ownsGraph = { owns },
            onRetry = { rejected, reason -> retries += rejected to reason },
            onFailure = { code, message -> failures += code to message },
        ) { index ->
            attempts += index
            attempt(index)
        }
    }

    private fun probe(
        cameraId: String = "0",
        yuvSizes: List<StreamSize>? = listOf(yuv, preview),
        rawSizes: List<StreamSize>? = listOf(raw),
    ) = StreamCapabilityProbe {
        StreamCapabilityMetadata(
            cameraId = cameraId,
            previewSizes = yuvSizes,
            rawSizes = rawSizes,
            highSpeedSizes = null,
            highSpeedFpsRanges = null,
            targetFpsRanges = null,
            manualSensor = null,
            manualPostProcessing = null,
            dynamicRangeProfiles = null,
            colorSpaceProfiles = null,
        )
    }.probe(cameraId)
}
