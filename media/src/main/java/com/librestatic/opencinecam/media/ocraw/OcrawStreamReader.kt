// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** One `RAW_FRAME` chunk found by a scan. [header] is null when its frame header is not v1-readable. */
data class OcrawStreamFrame(
    val sequence: Long,
    val fileOffset: Long,
    val timestampTicks: Long,
    val header: OcrawFrameHeader?,
)

/**
 * One `AUDIO_PCM16` chunk found by [OcrawStreamReader.indexFrames]: interleaved little-endian PCM16 whose
 * [timestampTicks] is the capture time of its first sample, in the RAW frame chunks' time base.
 */
data class OcrawAudioChunk(
    val sequence: Long,
    val fileOffset: Long,
    val timestampTicks: Long,
    val payloadLength: Int,
)

/** Result of [OcrawStreamReader.scan]; the file is never loaded whole. */
data class OcrawStreamScan(
    val header: OcrawHeader?,
    val fileSize: Long,
    /** End of the last complete, CRC-valid chunk (the header end when there is none). */
    val lastValidOffset: Long,
    /** A torn, oversized or corrupt chunk (or trailing bytes) follows [lastValidOffset]. */
    val staleEvidence: Boolean,
    val chunkCount: Int,
    val frameCount: Int,
    /** Present when the scan collected frames. */
    val frames: List<OcrawStreamFrame>,
    /** Every `METADATA` chunk merged in file order; a later key wins. */
    val metadata: Map<String, String>,
    /** `FRAME_METADATA` maps keyed by the `RAW_FRAME` sequence they reference, without the `sequence` key. */
    val frameMetadata: Map<Long, Map<String, String>>,
    val periodicIndexCount: Int,
    /** The last valid `FINAL_INDEX`, if any. */
    val finalIndex: List<OcrawIndexEntry>?,
    /** The index rebuilt from the scan: every chunk except indexes and END. */
    val rebuiltIndex: OcrawIndexTable,
    val hasEnd: Boolean,
    /** The last valid chunk is END. */
    val endsWithEnd: Boolean,
    val sequencesMonotonic: Boolean,
    val maxSequence: Long,
    val lastTimestampTicks: Long,
    val lastTimeBase: OcrawTimeBase?,
    val unknownChunkIds: Set<Int>,
    /** Payload problems inside CRC-valid chunks, such as malformed CBOR. */
    val errors: List<String>,
) {
    /** Finalised by a writer and untouched since: END last, nothing after it. */
    val cleanlyClosed: Boolean
        get() = header != null && endsWithEnd && !staleEvidence && lastValidOffset == fileSize
}

/**
 * Result of [OcrawStreamReader.indexFrames]: where every frame is, read from the
 * chunk and frame headers alone. Frame payloads are not CRC-checked, so a
 * corrupt frame still appears here; reading it with
 * [OcrawStreamReader.readRawFrame] finds it.
 */
