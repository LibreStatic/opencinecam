/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.*
import java.util.UUID

/** Lens identity and calibrated focus marks are never assumed portable. */
data class CameraPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val settings: CameraSettings,
    val mode: CaptureMode = CaptureMode.VIDEO,
    val focusDiopters: Float? = null,
    val zoomRatio: Float = 1f,
) {
    init {
        require(id.length in 1..64 && id.none(Char::isISOControl)) { "Invalid local preset ID" }
        require(name.isNotBlank() && name.length <= 64 && name.none { it.isISOControl() }) { "Preset name must have 1–64 readable characters" }
        require(focusDiopters == null || (focusDiopters.isFinite() && focusDiopters in 0f..100f)) { "Invalid focus distance" }
        require(zoomRatio.isFinite() && zoomRatio in 0.1f..200f) { "Invalid zoom ratio" }
    }
}

/** Flat typed settings schema; never deserialize app/device identifiers or arbitrary file paths. */
object CameraPresetCodec {
    const val VERSION = 20
    const val MAX_BYTES = 65_536
    // Explicit V2 registry: adding an application preference never exports it accidentally.
    // A schema revision and privacy review are required before extending this registry.
    private val portableV2 = """
        af-lock-behavior
        anamorphic-output-mode
        anamorphic-squeeze
        antibanding
        audio-acoustic-echo-canceler
        audio-automatic-gain-control
        audio-bit-depth
        audio-bitrate-kbps
        audio-channels
        audio-enabled
        audio-noise-suppressor
        audio-output-format
        audio-sample-rate-hz
        audio-source
        burst-count
        composition-grid-enabled
        composition-grid-mode
        exposure-iso
        exposure-mode
        exposure-time-ns
        flash-enabled
        focus-pull-duration-ms
        focus-pull-easing
        fold-adapt
        fold-continue-recording
        fold-swap
        histogram-enabled
        histogram-mode
        horizon-level-enabled
        image-edge-enhancement
        image-noise-reduction
        image-stabilization
        log-geometry-fps
        log-geometry-height
        log-geometry-width
        log-view-assist-enabled
        mode-selector-style
        peaking-enabled
        recording-geometry-mode
        recording-white-balance
        self-minimal-controls
        self-timer-seconds
        shutter-angle-tenths
        shutter-unit
        subject-brightness
        subject-font-sp
        subject-mode
        subject-paused
        subject-preview-mirror
        subject-preview-view-assist
        subject-show-status
        subject-speed
        subject-touch-locked
        tap-exposure-metering-enabled
        timecode-dropframe
        timecode-enabled
        timecode-mode
        timecode-nominalfps
        timecode-startframes
        timecode-starthours
        timecode-startminutes
        timecode-startseconds
        timelapse-duration-ms
        timelapse-frame-count
        timelapse-geometry-fps
        timelapse-geometry-height
        timelapse-geometry-width
        timelapse-interval-ms
        timelapse-limit-mode
        torch-strength-level
        video-bitrate-mbps
        video-geometry-fps
        video-geometry-height
        video-geometry-width
        white-balance-kelvin
        white-balance-kind
        white-balance-preset
        white-balance-tint
        zebra-enabled
        zoom-lens-switch-mode
    """.trimIndent().lines().toSet()
    private val portableV3 = portableV2 + setOf(
        "operator-button-1", "operator-button-2", "operator-button-3", "operator-volume-up", "operator-volume-down",
        "operator-startup-mode", "operator-restore-exposure-wb", "operator-restore-torch", "operator-lock-during-take",
    )
    private val portableV4 = portableV3 + setOf("timelapse-project-denominator", "video-off-speed", "video-project-numerator", "video-project-denominator")
    private val portableV5 = portableV4 + setOf("photo-flash-mode", "photo-flash-strength")
    private val portableV6 = portableV5 + setOf("photo-format", "photo-quality")
    private val portableV7 = portableV6 + setOf("bracket-count", "bracket-step")
    private val portableV8 = portableV7 + setOf("accumulation-mode", "accumulation-duration-ms", "accumulation-interval-ms", "accumulation-max-edge", "accumulation-stars-threshold")
    private val portableV9 = portableV8 + setOf("photo-aspect-enabled", "photo-aspect-width", "photo-aspect-height")
    private val portableV10 = portableV9 + setOf("monitor-waveform", "monitor-vectorscope", "monitor-false-color", "monitor-zebra-high", "monitor-zebra-shadow", "monitor-zebra-low", "monitor-peaking-threshold", "monitor-opacity", "monitor-zebra-color", "monitor-peaking-color", "monitor-luma-color", "monitor-false-palette", "monitor-false-black", "monitor-false-shadow", "monitor-false-highlight", "monitor-false-clip", "monitor-refresh-hz", "monitor-aspect-guide", "monitor-safe-enabled", "monitor-safe-percent")
    private val portableV11 = portableV10 + setOf("audio-recording-gain-enabled", "audio-recording-gain-db")
    private val portableV12 = portableV11 + setOf("audio-listening-enabled", "audio-listening-volume", "audio-listening-output")
    private val portableV13 = portableV12 + setOf("audio-meter-visible", "audio-meter-mode", "audio-meter-vu-reference", "audio-meter-peak-hold-ms", "audio-meter-show-values")
    private val portableV14 = portableV13 + setOf("slate-project", "slate-camera", "slate-scene", "slate-reel", "slate-lens", "slate-take-number", "slate-location", "slate-time-of-day", "slate-good-take", "slate-auto-increment")
    private val portableV15 = portableV14 + setOf("gallery-kind", "gallery-newest-first", "gallery-good-takes-only", "gallery-show-slate", "gallery-show-technical")
    private val portableV16 = portableV15 + setOf("media-share-content", "media-share-metadata", "media-share-include-lut")
    private val portableV17 = portableV16 + setOf("capture-naming-enabled", "capture-naming-template")
    private val portableV18 = portableV17 + setOf("playback-muted", "playback-loop", "playback-show-frame-position")
    private val portableV19 = portableV18 + setOf("gallery-auto-thumbnails")
    private val portableV20 = portableV19 + setOf("translucent-chrome", "viewfinder-scale", "chrome-opacity")
    /**
     * The exact key set a payload of [version] must carry. Every published version stays frozen
     * here, so a payload written by an older build keeps decoding without its keys being guessed.
     */
    internal fun portableKeysFor(version: Int): Set<String> = when {
        version < 3 -> portableV2; version == 3 -> portableV3; version == 4 -> portableV4
        version == 5 -> portableV5; version == 6 -> portableV6; version == 7 -> portableV7
        version == 8 -> portableV8; version == 9 -> portableV9; version == 10 -> portableV10
        version == 11 -> portableV11; version == 12 -> portableV12; version == 13 -> portableV13
        version == 14 -> portableV14; version == 15 -> portableV15; version == 16 -> portableV16
        version == 17 -> portableV17; version == 18 -> portableV18; version == 19 -> portableV19
        else -> portableV20
    }
    private fun snapshot(settings: CameraSettings): Map<String, Any> {
        val memory = PresetPreferences(); CameraSettingsStore(memory).save(settings)
        return memory.all.filterKeys { it in portableV20 }.mapValues { requireNotNull(it.value) }.toSortedMap()
    }
    private val defaults by lazy { snapshot(CameraSettings()) }
    val portableKeys: Set<String> get() = defaults.keys
    private fun primitive(value: Any): JsonPrimitive = when (value) {
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> error("Unknown preference type")
    }
    fun settingsJson(settings: CameraSettings): JsonObject = JsonObject(snapshot(settings).mapValues { primitive(it.value) })
    fun encode(preset: CameraPreset): String = buildJsonObject {
        put("format", "OpenCineCamPreset"); put("version", VERSION); put("name", preset.name)
        put("mode", preset.mode.name); put("focusDiopters", preset.focusDiopters?.let(::JsonPrimitive) ?: JsonNull)
        put("zoomRatio", preset.zoomRatio); put("settings", settingsJson(preset.settings))
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }

    fun decode(text: String): CameraPreset {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Preset exceeds 64 KiB" }
        rejectDuplicateKeys(text)
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Expected preset object")
        val version = root["version"]?.jsonPrimitive?.also { require(!it.isString) }?.intOrNull ?: error("Missing version")
        require(version in 1..VERSION) { "Unsupported preset version" }
        val allowed = if (version == 1) setOf("format", "version", "name", "settings") else setOf("format", "version", "name", "settings", "mode", "focusDiopters", "zoomRatio")
        require(root.keys == allowed) { "Unexpected or missing preset fields" }
        require(root["format"] == JsonPrimitive("OpenCineCamPreset")) { "Unknown preset format" }
        val name = root.getValue("name").jsonPrimitive.also { require(it.isString) }.content
        val entries = root["settings"] as? JsonObject ?: error("Expected settings object")
        val acceptedKeys = portableKeysFor(version)
        require(entries.keys.all { it in acceptedKeys }) { "Unknown or nonportable setting" }
        if (version >= 2) require(entries.keys == acceptedKeys) { "Incomplete version $version settings" }
        val values = defaults.toMutableMap()
        for ((key, element) in entries) {
            val p = element as? JsonPrimitive ?: error("Settings must be scalar")
            require(p != JsonNull)
            values[key] = when (defaults.getValue(key)) {
                is String -> p.also { require(it.isString && it.content.length <= 128) }.content
                is Boolean -> p.also { require(!it.isString) }.boolean
                is Int -> p.also { require(!it.isString) }.int
                is Long -> p.also { require(!it.isString) }.long
                is Float -> p.also { require(!it.isString) }.float.also { require(it.isFinite()) }
                else -> error("Unknown preference type")
            }
            if (key.endsWith("width") || key.endsWith("height")) require((values[key] as Int) in 1..32_768)
            if (key.endsWith("-fps")) require((values[key] as Int) in 1..(if (version >= 4 && key == "timelapse-geometry-fps") 60000 else 1000))
        }
        val decoded = CameraSettingsStore(PresetPreferences(values)).load()
        require(!decoded.timecodeDropFrame || decoded.timecodeNominalFps in setOf(30, 60)) { "Drop-frame requires nominal 30 or 60 fps" }
        require(decoded.timecodeStartFrames < decoded.timecodeNominalFps) { "Timecode frame exceeds nominal rate" }
        require(snapshot(decoded) == values) { "Invalid, noncanonical or out-of-range settings" }
        return CameraPreset(name = name, settings = decoded,
            mode = if (version == 1) CaptureMode.VIDEO else CaptureMode.valueOf(root.getValue("mode").jsonPrimitive.also { require(it.isString) }.content),
            focusDiopters = if (version == 1 || root["focusDiopters"] == JsonNull) null else root.getValue("focusDiopters").jsonPrimitive.also { require(!it.isString) }.float,
            zoomRatio = if (version == 1) 1f else root.getValue("zoomRatio").jsonPrimitive.also { require(!it.isString) }.float)
    }

