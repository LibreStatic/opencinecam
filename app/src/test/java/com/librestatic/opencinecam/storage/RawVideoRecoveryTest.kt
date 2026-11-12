/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawHeader
import com.librestatic.opencinecam.media.ocraw.OcrawRecoveryAction
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamWriter
import com.librestatic.opencinecam.media.ocraw.OcrawTimeBase
import com.librestatic.opencinecam.rawvideo.RawTakeFileDecision
import com.librestatic.opencinecam.rawvideo.recoverRawTakeFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RawVideoRecoveryTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("raw-recovery").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    /** A take of [frames] 32x8 RAW10 frames, closed cleanly when [finish], else left as a crash leaves it. */
    private fun take(name: String, frames: Int, finish: Boolean): File {
        val file = File(dir, name)
        RandomAccessFile(file, "rw").use { raf ->
            val writer = OcrawStreamWriter(raf.channel, OcrawHeader(ByteArray(16) { it.toByte() }), OcrawTimeBase(1, 1_000_000_000))
            writer.writeMetadata(mapOf("cameraId" to "0", "crop" to "0 0 32 8"), 0)
            repeat(frames) { index ->
                val header = OcrawFrameHeader.raw10(32, 8, 0, 0, 1_000L + index, index.toLong())
                writer.writeRawFrame(header, ByteBuffer.wrap(ByteArray(header.dataLength) { index.toByte() }), 1_000L + index)
            }
            if (finish) writer.finish() else writer.abort()
        }
        return file
    }

    private fun recoverFile(file: File): RawTakeFileDecision =
        RandomAccessFile(file, "rw").use { recoverRawTakeFile(it.channel).decision }

    private fun scanFrames(file: File) = RandomAccessFile(file, "r").use { OcrawStreamReader(it.channel).scan() }

    @Test
    fun truncatedTakeIsRepairedAndPublished() {
        val file = take("killed.ocraw", frames = 3, finish = false)
        // Process death in the middle of a fourth frame.
        FileOutputStream(file, true).use { it.write(ByteArray(200) { 1 }) }
        val repair = RandomAccessFile(file, "rw").use { recoverRawTakeFile(it.channel) }
        assertEquals(RawTakeFileDecision.PUBLISH, repair.decision)
        assertEquals(OcrawRecoveryAction.FINALIZED, repair.action)
        assertEquals(3, repair.frames)
        assertEquals(200, repair.truncatedBytes)
        val scan = scanFrames(file)
        assertTrue(scan.errors.toString(), scan.cleanlyClosed)
        assertEquals(3, scan.frameCount)
    }

    @Test
    fun recoveryPassPublishesFramesDeletesEmptyAndSkipsLiveRows() {
        val withFrames = take("a.ocraw", frames = 2, finish = false)
        val clean = take("b.ocraw", frames = 1, finish = true)
        val headerOnly = take("c.ocraw", frames = 0, finish = false)
        val empty = File(dir, "d.ocraw").apply { writeBytes(ByteArray(0)) }
        val garbage = File(dir, "e.ocraw").apply { writeBytes(ByteArray(100) { 7 }) }
        val live = take("f.ocraw", frames = 2, finish = false)
        val missing = File(dir, "g.ocraw")
        val published = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        val report = recoverPendingRawTakes(
            rows = listOf(withFrames, clean, headerOnly, empty, garbage, live, missing),
            isLive = { it == live },
            recoverRow = { file ->
                check(file.exists()) { "row disappeared" }
                recoverFile(file)
            },
            publish = { published += it.name },
            delete = { deleted += it.name; it.delete() },
        )

        assertEquals(RawVideoRecoveryReport(published = 2, deleted = 3, unresolved = 1), report)
        assertEquals(listOf("a.ocraw", "b.ocraw"), published)
        assertEquals(listOf("c.ocraw", "d.ocraw", "e.ocraw"), deleted)
        assertTrue(scanFrames(withFrames).cleanlyClosed)
        // The live take is untouched: still open-ended.
        assertTrue(!scanFrames(live).cleanlyClosed)
    }

    @Test
    fun duplexChannelOverOneDescriptorWritesAndRecovers() {
        val file = File(dir, "duplex.ocraw")
        RandomAccessFile(file, "rw").use { raf ->
            val channel: FileChannel = DuplexFileChannel(FileInputStream(raf.fd).channel, FileOutputStream(raf.fd).channel)
            val writer = OcrawStreamWriter(channel, OcrawHeader(ByteArray(16)), OcrawTimeBase(1, 1_000_000_000))
            repeat(4) { index ->
                val header = OcrawFrameHeader.raw10(32, 8, 0, 0, 10L + index, index.toLong())
                writer.writeRawFrame(header, ByteBuffer.wrap(ByteArray(header.dataLength)), 10L + index)
            }
            writer.abort()
            channel.write(ByteBuffer.wrap(ByteArray(33) { 9 })) // a torn tail
            val repair = recoverRawTakeFile(channel)
            assertEquals(RawTakeFileDecision.PUBLISH, repair.decision)
            assertEquals(4, repair.frames)
            assertEquals(33, repair.truncatedBytes)
        }
        val scan = scanFrames(file)
        assertTrue(scan.errors.toString(), scan.cleanlyClosed)
        assertEquals(4, scan.frameCount)
    }

    @Test
    fun unreadableFileIsKeptUnlessTheWriterKnowsItsFrames() {
        val file = take("unreadable.ocraw", frames = 3, finish = false)
        val published = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        val report = recoverPendingRawTakes(
            rows = listOf(file),
            isLive = { false },
            recoverRow = { row -> RandomAccessFile(row, "r").use { recoverRawTakeFile(UnreadableChannel(it.channel)).decision } },
            publish = { published += it.name },
            delete = { deleted += it.name },
        )

        assertEquals(RawVideoRecoveryReport(unresolved = 1), report)
        assertTrue(published.isEmpty() && deleted.isEmpty())
        RandomAccessFile(file, "r").use { raf ->
            val known = recoverRawTakeFile(UnreadableChannel(raf.channel), fallbackFrames = 3)
            assertEquals(RawTakeFileDecision.PUBLISH, known.decision)
            assertEquals(3, known.frames)
            assertEquals(RawTakeFileDecision.DELETE, recoverRawTakeFile(UnreadableChannel(raf.channel), fallbackFrames = 0).decision)
        }
    }

    @Test
    fun liveRegistryTracksOwnedRows() {
        val uri = RawVideoRecovery.insertLive { Uri.parse("content://media/external/file/42") }
        assertTrue(RawVideoRecovery.isLive(uri))
        RawVideoRecovery.release(uri)
        assertTrue(!RawVideoRecovery.isLive(uri))
    }
}

