/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/**
 * YCbCr to non-linear R'G'B' conversion that the OCLog2 shader applies itself when the GPU exposes
 * raw YCbCr through `GL_EXT_YUV_target`. The default external sampler converts with a driver-chosen
 * matrix and range; on a Snapdragon 8 Gen 3 it used BT.601 limited range for a P010 buffer tagged
 * BT.2020 HLG full range, so the recorded colours depended on the driver rather than the source.
 *
 * The shader computes `rgb = matrix * (yuv - offset)`, where `yuv` is the sampled code value
 * normalized to `[0, 1]` over the buffer's bit depth. [matrix] is column-major for `glUniformMatrix3fv`
 * and already folds the range expansion in.
 */
data class OpenCineLogYcbcrConversion(
    val standard: Standard,
    val fullRange: Boolean,
    val bitDepth: Int,
    /** Whether the dataspace named the matrix and range, or the documented default was used. */
    val fromDataSpace: Boolean,
    val matrix: FloatArray,
    val offset: FloatArray,
) {
    enum class Standard(val kr: Double, val kb: Double) {
        BT601(0.299, 0.114),
        BT709(0.2126, 0.0722),
        BT2020(0.2627, 0.0593),
    }

    /** Stable identity recorded in evidence and sidecars, e.g. `BT2020/full/10-bit`. */
    val label: String get() = "${standard.name}/${if (fullRange) "full" else "limited"}/$bitDepth-bit" +
        if (fromDataSpace) "" else " (default)"

    override fun equals(other: Any?): Boolean = other is OpenCineLogYcbcrConversion &&
        standard == other.standard && fullRange == other.fullRange && bitDepth == other.bitDepth && fromDataSpace == other.fromDataSpace

    override fun hashCode(): Int = listOf(standard, fullRange, bitDepth, fromDataSpace).hashCode()

    companion object {
        // android.hardware.DataSpace bit fields; spelled out so the mapping also runs on host JVMs.
        private const val STANDARD_MASK = 63 shl 16
        private const val STANDARD_BT709 = 1 shl 16
        private const val STANDARD_BT601_625 = 2 shl 16
        private const val STANDARD_BT601_625_UNADJUSTED = 3 shl 16
        private const val STANDARD_BT601_525 = 4 shl 16
        private const val STANDARD_BT601_525_UNADJUSTED = 5 shl 16
        private const val STANDARD_BT2020 = 6 shl 16
        private const val RANGE_MASK = 7 shl 27
        private const val RANGE_FULL = 1 shl 27
        private const val RANGE_LIMITED = 2 shl 27

        /**
         * Conversion for one frame. The HLG tier is a ten-bit camera profile; the SDR tier's ISP
         * output is treated as eight-bit, because the sampler does not report the buffer format.
         * The normalization difference between the two depths is at most 0.3% of full scale. An
         * unspecified standard or range falls back to BT.601 limited, Android's legacy default for
         * camera YCbCr, and the label marks it as a default.
         */
        fun forDataSpace(dataSpace: Int?, sourcePath: OpenCineLogSourcePath): OpenCineLogYcbcrConversion {
            val bitDepth = if (sourcePath == OpenCineLogSourcePath.HLG10_BT2020) 10 else 8
            val standard = when ((dataSpace ?: 0) and STANDARD_MASK) {
                STANDARD_BT709 -> Standard.BT709
                STANDARD_BT601_625, STANDARD_BT601_625_UNADJUSTED, STANDARD_BT601_525, STANDARD_BT601_525_UNADJUSTED -> Standard.BT601
                STANDARD_BT2020 -> Standard.BT2020
                else -> null
            }
            val range = when ((dataSpace ?: 0) and RANGE_MASK) {
                RANGE_FULL -> true
                RANGE_LIMITED -> false
                else -> null
            }
            return create(standard ?: Standard.BT601, range ?: false, bitDepth, standard != null && range != null)
        }

        fun create(standard: Standard, fullRange: Boolean, bitDepth: Int, fromDataSpace: Boolean = true): OpenCineLogYcbcrConversion {
            require(bitDepth in 8..16)
            val maxCode = ((1 shl bitDepth) - 1).toDouble()
            val step = (1 shl (bitDepth - 8)).toDouble()
            val offset: DoubleArray
            val scale: DoubleArray
            if (fullRange) {
                offset = doubleArrayOf(0.0, 128.0 * step / maxCode, 128.0 * step / maxCode)
                scale = doubleArrayOf(1.0, 1.0, 1.0)
            } else {
                offset = doubleArrayOf(16.0 * step / maxCode, 128.0 * step / maxCode, 128.0 * step / maxCode)
                scale = doubleArrayOf(maxCode / (219.0 * step), maxCode / (224.0 * step), maxCode / (224.0 * step))
            }
            val kr = standard.kr
            val kb = standard.kb
            val kg = 1.0 - kr - kb
            // Rows are R, G, B; columns are Y', Cb, Cr after range expansion.
            val rows = arrayOf(
                doubleArrayOf(1.0, 0.0, 2.0 * (1.0 - kr)),
                doubleArrayOf(1.0, -2.0 * kb * (1.0 - kb) / kg, -2.0 * kr * (1.0 - kr) / kg),
                doubleArrayOf(1.0, 2.0 * (1.0 - kb), 0.0),
            )
            val columnMajor = FloatArray(9) { index ->
                val column = index / 3
                val row = index % 3
                (rows[row][column] * scale[column]).toFloat()
            }
            return OpenCineLogYcbcrConversion(standard, fullRange, bitDepth, fromDataSpace, columnMajor, offset.map(Double::toFloat).toFloatArray())
        }
    }
}
