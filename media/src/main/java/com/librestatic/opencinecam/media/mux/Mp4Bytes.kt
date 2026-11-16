/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random access to an MP4 file. Positions are absolute. */
interface Mp4Bytes {
    fun size(): Long

    /** Fills [buffer] (remaining bytes) from [position]; throws EOFException when the file is shorter. */
    fun readFully(position: Long, buffer: ByteBuffer)

    /** Writes all of [buffer]'s remaining bytes at [position], growing the file when it ends past the end. */
    fun writeFully(position: Long, buffer: ByteBuffer)

    /** Forces written bytes to storage. */
    fun sync()
}

/** [Mp4Bytes] over a [FileChannel] opened for reading, and for writing when the file is edited. */
class FileChannelMp4Bytes(private val channel: FileChannel) : Mp4Bytes {
    override fun size(): Long = channel.size()

    override fun readFully(position: Long, buffer: ByteBuffer) {
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read < 0) throw EOFException("The file ends at $at, ${buffer.remaining()} bytes short")
            at += read
        }
    }

    override fun writeFully(position: Long, buffer: ByteBuffer) {
        var at = position
        while (buffer.hasRemaining()) {
            at += channel.write(buffer, at)
        }
    }

    override fun sync() {
        channel.force(false)
    }
}
