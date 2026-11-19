/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.mux

import android.system.ErrnoException
import android.system.Os
import java.io.EOFException
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

/**
 * [Mp4Bytes] over a file descriptor opened for reading, and for writing when the file is edited: a MediaStore pending row
 * opened "rw" or a document descriptor from a SAF provider. Positioned reads and writes leave the descriptor's offset alone.
 * The descriptor is never closed here.
 */
class DescriptorMp4Bytes(private val descriptor: FileDescriptor) : Mp4Bytes {
    override fun size(): Long = errno { Os.fstat(descriptor).st_size }

    override fun readFully(position: Long, buffer: ByteBuffer) {
        var at = position
        while (buffer.hasRemaining()) {
            val read = errno { Os.pread(descriptor, buffer, at) }
            if (read <= 0) throw EOFException("The file ends at $at, ${buffer.remaining()} bytes short")
            at += read
        }
    }

    override fun writeFully(position: Long, buffer: ByteBuffer) {
        var at = position
        while (buffer.hasRemaining()) {
            val written = errno { Os.pwrite(descriptor, buffer, at) }
            if (written <= 0) throw IOException("A write at $at did not advance")
            at += written
        }
    }

    override fun sync() {
        errno { Os.fsync(descriptor) }
    }

    private inline fun <T> errno(block: () -> T): T = try {
        block()
    } catch (e: ErrnoException) {
        throw IOException(e.message, e)
    }
}
