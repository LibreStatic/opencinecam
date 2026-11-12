/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import androidx.annotation.StringRes
import androidx.exifinterface.media.ExifInterface
import com.librestatic.opencinecam.core.model.decodeClipSidecar
import com.librestatic.opencinecam.media.ocraw.OcrawFrameIndex
import com.librestatic.opencinecam.playback.ClipMetadata
import com.librestatic.opencinecam.playback.RawDevelop
import com.librestatic.opencinecam.playback.codecLabel
import com.librestatic.opencinecam.playback.durationLabel
import com.librestatic.opencinecam.playback.resolutionLabel
import com.librestatic.opencinecam.storage.LocalMediaArtifact
import com.librestatic.opencinecam.storage.LocalMediaKind
import com.librestatic.opencinecam.storage.OcLogClip
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The inspector's sections, in display order. */
internal enum class DetailGroupKind { FILE, VIDEO, COLOUR, AUDIO, CAMERA, RAW, RECORDING }

/** One label and its pre-formatted value; [key] is stable per take ("path-0", "iso"), [copyable] offers a copy action. */
internal data class DetailRow(@StringRes val label: Int, val value: String, val key: String, val copyable: Boolean = false)
internal data class DetailGroup(val kind: DetailGroupKind, val rows: List<DetailRow>)

/** Everything the inspector lists about a take; [facts] fills the poster chips a RAW take's MediaStore row cannot. */
internal data class TakeTechnicalDetails(val groups: List<DetailGroup>, val facts: GalleryFacts? = null) {
    fun group(kind: DetailGroupKind): DetailGroup? = groups.firstOrNull { it.kind == kind }
}

/** The inspector's read of a take: still reading, or read (null when nothing could be read). */
internal sealed interface TakeDetailsLoad {
    data object Loading : TakeDetailsLoad
    data class Loaded(val details: TakeTechnicalDetails?) : TakeDetailsLoad
}
internal val TakeDetailsLoad?.facts: GalleryFacts? get() = (this as? TakeDetailsLoad.Loaded)?.details?.facts

/** The group's title; a photo's VIDEO group is its image. */
@StringRes internal fun DetailGroupKind.title(take: LocalMediaKind): Int = when (this) {
    DetailGroupKind.FILE -> R.string.media_detail_group_file
    DetailGroupKind.VIDEO -> if (take == LocalMediaKind.PHOTO) R.string.media_detail_group_image else R.string.media_detail_group_video
    DetailGroupKind.COLOUR -> R.string.media_detail_group_colour
    DetailGroupKind.AUDIO -> R.string.media_detail_group_audio
    DetailGroupKind.CAMERA -> R.string.media_detail_group_camera
    DetailGroupKind.RAW -> R.string.media_detail_group_raw
    DetailGroupKind.RECORDING -> R.string.media_detail_group_recording
}

/** Resource text, sizes and dates for the builders; host tests pass fakes. [dateTime] takes epoch milliseconds. */
internal class DetailText(val string: (Int, Array<out Any>) -> String, val size: (Long) -> String, val dateTime: (Long) -> String) {
    fun s(@StringRes id: Int, vararg args: Any): String = string(id, args)
    fun yesNo(value: Boolean) = s(if (value) R.string.media_detail_yes else R.string.media_detail_no)
    fun onOff(value: Boolean) = s(if (value) R.string.media_detail_on else R.string.media_detail_off)
    /** "29.97 fps (requested 30 fps)", or the one value when they agree or one is missing. */
    fun requested(actual: String?, requested: String?): String? = when {
        actual == null -> requested
        requested == null || requested == actual -> actual
        else -> s(R.string.media_detail_requested, actual, requested)
    }
    /** [value] in words when [field] knows it, otherwise as the sidecar wrote it. */
    fun word(field: DetailEnum, value: String?): String? = value?.let { v -> detailWord(field, v)?.let { s(it) } ?: v }
}

/** The sidecar fields whose enum values have words. */
internal enum class DetailEnum { RECORDING_POLICY, CAPTURE_MODE, WHITE_BALANCE, COLOUR_KIND, AUDIO_STORAGE, STABILIZATION }

/** The string for a sidecar enum value, or null when it has none and is shown as written. */
@StringRes internal fun detailWord(field: DetailEnum, value: String): Int? = when (field) {
    DetailEnum.RECORDING_POLICY -> when (value) { "STRICT" -> R.string.recording_policy_strict; "ADAPTIVE" -> R.string.recording_policy_adaptive; else -> null }
    DetailEnum.CAPTURE_MODE -> when (CaptureMode.entries.firstOrNull { it.name == value }) {
        CaptureMode.PHOTO -> R.string.photo_mode
        CaptureMode.RAW_PHOTO -> R.string.raw_photo_mode
        CaptureMode.BURST -> R.string.burst_mode
        CaptureMode.VIDEO -> R.string.video_mode
        CaptureMode.SLOW_MOTION -> R.string.slow_motion_mode
        CaptureMode.TIME_LAPSE -> R.string.time_lapse_mode
        CaptureMode.BRACKET -> R.string.bracket_mode
        CaptureMode.LIGHT_TRAIL -> R.string.light_trail_mode
        CaptureMode.LOG -> R.string.log_mode
        CaptureMode.HLG -> R.string.hlg_mode
        CaptureMode.APV -> R.string.apv_mode
        CaptureMode.RAW_VIDEO -> R.string.raw_video_mode
        null -> null
    }
    DetailEnum.WHITE_BALANCE -> when (value) { "AUTO" -> R.string.media_detail_auto; "KELVIN" -> R.string.media_detail_wb_kelvin; else -> null }
    DetailEnum.COLOUR_KIND -> when (value) {
        "SDR_BT709" -> R.string.media_detail_colour_sdr; "HLG" -> R.string.media_detail_colour_hlg; "OCLog2" -> R.string.media_detail_colour_oclog; else -> null }
    DetailEnum.AUDIO_STORAGE -> when (value) {
        "EMBEDDED_AAC" -> R.string.media_detail_audio_embedded_aac; "SEPARATE_WAV" -> R.string.media_detail_audio_separate_wav
        "SEPARATE_FLAC" -> R.string.media_detail_audio_separate_flac; else -> null }
    DetailEnum.STABILIZATION -> when (value) {
        "ON" -> R.string.media_detail_on; "OFF" -> R.string.media_detail_off; "PREVIEW_STABILIZATION" -> R.string.media_detail_eis_preview; else -> null }
}

