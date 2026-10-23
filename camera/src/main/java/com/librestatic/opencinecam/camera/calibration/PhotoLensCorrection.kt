/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera.calibration

import com.librestatic.opencinecam.core.model.CalibrationSet
import com.librestatic.opencinecam.core.model.CalibrationSourcePath
import com.librestatic.opencinecam.core.model.LensCorrection

/**
 * The geometry of a lens alignment on a JPEG pixel grid. The video alignment ([LensAlignment.uvMatrix])
 * is written for a GL texture whose v axis points up; a JPEG's rows run top-down, so the fit is
 * conjugated by the vertical flip (ty -> -ty, rotation -> -rotation) before use. A content shift
 * that moves the picture down in GL therefore moves it down the JPEG rows as well, so a corrected
 * still frames the scene like the corrected video.
 */
object PhotoAlignment {
    /**
     * Row-major 3x3 `[a b c; d e f; 0 0 1]` (the order of `android.graphics.Matrix.setValues`) taking
     * a source pixel of a [width] x [height] image to the output pixel it lands on, for
     * `Canvas.drawBitmap(src, matrix, bilinearPaint)` into a bitmap of the same size.
     */
    fun bitmapMatrix(
        tx: Float, ty: Float, scale: Float, rotationRad: Float, extraZoom: Float, width: Int, height: Int,
    ): FloatArray {
        require(width > 0 && height > 0)
        val uv = LensAlignment.uvMatrix(tx, -ty, scale, -rotationRad, extraZoom, width.toFloat() / height)
        // Output UV q samples source UV s = A q + b (column-major uv: A = [0 4; 1 5], b = [12 13]).
        val a = uv[0].toDouble(); val c = uv[1].toDouble()
        val b = uv[4].toDouble(); val d = uv[5].toDouble()
        val bu = uv[12].toDouble(); val bv = uv[13].toDouble()
        val det = a * d - b * c
        require(det != 0.0 && det.isFinite()) { "singular alignment" }
        // q = A^-1 (s - b); source pixel (x, y) is s = (x / w, y / h) and the output pixel is (q.u * w, q.v * h).
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        val w = width.toDouble(); val h = height.toDouble()
        val m01 = ib * w / h
        val m10 = ic * h / w
        val m02 = -(ia * bu + ib * bv) * w
        val m12 = -(ic * bu + id * bv) * h
        return floatArrayOf(ia.toFloat(), m01.toFloat(), m02.toFloat(), m10.toFloat(), id.toFloat(), m12.toFloat(), 0f, 0f, 1f)
    }
}

/** What to do to one still: the colour pass of its lens and, when alignment is on, the geometry. */
class PhotoLensPlan(
    val setSha256: String,
    val physicalId: String,
    /** Row-major 3x3. */
    val colorMatrix: FloatArray,
    val gains: FloatArray,
    val evOffset: Float,
    val alignmentEnabled: Boolean,
    /** Row-major 3x3 for [android.graphics.Matrix.setValues]; null when alignment is off or the mapping is the identity. */
    val bitmapMatrix: FloatArray?,
) {
    val hasColor: Boolean
        get() = evOffset != 0f || !colorMatrix.contentEquals(IDENTITY) || !gains.contentEquals(UNIT)

    /** Corrects [pixels] (ARGB_8888) in place; the stills are SDR, so the SDR curve is always used. */
    fun applyColor(pixels: IntArray) {
        if (hasColor) PhotoColorPass.applyToArgb(pixels, colorMatrix, gains, evOffset, TransferCurve.SDR)
    }

    private companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val UNIT = floatArrayOf(1f, 1f, 1f)
    }
}

/** Chooses the correction of a still from its physical lens. Pure, so the gating is host-tested. */
object PhotoLensCorrection {
    /** [note] says why there is no plan, or what to warn about; null when there is nothing to say. */
    class Resolution(val plan: PhotoLensPlan?, val note: String?)

    /**
     * The plan for a [width] x [height] still from [physicalId], or none. No plan when there is no
     * set, the lens is unknown (never guess a lens), or the lens' entry changes nothing and the
     * alignment is off. With alignment on, every lens of the set (the reference included) gets the
     * same session zoom as the video, so a lens switch never changes the framing.
     */
    fun resolve(set: CalibrationSet?, alignmentEnabled: Boolean, physicalId: String?, width: Int, height: Int): Resolution {
        if (set == null) return Resolution(null, null)
        if (physicalId == null) return Resolution(null, "the still names no active physical lens")
        val lens: LensCorrection? = set.correctionFor(physicalId)
        if (lens == null && physicalId != set.referencePhysicalId) {
            return Resolution(null, "lens $physicalId is not in the calibration")
        }
        if (width <= 0 || height <= 0) return Resolution(null, "the still has no size")
        val warning = if (set.sourcePath == CalibrationSourcePath.HLG) {
            "calibration was measured on the HLG route; stills are corrected with the SDR curve"
        } else null
        val aspect = width.toFloat() / height
        val table = LensCorrectionTable.build(set, alignmentEnabled, aspect)
        val matrix = if (alignmentEnabled) {
            val align = lens?.align
            val required = align?.let {
                LensAlignment.requiredZoom(it.tx, it.ty, it.scale, it.rotationRad, aspect)
            } ?: 1f
            PhotoAlignment.bitmapMatrix(
                align?.tx ?: 0f, align?.ty ?: 0f, align?.scale ?: 1f, align?.rotationRad ?: 0f,
                table.sessionZoom / required, width, height,
            ).takeUnless { isIdentity(it) }
        } else null
        val plan = PhotoLensPlan(
            table.setSha256, physicalId,
            lens?.colorMatrix?.toFloatArray() ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            lens?.gains?.toFloatArray() ?: floatArrayOf(1f, 1f, 1f),
            lens?.evOffset ?: 0f, alignmentEnabled, matrix,
        )
        if (!plan.hasColor && matrix == null) return Resolution(null, warning)
        return Resolution(plan, warning)
    }

    private fun isIdentity(m: FloatArray): Boolean {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (i in 0 until 9) if (kotlin.math.abs(m[i] - id[i]) > 1e-6f) return false
        return true
    }
}
