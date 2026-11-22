/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

/**
 * How coarse a take's CAMM track must be to fit in memory. The CAMM appender builds the new `moov` in memory, about
 * 12 bytes per packet on top of 5 bytes of its own tables, and it runs while the take finalises: an OutOfMemoryError
 * there would take the service down. The Gyroflow log is streamed to a file and always keeps 1 ms bins.
 */
internal object MotionCammBudget {
    /**
     * At most this many packets per take, about 20 MB of transient memory in the appender: 10 minutes of gyroscope and
     * accelerometer at 1 kHz bins, and over two hours at the coarsest bin.
     */
    const val MAX_PACKETS: Long = 1_200_000L

    const val DEFAULT_BIN_US: Long = MotionResampler.DEFAULT_BIN_US

    /** The bins tried, finest first, when the default one is over budget. */
    val FALLBACK_BINS_US: LongArray = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L)

    /** Every bin [chooseBinUs] may ask about: [DEFAULT_BIN_US], then [FALLBACK_BINS_US]. */
    val CANDIDATE_BINS_US: LongArray = longArrayOf(DEFAULT_BIN_US) + FALLBACK_BINS_US

    /**
     * The finest bin whose packet count, as [packetsAt] gives it, is within [maxPackets]: [DEFAULT_BIN_US] when it
     * fits, otherwise the first of [FALLBACK_BINS_US] that does, or null when none does (no CAMM track then).
     */
    fun chooseBinUs(maxPackets: Long = MAX_PACKETS, packetsAt: (binUs: Long) -> Long): Long? {
        if (packetsAt(DEFAULT_BIN_US) <= maxPackets) return DEFAULT_BIN_US
        for (bin in FALLBACK_BINS_US) {
            if (packetsAt(bin) <= maxPackets) return bin
        }
        return null
    }

    /** The packets [series] makes: every vector sample and every exposure change. */
    fun packets(series: CammSeries): Long =
        series.gyro.size.toLong() + series.accel.size + series.mag.size + series.exposureFileUs.size
}
