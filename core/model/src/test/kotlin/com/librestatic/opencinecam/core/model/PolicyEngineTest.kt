/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {
    private val requested = CaptureGraph("0", "avc", "encoder-a", 8, 20_000_000, 3840, 2160, 30, false, false, "SDR")
    private val failure = StableFailure(
        component = "preflight",
        code = FailureCode.ENCODER_CONFIGURATION_FAILED,
        severity = FailureSeverity.ERROR,
        recoverability = Recoverability.RETRYABLE,
        correlationId = "corr-1",
        userMessage = "The requested graph could not be started.",
    )

    @Test
    fun strictRejectsWithoutTransition() {
        val decision = PolicyEngine().decidePreflight(failure, PreflightContext(requested, emptyList()))
        assertTrue(decision is PreflightDecision.Rejected)
    }

    @Test
    fun adaptiveUsesExactOrderedFallbackAndPreservesInvariants() {
        val candidates = listOf(
            AdaptiveCandidate(AdaptiveStep.FPS, requested.copy(fps = 24), "24 fps"),
            AdaptiveCandidate(AdaptiveStep.BITRATE, requested.copy(bitrate = 15_000_000), "15 Mb/s"),
            AdaptiveCandidate(AdaptiveStep.SAME_CODEC_ENCODER, requested.copy(codecName = "encoder-b"), "alternate AVC encoder"),
            AdaptiveCandidate(AdaptiveStep.RESOLUTION, requested.copy(width = 1920, height = 1080), "1080p"),
        )
        val decision = PolicyEngine(PolicyMode.ADAPTIVE).decidePreflight(failure, PreflightContext(requested, candidates))
        assertEquals("encoder-b", (decision as PreflightDecision.Transition).candidate.graph.codecName)
    }

    @Test
    fun codecFamilyChangeNeedsConfirmationAndProtectedChangeIsRejected() {
        val familyChange = AdaptiveCandidate(
            AdaptiveStep.CODEC_FAMILY_CHANGE,
            requested.copy(codecFamily = "hevc", codecName = "encoder-hevc"),
            "HEVC requires confirmation",
        )
        val withoutConfirmation = PolicyEngine(PolicyMode.ADAPTIVE)
            .decidePreflight(failure, PreflightContext(requested, listOf(familyChange)))
        assertTrue(withoutConfirmation is PreflightDecision.Rejected)
        val protectedChange = AdaptiveCandidate(
            AdaptiveStep.BITRATE,
            requested.copy(bitDepth = 10),
            "10-bit change",
        )
        val rejected = PolicyEngine(PolicyMode.ADAPTIVE)
            .decidePreflight(failure, PreflightContext(requested, listOf(protectedChange), codecFamilyChangeConfirmed = true))
        assertTrue(rejected is PreflightDecision.Rejected)
    }

    @Test
    fun adaptiveRuntimeAudioFailureContinuesWithDisclosure() {
        val audioFailure = failure.copy(code = FailureCode.AUDIO_RUNTIME_FAILED)
        val decision = PolicyEngine(PolicyMode.ADAPTIVE).decideRuntime(audioFailure)
        assertTrue(decision is RuntimeDecision.ContinueWithWarning)
    }
}
