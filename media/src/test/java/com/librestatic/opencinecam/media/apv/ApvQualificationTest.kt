// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.apv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApvQualificationTest {
    private val report = ApvCapabilityReport(
        apiLevel = 36,
        mime = "video/apv",
        profile = "APVProfile422_10",
        chroma = "4:2:2 10-bit",
        frameRate = 30,
        surfaceInput = ApvEvidenceState.VERIFIED,
        hardwareEncoder = ApvEvidenceState.VERIFIED,
        mp4 = ApvEvidenceState.VERIFIED,
        storage = ApvEvidenceState.VERIFIED,
        decode = ApvEvidenceState.VERIFIED,
        evidenceId = "apv-fixture",
    )

    @Test
    fun probeRequiresEveryApiCodecStorageAndDecodeGate() {
        val qualified = ApvProbe.qualify(report)
        assertEquals(ApvQualificationStatus.READY, qualified.status)
        assertTrue(qualified.hardware)
        assertEquals(ApvQualificationStatus.UNSUPPORTED, ApvProbe.qualify(report.copy(apiLevel = 35)).status)
        assertEquals(ApvQualificationStatus.UNKNOWN,
            ApvProbe.qualify(report.copy(storage = ApvEvidenceState.UNKNOWN)).status)
    }

    @Test
    fun fileValidationChecksDrainFinalizeDecodeTimestampProfileAndChroma() {
        val fixture = ApvContainerFixture(
            frames = listOf(ApvEncodedFrame(10, byteArrayOf(1)), ApvEncodedFrame(20, byteArrayOf(2))),
            eosDrained = true,
            finalized = true,
            decoded = true,
            profile = "APVProfile422_10",
            chroma = "4:2:2 10-bit",
        )
        assertEquals(ApvFileStatus.PASS, ApvFileValidator.validate(fixture).status)
        assertEquals(ApvFileStatus.FAIL,
            ApvFileValidator.validate(fixture.copy(frames = listOf(fixture.frames[1], fixture.frames[0]))).status)
        assertEquals(ApvFileStatus.UNKNOWN,
            ApvFileValidator.validate(fixture.copy(frames = emptyList())).status)
    }

    @Test
    fun uiDisablesUnsupportedAndKeepsSeparateRawVerdict() {
        val unsupported = ApvUiPolicy.decide(ApvProbe.qualify(report.copy(apiLevel = 35)))
        assertEquals(ApvUiMode.DISABLED, unsupported.mode)
        assertEquals("AVC/HEVC SDR", unsupported.fallbackLabel)
        assertEquals(ApvRawVerdict.UNSUPPORTED,
            verdictForRaw(ApvRawEvidence(ApvEvidenceState.VERIFIED, ApvEvidenceState.UNSUPPORTED)))
        assertEquals(ApvRawVerdict.UNKNOWN,
            verdictForRaw(ApvRawEvidence(ApvEvidenceState.VERIFIED, ApvEvidenceState.UNKNOWN)))
        assertFalse(unsupported.label == "APV")
    }
}
