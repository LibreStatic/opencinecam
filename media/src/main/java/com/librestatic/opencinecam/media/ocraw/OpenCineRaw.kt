// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

/** Stable chunk identifiers for the append-only OpenCine RAW v1 container. */
enum class OcrawChunkId(val wireId: Int) {
    METADATA(0x0001),
    RAW_FRAME(0x0010),
    VIDEO_FRAME(0x0011),
    AUDIO_PCM16(0x0020),
    PERIODIC_INDEX(0x0030),
    FINAL_INDEX(0x0031),
    END(0x00ff),
}

data class OcrawUnknownChunk(val wireId: Int)

data class OcrawTimeBase(val numerator: Long, val denominator: Long) {
    init {
        require(numerator > 0) { "time-base numerator must be positive" }
        require(denominator > 0) { "time-base denominator must be positive" }
    }
}

data class OcrawHeader(val uuid: ByteArray, val flags: Int = 0) {
    init {
        require(uuid.size == 16) { "v1 header UUID must be 16 bytes" }
        require(flags in 0..0xffff) { "header flags must fit uint16" }
    }

    override fun equals(other: Any?): Boolean =
        other is OcrawHeader && flags == other.flags && uuid.contentEquals(other.uuid)

    override fun hashCode(): Int = 31 * uuid.contentHashCode() + flags
}

data class OcrawChunk(
    val wireId: Int,
    val sequence: Long,
    val timestampTicks: Long,
    val timeBase: OcrawTimeBase,
    val payload: ByteArray,
    val flags: Int = 0,
) {
    init {
        require(wireId in 0..0xffff) { "chunk id must fit uint16" }
        require(sequence >= 0) { "sequence must be non-negative" }
        require(timestampTicks >= 0) { "timestamp must be non-negative" }
        require(flags in 0..0xffff) { "chunk flags must fit uint16" }
        require(payload.size <= OcrawCodec.MAX_CHUNK_PAYLOAD) { "chunk exceeds bounded payload" }
    }

    fun knownId(): OcrawChunkId? = OcrawChunkId.entries.firstOrNull { it.wireId == wireId }
}

data class OcrawIndexEntry(
    val sequence: Long,
    val fileOffset: Long,
    val timestampTicks: Long,
)

data class OcrawScanResult(
    val header: OcrawHeader?,
    val chunks: List<OcrawChunk>,
    val lastValidOffset: Int,
    val truncated: Boolean,
    val staleEvidence: Boolean,
)

enum class OcrawAppendStatus {
    APPENDED,
    DUPLICATE_COMMAND,
    CANCELLED,
    CLOSED,
    CAPACITY_EXCEEDED,
}

/** Deterministic bounded append journal used by writers and crash fixtures. */
class OcrawAppendWriter(private val maxChunks: Int = 4096) {
    private val commandIds = HashSet<Long>()
    private val cancelledCommands = HashSet<Long>()
    private val chunks = ArrayList<OcrawChunk>()
    private var closed = false

    init {
        require(maxChunks > 0) { "maxChunks must be positive" }
    }

    fun append(commandId: Long, chunk: OcrawChunk): OcrawAppendStatus {
        if (closed) return OcrawAppendStatus.CLOSED
        if (commandId <= 0) return OcrawAppendStatus.DUPLICATE_COMMAND
        if (cancelledCommands.contains(commandId)) return OcrawAppendStatus.CANCELLED
        if (commandIds.contains(commandId)) return OcrawAppendStatus.DUPLICATE_COMMAND
        if (chunks.size >= maxChunks) return OcrawAppendStatus.CAPACITY_EXCEEDED
        commandIds += commandId
        chunks += chunk
        return OcrawAppendStatus.APPENDED
    }

    fun cancel(commandId: Long): OcrawAppendStatus {
        if (closed) return OcrawAppendStatus.CLOSED
        if (commandId <= 0 || commandIds.contains(commandId)) {
            return OcrawAppendStatus.DUPLICATE_COMMAND
        }
        cancelledCommands += commandId
        return OcrawAppendStatus.CANCELLED
    }

    fun close() {
        closed = true
    }

    fun snapshot(): List<OcrawChunk> = Collections.unmodifiableList(chunks.toList())
}

