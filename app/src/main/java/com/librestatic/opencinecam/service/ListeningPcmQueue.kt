/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.camera.PcmMeterEncoding
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

internal data class ListeningPcmPacket(val generation: Long, val bytes: ByteArray,
    val encoding: PcmMeterEncoding, val sampleRateHz: Int, val channels: Int)

/** Four reserved copies maximum, including the packet currently owned by the output worker. */
internal class ListeningPcmQueue {
    private val packets = ConcurrentLinkedQueue<ListeningPcmPacket>()
    private val reserved = AtomicInteger()
    val reservedPackets: Int get() = reserved.get()
    fun offer(buffer: ByteBuffer, byteCount: Int, encoding: PcmMeterEncoding,
        rate: Int, channels: Int, generation: Long): Boolean {
        if (channels !in 1..2 || rate !in 8000..192000 || byteCount !in 1..MAX_PACKET_BYTES ||
            byteCount > buffer.capacity() || byteCount % (encoding.bytesPerSample * channels) != 0) return false
        while (true) {
            val count = reserved.get()
            if (count >= MAX_PACKETS) return false
            if (reserved.compareAndSet(count, count + 1)) break
        }
        return try {
            val bytes = ByteArray(byteCount)
            buffer.duplicate().apply { clear(); limit(byteCount) }.get(bytes)
            packets.add(ListeningPcmPacket(generation, bytes, encoding, rate, channels))
            true
        } catch (_: Throwable) { reserved.decrementAndGet(); false }
    }
    fun poll(): ListeningPcmPacket? = packets.poll()
    fun complete() { check(reserved.decrementAndGet() >= 0) }
    fun clear() { while (packets.poll() != null) complete() }
    companion object { const val MAX_PACKETS = 4; const val MAX_PACKET_BYTES = 65536 }
}

/** Listening-only PCM16 conversion. Finite float headroom clips here, never in recording bytes. */
internal fun listeningPcm16(packet: ListeningPcmPacket): ByteBuffer {
    val input = ByteBuffer.wrap(packet.bytes).order(ByteOrder.LITTLE_ENDIAN)
    val output = ByteBuffer.allocateDirect(packet.bytes.size / packet.encoding.bytesPerSample * 2)
        .order(ByteOrder.LITTLE_ENDIAN)
    while (input.hasRemaining()) {
        val value = when (packet.encoding) {
            PcmMeterEncoding.PCM_16 -> input.short.toInt()
            PcmMeterEncoding.PCM_24 -> {
                val raw = (input.get().toInt() and 255) or ((input.get().toInt() and 255) shl 8) or
                    (input.get().toInt() shl 16)
                (raw / 256.0).roundToInt().coerceIn(-32768, 32767)
            }
            PcmMeterEncoding.PCM_FLOAT -> {
                val sample = input.float
                if (sample.isFinite()) (sample.toDouble() * 32768).coerceIn(-32768.0, 32767.0).roundToInt() else 0
            }
        }
        output.putShort(value.toShort())
    }
    return output.apply { flip() }
}