// ---- Formatters (locale-neutral numbers) ----

internal fun decimalLabel(value: Double, decimals: Int): String =
    String.format(Locale.ROOT, "%.${decimals}f", value).let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }

/** Up to three decimals, trimmed: "23.976 fps", "30 fps". */
internal fun fpsLabel(fps: Double): String = "${decimalLabel(fps, 3)} fps"

/** "1/48 s" (0.5 s and longer: "1.5 s"), with the shutter angle when [fps] is known: "1/48 s (180°)". */
internal fun shutterLabel(exposureNs: Long, fps: Double? = null): String {
    val seconds = exposureNs / 1e9
    val base = if (seconds >= 0.5) "${decimalLabel(seconds, 1)} s" else "1/${(1 / seconds).roundToLong()} s"
    val angle = fps?.takeIf { it > 0 && it.isFinite() }?.let { (360 * seconds * it).roundToInt() }
    return if (angle != null && angle in 1..360) "$base ($angle°)" else base
}

internal fun shutterRangeLabel(minNs: Long, maxNs: Long, fps: Double? = null): String =
    if (minNs == maxNs) shutterLabel(minNs, fps) else "${shutterLabel(minNs, fps)} – ${shutterLabel(maxNs, fps)}"

internal fun isoLabel(min: Int, max: Int = min): String = if (min == max) "ISO $min" else "ISO $min–$max"

/** Bits per second as "45.2 Mbps". */
internal fun bitrateLabel(bitsPerSecond: Double): String = String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1e6)

/** Bytes per second as "212.4 MB/s". */
internal fun dataRateLabel(bytesPerSecond: Double): String = String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1e6)

/** "6.3 mm (24 mm eq.)". */
internal fun focalLabel(mm: Double, equivalent35mm: Double? = null): String =
    "${decimalLabel(mm, 1)} mm" + (equivalent35mm?.takeIf { it > 0 }?.let { " (${it.roundToInt()} mm eq.)" } ?: "")

internal fun apertureLabel(fNumber: Double): String = "f/${decimalLabel(fNumber, 1)}"

/** Camera2 focus distance in diopters as metres: "2.00 m"; 0 is infinity. */
internal fun focusDistanceLabel(diopters: Double): String =
    if (diopters <= 0.0) "∞" else String.format(Locale.ROOT, "%.2f m", 1.0 / diopters)

internal fun sampleRateLabel(hz: Int): String = "${decimalLabel(hz / 1000.0, 3)} kHz"

internal fun zoomLabel(start: Double, end: Double = start): String =
    if (start == end) "${decimalLabel(start, 2)}×" else "${decimalLabel(start, 2)}×–${decimalLabel(end, 2)}×"

internal fun compressionLabel(ratio: Double): String = String.format(Locale.ROOT, "%.1f:1", ratio)

/** `<volume root>/<relativePath><name>`; null when either the root or the relative path is unknown. */
internal fun artifactPath(volumeRoot: String?, relativePath: String?, name: String): String? {
    val root = volumeRoot?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    val relative = relativePath?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
    return "$root/$relative/$name"
}

private fun channelsLabel(text: DetailText, channels: Int): String = when (channels) {
    1 -> text.s(R.string.media_detail_mono)
    2 -> text.s(R.string.media_detail_stereo)
    else -> text.s(R.string.media_detail_channel_count, channels)
}

/** "AAC", "FLAC", "PCM", else the subtype. */
internal fun audioCodecLabel(mime: String?): String? = when (val m = mime?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }) {
    null -> null
    "audio/mp4a-latm", "audio/aac", "audio/mp4" -> "AAC"
    "audio/flac", "audio/x-flac" -> "FLAC"
    "audio/raw", "audio/wav", "audio/x-wav" -> "PCM"
    "audio/opus" -> "Opus"
    else -> m.substringAfter('/').uppercase(Locale.ROOT)
}

// ---- Inputs ----

/** A take's original and its full path, when MediaStore gave a relative path. */
internal data class DetailFile(val artifact: LocalMediaArtifact, val path: String?)

/** The first audio track of a file, from MediaExtractor. */
internal data class AudioMetadata(val mime: String?, val sampleRateHz: Int?, val channels: Int?, val bitDepth: Int?,
    val float: Boolean = false, val bitRate: Int? = null, val durationUs: Long? = null)

/** What the `opencinecam.hdr-video.v1` sidecar of an HLG take declares. */
internal data class HdrVideoFacts(val transfer: String?, val transferStandard: String?, val primaries: String?, val range: String?,
    val codecName: String?, val codecProfile: String?, val sourceDynamicRange: String?)

internal fun parseHdrVideoSidecar(text: String): HdrVideoFacts? = runCatching {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
    if (root.str("schema") != "opencinecam.hdr-video.v1") return null
    val hdr = root.obj("hdr") ?: return null
    HdrVideoFacts(hdr.str("transfer"), hdr.str("transferStandard"), hdr.str("primaries"), hdr.str("range"),
        hdr.str("codecName"), hdr.str("codecProfile"), hdr.str("sourceDynamicRange"))
}.getOrNull()

/** A sampled value through the take, as the clip sidecar's camera block writes it. */
internal data class ClipRange(val start: Double?, val end: Double?, val min: Double?, val max: Double?) {
    val low: Double? get() = min ?: listOfNotNull(start, end).minOrNull()
    val high: Double? get() = max ?: listOfNotNull(start, end).maxOrNull()
}

