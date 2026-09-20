/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Knowledge
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure

enum class ExposureMode { AUTO, MANUAL, ISO_PRIORITY, SHUTTER_PRIORITY }
enum class FocusMode { CONTINUOUS_VIDEO, AUTO, MANUAL }
enum class WhiteBalanceMode { AUTO, MANUAL }
enum class StabilizationMode { OFF, VIDEO, OPTICAL }
enum class IspMode { DEFAULT, HIGH_QUALITY, FAST, OFF }

data class CaptureIntent(
    val exposureMode: ExposureMode = ExposureMode.AUTO,
    val sensitivityIso: Int? = null,
    val exposureTimeNs: Long? = null,
    val frameDurationNs: Long? = null,
    val focusMode: FocusMode = FocusMode.CONTINUOUS_VIDEO,
    val focusDistanceDiopters: Float? = null,
    val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val manualGains: List<Float>? = null,
    val manualColorTransform: List<Int>? = null,
    val stabilization: StabilizationMode = StabilizationMode.OFF,
    val ispMode: IspMode = IspMode.DEFAULT,
    val noiseReductionMode: IspMode? = null,
    val edgeMode: IspMode? = null,
) {
    init {
        require(sensitivityIso == null || sensitivityIso > 0)
        require(exposureTimeNs == null || exposureTimeNs > 0)
        require(frameDurationNs == null || frameDurationNs > 0)
        require(focusDistanceDiopters == null || focusDistanceDiopters >= 0f)
        require(manualGains == null || manualGains.size == 3)
        require(manualColorTransform == null || manualColorTransform.size == 9)
    }
}

data class CaptureRequestCapabilities(
    val manualSensor: Knowledge<Boolean>,
    val manualPostProcessing: Knowledge<Boolean>,
    val sensitivityIso: Knowledge<IntRange>,
    val exposureTimeNs: Knowledge<LongRange>,
    val frameDurationNs: Knowledge<LongRange>,
    val focusDistanceDiopters: Knowledge<ClosedFloatingPointRange<Float>>,
    val videoStabilization: Knowledge<Boolean>,
    val aePriorityModes: Knowledge<Set<ExposureMode>> = Knowledge.Unknown,
    val opticalStabilization: Knowledge<Boolean> = Knowledge.Unknown,
    val noiseReductionModes: Knowledge<Set<IspMode>> = Knowledge.Unknown,
    val edgeModes: Knowledge<Set<IspMode>> = Knowledge.Unknown,
)

sealed interface RequestValue {
    data class IntValue(val value: Int) : RequestValue
    data class LongValue(val value: Long) : RequestValue
    data class FloatValue(val value: Float) : RequestValue
    data class BooleanValue(val value: Boolean) : RequestValue
    data class IntListValue(val value: List<Int>) : RequestValue
    data class FloatListValue(val value: List<Float>) : RequestValue
    data class TextValue(val value: String) : RequestValue
}

data class ComposedCaptureRequest(
    val values: Map<String, RequestValue>,
    val disclosures: List<String> = emptyList(),
) {
    init { require(values.keys.all { it.isNotBlank() }) }
}

sealed interface CaptureRequestComposition {
    data class Accepted(val request: ComposedCaptureRequest) : CaptureRequestComposition
    data class Rejected(val failure: StableFailure) : CaptureRequestComposition
}

enum class CompositionPolicy { STRICT, ADAPTIVE }

/**
 * Pure deterministic translation of immutable capture intent. The Android adapter can apply
 * the resulting names/values to CaptureRequest.Builder without embedding policy in the UI.
 */
