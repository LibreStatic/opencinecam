/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.content.Context
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import com.librestatic.opencinecam.core.model.FailureCode
import com.librestatic.opencinecam.core.model.FailureSeverity
import com.librestatic.opencinecam.core.model.Knowledge
import com.librestatic.opencinecam.core.model.Recoverability
import com.librestatic.opencinecam.core.model.StableFailure

enum class AudioSourceKind { UNPROCESSED, VOICE_RECOGNITION, MIC }

data class AudioInputDevice(val id: Int, val name: String) {
    init { require(id >= 0 && name.isNotBlank()) }
}

data class AudioCapability(
    val unprocessed: Knowledge<Boolean>,
    val voiceRecognition: Knowledge<Boolean>,
    val mic: Knowledge<Boolean>,
    val sampleRates: Knowledge<Set<Int>>,
    val channelCounts: Knowledge<Set<Int>>,
    val noiseSuppressor: Knowledge<Boolean>,
    val automaticGainControl: Knowledge<Boolean>,
    val acousticEchoCanceler: Knowledge<Boolean>,
    val inputDevices: Knowledge<List<AudioInputDevice>>,
)

data class AudioRequest(
    val preferredSources: List<AudioSourceKind> = listOf(
        AudioSourceKind.UNPROCESSED,
        AudioSourceKind.VOICE_RECOGNITION,
        AudioSourceKind.MIC,
    ),
    val sampleRate: Int = 48_000,
    val preferStereo: Boolean = true,
    val preferredInputDeviceId: Int? = null,
    val enableNoiseSuppressor: Boolean = false,
    val enableAutomaticGainControl: Boolean = false,
    val enableAcousticEchoCanceler: Boolean = false,
) {
    init {
        require(preferredSources.isNotEmpty() && preferredSources.distinct().size == preferredSources.size)
        require(sampleRate > 0)
    }
}

data class AudioEffectState(val requested: Boolean, val available: Knowledge<Boolean>, val enabled: Boolean)

data class AudioConfiguration(
    val source: AudioSourceKind,
    val sampleRate: Int,
    val channelCount: Int,
    val pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    val inputDevice: AudioInputDevice?,
    val noiseSuppressor: AudioEffectState,
    val automaticGainControl: AudioEffectState,
    val acousticEchoCanceler: AudioEffectState,
)

sealed interface AudioNegotiation {
    data class Configured(val configuration: AudioConfiguration) : AudioNegotiation
    data class Rejected(val failure: StableFailure) : AudioNegotiation
}

class AudioNegotiator(private val correlationId: String = "audio-negotiation") {
    fun negotiate(request: AudioRequest, capability: AudioCapability): AudioNegotiation {
        val source = request.preferredSources.firstOrNull { supported(it, capability) }
            ?: return AudioNegotiation.Rejected(failure(FailureCode.AUDIO_INITIALIZATION_FAILED, "No requested audio source is available."))
        val rates = capability.sampleRates
        when (rates) {
            Knowledge.Unknown -> return AudioNegotiation.Rejected(failure(FailureCode.UNKNOWN_CAPABILITY, "Audio sample-rate support is unknown."))
            is Knowledge.Unsupported -> return AudioNegotiation.Rejected(failure(FailureCode.UNSUPPORTED_CAPABILITY, rates.reason))
            is Knowledge.Known -> if (request.sampleRate !in rates.value) {
                return AudioNegotiation.Rejected(failure(FailureCode.UNSUPPORTED_CAPABILITY, "48 kHz PCM is unsupported."))
            }
        }
        val channelCount = chooseChannels(request, capability.channelCounts)
            ?: return AudioNegotiation.Rejected(failure(FailureCode.UNSUPPORTED_CAPABILITY, "No supported PCM channel configuration."))
        val inputDevice = when (val devices = capability.inputDevices) {
            Knowledge.Unknown -> if (request.preferredInputDeviceId != null) {
                return AudioNegotiation.Rejected(failure(FailureCode.UNKNOWN_CAPABILITY, "Input devices are unknown."))
            } else null
            is Knowledge.Unsupported -> return AudioNegotiation.Rejected(failure(FailureCode.UNSUPPORTED_CAPABILITY, devices.reason))
            is Knowledge.Known -> request.preferredInputDeviceId?.let { id ->
                devices.value.firstOrNull { it.id == id }
                    ?: return AudioNegotiation.Rejected(failure(FailureCode.UNSUPPORTED_CAPABILITY, "Requested input device is unavailable."))
            }
        }
        return AudioNegotiation.Configured(
            AudioConfiguration(
                source,
                request.sampleRate,
                channelCount,
                inputDevice = inputDevice,
                noiseSuppressor = effect(request.enableNoiseSuppressor, capability.noiseSuppressor),
                automaticGainControl = effect(request.enableAutomaticGainControl, capability.automaticGainControl),
                acousticEchoCanceler = effect(request.enableAcousticEchoCanceler, capability.acousticEchoCanceler),
            ),
        )
    }

