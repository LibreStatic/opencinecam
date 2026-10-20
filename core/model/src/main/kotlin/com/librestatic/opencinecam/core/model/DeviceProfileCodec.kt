/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The outcome of reading one device profile document (`docs/schemas/device-profile.schema.json`). */
sealed interface DeviceProfileDecoding {
    data class Decoded(val profile: DeviceProfile) : DeviceProfileDecoding

    data class Invalid(val reason: String) : DeviceProfileDecoding
}

/**
 * Reads a profile document into the model. Fields the model does not use (`createdAt`,
 * `configurations`, additive extras) are ignored; a malformed or unsupported document is reported,
 * never partially applied.
 */
object DeviceProfileCodec {
    fun decode(text: String): DeviceProfileDecoding = try {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return invalid("profile must be a JSON object")
        val scope = root.obj("scope")
        val validity = root.obj("validity")
        val profile = DeviceProfile(
            schemaVersion = root.string("schemaVersion"),
            profileId = root.string("profileId"),
            source = root.string("source"),
            trust = ProfileTrust.entries.firstOrNull { it.name == root.string("trust") }
                ?: throw IllegalArgumentException("unknown trust label '${root.string("trust")}'"),
            scope = DeviceProfileScope(
                device = scope.string("device"),
                buildFingerprint = scope.string("buildFingerprint"),
                cameraId = scope.string("cameraId"),
                physicalCameraId = scope.optionalString("physicalCameraId"),
                codecName = scope.optionalString("codecName"),
                protocolVersion = validity.string("protocolVersion"),
                characteristicsDigest = validity.optionalString("characteristicsDigest"),
            ),
            signature = (root["signature"] as? JsonObject)?.let { signature ->
                ProfileSignature(
                    algorithm = signature.string("algorithm"),
                    keyId = signature.string("keyId"),
                    digestHex = signature.string("digest"),
                )
            },
            quirks = (root["quirks"] as? JsonArray).orEmpty().map { element ->
                val quirk = element as? JsonObject ?: throw IllegalArgumentException("a quirk must be a JSON object")
                DeviceProfileQuirk(
                    quirkId = quirk.string("quirkId"),
                    effect = quirk.string("effect"),
                    enabled = (quirk["enabled"] as? JsonPrimitive)?.booleanOrNull
                        ?: throw IllegalArgumentException("quirk 'enabled' must be a boolean"),
                )
            },
        )
        DeviceProfileDecoding.Decoded(profile)
    } catch (failure: SerializationException) {
        invalid(failure.message ?: "malformed JSON")
    } catch (failure: IllegalArgumentException) {
        // Also covers UnsupportedSchemaMajor and every model invariant.
        invalid(failure.message ?: "invalid profile")
    }

    private fun invalid(reason: String) = DeviceProfileDecoding.Invalid(reason)

    private fun JsonObject.obj(name: String): JsonObject =
        this[name] as? JsonObject ?: throw IllegalArgumentException("'$name' must be an object")

    private fun JsonObject.string(name: String): String =
        optionalString(name) ?: throw IllegalArgumentException("'$name' must be a string")

    private fun JsonObject.optionalString(name: String): String? {
        val element: JsonElement = this[name] ?: return null
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
