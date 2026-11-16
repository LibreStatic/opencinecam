/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

import com.librestatic.opencinecam.settings.appliedAudioEqCurve
import com.librestatic.opencinecam.media.audio.AudioCapturePath
import com.librestatic.opencinecam.media.audio.AudioInputRouteGuard
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import com.librestatic.opencinecam.camera.AudioSpectrum
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.InputEqualizer
import com.librestatic.opencinecam.camera.SpectrumAnalyzer
import com.librestatic.opencinecam.camera.appliedEqCurve
import com.librestatic.opencinecam.media.audio.PcmFormatCheck
import com.librestatic.opencinecam.media.audio.PcmInput
import com.librestatic.opencinecam.media.audio.PcmInputRequest
import com.librestatic.opencinecam.media.audio.PcmInputs
import com.librestatic.opencinecam.media.audio.audioRouting
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import com.librestatic.opencinecam.settings.CameraSettings
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
    private val context: Context,
    private val input: PcmInput,
    private val equalizer: InputEqualizer,
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
    /** Why the low-latency input asked for is not the one open ([com.librestatic.opencinecam.media.audio.OpenedPcmInput.fallbackReason]). */
    val inputFallbackReason: String? = null,
    private val onSpectrum: (AudioSpectrum) -> Unit = {},
) : AutoCloseable, EqSnippetSource {
    /** The equaliser editor's live spectrum (PLAN-074 decision 13); the read loop builds the analyser only while wanted. */
    @Volatile var spectrumWanted: Boolean = false
    private var spectrum: SpectrumAnalyzer? = null

    /** The equaliser editor's snippet (PLAN-074 decision 13) is the input before the equaliser, so it can be replayed through any curve. */
    @Volatile override var snippetTap: EqSnippetRecorder? = null
    override val snippetEncoding get() = depth.toMeterEncoding()
    override val snippetSampleRateHz get() = input.sampleRateHz
    override val snippetChannels get() = channels

    private val lifecycle = PreviewAudioLifecycle(
        startNative = {
            input.start()
            inputGuard?.attach(input.audioRouting(context), AudioCapturePath.PREVIEW)
        },
        readLoop = ::readLoop,
        stopNative = { inputGuard?.detach(); input.stop() },
        releaseNative = { releaseAudioResources(effects.map { effect -> { effect.release() } } + { input.close() }) },
        onFailure = onFailure,
        retainRetirement = AudioRetirementGate::retain,
        releaseRetirement = AudioRetirementGate::release,
    )

    fun start() = lifecycle.start()

    private fun readLoop() {
        val buffer = ByteBuffer.allocateDirect(previewReadBufferBytes(input.sampleRateHz, channels, depth))
        while (lifecycle.isRunning) {
            buffer.clear()
            val read = input.read(buffer, buffer.capacity())
            if (read == PcmInput.ERROR_DEAD || read == PcmInput.ERROR_INVALID) {
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
            val analyzer = if (spectrumWanted) spectrum ?: SpectrumAnalyzer(depth.toMeterEncoding(), channels, input.sampleRateHz)
                .also { spectrum = it } else { spectrum = null; null }
            if (read > 0) levelMeter.observeInput(buffer, read)
            if (read > 0) analyzer?.observeInput(buffer, read)
            if (read > 0) snippetTap?.append(buffer, read)
            // A flat curve is bit-exact, so the monitor always owns one and an editor can change it live.
            if (read > 0) equalizer.process(buffer, read, depth.toMeterEncoding())
            if (read > 0) analyzer?.observeOutput(buffer, read, SystemClock.elapsedRealtime())?.let {
                if (lifecycle.isRunning) onSpectrum(it)
            }
            if (read > 0) softAgc?.let { agc ->
                when (depth) {
                    AudioBitDepth.PCM_16 -> agc.processPcm16(buffer, read)
                    AudioBitDepth.PCM_24 -> agc.processPcm24(buffer, read)
                    AudioBitDepth.PCM_FLOAT -> Unit
                }
            }
            if (read > 0) {
                recordingGain.process(buffer, read, depth.toMeterEncoding(), channels)
                if (lifecycle.isRunning) listeningSink.offerListening(buffer, read, depth.toMeterEncoding(), input.sampleRateHz, channels)
                levelMeter.analyze(buffer, read, SystemClock.elapsedRealtime())?.let {
                    if (lifecycle.isRunning) onLevel(it.copy(appliedRecordingGain = recordingGain,
                        effects = observedEffects))
                }
            }
        }
    }

    /**
     * Adopted at the next buffer without reopening the microphone; takes read their curve at their start. [profileId]
     * names the profile [curve] came from, so a bundled one is left out on an `AudioRecord` path, as a take leaves it out.
     */
    fun setEqCurve(curve: EqCurve, profileId: String?) = equalizer.setCurve(appliedEqCurve(curve, profileId, input.path))

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
            onSpectrum: (AudioSpectrum) -> Unit = {},
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
            val opened = try {
                val device = settings.audioInputDeviceId?.let { requestedId ->
                    appContext.getSystemService(AudioManager::class.java)
                        .getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.id == requestedId }
                        ?: error("Requested audio input $requestedId is no longer connected.")
                }
                PcmInputs.open(PcmInputRequest(
                    source = settings.audioSource,
                    sampleRateHz = settings.audioSampleRateHz,
                    channels = settings.audioChannels,
                    encoding = depth.androidEncoding,
                    bufferBytes = frameAlignedBufferBytes(minimum, settings.audioChannels * depth.bits / 8),
                    preferredDevice = device,
                    formatCheck = PcmFormatCheck.NONE,
                    initializationFailure = "Live-monitor AudioRecord initialization failed.",
                    deviceRejected = { id -> "AudioRecord rejected input $id." },
                ))
            } catch (failure: Throwable) {
                // The factory has already released whatever it built.
                throw PreviewAudioMonitorCreationFailure(failure, CompletableFuture.completedFuture(Unit))
            }
            val record = opened.input
            val effects = mutableListOf<AudioEffect>()
            try {
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
                // An input without a session (the low-latency one) carries no platform effects: they read
                // UNAVAILABLE, and software AGC stands in for the hardware one.
                val session = record.audioSessionId
                val ns = observeOptional({ session != null && NoiseSuppressor.isAvailable() },
                    { session?.let { NoiseSuppressor.create(it) } }, settings.noiseSuppressorEnabled)
                val aec = observeOptional({ session != null && AcousticEchoCanceler.isAvailable() },
                    { session?.let { AcousticEchoCanceler.create(it) } }, settings.acousticEchoCancelerEnabled)
                val agc = if (settings.audioRecordingGain.enabled) {
                    val supported = session != null && AutomaticGainControl.isAvailable()
                    var effect: AutomaticGainControl? = null
                    if (session != null) createDisabledManualAgc(session) { effects += it; effect = it }
                    effect to AudioEffectObservationReader(settings.automaticGainControlEnabled, effect, supported)
                } else observeOptional({ session != null && AutomaticGainControl.isAvailable() },
                    { session?.let { AutomaticGainControl.create(it) } }, settings.automaticGainControlEnabled)
                val initialAgc = agc.second.read()
                val softwareAgc = if (!settings.audioRecordingGain.enabled && settings.automaticGainControlEnabled &&
                    depth != AudioBitDepth.PCM_FLOAT && (agc.first == null || initialAgc.state == AudioEffectState.DISABLED))
                    SoftAgc(settings.audioSampleRateHz, settings.audioChannels) else null
                return PreviewAudioMonitor(
                    appContext,
                    record,
                    InputEqualizer(record.sampleRateHz, settings.audioChannels)
                        .apply { setCurve(appliedEqCurve(settings.appliedAudioEqCurve, settings.audioEqProfileId, record.path)) },
                    AudioLevelMeter(depth.toMeterEncoding(), settings.audioChannels, sampleRateHz = record.sampleRateHz),
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
                    opened.fallbackReason,
                    onSpectrum,
                )
            } catch (failure: Throwable) {
                val cleanup = PreviewAudioLifecycle(
                    startNative = {}, readLoop = {}, stopNative = {},
                    releaseNative = { releaseAudioResources(effects.map { effect -> { effect.release() } } + { record.close() }) },
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
