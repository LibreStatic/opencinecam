/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.parseProductionSlateJson
import kotlinx.serialization.json.*

internal data class ProxySidecarSelection(val audio: LocalMediaArtifact,
    val videoMetadata: LocalMediaArtifact, val audioMetadata: LocalMediaArtifact,
    val observation: ProxySidecarObservation, val timeline: ProxySidecarTimeline)

/** No IO or integrity claim. Caller supplies a fresh owned/committed snapshot and independently
 * decoded observations, then hashes/revalidates the selected sources and both exact documents.
 * The production writer publishes one video document and one explicitly URI-related sidecar. */
internal fun selectProxySidecar(take: LocalMediaTake, documents: List<MetadataDocument>,
    decodedSampleRateHz: Int, decodedChannels: Int, decodedFrames: Long, videoFirstPtsUs: Long, videoEndUs: Long): ProxySidecarSelection {
    require(take.kind == LocalMediaKind.VIDEO && take.relationStatus == LocalMediaRelationStatus.DECLARED &&
        take.id.startsWith("take:") && canonicalMediaId(take.id.removePrefix("take:"))) { "Declared recording take required" }
    require(take.originals.size == 2 && take.primary in take.originals &&
        take.originals.map { it.uri }.distinct().size == 2 && take.primary.mimeType == "video/mp4" &&
        mediaOriginalIdentity(take.primary.uri)?.first == LocalMediaKind.VIDEO) { "Exactly one primary video and one sidecar required" }
    val audio = take.originals.single { it.uri != take.primary.uri }
    val container = when (audio.mimeType) {
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> ProxySidecarContainer.WAV
        "audio/flac" -> ProxySidecarContainer.FLAC
        else -> error("Associated audio is not a WAV/FLAC sidecar")
    }
    require(mediaOriginalIdentity(audio.uri)?.first == LocalMediaKind.AUDIO && audio.sizeBytes > 0)
    require(take.metadata.size == 2 && documents.size == 2 && take.metadata.toSet().size == 2 &&
        documents.map { it.artifact }.toSet() == take.metadata.toSet() &&
        (take.originals + take.metadata).map { it.uri }.distinct().size == 4) { "Complete exact sidecar metadata snapshot required" }
    val parsed = documents.map { doc ->
        require(!doc.disappeared && doc.artifact.mimeType == "application/json" &&
            mediaDeleteIdentity(doc.artifact.uri)?.collection == MediaDeleteCollection.METADATA)
        val text = requireNotNull(doc.text) { "Sidecar metadata read unavailable" }
        require(text.length <= 512 * 1024 && Charsets.UTF_8.newEncoder().canEncode(text))
        val bytes = text.toByteArray()
        require(bytes.size in 1..512 * 1024 && bytes.size.toLong() == doc.artifact.sizeBytes)
        require(jsonNestingWithinBound(text) && jsonObjectKeysUnique(text)) { "Ambiguous sidecar JSON" }
        val json = Json.parseToJsonElement(text).jsonObject
        val slate = json["productionSlate"]?.let { requireNotNull(parseProductionSlateJson(it.jsonObject)) }
        require(slate == take.slate) { "Sidecar production slate differs from selected take" }
        doc.artifact to json
    }
    val audioDoc = parsed.single { SidecarFields(it.second).string("schema") == "opencinecam-audio-sidecar-v1" }
    val videoDoc = parsed.single { it.first != audioDoc.first }
    val a = SidecarFields(audioDoc.second)
    val v = SidecarFields(videoDoc.second)
    require(v.string("schema") in setOf("opencinecam.recording.v1", "opencinecam.av-timing.v1",
        "opencinecam.recording-color.v1", "opencinecam.recording-audio.v1", "opencinecam.recording-timing.v1",
        "opencinecam.timecode.v1", "opencinecam-oclog-sidecar-v2")) { "Unknown sidecar video metadata schema" }
    require(v.string("videoUri") == take.primary.uri && v.string("bundleId") == take.id.removePrefix("take:"))
    require(a.string("audioUri") == audio.uri && a.string("file") == audio.name && a.string("container") == container.name)
    if ("bundleId" in audioDoc.second) require(a.string("bundleId") == take.id.removePrefix("take:"))
    // A foreign cross-role reference is not a second way to select a member.
    require("videoUri" !in audioDoc.second && "audioUri" !in videoDoc.second)
    val channels = a.int("channels")
    val bits = when (a.string("encoding")) { "PCM_16" -> 16; "PCM_24" -> 24; "PCM_FLOAT" -> 32; else -> error("Unknown PCM encoding") }
    require(channels in 1..2 && channels == decodedChannels && a.long("dataBytes") in 1..audio.sizeBytes)
    val rate = a.int("sampleRateHz")
    val frames = a.long("frames")
    require(a.long("startedAtElapsedRealtimeNs") > 0 &&
        a.long("stoppedAtElapsedRealtimeNs") >= a.long("startedAtElapsedRealtimeNs"))
    val capture = parseSidecarPcm(a.obj("captureTiming"))
    require(capture.frameBytes == channels * (bits / 8))
    if (container == ProxySidecarContainer.WAV) require(a.long("dataBytes") == Math.multiplyExact(frames, capture.frameBytes.toLong()))
    val shared = parseSidecarShared(a.obj("sharedTiming"))
    val videoShared = parseSidecarShared(v.obj("avTiming"))
    // The audio writer legitimately records null videoEncoderFirstPtsUs; it is codec-local,
    // not a source clock. Every other represented source/pause/count field must agree.
    require(shared.copy(videoEncoderFirstPtsUs = null) == videoShared.copy(videoEncoderFirstPtsUs = null)) {
        "Audio and video shared capture timing disagree"
    }
    val observation = ProxySidecarObservation(container, shared, capture, decodedSampleRateHz,
        decodedFrames, rate, frames, videoFirstPtsUs, videoEndUs)
    return ProxySidecarSelection(audio, videoDoc.first, audioDoc.first, observation, proxySidecarTimeline(observation))
}

