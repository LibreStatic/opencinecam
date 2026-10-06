/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.media.audio.AudioCapturePath
import com.librestatic.opencinecam.media.audio.AudioInputRouteGuard
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import com.librestatic.opencinecam.CameraSettings
import com.librestatic.opencinecam.camera.PcmListeningSink
import com.librestatic.opencinecam.camera.offerListening
import com.librestatic.opencinecam.camera.AudioLevelMeter
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.AudioEffectObservationReader
import com.librestatic.opencinecam.camera.AudioEffectsSnapshot
import com.librestatic.opencinecam.camera.AudioEffectState
import com.librestatic.opencinecam.camera.AudioEffectImplementation
import com.librestatic.opencinecam.camera.requireExclusiveAgcObservation
import com.librestatic.opencinecam.camera.DigitalRecordingGain
import com.librestatic.opencinecam.camera.createDisabledManualAgc
import com.librestatic.opencinecam.camera.SoftAgc
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.storage.AudioRetirementGate
import com.librestatic.opencinecam.storage.frameAlignedBufferBytes
import com.librestatic.opencinecam.storage.releaseAudioResources
import com.librestatic.opencinecam.storage.toMeterEncoding
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

/** Non-recording mic owner used only while the Video/LOG viewfinder is active. */
internal class PreviewAudioMonitor private constructor(
    private val audioRecord: AudioRecord,
    private val levelMeter: AudioLevelMeter,
    private val softAgc: SoftAgc?,
    private val depth: AudioBitDepth,
    private val recordingGain: DigitalRecordingGain,
    private val channels: Int,
    private val effects: List<AudioEffect>,
    private val effectReaders: List<AudioEffectObservationReader>,
    private val hardwareAgcReader: AudioEffectObservationReader,
    private val hasHardwareAgc: Boolean,
    private val onLevel: (AudioLevelSnapshot) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val listeningSink: PcmListeningSink?,
    private val inputGuard: AudioInputRouteGuard?,
) : AutoCloseable {
    private val lifecycle = PreviewAudioLifecycle(
        startNative = {
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Preview AudioRecord did not enter RECORDING state."
            }
            inputGuard?.attach(audioRecord, AudioCapturePath.PREVIEW)
        },
        readLoop = ::readLoop,
        stopNative = { inputGuard?.detach(); if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop() },
        releaseNative = { releaseAudioResources(effects.map { effect -> { effect.release() } } + { audioRecord.release() }) },
        onFailure = onFailure,
        retainRetirement = AudioRetirementGate::retain,
        releaseRetirement = AudioRetirementGate::release,
    )

    fun start() = lifecycle.start()

    private fun readLoop() {
        val buffer = ByteBuffer.allocateDirect(previewReadBufferBytes(audioRecord.sampleRate, channels, depth))
        while (lifecycle.isRunning) {
            buffer.clear()
            val read = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
            if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                error("Preview AudioRecord read failed with code $read.")
            }
            val observedEffects = if (read > 0) {
                val platformAgc = hardwareAgcReader.read()
                requireExclusiveAgcObservation(platformAgc, hasHardwareAgc, recordingGain.enabled, softAgc != null)
                // Derive software evidence from the exact HW observation that authorized this PCM,
                // not a second getter after transformation. No atomicity across HAL changes is claimed.
                val appliedAgc = if (softAgc == null) platformAgc else platformAgc.copy(
                    state = AudioEffectState.ENABLED, implementation = AudioEffectImplementation.SOFTWARE, hasControl = null)
                AudioEffectsSnapshot(effectReaders[0].read(), appliedAgc, effectReaders[2].read())
            } else null
            if (read > 0) levelMeter.observeInput(buffer, read)
            if (read > 0) softAgc?.let { agc ->
                when (depth) {
                    AudioBitDepth.PCM_16 -> agc.processPcm16(buffer, read)
                    AudioBitDepth.PCM_24 -> agc.processPcm24(buffer, read)
                    AudioBitDepth.PCM_FLOAT -> Unit
                }
            }
            if (read > 0) {
                recordingGain.process(buffer, read, depth.toMeterEncoding(), channels)
                if (lifecycle.isRunning) listeningSink.offerListening(buffer, read, depth.toMeterEncoding(), audioRecord.sampleRate, channels)
                levelMeter.analyze(buffer, read, SystemClock.elapsedRealtime())?.let {
                    if (lifecycle.isRunning) onLevel(it.copy(appliedRecordingGain = recordingGain,
                        effects = observedEffects))
                }
            }
        }
    }

    fun closeAsync(): CompletableFuture<Unit> = lifecycle.closeAsync()

    /** AutoCloseable compatibility requests retirement; callers that need proof await closeAsync. */
    override fun close() { closeAsync() }

    companion object {
        fun create(context: Context, settings: CameraSettings, onLevel: (AudioLevelSnapshot) -> Unit,
            onFailure: (Throwable) -> Unit): PreviewAudioMonitor = create(context, settings, onLevel, onFailure, null)

        @SuppressLint("MissingPermission")
        fun create(
            context: Context,
            settings: CameraSettings,
            onLevel: (AudioLevelSnapshot) -> Unit,
            onFailure: (Throwable) -> Unit,
            listeningSink: PcmListeningSink?,
            inputGuard: AudioInputRouteGuard? = null,
        ): PreviewAudioMonitor {
            AudioRetirementGate.requireIdle()
            val appContext = context.applicationContext
            check(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "RECORD_AUDIO permission is required for live monitoring."
            }
            val depth = when (settings.audioOutputFormat) {
                AudioOutputFormat.AAC_MP4, AudioOutputFormat.FLAC -> AudioBitDepth.PCM_16
                AudioOutputFormat.WAV_PCM -> settings.audioBitDepth
            }
            val channelMask = if (settings.audioChannels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minimum = AudioRecord.getMinBufferSize(settings.audioSampleRateHz, channelMask, depth.androidEncoding)
            check(minimum > 0) { "Selected live-monitor PCM configuration is unsupported." }
            val record = AudioRecord.Builder()
                .setAudioSource(settings.audioSource.androidSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(settings.audioSampleRateHz)
                        .setChannelMask(channelMask)
                        .setEncoding(depth.androidEncoding)
                        .build(),
                )
                .setBufferSizeInBytes(frameAlignedBufferBytes(minimum, settings.audioChannels * depth.bits / 8))
                .build()
            val effects = mutableListOf<AudioEffect>()
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED) { "Live-monitor AudioRecord initialization failed." }
                settings.audioInputDeviceId?.let { requestedId ->
                    val device = appContext.getSystemService(AudioManager::class.java)
                        .getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.id == requestedId }
                        ?: error("Requested audio input $requestedId is no longer connected.")
                    check(record.setPreferredDevice(device)) { "AudioRecord rejected input $requestedId." }
                }
                fun observeOptional(available: () -> Boolean, create: () -> AudioEffect?, requested: Boolean): Pair<AudioEffect?, AudioEffectObservationReader> {
                    var failed = false
                    val supported = try { available() } catch (_: Throwable) { failed = true; null }
                    val effect = try { create() } catch (_: Throwable) { failed = true; null }
                    if (effect != null) {
                        effects += effect
                        try {
                            val result = effect.setEnabled(requested)
                            if (result != AudioEffect.SUCCESS || effect.enabled != requested) failed = true
                        } catch (_: Throwable) { failed = true }
                    }
                    return effect to AudioEffectObservationReader(requested, effect, supported, failed)
                }
                val ns = observeOptional(NoiseSuppressor::isAvailable, { NoiseSuppressor.create(record.audioSessionId) }, settings.noiseSuppressorEnabled)
                val aec = observeOptional(AcousticEchoCanceler::isAvailable, { AcousticEchoCanceler.create(record.audioSessionId) }, settings.acousticEchoCancelerEnabled)
                val agc = if (settings.audioRecordingGain.enabled) {
                    val supported = AutomaticGainControl.isAvailable()
                    var effect: AutomaticGainControl? = null
                    createDisabledManualAgc(record.audioSessionId) { effects += it; effect = it }
                    effect to AudioEffectObservationReader(settings.automaticGainControlEnabled, effect, supported)
                } else observeOptional(AutomaticGainControl::isAvailable, { AutomaticGainControl.create(record.audioSessionId) }, settings.automaticGainControlEnabled)
                val initialAgc = agc.second.read()
                val softwareAgc = if (!settings.audioRecordingGain.enabled && settings.automaticGainControlEnabled &&
                    depth != AudioBitDepth.PCM_FLOAT && (agc.first == null || initialAgc.state == AudioEffectState.DISABLED))
                    SoftAgc(settings.audioSampleRateHz, settings.audioChannels) else null
                return PreviewAudioMonitor(
                    record,
                    AudioLevelMeter(depth.toMeterEncoding(), settings.audioChannels, sampleRateHz = record.sampleRate),
                    softwareAgc,
                    depth,
                    settings.audioRecordingGain,
                    settings.audioChannels,
                    effects,
                    listOf(ns.second, agc.second, aec.second),
                    agc.second,
                    agc.first != null,
                    onLevel,
                    onFailure,
                    listeningSink,
                    inputGuard,
                )
            } catch (failure: Throwable) {
                val cleanup = PreviewAudioLifecycle(
                    startNative = {}, readLoop = {}, stopNative = {},
                    releaseNative = { releaseAudioResources(effects.map { effect -> { effect.release() } } + { record.release() }) },
                    onFailure = {},
                    retainRetirement = AudioRetirementGate::retain,
                    releaseRetirement = AudioRetirementGate::release,
                ).closeAsync()
                throw PreviewAudioMonitorCreationFailure(failure, cleanup)
            }
        }
    }
}

