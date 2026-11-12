/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import com.librestatic.opencinecam.camera.RawVideoConfig
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.raw.NativeRawCodec
import com.librestatic.opencinecam.media.raw.RawQuantisation
import java.nio.ByteBuffer

/**
 * OCC-PLAN-071 slice G2b: one lossless frame codec as the RAW take pipeline uses it. The production
 * ones are [NativeBlockPackFrameEncoder] (what the app records) and [NativeLj92FrameEncoder]; host
 * tests inject a fake, so they never need the native library. Used by one thread at a time (the
 * take's writer thread).
 */
interface RawFrameEncoder : AutoCloseable {
    /**
     * The OCRAW frame header encoding of the frame the last [encode] produced
     * ([OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK], [OcrawFrameHeader.ENCODING_LJ92], or for the
     * near-lossless encoder [OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED] or block-pack). Constant
     * for the lossless encoders.
     */
    val encoding: Int

    /**
     * Static `METADATA` keys describing the codec settings (`blockPackStrips`; `lj92Predictor`,
     * `lj92Strips`; the near-lossless encoder also sets [RawFrameEncoders.KEY_ENCODING] and its own keys).
     */
    val metadata: Map<String, String>

    /**
     * The sensor sensitivity (Camera2 ISO) of the frames that follow; called on the encoding thread,
     * never during [encode]. Only the near-lossless encoder uses it.
     */
    fun setIso(iso: Int) = Unit

    /**
     * Forgets what the previous take left behind, before a new take's first frame. The near-lossless
     * encoder forgets its ISO, so frames are lossless again until this take gives one.
     */
    fun startTake() = Unit

    /**
     * Encodes the tight RAW10 frame `src[position, limit)` of [width] x [height] into
     * `dst[position, limit)`. Returns the encoded size, or 0 when it does not fit (the frame is then
     * stored uncompressed). Positions and limits are not changed.
     */
    fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int
}

/** [NativeRawCodec] LJ92 with a persistent pool of [threads] (the caller counts as one). */
class NativeLj92FrameEncoder(
    threads: Int = RawFrameEncoders.LJ92_THREADS,
    strips: Int = RawFrameEncoders.DEFAULT_STRIPS,
    predictor: Int = RawFrameEncoders.LJ92_PREDICTOR,
) : RawFrameEncoder {
    private val encoder = NativeRawCodec.newEncoder(
        NativeRawCodec.Codec.LJ92, threads = threads, strips = strips, lj92Predictor = predictor, lj92OptimalHuffman = true,
    )

    override val encoding: Int = OcrawFrameHeader.ENCODING_LJ92

    override val metadata: Map<String, String> = mapOf(
        RawFrameEncoders.KEY_LJ92_PREDICTOR to predictor.toString(),
        RawFrameEncoders.KEY_LJ92_STRIPS to strips.toString(),
    )

    override fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int =
        encoder.encode(src, width, height, dst)

    override fun close() = encoder.close()
}

/** [NativeRawCodec] Bayer block-pack with a persistent pool of [threads] (the caller counts as one). */
class NativeBlockPackFrameEncoder(
    threads: Int = RawFrameEncoders.BLOCK_PACK_THREADS,
    strips: Int = RawFrameEncoders.DEFAULT_STRIPS,
) : RawFrameEncoder {
    private val encoder = NativeRawCodec.newEncoder(NativeRawCodec.Codec.BLOCK_PACK, threads = threads, strips = strips)

    override val encoding: Int = OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK

    override val metadata: Map<String, String> = mapOf(RawFrameEncoders.KEY_BLOCK_PACK_STRIPS to strips.toString())

    override fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int =
        encoder.encode(src, width, height, dst)

    override fun close() = encoder.close()
}

/**
 * The tables of a [NativeRawCodec.Codec.BLOCK_PACK_QUANTISED] encoder and its encode; a seam so host
 * tests can stand in for the native codec.
 */
