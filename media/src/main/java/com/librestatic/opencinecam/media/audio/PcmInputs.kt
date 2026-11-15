/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.os.Handler
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** True when the native low-latency input can be opened on this device (its library loads). */
fun lowLatencyInputAvailable(): Boolean = AAudioNative.isAvailable

/** Which properties of a built `AudioRecord` must equal the request; anything else is refused. */
enum class PcmFormatCheck { NONE, SAMPLE_RATE, RATE_AND_CHANNELS, EXACT }

/** What a read loop asks [PcmInputs.open] for. */
class PcmInputRequest(
    val source: AudioSourceSelection,
    val sampleRateHz: Int,
    val channels: Int,
    /** The `AudioFormat.ENCODING_*` of the take: what [PcmInput.read] must deliver. */
    val encoding: Int,
    /** The `AudioRecord` buffer; the low-latency input keeps its own capacity. */
    val bufferBytes: Int,
    /** The input the take asked for, already resolved; null lets the platform route. */
    val preferredDevice: AudioDeviceInfo?,
    /** The `AudioRecord` source; [source]'s own unless the caller only knows the platform value. */
    val androidSource: Int = source.androidSource,
    /** Names the input in failure messages, e.g. `FLAC AudioRecord`. */
    val label: String = "AudioRecord",
    val formatCheck: PcmFormatCheck = PcmFormatCheck.EXACT,
    /** The failure when the `AudioRecord` does not initialize; loops keep the message they always had. */
    val initializationFailure: String = "$label initialization failed.",
    /** The failure when the `AudioRecord` refuses [preferredDevice], given its id. */
    val deviceRejected: (Int) -> String = { id -> "$label rejected audio input $id." },
)

class OpenedPcmInput(
    val input: PcmInput,
    /** Null when the input is what the source asked for; otherwise why it is the `AudioRecord` fallback. */
    val fallbackReason: String?,
    /** Bits the microphone path delivered: 16 for the low-latency input, else the take's. */
    val captureBits: Int,
)

/** How [PcmInputs.open] serves a request. */
internal enum class PcmInputRoute { AUDIO_RECORD, LOW_LATENCY, EXTERNAL_FALLBACK }

/**
 * The low-latency input is for the built-in microphones only (PLAN-074 decision 2): an external
 * input keeps `AudioRecord`, which routes to it and carries its formats.
 */
internal fun pcmInputRoute(lowLatency: Boolean, preferredDeviceType: Int?): PcmInputRoute = when {
    !lowLatency -> PcmInputRoute.AUDIO_RECORD
    preferredDeviceType == null || audioInputKind(preferredDeviceType) == AudioInputKind.BUILT_IN -> PcmInputRoute.LOW_LATENCY
    else -> PcmInputRoute.EXTERNAL_FALLBACK
}

/** Bits per sample of an `AudioFormat.ENCODING_*` the read loops use. */
internal fun pcmEncodingBits(encoding: Int): Int = when (encoding) {
    AudioFormat.ENCODING_PCM_16BIT -> 16
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
    AudioFormat.ENCODING_PCM_FLOAT -> 32
    else -> throw IllegalArgumentException("Unsupported PCM encoding $encoding.")
}

/** Opens the PCM input of a read loop (PLAN-074 decision 3). */
object PcmInputs {
    const val FALLBACK_EXTERNAL_INPUT = "external-input"
    private const val TAG = "PcmInputs"

    /**
     * Opens what [request] asks for. A source other than [AudioSourceSelection.LOW_LATENCY] builds an
     * `AudioRecord` exactly as the read loops always did. The low-latency source opens the native
     * input on a built-in microphone, widened to the take's encoding when that is not PCM16; on an
     * external input, or when the native input refuses, it falls back to `AudioRecord` with
     * [AudioSourceSelection.androidSource] and says why in [OpenedPcmInput.fallbackReason].
     *
     * Callers check `RECORD_AUDIO` first. Throws [IllegalStateException] when the `AudioRecord` does
     * not initialize, differs from the request or refuses the preferred device.
     */
    fun open(request: PcmInputRequest): OpenedPcmInput {
        val bits = pcmEncodingBits(request.encoding)
        return when (pcmInputRoute(request.source.lowLatency, request.preferredDevice?.type)) {
            PcmInputRoute.AUDIO_RECORD -> OpenedPcmInput(openAudioRecord(request), null, bits)
            PcmInputRoute.EXTERNAL_FALLBACK -> fallback(request, FALLBACK_EXTERNAL_INPUT, null, bits)
            PcmInputRoute.LOW_LATENCY -> try {
                val native = AAudioPcmInput.open(request.sampleRateHz, request.channels, deviceId = request.preferredDevice?.id)
                Log.i(TAG, "Native input open: performance mode ${native.performanceMode}, burst ${native.framesPerBurst} frames, " +
                    "capacity ${native.bufferCapacityFrames} frames.")
                val input = if (request.encoding == AudioFormat.ENCODING_PCM_16BIT) native else try {
                    WideningPcmInput(native, request.encoding)
                } catch (failure: Throwable) {
                    native.close()
                    throw failure
                }
                OpenedPcmInput(input, null, 16)
            } catch (refused: PcmInputUnavailableException) {
                fallback(request, refused.reason, refused, bits)
            }
        }
    }

    private fun fallback(request: PcmInputRequest, reason: String, cause: Throwable?, bits: Int): OpenedPcmInput {
        Log.w(TAG, "Low-latency input not used ($reason); recording through AudioRecord source ${request.androidSource}.", cause)
        return OpenedPcmInput(openAudioRecord(request), reason, bits)
    }

