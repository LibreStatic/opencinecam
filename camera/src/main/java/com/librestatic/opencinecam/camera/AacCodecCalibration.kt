/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToLong

/** Exact encoder configuration. Cache is process-local; codec/OS changes do not inherit old evidence. */
data class AacCalibrationConfig(val codecName: String, val sampleRateHz: Int, val channels: Int, val bitrateBps: Int, val maxInputBytes: Int) {
    init {
        require(codecName.isNotBlank() && sampleRateHz in 8000..192000 && channels in 1..2)
        require(bitrateBps in 16000..1024000 && maxInputBytes in 2..1048576)
    }
}
data class AacCodecCalibration(
    val config: AacCalibrationConfig,
    val decoderName: String,
    val primingFrames: Int,
    val drainPaddingFrames: Int,
    val minimumCorrelation: Double,
    val decoderSignalLagFrames: Int,
    val decoderFirstPtsUs: Long,
    val firstEncodedPtsUs: Long,
    val measuredAtElapsedMs: Long,
    val codecSpecificDataSha256: String,
)
data class AacProbePacket(val data: ByteArray, val ptsUs: Long, val flags: Int)
data class AacCalibrationEvidence(
    val config: AacCalibrationConfig, val sourceFrames: Int, val paddingFrames: Int,
    val sourcePcm: ByteArray, val decodedPcm: ByteArray, val codecSpecificData: ByteArray,
    val packets: List<AacProbePacket>, val measurement: AacCodecCalibration,
)

/** Native resources stay on their owner until actual stop/release. No microphone is opened here. */
object AacCodecCalibrator {
    private val cache = mutableMapOf<AacCalibrationConfig, AacCodecCalibration>()
    @Synchronized fun qualify(config: AacCalibrationConfig, isCancelled: () -> Boolean = { false }, onEvidence: ((AacCalibrationEvidence) -> Unit)? = null): AacCodecCalibration {
        check(!isCancelled()) { "AAC calibration was cancelled" }
        if (onEvidence == null) cache[config]?.takeIf { SystemClock.elapsedRealtime() - it.measuredAtElapsedMs in 0..300000 }?.let { return it }
        // A bounded explicit drain, verified with two source lengths and different input boundaries.
        // This is a measured configuration policy, not an assumption about a codec name.
        var lastFailure: Throwable? = null
        for (padding in listOf(4096, 8192, 16384)) {
            try {
                val first = probe(config, 24576, padding, 2048, isCancelled)
                val second = probe(config, 25013, padding, 1161, isCancelled)
                check(first.measurement.primingFrames == second.measurement.primingFrames &&
                    first.measurement.decoderName == second.measurement.decoderName && first.codecSpecificData.contentEquals(second.codecSpecificData)) { "AAC priming was not repeatable" }
                val result = first.measurement.copy(minimumCorrelation = minOf(first.measurement.minimumCorrelation, second.measurement.minimumCorrelation))
                check(!isCancelled()) { "AAC calibration was cancelled" }
                onEvidence?.invoke(first); onEvidence?.invoke(second)
                cache[config] = result
                return result
            } catch (failure: IllegalArgumentException) { lastFailure = failure }
        }
        throw IllegalStateException("AAC configuration did not preserve the complete calibration signal", lastFailure)
    }

