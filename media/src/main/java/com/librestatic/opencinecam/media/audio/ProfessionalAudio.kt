/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

enum class AudioOutputFormat(val label: String) {
    AAC_MP4("AAC · pista MP4"),
    WAV_PCM("WAV · lossless PCM"),
    FLAC("FLAC · lossless"),
}

enum class AudioBitDepth(val bits: Int, val androidEncoding: Int, val label: String) {
    PCM_16(16, AudioFormat.ENCODING_PCM_16BIT, "16-bit PCM"),
    PCM_24(24, AudioFormat.ENCODING_PCM_24BIT_PACKED, "24-bit PCM"),
    PCM_FLOAT(32, AudioFormat.ENCODING_PCM_FLOAT, "32-bit float"),
}

enum class AudioSourceSelection(val androidSource: Int, val label: String) {
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, "Unprocessed"),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "Reconocimiento de voz"),
    MIC(MediaRecorder.AudioSource.MIC, "Standard microphone"),
}

data class SelectableAudioInput(
    /** Volatile runtime id; changes on every reconnection. Never persist it. */
    val id: Int,
    /** The product name, or empty when the device reports none; :app maps [type] to a localized name. */
    val label: String,
    val type: Int,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<Int>,
    val address: String = "",
) {
    val key: AudioInputKey get() = AudioInputKey(type, label, address)
    val isExternal: Boolean get() = isExternalInputType(type)
    val kind: AudioInputKind get() = audioInputKind(type)
}

data class PcmAudioConfiguration(
    val sampleRate: Int,
    val bitDepth: AudioBitDepth,
    val channels: Int,
) {
    val derivedBitrateKbps: Int get() = sampleRate * bitDepth.bits * channels / 1_000
}

data class ProfessionalAudioCapabilities(
    val permissionGranted: Boolean,
    val formats: List<AudioOutputFormat>,
    val sampleRates: List<Int>,
    val bitDepths: List<AudioBitDepth>,
    val channelCounts: List<Int>,
    val pcmConfigurations: List<PcmAudioConfiguration>,
    val aacSampleRates: List<Int>,
    val aacChannelCounts: List<Int>,
    val aacBitratesKbps: List<Int>,
    val sources: List<AudioSourceSelection>,
    val inputs: List<SelectableAudioInput>,
    val noiseSuppressorAvailable: Boolean,
    val automaticGainControlAvailable: Boolean,
    val acousticEchoCancelerAvailable: Boolean,
    /** The input the PCM formats were probed on; null when only the default route was probed. */
    val probedInputKey: AudioInputKey? = null,
    /** False when the probed input refused to route a started capture to itself. */
    val probedInputRouteConfirmed: Boolean? = null,
) {
    val losslessAvailable: Boolean get() = formats.any { it != AudioOutputFormat.AAC_MP4 }
}

/**
 * Public-API capability probe. Empty AudioDeviceInfo arrays are treated as unknown and are
 * supplemented only by AudioRecord configurations that actually initialize on this device.
 */
