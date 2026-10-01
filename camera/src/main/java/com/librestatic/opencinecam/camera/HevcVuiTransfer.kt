/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/**
 * Rewrites the VUI `transfer_characteristics` of HEVC sequence parameter sets in an Annex B stream.
 *
 * Android cannot ask an encoder for H.273 transfer 2 (unspecified). Qualcomm `c2.qti.hevc.encoder`
 * writes ST 2084 (PQ) into the SPS of BT.2020 OCLog2 recordings whatever buffer dataspace or EGL
 * colorspace it is given, and a PQ tag makes decoders treat the clip as HDR10. Only the 8-bit
 * transfer field changes; every other bit of the SPS is kept, so the coded pictures stay valid.
 */
object HevcVuiTransfer {
    /** H.273 TransferCharacteristics 2: unspecified. */
    const val UNSPECIFIED = 2

    private const val SPS_NAL_TYPE = 33

    /**
     * [bytes] with the transfer rewritten. [previousTransfer] is the transfer of the last SPS that
     * carried a colour description, or null when none did (nothing was rewritten then).
     */
    class Result(val bytes: ByteArray, val previousTransfer: Int?)

    /**
     * Rewrites every SPS in [annexB] that carries a colour description to [transfer]. Other NAL
     * units are copied unchanged. Throws [IllegalArgumentException] when an SPS cannot be parsed.
     */
    fun rewrite(annexB: ByteArray, transfer: Int): Result {
        require(transfer in 0..255) { "H.273 transfer must fit in 8 bits: $transfer" }
        val output = java.io.ByteArrayOutputStream(annexB.size + 4)
        var copied = 0
        var previous: Int? = null
        for ((start, end) in nalUnits(annexB)) {
            if (end - start < 2 || (annexB[start].toInt() shr 1) and 0x3F != SPS_NAL_TYPE) continue
            val nal = annexB.copyOfRange(start, end)
            val rbsp = unescape(nal)
            val bit = transferBitOffset(rbsp) ?: continue
            previous = readBits(rbsp, bit, 8)
            writeBits(rbsp, bit, 8, transfer)
            output.write(annexB, copied, start - copied)
            output.write(escape(rbsp))
            copied = end
        }
        if (copied == 0) return Result(annexB, previous)
        output.write(annexB, copied, annexB.size - copied)
        return Result(output.toByteArray(), previous)
    }

    /** Transfer of the first SPS in [annexB] with a colour description, or null. */
    fun transferOf(annexB: ByteArray): Int? = nalUnits(annexB).firstNotNullOfOrNull { (start, end) ->
        if (end - start < 2 || (annexB[start].toInt() shr 1) and 0x3F != SPS_NAL_TYPE) return@firstNotNullOfOrNull null
        val rbsp = unescape(annexB.copyOfRange(start, end))
        transferBitOffset(rbsp)?.let { readBits(rbsp, it, 8) }
    }