    /** Preserve local routing and script content even when applying a complete portable snapshot. */
    fun mergeLocal(preset: CameraSettings, current: CameraSettings): CameraSettings = preset.copy(
        geotaggingEnabled = current.geotaggingEnabled,
        proxy = current.proxy,
        timecodeRememberPosition = current.timecodeRememberPosition,
        timecodeResetRevision = current.timecodeResetRevision,
        audioInputDeviceId = current.audioInputDeviceId,
        audioListeningOutputDeviceId = current.audioListeningOutputDeviceId,
        // Not yet a portable preset key (needs a schema revision); keep the device value.
        logGreyReference = current.logGreyReference,
        // OCLog2 review view is a viewing habit of this device, not part of a look.
        playback = preset.playback.copy(logView = current.playback.logView),
        subjectDisplay = preset.subjectDisplay.copy(prompterText = current.subjectDisplay.prompterText, operatorCue = current.subjectDisplay.operatorCue))

    fun differences(current: CameraSettings, preset: CameraSettings): List<String> {
        val before = snapshot(current)
        return snapshot(preset).mapNotNull { (key, value) -> if (value == before[key]) null else "$key: ${before[key]} → $value" }
    }

    private fun rejectDuplicateKeys(text: String) {
        val stack = mutableListOf<MutableSet<String>>()
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '[', ']' -> error("Preset arrays are not supported")
                '{' -> { require(stack.size < 4) { "Preset nesting is too deep" }; stack.add(mutableSetOf()) }
                '}' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
                '"' -> {
                    val start = i++
                    while (i < text.length && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                    require(i < text.length) { "Unterminated string" }
                    val end = i + 1
                    var next = end
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (next < text.length && text[next] == ':') {
                        val key = Json.parseToJsonElement(text.substring(start, end)).jsonPrimitive.content
                        require(stack.isNotEmpty() && stack.last().add(key)) { "Duplicate preset key" }
                    }
                }
            }
            i++
        }
    }
}
