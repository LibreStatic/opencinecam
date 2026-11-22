/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.media.mux.Mp4Bytes
import com.librestatic.opencinecam.media.mux.Mp4CammAppender
import java.io.EOFException
import java.nio.ByteBuffer

/** [Mp4Bytes] over a growable byte array. */
internal class ByteArrayMp4Bytes(initial: ByteArray) : Mp4Bytes {
    var bytes = initial.copyOf()
        private set

    override fun size(): Long = bytes.size.toLong()

    override fun readFully(position: Long, buffer: ByteBuffer) {
        if (position + buffer.remaining() > bytes.size) throw EOFException("Read past ${bytes.size}")
        buffer.put(bytes, position.toInt(), buffer.remaining())
    }

    override fun writeFully(position: Long, buffer: ByteBuffer) {
        val end = position.toInt() + buffer.remaining()
        if (end > bytes.size) bytes = bytes.copyOf(end)
        buffer.get(bytes, position.toInt(), buffer.remaining())
    }

    override fun sync() {}
}

/** [mp4] with a CAMM track of [packets] gyro packets every 5 ms from [startUs] appended, as a finished take gets one. */
internal fun withCammTrack(mp4: ByteArray, packets: Int = 8, startUs: Long = 0): ByteArray {
    val file = ByteArrayMp4Bytes(mp4)
    Mp4CammAppender.begin(file).use { appender ->
        repeat(packets) { appender.writePacket(startUs + it * 5_000L, 2, 0.1f * it, 0f, -0.1f * it) }
        appender.finish()
    }
    return file.bytes
}

/** The payload of the `trak` in [moov] (a `moov` payload) whose `mdia/hdlr` handler is [handler]. */
internal fun trakPayload(moov: ByteArray, handler: String): ByteArray {
    fun children(data: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var at = 0
        while (at < data.size) {
            val size = ByteBuffer.wrap(data, at, 4).int
            out += String(data, at + 4, 4, Charsets.US_ASCII) to data.copyOfRange(at + 8, at + size)
            at += size
        }
        return out
    }
    fun child(data: ByteArray, type: String) = children(data).single { it.first == type }.second
    return children(moov).filter { it.first == "trak" }.map { it.second }
        .single { String(child(child(it, "mdia"), "hdlr"), 8, 4, Charsets.US_ASCII) == handler }
}
