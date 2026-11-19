/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject

/** Which track of a take a sample or a [TakeTrackInfo] belongs to. [code] is its byte in the index file. */
enum class TrackKind(val code: Int) {
    VIDEO(0),
    AUDIO(1),
    ;

    companion object {
        fun fromCode(code: Int): TrackKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What [Mp4MoovRebuilder] needs to describe a track in a new `moov`: the codec configuration and the format keys the
 * muxer would have written. The parameter sets exist only in the encoder's MediaFormat (csd), never in the stream.
 * Colour values are the raw MediaFormat COLOR_* constants.
 */
class TakeTrackInfo(
    val mime: String,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val colorStandard: Int? = null,
    val colorRange: Int? = null,
    val colorTransfer: Int? = null,
    val pixelAspectWidth: Int? = null,
    val pixelAspectHeight: Int? = null,
    val encoderDelayFrames: Int? = null,
    val csd0: ByteArray? = null,
    val csd1: ByteArray? = null,
    val csd2: ByteArray? = null,
) {
    internal fun toJson(): String = JSONObject().apply {
        put("mime", mime)
        put("width", width)
        put("height", height)
        put("rotation", rotationDegrees)
        put("sampleRate", sampleRate)
        put("channelCount", channelCount)
        colorStandard?.let { put("colorStandard", it) }
        colorRange?.let { put("colorRange", it) }
        colorTransfer?.let { put("colorTransfer", it) }
        pixelAspectWidth?.let { put("paspW", it) }
        pixelAspectHeight?.let { put("paspH", it) }
        encoderDelayFrames?.let { put("encoderDelay", it) }
        csd0?.let { put("csd0", Base64.getEncoder().encodeToString(it)) }
        csd1?.let { put("csd1", Base64.getEncoder().encodeToString(it)) }
        csd2?.let { put("csd2", Base64.getEncoder().encodeToString(it)) }
    }.toString()

    companion object {
        internal fun fromJson(text: String): TakeTrackInfo {
            val o = JSONObject(text)
            fun int(key: String): Int? = if (o.has(key)) o.getInt(key) else null
            fun bytes(key: String): ByteArray? = if (o.has(key)) Base64.getDecoder().decode(o.getString(key)) else null
            return TakeTrackInfo(
                mime = o.getString("mime"),
                width = o.optInt("width"),
                height = o.optInt("height"),
                rotationDegrees = o.optInt("rotation"),
                sampleRate = o.optInt("sampleRate"),
                channelCount = o.optInt("channelCount"),
                colorStandard = int("colorStandard"),
                colorRange = int("colorRange"),
                colorTransfer = int("colorTransfer"),
                pixelAspectWidth = int("paspW"),
                pixelAspectHeight = int("paspH"),
                encoderDelayFrames = int("encoderDelay"),
                csd0 = bytes("csd0"),
                csd1 = bytes("csd1"),
                csd2 = bytes("csd2"),
            )
        }

        /** The info of the encoder output [format] (after INFO_OUTPUT_FORMAT_CHANGED, so the csd are present). */
        fun fromMediaFormat(format: MediaFormat, rotationDegrees: Int = 0): TakeTrackInfo {
            fun int(key: String): Int? = if (format.containsKey(key)) format.getInteger(key) else null
            fun bytes(key: String): ByteArray? = format.getByteBuffer(key)?.let { buffer ->
                ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
            }
            return TakeTrackInfo(
                mime = format.getString(MediaFormat.KEY_MIME) ?: "",
                width = int(MediaFormat.KEY_WIDTH) ?: 0,
                height = int(MediaFormat.KEY_HEIGHT) ?: 0,
                rotationDegrees = rotationDegrees,
                sampleRate = int(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
                channelCount = int(MediaFormat.KEY_CHANNEL_COUNT) ?: 0,
                colorStandard = int(MediaFormat.KEY_COLOR_STANDARD),
                colorRange = int(MediaFormat.KEY_COLOR_RANGE),
                colorTransfer = int(MediaFormat.KEY_COLOR_TRANSFER),
                // Literal keys: the public constants need API 30, minSdk is 29.
                pixelAspectWidth = int("sar-width"),
                pixelAspectHeight = int("sar-height"),
                encoderDelayFrames = int("encoder-delay"),
                csd0 = bytes("csd-0"),
                csd1 = bytes("csd-1"),
                csd2 = bytes("csd-2"),
            )
        }
    }
}

/** One encoded sample as the muxer was given it. Bit 0 of [flags] is MediaCodec.BUFFER_FLAG_KEY_FRAME. */
class IndexedSample(val size: Int, val presentationTimeUs: Long, val flags: Int) {
    val isKeyFrame: Boolean get() = flags and 1 != 0
}

class TakeSampleIndex(val tracks: Map<TrackKind, TakeTrackInfo>, val samples: Map<TrackKind, List<IndexedSample>>) {
    fun samplesOf(track: TrackKind): List<IndexedSample> = samples[track].orEmpty()
}

/**
 * PLAN-072, ADR-0037: the append-only side index of one take, in app-private storage.
 *
 * A drive pulled mid-take leaves an MP4 with its `mdat` bytes and no `moov`. The index records what the muxer was
 * given (per-sample size, time and flags, and each track's codec configuration) so [Mp4MoovRebuilder] can rebuild a
 * `moov` at recovery. It lives on the phone and only has to survive the app, so [flush] reaches the kernel without an
 * fsync. [writeSample] runs on the codec drain threads: it fills one reusable buffer and takes a monitor, nothing else.
 *
 * Format, big-endian: the magic "OCIX", a version byte (1), then records. 'T': track byte, u32 length, UTF-8 JSON of
 * [TakeTrackInfo]. 'S': track byte, u32 size, s64 presentation time in us, u32 flags.
 */
class TakeSampleIndexWriter(file: File) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file, false), BUFFER_BYTES)
    private val record = ByteBuffer.allocate(SAMPLE_RECORD_BYTES)
    private var closed = false