    /**
     * NAL unit payload ranges, start code and trailing zero bytes excluded. Scanning stops after the
     * first coded slice: parameter sets precede it, and a 4K keyframe need not be read to its end.
     */
    internal fun nalUnits(data: ByteArray): List<Pair<Int, Int>> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i + 3
                if (i + 3 < data.size && (data[i + 3].toInt() shr 1) and 0x3F < 32) break
                i += 3
            } else i++
        }
        return starts.mapIndexed { index, start ->
            var end = if (index + 1 < starts.size) starts[index + 1] - 3 else data.size
            while (end > start && data[end - 1].toInt() == 0) end--
            start to end
        }
    }

    internal fun unescape(nal: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (byte in nal) {
            val value = byte.toInt() and 0xFF
            if (zeros >= 2 && value == 3) {
                zeros = 0
                continue
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    internal fun escape(rbsp: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(rbsp.size + 4)
        var zeros = 0
        for (byte in rbsp) {
            val value = byte.toInt() and 0xFF
            if (zeros >= 2 && value <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Bit offset of `transfer_characteristics` in an SPS RBSP (header included), or null when absent. */
    internal fun transferBitOffset(rbsp: ByteArray): Int? {
        val r = BitReader(rbsp)
        r.skip(16) // NAL unit header
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.u(3)
        r.skip(1) // sps_temporal_id_nesting_flag
        profileTierLevel(r, maxSubLayersMinus1)
        r.ue() // sps_seq_parameter_set_id
        if (r.ue() == 3) r.skip(1) // chroma_format_idc, separate_colour_plane_flag
        r.ue(); r.ue() // pic_width/height_in_luma_samples
        if (r.flag()) repeat(4) { r.ue() } // conformance window offsets
        r.ue(); r.ue() // bit_depth_luma/chroma_minus8
        val log2MaxPocLsb = r.ue() + 4
        val orderingForAll = r.flag()
        repeat(if (orderingForAll) maxSubLayersMinus1 + 1 else 1) { repeat(3) { r.ue() } }
        repeat(6) { r.ue() } // coding/transform block sizes and hierarchy depths
        if (r.flag() && r.flag()) scalingListData(r) // scaling_list_enabled, sps_scaling_list_data_present
        r.skip(2) // amp_enabled_flag, sample_adaptive_offset_enabled_flag
        if (r.flag()) { // pcm_enabled_flag
            r.skip(8)
            r.ue(); r.ue()
            r.skip(1)
        }
        shortTermRefPicSets(r)
        if (r.flag()) repeat(r.ue()) { r.skip(log2MaxPocLsb + 1) } // long-term reference pictures
        r.skip(2) // sps_temporal_mvp_enabled_flag, strong_intra_smoothing_enabled_flag
        if (!r.flag()) return null // vui_parameters_present_flag
        if (r.flag() && r.u(8) == 255) r.skip(32) // aspect_ratio_idc, sar_width/height
        if (r.flag()) r.skip(1) // overscan
        if (!r.flag()) return null // video_signal_type_present_flag
        r.skip(4) // video_format, video_full_range_flag
        if (!r.flag()) return null // colour_description_present_flag
        r.skip(8) // colour_primaries
        return r.position
    }

    private fun profileTierLevel(r: BitReader, maxSubLayersMinus1: Int) {
        r.skip(96) // general profile (88 bits) and general_level_idc
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = r.flag()
            levelPresent[i] = r.flag()
        }
        if (maxSubLayersMinus1 > 0) r.skip(2 * (8 - maxSubLayersMinus1))
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) r.skip(88)
            if (levelPresent[i]) r.skip(8)
        }
    }

    private fun scalingListData(r: BitReader) {
        for (sizeId in 0..3) {
            for (matrixId in 0 until 6 step if (sizeId == 3) 3 else 1) {
                if (!r.flag()) r.ue() // scaling_list_pred_matrix_id_delta
                else {
                    if (sizeId > 1) r.se() // scaling_list_dc_coef_minus8
                    repeat(minOf(64, 1 shl (4 + (sizeId shl 1)))) { r.se() }
                }
            }
        }
    }

    /** Parses st_ref_pic_set() for every SPS set, deriving the delta POCs inter prediction needs (H.265 7.4.8). */
    private fun shortTermRefPicSets(r: BitReader) {
        val count = r.ue()
        require(count <= 64) { "num_short_term_ref_pic_sets out of range: $count" }
        val negatives = ArrayList<IntArray>(count)
        val positives = ArrayList<IntArray>(count)
        for (index in 0 until count) {
            if (index != 0 && r.flag()) {
                val refNegative = negatives[index - 1]
                val refPositive = positives[index - 1]
                val sign = r.u(1)
                val deltaRps = (1 - 2 * sign) * (r.ue() + 1)
                val refCount = refNegative.size + refPositive.size
                val useDelta = BooleanArray(refCount + 1) { r.flag() || r.flag() }
                val s0 = ArrayList<Int>()
                for (j in refPositive.indices.reversed()) {
                    val dPoc = refPositive[j] + deltaRps
                    if (dPoc < 0 && useDelta[refNegative.size + j]) s0 += dPoc
                }
                if (deltaRps < 0 && useDelta[refCount]) s0 += deltaRps
                for (j in refNegative.indices) {
                    val dPoc = refNegative[j] + deltaRps
                    if (dPoc < 0 && useDelta[j]) s0 += dPoc
                }
                val s1 = ArrayList<Int>()
                for (j in refNegative.indices.reversed()) {
                    val dPoc = refNegative[j] + deltaRps
                    if (dPoc > 0 && useDelta[j]) s1 += dPoc
                }
                if (deltaRps > 0 && useDelta[refCount]) s1 += deltaRps
                for (j in refPositive.indices) {
                    val dPoc = refPositive[j] + deltaRps
                    if (dPoc > 0 && useDelta[refNegative.size + j]) s1 += dPoc
                }
                negatives += s0.toIntArray()
                positives += s1.toIntArray()
            } else {
                val negativeCount = r.ue()
                val positiveCount = r.ue()
                require(negativeCount <= 16 && positiveCount <= 16) { "Short-term RPS too large: $negativeCount/$positiveCount" }
                var poc = 0
                negatives += IntArray(negativeCount) { poc -= r.ue() + 1; r.skip(1); poc }
                poc = 0
                positives += IntArray(positiveCount) { poc += r.ue() + 1; r.skip(1); poc }
            }
        }
    }

    private fun readBits(data: ByteArray, offset: Int, count: Int): Int {
        var value = 0
        for (bit in offset until offset + count) value = (value shl 1) or ((data[bit ushr 3].toInt() shr (7 - (bit and 7))) and 1)
        return value
    }

    private fun writeBits(data: ByteArray, offset: Int, count: Int, value: Int) {
        for (i in 0 until count) {
            val bit = offset + i
            val mask = 1 shl (7 - (bit and 7))
            val set = (value shr (count - 1 - i)) and 1 == 1
            data[bit ushr 3] = (if (set) data[bit ushr 3].toInt() or mask else data[bit ushr 3].toInt() and mask.inv()).toByte()
        }
    }

    private class BitReader(private val data: ByteArray) {
        var position = 0
            private set

        fun skip(bits: Int) {
            position += bits
            require(position <= data.size * 8) { "SPS ended early" }
        }

        fun u(bits: Int): Int {
            require(position + bits <= data.size * 8) { "SPS ended early" }
            return readBits(data, position, bits).also { position += bits }
        }

        fun flag(): Boolean = u(1) == 1

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) require(++zeros <= 31) { "Exp-Golomb code too long" }
            return ((1L shl zeros) - 1 + u(zeros).toLong()).toInt()
        }

        fun se(): Int {
            val code = ue()
            return if (code and 1 == 1) (code + 1) / 2 else -(code / 2)
        }
    }
}
