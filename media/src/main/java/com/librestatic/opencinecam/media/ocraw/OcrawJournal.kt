// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Result of closing an append-only writer. */
enum class OcrawWriteOutcome {
    FINALIZED,
    INTERRUPTED,
    ALREADY_FINALIZED,
}

data class OcrawWriteResult(
    val bytes: ByteArray,
    val outcome: OcrawWriteOutcome,
    val periodicIndexCount: Int,
    val finalIndexAppended: Boolean,
    val endChunkAppended: Boolean,
)

/** Compact, deterministic binary representation for periodic and final indexes. */
object OcrawIndexCodec {
    private const val ENTRY_BYTES = 24

    fun encode(entries: List<OcrawIndexEntry>): ByteArray {
        require(entries.size <= 0xffff) { "index has too many entries" }
        val buffer = ByteBuffer.allocate(4 + entries.size * ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(entries.size)
        entries.forEach { entry ->
            require(entry.sequence >= 0 && entry.fileOffset >= 0 && entry.timestampTicks >= 0) {
                "index values must be non-negative"
            }
            buffer.putLong(entry.sequence)
            buffer.putLong(entry.fileOffset)
            buffer.putLong(entry.timestampTicks)
        }
        return buffer.array()
    }

    fun decode(payload: ByteArray): List<OcrawIndexEntry> {
        require(payload.size >= 4) { "truncated index payload" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.int
        require(count >= 0 && count <= (payload.size - 4) / ENTRY_BYTES) { "invalid index payload" }
        require(payload.size == 4 + count * ENTRY_BYTES) { "invalid index payload" }
        return buildList(count) {
            repeat(count) {
                add(OcrawIndexEntry(buffer.long, buffer.long, buffer.long))
            }
        }.also { entries ->
            require(entries.all { it.sequence >= 0 && it.fileOffset >= 0 && it.timestampTicks >= 0 }) {
                "index values must be non-negative"
            }
        }
    }
}

/**
 * Bounded append journal with periodic index checkpoints and an explicit end
 * chunk. A caller that closes without [finish] gets an interrupted result and
 * readers can recover every complete chunk before the interruption.
 */
class OcrawAppendJournal(
    private val maxChunks: Int = 4096,
    private val journalInterval: Int = 64,
) {
    private val writer = OcrawAppendWriter(maxChunks)
    private var interrupted = false
    private var lastResult: OcrawWriteResult? = null

    init {
        require(journalInterval > 0) { "journalInterval must be positive" }
    }

    fun append(commandId: Long, chunk: OcrawChunk): OcrawAppendStatus = writer.append(commandId, chunk)

    fun cancel(commandId: Long): OcrawAppendStatus = writer.cancel(commandId)

    fun snapshot(): List<OcrawChunk> = writer.snapshot()

    fun interrupt(header: OcrawHeader): OcrawWriteResult {
        lastResult?.let { return it.copy(outcome = OcrawWriteOutcome.ALREADY_FINALIZED) }
        writer.close()
        val bytes = OcrawCodec.encodeContainer(header, writer.snapshot())
        interrupted = true
        return OcrawWriteResult(bytes, OcrawWriteOutcome.INTERRUPTED, 0, false, false).also { lastResult = it }
    }

    fun finish(header: OcrawHeader): OcrawWriteResult {
        lastResult?.let { return it.copy(outcome = OcrawWriteOutcome.ALREADY_FINALIZED) }
        if (interrupted) return interrupt(header)
        val userChunks = writer.snapshot()
        writer.close()
        val output = ArrayList<OcrawChunk>(userChunks.size + 3)
        val entries = ArrayList<OcrawIndexEntry>(userChunks.size)
        val periodicCounts = ArrayList<Int>()
        var offset = OcrawCodec.HEADER_BYTES.toLong()
        var syntheticSequence = userChunks.maxOfOrNull { it.sequence }?.let { it + 1 } ?: 1
        userChunks.forEachIndexed { index, chunk ->
            output += chunk
            entries += OcrawIndexEntry(chunk.sequence, offset, chunk.timestampTicks)
            offset += OcrawCodec.encodeChunk(chunk).size
            if ((index + 1) % journalInterval == 0) {
                val checkpoint = OcrawChunk(
                    wireId = OcrawChunkId.PERIODIC_INDEX.wireId,
                    sequence = syntheticSequence++,
                    timestampTicks = chunk.timestampTicks,
                    timeBase = chunk.timeBase,
                    payload = OcrawIndexCodec.encode(entries),
                )
                output += checkpoint
                periodicCounts += entries.size
                offset += OcrawCodec.encodeChunk(checkpoint).size
            }
        }
        val finalIndex = OcrawChunk(
            wireId = OcrawChunkId.FINAL_INDEX.wireId,
            sequence = syntheticSequence++,
            timestampTicks = entries.lastOrNull()?.timestampTicks ?: 0,
            timeBase = entries.lastOrNull()?.let { userChunks.last().timeBase } ?: OcrawTimeBase(1, 1),
            payload = OcrawIndexCodec.encode(entries),
        )
        output += finalIndex
        val end = OcrawChunk(
            wireId = OcrawChunkId.END.wireId,
            sequence = syntheticSequence,
            timestampTicks = finalIndex.timestampTicks,
            timeBase = finalIndex.timeBase,
            payload = ByteArray(0),
        )
        output += end
        val bytes = OcrawCodec.encodeContainer(header, output)
        return OcrawWriteResult(bytes, OcrawWriteOutcome.FINALIZED, periodicCounts.size, true, true)
            .also { lastResult = it }
    }
}
