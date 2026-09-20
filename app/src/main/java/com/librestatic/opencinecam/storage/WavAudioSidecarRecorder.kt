/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.PcmListeningSink
import com.librestatic.opencinecam.camera.offerListening

import android.annotation.SuppressLint
import android.content.ContentUris
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
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.camera.createDisabledManualAgc
import com.librestatic.opencinecam.recordingGainJson
import com.librestatic.opencinecam.camera.SoftAgc
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.camera.AudioLevelMeter
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.AudioEffectsSnapshot
import com.librestatic.opencinecam.camera.AudioEffectState
import com.librestatic.opencinecam.camera.AudioEffectObservationReader
import com.librestatic.opencinecam.camera.requireExclusiveAgcObservation
import com.librestatic.opencinecam.audioEffectsJson
import com.librestatic.opencinecam.camera.PcmMeterEncoding
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Lossless audio recorder paired with a video by basename. JSON distinguishes command receipts
 * from a measured PCM frame-zero epoch; a missing source timestamp is explicitly unavailable.
 * Video alignment is not implied by matching filenames or start receipts. WAV/RF64 files larger than 4 GiB are deliberately rejected rather than corrupted.
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
    private var writerThread: Thread? = null
    private var startAttempted = false
    @Volatile private var writerFailure: Throwable? = null
    private var dataBytes = 0L
    private var startedAtElapsedNs = 0L
    private var stoppedAtElapsedNs = 0L
    private var routedInputDeviceId: Int? = null
    private var effectReaders: Triple<AudioEffectObservationReader, AudioEffectObservationReader, AudioEffectObservationReader>? = null
    private var hardwareAgcReader: AudioEffectObservationReader? = null
    private var hasHardwareAgc = false
    private var lastEffects: AudioEffectsSnapshot? = null
    private var effectsObservedAtMs: Long? = null
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
    private var softAgc: SoftAgc? = null
    private val levelMeter = AudioLevelMeter(settings.audioBitDepth.toMeterEncoding(), settings.audioChannels, sampleRateHz = capturedSampleRateHz)

    @Synchronized override fun start() {
        AudioRetirementGate.requireIdle()
        check(!finished && !startAttempted) { "WAV recorder cannot be started twice." }
        startAttempted = true
        output.channel.position(0)
        output.channel.write(createWavHeader(0, settings.audioSampleRateHz, settings.audioBitDepth.bits, settings.audioChannels, settings.audioBitDepth == AudioBitDepth.PCM_FLOAT))
        attachEffects()
        startedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
        audioRecord.startRecording()
        check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not enter RECORDING state." }
        routedInputDeviceId = audioRecord.routedDevice?.id
        running.set(true)
        writerThread = Thread(::writeLoop, "OpenCineCamWavWriter").apply { start() }
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
                "WAV MediaStore publication failed."
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
        val retirement = retireAudioWorkers(
            workers = listOfNotNull(writerThread),
            timeoutMs = 3_000,
            stop = { if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop() },
            release = {
                releaseAudioResources(effects.map { effect -> { effect.release() } } +
                    listOf({ audioRecord.release() }))
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
        writerFailure?.let { if (success) throw it }
        if (!success || writerFailure != null || dataBytes == 0L) return null
        captureTiming.requireComplete(capturedResult.frames)
        output.channel.position(0)
        val header = createWavHeader(dataBytes, settings.audioSampleRateHz, settings.audioBitDepth.bits, settings.audioChannels, settings.audioBitDepth == AudioBitDepth.PCM_FLOAT)
        while (header.hasRemaining()) output.channel.write(header)
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

    private fun writeLoop() {
        val frameBytes = settings.audioChannels * settings.audioBitDepth.bits / 8
        val minimum = AudioRecord.getMinBufferSize(
            settings.audioSampleRateHz,
            channelMask(settings.audioChannels),
            settings.audioBitDepth.androidEncoding,
        )
        val bufferSize = (minimum * 2).coerceAtLeast(frameBytes * 4_096) / frameBytes * frameBytes
        val buffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (running.get()) {
                buffer.clear()
                val read = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                if (read <= 0 && !running.get()) break
                if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    error("AudioRecord read failed with code $read")
                }
                if (read <= 0) continue
                val observedAtMs = SystemClock.elapsedRealtime()
                val observedEffects = observeEffects(observedAtMs)
                levelMeter.observeInput(buffer, read)
                when (settings.audioBitDepth) {
                    AudioBitDepth.PCM_16 -> softAgc?.processPcm16(buffer, read)
                    AudioBitDepth.PCM_24 -> softAgc?.processPcm24(buffer, read)
                    AudioBitDepth.PCM_FLOAT -> Unit // SoftAgc does not support float PCM yet
                }
                settings.audioRecordingGain.process(buffer, read, settings.audioBitDepth.toMeterEncoding(), settings.audioChannels)
                listeningSink.offerListening(buffer, read, settings.audioBitDepth.toMeterEncoding(), capturedSampleRateHz, settings.audioChannels)
                levelMeter.analyze(buffer, read, observedAtMs)?.let {
                    onAudioLevel?.invoke(it.copy(appliedRecordingGain = settings.audioRecordingGain, effects = observedEffects))
                }
                val retained = captureTiming.captured(audioRecord, buffer, read)
                check(dataBytes + retained <= MAX_WAV_DATA_BYTES) { "WAV reached the 4 GiB RIFF limit." }
                buffer.position(0)
                buffer.limit(retained)
                while (buffer.hasRemaining()) output.channel.write(buffer)
                captureTiming.written(retained)
                dataBytes += retained
            }
        } catch (failure: Throwable) {
            writerFailure = failure
            running.set(false)
        }
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
        container = "WAV",
        uri = uri,
        displayName = displayName,
        sampleRateHz = capturedSampleRateHz,
        bitDepth = settings.audioBitDepth,
        channels = settings.audioChannels,
        derivedBitrateKbps = settings.audioSampleRateHz * settings.audioBitDepth.bits * settings.audioChannels / 1_000,
        frames = dataBytes / (settings.audioChannels * settings.audioBitDepth.bits / 8),
        dataBytes = dataBytes,
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
            .put("container", "WAV")
            .put("encoding", result.bitDepth.name)
            .put("sampleRateHz", result.sampleRateHz)
            .put("channels", result.channels)
            .put("derivedBitrateKbps", result.derivedBitrateKbps)
            .put("frames", result.frames)
            .put("dataBytes", result.dataBytes)
            .put("startedAtElapsedRealtimeNs", result.startedAtElapsedNs)
            .put("stoppedAtElapsedRealtimeNs", result.stoppedAtElapsedNs)
            .put("captureTiming", result.captureTiming?.let(::pcmSourceTimingJson) ?: JSONObject.NULL)
            .put("sharedTiming", captureClock?.report(null, null, null)?.copy(submittedPcmFrames = result.frames, audioStorage = "SEPARATE_WAV")?.let { com.librestatic.opencinecam.captureEpochJson(it) } ?: JSONObject.NULL)
            .put("source", result.source)
            .put("preferredInputDeviceId", result.preferredInputDeviceId ?: JSONObject.NULL)
            .put("routedInputDeviceId", result.routedInputDeviceId ?: JSONObject.NULL)
            .put("noiseSuppressorEnabled", result.noiseSuppressorEnabled)
            .put("automaticGainControlEnabled", result.automaticGainControlEnabled)
            .put("recordingGain", recordingGainJson(result.recordingGain))
            .put("audioEffects", result.audioEffects?.let { audioEffectsJson(it, requireNotNull(result.audioEffectsObservedAtElapsedRealtimeMs)) } ?: JSONObject.NULL)
            .put("acousticEchoCancelerEnabled", result.acousticEchoCancelerEnabled)
            .put("disclosure", "Public AudioRecord state; OEM signal processing outside public APIs may still exist.")
        val metadataName = displayName.removeSuffix(".wav") + ".audio.json"
        val resolver = context.contentResolver
        val metadataUri = recoveryMember.insert(metadataName, "application/json", metadata = true)
        ownedRows.add(metadataUri)
        requireNotNull(resolver.openOutputStream(metadataUri, "w")).use { it.write(json.toString(2).toByteArray()) }
        return metadataUri
    }

    companion object {
        private const val MAX_WAV_DATA_BYTES = 0xffff_ffffL - 36L

        /** Preserve existing trailing-lambda callers while the full factory adds group ownership. */
        fun create(
            context: Context,
            videoDisplayName: String,
            settings: CameraSettings,
            captureClock: com.librestatic.opencinecam.camera.CaptureEpochClock? = null,
            onAudioLevel: (AudioLevelSnapshot) -> Unit,
        ): WavAudioSidecarRecorder = create(context, videoDisplayName, settings,
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
        ): WavAudioSidecarRecorder {
            AudioRetirementGate.requireIdle()
            val appContext = context.applicationContext
            val displayName = videoDisplayName.removeSuffix(".mp4") + ".wav"
            val resolver = appContext.contentResolver
            val recoveryMember = (recoveryGroup ?: RecordingCaptureRecovery.begin(appContext))
                .member(RecordingMemberKind.AUDIO)
            var record: AudioRecord? = null
            var descriptor: ParcelFileDescriptor? = null
            var output: FileOutputStream? = null
            try {
                val uri = recoveryMember.insert(displayName, "audio/wav", metadata = false)
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
                check(record.sampleRate == settings.audioSampleRateHz && record.channelCount == settings.audioChannels &&
                    record.audioFormat == settings.audioBitDepth.androidEncoding) { "AudioRecord actual PCM format differs from requested lossless format." }
                val preferred = settings.audioInputDeviceId?.let { requestedId ->
                    appContext.getSystemService(AudioManager::class.java)
                        .getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.id == requestedId }
                        ?: error("Requested audio input $requestedId is no longer connected.")
                }
                if (preferred != null) check(record.setPreferredDevice(preferred)) { "AudioRecord rejected audio input ${preferred.id}." }
                descriptor = requireNotNull(resolver.openFileDescriptor(uri, "rw"))
                val stream = FileOutputStream(descriptor.fileDescriptor).also { output = it }
                return WavAudioSidecarRecorder(
                    appContext,
                    uri,
                    displayName,
                    settings,
                    record,
                    descriptor,
                    stream,
                    preferred,
                    captureClock,
                    onAudioLevel,
                    recoveryMember,
                    listeningSink,
                )
            } catch (failure: Throwable) {
                var retired = true
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (problem: Throwable) {
                        retired = false
                        if (problem !== failure) failure.addSuppressed(problem)
                    }
                }
                cleanup { record?.release() }
                cleanup { output?.close() }
                cleanup { descriptor?.close() }
                // The group retains ownership when native/descriptor cleanup cannot be confirmed.
                if (retired) cleanup { recoveryMember.abort() }
                throw failure
            }
        }

        private fun channelMask(channels: Int): Int = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    }
}

interface AudioSidecarRecorder : AutoCloseable {
    val displayName: String
    val captureClock: com.librestatic.opencinecam.camera.CaptureEpochClock? get() = null
    fun start()
    /** Seal native output and metadata, returning stable identities that are still pending. */
    fun prepareCompletion(): PreparedAudioSidecar?
    /** Publish only a previously prepared output; return null after abort or before prepare. */
    fun publishPrepared(): AudioSidecarRecordingResult?
    fun finish(success: Boolean): AudioSidecarRecordingResult?
    fun discard()
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
    val captureTiming: com.librestatic.opencinecam.camera.PcmSourceTimingReport? = null,
    val recordingGain: DigitalRecordingGain = DigitalRecordingGain(),
    val audioEffects: AudioEffectsSnapshot? = null,
    val audioEffectsObservedAtElapsedRealtimeMs: Long? = null,
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
