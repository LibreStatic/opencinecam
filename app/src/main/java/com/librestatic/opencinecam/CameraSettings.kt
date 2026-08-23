/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.Context
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.AfLockBehavior
import com.librestatic.opencinecam.camera.ZoomLensSwitchMode

enum class ModeSelectorStyle { DIAL, BUTTONS }
enum class HistogramMode { RGB, LUMA }

enum class TimeLapseLimitMode { UNLIMITED, FRAME_COUNT, DURATION }

data class CameraSettings(
    val audioEnabled: Boolean = true,
    val audioOutputFormat: AudioOutputFormat = AudioOutputFormat.AAC_MP4,
    val audioSampleRateHz: Int = 48_000,
    val audioBitDepth: AudioBitDepth = AudioBitDepth.PCM_24,
    val audioChannels: Int = 1,
    val audioBitrateKbps: Int = 128,
    val audioInputDeviceId: Int? = null,
    val audioSource: AudioSourceSelection = AudioSourceSelection.UNPROCESSED,
    val noiseSuppressorEnabled: Boolean = false,
    val automaticGainControlEnabled: Boolean = true,
    val acousticEchoCancelerEnabled: Boolean = false,
    val burstCount: Int = 5,
    val videoBitrateMbps: Int = 20,
    val flashEnabled: Boolean = false,
    val histogramEnabled: Boolean = true,
    val histogramMode: HistogramMode = HistogramMode.RGB,
    val compositionGridEnabled: Boolean = true,
    val compositionGridMode: CompositionGridMode = CompositionGridMode.THIRDS,
    val horizonLevelEnabled: Boolean = false,
    val tapExposureMeteringEnabled: Boolean = true,
    val logViewAssistEnabled: Boolean = false,
    val modeSelectorStyle: ModeSelectorStyle = ModeSelectorStyle.DIAL,
    val recordingGeometryMode: RecordingGeometryMode = RecordingGeometryMode.COMPATIBLE,
    val videoWidth: Int = 1920,
    val videoHeight: Int = 1080,
    val videoFps: Int = 30,
    val logWidth: Int = 1920,
    val logHeight: Int = 1080,
    val logFps: Int = 30,
    val zoomLensSwitchMode: ZoomLensSwitchMode = ZoomLensSwitchMode.MANUAL_PRESETS,
    val timelapseIntervalMs: Long = 500L,
    val timelapseLimitMode: TimeLapseLimitMode = TimeLapseLimitMode.UNLIMITED,
    val timelapseFrameCount: Int = 300,
    val timelapseDurationMs: Long = 3_600_000L,
    val timelapseWidth: Int = 1920,
    val timelapseHeight: Int = 1080,
    val timelapseFps: Int = 30,
    val afLockBehavior: AfLockBehavior = AfLockBehavior.FREEZE_CURRENT,
) {
    init {
        require(burstCount in 3..10)
        require(videoBitrateMbps in setOf(12, 20, 40))
        require(audioSampleRateHz in setOf(44_100, 48_000, 88_200, 96_000, 192_000))
        require(audioChannels in 1..2)
        require(audioBitrateKbps in setOf(64, 96, 128, 160, 192, 256, 320))
        require(videoWidth > 0 && videoHeight > 0 && videoFps > 0)
        require(logWidth > 0 && logHeight > 0 && logFps > 0)
        require(timelapseIntervalMs in 100L..3_600_000L)
        require(timelapseFrameCount in 2..100_000)
        require(timelapseDurationMs in 1_000L..86_400_000L)
        require(timelapseWidth > 0 && timelapseHeight > 0 && timelapseFps > 0)
    }
}

