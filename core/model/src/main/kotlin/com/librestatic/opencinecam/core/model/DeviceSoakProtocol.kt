// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.core.model

enum class SoakScenario {
    LIFECYCLE,
    FOLD_TRANSITION,
    CAMERA_DISCONNECT,
    THERMAL,
    LOW_SPACE,
    FILE_EVIDENCE,
}

enum class SoakObservationStatus {
    PASS,
    FAIL,
    UNKNOWN,
    NOT_RUN,
}

data class SoakObservation(
    val scenario: SoakScenario,
    val status: SoakObservationStatus,
    val elapsedSeconds: Long,
    val evidenceId: String?,
    val safeMessage: String,
) {
    init {
        require(elapsedSeconds >= 0) { "soak elapsed time must be non-negative" }
        require(safeMessage.isNotBlank()) { "soak observations require a safe message" }
        require(evidenceId == null || evidenceId.length <= 128) { "evidence ID is too long" }
    }
}

data class SoakTarget(
    val fingerprint: String,
    val protocolVersion: Int,
    val profileId: String,
) {
    init {
        require(fingerprint.isNotBlank()) { "device fingerprint is required" }
        require(protocolVersion > 0) { "protocol version must be positive" }
        require(profileId.isNotBlank()) { "profile ID is required" }
    }
}

enum class SoakGateStatus {
    QUALIFIED,
    FAILED,
    NOT_RUN,
}

data class SoakReport(
    val status: SoakGateStatus,
    val target: SoakTarget?,
    val durationSeconds: Long,
    val observations: List<SoakObservation>,
    val missingScenarios: Set<SoakScenario>,
    val failures: List<String>,
) {
    init {
        require(durationSeconds >= 0) { "soak duration must be non-negative" }
        require(observations.size <= SoakProtocol.MAX_OBSERVATIONS) { "too many soak observations" }
    }
}

/** Host-testable part of the physical-device M17 protocol. */
object SoakProtocol {
    const val REQUIRED_DURATION_SECONDS = 30 * 60L
    const val MAX_OBSERVATIONS = 128

    fun evaluate(
        target: SoakTarget?,
        durationSeconds: Long,
        observations: List<SoakObservation>,
    ): SoakReport {
        require(durationSeconds >= 0) { "soak duration must be non-negative" }
        require(observations.size <= MAX_OBSERVATIONS) { "too many soak observations" }
        val unique = observations.map { it.scenario }.toSet()
        val missing = SoakScenario.entries.toSet() - unique
        val failures = buildList {
            if (target == null) add("physical target not identified")
            if (durationSeconds < REQUIRED_DURATION_SECONDS) add("30-minute soak requirement not met")
            if (missing.isNotEmpty()) add("missing scenarios: ${missing.joinToString()}")
            observations.filter { it.status == SoakObservationStatus.FAIL }
                .forEach { add("${it.scenario}: ${it.safeMessage}") }
            observations.filter { it.status == SoakObservationStatus.UNKNOWN || it.status == SoakObservationStatus.NOT_RUN }
                .forEach { add("${it.scenario}: evidence not run or unknown") }
            if (observations.size != unique.size) add("duplicate scenario observations")
        }
        val hasHardFailure = observations.any { it.status == SoakObservationStatus.FAIL }
        val hasUnknown = observations.any {
            it.status == SoakObservationStatus.UNKNOWN || it.status == SoakObservationStatus.NOT_RUN
        }
        val status = when {
            target == null || durationSeconds < REQUIRED_DURATION_SECONDS || missing.isNotEmpty() -> SoakGateStatus.NOT_RUN
            hasHardFailure || observations.size != unique.size -> SoakGateStatus.FAILED
            hasUnknown -> SoakGateStatus.NOT_RUN
            else -> SoakGateStatus.QUALIFIED
        }
        return SoakReport(status, target, durationSeconds, observations.toList(), missing, failures)
    }
}