    @SuppressLint("MissingPermission")
    private fun openAudioRecord(request: PcmInputRequest): AudioRecordPcmInput {
        val mask = if (request.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val record = AudioRecord.Builder()
            .setAudioSource(request.androidSource)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(request.sampleRateHz)
                .setEncoding(request.encoding)
                .setChannelMask(mask)
                .build())
            .setBufferSizeInBytes(request.bufferBytes)
            .build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { request.initializationFailure }
            val rateMatches = record.sampleRate == request.sampleRateHz
            val channelsMatch = record.channelCount == request.channels
            when (request.formatCheck) {
                PcmFormatCheck.NONE -> Unit
                PcmFormatCheck.SAMPLE_RATE -> check(rateMatches) { "${request.label} sample rate differs from the request." }
                PcmFormatCheck.RATE_AND_CHANNELS -> check(rateMatches && channelsMatch) {
                    "${request.label} PCM format differs from the request."
                }
                PcmFormatCheck.EXACT -> check(rateMatches && channelsMatch && record.audioFormat == request.encoding) {
                    "${request.label} actual PCM format differs from requested lossless format."
                }
            }
            request.preferredDevice?.let { device ->
                check(record.setPreferredDevice(device)) { request.deviceRejected(device.id) }
            }
            return AudioRecordPcmInput(record)
        } catch (failure: Throwable) {
            runCatching { record.release() }
            throw failure
        }
    }
}

/**
 * A PCM16 input delivered as a wider take encoding (PLAN-074 decision 5): 24-bit packed (sample << 8)
 * or float (sample / 32768). The 16-bit samples are read into a buffer of its own, then widened into
 * the caller's; byte counts are converted both ways in whole frames. Everything else is [wrapped]'s.
 */
class WideningPcmInput(val wrapped: PcmInput, override val encoding: Int) : PcmInput {
    init {
        require(wrapped.encoding == AudioFormat.ENCODING_PCM_16BIT) { "Only a PCM16 input can be widened." }
        require(encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            "PCM16 widens only to 24-bit packed or float, not encoding $encoding."
        }
    }

    private val outputBytesPerSample = pcmEncodingBits(encoding) / 8
    private val inputFrameBytes = wrapped.channels * 2
    private val outputFrameBytes = wrapped.channels * outputBytesPerSample
    // Grows to the largest read asked for; allocated on the first read, not per buffer.
    private var pcm16: ByteBuffer = ByteBuffer.allocateDirect(0)

    override val sampleRateHz: Int get() = wrapped.sampleRateHz
    override val channels: Int get() = wrapped.channels
    override val audioSessionId: Int? get() = wrapped.audioSessionId
    override val routedDeviceId: Int? get() = wrapped.routedDeviceId
    override val path: PcmInputPath get() = wrapped.path

    override fun start() = wrapped.start()

    override fun read(buffer: ByteBuffer, byteCount: Int): Int {
        val frames = byteCount / outputFrameBytes
        if (frames <= 0 || byteCount > buffer.capacity()) return PcmInput.ERROR_INVALID
        val inputBytes = frames * inputFrameBytes
        if (pcm16.capacity() < inputBytes) pcm16 = ByteBuffer.allocateDirect(inputBytes).order(ByteOrder.LITTLE_ENDIAN)
        val read = wrapped.read(pcm16, inputBytes)
        if (read <= 0) return read
        val samples = read / inputFrameBytes * wrapped.channels
        for (index in 0 until samples) {
            val sample = pcm16.getShort(index * 2).toInt()
            val offset = index * outputBytesPerSample
            if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
                buffer.put(offset, 0)
                buffer.put(offset + 1, sample.toByte())
                buffer.put(offset + 2, (sample shr 8).toByte())
            } else {
                val bits = java.lang.Float.floatToRawIntBits(sample / 32768f)
                buffer.put(offset, bits.toByte())
                buffer.put(offset + 1, (bits shr 8).toByte())
                buffer.put(offset + 2, (bits shr 16).toByte())
                buffer.put(offset + 3, (bits shr 24).toByte())
            }
        }
        return samples * outputBytesPerSample
    }

    override fun timestamp(out: PcmInputTimestamp): Boolean = wrapped.timestamp(out)

    override fun stop() = wrapped.stop()

    override fun close() = wrapped.close()
}

/**
 * The [AudioRouting] an [AudioInputRouteGuard] attaches to. An `AudioRecord` input is its own; any
 * other input gets a view whose routed device is [PcmInput.routedDeviceId], with no preferred device
 * and no routing callbacks: a lost native device shows as [PcmInput.ERROR_DEAD] from a read, and the
 * guard's own device callback still sees a requested input that is removed.
 */
fun PcmInput.audioRouting(context: Context): AudioRouting = when (this) {
    is AudioRecordPcmInput -> audioRecord
    is WideningPcmInput -> wrapped.audioRouting(context)
    else -> PcmInputRouting(context.applicationContext, this)
}

private class PcmInputRouting(private val context: Context, private val input: PcmInput) : AudioRouting {
    override fun setPreferredDevice(deviceInfo: AudioDeviceInfo?): Boolean = false

    override fun getPreferredDevice(): AudioDeviceInfo? = null

    override fun getRoutedDevice(): AudioDeviceInfo? = input.routedDeviceId?.let { id ->
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
    }

    override fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener?, handler: Handler?) = Unit

    override fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener?) = Unit
}
