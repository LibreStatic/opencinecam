/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.media.MediaCodec
import android.system.ErrnoException
import android.system.Os
import com.librestatic.opencinecam.camera.AacCodecCalibrator
import com.librestatic.opencinecam.camera.pcmFrameDurationNs
import com.librestatic.opencinecam.media.mux.DescriptorMp4Bytes
import com.librestatic.opencinecam.media.mux.Mp4AacTrack
import com.librestatic.opencinecam.media.mux.Mp4AudioAppender
import com.librestatic.opencinecam.media.mux.Mp4VideoTiming
import com.librestatic.opencinecam.media.mux.readMp4VideoTiming
import java.io.EOFException
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** The canonical 44-byte header [createWavHeader] writes. */
internal const val PCM16_WAV_HEADER_BYTES = 44

/** The PCM layout of a 16-bit WAV whose data starts at [PCM16_WAV_HEADER_BYTES]. */
internal class Pcm16WavHeader(val sampleRateHz: Int, val channels: Int, val dataBytes: Long) {
    val blockAlign: Int get() = channels * 2
    val frames: Long get() = dataBytes / blockAlign
}

/**
 * Reads the canonical header [createWavHeader] writes for 16-bit PCM, from [header]'s position. [fileSize] is the whole
 * file's. Throws [IllegalArgumentException] for anything else: other formats, depths or layouts, or a data size the file
 * cannot hold.
 */
internal fun parsePcm16WavHeader(header: ByteBuffer, fileSize: Long): Pcm16WavHeader {
    require(header.remaining() >= PCM16_WAV_HEADER_BYTES) { "WAV header is truncated" }
    val v = header.slice().order(ByteOrder.LITTLE_ENDIAN)
    fun tag(at: Int) = String(ByteArray(4) { v.get(at + it) }, Charsets.US_ASCII)
    fun u32(at: Int) = v.getInt(at).toLong() and 0xffff_ffffL
    fun u16(at: Int) = v.getShort(at).toInt() and 0xffff
    require(tag(0) == "RIFF" && tag(8) == "WAVE") { "Not a RIFF WAVE file" }
    require(tag(12) == "fmt " && u32(16) == 16L) { "WAV fmt chunk is not canonical" }
    require(u16(20) == 1) { "WAV is not integer PCM" }
    require(u16(34) == 16) { "WAV is not 16-bit" }
    val channels = u16(22)
    require(channels in 1..2) { "WAV has $channels channels" }
    val rate = u32(24)
    require(rate in 1..Int.MAX_VALUE) { "WAV sample rate $rate" }
    val blockAlign = channels * 2
    require(u16(32) == blockAlign && u32(28) == rate * blockAlign) { "WAV block alignment is inconsistent" }
    require(tag(36) == "data") { "WAV data chunk is not canonical" }
    val dataBytes = u32(40)
    require(dataBytes > 0) { "WAV has no PCM" }
    require(dataBytes % blockAlign == 0L) { "WAV data ends inside a frame" }
    require(u32(4) == 36L + dataBytes) { "WAV RIFF size does not match its data" }
    require(dataBytes <= fileSize - PCM16_WAV_HEADER_BYTES) { "WAV data runs past the end of the file" }
    return Pcm16WavHeader(rate.toInt(), channels, dataBytes)
}

/** What [appendWavAsAac] put in the file. */
internal class AppendedTakeAudio(
    val sampleRateHz: Int,
    val channels: Int,
    val bitrateBps: Int,
    /** Frames read from the WAV. */
    val pcmFrames: Long,
    val accessUnits: Long,
    val primingFrames: Int,
    /** As passed to [Mp4AacTrack]. */
    val startOffsetUs: Long,
    val video: Mp4VideoTiming,
    val encoderName: String,
)

/** The encoder's input size, as the proxy sidecar export uses: no AudioRecord sets it here. */
private const val APPEND_MAX_INPUT_BYTES = 16_384
private const val APPEND_CODEC_TIMEOUT_US = 10_000L
private const val APPEND_STALL_NS = 10_000_000_000L
private const val AAC_FRAMES_PER_UNIT = 1024

/**
 * Encodes the PCM of [wav] as AAC-LC and appends it to [video] as its audio track (PLAN-074 decision 10).
 * [startOffsetUs] receives the video's timing and answers where the first PCM frame sits on the file's timeline
 * (negative: the audio started before the video). Throws on any failure; until the appender's finish the video is
 * unchanged apart from sealed bytes after its end. Blocking; call it on the storage thread.
 *
 * The encoder configuration is calibrated by [AacCodecCalibrator] like the embedded AAC path: its priming becomes the
 * track's priming, its drain padding is fed as silence after the last PCM frame, and the live encoder must emit the
 * calibrated codec config. Neither descriptor is closed.
 */
