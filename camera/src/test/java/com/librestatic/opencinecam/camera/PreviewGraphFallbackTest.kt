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
