/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.camera.SoftAgc
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.camera.AudioLevelMeter
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.PcmMeterEncoding
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Lossless audio recorder paired with a video by basename. The monotonic start/stop timestamps in
 * the JSON sidecar make the independently stored PCM stream alignable without pretending it is an
 * MP4 track. WAV/RF64 files larger than 4 GiB are deliberately rejected rather than corrupted.
 */
class WavAudioSidecarRecorder private constructor(
    private val context: Context,
    val uri: Uri,
    override val displayName: String,
    private val settings: CameraSettings,
    private val audioRecord: AudioRecord,
    private val descriptor: ParcelFileDescriptor,
    private val output: FileOutputStream,
    private val preferredInput: AudioDeviceInfo?,
    private val onAudioLevel: ((AudioLevelSnapshot) -> Unit)?,
) : AudioSidecarRecorder {
    private val running = AtomicBoolean(false)
    private val effects = mutableListOf<AudioEffect>()
    private var writerThread: Thread? = null
    @Volatile private var writerFailure: Throwable? = null
    private var dataBytes = 0L
    private var startedAtElapsedNs = 0L
    private var stoppedAtElapsedNs = 0L
    private var routedInputDeviceId: Int? = null
    private var effectState = EffectState(false, false, false)
    private var finished = false
    private var softAgc: SoftAgc? = null
    private val levelMeter = AudioLevelMeter(settings.audioBitDepth.toMeterEncoding(), settings.audioChannels)

    override fun start() {
        check(!finished && writerThread == null) { "WAV recorder cannot be started twice." }
        output.channel.position(0)
        output.channel.write(createWavHeader(0, settings.audioSampleRateHz, settings.audioBitDepth.bits, settings.audioChannels, settings.audioBitDepth == AudioBitDepth.PCM_FLOAT))
        effectState = attachEffects()
        startedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
        audioRecord.startRecording()
        check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not enter RECORDING state." }
        routedInputDeviceId = audioRecord.routedDevice?.id
        running.set(true)
        writerThread = Thread(::writeLoop, "OpenCineCamWavWriter").apply { start() }
    }

    override fun finish(success: Boolean): AudioSidecarRecordingResult? {
        if (finished) return null
        finished = true
        running.set(false)
        runCatching { audioRecord.stop() }
        writerThread?.join(3_000)
        if (writerThread?.isAlive == true) {
            writerFailure = IllegalStateException("WAV writer did not stop within 3 seconds.")
            writerThread?.interrupt()
        }
        stoppedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
        val capturedResult = result()
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        audioRecord.release()
        val failure = writerFailure
        return try {
            if (!success || failure != null || dataBytes == 0L) {
                context.contentResolver.delete(uri, null, null)
                if (success && failure != null) throw failure
                null
            } else {
                output.channel.position(0)
                output.channel.write(createWavHeader(dataBytes, settings.audioSampleRateHz, settings.audioBitDepth.bits, settings.audioChannels, settings.audioBitDepth == AudioBitDepth.PCM_FLOAT))
                output.channel.force(true)
                output.close()
                var metadataUri: Uri? = null
                try {
                    metadataUri = writeMetadata(capturedResult)
                    check(context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                        null,
                        null,
                    ) == 1) { "WAV MediaStore publication failed." }
                    capturedResult
                } catch (failure: Throwable) {
                    context.contentResolver.delete(uri, null, null)
                    metadataUri?.let { context.contentResolver.delete(it, null, null) }
                    throw failure
                }
            }
        } finally {
            runCatching { output.close() }
            runCatching { descriptor.close() }
        }
    }

    override fun close() {
        finish(false)
    }

    private fun writeLoop() {
        val frameBytes = settings.audioChannels * settings.audioBitDepth.bits / 8
        val minimum = AudioRecord.getMinBufferSize(
            settings.audioSampleRateHz,
            channelMask(settings.audioChannels),
            settings.audioBitDepth.androidEncoding,
        )
        val bufferSize = (minimum * 2).coerceAtLeast(frameBytes * 4_096)
        val buffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (running.get()) {
                buffer.clear()
                val read = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    error("AudioRecord read failed with code $read")
                }
                if (read <= 0) continue
                levelMeter.analyze(buffer, read, SystemClock.elapsedRealtime())?.let { onAudioLevel?.invoke(it) }
                when (settings.audioBitDepth) {
                    AudioBitDepth.PCM_16 -> softAgc?.processPcm16(buffer, read)
                    AudioBitDepth.PCM_24 -> softAgc?.processPcm24(buffer, read)
                    AudioBitDepth.PCM_FLOAT -> Unit // SoftAgc does not support float PCM yet
                }
                check(dataBytes + read <= MAX_WAV_DATA_BYTES) { "WAV reached the 4 GiB RIFF limit." }
                buffer.position(0)
                buffer.limit(read)
                while (buffer.hasRemaining()) output.channel.write(buffer)
                dataBytes += read
            }
        } catch (failure: Throwable) {
            writerFailure = failure
            running.set(false)
        }
    }

    private fun attachEffects(): EffectState {
        fun <T : AudioEffect> safeCreate(create: () -> T?, requested: Boolean): Boolean {
            val effect = try { create() } catch (failure: Throwable) { null } ?: return false
            effects += effect
            val status = runCatching { effect.setEnabled(requested) }.getOrDefault(AudioEffect.ERROR)
            return status == AudioEffect.SUCCESS && effect.enabled
        }
        val hardwareAgc = safeCreate(
            { AutomaticGainControl.create(audioRecord.audioSessionId) },
            settings.automaticGainControlEnabled,
        )
        if (settings.automaticGainControlEnabled && !hardwareAgc) {
            softAgc = SoftAgc(settings.audioSampleRateHz, settings.audioChannels)
        }
        return EffectState(
            noiseSuppressor = safeCreate({ NoiseSuppressor.create(audioRecord.audioSessionId) }, settings.noiseSuppressorEnabled),
            automaticGainControl = hardwareAgc || softAgc != null,
            acousticEchoCanceler = safeCreate({ AcousticEchoCanceler.create(audioRecord.audioSessionId) }, settings.acousticEchoCancelerEnabled),
        )
    }

    private fun result() = AudioSidecarRecordingResult(
        container = "WAV",
        uri = uri,
        displayName = displayName,
        sampleRateHz = audioRecord.sampleRate,
        bitDepth = settings.audioBitDepth,
        channels = settings.audioChannels,
        derivedBitrateKbps = settings.audioSampleRateHz * settings.audioBitDepth.bits * settings.audioChannels / 1_000,
        frames = dataBytes / (settings.audioChannels * settings.audioBitDepth.bits / 8),
        dataBytes = dataBytes,
        startedAtElapsedNs = startedAtElapsedNs,
        stoppedAtElapsedNs = stoppedAtElapsedNs,
        preferredInputDeviceId = preferredInput?.id,
        routedInputDeviceId = routedInputDeviceId,
        source = settings.audioSource.name,
        noiseSuppressorEnabled = effectState.noiseSuppressor,
        automaticGainControlEnabled = effectState.automaticGainControl,
        acousticEchoCancelerEnabled = effectState.acousticEchoCanceler,
    )

    private fun writeMetadata(result: AudioSidecarRecordingResult): Uri {
        val json = JSONObject()
            .put("schema", "opencinecam-audio-sidecar-v1")
            .put("audioUri", result.uri.toString())
            .put("file", result.displayName)
            .put("container", "WAV")
            .put("encoding", result.bitDepth.name)
            .put("sampleRateHz", result.sampleRateHz)
            .put("channels", result.channels)
            .put("derivedBitrateKbps", result.derivedBitrateKbps)
            .put("frames", result.frames)
            .put("dataBytes", result.dataBytes)
            .put("startedAtElapsedRealtimeNs", result.startedAtElapsedNs)
            .put("stoppedAtElapsedRealtimeNs", result.stoppedAtElapsedNs)
            .put("source", result.source)
            .put("preferredInputDeviceId", result.preferredInputDeviceId ?: JSONObject.NULL)
            .put("routedInputDeviceId", result.routedInputDeviceId ?: JSONObject.NULL)
            .put("noiseSuppressorEnabled", result.noiseSuppressorEnabled)
            .put("automaticGainControlEnabled", result.automaticGainControlEnabled)
            .put("acousticEchoCancelerEnabled", result.acousticEchoCancelerEnabled)
            .put("disclosure", "Public AudioRecord state; OEM signal processing outside public APIs may still exist.")
        val metadataName = displayName.removeSuffix(".wav") + ".audio.json"
        val resolver = context.contentResolver
        val metadataUri = requireNotNull(resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, metadataName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/OpenCineCam")
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ))
        try {
            requireNotNull(resolver.openOutputStream(metadataUri, "w")).use { it.write(json.toString(2).toByteArray()) }
            check(resolver.update(metadataUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
                "Audio metadata publication failed."
            }
            return metadataUri
        } catch (failure: Throwable) {
            resolver.delete(metadataUri, null, null)
            throw failure
        }
    }

    private data class EffectState(
        val noiseSuppressor: Boolean,
        val automaticGainControl: Boolean,
        val acousticEchoCanceler: Boolean,
    )

    companion object {
        private const val MAX_WAV_DATA_BYTES = 0xffff_ffffL - 36L

        @SuppressLint("MissingPermission")
        fun create(
            context: Context,
            videoDisplayName: String,
            settings: CameraSettings,
            onAudioLevel: ((AudioLevelSnapshot) -> Unit)? = null,
        ): WavAudioSidecarRecorder {
            val appContext = context.applicationContext
            val displayName = videoDisplayName.removeSuffix(".mp4") + ".wav"
            val resolver = appContext.contentResolver
            val uri = requireNotNull(resolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/OpenCineCam")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                },
            ))
            var record: AudioRecord? = null
            var descriptor: ParcelFileDescriptor? = null
            try {
                val mask = channelMask(settings.audioChannels)
                val minimum = AudioRecord.getMinBufferSize(settings.audioSampleRateHz, mask, settings.audioBitDepth.androidEncoding)
                require(minimum > 0) { "Unsupported WAV configuration." }
                record = AudioRecord.Builder()
                    .setAudioSource(settings.audioSource.androidSource)
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(settings.audioSampleRateHz)
                        .setEncoding(settings.audioBitDepth.androidEncoding)
                        .setChannelMask(mask)
                        .build())
                    .setBufferSizeInBytes((minimum * 2).coerceAtLeast(16_384))
                    .build()
                check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed." }
                val preferred = settings.audioInputDeviceId?.let { requestedId ->
                    appContext.getSystemService(AudioManager::class.java)
                        .getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.id == requestedId }
                        ?: error("Requested audio input $requestedId is no longer connected.")
                }
                if (preferred != null) check(record.setPreferredDevice(preferred)) { "AudioRecord rejected audio input ${preferred.id}." }
                descriptor = requireNotNull(resolver.openFileDescriptor(uri, "rw"))
                return WavAudioSidecarRecorder(
                    appContext,
                    uri,
                    displayName,
                    settings,
                    record,
                    descriptor,
                    FileOutputStream(descriptor.fileDescriptor),
                    preferred,
                    onAudioLevel,
                )
            } catch (failure: Throwable) {
                runCatching { record?.release() }
                runCatching { descriptor?.close() }
                resolver.delete(uri, null, null)
                throw failure
            }
        }

        private fun channelMask(channels: Int): Int = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    }
}

