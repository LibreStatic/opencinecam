/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational
import com.librestatic.opencinecam.camera.RawVideoConfig

/**
 * Where every frame of a take sits in the RAW output: the configured crop, centred. RAW10 packs four
 * pixels into five bytes, so [left] and [width] are multiples of 4; [top] is even so the frame keeps
 * the sensor's CFA phase (`docs/formats/opencine-raw-v1.md`).
 */
data class RawVideoCrop(val left: Int, val top: Int, val width: Int, val height: Int) {
    init {
        require(left >= 0 && top >= 0 && width > 0 && height > 0) { "invalid crop" }
        require(left % 4 == 0 && width % 4 == 0) { "RAW10 crop left and width must be multiples of 4" }
        require(top % 2 == 0) { "crop top must be even to keep the CFA phase" }
    }

    /** The `crop` metadata value: `left top width height`. */
    fun metadataValue(): String = "$left $top $width $height"

    companion object {
        /** The centred crop of [config] in its `sensorWidth x sensorHeight` RAW output. */
        fun centred(config: RawVideoConfig): RawVideoCrop {
            require(config.cropWidth % 4 == 0) { "RAW10 crop width must be a multiple of 4" }
            val left = (config.sensorWidth - config.cropWidth) / 2 / 4 * 4
            val top = (config.sensorHeight - config.cropHeight) / 2 / 2 * 2
            return RawVideoCrop(left, top, config.cropWidth, config.cropHeight)
        }
    }
}

/**
 * The `METADATA` and `FRAME_METADATA` maps of a RAW video take, with the keys and value formats of
 * `docs/formats/opencine-raw-v1.md`: rationals `n/d` with a positive `d`, lists separated by one
 * space, decimals with `.` and no exponent.
 */
object RawVideoMetadata {
    /** Keys a take writes from its configuration, whatever the camera reports. */
    fun baseValues(
        cameraId: String,
        rawWidth: Int,
        rawHeight: Int,
        crop: RawVideoCrop,
        fps: Int,
        model: String,
        appVersion: String,
    ): Map<String, String> = linkedMapOf(
        "cameraId" to cameraId,
        "sensorWidth" to rawWidth.toString(),
        "sensorHeight" to rawHeight.toString(),
        "crop" to crop.metadataValue(),
        "fps" to "$fps/1",
        "model" to model,
        "appVersion" to appVersion,
    )

    /** The static DNG keys a camera reports; absent keys are left out. */
    fun staticValues(characteristics: CameraCharacteristics): Map<String, String> = staticValues(
        cfaArrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
        blackLevel = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN),
        whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
        colorMatrix1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1),
        colorMatrix2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2),
        forwardMatrix1 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1),
        forwardMatrix2 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2),
        illuminant1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
        illuminant2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
        activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
    )

    fun staticValues(
        cfaArrangement: Int?,
        blackLevel: BlackLevelPattern?,
        whiteLevel: Int?,
        colorMatrix1: ColorSpaceTransform?,
        colorMatrix2: ColorSpaceTransform?,
        forwardMatrix1: ColorSpaceTransform?,
        forwardMatrix2: ColorSpaceTransform?,
        illuminant1: Int?,
        illuminant2: Int?,
        activeArray: Rect?,
    ): Map<String, String> {
        val values = linkedMapOf<String, String>()
        cfa(cfaArrangement)?.let { values["cfa"] = it }
        blackLevel?.let { pattern ->
            // CFA order: (column, row) = (0,0), (1,0), (0,1), (1,1).
            values["blackLevel"] = CFA_ORDER.joinToString(" ") { (col, row) -> pattern.getOffsetForIndex(col, row).toString() }
        }
        whiteLevel?.let { values["whiteLevel"] = it.toString() }
        colorMatrix1?.let { values["colorMatrix1"] = matrix(it) }
        colorMatrix2?.let { values["colorMatrix2"] = matrix(it) }
        forwardMatrix1?.let { values["forwardMatrix1"] = matrix(it) }
        forwardMatrix2?.let { values["forwardMatrix2"] = matrix(it) }
        illuminant1?.let { values["calibrationIlluminant1"] = it.toString() }
        illuminant2?.let { values["calibrationIlluminant2"] = it.toString() }
        activeArray?.let { values["activeArray"] = "${it.left} ${it.top} ${it.right} ${it.bottom}" }
        return values
    }

    /** The `FRAME_METADATA` keys of one capture result (without `sequence`, which the writer adds). */
    fun frameValues(result: CaptureResult): Map<String, String> = frameValues(
        exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
        frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
        neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT),
        ccGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS),
        focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
        dynamicBlackLevel = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
        dynamicWhiteLevel = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL),
    )

    fun frameValues(
        exposureNs: Long?,
        iso: Int?,
        frameDurationNs: Long?,
        neutral: Array<Rational>?,
        ccGains: RggbChannelVector?,
        focusDistance: Float?,
        dynamicBlackLevel: FloatArray?,
        dynamicWhiteLevel: Int?,
    ): Map<String, String> {
        val values = HashMap<String, String>(16)
        exposureNs?.let { values["exposureNs"] = it.toString() }
        iso?.let { values["iso"] = it.toString() }
        frameDurationNs?.let { values["frameDurationNs"] = it.toString() }
        neutral?.takeIf { it.size == 3 }?.let { values["neutral"] = it.joinToString(" ", transform = ::rational) }
        ccGains?.let {
            values["ccGains"] = listOf(it.red, it.greenEven, it.greenOdd, it.blue).joinToString(" ", transform = ::decimal)
        }
        focusDistance?.let { values["focusDistance"] = decimal(it) }
        dynamicBlackLevel?.takeIf { it.size == 4 }?.let { levels ->
            values["dynamicBlackLevel"] = levels.joinToString(" ", transform = ::decimal)
        }
        dynamicWhiteLevel?.let { values["whiteLevel"] = it.toString() }
        return values
    }

    fun cfa(arrangement: Int?): String? = when (arrangement) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO,
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "MONO"
        else -> null
    }

    /** Nine rationals, row-major. */
    fun matrix(transform: ColorSpaceTransform): String =
        (0 until 3).flatMap { row -> (0 until 3).map { col -> rational(transform.getElement(col, row)) } }.joinToString(" ")

    /** `n/d` with a positive denominator; an undefined rational becomes `0/1`. */
    fun rational(value: Rational): String {
        val sign = if (value.denominator < 0) -1 else 1
        val denominator = value.denominator * sign
        return if (denominator == 0) "0/1" else "${value.numerator * sign}/$denominator"
    }

    /** A plain decimal: `.` separator, no exponent, no trailing zeros; non-finite values become `0`. */
    fun decimal(value: Float): String {
        if (!value.isFinite()) return "0"
        val plain = value.toString().toBigDecimal().stripTrailingZeros().toPlainString()
        return if (plain == "-0") "0" else plain
    }

    private val CFA_ORDER = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
}