private class SidecarFields(val json: JsonObject) {
    fun string(key: String): String = json.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
    fun long(key: String): Long = requireNotNull(nullableLong(key)) { "Missing $key" }
    fun int(key: String): Int = Math.toIntExact(long(key))
    fun nullableLong(key: String): Long? = json.getValue(key).let { node ->
        if (node == JsonNull) null else node.jsonPrimitive.let {
            require(!it.isString); val value = requireNotNull(it.longOrNull)
            require(value.toString() == it.content); value
        }
    }
    fun bool(key: String): Boolean = json.getValue(key).jsonPrimitive.let { require(!it.isString); requireNotNull(it.booleanOrNull) }
    fun obj(key: String): JsonObject = json.getValue(key).jsonObject
    fun nullField(key: String) { require(json.getValue(key) == JsonNull) { "Unexpected sidecar $key" } }
}

private fun parseSidecarPcm(json: JsonObject): PcmSourceTimingReport {
    require(json.keys == setOf("policy", "sampleRateHz", "frameBytes", "capturedFrames", "writtenFrames", "durationNs",
        "frameZeroNs", "timestampBacked", "maxResidualNs", "timestampObservations", "unavailableTimestamps",
        "firstTimestampFrame", "firstTimestampNs", "lastTimestampFrame", "lastTimestampNs", "videoAlignmentApplied", "waveformAlignmentVerified"))
    val p = SidecarFields(json)
    require(p.string("policy") == "AUDIORECORD_BOOTTIME_FIXED_SOURCE_EPOCH_V1" && p.bool("timestampBacked"))
    require(!p.bool("videoAlignmentApplied") && !p.bool("waveformAlignmentVerified"))
    val rate = p.int("sampleRateHz")
    val zero = p.long("frameZeroNs")
    val residual = p.long("maxResidualNs")
    val firstFrame = p.long("firstTimestampFrame"); val lastFrame = p.long("lastTimestampFrame")
    val firstNs = p.long("firstTimestampNs"); val lastNs = p.long("lastTimestampNs")
    require(firstFrame >= 0 && lastFrame >= firstFrame && firstNs > 0 && lastNs >= firstNs && residual >= 0 &&
        p.long("timestampObservations") > 0 && p.long("unavailableTimestamps") >= 0)
    require(Math.subtractExact(firstNs, pcmFrameDurationNs(firstFrame, rate)) == zero)
    val lastZero = Math.subtractExact(lastNs, pcmFrameDurationNs(lastFrame, rate))
    require(lastZero >= Math.subtractExact(zero, residual) && lastZero <= Math.addExact(zero, residual))
    return PcmSourceTimingReport(rate, p.int("frameBytes"), p.long("capturedFrames"), p.long("writtenFrames"),
        p.long("durationNs"), AudioCaptureEpoch(zero, true, residual), p.long("timestampObservations"),
        p.long("unavailableTimestamps"), firstFrame, firstNs, lastFrame, lastNs)
}

