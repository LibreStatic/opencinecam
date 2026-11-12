// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.ocraw

import com.librestatic.opencinecam.media.raw.NativeRawCodec
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel

/** Why [OcrawFrameSource.read] could not produce a frame. */
enum class OcrawMissingReason {
    /** The frame header is not v1-readable, or its geometry is not RAW10. */
    UNREADABLE_HEADER,

    /** CRC32C mismatch, a truncated chunk or a header that disagrees with its chunk. */
    CORRUPT,

    /** An encoding this build cannot decode. */
    UNSUPPORTED_ENCODING,

    /** A compressed frame, but `libocc_rawcodec` is not loaded. */
    CODEC_UNAVAILABLE,

    /** The codec rejected the frame as malformed. */
    DECODE_FAILED,
}

/** The outcome of [OcrawFrameSource.read]. */
sealed interface OcrawFrameRead {
    val frameIndex: Int

    /**
     * The slot holds the frame as tight RAW10 rows of [rowBytes] (`width * 5 / 4`),
     * position 0 and limit `rowBytes * header.height`. [header] is the stored one,
     * so for a compressed frame its `dataLength` is the encoded size. [metadata] is
     * [OcrawFrameIndex.effectiveFrameMetadata]: the frame's own `FRAME_METADATA`, or for
     * a frame without one the neutral and levels it inherits; [neutral] is its
     * `neutral` or, failing that, the nearest earlier frame's, else the nearest
     * later one's, else null.
     */
    data class Frame(
        override val frameIndex: Int,
        val header: OcrawFrameHeader,
        val rowBytes: Int,
        val metadata: Map<String, String>,
        val neutral: String?,
    ) : OcrawFrameRead

    data class Missing(override val frameIndex: Int, val reason: OcrawMissingReason) : OcrawFrameRead
}

/**
 * Reads frames of an indexed take as packed RAW10, the frame contract of the
 * playback GPU stage (OCC-PLAN-071 §7, decisions 1 and 2). RAW10 frames are read
 * with [OcrawStreamReader.readRawFrame] and its CRC check; LJ92 and block-pack
 * frames are read into one reusable input buffer (CRC-checked too) and decoded by
 * [NativeRawCodec]. A frame that fails any of this comes back as
 * [OcrawFrameRead.Missing], never as an exception.
 *
 * Frames land in a bounded pool of [slotCount] direct slots, each sized for the
 * largest decoded frame in [index] and allocated on first [acquire].
 *
 * Thread confinement: one reader thread owns the source. [acquire], [read],
 * [release] and [close] are not thread-safe; a slot may be handed to another
 * thread (the GL stage) only between [read] and [release]. The caller owns
 * [channel] and closes it after [close].
 */