class AndroidProfessionalAudioProbe(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * Probes the inputs and the PCM formats of one of them: the one [requestedInput] resolves to,
     * or the "Auto" choice when it is null. Formats are only probed for that input to keep this short.
     */
    @SuppressLint("MissingPermission")
    fun probe(requestedInput: AudioInputKey? = null): ProfessionalAudioCapabilities {
        val permission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val infos = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter(AudioDeviceInfo::isSource)
        val devices = infos
            .map { device ->
                SelectableAudioInput(
                    id = device.id,
                    label = device.productName?.toString().orEmpty().trim(),
                    type = device.type,
                    sampleRates = device.sampleRates.filter { it > 0 }.distinct().sorted(),
                    channelCounts = device.channelCounts.filter { it in 1..2 }.distinct().sorted(),
                    encodings = device.encodings.distinct().sorted(),
                    address = device.address.orEmpty(),
                )
            }
            .sortedWith(compareBy<SelectableAudioInput> { inputOrder(it.type) }.thenBy { it.label })

        val target = (requestedInput?.let { resolveAudioInput(it, devices) } ?: preferredAutoInput(devices))
            ?.let { selected -> infos.firstOrNull { it.id == selected.id } }
            // The built-in microphone is the default route: the original fast probe applies.
            ?.takeIf { isExternalInputType(it.type) }
        val probed = when {
            !permission -> null
            target == null -> ExternalProbe(probePcmConfigurations(), null)
            // Never leave the format list empty because the external input refused every candidate.
            else -> probeExternalPcm(target).let { if (it.configurations.isEmpty()) ExternalProbe(probePcmConfigurations(), it.routeConfirmed) else it }
        }
        val working = probed?.configurations.orEmpty()
        val rates = working.map { it.sampleRate }.distinct().sorted()
        val depths = AudioBitDepth.entries.filter { depth -> working.any { it.depth == depth } }
        val channels = working.map { it.channels }.distinct().sorted()
        val aac = aacCapabilities()
        val aacBitrates = aac?.bitratesKbps.orEmpty()
        val flacAvailable = codecCapabilities(MediaFormat.MIMETYPE_AUDIO_FLAC) != null &&
            working.any { it.depth != AudioBitDepth.PCM_FLOAT }
        val formats = buildList {
            if (aac != null && aacBitrates.isNotEmpty()) add(AudioOutputFormat.AAC_MP4)
            if (working.isNotEmpty()) add(AudioOutputFormat.WAV_PCM)
            if (flacAvailable) add(AudioOutputFormat.FLAC)
        }
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.toBooleanStrictOrNull() == true
        return ProfessionalAudioCapabilities(
            permissionGranted = permission,
            formats = formats,
            sampleRates = rates,
            bitDepths = depths,
            channelCounts = channels,
            pcmConfigurations = working.map { PcmAudioConfiguration(it.sampleRate, it.depth, it.channels) }
                .sortedWith(compareBy<PcmAudioConfiguration> { it.sampleRate }.thenBy { it.bitDepth.bits }.thenBy { it.channels }),
            aacSampleRates = aac?.sampleRates.orEmpty(),
            aacChannelCounts = aac?.channelCounts.orEmpty(),
            aacBitratesKbps = aacBitrates,
            sources = buildList {
                if (unprocessed) add(AudioSourceSelection.UNPROCESSED)
                add(AudioSourceSelection.VOICE_RECOGNITION)
                add(AudioSourceSelection.MIC)
            },
            inputs = devices,
            noiseSuppressorAvailable = NoiseSuppressor.isAvailable(),
            automaticGainControlAvailable = AutomaticGainControl.isAvailable(),
            acousticEchoCancelerAvailable = AcousticEchoCanceler.isAvailable(),
            probedInputKey = target?.inputKey(),
            probedInputRouteConfirmed = probed?.routeConfirmed,
        )
    }

    private class ExternalProbe(val configurations: Set<PcmTuple>, val routeConfirmed: Boolean?)

    /**
     * An AudioRecord negotiates its format with the default route at build time, so an external
     * input is only proven by starting a capture bound to it and reading back [AudioRecord.getRoutedDevice].
     * Candidates are the standard rates plus any extra rate the device advertises.
     */
    @SuppressLint("MissingPermission")
    private fun probeExternalPcm(device: AudioDeviceInfo): ExternalProbe {
        val advertisedRates = device.sampleRates.filter { it >= MIN_EXTRA_SAMPLE_RATE && it <= MAX_SAMPLE_RATE }
        val rates = (SAMPLE_RATE_CANDIDATES + advertisedRates).distinct().sorted()
            .filter { advertisedRates.isEmpty() || it in advertisedRates }
        val advertisedChannels = device.channelCounts.filter { it in 1..2 }
        val channelOptions = listOf(1, 2).filter { advertisedChannels.isEmpty() || it in advertisedChannels || it == 1 }
        var anyRouted = false
        var anyStarted = false
        val working = buildSet {
            for (rate in rates) for (depth in AudioBitDepth.entries) for (channels in channelOptions) {
                val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val minimum = AudioRecord.getMinBufferSize(rate, mask, depth.androidEncoding)
                if (minimum <= 0) continue
                val record = runCatching {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(depth.androidEncoding).build())
                        .setBufferSizeInBytes((minimum * 2).coerceAtLeast(16_384))
                        .build()
                }.getOrNull() ?: continue
                try {
                    if (record.state != AudioRecord.STATE_INITIALIZED || !record.setPreferredDevice(device)) continue
                    record.startRecording()
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) continue
                    anyStarted = true
                    val routed = record.routedDevice?.id == device.id
                    if (routed) anyRouted = true
                    if (routed && record.format.sampleRate == rate && record.format.encoding == depth.androidEncoding &&
                        record.channelCount == channels) add(PcmTuple(rate, depth, channels))
                } catch (_: Throwable) {
                    // A refused candidate is simply not offered.
                } finally {
                    runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
                    record.release()
                }
            }
        }
        return ExternalProbe(working, if (anyStarted) anyRouted else null)
    }

    @SuppressLint("MissingPermission")
    private fun probePcmConfigurations(): Set<PcmTuple> = buildSet {
        for (rate in SAMPLE_RATE_CANDIDATES) {
            for (depth in AudioBitDepth.entries) {
                for (channels in listOf(1, 2)) {
                    val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                    val minimum = AudioRecord.getMinBufferSize(rate, mask, depth.androidEncoding)
                    if (minimum <= 0) continue
                    val record = runCatching {
                        AudioRecord.Builder()
                            .setAudioSource(MediaRecorder.AudioSource.MIC)
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(rate)
                                    .setChannelMask(mask)
                                    .setEncoding(depth.androidEncoding)
                                    .build(),
                            )
                            .setBufferSizeInBytes((minimum * 2).coerceAtLeast(16_384))
                            .build()
                    }.getOrNull() ?: continue
                    try {
                        if (record.state == AudioRecord.STATE_INITIALIZED &&
                            record.format.sampleRate == rate &&
                            record.format.encoding == depth.androidEncoding
                        ) add(PcmTuple(rate, depth, channels))
                    } finally {
                        record.release()
                    }
                }
            }
        }
    }

    private fun aacCapabilities(): AacCapabilities? {
        val audio = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence()
            .filter { it.isEncoder && it.supportedTypes.any { mime -> mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).audioCapabilities }.getOrNull() }
            .firstOrNull() ?: return null
        val rates = SAMPLE_RATE_CANDIDATES.filter(audio::isSampleRateSupported)
        val channels = listOf(1, 2).filter { it <= audio.maxInputChannelCount }
        if (rates.isEmpty() || channels.isEmpty()) return null
        return AacCapabilities(
            sampleRates = rates,
            channelCounts = channels,
            bitratesKbps = AAC_BITRATE_CANDIDATES.filter { it * 1_000 in audio.bitrateRange },
        )
    }

    private fun codecCapabilities(mime: String): MediaCodecInfo.CodecCapabilities? =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { mime in it.supportedTypes }
            .mapNotNull { runCatching { it.getCapabilitiesForType(mime) }.getOrNull() }
            .firstOrNull()

    private data class PcmTuple(val sampleRate: Int, val depth: AudioBitDepth, val channels: Int)
    private data class AacCapabilities(
        val sampleRates: List<Int>,
        val channelCounts: List<Int>,
        val bitratesKbps: List<Int>,
    )

    companion object {
        val SAMPLE_RATE_CANDIDATES = listOf(44_100, 48_000, 88_200, 96_000, 192_000)
        /** Advertised rates below this are voice-band and not offered for recording. */
        private const val MIN_EXTRA_SAMPLE_RATE = 32_000
        private const val MAX_SAMPLE_RATE = 192_000
        val AAC_BITRATE_CANDIDATES = listOf(64, 96, 128, 160, 192, 256, 320)
    }
}

private fun inputOrder(type: Int): Int = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> 0
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 1
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> 2
    else -> 3
}
