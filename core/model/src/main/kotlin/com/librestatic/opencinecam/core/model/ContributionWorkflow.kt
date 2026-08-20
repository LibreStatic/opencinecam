/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import java.security.MessageDigest

enum class ContributionState {
    DRAFT,
    SUBMITTED,
    REVIEWED,
    SIGNED,
    REVOKED,
    REJECTED,
}

enum class ContributionAction {
    CREATED,
    SUBMITTED,
    REVIEWED,
    SIGNED,
    REVOKED,
    DUPLICATE,
    CAPACITY_EXCEEDED,
    NOT_FOUND,
    INVALID_STATE,
    PRIVACY_REJECTED,
    EVIDENCE_REJECTED,
}

enum class ContributionCheckStatus { PASS, FAIL, NOT_RUN }

data class ContributionCheck(
    val name: String,
    val status: ContributionCheckStatus,
    val reason: String,
)

data class ContributionDraft(
    val contributionId: String,
    val scope: DeviceProfileScope,
    val protocolVersion: String,
    val fixtureId: String,
    val fixtureDigestHex: String,
    val fixturePassed: Boolean,
    val evidence: Map<String, String>,
) {
    init {
        require(contributionId.isNotBlank()) { "contribution ID must not be blank" }
        require(protocolVersion == scope.protocolVersion) { "protocol must match exact profile scope" }
        require(fixtureId.isNotBlank()) { "fixture ID must not be blank" }
        require(evidence.keys.none { it.isBlank() }) { "evidence keys must not be blank" }
        require(evidence.values.none { it.isBlank() }) { "evidence values must not be blank" }
    }
}

data class ContributionRecord(
    val draft: ContributionDraft,
    val state: ContributionState = ContributionState.DRAFT,
    val checks: List<ContributionCheck> = emptyList(),
    val reviewerId: String? = null,
    val signature: ProfileSignature? = null,
    val revocationReason: String? = null,
)

enum class ExportConsent {
    REDACTED_EXPLICIT,
    FULL_EXPLICIT,
}

data class ContributionExport(
    val contributionId: String,
    val scope: DeviceProfileScope,
    val evidence: Map<String, String>,
    val sensitiveIncluded: Boolean,
)

/** Local-only contribution workflow; no network upload or remote trust promotion. */
class ContributionWorkflow(private val maxRecords: Int = 128) {
    private val records = LinkedHashMap<String, ContributionRecord>()

    init {
        require(maxRecords > 0) { "maxRecords must be positive" }
    }

    fun create(draft: ContributionDraft): ContributionAction {
        if (records.containsKey(draft.contributionId)) return ContributionAction.DUPLICATE
        if (records.size >= maxRecords) return ContributionAction.CAPACITY_EXCEEDED
        records[draft.contributionId] = ContributionRecord(draft)
        return ContributionAction.CREATED
    }

    fun validate(contributionId: String): List<ContributionCheck> {
        val record = records[contributionId] ?: return listOf(
            ContributionCheck("record", ContributionCheckStatus.FAIL, "contribution not found"),
        )
        val draft = record.draft
        val checks = buildList {
            add(
                ContributionCheck(
                    "exact-scope",
                    if (draft.protocolVersion == draft.scope.protocolVersion) {
                        ContributionCheckStatus.PASS
                    } else {
                        ContributionCheckStatus.FAIL
                    },
                    "fingerprint/camera/codec/protocol/digest scope is required",
                ),
            )
            add(
                ContributionCheck(
                    "fixture-digest",
                    if (draft.fixtureDigestHex.matches(Regex("[0-9a-fA-F]{64}"))) {
                        ContributionCheckStatus.PASS
                    } else {
                        ContributionCheckStatus.FAIL
                    },
                    "fixture digest must be SHA-256",
                ),
            )
            add(
                ContributionCheck(
                    "fixture-regression",
                    if (draft.fixturePassed) ContributionCheckStatus.PASS else ContributionCheckStatus.FAIL,
                    "fixture must pass before submission",
                ),
            )
            val privateKeys = setOf("serial", "imei", "location", "account", "path", "raw-frame")
            val privateEvidence = draft.evidence.keys.filter { key ->
                privateKeys.any { privateKey -> key.lowercase().contains(privateKey) }
            }
            add(
                ContributionCheck(
                    "privacy",
                    if (privateEvidence.isEmpty()) ContributionCheckStatus.PASS else ContributionCheckStatus.FAIL,
                    if (privateEvidence.isEmpty()) "no direct identifiers or file paths" else "private evidence keys: $privateEvidence",
                ),
            )
        }
        records[contributionId] = record.copy(checks = checks)
        return checks
    }

