/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.ln
import kotlin.math.pow

enum class LogProvenance {
    RAW_DERIVED,
    P010_DERIVED,
    ISP_DERIVED,
}

enum class LogEvidenceState {
    UNKNOWN,
    UNSUPPORTED,
    VERIFIED,
}

data class OpenCineLogSpec(
    val version: String = "1.0",
    val domain: String = "scene-linear",
    val gamut: String = "BT.2020",
    val curve: String = "OCLog1",
    val range: String = "full",
) {
    init {
        require(version == "1.0") { "only OpenCine Log v1 is defined" }
        require(domain == "scene-linear" && gamut == "BT.2020" && curve == "OCLog1" && range == "full") {
            "OpenCine Log v1 domain/gamut/curve/range are immutable"
        }
    }
}

data class OpenCineLogGateInput(
    val acceptedSpec: Boolean,
    val rawFixture: LogEvidenceState,
    val p010Fixture: LogEvidenceState,
    val ispFixture: LogEvidenceState,
)

enum class LogGateStatus {
    READY,
    UNSUPPORTED,
    UNKNOWN,
}

data class OpenCineLogGate(
    val status: LogGateStatus,
    val acceptedBranches: Set<LogProvenance>,
    val reasonCode: String,
)

object OpenCineLogGateEvaluator {
    fun evaluate(input: OpenCineLogGateInput): OpenCineLogGate {
        if (!input.acceptedSpec) return OpenCineLogGate(LogGateStatus.UNSUPPORTED, emptySet(), "log-spec-not-accepted")
        val states = mapOf(
            LogProvenance.RAW_DERIVED to input.rawFixture,
            LogProvenance.P010_DERIVED to input.p010Fixture,
            LogProvenance.ISP_DERIVED to input.ispFixture,
        )
        val verified = states.filterValues { it == LogEvidenceState.VERIFIED }.keys
        if (verified.isNotEmpty()) return OpenCineLogGate(LogGateStatus.READY, verified, "log-provenance-verified")
        if (states.values.any { it == LogEvidenceState.UNKNOWN }) {
            return OpenCineLogGate(LogGateStatus.UNKNOWN, emptySet(), "log-provenance-unknown")
        }
        return OpenCineLogGate(LogGateStatus.UNSUPPORTED, emptySet(), "log-provenance-not-verified")
    }
}

data class LogTransformMetadata(
    val implementation: String,
    val version: String,
    val hash: String,
    val tolerance: Double,
) {
    init {
        require(implementation.isNotBlank() && version.isNotBlank() && hash.isNotBlank())
        require(tolerance > 0.0)
    }
}

enum class TransformStatus {
    PASS,
    UNSUPPORTED,
    INVALID,
}

data class LogTransformResult(
    val status: TransformStatus,
    val values: DoubleArray?,
    val metadata: LogTransformMetadata,
    val reasonCode: String,
)

object OpenCineLogCurve {
    private const val MAX_VALUE = 1.0
    private const val BASE = 9.0
    private val metadata = LogTransformMetadata("reference-cpu", "1.0", "oclog1-reference", 1e-6)

    fun encode(linear: Double): Double {
        require(linear in 0.0..MAX_VALUE) { "scene-linear value is outside full range" }
        return ln(1.0 + BASE * linear) / ln(1.0 + BASE)
    }

    fun decode(code: Double): Double {
        require(code in 0.0..MAX_VALUE) { "log code is outside full range" }
        return ((1.0 + BASE).pow(code) - 1.0) / BASE
    }

    fun encodeCpu(values: DoubleArray): LogTransformResult = transform(values, true, metadata)

    fun decodeCpu(values: DoubleArray): LogTransformResult = transform(values, false, metadata)

    fun encodeGpuReference(values: DoubleArray): LogTransformResult = transform(
        values, true, metadata.copy(implementation = "reference-gpu", hash = "oclog1-gpu-reference"),
    )

    private fun transform(values: DoubleArray, forward: Boolean, transformMetadata: LogTransformMetadata): LogTransformResult {
        if (values.any { it.isNaN() || it < 0.0 || it > 1.0 }) {
            return LogTransformResult(TransformStatus.INVALID, null, transformMetadata, "log-input-out-of-range")
        }
        val output = values.map { if (forward) encode(it) else decode(it) }.toDoubleArray()
        return LogTransformResult(TransformStatus.PASS, output, transformMetadata, "log-transform-pass")
    }
}

/**
 * Camera-oriented successor to the OCLog1 planning candidate.
 *
 * Code values 0.10 and 0.90 are reserved for scene black and normalized scene white. This
 * pedestal and headroom keep an unassisted monitor recognizably flat while preserving an exact,
 * monotonic inverse for the recorded BT.2020 signal.
 */
object OpenCineLog2Curve {
    private const val BASE = 50.0
    const val BLACK_CODE = 0.10
    const val WHITE_CODE = 0.90

    fun encode(linear: Double): Double {
        require(linear in 0.0..1.0) { "scene-linear value is outside normalized range" }
        val normalized = ln(1.0 + BASE * linear) / ln(1.0 + BASE)
        return BLACK_CODE + (WHITE_CODE - BLACK_CODE) * normalized
    }

    fun decode(code: Double): Double {
        require(code in BLACK_CODE..WHITE_CODE) { "OCLog2 code is outside its signal range" }
        val normalized = (code - BLACK_CODE) / (WHITE_CODE - BLACK_CODE)
        return ((1.0 + BASE).pow(normalized) - 1.0) / BASE
    }
}

