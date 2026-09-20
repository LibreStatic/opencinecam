/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class ProxyPcmSampleType(val bytes: Int) { U8(1), S16_LE(2), S24_LE(3), S32_LE(4), F32_LE(4) }

/** Independent oracle for Media3 1.11's mandatory pre-effect PCM16 normalization. No dither,
 * resampling, channel mixing or timing changes. Source bytes are hashed separately and preserved.
 * Integer conversion discards low bits; finite floats clamp to [-1,1], scale by32767 and truncate. */
internal fun normalizeProxyPcm16(input: ByteBuffer, type: ProxyPcmSampleType): ByteBuffer {
    val source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    require(source.remaining() % type.bytes == 0) { "Partial PCM sample" }
    val result = ByteBuffer.allocate(Math.multiplyExact(source.remaining() / type.bytes, 2)).order(ByteOrder.LITTLE_ENDIAN)
    while (source.hasRemaining()) {
        val value = when (type) {
            ProxyPcmSampleType.U8 -> ((source.get().toInt() and 255) - 128) shl 8
            ProxyPcmSampleType.S16_LE -> source.short.toInt()
            ProxyPcmSampleType.S24_LE -> { source.get(); source.short.toInt() }
            ProxyPcmSampleType.S32_LE -> source.int shr 16
            ProxyPcmSampleType.F32_LE -> {
                val value = source.float
                require(value.isFinite()) { "Non-finite sidecar PCM" }
                (value.coerceIn(-1f, 1f) * 32767f).toInt()
            }
        }
        result.putShort(value.toShort())
    }
    result.flip(); return result
}
