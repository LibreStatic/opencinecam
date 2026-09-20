/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Immutable consent snapshot bound when REC is admitted. This record is not live network
 * authorization: current endpoint, consent, connectivity and capture gates still apply to I/O.
 * Publication discovery must never create one on behalf of a previously unenrolled recording.
 */
data class CaptureTransferEnrollment(
    val bundleId: String,
    val endpointId: String,
    val endpointRevision: Long,
    val consentRevision: Long,
) {
    init {
        requireEnrollmentUuid(bundleId)
        requireEnrollmentUuid(endpointId)
        require(endpointRevision >= 0 && consentRevision >= 0)
    }
}

interface CaptureTransferEnrollmentStore {
    fun enroll(value: CaptureTransferEnrollment): CaptureTransferEnrollment
    fun load(bundleId: String): CaptureTransferEnrollment?
}

class CaptureTransferEnrollmentConflict : IllegalStateException("Capture transfer enrollment is already bound")
class CaptureTransferEnrollmentCorruptData : IllegalStateException("Capture transfer enrollment requires recovery")
class CaptureTransferEnrollmentCapacity : IllegalStateException("Capture transfer enrollment storage is full")

internal fun requireEnrollmentUuid(value: String) {
    require(value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false))
}

/** A flat, writer-owned encoding: exact integer Long values, no duplicate keys or coercions. */
internal object CaptureTransferEnrollmentCodec {
    const val MAX_BYTES = 1024

    fun encode(value: CaptureTransferEnrollment): ByteArray = buildJsonObject {
        put("version", 1)
        put("bundleId", value.bundleId)
        put("endpointId", value.endpointId)
        put("endpointRevision", value.endpointRevision)
        put("consentRevision", value.consentRevision)
    }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }

    fun decode(bytes: ByteArray): CaptureTransferEnrollment = try {
        require(bytes.size <= MAX_BYTES)
        val document = bytes.decodeToString(throwOnInvalidSequence = true)
        requireFlatJson(document)
        val json = Json.parseToJsonElement(document).jsonObject
        require(json.keys == setOf("version", "bundleId", "endpointId", "endpointRevision", "consentRevision"))
        val version = json.getValue("version").jsonPrimitive
        require(!version.isString && version.content == "1")
        fun text(key: String): String = json.getValue(key).jsonPrimitive.also { require(it.isString) }.content
        fun revision(key: String): Long = json.getValue(key).jsonPrimitive.let {
            require(!it.isString)
            requireNotNull(it.content.toLongOrNull())
        }
        val value = CaptureTransferEnrollment(text("bundleId"), text("endpointId"), revision("endpointRevision"), revision("consentRevision"))
        // Parsing alone can discard duplicate keys; byte equality enforces a single canonical form.
        require(bytes.contentEquals(encode(value)))
        value
    } catch (_: Exception) {
        throw CaptureTransferEnrollmentCorruptData()
    }

    private fun requireFlatJson(document: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in document) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 1) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
    }
}
