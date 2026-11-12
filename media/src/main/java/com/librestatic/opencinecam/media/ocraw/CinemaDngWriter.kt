// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import com.librestatic.opencinecam.media.raw.NativeRawCodec
import com.librestatic.opencinecam.media.raw.RawQuantisation
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel

/** A frame that cannot become a DNG; [reason] says why, as for the playback source. */
class OcrawDngException(message: String, val reason: OcrawMissingReason, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Where the bytes of one frame's DNG come from: the first [headerSize] bytes are
 * [header] (TIFF header, both IFDs with their values, alignment pad), and the
 * [imageBytes] after them are the strips or tiles back to back.
 */
class CinemaDngLayout internal constructor(
    val frame: Int,
    private val headerBytes: ByteArray,
    val imageBytes: Long,
    /** DNG `Compression`: 1 (uncompressed 16-bit) or 7 (lossless JPEG tiles). */
    val compression: Int,
    /** For an LJ92 pass-through frame, the file offset the tiles are copied from verbatim; else -1. */
    internal val sourceOffset: Long,
) {
    val headerSize: Int get() = headerBytes.size

    /** The exact DNG length in bytes. */
    val size: Long get() = headerBytes.size + imageBytes

    fun header(): ByteArray = headerBytes.copyOf()

    internal fun headerBuffer(): ByteBuffer = ByteBuffer.wrap(headerBytes).asReadOnlyBuffer()
}

/**
 * Builds the CinemaDNG frames of an indexed `.ocraw` take (OCC-PLAN-071 §8), byte for
 * byte as `desktop-tools/ocraw_to_dng.py` writes them; the golden test
 * `CinemaDngGoldenTest` holds the two together. Pure JVM: only [NativeRawCodec]
 * (block-pack frames) needs the native library.
 *
 * - RAW10 frames become uncompressed 16-bit strips (`Compression` 1). Their size
 *   follows from the dimensions; [writeRange] unpacks only the bytes asked for.
 * - LJ92 frames pass through as lossless JPEG tiles (`Compression` 7). Their size
 *   follows from `dataLength` and the container's strip count, so [layout] reads
 *   the container's strip table (4 + 8 x strips bytes, at most 516), never the
 *   strips; [writeRange] copies the tile bytes straight from the file.
 * - Block-pack frames, plain or quantised (encoding 3, decoded to the reconstructed DN), are
 *   decoded through [NativeRawCodec] (or [blockPackDecoderFactory]) to uncompressed
 *   16-bit; without the library they are not convertible.
 * - With [lj92Encoder], RAW10 frames, and block-pack frames once decoded, are
 *   re-encoded to LJ92 tiles for export (a frame that does not fit falls back to
 *   uncompressed); their size is known only once encoded, see [isSizeKnown].
 *
 * Every DNG carries the CinemaDNG tags: FrameRate from `fps`, TimeCodes from the
 * frame's sensor timestamp relative to the take's first readable frame, and
 * ReelName and CameraLabel set to [takeName]. Static colour comes from
 * [OcrawFrameIndex.metadata], per-frame values from [OcrawFrameIndex.effectiveFrameMetadata] (a frame
 * without its own record inherits a neighbour's neutral and levels).
 *
 * [write] reads a frame's payload with its CRC32C check; a range that is not the
 * whole file reads only what it needs and skips the check. A frame whose header is
 * unreadable, whose encoding is unsupported, or whose payload proves malformed or
 * corrupt fails with [OcrawDngException]; [layout] assumes a well-formed payload.
 *
 * Not thread-safe (it owns scratch buffers); use one writer per thread over the
 * same [index], and [close] it to free the block-pack decoder. The caller owns [channel]. Throws [IllegalArgumentException] when
 * the take's CFA is not one of RGGB, GRBG, GBRG and BGGR, as the converter does.
 */
class CinemaDngWriter(
    private val channel: FileChannel,
    val index: OcrawFrameIndex,
    val takeName: String,
    private val lj92Encoder: Lj92Encoder? = null,
    private val decoderThreads: Int = 1,
    /** The block-pack decoder; null for [NativeRawCodec] with [decoderThreads], which needs the library. */
    private val blockPackDecoderFactory: (() -> BlockPackDecoder)? = null,
) : Closeable {
    /** Decodes a block-pack frame to tight RAW10 rows; [NativeRawCodec.Decoder] through [native]. */
    interface BlockPackDecoder : Closeable {
        /** Decodes `src[position, limit)` into tight RAW10 at `dst[position, ...)`; false when it is malformed. */
        fun decode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Boolean

        /**
         * Decodes a quantised block-pack payload (encoding 3) into tight RAW10 DN; false when
         * it is malformed. By default the prefix is read here, the codes come from [decode] and
         * are mapped through the payload's inverse table.
         */
        fun decodeQuantised(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Boolean =
            RawQuantisation.decodeWith(src, width, height, dst) { container, out -> decode(container, width, height, out) }

        companion object {
            fun native(threads: Int): BlockPackDecoder {
                val decoder = NativeRawCodec.newDecoder(threads)
                return object : BlockPackDecoder {
                    override fun decode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer) =
                        decoder.decode(NativeRawCodec.Codec.BLOCK_PACK, src, width, height, dst)

                    override fun decodeQuantised(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer) =
                        decoder.decode(NativeRawCodec.Codec.BLOCK_PACK_QUANTISED, src, width, height, dst)

                    override fun close() = decoder.close()
                }
            }
        }
    }

    /** Re-encodes RAW10 to the LJ92 frame container; [NativeRawCodec.Encoder] through [native]. */
    interface Lj92Encoder {
        /** Worst-case container size for a frame. */
        fun maxEncodedSize(width: Int, height: Int): Long

        /** Encodes tight RAW10 `src[position, ...)` into `dst[position, limit)`; the size, or 0 when it does not fit. */
        fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int

        companion object {
            fun native(encoder: NativeRawCodec.Encoder): Lj92Encoder {
                require(encoder.codec == NativeRawCodec.Codec.LJ92) { "the encoder must be LJ92" }
                return object : Lj92Encoder {
                    override fun maxEncodedSize(width: Int, height: Int) = encoder.maxEncodedSize(width, height)
                    override fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer) =
                        encoder.encode(src, width, height, dst)
                }
            }
        }
    }

    private val colour = StaticColour.from(index.metadata)
    private val fps: DngFraction? = parseFps(index.metadata["fps"])
    /** TIFF Orientation from the take's `orientation`; 1 (as stored) for takes without one. */
    private val tiffOrientation: Int = OcrawOrientation.tiffOrientation(index.orientationDegrees)
    private val originNs: Long? = index.frames.firstNotNullOfOrNull { it.header }?.sensorTimestampNs
    private val reader = OcrawStreamReader(channel, scratchBytes = OcrawFrameHeader.BYTES)
    private var payload: ByteBuffer? = null
    private var decoded: ByteBuffer? = null
    private var encoded: ByteBuffer? = null
    private var decoder: BlockPackDecoder? = null
    private val table = ByteBuffer.allocate(4 + 8 * MAX_STRIPS).order(ByteOrder.LITTLE_ENDIAN)
    private val unpacked = ByteBuffer.allocate(UNPACK_CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val packed = ByteBuffer.allocate(UNPACK_CHUNK_BYTES / 8 * 5).order(ByteOrder.LITTLE_ENDIAN)

    init {
        require(decoderThreads in 1..64) { "decoderThreads must be 1..64" }
    }

    val frameCount: Int get() = index.frames.size

    /** False when the take has no usable `fps`: the DNGs then carry no FrameRate and no TimeCodes. */
    val hasFrameRate: Boolean get() = fps != null

    /** `<take>_000000.dng`: the frame's index in file order, 6 digits (more past 999999). */
    fun fileName(frame: Int): String = fileName(takeName, frame)

    /** True when the frame's header is readable and its encoding is one this writer handles here. */
    fun isConvertible(frame: Int): Boolean {
        val header = frameAt(frame).header ?: return false
        return when (header.encoding) {
            OcrawFrameHeader.ENCODING_RAW10 -> true
            OcrawFrameHeader.ENCODING_LJ92 -> header.width % 2 == 0
            OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK, OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED ->
                header.width % 4 == 0 && (blockPackDecoderFactory != null || NativeRawCodec.available)
            else -> false
        }
    }

    /** False only for a RAW10 or block-pack frame re-encoded to LJ92, whose size is known once encoded. */
    fun isSizeKnown(frame: Int): Boolean =
        !(lj92Encoder != null && frameAt(frame).header?.encoding in REENCODED_ENCODINGS)

    /** The exact DNG size; see [layout]. */
    fun sizeOf(frame: Int): Long = layout(frame).size

    /**
     * The frame's DNG layout without reading pixel data: none for RAW10 and block-pack,
     * the strip table for LJ92. Throws [IllegalStateException] when ![isSizeKnown],
     * [OcrawDngException] when the frame is not convertible or its strip table is invalid.
     */
    fun layout(frame: Int): CinemaDngLayout {
        check(isSizeKnown(frame)) { "frame $frame is re-encoded; its size is known once written" }
        val stored = frameAt(frame)
        val header = convertibleHeader(frame)
        return when (header.encoding) {
            OcrawFrameHeader.ENCODING_LJ92 -> {
                table.clear()
                val tableBytes = minOf(table.capacity(), header.dataLength)
                table.limit(tableBytes)
                readFully(table, dataOffset(stored))
                table.flip()
                lj92Layout(frame, stored, header, parseStrips(table, header, frame), dataOffset(stored))
            }
            else -> uncompressedLayout(frame, stored, header)
        }
    }

    /** Writes the whole DNG to [out] and returns its length. The payload is CRC-checked. */
    fun write(frame: Int, out: WritableByteChannel): Long {
        val stored = frameAt(frame)
        val header = convertibleHeader(frame)
        val data = readPayload(frame, stored, header)
        return when (header.encoding) {
            OcrawFrameHeader.ENCODING_RAW10 -> {
                val reencoded = lj92Encoder?.let { encodeLj92(frame, stored, header, data) }
                if (reencoded != null) {
                    val (layout, container) = reencoded
                    emit(out, layout.headerBuffer())
                    emit(out, container)
                    layout.size
                } else {
                    writeUncompressed(uncompressedLayout(frame, stored, header), data, out)
                }
            }
            OcrawFrameHeader.ENCODING_LJ92 -> {
                val strips = parseStrips(data.duplicate().order(ByteOrder.LITTLE_ENDIAN), header, frame)
                checkSof3(data, strips, header.width, frame)
                val layout = lj92Layout(frame, stored, header, strips, -1)
                emit(out, layout.headerBuffer())
                emit(out, data.duplicate().apply { position(strips.streamsStart) })
                layout.size
            }
            else -> {
                val raw10 = decodeBlockPack(frame, header, data)
                val reencoded = lj92Encoder?.let { encodeLj92(frame, stored, header, raw10) }
                if (reencoded != null) {
                    val (layout, container) = reencoded
                    emit(out, layout.headerBuffer())
                    emit(out, container)
                    layout.size
                } else {
                    writeUncompressed(uncompressedLayout(frame, stored, header), raw10, out)
                }
            }
        }
    }

    fun write(frame: Int, out: OutputStream): Long = write(frame, Channels.newChannel(out))

    /**
     * Writes bytes `[offset, offset + length)` of the frame's DNG to [out] and returns
     * [length]. The whole file goes through [write]; a part of a RAW10 or LJ92
     * pass-through frame reads only the frame bytes it covers, unchecked. Other frames
     * (block-pack, re-encoded) are generated whole and cut.
     */
    fun writeRange(frame: Int, offset: Long, length: Long, out: WritableByteChannel): Long {
        require(offset >= 0 && length >= 0) { "negative range" }
        if (length == 0L) return 0
        val header = convertibleHeader(frame)
        val direct = isSizeKnown(frame) && header.encoding !in BLOCK_PACK_ENCODINGS
        if (!direct) {
            val window = WindowChannel(out, offset, length)
            write(frame, window)
            require(window.written == length) { "range $offset+$length runs past the end of the file" }
            return length
        }
        val layout = layout(frame)
        require(offset + length <= layout.size) { "range $offset+$length runs past ${layout.size}" }
        if (offset == 0L && length == layout.size) {
            write(frame, out)
            return length
        }
        val end = offset + length
        if (offset < layout.headerSize) {
            val slice = layout.headerBuffer()
            slice.position(offset.toInt()).limit(minOf(end, layout.headerSize.toLong()).toInt())
            emit(out, slice)
        }
        val from = maxOf(offset, layout.headerSize.toLong()) - layout.headerSize
        val to = end - layout.headerSize
        if (to > from) {
            if (layout.sourceOffset >= 0) {
                copyFromFile(layout.sourceOffset + from, to - from, out)
            } else {
                unpackRange(frameAt(frame), from, to, out)
            }
        }
        return length
    }

    fun writeRange(frame: Int, offset: Long, length: Long, out: OutputStream): Long =
        writeRange(frame, offset, length, Channels.newChannel(out))

    /** Releases the block-pack decoder and scratch buffers; a later call allocates them again. */
    override fun close() {
        payload = null
        decoded = null
        encoded = null
        decoder?.close()
        decoder = null
    }

    // ------------------------------------------------------------------------------------------
    // Layouts.
    // ------------------------------------------------------------------------------------------

    private fun uncompressedLayout(frame: Int, stored: OcrawStreamFrame, header: OcrawFrameHeader): CinemaDngLayout {
        val width = header.width
        val height = header.height
        val total = width.toLong() * height * 2
        val rowsPerStrip = maxOf(1, minOf(height, STRIP_TARGET_BYTES / (width * 2)))
        val step = rowsPerStrip.toLong() * width * 2
        val parts = LongArray(((total + step - 1) / step).toInt()) { minOf(step, total - it * step) }
        val bytes = buildDng(stored, header, compression = 1, bitsPerSample = 16, rowsPerPart = rowsPerStrip,
            tiled = false, parts = parts)
        return CinemaDngLayout(frame, bytes, total, 1, -1)
    }

    private fun lj92Layout(
        frame: Int,
        stored: OcrawStreamFrame,
        header: OcrawFrameHeader,
        strips: Strips,
        containerOffset: Long,
    ): CinemaDngLayout {
        val parts = LongArray(strips.lengths.size) { strips.lengths[it].toLong() }
        val bytes = buildDng(stored, header, compression = 7, bitsPerSample = LJ92_PRECISION,
            rowsPerPart = strips.rows[0], tiled = true, parts = parts)
        val source = if (containerOffset < 0) -1 else containerOffset + strips.streamsStart
        return CinemaDngLayout(frame, bytes, parts.sum(), 7, source)
    }

    /** Strip rows and byte lengths of an encoded frame container, and where its streams start. */
    private class Strips(val rows: IntArray, val lengths: IntArray, val streamsStart: Int)

    /**
     * Parses the container table at [src]'s position (`parse_strips` and the uniform-height
     * check of `decode_lj92`); [src] holds at least the table. Lengths are checked against
     * the header's `dataLength`, so the strips themselves need not be in [src].
     */
    private fun parseStrips(src: ByteBuffer, header: OcrawFrameHeader, frame: Int): Strips {
        val base = src.position()
        val dataLength = header.dataLength.toLong()
        fun malformed(message: String): Nothing =
            throw OcrawDngException("frame $frame: $message", OcrawMissingReason.DECODE_FAILED)
        if (header.width % 2 != 0) malformed("LJ92 frame width must be even")
        if (dataLength < 4 || src.remaining() < 4) malformed("encoded frame is truncated")
        val count = src.getInt(base)
        if (count !in 1..MAX_STRIPS) malformed("encoded frame has $count strips (1..$MAX_STRIPS)")
        var offset = 4L + 8L * count
        if (dataLength < offset || src.remaining() < offset) malformed("encoded frame strip table is truncated")
        val rows = IntArray(count)
        val lengths = IntArray(count)
        var total = 0L
        for (i in 0 until count) {
            val stripRows = src.getInt(base + 4 + 8 * i).toLong() and 0xffffffffL
            val length = src.getInt(base + 8 + 8 * i).toLong() and 0xffffffffL
            if (stripRows == 0L || stripRows % 2 != 0L || length > dataLength - offset) malformed("encoded frame strip $i is invalid")
            rows[i] = stripRows.toInt()
            lengths[i] = length.toInt()
            offset += length
            total += stripRows
        }
        if (total != header.height.toLong() || offset != dataLength) {
            malformed("encoded frame strips disagree with the frame height or data length")
        }
        val first = rows[0]
        if ((0 until count - 1).any { rows[it] != first } || rows[count - 1] > first) {
            malformed("LJ92 strips do not have a uniform height")
        }
        return Strips(rows, lengths, 4 + 8 * count)
    }

    /** `_check_sof3`: each strip is a JPEG stream whose SOF3 frame matches the strip geometry. */
    private fun checkSof3(data: ByteBuffer, strips: Strips, width: Int, frame: Int) {
        var start = data.position() + strips.streamsStart
        for (i in strips.rows.indices) {
            val length = strips.lengths[i]
            val rows = strips.rows[i]
            fun byte(at: Int) = data.get(start + at).toInt() and 0xff
            fun be16(at: Int) = byte(at) shl 8 or byte(at + 1)
            fun malformed(message: String): Nothing =
                throw OcrawDngException("frame $frame: LJ92 strip $i $message", OcrawMissingReason.DECODE_FAILED)
            if (length < 2 || byte(0) != 0xff || byte(1) != 0xd8 || byte(length - 2) != 0xff || byte(length - 1) != 0xd9) {
                malformed("is not a JPEG stream")
            }
            var at = 2
            var found = false
            while (at + 4 <= length) {
                if (byte(at) != 0xff) break
                val marker = byte(at + 1)
                val segment = be16(at + 2)
                if (marker == 0xc3) {
                    if (at + 10 > length) break
                    val precision = byte(at + 4)
                    val lines = be16(at + 5)
                    val columns = be16(at + 7)
                    val components = byte(at + 9)
                    if (precision != LJ92_PRECISION || lines != rows || columns * components != width || components != 2) {
                        malformed("frame is ${columns}x${lines}x$components P=$precision")
                    }
                    found = true
                    break
                }
                if (marker == 0xda) break
                at += 2 + segment
            }
            if (!found) malformed("has no SOF3 frame header")
            start += length
        }
    }

    // ------------------------------------------------------------------------------------------
    // TIFF/DNG header, as write_dng in ocraw_to_dng.py.
    // ------------------------------------------------------------------------------------------

    private fun buildDng(
        stored: OcrawStreamFrame,
        header: OcrawFrameHeader,
        compression: Int,
        bitsPerSample: Int,
        rowsPerPart: Int,
        tiled: Boolean,
        parts: LongArray,
    ): ByteArray {
        val meta = index.effectiveFrameMetadata(stored.sequence)
        var black = colour.black
        val dynamicBlack = meta["dynamicBlackLevel"]?.takeIf { it.isNotEmpty() } ?: meta["blackLevel"]
        if (!dynamicBlack.isNullOrEmpty()) {
            parseNumbersOrNull(dynamicBlack, 4)?.let { black = it }
        }
        var white = colour.white
        meta["whiteLevel"]?.takeIf { it.isNotEmpty() }?.let { text -> pyIntOrNull(text)?.let { white = it.toLong() } }

        fun ifd0(offsets: LongArray, exifOffset: Long): List<TiffEntry> {
            val entries = arrayListOf(
                TiffEntry.longs(254, 0),
                TiffEntry.longs(256, header.width.toLong()),
                TiffEntry.longs(257, header.height.toLong()),
                TiffEntry.shorts(258, bitsPerSample),
                TiffEntry.shorts(259, compression),
                TiffEntry.shorts(262, 32803),
                TiffEntry.ascii(271, colour.make),
                TiffEntry.ascii(272, colour.model),
                TiffEntry.shorts(274, tiffOrientation),
                TiffEntry.shorts(277, 1),
                TiffEntry.shorts(284, 1),
                TiffEntry.ascii(305, SOFTWARE),
                TiffEntry.shorts(33421, 2, 2),
                TiffEntry.bytes(33422, colour.cfa),
                TiffEntry.longs(34665, exifOffset),
                TiffEntry.bytes(50706, intArrayOf(1, 4, 0, 0)),
                TiffEntry.bytes(50707, intArrayOf(1, 1, 0, 0)),
                TiffEntry.ascii(50708, colour.model),
                TiffEntry.bytes(50710, intArrayOf(0, 1, 2)),
                TiffEntry.shorts(50711, 1),
                TiffEntry.shorts(50713, 2, 2),
                blackLevelEntry(black),
                TiffEntry.longs(50717, white),
                TiffEntry.longs(50719, 0, 0),
                TiffEntry.longs(50720, header.width.toLong(), header.height.toLong()),
                TiffEntry.rationals(50721, colour.colorMatrix1, signed = true),
            )
            if (tiled) {
                entries += TiffEntry.longs(322, header.width.toLong())
                entries += TiffEntry.longs(323, rowsPerPart.toLong())
                entries += TiffEntry.longs(324, *offsets)
                entries += TiffEntry.longs(325, *parts)
            } else {
                entries += TiffEntry.longs(273, *offsets)
                entries += TiffEntry.longs(278, rowsPerPart.toLong())
                entries += TiffEntry.longs(279, *parts)
            }
            colour.colorMatrix2?.let { entries += TiffEntry.rationals(50722, it, signed = true) }
            colour.illuminant1?.let { entries += TiffEntry.shorts(50778, it) }
            colour.illuminant2?.let { entries += TiffEntry.shorts(50779, it) }
            colour.forwardMatrix1?.let { entries += TiffEntry.rationals(50964, it, signed = true) }
            colour.forwardMatrix2?.let { entries += TiffEntry.rationals(50965, it, signed = true) }
            entries += TiffEntry.ascii(TAG_REEL_NAME, takeName)
            entries += TiffEntry.ascii(TAG_CAMERA_LABEL, takeName)
            fps?.let { rate ->
                entries += TiffEntry.rationals(TAG_FRAME_RATE, listOf(rate), signed = true)
                originNs?.let { origin ->
                    val code = smpteTimecode(timecodeFrames(header.sensorTimestampNs, origin, rate), rate)
                    entries += TiffEntry.bytes(TAG_TIME_CODES, IntArray(8) { code[it].toInt() and 0xff })
                }
            }
            meta["neutral"]?.takeIf { it.isNotEmpty() }?.let { text ->
                parseNumbersOrNull(text, 3)?.takeIf { values -> values.none { it.signum < 0 } }
                    ?.let { entries += TiffEntry.rationals(50728, it, signed = false) }
            }
            return entries
        }

        val exif = arrayListOf(TiffEntry(36864, UNDEFINED, 4, "0230".toByteArray(Charsets.US_ASCII)))
        meta["exposureNs"]?.takeIf { it.isNotEmpty() }?.let { text ->
            pyIntOrNull(text)?.takeIf { it.signum() >= 0 }?.let {
                exif += TiffEntry.rationals(33434, listOf(DngFraction.of(it, NANOS)), signed = false)
            }
        }
        meta["iso"]?.takeIf { it.isNotEmpty() }?.let { text ->
            pyIntOrNull(text)?.let { exif += TiffEntry.shorts(34855, it.max(BigInteger.ZERO).min(BigInteger.valueOf(0xffff)).toInt()) }
        }

        // IFD sizes do not depend on the offset values, so lay out with placeholders first.
        val placeholder = TiffEntry.buildIfd(ifd0(LongArray(parts.size), 0), 8)
        val exifOffset = 8L + placeholder.size
        val exifBytes = TiffEntry.buildIfd(exif, exifOffset)
        var dataOffset = exifOffset + exifBytes.size
        if (dataOffset % 2 != 0L) dataOffset++
        val offsets = LongArray(parts.size)
        var cursor = dataOffset
        for (i in parts.indices) {
            offsets[i] = cursor
            cursor += parts[i]
        }
        require(cursor <= 0xffffffffL) { "a DNG over 4 GiB is not a classic TIFF" }
        val first = TiffEntry.buildIfd(ifd0(offsets, exifOffset), 8)
        val out = ByteBuffer.allocate(dataOffset.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        out.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        out.put(first).put(exifBytes)
        return out.array()
    }

    private fun blackLevelEntry(black: List<DngFraction>): TiffEntry =
        if (black.all { it.isInteger && it.num.signum() >= 0 && it.num <= UINT32_MAX }) {
            TiffEntry.longs(50714, *LongArray(black.size) { black[it].num.toLong() })
        } else {
            TiffEntry.rationals(50714, black, signed = false)
        }

    // ------------------------------------------------------------------------------------------
    // Payloads.
    // ------------------------------------------------------------------------------------------

    private fun frameAt(frame: Int): OcrawStreamFrame {
        require(frame in index.frames.indices) { "frame $frame out of 0 until $frameCount" }
        return index.frames[frame]
    }

    private fun convertibleHeader(frame: Int): OcrawFrameHeader {
        val header = frameAt(frame).header
            ?: throw OcrawDngException("frame $frame has an unreadable header", OcrawMissingReason.UNREADABLE_HEADER)
        if (!isConvertible(frame)) {
            val reason = if (header.encoding in BLOCK_PACK_ENCODINGS && header.width % 4 == 0) {
                OcrawMissingReason.CODEC_UNAVAILABLE
            } else if (header.encoding in KNOWN_ENCODINGS) {
                OcrawMissingReason.UNREADABLE_HEADER
            } else {
                OcrawMissingReason.UNSUPPORTED_ENCODING
            }
            throw OcrawDngException("frame $frame (encoding ${header.encoding}) cannot become a DNG here", reason)
        }
        return header
    }

    private fun dataOffset(stored: OcrawStreamFrame): Long =
        stored.fileOffset + OcrawCodec.CHUNK_HEADER_BYTES + OcrawFrameHeader.BYTES

    /** The frame data, CRC-checked, in a reused direct buffer: position 0, limit `dataLength`. */
    private fun readPayload(frame: Int, stored: OcrawStreamFrame, header: OcrawFrameHeader): ByteBuffer {
        val buffer = payload?.takeIf { it.capacity() >= header.dataLength }
            ?: ByteBuffer.allocateDirect(maxOf(header.dataLength, maxDataLength())).order(ByteOrder.LITTLE_ENDIAN)
                .also { payload = it }
        buffer.clear()
        try {
            reader.readRawFrame(stored.fileOffset, buffer)
        } catch (error: ClosedChannelException) {
            throw error
        } catch (error: IOException) {
            throw OcrawDngException("frame $frame: ${error.message}", OcrawMissingReason.CORRUPT, error)
        } catch (error: IllegalArgumentException) {
            throw OcrawDngException("frame $frame: ${error.message}", OcrawMissingReason.CORRUPT, error)
        }
        buffer.flip()
        return buffer
    }

    private fun maxDataLength(): Int = index.frames.maxOfOrNull { it.header?.dataLength ?: 0 } ?: 0

    private fun encodeLj92(
        frame: Int,
        stored: OcrawStreamFrame,
        header: OcrawFrameHeader,
        data: ByteBuffer,
    ): Pair<CinemaDngLayout, ByteBuffer>? {
        val encoder = lj92Encoder ?: return null
        if (header.height % 2 != 0) return null
        val worst = encoder.maxEncodedSize(header.width, header.height)
        require(worst in 1..Int.MAX_VALUE) { "encoder reports $worst bytes" }
        val dst = encoded?.takeIf { it.capacity() >= worst }
            ?: ByteBuffer.allocateDirect(worst.toInt()).order(ByteOrder.LITTLE_ENDIAN).also { encoded = it }
        dst.clear()
        val size = encoder.encode(data.duplicate(), header.width, header.height, dst.duplicate())
        if (size <= 0) return null
        dst.limit(size)
        val lj92Header = header.copy(encoding = OcrawFrameHeader.ENCODING_LJ92, dataLength = size)
        val strips = parseStrips(dst.duplicate().order(ByteOrder.LITTLE_ENDIAN), lj92Header, frame)
        checkSof3(dst, strips, header.width, frame)
        val layout = lj92Layout(frame, stored, lj92Header, strips, -1)
        return layout to dst.duplicate().apply { position(strips.streamsStart) }
    }

    private fun decodeBlockPack(frame: Int, header: OcrawFrameHeader, data: ByteBuffer): ByteBuffer {
        val codec = decoder ?: (blockPackDecoderFactory?.invoke() ?: BlockPackDecoder.native(decoderThreads)).also { decoder = it }
        val bytes = header.width / 4 * 5 * header.height
        val dst = decoded?.takeIf { it.capacity() >= bytes }
            ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN).also { decoded = it }
        dst.clear()
        val ok = try {
            if (header.encoding == OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED) {
                codec.decodeQuantised(data, header.width, header.height, dst)
            } else {
                codec.decode(data, header.width, header.height, dst)
            }
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!ok) throw OcrawDngException("frame $frame: malformed block-pack data", OcrawMissingReason.DECODE_FAILED)
        dst.limit(bytes)
        return dst
    }

    /** Writes the header, then [raw10] (tight packed rows) unpacked to little-endian 16-bit samples. */
    private fun writeUncompressed(layout: CinemaDngLayout, raw10: ByteBuffer, out: WritableByteChannel): Long {
        emit(out, layout.headerBuffer())
        val groups = raw10.remaining() / 5
        val perChunk = UNPACK_CHUNK_BYTES / 8
        var group = 0
        while (group < groups) {
            val count = minOf(perChunk, groups - group)
            unpacked.clear()
            unpackRaw10(raw10, raw10.position() + group * 5, count, unpacked)
            unpacked.flip()
            emit(out, unpacked)
            group += count
        }
        return layout.size
    }

    /** Image bytes `[from, to)` of an uncompressed RAW10 frame, unpacked from the groups that hold them. */
    private fun unpackRange(stored: OcrawStreamFrame, from: Long, to: Long, out: WritableByteChannel) {
        val base = dataOffset(stored)
        var at = from
        while (at < to) {
            val firstGroup = at / 8
            val lastGroup = minOf((to + 7) / 8, firstGroup + UNPACK_CHUNK_BYTES / 8)
            val groups = (lastGroup - firstGroup).toInt()
            packed.clear().limit(groups * 5)
            readFully(packed, base + firstGroup * 5)
            packed.flip()
            unpacked.clear()
            unpackRaw10(packed, 0, groups, unpacked)
            val sliceStart = (at - firstGroup * 8).toInt()
            val sliceEnd = (minOf(to, lastGroup * 8) - firstGroup * 8).toInt()
            unpacked.position(sliceStart).limit(sliceEnd)
            emit(out, unpacked)
            at = firstGroup * 8 + sliceEnd
        }
    }

    private fun copyFromFile(position: Long, count: Long, out: WritableByteChannel) {
        var done = 0L
        while (done < count) {
            val moved = channel.transferTo(position + done, count - done, out)
            if (moved <= 0) {
                if (position + done >= channel.size()) throw EOFException("unexpected end of file at ${position + done}")
                // transferTo may move nothing to a non-blocking or odd target; fall back to a buffer.
                val buffer = ByteBuffer.allocate(minOf(count - done, 65_536L).toInt())
                readFully(buffer, position + done)
                buffer.flip()
                emit(out, buffer)
                done += buffer.limit()
            } else {
                done += moved
            }
        }
    }

    private fun readFully(buffer: ByteBuffer, position: Long) {
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read < 0) throw EOFException("unexpected end of file at $at")
            at += read
        }
    }

    /** Counts and forwards only the bytes in `[offset, offset + length)` of what is written to it. */
    private class WindowChannel(val out: WritableByteChannel, val offset: Long, val length: Long) : WritableByteChannel {
        var position = 0L
        var written = 0L

        override fun write(src: ByteBuffer): Int {
            val count = src.remaining()
            val start = maxOf(position, offset)
            val end = minOf(position + count, offset + length)
            if (end > start) {
                val slice = src.duplicate()
                slice.position(src.position() + (start - position).toInt())
                slice.limit(src.position() + (end - position).toInt())
                emit(out, slice)
                written += end - start
            }
            position += count
            src.position(src.limit())
            return count
        }

        override fun isOpen() = out.isOpen

        override fun close() = Unit
    }

    companion object {
        const val SOFTWARE = "OpenCineCam ocraw_to_dng"
        const val TAG_TIME_CODES = 51043
        const val TAG_FRAME_RATE = 51044
        const val TAG_REEL_NAME = 51081
        const val TAG_CAMERA_LABEL = 51105
        const val STRIP_TARGET_BYTES = 1 shl 20
        const val MAX_STRIPS = 64
        const val LJ92_PRECISION = 10
        private const val UNPACK_CHUNK_BYTES = 1 shl 16
        private const val SMPTE_MAX_FRAME_DIGITS = 30
        private val KNOWN_ENCODINGS = setOf(0, 1, 2, 3)
        /** The encodings decoded through a [BlockPackDecoder]: block-pack, plain or quantised. */
        private val BLOCK_PACK_ENCODINGS =
            setOf(OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK, OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED)
        /** The encodings an [Lj92Encoder] re-encodes: RAW10 and block-pack. */
        private val REENCODED_ENCODINGS = setOf(OcrawFrameHeader.ENCODING_RAW10) + BLOCK_PACK_ENCODINGS
        private val NANOS = BigInteger.valueOf(1_000_000_000)
        internal val UINT32_MAX: BigInteger = BigInteger.valueOf(0xffffffffL)

        /** `<take>_000000.dng`. */
        fun fileName(takeName: String, frame: Int): String = "${takeName}_${frame.toString().padStart(6, '0')}.dng"

        /** `parse_fps`: the positive `fps` rational, or null. */
        internal fun parseFps(text: String?): DngFraction? {
            if (text.isNullOrEmpty()) return null
            val value = try {
                DngFraction.parse(text.trim())
            } catch (_: IllegalArgumentException) {
                return null
            } catch (_: ArithmeticException) {
                return null
            }
            return value.takeIf { it.signum > 0 }
        }

        /** `timecode_frames`: frames from the take start at [fps], rounded half to even, never negative. */
        internal fun timecodeFrames(timestampNs: Long, originNs: Long, fps: DngFraction): Long {
            val offset = DngFraction.of(BigInteger.valueOf(timestampNs) - BigInteger.valueOf(originNs), NANOS)
            return (offset * fps).roundHalfEven().max(BigInteger.ZERO).toLong()
        }

        /**
         * `smpte_timecode`: one 8-byte SMPTE 12M time code in the DNG `TimeCodes` layout,
         * non-drop-frame. Byte 0 holds the frames (bits 5-4 tens, 3-0 units), bytes 1, 2
         * and 3 the seconds, minutes and hours the same way, bytes 4-7 (binary groups)
         * zero. Labels count at the integer nominal rate; hours wrap at 24. Above 30 fps
         * the frames field counts pairs and the field flag marks a pair's second frame:
         * byte 3 bit 7 at 50 fps, byte 1 bit 7 otherwise.
         */
        fun smpteTimecode(frames: Long, fps: DngFraction): ByteArray {
            require(frames >= 0) { "frames must be non-negative" }
            val base = maxOf(1L, fps.roundHalfEven().min(BigInteger.valueOf(Int.MAX_VALUE.toLong())).toLong())
            val divisor = (base + SMPTE_MAX_FRAME_DIGITS - 1) / SMPTE_MAX_FRAME_DIGITS
            val seconds = frames / base
            val frame = frames % base
            fun bcd(value: Long): Byte = ((value / 10) shl 4 or (value % 10)).toByte()
            val code = byteArrayOf(bcd(frame / divisor), bcd(seconds % 60), bcd(seconds / 60 % 60),
                bcd(seconds / 3600 % 24), 0, 0, 0, 0)
            if (divisor == 2L && frame % 2 == 1L) {
                if (base % 25 == 0L) code[3] = (code[3].toInt() or 0x80).toByte() else code[1] = (code[1].toInt() or 0x80).toByte()
            }
            return code
        }

        /** Unpacks [groups] RAW10 groups from `src[at...]` to little-endian 16-bit samples at `dst.position()`. */
        internal fun unpackRaw10(src: ByteBuffer, at: Int, groups: Int, dst: ByteBuffer) {
            var i = at
            repeat(groups) {
                val low = src.get(i + 4).toInt()
                for (k in 0 until 4) {
                    val value = (src.get(i + k).toInt() and 0xff shl 2) or (low shr (2 * k) and 3)
                    dst.put(value.toByte()).put((value shr 8).toByte())
                }
                i += 5
            }
        }
    }
}

