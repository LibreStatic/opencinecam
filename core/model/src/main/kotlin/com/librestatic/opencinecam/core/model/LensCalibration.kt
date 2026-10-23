/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import kotlin.math.pow

/**
 * A 2D similarity (translate, uniform scale, rotate) used to align one physical lens to the reference lens.
 *
 * Convention: it maps an output UV (0..1, rotated and scaled about the centre 0.5, 0.5) to the UV at
 * which the target lens texture is sampled, so that the content lands where the reference lens shows
 * it. [tx] and [ty] are in UV units, [rotationRad] is counter-clockwise.
 */
data class Similarity(
    val tx: Float = 0f,
    val ty: Float = 0f,
    val scale: Float = 1f,
    val rotationRad: Float = 0f,
) {
    val isIdentity: Boolean get() = this == IDENTITY

    companion object {
        val IDENTITY = Similarity()
    }
}

/** Reasons a calibration cell set or fit is less trustworthy than usual. */
enum class CalibrationFlag { LOW_TEXTURE, LOW_CHROMA, CLIPPED, UNSTABLE_AE, PARALLAX_SUSPECT, DIAGONAL_FALLBACK }

/**
 * How well a lens fit explained the data: residual colour error (delta E), residual alignment error
 * in pixels, the number of grid cells that contributed, an overall [score] in 0..1 and [flags].
 */
data class CalibrationQuality(
    val colorResidualDeltaE: Float,
    val alignResidualPx: Float,
    val cellsUsed: Int,
    val score: Float,
    val flags: Set<CalibrationFlag> = emptySet(),
)

/**
 * The correction for one physical lens, relative to the reference lens.
 *
 * [colorMatrix] is 3x3 row-major in linear Rec.709 with rows summing to 1, [gains] are per-channel
 * (R, G, B) multipliers, [evOffset] is in stops. Applied as `corrected_lin = 2^evOffset * M * (gains * lin)`.
 */
data class LensCorrection(
    val physicalId: String,
    val colorMatrix: List<Float>,
    val gains: List<Float>,
    val evOffset: Float,
    val align: Similarity,
    val quality: CalibrationQuality,
) {
    /** True when applying this correction would change nothing. */
    val isIdentity: Boolean
        get() = evOffset == 0f && align.isIdentity && colorMatrix == IDENTITY_MATRIX && gains == UNIT_GAINS

    /** Linear-light scale of [evOffset]. */
    val exposureScale: Float get() = 2.0.pow(evOffset.toDouble()).toFloat()

    companion object {
        val IDENTITY_MATRIX: List<Float> = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val UNIT_GAINS: List<Float> = listOf(1f, 1f, 1f)

        /** The no-op correction, used for the reference lens. */
        fun identity(physicalId: String) = LensCorrection(
            physicalId = physicalId,
            colorMatrix = IDENTITY_MATRIX,
            gains = UNIT_GAINS,
            evOffset = 0f,
            align = Similarity.IDENTITY,
            quality = CalibrationQuality(0f, 0f, 0, 1f),
        )
    }
}

/** How the calibration frames were captured. */
enum class CalibrationSessionKind { LOGICAL, DIRECT_PHYSICAL }

/** Which pipeline the calibrated pixels came from. */
enum class CalibrationSourcePath { SDR, HLG }

/** The illuminant seen while calibrating: correlated colour temperature and/or the AWB gains (R, Geven, Godd, B). */
data class IlluminantHint(val cctKelvin: Float?, val awbGains: List<Float>?)

/** A complete calibration of one logical camera on one build: the reference lens plus a correction per lens. */
data class CalibrationSet(
    val schema: Int = SCHEMA,
    val fingerprint: String,
    val logicalId: String,
    val sessionKind: CalibrationSessionKind,
    val referencePhysicalId: String,
    val sourcePath: CalibrationSourcePath,
    val createdAtMs: Long,
    val illuminantHint: IlluminantHint?,
    val lenses: List<LensCorrection>,
) {
    fun correctionFor(physicalId: String): LensCorrection? = lenses.firstOrNull { it.physicalId == physicalId }

    companion object {
        const val SCHEMA = 1
    }
}
