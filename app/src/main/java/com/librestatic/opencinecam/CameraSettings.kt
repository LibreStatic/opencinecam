/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.Context
import com.librestatic.opencinecam.camera.MonitoringOptions
import com.librestatic.opencinecam.camera.MonitorColor
import com.librestatic.opencinecam.camera.FalseColorPalette
import com.librestatic.opencinecam.camera.MonitorAspectGuide
import com.librestatic.opencinecam.camera.ImageProcessingSelection
import com.librestatic.opencinecam.camera.StabilizationMode
import com.librestatic.opencinecam.camera.IspMode
import com.librestatic.opencinecam.camera.ExposureSelection
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ShutterUnit
import com.librestatic.opencinecam.camera.Antibanding
import com.librestatic.opencinecam.camera.RecordingWhiteBalancePolicy
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioInputKey
import com.librestatic.opencinecam.media.audio.AudioInputLossPolicy
import com.librestatic.opencinecam.media.audio.SelectableAudioInput
import com.librestatic.opencinecam.media.audio.preferredAutoInput
import com.librestatic.opencinecam.media.audio.resolveAudioInput
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.AfLockBehavior
import com.librestatic.opencinecam.camera.ZoomLensSwitchMode
import com.librestatic.opencinecam.camera.FocusPullEasing
import com.librestatic.opencinecam.camera.AnamorphicSqueeze
import com.librestatic.opencinecam.camera.AnamorphicOutputMode
import com.librestatic.opencinecam.camera.TimecodeMode
import com.librestatic.opencinecam.camera.OpenCineLogGreyReference

import com.librestatic.opencinecam.camera.StillPhotoFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

enum class ModeSelectorStyle { DIAL, BUTTONS }

/** How the viewfinder covers the screen when the capture chrome floats over it. */
enum class ViewfinderScale { FIT, FILL }
enum class HistogramMode { RGB, LUMA }

const val MIN_CHROME_OPACITY = 0.30f
const val MAX_CHROME_OPACITY = 0.85f
const val DEFAULT_CHROME_OPACITY = 0.55f

/** Keeps the floating chrome legible and the image visible; non-finite values fall back to the default. */
fun clampChromeOpacity(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MIN_CHROME_OPACITY, MAX_CHROME_OPACITY) else DEFAULT_CHROME_OPACITY

enum class TimeLapseLimitMode { UNLIMITED, FRAME_COUNT, DURATION }

data class CameraSettings(
    val monitoring: MonitoringOptions = MonitoringOptions(),
    val operation: OperatorPreferences = OperatorPreferences(),
    val imageProcessing: ImageProcessingSelection = ImageProcessingSelection(),
    val exposure: ExposureSelection = ExposureSelection(),
    val whiteBalance: WhiteBalanceSelection = WhiteBalanceSelection.Auto,
    val recordingWhiteBalance: RecordingWhiteBalancePolicy = RecordingWhiteBalancePolicy.CONTINUOUS,
    val subjectDisplay: SubjectDisplaySettings = SubjectDisplaySettings(),
    val audioEnabled: Boolean = true,
    val audioOutputFormat: AudioOutputFormat = AudioOutputFormat.AAC_MP4,
    val audioSampleRateHz: Int = 48_000,
    val audioBitDepth: AudioBitDepth = AudioBitDepth.PCM_24,
    val audioChannels: Int = 1,
    val audioBitrateKbps: Int = 128,
    /**
     * Runtime only: the live device id the selection resolves to on this connection, or null for
     * the platform default. Derived by [normalizedFor]; never persisted, because it changes on
     * every reconnection.
     */
    val audioInputDeviceId: Int? = null,
    /** The remembered input; null means "Auto". Kept while the device is unplugged (OCC-AUDIO-009). */
    val audioInputKey: AudioInputKey? = null,
    /** An id saved by an older version, turned into [audioInputKey] by the first probe that sees it. */
    val legacyAudioInputDeviceId: Int? = null,
    val audioInputLossPolicy: AudioInputLossPolicy = AudioInputLossPolicy.STOP_TAKE,
    val audioListening: AudioListeningSettings = AudioListeningSettings(),
    val audioMeter: AudioMeterSettings = AudioMeterSettings(),
    /** Local explicit consent; never portable through presets or inferred from permission. */
    val geotaggingEnabled: Boolean = false,
    val productionSlate: ProductionSlateSettings = ProductionSlateSettings(),
    val gallery: GallerySettings = GallerySettings(),
    val mediaSharing: MediaSharingSettings = MediaSharingSettings(),
    val captureNaming: CaptureNamingSettings = CaptureNamingSettings(),
    val playback: PlaybackSettings = PlaybackSettings(),
    val proxy: ProxySettings = ProxySettings(),
    val audioListeningOutputDeviceId: Int? = null,
    val audioSource: AudioSourceSelection = AudioSourceSelection.UNPROCESSED,
    val noiseSuppressorEnabled: Boolean = false,
    val automaticGainControlEnabled: Boolean = true,
    val audioRecordingGain: com.librestatic.opencinecam.camera.DigitalRecordingGain = com.librestatic.opencinecam.camera.DigitalRecordingGain(),
    val acousticEchoCancelerEnabled: Boolean = false,
    val burstCount: Int = 5,
    val bracket: com.librestatic.opencinecam.camera.BracketSelection = com.librestatic.opencinecam.camera.BracketSelection(),
    val accumulation: com.librestatic.opencinecam.camera.AccumulationSelection = com.librestatic.opencinecam.camera.AccumulationSelection(),
    val videoBitrateMbps: Int = 20,
    val flashEnabled: Boolean = false,
    val photoFlash: com.librestatic.opencinecam.camera.PhotoFlashSelection = com.librestatic.opencinecam.camera.PhotoFlashSelection(),
    val photoFormat: StillPhotoFormat = StillPhotoFormat.JPEG,
    val photoQuality: Int = 95,
    val photoAspect: com.librestatic.opencinecam.camera.PhotoAspectSelection = com.librestatic.opencinecam.camera.PhotoAspectSelection(),
    val torchStrengthLevel: Int? = null,
    val zebraEnabled: Boolean = false,
    val peakingEnabled: Boolean = false,
    val histogramEnabled: Boolean = true,
    val histogramMode: HistogramMode = HistogramMode.RGB,
    val compositionGridEnabled: Boolean = true,
    val compositionGridMode: CompositionGridMode = CompositionGridMode.THIRDS,
    val horizonLevelEnabled: Boolean = false,
    val tapExposureMeteringEnabled: Boolean = true,
    val logViewAssistEnabled: Boolean = false,
    /** Structural: changes the recorded OCLog2 code values, so it stays pending during a take. */
    val logGreyReference: OpenCineLogGreyReference = OpenCineLogGreyReference.NATIVE,
    val modeSelectorStyle: ModeSelectorStyle = ModeSelectorStyle.DIAL,
    /** Viewfinder spans the whole screen and the chrome floats over it with translucent panels. */
    val translucentChrome: Boolean = false,
    val viewfinderScale: ViewfinderScale = ViewfinderScale.FIT,
    val chromeOpacity: Float = DEFAULT_CHROME_OPACITY,
    val recordingGeometryMode: RecordingGeometryMode = RecordingGeometryMode.COMPATIBLE,
    val videoWidth: Int = 1920,
    val videoHeight: Int = 1080,
    val videoFps: Int = 30,
    val logWidth: Int = 1920,
    val logHeight: Int = 1080,
    val logFps: Int = 30,
    val zoomLensSwitchMode: ZoomLensSwitchMode = ZoomLensSwitchMode.MANUAL_PRESETS,
    val focusPullDurationMs: Long = 2000L,
    val focusPullEasing: FocusPullEasing = FocusPullEasing.EASE_IN_OUT,
    val anamorphicSqueeze: AnamorphicSqueeze = AnamorphicSqueeze.NONE,
    val anamorphicOutputMode: AnamorphicOutputMode = AnamorphicOutputMode.SQUEEZED,
    val timelapseIntervalMs: Long = 500L,
    val timelapseLimitMode: TimeLapseLimitMode = TimeLapseLimitMode.UNLIMITED,
    val timelapseFrameCount: Int = 300,
    val timelapseDurationMs: Long = 3_600_000L,
    val timelapseWidth: Int = 1920,
    val timelapseHeight: Int = 1080,
    val timelapseFps: Int = 30,
    val timelapseFpsDenominator: Int = 1,
    val videoOffSpeed: Boolean = false,
    val videoProjectNumerator: Int = 30,
    val videoProjectDenominator: Int = 1,
    val afLockBehavior: AfLockBehavior = AfLockBehavior.FREEZE_CURRENT,
    val timecodeEnabled: Boolean = false,
    val timecodeRememberPosition: Boolean = true,
    val timecodeResetRevision: Int = 0,
    val timecodeMode: TimecodeMode = TimecodeMode.RECORD_RUN,
    val timecodeNominalFps: Int = 30,
    val timecodeDropFrame: Boolean = false,
    val timecodeStartHours: Int = 1,
    val timecodeStartMinutes: Int = 0,
    val timecodeStartSeconds: Int = 0,
    val timecodeStartFrames: Int = 0,
) {
    init {
        require(photoFormat != StillPhotoFormat.DNG) { "DNG-only uses RAW_PHOTO mode" }
        require(photoQuality in 1..100)
        require(torchStrengthLevel == null || torchStrengthLevel > 0)
        photoFlash.strength?.let { require(it > 0) }
        require(burstCount in 3..10)
        require(videoBitrateMbps in setOf(12, 20, 40))
        require(audioSampleRateHz in setOf(44_100, 48_000, 88_200, 96_000, 192_000))
        require(audioChannels in 1..2)
        require(audioBitrateKbps in setOf(64, 96, 128, 160, 192, 256, 320))
        require(videoWidth > 0 && videoHeight > 0 && videoFps > 0)
        require(logWidth > 0 && logHeight > 0 && logFps > 0)
        require(focusPullDurationMs in 500L..10_000L)
        require(timelapseIntervalMs in 100L..3_600_000L)
        require(timelapseFrameCount in 2..100_000)
        require(timelapseDurationMs in 1_000L..86_400_000L)
        require(timelapseWidth > 0 && timelapseHeight > 0)
        require(validProjectRate(timelapseFps, timelapseFpsDenominator))
        require(validProjectRate(videoProjectNumerator, videoProjectDenominator))
    }
}