/** Writes all of [src] to [out]. */
internal fun emit(out: WritableByteChannel, src: ByteBuffer) {
    while (src.hasRemaining()) {
        if (out.write(src) < 0) throw IOException("channel refused bytes")
    }
}

/** The take's static colour and camera names, parsed as `static_colour` in ocraw_to_dng.py. */
internal class StaticColour(
    val cfa: IntArray,
    val black: List<DngFraction>,
    val white: Long,
    val colorMatrix1: List<DngFraction>,
    val colorMatrix2: List<DngFraction>?,
    val forwardMatrix1: List<DngFraction>?,
    val forwardMatrix2: List<DngFraction>?,
    val illuminant1: Int?,
    val illuminant2: Int?,
    val model: String,
    val make: String,
) {
    companion object {
        private val PATTERNS = mapOf(
            "RGGB" to intArrayOf(0, 1, 1, 2),
            "GRBG" to intArrayOf(1, 0, 2, 1),
            "GBRG" to intArrayOf(1, 2, 0, 1),
            "BGGR" to intArrayOf(2, 1, 1, 0),
        )
        private val ANDROID_CFA = mapOf("0" to "RGGB", "1" to "GRBG", "2" to "GBRG", "3" to "BGGR", "4" to "RGB",
            "5" to "MONO", "6" to "NIR")
        private const val D65 = 21

        fun from(metadata: Map<String, String>): StaticColour {
            val name = metadata["cfa"]?.trim()?.uppercase()?.let { ANDROID_CFA[it] ?: it } ?: "RGGB"
            val cfa = requireNotNull(PATTERNS[name]) {
                "CFA arrangement $name is not supported (only RGGB, GRBG, GBRG and BGGR)"
            }
            var white = (metadata["whiteLevel"]?.takeIf { it.isNotEmpty() }?.let(::pyInt) ?: BigInteger.ZERO).toLong()
            if (white <= 0) white = 1023
            val black = parseNumbers(metadata["blackLevel"], 4) ?: List(4) { DngFraction.ZERO }
            var matrix1 = parseNumbers(metadata["colorMatrix1"], 9)
            var illuminant1 = metadata["calibrationIlluminant1"]?.let { pyInt(it).toInt() }
            if (matrix1 == null) {
                matrix1 = List(9) { if (it % 4 == 0) DngFraction.ONE else DngFraction.ZERO }
                illuminant1 = D65
            }
            val model = metadata["model"]?.takeIf { it.isNotEmpty() }
                ?: "OpenCineCam camera ${metadata["cameraId"] ?: "?"}"
            return StaticColour(
                cfa = cfa,
                black = black,
                white = white,
                colorMatrix1 = matrix1,
                colorMatrix2 = parseNumbers(metadata["colorMatrix2"], 9),
                forwardMatrix1 = parseNumbers(metadata["forwardMatrix1"], 9),
                forwardMatrix2 = parseNumbers(metadata["forwardMatrix2"], 9),
                illuminant1 = illuminant1,
                illuminant2 = metadata["calibrationIlluminant2"]?.let { pyInt(it).toInt() },
                model = model,
                // The take records Build.MODEL only; a `make` key (none is written yet) wins when present.
                make = metadata["make"]?.takeIf { it.isNotEmpty() } ?: model,
            )
        }
    }
}