    private data class Encoded(val packets: List<AacProbePacket>, val csd: ByteArray)
    private data class Decoded(val bytes: ByteArray, val firstPtsUs: Long, val name: String)
    private fun probe(config: AacCalibrationConfig, frames: Int, padding: Int, chunkFrames: Int, isCancelled: () -> Boolean): AacCalibrationEvidence {
        val source = aacCalibrationSignal(frames, config.sampleRateHz, config.channels)
        val pcm = ByteBuffer.allocate((frames + padding) * config.channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        source.forEach { pcm.putShort(it) }
        val encoded = encode(config, pcm.array(), chunkFrames, isCancelled)
        val decoded = decode(config, encoded, isCancelled)
        val shorts = ByteBuffer.wrap(decoded.bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { buffer -> ShortArray(buffer.remaining()).also { buffer.get(it) } }
        val alignment = measureAacSignal(source, shorts, config.channels)
        val firstEncoded = encoded.packets.first().ptsUs
        val ptsOffset = ((decoded.firstPtsUs - firstEncoded) * config.sampleRateHz / 1000000.0).roundToLong()
        val priming = Math.addExact(ptsOffset, alignment.lagFrames.toLong())
        require(priming in 0..8192) { "AAC priming exceeds measured policy bounds" }
        val result = AacCodecCalibration(config, decoded.name, priming.toInt(), padding, alignment.minimumCorrelation,
            alignment.lagFrames, decoded.firstPtsUs, firstEncoded, SystemClock.elapsedRealtime(), aacCodecConfigSha256(encoded.csd))
        return AacCalibrationEvidence(config, frames, padding, pcm.array().copyOf(frames * config.channels * 2),
            decoded.bytes, encoded.csd, encoded.packets, result)
    }

    private fun encode(config: AacCalibrationConfig, pcm: ByteArray, chunkFrames: Int, isCancelled: () -> Boolean): Encoded {
        val codec = MediaCodec.createByCodecName(config.codecName)
        var started = false
        try {
            codec.configure(encoderFormat(config), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); started = true
            val packets = mutableListOf<AacProbePacket>(); var csd: ByteArray? = null
            var at = 0; var inputEos = false; var outputEos = false; var encodedBytes = 0
            val info = MediaCodec.BufferInfo(); val deadline = SystemClock.elapsedRealtime() + 5000
            while (!outputEos) {
                check(!isCancelled()) { "AAC calibration was cancelled" }
                check(SystemClock.elapsedRealtime() < deadline) { "AAC calibration encoder EOS timed out" }
                if (!inputEos) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                        val frameBytes = config.channels * 2
                        val count = minOf(buffer.capacity() / frameBytes, chunkFrames, (pcm.size - at) / frameBytes) * frameBytes
                        require(count > 0 || at == pcm.size)
                        buffer.put(pcm, at, count)
                        inputEos = count == 0
                        codec.queueInputBuffer(index, 0, count, pcmFrameDurationNs((at / frameBytes).toLong(), config.sampleRateHz) / 1000,
                            if (inputEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        at += count
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(csd == null)
                        csd = requireNotNull(codec.outputFormat.getByteBuffer("csd-0")).duplicate().let { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } }
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                require(packets.size < 256 && info.size <= 65536)
                                encodedBytes = Math.addExact(encodedBytes, info.size); require(encodedBytes <= 1048576)
                                val buffer = requireNotNull(codec.getOutputBuffer(index)).duplicate()
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size); buffer.get(bytes)
                                require(packets.lastOrNull()?.let { info.presentationTimeUs < it.ptsUs } != true)
                                packets += AacProbePacket(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                            }
                            outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
            check(packets.isNotEmpty())
            return Encoded(packets, requireNotNull(csd))
        } finally { try { if (started) codec.stop() } finally { codec.release() } }
    }

    private fun decode(config: AacCalibrationConfig, encoded: Encoded, isCancelled: () -> Boolean): Decoded {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val name = codec.name
        var started = false
        try {
            // Do not pass encoder delay/padding keys: measure the raw decoder timeline explicitly.
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRateHz, config.channels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(encoded.csd))
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }, null, null, 0)
            codec.start(); started = true
            val output = ByteArrayOutputStream()
            var at = 0; var inputEos = false; var outputEos = false; var firstPts: Long? = null
            val info = MediaCodec.BufferInfo(); val deadline = SystemClock.elapsedRealtime() + 5000
            while (!outputEos) {
                check(!isCancelled()) { "AAC calibration was cancelled" }
                check(SystemClock.elapsedRealtime() < deadline) { "AAC calibration decoder EOS timed out" }
                if (!inputEos) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                        val packet = encoded.packets.getOrNull(at)
                        if (packet == null) {
                            codec.queueInputBuffer(index, 0, 0, encoded.packets.last().ptsUs + 1024000000L / config.sampleRateHz, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            require(packet.data.size <= buffer.capacity())
                            buffer.put(packet.data); codec.queueInputBuffer(index, 0, packet.data.size, packet.ptsUs, 0); at++
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == config.sampleRateHz && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == config.channels)
                        require(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0) {
                                require(info.size % (config.channels * 2) == 0 && output.size() + info.size <= 1048576)
                                val first = firstPts ?: info.presentationTimeUs.also { firstPts = it }
                                val expected = first + pcmFrameDurationNs((output.size() / (config.channels * 2)).toLong(), config.sampleRateHz) / 1000
                                require(abs(info.presentationTimeUs - expected) <= 1000000L / config.sampleRateHz + 1) { "AAC decoder PCM timestamps are not contiguous" }
                                val buffer = requireNotNull(codec.getOutputBuffer(index)).duplicate()
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size); buffer.get(bytes); output.write(bytes)
                            }
                            outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
            return Decoded(output.toByteArray(), requireNotNull(firstPts), name)
        } finally { try { if (started) codec.stop() } finally { codec.release() } }
    }

    fun selectConfig(rate: Int, channels: Int, bitrate: Int, maxInputBytes: Int): AacCalibrationConfig {
        val requested = AacCalibrationConfig("selection", rate, channels, bitrate, maxInputBytes)
        val name = MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(encoderFormat(requested))
            ?: error("No AAC-LC encoder accepts the requested audio configuration")
        return requested.copy(codecName = name)
    }

    fun encoderFormat(config: AacCalibrationConfig): MediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRateHz, config.channels).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, config.maxInputBytes)
    }
}

fun aacCodecConfigSha256(bytes: ByteArray): String {
    require(bytes.size in 1..4096)
    return java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