    private fun supported(source: AudioSourceKind, capability: AudioCapability): Boolean = when (val value = when (source) {
        AudioSourceKind.UNPROCESSED -> capability.unprocessed
        AudioSourceKind.VOICE_RECOGNITION -> capability.voiceRecognition
        AudioSourceKind.MIC -> capability.mic
    }) {
        is Knowledge.Known -> value.value
        Knowledge.Unknown -> false
        is Knowledge.Unsupported -> false
    }

    private fun chooseChannels(request: AudioRequest, counts: Knowledge<Set<Int>>): Int? = when (counts) {
        Knowledge.Unknown, is Knowledge.Unsupported -> null
        is Knowledge.Known -> when {
            request.preferStereo && 2 in counts.value -> 2
            1 in counts.value -> 1
            else -> null
        }
    }

    private fun effect(requested: Boolean, available: Knowledge<Boolean>) = AudioEffectState(
        requested,
        available,
        requested && available is Knowledge.Known && available.value,
    )

    private fun failure(code: FailureCode, message: String) = StableFailure(
        component = "audio-negotiation",
        code = code,
        severity = if (code == FailureCode.UNKNOWN_CAPABILITY) FailureSeverity.WARNING else FailureSeverity.ERROR,
        recoverability = if (code == FailureCode.UNSUPPORTED_CAPABILITY) Recoverability.UNSUPPORTED else Recoverability.USER_ACTION,
        correlationId = correlationId,
        userMessage = message,
    )
}

/** Android source and effect capability probe using only public APIs. */
class AndroidAudioCapabilitySource(private val audioManager: AudioManager) {
    fun probe(): AudioCapability {
        val minBuffer = AudioRecord.getMinBufferSize(
            48_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val available = minBuffer > 0
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.toBooleanStrictOrNull()
            ?.let(Knowledge<Boolean>::Known)
            ?: Knowledge.Unknown
        val devices = Knowledge.Known(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map {
            AudioInputDevice(it.id, it.productName?.toString().orEmpty().ifBlank { "input-${it.id}" })
        })
        return AudioCapability(
            unprocessed = unprocessed,
            voiceRecognition = Knowledge.Known(available),
            mic = Knowledge.Known(available),
            sampleRates = if (available) Knowledge.Known(setOf(48_000)) else Knowledge.Unsupported("PCM input is unavailable."),
            channelCounts = if (available) Knowledge.Known(setOf(1, 2)) else Knowledge.Unsupported("PCM input is unavailable."),
            noiseSuppressor = Knowledge.Known(NoiseSuppressor.isAvailable()),
            automaticGainControl = Knowledge.Known(AutomaticGainControl.isAvailable()),
            acousticEchoCanceler = Knowledge.Known(AcousticEchoCanceler.isAvailable()),
            inputDevices = devices,
        )
    }
}

class AndroidAudioRecordFactory(
    private val context: Context,
    private val audioManager: AudioManager,
) {
    @SuppressLint("MissingPermission")
    fun create(configuration: AudioConfiguration, bufferBytes: Int): AudioRecord {
        require(bufferBytes > 0)
        check(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "RECORD_AUDIO permission is required before creating AudioRecord."
        }
        val source = when (configuration.source) {
            AudioSourceKind.UNPROCESSED -> android.media.MediaRecorder.AudioSource.UNPROCESSED
            AudioSourceKind.VOICE_RECOGNITION -> android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
            AudioSourceKind.MIC -> android.media.MediaRecorder.AudioSource.MIC
        }
        val channelMask = if (configuration.channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        return AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(configuration.pcmEncoding)
                    .setSampleRate(configuration.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
            .also { record ->
                configuration.inputDevice?.let { device ->
                    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.id == device.id }
                        ?.let(record::setPreferredDevice)
                }
            }
    }
}
