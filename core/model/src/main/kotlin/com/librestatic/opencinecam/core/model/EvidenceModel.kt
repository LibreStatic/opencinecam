/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import java.time.Instant

/** Evidence stages are ordered only for one exact graph and validity key. */
enum class EvidenceStage(val ordinalValue: Int) {
    ADVERTISED(0),
    CANDIDATE(1),
    SESSION_CREATED(2),
    FIRST_FRAME_RECEIVED(3),
    RECORDED(4),
    FILE_VERIFIED(5),
    SUSTAINED(6),
    EMPIRICALLY_VERIFIED(7),
    CERTIFIED(8),
    FAILED(-1),
}

enum class EvidenceStatus {
    PASS,
    FAIL,
    STALE,
    NOT_RUN,
}

/** Stable IDs are opaque to callers and cannot be empty or whitespace-only. */
@JvmInline
value class GraphId(val value: String) {
    init {
        require(value.isNotBlank()) { "graph ID must not be blank" }
    }
}

@JvmInline
value class ObservationId(val value: String) {
    init {
        require(value.isNotBlank()) { "observation ID must not be blank" }
    }
}

@JvmInline
value class EvidenceId(val value: String) {
    init {
        require(value.isNotBlank()) { "evidence ID must not be blank" }
    }
}

sealed interface Knowledge<out T> {
    data class Known<T>(val value: T, val unit: String? = null) : Knowledge<T> {
        init {
            require(unit == null || unit.isNotBlank()) { "unit must not be blank" }
        }
    }

    data object Unknown : Knowledge<Nothing>

    data class Unsupported(val reason: String) : Knowledge<Nothing> {
        init {
            require(reason.isNotBlank()) { "unsupported reason must not be blank" }
        }
    }
}

data class CacheValidityKey(
    val schemaMajor: Int,
    val protocolVersion: String,
    val appProbeVersion: String,
    val buildFingerprint: String,
    val characteristicsDigest: String,
    val cameraId: String,
    val physicalCameraId: String?,
    val codecName: String?,
    val graphId: GraphId,
) {
    init {
        require(schemaMajor > 0) { "schema major must be positive" }
        require(protocolVersion.isNotBlank()) { "protocol version must not be blank" }
        require(appProbeVersion.isNotBlank()) { "app probe version must not be blank" }
        require(buildFingerprint.isNotBlank()) { "build fingerprint must not be blank" }
        require(characteristicsDigest.isNotBlank()) { "characteristics digest must not be blank" }
        require(cameraId.isNotBlank()) { "camera ID must not be blank" }
        require(physicalCameraId == null || physicalCameraId.isNotBlank()) {
            "physical camera ID must not be blank"
        }
        require(codecName == null || codecName.isNotBlank()) { "codec name must not be blank" }
    }
}

data class CapabilityEvidence<T>(
    val id: EvidenceId,
    val stage: EvidenceStage,
    val status: EvidenceStatus,
    val source: String,
    val observedAt: Instant,
    val protocolVersion: String,
    val graphId: GraphId,
    val validityKey: CacheValidityKey,
    val value: Knowledge<T>,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(source.isNotBlank()) { "evidence source must not be blank" }
        require(protocolVersion.isNotBlank()) { "evidence protocol must not be blank" }
        require(details.keys.none { it.isBlank() }) { "evidence detail keys must not be blank" }
        require(details.values.none { it.isBlank() }) { "evidence detail values must not be blank" }
    }

    fun staleFor(current: CacheValidityKey): CapabilityEvidence<T> =
        if (validityKey == current) this else copy(status = EvidenceStatus.STALE)
}

sealed interface ObservationValue {
    data class Text(val value: String) : ObservationValue
    data class Number(val value: Double, val unit: String? = null) : ObservationValue
    data class BooleanValue(val value: Boolean) : ObservationValue
    data object Unknown : ObservationValue
}

data class Observation(
    val id: ObservationId,
    val category: String,
    val observedAt: Instant,
    val source: String,
    val graphId: GraphId,
    val value: ObservationValue,
) {
    init {
        require(category.isNotBlank()) { "observation category must not be blank" }
        require(source.isNotBlank()) { "observation source must not be blank" }
    }
}

/** Immutable append-only evidence trail; stale entries are retained. */
data class EvidenceTrail<T>(val entries: List<CapabilityEvidence<T>> = emptyList()) {
    init {
        require(entries.zipWithNext().none { (a, b) -> a.observedAt.isAfter(b.observedAt) }) {
            "evidence entries must be ordered by observation time"
        }
    }

    fun append(next: CapabilityEvidence<T>): EvidenceTrail<T> {
        val previous = entries.lastOrNull { it.graphId == next.graphId && it.validityKey == next.validityKey }
        if (previous != null && previous.status == EvidenceStatus.PASS && next.status == EvidenceStatus.PASS) {
            require(
                next.stage == EvidenceStage.FAILED ||
                    next.stage.ordinalValue >= previous.stage.ordinalValue,
            ) { "evidence stage cannot move backwards for an unchanged graph" }
        }
        return copy(entries = entries + next)
    }
}
