/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer

/** Optional listening branch. Implementations must accept/drop without blocking the PCM owner. */
fun interface PcmListeningSink {
    fun offer(buffer: ByteBuffer, byteCount: Int, encoding: PcmMeterEncoding, sampleRateHz: Int, channels: Int): Boolean
}

/** A listening failure never mutates or fails capture. The sink receives its own read-only cursor. */
fun PcmListeningSink?.offerListening(buffer: ByteBuffer, byteCount: Int, encoding: PcmMeterEncoding,
    sampleRateHz: Int, channels: Int): Boolean = if (this == null) false else try {
    offer(buffer.asReadOnlyBuffer(), byteCount, encoding, sampleRateHz, channels)
} catch (_: Throwable) { false }
