/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.core.model.ClipSidecarDocument
import com.librestatic.opencinecam.core.model.SchemaEvidence
import com.librestatic.opencinecam.core.model.encodeClipSidecar
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Suffix of the canonical per-take sidecar: `<videoStem>.clip.json`. */
internal const val CLIP_SIDECAR_SUFFIX = ".clip.json"

/** What the operator asked for, frozen when the take started. A null field was not known to the service. */
internal data class ClipIntent(
    val recordingPolicy: String? = null,
    val captureMode: String? = null,
    val requestedWidth: Int? = null,
    val requestedHeight: Int? = null,
    val requestedFps: Int? = null,
    /** The off-speed project rate label (for example `24 fps`); null when the take has none. */
    val projectRate: String? = null,
    val videoCodecPreference: String? = null,
    val videoBitrateMbps: Int? = null,
    val audioEnabled: Boolean? = null,
    val audioFormat: String? = null,
    val audioSampleRateHz: Int? = null,
    val audioBitDepth: Int? = null,
    val audioChannels: Int? = null,
    val lutSelectionId: String? = null,
    val timecodeEnabled: Boolean? = null,
)

/** The device profile the camera resolved to for this take. */
internal data class ClipDeviceProfile(
    val profileId: String?,
    val status: String,
    val appliedQuirkIds: List<String>,
    /** `VERIFIED`, `NOT_VERIFIED_ON_THIS_BUILD` or `NO_PROFILE`. */
    val buildCheck: String,
)

/** What actually ran, read when the take stopped. A null field is written as an unknown value, never guessed. */
internal data class ClipGraph(
    val cameraId: String? = null,
    val routeTruth: String? = null,
    val requestedRoute: String? = null,
    val reportedRoute: String? = null,
    /** `MediaRecorder` or `MediaCodec`: the writer that produced the file. */
    val recorder: String? = null,
    val codecMime: String? = null,
    val encoderName: String? = null,
    val hardwareAccelerated: Boolean? = null,
    val softwareEncoder: Boolean = false,
    val encodedWidth: Int? = null,
    val encodedHeight: Int? = null,
    val fps: Int? = null,
    val containerRotationDegrees: Int? = null,
    /** `SDR_BT709`, `HLG` or `OCLog2`. */
    val colorKind: String? = null,
    val colorTransfer: String? = null,
    val colorGamut: String? = null,
    val colorProfile: String? = null,
    /** `EMBEDDED_AAC`, `SEPARATE_WAV`, `SEPARATE_FLAC` or null when the take has no audio. */
    val audioStorage: String? = null,
    val audioContainer: String? = null,
    val audioSampleRateHz: Int? = null,
    val audioBitDepth: Int? = null,
    val deviceProfile: ClipDeviceProfile? = null,
)

/** A pause window, in microseconds from the take start. */
internal data class ClipPause(val startUs: Long, val endUs: Long?)

/** A condition the service recorded during the take; [timeUs] null means it was only known at the end. */
internal data class ClipWarning(val code: String, val severity: String = "warning", val timeUs: Long? = null, val message: String? = null)

internal data class ClipTakeFacts(
    val startedAt: Instant,
    val intent: ClipIntent?,
    val graph: ClipGraph,
    val pauses: List<ClipPause> = emptyList(),
    val warnings: List<ClipWarning> = emptyList(),
    val stopReason: String? = null,
    val durationMs: Long? = null,
    val encodedFrames: Long? = null,
    val success: Boolean,
    val nativeRetired: Boolean,
    /** Files of the take besides the video and its metadata sidecar: the separate audio file and its sidecar. */
    val audioFiles: List<String> = emptyList(),
)

enum class ClipStepStatus(val wire: String) { PASS("pass"), FAIL("fail"), NOT_RUN("notRun") }

/** What the storage layer knows at preparation time and the service cannot: the row names and the file check. */
data class ClipSidecarPrepareContext(
    val recordingId: String,
    val videoName: String,
    /** Name of the timing or OCLog sidecar written beside the video; null when there is none. */
    val metadataName: String?,
    val fileVerified: ClipStepStatus,
    val fileVerifiedDetail: String? = null,
    /** ADR-0011 reasons; null for a take being completed normally. */
    val incompleteReasons: List<String>? = null,
    val observedAt: Instant = Instant.now(),
)

/** Builds the JSON of a take's clip sidecar; a null result (or a throw) means the take publishes without one. */
fun interface ClipSidecarProvider {
    fun build(context: ClipSidecarPrepareContext): String?
}

