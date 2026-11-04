// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel

/**
 * The sound of an indexed RAW take as a 16-bit PCM WAV (OCC-PLAN-071 §7.1 and §8), byte
 * for byte as `ocraw_to_dng.py --wav` writes it, laid out from the index alone so
 * [size] is exact before any sample is read.
 *
 * Chunks are placed by their timestamps, in time order (file order for equal times).
 * Sample zero is the first RAW frame's capture time ([alignToVideo]; otherwise the
 * first audio sample's). A chunk starting more than 1 ms after the samples placed so
 * far is preceded by silence; one starting more than 1 ms before loses its overlapping
 * samples (all of them, if it lies wholly before); a chunk whose length is not whole
 * sample frames is skipped. Every timestamp is read in the take's time base
 * ([OcrawFrameIndex.timeBase]), which the format requires of audio chunks too.
 *
 * Chunks are CRC-checked as they are written: a corrupt one throws [IOException], or
 * with [corruptChunkAsSilence] becomes silence of the same length so the size holds.
 *
 * Not thread-safe; use one writer per thread. The caller owns [channel]. The
 * constructor throws [IllegalArgumentException] when the take has no sound (see
 * [hasAudio]) or the audio does not fit a WAV (4 GiB).
 */
class OcrawWavWriter(
    private val channel: FileChannel,
    val index: OcrawFrameIndex,
    val alignToVideo: Boolean = true,
    private val corruptChunkAsSilence: Boolean = false,
) {
    val sampleRate: Int
    val channels: Int

    /** Bytes per sample frame: one PCM16 sample per channel. */
    val frameBytes: Int

    /** Sample frames in the WAV, silence included. */
    val sampleFrames: Long

    /** Sample frames of silence inserted for gaps. */
    val paddedFrames: Long

    /** Sample frames dropped as overlap or as preceding sample zero. */
    val trimmedFrames: Long

    /** Chunks left out because their length is not whole sample frames. */
    val skippedChunks: Int

    /** The `data` chunk length; the WAV is [HEADER_BYTES] longer. */
    val dataBytes: Long

    /** The exact WAV length. */
    val size: Long get() = HEADER_BYTES + dataBytes

    /** A run of the `data` chunk: silence when [chunk] is null, else payload bytes from [skip] on. */
    private class Segment(val start: Long, val length: Long, val chunk: OcrawAudioChunk?, val skip: Int)

    private val segments: List<Segment>
    private val header: ByteArray
    private val reader = OcrawStreamReader(channel, scratchBytes = OcrawFrameHeader.BYTES)
    private var chunkBuffer: ByteBuffer? = null
    private val silence = ByteBuffer.allocate(SILENCE_BYTES)

    init {
        val rate = positiveInt(index.metadata, RATE_KEY)
        val count = positiveInt(index.metadata, CHANNELS_KEY)
        require(index.audio.isNotEmpty()) { "take has no audio chunks" }
        val timeBase = requireNotNull(index.timeBase) { "take has no time base" }
        sampleRate = rate
        channels = count
        frameBytes = 2 * count
        val audio = index.audio.sortedBy { it.timestampTicks }
        val originTicks = if (alignToVideo && index.frames.isNotEmpty()) index.frames[0].timestampTicks else audio[0].timestampTicks
        val tolerance = (rate / 1000).toLong()
        val list = ArrayList<Segment>()
        var cursor = 0L
        var data = 0L
        var padded = 0L
        var trimmed = 0L
        var skipped = 0
        val rateTimesNumerator = BigInteger.valueOf(rate.toLong()) * BigInteger.valueOf(timeBase.numerator)
        val denominator = BigInteger.valueOf(timeBase.denominator)
        for (chunk in audio) {
            val length = chunk.payloadLength
            if (length % frameBytes != 0) {
                skipped++
                continue
            }
            val ticks = BigInteger.valueOf(chunk.timestampTicks) - BigInteger.valueOf(originTicks)
            val start = DngFraction.of(ticks * rateTimesNumerator, denominator).roundHalfEven().toLong()
            var skip = 0
            if (start > cursor + tolerance) {
                val gap = (start - cursor) * frameBytes
                list += Segment(data, gap, null, 0)
                data += gap
                padded += start - cursor
                cursor = start
            } else if (start < cursor - tolerance) {
                val drop = minOf(cursor - start, (length / frameBytes).toLong())
                skip = (drop * frameBytes).toInt()
                trimmed += drop
            }
            val kept = (length - skip).toLong()
            if (kept > 0) list += Segment(data, kept, chunk, skip)
            data += kept
            cursor += kept / frameBytes
        }
        require(data <= MAX_DATA_BYTES) { "audio is too long for a WAV file (4 GiB)" }
        segments = list
        dataBytes = data
        sampleFrames = cursor
        paddedFrames = padded
        trimmedFrames = trimmed
        skippedChunks = skipped
        header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((HEADER_BYTES - 8 + data).toInt())
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(count.toShort())
            putInt(rate)
            putInt(rate * frameBytes)
            putShort(frameBytes.toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(data.toInt())
        }.array()
    }

    /** Writes the whole WAV to [out] and returns its length. */
    fun write(out: WritableByteChannel): Long = writeRange(0, size, out)

    fun write(out: OutputStream): Long = write(Channels.newChannel(out))

    /** Writes bytes `[offset, offset + length)` of the WAV to [out] and returns [length]. */
    fun writeRange(offset: Long, length: Long, out: WritableByteChannel): Long {
        require(offset >= 0 && length >= 0 && offset + length <= size) { "range $offset+$length outside 0..$size" }
        val end = offset + length
        if (offset < HEADER_BYTES) {
            val slice = ByteBuffer.wrap(header)
            slice.position(offset.toInt()).limit(minOf(end, HEADER_BYTES.toLong()).toInt())
            emit(out, slice)
        }
        val from = maxOf(offset - HEADER_BYTES, 0)
        val to = end - HEADER_BYTES
        if (to <= from) return length
        var i = firstSegment(from)
        while (i < segments.size && segments[i].start < to) {
            val segment = segments[i]
            val a = maxOf(from, segment.start) - segment.start
            val b = minOf(to, segment.start + segment.length) - segment.start
            if (segment.chunk == null) writeSilence(b - a, out) else writeChunk(segment, a.toInt(), b.toInt(), out)
            i++
        }
        return length
    }

    fun writeRange(offset: Long, length: Long, out: OutputStream): Long =
        writeRange(offset, length, Channels.newChannel(out))

    private fun firstSegment(dataOffset: Long): Int {
        var low = 0
        var high = segments.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (segments[mid].start <= dataOffset) low = mid else high = mid - 1
        }
        return low
    }

    private fun writeChunk(segment: Segment, from: Int, to: Int, out: WritableByteChannel) {
        val chunk = requireNotNull(segment.chunk)
        val buffer = chunkBuffer?.takeIf { it.capacity() >= chunk.payloadLength }
            ?: ByteBuffer.allocate(index.audio.maxOf { it.payloadLength }).also { chunkBuffer = it }
        buffer.clear()
        try {
            reader.readAudioChunk(chunk.fileOffset, buffer)
        } catch (error: ClosedChannelException) {
            throw error
        } catch (error: IOException) {
            if (!corruptChunkAsSilence) throw error
            writeSilence((to - from).toLong(), out)
            return
        }
        buffer.position(segment.skip + from).limit(segment.skip + to)
        emit(out, buffer)
    }

    private fun writeSilence(bytes: Long, out: WritableByteChannel) {
        var left = bytes
        while (left > 0) {
            silence.clear().limit(minOf(left, SILENCE_BYTES.toLong()).toInt())
            emit(out, silence)
            left -= silence.limit()
        }
    }

    companion object {
        const val HEADER_BYTES = 44
        const val RATE_KEY = "audioSampleRate"
        const val CHANNELS_KEY = "audioChannels"
        private const val MAX_DATA_BYTES = 0xffffffffL - (HEADER_BYTES - 8)
        private const val SILENCE_BYTES = 8192

        /** True when the take names its sample rate and channel count and holds an audio chunk. */
        fun hasAudio(index: OcrawFrameIndex): Boolean =
            positiveIntOrNull(index.metadata[RATE_KEY]) != null &&
                positiveIntOrNull(index.metadata[CHANNELS_KEY]) != null &&
                index.audio.isNotEmpty()

        private fun positiveInt(metadata: Map<String, String>, key: String): Int =
            requireNotNull(positiveIntOrNull(metadata[key])) { "take has no usable $key metadata (audio was not recorded?)" }

        /** As `_positive_int` in ocraw_to_dng.py: ASCII digits only, above zero. */
        private fun positiveIntOrNull(text: String?): Int? {
            if (text.isNullOrEmpty() || text.any { it !in '0'..'9' }) return null
            return text.toIntOrNull()?.takeIf { it > 0 }
        }
    }
}