data class LogNumericVector(val input: Double, val expected: Double, val tolerance: Double)

object OpenCineLogVectors {
    fun default(): List<LogNumericVector> = listOf(
        LogNumericVector(0.0, OpenCineLogCurve.encode(0.0), 1e-9),
        LogNumericVector(0.18, OpenCineLogCurve.encode(0.18), 1e-9),
        LogNumericVector(1.0, OpenCineLogCurve.encode(1.0), 1e-9),
    )

    fun validate(vectors: List<LogNumericVector>): Boolean = vectors.all { vector ->
        kotlin.math.abs(OpenCineLogCurve.encode(vector.input) - vector.expected) <= vector.tolerance
    }
}

data class Lut1D(val values: DoubleArray, val metadata: LogTransformMetadata) {
    init {
        require(values.size in 2..65_536) { "LUT must be bounded and have at least two entries" }
        require(values.all { it in 0.0..1.0 }) { "LUT values must be normalized" }
    }

    fun lookup(input: Double): Double {
        require(input in 0.0..1.0)
        val scaled = input * (values.size - 1)
        val lower = scaled.toInt().coerceAtMost(values.size - 2)
        val fraction = scaled - lower
        return values[lower] * (1.0 - fraction) + values[lower + 1] * fraction
    }
}

data class DctlTransform(val scriptName: String, val sha256: String, val signed: Boolean) {
    init {
        require(scriptName.isNotBlank() && sha256.isNotBlank())
    }
}

data class IntegratedLogFrame(
    val provenance: LogProvenance,
    val values: DoubleArray,
    val transform: LogTransformMetadata,
)

data class LogIntegrationResult(
    val status: TransformStatus,
    val frame: IntegratedLogFrame?,
    val reasonCode: String,
)

object LogInputIntegrator {
    fun integrate(
        gate: OpenCineLogGate,
        provenance: LogProvenance,
        normalizedValues: DoubleArray,
    ): LogIntegrationResult {
        if (gate.status == LogGateStatus.UNKNOWN) return LogIntegrationResult(TransformStatus.UNSUPPORTED, null, gate.reasonCode)
        if (gate.status != LogGateStatus.READY || provenance !in gate.acceptedBranches) {
            return LogIntegrationResult(TransformStatus.UNSUPPORTED, null, "provenance-branch-not-verified")
        }
        val encoded = OpenCineLogCurve.encodeCpu(normalizedValues)
        if (encoded.status != TransformStatus.PASS || encoded.values == null) {
            return LogIntegrationResult(TransformStatus.INVALID, null, encoded.reasonCode)
        }
        return LogIntegrationResult(
            TransformStatus.PASS,
            IntegratedLogFrame(provenance, encoded.values, encoded.metadata),
            "log-integration-pass",
        )
    }
}

enum class EditorStatus {
    PASS,
    CLIPPED,
    INVALID,
}

data class LogEditorResult(val status: EditorStatus, val values: DoubleArray?, val reasonCode: String)

object LogNumericalEditor {
    fun applyGainOffset(values: DoubleArray, gain: Double, offset: Double): LogEditorResult {
        if (!gain.isFinite() || !offset.isFinite() || gain < 0.0) {
            return LogEditorResult(EditorStatus.INVALID, null, "editor-parameter-invalid")
        }
        val transformed = values.map { it * gain + offset }
        val clipped = transformed.any { it !in 0.0..1.0 }
        return LogEditorResult(
            if (clipped) EditorStatus.CLIPPED else EditorStatus.PASS,
            transformed.map { it.coerceIn(0.0, 1.0) }.toDoubleArray(),
            if (clipped) "editor-range-clipped" else "editor-pass",
        )
    }

    fun roundTrip(values: DoubleArray, tolerance: Double): Boolean {
        require(tolerance > 0.0)
        val encoded = OpenCineLogCurve.encodeCpu(values)
        val decoded = encoded.values?.let(OpenCineLogCurve::decodeCpu)?.values ?: return false
        return values.indices.all { kotlin.math.abs(values[it] - decoded[it]) <= tolerance }
    }
}

enum class LogQualificationStatus {
    STARTED,
    DUPLICATE,
    CANCELLED,
    CLOSED,
}

class LogQualificationSession(private val maxRuns: Int = 4) {
    private val results = LinkedHashMap<String, Boolean>()
    private val cancelled = HashSet<String>()
    private var closed = false

    init {
        require(maxRuns > 0)
    }

    fun run(runId: String, values: DoubleArray, tolerance: Double): LogQualificationStatus {
        if (closed) return LogQualificationStatus.CLOSED
        if (cancelled.contains(runId)) return LogQualificationStatus.CANCELLED
        if (runId.isBlank() || results.containsKey(runId) || results.size >= maxRuns) {
            return if (results.containsKey(runId)) LogQualificationStatus.DUPLICATE else LogQualificationStatus.CLOSED
        }
        results[runId] = LogNumericalEditor.roundTrip(values, tolerance)
        return LogQualificationStatus.STARTED
    }

    fun cancel(runId: String): LogQualificationStatus {
        if (closed) return LogQualificationStatus.CLOSED
        if (runId.isBlank() || results.containsKey(runId)) return LogQualificationStatus.CLOSED
        cancelled += runId
        return LogQualificationStatus.CANCELLED
    }

    fun result(runId: String): Boolean? = results[runId]

    fun close() {
        results.clear()
        cancelled.clear()
        closed = true
    }
}
