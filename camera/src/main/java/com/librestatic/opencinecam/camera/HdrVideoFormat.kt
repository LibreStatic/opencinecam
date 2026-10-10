/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.media.MediaFormat
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Transfer of an HDR take in HLG mode. Both share one camera graph (HLG10 source, BT.2020 HEVC Main10);
 * only the GPU output transfer, the encoder profile and the colour tags differ.
 */
enum class HdrVideoFormat(
    /** H.273 TransferCharacteristics written to the SPS. */
    val h273Transfer: Int,
    /** [MediaFormat] `KEY_COLOR_TRANSFER` value. */
    val mediaFormatTransfer: Int,
    val curve: String,
    val curveVersion: String,
    /** Short name for status text. */
    val label: String,
) {
    HLG(HevcVuiTransfer.ARIB_STD_B67, MediaFormat.COLOR_TRANSFER_HLG, "HLG", "ARIB STD-B67", "HLG"),
    HDR10(HevcVuiTransfer.SMPTE_ST_2084, MediaFormat.COLOR_TRANSFER_ST2084, "PQ", "SMPTE ST 2084", "HDR10 PQ"),
    ;

    companion object {
        /** The format of an evidence [OpenCineLogRecordingEvidence.curve], or null for non-HDR curves. */
        fun fromCurve(curve: String): HdrVideoFormat? = entries.firstOrNull { it.curve == curve }
    }
}

/** Gating and static metadata of HDR10 (PQ) video. */
object Hdr10Video {
    /**
     * Internal kill switch. The conversion and the encoder plumbing are unit tested but still need the
     * device checks in docs/device-validation.md (PQ SPS tag, player tone mapping, mastering metadata in the file).
     */
    const val ENABLED = true

    /** First API with Camera2 dynamic range profiles, which the HLG graph and the HDR10 gate rely on. */
    const val MIN_SDK = 33

    /** Mastering display peak, cd/m2: the HLG reference display the shader renders for (BT.2100). */
    const val MASTERING_PEAK_NITS = 1000

    /** Mastering display black, in 0.0001 cd/m2 units: 0.005 cd/m2. */
    private const val MASTERING_MIN_UNITS = 50

    /** Conservative content light levels, cd/m2: nothing above the mastering peak, a modest frame average. */
    const val MAX_CLL_NITS = 1000
    const val MAX_FALL_NITS = 400

    /**
     * Whether the HDR format choice is offered for a profile: the camera advertises HDR10, a hardware
     * HEVC encoder advertises Main10 HDR10 for the size and rate, and the platform is new enough.
     */
    fun isOffered(sdk: Int, cameraAdvertisesHdr10: Boolean, encoderAdvertisesHdr10: Boolean): Boolean =
        ENABLED && sdk >= MIN_SDK && cameraAdvertisesHdr10 && encoderAdvertisesHdr10

    /** The format a take actually uses: HDR10 only when requested and offered, else HLG. */
    fun effectiveFormat(requested: HdrVideoFormat, offered: Boolean): HdrVideoFormat =
        if (requested == HdrVideoFormat.HDR10 && offered) HdrVideoFormat.HDR10 else HdrVideoFormat.HLG

    /**
     * `KEY_HDR_STATIC_INFO`: CTA-861.3 Static Metadata Type 1 as Android expects it, 25 bytes, little endian.
     * Primaries are BT.2020, white point D65; chromaticities are in 0.00002 units, luminance in cd/m2
     * (minimum in 0.0001 cd/m2).
     */
    fun staticInfo(): ByteArray {
        val values = intArrayOf(
            35400, 14600, // red 0.708, 0.292
            8500, 39850, // green 0.170, 0.797
            6550, 2300, // blue 0.131, 0.046
            15635, 16450, // white point D65 0.3127, 0.3290
            MASTERING_PEAK_NITS, MASTERING_MIN_UNITS, MAX_CLL_NITS, MAX_FALL_NITS,
        )
        val out = ByteArray(1 + values.size * 2) // byte 0: Static_Metadata_Descriptor_ID = 0
        values.forEachIndexed { index, value ->
            out[1 + index * 2] = (value and 0xFF).toByte()
            out[2 + index * 2] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }
}

/**
 * Reference maths of the GPU HLG to PQ conversion ([OpenCineLogGpuPipeline.pqSignalShader] repeats it
 * in GLSL). BT.2100: HLG inverse OETF to normalised scene light, the 1000 cd/m2 HLG OOTF
 * (system gamma 1.2) to display light, then the PQ inverse EOTF. HLG reference white (75% signal)
 * therefore lands at 203 cd/m2, as in BT.2408.
 */
object HlgToPq {
    private const val HLG_A = 0.17883277
    private const val HLG_B = 0.28466892
    private const val HLG_C = 0.55991073
    private const val PQ_M1 = 2610.0 / 16384.0
    private const val PQ_M2 = 2523.0 / 4096.0 * 128.0
    private const val PQ_C1 = 3424.0 / 4096.0
    private const val PQ_C2 = 2413.0 / 4096.0 * 32.0
    private const val PQ_C3 = 2392.0 / 4096.0 * 32.0
    private const val SYSTEM_GAMMA = 1.2
    private const val PQ_PEAK_NITS = 10000.0

    /** BT.2100 HLG inverse OETF: signal [0, 1] to normalised scene light [0, 1]. */
    fun hlgInverseOetf(signal: Double): Double {
        val e = signal.coerceIn(0.0, 1.0)
        return if (e <= 0.5) e * e / 3.0 else (exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0
    }

    /** SMPTE ST 2084 inverse EOTF: display light in cd/m2 to the PQ signal [0, 1]. */
    fun pqInverseEotf(nits: Double): Double {
        val y = (nits / PQ_PEAK_NITS).coerceIn(0.0, 1.0).pow(PQ_M1)
        return ((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y)).pow(PQ_M2)
    }

    /** SMPTE ST 2084 EOTF: PQ signal to display light in cd/m2. */
    fun pqEotf(signal: Double): Double {
        val p = signal.coerceIn(0.0, 1.0).pow(1.0 / PQ_M2)
        return PQ_PEAK_NITS * (max(p - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * p)).pow(1.0 / PQ_M1)
    }

    /**
     * One BT.2020 pixel from scene-linear HLG to PQ. [sceneLinear] is normalised scene light (red,
     * green, blue); the result is the PQ signal per channel.
     */
    fun sceneLinearToPq(sceneLinear: DoubleArray, peakNits: Double = Hdr10Video.MASTERING_PEAK_NITS.toDouble()): DoubleArray {
        val e = DoubleArray(3) { min(max(sceneLinear[it], 0.0), 1.0) }
        val luma = 0.2627 * e[0] + 0.6780 * e[1] + 0.0593 * e[2]
        val gain = peakNits * max(luma, 1e-6).pow(SYSTEM_GAMMA - 1.0)
        return DoubleArray(3) { pqInverseEotf(gain * e[it]) }
    }

    /** Neutral HLG signal to neutral PQ signal; handy for ramps and tests. */
    fun greyHlgToPq(hlgSignal: Double): Double {
        val scene = hlgInverseOetf(hlgSignal)
        return sceneLinearToPq(doubleArrayOf(scene, scene, scene))[0]
    }
}