data class OcrawFrameIndex(
    val header: OcrawHeader?,
    val fileSize: Long,
    /** End of the last complete chunk the walk accepted (the header end when there is none). */
    val validEnd: Long,
    /** A torn or invalid chunk header, a metadata chunk failing its CRC, or trailing bytes follow [validEnd]. */
    val tornTail: Boolean,
    /** Every `RAW_FRAME` chunk in file order; [OcrawStreamFrame.header] is null when its frame header is unreadable. */
    val frames: List<OcrawStreamFrame>,
    /** Every `METADATA` chunk merged in file order; a later key wins. */
    val metadata: Map<String, String>,
    /** `FRAME_METADATA` maps keyed by the `RAW_FRAME` sequence they reference, without the `sequence` key. */
    val frameMetadata: Map<Long, Map<String, String>>,
    /** The first frame's time base (the first chunk's when there are no frames), null for an empty file. */
    val timeBase: OcrawTimeBase?,
    val hasEnd: Boolean,
    /** Malformed metadata payloads, as in [OcrawStreamScan.errors]. */
    val errors: List<String>,
    /** Every `AUDIO_PCM16` chunk in file order, payloads unread and unchecked. */
    val audio: List<OcrawAudioChunk> = emptyList(),
) {
    /** The take's `orientation` key, parsed with [OcrawOrientation.degrees]. */
    val orientationDegrees: Int get() = OcrawOrientation.degrees(metadata)

    /** For each frame without a `FRAME_METADATA` record, the colour values it inherits; built on first use. */
    private val inheritedColour: Map<Long, Map<String, String>> by lazy { inheritColour(frames, frameMetadata) }

    /**
     * The per-frame metadata to develop the frame with sequence [sequence]: its own
     * `FRAME_METADATA` record when it has one. A frame without one (takes recorded
     * before the engine kept the last capture result lose it on the final frame)
     * inherits only the colour-defining values — `neutral`, the black level
     * (`dynamicBlackLevel`, else `blackLevel`) and `whiteLevel` — each from the nearest
     * earlier frame whose record carries it, else the nearest later one. Exposure, ISO
     * and the rest stay absent. Empty when no frame carries any of them.
     */
    fun effectiveFrameMetadata(sequence: Long): Map<String, String> =
        frameMetadata[sequence] ?: inheritedColour[sequence].orEmpty()

    companion object {
        /** The inherited key groups: a group is taken whole from one record. */
        internal val INHERITED_COLOUR_GROUPS: List<List<String>> = listOf(
            listOf("neutral"),
            listOf("dynamicBlackLevel", "blackLevel"),
            listOf("whiteLevel"),
        )

        private fun inheritColour(
            frames: List<OcrawStreamFrame>,
            frameMetadata: Map<Long, Map<String, String>>,
        ): Map<Long, Map<String, String>> {
            val count = frames.size
            val records = Array(count) { frameMetadata[frames[it].sequence] }
            if (records.none { it == null } || records.all { it == null }) return emptyMap()
            // Per group, the nearest frame whose record has a non-empty value in it: earlier first, else later.
            val sources = INHERITED_COLOUR_GROUPS.map { group ->
                val has = BooleanArray(count) { i -> records[i]?.let { r -> group.any { !r[it].isNullOrEmpty() } } == true }
                val source = IntArray(count) { -1 }
                var last = -1
                for (i in 0 until count) {
                    if (has[i]) last = i
                    source[i] = last
                }
                var next = -1
                for (i in count - 1 downTo 0) {
                    if (has[i]) next = i
                    if (source[i] < 0) source[i] = next
                }
                source
            }
            val inherited = HashMap<Long, Map<String, String>>()
            // Consecutive frames without a record usually share their sources, so share the map too.
            var previousKey: List<Int>? = null
            var previous: Map<String, String> = emptyMap()
            for (i in 0 until count) {
                if (records[i] != null) continue
                val key = sources.map { it[i] }
                if (key != previousKey) {
                    val values = LinkedHashMap<String, String>()
                    INHERITED_COLOUR_GROUPS.forEachIndexed { g, group ->
                        val record = key[g].takeIf { it >= 0 }?.let { records[it] } ?: return@forEachIndexed
                        group.forEach { name -> record[name]?.takeIf { it.isNotEmpty() }?.let { values[name] = it } }
                    }
                    previous = values
                    previousKey = key
                }
                if (previous.isNotEmpty()) inherited[frames[i].sequence] = previous
            }
            return inherited
        }
    }
}

/**
 * The static `orientation` key: the clockwise rotation, in degrees, that shows the frames
 * (stored in sensor orientation) upright, as the MP4 orientation hint and EXIF do.
 */
object OcrawOrientation {
    const val KEY = "orientation"

    /** 0, 90, 180 or 270; 0 when [metadata] has no `orientation` or an invalid one, as older takes. */
    fun degrees(metadata: Map<String, String>): Int = parse(metadata[KEY])

    /** [value] as 0, 90, 180 or 270; 0 for null or anything else. */
    fun parse(value: String?): Int = when (value?.trim()) {
        "90" -> 90
        "180" -> 180
        "270" -> 270
        else -> 0
    }

    /** The TIFF/EXIF `Orientation` (tag 274) value for [degrees]: 1, 6, 3 or 8. */
    fun tiffOrientation(degrees: Int): Int = when (degrees) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }
}