interface AudioSidecarRecorder : AutoCloseable {
    val displayName: String
    fun start()
    fun finish(success: Boolean): AudioSidecarRecordingResult?
}

internal fun AudioBitDepth.toMeterEncoding(): PcmMeterEncoding = when (this) {
    AudioBitDepth.PCM_16 -> PcmMeterEncoding.PCM_16
    AudioBitDepth.PCM_24 -> PcmMeterEncoding.PCM_24
    AudioBitDepth.PCM_FLOAT -> PcmMeterEncoding.PCM_FLOAT
}

data class AudioSidecarRecordingResult(
    val container: String,
    val uri: Uri,
    val displayName: String,
    val sampleRateHz: Int,
    val bitDepth: AudioBitDepth,
    val channels: Int,
    val derivedBitrateKbps: Int,
    val frames: Long,
    val dataBytes: Long,
    val startedAtElapsedNs: Long,
    val stoppedAtElapsedNs: Long,
    val preferredInputDeviceId: Int?,
    val routedInputDeviceId: Int?,
    val source: String,
    val noiseSuppressorEnabled: Boolean,
    val automaticGainControlEnabled: Boolean,
    val acousticEchoCancelerEnabled: Boolean,
)

internal fun createWavHeader(
    dataBytes: Long,
    sampleRateHz: Int,
    bits: Int,
    channels: Int,
    floatingPoint: Boolean,
): ByteBuffer {
    require(dataBytes in 0..(0xffff_ffffL - 36L))
    require(sampleRateHz > 0 && bits in setOf(16, 24, 32) && channels in 1..2)
    val blockAlign = channels * bits / 8
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt((36L + dataBytes).toInt())
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort((if (floatingPoint) 3 else 1).toShort())
        putShort(channels.toShort())
        putInt(sampleRateHz)
        putInt(sampleRateHz * blockAlign)
        putShort(blockAlign.toShort())
        putShort(bits.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataBytes.toInt())
        flip()
    }
}
