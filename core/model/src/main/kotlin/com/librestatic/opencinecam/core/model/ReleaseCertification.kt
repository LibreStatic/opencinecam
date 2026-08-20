/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

enum class CertificationStatus {
    CERTIFIED,
    FAILED,
    NOT_RUN,
}

data class ReleaseArtifactIdentity(
    val artifactId: String,
    val versionCode: Int,
    val variant: String,
    val sha256: String,
    val sourceDigest: String,
) {
    init {
        require(artifactId.isNotBlank() && variant.isNotBlank())
        require(versionCode > 0)
        require(sha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(sourceDigest.matches(Regex("[0-9a-fA-F]{64}")))
    }
}

data class CertificationTarget(
    val fingerprint: String?,
    val apiLevel: Int?,
    val protocolVersion: String,
) {
    init {
        require(protocolVersion.isNotBlank())
    }
}

data class CertificationResult(
    val status: CertificationStatus,
    val artifactId: String,
    val fingerprint: String?,
    val passedChecks: List<String>,
    val failedChecks: List<String>,
    val reasonCode: String,
)

object ReleaseCertification {
    fun evaluate(
        artifact: ReleaseArtifactIdentity?,
        target: CertificationTarget,
        checks: Map<String, Boolean>,
    ): CertificationResult {
        if (artifact == null || target.fingerprint == null || target.apiLevel == null) {
            return CertificationResult(
                CertificationStatus.NOT_RUN,
                artifact?.artifactId ?: "unknown",
                target.fingerprint,
                emptyList(),
                emptyList(),
                "physical-target-or-artifact-missing",
            )
        }
        if (checks.isEmpty()) {
            return CertificationResult(CertificationStatus.NOT_RUN, artifact.artifactId, target.fingerprint,
                emptyList(), emptyList(), "certification-checks-missing")
        }
        val passed = checks.filterValues { it }.keys.sorted()
        val failed = checks.filterValues { !it }.keys.sorted()
        return CertificationResult(
            if (failed.isEmpty()) CertificationStatus.CERTIFIED else CertificationStatus.FAILED,
            artifact.artifactId,
            target.fingerprint,
            passed,
            failed,
            if (failed.isEmpty()) "certification-pass" else "certification-check-failed",
        )
    }
}

enum class CertificationCommandStatus {
    STARTED,
    DUPLICATE,
    CANCELLED,
    CLOSED,
}

class CertificationSession(private val maxRuns: Int = 8) {
    private val results = LinkedHashMap<String, CertificationResult>()
    private val cancelled = HashSet<String>()
    private var closed = false

    init {
        require(maxRuns > 0)
    }

    fun run(
        runId: String,
        artifact: ReleaseArtifactIdentity?,
        target: CertificationTarget,
        checks: Map<String, Boolean>,
    ): CertificationCommandStatus {
        if (closed) return CertificationCommandStatus.CLOSED
        if (cancelled.contains(runId)) return CertificationCommandStatus.CANCELLED
        if (runId.isBlank() || results.containsKey(runId) || results.size >= maxRuns) {
            return if (results.containsKey(runId)) CertificationCommandStatus.DUPLICATE
            else CertificationCommandStatus.CLOSED
        }
        results[runId] = ReleaseCertification.evaluate(artifact, target, checks)
        return CertificationCommandStatus.STARTED
    }

    fun cancel(runId: String): CertificationCommandStatus {
        if (closed) return CertificationCommandStatus.CLOSED
        if (runId.isBlank() || results.containsKey(runId)) return CertificationCommandStatus.CLOSED
        cancelled += runId
        return CertificationCommandStatus.CANCELLED
    }

    fun result(runId: String): CertificationResult? = results[runId]

    fun close() {
        results.clear()
        cancelled.clear()
        closed = true
    }
}