/** Legacy local values are repaired to valid labels; strict preset decoding still rejects changed snapshots. */
fun CameraSettings.normalizedTimecode(): CameraSettings {
    val fps = timecodeNominalFps.takeIf { it in setOf(24, 25, 30, 50, 60) } ?: 30
    val drop = timecodeDropFrame && fps in setOf(30, 60)
    val minute = timecodeStartMinutes.coerceIn(0, 59)
    val second = timecodeStartSeconds.coerceIn(0, 59)
    val firstFrame = if (drop && minute % 10 != 0 && second == 0) fps / 15 else 0
    return copy(timecodeNominalFps = fps, timecodeDropFrame = drop,
        timecodeStartHours = timecodeStartHours.coerceIn(0, 23), timecodeStartMinutes = minute,
        timecodeStartSeconds = second, timecodeStartFrames = timecodeStartFrames.coerceIn(firstFrame, fps - 1))
}

/** Changing numbering keeps the nearest valid label, not an invented skipped frame. */
fun CameraSettings.withTimecodeRate(fps: Int, dropFrame: Boolean): CameraSettings =
    copy(timecodeNominalFps = fps, timecodeDropFrame = dropFrame).normalizedTimecode()

/** Where the input selection points on the current connection. */
data class AudioInputResolution(
    /** The resolved input, or null when nothing specific is bound (Auto on the built-in, or a missing key). */
    val input: SelectableAudioInput?,
    /** The remembered key is not connected right now. */
    val unavailable: Boolean,
    /** What "Auto" picks, shown next to the Auto choice. */
    val autoChoice: SelectableAudioInput?,
)

fun CameraSettings.resolveAudioInputSelection(inputs: List<SelectableAudioInput>): AudioInputResolution {
    val auto = preferredAutoInput(inputs)
    val key = audioInputKey ?: return AudioInputResolution(auto, false, auto)
    val resolved = resolveAudioInput(key, inputs)
    return AudioInputResolution(resolved, resolved == null, auto)
}

fun CameraSettings.audioInputUnavailable(capabilities: ProfessionalAudioCapabilities?): Boolean =
    capabilities != null && capabilities.permissionGranted && resolveAudioInputSelection(capabilities.inputs).unavailable

fun CameraSettings.normalizedFor(capabilities: ProfessionalAudioCapabilities): CameraSettings {
    if (!capabilities.permissionGranted || capabilities.formats.isEmpty()) return this
    val format = audioOutputFormat.takeIf { it in capabilities.formats } ?: capabilities.formats.first()
    val source = audioSource.takeIf { it in capabilities.sources } ?: capabilities.sources.firstOrNull() ?: AudioSourceSelection.MIC
    // A legacy id is only meaningful on the connection it was saved on; migrate it if it still matches.
    val inputKey = audioInputKey ?: legacyAudioInputDeviceId?.let { legacy -> capabilities.inputs.firstOrNull { it.id == legacy }?.key }
    val resolution = copy(audioInputKey = inputKey).resolveAudioInputSelection(capabilities.inputs)
    // Auto binds only an external input; the built-in microphone is already the platform default.
    val inputId = if (inputKey == null) resolution.input?.takeIf { it.isExternal }?.id else resolution.input?.id
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
            audioInputKey = inputKey,
            legacyAudioInputDeviceId = null,
            audioSource = source,
            noiseSuppressorEnabled = noiseSuppressorEnabled && capabilities.noiseSuppressorAvailable,
            // Preserve requested AGC; actual hardware/software application is a backend result.
            // Explicit manual digital gain takes precedence without erasing this preference.
            automaticGainControlEnabled = automaticGainControlEnabled,
            acousticEchoCancelerEnabled = acousticEchoCancelerEnabled && capabilities.acousticEchoCancelerAvailable,
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
                audioInputKey = inputKey,
                legacyAudioInputDeviceId = null,
                audioSource = source,
                noiseSuppressorEnabled = noiseSuppressorEnabled && capabilities.noiseSuppressorAvailable,
                automaticGainControlEnabled = automaticGainControlEnabled,
                acousticEchoCancelerEnabled = acousticEchoCancelerEnabled && capabilities.acousticEchoCancelerAvailable,
            )
        }
    }
}