/** The factory retains failed preparation resources until this asynchronous receipt completes. */
internal class PreviewAudioMonitorCreationFailure(
    cause: Throwable,
    retirement: CompletableFuture<Unit>,
) : IllegalStateException("Preview audio monitor preparation failed: ${cause.message}", cause) {
    private val ownedRetirement = retirement.thenApply { it }
    val retirement: CompletableFuture<Unit> get() = ownedRetirement.thenApply { it }
}

/**
 * Bytes for one blocking preview read, sized by time rather than by the platform buffer. A buffer
 * sized in frames times a fixed byte factor made a mono 16-bit read span ~680 ms at 48 kHz, longer
 * than the meter's freshness window, so the meter blinked between levels and "no current PCM".
 */
internal fun previewReadBufferBytes(sampleRate: Int, channels: Int, depth: AudioBitDepth,
    chunkMs: Int = PREVIEW_READ_CHUNK_MS): Int {
    val bytesPerSample = when (depth) {
        AudioBitDepth.PCM_16 -> 2
        AudioBitDepth.PCM_24 -> 3
        AudioBitDepth.PCM_FLOAT -> 4
    }
    val frames = (sampleRate.toLong() * chunkMs / 1_000L).coerceAtLeast(MIN_PREVIEW_READ_FRAMES)
    return Math.toIntExact(frames * channels.coerceAtLeast(1) * bytesPerSample)
}

internal const val PREVIEW_READ_CHUNK_MS = 40
private const val MIN_PREVIEW_READ_FRAMES = 256L