object OcrawMetadataCbor {
    /** Canonical CBOR map of UTF-8 text keys to UTF-8 text values. */
    fun encode(values: Map<String, String>): ByteArray {
        require(values.size <= 0xffff) { "metadata map is too large" }
        val sorted = values.toSortedMap()
        val output = ArrayList<Byte>()
        writeTypeAndLength(output, 5, sorted.size.toLong())
        sorted.forEach { (key, value) ->
            writeText(output, key)
            writeText(output, value)
        }
        return output.toByteArray()
    }

    fun decode(encoded: ByteArray): Map<String, String> {
        var offset = 0
        val (major, count) = readTypeAndLength(encoded, offset)
        require(major == 5) { "metadata must be a CBOR map" }
        offset += count.second
        require(count.first <= 0xffff) { "metadata map is too large" }
        val values = linkedMapOf<String, String>()
        repeat(count.first.toInt()) {
            val key = readText(encoded, offset)
            offset += key.second
            val value = readText(encoded, offset)
            offset += value.second
            values[key.first] = value.first
        }
        require(offset == encoded.size) { "trailing metadata bytes" }
        return values.toMap()
    }

    private fun writeText(output: MutableList<Byte>, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xffff) { "metadata text is too long" }
        writeTypeAndLength(output, 3, bytes.size.toLong())
        bytes.forEach(output::add)
    }

    private fun writeTypeAndLength(output: MutableList<Byte>, major: Int, length: Long) {
        require(major in 0..7 && length >= 0)
        when {
            length < 24 -> output += ((major shl 5) or length.toInt()).toByte()
            length <= 0xff -> {
                output += ((major shl 5) or 24).toByte()
                output += length.toByte()
            }
            else -> {
                output += ((major shl 5) or 25).toByte()
                output += (length ushr 8).toByte()
                output += length.toByte()
            }
        }
    }

    private fun readTypeAndLength(bytes: ByteArray, offset: Int): Pair<Int, Pair<Long, Int>> {
        require(offset in bytes.indices) { "truncated CBOR" }
        val initial = bytes[offset].toInt() and 0xff
        val major = initial ushr 5
        val additional = initial and 0x1f
        return when {
            additional < 24 -> major to (additional.toLong() to 1)
            additional == 24 -> {
                require(offset + 1 < bytes.size) { "truncated CBOR" }
                major to ((bytes[offset + 1].toLong() and 0xff) to 2)
            }
            additional == 25 -> {
                require(offset + 2 < bytes.size) { "truncated CBOR" }
                val length = ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                    (bytes[offset + 2].toLong() and 0xff)
                major to (length to 3)
            }
            else -> error("unsupported CBOR length")
        }
    }

    private fun readText(bytes: ByteArray, offset: Int): Pair<String, Int> {
        val (major, lengthAndBytes) = readTypeAndLength(bytes, offset)
        require(major == 3) { "metadata values must be text" }
        val length = lengthAndBytes.first.toInt()
        val start = offset + lengthAndBytes.second
        val end = start + length
        require(end <= bytes.size) { "truncated CBOR text" }
        return bytes.copyOfRange(start, end).toString(Charsets.UTF_8) to (end - offset)
    }
}