fun CameraSettings.normalizedFor(capabilities: ProfessionalAudioCapabilities): CameraSettings {
    if (!capabilities.permissionGranted || capabilities.formats.isEmpty()) return this
    val format = audioOutputFormat.takeIf { it in capabilities.formats } ?: capabilities.formats.first()
    val source = audioSource.takeIf { it in capabilities.sources } ?: capabilities.sources.firstOrNull() ?: AudioSourceSelection.MIC
    val inputId = audioInputDeviceId?.takeIf { requested -> capabilities.inputs.any { it.id == requested } }
    return when (format) {
        AudioOutputFormat.AAC_MP4 -> copy(
            audioOutputFormat = format,
            audioSampleRateHz = audioSampleRateHz.takeIf { it in capabilities.aacSampleRates }
                ?: capabilities.aacSampleRates.minByOrNull { kotlin.math.abs(it - 48_000) } ?: 48_000,
            audioChannels = audioChannels.takeIf { it in capabilities.aacChannelCounts }
                ?: capabilities.aacChannelCounts.firstOrNull() ?: 1,
            audioBitrateKbps = audioBitrateKbps.takeIf { it in capabilities.aacBitratesKbps }
                ?: capabilities.aacBitratesKbps.minByOrNull { kotlin.math.abs(it - 128) } ?: 128,
            audioInputDeviceId = inputId,
            audioSource = source,
            noiseSuppressorEnabled = false,
            // AGC survives normalization even without the HAL effect: the in-process
            // SoftAgc fallback guarantees the requested behaviour on every device.
            automaticGainControlEnabled = automaticGainControlEnabled,
            acousticEchoCancelerEnabled = false,
        )
        AudioOutputFormat.WAV_PCM, AudioOutputFormat.FLAC -> {
            val eligible = capabilities.pcmConfigurations.filter {
                format != AudioOutputFormat.FLAC || it.bitDepth == AudioBitDepth.PCM_16
            }
            val exact = eligible.firstOrNull {
                it.sampleRate == audioSampleRateHz && it.bitDepth == audioBitDepth && it.channels == audioChannels
            }
            val chosen = exact ?: eligible.minWithOrNull(
                compareBy<com.librestatic.opencinecam.media.audio.PcmAudioConfiguration> {
                    kotlin.math.abs(it.sampleRate - audioSampleRateHz)
                }.thenBy { kotlin.math.abs(it.bitDepth.bits - audioBitDepth.bits) }
                    .thenBy { kotlin.math.abs(it.channels - audioChannels) },
            )
            copy(
                audioOutputFormat = format,
                audioSampleRateHz = chosen?.sampleRate ?: audioSampleRateHz,
                audioBitDepth = chosen?.bitDepth ?: audioBitDepth,
                audioChannels = chosen?.channels ?: audioChannels,
                audioInputDeviceId = inputId,
                audioSource = source,
                noiseSuppressorEnabled = noiseSuppressorEnabled && capabilities.noiseSuppressorAvailable,
                automaticGainControlEnabled = automaticGainControlEnabled,
                acousticEchoCancelerEnabled = acousticEchoCancelerEnabled && capabilities.acousticEchoCancelerAvailable,
            )
        }
    }
}

class CameraSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): CameraSettings = CameraSettings(
        audioEnabled = preferences.getBoolean(KEY_AUDIO, true),
        audioOutputFormat = migratedAudioOutputFormat(),
        audioSampleRateHz = preferences.getInt(KEY_AUDIO_SAMPLE_RATE, 48_000)
            .takeIf { it in setOf(44_100, 48_000, 88_200, 96_000, 192_000) } ?: 48_000,
        audioBitDepth = enumPreference(KEY_AUDIO_BIT_DEPTH, AudioBitDepth.PCM_24),
        audioChannels = preferences.getInt(KEY_AUDIO_CHANNELS, 1).coerceIn(1, 2),
        audioBitrateKbps = preferences.getInt(KEY_AUDIO_BITRATE, 128)
            .takeIf { it in setOf(64, 96, 128, 160, 192, 256, 320) } ?: 128,
        audioInputDeviceId = preferences.getInt(KEY_AUDIO_INPUT, NO_DEVICE).takeUnless { it == NO_DEVICE },
        audioSource = enumPreference(KEY_AUDIO_SOURCE, AudioSourceSelection.UNPROCESSED),
        noiseSuppressorEnabled = preferences.getBoolean(KEY_AUDIO_NS, false),
        automaticGainControlEnabled = preferences.getBoolean(KEY_AUDIO_AGC, true),
        acousticEchoCancelerEnabled = preferences.getBoolean(KEY_AUDIO_AEC, false),
        burstCount = preferences.getInt(KEY_BURST, 5).coerceIn(3, 10),
        videoBitrateMbps = preferences.getInt(KEY_BITRATE, 20).takeIf { it in setOf(12, 20, 40) } ?: 20,
        flashEnabled = preferences.getBoolean(KEY_FLASH, false),
        histogramEnabled = preferences.getBoolean(KEY_HISTOGRAM, true),
        histogramMode = enumPreference(KEY_HISTOGRAM_MODE, HistogramMode.RGB),
        compositionGridEnabled = preferences.getBoolean(KEY_COMPOSITION_GRID, true),
        compositionGridMode = enumPreference(KEY_COMPOSITION_GRID_MODE, CompositionGridMode.THIRDS),
        horizonLevelEnabled = preferences.getBoolean(KEY_HORIZON_LEVEL, false),
        tapExposureMeteringEnabled = preferences.getBoolean(KEY_TAP_EXPOSURE_METERING, true),
        logViewAssistEnabled = preferences.getBoolean(KEY_LOG_VIEW_ASSIST, false),
        modeSelectorStyle = migratedModeSelectorStyle(),
        recordingGeometryMode = enumPreference(KEY_RECORDING_GEOMETRY_MODE, RecordingGeometryMode.COMPATIBLE),
        videoWidth = preferences.getInt(KEY_VIDEO_WIDTH, 1920).takeUnless { it <= 0 } ?: 1920,
        videoHeight = preferences.getInt(KEY_VIDEO_HEIGHT, 1080).takeUnless { it <= 0 } ?: 1080,
        videoFps = preferences.getInt(KEY_VIDEO_FPS, 30).takeUnless { it <= 0 } ?: 30,
        logWidth = preferences.getInt(KEY_LOG_WIDTH, 1920).takeUnless { it <= 0 } ?: 1920,
        logHeight = preferences.getInt(KEY_LOG_HEIGHT, 1080).takeUnless { it <= 0 } ?: 1080,
        logFps = preferences.getInt(KEY_LOG_FPS, 30).takeUnless { it <= 0 } ?: 30,
        zoomLensSwitchMode = enumPreference(KEY_ZOOM_LENS_SWITCH_MODE, ZoomLensSwitchMode.MANUAL_PRESETS),
        timelapseIntervalMs = preferences.getLong(KEY_TIMELAPSE_INTERVAL_MS, 500L).coerceIn(100L, 3_600_000L),
        timelapseLimitMode = enumPreference(KEY_TIMELAPSE_LIMIT_MODE, TimeLapseLimitMode.UNLIMITED),
        timelapseFrameCount = preferences.getInt(KEY_TIMELAPSE_FRAME_COUNT, 300).coerceIn(2, 100_000),
        timelapseDurationMs = preferences.getLong(KEY_TIMELAPSE_DURATION_MS, 3_600_000L).coerceIn(1_000L, 86_400_000L),
        timelapseWidth = preferences.getInt(KEY_TIMELAPSE_WIDTH, 1920).takeUnless { it <= 0 } ?: 1920,
        timelapseHeight = preferences.getInt(KEY_TIMELAPSE_HEIGHT, 1080).takeUnless { it <= 0 } ?: 1080,
        timelapseFps = preferences.getInt(KEY_TIMELAPSE_FPS, 30).takeUnless { it <= 0 } ?: 30,
        afLockBehavior = enumPreference(KEY_AF_LOCK_BEHAVIOR, AfLockBehavior.FREEZE_CURRENT),
    )

    fun save(settings: CameraSettings) {
        preferences.edit()
            .putBoolean(KEY_AUDIO, settings.audioEnabled)
            .putString(KEY_AUDIO_FORMAT, settings.audioOutputFormat.name)
            .putInt(KEY_AUDIO_SAMPLE_RATE, settings.audioSampleRateHz)
            .putString(KEY_AUDIO_BIT_DEPTH, settings.audioBitDepth.name)
            .putInt(KEY_AUDIO_CHANNELS, settings.audioChannels)
            .putInt(KEY_AUDIO_BITRATE, settings.audioBitrateKbps)
            .putInt(KEY_AUDIO_INPUT, settings.audioInputDeviceId ?: NO_DEVICE)
            .putString(KEY_AUDIO_SOURCE, settings.audioSource.name)
            .putBoolean(KEY_AUDIO_NS, settings.noiseSuppressorEnabled)
            .putBoolean(KEY_AUDIO_AGC, settings.automaticGainControlEnabled)
            .putBoolean(KEY_AUDIO_AEC, settings.acousticEchoCancelerEnabled)
            .putInt(KEY_BURST, settings.burstCount)
            .putInt(KEY_BITRATE, settings.videoBitrateMbps)
            .putBoolean(KEY_FLASH, settings.flashEnabled)
            .putBoolean(KEY_HISTOGRAM, settings.histogramEnabled)
            .putString(KEY_HISTOGRAM_MODE, settings.histogramMode.name)
            .putBoolean(KEY_COMPOSITION_GRID, settings.compositionGridEnabled)
            .putString(KEY_COMPOSITION_GRID_MODE, settings.compositionGridMode.name)
            .putBoolean(KEY_HORIZON_LEVEL, settings.horizonLevelEnabled)
            .putBoolean(KEY_TAP_EXPOSURE_METERING, settings.tapExposureMeteringEnabled)
            .putBoolean(KEY_LOG_VIEW_ASSIST, settings.logViewAssistEnabled)
            .putString(KEY_MODE_SELECTOR_STYLE, settings.modeSelectorStyle.name)
            .putString(KEY_RECORDING_GEOMETRY_MODE, settings.recordingGeometryMode.name)
            .putInt(KEY_VIDEO_WIDTH, settings.videoWidth)
            .putInt(KEY_VIDEO_HEIGHT, settings.videoHeight)
            .putInt(KEY_VIDEO_FPS, settings.videoFps)
            .putInt(KEY_LOG_WIDTH, settings.logWidth)
            .putInt(KEY_LOG_HEIGHT, settings.logHeight)
            .putInt(KEY_LOG_FPS, settings.logFps)
            .putString(KEY_ZOOM_LENS_SWITCH_MODE, settings.zoomLensSwitchMode.name)
            .putLong(KEY_TIMELAPSE_INTERVAL_MS, settings.timelapseIntervalMs)
            .putString(KEY_TIMELAPSE_LIMIT_MODE, settings.timelapseLimitMode.name)
            .putInt(KEY_TIMELAPSE_FRAME_COUNT, settings.timelapseFrameCount)
            .putLong(KEY_TIMELAPSE_DURATION_MS, settings.timelapseDurationMs)
            .putInt(KEY_TIMELAPSE_WIDTH, settings.timelapseWidth)
            .putInt(KEY_TIMELAPSE_HEIGHT, settings.timelapseHeight)
            .putInt(KEY_TIMELAPSE_FPS, settings.timelapseFps)
            .putString(KEY_AF_LOCK_BEHAVIOR, settings.afLockBehavior.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumPreference(key: String, fallback: T): T =
        preferences.getString(key, null)?.let { saved -> enumValues<T>().firstOrNull { it.name == saved } } ?: fallback

    /** One-time repair for releases that accidentally shipped without the approved carousel. */
    private fun migratedModeSelectorStyle(): ModeSelectorStyle {
        if (!preferences.getBoolean(KEY_MODE_SELECTOR_CAROUSEL_MIGRATED, false)) {
            preferences.edit()
                .putBoolean(KEY_MODE_SELECTOR_CAROUSEL_MIGRATED, true)
                .putString(KEY_MODE_SELECTOR_STYLE, ModeSelectorStyle.DIAL.name)
                .apply()
            return ModeSelectorStyle.DIAL
        }
        return enumPreference(KEY_MODE_SELECTOR_STYLE, ModeSelectorStyle.DIAL)
    }

    /** Repairs the WAV-only LOG workaround shipped before AAC could be embedded. */
    private fun migratedAudioOutputFormat(): AudioOutputFormat {
        if (!preferences.getBoolean(KEY_AUDIO_AAC_LOG_MIGRATED, false)) {
            preferences.edit()
                .putBoolean(KEY_AUDIO_AAC_LOG_MIGRATED, true)
                .putString(KEY_AUDIO_FORMAT, AudioOutputFormat.AAC_MP4.name)
                .apply()
            return AudioOutputFormat.AAC_MP4
        }
        return enumPreference(KEY_AUDIO_FORMAT, AudioOutputFormat.AAC_MP4)
    }

    private companion object {
        const val NAME = "camera-settings"
        const val KEY_AUDIO = "audio-enabled"
        const val KEY_AUDIO_FORMAT = "audio-output-format"
        const val KEY_AUDIO_AAC_LOG_MIGRATED = "audio-aac-log-migrated-v1"
        const val KEY_AUDIO_SAMPLE_RATE = "audio-sample-rate-hz"
        const val KEY_AUDIO_BIT_DEPTH = "audio-bit-depth"
        const val KEY_AUDIO_CHANNELS = "audio-channels"
        const val KEY_AUDIO_BITRATE = "audio-bitrate-kbps"
        const val KEY_AUDIO_INPUT = "audio-input-device-id"
        const val KEY_AUDIO_SOURCE = "audio-source"
        const val KEY_AUDIO_NS = "audio-noise-suppressor"
        const val KEY_AUDIO_AGC = "audio-automatic-gain-control"
        const val KEY_AUDIO_AEC = "audio-acoustic-echo-canceler"
        const val KEY_BURST = "burst-count"
        const val KEY_BITRATE = "video-bitrate-mbps"
        const val KEY_FLASH = "flash-enabled"
        const val KEY_HISTOGRAM = "histogram-enabled"
        const val KEY_HISTOGRAM_MODE = "histogram-mode"
        const val KEY_COMPOSITION_GRID = "composition-grid-enabled"
        const val KEY_COMPOSITION_GRID_MODE = "composition-grid-mode"
        const val KEY_HORIZON_LEVEL = "horizon-level-enabled"
        const val KEY_TAP_EXPOSURE_METERING = "tap-exposure-metering-enabled"
        const val KEY_LOG_VIEW_ASSIST = "log-view-assist-enabled"
        const val KEY_MODE_SELECTOR_STYLE = "mode-selector-style"
        const val KEY_MODE_SELECTOR_CAROUSEL_MIGRATED = "mode-selector-carousel-migrated-v1"
        const val KEY_RECORDING_GEOMETRY_MODE = "recording-geometry-mode"
        const val KEY_VIDEO_WIDTH = "video-geometry-width"
        const val KEY_VIDEO_HEIGHT = "video-geometry-height"
        const val KEY_VIDEO_FPS = "video-geometry-fps"
        const val KEY_LOG_WIDTH = "log-geometry-width"
        const val KEY_LOG_HEIGHT = "log-geometry-height"
        const val KEY_LOG_FPS = "log-geometry-fps"
        const val KEY_ZOOM_LENS_SWITCH_MODE = "zoom-lens-switch-mode"
        const val KEY_TIMELAPSE_INTERVAL_MS = "timelapse-interval-ms"
        const val KEY_TIMELAPSE_LIMIT_MODE = "timelapse-limit-mode"
        const val KEY_TIMELAPSE_FRAME_COUNT = "timelapse-frame-count"
        const val KEY_TIMELAPSE_DURATION_MS = "timelapse-duration-ms"
        const val KEY_TIMELAPSE_WIDTH = "timelapse-geometry-width"
        const val KEY_TIMELAPSE_HEIGHT = "timelapse-geometry-height"
        const val KEY_TIMELAPSE_FPS = "timelapse-geometry-fps"
        const val KEY_AF_LOCK_BEHAVIOR = "af-lock-behavior"
        const val NO_DEVICE = Int.MIN_VALUE
    }
}