class CaptureRequestComposer(
    private val policy: CompositionPolicy = CompositionPolicy.STRICT,
    private val correlationId: String = "capture-request",
) {
    fun compose(intent: CaptureIntent, capabilities: CaptureRequestCapabilities): CaptureRequestComposition {
        val values = linkedMapOf<String, RequestValue>()
        val disclosures = mutableListOf<String>()

        when (intent.exposureMode) {
            ExposureMode.AUTO -> {
                if (intent.sensitivityIso != null || intent.exposureTimeNs != null || intent.frameDurationNs != null) {
                    return reject(FailureCode.INVALID_COMMAND, "Manual exposure values conflict with automatic exposure.")
                }
                values["CONTROL_AE_MODE"] = RequestValue.TextValue("ON")
            }
            ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY -> {
                val modes = capabilities.aePriorityModes
                if (modes is Knowledge.Unknown) return reject(FailureCode.UNKNOWN_CAPABILITY, "Native AE priority modes are unknown.")
                if (modes !is Knowledge.Known || intent.exposureMode !in modes.value) return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Native AE priority mode is unsupported.")
                if (intent.frameDurationNs != null) return reject(FailureCode.INVALID_COMMAND, "AE priority owns frame duration.")
                values["CONTROL_MODE"] = RequestValue.TextValue("AUTO")
                values["CONTROL_AE_MODE"] = RequestValue.TextValue("ON")
                if (intent.exposureMode == ExposureMode.ISO_PRIORITY) {
                    if (intent.exposureTimeNs != null) return reject(FailureCode.INVALID_COMMAND, "ISO priority owns exposure time.")
                    if (capabilities.sensitivityIso is Knowledge.Unknown) return reject(FailureCode.UNKNOWN_CAPABILITY, "Sensitivity range is unknown.")
                    val iso = intent.sensitivityIso ?: return reject(FailureCode.INVALID_COMMAND, "ISO priority requires sensitivity.")
                    val resolved = resolveRange("sensitivity", iso, capabilities.sensitivityIso, disclosures) ?: return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Sensitivity is unavailable or out of range.")
                    values["CONTROL_AE_PRIORITY_MODE"] = RequestValue.TextValue("SENSOR_SENSITIVITY_PRIORITY")
                    values["SENSOR_SENSITIVITY"] = RequestValue.IntValue(resolved)
                } else {
                    if (intent.sensitivityIso != null) return reject(FailureCode.INVALID_COMMAND, "Shutter priority owns sensitivity.")
                    if (capabilities.exposureTimeNs is Knowledge.Unknown) return reject(FailureCode.UNKNOWN_CAPABILITY, "Exposure time range is unknown.")
                    val exposure = intent.exposureTimeNs ?: return reject(FailureCode.INVALID_COMMAND, "Shutter priority requires exposure time.")
                    val resolved = resolveRange("exposure time", exposure, capabilities.exposureTimeNs, disclosures) ?: return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Exposure time is unavailable or out of range.")
                    values["CONTROL_AE_PRIORITY_MODE"] = RequestValue.TextValue("SENSOR_EXPOSURE_TIME_PRIORITY")
                    values["SENSOR_EXPOSURE_TIME"] = RequestValue.LongValue(resolved)
                }
            }
            ExposureMode.MANUAL -> {
                val manual = requireKnownTrue(capabilities.manualSensor, "Manual sensor capability") ?: return reject(FailureCode.UNKNOWN_CAPABILITY, "Manual sensor capability is unknown.")
                if (!manual) return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Manual sensor control is unsupported.")
                val iso = intent.sensitivityIso ?: return reject(FailureCode.INVALID_COMMAND, "Manual exposure requires sensitivity.")
                val exposure = intent.exposureTimeNs ?: return reject(FailureCode.INVALID_COMMAND, "Manual exposure requires exposure time.")
                val frame = intent.frameDurationNs ?: return reject(FailureCode.INVALID_COMMAND, "Manual exposure requires frame duration.")
                if (capabilities.sensitivityIso is Knowledge.Unknown || capabilities.exposureTimeNs is Knowledge.Unknown || capabilities.frameDurationNs is Knowledge.Unknown) {
                    return reject(FailureCode.UNKNOWN_CAPABILITY, "Manual exposure ranges are unknown.")
                }
                if (capabilities.sensitivityIso is Knowledge.Unsupported || capabilities.exposureTimeNs is Knowledge.Unsupported || capabilities.frameDurationNs is Knowledge.Unsupported) {
                    return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Manual exposure ranges are unsupported.")
                }
                val resolvedIso = resolveRange("sensitivity", iso, capabilities.sensitivityIso, disclosures) ?: return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Sensitivity is outside the advertised range.")
                val resolvedExposure = resolveRange("exposure time", exposure, capabilities.exposureTimeNs, disclosures) ?: return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Exposure time is outside the advertised range.")
                val resolvedFrame = resolveRange("frame duration", frame, capabilities.frameDurationNs, disclosures) ?: return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Frame duration is outside the advertised range.")
                if (resolvedFrame < resolvedExposure) return reject(FailureCode.INVALID_COMMAND, "Frame duration must not be shorter than exposure time.")
                values["CONTROL_AE_MODE"] = RequestValue.TextValue("OFF")
                values["SENSOR_SENSITIVITY"] = RequestValue.IntValue(resolvedIso)
                values["SENSOR_EXPOSURE_TIME"] = RequestValue.LongValue(resolvedExposure)
                values["SENSOR_FRAME_DURATION"] = RequestValue.LongValue(resolvedFrame)
            }
        }

        when (intent.focusMode) {
            FocusMode.MANUAL -> {
                val distance = intent.focusDistanceDiopters ?: return reject(FailureCode.INVALID_COMMAND, "Manual focus requires distance.")
                when (capabilities.focusDistanceDiopters) {
                    Knowledge.Unknown -> return reject(FailureCode.UNKNOWN_CAPABILITY, "Focus distance range is unknown.")
                    is Knowledge.Unsupported -> return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Focus distance is unsupported.")
                    is Knowledge.Known -> Unit
                }
                val resolved = resolveRange("focus distance", distance, capabilities.focusDistanceDiopters, disclosures) ?: return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Focus distance is outside the advertised range.")
                values["CONTROL_AF_MODE"] = RequestValue.TextValue("OFF")
                values["LENS_FOCUS_DISTANCE"] = RequestValue.FloatValue(resolved)
            }
            FocusMode.AUTO -> values["CONTROL_AF_MODE"] = RequestValue.TextValue("AUTO")
            FocusMode.CONTINUOUS_VIDEO -> values["CONTROL_AF_MODE"] = RequestValue.TextValue("CONTINUOUS_VIDEO")
        }

        when (intent.whiteBalanceMode) {
            WhiteBalanceMode.AUTO -> {
                if (intent.manualGains != null || intent.manualColorTransform != null) return reject(FailureCode.INVALID_COMMAND, "Manual white-balance values conflict with automatic white balance.")
                values["CONTROL_AWB_MODE"] = RequestValue.TextValue("AUTO")
            }
            WhiteBalanceMode.MANUAL -> {
                val manual = requireKnownTrue(capabilities.manualPostProcessing, "Manual post-processing capability") ?: return reject(FailureCode.UNKNOWN_CAPABILITY, "Manual post-processing capability is unknown.")
                if (!manual) return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Manual white balance is unsupported.")
                val gains = intent.manualGains ?: return reject(FailureCode.INVALID_COMMAND, "Manual white balance requires gains.")
                val transform = intent.manualColorTransform ?: return reject(FailureCode.INVALID_COMMAND, "Manual white balance requires a color transform.")
                values["CONTROL_AWB_MODE"] = RequestValue.TextValue("OFF")
                values["COLOR_CORRECTION_GAINS"] = RequestValue.FloatListValue(gains.toList())
                values["COLOR_CORRECTION_TRANSFORM"] = RequestValue.IntListValue(transform.toList())
            }
        }

        when (intent.stabilization) {
            StabilizationMode.OPTICAL -> {
                val support = requireKnownTrue(capabilities.opticalStabilization, "Optical stabilization")
                    ?: return reject(FailureCode.UNKNOWN_CAPABILITY, "Optical stabilization capability is unknown.")
                if (!support) return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Optical stabilization is unsupported.")
                values["LENS_OPTICAL_STABILIZATION_MODE"] = RequestValue.TextValue("ON")
                values["CONTROL_VIDEO_STABILIZATION_MODE"] = RequestValue.TextValue("OFF")
            }
            StabilizationMode.OFF -> {
                values["CONTROL_VIDEO_STABILIZATION_MODE"] = RequestValue.TextValue("OFF")
                if ((capabilities.opticalStabilization as? Knowledge.Known)?.value == true) values["LENS_OPTICAL_STABILIZATION_MODE"] = RequestValue.TextValue("OFF")
            }
            StabilizationMode.VIDEO -> when (val support = capabilities.videoStabilization) {
                Knowledge.Unknown -> return reject(FailureCode.UNKNOWN_CAPABILITY, "Video stabilization capability is unknown.")
                is Knowledge.Unsupported -> return reject(FailureCode.UNSUPPORTED_CAPABILITY, support.reason)
                is Knowledge.Known -> if (support.value) {
                    values["CONTROL_VIDEO_STABILIZATION_MODE"] = RequestValue.TextValue("VIDEO")
                    if ((capabilities.opticalStabilization as? Knowledge.Known)?.value == true) values["LENS_OPTICAL_STABILIZATION_MODE"] = RequestValue.TextValue("OFF")
                } else if (policy == CompositionPolicy.ADAPTIVE) {
                    values["CONTROL_VIDEO_STABILIZATION_MODE"] = RequestValue.TextValue("OFF")
                    disclosures += "Video stabilization was disabled because it is unsupported."
                } else return reject(FailureCode.UNSUPPORTED_CAPABILITY, "Video stabilization is unsupported.")
            }
        }
        for ((key, mode, support) in listOf(
            Triple("EDGE_MODE", intent.edgeMode, capabilities.edgeModes),
            Triple("NOISE_REDUCTION_MODE", intent.noiseReductionMode, capabilities.noiseReductionModes),
        )) {
            val explicitMode = mode ?: intent.ispMode.takeIf { it == IspMode.OFF }
            if (explicitMode != null && explicitMode != IspMode.DEFAULT) {
                if (support is Knowledge.Unknown) return reject(FailureCode.UNKNOWN_CAPABILITY, "$key capability is unknown.")
                if (support !is Knowledge.Known || explicitMode !in support.value) return reject(FailureCode.UNSUPPORTED_CAPABILITY, "$key mode is unsupported.")
            }
            values[key] = RequestValue.TextValue((mode ?: intent.ispMode).name)
        }
        return CaptureRequestComposition.Accepted(ComposedCaptureRequest(values.toMap(), disclosures.toList()))
    }

    private fun requireKnownTrue(value: Knowledge<Boolean>, label: String): Boolean? = when (value) {
        Knowledge.Unknown -> null
        is Knowledge.Unsupported -> false
        is Knowledge.Known -> value.value
    }

    private fun resolveRange(label: String, value: Int, range: Knowledge<IntRange>, disclosures: MutableList<String>): Int? = when (range) {
        Knowledge.Unknown -> null
        is Knowledge.Unsupported -> null
        is Knowledge.Known -> if (value in range.value) value else if (policy == CompositionPolicy.ADAPTIVE) value.coerceIn(range.value).also { disclosures += "$label was clamped to the advertised range." } else null
    }

    private fun resolveRange(label: String, value: Long, range: Knowledge<LongRange>, disclosures: MutableList<String>): Long? = when (range) {
        Knowledge.Unknown -> null
        is Knowledge.Unsupported -> null
        is Knowledge.Known -> if (value in range.value) value else if (policy == CompositionPolicy.ADAPTIVE) value.coerceIn(range.value).also { disclosures += "$label was clamped to the advertised range." } else null
    }

    private fun resolveRange(label: String, value: Float, range: Knowledge<ClosedFloatingPointRange<Float>>, disclosures: MutableList<String>): Float? = when (range) {
        Knowledge.Unknown -> null
        is Knowledge.Unsupported -> null
        is Knowledge.Known -> if (value in range.value) value else if (policy == CompositionPolicy.ADAPTIVE) value.coerceIn(range.value).also { disclosures += "$label was clamped to the advertised range." } else null
    }

    private fun reject(code: FailureCode, message: String) = CaptureRequestComposition.Rejected(
        StableFailure(
            component = "capture-request",
            code = code,
            severity = if (code == FailureCode.UNKNOWN_CAPABILITY) FailureSeverity.WARNING else FailureSeverity.ERROR,
            recoverability = if (code == FailureCode.UNSUPPORTED_CAPABILITY) Recoverability.UNSUPPORTED else Recoverability.USER_ACTION,
            correlationId = correlationId,
            userMessage = message,
        ),
    )
}