object OcrawCodec {
    const val VERSION = 1
    const val HEADER_BYTES = 32
    const val CHUNK_HEADER_BYTES = 44
    const val MAX_CHUNK_PAYLOAD = 64 * 1024 * 1024
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'R'.code.toByte(),
        'A'.code.toByte(), 'W'.code.toByte(), 0, 1, 0)

    fun encodeHeader(header: OcrawHeader): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.putShort(VERSION.toShort())
        buffer.putShort(header.flags.toShort())
        buffer.put(header.uuid)
        buffer.putInt(0)
        return buffer.array()
    }

    fun decodeHeader(encoded: ByteArray, offset: Int = 0): OcrawHeader {
        require(offset >= 0 && encoded.size - offset >= HEADER_BYTES) { "truncated ocraw header" }
        require(encoded.copyOfRange(offset, offset + MAGIC.size).contentEquals(MAGIC)) {
            "invalid ocraw magic"
        }
        val buffer = ByteBuffer.wrap(encoded, offset, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(offset + MAGIC.size)
        require(buffer.short.toInt() and 0xffff == VERSION) { "unsupported ocraw version" }
        val flags = buffer.short.toInt() and 0xffff
        val uuid = ByteArray(16)
        buffer.get(uuid)
        buffer.int
        return OcrawHeader(uuid, flags)
    }

    fun encodeChunk(chunk: OcrawChunk): ByteArray {
        val crc = crc32c(chunk.payload)
        val buffer = ByteBuffer.allocate(CHUNK_HEADER_BYTES + chunk.payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(chunk.wireId.toShort())
        buffer.putShort(chunk.flags.toShort())
        buffer.putLong(chunk.sequence)
        buffer.putLong(chunk.timestampTicks)
        buffer.putLong(chunk.timeBase.numerator)
        buffer.putLong(chunk.timeBase.denominator)
        buffer.putInt(chunk.payload.size)
        buffer.putInt(crc)
        buffer.put(chunk.payload)
        return buffer.array()
    }

    fun decodeChunk(encoded: ByteArray, offset: Int = 0): OcrawChunk {
        require(offset >= 0 && encoded.size - offset >= CHUNK_HEADER_BYTES) { "truncated chunk" }
        val buffer = ByteBuffer.wrap(encoded, offset, encoded.size - offset).order(ByteOrder.LITTLE_ENDIAN)
        val wireId = buffer.short.toInt() and 0xffff
        val flags = buffer.short.toInt() and 0xffff
        val sequence = buffer.long
        val timestamp = buffer.long
        val numerator = buffer.long
        val denominator = buffer.long
        val payloadLength = buffer.int
        val expectedCrc = buffer.int
        require(payloadLength in 0..MAX_CHUNK_PAYLOAD) { "invalid chunk length" }
        require(encoded.size - offset - CHUNK_HEADER_BYTES >= payloadLength) { "truncated chunk payload" }
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        val actualCrc = crc32c(payload)
        require(expectedCrc == actualCrc) { "chunk CRC32C mismatch" }
        return OcrawChunk(wireId, sequence, timestamp, OcrawTimeBase(numerator, denominator), payload, flags)
    }

    fun encodeContainer(header: OcrawHeader, chunks: List<OcrawChunk>): ByteArray {
        require(chunks.size <= 0xffff) { "too many chunks" }
        val encodedChunks = chunks.map(::encodeChunk)
        val output = ByteArray(HEADER_BYTES + encodedChunks.sumOf { it.size })
        encodeHeader(header).copyInto(output)
        var offset = HEADER_BYTES
        encodedChunks.forEach { encoded ->
            encoded.copyInto(output, offset)
            offset += encoded.size
        }
        return output
    }

    fun scanContainer(encoded: ByteArray): OcrawScanResult {
        if (encoded.size < HEADER_BYTES) {
            return OcrawScanResult(null, emptyList(), 0, true, true)
        }
        val header = try {
            decodeHeader(encoded)
        } catch (_: IllegalArgumentException) {
            return OcrawScanResult(null, emptyList(), 0, true, true)
        }
        val chunks = ArrayList<OcrawChunk>()
        var offset = HEADER_BYTES
        var stale = false
        while (offset < encoded.size) {
            if (encoded.size - offset < CHUNK_HEADER_BYTES) {
                stale = true
                break
            }
            val payloadLength = ByteBuffer.wrap(encoded, offset + 36, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            if (payloadLength !in 0..MAX_CHUNK_PAYLOAD ||
                encoded.size - offset < CHUNK_HEADER_BYTES + payloadLength) {
                stale = true
                break
            }
            try {
                chunks += decodeChunk(encoded, offset)
            } catch (_: IllegalArgumentException) {
                stale = true
                break
            }
            offset += CHUNK_HEADER_BYTES + payloadLength
        }
        return OcrawScanResult(header, chunks.toList(), offset, stale, stale)
    }

    fun pcm16Le(samples: ShortArray): ByteArray {
        val output = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(output::putShort)
        return output.array()
    }

    /** Castagnoli CRC32C kept in Kotlin so minSdk 29 does not call API 34. */
    private fun crc32c(bytes: ByteArray): Int {
        var crc = -1
        bytes.forEach { value ->
            crc = crc xor (value.toInt() and 0xff)
            repeat(8) {
                crc = if ((crc and 1) != 0) {
                    (crc ushr 1) xor 0x82f63b78.toInt()
                } else {
                    crc ushr 1
                }
            }
        }
        return crc.inv()
    }
}
