/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import com.librestatic.opencinecam.core.model.AdaptiveCandidate
import com.librestatic.opencinecam.core.model.AdaptiveStep
import com.librestatic.opencinecam.core.model.CaptureGraph
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.PolicyMode
import com.librestatic.opencinecam.core.model.StableFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderIntegrationTest {
    @Test
    fun startsStopsAndValidatesWithOwnedCleanup() {
        val resources = FakeResources()
        val integration = RecorderIntegration()
        val started = integration.start(request(), listOf(capability()), RecorderResourceFactory { _, _ -> resources })
        assertTrue(started is RecorderStartResult.Started)

        val stopped = integration.stop("session-1") { RecordedFileValidation(true, 100, true, false, "ok") }
        assertEquals(100, (stopped as RecorderStopResult.Validated).validation.bytesWritten)
        assertTrue(resources.started && resources.stopped && resources.closed)
    }

    @Test
    fun strictRejectsPreflightAndAdaptiveDisclosesTransition() {
        val failure = StableFailure(
            component = "preflight",
            code = FailureCode.ENCODER_CONFIGURATION_FAILED,
            severity = com.librestatic.opencinecam.core.model.FailureSeverity.ERROR,
            recoverability = com.librestatic.opencinecam.core.model.Recoverability.RETRYABLE,
            correlationId = "preflight-1",
            userMessage = "encoder rejected",
        )
        val adaptiveGraph = graph().copy(bitrate = 800_000)
        val adaptiveRequest = request().copy(
            preflightFailure = failure,
            adaptiveCandidates = listOf(
                AdaptiveCandidate(AdaptiveStep.BITRATE, adaptiveGraph, "Bitrate reduced after encoder rejection."),
            ),
        )
        val strict = RecorderIntegration(policy = PolicyMode.STRICT)
        assertEquals(FailureCode.ENCODER_CONFIGURATION_FAILED, (strict.start(adaptiveRequest, listOf(capability()), RecorderResourceFactory { _, _ -> FakeResources() }) as RecorderStartResult.Rejected).failure.code)

        val resources = FakeResources()
        val adaptive = RecorderIntegration(policy = PolicyMode.ADAPTIVE)
        val started = adaptive.start(adaptiveRequest, listOf(capability()), RecorderResourceFactory { _, _ -> resources }) as RecorderStartResult.Started
        assertEquals(adaptiveGraph, started.graph)
        assertTrue(started.disclosure!!.contains("Bitrate reduced"))
        adaptive.stop("session-1") { RecordedFileValidation(true, 1, true, false, "ok") }
    }

    @Test
    fun cancellationDuplicateStaleAndValidationFailureAreExplicit() {
        val integration = RecorderIntegration()
        val cancelled = integration.start(request(), listOf(capability()), RecorderResourceFactory { _, _ -> FakeResources() }, RecorderCancellation { true })
        assertTrue(cancelled is RecorderStartResult.Cancelled)

        val resources = FakeResources()
        assertTrue(integration.start(request(), listOf(capability()), RecorderResourceFactory { _, _ -> resources }) is RecorderStartResult.Started)
        val duplicate = integration.start(request().copy(sessionId = "other"), listOf(capability()), RecorderResourceFactory { _, _ -> FakeResources() })
        assertEquals(FailureCode.DUPLICATE_COMMAND, (duplicate as RecorderStartResult.Rejected).failure.code)
        val stale = integration.stop("wrong") { RecordedFileValidation(true, 1, true, false, "ok") }
        assertEquals(FailureCode.STALE_EVIDENCE, (stale as RecorderStopResult.Failed).failure.code)
        val invalid = integration.stop("session-1") { RecordedFileValidation(false, 1, true, false, "bad") }
        assertEquals(FailureCode.INTEGRITY_FAILURE, (invalid as RecorderStopResult.Failed).failure.code)
        assertTrue(resources.closed)
    }

    private fun request() = RecorderStartRequest(
        sessionId = "session-1",
        graph = graph(),
        encoder = VideoEncoderRequest("encoder-1", "video/avc", 1920, 1080, 30, 2_000_000, null),
        orientation = RecordingOrientation(90, mirroredPreview = true),
    )

    private fun graph() = CaptureGraph("0", "avc", "codec", 8, 2_000_000, 1920, 1080, 30, false, false, "SDR")

    private fun capability() = VideoEncoderCapability(
        name = "codec",
        mime = "video/avc",
        hardwareAccelerated = com.librestatic.opencinecam.core.model.Knowledge.Known(true),
        profileLevels = com.librestatic.opencinecam.core.model.Knowledge.Known(emptySet()),
        colorFormats = com.librestatic.opencinecam.core.model.Knowledge.Known(setOf(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)),
        widthAlignment = com.librestatic.opencinecam.core.model.Knowledge.Known(2),
        heightAlignment = com.librestatic.opencinecam.core.model.Knowledge.Known(2),
        bitrateRange = com.librestatic.opencinecam.core.model.Knowledge.Known(1_000_000L..4_000_000L),
        fpsRange = com.librestatic.opencinecam.core.model.Knowledge.Known(24..60),
        performanceScore = com.librestatic.opencinecam.core.model.Knowledge.Known(100),
    )

    private class FakeResources : RecorderResources {
        var started = false
        var stopped = false
        var closed = false
        override fun start() { started = true }
        override fun stop() { stopped = true }
        override fun close() { closed = true }

        companion object {
            fun factory(graph: CaptureGraph, encoder: SelectedVideoEncoder): RecorderResources = FakeResources()
        }
    }
}
