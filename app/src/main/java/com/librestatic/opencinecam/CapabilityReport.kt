/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.CameraInventory
import com.librestatic.opencinecam.core.model.CapabilityReportDocument
import com.librestatic.opencinecam.core.model.QuirkResolution
import com.librestatic.opencinecam.core.model.SchemaEvidence
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The build facts the report carries; the redactor hides the identifying ones in a redacted export. */
data class ReportDeviceFacts(
    val buildFingerprint: String,
    val manufacturer: String,
    val model: String,
    val release: String,
    val sdkInt: Int,
)

/** The audio section before (or without) a probe: nothing is claimed. */
internal fun unknownAudioReport(): JsonObject = buildJsonObject { put("unprocessed", buildJsonObject { put("knowledge", "unknown") }) }

/** Digest of every characteristic read, so two reports from the same firmware compare equal. */
internal fun characteristicsDigest(inventory: List<CameraInventory>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inventory.sortedBy { it.cameraId }.forEach { camera ->
        digest.update("camera:${camera.cameraId}\n".toByteArray())
        camera.entries.sortedBy { it.key }.forEach { digest.update("${it.key}=${it.value}\n".toByteArray()) }
    }
    return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Builds the schema document (`docs/schemas/capability-report.schema.json`) from what the
 * Capabilities page already has: the raw inventory, the negotiated descriptors and the live readback.
 */
internal fun buildCapabilityReport(
    device: ReportDeviceFacts,
    inventory: List<CameraInventory>,
    descriptors: List<Camera2CameraDescriptor>,
    live: CameraUiState? = null,
    codecs: List<JsonObject> = emptyList(),
    audio: JsonObject = unknownAudioReport(),
    apvReasons: List<String> = APV_UNPROBED_REASONS,
    deviceProfiles: Map<String, QuirkResolution> = emptyMap(),
    reportId: String = UUID.randomUUID().toString(),
    createdAt: String = Instant.now().toString(),
): CapabilityReportDocument = CapabilityReportDocument(
    schemaVersion = "1.0.0",
    reportId = reportId,
    createdAt = createdAt,
    device = buildJsonObject {
        put("buildFingerprint", device.buildFingerprint)
        put("manufacturer", device.manufacturer)
        put("model", device.model)
        put("release", device.release)
        put("sdkInt", device.sdkInt)
        put("characteristicsDigest", characteristicsDigest(inventory))
    },
    cameras = inventory.map { camera ->
        buildJsonObject {
            put("cameraId", camera.cameraId)
            camera.logicalParentId?.let { put("logicalParentId", it) }
            camera.lensFacing?.let { put("lensFacing", it) }
            camera.error?.let { put("error", it) }
            put("characteristics", JsonObject(camera.entries.associate { it.key to JsonPrimitive(it.value) }))
            deviceProfiles[camera.cameraId]?.let { put("deviceProfile", deviceProfileJson(it)) }
            descriptors.firstOrNull { it.cameraId == camera.cameraId }?.let { descriptor ->
                put("features", JsonArray(auditCameraCapabilities(descriptor, apvReasons).map { finding ->
                    buildJsonObject {
                        put("feature", finding.feature.name)
                        put("status", finding.status.name)
                        put("detail", finding.detail)
                    }
                }))
            }
            live?.takeIf { it.selectedCameraId == camera.cameraId }?.let { state ->
                put("readback", JsonObject(requestedReportedLines(state).associate { (key, value) -> key to JsonPrimitive(value) }))
            }
        }
    },
    codecs = codecs,
    audio = audio,
    evidence = listOf(
        SchemaEvidence(
            stage = "Advertised",
            status = "pass",
            observedAt = createdAt,
            source = "Camera2CapabilityInventory",
            details = buildJsonObject { put("cameraCount", inventory.size) },
        ),
    ),
)

internal fun capabilityReportJson(document: CapabilityReportDocument): JsonElement =
    Json.encodeToJsonElement(CapabilityReportDocument.serializer(), document)