internal fun clipSidecarName(videoName: String): String = videoName.removeSuffix(".mp4") + CLIP_SIDECAR_SUFFIX

private fun known(value: JsonElement): JsonObject = buildJsonObject {
    put("knowledge", "known")
    put("value", value)
}

private fun unknown(): JsonObject = buildJsonObject { put("knowledge", "unknown") }

private fun putOrUnknown(builder: kotlinx.serialization.json.JsonObjectBuilder, key: String, value: String?) {
    builder.put(key, value?.let { known(JsonPrimitive(it)) } ?: unknown())
}

private fun putOrUnknown(builder: kotlinx.serialization.json.JsonObjectBuilder, key: String, value: Int?) {
    builder.put(key, value?.let { known(JsonPrimitive(it)) } ?: unknown())
}

private fun putOrUnknown(builder: kotlinx.serialization.json.JsonObjectBuilder, key: String, value: Boolean?) {
    builder.put(key, value?.let { known(JsonPrimitive(it)) } ?: unknown())
}

private fun intentJson(intent: ClipIntent?): JsonObject = if (intent == null) unknown() else buildJsonObject {
    putOrUnknown(this, "recordingPolicy", intent.recordingPolicy)
    putOrUnknown(this, "captureMode", intent.captureMode)
    put("requestedSize", if (intent.requestedWidth != null && intent.requestedHeight != null) known(buildJsonObject {
        put("width", intent.requestedWidth); put("height", intent.requestedHeight)
    }) else unknown())
    putOrUnknown(this, "requestedFps", intent.requestedFps)
    intent.projectRate?.let { put("projectRate", known(JsonPrimitive(it))) }
    putOrUnknown(this, "videoCodecPreference", intent.videoCodecPreference)
    putOrUnknown(this, "videoBitrateMbps", intent.videoBitrateMbps)
    put("audio", buildJsonObject {
        putOrUnknown(this, "enabled", intent.audioEnabled)
        if (intent.audioEnabled == true) {
            putOrUnknown(this, "requestedFormat", intent.audioFormat)
            intent.audioSampleRateHz?.let { put("sampleRateHz", it) }
            intent.audioBitDepth?.let { put("bitDepth", it) }
            intent.audioChannels?.let { put("channels", it) }
        }
    })
    intent.lutSelectionId?.let { put("lutSelectionId", it) }
    putOrUnknown(this, "timecodeEnabled", intent.timecodeEnabled)
}

private fun graphJson(graph: ClipGraph): JsonObject = buildJsonObject {
    putOrUnknown(this, "cameraId", graph.cameraId)
    put("route", if (graph.routeTruth == null) unknown() else known(buildJsonObject {
        put("truth", graph.routeTruth)
        graph.requestedRoute?.let { put("requested", it) }
        graph.reportedRoute?.let { put("reported", it) }
    }))
    putOrUnknown(this, "recordingRoute", graph.recorder)
    put("video", buildJsonObject {
        putOrUnknown(this, "codecMime", graph.codecMime)
        putOrUnknown(this, "encoderName", graph.encoderName)
        putOrUnknown(this, "hardwareAccelerated", if (graph.softwareEncoder) false else graph.hardwareAccelerated)
        put("softwareEncoder", graph.softwareEncoder)
        put("encodedSize", if (graph.encodedWidth != null && graph.encodedHeight != null) known(buildJsonObject {
            put("width", graph.encodedWidth); put("height", graph.encodedHeight)
        }) else unknown())
        putOrUnknown(this, "fps", graph.fps)
        putOrUnknown(this, "containerRotationDegrees", graph.containerRotationDegrees)
    })
    put("color", if (graph.colorKind == null) unknown() else known(buildJsonObject {
        put("kind", graph.colorKind)
        graph.colorTransfer?.let { put("transfer", it) }
        graph.colorGamut?.let { put("gamut", it) }
        graph.colorProfile?.let { put("profile", it) }
    }))
    put("audio", if (graph.audioStorage == null) buildJsonObject { put("storage", "none") } else buildJsonObject {
        put("storage", graph.audioStorage)
        graph.audioContainer?.let { put("container", it) }
        graph.audioSampleRateHz?.let { put("sampleRateHz", it) }
        graph.audioBitDepth?.let { put("bitDepth", it) }
    })
    put("deviceProfile", graph.deviceProfile?.let { profile ->
        known(buildJsonObject {
            profile.profileId?.let { put("profileId", it) }
            put("status", profile.status)
            put("appliedQuirkIds", JsonArray(profile.appliedQuirkIds.map(::JsonPrimitive)))
            put("buildCheck", profile.buildCheck)
        })
    } ?: unknown())
}

