/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val SUPPORTED_MAJOR = 1
private const val SUPPORTED_MINOR = 0

@Serializable
data class SchemaEvidence(
    val stage: String,
    val status: String,
    val observedAt: String,
    val source: String? = null,
    val details: JsonObject? = null,
)

@Serializable
data class CapabilityReportDocument(
    val schemaVersion: String,
    val reportId: String,
    val createdAt: String,
    val device: JsonObject,
    val cameras: List<JsonObject>,
    val codecs: List<JsonObject>,
    val audio: JsonObject,
    val evidence: List<SchemaEvidence>,
)

@Serializable
data class ClipSidecarDocument(
    val schemaVersion: String,
    val recordingId: String,
    val createdAt: String,
    val intent: JsonObject,
    val resolvedGraph: JsonObject,
    val events: List<JsonObject>,
    val validation: List<SchemaEvidence>,
)

@Serializable
data class DeviceProfileDocument(
    val schemaVersion: String,
    val profileId: String,
    val createdAt: String,
    val scope: JsonObject,
    val validity: JsonObject,
    val configurations: List<JsonObject>,
)

@Serializable
data class QuirkDocument(
    val schemaVersion: String,
    val quirkId: String,
    val scope: JsonObject,
    val effect: JsonObject,
    val evidence: List<SchemaEvidence>,
)

@Serializable
data class BenchmarkResultDocument(
    val schemaVersion: String,
    val benchmarkId: String,
    val createdAt: String,
    val destination: JsonObject,
    val bytesWritten: Long,
    val windows: List<JsonObject>,
    val requiredBytesPerSecond: Long? = null,
    val outcome: String,
)

@Serializable
data class ValidationResultDocument(
    val schemaVersion: String,
    val validationId: String,
    val createdAt: String,
    val subject: JsonObject,
    val protocolVersion: String,
    val checks: List<JsonObject>,
    val finalStage: String,
)

enum class SchemaCompatibility {
    EXACT_OR_OLDER_MINOR,
    NEWER_MINOR,
}

class UnsupportedSchemaMajor(message: String) : IllegalArgumentException(message)

data class ParsedSchemaVersion(val major: Int, val minor: Int, val patch: Int) {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "schema version components must be non-negative" }
    }
}

fun parseSchemaVersion(raw: String): ParsedSchemaVersion {
    val parts = raw.split('.')
    require(parts.size == 3 && parts.all { it.toIntOrNull() != null }) {
        "schema version must be semantic major.minor.patch"
    }
    return ParsedSchemaVersion(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
}

fun checkSchemaCompatibility(raw: String): SchemaCompatibility {
    val version = parseSchemaVersion(raw)
    if (version.major != SUPPORTED_MAJOR) {
        throw UnsupportedSchemaMajor(
            "schema major ${version.major} is unsupported; expected $SUPPORTED_MAJOR",
        )
    }
    return if (version.minor > SUPPORTED_MINOR) {
        SchemaCompatibility.NEWER_MINOR
    } else {
        SchemaCompatibility.EXACT_OR_OLDER_MINOR
    }
}

/** Canonical JSON settings: additive unknown fields are tolerated, majors are checked explicitly. */
val canonicalJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    prettyPrint = true
}

inline fun <reified T> decodeCanonical(raw: String): T {
    val element = canonicalJson.parseToJsonElement(raw)
    val version = (element as? JsonObject)?.get("schemaVersion")
        ?: error("schemaVersion is required")
    val versionText = (version as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?: error("schemaVersion must be a string")
    checkSchemaCompatibility(versionText)
    return canonicalJson.decodeFromString(element.toString())
}

inline fun <reified T> encodeCanonical(value: T): String = canonicalJson.encodeToString(value)