    fun submit(contributionId: String, consent: ExportConsent): ContributionAction {
        val record = records[contributionId] ?: return ContributionAction.NOT_FOUND
        if (record.state != ContributionState.DRAFT && record.state != ContributionState.REJECTED) {
            return ContributionAction.INVALID_STATE
        }
        val checks = validate(contributionId)
        if (checks.any { it.status == ContributionCheckStatus.FAIL }) {
            records[contributionId] = record.copy(state = ContributionState.REJECTED, checks = checks)
            return if (checks.any { it.name == "privacy" && it.status == ContributionCheckStatus.FAIL }) {
                ContributionAction.PRIVACY_REJECTED
            } else {
                ContributionAction.EVIDENCE_REJECTED
            }
        }
        // Both modes are explicit caller actions; there is no implicit export consent.
        @Suppress("UNUSED_VARIABLE")
        val explicitConsent = consent
        records[contributionId] = records.getValue(contributionId).copy(state = ContributionState.SUBMITTED)
        return ContributionAction.SUBMITTED
    }

    fun review(contributionId: String, reviewerId: String): ContributionAction {
        val record = records[contributionId] ?: return ContributionAction.NOT_FOUND
        if (record.state != ContributionState.SUBMITTED || reviewerId.isBlank()) {
            return ContributionAction.INVALID_STATE
        }
        records[contributionId] = record.copy(state = ContributionState.REVIEWED, reviewerId = reviewerId)
        return ContributionAction.REVIEWED
    }

    fun sign(contributionId: String, keyId: String): ContributionAction {
        val record = records[contributionId] ?: return ContributionAction.NOT_FOUND
        if (record.state != ContributionState.REVIEWED || keyId.isBlank()) {
            return ContributionAction.INVALID_STATE
        }
        val digest = sha256(canonical(record.draft))
        records[contributionId] = record.copy(
            state = ContributionState.SIGNED,
            signature = ProfileSignature("SHA-256", keyId, digest),
        )
        return ContributionAction.SIGNED
    }

    fun revoke(contributionId: String, reason: String): ContributionAction {
        val record = records[contributionId] ?: return ContributionAction.NOT_FOUND
        if (record.state == ContributionState.REVOKED || reason.isBlank()) {
            return ContributionAction.INVALID_STATE
        }
        records[contributionId] = record.copy(
            state = ContributionState.REVOKED,
            revocationReason = reason,
        )
        return ContributionAction.REVOKED
    }

    fun regression(contributionId: String, fixtureBytes: ByteArray): ContributionCheck {
        val record = records[contributionId]
            ?: return ContributionCheck("fixture-regression", ContributionCheckStatus.FAIL, "contribution not found")
        val actual = sha256(fixtureBytes)
        val check = ContributionCheck(
            "fixture-regression",
            if (actual.equals(record.draft.fixtureDigestHex, ignoreCase = true)) {
                ContributionCheckStatus.PASS
            } else {
                ContributionCheckStatus.FAIL
            },
            "fixture digest comparison",
        )
        records[contributionId] = record.copy(checks = record.checks + check)
        return check
    }

    fun export(contributionId: String, consent: ExportConsent): ContributionExport? {
        val record = records[contributionId] ?: return null
        val full = consent == ExportConsent.FULL_EXPLICIT
        val scope = if (full) {
            record.draft.scope
        } else {
            record.draft.scope.copy(
                buildFingerprint = sha256(record.draft.scope.buildFingerprint),
                cameraId = sha256(record.draft.scope.cameraId),
                physicalCameraId = record.draft.scope.physicalCameraId?.let(::sha256),
            )
        }
        val evidence = if (full) {
            record.draft.evidence
        } else {
            record.draft.evidence.mapValues { (_, value) -> sha256(value) }
        }
        return ContributionExport(record.draft.contributionId, scope, evidence, full)
    }

    fun get(contributionId: String): ContributionRecord? = records[contributionId]

    fun clear() = records.clear()

    fun size(): Int = records.size

    private fun canonical(draft: ContributionDraft): String = buildString {
        append(draft.contributionId).append('|')
        append(draft.scope).append('|')
        append(draft.fixtureId).append('|')
        append(draft.fixtureDigestHex).append('|')
        draft.evidence.toSortedMap().forEach { (key, value) -> append(key).append('=').append(value).append(';') }
    }

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(value: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