/** Python `int(text)` for plain decimal integers (surrounding whitespace, a sign). */
internal fun pyInt(text: String): BigInteger = text.trim().toBigInteger()

internal fun pyIntOrNull(text: String): BigInteger? = try {
    pyInt(text)
} catch (_: NumberFormatException) {
    null
}

/** `parse_numbers`: [count] whitespace-separated numbers, null for absent text; throws when malformed. */
internal fun parseNumbers(text: String?, count: Int): List<DngFraction>? {
    if (text == null) return null
    val parts = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    val values = parts.map(DngFraction::parse)
    require(values.size == count) { "expected $count values, found ${values.size}" }
    return values
}

internal fun parseNumbersOrNull(text: String, count: Int): List<DngFraction>? = try {
    parseNumbers(text, count)
} catch (_: IllegalArgumentException) {
    null
} catch (_: ArithmeticException) {
    null
}

private val WHITESPACE = Regex("\\s+")

/** An exact rational with Python `fractions.Fraction` semantics where the DNG bytes depend on them. */
class DngFraction private constructor(val num: BigInteger, val den: BigInteger) {
    val signum: Int get() = num.signum()
    val isInteger: Boolean get() = den == BigInteger.ONE

    operator fun times(other: DngFraction) = of(num * other.num, den * other.den)