interface QuantisingFrameCodec : AutoCloseable {
    fun setQuantisation(tables: RawQuantisation.Tables, iso: Int)

    /** As [RawFrameEncoder.encode]. */
    fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int
}

/** [QuantisingFrameCodec] over a native quantised block-pack encoder. */
class NativeQuantisingFrameCodec(
    threads: Int = RawFrameEncoders.BLOCK_PACK_THREADS,
    strips: Int = RawFrameEncoders.DEFAULT_STRIPS,
) : QuantisingFrameCodec {
    private val encoder = NativeRawCodec.newEncoder(NativeRawCodec.Codec.BLOCK_PACK_QUANTISED, threads = threads, strips = strips)

    override fun setQuantisation(tables: RawQuantisation.Tables, iso: Int) = encoder.setQuantisation(tables, iso)

    override fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int = encoder.encode(src, width, height, dst)

    override fun close() = encoder.close()
}

/**
 * The near-lossless RAW video encoder: block-pack of codes quantised to the sensor noise of [profile]
 * at the frame's ISO (OCRAW encoding 3, [RawQuantisation]). The writer learns the ISO from the capture
 * results, which trail the frames, so [setIso] gives the most recent one known; the tables are rebuilt
 * only when it changes. Until the first ISO is known frames are encoded lossless by [lossless] (encoding
 * 2), which each frame header says, so a reader needs nothing else. [encoding] is the last frame's.
 */
class NearLosslessFrameEncoder(
    private val lossless: RawFrameEncoder,
    private val quantised: QuantisingFrameCodec,
    private val profile: RawNoiseProfile,
    private val k: Double = RawQuantisation.DEFAULT_K,
) : RawFrameEncoder {
    /** The ISO the current tables were built for; null before the first [setIso]. */
    var tablesIso: Int? = null
        private set

    /** How many times the tables were built, for tests and diagnostics. */
    var tableBuilds: Int = 0
        private set

    override var encoding: Int = OcrawFrameHeader.ENCODING_BAYER_BLOCK_PACK
        private set

    override val metadata: Map<String, String> = lossless.metadata + mapOf(
        RawFrameEncoders.KEY_ENCODING to RawFrameEncoders.ENCODING_NEAR_LOSSLESS,
        RawFrameEncoders.KEY_QUANT_K to k.toString(),
        RawFrameEncoders.KEY_QUANT_NOISE_PROFILE to profile.id,
    )

    override fun setIso(iso: Int) {
        if (iso <= 0 || iso == tablesIso) return
        quantised.setQuantisation(RawQuantisation.build(profile.modelAt(iso), profile.blackLevel, k), iso)
        tablesIso = iso
        tableBuilds++
    }

    override fun startTake() {
        tablesIso = null
    }

    override fun encode(src: ByteBuffer, width: Int, height: Int, dst: ByteBuffer): Int =
        if (tablesIso == null) {
            encoding = lossless.encoding
            lossless.encode(src, width, height, dst)
        } else {
            encoding = OcrawFrameHeader.ENCODING_BLOCK_PACK_QUANTISED
            quantised.encode(src, width, height, dst)
        }

    override fun close() {
        try {
            quantised.close()
        } finally {
            lossless.close()
        }
    }
}

/** The encoder settings PLAN-071 chose on device evidence, and the factories the recorder uses. */
object RawFrameEncoders {
    /**
     * Block-pack encode parallelism: on the Razr Fold a 12 MP frame takes 6.6 ms on one thread and
     * 3.5 ms on two; more threads gain nothing there.
     */
    const val BLOCK_PACK_THREADS = 2
    /** LJ92 encode parallelism: about 12–15 ms per 12.5 MP frame on the S24, ≈1.5 cores at 30 fps. */
    const val LJ92_THREADS = 4
    /** Strips (independent streams); for LJ92 each maps to one DNG tile. */
    const val DEFAULT_STRIPS = 8
    /** T.81 predictor 1 (Ra, the same-colour sample two pixels left), with per-strip optimal Huffman tables. */
    const val LJ92_PREDICTOR = 1

