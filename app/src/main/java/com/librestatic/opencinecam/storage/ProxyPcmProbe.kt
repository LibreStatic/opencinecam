/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Independent decoded-frame observation, not a claim that a subsequent AAC export is aligned.
 * The digest describes decoder PCM (including its reported encoding), not original encoded bytes.
 * No resampling, channel conversion, inferred silence, or command-duration frame estimates. */
internal data class ProxyPcmObservation(val sampleRateHz: Int, val channels: Int,
    val pcmEncoding: Int, val frames: Long, val decodedSha256: String,
    val exportPcm16Sha256: String = decodedSha256)

internal suspend fun probeProxyPcm(context: Context, source: Uri): ProxyPcmObservation = withContext(Dispatchers.IO) {
    require(source.scheme in setOf("content", "file"))
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    var started = false
    try {
        extractor.setDataSource(context, source, null)
        require(extractor.trackCount == 1) { "Sidecar must contain exactly one audio track" }
        val sourceFormat = extractor.getTrackFormat(0)
        val mime = requireNotNull(sourceFormat.getString(MediaFormat.KEY_MIME))
        require(mime in setOf("audio/raw", "audio/flac")) { "Sidecar must decode from PCM WAV or FLAC" }
        val rate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        require(rate in 1..384_000 && channels in 1..8)
        extractor.selectTrack(0)
        var encoding: Int? = null
        var frames = 0L
        val hash = MessageDigest.getInstance("SHA-256")
        val normalizedHash = MessageDigest.getInstance("SHA-256")
        fun consume(buffer: ByteBuffer, format: MediaFormat) {
            require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == rate &&
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == channels) { "Sidecar decoder changed sample grid" }
            val current = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                else AudioFormat.ENCODING_PCM_16BIT
            require(encoding == null || encoding == current) { "Sidecar decoder changed PCM encoding" }
            encoding = current
            val sampleBytes = when (current) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> error("Unsupported observed PCM encoding")
            }
            val frameBytes = channels * sampleBytes
            require(buffer.remaining() % frameBytes == 0) { "Sidecar decoder returned a partial PCM frame" }
            frames = Math.addExact(frames, (buffer.remaining() / frameBytes).toLong())
            hash.update(buffer.duplicate())
            normalizedHash.update(normalizeProxyPcm16(buffer, proxyPcmSampleType(current)))
        }
        if (mime == "audio/raw") {
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                require(size in 1..buffer.capacity()) { "Sidecar PCM sample exceeds read bound" }
                buffer.position(0); buffer.limit(size)
                consume(buffer, sourceFormat)
                if (!extractor.advance()) break
            }
        } else {
            val codec = MediaCodec.createDecoderByType(mime).also { decoder = it }
            codec.configure(sourceFormat, null, null, 0); codec.start(); started = true
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputFormat: MediaFormat? = null
            var lastProgress = SystemClock.elapsedRealtime()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime() - lastProgress < 10_000) { "Sidecar decoder stalled" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(1_000)
                    if (index >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(index)); input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                        } else {
                            require(size in 1..input.capacity() && extractor.sampleTime >= 0)
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 1_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { outputFormat = codec.outputFormat; lastProgress = SystemClock.elapsedRealtime() }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val output = requireNotNull(codec.getOutputBuffer(index)).duplicate()
                                require(info.offset >= 0 && info.size <= output.capacity() - info.offset)
                                output.position(info.offset); output.limit(info.offset + info.size)
                                consume(output, requireNotNull(outputFormat) { "Sidecar decoder omitted PCM format" })
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
            }
        }
        require(frames > 0) { "Sidecar contains no decoded PCM frames" }
        ProxyPcmObservation(rate, channels, requireNotNull(encoding), frames, hash.digest().proxyHex(), normalizedHash.digest().proxyHex())
    } finally {
        try { decoder?.let { try { if (started) it.stop() } finally { it.release() } } }
        finally { extractor.release() }
    }
}

internal fun proxyPcmSampleType(encoding: Int): ProxyPcmSampleType = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> ProxyPcmSampleType.U8
    AudioFormat.ENCODING_PCM_16BIT -> ProxyPcmSampleType.S16_LE
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> ProxyPcmSampleType.S24_LE
    AudioFormat.ENCODING_PCM_32BIT -> ProxyPcmSampleType.S32_LE
    AudioFormat.ENCODING_PCM_FLOAT -> ProxyPcmSampleType.F32_LE
    else -> error("Unsupported observed PCM encoding")
}
