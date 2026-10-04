/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh

/**
 * The Rec.709 view assist, on the CPU: the capture shaders' `viewAssist709` for review.
 * It puts the tier's middle grey at 0.18, rolls luminance off above [KNEE] instead of clipping,
 * and pulls each pixel towards its own luminance until it fits [0, 1], so neither highlights nor
 * out-of-gamut colours change hue per channel.
 */
object OpenCineLogViewAssist {
    const val KNEE = 0.5
    private const val VIEW_GREY = 0.18

    /** Display-linear gain that lands the tier's recorded middle grey (after [sceneGain]) at 0.18. */
    fun viewGain(sourcePath: OpenCineLogSourcePath, sceneGain: Double): Double {
        val grey = when (sourcePath) {
            OpenCineLogSourcePath.HLG10_BT2020 -> OpenCineLogGreyReference.HLG_REFERENCE_GREY_LINEAR
            OpenCineLogSourcePath.SDR_BT709_ISP -> OpenCineLogGreyReference.SDR_REFERENCE_GREY_LINEAR
        }
        return VIEW_GREY / (grey * sceneGain)
    }

    /** Linear BT.709 (may hold negatives from the gamut conversion) to Rec.709 OETF code values. */
    fun encode(displayLinear: DoubleArray, viewGain: Double): DoubleArray {
        val c = DoubleArray(3) { displayLinear[it] * viewGain }
        val y = max(0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2], 1.0e-6)
        val toned = if (y <= KNEE) y else KNEE + (1.0 - KNEE) * tanh((y - KNEE) / (1.0 - KNEE))
        for (i in 0..2) c[i] *= toned / y
        val hi = max(c[0], max(c[1], c[2]))
        val lo = min(c[0], min(c[1], c[2]))
        var fit = 1.0
        if (hi > 1.0) fit = min(fit, (1.0 - toned) / (hi - toned))
        if (lo < 0.0) fit = min(fit, toned / (toned - lo))
        return DoubleArray(3) { rec709Oetf(toned + fit * (c[it] - toned)) }
    }

    private fun rec709Oetf(x: Double): Double {
        val clamped = x.coerceIn(0.0, 1.0)
        return if (clamped < 0.018) 4.5 * clamped else 1.099 * clamped.pow(0.45) - 0.099
    }
}