    /** `METADATA` key of the configured encoding, [RawVideoConfig.encoding] (`raw10`, `bp` or `lj92`). */
    const val KEY_ENCODING = "encoding"
    const val KEY_LJ92_PREDICTOR = "lj92Predictor"
    const val KEY_LJ92_STRIPS = "lj92Strips"
    const val KEY_BLOCK_PACK_STRIPS = "blockPackStrips"
    /** Near-lossless takes: the step in noise sigmas, a decimal. */
    const val KEY_QUANT_K = "quantK"
    /** Near-lossless takes: the noise profile the tables come from (`device/camera`), informative. */
    const val KEY_QUANT_NOISE_PROFILE = "quantNoiseProfile"

    /**
     * The [KEY_ENCODING] value of a near-lossless take: a block-pack configuration recorded with
     * quantised block-pack. Never a [RawVideoConfig.encoding]: the configuration (and its
     * qualification) stays block-pack.
     */
    const val ENCODING_NEAR_LOSSLESS = "bpq"

    /** The range threads and strips accept (the native codec's limits). */
    val SETTING_RANGE: IntRange = 1..64

    /** The production thread count of a compressed [encoding] ([RawVideoConfig.encoding]). */
    fun defaultThreads(encoding: String): Int = when (encoding) {
        RawVideoConfig.ENCODING_LJ92 -> LJ92_THREADS
        else -> BLOCK_PACK_THREADS
    }

    /**
     * A native encoder for the compressed [encoding] ([RawVideoConfig.ENCODING_BLOCK_PACK] or
     * [RawVideoConfig.ENCODING_LJ92]), or null when `libocc_rawcodec` is not available (the config
     * cannot be armed). [threads] and [strips] default to the production settings; the qualifier's
     * encode benchmark passes others to compare them (more threads than strips cannot help: a strip
     * is one task).
     */
    fun native(encoding: String, threads: Int = defaultThreads(encoding), strips: Int = DEFAULT_STRIPS): RawFrameEncoder? =
        when (encoding) {
            RawVideoConfig.ENCODING_BLOCK_PACK -> nativeBlockPack(threads, strips)
            RawVideoConfig.ENCODING_LJ92 -> nativeLj92(threads, strips)
            else -> throw IllegalArgumentException("not a compressed RAW video encoding: $encoding")
        }

    /** A native block-pack encoder, or null when `libocc_rawcodec` is not available. */
    fun nativeBlockPack(threads: Int = BLOCK_PACK_THREADS, strips: Int = DEFAULT_STRIPS): RawFrameEncoder? {
        require(threads in SETTING_RANGE && strips in SETTING_RANGE) { "block-pack threads and strips must be 1..64" }
        return if (NativeRawCodec.available) NativeBlockPackFrameEncoder(threads, strips) else null
    }

    /**
     * A native near-lossless encoder for [profile] (a lossless and a quantised block-pack encoder, each
     * with its own pool of [threads]), or null when `libocc_rawcodec` is not available.
     */
    fun nativeNearLossless(profile: RawNoiseProfile, threads: Int = BLOCK_PACK_THREADS, strips: Int = DEFAULT_STRIPS): RawFrameEncoder? {
        require(threads in SETTING_RANGE && strips in SETTING_RANGE) { "block-pack threads and strips must be 1..64" }
        if (!NativeRawCodec.available) return null
        val lossless = NativeBlockPackFrameEncoder(threads, strips)
        val quantised = try {
            NativeQuantisingFrameCodec(threads, strips)
        } catch (problem: Throwable) {
            lossless.close()
            throw problem
        }
        return NearLosslessFrameEncoder(lossless, quantised, profile)
    }

