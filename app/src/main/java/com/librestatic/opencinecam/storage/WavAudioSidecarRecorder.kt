/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.settings.appliedAudioEqCurve
import com.librestatic.opencinecam.settings.appliedAudioEqProfileId
import com.librestatic.opencinecam.camera.PcmListeningSink
import com.librestatic.opencinecam.camera.offerListening

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import com.librestatic.opencinecam.camera.PcmInputEvidence
import com.librestatic.opencinecam.camera.inputEqualizer
import com.librestatic.opencinecam.camera.pcmInputEvidence
import com.librestatic.opencinecam.media.audio.PcmFormatCheck
import com.librestatic.opencinecam.media.audio.PcmInput
import com.librestatic.opencinecam.media.audio.PcmInputRequest
import com.librestatic.opencinecam.media.audio.PcmInputs
import com.librestatic.opencinecam.media.audio.audioRouting
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.camera.createDisabledManualAgc
import com.librestatic.opencinecam.metadata.recordingGainJson
import com.librestatic.opencinecam.camera.SoftAgc
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.camera.AudioLevelMeter
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.AudioEffectsSnapshot
import com.librestatic.opencinecam.camera.AudioEffectState
import com.librestatic.opencinecam.camera.AudioEffectObservationReader
import com.librestatic.opencinecam.camera.requireExclusiveAgcObservation
import com.librestatic.opencinecam.metadata.audioEffectsJson
import com.librestatic.opencinecam.camera.PcmMeterEncoding
import java.io.FileOutputStream
import com.librestatic.opencinecam.media.audio.AudioCapturePath
import com.librestatic.opencinecam.media.audio.AudioInputRouteGuard
import com.librestatic.opencinecam.media.audio.inputKey
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
    private val input: PcmInput,
    private val inputEvidence: PcmInputEvidence,
    private val descriptor: ParcelFileDescriptor,
    private val output: FileOutputStream,
    private val preferredInput: AudioDeviceInfo?,
    override val captureClock: com.librestatic.opencinecam.camera.CaptureEpochClock?,
    private val onAudioLevel: ((AudioLevelSnapshot) -> Unit)?,
    private val recoveryMember: RecordingRecoveryMember,
    private val listeningSink: PcmListeningSink?,
    private val inputGuard: AudioInputRouteGuard?,
) : AudioSidecarRecorder {
    override val inputFallbackReason: String? get() = inputEvidence.inputFallbackReason
    private val running = AtomicBoolean(false)
    // ADR-0037: on a drive the descriptor is registered, so a leaving drive closes it at once (vold kills a holder);
    // the writer thread then fails its next write and stops, as on ENODEV.
    private val fileCloser = CloseOnce(output, descriptor)
    private val driveHandle = DriveDescriptors.registerDocument(uri, fileCloser)
    private val capturedSampleRateHz = input.sampleRateHz
    private val captureTiming = SidecarCaptureTiming(capturedSampleRateHz, settings.audioChannels * settings.audioBitDepth.bits / 8, captureClock)
    override fun liveCaptureTiming() = runCatching { captureTiming.report() }.getOrNull()
    override val writerFailed: Boolean get() = writerFailure != null
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
        // Idempotent on the take's backend: a group sweep may already have removed the row.
        recoveryMember.deleteOwned(row, "Audio compensation affected an unexpected row count.")
    }.apply { add(uri) }
    private var softAgc: SoftAgc? = null
    private val levelMeter = AudioLevelMeter(settings.audioBitDepth.toMeterEncoding(), settings.audioChannels, sampleRateHz = capturedSampleRateHz)
    // Only the writer thread runs it; null when the curve applied on the opened path is flat, so the bytes stay untouched.
    private val equalizer = inputEvidence.inputEqualizer(capturedSampleRateHz, settings.audioChannels)

    private var salvaging = false
    private var incompleteReasons: List<String>? = null
    // A write that blocks past the input's short buffer makes it overrun; later reads succeed, so only the count shows the gap.
    private var overrunBaseline = 0
    @Volatile private var inputOverruns = 0

    /** After each read, and once more when the input stops: an overrun after the last read shows only then, and a closed input counts none. */
    private fun sampleInputOverruns() = synchronized(overrunLock) {
        inputOverruns = maxOf(inputOverruns, input.overrunCount - overrunBaseline)
    }
    private val overrunLock = Any()

    @Synchronized override fun start() {
        AudioRetirementGate.requireIdle()
        check(!finished && !startAttempted) { "WAV recorder cannot be started twice." }
        startAttempted = true
        output.channel.position(0)
        output.channel.write(createWavHeader(0, settings.audioSampleRateHz, settings.audioBitDepth.bits, settings.audioChannels, settings.audioBitDepth == AudioBitDepth.PCM_FLOAT))
        attachEffects()
        startedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
        input.start()
        overrunBaseline = input.overrunCount
        routedInputDeviceId = input.routedDeviceId
        inputGuard?.attach(input.audioRouting(context), AudioCapturePath.AUDIO_RECORD)
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

    /**
     * ADR-0011: seal whatever was captured, even after a writer failure or with incomplete timing, and
     * keep it. Empty audio is removed without discarding the rest of the take.
     */
    @Synchronized override fun prepareIncompleteCompletion(reasons: List<String>): PreparedAudioSidecar? {
        recoveryMember.beginSalvage()
        salvaging = true
        incompleteReasons = reasons
        val alreadyPrepared = preparedOutput != null
        return prepareCompletion()?.also {
            recoveryMember.keepBytes()
            if (alreadyPrepared) markPreparedMetadataIncomplete(IncompleteTake.withInputOverruns(reasons, inputOverruns) ?: reasons)
        }
    }

    /** The metadata of an output prepared as complete is rewritten when the take turns out incomplete. */
    private fun markPreparedMetadataIncomplete(reasons: List<String>) {
        val metadata = preparedMetadataUri ?: return
        try {
            val resolver = context.contentResolver
            val document = requireNotNull(resolver.openInputStream(metadata)).use { JSONObject(String(it.readBytes())) }
            requireNotNull(resolver.openOutputStream(metadata, "wt")).use {
                it.write(document.putIncompleteMarker(reasons).toString(2).toByteArray())
            }
        } catch (failure: Exception) {
            android.util.Log.w("AudioSidecar", "Incomplete marker could not be written to the audio metadata", failure)
        }
    }

    /** Publish exactly the prepared identities; retries never allocate replacement rows. */
    @Synchronized override fun publishPrepared(): AudioSidecarRecordingResult? {
        if (aborted) return null
        finishedResult?.let { return it }
        val prepared = preparedOutput ?: return null
        return try {
            recoveryMember.publish(requireNotNull(preparedMetadataUri), "Audio metadata publication failed.")
            recoveryMember.publish(uri, "WAV MediaStore publication failed.")
            recoveryMember.published()
            prepared.result.also { finishedResult = it }
        } catch (failure: Throwable) {
            abortAfterFailure(failure)
            throw failure
        }
    }

    /**
     * PLAN-074 decision 10: the prepared audio was appended to the take's video, so this file and its metadata go and
     * the take publishes without them. False when nothing could be removed: the audio is still prepared and publishes
     * beside the video.
     */
    @Synchronized fun withdrawPrepared(): Boolean {
        check(preparedOutput != null && finishedResult == null && !aborted) { "Only prepared, unpublished audio withdraws" }
        try {
            if (!recoveryMember.withdraw()) return false
        } catch (failure: Throwable) {
            // Half removed: nothing of it can be published any more.
            aborted = true
            preparedOutput = null
            preparedMetadataUri = null
            throw failure
        }
        aborted = true
        preparedOutput = null
        preparedMetadataUri = null
        return true
    }

    /** Runs [block] on the prepared WAV, opened for reading; on a drive the descriptor is released with it (ADR-0037). */
    @Synchronized internal fun <T> readPrepared(block: (java.io.FileDescriptor) -> T): T {
        check(preparedOutput != null && !aborted) { "The audio is not prepared" }
        val mark = DriveDescriptors.releaseMark(uri)
        return requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")).useOnDrive(uri, mark) { block(it.fileDescriptor) }
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
            stop = { try { input.stop() } finally { sampleInputOverruns() } },
            release = {
                inputGuard?.detach()
                releaseAudioResources(effects.map { effect -> { effect.release() } } +
                    listOf({ input.close() }))
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
        writerFailure?.let { if (success && !salvaging) throw it }
        if (!success || (writerFailure != null && !salvaging) || dataBytes == 0L) return null
        if (salvaging) runCatching { captureTiming.requireComplete(capturedResult.frames) } else captureTiming.requireComplete(capturedResult.frames)
        output.channel.position(0)
        val header = createWavHeader(dataBytes, settings.audioSampleRateHz, settings.audioBitDepth.bits, settings.audioChannels, settings.audioBitDepth == AudioBitDepth.PCM_FLOAT)
        while (header.hasRemaining()) output.channel.write(header)
        output.channel.force(true)
        closeOutput()
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

    /** Closes the stream, then the descriptor, once; harmless after a [DriveDescriptors] release closed them. */
    private fun closeOutput() {
        try { fileCloser.close() } finally { driveHandle.unregister() }
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
                val read = input.read(buffer, buffer.capacity())
                if (read <= 0 && !running.get()) break
                if (read == PcmInput.ERROR_DEAD || read == PcmInput.ERROR_INVALID) {
                    error("AudioRecord read failed with code $read")
                }
                if (read <= 0) continue
                captureTiming.readReturned(read, SystemClock.elapsedRealtimeNanos())
                sampleInputOverruns()
                val observedAtMs = SystemClock.elapsedRealtime()
                val observedEffects = observeEffects(observedAtMs)
                inputGuard?.silenceIfMuted(buffer, read)
                levelMeter.observeInput(buffer, read)
                equalizer?.process(buffer, read, settings.audioBitDepth.toMeterEncoding())
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
                val retained = captureTiming.captured(input, buffer, read)
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
        // An input without a session (the low-latency one) carries no platform effects: they read
        // UNAVAILABLE, and software AGC stands in for the hardware one.
        val session = input.audioSessionId
        val requestedAgc = settings.automaticGainControlEnabled
        val agc = if (settings.audioRecordingGain.enabled) {
            val effect = session?.let { createDisabledManualAgc(it) { retained -> effects += retained } }
            val available = if (session == null) false else runCatching { AutomaticGainControl.isAvailable() }.getOrNull()
            Configured(effect, available, false, AudioEffectObservationReader(requestedAgc, effect, available))
        } else configure(requestedAgc, { session != null && AutomaticGainControl.isAvailable() }) { session?.let { AutomaticGainControl.create(it) } }
        hardwareAgcReader = agc.reader
        hasHardwareAgc = agc.effect != null
        // Float never runs SoftAgc: do not advertise a processor the writer does not execute.
        if (!settings.audioRecordingGain.enabled && requestedAgc && settings.audioBitDepth != AudioBitDepth.PCM_FLOAT &&
            (agc.effect == null || agc.reader.read().state == AudioEffectState.DISABLED)) {
            softAgc = SoftAgc(settings.audioSampleRateHz, settings.audioChannels)
        }
        effectReaders = Triple(
            configure(settings.noiseSuppressorEnabled, { session != null && NoiseSuppressor.isAvailable() }) { session?.let { NoiseSuppressor.create(it) } }.reader,
            if (softAgc == null) agc.reader else AudioEffectObservationReader(requestedAgc, agc.effect, agc.available, agc.failed, softwareEnabled = true),
            configure(settings.acousticEchoCancelerEnabled, { session != null && AcousticEchoCanceler.isAvailable() }) { session?.let { AcousticEchoCanceler.create(it) } }.reader,
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
        readEstimatedFrameZeroNs = captureTiming.readEstimatedFrameZeroNs(),
        preferredInputDeviceId = preferredInput?.id,
        routedInputDeviceId = routedInputDeviceId,
        source = settings.audioSource.name,
        noiseSuppressorEnabled = lastEffects?.noiseSuppressor?.state == AudioEffectState.ENABLED,
        automaticGainControlEnabled = lastEffects?.automaticGainControl?.state == AudioEffectState.ENABLED,
        acousticEchoCancelerEnabled = lastEffects?.acousticEchoCanceler?.state == AudioEffectState.ENABLED,
        recordingGain = settings.audioRecordingGain,
        audioEffects = lastEffects,
        audioEffectsObservedAtElapsedRealtimeMs = effectsObservedAtMs,
        inputEvidence = inputEvidence,
        inputOverruns = inputOverruns,
    )

    private fun writeMetadata(result: AudioSidecarRecordingResult): Uri {
        val json = JSONObject()
            .put("schema", "opencinecam-audio-sidecar-v1")
            .put("productionSlate", JSONObject(com.librestatic.opencinecam.metadata.productionSlateJson(settings.productionSlate).toString()))
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
            // The manual sync offset (PLAN-074 decision 12) is recorded, not applied: this file is not shifted.
            .put("syncOffsetMs", settings.audioSyncOffsetMs)
            .put("captureTiming", result.captureTiming?.let(::pcmSourceTimingJson) ?: JSONObject.NULL)
            .put("sharedTiming", captureClock?.report(null, null, null)?.copy(submittedPcmFrames = result.frames, audioStorage = "SEPARATE_WAV")?.let { com.librestatic.opencinecam.metadata.captureEpochJson(it) } ?: JSONObject.NULL)
            .put("source", result.source)
            .put("preferredInputDeviceId", result.preferredInputDeviceId ?: JSONObject.NULL)
            .put("routedInputDeviceId", result.routedInputDeviceId ?: JSONObject.NULL)
            .putAudioInputRoute(settings.audioInputKey ?: preferredInput?.inputKey(), inputGuard)
            .put("noiseSuppressorEnabled", result.noiseSuppressorEnabled)
            .put("automaticGainControlEnabled", result.automaticGainControlEnabled)
            .put("recordingGain", recordingGainJson(result.recordingGain))
            .put("audioEffects", result.audioEffects?.let { audioEffectsJson(it, requireNotNull(result.audioEffectsObservedAtElapsedRealtimeMs)) } ?: JSONObject.NULL)
            .put("acousticEchoCancelerEnabled", result.acousticEchoCancelerEnabled)
            .putPcmInputEvidence(result.inputEvidence)
            .put("disclosure", audioInputDisclosure(result.inputEvidence))
            .put("inputOverruns", inputOverruns)
        IncompleteTake.withInputOverruns(incompleteReasons, inputOverruns)?.let { json.putIncompleteMarker(it) }
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

        fun create(
            context: Context,
            videoDisplayName: String,
            settings: CameraSettings,
            captureClock: com.librestatic.opencinecam.camera.CaptureEpochClock? = null,
            onAudioLevel: ((AudioLevelSnapshot) -> Unit)? = null,
            recoveryGroup: RecordingRecoveryGroup? = null,
            listeningSink: PcmListeningSink? = null,
            inputGuard: AudioInputRouteGuard? = null,
        ): WavAudioSidecarRecorder = create(context, videoDisplayName, settings, captureClock, onAudioLevel, recoveryGroup, listeningSink, inputGuard,
            destination = RecordingDestination.Phone)

        /** PLAN-072: [destination] is where a take this recorder begins itself goes; a [recoveryGroup] already has one. */
        @SuppressLint("MissingPermission")
        internal fun create(
            context: Context,
            videoDisplayName: String,
            settings: CameraSettings,
            captureClock: com.librestatic.opencinecam.camera.CaptureEpochClock? = null,
            onAudioLevel: ((AudioLevelSnapshot) -> Unit)? = null,
            recoveryGroup: RecordingRecoveryGroup? = null,
            listeningSink: PcmListeningSink? = null,
            inputGuard: AudioInputRouteGuard? = null,
            destination: RecordingDestination,
        ): WavAudioSidecarRecorder {
            require(recoveryGroup == null || destination == RecordingDestination.Phone) { "A joined take already has its destination" }
            AudioRetirementGate.requireIdle()
            val appContext = context.applicationContext
            val displayName = videoDisplayName.removeSuffix(".mp4") + ".wav"
            val resolver = appContext.contentResolver
            val recoveryMember = (recoveryGroup ?: RecordingCaptureRecovery.begin(appContext, destination))
                .member(RecordingMemberKind.AUDIO)
            var record: PcmInput? = null
            var descriptor: ParcelFileDescriptor? = null
            var output: FileOutputStream? = null
            try {
                val uri = recoveryMember.insert(displayName, "audio/wav", metadata = false)
                val mask = channelMask(settings.audioChannels)
                val minimum = AudioRecord.getMinBufferSize(settings.audioSampleRateHz, mask, settings.audioBitDepth.androidEncoding)
                require(minimum > 0) { "Unsupported WAV configuration." }
                val preferred = settings.audioInputDeviceId?.let { requestedId ->
                    appContext.getSystemService(AudioManager::class.java)
                        .getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.id == requestedId }
                        ?: error("Requested audio input $requestedId is no longer connected.")
                }
                val opened = PcmInputs.open(PcmInputRequest(
                    source = settings.audioSource,
                    sampleRateHz = settings.audioSampleRateHz,
                    channels = settings.audioChannels,
                    encoding = settings.audioBitDepth.androidEncoding,
                    bufferBytes = frameAlignedBufferBytes(minimum, settings.audioChannels * settings.audioBitDepth.bits / 8),
                    preferredDevice = preferred,
                    formatCheck = PcmFormatCheck.EXACT,
                ))
                record = opened.input
                val evidence = pcmInputEvidence(settings.audioSource.name, opened, settings.appliedAudioEqCurve, settings.appliedAudioEqProfileId)
                descriptor = requireNotNull(resolver.openFileDescriptor(uri, "rw"))
                val stream = FileOutputStream(descriptor.fileDescriptor).also { output = it }
                return WavAudioSidecarRecorder(
                    appContext,
                    uri,
                    displayName,
                    settings,
                    opened.input,
                    evidence,
                    descriptor,
                    stream,
                    preferred,
                    captureClock,
                    onAudioLevel,
                    recoveryMember,
                    listeningSink,
                    inputGuard,
                )
            } catch (failure: Throwable) {
                var retired = true
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (problem: Throwable) {
                        retired = false
                        if (problem !== failure) failure.addSuppressed(problem)
                    }
                }
                cleanup { record?.close() }
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
    /** Why the microphone open is not the low-latency input asked for, or null (PLAN-074 decision 4). */
    val inputFallbackReason: String? get() = null
    /** A thread-safe look at the capture clocks while the take runs; null before any timestamp or when unsupported. */
    fun liveCaptureTiming(): com.librestatic.opencinecam.camera.PcmSourceTimingReport? = null
    /** The writer thread stopped on a failure (e.g. ENODEV once a USB drive is pulled); the take goes on without audio. */
    val writerFailed: Boolean get() = false
    fun start()
    /** Seal native output and metadata, returning stable identities that are still pending. */
    fun prepareCompletion(): PreparedAudioSidecar?
    /**
     * ADR-0011: like [prepareCompletion] for a take that is not fully successful. Audio that holds
     * bytes is sealed and kept (null when it holds none and has been removed).
     */
    fun prepareIncompleteCompletion(reasons: List<String> = emptyList()): PreparedAudioSidecar? = prepareCompletion()
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
    /** Frame zero on the boot-time clock estimated from when the reads returned; null when nothing was read. */
    val readEstimatedFrameZeroNs: Long? = null,
    val recordingGain: DigitalRecordingGain = DigitalRecordingGain(),
    val audioEffects: AudioEffectsSnapshot? = null,
    val audioEffectsObservedAtElapsedRealtimeMs: Long? = null,
    /** PLAN-074 decision 11: the microphone path that delivered the PCM; null when not recorded. */
    val inputEvidence: PcmInputEvidence? = null,
    /** Times the input overran during the take: the audio has gaps. */
    val inputOverruns: Int = 0,
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
