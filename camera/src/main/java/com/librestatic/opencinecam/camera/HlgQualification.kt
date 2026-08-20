/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

enum class HlgCapabilityState {
    UNKNOWN,
    UNSUPPORTED,
    SUPPORTED,
}

data class HlgCapabilityReport(
    val apiLevel: Int?,
    val cameraHlg10: HlgCapabilityState,
    val cameraBt2020: HlgCapabilityState,
    val hevcMain10Surface: HlgCapabilityState,
    val evidenceId: String,
) {
    init {
        require(evidenceId.isNotBlank()) { "HLG evidence ID must not be blank" }
    }
}

enum class HlgDecisionStatus {
    CANDIDATE,
    UNSUPPORTED,
    UNKNOWN,
}

data class HlgUnsupportedReason(
    val code: String,
    val message: String,
) {
    init {
        require(code.isNotBlank() && message.isNotBlank()) { "HLG reason must be stable and non-blank" }
    }
}

data class HlgCandidateGraph(
    val dynamicRange: String = "HLG10",
    val colorSpace: String = "BT.2020",
    val transfer: String = "HLG",
    val codecMime: String = "video/hevc",
    val codecProfile: String = "Main10",
    val input: String = "Surface",
)

data class HlgDecision(
    val status: HlgDecisionStatus,
    val graph: HlgCandidateGraph?,
    val reason: HlgUnsupportedReason?,
    val evidenceId: String,
) {
    init {
        require(evidenceId.isNotBlank()) { "HLG evidence ID must not be blank" }
        require(status == HlgDecisionStatus.CANDIDATE == (graph != null)) {
            "candidate graph must exist only for candidate decisions"
        }
        require(status == HlgDecisionStatus.CANDIDATE == (reason == null)) {
            "candidate decision cannot carry an unsupported reason"
        }
    }
}

object HlgDecisionTable {
    fun evaluate(report: HlgCapabilityReport): HlgDecision {
        val api = report.apiLevel ?: return unknown(report, "api-level-unknown", "API level evidence is unknown")
        if (api < 33) return unsupported(report, "api-level-below-33", "HLG10 recording requires API 33 or newer")
        return when {
            report.cameraHlg10 == HlgCapabilityState.UNKNOWN ->
                unknown(report, "camera-hlg10-unknown", "Camera2 HLG10 capability evidence is unknown")
            report.cameraHlg10 == HlgCapabilityState.UNSUPPORTED ->
                unsupported(report, "camera-hlg10-unsupported", "Camera2 does not advertise HLG10")
            report.cameraBt2020 == HlgCapabilityState.UNKNOWN ->
                unknown(report, "camera-bt2020-unknown", "Camera2 BT.2020 color evidence is unknown")
            report.cameraBt2020 == HlgCapabilityState.UNSUPPORTED ->
                unsupported(report, "camera-bt2020-unsupported", "Camera2 does not advertise BT.2020")
            report.hevcMain10Surface == HlgCapabilityState.UNKNOWN ->
                unknown(report, "hevc-main10-surface-unknown", "HEVC Main10 Surface encoder evidence is unknown")
            report.hevcMain10Surface == HlgCapabilityState.UNSUPPORTED ->
                unsupported(report, "hevc-main10-surface-unsupported", "No qualifying HEVC Main10 Surface encoder")
            else -> HlgDecision(HlgDecisionStatus.CANDIDATE, HlgCandidateGraph(), null, report.evidenceId)
        }
    }

    private fun unsupported(report: HlgCapabilityReport, code: String, message: String) =
        HlgDecision(HlgDecisionStatus.UNSUPPORTED, null, HlgUnsupportedReason(code, message), report.evidenceId)

    private fun unknown(report: HlgCapabilityReport, code: String, message: String) =
        HlgDecision(HlgDecisionStatus.UNKNOWN, null, HlgUnsupportedReason(code, message), report.evidenceId)
}

enum class HlgCommandStatus {
    STARTED,
    DUPLICATE,
    CANCELLED,
    STALE,
    CLOSED,
}

/** Bounded command/evidence lifecycle; no decision is replayed after close. */
class HlgDecisionSession(private val maxCommands: Int = 8) {
    private val commands = LinkedHashMap<String, HlgDecision>()
    private val cancelled = HashSet<String>()
    private var closed = false

    init {
        require(maxCommands > 0) { "maxCommands must be positive" }
    }

    fun evaluate(commandId: String, report: HlgCapabilityReport): HlgCommandStatus {
        if (closed) return HlgCommandStatus.CLOSED
        if (commandId.isBlank()) return HlgCommandStatus.STALE
        if (cancelled.contains(commandId)) return HlgCommandStatus.CANCELLED
        if (commands.containsKey(commandId)) return HlgCommandStatus.DUPLICATE
        if (commands.size >= maxCommands) return HlgCommandStatus.STALE
        commands[commandId] = HlgDecisionTable.evaluate(report)
        return HlgCommandStatus.STARTED
    }

    fun decision(commandId: String): HlgDecision? = commands[commandId]

    fun cancel(commandId: String): HlgCommandStatus {
        if (closed) return HlgCommandStatus.CLOSED
        if (commandId.isBlank() || commands.containsKey(commandId)) return HlgCommandStatus.STALE
        cancelled += commandId
        return HlgCommandStatus.CANCELLED
    }

    fun close() {
        commands.clear()
        cancelled.clear()
        closed = true
    }
}
