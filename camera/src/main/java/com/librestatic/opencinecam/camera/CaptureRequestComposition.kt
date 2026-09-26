/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CaptureRequest
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

/**
 * The base control blocks Camera2PreviewEngine sets when it creates each request, before its
 * stateful appliers (target fps, manual/pro controls, zoom, torch, white balance, image
 * processing, photo flash plans, high-speed controls) run on the same builder. Unlike [CaptureIntent]
 * composition, these modes reproduce today's requests exactly: no EDGE/NOISE_REDUCTION/stabilization
 * keys, CONTINUOUS_PICTURE where the engine uses it, and nothing at all where it keeps template defaults.
 */
enum class EngineRequestMode {
    /** Regular (photo or video) preview graph repeating request, TEMPLATE_PREVIEW. */
    PREVIEW,
    /** Private photo-flash metering repeating request, TEMPLATE_PREVIEW. */
    PHOTO_PRECAPTURE,
    /** GPU video preview, GPU recording and direct recording repeating requests, TEMPLATE_RECORD. */
    VIDEO_RECORD,
    /** OCLog HLG10/SDR-ISP source repeating request, TEMPLATE_RECORD. */
    LOG_RECORD,
    /** Constrained high-speed preview and GPU HFR preview/recording: template defaults, then high-speed controls. */
    HIGH_SPEED_PREVIEW,
    /** Constrained high-speed recording and OCLog HFR; intent only, then high-speed controls. */
    HIGH_SPEED_RECORD,
    /** Still capture (JPEG/HEIC/DNG, bursts, brackets, accumulation), TEMPLATE_STILL_CAPTURE. */
    STILL_CAPTURE,
    /** Legacy one-second manual still, TEMPLATE_STILL_CAPTURE. */
    LONG_EXPOSURE_STILL,
    /** Bracket metering repeating request over frozen still controls. */
    BRACKET_METERING,
    /** Precapture cancel single request. */
    PRECAPTURE_CANCEL,
}

data class EngineRequestParameters(
    val jpegQuality: Int? = null,
    val jpegOrientationDegrees: Int? = null,
    val sensitivityIso: Int? = null,
    val exposureTimeNs: Long? = null,
)

/** One (key name, value) pair; names are `CaptureRequest` field names, enum values their suffixes. */
data class RequestEntry(val key: String, val value: RequestValue)

/** Ordered base block for [mode]; the engine applies it in place of its former hand-set keys. */
fun CaptureRequestComposer.composeEngineRequest(
    mode: EngineRequestMode,
    parameters: EngineRequestParameters = EngineRequestParameters(),
): List<RequestEntry> {
    fun text(key: String, value: String) = RequestEntry(key, RequestValue.TextValue(value))
    fun <T> required(value: T?, label: String): T = requireNotNull(value) { "$mode requires $label" }
    return when (mode) {
        EngineRequestMode.PREVIEW -> listOf(
            text("CONTROL_MODE", "AUTO"),
            text("CONTROL_AF_MODE", "CONTINUOUS_PICTURE"),
            text("CONTROL_AE_MODE", "ON"),
            text("CONTROL_AWB_MODE", "AUTO"),
        )
        EngineRequestMode.PHOTO_PRECAPTURE -> listOf(
            text("CONTROL_MODE", "AUTO"),
            text("CONTROL_AF_MODE", "CONTINUOUS_PICTURE"),
        )
        EngineRequestMode.VIDEO_RECORD -> listOf(
            text("CONTROL_MODE", "AUTO"),
            text("CONTROL_AF_MODE", "CONTINUOUS_VIDEO"),
            text("CONTROL_AE_MODE", "ON"),
            text("CONTROL_AWB_MODE", "AUTO"),
        )
        EngineRequestMode.LOG_RECORD -> listOf(
            text("CONTROL_MODE", "AUTO"),
            text("CONTROL_CAPTURE_INTENT", "VIDEO_RECORD"),
            text("CONTROL_AF_MODE", "CONTINUOUS_VIDEO"),
            text("CONTROL_AE_MODE", "ON"),
            text("CONTROL_AWB_MODE", "AUTO"),
        )
        EngineRequestMode.HIGH_SPEED_PREVIEW -> emptyList()
        EngineRequestMode.HIGH_SPEED_RECORD -> listOf(text("CONTROL_CAPTURE_INTENT", "VIDEO_RECORD"))
        EngineRequestMode.STILL_CAPTURE -> listOf(
            RequestEntry("JPEG_QUALITY", RequestValue.IntValue(required(parameters.jpegQuality, "JPEG quality").also { require(it in 1..100) })),
            text("CONTROL_MODE", "AUTO"),
            text("CONTROL_AF_MODE", "CONTINUOUS_PICTURE"),
            text("CONTROL_AE_MODE", "ON"),
            RequestEntry("JPEG_ORIENTATION", RequestValue.IntValue(required(parameters.jpegOrientationDegrees, "JPEG orientation"))),
        )
        EngineRequestMode.LONG_EXPOSURE_STILL -> listOf(
            text("CONTROL_AE_MODE", "OFF"),
            text("CONTROL_AF_MODE", "OFF"),
            RequestEntry("SENSOR_SENSITIVITY", RequestValue.IntValue(required(parameters.sensitivityIso, "sensitivity"))),
            RequestEntry("SENSOR_EXPOSURE_TIME", RequestValue.LongValue(required(parameters.exposureTimeNs, "exposure time"))),
            RequestEntry("JPEG_ORIENTATION", RequestValue.IntValue(required(parameters.jpegOrientationDegrees, "JPEG orientation"))),
        )
        EngineRequestMode.BRACKET_METERING -> listOf(text("CONTROL_CAPTURE_INTENT", "PREVIEW"))
        EngineRequestMode.PRECAPTURE_CANCEL -> listOf(text("CONTROL_AE_PRECAPTURE_TRIGGER", "CANCEL"))
    }
}

/**
 * Resolves an engine request enum name to its Camera2 constant. Pure (the constants are
 * compile-time ints), so host tests can prove the numeric values the engine sends.
 */
fun camera2RequestEnum(key: String, name: String): Int = when (key to name) {
    "CONTROL_MODE" to "AUTO" -> CaptureRequest.CONTROL_MODE_AUTO
    "CONTROL_AF_MODE" to "OFF" -> CaptureRequest.CONTROL_AF_MODE_OFF
    "CONTROL_AF_MODE" to "CONTINUOUS_PICTURE" -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    "CONTROL_AF_MODE" to "CONTINUOUS_VIDEO" -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
    "CONTROL_AE_MODE" to "OFF" -> CaptureRequest.CONTROL_AE_MODE_OFF
    "CONTROL_AE_MODE" to "ON" -> CaptureRequest.CONTROL_AE_MODE_ON
    "CONTROL_AWB_MODE" to "AUTO" -> CaptureRequest.CONTROL_AWB_MODE_AUTO
    "CONTROL_CAPTURE_INTENT" to "PREVIEW" -> CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
    "CONTROL_CAPTURE_INTENT" to "VIDEO_RECORD" -> CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
    "CONTROL_AE_PRECAPTURE_TRIGGER" to "CANCEL" -> CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL
    else -> throw IllegalArgumentException("No Camera2 constant for $key=$name")
}