    /** `round(Fraction)`: the nearest integer, half to even. */
    fun roundHalfEven(): BigInteger {
        val (floor, remainder) = floorDivMod(num, den)
        val twice = remainder.shiftLeft(1)
        return when {
            twice < den -> floor
            twice > den -> floor + BigInteger.ONE
            floor.testBit(0) -> floor + BigInteger.ONE
            else -> floor
        }
    }

    /** `Fraction.limit_denominator`: the closest fraction with a denominator at most [max]. */
    fun limitDenominator(max: BigInteger): DngFraction {
        require(max >= BigInteger.ONE) { "max must be at least 1" }
        if (den <= max) return this
        var p0 = BigInteger.ZERO
        var q0 = BigInteger.ONE
        var p1 = BigInteger.ONE
        var q1 = BigInteger.ZERO
        var n = num
        var d = den
        while (true) {
            val a = floorDivMod(n, d).first
            val q2 = q0 + a * q1
            if (q2 > max) break
            val p2 = p0 + a * p1
            p0 = p1
            q0 = q1
            p1 = p2
            q1 = q2
            val nextD = n - a * d
            n = d
            d = nextD
        }
        val k = (max - q0) / q1
        val bound1 = of(p0 + k * p1, q0 + k * q1)
        val bound2 = of(p1, q1)
        return if (distance(bound2) <= distance(bound1)) bound2 else bound1
    }