enum class OcrawRecoveryAction {
    /** The file was already cleanly closed. */
    ALREADY_CLEAN,

    /** A torn tail was cut; nothing was appended. */
    TRUNCATED,

    /** The tail (if any) was cut and `FINAL_INDEX` + `END` appended. */
    FINALIZED,

    /** No valid v1 header; the file was left untouched. */
    UNRECOVERABLE,
}

data class OcrawRecoveryResult(
    val action: OcrawRecoveryAction,
    /** The scan before any change. */
    val scan: OcrawStreamScan,
    val truncatedBytes: Long,
    val finalSize: Long,
) {
    val framesRecovered: Int get() = scan.frameCount
}

/**
 * Streaming reader and crash recovery over a [FileChannel]. It reads with
 * positional I/O, so the channel's position is not used, and keeps only one
 * scratch buffer for CRC checks. Not thread-safe.
 */
class OcrawStreamReader(
    private val channel: FileChannel,
    scratchBytes: Int = 4 * 1024 * 1024,
) {
    private val scratch = ByteBuffer.allocateDirect(scratchBytes.coerceAtLeast(OcrawFrameHeader.BYTES))
        .order(ByteOrder.LITTLE_ENDIAN)
    private val chunkHeader = ByteBuffer.allocate(OcrawCodec.CHUNK_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val chunkAndFrameHeader = ByteBuffer.allocate(OcrawCodec.CHUNK_HEADER_BYTES + OcrawFrameHeader.BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
    private val crc = Crc32c.newAccumulator()

    /** Walks every chunk from the header, stopping at the first invalid one. */
    fun scan(collectFrames: Boolean = true): OcrawStreamScan {
        val fileSize = channel.size()
        val header = readFileHeader(fileSize)
        val rebuilt = OcrawIndexTable(1024)
        if (header == null) {
            return OcrawStreamScan(null, fileSize, 0, true, 0, 0, emptyList(), emptyMap(), emptyMap(), 0, null,
                rebuilt, false, false, true, -1, 0, null, emptySet(), emptyList())
        }
        val frames = ArrayList<OcrawStreamFrame>()
        val metadata = LinkedHashMap<String, String>()
        val frameMetadata = LinkedHashMap<Long, Map<String, String>>()
        val unknown = LinkedHashSet<Int>()
        val errors = ArrayList<String>()
        var finalIndex: List<OcrawIndexEntry>? = null
        var offset = OcrawCodec.HEADER_BYTES.toLong()
        var stale = false
        var chunkCount = 0
        var frameCount = 0
        var periodic = 0
        var hasEnd = false
        var lastWasEnd = false
        var monotonic = true
        var maxSequence = -1L
        var previousSequence = -1L
        var lastTicks = 0L
        var lastTimeBase: OcrawTimeBase? = null
        while (offset < fileSize) {
            val chunk = readChunkHeader(offset, fileSize)
            if (chunk == null) {
                stale = true
                break
            }
            val payloadOffset = offset + OcrawCodec.CHUNK_HEADER_BYTES
            val isFrame = chunk.wireId == OcrawChunkId.RAW_FRAME.wireId
            var payload: ByteArray? = null
            var frameHeader: OcrawFrameHeader? = null
            if (chunk.wireId in PAYLOAD_KEPT) {
                payload = ByteArray(chunk.payloadLength)
                readFully(ByteBuffer.wrap(payload), payloadOffset)
                if (Crc32c.compute(payload) != chunk.crc) {
                    stale = true
                    break
                }
            } else {
                val result = checkStreamedPayload(payloadOffset, chunk.payloadLength, chunk.crc, isFrame)
                if (result == null) {
                    stale = true
                    break
                }
                frameHeader = result.frameHeader
            }
            chunkCount++
            if (chunk.sequence <= previousSequence) monotonic = false
            previousSequence = chunk.sequence
            maxSequence = maxOf(maxSequence, chunk.sequence)
            lastTicks = chunk.timestampTicks
            lastTimeBase = chunk.timeBase
            lastWasEnd = chunk.wireId == OcrawChunkId.END.wireId
            if (OcrawIndexTable.isIndexed(chunk.wireId)) rebuilt.add(chunk.sequence, offset, chunk.timestampTicks)
            when (chunk.wireId) {
                OcrawChunkId.RAW_FRAME.wireId -> {
                    frameCount++
                    if (frameHeader == null) errors += "frame ${chunk.sequence}: unreadable frame header"
                    if (collectFrames) {
                        frames += OcrawStreamFrame(chunk.sequence, offset, chunk.timestampTicks, frameHeader)
                    }
                }
                OcrawChunkId.METADATA.wireId -> decodeCbor(payload!!, chunk.sequence, errors)?.let(metadata::putAll)
                OcrawChunkId.FRAME_METADATA.wireId -> decodeCbor(payload!!, chunk.sequence, errors)?.let { map ->
                    val reference = map[OcrawStreamWriter.SEQUENCE_KEY]?.toLongOrNull()
                    if (reference == null) {
                        errors += "frame metadata ${chunk.sequence}: missing sequence"
                    } else {
                        frameMetadata[reference] = map - OcrawStreamWriter.SEQUENCE_KEY
                    }
                }
                OcrawChunkId.PERIODIC_INDEX.wireId -> periodic++
                OcrawChunkId.FINAL_INDEX.wireId -> try {
                    finalIndex = OcrawIndexCodec.decode(payload!!)
                } catch (error: IllegalArgumentException) {
                    errors += "final index ${chunk.sequence}: ${error.message}"
                }
                OcrawChunkId.END.wireId -> hasEnd = true
                else -> if (OcrawChunkId.entries.none { it.wireId == chunk.wireId }) unknown += chunk.wireId
            }
            offset = payloadOffset + chunk.payloadLength
        }
        return OcrawStreamScan(
            header = header,
            fileSize = fileSize,
            lastValidOffset = offset,
            staleEvidence = stale,
            chunkCount = chunkCount,
            frameCount = frameCount,
            frames = frames.toList(),
            metadata = metadata.toMap(),
            frameMetadata = frameMetadata.toMap(),
            periodicIndexCount = periodic,
            finalIndex = finalIndex,
            rebuiltIndex = rebuilt,
            hasEnd = hasEnd,
            endsWithEnd = lastWasEnd,
            sequencesMonotonic = monotonic,
            maxSequence = maxSequence,
            lastTimestampTicks = lastTicks,
            lastTimeBase = lastTimeBase,
            unknownChunkIds = unknown.toSet(),
            errors = errors.toList(),
        )
    }

    /**
     * Walks the chunk headers from the file header without reading frame data:
     * one positional read per `RAW_FRAME` covers its chunk header and 64-byte
     * frame header, then the walk seeks past the payload. Metadata chunks are
     * read, CRC-checked and merged as [scan] does, metadata that follows later
     * frames included. `AUDIO_PCM16` chunks are listed in [OcrawFrameIndex.audio]
     * with their payloads skipped. Other chunks are skipped unchecked. Stops at the first
     * torn or invalid chunk header, or a metadata chunk failing its CRC.
     */
    fun indexFrames(): OcrawFrameIndex {
        val fileSize = channel.size()
        val header = readFileHeader(fileSize)
        if (header == null) {
            return OcrawFrameIndex(null, fileSize, 0, true, emptyList(), emptyMap(), emptyMap(), null, false,
                emptyList())
        }
        val frames = ArrayList<OcrawStreamFrame>()
        val audio = ArrayList<OcrawAudioChunk>()
        val metadata = LinkedHashMap<String, String>()
        val frameMetadata = LinkedHashMap<Long, Map<String, String>>()
        val errors = ArrayList<String>()
        var offset = OcrawCodec.HEADER_BYTES.toLong()
        var torn = false
        var hasEnd = false
        var firstTimeBase: OcrawTimeBase? = null
        var frameTimeBase: OcrawTimeBase? = null
        while (offset < fileSize) {
            chunkAndFrameHeader.clear()
            chunkAndFrameHeader.limit(minOf(fileSize - offset, chunkAndFrameHeader.capacity().toLong()).toInt())
            readFully(chunkAndFrameHeader, offset)
            chunkAndFrameHeader.flip()
            val chunk = parseChunkHeader(chunkAndFrameHeader, offset, fileSize)
            if (chunk == null) {
                torn = true
                break
            }
            val payloadOffset = offset + OcrawCodec.CHUNK_HEADER_BYTES
            if (firstTimeBase == null) firstTimeBase = chunk.timeBase
            when (chunk.wireId) {
                OcrawChunkId.RAW_FRAME.wireId -> {
                    if (frameTimeBase == null) frameTimeBase = chunk.timeBase
                    var frameHeader: OcrawFrameHeader? = null
                    if (chunk.payloadLength >= OcrawFrameHeader.BYTES) {
                        frameHeader = try {
                            OcrawFrameHeader.readFrom(chunkAndFrameHeader)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                        val dataLength = chunk.payloadLength - OcrawFrameHeader.BYTES
                        if (frameHeader != null && frameHeader.dataLength != dataLength) frameHeader = null
                    }
                    frames += OcrawStreamFrame(chunk.sequence, offset, chunk.timestampTicks, frameHeader)
                }
                OcrawChunkId.METADATA.wireId, OcrawChunkId.FRAME_METADATA.wireId -> {
                    val payload = ByteArray(chunk.payloadLength)
                    readFully(ByteBuffer.wrap(payload), payloadOffset)
                    if (Crc32c.compute(payload) != chunk.crc) {
                        torn = true
                        break
                    }
                    val map = decodeCbor(payload, chunk.sequence, errors)
                    if (map != null && chunk.wireId == OcrawChunkId.METADATA.wireId) {
                        metadata.putAll(map)
                    } else if (map != null) {
                        val reference = map[OcrawStreamWriter.SEQUENCE_KEY]?.toLongOrNull()
                        if (reference == null) {
                            errors += "frame metadata ${chunk.sequence}: missing sequence"
                        } else {
                            frameMetadata[reference] = map - OcrawStreamWriter.SEQUENCE_KEY
                        }
                    }
                }
                OcrawChunkId.AUDIO_PCM16.wireId ->
                    audio += OcrawAudioChunk(chunk.sequence, offset, chunk.timestampTicks, chunk.payloadLength)
                OcrawChunkId.END.wireId -> hasEnd = true
            }
            offset = payloadOffset + chunk.payloadLength
        }
        return OcrawFrameIndex(
            header = header,
            fileSize = fileSize,
            validEnd = offset,
            tornTail = torn,
            frames = frames.toList(),
            metadata = metadata.toMap(),
            frameMetadata = frameMetadata.toMap(),
            timeBase = frameTimeBase ?: firstTimeBase,
            hasEnd = hasEnd,
            errors = errors.toList(),
            audio = audio.toList(),
        )
    }

    /**
     * Reads the `AUDIO_PCM16` chunk at [chunkOffset] into `dst.position()`, advancing it by the
     * payload length, which it returns. Throws [IOException] for a truncated or corrupt chunk and
     * [IllegalArgumentException] when the chunk is not audio or [dst] is too small.
     */
    fun readAudioChunk(chunkOffset: Long, dst: ByteBuffer): Int {
        val chunk = readChunkHeader(chunkOffset, channel.size()) ?: throw IOException("invalid chunk at $chunkOffset")
        require(chunk.wireId == OcrawChunkId.AUDIO_PCM16.wireId) { "chunk at $chunkOffset is not audio" }
        val length = chunk.payloadLength
        require(dst.remaining() >= length) { "destination holds ${dst.remaining()} of $length bytes" }
        val start = dst.position()
        val window = dst.duplicate()
        window.limit(start + length)
        readFully(window, chunkOffset + OcrawCodec.CHUNK_HEADER_BYTES)
        crc.reset()
        crc.update(dst, start, length)
        if (crc.value() != chunk.crc) throw IOException("audio CRC32C mismatch at $chunkOffset")
        dst.position(start + length)
        return length
    }

    /**
     * Reads the `RAW_FRAME` chunk at [chunkOffset], puts its frame data at
     * `dst.position()` (advancing it by `dataLength`) and returns its header.
     * Throws [IOException] for a truncated or corrupt chunk and
     * [IllegalArgumentException] when the chunk is not a v1 RAW frame or
     * [dst] is too small.
     */
    fun readRawFrame(chunkOffset: Long, dst: ByteBuffer): OcrawFrameHeader {
        val fileSize = channel.size()
        val chunk = readChunkHeader(chunkOffset, fileSize) ?: throw IOException("invalid chunk at $chunkOffset")
        require(chunk.wireId == OcrawChunkId.RAW_FRAME.wireId) { "chunk at $chunkOffset is not a RAW frame" }
        require(chunk.payloadLength >= OcrawFrameHeader.BYTES) { "RAW frame payload lacks its header" }
        val payloadOffset = chunkOffset + OcrawCodec.CHUNK_HEADER_BYTES
        scratch.clear().limit(OcrawFrameHeader.BYTES)
        readFully(scratch, payloadOffset)
        scratch.flip()
        val header = OcrawFrameHeader.readFrom(scratch.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        val dataLength = chunk.payloadLength - OcrawFrameHeader.BYTES
        require(header.dataLength == dataLength) { "frame data length disagrees with the chunk" }
        require(dst.remaining() >= dataLength) { "destination holds ${dst.remaining()} of $dataLength bytes" }
        crc.reset()
        crc.update(scratch, 0, OcrawFrameHeader.BYTES)
        val start = dst.position()
        val window = dst.duplicate()
        window.limit(start + dataLength)
        readFully(window, payloadOffset + OcrawFrameHeader.BYTES)
        crc.update(dst, start, dataLength)
        if (crc.value() != chunk.crc) throw IOException("RAW frame CRC32C mismatch at $chunkOffset")
        dst.position(start + dataLength)
        return header
    }

    private class ChunkHeader(
        val wireId: Int,
        val sequence: Long,
        val timestampTicks: Long,
        val timeBase: OcrawTimeBase,
        val payloadLength: Int,
        val crc: Int,
    )

    private class StreamedPayload(val frameHeader: OcrawFrameHeader?)

    private fun readFileHeader(fileSize: Long): OcrawHeader? {
        if (fileSize < OcrawCodec.HEADER_BYTES) return null
        val bytes = ByteArray(OcrawCodec.HEADER_BYTES)
        readFully(ByteBuffer.wrap(bytes), 0)
        return try {
            OcrawCodec.decodeHeader(bytes)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The chunk header at [offset] when it is complete, in bounds and its payload fits the file. */
    private fun readChunkHeader(offset: Long, fileSize: Long): ChunkHeader? {
        if (offset < OcrawCodec.HEADER_BYTES || fileSize - offset < OcrawCodec.CHUNK_HEADER_BYTES) return null
        chunkHeader.clear()
        readFully(chunkHeader, offset)
        chunkHeader.flip()
        return parseChunkHeader(chunkHeader, offset, fileSize)
    }

    /** Parses a chunk header from [buffer]'s position, advancing it; null as for [readChunkHeader]. */
    private fun parseChunkHeader(buffer: ByteBuffer, offset: Long, fileSize: Long): ChunkHeader? {
        if (offset < OcrawCodec.HEADER_BYTES || buffer.remaining() < OcrawCodec.CHUNK_HEADER_BYTES) return null
        val wireId = buffer.short.toInt() and 0xffff
        buffer.short
        val sequence = buffer.long
        val ticks = buffer.long
        val numerator = buffer.long
        val denominator = buffer.long
        val payloadLength = buffer.int
        val crcValue = buffer.int
        if (sequence < 0 || ticks < 0 || numerator <= 0 || denominator <= 0) return null
        if (payloadLength !in 0..OcrawCodec.MAX_CHUNK_PAYLOAD) return null
        if (fileSize - offset - OcrawCodec.CHUNK_HEADER_BYTES < payloadLength) return null
        return ChunkHeader(wireId, sequence, ticks, OcrawTimeBase(numerator, denominator), payloadLength, crcValue)
    }

    /** CRC-checks a large payload through [scratch]; null on mismatch. Decodes a leading frame header. */
    private fun checkStreamedPayload(
        payloadOffset: Long,
        length: Int,
        expectedCrc: Int,
        decodeFrameHeader: Boolean,
    ): StreamedPayload? {
        crc.reset()
        var frameHeader: OcrawFrameHeader? = null
        var done = 0
        while (done < length) {
            scratch.clear()
            scratch.limit(minOf(scratch.capacity(), length - done))
            readFully(scratch, payloadOffset + done)
            scratch.flip()
            if (decodeFrameHeader && done == 0 && scratch.remaining() >= OcrawFrameHeader.BYTES) {
                frameHeader = try {
                    OcrawFrameHeader.readFrom(scratch.duplicate().order(ByteOrder.LITTLE_ENDIAN))
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            crc.update(scratch)
            done += scratch.remaining()
        }
        if (crc.value() != expectedCrc) return null
        if (frameHeader != null && frameHeader.dataLength != length - OcrawFrameHeader.BYTES) frameHeader = null
        return StreamedPayload(frameHeader)
    }

    private fun decodeCbor(payload: ByteArray, sequence: Long, errors: MutableList<String>): Map<String, String>? =
        try {
            OcrawMetadataCbor.decode(payload)
        } catch (error: IllegalArgumentException) {
            errors += "metadata $sequence: ${error.message}"
            null
        } catch (error: IllegalStateException) {
            errors += "metadata $sequence: ${error.message}"
            null
        }

    private fun readFully(buffer: ByteBuffer, position: Long) {
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read < 0) throw EOFException("unexpected end of file at $at")
            at += read
        }
    }

    companion object {
        /** Chunks whose payload the scan decodes; the rest are CRC-checked in scratch-sized pieces. */
        private val PAYLOAD_KEPT = setOf(
            OcrawChunkId.METADATA.wireId,
            OcrawChunkId.FRAME_METADATA.wireId,
            OcrawChunkId.FINAL_INDEX.wireId,
        )

        /**
         * Makes an interrupted take readable: cuts everything after the last
         * valid chunk and, with [finalize], appends `FINAL_INDEX` and `END`
         * so the file verifies as closed. The appended chunks continue the
         * sequence and reuse the last chunk's timestamp and time base. A file
         * without a valid header is left untouched.
         */
        fun recover(channel: FileChannel, finalize: Boolean = true): OcrawRecoveryResult {
            val scan = OcrawStreamReader(channel).scan(collectFrames = true)
            if (scan.header == null) {
                return OcrawRecoveryResult(OcrawRecoveryAction.UNRECOVERABLE, scan, 0, scan.fileSize)
            }
            if (scan.cleanlyClosed) {
                return OcrawRecoveryResult(OcrawRecoveryAction.ALREADY_CLEAN, scan, 0, scan.fileSize)
            }
            val truncated = scan.fileSize - scan.lastValidOffset
            if (truncated > 0) channel.truncate(scan.lastValidOffset)
            if (!finalize || scan.endsWithEnd) {
                channel.force(true)
                return OcrawRecoveryResult(OcrawRecoveryAction.TRUNCATED, scan, truncated, channel.size())
            }
            val timeBase = scan.lastTimeBase ?: OcrawTimeBase(1, 1_000_000_000)
            var sequence = scan.maxSequence + 1
            val chunks = ArrayList<OcrawChunk>(2)
            if (scan.rebuiltIndex.payloadBytes() <= OcrawCodec.MAX_CHUNK_PAYLOAD) {
                val buffer = scan.rebuiltIndex.encode()
                val payload = ByteArray(buffer.remaining()).also(buffer::get)
                chunks += OcrawChunk(OcrawChunkId.FINAL_INDEX.wireId, sequence++, scan.lastTimestampTicks, timeBase,
                    payload)
            }
            chunks += OcrawChunk(OcrawChunkId.END.wireId, sequence, scan.lastTimestampTicks, timeBase, ByteArray(0))
            var at = scan.lastValidOffset
            chunks.forEach { chunk ->
                val bytes = ByteBuffer.wrap(OcrawCodec.encodeChunk(chunk))
                while (bytes.hasRemaining()) at += channel.write(bytes, at)
            }
            channel.force(true)
            return OcrawRecoveryResult(OcrawRecoveryAction.FINALIZED, scan, truncated, channel.size())
        }
    }
}
