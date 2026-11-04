/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * One read-write [FileChannel] over a file descriptor. A `ParcelFileDescriptor` opened `"rw"` only
 * yields a read-only channel (`FileInputStream`) or a write-only one (`FileOutputStream`), while
 * `.ocraw` recovery reads, truncates and appends through a single channel. Both halves share the
 * descriptor, so they share its file offset; reads go to [reader], everything else to [writer].
 * Closing this channel closes both halves (and so the descriptor's streams); the descriptor's owner
 * still closes the descriptor itself.
 */
internal class DuplexFileChannel(private val reader: FileChannel, private val writer: FileChannel) : FileChannel() {
    override fun read(dst: ByteBuffer): Int = reader.read(dst)
    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = reader.read(dsts, offset, length)
    override fun read(dst: ByteBuffer, position: Long): Int = reader.read(dst, position)
    override fun write(src: ByteBuffer): Int = writer.write(src)
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = writer.write(srcs, offset, length)
    override fun write(src: ByteBuffer, position: Long): Int = writer.write(src, position)
    override fun position(): Long = writer.position()
    override fun position(newPosition: Long): FileChannel = apply { writer.position(newPosition) }
    override fun size(): Long = writer.size()
    override fun truncate(size: Long): FileChannel = apply { writer.truncate(size) }
    override fun force(metaData: Boolean) = writer.force(metaData)
    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = reader.transferTo(position, count, target)
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = writer.transferFrom(src, position, count)
    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = throw UnsupportedOperationException("map")
    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException("lock")
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException("tryLock")

    override fun implCloseChannel() {
        var failure: Throwable? = null
        try { writer.close() } catch (problem: Throwable) { failure = problem }
        try { reader.close() } catch (problem: Throwable) { failure?.addSuppressed(problem) ?: run { failure = problem } }
        failure?.let { throw it }
    }

    companion object {
        fun open(descriptor: FileDescriptor): DuplexFileChannel =
            DuplexFileChannel(FileInputStream(descriptor).channel, FileOutputStream(descriptor).channel)
    }
}