    /** `_clamp_fraction`: the TIFF (numerator, denominator) pair. */
    internal fun tiffPair(signed: Boolean): Pair<Long, Long> {
        val limit = BigInteger.valueOf(if (signed) 0x7fffffffL else 0xffffffffL)
        require(signed || signum >= 0) { "negative value in an unsigned RATIONAL" }
        val value = limitDenominator(limit)
        if (value.num.abs() > limit) return (if (value.signum > 0) limit else limit.negate()).toLong() to 1L
        return value.num.toLong() to value.den.toLong()
    }

    private fun distance(other: DngFraction): DngFraction = of((other.num * den - num * other.den).abs(), other.den * den)

    private operator fun compareTo(other: DngFraction): Int = (num * other.den).compareTo(other.num * den)

    override fun equals(other: Any?) = other is DngFraction && num == other.num && den == other.den

    override fun hashCode() = num.hashCode() * 31 + den.hashCode()

    override fun toString() = "$num/$den"

    companion object {
        val ZERO = DngFraction(BigInteger.ZERO, BigInteger.ONE)
        val ONE = DngFraction(BigInteger.ONE, BigInteger.ONE)

        fun of(n: BigInteger, d: BigInteger): DngFraction {
            if (d.signum() == 0) throw ArithmeticException("zero denominator")
            val g = n.gcd(d)
            var nn = n / g
            var dd = d / g
            if (dd.signum() < 0) {
                nn = nn.negate()
                dd = dd.negate()
            }
            return DngFraction(nn, dd)
        }

        fun of(n: Long, d: Long = 1) = of(BigInteger.valueOf(n), BigInteger.valueOf(d))

        /** `parse_number`: `n/d` with a positive `d`, or an exact decimal. */
        fun parse(text: String): DngFraction {
            val slash = text.indexOf('/')
            if (slash >= 0) {
                val n = pyInt(text.substring(0, slash))
                val d = pyInt(text.substring(slash + 1))
                val value = of(n, d)
                require(d.signum() > 0) { "non-positive denominator in $text" }
                return value
            }
            val decimal = BigDecimal(text.trim())
            val unscaled = decimal.unscaledValue()
            val scale = decimal.scale()
            return if (scale >= 0) of(unscaled, BigInteger.TEN.pow(scale)) else of(unscaled * BigInteger.TEN.pow(-scale), BigInteger.ONE)
        }

        private fun floorDivMod(n: BigInteger, d: BigInteger): Pair<BigInteger, BigInteger> {
            val qr = n.divideAndRemainder(d)
            var q = qr[0]
            var r = qr[1]
            if (r.signum() != 0 && (r.signum() != d.signum())) {
                q -= BigInteger.ONE
                r += d
            }
            return q to r
        }
    }
}

