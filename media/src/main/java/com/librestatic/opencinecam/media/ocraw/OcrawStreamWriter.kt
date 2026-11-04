// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.GatheringByteChannel
import java.nio.channels.WritableByteChannel

/**
 * Growable index of `(sequence, fileOffset, timestampTicks)` triples kept in a
 * flat [LongArray], so an hour of 60 fps frames costs no per-entry objects.
 * Its payload is the v1 index wire format: u32 count, then 24-byte entries.
 */
class OcrawIndexTable(initialCapacity: Int = 256) {
    private var values = LongArray(maxOf(1, initialCapacity) * 3)

    var size: Int = 0
        private set

    fun add(sequence: Long, fileOffset: Long, timestampTicks: Long) {
        if ((size + 1) * 3 > values.size) values = values.copyOf(values.size * 2)
        values[size * 3] = sequence
        values[size * 3 + 1] = fileOffset
        values[size * 3 + 2] = timestampTicks
        size++
    }

    fun sequence(index: Int): Long = values[checked(index) * 3]

    fun fileOffset(index: Int): Long = values[checked(index) * 3 + 1]

    fun timestampTicks(index: Int): Long = values[checked(index) * 3 + 2]

    fun entry(index: Int): OcrawIndexEntry = OcrawIndexEntry(sequence(index), fileOffset(index), timestampTicks(index))

    fun toList(): List<OcrawIndexEntry> = List(size, ::entry)

    /** Index payload bytes for entries `[from, to)`. */
    fun payloadBytes(from: Int = 0, to: Int = size): Long = 4L + (to - from).toLong() * ENTRY_BYTES