/** A channel whose every read and write fails, as on a volume returning I/O errors. */
private class UnreadableChannel(private val delegate: FileChannel) : FileChannel() {
    private fun fail(): Nothing = throw java.io.IOException("I/O error")
    override fun read(dst: ByteBuffer): Int = fail()
    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = fail()
    override fun read(dst: ByteBuffer, position: Long): Int = fail()
    override fun write(src: ByteBuffer): Int = fail()
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = fail()
    override fun write(src: ByteBuffer, position: Long): Int = fail()
    override fun position(): Long = delegate.position()
    override fun position(newPosition: Long): FileChannel = apply { delegate.position(newPosition) }
    override fun size(): Long = delegate.size()
    override fun truncate(size: Long): FileChannel = fail()
    override fun force(metaData: Boolean) = fail()
    override fun transferTo(position: Long, count: Long, target: java.nio.channels.WritableByteChannel): Long = fail()
    override fun transferFrom(src: java.nio.channels.ReadableByteChannel, position: Long, count: Long): Long = fail()
    override fun map(mode: MapMode, position: Long, size: Long): java.nio.MappedByteBuffer = fail()
    override fun lock(position: Long, size: Long, shared: Boolean): java.nio.channels.FileLock = fail()
    override fun tryLock(position: Long, size: Long, shared: Boolean): java.nio.channels.FileLock = fail()
    override fun implCloseChannel() = Unit
}