private fun parseSidecarShared(json: JsonObject): CaptureEpochReport {
    require(json.keys == setOf("audioStorage", "sourceAudioMinusVideoNs", "policy", "cameraRealtime", "videoFrameZeroNs",
        "audioFrameZeroNs", "sharedOriginNs", "audioTimestampBacked", "audioMaxResidualNs", "videoEncoderFirstPtsUs",
        "audioEncoderFirstPtsUs", "audioEncoderDelayFrames", "waveformAlignmentVerified", "submittedPcmFrames", "encodedAudioPackets",
        "aacCalibration", "audioSourceWindow", "audioDrainPaddingFrames", "codecInputFrames", "audioPresentationOffsetUs", "capturedPcmFrames", "sharedPause"))
    val p = SidecarFields(json)
    require(p.string("policy") == "SHARED_BOOTTIME_CAPTURE_ANCHORS" && p.bool("cameraRealtime") && p.bool("audioTimestampBacked"))
    require(!p.bool("waveformAlignmentVerified") && p.long("encodedAudioPackets") == 0L && p.long("audioDrainPaddingFrames") == 0L)
    for (key in listOf("audioEncoderFirstPtsUs", "audioEncoderDelayFrames", "aacCalibration", "audioSourceWindow", "codecInputFrames", "audioPresentationOffsetUs")) p.nullField(key)
    val video = p.long("videoFrameZeroNs"); val audio = p.long("audioFrameZeroNs")
    require(p.long("sourceAudioMinusVideoNs") == Math.subtractExact(audio, video))
    val pause = json.getValue("sharedPause").takeUnless { it == JsonNull }?.jsonObject?.let { node ->
        require(node.keys == setOf("policy", "sampleRateHz", "audioFrameZeroNs", "stopFrame", "capturedPcmFrames",
            "retainedPcmFrames", "lastCommandNs", "lastEffectiveBoundaryNs", "windows"))
        val s = SidecarFields(node)
        require(s.string("policy") == "SHARED_BOOTTIME_PCM_GRID_V1")
        val windows = node.getValue("windows").jsonArray
        require(windows.size <= 10_000)
        SharedCapturePauseReport(s.int("sampleRateHz"), s.long("audioFrameZeroNs"), windows.map { value ->
            require(value.jsonObject.keys == setOf("startFrame", "endFrame"))
            val w = SidecarFields(value.jsonObject)
            CapturePauseWindow(w.long("startFrame"), w.nullableLong("endFrame"))
        }, s.nullableLong("stopFrame"), s.long("capturedPcmFrames"), s.long("retainedPcmFrames"),
            s.nullableLong("lastCommandNs"), s.nullableLong("lastEffectiveBoundaryNs"))
    }
    return CaptureEpochReport(p.string("policy"), true, video, audio, p.long("sharedOriginNs"), true,
        p.long("audioMaxResidualNs"), p.nullableLong("videoEncoderFirstPtsUs"), null, null,
        submittedPcmFrames = p.long("submittedPcmFrames"), capturedPcmFrames = p.long("capturedPcmFrames"),
        sharedPause = pause, audioStorage = p.string("audioStorage"))
}