    /** A native LJ92 encoder, or null when `libocc_rawcodec` is not available. */
    fun nativeLj92(threads: Int = LJ92_THREADS, strips: Int = DEFAULT_STRIPS): RawFrameEncoder? {
        require(threads in SETTING_RANGE && strips in SETTING_RANGE) { "LJ92 threads and strips must be 1..64" }
        return if (NativeRawCodec.available) NativeLj92FrameEncoder(threads, strips) else null
    }
}

/**
 * Encodes ring slots for one armed configuration into [output] (preallocated once, the slot size)
 * and keeps the take's compression numbers. Writer thread only; the numbers are safe to read from
 * any thread. A frame that does not fit [output] is stored uncompressed (encoding 0): the format
 * allows the encoding to change from frame to frame.
 */
class RawFrameCompressor(private val encoder: RawFrameEncoder, private val output: ByteBuffer) {
    @Volatile var framesEncoded = 0L
        private set
    @Volatile var framesStoredUncompressed = 0L
        private set
    /** Sum of the frames' RAW10 sizes. */
    @Volatile var uncompressedBytes = 0L
        private set
    /** Sum of the frame data actually stored (encoded or, for a fallback, RAW10). */
    @Volatile var storedBytes = 0L
        private set
    @Volatile var encodeTotalNs = 0L
        private set
    @Volatile var encodeMaxNs = 0L
        private set
    /** The last call's encode time, for callers that keep a distribution. */
    @Volatile var lastEncodeNs = 0L
        private set

    val encoding: Int get() = encoder.encoding
    val metadata: Map<String, String> get() = encoder.metadata

    /** uncompressed / stored, or null before the first frame. */
    val compressionRatio: Double? get() = storedBytes.takeIf { it > 0 }?.let { uncompressedBytes.toDouble() / it }

    /** Mean encode time per frame, fallbacks included, or null before the first frame. */
    val encodeMeanMs: Double?
        get() {
            val frames = framesEncoded + framesStoredUncompressed
            return if (frames > 0) encodeTotalNs / 1e6 / frames else null
        }

    val encodeMaxMs: Double get() = encodeMaxNs / 1e6

    /** Resets the numbers and the encoder's per-take state for a new take. */
    fun reset() {
        encoder.startTake()
        framesEncoded = 0; framesStoredUncompressed = 0; uncompressedBytes = 0; storedBytes = 0
        encodeTotalNs = 0; encodeMaxNs = 0; lastEncodeNs = 0
    }

    /**
     * The header and data to write for the RAW10 frame [header] in [frame] (position 0, limit its
     * data length); [iso], when known, is the sensitivity of the frame (or the latest one known) for
     * the near-lossless encoder. The returned buffer is [output] or [frame] and stays valid until the next call.
     * An encoder exception propagates (the caller treats it as a writer failure).
     */
    fun compress(header: OcrawFrameHeader, frame: ByteBuffer, iso: Int? = null): Pair<OcrawFrameHeader, ByteBuffer> {
        require(header.encoding == OcrawFrameHeader.ENCODING_RAW10) { "only RAW10 frames are compressed" }
        output.clear()
        val started = System.nanoTime()
        // A table rebuild (near-lossless, on an ISO change) counts as encode time: the writer pays it.
        if (iso != null) encoder.setIso(iso)
        val size = encoder.encode(frame, header.width, header.height, output)
        val elapsed = System.nanoTime() - started
        lastEncodeNs = elapsed
        encodeTotalNs += elapsed
        if (elapsed > encodeMaxNs) encodeMaxNs = elapsed
        uncompressedBytes += header.dataLength
        return if (size in 1..output.capacity()) {
            output.limit(size).position(0)
            framesEncoded++
            storedBytes += size
            // rowBytes keeps the RAW10 row length of the decoded frame (see opencine-raw-v1.md).
            header.copy(encoding = encoder.encoding, dataLength = size) to output
        } else {
            framesStoredUncompressed++
            storedBytes += header.dataLength
            header to frame
        }
    }
}
