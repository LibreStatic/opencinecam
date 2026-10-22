/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.CacheValidityKey
import com.librestatic.opencinecam.core.model.GraphId
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The validity key of the device-wide codec and audio report: the build, a digest of the
 * characteristics and the encoder list, and the protocol and probe versions (PLAN-009).
 */
fun codecAudioValidityKey(buildFingerprint: String, characteristicsDigest: String, encoderNames: List<String>): CacheValidityKey {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(characteristicsDigest.toByteArray())
    encoderNames.sorted().forEach { digest.update("\n$it".toByteArray()) }
    return CacheValidityKey(
        schemaMajor = 1,
        protocolVersion = CODEC_AUDIO_PROTOCOL_VERSION,
        appProbeVersion = CODEC_AUDIO_PROBE_VERSION,
        buildFingerprint = buildFingerprint,
        characteristicsDigest = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) },
        cameraId = "device",
        physicalCameraId = null,
        codecName = null,
        graphId = GraphId("codec-audio-probe"),
    )
}

/** The report's JSON, in the capability-report schema's shape (`codecs` array, `audio` knownValue object). */
object CodecAudioReportJson {
    fun codecs(metadata: CodecAudioMetadata): List<JsonObject> = metadata.codecs.map { codec ->
        buildJsonObject {
            put("name", codec.name)
            put("mime", codec.mime)
            codec.hardwareAccelerated?.let { put("hardwareAccelerated", it) }
            put("profileLevels", JsonArray(codec.profileLevels.map(::JsonPrimitive)))
            put("colorFormats", JsonArray(codec.colorFormats.map(::JsonPrimitive)))
        }
    }

    fun audio(metadata: CodecAudioMetadata): JsonObject = buildJsonObject {
        put("unprocessed", known(metadata.audio.unprocessedSource))
        put("noiseSuppressor", known(metadata.audio.noiseSuppressorAvailable))
        put("automaticGainControl", known(metadata.audio.automaticGainControlAvailable))
        put("acousticEchoCanceler", known(metadata.audio.acousticEchoCancelerAvailable))
    }

    fun decode(codecs: List<JsonElement>, audio: JsonObject): CodecAudioMetadata = CodecAudioMetadata(
        codecs = codecs.map { element ->
            val codec = element.jsonObject
            CodecMetadata(
                name = codec.getValue("name").jsonPrimitive.content,
                mime = codec.getValue("mime").jsonPrimitive.content,
                hardwareAccelerated = codec["hardwareAccelerated"]?.jsonPrimitive?.booleanOrNull,
                profileLevels = codec.getValue("profileLevels").jsonArray.map { it.jsonPrimitive.content },
                colorFormats = codec.getValue("colorFormats").jsonArray.map { requireNotNull(it.jsonPrimitive.intOrNull) },
            )
        },
        audio = AudioMetadata(
            unprocessedSource = value(audio, "unprocessed"),
            noiseSuppressorAvailable = value(audio, "noiseSuppressor"),
            automaticGainControlAvailable = value(audio, "automaticGainControl"),
            acousticEchoCancelerAvailable = value(audio, "acousticEchoCanceler"),
        ),
    )

    private fun known(value: Boolean?): JsonObject = buildJsonObject {
        put("knowledge", if (value == null) "unknown" else "known")
        if (value != null) put("value", value)
    }

    private fun value(audio: JsonObject, key: String): Boolean? =
        audio.getValue(key).jsonObject["value"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.booleanOrNull
}

/** Where a [CodecAudioReportCache] keeps its report between runs. */
interface CodecAudioReportStore {
    fun load(): CachedCodecAudioReport?
    fun save(report: CachedCodecAudioReport)
    fun delete()
}

/** The key and the report as one JSON document; [decode] answers null for anything it cannot trust. */
object CodecAudioReportCodec {
    fun encode(report: CachedCodecAudioReport): String {
        val key = report.validityKey
        return Json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("validityKey", buildJsonObject {
                put("schemaMajor", key.schemaMajor)
                put("protocolVersion", key.protocolVersion)
                put("appProbeVersion", key.appProbeVersion)
                put("buildFingerprint", key.buildFingerprint)
                put("characteristicsDigest", key.characteristicsDigest)
                put("cameraId", key.cameraId)
                key.physicalCameraId?.let { put("physicalCameraId", it) }
                key.codecName?.let { put("codecName", it) }
                put("graphId", key.graphId.value)
            })
            put("codecs", JsonArray(CodecAudioReportJson.codecs(report.metadata)))
            put("audio", CodecAudioReportJson.audio(report.metadata))
        })
    }

    fun decode(text: String): CachedCodecAudioReport? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        val key = root.getValue("validityKey").jsonObject
        fun string(name: String) = key.getValue(name).jsonPrimitive.content
        CachedCodecAudioReport(
            CacheValidityKey(
                schemaMajor = requireNotNull(key.getValue("schemaMajor").jsonPrimitive.intOrNull),
                protocolVersion = string("protocolVersion"),
                appProbeVersion = string("appProbeVersion"),
                buildFingerprint = string("buildFingerprint"),
                characteristicsDigest = string("characteristicsDigest"),
                cameraId = string("cameraId"),
                physicalCameraId = key["physicalCameraId"]?.jsonPrimitive?.contentOrNull,
                codecName = key["codecName"]?.jsonPrimitive?.contentOrNull,
                graphId = GraphId(string("graphId")),
            ),
            CodecAudioReportJson.decode(root.getValue("codecs").jsonArray, root.getValue("audio").jsonObject),
        )
    }.getOrNull()
}
