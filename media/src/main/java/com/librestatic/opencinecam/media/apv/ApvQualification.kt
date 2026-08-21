// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.apv

enum class ApvEvidenceState {
    UNKNOWN,
    UNSUPPORTED,
    VERIFIED,
}

data class ApvCapabilityReport(
    val apiLevel: Int?,
    val mime: String?,
    val profile: String?,
    val chroma: String?,
    val frameRate: Int?,
    val surfaceInput: ApvEvidenceState,
    val hardwareEncoder: ApvEvidenceState,
    val mp4: ApvEvidenceState,
    val storage: ApvEvidenceState,
    val decode: ApvEvidenceState,
    val evidenceId: String,
) {
    init {
        require(evidenceId.isNotBlank()) { "APV evidence ID must not be blank" }
    }
}

enum class ApvQualificationStatus {
    READY,
    UNSUPPORTED,
    UNKNOWN,
}

data class ApvQualification(
    val status: ApvQualificationStatus,
    val reasonCode: String,
    val evidenceId: String,
    val hardware: Boolean,
)

object ApvProbe {
    fun qualify(report: ApvCapabilityReport): ApvQualification {
        val api = report.apiLevel ?: return unknown(report, "api-level-unknown")
        if (api < 36) return unsupported(report, "api-level-below-36")
        if (report.mime == null || report.profile == null || report.chroma == null || report.frameRate == null) {
            return unknown(report, "apv-metadata-unknown")
        }
        if (report.mime != "video/apv") return unsupported(report, "apv-mime-unavailable")
        if (report.profile !in APV_422_10_PROFILES || report.chroma != "4:2:2 10-bit" || report.frameRate <= 0) {
            return unsupported(report, "apv-profile-chroma-rate-unsupported")
        }
        val states = listOf(report.surfaceInput, report.hardwareEncoder, report.mp4, report.storage, report.decode)
        if (states.any { it == ApvEvidenceState.UNKNOWN }) return unknown(report, "apv-qualification-unknown")
        if (states.any { it != ApvEvidenceState.VERIFIED }) return unsupported(report, "apv-qualification-failed")
        return ApvQualification(ApvQualificationStatus.READY, "apv-qualified", report.evidenceId, hardware = true)
    }

    private fun unsupported(report: ApvCapabilityReport, reason: String) =
        ApvQualification(ApvQualificationStatus.UNSUPPORTED, reason, report.evidenceId, hardware = false)

    private fun unknown(report: ApvCapabilityReport, reason: String) =
        ApvQualification(ApvQualificationStatus.UNKNOWN, reason, report.evidenceId, hardware = false)
}

data class ApvEncodedFrame(val timestampUs: Long, val payload: ByteArray) {
    init {
        require(timestampUs >= 0 && payload.isNotEmpty()) { "APV frame is invalid" }
    }
}

data class ApvContainerFixture(
    val frames: List<ApvEncodedFrame>,
    val eosDrained: Boolean,
    val finalized: Boolean,
    val decoded: Boolean,
    val profile: String,
    val chroma: String,
) 

enum class ApvFileStatus {
    PASS,
    FAIL,
    UNKNOWN,
}

data class ApvFileValidation(val status: ApvFileStatus, val reasonCode: String)

object ApvFileValidator {
    fun validate(fixture: ApvContainerFixture): ApvFileValidation {
        if (fixture.frames.isEmpty()) return ApvFileValidation(ApvFileStatus.UNKNOWN, "apv-no-frames")
        if (!fixture.eosDrained || !fixture.finalized || !fixture.decoded) {
            return ApvFileValidation(ApvFileStatus.FAIL, "apv-drain-finalize-decode-failed")
        }
        if (fixture.frames.zipWithNext().any { it.first.timestampUs >= it.second.timestampUs }) {
            return ApvFileValidation(ApvFileStatus.FAIL, "apv-timestamps-not-monotonic")
        }
        if (fixture.profile !in APV_422_10_PROFILES || fixture.chroma != "4:2:2 10-bit") {
            return ApvFileValidation(ApvFileStatus.FAIL, "apv-profile-chroma-mismatch")
        }
        return ApvFileValidation(ApvFileStatus.PASS, "apv-file-validation-pass")
    }
}

val APV_422_10_PROFILES: Set<String> = setOf(
    "APVProfile422_10",
    "APVProfile422_10HDR10",
    "APVProfile422_10HDR10Plus",
)

enum class ApvUiMode {
    QUALIFIED,
    DISABLED,
    UNKNOWN,
}

data class ApvUiDecision(
    val mode: ApvUiMode,
    val label: String,
    val reasonCode: String,
    val fallbackLabel: String,
)

object ApvUiPolicy {
    fun decide(qualification: ApvQualification): ApvUiDecision = when (qualification.status) {
        ApvQualificationStatus.READY -> ApvUiDecision(ApvUiMode.QUALIFIED, "APV", "apv-qualified", "")
        ApvQualificationStatus.UNSUPPORTED -> ApvUiDecision(ApvUiMode.DISABLED, "APV unavailable", qualification.reasonCode, "AVC/HEVC SDR")
        ApvQualificationStatus.UNKNOWN -> ApvUiDecision(ApvUiMode.UNKNOWN, "APV capability unknown", qualification.reasonCode, "AVC/HEVC SDR")
    }
}

enum class ApvRawVerdict {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

data class ApvRawEvidence(val stillRaw: ApvEvidenceState, val rawVideo: ApvEvidenceState)

fun verdictForRaw(evidence: ApvRawEvidence): ApvRawVerdict = when (evidence.rawVideo) {
    ApvEvidenceState.VERIFIED -> ApvRawVerdict.SUPPORTED
    ApvEvidenceState.UNKNOWN -> ApvRawVerdict.UNKNOWN
    ApvEvidenceState.UNSUPPORTED -> ApvRawVerdict.UNSUPPORTED
}
