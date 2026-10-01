/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/**
 * Where scene middle grey lands in OCLog2 for each source tier. The two tiers decode their
 * camera signal with different references, so the same grey card sits about 1.9 stops lower
 * in HLG than in SDR. The gain multiplies scene-linear BT.2020 before OCLog2 encoding (the
 * shader `uSceneGain` uniform); it is a runtime parameter, not part of the hashed shader.
 * Names are persisted, so they are stable.
 */
enum class OpenCineLogGreyReference {
    /** Each tier keeps its own reference; gain 1.0 everywhere. */
    NATIVE,
    /** SDR tier scaled down to the HLG grey. Lossless: nothing new clips. */
    MATCH_HLG,
    /** HLG tier scaled up to the SDR grey. Clips HDR highlights above [MATCH_SDR_CLIP_HLG_SIGNAL]. */
    MATCH_SDR,
    ;

    /** Scene-linear gain for [sourcePath]; 1.0 whenever the tier already holds the reference. */
    fun sceneGain(sourcePath: OpenCineLogSourcePath): Float = when (this) {
        NATIVE -> 1f
        MATCH_HLG -> if (sourcePath == OpenCineLogSourcePath.SDR_BT709_ISP) (1.0 / TIER_GREY_RATIO).toFloat() else 1f
        MATCH_SDR -> if (sourcePath == OpenCineLogSourcePath.HLG10_BT2020) TIER_GREY_RATIO.toFloat() else 1f
    }

    companion object {
        /** ITU-R BT.2408: 18% grey is placed at 38% HLG signal. */
        const val HLG_REFERENCE_GREY_SIGNAL = 0.38

        /** Scene light the BT.2100 inverse HLG OETF assigns to that signal (E <= 1/2 branch: E^2/3). */
        const val HLG_REFERENCE_GREY_LINEAR = HLG_REFERENCE_GREY_SIGNAL * HLG_REFERENCE_GREY_SIGNAL / 3.0

        /**
         * The SDR tier inverts the BT.709 OETF, which maps an 18% grey to code ~0.409 and back to
         * exactly 0.18 scene-linear.
         */
        const val SDR_REFERENCE_GREY_LINEAR = 0.18

        /** 0.18 / (0.38^2 / 3) = 0.54 / 0.1444 ~= 3.7396, i.e. ~1.90 stops. */
        const val TIER_GREY_RATIO = SDR_REFERENCE_GREY_LINEAR / HLG_REFERENCE_GREY_LINEAR

        /**
         * HLG signal whose scene light reaches OCLog2's 1.0 ceiling once scaled by [TIER_GREY_RATIO]:
         * inverse HLG of 1/ratio through the BT.2100 log segment, ~0.752.
         */
        val MATCH_SDR_CLIP_HLG_SIGNAL: Double = run {
            val a = 0.17883277
            val b = 1.0 - 4.0 * a
            val c = 0.5 - a * kotlin.math.ln(4.0 * a)
            a * kotlin.math.ln(12.0 / TIER_GREY_RATIO - b) + c
        }
    }
}
