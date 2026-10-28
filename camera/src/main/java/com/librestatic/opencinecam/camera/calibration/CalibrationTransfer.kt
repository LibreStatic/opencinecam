/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** The display-referred curve the frames carry. */
enum class TransferCurve { SDR, HLG }

/**
 * Code <-> scene-linear conversion for calibration. The SDR decode and encode are the exact
 * formulas of the GL shaders (`inverseRec709` and `rec709Oetf` in OpenCineLogGpuPipeline), and the
 * HLG decode is its `inverseHlg`, so a correction solved here matches the one applied on the GPU.
 * The HLG forward is the BT.2100 OETF, the exact inverse of `inverseHlg`.
 */
object CalibrationTransfer {
    private const val HLG_A = 0.17883277
    private const val HLG_B = 0.28466892
    private const val HLG_C = 0.55991073

    /** Entries of the linear -> code table used by [encodeLut]. */
    const val ENCODE_LUT_SIZE = 4096

    private val decodeSdr = FloatArray(256) { toLinear(TransferCurve.SDR, it / 255f) }
    private val decodeHlg = FloatArray(256) { toLinear(TransferCurve.HLG, it / 255f) }
    private val encodeSdr = FloatArray(ENCODE_LUT_SIZE) { toCode(TransferCurve.SDR, lutLinear(it)) * 255f }
    private val encodeHlg = FloatArray(ENCODE_LUT_SIZE) { toCode(TransferCurve.HLG, lutLinear(it)) * 255f }

    /** 256-entry 8-bit code -> scene-linear table. */
    fun decodeLut(curve: TransferCurve): FloatArray = if (curve == TransferCurve.SDR) decodeSdr else decodeHlg

    private fun lutLinear(i: Int): Float { val s = i / (ENCODE_LUT_SIZE - 1f); return s * s }

    /**
     * 4096-entry table indexed by sqrt(linear) (entry i holds the code, 0..255 as float, of linear
     * (i / 4095)^2), which keeps the dark end, where 8-bit codes are densest, accurate. Read it with [encodeFast].
     */
    fun encodeLut(curve: TransferCurve): FloatArray = if (curve == TransferCurve.SDR) encodeSdr else encodeHlg

    /** Normalised code (0..1) -> scene-linear (0..1). */
    fun toLinear(curve: TransferCurve, code: Float): Float {
        val e = code.coerceIn(0f, 1f).toDouble()
        return when (curve) {
            TransferCurve.SDR ->
                if (e < 0.081) (e / 4.5).toFloat() else ((e + 0.099) / 1.099).pow(1.0 / 0.45).toFloat()
            TransferCurve.HLG ->
                if (e <= 0.5) (e * e / 3.0).toFloat() else ((exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0).toFloat()
        }
    }

    /** Scene-linear -> normalised code (0..1); the input is clamped to 0..1. */
    fun toCode(curve: TransferCurve, linear: Float): Float {
        val x = linear.coerceIn(0f, 1f).toDouble()
        return when (curve) {
            TransferCurve.SDR -> if (x < 0.018) 4.5 * x else 1.099 * x.pow(0.45) - 0.099
            TransferCurve.HLG -> if (x <= 1.0 / 12.0) sqrt(3.0 * x) else HLG_A * ln(12.0 * x - HLG_B) + HLG_C
        }.toFloat().coerceIn(0f, 1f)
    }

    /** Linear -> code (0..255, float) through an [encodeLut] table with linear interpolation. */
    fun encodeFast(table: FloatArray, linear: Float): Float {
        val p = sqrt(linear.coerceIn(0f, 1f)) * (ENCODE_LUT_SIZE - 1)
        val i = p.toInt().coerceAtMost(ENCODE_LUT_SIZE - 2)
        val f = p - i
        return table[i] + (table[i + 1] - table[i]) * f
    }
}