/** TIFF field types. */
private const val BYTE = 1
private const val ASCII = 2
private const val SHORT = 3
private const val LONG = 4
private const val RATIONAL = 5
private const val UNDEFINED = 7
private const val SRATIONAL = 10

/** One IFD entry with its encoded value, as `_encode` in ocraw_to_dng.py. */
internal class TiffEntry(val tag: Int, val type: Int, val count: Int, val raw: ByteArray) {
    companion object {
        fun ascii(tag: Int, text: String): TiffEntry {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                out.write(if (codePoint < 0x80) codePoint else '?'.code)
                i += Character.charCount(codePoint)
            }
            out.write(0)
            val raw = out.toByteArray()
            return TiffEntry(tag, ASCII, raw.size, raw)
        }

        fun bytes(tag: Int, values: IntArray) = TiffEntry(tag, BYTE, values.size, ByteArray(values.size) { values[it].toByte() })

        fun shorts(tag: Int, vararg values: Int): TiffEntry {
            val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach {
                require(it in 0..0xffff) { "SHORT value $it out of range" }
                buffer.putShort(it.toShort())
            }
            return TiffEntry(tag, SHORT, values.size, buffer.array())
        }

        fun longs(tag: Int, vararg values: Long): TiffEntry {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach {
                require(it in 0..0xffffffffL) { "LONG value $it out of range" }
                buffer.putInt(it.toInt())
            }
            return TiffEntry(tag, LONG, values.size, buffer.array())
        }