/** `resolvedGraph.camera`; absent from sidecars written before it existed. */
internal data class ClipCameraFacts(val cameraId: String? = null, val focalLengthMm: Double? = null, val focalLength35mm: Double? = null,
    val aperture: Double? = null, val requestedExposureMode: String? = null, val reportedExposureMode: String? = null,
    val iso: ClipRange? = null, val exposureTimeNs: ClipRange? = null, val whiteBalanceMode: String? = null,
    val whiteBalanceKelvin: Int? = null, val reportedCctStart: Int? = null, val reportedCctEnd: Int? = null, val tint: Int? = null,
    val afMode: String? = null, val focusDiopters: Double? = null, val zoom: ClipRange? = null, val ois: String? = null,
    val eis: String? = null, val noiseReduction: String? = null, val edge: String? = null, val toneMap: String? = null)

/** The parts of a `.clip.json` the inspector shows; unknown values are null. */
internal data class ClipSidecarFacts(val createdAt: String? = null, val takeStatus: String? = null, val failureReasons: List<String> = emptyList(),
    val recordingPolicy: String? = null, val captureMode: String? = null, val requestedWidth: Int? = null, val requestedHeight: Int? = null,
    val requestedFps: Double? = null, val projectRate: String? = null, val videoCodecPreference: String? = null, val videoBitrateMbps: Int? = null,
    val audioEnabled: Boolean? = null, val audioRequestedFormat: String? = null, val audioRequestedSampleRateHz: Int? = null,
    val audioRequestedBitDepth: Int? = null, val audioRequestedChannels: Int? = null, val lutSelectionId: String? = null,
    val timecodeEnabled: Boolean? = null, val cameraId: String? = null, val routeTruth: String? = null, val routeRequested: String? = null,
    val routeReported: String? = null, val recordingRoute: String? = null, val codecMime: String? = null, val encoderName: String? = null,
    val hardwareAccelerated: Boolean? = null, val softwareEncoder: Boolean? = null, val encodedWidth: Int? = null, val encodedHeight: Int? = null,
    val fps: Double? = null, val rotationDegrees: Int? = null, val colorKind: String? = null, val colorTransfer: String? = null,
    val colorGamut: String? = null, val colorProfile: String? = null, val audioStorage: String? = null, val audioContainer: String? = null,
    val audioSampleRateHz: Int? = null, val audioBitDepth: Int? = null, val deviceProfileId: String? = null,
    val deviceProfileStatus: String? = null, val deviceProfileBuildCheck: String? = null, val appliedQuirks: List<String> = emptyList(),
    val durationMs: Long? = null, val encodedFrames: Long? = null, val camera: ClipCameraFacts? = null)

