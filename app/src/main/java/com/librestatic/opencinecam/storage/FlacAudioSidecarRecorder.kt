/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.PcmListeningSink
import com.librestatic.opencinecam.camera.offerListening

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
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
import com.librestatic.opencinecam.camera.AudioEffectsSnapshot
import com.librestatic.opencinecam.camera.AudioEffectState
import com.librestatic.opencinecam.camera.AudioEffectObservationReader
import com.librestatic.opencinecam.camera.requireExclusiveAgcObservation
import com.librestatic.opencinecam.audioEffectsJson
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.camera.createDisabledManualAgc
import com.librestatic.opencinecam.recordingGainJson
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
    override val captureClock: com.librestatic.opencinecam.camera.CaptureEpochClock?,
    private val onAudioLevel: ((AudioLevelSnapshot) -> Unit)?,
    private val recoveryMember: RecordingRecoveryMember,
    private val listeningSink: PcmListeningSink?,
) : AudioSidecarRecorder {
    private val running = AtomicBoolean(false)
    private val capturedSampleRateHz = audioRecord.sampleRate
    private val captureTiming = SidecarCaptureTiming(capturedSampleRateHz, settings.audioChannels * settings.audioBitDepth.bits / 8, captureClock)
    private var deferredFileCleanup = false
    @Volatile private var nativeRetired = false
    private val effects = mutableListOf<AudioEffect>()
    private var feederThread: Thread? = null
    private var drainThread: Thread? = null
    @Volatile private var failure: Throwable? = null
    @Volatile private var inputBytes = 0L
    @Volatile private var encodedBytes = 0L
    private var startedAtElapsedNs = 0L
    private var stoppedAtElapsedNs = 0L
    private var routedInputDeviceId: Int? = null
    private var effectReaders: Triple<AudioEffectObservationReader, AudioEffectObservationReader, AudioEffectObservationReader>? = null
    private var hardwareAgcReader: AudioEffectObservationReader? = null
    private var hasHardwareAgc = false
    private var lastEffects: AudioEffectsSnapshot? = null
    private var effectsObservedAtMs: Long? = null
    private var softAgc: SoftAgc? = null
    private var started = false
    private var codecStarted = false
    private val stopDeadline = AudioStopDeadline(STOP_TIMEOUT_MS - 500)
    @Volatile private var finished = false
    private var finishedResult: AudioSidecarRecordingResult? = null
    private var preparedOutput: PreparedAudioSidecar? = null
    private var preparedMetadataUri: Uri? = null
    private var aborted = false
    private var explicitDiscardRequested = false
    private val ownedRows = OwnedOutputRows<Uri> { row ->
        // A group sweep may already have removed the row. Collection+exact ID deletion remains
        // idempotent without asking MediaStore to grant access to a now-nonexistent item URI.
        val deleted = context.contentResolver.delete(ContentUris.removeId(row),
            "${MediaStore.MediaColumns._ID} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf(ContentUris.parseId(row).toString(), context.packageName))
        check(deleted in 0..1) { "Audio compensation affected an unexpected row count." }
    }.apply { add(uri) }
    private val levelMeter = AudioLevelMeter(settings.audioBitDepth.toMeterEncoding(), settings.audioChannels, sampleRateHz = capturedSampleRateHz)

    @Synchronized override fun start() {
        AudioRetirementGate.requireIdle()
        check(!finished && !started) { "FLAC recorder cannot be started twice." }
        started = true
        codec.start()
        codecStarted = true
        attachEffects()
        startedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
        audioRecord.startRecording()
        check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "AudioRecord did not enter RECORDING state."
        }
        routedInputDeviceId = audioRecord.routedDevice?.id
        running.set(true)
        drainThread = Thread(::drainLoop, "OpenCineCamFlacDrain").apply { start() }
        feederThread = Thread(::feedLoop, "OpenCineCamFlacFeeder").apply { start() }
    }

    /** Retire native owners and seal every byte while both MediaStore rows remain pending. */
    @Synchronized override fun prepareCompletion(): PreparedAudioSidecar? {
        if (aborted) return null
        preparedOutput?.let { return it }
        if (finished) return null
        finished = true
        return try {
            prepareOnce(true).also { prepared ->
                preparedOutput = prepared
                if (prepared == null) {
                    aborted = true
                    if (!deferredFileCleanup) cleanupOwnedOutputs()
                }
            }
        } catch (failure: Throwable) {
            abortAfterFailure(failure)
            throw failure
        }
    }

    /** Publish exactly the prepared identities; retries never allocate replacement rows. */
    @Synchronized override fun publishPrepared(): AudioSidecarRecordingResult? {
        if (aborted) return null
        finishedResult?.let { return it }
        val prepared = preparedOutput ?: return null
        return try {
            val resolver = context.contentResolver
            check(resolver.update(requireNotNull(preparedMetadataUri),
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
                "Audio metadata publication failed."
            }
            check(resolver.update(uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1) {
                "FLAC MediaStore publication failed."
            }
            recoveryMember.published()
            prepared.result.also { finishedResult = it }
        } catch (failure: Throwable) {
            abortAfterFailure(failure)
            throw failure
        }
    }

    @Synchronized override fun finish(success: Boolean): AudioSidecarRecordingResult? {
        // Preserve the established legacy result after successful publication. Explicit
        // compensation uses discard(); false still aborts an unpublished prepared output.
        finishedResult?.let { return it }
        if (!success) { discardOwned(explicit = false); return null }
        prepareCompletion() ?: return null
        return publishPrepared()
    }

    /** Explicit compensation also removes previously published audio and metadata rows. */
    @Synchronized override fun discard() { discardOwned(explicit = true) }

    private fun discardOwned(explicit: Boolean) {
        explicitDiscardRequested = explicitDiscardRequested || explicit
        aborted = true
        preparedOutput = null
        preparedMetadataUri = null
        finishedResult = null
        var failure: Throwable? = null
        if (!finished) {
            finished = true
            try { prepareOnce(false) } catch (problem: Throwable) { failure = problem }
        }
        if (!deferredFileCleanup) {
            try { cleanupOwnedOutputs() } catch (problem: Throwable) {
                val first = failure
                if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }

    private fun abortAfterFailure(failure: Throwable) {
        aborted = true
        preparedOutput = null
        preparedMetadataUri = null
        finishedResult = null
        if (!deferredFileCleanup) {
            try { cleanupOwnedOutputs() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
    }

    /** Only called after all native workers have relinquished the file. Failed rows retry. */
    private fun cleanupOwnedOutputs() {
        // Do not advertise retirement or compensate files until both close calls succeeded.
        closeOutput()
        check(nativeRetired) { "Audio native retirement has not been confirmed." }
        var failure: Throwable? = null
        // Explicit legacy discard may remove this member after a completed publication.
        // Automatic failures instead defer to the group: COMMITTED must remain intact.
        if (explicitDiscardRequested) {
            try { ownedRows.deleteAll() } catch (problem: Throwable) { failure = problem }
        }
        try { recoveryMember.abort() } catch (problem: Throwable) {
            val first = failure
            if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
        }
        failure?.let { throw it }
    }

    @Synchronized private fun cleanupAbandonedOutputs() {
        deferredFileCleanup = false
        cleanupOwnedOutputs()
    }

    private fun prepareOnce(success: Boolean): PreparedAudioSidecar? {
        running.set(false)
        stopDeadline.begin()
        val retirement = retireAudioWorkers(
            workers = listOfNotNull(feederThread, drainThread),
            timeoutMs = STOP_TIMEOUT_MS,
            stop = { if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop() },
            release = {
                releaseAudioResources(effects.map { effect -> { effect.release() } } +
                    listOf({ audioRecord.release() }, { if (codecStarted) codec.stop() }, { codec.release() }))
                effects.clear()
                nativeRetired = true
            },
            abandonedCleanup = ::cleanupAbandonedOutputs,
            onAbandonedFailure = { android.util.Log.e("AudioRetirement", "Deferred audio cleanup failed", it) },
        )
        deferredFileCleanup = !retirement.completed
        retirement.failure?.let { throw it }
        stoppedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
        val capturedResult = result()
        failure?.let { if (success) throw it }
        if (!success || failure != null || inputBytes == 0L || encodedBytes == 0L) return null
        captureTiming.requireComplete(capturedResult.frames)
        patchTotalSamples(inputBytes / (settings.audioChannels * settings.audioBitDepth.bits / 8))
        output.channel.force(true)
        output.close()
        descriptor.close()
        val audioName = storedDisplayName(uri)
        val completedResult = capturedResult.copy(displayName = audioName)
        val metadataUri = writeMetadata(completedResult)
        val metadataName = storedDisplayName(metadataUri)
        preparedMetadataUri = metadataUri
        return PreparedAudioSidecar(
            completedResult,
            java.util.Collections.unmodifiableList(listOf(
                PreparedCaptureArtifact(CaptureArtifactRole.AUDIO, uri.toString(), audioName),
                PreparedCaptureArtifact(CaptureArtifactRole.AUDIO_METADATA, metadataUri.toString(), metadataName),
            )),
        ).also { recoveryMember.prepared(it.artifacts) }
    }

    private fun storedDisplayName(row: Uri): String = requireNotNull(context.contentResolver.query(
        row, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
    )).use { cursor ->
        check(cursor.moveToFirst()) { "Prepared audio output row is missing." }
        requireNotNull(cursor.getString(0)).also { check(it.isNotBlank()) { "Prepared audio output name is missing." } }
    }

    private fun closeOutput() {
        var failure: Throwable? = null
        try { output.close() } catch (problem: Throwable) { failure = problem }
        try { descriptor.close() } catch (problem: Throwable) {
            val first = failure
            if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
        }
        failure?.let { throw it }
    }

    override fun close() {
        finish(false)
    }

    private fun feedLoop() {
        val frameBytes = settings.audioChannels * settings.audioBitDepth.bits / 8
        var submittedFrames = 0L
        fun sourcePtsUs() = com.librestatic.opencinecam.camera.pcmFrameDurationNs(submittedFrames, capturedSampleRateHz) / 1_000L
        val buffer = ByteBuffer.allocateDirect(4096 * frameBytes)
        try {
            while (running.get()) {
                buffer.clear()
                val read = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                if (read <= 0 && !running.get()) break
                if (read < 0) error("AudioRecord read failed with code $read.")
                if (read == 0) continue
                val observedAtMs = SystemClock.elapsedRealtime()
                val observedEffects = observeEffects(observedAtMs)
                levelMeter.observeInput(buffer, read)
                softAgc?.processPcm16(buffer, read)
                settings.audioRecordingGain.process(buffer, read, settings.audioBitDepth.toMeterEncoding(), settings.audioChannels)
                listeningSink.offerListening(buffer, read, settings.audioBitDepth.toMeterEncoding(), capturedSampleRateHz, settings.audioChannels)
                levelMeter.analyze(buffer, read, observedAtMs)?.let {
                    onAudioLevel?.invoke(it.copy(appliedRecordingGain = settings.audioRecordingGain, effects = observedEffects))
                }
                val retained = captureTiming.captured(audioRecord, buffer, read)
                buffer.position(0); buffer.limit(retained)
                while (buffer.hasRemaining()) {
                    if (!running.get()) stopDeadline.check()
                    val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (index < 0) continue
                    val input = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                    val count = minOf(buffer.remaining(), input.capacity() / frameBytes * frameBytes)
                    check(count > 0) { "FLAC input buffer cannot hold a PCM frame." }
                    val end = buffer.limit(); buffer.limit(buffer.position() + count)
                    input.put(buffer); buffer.limit(end)
                    codec.queueInputBuffer(index, 0, count, sourcePtsUs(), 0)
                    captureTiming.written(count); inputBytes += count; submittedFrames += count / frameBytes
                }
            }
        } catch (feedFailure: Throwable) {
            if (failure == null) failure = feedFailure
            running.set(false)
        } finally {
            stopDeadline.begin()
            runCatching {
                var eosIndex: Int
                do {
                    stopDeadline.check()
                    eosIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                } while (eosIndex < 0)
                codec.queueInputBuffer(eosIndex, 0, 0, sourcePtsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }.onFailure { if (failure == null) failure = it }
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var headerWritten = false
        try {
            while (true) {
                stopDeadline.check()
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
            if (failure == null) failure = drainFailure
            running.set(false)
            stopDeadline.begin()
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

    private fun attachEffects() {
        data class Configured(val effect: AudioEffect?, val available: Boolean?, val failed: Boolean,
            val reader: AudioEffectObservationReader)
        fun configure(requested: Boolean, available: () -> Boolean, create: () -> AudioEffect?): Configured {
            val advertised = runCatching(available).getOrNull()
            var failed = false
            val effect = try { create() } catch (_: Throwable) { failed = true; null }
            if (effect != null) {
                effects += effect // Native owner retained before set/get can fail.
                failed = runCatching { effect.setEnabled(requested) != AudioEffect.SUCCESS || effect.enabled != requested }.getOrDefault(true)
            }
            return Configured(effect, advertised, failed,
                AudioEffectObservationReader(requested, effect, advertised, failed))
        }
        val requestedAgc = settings.automaticGainControlEnabled
        val agc = if (settings.audioRecordingGain.enabled) {
            val effect = createDisabledManualAgc(audioRecord.audioSessionId) { effects += it }
            val available = runCatching { AutomaticGainControl.isAvailable() }.getOrNull()
            Configured(effect, available, false, AudioEffectObservationReader(requestedAgc, effect, available))
        } else configure(requestedAgc, AutomaticGainControl::isAvailable) { AutomaticGainControl.create(audioRecord.audioSessionId) }
        hardwareAgcReader = agc.reader
        hasHardwareAgc = agc.effect != null
        // Float never runs SoftAgc: do not advertise a processor the writer does not execute.
        if (!settings.audioRecordingGain.enabled && requestedAgc && settings.audioBitDepth != AudioBitDepth.PCM_FLOAT &&
            (agc.effect == null || agc.reader.read().state == AudioEffectState.DISABLED)) {
            softAgc = SoftAgc(settings.audioSampleRateHz, settings.audioChannels)
        }
        effectReaders = Triple(
            configure(settings.noiseSuppressorEnabled, NoiseSuppressor::isAvailable) { NoiseSuppressor.create(audioRecord.audioSessionId) }.reader,
            if (softAgc == null) agc.reader else AudioEffectObservationReader(requestedAgc, agc.effect, agc.available, agc.failed, softwareEnabled = true),
            configure(settings.acousticEchoCancelerEnabled, AcousticEchoCanceler::isAvailable) { AcousticEchoCanceler.create(audioRecord.audioSessionId) }.reader,
        )
    }

    private fun observeEffects(atMs: Long): AudioEffectsSnapshot {
        val readers = requireNotNull(effectReaders)
        val hardware = requireNotNull(hardwareAgcReader).read()
        requireExclusiveAgcObservation(hardware, hasHardwareAgc, settings.audioRecordingGain.enabled, softAgc != null)
        val appliedAgc = if (softAgc == null) hardware else readers.second.read().also {
            check(it.state == AudioEffectState.ENABLED) { "Software AGC no longer has an exclusive observed path" }
        }
        return AudioEffectsSnapshot(readers.first.read(), appliedAgc, readers.third.read()).also {
            lastEffects = it
            effectsObservedAtMs = atMs
        }
    }

    private fun result() = AudioSidecarRecordingResult(
        container = "FLAC",
        uri = uri,
        displayName = displayName,
        sampleRateHz = capturedSampleRateHz,
        bitDepth = settings.audioBitDepth,
        channels = settings.audioChannels,
        derivedBitrateKbps = if (stoppedAtElapsedNs > startedAtElapsedNs) {
            (encodedBytes * 8_000_000L / (stoppedAtElapsedNs - startedAtElapsedNs)).toInt()
        } else 0,
        frames = inputBytes / (settings.audioChannels * settings.audioBitDepth.bits / 8),
        dataBytes = encodedBytes,
        startedAtElapsedNs = startedAtElapsedNs,
        stoppedAtElapsedNs = stoppedAtElapsedNs,
        captureTiming = captureTiming.report(),
        preferredInputDeviceId = preferredInput?.id,
        routedInputDeviceId = routedInputDeviceId,
        source = settings.audioSource.name,
        noiseSuppressorEnabled = lastEffects?.noiseSuppressor?.state == AudioEffectState.ENABLED,
        automaticGainControlEnabled = lastEffects?.automaticGainControl?.state == AudioEffectState.ENABLED,
        acousticEchoCancelerEnabled = lastEffects?.acousticEchoCanceler?.state == AudioEffectState.ENABLED,
        recordingGain = settings.audioRecordingGain,
        audioEffects = lastEffects,
        audioEffectsObservedAtElapsedRealtimeMs = effectsObservedAtMs,
    )

    private fun writeMetadata(result: AudioSidecarRecordingResult): Uri {
        val json = JSONObject()
            .put("schema", "opencinecam-audio-sidecar-v1")
            .put("productionSlate", JSONObject(com.librestatic.opencinecam.productionSlateJson(settings.productionSlate).toString()))
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
            .put("captureTiming", result.captureTiming?.let(::pcmSourceTimingJson) ?: JSONObject.NULL)
            .put("sharedTiming", captureClock?.report(null, null, null)?.copy(submittedPcmFrames = result.frames, audioStorage = "SEPARATE_FLAC")?.let { com.librestatic.opencinecam.captureEpochJson(it) } ?: JSONObject.NULL)
            .put("source", result.source)
            .put("preferredInputDeviceId", result.preferredInputDeviceId ?: JSONObject.NULL)
            .put("routedInputDeviceId", result.routedInputDeviceId ?: JSONObject.NULL)
            .put("noiseSuppressorEnabled", result.noiseSuppressorEnabled)
            .put("automaticGainControlEnabled", result.automaticGainControlEnabled)
            .put("recordingGain", recordingGainJson(result.recordingGain))
            .put("audioEffects", result.audioEffects?.let { audioEffectsJson(it, requireNotNull(result.audioEffectsObservedAtElapsedRealtimeMs)) } ?: JSONObject.NULL)
            .put("acousticEchoCancelerEnabled", result.acousticEchoCancelerEnabled)
            .put("disclosure", "FLAC encoded with Android MediaCodec; OEM processing outside public APIs may still exist.")
        val metadataName = displayName.removeSuffix(".flac") + ".audio.json"
        val resolver = context.contentResolver
        val metadataUri = recoveryMember.insert(metadataName, "application/json", metadata = true)
        ownedRows.add(metadataUri)
        requireNotNull(resolver.openOutputStream(metadataUri, "w")).use { it.write(json.toString(2).toByteArray()) }
        return metadataUri
    }


    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val FLAC_STREAMINFO_BYTES = 34
        private const val STREAMINFO_SAMPLE_FIELD_OFFSET = 18L
        private const val TOTAL_SAMPLES_UPPER_MASK = -1L shl 36

        /** Preserve existing trailing-lambda callers while the full factory adds group ownership. */
        fun create(
            context: Context,
            videoDisplayName: String,
            settings: CameraSettings,
            captureClock: com.librestatic.opencinecam.camera.CaptureEpochClock? = null,
            onAudioLevel: (AudioLevelSnapshot) -> Unit,
        ): FlacAudioSidecarRecorder = create(context, videoDisplayName, settings,
            captureClock, onAudioLevel, recoveryGroup = null)

        @SuppressLint("MissingPermission")
        fun create(
            context: Context,
            videoDisplayName: String,
            settings: CameraSettings,
            captureClock: com.librestatic.opencinecam.camera.CaptureEpochClock? = null,
            onAudioLevel: ((AudioLevelSnapshot) -> Unit)? = null,
            recoveryGroup: RecordingRecoveryGroup? = null,
            listeningSink: PcmListeningSink? = null,
        ): FlacAudioSidecarRecorder {
            AudioRetirementGate.requireIdle()
            val appContext = context.applicationContext
            check(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "RECORD_AUDIO permission is required for FLAC."
            }
            require(settings.audioBitDepth == AudioBitDepth.PCM_16) {
                "The Android FLAC writer is qualified only for 16-bit PCM input."
            }
            val displayName = videoDisplayName.removeSuffix(".mp4") + ".flac"
            val resolver = appContext.contentResolver
            val recoveryMember = (recoveryGroup ?: RecordingCaptureRecovery.begin(appContext))
                .member(RecordingMemberKind.AUDIO)
            var record: AudioRecord? = null
            var codec: MediaCodec? = null
            var descriptor: ParcelFileDescriptor? = null
            var output: FileOutputStream? = null
            try {
                val uri = recoveryMember.insert(displayName, MediaFormat.MIMETYPE_AUDIO_FLAC, metadata = false)
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
                    .setBufferSizeInBytes(frameAlignedBufferBytes(minimum, settings.audioChannels * settings.audioBitDepth.bits / 8))
                    .build()
                check(record.state == AudioRecord.STATE_INITIALIZED) { "FLAC AudioRecord initialization failed." }
                check(record.sampleRate == settings.audioSampleRateHz && record.channelCount == settings.audioChannels &&
                    record.audioFormat == settings.audioBitDepth.androidEncoding) { "AudioRecord actual PCM format differs from requested lossless format." }
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
                val stream = FileOutputStream(descriptor.fileDescriptor).also { output = it }
                return FlacAudioSidecarRecorder(
                    appContext,
                    uri,
                    displayName,
                    settings,
                    record,
                    codec,
                    descriptor,
                    stream,
                    preferred,
                    captureClock,
                    onAudioLevel,
                    recoveryMember,
                    listeningSink,
                )
            } catch (creationFailure: Throwable) {
                var retired = true
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (problem: Throwable) {
                        retired = false
                        if (problem !== creationFailure) creationFailure.addSuppressed(problem)
                    }
                }
                cleanup { record?.release() }
                cleanup { codec?.release() }
                cleanup { output?.close() }
                cleanup { descriptor?.close() }
                // The group retains ownership when native/descriptor cleanup cannot be confirmed.
                if (retired) cleanup { recoveryMember.abort() }
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