    /** Encodes entries `[from, to)` as an index payload. */
    fun encode(from: Int = 0, to: Int = size): ByteBuffer {
        require(from in 0..to && to <= size) { "index range out of bounds" }
        val bytes = payloadBytes(from, to)
        require(bytes <= OcrawCodec.MAX_CHUNK_PAYLOAD) { "index exceeds the bounded payload" }
        val buffer = ByteBuffer.allocate(bytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(to - from)
        for (index in from until to) {
            buffer.putLong(values[index * 3])
            buffer.putLong(values[index * 3 + 1])
            buffer.putLong(values[index * 3 + 2])
        }
        buffer.flip()
        return buffer
    }

    private fun checked(index: Int): Int {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index $index of $size")
        return index
    }

    companion object {
        const val ENTRY_BYTES = 24

        /** Most entries one index chunk can hold under the 64 MiB payload bound. */
        const val MAX_ENTRIES_PER_CHUNK = (OcrawCodec.MAX_CHUNK_PAYLOAD - 4) / ENTRY_BYTES

        /** True for chunks an index lists: everything but indexes and END. */
        fun isIndexed(wireId: Int): Boolean = wireId != OcrawChunkId.PERIODIC_INDEX.wireId &&
            wireId != OcrawChunkId.FINAL_INDEX.wireId && wireId != OcrawChunkId.END.wireId
    }
}

/** Evidence counters from an [OcrawStreamWriter]. */
data class OcrawStreamStats(
    val bytesWritten: Long,
    val framesWritten: Long,
    val chunksWritten: Long,
    val periodicIndexCount: Int,
    val forceCount: Int,
    val maxWriteNanos: Long,
    val totalWriteNanos: Long,
    val maxForceNanos: Long,
    val totalForceNanos: Long,
    val finalIndexWritten: Boolean,
    val endWritten: Boolean,
) {
    val maxWriteMs: Double get() = maxWriteNanos / 1e6
    val maxForceMs: Double get() = maxForceNanos / 1e6
}

/**
 * Streaming `.ocraw` writer for RAW video: gather writes of chunk header,
 * frame header and frame data, with no copy of the frame.
 *
 * **Single-threaded.** Every call, including [finish] and [abort], must come
 * from one thread (the writer thread). The writer does not close [channel];
 * its owner does, after [finish] or [abort].
 *
 * The 32-byte file header is written by the constructor, so the channel must
 * be empty and positioned at 0. Every chunk is written whole before the next
 * starts; after a crash, [OcrawStreamReader.recover] truncates a torn tail and
 * keeps every complete chunk. A periodic index is appended at least every
 * [Options.periodicIndexSeconds] of media time or [Options.periodicIndexFrames]
 * raw frames, and [finish] appends `FINAL_INDEX` and `END`.
 *
 * An [IOException] from the channel leaves the writer failed: later calls throw
 * [IllegalStateException] and the file is recovered like a crash.
 */
class OcrawStreamWriter(
    private val channel: WritableByteChannel,
    header: OcrawHeader,
    private val timeBase: OcrawTimeBase,
    private val options: Options = Options(),
    private val force: (() -> Unit)? = (channel as? FileChannel)?.let { file -> { file.force(false) } },
) {
    data class Options(
        /** Force the channel to storage after this many bytes since the last force. */
        val forceEveryBytes: Long = 256L * 1024 * 1024,
        /** Longest media time between periodic indexes. */
        val periodicIndexSeconds: Double = 1.0,
        /** Most raw frames between periodic indexes. */
        val periodicIndexFrames: Int = 32,
        /** Force once more after the END chunk in [finish]. */
        val forceOnFinish: Boolean = true,
    ) {
        init {
            require(forceEveryBytes > 0) { "forceEveryBytes must be positive" }
            require(periodicIndexSeconds > 0) { "periodicIndexSeconds must be positive" }
            require(periodicIndexFrames > 0) { "periodicIndexFrames must be positive" }
        }
    }

    private val chunkHeader = ByteBuffer.allocateDirect(OcrawCodec.CHUNK_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val frameHeader = ByteBuffer.allocateDirect(OcrawFrameHeader.BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val gather2 = arrayOfNulls<ByteBuffer>(2)
    private val gather3 = arrayOfNulls<ByteBuffer>(3)
    private val crc = Crc32c.newAccumulator()
    private val index = OcrawIndexTable(1024)
    private val gathering = channel as? GatheringByteChannel
    private val ticksPerIndexInterval: Double = options.periodicIndexSeconds * timeBase.denominator / timeBase.numerator

    private var nextSequence = 0L
    private var lastTicks = 0L
    private var lastPeriodicTicks = -1L
    private var lastPeriodicEntry = 0
    private var framesSincePeriodic = 0
    private var bytesSinceForce = 0L
    private var state = State.OPEN

    private var bytesWritten = 0L
    private var framesWritten = 0L
    private var chunksWritten = 0L
    private var periodicIndexCount = 0
    private var forceCount = 0
    private var maxWriteNanos = 0L
    private var totalWriteNanos = 0L
    private var maxForceNanos = 0L
    private var totalForceNanos = 0L
    private var finalIndexWritten = false
    private var endWritten = false

    private enum class State { OPEN, FINISHED, ABORTED, FAILED }

    init {
        if (channel is FileChannel) {
            require(channel.position() == 0L) { "the writer starts an empty file at offset 0" }
        }
        val encoded = ByteBuffer.wrap(OcrawCodec.encodeHeader(header))
        runIo { writeFully(encoded) }
        bytesWritten = OcrawCodec.HEADER_BYTES.toLong()
        bytesSinceForce = bytesWritten
    }

    /** Sequence number the next chunk will get. */
    val nextChunkSequence: Long get() = nextSequence

    /** Appends a static `METADATA` chunk and returns its sequence. */
    fun writeMetadata(values: Map<String, String>, timestampTicks: Long = lastTicks): Long {
        val payload = ByteBuffer.wrap(OcrawMetadataCbor.encode(values))
        return writeChunk(OcrawChunkId.METADATA.wireId, timestampTicks, payload)
    }

    /**
     * Appends a `RAW_FRAME` chunk and returns its sequence. [data] holds
     * exactly `frameHeader.dataLength` bytes between position and limit; they
     * are written straight from the buffer, which is consumed (its position
     * ends at its limit).
     */
    fun writeRawFrame(frameHeader: OcrawFrameHeader, data: ByteBuffer, timestampTicks: Long): Long {
        checkOpen()
        require(data.remaining() == frameHeader.dataLength) {
            "frame data holds ${data.remaining()} bytes, header says ${frameHeader.dataLength}"
        }
        val payloadLength = OcrawFrameHeader.BYTES + frameHeader.dataLength
        require(payloadLength <= OcrawCodec.MAX_CHUNK_PAYLOAD) { "frame exceeds the bounded payload" }
        checkTicks(timestampTicks)
        this.frameHeader.clear()
        frameHeader.writeTo(this.frameHeader)
        this.frameHeader.flip()
        crc.reset()
        crc.update(this.frameHeader)
        crc.update(data)
        val sequence = nextSequence
        val offset = bytesWritten
        fillChunkHeader(OcrawChunkId.RAW_FRAME.wireId, sequence, timestampTicks, payloadLength, crc.value())
        gather3[0] = chunkHeader
        gather3[1] = this.frameHeader
        gather3[2] = data
        timedWrite(gather3, OcrawCodec.CHUNK_HEADER_BYTES + payloadLength.toLong())
        gather3[2] = null
        committed(OcrawChunkId.RAW_FRAME.wireId, sequence, offset, timestampTicks, payloadLength)
        framesWritten++
        framesSincePeriodic++
        maybePeriodicIndex(timestampTicks)
        maybeForce()
        return sequence
    }

    /**
     * Appends a `FRAME_METADATA` chunk for the `RAW_FRAME` with sequence
     * [frameSequence] and returns its own sequence. The `sequence` key is set
     * by the writer.
     */
    fun writeFrameMetadata(frameSequence: Long, values: Map<String, String>, timestampTicks: Long = lastTicks): Long {
        require(frameSequence in 0 until nextSequence) { "frame metadata must follow its frame" }
        require(SEQUENCE_KEY !in values) { "the writer sets the $SEQUENCE_KEY key" }
        val payload = ByteBuffer.wrap(OcrawMetadataCbor.encode(values + (SEQUENCE_KEY to frameSequence.toString())))
        return writeChunk(OcrawChunkId.FRAME_METADATA.wireId, timestampTicks, payload)
    }

    /** Appends an opaque chunk; indexes and END are reserved to the writer. */
    fun writeChunk(wireId: Int, timestampTicks: Long, payload: ByteBuffer): Long {
        checkOpen()
        require(wireId in 0..0xffff) { "chunk id must fit uint16" }
        require(OcrawIndexTable.isIndexed(wireId)) { "indexes and END are written by the writer" }
        require(wireId != OcrawChunkId.RAW_FRAME.wireId) { "use writeRawFrame" }
        checkTicks(timestampTicks)
        val sequence = appendChunk(wireId, timestampTicks, payload)
        maybeForce()
        return sequence
    }

    /** Appends `FINAL_INDEX` and `END`, forces, and closes the writer. Idempotent. */
    fun finish(): OcrawStreamStats {
        if (state == State.FINISHED) return stats()
        checkOpen()
        if (index.payloadBytes() <= OcrawCodec.MAX_CHUNK_PAYLOAD) {
            appendChunk(OcrawChunkId.FINAL_INDEX.wireId, lastTicks, index.encode())
            finalIndexWritten = true
        }
        appendChunk(OcrawChunkId.END.wireId, lastTicks, ByteBuffer.allocate(0))
        endWritten = true
        if (options.forceOnFinish) forceNow()
        state = State.FINISHED
        return stats()
    }

    /**
     * Stops without `FINAL_INDEX` or `END`, forcing what was written so far.
     * The file is a recoverable prefix. A no-op after [finish] or a failure.
     */
    fun abort(): OcrawStreamStats {
        if (state == State.OPEN) {
            state = State.ABORTED
            try {
                forceNow()
            } catch (_: IOException) {
                state = State.FAILED
            }
        }
        return stats()
    }

    fun stats(): OcrawStreamStats = OcrawStreamStats(
        bytesWritten = bytesWritten,
        framesWritten = framesWritten,
        chunksWritten = chunksWritten,
        periodicIndexCount = periodicIndexCount,
        forceCount = forceCount,
        maxWriteNanos = maxWriteNanos,
        totalWriteNanos = totalWriteNanos,
        maxForceNanos = maxForceNanos,
        totalForceNanos = totalForceNanos,
        finalIndexWritten = finalIndexWritten,
        endWritten = endWritten,
    )

    private fun appendChunk(wireId: Int, timestampTicks: Long, payload: ByteBuffer): Long {
        val payloadLength = payload.remaining()
        require(payloadLength <= OcrawCodec.MAX_CHUNK_PAYLOAD) { "chunk exceeds the bounded payload" }
        crc.reset()
        crc.update(payload)
        val sequence = nextSequence
        val offset = bytesWritten
        fillChunkHeader(wireId, sequence, timestampTicks, payloadLength, crc.value())
        gather2[0] = chunkHeader
        gather2[1] = payload
        timedWrite(gather2, OcrawCodec.CHUNK_HEADER_BYTES + payloadLength.toLong())
        gather2[1] = null
        committed(wireId, sequence, offset, timestampTicks, payloadLength)
        return sequence
    }

    private fun committed(wireId: Int, sequence: Long, offset: Long, timestampTicks: Long, payloadLength: Int) {
        val total = OcrawCodec.CHUNK_HEADER_BYTES.toLong() + payloadLength
        nextSequence = sequence + 1
        bytesWritten += total
        bytesSinceForce += total
        chunksWritten++
        lastTicks = timestampTicks
        if (OcrawIndexTable.isIndexed(wireId)) index.add(sequence, offset, timestampTicks)
    }

    private fun maybePeriodicIndex(timestampTicks: Long) {
        if (lastPeriodicTicks < 0) lastPeriodicTicks = timestampTicks
        val dueByTime = (timestampTicks - lastPeriodicTicks) >= ticksPerIndexInterval
        val dueByFrames = framesSincePeriodic >= options.periodicIndexFrames
        val dueBySize = index.payloadBytes(lastPeriodicEntry) + OcrawIndexTable.ENTRY_BYTES * 4 >
            OcrawCodec.MAX_CHUNK_PAYLOAD
        if (!dueByTime && !dueByFrames && !dueBySize) return
        val end = index.size
        appendChunk(OcrawChunkId.PERIODIC_INDEX.wireId, timestampTicks, index.encode(lastPeriodicEntry, end))
        lastPeriodicEntry = end
        lastPeriodicTicks = timestampTicks
        framesSincePeriodic = 0
        periodicIndexCount++
    }

    private fun maybeForce() {
        if (bytesSinceForce >= options.forceEveryBytes) forceNow()
    }

    private fun forceNow() {
        val action = force ?: return
        val start = System.nanoTime()
        runIo { action() }
        val elapsed = System.nanoTime() - start
        maxForceNanos = maxOf(maxForceNanos, elapsed)
        totalForceNanos += elapsed
        forceCount++
        bytesSinceForce = 0
    }

    private fun fillChunkHeader(wireId: Int, sequence: Long, ticks: Long, payloadLength: Int, crcValue: Int) {
        chunkHeader.clear()
        chunkHeader.putShort(wireId.toShort())
        chunkHeader.putShort(0)
        chunkHeader.putLong(sequence)
        chunkHeader.putLong(ticks)
        chunkHeader.putLong(timeBase.numerator)
        chunkHeader.putLong(timeBase.denominator)
        chunkHeader.putInt(payloadLength)
        chunkHeader.putInt(crcValue)
        chunkHeader.flip()
    }

    private fun timedWrite(buffers: Array<ByteBuffer?>, total: Long) {
        val start = System.nanoTime()
        runIo {
            @Suppress("UNCHECKED_CAST")
            val nonNull = buffers as Array<ByteBuffer>
            if (gathering != null) {
                var remaining = total
                while (remaining > 0) {
                    val written = gathering.write(nonNull)
                    if (written < 0) throw IOException("channel closed")
                    remaining -= written
                }
            } else {
                nonNull.forEach(::writeFully)
            }
        }
        val elapsed = System.nanoTime() - start
        maxWriteNanos = maxOf(maxWriteNanos, elapsed)
        totalWriteNanos += elapsed
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) < 0) throw IOException("channel closed")
        }
    }

    private inline fun runIo(block: () -> Unit) {
        try {
            block()
        } catch (error: IOException) {
            state = State.FAILED
            throw error
        } catch (error: RuntimeException) {
            state = State.FAILED
            throw error
        }
    }

    private fun checkOpen() {
        check(state == State.OPEN) { "writer is ${state.name.lowercase()}" }
    }

    private fun checkTicks(timestampTicks: Long) {
        require(timestampTicks >= 0) { "timestamp must be non-negative" }
    }

    companion object {
        /** Key the writer adds to every `FRAME_METADATA` map. */
        const val SEQUENCE_KEY = "sequence"
    }
}
