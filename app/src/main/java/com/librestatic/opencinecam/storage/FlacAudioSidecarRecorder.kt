/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.camera.AudioLevelMeter
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.SoftAgc
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Real-time, capability-gated FLAC sidecar encoded with Android's public MediaCodec API. */
class FlacAudioSidecarRecorder private constructor(
    private val context: Context,
    private val uri: Uri,
    override val displayName: String,
    private val settings: CameraSettings,
    private val audioRecord: AudioRecord,
    private val codec: MediaCodec,
    private val descriptor: ParcelFileDescriptor,
    private val output: FileOutputStream,
    private val preferredInput: AudioDeviceInfo?,
    private val onAudioLevel: ((AudioLevelSnapshot) -> Unit)?,
) : AudioSidecarRecorder {
    private val running = AtomicBoolean(false)
    private val effects = mutableListOf<AudioEffect>()
    private var feederThread: Thread? = null
    private var drainThread: Thread? = null
    @Volatile private var failure: Throwable? = null
    @Volatile private var inputBytes = 0L
    @Volatile private var encodedBytes = 0L
    private var startedAtElapsedNs = 0L
    private var stoppedAtElapsedNs = 0L
    private var routedInputDeviceId: Int? = null
    private var effectState = EffectState(false, false, false)
    private var softAgc: SoftAgc? = null
    private var started = false
    private var finished = false
    private val levelMeter = AudioLevelMeter(settings.audioBitDepth.toMeterEncoding(), settings.audioChannels)

    override fun start() {
        check(!finished && !started) { "FLAC recorder cannot be started twice." }
        codec.start()
        effectState = attachEffects()
        startedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
        audioRecord.startRecording()
        check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "AudioRecord did not enter RECORDING state."
        }
        routedInputDeviceId = audioRecord.routedDevice?.id
        started = true
        running.set(true)
        drainThread = Thread(::drainLoop, "OpenCineCamFlacDrain").apply { start() }
        feederThread = Thread(::feedLoop, "OpenCineCamFlacFeeder").apply { start() }
    }

    override fun finish(success: Boolean): AudioSidecarRecordingResult? {
        if (finished) return null
        finished = true
        running.set(false)
        if (started) runCatching { audioRecord.stop() }
        feederThread?.join(STOP_TIMEOUT_MS)
        drainThread?.join(STOP_TIMEOUT_MS)
        if (feederThread?.isAlive == true || drainThread?.isAlive == true) {
            failure = IllegalStateException("FLAC encoder did not stop within ${STOP_TIMEOUT_MS} ms.")
            feederThread?.interrupt()
            drainThread?.interrupt()
        }
        stoppedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        runCatching { audioRecord.release() }
        if (started) runCatching { codec.stop() }
        runCatching { codec.release() }

        val recorderFailure = failure
        return try {
            if (!success || recorderFailure != null || inputBytes == 0L || encodedBytes == 0L) {
                context.contentResolver.delete(uri, null, null)
                if (success && recorderFailure != null) throw recorderFailure as Throwable
                null
            } else {
                patchTotalSamples(inputBytes / (settings.audioChannels * settings.audioBitDepth.bits / 8))
                output.channel.force(true)
                output.close()
                val result = result()
                var metadataUri: Uri? = null
                try {
                    metadataUri = writeMetadata(result)
                    check(context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                        null,
                        null,
                    ) == 1) { "FLAC MediaStore publication failed." }
                    result
                } catch (publicationFailure: Throwable) {
                    context.contentResolver.delete(uri, null, null)
                    metadataUri?.let { context.contentResolver.delete(it, null, null) }
                    throw publicationFailure
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

    private fun feedLoop() {
        val frameBytes = settings.audioChannels * settings.audioBitDepth.bits / 8
        var submittedFrames = 0L
        val anchorUs = System.nanoTime() / 1_000L
        try {
            while (running.get()) {
                val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index < 0) continue
                val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                val read = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                if (read == AudioRecord.ERROR_DEAD_OBJECT ||
                    read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE
                ) error("AudioRecord read failed with code $read.")
                if (read <= 0) {
                    codec.queueInputBuffer(index, 0, 0, anchorUs + submittedFrames * 1_000_000L / settings.audioSampleRateHz, 0)
                    continue
                }
                levelMeter.analyze(buffer, read, SystemClock.elapsedRealtime())?.let { onAudioLevel?.invoke(it) }
                softAgc?.processPcm16(buffer, read)
                codec.queueInputBuffer(
                    index,
                    0,
                    read,
                    anchorUs + submittedFrames * 1_000_000L / settings.audioSampleRateHz,
                    0,
                )
                inputBytes += read
                submittedFrames += read / frameBytes
            }
        } catch (feedFailure: Throwable) {
            if (!finished) failure = feedFailure
        } finally {
            runCatching {
                var eosIndex: Int
                do {
                    eosIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                } while (eosIndex < 0)
                codec.queueInputBuffer(
                    eosIndex,
                    0,
                    0,
                    anchorUs + submittedFrames * 1_000_000L / settings.audioSampleRateHz,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }.onFailure { if (failure == null) failure = it }
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var headerWritten = false
        try {
            while (true) {
                when (val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        codec.outputFormat.getByteBuffer("csd-0")?.let { csd ->
                            val bytes = csd.toByteArray()
                            writeFlacHeader(bytes)
                            headerWritten = true
                        }
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (codecConfig && !headerWritten) {
                                writeFlacHeader(buffer.toByteArray())
                                headerWritten = true
                            } else if (!codecConfig) {
                                while (buffer.hasRemaining()) encodedBytes += output.channel.write(buffer)
                            }
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (eos) break
                    }
                }
            }
        } catch (drainFailure: Throwable) {
            failure = drainFailure
            running.set(false)
        }
    }

    private fun writeFlacHeader(bytes: ByteArray) {
        val marker = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
        when {
            bytes.startsWith(marker) -> output.write(bytes)
            bytes.size == FLAC_STREAMINFO_BYTES -> {
                output.write(marker)
                output.write(byteArrayOf(0x80.toByte(), 0, 0, FLAC_STREAMINFO_BYTES.toByte()))
                output.write(bytes)
            }
            bytes.size == FLAC_STREAMINFO_BYTES + 4 -> {
                output.write(marker)
                output.write(bytes)
            }
            else -> error("Unexpected FLAC codec header size ${bytes.size}.")
        }
        encodedBytes += bytes.size + when {
            bytes.startsWith(marker) -> 0
            bytes.size == FLAC_STREAMINFO_BYTES -> 8
            else -> 4
        }
    }

    /** Updates STREAMINFO's 36-bit total-samples field so galleries can report duration. */
    private fun patchTotalSamples(frames: Long) {
        require(frames in 1 until (1L shl 36)) { "FLAC sample count is outside STREAMINFO range." }
        val packed = ByteArray(8)
        check(Os.pread(descriptor.fileDescriptor, packed, 0, packed.size, STREAMINFO_SAMPLE_FIELD_OFFSET) == packed.size)
        var value = 0L
        packed.forEach { value = (value shl 8) or (it.toLong() and 0xffL) }
        value = (value and TOTAL_SAMPLES_UPPER_MASK) or frames
        for (index in packed.indices.reversed()) {
            packed[index] = (value and 0xffL).toByte()
            value = value ushr 8
        }
        check(Os.pwrite(descriptor.fileDescriptor, packed, 0, packed.size, STREAMINFO_SAMPLE_FIELD_OFFSET) == packed.size)
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
        container = "FLAC",
        uri = uri,
        displayName = displayName,
        sampleRateHz = audioRecord.sampleRate,
        bitDepth = settings.audioBitDepth,
        channels = settings.audioChannels,
        derivedBitrateKbps = if (stoppedAtElapsedNs > startedAtElapsedNs) {
            (encodedBytes * 8_000_000L / (stoppedAtElapsedNs - startedAtElapsedNs)).toInt()
        } else 0,
        frames = inputBytes / (settings.audioChannels * settings.audioBitDepth.bits / 8),
        dataBytes = encodedBytes,
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
            .put("container", result.container)
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
            .put("disclosure", "FLAC encoded with Android MediaCodec; OEM processing outside public APIs may still exist.")
        val metadataName = displayName.removeSuffix(".flac") + ".audio.json"
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
                "FLAC metadata publication failed."
            }
            return metadataUri
        } catch (metadataFailure: Throwable) {
            resolver.delete(metadataUri, null, null)
            throw metadataFailure
        }
    }

    private data class EffectState(val noiseSuppressor: Boolean, val automaticGainControl: Boolean, val acousticEchoCanceler: Boolean)

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val FLAC_STREAMINFO_BYTES = 34
        private const val STREAMINFO_SAMPLE_FIELD_OFFSET = 18L
        private const val TOTAL_SAMPLES_UPPER_MASK = -1L shl 36

        @SuppressLint("MissingPermission")
        fun create(
            context: Context,
            videoDisplayName: String,
            settings: CameraSettings,
            onAudioLevel: ((AudioLevelSnapshot) -> Unit)? = null,
        ): FlacAudioSidecarRecorder {
            val appContext = context.applicationContext
            check(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "RECORD_AUDIO permission is required for FLAC."
            }
            require(settings.audioBitDepth == AudioBitDepth.PCM_16) {
                "The Android FLAC writer is qualified only for 16-bit PCM input."
            }
            val displayName = videoDisplayName.removeSuffix(".mp4") + ".flac"
            val resolver = appContext.contentResolver
            val uri = requireNotNull(resolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, MediaFormat.MIMETYPE_AUDIO_FLAC)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/OpenCineCam")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                },
            ))
            var record: AudioRecord? = null
            var codec: MediaCodec? = null
            var descriptor: ParcelFileDescriptor? = null
            try {
                val channelMask = if (settings.audioChannels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val minimum = AudioRecord.getMinBufferSize(settings.audioSampleRateHz, channelMask, settings.audioBitDepth.androidEncoding)
                require(minimum > 0) { "Unsupported FLAC PCM input configuration." }
                record = AudioRecord.Builder()
                    .setAudioSource(settings.audioSource.androidSource)
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(settings.audioSampleRateHz)
                        .setEncoding(settings.audioBitDepth.androidEncoding)
                        .setChannelMask(channelMask)
                        .build())
                    .setBufferSizeInBytes((minimum * 2).coerceAtLeast(16_384))
                    .build()
                check(record.state == AudioRecord.STATE_INITIALIZED) { "FLAC AudioRecord initialization failed." }
                val preferred = settings.audioInputDeviceId?.let { requestedId ->
                    appContext.getSystemService(AudioManager::class.java)
                        .getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.id == requestedId }
                        ?: error("Requested audio input $requestedId is no longer connected.")
                }
                if (preferred != null) check(record.setPreferredDevice(preferred)) { "AudioRecord rejected audio input ${preferred.id}." }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
                codec.configure(
                    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, settings.audioSampleRateHz, settings.audioChannels).apply {
                        setInteger(MediaFormat.KEY_PCM_ENCODING, settings.audioBitDepth.androidEncoding)
                        setInteger(MediaFormat.KEY_COMPLEXITY, 5)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minimum * 2)
                    },
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                descriptor = requireNotNull(resolver.openFileDescriptor(uri, "rw"))
                return FlacAudioSidecarRecorder(
                    appContext,
                    uri,
                    displayName,
                    settings,
                    record,
                    codec,
                    descriptor,
                    FileOutputStream(descriptor.fileDescriptor),
                    preferred,
                    onAudioLevel,
                )
            } catch (creationFailure: Throwable) {
                runCatching { record?.release() }
                runCatching { codec?.release() }
                runCatching { descriptor?.close() }
                resolver.delete(uri, null, null)
                throw creationFailure
            }
        }

        private fun ByteBuffer.toByteArray(): ByteArray {
            val duplicate = duplicate()
            val bytes = ByteArray(duplicate.remaining())
            duplicate.get(bytes)
            return bytes
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}