class OcrawFrameSource(
    private val channel: FileChannel,
    val index: OcrawFrameIndex,
    private val decoderThreads: Int = DEFAULT_DECODER_THREADS,
    val slotCount: Int = DEFAULT_SLOTS,
) : Closeable {
    /** A pool buffer; valid until [release]. */
    class Slot internal constructor(val buffer: ByteBuffer) {
        internal var free = true
    }

    private val reader = OcrawStreamReader(channel, scratchBytes = OcrawFrameHeader.BYTES)
    private val slots = ArrayList<Slot>(slotCount)
    private var input: ByteBuffer? = null
    private var decoder: NativeRawCodec.Decoder? = null
    private var closed = false

    /** Bytes of one slot: the largest tight RAW10 frame in the index. */
    val slotBytes: Int

    private val maxEncodedBytes: Int

    /** For each frame, the index of the frame whose `neutral` it uses, or -1. */
    private val neutralSource: IntArray

    init {
        require(slotCount > 0) { "slotCount must be positive" }
        require(decoderThreads in 1..64) { "decoderThreads must be 1..64" }
        var largest = 0L
        var encoded = 0
        index.frames.forEach { frame ->
            val header = frame.header ?: return@forEach
            if (header.width % 4 == 0) largest = maxOf(largest, header.width / 4 * 5L * header.height)
            if (header.encoding != OcrawFrameHeader.ENCODING_RAW10) encoded = maxOf(encoded, header.dataLength)
        }
        require(largest <= Int.MAX_VALUE) { "frame too large for a direct buffer" }
        slotBytes = largest.toInt()
        maxEncodedBytes = encoded
        neutralSource = nearestNeutral(index)
    }

    val frameCount: Int get() = index.frames.size

    /** A free slot, or null when all [slotCount] are acquired. */
    fun acquire(): Slot? {
        checkOpen()
        slots.firstOrNull { it.free }?.let { slot ->
            slot.free = false
            return slot
        }
        if (slots.size == slotCount) return null
        val slot = Slot(ByteBuffer.allocateDirect(slotBytes.coerceAtLeast(1)).order(ByteOrder.LITTLE_ENDIAN))
        slot.free = false
        slots += slot
        return slot
    }

    fun release(slot: Slot) {
        require(slot in slots) { "slot is not from this source" }
        slot.free = true
    }

    /** Fills [slot] with frame [frameIndex]; see [OcrawFrameRead.Frame] for the layout. */
    fun read(frameIndex: Int, slot: Slot): OcrawFrameRead {
        checkOpen()
        require(frameIndex in index.frames.indices) { "frame $frameIndex out of 0 until $frameCount" }
        require(slot in slots && !slot.free) { "slot is not acquired from this source" }
        val frame = index.frames[frameIndex]
        val header = frame.header ?: return missing(frameIndex, OcrawMissingReason.UNREADABLE_HEADER)
        if (header.width % 4 != 0) return missing(frameIndex, OcrawMissingReason.UNREADABLE_HEADER)
        val rowBytes = header.width / 4 * 5
        val bytes = rowBytes * header.height
        val dst = slot.buffer
        dst.clear()
        when (header.encoding) {
            OcrawFrameHeader.ENCODING_RAW10 -> {
                if (readChecked(frame.fileOffset, dst) == null) return missing(frameIndex, OcrawMissingReason.CORRUPT)
            }
            OcrawFrameHeader.ENCODING_LJ92, OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK,
            OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED -> {
                val codec = NativeRawCodec.Codec.entries.first { it.encoding == header.encoding }
                val decoder = decoder() ?: return missing(frameIndex, OcrawMissingReason.CODEC_UNAVAILABLE)
                val src = inputBuffer()
                src.clear()
                if (readChecked(frame.fileOffset, src) == null) return missing(frameIndex, OcrawMissingReason.CORRUPT)
                src.flip()
                val decoded = try {
                    decoder.decode(codec, src, header.width, header.height, dst, rowBytes)
                } catch (_: IllegalArgumentException) {
                    false
                }
                if (!decoded) return missing(frameIndex, OcrawMissingReason.DECODE_FAILED)
            }
            else -> return missing(frameIndex, OcrawMissingReason.UNSUPPORTED_ENCODING)
        }
        dst.position(0).limit(bytes)
        val source = neutralSource[frameIndex]
        return OcrawFrameRead.Frame(
            frameIndex = frameIndex,
            header = header,
            rowBytes = rowBytes,
            metadata = index.effectiveFrameMetadata(index.frames[frameIndex].sequence),
            neutral = if (source < 0) null else metadataOf(source)[NEUTRAL_KEY],
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        decoder?.close()
        decoder = null
        slots.clear()
        input = null
    }

    private fun metadataOf(frameIndex: Int): Map<String, String> =
        index.frameMetadata[index.frames[frameIndex].sequence].orEmpty()

    private fun missing(frameIndex: Int, reason: OcrawMissingReason) = OcrawFrameRead.Missing(frameIndex, reason)

    /** [OcrawStreamReader.readRawFrame], with a corrupt or truncated chunk as null. A closed channel still throws. */
    private fun readChecked(chunkOffset: Long, dst: ByteBuffer): OcrawFrameHeader? = try {
        reader.readRawFrame(chunkOffset, dst)
    } catch (error: ClosedChannelException) {
        throw error
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decoder(): NativeRawCodec.Decoder? {
        decoder?.let { return it }
        if (!NativeRawCodec.available) return null
        return NativeRawCodec.newDecoder(decoderThreads).also { decoder = it }
    }

    private fun inputBuffer(): ByteBuffer =
        input ?: ByteBuffer.allocateDirect(maxEncodedBytes.coerceAtLeast(1)).also { input = it }

    private fun checkOpen() = check(!closed) { "frame source is closed" }

    companion object {
        const val DEFAULT_SLOTS = 3
        const val NEUTRAL_KEY = "neutral"
        val DEFAULT_DECODER_THREADS: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /** Per frame, the nearest frame carrying a `neutral`: itself, else earlier, else later, else -1. */
        internal fun nearestNeutral(index: OcrawFrameIndex): IntArray {
            val count = index.frames.size
            val has = BooleanArray(count) { NEUTRAL_KEY in index.frameMetadata[index.frames[it].sequence].orEmpty() }
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
            return source
        }
    }
}