class CameraSettingsStore internal constructor(private val preferences: android.content.SharedPreferences) : SettingsPersistence {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE))

    private fun projectRate(numeratorKey: String, denominatorKey: String): Pair<Int, Int> {
        val n = preferences.getInt(numeratorKey, 30); val d = preferences.getInt(denominatorKey, 1)
        return if (validProjectRate(n, d)) n to d else 30 to 1
    }

    override fun load(): CameraSettings = CameraSettings(
        operation = OperatorPreferences(
            button1 = enumPreference("operator-button-1", OperatorAction.TORCH).takeUnless { it == OperatorAction.SYSTEM_VOLUME } ?: OperatorAction.TORCH,
            button2 = enumPreference("operator-button-2", OperatorAction.PEAKING).takeUnless { it == OperatorAction.SYSTEM_VOLUME } ?: OperatorAction.PEAKING,
            button3 = enumPreference("operator-button-3", OperatorAction.VIEW_ASSIST).takeUnless { it == OperatorAction.SYSTEM_VOLUME } ?: OperatorAction.VIEW_ASSIST,
            volumeUp = enumPreference("operator-volume-up", OperatorAction.SYSTEM_VOLUME),
            volumeDown = enumPreference("operator-volume-down", OperatorAction.SYSTEM_VOLUME),
            startupMode = enumPreference("operator-startup-mode", StartupMode.PHOTO),
            restoreExposureWhiteBalance = preferences.getBoolean("operator-restore-exposure-wb", true),
            restoreTorch = preferences.getBoolean("operator-restore-torch", false),
            lockDuringTake = preferences.getBoolean("operator-lock-during-take", false),
        ),
        imageProcessing = ImageProcessingSelection(
            stabilization = preferences.getString("image-stabilization", "DEFAULT")?.let { runCatching { StabilizationMode.valueOf(it) }.getOrNull() },
            noiseReduction = enumPreference("image-noise-reduction", IspMode.DEFAULT),
            edge = enumPreference("image-edge-enhancement", IspMode.DEFAULT),
        ),
        exposure = ExposureSelection(
            mode = enumPreference("exposure-mode", ExposureMode.AUTO),
            iso = preferences.getInt("exposure-iso", 100).coerceAtLeast(1),
            timeNs = preferences.getLong("exposure-time-ns", 16_666_667L).coerceAtLeast(1),
            shutterUnit = enumPreference("shutter-unit", ShutterUnit.TIME),
            angleTenths = preferences.getInt("shutter-angle-tenths", 1800).coerceIn(1, 3600),
            antibanding = enumPreference("antibanding", Antibanding.AUTO),
        ),
        recordingWhiteBalance = runCatching { RecordingWhiteBalancePolicy.valueOf(preferences.getString("recording-white-balance", "CONTINUOUS") ?: "CONTINUOUS") }.getOrDefault(RecordingWhiteBalancePolicy.CONTINUOUS),
        whiteBalance = when (preferences.getString("white-balance-kind", "AUTO")) {
            "KELVIN" -> WhiteBalanceSelection.Kelvin(preferences.getInt("white-balance-kelvin", 5600).coerceAtLeast(1), preferences.getInt("white-balance-tint", 0).coerceIn(-50, 50))
            "PRESET" -> WhiteBalanceSelection.Preset(preferences.getInt("white-balance-preset", 1))
            else -> WhiteBalanceSelection.Auto
        },
        subjectDisplay = SubjectDisplaySettings(
            mode = enumPreference("subject-mode", SubjectDisplayMode.STATUS),
            previewMirror = preferences.getBoolean("subject-preview-mirror", true),
            previewViewAssist = preferences.getBoolean("subject-preview-view-assist", true),
            selfTimerSeconds = preferences.getInt("self-timer-seconds", 0).takeIf { it in setOf(0, 3, 5, 10) } ?: 0,
            selfMinimalControls = preferences.getBoolean("self-minimal-controls", true),
            brightness = (preferences.getFloat("subject-brightness", 0.7f).takeIf { it.isFinite() } ?: 0.7f).coerceIn(0f, 1f),
            touchLocked = preferences.getBoolean("subject-touch-locked", true),
            showStatus = preferences.getBoolean("subject-show-status", true),
            operatorCue = preferences.getString("subject-cue", "").orEmpty().take(200),
            prompterText = preferences.getString("subject-script", "").orEmpty().take(20_000),
            prompterFontSp = preferences.getInt("subject-font-sp", 32).coerceIn(16, 72),
            prompterSpeedDpPerSecond = preferences.getInt("subject-speed", 24).coerceIn(5, 120),
            prompterPaused = preferences.getBoolean("subject-paused", true),
            continueRecordingOnFold = preferences.getBoolean("fold-continue-recording", true),
            adaptToHinge = preferences.getBoolean("fold-adapt", true),
            swapPanes = preferences.getBoolean("fold-swap", false),
            previewRecordedAreaBands = preferences.getBoolean("subject-preview-bands", true),
            previewGuide = enumPreference("subject-preview-guide", SubjectPreviewGuide.NONE),
            previewAudioMeter = preferences.getBoolean("subject-preview-audio-meter", false),
            tallyBorder = preferences.getBoolean("subject-tally-border", true),
            giantCountdown = preferences.getBoolean("subject-giant-countdown", true),
            fillLightKelvin = preferences.getInt("subject-fill-kelvin", 5000).coerceIn(2700, 6500),
            fillLightTint = preferences.getInt("subject-fill-tint", 0).coerceIn(-50, 50),
            fillLightTimeoutSeconds = preferences.getInt("subject-fill-timeout-seconds", 0).coerceIn(0, 3600),
            interviewQuestions = loadInterviewQuestions(),
            slateFields = preferences.getString("subject-slate-fields", null)?.let { saved ->
                saved.split(',').mapNotNullTo(linkedSetOf()) { name -> SubjectSlateField.entries.firstOrNull { it.name == name } }
            } ?: SubjectSlateField.entries.toSet(),
            slateSyncFlash = preferences.getBoolean("subject-slate-sync-flash", false),
            slateSyncBeep = preferences.getBoolean("subject-slate-sync-beep", false),
            outOfFrameWarning = preferences.getBoolean("subject-out-of-frame", false),
            outOfFrameDelaySeconds = preferences.getInt("subject-out-of-frame-delay", 2).coerceIn(1, 10),
        ),
        audioEnabled = preferences.getBoolean(KEY_AUDIO, true),
        audioOutputFormat = migratedAudioOutputFormat(),
        audioSampleRateHz = preferences.getInt(KEY_AUDIO_SAMPLE_RATE, 48_000)
            .takeIf { it in setOf(44_100, 48_000, 88_200, 96_000, 192_000) } ?: 48_000,
        audioBitDepth = enumPreference(KEY_AUDIO_BIT_DEPTH, AudioBitDepth.PCM_24),
        audioChannels = preferences.getInt(KEY_AUDIO_CHANNELS, 1).coerceIn(1, 2),
        audioBitrateKbps = preferences.getInt(KEY_AUDIO_BITRATE, 128)
            .takeIf { it in setOf(64, 96, 128, 160, 192, 256, 320) } ?: 128,
        audioInputKey = preferences.getString(KEY_AUDIO_INPUT_KEY, null)?.let(::decodeAudioInputKey),
        legacyAudioInputDeviceId = if (preferences.contains(KEY_AUDIO_INPUT_KEY)) null
            else preferences.getInt(KEY_AUDIO_INPUT, NO_DEVICE).takeUnless { it == NO_DEVICE },
        audioInputLossPolicy = enumPreference(KEY_AUDIO_INPUT_LOSS_POLICY, AudioInputLossPolicy.STOP_TAKE),
        audioListening = loadAudioListening(),
        audioMeter = loadAudioMeter(),
        geotaggingEnabled = preferences.getBoolean("geotagging-enabled", false),
        productionSlate = loadProductionSlate(),
        gallery = loadGallery(),
        mediaSharing = loadMediaSharing(),
        captureNaming = loadCaptureNaming(),
        playback = loadPlayback(),
        proxy = loadProxy(),
        audioListeningOutputDeviceId = preferences.getInt("audio-listening-output-device-id", NO_DEVICE).takeIf { it > 0 },
        audioSource = enumPreference(KEY_AUDIO_SOURCE, AudioSourceSelection.UNPROCESSED),
        noiseSuppressorEnabled = preferences.getBoolean(KEY_AUDIO_NS, false),
        automaticGainControlEnabled = preferences.getBoolean(KEY_AUDIO_AGC, true),
        audioRecordingGain = loadRecordingGain(),
        acousticEchoCancelerEnabled = preferences.getBoolean(KEY_AUDIO_AEC, false),
        accumulation = loadAccumulation(),
        photoAspect = loadPhotoAspect(),
        monitoring = loadMonitoring(),
        bracket = com.librestatic.opencinecam.camera.BracketSelection(
            preferences.getInt(KEY_BRACKET_COUNT, 3).takeIf { it in setOf(3, 5, 7, 9) } ?: 3,
            enumPreference(KEY_BRACKET_STEP, com.librestatic.opencinecam.camera.BracketStep.TWO_EV)),
        burstCount = preferences.getInt(KEY_BURST, 5).coerceIn(3, 10),
        videoBitrateMbps = preferences.getInt(KEY_BITRATE, 20).takeIf { it in setOf(12, 20, 40) } ?: 20,
        flashEnabled = preferences.getBoolean(KEY_FLASH, false),
        photoFlash = com.librestatic.opencinecam.camera.PhotoFlashSelection(
            enumPreference(KEY_PHOTO_FLASH, com.librestatic.opencinecam.camera.PhotoFlashMode.OFF),
            preferences.getInt(KEY_PHOTO_FLASH_STRENGTH, 0).takeIf { it > 0 },
        ),
        photoFormat = enumPreference(KEY_PHOTO_FORMAT, StillPhotoFormat.JPEG).takeUnless { it == StillPhotoFormat.DNG } ?: StillPhotoFormat.JPEG,
        photoQuality = preferences.getInt(KEY_PHOTO_QUALITY, 95).takeIf { it in 1..100 } ?: 95,
        torchStrengthLevel = preferences.getInt(KEY_TORCH_STRENGTH, 0).takeIf { it > 0 },
        zebraEnabled = preferences.getBoolean("zebra-enabled", false),
        peakingEnabled = preferences.getBoolean("peaking-enabled", false),
        histogramEnabled = preferences.getBoolean(KEY_HISTOGRAM, true),
        histogramMode = enumPreference(KEY_HISTOGRAM_MODE, HistogramMode.RGB),
        compositionGridEnabled = preferences.getBoolean(KEY_COMPOSITION_GRID, true),
        compositionGridMode = enumPreference(KEY_COMPOSITION_GRID_MODE, CompositionGridMode.THIRDS),
        horizonLevelEnabled = preferences.getBoolean(KEY_HORIZON_LEVEL, false),
        tapExposureMeteringEnabled = preferences.getBoolean(KEY_TAP_EXPOSURE_METERING, true),
        logViewAssistEnabled = preferences.getBoolean(KEY_LOG_VIEW_ASSIST, false),
        logGreyReference = enumPreference(KEY_LOG_GREY_REFERENCE, OpenCineLogGreyReference.NATIVE),
        modeSelectorStyle = migratedModeSelectorStyle(),
        translucentChrome = preferences.getBoolean(KEY_TRANSLUCENT_CHROME, false),
        viewfinderScale = enumPreference(KEY_VIEWFINDER_SCALE, ViewfinderScale.FIT),
        chromeOpacity = clampChromeOpacity(preferences.getFloat(KEY_CHROME_OPACITY, DEFAULT_CHROME_OPACITY)),
        recordingGeometryMode = enumPreference(KEY_RECORDING_GEOMETRY_MODE, RecordingGeometryMode.COMPATIBLE),
        videoWidth = preferences.getInt(KEY_VIDEO_WIDTH, 1920).takeUnless { it <= 0 } ?: 1920,
        videoHeight = preferences.getInt(KEY_VIDEO_HEIGHT, 1080).takeUnless { it <= 0 } ?: 1080,
        videoFps = preferences.getInt(KEY_VIDEO_FPS, 30).takeUnless { it <= 0 } ?: 30,
        logWidth = preferences.getInt(KEY_LOG_WIDTH, 1920).takeUnless { it <= 0 } ?: 1920,
        logHeight = preferences.getInt(KEY_LOG_HEIGHT, 1080).takeUnless { it <= 0 } ?: 1080,
        logFps = preferences.getInt(KEY_LOG_FPS, 30).takeUnless { it <= 0 } ?: 30,
        zoomLensSwitchMode = enumPreference(KEY_ZOOM_LENS_SWITCH_MODE, ZoomLensSwitchMode.MANUAL_PRESETS),
        focusPullDurationMs = preferences.getLong(KEY_FOCUS_PULL_DURATION_MS, 2000L).coerceIn(500L, 10_000L),
        focusPullEasing = enumPreference(KEY_FOCUS_PULL_EASING, FocusPullEasing.EASE_IN_OUT),
        anamorphicSqueeze = enumPreference(KEY_ANAMORPHIC_SQUEEZE, AnamorphicSqueeze.NONE),
        anamorphicOutputMode = enumPreference(KEY_ANAMORPHIC_OUTPUT_MODE, AnamorphicOutputMode.SQUEEZED),
        timelapseIntervalMs = preferences.getLong(KEY_TIMELAPSE_INTERVAL_MS, 500L).coerceIn(100L, 3_600_000L),
        timelapseLimitMode = enumPreference(KEY_TIMELAPSE_LIMIT_MODE, TimeLapseLimitMode.UNLIMITED),
        timelapseFrameCount = preferences.getInt(KEY_TIMELAPSE_FRAME_COUNT, 300).coerceIn(2, 100_000),
        timelapseDurationMs = preferences.getLong(KEY_TIMELAPSE_DURATION_MS, 3_600_000L).coerceIn(1_000L, 86_400_000L),
        timelapseWidth = preferences.getInt(KEY_TIMELAPSE_WIDTH, 1920).takeUnless { it <= 0 } ?: 1920,
        timelapseHeight = preferences.getInt(KEY_TIMELAPSE_HEIGHT, 1080).takeUnless { it <= 0 } ?: 1080,
        timelapseFps = projectRate(KEY_TIMELAPSE_FPS, "timelapse-project-denominator").first,
        timelapseFpsDenominator = projectRate(KEY_TIMELAPSE_FPS, "timelapse-project-denominator").second,
        videoOffSpeed = preferences.getBoolean("video-off-speed", false),
        videoProjectNumerator = projectRate("video-project-numerator", "video-project-denominator").first,
        videoProjectDenominator = projectRate("video-project-numerator", "video-project-denominator").second,
        afLockBehavior = enumPreference(KEY_AF_LOCK_BEHAVIOR, AfLockBehavior.FREEZE_CURRENT),
        timecodeEnabled = preferences.getBoolean("timecode-enabled", false),
        timecodeRememberPosition = preferences.getBoolean("timecode-remember-position", true),
        timecodeResetRevision = preferences.getInt("timecode-reset-revision", 0).coerceAtLeast(0),
        timecodeMode = enumPreference("timecode-mode", TimecodeMode.RECORD_RUN),
        timecodeNominalFps = preferences.getInt("timecode-nominalfps", 30).takeIf { it in setOf(24, 25, 30, 50, 60) } ?: 30,
        timecodeDropFrame = preferences.getBoolean("timecode-dropframe", false),
        timecodeStartHours = preferences.getInt("timecode-starthours", 1).coerceIn(0, 23),
        timecodeStartMinutes = preferences.getInt("timecode-startminutes", 0).coerceIn(0, 59),
        timecodeStartSeconds = preferences.getInt("timecode-startseconds", 0).coerceIn(0, 59),
        timecodeStartFrames = preferences.getInt("timecode-startframes", 0).coerceIn(0, 59),
    ).normalizedTimecode()

    override fun save(settings: CameraSettings) {
        preferences.edit()
            .putString("operator-button-1", settings.operation.button1.name)
            .putString("operator-button-2", settings.operation.button2.name)
            .putString("operator-button-3", settings.operation.button3.name)
            .putString("operator-volume-up", settings.operation.volumeUp.name)
            .putString("operator-volume-down", settings.operation.volumeDown.name)
            .putString("operator-startup-mode", settings.operation.startupMode.name)
            .putBoolean("operator-restore-exposure-wb", settings.operation.restoreExposureWhiteBalance)
            .putBoolean("operator-restore-torch", settings.operation.restoreTorch)
            .putBoolean("operator-lock-during-take", settings.operation.lockDuringTake)
            .putString("subject-mode", settings.subjectDisplay.mode.name)
            .putBoolean("subject-preview-mirror", settings.subjectDisplay.previewMirror)
            .putBoolean("subject-preview-view-assist", settings.subjectDisplay.previewViewAssist)
            .putInt("self-timer-seconds", settings.subjectDisplay.selfTimerSeconds)
            .putBoolean("self-minimal-controls", settings.subjectDisplay.selfMinimalControls)
            .putFloat("subject-brightness", settings.subjectDisplay.brightness)
            .putBoolean("subject-touch-locked", settings.subjectDisplay.touchLocked)
            .putBoolean("subject-show-status", settings.subjectDisplay.showStatus)
            .putString("subject-cue", settings.subjectDisplay.operatorCue)
            .putString("subject-script", settings.subjectDisplay.prompterText)
            .putInt("subject-font-sp", settings.subjectDisplay.prompterFontSp)
            .putInt("subject-speed", settings.subjectDisplay.prompterSpeedDpPerSecond)
            .putBoolean("subject-paused", settings.subjectDisplay.prompterPaused)
            .putBoolean("fold-continue-recording", settings.subjectDisplay.continueRecordingOnFold)
            .putBoolean("fold-adapt", settings.subjectDisplay.adaptToHinge)
            .putBoolean("fold-swap", settings.subjectDisplay.swapPanes)
            .putBoolean("subject-preview-bands", settings.subjectDisplay.previewRecordedAreaBands)
            .putString("subject-preview-guide", settings.subjectDisplay.previewGuide.name)
            .putBoolean("subject-preview-audio-meter", settings.subjectDisplay.previewAudioMeter)
            .putBoolean("subject-tally-border", settings.subjectDisplay.tallyBorder)
            .putBoolean("subject-giant-countdown", settings.subjectDisplay.giantCountdown)
            .putInt("subject-fill-kelvin", settings.subjectDisplay.fillLightKelvin)
            .putInt("subject-fill-tint", settings.subjectDisplay.fillLightTint)
            .putInt("subject-fill-timeout-seconds", settings.subjectDisplay.fillLightTimeoutSeconds)
            .putString("subject-interview-questions", JsonArray(settings.subjectDisplay.interviewQuestions.map(::JsonPrimitive)).toString())
            .putString("subject-slate-fields", SubjectSlateField.entries.filter { it in settings.subjectDisplay.slateFields }.joinToString(",") { it.name })
            .putBoolean("subject-slate-sync-flash", settings.subjectDisplay.slateSyncFlash)
            .putBoolean("subject-slate-sync-beep", settings.subjectDisplay.slateSyncBeep)
            .putBoolean("subject-out-of-frame", settings.subjectDisplay.outOfFrameWarning)
            .putInt("subject-out-of-frame-delay", settings.subjectDisplay.outOfFrameDelaySeconds)
            .putString("image-stabilization", settings.imageProcessing.stabilization?.name ?: "DEFAULT")
            .putString("image-noise-reduction", settings.imageProcessing.noiseReduction.name)
            .putString("image-edge-enhancement", settings.imageProcessing.edge.name)
            .putString("exposure-mode", settings.exposure.mode.name)
            .putInt("exposure-iso", settings.exposure.iso)
            .putLong("exposure-time-ns", settings.exposure.timeNs)
            .putString("shutter-unit", settings.exposure.shutterUnit.name)
            .putInt("shutter-angle-tenths", settings.exposure.angleTenths)
            .putString("antibanding", settings.exposure.antibanding.name)
            .putString("recording-white-balance", settings.recordingWhiteBalance.name)
            .putString("white-balance-kind", when (settings.whiteBalance) {
                is WhiteBalanceSelection.Auto -> "AUTO"
                is WhiteBalanceSelection.Kelvin -> "KELVIN"
                is WhiteBalanceSelection.Preset -> "PRESET"
            })
            .putInt("white-balance-kelvin", (settings.whiteBalance as? WhiteBalanceSelection.Kelvin)?.kelvin ?: 5600)
            .putInt("white-balance-tint", (settings.whiteBalance as? WhiteBalanceSelection.Kelvin)?.tint ?: 0)
            .putInt("white-balance-preset", (settings.whiteBalance as? WhiteBalanceSelection.Preset)?.awbMode ?: 1)
            .putBoolean(KEY_AUDIO, settings.audioEnabled)
            .putString(KEY_AUDIO_FORMAT, settings.audioOutputFormat.name)
            .putInt(KEY_AUDIO_SAMPLE_RATE, settings.audioSampleRateHz)
            .putString(KEY_AUDIO_BIT_DEPTH, settings.audioBitDepth.name)
            .putInt(KEY_AUDIO_CHANNELS, settings.audioChannels)
            .putInt(KEY_AUDIO_BITRATE, settings.audioBitrateKbps)
            .also { editor ->
                // Until the first probe migrates it, the legacy id stays the only record of the choice.
                if (settings.legacyAudioInputDeviceId != null) editor.putInt(KEY_AUDIO_INPUT, settings.legacyAudioInputDeviceId)
                else editor.remove(KEY_AUDIO_INPUT).putString(KEY_AUDIO_INPUT_KEY, settings.audioInputKey?.let(::encodeAudioInputKey).orEmpty())
            }
            .putString(KEY_AUDIO_INPUT_LOSS_POLICY, settings.audioInputLossPolicy.name)
            .putInt("proxy-max-long-edge", settings.proxy.maxLongEdge)
            .putInt("proxy-video-bitrate-mbps", settings.proxy.videoBitrateMbps)
            .putBoolean("playback-muted", settings.playback.muted)
            .putBoolean("playback-loop", settings.playback.loop)
            .putBoolean("playback-show-frame-position", settings.playback.showFramePosition)
            .putString("review-log-view", settings.playback.logView.name)
            .putBoolean("review-native-surface", settings.playback.nativeSurfaceFrames)
            .putBoolean("geotagging-enabled", settings.geotaggingEnabled)
            .putBoolean("capture-naming-enabled", settings.captureNaming.enabled)
            .putString("capture-naming-template", settings.captureNaming.template)
            .putString("media-share-content", settings.mediaSharing.content.name)
            .putString("media-share-metadata", settings.mediaSharing.metadata.name)
            .putBoolean("media-share-include-lut", settings.mediaSharing.includeReferencedLut)
            .putString("gallery-kind", settings.gallery.kind.name)
            .putBoolean("gallery-newest-first", settings.gallery.newestFirst)
            .putBoolean("gallery-good-takes-only", settings.gallery.goodTakesOnly)
            .putBoolean("gallery-show-slate", settings.gallery.showSlate)
            .putBoolean("gallery-show-technical", settings.gallery.showTechnical)
            .putBoolean("gallery-auto-thumbnails", settings.gallery.autoThumbnails)
            .putString("slate-project", settings.productionSlate.project)
            .putString("slate-camera", settings.productionSlate.camera)
            .putString("slate-scene", settings.productionSlate.scene)
            .putString("slate-reel", settings.productionSlate.reel)
            .putString("slate-lens", settings.productionSlate.lens)
            .putInt("slate-take-number", settings.productionSlate.takeNumber)
            .putString("slate-location", settings.productionSlate.location.name)
            .putString("slate-time-of-day", settings.productionSlate.timeOfDay.name)
            .putBoolean("slate-good-take", settings.productionSlate.goodTake)
            .putBoolean("slate-auto-increment", settings.productionSlate.autoIncrementTake)
            .putBoolean("audio-meter-visible", settings.audioMeter.visible)
            .putString("audio-meter-mode", settings.audioMeter.mode.name)
            .putInt("audio-meter-vu-reference", settings.audioMeter.vuReferenceDbfs)
            .putInt("audio-meter-peak-hold-ms", settings.audioMeter.peakHoldMs)
            .putBoolean("audio-meter-show-values", settings.audioMeter.showValues)
            .putBoolean("audio-listening-enabled", settings.audioListening.enabled)
            .putInt("audio-listening-volume", settings.audioListening.volumePercent)
            .putString("audio-listening-output", settings.audioListening.output.name)
            .putInt("audio-listening-output-device-id", settings.audioListeningOutputDeviceId ?: NO_DEVICE)
            .putString(KEY_AUDIO_SOURCE, settings.audioSource.name)
            .putBoolean(KEY_AUDIO_NS, settings.noiseSuppressorEnabled)
            .putBoolean(KEY_AUDIO_AGC, settings.automaticGainControlEnabled)
            .putBoolean(KEY_AUDIO_RECORDING_GAIN_ENABLED, settings.audioRecordingGain.enabled)
            .putInt(KEY_AUDIO_RECORDING_GAIN_DB, settings.audioRecordingGain.decibels)
            .putBoolean(KEY_AUDIO_AEC, settings.acousticEchoCancelerEnabled)
            .putInt(KEY_BURST, settings.burstCount)
            .putInt(KEY_BITRATE, settings.videoBitrateMbps)
            .putBoolean(KEY_FLASH, settings.flashEnabled)
            .putBoolean("photo-aspect-enabled", settings.photoAspect.enabled)
            .putInt("photo-aspect-width", settings.photoAspect.width)
            .putInt("photo-aspect-height", settings.photoAspect.height)
            .putString("accumulation-mode", settings.accumulation.mode.name)
            .putLong("accumulation-duration-ms", settings.accumulation.durationMs)
            .putLong("accumulation-interval-ms", settings.accumulation.intervalMs)
            .putInt("accumulation-max-edge", settings.accumulation.maxEdge)
            .putInt("accumulation-stars-threshold", settings.accumulation.starsThreshold)
            .putInt(KEY_BRACKET_COUNT, settings.bracket.count)
            .putString(KEY_BRACKET_STEP, settings.bracket.step.name)
            .putString(KEY_PHOTO_FORMAT, settings.photoFormat.name)
            .putInt(KEY_PHOTO_QUALITY, settings.photoQuality)
            .putString(KEY_PHOTO_FLASH, settings.photoFlash.mode.name)
            .putInt(KEY_PHOTO_FLASH_STRENGTH, settings.photoFlash.strength ?: 0)
            .putInt(KEY_TORCH_STRENGTH, settings.torchStrengthLevel ?: 0)
            .putBoolean("monitor-waveform", settings.monitoring.waveformEnabled)
            .putBoolean("monitor-vectorscope", settings.monitoring.vectorscopeEnabled)
            .putBoolean("monitor-false-color", settings.monitoring.falseColorEnabled)
            .putInt("monitor-zebra-high", settings.monitoring.zebraHighPercent)
            .putBoolean("monitor-zebra-shadow", settings.monitoring.zebraShadowEnabled)
            .putInt("monitor-zebra-low", settings.monitoring.zebraLowPercent)
            .putInt("monitor-peaking-threshold", settings.monitoring.peakingThreshold)
            .putInt("monitor-opacity", settings.monitoring.opacityPercent)
            .putString("monitor-zebra-color", settings.monitoring.zebraColor.name)
            .putString("monitor-peaking-color", settings.monitoring.peakingColor.name)
            .putString("monitor-luma-color", settings.monitoring.lumaColor.name)
            .putString("monitor-false-palette", settings.monitoring.falseColorPalette.name)
            .putInt("monitor-false-black", settings.monitoring.falseColorBlackPercent)
            .putInt("monitor-false-shadow", settings.monitoring.falseColorShadowPercent)
            .putInt("monitor-false-highlight", settings.monitoring.falseColorHighlightPercent)
            .putInt("monitor-false-clip", settings.monitoring.falseColorClipPercent)
            .putInt("monitor-refresh-hz", settings.monitoring.refreshHz)
            .putString("monitor-aspect-guide", settings.monitoring.aspectGuide.name)
            .putBoolean("monitor-safe-enabled", settings.monitoring.safeAreaEnabled)
            .putInt("monitor-safe-percent", settings.monitoring.safeAreaPercent)
            .putBoolean("zebra-enabled", settings.zebraEnabled)
            .putBoolean("peaking-enabled", settings.peakingEnabled)
            .putBoolean(KEY_HISTOGRAM, settings.histogramEnabled)
            .putString(KEY_HISTOGRAM_MODE, settings.histogramMode.name)
            .putBoolean(KEY_COMPOSITION_GRID, settings.compositionGridEnabled)
            .putString(KEY_COMPOSITION_GRID_MODE, settings.compositionGridMode.name)
            .putBoolean(KEY_HORIZON_LEVEL, settings.horizonLevelEnabled)
            .putBoolean(KEY_TAP_EXPOSURE_METERING, settings.tapExposureMeteringEnabled)
            .putBoolean(KEY_LOG_VIEW_ASSIST, settings.logViewAssistEnabled)
            .putString(KEY_LOG_GREY_REFERENCE, settings.logGreyReference.name)
            .putString(KEY_MODE_SELECTOR_STYLE, settings.modeSelectorStyle.name)
            .putBoolean(KEY_TRANSLUCENT_CHROME, settings.translucentChrome)
            .putString(KEY_VIEWFINDER_SCALE, settings.viewfinderScale.name)
            .putFloat(KEY_CHROME_OPACITY, clampChromeOpacity(settings.chromeOpacity))
            .putString(KEY_RECORDING_GEOMETRY_MODE, settings.recordingGeometryMode.name)
            .putInt(KEY_VIDEO_WIDTH, settings.videoWidth)
            .putInt(KEY_VIDEO_HEIGHT, settings.videoHeight)
            .putInt(KEY_VIDEO_FPS, settings.videoFps)
            .putInt(KEY_LOG_WIDTH, settings.logWidth)
            .putInt(KEY_LOG_HEIGHT, settings.logHeight)
            .putInt(KEY_LOG_FPS, settings.logFps)
            .putString(KEY_ZOOM_LENS_SWITCH_MODE, settings.zoomLensSwitchMode.name)
            .putLong(KEY_FOCUS_PULL_DURATION_MS, settings.focusPullDurationMs)
            .putString(KEY_FOCUS_PULL_EASING, settings.focusPullEasing.name)
            .putString(KEY_ANAMORPHIC_SQUEEZE, settings.anamorphicSqueeze.name)
            .putString(KEY_ANAMORPHIC_OUTPUT_MODE, settings.anamorphicOutputMode.name)
            .putLong(KEY_TIMELAPSE_INTERVAL_MS, settings.timelapseIntervalMs)
            .putString(KEY_TIMELAPSE_LIMIT_MODE, settings.timelapseLimitMode.name)
            .putInt(KEY_TIMELAPSE_FRAME_COUNT, settings.timelapseFrameCount)
            .putLong(KEY_TIMELAPSE_DURATION_MS, settings.timelapseDurationMs)
            .putInt(KEY_TIMELAPSE_WIDTH, settings.timelapseWidth)
            .putInt(KEY_TIMELAPSE_HEIGHT, settings.timelapseHeight)
            .putInt(KEY_TIMELAPSE_FPS, settings.timelapseFps)
            .putInt("timelapse-project-denominator", settings.timelapseFpsDenominator)
            .putBoolean("video-off-speed", settings.videoOffSpeed)
            .putInt("video-project-numerator", settings.videoProjectNumerator)
            .putInt("video-project-denominator", settings.videoProjectDenominator)
            .putString(KEY_AF_LOCK_BEHAVIOR, settings.afLockBehavior.name)
            .putBoolean("timecode-enabled", settings.timecodeEnabled)
            .putBoolean("timecode-remember-position", settings.timecodeRememberPosition)
            .putInt("timecode-reset-revision", settings.timecodeResetRevision)
            .putString("timecode-mode", settings.timecodeMode.name)
            .putInt("timecode-nominalfps", settings.timecodeNominalFps)
            .putBoolean("timecode-dropframe", settings.timecodeDropFrame)
            .putInt("timecode-starthours", settings.timecodeStartHours)
            .putInt("timecode-startminutes", settings.timecodeStartMinutes)
            .putInt("timecode-startseconds", settings.timecodeStartSeconds)
            .putInt("timecode-startframes", settings.timecodeStartFrames)
            .apply()
    }

    private fun loadProxy() = runCatching {
        ProxySettings(
            maxLongEdge = preferences.getInt("proxy-max-long-edge", 1280),
            videoBitrateMbps = preferences.getInt("proxy-video-bitrate-mbps", 3),
        )
    }.getOrDefault(ProxySettings())

    private fun loadPlayback() = runCatching {
        PlaybackSettings(preferences.getBoolean("playback-muted", false),
            preferences.getBoolean("playback-loop", false), preferences.getBoolean("playback-show-frame-position", true),
            com.librestatic.opencinecam.storage.PreciseLogView.entries.firstOrNull { it.name == preferences.getString("review-log-view", null) }
                ?: com.librestatic.opencinecam.storage.PreciseLogView.FLAT_LOG,
            preferences.getBoolean("review-native-surface", false))
    }.getOrDefault(PlaybackSettings())

    private fun loadCaptureNaming() = runCatching {
        CaptureNamingSettings(preferences.getBoolean("capture-naming-enabled", false),
            preferences.getString("capture-naming-template", CaptureNamingSettings().template) ?: error("Null filename template"))
    }.getOrDefault(CaptureNamingSettings())

    private fun loadMediaSharing() = runCatching {
        MediaSharingSettings(MediaShareContent.valueOf(preferences.getString("media-share-content", "ORIGINALS_AND_METADATA") ?: "ORIGINALS_AND_METADATA"),
            MediaShareMetadata.valueOf(preferences.getString("media-share-metadata", "BOTH") ?: "BOTH"),
            preferences.getBoolean("media-share-include-lut", true))
    }.getOrDefault(MediaSharingSettings())

    private fun loadGallery() = runCatching {
        GallerySettings(GalleryMediaKind.valueOf(preferences.getString("gallery-kind", "ALL") ?: "ALL"),
            preferences.getBoolean("gallery-newest-first", true), preferences.getBoolean("gallery-good-takes-only", false),
            preferences.getBoolean("gallery-show-slate", true), preferences.getBoolean("gallery-show-technical", false),
            preferences.getBoolean("gallery-auto-thumbnails", true))
    }.getOrDefault(GallerySettings())

    private fun loadProductionSlate() = runCatching {
        ProductionSlateSettings(project = preferences.getString("slate-project", "") ?: "",
            camera = preferences.getString("slate-camera", "") ?: "", scene = preferences.getString("slate-scene", "") ?: "",
            reel = preferences.getString("slate-reel", "") ?: "", lens = preferences.getString("slate-lens", "") ?: "",
            takeNumber = preferences.getInt("slate-take-number", 1),
            location = ProductionSlateLocation.valueOf(preferences.getString("slate-location", "UNSPECIFIED") ?: "UNSPECIFIED"),
            timeOfDay = ProductionSlateTimeOfDay.valueOf(preferences.getString("slate-time-of-day", "UNSPECIFIED") ?: "UNSPECIFIED"),
            goodTake = preferences.getBoolean("slate-good-take", false),
            autoIncrementTake = preferences.getBoolean("slate-auto-increment", false))
    }.getOrDefault(ProductionSlateSettings())

    /** Stored as a JSON string array; malformed or oversized data is trimmed to the bounds, never rejected wholesale. */
    private fun loadInterviewQuestions(): List<String> = runCatching {
        (Json.parseToJsonElement(preferences.getString("subject-interview-questions", null) ?: return emptyList()) as JsonArray)
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.take(SUBJECT_INTERVIEW_MAX_QUESTION_LENGTH) }
            .filter(String::isNotBlank).take(SUBJECT_INTERVIEW_MAX_QUESTIONS)
    }.getOrDefault(emptyList())

    private fun loadAudioMeter() = runCatching {
        AudioMeterSettings(preferences.getBoolean("audio-meter-visible", true),
            AudioMeterMode.valueOf(preferences.getString("audio-meter-mode", AudioMeterMode.PEAK_RMS.name) ?: AudioMeterMode.PEAK_RMS.name),
            preferences.getInt("audio-meter-vu-reference", -18), preferences.getInt("audio-meter-peak-hold-ms", 1500),
            preferences.getBoolean("audio-meter-show-values", false))
    }.getOrDefault(AudioMeterSettings())

    private fun loadAudioListening() = runCatching {
        AudioListeningSettings(preferences.getBoolean("audio-listening-enabled", false),
            preferences.getInt("audio-listening-volume", 50),
            AudioListeningOutput.valueOf(preferences.getString("audio-listening-output", AudioListeningOutput.WIRED_USB.name) ?: AudioListeningOutput.WIRED_USB.name))
    }.getOrDefault(AudioListeningSettings())

    private fun loadRecordingGain() = runCatching {
        com.librestatic.opencinecam.camera.DigitalRecordingGain(
            preferences.getBoolean(KEY_AUDIO_RECORDING_GAIN_ENABLED, false),
            preferences.getInt(KEY_AUDIO_RECORDING_GAIN_DB, 0))
    }.getOrDefault(com.librestatic.opencinecam.camera.DigitalRecordingGain())

    private fun loadMonitoring(): MonitoringOptions = runCatching {
        val defaults = MonitoringOptions()
        MonitoringOptions(
            waveformEnabled = preferences.getBoolean("monitor-waveform", defaults.waveformEnabled),
            vectorscopeEnabled = preferences.getBoolean("monitor-vectorscope", defaults.vectorscopeEnabled),
            falseColorEnabled = preferences.getBoolean("monitor-false-color", defaults.falseColorEnabled),
            zebraHighPercent = preferences.getInt("monitor-zebra-high", defaults.zebraHighPercent),
            zebraShadowEnabled = preferences.getBoolean("monitor-zebra-shadow", defaults.zebraShadowEnabled),
            zebraLowPercent = preferences.getInt("monitor-zebra-low", defaults.zebraLowPercent),
            peakingThreshold = preferences.getInt("monitor-peaking-threshold", defaults.peakingThreshold),
            opacityPercent = preferences.getInt("monitor-opacity", defaults.opacityPercent),
            zebraColor = enumPreference("monitor-zebra-color", defaults.zebraColor),
            peakingColor = enumPreference("monitor-peaking-color", defaults.peakingColor),
            lumaColor = enumPreference("monitor-luma-color", defaults.lumaColor),
            falseColorPalette = enumPreference("monitor-false-palette", defaults.falseColorPalette),
            falseColorBlackPercent = preferences.getInt("monitor-false-black", defaults.falseColorBlackPercent),
            falseColorShadowPercent = preferences.getInt("monitor-false-shadow", defaults.falseColorShadowPercent),
            falseColorHighlightPercent = preferences.getInt("monitor-false-highlight", defaults.falseColorHighlightPercent),
            falseColorClipPercent = preferences.getInt("monitor-false-clip", defaults.falseColorClipPercent),
            refreshHz = preferences.getInt("monitor-refresh-hz", defaults.refreshHz),
            aspectGuide = enumPreference("monitor-aspect-guide", defaults.aspectGuide),
            safeAreaEnabled = preferences.getBoolean("monitor-safe-enabled", defaults.safeAreaEnabled),
            safeAreaPercent = preferences.getInt("monitor-safe-percent", defaults.safeAreaPercent),
        )
    }.getOrDefault(MonitoringOptions())

    private fun loadPhotoAspect() = runCatching {
        com.librestatic.opencinecam.camera.PhotoAspectSelection(
            enabled = preferences.getBoolean("photo-aspect-enabled", false),
            width = preferences.getInt("photo-aspect-width", 4),
            height = preferences.getInt("photo-aspect-height", 3))
    }.getOrDefault(com.librestatic.opencinecam.camera.PhotoAspectSelection())

    private fun loadAccumulation(): com.librestatic.opencinecam.camera.AccumulationSelection = runCatching {
        com.librestatic.opencinecam.camera.AccumulationSelection(
            mode = enumPreference("accumulation-mode", com.librestatic.opencinecam.camera.AccumulationMode.LIGHT),
            durationMs = preferences.getLong("accumulation-duration-ms", 10_000L),
            intervalMs = preferences.getLong("accumulation-interval-ms", 250L),
            maxEdge = preferences.getInt("accumulation-max-edge", 2048),
            starsThreshold = preferences.getInt("accumulation-stars-threshold", 16))
    }.getOrDefault(com.librestatic.opencinecam.camera.AccumulationSelection())

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
        /** Legacy: a volatile device id saved by versions before the stable input key. */
        const val KEY_AUDIO_INPUT = "audio-input-device-id"
        const val KEY_AUDIO_INPUT_KEY = "audio-input-key"
        const val KEY_AUDIO_INPUT_LOSS_POLICY = "audio-input-loss-policy"
        const val KEY_AUDIO_SOURCE = "audio-source"
        const val KEY_AUDIO_NS = "audio-noise-suppressor"
        const val KEY_AUDIO_AGC = "audio-automatic-gain-control"
        const val KEY_AUDIO_RECORDING_GAIN_ENABLED = "audio-recording-gain-enabled"
        const val KEY_AUDIO_RECORDING_GAIN_DB = "audio-recording-gain-db"
        const val KEY_AUDIO_AEC = "audio-acoustic-echo-canceler"
        const val KEY_BURST = "burst-count"
        const val KEY_BITRATE = "video-bitrate-mbps"
        const val KEY_FLASH = "flash-enabled"
        const val KEY_BRACKET_COUNT = "bracket-count"
        const val KEY_BRACKET_STEP = "bracket-step"
        const val KEY_PHOTO_FORMAT = "photo-format"
        const val KEY_PHOTO_QUALITY = "photo-quality"
        const val KEY_PHOTO_FLASH = "photo-flash-mode"
        const val KEY_PHOTO_FLASH_STRENGTH = "photo-flash-strength"
        const val KEY_TORCH_STRENGTH = "torch-strength-level"
        const val KEY_HISTOGRAM = "histogram-enabled"
        const val KEY_HISTOGRAM_MODE = "histogram-mode"
        const val KEY_COMPOSITION_GRID = "composition-grid-enabled"
        const val KEY_COMPOSITION_GRID_MODE = "composition-grid-mode"
        const val KEY_HORIZON_LEVEL = "horizon-level-enabled"
        const val KEY_TAP_EXPOSURE_METERING = "tap-exposure-metering-enabled"
        const val KEY_LOG_VIEW_ASSIST = "log-view-assist-enabled"
        const val KEY_LOG_GREY_REFERENCE = "log-grey-reference"
        const val KEY_MODE_SELECTOR_STYLE = "mode-selector-style"
        const val KEY_MODE_SELECTOR_CAROUSEL_MIGRATED = "mode-selector-carousel-migrated-v1"
        const val KEY_TRANSLUCENT_CHROME = "translucent-chrome"
        const val KEY_VIEWFINDER_SCALE = "viewfinder-scale"
        const val KEY_CHROME_OPACITY = "chrome-opacity"
        const val KEY_RECORDING_GEOMETRY_MODE = "recording-geometry-mode"
        const val KEY_VIDEO_WIDTH = "video-geometry-width"
        const val KEY_VIDEO_HEIGHT = "video-geometry-height"
        const val KEY_VIDEO_FPS = "video-geometry-fps"
        const val KEY_LOG_WIDTH = "log-geometry-width"
        const val KEY_LOG_HEIGHT = "log-geometry-height"
        const val KEY_LOG_FPS = "log-geometry-fps"
        const val KEY_ZOOM_LENS_SWITCH_MODE = "zoom-lens-switch-mode"
        const val KEY_FOCUS_PULL_DURATION_MS = "focus-pull-duration-ms"
       const val KEY_FOCUS_PULL_EASING = "focus-pull-easing"
        const val KEY_ANAMORPHIC_SQUEEZE = "anamorphic-squeeze"
        const val KEY_ANAMORPHIC_OUTPUT_MODE = "anamorphic-output-mode"
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

/** "type\naddress\nproduct"; an empty string stores "Auto". Line breaks are not part of any device name. */
internal fun encodeAudioInputKey(key: AudioInputKey): String =
    listOf(key.type.toString(), key.address, key.productName).joinToString("\n") { it.replace('\n', ' ') }

internal fun decodeAudioInputKey(encoded: String): AudioInputKey? {
    val parts = encoded.split('\n', limit = 3)
    if (parts.size != 3) return null
    val type = parts[0].toIntOrNull() ?: return null
    return AudioInputKey(type, parts[2], parts[1])
}
