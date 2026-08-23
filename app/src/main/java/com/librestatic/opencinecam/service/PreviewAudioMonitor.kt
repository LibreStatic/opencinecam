/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.service

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
import com.librestatic.opencinecam.camera.AudioLevelMeter
import com.librestatic.opencinecam.camera.AudioLevelSnapshot
import com.librestatic.opencinecam.camera.SoftAgc
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.storage.toMeterEncoding
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Non-recording mic owner used only while the Video/LOG viewfinder is active. */
internal class PreviewAudioMonitor private constructor(
    private val audioRecord: AudioRecord,
    private val levelMeter: AudioLevelMeter,
    private val softAgc: SoftAgc?,
    private val depth: AudioBitDepth,
    private val effects: List<AudioEffect>,
    private val onLevel: (AudioLevelSnapshot) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "Preview audio monitor is already running." }
        audioRecord.startRecording()
        check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "Preview AudioRecord did not enter RECORDING state."
        }
        thread = Thread(::readLoop, "OpenCineCamAudioMonitor").apply { start() }
    }

    private fun readLoop() {
        val buffer = ByteBuffer.allocateDirect(audioRecord.bufferSizeInFrames.coerceAtLeast(2_048) * 8)
        try {
            while (running.get()) {
                buffer.clear()
                val read = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    error("Preview AudioRecord read failed with code $read.")
                }
                if (read > 0) levelMeter.analyze(buffer, read, SystemClock.elapsedRealtime())?.let(onLevel)
                if (read > 0) softAgc?.let { agc ->
                    when (depth) {
                        AudioBitDepth.PCM_16 -> agc.processPcm16(buffer, read)
                        AudioBitDepth.PCM_24 -> agc.processPcm24(buffer, read)
                        AudioBitDepth.PCM_FLOAT -> Unit
                    }
                }
            }
        } catch (failure: Throwable) {
            if (running.get()) onFailure(failure)
        } finally {
            running.set(false)
        }
    }

    override fun close() {
        val wasRunning = running.getAndSet(false)
        if (wasRunning) runCatching { audioRecord.stop() }
        thread?.join(1_000)
        if (thread?.isAlive == true) thread?.interrupt()
        effects.forEach { runCatching { it.release() } }
        runCatching { audioRecord.release() }
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun create(
            context: Context,
            settings: CameraSettings,
            onLevel: (AudioLevelSnapshot) -> Unit,
            onFailure: (Throwable) -> Unit,
        ): PreviewAudioMonitor {
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
                .setBufferSizeInBytes((minimum * 2).coerceAtLeast(16_384))
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
                fun attach(effect: AudioEffect?, requested: Boolean) {
                    if (effect == null) return
                    effects += effect
                    effect.setEnabled(requested)
                }
                fun <T : AudioEffect> safeCreate(create: () -> T?, requested: Boolean) {
                    val effect = try { create() } catch (failure: Throwable) { null } ?: return
                    runCatching { effect.setEnabled(requested) }
                    effects += effect
                }
                safeCreate({ NoiseSuppressor.create(record.audioSessionId) }, settings.noiseSuppressorEnabled)
                safeCreate({ AcousticEchoCanceler.create(record.audioSessionId) }, settings.acousticEchoCancelerEnabled)
                var softwareAgc: SoftAgc? = null
                if (settings.automaticGainControlEnabled) {
                    val hardwareEffect = runCatching { AutomaticGainControl.create(record.audioSessionId) }.getOrNull()
                    if (hardwareEffect != null) {
                        val status = runCatching { hardwareEffect.setEnabled(true) }.getOrDefault(AudioEffect.ERROR)
                        if (status == AudioEffect.SUCCESS && hardwareEffect.enabled) effects += hardwareEffect
                        else runCatching { hardwareEffect.release() }
                    }
                    if (effects.none { it is AutomaticGainControl }) {
                        softwareAgc = SoftAgc(settings.audioSampleRateHz, settings.audioChannels)
                    }
                }
                return PreviewAudioMonitor(
                    record,
                    AudioLevelMeter(depth.toMeterEncoding(), settings.audioChannels),
                    softwareAgc,
                    depth,
                    effects,
                    onLevel,
                    onFailure,
                )
            } catch (failure: Throwable) {
                effects.forEach { runCatching { it.release() } }
                record.release()
                throw failure
            }
        }
    }
}
