/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

enum class QualificationStatus {
    PASS,
    FAIL,
    UNKNOWN,
}

data class Main10FileSignal(
    val mime: String?,
    val profile: String?,
    val colorStandard: String?,
    val colorTransfer: String?,
    val bitDepth: Int?,
)

data class QualificationResult(
    val status: QualificationStatus,
    val reasonCode: String,
    val detail: String,
) {
    init {
        require(reasonCode.isNotBlank() && detail.isNotBlank()) { "qualification result must be explicit" }
    }
}

data class EffectivePrecisionReport(
    val status: QualificationStatus,
    val measuredBits: Int?,
    val sampleCount: Int,
    val reasonCode: String,
)

data class Main10Qualification(
    val fileSignal: QualificationResult,
    val effectivePrecision: EffectivePrecisionReport,
    val promoted: Boolean,
) {
    init {
        require(promoted == (fileSignal.status == QualificationStatus.PASS &&
            effectivePrecision.status == QualificationStatus.PASS)) {
            "promotion requires both independent qualification passes"
        }
    }
}

object Main10FileQualifier {
    fun validateSignal(signal: Main10FileSignal): QualificationResult {
        if (signal.mime == null || signal.profile == null || signal.colorStandard == null ||
            signal.colorTransfer == null || signal.bitDepth == null) {
            return QualificationResult(QualificationStatus.UNKNOWN, "file-signal-unknown", "file metadata is incomplete")
        }
        if (signal.mime != "video/hevc") {
            return QualificationResult(QualificationStatus.FAIL, "mime-not-hevc", "file MIME is not video/hevc")
        }
        if (signal.profile != "Main10") {
            return QualificationResult(QualificationStatus.FAIL, "profile-not-main10", "file profile is not Main10")
        }
        if (signal.colorStandard != "BT.2020" || signal.colorTransfer != "HLG") {
            return QualificationResult(QualificationStatus.FAIL, "color-signaling-mismatch", "file is not BT.2020 HLG")
        }
        if (signal.bitDepth < 10) {
            return QualificationResult(QualificationStatus.FAIL, "effective-bit-depth-below-10", "file bit depth is below 10")
        }
        return QualificationResult(QualificationStatus.PASS, "main10-hlg-signal-pass", "Main10 and HLG signaling match")
    }

    fun measureEffectivePrecision(samples: IntArray?, declaredBits: Int?): EffectivePrecisionReport {
        if (samples == null || declaredBits == null) {
            return EffectivePrecisionReport(QualificationStatus.UNKNOWN, null, 0, "precision-evidence-unknown")
        }
        if (declaredBits < 10 || samples.isEmpty()) {
            return EffectivePrecisionReport(QualificationStatus.FAIL, 0, samples.size, "precision-input-invalid")
        }
        val distinct = samples.toSet().size
        val measured = if (distinct <= 1) 0 else 32 - Integer.numberOfLeadingZeros(distinct - 1)
        return EffectivePrecisionReport(
            status = if (measured >= 10) QualificationStatus.PASS else QualificationStatus.FAIL,
            measuredBits = measured,
            sampleCount = samples.size,
            reasonCode = if (measured >= 10) "effective-depth-pass" else "effective-depth-below-10",
        )
    }

    fun qualify(signal: Main10FileSignal, samples: IntArray?, declaredBits: Int?): Main10Qualification {
        val file = validateSignal(signal)
        val precision = measureEffectivePrecision(samples, declaredBits)
        return Main10Qualification(file, precision,
            file.status == QualificationStatus.PASS && precision.status == QualificationStatus.PASS)
    }
}

enum class QualificationCommandStatus {
    STARTED,
    DUPLICATE,
    CANCELLED,
    STALE,
    CLOSED,
}

class Main10QualificationSession(private val maxCommands: Int = 4) {
    private val results = LinkedHashMap<String, Main10Qualification>()
    private val cancelled = HashSet<String>()
    private var closed = false

    init {
        require(maxCommands > 0) { "maxCommands must be positive" }
    }

    fun qualify(
        commandId: String,
        signal: Main10FileSignal,
        samples: IntArray?,
        declaredBits: Int?,
    ): QualificationCommandStatus {
        if (closed) return QualificationCommandStatus.CLOSED
        if (cancelled.contains(commandId)) return QualificationCommandStatus.CANCELLED
        if (commandId.isBlank() || results.containsKey(commandId) || results.size >= maxCommands) {
            return if (results.containsKey(commandId)) QualificationCommandStatus.DUPLICATE
            else QualificationCommandStatus.STALE
        }
        results[commandId] = Main10FileQualifier.qualify(signal, samples, declaredBits)
        return QualificationCommandStatus.STARTED
    }

    fun cancel(commandId: String): QualificationCommandStatus {
        if (closed) return QualificationCommandStatus.CLOSED
        if (commandId.isBlank() || results.containsKey(commandId)) return QualificationCommandStatus.STALE
        cancelled += commandId
        return QualificationCommandStatus.CANCELLED
    }

    fun result(commandId: String): Main10Qualification? = results[commandId]

    fun close() {
        results.clear()
        cancelled.clear()
        closed = true
    }
}
