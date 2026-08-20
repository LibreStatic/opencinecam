/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.librestatic.opencinecam.core.model.CacheValidityKey

data class CodecMetadata(
    val name: String,
    val mime: String,
    val hardwareAccelerated: Boolean?,
    val profileLevels: List<String>,
    val colorFormats: List<Int>,
)

data class AudioMetadata(
    val unprocessedSource: Boolean?,
    val noiseSuppressorAvailable: Boolean?,
    val automaticGainControlAvailable: Boolean?,
    val acousticEchoCancelerAvailable: Boolean?,
)

data class CodecAudioMetadata(
    val codecs: List<CodecMetadata>,
    val audio: AudioMetadata,
)

fun interface CodecAudioMetadataSource {
    fun probe(): CodecAudioMetadata
}

class AndroidCodecAudioMetadataSource(private val audioManager: AudioManager) : CodecAudioMetadataSource {
    override fun probe(): CodecAudioMetadata {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter(MediaCodecInfo::isEncoder)
            .flatMap { info ->
                info.supportedTypes.asSequence().mapNotNull { mime ->
                    runCatching { info.getCapabilitiesForType(mime) }.getOrNull()?.let { capabilities ->
                        CodecMetadata(
                            name = info.name,
                            mime = mime,
                            hardwareAccelerated = info.isHardwareAccelerated,
                            profileLevels = capabilities.profileLevels.map { "${it.profile}:${it.level}" }.sorted(),
                            colorFormats = capabilities.colorFormats.sorted(),
                        )
                    }
                }
            }
            .sortedWith(compareBy({ it.mime }, { it.name }))
            .toList()
        return CodecAudioMetadata(
            codecs = codecs,
            audio = AudioMetadata(
                unprocessedSource = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                    ?.toBooleanStrictOrNull(),
                noiseSuppressorAvailable = NoiseSuppressor.isAvailable(),
                automaticGainControlAvailable = AutomaticGainControl.isAvailable(),
                acousticEchoCancelerAvailable = AcousticEchoCanceler.isAvailable(),
            ),
        )
    }
}

data class CachedCodecAudioReport(
    val validityKey: CacheValidityKey,
    val metadata: CodecAudioMetadata,
)

/** Small owner-scoped cache; any validity-key change invalidates the previous report. */
class CodecAudioReportCache {
    private var cached: CachedCodecAudioReport? = null

    fun read(current: CacheValidityKey): CodecAudioMetadata? =
        cached?.takeIf { it.validityKey == current }?.metadata

    fun write(key: CacheValidityKey, metadata: CodecAudioMetadata) {
        cached = CachedCodecAudioReport(key, metadata)
    }

    fun clear() {
        cached = null
    }
}

class CodecAudioProbe(
    private val source: CodecAudioMetadataSource,
    private val cache: CodecAudioReportCache,
) {
    fun probe(key: CacheValidityKey): CodecAudioMetadata =
        cache.read(key) ?: source.probe().also { cache.write(key, it) }
}
