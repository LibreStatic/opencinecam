/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

enum class HdrDisplayCapability {
    UNKNOWN,
    UNSUPPORTED,
    SUPPORTED,
}

enum class HdrPreviewMode {
    HDR,
    SDR,
    UNKNOWN,
}

enum class HlgSessionStatus {
    READY,
    UNSUPPORTED,
    UNKNOWN,
}

data class HlgEncoderMetadata(
    val mime: String,
    val profile: String,
    val colorStandard: String,
    val colorTransfer: String,
    val colorRange: String,
) {
    init {
        require(mime.isNotBlank() && profile.isNotBlank()) { "encoder metadata identity is required" }
        require(colorStandard.isNotBlank() && colorTransfer.isNotBlank() && colorRange.isNotBlank()) {
            "encoder color metadata must be explicit"
        }
    }
}

data class HlgSidecarProvenance(
    val evidenceId: String,
    val graph: HlgCandidateGraph,
    val encoder: HlgEncoderMetadata,
    val previewMode: HdrPreviewMode,
    val warningCode: String?,
) {
    init {
        require(evidenceId.isNotBlank()) { "sidecar evidence ID must not be blank" }
    }
}

data class HlgSessionDecision(
    val status: HlgSessionStatus,
    val graph: HlgCandidateGraph?,
    val encoder: HlgEncoderMetadata?,
    val previewMode: HdrPreviewMode,
    val warningCode: String?,
    val provenance: HlgSidecarProvenance?,
)

object HlgSessionConfigurator {
    fun configure(
        decision: HlgDecision,
        display: HdrDisplayCapability,
    ): HlgSessionDecision {
        if (decision.status == HlgDecisionStatus.UNSUPPORTED) {
            return HlgSessionDecision(
                HlgSessionStatus.UNSUPPORTED,
                null,
                null,
                HdrPreviewMode.UNKNOWN,
                decision.reason?.code ?: "hlg-unsupported",
                null,
            )
        }
        if (decision.status == HlgDecisionStatus.UNKNOWN || decision.graph == null) {
            return HlgSessionDecision(
                HlgSessionStatus.UNKNOWN,
                null,
                null,
                HdrPreviewMode.UNKNOWN,
                decision.reason?.code ?: "hlg-evidence-unknown",
                null,
            )
        }
        val encoder = HlgEncoderMetadata(
            mime = decision.graph.codecMime,
            profile = decision.graph.codecProfile,
            colorStandard = decision.graph.colorSpace,
            colorTransfer = decision.graph.transfer,
            colorRange = "LIMITED",
        )
        val (preview, warning) = when (display) {
            HdrDisplayCapability.SUPPORTED -> HdrPreviewMode.HDR to null
            HdrDisplayCapability.UNSUPPORTED -> HdrPreviewMode.SDR to "hdr-display-unsupported"
            HdrDisplayCapability.UNKNOWN -> HdrPreviewMode.UNKNOWN to "hdr-display-unknown"
        }
        val provenance = HlgSidecarProvenance(decision.evidenceId, decision.graph, encoder, preview, warning)
        return HlgSessionDecision(HlgSessionStatus.READY, decision.graph, encoder, preview, warning, provenance)
    }
}

enum class HlgSessionCommandStatus {
    STARTED,
    DUPLICATE,
    CANCELLED,
    CLOSED,
}

class HlgSessionController(private val maxSessions: Int = 4) {
    private val sessions = LinkedHashMap<String, HlgSessionDecision>()
    private val cancelled = HashSet<String>()
    private var closed = false

    init {
        require(maxSessions > 0) { "maxSessions must be positive" }
    }

    fun configure(
        commandId: String,
        decision: HlgDecision,
        display: HdrDisplayCapability,
    ): HlgSessionCommandStatus {
        if (closed) return HlgSessionCommandStatus.CLOSED
        if (cancelled.contains(commandId)) return HlgSessionCommandStatus.CANCELLED
        if (sessions.containsKey(commandId)) return HlgSessionCommandStatus.DUPLICATE
        if (sessions.size >= maxSessions || commandId.isBlank()) return HlgSessionCommandStatus.CLOSED
        sessions[commandId] = HlgSessionConfigurator.configure(decision, display)
        return HlgSessionCommandStatus.STARTED
    }

    fun cancel(commandId: String): HlgSessionCommandStatus {
        if (closed) return HlgSessionCommandStatus.CLOSED
        if (commandId.isBlank() || sessions.containsKey(commandId)) return HlgSessionCommandStatus.CLOSED
        cancelled += commandId
        return HlgSessionCommandStatus.CANCELLED
    }

    fun decision(commandId: String): HlgSessionDecision? = sessions[commandId]

    fun close() {
        sessions.clear()
        cancelled.clear()
        closed = true
    }
}