private fun event(timeUs: Long, code: String, severity: String, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null) =
    buildJsonObject {
        put("timeUs", timeUs); put("code", code); put("severity", severity)
        extra?.invoke(this)
    }

private fun eventsJson(facts: ClipTakeFacts, context: ClipSidecarPrepareContext): List<JsonObject> {
    val endUs = facts.durationMs?.let { it * 1000L }
    val events = mutableListOf(event(0L, "take-started", "info"))
    facts.pauses.forEach { pause ->
        // Pause windows are counted on the shared PCM grid, which is the take's only pause clock.
        events += event(pause.startUs, "pause", "info") { put("timeBasis", "audio-pcm-grid") }
        pause.endUs?.let { events += event(it, "resume", "info") { put("timeBasis", "audio-pcm-grid") } }
    }
    facts.warnings.forEach { warning ->
        events += event(warning.timeUs ?: endUs ?: 0L, warning.code, warning.severity) {
            if (warning.timeUs == null) put("timeBasis", "take-end")
            warning.message?.let { put("message", it.take(200)) }
        }
    }
    events += event(endUs ?: 0L, "take-stopped", if (facts.success && facts.nativeRetired) "info" else "error") {
        facts.stopReason?.let { put("reason", it) }
        put("success", facts.success)
        put("nativeRetired", facts.nativeRetired)
    }
    context.incompleteReasons.orEmpty().distinct().forEach { reason ->
        events += event(endUs ?: 0L, "incomplete:$reason", "error")
    }
    return events.sortedBy { (it["timeUs"] as JsonPrimitive).content.toLong() }
}

private fun validationJson(facts: ClipTakeFacts, context: ClipSidecarPrepareContext): List<SchemaEvidence> {
    val observed = context.observedAt.toString()
    val recorded = facts.success && facts.nativeRetired
    return listOf(
        SchemaEvidence(
            stage = "Recorded",
            status = if (recorded) "pass" else "fail",
            observedAt = observed,
            source = facts.graph.recorder,
            details = buildJsonObject {
                put("success", facts.success); put("nativeRetired", facts.nativeRetired)
                facts.encodedFrames?.let { put("encodedFrames", it) }
            },
        ),
        SchemaEvidence(
            stage = "FileVerified",
            status = context.fileVerified.wire,
            observedAt = observed,
            source = "VideoFileGeometryValidator",
            details = context.fileVerifiedDetail?.let { buildJsonObject { put("message", it.take(200)) } },
        ),
    )
}

private fun mediaJson(facts: ClipTakeFacts, context: ClipSidecarPrepareContext): JsonObject = buildJsonObject {
    put("displayName", context.videoName)
    facts.durationMs?.let { put("durationMs", it) }
    facts.encodedFrames?.let { put("encodedFrames", it) }
    put("sidecars", JsonArray((listOfNotNull(context.metadataName) + facts.audioFiles.filter { it.endsWith(".json") }).map(::JsonPrimitive)))
    put("audioFiles", JsonArray(facts.audioFiles.filterNot { it.endsWith(".json") }.map(::JsonPrimitive)))
}

/** The clip sidecar of one take as the typed [ClipSidecarDocument]; pure, so every branch is host-testable. */
internal fun buildClipSidecar(facts: ClipTakeFacts, context: ClipSidecarPrepareContext): ClipSidecarDocument =
    ClipSidecarDocument(
        schemaVersion = "1.0.0",
        recordingId = context.recordingId,
        createdAt = facts.startedAt.toString(),
        intent = intentJson(facts.intent),
        resolvedGraph = graphJson(facts.graph),
        events = eventsJson(facts, context),
        validation = validationJson(facts, context),
        media = mediaJson(facts, context),
        takeStatus = context.incompleteReasons?.let { IncompleteTake.STATUS_INCOMPLETE },
        failureReasons = context.incompleteReasons?.distinct(),
    )

/**
 * The provider the service hands to [VideoOutput]. [facts] is evaluated at preparation, after the take's audio
 * sidecar was prepared, so it can include the audio files and their final format.
 */
internal fun clipSidecarProvider(facts: () -> ClipTakeFacts): ClipSidecarProvider =
    ClipSidecarProvider { context -> encodeClipSidecar(buildClipSidecar(facts(), context)) }
