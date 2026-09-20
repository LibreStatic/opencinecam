/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultCodec
import com.librestatic.opencinecam.camera.AacCodecCalibration
import com.librestatic.opencinecam.camera.AacCodecCalibrator
import com.librestatic.opencinecam.camera.AacSourceWindow
import com.librestatic.opencinecam.camera.ProjectMp4File
import com.librestatic.opencinecam.camera.aacCodecConfigSha256
import com.librestatic.opencinecam.camera.finalizeAacSourceWindow
import com.librestatic.opencinecam.camera.inspectAacSourceWindow
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class ProxySidecarExportInput(val uri: Uri, val pcm: ProxyPcmObservation,
    val timeline: ProxySidecarTimeline)

/** An independently counted PCM16 encoder route. Media3 normalizes source bit depth before effects;
 * its complete output must match the independent normalization hash, not the raw source-byte hash.
 * The extra zeros only drain the calibrated AAC encoder; the MP4 presentation excludes all of them.
 * Neither capture pause cuts nor leading/trailing source gaps become synthetic presented samples. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ProxySidecarExport(val input: ProxySidecarExportInput, val calibration: AacCodecCalibration) {
    var videoDecoderCreated = false
    private var encodedInputFrames = 0L
    private var packets = 0L
    private var audioTrackId: Int? = null
    private var videoTrackId: Int? = null
    private var firstVideo = true
    private var encoderCreated = false
    private var encoderEos = false
    private var processorEos = false
    val audioProcessor: AudioProcessor = object : BaseAudioProcessor() {
        private var frames = 0L
        private val hash = MessageDigest.getInstance("SHA-256")
        private var pendingDrain: ByteBuffer? = null
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            require(inputAudioFormat.sampleRate == input.pcm.sampleRateHz &&
                inputAudioFormat.channelCount == input.pcm.channels && inputAudioFormat.encoding == C.ENCODING_PCM_16BIT)
            return inputAudioFormat
        }
        override fun queueInput(buffer: ByteBuffer) {
            val size = buffer.remaining()
            if (size == 0) return
            check(!processorEos) { "Sidecar PCM arrived after EOS" }
            require(size % (input.pcm.channels * 2) == 0)
            frames = Math.addExact(frames, (size / (input.pcm.channels * 2)).toLong())
            require(frames <= input.pcm.frames) { "Media3 supplied excess sidecar PCM" }
            hash.update(buffer.duplicate())
            replaceOutputBuffer(size).put(buffer).flip()
        }
        override fun onQueueEndOfStream() {
            // AudioProcessingPipeline may requeue EOS while draining our output buffer.
            // Validate and append the calibrated drain once; never reset/digest it a second time.
            if (processorEos) return
            val observedHash = hash.digest().proxyHex()
            check(!processorEos && frames == input.pcm.frames && observedHash == input.pcm.exportPcm16Sha256) {
                "Media3 sidecar PCM differs from independent probe: eos=$processorEos frames=$frames expected=${input.pcm.frames} hash=$observedHash expectedHash=${input.pcm.exportPcm16Sha256}"
            }
            processorEos = true
            val bytes = calibration.drainPaddingFrames * input.pcm.channels * 2
            // EOS may arrive while the downstream pipeline still owns our last PCM output.
            // Never reuse that backing buffer for drain, or its unconsumed partial block is lost.
            pendingDrain = ByteBuffer.allocateDirect(bytes).put(ByteArray(bytes)).apply { flip() }
        }
        override fun getOutput(): ByteBuffer {
            val output = super.getOutput()
            if (output.hasRemaining()) return output
            return pendingDrain?.also { pendingDrain = null } ?: output
        }
        override fun isEnded(): Boolean = super.isEnded() && pendingDrain == null
    }

    fun createEncoder(context: Context, format: Format): Codec {
        check(!encoderCreated); encoderCreated = true
        require(format.sampleMimeType == "audio/mp4a-latm" && format.sampleRate == input.pcm.sampleRateHz &&
            format.channelCount == input.pcm.channels && format.pcmEncoding == C.ENCODING_PCM_16BIT)
        val config = calibration.config
        val native = DefaultCodec(context, format, AacCodecCalibrator.encoderFormat(config), config.codecName, false, null)
        return object : Codec by native {
            override fun queueInputBuffer(buffer: DecoderInputBuffer) {
                val bytes = buffer.data?.remaining() ?: 0
                require(bytes % (config.channels * 2) == 0)
                encodedInputFrames = Math.addExact(encodedInputFrames, (bytes / (config.channels * 2)).toLong())
                if (buffer.isEndOfStream) {
                    check(!encoderEos && encodedInputFrames == input.pcm.frames + calibration.drainPaddingFrames) {
                        "Sidecar encoder input differs: eos=$encoderEos frames=$encodedInputFrames expected=${input.pcm.frames + calibration.drainPaddingFrames} source=${input.pcm.frames} drain=${calibration.drainPaddingFrames}"
                    }
                    encoderEos = true
                }
                native.queueInputBuffer(buffer)
            }
            override fun getOutputFormat(): Format? = native.outputFormat?.let { output ->
                check(output.sampleRate == config.sampleRateHz && output.channelCount == config.channels &&
                    output.initializationData.size == 1 &&
                    aacCodecConfigSha256(output.initializationData.single()) == calibration.codecSpecificDataSha256)
                // One authority owns the final edit list; do not also apply Media3's inferred delay.
                output.buildUpon().setEncoderDelay(0).setEncoderPadding(0).build()
            }
        }
    }

    fun wrapMuxer(delegate: Muxer.Factory): Muxer.Factory = object : Muxer.Factory by delegate {
        override fun create(path: String): Muxer {
            val native = delegate.create(path)
            return object : Muxer by native {
                override fun addTrack(format: Format): Int = native.addTrack(format).also { id ->
                    when {
                        format.sampleMimeType?.startsWith("audio/") == true -> { check(audioTrackId == null); audioTrackId = id }
                        format.sampleMimeType?.startsWith("video/") == true -> { check(videoTrackId == null); videoTrackId = id }
                        else -> error("Unexpected sidecar composition track")
                    }
                }
                override fun writeSampleData(trackId: Int, buffer: ByteBuffer, info: BufferInfo) {
                    var timeUs = info.presentationTimeUs
                    if (trackId == audioTrackId && info.size > 0) packets++
                    if (trackId == videoTrackId && info.size > 0 && firstVideo) {
                        firstVideo = false
                        // Media3 1.11 forces only the first video PTS to zero when audio is present.
                        // Restore the independently observed source anchor, not a computed rebase.
                        require(timeUs == 0L || timeUs == input.timeline.videoFirstPtsUs)
                        timeUs = input.timeline.videoFirstPtsUs
                    }
                    native.writeSampleData(trackId, buffer, BufferInfo(timeUs, info.size, info.flags))
                }
            }
        }
    }

    fun finalizeCandidate(file: File) {
        check(encoderCreated && encoderEos && processorEos && packets > 0 && !firstVideo)
        val window = AacSourceWindow(input.pcm.sampleRateHz, input.pcm.frames,
            calibration.primingFrames.toLong(), packets, input.timeline.audioStartUs)
        RandomAccessFile(file, "rw").use { access ->
            val mp4 = object : ProjectMp4File {
                override val size: Long get() = access.length()
                override fun read(offset: Long, length: Int): ByteArray = ByteArray(length).also {
                    access.seek(offset); access.readFully(it)
                }
                override fun write(offset: Long, bytes: ByteArray) { access.seek(offset); access.write(bytes) }
            }
            finalizeProxyVideoTrackHeader(mp4, input.timeline.videoEndUs)
            finalizeAacSourceWindow(mp4, window)
            check(inspectAacSourceWindow(mp4) == window) { "Proxy AAC presentation window differs" }
        }
    }
}
