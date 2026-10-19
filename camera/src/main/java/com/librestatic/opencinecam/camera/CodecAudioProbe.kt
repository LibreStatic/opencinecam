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

/**
 * Owner-scoped cache; any validity-key change invalidates the previous report. With a [store] the
 * report also survives a restart, so the encoder list is not re-probed until the build, the
 * characteristics or a probe version changes.
 */
class CodecAudioReportCache(private val store: CodecAudioReportStore? = null) {
    private var cached: CachedCodecAudioReport? = null
    private var loaded = false

    @Synchronized
    fun read(current: CacheValidityKey): CodecAudioMetadata? {
        if (!loaded) {
            loaded = true
            cached = store?.load()
        }
        val hit = cached?.takeIf { it.validityKey == current }
        if (hit == null && cached != null) clear()
        return hit?.metadata
    }

    @Synchronized
    fun write(key: CacheValidityKey, metadata: CodecAudioMetadata) {
        loaded = true
        cached = CachedCodecAudioReport(key, metadata)
        store?.save(requireNotNull(cached))
    }

    @Synchronized
    fun clear() {
        cached = null
        store?.delete()
    }
}

class CodecAudioProbe(
    private val source: CodecAudioMetadataSource,
    private val cache: CodecAudioReportCache,
) {
    fun probe(key: CacheValidityKey): CodecAudioMetadata =
        cache.read(key) ?: source.probe().also { cache.write(key, it) }
}

/** Names of every encoder on the device: cheap to list, and it changes when codecs are added or removed. */
fun listEncoderNames(): List<String> = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    .filter(MediaCodecInfo::isEncoder)
    .flatMap { info -> info.supportedTypes.map { mime -> "${info.name}|$mime" } }
    .sorted()

const val CODEC_AUDIO_PROTOCOL_VERSION = "1"
const val CODEC_AUDIO_PROBE_VERSION = "1"