    init {
        out.write(MAGIC)
        out.write(VERSION)
    }

    @Synchronized
    fun writeTrack(track: TrackKind, info: TakeTrackInfo) {
        if (closed) return
        val json = info.toJson().toByteArray(StandardCharsets.UTF_8)
        val header = ByteBuffer.allocate(6).put('T'.code.toByte()).put(track.code.toByte()).putInt(json.size)
        out.write(header.array())
        out.write(json)
    }

    @Synchronized
    fun writeSample(track: TrackKind, size: Int, presentationTimeUs: Long, flags: Int) {
        if (closed) return
        record.clear()
        record.put('S'.code.toByte()).put(track.code.toByte()).putInt(size).putLong(presentationTimeUs).putInt(flags)
        out.write(record.array(), 0, SAMPLE_RECORD_BYTES)
    }

    /** Pushes the buffered records into the kernel (no fsync). */
    @Synchronized
    fun flush() {
        if (!closed) out.flush()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        out.close()
    }

    internal companion object {
        val MAGIC = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'I'.code.toByte(), 'X'.code.toByte())
        const val VERSION = 1
        const val SAMPLE_RECORD_BYTES = 18
        private const val BUFFER_BYTES = 64 * 1024
    }
}

/**
 * The index at [file], or null when it is missing, unreadable or has another magic or version. A torn last record (the
 * app died mid-write) is ignored, and so is everything after a record that does not parse.
 */
fun readTakeSampleIndex(file: File): TakeSampleIndex? {
    val bytes = try { file.readBytes() } catch (_: java.io.IOException) { return null }
    if (bytes.size < 5) return null
    for (i in TakeSampleIndexWriter.MAGIC.indices) if (bytes[i] != TakeSampleIndexWriter.MAGIC[i]) return null
    if (bytes[4].toInt() != TakeSampleIndexWriter.VERSION) return null
    val buffer = ByteBuffer.wrap(bytes)
    buffer.position(5)
    val tracks = LinkedHashMap<TrackKind, TakeTrackInfo>()
    val samples = LinkedHashMap<TrackKind, MutableList<IndexedSample>>()
    while (buffer.remaining() >= 2) {
        val tag = buffer.get().toInt().toChar()
        val track = TrackKind.fromCode(buffer.get().toInt()) ?: break
        if (tag == 'S') {
            if (buffer.remaining() < SAMPLE_BODY_BYTES) break
            val size = buffer.int
            val pts = buffer.long
            val flags = buffer.int
            if (size < 0) break
            samples.getOrPut(track) { ArrayList() } += IndexedSample(size, pts, flags)
        } else if (tag == 'T') {
            if (buffer.remaining() < 4) break
            val length = buffer.int
            if (length < 0 || buffer.remaining() < length) break
            val json = String(bytes, buffer.position(), length, StandardCharsets.UTF_8)
            buffer.position(buffer.position() + length)
            tracks[track] = try {
                TakeTrackInfo.fromJson(json)
            } catch (_: org.json.JSONException) {
                break
            } catch (_: IllegalArgumentException) {
                break // invalid Base64 in a csd field
            }
        } else break
    }
    return TakeSampleIndex(tracks, samples)
}

private const val SAMPLE_BODY_BYTES = 16