private fun JsonElement?.known(): JsonElement? = when {
    this == null || this is JsonNull -> null
    this is JsonObject && "knowledge" in this -> if (str("knowledge") == "known") this["value"].known() else null
    else -> this
}
private fun JsonObject.obj(key: String): JsonObject? = this[key].known() as? JsonObject
private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key].known() as? JsonPrimitive)?.takeIf { it !is JsonNull }
private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
private fun JsonObject.num(key: String): Double? = prim(key)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
private fun JsonObject.int(key: String): Int? = num(key)?.takeIf { it == Math.rint(it) && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
private fun JsonObject.long(key: String): Long? = num(key)?.takeIf { it == Math.rint(it) }?.toLong()
private fun JsonObject.bool(key: String): Boolean? = prim(key)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
private fun JsonObject.strings(key: String): List<String> =
    (this[key].known() as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
private fun JsonObject.range(key: String): ClipRange? = obj(key)?.let { ClipRange(it.num("start"), it.num("end"), it.num("min"), it.num("max")) }
    ?.takeIf { it.low != null }

/** A `.clip.json` through [decodeClipSidecar] (schema major checked); null when it is not one. */
internal fun parseClipSidecarFacts(text: String): ClipSidecarFacts? = runCatching {
    val document = decodeClipSidecar(text)
    val intent = document.intent.known() as? JsonObject ?: JsonObject(emptyMap())
    val graph = document.resolvedGraph
    val video = graph.obj("video") ?: JsonObject(emptyMap())
    val color = graph.obj("color")
    val graphAudio = graph.obj("audio")
    val intentAudio = intent.obj("audio")
    val requested = intent.obj("requestedSize")
    val encoded = video.obj("encodedSize")
    val route = graph.obj("route")
    val profile = graph.obj("deviceProfile")
    val camera = graph.obj("camera")?.let { c ->
        val exposure = c.obj("exposureMode"); val wb = c.obj("whiteBalance"); val focus = c.obj("focus")
        val stabilization = c.obj("stabilization"); val processing = c.obj("processing")
        ClipCameraFacts(c.str("cameraId"), c.num("focalLengthMm"), c.num("focalLength35mm"), c.num("aperture"),
            exposure?.str("requested"), exposure?.str("reported"), c.range("iso"), c.range("exposureTimeNs"),
            wb?.str("mode"), wb?.int("kelvin"), wb?.int("reportedCctStart"), wb?.int("reportedCctEnd"), wb?.int("tint"),
            focus?.str("afMode"), focus?.num("distanceDiopters"), c.range("zoom"), stabilization?.str("ois"), stabilization?.str("eis"),
            processing?.str("noiseReduction"), processing?.str("edge"), processing?.str("toneMap"))
    }?.takeIf { it != ClipCameraFacts() }
    val media = document.media
    ClipSidecarFacts(
        createdAt = document.createdAt.takeIf { it.isNotBlank() }, takeStatus = document.takeStatus,
        failureReasons = document.failureReasons.orEmpty(),
        recordingPolicy = intent.str("recordingPolicy"), captureMode = intent.str("captureMode"),
        requestedWidth = requested?.int("width"), requestedHeight = requested?.int("height"), requestedFps = intent.num("requestedFps"),
        projectRate = intent.str("projectRate"), videoCodecPreference = intent.str("videoCodecPreference"),
        videoBitrateMbps = intent.int("videoBitrateMbps"), audioEnabled = intentAudio?.bool("enabled"),
        audioRequestedFormat = intentAudio?.str("requestedFormat"), audioRequestedSampleRateHz = intentAudio?.int("sampleRateHz"),
        audioRequestedBitDepth = intentAudio?.int("bitDepth"), audioRequestedChannels = intentAudio?.int("channels"),
        lutSelectionId = intent.str("lutSelectionId"), timecodeEnabled = intent.bool("timecodeEnabled"),
        cameraId = graph.str("cameraId"), routeTruth = route?.str("truth"), routeRequested = route?.str("requested"),
        routeReported = route?.str("reported"), recordingRoute = graph.str("recordingRoute"),
        codecMime = video.str("codecMime"), encoderName = video.str("encoderName"), hardwareAccelerated = video.bool("hardwareAccelerated"),
        softwareEncoder = video.bool("softwareEncoder"), encodedWidth = encoded?.int("width"), encodedHeight = encoded?.int("height"),
        fps = video.num("fps"), rotationDegrees = video.int("containerRotationDegrees"),
        colorKind = color?.str("kind"), colorTransfer = color?.str("transfer"), colorGamut = color?.str("gamut"), colorProfile = color?.str("profile"),
        audioStorage = graphAudio?.str("storage")?.takeIf { it != "none" }, audioContainer = graphAudio?.str("container"),
        audioSampleRateHz = graphAudio?.int("sampleRateHz"), audioBitDepth = graphAudio?.int("bitDepth"),
        deviceProfileId = profile?.str("profileId"), deviceProfileStatus = profile?.str("status"),
        deviceProfileBuildCheck = profile?.str("buildCheck"), appliedQuirks = profile?.strings("appliedQuirkIds").orEmpty(),
        durationMs = media?.long("durationMs"), encodedFrames = media?.long("encodedFrames"), camera = camera,
    )
}.getOrNull()

/** What an `.ocraw` take's metadata and frame index say, folded once. */
internal data class OcrawSummary(val width: Int?, val height: Int?, val requestedFps: Double?, val measuredFps: Double?, val frameCount: Int,
    val durationUs: Long?, val encoding: String?, val leadingEncoding: String?, val bitDepth: Int = 10, val cfa: String? = null,
    val blackLevel: String? = null, val whiteLevel: String? = null, val cameraId: String? = null, val model: String? = null,
    val appVersion: String? = null, val fingerprint: String? = null, val focalLengthMm: Double? = null, val aperture: Double? = null,
    val orientationDegrees: Int = 0, val audioSampleRateHz: Int? = null, val audioChannels: Int? = null, val audioSource: String? = null,
    val audioDroppedSamples: Long? = null, val bytesPerSecond: Double? = null, val compressionRatio: Double? = null,
    val isoMin: Int? = null, val isoMax: Int? = null, val exposureMinNs: Long? = null, val exposureMaxNs: Long? = null,
    val missingFrames: Long? = null, val closedCleanly: Boolean = false) {

    val facts: GalleryFacts get() = GalleryFacts(durationUs?.let { (it + 500) / 1000 }, width, height, codec = rawCodecBadge(leadingEncoding))

    companion object {
        fun from(metadata: Map<String, String>, index: OcrawFrameIndex, sizeBytes: Long): OcrawSummary {
            val header = index.frames.firstNotNullOfOrNull { it.header }
            val orientation = index.orientationDegrees
            val shown = header?.let { RawDevelop.displaySize(it.width, it.height, orientation) }
            val count = index.frames.size
            val times = if (count > 0) rawFrameTimesUs(index) else LongArray(0)
            val durationUs = if (count > 0) times.last() + rawFrameIntervalNs(metadata, times) / 1000 else null
            val span = if (count >= 2) times.last() - times.first() else 0L
            val numbers = index.frames.mapNotNull { it.header?.frameNumber }.distinct().sorted()
            val missing = if (numbers.size >= 2) numbers.zipWithNext { a, b -> b - a - 1 }.sum() else null
            val records = index.frameMetadata.values
            val isos = records.mapNotNull { it["iso"]?.trim()?.toIntOrNull() }
            val exposures = records.mapNotNull { it["exposureNs"]?.trim()?.toLongOrNull()?.takeIf { ns -> ns > 0 } }
            val seconds = durationUs?.takeIf { it > 0 }?.let { it / 1e6 }
            val packed = header?.let { it.width.toDouble() * it.height * 10 / 8 * count }
            fun text(key: String) = metadata[key]?.trim()?.takeIf { it.isNotEmpty() }
            fun number(key: String) = text(key)?.let(RawDevelop::parseNumber)?.takeIf { it > 0 }
            return OcrawSummary(
                width = shown?.get(0), height = shown?.get(1), requestedFps = number("fps"),
                measuredFps = if (span > 0) (count - 1) * 1e6 / span else null, frameCount = count, durationUs = durationUs,
                encoding = rawCodecLabel(index), leadingEncoding = text("encoding"), cfa = text("cfa"), blackLevel = text("blackLevel"),
                whiteLevel = text("whiteLevel"), cameraId = text("cameraId"), model = text("model"), appVersion = text("appVersion"),
                fingerprint = text("fingerprint"), focalLengthMm = number("focalLength"), aperture = number("aperture"),
                orientationDegrees = orientation, audioSampleRateHz = text("audioSampleRate")?.toIntOrNull()?.takeIf { it > 0 },
                audioChannels = text("audioChannels")?.toIntOrNull()?.takeIf { it > 0 }, audioSource = text("audioSource"),
                audioDroppedSamples = text("audioDroppedSamples")?.toLongOrNull(),
                bytesPerSecond = seconds?.let { sizeBytes / it }?.takeIf { sizeBytes > 0 },
                compressionRatio = packed?.takeIf { sizeBytes > 0 && it > 0 }?.let { it / sizeBytes },
                isoMin = isos.minOrNull(), isoMax = isos.maxOrNull(), exposureMinNs = exposures.minOrNull(), exposureMaxNs = exposures.maxOrNull(),
                missingFrames = missing, closedCleanly = index.hasEnd && !index.tornTail,
            )
        }
    }
}

/** The EXIF tags [addExifRows] reads; the reader snapshots exactly these. */
internal val EXIF_DETAIL_TAGS: List<String> = listOf(ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
    ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
    ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_COLOR_SPACE, ExifInterface.TAG_BITS_PER_SAMPLE, ExifInterface.TAG_CFA_PATTERN,
    ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_SOFTWARE)

/** Everything one take's readers produced; any of them may be missing. */
internal data class TakeDetailInputs(val kind: LocalMediaKind, val files: List<DetailFile> = emptyList(), val clip: ClipSidecarFacts? = null,
    val video: ClipMetadata? = null, val mediaStoreBitrate: Long? = null, val log: OcLogClip? = null, val hdr: HdrVideoFacts? = null,
    val audio: AudioMetadata? = null, val exif: ((String) -> String?)? = null, val raw: OcrawSummary? = null)

// ---- Rows ----

/** Collects rows by group; the first row of a key wins, so sources are added in order of trust. */
internal class DetailRowsBuilder {
    private val groups = LinkedHashMap<DetailGroupKind, LinkedHashMap<String, DetailRow>>()

    fun add(group: DetailGroupKind, @StringRes label: Int, value: String?, key: String, copyable: Boolean = false) {
        if (value.isNullOrBlank()) return
        val rows = groups.getOrPut(group) { LinkedHashMap() }
        if (key !in rows) rows[key] = DetailRow(label, value, key, copyable)
    }

    /** Runs [block] on its own rows and keeps them only when it finishes, so one bad source drops only what it adds. */
    fun section(block: DetailRowsBuilder.() -> Unit) {
        val staged = DetailRowsBuilder()
        try { staged.block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return }
        staged.groups.forEach { (group, rows) -> rows.values.forEach { add(group, it.label, it.value, it.key, it.copyable) } }
    }

    fun build(facts: GalleryFacts? = null) = TakeTechnicalDetails(
        DetailGroupKind.entries.mapNotNull { kind -> groups[kind]?.values?.toList()?.takeIf { it.isNotEmpty() }?.let { DetailGroup(kind, it) } }, facts)
}

/** Every section of one take, from its readers' results. */
internal fun buildTakeDetails(input: TakeDetailInputs, text: DetailText): TakeTechnicalDetails {
    val rows = DetailRowsBuilder()
    rows.section { addFileRows(input.files, input.clip, text) }
    input.video?.let { video -> rows.section { addProbeRows(video, input, text) } }
    input.log?.let { log -> rows.section { addLogRows(log, text) } }
    input.hdr?.let { hdr -> rows.section { addHdrRows(hdr, text) } }
    input.audio?.let { audio -> rows.section { addAudioRows(audio, text, ownDuration = input.kind == LocalMediaKind.AUDIO) } }
    input.exif?.let { exif -> rows.section { addExifRows(exif, text) } }
    input.raw?.let { raw -> rows.section { addRawRows(raw, text) } }
    input.clip?.let { clip -> rows.section { addClipRows(clip, text, input.video?.frameRate) } }
    return rows.build(input.raw?.facts)
}

internal fun DetailRowsBuilder.addFileRows(files: List<DetailFile>, clip: ClipSidecarFacts?, text: DetailText) {
    val g = DetailGroupKind.FILE
    files.forEachIndexed { i, file ->
        val artifact = file.artifact
        if (file.path != null) add(g, R.string.media_detail_path, file.path, "path-$i", copyable = true)
        else add(g, R.string.media_detail_name, artifact.name, "name-$i", copyable = true)
        add(g, R.string.media_detail_type, artifact.mimeType, "mime-$i")
        if (artifact.sizeBytes > 0) add(g, R.string.media_detail_size, text.size(artifact.sizeBytes), "size-$i")
        if (artifact.modifiedSeconds > 0) add(g, R.string.media_detail_modified, text.dateTime(artifact.modifiedSeconds * 1000), "modified-$i")
    }
    clip?.createdAt?.let { created -> runCatching { Instant.parse(created).toEpochMilli() }.getOrNull() }
        ?.let { add(g, R.string.media_detail_created, text.dateTime(it), "created") }
}

private fun DetailRowsBuilder.addProbeRows(video: ClipMetadata, input: TakeDetailInputs, text: DetailText) {
    val g = DetailGroupKind.VIDEO
    val clip = input.clip
    add(g, R.string.media_detail_resolution, resolutionLabel(video.width, video.height), "resolution")
    add(g, R.string.media_detail_frame_rate, text.requested((video.frameRate ?: clip?.fps)?.let(::fpsLabel), clip?.requestedFps?.let(::fpsLabel)), "fps")
    add(g, R.string.media_detail_codec, video.codec, "codec")
    val durationUs = video.durationUs ?: clip?.durationMs?.let { it * 1000 }
    durationUs?.let { add(g, R.string.media_detail_duration, durationLabel(it), "duration") }
    val size = input.files.firstOrNull { it.artifact.mimeType.startsWith("video/") }?.artifact?.sizeBytes ?: 0
    val average = durationUs?.takeIf { it > 0 && size > 0 }?.let { size * 8.0 / (it / 1e6) }
    add(g, R.string.media_detail_bitrate, (average ?: input.mediaStoreBitrate?.takeIf { it > 0 }?.toDouble())?.let(::bitrateLabel), "bitrate")
    if (input.log == null && input.hdr == null) add(DetailGroupKind.COLOUR, R.string.media_detail_colour, video.color, "colour")
}

private fun DetailRowsBuilder.addLogRows(log: OcLogClip, text: DetailText) {
    val g = DetailGroupKind.COLOUR
    add(g, R.string.media_detail_transfer, listOfNotNull(log.curve, log.version).joinToString(" "), "transfer")
    add(g, R.string.media_detail_gamut, log.gamut, "gamut")
    add(g, R.string.media_detail_range, text.s(if (log.fullRange) R.string.media_detail_range_full else R.string.media_detail_range_limited), "range")
    add(DetailGroupKind.VIDEO, R.string.media_detail_codec_profile, log.profile, "codec-profile")
}

private fun DetailRowsBuilder.addHdrRows(hdr: HdrVideoFacts, text: DetailText) {
    val g = DetailGroupKind.COLOUR
    add(g, R.string.media_detail_transfer, listOfNotNull(hdr.transfer, hdr.transferStandard?.let { "($it)" }).joinToString(" "), "transfer")
    add(g, R.string.media_detail_gamut, hdr.primaries, "gamut")
    add(g, R.string.media_detail_range, rangeText(hdr.range, text), "range")
    add(g, R.string.media_detail_source_range, hdr.sourceDynamicRange, "source-range")
    add(DetailGroupKind.VIDEO, R.string.media_detail_codec_profile, hdr.codecProfile, "codec-profile")
}

private fun rangeText(range: String?, text: DetailText): String? = when (range?.lowercase(Locale.ROOT)) {
    null -> null
    "full" -> text.s(R.string.media_detail_range_full)
    "limited" -> text.s(R.string.media_detail_range_limited)
    else -> range
}

private fun DetailRowsBuilder.addAudioRows(audio: AudioMetadata, text: DetailText, ownDuration: Boolean) {
    val g = DetailGroupKind.AUDIO
    add(g, R.string.media_detail_codec, audioCodecLabel(audio.mime), "audio-codec")
    audio.sampleRateHz?.takeIf { it > 0 }?.let { add(g, R.string.media_detail_sample_rate, sampleRateLabel(it), "sample-rate") }
    audio.bitDepth?.takeIf { it > 0 }?.let {
        add(g, R.string.media_detail_bit_depth, if (audio.float) text.s(R.string.media_detail_bits_float, it) else text.s(R.string.media_detail_bits, it), "audio-bit-depth")
    }
    audio.channels?.takeIf { it > 0 }?.let { add(g, R.string.media_detail_channels, channelsLabel(text, it), "channels") }
    audio.bitRate?.takeIf { it > 0 }?.let { add(g, R.string.media_detail_bitrate, "${(it / 1000.0).roundToInt()} kbps", "audio-bitrate") }
    if (ownDuration) audio.durationUs?.takeIf { it > 0 }?.let { add(g, R.string.media_detail_duration, durationLabel(it), "duration") }
}

/** EXIF through [get], so the mapping is host-testable without a file. */
internal fun DetailRowsBuilder.addExifRows(get: (String) -> String?, text: DetailText) {
    fun value(tag: String) = get(tag)?.trim()?.takeIf { it.isNotEmpty() }
    fun number(tag: String) = value(tag)?.let(RawDevelop::parseNumber)
    fun int(tag: String) = number(tag)?.takeIf { it == Math.rint(it) }?.toInt()
    val video = DetailGroupKind.VIDEO
    val width = int(ExifInterface.TAG_PIXEL_X_DIMENSION)?.takeIf { it > 0 } ?: int(ExifInterface.TAG_IMAGE_WIDTH)
    val height = int(ExifInterface.TAG_PIXEL_Y_DIMENSION)?.takeIf { it > 0 } ?: int(ExifInterface.TAG_IMAGE_LENGTH)
    if (width != null && height != null && width > 0 && height > 0)
        add(video, R.string.media_detail_resolution, "$width×$height (${decimalLabel(width.toDouble() * height / 1e6, 1)} MP)", "resolution")
    value(ExifInterface.TAG_BITS_PER_SAMPLE)?.split(',', ' ')?.mapNotNull { it.trim().toIntOrNull() }?.firstOrNull()?.takeIf { it > 0 }
        ?.let { add(video, R.string.media_detail_bit_depth, text.s(R.string.media_detail_bits, it), "bit-depth") }
    when (int(ExifInterface.TAG_COLOR_SPACE)) {
        1 -> "sRGB"
        65535 -> text.s(R.string.media_detail_uncalibrated)
        else -> null
    }?.let { add(DetailGroupKind.COLOUR, R.string.media_detail_colour_space, it, "colour-space") }
    val camera = DetailGroupKind.CAMERA
    add(camera, R.string.media_detail_device, listOfNotNull(value(ExifInterface.TAG_MAKE), value(ExifInterface.TAG_MODEL)).distinct().joinToString(" "), "device")
    add(camera, R.string.media_detail_lens, value(ExifInterface.TAG_LENS_MODEL), "lens")
    number(ExifInterface.TAG_FOCAL_LENGTH)?.takeIf { it > 0 }
        ?.let { add(camera, R.string.media_detail_focal_length, focalLabel(it, number(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)), "focal") }
    number(ExifInterface.TAG_F_NUMBER)?.takeIf { it > 0 }?.let { add(camera, R.string.media_detail_aperture, apertureLabel(it), "aperture") }
    int(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.takeIf { it > 0 }?.let { add(camera, R.string.media_detail_iso, isoLabel(it), "iso") }
    number(ExifInterface.TAG_EXPOSURE_TIME)?.takeIf { it > 0 }?.let { add(camera, R.string.media_detail_shutter, shutterLabel((it * 1e9).roundToLong()), "shutter") }
    number(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.let { add(camera, R.string.media_detail_exposure_bias, if (kotlin.math.abs(it) < 0.05) "0 EV" else String.format(Locale.ROOT, "%+.1f EV", it), "exposure-bias") }
    when (int(ExifInterface.TAG_WHITE_BALANCE)) { 0 -> R.string.media_detail_auto; 1 -> R.string.media_detail_manual; else -> null }
        ?.let { add(camera, R.string.media_detail_white_balance, text.s(it), "wb-mode") }
    int(ExifInterface.TAG_FLASH)?.let { add(camera, R.string.media_detail_flash, text.s(if (it and 1 == 1) R.string.media_detail_flash_fired else R.string.media_detail_flash_not_fired), "flash") }
    // EXIF writes "YYYY:MM:DD HH:MM:SS"; only the date's colons become dashes.
    value(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { if (it.length >= 10) it.substring(0, 10).replace(':', '-') + it.substring(10) else it }
        ?.let { add(camera, R.string.media_detail_taken, it, "taken") }
    add(DetailGroupKind.RECORDING, R.string.media_detail_software, value(ExifInterface.TAG_SOFTWARE), "software")
    exifCfa(value(ExifInterface.TAG_CFA_PATTERN))?.let { add(DetailGroupKind.RAW, R.string.media_detail_cfa, it, "cfa") }
}

/** The 2×2 CFA as "RGGB" from the last four colour indexes EXIF lists (0 red, 1 green, 2 blue); null otherwise. */
internal fun exifCfa(value: String?): String? {
    val numbers = value?.split(',', ' ')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
    if (numbers.size < 4) return null
    val last = numbers.takeLast(4)
    if (last.any { it !in 0..2 }) return null
    return last.joinToString("") { "RGB"[it].toString() }
}

private fun DetailRowsBuilder.addRawRows(raw: OcrawSummary, text: DetailText) {
    val video = DetailGroupKind.VIDEO
    if (raw.width != null && raw.height != null) add(video, R.string.media_detail_resolution, resolutionLabel(raw.width, raw.height), "resolution")
    add(video, R.string.media_detail_frame_rate, text.requested(raw.measuredFps?.let(::fpsLabel), raw.requestedFps?.let(::fpsLabel)), "fps")
    raw.durationUs?.let { add(video, R.string.media_detail_duration, durationLabel(it), "duration") }
    add(video, R.string.media_detail_frames, raw.frameCount.toString(), "frames")
    add(video, R.string.media_detail_orientation, "${raw.orientationDegrees}°", "orientation")
    val g = DetailGroupKind.RAW
    add(g, R.string.media_detail_raw_encoding, raw.encoding, "raw-encoding")
    add(g, R.string.media_detail_bit_depth, text.s(R.string.media_detail_bits, raw.bitDepth), "raw-bit-depth")
    add(g, R.string.media_detail_cfa, raw.cfa, "cfa")
    add(g, R.string.media_detail_black_level, raw.blackLevel, "black-level")
    add(g, R.string.media_detail_white_level, raw.whiteLevel, "white-level")
    // An uncompressed RAW10 take reads 1.0:1; the row only says something for a compressed one.
    raw.compressionRatio?.takeIf { it >= 1.05 }?.let { add(g, R.string.media_detail_compression, compressionLabel(it), "compression") }
    raw.bytesPerSecond?.let { add(g, R.string.media_detail_data_rate, dataRateLabel(it), "data-rate") }
    raw.missingFrames?.let { add(g, R.string.media_detail_missing_frames, it.toString(), "missing-frames") }
    add(g, R.string.media_detail_closed_cleanly, text.yesNo(raw.closedCleanly), "closed-cleanly")
    val camera = DetailGroupKind.CAMERA
    add(camera, R.string.media_detail_camera_id, raw.cameraId, "camera-id")
    add(camera, R.string.media_detail_device, raw.model, "device")
    raw.focalLengthMm?.let { add(camera, R.string.media_detail_focal_length, focalLabel(it), "focal") }
    raw.aperture?.let { add(camera, R.string.media_detail_aperture, apertureLabel(it), "aperture") }
    if (raw.isoMin != null && raw.isoMax != null) add(camera, R.string.media_detail_iso, isoLabel(raw.isoMin, raw.isoMax), "iso")
    if (raw.exposureMinNs != null && raw.exposureMaxNs != null)
        add(camera, R.string.media_detail_shutter, shutterRangeLabel(raw.exposureMinNs, raw.exposureMaxNs, raw.requestedFps ?: raw.measuredFps), "shutter")
    val audio = DetailGroupKind.AUDIO
    raw.audioSampleRateHz?.let { add(audio, R.string.media_detail_sample_rate, sampleRateLabel(it), "sample-rate") }
    raw.audioChannels?.let { add(audio, R.string.media_detail_channels, channelsLabel(text, it), "channels") }
    if (raw.audioSampleRateHz != null) add(audio, R.string.media_detail_bit_depth, text.s(R.string.media_detail_bits, 16), "audio-bit-depth")
    add(audio, R.string.media_detail_audio_source, raw.audioSource, "audio-source")
    raw.audioDroppedSamples?.let { add(audio, R.string.media_detail_audio_dropped, it.toString(), "audio-dropped") }
    val recording = DetailGroupKind.RECORDING
    add(recording, R.string.media_detail_app_version, raw.appVersion, "app-version")
    add(recording, R.string.media_detail_fingerprint, raw.fingerprint, "fingerprint", copyable = true)
}

/** The clip sidecar's rows; [measuredFps] (from the file) sets the shutter angle when known. */
internal fun DetailRowsBuilder.addClipRows(clip: ClipSidecarFacts, text: DetailText, measuredFps: Double? = null) {
    val video = DetailGroupKind.VIDEO
    if (clip.encodedWidth != null && clip.encodedHeight != null)
        add(video, R.string.media_detail_resolution, resolutionLabel(clip.encodedWidth, clip.encodedHeight), "resolution")
    add(video, R.string.media_detail_frame_rate, text.requested(clip.fps?.let(::fpsLabel), clip.requestedFps?.let(::fpsLabel)), "fps")
    add(video, R.string.media_detail_codec, codecLabel(clip.codecMime, null, null), "codec")
    clip.durationMs?.let { add(video, R.string.media_detail_duration, durationLabel(it * 1000), "duration") }
    clip.encodedFrames?.let { add(video, R.string.media_detail_frames, it.toString(), "frames") }
    clip.videoBitrateMbps?.let { add(video, R.string.media_detail_bitrate_target, "$it Mbps", "bitrate-target") }
    clip.rotationDegrees?.let { add(video, R.string.media_detail_rotation, "$it°", "rotation") }
    add(video, R.string.media_detail_encoder, clip.encoderName, "encoder")
    val software = clip.softwareEncoder == true || clip.hardwareAccelerated == false
    if (software || clip.hardwareAccelerated == true)
        add(video, R.string.media_detail_encoder_type, text.s(if (software) R.string.media_detail_software_encoder else R.string.media_detail_hardware_encoder), "encoder-type")
    add(video, R.string.media_detail_codec_requested, clip.videoCodecPreference, "codec-preference")
    // The graph's colour profile is the codec profile (HEVC Main10): one row, under Video.
    add(video, R.string.media_detail_codec_profile, clip.colorProfile, "codec-profile")

    val colour = DetailGroupKind.COLOUR
    add(colour, R.string.media_detail_transfer, clip.colorTransfer ?: text.word(DetailEnum.COLOUR_KIND, clip.colorKind), "transfer")
    add(colour, R.string.media_detail_gamut, clip.colorGamut, "gamut")
    add(colour, R.string.media_detail_lut, clip.lutSelectionId, "lut")

    val audio = DetailGroupKind.AUDIO
    if (clip.audioEnabled == false) add(audio, R.string.media_detail_audio_recorded, text.onOff(false), "audio-enabled")
    add(audio, R.string.media_detail_codec, clip.audioContainer, "audio-codec")
    clip.audioSampleRateHz?.let { add(audio, R.string.media_detail_sample_rate, sampleRateLabel(it), "sample-rate") }
    clip.audioBitDepth?.let { add(audio, R.string.media_detail_bit_depth, text.s(R.string.media_detail_bits, it), "audio-bit-depth") }
    clip.audioRequestedChannels?.let { add(audio, R.string.media_detail_channels, channelsLabel(text, it), "channels") }
    add(audio, R.string.media_detail_audio_storage, text.word(DetailEnum.AUDIO_STORAGE, clip.audioStorage), "audio-storage")
    add(audio, R.string.media_detail_audio_requested, clip.audioRequestedFormat, "audio-requested")

    val camera = DetailGroupKind.CAMERA
    val c = clip.camera
    add(camera, R.string.media_detail_camera_id, c?.cameraId ?: clip.cameraId, "camera-id")
    if (c != null) {
        c.focalLengthMm?.takeIf { it > 0 }?.let { add(camera, R.string.media_detail_focal_length, focalLabel(it, c.focalLength35mm), "focal") }
        c.aperture?.takeIf { it > 0 }?.let { add(camera, R.string.media_detail_aperture, apertureLabel(it), "aperture") }
        add(camera, R.string.media_detail_exposure_mode, text.requested(c.reportedExposureMode, c.requestedExposureMode), "exposure-mode")
        c.iso?.let { iso -> val lo = iso.low; val hi = iso.high
            if (lo != null && hi != null) add(camera, R.string.media_detail_iso, isoLabel(lo.roundToInt(), hi.roundToInt()), "iso") }
        c.exposureTimeNs?.let { exposure -> val lo = exposure.low; val hi = exposure.high
            if (lo != null && hi != null && lo > 0)
                add(camera, R.string.media_detail_shutter, shutterRangeLabel(lo.roundToLong(), hi.roundToLong(), measuredFps ?: clip.fps ?: clip.requestedFps), "shutter") }
        add(camera, R.string.media_detail_white_balance, listOfNotNull(text.word(DetailEnum.WHITE_BALANCE, c.whiteBalanceMode), c.whiteBalanceKelvin?.let { "$it K" }).joinToString(" · "), "wb-mode")
        val cct = listOfNotNull(c.reportedCctStart, c.reportedCctEnd).distinct()
        if (cct.isNotEmpty()) add(camera, R.string.media_detail_reported_cct, "${cct.joinToString("–")} K", "wb-cct")
        c.tint?.let { add(camera, R.string.media_detail_tint, if (it > 0) "+$it" else "$it", "wb-tint") }
        add(camera, R.string.media_detail_focus_mode, c.afMode, "af-mode")
        c.focusDiopters?.let { add(camera, R.string.media_detail_focus_distance, focusDistanceLabel(it), "focus-distance") }
        c.zoom?.let { zoom -> val start = zoom.start ?: zoom.low; val end = zoom.end ?: start
            if (start != null && end != null) add(camera, R.string.media_detail_zoom, zoomLabel(start, end), "zoom") }
        add(camera, R.string.media_detail_ois, text.word(DetailEnum.STABILIZATION, c.ois), "ois")
        add(camera, R.string.media_detail_eis, text.word(DetailEnum.STABILIZATION, c.eis), "eis")
        add(camera, R.string.media_detail_noise_reduction, c.noiseReduction, "noise-reduction")
        add(camera, R.string.media_detail_edge, c.edge, "edge")
        add(camera, R.string.media_detail_tone_map, c.toneMap, "tone-map")
    }

    val recording = DetailGroupKind.RECORDING
    add(recording, R.string.media_detail_take_status, if (clip.takeStatus == "incomplete")
        listOf(text.s(R.string.media_detail_take_incomplete)).plus(clip.failureReasons).joinToString(" · ")
        else text.s(R.string.media_detail_take_complete), "take-status")
    add(recording, R.string.media_detail_capture_mode, text.word(DetailEnum.CAPTURE_MODE, clip.captureMode), "capture-mode")
    add(recording, R.string.media_detail_recording_policy, text.word(DetailEnum.RECORDING_POLICY, clip.recordingPolicy), "recording-policy")
    if (clip.requestedWidth != null && clip.requestedHeight != null)
        add(recording, R.string.media_detail_requested_size, "${clip.requestedWidth}×${clip.requestedHeight}", "requested-size")
    add(recording, R.string.media_detail_project_rate, clip.projectRate, "project-rate")
    clip.timecodeEnabled?.let { add(recording, R.string.media_detail_timecode, text.onOff(it), "timecode") }
    add(recording, R.string.media_detail_route, text.requested(clip.routeReported, clip.routeRequested) ?: clip.routeTruth, "route")
    add(recording, R.string.media_detail_recorder, clip.recordingRoute, "recorder")
    add(recording, R.string.media_detail_device_profile,
        listOfNotNull(clip.deviceProfileId, clip.deviceProfileBuildCheck ?: clip.deviceProfileStatus).joinToString(" · "), "device-profile")
    if (clip.appliedQuirks.isNotEmpty()) add(recording, R.string.media_detail_quirks, clip.appliedQuirks.joinToString(", "), "quirks")
}