internal fun appendWavAsAac(
    video: FileDescriptor,
    wav: FileDescriptor,
    bitrateBps: Int,
    startOffsetUs: (Mp4VideoTiming) -> Long,
    isCancelled: () -> Boolean = { false },
): AppendedTakeAudio {
    val wavSize = try { Os.fstat(wav).st_size } catch (e: ErrnoException) { throw e.rethrowAsIOException() }
    val header = ByteBuffer.allocate(PCM16_WAV_HEADER_BYTES)
    preadFully(wav, header, 0)
    header.flip()
    val pcm = parsePcm16WavHeader(header, wavSize)
    val videoBytes = DescriptorMp4Bytes(video)
    val timing = readMp4VideoTiming(videoBytes)
    val offsetUs = startOffsetUs(timing)
    checkNotCancelled(isCancelled)
    val config = AacCodecCalibrator.selectConfig(pcm.sampleRateHz, pcm.channels, bitrateBps, APPEND_MAX_INPUT_BYTES)
    val calibration = AacCodecCalibrator.qualify(config, isCancelled)
    checkNotCancelled(isCancelled)

    val appender = Mp4AudioAppender.begin(videoBytes)
    try {
        val codec = MediaCodec.createByCodecName(config.codecName)
        var started = false
        try {
            codec.configure(AacCodecCalibrator.encoderFormat(config), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            started = true
            val frameBytes = pcm.blockAlign
            val chunkBytes = config.maxInputBytes / frameBytes * frameBytes
            val paddingFrames = calibration.drainPaddingFrames.toLong()
            var readFrames = 0L
            var paddedFrames = 0L
            var inputEos = false
            var outputEos = false
            var formatCsd: ByteArray? = null
            var configCsd: ByteArray? = null
            var units = 0L
            val info = MediaCodec.BufferInfo()
            var lastProgressNs = System.nanoTime()
            while (!outputEos) {
                checkNotCancelled(isCancelled)
                check(System.nanoTime() - lastProgressNs < APPEND_STALL_NS) { "AAC encoder stalled" }
                if (!inputEos) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                        val capacityFrames = minOf(buffer.capacity(), chunkBytes) / frameBytes
                        check(capacityFrames > 0) { "AAC encoder input buffer holds no PCM frame" }
                        val totalFrames = readFrames + paddedFrames
                        val ptsUs = pcmFrameDurationNs(totalFrames, pcm.sampleRateHz) / 1000
                        if (readFrames < pcm.frames) {
                            val frames = minOf(capacityFrames.toLong(), pcm.frames - readFrames).toInt()
                            buffer.limit(frames * frameBytes)
                            preadFully(wav, buffer, PCM16_WAV_HEADER_BYTES + readFrames * frameBytes)
                            codec.queueInputBuffer(index, 0, frames * frameBytes, ptsUs, 0)
                            readFrames += frames
                        } else if (paddedFrames < paddingFrames) {
                            // Drain samples are not part of the take and never count as valid frames.
                            val frames = minOf(capacityFrames.toLong(), paddingFrames - paddedFrames).toInt()
                            buffer.put(ByteArray(frames * frameBytes))
                            codec.queueInputBuffer(index, 0, frames * frameBytes, ptsUs, 0)
                            paddedFrames += frames
                        } else {
                            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        }
                        lastProgressNs = System.nanoTime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, APPEND_CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(formatCsd == null) { "AAC encoder emitted its format twice" }
                        formatCsd = codec.outputFormat.getByteBuffer("csd-0")?.duplicate()?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                        lastProgressNs = System.nanoTime()
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0) {
                                val buffer = requireNotNull(codec.getOutputBuffer(index)).duplicate()
                                buffer.limit(info.offset + info.size)
                                buffer.position(info.offset)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    configCsd = ByteArray(info.size).also { buffer.get(it) }
                                } else {
                                    appender.writeAccessUnit(buffer)
                                    units++
                                }
                            }
                            outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                        lastProgressNs = System.nanoTime()
                    }
                }
            }
            val csd = checkNotNull(formatCsd ?: configCsd) { "AAC encoder emitted no codec config" }
            AacCodecCalibrator.requireCalibratedCodecConfig(calibration, csd)
            check(units * AAC_FRAMES_PER_UNIT >= calibration.primingFrames + readFrames) {
                "AAC encoder emitted $units access units for ${calibration.primingFrames} priming and $readFrames PCM frames"
            }
            checkNotCancelled(isCancelled)
            appender.finish(Mp4AacTrack(
                sampleRateHz = pcm.sampleRateHz,
                channels = pcm.channels,
                audioSpecificConfig = csd,
                bitrateBps = config.bitrateBps,
                primingFrames = calibration.primingFrames,
                validFrames = readFrames,
                startOffsetUs = offsetUs,
            ))
            return AppendedTakeAudio(pcm.sampleRateHz, pcm.channels, config.bitrateBps, readFrames, units,
                calibration.primingFrames, offsetUs, timing, config.codecName)
        } finally {
            try { if (started) codec.stop() } finally { codec.release() }
        }
    } finally {
        appender.close()
    }
}

private fun checkNotCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("Appending the take's audio was cancelled")
}

/** Fills [buffer]'s remaining bytes from [position] of [descriptor], leaving the descriptor's offset alone. */
private fun preadFully(descriptor: FileDescriptor, buffer: ByteBuffer, position: Long) {
    var at = position
    while (buffer.hasRemaining()) {
        val read = try { Os.pread(descriptor, buffer, at) } catch (e: ErrnoException) { throw e.rethrowAsIOException() }
        if (read <= 0) throw EOFException("The WAV ends at $at, ${buffer.remaining()} bytes short")
        at += read
    }
}
