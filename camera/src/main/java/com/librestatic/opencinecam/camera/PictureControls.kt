/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult

/**
 * Qualcomm picture controls, discovered by exact vendor key name and clamped to the range the
 * camera advertises. Evidence (Motorola Razr Fold, dumpsys media.camera, camera 0):
 *  - org.codeaurora.qcamera3.saturation.range int32[4] = [min max default step] = [0 10 5 1];
 *    request key ...saturation.use_saturation int32[1]; the latest result reports 5 at template defaults.
 *  - org.codeaurora.qcamera3.sharpness.range int32[2] = [min max] = [0 6], no default advertised;
 *    request key ...sharpness.strength int32[1]; the latest result reports 2 at template defaults.
 * Other Qualcomm devices may differ, so nothing here assumes more than the advertised numbers.
 */
const val SATURATION_RANGE_KEY = "org.codeaurora.qcamera3.saturation.range"
const val SATURATION_REQUEST_KEY = "org.codeaurora.qcamera3.saturation.use_saturation"
const val SHARPNESS_RANGE_KEY = "org.codeaurora.qcamera3.sharpness.range"
const val SHARPNESS_REQUEST_KEY = "org.codeaurora.qcamera3.sharpness.strength"

/**
 * An advertised integer range. [default] is the camera's own neutral when [defaultAdvertised],
 * otherwise only the range midpoint, which a UI may mark but must not treat as the device default.
 */
data class PictureControlRange(val min: Int, val max: Int, val step: Int = 1, val default: Int, val defaultAdvertised: Boolean) {
    init { require(min <= max && step >= 1) { "Invalid picture control range" } }

    /** The highest value on the step grid anchored at [min]; equals [max] unless the step does not divide the span. */
    val top: Int get() = min + (max - min) / step * step

    /** Nearest value on the advertised grid (anchored at [min]) inside the range. */
    fun snap(value: Int): Int {
        val clamped = value.coerceIn(min, top)
        return min + (clamped - min + step / 2) / step * step
    }

    /** Number of selectable positions, min and max included. */
    val positions: Int get() = (top - min) / step + 1

    /** Intermediate stops for a Compose slider over [min]..[top]. */
    val sliderSteps: Int get() = (positions - 2).coerceAtLeast(0)

    /** The request value for a stored selection, or null to keep the template. */
    fun resolve(requested: Int?): Int? {
        requested ?: return null
        val snapped = snap(requested)
        return if (defaultAdvertised && snapped == snap(default)) null else snapped
    }
}

data class PictureControlCapabilities(val saturation: PictureControlRange? = null, val sharpness: PictureControlRange? = null) {
    val any: Boolean get() = saturation != null || sharpness != null
}

/** [min max default step]; a range with nothing to choose is dropped, an out-of-range or missing default degrades to the unadvertised midpoint. */
fun parseSaturationRange(values: IntArray?): PictureControlRange? {
    if (values == null || values.size < 2 || values[0] >= values[1]) return null
    val min = values[0]
    val max = values[1]
    val step = values.getOrNull(3)?.takeIf { it >= 1 } ?: 1
    val advertised = values.getOrNull(2)?.takeIf { it in min..max }
    return PictureControlRange(min, max, step, advertised ?: ((min + max) / 2), advertised != null)
}

/** [min max]; the default is not advertised, so the midpoint is only a neutral marker. */
fun parseSharpnessRange(values: IntArray?): PictureControlRange? {
    if (values == null || values.size < 2 || values[0] >= values[1]) return null
    return PictureControlRange(values[0], values[1], 1, (values[0] + values[1]) / 2, defaultAdvertised = false)
}

/** Sharpness is applied by the edge stage, so it has no effect while EDGE_MODE is OFF (0). */
fun sharpnessEffective(edgeMode: Int?): Boolean = edgeMode != 0

/** Values to put on a request; null clears the vendor key so the template applies. */
data class ResolvedPictureControls(val saturation: Int? = null, val sharpness: Int? = null)

/**
 * LOG, HLG and RAW need neutral processing and constrained HFR keeps template defaults, so
 * [neutralProcessing] suppresses both. [edgeMode] is the EDGE_MODE value being submitted.
 */
fun ImageProcessingSelection.resolvePictureControls(caps: PictureControlCapabilities, edgeMode: Int?, neutralProcessing: Boolean): ResolvedPictureControls {
    if (neutralProcessing) return ResolvedPictureControls()
    return ResolvedPictureControls(
        saturation = caps.saturation?.resolve(saturation),
        sharpness = caps.sharpness?.takeIf { sharpnessEffective(edgeMode) }?.resolve(sharpness),
    )
}

/** What was submitted and what the camera reported for the vendor keys; null when the key is absent. */
data class PictureControlReadback(
    val submittedSaturation: Int? = null, val reportedSaturation: Int? = null,
    val submittedSharpness: Int? = null, val reportedSharpness: Int? = null,
)

/** Vendor keys found by exact name on one camera; every key is null where the camera lacks it. */
internal class PictureControlKeys(
    val saturationRequest: CaptureRequest.Key<IntArray>? = null,
    val saturationResult: CaptureResult.Key<IntArray>? = null,
    val sharpnessRequest: CaptureRequest.Key<IntArray>? = null,
    val sharpnessResult: CaptureResult.Key<IntArray>? = null,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun of(characteristics: CameraCharacteristics, cameraId: String? = null): PictureControlKeys {
            val type = IntArray::class.java
            return PictureControlKeys(
                VendorKeys.request(characteristics, cameraId, SATURATION_REQUEST_KEY, SATURATION_RANGE_KEY, type),
                VendorKeys.result(characteristics, cameraId, SATURATION_REQUEST_KEY, SATURATION_RANGE_KEY, type),
                VendorKeys.request(characteristics, cameraId, SHARPNESS_REQUEST_KEY, SHARPNESS_RANGE_KEY, type),
                VendorKeys.result(characteristics, cameraId, SHARPNESS_REQUEST_KEY, SHARPNESS_RANGE_KEY, type))
        }
    }
}

/** A control is offered only when both its request key and its range key exist and parse. */
@Suppress("UNCHECKED_CAST")
internal fun CameraCharacteristics.pictureControlCapabilities(): PictureControlCapabilities = runCatching {
    fun range(rangeKey: String, requestKey: String, parse: (IntArray?) -> PictureControlRange?): PictureControlRange? {
        if (VendorKeys.request(this, null, requestKey, rangeKey, IntArray::class.java) == null) return null
        return runCatching { parse(VendorKeys.staticInts(this, rangeKey)) }.getOrNull()
    }
    PictureControlCapabilities(
        saturation = range(SATURATION_RANGE_KEY, SATURATION_REQUEST_KEY, ::parseSaturationRange),
        sharpness = range(SHARPNESS_RANGE_KEY, SHARPNESS_REQUEST_KEY, ::parseSharpnessRange),
    )
}.getOrDefault(PictureControlCapabilities())