        fun rationals(tag: Int, values: List<DngFraction>, signed: Boolean): TiffEntry {
            val buffer = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { value ->
                val (n, d) = value.tiffPair(signed)
                buffer.putInt(n.toInt()).putInt(d.toInt())
            }
            return TiffEntry(tag, if (signed) SRATIONAL else RATIONAL, values.size, buffer.array())
        }

        /** `_build_ifd`: the IFD at [offset] (entries ascending), then its out-of-line values, each padded to even. */
        fun buildIfd(entries: List<TiffEntry>, offset: Long): ByteArray {
            val sorted = entries.sortedBy { it.tag }
            val overflowAt = offset + 2 + 12L * sorted.size + 4
            val table = ByteBuffer.allocate(2 + 12 * sorted.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            val overflow = java.io.ByteArrayOutputStream()
            table.putShort(sorted.size.toShort())
            sorted.forEach { entry ->
                table.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count)
                if (entry.raw.size <= 4) {
                    table.put(entry.raw)
                    repeat(4 - entry.raw.size) { table.put(0) }
                } else {
                    table.putInt((overflowAt + overflow.size()).toInt())
                    overflow.write(entry.raw)
                    if (overflow.size() % 2 != 0) overflow.write(0)
                }
            }
            table.putInt(0)
            return table.array() + overflow.toByteArray()
        }
    }
}
